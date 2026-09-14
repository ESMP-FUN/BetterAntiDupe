package com.esmpfun.antidupe.ledger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bukkit.Material
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JoinBaselineTest {

    private val player: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val carried = mapOf(Material.DIAMOND_BLOCK to 10)

    @Test
    fun `a fast rejoin credits the inventory once`() = runBlocking {
        val storage = MemoryLedgerStorage(Logger.getAnonymousLogger())
        val baseline = JoinBaseline(storage)
        // The snapshot hops to the player's thread in the plugin, which is where two joins overlap.
        val results = (1..8).map {
            async(Dispatchers.Default) { baseline.run(player) { delay(50); carried } }
        }.awaitAll()

        assertEquals(1, results.count { it })
        assertEquals(10, storage.getBalance(player, Material.DIAMOND_BLOCK))
    }

    @Test
    fun `a later rejoin does not baseline again`() = runBlocking {
        val storage = MemoryLedgerStorage(Logger.getAnonymousLogger())
        val baseline = JoinBaseline(storage)
        assertTrue(baseline.run(player) { carried })
        assertFalse(baseline.run(player) { carried })
        assertEquals(10, storage.getBalance(player, Material.DIAMOND_BLOCK))
    }

    @Test
    fun `an empty inventory still leaves a marker`() = runBlocking {
        val storage = MemoryLedgerStorage(Logger.getAnonymousLogger())
        val baseline = JoinBaseline(storage)
        assertTrue(baseline.run(player) { emptyMap() })
        assertFalse(baseline.run(player) { carried })
        assertEquals(0, storage.getBalance(player, Material.DIAMOND_BLOCK))
    }
}
