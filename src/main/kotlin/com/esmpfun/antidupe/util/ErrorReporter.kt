package com.esmpfun.antidupe.util

import com.esmpfun.antidupe.metrics.MetricsService
import kotlinx.coroutines.CoroutineExceptionHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * One route for every failure that is not already handled where it happens. Both sinks are
 * throttled per site, because the failures worth catching here are the ones that repeat every
 * tick: a full disk, an unreachable database.
 */
object ErrorReporter {

    private const val LOG_INTERVAL_MS = 60_000L
    private const val REPORT_INTERVAL_MS = 600_000L

    private var logger: Logger? = null
    private var metrics: () -> MetricsService? = { null }
    private val lastLogged = ConcurrentHashMap<String, Long>()
    private val lastReported = ConcurrentHashMap<String, Long>()

    fun init(logger: Logger, metrics: () -> MetricsService?) {
        this.logger = logger
        this.metrics = metrics
        lastLogged.clear()
        lastReported.clear()
    }

    /** Releases the plugin, so a server reload can let go of the old copy of it. */
    fun shutdown() {
        logger = null
        metrics = { null }
        lastLogged.clear()
        lastReported.clear()
    }

    /** [where] is a fixed label, never a player, world or file name: it is the throttle key. */
    fun report(where: String, t: Throwable) {
        val now = System.currentTimeMillis()
        if (due(lastLogged, where, now, LOG_INTERVAL_MS)) {
            logger?.log(Level.WARNING, "Something went wrong in $where", t)
        }
        if (due(lastReported, where, now, REPORT_INTERVAL_MS)) {
            metrics()?.report(where, t)
        }
    }

    /** Catches what a coroutine in this scope throws; without one it goes to the JVM's stderr. */
    fun handler(where: String): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, t -> report(where, t) }

    private fun due(seen: ConcurrentHashMap<String, Long>, key: String, now: Long, interval: Long): Boolean {
        var fire = false
        seen.compute(key) { _, previous ->
            if (previous == null || now - previous >= interval) { fire = true; now } else previous
        }
        return fire
    }
}
