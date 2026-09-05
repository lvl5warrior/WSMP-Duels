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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * War-mode combat and lifecycle handling for real player vs. player
 * matches. War bots were removed (bot play is 1v1-only now, see
 * DuelListener) - this file no longer has any bot-specific logic.
 */
public class WarListener implements Listener {

    private final DuelsPlugin plugin;
    private final WarManager warManager;

    public WarListener(DuelsPlugin plugin, WarManager warManager) {
        this.plugin = plugin;
        this.warManager = warManager;
    }

    /** Resolves the actual attacking player, whether the damage came
     *  directly from them (melee) or from a projectile (arrow, trident,
     *  etc) they fired. See DuelListener's identical helper for the same
     *  reasoning. */
    private Player resolveAttacker(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    /** Guarantees damage between two players on OPPOSING teams of the same
     *  active war always goes through, regardless of any external
     *  scoreboard-team friendly-fire setting - same reasoning as the 1v1
     *  version of this fix, and now covers ranged damage too. Same-team
     *  damage is left completely alone here (neither forced on nor off)
     *  since this phase doesn't have a specific "no friendly fire within
     *  your own war team" requirement - it simply follows whatever the
     *  server's normal PVP rules already do for two players on the same
     *  side. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWarDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) return;

        WarMatch match = warManager.getMatch(victim.getUniqueId());
        if (match == null) return;
        if (!match.involves(attacker.getUniqueId())) return;

        int victimTeam = match.teamIndexOf(victim.getUniqueId());
        int attackerTeam = match.teamIndexOf(attacker.getUniqueId());
        if (victimTeam != attackerTeam) {
            event.setCancelled(false);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        WarMatch match = warManager.getMatch(dead.getUniqueId());
        if (match == null) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(false);
        event.setDeathMessage(null);

        if (match.getCtfState() != null) {
            warManager.getCtfManager().handleCarrierLost(match, dead.getUniqueId(), dead.getLocation());
        }

        if (warManager.isRespawnMode(match.getMode())) {
            // Objective modes: death doesn't end participation, just
            // queues them to come back at their team's spawn on their
            // next actual respawn (can't equip them here - Bukkit's own
            // respawn teleport a moment later would undo it).
            warManager.queueCtfRespawn(match, dead.getUniqueId());
            return;
        }

        match.markEliminated(dead.getUniqueId());
        warManager.checkForWinner(match, dead.getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        WarMatch ctfMatch = warManager.consumePendingCtfRespawn(player.getUniqueId());
        if (ctfMatch != null) {
            Bukkit.getScheduler().runTask(plugin, () -> warManager.respawnInMatch(ctfMatch, player));
            return;
        }

        PlayerStateSnapshot snapshot = warManager.consumePendingRestore(player.getUniqueId());
        if (snapshot == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> snapshot.restore(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        warManager.handleQuit(event.getPlayer().getUniqueId());
    }

    /** Stops health from regenerating too fast mid-war, for the same
     *  reason as the identical fix in DuelListener - equipForMatch sets
     *  hunger/saturation to full so the hunger bar looks right at match
     *  start, which also triggers vanilla's normal hunger-based natural
     *  regen. Anything else - eating, potions, a golden apple in a kit -
     *  still heals normally. */
    @EventHandler(ignoreCancelled = true)
    public void onRegainHealth(org.bukkit.event.entity.EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        var reason = event.getRegainReason();
        if (reason != org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.SATIATED
                && reason != org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.REGEN) return;
        if (warManager.getMatch(player.getUniqueId()) == null) return;
        event.setCancelled(true);
    }
}
