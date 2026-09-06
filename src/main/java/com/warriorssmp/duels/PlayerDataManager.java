package com.warriorssmp.duels;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class PlayerDataManager {

    private final DuelsPlugin plugin;
    private final File dataFile;
    private final Map<UUID, PlayerDuelData> data = new HashMap<>();

    public PlayerDataManager(DuelsPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "playerdata.yml");
        load();
    }

    public PlayerDuelData get(UUID playerId) {
        return data.computeIfAbsent(playerId, id -> new PlayerDuelData());
    }

    public Map<UUID, PlayerDuelData> getAll() {
        return data;
    }

    /** Rewrites the entire playerdata.yml from what's currently in
     *  memory. Returns whether it actually succeeded, so callers with a
     *  player in context (honor changes from a purchase, a duel result)
     *  can tell them their balance change might not have persisted,
     *  rather than the failure only ever showing up in console. */
    public boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerDuelData> entry : data.entrySet()) {
            String base = "players." + entry.getKey();
            PlayerDuelData d = entry.getValue();
            yaml.set(base + ".honor", d.getHonor());
            yaml.set(base + ".lifetimeHonor", d.getLifetimeHonor());
            yaml.set(base + ".wins", d.getWins());
            yaml.set(base + ".losses", d.getLosses());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(dataFile);
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save playerdata.yml", e);
            return false;
        }
    }

    /** Loads every player's data from playerdata.yml, if it exists and
     *  parses correctly. A missing file is normal (fresh server, nobody
     *  has played yet). A file that exists but fails to PARSE is not -
     *  that's a corrupted or hand-edited-wrong file, and silently
     *  starting with everyone's honor/wins/losses reset to zero instead
     *  of clearly logging why would be a serious, confusing data loss.
     *  There's no player to message at plugin-startup time, so this logs
     *  as severely as the situation warrants instead. */
    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(dataFile);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load playerdata.yml - it may be corrupted or "
                    + "contain invalid YAML. EVERY PLAYER'S honor, lifetime honor, wins, and losses will "
                    + "start at ZERO until this is fixed. The broken file was left in place at "
                    + dataFile.getPath() + " - do NOT let the server save over it until it's been recovered, "
                    + "or the original data is gone for good.", e);
            return;
        }

        var section = yaml.getConfigurationSection("players");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String base = "players." + key;
                PlayerDuelData d = new PlayerDuelData();
                d.setHonor(yaml.getInt(base + ".honor", 0));
                d.setLifetimeHonor(yaml.getInt(base + ".lifetimeHonor", 0));
                d.setWins(yaml.getInt(base + ".wins", 0));
                d.setLosses(yaml.getInt(base + ".losses", 0));
                data.put(id, d);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipped malformed player data entry: " + key);
            }
        }
    }
}
