package com.deanwaylabs.quietos.network.transport

import com.deanwaylabs.quietos.network.pairing.PairedDevice
import com.deanwaylabs.quietos.network.token.Bucket
import com.deanwaylabs.quietos.network.token.TokenCoordinator
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wi-Fi socket transport for the QuietOS Network.
 *
 * Real TCP sockets between paired devices on the same local network.
 * Every message after the handshake is AES-256-GCM encrypted with the
 * pairing channel key; framing is a 4-byte big-endian length prefix.
 *
 * Wire protocol:
 *     client -> server : HELLO  {device_id, key_id}          (plaintext)
 *     server -> client : WELCOME {device_id, key_id}         (plaintext)
 *     either way       : FRAME   {len(12 + ct)} + nonce + ct (encrypted)
 *                        where ct = AES-GCM(key, nonce, json_message)
 *
 * Message types inside the encrypted frame:
 *     TOKEN_CLAIM      {event_id, bucket, device_id}
 *     TOKEN_YIELD      {event_id, device_id}
 *     TOKEN_HEARTBEAT  {event_id, device_id}
 *     PING / PONG      {}                          (keepalive)
 *     EVENT            {event_type, payload}       (generic, e.g. Guardian)
 *
 * Ported from the Python bench (quietos/network/transport.py), 2026-10-11.
 * Field-proven on the bench 2026-10-11: two physical phones (Motorola +
 * Phone B over hotspot) paired, claimed/yielded tokens, exchanged events.
 */

const val DEFAULT_PORT = 8475
private const val NONCE_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val HANDSHAKE_TIMEOUT_MS = 10_000
private const val MAX_FRAME_BYTES = 4 * 1024 * 1024

open class TransportError(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Peer failed the pairing handshake (wrong key, bad HELLO). */
class HandshakeError(message: String) : TransportError(message)

// -- crypto ---------------------------------------------------------------

private fun aead(key: ByteArray): Cipher {
    require(key.size == 32) { "channel key must be 32 bytes" }
    return Cipher.getInstance("AES/GCM/NoPadding")
}

/** Serialize + encrypt one message. Returns the full wire frame. */
fun encryptFrame(key: ByteArray, message: JSONObject): ByteArray {
    val plaintext = message.toString().toByteArray(Charsets.UTF_8)
    val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
    val cipher = aead(key)
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
        GCMParameterSpec(GCM_TAG_BITS, nonce))
    val ct = cipher.doFinal(plaintext)
    val frame = ByteBuffer.allocate(4 + NONCE_BYTES + ct.size)
        .order(ByteOrder.BIG_ENDIAN)
    frame.putInt(NONCE_BYTES + ct.size)
    frame.put(nonce)
    frame.put(ct)
    return frame.array()
}

/** Decrypt one frame body (nonce + ciphertext). Returns the message. */
fun decryptFrame(key: ByteArray, blob: ByteArray): JSONObject {
    if (blob.size < NONCE_BYTES + 16) {
        throw TransportError("frame too short")
    }
    val nonce = blob.copyOfRange(0, NONCE_BYTES)
    val ct = blob.copyOfRange(NONCE_BYTES, blob.size)
    val plaintext = try {
        val cipher = aead(key)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.doFinal(ct)
    } catch (e: Exception) {
        throw TransportError("frame authentication failed: ${e.message}", e)
    }
    return try {
        JSONObject(plaintext.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        throw TransportError("bad frame payload: ${e.message}", e)
    }
}

// -- low-level socket helpers ---------------------------------------------

private fun sendAll(out: DataOutputStream, data: ByteArray) {
    out.write(data)
    out.flush()
}

private fun recvExact(`in`: DataInputStream, n: Int): ByteArray {
    val buf = ByteArray(n)
    var read = 0
    while (read < n) {
        val r = try {
            `in`.read(buf, read, n - read)
        } catch (e: SocketTimeoutException) {
            throw TransportError("read timeout: ${e.message}", e)
        }
        if (r == -1) throw TransportError("connection closed by peer")
        read += r
    }
    return buf
}

private fun readFrameLength(`in`: DataInputStream): Int {
    val hdr = recvExact(`in`, 4)
    return ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN).int
}

/** Length-prefixed plaintext JSON (handshake only). */
private fun sendPlain(out: DataOutputStream, message: JSONObject) {
    val payload = message.toString().toByteArray(Charsets.UTF_8)
    val hdr = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        .putInt(payload.size).array()
    sendAll(out, hdr + payload)
}

/** Read one length-prefixed plaintext JSON message (handshake only). */
private fun recvPlain(`in`: DataInputStream): JSONObject {
    val length = readFrameLength(`in`)
    if (length > 64 * 1024) throw HandshakeError("handshake message too large")
    if (length < 0) throw HandshakeError("bad handshake length")
    val payload = recvExact(`in`, length)
    return JSONObject(payload.toString(Charsets.UTF_8))
}

// -- message builders -----------------------------------------------------

