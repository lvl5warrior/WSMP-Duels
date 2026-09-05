package com.warriorssmp.duels;

/**
 * War game modes. Only Capture the Flag has been fully built out with
 * real, working mechanics (flag pickup, capture, respawn-based combat) -
 * the other 4 modes originally planned (Free For All, Domination,
 * Infection, Search & Destroy) were removed rather than kept half-built,
 * since they only ever ran generic elimination logic under the hood
 * without any actual mode-specific objective. Adding a real one back is a
 * matter of giving it the same full treatment CTF got, not just adding
 * an enum value.
 */
public enum WarMode {
    CAPTURE_THE_FLAG("Capture the Flag");

    private final String displayName;

    WarMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
