package com.deanwaylabs.quietos.network.spamshield

import org.json.JSONObject

/**
 * SPAM SHIELD — call/message spam classifier for the QuietOS Network.
 *
 * Phone B sits at home taking the calls. SPAM SHIELD decides what is
 * worth relaying to the phone Jon is carrying and what stays home:
 *
 *     CLEAN   -> RELAY          (contacts, VIPs, known-good numbers)
 *     SUSPECT -> RELAY_FLAGGED  (unknown, odd hour, one weak signal — Jon decides)
 *     SPAM    -> HOLD           (never leaves the home phone)
 *
 * Rule-based v1: no models, no network lookups. Jon's VIPs (Noah Dean,
 * Vanessa Brzeski, his doctors) always come through — the shield never
 * blocks them.
 *
 * The shield plugs into the socket transport: Phone B classifies each
 * incoming call/text, then calls shieldedSend(), which relays through
 * the NetworkNode only when the verdict warrants it.
 *
 * Ported from the Python bench (quietos/network/spamshield.py), 2026-10-11.
 * Enabled on Jon's Motorola per his 2026-10-11 decision.
 */

/** What SPAM SHIELD thinks of an inbound call or message. */
enum class Verdict(val value: String) {
    CLEAN("clean"),      // known-good: relay it
    SUSPECT("suspect"),  // uncertain: relay flagged so Jon decides
    SPAM("spam");        // junk: hold it on the home phone

    companion object {
        fun fromValue(value: String): Verdict =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown verdict: $value")
    }
}

/** What Phone B does with the event after classification. */
enum class RelayDecision(val value: String) {
    RELAY("relay"),                  // send to the other phone now
    RELAY_FLAGGED("relay_flagged"),  // send, marked suspect
    HOLD("hold");                    // stays on the home phone
}

// Jon's standing VIPs (call-pipeline v1, 2026-10-07): Noah Dean,
// Vanessa Brzeski, Jon's doctors. Names live here so config stays readable.
val VIP_NAMES = listOf("Noah Dean", "Vanessa Brzeski", "Jon's doctors")

// US toll-free area codes — unknown toll-free callers are SUSPECT,
// never outright SPAM (banks, airlines, and doctors use them too).
private val TOLL_FREE_CODES = setOf(
    "800", "888", "877", "866", "855", "844", "833", "822"
)

// Business hours (local): unknown callers outside these are SUSPECT.
private const val BUSINESS_START = 8
private const val BUSINESS_END = 21  // 9 PM

// Message spam signals (matched case-insensitively against the body).
private val SPAM_PHRASES = listOf(
    "you've won", "you won", "you are a winner", "congratulations you",
    "claim your prize", "claim your reward", "lottery", "sweepstakes",
    "free cruise", "free vacation", "free money", "get rich",
    "act now", "urgent action required", "immediate action",
    "final notice", "last chance",
    "wire transfer", "western union", "gift card", "itunes card",
    "verify your account", "account suspended", "account locked",
    "update your payment", "payment failed",
    "social security", "arrest warrant", "irs",
    "click the link below", "click here to claim"
)

// Link shorteners / shady TLDs commonly abused in smishing.
private val SHADY_LINK_BITS = listOf(
    "bit.ly", "tinyurl", "t.co", "goo.gl", "ow.ly", "is.gd",
    "buff.ly", ".xyz", ".top", ".click", ".loan"
)

// Common TLDs — an unknown sender dropping any link at all is SUSPECT.
private val LINK_TLDS = listOf(
    ".com", ".net", ".org", ".io", ".co", ".info", ".biz", ".us",
    ".me", ".app", ".link"
)

private fun hasLink(body: String): Boolean {
    if ("http" in body || "www." in body) return true
    return LINK_TLDS.any { it in body }
}

/**
 * Strip a phone number down to digits, dropping a leading US '1'.
 * Returns "" for anything that has no usable digits.
 */
