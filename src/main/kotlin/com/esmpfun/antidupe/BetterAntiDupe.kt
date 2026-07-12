package com.esmpfun.antidupe

import com.esmpfun.antidupe.commands.AdpCommand
import com.esmpfun.antidupe.ledger.ChainOfCustody
import com.esmpfun.antidupe.ledger.LedgerStorage
import com.esmpfun.antidupe.notify.AlertNotifier
import com.esmpfun.antidupe.platform.PlatformScheduler
import com.esmpfun.antidupe.util.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.logging.Level

class BetterAntiDupe : JavaPlugin() {

    lateinit var pluginScope: CoroutineScope
        private set

    lateinit var materialsConfig: FileConfiguration
        private set

    lateinit var scheduler: PlatformScheduler
        private set

    private var chainOfCustody: ChainOfCustody? = null
    private var tagStripper: com.esmpfun.antidupe.net.TagStripAdapter? = null
    private var ownershipKeys: com.esmpfun.antidupe.ledger.OwnershipKeys? = null
    private lateinit var adpCommand: AdpCommand

    override fun onEnable() {
        migrateLegacyDataFolder()
        @Suppress("DEPRECATION")
        logger.info("=== BetterAntiDupe v${description.version} ===")
        logger.info("Initializing Chain of Custody...")

        try {
            pluginScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scheduler = PlatformScheduler(this)
            logger.info("✓ Scheduler initialized (${if (scheduler.isFolia) "Folia" else "Bukkit"} mode)")

            saveDefaultConfig()
            Messages.init(this, config.getString("language", "en") ?: "en")
            materialsConfig = loadMaterialsConfig()
            applyLogLevel()
            validateConfiguration()
            logger.info("✓ Configuration loaded")

            adpCommand = AdpCommand(this, pluginScope, scheduler)
            getCommand("antidupe")?.let { cmd ->
                cmd.setExecutor(adpCommand)
                cmd.tabCompleter = adpCommand
            }

            initializeChainOfCustody()
            registerJoinBaseline()
            initializeTagStripper()
            registerDuperPrevention()

            // Update checking (PluginPulse). Spigot-safe: plain-text notices
            // when Adventure is absent. Config in pluginpulse.yml; server owners
            // can override mode/interval via an `update:` block in config.yml.
            io.github.darkstarworks.pluginpulse.PluginPulse.bootstrap(this)

            logger.info("=== BetterAntiDupe enabled successfully ===")
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to initialize BetterAntiDupe", e)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        logger.info("=== BetterAntiDupe shutting down ===")
        try {
            io.github.darkstarworks.pluginpulse.PluginPulse.shutdown(this)
            tagStripper?.let { s -> server.onlinePlayers.forEach { s.eject(it) } }
            tagStripper = null
            chainOfCustody?.shutdown()
            chainOfCustody = null
            if (::pluginScope.isInitialized) pluginScope.cancel()
        } catch (e: Exception) {
            logger.warning("Error during shutdown: ${e.message}")
        }
        logger.info("=== BetterAntiDupe disabled ===")
    }

    private fun loadMaterialsConfig(): FileConfiguration {
        val file = File(dataFolder, "materials.yml")
        if (!file.exists()) {
            val legacyHasAny = config.contains("tracked_materials") ||
                config.contains("tmar_limits") ||
                config.contains("ledger.alert_thresholds")

            if (legacyHasAny) {
                val migrated = YamlConfiguration()
                config.getStringList("tracked_materials").takeIf { it.isNotEmpty() }?.let {
                    migrated.set("tracked_materials", it)
                }
                config.getConfigurationSection("tmar_limits")?.getKeys(false)?.forEach { k ->
                    migrated.set("tmar_limits.$k", config.getInt("tmar_limits.$k"))
                }
                config.getConfigurationSection("ledger.alert_thresholds")?.getKeys(false)?.forEach { k ->
                    migrated.set("alert_thresholds.$k", config.getInt("ledger.alert_thresholds.$k"))
                }
                migrated.save(file)

                config.set("tracked_materials", null)
                config.set("tmar_limits", null)
                config.set("ledger.alert_thresholds", null)
                saveConfig()

                logger.info("Migrated material lists to materials.yml")
            } else {
                saveResource("materials.yml", false)
            }
        }
        return YamlConfiguration.loadConfiguration(file)
    }

    private fun validateConfiguration() {
        val mats = materialsConfig

        val redisPort = config.getInt("redis.port", 6379)
        if (redisPort < 1 || redisPort > 65535) {
            logger.warning("Invalid redis.port ($redisPort), using default 6379")
            config.set("redis.port", 6379)
        }

        val trackedMaterials = mats.getStringList("tracked_materials")
        if (trackedMaterials.isEmpty()) {
            logger.warning("No tracked_materials configured! Using defaults.")
            mats.set("tracked_materials", listOf(
                "DIAMOND_BLOCK", "NETHERITE_INGOT", "BEACON",
                "ENCHANTED_GOLDEN_APPLE", "SHULKER_BOX", "ELYTRA", "NETHER_STAR"
            ))
        }

        if (config.getBoolean("shadow_mode", true)) {
            logger.info("Running in SHADOW MODE - suspects will be tracked, not banned")
        }
        if (config.getBoolean("auto_delete_dupes", false)) {
            logger.info("AUTO-DELETE enabled - detected dupes will be removed automatically")
        }
    }

    private fun initializeChainOfCustody() {
        try {
            val configuredMaterials = materialsConfig.getStringList("tracked_materials")
                .mapNotNull { name ->
                    try { Material.valueOf(name.uppercase()) }
                    catch (e: IllegalArgumentException) {
                        logger.warning("Invalid material in tracked_materials: $name"); null
                    }
                }
            // Shulker boxes of every colour are always tracked (as materials.yml documents) —
            // they're the primary laundering vector, and listing only SHULKER_BOX would leave
            // the 16 dyed variants invisible to the ledger.
            val allShulkerBoxes = Material.values().filter {
                it.name.endsWith("SHULKER_BOX") && !it.name.startsWith("LEGACY_")
            }
            val trackedMaterials = (configuredMaterials + allShulkerBoxes).toSet()

            val tmarLimits = mutableMapOf<Material, Int>()
            materialsConfig.getConfigurationSection("tmar_limits")?.let { section ->
                section.getKeys(false).forEach { key ->
                    try {
                        val material = Material.valueOf(key.uppercase())
                        val limit = section.getInt(key)
                        if (limit > 0) tmarLimits[material] = limit
                    } catch (e: IllegalArgumentException) {
                        logger.warning("Invalid material in tmar_limits: $key")
                    }
                }
            }

            val witnessRadius = config.getDouble("ledger.witness.radius", 48.0)
            val verifiedThreshold = config.getInt("ledger.witness.verified_threshold", 3)
            val suspiciousSoloRatio = config.getDouble("ledger.witness.suspicious_solo_ratio", 0.8)
            val reconciliationCooldownMs = config.getLong("ledger.reconciliation.cooldown_ms", 5000L)

            val alertThresholds = mutableMapOf<Material, Int>()
            var defaultAlertThreshold = 5
            materialsConfig.getConfigurationSection("alert_thresholds")?.let { section ->
                section.getKeys(false).forEach { key ->
                    if (key.equals("default", ignoreCase = true)) {
                        defaultAlertThreshold = section.getInt(key, 5)
                    } else {
                        try { alertThresholds[Material.valueOf(key.uppercase())] = section.getInt(key) }
                        catch (e: IllegalArgumentException) { logger.warning("Invalid material in alert_thresholds: $key") }
                    }
                }
            }

            // Resolve the (configurable) ownership tag key once; the detection side and the
            // client-side stripper must agree on it. Handles rename migration via marker file.
            val keys = com.esmpfun.antidupe.ledger.OwnershipKeys.resolve(this, logger)
            ownershipKeys = keys
            if (keys.primary.toString() != "${name.lowercase()}:adp_owner") {
                logger.info("✓ Ownership tag key: ${keys.primary}" +
                    if (keys.legacy.isNotEmpty()) " (legacy: ${keys.legacy.joinToString()})" else "")
            }

            runBlocking {
                val ledgerStorage = LedgerStorage.create(this@BetterAntiDupe)
                chainOfCustody = ChainOfCustody.initialize(
                    plugin = this@BetterAntiDupe,
                    scope = pluginScope,
                    scheduler = scheduler,
                    ledgerStorage = ledgerStorage,
                    trackedMaterials = trackedMaterials,
                    tmarLimits = tmarLimits,
                    witnessRadius = witnessRadius,
                    verifiedThreshold = verifiedThreshold,
                    suspiciousSoloRatio = suspiciousSoloRatio,
                    reconciliationCooldownMs = reconciliationCooldownMs,
                    alertThresholds = alertThresholds,
                    defaultAlertThreshold = defaultAlertThreshold,
                    sensitivity = config.getInt("detection.sensitivity", 50),
                    logger = logger,
                    ownershipKeys = keys
                )
            }

            val notifier = AlertNotifier(config.getConfigurationSection("notifications"), pluginScope, logger)

            chainOfCustody?.onDupeAlert { alert ->
                notifier.handle(alert)

                // In-game text comes from messages.yml; the console log below stays English.
                val details = if (alert.messageKey.isNotEmpty())
                    Messages.msg(alert.messageKey, alert.placeholders) else alert.details
                val message = Messages.msg("alerts.broadcast",
                    "player" to alert.playerName,
                    "type" to alert.type.name,
                    "material" to alert.material.name,
                    "details" to details
                ) + if (alert.severity == com.esmpfun.antidupe.ledger.Severity.CRITICAL)
                    Messages.msg("alerts.critical-suffix") else ""

                // Alerts can be emitted from reconciliation coroutines — hop to the main
                // (global region) thread before touching the online-player roster.
                scheduler.runMain(Runnable {
                    // Alerts go to anyone with antidupe.alerts (admins inherit it via the
                    // antidupe.admin child tree); ledger COMMAND access is gated separately on
                    // antidupe.ledger, so a mod can be alerts-only.
                    Bukkit.getOnlinePlayers()
                        .filter { it.hasPermission("antidupe.alerts") }
                        .forEach { it.sendMessage(message) }
                })

                logger.warning("[DUPE] ${alert.playerName}: ${alert.details}")
            }

            chainOfCustody?.let {
                adpCommand.setChainOfCustody(it)
                it.reconciliationEngine.healLogLevel = healLogLevelFor()
            }

            logger.info("✓ Chain of Custody initialized")
            logger.info("  Tracking ${trackedMaterials.size} materials, ${tmarLimits.size} TMAR limits")
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to initialize Chain of Custody", e)
        }
    }

    fun getChainOfCustody(): ChainOfCustody? = chainOfCustody

    /**
     * Mechanic-level blocking of the classic block dupers (rail / carpet / TNT / gravity).
     * These duplicate blocks in the world before any inventory event exists, so the ledger
     * alone can only catch the aftermath — this stops the contraption itself.
     */
    private fun registerDuperPrevention() {
        val rail = config.getBoolean("prevent-rail-dupers", true)
        val carpet = config.getBoolean("prevent-carpet-dupers", true)
        val gravity = config.getBoolean("prevent-gravity-dupers", true)
        val tnt = config.getBoolean("prevent-tnt-dupers", true)
        val desync = config.getBoolean("prevent-container-desync-dupers", true)
        if (!rail && !carpet && !gravity && !tnt && !desync) {
            logger.info("Duper prevention fully disabled in config")
            return
        }
        server.pluginManager.registerEvents(
            DuperPreventionListener(rail, carpet, gravity, tnt, desync, logger), this
        )
        val active = listOfNotNull(
            "rail".takeIf { rail }, "carpet".takeIf { carpet },
            "gravity".takeIf { gravity }, "tnt".takeIf { tnt },
            "container-desync".takeIf { desync }
        )
        logger.info("✓ Duper prevention active: ${active.joinToString(", ")}")
    }

    /**
     * Optional client-side tag concealment. Strips ADP's PDC keys from outgoing item packets so
     * players can't read the ownership tag with an NBT-viewer mod. Server-side data is untouched.
     * Gated behind config and wrapped so a mapping mismatch on an unexpected server build disables
     * the feature cleanly instead of breaking the plugin.
     */
    private fun initializeTagStripper() {
        if (!config.getBoolean("hide_tag_from_clients", true)) return
        try {
            // Same key(s) the detection writes — resolved once in initializeChainOfCustody.
            // Legacy keys (from a rename) are concealed too; un-migrated items must not leak.
            val keys = ownershipKeys
            val namespace = keys?.primary?.namespace ?: name.lowercase()
            val qualified = keys?.allQualified ?: listOf("$namespace:adp_owner")

            val stripAll = config.getBoolean("strip_all_custom_data", false)
            val whitelist = config.getStringList("strip_whitelist")
            if (stripAll) {
                logger.info("Strict strip mode: ALL custom item data is hidden from clients" +
                    if (whitelist.isNotEmpty()) " (except: ${whitelist.joinToString()})" else "")
            }

            // Resolve the adapter for THIS server version; null = unsupported build, feature off.
            val stripper = com.esmpfun.antidupe.net.TagStripAdapters.load(
                this, logger, namespace, qualified, stripAll, whitelist
            ) ?: return
            tagStripper = stripper

            // Players with antidupe.tag.view keep the real tag in their own client (NBT viewers,
            // F3) — we simply never inject the stripper for them. Evaluated at join, so a
            // permission change applies on their next login.
            fun injectUnlessExempt(player: org.bukkit.entity.Player) {
                if (!player.hasPermission("antidupe.tag.view")) stripper.inject(player)
            }

            server.pluginManager.registerEvents(object : org.bukkit.event.Listener {
                @org.bukkit.event.EventHandler
                fun onJoin(event: org.bukkit.event.player.PlayerJoinEvent) = injectUnlessExempt(event.player)
                @org.bukkit.event.EventHandler
                fun onQuit(event: org.bukkit.event.player.PlayerQuitEvent) = stripper.eject(event.player)
            }, this)

            // Players already online across a /reload.
            server.onlinePlayers.forEach { injectUnlessExempt(it) }
            logger.info("✓ Client-side tag concealment enabled (hide_tag_from_clients)")
        } catch (e: Throwable) {
            logger.log(Level.WARNING, "Tag stripper unavailable on this server build — feature disabled", e)
            tagStripper = null
        }
    }

    /**
     * Map the config's 5-level scheme (CRITICAL/ERROR/WARNING/INFO/DEBUG, each including those
     * above it) onto java.util.logging levels and apply it to the plugin logger. Note CRITICAL
     * and ERROR both map to SEVERE (the JVM has no separate tier).
     */
    private fun applyLogLevel() {
        val configured = (config.getString("console_log_level", "INFO") ?: "INFO").uppercase()
        val level = when (configured) {
            "CRITICAL", "ERROR" -> Level.SEVERE
            "WARNING" -> Level.WARNING
            "INFO" -> Level.INFO
            "DEBUG" -> Level.FINE
            else -> { logger.warning("Unknown console_log_level '$configured', using INFO"); Level.INFO }
        }
        logger.level = level
    }

    /** The self-heal "re-baselined" line is verbose by nature; show it only at DEBUG. */
    private fun healLogLevelFor(): Level {
        val configured = (config.getString("console_log_level", "INFO") ?: "INFO").uppercase()
        return if (configured == "DEBUG") Level.INFO else Level.FINE
    }

    /** Seed a never-seen player's ledger from their inventory on first join (one-time baseline). */
    private fun registerJoinBaseline() {
        server.pluginManager.registerEvents(object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler
            fun onJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
                val coc = chainOfCustody ?: return
                val player = event.player
                pluginScope.launch {
                    try { coc.baselineIfNew(player) }
                    catch (e: Exception) { logger.warning("Baseline failed for ${player.name}: ${e.message}") }
                }
            }
        }, this)
    }

    /** One-time migration from the pre-4.0 plugin name (plugins/AntiDupePro/). */
    private fun migrateLegacyDataFolder() {
        try {
            if (dataFolder.exists()) return
            val legacy = java.io.File(dataFolder.parentFile, "AntiDupePro")
            if (!legacy.isDirectory) return
            logger.info("Migrating data from plugins/AntiDupePro/ to plugins/${dataFolder.name}/ ...")
            legacy.walkTopDown().forEach { src ->
                val dest = java.io.File(dataFolder, src.relativeTo(legacy).path)
                if (src.isDirectory) dest.mkdirs() else src.copyTo(dest, overwrite = false)
            }
            logger.info("Migration complete — the old folder was kept as a backup.")
        } catch (e: Exception) {
            logger.severe("Legacy data-folder migration failed: ${e.message} — migrate manually and restart.")
        }
    }
}
