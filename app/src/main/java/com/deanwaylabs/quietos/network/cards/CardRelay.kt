package com.deanwaylabs.quietos.network.cards

import android.content.Context
import com.deanwaylabs.quietos.network.relay.FirebaseRelay
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * The app's card transport — APP-CARD-ROUTE-DESIGN §3.4/§6.2.
 *
 * Listens on quietos-cards/{deviceId}/cards while the process lives
 * (layer 1 of the ding), refreshes on demand (tick, worker, hub
 * resume), and writes decisions back as the `decision` child —
 * verb, decided_at, ECDSA signature. The host verifies everything;
 * this side just has to be exact, and to fail closed: a card whose
 * body won't decrypt is shown as broken, never guessed at, and an
 * undecryptable card is never decidable from a notification action.
 */
object CardRelay {

    @Volatile private var listening = false
    private var listener: ChildEventListener? = null
    private var cardsRef: DatabaseReference? = null

    private fun cardsRef(context: Context, database: FirebaseDatabase =
        FirebaseDatabase.getInstance()): DatabaseReference? =
        CardIdentity.deviceId(context)?.let {
            database.getReference("quietos-cards").child(it).child("cards")
        }

    /** Attach the live listener (idempotent). Also refreshes the
     *  identity record so the doorbell token stays current. */
    suspend fun start(context: Context) {
        val app = context.applicationContext
        CardCrypto.ensureKeyPair()
        if (!FirebaseRelay.ensureAuth()) return
        CardIdentity.publishIdentity(app)
        if (listening) return
        val ref = cardsRef(app) ?: return
        val store = CardStore(app)
        val l = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) =
                onCard(app, store, s)
            override fun onChildChanged(s: DataSnapshot, prev: String?) =
                onCard(app, store, s)
            override fun onChildRemoved(s: DataSnapshot) {
                s.key?.let { store.remove(it); CardNotification.cancel(app, it) }
            }
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {
                listening = false
            }
        }
        listener = l
        cardsRef = ref
        ref.addChildEventListener(l)
        listening = true
    }

    fun stop() {
        listener?.let { cardsRef?.removeEventListener(it) }
        listener = null
        cardsRef = null
        listening = false
    }

    val isListening: Boolean get() = listening

    private fun onCard(context: Context, store: CardStore, s: DataSnapshot) {
        val cardId = s.key ?: return
        @Suppress("UNCHECKED_CAST")
        val node = s.value as? Map<String, Any?> ?: return
        val record = store.upsert(cardId, node) ?: return
        if (record.isPending && !record.isExpired(nowSec())) {
            CardNotification.show(context, store.withBody(record))
        } else {
            CardNotification.cancel(context, cardId)
        }
    }

    /**
     * One-shot refresh: pull the whole cards node, upsert everything,
     * reconcile notifications (show what's missing, cancel what's
     * settled), prune local pendings the host has dropped. Returns
     * the store snapshot. Used by the FCM tick, the WorkManager
     * backstop, and hub resume — the layers behind the listener.
     */
    suspend fun sweep(context: Context): List<CardRecord> {
        val app = context.applicationContext
        CardCrypto.ensureKeyPair()
        if (!FirebaseRelay.ensureAuth()) return CardStore(app).snapshotWithBodies()
        CardIdentity.publishIdentity(app)
        val ref = cardsRef(app) ?: return CardStore(app).snapshotWithBodies()
        val store = CardStore(app)
        try {
            val snap = ref.get().await()
            val remoteIds = mutableSetOf<String>()
            for (child in snap.children) {
                val cardId = child.key ?: continue
                @Suppress("UNCHECKED_CAST")
                val node = child.value as? Map<String, Any?> ?: continue
                remoteIds += cardId
                store.upsert(cardId, node)
            }
            store.prunePendingNotIn(remoteIds)
        } catch (e: Exception) {
            // Offline / auth blip: work from the local copy; the next
            // layer retries. Fail visible, not silent.
        }
        val now = nowSec()
        for (record in store.snapshotWithBodies()) {
            if (record.isPending && !record.isExpired(now)) {
                CardNotification.show(app, record)
            } else {
                CardNotification.cancel(app, record.id)
            }
        }
        return store.snapshotWithBodies()
    }

    /**
     * Sign and write back one decision. Double-submit guarded here
     * (not just in the UI): once this device has decided a card, a
     * second write is refused locally regardless of entry point.
     * Returns true when the decision was written.
     */
    suspend fun decide(context: Context, cardId: String, verb: String): Boolean {
        val app = context.applicationContext
        require(verb == "approve" || verb == "deny") { "bad verb" }
        val store = CardStore(app)
        val record = store.get(cardId) ?: return false
        if (!record.isPending) return false
        if (record.isExpired(nowSec())) return false
        // Customer-safety gate (Jon, 2026-10-23): a card whose body
        // cannot be decrypted on this phone is NEVER decidable —
        // nobody approves what they cannot read. Enforced here, at
        // the single choke point every entry point (hub dialog,
        // notification action) must pass through.
        if (store.withBody(record).body == null) return false
        if (!FirebaseRelay.ensureAuth()) return false
        val ref = cardsRef(app)?.child(cardId)?.child("decision") ?: return false
        val decidedAt = nowSec()
        val sig = CardCrypto.signDecision(cardId, verb, decidedAt)
        return try {
            ref.setValue(
                mapOf(
                    "verb" to verb,
                    "decided_at" to decidedAt,
                    "sig" to sig
                )
            ).await()
            store.markDecided(cardId, verb)
            CardNotification.cancel(app, cardId)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun nowSec(): Long = System.currentTimeMillis() / 1000
}