fun normalizeNumber(raw: String?): String {
    if (raw.isNullOrEmpty()) return ""
    var digits = raw.filter { it.isDigit() }
    if (digits.length == 11 && digits.startsWith("1")) {
        digits = digits.substring(1)
    }
    return digits
}

private fun areaCode(digits: String): String =
    if (digits.length >= 10) digits.substring(0, 3) else ""

/** Numbers that cannot be real callers. */
private fun looksInvalid(digits: String): Boolean {
    if (digits.isEmpty() || digits.length < 7) return true
    if (digits.toSet().size == 1) return true  // 0000000000, 1111111111, ...
    if (digits.startsWith("000") || digits.startsWith("111")) return true
    return false
}

/** 555-01xx numbers are reserved fictional — treat as suspicious. */
private fun looksFictional(digits: String): Boolean =
    digits.length == 10 && digits.substring(3, 6) == "555" &&
        digits.substring(6, 8) == "01"

/** Everything the shield knows about one inbound call. */
data class CallContext(
    val number: String,
    val inContacts: Boolean = false,
    val previouslyCalledByJon: Boolean = false,  // Jon dialed them before
    val previouslyReceived: Boolean = false,     // they called before
    val hour: Int = 12                           // local hour, 0-23
)

/** Everything the shield knows about one inbound SMS. */
data class MessageContext(
    val sender: String,
    val body: String = "",
    val inContacts: Boolean = false,
    val isServiceNumber: Boolean = false  // bank alerts, carrier, 2FA, etc.
)

data class Classification(
    val verdict: Verdict,
    val reasons: List<String> = emptyList()
)

/**
 * Rule-based call/message classifier. No models, no lookups.
 * Configure once with Jon's numbers; classify every inbound event.
 */
class SpamShield(
    vipNumbers: Collection<String> = emptyList(),
    blockedPrefixes: Collection<String> = emptyList()
) {
    private val vipNumbers: Set<String> =
        vipNumbers.map { normalizeNumber(it) }.filter { it.isNotEmpty() }.toSet()
    private val blockedPrefixes: List<String> = blockedPrefixes.toList()

    // -- calls ----------------------------------------------------------

    fun classifyCall(ctx: CallContext): Classification {
        val number = normalizeNumber(ctx.number)

        // VIPs always come through, any hour. The shield never blocks them.
        if (number.isNotEmpty() && number in vipNumbers) {
            return Classification(Verdict.CLEAN, listOf("vip allowlist"))
        }
        if (ctx.inContacts) {
            return Classification(Verdict.CLEAN, listOf("in contacts"))
        }
        if (ctx.previouslyCalledByJon) {
            return Classification(Verdict.CLEAN, listOf("jon called them before"))
        }
        if (looksInvalid(number)) {
            return Classification(Verdict.SPAM,
                listOf("invalid number: '${ctx.number}'"))
        }
        for (prefix in blockedPrefixes) {
            if (number.startsWith(normalizeNumber(prefix))) {
                return Classification(Verdict.SPAM,
                    listOf("blocked prefix $prefix"))
            }
        }
        if (areaCode(number) in TOLL_FREE_CODES) {
            return Classification(Verdict.SUSPECT,
                listOf("unknown toll-free caller"))
        }
        if (looksFictional(number)) {
            return Classification(Verdict.SUSPECT, listOf("fictional 555 number"))
        }
        if (ctx.hour !in BUSINESS_START until BUSINESS_END) {
            return Classification(Verdict.SUSPECT,
                listOf("unknown caller outside business hours"))
        }
        if (ctx.previouslyReceived) {
            return Classification(Verdict.SUSPECT,
                listOf("called before, not in contacts"))
        }
        return Classification(Verdict.SUSPECT, listOf("unknown number"))
    }

    // -- messages -------------------------------------------------------

    fun classifyMessage(ctx: MessageContext): Classification {
        val sender = normalizeNumber(ctx.sender)

        if (sender.isNotEmpty() && sender in vipNumbers) {
            return Classification(Verdict.CLEAN, listOf("vip allowlist"))
        }
        if (ctx.inContacts) {
            return Classification(Verdict.CLEAN, listOf("in contacts"))
        }
        if (ctx.isServiceNumber) {
            return Classification(Verdict.CLEAN, listOf("known service number"))
        }

        val body = ctx.body.lowercase()
        val hits = SPAM_PHRASES.filter { it in body }
        val shady = SHADY_LINK_BITS.filter { it in body }
        val reasons = mutableListOf<String>()

        if (hits.size >= 2 || (hits.isNotEmpty() && shady.isNotEmpty())) {
            reasons.add("spam phrases: ${hits.take(3).joinToString(", ")}")
            if (shady.isNotEmpty()) reasons.add("shady link: ${shady[0]}")
            return Classification(Verdict.SPAM, reasons)
        }
        if (hits.isNotEmpty() || shady.isNotEmpty() || looksInvalid(sender)) {
            if (hits.isNotEmpty()) reasons.add("spam phrase: ${hits[0]}")
            if (shady.isNotEmpty()) reasons.add("shady link: ${shady[0]}")
            if (looksInvalid(sender)) reasons.add("invalid sender")
            return Classification(Verdict.SUSPECT, reasons)
        }
        if (hasLink(body)) {
            return Classification(Verdict.SUSPECT,
                listOf("unknown sender with link"))
        }
        return Classification(Verdict.CLEAN, listOf("no spam signals"))
    }

    // -- relay ----------------------------------------------------------

    companion object {
        /** Map a verdict to what Phone B does with the event. */
        fun relayFor(verdict: Verdict): RelayDecision = when (verdict) {
            Verdict.CLEAN -> RelayDecision.RELAY
            Verdict.SUSPECT -> RelayDecision.RELAY_FLAGGED
            Verdict.SPAM -> RelayDecision.HOLD
        }
    }
}

