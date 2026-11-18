package com.esmpfun.antidupe.adapter

import org.bukkit.NamespacedKey
import org.bukkit.Registry
import java.util.logging.Logger

/**
 * Picks the adapter for the running server, newest first. Adapters are named only here, as
 * strings, so the JVM never loads one compiled against an API the server doesn't have.
 */
object ServerAdapters {

    private class Candidate(val className: String, val fits: () -> Boolean)

    private val candidates = listOf(
        // TODO(26.4): once Paper 26.4 is out, check for a class from its API instead.
        Candidate("com.esmpfun.antidupe.adapter.v26_4.Adapter26_4") { hasPaperSulfurCubes() && hasEntity("frostbite") },
        Candidate("com.esmpfun.antidupe.adapter.v26_2.Adapter26_2") { hasPaperSulfurCubes() },
        Candidate("com.esmpfun.antidupe.adapter.v1_21.Adapter1_21") { true },
    )

    fun pick(log: Logger): ServerAdapter {
        for (candidate in candidates) {
            if (!candidate.fits()) continue
            try {
                val adapter = Class.forName(candidate.className).getDeclaredConstructor().newInstance() as ServerAdapter
                log.info("Using the support for Minecraft ${adapter.versions}.")
                return adapter
            } catch (e: ReflectiveOperationException) {
                log.warning("The support for this Minecraft version couldn't start (${e.cause ?: e}). Trying an older one.")
            } catch (e: LinkageError) {
                log.warning("The support for this Minecraft version couldn't start ($e). Trying an older one.")
            }
        }
        error("No adapter fits this server")
    }

    // Paper 26.2 builds before 129 and Spigot lack one or both, so they stay on the 1.21 adapter.
    private fun hasPaperSulfurCubes(): Boolean =
        hasClass("io.papermc.paper.event.entity.SulfurCubeSwallowItemEvent") &&
            hasClass("io.papermc.paper.datacomponent.item.SulfurCubeContent")

    private fun hasClass(name: String): Boolean =
        runCatching { Class.forName(name, false, ServerAdapters::class.java.classLoader) }.isSuccess

    private fun hasEntity(name: String): Boolean =
        runCatching { Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(name)) != null }.getOrDefault(false)
}
