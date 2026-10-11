package com.deanwaylabs.quietos.network.relay

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog

/**
 * Watches the call log for new MISSED calls and feeds them to the relay.
 *
 * Phone B sits at home taking Jon's calls. When one goes unanswered,
 * this observer fires, SPAM SHIELD classifies the number, and the
 * RelayRouter sends CLEAN/SUSPECT to the carried phone. SPAM stays home.
 *
 * Requires READ_CALL_LOG. Only active when relay mode is on.
 */
class MissedCallObserver(
    private val context: Context,
    private val onMissedCall: (String) -> Unit = { number ->
        Thread {
            try {
                RelayFeed.onMissedCall(context, number)
            } catch (_: Exception) { /* relay must never crash the observer */ }
        }.apply { isDaemon = true; start() }
    }
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private var lastSeenId: Long = -1L

    init {
        // Baseline: don't relay calls that were already missed before
        // the observer started.
        lastSeenId = latestCallId() ?: -1L
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        checkForMissed()
    }

    private fun checkForMissed() {
        val (id, number, type) = latestMissed() ?: return
        if (id <= lastSeenId) return
        lastSeenId = id
        if (type == CallLog.Calls.MISSED_TYPE && number.isNotBlank()) {
            onMissedCall(number)
        }
    }

    private fun latestCallId(): Long? {
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                null, null,
                "${CallLog.Calls._ID} DESC LIMIT 1"
            )?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        }.getOrNull()
    }

    private fun latestMissed(): Triple<Long, String, Int>? {
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE
                ),
                null, null,
                "${CallLog.Calls._ID} DESC LIMIT 1"
            )?.use { c ->
                if (c.moveToFirst()) {
                    Triple(
                        c.getLong(0),
                        c.getString(1).orEmpty(),
                        c.getInt(2)
                    )
                } else null
            }
        }.getOrNull()
    }

    companion object {
        /** Register the observer. Returns it for later unregister. */
        fun register(context: Context): MissedCallObserver {
            val obs = MissedCallObserver(context.applicationContext)
            context.applicationContext.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                obs
            )
            return obs
        }
    }
}
