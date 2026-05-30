package com.server.antidupe.core

import com.server.antidupe.data.IsotopeStorage
import com.server.antidupe.core.IsotopeManager.Companion.isotopeId
import org.bukkit.block.Container
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class IsotopeScanner(
    private val plugin: JavaPlugin,
    private val storage: IsotopeStorage
) {

    companion object {
        // Maximum recursion depth to prevent stack overflow from malicious nested shulkers
        private const val MAX_RECURSION_DEPTH = 10
    }

    /**
     * Scan result containing detailed information about the scan
     */
    data class ScanResult(
        val isClean: Boolean,
        val totalItems: Int,
        val spentCount: Int,
        val activeCount: Int,
        val unknownCount: Int,
        val spentIds: List<UUID>
    )

    /**
     * THE ENTRY POINT: Scans an item (and its internals) for Duplicates.
     * Returns TRUE if the item is clean.
     * Returns FALSE if a dupe is detected (SPENT items found).
     *
     * @param rootItem The item to scan (typically a Shulker Box)
     * @param playerUUID The player currently holding/accessing the item
     */
    suspend fun scanItemHierarchy(rootItem: ItemStack, playerUUID: UUID): Boolean {
        val result = scanItemHierarchyDetailed(rootItem, playerUUID)
        return result.isClean
    }

    /**
     * Detailed scan that returns full scan results
     */
    suspend fun scanItemHierarchyDetailed(rootItem: ItemStack, playerUUID: UUID): ScanResult {
        // 1. Collect all IDs recursively (CPU operation, fast)
        val foundIsotopes = mutableListOf<UUID>()
        collectIsotopesRecursively(rootItem, foundIsotopes, 0)

        if (foundIsotopes.isEmpty()) {
            return ScanResult(
                isClean = true,
                totalItems = 0,
                spentCount = 0,
                activeCount = 0,
                unknownCount = 0,
                spentIds = emptyList()
            )
        }

        // 2. Batch Check against Redis (IO operation, Async)
        val statusMap = storage.getStatuses(foundIsotopes)

        // 3. Analyze Results
        var spentCount = 0
        var activeCount = 0
        var unknownCount = 0
        val spentIds = mutableListOf<UUID>()

        statusMap.forEach { (id, rawStatus) ->
            // Parse status (format: "STATUS|OWNER|TIMESTAMP" or just "SPENT")
            val status = rawStatus.split("|").firstOrNull() ?: "UNKNOWN"

            when {
                status == "SPENT" -> {
                    spentCount++
                    spentIds.add(id)
                    plugin.logger.warning("DUPE DETECTED: Item $id inside container is marked SPENT")
                }
                status == "ACTIVE" -> {
                    activeCount++
                    // Check if this item belongs to a different owner (suspicious but not definitive)
                    val storedOwner = extractOwnerFromStatus(rawStatus)
                    if (storedOwner != null && storedOwner != playerUUID && storedOwner != UUID(0, 0)) {
                        plugin.logger.info("OWNERSHIP TRANSFER: Item $id originally owned by $storedOwner, now held by $playerUUID")
                    }
                }
                status == "UNKNOWN" || rawStatus == "UNKNOWN" -> {
                    unknownCount++
                    // Item has UUID but not in Redis - could be from before tracking started
                    plugin.logger.fine("UNTRACKED isotope found: $id")
                }
                else -> {
                    unknownCount++
                    plugin.logger.warning("Unexpected status '$status' for isotope $id")
                }
            }
        }

        val isClean = spentCount == 0

        if (!isClean) {
            plugin.logger.warning("SCAN RESULT: Found $spentCount SPENT (duped) items out of ${foundIsotopes.size} total")
        }

        return ScanResult(
            isClean = isClean,
            totalItems = foundIsotopes.size,
            spentCount = spentCount,
            activeCount = activeCount,
            unknownCount = unknownCount,
            spentIds = spentIds
        )
    }

    /**
     * THE RECURSIVE EXTRACTOR
     * Drills down into Shulker Boxes to find all isotope IDs.
     * Includes depth limiting to prevent stack overflow from malicious nested containers.
     *
     * @param stack The item to scan
     * @param collector List to collect found isotope IDs
     * @param depth Current recursion depth (starts at 0)
     */
    private fun collectIsotopesRecursively(stack: ItemStack?, collector: MutableList<UUID>, depth: Int) {
        if (stack == null || stack.amount == 0) return

        // Prevent stack overflow from deeply nested containers
        if (depth > MAX_RECURSION_DEPTH) {
            plugin.logger.warning("Max recursion depth ($MAX_RECURSION_DEPTH) exceeded while scanning containers")
            return
        }

        // 1. Check the item itself
        val outerId = stack.isotopeId
        if (outerId != null) {
            collector.add(outerId)
        }

        // 2. Check for nesting (Shulker Boxes, Barrels stored as items, etc.)
        val meta = stack.itemMeta
        if (meta == null) {
            // Log if expected to have meta but doesn't
            if (stack.type.name.contains("SHULKER_BOX")) {
                plugin.logger.fine("Shulker box without itemMeta encountered")
            }
            return
        }

        if (meta is BlockStateMeta) {
            val blockState = meta.blockState

            // Is this a Container? (Shulker Box, Barrel, Chest)
            if (blockState is Container) {
                // Iterate through the internal inventory
                blockState.inventory.contents.forEach { internalItem ->
                    if (internalItem != null) {
                        // RECURSION: Call self for internal items with incremented depth
                        collectIsotopesRecursively(internalItem, collector, depth + 1)
                    }
                }
            }
        }
    }

    /**
     * Extract owner UUID from status string
     * Format: "ACTIVE|OWNER_UUID|TIMESTAMP"
     */
    private fun extractOwnerFromStatus(rawStatus: String): UUID? {
        val parts = rawStatus.split("|")
        if (parts.size < 2) return null

        return try {
            UUID.fromString(parts[1])
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Quick check if a single item is a duplicate (SPENT)
     */
    suspend fun isItemDuplicate(item: ItemStack): Boolean {
        val id = item.isotopeId ?: return false // Untracked items are not duplicates
        val status = storage.getStatus(id)
        return status == "SPENT"
    }

    /**
     * Get the count of isotopes in an item hierarchy
     * Useful for debugging/admin commands
     */
    fun countIsotopes(rootItem: ItemStack): Int {
        val collector = mutableListOf<UUID>()
        collectIsotopesRecursively(rootItem, collector, 0)
        return collector.size
    }
}
