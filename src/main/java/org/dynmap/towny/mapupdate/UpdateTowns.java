package org.dynmap.towny.mapupdate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerIcon;
import org.dynmap.markers.MarkerSet;
import org.dynmap.towny.DynmapTownyPlugin;
import org.dynmap.towny.events.BuildTownMarkerDescriptionEvent;
import org.dynmap.towny.events.TownRenderEvent;
import org.dynmap.towny.events.TownSetMarkerIconEvent;
import org.dynmap.towny.settings.Settings;

import eu.towny2.api.compatibility.CompatTown;
import eu.towny2.api.compatibility.CompatNation;
import eu.towny2.api.compatibility.CompatResident;
import eu.towny2.Towny;

public class UpdateTowns implements Runnable {

    private final int TOWNBLOCKSIZE = 16; // Assuming this is the default size, adjust if necessary
    private final DynmapTownyPlugin plugin = DynmapTownyPlugin.getPlugin();
    private final MarkerSet set = plugin.getMarkerSet();
    private Map<String, AreaMarker> existingAreaMarkers = plugin.getAreaMarkers();
    private Map<String, Marker> existingMarkers = plugin.getMarkers();

    private final AreaStyle defstyle = AreaStyleHolder.getDefaultStyle();
    private final Map<String, AreaStyle> cusstyle = AreaStyleHolder.getCustomStyles();
    private final Map<String, AreaStyle> nationstyle = AreaStyleHolder.getNationStyles();

    enum direction {XPLUS, ZPLUS, XMINUS, ZMINUS};

