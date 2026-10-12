package com.esmpfun.antidupe.ledger

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs owner marks with a secret only this server (or network) holds, so a mark written
 * anywhere else, by a creative-mode client, an item editor or a command, does not verify.
 *
 * The signature depends on the owner alone. Anything per-item would stop marked stacks merging.
 * The first key signs; the rest only verify, so marks made before a key change keep working
 * and are re-signed the next time they are written.
 */
class MarkSigner(secrets: List<ByteArray>) {

    enum class Verdict { VALID, FORGED, OTHER_KEY }

    private class Key(val id: String, val secret: ByteArray) {
        val cache = ConcurrentHashMap<UUID, String>()
    }

    private val ring: List<Key>

    init {
        require(secrets.isNotEmpty()) { "no signing key" }
        ring = secrets.map { Key(idOf(it), it) }
    }

    val currentKeyId: String get() = ring.first().id

    val previousKeyCount: Int get() = ring.size - 1

    fun sign(owner: UUID): String = ring.first().let { "${it.id}:${mac(it, owner)}" }

    fun verify(owner: UUID, signature: String): Verdict {
        val split = signature.indexOf(':')
        if (split <= 0) return Verdict.FORGED
        val key = ring.firstOrNull { it.id == signature.substring(0, split) } ?: return Verdict.OTHER_KEY
        val expected = mac(key, owner).toByteArray(Charsets.US_ASCII)
        val given = signature.substring(split + 1).toByteArray(Charsets.US_ASCII)
        return if (MessageDigest.isEqual(expected, given)) Verdict.VALID else Verdict.FORGED
    }

    /** True for a valid signature made with an older key, which should be rewritten. */
    fun isOutdated(signature: String): Boolean = !signature.startsWith(currentKeyId + ":")

    private fun mac(key: Key, owner: UUID): String {
        key.cache[owner]?.let { return it }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key.secret, ALGORITHM))
        val raw = mac.doFinal((DOMAIN + owner.toString()).toByteArray(Charsets.UTF_8))
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.copyOf(MAC_BYTES))
        if (key.cache.size >= MAX_CACHED) key.cache.clear()
        key.cache[owner] = encoded
        return encoded
    }

    companion object {
        private const val ALGORITHM = "HmacSHA256"
        // Bumping this invalidates every mark, so it only changes with the mark format.
        private const val DOMAIN = "adp-owner-mark-v1|"
        // 96 bits: a guessed signature is right once in 2^96 tries.
        private const val MAC_BYTES = 12
        private const val MAX_CACHED = 50_000

        /** Four hex characters naming a key without revealing anything about it. */
        fun idOf(secret: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(secret).copyOf(2).joinToString("") { "%02x".format(it) }

        fun newSecret(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }
}

/**
 * The key file, `plugins/BetterAntiDupe/mark-signing.key`. Plain `name=value` lines so an admin
 * can copy it between servers; the secrets never go into config.yml, which gets pasted around.
 */
class MarkKeyFile(private val file: File) {

    /** What to do with unsigned marks, decided once when the file is created. */
    enum class LegacyMarks { ACCEPT, IGNORE }

    class Contents(val keys: List<ByteArray>, val legacyMarks: LegacyMarks, val created: String)

    fun exists(): Boolean = file.isFile

    fun load(): Contents? {
        if (!file.isFile) return null
        val keys = ArrayList<ByteArray>()
        var legacy = LegacyMarks.ACCEPT
        var created = ""
        for (raw in file.readLines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val value = line.substring(eq + 1).trim()
            when (line.substring(0, eq).trim()) {
                "key", "previous" -> runCatching { Base64.getDecoder().decode(value) }
                    .getOrNull()?.takeIf { it.size >= 16 }?.let { keys.add(it) }
                "legacy-marks" -> legacy = if (value.equals("IGNORE", true)) LegacyMarks.IGNORE else LegacyMarks.ACCEPT
                "created" -> created = value
            }
        }
        return if (keys.isEmpty()) null else Contents(keys, legacy, created)
    }

    fun save(contents: Contents) {
        file.parentFile?.mkdirs()
        val encoder = Base64.getEncoder()
        val text = buildString {
            appendLine("# The secret that signs owner marks. Keep it private: anyone who has it can fake marks.")
            appendLine("# If this server shares items with others (for example through an inventory sync plugin),")
            appendLine("# copy this file to each of them. Servers sharing a Redis database share it automatically.")
            appendLine("# Never delete it: marks signed with a lost key stop counting.")
            appendLine("created=${contents.created.ifEmpty { Instant.now().toString() }}")
            appendLine("legacy-marks=${contents.legacyMarks}")
            contents.keys.forEachIndexed { i, key ->
                appendLine("${if (i == 0) "key" else "previous"}=${encoder.encodeToString(key)}")
            }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        restrict(tmp)
        Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** Owner read and write only, where the file system supports it. */
    private fun restrict(f: File) {
        try {
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (e: Exception) {
            // Windows and some network storage have no POSIX permissions.
        }
    }
}

/** Loads or creates the key file and, on a shared backend, adopts the key every server uses. */
object MarkKeyring {

    class Loaded(val signer: MarkSigner, val legacyMarks: MarkKeyFile.LegacyMarks, val created: Boolean, val adopted: Boolean)

    private const val FILE_NAME = "mark-signing.key"
    private const val SHARED_NAME = "mark-signing-key"

    suspend fun load(dataFolder: File, storage: LedgerStorage, logger: java.util.logging.Logger): Loaded {
        val keyFile = MarkKeyFile(File(dataFolder, FILE_NAME))
        var contents = keyFile.load()
        var created = false
        if (contents == null) {
            if (keyFile.exists()) {
                // Unreadable rather than missing: keep it for the admin instead of overwriting it.
                val kept = File(dataFolder, "$FILE_NAME.unreadable-${System.currentTimeMillis()}")
                File(dataFolder, FILE_NAME).renameTo(kept)
                logger.warning("[Marks] $FILE_NAME could not be read, so a new key was made and the old file was kept" +
                    " as ${kept.name}. Items signed with the old key count as unmarked until you put it back.")
            }
            // A server that already recorded items has unsigned marks out in the world worth trusting;
            // a new one cannot, so any unsigned mark there was made by hand.
            val hadHistory = storage.getTrackedPlayers().isNotEmpty()
            contents = MarkKeyFile.Contents(
                listOf(MarkSigner.newSecret()),
                if (hadHistory) MarkKeyFile.LegacyMarks.ACCEPT else MarkKeyFile.LegacyMarks.IGNORE,
                ""
            )
            keyFile.save(contents)
            contents = keyFile.load() ?: contents
            created = true
        }

        val encoder = Base64.getEncoder()
        val local = encoder.encodeToString(contents.keys.first())
        val shared = storage.sharedSecret(SHARED_NAME, local)
        var adopted = false
        if (shared != local) {
            val sharedKey = runCatching { Base64.getDecoder().decode(shared) }.getOrNull()?.takeIf { it.size >= 16 }
            if (sharedKey == null) {
                logger.warning("[Marks] The shared signing key in the database is not usable, so this server keeps its own.")
            } else {
                contents = MarkKeyFile.Contents(
                    listOf(sharedKey) + contents.keys.filterNot { it.contentEquals(sharedKey) },
                    contents.legacyMarks, contents.created
                )
                keyFile.save(contents)
                adopted = true
            }
        }
        return Loaded(MarkSigner(contents.keys), contents.legacyMarks, created, adopted)
    }
}
