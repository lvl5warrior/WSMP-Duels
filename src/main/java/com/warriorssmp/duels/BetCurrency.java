package com.warriorssmp.duels;

/** Which currency a bet is staked in - the bettor picks at bet time. */
public enum BetCurrency {
    VAULT("Vault money"),
    HONOR("Honor points");

    private final String displayName;

    BetCurrency(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
