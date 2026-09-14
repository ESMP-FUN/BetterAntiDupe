package com.esmpfun.antidupe.ledger

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettleTrackerTest {

    private val player: UUID = UUID.fromString("00000000-0000-0000-0000-000000000004")

    @Test
    fun `settling until every begun movement has ended`() {
        var now = 0L
        val tracker = SettleTracker(staleAfterMs = 5_000, clock = { now })
        assertFalse(tracker.isSettling(player))
        tracker.begin(player)
        tracker.begin(player)
        tracker.end(player)
        assertTrue(tracker.isSettling(player))
        tracker.end(player)
        assertFalse(tracker.isSettling(player))
    }

    @Test
    fun `a movement that never ended stops counting once stale`() {
        var now = 0L
        val tracker = SettleTracker(staleAfterMs = 5_000, clock = { now })
        tracker.begin(player)
        now = 4_999
        assertTrue(tracker.isSettling(player))
        now = 5_000
        assertFalse(tracker.isSettling(player))
    }

    @Test
    fun `the version moves with every movement, finished or not`() {
        val tracker = SettleTracker()
        val before = tracker.version(player)
        tracker.begin(player)
        tracker.end(player)
        assertTrue(tracker.version(player) > before)
        assertFalse(tracker.isSettling(player))
    }

    @Test
    fun `an extra end does not go negative and hide the next movement`() {
        val tracker = SettleTracker()
        tracker.end(player)
        tracker.begin(player)
        tracker.end(player)
        tracker.end(player)
        tracker.begin(player)
        assertTrue(tracker.isSettling(player))
    }
}
