package com.deanwaylabs.quietos.network.cards

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The device's card identity key — APP-CARD-ROUTE-DESIGN §5.3.
 *
 * A P-256 keypair generated INSIDE Android Keystore
 * (alias [KEY_ALIAS]), non-exportable: the private key never leaves
 * the keystore; this object only asks it to agree (ECDH) and sign.
 * Destroyed with the app data — an uninstall wipes it, which is why
 * re-pairing is part of every reinstall (design §8 risk 1).
 *
 * Purposes SIGN + AGREE_KEY: AGREE_KEY needs API 31+; Jon's Motorola
 * runs Android 16. On older builds [ensureKeyPair] throws and the
 * setup screen says so honestly instead of degrading silently.
 */
object CardCrypto {

    const val KEY_ALIAS = "quietos_card_key_v1"
    private const val STORE = "AndroidKeyStore"

    @Synchronized
    fun ensureKeyPair(): KeyPair {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (entry != null) return KeyPair(entry.certificate.publicKey, entry.privateKey)
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Card keys need Android 12+ (Keystore ECDH)."
        }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    fun hasKeyPair(): Boolean {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        return ks.containsAlias(KEY_ALIAS)
    }

    fun publicKeyDer(): ByteArray = ensureKeyPair().public.encoded

    fun publicKeyB64(): String =
        Base64.getEncoder().encodeToString(publicKeyDer())

    fun fingerprint(): String = CardCryptoCore.fingerprintOf(ensureKeyPair().public)

    /** Decrypt one card body. Throws on tamper/wrong key. */
    fun decryptBody(bodyEncB64: String): ByteArray =
        CardCryptoCore.decryptBody(ensureKeyPair().private, bodyEncB64)

    /** Sign one decision; returns base64 DER ECDSA. */
    fun signDecision(cardId: String, verb: String, decidedAtSec: Long): String =
        CardCryptoCore.signDecision(ensureKeyPair().private, cardId, verb, decidedAtSec)
}
