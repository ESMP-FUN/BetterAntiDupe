package com.server.antidupe.ledger

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.Range
import io.lettuce.core.RedisClient
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bukkit.Material
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisLedgerStorage internal constructor(
    private val client: RedisClient,
    private val connection: StatefulRedisConnection<String, String>,
    private val redis: RedisCoroutinesCommands<String, String>,
    logger: Logger
) : LedgerStorage(logger) {

    companion object {
        private const val KEY_ENTRY = "ledger:entry:"
        private const val KEY_PLAYER_ENTRIES = "ledger:player:"   // {uuid}:entries (ZSET by ts), {uuid}:chain (LIST insertion order)
        private const val KEY_GLOBAL_TIP = "ledger:tip"            // display only, last-write-wins
        private const val KEY_PLAYER_TIP = "ledger:tip:"           // {uuid} -> per-player chain tip
        private const val KEY_BALANCE = "balance:"
        private const val KEY_RECENT = "ledger:recent:"
        private const val SCAN_BATCH = 500L
        private const val RECENT_WINDOW_MS = 5 * 60 * 1000L

        suspend fun create(host: String, port: Int, password: String?, database: Int, logger: Logger): RedisLedgerStorage {
            val uri = if (password.isNullOrBlank()) "redis://$host:$port/$database"
                      else "redis://:$password@$host:$port/$database"
            val client = RedisClient.create(uri)
            val connection = client.connect()
            val coroutines = connection.coroutines()
            val pong = coroutines.ping()
            if (pong != "PONG") {
                connection.close(); client.shutdown()
                throw IllegalStateException("Redis connection failed: expected PONG, got $pong")
            }
            logger.info("[Ledger] Connected to Redis at $host:$port")
            return RedisLedgerStorage(client, connection, coroutines, logger)
        }
    }

    override fun close() {
        try { connection.close() } catch (e: Exception) { logger.warning("[Ledger] connection close: ${e.message}") }
        try { client.shutdown(100, 500, TimeUnit.MILLISECONDS) } catch (e: Exception) { logger.warning("[Ledger] client shutdown: ${e.message}") }
    }

    /**
     * MULTI/EXEC is connection-scoped state, so a single global mutex keeps concurrent
     * appends (different players bypass the per-player append lock) from interleaving
     * transactions. Uses the sync command API - lettuce's coroutine API doesn't expose
     * transactions - on the IO dispatcher.
     */
    private val txMutex = Mutex()

    override suspend fun writeEntry(entry: LedgerEntry): Unit = txMutex.withLock {
        withContext(Dispatchers.IO) {
            val entryKey = "$KEY_ENTRY${entry.id}"
            val playerEntriesKey = "${KEY_PLAYER_ENTRIES}${entry.player}:entries"
            val playerChainKey = "${KEY_PLAYER_ENTRIES}${entry.player}:chain"
            val balanceKey = "$KEY_BALANCE${entry.player}:${entry.material.name}"
            val recentKey = "$KEY_RECENT${entry.player}:${entry.material.name}"

            val tipJson = JSONObject().apply {
                put("lastEntryId", entry.id.toString())
                put("lastHash", entry.hash)
                put("timestamp", entry.timestamp)
            }.toString()

            val sync = connection.sync()
            sync.multi()
            try {
                sync.set(entryKey, entry.toJson())
                sync.zadd(playerEntriesKey, entry.timestamp.toDouble(), entry.id.toString())
                // Append-order list for chain verification (immune to same-ms ties, unlike the ZSET).
                sync.rpush(playerChainKey, entry.id.toString())
                sync.set("$KEY_PLAYER_TIP${entry.player}", tipJson)  // per-player chain tip
                sync.set(KEY_GLOBAL_TIP, tipJson)                     // global display tip
                sync.incrby(balanceKey, entry.quantity.toLong())
                if (entry.quantity > 0) {
                    sync.zadd(recentKey, entry.timestamp.toDouble(), "${entry.quantity}:${entry.id}")
                    val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
                    sync.zremrangebyscore(recentKey, Range.create(Double.NEGATIVE_INFINITY, cutoff.toDouble()))
                }
                sync.exec()
            } catch (e: Exception) {
                try { sync.discard() } catch (_: Exception) {}
                throw e
            }
        }
    }

    override suspend fun readPlayerTip(player: UUID): ChainTip? {
        val tipJson = redis.get("$KEY_PLAYER_TIP$player") ?: return null
        val obj = JSONObject(tipJson)
        return ChainTip(UUID.fromString(obj.getString("lastEntryId")), obj.getString("lastHash"), obj.getLong("timestamp"))
    }

    override suspend fun getTip(): ChainTip? {
        val tipJson = redis.get(KEY_GLOBAL_TIP) ?: return null
        val obj = JSONObject(tipJson)
        return ChainTip(UUID.fromString(obj.getString("lastEntryId")), obj.getString("lastHash"), obj.getLong("timestamp"))
    }

    override suspend fun getTrackedPlayers(): Set<UUID> {
        // Player entry-index keys look like "ledger:player:<uuid>:entries".
        return scanKeys("${KEY_PLAYER_ENTRIES}*:entries").mapNotNullTo(mutableSetOf()) { key ->
            val uuidStr = key.removePrefix(KEY_PLAYER_ENTRIES).removeSuffix(":entries")
            try { UUID.fromString(uuidStr) } catch (e: IllegalArgumentException) { null }
        }
    }

    override suspend fun getEntry(id: UUID): LedgerEntry? {
        val json = redis.get("$KEY_ENTRY$id") ?: return null
        return LedgerEntry.fromJson(json)
    }

    override suspend fun readBalanceFromStorage(player: UUID, material: Material): Int {
        return redis.get("$KEY_BALANCE$player:${material.name}")?.toIntOrNull() ?: 0
    }

    override suspend fun getAllBalances(player: UUID): Map<Material, Int> {
        val keys = scanKeys("$KEY_BALANCE$player:*")
        if (keys.isEmpty()) return emptyMap()
        val out = mutableMapOf<Material, Int>()
        for (key in keys) {
            val mat = try { Material.valueOf(key.substringAfterLast(":")) } catch (e: IllegalArgumentException) { continue }
            val bal = redis.get(key)?.toIntOrNull() ?: 0
            if (bal != 0) out[mat] = bal
        }
        return out
    }

    override suspend fun getRecentAcquisitions(player: UUID, material: Material, windowMs: Long): Int {
        val recentKey = "$KEY_RECENT$player:${material.name}"
        val cutoff = System.currentTimeMillis() - windowMs
        val members = redis.zrangebyscore(recentKey, Range.create(cutoff.toDouble(), Double.POSITIVE_INFINITY)).toList()
        var total = 0
        for (m in members) { m.substringBefore(':').toIntOrNull()?.let { if (it > 0) total += it } }
        return total
    }

    override suspend fun getPlayerEntries(player: UUID, limit: Long, offset: Long): List<LedgerEntry> {
        val key = "${KEY_PLAYER_ENTRIES}$player:entries"
        val ids = redis.zrevrange(key, offset, offset + limit - 1).toList()
        return ids.mapNotNull { getEntry(UUID.fromString(it)) }
    }

    override suspend fun pruneRecentWindows() {
        val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
        for (key in scanKeys("$KEY_RECENT*")) {
            redis.zremrangebyscore(key, Range.create(Double.NEGATIVE_INFINITY, cutoff.toDouble()))
        }
    }

    override suspend fun getPlayerChainOrdered(player: UUID): List<LedgerEntry> {
        val chainKey = "${KEY_PLAYER_ENTRIES}$player:chain"
        val ids = redis.lrange(chainKey, 0, -1).toList()  // insertion order, oldest first
        return ids.mapNotNull { getEntry(UUID.fromString(it)) }
    }

    override suspend fun markEntityPickup(
        entityUuid: UUID, playerUuid: UUID, material: Material, amount: Int
    ): PreviousPickup? {
        val key = "pickup:$entityUuid"
        val now = System.currentTimeMillis()
        val value = "$playerUuid|${material.name}|$amount|$now"
        val ttlSeconds = 30L * 86_400L
        val setResult = redis.set(key, value, SetArgs.Builder.nx().ex(ttlSeconds))
        if (setResult == "OK") return null

        val existing = redis.get(key) ?: return null
        val parts = existing.split("|")
        if (parts.size != 4) return null
        return try {
            PreviousPickup(
                playerUuid = UUID.fromString(parts[0]),
                material = Material.valueOf(parts[1]),
                amount = parts[2].toInt(),
                pickedUpAt = parts[3].toLong()
            )
        } catch (e: Exception) { null }
    }

    // pickup_history pruning: Redis TTL handles expiry automatically; no manual pass needed.

    private suspend fun scanKeys(pattern: String): List<String> {
        val out = mutableListOf<String>()
        var cursor: ScanCursor = ScanCursor.INITIAL
        val args = ScanArgs.Builder.matches(pattern).limit(SCAN_BATCH)
        do {
            val result = redis.scan(cursor, args) ?: break
            out.addAll(result.keys)
            cursor = result
        } while (!cursor.isFinished)
        return out
    }
}
