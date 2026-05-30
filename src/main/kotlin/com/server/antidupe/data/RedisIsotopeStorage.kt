package com.server.antidupe.data

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.future.await
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.TimeUnit

class RedisIsotopeStorage(private val plugin: JavaPlugin) : IsotopeStorage {

    private var client: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null
    private var asyncCommands: RedisAsyncCommands<String, String>? = null

    @Volatile
    private var isConnected: Boolean = false

    private val scriptBurn = """
        local value = redis.call("GET", KEYS[1])
        if value == nil then return 0 end
        if string.sub(value, 1, 6) == "ACTIVE" then
            redis.call("SET", KEYS[1], "SPENT")
            return 1
        else
            return 0
        end
    """.trimIndent()

    override fun connect() {
        val cfg = plugin.config
        val host = cfg.getString("redis.host", "localhost")
        val port = cfg.getInt("redis.port", 6379)
        val database = cfg.getInt("redis.database", 0)
        val password = cfg.getString("redis.password", "")
        val timeout = cfg.getInt("redis.timeout", 10)

        val uri = if (password.isNullOrEmpty()) "redis://$host:$port/$database"
                  else "redis://:$password@$host:$port/$database"

        plugin.logger.info("Connecting to Redis at $host:$port (database: $database)...")
        try {
            client = RedisClient.create(uri)
            connection = client!!.connect()
            asyncCommands = connection!!.async()
            connection!!.timeout = java.time.Duration.ofSeconds(timeout.toLong())

            val pingResult = connection!!.sync().ping()
            if (pingResult != "PONG") {
                throw RedisConnectionException("Redis PING failed: expected PONG, got $pingResult")
            }
            isConnected = true
            plugin.logger.info("✓ Connected to Redis Security Backend")
        } catch (e: Exception) {
            isConnected = false
            try { connection?.close() } catch (_: Exception) {}
            try { client?.shutdown() } catch (_: Exception) {}
            throw RedisConnectionException("Failed to connect to Redis at $host:$port: ${e.message}", e)
        }
    }

    override fun disconnect() {
        isConnected = false
        try { connection?.close() } catch (e: Exception) { plugin.logger.warning("close: ${e.message}") }
        try { client?.shutdown(100, 500, TimeUnit.MILLISECONDS) } catch (e: Exception) { plugin.logger.warning("shutdown: ${e.message}") }
        connection = null; client = null; asyncCommands = null
    }

    override fun isConnected(): Boolean = isConnected && connection?.isOpen == true

    override suspend fun mint(isotopeId: UUID, owner: UUID, details: String): Boolean {
        val cmd = asyncCommands ?: throw IllegalStateException("Not connected to Redis")
        val key = "iso:$isotopeId"
        val value = "ACTIVE|$owner|${System.currentTimeMillis()}"
        val reply = cmd.set(key, value, SetArgs.Builder.nx()).toCompletableFuture().await()
        if (reply != "OK") {
            plugin.logger.severe("COLLISION DETECTED: UUID $isotopeId already exists ($details)")
            return false
        }
        return true
    }

    override suspend fun attemptTransaction(oldIsotopeId: UUID?): Boolean {
        if (oldIsotopeId == null) return true
        val cmd = asyncCommands ?: throw IllegalStateException("Not connected to Redis")
        val key = "iso:$oldIsotopeId"
        val result = cmd.eval<Long>(scriptBurn, ScriptOutputType.INTEGER, arrayOf(key))
            .toCompletableFuture().await()
        return result == 1L
    }

    override suspend fun getStatus(isotopeId: UUID): String {
        val cmd = asyncCommands ?: throw IllegalStateException("Not connected to Redis")
        return cmd.get("iso:$isotopeId").toCompletableFuture().await() ?: "UNKNOWN"
    }

    override suspend fun getStatuses(isotopeIds: List<UUID>): Map<UUID, String> {
        if (isotopeIds.isEmpty()) return emptyMap()
        val cmd = asyncCommands ?: throw IllegalStateException("Not connected to Redis")
        val keys = isotopeIds.map { "iso:$it" }.toTypedArray()
        val results = cmd.mget(*keys).toCompletableFuture().await()

        val out = mutableMapOf<UUID, String>()
        results.forEachIndexed { i, kv ->
            if (i < isotopeIds.size) {
                out[isotopeIds[i]] = if (kv.hasValue()) kv.value else "UNKNOWN"
            }
        }
        return out
    }
}
