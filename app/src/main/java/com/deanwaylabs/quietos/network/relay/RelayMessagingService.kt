package com.deanwaylabs.quietos.network.relay

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM service for QuietOS Network relay wake-up.
 *
 * v1 role: infrastructure. The Realtime Database listener
 * (FirebaseRelay) is the primary inbound path and works whenever
 * the app process is alive. This service handles FCM data messages
 * so a future server-side sender (Cloud Function) can wake the
 * carried phone when the app was killed.
 *
 * Message format (data-only, no notification payload — the app
 * decides how to alert via the Attention Token):
 *     { "kind": "relay_tick", "msg_id": "<envelope id>" }
 *
 * On tickle, the service nudges the FirebaseRelay listener (which
 * re-checks the inbox on next start) — the DB listener itself
 * delivers anything waiting.
 */
class RelayMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val kind = message.data["kind"] ?: return
        if (kind != "relay_tick") return
        // The DB listener picks up the actual envelope; the tickle
        // just ensures we check. If NetworkManager is alive its
        // listener is already attached — nothing more to do here.
        // (Future: start a foreground relay-check service if needed.)
    }

    override fun onNewToken(token: String) {
        // FCM registration token refreshed. v1 doesn't send FCM
        // directly device-to-device (needs a server key), so the
        // token is logged for future server-side use.
        // Stored in prefs for the day Jon wires a Cloud Function.
        getSharedPreferences("quietos_network", MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .putLong("fcm_token_at", System.currentTimeMillis())
            .apply()
    }
}
