package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
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
import java.util.concurrent.ConcurrentHashMap;

public class AdminGUI implements Listener {

    public enum AwaitingInput { KIT_NAME, STORE_ITEM_NAME, STORE_ITEM_PRICE }

    private static final String ADMIN_MAIN_TITLE = ChatColor.translateAlternateColorCodes('&', "&4&lDuels Admin");
    private static final String KIT_LIST_TITLE = ChatColor.translateAlternateColorCodes('&', "&4&lManage Kits");
    private static final String ARENA_LIST_TITLE = ChatColor.translateAlternateColorCodes('&', "&4&lManage Maps");
    private static final String ARENA_DETAIL_PREFIX = ChatColor.translateAlternateColorCodes('&', "&4&lMap: ");
    private static final String DELETE_KIT_CONFIRM_TITLE = ChatColor.translateAlternateColorCodes('&', "&4&lConfirm Delete Kit");
    private static final String STORE_LIST_TITLE = ChatColor.translateAlternateColorCodes('&', "&4&lManage Store");
    private static final String DELETE_STORE_ITEM_CONFIRM_TITLE = ChatColor.translateAlternateColorCodes('&', "&4&lConfirm Delete Item");

    private final DuelsPlugin plugin;
    private final KitManager kitManager;
    private final ArenaManager arenaManager;
    private final StoreManager storeManager;
    private final SpawnWandListener spawnWandListener;

    // ConcurrentHashMap specifically (not HashMap) because this one is
    // both written from the main thread (GUI clicks) AND read from
    // DuelsChatListener's AsyncChatEvent handler, which Paper runs off
    // the main thread by design - see BettingManager.awaitingAmount for
    // the same reasoning in more detail.
    private final Map<UUID, AwaitingInput> awaitingInput = new ConcurrentHashMap<>();
    private final Map<UUID, Arena.Type> openArenaDetail = new HashMap<>();
    private final Map<UUID, UUID> pendingDeleteKit = new HashMap<>();
    private final Map<UUID, String> pendingStoreItemName = new HashMap<>();
    private final Map<UUID, UUID> pendingDeleteStoreItem = new HashMap<>();
    private final Map<UUID, java.util.List<UUID>> storeListSlots = new HashMap<>();

    public AdminGUI(DuelsPlugin plugin, KitManager kitManager, ArenaManager arenaManager, StoreManager storeManager,
                     SpawnWandListener spawnWandListener) {
        this.plugin = plugin;
        this.kitManager = kitManager;
        this.arenaManager = arenaManager;
        this.storeManager = storeManager;
        this.spawnWandListener = spawnWandListener;
    }

    public AwaitingInput getAwaitingInput(UUID adminId) {
        return awaitingInput.get(adminId);
    }

    public void consumeChatInput(Player admin, AwaitingInput type, String text) {
        awaitingInput.remove(admin.getUniqueId());
        switch (type) {
            case KIT_NAME -> {
                Kit kit = new Kit(UUID.randomUUID(), text.trim(), Material.IRON_SWORD);
                var inv = admin.getInventory();
                kit.setHelmet(inv.getHelmet());
                kit.setChestplate(inv.getChestplate());
                kit.setLeggings(inv.getLeggings());
                kit.setBoots(inv.getBoots());
                kit.setOffhand(inv.getItemInOffHand());
                ItemStack[] contents = inv.getStorageContents();
                for (int i = 0; i < contents.length && i < 36; i++) {
                    kit.getContents().set(i, contents[i]);
                }
                if (!kitManager.add(kit)) {
                    admin.sendMessage(ChatColor.RED + "Failed to save the new kit to kits.yml - it was NOT "
                            + "created. Check the console for the error.");
                    openKitList(admin);
                    return;
                }
                admin.sendMessage(ChatColor.GREEN + "Created kit '" + kit.getName() + "' from your current inventory.");
                openKitList(admin);
            }
            case STORE_ITEM_NAME -> {
                pendingStoreItemName.put(admin.getUniqueId(), text.trim());
                awaitingInput.put(admin.getUniqueId(), AwaitingInput.STORE_ITEM_PRICE);
                admin.sendMessage(ChatColor.YELLOW + "Now type the price in honor.");
            }
            case STORE_ITEM_PRICE -> {
                String name = pendingStoreItemName.remove(admin.getUniqueId());
                int price;
                try {
                    price = Integer.parseInt(text.trim());
                } catch (NumberFormatException e) {
                    admin.sendMessage(ChatColor.RED + "That's not a valid whole number - item not created.");
                    break;
                }
                if (price <= 0) {
                    admin.sendMessage(ChatColor.RED + "Price must be a positive number - item not created.");
                    break;
                }
                ItemStack reward = admin.getInventory().getItemInMainHand();
                if (reward == null || reward.getType() == Material.AIR) {
                    admin.sendMessage(ChatColor.RED + "You need to be holding the reward item - item not created.");
                    break;
                }
                StoreItem item = new StoreItem(UUID.randomUUID(), name != null ? name : "Unnamed Item", reward.clone(), price);
                if (!storeManager.add(item)) {
                    admin.sendMessage(ChatColor.RED + "Failed to save the new store item to store.yml - it was "
                            + "NOT created. Check the console for the error.");
                    openStoreList(admin);
                    return;
                }
                admin.sendMessage(ChatColor.GREEN + "Added '" + item.getName() + "' to the store for " + price + " honor.");
                openStoreList(admin);
            }
        }
    }

