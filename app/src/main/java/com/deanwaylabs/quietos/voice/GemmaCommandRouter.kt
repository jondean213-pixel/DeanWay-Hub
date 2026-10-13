package com.deanwaylabs.quietos.voice

/** V1 voice/text command intents for the Gemma workspace. */
enum class VoiceIntent { BLOCK, UNBLOCK, CALL, UNKNOWN }

data class VoiceCommand(val intent: VoiceIntent, val target: String, val raw: String)

/**
 * Parses a spoken/typed utterance into a [VoiceCommand].
 *
 * Deterministic keyword patterns run first (instant, no model needed).
 * An optional LLM parse callback handles free-form utterances the
 * patterns miss; it must return one line like
 * "INTENT: block | TARGET: 8125551234", or null when unavailable.
 * Anything unparseable becomes [VoiceIntent.UNKNOWN] — never a guess.
 */
class GemmaCommandRouter(
    private val llmParse: (suspend (String) -> String?)? = null
) {
    suspend fun parse(text: String): VoiceCommand {
        val raw = text.trim()
        if (raw.isEmpty()) return VoiceCommand(VoiceIntent.UNKNOWN, "", text)
        keywordParse(raw)?.let { return it }
        val parse = llmParse ?: return VoiceCommand(VoiceIntent.UNKNOWN, "", text)
        val out = runCatching { parse(raw) }.getOrNull()?.trim().orEmpty()
        return parseLlmLine(out, text) ?: VoiceCommand(VoiceIntent.UNKNOWN, "", text)
    }

    private fun keywordParse(raw: String): VoiceCommand? {
        val t = raw.lowercase()
        fun restOf(prefix: String): String =
            t.removePrefix(prefix).trim().trimStart(',', ':', '-', ' ').trim()
        return when {
            t.startsWith("unblock ") || t == "unblock" ->
                VoiceCommand(VoiceIntent.UNBLOCK, restOf("unblock"), raw)
            t.startsWith("block ") || t == "block" ->
                VoiceCommand(VoiceIntent.BLOCK, restOf("block"), raw)
            t.startsWith("ban ") || t == "ban" ->
                VoiceCommand(VoiceIntent.BLOCK, restOf("ban"), raw)
            t.startsWith("call ") || t == "call" ->
                VoiceCommand(VoiceIntent.CALL, restOf("call"), raw)
            t.startsWith("dial ") || t == "dial" ->
                VoiceCommand(VoiceIntent.CALL, restOf("dial"), raw)
            t.startsWith("phone ") || t == "phone" ->
                VoiceCommand(VoiceIntent.CALL, restOf("phone"), raw)
            t.startsWith("ring ") || t == "ring" ->
                VoiceCommand(VoiceIntent.CALL, restOf("ring"), raw)
            else -> null
        }
    }

    private fun parseLlmLine(line: String, raw: String): VoiceCommand? {
        if (line.isEmpty()) return null
        val m = Regex("""INTENT:\s*(\w+)\s*\|\s*TARGET:\s*(.*)""",
            RegexOption.IGNORE_CASE).find(line) ?: return null
        val intent = when (m.groupValues[1].lowercase()) {
            "block" -> VoiceIntent.BLOCK
            "unblock" -> VoiceIntent.UNBLOCK
            "call", "dial", "phone", "ring" -> VoiceIntent.CALL
            else -> VoiceIntent.UNKNOWN
        }
        return VoiceCommand(intent, m.groupValues[2].trim(), raw)
    }
}
