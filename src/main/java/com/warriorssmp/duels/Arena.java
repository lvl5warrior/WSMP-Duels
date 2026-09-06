package com.warriorssmp.duels;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A configured arena - one of exactly two permanent, static maps on the
 * server (one for 1v1 duels, one for Gauntlet), never cloned or deleted.
 * Every match of that type runs directly in this same world, one at a
 * time; anyone else who wants that mode while it's occupied waits in
 * that map's queue instead (see MatchQueue).
 */
public class Arena {

    public enum Type { DUEL, GAUNTLET }

    private final UUID id;
    private String name;
    private Type type;
    private String worldName;
    private final List<Location> spawnPoints = new ArrayList<>();
    /** GAUNTLET ONLY - the single fixed point mobs spawn around each
     *  wave, separate from any player's own spawn point. Null until an
     *  admin sets it with the spawn wand. */
    private Location monsterSpawnPoint;

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

    public Location getMonsterSpawnPoint() {
        return monsterSpawnPoint;
    }

    public void setMonsterSpawnPoint(Location monsterSpawnPoint) {
        this.monsterSpawnPoint = monsterSpawnPoint;
    }

    public boolean isReadyForDuel() {
        return spawnPoints.size() >= 2;
    }

    /** A Gauntlet arena needs one spawn point per party member - each
     *  player gets their own, the same way a duel arena needs one per
     *  duelist - AND a monster spawn point set. Supports parties of
     *  1-4. */
    public boolean isReadyForGauntlet(int partySize) {
        return spawnPoints.size() >= partySize && monsterSpawnPoint != null;
    }
}
