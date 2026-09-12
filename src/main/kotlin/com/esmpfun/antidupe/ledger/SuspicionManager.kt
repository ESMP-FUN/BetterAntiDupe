package com.esmpfun.antidupe.ledger

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player suspicion, made of two parts:
 *
 *  - Earned floor, raised by deterministic detections and admin verdicts. Never decays on the
 *    idle timer; only an admin clear lowers it.
 *  - Transient heat, raised by low-confidence signals and eroded once a player goes quiet.
 *
 * Effective suspicion is floor plus heat, clamped to 0..100, and it lowers the alert threshold.
 * [sensitivity] runs 1..100, 50 being balanced, and scales every threshold.
 */
class SuspicionManager(@Volatile var sensitivity: Int = 50) {

    private class Score {
        @Volatile var floor: Double = 0.0
        @Volatile var heat: Double = 0.0
        @Volatile var lastSignal: Long = 0
    }

    private val scores = ConcurrentHashMap<UUID, Score>()

    companion object {
        const val DETERMINISTIC_FLOOR_BUMP = 25.0
        const val CONFIRM_FLOOR = 90.0
        const val HEAT_PER_SIGNAL = 1.0
        const val HEAT_DECAY_PER_TICK = 1.0         // removed per decay call, which runs every 5 min
        const val HEAT_REPEAT_WINDOW_MS = 60_000L   // a nudge only counts if another landed this recently
        const val IDLE_BEFORE_DECAY_MS = 300_000L
        const val MAX = 100.0
    }

    fun suspicion(player: UUID): Double {
        val s = scores[player] ?: return 0.0
        return (s.floor + s.heat).coerceIn(0.0, MAX)
    }

    fun floor(player: UUID): Double = scores[player]?.floor ?: 0.0

    fun bumpFloor(player: UUID, amount: Double = DETERMINISTIC_FLOOR_BUMP) {
        val s = scores.computeIfAbsent(player) { Score() }
        s.floor = (s.floor + amount).coerceIn(0.0, MAX)
        s.lastSignal = System.currentTimeMillis()
    }

    /** An isolated burst adds nothing; only a signal repeated inside the window accrues heat. */
    fun addHeat(player: UUID, amount: Double = HEAT_PER_SIGNAL) {
        val s = scores.computeIfAbsent(player) { Score() }
        val now = System.currentTimeMillis()
        if (now - s.lastSignal <= HEAT_REPEAT_WINDOW_MS) {
            s.heat = (s.heat + amount).coerceIn(0.0, MAX)
        }
        s.lastSignal = now
    }

    fun decay() {
        val now = System.currentTimeMillis()
        scores.forEach { (_, s) ->
            if (now - s.lastSignal >= IDLE_BEFORE_DECAY_MS && s.heat > 0) {
                s.heat = (s.heat - HEAT_DECAY_PER_TICK).coerceAtLeast(0.0)
            }
        }
        scores.entries.removeIf { it.value.floor == 0.0 && it.value.heat == 0.0 }
    }

    fun confirm(player: UUID) {
        val s = scores.computeIfAbsent(player) { Score() }
        s.floor = CONFIRM_FLOOR
    }

    fun clear(player: UUID) {
        scores.remove(player)
    }

    /** How much excess trips an alert. Higher sensitivity or suspicion both lower the bar. */
    fun effectiveThreshold(player: UUID, base: Int): Double {
        val sensFactor = (sensitivity.coerceIn(1, 100)) / 50.0
        val suspFactor = 1.0 + suspicion(player) / 100.0
        return (base / sensFactor / suspFactor).coerceAtLeast(1.0)
    }
}
