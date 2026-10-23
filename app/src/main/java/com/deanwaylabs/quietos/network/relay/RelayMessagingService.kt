package com.deanwaylabs.quietos.network.relay

import com.deanwaylabs.quietos.network.cards.CardIdentity
import com.deanwaylabs.quietos.network.cards.CardRelay
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        when (message.data["kind"]) {
            "relay_tick" -> {
                // The DB listener picks up the actual envelope; the
                // tickle just ensures we check. If NetworkManager is
                // alive its listener is already attached — nothing
                // more to do here.
            }
            "card_tick" -> {
                // App card route (APP-CARD-ROUTE-DESIGN §4 layer 2):
                // a card was published. The tick carries NO content —
                // sweep pulls the encrypted card from RTDB and the
                // notification layer decides the ding. This is the
                // path that wakes a killed app.
                val app = applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    CardRelay.sweep(app)
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        // FCM registration token refreshed. Stored in prefs (relay v1
        // bookkeeping) AND published to the card identity record,
        // where the cardTickle Cloud Function reads it — a rotated
        // token must not silence the doorbell.
        getSharedPreferences("quietos_network", MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .putLong("fcm_token_at", System.currentTimeMillis())
            .apply()
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            CardIdentity.publishToken(app, token)
        }
    }
}
