package com.server.antidupe.ledger

import org.bukkit.Material
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * In-memory ledger. Data is lost on restart — use for testing only.
 */
class MemoryLedgerStorage(logger: Logger) : LedgerStorage(logger) {

    private val entries = ConcurrentHashMap<UUID, LedgerEntry>()
    private val byPlayer = ConcurrentHashMap<UUID, MutableList<LedgerEntry>>()
    private val balances = ConcurrentHashMap<Pair<UUID, Material>, Int>()
    private val recent = ConcurrentHashMap<Pair<UUID, Material>, MutableList<LedgerEntry>>()
    private val pickupHistory = ConcurrentHashMap<UUID, PreviousPickup>()
    private val playerTips = ConcurrentHashMap<UUID, ChainTip>()
    private val globalTip = AtomicReference<ChainTip?>(null)

    init { logger.info("[Ledger] Using MEMORY backend (non-persistent)") }

    override fun close() { /* nothing to release */ }

    override suspend fun readPlayerTip(player: UUID): ChainTip? = playerTips[player]

    override suspend fun getTip(): ChainTip? = globalTip.get()

    override suspend fun getTrackedPlayers(): Set<UUID> = byPlayer.keys.toSet()

    override suspend fun writeEntry(entry: LedgerEntry) {
        entries[entry.id] = entry
        byPlayer.getOrPut(entry.player) { Collections.synchronizedList(mutableListOf()) }.add(entry)
        balances.merge(entry.player to entry.material, entry.quantity) { a, b -> a + b }
        if (entry.quantity > 0) {
            val list = recent.getOrPut(entry.player to entry.material) { Collections.synchronizedList(mutableListOf()) }
            list.add(entry)
        }
        playerTips[entry.player] = ChainTip(entry.id, entry.hash, entry.timestamp)
        globalTip.set(ChainTip(entry.id, entry.hash, entry.timestamp))
    }

    override suspend fun getEntry(id: UUID): LedgerEntry? = entries[id]

    override suspend fun readBalanceFromStorage(player: UUID, material: Material): Int = balances[player to material] ?: 0

    override suspend fun getAllBalances(player: UUID): Map<Material, Int> =
        balances.entries.filter { it.key.first == player && it.value != 0 }
            .associate { it.key.second to it.value }

    override suspend fun getRecentAcquisitions(player: UUID, material: Material, windowMs: Long): Int {
        val list = recent[player to material] ?: return 0
        val cutoff = System.currentTimeMillis() - windowMs
        return synchronized(list) { list.filter { it.timestamp >= cutoff }.sumOf { it.quantity } }
    }

    override suspend fun getPlayerEntries(player: UUID, limit: Long, offset: Long): List<LedgerEntry> {
        val list = byPlayer[player] ?: return emptyList()
        return synchronized(list) {
            list.sortedByDescending { it.timestamp }
                .drop(offset.toInt())
                .take(limit.toInt())
        }
    }

    override suspend fun pruneRecentWindows() {
        val cutoff = System.currentTimeMillis() - 5 * 60 * 1000L
        recent.values.forEach { list -> synchronized(list) { list.removeIf { it.timestamp < cutoff } } }
    }

    override suspend fun markEntityPickup(
        entityUuid: UUID, playerUuid: UUID, material: Material, amount: Int
    ): PreviousPickup? {
        val newEntry = PreviousPickup(playerUuid, material, amount, System.currentTimeMillis())
        return pickupHistory.putIfAbsent(entityUuid, newEntry)
    }

    override suspend fun prunePickupHistory(olderThanMs: Long) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        pickupHistory.entries.removeIf { it.value.pickedUpAt < cutoff }
    }

    override suspend fun getPlayerChainOrdered(player: UUID): List<LedgerEntry> {
        val list = byPlayer[player] ?: return emptyList()
        return synchronized(list) { list.toList() }  // already in insertion order
    }
}
