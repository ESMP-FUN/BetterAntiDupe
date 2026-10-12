package com.esmpfun.antidupe

import com.esmpfun.antidupe.ledger.AlertType
import com.esmpfun.antidupe.ledger.DupeAlert
import com.esmpfun.antidupe.ledger.Severity
import com.esmpfun.antidupe.platform.PlatformScheduler
import org.bukkit.World
import org.bukkit.block.BlockState
import org.bukkit.block.Container
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import java.util.logging.Logger

/**
 * PaperMC/Paper#13586: a placement that overwrites a container (the last eye of ender fills
 * the portal middle) drops the container's contents before the place event fires. When any
 * plugin cancels that event, Paper restores the container with its contents, and a copy of the
 * container block drops as well. Those drops are deleted here to leave the world as if nothing
 * happened.
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
        for (state in replaced) {
            val container = state as? Container ?: continue
            val contents = container.snapshotInventory.contents.filterNotNull().filter { !it.type.isAir }
            if (contents.isEmpty()) continue
            // The container block itself also comes back as an item, after this event returns.
            val expected = (contents + ItemStack(state.type)).map { it.clone() }.toMutableList()
            val box = BoundingBox.of(state.block).expand(0.5)
            var removed = sweep(state.world, box, expected, maxAge = 1)
            scheduler.runForEntityLater(event.player, 1L, Runnable {
                removed += sweep(state.world, box, expected, maxAge = 2)
                if (removed > 0) report(event, state, removed)
            })
        }
    }

    /** Deletes fresh drops matching [expected] and takes what it deleted off [expected]. */
    private fun sweep(world: World, box: BoundingBox, expected: MutableList<ItemStack>, maxAge: Int): Int {
        if (expected.isEmpty()) return 0
        val fresh = world.getNearbyEntities(box) { it is Item && it.isValid && it.ticksLived <= maxAge }
            .map { it as Item }
        val stacks = fresh.map { it.itemStack }
        val left = expected.map { it.amount }.toIntArray()
        val take = copiesToRemove(expected, stacks, { it.amount }, { a, b -> a.isSimilar(b) }, left)
        var removed = 0
        for ((i, item) in fresh.withIndex()) {
            val n = take[i]
            if (n <= 0) continue
            val stack = stacks[i]
            if (n >= stack.amount) item.remove() else item.itemStack = stack.clone().apply { amount -= n }
            removed += n
        }
        for (j in expected.indices.reversed()) {
            if (left[j] <= 0) expected.removeAt(j) else expected[j].amount = left[j]
        }
        return removed
    }

    private fun report(event: BlockPlaceEvent, state: BlockState, removed: Int) {
        com.esmpfun.antidupe.metrics.DetectionCounters.recordPreventionBlock("reverted_placement")
        val b = state.block
        val where = "${b.world.name},${b.x},${b.y},${b.z}"
        logger.warning("[DuperPrevention] A blocked placement by ${event.player.name} would have copied a " +
            "${state.type.name} and its contents at $where. The $removed dropped item(s) were removed and the " +
            "${state.type.name} keeps its contents.")
        alert(DupeAlert(
            type = AlertType.REVERTED_PLACEMENT,
            player = event.player.uniqueId,
            playerName = event.player.name,
            material = state.type,
            details = "Blocked placement at $where would have copied $removed item(s) of a ${state.type.name} and its contents; the copies were removed",
            severity = Severity.MEDIUM,
            timestamp = System.currentTimeMillis(),
            messageKey = "alerts.reverted-placement",
            placeholders = mapOf("count" to "$removed", "material" to state.type.name, "where" to where)
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
