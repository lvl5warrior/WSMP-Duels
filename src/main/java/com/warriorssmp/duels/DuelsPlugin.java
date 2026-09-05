package com.warriorssmp.duels;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class DuelsPlugin extends JavaPlugin {

    private ArenaManager arenaManager;
    private WorldInstanceManager worldInstanceManager;
    private KitManager kitManager;
    private PlayerDataManager playerDataManager;
    private DuelManager duelManager;
    private DuelGUI duelGUI;
    private AdminGUI adminGUI;
    private WarPartyManager warPartyManager;
    private WarManager warManager;
    private WarGUI warGUI;
    private BotManager botManager;
    private VaultEconomy vaultEconomy;
    private BettingManager bettingManager;
    private SpectatorManager spectatorManager;
    private BettingGUI bettingGUI;
    private StoreManager storeManager;
    private StoreGUI storeGUI;
    private GauntletManager gauntletManager;
    private GauntletGUI gauntletGUI;
    private GauntletMerchantGUI gauntletMerchantGUI;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        arenaManager = new ArenaManager(this);
        kitManager = new KitManager(this);
        playerDataManager = new PlayerDataManager(this);
        botManager = new BotManager(this);
        worldInstanceManager = new WorldInstanceManager(this);
        worldInstanceManager.cleanupOrphanedInstances();
        duelManager = new DuelManager(this, arenaManager, playerDataManager, botManager, worldInstanceManager);
        duelGUI = new DuelGUI(this, duelManager, kitManager, playerDataManager);
        storeManager = new StoreManager(this, playerDataManager);
        storeGUI = new StoreGUI(this, storeManager, playerDataManager);
        adminGUI = new AdminGUI(this, kitManager, arenaManager, storeManager);
        warPartyManager = new WarPartyManager();
        warManager = new WarManager(this, arenaManager, playerDataManager, warPartyManager, worldInstanceManager);
        warGUI = new WarGUI(this, warManager, warPartyManager);
        duelGUI.setWarManager(warManager); // resolves the circular dependency - see DuelGUI's field comment
        warManager.setCtfManager(new CtfManager(this, warManager)); // same pattern - CtfManager needs WarManager back
        gauntletManager = new GauntletManager(this, arenaManager, playerDataManager, duelManager, warManager, warPartyManager, worldInstanceManager);
        warManager.setGauntletManager(gauntletManager);
        gauntletGUI = new GauntletGUI(gauntletManager);
        gauntletMerchantGUI = new GauntletMerchantGUI(this);
        duelGUI.setGauntletManager(gauntletManager);

        vaultEconomy = new VaultEconomy(this);
        bettingManager = new BettingManager(this, duelManager, warManager, gauntletManager, playerDataManager, vaultEconomy);
        spectatorManager = new SpectatorManager(this, duelManager, warManager, gauntletManager);
        gauntletManager.setSpectatorManager(spectatorManager);
        bettingGUI = new BettingGUI(this, duelManager, warManager, gauntletManager, bettingManager, spectatorManager);
        duelManager.setBettingManager(bettingManager);
        duelManager.setSpectatorManager(spectatorManager);
        warManager.setBettingManager(bettingManager);
        warManager.setSpectatorManager(spectatorManager);
        duelGUI.setBettingGUI(bettingGUI);
        duelGUI.setStoreGUI(storeGUI);

        getServer().getPluginManager().registerEvents(duelGUI, this);
        getServer().getPluginManager().registerEvents(adminGUI, this);
        getServer().getPluginManager().registerEvents(warGUI, this);
        getServer().getPluginManager().registerEvents(bettingGUI, this);
        getServer().getPluginManager().registerEvents(storeGUI, this);
        getServer().getPluginManager().registerEvents(gauntletManager, this);
        getServer().getPluginManager().registerEvents(gauntletGUI, this);
        getServer().getPluginManager().registerEvents(gauntletMerchantGUI, this);
        getServer().getPluginManager().registerEvents(new DuelListener(this, duelManager), this);
        getServer().getPluginManager().registerEvents(new WarListener(this, warManager), this);
        getServer().getPluginManager().registerEvents(new DuelsChatListener(this, adminGUI, bettingManager), this);

        getCommand("duels").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this command.");
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("accept")) {
                duelManager.acceptChallenge(player);
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("decline")) {
                duelManager.declineChallenge(player);
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("end")) {
                duelManager.endMatchVoluntarily(player);
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("ready")) {
                gauntletManager.readyUpViaCommand(player);
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("shop")) {
                gauntletManager.openShopViaCommand(player);
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("leave")) {
                if (spectatorManager.isSpectating(player.getUniqueId())) {
                    spectatorManager.stopSpectating(player);
                } else {
                    player.sendMessage(ChatColor.RED + "You're not spectating anything.");
                }
                return true;
            }
            duelGUI.openMain(player);
            return true;
        });

        getCommand("duelsadmin").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this command.");
                return true;
            }
            if (!player.hasPermission("wsmpduels.admin")) {
                player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }
            adminGUI.openMain(player);
            return true;
        });

        getCommand("wars").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this command.");
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("accept")) {
                warPartyManager.acceptInvite(player);
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("decline")) {
                warPartyManager.declineInvite(player);
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("leave")) {
                warPartyManager.leaveParty(player);
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("end")) {
                warManager.endMatchVoluntarily(player);
                return true;
            }
            warGUI.openMain(player);
            return true;
        });

        getLogger().info("WSMP-Duels enabled (Phase 1: 1v1 duels. Phase 2a: team wars, parties, FFA.)");
    }

    @Override
    public void onDisable() {
        // Restore every actively-mid-match player back to their real
        // inventory FIRST, before anything else - the server saves each
        // player's data as part of shutting down regardless of what this
        // plugin does, so this is the only remaining chance to make sure
        // that save captures their real items rather than whatever kit
        // items they were holding at the moment the server stopped. Also
        // has to happen before the Gauntlet world cleanup below, since a
        // world can only unload once every player is actually out of it.
        if (duelManager != null) duelManager.restoreAllOnShutdown();
        if (warManager != null) warManager.restoreAllOnShutdown();
        if (gauntletManager != null) gauntletManager.restoreAllOnShutdown();
        // Wagered currency is deducted up front and held unresolved until
        // a match ends - refund every pending bet too, or that money
        // would simply be gone for any match still active at shutdown.
        if (bettingManager != null) bettingManager.refundAllOnShutdown();

        if (playerDataManager != null) playerDataManager.save();
        if (kitManager != null) kitManager.save();
        if (arenaManager != null) arenaManager.save();
        if (gauntletManager != null) gauntletManager.shutdownAllInstances();
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public KitManager getKitManager() {
        return kitManager;
    }

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public DuelManager getDuelManager() {
        return duelManager;
    }

    public DuelGUI getDuelGUI() {
        return duelGUI;
    }

    public GauntletManager getGauntletManager() {
        return gauntletManager;
    }

    public GauntletGUI getGauntletGUI() {
        return gauntletGUI;
    }

    public GauntletMerchantGUI getGauntletMerchantGUI() {
        return gauntletMerchantGUI;
    }

    public AdminGUI getAdminGUI() {
        return adminGUI;
    }

    public WarPartyManager getWarPartyManager() {
        return warPartyManager;
    }

    public WarManager getWarManager() {
        return warManager;
    }

    public WarGUI getWarGUI() {
        return warGUI;
    }

    public BotManager getBotManager() {
        return botManager;
    }
}
