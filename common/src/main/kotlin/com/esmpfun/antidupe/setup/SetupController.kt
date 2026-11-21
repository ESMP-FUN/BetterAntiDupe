package com.esmpfun.antidupe.setup

import com.esmpfun.antidupe.platform.PlatformScheduler
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The engine behind `/adp setup`, shared by the Dialog and chat tours: each admin's position,
 * and applying a change with `config.set` plus `saveConfig` (Bukkit keeps the file's comments),
 * followed by [onChanged], the same reload `/adp reload` runs.
 */
class SetupController(
    private val plugin: JavaPlugin,
    private val scheduler: PlatformScheduler,
    val state: SetupState,
    private val onChanged: () -> Unit,
) {
    // In memory; saved only on Pause.
    private val progress = ConcurrentHashMap<UUID, Int>()

    val stepCount: Int get() = SetupCatalog.count
    fun stepAt(index: Int): SetupStep? = SetupCatalog.stepAt(index)
    fun currentIndex(uuid: UUID): Int = progress[uuid] ?: 0

    fun jumpTo(uuid: UUID, index: Int) { progress[uuid] = index.coerceIn(0, SetupCatalog.count - 1) }

    /** Begin (or restart) at the first step; ends the first-time reminders. */
    fun start(uuid: UUID) {
        progress[uuid] = 0
        markTouched()
    }

    /** Resume from a saved pause; returns the step to show (0 if none). */
    fun resume(uuid: UUID): Int {
        val idx = state.pausedIndex(uuid)?.coerceIn(0, SetupCatalog.count - 1) ?: 0
        progress[uuid] = idx
        state.clearPaused(uuid)
        markTouched()
        return idx
    }

    fun hasPaused(uuid: UUID): Boolean = state.pausedIndex(uuid) != null

    /** The next step, or null when the tour is done. */
    fun advance(uuid: UUID): Int? {
        val next = currentIndex(uuid) + 1
        if (next >= SetupCatalog.count) {
            progress.remove(uuid)
            markComplete()
            return null
        }
        progress[uuid] = next
        return next
    }

    fun back(uuid: UUID): Int {
        val prev = (currentIndex(uuid) - 1).coerceAtLeast(0)
        progress[uuid] = prev
        return prev
    }

    fun pause(uuid: UUID) {
        state.setPaused(uuid, currentIndex(uuid))
        progress.remove(uuid)
    }

    fun stop(uuid: UUID) {
        progress.remove(uuid)
        state.clearPaused(uuid)
        // Stopped early after the first reminders were used up: one gentle nudge a week from now.
        if (!state.completed &&
            state.reminderCount >= SetupReminderService.MAX_REMINDERS &&
            state.followUpEpoch == 0L
        ) {
            state.followUpEpoch = System.currentTimeMillis() + SetupReminderService.WEEK_MS
        }
    }

    fun markComplete() { state.completed = true }

    fun applyToggle(path: String, enabled: Boolean, onDone: () -> Unit = {}) = apply(path, enabled, onDone)

    fun applyChoice(path: String, value: Any, onDone: () -> Unit = {}) = apply(path, value, onDone)

    private fun apply(path: String, value: Any, onDone: () -> Unit) {
        val work = Runnable {
            plugin.config.set(path, value)
            plugin.saveConfig()
            onChanged()
            onDone()
        }
        if (scheduler.isFolia) scheduler.runMain(work) else work.run()
    }

    fun isEnabled(path: String, default: Boolean): Boolean = plugin.config.getBoolean(path, default)

    fun currentValue(path: String): Any? = plugin.config.get(path)

    fun markTouched() { if (!state.touched) state.touched = true }
}
