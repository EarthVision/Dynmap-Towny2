package org.dynmap.towny.events;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.dynmap.markers.MarkerIcon;
import org.jetbrains.annotations.NotNull;

import eu.towny2.api.compatibility.CompatTown;

/**
 * Event called when the marker icon for a town is chosen.
 */
public class TownSetMarkerIconEvent extends Event {

    private static final HandlerList handlers = new HandlerList();
    private final CompatTown town;
    private MarkerIcon icon;

    public TownSetMarkerIconEvent(CompatTown town, MarkerIcon icon) {
        super(!Bukkit.getServer().isPrimaryThread());
        this.town = town;
        this.icon = icon;
    }

    public CompatTown getTown() {
        return town;
    }

    public void setIcon(MarkerIcon icon) {
        this.icon = icon;
    }

    public MarkerIcon getIcon() {
        return icon;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