    // ---------------------------------------------------------------- main

    public void openMain(Player admin) {
        Inventory gui = Bukkit.createInventory(null, 27, ADMIN_MAIN_TITLE);
        fillBorder(gui);
        gui.setItem(11, GuiUtil.namedItem(Material.CHEST, "&c&lManage Kits", "&7Create and remove", "&7duel kits.", "", "&e&lClick to open!"));
        gui.setItem(13, GuiUtil.namedItem(Material.GOLD_INGOT, "&6&lManage Store", "&7Create and remove", "&7honor store items.", "", "&e&lClick to open!"));
        gui.setItem(15, GuiUtil.namedItem(Material.MAP, "&c&lManage Maps", "&7Set up the two static", "&7Duel/Gauntlet maps.", "", "&e&lClick to open!"));
        admin.openInventory(gui);
    }

    // ---------------------------------------------------------------- store

    public void openStoreList(Player admin) {
        Inventory gui = Bukkit.createInventory(null, 54, STORE_LIST_TITLE);
        int slot = 0;
        java.util.List<UUID> slotToId = new java.util.ArrayList<>();
        for (StoreItem item : storeManager.getAll()) {
            if (slot >= 45) break;
            gui.setItem(slot, GuiUtil.namedItem(item.getReward().getType(), "&f" + item.getName(),
                    "&7Price: &e" + item.getPrice() + " honor", "", "&e&lClick to delete!"));
            slotToId.add(item.getId());
            slot++;
        }
        storeListSlots.put(admin.getUniqueId(), slotToId);
        gui.setItem(49, GuiUtil.namedItem(Material.EMERALD, "&a&lCreate New Store Item",
                "&7Uses whatever you're CURRENTLY", "&7holding in your main hand.",
                "", "&e&lClick, then type a name and price!"));
        gui.setItem(45, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        admin.openInventory(gui);
    }

    private void openDeleteStoreItemConfirm(Player admin, StoreItem item) {
        pendingDeleteStoreItem.put(admin.getUniqueId(), item.getId());
        Inventory gui = Bukkit.createInventory(null, 27, DELETE_STORE_ITEM_CONFIRM_TITLE);
        fillBorder(gui);
        gui.setItem(13, GuiUtil.namedItem(item.getReward().getType(), "&f" + item.getName(), "&7Delete this item?"));
        gui.setItem(11, GuiUtil.namedItem(Material.LIME_WOOL, "&a&lConfirm", "", "&e&lClick to delete!"));
        gui.setItem(15, GuiUtil.namedItem(Material.RED_WOOL, "&c&lCancel", "", "&e&lClick to cancel!"));
        admin.openInventory(gui);
    }

    // ---------------------------------------------------------------- kits

    public void openKitList(Player admin) {
        Inventory gui = Bukkit.createInventory(null, 54, KIT_LIST_TITLE);
        int slot = 0;
        for (Kit kit : kitManager.getAll()) {
            if (slot >= 45) break;
            gui.setItem(slot++, GuiUtil.namedItem(kit.getIcon(), "&f" + kit.getName(),
                    "&e&lClick to delete!"));
        }
        gui.setItem(49, GuiUtil.namedItem(Material.EMERALD, "&a&lCreate New Kit",
                "&7Captures your CURRENT", "&7inventory/armor as a kit.",
                "", "&e&lClick, then type a name!"));
        gui.setItem(45, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        admin.openInventory(gui);
    }

    // ---------------------------------------------------------------- maps (the two permanent, static arenas)

    public void openArenaList(Player admin) {
        Inventory gui = Bukkit.createInventory(null, 27, ARENA_LIST_TITLE);
        fillBorder(gui);

        Arena duel = arenaManager.getDuelArena();
        gui.setItem(11, GuiUtil.namedItem(Material.IRON_SWORD, "&c&lDuel Map",
                "&7World: " + duel.getWorldName(),
                "&7Spawns: " + duel.getSpawnPoints().size() + "/2",
                duel.isReadyForDuel() ? "&aReady" : "&cNot fully set up",
                "", "&e&lClick to manage!"));

        Arena gauntlet = arenaManager.getGauntletArena();
        int maxPartySize = plugin.getConfig().getInt("gauntlet.max-party-size", 4);
        gui.setItem(15, GuiUtil.namedItem(Material.WITHER_SKELETON_SKULL, "&5&lGauntlet Map",
                "&7World: " + gauntlet.getWorldName(),
                "&7Player spawns: " + gauntlet.getSpawnPoints().size() + "/" + maxPartySize,
                "&7Monster spawn: " + (gauntlet.getMonsterSpawnPoint() != null ? "&aSet" : "&cNot set"),
                "", "&e&lClick to manage!"));

        gui.setItem(22, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        admin.openInventory(gui);
    }

    public void openArenaDetail(Player admin, Arena.Type type) {
        openArenaDetail.put(admin.getUniqueId(), type);
        Arena arena = arenaManager.getArena(type);
        Inventory gui = Bukkit.createInventory(null, 27, ARENA_DETAIL_PREFIX + type.name());
        fillBorder(gui);

        if (type == Arena.Type.DUEL) {
            gui.setItem(4, GuiUtil.namedItem(Material.MAP, "&fDuel Map",
                    "&7World: " + arena.getWorldName(),
                    "&7Spawns configured: " + arena.getSpawnPoints().size() + "/2"));
        } else {
            int maxPartySize = plugin.getConfig().getInt("gauntlet.max-party-size", 4);
            gui.setItem(4, GuiUtil.namedItem(Material.MAP, "&fGauntlet Map",
                    "&7World: " + arena.getWorldName(),
                    "&7Player spawns configured: " + arena.getSpawnPoints().size() + "/" + maxPartySize,
                    "&7Monster spawn: " + (arena.getMonsterSpawnPoint() != null ? "&aSet" : "&cNot set")));
        }

        gui.setItem(10, GuiUtil.namedItem(Material.COMPASS, "&e&lSet World to Here",
                "&7Registers your CURRENT world", "&7as this map's world.",
                "&7Only do this once - existing", "&7spawn points don't move with it.",
                "", "&e&lClick to set!"));
        gui.setItem(12, GuiUtil.namedItem(Material.BLAZE_ROD, "&e&lGet Spawn Wand",
                "&7Right-click to place spawn points", "&7in your current world, in order.",
                "&7Auto-removed once every point", "&7for this map is placed.",
                "", "&e&lClick to receive!"));
        gui.setItem(14, GuiUtil.namedItem(Material.BARRIER, "&c&lClear All Spawn Points",
                type == Arena.Type.GAUNTLET ? "&7Also clears the monster spawn." : "",
                "", "&e&lClick to clear!"));

        gui.setItem(22, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        admin.openInventory(gui);
    }

    private void handleArenaDetailClick(Player admin, int slot) {
        Arena.Type type = openArenaDetail.get(admin.getUniqueId());
        if (type == null) { openArenaList(admin); return; }
        Arena arena = arenaManager.getArena(type);

        if (slot == 22) { openArenaList(admin); return; }

        switch (slot) {
            case 10 -> {
                String oldWorld = arena.getWorldName();
                if (oldWorld.equals(admin.getWorld().getName())) {
                    admin.sendMessage(ChatColor.YELLOW + "This map is already set to world '" + oldWorld + "'.");
                    return;
                }
                List<Location> previousSpawns = new ArrayList<>(arena.getSpawnPoints());
                Location previousMonsterSpawn = arena.getMonsterSpawnPoint();
                // Every existing spawn point (and the monster spawn, for
                // Gauntlet) was placed in the OLD world - a Bukkit
                // Location holds a direct reference to its World object,
                // not just a name, so leaving these in place after
                // switching worlds would silently point matches at
                // locations in a world this map no longer claims to be
                // in. Changing the map's world always means starting
                // spawn placement over from scratch.
                arena.getSpawnPoints().clear();
                if (type == Arena.Type.GAUNTLET) arena.setMonsterSpawnPoint(null);
                arena.setWorldName(admin.getWorld().getName());
                if (!arenaManager.save()) {
                    // Roll back ALL of the in-memory changes to match
                    // what's actually on disk - the world, and the spawn
                    // points/monster spawn that were just cleared.
                    arena.setWorldName(oldWorld);
                    arena.getSpawnPoints().addAll(previousSpawns);
                    arena.setMonsterSpawnPoint(previousMonsterSpawn);
                    admin.sendMessage(ChatColor.RED + "Failed to save arenas.yml - the map's world was NOT "
                            + "changed. Check the console for the error.");
                    openArenaDetail(admin, type);
                    return;
                }
                admin.sendMessage(ChatColor.GREEN + "Set this map's world to '" + admin.getWorld().getName() + "'.");
                if (!previousSpawns.isEmpty() || previousMonsterSpawn != null) {
                    admin.sendMessage(ChatColor.YELLOW + "This map's existing spawn points were cleared, since "
                            + "they were set for the previous world - use the spawn wand to place new ones.");
                }
                openArenaDetail(admin, type);
            }
            case 12 -> {
                var leftover = admin.getInventory().addItem(spawnWandListener.createWand(type));
                if (!leftover.isEmpty()) {
                    admin.sendMessage(ChatColor.RED + "Your inventory is full - couldn't give you the spawn wand. "
                            + "Free up a slot and try again.");
                    return;
                }
                admin.sendMessage(ChatColor.GREEN + "You received the spawn wand for this map. Right-click to place points.");
                admin.closeInventory();
            }
            case 14 -> {
                List<Location> previousSpawns = new ArrayList<>(arena.getSpawnPoints());
                Location previousMonsterSpawn = arena.getMonsterSpawnPoint();
                arena.getSpawnPoints().clear();
                if (type == Arena.Type.GAUNTLET) arena.setMonsterSpawnPoint(null);
                if (!arenaManager.save()) {
                    // Roll back the in-memory change to match what's
                    // actually on disk, same reasoning as the world-change
                    // case above - otherwise a failed save here would
                    // silently discard the admin's spawn points from
                    // memory even though the file on disk still has them,
                    // and a later successful save would then wipe the
                    // file too.
                    arena.getSpawnPoints().addAll(previousSpawns);
                    arena.setMonsterSpawnPoint(previousMonsterSpawn);
                    admin.sendMessage(ChatColor.RED + "Failed to save arenas.yml - spawn points were NOT "
                            + "cleared. Check the console for the error.");
                    openArenaDetail(admin, type);
                    return;
                }
                admin.sendMessage(ChatColor.GREEN + "Cleared all spawn points for this map.");
                openArenaDetail(admin, type);
            }
            default -> {}
        }
    }

    private void openDeleteKitConfirm(Player admin, Kit kit) {
        pendingDeleteKit.put(admin.getUniqueId(), kit.getId());
        Inventory gui = Bukkit.createInventory(null, 27, DELETE_KIT_CONFIRM_TITLE);
        fillBorder(gui);
        gui.setItem(13, GuiUtil.namedItem(kit.getIcon(), "&f" + kit.getName(), "&7Delete this kit?"));
        gui.setItem(11, GuiUtil.namedItem(Material.LIME_WOOL, "&a&lConfirm", "", "&e&lClick to delete!"));
        gui.setItem(15, GuiUtil.namedItem(Material.RED_WOOL, "&c&lCancel", "", "&e&lClick to cancel!"));
        admin.openInventory(gui);
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        boolean ours = title.equals(ADMIN_MAIN_TITLE) || title.equals(KIT_LIST_TITLE) || title.equals(ARENA_LIST_TITLE)
                || title.startsWith(ARENA_DETAIL_PREFIX) || title.equals(DELETE_KIT_CONFIRM_TITLE)
                || title.equals(STORE_LIST_TITLE) || title.equals(DELETE_STORE_ITEM_CONFIRM_TITLE);
        if (!ours) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player admin)) return;
        if (!admin.hasPermission("wsmpduels.admin")) {
            admin.closeInventory();
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        int slot = event.getRawSlot();

        if (title.equals(ADMIN_MAIN_TITLE)) {
            if (slot == 11) openKitList(admin);
            else if (slot == 13) openStoreList(admin);
            else if (slot == 15) openArenaList(admin);
            return;
        }

        if (title.equals(KIT_LIST_TITLE)) {
            if (slot == 45) { openMain(admin); return; }
            if (slot == 49) {
                awaitingInput.put(admin.getUniqueId(), AwaitingInput.KIT_NAME);
                admin.closeInventory();
                admin.sendMessage(ChatColor.YELLOW + "Type the new kit's name in chat. Your CURRENT inventory/armor will be saved as this kit.");
                return;
            }
            String kitName = stripColor(clicked);
            Kit kit = kitManager.getByName(kitName);
            if (kit != null) openDeleteKitConfirm(admin, kit);
            return;
        }

        if (title.equals(ARENA_LIST_TITLE)) {
            if (slot == 22) { openMain(admin); return; }
            if (slot == 11) { openArenaDetail(admin, Arena.Type.DUEL); return; }
            if (slot == 15) { openArenaDetail(admin, Arena.Type.GAUNTLET); return; }
            return;
        }

        if (title.startsWith(ARENA_DETAIL_PREFIX)) {
            handleArenaDetailClick(admin, slot);
            return;
        }

        if (title.equals(DELETE_KIT_CONFIRM_TITLE)) {
            handleDeleteKitConfirm(admin, slot);
            return;
        }

        if (title.equals(STORE_LIST_TITLE)) {
            if (slot == 45) { openMain(admin); return; }
            if (slot == 49) {
                awaitingInput.put(admin.getUniqueId(), AwaitingInput.STORE_ITEM_NAME);
                admin.closeInventory();
                admin.sendMessage(ChatColor.YELLOW + "Type the new store item's name in chat. "
                        + "Your CURRENT main-hand item will be the reward.");
                return;
            }
            java.util.List<UUID> slotToId = storeListSlots.get(admin.getUniqueId());
            if (slotToId == null || slot < 0 || slot >= slotToId.size()) return;
            StoreItem item = storeManager.get(slotToId.get(slot));
            if (item != null) openDeleteStoreItemConfirm(admin, item);
            return;
        }

        if (title.equals(DELETE_STORE_ITEM_CONFIRM_TITLE)) {
            UUID itemId = pendingDeleteStoreItem.remove(admin.getUniqueId());
            if (itemId == null) return;
            if (slot == 11) {
                if (!storeManager.remove(itemId)) {
                    admin.sendMessage(ChatColor.RED + "Failed to save store.yml - the item was NOT deleted. "
                            + "Check the console for the error.");
                } else {
                    admin.sendMessage(ChatColor.GREEN + "Store item deleted.");
                }
            } else {
                admin.sendMessage(ChatColor.GRAY + "Cancelled.");
            }
            openStoreList(admin);
        }
    }

    private void handleDeleteKitConfirm(Player admin, int slot) {
        UUID kitId = pendingDeleteKit.remove(admin.getUniqueId());
        if (kitId == null) return;
        if (slot == 11) {
            if (!kitManager.remove(kitId)) {
                admin.sendMessage(ChatColor.RED + "Failed to save kits.yml - the kit was NOT deleted. "
                        + "Check the console for the error.");
            } else {
                admin.sendMessage(ChatColor.GREEN + "Kit deleted.");
            }
        } else {
            admin.sendMessage(ChatColor.GRAY + "Cancelled.");
        }
        openKitList(admin);
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

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        // Admin-only state, so the practical impact is small (bounded by
        // however many distinct ops/admins ever use these menus), but
        // cleaning it up on disconnect is cheap and keeps this
        // consistent with every other GUI's own cleanup.
        UUID id = event.getPlayer().getUniqueId();
        awaitingInput.remove(id);
        openArenaDetail.remove(id);
        pendingDeleteKit.remove(id);
        pendingStoreItemName.remove(id);
        pendingDeleteStoreItem.remove(id);
        storeListSlots.remove(id);
    }
}
