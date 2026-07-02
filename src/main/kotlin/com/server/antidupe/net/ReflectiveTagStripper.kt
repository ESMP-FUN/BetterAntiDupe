package com.server.antidupe.net

import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Removes ADP's PersistentDataContainer keys from items in CLIENT-BOUND packets, so the
 * ownership tag never reaches a player's client (defeating NBT-viewer mods / the alt-account
 * swap test). The tag stays intact server-side — only the copy sent over the wire is rewritten.
 *
 * Reflection, not NMS/paperweight: this builds against plain paper-api (the project's normal
 * setup) and resolves all server-internal types at runtime. Because it compiles to plain
 * bytecode with no version-specific classes, ONE jar runs on every supported server (1.21.x on
 * JDK21 and 26.x on JDK25 alike) — the runtime adapts.
 *
 * The single version-fragile operation — editing an item — is deliberately routed through the
 * PUBLIC Bukkit API: convert the NMS item to a Bukkit copy via CraftItemStack, drop our
 * NamespacedKeys through PersistentDataContainer, convert back. That API is stable across the
 * whole version span, so only the packet plumbing (field/record reflection) varies, and it fails
 * OPEN — any reflection hiccup forwards the original packet untouched rather than dropping it.
 */
class ReflectiveTagStripper(
    private val plugin: Plugin,
    private val logger: Logger,
    namespace: String,
    keys: Collection<String>,
    /**
     * Strict mode: strip EVERY plugin's PDC entries from outbound items, not only ours —
     * anything reaching the client is "clean". Costs more (nearly every custom-data item is
     * rewritten) and can blank CIT resource packs / client mods that read item data, which is
     * why it's opt-in and paired with [whitelistNamespaces].
     */
    private val stripAll: Boolean = false,
    /** Namespaces preserved in strict mode. Our own namespaces are never honored here. */
    whitelistNamespaces: Collection<String> = emptyList(),
) : TagStripAdapter {

    private val handlerName = "antidupe_tag_stripper"

    /** The NamespacedKeys we own, e.g. antidupepro:adp_owner. Matches OwnershipManager. */
    private val targetKeys: Set<NamespacedKey> = keys.map { raw ->
        if (raw.contains(':')) {
            val ns = raw.substringBefore(':'); val k = raw.substringAfter(':')
            NamespacedKey(ns.lowercase(), k.lowercase())
        } else NamespacedKey(namespace.lowercase(), raw.lowercase())
    }.toSet()

    private val whitelist: Set<String> = run {
        val ours = targetKeys.map { it.namespace }.toSet()
        val cleaned = whitelistNamespaces.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.toSet()
        val rejected = cleaned intersect ours
        if (rejected.isNotEmpty()) {
            logger.warning("[TagStripper] strip_whitelist may not contain our own namespace(s) $rejected — ignored")
        }
        cleaned - ours
    }

    // --- reflection handles, resolved once at construction (throws => unsupported server) ---
    private val nmsItemClass = Class.forName("net.minecraft.world.item.ItemStack")
    private val craftItemStackClass = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack")
    private val asBukkitCopy = craftItemStackClass.getMethod("asBukkitCopy", nmsItemClass)
    private val asNMSCopy = craftItemStackClass.getMethod("asNMSCopy", org.bukkit.inventory.ItemStack::class.java)

    /** Only these client-bound packets can carry a tracked item; everything else passes straight through. */
    private val targetPackets = setOf(
        "ClientboundContainerSetSlotPacket",     // single inventory/container slot
        "ClientboundContainerSetContentPacket",  // whole window
        "ClientboundSetEquipmentPacket",         // worn/held on an entity
        "ClientboundSetEntityDataPacket",        // dropped item entities AND item frames
        "ClientboundSetPlayerInventoryPacket",   // 1.21.2+ direct slot set
        "ClientboundSetCursorItemPacket",        // 1.21.2+ carried cursor item
    )

    private val instanceFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    // ---------------------------------------------------------------- lifecycle

    override fun inject(player: Player) {
        try {
            val channel = channelOf(player)
            channel.eventLoop().execute {
                if (channel.pipeline().get(handlerName) == null) {
                    channel.pipeline().addBefore("packet_handler", handlerName, StripHandler())
                }
            }
        } catch (e: Exception) {
            logger.warning("[TagStripper] inject failed for ${player.name}: ${e.message}")
        }
    }

    override fun eject(player: Player) {
        try {
            val channel = channelOf(player)
            channel.eventLoop().execute {
                if (channel.pipeline().get(handlerName) != null) channel.pipeline().remove(handlerName)
            }
        } catch (e: Exception) {
            // channel already gone on quit — Netty drops the handler with it
        }
    }

    /** CraftPlayer.getHandle().connection.connection.channel, walked reflectively by field name. */
    private fun channelOf(player: Player): Channel {
        val serverPlayer = player.javaClass.getMethod("getHandle").invoke(player)
        val gameListener = readField(serverPlayer, "connection")
            ?: error("no connection on ${serverPlayer.javaClass.name}")
        val connection = readField(gameListener, "connection")
            ?: error("no connection on ${gameListener.javaClass.name}")
        return readField(connection, "channel") as? Channel
            ?: error("no channel on ${connection.javaClass.name}")
    }

    private inner class StripHandler : ChannelDuplexHandler() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            val out = if (msg.javaClass.simpleName in targetPackets) {
                try { rewritePacket(msg) ?: msg } catch (e: Throwable) {
                    logger.fine("[TagStripper] passthrough ${msg.javaClass.simpleName}: ${e.message}")
                    msg
                }
            } else msg
            super.write(ctx, out, promise)
        }
    }

    // ---------------------------------------------------------------- packet rewrite

    /** @return a new/mutated packet if a tracked item was stripped, else null (caller forwards original). */
    private fun rewritePacket(msg: Any): Any? {
        val cls = msg.javaClass
        val fields = instanceFieldCache.getOrPut(cls) { collectInstanceFields(cls) }

        val replacements = HashMap<String, Any?>()
        for (f in fields) {
            val value = f.get(msg) ?: continue
            val replaced = rewriteValue(value) ?: continue
            replacements[f.name] = replaced
        }
        if (replacements.isEmpty()) return null

        return if (cls.isRecord) reconstructRecord(msg, cls, replacements)
        else reconstructOrMutate(msg, cls, fields, replacements)
    }

    /** A single field value: a bare NMS item, or a List/NonNullList that may contain items. */
    private fun rewriteValue(value: Any): Any? = when {
        nmsItemClass.isInstance(value) -> stripItem(value)
        value is List<*> -> rewriteList(value)
        else -> null
    }

    private fun rewriteList(list: List<*>): Any? {
        var changed = false
        val out = ArrayList<Any?>(list.size)
        for (el in list) {
            val replaced = el?.let { rewriteElement(it) }
            if (replaced != null) { changed = true; out.add(replaced) } else out.add(el)
        }
        if (!changed) return null
        return coerceListType(list, out)
    }

    /** List element: NMS item, a Pair (equipment: slot -> item), or a DataValue (entity data). */
    private fun rewriteElement(el: Any): Any? = when {
        nmsItemClass.isInstance(el) -> stripItem(el)
        el.javaClass.name == "com.mojang.datafixers.util.Pair" -> rewritePair(el)
        el.javaClass.simpleName == "DataValue" -> rewriteDataValue(el)
        else -> null
    }

    private fun rewritePair(pair: Any): Any? {
        val c = pair.javaClass
        val second = c.getMethod("getSecond").invoke(pair) ?: return null
        if (!nmsItemClass.isInstance(second)) return null
        val newSecond = stripItem(second) ?: return null
        val first = c.getMethod("getFirst").invoke(pair)
        return c.getMethod("of", Any::class.java, Any::class.java).invoke(null, first, newSecond)
    }

    private fun rewriteDataValue(dv: Any): Any? {
        val c = dv.javaClass
        val value = c.getMethod("value").invoke(dv) ?: return null
        if (!nmsItemClass.isInstance(value)) return null
        val newValue = stripItem(value) ?: return null
        val id = c.getMethod("id").invoke(dv)
        val serializer = c.getMethod("serializer").invoke(dv)
        val ctor = c.declaredConstructors.first { it.parameterCount == 3 }
        ctor.isAccessible = true
        return ctor.newInstance(id, serializer, newValue)
    }

    // ---------------------------------------------------------------- item strip (public Bukkit API)

    /** @return a fresh NMS item with the offending keys removed, or null if nothing to strip. */
    private fun stripItem(nmsItem: Any): Any? {
        val bukkit = asBukkitCopy.invoke(null, nmsItem) as org.bukkit.inventory.ItemStack
        if (bukkit.type.isAir) return null
        val meta = bukkit.itemMeta ?: return null
        val pdc = meta.persistentDataContainer
        val present: List<NamespacedKey> =
            if (stripAll) pdc.keys.filter { it.namespace !in whitelist }
            else targetKeys.filter { pdc.keys.contains(it) }
        if (present.isEmpty()) return null
        for (key in present) pdc.remove(key)
        bukkit.itemMeta = meta
        return asNMSCopy.invoke(null, bukkit)
    }

    // ---------------------------------------------------------------- reconstruction helpers

    private fun reconstructRecord(msg: Any, cls: Class<*>, replacements: Map<String, Any?>): Any {
        val components = cls.recordComponents
        val args = components.map { rc ->
            if (replacements.containsKey(rc.name)) replacements[rc.name] else rc.accessor.invoke(msg)
        }
        val ctor = cls.getDeclaredConstructor(*components.map { it.type }.toTypedArray())
        ctor.isAccessible = true
        return ctor.newInstance(*args.toTypedArray())
    }

    /**
     * Non-record packet: prefer a constructor whose parameter types line up with the instance
     * fields (so we never set a final field), falling back to in-place mutation if none matches.
     */
    private fun reconstructOrMutate(
        msg: Any, cls: Class<*>, fields: List<Field>, replacements: Map<String, Any?>,
    ): Any {
        val values = fields.map { if (replacements.containsKey(it.name)) replacements[it.name] else it.get(msg) }
        val ctor = cls.declaredConstructors.firstOrNull { c ->
            c.parameterCount == fields.size &&
                c.parameterTypes.zip(fields).all { (p, f) -> p.isAssignableFrom(f.type) }
        }
        if (ctor != null) {
            ctor.isAccessible = true
            return ctor.newInstance(*values.toTypedArray())
        }
        // Last resort: mutate the existing packet's item fields in place.
        for (f in fields) if (replacements.containsKey(f.name)) f.set(msg, replacements[f.name])
        return msg
    }

    /** Rebuild a list as the same concrete type the field expects — NonNullList must stay NonNullList. */
    private fun coerceListType(original: List<*>, out: List<Any?>): Any {
        if (original.javaClass.simpleName == "NonNullList") {
            val nnl = Class.forName("net.minecraft.core.NonNullList")
            val empty = nmsItemClass.getField("EMPTY").get(null)
            val of = nnl.getMethod("of", Any::class.java, java.lang.reflect.Array.newInstance(Any::class.java, 0).javaClass)
            return of.invoke(null, empty, out.toTypedArray())
        }
        return out
    }

    // ---------------------------------------------------------------- field reflection

    private fun collectInstanceFields(cls: Class<*>): List<Field> {
        val result = ArrayList<Field>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
                f.isAccessible = true
                result.add(f)
            }
            c = c.superclass
        }
        return result
    }

    private fun readField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) { f.isAccessible = true; return f.get(target) }
            c = c.superclass
        }
        return null
    }
}
