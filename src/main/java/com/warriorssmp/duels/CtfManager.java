package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Runs Capture the Flag gameplay for active WarMatches whose mode is
 * CAPTURE_THE_FLAG. A team's flag is represented visually by an armor
 * stand wearing a colored banner - sitting at the team's configured base
 * location, or wherever it was dropped after a carrier died. Pickup and
 * capture are both simple proximity checks run on a short repeating
 * timer rather than movement events, since that's cheap enough at these
 * player counts and avoids firing on every single step players take.
 */
public class CtfManager {

    private static final double PICKUP_RADIUS = 1.5;
    private static final double CAPTURE_RADIUS = 2.0;
    private static final int CAPTURES_TO_WIN = 3;

    private final DuelsPlugin plugin;
    private final WarManager warManager;

    public CtfManager(DuelsPlugin plugin, WarManager warManager) {
        this.plugin = plugin;
        this.warManager = warManager;
        startTask();
    }

    public static int getCapturesToWin() {
        return CAPTURES_TO_WIN;
    }

    /** Sets up a fresh CtfState for a match that's just starting, spawning
     *  a visual flag marker at each team's configured base. Call this
     *  right after the match's WarMatch is created and before players are
     *  actually released to play. */
    public void initMatch(WarMatch match) {
        CtfState state = new CtfState(match.getTeams().size());
        match.setCtfState(state);
        for (int i = 0; i < match.getTeams().size(); i++) {
            spawnMarker(match, i, match.getArena().getFlagLocation(i));
        }
    }

    /** Cleans up any lingering flag marker armor stands for a match that's
     *  ending - whether it ended by capture win, elimination, or someone
     *  quitting. Safe to call on a non-CTF match (no-op). */
    public void cleanupMatch(WarMatch match) {
        CtfState state = match.getCtfState();
        if (state == null) return;
        for (int i = 0; i < match.getTeams().size(); i++) {
            removeMarker(state, i);
        }
    }

    private void spawnMarker(WarMatch match, int teamIndex, Location location) {
        if (location == null) return;
        ArmorStand stand = location.getWorld().spawn(location, ArmorStand.class);
        stand.setInvisible(false);
        stand.setInvulnerable(true);
        stand.setGravity(false);
        stand.setSmall(true);
        stand.setBasePlate(false);
        stand.setArms(false);
        stand.setMarker(false);
        stand.setCollidable(false);
        stand.setCustomNameVisible(true);
        stand.setCustomName((teamIndex == 0 ? ChatColor.RED : ChatColor.BLUE) + "Team " + (teamIndex + 1) + " Flag");

        ItemStack banner = new ItemStack(teamIndex == 0 ? Material.RED_BANNER : Material.BLUE_BANNER);
        var equipment = stand.getEquipment();
        if (equipment != null) equipment.setHelmet(banner);

        match.getCtfState().setMarkerEntity(teamIndex, stand.getUniqueId());
    }

    private void removeMarker(CtfState state, int teamIndex) {
        UUID markerId = state.getMarkerEntity(teamIndex);
        if (markerId == null) return;
        var entity = Bukkit.getEntity(markerId);
        if (entity != null) entity.remove();
        state.setMarkerEntity(teamIndex, null);
    }

    /** Called when a flag carrier dies or disconnects - drops the flag
     *  they were holding at their current location instead of it simply
     *  vanishing, and respawns a marker there for anyone to pick back up. */
    public void handleCarrierLost(WarMatch match, UUID playerId, Location dropLocation) {
        CtfState state = match.getCtfState();
        if (state == null) return;
        int teamIndex = state.flagCarriedBy(playerId);
        if (teamIndex == -1) return;

        state.setDropped(teamIndex, dropLocation);
        spawnMarker(match, teamIndex, dropLocation);
        Bukkit.broadcastMessage(flagColor(teamIndex) + "Team " + (teamIndex + 1) + "'s flag"
                + ChatColor.YELLOW + " was dropped!");
    }

    private ChatColor flagColor(int teamIndex) {
        return teamIndex == 0 ? ChatColor.RED : ChatColor.BLUE;
    }

