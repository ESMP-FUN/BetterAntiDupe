package com.esmpfun.antidupe.ledger

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts, per player, item movements whose ledger entries are not written yet. A balance check
 * that lands inside that window sees items that already moved without the entry that explains
 * them, so checks wait for this to clear first.
 */
class SettleTracker(
    private val staleAfterMs: Long = 5_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class State {
        val pending = AtomicInteger()
        val version = AtomicLong()
        @Volatile var lastBegin = 0L
    }

    private val states = ConcurrentHashMap<UUID, State>()

    fun begin(player: UUID) {
        val state = states.computeIfAbsent(player) { State() }
        state.lastBegin = clock()
        state.version.incrementAndGet()
        state.pending.incrementAndGet()
    }

    /** Goes up with every movement, so a check can tell whether anything moved while it counted. */
    fun version(player: UUID): Long = states[player]?.version?.get() ?: 0L

    fun end(player: UUID) {
        val state = states[player] ?: return
        if (state.pending.decrementAndGet() < 0) state.pending.set(0)
    }

    /** Work older than [staleAfterMs] is ignored, so one missed [end] cannot blind the checks for good. */
    fun isSettling(player: UUID): Boolean {
        val state = states[player] ?: return false
        return state.pending.get() > 0 && clock() - state.lastBegin < staleAfterMs
    }

    fun prune() {
        val now = clock()
        states.entries.removeIf { now - it.value.lastBegin > staleAfterMs }
    }
}
