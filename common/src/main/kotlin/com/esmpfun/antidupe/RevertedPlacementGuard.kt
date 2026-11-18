package com.esmpfun.antidupe

import com.esmpfun.antidupe.api.model.Protection
import com.esmpfun.antidupe.integration.ApiEvents
import com.esmpfun.antidupe.ledger.AlertType
import com.esmpfun.antidupe.ledger.DupeAlert
import com.esmpfun.antidupe.ledger.Severity
import com.esmpfun.antidupe.platform.PlatformScheduler
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.util.BoundingBox
import java.util.logging.Logger

/**
 * PaperMC/Paper#13586: a placement that overwrites a container (the last eye of ender fills
 * the portal middle) drops the container's contents, and a copy of the container itself, before
 * the place event fires. When any plugin cancels that event the container comes back: on Paper
 * still full, on Spigot empty. Either way one tick later the world is put back as it was: the
 * container holds its items and nothing extra lies on the ground.
 */
class RevertedPlacementGuard(
    private val scheduler: PlatformScheduler,
    private val logger: Logger,
    private val alert: (DupeAlert) -> Unit
) : Listener {

    // MONITOR and not ignoreCancelled: only a cancelled or refused placement is reverted.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlace(event: BlockPlaceEvent) {
        if (!event.isCancelled && event.canBuild()) return
        val replaced = if (event is BlockMultiPlaceEvent) event.replacedBlockStates else listOf(event.blockReplacedState)
        // Spigot's saved copy of a replaced chest is a plain block state, not a container, so
        // every replaced spot is checked once the real block is back.
        val spots = replaced.map { it.location }
        val player = event.player
        // The container is restored after this event returns.
        scheduler.runForEntityLater(player, 1L, Runnable { for (spot in spots) restore(player, spot) })
    }

    private fun restore(player: Player, spot: Location) {
        val block = spot.block
        val restored = block.state as? Container ?: return
        val box = BoundingBox.of(block).expand(0.5)
        val fresh = block.world.getNearbyEntities(box) { it is Item && it.isValid && it.ticksLived <= 2 }
            .map { it as Item }.toMutableList()
        if (fresh.isEmpty()) return
        if (ApiEvents.stepAside(Protection.REVERTED_PLACEMENT, player, spot)) return
        val inventory = restored.inventory
        var removed = 0
        var returned = 0

        // The dropped container item is a copy of the block that came back. On Spigot a shulker
        // box carries the contents inside it while the restored one is empty, so they move over.
        fresh.firstOrNull { it.itemStack.type == block.type }?.let { copy ->
            val stack = copy.itemStack
            if (inventory.isEmpty) {
                val inner = ((stack.itemMeta as? BlockStateMeta)?.blockState as? Container)?.inventory?.contents
                inner?.forEachIndexed { slot, item ->
                    if (item != null && slot < inventory.size) {
                        inventory.setItem(slot, item)
                        returned += item.amount
                    }
                }
            }
            if (stack.amount <= 1) copy.remove() else copy.itemStack = stack.clone().apply { amount -= 1 }
            fresh.remove(copy)
            removed++
        }

        if (inventory.isEmpty) {
            // Spigot: the loose drops are the only copy of the contents, so they go back in.
            for (item in fresh) {
                val left = inventory.addItem(item.itemStack.clone())
                val kept = left.values.sumOf { it.amount }
                returned += item.itemStack.amount - kept
                if (kept == 0) item.remove() else item.itemStack = left.values.first()
            }
        } else {
            // Paper: the container is full again, so loose drops matching it are copies.
            val expected = inventory.contents.filterNotNull().filter { !it.type.isAir }.map { it.clone() }
            val stacks = fresh.map { it.itemStack }
            val take = copiesToRemove(expected, stacks, { it.amount }, { a, b -> a.isSimilar(b) })
            for ((i, item) in fresh.withIndex()) {
                val n = take[i]
                if (n <= 0) continue
                if (n >= stacks[i].amount) item.remove() else item.itemStack = stacks[i].clone().apply { amount -= n }
                removed += n
            }
        }
        if (removed > 0 || returned > 0) report(player, block.type, spot, removed, returned)
    }

    private fun report(player: Player, type: Material, spot: Location, removed: Int, returned: Int) {
        com.esmpfun.antidupe.metrics.DetectionCounters.recordPreventionBlock("reverted_placement")
        val where = "${spot.world?.name},${spot.blockX},${spot.blockY},${spot.blockZ}"
        logger.warning("[DuperPrevention] A blocked placement by ${player.name} would have copied a ${type.name}" +
            " at $where. $removed copied item(s) were removed, $returned item(s) went back inside, and the" +
            " ${type.name} keeps its contents.")
        alert(DupeAlert(
            type = AlertType.REVERTED_PLACEMENT,
            player = player.uniqueId,
            playerName = player.name,
            material = type,
            details = "Blocked placement at $where would have copied a ${type.name}; $removed copied item(s) removed",
            severity = Severity.MEDIUM,
            timestamp = System.currentTimeMillis(),
            messageKey = "alerts.reverted-placement",
            placeholders = mapOf("count" to "$removed", "material" to type.name, "where" to where)
        ))
    }

    companion object {
        /**
         * How much of each dropped stack is a copy of [expected]. Drops are split into random
         * piles, so amounts are matched across piles rather than stack for stack.
         */
        fun <T> copiesToRemove(
            expected: List<T>, dropped: List<T>, amountOf: (T) -> Int, same: (T, T) -> Boolean,
            left: IntArray = expected.map { amountOf(it) }.toIntArray()
        ): IntArray {
            val out = IntArray(dropped.size)
            for ((i, d) in dropped.withIndex()) {
                var need = amountOf(d)
                for ((j, e) in expected.withIndex()) {
                    if (need == 0) break
                    if (left[j] == 0 || !same(e, d)) continue
                    val n = minOf(need, left[j])
                    left[j] -= n
                    need -= n
                    out[i] += n
                }
            }
            return out
        }
    }
}
