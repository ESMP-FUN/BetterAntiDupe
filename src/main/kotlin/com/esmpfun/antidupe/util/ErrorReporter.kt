package com.esmpfun.antidupe.util

import com.esmpfun.antidupe.metrics.MetricsService
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
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
    private const val MAX_CAUSE_DEPTH = 10

    private var logger: Logger? = null
    private var metrics: () -> MetricsService? = { null }
    private var paths: List<Pair<String, String>> = emptyList()
    private val lastLogged = ConcurrentHashMap<String, Long>()
    private val lastReported = ConcurrentHashMap<String, Long>()

    fun init(logger: Logger, dataFolder: File, metrics: () -> MetricsService?) {
        this.logger = logger
        this.metrics = metrics
        // Longest first, so the plugin folder wins over the server folder inside it.
        this.paths = listOfNotNull(
            dataFolder.absolutePath to "plugins/${dataFolder.name}",
            dataFolder.parentFile?.parentFile?.absolutePath?.let { it to "." },
            System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { it to "~" },
            System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { it to "<temp>" }
        ).sortedByDescending { it.first.length }
        lastLogged.clear()
        lastReported.clear()
    }

    /** Releases the plugin, so a server reload can let go of the old copy of it. */
    fun shutdown() {
        logger = null
        metrics = { null }
        paths = emptyList()
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
            metrics()?.report(where, withoutPaths(t, MAX_CAUSE_DEPTH))
        }
    }

    /** Catches what a coroutine in this scope throws; without one it goes to the JVM's stderr. */
    fun handler(where: String): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, t -> report(where, t) }

    /**
     * Error reports carry no server or player detail, but a database or file error quotes the
     * path it failed on, and that names the hosting account. Stack traces hold class names only,
     * so the message is the one part that needs shortening.
     */
    internal fun withoutPaths(t: Throwable, depth: Int): Throwable {
        val cause = t.cause?.takeIf { it !== t && depth > 0 }?.let { withoutPaths(it, depth - 1) }
        val message = t.message
        val shortened = message?.let { shorten(it) }
        if (shortened == message && cause === t.cause) return t
        return ReportedFailure("${t.javaClass.name}: ${shortened ?: ""}", cause).also {
            it.stackTrace = t.stackTrace
        }
    }

    private fun shorten(message: String): String {
        var out = message
        for ((from, to) in paths) if (out.contains(from)) out = out.replace(from, to)
        return out
    }

    /** Carries a failure whose text has been shortened; the original type is in the message. */
    private class ReportedFailure(message: String, cause: Throwable?) : Exception(message, cause)

    private fun due(seen: ConcurrentHashMap<String, Long>, key: String, now: Long, interval: Long): Boolean {
        var fire = false
        seen.compute(key) { _, previous ->
            if (previous == null || now - previous >= interval) { fire = true; now } else previous
        }
        return fire
    }
}
