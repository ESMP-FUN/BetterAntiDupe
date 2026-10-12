package com.esmpfun.antidupe.api;

/**
 * Entry point of the BetterAntiDupe API.
 *
 * <p>Every method is safe to call from any thread unless its own documentation says otherwise.
 */
public interface BetterAntiDupeApi {

    /** Raised whenever the API gains methods or events, so callers can check before using them. */
    int API_LEVEL = 1;

    /** The {@link #API_LEVEL} of the BetterAntiDupe that is actually installed. */
    int apiLevel();

    /** The installed BetterAntiDupe version, for example {@code "4.7.0"}. */
    String pluginVersion();

    /** False when item tracking could not start; the duping-machine protections still run. */
    boolean isTracking();

    /** Recording and querying what players earned. */
    ItemLedger ledger();

    /** Reading and removing the owner mark on items. */
    OwnershipMarks ownership();

    /** How suspicious players currently look. */
    Suspects suspects();
}
