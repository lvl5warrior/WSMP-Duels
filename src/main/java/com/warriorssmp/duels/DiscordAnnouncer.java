package com.warriorssmp.duels;

import org.bukkit.Bukkit;

import java.util.logging.Level;

/**
 * Sends duel result announcements to Discord via DiscordSRV, if it's
 * installed. Uses reflection rather than a compile-time dependency on
 * DiscordSRV's API - this plugin builds and runs completely fine with or
 * without DiscordSRV present, and if DiscordSRV's exact method signatures
 * ever differ from what's expected here, this fails quietly (logged once)
 * rather than breaking anything else in the plugin.
 */
public class DiscordAnnouncer {

    private final DuelsPlugin plugin;
    private boolean warnedOnce = false;

    public DiscordAnnouncer(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    public void announce(String message) {
        if (Bukkit.getPluginManager().getPlugin("DiscordSRV") == null) return;

        try {
            Class<?> discordSRVClass = Class.forName("github.scarsz.discordsrv.DiscordSRV");
            Object discordSRVInstance = discordSRVClass.getMethod("getPlugin").invoke(null);
            Object textChannel = discordSRVClass.getMethod("getMainTextChannel").invoke(discordSRVInstance);
            if (textChannel == null) return;

            Class<?> channelClass = textChannel.getClass();
            Object action = channelClass.getMethod("sendMessage", CharSequence.class).invoke(textChannel, message);
            action.getClass().getMethod("queue").invoke(action);
        } catch (Exception e) {
            if (!warnedOnce) {
                warnedOnce = true;
                plugin.getLogger().log(Level.WARNING,
                        "Couldn't send a message to Discord via DiscordSRV - this won't be logged again this session. "
                                + "Duel results will still announce in-game normally.", e);
            }
        }
    }
}
