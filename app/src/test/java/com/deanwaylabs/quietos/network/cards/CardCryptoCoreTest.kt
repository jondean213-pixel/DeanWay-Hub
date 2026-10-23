package com.deanwaylabs.quietos.network.cards

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Interop vectors, Kotlin half — APP-CARD-ROUTE-DESIGN §7.1.3.
 *
 * The SAME vectors file as the Python host suite
 * (sentinel/host/appcard/tests/vectors.json; here as the
 * /card_vectors.json test resource). Proves the two implementations
 * meet exactly: this side decrypts a Python-encrypted body
 * byte-identically, reproduces the same ciphertext from the same
 * ephemeral key + nonce, and verifies signatures from both Python
 * and plain JCE — while the Python suite verifies a JCE signature
 * in return.
 *
 * Fields are pulled from the JSON with a small regex reader on
 * purpose: local JVM tests must not depend on android.jar's stubbed
 * org.json.
 */
class CardCryptoCoreTest {

    private val json: String by lazy {
        val stream = javaClass.getResourceAsStream("/card_vectors.json")
            ?: throw AssertionError("card_vectors.json missing from resources")
        String(stream.readBytes(), Charsets.UTF_8)
    }

    private fun field(name: String): String {
        // Matches both "name": "string" and "name": 12345 shapes.
        val m = Regex("\"$name\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9]+))").find(json)
            ?: throw AssertionError("field $name missing in vectors")
        return m.groupValues[1].ifEmpty { m.groupValues[2] }
    }

    private fun b64(name: String): ByteArray =
        Base64.getDecoder().decode(field(name))

    private fun privateKey(name: String): PrivateKey =
        KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(b64(name)))

    private fun appPublic(): PublicKey =
        CardCryptoCore.publicKeyFromDer(b64("app_public_der_b64"))

    @Test
    fun fingerprintMatchesHost() {
        assertEquals(field("app_fingerprint"),
            CardCryptoCore.fingerprintOf(appPublic()))
    }

    @Test
    fun decryptsPythonEncryptedBodyByteIdentically() {
        val plain = CardCryptoCore.decryptBody(
            privateKey("app_private_pkcs8_b64"), field("body_enc_b64"))
        assertArrayEquals(b64("plaintext_b64"), plain)
    }

    @Test
    fun encryptsToTheSameCiphertextAsPython() {
        // Same ephemeral key + nonce must give the same body_enc —
        // pins the wire layout (DER || nonce || ct) and the HKDF.
        val out = CardCryptoCore.encryptBody(
            peerPublic = appPublic(),
            plaintext = b64("plaintext_b64"),
            ephemeralPrivate = privateKey("ephemeral_private_pkcs8_b64"),
            ephemeralPublicDer = b64("ephemeral_public_der_b64"),
            nonce = b64("nonce_b64")
        )
        assertEquals(field("body_enc_b64"), out)
    }

    @Test
    fun verifiesPythonSignature() {
        assertTrue(
            CardCryptoCore.verifyDecision(
                appPublic(), field("card_id"), field("verb"),
                field("decided_at").toLong(), field("python_sig_b64")))
    }

    @Test
    fun verifiesJceSignature() {
        assertTrue(
            CardCryptoCore.verifyDecision(
                appPublic(), field("card_id"), field("verb"),
                field("decided_at").toLong(), field("jce_sig_b64")))
    }

    @Test
    fun ownSignatureVerifiesAndTamperingFails() {
        val priv = privateKey("app_private_pkcs8_b64")
        val sig = CardCryptoCore.signDecision(
            priv, field("card_id"), "approve",
            field("decided_at").toLong())
        assertTrue(
            CardCryptoCore.verifyDecision(
                appPublic(), field("card_id"), "approve",
                field("decided_at").toLong(), sig))
        // Same signature, different verb: must NOT verify (this is
        // the host collector's tampered-verb forgery case).
        assertFalse(
            CardCryptoCore.verifyDecision(
                appPublic(), field("card_id"), "deny",
                field("decided_at").toLong(), sig))
    }

    @Test
    fun tamperedBodyIsRejected() {
        val blob = Base64.getDecoder().decode(field("body_enc_b64"))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        try {
            CardCryptoCore.decryptBody(
                privateKey("app_private_pkcs8_b64"),
                Base64.getEncoder().encodeToString(blob))
            fail("tampered ciphertext must not decrypt")
        } catch (e: Exception) {
            // GCM tag mismatch — expected, fail closed.
        }
    }

    @Test
    fun decisionMessageShape() {
        assertEquals(
            field("message"),
            String(
                CardCryptoCore.decisionMessage(
                    field("card_id"), field("verb"),
                    field("decided_at").toLong()),
                Charsets.UTF_8))
    }
}
