package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DuelManager {

    /** Loaded from config at construction (duels.challenge-timeout-seconds) -
     *  see the safe-default fallback in the constructor. */
    private final long challengeTimeoutSeconds;
    /** Loaded from config at construction (duels.sudden-death-start-seconds) -
     *  after this long in a match, sudden death begins (1 HP/sec drain
     *  until someone dies). */
    private final long suddenDeathStartSeconds;

    /** One pending, queued duel request - everything needed to actually
     *  start the match once it's this request's turn on the map. */
    private record QueueEntry(UUID player1, UUID player2, Kit kit) {
    }

    private final DuelsPlugin plugin;
    private final ArenaManager arenaManager;
    private final PlayerDataManager playerDataManager;
    private final DiscordAnnouncer discordAnnouncer;
    private BettingManager bettingManager; // set after construction, see DuelsPlugin.onEnable - avoids a circular constructor dependency
    private SpectatorManager spectatorManager; // same pattern

    /** Keyed by the TARGET's UUID - a player can only have one incoming
     *  challenge pending at a time. */
    private final Map<UUID, DuelChallenge> challengesByTarget = new HashMap<>();
    /** Only one duel map exists, so only one match can ever be active at
     *  once - null whenever the map is free. Anyone else who wants a
     *  duel while this is non-null waits in queue instead. */
    private DuelMatch currentMatch;
    private final MatchQueue<QueueEntry> queue = new MatchQueue<>();
    /** Players whose match just ended via death - their PlayerDeathEvent has
     *  already been handled (stats/honor/announcement), but restoring their
     *  inventory and teleporting them back must wait until PlayerRespawnEvent,
     *  since doing it during the death event gets overwritten by Bukkit's own
     *  respawn teleport a moment later. */
    private final Map<UUID, PlayerStateSnapshot> pendingRespawnRestore = new HashMap<>();

    public DuelManager(DuelsPlugin plugin, ArenaManager arenaManager, PlayerDataManager playerDataManager) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.playerDataManager = playerDataManager;
        this.discordAnnouncer = new DiscordAnnouncer(plugin);
        this.challengeTimeoutSeconds = plugin.getConfig().getLong("duels.challenge-timeout-seconds", 60);
        this.suddenDeathStartSeconds = plugin.getConfig().getLong("duels.sudden-death-start-seconds", 600);
        startTickTask();
    }

    public void setBettingManager(BettingManager bettingManager) {
        this.bettingManager = bettingManager;
    }

    public void setSpectatorManager(SpectatorManager spectatorManager) {
        this.spectatorManager = spectatorManager;
    }

    /** All currently-active duels, for the spectate/bet browser to list -
     *  at most one, since there's only one duel map. */
    public List<DuelMatch> getAllActiveMatches() {
        List<DuelMatch> result = new ArrayList<>();
        if (currentMatch != null) result.add(currentMatch);
        return result;
    }

    public DuelMatch getActiveMatchById(UUID matchId) {
        return currentMatch != null && currentMatch.getPlayer1().equals(matchId) ? currentMatch : null;
    }

    /** 0 if playerId is player1, 1 if player2, -1 if neither - used to
     *  translate a winner's UUID into the side index bets are keyed by. */
    public int sideIndexOf(DuelMatch match, UUID playerId) {
        if (playerId == null) return -1;
        if (playerId.equals(match.getPlayer1())) return 0;
        if (playerId.equals(match.getPlayer2())) return 1;
        return -1;
    }

    // ---------------------------------------------------------------- queue status (for the queue GUI)

    public boolean isMapOccupied() {
        return currentMatch != null;
    }

    public DuelMatch getCurrentMatch() {
        return currentMatch;
    }

    public int getQueueSize() {
        return queue.size();
    }

    /** One display line per queued request, in order (first = next up) -
     *  e.g. "Alice vs Bob". */
    public List<String> getQueueDescriptions() {
        List<String> lines = new ArrayList<>();
        for (QueueEntry entry : queue.snapshot()) {
            lines.add(nameOf(entry.player1()) + " vs " + nameOf(entry.player2()));
        }
        return lines;
    }

    // ---------------------------------------------------------------- challenge flow

    public boolean hasActiveMatch(UUID playerId) {
        return currentMatch != null && currentMatch.involves(playerId);
    }

    public DuelMatch getMatch(UUID playerId) {
        return hasActiveMatch(playerId) ? currentMatch : null;
    }

    public DuelChallenge getIncomingChallenge(UUID targetId) {
        return challengesByTarget.get(targetId);
    }

    public boolean sendChallenge(Player challenger, Player target) {
        if (challenger.getUniqueId().equals(target.getUniqueId())) {
            challenger.sendMessage(err("You can't duel yourself."));
            return false;
        }
        if (hasActiveMatch(challenger.getUniqueId()) || hasActiveMatch(target.getUniqueId())) {
            challenger.sendMessage(err("One of you is already in a match."));
            return false;
        }
        if (challengesByTarget.containsKey(target.getUniqueId())) {
            challenger.sendMessage(err(target.getName() + " already has a pending challenge."));
            return false;
        }

        challengesByTarget.put(target.getUniqueId(), new DuelChallenge(challenger.getUniqueId(), target.getUniqueId()));
        challenger.sendMessage(ok("Challenge sent to " + target.getName() + "."));
        target.sendMessage(ok(challenger.getName() + " has challenged you to a duel!"));
        plugin.getDuelGUI().openChallengeResponse(target);
        return true;
    }

    public void acceptChallenge(Player target) {
        DuelChallenge challenge = challengesByTarget.get(target.getUniqueId());
        if (challenge == null) {
            target.sendMessage(err("You don't have a pending challenge."));
            return;
        }
        Player challenger = Bukkit.getPlayer(challenge.getChallenger());
        if (challenger == null || !challenger.isOnline()) {
            challengesByTarget.remove(target.getUniqueId());
            target.sendMessage(err("That player is no longer online."));
            return;
        }

        // The single unified reason for both players is now simply "this
        // is a normal duel challenge" - setting it always overwrites
        // anything left over from an abandoned different flow, so no
        // separate cleanup calls are needed here anymore.
        plugin.getDuelGUI().setPendingKitSelectReason(challenger.getUniqueId(), new PendingKitSelectReason.NormalDuelChallenge());
        plugin.getDuelGUI().setPendingKitSelectReason(target.getUniqueId(), new PendingKitSelectReason.NormalDuelChallenge());

        challenge.setState(DuelChallenge.State.SELECTING_KIT);
        challenger.sendMessage(ok(target.getName() + " accepted! Pick a kit in the menu - you both need to pick the same one."));
        target.sendMessage(ok("Pick a kit in the menu - you both need to pick the same one."));
        plugin.getDuelGUI().openKitSelect(challenger);
        plugin.getDuelGUI().openKitSelect(target);
    }

    public void declineChallenge(Player target) {
        DuelChallenge challenge = challengesByTarget.remove(target.getUniqueId());
        if (challenge == null) {
            target.sendMessage(err("You don't have a pending challenge."));
            return;
        }
        Player challenger = Bukkit.getPlayer(challenge.getChallenger());
        target.sendMessage(ok("Challenge declined."));
        if (challenger != null) challenger.sendMessage(err(target.getName() + " declined your challenge."));
    }

    public void selectKit(Player player, Kit kit) {
        DuelChallenge challenge = findChallengeInvolving(player.getUniqueId());
        if (challenge == null || challenge.getState() != DuelChallenge.State.SELECTING_KIT) {
            player.sendMessage(err("You don't have an active kit selection."));
            return;
        }

        boolean isChallenger = challenge.getChallenger().equals(player.getUniqueId());
        if (isChallenger) challenge.setChallengerKit(kit.getId());
        else challenge.setTargetKit(kit.getId());

        player.sendMessage(ok("Selected kit: " + kit.getName()));

        UUID otherId = challenge.theOtherPlayer(player.getUniqueId());
        Player other = otherId != null ? Bukkit.getPlayer(otherId) : null;

        if (challenge.kitsAgreed()) {
            if (other != null) other.sendMessage(ok("Both players agreed on '" + kit.getName() + "' - starting match!"));
            player.sendMessage(ok("Both players agreed on '" + kit.getName() + "' - starting match!"));
            challengesByTarget.remove(challenge.getTarget());
            queueOrStartMatch(challenge.getChallenger(), challenge.getTarget(), kit);
        } else if (other != null && challenge.getChallengerKit() != null && challenge.getTargetKit() != null) {
            // Both players have now picked, but picked different kits - tell
            // them clearly and send both back to the kit screen to try again.
            player.sendMessage(err("You both need to pick the SAME kit. Try again!"));
            other.sendMessage(err("You both need to pick the SAME kit. Try again!"));
            challenge.setChallengerKit(null);
            challenge.setTargetKit(null);
            plugin.getDuelGUI().setPendingKitSelectReason(player.getUniqueId(), new PendingKitSelectReason.NormalDuelChallenge());
            plugin.getDuelGUI().setPendingKitSelectReason(other.getUniqueId(), new PendingKitSelectReason.NormalDuelChallenge());
            plugin.getDuelGUI().openKitSelect(player);
            plugin.getDuelGUI().openKitSelect(other);
        } else if (other != null) {
            other.sendMessage(ChatColor.YELLOW + player.getName() + " picked '" + kit.getName() + "' - pick the same one to start!");
        }
    }

    /** Prepares the duel map's world to actually support a match - forces
     *  PVP on. World difficulty also resets to whatever server.properties
     *  says on every restart, so fixing it here at match start is what
     *  actually stays correct long-term. */
    private void prepareWorldForMatch(World arenaWorld) {
        if (arenaWorld == null) return;
        if (!arenaWorld.getPVP()) arenaWorld.setPVP(true);
    }

    private DuelChallenge findChallengeInvolving(UUID playerId) {
        for (DuelChallenge challenge : challengesByTarget.values()) {
            if (challenge.involves(playerId)) return challenge;
        }
        return null;
    }

    // ---------------------------------------------------------------- match lifecycle / queue

    /** Starts the match right now if the duel map is free, otherwise adds
     *  it to the back of the queue and lets both players know where they
     *  stand. */
    private void queueOrStartMatch(UUID player1Id, UUID player2Id, Kit kit) {
        if (currentMatch != null) {
            queue.enqueue(new QueueEntry(player1Id, player2Id, kit));
            Player p1 = Bukkit.getPlayer(player1Id);
            Player p2 = Bukkit.getPlayer(player2Id);
            String position = "#" + queue.size() + " in the queue";
            if (p1 != null) p1.sendMessage(ok("The duel map is busy - you're " + position + "."));
            if (p2 != null) p2.sendMessage(ok("The duel map is busy - you're " + position + "."));
            return;
        }
        startMatch(player1Id, player2Id, kit);
    }

    /** Pulls queued requests one at a time until one actually starts a
     *  match, or the queue runs dry - an explicit loop rather than
     *  startMatch calling back into this method on failure, since that
     *  mutual recursion could otherwise recurse once per stale/offline
     *  queue entry and risk a StackOverflowError on the main thread if
     *  the queue ever built up a long run of them (e.g. a burst of
     *  players queuing then disconnecting before their turn came up). */
    private void advanceQueue() {
        while (currentMatch == null) {
            QueueEntry next = queue.dequeueNext();
            if (next == null) return; // queue is empty, nothing left to try
            if (startMatch(next.player1(), next.player2(), next.kit())) return;
            // startMatch already messaged whichever player(s) were still
            // online about why it failed - loop around and try the next
            // queued entry instead of giving up entirely.
        }
    }

    /** Returns whether the match actually started. On failure, the
     *  caller (advanceQueue) is responsible for trying the next queued
     *  entry - this method never recurses into advanceQueue itself. */
    private boolean startMatch(UUID player1Id, UUID player2Id, Kit kit) {
        Player p1 = Bukkit.getPlayer(player1Id);
        Player p2 = Bukkit.getPlayer(player2Id);
        if (p1 == null || p2 == null || !p1.isOnline() || !p2.isOnline()) {
            if (p1 != null) p1.sendMessage(err("Match cancelled - the other player disconnected."));
            if (p2 != null) p2.sendMessage(err("Match cancelled - the other player disconnected."));
            return false;
        }

        Arena arena = arenaManager.getDuelArena();
        if (!arena.isReadyForDuel()) {
            p1.sendMessage(err("The duel map isn't fully set up yet - ask an admin to finish placing spawn points."));
            p2.sendMessage(err("The duel map isn't fully set up yet - ask an admin to finish placing spawn points."));
            return false;
        }
        World arenaWorld = Bukkit.getWorld(arena.getWorldName());
        if (arenaWorld == null) {
            p1.sendMessage(err("The duel map's world isn't loaded right now - ask an admin to check."));
            p2.sendMessage(err("The duel map's world isn't loaded right now - ask an admin to check."));
            return false;
        }

        prepareWorldForMatch(arenaWorld);

        PlayerStateSnapshot snap1 = PlayerStateSnapshot.capture(p1);
        PlayerStateSnapshot snap2 = PlayerStateSnapshot.capture(p2);

        DuelMatch match = new DuelMatch(p1.getUniqueId(), p2.getUniqueId(), kit, arena, snap1, snap2);
        currentMatch = match;

        equipForMatch(p1, kit, arena.getSpawnPoints().get(0));
        equipForMatch(p2, kit, arena.getSpawnPoints().get(1));

        String rank1 = playerDataManager.get(p1.getUniqueId()).getRank().getDisplayName();
        String rank2 = playerDataManager.get(p2.getUniqueId()).getRank().getDisplayName();
        String rankAnnouncement = ChatColor.translateAlternateColorCodes('&', rank1) + ChatColor.WHITE + " " + p1.getName()
                + ChatColor.GRAY + " vs "
                + ChatColor.translateAlternateColorCodes('&', rank2) + ChatColor.WHITE + " " + p2.getName();
        p1.sendMessage(rankAnnouncement);
        p2.sendMessage(rankAnnouncement);

        p1.sendMessage(ok("Duel started! You have 10 minutes before sudden death begins."));
        p2.sendMessage(ok("Duel started! You have 10 minutes before sudden death begins."));
        return true;
    }

    private void equipForMatch(Player player, Kit kit, Location spawn) {
        player.teleport(spawn);
        var inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        inv.setItemInOffHand(null);

        for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }

        inv.setHelmet(cloneOrNull(kit.getHelmet()));
        inv.setChestplate(cloneOrNull(kit.getChestplate()));
        inv.setLeggings(cloneOrNull(kit.getLeggings()));
        inv.setBoots(cloneOrNull(kit.getBoots()));
        inv.setItemInOffHand(cloneOrNull(kit.getOffhand()));
        tagAsKitItem(inv.getHelmet());
        tagAsKitItem(inv.getChestplate());
        tagAsKitItem(inv.getLeggings());
        tagAsKitItem(inv.getBoots());
        tagAsKitItem(inv.getItemInOffHand());

        List<org.bukkit.inventory.ItemStack> contents = kit.getContents();
        for (int i = 0; i < contents.size(); i++) {
            var item = contents.get(i);
            var cloned = item == null ? null : item.clone();
            tagAsKitItem(cloned);
            inv.setItem(i, cloned);
        }

        player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
    }

    private org.bukkit.inventory.ItemStack cloneOrNull(org.bukkit.inventory.ItemStack item) {
        return item == null ? null : item.clone();
    }

    /** Marks an item as coming from a duel kit, via a PersistentDataContainer
     *  flag. Kit items already can't leave the arena at all (the pre-duel
     *  inventory is fully restored the instant a match ends), so this is
     *  defense-in-depth rather than the only thing standing between kit
     *  items and being sold - it's here so any other plugin (e.g. a sell
     *  plugin) that's later updated to check for it can recognize these
     *  items as never-sellable, using the key "wsmpduels:kit_item". */
    private void tagAsKitItem(org.bukkit.inventory.ItemStack item) {
        if (item == null) return;
        var meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("wsmpduels", "kit_item"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
    }

    /** Ends a match with a clear winner - awards honor/win/loss, restores
     *  both players to their pre-match state, announces the result to
     *  chat, then pulls the next queued match (if any). For non-death
     *  endings (forfeit by disconnect, etc) where no respawn event is
     *  coming. */
    public void endMatch(DuelMatch match, UUID winnerId, String reason) {
        currentMatch = null;

        UUID loserId = match.theOtherPlayer(winnerId);
        Player winner = winnerId != null ? Bukkit.getPlayer(winnerId) : null;
        Player loser = loserId != null ? Bukkit.getPlayer(loserId) : null;

        if (winner != null) restorePlayer(winner, match);
        if (loser != null) restorePlayer(loser, match);

        if (bettingManager != null) bettingManager.resolveBets(MatchKind.DUEL, match.getPlayer1(), sideIndexOf(match, winnerId));
        if (spectatorManager != null) spectatorManager.handleMatchEnded(MatchKind.DUEL, match.getPlayer1());
        awardResultAndAnnounce(match, winnerId, loserId, reason);
        advanceQueue();
    }

    /** Ends a match where the losing player just died - stats/honor/chat
     *  happen immediately, same as endMatch, but neither player's actual
     *  restoration happens here. The winner (who didn't die) is restored
     *  right away since no respawn is coming for them; the loser (who did
     *  die) is queued for restoration on their upcoming PlayerRespawnEvent,
     *  which the listener consumes via consumePendingRestore. */
    public void endMatchByDeath(DuelMatch match, UUID winnerId, UUID loserId) {
        currentMatch = null;

        Player winner = Bukkit.getPlayer(winnerId);
        if (winner != null) restorePlayer(winner, match);

        PlayerStateSnapshot loserSnapshot = match.getSnapshot(loserId);
        if (loserSnapshot != null) pendingRespawnRestore.put(loserId, loserSnapshot);

        if (bettingManager != null) bettingManager.resolveBets(MatchKind.DUEL, match.getPlayer1(), sideIndexOf(match, winnerId));
        if (spectatorManager != null) spectatorManager.handleMatchEnded(MatchKind.DUEL, match.getPlayer1());
        awardResultAndAnnounce(match, winnerId, loserId, "won the duel");
        advanceQueue();
    }

    private String nameOf(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) return online.getName();
        String offlineName = Bukkit.getOfflinePlayer(playerId).getName();
        return offlineName != null ? offlineName : playerId.toString().substring(0, 8);
    }

    /** Voluntarily ends the caller's own active duel - counts as a
     *  forfeit and the opponent wins, same as if you'd disconnected. */
    public void endMatchVoluntarily(Player player) {
        if (currentMatch == null || !currentMatch.involves(player.getUniqueId())) {
            player.sendMessage(err("You're not in an active duel."));
            return;
        }
        DuelMatch match = currentMatch;

        UUID otherId = match.theOtherPlayer(player.getUniqueId());
        Player other = otherId != null ? Bukkit.getPlayer(otherId) : null;
        if (other != null) {
            endMatch(match, otherId, player.getName() + " forfeited");
            player.sendMessage(err("You forfeited the duel."));
        } else {
            cancelMatch(match, "left voluntarily, opponent already offline");
            player.sendMessage(ok("Duel ended."));
        }
    }

    public void cancelMatch(DuelMatch match, String reason) {
        currentMatch = null;

        Player p1 = Bukkit.getPlayer(match.getPlayer1());
        Player p2 = Bukkit.getPlayer(match.getPlayer2());
        if (p1 != null) restorePlayer(p1, match);
        if (p2 != null) restorePlayer(p2, match);

        if (bettingManager != null) bettingManager.cancelBets(MatchKind.DUEL, match.getPlayer1());
        if (spectatorManager != null) spectatorManager.handleMatchEnded(MatchKind.DUEL, match.getPlayer1());
        advanceQueue();
    }

    private void restorePlayer(Player player, DuelMatch match) {
        PlayerStateSnapshot snapshot = match.getSnapshot(player.getUniqueId());
        if (snapshot != null) snapshot.restore(player);
    }

    /** Called from onDisable, before the server saves player data during
     *  shutdown - restores whoever's actively mid-match right now back to
     *  their real inventory. Without this, a /stop or /reload while
     *  someone's mid-duel would leave their real items sitting only in an
     *  in-memory snapshot that's about to disappear when the plugin
     *  unloads; the server would then save whatever kit items they were
     *  holding as their new, permanent inventory instead - a real,
     *  confirmed data-loss risk this closes for good. */
    public void restoreAllOnShutdown() {
        if (currentMatch == null) return;
        Player p1 = Bukkit.getPlayer(currentMatch.getPlayer1());
        if (p1 != null) restorePlayer(p1, currentMatch);
        Player p2 = Bukkit.getPlayer(currentMatch.getPlayer2());
        if (p2 != null) restorePlayer(p2, currentMatch);
    }

    /** Handles a player disconnecting mid-match - the remaining player
     *  wins by forfeit. If both are somehow gone, the match is just
     *  cancelled with no honor awarded. */
    public void handleQuit(UUID playerId) {
        if (currentMatch == null || !currentMatch.involves(playerId)) return;
        DuelMatch match = currentMatch;
        UUID otherId = match.theOtherPlayer(playerId);
        Player other = otherId != null ? Bukkit.getPlayer(otherId) : null;
        if (other != null) {
            endMatch(match, otherId, "opponent disconnected");
        } else {
            cancelMatch(match, "both players disconnected");
        }
    }

    // ---------------------------------------------------------------- tick task (timer + sudden death)

    private void startTickTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Sweep expired challenges.
            challengesByTarget.entrySet().removeIf(entry -> {
                DuelChallenge challenge = entry.getValue();
                long ageSeconds = (System.currentTimeMillis() - challenge.getCreatedAt()) / 1000L;
                if (ageSeconds > challengeTimeoutSeconds) {
                    Player challenger = Bukkit.getPlayer(challenge.getChallenger());
                    Player target = Bukkit.getPlayer(challenge.getTarget());
                    if (challenger != null) challenger.sendMessage(err("Your challenge expired."));
                    if (target != null) target.sendMessage(err("A pending challenge expired."));
                    return true;
                }
                return false;
            });

            // Tick the active match (if any) for sudden death.
            if (currentMatch != null) {
                long elapsed = currentMatch.elapsedSeconds();
                Player p1 = Bukkit.getPlayer(currentMatch.getPlayer1());
                Player p2 = Bukkit.getPlayer(currentMatch.getPlayer2());

                if (elapsed >= suddenDeathStartSeconds) {
                    if (!currentMatch.isSuddenDeathActive()) {
                        currentMatch.setSuddenDeathActive(true);
                        if (p1 != null) p1.sendMessage(ChatColor.RED + "Sudden death! You'll now lose 1 HP every second.");
                        if (p2 != null) p2.sendMessage(ChatColor.RED + "Sudden death! You'll now lose 1 HP every second.");
                    }
                    if (p1 != null) damageForSuddenDeath(p1);
                    if (p2 != null) damageForSuddenDeath(p2);
                }
            }
        }, 20L, 20L);
    }

    private void damageForSuddenDeath(org.bukkit.entity.LivingEntity entity) {
        double newHealth = Math.max(0.0, entity.getHealth() - 1.0);
        entity.setHealth(newHealth); // 0 triggers a normal death event, handled by the appropriate listener
    }

    private void awardResultAndAnnounce(DuelMatch match, UUID winnerId, UUID loserId, String reason) {
        if (winnerId != null && loserId != null) {
            int winHonor = plugin.getConfig().getInt("honor.win-amount", 20);
            int lossHonor = plugin.getConfig().getInt("honor.loss-amount", 5);
            playerDataManager.get(winnerId).addHonor(winHonor);
            playerDataManager.get(winnerId).addWin();
            playerDataManager.get(loserId).addHonor(lossHonor);
            playerDataManager.get(loserId).addLoss();
            boolean saved = playerDataManager.save();

            String winnerName = nameOf(winnerId);
            String loserName = nameOf(loserId);
            Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[Duel] " + ChatColor.YELLOW
                    + winnerName + ChatColor.GRAY + " defeated " + ChatColor.YELLOW + loserName
                    + ChatColor.GRAY + " (+" + winHonor + " honor) " + ChatColor.DARK_GRAY + "- " + reason);
            discordAnnouncer.announce("⚔️ **" + winnerName + "** defeated **" + loserName
                    + "** in a duel (+" + winHonor + " honor) - " + reason);
            if (!saved) {
                // The honor/win/loss changes are still correct in memory
                // and will be written out by the next successful save (or
                // at shutdown) - only warn whichever of the two is
                // actually online right now, rather than blocking the
                // match result on a disk issue.
                String warning = err("Warning: honor changes from this duel couldn't be saved to disk right "
                        + "now (check console) - still correct for this session, but could be lost if the "
                        + "server crashes before the next successful save.");
                Player winnerOnline = Bukkit.getPlayer(winnerId);
                Player loserOnline = Bukkit.getPlayer(loserId);
                if (winnerOnline != null) winnerOnline.sendMessage(warning);
                if (loserOnline != null) loserOnline.sendMessage(warning);
            }
        }
    }

    /** Called from the respawn listener for a player whose duel just ended
     *  in their death. Returns the snapshot to restore from, or null if
     *  this player isn't awaiting one (the normal case for every other
     *  respawn on the server). */
    public PlayerStateSnapshot consumePendingRestore(UUID playerId) {
        return pendingRespawnRestore.remove(playerId);
    }

    // ---------------------------------------------------------------- helpers

    private String ok(String msg) {
        return ChatColor.GREEN + msg;
    }

    private String err(String msg) {
        return ChatColor.RED + msg;
    }
}
