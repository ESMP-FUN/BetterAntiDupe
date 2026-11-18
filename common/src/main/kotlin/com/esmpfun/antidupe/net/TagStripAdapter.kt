package com.esmpfun.antidupe.net

import org.bukkit.entity.Player

/**
 * Pure Bukkit API seam for tag concealment, so the plugin core never links a version-specific class.
 */
interface TagStripAdapter {
    /** Safe to call twice. */
    fun inject(player: Player)

    /** Safe to call if never injected or already gone. */
    fun eject(player: Player)
}
