package com.warriorssmp.duels;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A Gauntlet party - the leader plus 0 or more invited/accepted
 *  members. Gauntlet is the only mode that supports grouping up at all
 *  now that War mode (and its shared party system) has been removed. */
public class GauntletParty {

    private final UUID leader;
    private final List<UUID> members = new ArrayList<>();

    public GauntletParty(UUID leader) {
        this.leader = leader;
        this.members.add(leader);
    }

    public UUID getLeader() {
        return leader;
    }

    public List<UUID> getMembers() {
        return members;
    }
}
