package com.warriorssmp.duels;

import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spawns and manages duel bots. Bots are real Zombie entities under the
 * hood, tagged with a PersistentDataContainer marker so other listeners
 * can recognize them - this gets proven vanilla chase-and-attack AI for
 * free rather than needing custom pathfinding from scratch. Layered on
 * top of that built-in AI: jump attacks with real critical-hit damage
 * bonuses when they connect, difficulty-scaled self-healing when low on
 * health, real shield blocking for kits that include one, and a genuine
 * tactical decision loop - a bot with a bow in its kit switches to it and
 * shoots from range, switches back to melee up close, and disengages to
 * create distance when badly hurt instead of just tanking hits. None of
 * this makes a bot LOOK like a player (that would need Citizens or
 * similar - see BotManager's earlier design conversation), but it acts
 * with the same kind of situational decision-making a real opponent
 * would, rather than a fixed mob behavior pattern.
 */
public class BotManager {

    private static final org.bukkit.NamespacedKey BOT_MARKER =
            new org.bukkit.NamespacedKey("wsmpduels", "is_duel_bot");

    private final DuelsPlugin plugin;
    private final Map<UUID, BotDifficulty> activeBots = new HashMap<>();
    /** Tracks bots currently mid-jump, so the critical-hit check can tell
     *  a genuine jump-attack apart from just standing on uneven terrain. */
    private final java.util.Set<UUID> midJump = new java.util.HashSet<>();
    private final Map<UUID, Long> lastHealAt = new HashMap<>();
    private final Map<UUID, ItemStack> meleeWeapons = new HashMap<>();
    private final Map<UUID, ItemStack> bows = new HashMap<>();
    private final java.util.Set<UUID> hasShield = new java.util.HashSet<>();
    private final java.util.Set<UUID> currentlyWieldingBow = new java.util.HashSet<>();
    private final Map<UUID, Long> lastShotAt = new HashMap<>();
    private final java.util.Set<UUID> retreating = new java.util.HashSet<>();
    private final java.util.Random random = new java.util.Random();
    /** The plain "{Difficulty} Bot" name, kept separately from what's
     *  actually displayed above the bot's head - the displayed name also
     *  has a live health bar appended, rebuilt every AI tick, and keeping
     *  the original around means that rebuild never has to parse a
     *  previous bar back out of the name to recover it. */
    private final Map<UUID, String> baseNames = new HashMap<>();

    public BotManager(DuelsPlugin plugin) {
        this.plugin = plugin;
        startAiTask();
    }

    public boolean isBot(UUID entityId) {
        return activeBots.containsKey(entityId);
    }

    public BotDifficulty getDifficulty(UUID entityId) {
        return activeBots.get(entityId);
    }

    /** Spawns a bot at the given location, equips it with the kit's armor
     *  and main-hand weapon (kit inventory items beyond that aren't usable
     *  by a mob, so only equipment slots are applied), scales its combat
     *  attributes for the chosen difficulty, and sets it to target the
     *  given player immediately so the fight starts without delay. */
    public Zombie spawnBot(Location location, Kit kit, BotDifficulty difficulty, Player opponent) {
        return spawnBot(location, kit, difficulty, opponent, null);
    }

    /** Same as the 4-arg overload, but for war-mode bots that need to wear
     *  their team's color - any wool in the kit gets recolored the same
     *  way it does for a real player on that team (see WarManager's
     *  identical logic for real players). Pass null for a 1v1 bot with no
     *  team color. The target is a LivingEntity rather than specifically a
     *  Player so a bot filling out a team can target another bot when
     *  there's no real player on the enemy side to aim at. */
    public Zombie spawnBot(Location location, Kit kit, BotDifficulty difficulty, LivingEntity opponent, TeamColor teamColor) {
        Zombie bot = location.getWorld().spawn(location, Zombie.class);
        bot.setBaby(false);
        bot.setCustomNameVisible(true);
        bot.setRemoveWhenFarAway(false);
        bot.setPersistent(true);
        bot.setShouldBurnInDay(false); // a bot dying to sunlight isn't part of the intended fight
        bot.getPersistentDataContainer().set(BOT_MARKER, PersistentDataType.BYTE, (byte) 1);

        applyAttributes(bot, difficulty);
        equipBot(bot, kit, teamColor);

        bot.setTarget(opponent);
        activeBots.put(bot.getUniqueId(), difficulty);
        baseNames.put(bot.getUniqueId(), difficulty.getDisplayName() + " Bot");
        updateHealthDisplay(bot);
        return bot;
    }

    private void applyAttributes(Zombie bot, BotDifficulty difficulty) {
        var maxHealthAttr = bot.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            double baseHealth = maxHealthAttr.getBaseValue();
            maxHealthAttr.setBaseValue(baseHealth * difficulty.getHealthMultiplier());
        }
        bot.setHealth(bot.getAttribute(Attribute.MAX_HEALTH).getValue());

        var damageAttr = bot.getAttribute(Attribute.ATTACK_DAMAGE);
        if (damageAttr != null) {
            damageAttr.setBaseValue(damageAttr.getBaseValue() * difficulty.getDamageMultiplier());
        }

        var speedAttr = bot.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.setBaseValue(speedAttr.getBaseValue() * difficulty.getSpeedMultiplier());
        }
    }

    /** Rebuilds the name shown above a bot's head to include a live
     *  health bar, colored green/yellow/red by remaining health fraction,
     *  plus the exact numbers. Called at spawn and every AI tick so it
     *  always reflects current health, however it changed - damage taken,
     *  the bot's own self-heal, anything. */
    private void updateHealthDisplay(Zombie bot) {
        var maxHealthAttr = bot.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr == null) return;
        double maxHealth = maxHealthAttr.getValue();
        double health = Math.max(0, bot.getHealth());
        double fraction = maxHealth > 0 ? health / maxHealth : 0;

        int totalSegments = 10;
        int filledSegments = Math.max(0, Math.min(totalSegments, (int) Math.round(fraction * totalSegments)));

        org.bukkit.ChatColor barColor = fraction > 0.5 ? org.bukkit.ChatColor.GREEN
                : fraction > 0.25 ? org.bukkit.ChatColor.YELLOW : org.bukkit.ChatColor.RED;

        StringBuilder bar = new StringBuilder();
        bar.append(barColor);
        for (int i = 0; i < filledSegments; i++) bar.append('|');
        bar.append(org.bukkit.ChatColor.DARK_GRAY);
        for (int i = filledSegments; i < totalSegments; i++) bar.append('|');

        String baseName = baseNames.getOrDefault(bot.getUniqueId(), "Bot");
        String fullName = org.bukkit.ChatColor.translateAlternateColorCodes('&', baseName)
                + org.bukkit.ChatColor.RESET + " " + bar + org.bukkit.ChatColor.RESET + " "
                + barColor + Math.round(health) + "/" + Math.round(maxHealth);
        bot.setCustomName(fullName);
    }

    private void equipBot(Zombie bot, Kit kit, TeamColor teamColor) {
        EntityEquipment equipment = bot.getEquipment();
        if (equipment == null) return;

        equipment.setHelmet(recolorWool(cloneOrNull(kit.getHelmet()), teamColor));
        equipment.setChestplate(recolorWool(cloneOrNull(kit.getChestplate()), teamColor));
        equipment.setLeggings(recolorWool(cloneOrNull(kit.getLeggings()), teamColor));
        equipment.setBoots(recolorWool(cloneOrNull(kit.getBoots()), teamColor));

        // Track the kit's melee weapon and bow (if any) separately - the AI
        // task actively swaps the bot's main hand between them based on
        // range to its target, real tactical behavior rather than a fixed
        // loadout. If the kit has a shield, it goes in the offhand and the
        // bot has a chance to block with it.
        ItemStack meleeWeapon = null;
        ItemStack bow = null;
        for (ItemStack item : kit.getContents()) {
            if (item == null) continue;
            if (meleeWeapon == null && isWeaponLike(item)) meleeWeapon = item.clone();
            if (bow == null && (item.getType().name().equals("BOW") || item.getType().name().equals("CROSSBOW"))) {
                bow = item.clone();
            }
        }
        meleeWeapons.put(bot.getUniqueId(), meleeWeapon);
        bows.put(bot.getUniqueId(), bow);
        if (meleeWeapon != null) equipment.setItemInMainHand(meleeWeapon.clone());

        ItemStack offhand = cloneOrNull(kit.getOffhand());
        if (offhand != null && offhand.getType().name().equals("SHIELD")) {
            equipment.setItemInOffHand(offhand);
            hasShield.add(bot.getUniqueId());
        }

        equipment.setHelmetDropChance(0f);
        equipment.setChestplateDropChance(0f);
        equipment.setLeggingsDropChance(0f);
        equipment.setBootsDropChance(0f);
        equipment.setItemInMainHandDropChance(0f);
        equipment.setItemInOffHandDropChance(0f);
    }

    private boolean isWeaponLike(ItemStack item) {
        String name = item.getType().name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE") || name.equals("TRIDENT");
    }

    private ItemStack cloneOrNull(ItemStack item) {
        return item == null ? null : item.clone();
    }

    /** Recolors a wool item to match the bot's team, same logic as
     *  WarManager applies to real players' kit wool. No-op if there's no
     *  team color (1v1 bots) or the item isn't wool. */
    private ItemStack recolorWool(ItemStack item, TeamColor teamColor) {
        if (item == null || teamColor == null) return item;
        if (item.getType().name().endsWith("_WOOL")) {
            item.setType(teamColor.getWoolMaterial());
        }
        return item;
    }

    /** Removes a bot from tracking and despawns it - call this once its
     *  match has ended, whether by the bot dying or the player winning/
     *  losing some other way. Safe to call even if the entity is already
     *  gone. */
    public void despawn(UUID entityId) {
        activeBots.remove(entityId);
        midJump.remove(entityId);
        lastHealAt.remove(entityId);
        meleeWeapons.remove(entityId);
        bows.remove(entityId);
        hasShield.remove(entityId);
        currentlyWieldingBow.remove(entityId);
        lastShotAt.remove(entityId);
        retreating.remove(entityId);
        baseNames.remove(entityId);
        var entity = org.bukkit.Bukkit.getEntity(entityId);
        if (entity instanceof LivingEntity living && living.isValid()) {
            living.remove();
        }
    }

    /** True if this bot is currently mid-jump-attack - used by the damage
     *  listener to award a real critical-hit bonus, the same way landing a
     *  hit while airborne does for a player. */
    public boolean isMidJump(UUID entityId) {
        return midJump.contains(entityId);
    }

    /** Rolls whether a bot with a shield equipped blocks an incoming hit,
     *  reducing it the way a real player raising their shield would.
     *  Bots without a shield in their kit never block. Called from the
     *  damage listeners when a bot is the victim. */
    public boolean tryBlockWithShield(UUID botId, BotDifficulty difficulty) {
        if (!hasShield.contains(botId)) return false;
        return random.nextDouble() < difficulty.getAggressiveness();
    }

    /** Runs the jump-attack and self-heal behavior for every active bot.
     *  Ticks fairly often (every 4 ticks) so jump timing feels responsive
     *  rather than laggy, but each individual bot only actually rolls its
     *  jump/heal chance once per check, not every tick. */
    private void startAiTask() {
        org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Map.Entry<UUID, BotDifficulty> entry : new HashMap<>(activeBots).entrySet()) {
                UUID botId = entry.getKey();
                BotDifficulty difficulty = entry.getValue();
                var entity = org.bukkit.Bukkit.getEntity(botId);
                if (!(entity instanceof Zombie bot) || !bot.isValid()) continue;

                // Defensive extinguish, every tick, regardless of target
                // status - setShouldBurnInDay(false) should already stop
                // this at the source, but this guarantees it even if fire
                // comes from something else in the arena (lava splash,
                // another plugin, etc).
                if (bot.getFireTicks() > 0) bot.setFireTicks(0);
                updateHealthDisplay(bot);

                LivingEntity target = bot.getTarget() instanceof LivingEntity living ? living : null;
                if (target == null) continue;

                double distance = bot.getLocation().distance(target.getLocation());

                if (tryRetreat(bot, target, difficulty, distance)) {
                    // Skip offense entirely this tick while disengaging -
                    // a retreating bot is playing defense, not attacking.
                } else {
                    tryRangedAttack(bot, target, difficulty, distance);
                    tryJumpAttack(bot, target, difficulty);
                }
                tryHeal(bot, difficulty);

                if (bot.isOnGround() && midJump.contains(botId)) {
                    midJump.remove(botId);
                }
            }
        }, 20L, 4L);
    }

    /** Switches to a bow and shoots when the target is far away and the
     *  bot's kit actually has one - real ranged-vs-melee decision making
     *  instead of always closing to melee blindly. Switches back to the
     *  melee weapon once the bot is close again. */
    private void tryRangedAttack(Zombie bot, LivingEntity target, BotDifficulty difficulty, double distance) {
        ItemStack bow = bows.get(bot.getUniqueId());
        EntityEquipment equipment = bot.getEquipment();
        if (bow == null || equipment == null) return;

        boolean shouldUseBow = distance > 6.0;
        boolean wieldingBow = currentlyWieldingBow.contains(bot.getUniqueId());

        if (shouldUseBow && !wieldingBow) {
            equipment.setItemInMainHand(bow.clone());
            currentlyWieldingBow.add(bot.getUniqueId());
        } else if (!shouldUseBow && wieldingBow) {
            ItemStack melee = meleeWeapons.get(bot.getUniqueId());
            equipment.setItemInMainHand(melee != null ? melee.clone() : null);
            currentlyWieldingBow.remove(bot.getUniqueId());
        }

        if (!shouldUseBow) return;

        long now = System.currentTimeMillis();
        Long lastShot = lastShotAt.get(bot.getUniqueId());
        long cooldownMs = (long) (1800 - 800 * difficulty.getAggressiveness()); // harder bots fire faster
        if (lastShot != null && now - lastShot < cooldownMs) return;

        org.bukkit.entity.Arrow arrow = bot.launchProjectile(org.bukkit.entity.Arrow.class);
        org.bukkit.util.Vector direction = target.getEyeLocation().toVector()
                .subtract(bot.getEyeLocation().toVector()).normalize();
        arrow.setVelocity(direction.multiply(1.6));
        arrow.setDamage(arrow.getDamage() * difficulty.getDamageMultiplier());
        lastShotAt.put(bot.getUniqueId(), now);
    }

    /** Disengages briefly when badly hurt instead of standing and tanking
     *  hits while healing - runs directly away from the target for a
     *  moment, giving the self-heal a real chance to matter, the same way
     *  a real player would back off rather than trade blows at low health.
     *  Returns true if the bot is currently retreating (so the caller can
     *  skip its offense this tick). */
    private boolean tryRetreat(Zombie bot, LivingEntity target, BotDifficulty difficulty, double distance) {
        var maxHealthAttr = bot.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr == null) return false;
        double healthFraction = bot.getHealth() / maxHealthAttr.getValue();
        UUID botId = bot.getUniqueId();

        if (healthFraction > 0.25 || distance > 8.0) {
            retreating.remove(botId);
            return false;
        }

        // Only actually retreat some of the time even when hurt - a bot
        // that always flees at low health is predictable and unplayer-like;
        // harder bots are more willing to keep trading hits anyway.
        if (!retreating.contains(botId)) {
            if (random.nextDouble() > (1.0 - difficulty.getAggressiveness())) return false;
            retreating.add(botId);
        }

        if (bot.isOnGround()) {
            org.bukkit.util.Vector awayFromTarget = bot.getLocation().toVector()
                    .subtract(target.getLocation().toVector()).normalize();
            bot.setVelocity(awayFromTarget.multiply(0.3).setY(0.1));
        }
        return true;
    }

    private void tryJumpAttack(Zombie bot, LivingEntity target, BotDifficulty difficulty) {
        if (!bot.isOnGround() || midJump.contains(bot.getUniqueId())) return;
        double distance = bot.getLocation().distance(target.getLocation());
        if (distance > 4.0 || distance < 1.0) return;
        if (random.nextDouble() > difficulty.getAggressiveness()) return;

        org.bukkit.util.Vector towardTarget = target.getLocation().toVector()
                .subtract(bot.getLocation().toVector()).normalize();
        org.bukkit.util.Vector jumpVelocity = towardTarget.multiply(0.35).setY(0.42);
        bot.setVelocity(jumpVelocity);
        midJump.add(bot.getUniqueId());
    }

    /** Simulates using a healing item when badly hurt - a real golden-apple
     *  "eat" animation on a mob isn't something vanilla AI supports, so
     *  this applies the effect directly (a burst of healing plus a
     *  matching sound/particle cue) rather than actually managing a visible
     *  inventory item, which would need far more custom work to pull off
     *  reliably. */
    private void tryHeal(Zombie bot, BotDifficulty difficulty) {
        var maxHealthAttr = bot.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr == null) return;
        double maxHealth = maxHealthAttr.getValue();
        if (bot.getHealth() > maxHealth * 0.3) return;

        long now = System.currentTimeMillis();
        Long last = lastHealAt.get(bot.getUniqueId());
        if (last != null && now - last < 15_000L) return; // don't heal-spam, one "apple" every 15s at most
        if (random.nextDouble() > difficulty.getAggressiveness()) return;

        double healAmount = maxHealth * 0.25;
        bot.setHealth(Math.min(maxHealth, bot.getHealth() + healAmount));
        lastHealAt.put(bot.getUniqueId(), now);
        bot.getWorld().spawnParticle(org.bukkit.Particle.HEART, bot.getLocation().add(0, 1, 0), 5);
        bot.getWorld().playSound(bot.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_BURP, 1f, 1f);
    }
}
