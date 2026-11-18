package com.esmpfun.antidupe.ledger

import org.bukkit.Location
import org.bukkit.Material
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * One immutable item movement. Entries form a hash chain: each one hashes the hash of the
 * entry before it, so an edited or removed row breaks verification from that point on.
 */
data class LedgerEntry(
    val id: UUID,
    val timestamp: Long,
    val player: UUID,
    val action: LedgerAction,
    val material: Material,
    val quantity: Int,              // Positive = gain, negative = loss, zero = tracking only
    val metadata: LedgerMetadata,
    val prevHash: String?,          // Null on the first entry of a chain
    val hash: String,
    /**
     * Which payload the [hash] was built from. 1 covered only the transaction fields; 2 adds
     * [metadata], so the witness list, trust level, container location and notes are protected
     * too. Version 1 entries are still on disk and must keep verifying under the rules they
     * were created with, so this is read back from storage rather than assumed.
     */
    val hashVersion: Int = HASH_VERSION_CURRENT,
    /**
     * The material name exactly as stored, kept only when it no longer resolves to a [Material]
     * on this server (a Mojang enum rename). [material] then holds the best-effort remap, or AIR,
     * while the hash stays computed from this original string so the entry keeps verifying.
     */
    val materialRaw: String? = null
) {
    companion object {
        const val HASH_VERSION_LEGACY = 1
        const val HASH_VERSION_CURRENT = 2

        /** Mojang renames enum constants across versions; rows written under the old name must still read. */
        private val RENAMED_MATERIALS = mapOf(
            "GRASS" to "SHORT_GRASS",
            "SCUTE" to "TURTLE_SCUTE",
        )

        fun materialOrNull(name: String): Material? =
            runCatching { Material.valueOf(name) }.getOrNull()
                ?: RENAMED_MATERIALS[name]?.let { runCatching { Material.valueOf(it) }.getOrNull() }

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

    fun verifyIntegrity(): Boolean {
        val expectedHash = computeHash(id, timestamp, player, action, materialRaw ?: material.name,
            quantity, prevHash, metadata, hashVersion)
        return hash == expectedHash
    }

    /**
     * A version 2 entry must declare the reset with [LedgerAction.CHAIN_RESET], which the hash
     * covers. The old marker lived in the free-text notes field, which version 1 did not hash,
     * so editing one field on one row retired the whole chain from verification. That form is
     * honoured on version 1 entries only, since genuine ones sit in live databases.
     */
    fun isChainReset(): Boolean = when {
        action == LedgerAction.CHAIN_RESET -> true
        hashVersion <= HASH_VERSION_LEGACY -> metadata.notes?.startsWith("CHAIN_RESET:") == true
        else -> false
    }
}

enum class LedgerAction {
    MINE,
    CRAFT,
    PICKUP,
    RECEIVE,
    CONTAINER_TAKE,
    ENTITY_TAKE,
    FRAME_TAKE,
    ADMIN_GIVE,
    STATION_OUTPUT,
    SMELT,
    LOOT,
    VILLAGER_TRADE,

    PLACE,
    DROP,
    GIVE,
    CONTAINER_PUT,
    ENTITY_PUT,
    FRAME_PUT,
    CONSUME,
    DESTROY,
    DESPAWN,
    VILLAGER_BUY,

    TRANSFER,
    SPLIT,
    MERGE,
    OWNERSHIP_CHANGE,
    RECONCILE,
    CHAIN_RESET,
    LEFT_CREATIVE
}

data class LedgerMetadata(
    val worldName: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val relatedPlayer: UUID? = null,
    val containerType: String? = null,
    val containerLocation: String? = null,
    val sourceEntryId: UUID? = null,         // On a RECEIVE, the GIVE entry that produced it
    val blockType: Material? = null,
    val toolUsed: Material? = null,
    val enchantments: String? = null,
    val notes: String? = null,

    val witnesses: List<UUID>? = null,
    val witnessCount: Int? = null,
    val trustLevel: String? = null,          // Name of a TrustLevel
    val witnessSignature: String? = null
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
            witnesses?.let { list -> put("witnesses", list.map { it.toString() }) }
            witnessCount?.let { put("witnessCount", it) }
            trustLevel?.let { put("trustLevel", it) }
            witnessSignature?.let { put("witnessSignature", it) }
        }
    }

    /**
     * Hash input. Written out by hand in a fixed field order rather than reusing [toJsonObject],
     * because JSON key order is not guaranteed and a hash whose input can be reordered is not a
     * hash of anything. Each field carries its length in front, so free text containing a
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

    fun isWitnessed(): Boolean = (witnessCount ?: 0) > 0

    fun isVerified(): Boolean = trustLevel == "VERIFIED"
}