    @Override
    public void run() {
        Map<String, AreaMarker> newmap = new HashMap<String, AreaMarker>(); /* Build new map */
        Map<String, Marker> newmark = new HashMap<String, Marker>(); /* Build new map */

        /* Loop through towns */
        for (CompatTown t : plugin.getCompatibilityLayer().getTowns()) {
            try {
                handleTown(t, newmap, newmark);
            } catch (Exception e) {
                plugin.getLogger().severe("Error processing town " + t.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        /* Now, review old maps - anything left is removed */
        existingAreaMarkers.values().forEach(a -> a.deleteMarker());
        existingMarkers.values().forEach(m -> m.deleteMarker());

        /* And replace with new map */
        existingAreaMarkers = newmap;
        existingMarkers = newmark;
    }

    /* Handle specific town */
    private void handleTown(CompatTown town, Map<String, AreaMarker> newWorldNameAreaMarkerMap, Map<String, Marker> newWorldNameMarkerMap) throws Exception {
        String townName = town.getName();
        int poly_index = 0; /* Index of polygon for when a town has multiple shapes. */

        /* Get the Town's Chunks */
        Collection<String> townChunks = town.getChunks();
        if (townChunks.isEmpty()) {
            return;
        }

        /* Build popup */
        BuildTownMarkerDescriptionEvent event = new BuildTownMarkerDescriptionEvent(town);
        Bukkit.getPluginManager().callEvent(event);
        String infoWindowPopup = event.getDescription();

        HashMap<String, TileFlags> worldNameShapeMap = new HashMap<String, TileFlags>();
        LinkedList<String> chunksToDraw = new LinkedList<String>(townChunks);
        String currentWorld = null;
        TileFlags currentShape = null;
        boolean vis = false;
        /* Loop through chunks: set flags on blockmaps for worlds */
        for(String chunkStr : chunksToDraw) {
            if (chunkStr == null) {
                continue;
            }
            String[] parts = chunkStr.split(":");
            if (parts.length != 3) {
                continue;
            }
            String worldName = parts[0];
            int chunkX = Integer.parseInt(parts[1]);
            int chunkZ = Integer.parseInt(parts[2]);
            if(!worldName.equals(currentWorld)) { /* Not same world */
                vis = isVisible(townName, worldName);  /* See if visible */
                if(vis) {  /* Only accumulate for visible areas */
                    currentShape = worldNameShapeMap.get(worldName);  /* Find existing */
                    if(currentShape == null) {
                        currentShape = new TileFlags();
                        worldNameShapeMap.put(worldName, currentShape);   /* Add fresh one */
                    }
                }
                currentWorld = worldName;
            }
            if(vis) {
                currentShape.setFlag(chunkX, chunkZ, true); /* Set flag for chunk */
            }
        }
        /* Loop through until we don't find more areas */
        while(!chunksToDraw.isEmpty()) {
            LinkedList<String> ourChunks = new LinkedList<String>();
            LinkedList<String> chunksLeftToDraw = new LinkedList<String>();
            TileFlags ourShape = null;
            int minx = Integer.MAX_VALUE;
            int minz = Integer.MAX_VALUE;
            String ourWorld = null;
            for(String chunkStr : chunksToDraw) {
                if (chunkStr == null) {
                    continue;
                }
                String[] parts = chunkStr.split(":");
                if (parts.length != 3) {
                    continue;
                }
                String worldName = parts[0];
                int chunkX = Integer.parseInt(parts[1]);
                int chunkZ = Integer.parseInt(parts[2]);
                if(ourShape == null) {   /* If not started, switch to world for this chunk first */
                    if(!worldName.equals(currentWorld)) {
                        currentWorld = worldName;
                        currentShape = worldNameShapeMap.get(currentWorld);
                    }
                    ourWorld = worldName;
                }
                /* If we need to start shape, and this chunk is not part of one yet */
                if((ourShape == null) && currentShape.getFlag(chunkX, chunkZ)) {
                    ourShape = new TileFlags();  /* Create map for shape */
                    floodFillTarget(currentShape, ourShape, chunkX, chunkZ);   /* Copy shape */
                    ourChunks.add(chunkStr); /* Add it to our chunk list */
                    minx = chunkX; minz = chunkZ;
                }
                /* If shape found, and we're in it, add to our chunk list */
                else if((ourShape != null) && (worldName.equals(ourWorld)) &&
                    (ourShape.getFlag(chunkX, chunkZ))) {
                    ourChunks.add(chunkStr);
                    if(chunkX < minx) {
                        minx = chunkX; minz = chunkZ;
                    }
                    else if((chunkX == minx) && (chunkZ < minz)) {
                        minz = chunkZ;
                    }
                }
                else {  /* Else, keep it in the list for the next polygon */
                    chunksLeftToDraw.add(chunkStr);
                }
            }
            chunksToDraw = chunksLeftToDraw; /* Replace list (null if no more to process) */
            if(ourShape != null) {
                poly_index = traceTownOutline(town, newWorldNameAreaMarkerMap, poly_index, infoWindowPopup, ourWorld, ourShape, minx, minz);
            }
        }

        drawTownMarkers(town, newWorldNameMarkerMap, townName, infoWindowPopup);
    }

    private int traceTownOutline(CompatTown town, Map<String, AreaMarker> newWorldNameMarkerMap, int poly_index,
            String infoWindowPopup, String worldName, TileFlags ourShape, int minx, int minz) throws Exception {

        double[] x;
        double[] z;
        /* Trace outline of blocks - start from minx, minz going to x+ */
        int init_x = minx;
        int init_z = minz;
        int cur_x = minx;
        int cur_z = minz;
        direction dir = direction.XPLUS;
        ArrayList<int[]> linelist = new ArrayList<int[]>();
        linelist.add(new int[] { init_x, init_z } ); // Add start point
        while((cur_x != init_x) || (cur_z != init_z) || (dir != direction.ZMINUS)) {
            switch(dir) {
                case XPLUS: /* Segment in X+ direction */
                    if(!ourShape.getFlag(cur_x+1, cur_z)) { /* Right turn? */
                        linelist.add(new int[] { cur_x+1, cur_z }); /* Finish line */
                        dir = direction.ZPLUS;  /* Change direction */
                    }
                    else if(!ourShape.getFlag(cur_x+1, cur_z-1)) {  /* Straight? */
                        cur_x++;
                    }
                    else {  /* Left turn */
                        linelist.add(new int[] { cur_x+1, cur_z }); /* Finish line */
                        dir = direction.ZMINUS;
                        cur_x++; cur_z--;
                    }
                    break;
                case ZPLUS: /* Segment in Z+ direction */
                    if(!ourShape.getFlag(cur_x, cur_z+1)) { /* Right turn? */
                        linelist.add(new int[] { cur_x+1, cur_z+1 }); /* Finish line */
                        dir = direction.XMINUS;  /* Change direction */
                    }
                    else if(!ourShape.getFlag(cur_x+1, cur_z+1)) {  /* Straight? */
                        cur_z++;
                    }
                    else {  /* Left turn */
                        linelist.add(new int[] { cur_x+1, cur_z+1 }); /* Finish line */
                        dir = direction.XPLUS;
                        cur_x++; cur_z++;
                    }
                    break;
                case XMINUS: /* Segment in X- direction */
                    if(!ourShape.getFlag(cur_x-1, cur_z)) { /* Right turn? */
                        linelist.add(new int[] { cur_x, cur_z+1 }); /* Finish line */
                        dir = direction.ZMINUS;  /* Change direction */
                    }
                    else if(!ourShape.getFlag(cur_x-1, cur_z+1)) {  /* Straight? */
                        cur_x--;
                    }
                    else {  /* Left turn */
                        linelist.add(new int[] { cur_x, cur_z+1 }); /* Finish line */
                        dir = direction.ZPLUS;
                        cur_x--; cur_z++;
                    }
                    break;
                case ZMINUS: /* Segment in Z- direction */
                    if(!ourShape.getFlag(cur_x, cur_z-1)) { /* Right turn? */
                        linelist.add(new int[] { cur_x, cur_z }); /* Finish line */
                        dir = direction.XPLUS;  /* Change direction */
                    }
                    else if(!ourShape.getFlag(cur_x-1, cur_z-1)) {  /* Straight? */
                        cur_z--;
                    }
                    else {  /* Left turn */
                        linelist.add(new int[] { cur_x, cur_z }); /* Finish line */
                        dir = direction.XMINUS;
                        cur_x--; cur_z--;
                    }
                    break;
            }
        }
        /* Build information for specific area */
        String polyid = town.getName() + "__" + poly_index;
        int sz = linelist.size();
        x = new double[sz];
        z = new double[sz];
        for(int i = 0; i < sz; i++) {
            int[] line = linelist.get(i);
            x[i] = (double)line[0] * (double)TOWNBLOCKSIZE;
            z[i] = (double)line[1] * (double)TOWNBLOCKSIZE;
        }
        /* Find existing one */
        AreaMarker areaMarker = existingAreaMarkers.remove(polyid); /* Existing area? */
        if(areaMarker == null) {
            areaMarker = set.createAreaMarker(polyid, town.getName(), false, worldName, x, z, false);
            if(areaMarker == null) {
                areaMarker = set.findAreaMarker(polyid);
                if (areaMarker == null) {
                    throw new Exception("Error adding area marker " + polyid);
                }
            }
        }
        else {
            areaMarker.setCornerLocations(x, z); /* Replace corner locations */
            areaMarker.setLabel(town.getName());   /* Update label */
        }
        /* Set popup */
        areaMarker.setDescription(infoWindowPopup);
        /* Set line and fill properties */
        addStyle(town, areaMarker);

        /* Fire an event allowing other plugins to alter the AreaMarker */
        TownRenderEvent renderEvent = new TownRenderEvent(town, areaMarker); 
        Bukkit.getPluginManager().callEvent(renderEvent);
        areaMarker = renderEvent.getAreaMarker();

        /* Add to map */
        newWorldNameMarkerMap.put(polyid, areaMarker);
        poly_index++;
        return poly_index;
    }

    /**
     * Find all contiguous blocks, set in target and clear in source
     */
    private static int floodFillTarget(TileFlags src, TileFlags dest, int x, int y) {
        int cnt = 0;
        ArrayDeque<int[]> stack = new ArrayDeque<int[]>();
        stack.push(new int[] { x, y });

        while (stack.isEmpty() == false) {
            int[] nxt = stack.pop();
            x = nxt[0];
            y = nxt[1];
            if (src.getFlag(x, y)) { /* Set in src */
                src.setFlag(x, y, false); /* Clear source */
                dest.setFlag(x, y, true); /* Set in destination */
                cnt++;
                if (src.getFlag(x + 1, y))
                    stack.push(new int[] { x + 1, y });
                if (src.getFlag(x - 1, y))
                    stack.push(new int[] { x - 1, y });
                if (src.getFlag(x, y + 1))
                    stack.push(new int[] { x, y + 1 });
                if (src.getFlag(x, y - 1))
                    stack.push(new int[] { x, y - 1 });
            }
        }
        return cnt;
    }

    private static boolean isVisible(String id, String worldname) {

        if (Settings.visibleRegionsAreSet() &&
            (Settings.getVisibleRegions().contains("world:" + worldname) == false || Settings.getVisibleRegions().contains(id) == false))
                    return false;

        if (Settings.hiddenRegionsAreSet() &&
            (Settings.getHiddenRegions().contains(id) || Settings.getHiddenRegions().contains("world:" + worldname)))
                    return false;
        return true;
    }

    private void addStyle(CompatTown town, AreaMarker m) {
        AreaStyle as = cusstyle.get(town.getName());
        AreaStyle ns = nationstyle.get(getNationNameOrNone(town));
        
        m.setLineStyle(defstyle.getStrokeWeight(as, ns), defstyle.getStrokeOpacity(as, ns), defstyle.getStrokeColor(as, ns));
        
        int fillColor = defstyle.getFillColor(as, ns, town);
        
        m.setFillStyle(defstyle.getFillOpacity(as, ns), fillColor);
        
        double y = defstyle.getY(as, ns);
        m.setRangeY(y, y);
        m.setBoostFlag(defstyle.getBoost(as, ns));

        // We're dealing with something that has a custom AreaStyle applied via the
        // custstyle or nationstyle in the config.yml, do not apply dynamic colours.
        if (as != null || ns != null)
            return;

        //Read dynamic colors from town/nation objects
        if(Settings.usingDynamicTownColours() || Settings.usingDynamicNationColours()) {
            try {
                String colorHexCode; 
                Integer townFillColorInteger = null;
                Integer townBorderColorInteger = null;

                //CALCULATE FILL COLOUR
                if (Settings.usingDynamicTownColours()) {
                    //Here we know the server is using town colors. This takes top priority for fill
                    colorHexCode = town.getMapColorHexCode();              
                    if(colorHexCode != null && !colorHexCode.isEmpty()) {
                        //If town has a color, use it
                        townFillColorInteger = Integer.parseInt(colorHexCode, 16);
                        townBorderColorInteger = townFillColorInteger;
                    }                
                } else {
                    //Here we know the server is using nation colors
                    colorHexCode = town.getNationMapColorHexCode();              
                    if(colorHexCode != null && !colorHexCode.isEmpty()) {
                        //If nation has a color, use it
                        townFillColorInteger = Integer.parseInt(colorHexCode, 16);                
                    }                            
                }

                //CALCULATE BORDER COLOR
                if (Settings.usingDynamicNationColours()) {
                    //Here we know the server is using nation colors. This takes top priority for border
                    colorHexCode = town.getNationMapColorHexCode();              
                    if(colorHexCode != null && !colorHexCode.isEmpty()) {
                        //If nation has a color, use it
                        townBorderColorInteger = Integer.parseInt(colorHexCode, 16);                
                    }                                
                } else {
                    //Here we know the server is using town colors
                    colorHexCode = town.getMapColorHexCode();              
                    if(colorHexCode != null && !colorHexCode.isEmpty()) {
                        //If town has a color, use it
                        townBorderColorInteger = Integer.parseInt(colorHexCode, 16);                
                    }                
                }

                //SET FILL COLOR
                if(townFillColorInteger != null) {
                    //Set fill style
                    double fillOpacity = m.getFillOpacity();
                    m.setFillStyle(fillOpacity, townFillColorInteger);
                }

                //SET BORDER COLOR
                if(townBorderColorInteger != null) {
                    //Set stroke style
                    double strokeOpacity = m.getLineOpacity();
                    int strokeWeight = m.getLineWeight();
                    m.setLineStyle(strokeWeight, strokeOpacity, townBorderColorInteger);
                }   

            } catch (Exception ex) {
                plugin.getLogger().warning("Error setting dynamic colors for town " + town.getName() + ": " + ex.getMessage());
            }
        }
    }


    /*
     * Town Marker Drawing Methods
     */

    private void drawTownMarkers(CompatTown town, Map<String, Marker> newWorldNameMarkerMap, String townName, String desc) {
        /* Now, add marker for home block */
        String homeblock = town.getHomeblock();
        
        if (homeblock != null && isVisible(townName, homeblock.split(":")[0])) {
            MarkerIcon townHomeBlockIcon = getMarkerIcon(town);

            /* Fire an event allowing other plugins to alter the MarkerIcon */
            TownSetMarkerIconEvent iconEvent = new TownSetMarkerIconEvent(town, townHomeBlockIcon);
            Bukkit.getPluginManager().callEvent(iconEvent);
            townHomeBlockIcon = iconEvent.getIcon();

            if (townHomeBlockIcon != null)
                drawHomeBlockSpawn(newWorldNameMarkerMap, townName, desc, homeblock, townHomeBlockIcon);
        }

        // Outpost handling might need to be adjusted based on how Towny2 handles outposts
        if (town.getOutpostChunksById().size() > 0)
            drawOutpostIcons(town, newWorldNameMarkerMap, desc);
    }

    private MarkerIcon getMarkerIcon(CompatTown town) {
        if (town.isRuined())
            return defstyle.getRuinIcon();;

        AreaStyle as = cusstyle.get(town.getName());
        AreaStyle ns = nationstyle.get(getNationNameOrNone(town));

        return town.isCapital() ? defstyle.getCapitalMarker(as, ns) : defstyle.getHomeMarker(as, ns);
    }

        private String getNationNameOrNone(CompatTown town) {
        return town.hasNation() ? town.getNation().getName() : "_none_";
    }

    private void drawHomeBlockSpawn(Map<String, Marker> newWorldNameMarkerMap, String townName, String desc, String homeblock, MarkerIcon ico) {
        String markid = townName + "__home";
        Marker home = existingMarkers.remove(markid);
        String[] parts = homeblock.split(":");
        String worldName = parts[0];
        int chunkX = Integer.parseInt(parts[1]);
        int chunkZ = Integer.parseInt(parts[2]);
        double xx = TOWNBLOCKSIZE * chunkX + (TOWNBLOCKSIZE / 2);
        double zz = TOWNBLOCKSIZE * chunkZ + (TOWNBLOCKSIZE / 2);
        if(home == null) {
            home = set.createMarker(markid, townName, worldName, xx, 64, zz, ico, false);
            if (home == null)
                return;
        } else {
            home.setLocation(worldName, xx, 64, zz);
            home.setLabel(townName); /* Update label */
            home.setMarkerIcon(ico);
        }
        /* Set popup */
        home.setDescription(desc);

        newWorldNameMarkerMap.put(markid, home);
    }

    private void drawOutpostIcons(CompatTown town, Map<String, Marker> newWorldNameMarkerMap, String desc) {
        MarkerIcon outpostIco = Settings.getOutpostIcon();
        Map<Integer, String> outposts = town.getOutpostChunksById();
        for (Map.Entry<Integer, String> entry : outposts.entrySet()) {
            int i = entry.getKey();
            String chunkStr = entry.getValue();
            String[] parts = chunkStr.split(":");
            String worldName = parts[0];
            int chunkX = Integer.parseInt(parts[1]);
            int chunkZ = Integer.parseInt(parts[2]);

            if (!isVisible(town.getName(), worldName))
                continue;

            double xx = TOWNBLOCKSIZE * chunkX + (TOWNBLOCKSIZE / 2);
            double zz = TOWNBLOCKSIZE * chunkZ + (TOWNBLOCKSIZE / 2);
            String outpostName = town.getName() + "_Outpost_" + i;
            String outpostMarkerID = outpostName;
            Marker outpostMarker = existingMarkers.remove(outpostMarkerID);
            if (outpostMarker == null) {
                outpostMarker = set.createMarker(outpostMarkerID, outpostName, worldName, xx, 64, zz, outpostIco, true);
                if (outpostMarker == null)
                    continue;
            } else {
                outpostMarker.setLocation(worldName, xx, 64, zz);
                outpostMarker.setLabel(outpostName);
                outpostMarker.setMarkerIcon(outpostIco);
            }
            outpostMarker.setDescription(desc);
            newWorldNameMarkerMap.put(outpostMarkerID, outpostMarker);
        }
    }

}
