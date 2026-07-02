package com.server.antidupe.net

import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * Builds the [TagStripAdapter] for the running server.
 *
 * There is a single, reflection-based implementation ([ReflectiveTagStripper]) that adapts to the
 * server version at runtime, so one jar covers the whole supported span (1.21.x / 26.x). If the
 * server's internals don't match what the stripper expects (an unforeseen build), construction
 * throws and we disable the feature cleanly — the rest of the plugin is unaffected.
 */
object TagStripAdapters {
    fun load(
        plugin: Plugin,
        logger: Logger,
        namespace: String,
        keys: Collection<String>,
        stripAll: Boolean = false,
        whitelistNamespaces: Collection<String> = emptyList(),
    ): TagStripAdapter? = try {
        ReflectiveTagStripper(plugin, logger, namespace, keys, stripAll, whitelistNamespaces)
    } catch (e: Throwable) {
        logger.warning("[TagStripper] unsupported server build (${e.message}) — feature disabled")
        null
    }
}
