package com.warriorssmp.duels;

import org.bukkit.ChatColor;
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
 * A simple store where players spend Honor (the same spendable currency
 * duels/wars award) on admin-configured items. Persists to store.yml the
 * same way kits persist to kits.yml.
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

    public void add(StoreItem item) {
        items.put(item.getId(), item);
        save();
    }

    public void remove(UUID id) {
        items.remove(id);
        save();
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
        playerDataManager.save();

        ItemStack reward = item.getReward().clone();
        var leftover = buyer.getInventory().addItem(reward);
        if (!leftover.isEmpty()) {
            for (ItemStack extra : leftover.values()) {
                buyer.getWorld().dropItem(buyer.getLocation(), extra);
            }
            buyer.sendMessage(ok("Bought '" + item.getName() + "' for " + item.getPrice()
                    + " honor - your inventory was full, so it dropped at your feet."));
        } else {
            buyer.sendMessage(ok("Bought '" + item.getName() + "' for " + item.getPrice() + " honor."));
        }
    }

    public void save() {
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
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save store.yml", e);
        }
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
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
