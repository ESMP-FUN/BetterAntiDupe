package com.esmpfun.antidupe.api.model;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of a balance check.
 *
 * @param skipped    true when the check could not run right now, see {@code skipReason}
 * @param skipReason why it was skipped, in English, or null when it ran
 * @param extra      every kind of item the player held more of than the record explains
 */
public record CheckResult(
    UUID player,
    long timestamp,
    boolean skipped,
    @Nullable String skipReason,
    List<Discrepancy> extra
) {
    public CheckResult {
        extra = List.copyOf(extra);
    }

    /** True when the check ran and found more than the record explains. */
    public boolean foundExtra() {
        return !skipped && !extra.isEmpty();
    }
}
