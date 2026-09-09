package com.esmpfun.antidupe.ledger

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
    val hash: String,               // SHA-256 of this entry's contents
    /**
     * Which payload the [hash] was built from. 1 is the original, which covered only the
     * transaction fields; 2 adds [metadata], so the witness list, trust level, container
     * location and notes are protected too. Entries written before version 2 are still on
     * disk and must keep verifying under the rules they were created with, so this is read
     * back from storage rather than assumed.
     */
    val hashVersion: Int = HASH_VERSION_CURRENT,
    /**
     * The material name exactly as it was stored, kept only when it no longer resolves to a
     * [Material] constant on this server (a Mojang enum rename across a version upgrade, e.g.
     * GRASS -> SHORT_GRASS). [material] then carries the best-effort remap (or AIR) for logic,
     * while the hash is still computed from this original string so the entry keeps verifying.
     * Null on every normally-written entry.
     */
    val materialRaw: String? = null
) {
    companion object {
        const val HASH_VERSION_LEGACY = 1
        const val HASH_VERSION_CURRENT = 2

        /**
         * Mojang has renamed material enum constants across versions before (GRASS -> SHORT_GRASS
         * in 1.20.3, SCUTE -> TURTLE_SCUTE in 1.20.5) and 26.3 is a checkpoint release with a
         * large enum churn. A ledger row written under the old name must still be readable.
         */
        private val RENAMED_MATERIALS = mapOf(
            "GRASS" to "SHORT_GRASS",
            "SCUTE" to "TURTLE_SCUTE",
        )

        /**
         * Resolve a stored material name to a [Material], trying the known-rename table before
         * giving up. Returns null only when the name is genuinely unknown on this server.
         */
        fun materialOrNull(name: String): Material? =
            runCatching { Material.valueOf(name) }.getOrNull()
                ?: RENAMED_MATERIALS[name]?.let { runCatching { Material.valueOf(it) }.getOrNull() }

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

            val hash = computeHash(id, timestamp, player, action, material.name, quantity, prevHash,
                metadata, HASH_VERSION_CURRENT)

            return LedgerEntry(
                id = id,
                timestamp = timestamp,
                player = player,
                action = action,
                material = material,
                quantity = quantity,
                metadata = metadata,
                prevHash = prevHash,
                hash = hash,
                hashVersion = HASH_VERSION_CURRENT
            )
        }

        private fun computeHash(
            id: UUID,
            timestamp: Long,
            player: UUID,
            action: LedgerAction,
            materialName: String,
            quantity: Int,
            prevHash: String?,
            metadata: LedgerMetadata,
            version: Int
        ): String {
            val base = "$id|$timestamp|$player|${action.name}|$materialName|$quantity|${prevHash ?: "GENESIS"}"
            // Version 1 hashed the transaction only, leaving the whole audit payload editable.
            val payload = if (version >= HASH_VERSION_CURRENT) "$base|${metadata.canonical()}" else base
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        fun fromJson(json: String): LedgerEntry {
            val obj = JSONObject(json)
            return LedgerEntry(
                id = UUID.fromString(obj.getString("id")),
                timestamp = obj.getLong("timestamp"),
                player = UUID.fromString(obj.getString("player")),
                action = LedgerAction.valueOf(obj.getString("action")),
                material = obj.getString("material").let { materialOrNull(it) ?: Material.AIR },
                materialRaw = obj.getString("material").let { if (materialOrNull(it)?.name == it) null else it },
                quantity = obj.getInt("quantity"),
                metadata = LedgerMetadata.fromJson(obj.getJSONObject("metadata")),
                prevHash = obj.optStringOrNull("prevHash"),
                hash = obj.getString("hash"),
                // Absent means the entry predates hash version 2.
                hashVersion = if (obj.has("hashVersion")) obj.getInt("hashVersion") else HASH_VERSION_LEGACY
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
            put("material", materialRaw ?: material.name)
            put("quantity", quantity)
            put("metadata", metadata.toJsonObject())
            put("prevHash", prevHash)
            put("hash", hash)
            put("hashVersion", hashVersion)
        }.toString()
    }

    /**
     * Verify this entry's hash matches its contents
     */
    fun verifyIntegrity(): Boolean {
        val expectedHash = computeHash(id, timestamp, player, action, materialRaw ?: material.name,
            quantity, prevHash, metadata, hashVersion)
        return hash == expectedHash
    }

    /**
     * True when this entry marks the start of a fresh verification range for its player.
     *
     * Version 2 entries have to say so with [LedgerAction.CHAIN_RESET], which the hash covers.
     * The old marker lived in the free-text notes field, which version 1 did not hash, so
     * editing one field on one row retired the entire chain from verification. That form is
     * still honoured on version 1 entries, because genuine ones written by earlier releases
     * are sitting in live databases, and deliberately ignored on anything newer.
     */
    fun isChainReset(): Boolean = when {
        action == LedgerAction.CHAIN_RESET -> true
        hashVersion <= HASH_VERSION_LEGACY -> metadata.notes?.startsWith("CHAIN_RESET:") == true
        else -> false
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
    RECONCILE,          // Balance was audited
    CHAIN_RESET         // Verification starts fresh from here (schema migration marker)
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
                blockType = s("blockType")?.let { LedgerEntry.materialOrNull(it) },
                toolUsed = s("toolUsed")?.let { LedgerEntry.materialOrNull(it) },
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

    /**
     * A stable text form of every field, used as hash input.
     *
     * Written out by hand in a fixed field order rather than reusing [toJsonObject]: JSON key
     * order is not guaranteed, and a hash whose input can be reordered is not a hash of
     * anything. Each field is written with its length in front, so free text containing a
     * separator cannot be arranged to look like a different set of fields.
     */
    fun canonical(): String = listOf(
        worldName.orEmpty(),
        x?.toString().orEmpty(),
        y?.toString().orEmpty(),
        z?.toString().orEmpty(),
        relatedPlayer?.toString().orEmpty(),
        containerType.orEmpty(),
        containerLocation.orEmpty(),
        sourceEntryId?.toString().orEmpty(),
        blockType?.name.orEmpty(),
        toolUsed?.name.orEmpty(),
        enchantments.orEmpty(),
        notes.orEmpty(),
        witnesses?.joinToString(",").orEmpty(),
        witnessCount?.toString().orEmpty(),
        trustLevel.orEmpty(),
        witnessSignature.orEmpty()
    ).joinToString("|") { "${it.length}:$it" }

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
