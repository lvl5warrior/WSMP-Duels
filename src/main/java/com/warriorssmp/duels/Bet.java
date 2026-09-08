package com.warriorssmp.duels;

import java.util.UUID;

/** A single bet placed by a spectator on the outcome of an active duel.
 *  sideIndex means player1/player2 (0/1). Bets are held here only while
 *  the match is in progress - resolved (paid out or lost) the moment the
 *  match ends, then discarded. */
public record Bet(UUID bettorId, int sideIndex, double amount, BetCurrency currency) {
}
