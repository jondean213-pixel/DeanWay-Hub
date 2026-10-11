package com.deanwaylabs.quietos.network.relay

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Firebase cloud relay transport for the QuietOS Network.
 *
 * The forgotten-phone path: Phone B sits at home, Jon carries the
 * Motorola on another network. Phone B seals filtered events into
 * signed envelopes (AES-256-GCM + HMAC-SHA256, see RelayRouter) and
 * this transport carries them through Firebase Realtime Database.
 *
 * Layout in the database:
 *     quietos-relay/{toDeviceId}/{msgId} = { envelope JSON }
 *
 * Security properties:
 * - Firebase only ever sees ciphertext. The payload is AES-256-GCM
 *   encrypted with the pairing channel key BEFORE it touches the
 *   network; the envelope is HMAC-signed with the relay subkey.
 * - Database rules (see FIREBASE_RULES.md) restrict each device to
 *   writing under its own paired peer's inbox and reading only its
 *   own inbox. Anonymous auth gives each install a stable UID.
 * - The receiver deletes each envelope after verified delivery, so
 *   the inbox doesn't grow.
 *
 * Outbound: RelayRouter calls pushSend(envelope); this writes it to
 * the recipient's inbox and returns true on success.
 *
 * Inbound: startListening() attaches a child listener on this device's
 * inbox. Each new envelope goes through RelayRouter.receiveCloudEnvelope
 * (HMAC verify → decrypt → dedup → deliver), then is deleted.
 */
class FirebaseRelay(
    private val router: RelayRouter,
    private val ownDeviceId: String,
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    private val inboxRef: DatabaseReference =
        database.getReference("quietos-relay").child(ownDeviceId)

    private var listener: ChildEventListener? = null
    private var listening = false

    /**
     * Push one sealed envelope to the recipient's cloud inbox.
     * Called by RelayRouter as its pushSend hook.
     * Returns true when Firebase accepted the write.
     */
    suspend fun pushEnvelope(envelope: JSONObject): Boolean {
        return try {
            val toDevice = envelope.optString("to", "")
            val msgId = envelope.optString("msg_id", "")
            if (toDevice.isEmpty() || msgId.isEmpty()) return false
            val ref = database.getReference("quietos-relay")
                .child(toDevice)
                .child(msgId)
            // Store the envelope as a JSON string — keeps types intact.
            ref.setValue(envelope.toString()).await()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Blocking-friendly wrapper for the router's pushSend hook. */
    fun pushSendBlocking(envelope: JSONObject): Boolean {
        // The router calls this on a background thread; run the
        // coroutine write and wait briefly.
        var result = false
        val done = java.util.concurrent.CountDownLatch(1)
        // Use a plain thread to avoid depending on a scope here.
        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    result = pushEnvelope(envelope)
                }
            } catch (_: Exception) {
                result = false
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true; start() }
        done.await(15, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    /**
     * Start listening for envelopes addressed to this device.
     * Idempotent. Each envelope is verified, delivered, then deleted.
     */
    fun startListening() {
        if (listening) return
        listening = true
        val l = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prevKey: String?) {
                handleSnapshot(snapshot)
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onCancelled(e: DatabaseError) {
                listening = false
            }
        }
        listener = l
        inboxRef.addChildEventListener(l)
    }

    fun stopListening() {
        listener?.let { inboxRef.removeEventListener(it) }
        listener = null
        listening = false
    }

    val isListening: Boolean get() = listening

    private fun handleSnapshot(snapshot: DataSnapshot) {
        try {
            val raw = snapshot.getValue(String::class.java) ?: return
            val envelope = JSONObject(raw)
            val delivered = try {
                router.receiveCloudEnvelope(envelope)
            } catch (e: EnvelopeError) {
                // Tampered or wrong-key envelope: drop it and delete
                // so it doesn't sit in the inbox.
                false
            }
            // Delete after handling (delivered or invalid) — the inbox
            // is a queue, not storage.
            snapshot.ref.removeValue()
        } catch (_: Exception) {
            // Malformed entry: remove so it doesn't poison the listener.
            try { snapshot.ref.removeValue() } catch (_: Exception) {}
        }
    }

    companion object {
        /**
         * Ensure Firebase Auth is ready. Anonymous sign-in gives each
         * install a stable UID for the database rules without Jon
         * managing accounts.
         */
        suspend fun ensureAuth(): Boolean {
            return try {
                val auth = FirebaseAuth.getInstance()
                if (auth.currentUser == null) {
                    auth.signInAnonymously().await()
                }
                auth.currentUser != null
            } catch (e: Exception) {
                false
            }
        }
    }
}
