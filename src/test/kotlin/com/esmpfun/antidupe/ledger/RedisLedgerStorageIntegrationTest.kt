package com.esmpfun.antidupe.ledger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import io.lettuce.core.RedisClient
import org.bukkit.Material
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the two Redis-backend faults from the 26.3 review (F8) against a real Redis:
 *
 *  - concurrent appends for different players must not corrupt any player's hash chain. This
 *    fails if a write's MULTI runs on the connection the reads share, because a concurrent
 *    tip read gets queued into the transaction and comes back empty, producing a stray
 *    genesis entry mid-chain.
 *  - a balance written by one server process must be visible to another. This fails if the
 *    per-JVM read-through balance cache is consulted for a backend other processes can write.
 *
 * Skipped automatically when nothing is listening on localhost:6379. To run it:
 *   docker run -d --name adp-redis -p 6379:6379 redis:7-alpine
 */
class RedisLedgerStorageIntegrationTest {

    private val host = "localhost"
    private val port = 6379
    private val db = 15                       // kept off db 0 so a dev's real data is untouched
    private val logger: Logger = Logger.getLogger("RedisLedgerStorageIntegrationTest")
    private val stores = mutableListOf<LedgerStorage>()

    private fun redisReachable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 200); true }
    } catch (e: Exception) { false }

    private fun newStore(): RedisLedgerStorage = runBlocking {
        RedisLedgerStorage.create(host, port, null, db, 5, logger).also { stores += it }
    }

    @BeforeTest
    fun requireRedis() {
        org.junit.jupiter.api.Assumptions.assumeTrue(redisReachable(), "no Redis on $host:$port")
    }

    @AfterTest
    fun cleanup() {
        stores.forEach { runCatching { it.close() } }
        stores.clear()
        // Wipe only the scratch db so a rerun starts clean.
        runCatching {
            val client = RedisClient.create("redis://$host:$port/$db")
            try { client.connect().use { it.sync().flushdb() } } finally { client.shutdown() }
        }
    }

    @Test
    fun `concurrent appends across players keep every chain intact`() = runBlocking {
        val store = newStore()
        val players = List(6) { UUID.randomUUID() }
        val perPlayer = 15

        coroutineScope {
            players.forEach { p ->
                repeat(perPlayer) {
                    launch(Dispatchers.Default) {
                        store.appendBuilt(p, LedgerAction.PICKUP, Material.DIAMOND, 1, LedgerMetadata())
                    }
                }
            }
        }

        players.forEach { p ->
            val result = store.verifyChainIntegrity(p)
            assertTrue(result.valid, "chain for $p reported invalid: ${result.error}")
            assertEquals(perPlayer, result.entriesVerified, "wrong entry count for $p")
        }
    }

    @Test
    fun `a balance written by one client is visible to another`() = runBlocking {
        val writer = newStore()
        val reader = newStore()                         // stands in for a second server process
        val player = UUID.randomUUID()

        writer.appendBuilt(player, LedgerAction.PICKUP, Material.EMERALD, 10, LedgerMetadata())
        assertEquals(10, reader.getBalance(player, Material.EMERALD), "second client did not see the first write")

        writer.appendBuilt(player, LedgerAction.PICKUP, Material.EMERALD, 5, LedgerMetadata())
        assertEquals(15, reader.getBalance(player, Material.EMERALD), "second client served a stale cached balance")
    }
}
