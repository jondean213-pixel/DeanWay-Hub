package com.deanwaylabs.quietos.network.relay

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.deanwaylabs.quietos.network.NetworkManager
import com.deanwaylabs.quietos.network.spamshield.CallContext
import com.deanwaylabs.quietos.network.spamshield.MessageContext
import com.deanwaylabs.quietos.network.spamshield.RelayDecision
import java.util.Calendar

/**
 * Feeds live phone events (calls, SMS) into SPAM SHIELD and the relay.
 *
 * This is what makes Phone B the "home phone": it takes the calls and
 * texts, SPAM SHIELD decides what's worth relaying, and the RelayRouter
 * sends CLEAN/SUSPECT to the carried phone (local socket or cloud).
 * SPAM never leaves the device.
 *
 * Relay mode is a per-device setting (Network Hub toggle). Only the
 * home phone (Phone B) should have it on — the carried phone doesn't
 * relay its own inbound events.
 */
object RelayFeed {

    private const val PREFS = "quietos_network"
    private const val KEY_RELAY_MODE = "relay_mode_enabled"

    /** True when this device acts as the home relay phone. */
    fun isRelayMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RELAY_MODE, false)

    fun setRelayMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RELAY_MODE, enabled)
            .apply()
    }

    /**
     * Feed an inbound call. Call this when a call is missed (not
     * answered). Returns the relay decision, or null if relay mode
     * is off or the device isn't paired.
     */
    fun onMissedCall(context: Context, number: String): RelayDecision? {
        if (!isRelayMode(context)) return null
        val manager = NetworkManager.get(context)
        val router = manager.relay ?: return null
        // Address to the paired phone: live peer ID if on local Wi-Fi,
        // else the last-seen ID so the cloud envelope is addressed right.
        val toDevice = manager.pairedPeerId() ?: return null

        val ctx = CallContext(
            number = number,
            inContacts = isContact(context, number),
            previouslyCalledByJon = false,  // TODO: check call log
            previouslyReceived = false,      // TODO: check call log
            hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        )
        val (decision, route) = router.handleInboundCall(ctx, toDevice)
        return decision
    }

    /**
     * Feed an inbound SMS. Returns the relay decision, or null if
     * relay mode is off or the device isn't paired.
     */
    fun onInboundSms(
        context: Context,
        sender: String,
        body: String
    ): RelayDecision? {
        if (!isRelayMode(context)) return null
        val manager = NetworkManager.get(context)
        val router = manager.relay ?: return null
        val toDevice = manager.pairedPeerId() ?: return null

        val ctx = MessageContext(
            sender = sender,
            body = body,
            inContacts = isContact(context, sender),
            isServiceNumber = isServiceNumber(sender)
        )
        val (decision, route) = router.handleInboundSms(ctx, toDevice)
        return decision
    }

    private fun isContact(context: Context, number: String): Boolean {
        if (number.isBlank()) return false
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    /** Short codes (5-6 digits) are usually service numbers. */
    private fun isServiceNumber(sender: String): Boolean {
        val digits = sender.filter { it.isDigit() }
        return digits.length in 3..6
    }
}
