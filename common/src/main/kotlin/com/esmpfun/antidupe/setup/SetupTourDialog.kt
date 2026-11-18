package com.esmpfun.antidupe.setup

import com.esmpfun.antidupe.platform.PlatformScheduler
import com.esmpfun.antidupe.util.Messages
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * The tour as Paper Dialog screens, one per step. Only created once `SetupCommand` has found
 * the Dialog API, so Spigot and older servers never load it.
 */
class SetupTourDialog(
    private val scheduler: PlatformScheduler,
    private val controller: SetupController,
) : SetupTour {

    private val legacy = LegacyComponentSerializer.legacySection()

    private class Action(val label: Component, val handler: () -> Unit)

    override fun render(player: Player, index: Int) {
        val step = controller.stepAt(index) ?: run { complete(player); return }
        controller.jumpTo(player.uniqueId, index)

        val body = mutableListOf<Component>()
        body += text(step.titleKey).color(NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)
        body += lines(step.descKey)
        step.cpuImpact?.let {
            body += Component.text("CPU use: ", NamedTextColor.GRAY).append(legacy.deserialize(Messages.colorize(it.color) + it.text))
        }
        body += currentLine(step)

        show(
            player,
            Component.text("BetterAntiDupe Setup (${index + 1}/${controller.stepCount})", NamedTextColor.AQUA),
            body,
            actions(player, step, index),
        )
    }

    override fun complete(player: Player) {
        controller.markComplete()
        val base = base(Component.text("Setup complete", NamedTextColor.GREEN), lines("setup.complete"))
        val done = button(player, Component.text("Done", NamedTextColor.GREEN)) {}
        player.showDialog(Dialog.create { it.empty().base(base).type(DialogType.notice(done)) })
    }

    private fun text(key: String): Component = legacy.deserialize(Messages.msg(key))

    private fun lines(key: String): List<Component> =
        Messages.list(key).filter { it.isNotBlank() }.map { legacy.deserialize("§7$it") }

    private fun currentLine(step: SetupStep): Component = when (step) {
        is SetupStep.Toggle -> {
            val on = controller.isEnabled(step.configPath, step.default)
            Component.text("Currently: ", NamedTextColor.GRAY)
                .append(if (on) Component.text("On", NamedTextColor.GREEN) else Component.text("Off", NamedTextColor.RED))
        }
        is SetupStep.Choice -> {
            val value = controller.currentValue(step.configPath)
            val label = step.formatCurrent?.invoke(value) ?: value?.toString() ?: "default"
            Component.text("Currently: ", NamedTextColor.GRAY).append(Component.text(label, NamedTextColor.WHITE))
        }
    }

    private fun actions(player: Player, step: SetupStep, index: Int): List<Action> {
        val list = mutableListOf<Action>()
        when (step) {
            is SetupStep.Toggle -> {
                val missingPlugin = step.requiresPlugin?.let { !Bukkit.getPluginManager().isPluginEnabled(it) } ?: false
                if (missingPlugin) {
                    list += Action(Component.text("${step.requiresPlugin} is not installed", NamedTextColor.DARK_GRAY)) {
                        player.sendMessage(Messages.msg("setup.requires-plugin", "plugin" to step.requiresPlugin))
                        render(player, index)
                    }
                } else {
                    list += Action(Component.text("Turn on", NamedTextColor.GREEN)) {
                        controller.applyToggle(step.configPath, true); next(player)
                    }
                }
                list += Action(Component.text("Turn off", NamedTextColor.RED)) {
                    controller.applyToggle(step.configPath, false); next(player)
                }
            }
            is SetupStep.Choice -> step.options.forEach { opt ->
                list += Action(text(opt.labelKey).color(NamedTextColor.AQUA)) {
                    controller.applyChoice(step.configPath, opt.value); next(player)
                }
            }
        }
        if (index > 0) list += Action(Component.text("Back", NamedTextColor.GRAY)) { render(player, controller.back(player.uniqueId)) }
        list += Action(Component.text("Skip", NamedTextColor.GRAY)) { next(player) }
        list += Action(Component.text("Pause", NamedTextColor.YELLOW)) {
            controller.pause(player.uniqueId)
            player.sendMessage(Messages.msg("setup.paused"))
        }
        list += Action(Component.text("Stop", NamedTextColor.RED)) {
            controller.stop(player.uniqueId)
            player.sendMessage(Messages.msg("setup.stopped"))
        }
        return list
    }

    private fun next(player: Player) {
        val next = controller.advance(player.uniqueId)
        if (next == null) complete(player) else render(player, next)
    }

    private fun show(player: Player, title: Component, body: List<Component>, actions: List<Action>) {
        val buttons = actions.map { a -> button(player, a.label, a.handler) }
        val base = base(title, body)
        player.showDialog(Dialog.create { it.empty().base(base).type(DialogType.multiAction(buttons).build()) })
    }

    private fun base(title: Component, body: List<Component>): DialogBase =
        DialogBase.builder(title)
            .canCloseWithEscape(true)
            .body(body.map { DialogBody.plainMessage(it) })
            .build()

    // A click can arrive on another thread (Folia), so the answer runs on the player's own.
    private fun button(player: Player, label: Component, handler: () -> Unit): ActionButton =
        ActionButton.builder(label)
            .action(DialogAction.customClick({ _, _ -> scheduler.runForEntity(player, Runnable(handler)) }, ClickCallback.Options.builder().uses(1).build()))
            .build()
}
