package com.esmpfun.antidupe.api;

import com.esmpfun.antidupe.api.model.MarkState;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * The owner mark BetterAntiDupe keeps on tracked items. The mark is hidden from players' games
 * and signed with a key only the server holds, so a mark made anywhere else is not trusted.
 *
 * <p>Plugins that copy items from a template (shop displays, kits saved from someone's
 * inventory, crate previews) should {@link #strip(ItemStack)} the copy, or every copy carries
 * the original holder's mark.
 */
public interface OwnershipMarks {

    /** The player this item is marked as belonging to, if the mark is trusted. */
    Optional<UUID> owner(ItemStack stack);

    /** True when the item carries a trusted owner mark. */
    boolean isMarked(ItemStack stack);

    /**
     * What the item's mark looks like: signed, from before signing, faked, or signed elsewhere.
     *
     * @since API level 2
     */
    MarkState state(ItemStack stack);

    /** Removes the owner mark from this stack in place, including marks under older names. */
    void strip(ItemStack stack);
}
