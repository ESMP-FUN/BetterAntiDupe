package com.esmpfun.antidupe.ledger

import org.bukkit.Material
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The ledger's tamper-evidence rests entirely on these rules, and the cost of getting them
 * wrong is silent: verification keeps reporting "clean" while the thing it is meant to catch
 * goes through. None of this needs a running server, so it is checked here.
 */
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
        // What an attacker with database write access would do: rewrite the audit trail but
        // leave the transaction columns, which are all version 1 ever hashed, untouched.
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
        // A version 1 row, as written by an earlier release: its hash never covered metadata,
        // so it must keep verifying even though the payload has changed since.
        val v2 = entry()
        val legacyHash = legacyHashOf(v2)
        val legacyRow = v2.copy(hash = legacyHash, hashVersion = LedgerEntry.HASH_VERSION_LEGACY)
        assertTrue(legacyRow.verifyIntegrity(), "upgrading must not invalidate existing rows")
        assertNotEquals(legacyHash, v2.hash, "version 2 must hash to something different")
    }

    @Test
    fun `a reset marker in the notes is ignored on current entries`() {
        // The old marker lived in free text that version 1 did not hash, so one edit to one
        // row retired the whole chain from verification. Only the action counts now.
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
        // Without length prefixes, text moved across a field boundary would hash identically.
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
