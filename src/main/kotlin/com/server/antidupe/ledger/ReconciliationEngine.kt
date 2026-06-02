package com.server.antidupe.ledger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The Reconciliation Engine is the core dupe detection mechanism.
 *
 * It compares a player's actual inventory against their ledger balance.
 * If actual > ledger, they have more items than they should - DUPE DETECTED.
 *
 * Detection Strategies:
 * 1. Balance Reconciliation - inventory vs ledger sum
 * 2. Acquisition Rate (TMAR) - items gained per time window
 * 3. Orphan Detection - tracked items with no ledger history
 * 4. Transfer Validation - give/receive pairs must balance
 */
class ReconciliationEngine(
    private val plugin: Plugin,
    private val ledgerStorage: LedgerStorage,
    private val ownershipManager: OwnershipManager,
    private val trackedMaterials: Set<Material>,
    private val tmarLimits: Map<Material, Int>,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val suspicion: SuspicionManager,
    private val reconciliationCooldown: Long = 5000L,
    private val alertThresholds: Map<Material, Int> = emptyMap(),
    private val defaultAlertThreshold: Int = 5
) {
    private val activeReconciliations = ConcurrentHashMap<UUID, Long>()
    private val suspects = ConcurrentHashMap<UUID, SuspectProfile>()
    private val alertListeners = CopyOnWriteArrayList<(DupeAlert) -> Unit>()

    /** Configurable level for the verbose "[CoC] re-baselined" self-heal log line. */
    @Volatile var healLogLevel: Level = Level.FINE

    fun addAlertListener(listener: (DupeAlert) -> Unit) {
        alertListeners.add(listener)
    }

    private fun emitAlert(alert: DupeAlert) {
        alertListeners.forEach { it(alert) }
    }

    /**
     * Perform full reconciliation for a player.
     * Compares actual inventory to ledger balance for all tracked materials.
     */
    suspend fun reconcile(player: Player): ReconciliationResult {
        val playerId = player.uniqueId
        val now = System.currentTimeMillis()

        // Check cooldown
        val lastReconcile = activeReconciliations[playerId]
        if (lastReconcile != null && now - lastReconcile < reconciliationCooldown) {
            return ReconciliationResult(
                player = playerId,
                timestamp = now,
                skipped = true,
                reason = "Cooldown active"
            )
        }
        activeReconciliations[playerId] = now

        val discrepancies = mutableListOf<Discrepancy>()
        val tmarViolations = mutableListOf<TmarViolation>()

        for (material in trackedMaterials) {
            val ledgerBalance = ledgerStorage.getBalance(playerId, material)
            val actualCount = ownershipManager.countOwnedInInventoryDeep(player, material)

            // SELF-HEAL: a negative ledger is *proof our bookkeeping is incomplete* — more
            // disposals were recorded than acquisitions, which duping cannot produce (duping
            // makes you have MORE, a positive surplus). It only happens when an acquisition path
            // went unobserved (/give, a shop plugin's addItem, a tracking gap). Punishing here
            // would be punishing the player for our error. Instead adopt the real inventory as
            // the new baseline and move on. Real dupes (positive surplus on a trustworthy ledger,
            // entity-UUID reuse, source-bound excess) still fire.
            if (ledgerBalance < 0) {
                val correction = actualCount - ledgerBalance   // brings stored balance up to actual
                ledgerStorage.appendBuilt(
                    player = playerId,
                    action = LedgerAction.ADMIN_GIVE,
                    material = material,
                    quantity = correction,
                    metadata = LedgerMetadata(notes = "BASELINE_HEAL: ledger $ledgerBalance -> $actualCount")
                )
                logger.log(healLogLevel, "[CoC] Re-baselined ${player.name}/$material: ledger was $ledgerBalance, adopted actual $actualCount (incomplete-acquisition gap)")
                continue
            }

            // Trustworthy ledger: a positive surplus is a real candidate. Gate the alert through
            // the player's suspicion and the global sensitivity instead of a flat threshold.
            if (actualCount > ledgerBalance) {
                val excess = actualCount - ledgerBalance
                discrepancies.add(Discrepancy(material, ledgerBalance, actualCount, excess))

                val threshold = suspicion.effectiveThreshold(playerId, getAlertThreshold(material))
                if (excess >= threshold) {
                    suspicion.bumpFloor(playerId, SuspicionManager.DETERMINISTIC_FLOOR_BUMP / 2)  // medium confidence
                    emitAlert(DupeAlert(
                        type = AlertType.BALANCE_DISCREPANCY,
                        player = playerId,
                        playerName = player.name,
                        material = material,
                        details = "Has $actualCount but ledger shows $ledgerBalance (excess: $excess)",
                        severity = calculateSeverity(material, excess),
                        timestamp = now
                    ))
                }
            }

            // TMAR is a LOW-confidence signal now: legitimate farms and shop buyouts burst hard.
            // It only nudges transient heat (which decays); it never fires a standalone alert.
            val tmarLimit = tmarLimits[material]
            if (tmarLimit != null) {
                val recentAcquisitions = ledgerStorage.getRecentAcquisitions(playerId, material)
                if (recentAcquisitions > tmarLimit) {
                    tmarViolations.add(TmarViolation(material, recentAcquisitions, tmarLimit, 5))
                    suspicion.addHeat(playerId)
                }
            }
        }

        // Check for foreign items (items with different owner)
        val foreignItems = ownershipManager.findForeignItemsDeep(player)
        val foreignAlerts = foreignItems.map { foreign ->
            ForeignItemAlert(
                material = foreign.item.type,
                amount = foreign.item.amount,
                originalOwner = foreign.originalOwner,
                slot = foreign.slot
            )
        }

        // An alert-worthy discrepancy is one that cleared the suspicion-scaled threshold.
        val alertWorthy = discrepancies.filter {
            it.excess >= suspicion.effectiveThreshold(playerId, getAlertThreshold(it.material))
        }
        val dupeDetected = alertWorthy.isNotEmpty()

        if (dupeDetected) {
            val profile = suspects.getOrPut(playerId) { SuspectProfile(playerId, player.name) }
            profile.recordViolation(alertWorthy, tmarViolations)
            ledgerStorage.appendBuilt(
                player = playerId,
                action = LedgerAction.RECONCILE,
                material = alertWorthy.first().material,
                quantity = 0,
                metadata = LedgerMetadata(notes = "Reconciliation: ${alertWorthy.size} alert-worthy discrepancies")
            )
        }

        return ReconciliationResult(
            player = playerId,
            timestamp = now,
            discrepancies = discrepancies,
            tmarViolations = tmarViolations,
            foreignItems = foreignAlerts,
            dupeDetected = dupeDetected
        )
    }

    /**
     * Quick check for a specific material (lighter than full reconciliation)
     */
    suspend fun quickCheck(player: Player, material: Material): QuickCheckResult {
        val playerId = player.uniqueId
        val ledgerBalance = ledgerStorage.getBalance(playerId, material)
        val actualCount = ownershipManager.countOwnedInInventory(player, material)

        return QuickCheckResult(
            material = material,
            ledgerBalance = ledgerBalance,
            actualCount = actualCount,
            valid = actualCount <= ledgerBalance
        )
    }

    /**
     * Verify a specific acquisition is within TMAR limits
     */
    suspend fun checkTmar(player: Player, material: Material, addingAmount: Int): TmarCheckResult {
        val playerId = player.uniqueId
        val limit = tmarLimits[material] ?: return TmarCheckResult(allowed = true)

        val currentRate = ledgerStorage.getRecentAcquisitions(playerId, material)
        val projectedRate = currentRate + addingAmount

        return TmarCheckResult(
            allowed = projectedRate <= limit,
            currentRate = currentRate,
            projectedRate = projectedRate,
            limit = limit,
            excess = if (projectedRate > limit) projectedRate - limit else 0
        )
    }

    /**
     * Get suspect profile for a player
     */
    fun getSuspect(playerId: UUID): SuspectProfile? = suspects[playerId]

    /**
     * Get all current suspects
     */
    fun getAllSuspects(): List<SuspectProfile> = suspects.values.toList()

    /**
     * Clear suspect status for a player
     */
    fun clearSuspect(playerId: UUID) {
        suspects.remove(playerId)
    }

    /**
     * Run background reconciliation for a player (non-blocking)
     */
    fun reconcileAsync(player: Player, callback: ((ReconciliationResult) -> Unit)? = null) {
        scope.launch {
            val result = reconcile(player)
            callback?.invoke(result)
        }
    }

    /**
     * Called when an item-entity UUID is picked up after having been previously consumed —
     * the signature of a chunk-load / drop-race / cross-server dupe. This is the highest-
     * confidence signal we produce, so it always fires CRITICAL.
     */
    fun flagEntityDupe(player: Player, material: Material, amount: Int, previous: PreviousPickup) {
        val now = System.currentTimeMillis()
        // Deterministic, ~zero false positive → raise the earned floor and always alert.
        suspicion.bumpFloor(player.uniqueId)
        val profile = suspects.getOrPut(player.uniqueId) { SuspectProfile(player.uniqueId, player.name) }
        profile.recordViolation(
            listOf(Discrepancy(material, 0, amount, amount)), emptyList()
        )
        emitAlert(DupeAlert(
            type = AlertType.BALANCE_DISCREPANCY,
            player = player.uniqueId,
            playerName = player.name,
            material = material,
            details = "Chunk-load / drop-race dupe: entity-UUID re-used ${(now - previous.pickedUpAt) / 1000}s after original pickup",
            severity = Severity.CRITICAL,
            timestamp = now
        ))
    }

    /**
     * Pickup exceeded what nearby legitimate sources produced (source-bound excess). MEDIUM-HIGH
     * confidence — strong, but with edges (e.g. re-picking up your own dropped item next to a
     * mob death), so it's gated through suspicion/sensitivity rather than fired unconditionally.
     * Always raises heat; alerts only when the excess clears the player's scaled threshold.
     */
    fun flagDropExcess(player: Player, material: Material, excess: Int, source: String) {
        val now = System.currentTimeMillis()
        suspicion.addHeat(player.uniqueId, SuspicionManager.HEAT_PER_SIGNAL * 3)  // stronger than TMAR/witness
        val threshold = suspicion.effectiveThreshold(player.uniqueId, getAlertThreshold(material))
        if (excess < threshold) return

        suspicion.bumpFloor(player.uniqueId, SuspicionManager.DETERMINISTIC_FLOOR_BUMP / 2)
        val profile = suspects.getOrPut(player.uniqueId) { SuspectProfile(player.uniqueId, player.name) }
        profile.recordViolation(listOf(Discrepancy(material, 0, excess, excess)), emptyList())
        emitAlert(DupeAlert(
            type = AlertType.BALANCE_DISCREPANCY,
            player = player.uniqueId,
            playerName = player.name,
            material = material,
            details = "$excess ${material.name} picked up beyond nearby source output ($source)",
            severity = if (excess >= 4) Severity.CRITICAL else Severity.HIGH,
            timestamp = now
        ))
    }

    /** Low-confidence witness signal (unwitnessed-action pattern). Heat only, never alerts alone. */
    fun flagWitnessPattern(player: UUID) = suspicion.addHeat(player)

    // ===== Admin verdict loop =====

    /** Admin confirms a player is duping: pin suspicion high so future hits trip easily. */
    fun confirmSuspect(player: UUID) = suspicion.confirm(player)

    /** Admin clears a player (false positive): drop suspicion and remove from the suspect list. */
    fun clearVerdict(player: UUID) {
        suspicion.clear(player)
        suspects.remove(player)
    }

    fun suspicionOf(player: UUID): Double = suspicion.suspicion(player)
    fun decaySuspicion() = suspicion.decay()

    private fun getAlertThreshold(material: Material): Int =
        alertThresholds[material] ?: defaultAlertThreshold

    private fun calculateSeverity(material: Material, excess: Int): Severity {
        val baseValue = when (material) {
            Material.ENCHANTED_GOLDEN_APPLE -> 100
            Material.BEACON -> 80
            Material.NETHER_STAR -> 70
            Material.ELYTRA -> 90
            Material.NETHERITE_INGOT -> 50
            Material.DIAMOND_BLOCK -> 30
            else -> 10
        }

        val score = baseValue * excess
        return when {
            score >= 200 -> Severity.CRITICAL
            score >= 100 -> Severity.HIGH
            score >= 50 -> Severity.MEDIUM
            else -> Severity.LOW
        }
    }
}

