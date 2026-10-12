package com.esmpfun.antidupe.api;

import java.util.Optional;
import org.bukkit.Bukkit;

/**
 * Finds the running API. BetterAntiDupe registers it with Bukkit's services manager while it
 * enables and removes it when it disables.
 */
public final class BetterAntiDupeProvider {

    private BetterAntiDupeProvider() {
    }

    /**
     * The running API.
     *
     * @throws IllegalStateException when BetterAntiDupe is not installed or not enabled
     */
    public static BetterAntiDupeApi get() {
        return find().orElseThrow(() -> new IllegalStateException(
            "BetterAntiDupe is not enabled. Add it to depend or softdepend in your plugin.yml, "
                + "and check isAvailable() first if it is optional."));
    }

    /** The running API, or empty when BetterAntiDupe is not installed or not enabled. */
    public static Optional<BetterAntiDupeApi> find() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(BetterAntiDupeApi.class));
    }

    /** True when BetterAntiDupe is installed and enabled. */
    public static boolean isAvailable() {
        return find().isPresent();
    }
}
