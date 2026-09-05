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
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The merchant shown during a Gauntlet break - sells everything the
 * Gauntlet can drop (loot and food alike) for Gauntlet Coins, a
 * per-run-only currency that never touches Vault money or Honor, then
 * lets that same currency buy armor, weapons, food, and potions. The
 * merchant's stock gets stronger the deeper into the run it's visited,
 * using the same wave-bracket idea as loot drops.
 */
public class GauntletMerchantGUI implements Listener {

    private static final String MAIN_TITLE = ChatColor.translateAlternateColorCodes('&', "&6&lGauntlet Merchant");
    private static final String ARMOR_TITLE = ChatColor.translateAlternateColorCodes('&', "&6&lBuy Armor");
    private static final String WEAPON_TITLE = ChatColor.translateAlternateColorCodes('&', "&6&lBuy Weapons");
    private static final String FOOD_TITLE = ChatColor.translateAlternateColorCodes('&', "&6&lBuy Food");
    private static final String POTION_TITLE = ChatColor.translateAlternateColorCodes('&', "&6&lBuy Potions");

    /** What a single sellable drop is worth in Gauntlet Coins - the
     *  item's own rarity is what determines its price, not the wave it
     *  was found on, since a diamond sword found on wave 25 (lucky drop)
     *  is worth exactly as much as one found on wave 90. */
    private static final Map<Material, Integer> SELL_PRICES = new EnumMap<>(Material.class);
    static {
        SELL_PRICES.put(Material.IRON_NUGGET, 1);
        SELL_PRICES.put(Material.ROTTEN_FLESH, 1);
        SELL_PRICES.put(Material.ARROW, 1);
        SELL_PRICES.put(Material.LEATHER, 2);
        SELL_PRICES.put(Material.BREAD, 2);
        SELL_PRICES.put(Material.COOKED_PORKCHOP, 2);
        SELL_PRICES.put(Material.COOKED_BEEF, 2);
        SELL_PRICES.put(Material.COOKED_CHICKEN, 2);
        SELL_PRICES.put(Material.GOLD_INGOT, 6);
        SELL_PRICES.put(Material.GOLDEN_CARROT, 5);
        SELL_PRICES.put(Material.IRON_INGOT, 5);
        SELL_PRICES.put(Material.IRON_SWORD, 8);
        SELL_PRICES.put(Material.IRON_HELMET, 8);
        SELL_PRICES.put(Material.IRON_CHESTPLATE, 8);
        SELL_PRICES.put(Material.IRON_LEGGINGS, 8);
        SELL_PRICES.put(Material.IRON_BOOTS, 8);
        SELL_PRICES.put(Material.DIAMOND, 15);
        SELL_PRICES.put(Material.GOLDEN_APPLE, 20);
        SELL_PRICES.put(Material.DIAMOND_SWORD, 25);
        SELL_PRICES.put(Material.DIAMOND_HELMET, 25);
        SELL_PRICES.put(Material.DIAMOND_CHESTPLATE, 25);
        SELL_PRICES.put(Material.DIAMOND_LEGGINGS, 25);
        SELL_PRICES.put(Material.NETHERITE_SCRAP, 30);
        SELL_PRICES.put(Material.NETHERITE_INGOT, 50);
        SELL_PRICES.put(Material.NETHERITE_SWORD, 80);
        SELL_PRICES.put(Material.NETHERITE_CHESTPLATE, 80);
        // Added alongside letting armor/weapons drop at every wave tier
        // (including the earliest one) rather than only the higher ones -
        // these pieces can now actually show up as drops, so they need a
        // sell price too.
        SELL_PRICES.put(Material.LEATHER_HELMET, 3);
        SELL_PRICES.put(Material.LEATHER_CHESTPLATE, 3);
        SELL_PRICES.put(Material.LEATHER_LEGGINGS, 3);
        SELL_PRICES.put(Material.LEATHER_BOOTS, 3);
        SELL_PRICES.put(Material.DIAMOND_BOOTS, 25);
        SELL_PRICES.put(Material.NETHERITE_HELMET, 80);
        SELL_PRICES.put(Material.NETHERITE_LEGGINGS, 80);
        SELL_PRICES.put(Material.NETHERITE_BOOTS, 80);
    }

