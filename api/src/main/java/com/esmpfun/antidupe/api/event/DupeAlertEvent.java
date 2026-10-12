package com.esmpfun.antidupe.api.event;

import com.esmpfun.antidupe.api.model.AlertKind;
import com.esmpfun.antidupe.api.model.Severity;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before an alert reaches staff, webhooks and automatic removal. Cancel it to drop the
 * alert completely, for example when your plugin knows the items are legitimate.
 *
 * <p>Always fired off the main thread.
 */
public final class DupeAlertEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final Material material;
    private final AlertKind kind;
    private final Severity severity;
    private final int excess;
    private final String details;
    private final boolean afterUncleanShutdown;
    private boolean cancelled;

    public DupeAlertEvent(UUID playerId, String playerName, Material material, AlertKind kind,
                          Severity severity, int excess, String details, boolean afterUncleanShutdown) {
        super(true);
        this.playerId = playerId;
        this.playerName = playerName;
        this.material = material;
        this.kind = kind;
        this.severity = severity;
        this.excess = excess;
        this.details = details;
        this.afterUncleanShutdown = afterUncleanShutdown;
    }

    /** The player the alert is about. */
    public UUID getPlayerId() {
        return playerId;
    }

    /** Their name as it was when the alert was raised. */
    public String getPlayerName() {
        return playerName;
    }

    /** The item involved, or AIR when the alert is not about one kind of item. */
    public Material getMaterial() {
        return material;
    }

    public AlertKind getKind() {
        return kind;
    }

    public Severity getSeverity() {
        return severity;
    }

    /** How many items automatic removal may take back; 0 when nothing can be taken. */
    public int getExcess() {
        return excess;
    }

    /** The alert text in English, as it appears in the console. */
    public String getDetails() {
        return details;
    }

    /** True when the server crashed shortly before, which can cause this alert by itself. */
    public boolean isAfterUncleanShutdown() {
        return afterUncleanShutdown;
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
