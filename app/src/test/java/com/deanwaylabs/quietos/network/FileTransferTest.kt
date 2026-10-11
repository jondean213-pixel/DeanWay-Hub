package com.deanwaylabs.quietos.network

import com.deanwaylabs.quietos.network.pairing.PairedDevice
import com.deanwaylabs.quietos.network.transfer.FileReceiver
import com.deanwaylabs.quietos.network.transfer.FileSender
import com.deanwaylabs.quietos.network.transfer.IntegrityError
import com.deanwaylabs.quietos.network.transfer.MemoryTransport
import com.deanwaylabs.quietos.network.transfer.RefusedError
import com.deanwaylabs.quietos.network.transfer.decryptChunk
import com.deanwaylabs.quietos.network.transfer.encryptChunk
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for encrypted file transfer.
 * Mirrors the Python bench tests (quietos/tests/test_transfer.py).
 */
class FileTransferTest {

    private fun pairedPair(): Pair<PairedDevice, PairedDevice> {
        val a = PairedDevice.pair("K7Q2-M9XD", name = "phone-a")
        val b = PairedDevice.pair("K7Q2-M9XD", name = "phone-b")
        return a to b
    }

    @Test fun chunkEncryptDecryptRoundTrip() {
        val (a, _) = pairedPair()
        val plaintext = "hello quietos".toByteArray()
        val (nonce, ct) = encryptChunk(a.channelKey, plaintext)
        assertArrayEquals(plaintext, decryptChunk(a.channelKey, nonce, ct))
    }

    @Test fun chunkTamperFailsAuthentication() {
        val (a, _) = pairedPair()
        val (nonce, ct) = encryptChunk(a.channelKey, "secret".toByteArray())
        ct[0] = (ct[0] + 1).toByte()
        try {
            decryptChunk(a.channelKey, nonce, ct)
            fail("expected IntegrityError")
        } catch (e: IntegrityError) {
            // expected
        }
    }

    @Test fun smallFileRoundTrip() {
        val (a, b) = pairedPair()
        val ta = MemoryTransport()
        val tb = MemoryTransport()
        MemoryTransport.link(ta, tb)

        val data = "Hello from Phone A".toByteArray()
        var received: Pair<String, ByteArray>? = null
        val receiverThread = Thread {
            received = FileReceiver(b, tb).receive()
        }.apply { start() }

        val receipt = FileSender(a, ta).push("greeting.txt", data)
        receiverThread.join(5000)

        assertEquals("greeting.txt", receipt.name)
        assertEquals(data.size.toLong(), receipt.size)
        assertNotNull(received)
        assertEquals("greeting.txt", received!!.first)
        assertArrayEquals(data, received!!.second)
    }

    @Test fun multiChunkFileRoundTrip() {
        val (a, b) = pairedPair()
        val ta = MemoryTransport()
        val tb = MemoryTransport()
        MemoryTransport.link(ta, tb)

        // 100KB forces multiple 16KB chunks.
        val data = ByteArray(100 * 1024) { (it % 251).toByte() }
        var received: Pair<String, ByteArray>? = null
        val receiverThread = Thread {
            received = FileReceiver(b, tb).receive()
        }.apply { start() }

        val receipt = FileSender(a, ta).push("big.bin", data)
        receiverThread.join(10000)

        assertTrue("expected multiple chunks, got ${receipt.chunks}",
            receipt.chunks > 1)
        assertNotNull(received)
        assertArrayEquals(data, received!!.second)
    }

    @Test fun unpairedSenderRefused() {
        val (a, _) = pairedPair()
        val stranger = PairedDevice.pair("ZZZZ-YYYY", name = "stranger")
        val ta = MemoryTransport()
        val tb = MemoryTransport()
        MemoryTransport.link(ta, tb)

        var refused: Exception? = null
        val receiverThread = Thread {
            try {
                FileReceiver(a, tb).receive()
            } catch (e: RefusedError) {
                refused = e
            }
        }.apply { start() }

        try {
            FileSender(stranger, ta).push("evil.txt", "x".toByteArray())
            fail("expected RefusedError on sender side")
        } catch (e: RefusedError) {
            // expected — the receiver declined
        }
        receiverThread.join(5000)
        assertNotNull("receiver should also see RefusedError", refused)
    }

    @Test fun emptyFileRoundTrip() {
        val (a, b) = pairedPair()
        val ta = MemoryTransport()
        val tb = MemoryTransport()
        MemoryTransport.link(ta, tb)

        var received: Pair<String, ByteArray>? = null
        val receiverThread = Thread {
            received = FileReceiver(b, tb).receive()
        }.apply { start() }

        val receipt = FileSender(a, ta).push("empty.txt", ByteArray(0))
        receiverThread.join(5000)

        assertEquals(0, receipt.chunks)
        assertNotNull(received)
        assertEquals(0, received!!.second.size)
    }
}
