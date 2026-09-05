package com.warriorssmp.duels;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tracks one Gauntlet run - which can be a solo player or a co-op party
 *  of up to 4. Wave, alive mobs, and wave number are shared by the whole
 *  party (they fight the same wave together); buffs are picked and
 *  tracked per player, so a 4-player run can end up with each player
 *  built differently. Difficulty scaling uses the ORIGINAL party size
 *  fixed at the run's start, not however many players are still alive -
 *  a run stays exactly as hard as when it began even as people die or
 *  leave, rather than getting easier as the group shrinks. */
public class GauntletRun {

    private final UUID id = UUID.randomUUID();
    private final Arena arena;
    /** The genuinely separate, cloned world this specific run is
     *  actually happening in - never the admin's original template arena
     *  world directly, so multiple runs can use the same configured
     *  arena simultaneously without ever sharing physical space. */
    private final org.bukkit.World instanceWorld;
    private final int originalPartySize;
    /** Still-active participants (alive, haven't left or died) - shrinks
     *  as players die or leave; never grows past who started. */
    private final List<UUID> players = new ArrayList<>();
    private final Map<UUID, PlayerStateSnapshot> snapshots = new HashMap<>();
    private final Map<UUID, Map<GauntletBuff, Integer>> buffStacksByPlayer = new HashMap<>();
    /** Gauntlet Coins earned by selling drops to the merchant - a
     *  per-run currency only, never touching Vault money or Honor, so
     *  players can't stockpile real economy currency by farming the
     *  Gauntlet. Resets to nothing every run since it's never persisted
     *  anywhere outside this object. */
    private final Map<UUID, Integer> coins = new HashMap<>();
    private final Set<UUID> awaitingBuffPick = new HashSet<>();
    /** Players currently "downed" (would have died, but party revival
     *  gave them a 10-second window instead) - keyed to the scheduled
     *  task that finalizes their removal if nobody revives them in time,
     *  so reviving early can cancel it. Only ever used in a multiplayer
     *  run; a solo player has no one to revive them and just dies
     *  normally. */
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> downedPlayers = new HashMap<>();
    /** Players who've explicitly opted to skip the remaining break early -
     *  separate from having picked a buff, since a real, confirmed bug
     *  used to end the break the instant everyone picked a buff, which
     *  for a solo player collapsed the intended 3-minute break down to
     *  just a few seconds. Picking a buff no longer ends the break by
     *  itself; only this explicit opt-in (or the break's own timer
     *  running out) does. */
    private final Set<UUID> readyForNextWave = new HashSet<>();
    /** Whether a break is currently in progress - lets code outside the
     *  merchant GUI (like a /ready command) know whether "ready up" is
     *  even a meaningful thing to do right now, without having to infer
     *  it indirectly from buff-pick or other break-only state. */
    private boolean breakActive = false;
    private int wave = 1;
    private final List<UUID> aliveMobs = new ArrayList<>();

    public GauntletRun(Arena arena, int originalPartySize, org.bukkit.World instanceWorld) {
        this.arena = arena;
        this.originalPartySize = originalPartySize;
        this.instanceWorld = instanceWorld;
    }

    public org.bukkit.World getInstanceWorld() {
        return instanceWorld;
    }

    public Arena getArena() {
        return arena;
    }

    public UUID getId() {
        return id;
    }

    public int getOriginalPartySize() {
        return originalPartySize;
    }

    public void addPlayer(UUID playerId, PlayerStateSnapshot snapshot) {
        players.add(playerId);
        snapshots.put(playerId, snapshot);
        buffStacksByPlayer.put(playerId, new EnumMap<>(GauntletBuff.class));
        coins.put(playerId, 0);
    }

    public int getCoins(UUID playerId) {
        return coins.getOrDefault(playerId, 0);
    }

    /** Adds (or, with a negative amount, spends) Gauntlet Coins - never
     *  goes below zero. */
    public void addCoins(UUID playerId, int amount) {
        coins.merge(playerId, amount, (current, delta) -> Math.max(0, current + delta));
    }

    /** Removes a player from the run (death or voluntary leave) without
     *  touching anything else - the run and its wave keep going for
     *  whoever's left. */
    public void removePlayer(UUID playerId) {
        players.remove(playerId);
        awaitingBuffPick.remove(playerId);
    }

    public List<UUID> getPlayers() {
        return players;
    }

    public boolean isEmpty() {
        return players.isEmpty();
    }

    public PlayerStateSnapshot getSnapshot(UUID playerId) {
        return snapshots.get(playerId);
    }

    public int getWave() {
        return wave;
    }

    public void setWave(int wave) {
        this.wave = wave;
    }

    public List<UUID> getAliveMobs() {
        return aliveMobs;
    }

    public int getBuffStacks(UUID playerId, GauntletBuff buff) {
        return buffStacksByPlayer.getOrDefault(playerId, Map.of()).getOrDefault(buff, 0);
    }

    public void addBuffStack(UUID playerId, GauntletBuff buff) {
        buffStacksByPlayer.computeIfAbsent(playerId, k -> new EnumMap<>(GauntletBuff.class)).merge(buff, 1, Integer::sum);
    }

    public void consumeBuffStack(UUID playerId, GauntletBuff buff) {
        Map<GauntletBuff, Integer> stacks = buffStacksByPlayer.get(playerId);
        if (stacks == null) return;
        int current = stacks.getOrDefault(buff, 0);
        if (current <= 0) return;
        stacks.put(buff, current - 1);
    }

    public void setAwaitingBuffPick(UUID playerId, boolean awaiting) {
        if (awaiting) awaitingBuffPick.add(playerId);
        else awaitingBuffPick.remove(playerId);
    }

    public boolean isAwaitingBuffPick(UUID playerId) {
        return awaitingBuffPick.contains(playerId);
    }


    public boolean isDowned(UUID playerId) {
        return downedPlayers.containsKey(playerId);
    }

    public void markDowned(UUID playerId, org.bukkit.scheduler.BukkitTask timeoutTask) {
        downedPlayers.put(playerId, timeoutTask);
    }

    /** Clears downed state, cancelling the pending removal timeout if it
     *  hadn't already fired - used both when a teammate revives someone
     *  in time and when the timeout itself finally fires (so there's
     *  nothing left to double-cancel). */
    public void clearDowned(UUID playerId) {
        org.bukkit.scheduler.BukkitTask task = downedPlayers.remove(playerId);
        if (task != null) task.cancel();
    }

    public void markReadyForNextWave(UUID playerId) {
        readyForNextWave.add(playerId);
    }

    public boolean isReadyForNextWave(UUID playerId) {
        return readyForNextWave.contains(playerId);
    }

    /** True once every still-active player has explicitly opted to skip
     *  the remaining break early. */
    public boolean allPlayersReadyToSkipBreak() {
        return readyForNextWave.containsAll(getPlayers());
    }

    /** Clears the ready-to-skip tracking - called whenever a new break
     *  actually starts, so a stale "ready" from several waves ago (or
     *  from a wave with no break at all) can never carry forward and
     *  instantly skip a break nobody actually opted into this time. */
    public void resetReadyForNextWave() {
        readyForNextWave.clear();
    }

    public boolean isBreakActive() {
        return breakActive;
    }

    public void setBreakActive(boolean breakActive) {
        this.breakActive = breakActive;
    }
}
