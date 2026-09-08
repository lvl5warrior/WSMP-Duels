package com.warriorssmp.duels;

/**
 * The single source of truth for why a given player is currently looking
 * at the kit-select screen. Before this existed, the same screen was
 * routed by checking separate, independently-owned pending-state maps in
 * a fixed priority order - if any of them was left set from an abandoned
 * earlier attempt, it would silently hijack a completely unrelated later
 * kit pick, since it was checked first regardless of which flow the
 * player had actually just finished. That was a real, confirmed bug.
 * Storing exactly one of these per player, and always OVERWRITING rather
 * than merely setting, makes that class of bug structurally impossible:
 * there is never more than one reason active, so there is nothing for a
 * stale one to hijack.
 */
public sealed interface PendingKitSelectReason {

    /** No extra data needed - the actual challenge state lives in
     *  DuelManager's challengesByTarget map, keyed by the same player. */
    record NormalDuelChallenge() implements PendingKitSelectReason {}
}
