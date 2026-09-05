package com.warriorssmp.duels;

/**
 * The 3 bot difficulty tiers. Bots are real Zombie entities under the
 * hood (see BotManager) so they get proven, stable vanilla chase-and-melee
 * AI for free instead of needing custom pathfinding built from scratch -
 * difficulty is expressed by scaling their combat attributes, not by
 * different AI logic.
 */
public enum BotDifficulty {
    EASY("&aEasy", 0.6, 0.85, 0.5, 0.05),
    MEDIUM("&eMedium", 1.0, 1.0, 1.0, 0.12),
    HARD("&cHard", 1.6, 1.15, 1.5, 0.25);

    private final String displayName;
    private final double damageMultiplier;
    private final double speedMultiplier;
    private final double healthMultiplier;
    /** Per-tick chance (while in range of its target) that the bot
     *  attempts a jump attack, and separately, the chance per health-check
     *  interval that it uses a healing item when low - higher difficulty
     *  bots do both more often. */
    private final double aggressiveness;

    BotDifficulty(String displayName, double damageMultiplier, double speedMultiplier,
                  double healthMultiplier, double aggressiveness) {
        this.displayName = displayName;
        this.damageMultiplier = damageMultiplier;
        this.speedMultiplier = speedMultiplier;
        this.healthMultiplier = healthMultiplier;
        this.aggressiveness = aggressiveness;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getDamageMultiplier() {
        return damageMultiplier;
    }

    public double getSpeedMultiplier() {
        return speedMultiplier;
    }

    public double getHealthMultiplier() {
        return healthMultiplier;
    }

    public double getAggressiveness() {
        return aggressiveness;
    }
}
