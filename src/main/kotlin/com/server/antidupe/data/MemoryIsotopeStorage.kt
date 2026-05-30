package com.server.antidupe.data

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * In-memory backend. Data is lost on restart — use for testing only.
 */
class MemoryIsotopeStorage(private val logger: Logger) : IsotopeStorage {

    private data class Record(@Volatile var status: String, val owner: String, val mintedAt: Long)

    private val store = ConcurrentHashMap<UUID, Record>()

    @Volatile private var connected: Boolean = false

    override fun connect() {
        connected = true
        logger.info("✓ Connected to MEMORY Security Backend (non-persistent)")
    }

    override fun disconnect() {
        connected = false
        store.clear()
    }

    override fun isConnected(): Boolean = connected

    override suspend fun mint(isotopeId: UUID, owner: UUID, details: String): Boolean {
        val rec = Record("ACTIVE", owner.toString(), System.currentTimeMillis())
        return store.putIfAbsent(isotopeId, rec) == null
    }

    override suspend fun attemptTransaction(oldIsotopeId: UUID?): Boolean {
        if (oldIsotopeId == null) return true
        val rec = store[oldIsotopeId] ?: return false
        return synchronized(rec) {
            if (rec.status == "ACTIVE") { rec.status = "SPENT"; true } else false
        }
    }

    override suspend fun getStatus(isotopeId: UUID): String {
        val rec = store[isotopeId] ?: return "UNKNOWN"
        return if (rec.status == "ACTIVE") "ACTIVE|${rec.owner}|${rec.mintedAt}" else rec.status
    }

    override suspend fun getStatuses(isotopeIds: List<UUID>): Map<UUID, String> {
        return isotopeIds.associateWith { getStatus(it) }
    }
}
