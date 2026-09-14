package com.esmpfun.antidupe.ledger

import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Seeds a never-seen player's ledger from what they carry, so pre-existing or externally granted
 * items do not read as a surplus. Only one run per player at a time, and the marker is written
 * before the inventory snapshot, so a fast rejoin cannot credit the same inventory twice.
 */
class JoinBaseline(private val storage: LedgerStorage) {

    private val inFlight = ConcurrentHashMap.newKeySet<UUID>()

    /** Returns true when this call wrote the baseline. */
    suspend fun run(player: UUID, snapshot: suspend () -> Map<Material, Int>): Boolean {
        if (!inFlight.add(player)) return false
        try {
            if (storage.getPlayerEntries(player, limit = 1).isNotEmpty()) return false
            // The marker also stops an empty-inventory new player being re-baselined every join.
            storage.appendBuilt(
                player = player,
                action = LedgerAction.RECONCILE,
                material = Material.AIR,
                quantity = 0,
                metadata = LedgerMetadata(notes = "BASELINE_MARKER")
            )
            for ((material, actual) in snapshot()) {
                if (actual <= 0) continue
                storage.appendBuilt(
                    player = player,
                    action = LedgerAction.ADMIN_GIVE,
                    material = material,
                    quantity = actual,
                    metadata = LedgerMetadata(notes = "BASELINE_ON_JOIN")
                )
            }
            return true
        } finally {
            inFlight.remove(player)
        }
    }
}
