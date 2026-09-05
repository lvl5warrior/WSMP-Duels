package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class DuelListener implements Listener {

    private final DuelsPlugin plugin;
    private final DuelManager duelManager;

    public DuelListener(DuelsPlugin plugin, DuelManager duelManager) {
        this.plugin = plugin;
        this.duelManager = duelManager;
    }

    /** Resolves the actual attacking player, whether the damage came
     *  directly from them (melee) or from a projectile (arrow, trident,
     *  etc) they fired - a projectile's "damager" in the event is the
     *  projectile entity itself, not the player, so this has to trace
     *  back through Projectile.getShooter() to find them. */
    private Player resolveAttacker(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    /** Guarantees the two participants of an active duel can always damage
     *  each other, even if they're on the same WSMP-Teams (or any other
     *  scoreboard-based) team with friendly fire normally disabled -
     *  runs at HIGHEST priority specifically so it has the final say after
     *  any other plugin's own friendly-fire check has already run. Covers
     *  both melee and ranged (arrows, tridents) damage, and works whether
     *  the victim is a real player or a duel bot - the bot's own attacks
     *  against the player aren't specifically forced through here, since
     *  ordinary mob-vs-player damage isn't the kind of thing PVP/team
     *  settings normally block in the first place. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDuelDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) return;

        DuelMatch match = duelManager.getMatch(victim.getUniqueId());
        if (match == null) return;
        if (!attacker.getUniqueId().equals(match.theOtherPlayer(victim.getUniqueId()))) return;

        event.setCancelled(false);
    }

    /** Awards a bot a real critical-hit damage bonus when it lands a hit
     *  while mid-jump-attack - the same underlying idea as a player critical
     *  hit (bonus damage while airborne), applied manually here since mobs
     *  don't get this automatically the way players do. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBotCriticalHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof org.bukkit.entity.Zombie bot)) return;

        DuelMatch match = duelManager.getMatch(victim.getUniqueId());
        if (match == null || !match.isPlayer2Bot() || !match.getPlayer2().equals(bot.getUniqueId())) return;
        if (!duelManager.getBotManager().isMidJump(bot.getUniqueId())) return;

        event.setDamage(event.getDamage() * 1.5);
        victim.getWorld().spawnParticle(org.bukkit.Particle.CRIT, victim.getLocation().add(0, 1, 0), 12);
        victim.getWorld().playSound(victim.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
    }

    /** A bot with a shield in its kit has a real chance to block incoming
     *  hits, reducing the damage substantially - matches how shield
     *  blocking works for a real player. Runs at HIGH priority, before
     *  the HIGHEST-priority "force this through" handler, so the damage
     *  is already reduced by the time that runs. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBotShieldBlock(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Zombie bot)) return;
        DuelMatch match = duelManager.getMatch(bot.getUniqueId());
        if (match == null || !match.isPlayer2Bot() || !match.getPlayer2().equals(bot.getUniqueId())) return;

        BotDifficulty difficulty = duelManager.getBotManager().getDifficulty(bot.getUniqueId());
        if (difficulty == null) return;
        if (!duelManager.getBotManager().tryBlockWithShield(bot.getUniqueId(), difficulty)) return;

        event.setDamage(event.getDamage() * 0.2);
        bot.getWorld().playSound(bot.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
    }

    /** A death during an active duel is never a "real" death - no item
     *  drops, no exp loss, no death screen consequences that matter, since
     *  the player gets fully restored to their pre-duel state moments
     *  later. PlayerDeathEvent itself can't be cancelled, so instead this
     *  strips its normal consequences and records the win/loss; the actual
     *  teleport-and-restore happens on respawn (see onRespawn below), since
     *  doing it here would just get overwritten by Bukkit's own respawn
     *  handling right after. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        DuelMatch match = duelManager.getMatch(dead.getUniqueId());
        if (match == null) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(false);
        event.setDeathMessage(null);

        var winnerId = match.theOtherPlayer(dead.getUniqueId());
        duelManager.endMatchByDeath(match, winnerId, dead.getUniqueId());
    }

    /** A duel bot dying is a SEPARATE event from a real player dying -
     *  PlayerDeathEvent never fires for a mob, so this is the only way to
     *  detect it. Only acts on entities that are actually tracked as an
     *  active duel bot, so this never interferes with normal mob deaths
     *  elsewhere on the server. */
    @EventHandler
    public void onBotDeath(EntityDeathEvent event) {
        DuelMatch match = duelManager.getMatch(event.getEntity().getUniqueId());
        if (match == null || !match.isPlayer2Bot()) return;
        if (!match.getPlayer2().equals(event.getEntity().getUniqueId())) return;

        event.getDrops().clear();
        event.setDroppedExp(0);

        duelManager.endMatchByBotDeath(match);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        PlayerStateSnapshot snapshot = duelManager.consumePendingRestore(player.getUniqueId());
        if (snapshot == null) return;

        // Setting the respawn location here stops Bukkit's own bed/world-spawn
        // logic from placing the player somewhere else; the fuller restore
        // (inventory, health, potion effects) is scheduled a tick later since
        // some of those don't reliably apply mid-respawn.
        Bukkit.getScheduler().runTask(plugin, () -> snapshot.restore(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        duelManager.handleQuit(event.getPlayer().getUniqueId());
    }

    /** Blocks selling while in an active duel. This can only intercept
     *  command-based selling (/sell and its common aliases) - if your sell
     *  system works entirely through a GUI or a physical shop, this won't
     *  see or block that, since it's not something happening on this
     *  plugin's side. */
    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!duelManager.hasActiveMatch(player.getUniqueId())) return;

        String command = event.getMessage().toLowerCase();
        if (command.startsWith("/sell") || command.startsWith("/quicksell") || command.startsWith("/sellall")) {
            event.setCancelled(true);
            player.sendMessage(org.bukkit.ChatColor.RED + "You can't sell items while in a duel.");
        }
    }

    /** Stops health from regenerating too fast mid-duel. Kits equip
     *  players at full hunger/saturation (equipForMatch sets both to 20)
     *  so the hunger bar looks right at match start, but that also
     *  triggers vanilla's normal hunger-based natural regen (RegainReason
     *  SATIATED) - a real, confirmed bug where damage barely mattered
     *  because health kept climbing back on its own. REGEN (the
     *  Peaceful-difficulty-only variant) is blocked too for the same
     *  reason, just in case. Anything else - eating food, potions, a
     *  golden apple in a kit - still heals normally. */
    @EventHandler(ignoreCancelled = true)
    public void onRegainHealth(org.bukkit.event.entity.EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        var reason = event.getRegainReason();
        if (reason != org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.SATIATED
                && reason != org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.REGEN) return;
        if (!duelManager.hasActiveMatch(player.getUniqueId())) return;
        event.setCancelled(true);
    }
}
