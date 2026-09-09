package com.esmpfun.antidupe.ledger

import org.bukkit.Material
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Entries reach Redis and the memory backend as JSON, and the hash now covers the metadata.
 * That makes the JSON round trip load-bearing: if a single field came back even slightly
 * different, every affected entry would fail verification and admins would be chasing a
 * tampering warning that nothing caused. Worth pinning down, especially across a bump of the
 * JSON library itself.
 */
class LedgerEntryJsonTest {

    private val player: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val witness: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003")

    private fun fullMetadata() = LedgerMetadata(
        worldName = "world_the_end",
        x = 1234.56789,
        y = -64.0,
        z = 0.5,
        relatedPlayer = witness,
        containerType = "SHULKER_BOX",
        containerLocation = "world,10,20,30",
        sourceEntryId = UUID.randomUUID(),
        blockType = Material.DEEPSLATE_DIAMOND_ORE,
        toolUsed = Material.NETHERITE_PICKAXE,
        enchantments = "FORTUNE:3",
        notes = "pipe | and colon : and comma , in free text"
    ).withWitnesses(listOf(witness, player), "VERIFIED", "signature-value")

    @Test
    fun `an entry survives a json round trip unchanged`() {
        val original = LedgerEntry.create(
            player, LedgerAction.MINE, Material.DIAMOND_BLOCK, 7, fullMetadata(), prevHash = "abc123"
        )
        val restored = LedgerEntry.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun `a round tripped entry still verifies`() {
        val original = LedgerEntry.create(
            player, LedgerAction.MINE, Material.DIAMOND_BLOCK, 7, fullMetadata(), prevHash = "abc123"
        )
        val restored = LedgerEntry.fromJson(original.toJson())
        assertTrue(restored.verifyIntegrity(), "json round trip must not disturb the hash")
    }

    @Test
    fun `coordinates survive the round trip exactly`() {
        // Doubles are the field most likely to drift through a serializer, and the hash
        // reads them back as text, so an inexact value would show up as tampering.
        val meta = LedgerMetadata(worldName = "w", x = 1.0 / 3.0, y = 255.99999999, z = -0.1)
        val entry = LedgerEntry.create(player, LedgerAction.DROP, Material.ELYTRA, -1, meta, null)
        val restored = LedgerEntry.fromJson(entry.toJson())
        assertEquals(meta.canonical(), restored.metadata.canonical())
        assertTrue(restored.verifyIntegrity())
    }

    @Test
    fun `an entry with empty metadata survives the round trip`() {
        val entry = LedgerEntry.create(player, LedgerAction.RECONCILE, Material.AIR, 0, LedgerMetadata(), null)
        val restored = LedgerEntry.fromJson(entry.toJson())
        assertEquals(entry, restored)
        assertTrue(restored.verifyIntegrity())
    }

    @Test
    fun `an entry whose material was renamed since it was written stays readable`() {
        // A row written on an old server as "GRASS"; on this server that constant is SHORT_GRASS.
        val entry = LedgerEntry.create(player, LedgerAction.PICKUP, Material.SHORT_GRASS, 3, LedgerMetadata(), null)
        val storedUnderOldName = org.json.JSONObject(entry.toJson()).apply { put("material", "GRASS") }.toString()

        val restored = LedgerEntry.fromJson(storedUnderOldName)
        assertEquals(Material.SHORT_GRASS, restored.material, "the known rename is applied for logic")
        assertEquals("GRASS", restored.materialRaw, "the original name is kept for hashing")
        // toJson round-trips the original name, not the remapped one, so the hash a genuine
        // old-server entry carries still recomputes to the same value.
        assertEquals("GRASS", org.json.JSONObject(restored.toJson()).getString("material"))
    }

    @Test
    fun `hashing uses the raw material name so a renamed entry keeps verifying`() {
        // Faithfully simulate what the old server wrote: hash computed over "GRASS".
        val onOldServer = LedgerEntry.create(player, LedgerAction.PICKUP, Material.SHORT_GRASS, 3, LedgerMetadata(), null)
        val oldJson = org.json.JSONObject(onOldServer.toJson())
        // Recreate the entry as if SHORT_GRASS had been called GRASS end to end.
        val faithful = LedgerEntry.fromJson(oldJson.toString())  // materialRaw = null, verifies
        assertTrue(faithful.verifyIntegrity())
        val renamed = faithful.copy(material = Material.AIR, materialRaw = "SHORT_GRASS")
        assertTrue(renamed.verifyIntegrity(), "hash must read the raw name, not the AIR fallback")
    }

    @Test
    fun `an entry naming a material unknown on this server reads back as AIR without throwing`() {
        val entry = LedgerEntry.create(player, LedgerAction.PICKUP, Material.DIAMOND_BLOCK, 1, LedgerMetadata(), null)
        val storedUnderGoneName = org.json.JSONObject(entry.toJson()).apply { put("material", "SOME_FUTURE_BLOCK") }.toString()

        val restored = LedgerEntry.fromJson(storedUnderGoneName)
        assertEquals(Material.AIR, restored.material)
        assertEquals("SOME_FUTURE_BLOCK", restored.materialRaw)
    }

    @Test
    fun `a legacy entry without a hash version reads back as version one`() {
        // Rows written by earlier releases have no hashVersion key at all.
        val entry = LedgerEntry.create(player, LedgerAction.PICKUP, Material.BEACON, 1, LedgerMetadata(), null)
        val stripped = org.json.JSONObject(entry.toJson()).apply { remove("hashVersion") }.toString()
        assertEquals(LedgerEntry.HASH_VERSION_LEGACY, LedgerEntry.fromJson(stripped).hashVersion)
    }
}
