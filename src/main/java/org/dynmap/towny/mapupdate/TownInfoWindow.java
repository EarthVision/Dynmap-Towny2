package org.dynmap.towny.mapupdate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.dynmap.towny.events.BuildTownFlagsEvent;
import org.dynmap.towny.settings.Settings;

import eu.towny2.api.compatibility.CompatResident;
import eu.towny2.api.compatibility.CompatTown;

public class TownInfoWindow {

    public static String formatInfoWindow(CompatTown town) {
        String v = "<div class=\"regioninfo\">" + Settings.getTownInfoWindow() + "</div>";
        v = v.replace("%regionname%", town.getName());
        v = v.replace("%playerowners%", town.getMayor() != null ? town.getMayor().getName() : "");
        
        List<String> residentNames = town.getResidents().stream()
            .map(CompatResident::getName)
            .collect(Collectors.toList());
        
        if (residentNames.size() > 34) {
            residentNames = residentNames.subList(0, 35);
            residentNames.add("... and more");
        }
        
        String res = String.join(", ", residentNames);
        v = v.replace("%playermembers%", res);
        
        String mgrs = town.getResidentsWithRank("assistant").stream()
            .map(CompatResident::getName)
            .collect(Collectors.joining(", "));
        v = v.replace("%playermanagers%", mgrs);

        String dispNames = town.getResidents().stream()
            .map(r -> r.isOnline() ? r.getPlayer().getDisplayName() : r.getName())
            .collect(Collectors.joining(", "));
        v = v.replace("%residentdisplaynames%", dispNames);

        v = v.replace("%residentcount%", String.valueOf(town.getResidents().size()));
        v = v.replace("%founded%", town.getFoundedDate());
        v = v.replace("%board%", town.getBoard());
        v = v.replace("%towntrusted%", town.getTrustedResidents().isEmpty() ? "None" : 
            town.getTrustedResidents().stream().map(CompatResident::getName).collect(Collectors.joining(", ")));

        if (town.isUsingEconomy()) {
            v = v.replace("%tax%", town.getTaxes());
            v = v.replace("%bank%", town.getBankBalance());
            v = v.replace("%upkeep%", town.getUpkeep());
        }

        String nation = town.getNation() != null ? town.getNation().getName() : Settings.noNationSlug();
        v = v.replace("%nation%", nation);

        String natStatus = "";
        if (town.isCapital()) {
            natStatus = "Capital of " + nation;
        } else if (town.getNation() != null) {
            natStatus = "Member of " + nation;
        }
        v = v.replace("%nationstatus%", natStatus);

        v = v.replace("%public%", getEnabledDisabled(town.isPublic()));
        v = v.replace("%peaceful%", getEnabledDisabled(town.isNeutral()));
        v = v.replace("%conquered%", getEnabledDisabled(town.isConquered()));
        
        List<String> flags = new ArrayList<>();
        flags.add("PVP: " + getEnabledDisabled(town.isPVP()));
        flags.add("Mobs: " + getEnabledDisabled(town.hasMobs()));
        flags.add("Explosions: " + getEnabledDisabled(town.isExplosion()));
        flags.add("Fire Spread: " + getEnabledDisabled(town.isFire()));

        if (town.isRuined()) {
            flags.add("Ruined: " + town.getRuinedTime());
        }

        BuildTownFlagsEvent buildTownFlagsEvent = new BuildTownFlagsEvent(town, flags);
        Bukkit.getPluginManager().callEvent(buildTownFlagsEvent);

        v = v.replace("%flags%", String.join("<br/>", buildTownFlagsEvent.getFlags()));

        return v;
    }

    private static String getEnabledDisabled(boolean b) {
        return b ? "Enabled" : "Disabled";
    }
}