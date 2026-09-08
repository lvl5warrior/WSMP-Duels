package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Shows who's currently playing and who's waiting in line for each of
 * the plugin's two static maps (1v1 duel, Gauntlet) - each map only ever
 * has one match/run active at a time, so this is the one place to see
 * both what's happening right now and the full queue behind it.
 */
public class QueueGUI implements Listener {

    private static final String MAIN_TITLE = ChatColor.translateAlternateColorCodes('&', "&e&lMap Queues");

    private final DuelsPlugin plugin;
    private final DuelManager duelManager;
    private final GauntletManager gauntletManager;

    public QueueGUI(DuelsPlugin plugin, DuelManager duelManager, GauntletManager gauntletManager) {
        this.plugin = plugin;
        this.duelManager = duelManager;
        this.gauntletManager = gauntletManager;
    }

    public void openMain(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, MAIN_TITLE);
        fillBorder(gui);

        // ---- Duel map column ----
        boolean duelOccupied = duelManager.isMapOccupied();
        gui.setItem(10, GuiUtil.namedItem(Material.IRON_SWORD, "&c&lDuel Map",
                duelOccupied ? "&7Status: &aOccupied" : "&7Status: &7Free"));

        if (duelOccupied) {
            DuelMatch match = duelManager.getCurrentMatch();
            gui.setItem(11, GuiUtil.namedItem(Material.PLAYER_HEAD, "&f&lNow Playing",
                    "&7" + nameOf(match.getPlayer1()) + " &7vs &f" + nameOf(match.getPlayer2())));
        } else {
            gui.setItem(11, GuiUtil.namedItem(Material.BARRIER, "&7Nobody playing right now.", ""));
        }

        List<String> duelQueue = duelManager.getQueueDescriptions();
        if (duelQueue.isEmpty()) {
            gui.setItem(13, GuiUtil.namedItem(Material.BARRIER, "&7Queue is empty.", ""));
        } else {
            int slot = 13;
            for (int i = 0; i < duelQueue.size() && slot <= 16; i++, slot++) {
                gui.setItem(slot, GuiUtil.namedItem(Material.PAPER, "&e#" + (i + 1) + " &f" + duelQueue.get(i), ""));
            }
        }

        // ---- Gauntlet map column ----
        boolean gauntletOccupied = gauntletManager.isMapOccupied();
        gui.setItem(28, GuiUtil.namedItem(Material.WITHER_SKELETON_SKULL, "&5&lGauntlet Map",
                gauntletOccupied ? "&7Status: &aOccupied" : "&7Status: &7Free"));

        if (gauntletOccupied) {
            GauntletRun run = gauntletManager.getCurrentRun();
            List<String> names = new java.util.ArrayList<>();
            for (var id : run.getPlayers()) {
                var p = Bukkit.getPlayer(id);
                if (p != null) names.add(p.getName());
            }
            gui.setItem(29, GuiUtil.namedItem(Material.PLAYER_HEAD, "&f&lNow Playing",
                    "&7Wave " + run.getWave() + " - " + String.join(", ", names)));
        } else {
            gui.setItem(29, GuiUtil.namedItem(Material.BARRIER, "&7Nobody playing right now.", ""));
        }

        List<String> gauntletQueue = gauntletManager.getQueueDescriptions();
        if (gauntletQueue.isEmpty()) {
            gui.setItem(31, GuiUtil.namedItem(Material.BARRIER, "&7Queue is empty.", ""));
        } else {
            int slot = 31;
            for (int i = 0; i < gauntletQueue.size() && slot <= 34; i++, slot++) {
                gui.setItem(slot, GuiUtil.namedItem(Material.PAPER, "&e#" + (i + 1) + " &f" + gauntletQueue.get(i), ""));
            }
        }

        gui.setItem(49, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        player.openInventory(gui);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(MAIN_TITLE)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        if (event.getRawSlot() == 49) {
            plugin.getDuelGUI().openMain(player);
        }
    }

    private String nameOf(java.util.UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) return online.getName();
        String offlineName = Bukkit.getOfflinePlayer(playerId).getName();
        return offlineName != null ? offlineName : playerId.toString().substring(0, 8);
    }

    private void fillBorder(Inventory gui) {
        ItemStack border = GuiUtil.coloredPane(Material.YELLOW_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < gui.getSize(); i++) {
            gui.setItem(i, border);
        }
    }
}
