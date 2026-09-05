package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The Gauntlet - a no-kit, 100-wave PvE survival mode for a solo player
 * or a co-op party of up to 4. Every run starts with the same fixed gear
 * (no armor, a stone sword) regardless of party size. Mobs get
 * tougher every wave, from plain zombies up to a Wither + Warden final
 * boss at wave 100, and difficulty scales up further with party size -
 * more players means more mobs AND tougher mobs, fixed at whatever the
 * party size was when the run started (it doesn't get easier if people
 * die or leave partway through). A stackable roguelike buff choice is
 * offered to each player individually every 5 waves cleared. Killed mobs
 * drop loot that scales with how deep into the run they died. Death and
 * victory are both announced to Discord with the wave reached.
 */
public class GauntletManager implements Listener {

    private static final NamespacedKey MOB_MARKER = new NamespacedKey("wsmpduels", "gauntlet_mob");
    private static final NamespacedKey MOB_OWNER = new NamespacedKey("wsmpduels", "gauntlet_run_id");
    private static final int FINAL_WAVE = 100;
    private static final int MAX_PARTY_SIZE = 4;

    private final DuelsPlugin plugin;
    private final ArenaManager arenaManager;
    private final PlayerDataManager playerDataManager;
    private final DiscordAnnouncer discordAnnouncer;
    private final DuelManager duelManager;
    private final WarManager warManager;
    private final WarPartyManager warPartyManager;
    private final WorldInstanceManager worldInstanceManager;
    private SpectatorManager spectatorManager; // set after construction, see DuelsPlugin.onEnable - avoids a circular constructor dependency
    private final Random random = new Random();

    private final Map<UUID, GauntletRun> activeRuns = new HashMap<>(); // keyed by player id
    private final Map<UUID, GauntletRun> runsById = new HashMap<>(); // keyed by GauntletRun.getId() - stable even as players leave
    private final Map<UUID, PlayerStateSnapshot> pendingRespawnRestore = new HashMap<>();
    /** Run IDs whose current break has already ended (wave advanced) -
     *  guards against both the "everyone picked in time" path and the
     *  3-minute timer path both trying to advance the same break. */
    private final java.util.Set<UUID> breakEndedForRun = new java.util.HashSet<>();
    /** True only for the exact duration of a single spawnEntity(...) call
     *  in spawnMob - lets the CreatureSpawnEvent listener below tell "this
     *  spawn came from our own code" apart from anything else that might
     *  spawn in the same world at the same time, without needing to
     *  identify the entity itself (which isn't tagged as ours until after
     *  spawnEntity already returns). */
    private boolean spawningGauntletMob = false;

