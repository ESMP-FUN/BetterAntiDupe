package com.esmpfun.antidupe.setup

import org.bukkit.entity.Player

/** A way of showing the tour: Paper's Dialog screens or clickable chat. */
interface SetupTour {
    fun render(player: Player, index: Int)
    fun complete(player: Player)
}
