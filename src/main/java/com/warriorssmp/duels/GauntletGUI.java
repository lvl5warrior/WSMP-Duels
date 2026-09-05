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

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** The roguelike buff-pick screen shown every 5 waves cleared in The
 *  Gauntlet - 3 random buffs offered, pick one to stack it and continue
 *  to the next wave. Also the per-round item-pick screen shown after
 *  every other wave clears, offering 3 items pulled from that wave's
 *  loot tier to choose one from during the short break before the next
 *  wave. */
public class GauntletGUI implements Listener {

    private static final String BUFF_PICK_TITLE = ChatColor.translateAlternateColorCodes('&', "&5&lChoose a Buff");
    private static final String ITEM_PICK_TITLE = ChatColor.translateAlternateColorCodes('&', "&6&lChoose an Item");

    private final GauntletManager gauntletManager;
    private final Random random = new Random();
    private final Map<UUID, GauntletBuff[]> offeredBuffs = new HashMap<>();
    private final Map<UUID, ItemStack[]> offeredItems = new HashMap<>();

    public GauntletGUI(GauntletManager gauntletManager) {
        this.gauntletManager = gauntletManager;
    }

    public void openBuffPick(Player player, GauntletRun run) {
        GauntletBuff[] all = GauntletBuff.values();
        GauntletBuff[] offered = new GauntletBuff[3];
        // Simple sample-without-replacement over a small fixed array.
        boolean[] used = new boolean[all.length];
        for (int i = 0; i < offered.length; i++) {
            int pick;
            do {
                pick = random.nextInt(all.length);
            } while (used[pick]);
            used[pick] = true;
            offered[i] = all[pick];
        }
        offeredBuffs.put(player.getUniqueId(), offered);

        Inventory gui = Bukkit.createInventory(null, 27, BUFF_PICK_TITLE);
        ItemStack border = GuiUtil.coloredPane(Material.PURPLE_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < gui.getSize(); i++) gui.setItem(i, border);

        int[] slots = {11, 13, 15};
        for (int i = 0; i < offered.length; i++) {
            GauntletBuff buff = offered[i];
            int currentStacks = run.getBuffStacks(player.getUniqueId(), buff);
            gui.setItem(slots[i], GuiUtil.namedItem(iconFor(buff), buff.getDisplayName(),
                    buff.getDescription(),
                    currentStacks > 0 ? "&7Current stacks: &f" + currentStacks : "",
                    "", "&e&lClick to pick!"));
        }
        player.openInventory(gui);
    }

    /** Offers 3 items pulled from the exact same tier pool a kill's loot
     *  roll uses for the current wave, so what's on offer here always
     *  matches what's actually reachable through play at this point in
     *  the run. */
    public void openItemPick(Player player, GauntletRun run) {
        Material[] pool = gauntletManager.lootPoolForWave(run.getWave());
        ItemStack[] offered = new ItemStack[3];
        for (int i = 0; i < offered.length; i++) {
            Material chosen = pool[random.nextInt(pool.length)];
            int amount = chosen == Material.IRON_NUGGET || chosen == Material.ROTTEN_FLESH || chosen == Material.ARROW
                    ? 1 + random.nextInt(4) : 1;
            offered[i] = new ItemStack(chosen, amount);
        }
        offeredItems.put(player.getUniqueId(), offered);

        Inventory gui = Bukkit.createInventory(null, 27, ITEM_PICK_TITLE);
        ItemStack border = GuiUtil.coloredPane(Material.YELLOW_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < gui.getSize(); i++) gui.setItem(i, border);

        int[] slots = {11, 13, 15};
        for (int i = 0; i < offered.length; i++) {
            ItemStack display = offered[i].clone();
            display = GuiUtil.namedItem(display.getType(), niceName(display.getType()),
                    display.getAmount() > 1 ? "&7Amount: &f" + display.getAmount() : "",
                    "", "&e&lClick to take it!");
            display.setAmount(offered[i].getAmount());
            gui.setItem(slots[i], display);
        }
        player.openInventory(gui);
    }

    private String niceName(Material material) {
        String raw = material.name().toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String word : raw.split(" ")) {
            if (!word.isEmpty()) sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    private Material iconFor(GauntletBuff buff) {
        return switch (buff) {
            case VITALITY -> Material.GOLDEN_APPLE;
            case STRENGTH -> Material.IRON_SWORD;
            case SWIFTNESS -> Material.FEATHER;
            case REGENERATION -> Material.GHAST_TEAR;
            case RESILIENCE -> Material.SHIELD;
            case LUCKY -> Material.EMERALD;
            case SECOND_WIND -> Material.TOTEM_OF_UNDYING;
        };
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(BUFF_PICK_TITLE)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int[] slots = {11, 13, 15};
        int slot = event.getRawSlot();
        int index = -1;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) { index = i; break; }
        }
        if (index == -1) return;

        GauntletBuff[] offered = offeredBuffs.remove(player.getUniqueId());
        if (offered == null) return;
        GauntletRun run = gauntletManager.getRun(player.getUniqueId());
        if (run == null) { player.closeInventory(); return; }

        player.closeInventory();
        gauntletManager.applyBuff(player, run, offered[index]);
        gauntletManager.continueAfterBuffPick(run, player.getUniqueId());
    }

    @EventHandler
    public void onItemPickClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(ITEM_PICK_TITLE)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int[] slots = {11, 13, 15};
        int slot = event.getRawSlot();
        int index = -1;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) { index = i; break; }
        }
        if (index == -1) return;

        ItemStack[] offered = offeredItems.remove(player.getUniqueId());
        if (offered == null) return;

        player.closeInventory();
        var leftover = player.getInventory().addItem(offered[index]);
        for (ItemStack extra : leftover.values()) player.getWorld().dropItem(player.getLocation(), extra);
        player.sendMessage(ChatColor.GREEN + "You picked up " + niceName(offered[index].getType())
                + (offered[index].getAmount() > 1 ? " x" + offered[index].getAmount() : "") + "!");
    }
}
