package com.esmpfun.antidupe.ledger

import org.bukkit.Material
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LedgerEntryHashTest {

    private val player: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun entry(
        metadata: LedgerMetadata = LedgerMetadata(notes = "plain"),
        action: LedgerAction = LedgerAction.PICKUP
    ) = LedgerEntry.create(player, action, Material.DIAMOND_BLOCK, 5, metadata, prevHash = null)

    @Test
    fun `new entries verify against themselves`() {
        assertTrue(entry().verifyIntegrity())
    }

    @Test
    fun `new entries are written at the current hash version`() {
        assertEquals(LedgerEntry.HASH_VERSION_CURRENT, entry().hashVersion)
    }

    @Test
    fun `editing the audit metadata breaks the hash`() {
        val original = entry(LedgerMetadata(notes = "mined at spawn"))
        val tampered = original.copy(metadata = original.metadata.copy(notes = "nothing to see"))
        assertFalse(tampered.verifyIntegrity())
    }

    @Test
    fun `editing the witness list breaks the hash`() {
        val original = entry(LedgerMetadata().withWitnesses(listOf(player), "VERIFIED", "sig"))
        val tampered = original.copy(metadata = original.metadata.copy(witnesses = emptyList()))
        assertFalse(tampered.verifyIntegrity())
    }

    @Test
    fun `legacy entries still verify under the old rules`() {
        val v2 = entry()
        val legacyHash = legacyHashOf(v2)
        val legacyRow = v2.copy(hash = legacyHash, hashVersion = LedgerEntry.HASH_VERSION_LEGACY)
        assertTrue(legacyRow.verifyIntegrity(), "upgrading must not invalidate existing rows")
        assertNotEquals(legacyHash, v2.hash, "version 2 must hash to something different")
    }

    @Test
    fun `a reset marker in the notes is ignored on current entries`() {
        val forged = entry(LedgerMetadata(notes = "CHAIN_RESET:let me through"))
        assertFalse(forged.isChainReset())
    }

    @Test
    fun `a reset marker in the notes is still honoured on legacy entries`() {
        val legacy = entry(LedgerMetadata(notes = "CHAIN_RESET:legacy-global-chain"))
            .copy(hashVersion = LedgerEntry.HASH_VERSION_LEGACY)
        assertTrue(legacy.isChainReset(), "real markers written by older releases must keep working")
    }

    @Test
    fun `the reset action counts at any version`() {
        assertTrue(entry(action = LedgerAction.CHAIN_RESET).isChainReset())
    }

    @Test
    fun `canonical form separates fields that would otherwise run together`() {
        val a = LedgerMetadata(containerType = "CHEST", notes = "abc")
        val b = LedgerMetadata(containerType = "CHESTabc", notes = "")
        assertNotEquals(a.canonical(), b.canonical())
    }

    @Test
    fun `canonical form is stable across repeated calls`() {
        val meta = LedgerMetadata(worldName = "world", x = 1.5, y = 64.0, z = -3.25, notes = "n")
        assertEquals(meta.canonical(), meta.canonical())
    }

    private fun legacyHashOf(e: LedgerEntry): String {
        val payload = "${e.id}|${e.timestamp}|${e.player}|${e.action.name}|" +
            "${e.material.name}|${e.quantity}|${e.prevHash ?: "GENESIS"}"
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
