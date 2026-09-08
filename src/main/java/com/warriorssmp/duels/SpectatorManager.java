package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lets any online player fly around near an active duel or Gauntlet run in
 * spectator gamemode - not locked to a fixed camera or teleport-follow,
 * just dropped in near the fight and free to fly wherever from there.
 * Reuses PlayerStateSnapshot, the same mechanism duels/Gauntlet runs
 * already use to capture and restore a player's full state, so a
 * spectator's own inventory, gamemode, and location come back exactly as
 * they were the moment they stop spectating.
 */
public class SpectatorManager {

    private final DuelManager duelManager;
    private final GauntletManager gauntletManager;
    private final DuelsPlugin plugin;

    private final Map<UUID, PlayerStateSnapshot> snapshots = new HashMap<>();
    private final Map<UUID, MatchRef> spectatingMatch = new HashMap<>();

    public SpectatorManager(DuelsPlugin plugin, DuelManager duelManager, GauntletManager gauntletManager) {
        this.plugin = plugin;
        this.duelManager = duelManager;
        this.gauntletManager = gauntletManager;
        startEnforcementTask();
    }

    /** Belt-and-suspenders safety net: every couple of seconds, re-applies
     *  spectator mode to anyone this manager is tracking as a spectator
     *  whose actual gamemode has drifted away from it for any reason -
     *  a real, confirmed bug had this happen (getting pulled into a new
     *  match while still spectating another one would force Survival
     *  mode on top of spectating, leaving the player visible and
     *  damageable mid-arena). This doesn't replace fixing the actual
     *  cause where one's known, but guarantees a spectator can never be
     *  left standing in harm's way even if some other, undiscovered path
     *  resets their gamemode. */
    private void startEnforcementTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID id : new java.util.ArrayList<>(snapshots.keySet())) {
                Player player = Bukkit.getPlayer(id);
                if (player == null) continue;
                if (player.getGameMode() != GameMode.SPECTATOR) {
                    player.setGameMode(GameMode.SPECTATOR);
                }
                // A standing on-screen reminder of how to stop spectating,
                // since there's otherwise no visible hint once the GUI
                // that started it is closed - re-sent on this same cycle
                // rather than only once, since an action bar message fades
                // out on its own after a few seconds.
                player.sendActionBar(org.bukkit.ChatColor.GRAY + "Type " + org.bukkit.ChatColor.WHITE + "/duels leave"
                        + org.bukkit.ChatColor.GRAY + " to stop spectating.");
            }
        }, 40L, 40L);
    }

    public boolean isSpectating(UUID playerId) {
        return snapshots.containsKey(playerId);
    }

    public MatchRef getSpectatingMatch(UUID playerId) {
        return spectatingMatch.get(playerId);
    }

    public void startSpectating(Player spectator, MatchKind kind, UUID matchId) {
        if (duelManager.hasActiveMatch(spectator.getUniqueId())
                || gauntletManager.hasActiveRun(spectator.getUniqueId())) {
            spectator.sendMessage(err("You can't spectate while you're in a match yourself."));
            return;
        }
        if (isSpectating(spectator.getUniqueId())) {
            spectator.sendMessage(err("You're already spectating a match - stop that one first."));
            return;
        }

        Location dropInAt = resolveDropInLocation(kind, matchId);
        if (dropInAt == null) {
            spectator.sendMessage(err("That match isn't active anymore."));
            return;
        }

        snapshots.put(spectator.getUniqueId(), PlayerStateSnapshot.capture(spectator));
        spectatingMatch.put(spectator.getUniqueId(), new MatchRef(kind, matchId));

        spectator.teleport(dropInAt);
        spectator.setGameMode(GameMode.SPECTATOR);
        spectator.sendMessage(ok("Spectating - fly around freely. Type /duels leave to stop, or use the menu again."));
    }

    public void stopSpectating(Player spectator) {
        PlayerStateSnapshot snapshot = snapshots.remove(spectator.getUniqueId());
        spectatingMatch.remove(spectator.getUniqueId());
        if (snapshot == null) {
            spectator.sendMessage(err("You're not spectating anything."));
            return;
        }
        snapshot.restore(spectator);
        spectator.sendMessage(ok("Stopped spectating."));
    }

    /** Called when the match a spectator is watching ends, so they aren't
     *  left floating in spectator mode over an arena with nothing left
     *  going on. Restores anyone currently watching that specific match;
     *  no-op for everyone else. */
    public void handleMatchEnded(MatchKind kind, UUID matchId) {
        MatchRef ref = new MatchRef(kind, matchId);
        for (UUID spectatorId : new java.util.ArrayList<>(spectatingMatch.keySet())) {
            if (!ref.equals(spectatingMatch.get(spectatorId))) continue;
            var player = org.bukkit.Bukkit.getPlayer(spectatorId);
            PlayerStateSnapshot snapshot = snapshots.remove(spectatorId);
            spectatingMatch.remove(spectatorId);
            if (player != null && snapshot != null) {
                snapshot.restore(player);
                player.sendMessage(ok("The match you were spectating ended."));
            }
        }
    }

    /** Handles a spectator disconnecting mid-spectate - just drops the
     *  tracking, since there's no player to restore state onto anymore
     *  (their real inventory/location were never actually touched by
     *  Bukkit on logout, only by our own snapshot bookkeeping). */
    public void handleQuit(UUID playerId) {
        snapshots.remove(playerId);
        spectatingMatch.remove(playerId);
    }

    private Location resolveDropInLocation(MatchKind kind, UUID matchId) {
        if (kind == MatchKind.DUEL) {
            DuelMatch match = duelManager.getActiveMatchById(matchId);
            if (match == null) return null;
            var spawns = match.getArena().getSpawnPoints();
            return spawns.isEmpty() ? null : spawns.get(0);
        }
        GauntletRun run = gauntletManager.getRunById(matchId);
        if (run == null) return null;
        var spawns = run.getArena().getSpawnPoints();
        return spawns.isEmpty() ? null : spawns.get(0);
    }

    private String ok(String msg) {
        return org.bukkit.ChatColor.GREEN + msg;
    }

    private String err(String msg) {
        return org.bukkit.ChatColor.RED + msg;
    }
}
