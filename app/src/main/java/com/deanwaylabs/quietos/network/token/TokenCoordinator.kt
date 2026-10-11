package com.deanwaylabs.quietos.network.token

/**
 * The Attention Token — cross-device interrupt coordination.
 *
 * For any given event, exactly ONE device owns the right to interrupt Jon.
 * The rest digest quietly. Bucket decides urgency, token decides location.
 *
 * Lifecycle (Jon's locked v2 decisions, 2026-10-08):
 *     CLAIM   — a device takes the token for an event (nobody live holds it)
 *     LEASE   — held with a TTL (default 60s), renewed by heartbeat
 *     YIELD   — device hands off voluntarily (Jon picked up his phone)
 *     EXPIRE  — owner silent past TTL; token becomes claimable again.
 *               A dead device can NEVER swallow an emergency.
 *
 * Routing rule:
 *     NOW + live owner   -> interrupt on the owner, and ONLY the owner
 *     NOW + no live owner -> escalate per governor rules, NEVER silently drop
 *     SOON / DIGEST      -> digest quietly, no interrupt
 *     QUIET / SPAM       -> nothing leaves the device
 *
 * The token is PER-EVENT (event id), not global — Jon's phone can be
 * ringing about the missed call while his computer digests the newsletter.
 *
 * Ported from the Python bench (quietos/network/token.py), 2026-10-11.
 * All 255 bench tests passing; this is the Android production port.
 */

/** Attention buckets. Same five Jon uses everywhere. */
enum class Bucket(val value: String) {
    NOW("now"),          // interrupt-worthy
    SOON("soon"),        // digest quietly
    DIGEST("digest"),    // digest quietly
    QUIET("quiet"),      // stays on the device
    SPAM("spam");        // never leaves the house

    companion object {
        fun fromValue(value: String): Bucket =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown bucket: $value")
    }
}

const val DEFAULT_LEASE_SECONDS = 60.0

/** Routing decision for an event on the fleet. */
sealed class RouteAction {
    /** NOW event, live owner: interrupt THERE only. */
    data class Interrupt(val ownerDeviceId: String) : RouteAction()
    /** NOW event, nobody live: escalate per governor rules, never drop. */
    object Escalate : RouteAction()
    /** SOON/DIGEST: quiet digest, no interrupt. */
    object Digest : RouteAction()
    /** QUIET/SPAM: stays put, relays nothing. */
    object Quiet : RouteAction()
}

private data class Token(
    val eventId: String,
    val owner: String,
    val bucket: Bucket,
    var expiresAt: Double,
    val leaseSeconds: Double = DEFAULT_LEASE_SECONDS,
    var claims: Int = 1
)

/**
 * Owns token state for a fleet of paired devices.
 *
 * All time goes through the injected clock so tests can fast-forward
 * past TTLs without sleeping.
 */
class TokenCoordinator(
    private val leaseSeconds: Double = DEFAULT_LEASE_SECONDS,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val audit: ((String, Map<String, String>) -> Unit)? = null
) {
    private val tokens = mutableMapOf<String, Token>()
    private val lock = Any()

    private fun now(): Double = clock()

    private fun log(event: String, vararg fields: Pair<String, String>) {
        audit?.invoke(event, mapOf(*fields))
    }

    /**
     * Return the token if it exists and has not expired, else null.
     * Expiry is lazy: a token past its TTL is dropped the moment anyone
     * looks at it, and the drop is audited as an expiry.
     */
    private fun live(eventId: String): Token? {
        val tok = tokens[eventId] ?: return null
        if (now() >= tok.expiresAt) {
            tokens.remove(eventId)
            log("token_expired",
                "event_id" to eventId,
                "owner" to tok.owner,
                "bucket" to tok.bucket.value)
            return null
        }
        return tok
    }

    /** Try to take the token for an event. True = granted. */
    fun claim(eventId: String, deviceId: String, bucket: Bucket): Boolean {
        synchronized(lock) {
            val tok = live(eventId)
            if (tok == null) {
                tokens[eventId] = Token(
                    eventId = eventId,
                    owner = deviceId,
                    bucket = bucket,
                    expiresAt = now() + leaseSeconds,
                    leaseSeconds = leaseSeconds
                )
                log("token_claimed",
                    "event_id" to eventId,
                    "owner" to deviceId,
                    "bucket" to bucket.value)
                return true
            }
            if (tok.owner == deviceId) {
                // Re-claim by the owner is a heartbeat, not a conflict.
                return heartbeat(eventId, deviceId)
            }
            return false
        }
    }

    /** Renew the lease. False if this device doesn't hold the token. */
    fun heartbeat(eventId: String, deviceId: String): Boolean {
        synchronized(lock) {
            val tok = live(eventId)
            if (tok == null || tok.owner != deviceId) return false
            tok.expiresAt = now() + tok.leaseSeconds
            log("token_heartbeat", "event_id" to eventId, "owner" to deviceId)
            return true
        }
    }

    /** Voluntary handoff. False if this device doesn't hold the token. */
    fun yieldToken(eventId: String, deviceId: String): Boolean {
        synchronized(lock) {
            val tok = live(eventId)
            if (tok == null || tok.owner != deviceId) return false
            tokens.remove(eventId)
            log("token_yielded",
                "event_id" to eventId,
                "owner" to deviceId,
                "bucket" to tok.bucket.value)
            return true
        }
    }

    /** Current live owner device id, or null. */
    fun owner(eventId: String): String? {
        synchronized(lock) {
            return live(eventId)?.owner
        }
    }

    /**
     * Decide what happens for an event on the fleet.
     *
     *     Interrupt(owner) — NOW event, live owner: ring THERE only
     *     Escalate         — NOW event, nobody live: escalate, never drop
     *     Digest           — SOON/DIGEST: quiet digest, no interrupt
     *     Quiet            — QUIET/SPAM: stays put, relays nothing
     */
    fun route(eventId: String, bucket: Bucket): RouteAction {
        synchronized(lock) {
            if (bucket == Bucket.NOW) {
                val liveOwner = live(eventId)?.owner
                if (liveOwner != null) {
                    return RouteAction.Interrupt(liveOwner)
                }
                log("token_no_owner_escalated",
                    "event_id" to eventId,
                    "bucket" to bucket.value)
                return RouteAction.Escalate
            }
            if (bucket == Bucket.SOON || bucket == Bucket.DIGEST) {
                return RouteAction.Digest
            }
            return RouteAction.Quiet
        }
    }

    /** For diagnostics: snapshot of live (unexpired) token owners. */
    fun liveTokens(): Map<String, String> {
        synchronized(lock) {
            // Touch each to trigger lazy expiry, then snapshot survivors.
            val ids = tokens.keys.toList()
            ids.forEach { live(it) }
            return tokens.mapValues { it.value.owner }
        }
    }
}
