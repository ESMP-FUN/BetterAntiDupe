package com.esmpfun.antidupe.ledger

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

/**
 * Append-only ledger with one hash chain per player: entries link to that player's previous
 * entry via [LedgerEntry.prevHash], so only same-player appends serialize.
 */
abstract class LedgerStorage protected constructor(protected val logger: Logger) {

    private val playerLocks = ConcurrentHashMap<UUID, Mutex>()

    /**
     * Must stay [ConcurrentHashMap.computeIfAbsent], not Kotlin's `getOrPut`: the stdlib
     * extension is a plain get-then-put, so simultaneous appends for one player can take
     * different locks and fork that player's chain, which verification reports as tampering.
     */
    private fun lockFor(player: UUID): Mutex = playerLocks.computeIfAbsent(player) { Mutex() }

    private val balanceCache = ConcurrentHashMap<Pair<UUID, Material>, AtomicInteger>()

    /**
     * True when another server process can write the backend. The balance cache is per-JVM with
     * no cross-process invalidation, so shared backends skip it and read the counter every time.
     */
    protected open val sharedBackend: Boolean = false

    suspend fun appendBuilt(
        player: UUID,
        action: LedgerAction,
        material: Material,
        quantity: Int,
        metadata: LedgerMetadata
    ): LedgerEntry = lockFor(player).withLock {
        val tip = readPlayerTip(player)
        val entry = LedgerEntry.create(player, action, material, quantity, metadata, tip?.lastHash)
        writeEntry(entry)
        if (!sharedBackend) balanceCache[player to material]?.addAndGet(quantity)
        entry
    }

    /** Most recent entry across all players. Display only: last write wins, unordered. */
    abstract suspend fun getTip(): ChainTip?

    suspend fun getBalance(player: UUID, material: Material): Int {
        if (sharedBackend) return readBalanceFromStorage(player, material)
        val key = player to material
        balanceCache[key]?.let { return it.get() }
        // Populate under the append lock: an append landing between the storage read and the
        // cache insert would otherwise be missing from the cached value.
        return lockFor(player).withLock {
            balanceCache[key]?.get() ?: run {
                val fromStorage = readBalanceFromStorage(player, material)
                balanceCache.putIfAbsent(key, AtomicInteger(fromStorage))
                balanceCache[key]?.get() ?: fromStorage
            }
        }
    }

    protected abstract suspend fun readPlayerTip(player: UUID): ChainTip?
    protected abstract suspend fun writeEntry(entry: LedgerEntry)
    protected abstract suspend fun readBalanceFromStorage(player: UUID, material: Material): Int

    /** Players with at least one ledger entry. */
    abstract suspend fun getTrackedPlayers(): Set<UUID>

    abstract suspend fun getEntry(id: UUID): LedgerEntry?
    abstract suspend fun getAllBalances(player: UUID): Map<Material, Int>
    abstract suspend fun getRecentAcquisitions(player: UUID, material: Material, windowMs: Long = 5 * 60 * 1000L): Int
    abstract suspend fun getPlayerEntries(player: UUID, limit: Long = 100, offset: Long = 0): List<LedgerEntry>
    abstract suspend fun pruneRecentWindows()

    /**
     * Atomically record that an item-entity UUID was picked up. Returns null the first time a
     * UUID is seen, or the earlier record if that UUID was already consumed, which is a dupe.
     */
    abstract suspend fun markEntityPickup(
        entityUuid: UUID,
        playerUuid: UUID,
        material: Material,
        amount: Int
    ): PreviousPickup?

    open suspend fun prunePickupHistory(olderThanMs: Long) { /* default: backend handles TTL */ }

    /**
     * Claims the right to write [player]'s join baseline for every server sharing this storage.
     * A single server's own in-flight guard is enough, so only a shared backend overrides this.
     */
    open suspend fun claimBaseline(player: UUID): Boolean = true

    private val worldStockFallback = ConcurrentHashMap<Pair<UUID, Material>, AtomicInteger>()

    /**
     * Adds [delta] to how many of [owner]'s tagged [material] are outside every player inventory,
     * in containers, frames or on the ground, and returns the new count.
     */
    open suspend fun adjustWorldStock(owner: UUID, material: Material, delta: Int): Int =
        worldStockFallback.computeIfAbsent(owner to material) { AtomicInteger() }.addAndGet(delta)

    open suspend fun setWorldStock(owner: UUID, material: Material, value: Int) {
        worldStockFallback.computeIfAbsent(owner to material) { AtomicInteger() }.set(value)
    }

    abstract fun close()

    /**
     * Verify one player's hash chain from its most recent reset point forward. Entries before
     * that reset are kept for history and balance but never re-verified, which is how the
     * legacy global-chain prevHashes are skipped.
     */
    open suspend fun verifyChainIntegrity(player: UUID): IntegrityResult {
        val full = getPlayerChainOrdered(player)
        val resetIdx = full.indexOfLast { it.isChainReset() }
        val entries = if (resetIdx >= 0) full.subList(resetIdx, full.size) else full

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

    /** Stops at the first failing chain. */
    open suspend fun verifyAllChains(): IntegrityResult {
        var total = 0
        for (player in getTrackedPlayers()) {
            val result = verifyChainIntegrity(player)
            if (!result.valid) return result
            total += result.entriesVerified
        }
        return IntegrityResult(valid = true, entriesVerified = total)
    }

    /**
     * A player's entries oldest first. Backends must order by a monotonic insertion key (SQLite
     * rowid, Redis and Memory list order), not timestamp, or same-millisecond bursts read as
     * chain breaks.
     */
    protected abstract suspend fun getPlayerChainOrdered(player: UUID): List<LedgerEntry>

    companion object {
        suspend fun create(plugin: JavaPlugin): LedgerStorage {
            val backend = (plugin.config.getString("storage.backend", "SQLITE") ?: "SQLITE").uppercase()
            val logger = plugin.logger
            return when (backend) {
                "REDIS" -> {
                    val host = plugin.config.getString("redis.host", "localhost") ?: "localhost"
                    val port = plugin.config.getInt("redis.port", 6379)
                    val pw = plugin.config.getString("redis.password", "")
                    // Older configs carry both keys, but only ledger.redis_database was ever
                    // read, so it must keep winning or those servers silently change database.
                    val db = if (plugin.config.contains("ledger.redis_database"))
                        plugin.config.getInt("ledger.redis_database", 1)
                    else plugin.config.getInt("redis.database", 1)
                    val timeout = plugin.config.getLong("redis.timeout", 10L)
                    RedisLedgerStorage.create(host, port, pw, db, timeout, logger)
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

/** A pickup of an item entity that was already picked up once, so almost always a dupe. */
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
