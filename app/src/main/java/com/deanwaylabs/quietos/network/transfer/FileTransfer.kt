package com.deanwaylabs.quietos.network.transfer

import com.deanwaylabs.quietos.network.pairing.PairedDevice
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local file transfer between paired devices — encrypted, chunked, verified.
 *
 * Wire protocol:
 *     sender                          receiver
 *       |--- OFFER {name,size,sha256,key_id} --->|
 *       |<-- ACCEPT / DECLINE ------------------|
 *       |--- CHUNK {seq, nonce, ciphertext} --->|  (repeat)
 *       |--- DONE ----------------------------->|
 *       |                              verify SHA-256 over plaintext
 *
 * Encryption: AES-256-GCM with the pairing channel key, fresh 96-bit nonce
 * per chunk. A tampered or replayed chunk fails authentication and aborts
 * the transfer. The receiver's SHA-256 check over the reassembled plaintext
 * is the final integrity gate.
 *
 * Refusal is explicit: a receiver only accepts offers whose key_id matches
 * its own paired channel key. An unpaired sender gets DECLINE.
 *
 * Ported from the Python bench (quietos/network/transfer.py), 2026-10-11.
 * On Android the MemoryTransport is replaced by the socket transport's
 * encrypted channel; the OFFER/CHUNK/DONE protocol is identical.
 */

const val CHUNK_SIZE = 16 * 1024  // 16 KiB per chunk
private const val GCM_TAG_BITS = 128
private const val NONCE_BYTES = 12

open class TransferError(message: String) : Exception(message)

/** Receiver declined the offer (unpaired sender, bad key, policy). */
class RefusedError(message: String) : TransferError(message)

/** SHA-256 mismatch or chunk authentication failure. */
class IntegrityError(message: String) : TransferError(message)

// -- crypto ---------------------------------------------------------------

private fun aeadCipher(key: ByteArray, nonce: ByteArray, encrypt: Boolean): Cipher {
    require(key.size == 32) { "channel key must be 32 bytes" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val keySpec = SecretKeySpec(key, "AES")
    val paramSpec = GCMParameterSpec(GCM_TAG_BITS, nonce)
    cipher.init(
        if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
        keySpec, paramSpec
    )
    return cipher
}

/** Encrypt one chunk. Returns (nonce, ciphertext+tag). */
fun encryptChunk(key: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
    val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
    val ct = aeadCipher(key, nonce, true).doFinal(plaintext)
    return nonce to ct
}

/** Decrypt one chunk. Throws IntegrityError on authentication failure. */
fun decryptChunk(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
    return try {
        aeadCipher(key, nonce, false).doFinal(ciphertext)
    } catch (e: Exception) {
        throw IntegrityError("chunk authentication failed: ${e.message}")
    }
}

fun sha256Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(data).joinToString("") { "%02x".format(it) }
}

// -- message model --------------------------------------------------------
// On the wire these travel as JSON inside the socket transport's encrypted
// frames. Sealed hierarchy keeps the protocol explicit.

sealed class TransferMessage {
    abstract val offerId: String
}

data class Offer(
    override val offerId: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val keyId: String,
    val fromDeviceId: String
) : TransferMessage()

data class Accept(override val offerId: String) : TransferMessage()
data class Decline(override val offerId: String, val reason: String) : TransferMessage()

data class Chunk(
    override val offerId: String,
    val seq: Int,
    val nonce: ByteArray,
    val ciphertext: ByteArray
) : TransferMessage() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Chunk) return false
        return offerId == other.offerId && seq == other.seq &&
            nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)
    }
    override fun hashCode(): Int {
        var r = offerId.hashCode()
        r = 31 * r + seq
        r = 31 * r + nonce.contentHashCode()
        r = 31 * r + ciphertext.contentHashCode()
        return r
    }
}

data class Done(override val offerId: String, val chunks: Int) : TransferMessage()

/** Minimal in-process transport for tests; the socket transport replaces this on device. */
class MemoryTransport {
    private val inbox = LinkedBlockingQueue<TransferMessage>()
    private var peerOutbox: LinkedBlockingQueue<TransferMessage>? = null

