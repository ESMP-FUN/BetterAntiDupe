package com.server.antidupe.core

import com.server.antidupe.data.IsotopeStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * Manages the lifecycle of "Digital Isotopes" attached to ItemStacks.
 * This system treats items as unique financial transactions.
 */
class IsotopeManager(
    private val plugin: JavaPlugin,
    private val storage: IsotopeStorage,
    private val scope: CoroutineScope
) {

    companion object {
        @Volatile
        private var isotopeKey: NamespacedKey? = null
        private val initLock = Any()

        fun initialize(plugin: JavaPlugin) {
            synchronized(initLock) {
                if (isotopeKey == null) {
                    isotopeKey = NamespacedKey(plugin, "adp_sig")
                }
            }
        }

        private fun getKey(): NamespacedKey {
            return isotopeKey ?: throw IllegalStateException("IsotopeManager not initialized - call initialize() first")
        }

        // Extension Property: Allows us to do item.isotopeId instead of calling a method
        // Thread-safe: reads use synchronized access to isotopeKey
        var ItemStack.isotopeId: UUID?
            get() {
                if (!this.hasItemMeta()) return null
                val meta = this.itemMeta
                val key = getKey()
                val uuidString = meta.persistentDataContainer.get(key, PersistentDataType.STRING)
                return uuidString?.let {
                    try {
                        UUID.fromString(it)
                    } catch (e: IllegalArgumentException) {
                        null // Corrupted UUID string
                    }
                }
            }
            set(value) {
                val meta = this.itemMeta ?: return
                val key = getKey()
                if (value == null) {
                    meta.persistentDataContainer.remove(key)
                } else {
                    meta.persistentDataContainer.set(key, PersistentDataType.STRING, value.toString())
                }
                this.itemMeta = meta
            }
    }

    init {
        initialize(plugin)
    }

    /**
     * MINT: Called when a tracked item enters the world (Crafted, Mined, or Admin spawned).
     * Assigns a fresh Isotope ID and logs to Redis.
     *
     * @param stack The item to mint
     * @param owner The player who owns/created this item
     */
    fun mintIsotope(stack: ItemStack, owner: UUID? = null) {
        val newId = UUID.randomUUID()
        stack.isotopeId = newId

        // Async log to Redis
        scope.launch {
            try {
                storage.mint(
                    isotopeId = newId,
                    owner = owner ?: UUID(0, 0), // Zero UUID for system-minted items
                    details = "${stack.type} x${stack.amount}"
                )
            } catch (e: Exception) {
                plugin.logger.warning("Failed to log MINT to Redis: ${e.message}")
            }
        }
    }

    /**
     * SPLIT: Handles the logic when a player right-clicks a stack to split it.
     *
     * LOGIC:
     * Parent (ID: A) splits into Child 1 and Child 2.
     * ID A is marked SPENT.
     * Child 1 gets ID B.
     * Child 2 gets ID C.
     *
     * @param originalStack The remaining stack in the slot
     * @param splitStack The new stack on the cursor
     * @param owner The player performing the split
     */
    fun handleSplit(originalStack: ItemStack, splitStack: ItemStack, owner: UUID) {
        val oldId = originalStack.isotopeId ?: return // Ignore untracked items

        // Generate new IDs for both resulting stacks
        val newId1 = UUID.randomUUID()
        val newId2 = UUID.randomUUID()

        // Assign new identities
        originalStack.isotopeId = newId1
        splitStack.isotopeId = newId2

        // Async: Burn old ID and mint new ones
        scope.launch {
            try {
                // 1. Burn the old ID atomically
                val burnSuccess = storage.attemptTransaction(oldId)
                if (!burnSuccess) {
                    plugin.logger.warning("SPLIT BURN FAILED: $oldId was already spent (possible dupe)")
                }

                // 2. Mint the two new IDs
                storage.mint(newId1, owner, "SPLIT from $oldId (remaining)")
                storage.mint(newId2, owner, "SPLIT from $oldId (cursor)")

            } catch (e: Exception) {
                plugin.logger.warning("Failed to log SPLIT to Redis: ${e.message}")
            }
        }
    }

    /**
     * MERGE: Handles when a player combines two stacks.
     *
     * LOGIC:
     * Stack A (ID: 1) + Stack B (ID: 2) -> Stack C (ID: 3)
     * IDs 1 and 2 are SPENT.
     * Stack C gets ID 3.
     *
     * @param targetStack The stack being added to (will become the merged result)
     * @param sourceStack The stack being consumed
     * @param owner The player performing the merge
     */
    fun handleMerge(targetStack: ItemStack, sourceStack: ItemStack, owner: UUID) {
        val id1 = targetStack.isotopeId
        val id2 = sourceStack.isotopeId

        // If neither are tracked, do nothing
        if (id1 == null && id2 == null) return

        // Generate new ID for the combined stack
        val newId = UUID.randomUUID()
        targetStack.isotopeId = newId

        // Source stack is about to be destroyed/emptied by the game engine
        sourceStack.isotopeId = null

        // Async: Burn both parent IDs and mint new one
        scope.launch {
            try {
                // Burn both previous IDs (if they exist)
                if (id1 != null) {
                    val burn1 = storage.attemptTransaction(id1)
                    if (!burn1) {
                        plugin.logger.warning("MERGE BURN FAILED: $id1 was already spent")
                    }
                }
                if (id2 != null) {
                    val burn2 = storage.attemptTransaction(id2)
                    if (!burn2) {
                        plugin.logger.warning("MERGE BURN FAILED: $id2 was already spent")
                    }
                }

                // Mint the new combined ID
                storage.mint(newId, owner, "MERGE from ${id1 ?: "untracked"} + ${id2 ?: "untracked"}")

            } catch (e: Exception) {
                plugin.logger.warning("Failed to log MERGE to Redis: ${e.message}")
            }
        }
    }

    /**
     * MERGE RESULT: Handles a merge that already happened (post-event).
     * Re-mints the isotope for the resulting stack.
     *
     * @param resultStack The merged stack result
     * @param owner The player who performed the merge
     */
    fun handleMergeResult(resultStack: ItemStack?, owner: UUID) {
        if (resultStack == null || resultStack.type.isAir) return

        // The old IDs should already be burned by the event handler
        // Just mint a new ID for the result
        val newId = UUID.randomUUID()
        resultStack.isotopeId = newId

        scope.launch {
            try {
                storage.mint(newId, owner, "MERGE_RESULT ${resultStack.type} x${resultStack.amount}")
            } catch (e: Exception) {
                plugin.logger.warning("Failed to log MERGE_RESULT to Redis: ${e.message}")
            }
        }
    }

    /**
     * VALIDATE: Checks if the specific UUID is valid (ACTIVE) in Redis.
     * Returns FALSE if the item is marked SPENT (dupe detected).
     *
     * This is a suspend function - call from a coroutine.
     *
     * @param stack The item to validate
     * @return true if valid/untracked, false if SPENT (duplicate)
     */
    suspend fun validateIsotope(stack: ItemStack): Boolean {
        val id = stack.isotopeId ?: return true // Not a tracked item, so it's "valid"

        return try {
            val status = storage.getStatus(id)
            when {
                status.startsWith("ACTIVE") -> true
                status == "SPENT" -> false
                status == "UNKNOWN" -> {
                    // Item has an ID but it's not in Redis - could be from before tracking started
                    plugin.logger.info("UNKNOWN isotope encountered: $id")
                    true
                }
                else -> {
                    plugin.logger.warning("Unexpected isotope status: $status for $id")
                    true
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("Failed to validate isotope $id: ${e.message}")
            true // Fail open - don't punish players for Redis errors
        }
    }

    /**
     * BURN: Explicitly burns an isotope ID, marking it as SPENT.
     * Use this for drag operations or other non-standard splits.
     *
     * @param isotopeId The UUID to burn
     * @return true if burn was successful, false if already spent
     */
    suspend fun burnIsotope(isotopeId: UUID?): Boolean {
        if (isotopeId == null) return true
        return storage.attemptTransaction(isotopeId)
    }

    /**
     * Fire-and-forget burn for use in non-suspend contexts.
     */
    fun burnIsotopeAsync(isotopeId: UUID?) {
        if (isotopeId == null) return
        scope.launch {
            try {
                val success = storage.attemptTransaction(isotopeId)
                if (!success) {
                    plugin.logger.warning("Async BURN failed: $isotopeId was already spent")
                }
            } catch (e: Exception) {
                plugin.logger.warning("Failed to burn isotope $isotopeId: ${e.message}")
            }
        }
    }
}
