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

    private companion object {
        const val SHUTDOWN_DRAIN_MS = 5_000L
        const val CRASH_NOTE_MINUTES = 30L

        /** Settings `/adp reload` applies on the spot; any other changed key needs a restart. */
        val LIVE_KEYS = listOf(
            "language", "notifications", "shadow_mode", "auto_delete_dupes", "enforcement",
            "on_confirm_command", "detection.on_confirm_command", "console_log_level", "leaving_creative_mode"
        )
    }

    lateinit var pluginScope: CoroutineScope
        private set

    lateinit var materialsConfig: FileConfiguration
        private set

    lateinit var scheduler: PlatformScheduler
        private set

    private var chainOfCustody: ChainOfCustody? = null
    private var metrics: com.esmpfun.antidupe.metrics.MetricsService? = null
    private var trackedMaterialCount = 0
    private var tagStripper: com.esmpfun.antidupe.net.TagStripAdapter? = null
    private var ownershipKeys: com.esmpfun.antidupe.ledger.OwnershipKeys? = null
    private lateinit var adpCommand: AdpCommand
    @Volatile private var notifier: AlertNotifier? = null
    @Volatile private var enforcement: com.esmpfun.antidupe.enforce.EnforcementService? = null
    private var uncleanStartAt = 0L

    /** The -mc26 jar still runs on 26.3, but it follows the wrong update track there. */
    private fun warnIfNewerDownloadFits() {
        @Suppress("DEPRECATION")
        if (!description.version.endsWith("-mc26")) return
        val parts = server.bukkitVersion.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        if (major > 26 || (major == 26 && minor >= 3)) {
            logger.warning("You are using the download for Minecraft 26.0 to 26.2, but this server runs $major.$minor.")
            logger.warning("Please switch to the BetterAntiDupe jar ending in -mc263, so you get the right updates.")
        }
    }

    override fun onEnable() {
        migrateLegacyDataFolder()
        @Suppress("DEPRECATION")
        logger.info("=== BetterAntiDupe v${description.version} ===")
        logger.info("Initializing Chain of Custody...")
        checkUncleanShutdown()

        if (!dataFolderUsable()) {
            logger.severe("BetterAntiDupe cannot save anything into its own folder, so it cannot start.")
            logger.severe("The folder is: ${dataFolder.absolutePath}")
            logger.severe("Your host has made it read-only, or the disk is full. Fix that and start the server again.")
            server.pluginManager.disablePlugin(this)
            return
        }

        try {
            com.esmpfun.antidupe.util.ErrorReporter.init(logger) { metrics }
            pluginScope = CoroutineScope(
                Dispatchers.IO + SupervisorJob() +
                    com.esmpfun.antidupe.util.ErrorReporter.handler("background-task")
            )
            scheduler = PlatformScheduler(this)
            logger.info("✓ Scheduler initialized (${if (scheduler.isFolia) "Folia" else "Bukkit"} mode)")

            saveDefaultConfig()
            Messages.init(this, config.getString("language", "en") ?: "en")
            materialsConfig = loadMaterialsConfig()
            applyLogLevel()
            validateConfiguration()
            logger.info("✓ Configuration loaded")

            // Started before the risky init below so a crash in Chain of Custody or the tag
            // stripper still gets reported. The tracked-material count is not known yet, hence
            // the supplier.
            metrics = com.esmpfun.antidupe.metrics.MetricsService.start(this) { trackedMaterialCount }

            adpCommand = AdpCommand(this, pluginScope, scheduler)
            adpCommand.onReload = ::reloadSettings
            adpCommand.onTestAlert = ::sendTestAlert
            getCommand("antidupe")?.let { cmd ->
                cmd.setExecutor(adpCommand)
                cmd.tabCompleter = adpCommand
            }

            initializeChainOfCustody()
            registerJoinBaseline()
            initializeTagStripper()
            registerDuperPrevention()

            // Update checking. Its own settings live in pluginpulse.yml; an `update:` block in
            // config.yml overrides the mode and interval.
            io.github.darkstarworks.pluginpulse.PluginPulse.bootstrap(this)
            warnIfNewerDownloadFits()

            logger.info("=== BetterAntiDupe enabled successfully ===")
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to initialize BetterAntiDupe", e)
            metrics?.report("plugin-enable", e)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        logger.info("=== BetterAntiDupe shutting down ===")
        try {
            closeOpenInventories()
            metrics?.shutdown()
            metrics = null
            io.github.darkstarworks.pluginpulse.PluginPulse.shutdown(this)
            tagStripper?.let { s -> server.onlinePlayers.forEach { s.eject(it) } }
            tagStripper = null
            // Order matters: ledger writes run as coroutines, so stop the endless background
            // loops, let the outstanding writes finish, and only then close storage. Closing
            // first loses every in-flight append, in the exact window the shutdown-duper
            // protection exists to cover.
            chainOfCustody?.stopBackgroundJobs()
            drainPendingWork()
            chainOfCustody?.shutdown()
            chainOfCustody = null
            if (::pluginScope.isInitialized) pluginScope.cancel()
            runningMarker().delete()
        } catch (e: Exception) {
            logger.warning("Error during shutdown: ${e.message}")
        }
        com.esmpfun.antidupe.util.ErrorReporter.shutdown()
        logger.info("=== BetterAntiDupe disabled ===")
    }

    /** Bounded, because a wedged storage backend must not hang the whole server shutdown. */
    private fun drainPendingWork() {
        if (!::pluginScope.isInitialized) return
        val pending = pluginScope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList().orEmpty()
        if (pending.isEmpty()) return
        val finished = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(SHUTDOWN_DRAIN_MS) {
                pending.forEach { it.join() }
                true
            }
        }
        if (finished == null) {
            logger.warning(
                "Timed out after ${SHUTDOWN_DRAIN_MS}ms waiting for ${pending.size} pending ledger" +
                    " write(s); some very recent item movement may not have been recorded"
            )
        }
    }

    /**
     * Restart duper: the shutdown sequence writes player data and world data as separate steps,
     * so a stack moved between an inventory and a container in the window between them is saved
     * on one side and not the other, and exists twice on the next boot. Closing every view
     * leaves no in-flight transfer to race.
     *
     * MinecraftServer.stopServer() runs disablePlugins() ahead of both writes and while players
     * are still connected; confirmed on Paper 1.21.1, 1.21.7, 1.21.11, 26.1.2 and 26.2. Only a
     * clean stop is covered: a crash runs no plugin code, and reconciliation catches that case
     * instead (a restart dupe leaves two items carrying the same ownership UUID).
     */
    private fun closeOpenInventories() {
        if (!config.getBoolean("prevent-shutdown-dupers", true)) return
        // Copy: closeInventory mutates the roster's open views while we iterate.
        val closed = server.onlinePlayers.toList().count { player ->
            // A player with nothing open still reports their own crafting view; don't count
            // closing that as a duper-relevant close.
            val wasOpen = player.openInventory.type != org.bukkit.event.inventory.InventoryType.CRAFTING
            player.closeInventory()
            wasOpen
        }
        if (closed > 0) logger.info("[DuperPrevention] Closed $closed open inventory view(s) for shutdown")
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

        val shadow = config.getBoolean("shadow_mode", true)
        val autoDelete = config.getBoolean("auto_delete_dupes", false)
        when {
            shadow && autoDelete -> logger.warning(
                "SHADOW MODE is on, so auto_delete_dupes is being ignored; nothing will be removed." +
                    " Set shadow_mode: false to let it act."
            )
            shadow -> logger.info("Running in SHADOW MODE - suspects are recorded, nothing is removed")
            autoDelete -> logger.info(
                "AUTO-DELETE enabled - surplus items will be removed at severity " +
                    (config.getString("enforcement.min_severity", "HIGH") ?: "HIGH") + " and above"
            )
            else -> logger.info("Alert-only mode - admins are notified, nothing is removed")
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
            // Every dyed variant is tracked as well, not just SHULKER_BOX. materials.yml says so;
            // they are the primary laundering vector and must never fall out of the ledger.
            val allShulkerBoxes = Material.values().filter {
                it.name.endsWith("SHULKER_BOX") && !it.name.startsWith("LEGACY_")
            }
            val trackedMaterials = (configuredMaterials + allShulkerBoxes).toSet()
            trackedMaterialCount = trackedMaterials.size

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
            val reconcileOnPickup = config.getBoolean("ledger.reconciliation.on_pickup", true)
            val reconcileOnClose = config.getBoolean("ledger.reconciliation.on_inventory_close", true)
            val sweepIntervalMinutes = config.getInt("ledger.reconciliation.interval_minutes", 15)
            val sweepStaggerMs = config.getLong("ledger.reconciliation.stagger_ms", 250L)
            val flagPatterns = config.getBoolean("ledger.witness.flag_suspicious_patterns", true)
            val hopperMode = parseHopperMode()
            val blockCollect = config.getBoolean("block_collect_to_cursor", false)

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

            // Detection and the client-side stripper must agree on this key, so resolve the
            // configured one once, here.
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
                    ownershipKeys = keys,
                    reconcileOnPickup = reconcileOnPickup,
                    reconcileOnInventoryClose = reconcileOnClose,
                    flagSuspiciousPatterns = flagPatterns,
                    hopperMode = hopperMode,
                    blockCollectToCursor = blockCollect,
                    sweepIntervalMinutes = sweepIntervalMinutes,
                    sweepStaggerMs = sweepStaggerMs
                )
            }

            notifier = AlertNotifier(config.getConfigurationSection("notifications"), pluginScope, logger)
            val coc = chainOfCustody ?: throw IllegalStateException("Chain of Custody was not created")
            val enforcement = com.esmpfun.antidupe.enforce.EnforcementService(
                settings = enforcementSettings(),
                ownershipManager = coc.ownershipManager,
                scheduler = scheduler,
                scope = pluginScope,
                logger = logger
            )
            enforcement.setChainOfCustody(coc)
            this.enforcement = enforcement
            adpCommand.setEnforcement(enforcement)

            chainOfCustody?.onDupeAlert { raw ->
                // A crash rolls the world back past pickups and container moves the ledger already
                // saw, which produces both of these alerts by itself. Say so rather than suppress.
                val crashSensitive = raw.messageKey == "alerts.entity-dupe" || raw.messageKey == "alerts.world-stock"
                val alert = if (crashSensitive && recentlyCrashed())
                    raw.copy(afterUncleanShutdown = true) else raw
                com.esmpfun.antidupe.metrics.DetectionCounters
                    .recordDetection(alert.type.name, alert.severity.name, alert.material.name)
                notifier?.handle(alert)
                this.enforcement?.handle(alert)

                val message = formatAlert(alert)
                // Alerts can be emitted from reconciliation coroutines; hop to the main (global
                // region) thread before touching the online-player roster.
                scheduler.runMain(Runnable { broadcastAlert(alert, message) })

                logger.warning("[DUPE] ${alert.playerName}: ${alert.details}" +
                    if (alert.afterUncleanShutdown) " (the server did not shut down cleanly shortly before this)" else "")
            }

            chainOfCustody?.let {
                adpCommand.setChainOfCustody(it)
                it.reconciliationEngine.healLogLevel = healLogLevelFor()
                it.reconciliationEngine.alertOnLeavingCreative = alertOnLeavingCreative()
            }

            logger.info("✓ Chain of Custody initialized")
            logger.info("  Tracking ${trackedMaterials.size} materials, ${tmarLimits.size} TMAR limits")
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to initialize Chain of Custody", e)
            logger.severe("Item tracking is off for this run: nothing is being recorded and no dupe alerts will fire.")
            logger.severe("The protections that block duping machines are unaffected and still running.")
            logger.severe("The reason is in the lines above. Fix it and restart to get tracking back.")
            metrics?.report("chain-of-custody-init", e)
        }
    }

    fun getChainOfCustody(): ChainOfCustody? = chainOfCustody

    /** In-game text comes from messages.yml; console logs stay English. */
    private fun formatAlert(alert: com.esmpfun.antidupe.ledger.DupeAlert): String {
        val details = if (alert.messageKey.isNotEmpty())
            Messages.msg(alert.messageKey, alert.placeholders) else alert.details
        return Messages.msg("alerts.broadcast",
            "player" to alert.playerName,
            "type" to alert.type.name,
            "material" to alert.material.name,
            "details" to details
        ) + (if (alert.afterUncleanShutdown) Messages.msg("alerts.after-crash-suffix") else "") +
            if (alert.severity == com.esmpfun.antidupe.ledger.Severity.CRITICAL)
                Messages.msg("alerts.critical-suffix") else ""
    }

    /**
     * antidupe.admin inherits antidupe.alerts, while the ledger commands are gated separately,
     * so an alerts-only mod gets the plain line without buttons they could not use.
     */
    private fun broadcastAlert(alert: com.esmpfun.antidupe.ledger.DupeAlert, message: String) {
        val name = alert.playerName
        val withButtons = com.esmpfun.antidupe.util.Chat.line()
            .text(message).text(" ")
            .clickRunCommand(Messages.msg("alerts.buttons.history"), "/adp ledger history $name",
                hover = Messages.msg("alerts.buttons.history-hover", "player" to name))
            .text(" ")
            .clickRunCommand(Messages.msg("alerts.buttons.stash"), "/adp ledger stash $name",
                hover = Messages.msg("alerts.buttons.stash-hover", "player" to name))
            .build()
        Bukkit.getOnlinePlayers()
            .filter { it.hasPermission("antidupe.alerts") }
            .forEach { player ->
                if (player.hasPermission("antidupe.ledger")) {
                    with(com.esmpfun.antidupe.util.Chat) { player.sendChat(withButtons) }
                } else {
                    player.sendMessage(message)
                }
            }
    }

    /** Shown to the sender and pushed to every enabled channel; never counted or enforced. */
    fun sendTestAlert(sender: org.bukkit.command.CommandSender) {
        val alert = com.esmpfun.antidupe.ledger.DupeAlert(
            type = com.esmpfun.antidupe.ledger.AlertType.BALANCE_DISCREPANCY,
            player = (sender as? org.bukkit.entity.Player)?.uniqueId ?: java.util.UUID(0L, 0L),
            playerName = sender.name,
            material = Material.DIAMOND_BLOCK,
            details = "Test alert sent with /adp test alert. Nothing happened and nobody is suspected.",
            severity = com.esmpfun.antidupe.ledger.Severity.HIGH,
            timestamp = System.currentTimeMillis(),
            messageKey = "alerts.test"
        )
        sender.sendMessage(formatAlert(alert))
        val n = notifier
        val targets = n?.targets.orEmpty()
        if (n == null || targets.isEmpty()) {
            sender.sendMessage(Messages.msg("commands.test.no-channels"))
            return
        }
        sender.sendMessage(Messages.msg("commands.test.sending", "targets" to targets.joinToString(", ")))
        n.sendTest(alert) { target, failure ->
            val line = if (failure == null) Messages.msg("commands.test.delivered", "target" to target)
                else Messages.msg("commands.test.failed", "target" to target, "reason" to failure)
            scheduler.runMain(Runnable { sender.sendMessage(line) })
        }
    }

    /**
     * Re-reads config.yml, messages.yml and the alert and removal settings. Returns the
     * changed settings that only take effect after a restart.
     */
    fun reloadSettings(): List<String> {
        val before = restartOnlySnapshot()
        val materials = File(dataFolder, "materials.yml")
        val materialsBefore = if (materials.exists()) materials.readText() else null

        reloadConfig()
        Messages.init(this, config.getString("language", "en") ?: "en")
        applyLogLevel()
        notifier = AlertNotifier(config.getConfigurationSection("notifications"), pluginScope, logger)
        enforcement?.settings = enforcementSettings()
        chainOfCustody?.reconciliationEngine?.healLogLevel = healLogLevelFor()
        chainOfCustody?.reconciliationEngine?.alertOnLeavingCreative = alertOnLeavingCreative()

        val after = restartOnlySnapshot()
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }.toMutableList()
        val materialsAfter = if (materials.exists()) materials.readText() else null
        if (materialsAfter != materialsBefore) changed += "materials.yml"
        logger.info("Reloaded settings" +
            if (changed.isNotEmpty()) "; restart needed for: ${changed.joinToString()}" else "")
        return changed
    }

    private fun restartOnlySnapshot(): Map<String, String> =
        config.getValues(true)
            .filterValues { it !is org.bukkit.configuration.ConfigurationSection }
            .filterKeys { key -> LIVE_KEYS.none { key == it || key.startsWith("$it.") } }
            .mapValues { it.value.toString() }

    /**
     * Config, messages, the markers and the database all live here, and an unwritable folder
     * otherwise surfaces as whichever of them happens to be touched first.
     */
    private fun dataFolderUsable(): Boolean = try {
        dataFolder.mkdirs()
        val probe = File(dataFolder, ".write-test")
        probe.writeText("")
        probe.delete()
        true
    } catch (e: Exception) {
        false
    }

    private fun runningMarker() = File(dataFolder, "server-running")

    /** The marker is deleted on a clean stop, so finding it at startup means the last run crashed. */
    private fun checkUncleanShutdown() {
        try {
            dataFolder.mkdirs()
            val marker = runningMarker()
            if (marker.exists()) {
                uncleanStartAt = System.currentTimeMillis()
                logger.warning("The server did not shut down cleanly last time. For the next " +
                    "$CRASH_NOTE_MINUTES minutes, alerts about an item being picked up twice are " +
                    "marked as possibly caused by that.")
            }
            marker.writeText("Present while the server runs. Still here at startup means the last stop was not clean.")
        } catch (e: Exception) {
            logger.warning("Could not write the running marker: ${e.message}")
        }
    }

    private fun recentlyCrashed(): Boolean =
        uncleanStartAt > 0 && System.currentTimeMillis() - uncleanStartAt < CRASH_NOTE_MINUTES * 60_000L

    /** Covers droppers and the crafter too, not only hoppers. */
    private fun parseHopperMode(): com.esmpfun.antidupe.ledger.LedgerEventHandler.HopperMode {
        val raw = config.getString("hopper_tracking", "LOG") ?: "LOG"
        return try {
            com.esmpfun.antidupe.ledger.LedgerEventHandler.HopperMode.valueOf(raw.uppercase())
        } catch (e: IllegalArgumentException) {
            logger.warning("Unknown hopper_tracking '$raw', using LOG. Valid: OFF, LOG, BLOCK")
            com.esmpfun.antidupe.ledger.LedgerEventHandler.HopperMode.LOG
        }
    }

    private fun alertOnLeavingCreative(): Boolean =
        when (val raw = (config.getString("leaving_creative_mode", "RECORD") ?: "RECORD").uppercase()) {
            "RECORD" -> false
            "ALERT" -> true
            else -> {
                logger.warning("Unknown leaving_creative_mode '$raw', using RECORD. Valid: RECORD, ALERT")
                false
            }
        }

    private fun enforcementSettings(): com.esmpfun.antidupe.enforce.EnforcementService.Settings {
        val rawSeverity = config.getString("enforcement.min_severity", "HIGH") ?: "HIGH"
        val minSeverity = try {
            com.esmpfun.antidupe.ledger.Severity.valueOf(rawSeverity.uppercase())
        } catch (e: IllegalArgumentException) {
            logger.warning("Unknown enforcement.min_severity '$rawSeverity', using HIGH. Valid: LOW, MEDIUM, HIGH, CRITICAL")
            com.esmpfun.antidupe.ledger.Severity.HIGH
        }
        return com.esmpfun.antidupe.enforce.EnforcementService.Settings(
            shadowMode = config.getBoolean("shadow_mode", true),
            autoDelete = config.getBoolean("auto_delete_dupes", false),
            minSeverity = minSeverity,
            maxItemsPerAction = config.getInt("enforcement.max_items_per_action", 0),
            notifyPlayer = config.getBoolean("enforcement.notify_player", true)
        )
    }

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
     * Wrapped so a mapping mismatch on an unexpected server build disables the concealment
     * feature cleanly instead of breaking the plugin.
     */
    private fun initializeTagStripper() {
        if (!config.getBoolean("hide_tag_from_clients", true)) return
        try {
            // Resolved in initializeChainOfCustody, which must have run first. Legacy keys from
            // a rename are concealed too, so un-migrated items don't leak.
            val keys = ownershipKeys
            val namespace = keys?.primary?.namespace ?: name.lowercase()
            val qualified = keys?.allQualified ?: listOf("$namespace:adp_owner")

            val stripAll = config.getBoolean("strip_all_custom_data", false)
            val whitelist = config.getStringList("strip_whitelist")
            if (stripAll) {
                logger.info("Strict strip mode: ALL custom item data is hidden from clients" +
                    if (whitelist.isNotEmpty()) " (except: ${whitelist.joinToString()})" else "")
            }

            // null means this server build is unsupported, so the feature simply stays off.
            val stripper = com.esmpfun.antidupe.net.TagStripAdapters.load(
                this, logger, namespace, qualified, stripAll, whitelist
            ) ?: return
            tagStripper = stripper

            // Exempt players are simply never injected. Evaluated at join, so a permission
            // change only applies on their next login.
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
            logger.log(Level.WARNING, "Tag stripper unavailable on this server build - feature disabled", e)
            metrics?.report("tag-stripper-init", e)
            tagStripper = null
        }
    }

    /** CRITICAL and ERROR both land on SEVERE; java.util.logging has no separate tier. */
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
            logger.info("Migration complete - the old folder was kept as a backup.")
        } catch (e: Exception) {
            logger.severe("Legacy data-folder migration failed: ${e.message} - migrate manually and restart.")
        }
    }
}
