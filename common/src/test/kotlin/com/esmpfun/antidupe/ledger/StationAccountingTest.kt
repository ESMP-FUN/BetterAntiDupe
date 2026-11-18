package com.esmpfun.antidupe.ledger

import org.bukkit.Material
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StationAccountingTest {

    private val tracked = setOf(Material.DIAMOND_BLOCK, Material.NETHERITE_INGOT)
    private val isTracked: (Material) -> Boolean = { it in tracked }

    @Test
    fun `uncrafting a tracked block debits the block`() {
        val grid = listOf(Material.DIAMOND_BLOCK, null, null, null, null, null, null, null, null)
        assertEquals(mapOf(Material.DIAMOND_BLOCK to 1), StationAccounting.ingredientDebits(grid, isTracked, 1))
    }

    @Test
    fun `untracked ingredients and empty slots debit nothing`() {
        val grid = listOf(Material.DIAMOND, Material.AIR, null, Material.DIAMOND)
        assertEquals(emptyMap(), StationAccounting.ingredientDebits(grid, isTracked, 3))
    }

    @Test
    fun `each occupied slot costs one per craft`() {
        val grid = listOf(Material.NETHERITE_INGOT, Material.NETHERITE_INGOT, Material.DIAMOND_BLOCK)
        assertEquals(
            mapOf(Material.NETHERITE_INGOT to 4, Material.DIAMOND_BLOCK to 2),
            StationAccounting.ingredientDebits(grid, isTracked, 2)
        )
        assertEquals(emptyMap(), StationAccounting.ingredientDebits(grid, isTracked, 0))
    }

    @Test
    fun `craft count follows shift clicks`() {
        assertEquals(1, StationAccounting.craftCount(false, 9, 9))
        assertEquals(3, StationAccounting.craftCount(true, 27, 9))
        assertEquals(1, StationAccounting.craftCount(true, 0, 9))
        assertEquals(5, StationAccounting.craftCount(true, 5, 0))
    }

    @Test
    fun `a lectern put counts only when the book really moved`() {
        assertTrue(StationAccounting.lecternBookPlaced(false, true, 1, 0))
        assertFalse(StationAccounting.lecternBookPlaced(true, true, 1, 0))
        assertFalse(StationAccounting.lecternBookPlaced(false, false, 1, 1))
        assertFalse(StationAccounting.lecternBookPlaced(false, true, 1, 1))
    }

    @Test
    fun `station credits are summed per player and used once`() {
        val credits = StationCredits()
        val alice = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val bob = UUID.fromString("00000000-0000-0000-0000-00000000000b")
        credits.add(alice, Material.NETHERITE_INGOT, 2)
        credits.add(alice, Material.NETHERITE_INGOT, 3)
        credits.add(alice, Material.DIAMOND_BLOCK, 0)
        credits.add(bob, Material.NETHERITE_INGOT, 1)
        assertEquals(mapOf(Material.NETHERITE_INGOT to 5), credits.consume(alice))
        assertEquals(emptyMap(), credits.consume(alice))
        assertEquals(mapOf(Material.NETHERITE_INGOT to 1), credits.consume(bob))
    }
}
