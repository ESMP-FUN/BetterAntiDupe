package com.esmpfun.antidupe.ledger

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.inventory.ItemStack
import java.lang.reflect.Method
import java.util.logging.Logger

/**
 * A Bucket of Sulfur Cube holds the block the cube swallowed, NBT and ownership tag intact, so
 * without this a tracked block can be parked in a cube and bucketed to hide from every scan.
 *
 * The data component API is Paper-only and postdates both compile targets, so it is reached purely
 * by reflection. No Paper type may appear in this object's fields or signatures: Spigot fails to
 * initialise the object otherwise, and every inventory event that checks for a bucket throws.
 */
internal object SulfurCubeAccess {

    /** Null on servers that predate the bucket, which makes it a cheap pre-filter. */
    val bucketMaterial: Material? by lazy {
        runCatching { Material.matchMaterial("SULFUR_CUBE_BUCKET") }.getOrNull()
    }

    private class Api(
        val type: Any,
        val getData: Method,
        val setData: Method,
        val absorbedItem: Method,
        val contentFactory: Method
    )

    /** Null on Spigot and on Paper builds without the component. */
    private val api: Api? by lazy {
        runCatching {
            val accessClass = Class.forName("io.papermc.paper.registry.RegistryAccess")
            val keyClass = Class.forName("io.papermc.paper.registry.RegistryKey")
            val access = accessClass.getMethod("registryAccess").invoke(null)
            val registry = accessClass.getMethod("getRegistry", keyClass)
                .invoke(access, keyClass.getField("DATA_COMPONENT_TYPE").get(null)) as Registry<*>
            val type = registry.get(NamespacedKey.minecraft("sulfur_cube_content")) ?: return@runCatching null
            val valued = Class.forName("io.papermc.paper.datacomponent.DataComponentType\$Valued")
            val content = Class.forName("io.papermc.paper.datacomponent.item.SulfurCubeContent")
            Api(
                type,
                ItemStack::class.java.getMethod("getData", valued),
                ItemStack::class.java.getMethod("setData", valued, Any::class.java),
                content.getMethod("absorbedItem"),
                content.getMethod("sulfurCubeContent", ItemStack::class.java)
            )
        }.getOrNull()
    }

    private var warned = false
    private var warnedWrite = false

    fun absorbedItem(stack: ItemStack, logger: Logger? = null): ItemStack? {
        val bucket = bucketMaterial ?: return null
        if (stack.type != bucket) return null
        val api = api ?: return null
        return try {
            val content = api.getData.invoke(stack, api.type) ?: return null
            (api.absorbedItem.invoke(content) as? ItemStack)?.takeIf { it.type != Material.AIR }
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
        val api = api ?: return false
        return try {
            api.setData.invoke(stack, api.type, api.contentFactory.invoke(null, inner) ?: return false)
            true
        } catch (e: Exception) {
            if (!warnedWrite) {
                warnedWrite = true
                logger?.warning("[Ledger] could not update a sulfur cube bucket's contents: ${e.message}")
            }
            false
        }
    }
}
