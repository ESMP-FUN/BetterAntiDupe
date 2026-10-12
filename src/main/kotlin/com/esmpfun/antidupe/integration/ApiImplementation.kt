package com.esmpfun.antidupe.integration

import com.esmpfun.antidupe.BetterAntiDupe
import com.esmpfun.antidupe.api.BetterAntiDupeApi
import com.esmpfun.antidupe.api.ItemLedger
import com.esmpfun.antidupe.api.OwnershipMarks
import com.esmpfun.antidupe.api.Suspects
import com.esmpfun.antidupe.api.model.CheckResult
import com.esmpfun.antidupe.api.model.Discrepancy
import com.esmpfun.antidupe.api.model.SuspectInfo
import com.esmpfun.antidupe.ledger.ReconciliationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** The plugin's side of the public API. Reads Chain of Custody fresh on every call, since it can be missing. */
class ApiImplementation(
    private val plugin: BetterAntiDupe,
    private val scope: CoroutineScope
) : BetterAntiDupeApi, ItemLedger, OwnershipMarks, Suspects {

    private val coc get() = plugin.getChainOfCustody()

    override fun apiLevel(): Int = BetterAntiDupeApi.API_LEVEL

    @Suppress("DEPRECATION")
    override fun pluginVersion(): String = plugin.description.version

    override fun isTracking(): Boolean = coc != null

    override fun ledger(): ItemLedger = this

    override fun ownership(): OwnershipMarks = this

    override fun suspects(): Suspects = this

    override fun isTracked(material: Material): Boolean = coc?.trackedMaterials?.contains(material) == true

    override fun trackedMaterials(): Set<Material> = coc?.trackedMaterials.orEmpty()

    override fun recordGrant(player: UUID, material: Material, amount: Int, source: String): CompletableFuture<Void?> {
        val c = coc ?: return done()
        if (amount <= 0 || material !in c.trackedMaterials) return done()
        return scope.future { c.recordSystemGrant(player, material, amount, source); null }
    }

    override fun recordRemoval(player: UUID, removed: Collection<ItemStack>, source: String): CompletableFuture<Void?> {
        val c = coc ?: return done()
        val tracked = c.trackedMaterials
        val owners = HashMap<Pair<UUID, Material>, Int>()
        for (stack in removed) c.ownershipManager.tallyOwners(stack, { it in tracked }, owners)
        val mine = owners.filterKeys { it.first == player }.mapKeys { it.key.second }
        if (mine.isEmpty()) return done()
        return scope.future {
            for ((material, amount) in mine) c.recordSystemRemoval(player, material, amount, source)
            null
        }
    }

    override fun balance(player: UUID, material: Material): CompletableFuture<Int> {
        val c = coc ?: return CompletableFuture.completedFuture(0)
        return scope.future { c.getBalance(player, material) }
    }

    override fun balances(player: UUID): CompletableFuture<Map<Material, Int>> {
        val c = coc ?: return CompletableFuture.completedFuture(emptyMap())
        return scope.future { c.getAllBalances(player).filterKeys { it in c.trackedMaterials } }
    }

    override fun check(player: Player): CompletableFuture<CheckResult> {
        val c = coc ?: return CompletableFuture.completedFuture(
            CheckResult(player.uniqueId, System.currentTimeMillis(), true, "Item tracking is not running", emptyList())
        )
        return scope.future { c.reconciliationEngine.reconcile(player, ignoreCooldown = true).toApi() }
    }

    override fun owner(stack: ItemStack): Optional<UUID> = Optional.ofNullable(coc?.ownershipManager?.getOwner(stack))

    override fun isMarked(stack: ItemStack): Boolean = owner(stack).isPresent

    override fun strip(stack: ItemStack) {
        coc?.ownershipManager?.clearOwnerDeep(stack)
    }

    override fun suspicion(player: UUID): Double = coc?.suspicionOf(player) ?: 0.0

    override fun isSuspect(player: UUID): Boolean = coc?.getSuspect(player) != null

    override fun info(player: UUID): Optional<SuspectInfo> {
        val profile = coc?.getSuspect(player) ?: return Optional.empty()
        return Optional.of(
            SuspectInfo(
                profile.playerId, profile.playerName, profile.violationCount,
                profile.firstViolation, profile.lastViolation, profile.getTotalExcess()
            )
        )
    }

    private fun ReconciliationResult.toApi() = CheckResult(
        player, timestamp, skipped, reason,
        discrepancies.filter { it.excess > 0 }.map { Discrepancy(it.material, it.expected, it.actual) }
    )

    private fun done(): CompletableFuture<Void?> = CompletableFuture.completedFuture(null)
}