    private final DuelsPlugin plugin;
    private final Map<UUID, GauntletRun> openRunFor = new HashMap<>();
    /** Players currently in "sell one at a time" mode - while active,
     *  clicking an item in their OWN inventory (not the merchant screen)
     *  sells just that stack instead of moving it around, so someone can
     *  cherry-pick what to part with instead of the all-or-nothing bulk
     *  sell. */
    private final java.util.Set<UUID> sellOneMode = new java.util.HashSet<>();

    public GauntletMerchantGUI(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    /** How far along the shop's stock has progressed - same bracket
     *  boundaries as loot drops, so the merchant always sells roughly
     *  what you'd otherwise be finding on the ground at this depth. */
    private int tierForWave(int wave) {
        if (wave < 20) return 0;
        if (wave < 40) return 1;
        if (wave < 60) return 2;
        if (wave < 80) return 3;
        if (wave < 100) return 4;
        return 5;
    }

    public void openMain(Player player, GauntletRun run) {
        openRunFor.put(player.getUniqueId(), run);
        Inventory gui = Bukkit.createInventory(null, 27, MAIN_TITLE);
        fillBorder(gui);

        gui.setItem(4, GuiUtil.namedItem(Material.EMERALD, "&e&lYour Gauntlet Coins: &f" + run.getCoins(player.getUniqueId()),
                "&7Sell drops here for coins,", "&7spend them on gear below."));
        boolean selling = sellOneMode.contains(player.getUniqueId());
        gui.setItem(13, GuiUtil.namedItem(selling ? Material.LIME_DYE : Material.GRAY_DYE,
                selling ? "&a&lSelling Mode: ON" : "&7&lSell One Item",
                selling ? "&7Click items in YOUR inventory" : "&7Click to enable, then click",
                selling ? "&7below to sell just that stack." : "&7items in your inventory to sell them.",
                "", "&e&lClick to " + (selling ? "stop" : "start") + "!"));
        gui.setItem(15, GuiUtil.namedItem(Material.IRON_CHESTPLATE, "&b&lBuy Armor", "", "&e&lClick to browse!"));
        gui.setItem(19, GuiUtil.namedItem(Material.IRON_SWORD, "&b&lBuy Weapons", "", "&e&lClick to browse!"));
        gui.setItem(21, GuiUtil.namedItem(Material.COOKED_BEEF, "&b&lBuy Food", "", "&e&lClick to browse!"));
        gui.setItem(23, GuiUtil.namedItem(Material.POTION, "&b&lBuy Potions", "", "&e&lClick to browse!"));
        boolean ready = run.isReadyForNextWave(player.getUniqueId());
        gui.setItem(22, GuiUtil.namedItem(ready ? Material.LIME_CONCRETE : Material.YELLOW_CONCRETE,
                ready ? "&a&lReady!" : "&e&lReady for Next Wave",
                ready ? "&7Waiting on the rest of the party." : "&7Skip the rest of the break early -",
                ready ? "" : "&7only once EVERYONE'S ready.",
                "", ready ? "" : "&e&lClick when you're done shopping!"));
        gui.setItem(25, GuiUtil.namedItem(Material.ARROW, "&7Close", ""));

        player.openInventory(gui);
    }


    // ---------------------------------------------------------------- buy categories

    private record ShopEntry(Material material, int price, org.bukkit.potion.PotionEffectType potionType,
                              int potionAmplifier, int potionSeconds, String potionLabel,
                              Map<org.bukkit.enchantments.Enchantment, Integer> enchantments) {
        ShopEntry(Material material, int price) {
            this(material, price, null, 0, 0, null, Map.of());
        }

        ShopEntry(Material material, int price, Map<org.bukkit.enchantments.Enchantment, Integer> enchantments) {
            this(material, price, null, 0, 0, null, enchantments);
        }
    }

    private ShopEntry potionEntry(int price, org.bukkit.potion.PotionEffectType type, int amplifier, int seconds, String label) {
        return new ShopEntry(Material.POTION, price, type, amplifier, seconds, label, Map.of());
    }

    private ShopEntry[] armorForTier(int tier) {
        Map<org.bukkit.enchantments.Enchantment, Integer> enchants = armorEnchants(tier);
        return switch (tier) {
            case 0 -> new ShopEntry[]{new ShopEntry(Material.LEATHER_CHESTPLATE, 10, enchants), new ShopEntry(Material.LEATHER_LEGGINGS, 8, enchants)};
            case 1, 2 -> new ShopEntry[]{new ShopEntry(Material.IRON_CHESTPLATE, 25, enchants), new ShopEntry(Material.IRON_LEGGINGS, 22, enchants),
                    new ShopEntry(Material.IRON_HELMET, 18, enchants), new ShopEntry(Material.IRON_BOOTS, 15, enchants)};
            case 3, 4 -> new ShopEntry[]{new ShopEntry(Material.DIAMOND_CHESTPLATE, 60, enchants), new ShopEntry(Material.DIAMOND_LEGGINGS, 55, enchants),
                    new ShopEntry(Material.DIAMOND_HELMET, 45, enchants), new ShopEntry(Material.DIAMOND_BOOTS, 40, enchants)};
            default -> new ShopEntry[]{new ShopEntry(Material.NETHERITE_CHESTPLATE, 140, enchants), new ShopEntry(Material.NETHERITE_LEGGINGS, 130, enchants),
                    new ShopEntry(Material.NETHERITE_HELMET, 110, enchants), new ShopEntry(Material.NETHERITE_BOOTS, 100, enchants)};
        };
    }

    private ShopEntry[] weaponsForTier(int tier) {
        Map<org.bukkit.enchantments.Enchantment, Integer> swordEnchants = swordEnchants(tier);
        Map<org.bukkit.enchantments.Enchantment, Integer> bowEnchants = bowEnchants(tier);
        Map<org.bukkit.enchantments.Enchantment, Integer> crossbowEnchants = crossbowEnchants(tier);
        return switch (tier) {
            case 0 -> new ShopEntry[]{new ShopEntry(Material.STONE_SWORD, 8, swordEnchants), new ShopEntry(Material.BOW, 12, bowEnchants)};
            case 1, 2 -> new ShopEntry[]{new ShopEntry(Material.IRON_SWORD, 22, swordEnchants), new ShopEntry(Material.BOW, 20, bowEnchants)};
            case 3, 4 -> new ShopEntry[]{new ShopEntry(Material.DIAMOND_SWORD, 55, swordEnchants), new ShopEntry(Material.BOW, 35, bowEnchants)};
            default -> new ShopEntry[]{new ShopEntry(Material.NETHERITE_SWORD, 130, swordEnchants), new ShopEntry(Material.CROSSBOW, 90, crossbowEnchants)};
        };
    }

    /** Armor/weapon enchantments scale with tier - the monsters get
     *  genuinely brutal by the later waves, so store-bought gear needs to
     *  actually help survive that instead of being purely cosmetic tiers
     *  of the same vanilla stats. */
    private Map<org.bukkit.enchantments.Enchantment, Integer> armorEnchants(int tier) {
        return switch (tier) {
            case 0 -> Map.of(org.bukkit.enchantments.Enchantment.PROTECTION, 1);
            case 1, 2 -> Map.of(org.bukkit.enchantments.Enchantment.PROTECTION, 2, org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
            case 3, 4 -> Map.of(org.bukkit.enchantments.Enchantment.PROTECTION, 3, org.bukkit.enchantments.Enchantment.UNBREAKING, 2,
                    org.bukkit.enchantments.Enchantment.THORNS, 1);
            default -> Map.of(org.bukkit.enchantments.Enchantment.PROTECTION, 4, org.bukkit.enchantments.Enchantment.UNBREAKING, 3,
                    org.bukkit.enchantments.Enchantment.THORNS, 2);
        };
    }

    private Map<org.bukkit.enchantments.Enchantment, Integer> swordEnchants(int tier) {
        return switch (tier) {
            case 0 -> Map.of(org.bukkit.enchantments.Enchantment.SHARPNESS, 1);
            case 1, 2 -> Map.of(org.bukkit.enchantments.Enchantment.SHARPNESS, 2, org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
            case 3, 4 -> Map.of(org.bukkit.enchantments.Enchantment.SHARPNESS, 3, org.bukkit.enchantments.Enchantment.UNBREAKING, 2,
                    org.bukkit.enchantments.Enchantment.KNOCKBACK, 1);
            default -> Map.of(org.bukkit.enchantments.Enchantment.SHARPNESS, 4, org.bukkit.enchantments.Enchantment.UNBREAKING, 3,
                    org.bukkit.enchantments.Enchantment.FIRE_ASPECT, 1);
        };
    }

    private Map<org.bukkit.enchantments.Enchantment, Integer> bowEnchants(int tier) {
        return switch (tier) {
            case 0 -> Map.of(org.bukkit.enchantments.Enchantment.POWER, 1);
            case 1, 2 -> Map.of(org.bukkit.enchantments.Enchantment.POWER, 2, org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
            case 3, 4 -> Map.of(org.bukkit.enchantments.Enchantment.POWER, 3, org.bukkit.enchantments.Enchantment.UNBREAKING, 2,
                    org.bukkit.enchantments.Enchantment.PUNCH, 1);
            default -> Map.of(org.bukkit.enchantments.Enchantment.POWER, 4, org.bukkit.enchantments.Enchantment.UNBREAKING, 3,
                    org.bukkit.enchantments.Enchantment.FLAME, 1);
        };
    }

    private Map<org.bukkit.enchantments.Enchantment, Integer> crossbowEnchants(int tier) {
        // Crossbow uses a different enchant set than Bow (QUICK_CHARGE /
        // PIERCING, not POWER / FLAME) - only ever reached at the top
        // tier here, but built to scale the same way if that changes.
        return switch (tier) {
            case 0 -> Map.of(org.bukkit.enchantments.Enchantment.QUICK_CHARGE, 1);
            case 1, 2 -> Map.of(org.bukkit.enchantments.Enchantment.QUICK_CHARGE, 2, org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
            case 3, 4 -> Map.of(org.bukkit.enchantments.Enchantment.QUICK_CHARGE, 2, org.bukkit.enchantments.Enchantment.UNBREAKING, 2,
                    org.bukkit.enchantments.Enchantment.PIERCING, 1);
            default -> Map.of(org.bukkit.enchantments.Enchantment.QUICK_CHARGE, 3, org.bukkit.enchantments.Enchantment.UNBREAKING, 3,
                    org.bukkit.enchantments.Enchantment.PIERCING, 2);
        };
    }

    private ShopEntry[] foodForTier(int tier) {
        return switch (tier) {
            case 0, 1 -> new ShopEntry[]{new ShopEntry(Material.BREAD, 2), new ShopEntry(Material.COOKED_PORKCHOP, 3)};
            case 2, 3 -> new ShopEntry[]{new ShopEntry(Material.COOKED_BEEF, 4), new ShopEntry(Material.GOLDEN_CARROT, 8)};
            default -> new ShopEntry[]{new ShopEntry(Material.GOLDEN_CARROT, 10), new ShopEntry(Material.GOLDEN_APPLE, 25)};
        };
    }

    private ShopEntry[] potionsForTier(int tier) {
        return switch (tier) {
            case 0, 1 -> new ShopEntry[]{
                    potionEntry(15, PotionEffectType.INSTANT_HEALTH, 0, 0, "Healing")};
            case 2, 3 -> new ShopEntry[]{
                    potionEntry(20, PotionEffectType.INSTANT_HEALTH, 1, 0, "Healing II"),
                    potionEntry(25, PotionEffectType.STRENGTH, 0, 180, "Strength")};
            default -> new ShopEntry[]{
                    potionEntry(30, PotionEffectType.INSTANT_HEALTH, 1, 0, "Healing II"),
                    potionEntry(35, PotionEffectType.STRENGTH, 1, 180, "Strength II"),
                    potionEntry(35, PotionEffectType.REGENERATION, 1, 240, "Regeneration II")};
        };
    }

    private void openCategory(Player player, GauntletRun run, String title, ShopEntry[] entries) {
        Inventory gui = Bukkit.createInventory(null, 27, title);
        fillBorder(gui);
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < entries.length && i < slots.length; i++) {
            ShopEntry entry = entries[i];
            ItemStack display = buildDisplayItem(entry);
            gui.setItem(slots[i], display);
        }
        gui.setItem(22, GuiUtil.namedItem(Material.ARROW, "&7Back", ""));
        player.openInventory(gui);
    }

    private ItemStack buildDisplayItem(ShopEntry entry) {
        ItemStack item = new ItemStack(entry.material());
        ItemMeta meta = item.getItemMeta();
        String name = entry.potionLabel() != null ? entry.potionLabel() + " Potion" : niceName(entry.material());
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&f" + name));
        meta.setLore(List.of(ChatColor.translateAlternateColorCodes('&', "&7Price: &e" + entry.price() + " Gauntlet Coins"),
                ChatColor.translateAlternateColorCodes('&', "&e&lClick to buy!")));
        item.setItemMeta(meta);
        if (entry.potionType() != null && item.getItemMeta() instanceof PotionMeta potionMeta) {
            potionMeta.setDisplayName(meta.getDisplayName());
            potionMeta.setLore(meta.getLore());
            potionMeta.addCustomEffect(new PotionEffect(entry.potionType(), Math.max(1, entry.potionSeconds() * 20),
                    entry.potionAmplifier()), true);
            item.setItemMeta(potionMeta);
        }
        if (!entry.enchantments().isEmpty()) {
            for (var enchant : entry.enchantments().entrySet()) {
                item.addUnsafeEnchantment(enchant.getKey(), enchant.getValue());
            }
        }
        return item;
    }

    private String niceName(Material material) {
        String raw = material.name().toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String word : raw.split(" ")) {
            if (!word.isEmpty()) sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    /** A quick on-screen readout of the player's current Gauntlet Coins
     *  balance, shown right after it changes (buying or selling) - so the
     *  new total is visible immediately without needing to reopen the
     *  merchant menu to see it. */
    private void showCoinsPopup(Player player, GauntletRun run) {
        player.sendActionBar(ChatColor.GOLD + "" + ChatColor.BOLD + "Gauntlet Coins: " + ChatColor.YELLOW
                + run.getCoins(player.getUniqueId()));
    }

    private void purchase(Player player, GauntletRun run, ShopEntry entry) {
        int balance = run.getCoins(player.getUniqueId());
        if (balance < entry.price()) {
            player.sendMessage(ChatColor.RED + "You need " + entry.price() + " coins - you have " + balance + ".");
            return;
        }
        run.addCoins(player.getUniqueId(), -entry.price());
        ItemStack item = buildDisplayItem(entry);
        var leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            for (ItemStack extra : leftover.values()) player.getWorld().dropItem(player.getLocation(), extra);
        }
        player.sendMessage(ChatColor.GREEN + "Bought for " + entry.price() + " Gauntlet Coins!");
        showCoinsPopup(player, run);
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        boolean ours = title.equals(MAIN_TITLE) || title.equals(ARMOR_TITLE) || title.equals(WEAPON_TITLE)
                || title.equals(FOOD_TITLE) || title.equals(POTION_TITLE);
        if (!ours) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        int slot = event.getRawSlot();

        GauntletRun run = openRunFor.get(player.getUniqueId());
        if (run == null) { player.closeInventory(); return; }

        // Sell-one-at-a-time mode: a click on the player's OWN inventory
        // (not the merchant screen above it) sells just that one stack,
        // rather than moving it around like a normal inventory click.
        if (sellOneMode.contains(player.getUniqueId()) && event.getClickedInventory() == player.getInventory()) {
            Integer unitPrice = SELL_PRICES.get(clicked.getType());
            if (unitPrice == null) {
                player.sendMessage(ChatColor.GRAY + "That item can't be sold here.");
                return;
            }
            int total = unitPrice * clicked.getAmount();
            event.getClickedInventory().setItem(event.getSlot(), null);
            run.addCoins(player.getUniqueId(), total);
            player.sendMessage(ChatColor.GREEN + "Sold for " + total + " Gauntlet Coins!");
            showCoinsPopup(player, run);
            if (title.equals(MAIN_TITLE)) openMain(player, run);
            return;
        }

        int tier = tierForWave(run.getWave());

        if (title.equals(MAIN_TITLE)) {
            if (slot == 13) { toggleSellOneMode(player); openMain(player, run); }
            else if (slot == 15) openCategory(player, run, ARMOR_TITLE, armorForTier(tier));
            else if (slot == 19) openCategory(player, run, WEAPON_TITLE, weaponsForTier(tier));
            else if (slot == 21) openCategory(player, run, FOOD_TITLE, foodForTier(tier));
            else if (slot == 23) openCategory(player, run, POTION_TITLE, potionsForTier(tier));
            else if (slot == 22) { plugin.getGauntletManager().markReadyForNextWave(player, run); openMain(player, run); }
            else if (slot == 25) player.closeInventory();
            return;
        }

        if (slot == 22) { openMain(player, run); return; }

        ShopEntry[] entries;
        if (title.equals(ARMOR_TITLE)) entries = armorForTier(tier);
        else if (title.equals(WEAPON_TITLE)) entries = weaponsForTier(tier);
        else if (title.equals(FOOD_TITLE)) entries = foodForTier(tier);
        else entries = potionsForTier(tier);
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot && i < entries.length) {
                purchase(player, run, entries[i]);
                openCategory(player, run, title, entries);
                return;
            }
        }
    }

    private void toggleSellOneMode(Player player) {
        UUID id = player.getUniqueId();
        if (sellOneMode.contains(id)) {
            sellOneMode.remove(id);
            player.sendMessage(ChatColor.GRAY + "Sell-one mode off.");
        } else {
            sellOneMode.add(id);
            player.sendMessage(ChatColor.GREEN + "Sell-one mode on - click items in your inventory to sell them one at a time.");
        }
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        // Deferred a tick: opening one of our own screens to replace
        // another (e.g. refreshing the coin balance after a sale) also
        // fires a close event for the one being replaced, so checking
        // immediately would wrongly clear sell-one mode on every single
        // sale. Waiting a tick lets us see whether they're still looking
        // at one of our screens before deciding they actually left.
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player stillOnline = Bukkit.getPlayer(id);
            if (stillOnline == null) { sellOneMode.remove(id); return; }
            String currentTitle = stillOnline.getOpenInventory().getTitle();
            boolean stillInMerchant = currentTitle.equals(MAIN_TITLE) || currentTitle.equals(ARMOR_TITLE)
                    || currentTitle.equals(WEAPON_TITLE) || currentTitle.equals(FOOD_TITLE) || currentTitle.equals(POTION_TITLE);
            if (!stillInMerchant) sellOneMode.remove(id);
        });
    }

    /** Dragging (splitting a held stack across multiple slots) fires a
     *  completely separate event from clicking - cancelling clicks alone,
     *  which is all this GUI did before, doesn't stop a player from
     *  dragging display items out of the shop or rearranging them. A
     *  real, confirmed gap. */
    @EventHandler
    public void onDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (title.equals(MAIN_TITLE) || title.equals(ARMOR_TITLE) || title.equals(WEAPON_TITLE)
                || title.equals(FOOD_TITLE) || title.equals(POTION_TITLE)) {
            event.setCancelled(true);
        }
    }

    private void fillBorder(Inventory gui) {
        ItemStack border = GuiUtil.coloredPane(Material.YELLOW_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < gui.getSize(); i++) gui.setItem(i, border);
    }
}
