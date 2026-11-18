package com.esmpfun.antidupe.adapter.v1_21

import com.esmpfun.antidupe.adapter.ServerAdapter
import com.esmpfun.antidupe.adapter.SulfurCubeContents
import com.esmpfun.antidupe.adapter.SulfurCubeSwallow
import org.bukkit.plugin.Plugin

/** Minecraft 1.21 to 26.1, and Spigot on any version. */
class Adapter1_21 : ServerAdapter {

    override val versions = "1.21 to 26.1 (also used on Spigot)"

    override val sulfurCubeContents: SulfurCubeContents? = null

    override fun listenForSulfurCubeSwallow(plugin: Plugin, onSwallow: SulfurCubeSwallow) {}
}
