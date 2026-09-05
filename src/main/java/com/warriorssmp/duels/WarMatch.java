package com.warriorssmp.duels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An in-progress team war. Teams are stored as a list of member-UUID-lists
 * (team 0, team 1, ...) so this same structure covers 2v2 all the way
 * through 10v10 without a different data model per size. FFA elimination
 * (this phase's implemented mode) considers a team defeated once none of
 * its members are still alive.
 */
public class WarMatch {

    private final UUID id;
    private final List<List<UUID>> teams;
    private final Kit kit;
    private final Arena arena;
    /** The genuinely separate, cloned world this specific war match is
     *  actually happening in - never the admin's original template arena
     *  world directly, so multiple matches can use the same configured
     *  arena simultaneously without ever sharing physical space. */
    private final org.bukkit.World instanceWorld;
    private final WarMode mode;
    private final Map<UUID, PlayerStateSnapshot> snapshots = new HashMap<>();
    private final Set<UUID> alivePlayers = new HashSet<>();
    /** Whether every single participant on both sides was a real online
     *  player at match start - honor is only ever awarded when this is
     *  true, matching the same real-players-only rule as 1v1 duels. */
    private final boolean allPlayersReal;
    private final long startedAt;
    /** Only non-null for a CAPTURE_THE_FLAG match. Mutable and set after
     *  construction (not via the constructor) since building it needs the
     *  match's arena/teams, which are only available once this object
     *  already exists. */
    private CtfState ctfState;

    public WarMatch(List<List<UUID>> teams, Kit kit, Arena arena, org.bukkit.World instanceWorld, WarMode mode, boolean allPlayersReal) {
        this.id = UUID.randomUUID();
        this.teams = teams;
        this.kit = kit;
        this.arena = arena;
        this.instanceWorld = instanceWorld;
        this.mode = mode;
        this.allPlayersReal = allPlayersReal;
        this.startedAt = System.currentTimeMillis();
        for (List<UUID> team : teams) {
            alivePlayers.addAll(team);
        }
    }

    public org.bukkit.World getInstanceWorld() {
        return instanceWorld;
    }

    public CtfState getCtfState() {
        return ctfState;
    }

    public void setCtfState(CtfState ctfState) {
        this.ctfState = ctfState;
    }

    public List<List<UUID>> getTeams() {
        return teams;
    }

    public UUID getId() {
        return id;
    }

    public Kit getKit() {
        return kit;
    }

    public Arena getArena() {
        return arena;
    }

    public WarMode getMode() {
        return mode;
    }

    public boolean isAllPlayersReal() {
        return allPlayersReal;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long elapsedSeconds() {
        return (System.currentTimeMillis() - startedAt) / 1000L;
    }

    public void putSnapshot(UUID playerId, PlayerStateSnapshot snapshot) {
        snapshots.put(playerId, snapshot);
    }

    public PlayerStateSnapshot getSnapshot(UUID playerId) {
        return snapshots.get(playerId);
    }

    public boolean involves(UUID playerId) {
        for (List<UUID> team : teams) {
            if (team.contains(playerId)) return true;
        }
        return false;
    }

    public int teamIndexOf(UUID playerId) {
        for (int i = 0; i < teams.size(); i++) {
            if (teams.get(i).contains(playerId)) return i;
        }
        return -1;
    }

    public void markEliminated(UUID playerId) {
        alivePlayers.remove(playerId);
    }

    /** Marks a player as alive again after respawning in an objective
     *  mode (CTF and future modes) - the counterpart to markEliminated,
     *  used when death doesn't end someone's participation in the match. */
    public void markAlive(UUID playerId) {
        alivePlayers.add(playerId);
    }

    public boolean isAlive(UUID playerId) {
        return alivePlayers.contains(playerId);
    }

    /** For FFA: the index of the last team with any living members, or -1
     *  if more than one team still has survivors (match not over yet). */
    public int lastTeamStandingIndex() {
        int lastAliveTeam = -1;
        for (int i = 0; i < teams.size(); i++) {
            boolean teamHasSurvivor = false;
            for (UUID memberId : teams.get(i)) {
                if (alivePlayers.contains(memberId)) {
                    teamHasSurvivor = true;
                    break;
                }
            }
            if (teamHasSurvivor) {
                if (lastAliveTeam != -1) return -1; // more than one team still alive
                lastAliveTeam = i;
            }
        }
        return lastAliveTeam;
    }

    public List<UUID> allParticipants() {
        List<UUID> all = new ArrayList<>();
        for (List<UUID> team : teams) all.addAll(team);
        return all;
    }
}