    public GauntletManager(DuelsPlugin plugin, ArenaManager arenaManager, PlayerDataManager playerDataManager,
                           DuelManager duelManager, WarManager warManager, WarPartyManager warPartyManager,
                           WorldInstanceManager worldInstanceManager) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.playerDataManager = playerDataManager;
        this.discordAnnouncer = new DiscordAnnouncer(plugin);
        this.duelManager = duelManager;
        this.warManager = warManager;
        this.warPartyManager = warPartyManager;
        this.worldInstanceManager = worldInstanceManager;
        startHealthBarTask();
        startBreakReminderTask();
    }

    public boolean hasActiveRun(UUID playerId) {
        return activeRuns.containsKey(playerId);
    }

    public GauntletRun getRun(UUID playerId) {
        return activeRuns.get(playerId);
    }

    public GauntletRun getRunById(UUID runId) {
        return runsById.get(runId);
    }

    /** All currently-active Gauntlet runs, for the spectate browser to
     *  list - one entry per run, not per player, even for a party. */
    public java.util.Collection<GauntletRun> getAllActiveRuns() {
        return runsById.values();
    }

    public WorldInstanceManager getWorldInstanceManager() {
        return worldInstanceManager;
    }

    /** Called from onDisable, before the server saves player data during
     *  shutdown - restores every online player currently in an active
     *  Gauntlet run back to their real inventory. Same reasoning as
     *  DuelManager/WarManager's versions: without this, a /stop or
     *  /reload mid-run would leave real items sitting only in an
     *  in-memory snapshot about to disappear, and the server would save
     *  whatever kit items/loot they were holding as their new, permanent
     *  inventory instead. Also matters for shutdownAllInstances right
     *  after this - a world can only actually unload once every player
     *  is out of it, so restoring (which teleports them out) first gives
     *  that its best chance of succeeding on the very first try. */
    public void restoreAllOnShutdown() {
        for (GauntletRun run : new ArrayList<>(runsById.values())) {
            for (UUID id : new ArrayList<>(run.getPlayers())) {
                Player player = Bukkit.getPlayer(id);
                if (player == null) continue;
                PlayerStateSnapshot snapshot = run.getSnapshot(id);
                if (snapshot != null) snapshot.restore(player);
            }
        }
    }

    /** Called from onDisable so a normal /stop or /reload tears down
     *  every currently-active instance world immediately, rather than
     *  leaving that to the next startup's orphan scan. */
    public void shutdownAllInstances() {
        List<org.bukkit.World> worlds = new ArrayList<>();
        for (GauntletRun run : runsById.values()) {
            if (run.getInstanceWorld() != null) worlds.add(run.getInstanceWorld());
        }
        worldInstanceManager.destroyAllOnShutdown(worlds);
    }

    public void setSpectatorManager(SpectatorManager spectatorManager) {
        this.spectatorManager = spectatorManager;
    }

    // ---------------------------------------------------------------- starting a run

    public void startRun(Player leader) {
        if (hasActiveRun(leader.getUniqueId())) {
            leader.sendMessage(err("You're already in a Gauntlet run."));
            return;
        }
        if (duelManager.hasActiveMatch(leader.getUniqueId()) || warManager.hasActiveMatch(leader.getUniqueId())) {
            leader.sendMessage(err("You're already in a duel or war."));
            return;
        }

        WarParty party = warPartyManager.getOrCreateParty(leader);
        if (!party.getLeader().equals(leader.getUniqueId())) {
            leader.sendMessage(err("Only the party leader can start a Gauntlet run."));
            return;
        }
        if (warManager.isPartyQueued(party)) {
            leader.sendMessage(err("Your party is queued for war - leave the queue before starting a Gauntlet run."));
            return;
        }
        List<UUID> memberIds = party.getMembers();
        if (memberIds.size() > MAX_PARTY_SIZE) {
            leader.sendMessage(err("The Gauntlet supports at most " + MAX_PARTY_SIZE + " players - your party has "
                    + memberIds.size() + "."));
            return;
        }

        List<Player> members = new ArrayList<>();
        for (UUID id : memberIds) {
            Player member = Bukkit.getPlayer(id);
            if (member == null) continue;
            if (hasActiveRun(id) || duelManager.hasActiveMatch(id) || warManager.hasActiveMatch(id)) {
                leader.sendMessage(err(member.getName() + " is already in a match or run - can't start yet."));
                return;
            }
            if (spectatorManager != null && spectatorManager.isSpectating(id)) {
                // A real, confirmed gap: without this, equipStartingGear
                // below would force this player into Survival mode
                // mid-spectate - yanking them out of spectator mode and
                // dropping them into whatever hostile arena they were
                // just watching, right where mobs could actually hit
                // them.
                leader.sendMessage(err(member.getName() + " is currently spectating a match - they need to stop first."));
                return;
            }
            members.add(member);
        }
        if (members.isEmpty()) {
            leader.sendMessage(err("No online party members to start with."));
            return;
        }

        int partySize = members.size();
        List<Arena> readyArenas = arenaManager.getReadyGauntletArenas(partySize);
        if (readyArenas.isEmpty()) {
            leader.sendMessage(err("No Gauntlet arena is configured for a party of " + partySize
                    + " yet - ask an admin to add enough spawn points."));
            return;
        }
        Arena template = readyArenas.get(random.nextInt(readyArenas.size()));

        leader.sendMessage(ok("Preparing your Gauntlet instance..."));
        worldInstanceManager.createInstance(template.getWorldName(), template.getSpawnPoints(), instanceWorld -> {
            // Re-validate everyone's still actually available - the world
            // clone takes a moment, and a lot can change in that window
            // (someone could disconnect, or get pulled into something
            // else entirely).
            List<Player> stillAvailable = new ArrayList<>();
            for (Player member : members) {
                if (!member.isOnline()) continue;
                if (hasActiveRun(member.getUniqueId())) continue;
                stillAvailable.add(member);
            }
            if (stillAvailable.isEmpty()) {
                worldInstanceManager.destroyInstance(instanceWorld);
                return;
            }

            if (instanceWorld.getDifficulty() == org.bukkit.Difficulty.PEACEFUL) {
                // Same real, confirmed issue as bot wars - Peaceful blocks
                // all hostile mob spawning outright.
                instanceWorld.setDifficulty(org.bukkit.Difficulty.EASY);
            }

            Arena instanceArena = template.translatedTo(instanceWorld);
            GauntletRun run = new GauntletRun(instanceArena, partySize, instanceWorld);
            runsById.put(run.getId(), run);

            for (int i = 0; i < stillAvailable.size(); i++) {
                Player member = stillAvailable.get(i);
                PlayerStateSnapshot snapshot = PlayerStateSnapshot.capture(member);
                run.addPlayer(member.getUniqueId(), snapshot);
                activeRuns.put(member.getUniqueId(), run);
                equipStartingGear(member, instanceArena.getSpawnPoints().get(i));
            }

            for (Player member : stillAvailable) {
                member.sendMessage(ok("The Gauntlet has begun" + (partySize > 1 ? " (party of " + partySize + ")" : "")
                        + "! Survive as many waves as you can."));
                member.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "THE GAUNTLET", ChatColor.GRAY + "Wave 1", 10, 40, 10);
            }
            spawnWave(run);
        }, () -> leader.sendMessage(err("Couldn't prepare a Gauntlet instance right now - try again shortly.")));
    }

    /** Builds a throwaway (never saved to disk) copy of the template
     *  arena whose spawn points point at the freshly-cloned instance
     *  world instead of the admin's original arena world - the original
     *  Arena object itself is never touched, since it's shared,
     *  persistent configuration that every future run will read again. */
    private void equipStartingGear(Player player, Location spawn) {
        player.teleport(spawn);
        var inv = player.getInventory();
        inv.clear();
        inv.setHelmet(null);
        inv.setChestplate(null);
        inv.setLeggings(null);
        inv.setBoots(null);
        inv.setItemInMainHand(new ItemStack(Material.STONE_SWORD));
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setGameMode(GameMode.SURVIVAL);
    }

    // ---------------------------------------------------------------- wave spawning

    private void spawnWave(GauntletRun run) {
        if (run.isEmpty()) return;
        int wave = run.getWave();
        int partySize = run.getOriginalPartySize();
        Location center = run.getArena().getSpawnPoints().get(0);

        for (UUID id : run.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Wave " + wave + " incoming!");
            p.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "WAVE " + wave, "", 5, 30, 5);
        }

        if (wave == FINAL_WAVE) {
            // "Ending with Withers and Wardens" - scale the number of
            // boss pairs with party size, so a bigger group faces a
            // genuinely bigger final fight rather than the same two bosses
            // just having more health.
            int pairs = Math.max(1, (int) Math.ceil(partySize / 2.0));
            for (int i = 0; i < pairs; i++) {
                spawnMob(run, center, EntityType.WITHER, wave, partySize);
                spawnMob(run, center, EntityType.WARDEN, wave, partySize);
            }
            broadcastToRun(run, ChatColor.DARK_RED + "" + ChatColor.BOLD + "THE FINAL BOSS: "
                    + pairs + "x Wither and Warden!");
            return;
        }
        if (wave % 10 == 0) {
            // Mini-boss checkpoint - a single heavily scaled mob of the
            // toughest type unlocked so far, instead of a swarm. Party
            // size scales its stats further rather than its count, so it
            // stays a single dramatic fight even for a full party.
            EntityType[] pool = poolForWave(wave);
            EntityType bossType = pool[pool.length - 1];
            spawnMob(run, center, bossType, wave + 15, partySize);
            broadcastToRun(run, ChatColor.RED + "A mini-boss has appeared!");
            return;
        }

        // More players means more mobs per wave, not just tougher ones -
        // scaled by a diminishing factor so a 4-player wave isn't a flat
        // 4x mob count, while still clearly harder than solo.
        double partyCountScale = 1.0 + (partySize - 1) * 0.6;
        int mobCount = Math.min((int) Math.round((3 + wave / 3) * partyCountScale), 30);
        EntityType[] pool = poolForWave(wave);
        for (int i = 0; i < mobCount; i++) {
            EntityType type = pool[random.nextInt(pool.length)];
            Location spawnAt = center.clone().add(random.nextInt(9) - 4, 0, random.nextInt(9) - 4);
            spawnMob(run, spawnAt, type, wave, partySize);
        }
    }

    /** The cumulative pool of mob types unlocked by this wave - later
     *  waves keep earlier types mixed in for variety, weighted toward
     *  the newest (toughest) additions by nature of random.nextInt over
     *  the whole pool. */
    private EntityType[] poolForWave(int wave) {
        List<EntityType> pool = new ArrayList<>();
        pool.add(EntityType.ZOMBIE);
        if (wave >= 10) pool.add(EntityType.SKELETON);
        if (wave >= 20) pool.add(EntityType.SPIDER);
        if (wave >= 30) pool.add(EntityType.CREEPER);
        if (wave >= 40) pool.add(EntityType.WITCH);
        if (wave >= 50) pool.add(EntityType.VINDICATOR);
        if (wave >= 60) pool.add(EntityType.RAVAGER);
        if (wave >= 70) pool.add(EntityType.ENDERMAN);
        if (wave >= 80) pool.add(EntityType.PIGLIN_BRUTE);
        if (wave >= 90) pool.add(EntityType.WITHER_SKELETON);
        if (wave >= 95) pool.add(EntityType.BLAZE);
        return pool.toArray(new EntityType[0]);
    }

    private void spawnMob(GauntletRun run, Location loc, EntityType type, int scalingWave, int partySize) {
        // A real, confirmed issue: WorldGuard's "mob-spawning" region flag
        // blocks CreatureSpawnEvent regardless of whether the spawn came
        // from a plugin rather than natural generation - other plugins
        // that need their own mobs to spawn inside a protected region have
        // had to build the exact same workaround. If the instance world's
        // arena carries that flag (e.g. copied over from the template, or
        // just a server-wide default), every single Gauntlet spawn would
        // silently get cancelled - the entity reference still comes back
        // non-null, but it's immediately invalid/removed, which is
        // exactly what "no monsters ever show up" looks like from here.
        // This flag plus the onCreatureSpawn listener below un-cancels
        // specifically our own spawns, and only while one is actually in
        // progress on this exact call.
        spawningGauntletMob = true;
        var entity = loc.getWorld().spawnEntity(loc, type);
        spawningGauntletMob = false;
        if (!(entity instanceof LivingEntity mob) || !mob.isValid()) return;

        mob.getPersistentDataContainer().set(MOB_MARKER, PersistentDataType.BYTE, (byte) 1);
        mob.getPersistentDataContainer().set(MOB_OWNER, PersistentDataType.STRING, run.getId().toString());

        // Party size adds ON TOP OF wave-based scaling, fixed at
        // whatever the party size was at run start - each extra player
        // beyond the first adds another 35%/25% to health/damage.
        double partyHealthScale = 1.0 + (partySize - 1) * 0.35;
        double partyDamageScale = 1.0 + (partySize - 1) * 0.25;
        double healthMultiplier = (1.0 + (scalingWave * 0.04)) * partyHealthScale;
        double damageMultiplier = (1.0 + (scalingWave * 0.03)) * partyDamageScale;

        var maxHealthAttr = mob.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            maxHealthAttr.setBaseValue(maxHealthAttr.getBaseValue() * healthMultiplier);
            mob.setHealth(maxHealthAttr.getValue());
        }
        var damageAttr = mob.getAttribute(Attribute.ATTACK_DAMAGE);
        if (damageAttr != null) {
            damageAttr.setBaseValue(damageAttr.getBaseValue() * damageMultiplier);
        }

        updateHealthDisplay(mob, niceName(type));
        run.getAliveMobs().add(mob.getUniqueId());
    }

    private String niceName(EntityType type) {
        String raw = type.name().toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String word : raw.split(" ")) {
            if (!word.isEmpty()) sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- mob death / loot / wave clear

    /** Mobs spawned by the Gauntlet should only ever fight players, never
     *  each other - vanilla mob AI mostly already avoids this, but with a
     *  dozen different hostile types packed into one arena, indirect
     *  damage (a Skeleton's stray arrow, a Blaze fireball, a Creeper
     *  explosion) can otherwise still catch a neighboring mob. Checks the
     *  actual source of the damage - the shooter of a projectile, not the
     *  projectile itself - so this covers ranged attacks too, not just
     *  melee. */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMobVsMobDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!isGauntletMob(event.getEntity())) return;

        org.bukkit.entity.Entity source = event.getDamager();
        if (source instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof LivingEntity shooter) {
            source = shooter;
        }
        if (isGauntletMob(source)) {
            event.setCancelled(true);
        }
    }

    private boolean isGauntletMob(org.bukkit.entity.Entity entity) {
        return entity.getPersistentDataContainer().has(MOB_MARKER, PersistentDataType.BYTE);
    }

    /** Creepers spawned by the Gauntlet still explode and can still hurt
     *  players, but never damage the arena itself - block destruction
     *  from a Gauntlet creeper's explosion is cleared, same idea as
     *  keeping any other mode's arena intact between uses. */
    @EventHandler(ignoreCancelled = true)
    public void onCreeperExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        if (!isGauntletMob(event.getEntity())) return;
        event.blockList().clear();
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!entity.getPersistentDataContainer().has(MOB_MARKER, PersistentDataType.BYTE)) return;

        String runIdStr = entity.getPersistentDataContainer().get(MOB_OWNER, PersistentDataType.STRING);
        if (runIdStr == null) return;
        GauntletRun run = runsById.get(UUID.fromString(runIdStr));
        if (run == null) return;

        run.getAliveMobs().remove(entity.getUniqueId());

        event.getDrops().clear();
        event.setDroppedExp(0);
        int bestLuckyStacks = 0;
        for (UUID id : run.getPlayers()) {
            bestLuckyStacks = Math.max(bestLuckyStacks, run.getBuffStacks(id, GauntletBuff.LUCKY));
        }
        ItemStack loot = rollLoot(run.getWave(), bestLuckyStacks);
        if (loot != null) {
            entity.getWorld().dropItemNaturally(entity.getLocation(), loot);
        }
        ItemStack food = rollFood(run.getWave());
        if (food != null) {
            entity.getWorld().dropItemNaturally(entity.getLocation(), food);
        }

        if (run.getAliveMobs().isEmpty()) {
            onWaveCleared(run);
        }
    }

    private void onWaveCleared(GauntletRun run) {
        if (run.isEmpty()) { cleanupRun(run); return; }

        if (run.getWave() >= FINAL_WAVE) {
            handleVictory(run);
            return;
        }

        broadcastToRun(run, ok("Wave " + run.getWave() + " cleared!"));

        if (run.getWave() % 5 == 0) {
            breakEndedForRun.remove(run.getId());
            run.resetReadyForNextWave();
            run.setBreakActive(true);
            for (UUID id : new ArrayList<>(run.getPlayers())) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                run.setAwaitingBuffPick(id, true);
                plugin.getGauntletGUI().openBuffPick(p, run);
            }
            broadcastToRun(run, ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD
                    + "Break time! " + ChatColor.YELLOW + "Pick a buff, then visit the merchant - 3 minutes.");
            broadcastToRun(run, ChatColor.GRAY + "Type " + ChatColor.WHITE + "/duels ready" + ChatColor.GRAY
                    + " any time to ready up without opening the merchant menu.");
            Bukkit.getScheduler().runTaskLater(plugin, () -> forceEndBreak(run), 3600L);
            return;
        }

        openItemPickForAll(run);
        advanceWave(run);
    }

    /** Called once a specific player has picked their buff - opens the
     *  merchant for them right after (the whole point of the break is
     *  selling drops and buying gear before the next wave). Does NOT end
     *  the break by itself anymore - that was a real, confirmed bug where
     *  finishing the buff pick (which takes a few seconds) immediately
     *  ended the whole break, collapsing the intended 3 minutes down to
     *  almost nothing, especially for a solo player. The break now only
     *  ends via the 3-minute timer (forceEndBreak) or every player
     *  explicitly opting to skip early (see markReadyForNextWave). */
    public void continueAfterBuffPick(GauntletRun run, UUID playerId) {
        run.setAwaitingBuffPick(playerId, false);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) plugin.getGauntletMerchantGUI().openMain(player, run);
    }

    /** A player explicitly opting to skip the rest of the break early,
     *  from a button in the merchant - only actually ends the break once
     *  EVERY still-active player has done the same; otherwise it just
     *  records their choice and waits. */
    public void markReadyForNextWave(Player player, GauntletRun run) {
        run.markReadyForNextWave(player.getUniqueId());
        if (!run.allPlayersReadyToSkipBreak()) {
            broadcastToRun(run, ChatColor.GRAY + player.getName() + " is ready for the next wave.");
            return;
        }
        endBreak(run);
    }

    /** Same ready-up as the merchant's button, reachable from a plain
     *  command instead - lets a player skip the break early without ever
     *  having to open the merchant menu at all. */
    public void readyUpViaCommand(Player player) {
        GauntletRun run = activeRuns.get(player.getUniqueId());
        if (run == null) {
            player.sendMessage(err("You're not in a Gauntlet run."));
            return;
        }
        if (!run.isBreakActive()) {
            player.sendMessage(err("There's no break going on right now."));
            return;
        }
        if (run.isReadyForNextWave(player.getUniqueId())) {
            player.sendMessage(err("You're already marked ready."));
            return;
        }
        markReadyForNextWave(player, run);
        player.sendMessage(ok("You're ready for the next wave."));
    }

    /** Runs 3 minutes after a break starts, regardless of whether
     *  everyone's picked a buff yet - closes it out so a slow or AFK
     *  player can never stall the whole party's break indefinitely.
     *  Anyone who never picked gets a sensible default rather than
     *  missing the buff entirely. */
    private void forceEndBreak(GauntletRun run) {
        if (runsById.get(run.getId()) != run || run.isEmpty()) return;
        for (UUID id : new ArrayList<>(run.getPlayers())) {
            Player p = Bukkit.getPlayer(id);
            if (run.isAwaitingBuffPick(id)) {
                if (p != null) applyBuff(p, run, GauntletBuff.VITALITY);
                run.setAwaitingBuffPick(id, false);
            }
            if (p != null) p.closeInventory();
        }
        endBreak(run);
    }

    /** The single place a break actually ends and the wave advances -
     *  guarded so whichever of continueAfterBuffPick (everyone picked in
     *  time) or forceEndBreak (3-minute timer ran out first) gets here
     *  first is the one that counts; the other is a no-op, preventing the
     *  wave from ever advancing twice for the same break. */
    private void endBreak(GauntletRun run) {
        if (breakEndedForRun.contains(run.getId())) return;
        breakEndedForRun.add(run.getId());
        run.setBreakActive(false);
        broadcastToRun(run, ChatColor.YELLOW + "Break's over!");
        advanceWave(run);
    }

    /** Called right after a wave clears (both the normal case, where the
     *  item-pick GUI is up, and after a break ends) - the delay doubles
     *  as the item-pick window itself for the normal case, so bumping it
     *  to a full 15 seconds is what actually gives players real time to
     *  choose rather than a rushed 3-second breather. Force-closes
     *  anyone still looking at the item-pick screen right before mobs
     *  start spawning again, so nobody's caught browsing a menu mid-wave. */
    private void advanceWave(GauntletRun run) {
        run.setWave(run.getWave() + 1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (runsById.get(run.getId()) == run && !run.isEmpty()) {
                for (UUID id : run.getPlayers()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.closeInventory();
                }
                spawnWave(run);
            }
        }, 300L); // 15 second break before the next wave
    }

    public void applyBuff(Player player, GauntletRun run, GauntletBuff buff) {
        UUID id = player.getUniqueId();
        run.addBuffStack(id, buff);
        int stacks = run.getBuffStacks(id, buff);

        switch (buff) {
            case VITALITY -> {
                var maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealthAttr != null) {
                    maxHealthAttr.setBaseValue(maxHealthAttr.getBaseValue() + 4);
                    player.setHealth(Math.min(maxHealthAttr.getValue(), player.getHealth() + 4));
                }
            }
            case STRENGTH -> player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE,
                    Math.min(stacks - 1, 4), true, false));
            case SWIFTNESS -> player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE,
                    Math.min(stacks - 1, 4), true, false));
            case REGENERATION -> player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE,
                    Math.min(stacks - 1, 2), true, false));
            case RESILIENCE -> player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE,
                    Math.min(stacks - 1, 2), true, false));
            case LUCKY, SECOND_WIND -> { /* consulted directly in loot roll / damage handling, no potion effect */ }
        }
        player.sendMessage(ok("Gained " + ChatColor.translateAlternateColorCodes('&', buff.getDisplayName())
                + ChatColor.GREEN + " (stack " + stacks + ")!"));
    }

    /** Loot bucketed by wave depth - later waves roll from a strictly
     *  better pool. LUCKY stacks add extra rolls rather than changing the
     *  odds of any single roll, so it compounds cleanly. Uses whichever
     *  still-active player has the most LUCKY stacks, so one lucky
     *  teammate benefits the whole party's drops.
     *
     *  Every single tier, including the very first, includes at least one
     *  weapon and a full set of armor pieces alongside raw materials -
     *  gear has real durability and wears down, so a run that never
     *  reaches the higher wave brackets (which is most runs) still needs
     *  a genuine chance at replacement gear the whole way through, not
     *  just raw materials to sell. The base drop chance itself never
     *  gets harder as waves climb - it's the same flat chance at every
     *  tier, only the pool of what it can roll gets better. */
    private ItemStack rollLoot(int wave, int luckyStacks) {
        if (random.nextDouble() > 0.6 + luckyStacks * 0.1) return null; // not every kill drops something

        Material[] pool = lootPoolForWave(wave);
        int rolls = 1 + luckyStacks;
        Material chosen = pool[random.nextInt(pool.length)];
        int amount = chosen == Material.IRON_NUGGET || chosen == Material.ROTTEN_FLESH || chosen == Material.ARROW
                ? Math.min(rolls * (1 + random.nextInt(3)), 64) : 1;
        return new ItemStack(chosen, amount);
    }

    /** The shared tier pool both a single kill's loot roll and the
     *  end-of-round reward chest draw from, so they're always describing
     *  the exact same wave brackets rather than two copies that could
     *  drift apart. */
    /** Package-private rather than private so GauntletGUI's item-pick
     *  screen can draw from the exact same tier pool a kill's loot roll
     *  uses, rather than needing its own separate copy. */
    Material[] lootPoolForWave(int wave) {
        if (wave < 20) {
            return new Material[]{Material.IRON_SWORD, Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE,
                    Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS, Material.IRON_NUGGET,
                    Material.ROTTEN_FLESH, Material.ARROW, Material.LEATHER};
        } else if (wave < 40) {
            return new Material[]{Material.IRON_SWORD, Material.IRON_HELMET, Material.IRON_CHESTPLATE,
                    Material.IRON_LEGGINGS, Material.IRON_BOOTS, Material.IRON_INGOT,
                    Material.ROTTEN_FLESH, Material.ARROW};
        } else if (wave < 60) {
            return new Material[]{Material.IRON_SWORD, Material.IRON_HELMET, Material.IRON_CHESTPLATE,
                    Material.IRON_LEGGINGS, Material.IRON_BOOTS, Material.GOLD_INGOT, Material.DIAMOND};
        } else if (wave < 80) {
            return new Material[]{Material.DIAMOND_SWORD, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE,
                    Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS, Material.GOLD_INGOT, Material.DIAMOND};
        } else if (wave < 95) {
            return new Material[]{Material.DIAMOND_SWORD, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE,
                    Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS, Material.NETHERITE_SCRAP, Material.DIAMOND};
        } else if (wave < FINAL_WAVE) {
            return new Material[]{Material.NETHERITE_SWORD, Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                    Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS, Material.NETHERITE_SCRAP, Material.DIAMOND_SWORD};
        } else {
            return new Material[]{Material.NETHERITE_SWORD, Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                    Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS, Material.NETHERITE_INGOT};
        }
    }

    /** A guaranteed bundle of rewards after every single wave, on top of
     *  whatever individual mobs happened to drop - spawns a real chest
     *  near the party so clearing a round always feels like it paid off
     *  even on an unlucky wave with few kill-drops. Placed at the same
     *  reference point mobs spawn around, offset slightly so it doesn't
     *  land exactly on top of anyone. Left behind rather than cleaned up -
     *  this is an ephemeral instance world that gets deleted entirely once
     *  the run ends, so there's nothing to tidy up afterward. */
    private void openItemPickForAll(GauntletRun run) {
        for (UUID id : run.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) plugin.getGauntletGUI().openItemPick(p, run);
        }
    }

    /** A separate, more reliable drop from the gear/loot roll above -
     *  this is specifically about keeping a player's hunger bar fed
     *  through a long run, not about rewarding progress, so it rolls
     *  independently and fairly often. Better food shows up at higher
     *  waves, same bracket logic as loot, plus a rare chance at a golden
     *  apple late in a run for emergency healing on top of feeding. */
    private ItemStack rollFood(int wave) {
        if (random.nextDouble() > 0.28) return null; // not every kill feeds you - a bit more rare than before

        if (wave >= 60 && random.nextDouble() < 0.15) {
            return new ItemStack(Material.GOLDEN_APPLE, 1);
        }

        Material food;
        if (wave < 20) {
            food = random.nextBoolean() ? Material.BREAD : Material.COOKED_PORKCHOP;
        } else if (wave < 60) {
            food = random.nextBoolean() ? Material.COOKED_BEEF : Material.COOKED_CHICKEN;
        } else {
            food = random.nextBoolean() ? Material.GOLDEN_CARROT : Material.COOKED_BEEF;
        }
        return new ItemStack(food, 1);
    }

    // ---------------------------------------------------------------- death / victory / leave

    /** Consumes a SECOND_WIND stack, if the player has one, to survive
     *  what would otherwise be a killing blow - caps the damage so their
     *  health lands at exactly 1 instead of 0 or below. Has to happen
     *  here, before the damage is actually applied, since by the time
     *  PlayerDeathEvent fires the player is already dead and there's no
     *  reliable way back from that. In a party run, a fatal hit with no
     *  SECOND_WIND available goes down instead of dying outright - see
     *  downPlayer. Downed players are fully invulnerable, since the
     *  revival window is meant to be a guaranteed 10 seconds, not a race
     *  against further damage. */
    @EventHandler
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        GauntletRun run = activeRuns.get(player.getUniqueId());
        if (run == null) return;
        UUID id = player.getUniqueId();

        if (run.isDowned(id)) {
            event.setCancelled(true);
            return;
        }

        double remainingHealth = player.getHealth() - event.getFinalDamage();
        if (remainingHealth > 0) return; // not fatal, nothing to intercept

        if (run.getBuffStacks(id, GauntletBuff.SECOND_WIND) > 0) {
            run.consumeBuffStack(id, GauntletBuff.SECOND_WIND);
            event.setDamage(Math.max(0, player.getHealth() - 1));
            player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Second Wind! "
                    + ChatColor.YELLOW + "You survived a killing blow (" + run.getBuffStacks(id, GauntletBuff.SECOND_WIND)
                    + " stack(s) left).");
            return;
        }

        if (run.getOriginalPartySize() > 1) {
            event.setDamage(Math.max(0, player.getHealth() - 1));
            downPlayer(player, run);
        }
        // Solo run with no Second Wind left - let the damage through;
        // PlayerDeathEvent below handles it normally, same as always.
    }

    /** Puts a party member into a 10-second "downed" state instead of
     *  letting them actually die - only ever used in a multiplayer run,
     *  since a solo player has no one to revive them anyway. They're
     *  fully invulnerable and effectively helpless (blinded, nearly
     *  frozen, too weak to fight) for the duration; a teammate can
     *  right-click them at any point during that window to revive them
     *  early, and if nobody does, finalizeDowned removes them from the
     *  run exactly like a real death would have. */
    private void downPlayer(Player player, GauntletRun run) {
        UUID id = player.getUniqueId();
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 220, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 220, 8, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 220, 5, false, false));

        var task = Bukkit.getScheduler().runTaskLater(plugin, () -> finalizeDowned(player, run), 200L); // 10 seconds
        run.markDowned(id, task);

        player.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "You're downed! "
                + ChatColor.YELLOW + "A teammate has 10 seconds to revive you - right-click you.");
        for (UUID teammateId : run.getPlayers()) {
            if (teammateId.equals(id)) continue;
            Player teammate = Bukkit.getPlayer(teammateId);
            if (teammate != null) {
                teammate.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + player.getName() + " is downed! "
                        + ChatColor.YELLOW + "Right-click them within 10 seconds to revive!");
            }
        }
    }

    /** Runs 10 seconds after a player goes down, if nobody revived them
     *  first - removes them from the run exactly like a real death
     *  (Discord/chat announcement, restored to their pre-run state), the
     *  one difference being they're still technically alive (never
     *  actually died), so the restore happens immediately rather than
     *  waiting for a respawn event. */
    private void finalizeDowned(Player player, GauntletRun run) {
        UUID id = player.getUniqueId();
        if (!run.isDowned(id)) return; // already revived, nothing to do
        run.clearDowned(id);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());

        announceGauntletDeath(player, run);
        PlayerStateSnapshot snapshot = run.getSnapshot(id);
        if (snapshot != null) snapshot.restore(player);
        removePlayerFromRun(id, run);
    }

    /** Called when a teammate right-clicks a downed player in time -
     *  cancels their pending removal, clears the incapacitating effects,
     *  and gives them back partial health to rejoin the fight. */
    public void revivePlayer(Player reviver, Player downed, GauntletRun run) {
        UUID id = downed.getUniqueId();
        if (!run.isDowned(id)) return;
        run.clearDowned(id);
        for (PotionEffect effect : new ArrayList<>(downed.getActivePotionEffects())) downed.removePotionEffect(effect.getType());
        var maxHealthAttr = downed.getAttribute(Attribute.MAX_HEALTH);
        downed.setHealth(maxHealthAttr != null ? maxHealthAttr.getValue() * 0.5 : 10.0);

        downed.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "You've been revived by " + reviver.getName() + "!");
        reviver.sendMessage(ChatColor.GREEN + "You revived " + downed.getName() + "!");
    }

    @EventHandler
    public void onReviveAttempt(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player downed)) return;
        Player reviver = event.getPlayer();
        GauntletRun run = activeRuns.get(reviver.getUniqueId());
        if (run == null || !run.isDowned(downed.getUniqueId())) return;
        event.setCancelled(true);
        revivePlayer(reviver, downed, run);
    }

    /** Shared between an actual vanilla death (solo runs, or a party
     *  member who somehow dies without going through the downed window)
     *  and a downed player's revival window running out - just the
     *  announcement, since the two paths differ in how the player's
     *  state actually gets restored (immediately if still alive, or
     *  queued for their respawn event if truly dead). */
    private void announceGauntletDeath(Player player, GauntletRun run) {
        int waveDied = run.getWave();
        discordAnnouncer.announce("**" + player.getName() + "** died in **The Gauntlet** at wave **" + waveDied + "**.");
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Gauntlet] " + ChatColor.GRAY
                + player.getName() + " fell at wave " + waveDied + ".");
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        GauntletRun run = activeRuns.get(player.getUniqueId());
        if (run == null) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(false);
        event.setDeathMessage(null);

        run.clearDowned(player.getUniqueId()); // defensive - shouldn't normally be reachable while downed since downed players are invulnerable
        announceGauntletDeath(player, run);
        pendingRespawnRestore.put(player.getUniqueId(), run.getSnapshot(player.getUniqueId()));
        removePlayerFromRun(player.getUniqueId(), run);
    }

    private void handleVictory(GauntletRun run) {
        List<String> names = new ArrayList<>();
        for (UUID id : new ArrayList<>(run.getPlayers())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            names.add(player.getName());
            player.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "VICTORY!",
                    ChatColor.YELLOW + "You beat The Gauntlet!", 10, 60, 20);
            PlayerStateSnapshot snapshot = run.getSnapshot(id);
            if (snapshot != null) snapshot.restore(player);
        }

        String who = String.join(", ", names);
        discordAnnouncer.announce("**" + who + "** defeated the final boss and beat **The Gauntlet**!");
        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[Gauntlet] " + ChatColor.YELLOW
                + who + " has conquered The Gauntlet, defeating the final boss!");

        cleanupRun(run);
    }

    public void endRunVoluntarily(Player player) {
        GauntletRun run = activeRuns.get(player.getUniqueId());
        if (run == null) {
            player.sendMessage(err("You're not in a Gauntlet run."));
            return;
        }
        PlayerStateSnapshot snapshot = run.getSnapshot(player.getUniqueId());
        if (snapshot != null) snapshot.restore(player);
        player.sendMessage(err("You left The Gauntlet at wave " + run.getWave() + "."));
        removePlayerFromRun(player.getUniqueId(), run);
    }

    /** Removes one player from an in-progress run - the run and its wave
     *  keep going for whoever's left. Only fully tears the run down once
     *  every player is gone. */
    private void removePlayerFromRun(UUID playerId, GauntletRun run) {
        run.removePlayer(playerId);
        activeRuns.remove(playerId);
        if (run.isEmpty()) {
            cleanupRun(run);
        }
    }

    /** Full teardown - despawns every mob still alive from this run and
     *  drops it from tracking entirely. Only called once every player in
     *  the run has left one way or another (death, leave, victory), so
     *  it's the one place a run's mobs are ever cleaned up - nothing else
     *  needs to remember to do this. */
    private void cleanupRun(GauntletRun run) {
        for (UUID mobId : new ArrayList<>(run.getAliveMobs())) {
            var entity = Bukkit.getEntity(mobId);
            if (entity instanceof LivingEntity living && living.isValid()) living.remove();
        }
        runsById.remove(run.getId());
        breakEndedForRun.remove(run.getId());
        for (UUID id : new ArrayList<>(run.getPlayers())) {
            activeRuns.remove(id);
        }
        if (spectatorManager != null) spectatorManager.handleMatchEnded(MatchKind.GAUNTLET, run.getId());
        // Every player is already restored/teleported out by this point
        // (every caller of cleanupRun does that first), so the instance
        // world is safe to tear down now.
        worldInstanceManager.destroyInstance(run.getInstanceWorld());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        PlayerStateSnapshot snapshot = pendingRespawnRestore.remove(player.getUniqueId());
        if (snapshot == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> snapshot.restore(player));
    }

    /** Un-cancels a spawn WorldGuard (or any other plugin) already denied,
     *  but only while spawnMob is actually in the middle of its own
     *  spawnEntity(...) call - see spawningGauntletMob for why. Must run
     *  at HIGHEST priority so it executes after whatever plugin cancelled
     *  it in the first place, and ignoreCancelled is deliberately left
     *  false, since intercepting an already-cancelled event is the whole
     *  point here. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (spawningGauntletMob) event.setCancelled(false);
    }

    /** A working hypothesis, not yet a confirmed fix the way the mob-
     *  spawning one above is: if a WorldGuard "chest-access"-style deny
     *  flag on the template arena is now also being copied onto every
     *  instance (the same mechanism that was confirmed blocking mob
     *  spawns), it could just as easily be silently blocking a player
     *  from actually seeing into a reward chest they open, even though
     *  the items genuinely got placed inside it. This bypasses exactly
     *  that for any player currently in an active Gauntlet run
     *  right-clicking a chest - low-risk even if this isn't the real
     *  cause, since it only ever loosens something for players already
     *  mid-run. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onChestInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        var block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof org.bukkit.block.Chest)) return;
        if (!hasActiveRun(event.getPlayer().getUniqueId())) return;
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.ALLOW);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        GauntletRun run = activeRuns.get(id);
        if (run == null) return;

        // A real, confirmed bug: this used to just drop tracking on
        // disconnect without ever restoring the player's real inventory -
        // whatever kit items/gear they were holding at the exact moment
        // they disconnected would then get saved as their new inventory,
        // and their actual pre-run items would be gone permanently. The
        // Player object is still genuinely valid/online at this precise
        // point (mid-PlayerQuitEvent), so this is the only chance to
        // restore them before that happens.
        run.clearDowned(id); // cancel any pending downed-timeout task, so it can never later fire against this now-offline player
        PlayerStateSnapshot snapshot = run.getSnapshot(id);
        if (snapshot != null) snapshot.restore(player);
        removePlayerFromRun(id, run);
    }

    private void broadcastToRun(GauntletRun run, String message) {
        for (UUID id : run.getPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(message);
        }
    }

    // ---------------------------------------------------------------- health bars

    /** An on-screen reminder for anyone currently on a break who hasn't
     *  readied up yet - re-sent on a cycle rather than only once, since
     *  an action bar message fades on its own after a few seconds and
     *  the one-time chat broadcast when the break starts is easy to miss
     *  scrolling past. Stops nagging a player the moment they've actually
     *  readied up. */
    private void startBreakReminderTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (GauntletRun run : runsById.values()) {
                if (!run.isBreakActive()) continue;
                for (UUID id : run.getPlayers()) {
                    if (run.isReadyForNextWave(id)) continue;
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) {
                        p.sendActionBar(ChatColor.GRAY + "Type " + ChatColor.WHITE + "/duels ready"
                                + ChatColor.GRAY + " to start the next wave.");
                    }
                }
            }
        }, 40L, 40L);
    }

    private void startHealthBarTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (GauntletRun run : runsById.values()) {
                for (UUID mobId : run.getAliveMobs()) {
                    var entity = Bukkit.getEntity(mobId);
                    if (entity instanceof LivingEntity living && living.isValid()) {
                        updateHealthDisplay(living, niceName(living.getType()));
                    }
                }
            }
        }, 10L, 10L);
    }

    /** Same colored health-bar-in-the-name technique already used for 1v1
     *  bot duels, generalized to any LivingEntity type rather than just
     *  Zombie, since the Gauntlet's mob roster spans a dozen different
     *  types. */
    private void updateHealthDisplay(LivingEntity entity, String baseName) {
        var maxHealthAttr = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr == null) return;
        double maxHealth = maxHealthAttr.getValue();
        double health = Math.max(0, entity.getHealth());
        double fraction = maxHealth > 0 ? health / maxHealth : 0;

        int totalSegments = 10;
        int filledSegments = Math.max(0, Math.min(totalSegments, (int) Math.round(fraction * totalSegments)));

        ChatColor barColor = fraction > 0.5 ? ChatColor.GREEN : fraction > 0.25 ? ChatColor.YELLOW : ChatColor.RED;

        StringBuilder bar = new StringBuilder();
        bar.append(barColor);
        for (int i = 0; i < filledSegments; i++) bar.append('|');
        bar.append(ChatColor.DARK_GRAY);
        for (int i = filledSegments; i < totalSegments; i++) bar.append('|');

        entity.setCustomName(ChatColor.WHITE + baseName + " " + bar + ChatColor.RESET + " "
                + barColor + Math.round(health) + "/" + Math.round(maxHealth));
        entity.setCustomNameVisible(true);
    }

    private String ok(String msg) {
        return ChatColor.GREEN + msg;
    }

    private String err(String msg) {
        return ChatColor.RED + msg;
    }
}
