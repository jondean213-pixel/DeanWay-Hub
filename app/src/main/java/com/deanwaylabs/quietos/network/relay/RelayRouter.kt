package com.deanwaylabs.quietos.network.relay

import android.util.Base64
import com.deanwaylabs.quietos.network.pairing.PairedDevice
import com.deanwaylabs.quietos.network.spamshield.CallContext
import com.deanwaylabs.quietos.network.spamshield.EventSender
import com.deanwaylabs.quietos.network.spamshield.MessageContext
import com.deanwaylabs.quietos.network.spamshield.RelayDecision
import com.deanwaylabs.quietos.network.spamshield.SpamShield
import com.deanwaylabs.quietos.network.spamshield.Verdict
import com.deanwaylabs.quietos.network.transport.NetworkNode
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cloud relay — the forgotten-phone case.
 *
 * Phone B sits at home on the house Wi-Fi taking Jon's calls. Jon is out
 * with the Motorola. When Phone B gets a missed call or text, SPAM SHIELD
 * filters it:
 *
 *     CLEAN   -> relay to the Motorola now
 *     SUSPECT -> relay, flagged so Jon decides
 *     SPAM    -> NEVER leaves the home phone
 *
 * RelayRouter picks the path: if the peer is reachable over the local
 * Wi-Fi socket transport, the event goes direct; otherwise it is sealed
 * into a signed cloud envelope and queued in the outbox for push delivery.
 *
 * Cloud envelope format (v1) — JSON, exactly these fields:
 *     {
 *       "v": 1,
 *       "msg_id": "<uuid4 hex>",          // dedup key across local+cloud
 *       "from": "<sender device_id>",
 *       "to": "<recipient device_id>",
 *       "key_id": "<channel key fingerprint>",  // receiver rejects mismatch
 *       "event": "missed_call" | "sms" | "email_alert",
 *       "payload_enc": "<base64 AES-256-GCM(nonce||ct)>",
 *       "ts": 1728660000.0,               // unix time, sender clock
 *       "sig": "<HMAC-SHA256 hex>"        // over canonical JSON of the rest
 *     }
 *
 * The payload is AES-256-GCM encrypted with the pairing channel key
 * (bare nonce||ciphertext body, 12-byte nonce, base64-encoded). "sig" is
 * HMAC-SHA256 over the canonical JSON (sort_keys, no whitespace) of every
 * field except "sig" itself, keyed by a relay subkey derived from the
 * channel key. Tampered envelopes fail HMAC; wrong-key envelopes fail on
 * key_id and HMAC both.
 *
 * Firebase/push delivery itself is future work — RelayRouter queues
 * sealed envelopes in its outbox and hands them to a pushSend hook when
 * one is configured. The wire format above is what the push payload must
 * carry, so the phone side can be built against it now.
 *
 * Ported from the Python bench (quietos/network/relay.py), 2026-10-11.
 */

// -- relay event types --------------------------------------------------
const val MISSED_CALL = "missed_call"
const val SMS = "sms"
const val EMAIL_ALERT = "email_alert"

const val ENVELOPE_VERSION = 1

// Retry backoff for the cloud outbox (seconds): 1m, 5m, 15m, 1h, capped.
private val RETRY_BACKOFF = doubleArrayOf(60.0, 300.0, 900.0, 3600.0)

// How long the dedup window remembers a delivered msg_id (seconds).
private const val DEDUP_WINDOW = 24 * 3600.0

private const val NONCE_BYTES = 12
private const val GCM_TAG_BITS = 128

class EnvelopeError(message: String) : Exception(message)

// -- crypto ---------------------------------------------------------------

private fun relayHmacKey(channelKey: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(channelKey, "HmacSHA256"))
    return mac.doFinal("quietos-relay/hmac-v1".toByteArray(Charsets.UTF_8))
}

/** Canonical JSON bytes of an envelope (everything except "sig"). */
private fun canonical(env: JSONObject): ByteArray {
    // Sort keys, no whitespace — matches Python's sort_keys + separators.
    val sorted = JSONObject()
    val keys = mutableListOf<String>()
    val it = env.keys()
    while (it.hasNext()) {
        val k = it.next()
        if (k != "sig") keys.add(k)
    }
    keys.sorted().forEach { k -> sorted.put(k, env.get(k)) }
    return sorted.toString().toByteArray(Charsets.UTF_8)
}

private fun hmacHex(key: ByteArray, data: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data).joinToString("") { "%02x".format(it) }
}

