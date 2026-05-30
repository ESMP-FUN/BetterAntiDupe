package com.server.antidupe.ledger

import org.bukkit.Location
import org.bukkit.Material
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Represents a single immutable entry in the Chain of Custody Ledger.
 * Each entry records an item movement event with full context.
 *
 * The ledger forms a hash chain (blockchain-like) where each entry
 * references the hash of the previous entry, making tampering detectable.
 */
data class LedgerEntry(
    val id: UUID,
    val timestamp: Long,
    val player: UUID,
    val action: LedgerAction,
    val material: Material,
    val quantity: Int,              // Positive = gain, Negative = loss
    val metadata: LedgerMetadata,
    val prevHash: String?,          // Hash of previous entry (null for genesis)
    val hash: String                // SHA-256 of this entry's contents
) {
    companion object {
        /**
         * Create a new entry with computed hash
         */
        fun create(
            player: UUID,
            action: LedgerAction,
            material: Material,
            quantity: Int,
            metadata: LedgerMetadata,
            prevHash: String?
        ): LedgerEntry {
            val id = UUID.randomUUID()
            val timestamp = System.currentTimeMillis()

            val hash = computeHash(id, timestamp, player, action, material, quantity, prevHash)

            return LedgerEntry(
                id = id,
                timestamp = timestamp,
                player = player,
                action = action,
                material = material,
                quantity = quantity,
                metadata = metadata,
                prevHash = prevHash,
                hash = hash
            )
        }

        private fun computeHash(
            id: UUID,
            timestamp: Long,
            player: UUID,
            action: LedgerAction,
            material: Material,
            quantity: Int,
            prevHash: String?
        ): String {
            val payload = "$id|$timestamp|$player|${action.name}|${material.name}|$quantity|${prevHash ?: "GENESIS"}"
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        fun fromJson(json: String): LedgerEntry {
            val obj = JSONObject(json)
            return LedgerEntry(
                id = UUID.fromString(obj.getString("id")),
                timestamp = obj.getLong("timestamp"),
                player = UUID.fromString(obj.getString("player")),
                action = LedgerAction.valueOf(obj.getString("action")),
                material = Material.valueOf(obj.getString("material")),
                quantity = obj.getInt("quantity"),
                metadata = LedgerMetadata.fromJson(obj.getJSONObject("metadata")),
                prevHash = obj.optStringOrNull("prevHash"),
                hash = obj.getString("hash")
            )
        }

        internal fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("id", id.toString())
            put("timestamp", timestamp)
            put("player", player.toString())
            put("action", action.name)
            put("material", material.name)
            put("quantity", quantity)
            put("metadata", metadata.toJsonObject())
            put("prevHash", prevHash)
            put("hash", hash)
        }.toString()
    }

    /**
     * Verify this entry's hash matches its contents
     */
    fun verifyIntegrity(): Boolean {
        val expectedHash = computeHash(id, timestamp, player, action, material, quantity, prevHash)
        return hash == expectedHash
    }
}

/**
 * All possible item movement actions tracked by the ledger.
 * Each action has an implicit sign (gain/loss) but quantity
 * should always be explicitly signed for clarity.
 */
enum class LedgerAction {
    // Acquisition actions (quantity should be positive)
    MINE,               // Broke a block, received drops
    CRAFT,              // Crafted an item
    PICKUP,             // Picked up item entity from ground
    RECEIVE,            // Received from another player (trade/give)
    CONTAINER_TAKE,     // Took from chest/shulker/barrel
    ENTITY_TAKE,        // Took from horse/donkey/llama/chest boat/chest minecart
    FRAME_TAKE,         // Removed an item from an item frame
    ADMIN_GIVE,         // /give command, creative spawn, or system grant
    STATION_OUTPUT,     // Took the result of a workstation (anvil/smithing/loom/etc)
    SMELT,              // Output from furnace
    LOOT,               // Mob drop, chest loot, fishing
    VILLAGER_TRADE,     // Bought from villager

    // Disposal actions (quantity should be negative)
    PLACE,              // Placed as block in world
    DROP,               // Dropped on ground intentionally
    GIVE,               // Gave to another player
    CONTAINER_PUT,      // Put in chest/shulker/barrel
    ENTITY_PUT,         // Put in horse/donkey/llama/chest boat/chest minecart
    FRAME_PUT,          // Placed an item into an item frame
    CONSUME,            // Ate food, used tool durability
    DESTROY,            // Burned in lava, fell in void
    DESPAWN,            // Item entity despawned (5 min timer)
    VILLAGER_BUY,       // Sold to villager

    // Neutral actions (quantity = 0, tracking events)
    TRANSFER,           // Moved within same inventory
    SPLIT,              // Stack was split (informational)
    MERGE,              // Stacks merged (informational)
    OWNERSHIP_CHANGE,   // Item changed hands (updates NBT)
    RECONCILE           // Balance was audited
}

