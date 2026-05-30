package com.server.antidupe.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bukkit.plugin.java.JavaPlugin
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

class SqliteIsotopeStorage(private val plugin: JavaPlugin) : IsotopeStorage {

    private var conn: Connection? = null
    private val writeMutex = Mutex()

    @Volatile
    private var connected: Boolean = false

    override fun connect() {
        Class.forName("org.sqlite.JDBC")
        val dir = plugin.dataFolder.also { if (!it.exists()) it.mkdirs() }
        val name = plugin.config.getString("storage.sqlite_file", "storage.db") ?: "storage.db"
        val path = java.io.File(dir, name)
        val url = "jdbc:sqlite:${path.absolutePath}"
        val c = DriverManager.getConnection(url)
        c.autoCommit = true
        c.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA foreign_keys=ON")
            st.execute("""
                CREATE TABLE IF NOT EXISTS isotopes (
                    id TEXT PRIMARY KEY,
                    status TEXT NOT NULL,
                    owner TEXT,
                    minted_at INTEGER,
                    details TEXT
                )
            """.trimIndent())
        }
        conn = c
        connected = true
        plugin.logger.info("✓ Connected to SQLite Security Backend at ${path.name}")
    }

    override fun disconnect() {
        connected = false
        try { conn?.close() } catch (e: Exception) { plugin.logger.warning("SQLite close: ${e.message}") }
        conn = null
    }

    override fun isConnected(): Boolean = connected && conn?.isClosed == false

    private fun require(): Connection = conn ?: throw IllegalStateException("Not connected to SQLite")

    override suspend fun mint(isotopeId: UUID, owner: UUID, details: String): Boolean =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val c = require()
                c.prepareStatement(
                    "INSERT OR IGNORE INTO isotopes(id, status, owner, minted_at, details) VALUES (?, 'ACTIVE', ?, ?, ?)"
                ).use { st ->
                    st.setString(1, isotopeId.toString())
                    st.setString(2, owner.toString())
                    st.setLong(3, System.currentTimeMillis())
                    st.setString(4, details)
                    val rows = st.executeUpdate()
                    if (rows == 0) {
                        plugin.logger.severe("COLLISION DETECTED: UUID $isotopeId already exists ($details)")
                        false
                    } else true
                }
            }
        }

    override suspend fun attemptTransaction(oldIsotopeId: UUID?): Boolean {
        if (oldIsotopeId == null) return true
        return withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val c = require()
                c.prepareStatement(
                    "UPDATE isotopes SET status='SPENT' WHERE id=? AND status='ACTIVE'"
                ).use { st ->
                    st.setString(1, oldIsotopeId.toString())
                    st.executeUpdate() == 1
                }
            }
        }
    }

    override suspend fun getStatus(isotopeId: UUID): String = withContext(Dispatchers.IO) {
        val c = require()
        c.prepareStatement("SELECT status, owner, minted_at FROM isotopes WHERE id=?").use { st ->
            st.setString(1, isotopeId.toString())
            st.executeQuery().use { rs ->
                if (!rs.next()) "UNKNOWN"
                else {
                    val status = rs.getString(1)
                    if (status == "ACTIVE") "ACTIVE|${rs.getString(2)}|${rs.getLong(3)}" else status
                }
            }
        }
    }

    override suspend fun getStatuses(isotopeIds: List<UUID>): Map<UUID, String> {
        if (isotopeIds.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val c = require()
            val out = HashMap<UUID, String>(isotopeIds.size)
            isotopeIds.forEach { out[it] = "UNKNOWN" }

            // Chunk to avoid huge IN clauses
            isotopeIds.chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                c.prepareStatement(
                    "SELECT id, status, owner, minted_at FROM isotopes WHERE id IN ($placeholders)"
                ).use { st ->
                    chunk.forEachIndexed { i, id -> st.setString(i + 1, id.toString()) }
                    st.executeQuery().use { rs ->
                        while (rs.next()) {
                            val id = UUID.fromString(rs.getString(1))
                            val status = rs.getString(2)
                            out[id] = if (status == "ACTIVE") "ACTIVE|${rs.getString(3)}|${rs.getLong(4)}" else status
                        }
                    }
                }
            }
            out
        }
    }
}
