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
 * Append-only ledger with **per-player hash chains**. Each player's entries link to that
 * player's previous entry via [LedgerEntry.prevHash], so appends for different players never
 * contend — only same-player appends serialize, through a per-player [Mutex]. This removes the
 * single global append lock that previously bottlenecked every item gain server-wide.
 *
 * Backends implement [readPlayerTip], [writeEntry], and the read-only query methods.
 */
abstract class LedgerStorage protected constructor(protected val logger: Logger) {

    /**
     * One lock per player. Keyed by UUID so two different players' appends run concurrently.
     * Bounded by the number of distinct players ever seen (small, cheap Mutex objects).
     */
    private val playerLocks = ConcurrentHashMap<UUID, Mutex>()
    private fun lockFor(player: UUID): Mutex = playerLocks.getOrPut(player) { Mutex() }

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
    ): LedgerEntry = lockFor(player).withLock {
        val tip = readPlayerTip(player)
        val entry = LedgerEntry.create(player, action, material, quantity, metadata, tip?.lastHash)
        writeEntry(entry)
        balanceCache[player to material]?.addAndGet(quantity)
        entry
    }

    suspend fun append(entry: LedgerEntry): LedgerEntry = lockFor(entry.player).withLock {
        writeEntry(entry)
        balanceCache[entry.player to entry.material]?.addAndGet(entry.quantity)
        entry
    }

    /** The most recent entry across all players — display-only (last-write-wins, unordered). */
    abstract suspend fun getTip(): ChainTip?

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

    /** Per-player chain tip — the anchor each new entry's prevHash links to. */
    protected abstract suspend fun readPlayerTip(player: UUID): ChainTip?
    protected abstract suspend fun writeEntry(entry: LedgerEntry)
    protected abstract suspend fun readBalanceFromStorage(player: UUID, material: Material): Int

    /** Distinct players that have at least one ledger entry. Used by [verifyAllChains]. */
    abstract suspend fun getTrackedPlayers(): Set<UUID>

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

    /**
     * Verify a single player's hash chain from its most recent reset point forward. Every entry's
     * own hash must be self-consistent, and each entry's prevHash must link to the previous entry
     * in that player's chain.
     *
     * Entries before the most recent CHAIN_RESET (marker `notes = CHAIN_RESET:<reason>`) are kept
     * for history/balance but not re-verified — used by the 3.3.0 migration to skip over legacy
     * global-chain prevHashes inherited from 3.2.0 and earlier.
     */
    open suspend fun verifyChainIntegrity(player: UUID): IntegrityResult {
        val full = getPlayerChainOrdered(player)
        // Take only entries from the last reset (inclusive) forward, if any reset exists.
        val resetIdx = full.indexOfLast { it.metadata.notes?.startsWith("CHAIN_RESET:") == true }
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

    /** Verify every player's chain. Returns the first failure found, or aggregate success. */
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
     * A player's entries in chain (insertion) order, oldest first. Backends should order by a
     * monotonic insertion key (SQLite rowid, Redis/Memory list order) rather than timestamp, so
     * same-millisecond bursts don't read as chain breaks.
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
