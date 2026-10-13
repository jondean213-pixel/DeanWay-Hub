package com.deanwaylabs.quietos.calls

import android.content.Context

/**
 * Jon's manual call blocklist. Numbers Jon blocks from the SPAM SHIELD
 * workspace are rejected outright by [CallRiskEngine] on future calls.
 * An explicit block always wins — it is checked before the contacts trust.
 */
class BlocklistStore(context: Context) {
    private val prefs = context.getSharedPreferences("quietos_blocklist", Context.MODE_PRIVATE)

    companion object {
        /** Normalize for comparison: digits only, drop a leading US "1" on 11-digit numbers. */
        fun normalize(number: String?): String {
            var d = number.orEmpty().filter { it.isDigit() }
            if (d.length == 11 && d.startsWith("1")) d = d.substring(1)
            return d
        }
    }

    fun isBlocked(number: String?): Boolean {
        val n = normalize(number)
        return n.isNotEmpty() && prefs.getStringSet("numbers", emptySet()).orEmpty().contains(n)
    }

    fun add(number: String?): Boolean {
        val n = normalize(number)
        if (n.isEmpty()) return false
        val set = prefs.getStringSet("numbers", emptySet()).orEmpty().toMutableSet()
        val added = set.add(n)
        if (added) prefs.edit().putStringSet("numbers", set).apply()
        return added
    }

    fun remove(number: String?): Boolean {
        val n = normalize(number)
        if (n.isEmpty()) return false
        val set = prefs.getStringSet("numbers", emptySet()).orEmpty().toMutableSet()
        val removed = set.remove(n)
        if (removed) prefs.edit().putStringSet("numbers", set).apply()
        return removed
    }

    fun all(): List<String> = prefs.getStringSet("numbers", emptySet()).orEmpty().sorted()
}
