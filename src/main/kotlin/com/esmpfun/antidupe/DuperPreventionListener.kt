package com.esmpfun.antidupe

import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.entity.FallingBlock
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.InventoryHolder
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * Blocks the classic block-duplication contraptions (rail dupers, carpet dupers, TNT dupers,
 * gravity-block dupers) at the mechanic level.
 *
 * These exploits duplicate BLOCKS in the world, not inventory items: a piston dislodges a
 * fragile attached block (rail / carpet) in the same tick it moves its support, and the
 * update-order quirk leaves both the block and a dropped item — no inventory event ever
 * fires, so the ledger can't see the duplication moment (only the eventual pickup surplus).
 * Cancelling the piston movement removes the exploit at its root.
 *
 * Legit cost is small and documented in config.yml: pistons can no longer push/pull a block
 * that has a rail or carpet sitting on top of it (vanilla would pop the rail/carpet off —
 * exactly the interaction dupers abuse), can't move TNT blocks while the TNT toggle is on,
 * and falling blocks (sand, gravel, concrete powder, dragon egg...) can't travel through
 * portals (the end-portal sand duper).
 */
class DuperPreventionListener(
    private val preventRail: Boolean,
    private val preventCarpet: Boolean,
    private val preventGravity: Boolean,
    private val preventTnt: Boolean,
    private val preventContainerDesync: Boolean,
    private val logger: Logger
) : Listener {

    // Contraptions clock piston dupers several times a second; log at most one line per
    // location-agnostic 10s window so a running duper can't flood the console.
    private val lastLogAt = AtomicLong(0)
    private fun logBlocked(reason: String, block: Block) {
        val now = System.currentTimeMillis()
        val prev = lastLogAt.get()
        if (now - prev < 10_000 || !lastLogAt.compareAndSet(prev, now)) return
        logger.info("[DuperPrevention] Cancelled piston movement ($reason) at " +
            "${block.world.name},${block.x},${block.y},${block.z}")
    }

    private fun isCarpet(m: Material) = m.name.endsWith("_CARPET")

    /** Non-null reason when [type] is a fragile attached block covered by an enabled toggle. */
    private fun railCarpetVector(type: Material): String? = when {
        preventRail && Tag.RAILS.isTagged(type) -> "rail duper"
        preventCarpet && isCarpet(type) -> "carpet duper"
        else -> null
    }

    /**
     * Returns a short reason string when this piston movement matches a duper signature,
     * else null. Checks each moved block itself (TNT) and the block resting on top of it
     * (rails and carpets attach on top; dislodging them mid-move is the dupe).
     */
    private fun dupeVector(moved: List<Block>): Pair<String, Block>? {
        for (block in moved) {
            val type = block.type
            if (preventTnt && type == Material.TNT) return "TNT duper" to block
            val above = block.getRelative(BlockFace.UP)
            railCarpetVector(above.type)?.let { return it to above }
        }
        return null
    }

    // LOWEST so the cancellation is visible to every other plugin's handler.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        val vector = dupeVector(event.blocks) ?: return
        event.isCancelled = true
        logBlocked(vector.first, vector.second)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        // Carpet-on-piston-arm variant (used by TNT dupers too): the carpet/rail sits on the
        // HEAD block, which never appears in event.blocks — a plain (non-sticky) retract moves
        // nothing at all, yet removing the arm is exactly what dislodges and dupes it. Check
        // the head column explicitly.
        val aboveHead = event.block.getRelative(event.direction).getRelative(BlockFace.UP)
        railCarpetVector(aboveHead.type)?.let { reason ->
            event.isCancelled = true
            logBlocked(reason, aboveHead)
            return
        }
        val vector = dupeVector(event.blocks) ?: return
        event.isCancelled = true
        logBlocked(vector.first, vector.second)
    }

    /**
     * Gravity-block dupers (the end-portal sand duper family) work by sending a FallingBlock
     * entity through a portal: the entity is duplicated across the dimension change while the
     * block also lands. No legitimate farm needs falling blocks to travel through portals, so
     * cancelling the teleport kills the whole family with zero gameplay cost. Piston-based
     * gravity dupers all ride on the rail/carpet detach trick and are covered above — we
     * deliberately do NOT cancel pistons pushing sand (flying machines, legit farms).
     */
    // ========== CONTAINER-DESYNC (phantom GUI) DUPES ==========

    /**
     * "Removed container GUI reference" family: keep a shulker box / chest GUI open while the
     * block is destroyed, then take items out of the phantom GUI — the dropped box keeps its
     * contents too, so everything inside is duplicated. Especially dangerous for this plugin:
     * the phantom takes are recorded as legitimate CONTAINER_TAKE ledger credits, making the
     * dupe invisible to reconciliation. Force-closing viewers when the block goes away removes
     * the phantom reference entirely; a legitimately-open GUI just closes, nothing is lost.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onContainerBreak(event: BlockBreakEvent) {
        if (!preventContainerDesync) return
        closeViewersOf(event.block, "broken")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (!preventContainerDesync) return
        for (block in event.blockList()) closeViewersOf(block, "exploded")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (!preventContainerDesync) return
        for (block in event.blockList()) closeViewersOf(block, "exploded")
    }

    private fun closeViewersOf(block: Block, how: String) {
        val container = block.state as? Container ?: return
        // Copy — closeInventory mutates the live viewer list while we iterate.
        val viewers = container.inventory.viewers.toList()
        if (viewers.isEmpty()) return
        for (viewer in viewers) viewer.closeInventory()
        logger.info("[DuperPrevention] Closed ${viewers.size} viewer(s) of ${block.type} $how at " +
            "${block.world.name},${block.x},${block.y},${block.z}")
    }

    /**
     * "Unloaded ridable entity GUI reference": a donkey / llama / chest-boat / minecart
     * inventory kept open while the entity's chunk unloads leaves the same kind of phantom
     * GUI. Close viewers of any inventory-holding entity in an unloading chunk.
     */
    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        if (!preventContainerDesync) return
        for (entity in event.chunk.entities) {
            val holder = entity as? InventoryHolder ?: continue
            val viewers = holder.inventory.viewers
            if (viewers.isEmpty()) continue
            for (viewer in viewers.toList()) viewer.closeInventory()
            logger.info("[DuperPrevention] Closed viewer(s) of ${entity.type} inventory on chunk unload")
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityPortal(event: EntityPortalEvent) {
        if (!preventGravity) return
        if (event.entity !is FallingBlock) return
        event.isCancelled = true
        val loc = event.entity.location
        logger.info("[DuperPrevention] Cancelled falling-block portal travel (gravity duper) at " +
            "${loc.world?.name},${loc.blockX},${loc.blockY},${loc.blockZ}")
    }
}
