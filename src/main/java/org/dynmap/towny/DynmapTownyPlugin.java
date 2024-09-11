package org.dynmap.towny;
//ee
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;
import org.dynmap.markers.PlayerSet;
import org.dynmap.towny.listeners.DynmapTownyListener;
import org.dynmap.towny.mapupdate.AreaStyleHolder;
import org.dynmap.towny.mapupdate.UpdateTowns;
import org.dynmap.towny.settings.Settings;

import eu.towny.Towny;
import eu.towny.api.compatibility.CompatNation;
import eu.towny.api.compatibility.CompatResident;
import eu.towny.api.compatibility.CompatTown;
import eu.towny.api.compatibility.CompatibilityLayer;

public class DynmapTownyPlugin extends JavaPlugin {
    
    private static final String REQUIRED_TOWNY_VERSION = "0.100.0.0";
    private static final Logger LOG = Logger.getLogger("DynmapTowny");
    private static DynmapTownyPlugin plugin;
    private UpdateTowns townUpdater;
    private DynmapAPI dynmapAPI;
    private MarkerAPI markerAPI;
    private MarkerSet markerSet;
    private final Map<String, AreaMarker> areaMarkers = new HashMap<>();
    private final Map<String, Marker> markers = new HashMap<>();

    private Towny townyPlugin;
    private CompatibilityLayer compatLayer;

    @Override
    public void onLoad() {
        plugin = this;
    }

    @Override
    public void onEnable() {
        LOG.info("Initializing DynmapTowny.");

        PluginManager pm = getServer().getPluginManager();

        Plugin dynmap = pm.getPlugin("dynmap");
        if (dynmap == null || !dynmap.isEnabled()) {
            LOG.severe("Cannot find dynmap, check your logs to see if it enabled properly?!");
            pm.disablePlugin(this);
            return;
        }

        Plugin towny = pm.getPlugin("Towny2");
        if (towny == null || !towny.isEnabled()) {
            LOG.severe("Cannot find Towny2, check your logs to see if it enabled properly?!");
            pm.disablePlugin(this);
            return;
        }

        dynmapAPI = (DynmapAPI) dynmap;
        townyPlugin = (Towny) towny;
        compatLayer = townyPlugin.getCompatibilityLayer();

        if (!townyVersionCheck()) {
            LOG.severe("Towny version does not meet required minimum version: " + REQUIRED_TOWNY_VERSION);
            pm.disablePlugin(this);
            return;
        }

        LOG.info("Towny version " + towny.getDescription().getVersion() + " found.");

        if (!loadConfig()) {
            pm.disablePlugin(this);
            return;
        }

        new DynmapTownyListener(pm, this);

        activate();

        Plugin townychat = pm.getPlugin("TownyChat");
        if (townychat != null && townychat.isEnabled()) {
            dynmapAPI.setDisableChatToWebProcessing(true);
            Settings.setUsingTownyChat(true);
            LOG.info("TownyChat detected: disabling normal chat-to-web processing in Dynmap");
        }
    }

    private boolean loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        try {
            Settings.loadConfig();
        } catch (Exception e) {
            e.printStackTrace();
            LOG.severe("Config.yml failed to load! Disabling!");
            return false;
        }
        LOG.info("Config.yml loaded successfully.");
        return true;
    }

    private void activate() {
        markerAPI = dynmapAPI.getMarkerAPI();
        if (markerAPI == null) {
            LOG.severe("Error loading dynmap marker API!");
            return;
        }

        if (markerSet != null) {
            markerSet.deleteMarkerSet();
            markerSet = null;
        }

        if (!initializeMarkerSet()) {
            LOG.severe("Error creating Towny marker set!");
            return;
        }

        AreaStyleHolder.initialize();

        townUpdater = new UpdateTowns();

        long period = Math.max(15, Settings.getUpdatePeriod()) * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, new TownyUpdate(), 40, period);

        LOG.info("Version " + this.getDescription().getVersion() + " is activated");
    }

    private boolean initializeMarkerSet() {
        markerSet = markerAPI.getMarkerSet("towny.markerset");
        if (markerSet == null)
            markerSet = markerAPI.createMarkerSet("towny.markerset", Settings.getLayerName(), null, false);
        else
            markerSet.setMarkerSetLabel(Settings.getLayerName());

        if (markerSet == null)
            return false;

        int minzoom = Settings.getMinZoom();
        if (minzoom > 0)
            markerSet.setMinZoom(minzoom);
        markerSet.setLayerPriority(Settings.getLayerPriority());
        markerSet.setHideByDefault(Settings.getLayerHiddenByDefault());
        return true;
    }

    @Override
    public void onDisable() {
        if (markerSet != null) {
            markerSet.deleteMarkerSet();
            markerSet = null;
        }
        getServer().getScheduler().cancelTasks(this);
    }

    public static DynmapTownyPlugin getPlugin() {
        return plugin;
    }

    private boolean townyVersionCheck() {
        try {
            return compatLayer.isTownyVersionSupported(REQUIRED_TOWNY_VERSION);
        } catch (NoSuchMethodError e) {
            return false;
        }
    }

    public DynmapAPI getDynmapAPI() {
        return dynmapAPI;
    }

    public MarkerSet getMarkerSet() {
        return markerSet;
    }

    public Map<String, AreaMarker> getAreaMarkers() {
        return areaMarkers;
    }

    public Map<String, Marker> getMarkers() {
        return markers;
    }

    public CompatibilityLayer getCompatibilityLayer() {
        return compatLayer;
    }

    private class TownyUpdate implements Runnable {
        @Override
        public void run() {
            if (compatLayer != null) {
                try {
                    //LOG.info("Starting townUpdater.run()");
                    townUpdater.run();
                    //LOG.info("Completed townUpdater.run()");
                } catch (Exception e) {
                    LOG.severe("Error in townUpdater.run(): " + e.getMessage());
                    e.printStackTrace();
                }

                if (Settings.getPlayerVisibilityByTown()) {
                    compatLayer.getTowns().forEach(town -> {
                        try {
                            updateTown(town);
                        } catch (Exception e) {
                            LOG.severe("Error updating town " + town.getName() + ": " + e.getMessage());
                            e.printStackTrace();
                        }
                    });
                }

                if (Settings.getPlayerVisibilityByNation()) {
                    compatLayer.getNations().forEach(nation -> {
                        try {
                            updateNation(nation);
                        } catch (Exception e) {
                            LOG.severe("Error updating nation " + nation.getName() + ": " + e.getMessage());
                            e.printStackTrace();
                        }
                    });
                }
            }
        }
    }

    private void updateTown(CompatTown town) {
        Set<String> plids = town.getResidents().stream().map(CompatResident::getName).collect(Collectors.toSet());
        String setid = "towny.town." + town.getName();
        PlayerSet set = markerAPI.getPlayerSet(setid);
        if (set == null) {
            set = markerAPI.createPlayerSet(setid, true, plids, false);
            return;
        }
        set.setPlayers(plids);
    }

    private void updateNation(CompatNation nat) {
        Set<String> plids = nat.getTowns().stream()
            .flatMap(t -> t.getResidents().stream())
            .map(CompatResident::getName)
            .collect(Collectors.toSet());
        String setid = "towny.nation." + nat.getName();
        PlayerSet set = markerAPI.getPlayerSet(setid);
        if (set == null) {
            set = markerAPI.createPlayerSet(setid, true, plids, false);
            return;
        }
        set.setPlayers(plids);
    }
}
