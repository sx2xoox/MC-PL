package io.github.sx2xoox.mcpl.game.siege.creature;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeArena;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.util.TriState;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/** Shared helpers of the creatures the 공성전 v2 systems drive (minions, jungle monsters). */
public final class Creatures {

    /** Segments of the fallback health bar in a creature's name. */
    private static final int BAR = 10;

    /**
     * The walking speed attribute of every body ({@link CreatureBodies}: one mob type for all): a kind's settings speed
     * multiplies it (1 = an ordinary toy's walk).
     */
    public static final double BODY_SPEED = 0.25;

    private Creatures() {
    }

    /**
     * Makes a freshly spawned mob a driven creature: no vanilla goals (the systems walk and fight it), never despawns,
     * picks nothing up, the given health, size (the toy's size: 1 = as modelled), name; persistent false (tracked by
     * the match instead). Every body walks at {@link #BODY_SPEED} (the systems' speeds multiply it); the systems
     * replace vanilla's knockback with their own small push ({@link #push}).
     */
    public static void prepare(Mob mob, Component name, double health, double scale, double followRange) {
        mob.setPersistent(false);
        // no vanilla moans, steps or death cries: the toys make their own sounds (CreatureSounds, JungleSounds)
        mob.setSilent(true);
        CreatureBodies.optional(() -> mob.setRemoveWhenFarAway(false));
        CreatureBodies.optional(() -> mob.setDespawnInPeacefulOverride(TriState.FALSE));
        CreatureBodies.optional(() -> mob.setCanPickupItems(false));
        CreatureBodies.optional(() -> Bukkit.getMobGoals().removeAllGoals(mob));
        base(mob, Attribute.MAX_HEALTH, health);
        base(mob, Attribute.SCALE, scale);
        base(mob, Attribute.FOLLOW_RANGE, followRange);
        base(mob, Attribute.MOVEMENT_SPEED, BODY_SPEED);
        mob.setHealth(maxHealth(mob));
        mob.customName(name);
        mob.setCustomNameVisible(true);
    }

    public static void base(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    public static double maxHealth(LivingEntity entity) {
        AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? entity.getHealth() : max.getValue();
    }

    /** Full health again. */
    public static void heal(LivingEntity entity) {
        entity.setHealth(maxHealth(entity));
    }

    /** {@code name ▮▮▮▮▮▮▯▯▯▯} — the health in the name, for matches without overhead bars. */
    public static Component nameWithHealth(Component name, LivingEntity entity, TextColor colour) {
        double fraction = Math.clamp(entity.getHealth() / Math.max(1, maxHealth(entity)), 0, 1);
        int full = (int) Math.ceil(fraction * BAR);
        return Component.text().append(name).append(Component.space())
                .append(Component.text("|".repeat(full), colour))
                .append(Component.text("|".repeat(BAR - full), NamedTextColor.DARK_GRAY)).build();
    }

    /** Shows a creature's health: the match's overhead bar, or the name fallback. */
    public static void showHealth(SiegeArena arena, LivingEntity entity, Component name, TextColor colour) {
        if (!arena.overheadBar(entity, name)) {
            entity.customName(nameWithHealth(name, entity, colour));
        }
    }

    /**
     * A creature's hit: a structure hitbox goes through the rules ({@link SiegeArena#damageStructure}), anything else
     * takes mob attack damage caused by the creature. Ranged hits draw a short trail.
     */
    public static void strike(SiegeArena arena, Mob attacker, LivingEntity victim, double damage,
            LaneSites.@Nullable Side side, boolean ranged) {
        face(attacker, victim.getLocation());
        if (ranged) {
            trail(attacker.getEyeLocation(), victim.getLocation().add(0, victim.getHeight() / 2, 0));
        }
        hit(arena, attacker, victim, damage, side);
    }

    /**
     * A creature's blow landing on {@code victim} (an area skill, a projectile arriving): {@link #strike} without
     * turning the attacker. A structure hitbox goes through the rules, anything else takes mob attack damage caused by
     * the creature (the world difficulty compensated for players).
     */
    public static void hit(SiegeArena arena, Mob attacker, LivingEntity victim, double damage,
            LaneSites.@Nullable Side side) {
        if (!victim.isValid() || victim.isDead()) {
            return;
        }
        if (arena.isStructure(victim)) {
            if (side != null) {
                arena.damageStructure(victim, side, damage);
            }
            return;
        }
        if (victim instanceof Player && victim.getWorld().getDifficulty() == Difficulty.PEACEFUL) {
            // Peaceful turns damage a mob causes to a player into nothing: without a causing entity it is not scaled.
            victim.damage(damage, DamageSource.builder(DamageType.MOB_ATTACK).withDirectEntity(attacker).build());
            return;
        }
        double dealt = victim instanceof Player ? beforeDifficulty(damage, victim.getWorld().getDifficulty()) : damage;
        victim.damage(dealt, DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(attacker)
                .withDirectEntity(attacker).build());
    }

    /**
     * The amount a mob must deal for a player to take {@code damage}: the game scales damage a mob causes to a player
     * by the world difficulty (easy {@code min(x / 2 + 1, x)}, hard {@code x * 1.5}), while the balance numbers are
     * what the player takes, on every server.
     */
    public static double beforeDifficulty(double damage, Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> damage <= 2 ? damage : 2 * (damage - 1);
            case HARD -> damage / 1.5;
            case NORMAL, PEACEFUL -> damage;
        };
    }

