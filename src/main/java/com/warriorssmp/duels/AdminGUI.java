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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AdminGUI implements Listener {

    public enum AwaitingInput { KIT_NAME, ARENA_NAME, STORE_ITEM_NAME, STORE_ITEM_PRICE }

    private static final String ADMIN_MAIN_TITLE = ChatColor.translateAlternateColorCodes('&', "&4&lDuels Admin");
    private static final String KIT_LIST_TITLE = ChatColor.translateAlternateColorCodes('&', "&4&lManage Kits");
    private static final String ARENA_LIST_TITLE = ChatColor.translateAlternateColorCodes('&', "&4&lManage Arenas");
    private static final String ARENA_DETAIL_PREFIX = ChatColor.translateAlternateColorCodes('&', "&4&lArena: ");
    private static final String DELETE_KIT_CONFIRM_TITLE = ChatColor.translateAlternateColorCodes('&', "&4&lConfirm Delete Kit");
    private static final String DELETE_ARENA_CONFIRM_TITLE = ChatColor.translateAlternateColorCodes('&', "&4&lConfirm Delete Arena");
    private static final String STORE_LIST_TITLE = ChatColor.translateAlternateColorCodes('&', "&4&lManage Store");
    private static final String DELETE_STORE_ITEM_CONFIRM_TITLE = ChatColor.translateAlternateColorCodes('&', "&4&lConfirm Delete Item");

    private final DuelsPlugin plugin;
    private final KitManager kitManager;
    private final ArenaManager arenaManager;
    private final StoreManager storeManager;

    private final Map<UUID, AwaitingInput> awaitingInput = new HashMap<>();
    private final Map<UUID, Arena.Type> pendingArenaType = new HashMap<>();
    private final Map<UUID, UUID> openArenaDetail = new HashMap<>();
    private final Map<UUID, UUID> pendingDeleteKit = new HashMap<>();
    private final Map<UUID, UUID> pendingDeleteArena = new HashMap<>();
    private final Map<UUID, String> pendingStoreItemName = new HashMap<>();
    private final Map<UUID, UUID> pendingDeleteStoreItem = new HashMap<>();
    private final Map<UUID, java.util.List<UUID>> storeListSlots = new HashMap<>();

    public AdminGUI(DuelsPlugin plugin, KitManager kitManager, ArenaManager arenaManager, StoreManager storeManager) {
        this.plugin = plugin;
        this.kitManager = kitManager;
        this.arenaManager = arenaManager;
        this.storeManager = storeManager;
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
                kitManager.add(kit);
                admin.sendMessage(ChatColor.GREEN + "Created kit '" + kit.getName() + "' from your current inventory.");
                openKitList(admin);
            }
            case ARENA_NAME -> {
                Arena.Type arenaType = pendingArenaType.remove(admin.getUniqueId());
                if (arenaType == null) arenaType = Arena.Type.DUEL;
                Arena arena = new Arena(UUID.randomUUID(), text.trim(), arenaType, admin.getWorld().getName());
                arenaManager.add(arena);
                admin.sendMessage(ChatColor.GREEN + "Created " + arenaType.name() + " arena '" + arena.getName()
                        + "' in world '" + admin.getWorld().getName() + "'.");
                openArenaDetail(admin, arena);
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
                storeManager.add(item);
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
        gui.setItem(15, GuiUtil.namedItem(Material.MAP, "&c&lManage Arenas", "&7Register arenas and", "&7set spawn points.", "", "&e&lClick to open!"));
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

    // ---------------------------------------------------------------- arenas

    public void openArenaList(Player admin) {
        Inventory gui = Bukkit.createInventory(null, 54, ARENA_LIST_TITLE);
        int slot = 0;
        for (Arena arena : arenaManager.getAll()) {
            if (slot >= 45) break;
            String spawnInfo = arena.getType() == Arena.Type.WAR
                    ? "&7Team 1 spawns: " + arena.getTeamSpawns(0).size() + " &7| Team 2: " + arena.getTeamSpawns(1).size()
                    : "&7Spawns: " + arena.getSpawnPoints().size();
            gui.setItem(slot++, GuiUtil.namedItem(Material.MAP, "&f" + arena.getName(),
                    "&7Type: " + arena.getType().name(),
                    "&7World: " + arena.getWorldName(),
                    spawnInfo,
                    "", "&e&lClick to manage!"));
        }
        gui.setItem(48, GuiUtil.namedItem(Material.EMERALD, "&a&lCreate New Duel Arena",
                "&7Registers your CURRENT", "&7world for 1v1 duels.",
                "", "&e&lClick, then type a name!"));
        gui.setItem(50, GuiUtil.namedItem(Material.DIAMOND_SWORD, "&6&lCreate New War Arena",
                "&7Registers your CURRENT", "&7world for team wars.",
                "", "&e&lClick, then type a name!"));
        gui.setItem(52, GuiUtil.namedItem(Material.WITHER_SKELETON_SKULL, "&5&lCreate New Gauntlet Arena",
                "&7Registers your CURRENT", "&7world for The Gauntlet.",
                "", "&e&lClick, then type a name!"));
        gui.setItem(45, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        admin.openInventory(gui);
    }

    public void openArenaDetail(Player admin, Arena arena) {
        openArenaDetail.put(admin.getUniqueId(), arena.getId());
        Inventory gui = Bukkit.createInventory(null, 27, ARENA_DETAIL_PREFIX + arena.getName());
        fillBorder(gui);

        if (arena.getType() == Arena.Type.WAR) {
            String flag1 = arena.getFlagLocation(0) != null ? "&aSet" : "&cNot set";
            String flag2 = arena.getFlagLocation(1) != null ? "&aSet" : "&cNot set";
            gui.setItem(4, GuiUtil.namedItem(Material.MAP, "&f" + arena.getName(),
                    "&7Type: " + arena.getType().name(),
                    "&7World: " + arena.getWorldName(),
                    "&7Team 1 spawns: " + arena.getTeamSpawns(0).size(),
                    "&7Team 2 spawns: " + arena.getTeamSpawns(1).size(),
                    "&7Team 1 flag: " + flag1, "&7Team 2 flag: " + flag2));

            gui.setItem(10, GuiUtil.namedItem(Material.RED_WOOL, "&c&lAdd Team 1 Spawn Here",
                    "&7Uses your CURRENT position", "&7as a Team 1 spawn point.",
                    "&7For 10v10, add 10 of these!", "", "&e&lClick to add!"));
            gui.setItem(12, GuiUtil.namedItem(Material.BLUE_WOOL, "&9&lAdd Team 2 Spawn Here",
                    "&7Uses your CURRENT position", "&7as a Team 2 spawn point.",
                    "&7For 10v10, add 10 of these!", "", "&e&lClick to add!"));
            gui.setItem(14, GuiUtil.namedItem(Material.BARRIER, "&c&lClear All Spawn Points", "", "&e&lClick to clear!"));
            gui.setItem(16, GuiUtil.namedItem(Material.TNT, "&4&lDelete Arena", "&cRemoves this arena entirely.", "", "&e&lClick to delete!"));

            gui.setItem(19, GuiUtil.namedItem(Material.RED_BANNER, "&c&lSet Team 1 Flag Here",
                    "&7Uses your CURRENT position", "&7as Team 1's flag base.",
                    "&7Needed for Capture the Flag.", "", "&e&lClick to set!"));
            gui.setItem(21, GuiUtil.namedItem(Material.BLUE_BANNER, "&9&lSet Team 2 Flag Here",
                    "&7Uses your CURRENT position", "&7as Team 2's flag base.",
                    "&7Needed for Capture the Flag.", "", "&e&lClick to set!"));
        } else {
            gui.setItem(4, GuiUtil.namedItem(Material.MAP, "&f" + arena.getName(),
                    "&7Type: " + arena.getType().name(),
                    "&7World: " + arena.getWorldName(),
                    "&7Spawns configured: " + arena.getSpawnPoints().size()));

            gui.setItem(11, GuiUtil.namedItem(Material.LIME_DYE, "&a&lAdd Spawn Point Here",
                    "&7Uses your CURRENT position", "&7as the next spawn point.",
                    arena.getType() == Arena.Type.GAUNTLET ? "&7(1 per player, up to 4)" : "&7(Duels need at least 2)",
                    "", "&e&lClick to add!"));
            gui.setItem(13, GuiUtil.namedItem(Material.BARRIER, "&c&lClear All Spawn Points", "", "&e&lClick to clear!"));
            gui.setItem(15, GuiUtil.namedItem(Material.TNT, "&4&lDelete Arena", "&cRemoves this arena entirely.", "", "&e&lClick to delete!"));
        }

        gui.setItem(22, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        admin.openInventory(gui);
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

    /** Warns an admin trying to add a spawn point or flag location while
     *  standing in a different world than the one this arena is
     *  registered to - a mismatch here would otherwise silently point
     *  into the wrong world, most seriously for Gauntlet arenas since
     *  the whole instance-cloning system copies based on the arena's
     *  registered world name alone. */
    private void warnWrongWorld(Player admin, Arena arena) {
        admin.sendMessage(ChatColor.RED + "You're in world '" + admin.getWorld().getName()
                + "', but this arena is registered to world '" + arena.getWorldName()
                + "' - go there first, or spawn points won't line up correctly.");
    }

    private void openDeleteArenaConfirm(Player admin, Arena arena) {
        pendingDeleteArena.put(admin.getUniqueId(), arena.getId());
        Inventory gui = Bukkit.createInventory(null, 27, DELETE_ARENA_CONFIRM_TITLE);
        fillBorder(gui);
        gui.setItem(13, GuiUtil.namedItem(Material.MAP, "&f" + arena.getName(), "&7Delete this arena?"));
        gui.setItem(11, GuiUtil.namedItem(Material.LIME_WOOL, "&a&lConfirm", "", "&e&lClick to delete!"));
        gui.setItem(15, GuiUtil.namedItem(Material.RED_WOOL, "&c&lCancel", "", "&e&lClick to cancel!"));
        admin.openInventory(gui);
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        boolean ours = title.equals(ADMIN_MAIN_TITLE) || title.equals(KIT_LIST_TITLE) || title.equals(ARENA_LIST_TITLE)
                || title.startsWith(ARENA_DETAIL_PREFIX) || title.equals(DELETE_KIT_CONFIRM_TITLE) || title.equals(DELETE_ARENA_CONFIRM_TITLE)
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
            if (slot == 45) { openMain(admin); return; }
            if (slot == 48) {
                pendingArenaType.put(admin.getUniqueId(), Arena.Type.DUEL);
                awaitingInput.put(admin.getUniqueId(), AwaitingInput.ARENA_NAME);
                admin.closeInventory();
                admin.sendMessage(ChatColor.YELLOW + "Type the new duel arena's name in chat. Your CURRENT world will be registered for it.");
                return;
            }
            if (slot == 50) {
                pendingArenaType.put(admin.getUniqueId(), Arena.Type.WAR);
                awaitingInput.put(admin.getUniqueId(), AwaitingInput.ARENA_NAME);
                admin.closeInventory();
                admin.sendMessage(ChatColor.YELLOW + "Type the new war arena's name in chat. Your CURRENT world will be registered for it.");
                return;
            }
            if (slot == 52) {
                pendingArenaType.put(admin.getUniqueId(), Arena.Type.GAUNTLET);
                awaitingInput.put(admin.getUniqueId(), AwaitingInput.ARENA_NAME);
                admin.closeInventory();
                admin.sendMessage(ChatColor.YELLOW + "Type the new Gauntlet arena's name in chat. Your CURRENT world will be registered for it.");
                return;
            }
            String arenaName = stripColor(clicked);
            for (Arena arena : arenaManager.getAll()) {
                if (arena.getName().equalsIgnoreCase(arenaName)) {
                    openArenaDetail(admin, arena);
                    break;
                }
            }
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

        if (title.equals(DELETE_ARENA_CONFIRM_TITLE)) {
            handleDeleteArenaConfirm(admin, slot);
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
                storeManager.remove(itemId);
                admin.sendMessage(ChatColor.GREEN + "Store item deleted.");
            } else {
                admin.sendMessage(ChatColor.GRAY + "Cancelled.");
            }
            openStoreList(admin);
        }
    }

    private void handleArenaDetailClick(Player admin, int slot) {
        UUID arenaId = openArenaDetail.get(admin.getUniqueId());
        Arena arena = arenaId != null ? arenaManager.get(arenaId) : null;
        if (arena == null) { openArenaList(admin); return; }

        if (slot == 22) { openArenaList(admin); return; }

        if (arena.getType() == Arena.Type.WAR) {
            boolean wrongWorld = !admin.getWorld().getName().equals(arena.getWorldName());
            switch (slot) {
                case 10 -> {
                    if (wrongWorld) { warnWrongWorld(admin, arena); return; }
                    arena.getTeamSpawns(0).add(admin.getLocation().clone());
                    arenaManager.save();
                    admin.sendMessage(ChatColor.GREEN + "Added Team 1 spawn point " + arena.getTeamSpawns(0).size() + ".");
                    openArenaDetail(admin, arena);
                }
                case 12 -> {
                    if (wrongWorld) { warnWrongWorld(admin, arena); return; }
                    arena.getTeamSpawns(1).add(admin.getLocation().clone());
                    arenaManager.save();
                    admin.sendMessage(ChatColor.GREEN + "Added Team 2 spawn point " + arena.getTeamSpawns(1).size() + ".");
                    openArenaDetail(admin, arena);
                }
                case 14 -> {
                    arena.getTeamSpawnPoints().clear();
                    arenaManager.save();
                    admin.sendMessage(ChatColor.GREEN + "Cleared all spawn points.");
                    openArenaDetail(admin, arena);
                }
                case 16 -> openDeleteArenaConfirm(admin, arena);
                case 19 -> {
                    if (wrongWorld) { warnWrongWorld(admin, arena); return; }
                    arena.setFlagLocation(0, admin.getLocation().clone());
                    arenaManager.save();
                    admin.sendMessage(ChatColor.GREEN + "Set Team 1's flag location.");
                    openArenaDetail(admin, arena);
                }
                case 21 -> {
                    if (wrongWorld) { warnWrongWorld(admin, arena); return; }
                    arena.setFlagLocation(1, admin.getLocation().clone());
                    arenaManager.save();
                    admin.sendMessage(ChatColor.GREEN + "Set Team 2's flag location.");
                    openArenaDetail(admin, arena);
                }
                default -> {}
            }
            return;
        }

        switch (slot) {
            case 11 -> {
                // A real, confirmed gap: nothing previously stopped an
                // admin from adding a spawn point while standing in a
                // DIFFERENT world than the one the arena was originally
                // created in. For Gauntlet arenas specifically, the whole
                // instance-cloning system copies based on the arena's
                // registered world name, not on where any individual
                // spawn point actually is - a mismatched spawn added by
                // mistake would silently point into the wrong copied
                // world once a run actually starts.
                if (!admin.getWorld().getName().equals(arena.getWorldName())) {
                    warnWrongWorld(admin, arena);
                    return;
                }
                arena.getSpawnPoints().add(admin.getLocation().clone());
                arenaManager.save();
                admin.sendMessage(ChatColor.GREEN + "Added spawn point " + arena.getSpawnPoints().size() + ".");
                openArenaDetail(admin, arena);
            }
            case 13 -> {
                arena.getSpawnPoints().clear();
                arenaManager.save();
                admin.sendMessage(ChatColor.GREEN + "Cleared all spawn points.");
                openArenaDetail(admin, arena);
            }
            case 15 -> openDeleteArenaConfirm(admin, arena);
            default -> {}
        }
    }

    private void handleDeleteKitConfirm(Player admin, int slot) {
        UUID kitId = pendingDeleteKit.remove(admin.getUniqueId());
        if (kitId == null) return;
        if (slot == 11) {
            kitManager.remove(kitId);
            admin.sendMessage(ChatColor.GREEN + "Kit deleted.");
        } else {
            admin.sendMessage(ChatColor.GRAY + "Cancelled.");
        }
        openKitList(admin);
    }

    private void handleDeleteArenaConfirm(Player admin, int slot) {
        UUID arenaId = pendingDeleteArena.remove(admin.getUniqueId());
        if (arenaId == null) return;
        if (slot == 11) {
            arenaManager.remove(arenaId);
            admin.sendMessage(ChatColor.GREEN + "Arena deleted.");
        } else {
            admin.sendMessage(ChatColor.GRAY + "Cancelled.");
        }
        openArenaList(admin);
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
}
