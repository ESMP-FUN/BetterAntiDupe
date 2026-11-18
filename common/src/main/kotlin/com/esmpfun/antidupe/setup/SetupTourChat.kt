package com.esmpfun.antidupe.setup

import com.esmpfun.antidupe.util.Chat
import com.esmpfun.antidupe.util.Chat.sendChat
import com.esmpfun.antidupe.util.Messages
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * The clickable-chat tour, used on Spigot and wherever Paper's Dialog API is missing. Each
 * button is an `/adp setup <action> <step>` command, so a step renders from its number alone.
 */
class SetupTourChat(private val controller: SetupController) : SetupTour {

    override fun render(player: Player, index: Int) {
        val step = controller.stepAt(index) ?: run { complete(player); return }
        val line = Chat.line()
        line.text("§3§m                    §r §6Setup §7(${index + 1}/${controller.stepCount}) §3§m                    ").newline()
        line.text("§e§l" + Messages.msg(step.titleKey)).newline()
        Messages.list(step.descKey).forEach { line.text("§7$it").newline() }
        step.cpuImpact?.let { line.text("§7CPU use: " + Messages.colorize(it.color) + it.text).newline() }
        line.newline().text(currentLine(step)).newline()
        buttons(line, step, index)
        player.sendChat(line.build())
    }

    override fun complete(player: Player) {
        controller.markComplete()
        Messages.list("setup.complete").forEach { player.sendMessage(it) }
    }

    fun intro(player: Player) {
        Messages.list("setup.intro").forEach { player.sendMessage(it) }
    }

    private fun currentLine(step: SetupStep): String = when (step) {
        is SetupStep.Toggle -> "§7Currently: " + if (controller.isEnabled(step.configPath, step.default)) "§aOn" else "§cOff"
        is SetupStep.Choice -> {
            val value = controller.currentValue(step.configPath)
            "§7Currently: §f" + (step.formatCurrent?.invoke(value) ?: value?.toString() ?: "default")
        }
    }

    private fun buttons(line: Chat.Builder, step: SetupStep, index: Int) {
        when (step) {
            is SetupStep.Toggle -> {
                val missingPlugin = step.requiresPlugin?.let { !Bukkit.getPluginManager().isPluginEnabled(it) } ?: false
                if (missingPlugin) line.text("§8[${step.requiresPlugin} is not installed]")
                else line.clickRunCommand("§a[Turn on]", "/adp setup enable $index", "Turn this on")
                line.text(" ").clickRunCommand("§c[Turn off]", "/adp setup disable $index", "Turn this off")
            }
            is SetupStep.Choice -> step.options.forEach { opt ->
                line.clickRunCommand("§b[" + Messages.msg(opt.labelKey) + "]", "/adp setup set $index ${opt.optionId}", "Use this").text(" ")
            }
        }
        line.newline()
        if (index > 0) line.clickRunCommand("§7[Back]", "/adp setup prev $index", "Back one question").text("  ")
        line.clickRunCommand("§7[Skip]", "/adp setup skip $index", "Leave it as it is and go on").newline()
        line.clickRunCommand("§e[Pause]", "/adp setup pause $index", "Carry on later with /adp setup continue")
        line.text("  ").clickRunCommand("§c[Stop]", "/adp setup stop", "End the tour")
    }
}
