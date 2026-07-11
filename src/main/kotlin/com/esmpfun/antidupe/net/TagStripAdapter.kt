package com.esmpfun.antidupe.net

import org.bukkit.entity.Player

/**
 * Version-neutral seam for the client-side tag concealment feature.
 *
 * The implementation that actually rewrites packets is necessarily coupled to one server
 * version's internals (NMS packet types, the component API). To support a span that crosses a
 * versioning-scheme change — 1.21.11 through 26.1.x / 26.2.x — each supported line ships its own
 * implementation of THIS interface, compiled against that line's paperweight dev bundle, in its
 * own Gradle module. At runtime [TagStripAdapters.load] detects the server version and loads the
 * matching one by name (so the JVM never links NMS classes for the wrong version).
 *
 * This interface is pure Bukkit API on purpose: the plugin core depends only on it, and nothing
 * here forces the core to link any version-specific class.
 */
interface TagStripAdapter {
    /** Begin stripping our keys from packets sent to [player]. Safe to call twice. */
    fun inject(player: Player)

    /** Stop stripping for [player]. Safe to call if never injected / already gone. */
    fun eject(player: Player)
}