    // ---------------------------------------------------------------- tick task

    private void startTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (WarMatch match : warManager.getActiveCtfMatches()) {
                tickMatch(match);
            }
        }, 10L, 10L);
    }

    private void tickMatch(WarMatch match) {
        CtfState state = match.getCtfState();
        if (state == null) return;
        List<List<UUID>> teams = match.getTeams();

        for (int flagTeam = 0; flagTeam < teams.size(); flagTeam++) {
            if (state.getStatus(flagTeam) == CtfState.FlagStatus.CARRIED) {
                checkForCapture(match, state, flagTeam);
            } else {
                checkForPickup(match, state, flagTeam);
            }
        }
    }

    /** A flag sitting at base or dropped can be picked up by anyone NOT on
     *  that flag's own team who walks close enough to it. */
    private void checkForPickup(WarMatch match, CtfState state, int flagTeam) {
        Location flagLoc = state.getStatus(flagTeam) == CtfState.FlagStatus.AT_BASE
                ? match.getArena().getFlagLocation(flagTeam)
                : state.getDroppedAt(flagTeam);
        if (flagLoc == null) return;

        for (int i = 0; i < match.getTeams().size(); i++) {
            if (i == flagTeam) continue;
            for (UUID memberId : match.getTeams().get(i)) {
                if (!match.isAlive(memberId)) continue;
                Player player = Bukkit.getPlayer(memberId);
                if (player == null || !player.getWorld().equals(flagLoc.getWorld())) continue;
                if (player.getLocation().distance(flagLoc) > PICKUP_RADIUS) continue;
                if (state.isCarryingAnyFlag(memberId)) continue; // already carrying a different flag

                removeMarker(state, flagTeam);
                state.setCarried(flagTeam, memberId);
                markCarrying(player, true);
                Bukkit.broadcastMessage(ChatColor.YELLOW + player.getName() + " picked up "
                        + flagColor(flagTeam) + "Team " + (flagTeam + 1) + "'s flag" + ChatColor.YELLOW + "!");
                return;
            }
        }
    }

    /** The carrier of an enemy flag scores a capture by reaching their OWN
     *  team's flag base - but only while their own flag is still sitting
     *  there (standard CTF rule: you can't cap with your flag stolen). */
    private void checkForCapture(WarMatch match, CtfState state, int flagTeam) {
        UUID carrierId = state.getCarrier(flagTeam);
        Player carrier = carrierId != null ? Bukkit.getPlayer(carrierId) : null;
        if (carrier == null) return;

        int carrierTeam = match.teamIndexOf(carrierId);
        if (carrierTeam == -1 || carrierTeam == flagTeam) return; // shouldn't happen, defensive
        if (state.getStatus(carrierTeam) != CtfState.FlagStatus.AT_BASE) return;

        Location ownBase = match.getArena().getFlagLocation(carrierTeam);
        if (ownBase == null || !carrier.getWorld().equals(ownBase.getWorld())) return;
        if (carrier.getLocation().distance(ownBase) > CAPTURE_RADIUS) return;

        state.addCapture(carrierTeam);
        state.setAtBase(flagTeam);
        spawnMarker(match, flagTeam, match.getArena().getFlagLocation(flagTeam));
        markCarrying(carrier, false);

        int captures = state.getCaptures(carrierTeam);
        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[CTF] " + ChatColor.YELLOW + carrier.getName()
                + ChatColor.GRAY + " captured the flag for " + flagColor(carrierTeam) + "Team " + (carrierTeam + 1)
                + ChatColor.GRAY + "! (" + captures + "/" + CAPTURES_TO_WIN + ")");

        if (captures >= CAPTURES_TO_WIN) {
            warManager.endMatch(match, carrierTeam, "captured " + CAPTURES_TO_WIN + " flags");
        }
    }

    /** Visually marks whether a player is currently carrying a flag with a
     *  glow effect visible to everyone, so both teams can see who has it
     *  and where they are. */
    private void markCarrying(Player player, boolean carrying) {
        player.setGlowing(carrying);
    }
}
