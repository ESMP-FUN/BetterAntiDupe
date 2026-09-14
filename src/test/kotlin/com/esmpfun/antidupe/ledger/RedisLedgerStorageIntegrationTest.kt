package com.esmpfun.antidupe.ledger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
 * Needs a Redis on localhost:6379, otherwise every test here is skipped. To run it:
 *   docker run -d --name adp-redis -p 6379:6379 redis:7-alpine
 */
class RedisLedgerStorageIntegrationTest {

    private val host = "localhost"
    private val port = 6379
    private val db = 15                       // kept off db 0 so a dev's real data is untouched
    private val logger: Logger = Logger.getLogger("RedisLedgerStorageIntegrationTest")
    private val stores = mutableListOf<LedgerStorage>()

    /** Null when Redis answers, otherwise why it could not be reached. */
    private fun redisUnreachableReason(): String? = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 200) }
        null
    } catch (e: Exception) { e.toString() }

    private fun newStore(): RedisLedgerStorage = runBlocking {
        RedisLedgerStorage.create(host, port, null, db, 5, logger).also { stores += it }
    }

    @BeforeTest
    fun requireRedis() {
        val reason = redisUnreachableReason()
        org.junit.jupiter.api.Assumptions.assumeTrue(reason == null, "no Redis on $host:$port: $reason")
    }

    @AfterTest
    fun cleanup() {
        stores.forEach { runCatching { it.close() } }
        stores.clear()
        runCatching {
            val client = RedisClient.create("redis://$host:$port/$db")
            try { client.connect().use { it.sync().flushdb() } } finally { client.shutdown() }
        }
    }

    @Test
    fun `two servers baselining the same new player credit it once`() = runBlocking {
        val serverA = JoinBaseline(newStore())
        val serverB = JoinBaseline(newStore())
        val player = UUID.randomUUID()
        val snapshot: suspend () -> Map<Material, Int> = { kotlinx.coroutines.delay(50); mapOf(Material.DIAMOND_BLOCK to 10) }

        val results = coroutineScope {
            val scope = this
            listOf(serverA, serverB, serverA, serverB).map { server ->
                scope.async(Dispatchers.IO) { server.run(player, snapshot) }
            }.map { it.await() }
        }

        assertEquals(1, results.count { it })
        assertEquals(10, stores.first().getBalance(player, Material.DIAMOND_BLOCK))
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
        val reader = newStore()
        val player = UUID.randomUUID()

        writer.appendBuilt(player, LedgerAction.PICKUP, Material.EMERALD, 10, LedgerMetadata())
        assertEquals(10, reader.getBalance(player, Material.EMERALD), "second client did not see the first write")

        writer.appendBuilt(player, LedgerAction.PICKUP, Material.EMERALD, 5, LedgerMetadata())
        assertEquals(15, reader.getBalance(player, Material.EMERALD), "second client served a stale cached balance")
    }
}
