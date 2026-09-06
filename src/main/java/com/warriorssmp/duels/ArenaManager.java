package com.warriorssmp.duels;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Holds exactly two arenas - one static Duel map and one static
 * Gauntlet map, matching the "two permanent, static maps" design: no
 * cloning, no per-match copies, and no support for defining more than
 * one arena of either type. Both exist from the moment the plugin
 * loads, pointed at world "world" by default; an admin still needs to
 * point each one at the real map's world and set its spawn points
 * (via the spawn wand) before matches of that type can actually start.
 */
public class ArenaManager {

    private final DuelsPlugin plugin;
    private final File dataFile;
    private final Arena duelArena;
    private final Arena gauntletArena;

    public ArenaManager(DuelsPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "arenas.yml");
        this.duelArena = new Arena(UUID.nameUUIDFromBytes("duel-map".getBytes()), "Duel Map", Arena.Type.DUEL, "world");
        this.gauntletArena = new Arena(UUID.nameUUIDFromBytes("gauntlet-map".getBytes()), "Gauntlet Map", Arena.Type.GAUNTLET, "world");
        load();
    }

    public Arena getDuelArena() {
        return duelArena;
    }

    public Arena getGauntletArena() {
        return gauntletArena;
    }

    public Arena getArena(Arena.Type type) {
        return type == Arena.Type.DUEL ? duelArena : gauntletArena;
    }

    /** Saves both arenas to arenas.yml. Returns whether it actually
     *  succeeded, so callers with a player in context (the admin GUI, the
     *  spawn wand) can tell them directly rather than the failure only
     *  ever showing up in console. */
    public boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        saveArena(yaml, duelArena);
        saveArena(yaml, gauntletArena);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(dataFile);
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save arenas.yml", e);
            return false;
        }
    }

    private void saveArena(YamlConfiguration yaml, Arena arena) {
        String base = arena.getType().name().toLowerCase();
        yaml.set(base + ".world", arena.getWorldName());
        List<Location> spawns = arena.getSpawnPoints();
        for (int i = 0; i < spawns.size(); i++) {
            yaml.set(base + ".spawns." + i, spawns.get(i));
        }
        if (arena.getMonsterSpawnPoint() != null) {
            yaml.set(base + ".monsterSpawn", arena.getMonsterSpawnPoint());
        }
    }

    /** Loads both arenas from arenas.yml, if it exists and parses
     *  correctly. A missing file is normal (first-ever startup) and
     *  silently leaves both arenas at their just-constructed defaults.
     *  A file that exists but fails to PARSE is not normal - that's a
     *  corrupted or hand-edited-wrong file, and silently discarding
     *  every previously-configured spawn point without a clear log
     *  message would be a confusing, hard-to-diagnose way to lose that
     *  setup. There's no player to message at plugin-startup time, so
     *  this logs as severely as the situation warrants instead. */
    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(dataFile);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load arenas.yml - it may be corrupted or "
                    + "contain invalid YAML. BOTH the Duel and Gauntlet maps will start with NO spawn "
                    + "points configured until an admin re-sets them with the spawn wand. The broken file "
                    + "was left in place at " + dataFile.getPath() + " in case it can be manually recovered.", e);
            return;
        }
        loadArena(yaml, duelArena);
        loadArena(yaml, gauntletArena);
    }

    private void loadArena(YamlConfiguration yaml, Arena arena) {
        String base = arena.getType().name().toLowerCase();
        String world = yaml.getString(base + ".world");
        if (world != null) arena.setWorldName(world);

        var spawnsSection = yaml.getConfigurationSection(base + ".spawns");
        if (spawnsSection != null) {
            List<String> keys = new ArrayList<>(spawnsSection.getKeys(false));
            keys.sort(String::compareTo);
            for (String spawnKey : keys) {
                try {
                    Location loc = yaml.getLocation(base + ".spawns." + spawnKey);
                    if (loc != null) arena.getSpawnPoints().add(loc);
                } catch (IllegalArgumentException e) {
                    // A spawn point referencing a world that isn't currently
                    // loaded throws here rather than just returning null -
                    // skip that one entry rather than losing every other
                    // already-valid spawn point over it.
                    plugin.getLogger().warning("Skipped an unreadable " + base + " spawn point ('" + spawnKey
                            + "') in arenas.yml - its world may not be loaded.");
                }
            }
        }

        try {
            Location monsterSpawn = yaml.getLocation(base + ".monsterSpawn");
            if (monsterSpawn != null) arena.setMonsterSpawnPoint(monsterSpawn);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Skipped an unreadable " + base + " monster spawn point in arenas.yml - "
                    + "its world may not be loaded.");
        }
    }
}
