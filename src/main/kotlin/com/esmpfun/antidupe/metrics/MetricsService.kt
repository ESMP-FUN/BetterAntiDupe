package com.esmpfun.antidupe.metrics

import dev.faststats.Attributes
import dev.faststats.ErrorTracker
import dev.faststats.bukkit.BukkitContext
import dev.faststats.data.Metric
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Anonymous usage metrics via FastStats, plus opt-in error reporting.
 *
 * Nothing sent identifies a server or a player: no IPs, names, UUIDs or item data, only aggregate
 * counts and which features are switched on.
 */
class MetricsService private constructor(
    private val context: BukkitContext,
    private val errorTracker: ErrorTracker?,
    private val logger: Logger
) {

    /**
     * [where] is a short fixed label that groups reports; it must never contain a player name,
     * a world name or any other per-server detail.
     */
    fun report(where: String, t: Throwable) {
        val tracker = errorTracker ?: return
        try {
            tracker.trackError(t)
                .handled(true)
                .attributes(Attributes.empty().put("where", where))
        } catch (e: Throwable) {
            logger.log(Level.FINE, "FastStats error report failed", e)
        }
    }

    fun shutdown() {
        try {
            context.shutdown()
        } catch (e: Throwable) {
            logger.log(Level.FINE, "FastStats shutdown failed", e)
        }
    }

    companion object {
        /** Public by design: it ships inside the jar, is not a secret and grants no account access. */
        private const val TOKEN = "a18c8ce61660086181da8310cdbc7955"

        /**
         * [trackedMaterialCount] is a supplier because this starts before Chain of Custody loads,
         * and it is polled on the metrics thread.
         */
        fun start(plugin: JavaPlugin, trackedMaterialCount: () -> Int): MetricsService? {
            val config = plugin.config
            // A /reload keeps this object and its counts; start each run from zero.
            DetectionCounters.reset()
            if (!config.getBoolean("metrics.enabled", true)) {
                plugin.logger.info("Metrics disabled in config — sending nothing")
                return null
            }

            return try {
                // Metric suppliers run on a background thread, so read every value up front
                // rather than touching the live config from inside one.
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
                            .addMetric(Metric.number("tracked_materials") { trackedMaterialCount() })
                            // Aggregate counts only, nothing that identifies a server or a player.
                            .addMetric(Metric.number("detections") { DetectionCounters.detections() })
                            .addMetric(Metric.numberMap("detections_by_type") { DetectionCounters.byType() })
                            .addMetric(Metric.numberMap("detections_by_severity") { DetectionCounters.bySeverity() })
                            .addMetric(Metric.numberMap("detections_by_material") { DetectionCounters.byMaterial() })
                            .addMetric(Metric.number("items_removed") { DetectionCounters.itemsRemoved() })
                            .addMetric(Metric.numberMap("items_removed_by_material") { DetectionCounters.itemsRemovedByMaterial() })
                            .addMetric(Metric.numberMap("prevention_blocks") { DetectionCounters.preventionBlocks() })
                            // Only runs after an upload the server accepted, so a failed send
                            // carries its counts into the next cycle.
                            .onFlush { DetectionCounters.reset() }
                            .create()
                    }

                val errorReporting = config.getBoolean("metrics.error_reporting", true)
                val errorTracker = if (errorReporting) buildErrorTracker() else null
                if (errorTracker != null) factory.errorTrackerService(errorTracker)

                val context = factory.create()
                context.ready()

                plugin.logger.info(
                    "✓ Metrics enabled (anonymous)" +
                        if (errorReporting) " with error reporting" else ""
                )
                MetricsService(context, errorTracker, plugin.logger)
            } catch (e: Throwable) {
                plugin.logger.log(Level.FINE, "FastStats unavailable — metrics disabled", e)
                null
            }
        }

        /** Scrubs anything that could tie a report back to a person or a machine before it leaves the server. */
        private fun buildErrorTracker(): ErrorTracker = ErrorTracker.contextAware()
            .anonymize("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "[uuid hidden]")
            .anonymize("(?i)[A-Z]:\\\\Users\\\\[^\\\\]+", "[path hidden]")
            .anonymize("(?i)/home/[^/]+", "[path hidden]")
            .anonymize("(?i)(password|token|secret)\\s*[=:]\\s*\\S+", "$1=[redacted]")
    }
}
