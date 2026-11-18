package com.esmpfun.antidupe.ledger

import com.esmpfun.antidupe.platform.PlatformScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.block.DoubleChest
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Gives a player the mark on items another plugin hands them (auction houses, shops, orders,
 * crates, graves, kits) and credits their record with them, so those items stack with the
 * player's own. Such plugins add items straight to the inventory, often a few ticks later after a
 * database round trip, so each sign of one starts a short watch of the inventory instead of a
 * same-tick diff.
 *
 * Only unmarked items and items with someone else's trusted mark are claimed, and the credit is
 * exactly what was claimed, so a claim never changes a player's surplus. Faked marks are left for
 * the forged-mark check.
 *
 * Items going the other way, into a plugin, are settled when they reach someone else: the
 * previous holder is debited then, unless the items were already counted as put away in the
 * world, as death drops kept in a grave are. Items a plugin keeps or hands back to the same player
 * never changed hands, so nothing is written for them. Items a shop buys up are debited from a
 * count before and after the sale.
 */
class PluginDeliveries(
    private val plugin: Plugin,
    private val ledgerStorage: LedgerStorage,
    private val ownershipManager: OwnershipManager,
    private val trackedMaterials: Set<Material>,
    private val settle: SettleTracker,
    private val scope: CoroutineScope,
    private val scheduler: PlatformScheduler,
    private val logger: Logger,
    private val worldStock: WorldStock?,
) : Listener {

    private class Watch {
        @Volatile var until = 0L
        @Volatile var source = ""
        /** A player's own items coming back out of a grave, per material. */
        val ownReturns = ConcurrentHashMap<Material, OwnReturn>()
    }

    /** Up to [expected] items may come back; [baseline] is what the player held of their own before. */
    private class OwnReturn(val expected: Int, val baseline: Int) {
        var credited = 0
    }

    /** Own tracked items each player held when a shop sale started, until the sale ends. */
    private val saleSnapshots = ConcurrentHashMap<UUID, Map<Material, Int>>()

    private val watches = ConcurrentHashMap<UUID, Watch>()

    /** Watches [player]'s inventory for a few seconds, crediting what arrives to [source]. */
    private fun watch(player: Player, source: String): Watch? {
        var started: Watch? = null
        val watch = watches.compute(player.uniqueId) { _, current ->
            (current ?: Watch().also { started = it }).also {
                it.until = System.currentTimeMillis() + WATCH_MS
                it.source = source
            }
        }
        started?.let { scheduler.runForEntityLater(player, FIRST_SCAN_TICKS, scan(player, it)) }
        return watch
    }

    private fun scan(player: Player, watch: Watch): Runnable = Runnable {
        if (!player.isOnline) { watches.remove(player.uniqueId, watch); return@Runnable }
        try {
            claim(player, watch.source)
            if (watch.ownReturns.isNotEmpty()) creditOwnReturns(player, watch)
        } catch (e: Exception) {
            logger.warning("[Ledger] could not mark items another plugin gave ${player.name}: ${e.message}")
            com.esmpfun.antidupe.util.ErrorReporter.report("plugin-delivery-claim", e)
        }
        val inMenu = isPluginMenu(player.openInventory.topInventory)
        val keep = watches.computeIfPresent(player.uniqueId) { _, current ->
            if (current !== watch) current
            else if (inMenu || System.currentTimeMillis() < current.until) current
            else null
        } === watch
        if (keep) scheduler.runForEntityLater(player, SCAN_TICKS, scan(player, watch))
    }

    private fun claim(player: Player, source: String) {
        if (player.gameMode == org.bukkit.GameMode.SPECTATOR) return
        val id = player.uniqueId
        val claimed = HashMap<Material, Int>()
        val released = HashMap<Pair<UUID, Material>, Int>()
        val inv = player.inventory
        val claimedSlots = ArrayList<Int>()
        for (slot in 0 until inv.size) {
            val stack = inv.getItem(slot) ?: continue
            if (claimStack(stack, id, claimed, released)) {
                inv.setItem(slot, stack)
                claimedSlots.add(slot)
            }
        }
        val cursor = player.itemOnCursor
        if (claimStack(cursor, id, claimed, released)) player.setItemOnCursor(cursor)
        if (claimed.isEmpty()) return
        mergeClaimed(player, claimedSlots)

        val base = LedgerMetadata.fromLocation(player.location).copy(notes = "PLUGIN_DELIVERY:$source")
        for ((material, amount) in claimed) {
            val from = released.keys.filter { it.second == material }.map { it.first }.distinct().singleOrNull()
            append(id, LedgerAction.RECEIVE, material, amount, if (from != null) base.withRelatedPlayer(from) else base)
        }
        for ((key, amount) in released) debitPreviousHolder(key.first, key.second, amount, base.withRelatedPlayer(id))
    }

    /**
     * The previous holder handed these to a plugin without a record of it. What they had put away
     * in the world (a chest, a death drop kept in a grave) was debited back then, so only the rest
     * is debited now. Before stored items are counted, nothing is debited: a missed debit only
     * hides a dupe, a double one would accuse someone.
     */
    private fun debitPreviousHolder(previous: UUID, material: Material, amount: Int, meta: LedgerMetadata) {
        val ws = worldStock ?: return
        settle.begin(previous)
        scope.launch {
            try {
                if (!ws.ready) return@launch
                val fromStorage = ws.drawDown(previous, material, amount)
                val debit = amount - fromStorage
                if (debit > 0) ledgerStorage.appendBuilt(previous, LedgerAction.GIVE, material, -debit, meta)
            } catch (e: Exception) {
                logger.warning("[Ledger] append failed: ${e.message}")
            } finally {
                settle.end(previous)
            }
        }
    }

    private fun append(player: UUID, action: LedgerAction, material: Material, quantity: Int, meta: LedgerMetadata) {
        settle.begin(player)
        scope.launch {
            try {
                ledgerStorage.appendBuilt(player, action, material, quantity, meta)
            } catch (e: Exception) {
                logger.warning("[Ledger] append failed: ${e.message}")
            } finally {
                settle.end(player)
            }
        }
    }

    /**
     * Credits a player's own items as they come back out of their grave, up to what the grave
     * held of theirs and what was put away when they died. Run from the watch, so a slow handover
     * is still seen.
     */
    private fun creditOwnReturns(player: Player, watch: Watch) {
        val id = player.uniqueId
        val now = ownCounts(player, watch.ownReturns.keys, includeMenu = false)
        val meta = LedgerMetadata.fromLocation(player.location).copy(notes = "PLUGIN_RETURN:${watch.source}")
        for ((material, ret) in watch.ownReturns) {
            val back = minOf((now[material] ?: 0) - ret.baseline, ret.expected) - ret.credited
            if (back <= 0) continue
            ret.credited += back
            val ws = worldStock ?: continue
            settle.begin(id)
            scope.launch {
                try {
                    if (!ws.ready) return@launch
                    val fromStorage = ws.drawDown(id, material, back)
                    if (fromStorage > 0) ledgerStorage.appendBuilt(id, LedgerAction.RECEIVE, material, fromStorage, meta)
                } catch (e: Exception) {
                    logger.warning("[Ledger] append failed: ${e.message}")
                } finally {
                    settle.end(id)
                }
            }
        }
    }

    /** The player's own tracked items, packed ones included, and those in an open plugin menu. */
    private fun ownCounts(player: Player, materials: Set<Material>?, includeMenu: Boolean): Map<Material, Int> {
        val id = player.uniqueId
        val sink = HashMap<Pair<UUID, Material>, Int>()
        player.inventory.contents.forEach { ownershipManager.tallyOwners(it, ::isTracked, sink) }
        ownershipManager.tallyOwners(player.itemOnCursor, ::isTracked, sink)
        if (includeMenu) {
            val top = player.openInventory.topInventory
            if (isPluginMenu(top)) top.contents.forEach { ownershipManager.tallyOwners(it, ::isTracked, sink) }
        }
        val out = HashMap<Material, Int>()
        for ((key, amount) in sink) {
            if (key.first == id && (materials == null || key.second in materials)) out.merge(key.second, amount, Int::plus)
        }
        return out
    }

    /**
     * Claims [stack] and anything tracked inside it, adding what became [owner]'s to [claimed]
     * and what someone else's mark gave up to [released].
     */
    private fun claimStack(
        stack: ItemStack, owner: UUID, claimed: MutableMap<Material, Int>,
        released: MutableMap<Pair<UUID, Material>, Int>
    ): Boolean {
        if (stack.type.isAir) return false
        val holder = mightHoldItems(stack.type)
        val topClaimable = stack.type in trackedMaterials && isClaimable(stack, owner)
        if (!topClaimable && !holder) return false

        val before = HashMap<Pair<UUID, Material>, Int>()
        ownershipManager.tallyOwners(stack, ::isTracked, before)
        var changed = false
        if (topClaimable) {
            ownershipManager.setOwner(stack, owner)
            changed = true
        }
        if (holder && ownershipManager.retagNested(stack, owner, ::isTracked).changed) changed = true
        if (!changed) return false

        val after = HashMap<Pair<UUID, Material>, Int>()
        ownershipManager.tallyOwners(stack, ::isTracked, after)
        for ((key, amount) in after) {
            if (key.first != owner) continue
            val gained = amount - (before[key] ?: 0)
            if (gained > 0) claimed.merge(key.second, gained, Int::plus)
        }
        for ((key, amount) in before) {
            if (key.first == owner) continue
            val lost = amount - (after[key] ?: 0)
            if (lost > 0) released.merge(key, lost, Int::plus)
        }
        return true
    }

    private fun isClaimable(stack: ItemStack, owner: UUID): Boolean {
        val mark = ownershipManager.readMark(stack)
        return mark.state == OwnershipManager.MarkState.NONE || (mark.trusted != null && mark.trusted != owner)
    }

    /** Tops up partial stacks in the three rows and the hotbar with what was just claimed. */
    private fun mergeClaimed(player: Player, claimedSlots: List<Int>) {
        val inv = player.inventory
        val storage = inv.storageContents.size
        for (from in claimedSlots) {
            if (from >= storage) continue
            val moving = inv.getItem(from) ?: continue
            for (to in 0 until storage) {
                if (to == from) continue
                val target = inv.getItem(to) ?: continue
                if (target.amount >= target.maxStackSize || !target.isSimilar(moving)) continue
                val moved = minOf(target.maxStackSize - target.amount, moving.amount)
                target.amount += moved
                moving.amount -= moved
                inv.setItem(to, target)
                if (moving.amount <= 0) break
            }
            inv.setItem(from, if (moving.amount > 0) moving else null)
        }
    }

    private fun isTracked(material: Material): Boolean = material in trackedMaterials

    private fun mightHoldItems(type: Material): Boolean =
        type.name.endsWith("SHULKER_BOX") || type.name.endsWith("BUNDLE") ||
            type == SulfurCubeAccess.bucketMaterial

    /**
     * A menu made by a plugin rather than a real chest, barrel, ender chest or storage entity.
     * The chest diff in [LedgerEventHandler] skips these, so nothing is counted twice.
     */
    private fun isPluginMenu(top: Inventory): Boolean {
        if (top.type !in MENU_TYPES || top.location != null) return false
        val holder = top.holder ?: return true
        return holder !is Container && holder !is DoubleChest && holder !is Entity && holder !is BlockInventoryHolder
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onMenuOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        if (isPluginMenu(event.inventory)) watch(player, menuSource(event.inventory))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onMenuClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val top = event.view.topInventory
        if (isPluginMenu(top)) watch(player, menuSource(top))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onMenuDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val top = event.view.topInventory
        if (isPluginMenu(top)) watch(player, menuSource(top))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onMenuClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        if (isPluginMenu(event.inventory)) watch(player, menuSource(event.inventory))
    }

    /** Kits, claim commands and command-driven shops hand items out without a menu. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val label = event.message.removePrefix("/").substringBefore(' ').lowercase()
        if (label.isEmpty()) return
        watch(event.player, "command /$label")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        watches.remove(event.player.uniqueId)
        saleSnapshots.remove(event.player.uniqueId)
    }

    private fun menuSource(top: Inventory): String {
        val holder = top.holder ?: return "menu"
        return pluginOf(holder.javaClass)?.let { "menu of $it" } ?: "menu"
    }

    private fun pluginOf(type: Class<*>): String? = try {
        org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(type).name
    } catch (e: Exception) {
        null
    }

    private val hookedEvents = ConcurrentHashMap.newKeySet<String>()

    /** Hooks the delivery plugins already running; the rest are hooked as they start. */
    fun hookRunningPlugins() {
        for (other in plugin.server.pluginManager.plugins) if (other.isEnabled) hook(other)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginEnable(event: org.bukkit.event.server.PluginEnableEvent) = hook(event.plugin)

    /**
     * Listens to another plugin's own delivery events, by class name, so none of them is needed
     * at build time. Covers paths without a menu, such as crate rewards and punching a grave.
     */
    private fun hook(owner: Plugin) {
        var any = false
        for (known in KNOWN_EVENTS) {
            if (known.plugin != owner.name || !hookedEvents.add(known.eventClass)) continue
            val type = try {
                Class.forName(known.eventClass, false, owner.javaClass.classLoader).asSubclass(Event::class.java)
            } catch (e: Throwable) {
                logger.fine("[Ledger] ${owner.name} has no ${known.eventClass}: ${e.message}")
                continue
            }
            val getter = try { type.getMethod(known.playerGetter) } catch (e: NoSuchMethodException) {
                logger.fine("[Ledger] ${known.eventClass} has no ${known.playerGetter}()")
                continue
            }
            val executor = EventExecutor { _, event ->
                if (!type.isInstance(event)) return@EventExecutor
                val player = try { getter.invoke(event) as? Player } catch (e: Exception) { null } ?: return@EventExecutor
                try {
                    when (known.role) {
                        Role.WATCH -> watch(player, owner.name)
                        Role.SALE_START -> saleStarted(player)
                        Role.SALE_END -> saleEnded(player, owner.name)
                        Role.GRAVE -> graveOpened(player, event, owner.name)
                    }
                } catch (e: Exception) {
                    logger.warning("[Ledger] could not follow a ${owner.name} event for ${player.name}: ${e.message}")
                    com.esmpfun.antidupe.util.ErrorReporter.report("plugin-delivery-event", e)
                }
            }
            try {
                // Cancelled sales never happen, so their start is skipped; nothing else is cancellable here.
                plugin.server.pluginManager.registerEvent(type, this, EventPriority.MONITOR, executor, plugin, true)
                any = true
            } catch (e: Throwable) {
                logger.fine("[Ledger] could not listen to ${known.eventClass}: ${e.message}")
            }
        }
        if (any) logger.info("[Ledger] Items players get from ${owner.name} are given their owner mark, so they stack with their own.")
    }

    /** A shop sale removes the items between its two events, in the same tick. */
    private fun saleStarted(player: Player) {
        saleSnapshots[player.uniqueId] = ownCounts(player, null, includeMenu = true)
    }

    private fun saleEnded(player: Player, source: String) {
        val before = saleSnapshots.remove(player.uniqueId) ?: return
        val after = ownCounts(player, before.keys, includeMenu = true)
        val meta = LedgerMetadata.fromLocation(player.location).copy(notes = "PLUGIN_SALE:$source")
        for ((material, had) in before) {
            val sold = had - (after[material] ?: 0)
            if (sold > 0) append(player.uniqueId, LedgerAction.GIVE, material, -sold, meta)
        }
        watch(player, source)
    }

    /** When players open their own grave, their own items in it may be credited as they come back. */
    private fun graveOpened(player: Player, event: Event, source: String) {
        val watch = watch(player, source) ?: return
        val grave = event.javaClass.getMethod("getGrave").invoke(event) ?: return
        val owner = (grave.javaClass.getMethod("getPlayer").invoke(grave) as? org.bukkit.OfflinePlayer)?.uniqueId
        if (owner != player.uniqueId) return
        val gui = grave.javaClass.getMethod("getGui").invoke(grave) as? Inventory ?: return
        val sink = HashMap<Pair<UUID, Material>, Int>()
        gui.contents.forEach { ownershipManager.tallyOwners(it, ::isTracked, sink) }
        val inGrave = sink.filterKeys { it.first == owner }.mapKeys { it.key.second }
        if (inGrave.isEmpty()) return
        val held = ownCounts(player, inGrave.keys, includeMenu = false)
        for ((material, amount) in inGrave) {
            watch.ownReturns.putIfAbsent(material, OwnReturn(amount, held[material] ?: 0))
        }
    }

    private enum class Role { WATCH, SALE_START, SALE_END, GRAVE }

    private class KnownEvent(
        val plugin: String, val eventClass: String, val playerGetter: String, val role: Role = Role.WATCH
    )

    private companion object {
        /** How long a watch lasts after the last sign of a delivery; covers slow database saves. */
        const val WATCH_MS = 10_000L
        const val FIRST_SCAN_TICKS = 1L
        /** 10 ticks = half a second. */
        const val SCAN_TICKS = 10L

        val MENU_TYPES = setOf(InventoryType.CHEST, InventoryType.HOPPER, InventoryType.DISPENSER, InventoryType.DROPPER)

        val KNOWN_EVENTS = listOf(
            KnownEvent("zAuctionHouse", "fr.maxlego08.zauctionhouse.api.event.events.stats.AuctionItemPurchasedEvent", "getBuyer"),
            KnownEvent("zAuctionHouse", "fr.maxlego08.zauctionhouse.api.event.events.remove.AuctionRemovePurchasedItemEvent", "getPlayer"),
            KnownEvent("zAuctionHouse", "fr.maxlego08.zauctionhouse.api.event.events.remove.AuctionRemoveExpiredItemEvent", "getPlayer"),
            KnownEvent("zAuctionHouse", "fr.maxlego08.zauctionhouse.api.event.events.remove.AuctionRemoveListedItemEvent", "getPlayer"),
            KnownEvent("PhoenixCratesLite", "com.phoenixplugins.phoenixcrates.api.events.items.ItemDeliveryEvent", "getPlayer"),
            KnownEvent("PhoenixCrates", "com.phoenixplugins.phoenixcrates.api.events.items.ItemDeliveryEvent", "getPlayer"),
            KnownEvent("AxGraves", "com.artillexstudios.axgraves.api.events.GraveInteractEvent", "getOpener", Role.GRAVE),
            KnownEvent("AxGraves", "com.artillexstudios.axgraves.api.events.GraveOpenEvent", "getOpener", Role.GRAVE),
            KnownEvent("EconomyShopGUI", "me.gypopo.economyshopgui.api.events.PreTransactionEvent", "getPlayer", Role.SALE_START),
            KnownEvent("EconomyShopGUI", "me.gypopo.economyshopgui.api.events.PostTransactionEvent", "getPlayer", Role.SALE_END),
            KnownEvent("EconomyShopGUI-Premium", "me.gypopo.economyshopgui.api.events.PreTransactionEvent", "getPlayer", Role.SALE_START),
            KnownEvent("EconomyShopGUI-Premium", "me.gypopo.economyshopgui.api.events.PostTransactionEvent", "getPlayer", Role.SALE_END),
        )
    }
}
