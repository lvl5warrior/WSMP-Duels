package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tracks Gauntlet parties and pending invites - Gauntlet is the only
 *  mode that supports grouping up now, so this is the one remaining
 *  party system in the plugin (War mode's shared party system was
 *  removed along with War mode itself). */
public class GauntletPartyManager {

    private final DuelsPlugin plugin;

    /** Keyed by member UUID -> the party they're currently in (a leader
     *  is also counted as a member of their own party). A player with
     *  no party of their own and no pending invite isn't in this map at
     *  all. */
    private final Map<UUID, GauntletParty> partyByMember = new HashMap<>();
    /** Keyed by the INVITED player's UUID -> which party invited them -
     *  a player can only have one pending invite at a time. */
    private final Map<UUID, GauntletParty> pendingInvites = new HashMap<>();

    public GauntletPartyManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Read fresh from config rather than cached at construction time -
     *  GauntletManager and SpawnWandListener both read this exact same
     *  gauntlet.max-party-size setting the same way, and all three must
     *  never drift out of sync with each other. */
    private int maxPartySize() {
        return plugin.getConfig().getInt("gauntlet.max-party-size", 4);
    }

    public GauntletParty getOrCreateParty(Player leader) {
        return partyByMember.computeIfAbsent(leader.getUniqueId(), id -> new GauntletParty(id));
    }

    public GauntletParty getParty(UUID playerId) {
        return partyByMember.get(playerId);
    }

    public boolean invite(Player leader, Player target) {
        GauntletParty party = getOrCreateParty(leader);
        if (!party.getLeader().equals(leader.getUniqueId())) {
            leader.sendMessage(ChatColor.RED + "Only the party leader can invite.");
            return false;
        }
        if (party.getMembers().size() >= maxPartySize()) {
            leader.sendMessage(ChatColor.RED + "Your party is already full (max " + maxPartySize() + ").");
            return false;
        }
        if (party.getMembers().contains(target.getUniqueId())) {
            leader.sendMessage(ChatColor.RED + target.getName() + " is already in your party.");
            return false;
        }
        pendingInvites.put(target.getUniqueId(), party);
        leader.sendMessage(ChatColor.GREEN + "Invited " + target.getName() + " to your party.");
        target.sendMessage(ChatColor.GREEN + leader.getName() + " invited you to their Gauntlet party! "
                + ChatColor.GRAY + "Type /duels party accept or /duels party decline.");
        return true;
    }

    public void acceptInvite(Player player) {
        GauntletParty party = pendingInvites.remove(player.getUniqueId());
        if (party == null) {
            player.sendMessage(ChatColor.RED + "You don't have a pending party invite.");
            return;
        }
        // Leave whatever party they were already solo-in (their own,
        // single-member one) before joining the new one.
        partyByMember.remove(player.getUniqueId());
        party.getMembers().add(player.getUniqueId());
        partyByMember.put(player.getUniqueId(), party);
        player.sendMessage(ChatColor.GREEN + "Joined the party!");
        Player leader = Bukkit.getPlayer(party.getLeader());
        if (leader != null) leader.sendMessage(ChatColor.GREEN + player.getName() + " joined your party!");
    }

    public void declineInvite(Player player) {
        GauntletParty party = pendingInvites.remove(player.getUniqueId());
        if (party == null) {
            player.sendMessage(ChatColor.RED + "You don't have a pending party invite.");
            return;
        }
        player.sendMessage(ChatColor.GRAY + "Declined the party invite.");
        Player leader = Bukkit.getPlayer(party.getLeader());
        if (leader != null) leader.sendMessage(ChatColor.GRAY + player.getName() + " declined your invite.");
    }

    public void leaveParty(Player player) {
        GauntletParty party = partyByMember.get(player.getUniqueId());
        boolean soloLeaderOnly = party != null && party.getMembers().size() <= 1
                && party.getLeader().equals(player.getUniqueId());
        if (party == null || soloLeaderOnly) {
            player.sendMessage(ChatColor.RED + "You're not in a party with anyone else.");
            return;
        }
        party.getMembers().remove(player.getUniqueId());
        partyByMember.remove(player.getUniqueId());
        player.sendMessage(ChatColor.GRAY + "You left the party.");
        for (UUID memberId : party.getMembers()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null) member.sendMessage(ChatColor.GRAY + player.getName() + " left the party.");
        }
    }
}
