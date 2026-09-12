package com.esmpfun.antidupe.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Running tallies of what the plugin has caught since the last metrics upload.
 *
 * These feed the private analytics in [MetricsService]. Every value here is a plain count and
 * nothing else — no player, no UUID, no world or coordinates, no server name. A row says
 * "3 balance discrepancies, 1 of them CRITICAL, 2 of them on NETHERITE_INGOT, 2 rail-duper
 * contraptions blocked", which is what tells the developer whether the plugin is earning its
 * place and which items and exploits to prioritise. It cannot be tied back to a server or a person.
 *
 * The `record*` calls sit on hot event paths, so they are branch-free atomic increments. They run
 * even when metrics are switched off — the numbers simply sit in memory and are never read or sent.
 *
 * [reset] is called from the SDK's flush callback, i.e. only after an upload has actually been
 * accepted, so a failed upload never loses a cycle's counts.
 */
object DetectionCounters {

    private val detections = AtomicLong()
    private val itemsRemoved = AtomicLong()
    private val byType = ConcurrentHashMap<String, AtomicLong>()
    private val bySeverity = ConcurrentHashMap<String, AtomicLong>()
    private val byMaterial = ConcurrentHashMap<String, AtomicLong>()
    private val itemsRemovedByMaterial = ConcurrentHashMap<String, AtomicLong>()
    private val preventionBlocks = ConcurrentHashMap<String, AtomicLong>()

    private fun ConcurrentHashMap<String, AtomicLong>.bump(key: String, by: Long = 1) =
        computeIfAbsent(key) { AtomicLong() }.addAndGet(by)

    /**
     * One reconciliation/TMAR/foreign-item alert fired. [type] and [severity] are the enum names;
     * [material] is the item's `Material` name (e.g. `NETHERITE_INGOT`).
     */
    fun recordDetection(type: String, severity: String, material: String) {
        detections.incrementAndGet()
        byType.bump(type)
        bySeverity.bump(severity)
        byMaterial.bump(material)
    }

    /** Enforcement actually removed [count] surplus items of [material]. */
    fun recordItemsRemoved(material: String, count: Int) {
        if (count <= 0) return
        itemsRemoved.addAndGet(count.toLong())
        itemsRemovedByMaterial.bump(material, count.toLong())
    }

    /**
     * A duper contraption was stopped at the mechanic level. [kind] is a stable short tag:
     * `rail`, `carpet`, `tnt`, `gravity`, `container_desync`.
     */
    fun recordPreventionBlock(kind: String) = preventionBlocks.bump(kind)

    fun detections(): Number = detections.get()
    fun itemsRemoved(): Number = itemsRemoved.get()
    fun byType(): Map<String, Number> = byType.mapValues { it.value.get() }
    fun bySeverity(): Map<String, Number> = bySeverity.mapValues { it.value.get() }
    fun byMaterial(): Map<String, Number> = byMaterial.mapValues { it.value.get() }
    fun itemsRemovedByMaterial(): Map<String, Number> = itemsRemovedByMaterial.mapValues { it.value.get() }
    fun preventionBlocks(): Map<String, Number> = preventionBlocks.mapValues { it.value.get() }

    /** Clear everything back to zero after a successful upload (or on startup). */
    fun reset() {
        detections.set(0)
        itemsRemoved.set(0)
        byType.clear()
        bySeverity.clear()
        byMaterial.clear()
        itemsRemovedByMaterial.clear()
        preventionBlocks.clear()
    }
}
