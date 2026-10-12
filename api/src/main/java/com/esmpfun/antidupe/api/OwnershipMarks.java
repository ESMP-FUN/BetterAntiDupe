package com.esmpfun.antidupe.api;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * The owner mark BetterAntiDupe keeps on tracked items. The mark is hidden from players' games.
 *
 * <p>Plugins that copy items from a template (shop displays, kits saved from someone's
 * inventory, crate previews) should {@link #strip(ItemStack)} the copy, or every copy carries
 * the original holder's mark.
 */
public interface OwnershipMarks {

    /** The player this item is marked as belonging to, if any. */
    Optional<UUID> owner(ItemStack stack);

    /** True when the item carries an owner mark. */
    boolean isMarked(ItemStack stack);

    /** Removes the owner mark from this stack in place, including marks under older names. */
    void strip(ItemStack stack);
}
