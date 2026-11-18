package com.esmpfun.antidupe.ledger

import kotlinx.coroutines.runBlocking
import org.bukkit.Material
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

class LeftCreativeTest {

    @Test
    fun `a record reset after creative moves the balance but is not a fast gain`() = runBlocking {
        val storage = MemoryLedgerStorage(Logger.getAnonymousLogger())
        val player = UUID.randomUUID()
        storage.appendBuilt(player, LedgerAction.PICKUP, Material.DIAMOND_BLOCK, 3, LedgerMetadata())
        storage.appendBuilt(player, LedgerAction.LEFT_CREATIVE, Material.DIAMOND_BLOCK, 61,
            LedgerMetadata(notes = "LEFT_CREATIVE: ledger 3 -> 64"))

        assertEquals(64, storage.getBalance(player, Material.DIAMOND_BLOCK))
        assertEquals(3, storage.getRecentAcquisitions(player, Material.DIAMOND_BLOCK))
    }
}
