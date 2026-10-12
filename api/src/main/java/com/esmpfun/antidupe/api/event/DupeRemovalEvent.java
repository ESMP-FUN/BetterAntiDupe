package com.esmpfun.antidupe.api.event;

import com.esmpfun.antidupe.api.model.Severity;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired just before automatic removal takes duped items from a player (only when
 * {@code auto_delete_dupes} is on). Cancel it to leave the items where they are.
 *
 * <p>Fired on the player's own thread (the main thread outside Folia).
 */
public final class DupeRemovalEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Material material;
    private final int amount;
    private final Severity severity;
    private boolean cancelled;

    public DupeRemovalEvent(Player player, Material material, int amount, Severity severity) {
        this.player = player;
        this.material = material;
        this.amount = amount;
        this.severity = severity;
    }

    public Player getPlayer() {
        return player;
    }

    public Material getMaterial() {
        return material;
    }

    /** The most it will take; fewer when the player no longer holds that many marked items. */
    public int getAmount() {
        return amount;
    }

    /** Severity of the alert that led to the removal. */
    public Severity getSeverity() {
        return severity;
    }

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
