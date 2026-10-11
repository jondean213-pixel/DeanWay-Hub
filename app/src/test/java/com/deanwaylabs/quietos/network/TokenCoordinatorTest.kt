package com.deanwaylabs.quietos.network

import com.deanwaylabs.quietos.network.token.Bucket
import com.deanwaylabs.quietos.network.token.RouteAction
import com.deanwaylabs.quietos.network.token.TokenCoordinator
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the Attention Token.
 * Mirrors the Python bench tests (quietos/tests/test_token.py).
 */
class TokenCoordinatorTest {

    private var now = 1000.0
    private fun coordinator() = TokenCoordinator(clock = { now })

    @Test fun claimGrantsWhenNobodyHolds() {
        val c = coordinator()
        assertTrue(c.claim("call-1", "phone-a", Bucket.NOW))
        assertEquals("phone-a", c.owner("call-1"))
    }

    @Test fun secondClaimByOtherDeviceDenied() {
        val c = coordinator()
        assertTrue(c.claim("call-1", "phone-a", Bucket.NOW))
        assertFalse(c.claim("call-1", "phone-b", Bucket.NOW))
        assertEquals("phone-a", c.owner("call-1"))
    }

    @Test fun reclaimByOwnerIsHeartbeat() {
        val c = coordinator()
        assertTrue(c.claim("call-1", "phone-a", Bucket.NOW))
        now += 30.0
        // Re-claim by owner renews the lease instead of conflicting.
        assertTrue(c.claim("call-1", "phone-a", Bucket.NOW))
        now += 50.0  // 80s after first claim, but lease was renewed at 30s
        assertEquals("phone-a", c.owner("call-1"))
    }

    @Test fun heartbeatRenewsLease() {
        val c = coordinator()
        assertTrue(c.claim("call-1", "phone-a", Bucket.NOW))
        now += 50.0
        assertTrue(c.heartbeat("call-1", "phone-a"))
        now += 50.0  // 100s after claim, 50s after heartbeat
        assertEquals("phone-a", c.owner("call-1"))
    }

    @Test fun heartbeatByNonOwnerDenied() {
        val c = coordinator()
        assertTrue(c.claim("call-1", "phone-a", Bucket.NOW))
        assertFalse(c.heartbeat("call-1", "phone-b"))
    }

    @Test fun yieldReleasesToken() {
        val c = coordinator()
        assertTrue(c.claim("call-1", "phone-a", Bucket.NOW))
        assertTrue(c.yieldToken("call-1", "phone-a"))
        assertNull(c.owner("call-1"))
        // Another device can now claim it.
        assertTrue(c.claim("call-1", "phone-b", Bucket.NOW))
    }

    @Test fun yieldByNonOwnerDenied() {
        val c = coordinator()
        assertTrue(c.claim("call-1", "phone-a", Bucket.NOW))
        assertFalse(c.yieldToken("call-1", "phone-b"))
        assertEquals("phone-a", c.owner("call-1"))
    }

    @Test fun tokenExpiresAfterTtl() {
        val c = coordinator()
        assertTrue(c.claim("call-1", "phone-a", Bucket.NOW))
        now += 61.0  // past the 60s default lease
        assertNull(c.owner("call-1"))
        // Expired token is claimable again — a dead device never
        // swallows an emergency.
        assertTrue(c.claim("call-1", "phone-b", Bucket.NOW))
    }

    @Test fun tokensArePerEvent() {
        val c = coordinator()
        assertTrue(c.claim("call-1", "phone-a", Bucket.NOW))
        assertTrue(c.claim("call-2", "phone-b", Bucket.NOW))
        assertEquals("phone-a", c.owner("call-1"))
        assertEquals("phone-b", c.owner("call-2"))
    }

    @Test fun routeNowWithOwnerInterruptsThere() {
        val c = coordinator()
        c.claim("call-1", "phone-a", Bucket.NOW)
        val action = c.route("call-1", Bucket.NOW)
        assertTrue(action is RouteAction.Interrupt)
        assertEquals("phone-a", (action as RouteAction.Interrupt).ownerDeviceId)
    }

    @Test fun routeNowWithoutOwnerEscalates() {
        val c = coordinator()
        assertEquals(RouteAction.Escalate, c.route("call-1", Bucket.NOW))
    }

    @Test fun routeSoonDigests() {
        val c = coordinator()
        assertEquals(RouteAction.Digest, c.route("e1", Bucket.SOON))
        assertEquals(RouteAction.Digest, c.route("e2", Bucket.DIGEST))
    }

    @Test fun routeQuietAndSpamStayPut() {
        val c = coordinator()
        assertEquals(RouteAction.Quiet, c.route("e1", Bucket.QUIET))
        assertEquals(RouteAction.Quiet, c.route("e2", Bucket.SPAM))
    }

    @Test fun auditLogReceivesEvents() {
        val events = mutableListOf<String>()
        val c = TokenCoordinator(
            clock = { now },
            audit = { event, _ -> events.add(event) }
        )
        c.claim("call-1", "phone-a", Bucket.NOW)
        c.yieldToken("call-1", "phone-a")
        assertTrue("token_claimed" in events)
        assertTrue("token_yielded" in events)
    }
}
