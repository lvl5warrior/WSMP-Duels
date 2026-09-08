package com.warriorssmp.duels;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.List;

/**
 * Blocks setting a home or a team portal while standing in either the
 * Duel or Gauntlet map's world - both are always a whole, dedicated
 * world in this plugin's design, never shared with anything else, so
 * "inside the map" simply means "in that world at all". Works against
 * any plugin's command (Essentials' /sethome, WSMP-Teams' /team portal
 * set, or anything else) by matching the command's words against a
 * configurable list of prefixes, rather than depending on a specific
 * plugin's API - this also means a MULTI-WORD prefix like "team portal
 * set" blocks only that specific subcommand, not every "/team ..."
 * command.
 */
public class HomeProtectionListener implements Listener {

    private final DuelsPlugin plugin;
    private final ArenaManager arenaManager;

    public HomeProtectionListener(DuelsPlugin plugin, ArenaManager arenaManager) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("map-protection.enabled", true)) return;

        Player player = event.getPlayer();
        if (!inProtectedWorld(player)) return;

        String[] typedWords = event.getMessage().trim().split("\\s+");

        List<String> blockedPrefixes = plugin.getConfig().getStringList("map-protection.blocked-commands");
        for (String prefix : blockedPrefixes) {
            if (prefix == null || prefix.isBlank()) continue;
            if (matchesPrefix(typedWords, prefix.trim())) {
                event.setCancelled(true);
                String message = plugin.getConfig().getString("map-protection.message",
                        "&cYou can't do that here.");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                return;
            }
        }
    }

    /** Whether what the player actually typed starts with the given
     *  prefix, word by word (case-insensitive), so a single-word prefix
     *  like "/sethome" matches "/sethome base", and a multi-word prefix
     *  like "/team portal set" matches "/team portal set 1" without also
     *  matching a completely different "/team" subcommand like
     *  "/team portalsomethingelse" (leading slash on the prefix's first
     *  word is optional either way, since players may or may not have
     *  typed one in config). */
    private boolean matchesPrefix(String[] typedWords, String prefix) {
        String[] prefixWords = prefix.split("\\s+");
        if (prefixWords.length == 0 || typedWords.length < prefixWords.length) return false;

        for (int i = 0; i < prefixWords.length; i++) {
            String typed = typedWords[i];
            String expected = prefixWords[i];
            if (i == 0) {
                typed = typed.startsWith("/") ? typed.substring(1) : typed;
                expected = expected.startsWith("/") ? expected.substring(1) : expected;
            }
            if (!typed.equalsIgnoreCase(expected)) return false;
        }
        return true;
    }

    private boolean inProtectedWorld(Player player) {
        String worldName = player.getWorld().getName();
        return worldName.equals(arenaManager.getDuelArena().getWorldName())
                || worldName.equals(arenaManager.getGauntletArena().getWorldName());
    }
}
