package com.server.antidupe.ledger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
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

    // ========== CONTAINER TRANSFER ==========

    /**
     * Correct put/take classification by InventoryAction. We bail on actions whose effect can't
     * be inferred from the event alone (drags, hotbar swaps) — those are tracked by reconciliation.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (shouldSkip(player)) return

        val topInv = event.view.topInventory
        val container = topInv.holder as? Container ?: return
        val clickedTop = event.rawSlot < topInv.size

        val current = event.currentItem
        val cursor = event.cursor

        when (event.action) {
            InventoryAction.MOVE_TO_OTHER_INVENTORY -> {
                if (current == null || !isTracked(current.type)) return
                if (clickedTop) recordTake(player, current.type, current.amount, container)
                else recordPut(player, current.type, current.amount, container)
            }
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_ONE, InventoryAction.PICKUP_SOME -> {
                if (!clickedTop || current == null || !isTracked(current.type)) return
                val qty = when (event.action) {
                    InventoryAction.PICKUP_HALF -> (current.amount + 1) / 2
                    InventoryAction.PICKUP_ONE -> 1
                    else -> current.amount
                }
                recordTake(player, current.type, qty, container)
            }
            InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME -> {
                if (!clickedTop || cursor.type == Material.AIR || !isTracked(cursor.type)) return
                val qty = when (event.action) {
                    InventoryAction.PLACE_ONE -> 1
                    InventoryAction.PLACE_SOME -> cursor.amount - (current?.amount ?: 0).coerceAtLeast(0)
                    else -> cursor.amount
                }
                if (qty > 0) recordPut(player, cursor.type, qty, container)
            }
            InventoryAction.SWAP_WITH_CURSOR -> {
                if (!clickedTop) return
                if (current != null && isTracked(current.type)) recordTake(player, current.type, current.amount, container)
                if (isTracked(cursor.type)) recordPut(player, cursor.type, cursor.amount, container)
            }
            else -> Unit
        }
    }

    private fun recordTake(player: Player, mat: Material, qty: Int, container: Container) {
        val meta = LedgerMetadata.fromLocation(player.location)
            .withContainer(container.type.name, containerLoc(container))
        appendAsync(player.uniqueId, LedgerAction.CONTAINER_TAKE, mat, qty, meta)
    }

    private fun recordPut(player: Player, mat: Material, qty: Int, container: Container) {
        val meta = LedgerMetadata.fromLocation(player.location)
            .withContainer(container.type.name, containerLoc(container))
        appendAsync(player.uniqueId, LedgerAction.CONTAINER_PUT, mat, -qty, meta)
    }

    private fun containerLoc(container: Container): Location? = try { container.location } catch (e: Exception) { null }

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
