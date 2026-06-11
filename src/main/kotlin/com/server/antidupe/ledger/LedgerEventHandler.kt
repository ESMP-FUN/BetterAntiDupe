package com.server.antidupe.ledger

import com.server.antidupe.platform.PlatformScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.block.DecoratedPot
import org.bukkit.block.DoubleChest
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.Boat
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.minecart.HopperMinecart
import org.bukkit.entity.minecart.StorageMinecart
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.FurnaceExtractEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerTakeLecternBookEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Inventory
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
    private val scope: CoroutineScope,
    private val scheduler: PlatformScheduler
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

    /**
     * Block-break drops, observed via BlockDropItemEvent rather than BlockBreakEvent +
     * `block.getDrops(tool)`. Two reasons:
     *   1. `getDrops` performs a fresh random loot roll — for fortune-affected blocks it can
     *      disagree with the items actually dropped, under-authorizing and false-flagging
     *      the pickup as source-bound excess.
     *   2. The stacks `getDrops` returns are detached copies; tagging them never reached the
     *      real item entities. Here we get the actual entities and tag their live stacks.
     * This also covers decorated-pot contents (the stored item is part of the drop list), so
     * a deposit (CONTAINER_PUT -1) followed by break + pickup (+1) still nets to zero.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockDropItem(event: BlockDropItemEvent) {
        val player = event.player
        if (shouldSkip(player)) return

        val brokenType = event.blockState.type
        val tool = player.inventory.itemInMainHand
        for (itemEntity in event.items) {
            val stack = itemEntity.itemStack
            if (!isTracked(stack.type)) continue
            ownershipManager.setOwner(stack, player.uniqueId)
            itemEntity.itemStack = stack

            // No MINE ledger entry — the credit happens at pickup time. The PICKUP entry
            // inherits the source context (block type + tool) via the expected drop so the
            // history view still shows "this came from mining DIAMOND_ORE with NETHERITE_PICKAXE".
            authorizeDrop(
                material = stack.type, amount = stack.amount,
                loc = itemEntity.location, sourcePlayer = player.uniqueId,
                sourceAction = LedgerAction.MINE,
                sourceContext = "MINE:${brokenType.name}|TOOL:${tool.type.name}"
            )
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

        // Defer the source-matching, credit and entity-uuid dupe check by one tick so we only
        // act on pickups that actually COMPLETED. Another plugin can cancel the pickup at a
        // later priority (vault / drop-protection plugins do this); the item then survives
        // inside the player's hitbox and the event re-fires every tick. Acting at event time
        // would mark the UUID consumed on the first (failed) attempt and then flag every
        // subsequent re-fire as a dupe — an alert storm — while also crediting items that
        // never entered the inventory and burning drop authorizations a real pickup needs.
        val baseMeta = meta
        val capturedPlayerId = player.uniqueId
        val capturedMaterial = item.type
        val capturedAmount = item.amount
        val itemEntity = event.item
        val entityUuid = itemEntity.uniqueId
        val pickupLoc = itemEntity.location.clone()

        scheduler.runForEntityLater(itemEntity, 1, Runnable {
            val survived = itemEntity.isValid
            val amountAfter = if (survived) itemEntity.itemStack.amount else 0
            val consumed = capturedAmount - amountAfter
            if (consumed <= 0) return@Runnable  // pickup was cancelled downstream — nothing moved

            // Match the completed pickup against expected drops (mine, frame, pot break, etc.).
            // The matched portion is credited; the excess (if any) is the dupe — we leave it OFF
            // the ledger so the reconciliation pass also catches it, AND alert immediately.
            val match = matchPickup(capturedMaterial, consumed, pickupLoc)
            val excess = match?.excess ?: 0
            val creditAmount = (consumed - excess).coerceAtLeast(0)

            var deferredMeta = baseMeta
            if (excess > 0) {
                val src = match?.sourceContext ?: "UNKNOWN_SOURCE"
                logger.warning("[DUPE] ${player.name} picked up $excess $capturedMaterial beyond what nearby sources produced ($src)")
                reconciliationEngine.flagDropExcess(player, capturedMaterial, excess, src)
                deferredMeta = deferredMeta.copy(notes = listOfNotNull(deferredMeta.notes, "SOURCE_EXCESS:$excess", match?.sourceContext)
                    .joinToString("|"))
            } else if (match != null) {
                // Successful attribution — attach the source context to the audit entry.
                deferredMeta = deferredMeta.copy(notes = listOfNotNull(deferredMeta.notes, match.sourceContext).joinToString("|").ifBlank { null })
            }
            val finalMeta = deferredMeta

            scope.launch {
                // A partial pickup (near-full inventory) leaves the remainder on the ground under
                // the SAME entity UUID, so the UUID only counts as consumed once the entity is gone.
                val prev = if (!survived) {
                    try {
                        ledgerStorage.markEntityPickup(entityUuid, capturedPlayerId, capturedMaterial, consumed)
                    } catch (e: Exception) {
                        logger.warning("[Ledger] markEntityPickup failed: ${e.message}")
                        null
                    }
                } else null

                if (prev != null) {
                    // Chunk-load / drop-race dupe: this exact entity UUID was already consumed.
                    // Skip the PICKUP credit entirely — the resulting inventory-vs-ledger gap will
                    // also surface in reconciliation, providing a double-check. Alert at most once
                    // per entity UUID so a pathological re-fire can never spam the alert channel.
                    val nowMs = System.currentTimeMillis()
                    flaggedDupeEntities.entries.removeIf { nowMs - it.value > dupeAlertSuppressMs }
                    if (flaggedDupeEntities.putIfAbsent(entityUuid, nowMs) == null) {
                        scheduler.runMain(Runnable {
                            val online = plugin.server.getPlayer(capturedPlayerId) ?: return@Runnable
                            logger.severe("[DUPE] ${online.name} picked up entity $entityUuid which was previously consumed by ${prev.playerUuid} ${(System.currentTimeMillis() - prev.pickedUpAt) / 1000}s ago")
                            reconciliationEngine.flagEntityDupe(online, capturedMaterial, consumed, prev)
                        })
                    }
                    return@launch
                }

                if (creditAmount > 0) {
                    try {
                        ledgerStorage.appendBuilt(capturedPlayerId, LedgerAction.PICKUP, capturedMaterial, creditAmount, finalMeta)
                    } catch (e: Exception) {
                        logger.warning("[Ledger] PICKUP append failed: ${e.message}")
                    }
                }
            }
        })

        // Proof-of-Witness is a LOW-confidence signal only: it merely nudges transient heat, and
        // ONLY when other players are actually nearby to have witnessed. Solo farming — nobody
        // around — never accrues heat. It never alerts on its own.
        if (witnessManager.othersNearby(player)) {
            val pattern = witnessManager.hasSuspiciousPattern(player.uniqueId)
            if (pattern.suspicious) {
                logger.fine("[PoW] Pattern signal for ${player.name}: ${pattern.reason}")
                reconciliationEngine.flagWitnessPattern(player.uniqueId)
            }
        }

        if (previousOwner != null && previousOwner != player.uniqueId || previousOwner == null) {
            reconciliationEngine.reconcileAsync(player)
        }
    }

    /**
     * Mob death drops (raid totems, mob loot, etc.). Each dropped tracked item authorises an
     * acquisition of that quantity near the death location. Legitimate looting — including a
     * whole raid farm's worth funnelled to a collection point — matches these authorisations;
     * a duped entity (more items than mobs died to produce) overflows them and surfaces as
     * source-bound excess at pickup time. This is the fix for the raid-farm false positives.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: org.bukkit.event.entity.EntityDeathEvent) {
        val dead = event.entity
        val killer = dead.killer?.uniqueId
        val loc = dead.location
        for (drop in event.drops) {
            if (!isTracked(drop.type)) continue
            authorizeDrop(
                material = drop.type, amount = drop.amount,
                loc = loc, sourcePlayer = killer,
                sourceAction = LedgerAction.LOOT,
                sourceContext = "MOB_DEATH:${dead.type.name}"
            )
        }
    }

    /**
     * Loot-table generation (chest/barrel loot on first open, plugin LootTable.fillInventory).
     * Best-effort: this event's firing semantics vary across containers and versions, and vault
     * loot is ejected as item entities anyway (covered by pickup). Where it does fire with a
     * locatable holder, it authorises the generated items so taking them isn't unaccounted-for.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLootGenerate(event: org.bukkit.event.world.LootGenerateEvent) {
        val loc = (event.inventoryHolder as? org.bukkit.block.BlockState)?.location
            ?: event.entity?.location
            ?: return
        for (item in event.loot) {
            if (item == null || !isTracked(item.type)) continue
            authorizeDrop(
                material = item.type, amount = item.amount,
                loc = loc, sourcePlayer = null,
                sourceAction = LedgerAction.LOOT,
                sourceContext = "LOOT_TABLE"
            )
        }
    }

    // ========== DISPOSAL ==========

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (shouldSkip(player)) return
        val item = event.itemInHand
        if (!isTracked(item.type)) return

        // Deferred one tick: a plugin cancelling at MONITOR (after us) reverts the placement;
        // debiting at event time would leave the ledger one short and surface later as a
        // phantom excess. If the block isn't there next tick, nothing left the inventory.
        val block = event.blockPlaced
        val placedType = block.type
        val material = item.type
        val playerId = player.uniqueId
        scheduler.runForEntityLater(player, 1, Runnable {
            if (block.type != placedType) return@Runnable
            appendAsync(
                playerId, LedgerAction.PLACE, material, -1,
                LedgerMetadata.fromLocation(block.location)
            )
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        val player = event.player
        if (shouldSkip(player)) return
        val item = event.itemDrop.itemStack
        if (!isTracked(item.type)) return

        // Deferred one tick: a plugin cancelling at MONITOR (after us) removes the drop entity
        // and returns the item — debiting then would create a phantom deficit. If the entity is
        // gone for another reason (hopper suction etc.) skipping the debit only leaves the
        // ledger high, which is the safe direction.
        val dropEntity = event.itemDrop
        val material = item.type
        val amount = item.amount
        val playerId = player.uniqueId
        scheduler.runForEntityLater(dropEntity, 1, Runnable {
            if (!dropEntity.isValid) return@Runnable
            appendAsync(
                playerId, LedgerAction.DROP, material, -amount,
                LedgerMetadata.fromLocation(dropEntity.location)
            )
        })
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
     * Container transfers are recorded by SNAPSHOT-DIFF rather than per-InventoryAction
     * emulation. At click/drag time we snapshot the per-material totals of the open container,
     * then one tick later we count again and record the delta as a put or take.
     *
     * Why: action emulation had systematic holes that all surfaced as false dupe alerts —
     * MOVE_TO_OTHER_INVENTORY records the full stack even when only part fits (phantom PUT
     * deficits), HOTBAR_SWAP / HOTBAR_MOVE_AND_READD / COLLECT_TO_CURSOR / SWAP_OFFHAND and
     * drags weren't handled at all (invisible takes), and PLACE_SOME math was wrong. The diff
     * records what *actually moved*, is immune to clicks cancelled at a later MONITOR handler
     * (delta = 0), and automatically covers any InventoryAction added in future versions.
     *
     * Plugin GUIs (null or custom holders — shops, backpacks, vault menus) are deliberately
     * NOT classified: their item flow is plugin-internal, usually click-cancelled, and a shop
     * plugin must declare grants via [ChainOfCustody.recordSystemGrant] instead.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (shouldSkip(player)) return

        val topInv = event.view.topInventory

        // Workstation result-slot dispatch (smithing/anvil/loom/stonecutter/cartography/grindstone).
        val stationResult = STATION_RESULT_SLOTS[topInv.type]
        if (stationResult != null) {
            handleStationClick(player, event, topInv.type, stationResult)
            return
        }

        val target = classifyTopInventory(topInv) ?: return

        // Materials that could plausibly move in this interaction: the clicked slot, the
        // cursor, and the hotbar/offhand stack for number-key and F swaps.
        val materials = HashSet<Material>()
        event.currentItem?.let { if (it.type != Material.AIR && isTracked(it.type)) materials.add(it.type) }
        event.cursor.let { if (it.type != Material.AIR && isTracked(it.type)) materials.add(it.type) }
        if (event.click == ClickType.NUMBER_KEY && event.hotbarButton >= 0) {
            player.inventory.getItem(event.hotbarButton)?.let {
                if (it.type != Material.AIR && isTracked(it.type)) materials.add(it.type)
            }
        }
        if (event.click == ClickType.SWAP_OFFHAND) {
            player.inventory.itemInOffHand.let {
                if (it.type != Material.AIR && isTracked(it.type)) materials.add(it.type)
            }
        }
        if (materials.isEmpty()) return

        scheduleContainerDiff(player, topInv, target, materials)
    }

    /** Drag-distributing across container slots — covered by the same snapshot-diff. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (shouldSkip(player)) return

        val topInv = event.view.topInventory
        val target = classifyTopInventory(topInv) ?: return
        if (event.rawSlots.none { it < topInv.size }) return  // drag stayed in player inventory

        val mat = event.oldCursor.type
        if (mat == Material.AIR || !isTracked(mat)) return
        scheduleContainerDiff(player, topInv, target, hashSetOf(mat))
    }

    private class PendingContainerDiff(
        val inventory: Inventory,
        val target: StorageTarget,
        val preCounts: MutableMap<Material, Int>
    )

    /** One pending diff per player (a player has at most one open top inventory). Main-thread only. */
    private val pendingDiffs = ConcurrentHashMap<UUID, PendingContainerDiff>()

    private fun countInInventory(inv: Inventory, material: Material): Int {
        var total = 0
        for (stack in inv.contents) {
            if (stack != null && stack.type == material) total += stack.amount
        }
        return total
    }

    private fun scheduleContainerDiff(player: Player, inv: Inventory, target: StorageTarget, materials: Set<Material>) {
        val existing = pendingDiffs[player.uniqueId]
        if (existing != null && existing.inventory === inv) {
            // Another click in the same tick: extend the material set, keeping the FIRST
            // pre-count for materials already snapshotted (the diff spans the whole tick).
            for (mat in materials) existing.preCounts.getOrPut(mat) { countInInventory(inv, mat) }
            return
        }

        val pending = PendingContainerDiff(inv, target,
            materials.associateWithTo(HashMap()) { countInInventory(inv, it) })
        pendingDiffs[player.uniqueId] = pending

        scheduler.runForEntityLater(player, 1, Runnable {
            if (pendingDiffs[player.uniqueId] === pending) pendingDiffs.remove(player.uniqueId)
            for ((mat, pre) in pending.preCounts) {
                val post = countInInventory(pending.inventory, mat)
                val delta = post - pre
                // A hopper feeding/draining the container in the same tick can skew the delta
                // slightly; rare enough that we accept the attribution.
                if (delta > 0) recordPut(player, mat, delta, pending.target)
                else if (delta < 0) recordTake(player, mat, -delta, pending.target)
            }
        })
    }

    private sealed class StorageTarget(val label: String, val location: Location?, val isEntity: Boolean)
    private class BlockContainerTarget(c: Container) : StorageTarget(c.type.name,
        try { c.location } catch (e: Exception) { null }, false)
    private class DoubleChestTarget(dc: DoubleChest) : StorageTarget("DOUBLE_CHEST",
        try { dc.location } catch (e: Exception) { null }, false)
    private class EntityContainerTarget(e: Entity) : StorageTarget(e.type.name, e.location, true)
    private object EnderChestTarget : StorageTarget("ENDER_CHEST", null, false)

    private fun classifyTopInventory(topInv: Inventory): StorageTarget? {
        if (topInv.type == InventoryType.ENDER_CHEST) return EnderChestTarget
        val holder = topInv.holder ?: return null
        if (holder is Container) return BlockContainerTarget(holder)
        // A double chest's combined inventory is held by DoubleChest, which is an
        // InventoryHolder but NOT a Container — without this branch the most common storage
        // block on any server was completely untracked.
        if (holder is DoubleChest) return DoubleChestTarget(holder)
        // Entity-backed inventories: horses, donkeys, llamas, mules, chest boats, storage minecarts.
        val asEntity = holder as? Entity ?: return null
        if (asEntity is Player) return null
        // Entity-backed inventories. Note: not every Minecart has storage — only the storage and
        // hopper subtypes implement InventoryHolder, so we narrow to those instead of the broad
        // Minecart interface. ChestBoat extends Boat, so `is Boat` already covers chest boats.
        val isStorage = asEntity is AbstractHorse || asEntity is Boat ||
            asEntity is StorageMinecart || asEntity is HopperMinecart
        if (!isStorage) return null
        return EntityContainerTarget(asEntity)
    }

    // ========== WORKSTATION OUTPUTS ==========

    private companion object {
        /**
         * Vanilla result-slot indices for the stations whose outputs we credit. Inputs are
         * intentionally not debited; consumed inputs become silent deficits in the player's
         * ledger, and reconciliation only flags surpluses (so this is a safe asymmetry).
         */
        private val STATION_RESULT_SLOTS = mapOf(
            InventoryType.ANVIL to 2,
            InventoryType.SMITHING to 3,
            InventoryType.LOOM to 3,
            InventoryType.STONECUTTER to 1,
            InventoryType.CARTOGRAPHY to 2,
            InventoryType.GRINDSTONE to 2,
        )
    }

    private fun handleStationClick(player: Player, event: InventoryClickEvent, type: InventoryType, resultSlot: Int) {
        if (event.rawSlot != resultSlot) return       // input-slot clicks are not credited
        val current = event.currentItem ?: return
        if (current.type == Material.AIR || !isTracked(current.type)) return

        val qty = when (event.action) {
            InventoryAction.PICKUP_ALL,
            InventoryAction.MOVE_TO_OTHER_INVENTORY,
            InventoryAction.SWAP_WITH_CURSOR,
            InventoryAction.DROP_ALL_SLOT,
            InventoryAction.COLLECT_TO_CURSOR -> current.amount
            InventoryAction.PICKUP_HALF -> (current.amount + 1) / 2
            InventoryAction.PICKUP_ONE, InventoryAction.DROP_ONE_SLOT -> 1
            else -> return
        }
        if (qty <= 0) return

        val meta = LedgerMetadata.fromLocation(player.location)
            .copy(containerType = type.name, notes = "STATION:${type.name}")
        appendAsync(player.uniqueId, LedgerAction.STATION_OUTPUT, current.type, qty, meta)
    }

    /**
     * Villager trades hand the player a tracked item (librarians sell ENCHANTED_BOOK) with no
     * inventory event we can classify. Paper's PlayerTradeEvent fires once per completed trade,
     * including each repeat of a shift-click batch. Paper-only — registered via
     * [registerPaperOnlyListeners] so this class still loads on Spigot.
     */
    fun registerPaperOnlyListeners() {
        try {
            Class.forName("io.papermc.paper.event.player.PlayerTradeEvent")
            plugin.server.pluginManager.registerEvents(PaperTradeListener(), plugin)
        } catch (e: ClassNotFoundException) {
            logger.info("[Ledger] PlayerTradeEvent unavailable (Spigot?) — villager trades won't be credited")
        }
    }

    private inner class PaperTradeListener : Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onTrade(event: io.papermc.paper.event.player.PlayerTradeEvent) {
            val player = event.player
            if (shouldSkip(player)) return
            val result = event.trade.result
            if (!isTracked(result.type)) return

            val meta = LedgerMetadata.fromLocation(player.location)
                .copy(notes = "VILLAGER:${event.villager.type.name}")
            appendAsync(player.uniqueId, LedgerAction.VILLAGER_TRADE, result.type, result.amount, meta)
        }
    }

    /**
     * Enchanting a BOOK turns it into an ENCHANTED_BOOK in place — a tracked material
     * materialising with no transfer event. Credit the new material (and debit the source
     * if it happens to be tracked), then tag the result next tick while the table is open.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        val player = event.enchanter
        if (shouldSkip(player)) return
        val before = event.item.type
        val after = if (before == Material.BOOK) Material.ENCHANTED_BOOK else before
        if (after == before) return  // enchanted in place, same material — balance unchanged

        val meta = LedgerMetadata.fromLocation(event.enchantBlock.location)
            .copy(notes = "ENCHANT:${before.name}")
        if (isTracked(after)) appendAsync(player.uniqueId, LedgerAction.STATION_OUTPUT, after, 1, meta)
        if (isTracked(before)) appendAsync(player.uniqueId, LedgerAction.CONSUME, before, -1, meta)

        val inv = event.inventory
        scheduler.runForEntityLater(player, 1, Runnable {
            val slot = inv.getItem(0) ?: return@Runnable
            if (slot.type == after && isTracked(after)) {
                ownershipManager.setOwner(slot, player.uniqueId)
                inv.setItem(0, slot)
            }
        })
    }

    /**
     * Furnace / smoker / blast furnace output extracted by a player.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFurnaceExtract(event: FurnaceExtractEvent) {
        val player = event.player
        if (shouldSkip(player)) return
        if (!isTracked(event.itemType)) return

        val meta = LedgerMetadata.fromLocation(event.block.location)
            .copy(containerType = event.block.type.name, notes = "FURNACE:${event.block.type.name}")
        appendAsync(player.uniqueId, LedgerAction.STATION_OUTPUT, event.itemType, event.itemAmount, meta)
    }

    /**
     * Decorated pot deposit. Recorded as CONTAINER_PUT against the pot's location so a
     * deposit-then-break cycle nets to zero in the ledger. The break itself is handled in
     * onBlockBreak and registers the deposited stack as an expected drop, which suppresses
     * double-counting at pickup time.
     *
     * Uses PlayerInteractEvent rather than the version-specific DecoratedPotInsertEvent so
     * we work across the entire Paper 1.21.x line without recompiling.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPotRightClick(event: org.bukkit.event.player.PlayerInteractEvent) {
        if (event.action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.DECORATED_POT) return

        val player = event.player
        if (shouldSkip(player)) return

        val held = when (event.hand) {
            org.bukkit.inventory.EquipmentSlot.HAND -> player.inventory.itemInMainHand
            org.bukkit.inventory.EquipmentSlot.OFF_HAND -> player.inventory.itemInOffHand
            else -> return
        }
        if (held.type == Material.AIR || !isTracked(held.type)) return

        val state = block.state as? DecoratedPot ?: return
        val current = state.inventory.item
        // Pot is single-slot; only inserts succeed when empty or holding same item with room.
        if (current != null && current.type != Material.AIR && !current.isSimilar(held)) return

        // Known edge: a plugin cancelling this interaction at MONITOR (after us) would leave a
        // phantom -1. Pot deposits are niche enough that we accept it rather than pay for a
        // deferred pot-content comparison; the same applies to onFrameRightClick below.
        val meta = LedgerMetadata.fromLocation(block.location).copy(containerType = "DECORATED_POT")
        appendAsync(player.uniqueId, LedgerAction.CONTAINER_PUT, held.type, -1, meta)
    }

    // Crafter block automation (1.21+) is intentionally not handled here — CrafterCraftEvent
    // is exposed only on Paper API builds newer than what we currently target, and items
    // flowing out of a crafter into a hopper are already captured downstream via
    // InventoryMoveItemEvent (v1) and the consumer's CONTAINER_TAKE when they retrieve them.

    /**
     * Lectern: book taken out by player (or via redstone interaction). Credit the book to the
     * player as a normal container take so storing-then-retrieving books from a lectern is a
     * net-zero ledger operation.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLecternTake(event: PlayerTakeLecternBookEvent) {
        val player = event.player
        if (shouldSkip(player)) return
        val item = event.book ?: return
        if (!isTracked(item.type)) return

        val meta = LedgerMetadata.fromLocation(event.lectern.location).copy(containerType = "LECTERN")
        appendAsync(player.uniqueId, LedgerAction.CONTAINER_TAKE, item.type, item.amount, meta)
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
     * Source-bounded acquisition authorization. Every legitimate item-generation event (mine,
     * mob death, loot fill, frame break, pot break) registers an "authorized drop": *this much*
     * of *this material* may legitimately be acquired near *here*, within a time window. Pickups
     * consume authorizations; any portion of a pickup that can't be matched to an authorization
     * is the source-bound excess — the signal that more items exist than legitimate sources
     * produced (a duped entity).
     *
     * Three roles:
     *   1. Avoids double-counting (MINE + PICKUP once credited the same item twice).
     *   2. Surfaces excess pickups (frame-piston, pot-break, chunk-dupe drops) as a signal.
     *   3. Carries source context (action + block/tool) into the PICKUP audit entry.
     *
     * The store is **chunk-keyed** so matching is O(nearby) not O(all drops) — essential under
     * raid-farm volume where hundreds of mobs die in seconds.
     */
    private data class ExpectedDrop(
        val material: Material,
        val expectedAmount: Int,
        @Volatile var matchedAmount: Int,
        val worldName: String,
        val x: Double, val y: Double, val z: Double,
        val sourcePlayer: UUID?,
        val sourceAction: LedgerAction?,
        val sourceContext: String?,
        val expiresAt: Long
    ) {
        val remaining: Int get() = (expectedAmount - matchedAmount).coerceAtLeast(0)
    }

    private data class DropMatch(val excess: Int, val sourceAction: LedgerAction?, val sourceContext: String?)

    private data class FrameContent(val material: Material, val amount: Int, val placedBy: UUID)

    /** entity UUID -> what's currently in the frame. Only populated as frames are interacted with. */
    private val frameContents = ConcurrentHashMap<UUID, FrameContent>()

    /** Entity UUIDs already alerted as re-used — suppresses repeat alerts for the same entity. */
    private val flaggedDupeEntities = ConcurrentHashMap<UUID, Long>()
    private val dupeAlertSuppressMs = 60_000L

    /** Authorized drops bucketed by "world:chunkX:chunkZ". */
    private val authorizedDrops = ConcurrentHashMap<String, ConcurrentLinkedDeque<ExpectedDrop>>()

    // Generous defaults: items in farms travel via water streams / drop chutes, so a tight radius
    // would false-flag legitimate collection. The quantity bound does the real work; the radius
    // only scopes "plausibly from a recent nearby source". Tuned by sensitivity in a later step.
    private val dropMatchRadius = 24.0
    private val dropMatchRadiusSq = dropMatchRadius * dropMatchRadius
    private val dropChunkRadius = Math.ceil(dropMatchRadius / 16.0).toInt()
    private val dropWindowMs = 300_000L  // 5 minutes

    private fun chunkKey(worldName: String, blockX: Int, blockZ: Int): String =
        "$worldName:${blockX shr 4}:${blockZ shr 4}"

    /**
     * Sweep all expired/exhausted authorizations. [pruneExpiredDropsIn] only prunes a chunk
     * when something NEW is authorized there, so a chunk mined once would otherwise keep its
     * (expired) deque forever. Called from the maintenance loop.
     */
    fun pruneAuthorizedDrops() {
        val now = System.currentTimeMillis()
        val iter = authorizedDrops.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            entry.value.removeIf { it.expiresAt < now || it.remaining <= 0 }
            if (entry.value.isEmpty()) iter.remove()
        }
    }

    private fun pruneExpiredDropsIn(key: String) {
        val deque = authorizedDrops[key] ?: return
        val now = System.currentTimeMillis()
        deque.removeIf { it.expiresAt < now || it.remaining <= 0 }
        if (deque.isEmpty()) authorizedDrops.remove(key)
    }

    /**
     * Greedily consume authorizations matching this pickup across the nearby chunks, up to the
     * pickup amount. The unconsumed remainder is the source-bound excess.
     * @return a DropMatch when at least one authorization matched, else null (no nearby source —
     *         the pickup is credited in full and left to balance reconciliation).
     */
    private fun matchPickup(material: Material, amount: Int, loc: Location): DropMatch? {
        val world = loc.world ?: return null
        val worldName = world.name
        val cx = loc.blockX shr 4
        val cz = loc.blockZ shr 4
        val now = System.currentTimeMillis()

        var remaining = amount
        var matchedAny = false
        var ctxAction: LedgerAction? = null
        var ctx: String? = null

        for (dcx in (cx - dropChunkRadius)..(cx + dropChunkRadius)) {
            for (dcz in (cz - dropChunkRadius)..(cz + dropChunkRadius)) {
                if (remaining <= 0) break
                val deque = authorizedDrops["$worldName:$dcx:$dcz"] ?: continue
                for (drop in deque) {
                    if (remaining <= 0) break
                    if (drop.material != material || drop.expiresAt < now || drop.remaining <= 0) continue
                    val dx = drop.x - loc.x; val dy = drop.y - loc.y; val dz = drop.z - loc.z
                    if (dx * dx + dy * dy + dz * dz > dropMatchRadiusSq) continue
                    val take = minOf(drop.remaining, remaining)
                    drop.matchedAmount += take
                    remaining -= take
                    if (!matchedAny) { matchedAny = true; ctxAction = drop.sourceAction; ctx = drop.sourceContext }
                }
            }
        }

        if (!matchedAny) return null
        return DropMatch(excess = remaining, sourceAction = ctxAction, sourceContext = ctx)
    }

    private fun authorizeDrop(
        material: Material,
        amount: Int,
        loc: Location,
        sourcePlayer: UUID?,
        sourceAction: LedgerAction? = null,
        sourceContext: String? = null
    ) {
        if (amount <= 0) return
        val world = loc.world ?: return
        val key = chunkKey(world.name, loc.blockX, loc.blockZ)
        pruneExpiredDropsIn(key)
        authorizedDrops.getOrPut(key) { ConcurrentLinkedDeque() }.add(ExpectedDrop(
            material = material, expectedAmount = amount, matchedAmount = 0,
            worldName = world.name, x = loc.x, y = loc.y, z = loc.z,
            sourcePlayer = sourcePlayer,
            sourceAction = sourceAction,
            sourceContext = sourceContext,
            expiresAt = System.currentTimeMillis() + dropWindowMs
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
        // No FRAME_TAKE ledger entry — credit happens at pickup. Source context flows through
        // the expected drop so the upcoming PICKUP audit entry shows it came from a frame.
        authorizeDrop(
            material = mat, amount = amt, loc = frame.location, sourcePlayer = player.uniqueId,
            sourceAction = LedgerAction.FRAME_TAKE,
            sourceContext = "FRAME_TAKE:${frame.location.world?.name},${frame.location.blockX},${frame.location.blockY},${frame.location.blockZ}"
        )
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

        authorizeDrop(
            material = material, amount = amount, loc = frame.location, sourcePlayer = source,
            sourceAction = LedgerAction.FRAME_TAKE,
            sourceContext = "FRAME_BREAK:${frame.location.world?.name},${frame.location.blockX},${frame.location.blockY},${frame.location.blockZ}"
        )
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
