package com.esmpfun.antidupe.ledger

import com.esmpfun.antidupe.platform.PlatformScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Compares a player's real inventory against their ledger balance; a surplus is a dupe candidate. */
class ReconciliationEngine(
    private val plugin: Plugin,
    private val ledgerStorage: LedgerStorage,
    private val ownershipManager: OwnershipManager,
    private val trackedMaterials: Set<Material>,
    private val tmarLimits: Map<Material, Int>,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val scheduler: PlatformScheduler,
    private val suspicion: SuspicionManager,
    private val reconciliationCooldown: Long = 5000L,
    private val alertThresholds: Map<Material, Int> = emptyMap(),
    private val defaultAlertThreshold: Int = 5,
    private val settle: SettleTracker = SettleTracker()
) {
    private val activeReconciliations = ConcurrentHashMap<UUID, Long>()
    private val suspects = ConcurrentHashMap<UUID, SuspectProfile>()
    private val alertListeners = CopyOnWriteArrayList<(DupeAlert) -> Unit>()

    @Volatile var healLogLevel: Level = Level.FINE

    private class SurplusMemory(@Volatile var amount: Int, @Volatile var seenAt: Long)
    private class Washed(@Volatile var amount: Int, @Volatile var since: Long)

    /** The largest unexplained surplus seen per player and material within [WASH_WINDOW_MS]. */
    private val recentSurplus = ConcurrentHashMap<Pair<UUID, Material>, SurplusMemory>()

    /** Surplus written off by a negative-ledger heal that followed it, summed within the window. */
    private val washedSurplus = ConcurrentHashMap<Pair<UUID, Material>, Washed>()

    private companion object {
        const val WASH_WINDOW_MS = 86_400_000L
        const val SETTLE_WAIT_MS = 10_000L
        const val SETTLE_POLL_MS = 50L
    }

    fun addAlertListener(listener: (DupeAlert) -> Unit) {
        alertListeners.add(listener)
    }

    private fun emitAlert(alert: DupeAlert) {
        alertListeners.forEach { it(alert) }
    }

    private class InventorySnapshot(
        val ownedCounts: Map<Material, Int>,
        val foreignItems: List<ForeignItem>,
        val settleVersion: Long,
        val settling: Boolean
    )

    /**
     * Must run on the player's own thread: Bukkit inventories and the BlockStateMeta
     * deserialization the deep scan performs are unsafe off-thread, and on Folia touching
     * them from Dispatchers.IO is a cross-region violation. Everything after this snapshot
     * works on the immutable copy, so storage I/O and threshold math stay off-thread.
     */
    private suspend fun snapshotInventory(player: Player): InventorySnapshot =
        suspendCancellableCoroutine { cont ->
            scheduler.runForEntity(player, Runnable {
                try {
                    // Read on the player's thread, where their clicks are handled, so no movement can
                    // start between these and the count.
                    val version = settle.version(player.uniqueId)
                    val settling = settle.isSettling(player.uniqueId)
                    cont.resume(InventorySnapshot(
                        ownedCounts = ownershipManager.snapshotOwnedDeep(player, trackedMaterials),
                        foreignItems = ownershipManager.findForeignItemsDeep(player),
                        settleVersion = version,
                        settling = settling
                    ))
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            })
        }

    suspend fun snapshotOwned(player: Player): Map<Material, Int> =
        snapshotInventory(player).ownedCounts

    /**
     * Items that just moved are in the inventory before their ledger entry is written, and a
     * movement after the count changes the ledger before it is read. Either way the two disagree,
     * so a count and its balances only stand if nothing was settling and nothing moved between
     * them. Null when the player never stopped moving items for [SETTLE_WAIT_MS].
     */
    private suspend fun settledSnapshot(player: Player, started: Long): Pair<InventorySnapshot, Map<Material, Int>>? {
        val playerId = player.uniqueId
        while (true) {
            if (!settle.isSettling(playerId)) {
                val candidate = snapshotInventory(player)
                if (!candidate.settling) {
                    val balances = trackedMaterials.associateWith { ledgerStorage.getBalance(playerId, it) }
                    if (!settle.isSettling(playerId) && settle.version(playerId) == candidate.settleVersion) {
                        return candidate to balances
                    }
                }
            }
            if (System.currentTimeMillis() - started >= SETTLE_WAIT_MS) return null
            delay(SETTLE_POLL_MS)
        }
    }

    /** [ignoreCooldown] is for admin commands, which must always see a fresh count. */
    suspend fun reconcile(player: Player, ignoreCooldown: Boolean = false): ReconciliationResult {
        val playerId = player.uniqueId
        val started = System.currentTimeMillis()

        val lastReconcile = activeReconciliations[playerId]
        if (!ignoreCooldown && lastReconcile != null && started - lastReconcile < reconciliationCooldown) {
            return ReconciliationResult(
                player = playerId,
                timestamp = started,
                skipped = true,
                reason = "Cooldown active"
            )
        }
        activeReconciliations[playerId] = started

        val (snapshot, balances) = settledSnapshot(player, started)
            ?: return ReconciliationResult(
                player = playerId,
                timestamp = System.currentTimeMillis(),
                skipped = true,
                reason = "Player is still moving items"
            )
        val now = System.currentTimeMillis()

        val discrepancies = mutableListOf<Discrepancy>()
        val tmarViolations = mutableListOf<TmarViolation>()

        for (material in trackedMaterials) {
            val ledgerBalance = balances[material] ?: 0
            val actualCount = snapshot.ownedCounts[material] ?: 0

            val key = playerId to material

            // A negative ledger normally means an acquisition went unobserved (/give, a shop
            // plugin's addItem), and the real inventory is adopted as the new baseline. Those
            // items carry no tag, so they never showed up as a surplus first. A heal that follows
            // a recent tagged surplus is the surplus being stored away and written off, which is
            // how a small dupe is washed, so that part is counted instead of forgiven.
            if (ledgerBalance < 0) {
                val correction = actualCount - ledgerBalance
                ledgerStorage.appendBuilt(
                    player = playerId,
                    action = LedgerAction.ADMIN_GIVE,
                    material = material,
                    quantity = correction,
                    metadata = LedgerMetadata(notes = "BASELINE_HEAL: ledger $ledgerBalance -> $actualCount")
                )
                logger.log(healLogLevel, "[CoC] Re-baselined ${player.name}/$material: ledger was $ledgerBalance, adopted actual $actualCount (incomplete-acquisition gap)")
                checkWashedSurplus(player, material, -ledgerBalance, now)
                continue
            }

            if (actualCount > ledgerBalance) {
                val excess = actualCount - ledgerBalance
                discrepancies.add(Discrepancy(material, ledgerBalance, actualCount, excess))
                recentSurplus.compute(key) { _, memory ->
                    if (memory == null || now - memory.seenAt > WASH_WINDOW_MS) SurplusMemory(excess, now)
                    else memory.also { it.amount = maxOf(it.amount, excess); it.seenAt = now }
                }

                val threshold = suspicion.effectiveThreshold(playerId, getAlertThreshold(material))
                if (excess >= threshold) {
                    suspicion.bumpFloor(playerId, SuspicionManager.DETERMINISTIC_FLOOR_BUMP / 2)
                    emitAlert(DupeAlert(
                        type = AlertType.BALANCE_DISCREPANCY,
                        player = playerId,
                        playerName = player.name,
                        material = material,
                        details = "Has $actualCount but ledger shows $ledgerBalance (excess: $excess)",
                        severity = calculateSeverity(material, excess),
                        timestamp = now,
                        excess = excess,
                        messageKey = "alerts.balance-discrepancy",
                        placeholders = mapOf(
                            "actual" to "$actualCount", "expected" to "$ledgerBalance", "excess" to "$excess"
                        )
                    ))
                }
            }

            // Legitimate farms and shop buyouts burst hard, so a rate breach only nudges heat.
            // It deliberately never fires an alert of its own.
            val tmarLimit = tmarLimits[material]
            if (tmarLimit != null) {
                val recentAcquisitions = ledgerStorage.getRecentAcquisitions(playerId, material)
                if (recentAcquisitions > tmarLimit) {
                    tmarViolations.add(TmarViolation(material, recentAcquisitions, tmarLimit, 5))
                    suspicion.addHeat(playerId)
                }
            }
        }

        val foreignAlerts = snapshot.foreignItems.map { foreign ->
            ForeignItemAlert(
                material = foreign.item.type,
                amount = foreign.item.amount,
                originalOwner = foreign.originalOwner,
                slot = foreign.slot
            )
        }

        val alertWorthy = discrepancies.filter {
            it.excess >= suspicion.effectiveThreshold(playerId, getAlertThreshold(it.material))
        }
        val dupeDetected = alertWorthy.isNotEmpty()

        if (dupeDetected) {
            val profile = suspects.computeIfAbsent(playerId) { SuspectProfile(playerId, player.name) }
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

    suspend fun quickCheck(player: Player, material: Material): QuickCheckResult {
        val playerId = player.uniqueId
        val ledgerBalance = ledgerStorage.getBalance(playerId, material)
        val actualCount = snapshotInventory(player).ownedCounts[material] ?: 0

        return QuickCheckResult(
            material = material,
            ledgerBalance = ledgerBalance,
            actualCount = actualCount,
            valid = actualCount <= ledgerBalance
        )
    }

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

    fun getSuspect(playerId: UUID): SuspectProfile? = suspects[playerId]

    fun getAllSuspects(): List<SuspectProfile> = suspects.values.toList()

    fun clearSuspect(playerId: UUID) {
        suspects.remove(playerId)
    }

    fun reconcileAsync(
        player: Player,
        ignoreCooldown: Boolean = false,
        callback: ((ReconciliationResult) -> Unit)? = null
    ) {
        scope.launch {
            val result = reconcile(player, ignoreCooldown)
            callback?.invoke(result)
        }
    }

    /** Highest-confidence signal we produce, so it alerts unconditionally. */
    fun flagEntityDupe(player: Player, material: Material, amount: Int, previous: PreviousPickup) {
        val now = System.currentTimeMillis()
        suspicion.bumpFloor(player.uniqueId)
        val profile = suspects.computeIfAbsent(player.uniqueId) { SuspectProfile(player.uniqueId, player.name) }
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
            timestamp = now,
            excess = amount,
            messageKey = "alerts.entity-dupe",
            placeholders = mapOf("seconds" to "${(now - previous.pickedUpAt) / 1000}")
        ))
    }

    /**
     * Pickup beyond what nearby sources could have produced. Gated rather than unconditional:
     * re-picking up your own dropped item next to a mob death looks the same.
     */
    fun flagDropExcess(player: Player, material: Material, excess: Int, source: String) {
        val now = System.currentTimeMillis()
        suspicion.addHeat(player.uniqueId, SuspicionManager.HEAT_PER_SIGNAL * 3)
        val threshold = suspicion.effectiveThreshold(player.uniqueId, getAlertThreshold(material))
        if (excess < threshold) return

        suspicion.bumpFloor(player.uniqueId, SuspicionManager.DETERMINISTIC_FLOOR_BUMP / 2)
        val profile = suspects.computeIfAbsent(player.uniqueId) { SuspectProfile(player.uniqueId, player.name) }
        profile.recordViolation(listOf(Discrepancy(material, 0, excess, excess)), emptyList())
        emitAlert(DupeAlert(
            type = AlertType.BALANCE_DISCREPANCY,
            player = player.uniqueId,
            playerName = player.name,
            material = material,
            details = "$excess ${material.name} picked up beyond nearby source output ($source)",
            severity = if (excess >= 4) Severity.CRITICAL else Severity.HIGH,
            timestamp = now,
            excess = excess,
            messageKey = "alerts.drop-excess",
            placeholders = mapOf(
                "excess" to "$excess", "material" to material.name, "source" to source
            )
        ))
    }

    private fun checkWashedSurplus(player: Player, material: Material, writtenOff: Int, now: Long) {
        val key = player.uniqueId to material
        val memory = recentSurplus[key] ?: return
        if (now - memory.seenAt > WASH_WINDOW_MS) {
            recentSurplus.remove(key)
            return
        }
        val washedNow = minOf(writtenOff, memory.amount)
        if (washedNow <= 0) return
        memory.amount -= washedNow
        if (memory.amount <= 0) recentSurplus.remove(key)

        val total = washedSurplus.compute(key) { _, w ->
            if (w == null || now - w.since > WASH_WINDOW_MS) Washed(washedNow, now)
            else w.also { it.amount += washedNow }
        }!!.amount
        logger.fine("[CoC] ${player.name}/$material: $washedNow unexplained surplus written off by a heal ($total within a day)")

        if (total < suspicion.effectiveThreshold(player.uniqueId, getAlertThreshold(material))) return
        washedSurplus.remove(key)
        suspicion.bumpFloor(player.uniqueId, SuspicionManager.DETERMINISTIC_FLOOR_BUMP / 2)
        val profile = suspects.computeIfAbsent(player.uniqueId) { SuspectProfile(player.uniqueId, player.name) }
        profile.recordViolation(listOf(Discrepancy(material, 0, total, total)), emptyList())
        // excess stays 0: the items were already stored away and adopted, so there is nothing
        // in the inventory that removal could safely take.
        emitAlert(DupeAlert(
            type = AlertType.BALANCE_DISCREPANCY,
            player = player.uniqueId,
            playerName = player.name,
            material = material,
            details = "$total ${material.name} carried without explanation, then stored away and written off",
            severity = calculateSeverity(material, total),
            timestamp = now,
            messageKey = "alerts.washed-surplus",
            placeholders = mapOf("excess" to "$total", "material" to material.name)
        ))
    }

    /**
     * More of [owner]'s tagged items came back out of containers, frames or the ground than ever
     * went in, so copies were made outside anyone's inventory. Alert only: the taker may be
     * innocent, so no suspicion is added and nothing is removed.
     */
    fun flagWorldStockDeficit(
        actor: UUID, actorName: String, ownerName: String, material: Material, deficit: Int, where: String?
    ) {
        emitAlert(DupeAlert(
            type = AlertType.BALANCE_DISCREPANCY,
            player = actor,
            playerName = actorName,
            material = material,
            details = "$deficit ${material.name} belonging to $ownerName came out of storage beyond what was ever put in" +
                (where?.let { " (last taken at $it)" } ?: ""),
            severity = calculateSeverity(material, deficit),
            timestamp = System.currentTimeMillis(),
            messageKey = "alerts.world-stock",
            placeholders = mapOf(
                "excess" to "$deficit", "material" to material.name,
                "owner" to ownerName, "where" to (where ?: "?")
            )
        ))
    }

    fun flagWitnessPattern(player: UUID) = suspicion.addHeat(player)

    fun confirmSuspect(player: UUID) = suspicion.confirm(player)

    fun clearVerdict(player: UUID) {
        suspicion.clear(player)
        suspects.remove(player)
        recentSurplus.keys.removeIf { it.first == player }
        washedSurplus.keys.removeIf { it.first == player }
    }

    fun suspicionOf(player: UUID): Double = suspicion.suspicion(player)
    fun decaySuspicion() = suspicion.decay()

    fun alertThresholdFor(material: Material): Int = getAlertThreshold(material)

    private fun getAlertThreshold(material: Material): Int =
        alertThresholds[material] ?: defaultAlertThreshold

    private fun calculateSeverity(material: Material, excess: Int): Severity {
        val threshold = getAlertThreshold(material).coerceAtLeast(1)
        val ratio = excess.toDouble() / threshold
        return when {
            ratio >= 4.0 -> Severity.CRITICAL
            ratio >= 2.0 -> Severity.HIGH
            ratio >= 1.0 -> Severity.MEDIUM
            else -> Severity.LOW
        }
    }

    fun pruneMaintenance() {
        val now = System.currentTimeMillis()
        val cutoff = now - 3_600_000L
        activeReconciliations.entries.removeIf { it.value < cutoff }
        recentSurplus.entries.removeIf { now - it.value.seenAt > WASH_WINDOW_MS }
        washedSurplus.entries.removeIf { now - it.value.since > WASH_WINDOW_MS }
        settle.prune()
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
    val expected: Int,
    val actual: Int,
    val excess: Int
)

data class TmarViolation(
    val material: Material,
    val acquired: Int,
    val limit: Int,
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
    val timestamp: Long,
    // Enforcement removes at most this many items; 0 means no countable surplus and is
    // never acted on automatically.
    val excess: Int = 0,
    // The display layer formats messageKey plus placeholders through messages.yml, while
    // `details` stays English so console logs remain searchable.
    val messageKey: String = "",
    val placeholders: Map<String, String> = emptyMap(),
    val afterUncleanShutdown: Boolean = false
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