// -- transport plug-in --------------------------------------------------

const val CALL_EVENT = "missed_call"
const val MESSAGE_EVENT = "sms"

/** Minimal send-event interface so the shield doesn't depend on NetworkNode directly. */
fun interface EventSender {
    fun sendEvent(eventType: String, payload: JSONObject)
}

/**
 * Classify an inbound call/text and relay it only when warranted.
 *
 * kind is "call" or "message"; ctx is a CallContext or MessageContext.
 * On RELAY / RELAY_FLAGGED the event goes out through the sender
 * (SUSPECT payloads carry a spamshield flag); on HOLD nothing is sent.
 *
 * Returns the RelayDecision so the caller can log it.
 */
fun shieldedSend(
    shield: SpamShield,
    sender: EventSender,
    kind: String,
    ctx: Any,
    eventType: String? = null,
    payload: Map<String, Any?>? = null
): RelayDecision {
    val (classification, etype, base) = when (kind) {
        "call" -> {
            val c = ctx as CallContext
            Triple(shield.classifyCall(c), eventType ?: CALL_EVENT,
                mapOf("number" to c.number, "hour" to c.hour))
        }
        "message" -> {
            val c = ctx as MessageContext
            Triple(shield.classifyMessage(c), eventType ?: MESSAGE_EVENT,
                mapOf("sender" to c.sender, "body" to c.body))
        }
        else -> throw IllegalArgumentException(
            "kind must be 'call' or 'message', got '$kind'")
    }

    val decision = SpamShield.relayFor(classification.verdict)
    if (decision == RelayDecision.HOLD) return decision

    val out = JSONObject()
    for ((k, v) in base) out.put(k, v)
    payload?.forEach { (k, v) -> out.put(k, v) }
    out.put("spamshield_verdict", classification.verdict.value)
    val reasonsArray = org.json.JSONArray()
    classification.reasons.forEach { reasonsArray.put(it) }
    out.put("spamshield_reasons", reasonsArray)
    if (decision == RelayDecision.RELAY_FLAGGED) {
        out.put("spamshield_flag", true)
    }
    sender.sendEvent(etype, out)
    return decision
}
