package com.esmpfun.antidupe.ledger

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BundleMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Tags items with the current owner's UUID and nothing else. A per-item UUID would stop
 * same-owner stacks merging, so ownership history lives in the ledger rather than in NBT.
 */
class OwnershipManager(
    private val plugin: Plugin,
    primaryKey: NamespacedKey? = null,
    legacyKeys: List<NamespacedKey> = emptyList(),
) {

    private companion object {
        private const val MAX_RECURSION_DEPTH = 10
    }

    private val ownerKey = primaryKey ?: NamespacedKey("antidupepro", "adp_owner")

    /** Older keys still recognized on read; rewritten to [ownerKey] on the next write. */
    private val legacyOwnerKeys = legacyKeys.filter { it != ownerKey }

    fun getOwner(item: ItemStack): UUID? {
        val meta = item.itemMeta ?: return null
        val pdc = meta.persistentDataContainer
        val ownerStr = pdc.get(ownerKey, PersistentDataType.STRING)
            ?: legacyOwnerKeys.firstNotNullOfOrNull { pdc.get(it, PersistentDataType.STRING) }
        return ownerStr?.let {
            try {
                UUID.fromString(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    fun setOwner(item: ItemStack, owner: UUID) {
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        pdc.set(ownerKey, PersistentDataType.STRING, owner.toString())
        for (legacy in legacyOwnerKeys) pdc.remove(legacy)
        item.itemMeta = meta
    }

    fun clearOwner(item: ItemStack) {
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        pdc.remove(ownerKey)
        for (legacy in legacyOwnerKeys) pdc.remove(legacy)
        item.itemMeta = meta
    }

    fun isTracked(item: ItemStack): Boolean {
        return getOwner(item) != null
    }

    fun isOwnedBy(item: ItemStack, player: Player): Boolean {
        return getOwner(item) == player.uniqueId
    }

    fun isOwnedBy(item: ItemStack, playerUuid: UUID): Boolean {
        return getOwner(item) == playerUuid
    }

    fun transferOwnership(item: ItemStack, newOwner: UUID): Boolean {
        val currentOwner = getOwner(item) ?: return false
        if (currentOwner == newOwner) return false

        setOwner(item, newOwner)
        return true
    }

    fun tagNewItem(item: ItemStack, owner: UUID): ItemStack {
        val tagged = item.clone()
        setOwner(tagged, owner)
        return tagged
    }

    /**
     * `PlayerInventory.getContents()` already spans storage, armor and offhand, so adding
     * `armorContents` or `itemInOffHand` on top double-counts worn gear.
     */
    fun countOwnedInInventory(player: Player, material: Material): Int {
        var count = 0
        for (item in player.inventory.contents.filterNotNull()) {
            if (item.type == material && isOwnedBy(item, player)) {
                count += item.amount
            }
        }
        return count
    }

    fun countAllInInventory(player: Player, material: Material): Int {
        var count = 0
        for (item in player.inventory.contents.filterNotNull()) {
            if (item.type == material) {
                count += item.amount
            }
        }
        return count
    }

    fun getTrackedInventory(player: Player): Map<Material, Int> {
        val tracked = mutableMapOf<Material, Int>()
        for (item in player.inventory.contents.filterNotNull()) {
            if (isOwnedBy(item, player)) {
                tracked[item.type] = (tracked[item.type] ?: 0) + item.amount
            }
        }
        return tracked
    }

    fun countOwnedInInventoryDeep(player: Player, material: Material): Int {
        var count = countOwnedInInventory(player, material)
        for (item in allHeldItems(player)) {
            count += countOwnedInContainer(item, player.uniqueId, material, depth = 0)
        }
        return count
    }

    fun countAllInInventoryDeep(player: Player, material: Material): Int {
        var count = countAllInInventory(player, material)
        for (item in allHeldItems(player)) {
            count += countAllInContainer(item, material, depth = 0)
        }
        return count
    }

    /** Must be called on the player's thread; async callers snapshot here, then compute off-thread. */
    fun snapshotOwnedDeep(player: Player, materials: Set<Material>): Map<Material, Int> {
        val counts = HashMap<Material, Int>()
        for (item in player.inventory.contents) {
            if (item == null) continue
            if (item.type in materials && isOwnedBy(item, player)) {
                counts.merge(item.type, item.amount, Int::plus)
            }
            collectOwnedInContainer(item, player.uniqueId, materials, counts, depth = 0)
        }
        return counts
    }

    private fun collectOwnedInContainer(
        stack: ItemStack, ownerUuid: UUID, materials: Set<Material>,
        sink: MutableMap<Material, Int>, depth: Int
    ) {
        if (depth >= MAX_RECURSION_DEPTH) return
        val meta = stack.itemMeta ?: return

        if (meta is BlockStateMeta && meta.hasBlockState()) {
            val state = meta.blockState
            if (state is Container) {
                for (inner in state.inventory.contents) {
                    if (inner == null) continue
                    if (inner.type in materials && getOwner(inner) == ownerUuid) {
                        sink.merge(inner.type, inner.amount, Int::plus)
                    }
                    collectOwnedInContainer(inner, ownerUuid, materials, sink, depth + 1)
                }
            }
        }
        if (meta is BundleMeta) {
            for (inner in meta.items) {
                if (inner.type in materials && getOwner(inner) == ownerUuid) {
                    sink.merge(inner.type, inner.amount, Int::plus)
                }
                collectOwnedInContainer(inner, ownerUuid, materials, sink, depth + 1)
            }
        }
        SulfurCubeAccess.absorbedItem(stack)?.let { inner ->
            if (inner.type in materials && getOwner(inner) == ownerUuid) {
                sink.merge(inner.type, inner.amount, Int::plus)
            }
            collectOwnedInContainer(inner, ownerUuid, materials, sink, depth + 1)
        }
    }

    private fun countOwnedInContainer(stack: ItemStack, ownerUuid: UUID, material: Material, depth: Int): Int {
        if (depth >= MAX_RECURSION_DEPTH) return 0
        val meta = stack.itemMeta ?: return 0
        var count = 0

        if (meta is BlockStateMeta && meta.hasBlockState()) {
            val state = meta.blockState
            if (state is Container) {
                for (inner in state.inventory.contents) {
                    if (inner == null) continue
                    if (inner.type == material && getOwner(inner) == ownerUuid) count += inner.amount
                    count += countOwnedInContainer(inner, ownerUuid, material, depth + 1)
                }
            }
        }
        if (meta is BundleMeta) {
            for (inner in meta.items) {
                if (inner.type == material && getOwner(inner) == ownerUuid) count += inner.amount
                count += countOwnedInContainer(inner, ownerUuid, material, depth + 1)
            }
        }
        SulfurCubeAccess.absorbedItem(stack)?.let { inner ->
            if (inner.type == material && getOwner(inner) == ownerUuid) count += inner.amount
            count += countOwnedInContainer(inner, ownerUuid, material, depth + 1)
        }
        return count
    }

    private fun countAllInContainer(stack: ItemStack, material: Material, depth: Int): Int {
        if (depth >= MAX_RECURSION_DEPTH) return 0
        val meta = stack.itemMeta ?: return 0
        var count = 0

        if (meta is BlockStateMeta && meta.hasBlockState()) {
            val state = meta.blockState
            if (state is Container) {
                for (inner in state.inventory.contents) {
                    if (inner == null) continue
                    if (inner.type == material) count += inner.amount
                    count += countAllInContainer(inner, material, depth + 1)
                }
            }
        }
        if (meta is BundleMeta) {
            for (inner in meta.items) {
                if (inner.type == material) count += inner.amount
                count += countAllInContainer(inner, material, depth + 1)
            }
        }
        SulfurCubeAccess.absorbedItem(stack)?.let { inner ->
            if (inner.type == material) count += inner.amount
            count += countAllInContainer(inner, material, depth + 1)
        }
        return count
    }

    // Yielding armorContents or the offhand as well would scan a held container twice:
    // getContents() already spans storage, armor and offhand.
    private fun allHeldItems(player: Player): Sequence<ItemStack> = sequence {
        for (item in player.inventory.contents.filterNotNull()) yield(item)
    }

    fun findForeignItemsDeep(player: Player): List<ForeignItem> {
        val foreign = mutableListOf<ForeignItem>()
        for ((slot, item) in player.inventory.contents.withIndex()) {
            if (item == null) continue
            val owner = getOwner(item)
            if (owner != null && owner != player.uniqueId) {
                foreign.add(ForeignItem(slot = slot, item = item, originalOwner = owner))
            }
            collectForeignInContainer(item, player.uniqueId, slot, foreign, depth = 0)
        }
        return foreign
    }

    private fun collectForeignInContainer(
        stack: ItemStack, ownerUuid: UUID, outerSlot: Int,
        sink: MutableList<ForeignItem>, depth: Int
    ) {
        if (depth >= MAX_RECURSION_DEPTH) return
        val meta = stack.itemMeta ?: return

        if (meta is BlockStateMeta && meta.hasBlockState()) {
            val state = meta.blockState
            if (state is Container) {
                for (inner in state.inventory.contents) {
                    if (inner == null) continue
                    val innerOwner = getOwner(inner)
                    if (innerOwner != null && innerOwner != ownerUuid) {
                        sink.add(ForeignItem(slot = outerSlot, item = inner, originalOwner = innerOwner))
                    }
                    collectForeignInContainer(inner, ownerUuid, outerSlot, sink, depth + 1)
                }
            }
        }
        if (meta is BundleMeta) {
            for (inner in meta.items) {
                val innerOwner = getOwner(inner)
                if (innerOwner != null && innerOwner != ownerUuid) {
                    sink.add(ForeignItem(slot = outerSlot, item = inner, originalOwner = innerOwner))
                }
                collectForeignInContainer(inner, ownerUuid, outerSlot, sink, depth + 1)
            }
        }
        SulfurCubeAccess.absorbedItem(stack)?.let { inner ->
            val innerOwner = getOwner(inner)
            if (innerOwner != null && innerOwner != ownerUuid) {
                sink.add(ForeignItem(slot = outerSlot, item = inner, originalOwner = innerOwner))
            }
            collectForeignInContainer(inner, ownerUuid, outerSlot, sink, depth + 1)
        }
    }

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

fun ItemStack.getOwner(manager: OwnershipManager): UUID? = manager.getOwner(this)
fun ItemStack.setOwner(manager: OwnershipManager, owner: UUID) = manager.setOwner(this, owner)
fun ItemStack.isTracked(manager: OwnershipManager): Boolean = manager.isTracked(this)
