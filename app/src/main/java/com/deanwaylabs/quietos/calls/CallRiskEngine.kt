package com.deanwaylabs.quietos.calls

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

enum class CallRisk { TRUSTED, UNKNOWN, SUSPICIOUS, BLOCKED }

data class CallDecision(val risk: CallRisk, val silence: Boolean, val block: Boolean, val reason: String)

class CallRiskEngine(private val context: Context) {
    fun classify(number: String?): CallDecision {
        val n = number?.trim().orEmpty()
        if (n.isBlank()) return CallDecision(CallRisk.SUSPICIOUS, true, false, "hidden or unavailable caller ID")
        if (isContact(n)) return CallDecision(CallRisk.TRUSTED, false, false, "saved contact")
        return CallDecision(CallRisk.UNKNOWN, true, false, "unknown caller; silenced")
    }
    private fun isContact(number: String): Boolean = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)
}
