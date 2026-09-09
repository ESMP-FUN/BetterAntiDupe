package com.esmpfun.antidupe.ledger

import io.papermc.paper.datacomponent.DataComponentType
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import java.util.logging.Logger

/**
 * A Bucket of Sulfur Cube (26.2+) carries the block the cube swallowed in its
 * `minecraft:sulfur_cube_content` item component, and that block keeps its NBT -
 * including our ownership tag. Left unseen it is a laundering sink: park a tracked
 * block in a cube, bucket it, and the deep inventory scan reports nothing while
 * reconciliation says "balance matches at zero".
 *
 * The sulfur cube API landed in 26.2, after both of this plugin's compile targets
 * (1.21.11 and 26.1.2), so the component type is looked up from the registry by key
 * at runtime and the one method on the returned value is called reflectively. On an
 * older server the lookup returns null and every call here is a no-op.
 */
internal object SulfurCubeAccess {

    /** The bucket item, or null before 26.2. Used as a cheap pre-filter. */
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

    /** `SulfurCubeContent.absorbedItem()` - resolved off the value's own class. */
    private val absorbedItemMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            Class.forName("io.papermc.paper.datacomponent.item.SulfurCubeContent")
                .getMethod("absorbedItem")
        }.getOrNull()
    }

    private var warned = false

    /**
     * The block stashed inside a Bucket of Sulfur Cube, or null if [stack] is not such a
     * bucket, is an empty one, or the server predates the mechanic.
     */
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
