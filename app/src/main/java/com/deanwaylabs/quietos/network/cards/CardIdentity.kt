package com.deanwaylabs.quietos.network.cards

import android.content.Context
import com.deanwaylabs.quietos.network.relay.FirebaseRelay
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * The pairing ceremony, app half — APP-CARD-ROUTE-DESIGN §5.1.
 *
 * The app generates its Keystore keypair and publishes
 * quietos-cards/{deviceId}/identity = {pubkey, fingerprint,
 * fcm_token, updated_at}. The fingerprint is shown on the setup
 * screen (the value Jon eyeballs against the host pane) — the host
 * pins NOTHING until Jon taps "Confirm" here, which republishes the
 * identity with `confirm` = the fingerprint. That tap is the ceremony:
 * before it, the host publishes no cards; after it, a swapped pubkey
 * is refused forever (the pin lives host-side, mode 0600).
 *
 * The deviceId is the app's existing paired-device id (the
 * NetworkManager prefs, "quietos_network"/"device_id") — the same id
 * Jon sets host-side as QUIETOS_CARD_DEVICE_ID.
 */
object CardIdentity {

    private const val PREFS = "quietos_cards"
    private const val KEY_CONFIRMED_FP = "confirmed_fingerprint"

    fun deviceId(context: Context): String? =
        context.getSharedPreferences("quietos_network", Context.MODE_PRIVATE)
            .getString("device_id", null)?.ifEmpty { null }

    fun isConfirmed(context: Context): Boolean =
        confirmedFingerprint(context) == CardCrypto.fingerprint()

    fun confirmedFingerprint(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONFIRMED_FP, null)

    /** Jon's tap on the setup screen. From here the host may pin. */
    fun confirmHere(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIRMED_FP, CardCrypto.fingerprint())
            .apply()
    }

    fun identityRef(context: Context, database: FirebaseDatabase =
        FirebaseDatabase.getInstance()) =
        deviceId(context)?.let {
            database.getReference("quietos-cards").child(it).child("identity")
        }

    /**
     * Publish (or refresh) the identity record. `confirm` is included
     * only after [confirmHere]; an unconfirmed publish presents the
     * key for the ceremony without arming anything.
     */
    suspend fun publishIdentity(
        context: Context,
        database: FirebaseDatabase = FirebaseDatabase.getInstance()
    ): Boolean {
        val ref = identityRef(context, database) ?: return false
        if (!FirebaseRelay.ensureAuth()) return false
        return try {
            val token = try {
                FirebaseMessaging.getInstance().token.await()
            } catch (e: Exception) {
                null
            }
            val record = mutableMapOf<String, Any>(
                "pubkey" to CardCrypto.publicKeyB64(),
                "fingerprint" to CardCrypto.fingerprint(),
                "updated_at" to System.currentTimeMillis() / 1000
            )
            if (token != null) record["fcm_token"] = token
            if (isConfirmed(context)) record["confirm"] = CardCrypto.fingerprint()
            ref.setValue(record).await()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** FCM token rotation: refresh just the token child (called from
     *  RelayMessagingService.onNewToken). Best effort — a missed
     *  rotation only weakens the ding, never the card. */
    suspend fun publishToken(
        context: Context,
        token: String,
        database: FirebaseDatabase = FirebaseDatabase.getInstance()
    ): Boolean {
        val ref = identityRef(context, database) ?: return false
        if (!FirebaseRelay.ensureAuth()) return false
        return try {
            ref.child("fcm_token").setValue(token).await()
            true
        } catch (e: Exception) {
            false
        }
    }
}
