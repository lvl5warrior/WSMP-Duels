package com.warriorssmp.duels;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class ArenaManager {

    private final DuelsPlugin plugin;
    private final File dataFile;
    private final Map<UUID, Arena> arenas = new LinkedHashMap<>();

    public ArenaManager(DuelsPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "arenas.yml");
        load();
    }

    public Collection<Arena> getAll() {
        return arenas.values();
    }

    public Arena get(UUID id) {
        return arenas.get(id);
    }

    /** Arenas of a given type that are actually configured enough to use -
     *  for DUEL type, that means at least 2 spawn points are set. */
    public List<Arena> getReadyArenas(Arena.Type type) {
        List<Arena> result = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            if (arena.getType() == type && arena.isReadyForDuel()) {
                result.add(arena);
            }
        }
        return result;
    }

    /** GAUNTLET-type arenas with at least one spawn point set per party
     *  member. */
    public List<Arena> getReadyGauntletArenas(int partySize) {
        List<Arena> result = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            if (arena.getType() == Arena.Type.GAUNTLET && arena.isReadyForGauntlet(partySize)) {
                result.add(arena);
            }
        }
        return result;
    }

    /** WAR-type arenas with at least one spawn point set for every team
     *  from 0 up to teamCount. */
    public List<Arena> getReadyWarArenas(int teamCount) {
        List<Arena> result = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            if (arena.getType() == Arena.Type.WAR && arena.isReadyForWar(teamCount)) {
                result.add(arena);
            }
        }
        return result;
    }

    /** WAR-type arenas ready for Capture the Flag specifically - spawns
     *  AND flag locations set for every team from 0 up to teamCount. */
    public List<Arena> getReadyCtfArenas(int teamCount) {
        List<Arena> result = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            if (arena.getType() == Arena.Type.WAR && arena.isReadyForCtf(teamCount)) {
                result.add(arena);
            }
        }
        return result;
    }

    public void add(Arena arena) {
        arenas.put(arena.getId(), arena);
        save();
    }

    public void remove(UUID id) {
        arenas.remove(id);
        save();
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Arena arena : arenas.values()) {
            String base = "arenas." + arena.getId();
            yaml.set(base + ".name", arena.getName());
            yaml.set(base + ".type", arena.getType().name());
            yaml.set(base + ".world", arena.getWorldName());

            List<Location> spawns = arena.getSpawnPoints();
            for (int i = 0; i < spawns.size(); i++) {
                yaml.set(base + ".spawns." + i, spawns.get(i));
            }

            for (Map.Entry<Integer, List<Location>> entry : arena.getTeamSpawnPoints().entrySet()) {
                List<Location> teamSpawns = entry.getValue();
                for (int i = 0; i < teamSpawns.size(); i++) {
                    yaml.set(base + ".teamSpawns." + entry.getKey() + "." + i, teamSpawns.get(i));
                }
            }

            for (Map.Entry<Integer, Location> entry : arena.getFlagLocations().entrySet()) {
                yaml.set(base + ".flags." + entry.getKey(), entry.getValue());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save arenas.yml", e);
        }
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        var section = yaml.getConfigurationSection("arenas");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String base = "arenas." + key;
                String name = yaml.getString(base + ".name", "Unnamed Arena");
                Arena.Type type = Arena.Type.valueOf(yaml.getString(base + ".type", "DUEL"));
                String world = yaml.getString(base + ".world", "world");

                Arena arena = new Arena(id, name, type, world);

                var spawnsSection = yaml.getConfigurationSection(base + ".spawns");
                if (spawnsSection != null) {
                    List<String> keys = new ArrayList<>(spawnsSection.getKeys(false));
                    keys.sort(String::compareTo);
                    for (String spawnKey : keys) {
                        Location loc = yaml.getLocation(base + ".spawns." + spawnKey);
                        if (loc != null) arena.getSpawnPoints().add(loc);
                    }
                }

                var teamSpawnsSection = yaml.getConfigurationSection(base + ".teamSpawns");
                if (teamSpawnsSection != null) {
                    for (String teamKey : teamSpawnsSection.getKeys(false)) {
                        try {
                            int teamIndex = Integer.parseInt(teamKey);
                            var thisTeamSection = yaml.getConfigurationSection(base + ".teamSpawns." + teamKey);
                            if (thisTeamSection == null) continue;
                            List<String> spawnKeys = new ArrayList<>(thisTeamSection.getKeys(false));
                            spawnKeys.sort(String::compareTo);
                            for (String spawnKey : spawnKeys) {
                                Location loc = yaml.getLocation(base + ".teamSpawns." + teamKey + "." + spawnKey);
                                if (loc != null) arena.getTeamSpawns(teamIndex).add(loc);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }

                var flagsSection = yaml.getConfigurationSection(base + ".flags");
                if (flagsSection != null) {
                    for (String teamKey : flagsSection.getKeys(false)) {
                        try {
                            int teamIndex = Integer.parseInt(teamKey);
                            Location loc = yaml.getLocation(base + ".flags." + teamKey);
                            if (loc != null) arena.setFlagLocation(teamIndex, loc);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }

                arenas.put(id, arena);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipped malformed arena entry: " + key);
            }
        }
    }
}