    fun send(message: TransferMessage) {
        (peerOutbox ?: throw TransferError("transport not linked to a peer"))
            .put(message)
    }

    fun recv(timeoutMs: Long = 5000): TransferMessage {
        return inbox.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw TransferError("receive timeout")
    }

    companion object {
        fun link(a: MemoryTransport, b: MemoryTransport) {
            a.peerOutbox = b.inbox
            b.peerOutbox = a.inbox
        }
    }
}

// -- session --------------------------------------------------------------

data class TransferReceipt(
    val name: String,
    val size: Long,
    val sha256: String,
    val chunks: Int
)

/** Push side. Owned by a PairedDevice; speaks only to its transport. */
class FileSender(
    private val device: PairedDevice,
    private val transport: MemoryTransport
) {
    fun push(name: String, data: ByteArray): TransferReceipt {
        val digest = sha256Hex(data)
        val offerId = UUID.randomUUID().toString().replace("-", "").take(12)
        transport.send(Offer(
            offerId = offerId, name = name, size = data.size.toLong(),
            sha256 = digest, keyId = device.keyId,
            fromDeviceId = device.deviceId
        ))
        val reply = transport.recv()
        if (reply !is Accept || reply.offerId != offerId) {
            val reason = (reply as? Decline)?.reason ?: reply.toString()
            throw RefusedError("offer declined: $reason")
        }

        var chunks = 0
        var seq = 0
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + CHUNK_SIZE, data.size)
            val piece = data.copyOfRange(offset, end)
            val (nonce, ct) = encryptChunk(device.channelKey, piece)
            transport.send(Chunk(offerId = offerId, seq = seq,
                nonce = nonce, ciphertext = ct))
            chunks++
            seq++
            offset = end
        }
        // Empty file still sends DONE with 0 chunks.
        transport.send(Done(offerId = offerId, chunks = chunks))
        return TransferReceipt(name = name, size = data.size.toLong(),
            sha256 = digest, chunks = chunks)
    }
}

/** Receive side. Accept policy: paired key_id only, then verify. */
class FileReceiver(
    private val device: PairedDevice,
    private val transport: MemoryTransport
) {
    private fun decline(offer: Offer, reason: String) {
        transport.send(Decline(offerId = offer.offerId, reason = reason))
    }

    /**
     * Block for one incoming file. Returns (name, plaintext bytes).
     * Throws RefusedError for unpaired senders, IntegrityError for
     * tampered or corrupted transfers.
     */
    fun receive(): Pair<String, ByteArray> {
        val first = transport.recv()
        if (first !is Offer) {
            throw TransferError("expected OFFER, got ${first::class.simpleName}")
        }
        if (first.keyId != device.keyId) {
            decline(first, "unpaired sender: key_id mismatch")
            throw RefusedError("unpaired sender refused")
        }
        transport.send(Accept(offerId = first.offerId))

        val pieces = mutableMapOf<Int, ByteArray>()
        while (true) {
            val msg = transport.recv()
            when (msg) {
                is Done -> {
                    if (msg.offerId != first.offerId) {
                        throw TransferError("DONE for wrong offer")
                    }
                    break
                }
                is Chunk -> {
                    if (msg.offerId != first.offerId) {
                        throw TransferError("CHUNK for wrong offer")
                    }
                    pieces[msg.seq] = decryptChunk(
                        device.channelKey, msg.nonce, msg.ciphertext)
                }
                else -> throw TransferError(
                    "unexpected message ${msg::class.simpleName}")
            }
        }

        val ordered = pieces.keys.sorted()
        val totalSize = ordered.sumOf { pieces[it]!!.size }.toLong()
        if (totalSize != first.size) {
            throw IntegrityError(
                "size mismatch: got $totalSize, expected ${first.size}")
        }
        val data = ordered.flatMap { pieces[it]!!.asList() }.toByteArray()
        val digest = sha256Hex(data)
        if (digest != first.sha256) {
            throw IntegrityError("SHA-256 mismatch: transfer corrupted")
        }
        return first.name to data
    }
}
