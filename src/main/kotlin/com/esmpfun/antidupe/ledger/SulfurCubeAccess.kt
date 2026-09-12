package com.esmpfun.antidupe.ledger

import io.papermc.paper.datacomponent.DataComponentType
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import java.util.logging.Logger

/**
 * A Bucket of Sulfur Cube holds the block the cube swallowed, NBT and ownership tag intact, so
 * without this a tracked block can be parked in a cube and bucketed to hide from every scan.
 *
 * The API landed after both compile targets (1.21.11 and 26.1.2), so the component type is
 * looked up by key at runtime and read reflectively. Older servers get a null and a no-op.
 */
internal object SulfurCubeAccess {

    /** Null on servers that predate the bucket, which makes it a cheap pre-filter. */
    val bucketMaterial: Material? by lazy {
        runCatching { Material.matchMaterial("SULFUR_CUBE_BUCKET") }.getOrNull()
    }

    private val contentComponentType: DataComponentType.Valued<Any>? by lazy {
        runCatching {
            val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.DATA_COMPONENT_TYPE)
            @Suppress("UNCHECKED_CAST")
            registry.get(NamespacedKey.minecraft("sulfur_cube_content")) as? DataComponentType.Valued<Any>
        }.getOrNull()
    }

    private val absorbedItemMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            Class.forName("io.papermc.paper.datacomponent.item.SulfurCubeContent")
                .getMethod("absorbedItem")
        }.getOrNull()
    }

    private var warned = false

    fun absorbedItem(stack: ItemStack, logger: Logger? = null): ItemStack? {
        val bucket = bucketMaterial ?: return null
        if (stack.type != bucket) return null
        val type = contentComponentType ?: return null
        val method = absorbedItemMethod ?: return null
        return try {
            val content = stack.getData(type) ?: return null
            (method.invoke(content) as? ItemStack)?.takeIf { it.type != Material.AIR }
        } catch (e: Exception) {
            if (!warned) {
                warned = true
                logger?.warning("[Ledger] could not read a sulfur cube bucket's contents: ${e.message}")
            }
            null
        }
    }
}
