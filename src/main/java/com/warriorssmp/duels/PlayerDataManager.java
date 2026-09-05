package com.warriorssmp.duels;

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

    public void save() {
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
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save playerdata.yml", e);
        }
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
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
