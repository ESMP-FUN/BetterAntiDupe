package com.esmpfun.antidupe.integration

import com.esmpfun.antidupe.api.event.DupeAlertEvent
import com.esmpfun.antidupe.api.event.DupeBlockedEvent
import com.esmpfun.antidupe.api.event.DupeRemovalEvent
import com.esmpfun.antidupe.api.model.AlertKind
import com.esmpfun.antidupe.api.model.Protection
import com.esmpfun.antidupe.api.model.Severity as ApiSeverity
import com.esmpfun.antidupe.ledger.AlertType
import com.esmpfun.antidupe.ledger.DupeAlert
import com.esmpfun.antidupe.ledger.Severity
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player

/** Fires the public events. Each one skips building the event when nobody listens. */
object ApiEvents {

    /** True when another plugin asked the protection to step aside this once. */
    fun stepAside(protection: Protection, player: Player?, location: Location): Boolean {
        if (DupeBlockedEvent.getHandlerList().registeredListeners.isEmpty()) return false
        val event = DupeBlockedEvent(protection, player, location)
        Bukkit.getPluginManager().callEvent(event)
        return event.isCancelled
    }

    /** Must run off the main thread. Returns false when another plugin cancelled the alert. */
    fun alertAllowed(alert: DupeAlert): Boolean {
        if (DupeAlertEvent.getHandlerList().registeredListeners.isEmpty()) return true
        val event = DupeAlertEvent(
            alert.player, alert.playerName, alert.material, kindOf(alert), alert.severity.toApi(),
            alert.excess, alert.details, alert.afterUncleanShutdown
        )
        Bukkit.getPluginManager().callEvent(event)
        return !event.isCancelled
    }

    /** Runs on the player's thread. Returns false when another plugin cancelled the removal. */
    fun removalAllowed(player: Player, material: Material, amount: Int, severity: Severity): Boolean {
        if (DupeRemovalEvent.getHandlerList().registeredListeners.isEmpty()) return true
        val event = DupeRemovalEvent(player, material, amount, severity.toApi())
        Bukkit.getPluginManager().callEvent(event)
        return !event.isCancelled
    }

    fun kindOf(alert: DupeAlert): AlertKind = when (alert.messageKey) {
        "alerts.entity-dupe" -> AlertKind.SAME_ITEM_TWICE
        "alerts.drop-excess" -> AlertKind.PICKUP_BEYOND_SOURCE
        "alerts.washed-surplus" -> AlertKind.STORED_AWAY_EXTRA
        "alerts.world-stock" -> AlertKind.STORAGE_SHORTFALL
        "alerts.left-creative" -> AlertKind.LEFT_CREATIVE
        else -> when (alert.type) {
            AlertType.TMAR_EXCEEDED -> AlertKind.RATE_LIMIT
            AlertType.FOREIGN_ITEM, AlertType.ORPHAN_ITEM -> AlertKind.FOREIGN_ITEM
            AlertType.CHAIN_INTEGRITY -> AlertKind.RECORD_TAMPERED
            AlertType.HIDDEN_MENU -> AlertKind.HIDDEN_MENU
            AlertType.REVERTED_PLACEMENT -> AlertKind.REVERTED_PLACEMENT
            AlertType.BALANCE_DISCREPANCY -> AlertKind.CARRYING_EXTRA
        }
    }

    private fun Severity.toApi(): ApiSeverity = ApiSeverity.valueOf(name)
}
