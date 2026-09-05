package com.warriorssmp.duels;

import org.bukkit.DyeColor;
import org.bukkit.Material;

/**
 * The color assigned to each war team - "team 1 red, team 2 blue" and so
 * on for larger modes. Used to recolor any wool blocks in a kit per-team
 * at equip time, so a single kit works for every team without an admin
 * needing to build a separate colored copy of it for each side.
 */
public enum TeamColor {
    RED(DyeColor.RED),
    BLUE(DyeColor.BLUE),
    GREEN(DyeColor.GREEN),
    YELLOW(DyeColor.YELLOW),
    ORANGE(DyeColor.ORANGE),
    PURPLE(DyeColor.PURPLE),
    CYAN(DyeColor.CYAN),
    PINK(DyeColor.PINK),
    LIME(DyeColor.LIME),
    BLACK(DyeColor.BLACK);

    private final DyeColor dyeColor;

    TeamColor(DyeColor dyeColor) {
        this.dyeColor = dyeColor;
    }

    public DyeColor getDyeColor() {
        return dyeColor;
    }

    public Material getWoolMaterial() {
        return Material.valueOf(dyeColor.name() + "_WOOL");
    }

    public String getChatColorCode() {
        return switch (this) {
            case RED -> "&c";
            case BLUE -> "&9";
            case GREEN -> "&a";
            case YELLOW -> "&e";
            case ORANGE -> "&6";
            case PURPLE -> "&5";
            case CYAN -> "&b";
            case PINK -> "&d";
            case LIME -> "&a";
            case BLACK -> "&8";
        };
    }

    /** The actual ChatColor for this team, for contexts like
     *  sendMessage/setCustomName that DON'T auto-translate '&' codes the
     *  way GUI item names/lore do - using this instead of concatenating
     *  getChatColorCode()'s raw "&c"-style string directly is what
     *  prevents literal "&c" from ever showing up in chat instead of
     *  actually being red (a real, confirmed bug). */
    public org.bukkit.ChatColor getChatColor() {
        return switch (this) {
            case RED -> org.bukkit.ChatColor.RED;
            case BLUE -> org.bukkit.ChatColor.BLUE;
            case GREEN -> org.bukkit.ChatColor.GREEN;
            case YELLOW -> org.bukkit.ChatColor.YELLOW;
            case ORANGE -> org.bukkit.ChatColor.GOLD;
            case PURPLE -> org.bukkit.ChatColor.DARK_PURPLE;
            case CYAN -> org.bukkit.ChatColor.AQUA;
            case PINK -> org.bukkit.ChatColor.LIGHT_PURPLE;
            case LIME -> org.bukkit.ChatColor.GREEN;
            case BLACK -> org.bukkit.ChatColor.DARK_GRAY;
        };
    }

    /** The Nth team color, wrapping if there are ever more teams than
     *  colors defined (shouldn't happen for anything up to 10v10, but
     *  wrapping is safer than throwing). */
    public static TeamColor forTeamIndex(int index) {
        TeamColor[] all = values();
        return all[index % all.length];
    }
}
