package com.warriorssmp.duels;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class KitManager {

    private final DuelsPlugin plugin;
    private final File dataFile;
    private final Map<UUID, Kit> kits = new LinkedHashMap<>();

    public KitManager(DuelsPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "kits.yml");
        load();
    }

    public Collection<Kit> getAll() {
        return kits.values();
    }

    public Kit get(UUID id) {
        return kits.get(id);
    }

    public Kit getByName(String name) {
        for (Kit kit : kits.values()) {
            if (kit.getName().equalsIgnoreCase(name)) return kit;
        }
        return null;
    }

    public void add(Kit kit) {
        kits.put(kit.getId(), kit);
        save();
    }

    public void remove(UUID id) {
        kits.remove(id);
        save();
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Kit kit : kits.values()) {
            String base = "kits." + kit.getId();
            yaml.set(base + ".name", kit.getName());
            yaml.set(base + ".icon", kit.getIcon().name());
            if (kit.getHelmet() != null) yaml.set(base + ".helmet", kit.getHelmet());
            if (kit.getChestplate() != null) yaml.set(base + ".chestplate", kit.getChestplate());
            if (kit.getLeggings() != null) yaml.set(base + ".leggings", kit.getLeggings());
            if (kit.getBoots() != null) yaml.set(base + ".boots", kit.getBoots());
            if (kit.getOffhand() != null) yaml.set(base + ".offhand", kit.getOffhand());

            List<ItemStack> contents = kit.getContents();
            for (int i = 0; i < contents.size(); i++) {
                if (contents.get(i) != null) {
                    yaml.set(base + ".contents." + i, contents.get(i));
                }
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save kits.yml", e);
        }
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        var section = yaml.getConfigurationSection("kits");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String base = "kits." + key;
                String name = yaml.getString(base + ".name", "Unnamed Kit");
                Material icon = Material.valueOf(yaml.getString(base + ".icon", "IRON_SWORD"));

                Kit kit = new Kit(id, name, icon);
                kit.setHelmet(yaml.getItemStack(base + ".helmet"));
                kit.setChestplate(yaml.getItemStack(base + ".chestplate"));
                kit.setLeggings(yaml.getItemStack(base + ".leggings"));
                kit.setBoots(yaml.getItemStack(base + ".boots"));
                kit.setOffhand(yaml.getItemStack(base + ".offhand"));

                var contentsSection = yaml.getConfigurationSection(base + ".contents");
                if (contentsSection != null) {
                    for (String slotKey : contentsSection.getKeys(false)) {
                        try {
                            int slot = Integer.parseInt(slotKey);
                            if (slot >= 0 && slot < 36) {
                                kit.getContents().set(slot, yaml.getItemStack(base + ".contents." + slotKey));
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }

                kits.put(id, kit);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipped malformed kit entry: " + key);
            }
        }
    }
}
