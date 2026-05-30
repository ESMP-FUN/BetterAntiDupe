package com.server.antidupe.data

import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * Persistence interface for the v1 Digital Isotope system.
 * Three backends are provided: SQLite (default), Redis, Memory.
 *
 * Values returned by [getStatus] / [getStatuses] follow the legacy format used by
 * the original Redis implementation:
 *   "ACTIVE|<owner-uuid>|<timestamp-millis>"   — minted, valid
 *   "SPENT"                                    — burned (duplicate)
 *   "UNKNOWN"                                  — id not present
 */
interface IsotopeStorage {
    fun connect()
    fun disconnect()
    fun isConnected(): Boolean

    /** @return true if mint succeeded, false on (astronomically rare) UUID collision. */
    suspend fun mint(isotopeId: UUID, owner: UUID, details: String): Boolean

    /** Atomic check-and-spend. @return true if the id was ACTIVE and is now SPENT. */
    suspend fun attemptTransaction(oldIsotopeId: UUID?): Boolean

    suspend fun getStatus(isotopeId: UUID): String

    suspend fun getStatuses(isotopeIds: List<UUID>): Map<UUID, String>

    companion object {
        fun create(plugin: JavaPlugin): IsotopeStorage {
            val backend = (plugin.config.getString("storage.backend", "SQLITE") ?: "SQLITE").uppercase()
            return when (backend) {
                "REDIS" -> RedisIsotopeStorage(plugin)
                "MEMORY" -> MemoryIsotopeStorage(plugin.logger)
                "SQLITE" -> SqliteIsotopeStorage(plugin)
                else -> {
                    plugin.logger.warning("Unknown storage.backend '$backend', falling back to SQLITE")
                    SqliteIsotopeStorage(plugin)
                }
            }
        }
    }
}
