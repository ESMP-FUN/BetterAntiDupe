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
                logger.warning("[Ownership] In config.yml, ownership.namespace and ownership.key do not make a" +
                    " usable name ('$ns:$key'), so the standard one is being used instead ($defaultNs:adp_owner)." +
                    " Letters, numbers, dots, dashes and underscores only, and the first part cannot be 'minecraft'.")
                primary = NamespacedKey.fromString("$defaultNs:adp_owner")!!
            }

            val legacy = LinkedHashSet<NamespacedKey>()
            for (raw in cfg.getStringList("ownership.legacy_keys")) {
                val parsed = NamespacedKey.fromString(raw.lowercase().trim())
                if (parsed == null) logger.warning("[Ownership] In config.yml, ownership.legacy_keys includes" +
                    " '$raw', which is not a usable name, so it is being skipped. It should look like" +
                    " 'someplugin:some_name'.")
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
                logger.warning("[Ownership] Could not save a note of which owner mark this server uses. If you" +
                    " change it later, items marked with the old one may stop being recognised." +
                    " Details: ${e.message}")
            }

            legacy.remove(primary)
            return OwnershipKeys(primary, legacy.toList())
        }
    }
}
