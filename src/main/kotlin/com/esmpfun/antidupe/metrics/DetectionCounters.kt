package com.esmpfun.antidupe.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tallies uploaded by [MetricsService]. Every value is a plain count and nothing else: no player,
 * no UUID, no world or coordinates, no server name.
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

    /** All three must be enum names, never free text: they are uploaded verbatim. */
    fun recordDetection(type: String, severity: String, material: String) {
        detections.incrementAndGet()
        byType.bump(type)
        bySeverity.bump(severity)
        byMaterial.bump(material)
    }

    fun recordItemsRemoved(material: String, count: Int) {
        if (count <= 0) return
        itemsRemoved.addAndGet(count.toLong())
        itemsRemovedByMaterial.bump(material, count.toLong())
    }

    /** [kind] is one of the stable tags `rail`, `carpet`, `tnt`, `gravity`, `container_desync`. */
    fun recordPreventionBlock(kind: String) = preventionBlocks.bump(kind)

    fun detections(): Number = detections.get()
    fun itemsRemoved(): Number = itemsRemoved.get()
    fun byType(): Map<String, Number> = byType.mapValues { it.value.get() }
    fun bySeverity(): Map<String, Number> = bySeverity.mapValues { it.value.get() }
    fun byMaterial(): Map<String, Number> = byMaterial.mapValues { it.value.get() }
    fun itemsRemovedByMaterial(): Map<String, Number> = itemsRemovedByMaterial.mapValues { it.value.get() }
    fun preventionBlocks(): Map<String, Number> = preventionBlocks.mapValues { it.value.get() }

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
