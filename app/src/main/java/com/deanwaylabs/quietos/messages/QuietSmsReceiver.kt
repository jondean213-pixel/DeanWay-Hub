package com.deanwaylabs.quietos.messages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import com.deanwaylabs.quietos.MainActivity
import com.deanwaylabs.quietos.network.relay.RelayFeed
import com.deanwaylabs.quietos.network.spamshield.RelayDecision

class QuietSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }.trim()
        val timestamp = messages.minOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()

        val prefs = context.getSharedPreferences("quietos_message_spam", Context.MODE_PRIVATE)
        val protectionEnabled = prefs.getBoolean("enabled", false)
        val decision = MessageSpamRiskEngine().classify(sender, body)

        if (protectionEnabled && decision.suppressNotification) {
            MessageSpamLog(context).add(
                timestampMs = timestamp,
                sender = sender,
                text = body,
                risk = decision.risk.name,
                reason = decision.reason,
                suppressed = true
            )
            return
        }

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sender)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        runCatching { context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) }

        // Network relay: if this is the home phone (Phone B), SPAM SHIELD
        // decides whether the carried phone should see this text.
        // Runs on a background thread — the shield does contact lookups.
        if (RelayFeed.isRelayMode(context)) {
            Thread {
                try {
                    val decision = RelayFeed.onInboundSms(context, sender, body)
                    // HOLD = spam, stays home. RELAY/RELAY_FLAGGED already sent.
                    if (decision == RelayDecision.HOLD) {
                        // Optionally log: spam held on home phone.
                    }
                } catch (_: Exception) { /* relay must never crash SMS */ }
            }.apply { isDaemon = true; start() }
        }

        notifyCleanMessage(context, sender, body)
    }

    private fun notifyCleanMessage(context: Context, sender: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "quietos_sms"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "QuietOS Messages", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val launch = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(sender.ifBlank { "New message" })
            .setContentText(body.take(160))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify((sender + body).hashCode(), notification) }
    }
}
