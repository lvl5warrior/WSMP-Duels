package com.warriorssmp.duels;

/**
 * The 10 honor ranks, Private through Warlord. Rank is purely a function of
 * lifetime honor earned (see threshold) - it never decreases even if honor
 * is later spent on gear, since it represents total experience/reputation
 * earned, not current spendable balance.
 */
public enum HonorRank {

    PRIVATE("&7Private", 0),
    CORPORAL("&7Corporal", 100),
    SERGEANT("&aSergeant", 250),
    LIEUTENANT("&aLieutenant", 500),
    CAPTAIN("&bCaptain", 1000),
    MAJOR("&bMajor", 2000),
    COLONEL("&eColonel", 3500),
    GENERAL("&eGeneral", 5500),
    MARSHAL("&6Marshal", 8000),
    WARLORD("&c&lWarlord", 12000);

    private final String displayName;
    private final int threshold;

    HonorRank(String displayName, int threshold) {
        this.displayName = displayName;
        this.threshold = threshold;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getThreshold() {
        return threshold;
    }

    /** The rank for a given lifetime honor total - the highest rank whose
     *  threshold has been reached. */
    public static HonorRank forLifetimeHonor(int lifetimeHonor) {
        HonorRank result = PRIVATE;
        for (HonorRank rank : values()) {
            if (lifetimeHonor >= rank.threshold) {
                result = rank;
            }
        }
        return result;
    }

    /** The next rank up, or null if already at the top (Warlord). */
    public HonorRank next() {
        int nextOrdinal = ordinal() + 1;
        return nextOrdinal < values().length ? values()[nextOrdinal] : null;
    }
}
