package com.deanwaylabs.quietos.calls

import android.content.Context

/** One screened incoming call: when, what number, the risk verdict, why. */
data class ScreenedCall(
    val timeMs: Long,
    val number: String,
    val risk: String,
    val reason: String
)

class CallScreeningLog(context: Context) {
    private val prefs = context.getSharedPreferences("quietos_call_screening", Context.MODE_PRIVATE)

    fun record(number: String, decision: CallDecision) {
        val old = prefs.getString("history", "").orEmpty()
        // Full number is stored (private app storage) so the SPAM SHIELD
        // workspace can show it and offer a Block button. Jon asked for the visual.
        val line = java.lang.System.currentTimeMillis().toString() + "|" +
            number.replace("|", "") + "|" + decision.risk + "|" + decision.reason
        prefs.edit().putString("history", (line + "\n" + old).lineSequence().take(100).joinToString("\n")).apply()
    }

    fun readAll(): List<ScreenedCall> =
        prefs.getString("history", "").orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 4) return@mapNotNull null
            ScreenedCall(
                timeMs = parts[0].toLongOrNull() ?: 0L,
                number = parts[1],
                risk = parts[2],
                reason = parts.drop(3).joinToString("|")
            )
        }.toList()
}
