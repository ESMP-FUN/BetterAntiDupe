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
 *
 * With a [signer], every mark carries a signature beside it, and a mark whose signature does not
 * verify is not trusted: it reads as no owner at all, so a faked mark changes no one's record.
 */
class OwnershipManager(
    private val plugin: Plugin,
    primaryKey: NamespacedKey? = null,
    legacyKeys: List<NamespacedKey> = emptyList(),
    private val signer: MarkSigner? = null,
    private val acceptUnsigned: Boolean = true,
) {

    private companion object {
        private const val MAX_RECURSION_DEPTH = 10
        private val NO_MARK = Mark(MarkState.NONE, null, null)
    }

    enum class MarkState { NONE, SIGNED, UNSIGNED, FORGED, OTHER_KEY }

    /** [claimed] is whoever the mark names; [trusted] is that owner only when the mark is believed. */
    class Mark(val state: MarkState, val claimed: UUID?, val trusted: UUID?, val outdated: Boolean = false)

    private val ownerKey = primaryKey ?: NamespacedKey("antidupepro", "adp_owner")

    /** Older keys still recognized on read; rewritten to [ownerKey] on the next write. */
    private val legacyOwnerKeys = legacyKeys.filter { it != ownerKey }

    private val ownerSigKey = OwnershipKeys.signatureKeyFor(ownerKey)
    private val legacySigKeys = legacyOwnerKeys.map { OwnershipKeys.signatureKeyFor(it) }

    val signing: Boolean get() = signer != null
    val signingKeyId: String? get() = signer?.currentKeyId
    val previousSigningKeys: Int get() = signer?.previousKeyCount ?: 0
    val trustsUnsigned: Boolean get() = signer == null || acceptUnsigned

    /** A faked mark is about to be replaced: (the player it now goes to, who the mark claimed, the item). */
    @Volatile var onForgedMark: ((UUID, UUID, ItemStack) -> Unit)? = null

    /** A mark signed with a key this server does not have; usually a missing copy of the key file. */
    @Volatile var onOtherKey: (() -> Unit)? = null

    val resigned = java.util.concurrent.atomic.AtomicLong()
    /** Stacks with a fake mark that staff were told about. */
    val forgedReported = java.util.concurrent.atomic.AtomicLong()

    fun readMark(item: ItemStack): Mark {
        val meta = item.itemMeta ?: return NO_MARK
        return readMark(meta.persistentDataContainer)
    }

    private fun readMark(pdc: org.bukkit.persistence.PersistentDataContainer): Mark {
        var raw = pdc.get(ownerKey, PersistentDataType.STRING)
        var sigKey = ownerSigKey
        if (raw == null) {
            for ((i, key) in legacyOwnerKeys.withIndex()) {
                raw = pdc.get(key, PersistentDataType.STRING) ?: continue
                sigKey = legacySigKeys[i]
                break
            }
        }
        if (raw == null) return NO_MARK
        val owner = try { UUID.fromString(raw) } catch (e: IllegalArgumentException) { return NO_MARK }
        val signer = signer ?: return Mark(MarkState.SIGNED, owner, owner)
        val signature = pdc.get(sigKey, PersistentDataType.STRING)
            ?: return Mark(MarkState.UNSIGNED, owner, if (acceptUnsigned) owner else null)
        return when (signer.verify(owner, signature)) {
            MarkSigner.Verdict.VALID -> Mark(MarkState.SIGNED, owner, owner, signer.isOutdated(signature))
            MarkSigner.Verdict.FORGED -> Mark(MarkState.FORGED, owner, null)
            MarkSigner.Verdict.OTHER_KEY -> {
                onOtherKey?.invoke()
                Mark(MarkState.OTHER_KEY, owner, null)
            }
        }
    }

    fun getOwner(item: ItemStack): UUID? = readMark(item).trusted

    fun setOwner(item: ItemStack, owner: UUID) {
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        if (signer != null) {
            val previous = readMark(pdc)
            if (previous.state == MarkState.FORGED && previous.claimed != null) {
                onForgedMark?.invoke(owner, previous.claimed, item.clone())
            }
        }
        writeMark(pdc, owner)
        item.itemMeta = meta
    }

    private fun writeMark(pdc: org.bukkit.persistence.PersistentDataContainer, owner: UUID) {
        pdc.set(ownerKey, PersistentDataType.STRING, owner.toString())
        val signer = signer
        if (signer != null) pdc.set(ownerSigKey, PersistentDataType.STRING, signer.sign(owner))
        else pdc.remove(ownerSigKey)
        for (legacy in legacyOwnerKeys) pdc.remove(legacy)
        for (legacy in legacySigKeys) pdc.remove(legacy)
    }

    fun clearOwner(item: ItemStack) {
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        pdc.remove(ownerKey)
        pdc.remove(ownerSigKey)
        for (legacy in legacyOwnerKeys) pdc.remove(legacy)
        for (legacy in legacySigKeys) pdc.remove(legacy)
        item.itemMeta = meta
    }

    /**
     * Signs the trusted but unsigned or outdated marks of [owner]'s own items in their inventory,
     * nested ones included, so older marks catch up without anyone moving them. Only the holder's
     * own marks: signing someone else's unsigned mark would vouch for a mark nobody checked.
     * Must run on the player's thread.
     */
    fun signOwnMarks(player: Player): Int {
        if (signer == null) return 0
        val inventory = player.inventory
        var count = 0
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            val signed = signOwnMarks(item, player.uniqueId, 0)
            if (signed > 0) {
                inventory.setItem(slot, item)
                count += signed
            }
        }
        if (count > 0) resigned.addAndGet(count.toLong())
        return count
    }

    private fun signOwnMarks(holder: ItemStack, owner: UUID, depth: Int): Int {
        if (depth >= MAX_RECURSION_DEPTH || holder.type == Material.AIR) return 0
        val meta = holder.itemMeta ?: return 0
        var count = 0
        val mark = readMark(meta.persistentDataContainer)
        if (mark.trusted == owner && (mark.state == MarkState.UNSIGNED || mark.outdated)) {
            writeMark(meta.persistentDataContainer, owner)
            count++
        }
        if (meta is BlockStateMeta && meta.hasBlockState()) {
            val state = meta.blockState
            if (state is Container) {
                val inv = state.inventory
                var changed = false
                for (slot in 0 until inv.size) {
                    val inner = inv.getItem(slot) ?: continue
                    val n = signOwnMarks(inner, owner, depth + 1)
                    if (n > 0) { inv.setItem(slot, inner); count += n; changed = true }
                }
                if (changed) meta.blockState = state
            }
        }
        if (meta is BundleMeta) {
            val items = meta.items.map { it.clone() }
            var changed = false
            for (inner in items) {
                val n = signOwnMarks(inner, owner, depth + 1)
                if (n > 0) { count += n; changed = true }
            }
            if (changed) meta.setItems(items)
        }
        if (count > 0) holder.itemMeta = meta
        SulfurCubeAccess.absorbedItem(holder)?.let { inner ->
            val n = signOwnMarks(inner, owner, depth + 1)
            if (n > 0 && SulfurCubeAccess.setAbsorbedItem(holder, inner)) count += n
        }
        return count
    }

    /** Faked marks anywhere in [player]'s inventory, nested ones included. */
    fun findForgedDeep(player: Player): List<ForgedMark> {
        if (signer == null) return emptyList()
        val found = ArrayList<ForgedMark>()
        for (item in player.inventory.contents) collectForged(item, found, 0)
        return found
    }

    private fun collectForged(stack: ItemStack?, found: MutableList<ForgedMark>, depth: Int) {
        if (stack == null || stack.type == Material.AIR || depth >= MAX_RECURSION_DEPTH) return
        val meta = stack.itemMeta ?: return
        val mark = readMark(meta.persistentDataContainer)
        if (mark.state == MarkState.FORGED && mark.claimed != null) {
            found.add(ForgedMark(stack.type, stack.amount, mark.claimed))
        }
        if (meta is BlockStateMeta && meta.hasBlockState()) {
            (meta.blockState as? Container)?.inventory?.contents?.forEach { collectForged(it, found, depth + 1) }
        }
        if (meta is BundleMeta) meta.items.forEach { collectForged(it, found, depth + 1) }
        SulfurCubeAccess.absorbedItem(stack)?.let { collectForged(it, found, depth + 1) }
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

    class NestedRetag(val changed: Boolean, val previousOwner: UUID?)

    /**
     * Adds every tagged, tracked item in [stack], nested ones at any depth included, to [sink]
     * keyed by owner and material. Untagged items are left out.
     */
    fun tallyOwners(
        stack: ItemStack?, isTracked: (Material) -> Boolean,
        sink: MutableMap<Pair<UUID, Material>, Int>, depth: Int = 0
    ) {
        if (stack == null || stack.type == Material.AIR || depth >= MAX_RECURSION_DEPTH) return
        if (isTracked(stack.type)) getOwner(stack)?.let { sink.merge(it to stack.type, stack.amount, Int::plus) }
        val meta = stack.itemMeta ?: return
        if (meta is BlockStateMeta && meta.hasBlockState()) {
            (meta.blockState as? Container)?.inventory?.contents?.forEach { tallyOwners(it, isTracked, sink, depth + 1) }
        }
        if (meta is BundleMeta) meta.items.forEach { tallyOwners(it, isTracked, sink, depth + 1) }
        SulfurCubeAccess.absorbedItem(stack)?.let { tallyOwners(it, isTracked, sink, depth + 1) }
    }

    /**
     * Gives [owner]'s tag to tracked items nested in a shulker box or bundle held as [holder],
     * at any depth, writing the contents back into the holder. With a [budget], at most that many
     * of each material are retagged and the budget is drawn down; nested stacks are never split,
     * so one too large for the budget keeps its tag. A sulfur cube bucket's absorbed block counts
     * as nested too.
     */
    fun retagNested(
        holder: ItemStack, owner: UUID, isTracked: (Material) -> Boolean,
        budget: MutableMap<Material, Int>? = null, depth: Int = 0
    ): NestedRetag {
        if (depth >= MAX_RECURSION_DEPTH) return NestedRetag(false, null)
        val meta = holder.itemMeta ?: return NestedRetag(false, null)
        var changed = false
        var previous: UUID? = null

        fun claim(inner: ItemStack): Boolean {
            var modified = false
            if (isTracked(inner.type)) {
                val current = getOwner(inner)
                val allowed = budget?.get(inner.type) ?: if (budget == null) Int.MAX_VALUE else 0
                if (current != owner && inner.amount <= allowed) {
                    setOwner(inner, owner)
                    budget?.merge(inner.type, -inner.amount, Int::plus)
                    if (previous == null) previous = current
                    modified = true
                }
            }
            val deeper = retagNested(inner, owner, isTracked, budget, depth + 1)
            if (deeper.changed) {
                modified = true
                if (previous == null) previous = deeper.previousOwner
            }
            return modified
        }

        if (meta is BlockStateMeta && meta.hasBlockState()) {
            val state = meta.blockState
            if (state is Container) {
                val inv = state.inventory
                var stateChanged = false
                for (slot in 0 until inv.size) {
                    val inner = inv.getItem(slot) ?: continue
                    if (claim(inner)) {
                        inv.setItem(slot, inner)
                        stateChanged = true
                    }
                }
                if (stateChanged) {
                    meta.blockState = state
                    changed = true
                }
            }
        }
        if (meta is BundleMeta) {
            val items = meta.items.map { it.clone() }
            var bundleChanged = false
            for (inner in items) if (claim(inner)) bundleChanged = true
            if (bundleChanged) {
                meta.setItems(items)
                changed = true
            }
        }
        if (changed) holder.itemMeta = meta
        // After the meta write, which would otherwise put the old component value back.
        SulfurCubeAccess.absorbedItem(holder)?.let { inner ->
            if (claim(inner) && SulfurCubeAccess.setAbsorbedItem(holder, inner)) changed = true
        }
        return NestedRetag(changed, previous)
    }

    /** Removes every owner mark from [holder] and anything nested in it. Returns true if one was found. */
    fun clearOwnerDeep(holder: ItemStack, depth: Int = 0): Boolean {
        if (depth >= MAX_RECURSION_DEPTH || holder.type == Material.AIR) return false
        var changed = false
        // Any mark, trusted or not: a faked one has no owner but still has to go.
        if (readMark(holder).state != MarkState.NONE) {
            clearOwner(holder)
            changed = true
        }
        val meta = holder.itemMeta ?: return changed
        var metaChanged = false
        if (meta is BlockStateMeta && meta.hasBlockState()) {
            val state = meta.blockState
            if (state is Container) {
                val inv = state.inventory
                var stateChanged = false
                for (slot in 0 until inv.size) {
                    val inner = inv.getItem(slot) ?: continue
                    if (clearOwnerDeep(inner, depth + 1)) {
                        inv.setItem(slot, inner)
                        stateChanged = true
                    }
                }
                if (stateChanged) {
                    meta.blockState = state
                    metaChanged = true
                }
            }
        }
        if (meta is BundleMeta) {
            val items = meta.items.map { it.clone() }
            var bundleChanged = false
            for (inner in items) if (clearOwnerDeep(inner, depth + 1)) bundleChanged = true
            if (bundleChanged) {
                meta.setItems(items)
                metaChanged = true
            }
        }
        if (metaChanged) {
            holder.itemMeta = meta
            changed = true
        }
        SulfurCubeAccess.absorbedItem(holder)?.let { inner ->
            if (clearOwnerDeep(inner, depth + 1) && SulfurCubeAccess.setAbsorbedItem(holder, inner)) changed = true
        }
        return changed
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

data class ForgedMark(val material: Material, val amount: Int, val claimedOwner: UUID)

data class ForeignItem(
    val slot: Int,
    val item: ItemStack,
    val originalOwner: UUID
)

fun ItemStack.getOwner(manager: OwnershipManager): UUID? = manager.getOwner(this)
fun ItemStack.setOwner(manager: OwnershipManager, owner: UUID) = manager.setOwner(this, owner)
fun ItemStack.isTracked(manager: OwnershipManager): Boolean = manager.isTracked(this)
