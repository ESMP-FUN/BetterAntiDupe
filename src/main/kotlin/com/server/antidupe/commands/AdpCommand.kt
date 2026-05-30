package com.server.antidupe.commands

import com.server.antidupe.core.IsotopeManager.Companion.isotopeId
import com.server.antidupe.data.IsotopeStorage
import com.server.antidupe.ledger.ChainOfCustody
import com.server.antidupe.ledger.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * Base command handler for /adp with subcommands.
 * Usage:
 *   /adp inspect - Inspect held item's security ID
 *   /adp dupetest - Create test duplicate of held item
 *   /adp cleanse - Give held item a fresh security ID
 *   /adp ledger <subcommand> - Chain of Custody v2 commands
 *   /adp help - Show help
 */
class AdpCommand(
    private val plugin: JavaPlugin,
    private val storage: IsotopeStorage,
    private val scope: CoroutineScope
) : CommandExecutor, TabCompleter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    // Chain of Custody v2 (optional, set after initialization)
    private var chainOfCustody: ChainOfCustody? = null

    /**
     * Resolve a player name to a UUID without blocking the calling thread on a Mojang lookup.
     * Hits online roster first, then cached offline players, otherwise falls through to an
     * async lookup whose callback runs on the main thread.
     */
    private fun resolvePlayer(name: String, sender: CommandSender, onResolved: (UUID) -> Unit) {
        Bukkit.getPlayerExact(name)?.let { return onResolved(it.uniqueId) }
        @Suppress("DEPRECATION")
        val cached = Bukkit.getOfflinePlayerIfCached(name)
        if (cached != null) return onResolved(cached.uniqueId)

        sender.sendMessage("§7Looking up §e$name§7...")
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            @Suppress("DEPRECATION")
            val offline = Bukkit.getOfflinePlayer(name)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (offline.hasPlayedBefore() || offline.isOnline) onResolved(offline.uniqueId)
                else sender.sendMessage("§cUnknown player: $name")
            })
        })
    }

    fun setChainOfCustody(coc: ChainOfCustody) {
        this.chainOfCustody = coc
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            showHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "inspect" -> handleInspect(sender)
            "dupetest", "test" -> handleDupeTest(sender)
            "cleanse", "remint", "fix" -> handleCleanse(sender)
            "ledger", "coc", "chain" -> handleLedger(sender, args.drop(1).toTypedArray())
            "help", "?" -> showHelp(sender)
            else -> {
                sender.sendMessage("§cUnknown subcommand. Use §e/adp help§c for available commands.")
            }
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val subcommands = mutableListOf<String>()

            if (sender.hasPermission("antidupe.inspect")) {
                subcommands.add("inspect")
            }
            if (sender.hasPermission("antidupe.dupetest")) {
                subcommands.add("dupetest")
            }
            if (sender.hasPermission("antidupe.cleanse")) {
                subcommands.add("cleanse")
            }
            if (sender.hasPermission("antidupe.admin") && chainOfCustody != null) {
                subcommands.add("ledger")
            }
            subcommands.add("help")

            return subcommands.filter { it.startsWith(args[0].lowercase()) }
        }

        // Tab complete for ledger subcommands
        if (args.size >= 2 && args[0].lowercase() in listOf("ledger", "coc", "chain")) {
            if (!sender.hasPermission("antidupe.admin") || chainOfCustody == null) {
                return emptyList()
            }

            return when (args.size) {
                2 -> listOf("status", "balance", "history", "witness", "suspects", "reconcile", "trust", "verify", "help")
                    .filter { it.startsWith(args[1].lowercase()) }
                3 -> when (args[1].lowercase()) {
                    "balance", "history", "witness", "reconcile", "trust" -> {
                        Bukkit.getOnlinePlayers().map { it.name }
                            .filter { it.lowercase().startsWith(args[2].lowercase()) }
                    }
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

        if (sender.hasPermission("antidupe.inspect")) {
            sender.sendMessage("§e/adp inspect §7- Inspect held item's security ID")
        }
        if (sender.hasPermission("antidupe.dupetest")) {
            sender.sendMessage("§e/adp dupetest §7- Create test duplicate of held item")
        }
        if (sender.hasPermission("antidupe.cleanse")) {
            sender.sendMessage("§e/adp cleanse §7- Give held item a fresh security ID")
        }
        if (sender.hasPermission("antidupe.admin") && chainOfCustody != null) {
            sender.sendMessage("")
            sender.sendMessage("§6§lChain of Custody v2:")
            sender.sendMessage("§e/adp ledger status §7- System status")
            sender.sendMessage("§e/adp ledger balance <player> §7- Item balances")
            sender.sendMessage("§e/adp ledger witness <player> §7- Witness stats")
            sender.sendMessage("§e/adp ledger suspects §7- List suspects")
            sender.sendMessage("§e/adp ledger help §7- All ledger commands")
        }
        sender.sendMessage("")
        sender.sendMessage("§e/adp help §7- Show this help message")
        sender.sendMessage("§8§m-----------------------------------------")
    }

    // ==================== INSPECT SUBCOMMAND ====================

    private fun handleInspect(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage("§cOnly players can inspect items.")
            return
        }

        if (!sender.hasPermission("antidupe.inspect")) {
            sender.sendMessage("§cYou do not have permission to inspect protected items.")
            return
        }

        val item = sender.inventory.itemInMainHand
        val id = item.isotopeId

        if (id == null) {
            sender.sendMessage("§e[ADP] §7This item is §oUNTRACKED§7 (No security signature).")
            return
        }

        sender.sendMessage("§e[ADP] §fScanning: §b$id...")

        val playerUuid = sender.uniqueId

        scope.launch {
            val rawData = storage.getStatus(id)

            Bukkit.getScheduler().runTask(plugin, Runnable {
                val player = Bukkit.getPlayer(playerUuid)
                if (player == null || !player.isOnline) return@Runnable

                val parts = rawData.split("|")
                val status = parts.getOrElse(0) { "UNKNOWN" }
                val ownerUuidStr = parts.getOrElse(1) { "" }
                val timestamp = parts.getOrElse(2) { "0" }.toLongOrNull()

                val statusColor = when {
                    status.startsWith("ACTIVE") -> "§a§lACTIVE"
                    status == "SPENT" -> "§c§lSPENT (DUPLICATE)"
                    status == "UNKNOWN" || rawData == "UNKNOWN" -> "§7§lUNKNOWN"
                    else -> "§7$status"
                }

                val ownerName = if (ownerUuidStr.isNotEmpty()) {
                    try {
                        val ownerUuid = UUID.fromString(ownerUuidStr)
                        if (ownerUuid == UUID(0, 0)) {
                            "System"
                        } else {
                            Bukkit.getOfflinePlayer(ownerUuid).name ?: ownerUuidStr
                        }
                    } catch (e: Exception) {
                        ownerUuidStr
                    }
                } else {
                    "Unknown"
                }

                player.sendMessage("§8§m-----------------------------------------")
                player.sendMessage("§6§lITEM SECURITY REPORT")
                player.sendMessage("§7UUID: §f$id")
                player.sendMessage("§7Status: $statusColor")

                if (timestamp != null && timestamp > 0) {
                    val dateStr = dateFormat.format(Date(timestamp))
                    player.sendMessage("§7Minted: §e$dateStr")
                    player.sendMessage("§7Original Owner: §e$ownerName")
                }

                if (status == "SPENT") {
                    player.sendMessage("")
                    player.sendMessage("§4§l[!] WARNING [!]")
                    player.sendMessage("§cThis item is a detected DUPLICATE.")
                    player.sendMessage("§cThis signature has already been consumed.")
                }
                player.sendMessage("§8§m-----------------------------------------")
            })
        }
    }

    // ==================== DUPETEST SUBCOMMAND ====================

    private fun handleDupeTest(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage("§cOnly players can use this command.")
            return
        }

        if (!sender.hasPermission("antidupe.dupetest")) {
            sender.sendMessage("§cYou do not have permission to use this command.")
            return
        }

        val heldItem = sender.inventory.itemInMainHand

        if (heldItem.type.isAir) {
            sender.sendMessage("§e[ADP] §cYou must be holding an item to duplicate.")
            return
        }

        val originalId = heldItem.isotopeId
        if (originalId == null) {
            sender.sendMessage("§e[ADP] §cThis item has no security ID. Only protected items can be test-duplicated.")
            sender.sendMessage("§7Hint: Pick up or craft a tracked material first.")
            return
        }

        val duplicate = heldItem.clone()

        val dupeId = duplicate.isotopeId
        if (dupeId != originalId) {
            sender.sendMessage("§e[ADP] §cClone failed - IDs don't match. Please report this bug.")
            return
        }

        val leftover = sender.inventory.addItem(duplicate)
        if (leftover.isNotEmpty()) {
            leftover.values.forEach { item ->
                sender.world.dropItemNaturally(sender.location, item)
            }
            sender.sendMessage("§e[ADP] §aDuplicate created and dropped at your feet (inventory full).")
        } else {
            sender.sendMessage("§e[ADP] §aDuplicate created and added to your inventory.")
        }

        sender.sendMessage("§8§m-----------------------------------------")
        sender.sendMessage("§6§lDUPE TEST MODE")
        sender.sendMessage("§7Both items now share the same security ID:")
        sender.sendMessage("§f$originalId")
        sender.sendMessage("")
        sender.sendMessage("§eTest by splitting, merging, or using /adp inspect on either.")
        sender.sendMessage("§eThe first transaction will burn the ID,")
        sender.sendMessage("§emaking the second copy a detected duplicate.")
        sender.sendMessage("§8§m-----------------------------------------")

        plugin.logger.info("[DupeTest] ${sender.name} created a test duplicate of ${heldItem.type} with ID $originalId")
    }

    // ==================== CLEANSE SUBCOMMAND ====================

    private fun handleCleanse(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage("§cOnly players can use this command.")
            return
        }

        if (!sender.hasPermission("antidupe.cleanse")) {
            sender.sendMessage("§cYou do not have permission to use this command.")
            return
        }

        val heldItem = sender.inventory.itemInMainHand

        if (heldItem.type.isAir) {
            sender.sendMessage("§e[ADP] §cYou must be holding an item to cleanse.")
            return
        }

        val oldId = heldItem.isotopeId

        // Generate and assign new ID
        val newId = UUID.randomUUID()
        heldItem.isotopeId = newId

        // Log the cleanse to Redis
        scope.launch {
            try {
                // Mint the new ID (don't burn old - it may already be spent or not exist)
                storage.mint(newId, sender.uniqueId, "CLEANSED from ${oldId ?: "untracked"}")
            } catch (e: Exception) {
                plugin.logger.warning("Failed to log cleanse to Redis: ${e.message}")
            }
        }

        if (oldId != null) {
            sender.sendMessage("§e[ADP] §aItem cleansed!")
            sender.sendMessage("§7Old ID: §c$oldId §7(may be spent/invalid)")
            sender.sendMessage("§7New ID: §a$newId")
        } else {
            sender.sendMessage("§e[ADP] §aItem now has a security ID!")
            sender.sendMessage("§7New ID: §a$newId")
        }

        plugin.logger.info("[Cleanse] ${sender.name} cleansed ${heldItem.type}: $oldId -> $newId")
    }

    // ==================== LEDGER SUBCOMMANDS ====================

    private fun handleLedger(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("antidupe.admin")) {
            sender.sendMessage("§cYou don't have permission to use ledger commands.")
            return
        }

        val coc = chainOfCustody
        if (coc == null) {
            sender.sendMessage("§cChain of Custody v2 is not enabled.")
            sender.sendMessage("§7Set §eledger.enabled: true §7in config.yml")
            return
        }

        if (args.isEmpty()) {
            showLedgerHelp(sender)
            return
        }

        when (args[0].lowercase()) {
            "status" -> ledgerStatus(sender, coc)
            "balance" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /adp ledger balance <player>")
                    return
                }
                ledgerBalance(sender, coc, args[1])
            }
            "history" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /adp ledger history <player>")
                    return
                }
                ledgerHistory(sender, coc, args[1])
            }
            "witness" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /adp ledger witness <player>")
                    return
                }
                ledgerWitness(sender, coc, args[1])
            }
            "suspects" -> ledgerSuspects(sender, coc)
            "reconcile" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /adp ledger reconcile <player>")
                    return
                }
                ledgerReconcile(sender, coc, args[1])
            }
            "trust" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /adp ledger trust <player>")
                    return
                }
                ledgerTrust(sender, coc, args[1])
            }
            "verify" -> ledgerVerify(sender, coc)
            "help" -> showLedgerHelp(sender)
            else -> {
                sender.sendMessage("§cUnknown ledger subcommand. Use §e/adp ledger help")
            }
        }
    }

    private fun showLedgerHelp(sender: CommandSender) {
        sender.sendMessage(buildString {
            append("§6§lChain of Custody v2 Commands\n")
            append("§e/adp ledger status §7- System status and chain tip\n")
            append("§e/adp ledger balance <player> §7- Player's item balances\n")
            append("§e/adp ledger history <player> §7- Recent ledger entries\n")
            append("§e/adp ledger witness <player> §7- Witness statistics\n")
            append("§e/adp ledger suspects §7- List current suspects\n")
            append("§e/adp ledger reconcile <player> §7- Force reconciliation\n")
            append("§e/adp ledger trust <player> §7- Trust score details\n")
            append("§e/adp ledger verify §7- Verify chain integrity")
        })
    }

    private fun ledgerStatus(sender: CommandSender, coc: ChainOfCustody) {
        scope.launch {
            val stats = coc.getSystemStats()
            val tip = coc.getChainTip()

            plugin.server.scheduler.runTask(plugin, Runnable {
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
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (balances.isEmpty()) {
                        sender.sendMessage("§7No tracked items for §e$playerName")
                        return@Runnable
                    }
                    sender.sendMessage("§6§lBalances for $playerName")
                    balances.entries.sortedByDescending { it.value }.forEach { (material, count) ->
                        val color = if (count > 0) "§a" else "§c"
                        sender.sendMessage("  §7${material.name}: $color$count")
                    }
                })
            }
        }
    }

    private fun ledgerHistory(sender: CommandSender, coc: ChainOfCustody, playerName: String) {
        resolvePlayer(playerName, sender) { uuid ->
            scope.launch {
                val history = coc.getPlayerHistory(uuid, limit = 15)
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (history.isEmpty()) {
                        sender.sendMessage("§7No ledger entries for §e$playerName")
                        return@Runnable
                    }
                    sender.sendMessage("§6§lRecent History for $playerName")
                    history.forEach { entry ->
                        val time = dateFormat.format(Date(entry.timestamp))
                        val qtyColor = if (entry.quantity >= 0) "§a+" else "§c"
                        val witnessInfo = entry.metadata.witnessCount?.let { " §8[${it}W]" } ?: ""
                        sender.sendMessage(buildString {
                            append("  §7$time ")
                            append("§f${entry.action.name} ")
                            append("$qtyColor${entry.quantity} ")
                            append("§e${entry.material.name}")
                            append(witnessInfo)
                        })
                    }
                })
            }
        }
    }

    private fun ledgerWitness(sender: CommandSender, coc: ChainOfCustody, playerName: String) {
        resolvePlayer(playerName, sender) { uuid -> renderWitness(sender, coc, playerName, uuid) }
    }

    private fun renderWitness(sender: CommandSender, coc: ChainOfCustody, playerName: String, uuid: UUID) {
        val stats = coc.getWitnessStats(uuid)

        sender.sendMessage(buildString {
            append("§6§lWitness Stats for $playerName\n")
            append("§7Total Actions: §f${stats.totalActions}\n")
            append("§7Witnessed: §a${stats.witnessedActions} §7(${(stats.witnessRatio * 100).toInt()}%)\n")
            append("§7Verified (3+ witnesses): §a${stats.verifiedActions}\n")
            append("§7Solo (no witnesses): §e${stats.soloActions}\n")
            append("§7Trust Score: §f${String.format("%.1f", stats.trustScore)}/100\n")
            if (stats.isSuspicious) {
                append("§c§lSUSPICIOUS: ${stats.suspicionReason}")
            } else {
                append("§aNo suspicious patterns detected")
            }
        })
    }

    private fun ledgerSuspects(sender: CommandSender, coc: ChainOfCustody) {
        val suspects = coc.getSuspects()

        if (suspects.isEmpty()) {
            sender.sendMessage("§aNo current suspects")
            return
        }

        sender.sendMessage("§6§lCurrent Suspects (${suspects.size})")
        suspects.sortedByDescending { it.violationCount }.forEach { suspect ->
            val excess = suspect.getTotalExcess()
            val topItem = excess.maxByOrNull { it.value }

            sender.sendMessage(buildString {
                append("  §e${suspect.playerName} §7- ")
                append("§c${suspect.violationCount} violations")
                if (topItem != null) {
                    append(" §7(${topItem.key.name}: +${topItem.value})")
                }
            })
        }
    }

    private fun ledgerReconcile(sender: CommandSender, coc: ChainOfCustody, playerName: String) {
        val target = Bukkit.getPlayer(playerName)
        if (target == null) {
            sender.sendMessage("§cPlayer must be online for reconciliation")
            return
        }

        sender.sendMessage("§7Running reconciliation for §e${target.name}§7...")

        coc.reconcileAsync(target) { result ->
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (result.skipped) {
                    sender.sendMessage("§7Reconciliation skipped: ${result.reason}")
                    return@Runnable
                }

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
        resolvePlayer(playerName, sender) { uuid -> renderTrust(sender, coc, playerName, uuid) }
    }

    private fun renderTrust(sender: CommandSender, coc: ChainOfCustody, playerName: String, uuid: UUID) {
        val trust = coc.getTrustScore(uuid)

        val scoreColor = when {
            trust.score >= 80 -> "§a"
            trust.score >= 50 -> "§e"
            trust.score >= 20 -> "§6"
            else -> "§c"
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

    private fun ledgerVerify(sender: CommandSender, coc: ChainOfCustody) {
        sender.sendMessage("§7Verifying chain integrity...")

        scope.launch {
            val result = coc.verifyIntegrity()

            plugin.server.scheduler.runTask(plugin, Runnable {
                if (result.valid) {
                    sender.sendMessage("§a✓ Chain integrity verified: ${result.entriesVerified} entries OK")
                } else {
                    sender.sendMessage("§c§l✗ CHAIN INTEGRITY FAILURE!")
                    sender.sendMessage("§c  Error: ${result.error}")
                    sender.sendMessage("§c  Broken at: ${result.brokenAt}")
                    sender.sendMessage("§c  Last valid: ${result.lastValidEntry}")
                }
            })
        }
    }
}
