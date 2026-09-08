package com.warriorssmp.duels;

/** Whether a match/run being spectated (and, for DUEL, bet on) is a 1v1
 *  duel (DuelManager) or a Gauntlet run (GauntletManager) - each is
 *  looked up and resolved through its own manager, so this tags which
 *  one a given ID belongs to. Betting only applies to DUEL, since a
 *  Gauntlet run is co-op PvE with no "side" to wager on. */
public enum MatchKind {
    DUEL,
    GAUNTLET
}
