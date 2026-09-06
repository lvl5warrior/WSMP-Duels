package com.warriorssmp.duels;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
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

    /** Adds the kit and saves. Returns whether the save actually
     *  succeeded - on failure, the kit is rolled back out of memory too,
     *  so in-memory state never gets ahead of what's actually on disk
     *  (which would otherwise silently discard the kit entirely the next
     *  time something else triggers a save that succeeds). */
    public boolean add(Kit kit) {
        kits.put(kit.getId(), kit);
        if (!save()) {
            kits.remove(kit.getId());
            return false;
        }
        return true;
    }

    /** Removes the kit and saves. Returns whether the save actually
     *  succeeded - on failure, the kit is put back in memory so it isn't
     *  silently lost from the running server while still existing on
     *  disk. */
    public boolean remove(UUID id) {
        Kit removed = kits.remove(id);
        if (removed == null) return true; // wasn't there to begin with
        if (!save()) {
            kits.put(id, removed);
            return false;
        }
        return true;
    }

    public boolean save() {
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
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save kits.yml", e);
            return false;
        }
    }

    /** Loads every kit from kits.yml, if it exists and parses correctly.
     *  A missing file is normal (no kits created yet). A file that
     *  exists but fails to PARSE is not - that's a corrupted or
     *  hand-edited-wrong file, and silently starting with zero kits
     *  instead of clearly logging why would be a confusing way to lose
     *  every kit an admin has built. There's no player to message at
     *  plugin-startup time, so this logs as severely as the situation
     *  warrants instead. */
    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(dataFile);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load kits.yml - it may be corrupted or contain "
                    + "invalid YAML. NO kits were loaded. The broken file was left in place at "
                    + dataFile.getPath() + " in case it can be manually recovered.", e);
            return;
        }

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
