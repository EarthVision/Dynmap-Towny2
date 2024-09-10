package org.dynmap.towny;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
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
import eu.towny.compatibility.CompatibilityLayer;
import eu.towny.compatibility.CompatTown;
import eu.towny.compatibility.CompatNation;
import eu.towny.compatibility.CompatResident;

public class DynmapTownyPlugin extends JavaPlugin {
	
	private static final String requiredTownyVersion = "0.100.0.0";
	private static Logger log;
	private static DynmapTownyPlugin plugin;
	private final TaskScheduler scheduler;
	private ScheduledTask task;
	private UpdateTowns townUpdater;
	private DynmapAPI dynmapAPI;
	private MarkerAPI markerAPI;
	private MarkerSet markerSet;
	private Map<String, AreaMarker> areaMarkers = new HashMap<String, AreaMarker>();
	private Map<String, Marker> markers = new HashMap<String, Marker>();

	private Towny townyPlugin;
	private CompatibilityLayer compatLayer;

	public DynmapTownyPlugin() {
		this.scheduler = isFoliaClassPresent() ? new FoliaTaskScheduler(this) : new BukkitTaskScheduler(this);
		plugin = this;
	}

	@Override
	public void onLoad() {
		log = this.getLogger();
	}

	public void onEnable() {
		info("Initializing DynmapTowny.");

		Plugin dynmap = null;
		Plugin towny = null;
		Plugin townychat = null;
		PluginManager pm = getServer().getPluginManager();

		/* Get dynmap */
		dynmap = pm.getPlugin("dynmap");
		if (dynmap == null || !dynmap.isEnabled()) {
			severe("Cannot find dynmap, check your logs to see if it enabled properly?!");
			return;
		}

		/* Get Towny */
		towny = pm.getPlugin("Towny2");
		if (towny == null || !towny.isEnabled()) {
			severe("Cannot find Towny2, check your logs to see if it enabled properly?!");
			return;
		}

		/* Get DynmapAPI */
		dynmapAPI = (DynmapAPI) dynmap;

		townyPlugin = (Towny) towny;
		compatLayer = townyPlugin.getCompatibilityLayer();

		// Make sure the Towny version is new enough.
		if (!townyVersionCheck()) {
			getLogger().severe("Towny version does not meet required minimum version: " + requiredTownyVersion);
			this.getServer().getPluginManager().disablePlugin(this);
			return;
		} else {
			getLogger().info("Towny version " + towny.getDescription().getVersion() + " found.");
		}

		if (!loadConfig()) {
			onDisable();
			return;
		}

		/* Register the event listener. */
		new DynmapTownyListener(pm, this);

		/* Kick off the rest of the plugin. */
		activate();

		/* Check if TownyChat is present. */
		townychat = pm.getPlugin("TownyChat");
		if (townychat != null && townychat.isEnabled()) {
			dynmapAPI.setDisableChatToWebProcessing(true);
			Settings.setUsingTownyChat(true);
			info("TownyChat detect: disabling normal chat-to-web processing in Dynmap");
		}
	}

	private boolean loadConfig() {
		try {
			Settings.loadConfig();
		} catch (TownyInitException e) {
			e.printStackTrace();
			severe("Config.yml failed to load! Disabling!");
			return false;
		}
		info("Config.yml loaded successfully.");
		return true;
	}

	private void activate() {
		markerAPI = dynmapAPI.getMarkerAPI();
		if (markerAPI == null) {
			severe("Error loading dynmap marker API!");
			return;
		}

		/* We might be reloading and have a set already in use. */
		if (markerSet != null) {
			markerSet.deleteMarkerSet();
			markerSet = null;
		}

		/* Initialize the critical Towny MarkerSet. */
		if (!initializeMarkerSet()) {
			severe("Error creating Towny marker set!");
			return;
		}

		/* Initialize the Styles from the config for use by the TownyUpdate task. */
		AreaStyleHolder.initialize();

		/* Create our UpdateTowns instance, used to actually draw the towns. */
		townUpdater = new UpdateTowns();

		/* Set up update job - based on period */
		long per = Math.max(15, Settings.getUpdatePeriod()) * 20L;
		task = scheduler.runAsyncRepeating(new TownyUpdate(), 40, per);

		info("version " + this.getDescription().getVersion() + " is activated");
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

	public void onDisable() {
		if (markerSet != null) {
			markerSet.deleteMarkerSet();
			markerSet = null;
		}
		task.cancel();
	}

	private static boolean isFoliaClassPresent() {
		try {
			Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	public static DynmapTownyPlugin getPlugin() {
		return plugin;
	}

	public static String getPrefix() {
		return "[" + plugin.getName() + "]";
	}

	private boolean townyVersionCheck() {
		try {
			return compatLayer.isTownyVersionSupported(requiredTownyVersion);
		} catch (NoSuchMethodError e) {
			return false;
		}
	}

	public String getVersion() {
		return this.getDescription().getVersion();
	}

	public static void info(String msg) {
		log.log(Level.INFO, msg);
	}

	public static void severe(String msg) {
		log.log(Level.SEVERE, msg);
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

	private class TownyUpdate implements Runnable {
		public void run() {
			if (compatLayer != null) {
				scheduler.runAsync(townUpdater);

				if (Settings.getPlayerVisibilityByTown())
					compatLayer.getTowns().forEach(t -> updateTown(t));

				if (Settings.getPlayerVisibilityByNation())
					compatLayer.getNations().forEach(n -> updateNation(n));
			}
		}
	}

	private void updateTown(CompatTown town) {
		Set<String> plids = town.getResidents().stream().map(r -> r.getName()).collect(Collectors.toSet());
		String setid = "towny.town." + town.getName();
		PlayerSet set = markerAPI.getPlayerSet(setid); /* See if set exists */
		if (set == null) {
			set = markerAPI.createPlayerSet(setid, true, plids, false);
			info("Added player visibility set '" + setid + "' for town " + town.getName());
			return;
		}
		set.setPlayers(plids);
	}

	private void updateNation(CompatNation nat) {
		Set<String> plids = nat.getTowns().stream()
			.flatMap(t -> t.getResidents().stream())
			.map(r -> r.getName())
			.collect(Collectors.toSet());
		String setid = "towny.nation." + nat.getName();
		PlayerSet set = markerAPI.getPlayerSet(setid); /* See if set exists */
		if (set == null) {
			set = markerAPI.createPlayerSet(setid, true, plids, false);
			info("Added player visibility set '" + setid + "' for nation " + nat.getName());
			return;
		}
		set.setPlayers(plids);
	}
}
