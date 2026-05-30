package com.server.antidupe.ledger

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.Range
import io.lettuce.core.RedisClient
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.flow.toList
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
        private const val KEY_PLAYER_ENTRIES = "ledger:player:"
        private const val KEY_TIP = "ledger:tip"
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

    override suspend fun writeEntry(entry: LedgerEntry) {
        val entryKey = "$KEY_ENTRY${entry.id}"
        val playerEntriesKey = "${KEY_PLAYER_ENTRIES}${entry.player}:entries"
        val balanceKey = "$KEY_BALANCE${entry.player}:${entry.material.name}"
        val recentKey = "$KEY_RECENT${entry.player}:${entry.material.name}"

        redis.set(entryKey, entry.toJson())
        redis.zadd(playerEntriesKey, entry.timestamp.toDouble(), entry.id.toString())

        val tipJson = JSONObject().apply {
            put("lastEntryId", entry.id.toString())
            put("lastHash", entry.hash)
            put("timestamp", entry.timestamp)
        }.toString()
        redis.set(KEY_TIP, tipJson)
        redis.incrby(balanceKey, entry.quantity.toLong())

        if (entry.quantity > 0) {
            redis.zadd(recentKey, entry.timestamp.toDouble(), "${entry.quantity}:${entry.id}")
            val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
            redis.zremrangebyscore(recentKey, Range.create(Double.NEGATIVE_INFINITY, cutoff.toDouble()))
        }
    }

    override suspend fun readTipInternal(): ChainTip? {
        val tipJson = redis.get(KEY_TIP) ?: return null
        val obj = JSONObject(tipJson)
        return ChainTip(UUID.fromString(obj.getString("lastEntryId")), obj.getString("lastHash"), obj.getLong("timestamp"))
    }

    override suspend fun getEntry(id: UUID): LedgerEntry? {
        val json = redis.get("$KEY_ENTRY$id") ?: return null
        return LedgerEntry.fromJson(json)
    }

    override suspend fun getBalance(player: UUID, material: Material): Int {
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

    override suspend fun getAllEntriesChronological(): List<LedgerEntry> {
        return scanKeys("${KEY_ENTRY}*")
            .mapNotNull { redis.get(it)?.let { json -> LedgerEntry.fromJson(json) } }
            .sortedBy { it.timestamp }
    }

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
