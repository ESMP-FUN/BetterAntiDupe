package com.esmpfun.antidupe.setup

import com.esmpfun.antidupe.platform.PlatformScheduler
import com.esmpfun.antidupe.util.Chat
import com.esmpfun.antidupe.util.Chat.sendChat
import com.esmpfun.antidupe.util.Messages
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * A gentle hint toward `/adp setup` when an admin joins: at most weekly and [MAX_REMINDERS]
 * times, never again once the tour is opened, plus one follow-up a week after a Stop.
 * Switched off with `setup.reminder.enabled`.
 */
class SetupReminderService(
    private val plugin: JavaPlugin,
    private val scheduler: PlatformScheduler,
    private val state: SetupState,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (!plugin.config.getBoolean("setup.reminder.enabled", true)) return
        if (state.completed) return
        val player = event.player
        if (!player.hasPermission(PERMISSION)) return
        val now = System.currentTimeMillis()

        if (state.touched) {
            if (state.followUpEpoch != 0L && !state.followUpShown && now >= state.followUpEpoch) {
                send(player) {
                    player.sendChat(hint("setup.reminder-resume", "setup.reminder-resume-button", "/adp setup continue"))
                    state.followUpShown = true
                }
            }
            return
        }

        if (state.reminderCount >= MAX_REMINDERS) return
        if (now - state.lastReminderEpoch < WEEK_MS) return
        send(player) {
            player.sendChat(hint("setup.reminder", "setup.reminder-button", "/adp setup"))
            state.reminderCount = state.reminderCount + 1
            state.lastReminderEpoch = now
        }
    }

    private fun hint(textKey: String, buttonKey: String, command: String) =
        Chat.line().text(Messages.msg(textKey) + " ").clickRunCommand(Messages.msg(buttonKey), command, Messages.msg("setup.ui.reminder-hover")).build()

    /** A second later, once the join has settled, on the player's own region thread. */
    private fun send(player: Player, action: () -> Unit) {
        scheduler.runForEntityLater(player, 20L, Runnable {
            if (player.isOnline) action()
        })
    }

    companion object {
        const val PERMISSION = "antidupe.admin.setup"
        const val MAX_REMINDERS = 3
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
