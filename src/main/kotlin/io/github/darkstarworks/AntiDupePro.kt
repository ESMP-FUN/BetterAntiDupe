package io.github.darkstarworks

import com.server.antidupe.commands.AdpCommand
import com.server.antidupe.ledger.ChainOfCustody
import com.server.antidupe.ledger.LedgerStorage
import com.server.antidupe.platform.PlatformScheduler
import com.server.antidupe.util.Messages
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

class AntiDupePro : JavaPlugin() {

    lateinit var pluginScope: CoroutineScope
        private set

    lateinit var materialsConfig: FileConfiguration
        private set

    lateinit var scheduler: PlatformScheduler
        private set

    private var chainOfCustody: ChainOfCustody? = null
    private lateinit var adpCommand: AdpCommand

    override fun onEnable() {
        @Suppress("DEPRECATION")
        logger.info("=== AntiDupePro v${description.version} ===")
        logger.info("Initializing Chain of Custody...")

        try {
            pluginScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scheduler = PlatformScheduler(this)
            logger.info("✓ Scheduler initialized (${if (scheduler.isFolia) "Folia" else "Bukkit"} mode)")

            saveDefaultConfig()
            Messages.init(this)
            materialsConfig = loadMaterialsConfig()
            applyLogLevel()
            validateConfiguration()
            logger.info("✓ Configuration loaded")

            adpCommand = AdpCommand(this, pluginScope, scheduler)
            getCommand("adp")?.let { cmd ->
                cmd.setExecutor(adpCommand)
                cmd.tabCompleter = adpCommand
            }

            initializeChainOfCustody()
            registerJoinBaseline()

            logger.info("=== AntiDupePro enabled successfully ===")
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to initialize AntiDupePro", e)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        logger.info("=== AntiDupePro shutting down ===")
        try {
            chainOfCustody?.shutdown()
            chainOfCustody = null
            if (::pluginScope.isInitialized) pluginScope.cancel()
        } catch (e: Exception) {
            logger.warning("Error during shutdown: ${e.message}")
        }
        logger.info("=== AntiDupePro disabled ===")
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
            val trackedMaterials = materialsConfig.getStringList("tracked_materials")
                .mapNotNull { name ->
                    try { Material.valueOf(name.uppercase()) }
                    catch (e: IllegalArgumentException) {
                        logger.warning("Invalid material in tracked_materials: $name"); null
                    }
                }.toSet()

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

            runBlocking {
                val ledgerStorage = LedgerStorage.create(this@AntiDupePro)
                chainOfCustody = ChainOfCustody.initialize(
                    plugin = this@AntiDupePro,
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
                    logger = logger
                )
            }

            chainOfCustody?.onDupeAlert { alert ->
                // In-game text comes from messages.yml; the console log below stays English.
                val details = if (alert.messageKey.isNotEmpty())
                    Messages.msg(alert.messageKey, alert.placeholders) else alert.details
                val message = Messages.msg("alerts.broadcast",
                    "player" to alert.playerName,
                    "type" to alert.type.name,
                    "material" to alert.material.name,
                    "details" to details
                ) + if (alert.severity == com.server.antidupe.ledger.Severity.CRITICAL)
                    Messages.msg("alerts.critical-suffix") else ""

                // Alerts can be emitted from reconciliation coroutines — hop to the main
                // (global region) thread before touching the online-player roster.
                scheduler.runMain(Runnable {
                    Bukkit.getOnlinePlayers()
                        .filter { it.isOp || it.hasPermission("antidupe.admin") }
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
}
