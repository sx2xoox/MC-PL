package io.github.sx2xoox.mcpl.game.superpower.support.world;

import java.util.EnumMap;
import java.util.Map;

import com.google.common.base.Function;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * {@code LivingEntity#damage(double, DamageSource)} for test entities, following the server instead of MockBukkit's
 * simplified {@code simulateDamage} (which reports every entity-caused hit as {@code ENTITY_ATTACK}):
 *
 * <ul>
 *     <li>the {@link DamageCause} is derived from the damage type and the direct entity exactly like
 *     {@code CraftEventFactory#handleEntityDamageEvent} (ability damage of type {@code MAGIC} with an entity is
 *     {@code MAGIC}, an arrow is {@code PROJECTILE}, ...);</li>
 *     <li>players hit by living non-players get the vanilla difficulty scaling (easy: {@code min(d/2+1, d)},
 *     hard: {@code d*1.5});</li>
 *     <li>invulnerability frames: while {@code noDamageTicks > maxNoDamageTicks/2} a hit only lands if it is
 *     stronger than the last one, and only the difference is dealt;</li>
 *     <li>the event carries the {@code RESISTANCE} (20% per level) and {@code ABSORPTION} modifiers with their
 *     functions, so {@code setDamage}, {@code getFinalDamage} and absorption hearts behave like on a server.</li>
 * </ul>
 * Armor, enchantments, blocking and knockback are not simulated.
 */
public final class DamageSimulation {

    private static final Function<Double, Double> ZERO = value -> -0.0;

    private DamageSimulation() {
    }

    // DamageModifier and setLastDamageCause are deprecated API, but they are what the server uses for this.
    @SuppressWarnings({"deprecation", "removal"})
    public static void damage(LivingEntity target, double amount, DamageSource source) {
        if (!target.isValid() || target.isDead() || !Double.isFinite(amount)) {
            return;
        }
        DamageType type = source.getDamageType();
        boolean bypassesInvulnerability = type == DamageType.OUT_OF_WORLD || type == DamageType.GENERIC_KILL;
        if (!bypassesInvulnerability && (target.isInvulnerable() || (target instanceof Player player
                && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)))) {
            return;
        }
        if (target instanceof Player && scalesWithDifficulty(source)) {
            Difficulty difficulty = target.getWorld().getDifficulty();
            amount = switch (difficulty) {
                case PEACEFUL -> 0;
                case EASY -> Math.min(amount / 2.0 + 1.0, amount);
                case NORMAL -> amount;
                case HARD -> amount * 1.5;
            };
        }
        if (amount <= 0) {
            return;
        }
        boolean cooldown = target.getNoDamageTicks() > target.getMaximumNoDamageTicks() / 2.0;
        double lastHurt = target instanceof Hurtable hurtable ? hurtable.lastHurt() : 0.0;
        if (cooldown && amount <= lastHurt) {
            return;
        }

        EntityDamageEvent event = createEvent(target, amount, source);
        if (!event.callEvent()) {
            return;
        }
        target.setLastDamageCause(event);
        double finalDamage = event.getFinalDamage();
        if (cooldown) {
            finalDamage -= lastHurt;
        } else {
            target.setNoDamageTicks(target.getMaximumNoDamageTicks());
        }
        if (target instanceof Hurtable hurtable) {
            hurtable.lastHurt(amount);
        }
        double absorbed = -event.getDamage(DamageModifier.ABSORPTION);
        if (absorbed > 0) {
            target.setAbsorptionAmount(Math.max(0, target.getAbsorptionAmount() - absorbed));
        }
        if (finalDamage > 0) {
            target.setHealth(Math.max(0, target.getHealth() - finalDamage));
        }
    }

    /** Damage of a living non-player causer is scaled for players (the vanilla default damage scaling). */
    private static boolean scalesWithDifficulty(DamageSource source) {
        Entity causing = source.getCausingEntity();
        return causing instanceof LivingEntity && !(causing instanceof Player);
    }

    @SuppressWarnings("deprecation")
    static EntityDamageEvent createEvent(LivingEntity target, double amount, DamageSource source) {
        Function<Double, Double> resistance = resistanceFunction(target, source.getDamageType());
        double absorption = target.getAbsorptionAmount();
        Function<Double, Double> absorptionFunction = value -> -Math.max(value - Math.max(value - absorption, 0.0), 0.0);

        Map<DamageModifier, Double> modifiers = new EnumMap<>(DamageModifier.class);
        Map<DamageModifier, Function<? super Double, Double>> functions = new EnumMap<>(DamageModifier.class);
        modifiers.put(DamageModifier.BASE, amount);
        functions.put(DamageModifier.BASE, ZERO);
        double resisted = resistance.apply(amount);
        modifiers.put(DamageModifier.RESISTANCE, resisted);
        functions.put(DamageModifier.RESISTANCE, resistance);
        modifiers.put(DamageModifier.ABSORPTION, absorptionFunction.apply(amount + resisted));
        functions.put(DamageModifier.ABSORPTION, absorptionFunction);

        Entity damager = source.getDirectEntity();
        DamageCause cause = causeOf(source);
        if (damager != null) {
            return new EntityDamageByEntityEvent(damager, target, cause, source, modifiers, functions, false);
        }
        return new EntityDamageEvent(target, cause, source, modifiers, functions);
    }

    private static Function<Double, Double> resistanceFunction(LivingEntity target, DamageType type) {
        PotionEffect effect = target.getPotionEffect(PotionEffectType.RESISTANCE);
        if (effect == null || type == DamageType.OUT_OF_WORLD || type == DamageType.GENERIC_KILL) {
            return ZERO;
        }
        int reduction = (effect.getAmplifier() + 1) * 5;
        return value -> -(value - Math.max(value * (25 - reduction) / 25.0, 0.0));
    }

    /** {@code CraftEventFactory#handleEntityDamageEvent}: the cause the server reports for a damage source. */
    @SuppressWarnings("deprecation") // DRAGON_BREATH
    public static DamageCause causeOf(DamageSource source) {
        DamageType type = source.getDamageType();
        Entity damager = source.getDirectEntity();
        if (damager != null) {
            if (type == DamageType.FIREWORKS || type == DamageType.EXPLOSION || type == DamageType.PLAYER_EXPLOSION) {
                return damager instanceof TNTPrimed ? DamageCause.BLOCK_EXPLOSION : DamageCause.ENTITY_EXPLOSION;
            }
            if (damager instanceof Projectile) {
                return damager instanceof ThrownPotion ? DamageCause.MAGIC : DamageCause.PROJECTILE;
            }
            if (type == DamageType.THORNS) {
                return DamageCause.THORNS;
            }
            if (type == DamageType.SONIC_BOOM) {
                return DamageCause.SONIC_BOOM;
            }
            if (type == DamageType.FALLING_STALACTITE || type == DamageType.FALLING_BLOCK
                    || type == DamageType.FALLING_ANVIL) {
                return DamageCause.FALLING_BLOCK;
            }
            if (type == DamageType.LIGHTNING_BOLT) {
                return DamageCause.LIGHTNING;
            }
            if (type == DamageType.DRAGON_BREATH) {
                return DamageCause.DRAGON_BREATH;
            }
            if (type == DamageType.MAGIC) {
                return DamageCause.MAGIC;
            }
            return DamageCause.ENTITY_ATTACK;
        }
        if (type == DamageType.OUT_OF_WORLD) {
            return DamageCause.VOID;
        }
        if (type == DamageType.LAVA) {
            return DamageCause.LAVA;
        }
        if (type == DamageType.IN_FIRE) {
            return DamageCause.FIRE;
        }
        if (type == DamageType.STARVE) {
            return DamageCause.STARVATION;
        }
        if (type == DamageType.WITHER) {
            return DamageCause.WITHER;
        }
        if (type == DamageType.IN_WALL) {
            return DamageCause.SUFFOCATION;
        }
        if (type == DamageType.DROWN) {
            return DamageCause.DROWNING;
        }
        if (type == DamageType.ON_FIRE) {
            return DamageCause.FIRE_TICK;
        }
        if (type == DamageType.MAGIC) {
            return DamageCause.MAGIC;
        }
        if (type == DamageType.FALL) {
            return DamageCause.FALL;
        }
        if (type == DamageType.FREEZE) {
            return DamageCause.FREEZE;
        }
        if (type == DamageType.GENERIC_KILL) {
            return DamageCause.KILL;
        }
        if (type == DamageType.OUTSIDE_BORDER) {
            return DamageCause.WORLD_BORDER;
        }
        return DamageCause.CUSTOM;
    }
}
