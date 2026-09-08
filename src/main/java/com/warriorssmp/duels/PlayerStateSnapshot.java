package com.warriorssmp.duels;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;

/**
 * A full snapshot of a player's state before entering a duel, so it can be
 * restored exactly afterward - this is the mechanism that keeps kit items
 * from leaking out and the player's own items from leaking in. Taken the
 * instant a match actually starts (not at challenge time), so anything
 * that happened before the match began is preserved correctly.
 */
public class PlayerStateSnapshot {

    private final ItemStack[] contents;
    private final ItemStack[] armor;
    private final ItemStack offhand;
    private final double health;
    private final int foodLevel;
    private final float saturation;
    private final int level;
    private final float exp;
    private final GameMode gameMode;
    private final Location location;
    private final List<PotionEffect> potionEffects;
    /** The MAX_HEALTH attribute's base value at capture time - needed
     *  because The Gauntlet's VITALITY buff permanently raises this
     *  (Attribute base values, unlike potion effects, don't expire or
     *  get cleared on their own), so without explicitly restoring it a
     *  player would keep extra max health forever after leaving a run. */
    private final double maxHealthBaseValue;

    private PlayerStateSnapshot(ItemStack[] contents, ItemStack[] armor, ItemStack offhand,
                                 double health, int foodLevel, float saturation, int level, float exp,
                                 GameMode gameMode, Location location, List<PotionEffect> potionEffects,
                                 double maxHealthBaseValue) {
        this.contents = contents;
        this.armor = armor;
        this.offhand = offhand;
        this.health = health;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.level = level;
        this.exp = exp;
        this.gameMode = gameMode;
        this.location = location;
        this.potionEffects = potionEffects;
        this.maxHealthBaseValue = maxHealthBaseValue;
    }

    public static PlayerStateSnapshot capture(Player player) {
        PlayerInventory inv = player.getInventory();
        var maxHealthAttr = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return new PlayerStateSnapshot(
                inv.getContents().clone(),
                inv.getArmorContents().clone(),
                inv.getItemInOffHand().clone(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getLevel(),
                player.getExp(),
                player.getGameMode(),
                player.getLocation().clone(),
                new ArrayList<>(player.getActivePotionEffects()),
                maxHealthAttr != null ? maxHealthAttr.getBaseValue() : 20.0
        );
    }

    /** Restores everything captured, including teleporting back to the
     *  pre-duel location. Clears the player's current inventory and any
     *  potion effects first, so nothing from the duel (kit leftovers,
     *  combat potion effects) survives into the restored state. Also
     *  resets MAX_HEALTH back to its captured base value before setting
     *  health, so a Gauntlet run's VITALITY buff (or anything else that
     *  permanently alters max health) never lingers once the player is
     *  actually out of that run. */
    public void restore(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(null);

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }

        inv.setContents(contents);
        inv.setArmorContents(armor);
        inv.setItemInOffHand(offhand);

        player.setGameMode(gameMode);
        var maxHealthAttr = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            maxHealthAttr.setBaseValue(maxHealthBaseValue);
        }
        player.setHealth(Math.min(health, maxHealthAttr != null ? maxHealthAttr.getValue() : health));
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setLevel(level);
        player.setExp(exp);
        try {
            player.teleport(location);
        } catch (Exception e) {
            // A location whose world has since been unloaded (or deleted
            // outright) would otherwise throw here and skip the potion
            // effects restoration below entirely, with no indication to
            // the player of what went wrong or that their location is
            // now wherever they happened to be standing instead of back
            // where the match found them.
            player.sendMessage(org.bukkit.ChatColor.RED + "Couldn't teleport you back to your original location "
                    + "(its world may no longer be loaded) - everything else was restored normally.");
        }

        for (PotionEffect effect : potionEffects) {
            player.addPotionEffect(effect);
        }
    }
}
