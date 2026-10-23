package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.core.arena.TerrainDamage;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The look and sound of broken terrain: every broken block bursts into its own particles, the blocks' own break sounds
 * play, and a few chunks of them fly out in an arc and crumble away. Particle counts follow the match's particle scale;
 * the flying chunks are short-lived block displays within a budget, removed with the match at the latest.
 */
final class TerrainFx {

    /** At most this many broken blocks get their own particle burst per blast (scaled). */
    private static final int BURST_BLOCKS = 24;
    private static final int BURST_PARTICLES = 6;
    /** Distinct break sounds per blast. */
    private static final int SOUNDS = 3;
    /** Flying chunks per blast (scaled) and alive at once. */
    private static final int CHUNKS_PER_BLAST = 4;
    private static final int MAX_CHUNKS = 24;
    private static final int RISE_TICKS = 6;
    private static final int FALL_TICKS = 7;

    private final GameSession session;
    private int chunks;

    TerrainFx(GameSession session) {
        this.session = session;
    }

    /** Shows {@code broken} (nearest first) breaking around {@code center}. */
    void play(Location center, List<TerrainDamage.Broken> broken, double scale) {
        if (broken.isEmpty() || center.getWorld() == null) {
            return;
        }
        World world = center.getWorld();
        int bursts = Math.min(broken.size(), Math.max(4, (int) Math.round(BURST_BLOCKS * scale)));
        int particles = Math.max(2, (int) Math.round(BURST_PARTICLES * scale));
        for (int i = 0; i < bursts; i++) {
            TerrainDamage.Broken block = broken.get(i * broken.size() / bursts);
            Location middle = block.location().clone().add(0.5, 0.5, 0.5);
            world.spawnParticle(Particle.BLOCK, middle, particles, 0.3, 0.3, 0.3, 0.15, block.data());
        }
        Set<Sound> played = new HashSet<>();
        for (TerrainDamage.Broken block : broken) {
            if (played.size() >= SOUNDS) {
                break;
            }
            Sound sound = breakSound(block.data());
            if (played.add(sound)) {
                world.playSound(block.location().clone().add(0.5, 0.5, 0.5), sound, SoundCategory.BLOCKS, 1.0f,
                        0.75f + ThreadLocalRandom.current().nextFloat() * 0.2f);
            }
        }
        int count = Math.min(Math.max(1, (int) Math.round(CHUNKS_PER_BLAST * scale)), MAX_CHUNKS - chunks);
        for (int i = 0; i < count; i++) {
            fling(center, broken.get(i * broken.size() / count));
        }
    }

    /** The block's own break sound (stone for a block whose sound group the server cannot tell). */
    private static Sound breakSound(BlockData data) {
        try {
            return data.getSoundGroup().getBreakSound();
        } catch (RuntimeException unknown) {
            return Sound.BLOCK_STONE_BREAK;
        }
    }

    /** A small tumbling chunk of {@code block} thrown up and away from the blast, crumbling as it comes down. */
    private void fling(Location center, TerrainDamage.Broken block) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location at = block.location().clone().add(0.5, 0.5, 0.5);
        Vector away = at.toVector().subtract(center.toVector()).setY(0);
        if (away.lengthSquared() < 1.0E-4) {
            double angle = random.nextDouble(Math.PI * 2);
            away = new Vector(Math.cos(angle), 0, Math.sin(angle));
        }
        away.normalize().multiply(0.9 + random.nextDouble(0.8));
        float size = 0.28f + random.nextFloat() * 0.17f;
        Quaternionf spin = new Quaternionf().rotateXYZ(random.nextFloat() * 3f, random.nextFloat() * 3f,
                random.nextFloat() * 3f);
        Location spot = at.clone();
        spot.setYaw(0);
        spot.setPitch(0);
        BlockDisplay chunk = at.getWorld().spawn(spot, BlockDisplay.class,
                display -> {
                    display.setPersistent(false);
                    display.setBlock(block.data());
                    display.setShadowRadius(0);
                    display.setShadowStrength(0);
                    display.setInterpolationDelay(0);
                    display.setTransformation(pose(new Vector3f(), spin, size));
                });
        session.track(chunk);
        chunks++;
        float peak = 0.9f + random.nextFloat() * 0.7f;
        Vector3f up = new Vector3f((float) away.getX() * 0.6f, peak, (float) away.getZ() * 0.6f);
        Vector3f down = new Vector3f((float) away.getX() * 1.3f, -0.6f, (float) away.getZ() * 1.3f);
        Quaternionf tumble = new Quaternionf(spin).rotateXYZ(1.2f, 0.8f, 0.5f);
        session.runLater(1, () -> animate(chunk, pose(up, tumble, size), RISE_TICKS));
        session.runLater(1 + RISE_TICKS, () -> animate(chunk,
                pose(down, new Quaternionf(tumble).rotateXYZ(1.0f, 0.6f, 0.4f), size * 0.15f), FALL_TICKS));
        session.runLater(2 + RISE_TICKS + FALL_TICKS, () -> {
            chunks = Math.max(0, chunks - 1);
            if (chunk.isValid()) {
                chunk.remove();
            }
            session.untrack(chunk);
        });
    }

    /** A cube of edge {@code size} centered on {@code offset}, rotated by {@code rotation} around its center. */
    private static Transformation pose(Vector3f offset, Quaternionf rotation, float size) {
        Vector3f half = new Vector3f(size / 2, size / 2, size / 2).rotate(rotation);
        Vector3f translation = new Vector3f(offset).sub(half);
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    private static void animate(BlockDisplay chunk, Transformation target, int ticks) {
        if (!chunk.isValid()) {
            return;
        }
        chunk.setInterpolationDelay(0);
        chunk.setInterpolationDuration(ticks);
        chunk.setTransformation(target);
    }
}
