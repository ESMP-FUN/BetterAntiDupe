package com.esmpfun.antidupe.ledger

import org.bukkit.Material
import java.util.UUID
import java.util.logging.Logger

/**
 * Per owner and material, how many tagged items are outside every player inventory: in
 * containers, frames, pots or on the ground. Items only leave inventories and come back, so the
 * count can never honestly go below zero. When it does, more of that owner's items came out of
 * storage than ever went in, which is a dupe made outside anyone's inventory, where the
 * per-player ledger cannot see it.
 */
class WorldStock(
    private val storage: LedgerStorage,
    private val threshold: (Material) -> Int,
    private val logger: Logger,
    private val onDeficit: (actor: UUID, owner: UUID, material: Material, deficit: Int, where: String?) -> Unit
) {
    /** False until existing history has been counted once; before that a deficit is history we could not see. */
    @Volatile var ready = false

    suspend fun out(tally: Map<Pair<UUID, Material>, Int>) {
        for ((key, amount) in tally) {
            if (amount > 0) storage.adjustWorldStock(key.first, key.second, amount)
        }
    }

    /** [actor] took or picked up these, keyed by whose tag they carried. */
    suspend fun back(actor: UUID, tally: Map<Pair<UUID, Material>, Int>, where: String?) {
        for ((key, amount) in tally) {
            if (amount <= 0) continue
            val (owner, material) = key
            val left = storage.adjustWorldStock(owner, material, -amount)
            if (left >= 0) continue
            if (!ready) {
                storage.setWorldStock(owner, material, 0)
                continue
            }
            // A small gap stays negative and adds up until it is worth an alert.
            val deficit = -left
            if (deficit < threshold(material).coerceAtLeast(1)) continue
            storage.setWorldStock(owner, material, 0)
            onDeficit(actor, owner, material, deficit, where)
        }
    }

    /**
     * Counts what the existing history already put away, once per storage. Errs high: without tag
     * data, a put is assumed to be the putter's own items, and pickups of freshly mined or looted
     * items are left out, so an old dupe can be missed but no honest take reads as one.
     */
    suspend fun seedIfNeeded(players: Set<UUID>) {
        if (storage.adjustWorldStock(SEED_MARKER, Material.AIR, 1) == 1) {
            val totals = HashMap<Pair<UUID, Material>, Int>()
            for (player in players) {
                var offset = 0L
                while (true) {
                    val page = storage.getPlayerEntries(player, limit = PAGE, offset = offset)
                    if (page.isEmpty()) break
                    page.forEach { accumulate(it, totals) }
                    offset += page.size
                }
            }
            var seeded = 0
            for ((key, amount) in totals) {
                if (amount <= 0) continue
                storage.adjustWorldStock(key.first, key.second, amount)
                seeded++
            }
            logger.info("[Ledger] Counted items already stored away from existing history ($seeded owner and item pairs)")
        }
        ready = true
    }

    companion object {
        /** Claims the one-time history count for every server sharing the storage. */
        val SEED_MARKER: UUID = UUID(0L, 0L)
        private const val PAGE = 500L
        private val FRESH_SOURCES = listOf("MINE:", "MOB_DEATH:", "LOOT_TABLE")

        internal fun accumulate(entry: LedgerEntry, totals: MutableMap<Pair<UUID, Material>, Int>) {
            val notes = entry.metadata.notes ?: ""
            when (entry.action) {
                LedgerAction.CONTAINER_PUT, LedgerAction.ENTITY_PUT, LedgerAction.FRAME_PUT, LedgerAction.DROP ->
                    if (entry.quantity < 0) totals.merge(entry.player to entry.material, -entry.quantity, Int::plus)
                LedgerAction.PLACE ->
                    if (entry.quantity < 0 && "CONTENTS_OF" in notes) {
                        totals.merge(entry.player to entry.material, -entry.quantity, Int::plus)
                    }
                LedgerAction.CONTAINER_TAKE, LedgerAction.ENTITY_TAKE -> {
                    val owner = entry.metadata.relatedPlayer ?: entry.player
                    if (entry.quantity > 0) totals.merge(owner to entry.material, -entry.quantity, Int::plus)
                }
                LedgerAction.PICKUP -> {
                    if (FRESH_SOURCES.any { it in notes }) return
                    val owner = entry.metadata.relatedPlayer ?: entry.player
                    if (entry.quantity > 0) totals.merge(owner to entry.material, -entry.quantity, Int::plus)
                }
                else -> {}
            }
        }
    }
}
