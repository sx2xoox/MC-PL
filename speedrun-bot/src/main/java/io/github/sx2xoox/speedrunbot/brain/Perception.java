package io.github.sx2xoox.speedrunbot.brain;

import io.github.sx2xoox.speedrunbot.core.PerceptionRules;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * The bot's eyes. Every tick it casts rays from the eye position inside the camera frustum (render distance capped)
 * and writes every block a ray passes or stops at into {@link WorldMemory}. Rays stop at opaque full cubes and at
 * lava, and stop at chunks that are not loaded. The blocks touching the player are always sensed. This is the only
 * way world data reaches the bot's decisions. In relaxed (non-fair) mode, it additionally reads every block in a
 * cube around the player now and then (comparison runs only).
 */
public final class Perception {
    private final BotSenses senses;
    private final WorldMemory memory;
    private final PerceptionRules rules;
    private final int raysPerTick;
    private final Random random = new Random(12345);
    private long rays;
    private long ticks;

    public Perception(BotSenses senses, WorldMemory memory, PerceptionRules rules, int raysPerTick) {
        this.senses = senses;
        this.memory = memory;
        this.rules = rules;
        this.raysPerTick = raysPerTick;
    }

    public PerceptionRules rules() {
        return rules;
    }

    public void tick() {
        ticks++;
        Vec3d eye = senses.eyePos();
        Vec3d pos = senses.pos();
        int fx = floor(pos.x), fy = floor(pos.y), fz = floor(pos.z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    sense(fx + dx, fy + dy, fz + dz);
                }
            }
        }
        if (!rules.fair() && ticks % 40 == 1) {
            for (int dx = -24; dx <= 24; dx++) {
                for (int dz = -24; dz <= 24; dz++) {
                    for (int dy = -24; dy <= 24; dy++) {
                        sense(fx + dx, fy + dy, fz + dz);
                    }
                }
            }
        }
        float yaw = senses.yaw(), pitch = senses.pitch();
        cast(eye, PerceptionRules.direction(yaw, pitch));
        for (int i = 0; i < raysPerTick; i++) {
            cast(eye, rules.sampleRay(yaw, pitch, random));
        }
    }

    private void sense(int x, int y, int z) {
        BlockState s = senses.blockAt(x, y, z);
        if (s != null) {
            memory.put(x, y, z, s);
        }
    }

    /** Voxel traversal (Amanatides-Woo) from the eye along d. */
    private void cast(Vec3d eye, double[] d) {
        rays++;
        double range = rules.maxRange();
        int x = floor(eye.x), y = floor(eye.y), z = floor(eye.z);
        int sx = d[0] > 0 ? 1 : -1, sy = d[1] > 0 ? 1 : -1, sz = d[2] > 0 ? 1 : -1;
        double tdx = d[0] == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / d[0]);
        double tdy = d[1] == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / d[1]);
        double tdz = d[2] == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / d[2]);
        double tmx = d[0] == 0 ? Double.POSITIVE_INFINITY : ((sx > 0 ? (x + 1 - eye.x) : (eye.x - x)) * tdx);
        double tmy = d[1] == 0 ? Double.POSITIVE_INFINITY : ((sy > 0 ? (y + 1 - eye.y) : (eye.y - y)) * tdy);
        double tmz = d[2] == 0 ? Double.POSITIVE_INFINITY : ((sz > 0 ? (z + 1 - eye.z) : (eye.z - z)) * tdz);
        double t = 0;
        int water = 0;
        while (t <= range) {
            BlockState s = senses.blockAt(x, y, z);
            if (s == null) {
                return;
            }
            if (!s.getFluidState().isEmpty() && s.getFluidState().getFluid() != Fluids.LAVA && s.getFluidState().getFluid() != Fluids.FLOWING_LAVA
                    && !rules.seesThroughWater(++water)) {
                return;
            }
            memory.put(x, y, z, s);
            if (blocksSight(s, x, y, z)) {
                return;
            }
            if (tmx < tmy && tmx < tmz) {
                x += sx;
                t = tmx;
                tmx += tdx;
            } else if (tmy < tmz) {
                y += sy;
                t = tmy;
                tmy += tdy;
            } else {
                z += sz;
                t = tmz;
                tmz += tdz;
            }
        }
    }

    private boolean blocksSight(BlockState s, int x, int y, int z) {
        if (s.isAir()) {
            return false;
        }
        if (s.getFluidState().getFluid() == Fluids.LAVA || s.getFluidState().getFluid() == Fluids.FLOWING_LAVA) {
            return true;
        }
        return senses.opaque(s, x, y, z);
    }

    /** True when nothing opaque lies between the eyes and the point (block-grid line of sight). */
    public boolean lineOfSight(Vec3d from, Vec3d to) {
        Vec3d dv = to.subtract(from);
        double len = dv.length();
        if (len < 1e-6) {
            return true;
        }
        int steps = (int) Math.ceil(len * 4);
        int lx = Integer.MIN_VALUE, ly = 0, lz = 0;
        for (int i = 1; i < steps; i++) {
            double f = i / (double) steps;
            int x = floor(from.x + dv.x * f), y = floor(from.y + dv.y * f), z = floor(from.z + dv.z * f);
            if (x == lx && y == ly && z == lz) {
                continue;
            }
            lx = x;
            ly = y;
            lz = z;
            BlockState s = senses.blockAt(x, y, z);
            if (s == null || blocksSight(s, x, y, z)) {
                return false;
            }
        }
        return true;
    }

    /** Entities in view with a clear line of sight (fair mode) within range. */
    public <T extends Entity> List<T> visible(Class<T> type, double range, Predicate<T> filter) {
        Vec3d eye = senses.eyePos();
        Box box = new Box(eye, eye).expand(range);
        List<T> out = new ArrayList<>();
        for (Entity raw : senses.entitiesNear(box)) {
            if (!type.isInstance(raw) || !raw.isAlive()) {
                continue;
            }
            T e = type.cast(raw);
            if (filter != null && !filter.test(e)) {
                continue;
            }
            Vec3d c = e.getPos().add(0, e.getHeight() * 0.5, 0);
            if (c.squaredDistanceTo(eye) > range * range) {
                continue;
            }
            if (rules.fair()) {
                if (!rules.inView(senses.yaw(), senses.pitch(), c.x - eye.x, c.y - eye.y, c.z - eye.z)) {
                    continue;
                }
                if (c.squaredDistanceTo(eye) > 2.25 && !lineOfSight(eye, c)) {
                    continue;
                }
            }
            out.add(e);
        }
        return out;
    }

    public long rays() {
        return rays;
    }

    static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
