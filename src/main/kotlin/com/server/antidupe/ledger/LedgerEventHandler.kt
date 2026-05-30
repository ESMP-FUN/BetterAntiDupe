package com.server.antidupe.ledger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.Boat
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.InventoryHolder
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.logging.Logger

class LedgerEventHandler(
    private val plugin: Plugin,
    private val ledgerStorage: LedgerStorage,
    private val ownershipManager: OwnershipManager,
    private val reconciliationEngine: ReconciliationEngine,
    private val witnessManager: WitnessManager,
    private val trackedMaterials: Set<Material>,
    private val logger: Logger,
    private val scope: CoroutineScope
) : Listener {

    private fun isTracked(material: Material): Boolean = material in trackedMaterials
    private fun shouldSkip(player: Player): Boolean =
        player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR

    private fun witnessedMetadata(
        player: Player,
        action: LedgerAction,
        base: LedgerMetadata,
        itemDetails: String
    ): LedgerMetadata {
        val actionId = UUID.randomUUID()
        val attestation = witnessManager.attestAction(player, actionId, action, player.location, itemDetails)
        return base.withWitnesses(attestation.witnesses, attestation.trustLevel.name, attestation.signature)
    }

    private fun appendAsync(
        player: UUID,
        action: LedgerAction,
        material: Material,
        quantity: Int,
        metadata: LedgerMetadata
    ) {
        scope.launch {
            try {
                ledgerStorage.appendBuilt(player, action, material, quantity, metadata)
            } catch (e: Exception) {
                logger.warning("[Ledger] append failed: ${e.message}")
            }
        }
    }

    // ========== ACQUISITION ==========

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (shouldSkip(player)) return

        val block = event.block
        val drops = block.getDrops(player.inventory.itemInMainHand)
        for (drop in drops) {
            if (!isTracked(drop.type)) continue

            ownershipManager.setOwner(drop, player.uniqueId)

            val base = LedgerMetadata.fromLocation(block.location).copy(
                blockType = block.type,
                toolUsed = player.inventory.itemInMainHand.type
            )
            val meta = witnessedMetadata(player, LedgerAction.MINE, base, "${drop.amount}x${drop.type.name}")
            appendAsync(player.uniqueId, LedgerAction.MINE, drop.type, drop.amount, meta)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return
        if (shouldSkip(player)) return

        val result = event.recipe.result
        if (!isTracked(result.type)) return

        val amount = if (event.isShiftClick) calculateShiftCraftAmount(event) else result.amount

        val taggedResult = result.clone()
        ownershipManager.setOwner(taggedResult, player.uniqueId)
        event.currentItem = taggedResult

        val meta = witnessedMetadata(
            player, LedgerAction.CRAFT,
            LedgerMetadata.fromLocation(player.location),
            "${amount}x${result.type.name}"
        )
        appendAsync(player.uniqueId, LedgerAction.CRAFT, result.type, amount, meta)

        scope.launch {
            val tmar = reconciliationEngine.checkTmar(player, result.type, amount)
            if (!tmar.allowed) {
                logger.warning("[TMAR] ${player.name} exceeded ${result.type}: ${tmar.projectedRate}/${tmar.limit}")
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (shouldSkip(player)) return

        val item = event.item.itemStack
        if (!isTracked(item.type)) return

        val previousOwner = ownershipManager.getOwner(item)
        val base = LedgerMetadata.fromLocation(event.item.location).let {
            if (previousOwner != null && previousOwner != player.uniqueId) it.withRelatedPlayer(previousOwner) else it
        }
        var meta = witnessedMetadata(player, LedgerAction.PICKUP, base, "${item.amount}x${item.type.name}")

        if (previousOwner == null && meta.trustLevel == "SOLO") {
            meta = meta.copy(notes = "UNTRACKED_SOLO_PICKUP")
            logger.warning("[PoW] ${player.name} picked up untracked ${item.type} with no witnesses")
        }

        // Item-frame dupe detection: if a frame recently broke and dropped one item, the
        // expected drop is matched. If pickups in that area exceed the expected amount, the
        // surplus is unaccounted-for inventory — almost certainly a piston/chunk-race dupe.
        val excess = matchPickupToFrameDrop(item.type, item.amount, event.item.location)
        if (excess != null && excess > 0) {
            meta = meta.copy(notes = "FRAME_DROP_EXCESS:$excess")
            logger.warning("[DUPE] ${player.name} picked up $excess extra ${item.type} from a frame drop area")
            reconciliationEngine.flagFrameDupe(player, item.type, excess)
        }

        ownershipManager.setOwner(item, player.uniqueId)
        appendAsync(player.uniqueId, LedgerAction.PICKUP, item.type, item.amount, meta)

        val suspicion = witnessManager.hasSuspiciousPattern(player.uniqueId)
        if (suspicion.suspicious) {
            logger.warning("[PoW] Suspicious pattern for ${player.name}: ${suspicion.reason}")
        }

        if (previousOwner != null && previousOwner != player.uniqueId || previousOwner == null) {
            reconciliationEngine.reconcileAsync(player)
        }
    }

    // ========== DISPOSAL ==========

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (shouldSkip(player)) return
        val item = event.itemInHand
        if (!isTracked(item.type)) return

        appendAsync(
            player.uniqueId, LedgerAction.PLACE, item.type, -1,
            LedgerMetadata.fromLocation(event.block.location)
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        val player = event.player
        if (shouldSkip(player)) return
        val item = event.itemDrop.itemStack
        if (!isTracked(item.type)) return

        appendAsync(
            player.uniqueId, LedgerAction.DROP, item.type, -item.amount,
            LedgerMetadata.fromLocation(event.itemDrop.location)
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        val player = event.player
        if (shouldSkip(player)) return
        val item = event.item
        if (!isTracked(item.type)) return

        appendAsync(
            player.uniqueId, LedgerAction.CONSUME, item.type, -1,
            LedgerMetadata.fromLocation(player.location)
        )
    }

    // ========== CONTAINER / ENTITY TRANSFER ==========

    /**
     * Correct put/take classification by InventoryAction. Handles both block containers
     * (chest/shulker/barrel/etc) and entity inventories (horse/donkey/llama/chest boat/chest minecart).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (shouldSkip(player)) return

        val topInv = event.view.topInventory
        val target = classifyHolder(topInv.holder) ?: return
        val clickedTop = event.rawSlot < topInv.size

        val current = event.currentItem
        val cursor = event.cursor

        when (event.action) {
            InventoryAction.MOVE_TO_OTHER_INVENTORY -> {
                if (current == null || !isTracked(current.type)) return
                if (clickedTop) recordTake(player, current.type, current.amount, target)
                else recordPut(player, current.type, current.amount, target)
            }
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_ONE, InventoryAction.PICKUP_SOME -> {
                if (!clickedTop || current == null || !isTracked(current.type)) return
                val qty = when (event.action) {
                    InventoryAction.PICKUP_HALF -> (current.amount + 1) / 2
                    InventoryAction.PICKUP_ONE -> 1
                    else -> current.amount
                }
                recordTake(player, current.type, qty, target)
            }
            InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME -> {
                if (!clickedTop || cursor.type == Material.AIR || !isTracked(cursor.type)) return
                val qty = when (event.action) {
                    InventoryAction.PLACE_ONE -> 1
                    InventoryAction.PLACE_SOME -> cursor.amount - (current?.amount ?: 0).coerceAtLeast(0)
                    else -> cursor.amount
                }
                if (qty > 0) recordPut(player, cursor.type, qty, target)
            }
            InventoryAction.SWAP_WITH_CURSOR -> {
                if (!clickedTop) return
                if (current != null && isTracked(current.type)) recordTake(player, current.type, current.amount, target)
                if (isTracked(cursor.type)) recordPut(player, cursor.type, cursor.amount, target)
            }
            else -> Unit
        }
    }

    private sealed class StorageTarget(val label: String, val location: Location?, val isEntity: Boolean)
    private class BlockContainerTarget(c: Container) : StorageTarget(c.type.name,
        try { c.location } catch (e: Exception) { null }, false)
    private class EntityContainerTarget(e: Entity) : StorageTarget(e.type.name, e.location, true)

    private fun classifyHolder(holder: InventoryHolder?): StorageTarget? {
        if (holder == null) return null
        if (holder is Container) return BlockContainerTarget(holder)
        // Entity-backed inventories: horses, donkeys, llamas, mules, chest boats, storage minecarts.
        val asEntity = holder as? Entity ?: return null
        if (asEntity is Player) return null
        val isStorage = asEntity is AbstractHorse || asEntity is Boat || asEntity is Minecart
        if (!isStorage) return null
        return EntityContainerTarget(asEntity)
    }

    private fun recordTake(player: Player, mat: Material, qty: Int, target: StorageTarget) {
        val action = if (target.isEntity) LedgerAction.ENTITY_TAKE else LedgerAction.CONTAINER_TAKE
        val meta = LedgerMetadata.fromLocation(player.location).withContainer(target.label, target.location)
        appendAsync(player.uniqueId, action, mat, qty, meta)
    }

    private fun recordPut(player: Player, mat: Material, qty: Int, target: StorageTarget) {
        val action = if (target.isEntity) LedgerAction.ENTITY_PUT else LedgerAction.CONTAINER_PUT
        val meta = LedgerMetadata.fromLocation(player.location).withContainer(target.label, target.location)
        appendAsync(player.uniqueId, action, mat, -qty, meta)
    }

    // ========== ITEM FRAMES ==========

    /**
     * Tracks expected drops from item frames so we can detect frame-piston / chunk-race
     * dupes: each frame break registers exactly one expected drop, and any pickup beyond
     * the expected amount within a short radius and time window is an unaccounted-for copy.
     */
    private data class ExpectedDrop(
        val material: Material,
        val expectedAmount: Int,
        var matchedAmount: Int,
        val worldName: String,
        val x: Double, val y: Double, val z: Double,
        val sourcePlayer: UUID?,
        val expiresAt: Long
    )

    private data class FrameContent(val material: Material, val amount: Int, val placedBy: UUID)

    /** entity UUID -> what's currently in the frame. Only populated as frames are interacted with. */
    private val frameContents = ConcurrentHashMap<UUID, FrameContent>()
    private val expectedDrops = ConcurrentLinkedDeque<ExpectedDrop>()
    private val frameDropExpiryMs = 60_000L
    private val frameDropMatchRadiusSq = 25.0   // 5 blocks

    private fun pruneExpiredDrops() {
        val now = System.currentTimeMillis()
        expectedDrops.removeIf { it.expiresAt < now || it.matchedAmount >= it.expectedAmount }
    }

    /**
     * Try to attribute a pickup to a previously-registered expected drop.
     * @return excess amount picked up over the expected amount (>0 = dupe candidate),
     *         or null if no expected drop matched.
     */
    private fun matchPickupToFrameDrop(material: Material, amount: Int, loc: Location): Int? {
        pruneExpiredDrops()
        val worldName = loc.world?.name ?: return null
        for (drop in expectedDrops) {
            if (drop.material != material) continue
            if (drop.worldName != worldName) continue
            val dx = drop.x - loc.x; val dy = drop.y - loc.y; val dz = drop.z - loc.z
            if (dx * dx + dy * dy + dz * dz > frameDropMatchRadiusSq) continue
            drop.matchedAmount += amount
            return (drop.matchedAmount - drop.expectedAmount).coerceAtLeast(0)
        }
        return null
    }

    private fun expectFrameDrop(material: Material, amount: Int, loc: Location, sourcePlayer: UUID?) {
        val world = loc.world ?: return
        expectedDrops.add(ExpectedDrop(
            material = material, expectedAmount = amount, matchedAmount = 0,
            worldName = world.name, x = loc.x, y = loc.y, z = loc.z,
            sourcePlayer = sourcePlayer, expiresAt = System.currentTimeMillis() + frameDropExpiryMs
        ))
    }

    /**
     * Right-click on an item frame. Two cases:
     *   - Frame empty + player holds tracked item: that item is being placed in. PUT.
     *   - Frame has item: rotation only, no transfer. Skip.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFrameRightClick(event: PlayerInteractEntityEvent) {
        val frame = event.rightClicked as? ItemFrame ?: return
        val player = event.player
        if (shouldSkip(player)) return

        if (frame.item.type != Material.AIR) return  // rotation, not a transfer

        val held = event.hand.let { hand ->
            when (hand) {
                org.bukkit.inventory.EquipmentSlot.HAND -> player.inventory.itemInMainHand
                org.bukkit.inventory.EquipmentSlot.OFF_HAND -> player.inventory.itemInOffHand
                else -> return
            }
        }
        if (held.type == Material.AIR || !isTracked(held.type)) return

        // Frame doesn't store stack count; vanilla puts exactly one item in.
        val meta = LedgerMetadata.fromLocation(frame.location)
            .copy(containerType = "ITEM_FRAME", containerLocation = "${frame.location.world?.name},${frame.location.blockX},${frame.location.blockY},${frame.location.blockZ}")
        appendAsync(player.uniqueId, LedgerAction.FRAME_PUT, held.type, -1, meta)

        frameContents[frame.uniqueId] = FrameContent(held.type, 1, player.uniqueId)
    }

    /**
     * Left-click attack on an item frame. If it has a tracked item, that item is about to pop out.
     * Record FRAME_TAKE and pre-register an expected drop so a normal single pickup is matched
     * and not flagged.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFrameDamageByPlayer(event: EntityDamageByEntityEvent) {
        val frame = event.entity as? ItemFrame ?: return
        val player = event.damager as? Player ?: return
        if (shouldSkip(player)) return

        val item = frame.item
        if (item.type == Material.AIR || !isTracked(item.type)) return

        val mat = item.type
        val amt = item.amount.coerceAtLeast(1)
        val meta = LedgerMetadata.fromLocation(frame.location)
            .copy(containerType = "ITEM_FRAME",
                  containerLocation = "${frame.location.world?.name},${frame.location.blockX},${frame.location.blockY},${frame.location.blockZ}")
        appendAsync(player.uniqueId, LedgerAction.FRAME_TAKE, mat, amt, meta)

        // One legitimate item entity is about to spawn. Reserve it so the upcoming PICKUP doesn't
        // get attributed as a fresh acquisition.
        expectFrameDrop(mat, amt, frame.location, player.uniqueId)
        frameContents.remove(frame.uniqueId)
    }

    /**
     * Frame broken by something that isn't a direct left-click (piston, projectile, explosion,
     * chunk events). This is the dupe vector: we expected one drop, the exploit may produce two.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFrameBreakByEntity(event: HangingBreakByEntityEvent) {
        val frame = event.entity as? ItemFrame ?: return
        recordFrameBreakDrop(frame, (event.remover as? Player)?.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFrameBreak(event: HangingBreakEvent) {
        if (event is HangingBreakByEntityEvent) return  // handled above to avoid double-count
        val frame = event.entity as? ItemFrame ?: return
        recordFrameBreakDrop(frame, null)
    }

    private fun recordFrameBreakDrop(frame: ItemFrame, source: UUID?) {
        val cached = frameContents.remove(frame.uniqueId)
        val item = frame.item
        val material: Material; val amount: Int
        if (cached != null) { material = cached.material; amount = cached.amount }
        else if (item.type != Material.AIR && isTracked(item.type)) { material = item.type; amount = item.amount.coerceAtLeast(1) }
        else return

        expectFrameDrop(material, amount, frame.location, source)
    }

    // ========== UTILITY ==========

    /**
     * Shift-crafting in vanilla repeats the craft until either an ingredient runs out
     * OR the inventory can't hold any more output. We compute both and take the min.
     */
    private fun calculateShiftCraftAmount(event: CraftItemEvent): Int {
        val result = event.recipe.result
        val matrix = event.inventory.matrix

        var maxCraftsByIngredients = Int.MAX_VALUE
        for (ing in matrix) {
            if (ing != null && ing.type != Material.AIR) {
                maxCraftsByIngredients = minOf(maxCraftsByIngredients, ing.amount)
            }
        }
        if (maxCraftsByIngredients == Int.MAX_VALUE) maxCraftsByIngredients = 0

        val player = event.whoClicked as? Player
        val maxCraftsByInventory = if (player != null) {
            val maxStack = result.maxStackSize.coerceAtLeast(1)
            var capacity = 0
            for (slot in player.inventory.storageContents) {
                capacity += when {
                    slot == null || slot.type == Material.AIR -> maxStack
                    slot.isSimilar(result) -> (maxStack - slot.amount).coerceAtLeast(0)
                    else -> 0
                }
            }
            capacity / result.amount.coerceAtLeast(1)
        } else Int.MAX_VALUE

        return result.amount * minOf(maxCraftsByIngredients, maxCraftsByInventory)
    }
}
