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
 * Blocks the classic block-duplication contraptions (rail, carpet, TNT and gravity dupers) at
 * the mechanic level.
 *
 * These duplicate blocks in the world, not inventory items: a piston dislodges a fragile
 * attached block in the same tick it moves its support, and the update order leaves both the
 * block and a dropped item. No inventory event fires, so the ledger only ever sees the later
 * pickup surplus; cancelling the piston movement removes the exploit at its root.
 *
 * The cost, documented in config.yml: a piston can no longer move a block with a rail or carpet
 * on top (vanilla pops it off, which is the interaction being abused) or a TNT block while that
 * toggle is on, and falling blocks can no longer travel through portals.
 */
class DuperPreventionListener(
    private val preventRail: Boolean,
    private val preventCarpet: Boolean,
    private val preventGravity: Boolean,
    private val preventTnt: Boolean,
    private val preventContainerDesync: Boolean,
    private val logger: Logger
) : Listener {

    // Contraptions clock piston dupers several times a second, so only one console line per 10s
    // window gets through. The counter is still bumped every time; only the log is throttled.
    private val lastLogAt = AtomicLong(0)
    private fun logBlocked(reason: String, block: Block) {
        com.esmpfun.antidupe.metrics.DetectionCounters.recordPreventionBlock(counterKey(reason))
        val now = System.currentTimeMillis()
        val prev = lastLogAt.get()
        if (now - prev < 10_000 || !lastLogAt.compareAndSet(prev, now)) return
        logger.info("[DuperPrevention] Cancelled piston movement ($reason) at " +
            "${block.world.name},${block.x},${block.y},${block.z}")
    }

    /** The reason strings are human-facing; the counter needs a short tag that stays stable. */
    private fun counterKey(reason: String) = when {
        reason.startsWith("rail") -> "rail"
        reason.startsWith("carpet") -> "carpet"
        reason.startsWith("TNT") -> "tnt"
        else -> "other"
    }

    private fun isCarpet(m: Material) = m.name.endsWith("_CARPET")

    private fun isSlimeLike(m: Material) = m == Material.SLIME_BLOCK || m == Material.HONEY_BLOCK

    private fun railCarpetVector(type: Material): String? = when {
        preventRail && Tag.RAILS.isTagged(type) -> "rail duper"
        preventCarpet && isCarpet(type) -> "carpet duper"
        else -> null
    }

    // Beyond the "on top" case checked for every block: in the observer-driven slime-drag duper
    // the carpet sits beside or beneath the slime column, never on a pushed block.
    private val slimeDragFaces = arrayOf(
        BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    )

    // TNT dupers are powered by a detector rail and minecart riding on their slime, so with TNT
    // dupers allowed a detector rail no longer counts as a rail duper.
    private fun isTntDuperRail(b: Block) = !preventTnt && b.type == Material.DETECTOR_RAIL

    /** A short reason string when this piston movement matches a duper signature, else null. */
    private fun dupeVector(moved: List<Block>): Pair<String, Block>? {
        for (block in moved) {
            val type = block.type
            if (preventTnt && type == Material.TNT) return "TNT duper" to block
            val above = block.getRelative(BlockFace.UP)
            if (!isTntDuperRail(above)) railCarpetVector(above.type)?.let { return it to above }
            if ((preventRail || preventCarpet) && isSlimeLike(type)) {
                for (face in slimeDragFaces) {
                    val neighbor = block.getRelative(face)
                    if (isTntDuperRail(neighbor)) continue
                    railCarpetVector(neighbor.type)?.let { return it to neighbor }
                }
            }
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
        // Carpet-on-piston-arm variant, also used by TNT dupers: the rail or carpet sits on the
        // head block, which never appears in event.blocks, and a plain retract moves nothing at
        // all, yet pulling the arm back is exactly what dislodges and dupes it.
        val aboveHead = event.block.getRelative(event.direction).getRelative(BlockFace.UP)
        if (!isTntDuperRail(aboveHead)) railCarpetVector(aboveHead.type)?.let { reason ->
            event.isCancelled = true
            logBlocked(reason, aboveHead)
            return
        }
        val vector = dupeVector(event.blocks) ?: return
        event.isCancelled = true
        logBlocked(vector.first, vector.second)
    }

    /**
     * "Removed container GUI reference" family: a shulker box or chest GUI kept open while the
     * block is destroyed still hands out items, and the dropped box keeps its contents too. The
     * phantom takes are recorded as legitimate CONTAINER_TAKE ledger credits, so reconciliation
     * cannot see this one. Closing the viewers removes the phantom reference; a legitimately
     * open GUI just closes and loses nothing.
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
        // Copy: closeInventory mutates the live viewer list while we iterate.
        val viewers = container.inventory.viewers.toList()
        if (viewers.isEmpty()) return
        for (viewer in viewers) viewer.closeInventory()
        com.esmpfun.antidupe.metrics.DetectionCounters.recordPreventionBlock("container_desync")
        logger.info("[DuperPrevention] Closed ${viewers.size} viewer(s) of ${block.type} $how at " +
            "${block.world.name},${block.x},${block.y},${block.z}")
    }

    /**
     * A donkey, llama, chest boat or minecart inventory kept open while the entity's chunk
     * unloads leaves the same kind of phantom GUI.
     */
    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        if (!preventContainerDesync) return
        for (entity in event.chunk.entities) {
            val holder = entity as? InventoryHolder ?: continue
            val viewers = holder.inventory.viewers
            if (viewers.isEmpty()) continue
            for (viewer in viewers.toList()) viewer.closeInventory()
            com.esmpfun.antidupe.metrics.DetectionCounters.recordPreventionBlock("container_desync")
            logger.info("[DuperPrevention] Closed viewer(s) of ${entity.type} inventory on chunk unload")
        }
    }

    /**
     * The end-portal sand duper sends a falling block through a portal, which duplicates the
     * entity across the dimension change while the block also lands. Nothing legitimate needs
     * that, so the teleport is cancelled outright. Pistons pushing sand are deliberately left
     * alone (flying machines): those dupers ride on the rail/carpet detach trick instead.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityPortal(event: EntityPortalEvent) {
        if (!preventGravity) return
        if (event.entity !is FallingBlock) return
        event.isCancelled = true
        com.esmpfun.antidupe.metrics.DetectionCounters.recordPreventionBlock("gravity")
        val loc = event.entity.location
        logger.info("[DuperPrevention] Cancelled falling-block portal travel (gravity duper) at " +
            "${loc.world?.name},${loc.blockX},${loc.blockY},${loc.blockZ}")
    }
}
