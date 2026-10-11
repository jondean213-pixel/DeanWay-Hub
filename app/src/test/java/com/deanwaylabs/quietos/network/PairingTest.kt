package com.deanwaylabs.quietos.network

import com.deanwaylabs.quietos.network.pairing.PairedDevice
import com.deanwaylabs.quietos.network.pairing.PairingError
import com.deanwaylabs.quietos.network.pairing.canonicalCode
import com.deanwaylabs.quietos.network.pairing.deriveChannelKey
import com.deanwaylabs.quietos.network.pairing.keyFingerprint
import com.deanwaylabs.quietos.network.pairing.newPairingCode
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for pairing.
 * HKDF vectors are known-answer from the Python bench
 * (quietos/network/pairing.py) — the Kotlin port must match exactly.
 */
class PairingTest {

    // Known-answer vectors from the Python bench (2026-10-11).
    @Test fun hkdfMatchesPythonBench() {
        val key = deriveChannelKey("K7Q2-M9XD")
        val hex = key.joinToString("") { "%02x".format(it) }
        assertEquals(
            "c930548d94b31fe9c0f8172d03ed9106796885551b71c55484a69cc88a16376c",
            hex
        )
    }

    @Test fun fingerprintMatchesPythonBench() {
        val key = deriveChannelKey("K7Q2-M9XD")
        assertEquals("980138914a630a2c", keyFingerprint(key))
    }

    @Test fun hkdfSecondVector() {
        val key = deriveChannelKey("TEST-CODE")
        val hex = key.joinToString("") { "%02x".format(it) }
        assertEquals(
            "312ae566513c2bf117437a0dc21ac90d001e3c9cb5dea91809c3727d1befd266",
            hex
        )
        assertEquals("5c1226d78243d550", keyFingerprint(key))
    }

    @Test fun sameCodeBothSidesSameKey() {
        val a = PairedDevice.pair("K7Q2-M9XD", name = "phone-a")
        val b = PairedDevice.pair("K7Q2-M9XD", name = "phone-b")
        assertTrue(a.trusts(b))
        assertTrue(b.trusts(a))
        assertEquals(a.keyId, b.keyId)
        // But they have distinct device ids.
        assertNotEquals(a.deviceId, b.deviceId)
    }

    @Test fun differentCodesDifferentKeys() {
        val a = PairedDevice.pair("K7Q2-M9XD")
        val b = PairedDevice.pair("AAAA-BBBB")
        assertFalse(a.trusts(b))
    }

    @Test fun codeIsCaseAndDashInsensitive() {
        val a = PairedDevice.pair("k7q2-m9xd")
        val b = PairedDevice.pair("K7Q2M9XD")
        val c = PairedDevice.pair("k7q2 m9xd")
        assertTrue(a.trusts(b))
        assertTrue(b.trusts(c))
    }

    @Test fun badCodeRejected() {
        try {
            PairedDevice.pair("ABC")
            fail("expected PairingError")
        } catch (e: PairingError) {
            // expected
        }
        try {
            PairedDevice.pair("!!!!-!!!!")
            fail("expected PairingError")
        } catch (e: PairingError) {
            // expected
        }
    }

    @Test fun generatedCodeHasRightShape() {
        val code = newPairingCode()
        // e.g. "K7Q2-M9XD" — 8 chars from the unambiguous alphabet.
        assertEquals(9, code.length)
        assertEquals('-', code[4])
        val canon = canonicalCode(code)
        assertEquals(8, canon.length)
        assertTrue(canon.all { it in "ABCDEFGHJKMNPQRSTUVWXYZ23456789" })
    }

    @Test fun generatedCodesAreUnique() {
        val codes = (1..20).map { newPairingCode() }.toSet()
        assertEquals(20, codes.size)
    }

    @Test fun keyIs32Bytes() {
        assertEquals(32, deriveChannelKey("K7Q2-M9XD").size)
    }
}
