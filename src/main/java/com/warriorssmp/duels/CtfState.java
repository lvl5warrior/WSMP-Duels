package com.warriorssmp.duels;

import org.bukkit.Location;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks each team's flag state for an in-progress Capture the Flag
 * match. Each team has exactly one flag, which is always in exactly one
 * of three states: sitting at its own base, being carried by someone (of
 * either team - only the enemy actually wants it, but nothing stops a
 * teammate from picking up a dropped one to return it faster), or dropped
 * on the ground where its last carrier died/disconnected.
 */
public class CtfState {

    public enum FlagStatus { AT_BASE, CARRIED, DROPPED }

    private final Map<Integer, FlagStatus> status = new HashMap<>();
    private final Map<Integer, UUID> carrier = new HashMap<>();
    private final Map<Integer, Location> droppedAt = new HashMap<>();
    /** The armor stand entity currently representing this team's flag
     *  visually, whether at base or dropped - null while it's being
     *  carried, since a carried flag has no separate marker entity. */
    private final Map<Integer, UUID> markerEntity = new HashMap<>();
    private final Map<Integer, Integer> captures = new HashMap<>();

    public CtfState(int teamCount) {
        for (int i = 0; i < teamCount; i++) {
            status.put(i, FlagStatus.AT_BASE);
            captures.put(i, 0);
        }
    }

    public FlagStatus getStatus(int teamIndex) {
        return status.getOrDefault(teamIndex, FlagStatus.AT_BASE);
    }

    public void setAtBase(int teamIndex) {
        status.put(teamIndex, FlagStatus.AT_BASE);
        carrier.remove(teamIndex);
        droppedAt.remove(teamIndex);
    }

    public void setCarried(int teamIndex, UUID carrierId) {
        status.put(teamIndex, FlagStatus.CARRIED);
        carrier.put(teamIndex, carrierId);
        droppedAt.remove(teamIndex);
    }

    public void setDropped(int teamIndex, Location location) {
        status.put(teamIndex, FlagStatus.DROPPED);
        carrier.remove(teamIndex);
        droppedAt.put(teamIndex, location);
    }

    public UUID getCarrier(int teamIndex) {
        return carrier.get(teamIndex);
    }

    public Location getDroppedAt(int teamIndex) {
        return droppedAt.get(teamIndex);
    }

    public void setMarkerEntity(int teamIndex, UUID entityId) {
        if (entityId == null) markerEntity.remove(teamIndex);
        else markerEntity.put(teamIndex, entityId);
    }

    public UUID getMarkerEntity(int teamIndex) {
        return markerEntity.get(teamIndex);
    }

    /** True if this player is currently carrying ANY team's flag - a
     *  player can only ever carry one at a time. */
    public boolean isCarryingAnyFlag(UUID playerId) {
        return carrier.containsValue(playerId);
    }

    /** Which team's flag this player is carrying, or -1 if none. */
    public int flagCarriedBy(UUID playerId) {
        for (Map.Entry<Integer, UUID> entry : carrier.entrySet()) {
            if (entry.getValue().equals(playerId)) return entry.getKey();
        }
        return -1;
    }

    public void addCapture(int teamIndex) {
        captures.merge(teamIndex, 1, Integer::sum);
    }

    public int getCaptures(int teamIndex) {
        return captures.getOrDefault(teamIndex, 0);
    }
}
