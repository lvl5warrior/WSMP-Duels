package com.warriorssmp.duels;

/** A stackable roguelike buff offered every 5 waves cleared in The
 *  Gauntlet - picking the same buff again stacks it further (handled by
 *  GauntletManager tracking how many times each has been picked, not by
 *  this enum itself). LUCKY and SECOND_WIND are special-cased directly in
 *  GauntletManager's loot roll and death handling rather than being a
 *  PotionEffect, since neither has a vanilla potion equivalent. */
public enum GauntletBuff {
    VITALITY("&c&lVitality", "&7+2 max hearts per stack"),
    STRENGTH("&4&lStrength", "&7Increased melee damage"),
    SWIFTNESS("&b&lSwiftness", "&7Increased movement speed"),
    REGENERATION("&d&lRegeneration", "&7Slowly heal over time"),
    RESILIENCE("&9&lResilience", "&7Reduced damage taken"),
    LUCKY("&6&lLucky", "&7Better loot from kills"),
    SECOND_WIND("&a&lSecond Wind", "&7Survive a killing blow, once per stack");

    private final String displayName;
    private final String description;

    GauntletBuff(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
