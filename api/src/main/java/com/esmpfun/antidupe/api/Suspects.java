package com.esmpfun.antidupe.api;

import com.esmpfun.antidupe.api.model.SuspectInfo;
import java.util.Optional;
import java.util.UUID;

/** Read-only view of how suspicious players look. Staff decisions stay with staff. */
public interface Suspects {

    /** Current suspicion from 0 (none) to 100 (certain). It fades over time. */
    double suspicion(UUID player);

    /** True when the player is on the suspect list that {@code /adp ledger suspects} shows. */
    boolean isSuspect(UUID player);

    /** What was found on a suspect, or empty when they are not on the list. */
    Optional<SuspectInfo> info(UUID player);
}
