package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DuelManager {

    private static final long CHALLENGE_TIMEOUT_SECONDS = 60;
    /** After this long in a match, sudden death begins (1 HP/sec drain
     *  until someone dies), per the request. */
    private static final long SUDDEN_DEATH_START_SECONDS = 10 * 60;

    private final DuelsPlugin plugin;
    private final ArenaManager arenaManager;
    private final PlayerDataManager playerDataManager;
    private final DiscordAnnouncer discordAnnouncer;
    private final BotManager botManager;
    private final WorldInstanceManager worldInstanceManager;
    private BettingManager bettingManager; // set after construction, see DuelsPlugin.onEnable - avoids a circular constructor dependency
    private SpectatorManager spectatorManager; // same pattern

    /** Keyed by the TARGET's UUID - a player can only have one incoming
     *  challenge pending at a time. */
    private final Map<UUID, DuelChallenge> challengesByTarget = new HashMap<>();
    private final Map<UUID, DuelMatch> activeMatches = new HashMap<>();
    /** Reverse lookup: which match (if any) a given player is currently in,
     *  so damage/quit listeners can find their match in O(1). */
    private final Map<UUID, DuelMatch> matchByPlayer = new HashMap<>();
    /** Players whose match just ended via death - their PlayerDeathEvent has
     *  already been handled (stats/honor/announcement), but restoring their
     *  inventory and teleporting them back must wait until PlayerRespawnEvent,
     *  since doing it during the death event gets overwritten by Bukkit's own
     *  respawn teleport a moment later. */
    private final Map<UUID, PlayerStateSnapshot> pendingRespawnRestore = new HashMap<>();

    public DuelManager(DuelsPlugin plugin, ArenaManager arenaManager, PlayerDataManager playerDataManager,
                        BotManager botManager, WorldInstanceManager worldInstanceManager) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.playerDataManager = playerDataManager;
        this.discordAnnouncer = new DiscordAnnouncer(plugin);
        this.botManager = botManager;
        this.worldInstanceManager = worldInstanceManager;
        startTickTask();
    }

    public BotManager getBotManager() {
        return botManager;
    }

    public void setBettingManager(BettingManager bettingManager) {
        this.bettingManager = bettingManager;
    }

    public void setSpectatorManager(SpectatorManager spectatorManager) {
        this.spectatorManager = spectatorManager;
    }

    /** All currently-active duels, for the spectate/bet browser to list. */
    public List<DuelMatch> getAllActiveMatches() {
        return new ArrayList<>(activeMatches.values());
    }

    public DuelMatch getActiveMatchById(UUID matchId) {
        return activeMatches.get(matchId);
    }

    /** 0 if playerId is player1, 1 if player2, -1 if neither - used to
     *  translate a winner's UUID into the side index bets are keyed by. */
    public int sideIndexOf(DuelMatch match, UUID playerId) {
        if (playerId == null) return -1;
        if (playerId.equals(match.getPlayer1())) return 0;
        if (playerId.equals(match.getPlayer2())) return 1;
        return -1;
    }

    // ---------------------------------------------------------------- challenge flow

    public boolean hasActiveMatch(UUID playerId) {
        return matchByPlayer.containsKey(playerId);
    }

    public DuelMatch getMatch(UUID playerId) {
        return matchByPlayer.get(playerId);
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
            startMatch(challenge, kit);
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

    /** Prepares a duel arena's world to actually support a match - forces
     *  PVP on, and additionally forces the world off Peaceful difficulty,
     *  since Bukkit hard-refuses to spawn any hostile mob (Zombie
     *  included) while a world is Peaceful, which silently produces zero
     *  bots for a 1v1 bot duel. World difficulty also resets to whatever
     *  server.properties says on every restart, so fixing it here at
     *  match start is what actually stays correct long-term. */
    private void prepareWorldForMatch(org.bukkit.World arenaWorld) {
        if (arenaWorld == null) return;
        if (!arenaWorld.getPVP()) arenaWorld.setPVP(true);
        if (arenaWorld.getDifficulty() == org.bukkit.Difficulty.PEACEFUL) {
            arenaWorld.setDifficulty(org.bukkit.Difficulty.EASY);
        }
    }

    private DuelChallenge findChallengeInvolving(UUID playerId) {
        for (DuelChallenge challenge : challengesByTarget.values()) {
            if (challenge.involves(playerId)) return challenge;
        }
        return null;
    }

    // ---------------------------------------------------------------- match lifecycle

    private void startMatch(DuelChallenge challenge, Kit kit) {
        Player p1 = Bukkit.getPlayer(challenge.getChallenger());
        Player p2 = Bukkit.getPlayer(challenge.getTarget());
        if (p1 == null || p2 == null || !p1.isOnline() || !p2.isOnline()) {
            if (p1 != null) p1.sendMessage(err("Match cancelled - the other player disconnected."));
            if (p2 != null) p2.sendMessage(err("Match cancelled - the other player disconnected."));
            return;
        }

        List<Arena> readyArenas = arenaManager.getReadyArenas(Arena.Type.DUEL);
        if (readyArenas.isEmpty()) {
            p1.sendMessage(err("No duel arenas are configured yet - ask an admin to set one up."));
            p2.sendMessage(err("No duel arenas are configured yet - ask an admin to set one up."));
            return;
        }
        Arena template = readyArenas.get((int) (Math.random() * readyArenas.size()));

        p1.sendMessage(ok("Preparing your duel arena..."));
        p2.sendMessage(ok("Preparing your duel arena..."));
        worldInstanceManager.createInstance(template.getWorldName(), template.getSpawnPoints(), instanceWorld -> {
            Player onlineP1 = Bukkit.getPlayer(p1.getUniqueId());
            Player onlineP2 = Bukkit.getPlayer(p2.getUniqueId());
            if (onlineP1 == null || onlineP2 == null || !onlineP1.isOnline() || !onlineP2.isOnline()) {
                if (onlineP1 != null) onlineP1.sendMessage(err("Match cancelled - the other player disconnected."));
                if (onlineP2 != null) onlineP2.sendMessage(err("Match cancelled - the other player disconnected."));
                worldInstanceManager.destroyInstance(instanceWorld);
                return;
            }

            prepareWorldForMatch(instanceWorld);
            Arena arena = template.translatedTo(instanceWorld);

            PlayerStateSnapshot snap1 = PlayerStateSnapshot.capture(onlineP1);
            PlayerStateSnapshot snap2 = PlayerStateSnapshot.capture(onlineP2);

            DuelMatch match = new DuelMatch(onlineP1.getUniqueId(), onlineP2.getUniqueId(), kit, arena, instanceWorld, snap1, snap2);
            activeMatches.put(onlineP1.getUniqueId(), match);
            matchByPlayer.put(onlineP1.getUniqueId(), match);
            matchByPlayer.put(onlineP2.getUniqueId(), match);

            equipForMatch(onlineP1, kit, arena.getSpawnPoints().get(0));
            equipForMatch(onlineP2, kit, arena.getSpawnPoints().get(1));

            String rank1 = playerDataManager.get(onlineP1.getUniqueId()).getRank().getDisplayName();
            String rank2 = playerDataManager.get(onlineP2.getUniqueId()).getRank().getDisplayName();
            String rankAnnouncement = ChatColor.translateAlternateColorCodes('&', rank1) + ChatColor.WHITE + " " + onlineP1.getName()
                    + ChatColor.GRAY + " vs "
                    + ChatColor.translateAlternateColorCodes('&', rank2) + ChatColor.WHITE + " " + onlineP2.getName();
            onlineP1.sendMessage(rankAnnouncement);
            onlineP2.sendMessage(rankAnnouncement);

            onlineP1.sendMessage(ok("Duel started! You have 10 minutes before sudden death begins."));
            onlineP2.sendMessage(ok("Duel started! You have 10 minutes before sudden death begins."));
        }, () -> {
            p1.sendMessage(err("Couldn't prepare a duel arena right now - try again shortly."));
            p2.sendMessage(err("Couldn't prepare a duel arena right now - try again shortly."));
        });
    }

    /** Starts a solo practice duel against a bot - no challenge/accept/kit
     *  agreement needed since there's no second real player to negotiate
     *  with. The player's own kit choice equips both sides. No honor is
     *  ever awarded here (DuelMatch's bot constructor sets
     *  bothPlayersReal to false), matching the "honor only for real
     *  player fights" rule. */
    public void startBotDuel(Player player, BotDifficulty difficulty, Kit kit) {
        if (hasActiveMatch(player.getUniqueId())) {
            player.sendMessage(err("You're already in a match."));
            return;
        }

        List<Arena> readyArenas = arenaManager.getReadyArenas(Arena.Type.DUEL);
        if (readyArenas.isEmpty()) {
            player.sendMessage(err("No duel arenas are configured yet - ask an admin to set one up."));
            return;
        }
        Arena template = readyArenas.get((int) (Math.random() * readyArenas.size()));

        player.sendMessage(ok("Preparing your duel arena..."));
        worldInstanceManager.createInstance(template.getWorldName(), template.getSpawnPoints(), instanceWorld -> {
            Player online = Bukkit.getPlayer(player.getUniqueId());
            if (online == null || !online.isOnline() || hasActiveMatch(online.getUniqueId())) {
                worldInstanceManager.destroyInstance(instanceWorld);
                return;
            }

            prepareWorldForMatch(instanceWorld);
            Arena arena = template.translatedTo(instanceWorld);

            PlayerStateSnapshot snapshot = PlayerStateSnapshot.capture(online);
            equipForMatch(online, kit, arena.getSpawnPoints().get(0));

            var bot = botManager.spawnBot(arena.getSpawnPoints().get(1), kit, difficulty, online);

            DuelMatch match = new DuelMatch(online.getUniqueId(), bot.getUniqueId(), kit, arena, instanceWorld, snapshot);
            activeMatches.put(match.getPlayer1(), match);
            matchByPlayer.put(online.getUniqueId(), match);
            matchByPlayer.put(bot.getUniqueId(), match);

            online.sendMessage(ok("Bot duel started (" + ChatColor.translateAlternateColorCodes('&', difficulty.getDisplayName())
                    + ChatColor.GREEN + ")! You have 10 minutes before sudden death begins."));
        }, () -> player.sendMessage(err("Couldn't prepare a duel arena right now - try again shortly.")));
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

    /** Ends a match with a clear winner - awards honor/win/loss if both
     *  sides were real players, restores both players to their pre-match
     *  state, and announces the result to chat. For non-death endings
     *  (forfeit by disconnect, etc) where no respawn event is coming. */
    public void endMatch(DuelMatch match, UUID winnerId, String reason) {
        activeMatches.remove(match.getPlayer1());
        matchByPlayer.remove(match.getPlayer1());
        matchByPlayer.remove(match.getPlayer2());
        if (match.isPlayer2Bot()) botManager.despawn(match.getPlayer2());
        worldInstanceManager.destroyInstance(match.getInstanceWorld());

        UUID loserId = match.theOtherPlayer(winnerId);
        Player winner = winnerId != null ? Bukkit.getPlayer(winnerId) : null;
        Player loser = loserId != null ? Bukkit.getPlayer(loserId) : null;

        if (winner != null) restorePlayer(winner, match);
        if (loser != null) restorePlayer(loser, match);

        if (bettingManager != null) bettingManager.resolveBets(MatchKind.DUEL, match.getPlayer1(), sideIndexOf(match, winnerId));
        if (spectatorManager != null) spectatorManager.handleMatchEnded(MatchKind.DUEL, match.getPlayer1());
        awardResultAndAnnounce(match, winnerId, loserId, reason);
    }

    /** Ends a match where the losing player just died - stats/honor/chat
     *  happen immediately, same as endMatch, but neither player's actual
     *  restoration happens here. The winner (who didn't die) is restored
     *  right away since no respawn is coming for them; the loser (who did
     *  die) is queued for restoration on their upcoming PlayerRespawnEvent,
     *  which the listener consumes via consumePendingRestore. */
    public void endMatchByDeath(DuelMatch match, UUID winnerId, UUID loserId) {
        activeMatches.remove(match.getPlayer1());
        matchByPlayer.remove(match.getPlayer1());
        matchByPlayer.remove(match.getPlayer2());
        if (match.isPlayer2Bot()) botManager.despawn(match.getPlayer2());
        worldInstanceManager.destroyInstance(match.getInstanceWorld());

        Player winner = Bukkit.getPlayer(winnerId);
        if (winner != null) restorePlayer(winner, match);

        PlayerStateSnapshot loserSnapshot = match.getSnapshot(loserId);
        if (loserSnapshot != null) pendingRespawnRestore.put(loserId, loserSnapshot);

        if (bettingManager != null) bettingManager.resolveBets(MatchKind.DUEL, match.getPlayer1(), sideIndexOf(match, winnerId));
        if (spectatorManager != null) spectatorManager.handleMatchEnded(MatchKind.DUEL, match.getPlayer1());
        awardResultAndAnnounce(match, winnerId, loserId, "won the duel");
    }

    /** Ends a bot duel where the BOT just died - the player is restored
     *  immediately (no respawn wait needed since they're still alive),
     *  and the bot entity is despawned. No honor is awarded, matching the
     *  real-players-only rule (DuelMatch already reflects this via
     *  bothPlayersReal=false for bot matches). */
    public void endMatchByBotDeath(DuelMatch match) {
        activeMatches.remove(match.getPlayer1());
        matchByPlayer.remove(match.getPlayer1());
        matchByPlayer.remove(match.getPlayer2());
        botManager.despawn(match.getPlayer2());
        worldInstanceManager.destroyInstance(match.getInstanceWorld());

        Player player = Bukkit.getPlayer(match.getPlayer1());
        if (player != null) {
            restorePlayer(player, match);
            player.sendMessage(ok("You defeated the bot!"));
        }
        if (bettingManager != null) bettingManager.resolveBets(MatchKind.DUEL, match.getPlayer1(), 0);
        if (spectatorManager != null) spectatorManager.handleMatchEnded(MatchKind.DUEL, match.getPlayer1());
    }

    /** Called from the respawn listener for a player whose duel just ended
     *  in their death. Returns the snapshot to restore from, or null if
     *  this player isn't awaiting one (the normal case for every other
     *  respawn on the server). */
    public PlayerStateSnapshot consumePendingRestore(UUID playerId) {
        return pendingRespawnRestore.remove(playerId);
    }

    private void awardResultAndAnnounce(DuelMatch match, UUID winnerId, UUID loserId, String reason) {
        if (match.isBothPlayersReal() && winnerId != null && loserId != null) {
            int winHonor = plugin.getConfig().getInt("honor.win-amount", 20);
            int lossHonor = plugin.getConfig().getInt("honor.loss-amount", 5);
            playerDataManager.get(winnerId).addHonor(winHonor);
            playerDataManager.get(winnerId).addWin();
            playerDataManager.get(loserId).addHonor(lossHonor);
            playerDataManager.get(loserId).addLoss();
            playerDataManager.save();

            String winnerName = nameOf(winnerId);
            String loserName = nameOf(loserId);
            Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[Duel] " + ChatColor.YELLOW
                    + winnerName + ChatColor.GRAY + " defeated " + ChatColor.YELLOW + loserName
                    + ChatColor.GRAY + " (+" + winHonor + " honor) " + ChatColor.DARK_GRAY + "- " + reason);
            discordAnnouncer.announce("⚔️ **" + winnerName + "** defeated **" + loserName
                    + "** in a duel (+" + winHonor + " honor) - " + reason);
        }
    }

    private String nameOf(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) return online.getName();
        String offlineName = Bukkit.getOfflinePlayer(playerId).getName();
        return offlineName != null ? offlineName : playerId.toString().substring(0, 8);
    }

    /** Ends a match with no winner (e.g. both players disconnected) - no
     *  honor is awarded either way. */
    /** Voluntarily ends the caller's own active duel - a bot duel just
     *  ends cleanly with no result (there's nothing to forfeit against a
     *  bot, and bot duels never award honor anyway), while a real 1v1
     *  against another player counts as a forfeit and the opponent wins,
     *  same as if you'd disconnected. */
    public void endMatchVoluntarily(Player player) {
        DuelMatch match = matchByPlayer.get(player.getUniqueId());
        if (match == null) {
            player.sendMessage(err("You're not in an active duel."));
            return;
        }

        if (match.isPlayer2Bot()) {
            cancelMatch(match, "left voluntarily");
            player.sendMessage(ok("You left the bot duel."));
            return;
        }

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
        activeMatches.remove(match.getPlayer1());
        matchByPlayer.remove(match.getPlayer1());
        matchByPlayer.remove(match.getPlayer2());
        if (match.isPlayer2Bot()) botManager.despawn(match.getPlayer2());
        worldInstanceManager.destroyInstance(match.getInstanceWorld());

        Player p1 = Bukkit.getPlayer(match.getPlayer1());
        Player p2 = Bukkit.getPlayer(match.getPlayer2());
        if (p1 != null) restorePlayer(p1, match);
        if (p2 != null) restorePlayer(p2, match);

        if (bettingManager != null) bettingManager.cancelBets(MatchKind.DUEL, match.getPlayer1());
        if (spectatorManager != null) spectatorManager.handleMatchEnded(MatchKind.DUEL, match.getPlayer1());
    }

    private void restorePlayer(Player player, DuelMatch match) {
        PlayerStateSnapshot snapshot = match.getSnapshot(player.getUniqueId());
        if (snapshot != null) snapshot.restore(player);
    }

    /** Called from onDisable, before the server saves player data during
     *  shutdown - restores every online player who's actively mid-match
     *  right now back to their real inventory. Without this, a /stop or
     *  /reload while anyone is mid-duel would leave their real items
     *  sitting only in an in-memory snapshot that's about to disappear
     *  when the plugin unloads; the server would then save whatever kit
     *  items they were holding as their new, permanent inventory instead
     *  - a real, confirmed data-loss risk this closes for good. */
    public void restoreAllOnShutdown() {
        for (DuelMatch match : new ArrayList<>(activeMatches.values())) {
            Player p1 = Bukkit.getPlayer(match.getPlayer1());
            if (p1 != null) {
                PlayerStateSnapshot snapshot = match.getSnapshot(match.getPlayer1());
                if (snapshot != null) snapshot.restore(p1);
            }
            if (!match.isPlayer2Bot()) {
                Player p2 = Bukkit.getPlayer(match.getPlayer2());
                if (p2 != null) {
                    PlayerStateSnapshot snapshot = match.getSnapshot(match.getPlayer2());
                    if (snapshot != null) snapshot.restore(p2);
                }
            }
        }
    }

    /** Handles a player disconnecting mid-match - the remaining player
     *  wins by forfeit. If both are somehow gone, the match is just
     *  cancelled with no honor awarded. */
    public void handleQuit(UUID playerId) {
        DuelMatch match = matchByPlayer.get(playerId);
        if (match == null) return;
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
                if (ageSeconds > CHALLENGE_TIMEOUT_SECONDS) {
                    Player challenger = Bukkit.getPlayer(challenge.getChallenger());
                    Player target = Bukkit.getPlayer(challenge.getTarget());
                    if (challenger != null) challenger.sendMessage(err("Your challenge expired."));
                    if (target != null) target.sendMessage(err("A pending challenge expired."));
                    return true;
                }
                return false;
            });

            // Tick active matches for sudden death.
            for (DuelMatch match : new ArrayList<>(activeMatches.values())) {
                long elapsed = match.elapsedSeconds();
                Player p1 = Bukkit.getPlayer(match.getPlayer1());
                Player p2 = match.isPlayer2Bot() ? null : Bukkit.getPlayer(match.getPlayer2());
                org.bukkit.entity.LivingEntity bot = match.isPlayer2Bot()
                        ? asLivingEntity(match.getPlayer2()) : null;

                if (elapsed >= SUDDEN_DEATH_START_SECONDS) {
                    if (!match.isSuddenDeathActive()) {
                        match.setSuddenDeathActive(true);
                        if (p1 != null) p1.sendMessage(ChatColor.RED + "Sudden death! You'll now lose 1 HP every second.");
                        if (p2 != null) p2.sendMessage(ChatColor.RED + "Sudden death! You'll now lose 1 HP every second.");
                    }
                    if (p1 != null) damageForSuddenDeath(p1);
                    if (p2 != null) damageForSuddenDeath(p2);
                    if (bot != null) damageForSuddenDeath(bot);
                }
            }
        }, 20L, 20L);
    }

    private org.bukkit.entity.LivingEntity asLivingEntity(UUID entityId) {
        var entity = Bukkit.getEntity(entityId);
        return entity instanceof org.bukkit.entity.LivingEntity living ? living : null;
    }

    private void damageForSuddenDeath(org.bukkit.entity.LivingEntity entity) {
        double newHealth = Math.max(0.0, entity.getHealth() - 1.0);
        entity.setHealth(newHealth); // 0 triggers a normal death event, handled by the appropriate listener
    }

    // ---------------------------------------------------------------- helpers

    private String ok(String msg) {
        return ChatColor.GREEN + msg;
    }

    private String err(String msg) {
        return ChatColor.RED + msg;
    }
}
