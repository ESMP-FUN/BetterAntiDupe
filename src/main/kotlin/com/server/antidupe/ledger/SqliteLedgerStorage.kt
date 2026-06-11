package com.server.antidupe.ledger

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.logging.Logger

class SqliteLedgerStorage private constructor(
    private val conn: Connection,
    logger: Logger
) : LedgerStorage(logger) {

    /**
     * All SQLite access is confined to ONE thread. The per-player append mutex only
     * serializes same-player appends; with plain Dispatchers.IO two different players'
     * writeEntry calls would interleave `autoCommit = false` / `commit()` on the single
     * shared connection and cross-commit each other's half-finished transactions.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val db: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    companion object {
        suspend fun create(plugin: JavaPlugin, logger: Logger): SqliteLedgerStorage = withContext(Dispatchers.IO) {
            Class.forName("org.sqlite.JDBC")
            val dir = plugin.dataFolder.also { if (!it.exists()) it.mkdirs() }
            val name = plugin.config.getString("storage.sqlite_ledger_file", "ledger.db") ?: "ledger.db"
            val path = java.io.File(dir, name)
            val c = DriverManager.getConnection("jdbc:sqlite:${path.absolutePath}")
            c.autoCommit = true
            c.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA synchronous=NORMAL")
                st.execute("""
                    CREATE TABLE IF NOT EXISTS ledger_entries (
                        id TEXT PRIMARY KEY,
                        player TEXT NOT NULL,
                        ts INTEGER NOT NULL,
                        action TEXT NOT NULL,
                        material TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        prev_hash TEXT,
                        hash TEXT NOT NULL,
                        metadata TEXT NOT NULL
                    )
                """.trimIndent())
                st.execute("CREATE INDEX IF NOT EXISTS idx_entries_player_ts ON ledger_entries(player, ts)")
                st.execute("CREATE INDEX IF NOT EXISTS idx_entries_ts ON ledger_entries(ts)")
                // Per-player chain tip — the anchor each player's next entry links to.
                // Fresh table name (not "ledger_tip") so we don't collide with the old global-tip
                // schema on databases created before the per-player-chain refactor.
                st.execute("""
                    CREATE TABLE IF NOT EXISTS ledger_player_tip (
                        player TEXT PRIMARY KEY,
                        last_entry_id TEXT NOT NULL,
                        last_hash TEXT NOT NULL,
                        ts INTEGER NOT NULL
                    )
                """.trimIndent())
                // Global display tip (last-write-wins, unordered) for the status command only.
                st.execute("""
                    CREATE TABLE IF NOT EXISTS ledger_global_tip (
                        k INTEGER PRIMARY KEY CHECK (k=0),
                        last_entry_id TEXT NOT NULL,
                        last_hash TEXT NOT NULL,
                        ts INTEGER NOT NULL
                    )
                """.trimIndent())
                st.execute("""
                    CREATE TABLE IF NOT EXISTS ledger_balance (
                        player TEXT NOT NULL,
                        material TEXT NOT NULL,
                        balance INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (player, material)
                    )
                """.trimIndent())
                st.execute("""
                    CREATE TABLE IF NOT EXISTS ledger_recent (
                        player TEXT NOT NULL,
                        material TEXT NOT NULL,
                        entry_id TEXT NOT NULL,
                        ts INTEGER NOT NULL,
                        qty INTEGER NOT NULL,
                        PRIMARY KEY (player, material, entry_id)
                    )
                """.trimIndent())
                st.execute("CREATE INDEX IF NOT EXISTS idx_recent_ts ON ledger_recent(player, material, ts)")
                st.execute("""
                    CREATE TABLE IF NOT EXISTS pickup_history (
                        entity_uuid TEXT PRIMARY KEY,
                        player_uuid TEXT NOT NULL,
                        material TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        picked_up_at INTEGER NOT NULL
                    )
                """.trimIndent())
                st.execute("CREATE INDEX IF NOT EXISTS idx_pickup_ts ON pickup_history(picked_up_at)")
            }
            logger.info("[Ledger] Connected to SQLite at ${path.name}")
            SqliteLedgerStorage(c, logger)
        }
    }

    override fun close() {
        try { conn.close() } catch (e: Exception) { logger.warning("[Ledger] SQLite close: ${e.message}") }
    }

    override suspend fun writeEntry(entry: LedgerEntry) = withContext(db) {
        conn.autoCommit = false
        try {
            conn.prepareStatement("""
                INSERT INTO ledger_entries(id, player, ts, action, material, quantity, prev_hash, hash, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { st ->
                st.setString(1, entry.id.toString())
                st.setString(2, entry.player.toString())
                st.setLong(3, entry.timestamp)
                st.setString(4, entry.action.name)
                st.setString(5, entry.material.name)
                st.setInt(6, entry.quantity)
                st.setString(7, entry.prevHash)
                st.setString(8, entry.hash)
                st.setString(9, entry.metadata.toJsonObject().toString())
                st.executeUpdate()
            }
            // Per-player chain tip.
            conn.prepareStatement(
                "INSERT INTO ledger_player_tip(player, last_entry_id, last_hash, ts) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(player) DO UPDATE SET last_entry_id=excluded.last_entry_id, last_hash=excluded.last_hash, ts=excluded.ts"
            ).use { st ->
                st.setString(1, entry.player.toString())
                st.setString(2, entry.id.toString())
                st.setString(3, entry.hash)
                st.setLong(4, entry.timestamp)
                st.executeUpdate()
            }
            // Global display tip (cosmetic, last-write-wins).
            conn.prepareStatement(
                "INSERT INTO ledger_global_tip(k, last_entry_id, last_hash, ts) VALUES (0, ?, ?, ?) " +
                "ON CONFLICT(k) DO UPDATE SET last_entry_id=excluded.last_entry_id, last_hash=excluded.last_hash, ts=excluded.ts"
            ).use { st ->
                st.setString(1, entry.id.toString())
                st.setString(2, entry.hash)
                st.setLong(3, entry.timestamp)
                st.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO ledger_balance(player, material, balance) VALUES (?, ?, ?) " +
                "ON CONFLICT(player, material) DO UPDATE SET balance = balance + excluded.balance"
            ).use { st ->
                st.setString(1, entry.player.toString())
                st.setString(2, entry.material.name)
                st.setInt(3, entry.quantity)
                st.executeUpdate()
            }
            if (entry.quantity > 0) {
                conn.prepareStatement(
                    "INSERT OR REPLACE INTO ledger_recent(player, material, entry_id, ts, qty) VALUES (?, ?, ?, ?, ?)"
                ).use { st ->
                    st.setString(1, entry.player.toString())
                    st.setString(2, entry.material.name)
                    st.setString(3, entry.id.toString())
                    st.setLong(4, entry.timestamp)
                    st.setInt(5, entry.quantity)
                    st.executeUpdate()
                }
            }
            conn.commit()
        } catch (e: Exception) {
            try { conn.rollback() } catch (_: Exception) {}
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    override suspend fun readPlayerTip(player: UUID): ChainTip? = withContext(db) {
        conn.prepareStatement("SELECT last_entry_id, last_hash, ts FROM ledger_player_tip WHERE player=?").use { st ->
            st.setString(1, player.toString())
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else ChainTip(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3))
            }
        }
    }

    override suspend fun getTip(): ChainTip? = withContext(db) {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT last_entry_id, last_hash, ts FROM ledger_global_tip WHERE k=0").use { rs ->
                if (!rs.next()) null
                else ChainTip(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3))
            }
        }
    }

    override suspend fun getTrackedPlayers(): Set<UUID> = withContext(db) {
        val out = mutableSetOf<UUID>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT DISTINCT player FROM ledger_entries").use { rs ->
                while (rs.next()) {
                    try { out.add(UUID.fromString(rs.getString(1))) } catch (_: IllegalArgumentException) {}
                }
            }
        }
        out
    }

    override suspend fun getEntry(id: UUID): LedgerEntry? = withContext(db) {
        conn.prepareStatement(
            "SELECT id, player, ts, action, material, quantity, prev_hash, hash, metadata FROM ledger_entries WHERE id=?"
        ).use { st ->
            st.setString(1, id.toString())
            st.executeQuery().use { rs -> if (rs.next()) rowToEntry(rs) else null }
        }
    }

    override suspend fun readBalanceFromStorage(player: UUID, material: Material): Int = withContext(db) {
        conn.prepareStatement("SELECT balance FROM ledger_balance WHERE player=? AND material=?").use { st ->
            st.setString(1, player.toString())
            st.setString(2, material.name)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    override suspend fun getAllBalances(player: UUID): Map<Material, Int> = withContext(db) {
        val out = mutableMapOf<Material, Int>()
        conn.prepareStatement("SELECT material, balance FROM ledger_balance WHERE player=? AND balance != 0").use { st ->
            st.setString(1, player.toString())
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    try { out[Material.valueOf(rs.getString(1))] = rs.getInt(2) } catch (_: IllegalArgumentException) {}
                }
            }
        }
        out
    }

    override suspend fun getRecentAcquisitions(player: UUID, material: Material, windowMs: Long): Int = withContext(db) {
        val cutoff = System.currentTimeMillis() - windowMs
        conn.prepareStatement(
            "SELECT COALESCE(SUM(qty), 0) FROM ledger_recent WHERE player=? AND material=? AND ts >= ?"
        ).use { st ->
            st.setString(1, player.toString())
            st.setString(2, material.name)
            st.setLong(3, cutoff)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    override suspend fun getPlayerEntries(player: UUID, limit: Long, offset: Long): List<LedgerEntry> = withContext(db) {
        val out = mutableListOf<LedgerEntry>()
        conn.prepareStatement(
            "SELECT id, player, ts, action, material, quantity, prev_hash, hash, metadata FROM ledger_entries " +
            "WHERE player=? ORDER BY ts DESC LIMIT ? OFFSET ?"
        ).use { st ->
            st.setString(1, player.toString())
            st.setLong(2, limit)
            st.setLong(3, offset)
            st.executeQuery().use { rs -> while (rs.next()) out.add(rowToEntry(rs)) }
        }
        out
    }

    override suspend fun pruneRecentWindows() = withContext(db) {
        val cutoff = System.currentTimeMillis() - 5 * 60 * 1000L
        conn.prepareStatement("DELETE FROM ledger_recent WHERE ts < ?").use { st ->
            st.setLong(1, cutoff); st.executeUpdate()
        }
        Unit
    }

    override suspend fun getPlayerChainOrdered(player: UUID): List<LedgerEntry> = withContext(db) {
        val out = mutableListOf<LedgerEntry>()
        // rowid is the SQLite insertion sequence — true chain order, immune to same-ms ties.
        conn.prepareStatement(
            "SELECT id, player, ts, action, material, quantity, prev_hash, hash, metadata FROM ledger_entries " +
            "WHERE player=? ORDER BY rowid ASC"
        ).use { st ->
            st.setString(1, player.toString())
            st.executeQuery().use { rs -> while (rs.next()) out.add(rowToEntry(rs)) }
        }
        out
    }

    override suspend fun markEntityPickup(
        entityUuid: UUID, playerUuid: UUID, material: Material, amount: Int
    ): PreviousPickup? = withContext(db) {
        val now = System.currentTimeMillis()
        val inserted = conn.prepareStatement(
            "INSERT OR IGNORE INTO pickup_history(entity_uuid, player_uuid, material, amount, picked_up_at) VALUES (?, ?, ?, ?, ?)"
        ).use { st ->
            st.setString(1, entityUuid.toString())
            st.setString(2, playerUuid.toString())
            st.setString(3, material.name)
            st.setInt(4, amount)
            st.setLong(5, now)
            st.executeUpdate() > 0
        }
        if (inserted) return@withContext null

        conn.prepareStatement(
            "SELECT player_uuid, material, amount, picked_up_at FROM pickup_history WHERE entity_uuid=?"
        ).use { st ->
            st.setString(1, entityUuid.toString())
            st.executeQuery().use { rs ->
                if (rs.next()) PreviousPickup(
                    playerUuid = UUID.fromString(rs.getString(1)),
                    material = try { Material.valueOf(rs.getString(2)) } catch (e: Exception) { Material.AIR },
                    amount = rs.getInt(3),
                    pickedUpAt = rs.getLong(4)
                ) else null
            }
        }
    }

    override suspend fun prunePickupHistory(olderThanMs: Long) = withContext(db) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        conn.prepareStatement("DELETE FROM pickup_history WHERE picked_up_at < ?").use { st ->
            st.setLong(1, cutoff)
            st.executeUpdate()
        }
        Unit
    }

    private fun rowToEntry(rs: java.sql.ResultSet): LedgerEntry {
        return LedgerEntry(
            id = UUID.fromString(rs.getString(1)),
            player = UUID.fromString(rs.getString(2)),
            timestamp = rs.getLong(3),
            action = LedgerAction.valueOf(rs.getString(4)),
            material = Material.valueOf(rs.getString(5)),
            quantity = rs.getInt(6),
            prevHash = rs.getString(7),
            hash = rs.getString(8),
            metadata = LedgerMetadata.fromJson(org.json.JSONObject(rs.getString(9)))
        )
    }
}
