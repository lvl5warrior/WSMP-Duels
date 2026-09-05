package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the full war flow: a party queues for Capture the Flag at
 * a given size, gets auto-matched against another same-sized queued
 * party, both leaders agree on a kit, and the match starts. Real players
 * only - bot play is 1v1-only (see DuelManager/DuelGUI); team-vs-bots
 * war matches were removed rather than kept unreliable. Only Capture the
 * Flag exists as a mode; the other modes originally planned were removed
 * for the same reason (never fully built past generic elimination logic).
 */
public class WarManager {

    private static final long SUDDEN_DEATH_START_SECONDS = 15 * 60; // longer than 1v1, matches take longer to organize

    private final DuelsPlugin plugin;
    private final ArenaManager arenaManager;
    private final PlayerDataManager playerDataManager;
    private final WarPartyManager partyManager;
    private final WorldInstanceManager worldInstanceManager;
    private final DiscordAnnouncer discordAnnouncer;
    private CtfManager ctfManager; // set after construction, see DuelsPlugin.onEnable - avoids a circular constructor dependency
    private BettingManager bettingManager; // same pattern - set after construction
    private SpectatorManager spectatorManager;
    private GauntletManager gauntletManager; // set after construction, same circular-dependency pattern as the others

    /** Queue key is "mode:size", e.g. "CAPTURE_THE_FLAG:3" - a list of
     *  parties waiting for an opponent of that exact mode and size. */
    private final Map<String, List<WarParty>> queues = new HashMap<>();
    /** A party leader's UUID -> the pending match-found kit agreement, once
     *  two parties have been matched but haven't both picked a kit yet. */
    private final Map<UUID, WarChallenge> pendingByLeader = new HashMap<>();
    private final Map<UUID, WarMatch> activeMatches = new HashMap<>(); // keyed by WarMatch.getId(), not a player
    private final Map<UUID, WarMatch> matchByPlayer = new HashMap<>();
    private final Map<UUID, PlayerStateSnapshot> pendingRespawnRestore = new HashMap<>();
    /** A real player who died in a CTF match and needs to respawn back
     *  INTO the match on their next PlayerRespawnEvent, rather than being
     *  restored to their pre-match state - death in an objective mode
     *  doesn't end participation the way it does in elimination modes. */
    private final Map<UUID, WarMatch> pendingCtfRespawn = new HashMap<>();

