package com.warriorssmp.duels;

import java.util.UUID;

/** Identifies one specific match for betting/spectating purposes. A
 *  DuelMatch's ID (player1's UUID) and a GauntletRun's ID (its own
 *  random UUID) come from different ID spaces, so pairing the UUID with
 *  which kind it belongs to - rather than trusting the UUID alone - is
 *  what guarantees a duel and a Gauntlet run can never be confused for
 *  one another, however unlikely an actual UUID collision would be. */
public record MatchRef(MatchKind kind, UUID matchId) {
}
