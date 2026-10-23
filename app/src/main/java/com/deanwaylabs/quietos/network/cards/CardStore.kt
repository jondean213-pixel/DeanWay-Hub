package com.deanwaylabs.quietos.network.cards

import android.content.Context
import org.json.JSONObject
import java.io.File

/** The decrypted card body (§5.2) — what the card view renders. */
data class CardBody(
    val kind: String,
    val title: String,
    val text: String,
    val truncated: Boolean,
    val heldId: String?,
    val source: String?
)

/**
 * One card as the app keeps it: the plaintext RTDB metadata, the
 * ciphertext, and — after a successful decrypt — the body. A body
 * that fails to decrypt reads as null (the card row shows an honest
 * error state, never a guess).
 */
data class CardRecord(
    val id: String,
    val kind: String,
    val title: String,
    val createdAtSec: Long,
    val expiresAtSec: Long,
    val state: String,
    val bodyEnc: String,
    val heldId: String?,
    val decidedVerb: String? = null,
    val body: CardBody? = null
) {
    val isPending: Boolean get() = state == "pending" && decidedVerb == null
    fun isExpired(nowSec: Long): Boolean = expiresAtSec in 1..nowSec
}

/**
 * Local card persistence: one small JSON file in internal storage
 * (filesDir/cards/cards.json). The store is deliberately boring —
 * RTDB is the transport, this is the app's working copy so cards
 * render instantly and survive process death (design §4: a killed
 * app that reopens must show the card, not a lost decision).
 */
class CardStore(context: Context) {

    private val file = File(File(context.filesDir, "cards"), "cards.json")

    @Synchronized
    fun all(): List<CardRecord> {
        val raw = readAll()
        return raw.values.sortedBy { it.createdAtSec }
    }

    @Synchronized
    fun pending(): List<CardRecord> = all().filter { it.isPending }

    @Synchronized
    fun get(cardId: String): CardRecord? = readAll()[cardId]

    /** Insert/refresh from an RTDB card map; returns the stored
     *  record (with a decrypt attempt), or null for junk input. */
    @Synchronized
    fun upsert(cardId: String, node: Map<String, Any?>): CardRecord? {
        val record = parse(cardId, node) ?: return null
        val map = readAll().toMutableMap()
        val previous = map[cardId]
        map[cardId] = record.copy(
            decidedVerb = record.decidedVerb ?: previous?.decidedVerb)
        writeAll(map)
        return withBody(map[cardId]!!)
    }

    @Synchronized
    fun markDecided(cardId: String, verb: String) {
        val map = readAll().toMutableMap()
        val current = map[cardId] ?: return
        map[cardId] = current.copy(decidedVerb = verb)
        writeAll(map)
    }

    @Synchronized
    fun updateState(cardId: String, state: String) {
        val map = readAll().toMutableMap()
        val current = map[cardId] ?: return
        map[cardId] = current.copy(state = state)
        writeAll(map)
    }

    @Synchronized
    fun remove(cardId: String) {
        val map = readAll().toMutableMap()
        if (map.remove(cardId) != null) writeAll(map)
    }

    /** Drop local pendings the host no longer lists (pruned server
     *  side). Decided/expired history is kept for the record. */
    @Synchronized
    fun prunePendingNotIn(remoteIds: Set<String>) {
        val map = readAll().toMutableMap()
        val doomed = map.values
            .filter { it.isPending && it.id !in remoteIds }
            .map { it.id }
        doomed.forEach { map.remove(it) }
        if (doomed.isNotEmpty()) writeAll(map)
    }

    /** Attach a decrypt attempt for display. Never throws. */
    fun withBody(record: CardRecord): CardRecord {
        if (record.body != null || record.bodyEnc.isEmpty()) return record
        val body = try {
            val plain = CardCrypto.decryptBody(record.bodyEnc)
            val json = JSONObject(String(plain, Charsets.UTF_8))
            CardBody(
                kind = json.optString("kind", record.kind),
                title = json.optString("title", record.title),
                text = json.optString("text", ""),
                truncated = json.optBoolean("truncated", false),
                heldId = json.optString("held_id").ifEmpty { null },
                source = json.optString("source").ifEmpty { null }
            )
        } catch (e: Exception) {
            null
        }
        return record.copy(body = body)
    }

    fun snapshotWithBodies(): List<CardRecord> = all().map { withBody(it) }

    // -- file -------------------------------------------------------------

    private fun parse(cardId: String, node: Map<String, Any?>): CardRecord? {
        val kind = node["kind"] as? String ?: return null
        val bodyEnc = node["body_enc"] as? String ?: return null
        val decision = node["decision"] as? Map<*, *>
        return CardRecord(
            id = cardId,
            kind = kind,
            title = node["title"] as? String ?: "QuietOS needs your call",
            createdAtSec = (node["created_at"] as? Number)?.toLong() ?: 0L,
            expiresAtSec = (node["expires_at"] as? Number)?.toLong() ?: 0L,
            state = node["state"] as? String ?: "pending",
            bodyEnc = bodyEnc,
            heldId = node["held_id"] as? String,
            decidedVerb = decision?.get("verb") as? String
        )
    }

    private fun readAll(): Map<String, CardRecord> {
        if (!file.exists()) return emptyMap()
        return try {
            val root = JSONObject(file.readText())
            val out = mutableMapOf<String, CardRecord>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val o = root.getJSONObject(id)
                out[id] = CardRecord(
                    id = id,
                    kind = o.optString("kind"),
                    title = o.optString("title"),
                    createdAtSec = o.optLong("created_at"),
                    expiresAtSec = o.optLong("expires_at"),
                    state = o.optString("state", "pending"),
                    bodyEnc = o.optString("body_enc"),
                    heldId = o.optString("held_id").ifEmpty { null },
                    decidedVerb = o.optString("decided_verb")
                        .ifEmpty { null }
                )
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeAll(map: Map<String, CardRecord>) {
        file.parentFile?.mkdirs()
        val root = JSONObject()
        for ((id, r) in map) {
            root.put(id, JSONObject()
                .put("kind", r.kind)
                .put("title", r.title)
                .put("created_at", r.createdAtSec)
                .put("expires_at", r.expiresAtSec)
                .put("state", r.state)
                .put("body_enc", r.bodyEnc)
                .put("held_id", r.heldId ?: JSONObject.NULL)
                .put("decided_verb", r.decidedVerb ?: JSONObject.NULL))
        }
        val tmp = File(file.parentFile, "cards.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }
}
