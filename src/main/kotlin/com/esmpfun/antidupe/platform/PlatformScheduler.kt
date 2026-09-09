package com.esmpfun.antidupe.platform

import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.function.Consumer

/**
 * Scheduler abstraction that works across:
 *   - **Folia**: native region/entity/async schedulers (BukkitScheduler is rejected)
 *   - **Paper non-Folia**: Paper's global region / async schedulers (delegate to BukkitScheduler)
 *   - **Spigot**: classic BukkitScheduler
 *
 * Detection is done via reflection so the compiled bytecode has no hard reference to
 * Paper-only classes. That keeps the plugin loadable on Spigot even though we compile
 * against `paper-api`.
 */
class PlatformScheduler(private val plugin: Plugin) {

    private val server = plugin.server

    /** True if running on Folia (entity scheduler is needed to follow teleports). */
    val isFolia: Boolean = try {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
        true
    } catch (e: Throwable) { false }

    /** The Paper-style GlobalRegionScheduler if available (Paper ≥1.20.4 and Folia), else null. */
    private val paperGlobalScheduler: Any? = try {
        server.javaClass.getMethod("getGlobalRegionScheduler").invoke(server)
    } catch (e: Throwable) { null }

    private val paperAsyncScheduler: Any? = try {
        server.javaClass.getMethod("getAsyncScheduler").invoke(server)
    } catch (e: Throwable) { null }

    /** Run a task on the main thread (or the global region thread on Folia). */
    fun runMain(task: Runnable) {
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

    /** Run a task on the region thread that owns [entity] (follows teleports on Folia). */
    fun runForEntity(entity: Entity, task: Runnable) {
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
        // Paper or Spigot non-Folia: the regular scheduler is single-threaded enough.
        server.scheduler.runTask(plugin, task)
    }

    /**
     * Run a task on the region thread that owns [entity], after [delayTicks]. On Folia the
     * entity scheduler is used; if the entity is retired before the delay elapses, the task
     * still runs (so callers can observe "entity is gone" as a state).
     */
    fun runForEntityLater(entity: Entity, delayTicks: Long, task: Runnable) {
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

    /** Run a task on a background pool. */
    fun runAsync(task: Runnable) {
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
