package com.server.antidupe.ledger

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
    private val logger: Logger
) {
    private var maintenanceJob: Job? = null

    companion object {
        suspend fun initialize(
            plugin: Plugin,
            scope: CoroutineScope,
            ledgerStorage: LedgerStorage,
            trackedMaterials: Set<Material>,
            tmarLimits: Map<Material, Int>,
            witnessRadius: Double,
            verifiedThreshold: Int,
            suspiciousSoloRatio: Double,
            reconciliationCooldownMs: Long,
            alertThresholds: Map<Material, Int>,
            defaultAlertThreshold: Int,
            logger: Logger
        ): ChainOfCustody {
            val ownershipManager = OwnershipManager(plugin)
            val witnessManager = WitnessManager(plugin, witnessRadius, verifiedThreshold, suspiciousSoloRatio)

            val reconciliationEngine = ReconciliationEngine(
                plugin = plugin,
                ledgerStorage = ledgerStorage,
                ownershipManager = ownershipManager,
                trackedMaterials = trackedMaterials,
                tmarLimits = tmarLimits,
                logger = logger,
                scope = scope,
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
                scope = scope
            )

            plugin.server.pluginManager.registerEvents(eventHandler, plugin)

            val coc = ChainOfCustody(plugin, ledgerStorage, ownershipManager, witnessManager,
                reconciliationEngine, eventHandler, scope, logger)

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

    suspend fun verifyIntegrity(): IntegrityResult = ledgerStorage.verifyChainIntegrity()

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
