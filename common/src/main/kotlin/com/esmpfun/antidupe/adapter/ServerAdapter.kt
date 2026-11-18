package com.esmpfun.antidupe.adapter

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** The parts of BetterAntiDupe that differ between the servers it runs on. */
interface ServerAdapter {
    /** Which servers this adapter is for, as the startup log names them. */
    val versions: String

    /** What a Bucket of Sulfur Cube holds; null where the server has no way to read it. */
    val sulfurCubeContents: SulfurCubeContents?

    /** Calls [onSwallow] each time a player feeds a sulfur cube; does nothing where the server has no such event. */
    fun listenForSulfurCubeSwallow(plugin: Plugin, onSwallow: SulfurCubeSwallow)
}

interface SulfurCubeContents {
    fun absorbedItem(bucket: ItemStack): ItemStack?

    /** False when the server refused, leaving [bucket] as it was. */
    fun setAbsorbedItem(bucket: ItemStack, inner: ItemStack): Boolean
}

fun interface SulfurCubeSwallow {
    fun swallowed(player: Player, cube: Entity, oldItem: ItemStack?, newItem: ItemStack)
}
