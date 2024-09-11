package org.dynmap.towny.events;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.dynmap.markers.AreaMarker;
import org.jetbrains.annotations.NotNull;

import eu.towny.api.compatibility.CompatTown;

/**
 * Called when Dynmap-Towny has made a town which will be rendered.
 */
public class TownRenderEvent extends Event {
    private static HandlerList handlers = new HandlerList();
    private final CompatTown town;
    private final AreaMarker areaMarker;

    public TownRenderEvent(CompatTown town, AreaMarker areaMarker) {
        super(!Bukkit.getServer().isPrimaryThread());
        this.town = town;
        this.areaMarker = areaMarker;
    }

    public CompatTown getTown() {
        return town;
    }

    public AreaMarker getAreaMarker() {
        return areaMarker;
    }

    @NotNull
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
