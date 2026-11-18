package com.esmpfun.antidupe.adapter.v26_2

import com.esmpfun.antidupe.adapter.ServerAdapter
import com.esmpfun.antidupe.adapter.SulfurCubeContents
import com.esmpfun.antidupe.adapter.SulfurCubeSwallow
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.SulfurCubeContent
import io.papermc.paper.event.entity.SulfurCubeSwallowItemEvent
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** Paper 26.2 and 26.3, the first with sulfur cubes. */
open class Adapter26_2 : ServerAdapter {

    override val versions = "26.2 to 26.3"

    override val sulfurCubeContents: SulfurCubeContents = object : SulfurCubeContents {
        override fun absorbedItem(bucket: ItemStack): ItemStack? =
            bucket.getData(DataComponentTypes.SULFUR_CUBE_CONTENT)?.absorbedItem()?.takeIf { it.type != Material.AIR }

        override fun setAbsorbedItem(bucket: ItemStack, inner: ItemStack): Boolean {
            bucket.setData(DataComponentTypes.SULFUR_CUBE_CONTENT, SulfurCubeContent.sulfurCubeContent(inner))
            return true
        }
    }

    override fun listenForSulfurCubeSwallow(plugin: Plugin, onSwallow: SulfurCubeSwallow) {
        plugin.server.pluginManager.registerEvents(SwallowListener(onSwallow), plugin)
    }

    private class SwallowListener(private val onSwallow: SulfurCubeSwallow) : Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onSwallow(event: SulfurCubeSwallowItemEvent) {
            onSwallow.swallowed(event.player, event.entity, event.oldItem, event.newItem)
        }
    }
}
