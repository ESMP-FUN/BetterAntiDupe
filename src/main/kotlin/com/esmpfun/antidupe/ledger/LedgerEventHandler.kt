package com.esmpfun.antidupe.ledger

import com.esmpfun.antidupe.platform.PlatformScheduler
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
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BundleMeta
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
    private val scheduler: PlatformScheduler,
    private val reconcileOnPickup: Boolean = true,
    private val reconcileOnInventoryClose: Boolean = false,
    private val flagSuspiciousPatterns: Boolean = true,
    private val hopperMode: HopperMode = HopperMode.LOG,
    private val blockCollectToCursor: Boolean = false
) : Listener {

    enum class HopperMode {
        OFF,
        LOG,
        BLOCK
    }

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

    private fun mightHoldItems(type: Material): Boolean =
        type.name.endsWith("SHULKER_BOX") || type.name.endsWith("BUNDLE") ||
        type == SulfurCubeAccess.bucketMaterial

    /**
     * Tracked materials stored inside an item, recursively. The deep inventory scan counts these
     * as the holder's possessions, so every ledger move of the outer item must move them too.
     */
    private fun containedTracked(stack: ItemStack, depth: Int = 0): Map<Material, Int> {
        if (depth >= 8 || !mightHoldItems(stack.type)) return emptyMap()
        val meta = stack.itemMeta ?: return emptyMap()
        val counts = HashMap<Material, Int>()
        fun addInner(inner: ItemStack?) {
            if (inner == null || inner.type == Material.AIR) return
            if (isTracked(inner.type)) counts.merge(inner.type, inner.amount, Int::plus)
            for ((m, c) in containedTracked(inner, depth + 1)) counts.merge(m, c, Int::plus)
        }
        if (meta is BlockStateMeta && meta.hasBlockState()) {
            (meta.blockState as? Container)?.inventory?.contents?.forEach { addInner(it) }
        }
        if (meta is BundleMeta) meta.items.forEach { addInner(it) }
        addInner(SulfurCubeAccess.absorbedItem(stack, logger))
        return counts
    }

    private fun appendContents(
        player: UUID, action: LedgerAction,
        contents: Map<Material, Int>, sign: Int, meta: LedgerMetadata
    ) {
        for ((material, count) in contents) appendAsync(player, action, material, sign * count, meta)
    }

    /**
     * BlockDropItemEvent rather than BlockBreakEvent plus `block.getDrops(tool)`: getDrops rolls
     * fresh random loot that can disagree with what actually dropped, and the stacks it returns
     * are detached copies, so tagging them never reaches the real item entities.
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

            // No MINE entry on purpose: the credit happens at pickup, which inherits the
            // source context through the expected drop.
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

        // Tag the computed result, never a clone of event.recipe.result: recipes that build the
        // result dynamically copy data onto it that the static one lacks, and the shulker recolor
        // recipe carries the input's contents that way. Overwriting with recipe.result loses them.
        val actual = event.currentItem ?: event.recipe.result
        if (!isTracked(actual.type)) return

        val amount = if (event.isShiftClick) calculateShiftCraftAmount(event) else actual.amount

        val taggedResult = actual.clone()
        ownershipManager.setOwner(taggedResult, player.uniqueId)
        event.currentItem = taggedResult

        val meta = witnessedMetadata(
            player, LedgerAction.CRAFT,
            LedgerMetadata.fromLocation(player.location),
            "${amount}x${actual.type.name}"
        )
        appendAsync(player.uniqueId, LedgerAction.CRAFT, actual.type, amount, meta)

        val perCraft = actual.amount.coerceAtLeast(1)
        val crafts = if (event.isShiftClick) (amount / perCraft).coerceAtLeast(1) else 1
        debitCraftIngredients(player, event, crafts)

        val craftedType = actual.type
        scope.launch {
            val tmar = reconciliationEngine.checkTmar(player, craftedType, amount)
            if (!tmar.allowed) {
                logger.warning("[TMAR] ${player.name} exceeded $craftedType: ${tmar.projectedRate}/${tmar.limit}")
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
        // getItemStack can hand back a detached copy, so write the tagged stack back.
        event.item.itemStack = item

        pendingDiffs[player.uniqueId]?.let { pending ->
            val arriving = item.amount - event.remaining
            if (arriving > 0) pending.pickedUp.merge(item.type, arriving, Int::plus)
            if (event.remaining == 0) {
                for ((m, c) in containedTracked(item)) pending.pickedUp.merge(m, c, Int::plus)
            }
        }

        // Deferred a tick because a plugin at a later priority can cancel the pickup, after which
        // the item survives in the player's hitbox and the event re-fires every tick. Acting at
        // event time would credit items that never arrived and flag every re-fire as a dupe.
        val baseMeta = meta
        val capturedPlayerId = player.uniqueId
        val capturedMaterial = item.type
        val capturedAmount = item.amount
        val capturedContents = containedTracked(item)
        val itemEntity = event.item
        val entityUuid = itemEntity.uniqueId
        val pickupLoc = itemEntity.location.clone()
        val checkAfterCredit = reconcileOnPickup && previousOwner != player.uniqueId

        scheduler.runForEntityLater(itemEntity, 1, Runnable {
            val survived = itemEntity.isValid
            val amountAfter = if (survived) itemEntity.itemStack.amount else 0
            val consumed = capturedAmount - amountAfter
            if (consumed <= 0) return@Runnable

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
                deferredMeta = deferredMeta.copy(notes = listOfNotNull(deferredMeta.notes, match.sourceContext).joinToString("|").ifBlank { null })
            }
            val finalMeta = deferredMeta

            scope.launch {
                // A partial pickup leaves the remainder on the ground under the same entity UUID,
                // so the UUID only counts as consumed once the entity is gone.
                val prev = if (!survived) {
                    try {
                        ledgerStorage.markEntityPickup(entityUuid, capturedPlayerId, capturedMaterial, consumed)
                    } catch (e: Exception) {
                        logger.warning("[Ledger] markEntityPickup failed: ${e.message}")
                        null
                    }
                } else null

                if (prev != null) {
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
                if (capturedContents.isNotEmpty()) {
                    val contentsMeta = finalMeta.copy(notes = listOfNotNull(finalMeta.notes, "CONTENTS_OF:$capturedMaterial").joinToString("|"))
                    for ((material, count) in capturedContents) {
                        try {
                            ledgerStorage.appendBuilt(capturedPlayerId, LedgerAction.PICKUP, material, count, contentsMeta)
                        } catch (e: Exception) {
                            logger.warning("[Ledger] PICKUP append failed: ${e.message}")
                        }
                    }
                }
                // Only once the credit is written: checking at event time sees the tagged item
                // but not yet the credit, and alerts on every pickup above the threshold.
                if (checkAfterCredit && player.isOnline) reconciliationEngine.reconcileAsync(player)
            }
        })

        if (flagSuspiciousPatterns && witnessManager.othersNearby(player)) {
            val pattern = witnessManager.hasSuspiciousPattern(player.uniqueId)
            if (pattern.suspicious) {
                logger.fine("[PoW] Pattern signal for ${player.name}: ${pattern.reason}")
                reconciliationEngine.flagWitnessPattern(player.uniqueId)
            }
        }

    }

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (shouldSkip(player)) return
        val item = event.itemInHand
        if (!isTracked(item.type)) return

        val block = event.blockPlaced
        val placedType = block.type
        val material = item.type
        val playerId = player.uniqueId
        val contents = containedTracked(item)
        scheduler.runForEntityLater(player, 1, Runnable {
            if (block.type != placedType) return@Runnable
            val meta = LedgerMetadata.fromLocation(block.location)
            appendAsync(playerId, LedgerAction.PLACE, material, -1, meta)
            if (contents.isNotEmpty()) {
                appendContents(playerId, LedgerAction.PLACE, contents, -1,
                    meta.copy(notes = "CONTENTS_OF:$material"))
            }
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        val player = event.player
        if (shouldSkip(player)) return
        val item = event.itemDrop.itemStack
        if (!isTracked(item.type)) return

        val dropEntity = event.itemDrop
        val material = item.type
        val amount = item.amount
        val playerId = player.uniqueId
        val contents = containedTracked(item)
        pendingDiffs[playerId]?.let { pending ->
            pending.dropped.merge(material, amount, Int::plus)
            for ((m, c) in contents) pending.dropped.merge(m, c, Int::plus)
        }
        scheduler.runForEntityLater(dropEntity, 1, Runnable {
            if (!dropEntity.isValid) return@Runnable
            val meta = LedgerMetadata.fromLocation(dropEntity.location)
            appendAsync(playerId, LedgerAction.DROP, material, -amount, meta)
            if (contents.isNotEmpty()) {
                appendContents(playerId, LedgerAction.DROP, contents, -1,
                    meta.copy(notes = "CONTENTS_OF:$material"))
            }
        })
    }

    /**
     * PlayerDeathEvent extends EntityDeathEvent, so [onEntityDeath] has already authorized these
     * drops; this only adds the debit that keeps re-collecting them net zero.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerDeath(event: org.bukkit.event.entity.PlayerDeathEvent) {
        val player = event.entity
        if (shouldSkip(player)) return
        val meta = LedgerMetadata.fromLocation(player.location).copy(notes = "DEATH_DROP")
        for (drop in event.drops) {
            if (isTracked(drop.type)) {
                appendAsync(player.uniqueId, LedgerAction.DROP, drop.type, -drop.amount, meta)
            }
            val contents = containedTracked(drop)
            if (contents.isNotEmpty()) {
                appendContents(player.uniqueId, LedgerAction.DROP, contents, -1,
                    meta.copy(notes = "DEATH_DROP|CONTENTS_OF:${drop.type}"))
            }
        }
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

    /**
     * Container transfers are recorded by snapshot-diff of the clicking player's own inventory and
     * cursor, not of the container: several players, hoppers and copper golems can change a
     * container in the same tick, but only this player changes their own side. Plugin GUIs (null
     * or custom holders) are deliberately not classified, because their item flow is
     * plugin-internal and a shop plugin declares grants through [ChainOfCustody.recordSystemGrant].
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (shouldSkip(player)) return

        val topInv = event.view.topInventory

        val stationResult = STATION_RESULT_SLOTS[topInv.type]
        if (stationResult != null) {
            handleStationClick(player, event, topInv.type, stationResult)
            return
        }

        val target = classifyTopInventory(topInv) ?: return

        val materials = HashSet<Material>()
        fun consider(stack: ItemStack?) {
            if (stack == null || stack.type == Material.AIR) return
            if (isTracked(stack.type)) materials.add(stack.type)
            materials.addAll(containedTracked(stack).keys)
        }
        consider(event.currentItem)
        consider(event.cursor)
        if (event.click == ClickType.NUMBER_KEY && event.hotbarButton >= 0) {
            consider(player.inventory.getItem(event.hotbarButton))
        }
        if (event.click == ClickType.SWAP_OFFHAND) {
            consider(player.inventory.itemInOffHand)
        }
        if (materials.isEmpty()) return

        scheduleContainerDiff(player, topInv, target, materials)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (shouldSkip(player)) return

        val topInv = event.view.topInventory
        val target = classifyTopInventory(topInv) ?: return
        if (event.rawSlots.none { it < topInv.size }) return

        val mat = event.oldCursor.type
        if (mat == Material.AIR) return
        val materials = HashSet<Material>()
        if (isTracked(mat)) materials.add(mat)
        materials.addAll(containedTracked(event.oldCursor).keys)
        if (materials.isEmpty()) return
        scheduleContainerDiff(player, topInv, target, materials)
    }

    /**
     * Pickups and drops during the window move the player's side without touching the container,
     * so they are subtracted back out. A drop from a container slot (Q over the chest) counts as a
     * take this way, which is right: the drop debit and a later pickup then net to zero.
     */
    private class PendingContainerDiff(
        val inventory: Inventory,
        val target: StorageTarget
    ) {
        val playerPre = ConcurrentHashMap<Material, Int>()
        /** Top-level stacks not carrying the player's tag; a swap moves tags without moving counts. */
        val foreignPre = ConcurrentHashMap<Material, Int>()
        /** The container's count at every click in the window, summed: nobody can take more. */
        val takeCeiling = ConcurrentHashMap<Material, Int>()
        val pickedUp = ConcurrentHashMap<Material, Int>()
        val dropped = ConcurrentHashMap<Material, Int>()
    }

    private val pendingDiffs = ConcurrentHashMap<UUID, PendingContainerDiff>()

    private fun countInInventory(inv: Inventory, material: Material): Int {
        var total = 0
        for (stack in inv.contents) {
            if (stack == null) continue
            if (stack.type == material) total += stack.amount
            if (mightHoldItems(stack.type)) total += containedTracked(stack)[material] ?: 0
        }
        return total
    }

    private fun countPlayerSide(player: Player, material: Material): Int {
        var total = countInInventory(player.inventory, material)
        val cursor = player.itemOnCursor
        if (cursor.type == material) total += cursor.amount
        if (mightHoldItems(cursor.type)) total += containedTracked(cursor)[material] ?: 0
        return total
    }

    private fun countForeignPlayerSide(player: Player, material: Material): Int {
        val id = player.uniqueId
        var total = 0
        for (stack in player.inventory.storageContents) {
            if (stack != null && stack.type == material && ownershipManager.getOwner(stack) != id) total += stack.amount
        }
        val cursor = player.itemOnCursor
        if (cursor.type == material && ownershipManager.getOwner(cursor) != id) total += cursor.amount
        return total
    }

    private fun scheduleContainerDiff(player: Player, inv: Inventory, target: StorageTarget, materials: Set<Material>) {
        val existing = pendingDiffs[player.uniqueId]
        val pending = if (existing != null && existing.inventory === inv) existing else {
            val fresh = PendingContainerDiff(inv, target)
            pendingDiffs[player.uniqueId] = fresh
            scheduler.runForEntityLater(player, 1, Runnable { settleContainerDiff(player, fresh) })
            fresh
        }
        // Each click is applied after this MONITOR handler returns, so these are pre-click counts.
        for (mat in materials) {
            if (pending.playerPre.putIfAbsent(mat, countPlayerSide(player, mat)) == null) {
                pending.foreignPre[mat] = countForeignPlayerSide(player, mat)
            }
            pending.takeCeiling.merge(mat, countInInventory(inv, mat), Int::plus)
        }
    }

    private fun settleContainerDiff(player: Player, pending: PendingContainerDiff) {
        pendingDiffs.remove(player.uniqueId, pending)
        for ((mat, pre) in pending.playerPre) {
            val delta = countPlayerSide(player, mat) - pre
            val moved = delta - (pending.pickedUp[mat] ?: 0) + (pending.dropped[mat] ?: 0)
            val ceiling = pending.takeCeiling[mat] ?: 0
            val taken = if (moved > 0) minOf(moved, ceiling) else 0
            // Swapping a stack for an equal one from the container leaves the count unchanged but
            // hands the player someone else's tag, so retag what arrived even when nothing is credited.
            val foreignArrived = countForeignPlayerSide(player, mat) - (pending.foreignPre[mat] ?: 0)
            val retagBudget = minOf(maxOf(taken, foreignArrived), ceiling)
            val previousOwner = if (retagBudget > 0) retagTaken(player, mat, retagBudget) else null
            when {
                taken > 0 -> recordTake(player, mat, taken, pending.target, previousOwner)
                moved < 0 -> recordPut(player, mat, -moved, pending.target)
            }
        }
    }

    /**
     * Gives the taker's tag to up to [amount] of [material] they hold under someone else's tag or
     * none, so balance checks count what they were just credited for. Only whole stacks are
     * retagged; an oversized stack is split into a free slot, or left alone when there is none.
     * Items nested in a shulker box or bundle keep their tag. Returns one previous owner, if any.
     */
    private fun retagTaken(player: Player, material: Material, amount: Int): UUID? {
        val id = player.uniqueId
        var remaining = amount
        var previous: UUID? = null

        val cursor = player.itemOnCursor
        if (cursor.type == material && cursor.amount <= remaining) {
            val owner = ownershipManager.getOwner(cursor)
            if (owner != id) {
                ownershipManager.setOwner(cursor, id)
                player.setItemOnCursor(cursor)
                remaining -= cursor.amount
                previous = owner
            }
        }

        val inv = player.inventory
        for (slot in inv.storageContents.indices) {
            if (remaining <= 0) break
            val stack = inv.getItem(slot) ?: continue
            if (stack.type != material) continue
            val owner = ownershipManager.getOwner(stack)
            if (owner == id) continue
            if (stack.amount <= remaining) {
                ownershipManager.setOwner(stack, id)
                inv.setItem(slot, stack)
                remaining -= stack.amount
            } else {
                val free = inv.firstEmpty()
                if (free < 0) continue
                val split = stack.clone().also { it.amount = remaining }
                ownershipManager.setOwner(split, id)
                stack.amount -= remaining
                inv.setItem(slot, stack)
                inv.setItem(free, split)
                remaining = 0
            }
            if (previous == null) previous = owner
        }
        if (remaining > 0) {
            logger.fine("[Ledger] ${player.name}: $remaining x $material taken could not be retagged")
        }
        return previous
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
        // A double chest's combined inventory is held by DoubleChest, an InventoryHolder that is
        // not a Container, so it needs its own branch.
        if (holder is DoubleChest) return DoubleChestTarget(holder)
        val asEntity = holder as? Entity ?: return null
        if (asEntity is Player) return null
        // Only the storage and hopper minecart subtypes implement InventoryHolder, so the broad
        // Minecart interface is no good here. ChestBoat extends Boat, so `is Boat` covers it.
        val isStorage = asEntity is AbstractHorse || asEntity is Boat ||
            asEntity is StorageMinecart || asEntity is HopperMinecart
        if (!isStorage) return null
        return EntityContainerTarget(asEntity)
    }

    private companion object {
        private const val HOPPER_LOG_WINDOW_MS = 60_000L

        /** Result-slot index per station; every slot below it is an input slot. */
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
        if (event.rawSlot != resultSlot) return
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

        debitStationInputs(player, event.view.topInventory, type, resultSlot)
    }

    private fun debitStationInputs(player: Player, top: Inventory, type: InventoryType, resultSlot: Int) {
        val watched = HashSet<Material>()
        for (slot in 0 until minOf(resultSlot, top.size)) {
            val stack = top.getItem(slot) ?: continue
            if (isTracked(stack.type)) watched.add(stack.type)
            watched.addAll(containedTracked(stack).keys)
        }
        if (watched.isEmpty()) return

        val before = watched.associateWith { countInSlots(top, it, resultSlot) }
        scheduler.runForEntityLater(player, 1, Runnable {
            for ((material, pre) in before) {
                val consumed = pre - countInSlots(top, material, resultSlot)
                if (consumed <= 0) continue
                val meta = LedgerMetadata.fromLocation(player.location)
                    .copy(containerType = type.name, notes = "STATION_INPUT:${type.name}")
                appendAsync(player.uniqueId, LedgerAction.CONSUME, material, -consumed, meta)
            }
        })
    }

    private fun countInSlots(inv: Inventory, material: Material, endExclusive: Int): Int {
        var total = 0
        for (slot in 0 until minOf(endExclusive, inv.size)) {
            val stack = inv.getItem(slot) ?: continue
            if (stack.type == material) total += stack.amount
            if (mightHoldItems(stack.type)) total += containedTracked(stack)[material] ?: 0
        }
        return total
    }

    fun registerCollectBlocker() {
        if (!blockCollectToCursor) return
        plugin.server.pluginManager.registerEvents(CollectBlocker(), plugin)
        logger.info("[Ledger] Double-click gather is blocked for watched items")
    }

    private inner class CollectBlocker : Listener {
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        fun onCollect(event: InventoryClickEvent) {
            if (event.action != InventoryAction.COLLECT_TO_CURSOR) return
            val player = event.whoClicked as? Player ?: return
            if (shouldSkip(player)) return
            val gathered = event.cursor?.takeIf { it.type != Material.AIR } ?: event.currentItem ?: return
            if (!isTracked(gathered.type)) return
            event.isCancelled = true
        }
    }

    fun registerHopperListener() {
        val listener: Listener = when (hopperMode) {
            HopperMode.OFF -> return
            // Blocking runs at HIGH so other plugins still see the cancellation; logging runs at
            // MONITOR so it sees the settled outcome.
            HopperMode.BLOCK -> HopperBlockListener()
            HopperMode.LOG -> HopperLogListener()
        }
        plugin.server.pluginManager.registerEvents(listener, plugin)
        logger.info("[Ledger] Automated-transfer tracking: ${hopperMode.name}")
    }

    private val hopperTrail = ConcurrentHashMap<String, Long>()

    private inner class HopperBlockListener : Listener {
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        fun onMove(event: org.bukkit.event.inventory.InventoryMoveItemEvent) {
            if (!isTracked(event.item.type)) return
            event.isCancelled = true
        }
    }

    private inner class HopperLogListener : Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onMove(event: org.bukkit.event.inventory.InventoryMoveItemEvent) {
            val stack = event.item
            if (!isTracked(stack.type)) return

            val owner = ownershipManager.getOwner(stack) ?: return
            val destination = try { event.destination.location } catch (e: Exception) { null }
            val where = destination?.let { "${it.world?.name}:${it.blockX},${it.blockY},${it.blockZ}" } ?: "unknown"
            val key = "$owner|${stack.type.name}|$where"

            val now = System.currentTimeMillis()
            val last = hopperTrail[key]
            if (last != null && now - last < HOPPER_LOG_WINDOW_MS) return
            hopperTrail[key] = now

            // Quantity zero: the item moves between two containers, so no balance changes. The
            // entry exists only so the route shows up in the item's history.
            val meta = LedgerMetadata(notes = "AUTOMATED_TRANSFER:${stack.type.name}")
                .withContainer(event.destination.type.name, destination)
            appendAsync(owner, LedgerAction.TRANSFER, stack.type, 0, meta)
        }
    }

    fun pruneHopperTrail() {
        val cutoff = System.currentTimeMillis() - (HOPPER_LOG_WINDOW_MS * 2)
        hopperTrail.entries.removeIf { it.value < cutoff }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: org.bukkit.event.inventory.InventoryCloseEvent) {
        if (!reconcileOnInventoryClose) return
        val player = event.player as? Player ?: return
        if (shouldSkip(player)) return
        if (classifyTopInventory(event.view.topInventory) == null) return
        reconciliationEngine.reconcileAsync(player)
    }

    /**
     * Paper's PlayerTradeEvent fires once per completed trade, including each repeat of a
     * shift-click batch. Registered reflectively so the class still loads on Spigot.
     */
    fun registerPaperOnlyListeners() {
        try {
            Class.forName("io.papermc.paper.event.player.PlayerTradeEvent")
            plugin.server.pluginManager.registerEvents(PaperTradeListener(), plugin)
        } catch (e: ClassNotFoundException) {
            logger.info("[Ledger] PlayerTradeEvent unavailable (Spigot?) - villager trades won't be credited")
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        val player = event.enchanter
        if (shouldSkip(player)) return
        val before = event.item.type
        val after = if (before == Material.BOOK) Material.ENCHANTED_BOOK else before
        if (after == before) return

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFurnaceExtract(event: FurnaceExtractEvent) {
        val player = event.player
        if (shouldSkip(player)) return
        if (!isTracked(event.itemType)) return

        val meta = LedgerMetadata.fromLocation(event.block.location)
            .copy(containerType = event.block.type.name, notes = "FURNACE:${event.block.type.name}")
        appendAsync(player.uniqueId, LedgerAction.STATION_OUTPUT, event.itemType, event.itemAmount, meta)
    }

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
        if (current != null && current.type != Material.AIR && !current.isSimilar(held)) return

        val meta = LedgerMetadata.fromLocation(block.location).copy(containerType = "DECORATED_POT")
        appendAsync(player.uniqueId, LedgerAction.CONTAINER_PUT, held.type, -1, meta)
        val contents = containedTracked(held)
        if (contents.isNotEmpty()) {
            appendContents(player.uniqueId, LedgerAction.CONTAINER_PUT, contents, -1,
                meta.copy(notes = "CONTENTS_OF:${held.type}"))
        }
    }

    // Copper golem sorting is deliberately not handled: a golem only moves items between two
    // chests, never a player inventory, so no balance changes when it works.

    // Crafter automation is deliberately not handled: CrafterCraftEvent only exists on Paper
    // builds newer than we target, and items leaving a crafter are caught downstream anyway.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLecternTake(event: PlayerTakeLecternBookEvent) {
        val player = event.player
        if (shouldSkip(player)) return
        val item = event.book ?: return
        if (!isTracked(item.type)) return

        val meta = LedgerMetadata.fromLocation(event.lectern.location).copy(containerType = "LECTERN")
        appendAsync(player.uniqueId, LedgerAction.CONTAINER_TAKE, item.type, item.amount, meta)
    }

    /**
     * A shelf swaps items straight between the hand and the block without opening an inventory,
     * so no click event fires and the move has to be measured from the player's side. Uses
     * PlayerInteractEvent rather than a version-specific shelf event so one jar covers 1.21.9+.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShelfInteract(event: org.bukkit.event.player.PlayerInteractEvent) {
        if (event.action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (!block.type.name.endsWith("_SHELF")) return

        val player = event.player
        if (shouldSkip(player)) return
        val shelf = block.state as? org.bukkit.block.Shelf ?: return

        val materials = HashSet<Material>()
        fun consider(stack: ItemStack?) {
            if (stack == null || stack.type == Material.AIR) return
            if (isTracked(stack.type)) materials.add(stack.type)
            materials.addAll(containedTracked(stack).keys)
        }
        consider(player.inventory.itemInMainHand)
        shelf.inventory.contents.forEach { consider(it) }
        if (materials.isEmpty()) return

        val before = materials.associateWith { countInInventory(player.inventory, it) }
        val loc = block.location
        scheduler.runForEntityLater(player, 1, Runnable {
            for ((mat, pre) in before) {
                val delta = countInInventory(player.inventory, mat) - pre
                if (delta == 0) continue
                val meta = LedgerMetadata.fromLocation(player.location).withContainer("SHELF", loc)
                if (delta < 0) {
                    appendAsync(player.uniqueId, LedgerAction.CONTAINER_PUT, mat, delta, meta)
                } else {
                    val previousOwner = retagTaken(player, mat, delta)
                    val takeMeta = if (previousOwner != null) meta.withRelatedPlayer(previousOwner) else meta
                    appendAsync(player.uniqueId, LedgerAction.CONTAINER_TAKE, mat, delta, takeMeta)
                }
            }
        })
    }

    private fun recordTake(player: Player, mat: Material, qty: Int, target: StorageTarget, previousOwner: UUID?) {
        val action = if (target.isEntity) LedgerAction.ENTITY_TAKE else LedgerAction.CONTAINER_TAKE
        var meta = LedgerMetadata.fromLocation(player.location).withContainer(target.label, target.location)
        if (previousOwner != null && previousOwner != player.uniqueId) meta = meta.withRelatedPlayer(previousOwner)
        appendAsync(player.uniqueId, action, mat, qty, meta)
    }

    private fun recordPut(player: Player, mat: Material, qty: Int, target: StorageTarget) {
        val action = if (target.isEntity) LedgerAction.ENTITY_PUT else LedgerAction.CONTAINER_PUT
        val meta = LedgerMetadata.fromLocation(player.location).withContainer(target.label, target.location)
        appendAsync(player.uniqueId, action, mat, -qty, meta)
    }

    /**
     * A record that this much of this material may legitimately be acquired near here within a
     * time window. Pickups consume these, and whatever a pickup cannot match is the source-bound
     * excess: more items on the ground than any legitimate source produced.
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

    private val frameContents = ConcurrentHashMap<UUID, FrameContent>()

    private val flaggedDupeEntities = ConcurrentHashMap<UUID, Long>()
    private val dupeAlertSuppressMs = 60_000L

    private val authorizedDrops = ConcurrentHashMap<String, ConcurrentLinkedDeque<ExpectedDrop>>()

    // Deliberately generous: farm items travel by water stream or drop chute, so a tight radius
    // would false-flag honest collection. The quantity bound does the real work.
    private val dropMatchRadius = 24.0
    private val dropMatchRadiusSq = dropMatchRadius * dropMatchRadius
    private val dropChunkRadius = Math.ceil(dropMatchRadius / 16.0).toInt()
    private val dropWindowMs = 300_000L  // 5 minutes

    private fun chunkKey(worldName: String, blockX: Int, blockZ: Int): String =
        "$worldName:${blockX shr 4}:${blockZ shr 4}"

    /**
     * Full sweep from the maintenance loop. [pruneExpiredDropsIn] only touches a chunk when
     * something new is authorized there, so a chunk mined once would keep its deque forever.
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
        authorizedDrops.computeIfAbsent(key) { ConcurrentLinkedDeque() }.add(ExpectedDrop(
            material = material, expectedAmount = amount, matchedAmount = 0,
            worldName = world.name, x = loc.x, y = loc.y, z = loc.z,
            sourcePlayer = sourcePlayer,
            sourceAction = sourceAction,
            sourceContext = sourceContext,
            expiresAt = System.currentTimeMillis() + dropWindowMs
        ))
    }

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

        // A frame stores no stack count because exactly one item ever goes in.
        val meta = LedgerMetadata.fromLocation(frame.location)
            .copy(containerType = "ITEM_FRAME", containerLocation = "${frame.location.world?.name},${frame.location.blockX},${frame.location.blockY},${frame.location.blockZ}")
        appendAsync(player.uniqueId, LedgerAction.FRAME_PUT, held.type, -1, meta)
        val contents = containedTracked(held)
        if (contents.isNotEmpty()) {
            appendContents(player.uniqueId, LedgerAction.FRAME_PUT, contents, -1,
                meta.copy(notes = "CONTENTS_OF:${held.type}"))
        }

        frameContents[frame.uniqueId] = FrameContent(held.type, 1, player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFrameDamageByPlayer(event: EntityDamageByEntityEvent) {
        val frame = event.entity as? ItemFrame ?: return
        val player = event.damager as? Player ?: return
        if (shouldSkip(player)) return

        val item = frame.item
        if (item.type == Material.AIR || !isTracked(item.type)) return

        val mat = item.type
        val amt = item.amount.coerceAtLeast(1)
        authorizeDrop(
            material = mat, amount = amt, loc = frame.location, sourcePlayer = player.uniqueId,
            sourceAction = LedgerAction.FRAME_TAKE,
            sourceContext = "FRAME_TAKE:${frame.location.world?.name},${frame.location.blockX},${frame.location.blockY},${frame.location.blockZ}"
        )
        frameContents.remove(frame.uniqueId)
    }

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

    /**
     * A recipe uses one item from each occupied grid slot per craft, so a material sitting in
     * three slots costs three. At MONITOR the grid still holds its pre-craft contents.
     */
    private fun debitCraftIngredients(player: Player, event: CraftItemEvent, crafts: Int) {
        if (crafts <= 0) return
        val perMaterial = HashMap<Material, Int>()
        for (slot in event.inventory.matrix) {
            if (slot == null || slot.type == Material.AIR) continue
            if (!isTracked(slot.type)) continue
            perMaterial.merge(slot.type, 1, Int::plus)
        }
        if (perMaterial.isEmpty()) return

        val meta = LedgerMetadata.fromLocation(player.location)
            .copy(notes = "CRAFT_INGREDIENT")
        for ((material, slots) in perMaterial) {
            appendAsync(player.uniqueId, LedgerAction.CONSUME, material, -(slots * crafts), meta)
        }
    }

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
