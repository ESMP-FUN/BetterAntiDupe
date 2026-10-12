package com.esmpfun.antidupe

import com.esmpfun.antidupe.ledger.AlertType
import com.esmpfun.antidupe.ledger.DupeAlert
import com.esmpfun.antidupe.ledger.Severity
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Catches a client that hid a menu without telling the server ("close without packet"), the
 * first step of most plugin menu dupes: the server keeps the menu open while the player acts in
 * the world. A vanilla client sends the close packet before it can do any of the actions below,
 * so the action is cancelled and the menu closed server-side, as if the client had closed it.
 *
 * Only actions that arrive straight from a client packet count. Plugins fire fake place, break
 * and interact events for protection checks, and menu plugins run commands through
 * `Player.chat`, both while a menu is legitimately open.
 */
class HiddenMenuGuard(
    private val logger: Logger,
    private val alert: (DupeAlert) -> Unit
) : Listener {

    private val openedAt = ConcurrentHashMap<UUID, Long>()
    private val lastAlertAt = ConcurrentHashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        openedAt[event.player.uniqueId] = System.currentTimeMillis()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        openedAt.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        openedAt.remove(event.player.uniqueId)
        lastAlertAt.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action == Action.PHYSICAL) return
        check(event.player, event, if (event.action.isLeftClick) "hit" else "use")
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) = check(event.player, event, "use")

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onAttack(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        check(player, event, "hit")
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) = check(event.player, event, "break")

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) = check(event.player, event, "place")

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) = check(event.player, event, "drop")

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onHotbar(event: PlayerItemHeldEvent) = check(event.player, event, "hotbar")

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) = check(event.player, event, "swap")

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) = check(event.player, event, "command")

    private fun check(player: Player, event: Cancellable, action: String) {
        val since = openedAt[player.uniqueId] ?: return
        val menu = player.openInventory.topInventory.type
        if (menu == InventoryType.CRAFTING || menu == InventoryType.CREATIVE) {
            openedAt.remove(player.uniqueId, since)
            return
        }
        val now = System.currentTimeMillis()
        if (now - since < graceMs(player.ping)) return
        if (!fromClientPacket()) return

        event.isCancelled = true
        player.closeInventory()
        openedAt.remove(player.uniqueId)
        com.esmpfun.antidupe.metrics.DetectionCounters.recordPreventionBlock("hidden_menu")

        val last = lastAlertAt[player.uniqueId]
        if (last != null && now - last < ALERT_EVERY_MS) return
        lastAlertAt[player.uniqueId] = now
        val held = player.inventory.itemInMainHand.type
        val doing = CONSOLE_ACTIONS[action] ?: action
        logger.warning("[DuperPrevention] ${player.name} tried to $doing while a ${menu.name} menu was still " +
            "open on the server. Their game hid the menu without closing it, which is how most plugin menu " +
            "dupes start. The action was stopped and the menu closed.")
        alert(DupeAlert(
            type = AlertType.HIDDEN_MENU,
            player = player.uniqueId,
            playerName = player.name,
            material = held,
            details = "Tried to $doing while a ${menu.name} menu was hidden but still open on the server",
            severity = Severity.HIGH,
            timestamp = now,
            messageKey = "alerts.hidden-menu",
            placeholders = mapOf(
                "action" to com.esmpfun.antidupe.util.Messages.msg("alerts.hidden-menu-actions.$action"),
                "menu" to menu.name
            )
        ))
    }

    companion object {
        private const val PACKET_LISTENER = "net.minecraft.server.network.ServerGamePacketListenerImpl"

        /** Client packets a vanilla client cannot send while a menu screen is open. */
        private val WORLD_ACTION_HANDLERS = setOf(
            "handleUseItemOn", "handleUseItem", "handleInteract", "handleAttack", "handlePunch",
            "handleAnimate", "handlePlayerAction", "handleSetCarriedItem", "handlePickItemFromBlock",
            "handlePickItemFromEntity", "performUnsignedChatCommand", "performSignedChatCommand"
        )

        /** Console lines stay English whatever the server language. */
        private val CONSOLE_ACTIONS = mapOf(
            "use" to "use an item or block", "hit" to "hit something", "break" to "break a block",
            "place" to "place a block", "drop" to "drop an item", "hotbar" to "switch hotbar slot",
            "swap" to "swap hands", "command" to "run a command"
        )

        private const val BASE_GRACE_MS = 1_000L
        private const val MAX_GRACE_MS = 5_000L
        private const val ALERT_EVERY_MS = 30_000L

        /**
         * Packets the client sent before it saw the menu open are legitimate, so the window
         * covers a round trip on top of a fixed margin.
         */
        fun graceMs(pingMs: Int): Long = (BASE_GRACE_MS + 2L * pingMs.coerceAtLeast(0)).coerceAtMost(MAX_GRACE_MS)

        /**
         * Paper names are Mojang names since 1.20.5. On a server with other names this never
         * matches, so the guard stays silent rather than guessing.
         */
        private fun fromClientPacket(): Boolean = StackWalker.getInstance().walk { frames ->
            frames.anyMatch { it.className == PACKET_LISTENER && it.methodName in WORLD_ACTION_HANDLERS }
        }
    }
}
