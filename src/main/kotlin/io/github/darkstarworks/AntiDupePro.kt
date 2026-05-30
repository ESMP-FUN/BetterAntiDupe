package io.github.darkstarworks

import com.server.antidupe.commands.AdpCommand
import com.server.antidupe.core.DupeAlertManager
import com.server.antidupe.core.IsotopeManager
import com.server.antidupe.core.IsotopeScanner
import com.server.antidupe.data.IsotopeStorage
import com.server.antidupe.ledger.ChainOfCustody
import com.server.antidupe.ledger.LedgerStorage
import com.server.antidupe.listeners.IsotopeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class AntiDupePro : JavaPlugin() {

    lateinit var pluginScope: CoroutineScope
        private set

    /** Material-keyed configuration (tracked_materials, tmar_limits, alert_thresholds). */
    lateinit var materialsConfig: FileConfiguration
        private set

    private lateinit var isotopeStorage: IsotopeStorage
    private lateinit var isotopeManager: IsotopeManager
    private lateinit var isotopeScanner: IsotopeScanner
    private lateinit var dupeAlertManager: DupeAlertManager
    private lateinit var isotopeListener: IsotopeListener
    private lateinit var adpCommand: AdpCommand

    private var chainOfCustody: ChainOfCustody? = null

    override fun onEnable() {
        @Suppress("DEPRECATION")
        logger.info("=== AntiDupePro v${description.version} ===")
        logger.info("Initializing Protection Engine...")

        try {
            pluginScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            logger.info("✓ Coroutine scope initialized")

            saveDefaultConfig()
            materialsConfig = loadMaterialsConfig()
            validateConfiguration()
            logger.info("✓ Configuration loaded and validated")

            isotopeStorage = IsotopeStorage.create(this)
            isotopeStorage.connect()

            isotopeManager = IsotopeManager(this, isotopeStorage, pluginScope)
            logger.info("✓ Core initialized")

            isotopeScanner = IsotopeScanner(this, isotopeStorage)
            logger.info("✓ Scanner initialized")

            dupeAlertManager = DupeAlertManager(this)
            logger.info("✓ Dupe Alert Manager initialized")

            isotopeListener = IsotopeListener(this, isotopeManager, dupeAlertManager, isotopeScanner, pluginScope)
            server.pluginManager.registerEvents(isotopeListener, this)
            logger.info("✓ Event listeners registered")

            adpCommand = AdpCommand(this, isotopeStorage, pluginScope)
            getCommand("adp")?.let { cmd ->
                cmd.setExecutor(adpCommand)
                cmd.tabCompleter = adpCommand
            }
            logger.info("✓ Commands registered")

            initializeChainOfCustody()

            logger.info("=== AntiDupePro enabled successfully ===")
        } catch (e: Exception) {
            logger.severe("Failed to initialize AntiDupePro: ${e.message}")
            logger.log(java.util.logging.Level.SEVERE, "Stack trace:", e)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        logger.info("=== AntiDupePro shutting down ===")

        try {
            chainOfCustody?.shutdown()
            chainOfCustody = null

            if (::pluginScope.isInitialized) {
                pluginScope.cancel()
            }

            if (::isotopeStorage.isInitialized) {
                isotopeStorage.disconnect()
            }
        } catch (e: Exception) {
            logger.warning("Error during shutdown: ${e.message}")
        }

        logger.info("=== AntiDupePro disabled ===")
    }

    /**
     * Load materials.yml. If absent and config.yml still holds legacy keys
     * (tracked_materials, tmar_limits, ledger.alert_thresholds), migrate them
     * into the new file and strip them from config.yml on disk.
     */
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

                logger.info("Migrated tracked_materials / tmar_limits / alert_thresholds to materials.yml")
            } else {
                saveResource("materials.yml", false)
            }
        }
        return YamlConfiguration.loadConfiguration(file)
    }

    private fun validateConfiguration() {
        val cfg = config
        val mats = materialsConfig

        val redisPort = cfg.getInt("redis.port", 6379)
        val redisTimeout = cfg.getInt("redis.timeout", 10)

        if (redisPort < 1 || redisPort > 65535) {
            logger.warning("Invalid redis.port ($redisPort), using default 6379")
            cfg.set("redis.port", 6379)
        }

        if (redisTimeout < 1 || redisTimeout > 60) {
            logger.warning("Invalid redis.timeout ($redisTimeout), using default 10 seconds")
            cfg.set("redis.timeout", 10)
        }

        val trackedMaterials = mats.getStringList("tracked_materials")
        if (trackedMaterials.isEmpty()) {
            logger.warning("No tracked_materials configured in materials.yml! Using defaults.")
            mats.set("tracked_materials", listOf(
                "DIAMOND_BLOCK", "NETHERITE_INGOT", "BEACON",
                "ENCHANTED_GOLDEN_APPLE", "SHULKER_BOX", "ELYTRA", "NETHER_STAR"
            ))
        }

        val tmarSection = mats.getConfigurationSection("tmar_limits")
        if (tmarSection == null) {
            logger.warning("No tmar_limits configured in materials.yml! Rate limiting disabled.")
        } else {
            tmarSection.getKeys(false).forEach { material ->
                val limit = tmarSection.getInt(material)
                if (limit < 1) {
                    logger.warning("Invalid TMAR limit for $material ($limit), disabling limit for this material")
                }
            }
        }

        val shadowMode = cfg.getBoolean("shadow_mode", true)
        val autoDelete = cfg.getBoolean("auto_delete_dupes", false)

        if (shadowMode) {
            logger.info("Running in SHADOW MODE - suspects will be tracked, not banned")
        }
        if (autoDelete) {
            logger.info("AUTO-DELETE enabled - detected dupes will be removed automatically")
        } else {
            logger.info("AUTO-DELETE disabled - dupes will only be logged")
        }
    }

    private fun initializeChainOfCustody() {
        val enableLedger = config.getBoolean("ledger.enabled", false)
        if (!enableLedger) {
            logger.info("Chain of Custody v2 is disabled (set ledger.enabled: true to enable)")
            return
        }

        logger.info("Initializing Chain of Custody v2 (Ledger + Proof of Witness)...")

        try {
            val trackedMaterials = materialsConfig.getStringList("tracked_materials")
                .mapNotNull { name ->
                    try {
                        Material.valueOf(name.uppercase())
                    } catch (e: IllegalArgumentException) {
                        logger.warning("Invalid material in tracked_materials: $name")
                        null
                    }
                }.toSet()

            val tmarLimits = mutableMapOf<Material, Int>()
            materialsConfig.getConfigurationSection("tmar_limits")?.let { section ->
                section.getKeys(false).forEach { key ->
                    try {
                        val material = Material.valueOf(key.uppercase())
                        val limit = section.getInt(key)
                        if (limit > 0) {
                            tmarLimits[material] = limit
                        }
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
                    ledgerStorage = ledgerStorage,
                    trackedMaterials = trackedMaterials,
                    tmarLimits = tmarLimits,
                    witnessRadius = witnessRadius,
                    verifiedThreshold = verifiedThreshold,
                    suspiciousSoloRatio = suspiciousSoloRatio,
                    reconciliationCooldownMs = reconciliationCooldownMs,
                    alertThresholds = alertThresholds,
                    defaultAlertThreshold = defaultAlertThreshold,
                    logger = logger
                )
            }

            chainOfCustody?.onDupeAlert { alert ->
                val message = buildString {
                    append("§c§l[DUPE ALERT] ")
                    append("§e${alert.playerName} ")
                    append("§7(${alert.type.name}) ")
                    append("§f${alert.material.name}: ")
                    append("§c${alert.details}")
                    if (alert.severity == com.server.antidupe.ledger.Severity.CRITICAL) {
                        append(" §4§l[CRITICAL]")
                    }
                }

                Bukkit.getOnlinePlayers()
                    .filter { it.isOp || it.hasPermission("antidupe.admin") }
                    .forEach { it.sendMessage(message) }

                logger.warning("[DUPE] ${alert.playerName}: ${alert.details}")
            }

            chainOfCustody?.let { coc ->
                adpCommand.setChainOfCustody(coc)
            }

            logger.info("✓ Chain of Custody v2 initialized with Proof of Witness")
            logger.info("  Tracking ${trackedMaterials.size} materials, ${tmarLimits.size} TMAR limits")
        } catch (e: Exception) {
            logger.severe("Failed to initialize Chain of Custody: ${e.message}")
            logger.log(java.util.logging.Level.SEVERE, "Stack trace:", e)
        }
    }

    fun getChainOfCustody(): ChainOfCustody? = chainOfCustody
}
