package com.server.antidupe.ledger

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

/**
 * Append-only ledger. Backends must implement [readTipInternal], [writeEntry], and the
 * read-only query methods. The chain-tip read+write is serialized by [appendMutex] so
 * concurrent producers can't break hash continuity.
 */
abstract class LedgerStorage protected constructor(protected val logger: Logger) {

    private val appendMutex = Mutex()

    /**
     * Read-through balance cache. Lookups consult the cache first; misses load from storage and
     * populate. Appends bump the cached entry atomically, so reconciliation passes — which
     * query getBalance once per material per player — avoid round-trips to the backend.
     */
    private val balanceCache = ConcurrentHashMap<Pair<UUID, Material>, AtomicInteger>()

    suspend fun appendBuilt(
        player: UUID,
        action: LedgerAction,
        material: Material,
        quantity: Int,
        metadata: LedgerMetadata
    ): LedgerEntry = appendMutex.withLock {
        val tip = readTipInternal()
        val entry = LedgerEntry.create(player, action, material, quantity, metadata, tip?.lastHash)
        writeEntry(entry)
        balanceCache[player to material]?.addAndGet(quantity)
        entry
    }

    suspend fun append(entry: LedgerEntry): LedgerEntry = appendMutex.withLock {
        writeEntry(entry)
        balanceCache[entry.player to entry.material]?.addAndGet(entry.quantity)
        entry
    }

    suspend fun getTip(): ChainTip? = appendMutex.withLock { readTipInternal() }

    suspend fun getBalance(player: UUID, material: Material): Int {
        val key = player to material
        balanceCache[key]?.let { return it.get() }
        val fromStorage = readBalanceFromStorage(player, material)
        balanceCache.putIfAbsent(key, AtomicInteger(fromStorage))
        return balanceCache[key]?.get() ?: fromStorage
    }

    /** Drop the cached balance for one (player, material) — used after manual repairs. */
    fun invalidateBalance(player: UUID, material: Material) {
        balanceCache.remove(player to material)
    }

    protected abstract suspend fun readTipInternal(): ChainTip?
    protected abstract suspend fun writeEntry(entry: LedgerEntry)
    protected abstract suspend fun readBalanceFromStorage(player: UUID, material: Material): Int

    abstract suspend fun getEntry(id: UUID): LedgerEntry?
    abstract suspend fun getAllBalances(player: UUID): Map<Material, Int>
    abstract suspend fun getRecentAcquisitions(player: UUID, material: Material, windowMs: Long = 5 * 60 * 1000L): Int
    abstract suspend fun getPlayerEntries(player: UUID, limit: Long = 100, offset: Long = 0): List<LedgerEntry>
    abstract suspend fun pruneRecentWindows()

    /**
     * Atomically record that an item-entity UUID was picked up by a player.
     * @return null if this is the first time the UUID has been seen (legitimate pickup),
     *         or the previous pickup record if the UUID has already been consumed (dupe).
     */
    abstract suspend fun markEntityPickup(
        entityUuid: UUID,
        playerUuid: UUID,
        material: Material,
        amount: Int
    ): PreviousPickup?

    /** Prune entity-pickup records older than [olderThanMs]. */
    open suspend fun prunePickupHistory(olderThanMs: Long) { /* default: backend handles TTL */ }

    abstract fun close()

    open suspend fun getPlayerMaterialEntries(player: UUID, material: Material, limit: Int = 50): List<LedgerEntry> =
        getPlayerEntries(player, limit = limit.toLong()).filter { it.material == material }

    open suspend fun recomputeBalance(player: UUID, material: Material): Int {
        val entries = getPlayerMaterialEntries(player, material, limit = 10000)
        return entries.sumOf { it.quantity }
    }

    open suspend fun verifyChainIntegrity(fromEntry: UUID? = null): IntegrityResult {
        val entries = if (fromEntry != null) getEntriesAfter(fromEntry) else getAllEntriesChronological()
        var prevHash: String? = null
        var lastValid: UUID? = null
        for (entry in entries) {
            if (!entry.verifyIntegrity()) {
                return IntegrityResult(false, "Entry ${entry.id} hash mismatch", entry.id, lastValid)
            }
            if (prevHash != null && entry.prevHash != prevHash) {
                return IntegrityResult(false, "Chain break at ${entry.id}: expected $prevHash, got ${entry.prevHash}",
                    entry.id, lastValid)
            }
            prevHash = entry.hash
            lastValid = entry.id
        }
        return IntegrityResult(valid = true, entriesVerified = entries.size)
    }

    protected abstract suspend fun getAllEntriesChronological(): List<LedgerEntry>

    protected open suspend fun getEntriesAfter(afterEntry: UUID): List<LedgerEntry> {
        val start = getEntry(afterEntry) ?: return emptyList()
        return getAllEntriesChronological().filter { it.timestamp > start.timestamp }
    }

    companion object {
        suspend fun create(plugin: JavaPlugin): LedgerStorage {
            val backend = (plugin.config.getString("storage.backend", "SQLITE") ?: "SQLITE").uppercase()
            val logger = plugin.logger
            return when (backend) {
                "REDIS" -> {
                    val host = plugin.config.getString("redis.host", "localhost") ?: "localhost"
                    val port = plugin.config.getInt("redis.port", 6379)
                    val pw = plugin.config.getString("redis.password", "")
                    val db = plugin.config.getInt("ledger.redis_database", 1)
                    RedisLedgerStorage.create(host, port, pw, db, logger)
                }
                "MEMORY" -> MemoryLedgerStorage(logger)
                "SQLITE" -> SqliteLedgerStorage.create(plugin, logger)
                else -> {
                    logger.warning("Unknown storage.backend '$backend', falling back to SQLITE")
                    SqliteLedgerStorage.create(plugin, logger)
                }
            }
        }
    }
}

data class ChainTip(val lastEntryId: UUID, val lastHash: String, val timestamp: Long)

/**
 * Record of a previously-processed item-entity pickup. Returned by
 * [LedgerStorage.markEntityPickup] when a UUID has been picked up before — which is
 * almost always a chunk-load / drop-race / cross-server-race dupe.
 */
data class PreviousPickup(
    val playerUuid: UUID,
    val material: Material,
    val amount: Int,
    val pickedUpAt: Long
)

data class IntegrityResult(
    val valid: Boolean,
    val error: String? = null,
    val brokenAt: UUID? = null,
    val lastValidEntry: UUID? = null,
    val entriesVerified: Int = 0
)
