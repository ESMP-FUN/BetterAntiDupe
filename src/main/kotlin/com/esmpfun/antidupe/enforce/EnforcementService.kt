package com.esmpfun.antidupe.enforce

import com.esmpfun.antidupe.ledger.ChainOfCustody
import com.esmpfun.antidupe.ledger.DupeAlert
import com.esmpfun.antidupe.ledger.OwnershipManager
import com.esmpfun.antidupe.ledger.Severity
import com.esmpfun.antidupe.platform.PlatformScheduler
import com.esmpfun.antidupe.util.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.logging.Logger

/**
 * Takes a confirmed surplus back out of the player's inventory. Items nested in a shulker box
 * or bundle are counted by reconciliation but deliberately left alone; the shortfall is logged.
 */
class EnforcementService(
    @Volatile var settings: Settings,
    private val ownershipManager: OwnershipManager,
    private val scheduler: PlatformScheduler,
    private val scope: CoroutineScope,
    private val logger: Logger
) {

    /** [maxItemsPerAction] 0 means no cap. */
    data class Settings(
        val shadowMode: Boolean = true,
        val autoDelete: Boolean = false,
        val minSeverity: Severity = Severity.HIGH,
        val maxItemsPerAction: Int = 0,
        val notifyPlayer: Boolean = true
    ) {
        val active: Boolean get() = autoDelete && !shadowMode
    }

    private var chainOfCustody: ChainOfCustody? = null

    fun setChainOfCustody(coc: ChainOfCustody) {
        chainOfCustody = coc
    }

    fun handle(alert: DupeAlert) {
        val settings = settings
        if (!settings.active) return
        if (alert.excess <= 0) return
        if (alert.severity.ordinal < settings.minSeverity.ordinal) return

        val wanted = if (settings.maxItemsPerAction > 0)
            minOf(alert.excess, settings.maxItemsPerAction) else alert.excess

        // Alerts arrive on a coroutine. On Folia, reading the player roster off the global region
        // thread or touching an inventory off the player's own thread is a cross-region violation.
        scheduler.runMain(Runnable {
            val player = Bukkit.getPlayer(alert.player) ?: return@Runnable
            scheduler.runForEntity(player, Runnable {
                val removed = try {
                    removeOwned(player, alert.material, wanted)
                } catch (e: Exception) {
                    logger.warning("[Enforce] Removal failed for ${player.name}: ${e.message}")
                    return@Runnable
                }
                if (removed <= 0) return@Runnable
                logger.warning(
                    "[Enforce] Removed $removed x ${alert.material.name} from ${player.name}" +
                        " (surplus ${alert.excess}, severity ${alert.severity})"
                )
                report(player, alert.material, removed, wanted, alert.severity.name)
            })
        })
    }

    /**
     * Admin-ordered removal, deliberately not gated by shadow mode or the severity floor.
     * Must run on [player]'s own thread. Returns how many were actually taken.
     */
    fun removeSurplusNow(player: Player, material: Material, amount: Int, admin: String): Int {
        if (amount <= 0) return 0
        val removed = removeOwned(player, material, amount)
        if (removed <= 0) return 0
        logger.warning("[Enforce] $admin removed $removed x ${material.name} from ${player.name} by hand")
        report(player, material, removed, amount, "MANUAL")
        return removed
    }

    private fun removeOwned(player: Player, material: Material, wanted: Int): Int {
        val inventory = player.inventory
        var remaining = wanted

        for (slot in 0 until inventory.size) {
            if (remaining <= 0) break
            val stack = inventory.getItem(slot) ?: continue
            if (stack.type != material) continue
            // Untagged stacks were never counted by reconciliation, so they are not surplus.
            if (!ownershipManager.isOwnedBy(stack, player.uniqueId)) continue

            val take = minOf(stack.amount, remaining)
            remaining -= take
            if (take >= stack.amount) {
                inventory.setItem(slot, null)
            } else {
                stack.amount -= take
                inventory.setItem(slot, stack)
            }
        }
        return wanted - remaining
    }

    private fun friendlyName(material: Material): String =
        material.name.split('_').joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }

    private fun report(player: Player, material: Material, removed: Int, wanted: Int, reason: String) {
        com.esmpfun.antidupe.metrics.DetectionCounters.recordItemsRemoved(material.name, removed)
        if (removed < wanted) {
            logger.warning(
                "[Enforce] ${wanted - removed} x ${material.name} of ${player.name}'s surplus" +
                    " could not be reached: it is stored inside a shulker box or bundle." +
                    " Review it by hand with /adp ledger balance ${player.name}"
            )
        }

        if (settings.notifyPlayer) {
            player.sendMessage(Messages.msg(
                "enforcement.items-removed",
                "amount" to "$removed",
                "material" to friendlyName(material)
            ))
        }

        val coc = chainOfCustody ?: return
        scope.launch {
            try {
                coc.recordEnforcement(player.uniqueId, material, removed, reason)
            } catch (e: Exception) {
                logger.warning("[Enforce] Audit entry failed: ${e.message}")
            }
        }
    }
}
