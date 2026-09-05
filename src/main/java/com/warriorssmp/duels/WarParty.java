package com.warriorssmp.duels;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A temporary group of players who've teamed up to fight together in a war
 * match (2v2 through 10v10) - separate from WSMP-Teams' persistent guild
 * teams, since "who I want to war with right now" isn't necessarily the
 * same group as a long-term team. Exists only from formation through the
 * end of one war match.
 */
public class WarParty {

    private final UUID id;
    private final UUID leader;
    private final List<UUID> members = new ArrayList<>();

    public WarParty(UUID leader) {
        this.id = UUID.randomUUID();
        this.leader = leader;
        this.members.add(leader);
    }

    public UUID getId() {
        return id;
    }

    public UUID getLeader() {
        return leader;
    }

    public List<UUID> getMembers() {
        return members;
    }

    public boolean contains(UUID playerId) {
        return members.contains(playerId);
    }

    public int size() {
        return members.size();
    }
}
