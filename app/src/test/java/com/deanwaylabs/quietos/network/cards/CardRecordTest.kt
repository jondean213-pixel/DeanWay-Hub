package com.deanwaylabs.quietos.network.cards

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card lifecycle semantics (pure): a card is decidable only while
 * pending, undecided, and unexpired — the app-side half of the
 * fail-closed design (the host collector enforces the same rules
 * again, independently).
 */
class CardRecordTest {

    private fun record(
        state: String = "pending",
        decidedVerb: String? = null,
        expiresAtSec: Long = 2000
    ) = CardRecord(
        id = "abc123", kind = "governor_approval", title = "t",
        createdAtSec = 1000, expiresAtSec = expiresAtSec, state = state,
        bodyEnc = "", heldId = null, decidedVerb = decidedVerb
    )

    @Test
    fun freshCardIsPending() {
        assertTrue(record().isPending)
        assertFalse(record().isExpired(1500))
    }

    @Test
    fun decidedCardIsNotPending() {
        assertFalse(record(decidedVerb = "approve").isPending)
    }

    @Test
    fun settledCardIsNotPending() {
        assertFalse(record(state = "answered").isPending)
        assertFalse(record(state = "expired").isPending)
    }

    @Test
    fun expiryBoundary() {
        assertFalse(record().isExpired(1999))
        assertTrue(record().isExpired(2000))
        assertTrue(record().isExpired(2001))
    }
}
