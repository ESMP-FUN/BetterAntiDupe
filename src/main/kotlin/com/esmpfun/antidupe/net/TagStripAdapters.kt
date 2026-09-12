package com.esmpfun.antidupe.net

import org.bukkit.plugin.Plugin
import java.util.logging.Logger

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
        logger.warning("[TagStripper] unsupported server build (${e.message}) - feature disabled")
        null
    }
}
