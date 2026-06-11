package com.server.antidupe.commands

import com.server.antidupe.ledger.ChainOfCustody
import com.server.antidupe.platform.PlatformScheduler
import com.server.antidupe.util.Chat
import com.server.antidupe.util.Chat.sendChat
import com.server.antidupe.util.Messages
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

/**
 * Single-entry `/adp` command for Chain of Custody administration. All subcommands
 * live under `/adp ledger ...`. Every displayed string is looked up through
 * [Messages] (messages.yml) so the plugin is translatable.
 */
class AdpCommand(
    private val plugin: JavaPlugin,
    private val scope: CoroutineScope,
    private val scheduler: PlatformScheduler
) : CommandExecutor, TabCompleter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    private var chainOfCustody: ChainOfCustody? = null

    fun setChainOfCustody(coc: ChainOfCustody) { this.chainOfCustody = coc }

    /**
     * Resolve a player name to a UUID without blocking the calling thread on a Mojang
     * lookup. Online roster first, cached offline players second, async lookup as last resort.
     */
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
            "help", "?" -> showHelp(sender)
            else -> sender.sendMessage(Messages.msg("commands.unknown-subcommand"))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val subs = mutableListOf<String>()
            if (sender.hasPermission("antidupe.admin") && chainOfCustody != null) subs.add("ledger")
            subs.add("help")
            return subs.filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size >= 2 && args[0].lowercase() in listOf("ledger", "coc", "chain")) {
            if (!sender.hasPermission("antidupe.admin") || chainOfCustody == null) return emptyList()
            return when (args.size) {
                2 -> listOf("status", "balance", "history", "witness", "suspects", "stash",
                            "reconcile", "trust", "confirm", "clear", "verify", "help")
                    .filter { it.startsWith(args[1].lowercase()) }
                3 -> when (args[1].lowercase()) {
                    "balance", "history", "witness", "reconcile", "trust", "stash", "confirm", "clear" ->
                        Bukkit.getOnlinePlayers().map { it.name }
                            .filter { it.lowercase().startsWith(args[2].lowercase()) }
                    else -> emptyList()
                }
                else -> emptyList()
            }
        }
        return emptyList()
    }

    private fun showHelp(sender: CommandSender) {
        sender.sendMessage(Messages.msg("commands.help.header"))
        sender.sendMessage(Messages.msg("commands.help.title"))
        sender.sendMessage("")
        if (sender.hasPermission("antidupe.admin") && chainOfCustody != null) {
            Messages.list("commands.help.admin-lines").forEach { sender.sendMessage(it) }
        }
        sender.sendMessage(Messages.msg("commands.help.user-line"))
        sender.sendMessage(Messages.msg("commands.help.header"))
    }

    private fun usage(sender: CommandSender, usage: String) =
        sender.sendMessage(Messages.msg("commands.usage", "usage" to usage))

    private fun handleLedger(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("antidupe.admin")) {
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
            "trust" -> if (args.size < 2) usage(sender, "/adp ledger trust <player>")
                       else ledgerTrust(sender, coc, args[1])
            "verify" -> ledgerVerify(sender, coc)
            "help" -> showLedgerHelp(sender)
            else -> sender.sendMessage(Messages.msg("commands.unknown-ledger-subcommand"))
        }
    }

    private fun showLedgerHelp(sender: CommandSender) {
        sender.sendMessage(Messages.list("commands.ledger-help").joinToString("\n"))
    }

    private fun ledgerStatus(sender: CommandSender, coc: ChainOfCustody) {
        scope.launch {
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
            scope.launch {
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
            scope.launch {
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

    /**
     * Show the player's recent CONTAINER_PUT / ENTITY_PUT / FRAME_PUT entries with clickable
     * coordinates that fire `/execute in <dimension> run tp @s x y z` when the admin clicks.
     */
    private fun ledgerStash(sender: CommandSender, coc: ChainOfCustody, playerName: String) {
        resolvePlayer(playerName, sender) { uuid ->
            scope.launch {
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
                                .clickToTp(display, Chat.worldKeyOf(world), x, y, z,
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

    /** Parses "world,x,y,z" container-location strings produced by LedgerMetadata.withContainer. */
    private fun parseContainerCoords(loc: String?): Coords? {
        if (loc.isNullOrBlank()) return null
        val parts = loc.split(",")
        if (parts.size != 4) return null
        return try {
            Coords(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
        } catch (e: NumberFormatException) { null }
    }

    /** Falls back to the entry's own world/x/y/z if there's no containerLocation. */
    private fun parseEntryCoords(entry: com.server.antidupe.ledger.LedgerEntry): Coords? {
        val w = entry.metadata.worldName ?: return null
        val x = entry.metadata.x ?: return null
        val y = entry.metadata.y ?: return null
        val z = entry.metadata.z ?: return null
        return Coords(w, x.toInt(), y.toInt(), z.toInt())
    }

    private data class Coords(val world: String, val x: Int, val y: Int, val z: Int)

    /**
     * Admin verdict on a flagged player. `confirm` pins suspicion high (future hits trip easily)
     * and runs the configured punishment command if set; `clear` marks it a false positive and
     * resets the player's suspicion and suspect entry.
     */
    private fun ledgerVerdict(sender: CommandSender, coc: ChainOfCustody, playerName: String, confirm: Boolean) {
        resolvePlayer(playerName, sender) { uuid ->
            if (confirm) {
                coc.confirmSuspect(uuid)
                sender.sendMessage(Messages.msg("commands.verdict.confirmed", "player" to playerName))
                // Optional configurable punishment hook: detection.on_confirm_command, with {player}.
                val cmd = plugin.config.getString("detection.on_confirm_command", "")?.trim().orEmpty()
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
        scope.launch {
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
