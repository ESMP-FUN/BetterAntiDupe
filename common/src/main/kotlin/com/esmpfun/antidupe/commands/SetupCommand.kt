package com.esmpfun.antidupe.commands

import com.esmpfun.antidupe.platform.PlatformScheduler
import com.esmpfun.antidupe.setup.SetupController
import com.esmpfun.antidupe.setup.SetupReminderService
import com.esmpfun.antidupe.setup.SetupStep
import com.esmpfun.antidupe.setup.SetupTour
import com.esmpfun.antidupe.setup.SetupTourChat
import com.esmpfun.antidupe.setup.SetupTourDialog
import com.esmpfun.antidupe.util.Messages
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/adp setup`, the opt-in settings tour: `/adp setup` starts it, `/adp setup continue` resumes
 * a paused one. The chat buttons call back with enable|disable|skip|prev|pause <step>,
 * set <step> <option> and stop.
 */
class SetupCommand(
    scheduler: PlatformScheduler,
    private val controller: SetupController,
) {
    private val chatTour = SetupTourChat(controller)

    // The check lives here, not on SetupTourDialog: naming that class at all would make the
    // JVM link it, and on a server without the Dialog API linking it fails.
    private val dialogTour: SetupTour? =
        if (runCatching { Class.forName("io.papermc.paper.dialog.Dialog") }.isSuccess) {
            SetupTourDialog(scheduler, controller)
        } else null

    fun execute(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(SetupReminderService.PERMISSION)) {
            sender.sendMessage(Messages.msg("commands.no-permission-admin")); return
        }
        val player = sender as? Player ?: run {
            sender.sendMessage(Messages.msg("setup.player-only")); return
        }

        when (args.getOrNull(0)?.lowercase()) {
            null, "start" -> startTour(player)
            "continue", "resume" -> continueTour(player)
            "enable" -> applyToggle(player, args, enabled = true)
            "disable" -> applyToggle(player, args, enabled = false)
            "skip" -> indexArg(player, args)?.let { renderNext(player, it) }
            "set" -> applyChoice(player, args)
            "prev" -> indexArg(player, args)?.let { chatTour.render(player, (it - 1).coerceAtLeast(0)) }
            "pause" -> indexArg(player, args)?.let {
                controller.jumpTo(player.uniqueId, it)
                controller.pause(player.uniqueId)
                player.sendMessage(Messages.msg("setup.paused"))
            }
            "stop" -> {
                controller.stop(player.uniqueId)
                player.sendMessage(Messages.msg("setup.stopped"))
            }
            else -> player.sendMessage(Messages.msg("setup.usage"))
        }
    }

    private fun startTour(player: Player) {
        controller.start(player.uniqueId)
        render(player, 0, intro = true)
    }

    private fun continueTour(player: Player) {
        if (!controller.hasPaused(player.uniqueId)) { startTour(player); return }
        render(player, controller.resume(player.uniqueId), intro = false)
    }

    private fun applyToggle(player: Player, args: List<String>, enabled: Boolean) {
        val index = indexArg(player, args) ?: return
        (controller.stepAt(index) as? SetupStep.Toggle)?.let { controller.applyToggle(it.configPath, enabled) }
        renderNext(player, index)
    }

    private fun applyChoice(player: Player, args: List<String>) {
        val index = indexArg(player, args) ?: return
        val optionId = args.getOrNull(2) ?: run { player.sendMessage(Messages.msg("setup.missing-option")); return }
        val choice = controller.stepAt(index) as? SetupStep.Choice
        val opt = choice?.options?.firstOrNull { it.optionId == optionId }
        if (choice != null && opt != null) controller.applyChoice(choice.configPath, opt.value)
        renderNext(player, index)
    }

    private fun indexArg(player: Player, args: List<String>): Int? {
        val i = args.getOrNull(1)?.toIntOrNull()
        if (i == null || i < 0 || i >= controller.stepCount) {
            player.sendMessage(Messages.msg("setup.invalid-step")); return null
        }
        return i
    }

    /** The chat buttons carry on in chat. */
    private fun renderNext(player: Player, index: Int) {
        val next = index + 1
        if (next >= controller.stepCount) chatTour.complete(player) else chatTour.render(player, next)
    }

    private fun render(player: Player, index: Int, intro: Boolean) {
        val tour = dialogTour
        if (tour != null) {
            tour.render(player, index)
        } else {
            if (intro) chatTour.intro(player)
            chatTour.render(player, index)
        }
    }

    companion object {
        val SUBCOMMANDS = listOf("start", "continue", "stop")
    }
}
