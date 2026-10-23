package com.deanwaylabs.quietos.network.cards

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Card crypto — the app half of APP-CARD-ROUTE-DESIGN §5.2/§5.3.
 * Pure JCE (no Android classes) so it runs in plain JVM unit tests
 * against the same checked-in vectors as the Python host suite
 * (app/src/test/resources/card_vectors.json — the host copy is
 * sentinel/host/appcard/tests/vectors.json).
 *
 * Body encryption (receive): ECDH P-256 -> HKDF-SHA256 (salt ABSENT,
 * info "quietos-cards/v1") -> AES-256-GCM.
 *     body_enc = base64( ephemeral_pub_DER[91] || nonce[12] || ct )
 * Decision signature (send): ECDSA P-256 / SHA-256, DER-encoded,
 * base64'd, over the UTF-8 of "{card_id}:{verb}:{decided_at}".
 * Fingerprint: SHA-256 hex of the public key's X.509 DER encoding.
 */
object CardCryptoCore {

    const val HKDF_INFO = "quietos-cards/v1"
    const val P256_SPKI_LEN = 91
    const val NONCE_LEN = 12
    const val GCM_TAG_BITS = 128
    const val KEY_LEN = 32

    /** RFC 5869 HKDF-SHA256, salt absent -> 32 zero bytes (host uses
     *  cryptography.hazmat HKDF with salt=None; identical by the RFC's
     *  rule and by the checked-in interop vectors). */
    fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(ByteArray(32), ikm)
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

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun deriveBodyKey(myPrivate: PrivateKey, peerPublic: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(myPrivate)
        agreement.doPhase(peerPublic, true)
        val z = agreement.generateSecret()
        return hkdfSha256(z, HKDF_INFO.toByteArray(Charsets.UTF_8), KEY_LEN)
    }

    fun publicKeyFromDer(der: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))

    /** Decrypt a host-published card body with this device's private
     *  key. Throws on any tampering (GCM tag) — callers fail closed. */
    fun decryptBody(myPrivate: PrivateKey, bodyEncB64: String): ByteArray {
        val blob = Base64.getDecoder().decode(bodyEncB64)
        require(blob.size > P256_SPKI_LEN + NONCE_LEN) { "body_enc too short" }
        val ephDer = blob.copyOfRange(0, P256_SPKI_LEN)
        val nonce = blob.copyOfRange(P256_SPKI_LEN, P256_SPKI_LEN + NONCE_LEN)
        val ct = blob.copyOfRange(P256_SPKI_LEN + NONCE_LEN, blob.size)
        val key = deriveBodyKey(myPrivate, publicKeyFromDer(ephDer))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        return cipher.doFinal(ct)
    }

    /** Encrypt a body to a peer public key. Used by the interop
     *  tests (and any future app->host encrypted payload); production
     *  card bodies are encrypted host-side. Ephemeral key + nonce are
     *  parameters so tests can reproduce the checked-in vectors. */
    fun encryptBody(
        peerPublic: PublicKey,
        plaintext: ByteArray,
        ephemeralPrivate: PrivateKey,
        ephemeralPublicDer: ByteArray,
        nonce: ByteArray
    ): String {
        val key = deriveBodyKey(ephemeralPrivate, peerPublic)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        val ct = cipher.doFinal(plaintext)
        return Base64.getEncoder().encodeToString(ephemeralPublicDer + nonce + ct)
    }

    fun decisionMessage(cardId: String, verb: String, decidedAtSec: Long): ByteArray =
        "$cardId:$verb:$decidedAtSec".toByteArray(Charsets.UTF_8)

    fun signDecision(
        myPrivate: PrivateKey,
        cardId: String,
        verb: String,
        decidedAtSec: Long
    ): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(myPrivate)
        sig.update(decisionMessage(cardId, verb, decidedAtSec))
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    fun verifyDecision(
        publicKey: PublicKey,
        cardId: String,
        verb: String,
        decidedAtSec: Long,
        sigB64: String
    ): Boolean = try {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(publicKey)
        sig.update(decisionMessage(cardId, verb, decidedAtSec))
        sig.verify(Base64.getDecoder().decode(sigB64))
    } catch (e: Exception) {
        false
    }

    fun fingerprintOf(publicKey: PublicKey): String =
        fingerprintOfDer(publicKey.encoded)

    fun fingerprintOfDer(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString("") { "%02x".format(it) }
}
