package com.esmpfun.antidupe.api.model;

import org.bukkit.Material;

/**
 * One kind of item a player held more of than the record explains.
 *
 * @param recorded what the record says they should hold
 * @param held     what they actually held, including inside shulker boxes and bundles
 */
public record Discrepancy(Material material, int recorded, int held) {

    /** How many more they held than recorded. */
    public int excess() {
        return Math.max(0, held - recorded);
    }
}