    public WarManager(DuelsPlugin plugin, ArenaManager arenaManager, PlayerDataManager playerDataManager,
                       WarPartyManager partyManager, WorldInstanceManager worldInstanceManager) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.playerDataManager = playerDataManager;
        this.partyManager = partyManager;
        this.worldInstanceManager = worldInstanceManager;
        this.discordAnnouncer = new DiscordAnnouncer(plugin);
        startTickTask();
    }

    public boolean hasActiveMatch(UUID playerId) {
        return matchByPlayer.containsKey(playerId);
    }

    public WarMatch getMatch(UUID playerId) {
        return matchByPlayer.get(playerId);
    }

    public WarChallenge getPendingKitAgreement(UUID leaderId) {
        return pendingByLeader.get(leaderId);
    }


    public void setCtfManager(CtfManager ctfManager) {
        this.ctfManager = ctfManager;
    }

    public CtfManager getCtfManager() {
        return ctfManager;
    }

    public void setBettingManager(BettingManager bettingManager) {
        this.bettingManager = bettingManager;
    }

    public void setSpectatorManager(SpectatorManager spectatorManager) {
        this.spectatorManager = spectatorManager;
    }

    public void setGauntletManager(GauntletManager gauntletManager) {
        this.gauntletManager = gauntletManager;
    }

    /** Whether this exact party object currently has an entry in any war
     *  queue - used to stop a party from also starting a Gauntlet run
     *  while they're waiting for a war opponent, since a match being
     *  found mid-Gauntlet-run would otherwise try to pull them into two
     *  game modes at once. */
    public boolean isPartyQueued(WarParty party) {
        for (List<WarParty> queue : queues.values()) {
            if (queue.contains(party)) return true;
        }
        return false;
    }

    /** All currently-active wars, for the spectate/bet browser to list. */
    public List<WarMatch> getAllActiveMatches() {
        return new ArrayList<>(activeMatches.values());
    }

    public WarMatch getActiveMatchById(UUID matchId) {
        return activeMatches.get(matchId);
    }

    /** All currently-active matches whose mode is CAPTURE_THE_FLAG - used
     *  by CtfManager's tick task to know which matches to actually check
     *  flag proximity for. */
    public List<WarMatch> getActiveCtfMatches() {
        List<WarMatch> result = new ArrayList<>();
        for (WarMatch match : activeMatches.values()) {
            if (match.getMode() == WarMode.CAPTURE_THE_FLAG) result.add(match);
        }
        return result;
    }

    // ---------------------------------------------------------------- queueing

    public void joinQueue(Player leader, WarMode mode, int teamSize) {
        WarParty party = partyManager.getOrCreateParty(leader);
        if (!party.getLeader().equals(leader.getUniqueId())) {
            leader.sendMessage(err("Only the party leader can queue for a war."));
            return;
        }
        if (party.size() != teamSize) {
            leader.sendMessage(err("Your party has " + party.size() + " member(s) - you need exactly "
                    + teamSize + " for this size."));
            return;
        }
        if (hasActiveMatch(leader.getUniqueId())) {
            leader.sendMessage(err("Your party is already in a match."));
            return;
        }
        if (gauntletManager != null) {
            for (UUID memberId : party.getMembers()) {
                if (gauntletManager.hasActiveRun(memberId)) {
                    leader.sendMessage(err("Your party can't queue for war while someone's in a Gauntlet run."));
                    return;
                }
            }
        }

        String key = queueKey(mode, teamSize);
        List<WarParty> queue = queues.computeIfAbsent(key, k -> new ArrayList<>());
        if (queue.contains(party)) {
            leader.sendMessage(err("Your party is already queued."));
            return;
        }

        // Try to find an already-waiting opponent party immediately, rather
        // than waiting for the next tick.
        for (WarParty opponent : queue) {
            if (partiesStillValid(party, opponent)) {
                queue.remove(opponent);
                partyManager.broadcastToParty(party, "Match found! Agree on a kit to start.");
                partyManager.broadcastToParty(opponent, "Match found! Agree on a kit to start.");
                beginKitAgreement(party, opponent, mode);
                return;
            }
        }

        queue.add(party);
        partyManager.broadcastToParty(party, "Queued for " + teamSize + "v" + teamSize + " "
                + mode.getDisplayName() + " - waiting for an opponent...");
    }

    public void leaveQueue(Player leader) {
        WarParty party = partyManager.getParty(leader.getUniqueId());
        if (party == null) return;
        boolean removedAny = false;
        for (List<WarParty> queue : queues.values()) {
            if (queue.remove(party)) removedAny = true;
        }
        if (removedAny) leader.sendMessage(ok("Left the queue."));
    }

    private boolean partiesStillValid(WarParty a, WarParty b) {
        for (UUID id : a.getMembers()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || hasActiveMatch(id)) return false;
        }
        for (UUID id : b.getMembers()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || hasActiveMatch(id)) return false;
        }
        return true;
    }

    private String queueKey(WarMode mode, int teamSize) {
        return mode.name() + ":" + teamSize;
    }

    // ---------------------------------------------------------------- kit agreement

    private void beginKitAgreement(WarParty partyA, WarParty partyB, WarMode mode) {
        WarChallenge challenge = new WarChallenge(partyA.getId(), partyA.getLeader(),
                partyB.getId(), partyB.getLeader(), mode);
        challenge.setState(WarChallenge.State.SELECTING_KIT);
        pendingByLeader.put(partyA.getLeader(), challenge);
        pendingByLeader.put(partyB.getLeader(), challenge);

        plugin.getDuelGUI().setPendingKitSelectReason(partyA.getLeader(), new PendingKitSelectReason.WarLeaderAgreement());
        plugin.getDuelGUI().setPendingKitSelectReason(partyB.getLeader(), new PendingKitSelectReason.WarLeaderAgreement());

        Player leaderA = Bukkit.getPlayer(partyA.getLeader());
        Player leaderB = Bukkit.getPlayer(partyB.getLeader());
        if (leaderA != null) plugin.getDuelGUI().openKitSelect(leaderA);
        if (leaderB != null) plugin.getDuelGUI().openKitSelect(leaderB);
    }

    /** Called when a party LEADER picks a kit on behalf of their whole
     *  party. Non-leaders never reach this - the kit-select GUI is only
     *  ever opened for leaders during war kit agreement. */
    public void leaderSelectKit(Player leader, Kit kit) {
        WarChallenge challenge = pendingByLeader.get(leader.getUniqueId());
        if (challenge == null) {
            leader.sendMessage(err("You don't have an active kit selection."));
            return;
        }

        boolean isChallenger = challenge.getChallengingLeader().equals(leader.getUniqueId());
        if (isChallenger) challenge.setChallengerKit(kit.getId());
        else challenge.setTargetKit(kit.getId());
        leader.sendMessage(ok("Your party will use: " + kit.getName()));

        UUID otherLeaderId = challenge.theOtherLeader(leader.getUniqueId());
        Player otherLeader = otherLeaderId != null ? Bukkit.getPlayer(otherLeaderId) : null;

        if (challenge.kitsAgreed()) {
            pendingByLeader.remove(challenge.getChallengingLeader());
            pendingByLeader.remove(challenge.getTargetLeader());
            if (otherLeader != null) otherLeader.sendMessage(ok("Both parties agreed on '" + kit.getName() + "' - starting match!"));
            leader.sendMessage(ok("Both parties agreed on '" + kit.getName() + "' - starting match!"));
            startMatch(challenge, kit);
        } else if (otherLeader != null && challenge.getChallengerKit() != null && challenge.getTargetKit() != null) {
            leader.sendMessage(err("Both parties need to pick the SAME kit. Try again!"));
            otherLeader.sendMessage(err("Both parties need to pick the SAME kit. Try again!"));
            challenge.setChallengerKit(null);
            challenge.setTargetKit(null);
            plugin.getDuelGUI().setPendingKitSelectReason(leader.getUniqueId(), new PendingKitSelectReason.WarLeaderAgreement());
            plugin.getDuelGUI().setPendingKitSelectReason(otherLeader.getUniqueId(), new PendingKitSelectReason.WarLeaderAgreement());
            plugin.getDuelGUI().openKitSelect(leader);
            plugin.getDuelGUI().openKitSelect(otherLeader);
        } else if (otherLeader != null) {
            otherLeader.sendMessage(ChatColor.YELLOW + leader.getName() + "'s party picked '" + kit.getName()
                    + "' - pick the same one to start!");
        }
    }

    // ---------------------------------------------------------------- match lifecycle

    private void startMatch(WarChallenge challenge, Kit kit) {
        WarParty partyA = partyManager.getParty(challenge.getChallengingLeader());
        WarParty partyB = partyManager.getParty(challenge.getTargetLeader());
        if (partyA == null || partyB == null) return;

        int teamSize = partyA.getMembers().size();
        boolean isCtf = challenge.getMode() == WarMode.CAPTURE_THE_FLAG;
        List<Arena> readyArenas = isCtf ? arenaManager.getReadyCtfArenas(2) : arenaManager.getReadyWarArenas(2);
        if (readyArenas.isEmpty()) {
            String reason = isCtf
                    ? "No arenas are set up for Capture the Flag yet - ask an admin to set flag locations."
                    : "No war arenas are configured yet - ask an admin to set one up.";
            notifyBothParties(partyA, partyB, err(reason));
            return;
        }
        Arena template = pickBestArena(readyArenas, teamSize);

        List<Location> criticalLocations = new ArrayList<>();
        criticalLocations.addAll(template.getTeamSpawns(0));
        criticalLocations.addAll(template.getTeamSpawns(1));
        if (isCtf) {
            for (Location flag : template.getFlagLocations().values()) criticalLocations.add(flag);
        }

        notifyBothParties(partyA, partyB, ok("Preparing your war arena..."));
        worldInstanceManager.createInstance(template.getWorldName(), criticalLocations, instanceWorld -> {
            prepareWorldForMatch(instanceWorld);
            Arena arena = template.translatedTo(instanceWorld);

            List<List<UUID>> teams = new ArrayList<>();
            teams.add(new ArrayList<>(partyA.getMembers()));
            teams.add(new ArrayList<>(partyB.getMembers()));

            WarMatch match = new WarMatch(teams, kit, arena, instanceWorld, challenge.getMode(), true);
            activeMatches.put(match.getId(), match);
            if (isCtf) ctfManager.initMatch(match);

            for (int teamIndex = 0; teamIndex < teams.size(); teamIndex++) {
                List<UUID> team = teams.get(teamIndex);
                List<Location> spawns = repairSpawnList(arena.getTeamSpawns(teamIndex), arena.getWorldName());
                TeamColor color = TeamColor.forTeamIndex(teamIndex);
                for (int i = 0; i < team.size(); i++) {
                    Player player = Bukkit.getPlayer(team.get(i));
                    if (player == null) continue;
                    matchByPlayer.put(player.getUniqueId(), match);
                    match.putSnapshot(player.getUniqueId(), PlayerStateSnapshot.capture(player));
                    Location spawn = pickSpawn(spawns, i, player.getLocation());
                    equipForMatch(player, kit, spawn, color);
                }
            }

            notifyBothParties(partyA, partyB, ok(teamSize + "v" + teamSize + " " + challenge.getMode().getDisplayName()
                    + " has started in arena '" + arena.getName() + "'!"));
            notifyBothParties(partyA, partyB, ChatColor.GRAY + "This arena has " + arena.getTeamSpawns(0).size()
                    + " Team 1 spawn(s) and " + arena.getTeamSpawns(1).size() + " Team 2 spawn(s).");
            for (List<UUID> team : teams) {
                for (UUID id : team) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null) {
                        player.sendTitle(ChatColor.GOLD + arena.getName(), ChatColor.GRAY + "War has begun!", 10, 40, 10);
                    }
                }
            }
        }, () -> notifyBothParties(partyA, partyB, err("Couldn't prepare a war arena right now - try again shortly.")));
    }

    /** Picks the best-configured arena for the requested team size, instead
     *  of randomly - if there are multiple war arenas registered (a
     *  half-finished test one alongside a properly set up one, for
     *  instance), random selection would inconsistently send matches to
     *  whichever one happened to get picked, which is confusing to debug
     *  and to play. "Best" means the smaller of its two teams' spawn
     *  counts is as close to (or above) teamSize as any other candidate -
     *  ties broken by whichever arena has more spawns overall. */
    /** Prepares a war arena's world to actually support the match about to
     *  run in it - forces PVP on (as before), and additionally forces the
     *  world off Peaceful difficulty, since Bukkit hard-refuses to spawn
     *  any hostile mob (Zombie included) while a world is set to Peaceful,
     *  throwing IllegalStateException - a real, confirmed cause of bot
     *  matches silently spawning zero bots. World difficulty also resets
     *  to whatever server.properties says on every server restart, so
     *  fixing it here at match start (rather than relying on an admin
     *  remembering to run /difficulty once) is what actually stays
     *  correct long-term. */
    private void prepareWorldForMatch(org.bukkit.World arenaWorld) {
        if (arenaWorld == null) return;
        if (!arenaWorld.getPVP()) arenaWorld.setPVP(true);
        if (arenaWorld.getDifficulty() == org.bukkit.Difficulty.PEACEFUL) {
            arenaWorld.setDifficulty(org.bukkit.Difficulty.EASY);
        }
    }

    private Arena pickBestArena(List<Arena> readyArenas, int teamSize) {
        Arena best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Arena candidate : readyArenas) {
            int team0Count = candidate.getTeamSpawns(0).size();
            int team1Count = candidate.getTeamSpawns(1).size();
            int minSpawns = Math.min(team0Count, team1Count);
            // Score rewards actually meeting the requested size, then
            // rewards having more spawns overall as a tiebreaker.
            int score = Math.min(minSpawns, teamSize) * 1000 + (team0Count + team1Count);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private void notifyBothParties(WarParty a, WarParty b, String message) {
        partyManager.broadcastToParty(a, message);
        partyManager.broadcastToParty(b, message);
    }

    /** Picks a spawn location for the i-th team member. If there are
     *  enough distinct spawn points, each member gets their own. If there
     *  aren't (a war arena set up with fewer spawn points than the team
     *  actually needs), reused points get a small circular offset instead
     *  of stacking every extra entity exactly on top of the same block -
     *  still not ideal (an admin should add more spawns), but at least
     *  entities land next to each other instead of inside each other. The
     *  fallback location is only used if the spawn list is completely
     *  empty, which validation should already prevent for a ready arena. */
    private Location pickSpawn(List<Location> spawns, int index, Location fallback) {
        if (spawns.isEmpty()) return fallback;
        Location base = spawns.get(index % spawns.size());
        int reuseRound = index / spawns.size();
        if (reuseRound == 0) return base;

        double angle = (2 * Math.PI / 6) * (index % 6);
        double radius = 1.5 * reuseRound;
        Location offset = base.clone();
        offset.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
        return offset;
    }

    /** Defensively repairs a possibly-null world reference on a spawn
     *  Location loaded from YAML. If the arena's world wasn't fully
     *  loaded yet at the moment ArenaManager deserialized arenas.yml
     *  during server startup, a Location's world can end up null even
     *  though the world itself is genuinely loaded by the time a match
     *  actually starts - this re-resolves it by name so a stale null
     *  reference can never cause spawn.getWorld().spawn(...) to throw. */
    private List<Location> repairSpawnList(List<Location> spawns, String worldName) {
        List<Location> repaired = new ArrayList<>();
        var world = Bukkit.getWorld(worldName);
        for (Location loc : spawns) {
            if (loc.getWorld() != null) {
                repaired.add(loc);
            } else if (world != null) {
                repaired.add(new Location(world, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()));
            }
            // If both loc.getWorld() and the re-resolved world are null,
            // this spawn point is genuinely unusable (world not loaded at
            // all right now) - it's dropped rather than passed through,
            // since using it would still throw later.
        }
        return repaired;
    }

    /** Respawns a player back into an ongoing objective-mode match (CTF
     *  and future objective modes) instead of treating their death as
     *  permanent elimination - re-equips them with the match kit and
     *  teleports them to one of their team's spawns, the same as match
     *  start. Elimination-style modes never call this; a dead player
     *  there stays out via the existing checkForWinner path. */
    public void respawnInMatch(WarMatch match, Player player) {
        int teamIndex = match.teamIndexOf(player.getUniqueId());
        if (teamIndex == -1) return;
        List<Location> spawns = repairSpawnList(match.getArena().getTeamSpawns(teamIndex), match.getArena().getWorldName());
        int index = spawns.isEmpty() ? 0 : (int) (Math.random() * spawns.size());
        Location spawn = pickSpawn(spawns, index, player.getLocation());
        equipForMatch(player, match.getKit(), spawn, TeamColor.forTeamIndex(teamIndex));
        match.markAlive(player.getUniqueId());
    }

    /** Whether this match's mode treats death as permanent elimination
     *  (the default) or as something a player simply respawns from -
     *  currently just CTF, but this is the single place future objective
     *  modes will each define their own death behavior. */
    public boolean isRespawnMode(WarMode mode) {
        return mode == WarMode.CAPTURE_THE_FLAG;
    }

    /** Queues a real player to respawn back into the match on their next
     *  PlayerRespawnEvent, rather than the normal restore-to-pre-match
     *  path. Consumed by WarListener's respawn handler. */
    public void queueCtfRespawn(WarMatch match, UUID playerId) {
        pendingCtfRespawn.put(playerId, match);
    }

    public WarMatch consumePendingCtfRespawn(UUID playerId) {
        return pendingCtfRespawn.remove(playerId);
    }

    private void equipForMatch(Player player, Kit kit, Location spawn, TeamColor color) {
        player.teleport(spawn);
        var inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(null);

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }

        inv.setHelmet(recolorWoolAndTag(kit.getHelmet(), color));
        inv.setChestplate(recolorWoolAndTag(kit.getChestplate(), color));
        inv.setLeggings(recolorWoolAndTag(kit.getLeggings(), color));
        inv.setBoots(recolorWoolAndTag(kit.getBoots(), color));
        inv.setItemInOffHand(recolorWoolAndTag(kit.getOffhand(), color));

        List<ItemStack> contents = kit.getContents();
        for (int i = 0; i < contents.size(); i++) {
            inv.setItem(i, recolorWoolAndTag(contents.get(i), color));
        }

        player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setGameMode(GameMode.SURVIVAL);
        player.sendMessage(color.getChatColor() + "You are on " + color.name() + " team!");
    }

    /** Any wool block in a kit gets recolored to the player's team color at
     *  equip time - this is what makes "team 1 red, team 2 blue" work from
     *  a single shared kit rather than needing a separately-built colored
     *  copy of the kit per team. Non-wool items pass through unchanged
     *  (still cloned and tagged as a kit item). */
    private ItemStack recolorWoolAndTag(ItemStack item, TeamColor color) {
        if (item == null) return null;
        ItemStack clone = item.clone();
        if (clone.getType().name().endsWith("_WOOL")) {
            clone.setType(color.getWoolMaterial());
        }
        ItemMeta meta = clone.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey("wsmpduels", "kit_item"),
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            clone.setItemMeta(meta);
        }
        return clone;
    }

    /** Ends a war for non-death reasons (all of one side disconnected,
     *  etc). Restores every real participant immediately and despawns any
     *  bots involved. */
    /** Voluntarily ends the caller's own active war. A bot war just ends
     *  cleanly with no result announced (there's nothing to forfeit
     *  against bots, and bot wars never award honor anyway). A real war
     *  treats leaving like a disconnect - the player is restored
     *  immediately (they're still alive, not respawning, so this can't
     *  reuse the death/respawn-restore path), then the match checks
     *  whether that decides it for whoever's left. */
    public void endMatchVoluntarily(Player player) {
        WarMatch match = matchByPlayer.get(player.getUniqueId());
        if (match == null) {
            player.sendMessage(err("You're not in an active war."));
            return;
        }

        restorePlayer(player, match);
        matchByPlayer.remove(player.getUniqueId());
        match.markEliminated(player.getUniqueId());
        int winningTeam = match.lastTeamStandingIndex();
        if (winningTeam != -1) {
            endMatch(match, winningTeam, player.getName() + " left the match");
        }
        player.sendMessage(err("You left the war."));
    }

    public void endMatch(WarMatch match, int winningTeamIndex, String reason) {
        activeMatches.remove(match.getId());
        for (UUID id : match.allParticipants()) matchByPlayer.remove(id);
        ctfManager.cleanupMatch(match);
        worldInstanceManager.destroyInstance(match.getInstanceWorld());

        for (UUID id : match.allParticipants()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) restorePlayer(player, match);
        }

        if (bettingManager != null) bettingManager.resolveBets(MatchKind.WAR, match.getId(), winningTeamIndex);
        if (spectatorManager != null) spectatorManager.handleMatchEnded(MatchKind.WAR, match.getId());
        awardResultAndAnnounce(match, winningTeamIndex, reason);
    }

    /** Ends a war where the last elimination just happened - the survivors
     *  (who didn't just die) are restored immediately; a real player who
     *  died in the finishing blow is queued for restoration on their
     *  respawn, same pattern as 1v1 duels. */
    public void endMatchByElimination(WarMatch match, int winningTeamIndex, UUID justDiedId) {
        activeMatches.remove(match.getId());
        for (UUID id : match.allParticipants()) matchByPlayer.remove(id);
        ctfManager.cleanupMatch(match);
        worldInstanceManager.destroyInstance(match.getInstanceWorld());

        for (UUID id : match.allParticipants()) {
            if (id.equals(justDiedId)) {
                PlayerStateSnapshot snapshot = match.getSnapshot(id);
                if (snapshot != null) pendingRespawnRestore.put(id, snapshot);
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player != null) restorePlayer(player, match);
        }

        if (bettingManager != null) bettingManager.resolveBets(MatchKind.WAR, match.getId(), winningTeamIndex);
        if (spectatorManager != null) spectatorManager.handleMatchEnded(MatchKind.WAR, match.getId());
        awardResultAndAnnounce(match, winningTeamIndex, "eliminated the other team");
    }

    public PlayerStateSnapshot consumePendingRestore(UUID playerId) {
        return pendingRespawnRestore.remove(playerId);
    }

    private void restorePlayer(Player player, WarMatch match) {
        PlayerStateSnapshot snapshot = match.getSnapshot(player.getUniqueId());
        if (snapshot != null) snapshot.restore(player);
    }

    private void awardResultAndAnnounce(WarMatch match, int winningTeamIndex, String reason) {
        String winningTeamName = "Team " + (winningTeamIndex + 1);
        List<String> winnerNames = new ArrayList<>();
        List<String> loserNames = new ArrayList<>();

        int winHonor = plugin.getConfig().getInt("honor.war-win-amount", 15);
        int lossHonor = plugin.getConfig().getInt("honor.war-loss-amount", 4);
        for (int i = 0; i < match.getTeams().size(); i++) {
            boolean won = i == winningTeamIndex;
            for (UUID id : match.getTeams().get(i)) {
                PlayerDuelData data = playerDataManager.get(id);
                if (won) {
                    data.addHonor(winHonor);
                    data.addWin();
                    winnerNames.add(nameOf(id));
                } else {
                    data.addHonor(lossHonor);
                    data.addLoss();
                    loserNames.add(nameOf(id));
                }
            }
        }
        playerDataManager.save();

        String message = ChatColor.GOLD + "" + ChatColor.BOLD + "[War] " + ChatColor.YELLOW + winningTeamName
                + ChatColor.GRAY + " (" + String.join(", ", winnerNames) + ") defeated "
                + ChatColor.GRAY + "(" + String.join(", ", loserNames) + ") "
                + ChatColor.DARK_GRAY + "- " + reason;
        Bukkit.broadcastMessage(message);
        discordAnnouncer.announce("Team fight: **" + winningTeamName + "** (" + String.join(", ", winnerNames)
                + ") defeated (" + String.join(", ", loserNames) + ") - " + reason);
    }

    /** Called from onDisable, before the server saves player data during
     *  shutdown - restores every online player who's actively mid-match
     *  right now back to their real inventory. Same reasoning as
     *  DuelManager's version: without this, a /stop or /reload while
     *  anyone is mid-war would leave their real items sitting only in an
     *  in-memory snapshot that's about to disappear, and the server
     *  would save whatever kit items they were holding as their new,
     *  permanent inventory instead. */
    public void restoreAllOnShutdown() {
        for (WarMatch match : new ArrayList<>(activeMatches.values())) {
            for (UUID id : match.allParticipants()) {
                Player player = Bukkit.getPlayer(id);
                if (player == null) continue;
                PlayerStateSnapshot snapshot = match.getSnapshot(id);
                if (snapshot != null) snapshot.restore(player);
            }
        }
    }

    public void handleQuit(UUID playerId) {
        WarMatch match = matchByPlayer.get(playerId);
        if (match != null) {
            if (match.getCtfState() != null) {
                Player quitting = Bukkit.getPlayer(playerId);
                Location dropAt = quitting != null ? quitting.getLocation() : null;
                if (dropAt != null) ctfManager.handleCarrierLost(match, playerId, dropAt);
            }
            match.markEliminated(playerId);
            checkForWinner(match, playerId);
        }
        Player asPlayer = Bukkit.getPlayer(playerId);
        if (asPlayer != null) partyManager.leaveParty(asPlayer);
    }

    /** checkForWinner is only ever called from handleQuit (a disconnect),
     *  never from an actual combat death - PlayerDeathEvent for War
     *  matches is handled entirely separately in WarListener.onDeath. A
     *  real, confirmed bug here: the "match still going" branch used to
     *  queue the disconnecting player's restore for their next
     *  PlayerRespawnEvent - but since they're disconnecting while still
     *  ALIVE (not dead), that event would never come, and their real
     *  inventory would never be restored; whatever kit items they were
     *  holding at disconnect would be saved as their new inventory
     *  instead, permanently. They're still genuinely online at this exact
     *  point (mid-PlayerQuitEvent), so restoring immediately is both safe
     *  and the only chance to do it before they actually leave. */
    public void checkForWinner(WarMatch match, UUID justEliminatedId) {
        int winningTeam = match.lastTeamStandingIndex();
        if (winningTeam != -1) {
            endMatchByElimination(match, winningTeam, justEliminatedId);
        } else {
            Player eliminated = Bukkit.getPlayer(justEliminatedId);
            if (eliminated != null) restorePlayer(eliminated, match);
            matchByPlayer.remove(justEliminatedId);
        }
    }

    private String nameOf(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) return online.getName();
        String offlineName = Bukkit.getOfflinePlayer(playerId).getName();
        return offlineName != null ? offlineName : playerId.toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- tick task

    private void startTickTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (WarMatch match : new ArrayList<>(activeMatches.values())) {
                long elapsed = match.elapsedSeconds();
                if (elapsed >= SUDDEN_DEATH_START_SECONDS) {
                    for (UUID id : match.allParticipants()) {
                        if (!match.isAlive(id)) continue;
                        var entity = Bukkit.getEntity(id);
                        if (!(entity instanceof org.bukkit.entity.LivingEntity living)) continue;
                        double newHealth = Math.max(0.0, living.getHealth() - 1.0);
                        living.setHealth(newHealth);
                    }
                }
            }
        }, 20L, 20L);
    }

    private String ok(String msg) {
        return ChatColor.GREEN + msg;
    }

    private String err(String msg) {
        return ChatColor.RED + msg;
    }
}
