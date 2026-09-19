package com.esmpfun.antidupe.commands

import com.esmpfun.antidupe.enforce.EnforcementService
import com.esmpfun.antidupe.ledger.ChainOfCustody
import com.esmpfun.antidupe.platform.PlatformScheduler
import com.esmpfun.antidupe.util.Chat
import com.esmpfun.antidupe.util.Chat.sendChat
import com.esmpfun.antidupe.util.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

class AdpCommand(
    private val plugin: JavaPlugin,
    private val scope: CoroutineScope,
    private val scheduler: PlatformScheduler
) : CommandExecutor, TabCompleter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    private var chainOfCustody: ChainOfCustody? = null

    private var enforcement: EnforcementService? = null
    var onReload: (() -> List<String>)? = null
    var onTestAlert: ((CommandSender) -> Unit)? = null

    fun setChainOfCustody(coc: ChainOfCustody) { this.chainOfCustody = coc }
    fun setEnforcement(service: EnforcementService) { this.enforcement = service }

    private fun requireAdmin(sender: CommandSender): Boolean {
        if (sender.hasPermission("antidupe.admin")) return true
        sender.sendMessage(Messages.msg("commands.no-permission-admin"))
        return false
    }

    /** getOfflinePlayer can hit Mojang, so an unknown name is resolved off the calling thread. */
    private fun resolvePlayer(name: String, sender: CommandSender, onResolved: (UUID) -> Unit) {
        Bukkit.getPlayerExact(name)?.let { return onResolved(it.uniqueId) }
        sender.sendMessage(Messages.msg("commands.player-lookup", "player" to name))
        scheduler.runAsync(Runnable {
            @Suppress("DEPRECATION")
            val offline = Bukkit.getOfflinePlayer(name)
            scheduler.runMain(Runnable {
                if (offline.hasPlayedBefore() || offline.isOnline) onResolved(offline.uniqueId)
                else sender.sendMessage(Messages.msg("commands.unknown-player", "player" to name))
            })
        })
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) { showHelp(sender); return true }
        when (args[0].lowercase()) {
            "ledger", "coc", "chain" -> handleLedger(sender, args.drop(1).toTypedArray())
            "update" -> {
                // Checked here as well as in PluginPulse: if the library ever stops enforcing
                // its own declared permission, /adp update download becomes a public command.
                if (!sender.hasPermission("antidupe.admin")) {
                    sender.sendMessage(Messages.msg("commands.no-permission-update"))
                } else {
                    io.github.darkstarworks.pluginpulse.PluginPulse.handleUpdateCommand(
                        plugin, sender, args.copyOfRange(1, args.size))
                }
            }
            "reload" -> if (requireAdmin(sender)) reload(sender)
            "test" -> if (requireAdmin(sender)) {
                if (args.size < 2 || args[1].equals("alert", ignoreCase = true)) onTestAlert?.invoke(sender)
                else usage(sender, "/adp test alert")
            }
            "help", "?" -> showHelp(sender)
            else -> sender.sendMessage(Messages.msg("commands.unknown-subcommand"))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val subs = mutableListOf<String>()
            if (sender.hasPermission("antidupe.ledger") && chainOfCustody != null) subs.add("ledger")
            if (sender.hasPermission("antidupe.admin")) subs.addAll(listOf("reload", "test", "update"))
            subs.add("help")
            return subs.filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2 && args[0].lowercase() == "test" && sender.hasPermission("antidupe.admin")) {
            return listOf("alert").filter { it.startsWith(args[1].lowercase()) }
        }
        if (args.size >= 2 && args[0].lowercase() == "update") {
            if (!sender.hasPermission("antidupe.admin")) return emptyList()
            if (args.size == 2) return listOf("check", "download", "restore", "status")
                .filter { it.startsWith(args[1].lowercase()) }
            return emptyList()
        }
        if (args.size >= 2 && args[0].lowercase() in listOf("ledger", "coc", "chain")) {
            if (!sender.hasPermission("antidupe.ledger") || chainOfCustody == null) return emptyList()
            return when (args.size) {
                2 -> listOf("status", "balance", "history", "witness", "suspects", "stash",
                            "reconcile", "remove", "trust", "confirm", "clear", "verify", "help")
                    .filter { it.startsWith(args[1].lowercase()) }
                3 -> when (args[1].lowercase()) {
                    // reconcile needs the player online; the rest accept offline names too.
                    "reconcile", "remove" -> onlineNames(args[2])
                    "balance", "history", "witness", "trust", "stash", "confirm", "clear" ->
                        suspectAndOnlineNames(args[2])
                    else -> emptyList()
                }
                4 -> if (args[1].equals("remove", ignoreCase = true))
                    listOf("confirm").filter { it.startsWith(args[3].lowercase()) } else emptyList()
                else -> emptyList()
            }
        }
        return emptyList()
    }

    private fun onlineNames(prefix: String): List<String> =
        Bukkit.getOnlinePlayers().map { it.name }
            .filter { it.lowercase().startsWith(prefix.lowercase()) }

    /** Suspects are listed first so the names an admin is actually after are at the top. */
    private fun suspectAndOnlineNames(prefix: String): List<String> {
        val lower = prefix.lowercase()
        val suspects = chainOfCustody?.getSuspects()?.map { it.playerName }.orEmpty()
        return (suspects + Bukkit.getOnlinePlayers().map { it.name })
            .distinct()
            .filter { it.lowercase().startsWith(lower) }
    }

    private fun showHelp(sender: CommandSender) {
        sender.sendMessage(Messages.msg("commands.help.header"))
        sender.sendMessage(Messages.msg("commands.help.title"))
        sender.sendMessage("")
        if (sender.hasPermission("antidupe.ledger") && chainOfCustody != null) {
            Messages.list("commands.help.admin-lines").forEach { sender.sendMessage(it) }
        }
        if (sender.hasPermission("antidupe.admin")) {
            Messages.list("commands.help.admin-only-lines").forEach { sender.sendMessage(it) }
        }
        sender.sendMessage(Messages.msg("commands.help.user-line"))
        sender.sendMessage(Messages.msg("commands.help.header"))
    }

    private fun usage(sender: CommandSender, usage: String) =
        sender.sendMessage(Messages.msg("commands.usage", "usage" to usage))

    private fun handleLedger(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("antidupe.ledger")) {
            sender.sendMessage(Messages.msg("commands.no-permission")); return
        }
        val coc = chainOfCustody ?: run {
            sender.sendMessage(Messages.msg("commands.not-initialized")); return
        }
        if (args.isEmpty()) { showLedgerHelp(sender); return }
        when (args[0].lowercase()) {
            "status" -> ledgerStatus(sender, coc)
            "balance" -> if (args.size < 2) usage(sender, "/adp ledger balance <player>")
                         else ledgerBalance(sender, coc, args[1])
            "history" -> if (args.size < 2) usage(sender, "/adp ledger history <player>")
                         else ledgerHistory(sender, coc, args[1])
            "witness" -> if (args.size < 2) usage(sender, "/adp ledger witness <player>")
                         else ledgerWitness(sender, coc, args[1])
            "suspects" -> ledgerSuspects(sender, coc)
            "stash" -> if (args.size < 2) usage(sender, "/adp ledger stash <player>")
                       else ledgerStash(sender, coc, args[1])
            "confirm" -> if (args.size < 2) usage(sender, "/adp ledger confirm <player>")
                         else ledgerVerdict(sender, coc, args[1], confirm = true)
            "clear" -> if (args.size < 2) usage(sender, "/adp ledger clear <player>")
                       else ledgerVerdict(sender, coc, args[1], confirm = false)
            "reconcile" -> if (args.size < 2) usage(sender, "/adp ledger reconcile <player>")
                           else ledgerReconcile(sender, coc, args[1])
            "remove" -> if (args.size < 2) usage(sender, "/adp ledger remove <player>")
                        else ledgerRemove(sender, coc, args[1], args.getOrNull(2).equals("confirm", ignoreCase = true))
            "trust" -> if (args.size < 2) usage(sender, "/adp ledger trust <player>")
                       else ledgerTrust(sender, coc, args[1])
            "verify" -> ledgerVerify(sender, coc)
            // Internal target of the clickable stash coordinates; see Chat.clickRunCommand.
            "tp" -> if (args.size < 5) usage(sender, "/adp ledger tp <world> <x> <y> <z>")
                    else ledgerTp(sender, args[1], args[2], args[3], args[4])
            "help" -> showLedgerHelp(sender)
            else -> sender.sendMessage(Messages.msg("commands.unknown-ledger-subcommand"))
        }
    }

    /**
     * Reads run off the main thread, so a storage failure would otherwise leave the sender with
     * no reply at all and the stack trace only in the console.
     */
    private fun launchReplying(sender: CommandSender, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.esmpfun.antidupe.util.ErrorReporter.report("command", e)
                scheduler.runMain(Runnable { sender.sendMessage(Messages.msg("commands.storage-unavailable")) })
            }
        }
    }

    private fun showLedgerHelp(sender: CommandSender) {
        sender.sendMessage(Messages.list("commands.ledger-help").joinToString("\n"))
    }

    private fun ledgerStatus(sender: CommandSender, coc: ChainOfCustody) {
        launchReplying(sender) {
            val stats = coc.getSystemStats()
            val tip = coc.getChainTip()
            scheduler.runMain(Runnable {
                sender.sendMessage(buildString {
                    append(Messages.msg("commands.status.header")).append("\n")
                    append(Messages.msg("commands.status.tip",
                        "tip" to (tip?.lastEntryId?.toString()?.take(8) ?: "EMPTY"))).append("\n")
                    append(Messages.msg("commands.status.hash",
                        "hash" to (tip?.lastHash?.take(16) ?: "N/A"))).append("\n")
                    append(Messages.msg("commands.status.suspects", "count" to stats.activeSuspects))
                    if (stats.suspectNames.isNotEmpty()) {
                        append("\n").append(Messages.msg("commands.status.suspect-names",
                            "names" to stats.suspectNames.joinToString(", ")))
                    }
                })
            })
        }
    }

    private fun ledgerBalance(sender: CommandSender, coc: ChainOfCustody, playerName: String) {
        resolvePlayer(playerName, sender) { uuid ->
            launchReplying(sender) {
                val balances = coc.getAllBalances(uuid)
                scheduler.runMain(Runnable {
                    if (balances.isEmpty()) {
                        sender.sendMessage(Messages.msg("commands.balance.none", "player" to playerName)); return@Runnable
                    }
                    sender.sendMessage(Messages.msg("commands.balance.header", "player" to playerName))
                    balances.entries.sortedByDescending { it.value }.forEach { (mat, count) ->
                        val key = if (count > 0) "commands.balance.line-positive" else "commands.balance.line-negative"
                        sender.sendMessage(Messages.msg(key, "material" to mat.name, "count" to count))
                    }
                })
            }
        }
    }

    private fun ledgerHistory(sender: CommandSender, coc: ChainOfCustody, playerName: String) {
        resolvePlayer(playerName, sender) { uuid ->
            launchReplying(sender) {
                val history = coc.getPlayerHistory(uuid, limit = 15)
                scheduler.runMain(Runnable {
                    if (history.isEmpty()) {
                        sender.sendMessage(Messages.msg("commands.history.none", "player" to playerName)); return@Runnable
                    }
                    sender.sendMessage(Messages.msg("commands.history.header", "player" to playerName))
                    history.forEach { entry ->
                        val time = dateFormat.format(Date(entry.timestamp))
                        val qty = if (entry.quantity >= 0)
                            Messages.msg("commands.history.qty-gain", "n" to entry.quantity)
                        else Messages.msg("commands.history.qty-loss", "n" to entry.quantity)
                        val witness = entry.metadata.witnessCount
                            ?.let { Messages.msg("commands.history.witness-suffix", "count" to it) } ?: ""
                        sender.sendMessage(Messages.msg("commands.history.line",
                            "time" to time, "action" to entry.action.name, "qty" to qty,
                            "material" to entry.material.name, "witness" to witness))
                    }
                })
            }
        }
    }

    private fun ledgerWitness(sender: CommandSender, coc: ChainOfCustody, playerName: String) {
        resolvePlayer(playerName, sender) { uuid ->
            val stats = coc.getWitnessStats(uuid)
            sender.sendMessage(buildString {
                append(Messages.msg("commands.witness.header", "player" to playerName)).append("\n")
                append(Messages.msg("commands.witness.total", "count" to stats.totalActions)).append("\n")
                append(Messages.msg("commands.witness.witnessed",
                    "count" to stats.witnessedActions, "percent" to (stats.witnessRatio * 100).toInt())).append("\n")
                append(Messages.msg("commands.witness.verified", "count" to stats.verifiedActions)).append("\n")
                append(Messages.msg("commands.witness.solo", "count" to stats.soloActions)).append("\n")
                append(Messages.msg("commands.witness.trust",
                    "score" to String.format("%.1f", stats.trustScore))).append("\n")
                if (stats.isSuspicious) append(Messages.msg("commands.witness.suspicious", "reason" to stats.suspicionReason))
                else append(Messages.msg("commands.witness.ok"))
            })
        }
    }

    private fun ledgerSuspects(sender: CommandSender, coc: ChainOfCustody) {
        val suspects = coc.getSuspects()
        if (suspects.isEmpty()) { sender.sendMessage(Messages.msg("commands.suspects.none")); return }
        sender.sendMessage(Messages.msg("commands.suspects.header", "count" to suspects.size))
        sender.sendMessage(Messages.msg("commands.suspects.legend"))
        suspects.sortedByDescending { it.violationCount }.forEach { s ->
            val top = s.getTotalExcess().maxByOrNull { it.value }
            val topSuffix = top?.let {
                Messages.msg("commands.suspects.entry-top-material",
                    "material" to it.key.name, "excess" to it.value)
            } ?: ""
            sender.sendMessage(Messages.msg("commands.suspects.entry",
                "player" to s.playerName, "violations" to s.violationCount) + topSuffix)
        }
        sender.sendMessage(Messages.msg("commands.suspects.hint"))
    }

    private fun ledgerStash(sender: CommandSender, coc: ChainOfCustody, playerName: String) {
        resolvePlayer(playerName, sender) { uuid ->
            launchReplying(sender) {
                val stashes = coc.getPlayerStashes(uuid, limit = 20)
                scheduler.runMain(Runnable {
                    if (stashes.isEmpty()) {
                        sender.sendMessage(Messages.msg("commands.stash.none", "player" to playerName)); return@Runnable
                    }
                    sender.sendMessage(Messages.msg("commands.stash.header", "player" to playerName))

                    for (entry in stashes) {
                        val time = dateFormat.format(Date(entry.timestamp))
                        val container = entry.metadata.containerType ?: entry.action.name
                        val coords = parseContainerCoords(entry.metadata.containerLocation)
                            ?: parseEntryCoords(entry)
                        val absQty = kotlin.math.abs(entry.quantity)

                        val prefix = Messages.msg("commands.stash.line-prefix",
                            "time" to time, "qty" to absQty,
                            "material" to entry.material.name, "container" to container)
                        if (coords != null) {
                            val (world, x, y, z) = coords
                            val display = Messages.msg("commands.stash.location",
                                "world" to world, "x" to x, "y" to y, "z" to z)
                            val msg = Chat.line()
                                .text(prefix)
                                .clickRunCommand(display, "/adp ledger tp $world $x $y $z",
                                    hover = Messages.msg("commands.stash.hover"))
                                .build()
                            sender.sendChat(msg)
                        } else {
                            sender.sendMessage(prefix + Messages.msg("commands.stash.location-unknown"))
                        }
                    }
                })
            }
        }
    }

    private fun ledgerTp(sender: CommandSender, worldName: String, xs: String, ys: String, zs: String) {
        val player = sender as? org.bukkit.entity.Player ?: run {
            sender.sendMessage(Messages.msg("commands.tp.players-only")); return
        }
        val world = Bukkit.getWorld(worldName) ?: run {
            sender.sendMessage(Messages.msg("commands.tp.world-not-found", "world" to worldName)); return
        }
        val x = xs.toDoubleOrNull(); val y = ys.toDoubleOrNull(); val z = zs.toDoubleOrNull()
        if (x == null || y == null || z == null) {
            usage(sender, "/adp ledger tp <world> <x> <y> <z>"); return
        }
        val loc = org.bukkit.Location(world, x + 0.5, y, z + 0.5, player.location.yaw, player.location.pitch)
        try {
            player.teleportAsync(loc)  // Paper only, and safe from any thread
        } catch (e: Throwable) {
            player.teleport(loc)
        }
        sender.sendMessage(Messages.msg("commands.tp.success",
            "world" to worldName, "x" to xs, "y" to ys, "z" to zs))
    }

    private fun parseContainerCoords(loc: String?): Coords? {
        if (loc.isNullOrBlank()) return null
        val parts = loc.split(",")
        if (parts.size != 4) return null
        return try {
            Coords(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
        } catch (e: NumberFormatException) { null }
    }

    private fun parseEntryCoords(entry: com.esmpfun.antidupe.ledger.LedgerEntry): Coords? {
        val w = entry.metadata.worldName ?: return null
        val x = entry.metadata.x ?: return null
        val y = entry.metadata.y ?: return null
        val z = entry.metadata.z ?: return null
        return Coords(w, x.toInt(), y.toInt(), z.toInt())
    }

    private data class Coords(val world: String, val x: Int, val y: Int, val z: Int)

    private fun ledgerVerdict(sender: CommandSender, coc: ChainOfCustody, playerName: String, confirm: Boolean) {
        resolvePlayer(playerName, sender) { uuid ->
            if (confirm) {
                coc.confirmSuspect(uuid)
                sender.sendMessage(Messages.msg("commands.verdict.confirmed", "player" to playerName))
                // The detection. prefix is where this key used to live; old configs still work.
                val cmd = (plugin.config.getString("on_confirm_command", null)
                    ?: plugin.config.getString("detection.on_confirm_command", ""))?.trim().orEmpty()
                if (cmd.isNotEmpty()) {
                    val resolved = cmd.replace("{player}", playerName)
                    scheduler.runMain(Runnable {
                        plugin.server.dispatchCommand(plugin.server.consoleSender, resolved)
                    })
                    sender.sendMessage(Messages.msg("commands.verdict.ran-command", "command" to resolved))
                }
            } else {
                coc.clearVerdict(uuid)
                sender.sendMessage(Messages.msg("commands.verdict.cleared", "player" to playerName))
            }
        }
    }

    private fun ledgerReconcile(sender: CommandSender, coc: ChainOfCustody, playerName: String) {
        val target = Bukkit.getPlayer(playerName) ?: run {
            sender.sendMessage(Messages.msg("commands.reconcile.must-be-online")); return
        }
        sender.sendMessage(Messages.msg("commands.reconcile.running", "player" to target.name))
        coc.reconcileAsync(target) { result ->
            scheduler.runMain(Runnable {
                if (result.skipped) {
                    sender.sendMessage(Messages.msg("commands.reconcile.skipped", "reason" to (result.reason ?: ""))); return@Runnable
                }
                if (result.dupeDetected) {
                    sender.sendMessage(Messages.msg("commands.reconcile.dupe-detected"))
                    result.discrepancies.forEach { d ->
                        sender.sendMessage(Messages.msg("commands.reconcile.discrepancy",
                            "material" to d.material.name, "actual" to d.actual,
                            "expected" to d.expected, "excess" to d.excess))
                    }
                    result.tmarViolations.forEach { t ->
                        sender.sendMessage(Messages.msg("commands.reconcile.tmar",
                            "material" to t.material.name, "acquired" to t.acquired,
                            "limit" to t.limit, "window" to t.windowMinutes))
                    }
                } else {
                    sender.sendMessage(Messages.msg("commands.reconcile.clean"))
                }
            })
        }
    }

    private fun reload(sender: CommandSender) {
        val changed = try {
            onReload?.invoke() ?: return
        } catch (e: Exception) {
            plugin.logger.warning("Reload failed: ${e.message}")
            sender.sendMessage(Messages.msg("commands.reload.failed", "error" to (e.message ?: e.javaClass.simpleName)))
            return
        }
        sender.sendMessage(Messages.msg("commands.reload.done"))
        if (changed.isNotEmpty()) {
            sender.sendMessage(Messages.msg("commands.reload.restart-needed", "settings" to changed.joinToString(", ")))
        }
    }

    /**
     * Without `confirm` this only lists the surplus, with a click to confirm. The confirmed run
     * counts again, so it removes what is extra at that moment, not what the preview showed.
     */
    private fun ledgerRemove(sender: CommandSender, coc: ChainOfCustody, playerName: String, confirmed: Boolean) {
        val service = enforcement ?: run {
            sender.sendMessage(Messages.msg("commands.not-initialized")); return
        }
        val target = Bukkit.getPlayer(playerName) ?: run {
            sender.sendMessage(Messages.msg("commands.reconcile.must-be-online")); return
        }
        coc.reconcileAsync(target, ignoreCooldown = true) { result ->
            val surplus = result.discrepancies.filter { it.excess > 0 }
            if (surplus.isEmpty()) {
                scheduler.runMain(Runnable {
                    sender.sendMessage(Messages.msg("commands.remove.nothing", "player" to target.name))
                })
                return@reconcileAsync
            }
            if (!confirmed) {
                scheduler.runMain(Runnable {
                    sender.sendMessage(Messages.msg("commands.remove.preview-header", "player" to target.name))
                    surplus.forEach { d ->
                        sender.sendMessage(Messages.msg("commands.remove.preview-line",
                            "excess" to d.excess, "material" to d.material.name,
                            "actual" to d.actual, "expected" to d.expected))
                    }
                    sender.sendChat(Chat.line()
                        .text(Messages.msg("commands.remove.preview-confirm"))
                        .clickRunCommand(Messages.msg("commands.remove.confirm-button"),
                            "/adp ledger remove ${target.name} confirm",
                            hover = Messages.msg("commands.remove.confirm-hover"))
                        .build())
                })
                return@reconcileAsync
            }
            scheduler.runForEntity(target, Runnable {
                val outcome = surplus.map { d ->
                    val removed = try {
                        service.removeSurplusNow(target, d.material, d.excess, sender.name)
                    } catch (e: Exception) {
                        plugin.logger.warning("[Enforce] Manual removal failed for ${target.name}: ${e.message}")
                        0
                    }
                    d to removed
                }
                scheduler.runMain(Runnable {
                    for ((d, removed) in outcome) {
                        if (removed > 0) sender.sendMessage(Messages.msg("commands.remove.removed",
                            "removed" to removed, "material" to d.material.name))
                        if (removed < d.excess) sender.sendMessage(Messages.msg("commands.remove.unreachable",
                            "amount" to (d.excess - removed), "material" to d.material.name))
                    }
                })
            })
        }
    }

    private fun ledgerTrust(sender: CommandSender, coc: ChainOfCustody, playerName: String) {
        resolvePlayer(playerName, sender) { uuid ->
            val trust = coc.getTrustScore(uuid)
            val scoreColor = when {
                trust.score >= 80 -> "§a"; trust.score >= 50 -> "§e"
                trust.score >= 20 -> "§6"; else -> "§c"
            }
            sender.sendMessage(buildString {
                append(Messages.msg("commands.trust.header", "player" to playerName)).append("\n")
                append(Messages.msg("commands.trust.score",
                    "color" to scoreColor, "score" to String.format("%.1f", trust.score))).append("\n")
                append(Messages.msg("commands.trust.verified", "count" to trust.verifiedCount)).append("\n")
                append(Messages.msg("commands.trust.corroborated", "count" to trust.corroboratedCount)).append("\n")
                append(Messages.msg("commands.trust.solo", "count" to trust.soloCount)).append("\n")
                append(Messages.msg("commands.trust.contested", "count" to trust.contestedCount))
            })
        }
    }

    private fun ledgerVerify(sender: CommandSender, coc: ChainOfCustody) {
        sender.sendMessage(Messages.msg("commands.verify.start"))
        launchReplying(sender) {
            val result = coc.verifyIntegrity()
            scheduler.runMain(Runnable {
                if (result.valid) sender.sendMessage(Messages.msg("commands.verify.ok", "count" to result.entriesVerified))
                else {
                    sender.sendMessage(Messages.msg("commands.verify.fail-header"))
                    sender.sendMessage(Messages.msg("commands.verify.fail-error", "error" to (result.error ?: "")))
                    sender.sendMessage(Messages.msg("commands.verify.fail-broken", "entry" to (result.brokenAt ?: "")))
                    sender.sendMessage(Messages.msg("commands.verify.fail-last-valid", "entry" to (result.lastValidEntry ?: "")))
                }
            })
        }
    }
}
