package com.esmpfun.antidupe.api.model;

/** What an alert is about. New kinds may be added in later versions, so keep a default branch. */
public enum AlertKind {
    /** Carrying more than the record explains. */
    CARRYING_EXTRA,
    /** The exact same dropped item was picked up twice. */
    SAME_ITEM_TWICE,
    /** Picked up more than the nearby source could have produced. */
    PICKUP_BEYOND_SOURCE,
    /** Carried extra items, then stored them away so the record wrote them off. */
    STORED_AWAY_EXTRA,
    /** More of one player's items came out of storage than ever went in. */
    STORAGE_SHORTFALL,
    /** Left creative mode carrying more than the record showed. */
    LEFT_CREATIVE,
    /** Gained an item faster than the configured rate allows. */
    RATE_LIMIT,
    /** Carrying an item marked as someone else's. */
    FOREIGN_ITEM,
    /** The record's tamper check failed. */
    RECORD_TAMPERED,
    /** Acted in the world while their game hid a menu the server still had open. */
    HIDDEN_MENU,
    /** A blocked placement would have copied a container and its contents. */
    REVERTED_PLACEMENT
}
