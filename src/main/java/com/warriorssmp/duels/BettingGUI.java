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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Browse active duels and wars to either spectate (fly around near the
 * arena in spectator mode) or place a bet on which side wins. One shared
 * entry point for both match kinds, since from a spectator's point of
 * view "is this a duel or a war" barely matters - they just want to
 * watch or bet on a fight.
 */
public class BettingGUI implements Listener {

    private static final String BROWSER_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lActive Matches");
    private static final String ACTIONS_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lSpectate or Bet");
    private static final String SIDE_PICKER_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lPick a Side");
    private static final String CURRENCY_PICKER_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lPick a Currency");

    private final DuelsPlugin plugin;
    private final DuelManager duelManager;
    private final WarManager warManager;
    private final GauntletManager gauntletManager;
    private final BettingManager bettingManager;
    private final SpectatorManager spectatorManager;

    /** Snapshot of which match sits at which slot the moment the browser
     *  was opened for a player - same reasoning as WarGUI's arena picker:
     *  the click handler reads from THIS, not by re-matching the clicked
     *  item's name, so there's no ambiguity even if two matches happen to
     *  look identical. */
    private final Map<UUID, List<MatchRef>> browserSlots = new HashMap<>();
    private final Map<UUID, MatchRef> pendingMatch = new HashMap<>();
    private final Map<UUID, Integer> pendingSide = new HashMap<>();

    public BettingGUI(DuelsPlugin plugin, DuelManager duelManager, WarManager warManager, GauntletManager gauntletManager,
                       BettingManager bettingManager, SpectatorManager spectatorManager) {
        this.plugin = plugin;
        this.duelManager = duelManager;
        this.warManager = warManager;
        this.gauntletManager = gauntletManager;
        this.bettingManager = bettingManager;
        this.spectatorManager = spectatorManager;
    }

    // ---------------------------------------------------------------- browser

