package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Lets betting accept Vault money as a currency, if Vault AND a real
 * economy plugin are both installed. Uses reflection rather than a
 * compile-time dependency on Vault's API - same reasoning as
 * DiscordAnnouncer: this plugin builds and runs completely fine with or
 * without Vault present, and if Vault's exact method signatures ever
 * differ from what's expected here, this fails quietly (logged once) and
 * betting simply falls back to Honor-only rather than breaking anything.
 */
public class VaultEconomy {

    private final DuelsPlugin plugin;
    private boolean resolved = false;
    private boolean available = false;
    private Object economy; // net.milkbowl.vault.economy.Economy instance
    private boolean warnedOnce = false;

    public VaultEconomy(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        ensureResolved();
        return available;
    }

    private void ensureResolved() {
        if (resolved) return;
        resolved = true;
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(economyClass);
            if (registration == null) return;
            Object provider = registration.getProvider();
            if (provider == null) return;
            this.economy = provider;
            this.available = true;
        } catch (Exception e) {
            logWarningOnce(e);
        }
    }

    /** Current Vault balance, or 0.0 if Vault isn't available or the call
     *  fails for any reason - callers should check isAvailable() before
     *  relying on this for anything that affects gameplay. */
    public double getBalance(OfflinePlayer player) {
        if (!isAvailable()) return 0.0;
        try {
            Method method = economy.getClass().getMethod("getBalance", OfflinePlayer.class);
            return (double) method.invoke(economy, player);
        } catch (Exception e) {
            logWarningOnce(e);
            return 0.0;
        }
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (!isAvailable()) return false;
        try {
            Method method = economy.getClass().getMethod("has", OfflinePlayer.class, double.class);
            return (boolean) method.invoke(economy, player, amount);
        } catch (Exception e) {
            logWarningOnce(e);
            return false;
        }
    }

    /** Withdraws from the player's Vault balance. Returns true only if
     *  Vault itself confirms the transaction succeeded - callers must
     *  check this before treating money as actually taken. */
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (!isAvailable()) return false;
        try {
            Method withdrawMethod = economy.getClass().getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            Object response = withdrawMethod.invoke(economy, player, amount);
            Method successMethod = response.getClass().getMethod("transactionSuccess");
            return (boolean) successMethod.invoke(response);
        } catch (Exception e) {
            logWarningOnce(e);
            return false;
        }
    }

    /** Deposits into the player's Vault balance. Returns true only if
     *  Vault itself confirms the transaction succeeded. */
    public boolean deposit(OfflinePlayer player, double amount) {
        if (!isAvailable()) return false;
        try {
            Method depositMethod = economy.getClass().getMethod("depositPlayer", OfflinePlayer.class, double.class);
            Object response = depositMethod.invoke(economy, player, amount);
            Method successMethod = response.getClass().getMethod("transactionSuccess");
            return (boolean) successMethod.invoke(response);
        } catch (Exception e) {
            logWarningOnce(e);
            return false;
        }
    }

    private void logWarningOnce(Exception e) {
        if (warnedOnce) return;
        warnedOnce = true;
        plugin.getLogger().log(Level.WARNING,
                "Couldn't reach Vault's economy service - this won't be logged again this session. "
                        + "Betting will fall back to Honor points only.", e);
    }
}