data class ReconciliationResult(
    val player: UUID,
    val timestamp: Long,
    val discrepancies: List<Discrepancy> = emptyList(),
    val tmarViolations: List<TmarViolation> = emptyList(),
    val foreignItems: List<ForeignItemAlert> = emptyList(),
    val dupeDetected: Boolean = false,
    val skipped: Boolean = false,
    val reason: String? = null
)

data class Discrepancy(
    val material: Material,
    val expected: Int,          // From ledger
    val actual: Int,            // In inventory
    val excess: Int             // actual - expected
)

data class TmarViolation(
    val material: Material,
    val acquired: Int,          // Items acquired in window
    val limit: Int,             // TMAR limit
    val windowMinutes: Int
)

data class ForeignItemAlert(
    val material: Material,
    val amount: Int,
    val originalOwner: UUID,
    val slot: Int
)

data class QuickCheckResult(
    val material: Material,
    val ledgerBalance: Int,
    val actualCount: Int,
    val valid: Boolean
)

data class TmarCheckResult(
    val allowed: Boolean,
    val currentRate: Int = 0,
    val projectedRate: Int = 0,
    val limit: Int = 0,
    val excess: Int = 0
)

data class DupeAlert(
    val type: AlertType,
    val player: UUID,
    val playerName: String,
    val material: Material,
    val details: String,
    val severity: Severity,
    val timestamp: Long
)

