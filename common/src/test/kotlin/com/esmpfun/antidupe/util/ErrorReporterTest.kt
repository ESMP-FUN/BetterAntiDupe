package com.esmpfun.antidupe.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.logging.Logger

class ErrorReporterTest {

    private val dataFolder = File("/srv/mc/plugins/BetterAntiDupe")

    @BeforeEach
    fun setUp() = ErrorReporter.init(Logger.getLogger("test"), dataFolder) { null }

    @AfterEach
    fun tearDown() = ErrorReporter.shutdown()

    @Test
    fun `the plugin folder is replaced`() {
        val e = Exception("unable to open database file /srv/mc/plugins/BetterAntiDupe/ledger.db")
        assertTrue(withoutPaths(e).message!!.contains("plugins/BetterAntiDupe/ledger.db"))
        assertTrue(!withoutPaths(e).message!!.contains("/srv/mc/"))
    }

    @Test
    fun `the server folder is replaced`() {
        val e = Exception("cannot write /srv/mc/world/level.dat")
        assertEquals("java.lang.Exception: cannot write ./world/level.dat", withoutPaths(e).message)
    }

    @Test
    fun `a message with no path is passed through untouched`() {
        val e = Exception("connection reset")
        assertSame(e, withoutPaths(e))
    }

    @Test
    fun `causes are shortened too and the stack trace is kept`() {
        val cause = Exception("/srv/mc/plugins/BetterAntiDupe/native is full")
        val e = Exception("could not start", cause)
        val out = withoutPaths(e)
        assertTrue(out.cause!!.message!!.startsWith("java.lang.Exception: plugins/BetterAntiDupe/native"))
        assertTrue(out.stackTrace.contentEquals(e.stackTrace))
    }

    @Test
    fun `a self referencing cause does not loop`() {
        val e = object : Exception("/srv/mc/boom") {
            override val cause: Throwable get() = this
        }
        assertTrue(withoutPaths(e).message!!.contains("./boom"))
    }

    private fun withoutPaths(t: Throwable) = ErrorReporter.withoutPaths(t, 10)
}
