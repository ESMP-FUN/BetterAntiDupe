package com.esmpfun.antidupe

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PreventionGuardsTest {

    private data class Pile(val kind: String, val amount: Int)

    private fun remove(expected: List<Pile>, dropped: List<Pile>) =
        RevertedPlacementGuard.copiesToRemove(expected, dropped, { it.amount }, { a, b -> a.kind == b.kind })

    @Test
    fun `random piles of one stack are all removed`() {
        val out = remove(listOf(Pile("diamond", 64)), listOf(Pile("diamond", 21), Pile("diamond", 30), Pile("diamond", 13)))
        assertContentEquals(intArrayOf(21, 30, 13), out)
    }

    @Test
    fun `an unrelated drop in the same block is left alone`() {
        val out = remove(listOf(Pile("diamond", 10)), listOf(Pile("dirt", 5), Pile("diamond", 10)))
        assertContentEquals(intArrayOf(0, 10), out)
    }

    @Test
    fun `never removes more than the container held`() {
        val out = remove(listOf(Pile("diamond", 10)), listOf(Pile("diamond", 8), Pile("diamond", 8)))
        assertContentEquals(intArrayOf(8, 2), out)
    }

    @Test
    fun `one pile can be covered by two container slots`() {
        val out = remove(listOf(Pile("iron", 3), Pile("iron", 4)), listOf(Pile("iron", 7)))
        assertContentEquals(intArrayOf(7), out)
    }

    @Test
    fun `grace covers a round trip and is capped`() {
        assertEquals(1000L, HiddenMenuGuard.graceMs(0))
        assertEquals(1400L, HiddenMenuGuard.graceMs(200))
        assertEquals(5000L, HiddenMenuGuard.graceMs(10_000))
        assertEquals(1000L, HiddenMenuGuard.graceMs(-1))
    }
}
