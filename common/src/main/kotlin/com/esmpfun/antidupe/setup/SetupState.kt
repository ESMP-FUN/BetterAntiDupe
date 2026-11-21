package com.esmpfun.antidupe.setup

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

/**
 * Saved tour progress in `setup-state.yml` (not config.yml, so no comments to keep):
 * whether the tour was touched or finished, the reminder counters, and each admin's
 * paused step so `/adp setup continue` works across restarts.
 */
class SetupState(private val dataFolder: File) {

    private val lock = Any()
    private val file = File(dataFolder, FILE_NAME)
    private val cfg = YamlConfiguration().apply { if (file.exists()) runCatching { load(file) } }

    /** Once true (the tour was opened), the first-time reminders stop for good. */
    var touched: Boolean
        get() = synchronized(lock) { cfg.getBoolean("touched", false) }
        set(v) = synchronized(lock) { cfg.set("touched", v); save() }

    var reminderCount: Int
        get() = synchronized(lock) { cfg.getInt("reminder.count", 0) }
        set(v) = synchronized(lock) { cfg.set("reminder.count", v); save() }

    var lastReminderEpoch: Long
        get() = synchronized(lock) { cfg.getLong("reminder.last-epoch", 0L) }
        set(v) = synchronized(lock) { cfg.set("reminder.last-epoch", v); save() }

    var completed: Boolean
        get() = synchronized(lock) { cfg.getBoolean("completed", false) }
        set(v) = synchronized(lock) { cfg.set("completed", v); save() }

    /** When set (epoch ms), one "you started but didn't finish" nudge is due after it. 0 = none. */
    var followUpEpoch: Long
        get() = synchronized(lock) { cfg.getLong("follow-up-epoch", 0L) }
        set(v) = synchronized(lock) { cfg.set("follow-up-epoch", v); save() }

    var followUpShown: Boolean
        get() = synchronized(lock) { cfg.getBoolean("follow-up-shown", false) }
        set(v) = synchronized(lock) { cfg.set("follow-up-shown", v); save() }

    fun pausedIndex(uuid: UUID): Int? = synchronized(lock) {
        if (cfg.contains("paused.$uuid")) cfg.getInt("paused.$uuid") else null
    }

    fun setPaused(uuid: UUID, index: Int) = synchronized(lock) { cfg.set("paused.$uuid", index); save() }

    fun clearPaused(uuid: UUID) = synchronized(lock) { cfg.set("paused.$uuid", null); save() }

    private fun save() {
        runCatching {
            if (!dataFolder.exists()) dataFolder.mkdirs()
            cfg.save(file)
        }
    }

    companion object {
        const val FILE_NAME = "setup-state.yml"
    }
}
