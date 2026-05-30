package com.server.antidupe.ledger

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Manages item ownership tagging using a simplified NBT approach.
 *
 * Unlike the per-item UUID approach (which breaks stacking), this system
 * only stores the current OWNER's UUID. Items with the same owner and
 * material type stack normally, preserving vanilla behavior.
 *
 * Ownership changes are tracked in the Ledger, not in item NBT history.
 */
class OwnershipManager(private val plugin: Plugin) {

    private val ownerKey = NamespacedKey(plugin, "adp_owner")

    /**
     * Get the current owner of an item, if tracked
     */
    fun getOwner(item: ItemStack): UUID? {
        val meta = item.itemMeta ?: return null
        val ownerStr = meta.persistentDataContainer.get(ownerKey, PersistentDataType.STRING)
        return ownerStr?.let {
            try {
                UUID.fromString(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    /**
     * Set the owner of an item
     */
    fun setOwner(item: ItemStack, owner: UUID) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, owner.toString())
        item.itemMeta = meta
    }

    /**
     * Remove ownership tag (makes item untracked)
     */
    fun clearOwner(item: ItemStack) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.remove(ownerKey)
        item.itemMeta = meta
    }

    /**
     * Check if an item is currently tracked (has an owner)
     */
    fun isTracked(item: ItemStack): Boolean {
        return getOwner(item) != null
    }

    /**
     * Check if an item is owned by a specific player
     */
    fun isOwnedBy(item: ItemStack, player: Player): Boolean {
        return getOwner(item) == player.uniqueId
    }

    /**
     * Check if an item is owned by a specific player UUID
     */
    fun isOwnedBy(item: ItemStack, playerUuid: UUID): Boolean {
        return getOwner(item) == playerUuid
    }

    /**
     * Transfer ownership from one player to another.
     * Returns true if ownership was changed, false if item was untracked.
     */
    fun transferOwnership(item: ItemStack, newOwner: UUID): Boolean {
        val currentOwner = getOwner(item) ?: return false
        if (currentOwner == newOwner) return false

        setOwner(item, newOwner)
        return true
    }

    /**
     * Create a tagged copy of an item for a new owner.
     * Used when items are legitimately duplicated (crafting output, etc.)
     */
    fun tagNewItem(item: ItemStack, owner: UUID): ItemStack {
        val tagged = item.clone()
        setOwner(tagged, owner)
        return tagged
    }

    /**
     * Count items of a specific type owned by a player in their inventory
     */
    fun countOwnedInInventory(player: Player, material: Material): Int {
        var count = 0

        // Main inventory
        for (item in player.inventory.contents.filterNotNull()) {
            if (item.type == material && isOwnedBy(item, player)) {
                count += item.amount
            }
        }

        // Armor slots
        for (item in player.inventory.armorContents.filterNotNull()) {
            if (item.type == material && isOwnedBy(item, player)) {
                count += item.amount
            }
        }

        // Offhand
        player.inventory.itemInOffHand.let { item ->
            if (item.type == material && isOwnedBy(item, player)) {
                count += item.amount
            }
        }

        return count
    }

    /**
     * Count ALL items of a specific type in player's inventory (tracked or not)
     */
    fun countAllInInventory(player: Player, material: Material): Int {
        var count = 0

        for (item in player.inventory.contents.filterNotNull()) {
            if (item.type == material) {
                count += item.amount
            }
        }

        for (item in player.inventory.armorContents.filterNotNull()) {
            if (item.type == material) {
                count += item.amount
            }
        }

        player.inventory.itemInOffHand.let { item ->
            if (item.type == material) {
                count += item.amount
            }
        }

        return count
    }

    /**
     * Get all tracked items in a player's inventory grouped by material
     */
    fun getTrackedInventory(player: Player): Map<Material, Int> {
        val tracked = mutableMapOf<Material, Int>()

        for (item in player.inventory.contents.filterNotNull()) {
            if (isOwnedBy(item, player)) {
                tracked[item.type] = (tracked[item.type] ?: 0) + item.amount
            }
        }

        for (item in player.inventory.armorContents.filterNotNull()) {
            if (isOwnedBy(item, player)) {
                tracked[item.type] = (tracked[item.type] ?: 0) + item.amount
            }
        }

        player.inventory.itemInOffHand.let { item ->
            if (isOwnedBy(item, player)) {
                tracked[item.type] = (tracked[item.type] ?: 0) + item.amount
            }
        }

        return tracked
    }

    /**
     * Find items that are tracked but have a different owner (suspicious)
     */
    fun findForeignItems(player: Player): List<ForeignItem> {
        val foreign = mutableListOf<ForeignItem>()

        for ((slot, item) in player.inventory.contents.withIndex()) {
            if (item == null) continue
            val owner = getOwner(item)
            if (owner != null && owner != player.uniqueId) {
                foreign.add(ForeignItem(
                    slot = slot,
                    item = item,
                    originalOwner = owner
                ))
            }
        }

        return foreign
    }
}

data class ForeignItem(
    val slot: Int,
    val item: ItemStack,
    val originalOwner: UUID
)

/**
 * Extension functions for ItemStack to work with ownership
 */
fun ItemStack.getOwner(manager: OwnershipManager): UUID? = manager.getOwner(this)
fun ItemStack.setOwner(manager: OwnershipManager, owner: UUID) = manager.setOwner(this, owner)
fun ItemStack.isTracked(manager: OwnershipManager): Boolean = manager.isTracked(this)
