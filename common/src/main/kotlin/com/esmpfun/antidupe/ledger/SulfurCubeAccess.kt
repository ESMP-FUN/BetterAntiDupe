package com.esmpfun.antidupe.ledger

import com.esmpfun.antidupe.adapter.ServerAdapter
import com.esmpfun.antidupe.adapter.SulfurCubeContents
import com.esmpfun.antidupe.adapter.SulfurCubeSwallow
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * A Bucket of Sulfur Cube holds the block the cube swallowed, NBT and ownership tag intact, so
 * without this a tracked block can be parked in a cube and bucketed to hide from every scan.
 * The reading and writing is the server adapter's; this only guards it.
 */
internal object SulfurCubeAccess {

    /** Null on servers that predate the bucket, which makes it a cheap pre-filter. */
    val bucketMaterial: Material? by lazy {
        runCatching { Material.matchMaterial("SULFUR_CUBE_BUCKET") }.getOrNull()
    }

    @Volatile private var adapter: ServerAdapter? = null
    private val contents: SulfurCubeContents? get() = adapter?.sulfurCubeContents

    fun install(adapter: ServerAdapter) { this.adapter = adapter }

    fun listenForSwallow(plugin: Plugin, onSwallow: SulfurCubeSwallow) {
        adapter?.listenForSulfurCubeSwallow(plugin, onSwallow)
    }

    private var warned = false
    private var warnedWrite = false

    fun absorbedItem(stack: ItemStack, logger: Logger? = null): ItemStack? {
        val bucket = bucketMaterial ?: return null
        if (stack.type != bucket) return null
        val contents = contents ?: return null
        return try {
            contents.absorbedItem(stack)
        } catch (e: Exception) {
            if (!warned) {
                warned = true
                logger?.warning("[Ledger] could not read a sulfur cube bucket's contents: ${e.message}")
            }
            null
        }
    }

    /** Puts [inner] back as the absorbed item. False when this server cannot, leaving [stack] as it was. */
    fun setAbsorbedItem(stack: ItemStack, inner: ItemStack, logger: Logger? = null): Boolean {
        val bucket = bucketMaterial ?: return false
        if (stack.type != bucket) return false
        val contents = contents ?: return false
        return try {
            contents.setAbsorbedItem(stack, inner)
        } catch (e: Exception) {
            if (!warnedWrite) {
                warnedWrite = true
                logger?.warning("[Ledger] could not update a sulfur cube bucket's contents: ${e.message}")
            }
            false
        }
    }
}
