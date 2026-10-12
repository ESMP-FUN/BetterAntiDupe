package com.esmpfun.antidupe.api.model;

import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;

/**
 * What was found on a suspect.
 *
 * @param violations  how many times they were flagged
 * @param firstFlagged when they were first flagged, in epoch milliseconds
 * @param lastFlagged  when they were last flagged, in epoch milliseconds
 * @param totalExcess  extra items found across their recent flags, per item
 */
public record SuspectInfo(
    UUID player,
    String name,
    int violations,
    long firstFlagged,
    long lastFlagged,
    Map<Material, Integer> totalExcess
) {
    public SuspectInfo {
        totalExcess = Map.copyOf(totalExcess);
    }
}
