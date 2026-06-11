package com.server.antidupe.ledger

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player suspicion, modelling darkstarworks' design:
 *
 *  - **Earned floor** — raised by deterministic detections and admin-confirmed verdicts.
 *    Does NOT decay on the idle timer; only an admin `clear` lowers it. A confirmed duper
 *    stays watched even while "being brave" and idle.
 *  - **Transient heat** — raised by low-confidence signals (TMAR bursts, witness solo-patterns).
 *    Decays on the idle timer so noise doesn't accumulate forever.
 *
 * A player's effective suspicion is `clamp(floor + heat, 0, 100)`. It feeds the alert threshold:
 * the more suspicious a player, the smaller a discrepancy needs to be to alert.
 *
 * Global [sensitivity] (1..100, 50 = balanced) scales every threshold: higher = more paranoid.
 */
class SuspicionManager(@Volatile var sensitivity: Int = 50) {

    private class Score {
        @Volatile var floor: Double = 0.0
        @Volatile var heat: Double = 0.0
        @Volatile var lastSignal: Long = 0
    }

    private val scores = ConcurrentHashMap<UUID, Score>()

    companion object {
        const val DETERMINISTIC_FLOOR_BUMP = 25.0   // a known-real hit
        const val CONFIRM_FLOOR = 90.0              // admin confirms a duper
        const val HEAT_PER_SIGNAL = 1.0             // a low-confidence nudge
        const val HEAT_DECAY_PER_TICK = 1.0         // removed per idle decay tick (5 min)
        const val HEAT_REPEAT_WINDOW_MS = 60_000L   // low-confidence nudges only count on repeat within this
        const val IDLE_BEFORE_DECAY_MS = 300_000L   // heat only erodes after 5 min without signals
        const val MAX = 100.0
    }

    fun suspicion(player: UUID): Double {
        val s = scores[player] ?: return 0.0
        return (s.floor + s.heat).coerceIn(0.0, MAX)
    }

    fun floor(player: UUID): Double = scores[player]?.floor ?: 0.0

    /** A deterministic detection (entity-UUID reuse) or repeated confirmed pattern. Permanent-ish. */
    fun bumpFloor(player: UUID, amount: Double = DETERMINISTIC_FLOOR_BUMP) {
        val s = scores.getOrPut(player) { Score() }
        s.floor = (s.floor + amount).coerceIn(0.0, MAX)
        s.lastSignal = System.currentTimeMillis()
    }

    /**
     * A low-confidence signal (TMAR, witness pattern). Only accrues heat if another signal landed
     * recently — a single isolated burst is ignored, a *sustained* pattern accumulates.
     */
    fun addHeat(player: UUID, amount: Double = HEAT_PER_SIGNAL) {
        val s = scores.getOrPut(player) { Score() }
        val now = System.currentTimeMillis()
        if (now - s.lastSignal <= HEAT_REPEAT_WINDOW_MS) {
            s.heat = (s.heat + amount).coerceIn(0.0, MAX)
        }
        s.lastSignal = now
    }

    /** Idle decay — erodes transient heat only, never the earned floor. Call periodically. */
    fun decay() {
        val now = System.currentTimeMillis()
        scores.forEach { (_, s) ->
            // Only decay players who have been quiet — an active pattern keeps its heat.
            // (The previous `>= 0` check was vacuously true and eroded heat on every tick,
            // so sustained low-confidence patterns could never accumulate.)
            if (now - s.lastSignal >= IDLE_BEFORE_DECAY_MS && s.heat > 0) {
                s.heat = (s.heat - HEAT_DECAY_PER_TICK).coerceAtLeast(0.0)
            }
        }
        // Evict fully-cooled players so the map doesn't grow forever.
        scores.entries.removeIf { it.value.floor == 0.0 && it.value.heat == 0.0 }
    }

    /** Admin confirmed this player is duping — pin the floor high so future hits trip easily. */
    fun confirm(player: UUID) {
        val s = scores.getOrPut(player) { Score() }
        s.floor = CONFIRM_FLOOR
    }

    /** Admin cleared this player (false positive) — drop the earned floor and the heat. */
    fun clear(player: UUID) {
        scores.remove(player)
    }

    /**
     * The effective excess threshold for an alert, given a base threshold and the player's
     * current suspicion. Higher sensitivity and higher suspicion both lower the bar.
     *
     *   threshold = base / (sensitivity/50) / (1 + suspicion/100)
     *
     * A balanced server (sensitivity 50) with a clean player (suspicion 0) keeps the base; a
     * paranoid server or a flagged player needs far less excess to alert.
     */
    fun effectiveThreshold(player: UUID, base: Int): Double {
        val sensFactor = (sensitivity.coerceIn(1, 100)) / 50.0
        val suspFactor = 1.0 + suspicion(player) / 100.0
        return (base / sensFactor / suspFactor).coerceAtLeast(1.0)
    }

    /** Multiplier in [0.5 .. ~3] that widens source-bound match radius/window as leniency rises. */
    fun leniencyScale(): Double {
        // sensitivity 1 → ~2.0 (very lenient, wide windows); 50 → 1.0; 100 → ~0.5 (tight).
        return (100.0 / (sensitivity.coerceIn(1, 100) + 50.0))
    }
}
