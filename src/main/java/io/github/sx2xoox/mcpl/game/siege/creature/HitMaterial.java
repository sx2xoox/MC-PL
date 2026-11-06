package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.Vector;

/**
 * What a 공성전 toy is made of, for the hit feel: the sparks that fly where a blow lands and the material layer of its
 * hit sound. Plastic toys glint, plush puffs cotton, jelly splashes drops, tin and clockwork throw metal sparks, wooden
 * buildings chip splinters and the porcelain core sheds white shards. Particles only (no entities); everyone sees them.
 */
public enum HitMaterial {
    /** Plastic toys (the soldiers, the archers, the merchant, the dragon's vinyl): white glints. */
    PLASTIC(Sound.ENTITY_ARMOR_STAND_HIT, 1.45f),
    /** Plush (the teddy, the rabbit): cotton tufts. */
    COTTON(Sound.BLOCK_WOOL_HIT, 0.9f),
    /** Jelly: pink drops. */
    JELLY(Sound.ENTITY_SLIME_SQUISH_SMALL, 1.3f),
    /** Tin and clockwork (the cannon, the mice): metal sparks. */
    METAL(Sound.BLOCK_METAL_HIT, 1.4f),
    /** The painted wooden towers: splinters. */
    WOOD(Sound.BLOCK_BAMBOO_WOOD_HIT, 1.25f),
    /** The music-box core: white shards. */
    PORCELAIN(Sound.BLOCK_DECORATED_POT_HIT, 1.35f);

    /** The jelly's pink (its drops). */
    private static final Color JELLY_PINK = Color.fromRGB(0xFF, 0x8C, 0xBE);

    private final Sound sound;
    private final float pitch;

    HitMaterial(Sound sound, float pitch) {
        this.sound = sound;
        this.pitch = pitch;
    }

    /** The material layer of the hit sound. */
    public Sound sound() {
        return sound;
    }

    public float pitch() {
        return pitch;
    }

    /** What a creature is made of. */
    public static HitMaterial of(CreatureKind kind) {
        return switch (kind) {
            case MINION_MELEE, MINION_RANGED, SHOPKEEPER, DRAGON -> PLASTIC;
            case MINION_CANNON, MICE -> METAL;
            case JELLY -> JELLY;
            case TEDDY, RABBIT -> COTTON;
        };
    }

    /**
     * A creature's own hurt sound (its "voice": the jelly's squish, the mouse's squeak, the dragon's grunt) as the
     * material layer, or its material's sound.
     */
    public static Voice voice(CreatureKind kind) {
        return switch (kind) {
            case JELLY -> new Voice(Sound.ENTITY_SLIME_SQUISH_SMALL, 1.3f, 0.9f);
            case MICE -> new Voice(Sound.ENTITY_SILVERFISH_HURT, 1.9f, 0.7f);
            case TEDDY -> new Voice(Sound.BLOCK_WOOL_BREAK, 0.7f, 1f);
            case RABBIT -> new Voice(Sound.ENTITY_RABBIT_HURT, 1.2f, 0.8f);
            case DRAGON -> new Voice(Sound.ENTITY_RAVAGER_HURT, 1.3f, 1f);
            case MINION_CANNON -> new Voice(Sound.BLOCK_METAL_HIT, 1.4f, 0.8f);
            case MINION_MELEE, MINION_RANGED, SHOPKEEPER -> new Voice(Sound.ENTITY_ARMOR_STAND_HIT, 1.45f, 0.8f);
        };
    }

    /** One sound layer: the sound, its base pitch and volume. */
    public record Voice(Sound sound, float pitch, float volume) {
    }

    /**
     * The sparks of a blow landing at {@code at}: flying out along {@code out} (towards the attacker; zero = all round),
     * {@code amount} times the usual count (0 = none), a bigger burst for a {@code strong} hit.
     *
     * @return the particles spawned (tests, budgets)
     */
    public int sparks(Location at, Vector out, double amount, boolean strong) {
        World world = at.getWorld();
        if (world == null || !(amount > 0)) {
            return 0;
        }
        double k = amount * (strong ? 1.8 : 1);
        int spawned = 0;
        switch (this) {
            case PLASTIC -> {
                spawned += burst(world, Particle.CRIT, at, out, count(5, k), 0.35, null);
                spawned += spray(world, Particle.WAX_OFF, at, count(3, k), 0.18, null);
            }
            case COTTON -> {
                spawned += burst(world, Particle.BLOCK, at, out, count(6, k), 0.18, block(Material.WHITE_WOOL));
                spawned += spray(world, Particle.WHITE_ASH, at, count(6, k), 0.3, null);
            }
            case JELLY -> {
                spawned += burst(world, Particle.ITEM_SLIME, at, out, count(5, k), 0.25, null);
                spawned += spray(world, Particle.DUST, at, count(5, k), 0.25,
                        new Particle.DustOptions(JELLY_PINK, 1.1f));
            }
            case METAL -> {
                spawned += burst(world, Particle.ELECTRIC_SPARK, at, out, count(6, k), 0.45, null);
                spawned += burst(world, Particle.SMALL_FLAME, at, out, count(2, k), 0.08, null);
            }
            case WOOD -> {
                spawned += burst(world, Particle.BLOCK, at, out, count(7, k), 0.2, block(Material.BIRCH_PLANKS));
                spawned += spray(world, Particle.CRIT, at, count(3, k), 0.25, null);
            }
            case PORCELAIN -> {
                spawned += burst(world, Particle.BLOCK, at, out, count(6, k), 0.2, block(Material.WHITE_GLAZED_TERRACOTTA));
                spawned += spray(world, Particle.WAX_OFF, at, count(4, k), 0.3, null);
            }
        }
        if (strong) {
            world.spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, Color.WHITE);
            spawned++;
        }
        return spawned;
    }

    private static BlockData block(Material material) {
        return material.createBlockData();
    }

    private static int count(int base, double k) {
        return Math.max(1, (int) Math.round(base * k));
    }

    /** {@code count} particles thrown out of {@code at} along {@code out} (a cone), each its own way. */
    private static <T> int burst(World world, Particle particle, Location at, Vector out, int count, double speed,
                                 T data) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            Vector v = new Vector(random.nextGaussian(), random.nextGaussian() * 0.7 + 0.35, random.nextGaussian())
                    .multiply(0.6).add(out);
            if (v.lengthSquared() > 1.0E-6) {
                v.normalize();
            }
            // count 0: the offsets are the particle's own direction, the speed its speed
            world.spawnParticle(particle, at, 0, v.getX(), v.getY(), v.getZ(), speed * (0.6 + random.nextDouble() * 0.6),
                    data);
        }
        return count;
    }

    /** {@code count} particles spread round {@code at}. */
    private static <T> int spray(World world, Particle particle, Location at, int count, double spread, T data) {
        world.spawnParticle(particle, at, count, spread, spread, spread, 0.02, data);
        return count;
    }
}
