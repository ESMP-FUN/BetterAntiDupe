package com.esmpfun.antidupe.ledger

import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Counting rules for crafting grids, stations and lecterns, kept free of server types so they can be tested. */
internal object StationAccounting {

    /** A craft uses one item from each occupied slot, so a tracked material in three slots costs three per craft. */
    fun ingredientDebits(matrix: List<Material?>, isTracked: (Material) -> Boolean, crafts: Int): Map<Material, Int> {
        if (crafts <= 0) return emptyMap()
        val perMaterial = HashMap<Material, Int>()
        for (type in matrix) {
            if (type == null || type == Material.AIR || !isTracked(type)) continue
            perMaterial.merge(type, crafts, Int::plus)
        }
        return perMaterial
    }

    fun craftCount(shiftClick: Boolean, amount: Int, perCraft: Int): Int =
        if (shiftClick) (amount / perCraft.coerceAtLeast(1)).coerceAtLeast(1) else 1

    /** Whether a right click really put a book on the lectern, judged from before and after. */
    fun lecternBookPlaced(hadBook: Boolean, hasBook: Boolean, heldBefore: Int, heldAfter: Int): Boolean =
        !hadBook && hasBook && heldAfter < heldBefore
}

/**
 * A furnace take is written as a station output, but the same click also moves the player's side
 * of the open furnace, which the container diff would count a second time as a take.
 */
internal class StationCredits {
    private val credits = ConcurrentHashMap<UUID, ConcurrentHashMap<Material, Int>>()

    fun add(player: UUID, material: Material, amount: Int) {
        if (amount <= 0) return
        credits.computeIfAbsent(player) { ConcurrentHashMap() }.merge(material, amount, Int::plus)
    }

    /** Hands back and forgets everything credited to [player] since the last call. */
    fun consume(player: UUID): Map<Material, Int> = credits.remove(player) ?: emptyMap()
}
