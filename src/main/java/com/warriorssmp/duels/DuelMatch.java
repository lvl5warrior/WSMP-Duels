package com.warriorssmp.duels;

import java.util.UUID;

/**
 * An in-progress 1v1 duel. Holds both players' pre-match snapshots (for
 * restoration afterward), which kit was used, and timing info for the
 * 10-minute-then-sudden-death timer. Always runs directly in the single,
 * static Duel arena - there is never more than one of these active at
 * once; anyone else who wants a duel while this is running waits in the
 * queue instead (see MatchQueue).
 */
public class DuelMatch {

    private final UUID player1;
    private final UUID player2;
    private final Kit kit;
    private final Arena arena;
    private final PlayerStateSnapshot snapshot1;
    private final PlayerStateSnapshot snapshot2;
    private final long startedAt;
    private boolean suddenDeathActive = false;

    public DuelMatch(UUID player1, UUID player2, Kit kit, Arena arena,
                      PlayerStateSnapshot snapshot1, PlayerStateSnapshot snapshot2) {
        this.player1 = player1;
        this.player2 = player2;
        this.kit = kit;
        this.arena = arena;
        this.snapshot1 = snapshot1;
        this.snapshot2 = snapshot2;
        this.startedAt = System.currentTimeMillis();
    }

    public UUID getPlayer1() {
        return player1;
    }

    public UUID getPlayer2() {
        return player2;
    }

    public Kit getKit() {
        return kit;
    }

    public Arena getArena() {
        return arena;
    }

    public PlayerStateSnapshot getSnapshot(UUID playerId) {
        if (playerId.equals(player1)) return snapshot1;
        if (playerId.equals(player2)) return snapshot2;
        return null;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public boolean isSuddenDeathActive() {
        return suddenDeathActive;
    }

    public void setSuddenDeathActive(boolean suddenDeathActive) {
        this.suddenDeathActive = suddenDeathActive;
    }

    public boolean involves(UUID playerId) {
        return player1.equals(playerId) || player2.equals(playerId);
    }

    public UUID theOtherPlayer(UUID playerId) {
        if (player1.equals(playerId)) return player2;
        if (player2.equals(playerId)) return player1;
        return null;
    }

    public long elapsedSeconds() {
        return (System.currentTimeMillis() - startedAt) / 1000L;
    }
}
