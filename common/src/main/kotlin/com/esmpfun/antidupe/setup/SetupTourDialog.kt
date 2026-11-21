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
            body += text("setup.ui.cpu-use").colorIfAbsent(NamedTextColor.GRAY)
                .append(legacy.deserialize(Messages.colorize(it.color) + it.text))
        }
        body += currentLine(step)

        show(
            player,
            legacy.deserialize(Messages.msg("setup.ui.dialog-title", "step" to "${index + 1}", "total" to "${controller.stepCount}"))
                .colorIfAbsent(NamedTextColor.AQUA),
            body,
            actions(player, step, index),
        )
    }

    override fun complete(player: Player) {
        controller.markComplete()
        val base = base(text("setup.ui.complete-title").colorIfAbsent(NamedTextColor.GREEN), lines("setup.complete"))
        val done = button(player, text("setup.ui.done").colorIfAbsent(NamedTextColor.GREEN)) {}
        player.showDialog(Dialog.create { it.empty().base(base).type(DialogType.notice(done)) })
    }

    private fun text(key: String): Component = legacy.deserialize(Messages.msg(key))

    private fun lines(key: String): List<Component> =
        Messages.list(key).filter { it.isNotBlank() }.map { legacy.deserialize("§7$it") }

    private fun currentLine(step: SetupStep): Component = when (step) {
        is SetupStep.Toggle -> {
            val on = controller.isEnabled(step.configPath, step.default)
            val state = if (on) text("setup.ui.on").colorIfAbsent(NamedTextColor.GREEN)
            else text("setup.ui.off").colorIfAbsent(NamedTextColor.RED)
            text("setup.ui.currently").colorIfAbsent(NamedTextColor.GRAY).append(state)
        }
        is SetupStep.Choice -> {
            val value = controller.currentValue(step.configPath)
            val label = step.formatCurrent?.invoke(value) ?: value?.toString() ?: Messages.msg("setup.ui.not-set")
            text("setup.ui.currently").colorIfAbsent(NamedTextColor.GRAY)
                .append(legacy.deserialize(label).colorIfAbsent(NamedTextColor.WHITE))
        }
    }

    private fun actions(player: Player, step: SetupStep, index: Int): List<Action> {
        val list = mutableListOf<Action>()
        when (step) {
            is SetupStep.Toggle -> {
                val missingPlugin = step.requiresPlugin?.let { !Bukkit.getPluginManager().isPluginEnabled(it) } ?: false
                if (missingPlugin) {
                    val missingLabel = legacy.deserialize(
                        Messages.msg("setup.ui.requires-plugin-button", "plugin" to step.requiresPlugin)
                    ).colorIfAbsent(NamedTextColor.DARK_GRAY)
                    list += Action(missingLabel) {
                        player.sendMessage(Messages.msg("setup.requires-plugin", "plugin" to step.requiresPlugin))
                        render(player, index)
                    }
                } else {
                    list += Action(text("setup.ui.turn-on").colorIfAbsent(NamedTextColor.GREEN)) {
                        controller.applyToggle(step.configPath, true) {
                            runForPlayer(player) { next(player) }
                        }
                    }
                }
                list += Action(text("setup.ui.turn-off").colorIfAbsent(NamedTextColor.RED)) {
                    controller.applyToggle(step.configPath, false) {
                        runForPlayer(player) { next(player) }
                    }
                }
            }
            is SetupStep.Choice -> step.options.forEach { opt ->
                list += Action(text(opt.labelKey).colorIfAbsent(NamedTextColor.AQUA)) {
                    controller.applyChoice(step.configPath, opt.value) {
                        runForPlayer(player) { next(player) }
                    }
                }
            }
        }
        if (index > 0) list += Action(text("setup.ui.back").colorIfAbsent(NamedTextColor.GRAY)) { render(player, controller.back(player.uniqueId)) }
        list += Action(text("setup.ui.skip").colorIfAbsent(NamedTextColor.GRAY)) { next(player) }
        list += Action(text("setup.ui.pause").colorIfAbsent(NamedTextColor.YELLOW)) {
            controller.pause(player.uniqueId)
            player.sendMessage(Messages.msg("setup.paused"))
        }
        list += Action(text("setup.ui.stop").colorIfAbsent(NamedTextColor.RED)) {
            controller.stop(player.uniqueId)
            player.sendMessage(Messages.msg("setup.stopped"))
        }
        return list
    }

    private fun runForPlayer(player: Player, action: () -> Unit) {
        if (scheduler.isFolia) {
            scheduler.runForEntity(player, Runnable { if (player.isOnline) action() })
        } else {
            action()
        }
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
