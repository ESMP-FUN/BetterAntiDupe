package com.esmpfun.antidupe.api.model;

/** What an item's owner mark looks like to this server. New states may be added later. */
public enum MarkState {
    /** The item carries no owner mark. */
    NONE,
    /** Signed by this server or its network, so the owner is trusted. */
    SIGNED,
    /** Made before signing was switched on. Trusted or not depending on the server's settings. */
    UNSIGNED,
    /** The signature does not match: the mark was faked. Not trusted. */
    FORGED,
    /** Signed with a key this server does not have, usually an item from another server. Not trusted. */
    OTHER_KEY
}
