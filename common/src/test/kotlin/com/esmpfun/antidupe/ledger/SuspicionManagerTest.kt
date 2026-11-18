package com.esmpfun.antidupe.ledger

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SuspicionManagerTest {

    private val player: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val day = SuspicionManager.FLOOR_HALF_LIFE_MS

    @Test
    fun `an automatic floor halves after a clean day`() {
        val s = SuspicionManager()
        s.bumpFloor(player, 40.0, now = 0)
        s.decay(now = day - 1)
        assertEquals(40.0, s.floor(player))
        s.decay(now = day)
        assertEquals(20.0, s.floor(player))
        s.decay(now = 2 * day)
        assertEquals(10.0, s.floor(player))
    }

    @Test
    fun `a new detection restarts the clean day`() {
        val s = SuspicionManager()
        s.bumpFloor(player, 40.0, now = 0)
        s.bumpFloor(player, 10.0, now = day - 10)
        s.decay(now = day)
        assertEquals(50.0, s.floor(player))
    }

    @Test
    fun `a confirmed duper stays pinned high`() {
        val s = SuspicionManager()
        s.confirm(player)
        repeat(10) { s.decay(now = (it + 1) * day) }
        assertEquals(SuspicionManager.CONFIRM_FLOOR, s.suspicion(player))
    }

    @Test
    fun `a faded false alarm no longer lowers the threshold`() {
        val s = SuspicionManager()
        s.bumpFloor(player, 50.0, now = 0)
        repeat(12) { s.decay(now = (it + 1) * day) }
        assertEquals(5.0, s.effectiveThreshold(player, 5))
    }
}
