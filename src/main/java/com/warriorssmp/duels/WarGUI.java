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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * War menu - party formation and matchmaking for real Capture the Flag
 * matches against another party. Bots are 1v1-only (see DuelGUI); war
 * mode intentionally has no bot option, since team-vs-bots play wasn't
 * working reliably and was cut rather than kept half-working. Only
 * Capture the Flag exists as a mode right now, so there's no mode-picker
 * step - picking a size queues directly for it.
 */
public class WarGUI implements Listener {

    private static final int[] TEAM_SIZES = {2, 3, 5, 10};

    private static final String WAR_MAIN_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lWSMP Wars");
    private static final String INVITE_PICKER_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lInvite to Party");
    private static final String PARTY_VIEW_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lYour Party");
    private static final String PARTY_INVITE_RESPONSE_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lParty Invite");
    private static final String SIZE_PICKER_TITLE = ChatColor.translateAlternateColorCodes('&', "&c&lPick a Size (Capture the Flag)");

    private final DuelsPlugin plugin;
    private final WarManager warManager;
    private final WarPartyManager partyManager;
    /** Which member UUID sits at which slot the moment the party view was
     *  opened for a given viewer - the click handler reads from this
     *  rather than re-deriving from the item clicked, same reasoning as
     *  every other slot-mapped browser in this plugin. */
    private final Map<UUID, List<UUID>> partySlots = new HashMap<>();

    public WarGUI(DuelsPlugin plugin, WarManager warManager, WarPartyManager partyManager) {
        this.plugin = plugin;
        this.warManager = warManager;
        this.partyManager = partyManager;
    }

    // ---------------------------------------------------------------- main

    public void openMain(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, WAR_MAIN_TITLE);
        fillBorder(gui);

        gui.setItem(10, GuiUtil.namedItem(Material.NAME_TAG, "&c&lInvite to Party",
                "&7Bring friends into your", "&7war party.", "", "&e&lClick to open!"));
        gui.setItem(12, GuiUtil.namedItem(Material.CHEST, "&b&lYour Party",
                "&7View your current party", "&7and its members.", "", "&e&lClick to view!"));
        gui.setItem(14, GuiUtil.namedItem(Material.DIAMOND_SWORD, "&6&lQueue For War",
                "&7Capture the Flag - pick a size.", "&7Your party must be exactly",
                "&7that many members.", "", "&e&lClick to queue!"));

        WarParty pendingInvite = partyManager.getPendingInvite(player.getUniqueId());
        if (pendingInvite != null) {
            gui.setItem(16, GuiUtil.namedItem(Material.BEACON, "&a&lPending Party Invite!",
                    "&7From: &f" + nameOf(pendingInvite.getLeader()), "", "&e&lClick to respond!"));
        }

        if (warManager.hasActiveMatch(player.getUniqueId())) {
            gui.setItem(22, GuiUtil.namedItem(Material.BARRIER, "&c&lEnd Current War",
                    "&7Leave your active war.", "&7Counts as a forfeit.", "", "&e&lClick to end!"));
        }