/**
 * Contextual metadata for a ledger entry.
 * Provides audit trail details without affecting the core transaction.
 */
data class LedgerMetadata(
    val worldName: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val relatedPlayer: UUID? = null,        // Other party in transfers
    val containerType: String? = null,       // CHEST, SHULKER_BOX, etc.
    val containerLocation: String? = null,   // Serialized location
    val sourceEntryId: UUID? = null,         // For RECEIVE: the GIVE that caused it
    val blockType: Material? = null,         // For MINE: what block was broken
    val toolUsed: Material? = null,          // What tool was used
    val enchantments: String? = null,        // Relevant enchants (Fortune, etc.)
    val notes: String? = null,               // Debug/admin notes

    // Proof of Witness (PoW) fields
    val witnesses: List<UUID>? = null,       // UUIDs of players who witnessed this action
    val witnessCount: Int? = null,           // Number of witnesses (for quick queries)
    val trustLevel: String? = null,          // VERIFIED, CORROBORATED, SOLO, CONTESTED
    val witnessSignature: String? = null     // Cryptographic attestation signature
) {
    companion object {
        fun fromLocation(loc: Location?): LedgerMetadata {
            return LedgerMetadata(
                worldName = loc?.world?.name,
                x = loc?.x,
                y = loc?.y,
                z = loc?.z
            )
        }

        fun fromJson(obj: JSONObject): LedgerMetadata {
            // Parse witness list if present
            val witnesses = if (obj.has("witnesses")) {
                val arr = obj.getJSONArray("witnesses")
                (0 until arr.length()).map { UUID.fromString(arr.getString(it)) }
            } else null

            fun s(k: String) = if (obj.has(k) && !obj.isNull(k)) obj.getString(k) else null
            return LedgerMetadata(
                worldName = s("worldName"),
                x = if (obj.has("x") && !obj.isNull("x")) obj.getDouble("x") else null,
                y = if (obj.has("y") && !obj.isNull("y")) obj.getDouble("y") else null,
                z = if (obj.has("z") && !obj.isNull("z")) obj.getDouble("z") else null,
                relatedPlayer = s("relatedPlayer")?.let { UUID.fromString(it) },
                containerType = s("containerType"),
                containerLocation = s("containerLocation"),
                sourceEntryId = s("sourceEntryId")?.let { UUID.fromString(it) },
                blockType = s("blockType")?.let { Material.valueOf(it) },
                toolUsed = s("toolUsed")?.let { Material.valueOf(it) },
                enchantments = s("enchantments"),
                notes = s("notes"),
                witnesses = witnesses,
                witnessCount = if (obj.has("witnessCount") && !obj.isNull("witnessCount")) obj.getInt("witnessCount") else null,
                trustLevel = s("trustLevel"),
                witnessSignature = s("witnessSignature")
            )
        }
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            worldName?.let { put("worldName", it) }
            x?.let { put("x", it) }
            y?.let { put("y", it) }
            z?.let { put("z", it) }
            relatedPlayer?.let { put("relatedPlayer", it.toString()) }
            containerType?.let { put("containerType", it) }
            containerLocation?.let { put("containerLocation", it) }
            sourceEntryId?.let { put("sourceEntryId", it.toString()) }
            blockType?.let { put("blockType", it.name) }
            toolUsed?.let { put("toolUsed", it.name) }
            enchantments?.let { put("enchantments", it) }
            notes?.let { put("notes", it) }
            // Proof of Witness fields
            witnesses?.let { list -> put("witnesses", list.map { it.toString() }) }
            witnessCount?.let { put("witnessCount", it) }
            trustLevel?.let { put("trustLevel", it) }
            witnessSignature?.let { put("witnessSignature", it) }
        }
    }

    fun withRelatedPlayer(player: UUID): LedgerMetadata = copy(relatedPlayer = player)
    fun withSourceEntry(entryId: UUID): LedgerMetadata = copy(sourceEntryId = entryId)
    fun withContainer(type: String, location: Location?): LedgerMetadata = copy(
        containerType = type,
        containerLocation = location?.let { "${it.world?.name},${it.blockX},${it.blockY},${it.blockZ}" }
    )

    /**
     * Add witness attestation to this metadata
     */
    fun withWitnesses(
        witnessUuids: List<UUID>,
        trust: String,
        signature: String
    ): LedgerMetadata = copy(
        witnesses = witnessUuids,
        witnessCount = witnessUuids.size,
        trustLevel = trust,
        witnessSignature = signature
    )

    /**
     * Check if this action was witnessed
     */
    fun isWitnessed(): Boolean = (witnessCount ?: 0) > 0

    /**
     * Check if this action has verified trust level
     */
    fun isVerified(): Boolean = trustLevel == "VERIFIED"
}
