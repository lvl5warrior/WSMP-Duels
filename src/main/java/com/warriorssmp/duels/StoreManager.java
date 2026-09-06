package com.warriorssmp.duels;

import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * A simple store where players spend Honor on admin-configured items.
 * Persists to store.yml the same way kits persist to kits.yml.
 */
public class StoreManager {

    private final DuelsPlugin plugin;
    private final PlayerDataManager playerDataManager;
    private final File dataFile;
    private final Map<UUID, StoreItem> items = new LinkedHashMap<>();

    public StoreManager(DuelsPlugin plugin, PlayerDataManager playerDataManager) {
        this.plugin = plugin;
        this.playerDataManager = playerDataManager;
        this.dataFile = new File(plugin.getDataFolder(), "store.yml");
        load();
    }

    public Collection<StoreItem> getAll() {
        return items.values();
    }

    public StoreItem get(UUID id) {
        return items.get(id);
    }

    /** Adds the item and saves. Returns whether the save actually
     *  succeeded - on failure, the item is rolled back out of memory too,
     *  so in-memory state never gets ahead of what's actually on disk. */
    public boolean add(StoreItem item) {
        items.put(item.getId(), item);
        if (!save()) {
            items.remove(item.getId());
            return false;
        }
        return true;
    }

    /** Removes the item and saves. Returns whether the save actually
     *  succeeded - on failure, the item is put back in memory so it
     *  isn't silently lost from the running server while still existing
     *  on disk. */
    public boolean remove(UUID id) {
        StoreItem removed = items.remove(id);
        if (removed == null) return true; // wasn't there to begin with
        if (!save()) {
            items.put(id, removed);
            return false;
        }
        return true;
    }

    /** Attempts to buy a store item for the given player - checks their
     *  Honor balance, deducts the price (never touching lifetime
     *  honor/rank, same as any other honor spend), and gives the reward
     *  item, dropping it at their feet instead if their inventory is
     *  full so a purchase can never just vanish. */
    public void purchase(Player buyer, UUID itemId) {
        StoreItem item = items.get(itemId);
        if (item == null) {
            buyer.sendMessage(err("That item isn't available anymore."));
            return;
        }

        PlayerDuelData data = playerDataManager.get(buyer.getUniqueId());
        if (data.getHonor() < item.getPrice()) {
            buyer.sendMessage(err("You need " + item.getPrice() + " honor - you have " + data.getHonor() + "."));
            return;
        }

        data.addHonor(-item.getPrice());
        // A failed save here is a disk/IO problem, not a data-correctness
        // one - the deduction is still correctly reflected in memory and
        // will be written out by the next successful save (or on server
        // shutdown), so the purchase still proceeds rather than blocking
        // gameplay on what's very likely a transient issue. The player is
        // still told, since silently proceeding while their balance
        // change might not survive a crash would be worse.
        boolean saved = playerDataManager.save();

        ItemStack reward = item.getReward().clone();
        var leftover = buyer.getInventory().addItem(reward);
        StringBuilder message = new StringBuilder(ChatColor.GREEN.toString());
        if (!leftover.isEmpty()) {
            for (ItemStack extra : leftover.values()) {
                buyer.getWorld().dropItem(buyer.getLocation(), extra);
            }
            message.append("Bought '").append(item.getName()).append("' for ").append(item.getPrice())
                    .append(" honor - your inventory was full, so it dropped at your feet.");
        } else {
            message.append("Bought '").append(item.getName()).append("' for ").append(item.getPrice()).append(" honor.");
        }
        buyer.sendMessage(message.toString());
        if (!saved) {
            buyer.sendMessage(err("Warning: your new honor balance couldn't be saved to disk right now "
                    + "(check console) - it's still correct for this session, but could be lost if the "
                    + "server crashes before the next successful save."));
        }
    }

    public boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (StoreItem item : items.values()) {
            String base = "items." + item.getId();
            yaml.set(base + ".name", item.getName());
            yaml.set(base + ".price", item.getPrice());
            yaml.set(base + ".reward", item.getReward());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(dataFile);
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save store.yml", e);
            return false;
        }
    }

    /** Loads every store item from store.yml, if it exists and parses
     *  correctly. A missing file is normal (no items created yet). A
     *  file that exists but fails to PARSE is not - that's a corrupted
     *  or hand-edited-wrong file, and silently starting with an empty
     *  store instead of clearly logging why would be a confusing way to
     *  lose every item an admin has configured. There's no player to
     *  message at plugin-startup time, so this logs as severely as the
     *  situation warrants instead. */
    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(dataFile);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load store.yml - it may be corrupted or contain "
                    + "invalid YAML. NO store items were loaded. The broken file was left in place at "
                    + dataFile.getPath() + " in case it can be manually recovered.", e);
            return;
        }

        var section = yaml.getConfigurationSection("items");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String base = "items." + key;
                String name = yaml.getString(base + ".name", "Unnamed Item");
                int price = yaml.getInt(base + ".price", 0);
                ItemStack reward = yaml.getItemStack(base + ".reward");
                if (reward == null) continue; // malformed entry, no reward to sell
                items.put(id, new StoreItem(id, name, reward, price));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipped malformed store item entry: " + key);
            }
        }
    }

    private String ok(String msg) {
        return ChatColor.GREEN + msg;
    }

    private String err(String msg) {
        return ChatColor.RED + msg;
    }
}
