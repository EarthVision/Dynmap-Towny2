package org.dynmap.towny.events;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import eu.towny.compatibility.CompatTown;

/**
 * Called when building town flags for display on the dynmap
 */
public class BuildTownFlagsEvent extends Event {
    private static HandlerList handlers = new HandlerList();
    private final CompatTown town;
    private final List<String> flags;

    public BuildTownFlagsEvent(CompatTown town, List<String> flags) {
        super(!Bukkit.getServer().isPrimaryThread());
        this.town = town;
        this.flags = flags;
    }

    public CompatTown getTown() {
        return town;
    }

    public List<String> getFlags() {
        return flags;
    }

    @NotNull
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