    /**
     * Turns a standing creature to face {@code yaw} (Minecraft yaw, 0 = +z): its rotation, head and body, so a mob
     * without AI keeps facing there (its model follows the rotation).
     */
    public static void turn(Mob mob, float yaw) {
        CreatureBodies.optional(() -> mob.setRotation(yaw, 0));
        CreatureBodies.optional(() -> mob.setBodyYaw(yaw));
    }

    /** Turns the body towards a place. */
    public static void face(Mob mob, Location at) {
        Vector delta = at.toVector().subtract(mob.getLocation().toVector()).setY(0);
        if (delta.lengthSquared() < 1.0E-6) {
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.getX(), delta.getZ()));
        CreatureBodies.optional(() -> mob.setRotation(yaw, 0));
    }

    private static void trail(Location from, Location to) {
        Vector step = to.toVector().subtract(from.toVector());
        double length = step.length();
        if (length < 0.5) {
            return;
        }
        int points = (int) Math.min(24, length * 2);
        step.multiply(1.0 / points);
        Location at = from.clone();
        for (int i = 0; i < points; i++) {
            at.add(step);
            at.getWorld().spawnParticle(Particle.CRIT, at, 1, 0, 0, 0, 0);
        }
    }

    /**
     * The creatures' own knockback (instead of vanilla's, which flings a toy like the mob its body is): the velocity of
     * a small horizontal push away from {@code from} that slides about {@code blocks} (0 = it stands its ground).
     */
    public static Vector push(Location body, Location from, double blocks) {
        Vector away = body.toVector().subtract(from.toVector()).setY(0);
        if (blocks <= 0 || away.lengthSquared() < 1.0E-6) {
            return new Vector();
        }
        // v blocks per tick slides about v / (1 - 0.546) on the ground (block friction 0.6 × drag 0.91)
        return away.normalize().multiply(blocks * 0.454);
    }

    /**
     * Where a landed hit came from: the projectile or attacker that dealt it (the direct entity, else the causing one,
     * else the source's place), or null when nothing says (a burn, a poison).
     */
    public static @Nullable Location hitFrom(EntityDamageEvent event) {
        DamageSource source = event.getDamageSource();
        Entity direct = source.getDirectEntity();
        if (direct != null && !direct.equals(event.getEntity())) {
            return direct.getLocation();
        }
        Entity causing = source.getCausingEntity();
        if (causing != null && !causing.equals(event.getEntity())) {
            return causing.getLocation();
        }
        Location at = source.getSourceLocation();
        return at == null ? null : at.clone();
    }

    /** Horizontal distance squared. */
    public static double flatDistanceSquared(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
