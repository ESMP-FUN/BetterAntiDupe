package com.esmpfun.antidupe.ledger

import com.esmpfun.antidupe.platform.PlatformScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ChainOfCustody private constructor(
    private val plugin: Plugin,
    private val ledgerStorage: LedgerStorage,
    val ownershipManager: OwnershipManager,
    val witnessManager: WitnessManager,
    val reconciliationEngine: ReconciliationEngine,
    private val eventHandler: LedgerEventHandler,
    private val scope: CoroutineScope,
    private val trackedMaterialsView: Set<Material>,
    private val logger: Logger,
    private val scheduler: PlatformScheduler,
    private val sweepIntervalMinutes: Int,
    private val sweepStaggerMs: Long
) {
    private var maintenanceJob: Job? = null
    private var sweepJob: Job? = null

    companion object {
        suspend fun initialize(
            plugin: Plugin,
            scope: CoroutineScope,
            scheduler: PlatformScheduler,
            ledgerStorage: LedgerStorage,
            trackedMaterials: Set<Material>,
            tmarLimits: Map<Material, Int>,
            witnessRadius: Double,
            verifiedThreshold: Int,
            suspiciousSoloRatio: Double,
            reconciliationCooldownMs: Long,
            alertThresholds: Map<Material, Int>,
            defaultAlertThreshold: Int,
            sensitivity: Int,
            logger: Logger,
            ownershipKeys: OwnershipKeys? = null,
            reconcileOnPickup: Boolean = true,
            reconcileOnInventoryClose: Boolean = false,
            flagSuspiciousPatterns: Boolean = true,
            hopperMode: LedgerEventHandler.HopperMode = LedgerEventHandler.HopperMode.LOG,
            sweepIntervalMinutes: Int = 15,
            sweepStaggerMs: Long = 250L
        ): ChainOfCustody {
            val ownershipManager = OwnershipManager(
                plugin,
                primaryKey = ownershipKeys?.primary,
                legacyKeys = ownershipKeys?.legacy ?: emptyList()
            )
            val witnessManager = WitnessManager(plugin, witnessRadius, verifiedThreshold, suspiciousSoloRatio)
            val suspicionManager = SuspicionManager(sensitivity)

            val reconciliationEngine = ReconciliationEngine(
                plugin = plugin,
                ledgerStorage = ledgerStorage,
                ownershipManager = ownershipManager,
                trackedMaterials = trackedMaterials,
                tmarLimits = tmarLimits,
                logger = logger,
                scope = scope,
                scheduler = scheduler,
                suspicion = suspicionManager,
                reconciliationCooldown = reconciliationCooldownMs,
                alertThresholds = alertThresholds,
                defaultAlertThreshold = defaultAlertThreshold
            )

            val eventHandler = LedgerEventHandler(
                plugin = plugin,
                ledgerStorage = ledgerStorage,
                ownershipManager = ownershipManager,
                reconciliationEngine = reconciliationEngine,
                witnessManager = witnessManager,
                trackedMaterials = trackedMaterials,
                logger = logger,
                scope = scope,
                scheduler = scheduler,
                reconcileOnPickup = reconcileOnPickup,
                reconcileOnInventoryClose = reconcileOnInventoryClose,
                flagSuspiciousPatterns = flagSuspiciousPatterns,
                hopperMode = hopperMode
            )

            plugin.server.pluginManager.registerEvents(eventHandler, plugin)
            eventHandler.registerPaperOnlyListeners()
            eventHandler.registerHopperListener()

            val coc = ChainOfCustody(plugin, ledgerStorage, ownershipManager, witnessManager,
                reconciliationEngine, eventHandler, scope, trackedMaterials, logger,
                scheduler, sweepIntervalMinutes, sweepStaggerMs)

            coc.migrateLegacyChains()
            coc.startMaintenance()
            coc.startPeriodicSweep()
            coc.verifyIntegrityAsync()

            logger.info("[CoC] Chain of Custody initialized (v3.x detection model)")
            logger.info("[CoC] Tracking ${trackedMaterials.size} materials")
            return coc
        }
    }

    suspend fun getChainTip(): ChainTip? = ledgerStorage.getTip()
    suspend fun getEntry(id: UUID): LedgerEntry? = ledgerStorage.getEntry(id)
    suspend fun getBalance(player: UUID, material: Material): Int = ledgerStorage.getBalance(player, material)
    suspend fun getAllBalances(player: UUID): Map<Material, Int> = ledgerStorage.getAllBalances(player)
    suspend fun getPlayerHistory(player: UUID, limit: Int = 50): List<LedgerEntry> =
        ledgerStorage.getPlayerEntries(player, limit = limit.toLong())

    /**
     * Recent "stash" actions for a player: items they put into a chest, shulker, barrel,
     * ender chest, lectern, decorated pot, horse / boat / minecart chest, or item frame.
     * Used by the admin "where did they hide it" workflow.
     */
    suspend fun getPlayerStashes(player: UUID, limit: Int = 30): List<LedgerEntry> {
        val stashActions = setOf(
            LedgerAction.CONTAINER_PUT,
            LedgerAction.ENTITY_PUT,
            LedgerAction.FRAME_PUT
        )
        // Pull a generous window and filter; this only runs on the admin command path.
        return ledgerStorage.getPlayerEntries(player, limit = (limit * 4).toLong())
            .filter { it.action in stashActions }
            .take(limit)
    }

    /** Verify a single player's chain, or all players' chains if [player] is null. */
    suspend fun verifyIntegrity(player: UUID? = null): IntegrityResult =
        if (player != null) ledgerStorage.verifyChainIntegrity(player) else ledgerStorage.verifyAllChains()

    /**
     * One-time migration from the pre-3.3.0 global hash chain to per-player chains. For every
     * tracked player whose chain has no CHAIN_RESET marker yet, append one. Verification then
     * walks each player's chain from the marker forward and ignores the legacy global-chain
     * prevHashes that lived before the schema change. Idempotent: re-runs do nothing.
     */
    private suspend fun migrateLegacyChains() {
        val players = ledgerStorage.getTrackedPlayers()
        if (players.isEmpty()) return
        var stamped = 0
        for (player in players) {
            val history = ledgerStorage.getPlayerEntries(player, limit = 200)
            val alreadyReset = history.any { it.metadata.notes?.startsWith("CHAIN_RESET:") == true }
            if (alreadyReset) continue
            ledgerStorage.appendBuilt(
                player = player,
                action = LedgerAction.RECONCILE,
                material = Material.AIR,
                quantity = 0,
                metadata = LedgerMetadata(notes = "CHAIN_RESET:legacy-global-chain")
            )
            stamped++
        }
        if (stamped > 0) {
            logger.info("[CoC] Migrated $stamped legacy chain(s) to per-player verification — old entries kept for history, new chain verifies clean")
        }
    }

    private fun verifyIntegrityAsync() {
        scope.launch {
            try {
                val result = verifyIntegrity()
                if (result.valid) logger.info("[CoC] Chain integrity verified: ${result.entriesVerified} entries OK")
                else {
                    logger.warning("[CoC] Chain integrity check found a break: ${result.error}")
                    logger.warning("[CoC] Broken at entry: ${result.brokenAt} — investigate or run /adp ledger verify <player>")
                }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "[CoC] integrity check failed", e)
            }
        }
    }

    suspend fun reconcile(player: Player): ReconciliationResult = reconciliationEngine.reconcile(player)

    fun reconcileAsync(player: Player, callback: ((ReconciliationResult) -> Unit)? = null) {
        reconciliationEngine.reconcileAsync(player, callback)
    }

    suspend fun quickCheck(player: Player, material: Material): QuickCheckResult =
        reconciliationEngine.quickCheck(player, material)

    suspend fun checkTmar(player: Player, material: Material, amount: Int): TmarCheckResult =
        reconciliationEngine.checkTmar(player, material, amount)

    fun getWitnessStats(player: UUID): WitnessStats = witnessManager.getPlayerStats(player)
    fun hasSuspiciousPattern(player: UUID): SuspicionAnalysis = witnessManager.hasSuspiciousPattern(player)
    fun getTrustScore(player: UUID): TrustScore = witnessManager.getTrustScore(player)

    /**
     * Public API for other plugins to declare a legitimate grant of items to a player.
     * Use this whenever your plugin uses `player.getInventory().addItem(...)` or similar
     * outside of normal Bukkit events — otherwise BetterAntiDupe's reconciliation will see
     * the extra inventory and may flag the player as a duper.
     *
     * Example:
     *     anti.recordSystemGrant(player.uniqueId, Material.DIAMOND_BLOCK, 5, "DailyReward")
     */
    suspend fun recordSystemGrant(player: UUID, material: Material, amount: Int, source: String) {
        if (amount <= 0) return
        ledgerStorage.appendBuilt(
            player = player,
            action = LedgerAction.ADMIN_GIVE,
            material = material,
            quantity = amount,
            metadata = LedgerMetadata(notes = "SYSTEM_GRANT:$source")
        )
    }

    fun onDupeAlert(listener: (DupeAlert) -> Unit) = reconciliationEngine.addAlertListener(listener)
    fun getSuspects(): List<SuspectProfile> = reconciliationEngine.getAllSuspects()
    fun getSuspect(player: UUID): SuspectProfile? = reconciliationEngine.getSuspect(player)
    fun clearSuspect(player: UUID) = reconciliationEngine.clearSuspect(player)

    /** Admin verdict: confirm a player is duping (pins suspicion high). */
    fun confirmSuspect(player: UUID) = reconciliationEngine.confirmSuspect(player)
    /** Admin verdict: false positive — clear suspicion and remove from the suspect list. */
    fun clearVerdict(player: UUID) = reconciliationEngine.clearVerdict(player)
    /** Current effective suspicion (0..100) for a player. */
    fun suspicionOf(player: UUID): Double = reconciliationEngine.suspicionOf(player)

    /**
     * Seed a never-before-seen player's ledger from their current inventory, so pre-existing
     * or externally-granted items don't read as a surplus. Safe because it only runs once per
     * player (guarded by a BASELINE marker entry) — returning players keep their real history.
     */
    suspend fun baselineIfNew(player: Player) {
        val already = ledgerStorage.getPlayerEntries(player.uniqueId, limit = 1).isNotEmpty()
        if (already) return
        // Snapshot on the player's thread (we're on Dispatchers.IO here) — single pass.
        val owned = reconciliationEngine.snapshotOwned(player)
        for ((material, actual) in owned) {
            if (actual > 0) {
                ledgerStorage.appendBuilt(
                    player = player.uniqueId,
                    action = LedgerAction.ADMIN_GIVE,
                    material = material,
                    quantity = actual,
                    metadata = LedgerMetadata(notes = "BASELINE_ON_JOIN")
                )
            }
        }
        // Always drop a marker so an empty-inventory new player isn't re-baselined every join.
        ledgerStorage.appendBuilt(
            player = player.uniqueId,
            action = LedgerAction.RECONCILE,
            material = Material.AIR,
            quantity = 0,
            metadata = LedgerMetadata(notes = "BASELINE_MARKER")
        )
    }

    /**
     * Record that the plugin took a surplus back out of a player's inventory.
     *
     * Quantity is zero on purpose: the removal cancels the surplus rather than moving items
     * on or off the books, so the player's balance is already correct once the items are gone.
     * This entry exists so the action is visible in `/adp ledger history`.
     */
    suspend fun recordEnforcement(player: UUID, material: Material, amount: Int, severity: String) {
        ledgerStorage.appendBuilt(
            player = player,
            action = LedgerAction.RECONCILE,
            material = material,
            quantity = 0,
            metadata = LedgerMetadata(notes = "ENFORCED_REMOVAL:$amount:$severity")
        )
    }

    /**
     * Re-check every online player on a timer.
     *
     * Without this, a balance check only ever runs when somebody picks an item up off the
     * ground. Anyone who moves everything through chests, which is every container-mediated
     * dupe, would never be checked at all. The sweep closes that by walking the whole online
     * roster on an interval.
     *
     * Players are spaced out by [sweepStaggerMs] rather than checked all at once, so a full
     * server does not pay for every scan in the same tick. Each check still respects the
     * per-player cooldown, so this never doubles up with a pickup-triggered check.
     */
    private fun startPeriodicSweep() {
        if (sweepIntervalMinutes <= 0) {
            logger.info("[CoC] Periodic balance sweep disabled (interval set to 0)")
            return
        }
        val intervalMs = sweepIntervalMinutes * 60_000L
        sweepJob = scope.launch {
            // Offset the first pass so a restart does not scan everyone during the join rush.
            delay(intervalMs)
            while (isActive) {
                try {
                    sweepOnce()
                } catch (e: Exception) {
                    logger.warning("[CoC] Balance sweep error: ${e.message}")
                }
                delay(intervalMs)
            }
        }
        logger.info("[CoC] Periodic balance sweep every $sweepIntervalMinutes min")
    }

    private suspend fun sweepOnce() {
        val players = onlinePlayersSnapshot()
        if (players.isEmpty()) return
        logger.fine("[CoC] Balance sweep starting for ${players.size} player(s)")
        for (player in players) {
            if (!player.isOnline) continue
            reconciliationEngine.reconcileAsync(player)
            if (sweepStaggerMs > 0) delay(sweepStaggerMs)
        }
    }

    /**
     * Read the online roster on the main (global region) thread. On Folia the player list is
     * not safe to walk from an arbitrary coroutine thread.
     */
    private suspend fun onlinePlayersSnapshot(): List<Player> =
        suspendCancellableCoroutine { cont ->
            scheduler.runMain(Runnable {
                try {
                    cont.resume(plugin.server.onlinePlayers.toList())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            })
        }

    private fun startMaintenance() {
        maintenanceJob = scope.launch {
            while (isActive) {
                delay(300_000)
                try {
                    witnessManager.pruneHistory()
                    ledgerStorage.pruneRecentWindows()
                    // Pickup-history retention: 30 days. Entries older than this are evicted
                    // — long enough to catch latent chunk-load dupes, short enough to bound disk.
                    ledgerStorage.prunePickupHistory(30L * 86_400_000L)
                    // Sweep expired source-bound authorizations (chunks visited once would
                    // otherwise hold their empty deques forever).
                    eventHandler.pruneAuthorizedDrops()
                    // Drop the "already logged this hopper route" memory for routes gone quiet.
                    eventHandler.pruneHopperTrail()
                    // Evict stale reconciliation cooldowns.
                    reconciliationEngine.pruneMaintenance()
                    // Idle decay of transient suspicion heat (the earned floor is untouched).
                    reconciliationEngine.decaySuspicion()
                    logger.fine("[CoC] Maintenance completed")
                } catch (e: Exception) {
                    logger.warning("[CoC] Maintenance error: ${e.message}")
                }
            }
        }
    }

    fun shutdown() {
        maintenanceJob?.cancel()
        sweepJob?.cancel()
        ledgerStorage.close()
        logger.info("[CoC] Chain of Custody shutdown complete")
    }

    suspend fun getSystemStats(): SystemStats {
        val tip = getChainTip()
        val suspects = getSuspects()
        return SystemStats(
            chainTipId = tip?.lastEntryId,
            chainTipHash = tip?.lastHash,
            activeSuspects = suspects.size,
            suspectNames = suspects.map { it.playerName }
        )
    }
}

data class SystemStats(
    val chainTipId: UUID?,
    val chainTipHash: String?,
    val activeSuspects: Int,
    val suspectNames: List<String>
)
