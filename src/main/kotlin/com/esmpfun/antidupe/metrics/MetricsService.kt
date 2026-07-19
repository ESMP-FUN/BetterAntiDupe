package com.esmpfun.antidupe.metrics

import dev.faststats.ErrorTracker
import dev.faststats.bukkit.BukkitContext
import dev.faststats.data.Metric
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Anonymous usage metrics via FastStats, plus opt-in error reporting.
 *
 * Nothing here identifies a server or a player: no IPs, names, UUIDs or item data — only which
 * features are switched on, so we can tell which parts of the plugin are actually used. FastStats
 * adds server software, Minecraft version, Java version and plugin version on its own.
 *
 * Two config switches, both under `metrics:` in config.yml:
 *   - `enabled` (default true)         — turns all transmission on or off.
 *   - `error_reporting` (default false) — attaches the error tracker. Off by default because
 *     stack traces are a different privacy proposition than counters; a server owner should
 *     choose to send them.
 *
 * The SDK's own opt-out is the `-Dfaststats.enabled=false` JVM flag, which realistically nobody
 * finds, hence the config keys above.
 *
 * Every entry point swallows its own failures. Telemetry must never be the reason an anti-dupe
 * plugin fails to start.
 */
class MetricsService private constructor(
    private val context: BukkitContext,
    private val logger: Logger
) {

    fun shutdown() {
        try {
            context.shutdown()
        } catch (e: Throwable) {
            logger.log(Level.FINE, "FastStats shutdown failed", e)
        }
    }

    companion object {
        /**
         * Project ingest token. Public by design — it ships inside the jar and can be read out of
         * it, exactly like a bStats plugin id. It is not a secret and grants no account access.
         */
        private const val TOKEN = "a18c8ce61660086181da8310cdbc7955"

        /** Returns null when metrics are disabled or the SDK could not start. */
        fun start(plugin: JavaPlugin, trackedMaterialCount: Int): MetricsService? {
            val config = plugin.config
            if (!config.getBoolean("metrics.enabled", true)) {
                plugin.logger.info("Metrics disabled in config — sending nothing")
                return null
            }

            return try {
                // Read every value up front. Metric suppliers are polled on a background thread
                // and must be cheap, thread-safe and pure, so none of them touch the live config.
                val backend = (config.getString("storage.backend", "SQLITE") ?: "SQLITE").uppercase()
                val language = config.getString("language", "en") ?: "en"
                val shadowMode = config.getBoolean("shadow_mode", true)
                val autoDelete = config.getBoolean("auto_delete_dupes", false)
                val hideTag = config.getBoolean("hide_tag_from_clients", true)
                val prevention = listOf(
                    "rail" to "prevent-rail-dupers",
                    "carpet" to "prevent-carpet-dupers",
                    "gravity" to "prevent-gravity-dupers",
                    "tnt" to "prevent-tnt-dupers",
                    "container-desync" to "prevent-container-desync-dupers",
                    "shutdown" to "prevent-shutdown-dupers"
                ).filter { (_, key) -> config.getBoolean(key, true) }
                    .map { (label, _) -> label }
                    .toTypedArray()

                val factory = BukkitContext.Factory(plugin, TOKEN)
                    .metrics { metrics ->
                        metrics
                            .addMetric(Metric.string("storage_backend") { backend })
                            .addMetric(Metric.string("language") { language })
                            .addMetric(Metric.bool("shadow_mode") { shadowMode })
                            .addMetric(Metric.bool("auto_delete_dupes") { autoDelete })
                            .addMetric(Metric.bool("hide_tag_from_clients") { hideTag })
                            .addMetric(Metric.stringArray("duper_prevention") { prevention })
                            .addMetric(Metric.number("tracked_materials") { trackedMaterialCount })
                            .create()
                    }

                val errorReporting = config.getBoolean("metrics.error_reporting", false)
                if (errorReporting) factory.errorTrackerService(buildErrorTracker())

                val context = factory.create()
                context.ready()

                plugin.logger.info(
                    "✓ Metrics enabled (anonymous)" +
                        if (errorReporting) " with error reporting" else ""
                )
                MetricsService(context, plugin.logger)
            } catch (e: Throwable) {
                // A telemetry outage, a repo hiccup or an SDK change must not take the plugin
                // down with it — log at FINE and carry on without metrics.
                plugin.logger.log(Level.FINE, "FastStats unavailable — metrics disabled", e)
                null
            }
        }

        /**
         * Scrubs anything that could tie a report back to a person or a machine before it leaves
         * the server. Stack traces are far likelier than counters to carry incidental data —
         * a player UUID in an exception message, a home directory in a file path — so the tracker
         * is only ever built when the owner has opted in, and even then it redacts.
         */
        private fun buildErrorTracker(): ErrorTracker = ErrorTracker.contextAware()
            .anonymize("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "[uuid hidden]")
            .anonymize("(?i)[A-Z]:\\\\Users\\\\[^\\\\]+", "[path hidden]")
            .anonymize("(?i)/home/[^/]+", "[path hidden]")
            .anonymize("(?i)(password|token|secret)\\s*[=:]\\s*\\S+", "$1=[redacted]")
    }
}
