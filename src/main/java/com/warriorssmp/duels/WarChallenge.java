package com.warriorssmp.duels;

import java.util.UUID;

/**
 * A party-vs-party challenge, from send through kit agreement - the party
 * equivalent of DuelChallenge. Only the two party LEADERS pick a kit on
 * behalf of their whole party, rather than every member voting
 * individually, to keep the flow manageable for larger parties.
 */
public class WarChallenge {

    public enum State { AWAITING_ACCEPT, SELECTING_KIT }

    private final UUID challengingParty;
    private final UUID challengingLeader;
    private final UUID targetParty;
    private final UUID targetLeader;
    private final WarMode mode;
    private final long createdAt;
    private State state = State.AWAITING_ACCEPT;
    private UUID challengerKit;
    private UUID targetKit;

    public WarChallenge(UUID challengingParty, UUID challengingLeader, UUID targetParty, UUID targetLeader, WarMode mode) {
        this.challengingParty = challengingParty;
        this.challengingLeader = challengingLeader;
        this.targetParty = targetParty;
        this.targetLeader = targetLeader;
        this.mode = mode;
        this.createdAt = System.currentTimeMillis();
    }

    public UUID getChallengingParty() {
        return challengingParty;
    }

    public UUID getChallengingLeader() {
        return challengingLeader;
    }

    public UUID getTargetParty() {
        return targetParty;
    }

    public UUID getTargetLeader() {
        return targetLeader;
    }

    public WarMode getMode() {
        return mode;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public UUID getChallengerKit() {
        return challengerKit;
    }

    public void setChallengerKit(UUID challengerKit) {
        this.challengerKit = challengerKit;
    }

    public UUID getTargetKit() {
        return targetKit;
    }

    public void setTargetKit(UUID targetKit) {
        this.targetKit = targetKit;
    }

    public boolean kitsAgreed() {
        return challengerKit != null && challengerKit.equals(targetKit);
    }

    public boolean involvesLeader(UUID leaderId) {
        return challengingLeader.equals(leaderId) || targetLeader.equals(leaderId);
    }

    public UUID theOtherLeader(UUID leaderId) {
        if (challengingLeader.equals(leaderId)) return targetLeader;
        if (targetLeader.equals(leaderId)) return challengingLeader;
        return null;
    }
}
