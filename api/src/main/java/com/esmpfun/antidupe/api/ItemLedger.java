package com.esmpfun.antidupe.api;

import com.esmpfun.antidupe.api.model.CheckResult;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The record of what each player earned. Items that reach a player through normal play are
 * recorded automatically; only items your plugin hands out or takes away outside of that need
 * the two record methods below.
 *
 * <p>Futures complete off the main thread. Hop back before touching the world.
 */
public interface ItemLedger {

    /** True when this kind of item is watched. Untracked items need no recording at all. */
    boolean isTracked(Material material);

    /** Every kind of item currently watched. */
    Set<Material> trackedMaterials();

    /**
     * Declares items your plugin gave a player (a reward, a shop sale, a kit), so carrying them
     * is never treated as a dupe. Call it once per handout. Untracked materials are ignored.
     *
     * @param source short label that shows in the player's history, for example {@code "MyShop"}
     */
    CompletableFuture<@Nullable Void> recordGrant(UUID player, Material material, int amount, String source);

    /**
     * Declares items your plugin took out of a player's inventory (a sell shop, a custom recipe,
     * a tax). Pass the stacks exactly as they were removed: only items marked as this player's
     * are counted, including ones inside shulker boxes and bundles. Reads the stacks right away,
     * so they can be changed once this returns.
     *
     * @param source short label that shows in the player's history
     */
    CompletableFuture<@Nullable Void> recordRemoval(UUID player, Collection<ItemStack> removed, String source);

    /** What the record says the player should hold of one item. */
    CompletableFuture<Integer> balance(UUID player, Material material);

    /** What the record says the player should hold, per tracked item. */
    CompletableFuture<Map<Material, Integer>> balances(UUID player);

    /** Runs a balance check on an online player now and reports what it found. */
    CompletableFuture<CheckResult> check(Player player);
}
