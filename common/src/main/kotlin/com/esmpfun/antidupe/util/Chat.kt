@file:Suppress("DEPRECATION")
// The BungeeCord chat API is deprecated on Paper in favour of Adventure, but Adventure is not
// on Spigot; this one path works on all three platforms and the deprecation is docs-only.
package com.esmpfun.antidupe.util

import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ComponentBuilder
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.command.CommandSender

object Chat {

    fun line(): Builder = Builder()

    /** The console receives these as plain text. */
    fun CommandSender.sendChat(components: Array<BaseComponent>) {
        @Suppress("DEPRECATION")
        spigot().sendMessage(*components)
    }

    class Builder {
        private val cb = ComponentBuilder("")

        fun text(legacy: String): Builder {
            val parts = TextComponent.fromLegacyText(legacy)
            cb.append(parts, ComponentBuilder.FormatRetention.NONE)
            return this
        }

        fun newline(): Builder {
            cb.append("\n", ComponentBuilder.FormatRetention.NONE)
            return this
        }

        /**
         * [command] must be a plugin command: since MC 1.21.6 the client shows a permissions
         * confirmation dialog for click-events that run vanilla commands such as `/tp`.
         */
        fun clickRunCommand(
            display: String,
            command: String,
            hover: String = "Click to run"
        ): Builder {
            val parts = TextComponent.fromLegacyText(display)
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
