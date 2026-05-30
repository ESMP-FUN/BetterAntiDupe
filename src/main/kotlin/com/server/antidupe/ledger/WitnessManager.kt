package com.server.antidupe.ledger

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Proof of Witness (PoW) - A mesh network consensus system for item tracking.
 *
 * When a player performs a tracked action (mine, pickup, drop, etc.), nearby
 * players act as "witnesses" who can validate that the action occurred.
 *
 * This creates a decentralized trust model where:
 * - Actions with multiple witnesses are highly trusted
 * - Actions with no witnesses (solo) are flagged for extra scrutiny
 * - Patterns of unwitnessed acquisitions indicate suspicious behavior
 * - Duping exploits that bypass server events have no witnesses
 *
 * Trust Levels:
 *   VERIFIED   - 3+ witnesses, cryptographically signed
 *   CORROBORATED - 1-2 witnesses present
 *   SOLO       - No witnesses (not suspicious alone, but patterns matter)
 *   CONTESTED  - Witness reports conflict with actor's claim
 *   UNVERIFIED - Unable to determine witness status
 */
class WitnessManager(
    private val plugin: Plugin,
    witnessRadius: Double = 48.0,
    private val verifiedThreshold: Int = 3,
    private val suspiciousSoloRatio: Double = 0.8
) {
    private val radiusSquared = witnessRadius * witnessRadius

    // Pending attestation requests: actionId -> pending witnesses
    private val pendingAttestations = ConcurrentHashMap<UUID, AttestationRequest>()

    // Player trust scores based on witness history
    private val trustScores = ConcurrentHashMap<UUID, TrustScore>()

    // Recent witnessed events for each player (sliding window). Lists are synchronized.
    private val witnessHistory = ConcurrentHashMap<UUID, MutableList<WitnessRecord>>()

    private fun historyFor(id: UUID): MutableList<WitnessRecord> =
        witnessHistory.getOrPut(id) { Collections.synchronizedList(mutableListOf()) }

    /**
     * Get all players who can witness an action at a location.
     */
    fun getNearbyWitnesses(actor: Player, location: Location): List<Player> {
        return location.world?.players?.filter { player ->
            player.uniqueId != actor.uniqueId &&
            player.location.distanceSquared(location) <= radiusSquared &&
            player.canSee(actor)
        } ?: emptyList()
    }

    /**
     * Create a witness attestation for an action.
     * Returns the witnesses and a trust level.
     */
    fun attestAction(
        actor: Player,
        actionId: UUID,
        actionType: LedgerAction,
        location: Location,
        itemDetails: String
    ): WitnessAttestation {
        val witnesses = getNearbyWitnesses(actor, location)
        val witnessIds = witnesses.map { it.uniqueId }

        val trustLevel = when {
            witnesses.size >= verifiedThreshold -> TrustLevel.VERIFIED
            witnesses.isNotEmpty() -> TrustLevel.CORROBORATED
            else -> TrustLevel.SOLO
        }

        // Generate attestation signature
        val signature = generateSignature(
            actionId = actionId,
            actor = actor.uniqueId,
            witnesses = witnessIds,
            timestamp = System.currentTimeMillis(),
            actionType = actionType,
            itemDetails = itemDetails
        )

        // Record this in witness history
        val record = WitnessRecord(
            actionId = actionId,
            timestamp = System.currentTimeMillis(),
            witnessCount = witnesses.size,
            trustLevel = trustLevel
        )
        historyFor(actor.uniqueId).add(record)

        // Update trust score
        updateTrustScore(actor.uniqueId, trustLevel)

        return WitnessAttestation(
            actionId = actionId,
            actor = actor.uniqueId,
            witnesses = witnessIds,
            trustLevel = trustLevel,
            signature = signature,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Generate a cryptographic signature for an attestation.
     * This binds the witnesses to the specific action.
     */
    private fun generateSignature(
        actionId: UUID,
        actor: UUID,
        witnesses: List<UUID>,
        timestamp: Long,
        actionType: LedgerAction,
        itemDetails: String
    ): String {
        val payload = buildString {
            append(actionId)
            append("|")
            append(actor)
            append("|")
            append(witnesses.sorted().joinToString(","))
            append("|")
            append(timestamp)
            append("|")
            append(actionType.name)
            append("|")
            append(itemDetails)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)  // Short signature for storage
    }

    /**
     * Calculate witness ratio for a player.
     * High ratio = most actions are witnessed = trustworthy
     * Low ratio = most actions are solo = needs scrutiny
     */
    fun getWitnessRatio(playerId: UUID): WitnessRatio {
        val history = witnessHistory[playerId] ?: return WitnessRatio(0, 0, 0.0)
        val cutoff = System.currentTimeMillis() - 3600_000
        val recent = synchronized(history) { history.filter { it.timestamp >= cutoff } }

        if (recent.isEmpty()) return WitnessRatio(0, 0, 0.0)

        val witnessed = recent.count { it.witnessCount > 0 }
        val total = recent.size
        val ratio = witnessed.toDouble() / total

        return WitnessRatio(witnessed, total, ratio)
    }

    /**
     * Check if a player has a suspicious witness pattern.
     * Returns true if they have many unwitnessed acquisitions.
     */
    fun hasSuspiciousPattern(playerId: UUID): SuspicionAnalysis {
        val history = witnessHistory[playerId] ?: return SuspicionAnalysis(
            suspicious = false,
            reason = "No history"
        )

        val recent = synchronized(history) {
            history.filter { it.timestamp >= System.currentTimeMillis() - 3600_000 }
        }

        if (recent.size < 10) {
            return SuspicionAnalysis(
                suspicious = false,
                reason = "Insufficient data (${recent.size} actions)"
            )
        }

        val soloCount = recent.count { it.trustLevel == TrustLevel.SOLO }
        val soloRatio = soloCount.toDouble() / recent.size

        val onlinePlayers = Bukkit.getOnlinePlayers().size
        if (soloRatio > suspiciousSoloRatio && onlinePlayers >= 5) {
            return SuspicionAnalysis(
                suspicious = true,
                reason = "High solo ratio (${(soloRatio * 100).toInt()}%) with $onlinePlayers players online",
                soloRatio = soloRatio,
                soloCount = soloCount,
                totalActions = recent.size
            )
        }

        // Check for burst of unwitnessed acquisitions
        val recentSolo = recent.filter {
            it.trustLevel == TrustLevel.SOLO &&
            it.timestamp >= System.currentTimeMillis() - 300_000  // Last 5 min
        }
        if (recentSolo.size >= 20) {
            return SuspicionAnalysis(
                suspicious = true,
                reason = "${recentSolo.size} unwitnessed actions in 5 minutes",
                soloRatio = soloRatio,
                soloCount = soloCount,
                totalActions = recent.size
            )
        }

        return SuspicionAnalysis(
            suspicious = false,
            reason = "Pattern within normal bounds",
            soloRatio = soloRatio,
            soloCount = soloCount,
            totalActions = recent.size
        )
    }

    /**
     * Get the trust score for a player.
     */
    fun getTrustScore(playerId: UUID): TrustScore {
        return trustScores.getOrPut(playerId) { TrustScore(playerId) }
    }

    private fun updateTrustScore(playerId: UUID, trustLevel: TrustLevel) {
        val score = trustScores.getOrPut(playerId) { TrustScore(playerId) }
        score.recordAction(trustLevel)
    }

    /**
     * Clean up old history entries (call periodically)
     */
    fun pruneHistory() {
        val cutoff = System.currentTimeMillis() - 3600_000 * 24
        witnessHistory.forEach { (_, records) ->
            synchronized(records) { records.removeIf { it.timestamp < cutoff } }
        }
        witnessHistory.entries.removeIf { synchronized(it.value) { it.value.isEmpty() } }
    }

    /**
     * Get detailed witness stats for admin inspection
     */
    fun getPlayerStats(playerId: UUID): WitnessStats {
        val historyRef = witnessHistory[playerId]
        val snapshot: List<WitnessRecord> = if (historyRef == null) emptyList()
            else synchronized(historyRef) { historyRef.toList() }
        val trustScore = getTrustScore(playerId)
        val ratio = getWitnessRatio(playerId)
        val suspicion = hasSuspiciousPattern(playerId)

        return WitnessStats(
            playerId = playerId,
            totalActions = snapshot.size,
            witnessedActions = snapshot.count { it.witnessCount > 0 },
            verifiedActions = snapshot.count { it.trustLevel == TrustLevel.VERIFIED },
            soloActions = snapshot.count { it.trustLevel == TrustLevel.SOLO },
            trustScore = trustScore.score,
            witnessRatio = ratio.ratio,
            isSuspicious = suspicion.suspicious,
            suspicionReason = suspicion.reason
        )
    }
}

/**
 * Trust levels for witnessed actions
 */
enum class TrustLevel {
    VERIFIED,      // 3+ witnesses, cryptographically signed
    CORROBORATED,  // 1-2 witnesses present
    SOLO,          // No witnesses (not inherently suspicious)
    CONTESTED,     // Witnesses disagree with claimed action
    UNVERIFIED     // Unable to determine
}

/**
 * Attestation record for a witnessed action
 */
data class WitnessAttestation(
    val actionId: UUID,
    val actor: UUID,
    val witnesses: List<UUID>,
    val trustLevel: TrustLevel,
    val signature: String,
    val timestamp: Long
) {
    fun toMetadataString(): String {
        return "${trustLevel.name}:${witnesses.size}:$signature"
    }

    companion object {
        fun fromMetadataString(actionId: UUID, actor: UUID, data: String): WitnessAttestation? {
            val parts = data.split(":")
            if (parts.size != 3) return null

            return WitnessAttestation(
                actionId = actionId,
                actor = actor,
                witnesses = emptyList(),  // Not stored in compact form
                trustLevel = TrustLevel.valueOf(parts[0]),
                signature = parts[2],
                timestamp = System.currentTimeMillis()
            )
        }
    }
}

data class WitnessRecord(
    val actionId: UUID,
    val timestamp: Long,
    val witnessCount: Int,
    val trustLevel: TrustLevel
)

data class WitnessRatio(
    val witnessed: Int,
    val total: Int,
    val ratio: Double
)

data class SuspicionAnalysis(
    val suspicious: Boolean,
    val reason: String,
    val soloRatio: Double = 0.0,
    val soloCount: Int = 0,
    val totalActions: Int = 0
)

data class AttestationRequest(
    val actionId: UUID,
    val actor: UUID,
    val expectedWitnesses: List<UUID>,
    val createdAt: Long,
    val receivedAttestations: MutableList<UUID> = mutableListOf()
)

/**
 * Running trust score for a player based on witness history
 */
class TrustScore(val playerId: UUID) {
    var score: Double = 100.0
        private set

    var verifiedCount: Int = 0
        private set
    var corroboratedCount: Int = 0
        private set
    var soloCount: Int = 0
        private set
    var contestedCount: Int = 0
        private set

    fun recordAction(trustLevel: TrustLevel) {
        when (trustLevel) {
            TrustLevel.VERIFIED -> {
                verifiedCount++
                score = minOf(100.0, score + 0.5)  // Slow increase
            }
            TrustLevel.CORROBORATED -> {
                corroboratedCount++
                score = minOf(100.0, score + 0.1)
            }
            TrustLevel.SOLO -> {
                soloCount++
                // Solo doesn't decrease score unless pattern emerges
            }
            TrustLevel.CONTESTED -> {
                contestedCount++
                score = maxOf(0.0, score - 10.0)  // Big penalty
            }
            TrustLevel.UNVERIFIED -> {
                // No change
            }
        }
    }

    fun penalize(amount: Double, reason: String) {
        score = maxOf(0.0, score - amount)
    }
}

data class WitnessStats(
    val playerId: UUID,
    val totalActions: Int,
    val witnessedActions: Int,
    val verifiedActions: Int,
    val soloActions: Int,
    val trustScore: Double,
    val witnessRatio: Double,
    val isSuspicious: Boolean,
    val suspicionReason: String
)
