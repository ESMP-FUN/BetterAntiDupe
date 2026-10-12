package com.esmpfun.antidupe.api.event;

import com.esmpfun.antidupe.api.model.Protection;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/**
 * Fired when a protection is about to stop something. Cancel it to let that one action through,
 * for example an action your own menu plugin performs on purpose.
 *
 * <p>Fired on the thread that owns the location (the main thread outside Folia).
 */
public final class DupeBlockedEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Protection protection;
    private final @Nullable Player player;
    private final Location location;
    private boolean cancelled;

    public DupeBlockedEvent(Protection protection, @Nullable Player player, Location location) {
        this.protection = protection;
        this.player = player;
        this.location = location;
    }

    public Protection getProtection() {
        return protection;
    }

    /** The player involved, or null for machines nobody is directly operating. */
    public @Nullable Player getPlayer() {
        return player;
    }

    /** Where it happened. A copy, so changing it changes nothing. */
    public Location getLocation() {
        return location.clone();
    }

    /** True means the protection steps aside this once. */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
