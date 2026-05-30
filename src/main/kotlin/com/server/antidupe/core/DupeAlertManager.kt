package com.server.antidupe.core

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DupeAlertManager(private val plugin: JavaPlugin) : Listener {

    // SUSPECT LIST: Players who triggered a dupe check but aren't banned yet.
    // We watch their next container interactions.
    private val shadowSuspects = ConcurrentHashMap.newKeySet<UUID>()

    // HEURISTIC TRACKER: Rolling window for TMAR (Phase 5)
    // Map<PlayerUUID, Map<Material, RollingCounter>>
    private val acquisitionHistory = ConcurrentHashMap<UUID, MutableMap<Material, MutableList<Long>>>()

    init {
        // Register this class as an event listener for PlayerQuitEvent
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /**
     * TRIGGER: Called when IsotopeStorage returns "FALSE" (Dupe Confirmed)
     * or when Heuristics go critical.
     */
    fun flagAnomaly(player: Player, item: ItemStack, method: String, confidence: Double) {
        val message = "§c[ANTIDUPE] §f${player.name} flagged for $method (§e${item.type} x${item.amount}§f). Confidence: $confidence%"

        // 1. Notify Admins (In-Game)
        notifyAdmins(message)

        // 2. The "Shadow" Strategy
        // Instead of deleting the item immediately, we mark the player as a suspect.
        if (confidence > 99.0) {
            // High confidence (Isotope collision):
            // We could delete, but let's wait for the stash.
            shadowSuspects.add(player.uniqueId)
            plugin.logger.warning("SHADOW LOG: Watching ${player.name} for stash location.")
        } else {
            // Low confidence (Heuristics): Log and notify admins
            plugin.logger.info("SUSPICIOUS ACTIVITY: ${player.name} exceeded TMAR.")
            // Also notify admins for TMAR violations
            notifyAdmins("§e[ANTIDUPE] §f${player.name} TMAR threshold reached - monitoring")
        }
    }

    /**
     * TMAR CHECK (Theoretical Max Acquisition Rate)
     * Called whenever a player acquires a tracked item (e.g., InventoryPickupItemEvent).
     */
    fun checkAcquisitionRate(player: Player, item: ItemStack): Boolean {
        // Get limit from config, return true (allow) if no limit defined
        val limit = getLimitForMaterial(item.type) ?: return true
        val now = System.currentTimeMillis()
        val oneMinuteAgo = now - 60000

        val history = acquisitionHistory.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        val timestamps = history.computeIfAbsent(item.type) { mutableListOf() }

        // Prune old entries (older than 1 minute)
        synchronized(timestamps) {
            timestamps.removeIf { it < oneMinuteAgo }

            // Add new entries (one per item in stack)
            repeat(item.amount) { timestamps.add(now) }

            // CHECK: Rate = DeltaItems / DeltaTime
            if (timestamps.size > limit) {
                flagAnomaly(player, item, "TMAR_EXCEEDED (Rate: ${timestamps.size}/min)", 75.0)
                return false // Rate limit exceeded
            }
        }
        return true
    }

    /**
     * STASH TRAP: Called on InventoryCloseEvent.
     * If a Shadow Suspect puts items into a chest, we flag that chest as a "Dirty Stash".
     */
    fun handleContainerClose(player: Player, location: Location?) {
        if (!shadowSuspects.contains(player.uniqueId)) return
        if (location == null) return

        // The suspect just accessed a container.
        // Logic: They are likely dumping their duped goods.

        // Action: Log the coordinates for manual review or auto-wipe.
        val worldName = location.world?.name ?: "unknown"
        plugin.logger.severe("STASH FOUND: Suspect ${player.name} accessed container at $worldName ${location.blockX}, ${location.blockY}, ${location.blockZ}")

        // Remove from suspects after catching the stash (they can be re-added on next violation)
        shadowSuspects.remove(player.uniqueId)
    }

    /**
     * Cleanup player data when they disconnect to prevent memory leaks.
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        cleanupPlayer(playerId)
    }

    /**
     * Remove all tracking data for a player.
     * Called on disconnect and can be called manually.
     */
    fun cleanupPlayer(playerId: UUID) {
        acquisitionHistory.remove(playerId)
        shadowSuspects.remove(playerId)
    }

    /**
     * Check if a player is currently marked as a shadow suspect.
     */
    fun isSuspect(playerId: UUID): Boolean {
        return shadowSuspects.contains(playerId)
    }

    /**
     * Get the current suspect count (for admin commands).
     */
    fun getSuspectCount(): Int {
        return shadowSuspects.size
    }

    /**
     * Clear all suspects (admin command).
     */
    fun clearAllSuspects() {
        shadowSuspects.clear()
    }

    private fun getLimitForMaterial(mat: Material): Int? {
        val source = (plugin as? io.github.darkstarworks.AntiDupePro)?.materialsConfig ?: plugin.config
        val configSection = source.getConfigurationSection("tmar_limits") ?: return null
        return configSection.getInt(mat.name, -1).takeIf { it > 0 }
    }

    private fun notifyAdmins(msg: String) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            Bukkit.getOnlinePlayers().filter { it.isOp || it.hasPermission("antidupe.admin") }
                .forEach { it.sendMessage(msg) }
        })
    }
}
