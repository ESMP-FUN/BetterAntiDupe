@file:Suppress("DEPRECATION")
// Uses net.md_5.bungee.api.chat (the Spigot/BungeeCord chat API). Paper deprecates this
// in favour of Adventure, but Adventure isn't available on Spigot and we want one code
// path that works on all three platforms (Spigot, Paper, Folia). The BungeeCord API
// remains functional everywhere and the deprecation is documentation-only on Paper.
package com.server.antidupe.util

import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ComponentBuilder
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.command.CommandSender

/**
 * Small helper around Bukkit/Spigot's BaseComponent API. Works identically on Spigot, Paper
 * and Folia without pulling Adventure or MiniMessage as a runtime dependency.
 *
 * Usage:
 *   val msg = Chat.line()
 *       .text("§7Stashed §e16x §fDIAMOND_BLOCK §7at ")
 *       .clickToTp("§b[overworld 100, 64, -200]", "world", 100, 64, -200,
 *                  hover = "Click to teleport")
 *       .build()
 *   sender.sendChat(msg)
 */
object Chat {

    fun line(): Builder = Builder()

    /**
     * Resolve a Bukkit world name to the namespaced dimension key `/execute in` expects
     * (e.g. world_nether -> minecraft:the_nether). Falls back to a lowercased guess when
     * the world isn't loaded or `World.getKey()` is unavailable (Spigot).
     */
    fun worldKeyOf(worldName: String): String {
        return try {
            org.bukkit.Bukkit.getWorld(worldName)?.key?.toString()
        } catch (e: Throwable) {
            null  // Spigot: World.getKey() doesn't exist
        } ?: "minecraft:${worldName.lowercase()}"
    }

    /** Send a clickable component message via the Spigot API. Console gets a plain-text fallback. */
    fun CommandSender.sendChat(components: Array<BaseComponent>) {
        // CommandSender.Spigot exists on Spigot and Paper; sends to console as plain text too.
        @Suppress("DEPRECATION")
        spigot().sendMessage(*components)
    }

    class Builder {
        private val cb = ComponentBuilder("")

        fun text(legacy: String): Builder {
            // Honour §-style colour codes in the source string.
            val parts = TextComponent.fromLegacyText(legacy)
            cb.append(parts, ComponentBuilder.FormatRetention.NONE)
            return this
        }

        fun newline(): Builder {
            cb.append("\n", ComponentBuilder.FormatRetention.NONE)
            return this
        }

        /**
         * Add a clickable segment that fires `/tp <x> <y> <z>` when clicked. Uses
         * `/execute in <dimensionKey> run tp @s <x> <y> <z>` so cross-world teleports also work.
         *
         * [dimensionKey] must be the full namespaced dimension key (e.g. `minecraft:the_nether`),
         * NOT the Bukkit world name — for the default nether/end and renamed custom worlds the
         * two differ, and `/execute in` only accepts the key. See [worldKeyOf].
         */
        fun clickToTp(
            display: String,
            dimensionKey: String,
            x: Int, y: Int, z: Int,
            hover: String = "Click to teleport here"
        ): Builder {
            val command = "/execute in $dimensionKey run tp @s $x $y $z"
            val parts = TextComponent.fromLegacyText(display)
            // Apply the click + hover to every part of the legacy text.
            for (part in parts) {
                part.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, command)
                part.hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(hover))
            }
            cb.append(parts, ComponentBuilder.FormatRetention.NONE)
            return this
        }

        fun build(): Array<BaseComponent> = cb.create()
    }
}
