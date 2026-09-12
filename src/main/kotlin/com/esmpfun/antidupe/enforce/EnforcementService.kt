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
 * Acts on a confirmed dupe alert by taking the surplus back out of the player's inventory.
 *
 * Everything here is off unless an admin turns it on. Two independent switches gate it, and
 * both must agree before a single item is touched:
 *
 *  - `shadow_mode: true` (the default) is a hard veto. Shadow mode means "watch and record",
 *    so it wins over every other setting in this file.
 *  - `auto_delete_dupes: false` (the default) means alert-only even with shadow mode off.
 *
 * Removal is deliberately conservative. It only takes items the ledger actually counted:
 * stacks carrying this player's ownership tag, in the main inventory, and never more than
 * the surplus the alert reported. Items stored inside a shulker box or bundle are counted by
 * reconciliation but are not unpacked here: reaching into nested containers to delete things
 * is a far larger blast radius than the problem warrants, so a shortfall is reported to the
 * console instead and left for an admin to handle by hand.
 */
class EnforcementService(
    private val settings: Settings,
    private val ownershipManager: OwnershipManager,
    private val scheduler: PlatformScheduler,
    private val scope: CoroutineScope,
    private val logger: Logger
) {

    /**
     * @param shadowMode          Watch-only. Vetoes removal regardless of [autoDelete].
     * @param autoDelete          Remove the surplus instead of only alerting.
     * @param minSeverity         Alerts below this confidence are never acted on.
     * @param maxItemsPerAction   Safety cap on one removal; 0 means no cap.
     * @param notifyPlayer        Tell the player something was taken back.
     */
    data class Settings(
        val shadowMode: Boolean = true,
        val autoDelete: Boolean = false,
        val minSeverity: Severity = Severity.HIGH,
        val maxItemsPerAction: Int = 0,
        val notifyPlayer: Boolean = true
    ) {
        /** True when this configuration can ever remove an item. */
        val active: Boolean get() = autoDelete && !shadowMode
    }

    private var chainOfCustody: ChainOfCustody? = null

    fun setChainOfCustody(coc: ChainOfCustody) {
        chainOfCustody = coc
    }

    /** Alert listener entry point. Returns immediately unless removal is both enabled and warranted. */
    fun handle(alert: DupeAlert) {
        if (!settings.active) return
        if (alert.excess <= 0) return
        if (alert.severity.ordinal < settings.minSeverity.ordinal) return

        val wanted = if (settings.maxItemsPerAction > 0)
            minOf(alert.excess, settings.maxItemsPerAction) else alert.excess

        // Alerts arrive on a reconciliation coroutine. The online-player roster is read on the
        // main (global region) thread, and the inventory itself is only safe to change on the
        // player's own thread. On Folia, doing either from here is a cross-region violation.
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
                report(player, alert, removed, wanted)
            })
        })
    }

    /**
     * Take up to [wanted] items of [material] that carry this player's ownership tag out of
     * their main inventory. Returns how many were actually removed.
     */
    private fun removeOwned(player: Player, material: Material, wanted: Int): Int {
        val inventory = player.inventory
        var remaining = wanted

        for (slot in 0 until inventory.size) {
            if (remaining <= 0) break
            val stack = inventory.getItem(slot) ?: continue
            if (stack.type != material) continue
            // Untagged stacks were never counted by reconciliation, so they are not part of
            // the surplus and must not be taken.
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

    /** DIAMOND_BLOCK reads as "Diamond Block" for anything a player sees. */
    private fun friendlyName(material: Material): String =
        material.name.split('_').joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }

    /** Console line, optional player notice, and an audit entry so the removal is on the record. */
    private fun report(player: Player, alert: DupeAlert, removed: Int, wanted: Int) {
        com.esmpfun.antidupe.metrics.DetectionCounters.recordItemsRemoved(alert.material.name, removed)
        logger.warning(
            "[Enforce] Removed $removed x ${alert.material.name} from ${player.name}" +
                " (surplus ${alert.excess}, severity ${alert.severity})"
        )
        if (removed < wanted) {
            logger.warning(
                "[Enforce] ${wanted - removed} x ${alert.material.name} of ${player.name}'s surplus" +
                    " could not be reached: it is stored inside a shulker box or bundle." +
                    " Review it by hand with /adp ledger balance ${player.name}"
            )
        }

        if (settings.notifyPlayer) {
            player.sendMessage(Messages.msg(
                "enforcement.items-removed",
                "amount" to "$removed",
                "material" to friendlyName(alert.material)
            ))
        }

        val coc = chainOfCustody ?: return
        scope.launch {
            try {
                coc.recordEnforcement(player.uniqueId, alert.material, removed, alert.severity.name)
            } catch (e: Exception) {
                logger.warning("[Enforce] Audit entry failed: ${e.message}")
            }
        }
    }
}
