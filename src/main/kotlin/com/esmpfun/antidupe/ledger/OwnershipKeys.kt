package com.esmpfun.antidupe.ledger

import org.bukkit.NamespacedKey
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.logging.Logger

/**
 * Resolves the PersistentDataContainer key(s) the ownership tag is stored under.
 *
 * By default the tag is `antidupepro:adp_owner`. Both halves are configurable
 * (`ownership.namespace` / `ownership.key`) so an admin can de-brand the tag — a leaked
 * screenshot or stream frame showing item NBT then reveals only something bland like
 * `data:o`, not which plugin wrote it.
 *
 * Renaming is self-healing: a marker file remembers the key that was last active, and when
 * the configured key changes, the previous one is automatically carried as a LEGACY key.
 * Reads recognize legacy-tagged items (so nothing silently becomes untracked), and every
 * ownership write re-stamps the item under the new key and drops the old — the population
 * migrates organically as items are picked up, crafted, and transferred.
 */
class OwnershipKeys(
    val primary: NamespacedKey,
    val legacy: List<NamespacedKey>,
) {
    /** Every key we own, as "namespace:key" strings — what the client-side stripper conceals. */
    val allQualified: List<String> get() = (listOf(primary) + legacy).map { it.toString() }

    /** Namespaces that must never be whitelisted in strict strip mode (ours). */
    val protectedNamespaces: Set<String> get() = (listOf(primary) + legacy).map { it.namespace }.toSet()

    companion object {
        private const val MARKER_FILE = "ownership-key"

        fun resolve(plugin: JavaPlugin, logger: Logger): OwnershipKeys {
            val cfg = plugin.config
            val defaultNs = plugin.name.lowercase()

            val ns = (cfg.getString("ownership.namespace") ?: defaultNs).lowercase().trim()
            val key = (cfg.getString("ownership.key") ?: "adp_owner").lowercase().trim()

            var primary = NamespacedKey.fromString("$ns:$key")
            if (primary == null || ns == "minecraft") {
                logger.warning("[Ownership] invalid ownership.namespace/key ('$ns:$key') — falling back to $defaultNs:adp_owner")
                primary = NamespacedKey.fromString("$defaultNs:adp_owner")!!
            }

            val legacy = LinkedHashSet<NamespacedKey>()
            for (raw in cfg.getStringList("ownership.legacy_keys")) {
                val parsed = NamespacedKey.fromString(raw.lowercase().trim())
                if (parsed == null) logger.warning("[Ownership] ignoring invalid legacy key '$raw'")
                else legacy.add(parsed)
            }

            // Self-healing rename: if the marker records a different key than the config now
            // names, that old key still exists on items in the world — keep reading it.
            val marker = File(plugin.dataFolder, MARKER_FILE)
            try {
                if (marker.exists()) {
                    val previous = marker.readText().trim()
                    if (previous.isNotEmpty() && previous != primary.toString()) {
                        NamespacedKey.fromString(previous)?.let {
                            if (legacy.add(it)) {
                                logger.info("[Ownership] tag key changed ($previous -> $primary); old key kept as legacy — existing items stay tracked and re-stamp on next ownership write")
                            }
                        }
                    }
                }
                marker.writeText(primary.toString())
            } catch (e: Exception) {
                logger.warning("[Ownership] could not read/write key marker: ${e.message}")
            }

            legacy.remove(primary)
            return OwnershipKeys(primary, legacy.toList())
        }
    }
}