        player.openInventory(gui);
    }

    // ---------------------------------------------------------------- invite / party view

    public void openInvitePicker(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, INVITE_PICKER_TITLE);
        int slot = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(player.getUniqueId())) continue;
            if (partyManager.inParty(online.getUniqueId())) continue;
            if (slot >= 53) break;
            gui.setItem(slot++, GuiUtil.namedItem(Material.PLAYER_HEAD, "&f" + online.getName(), "&e&lClick to invite!"));
        }
        if (slot == 0) {
            gui.setItem(22, GuiUtil.namedItem(Material.BARRIER, "&7No available players to invite.", ""));
        }
        gui.setItem(53, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        player.openInventory(gui);
    }

    public void openPartyView(Player player) {
        WarParty party = partyManager.getOrCreateParty(player);
        boolean viewerIsLeader = party.getLeader().equals(player.getUniqueId());
        Inventory gui = Bukkit.createInventory(null, 27, PARTY_VIEW_TITLE);
        fillBorder(gui);

        int slot = 10;
        List<UUID> slotToMember = new ArrayList<>();
        for (UUID memberId : party.getMembers()) {
            if (slot > 16) break;
            boolean isLeader = memberId.equals(party.getLeader());
            boolean canKick = viewerIsLeader && !isLeader;
            gui.setItem(slot, GuiUtil.namedItem(Material.PLAYER_HEAD, "&f" + nameOf(memberId),
                    isLeader ? "&6Leader" : "&7Member", "", canKick ? "&e&lClick to kick!" : ""));
            slotToMember.add(memberId);
            slot++;
        }
        partySlots.put(player.getUniqueId(), slotToMember);

        gui.setItem(4, GuiUtil.namedItem(Material.RED_DYE, "&c&lLeave Party",
                "&7Leave your current party.", "", "&e&lClick to leave!"));
        gui.setItem(22, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        player.openInventory(gui);
    }

    public void openInviteResponse(Player player) {
        WarParty invite = partyManager.getPendingInvite(player.getUniqueId());
        if (invite == null) { openMain(player); return; }

        Inventory gui = Bukkit.createInventory(null, 27, PARTY_INVITE_RESPONSE_TITLE);
        fillBorder(gui);
        gui.setItem(13, GuiUtil.namedItem(Material.PLAYER_HEAD, "&f" + nameOf(invite.getLeader()),
                "&7invited you to their party!"));
        gui.setItem(11, GuiUtil.namedItem(Material.LIME_WOOL, "&a&lAccept", "", "&e&lClick to accept!"));
        gui.setItem(15, GuiUtil.namedItem(Material.RED_WOOL, "&c&lDecline", "", "&e&lClick to decline!"));
        gui.setItem(22, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        player.openInventory(gui);
    }

    // ---------------------------------------------------------------- size picker

    public void openSizePicker(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, SIZE_PICKER_TITLE);
        fillBorder(gui);

        int[] slots = {10, 12, 14, 16};
        for (int i = 0; i < TEAM_SIZES.length; i++) {
            int size = TEAM_SIZES[i];
            gui.setItem(slots[i], GuiUtil.namedItem(Material.IRON_SWORD, "&f" + size + "v" + size,
                    "&7Your party needs exactly", "&7" + size + " members.", "", "&e&lClick to queue!"));
        }
        gui.setItem(22, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));

        player.openInventory(gui);
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        boolean ours = title.equals(WAR_MAIN_TITLE) || title.equals(INVITE_PICKER_TITLE) || title.equals(PARTY_VIEW_TITLE)
                || title.equals(PARTY_INVITE_RESPONSE_TITLE) || title.equals(SIZE_PICKER_TITLE);
        if (!ours) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        int slot = event.getRawSlot();

        if (title.equals(WAR_MAIN_TITLE)) {
            if (slot == 10) openInvitePicker(player);
            else if (slot == 12) openPartyView(player);
            else if (slot == 14) openSizePicker(player);
            else if (slot == 16) openInviteResponse(player);
            else if (slot == 22) { player.closeInventory(); warManager.endMatchVoluntarily(player); }
            return;
        }

        if (title.equals(INVITE_PICKER_TITLE)) {
            if (slot == 53) { openMain(player); return; }
            String targetName = stripColor(clicked);
            Player target = Bukkit.getPlayerExact(targetName);
            if (target != null) {
                player.closeInventory();
                if (partyManager.invite(player, target)) {
                    // Pops the response screen open for them directly,
                    // instead of leaving them to notice the chat message
                    // and remember to type /duels themselves.
                    openInviteResponse(target);
                }
            }
            return;
        }

        if (title.equals(PARTY_VIEW_TITLE)) {
            if (slot == 22) { openMain(player); return; }
            if (slot == 4) {
                player.closeInventory();
                partyManager.leaveParty(player);
                return;
            }
            List<UUID> slotToMember = partySlots.get(player.getUniqueId());
            if (slotToMember == null || slot < 10 || slot > 16) return;
            int index = slot - 10;
            if (index >= slotToMember.size()) return;
            partyManager.kickMember(player, slotToMember.get(index));
            openPartyView(player);
            return;
        }

        if (title.equals(PARTY_INVITE_RESPONSE_TITLE)) {
            if (slot == 22) { openMain(player); return; }
            player.closeInventory();
            if (slot == 11) partyManager.acceptInvite(player);
            else if (slot == 15) partyManager.declineInvite(player);
            return;
        }

        if (title.equals(SIZE_PICKER_TITLE)) {
            if (slot == 22) { openMain(player); return; }
            String sizeLabel = stripColor(clicked);
            for (int size : TEAM_SIZES) {
                if ((size + "v" + size).equals(sizeLabel)) {
                    player.closeInventory();
                    warManager.joinQueue(player, WarMode.CAPTURE_THE_FLAG, size);
                    return;
                }
            }
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
