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
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DuelGUI implements Listener {

    private static final String MAIN_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lWSMP Duels");
    private static final String PLAYER_PICKER_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lChallenge a Player");
    private static final String CHALLENGE_RESPONSE_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lIncoming Challenge");
    private static final String KIT_SELECT_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lPick a Kit");
    private static final String BOT_DIFFICULTY_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lPick Bot Difficulty");
    private static final String STATS_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lYour Stats");
    private static final String GAUNTLET_MENU_TITLE = ChatColor.translateAlternateColorCodes('&', "&5&lThe Gauntlet");
    private static final String RANK_LIST_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lAll Ranks");
    private static final String LEADERBOARD_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lHonor Leaderboard");

    private final DuelsPlugin plugin;
    private final DuelManager duelManager;
    private WarManager warManager; // set after construction, see DuelsPlugin.onEnable - avoids a circular constructor dependency
    private BettingGUI bettingGUI; // same pattern
    private StoreGUI storeGUI; // same pattern
    private GauntletManager gauntletManager; // same pattern
    private final KitManager kitManager;
    private final PlayerDataManager playerDataManager;
    private final Map<UUID, PendingKitSelectReason> pendingKitSelectReason = new java.util.HashMap<>();

    /** The single point of entry for setting why a player is on the kit
     *  select screen - always overwrites whatever reason (if any) was
     *  there before, so a stale reason from an abandoned earlier flow can
     *  never coexist with, or be mistaken for, a fresh one. Every flow
     *  that leads to the kit-select screen must call this, and nothing
     *  should ever write to the underlying map directly. */
    public void setPendingKitSelectReason(UUID playerId, PendingKitSelectReason reason) {
        pendingKitSelectReason.put(playerId, reason);
    }

    public PendingKitSelectReason getPendingKitSelectReason(UUID playerId) {
        return pendingKitSelectReason.get(playerId);
    }

    public PendingKitSelectReason consumePendingKitSelectReason(UUID playerId) {
        return pendingKitSelectReason.remove(playerId);
    }

    public DuelGUI(DuelsPlugin plugin, DuelManager duelManager, KitManager kitManager, PlayerDataManager playerDataManager) {
        this.plugin = plugin;
        this.duelManager = duelManager;
        this.kitManager = kitManager;
        this.playerDataManager = playerDataManager;
    }

    public void setWarManager(WarManager warManager) {
        this.warManager = warManager;
    }

    public void setBettingGUI(BettingGUI bettingGUI) {
        this.bettingGUI = bettingGUI;
    }

    public void setStoreGUI(StoreGUI storeGUI) {
        this.storeGUI = storeGUI;
    }

    public void setGauntletManager(GauntletManager gauntletManager) {
        this.gauntletManager = gauntletManager;
    }

    // ---------------------------------------------------------------- main menu

    public void openMain(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, MAIN_TITLE);
        fillBorder(gui);

        gui.setItem(11, GuiUtil.namedItem(Material.IRON_SWORD, "&c&lChallenge a Player",
                "&7Pick an online player", "&7to duel 1v1.", "", "&e&lClick to open!"));
        gui.setItem(13, GuiUtil.namedItem(Material.NETHER_STAR, "&b&lYour Stats",
                "&7View your honor,", "&7rank, and record.", "", "&e&lClick to view!"));
        gui.setItem(15, GuiUtil.namedItem(Material.GOLD_INGOT, "&6&lLeaderboard",
                "&7See the top duelists", "&7by honor.", "", "&e&lClick to view!"));
        gui.setItem(19, GuiUtil.namedItem(Material.SPYGLASS, "&b&lSpectate & Bet",
                "&7Watch an active match, or", "&7bet Honor/Vault money on it.", "", "&e&lClick to open!"));
        gui.setItem(20, GuiUtil.namedItem(Material.DIAMOND_SWORD, "&e&lTeam Wars",
                "&72v2 through 10v10 team", "&7battles.", "", "&e&lClick to open!"));
        gui.setItem(21, GuiUtil.namedItem(Material.EMERALD, "&a&lHonor Store",
                "&7Spend your honor on", "&7admin-configured items.", "", "&e&lClick to open!"));
        gui.setItem(24, GuiUtil.namedItem(Material.ZOMBIE_HEAD, "&2&lFight a Bot",
                "&7Practice solo against a", "&7bot - no one else needed.", "", "&e&lClick to open!"));
        gui.setItem(25, GuiUtil.namedItem(Material.WITHER_SKELETON_SKULL, "&5&lThe Gauntlet",
                "&7100 waves of monsters, solo or", "&7co-op with your war party (up to 4).",
                "&7No kit - fixed leather + iron.", "", "&e&lClick to start!"));

        DuelChallenge incoming = duelManager.getIncomingChallenge(player.getUniqueId());
        if (incoming != null && incoming.getState() == DuelChallenge.State.AWAITING_ACCEPT) {
            String challengerName = nameOf(incoming.getChallenger());
            gui.setItem(22, GuiUtil.namedItem(Material.BEACON, "&a&lPending Challenge!",
                    "&7From: &f" + challengerName, "", "&e&lClick to respond!"));
        }

        if (duelManager.hasActiveMatch(player.getUniqueId())) {
            gui.setItem(16, GuiUtil.namedItem(Material.BARRIER, "&c&lEnd Current Duel",
                    "&7Leave your active duel.", "&7A bot duel ends cleanly -",
                    "&7a real one counts as a forfeit.", "", "&e&lClick to end!"));
        } else if (gauntletManager != null && gauntletManager.hasActiveRun(player.getUniqueId())) {
            gui.setItem(16, GuiUtil.namedItem(Material.BARRIER, "&c&lEnd Gauntlet Run",
                    "&7Leave your active Gauntlet run.", "", "&e&lClick to end!"));
        }

        player.openInventory(gui);
    }

    // ---------------------------------------------------------------- player picker

    public void openPlayerPicker(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, PLAYER_PICKER_TITLE);
        int slot = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(player.getUniqueId())) continue;
            if (slot >= 53) break;
            gui.setItem(slot++, GuiUtil.namedItem(Material.PLAYER_HEAD, "&f" + online.getName(),
                    "&e&lClick to challenge!"));
        }
        if (slot == 0) {
            gui.setItem(22, GuiUtil.namedItem(Material.BARRIER, "&7No other players online.", ""));
        }
        gui.setItem(53, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        player.openInventory(gui);
    }

    // ---------------------------------------------------------------- challenge response

    public void openChallengeResponse(Player player) {
        DuelChallenge incoming = duelManager.getIncomingChallenge(player.getUniqueId());
        if (incoming == null) {
            openMain(player);
            return;
        }
        Inventory gui = Bukkit.createInventory(null, 27, CHALLENGE_RESPONSE_TITLE);
        fillBorder(gui);

        gui.setItem(13, GuiUtil.namedItem(Material.PLAYER_HEAD, "&f" + nameOf(incoming.getChallenger()),
                "&7wants to duel you!"));
        gui.setItem(11, GuiUtil.namedItem(Material.LIME_WOOL, "&a&lAccept", "", "&e&lClick to accept!"));
        gui.setItem(15, GuiUtil.namedItem(Material.RED_WOOL, "&c&lDecline", "", "&e&lClick to decline!"));
        gui.setItem(22, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));

        player.openInventory(gui);
    }

    // ---------------------------------------------------------------- kit select

    public void openKitSelect(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, KIT_SELECT_TITLE);
        int slot = 0;
        for (Kit kit : kitManager.getAll()) {
            if (slot >= 53) break;
            gui.setItem(slot++, GuiUtil.namedItem(kit.getIcon(), "&f" + kit.getName(),
                    "&e&lClick to select this kit!"));
        }
        if (slot == 0) {
            gui.setItem(22, GuiUtil.namedItem(Material.BARRIER, "&7No kits have been created yet.", ""));
        }
        player.openInventory(gui);
    }

    // ---------------------------------------------------------------- bot fight

    public void openBotDifficultyPicker(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, BOT_DIFFICULTY_TITLE);
        fillBorder(gui);

        BotDifficulty[] difficulties = BotDifficulty.values();
        Material[] icons = {Material.LIME_DYE, Material.YELLOW_DYE, Material.RED_DYE};
        int[] slots = {11, 13, 15};
        for (int i = 0; i < difficulties.length; i++) {
            gui.setItem(slots[i], GuiUtil.namedItem(icons[i], "&f" + difficulties[i].getDisplayName(),
                    "&e&lClick to pick!"));
        }
        gui.setItem(22, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));

        player.openInventory(gui);
    }

    // ---------------------------------------------------------------- gauntlet menu

    public void openGauntletMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, GAUNTLET_MENU_TITLE);
        fillBorder(gui);

        gui.setItem(11, GuiUtil.namedItem(Material.CHEST, "&b&lYour Party",
                "&7View your current party", "&7and its members.", "", "&e&lClick to view!"));
        gui.setItem(13, GuiUtil.namedItem(Material.NAME_TAG, "&b&lInvite to Party",
                "&7Bring friends into your", "&7Gauntlet run (up to 4).", "", "&e&lClick to open!"));

        if (gauntletManager != null && gauntletManager.hasActiveRun(player.getUniqueId())) {
            gui.setItem(15, GuiUtil.namedItem(Material.BARRIER, "&c&lEnd Gauntlet Run",
                    "&7Leave your active run.", "", "&e&lClick to end!"));
        } else {
            gui.setItem(15, GuiUtil.namedItem(Material.WITHER_SKELETON_SKULL, "&5&lStart Gauntlet",
                    "&7100 waves of monsters, solo or", "&7co-op with your current party.",
                    "&7No kit - fixed leather + iron.", "", "&e&lClick to start!"));
        }

        gui.setItem(22, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        player.openInventory(gui);
    }

    // ---------------------------------------------------------------- stats

    public void openStats(Player player) {
        PlayerDuelData data = playerDataManager.get(player.getUniqueId());
        Inventory gui = Bukkit.createInventory(null, 27, STATS_TITLE);
        fillBorder(gui);

        gui.setItem(13, GuiUtil.namedItem(Material.PLAYER_HEAD, "&f" + player.getName(),
                "&7Rank: " + data.getRank().getDisplayName(),
                "&7Honor: &f" + data.getHonor() + " &7(lifetime: &f" + data.getLifetimeHonor() + "&7)",
                "&7Wins: &a" + data.getWins() + " &7Losses: &c" + data.getLosses(),
                "&7Win rate: &f" + String.format("%.1f", data.getWinRate()) + "%"));

        HonorRank next = data.getRank().next();
        if (next != null) {
            int needed = next.getThreshold() - data.getLifetimeHonor();
            gui.setItem(15, GuiUtil.namedItem(Material.EXPERIENCE_BOTTLE, "&e&lNext Rank",
                    "&7" + next.getDisplayName(), "&7" + Math.max(0, needed) + " more lifetime honor needed"));
        }
        gui.setItem(11, GuiUtil.namedItem(Material.BOOK, "&b&lAll Ranks",
                "&7See every rank tier from", "&7Private up to Warlord.", "", "&e&lClick to view!"));

        gui.setItem(22, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        player.openInventory(gui);
    }

    // ---------------------------------------------------------------- rank list

    public void openRankList(Player player) {
        HonorRank current = playerDataManager.get(player.getUniqueId()).getRank();
        Inventory gui = Bukkit.createInventory(null, 36, RANK_LIST_TITLE);
        fillBorder(gui);

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
        HonorRank[] ranks = HonorRank.values();
        for (int i = 0; i < ranks.length && i < slots.length; i++) {
            HonorRank rank = ranks[i];
            boolean isCurrent = rank == current;
            gui.setItem(slots[i], GuiUtil.namedItem(isCurrent ? Material.NETHER_STAR : Material.IRON_INGOT,
                    "&f" + rank.getDisplayName(),
                    "&7Requires: &f" + rank.getThreshold() + " lifetime honor",
                    isCurrent ? "&a&lYour current rank!" : ""));
        }

        gui.setItem(31, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        player.openInventory(gui);
    }

    // ---------------------------------------------------------------- leaderboard

    public void openLeaderboard(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, LEADERBOARD_TITLE);
        fillBorder(gui);

        List<Map.Entry<UUID, PlayerDuelData>> sorted = new ArrayList<>(playerDataManager.getAll().entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<UUID, PlayerDuelData> e) -> e.getValue().getLifetimeHonor()).reversed());

        int rank = 1;
        for (Map.Entry<UUID, PlayerDuelData> entry : sorted) {
            if (rank > 28) break;
            PlayerDuelData data = entry.getValue();
            int index = rank - 1;
            int row = index / 7;
            int col = index % 7;
            int slot = 10 + row * 9 + col;
            gui.setItem(slot, GuiUtil.namedItem(Material.PLAYER_HEAD, "&e#" + rank + " &f" + nameOf(entry.getKey()),
                    "&7Rank: " + data.getRank().getDisplayName(),
                    "&7Lifetime honor: &f" + data.getLifetimeHonor(),
                    "&7Wins: &a" + data.getWins() + " &7Losses: &c" + data.getLosses()));
            rank++;
        }

        gui.setItem(49, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        player.openInventory(gui);
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        boolean ours = title.equals(MAIN_TITLE) || title.equals(PLAYER_PICKER_TITLE) || title.equals(CHALLENGE_RESPONSE_TITLE)
                || title.equals(KIT_SELECT_TITLE) || title.equals(STATS_TITLE) || title.equals(LEADERBOARD_TITLE)
                || title.equals(BOT_DIFFICULTY_TITLE) || title.equals(RANK_LIST_TITLE) || title.equals(GAUNTLET_MENU_TITLE);
        if (!ours) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        int slot = event.getRawSlot();

        if (title.equals(MAIN_TITLE)) {
            if (slot == 11) openPlayerPicker(player);
            else if (slot == 13) openStats(player);
            else if (slot == 15) openLeaderboard(player);
            else if (slot == 16) {
                player.closeInventory();
                if (duelManager.hasActiveMatch(player.getUniqueId())) duelManager.endMatchVoluntarily(player);
                else if (gauntletManager != null) gauntletManager.endRunVoluntarily(player);
            }
            else if (slot == 19) { if (bettingGUI != null) bettingGUI.openBrowser(player); }
            else if (slot == 20) plugin.getWarGUI().openMain(player);
            else if (slot == 21) { if (storeGUI != null) storeGUI.openStore(player); }
            else if (slot == 22) openChallengeResponse(player);
            else if (slot == 24) openBotDifficultyPicker(player);
            else if (slot == 25) openGauntletMenu(player);
            return;
        }

        if (title.equals(BOT_DIFFICULTY_TITLE)) {
            if (slot == 22) { openMain(player); return; }
            BotDifficulty[] difficulties = BotDifficulty.values();
            int[] slots = {11, 13, 15};
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == slot) {
                    setPendingKitSelectReason(player.getUniqueId(), new PendingKitSelectReason.BotDuel1v1(difficulties[i]));
                    player.closeInventory();
                    player.sendMessage(ChatColor.YELLOW + "Now pick a kit to fight with!");
                    openKitSelect(player);
                    return;
                }
            }
            return;
        }

        if (title.equals(PLAYER_PICKER_TITLE)) {
            if (slot == 53) { openMain(player); return; }
            String targetName = stripColor(clicked);
            Player target = Bukkit.getPlayerExact(targetName);
            if (target != null) {
                player.closeInventory();
                duelManager.sendChallenge(player, target);
            }
            return;
        }

        if (title.equals(CHALLENGE_RESPONSE_TITLE)) {
            if (slot == 22) { openMain(player); return; }
            player.closeInventory();
            if (slot == 11) duelManager.acceptChallenge(player);
            else if (slot == 15) duelManager.declineChallenge(player);
            return;
        }

        if (title.equals(KIT_SELECT_TITLE)) {
            String kitName = stripColor(clicked);
            Kit kit = kitManager.getByName(kitName);
            if (kit != null) {
                player.closeInventory();
                // This screen serves three flows now that bot wars have
                // been removed. Which one applies is read from a single,
                // always-overwritten reason rather than checking
                // independent flags in priority order - that older
                // approach had a real, confirmed bug where a stale flag
                // from an abandoned flow could silently hijack an
                // unrelated later kit pick, since it was checked first no
                // matter which flow the player had actually just done.
                PendingKitSelectReason reason = consumePendingKitSelectReason(player.getUniqueId());
                if (reason instanceof PendingKitSelectReason.BotDuel1v1 botDuel) {
                    duelManager.startBotDuel(player, botDuel.difficulty(), kit);
                } else if (reason instanceof PendingKitSelectReason.WarLeaderAgreement) {
                    if (warManager != null) warManager.leaderSelectKit(player, kit);
                } else if (reason instanceof PendingKitSelectReason.NormalDuelChallenge) {
                    duelManager.selectKit(player, kit);
                } else {
                    // No reason was ever recorded for this player - fail
                    // safely and visibly rather than silently guessing a
                    // default flow, which is exactly the kind of silent
                    // wrong-guess that caused the original bug.
                    player.sendMessage(ChatColor.RED + "Your kit selection wasn't tied to an active challenge or "
                            + "match - please start again from the menu.");
                }
            }
            return;
        }

        if (title.equals(STATS_TITLE)) {
            if (slot == 11) openRankList(player);
            else if (slot == 22) openMain(player);
            return;
        }

        if (title.equals(RANK_LIST_TITLE)) {
            if (slot == 31) openStats(player);
            return;
        }

        if (title.equals(GAUNTLET_MENU_TITLE)) {
            if (slot == 11) plugin.getWarGUI().openPartyView(player);
            else if (slot == 13) plugin.getWarGUI().openInvitePicker(player);
            else if (slot == 15) {
                player.closeInventory();
                if (gauntletManager == null) return;
                if (gauntletManager.hasActiveRun(player.getUniqueId())) gauntletManager.endRunVoluntarily(player);
                else gauntletManager.startRun(player);
            } else if (slot == 22) openMain(player);
            return;
        }

        if (title.equals(LEADERBOARD_TITLE)) {
            if (slot == 49) openMain(player);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void fillBorder(Inventory gui) {
        ItemStack border = GuiUtil.coloredPane(Material.RED_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < gui.getSize(); i++) {
            gui.setItem(i, border);
        }
    }

    private String stripColor(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getDisplayName() == null) return "";
        return ChatColor.stripColor(meta.getDisplayName());
    }

    private String nameOf(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) return online.getName();
        String offlineName = Bukkit.getOfflinePlayer(playerId).getName();
        return offlineName != null ? offlineName : playerId.toString().substring(0, 8);
    }
}
