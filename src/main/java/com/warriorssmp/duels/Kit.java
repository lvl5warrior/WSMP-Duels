package com.warriorssmp.duels;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A saved loadout: armor + inventory contents, snapshotted once by an admin
 * and then handed out fresh (cloned) to every player who selects it for a
 * match. Includes wool blocks for building, per the request - the specific
 * wool color used is up to whoever builds the kit, and team-color variants
 * (e.g. a red-wool version and a blue-wool version of the same kit) are just
 * two separate Kit objects an admin creates.
 */
public class Kit {

    private final UUID id;
    private String name;
    private Material icon;
    private ItemStack helmet;
    private ItemStack chestplate;
    private ItemStack leggings;
    private ItemStack boots;
    private ItemStack offhand;
    /** Always exactly 36 slots (0-35), matching PlayerInventory's main+hotbar
     *  storage contents - nulls for empty slots. */
    private final List<ItemStack> contents = new ArrayList<>();

    public Kit(UUID id, String name, Material icon) {
        this.id = id;
        this.name = name;
        this.icon = icon;
        for (int i = 0; i < 36; i++) contents.add(null);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Material getIcon() {
        return icon;
    }

    public void setIcon(Material icon) {
        this.icon = icon;
    }

    public ItemStack getHelmet() {
        return helmet;
    }

    public void setHelmet(ItemStack helmet) {
        this.helmet = helmet;
    }

    public ItemStack getChestplate() {
        return chestplate;
    }

    public void setChestplate(ItemStack chestplate) {
        this.chestplate = chestplate;
    }

    public ItemStack getLeggings() {
        return leggings;
    }

    public void setLeggings(ItemStack leggings) {
        this.leggings = leggings;
    }

    public ItemStack getBoots() {
        return boots;
    }

    public void setBoots(ItemStack boots) {
        this.boots = boots;
    }

    public ItemStack getOffhand() {
        return offhand;
    }

    public void setOffhand(ItemStack offhand) {
        this.offhand = offhand;
    }

    public List<ItemStack> getContents() {
        return contents;
    }
}
