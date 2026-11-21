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
        line.text(Messages.msg("setup.ui.chat-header", "step" to "${index + 1}", "total" to "${controller.stepCount}")).newline()
        line.text("§e§l" + Messages.msg(step.titleKey)).newline()
        Messages.list(step.descKey).forEach { line.text("§7$it").newline() }
        step.cpuImpact?.let { line.text("§7" + Messages.msg("setup.ui.cpu-use") + Messages.colorize(it.color) + it.text).newline() }
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
        is SetupStep.Toggle -> "§7" + Messages.msg("setup.ui.currently") +
            if (controller.isEnabled(step.configPath, step.default)) "§a" + Messages.msg("setup.ui.on")
            else "§c" + Messages.msg("setup.ui.off")
        is SetupStep.Choice -> {
            val value = controller.currentValue(step.configPath)
            "§7" + Messages.msg("setup.ui.currently") + "§f" +
                (step.formatCurrent?.invoke(value) ?: value?.toString() ?: Messages.msg("setup.ui.not-set"))
        }
    }

    private fun buttons(line: Chat.Builder, step: SetupStep, index: Int) {
        when (step) {
            is SetupStep.Toggle -> {
                val missingPlugin = step.requiresPlugin?.let { !Bukkit.getPluginManager().isPluginEnabled(it) } ?: false
                if (missingPlugin) {
                    line.text("§8[" + Messages.msg("setup.ui.requires-plugin-button", "plugin" to step.requiresPlugin) + "]")
                } else {
                    line.clickRunCommand("§a[" + Messages.msg("setup.ui.turn-on") + "]", "/adp setup enable $index", Messages.msg("setup.ui.turn-on-hover"))
                }
                line.text(" ").clickRunCommand("§c[" + Messages.msg("setup.ui.turn-off") + "]", "/adp setup disable $index", Messages.msg("setup.ui.turn-off-hover"))
            }
            is SetupStep.Choice -> step.options.forEach { opt ->
                line.clickRunCommand("§b[" + Messages.msg(opt.labelKey) + "]", "/adp setup set $index ${opt.optionId}", Messages.msg("setup.ui.use-hover")).text(" ")
            }
        }
        line.newline()
        if (index > 0) line.clickRunCommand("§7[" + Messages.msg("setup.ui.back") + "]", "/adp setup prev $index", Messages.msg("setup.ui.back-hover")).text("  ")
        line.clickRunCommand("§7[" + Messages.msg("setup.ui.skip") + "]", "/adp setup skip $index", Messages.msg("setup.ui.skip-hover")).newline()
        line.clickRunCommand("§e[" + Messages.msg("setup.ui.pause") + "]", "/adp setup pause $index", Messages.msg("setup.ui.pause-hover"))
        line.text("  ").clickRunCommand("§c[" + Messages.msg("setup.ui.stop") + "]", "/adp setup stop", Messages.msg("setup.ui.stop-hover"))
    }
}
