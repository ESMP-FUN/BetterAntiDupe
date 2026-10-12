package com.esmpfun.antidupe.api.model;

/** The protections that stop a dupe before it happens. New ones may be added later. */
public enum Protection {
    /** A piston moving a rail off its block. */
    RAIL_DUPER,
    /** A piston moving a carpet off its block. */
    CARPET_DUPER,
    /** A piston moving TNT. */
    TNT_DUPER,
    /** Sand, gravel and the like falling through a portal. */
    GRAVITY_DUPER,
    /** A menu staying open after its chest, shulker box or mount is gone. */
    CONTAINER_DESYNC,
    /** An action sent while the player's game hid a menu the server still had open. */
    HIDDEN_MENU,
    /** Items dropped by a placement another plugin then blocked. */
    REVERTED_PLACEMENT
}
