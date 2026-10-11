package com.deanwaylabs.quietos.network.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Device pairing — one shared secret, two paired devices.
 *
 * Jon shows a pairing code on device A and types it into device B —
 * the same ritual as a Wi-Fi password. The code IS the shared secret.
 * Both sides run it through HKDF-SHA256 and land on the same 32-byte
 * channel key; each side keeps its own stable device id.
 *
 * Nothing here ever sends the secret anywhere. On the phone the code
 * will travel by QR (camera, local only).
 *
 * Ported from the Python bench (quietos/network/pairing.py), 2026-10-11.
 * HKDF-SHA256 matches the bench exactly: salt "quietos-network-v1",
 * info "local-channel-key", 32-byte output.
 */

private val HKDF_SALT = "quietos-network-v1".toByteArray(Charsets.UTF_8)
private val HKDF_INFO = "local-channel-key".toByteArray(Charsets.UTF_8)
const val KEY_BYTES = 32

// Short, typable, unambiguous: no 0/O, 1/I/l.
private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

class PairingError(message: String) : Exception(message)

/** Generate a fresh pairing code, e.g. "K7Q2-M9XD". Shown on device A. */
fun newPairingCode(length: Int = 8): String {
    val rng = SecureRandom()
    val raw = (1..length)
        .map { CODE_ALPHABET[rng.nextInt(CODE_ALPHABET.length)] }
        .joinToString("")
    return raw.chunked(4).joinToString("-")
}

/** Canonical form: uppercase, no dashes or spaces. */
fun canonicalCode(code: String): String =
    code.trim().uppercase().replace("-", "").replace(" ", "")

private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

/**
 * HKDF-SHA256 per RFC 5869.
 * Extract: PRK = HMAC-SHA256(salt, ikm)
 * Expand:  T(0) = empty; T(i) = HMAC-SHA256(PRK, T(i-1) | info | i)
 */
private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    // Extract
    val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
    // Expand
    val out = mutableListOf<Byte>()
    var t = ByteArray(0)
    var counter: Byte = 1
    while (out.size < length) {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(t)
        mac.update(info)
        mac.update(counter)
        t = mac.doFinal()
        out.addAll(t.toList())
        counter++
    }
    return out.take(length).toByteArray()
}

/**
 * Both devices, same code in -> same 32-byte key out. Deterministic.
 * Matches the Python bench's cryptography.hazmat HKDF exactly.
 */
fun deriveChannelKey(pairingCode: String): ByteArray {
    val secret = canonicalCode(pairingCode).toByteArray(Charsets.US_ASCII)
    return hkdfSha256(secret, HKDF_SALT, HKDF_INFO, KEY_BYTES)
}

/** Short public fingerprint of a channel key (safe to display / compare). */
fun keyFingerprint(key: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("quietos-key-id".toByteArray(Charsets.UTF_8))
    digest.update(key)
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
}

/** One side of a pairing. Holds its stable id and the shared channel key. */
class PairedDevice(
    val deviceId: String,
    val channelKey: ByteArray,
    val name: String = ""
) {
    val displayName: String = name.ifEmpty { deviceId.take(8) }

    val keyId: String get() = keyFingerprint(channelKey)

    /** True when both sides derived the same channel key. */
    fun trusts(other: PairedDevice): Boolean =
        MessageDigest.isEqual(keyId.toByteArray(), other.keyId.toByteArray())

    override fun toString(): String =
        "PairedDevice(name=$displayName, id=${deviceId.take(8)}…)"

    companion object {
        /** Enter the code shown on the other device. Returns this side. */
        fun pair(pairingCode: String, name: String = ""): PairedDevice {
            val code = canonicalCode(pairingCode)
            if (code.length < 6 || code.any { it !in CODE_ALPHABET }) {
                throw PairingError("pairing code rejected: bad format")
            }
            return PairedDevice(
                deviceId = UUID.randomUUID().toString().replace("-", "").take(12),
                channelKey = deriveChannelKey(code),
                name = name
            )
        }
    }
}
