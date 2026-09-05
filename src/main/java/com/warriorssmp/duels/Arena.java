package com.warriorssmp.duels;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A configured arena - already-existing worlds on the server, per the
 * request, so this just records which world and which spawn points inside
 * it to use. Spawn points are a plain list rather than fixed slot-1/slot-2
 * fields so the same structure can grow into team arenas (2v2 through
 * 10v10) in a later phase without a data model change - for a 1v1 duel,
 * only the first two entries in the list are ever used.
 */
public class Arena {

    public enum Type { DUEL, WAR, GAUNTLET }

    private final UUID id;
    private String name;
    private Type type;
    private String worldName;
    private final List<Location> spawnPoints = new ArrayList<>();
    /** Team-grouped spawn points for war matches - keyed by team index (0,
     *  1, 2...). A single 1v1 duel arena only ever uses the flat
     *  spawnPoints list above; a war arena uses this instead, with each
     *  team's members spread across that team's own list of points. */
    private final Map<Integer, List<Location>> teamSpawnPoints = new HashMap<>();
    /** Each team's flag base location for Capture the Flag - keyed by team
     *  index, one location per team (not a list, since a team only ever
     *  has one flag). Only set on arenas an admin has actually configured
     *  for CTF; empty otherwise. */
    private final Map<Integer, Location> flagLocations = new HashMap<>();

    public Arena(UUID id, String name, Type type, String worldName) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.worldName = worldName;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public List<Location> getSpawnPoints() {
        return spawnPoints;
    }

    public Map<Integer, List<Location>> getTeamSpawnPoints() {
        return teamSpawnPoints;
    }

    public List<Location> getTeamSpawns(int teamIndex) {
        return teamSpawnPoints.computeIfAbsent(teamIndex, k -> new ArrayList<>());
    }

    public Map<Integer, Location> getFlagLocations() {
        return flagLocations;
    }

    public Location getFlagLocation(int teamIndex) {
        return flagLocations.get(teamIndex);
    }

    public void setFlagLocation(int teamIndex, Location location) {
        flagLocations.put(teamIndex, location);
    }

    public boolean isReadyForDuel() {
        return spawnPoints.size() >= 2;
    }

    /** A Gauntlet arena needs one spawn point per party member - each
     *  player gets their own, the same way a duel arena needs one per
     *  duelist. Supports parties of 1-4. */
    public boolean isReadyForGauntlet(int partySize) {
        return spawnPoints.size() >= partySize;
    }

    /** Ready for a war of the given team count if every team from 0 up to
     *  (but not including) teamCount has at least one spawn point set. */
    public boolean isReadyForWar(int teamCount) {
        for (int i = 0; i < teamCount; i++) {
            if (teamSpawnPoints.getOrDefault(i, List.of()).isEmpty()) return false;
        }
        return true;
    }

    /** Ready for CTF if it's already ready for a war of that size AND
     *  every team from 0 up to teamCount has a flag location set. */
    public boolean isReadyForCtf(int teamCount) {
        if (!isReadyForWar(teamCount)) return false;
        for (int i = 0; i < teamCount; i++) {
            if (flagLocations.get(i) == null) return false;
        }
        return true;
    }

    /** Builds a throwaway (never saved to disk) copy of this arena whose
     *  every location - flat spawn points, per-team spawn points, and CTF
     *  flag locations - points at newWorld instead of this arena's
     *  original world, keeping the exact same relative coordinates. Used
     *  by every match type (duels, wars, Gauntlet runs) once their arena
     *  world has been cloned into a fresh per-match instance, so the
     *  match actually teleports players into the right copy rather than
     *  the admin's shared original. This object itself is never
     *  registered with ArenaManager and never persisted - it only exists
     *  for the lifetime of the one match using it. */
    public Arena translatedTo(org.bukkit.World newWorld) {
        Arena instance = new Arena(UUID.randomUUID(), name, type, newWorld.getName());
        for (Location spawn : spawnPoints) {
            instance.spawnPoints.add(retarget(spawn, newWorld));
        }
        for (Map.Entry<Integer, List<Location>> entry : teamSpawnPoints.entrySet()) {
            List<Location> translated = new ArrayList<>();
            for (Location spawn : entry.getValue()) {
                translated.add(retarget(spawn, newWorld));
            }
            instance.teamSpawnPoints.put(entry.getKey(), translated);
        }
        for (Map.Entry<Integer, Location> entry : flagLocations.entrySet()) {
            instance.flagLocations.put(entry.getKey(), retarget(entry.getValue(), newWorld));
        }
        return instance;
    }

    private static Location retarget(Location original, org.bukkit.World newWorld) {
        Location translated = new Location(newWorld, original.getX(), original.getY(), original.getZ(),
                original.getYaw(), original.getPitch());
        loadChunksAround(translated);
        return translated;
    }

    /** Force-loads (synchronously, generating if needed) every chunk
     *  within a small radius of a translated location - a real, confirmed
     *  cause of players and mobs both ending up somewhere wrong (a player
     *  dropped straight into water, a mob spawned inside a wall) right
     *  after a freshly-cloned instance world loads: Bukkit doesn't
     *  proactively load chunks just because a world was created, and
     *  teleporting or spawning into a chunk before it's actually loaded
     *  can land on default/incomplete terrain state rather than the real,
     *  copied data - even though the copy itself was perfectly fine. The
     *  radius (not just the exact spawn chunk) matters too, since nearby
     *  mob spawns are offset by a few blocks from the arena's center
     *  point and can easily land in a neighboring chunk that was never
     *  otherwise touched. */
    private static void loadChunksAround(Location location) {
        org.bukkit.World world = location.getWorld();
        int centerChunkX = location.getBlockX() >> 4;
        int centerChunkZ = location.getBlockZ() >> 4;
        int radius = 2;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                world.getChunkAt(centerChunkX + dx, centerChunkZ + dz);
            }
        }
    }
}
