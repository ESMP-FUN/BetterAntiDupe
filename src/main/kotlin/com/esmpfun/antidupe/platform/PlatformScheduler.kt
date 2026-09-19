package com.esmpfun.antidupe.platform

import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.function.Consumer

/**
 * Folia rejects BukkitScheduler outright, so its region/entity/async schedulers are used instead.
 * They are reached by reflection so the bytecode holds no hard reference to Paper-only classes,
 * which keeps the plugin loadable on Spigot despite compiling against `paper-api`.
 */
class PlatformScheduler(private val plugin: Plugin) {

    private val server = plugin.server

    val isFolia: Boolean = try {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
        true
    } catch (e: Throwable) { false }

    /** Null on Spigot and on Paper older than 1.20.4. */
    private val paperGlobalScheduler: Any? = try {
        server.javaClass.getMethod("getGlobalRegionScheduler").invoke(server)
    } catch (e: Throwable) { null }

    private val paperAsyncScheduler: Any? = try {
        server.javaClass.getMethod("getAsyncScheduler").invoke(server)
    } catch (e: Throwable) { null }

    /**
     * Bukkit refuses to schedule anything for a disabled plugin, and shutdown blocks the main
     * thread while in-flight ledger writes finish, so a queued task would never run anyway.
     * Running it here keeps that last work from being lost.
     */
    private fun runningNow(task: Runnable): Boolean {
        if (plugin.isEnabled) return false
        task.run()
        return true
    }

    /** Runs on the global region thread when there is one, the main thread otherwise. */
    fun runMain(task: Runnable) {
        if (runningNow(task)) return
        val sched = paperGlobalScheduler
        if (sched != null) {
            try {
                sched.javaClass.getMethod("execute", Plugin::class.java, Runnable::class.java)
                    .invoke(sched, plugin, task)
                return
            } catch (e: Throwable) { /* fall through to BukkitScheduler */ }
        }
        server.scheduler.runTask(plugin, task)
    }

    /** On Folia the task follows [entity] across teleports between regions. */
    fun runForEntity(entity: Entity, task: Runnable) {
        if (runningNow(task)) return
        if (isFolia) {
            try {
                val entityScheduler = entity.javaClass.getMethod("getScheduler").invoke(entity)
                entityScheduler.javaClass.getMethod(
                    "run",
                    Plugin::class.java,
                    Consumer::class.java,
                    Runnable::class.java
                ).invoke(entityScheduler, plugin, Consumer<Any> { task.run() }, null)
                return
            } catch (e: Throwable) { /* fall through */ }
        }
        server.scheduler.runTask(plugin, task)
    }

    /** The task still runs if [entity] is retired before the delay elapses. */
    fun runForEntityLater(entity: Entity, delayTicks: Long, task: Runnable) {
        if (runningNow(task)) return
        if (isFolia) {
            try {
                val entityScheduler = entity.javaClass.getMethod("getScheduler").invoke(entity)
                val scheduled = entityScheduler.javaClass.getMethod(
                    "runDelayed",
                    Plugin::class.java,
                    Consumer::class.java,
                    Runnable::class.java,
                    Long::class.javaPrimitiveType
                ).invoke(entityScheduler, plugin, Consumer<Any> { task.run() }, Runnable { task.run() }, delayTicks)
                if (scheduled == null) task.run()  // entity already retired; run inline
                return
            } catch (e: Throwable) { /* fall through */ }
        }
        server.scheduler.runTaskLater(plugin, task, delayTicks)
    }

    fun runAsync(task: Runnable) {
        if (runningNow(task)) return
        val sched = paperAsyncScheduler
        if (sched != null) {
            try {
                sched.javaClass.getMethod("runNow", Plugin::class.java, Consumer::class.java)
                    .invoke(sched, plugin, Consumer<Any> { task.run() })
                return
            } catch (e: Throwable) { /* fall through */ }
        }
        server.scheduler.runTaskAsynchronously(plugin, task)
    }
}
