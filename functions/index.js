/**
 * QuietOS card tickle — APP-CARD-ROUTE-DESIGN §3.3 / §4.
 *
 * The ONLY server component of the app card route. When the host
 * publishes a card under quietos-cards/{deviceId}/cards/{cardId},
 * this sends ONE high-priority, data-only FCM message to the token
 * the app registered at quietos-cards/{deviceId}/identity/fcm_token.
 * The message carries NO content — the app fetches the encrypted card
 * itself. It is a doorbell, not a channel: no decision ever travels
 * through FCM.
 *
 * Deploy (Jon's step — needs Firebase CLI auth + the Blaze plan for
 * outbound FCM from functions; see DEPLOY notes in
 * ~/workspace/sentinel/docs/APP-CARD-ROUTE-DESIGN.md §8 risk 4):
 *   firebase deploy --only functions,database
 *
 * Written 2026-10-23. NOT deployed.
 */
const { onValueCreated } = require("firebase-functions/v2/database");
const { getDatabase } = require("firebase-admin/database");
const { getMessaging } = require("firebase-admin/messaging");
const { initializeApp } = require("firebase-admin/app");

initializeApp();

exports.cardTickle = onValueCreated(
  "/quietos-cards/{deviceId}/cards/{cardId}",
  async (event) => {
    const deviceId = event.params.deviceId;
    const cardId = event.params.cardId;
    const db = getDatabase();
    const tokenSnap = await db
      .ref(`quietos-cards/${deviceId}/identity/fcm_token`)
      .get();
    const token = tokenSnap.val();
    if (!token || typeof token !== "string") {
      console.log(`cardTickle ${cardId}: no fcm_token for ${deviceId}`);
      return;
    }
    try {
      await getMessaging().send({
        token,
        // "kind" matches the app's RelayMessagingService convention
        // (relay_tick / card_tick share the service).
        data: { kind: "card_tick", card_id: cardId },
        android: { priority: "high" },
      });
      console.log(`cardTickle ${cardId}: tick sent`);
    } catch (err) {
      // A stale token must not retry-storm: log and drop. The card
      // itself is safe in RTDB; the app's WorkManager backstop and
      // hub-open refresh still surface it (design §4 degradation:
      // later and visible, never silent).
      console.log(`cardTickle ${cardId}: send failed: ${err.code || err}`);
    }
  }
);
