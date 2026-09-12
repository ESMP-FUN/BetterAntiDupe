package com.esmpfun.antidupe.ledger

import org.bukkit.NamespacedKey
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.logging.Logger

/**
 * Resolves the PersistentDataContainer keys the ownership tag is stored under. Both halves are
 * configurable so an admin can de-brand the tag in visible item NBT.
 *
 * Renaming is self-healing: a marker file remembers the last active key, so a changed key is
 * carried as legacy, still read, and replaced on the next ownership write to each item.
 */
class OwnershipKeys(
    val primary: NamespacedKey,
    val legacy: List<NamespacedKey>,
) {
    val allQualified: List<String> get() = (listOf(primary) + legacy).map { it.toString() }

    /** Never whitelist these in strict strip mode: the tag would reach the client. */
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
                logger.warning("[Ownership] invalid ownership.namespace/key ('$ns:$key') - falling back to $defaultNs:adp_owner")
                primary = NamespacedKey.fromString("$defaultNs:adp_owner")!!
            }

            val legacy = LinkedHashSet<NamespacedKey>()
            for (raw in cfg.getStringList("ownership.legacy_keys")) {
                val parsed = NamespacedKey.fromString(raw.lowercase().trim())
                if (parsed == null) logger.warning("[Ownership] ignoring invalid legacy key '$raw'")
                else legacy.add(parsed)
            }

            // A key the marker records but the config no longer names still exists on items
            // out in the world, so keep reading it.
            val marker = File(plugin.dataFolder, MARKER_FILE)
            try {
                if (marker.exists()) {
                    val previous = marker.readText().trim()
                    if (previous.isNotEmpty() && previous != primary.toString()) {
                        NamespacedKey.fromString(previous)?.let {
                            if (legacy.add(it)) {
                                logger.info("[Ownership] tag key changed ($previous -> $primary); old key kept as legacy - existing items stay tracked and re-stamp on next ownership write")
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
