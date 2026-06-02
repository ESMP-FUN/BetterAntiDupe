package com.server.antidupe.commands

import com.server.antidupe.ledger.ChainOfCustody
import com.server.antidupe.platform.PlatformScheduler
import com.server.antidupe.util.Chat
import com.server.antidupe.util.Chat.sendChat
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
 * live under `/adp ledger ...`.
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
        sender.sendMessage("§7Looking up §e$name§7...")
        scheduler.runAsync(Runnable {
            @Suppress("DEPRECATION")
            val offline = Bukkit.getOfflinePlayer(name)
            scheduler.runMain(Runnable {
                if (offline.hasPlayedBefore() || offline.isOnline) onResolved(offline.uniqueId)
                else sender.sendMessage("§cUnknown player: $name")
            })
        })
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) { showHelp(sender); return true }
        when (args[0].lowercase()) {
            "ledger", "coc", "chain" -> handleLedger(sender, args.drop(1).toTypedArray())
            "help", "?" -> showHelp(sender)
            else -> sender.sendMessage("§cUnknown subcommand. Use §e/adp help§c for available commands.")
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
        sender.sendMessage("§8§m-----------------------------------------")
        sender.sendMessage("§6§lAntiDupePro Commands")
        sender.sendMessage("")
        if (sender.hasPermission("antidupe.admin") && chainOfCustody != null) {
            sender.sendMessage("§e/adp ledger status §7- System status")
            sender.sendMessage("§e/adp ledger balance <player> §7- Item balances")
            sender.sendMessage("§e/adp ledger history <player> §7- Recent ledger entries")
            sender.sendMessage("§e/adp ledger witness <player> §7- Witness stats")
            sender.sendMessage("§e/adp ledger suspects §7- List suspects")
            sender.sendMessage("§e/adp ledger stash <player> §7- Where they stashed items (clickable)")
            sender.sendMessage("§e/adp ledger reconcile <player> §7- Force reconciliation")
            sender.sendMessage("§e/adp ledger trust <player> §7- Trust score")
            sender.sendMessage("§e/adp ledger verify §7- Verify chain integrity")
        }
        sender.sendMessage("§e/adp help §7- Show this help message")
        sender.sendMessage("§8§m-----------------------------------------")
    }

    private fun handleLedger(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("antidupe.admin")) {
            sender.sendMessage("§cYou don't have permission to use ledger commands."); return
        }
        val coc = chainOfCustody ?: run {
            sender.sendMessage("§cChain of Custody is not initialized."); return
        }
        if (args.isEmpty()) { showLedgerHelp(sender); return }
        when (args[0].lowercase()) {
            "status" -> ledgerStatus(sender, coc)
            "balance" -> if (args.size < 2) sender.sendMessage("§cUsage: /adp ledger balance <player>")
                         else ledgerBalance(sender, coc, args[1])
            "history" -> if (args.size < 2) sender.sendMessage("§cUsage: /adp ledger history <player>")
                         else ledgerHistory(sender, coc, args[1])
            "witness" -> if (args.size < 2) sender.sendMessage("§cUsage: /adp ledger witness <player>")
                         else ledgerWitness(sender, coc, args[1])
            "suspects" -> ledgerSuspects(sender, coc)
            "stash" -> if (args.size < 2) sender.sendMessage("§cUsage: /adp ledger stash <player>")
                       else ledgerStash(sender, coc, args[1])
            "confirm" -> if (args.size < 2) sender.sendMessage("§cUsage: /adp ledger confirm <player>")
                         else ledgerVerdict(sender, coc, args[1], confirm = true)
            "clear" -> if (args.size < 2) sender.sendMessage("§cUsage: /adp ledger clear <player>")
                       else ledgerVerdict(sender, coc, args[1], confirm = false)
            "reconcile" -> if (args.size < 2) sender.sendMessage("§cUsage: /adp ledger reconcile <player>")
                           else ledgerReconcile(sender, coc, args[1])
            "trust" -> if (args.size < 2) sender.sendMessage("§cUsage: /adp ledger trust <player>")
                       else ledgerTrust(sender, coc, args[1])
            "verify" -> ledgerVerify(sender, coc)
            "help" -> showLedgerHelp(sender)
            else -> sender.sendMessage("§cUnknown ledger subcommand. Use §e/adp ledger help")
        }
    }

    private fun showLedgerHelp(sender: CommandSender) {
        sender.sendMessage(buildString {
            append("§6§lChain of Custody Commands\n")
            append("§e/adp ledger status §7- System status and chain tip\n")
            append("§e/adp ledger balance <player> §7- Player's item balances\n")
            append("§e/adp ledger history <player> §7- Recent ledger entries\n")
            append("§e/adp ledger witness <player> §7- Witness statistics\n")
            append("§e/adp ledger suspects §7- List current suspects\n")
            append("§e/adp ledger stash <player> §7- Where the player stashed items (clickable)\n")
            append("§e/adp ledger reconcile <player> §7- Force reconciliation\n")
            append("§e/adp ledger trust <player> §7- Trust score details\n")
            append("§e/adp ledger confirm <player> §7- Confirm a real duper (pins suspicion)\n")
            append("§e/adp ledger clear <player> §7- Mark a false positive (resets suspicion)\n")
            append("§e/adp ledger verify §7- Verify chain integrity")
        })
    }

    private fun ledgerStatus(sender: CommandSender, coc: ChainOfCustody) {
        scope.launch {
            val stats = coc.getSystemStats()
            val tip = coc.getChainTip()
            scheduler.runMain(Runnable {
                sender.sendMessage(buildString {
                    append("§6§lChain of Custody Status\n")
                    append("§7Chain Tip: §f${tip?.lastEntryId?.toString()?.take(8) ?: "EMPTY"}...\n")
                    append("§7Hash: §f${tip?.lastHash?.take(16) ?: "N/A"}...\n")
                    append("§7Active Suspects: §c${stats.activeSuspects}\n")
                    if (stats.suspectNames.isNotEmpty()) {
                        append("§7Suspect Names: §e${stats.suspectNames.joinToString(", ")}")
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
                    if (balances.isEmpty()) { sender.sendMessage("§7No tracked items for §e$playerName"); return@Runnable }
                    sender.sendMessage("§6§lBalances for $playerName")
                    balances.entries.sortedByDescending { it.value }.forEach { (mat, count) ->
                        val color = if (count > 0) "§a" else "§c"
                        sender.sendMessage("  §7${mat.name}: $color$count")
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
                    if (history.isEmpty()) { sender.sendMessage("§7No ledger entries for §e$playerName"); return@Runnable }
                    sender.sendMessage("§6§lRecent History for $playerName")
                    history.forEach { entry ->
                        val time = dateFormat.format(Date(entry.timestamp))
                        val qtyColor = if (entry.quantity >= 0) "§a+" else "§c"
                        val witnessInfo = entry.metadata.witnessCount?.let { " §8[${it}W]" } ?: ""
                        sender.sendMessage(buildString {
                            append("  §7$time §f${entry.action.name} $qtyColor${entry.quantity} §e${entry.material.name}$witnessInfo")
                        })
                    }
                })
            }
        }
    }

    private fun ledgerWitness(sender: CommandSender, coc: ChainOfCustody, playerName: String) {
        resolvePlayer(playerName, sender) { uuid ->
            val stats = coc.getWitnessStats(uuid)
            sender.sendMessage(buildString {
                append("§6§lWitness Stats for $playerName\n")
                append("§7Total Actions: §f${stats.totalActions}\n")
                append("§7Witnessed: §a${stats.witnessedActions} §7(${(stats.witnessRatio * 100).toInt()}%)\n")
                append("§7Verified (3+ witnesses): §a${stats.verifiedActions}\n")
                append("§7Solo (no witnesses): §e${stats.soloActions}\n")
                append("§7Trust Score: §f${String.format("%.1f", stats.trustScore)}/100\n")
                if (stats.isSuspicious) append("§c§lSUSPICIOUS: ${stats.suspicionReason}")
                else append("§aNo suspicious patterns detected")
            })
        }
    }

    private fun ledgerSuspects(sender: CommandSender, coc: ChainOfCustody) {
        val suspects = coc.getSuspects()
        if (suspects.isEmpty()) { sender.sendMessage("§aNo current suspects"); return }
        sender.sendMessage("§6§lCurrent Suspects (${suspects.size})")
        sender.sendMessage("§8§o(material: +N = cumulative excess of that material across all violations)")
        suspects.sortedByDescending { it.violationCount }.forEach { s ->
            val top = s.getTotalExcess().maxByOrNull { it.value }
            sender.sendMessage(buildString {
                append("  §e${s.playerName} §7- §c${s.violationCount} violations")
                if (top != null) append(" §7(${top.key.name}: +${top.value})")
            })
        }
        sender.sendMessage("§8§oUse §e/adp ledger stash <player> §8§oto see where they stashed items.")
    }

    /**
     * Show the player's recent CONTAINER_PUT / ENTITY_PUT / FRAME_PUT entries with clickable
     * coordinates that fire `/execute in <world> run tp @s x y z` when the admin clicks.
     */
    private fun ledgerStash(sender: CommandSender, coc: ChainOfCustody, playerName: String) {
        resolvePlayer(playerName, sender) { uuid ->
            scope.launch {
                val stashes = coc.getPlayerStashes(uuid, limit = 20)
                scheduler.runMain(Runnable {
                    if (stashes.isEmpty()) {
                        sender.sendMessage("§7No recorded stash events for §e$playerName"); return@Runnable
                    }
                    sender.sendMessage("§6§lRecent stashes by $playerName §7(newest first, click coords to TP)")

                    for (entry in stashes) {
                        val time = dateFormat.format(Date(entry.timestamp))
                        val container = entry.metadata.containerType ?: entry.action.name
                        val coords = parseContainerCoords(entry.metadata.containerLocation)
                            ?: parseEntryCoords(entry)
                        val absQty = kotlin.math.abs(entry.quantity)

                        val prefix = "§7$time §f$absQty§7×§e${entry.material.name} §7→ §b$container §7@ "
                        if (coords != null) {
                            val (world, x, y, z) = coords
                            val display = "§a§n[$world $x, $y, $z]"
                            val msg = Chat.line()
                                .text(prefix)
                                .clickToTp(display, world, x, y, z, hover = "Click to teleport to this stash")
                                .build()
                            sender.sendChat(msg)
                        } else {
                            sender.sendMessage("$prefix§8(location unknown)")
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
                sender.sendMessage("§cConfirmed §e$playerName §cas a duper. Suspicion pinned high.")
                // Optional configurable punishment hook: detection.on_confirm_command, with {player}.
                val cmd = plugin.config.getString("detection.on_confirm_command", "")?.trim().orEmpty()
                if (cmd.isNotEmpty()) {
                    val resolved = cmd.replace("{player}", playerName)
                    scheduler.runMain(Runnable {
                        plugin.server.dispatchCommand(plugin.server.consoleSender, resolved)
                    })
                    sender.sendMessage("§7Ran punishment command: §f/$resolved")
                }
            } else {
                coc.clearVerdict(uuid)
                sender.sendMessage("§aCleared §e$playerName§a (false positive). Suspicion reset.")
            }
        }
    }

    private fun ledgerReconcile(sender: CommandSender, coc: ChainOfCustody, playerName: String) {
        val target = Bukkit.getPlayer(playerName) ?: run {
            sender.sendMessage("§cPlayer must be online for reconciliation"); return
        }
        sender.sendMessage("§7Running reconciliation for §e${target.name}§7...")
        coc.reconcileAsync(target) { result ->
            scheduler.runMain(Runnable {
                if (result.skipped) { sender.sendMessage("§7Reconciliation skipped: ${result.reason}"); return@Runnable }
                if (result.dupeDetected) {
                    sender.sendMessage("§c§lDUPE DETECTED!")
                    result.discrepancies.forEach { d ->
                        sender.sendMessage("  §c${d.material.name}: has ${d.actual}, should have ${d.expected} (excess: ${d.excess})")
                    }
                    result.tmarViolations.forEach { t ->
                        sender.sendMessage("  §e${t.material.name}: ${t.acquired}/${t.limit} in ${t.windowMinutes}min")
                    }
                } else {
                    sender.sendMessage("§aNo discrepancies found - player balances verified")
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
                append("§6§lTrust Score for $playerName\n")
                append("§7Score: $scoreColor${String.format("%.1f", trust.score)}§7/100\n")
                append("§7Verified Actions: §a${trust.verifiedCount}\n")
                append("§7Corroborated: §e${trust.corroboratedCount}\n")
                append("§7Solo: §7${trust.soloCount}\n")
                append("§7Contested: §c${trust.contestedCount}")
            })
        }
    }

    private fun ledgerVerify(sender: CommandSender, coc: ChainOfCustody) {
        sender.sendMessage("§7Verifying chain integrity...")
        scope.launch {
            val result = coc.verifyIntegrity()
            scheduler.runMain(Runnable {
                if (result.valid) sender.sendMessage("§a✓ Chain integrity verified: ${result.entriesVerified} entries OK")
                else {
                    sender.sendMessage("§c§l✗ CHAIN INTEGRITY FAILURE!")
                    sender.sendMessage("§c  Error: ${result.error}")
                    sender.sendMessage("§c  Broken at: ${result.brokenAt}")
                    sender.sendMessage("§c  Last valid: ${result.lastValidEntry}")
                }
            })
        }
    }
}
