package com.deanwaylabs.quietos.network.cards

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The ding — APP-CARD-ROUTE-DESIGN §6.1.
 *
 * One high-importance channel ("quietos_cards"), one notification
 * per pending card (id = card id hash — re-showing updates in place,
 * never stacks). Heads-up + sound + vibration; the notification
 * expires with the card (setTimeoutAfter the remaining TTL). The
 * body decrypts for the preview; actions are text-only ("Approve /
 * Deny", "Release / Kill") because an icon-only button is exactly
 * the mis-tap this design refuses.
 *
 * Tapping the body opens the hub deep-linked to the card's verbatim
 * view. Actions fire CardDecisionReceiver — the signed decision,
 * never a UI shortcut.
 */
object CardNotification {

    const val CHANNEL_ID = "quietos_cards"
    const val EXTRA_CARD_ID = "quietos_card_id"
    private const val PREFS = "quietos_cards"
    private const val KEY_SHOWN = "shown_card_ids"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Decision cards",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "QuietOS needs your call: governor approvals " +
                "and Sentinel holds, with approve/deny on the card."
            enableVibration(true)
        }
        mgr.createNotificationChannel(channel)
    }

    fun notificationId(cardId: String): Int = cardId.hashCode()

    fun show(context: Context, card: CardRecord) {
        if (!card.isPending) return
        ensureChannel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (alreadyShown(context, card.id)) return
        try {
            val preview = card.body?.text
                ?.replace(Regex("\\s+"), " ")
                ?.take(140)
                ?: card.title
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(card.title)
                .setContentText(preview)
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentIntent(openCardIntent(context, card.id))
            val remainingMs = (card.expiresAtSec - CardRelay.nowSec()) * 1000
            if (remainingMs > 0) builder.setTimeoutAfter(remainingMs)
            // No decision actions on an undecryptable card: the ding
            // still fires (visible, never silent) and the tap opens
            // the hub's honest error view — but nobody can approve
            // what they cannot read (Jon, 2026-10-23).
            if (card.body != null) {
                for ((label, verb) in actionLabels(card.kind)) {
                    builder.addAction(
                        0, label,
                        decisionIntent(context, card.id, verb)
                    )
                }
            }
            NotificationManagerCompat.from(context)
                .notify(notificationId(card.id), builder.build())
            markShown(context, card.id)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted: the in-hub stack still
            // carries the card (setup screen nags for the grant).
        }
    }

    fun cancel(context: Context, cardId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(cardId))
        unmarkShown(context, cardId)
    }

    /** (label, wire verb) pairs, kind-true per design §6.4. The
     *  verbs on the wire never change; only the words do. */
    fun actionLabels(kind: String): List<Pair<String, String>> =
        if (kind == "sentinel_hold") {
            listOf("Release" to "approve", "Kill" to "deny")
        } else {
            listOf("Approve" to "approve", "Deny" to "deny")
        }

    private fun openCardIntent(context: Context, cardId: String): PendingIntent {
        val intent = Intent()
            .setClassName(
                context.packageName,
                "com.deanwaylabs.quietos.network.NetworkHubActivity"
            )
            .putExtra(EXTRA_CARD_ID, cardId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, cardId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun decisionIntent(
        context: Context,
        cardId: String,
        verb: String
    ): PendingIntent {
        val intent = Intent(context, CardDecisionReceiver::class.java)
            .setAction("com.deanwaylabs.quietos.CARD_DECISION")
            .putExtra(EXTRA_CARD_ID, cardId)
            .putExtra(CardDecisionReceiver.EXTRA_VERB, verb)
        return PendingIntent.getBroadcast(
            context, (cardId + verb).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun alreadyShown(context: Context, cardId: String): Boolean =
        shownSet(context).contains(cardId)

    private fun markShown(context: Context, cardId: String) {
        val set = shownSet(context) + cardId
        prefs(context).edit().putStringSet(KEY_SHOWN, set).apply()
    }

    private fun unmarkShown(context: Context, cardId: String) {
        val set = shownSet(context) - cardId
        prefs(context).edit().putStringSet(KEY_SHOWN, set).apply()
    }

    private fun shownSet(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SHOWN, emptySet()) ?: emptySet()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
