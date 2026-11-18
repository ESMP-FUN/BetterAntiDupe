package com.esmpfun.antidupe.ledger

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Treats nearby players as witnesses to a tracked action. A single unwitnessed action means
 * nothing; a sustained run of them while others are around is what the signal is looking for.
 */
class WitnessManager(
    private val plugin: Plugin,
    witnessRadius: Double = 48.0,
    private val verifiedThreshold: Int = 3,
    private val suspiciousSoloRatio: Double = 0.8
) {
    private val radiusSquared = witnessRadius * witnessRadius

    private val trustScores = ConcurrentHashMap<UUID, TrustScore>()

    // Hold each list's own monitor while iterating or filtering it.
    private val witnessHistory = ConcurrentHashMap<UUID, MutableList<WitnessRecord>>()

    private fun historyFor(id: UUID): MutableList<WitnessRecord> =
        witnessHistory.computeIfAbsent(id) { Collections.synchronizedList(mutableListOf()) }

    fun getNearbyWitnesses(actor: Player, location: Location): List<Player> {
        return location.world?.players?.filter { player ->
            player.uniqueId != actor.uniqueId &&
            !isExcludedWitness(player) &&
            player.location.distanceSquared(location) <= radiusSquared &&
            player.canSee(actor)
        } ?: emptyList()
    }

    /**
     * Ignores line of sight on purpose: this only answers whether anyone could plausibly
     * have seen the action, so playing alone never accrues suspicion.
     */
    fun othersNearby(actor: Player): Boolean {
        val world = actor.world
        val loc = actor.location
        return world.players.any {
            it.uniqueId != actor.uniqueId &&
            !isExcludedWitness(it) &&
            it.location.distanceSquared(loc) <= radiusSquared
        }
    }

    /**
     * A vanished admin standing next to a duper would otherwise turn a solo action into a
     * corroborated one. "vanished" is the shared metadata flag EssentialsX, SuperVanish and
     * PremiumVanish all set, so reading it avoids a hard dependency on any of them.
     */
    private fun isExcludedWitness(player: Player): Boolean {
        if (player.hasPermission("antidupe.witness.exempt")) return true
        return try {
            player.getMetadata("vanished").any { it.asBoolean() }
        } catch (e: Exception) {
            false
        }
    }

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

        val signature = generateSignature(
            actionId = actionId,
            actor = actor.uniqueId,
            witnesses = witnessIds,
            timestamp = System.currentTimeMillis(),
            actionType = actionType,
            itemDetails = itemDetails
        )

        val record = WitnessRecord(
            actionId = actionId,
            timestamp = System.currentTimeMillis(),
            witnessCount = witnesses.size,
            trustLevel = trustLevel
        )
        historyFor(actor.uniqueId).add(record)

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
            .take(16)
    }

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

        val recentSolo = recent.filter {
            it.trustLevel == TrustLevel.SOLO &&
            it.timestamp >= System.currentTimeMillis() - 300_000
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

    fun getTrustScore(playerId: UUID): TrustScore {
        return trustScores.computeIfAbsent(playerId) { TrustScore(playerId) }
    }

    private fun updateTrustScore(playerId: UUID, trustLevel: TrustLevel) {
        val score = trustScores.computeIfAbsent(playerId) { TrustScore(playerId) }
        score.recordAction(trustLevel)
    }

    fun pruneHistory() {
        val cutoff = System.currentTimeMillis() - 3600_000 * 24
        witnessHistory.forEach { (_, records) ->
            synchronized(records) { records.removeIf { it.timestamp < cutoff } }
        }
        witnessHistory.entries.removeIf { synchronized(it.value) { it.value.isEmpty() } }
    }

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

enum class TrustLevel {
    VERIFIED,
    CORROBORATED,
    SOLO,
    CONTESTED,
    UNVERIFIED
}

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
                witnesses = emptyList(),  // The compact form does not carry the witness list
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
                score = minOf(100.0, score + 0.5)
            }
            TrustLevel.CORROBORATED -> {
                corroboratedCount++
                score = minOf(100.0, score + 0.1)
            }
            TrustLevel.SOLO -> {
                soloCount++
                // No penalty: a single unwitnessed action is normal, only the pattern counts.
            }
            TrustLevel.CONTESTED -> {
                contestedCount++
                score = maxOf(0.0, score - 10.0)
            }
            TrustLevel.UNVERIFIED -> {
            }
        }
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