private fun encryptPayload(key: ByteArray, payload: JSONObject): ByteArray {
    val plaintext = payload.toString().toByteArray(Charsets.UTF_8)
    val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
        GCMParameterSpec(GCM_TAG_BITS, nonce))
    return nonce + cipher.doFinal(plaintext)
}

private fun decryptPayload(key: ByteArray, blob: ByteArray): JSONObject {
    if (blob.size < NONCE_BYTES + 16) throw EnvelopeError("payload too short")
    val nonce = blob.copyOfRange(0, NONCE_BYTES)
    val ct = blob.copyOfRange(NONCE_BYTES, blob.size)
    val plaintext = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.doFinal(ct)
    } catch (e: Exception) {
        throw EnvelopeError("payload decrypt failed: ${e.message}")
    }
    return JSONObject(plaintext.toString(Charsets.UTF_8))
}

// -- relay events ---------------------------------------------------------

/** One filtered event ready to relay to the carried phone. */
data class RelayEvent(
    val eventId: String,
    val eventType: String,   // MISSED_CALL / SMS / EMAIL_ALERT
    val payload: JSONObject,
    val verdict: Verdict,
    val reasons: List<String> = emptyList(),
    val ts: Double = System.currentTimeMillis() / 1000.0
) {
    val flagged: Boolean get() = verdict == Verdict.SUSPECT
}

fun missedCallEvent(
    number: String,
    verdict: Verdict,
    reasons: List<String> = emptyList(),
    calledAt: Double = System.currentTimeMillis() / 1000.0
): RelayEvent {
    val payload = JSONObject()
    payload.put("number", number)
    payload.put("called_at", calledAt)
    return RelayEvent(
        eventId = UUID.randomUUID().toString().replace("-", ""),
        eventType = MISSED_CALL, payload = payload,
        verdict = verdict, reasons = reasons
    )
}

fun smsEvent(
    sender: String,
    body: String,
    verdict: Verdict,
    reasons: List<String> = emptyList(),
    receivedAt: Double = System.currentTimeMillis() / 1000.0
): RelayEvent {
    val payload = JSONObject()
    payload.put("sender", sender)
    payload.put("body", body)
    payload.put("received_at", receivedAt)
    return RelayEvent(
        eventId = UUID.randomUUID().toString().replace("-", ""),
        eventType = SMS, payload = payload,
        verdict = verdict, reasons = reasons
    )
}

/**
 * Email alerts have no spam classifier yet; the caller assigns the
 * verdict (default CLEAN).
 */
fun emailAlertEvent(
    sender: String,
    subject: String,
    snippet: String,
    verdict: Verdict = Verdict.CLEAN,
    reasons: List<String> = emptyList()
): RelayEvent {
    val payload = JSONObject()
    payload.put("sender", sender)
    payload.put("subject", subject)
    payload.put("snippet", snippet)
    return RelayEvent(
        eventId = UUID.randomUUID().toString().replace("-", ""),
        eventType = EMAIL_ALERT, payload = payload,
        verdict = verdict, reasons = reasons
    )
}

// -- cloud envelope --------------------------------------------------------

/**
 * Seal a RelayEvent into a signed cloud envelope (v1 format).
 *
 * Payload is AES-256-GCM encrypted with the pairing channel key;
 * the whole envelope (minus sig) is HMAC-SHA256 signed with the
 * relay subkey.
 */
fun sealEnvelope(
    device: PairedDevice,
    toDeviceId: String,
    event: RelayEvent
): JSONObject {
    val inner = JSONObject(event.payload.toString())  // copy
    inner.put("spamshield_verdict", event.verdict.value)
    val reasonsArray = org.json.JSONArray()
    event.reasons.forEach { reasonsArray.put(it) }
    inner.put("spamshield_reasons", reasonsArray)
    inner.put("spamshield_flag", event.flagged)
    inner.put("event_ts", event.ts)

    val env = JSONObject()
    env.put("v", ENVELOPE_VERSION)
    env.put("msg_id", event.eventId)
    env.put("from", device.deviceId)
    env.put("to", toDeviceId)
    env.put("key_id", device.keyId)
    env.put("event", event.eventType)
    env.put("payload_enc", Base64.encodeToString(
        encryptPayload(device.channelKey, inner), Base64.NO_WRAP))
    env.put("ts", event.ts)
    env.put("sig", hmacHex(
        relayHmacKey(device.channelKey), canonical(env)))
    return env
}

/**
 * Verify and decrypt a cloud envelope.
 * Returns (eventType, payload). Throws EnvelopeError on bad version,
 * key_id mismatch, bad signature, or undecryptable payload.
 */
