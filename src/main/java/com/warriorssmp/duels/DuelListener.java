package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
     *  both melee and ranged (arrows, tridents) damage. */
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