private fun msg(type: String, vararg fields: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    o.put("type", type)
    for ((k, v) in fields) {
        when (v) {
            null -> o.put(k, JSONObject.NULL)
            is JSONObject -> o.put(k, v)
            is Map<*, *> -> {
                val sub = JSONObject()
                for ((sk, sv) in v) sub.put(sk.toString(), sv)
                o.put(k, sub)
            }
            else -> o.put(k, v)
        }
    }
    return o
}

// -- the node ---------------------------------------------------------------

/**
 * One device on the QuietOS Network.
 *
 * Runs a TCP server (background thread), connects out to peers, and
 * keeps a TokenCoordinator in sync across the fleet via encrypted
 * TOKEN_* broadcasts.
 *
 *     val device = PairedDevice.pair("K7Q2-M9XD", name="phone-a")
 *     val node = NetworkNode(device, port=8475)
 *     node.start()                        // listen for peers
 *     node.connect("192.168.1.42", 8475)  // dial a peer
 *     node.claimToken("call-123", Bucket.NOW)
 *     ...
 *     node.stop()
 */
class NetworkNode(
    val device: PairedDevice,
    val port: Int = DEFAULT_PORT,
    val tokens: TokenCoordinator = TokenCoordinator()
) {
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val peers = ConcurrentHashMap<String, Socket>()
    private val running = AtomicBoolean(false)

    /** (eventType, payload, fromDeviceId) */
    var onEvent: ((String, JSONObject, String) -> Unit)? = null

    /** (deviceId, connected) */
    var onPeer: ((String, Boolean) -> Unit)? = null

    // -- lifecycle ------------------------------------------------------

    /** Start listening for peers. Idempotent. */
    @Synchronized
    fun start() {
        if (running.getAndSet(true)) return
        val srv = ServerSocket()
        srv.reuseAddress = true
        srv.bind(InetSocketAddress("0.0.0.0", port))
        srv.soTimeout = 1000
        server = srv
        acceptThread = Thread({ acceptLoop() }, "quietos-accept-$port").apply {
            isDaemon = true
            start()
        }
    }

    /** Shut down the server and all peer connections. */
    @Synchronized
    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
        val sockets = peers.values.toList()
        peers.clear()
        for (s in sockets) {
            try { s.close() } catch (_: Exception) {}
        }
        try { acceptThread?.join(3000) } catch (_: InterruptedException) {}
        acceptThread = null
    }

    val peerIds: List<String> get() = peers.keys.toList()
    val isRunning: Boolean get() = running.get()

    // -- outbound ---------------------------------------------------------

    /** Dial a peer, handshake, and register it. Throws on failure. */
    fun connect(host: String, port: Int = DEFAULT_PORT,
                timeoutMs: Int = HANDSHAKE_TIMEOUT_MS) {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            sock.soTimeout = HANDSHAKE_TIMEOUT_MS
            val peerId = clientHandshake(sock)
            sock.soTimeout = 0
            registerPeer(peerId, sock)
        } catch (e: HandshakeError) {
            try { sock.close() } catch (_: Exception) {}
            throw e
        } catch (e: TransportError) {
            try { sock.close() } catch (_: Exception) {}
            throw HandshakeError("handshake failed: ${e.message}")
        } catch (e: Exception) {
            try { sock.close() } catch (_: Exception) {}
            throw TransportError("connect failed: ${e.message}", e)
        }
    }

    /** Returns the peer's device id on success. */
    private fun clientHandshake(sock: Socket): String {
        val out = DataOutputStream(sock.getOutputStream())
        val `in` = DataInputStream(sock.getInputStream())
        sendPlain(out, msg("HELLO",
            "device_id" to device.deviceId,
            "key_id" to device.keyId))
        val reply = recvPlain(`in`)
        if (reply.optString("type") != "WELCOME") {
            throw HandshakeError(
                "expected WELCOME, got ${reply.optString("type")}")
        }
        if (reply.optString("key_id") != device.keyId) {
            throw HandshakeError("peer key_id mismatch: not paired")
        }
        return reply.optString("device_id", "unknown")
    }

    // -- inbound ----------------------------------------------------------

    private fun acceptLoop() {
        val srv = server ?: return
        while (running.get()) {
            val conn = try {
                srv.accept()
            } catch (e: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            Thread({ serverHandshake(conn) }, "quietos-handshake").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serverHandshake(conn: Socket) {
        try {
            conn.soTimeout = HANDSHAKE_TIMEOUT_MS
            val `in` = DataInputStream(conn.getInputStream())
            val out = DataOutputStream(conn.getOutputStream())
            val hello = recvPlain(`in`)
            if (hello.optString("type") != "HELLO") {
                throw HandshakeError("expected HELLO")
            }
            if (hello.optString("key_id") != device.keyId) {
                // Unpaired device: refuse silently, close.
                try { conn.close() } catch (_: Exception) {}
                return
            }
            sendPlain(out, msg("WELCOME",
                "device_id" to device.deviceId,
                "key_id" to device.keyId))
            conn.soTimeout = 0
            val peerId = hello.optString("device_id", "unknown")
            registerPeer(peerId, conn)
        } catch (_: Exception) {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private fun registerPeer(peerId: String, sock: Socket) {
        // If we already have this peer (both dialed each other), keep
        // the existing connection and drop the duplicate.
        val existing = peers.putIfAbsent(peerId, sock)
        if (existing != null) {
            try { sock.close() } catch (_: Exception) {}
            return
        }
        try { onPeer?.invoke(peerId, true) } catch (_: Exception) {}
        Thread({ peerLoop(peerId, sock) }, "quietos-peer-${peerId.take(8)}").apply {
            isDaemon = true
            start()
        }
    }

    private fun dropPeer(peerId: String) {
        val sock = peers.remove(peerId)
        if (sock != null) {
            try { sock.close() } catch (_: Exception) {}
        }
        try { onPeer?.invoke(peerId, false) } catch (_: Exception) {}
    }

    private fun peerLoop(peerId: String, sock: Socket) {
        try {
            val `in` = DataInputStream(sock.getInputStream())
            while (running.get()) {
                val length = readFrameLength(`in`)
                if (length > MAX_FRAME_BYTES || length < 0) {
                    throw TransportError("frame too large")
                }
                val blob = recvExact(`in`, length)
                val message = decryptFrame(device.channelKey, blob)
                handleMessage(message, peerId)
            }
        } catch (_: Exception) {
            // Peer went away; drop below.
        } finally {
            // Only drop if this socket is still the registered one.
            if (peers[peerId] === sock) dropPeer(peerId)
            else try { sock.close() } catch (_: Exception) {}
        }
    }

    // -- messaging ----------------------------------------------------------

    private fun sendTo(peerId: String, message: JSONObject) {
        val sock = peers[peerId]
            ?: throw TransportError("no such peer: $peerId")
        val frame = encryptFrame(device.channelKey, message)
        try {
            synchronized(sock) {
                sendAll(DataOutputStream(sock.getOutputStream()), frame)
            }
        } catch (e: Exception) {
            dropPeer(peerId)
            throw TransportError("send failed: ${e.message}", e)
        }
    }

    private fun broadcast(message: JSONObject) {
        for (peerId in peerIds) {
            try {
                sendTo(peerId, message)
            } catch (_: TransportError) {
                // Dead peer already dropped.
            }
        }
    }

    private fun handleMessage(message: JSONObject, peerId: String) {
        when (message.optString("type")) {
            "TOKEN_CLAIM" -> {
                val bucket = try {
                    Bucket.fromValue(message.optString("bucket"))
                } catch (_: IllegalArgumentException) {
                    return
                }
                tokens.claim(
                    message.optString("event_id"),
                    message.optString("device_id"),
                    bucket
                )
            }
            "TOKEN_YIELD" -> tokens.yieldToken(
                message.optString("event_id"),
                message.optString("device_id")
            )
            "TOKEN_HEARTBEAT" -> tokens.heartbeat(
                message.optString("event_id"),
                message.optString("device_id")
            )
            "PING" -> {
                try {
                    sendTo(peerId, msg("PONG"))
                } catch (_: TransportError) {}
            }
            "PONG" -> { /* keepalive ack */ }
            "EVENT" -> {
                try {
                    onEvent?.invoke(
                        message.optString("event_type"),
                        message.optJSONObject("payload") ?: JSONObject(),
                        message.optString("device_id", peerId)
                    )
                } catch (_: Exception) {}
            }
            // Unknown types are ignored — forward-compatible.
        }
    }

    // -- token API (local claim + fleet broadcast) --------------------------

    /** Claim the token locally and tell the fleet. True = granted. */
    fun claimToken(eventId: String, bucket: Bucket): Boolean {
        val granted = tokens.claim(eventId, device.deviceId, bucket)
        if (granted) {
            broadcast(msg("TOKEN_CLAIM",
                "event_id" to eventId,
                "bucket" to bucket.value,
                "device_id" to device.deviceId))
        }
        return granted
    }

    /** Yield the token locally and tell the fleet. */
    fun yieldToken(eventId: String): Boolean {
        val yielded = tokens.yieldToken(eventId, device.deviceId)
        if (yielded) {
            broadcast(msg("TOKEN_YIELD",
                "event_id" to eventId,
                "device_id" to device.deviceId))
        }
        return yielded
    }

    /** Renew the lease locally and tell the fleet. */
    fun heartbeatToken(eventId: String): Boolean {
        val renewed = tokens.heartbeat(eventId, device.deviceId)
        if (renewed) {
            broadcast(msg("TOKEN_HEARTBEAT",
                "event_id" to eventId,
                "device_id" to device.deviceId))
        }
        return renewed
    }

    /** Who holds the token for this event (local view, fleet-synced). */
    fun tokenOwner(eventId: String): String? = tokens.owner(eventId)

    // -- generic events -----------------------------------------------------

    /** Broadcast a generic event (e.g. Guardian) to all peers. */
    fun sendEvent(eventType: String, payload: JSONObject = JSONObject()) {
        broadcast(msg("EVENT",
            "event_type" to eventType,
            "payload" to payload,
            "device_id" to device.deviceId))
    }

    /** Keepalive to all peers. */
    fun ping() {
        broadcast(msg("PING"))
    }
}
