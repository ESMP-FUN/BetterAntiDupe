package com.server.antidupe.ledger

import com.server.antidupe.platform.PlatformScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

class ChainOfCustody private constructor(
    private val plugin: Plugin,
    private val ledgerStorage: LedgerStorage,
    val ownershipManager: OwnershipManager,
    val witnessManager: WitnessManager,
    val reconciliationEngine: ReconciliationEngine,
    private val eventHandler: LedgerEventHandler,
    private val scope: CoroutineScope,
    private val trackedMaterialsView: Set<Material>,
    private val logger: Logger
) {
    private var maintenanceJob: Job? = null

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
            logger: Logger
        ): ChainOfCustody {
            val ownershipManager = OwnershipManager(plugin)
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
                scheduler = scheduler
            )

            plugin.server.pluginManager.registerEvents(eventHandler, plugin)

            val coc = ChainOfCustody(plugin, ledgerStorage, ownershipManager, witnessManager,
                reconciliationEngine, eventHandler, scope, trackedMaterials, logger)

            coc.startMaintenance()
            coc.verifyIntegrityAsync()

            logger.info("[CoC] Chain of Custody v2 initialized successfully")
            logger.info("[CoC] Tracking ${trackedMaterials.size} materials with Proof of Witness")
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

    private fun verifyIntegrityAsync() {
        scope.launch {
            try {
                val result = verifyIntegrity()
                if (result.valid) logger.info("[CoC] Chain integrity verified: ${result.entriesVerified} entries OK")
                else {
                    logger.severe("[CoC] CHAIN INTEGRITY FAILURE: ${result.error}")
                    logger.severe("[CoC] Broken at entry: ${result.brokenAt}")
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
     * outside of normal Bukkit events — otherwise AntiDupePro's reconciliation will see
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
        for (material in trackedMaterialsView) {
            val actual = ownershipManager.countOwnedInInventoryDeep(player, material)
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
