package com.warriorssmp.duels;

import java.util.UUID;

/**
 * An in-progress 1v1 duel. Holds both players' pre-match snapshots (for
 * restoration afterward), which arena/kit were used, and timing info for
 * the 10-minute-then-sudden-death timer.
 */
public class DuelMatch {

    private final UUID player1;
    private final UUID player2;
    private final Kit kit;
    private final Arena arena;
    /** The genuinely separate, cloned world this specific duel is
     *  actually happening in - never the admin's original template arena
     *  world directly, so multiple duels can use the same configured
     *  arena simultaneously without ever sharing physical space. */
    private final org.bukkit.World instanceWorld;
    private final PlayerStateSnapshot snapshot1;
    private final PlayerStateSnapshot snapshot2; // null when player2 is a bot - a bot has no pre-match state to restore
    private final boolean player2IsBot;
    private final long startedAt;
    /** Whether real players are involved on both sides - only real-vs-real
     *  matches award honor and get announced to chat, per the request that
     *  honor and results only apply when actually fighting a player. */
    private final boolean bothPlayersReal;
    private boolean suddenDeathActive = false;

    /** Standard player-vs-player constructor. */
    public DuelMatch(UUID player1, UUID player2, Kit kit, Arena arena, org.bukkit.World instanceWorld,
                      PlayerStateSnapshot snapshot1, PlayerStateSnapshot snapshot2) {
        this.player1 = player1;
        this.player2 = player2;
        this.kit = kit;
        this.arena = arena;
        this.instanceWorld = instanceWorld;
        this.snapshot1 = snapshot1;
        this.snapshot2 = snapshot2;
        this.player2IsBot = false;
        this.bothPlayersReal = true;
        this.startedAt = System.currentTimeMillis();
    }

    /** Player-vs-bot constructor. player2 here is the bot ENTITY's UUID,
     *  not a real player's - callers must always check isPlayer2Bot()
     *  before treating player2 as something Bukkit.getPlayer() would
     *  resolve, since it won't. */
    public DuelMatch(UUID player1, UUID botEntityId, Kit kit, Arena arena, org.bukkit.World instanceWorld,
                      PlayerStateSnapshot snapshot1) {
        this.player1 = player1;
        this.player2 = botEntityId;
        this.kit = kit;
        this.arena = arena;
        this.instanceWorld = instanceWorld;
        this.snapshot1 = snapshot1;
        this.snapshot2 = null;
        this.player2IsBot = true;
        this.bothPlayersReal = false;
        this.startedAt = System.currentTimeMillis();
    }

    public boolean isPlayer2Bot() {
        return player2IsBot;
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

    public org.bukkit.World getInstanceWorld() {
        return instanceWorld;
    }

    public PlayerStateSnapshot getSnapshot(UUID playerId) {
        if (playerId.equals(player1)) return snapshot1;
        if (playerId.equals(player2)) return snapshot2;
        return null;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public boolean isBothPlayersReal() {
        return bothPlayersReal;
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