    public void openBrowser(Player player) {
        List<MatchRef> refs = new ArrayList<>();
        for (DuelMatch match : duelManager.getAllActiveMatches()) {
            refs.add(new MatchRef(MatchKind.DUEL, match.getPlayer1()));
        }
        for (WarMatch match : warManager.getAllActiveMatches()) {
            refs.add(new MatchRef(MatchKind.WAR, match.getId()));
        }
        for (GauntletRun run : gauntletManager.getAllActiveRuns()) {
            refs.add(new MatchRef(MatchKind.GAUNTLET, run.getId()));
        }

        Inventory gui = Bukkit.createInventory(null, 54, BROWSER_TITLE);
        fillBorder(gui);

        int slot = 0;
        List<MatchRef> slotToRef = new ArrayList<>();
        for (MatchRef ref : refs) {
            if (slot >= 45) break; // leave the border row free
            String label = describeMatch(ref);
            if (label == null) continue; // match vanished between listing and building the icon
            gui.setItem(slot, GuiUtil.namedItem(Material.SPYGLASS, "&f" + label,
                    "", "&e&lClick to spectate or bet!"));
            slotToRef.add(ref);
            slot++;
        }
        browserSlots.put(player.getUniqueId(), slotToRef);

        if (slot == 0) {
            gui.setItem(22, GuiUtil.namedItem(Material.BARRIER, "&7No active matches right now.", ""));
        }
        gui.setItem(49, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        player.openInventory(gui);
    }

    private String describeMatch(MatchRef ref) {
        if (ref.kind() == MatchKind.DUEL) {
            DuelMatch match = duelManager.getActiveMatchById(ref.matchId());
            if (match == null) return null;
            return bettingManager.sideName(MatchKind.DUEL, ref.matchId(), 0) + " &7vs &f"
                    + bettingManager.sideName(MatchKind.DUEL, ref.matchId(), 1) + " &7(Duel)";
        }
        if (ref.kind() == MatchKind.GAUNTLET) {
            GauntletRun run = gauntletManager.getRunById(ref.matchId());
            if (run == null) return null;
            List<String> names = new ArrayList<>();
            for (UUID id : run.getPlayers()) {
                var p = Bukkit.getPlayer(id);
                if (p != null) names.add(p.getName());
            }
            return "&fThe Gauntlet &7- Wave " + run.getWave() + " (" + String.join(", ", names) + ")";
        }
        WarMatch match = warManager.getActiveMatchById(ref.matchId());
        if (match == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < match.getTeams().size(); i++) {
            if (i > 0) sb.append(" &7vs &f");
            sb.append(bettingManager.sideName(MatchKind.WAR, ref.matchId(), i));
        }
        sb.append(" &7(").append(match.getMode().getDisplayName()).append(")");
        return sb.toString();
    }

    // ---------------------------------------------------------------- match actions

    public void openMatchActions(Player player, MatchRef ref) {
        if (!bettingManager.isMatchActive(ref.kind(), ref.matchId())) {
            player.sendMessage(ChatColor.RED + "That match just ended.");
            openBrowser(player);
            return;
        }
        pendingMatch.put(player.getUniqueId(), ref);

        Inventory gui = Bukkit.createInventory(null, 27, ACTIONS_TITLE);
        fillBorder(gui);

        boolean isParticipant = bettingManager.isParticipant(ref.kind(), ref.matchId(), player.getUniqueId());
        boolean watchingThis = ref.equals(spectatorManager.getSpectatingMatch(player.getUniqueId()));

        if (watchingThis) {
            gui.setItem(11, GuiUtil.namedItem(Material.RED_DYE, "&c&lStop Spectating",
                    "", "&e&lClick to return to where you were!"));
        } else if (!spectatorManager.isSpectating(player.getUniqueId())) {
            gui.setItem(11, GuiUtil.namedItem(Material.ENDER_EYE, "&b&lSpectate",
                    "&7Fly around near the arena", "&7in spectator mode.", "", "&e&lClick to spectate!"));
        }

        if (!isParticipant && ref.kind() != MatchKind.GAUNTLET) {
            gui.setItem(15, GuiUtil.namedItem(Material.GOLD_INGOT, "&6&lPlace a Bet",
                    "&7Pick a side, a currency,", "&7and an amount.", "", "&e&lClick to bet!"));
        }

        gui.setItem(22, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        player.openInventory(gui);
    }

    // ---------------------------------------------------------------- side / currency pickers

    public void openSidePicker(Player player, MatchRef ref) {
        int sideCount = ref.kind() == MatchKind.DUEL ? 2
                : warManager.getActiveMatchById(ref.matchId()) != null
                        ? warManager.getActiveMatchById(ref.matchId()).getTeams().size() : 0;
        if (sideCount == 0) {
            player.sendMessage(ChatColor.RED + "That match just ended.");
            openBrowser(player);
            return;
        }

        Inventory gui = Bukkit.createInventory(null, 27, SIDE_PICKER_TITLE);
        fillBorder(gui);
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < sideCount && i < slots.length; i++) {
            gui.setItem(slots[i], GuiUtil.namedItem(Material.PLAYER_HEAD,
                    "&f" + bettingManager.sideName(ref.kind(), ref.matchId(), i), "&e&lClick to bet on this side!"));
        }
        gui.setItem(22, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        player.openInventory(gui);
    }

    public void openCurrencyPicker(Player player, MatchRef ref, int sideIndex) {
        pendingSide.put(player.getUniqueId(), sideIndex);

        Inventory gui = Bukkit.createInventory(null, 27, CURRENCY_PICKER_TITLE);
        fillBorder(gui);
        gui.setItem(11, GuiUtil.namedItem(Material.EXPERIENCE_BOTTLE, "&e&lHonor Points",
                "", "&e&lClick to pick!"));

        if (bettingManager.getVaultEconomy().isAvailable()) {
            gui.setItem(15, GuiUtil.namedItem(Material.EMERALD, "&a&lVault Money",
                    "", "&e&lClick to pick!"));
        } else {
            gui.setItem(15, GuiUtil.namedItem(Material.BARRIER, "&7Vault Money",
                    "&7Not available on this server.", ""));
        }
        gui.setItem(22, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        player.openInventory(gui);
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        boolean ours = title.equals(BROWSER_TITLE) || title.equals(ACTIONS_TITLE)
                || title.equals(SIDE_PICKER_TITLE) || title.equals(CURRENCY_PICKER_TITLE);
        if (!ours) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        int slot = event.getRawSlot();

        if (title.equals(BROWSER_TITLE)) {
            if (slot == 49) { plugin.getDuelGUI().openMain(player); return; }
            List<MatchRef> slotToRef = browserSlots.get(player.getUniqueId());
            if (slotToRef == null || slot < 0 || slot >= slotToRef.size()) return;
            openMatchActions(player, slotToRef.get(slot));
            return;
        }

        if (title.equals(ACTIONS_TITLE)) {
            MatchRef ref = pendingMatch.get(player.getUniqueId());
            if (ref == null) { player.closeInventory(); return; }

            if (slot == 22) { openBrowser(player); return; }
            if (slot == 11) {
                boolean watchingThis = ref.equals(spectatorManager.getSpectatingMatch(player.getUniqueId()));
                player.closeInventory();
                if (watchingThis) {
                    spectatorManager.stopSpectating(player);
                } else {
                    spectatorManager.startSpectating(player, ref.kind(), ref.matchId());
                }
                return;
            }
            if (slot == 15 && ref.kind() != MatchKind.GAUNTLET) {
                openSidePicker(player, ref);
            }
            return;
        }

        if (title.equals(SIDE_PICKER_TITLE)) {
            MatchRef ref = pendingMatch.get(player.getUniqueId());
            if (ref == null) { player.closeInventory(); return; }
            if (slot == 22) { openMatchActions(player, ref); return; }
            int[] slots = {10, 11, 12, 13, 14, 15, 16};
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == slot) {
                    openCurrencyPicker(player, ref, i);
                    return;
                }
            }
            return;
        }

        if (title.equals(CURRENCY_PICKER_TITLE)) {
            MatchRef ref = pendingMatch.get(player.getUniqueId());
            Integer sideIndex = pendingSide.remove(player.getUniqueId());
            if (ref == null || sideIndex == null) { player.closeInventory(); return; }
            if (slot == 22) { openSidePicker(player, ref); return; }

            if (slot == 11) {
                bettingManager.beginAmountEntry(player, ref.kind(), ref.matchId(), sideIndex, BetCurrency.HONOR);
            } else if (slot == 15 && bettingManager.getVaultEconomy().isAvailable()) {
                bettingManager.beginAmountEntry(player, ref.kind(), ref.matchId(), sideIndex, BetCurrency.VAULT);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        spectatorManager.handleQuit(event.getPlayer().getUniqueId());
    }

    private void fillBorder(Inventory gui) {
        ItemStack border = GuiUtil.coloredPane(Material.RED_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < gui.getSize(); i++) {
            gui.setItem(i, border);
        }
    }
}
