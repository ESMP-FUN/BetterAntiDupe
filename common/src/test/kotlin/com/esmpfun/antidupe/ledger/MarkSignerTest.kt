package com.esmpfun.antidupe.ledger

import kotlinx.coroutines.runBlocking
import org.bukkit.Material
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkSignerTest {

    private val alice: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bob: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val logger = Logger.getAnonymousLogger()

    @Test
    fun `a signature verifies for its owner only`() {
        val signer = MarkSigner(listOf(MarkSigner.newSecret()))
        val sig = signer.sign(alice)
        assertEquals(MarkSigner.Verdict.VALID, signer.verify(alice, sig))
        assertEquals(MarkSigner.Verdict.FORGED, signer.verify(bob, sig))
    }

    @Test
    fun `the same owner always gets the same signature, so stacks still merge`() {
        val secret = MarkSigner.newSecret()
        val signer = MarkSigner(listOf(secret))
        assertEquals(signer.sign(alice), signer.sign(alice))
        assertEquals(signer.sign(alice), MarkSigner(listOf(secret)).sign(alice))
    }

    @Test
    fun `made up and tampered signatures do not verify`() {
        val signer = MarkSigner(listOf(MarkSigner.newSecret()))
        val sig = signer.sign(alice)
        val tampered = sig.dropLast(1) + if (sig.last() == 'A') 'B' else 'A'
        assertEquals(MarkSigner.Verdict.FORGED, signer.verify(alice, tampered))
        assertEquals(MarkSigner.Verdict.FORGED, signer.verify(alice, "nonsense"))
        assertEquals(MarkSigner.Verdict.FORGED, signer.verify(alice, ":"))
        assertEquals(MarkSigner.Verdict.FORGED, signer.verify(alice, "${signer.currentKeyId}:"))
    }

    @Test
    fun `another server's key is told apart from a fake`() {
        val ours = MarkSigner(listOf(MarkSigner.newSecret()))
        val theirs = MarkSigner(listOf(MarkSigner.newSecret()))
        assertEquals(MarkSigner.Verdict.OTHER_KEY, ours.verify(alice, theirs.sign(alice)))
    }

    @Test
    fun `an older key still verifies but counts as outdated`() {
        val old = MarkSigner.newSecret()
        val oldSig = MarkSigner(listOf(old)).sign(alice)
        val rotated = MarkSigner(listOf(MarkSigner.newSecret(), old))
        assertEquals(MarkSigner.Verdict.VALID, rotated.verify(alice, oldSig))
        assertTrue(rotated.isOutdated(oldSig))
        assertFalse(rotated.isOutdated(rotated.sign(alice)))
    }

    @Test
    fun `the key file keeps every key and the decision about old marks`() {
        val dir = Files.createTempDirectory("marks").toFile()
        val file = MarkKeyFile(File(dir, "mark-signing.key"))
        val keys = listOf(MarkSigner.newSecret(), MarkSigner.newSecret())
        file.save(MarkKeyFile.Contents(keys, MarkKeyFile.LegacyMarks.IGNORE, "2026-10-13T00:00:00Z"))
        val loaded = file.load()!!
        assertEquals(2, loaded.keys.size)
        assertTrue(loaded.keys[0].contentEquals(keys[0]))
        assertTrue(loaded.keys[1].contentEquals(keys[1]))
        assertEquals(MarkKeyFile.LegacyMarks.IGNORE, loaded.legacyMarks)
        assertFalse(File(dir, "mark-signing.key").readText().contains("legacy-marks=ACCEPT"))
    }

    @Test
    fun `a new server does not trust unsigned marks, an existing one does`() = runBlocking {
        val fresh = MarkKeyring.load(Files.createTempDirectory("fresh").toFile(), MemoryLedgerStorage(logger), logger)
        assertEquals(MarkKeyFile.LegacyMarks.IGNORE, fresh.legacyMarks)
        assertTrue(fresh.created)

        val storage = MemoryLedgerStorage(logger)
        storage.appendBuilt(alice, LedgerAction.PICKUP, Material.DIAMOND_BLOCK, 1, LedgerMetadata())
        val upgraded = MarkKeyring.load(Files.createTempDirectory("upgraded").toFile(), storage, logger)
        assertEquals(MarkKeyFile.LegacyMarks.ACCEPT, upgraded.legacyMarks)
    }

    @Test
    fun `a restart keeps the same key`() = runBlocking {
        val dir = Files.createTempDirectory("restart").toFile()
        val first = MarkKeyring.load(dir, MemoryLedgerStorage(logger), logger)
        val second = MarkKeyring.load(dir, MemoryLedgerStorage(logger), logger)
        assertEquals(first.signer.currentKeyId, second.signer.currentKeyId)
        assertFalse(second.created)
        assertEquals(MarkSigner.Verdict.VALID, second.signer.verify(alice, first.signer.sign(alice)))
    }

    @Test
    fun `an unreadable key file is kept aside, not overwritten`() = runBlocking {
        val dir = Files.createTempDirectory("broken").toFile()
        File(dir, "mark-signing.key").writeText("key=not base64 at all!\n")
        MarkKeyring.load(dir, MemoryLedgerStorage(logger), logger)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("mark-signing.key.unreadable-") })
        assertTrue(MarkKeyFile(File(dir, "mark-signing.key")).load() != null)
    }

    @Test
    fun `servers on one shared database end up with the same key`() = runBlocking {
        val shared = java.util.concurrent.ConcurrentHashMap<String, String>()
        val a = MarkKeyring.load(Files.createTempDirectory("a").toFile(), MemoryLedgerStorage(logger, shared), logger)
        val bDir = Files.createTempDirectory("b").toFile()
        val b = MarkKeyring.load(bDir, MemoryLedgerStorage(logger, shared), logger)
        assertEquals(a.signer.currentKeyId, b.signer.currentKeyId)
        assertTrue(b.adopted)
        assertEquals(MarkSigner.Verdict.VALID, b.signer.verify(alice, a.signer.sign(alice)))
        // B's own first key stays in its file as a previous key.
        assertEquals(2, MarkKeyFile(File(bDir, "mark-signing.key")).load()!!.keys.size)
    }
}