fun openEnvelope(
    device: PairedDevice,
    envelope: JSONObject
): Pair<String, JSONObject> {
    if (envelope.optInt("v", -1) != ENVELOPE_VERSION) {
        throw EnvelopeError("unsupported envelope version")
    }
    if (envelope.optString("key_id") != device.keyId) {
        throw EnvelopeError("key_id mismatch: not paired with sender")
    }
    val sig = envelope.optString("sig", "")
    val expect = hmacHex(relayHmacKey(device.channelKey), canonical(envelope))
    if (!MessageDigest.isEqual(sig.toByteArray(), expect.toByteArray())) {
        throw EnvelopeError("bad signature: envelope tampered or wrong key")
    }
    val payload = try {
        val blob = Base64.decode(envelope.getString("payload_enc"), Base64.DEFAULT)
        decryptPayload(device.channelKey, blob)
    } catch (e: EnvelopeError) {
        throw e
    } catch (e: Exception) {
        throw EnvelopeError("payload decode failed: ${e.message}")
    }
    return envelope.getString("event") to payload
}

// -- outbox ---------------------------------------------------------------

data class OutboxEntry(
    val msgId: String,
    val toDeviceId: String,
    val envelope: JSONObject,
    val queuedAt: Double,
    var nextRetryAt: Double,
    var attempts: Int = 0
)

// -- router ---------------------------------------------------------------

/**
 * Decides local vs cloud for each filtered relay event.
 *
 * Phone B constructs one with its SpamShield and (optionally) its
 * live NetworkNode. Inbound calls/texts are classified and routed:
 *
 *     routeEvent() -> "local" | "cloud" | "held"
 *
 * SPAM is always "held" — it never leaves the home phone, local or
 * cloud. Cloud-queued envelopes sit in the outbox until pushSend
 * accepts them or they are acked.
 */
