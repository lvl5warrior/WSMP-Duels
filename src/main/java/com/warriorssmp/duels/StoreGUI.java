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

/** Browse and buy items from the honor store. */
public class StoreGUI implements Listener {

    private static final String STORE_TITLE = ChatColor.translateAlternateColorCodes('&', "&6&lHonor Store");
    private static final String CONFIRM_TITLE = ChatColor.translateAlternateColorCodes('&', "&6&lConfirm Purchase");

    private final DuelsPlugin plugin;
    private final StoreManager storeManager;
    private final PlayerDataManager playerDataManager;

    /** Snapshot of which store item sits at which slot the moment the
     *  store was opened - same reasoning as every other browser GUI in
     *  this plugin: the click handler reads from THIS, not by re-matching
     *  the clicked item's name, so two items can never be confused even
     *  if they happen to share a display name. */
    private final Map<UUID, List<UUID>> storeSlots = new HashMap<>();
    private final Map<UUID, UUID> pendingPurchase = new HashMap<>();

    public StoreGUI(DuelsPlugin plugin, StoreManager storeManager, PlayerDataManager playerDataManager) {
        this.plugin = plugin;
        this.storeManager = storeManager;
        this.playerDataManager = playerDataManager;
    }

    public void openStore(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, STORE_TITLE);

        int honor = playerDataManager.get(player.getUniqueId()).getHonor();
        gui.setItem(49, GuiUtil.namedItem(Material.EXPERIENCE_BOTTLE, "&e&lYour Honor: &f" + honor, ""));
        gui.setItem(53, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));

        int slot = 0;
        List<UUID> slotToId = new ArrayList<>();
        for (StoreItem item : storeManager.getAll()) {
            if (slot >= 45) break;
            ItemStack display = item.getReward().clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&f" + item.getName()));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.translateAlternateColorCodes('&', "&7Price: &e" + item.getPrice() + " honor"));
                lore.add(ChatColor.translateAlternateColorCodes('&', "&e&lClick to buy!"));
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            gui.setItem(slot, display);
            slotToId.add(item.getId());
            slot++;
        }
        storeSlots.put(player.getUniqueId(), slotToId);

        if (slot == 0) {
            gui.setItem(22, GuiUtil.namedItem(Material.BARRIER, "&7The store is empty right now.", ""));
        }
        player.openInventory(gui);
    }

    private void openConfirm(Player player, StoreItem item) {
        pendingPurchase.put(player.getUniqueId(), item.getId());
        Inventory gui = Bukkit.createInventory(null, 27, CONFIRM_TITLE);
        fillBorder(gui);

        ItemStack display = item.getReward().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&f" + item.getName()));
            meta.setLore(List.of(ChatColor.translateAlternateColorCodes('&', "&7Price: &e" + item.getPrice() + " honor")));
            display.setItemMeta(meta);
        }
        gui.setItem(13, display);
        gui.setItem(11, GuiUtil.namedItem(Material.LIME_WOOL, "&a&lConfirm Purchase", "", "&e&lClick to buy!"));
        gui.setItem(15, GuiUtil.namedItem(Material.RED_WOOL, "&c&lCancel", "", "&e&lClick to cancel!"));
        player.openInventory(gui);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        boolean ours = title.equals(STORE_TITLE) || title.equals(CONFIRM_TITLE);
        if (!ours) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        int slot = event.getRawSlot();

        if (title.equals(STORE_TITLE)) {
            if (slot == 53) { plugin.getDuelGUI().openMain(player); return; }
            List<UUID> slotToId = storeSlots.get(player.getUniqueId());
            if (slotToId == null || slot < 0 || slot >= slotToId.size()) return;
            StoreItem item = storeManager.get(slotToId.get(slot));
            if (item != null) openConfirm(player, item);
            return;
        }

        if (title.equals(CONFIRM_TITLE)) {
            UUID itemId = pendingPurchase.remove(player.getUniqueId());
            if (itemId == null) { player.closeInventory(); return; }
            if (slot == 11) {
                player.closeInventory();
                storeManager.purchase(player, itemId);
            } else {
                openStore(player);
            }
        }
    }

    /** Same gap as GauntletMerchantGUI - dragging fires a separate event
     *  from clicking, so cancelling clicks alone doesn't stop someone
     *  from dragging store items around. */
    @EventHandler
    public void onDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (title.equals(STORE_TITLE) || title.equals(CONFIRM_TITLE)) {
            event.setCancelled(true);
        }
    }

    private void fillBorder(Inventory gui) {
        ItemStack border = GuiUtil.coloredPane(Material.YELLOW_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < gui.getSize(); i++) {
            gui.setItem(i, border);
        }
    }
}
