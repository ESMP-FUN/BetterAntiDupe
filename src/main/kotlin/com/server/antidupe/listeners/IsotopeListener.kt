package com.server.antidupe.listeners

import com.server.antidupe.core.DupeAlertManager
import com.server.antidupe.core.IsotopeManager
import com.server.antidupe.core.IsotopeManager.Companion.isotopeId
import com.server.antidupe.core.IsotopeScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class IsotopeListener(
    private val plugin: JavaPlugin,
    private val isotopeManager: IsotopeManager,
    private val dupeAlertManager: DupeAlertManager,
    private val scanner: IsotopeScanner,
    private val scope: CoroutineScope
) : Listener {

    // List of items worth tracking - loaded from materials.yml
    private val trackedMaterials: Set<Material> by lazy {
        val source = (plugin as? io.github.darkstarworks.AntiDupePro)?.materialsConfig ?: plugin.config
        val materialNames = source.getStringList("tracked_materials")
        materialNames.mapNotNull { name ->
            try {
                Material.valueOf(name.uppercase())
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("Invalid material in config: $name")
                null
            }
        }.toSet().also { materials ->
            // Also include all shulker box variants
            val allTracked = materials.toMutableSet()
            Material.entries.filter { it.name.contains("SHULKER_BOX") }.forEach { allTracked.add(it) }
            plugin.logger.info("Tracking ${allTracked.size} material types")
        }
    }

    // Snapshot storage for detecting merges on shift-click
    // Map<PlayerUUID, Map<SlotIndex, IsotopeID>>
    private val inventorySnapshots = ConcurrentHashMap<UUID, MutableMap<Int, UUID?>>()

    // ==================== INVENTORY CLICK EVENT ====================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (player.gameMode == GameMode.CREATIVE) return

        val currentItem = event.currentItem
        val cursor = event.cursor

        // Optional: block double-click-gather on tracked items (genealogy is intractable here).
        // Off by default to avoid UX friction; enable with block_collect_to_cursor: true.
        if (event.action == InventoryAction.COLLECT_TO_CURSOR && isTracked(cursor)
            && plugin.config.getBoolean("block_collect_to_cursor", false)) {
            event.isCancelled = true
            return
        }

        // 2. Take snapshot BEFORE the event resolves (for shift-click detection)
        if (event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY && isTracked(currentItem)) {
            takeInventorySnapshot(player.uniqueId, event.view.topInventory, event.view.bottomInventory)
        }

        // 3. GENEALOGY TRACKING - Use runTask to capture final state AFTER vanilla processing
        plugin.server.scheduler.runTask(plugin, Runnable {
            val newCursor = event.view.cursor
            val newSlotItem = event.view.getItem(event.rawSlot)

            // Handle Split (Right Click on stack)
            if (event.isRightClick && event.action == InventoryAction.PICKUP_HALF) {
                processSplit(player.uniqueId, newSlotItem, newCursor)
            }

            // Handle Merges (Placing items on existing stack)
            if (event.isLeftClick && (event.action == InventoryAction.PLACE_ALL || event.action == InventoryAction.PLACE_SOME)) {
                if (isTracked(newSlotItem)) {
                    isotopeManager.handleMergeResult(newSlotItem, player.uniqueId)
                }
            }

            // Handle Shift-Clicks (Move + Potential Merge)
            if (event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                scanInventoryForNewArrivals(player.uniqueId, event.view.topInventory, event.view.bottomInventory)
            }

            // Handle Swap with hotbar
            if (event.action == InventoryAction.HOTBAR_SWAP || event.action == InventoryAction.HOTBAR_MOVE_AND_READD) {
                // Both items retain their IDs - no minting needed
            }
        })
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        inventorySnapshots.remove(event.player.uniqueId)
    }

    // ==================== INVENTORY CLOSE EVENT ====================

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val inventory = event.inventory

        // Clean up snapshots
        inventorySnapshots.remove(player.uniqueId)

        // Only check storable containers (Chest, Barrel, Shulker)
        if (inventory.type == InventoryType.CHEST ||
            inventory.type == InventoryType.BARREL ||
            inventory.type == InventoryType.SHULKER_BOX
        ) {
            val location = inventory.location
            dupeAlertManager.handleContainerClose(player, location)
        }
    }

    // ==================== INVENTORY DRAG EVENT ====================

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (player.gameMode == GameMode.CREATIVE) return

        // Drags are essentially multi-splits
        // Cursor (ID: A) -> distributed into N slots
        // Logic: Burn A, Mint new IDs for each result

        val oldCursor = event.oldCursor
        if (!isTracked(oldCursor)) return

        val oldId = oldCursor.isotopeId

        plugin.server.scheduler.runTask(plugin, Runnable {
            // Burn the original cursor ID
            isotopeManager.burnIsotopeAsync(oldId)

            // Mint new IDs for each distributed item
            event.newItems.forEach { (_, itemStack) ->
                isotopeManager.mintIsotope(itemStack, player.uniqueId)
            }

            // Mint for the cursor remainder (if any)
            val newCursor = event.view.cursor
            if (newCursor.amount > 0 && isTracked(newCursor)) {
                isotopeManager.mintIsotope(newCursor, player.uniqueId)
            }
        })
    }

    // ==================== ITEM PICKUP EVENT (Ground Items & Shulker Scanning) ====================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val item = event.item.itemStack

        // TMAR Check: Rate limit acquisitions
        if (isTracked(item)) {
            val allowed = dupeAlertManager.checkAcquisitionRate(player, item)
            if (!allowed) {
                // Optionally cancel the pickup for rate-limited players
                // event.isCancelled = true
                // For now, just log and allow (shadow mode)
            }

            // Mint isotope for newly picked up tracked items (if not already tracked)
            if (item.isotopeId == null) {
                isotopeManager.mintIsotope(item, player.uniqueId)
            }
        }

        // Scan Shulker Boxes for nested dupes
        if (item.type.name.contains("SHULKER_BOX")) {
            // Clone the item for scanning since the original will be in player's inventory
            val itemToScan = item.clone()

            scope.launch {
                val isClean = scanner.scanItemHierarchy(itemToScan, player.uniqueId)

                if (!isClean) {
                    dupeAlertManager.flagAnomaly(player, item, "NESTED_DUPE_DETECTED", 100.0)

                    val autoDelete = plugin.config.getBoolean("auto_delete_dupes", false)
                    if (autoDelete) {
                        // Remove from player's inventory (not ground entity - already picked up)
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            // Find and remove the shulker from player's inventory
                            val inventory = player.inventory
                            for (slot in 0 until inventory.size) {
                                val slotItem = inventory.getItem(slot)
                                if (slotItem != null &&
                                    slotItem.type == item.type &&
                                    slotItem.isotopeId == item.isotopeId) {
                                    inventory.setItem(slot, null)
                                    player.sendMessage("§c[AntiDupe] A suspicious shulker box was confiscated.")
                                    plugin.logger.warning("Auto-deleted duped shulker from ${player.name}'s inventory")
                                    break
                                }
                            }
                        })
                    }
                }
            }
        }
    }

    // ==================== CRAFTING EVENT (Initial Minting) ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return
        if (player.gameMode == GameMode.CREATIVE) return

        val result = event.recipe.result

        // Only track configured materials
        if (!isTracked(result)) return

        // Use runTask to mint after the crafting completes
        plugin.server.scheduler.runTask(plugin, Runnable {
            // Find the crafted item in the result slot
            val craftedItem = event.inventory.result
            if (craftedItem != null && isTracked(craftedItem) && craftedItem.isotopeId == null) {
                isotopeManager.mintIsotope(craftedItem, player.uniqueId)
            }
        })
    }

    // ==================== BLOCK BREAK EVENT (Mining) ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (player.gameMode == GameMode.CREATIVE) return

        // Check if the block drops tracked items
        val block = event.block
        val drops = block.getDrops(player.inventory.itemInMainHand)

        drops.forEach { drop ->
            if (isTracked(drop)) {
                // Post-event minting - the actual drop happens after this event
                plugin.server.scheduler.runTask(plugin, Runnable {
                    // Find the dropped item entity near the block
                    block.world.getNearbyEntities(block.location, 2.0, 2.0, 2.0)
                        .filterIsInstance<org.bukkit.entity.Item>()
                        .filter { it.itemStack.type == drop.type && it.itemStack.isotopeId == null }
                        .forEach { itemEntity ->
                            isotopeManager.mintIsotope(itemEntity.itemStack, player.uniqueId)
                        }
                })
            }
        }

        // Special handling for Shulker Boxes - mint the dropped shulker
        if (block.type.name.contains("SHULKER_BOX")) {
            plugin.server.scheduler.runTask(plugin, Runnable {
                block.world.getNearbyEntities(block.location, 2.0, 2.0, 2.0)
                    .filterIsInstance<org.bukkit.entity.Item>()
                    .filter { it.itemStack.type.name.contains("SHULKER_BOX") && it.itemStack.isotopeId == null }
                    .forEach { itemEntity ->
                        isotopeManager.mintIsotope(itemEntity.itemStack, player.uniqueId)
                    }
            })
        }
    }

    // ==================== HOPPER/AUTOMATION TRANSFER EVENT ====================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
        val item = event.item

        // Only process tracked items
        if (!isTracked(item)) return

        // If item is being moved and has no isotope, mint one (system-minted)
        if (item.isotopeId == null) {
            isotopeManager.mintIsotope(item, null)
        }

        // Scan shulker boxes being moved through hoppers
        if (item.type.name.contains("SHULKER_BOX")) {
            scope.launch {
                val isClean = scanner.scanItemHierarchy(item, UUID(0, 0))
                if (!isClean) {
                    // Log the suspicious automation transfer
                    val sourceType = event.source.type.name
                    val destType = event.destination.type.name
                    plugin.logger.warning("DUPE VIA AUTOMATION: Shulker with spent items moved from $sourceType to $destType")

                    // Optionally cancel the transfer
                    val autoDelete = plugin.config.getBoolean("auto_delete_dupes", false)
                    if (autoDelete) {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            event.isCancelled = true
                        })
                    }
                }
            }
        }
    }

    // ==================== HELPER METHODS ====================

    /**
     * Process a stack split - burns old ID, mints two new ones
     * Both items must exist and BOTH must be tracked for proper split handling.
     * The slotItem (original) must have an existing isotopeId to burn.
     */
    private fun processSplit(owner: UUID, slotItem: ItemStack?, cursorItem: ItemStack?) {
        // Both must exist for a valid split
        if (slotItem == null || cursorItem == null) return

        // Both items must be of tracked material types
        if (!isTracked(slotItem) || !isTracked(cursorItem)) return

        // The original (slotItem) must have an existing isotope ID to split from
        // If it doesn't, this might be the first time we're tracking this item
        val existingId = slotItem.isotopeId
        if (existingId == null) {
            // Neither has an ID - mint both as new items
            isotopeManager.mintIsotope(slotItem, owner)
            isotopeManager.mintIsotope(cursorItem, owner)
            return
        }

        // Use the manager's split handler (burns old, mints two new)
        isotopeManager.handleSplit(slotItem, cursorItem, owner)
    }

    /**
     * Check if an item should be tracked
     */
    private fun isTracked(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) return false
        if (item.type.name.contains("SHULKER_BOX")) return true
        return trackedMaterials.contains(item.type)
    }

    /**
     * Take a snapshot of isotope IDs in inventories before an operation
     */
    private fun takeInventorySnapshot(playerId: UUID, vararg inventories: Inventory) {
        val snapshot = mutableMapOf<Int, UUID?>()
        var slotOffset = 0

        for (inv in inventories) {
            inv.contents.forEachIndexed { index, item ->
                if (item != null && isTracked(item)) {
                    snapshot[slotOffset + index] = item.isotopeId
                }
            }
            slotOffset += inv.size
        }

        inventorySnapshots[playerId] = snapshot
    }

    /**
     * Scan inventories after a shift-click to detect merges
     * Compares current state with pre-operation snapshot
     */
    private fun scanInventoryForNewArrivals(playerId: UUID, vararg inventories: Inventory) {
        val oldSnapshot = inventorySnapshots.remove(playerId) ?: return

        var slotOffset = 0
        for (inv in inventories) {
            inv.contents.forEachIndexed { index, item ->
                if (item != null && isTracked(item)) {
                    val globalSlot = slotOffset + index
                    val oldId = oldSnapshot[globalSlot]
                    val currentId = item.isotopeId

                    // Case 1: Slot had an item, now has different ID -> merge occurred
                    if (oldId != null && currentId != oldId) {
                        // A merge happened - the item in this slot changed
                        // Re-mint to ensure proper tracking
                        isotopeManager.handleMergeResult(item, playerId)
                    }

                    // Case 2: Slot was empty or had different item, now has tracked item
                    if (oldId == null && currentId == null && isTracked(item)) {
                        // New item arrived without an ID - mint it
                        isotopeManager.mintIsotope(item, playerId)
                    }
                }
            }
            slotOffset += inv.size
        }
    }
}
