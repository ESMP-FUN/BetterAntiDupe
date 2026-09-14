package com.esmpfun.antidupe.ledger

import kotlinx.coroutines.runBlocking
import org.bukkit.Material
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldStockTest {

    private val alice: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bob: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val block = Material.DIAMOND_BLOCK

    private class Alert(val actor: UUID, val owner: UUID, val deficit: Int)

    private fun stock(storage: LedgerStorage, alerts: MutableList<Alert>, threshold: Int = 3) =
        WorldStock(storage, { threshold }, Logger.getAnonymousLogger()) { actor, owner, _, deficit, _ ->
            alerts += Alert(actor, owner, deficit)
        }.also { it.ready = true }

    @Test
    fun `storing and taking back your own items is quiet`() = runBlocking {
        val alerts = mutableListOf<Alert>()
        val ws = stock(MemoryLedgerStorage(Logger.getAnonymousLogger()), alerts)
        ws.out(mapOf((alice to block) to 10))
        ws.back(alice, mapOf((alice to block) to 10), null)
        assertEquals(0, alerts.size)
    }

    @Test
    fun `a chest trade is quiet`() = runBlocking {
        val alerts = mutableListOf<Alert>()
        val ws = stock(MemoryLedgerStorage(Logger.getAnonymousLogger()), alerts)
        ws.out(mapOf((alice to block) to 10))
        ws.back(bob, mapOf((alice to block) to 10), null)
        assertEquals(0, alerts.size)
    }

    @Test
    fun `more coming out than went in alerts once, naming owner and taker`() = runBlocking {
        val alerts = mutableListOf<Alert>()
        val storage = MemoryLedgerStorage(Logger.getAnonymousLogger())
        val ws = stock(storage, alerts)
        ws.out(mapOf((alice to block) to 10))
        ws.back(bob, mapOf((alice to block) to 20), "world 1, 2, 3")
        assertEquals(1, alerts.size)
        assertEquals(bob, alerts[0].actor)
        assertEquals(alice, alerts[0].owner)
        assertEquals(10, alerts[0].deficit)
        assertEquals(0, storage.adjustWorldStock(alice, block, 0))
    }

    @Test
    fun `small gaps add up to an alert`() = runBlocking {
        val alerts = mutableListOf<Alert>()
        val ws = stock(MemoryLedgerStorage(Logger.getAnonymousLogger()), alerts, threshold = 5)
        ws.back(bob, mapOf((alice to block) to 3), null)
        assertEquals(0, alerts.size)
        ws.back(bob, mapOf((alice to block) to 3), null)
        assertEquals(1, alerts.size)
        assertEquals(6, alerts[0].deficit)
    }

    @Test
    fun `before the history is counted a gap is reset quietly`() = runBlocking {
        val alerts = mutableListOf<Alert>()
        val storage = MemoryLedgerStorage(Logger.getAnonymousLogger())
        val ws = stock(storage, alerts).also { it.ready = false }
        ws.back(bob, mapOf((alice to block) to 50), null)
        assertEquals(0, alerts.size)
        assertEquals(0, storage.adjustWorldStock(alice, block, 0))
    }

    @Test
    fun `history counts puts, takes by owner and skips mined pickups, once`() = runBlocking {
        val storage = MemoryLedgerStorage(Logger.getAnonymousLogger())
        storage.appendBuilt(alice, LedgerAction.CONTAINER_PUT, block, -10, LedgerMetadata())
        storage.appendBuilt(alice, LedgerAction.PICKUP, block, 4, LedgerMetadata(notes = "MINE:DIAMOND_BLOCK|TOOL:IRON_PICKAXE"))
        storage.appendBuilt(bob, LedgerAction.CONTAINER_TAKE, block, 6, LedgerMetadata(relatedPlayer = alice))
        val alerts = mutableListOf<Alert>()
        val ws = stock(storage, alerts).also { it.ready = false }

        ws.seedIfNeeded(setOf(alice, bob))
        ws.seedIfNeeded(setOf(alice, bob))

        assertEquals(4, storage.adjustWorldStock(alice, block, 0))
        ws.back(bob, mapOf((alice to block) to 4), null)
        assertEquals(0, alerts.size)
    }
}
