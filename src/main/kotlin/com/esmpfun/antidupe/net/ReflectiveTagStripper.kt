package com.esmpfun.antidupe.net

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
 * Removes our PersistentDataContainer keys from items in client-bound packets. The server-side
 * item keeps its tag; only the copy sent over the wire is rewritten.
 *
 * Every server-internal type is resolved by reflection at runtime, so one jar covers the whole
 * supported version span. Editing an item deliberately goes through the public Bukkit API
 * (CraftItemStack plus PersistentDataContainer), which is stable across that span, leaving only
 * the packet plumbing version-fragile. Any reflection failure forwards the original packet
 * untouched rather than dropping it.
 */
class ReflectiveTagStripper(
    private val plugin: Plugin,
    private val logger: Logger,
    namespace: String,
    keys: Collection<String>,
    /**
     * Strip every plugin's PDC entries, not only ours. Can blank CIT resource packs and client
     * mods that read item data, hence [whitelistNamespaces].
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
            logger.warning("[TagStripper] In config.yml, strip_whitelist includes $rejected, which is this" +
                " plugin's own item data. That entry is ignored, because the hidden owner mark has to stay" +
                " hidden. The rest of your list still works.")
        }
        cleaned - ours
    }

    private val nmsItemClass = Class.forName("net.minecraft.world.item.ItemStack")
    private val craftItemStackClass = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack")
    // 26.3 dropped the ItemStack overload and kept the ItemInstance one, which ItemStack implements.
    private val asBukkitCopy = runCatching { craftItemStackClass.getMethod("asBukkitCopy", nmsItemClass) }
        .getOrElse { craftItemStackClass.getMethod("asBukkitCopy", Class.forName("net.minecraft.world.item.ItemInstance")) }
    private val asNMSCopy = craftItemStackClass.getMethod("asNMSCopy", org.bukkit.inventory.ItemStack::class.java)

    private val targetPackets = setOf(
        "ClientboundContainerSetSlotPacket",
        "ClientboundContainerSetContentPacket",
        "ClientboundSetEquipmentPacket",
        "ClientboundSetEntityDataPacket",        // dropped item entities AND item frames
        "ClientboundSetPlayerInventoryPacket",   // 1.21.2+ direct slot set
        "ClientboundSetCursorItemPacket",        // 1.21.2+ carried cursor item
    )

    /**
     * Item-bearing packets deliberately left unstripped. Villager offers are built by the villager,
     * never tagged by us, so only strict mode has anything to hide in them.
     */
    private val unstrippedUnlessStrict = setOf("ClientboundMerchantOffersPacket")

    private val instanceFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    /** Packet class names already logged as a passthrough/failure, so the warning fires once each. */
    private val loggedPacketNames = ConcurrentHashMap.newKeySet<String>()

    /**
     * A version rename could move an item-bearing packet out of [targetPackets] and the tag
     * would start leaking to clients with no signal at all; this turns that into one warning.
     * The name only shortlists a packet; it counts as item-bearing when an ItemStack is actually
     * reachable from its fields, so TakeItemEntity or SetHeldSlot, which carry ids and slot
     * numbers, stay quiet.
     */
    private fun looksItemBearing(packet: Class<*>): Boolean {
        val name = packet.simpleName
        val shortlisted = name.startsWith("Clientbound") && (
            name.contains("Item") || name.contains("Slot") ||
            name.contains("Inventory") || name.contains("Equipment") ||
            name.contains("ContainerSetContent") || name.contains("MerchantOffers")
        )
        return shortlisted && reachesItemStack(packet, HashSet(), 0)
    }

    private val minecraftTypeName = Regex("net\\.minecraft\\.[\\w.$]+")

    /**
     * Follows field types, their generic arguments and superclasses within net.minecraft, a few
     * levels deep. Only reads declared types and never makes a field accessible: MerchantOffers
     * extends ArrayList, and touching java.util internals throws on modern Java.
     */
    private fun reachesItemStack(cls: Class<*>, seen: MutableSet<Class<*>>, depth: Int): Boolean {
        if (cls == nmsItemClass) return true
        if (depth > 3 || !seen.add(cls)) return false
        val typeNames = ArrayList<String>()
        cls.genericSuperclass?.let { typeNames.add(it.typeName) }
        var c: Class<*>? = cls
        while (c != null && c.name.startsWith("net.minecraft.")) {
            for (field in c.declaredFields) {
                if (!Modifier.isStatic(field.modifiers)) typeNames.add(field.genericType.typeName)
            }
            c = c.superclass
        }
        for (typeName in typeNames) {
            for (match in minecraftTypeName.findAll(typeName)) {
                val referenced = try {
                    Class.forName(match.value, false, cls.classLoader)
                } catch (e: Throwable) {
                    continue
                }
                if (reachesItemStack(referenced, seen, depth + 1)) return true
            }
        }
        return false
    }

    override fun inject(player: Player) {
        try {
            val channel = channelOf(player)
            channel.eventLoop().execute {
                if (channel.pipeline().get(handlerName) == null) {
                    channel.pipeline().addBefore("packet_handler", handlerName, StripHandler())
                }
            }
        } catch (e: Exception) {
            logger.warning("[TagStripper] Could not start hiding the owner mark from ${player.name}'s game" +
                " client, so they may be able to see it. Tracking and dupe detection still work as normal." +
                " Details: ${e.message}")
        }
    }

    override fun eject(player: Player) {
        try {
            val channel = channelOf(player)
            channel.eventLoop().execute {
                if (channel.pipeline().get(handlerName) != null) channel.pipeline().remove(handlerName)
            }
        } catch (e: Exception) {
            // channel already gone on quit, Netty drops the handler with it
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
            val name = msg.javaClass.simpleName
            val out = if (name == "ClientboundBundlePacket") {
                // A bundle wraps several packets sent as one unit, commonly a spawn plus its
                // SetEntityData. Without descending into it, a bundled SetEntityData or
                // container packet ships the tag straight through.
                try { rewriteBundle(msg) ?: msg } catch (e: Throwable) {
                    if (loggedPacketNames.add(name)) {
                        logger.warning("[TagStripper] Could not check part of what the server sent to a player," +
                            " so the hidden owner mark may show up in their game client. Tracking and dupe" +
                            " detection still work as normal. Details: ${e.message}")
                    }
                    msg
                }
            } else if (name in targetPackets) {
                try { rewritePacket(msg) ?: msg } catch (e: Throwable) {
                    if (loggedPacketNames.add(name)) {
                        logger.warning("[TagStripper] Could not take the owner mark out of something the server" +
                            " sent to a player, so it may show up in their game client. Tracking and dupe" +
                            " detection still work as normal. Please report this with your server version and" +
                            " these details: $name, ${e.message}")
                    }
                    msg
                }
            } else {
                // Runs inside the player's network pipeline, where anything thrown disconnects them.
                val expected = !stripAll && name in unstrippedUnlessStrict
                if (!expected && name !in loggedPacketNames && loggedPacketNames.add(name) &&
                    runCatching { looksItemBearing(msg.javaClass) }.getOrDefault(false)) {
                    logger.warning("[TagStripper] This server sends some item information in a way this version" +
                        " of BetterAntiDupe does not know about, so the hidden owner mark may show up in" +
                        " players' game clients. Tracking and dupe detection still work as normal. Please" +
                        " report this with your server version and this detail: $name")
                }
                msg
            }
            super.write(ctx, out, promise)
        }
    }

    /**
     * A bundle's members arrive via `subPackets()` and never reach the outer `write`; a rebuilt
     * bundle goes back through the bundle class's `Iterable` constructor.
     *
     * @return a fresh bundle if a member was stripped, else null (caller forwards the original).
     */
    private fun rewriteBundle(msg: Any): Any? {
        val subPackets = msg.javaClass.getMethod("subPackets").invoke(msg) as? Iterable<*> ?: return null
        var changed = false
        val out = ArrayList<Any?>()
        for (p in subPackets) {
            val replaced = if (p != null && p.javaClass.simpleName in targetPackets) {
                try { rewritePacket(p) } catch (e: Throwable) {
                    if (loggedPacketNames.add(p.javaClass.simpleName)) {
                        logger.warning("[TagStripper] Could not take the owner mark out of something the server" +
                            " sent to a player, so it may show up in their game client. Tracking and dupe" +
                            " detection still work as normal. Please report this with your server version and" +
                            " these details: ${p.javaClass.simpleName}, ${e.message}")
                    }
                    null
                }
            } else null
            if (replaced != null) { changed = true; out.add(replaced) } else out.add(p)
        }
        if (!changed) return null
        return msg.javaClass.getConstructor(Iterable::class.java).newInstance(out)
    }

    /** @return a new/mutated packet if a tracked item was stripped, else null (caller forwards original). */
    private fun rewritePacket(msg: Any): Any? {
        val cls = msg.javaClass
        val fields = instanceFieldCache.computeIfAbsent(cls) { collectInstanceFields(cls) }

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

    /** A Pair here is an equipment slot plus its item; a DataValue is one entity-data entry. */
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

    private fun reconstructRecord(msg: Any, cls: Class<*>, replacements: Map<String, Any?>): Any {
        val components = cls.recordComponents
        val args = components.map { rc ->
            if (replacements.containsKey(rc.name)) replacements[rc.name] else rc.accessor.invoke(msg)
        }
        val ctor = cls.getDeclaredConstructor(*components.map { it.type }.toTypedArray())
        ctor.isAccessible = true
        return ctor.newInstance(*args.toTypedArray())
    }

    /** Prefers a matching constructor so a final field is never written to. */
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
        for (f in fields) if (replacements.containsKey(f.name)) f.set(msg, replacements[f.name])
        return msg
    }

    /** The field's concrete list type must be preserved: a NonNullList has to stay a NonNullList. */
    private fun coerceListType(original: List<*>, out: List<Any?>): Any {
        if (original.javaClass.simpleName == "NonNullList") {
            val nnl = Class.forName("net.minecraft.core.NonNullList")
            val empty = nmsItemClass.getField("EMPTY").get(null)
            val of = nnl.getMethod("of", Any::class.java, java.lang.reflect.Array.newInstance(Any::class.java, 0).javaClass)
            return of.invoke(null, empty, out.toTypedArray())
        }
        return out
    }

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
