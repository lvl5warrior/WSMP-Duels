package com.warriorssmp.duels;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * A wand item (right-click to place a point) for setting up the two
 * static arenas' spawn points, instead of the old "click a GUI button
 * while standing where you want the spawn" flow. One wand handles every
 * point a given arena type needs, in a fixed order:
 *  - Duel: exactly 2 player spawn points.
 *  - Gauntlet: up to 4 player spawn points, THEN the single monster
 *    spawn point.
 * Once the last needed point for that wand is placed, it's automatically
 * removed from the admin's inventory - there's nothing left for it to do.
 */
public class SpawnWandListener implements Listener {

    private static final NamespacedKey WAND_TYPE_KEY = new NamespacedKey("wsmpduels", "spawn_wand_type");

    private final DuelsPlugin plugin;
    private final ArenaManager arenaManager;
    /** Read fresh from config on every placement rather than cached at
     *  construction time - GauntletManager reads this same
     *  gauntlet.max-party-size setting the same way, and the two must
     *  never drift out of sync with each other (a wand that places fewer
     *  points than GauntletManager expects a party to need would leave
     *  the Gauntlet map permanently unable to fill its last slot). */
    private int gauntletMaxPlayerSpawns() {
        return plugin.getConfig().getInt("gauntlet.max-party-size", 4);
    }

    public SpawnWandListener(DuelsPlugin plugin, ArenaManager arenaManager) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
    }

    /** Builds a fresh wand for the given arena type - always starts this
     *  admin's placement sequence over from point 1, regardless of
     *  whatever partial progress a PREVIOUS wand of this type might have
     *  left on the arena (that existing progress is still there in
     *  arenas.yml either way; this wand just continues filling in
     *  whatever's still empty). */
    public ItemStack createWand(Arena.Type type) {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        String label = type == Arena.Type.DUEL ? "Duel" : "Gauntlet";
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&e&l" + label + " Spawn Wand"));
        meta.setLore(java.util.List.of(
                ChatColor.translateAlternateColorCodes('&', "&7Right-click a location to set"),
                ChatColor.translateAlternateColorCodes('&', "&7the next needed spawn point."),
                ChatColor.translateAlternateColorCodes('&', "&7Removed automatically once"),
                ChatColor.translateAlternateColorCodes('&', "&7every point is placed.")
        ));
        meta.getPersistentDataContainer().set(WAND_TYPE_KEY, PersistentDataType.STRING, type.name());
        wand.setItemMeta(meta);
        return wand;
    }

    private Arena.Type wandType(ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String stored = meta.getPersistentDataContainer().get(WAND_TYPE_KEY, PersistentDataType.STRING);
        if (stored == null) return null;
        try {
            return Arena.Type.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) return;
        // Bukkit fires this event separately for the main hand and the
        // off hand for what the player experiences as a single physical
        // right-click - without this check, a wand placement would run
        // TWICE per click (placing two spawn points at once) whenever
        // the player has anything at all in their off hand slot. Only
        // ever act on the main-hand firing.
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        ItemStack held = event.getItem();
        Arena.Type type = wandType(held);
        if (type == null) return;
        event.setCancelled(true);

        Player admin = event.getPlayer();
        if (!admin.hasPermission("wsmpduels.admin")) {
            admin.sendMessage(ChatColor.RED + "You don't have permission to use this wand.");
            return;
        }
        Arena arena = arenaManager.getArena(type);

        if (!admin.getWorld().getName().equals(arena.getWorldName())) {
            admin.sendMessage(ChatColor.RED + "This wand is for the " + type.name() + " map, in world '"
                    + arena.getWorldName() + "' - you're standing in a different world.");
            return;
        }

        if (type == Arena.Type.DUEL) {
            placeDuelPoint(admin, arena, held);
        } else {
            placeGauntletPoint(admin, arena, held);
        }
    }

    private void placeDuelPoint(Player admin, Arena arena, ItemStack wand) {
        if (arena.getSpawnPoints().size() >= 2) {
            admin.sendMessage(ChatColor.YELLOW + "Both duel spawn points are already set - removing the wand.");
            removeWand(admin, wand);
            return;
        }
        arena.getSpawnPoints().add(admin.getLocation().clone());
        int placed = arena.getSpawnPoints().size();
        if (!arenaManager.save()) {
            admin.sendMessage(ChatColor.RED + "Spawn point " + placed + "/2 was set in memory, but FAILED TO "
                    + "SAVE to arenas.yml - check the console for the error, and don't restart the server "
                    + "until this is resolved or the point will be lost.");
            return;
        }
        admin.sendMessage(ChatColor.GREEN + "Set duel spawn point " + placed + "/2.");
        if (placed >= 2) {
            admin.sendMessage(ChatColor.GREEN + "Duel map is fully set up! Removing the wand.");
            removeWand(admin, wand);
        }
    }

    private void placeGauntletPoint(Player admin, Arena arena, ItemStack wand) {
        int maxPlayerSpawns = gauntletMaxPlayerSpawns();
        if (arena.getSpawnPoints().size() < maxPlayerSpawns) {
            arena.getSpawnPoints().add(admin.getLocation().clone());
            int placed = arena.getSpawnPoints().size();
            if (!arenaManager.save()) {
                admin.sendMessage(ChatColor.RED + "Spawn point " + placed + "/" + maxPlayerSpawns
                        + " was set in memory, but FAILED TO SAVE to arenas.yml - check the console for the "
                        + "error, and don't restart the server until this is resolved or the point will be lost.");
                return;
            }
            admin.sendMessage(ChatColor.GREEN + "Set Gauntlet player spawn point " + placed + "/" + maxPlayerSpawns + ".");
            if (placed < maxPlayerSpawns) return;
            admin.sendMessage(ChatColor.YELLOW + "Now right-click to set the monster spawn point.");
            return;
        }

        if (arena.getMonsterSpawnPoint() == null) {
            arena.setMonsterSpawnPoint(admin.getLocation().clone());
            if (!arenaManager.save()) {
                admin.sendMessage(ChatColor.RED + "The monster spawn point was set in memory, but FAILED TO "
                        + "SAVE to arenas.yml - check the console for the error, and don't restart the server "
                        + "until this is resolved or the point will be lost.");
                return;
            }
            admin.sendMessage(ChatColor.GREEN + "Set the monster spawn point.");
            admin.sendMessage(ChatColor.GREEN + "Gauntlet map is fully set up! Removing the wand.");
            removeWand(admin, wand);
            return;
        }

        admin.sendMessage(ChatColor.YELLOW + "Gauntlet map is already fully set up - removing the wand.");
        removeWand(admin, wand);
    }

    private void removeWand(Player admin, ItemStack wand) {
        admin.getInventory().removeItem(wand);
    }
}