class RelayRouter(
    private val device: PairedDevice,
    private val shield: SpamShield,
    private val node: NetworkNode? = null,
    /** Returns true when the push service accepted the envelope. */
    pushSend: ((JSONObject) -> Boolean)? = null,
    private val onRelayEvent: ((String, JSONObject, String) -> Unit)? = null
) {
    /** Swappable push hook — NetworkManager sets the Firebase transport here. */
    var pushSend: ((JSONObject) -> Boolean)? = pushSend
    private val outbox = mutableMapOf<String, OutboxEntry>()
    private val seen = mutableMapOf<String, Double>()  // msg_id -> first-seen
    private val lock = Any()

    // -- reachability -----------------------------------------------------

    /** True when the peer is on the local Wi-Fi socket transport. */
    fun localReachable(deviceId: String): Boolean {
        val n = node ?: return false
        return try {
            deviceId in n.peerIds
        } catch (_: Exception) {
            false
        }
    }

    // -- inbound (home phone side) ----------------------------------------

    /** Classify an inbound call and route it. Returns (decision, route). */
    fun handleInboundCall(
        ctx: CallContext,
        toDeviceId: String
    ): Pair<RelayDecision, String> {
        val cls = shield.classifyCall(ctx)
        val event = missedCallEvent(ctx.number, cls.verdict, cls.reasons)
        return SpamShield.relayFor(cls.verdict) to routeEvent(event, toDeviceId)
    }

    /** Classify an inbound SMS and route it. Returns (decision, route). */
    fun handleInboundSms(
        ctx: MessageContext,
        toDeviceId: String
    ): Pair<RelayDecision, String> {
        val cls = shield.classifyMessage(ctx)
        val event = smsEvent(ctx.sender, ctx.body, cls.verdict, cls.reasons)
        return SpamShield.relayFor(cls.verdict) to routeEvent(event, toDeviceId)
    }

    /** Route an email alert with a caller-assigned verdict. */
    fun handleInboundEmail(
        sender: String,
        subject: String,
        snippet: String,
        toDeviceId: String,
        verdict: Verdict = Verdict.CLEAN,
        reasons: List<String> = emptyList()
    ): Pair<RelayDecision, String> {
        val event = emailAlertEvent(sender, subject, snippet, verdict, reasons)
        return SpamShield.relayFor(verdict) to routeEvent(event, toDeviceId)
    }

    // -- routing ----------------------------------------------------------

    /** Route one filtered event. Returns "local", "cloud", or "held". */
    fun routeEvent(event: RelayEvent, toDeviceId: String): String {
        synchronized(lock) {
            if (event.verdict == Verdict.SPAM) return "held"
            if (localReachable(toDeviceId)) {
                sendLocal(event, toDeviceId)
                markSeen(event.eventId)
                return "local"
            }
            queueCloud(event, toDeviceId)
            return "cloud"
        }
    }

    private fun sendLocal(event: RelayEvent, toDeviceId: String) {
        val n = node ?: return
        val payload = JSONObject(event.payload.toString())
        payload.put("relay_msg_id", event.eventId)
        payload.put("spamshield_verdict", event.verdict.value)
        val reasonsArray = org.json.JSONArray()
        event.reasons.forEach { reasonsArray.put(it) }
        payload.put("spamshield_reasons", reasonsArray)
        payload.put("spamshield_flag", event.flagged)
        // The socket transport broadcasts; the paired key handshake means
        // only the paired peer can read it. toDeviceId is recorded for
        // the cloud path and for logging.
        n.sendEvent(event.eventType, payload)
    }

    private fun queueCloud(event: RelayEvent, toDeviceId: String): String {
        val envelope = sealEnvelope(device, toDeviceId, event)
        val now = System.currentTimeMillis() / 1000.0
        outbox[event.eventId] = OutboxEntry(
            msgId = event.eventId, toDeviceId = toDeviceId,
            envelope = envelope, queuedAt = now, nextRetryAt = now
        )
        flushOutbox()
        markSeen(event.eventId)
        return event.eventId
    }

    // -- outbox -----------------------------------------------------------

    /**
     * Try to push everything due. Hook returns true when the push
     * service accepted the envelope.
     */
    private fun flushOutbox() {
        val send = pushSend ?: return
        val now = System.currentTimeMillis() / 1000.0
        synchronized(lock) {
            for (entry in outbox.values.toList()) {
                if (entry.nextRetryAt > now) continue
                entry.attempts++
                val accepted = try {
                    send(entry.envelope)
                } catch (_: Exception) {
                    false
                }
                if (accepted) {
                    outbox.remove(entry.msgId)
                } else {
                    val backoff = RETRY_BACKOFF[
                        minOf(entry.attempts - 1, RETRY_BACKOFF.size - 1)]
                    entry.nextRetryAt = now + backoff
                }
            }
        }
    }

    /** Entries whose retry timer has fired (also triggers a flush). */
    fun retryDue(now: Double = System.currentTimeMillis() / 1000.0): List<OutboxEntry> {
        flushOutbox()
        synchronized(lock) {
            return outbox.values.filter { it.nextRetryAt <= now }
        }
    }

    /** The push service confirmed delivery — drop from the outbox. */
    fun cloudAcked(msgId: String): Boolean {
        synchronized(lock) {
            return outbox.remove(msgId) != null
        }
    }

    fun outboxPending(): List<OutboxEntry> {
        synchronized(lock) {
            return outbox.values.toList()
        }
    }

    // -- receiving (carried phone side) -----------------------------------

    private fun markSeen(msgId: String) {
        val now = System.currentTimeMillis() / 1000.0
        synchronized(lock) {
            seen[msgId] = now
            val cutoff = now - DEDUP_WINDOW
            seen.keys.filter { seen[it]!! < cutoff }.forEach { seen.remove(it) }
        }
    }

    fun alreadySeen(msgId: String): Boolean {
        synchronized(lock) {
            return msgId in seen
        }
    }

    /**
     * Handle an event arriving over the socket transport. Returns
     * true if delivered (false when it was a duplicate).
     */
    fun receiveLocal(
        eventType: String,
        payload: JSONObject,
        fromDevice: String
    ): Boolean {
        val msgId = payload.optString("relay_msg_id", "")
            .ifEmpty { payload.optString("msg_id", "") }
        if (msgId.isNotEmpty() && alreadySeen(msgId)) return false
        if (msgId.isNotEmpty()) markSeen(msgId)
        deliver(eventType, payload, fromDevice)
        return true
    }

    /**
     * Verify, decrypt, and deliver a cloud envelope. Returns true
     * if delivered; false when it was a duplicate. Throws
     * EnvelopeError on verification failure.
     */
    fun receiveCloudEnvelope(envelope: JSONObject): Boolean {
        val (eventType, payload) = openEnvelope(device, envelope)
        val msgId = envelope.optString("msg_id", "")
        if (msgId.isNotEmpty() && alreadySeen(msgId)) return false
        if (msgId.isNotEmpty()) markSeen(msgId)
        deliver(eventType, payload, envelope.optString("from", ""))
        return true
    }

    private fun deliver(
        eventType: String,
        payload: JSONObject,
        fromDevice: String
    ) {
        onRelayEvent?.invoke(eventType, payload, fromDevice)
    }
}
