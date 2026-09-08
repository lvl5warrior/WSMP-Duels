package com.warriorssmp.duels;

import java.util.UUID;

/**
 * Tracks one challenge from the moment it's sent through kit agreement.
 * Both players must independently pick the SAME kit before the match
 * actually starts - picking a kit just records that player's choice; the
 * match only begins once both choices match.
 */
public class DuelChallenge {

    public enum State { AWAITING_ACCEPT, SELECTING_KIT }

    private final UUID challenger;
    private final UUID target;
    private final long createdAt;
    private State state = State.AWAITING_ACCEPT;
    private UUID challengerKit;
    private UUID targetKit;

    public DuelChallenge(UUID challenger, UUID target) {
        this.challenger = challenger;
        this.target = target;
        this.createdAt = System.currentTimeMillis();
    }

    public UUID getChallenger() {
        return challenger;
    }

    public UUID getTarget() {
        return target;
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

    /** True once both players have picked, and picked the same kit. */
    public boolean kitsAgreed() {
        return challengerKit != null && challengerKit.equals(targetKit);
    }

    public boolean involves(UUID playerId) {
        return challenger.equals(playerId) || target.equals(playerId);
    }

    public UUID theOtherPlayer(UUID playerId) {
        if (challenger.equals(playerId)) return target;
        if (target.equals(playerId)) return challenger;
        return null;
    }
}
