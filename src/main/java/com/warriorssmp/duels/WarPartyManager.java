package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class WarPartyManager {

    private static final int MAX_PARTY_SIZE = 10;

    private final Map<UUID, WarParty> partyByMember = new HashMap<>();
    /** Keyed by the INVITED player's UUID -> the party they're invited to,
     *  mirroring the same one-pending-thing-at-a-time pattern as duel
     *  challenges. */
    private final Map<UUID, WarParty> pendingInvites = new HashMap<>();

    public WarParty getParty(UUID playerId) {
        return partyByMember.get(playerId);
    }

    public boolean inParty(UUID playerId) {
        return partyByMember.containsKey(playerId);
    }

    /** Creates a brand new party with this player as its sole member and
     *  leader, if they aren't already in one. */
    public WarParty getOrCreateParty(Player player) {
        WarParty existing = partyByMember.get(player.getUniqueId());
        if (existing != null) return existing;
        WarParty party = new WarParty(player.getUniqueId());
        partyByMember.put(player.getUniqueId(), party);
        return party;
    }

    public boolean invite(Player leader, Player target) {
        WarParty party = getOrCreateParty(leader);
        if (!party.getLeader().equals(leader.getUniqueId())) {
            leader.sendMessage(err("Only the party leader can invite players."));
            return false;
        }
        if (party.contains(target.getUniqueId())) {
            leader.sendMessage(err(target.getName() + " is already in your party."));
            return false;
        }
        if (party.size() >= MAX_PARTY_SIZE) {
            leader.sendMessage(err("Your party is already at the max size (" + MAX_PARTY_SIZE + ")."));
            return false;
        }
        if (partyByMember.containsKey(target.getUniqueId())) {
            leader.sendMessage(err(target.getName() + " is already in a party."));
            return false;
        }

        pendingInvites.put(target.getUniqueId(), party);
        leader.sendMessage(ok("Invited " + target.getName() + " to your party."));
        target.sendMessage(ok(leader.getName() + " invited you to their war party! Type /duels to respond."));
        return true;
    }

    public void acceptInvite(Player player) {
        WarParty party = pendingInvites.remove(player.getUniqueId());
        if (party == null) {
            player.sendMessage(err("You don't have a pending party invite."));
            return;
        }
        if (party.size() >= MAX_PARTY_SIZE) {
            player.sendMessage(err("That party is now full."));
            return;
        }

        // A real, confirmed bug: this used to add the player to their new
        // party without ever removing them from whatever party they were
        // already in (everyone starts in a solo party of themselves the
        // moment they touch the party system at all) - leaving a "ghost"
        // membership behind in the old party's member list even though
        // partyByMember now correctly points to the new one. Leaving the
        // old party first (silently, if it's just a solo party of one)
        // closes that gap the same way an explicit /wars leave would.
        WarParty oldParty = partyByMember.get(player.getUniqueId());
        if (oldParty != null && oldParty != party) {
            leaveParty(player);
        }

        party.getMembers().add(player.getUniqueId());
        partyByMember.put(player.getUniqueId(), party);
        broadcastToParty(party, player.getName() + " joined the party! (" + party.size() + "/" + MAX_PARTY_SIZE + ")");
    }

    public void declineInvite(Player player) {
        WarParty party = pendingInvites.remove(player.getUniqueId());
        if (party == null) {
            player.sendMessage(err("You don't have a pending party invite."));
            return;
        }
        player.sendMessage(ok("Declined the party invite."));
        Player leader = Bukkit.getPlayer(party.getLeader());
        if (leader != null) leader.sendMessage(err(player.getName() + " declined your party invite."));
    }

    public WarParty getPendingInvite(UUID playerId) {
        return pendingInvites.get(playerId);
    }

    /** Removes a player from whatever party they're in. If they were the
     *  leader and others remain, leadership passes to the next member; if
     *  they were the last member, the party is dissolved entirely. */
    public void leaveParty(Player player) {
        WarParty party = partyByMember.remove(player.getUniqueId());
        if (party == null) return;

        party.getMembers().remove(player.getUniqueId());
        if (party.getMembers().isEmpty()) return;

        broadcastToParty(party, player.getName() + " left the party.");
        // WarParty's leader field is final, so leadership transfer is really
        // "the old party object is retired and a fresh one inherits the
        // remaining members" rather than mutating the leader in place.
        if (party.getLeader().equals(player.getUniqueId())) {
            UUID newLeaderId = party.getMembers().get(0);
            WarParty newParty = new WarParty(newLeaderId);
            for (UUID memberId : party.getMembers()) {
                if (!newParty.contains(memberId)) newParty.getMembers().add(memberId);
                partyByMember.put(memberId, newParty);
            }
            // A real, confirmed bug: any invite still pending against the
            // OLD (now-retired) party object would otherwise silently
            // orphan itself here - accepting it later would add someone
            // to a party nobody actually belongs to anymore, since every
            // real member was just migrated to newParty above. Re-point
            // those invites to the new party object instead of leaving
            // them to rot.
            for (Map.Entry<UUID, WarParty> entry : pendingInvites.entrySet()) {
                if (entry.getValue() == party) entry.setValue(newParty);
            }
            Player newLeader = Bukkit.getPlayer(newLeaderId);
            if (newLeader != null) newLeader.sendMessage(ok("You are now the party leader."));
        }
    }

    /** Removes a specific member from the leader's own party - unlike
     *  leaveParty, this never removes the leader themselves (that's what
     *  leaving is for), so there's no leadership-transfer case to handle
     *  here. */
    public void kickMember(Player leader, UUID targetId) {
        WarParty party = partyByMember.get(leader.getUniqueId());
        if (party == null || !party.getLeader().equals(leader.getUniqueId())) {
            leader.sendMessage(err("Only the party leader can kick players."));
            return;
        }
        if (targetId.equals(leader.getUniqueId())) {
            leader.sendMessage(err("You can't kick yourself - use Leave Party instead."));
            return;
        }
        if (!party.contains(targetId)) {
            leader.sendMessage(err("That player isn't in your party."));
            return;
        }

        party.getMembers().remove(targetId);
        partyByMember.remove(targetId);

        String targetName = Bukkit.getOfflinePlayer(targetId).getName();
        broadcastToParty(party, (targetName != null ? targetName : "A player") + " was kicked from the party.");
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) target.sendMessage(err("You were kicked from " + leader.getName() + "'s party."));
    }

    public void broadcastToParty(WarParty party, String message) {
        for (UUID memberId : party.getMembers()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null) member.sendMessage(ChatColor.AQUA + "[Party] " + ChatColor.RESET + message);
        }
    }

    private String ok(String msg) {
        return ChatColor.GREEN + msg;
    }

    private String err(String msg) {
        return ChatColor.RED + msg;
    }
}
