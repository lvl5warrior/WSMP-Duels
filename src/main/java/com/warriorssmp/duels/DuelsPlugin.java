package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class DuelsPlugin extends JavaPlugin {

    private ArenaManager arenaManager;
    private KitManager kitManager;
    private PlayerDataManager playerDataManager;
    private DuelManager duelManager;
    private DuelGUI duelGUI;
    private AdminGUI adminGUI;
    private SpawnWandListener spawnWandListener;
    private VaultEconomy vaultEconomy;
    private BettingManager bettingManager;
    private SpectatorManager spectatorManager;
    private BettingGUI bettingGUI;
    private StoreManager storeManager;
    private StoreGUI storeGUI;
    private GauntletManager gauntletManager;
    private GauntletGUI gauntletGUI;
    private GauntletMerchantGUI gauntletMerchantGUI;
    private GauntletPartyManager gauntletPartyManager;
    private QueueGUI queueGUI;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        arenaManager = new ArenaManager(this);
        kitManager = new KitManager(this);
        playerDataManager = new PlayerDataManager(this);
        duelManager = new DuelManager(this, arenaManager, playerDataManager);
        duelGUI = new DuelGUI(this, duelManager, kitManager, playerDataManager);
        storeManager = new StoreManager(this, playerDataManager);
        storeGUI = new StoreGUI(this, storeManager, playerDataManager);
        spawnWandListener = new SpawnWandListener(this, arenaManager);
        adminGUI = new AdminGUI(this, kitManager, arenaManager, storeManager, spawnWandListener);

        gauntletPartyManager = new GauntletPartyManager(this);
        gauntletManager = new GauntletManager(this, arenaManager, playerDataManager, duelManager, gauntletPartyManager);
        gauntletGUI = new GauntletGUI(gauntletManager);
        gauntletMerchantGUI = new GauntletMerchantGUI(this);
        duelGUI.setGauntletManager(gauntletManager);
        duelGUI.setGauntletPartyManager(gauntletPartyManager);

        vaultEconomy = new VaultEconomy(this);
        bettingManager = new BettingManager(this, duelManager, gauntletManager, playerDataManager, vaultEconomy);
        spectatorManager = new SpectatorManager(this, duelManager, gauntletManager);
        gauntletManager.setSpectatorManager(spectatorManager);
        bettingGUI = new BettingGUI(this, duelManager, gauntletManager, bettingManager, spectatorManager);
        duelManager.setBettingManager(bettingManager);
        duelManager.setSpectatorManager(spectatorManager);
        duelGUI.setBettingGUI(bettingGUI);
        duelGUI.setStoreGUI(storeGUI);

        queueGUI = new QueueGUI(this, duelManager, gauntletManager);
        duelGUI.setQueueGUI(queueGUI);

        getServer().getPluginManager().registerEvents(duelGUI, this);
        getServer().getPluginManager().registerEvents(adminGUI, this);
        getServer().getPluginManager().registerEvents(spawnWandListener, this);
        getServer().getPluginManager().registerEvents(bettingGUI, this);
        getServer().getPluginManager().registerEvents(storeGUI, this);
        getServer().getPluginManager().registerEvents(gauntletManager, this);
        getServer().getPluginManager().registerEvents(gauntletGUI, this);
        getServer().getPluginManager().registerEvents(gauntletMerchantGUI, this);
        getServer().getPluginManager().registerEvents(queueGUI, this);
        getServer().getPluginManager().registerEvents(new DuelListener(this, duelManager), this);
        getServer().getPluginManager().registerEvents(new DuelsChatListener(this, adminGUI, bettingManager), this);
        getServer().getPluginManager().registerEvents(new HomeProtectionListener(this, arenaManager), this);

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
            if (args.length >= 1 && args[0].equalsIgnoreCase("party")) {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /duels party <invite|accept|decline|leave>");
                    return true;
                }
                if (args[1].equalsIgnoreCase("accept")) { gauntletPartyManager.acceptInvite(player); return true; }
                if (args[1].equalsIgnoreCase("decline")) { gauntletPartyManager.declineInvite(player); return true; }
                if (args[1].equalsIgnoreCase("leave")) { gauntletPartyManager.leaveParty(player); return true; }
                if (args[1].equalsIgnoreCase("invite")) {
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.RED + "Usage: /duels party invite <player>");
                        return true;
                    }
                    Player target = Bukkit.getPlayer(args[2]);
                    if (target == null) {
                        player.sendMessage(ChatColor.RED + "That player isn't online.");
                        return true;
                    }
                    gauntletPartyManager.invite(player, target);
                    return true;
                }
                player.sendMessage(ChatColor.RED + "Usage: /duels party <invite|accept|decline|leave>");
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("queue")) {
                queueGUI.openMain(player);
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

        getLogger().info("WSMP-Duels enabled (1v1 duels + The Gauntlet, each on a single static map with a queue).");
    }

    @Override
    public void onDisable() {
        // Restore every actively-mid-match player back to their real
        // inventory FIRST, before anything else - the server saves each
        // player's data as part of shutting down regardless of what this
        // plugin does, so this is the only remaining chance to make sure
        // that save captures their real items rather than whatever kit
        // items they were holding at the moment the server stopped.
        if (duelManager != null) duelManager.restoreAllOnShutdown();
        if (gauntletManager != null) gauntletManager.restoreAllOnShutdown();
        // Wagered currency is deducted up front and held unresolved until
        // a match ends - refund every pending bet too, or that money
        // would simply be gone for any match still active at shutdown.
        if (bettingManager != null) bettingManager.refundAllOnShutdown();

        if (playerDataManager != null) playerDataManager.save();
        if (kitManager != null) kitManager.save();
        if (arenaManager != null) arenaManager.save();
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

    public GauntletPartyManager getGauntletPartyManager() {
        return gauntletPartyManager;
    }

    public AdminGUI getAdminGUI() {
        return adminGUI;
    }

    public QueueGUI getQueueGUI() {
        return queueGUI;
    }

    public SpawnWandListener getSpawnWandListener() {
        return spawnWandListener;
    }
}
