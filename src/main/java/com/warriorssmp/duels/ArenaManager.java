package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

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
 *
 * Spawn points are stored as plain values (world name, x/y/z/yaw/pitch)
 * rather than via Bukkit's own Location serialization - a real,
 * confirmed crash showed why: Bukkit deserializes a Location EAGERLY,
 * as part of the raw yaml.load(...) parse itself, and throws
 * IllegalArgumentException("unknown world") immediately if that
 * world isn't currently loaded - happening deep inside Bukkit's own
 * YAML parsing internals, before any of this class's own code runs,
 * and poisoning the ENTIRE file's parse (not just that one entry) since
 * it all happens in one yaml.load(...) call. That's exactly what
 * happens on a freshly reset server: this plugin loads before the
 * Duel/Gauntlet worlds do, so any saved spawn point immediately failed
 * to parse and took the whole plugin down with it in onEnable().
 * Storing plain values sidesteps this category of failure entirely -
 * a world that isn't loaded (yet, or ever again) becomes a simple,
 * graceful "skip this one point" instead of a hard crash.
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
            writeLocation(yaml, base + ".spawns." + i, spawns.get(i));
        }
        if (arena.getMonsterSpawnPoint() != null) {
            writeLocation(yaml, base + ".monsterSpawn", arena.getMonsterSpawnPoint());
        }
    }

    /** Writes a Location as plain values, never via Bukkit's own
     *  Location object serialization - see the class-level doc comment
     *  for why. */
    private void writeLocation(YamlConfiguration yaml, String path, Location loc) {
        yaml.set(path + ".world", loc.getWorld() != null ? loc.getWorld().getName() : null);
        yaml.set(path + ".x", loc.getX());
        yaml.set(path + ".y", loc.getY());
        yaml.set(path + ".z", loc.getZ());
        yaml.set(path + ".yaw", loc.getYaw());
        yaml.set(path + ".pitch", loc.getPitch());
    }

    /** Reads a Location back from plain values. Returns null (never
     *  throws) if this path doesn't exist, or if the world it names
     *  isn't currently loaded - both are normal, expected situations
     *  (an admin hasn't set this point yet, or the plugin started up
     *  before that world finished loading), not failures. */
    private Location readLocation(YamlConfiguration yaml, String path) {
        if (!yaml.contains(path + ".world")) return null;
        String worldName = yaml.getString(path + ".world");
        World world = worldName != null ? Bukkit.getWorld(worldName) : null;
        if (world == null) return null;
        double x = yaml.getDouble(path + ".x");
        double y = yaml.getDouble(path + ".y");
        double z = yaml.getDouble(path + ".z");
        float yaw = (float) yaml.getDouble(path + ".yaw");
        float pitch = (float) yaml.getDouble(path + ".pitch");
        return new Location(world, x, y, z, yaw, pitch);
    }

    /** Loads both arenas from arenas.yml, if it exists and parses
     *  correctly. A missing file is normal (first-ever startup) and
     *  silently leaves both arenas at their just-constructed defaults.
     *  A file that exists but fails to PARSE is not normal - that's a
     *  corrupted or hand-edited-wrong file, and silently discarding
     *  every previously-configured spawn point without a clear log
     *  message would be a confusing, hard-to-diagnose way to lose that
     *  setup. There's no player to message at plugin-startup time, so
     *  this logs as severely as the situation warrants instead. Only
     *  IOException/InvalidConfigurationException are expected here now
     *  that spawn points are stored as plain values rather than
     *  Bukkit-serialized Locations - see the class-level doc comment
     *  for why that distinction matters. */
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
            int skipped = 0;
            for (String spawnKey : keys) {
                Location loc = readLocation(yaml, base + ".spawns." + spawnKey);
                if (loc != null) {
                    arena.getSpawnPoints().add(loc);
                } else {
                    skipped++;
                }
            }
            if (skipped > 0) {
                plugin.getLogger().warning("Skipped " + skipped + " " + base + " spawn point(s) in arenas.yml - "
                        + "their world isn't currently loaded. They'll load normally once that world is "
                        + "available (e.g. on the next restart, if it just hadn't finished loading yet).");
            }
        }

        Location monsterSpawn = readLocation(yaml, base + ".monsterSpawn");
        if (monsterSpawn != null) {
            arena.setMonsterSpawnPoint(monsterSpawn);
        } else if (yaml.contains(base + ".monsterSpawn.world")) {
            plugin.getLogger().warning("Skipped the " + base + " monster spawn point in arenas.yml - its world "
                    + "isn't currently loaded.");
        }
    }
}