enum class AlertType {
    BALANCE_DISCREPANCY,
    TMAR_EXCEEDED,
    FOREIGN_ITEM,
    ORPHAN_ITEM,
    CHAIN_INTEGRITY
}

enum class Severity {
    LOW, MEDIUM, HIGH, CRITICAL
}

/**
 * Tracks a player who has triggered dupe detection
 */
class SuspectProfile(
    val playerId: UUID,
    val playerName: String
) {
    val firstViolation: Long = System.currentTimeMillis()
    var lastViolation: Long = firstViolation
        private set
    var violationCount: Int = 0
        private set

    private val violations = mutableListOf<ViolationRecord>()

    fun recordViolation(discrepancies: List<Discrepancy>, tmarViolations: List<TmarViolation>) {
        val now = System.currentTimeMillis()
        lastViolation = now
        violationCount++

        violations.add(ViolationRecord(
            timestamp = now,
            discrepancies = discrepancies.toList(),
            tmarViolations = tmarViolations.toList()
        ))

        // Keep only last 100 violations
        if (violations.size > 100) {
            violations.removeAt(0)
        }
    }

    fun getRecentViolations(count: Int = 10): List<ViolationRecord> {
        return violations.takeLast(count)
    }

    fun getTotalExcess(): Map<Material, Int> {
        val totals = mutableMapOf<Material, Int>()
        for (violation in violations) {
            for (d in violation.discrepancies) {
                totals[d.material] = (totals[d.material] ?: 0) + d.excess
            }
        }
        return totals
    }
}

data class ViolationRecord(
    val timestamp: Long,
    val discrepancies: List<Discrepancy>,
    val tmarViolations: List<TmarViolation>
)
