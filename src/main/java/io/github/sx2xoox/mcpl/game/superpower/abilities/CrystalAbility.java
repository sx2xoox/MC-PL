package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 크리스탈술사 — an amethyst mage. Standing near crystals (its own leftover shards or amethyst in the world) makes its
 * active come back faster; the active drives a line of amethyst spikes along the ground that launches whoever it
 * catches and leaves a few resonating shards behind; the ultimate grows a crystal prison around the aimed enemy and
 * shatters it in a burst of shards.
 *
 * <p>Look (pale pink, amethyst lilac, deep purple): resonance draws lilac motes from the nearest crystal into the owner
 * with soft chimes. Spikes are amethyst clusters erupting one after another with jagged tilts and amethyst chunks at
 * their feet, sparkling at the tips, a crack running just ahead of them. The prison is a ring of tall clusters
 * leaning in around the victim inside a glowing violet glass shell with a crown hanging on top; it shimmers, cracks
 * and shivers before it bursts: a flash, the clusters and amethyst shards flung outwards, a pink shockwave.
 */
public final class CrystalAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("crystal", "크리스탈술사", Material.AMETHYST_CLUSTER)
            .color(TextColor.color(0xC77DFF))
            .difficulty(2)
            .passive("공명",
                    "내 크리스탈이나 자수정 블록에서 {resonance_radius}블록 안에 있으면 액티브 쿨타임이 "
                            + "{resonance_bonus}% 더 빨리 돌아옵니다.")
            .active("크리스탈 가시",
                    "바라보는 방향으로 바닥에서 자수정 가시가 {spike_length}블록까지 차례로 솟아, 맞은 적에게 {spike_damage} 피해를 "
                            + "주고 {spike_lift}블록 띄웁니다. 가시 몇 개는 크리스탈로 {shard_duration}초간 남아 공명합니다.",
                    9)
            .ultimate("크리스탈 감옥",
                    "조준한 적({prison_range}블록 이내)을 크리스탈로 {prison_duration}초간 가둬 꼼짝 못 하게 합니다. 감옥이 "
                            + "산산조각 나며 갇힌 적에게 {shatter_damage}, 반경 {shatter_radius}블록의 다른 적에게 "
                            + "{splash_damage} 피해를 주고 밀쳐냅니다.")
            .param("resonance_radius", 4)
            .param("resonance_bonus", 60)
            .param("spike_length", 12)
            .param("spike_width", 1.1)
            .param("spike_damage", 5)
            .param("spike_lift", 1.2)
            .param("shard_duration", 6)
            .param("prison_range", 24)
            .param("prison_duration", 2)
            .param("shatter_damage", 7)
            .param("shatter_radius", 3.5)
            .param("splash_damage", 4)
            .build();

    private static final String COLOR_TAG = "#C77DFF";
    /** Pale pink, amethyst lilac, deep purple. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFE3F8, 0xC77DFF, 0x5A189A);
    private static final Color PINK = Color.fromRGB(0xFF8AD8);
    private static final Particle.DustOptions LILAC = PALETTE.dust(1.0f);
    private static final Particle.DustOptions LILAC_SMALL = PALETTE.dust(0.6f);
    private static final Particle.DustOptions PINK_DUST = new Particle.DustOptions(PINK, 0.9f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.1f);
    private static final Particle.DustTransition GLOW_SMALL = PALETTE.glow(0.7f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.2f);
    private static final Particle.DustTransition BLUSH = new Particle.DustTransition(PINK, PALETTE.body(), 1.0f);

    // Block data needs a running server: only the materials are constants, the data is created when used.
    private static final Material CLUSTER = Material.AMETHYST_CLUSTER;
    private static final Material BUD = Material.LARGE_AMETHYST_BUD;
    private static final Material AMETHYST = Material.AMETHYST_BLOCK;
    private static final Material SHELL = Material.PURPLE_STAINED_GLASS;
    private static final Material SHARD_ITEM = Material.AMETHYST_SHARD;
    /** Blocks of the world that resonate like the mage's own crystals. */
    private static final Set<Material> AMETHYSTS = Set.of(Material.AMETHYST_BLOCK, Material.BUDDING_AMETHYST,
            Material.AMETHYST_CLUSTER, Material.LARGE_AMETHYST_BUD, Material.MEDIUM_AMETHYST_BUD,
            Material.SMALL_AMETHYST_BUD);

    /** Display entities this ability keeps alive at most at once (optional extras fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 2.0;

    // passive
    private static final long RESONANCE_PERIOD = 10;
    private static final long BLOCK_SCAN_PERIOD = 20;
    /** How far above and below the feet the world is searched for amethyst. */
    private static final int SCAN_HEIGHT = 2;
    private static final int MAX_SHARDS = 6;
    private static final long SHARD_SHRINK_TICKS = 5;

    // active
    /** The first spike rises this far in front of the owner. */
    private static final double SPIKE_START = 1.3;
    private static final double SPIKE_STEP = 1.0;
    private static final float SPIKE_WIDTH = 0.95f;
    private static final float SPIKE_HEIGHT = 1.55f;
    private static final long SPIKE_RISE = 2;
    private static final long SPIKE_HOLD = 7;
    private static final long SPIKE_SINK = 4;
    /** Every n-th spike (and the last one) stays behind as a resonating shard. */
    private static final int SHARD_EVERY = 4;
    private static final float SHARD_SCALE = 0.62f;
    private static final double SPIKE_PUSH = 0.18;

    // ultimate
    private static final int CAGE_CLUSTERS = 6;
    private static final double CAGE_RADIUS = 0.72;
    private static final float CAGE_HEIGHT = 2.25f;
    private static final float CAGE_WIDTH = 0.9f;
    private static final float CAGE_LEAN = 22f;
    private static final float SHELL_WIDTH = 1.45f;
    private static final float SHELL_HEIGHT = 2.45f;
    private static final int CAGE_BUDS = 3;
    private static final long CAGE_GROW = 5;
    /** The cage starts to shiver this long before it shatters. */
    private static final long SHIVER_TICKS = 12;
    /** A prisoner farther than this from its cell broke out (teleport): the cage shatters at once. */
    private static final double BREAKOUT_DISTANCE = 3.0;
    /** A prisoner drifting farther than this is put back. */
    private static final double HOLD_SLACK = 0.35;
    /** A prisoner this close above the ground (mid-jump) is pinned down onto it. */
    private static final double ANCHOR_SNAP = 1.25;
    /** The cell's ground is searched from this far above the prisoner's feet, which may be a hair in the floor. */
    private static final double ANCHOR_LIFT = 0.2;
    /** On the first tick the prisoner is set onto the cell floor unless it already stands there. */
    private static final double PLACE_EPSILON = 1.0E-4;
    private static final int FLYING_SHARDS = 6;
    private static final double SHATTER_PUSH = 0.9;
    private static final double SHATTER_LIFT = 0.45;
    private static final double PRISONER_POP = 0.4;
    private static final int RESIDUE_SHARDS = 3;

    // passive state
    private final List<Shard> shards = new ArrayList<>();
    private @Nullable Location amethystNearby;
    private boolean resonating;
    /** Prisons of this instance still holding someone, released when the owner's ability is revoked. */
    private final List<Prison> prisons = new ArrayList<>();
    /** Display entities spawned by this instance (pruned when counted). */
    private final List<Display> live = new ArrayList<>();

    /** A crystal left behind by a skill: it resonates with the owner until it fades. */
    private static final class Shard {
        final Location at;
        final @Nullable BlockDisplay display;
        final long until;
        boolean faded;

        Shard(Location at, @Nullable BlockDisplay display, long until) {
            this.at = at;
            this.display = display;
            this.until = until;
        }
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        // The cage disappears with the owner: its prisoner is let go at once instead of when the root runs out.
        for (Prison prison : List.copyOf(prisons)) {
            prison.unroot();
        }
        prisons.clear();
        shards.clear();
        live.clear();
        resonating = false;
    }

    // ------------------------------------------------------------------ passive: resonance

    @Override
    public void onTick(long tick) {
        if (tick % BLOCK_SCAN_PERIOD == 0) {
            amethystNearby = scanAmethyst();
        }
        if (tick % RESONANCE_PERIOD != 0) {
            return;
        }
        long now = ctx().currentTick();
        fadeShards(now);
        Player owner = player();
        Location source = resonanceSource();
        boolean cooling = ctx().cooldownRemaining(owner) > 0;
        boolean was = resonating;
        resonating = source != null && cooling;
        if (!resonating) {
            return;
        }
        double bonus = Math.max(0, params().num("resonance_bonus")) / 100.0;
        ctx().reduceCooldown(owner, RESONANCE_PERIOD / 20.0 * bonus);
        resonanceFx(owner, source, tick, !was);
    }

    /** The nearest crystal in reach: one of the owner's shards, else amethyst found in the world. Null if none. */
    private @Nullable Location resonanceSource() {
        Location feet = player().getLocation();
        double radius = Math.max(0, params().num("resonance_radius"));
        Location best = null;
        double bestDistance = radius * radius;
        for (Shard shard : shards) {
            if (shard.faded || !shard.at.getWorld().equals(feet.getWorld())) {
                continue;
            }
            double distance = shard.at.distanceSquared(feet);
            if (distance <= bestDistance) {
                best = shard.at;
                bestDistance = distance;
            }
        }
        if (best == null && amethystNearby != null && amethystNearby.getWorld().equals(feet.getWorld())
                && amethystNearby.distanceSquared(feet) <= (radius + 0.5) * (radius + 0.5)) {
            best = amethystNearby;
        }
        return best;
    }

    /** The nearest amethyst block around the owner (block center), null if none. */
    private @Nullable Location scanAmethyst() {
        Player owner = player();
        Location feet = owner.getLocation();
        World world = owner.getWorld();
        int reach = (int) Math.ceil(Math.max(0, params().num("resonance_radius")));
        int fx = feet.getBlockX();
        int fy = feet.getBlockY();
        int fz = feet.getBlockZ();
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = Math.max(world.getMinHeight(), fy - SCAN_HEIGHT);
             y <= Math.min(world.getMaxHeight() - 1, fy + SCAN_HEIGHT); y++) {
            for (int x = fx - reach; x <= fx + reach; x++) {
                for (int z = fz - reach; z <= fz + reach; z++) {
                    if (!AMETHYSTS.contains(world.getBlockAt(x, y, z).getType())) {
                        continue;
                    }
                    Location center = new Location(world, x + 0.5, y + 0.5, z + 0.5);
                    double distance = center.distanceSquared(feet);
                    if (distance < bestDistance) {
                        best = center;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    /** Lilac motes stream from the crystal into the owner, with a soft chime now and then. */
    private void resonanceFx(Player owner, Location source, long tick, boolean started) {
        Location chest = Fx.body(owner);
        Location from = source.clone().add(0, 0.4, 0);
        Vfx.trail(from, chest, PALETTE.body(), 8, false);
        Vfx.trail(from.clone().add(Fx.jitter(0.2f), 0.2, Fx.jitter(0.2f)), chest, PINK, 10, false);
        Vfx.ring(ctx(), owner.getLocation().add(0, 0.08, 0), 0.55, 8, tick * 0.2, Particle.DUST, LILAC_SMALL, false);
        Vfx.dot(from.clone().add(0, 0.4, 0), Particle.END_ROD, null, false);
        if (started || tick % (RESONANCE_PERIOD * 3) == 0) {
            Fx.sound(source, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.9f + ThreadLocalRandom.current().nextFloat() * 0.8f);
        }
        if (started) {
            Fx.sound(chest, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 1.4f + Fx.jitter(0.1f));
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">공명 <white>쿨타임 가속</white></" + COLOR_TAG + ">"));
        }
        for (Shard shard : shards) {
            BlockDisplay display = shard.display;
            if (shard.at.equals(source) && display != null && display.isValid()) {
                // The resonating shard pulses.
                Displays.animate(display, shardPose(SHARD_SCALE * 1.18f, shard.at.getYaw()), 3);
                ctx().runLater(owner, 4, () -> Displays.animate(display, shardPose(SHARD_SCALE, shard.at.getYaw()), 5));
            }
        }
    }

    /** Fades shards whose time is up, sparkling the others. */
    private void fadeShards(long now) {
        Iterator<Shard> iterator = shards.iterator();
        while (iterator.hasNext()) {
            Shard shard = iterator.next();
            BlockDisplay display = shard.display;
            if (shard.faded || now >= shard.until || display != null && !display.isValid()) {
                fade(shard);
                iterator.remove();
            } else if (ThreadLocalRandom.current().nextInt(3) == 0) {
                Location sparkle = shard.at.clone().add(Fx.jitter(0.3f), 0.3 + ThreadLocalRandom.current()
                        .nextDouble(0.6), Fx.jitter(0.3f));
                Vfx.dot(sparkle, Particle.WAX_OFF, null, false);
            }
        }
    }

    private void fade(Shard shard) {
        if (shard.faded) {
            return;
        }
        shard.faded = true;
        BlockDisplay display = shard.display;
        if (display != null && display.isValid()) {
            Displays.shrinkAway(ctx(), player(), display, (int) SHARD_SHRINK_TICKS);
            Location at = shard.at.clone().add(0, 0.3, 0);
            at.getWorld().spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 4), 0.2, 0.2, 0.2, 0, LILAC_SMALL);
            Fx.sound(at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.4f, 1.6f + Fx.jitter(0.1f));
        }
    }

    /**
     * Leaves a resonating crystal at {@code at} (ground) for {@code shard_duration}: either {@code display} (already
     * spawned, it is posed as a shard) or a new small cluster. The oldest shard fades when there are too many.
     */
    private void leaveShard(Location at, @Nullable BlockDisplay display) {
        long duration = Math.max(1, params().ticks("shard_duration"));
        while (shards.size() >= MAX_SHARDS) {
            fade(shards.removeFirst());
        }
        float yaw = ThreadLocalRandom.current().nextFloat() * 90f;
        Location spot = flat(at);
        spot.setYaw(yaw);
        BlockDisplay crystal = display;
        if (crystal == null && roomFor(1)) {
            crystal = keep(Displays.block(ctx(), player(), flat(at), cluster(), shardPose(0.05f, yaw),
                    shardLifetime()));
        }
        if (crystal != null) {
            Displays.animateNextTick(ctx(), player(), crystal, shardPose(SHARD_SCALE, yaw), 4);
        }
        Shard shard = new Shard(spot, crystal, ctx().currentTick() + duration);
        shards.add(shard);
        Location glint = spot.clone().add(0, 0.5, 0);
        Vfx.dot(glint, Particle.END_ROD, null, false);
        Fx.sound(glint, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.2f + Fx.jitter(0.2f));
        // Faded by its own timer, so shards also fade on an instance that never ticks (a borrowed ultimate).
        ctx().runLater(player(), duration, () -> fade(shard));
    }

    /** How long a shard's display may exist: its duration plus the fade, with some slack. */
    private long shardLifetime() {
        return Math.max(1, params().ticks("shard_duration")) + SHARD_SHRINK_TICKS + 2;
    }

    private static Transformation shardPose(float scale, float yaw) {
        float height = SPIKE_HEIGHT * scale;
        return Displays.transform(0, height / 2 - 0.05f, 0, SPIKE_WIDTH * scale, height, SPIKE_WIDTH * scale, yaw, 0,
                0);
    }

    // ------------------------------------------------------------------ active: crystal spikes

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location feet = owner.getLocation();
        Double ground = groundBelow(feet.clone().add(0, 0.3, 0), 4);
        if (ground == null) {
            return ActivationResult.fail("가시가 솟을 땅이 없습니다");
        }
        Vector direction = Motion.horizontalFacing(feet);
        SpikeLine line = new SpikeLine(feet, direction, ground);
        line.cast();
        Loop.repeat(ctx(), owner, 1, 1, line.steps, line, line::finish);
        return ActivationResult.success();
    }

    /** One erupted spike: its display (if within budget), where it stands and when it sinks. */
    private record Spike(@Nullable BlockDisplay display, @Nullable BlockDisplay chunk, Location at, int index) {
    }

    /**
     * The spike line: one amethyst spike per tick, following the ground (up one-block steps, down small drops) and
     * stopping at walls, gaps and the arena border. Every enemy it reaches is hurt and launched once.
     */
    private final class SpikeLine implements LongPredicate {

        private final Location origin;
        private final Vector direction;
        private final World world;
        private final int steps;
        private final double width;
        private final Set<UUID> struck = new HashSet<>();
        private final List<Spike> spikes = new ArrayList<>();
        private double floor;
        private boolean stopped;
        private int hits;

        SpikeLine(Location feet, Vector direction, double floor) {
            this.origin = flat(feet);
            this.direction = direction;
            this.world = feet.getWorld();
            this.floor = floor;
            this.width = Math.max(0.3, params().num("spike_width"));
            this.steps = Math.max(1, (int) Math.round((params().num("spike_length") - SPIKE_START) / SPIKE_STEP) + 1);
        }

        void cast() {
            Player owner = player();
            Location at = origin.clone().add(0, 0.1, 0);
            at.setY(floor + 0.1);
            Vfx.arc(ctx(), at.clone().add(direction.clone().multiply(0.6)), 0.9, owner.getLocation().getYaw(), 120, 12,
                    Particle.DUST_COLOR_TRANSITION, GLOW, false);
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 10), 0.4, 0.05, 0.4, 0.1, amethyst());
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.6f, 1.4f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 1.0f, 0.6f);
        }

        @Override
        public boolean test(long tick) {
            if (stopped) {
                return false;
            }
            int index = (int) tick;
            Location point = origin.clone().add(direction.clone().multiply(SPIKE_START + index * SPIKE_STEP));
            Double top = stepGround(point, floor);
            if (top == null || !ctx().isInsideArena(point)) {
                stopped = true;
                blocked(point);
                return false;
            }
            floor = top;
            point.setY(top);
            erupt(point, index);
            crack(point);
            return true;
        }

        /** A spike bursts out of the ground; enemies around it are struck and launched. */
        private void erupt(Location point, int index) {
            Player owner = player();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            float yaw = random.nextFloat() * 90f;
            float tilt = (index % 2 == 0 ? 1 : -1) * (6f + random.nextFloat() * 8f);
            float height = SPIKE_HEIGHT * (0.85f + random.nextFloat() * 0.3f);
            // Long enough to live on as a shard; spikes that sink are removed by their shrink much earlier.
            long lifetime = SPIKE_RISE + SPIKE_HOLD + Math.max(SPIKE_SINK, shardLifetime()) + 3;
            BlockDisplay spike = null;
            BlockDisplay chunk = null;
            if (roomFor(1)) {
                spike = keep(Displays.block(ctx(), owner, point, cluster(),
                        spikePose(SPIKE_WIDTH, 0.05f, yaw, tilt), lifetime));
                Displays.animateNextTick(ctx(), owner, spike, spikePose(SPIKE_WIDTH, height, yaw, tilt),
                        (int) SPIKE_RISE);
            }
            if (index % 2 == 1 && roomFor(1)) {
                float chunkYaw = yaw + 45f;
                chunk = keep(Displays.block(ctx(), owner, point, amethyst(),
                        Displays.transform(0, 0, 0, 0.05f, 0.05f, 0.05f, chunkYaw, 30f, 20f), lifetime));
                Displays.animateNextTick(ctx(), owner, chunk,
                        Displays.transform(0, 0.12f, 0, 0.42f, 0.42f, 0.42f, chunkYaw, 30f, 20f), (int) SPIKE_RISE);
            }
            Spike made = new Spike(spike, chunk, point.clone(), index);
            spikes.add(made);

            Location base = point.clone().add(0, 0.1, 0);
            Location tip = point.clone().add(0, height, 0);
            world.spawnParticle(Particle.BLOCK, base, Vfx.n(ctx(), 6), 0.3, 0.05, 0.3, 0.1, amethyst());
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, base.clone().add(0, 0.5, 0), Vfx.n(ctx(), 5), 0.25, 0.4,
                    0.25, 0, GLOW_SMALL);
            Vfx.dot(tip, Particle.END_ROD, null, false);
            world.spawnParticle(Particle.ELECTRIC_SPARK, tip, Vfx.n(ctx(), 2), 0.15, 0.15, 0.15, 0.05);
            Fx.sound(base, Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 0.9f, (float) Math.min(2.0, 0.8 + 0.08 * index));
            if (index % 2 == 0) {
                Fx.sound(base, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.8f, (float) Math.min(2.0, 0.9 + 0.07 * index));
            }

            for (LivingEntity enemy : ctx().enemiesNear(owner, point.clone().add(0, 0.7, 0), width)) {
                if (!struck.add(enemy.getUniqueId())) {
                    continue;
                }
                strike(enemy, point);
            }
            // The spike sinks again after a moment (or turns into a shard).
            ctx().runLater(owner, SPIKE_RISE + SPIKE_HOLD, () -> settle(made));
        }

        private void strike(LivingEntity enemy, Location point) {
            Player owner = player();
            Combat.hit(ctx(), owner, enemy, params().num("spike_damage"));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            hits++;
            double lift = Motion.launchSpeed(Math.max(0, params().num("spike_lift")));
            Motion.push(enemy, direction.clone().multiply(SPIKE_PUSH).setY(lift), MAX_SPEED);
            Location body = Fx.body(enemy);
            Vfx.dot(body, Particle.FLASH, PINK, false);
            Vfx.sphere(ctx(), body, 0.6, 14, Particle.DUST_COLOR_TRANSITION, BLUSH, false);
            world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 10), 0.3, 0.4, 0.3, 0.15, amethyst());
            world.spawnParticle(Particle.CRIT, body, Vfx.n(ctx(), 6), 0.3, 0.4, 0.3, 0.2);
            Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0f, 0.9f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.2f + Fx.jitter(0.1f));
            Vfx.line(ctx(), point.clone().add(0, 0.2, 0), body, 4, Particle.DUST, PINK_DUST, false);
        }

        /** The crack running one step ahead of the spikes: the line's telegraph. */
        private void crack(Location point) {
            Location ahead = point.clone().add(direction.clone().multiply(SPIKE_STEP)).add(0, 0.08, 0);
            ahead.setY(point.getY() + 0.08);
            Vfx.line(ctx(), point.clone().add(0, 0.08, 0), ahead, 4, Particle.DUST, LILAC_SMALL, false);
            Vfx.dot(ahead, Particle.WAX_OFF, null, false);
        }

        /** A wall (or gap, or the border) stops the line: crystal splinters burst against it. */
        private void blocked(Location point) {
            Location at = point.clone();
            at.setY(floor + 0.6);
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 12), 0.3, 0.4, 0.3, 0.15, amethyst());
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, Vfx.n(ctx(), 8), 0.3, 0.4, 0.3, 0, FADE);
            Fx.sound(at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.9f, 0.7f + Fx.jitter(0.1f));
        }

        /** After its hold a spike sinks back, unless it is chosen to stay as a resonating shard. */
        private void settle(Spike spike) {
            Player owner = player();
            boolean last = spike.index() == lastIndex();
            boolean stays = spike.index() % SHARD_EVERY == SHARD_EVERY - 1 || last;
            BlockDisplay chunk = spike.chunk();
            if (chunk != null && chunk.isValid()) {
                Displays.shrinkAway(ctx(), owner, chunk, (int) SPIKE_SINK);
            }
            BlockDisplay display = spike.display();
            if (stays) {
                leaveShard(spike.at(), display != null && display.isValid() ? display : null);
                return;
            }
            if (display != null && display.isValid()) {
                Displays.shrinkAway(ctx(), owner, display, (int) SPIKE_SINK);
            }
            Vfx.dot(spike.at().clone().add(0, 0.3, 0), Particle.DUST, LILAC_SMALL, false);
        }

        private int lastIndex() {
            return spikes.isEmpty() ? -1 : spikes.getLast().index();
        }

        /** The loop ended: the last spike flares, and the owner learns how many it struck. */
        void finish() {
            Player owner = player();
            if (!spikes.isEmpty()) {
                Location end = spikes.getLast().at().clone().add(0, 1.0, 0);
                Vfx.burst(ctx(), end, PALETTE, 0.8, Particle.END_ROD, false);
                Fx.sound(end, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.5f + Fx.jitter(0.1f));
            }
            if (hits > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">크리스탈 가시 <white>" + hits + "명 적중!</white></"
                        + COLOR_TAG + ">"));
            }
        }
    }

    /**
     * The ground at the next spike position: the top of the first solid block with room above, at most one block
     * higher than {@code previous} (a step) and at most three lower. Null for a wall or a gap.
     */
    private static @Nullable Double stepGround(Location point, double previous) {
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int top = (int) Math.floor(previous + 0.01);
        for (int y = Math.min(top + 1, world.getMaxHeight() - 2); y >= Math.max(world.getMinHeight(), top - 3); y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable() && block.getRelative(BlockFace.UP).isPassable()) {
                double surface = block.getBoundingBox().getMaxY();
                return surface <= previous + 1.01 ? surface : null;
            }
        }
        return null;
    }

    /** A spike standing on the origin: width, height, turned {@code yaw}, leaning {@code tilt} degrees. */
    private static Transformation spikePose(float width, float height, float yaw, float tilt) {
        return Displays.transform(0, height / 2 - 0.08f, 0, width, height, width, yaw, tilt, tilt * 0.4f);
    }

    // ------------------------------------------------------------------ ultimate: crystal prison

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        LivingEntity target = ctx().findTarget(owner, params().num("prison_range"));
        if (target == null || !seen(owner, target)) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        Prison prison = new Prison(target);
        prisons.add(prison);
        prison.cast();
        Loop.repeat(ctx(), owner, 1, 1, prison.hold + 1, prison, prison::release);
        return ActivationResult.success();
    }

    /** One cluster of the cage: its display and the compass angle it stands at. */
    private record Bar(BlockDisplay display, float angle, float height) {
    }

    /**
     * The crystal prison: the target is held in place (no walking, jumping or falling) while clusters grow around it
     * inside a glass shell; after {@code prison_duration} the cage shivers and shatters, hurting the prisoner and
     * everyone around it. It shatters early if the prisoner dies or breaks out (a teleport).
     */
    private final class Prison implements LongPredicate {

        private final LivingEntity prisoner;
        private final Location anchor;
        private final Location cell;
        private final World world;
        private final long hold;
        private final List<Bar> bars = new ArrayList<>();
        private final List<BlockDisplay> extras = new ArrayList<>();
        private final List<NamespacedKey> keys = new ArrayList<>();
        private @Nullable BlockDisplay shell;
        private boolean shattered;

        Prison(LivingEntity prisoner) {
            this.prisoner = prisoner;
            // The cell stands on the ground: a prisoner caught mid-jump is pinned down onto it, and a server
            // position a hair inside the floor is lifted onto the surface (never holding a body in the ground).
            Location at = prisoner.getLocation();
            Location probe = at.clone().add(0, ANCHOR_LIFT, 0);
            Spots.snapToGround(probe.getWorld(), probe, ANCHOR_SNAP + ANCHOR_LIFT);
            if (probe.getY() < at.getY() + ANCHOR_LIFT) {
                at.setY(probe.getY());
            }
            this.anchor = at;
            this.cell = flat(anchor);
            this.world = anchor.getWorld();
            this.hold = Math.max(1, params().ticks("prison_duration"));
        }

        void cast() {
            Player owner = player();
            // Rooted at once: no walking, jumping or falling until the cage breaks.
            long lease = hold + 2;
            keys.add(ctx().addModifier(owner, prisoner, Attribute.MOVEMENT_SPEED, -1.0,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1, lease));
            keys.add(ctx().addModifier(owner, prisoner, Attribute.JUMP_STRENGTH, -1.0,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1, lease));
            keys.add(ctx().addModifier(owner, prisoner, Attribute.GRAVITY, -1.0,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1, lease));
            prisoner.setVelocity(new Vector());
            prisoner.setFallDistance(0);

            Location eye = owner.getEyeLocation();
            Location hand = eye.clone().add(eye.getDirection().multiply(0.55)).subtract(0, 0.3, 0);
            Location body = Fx.body(prisoner);
            Vfx.line(ctx(), hand, body, 3, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, true);
            Vfx.trail(hand, body, PINK, 6, true);
            Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.END_ROD, true);
            Fx.sound(eye, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.6f);
            Fx.sound(body, Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 1.4f, 0.5f);
            Fx.sound(body, Sound.BLOCK_BEACON_POWER_SELECT, 0.9f, 1.6f);
            growCage();
        }

        /** Clusters grow one after another around the prisoner, then the shell closes, then the crown drops in. */
        private void growCage() {
            Player owner = player();
            long lifetime = hold + 16;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            float phase = random.nextFloat() * 60f;
            for (int i = 0; i < CAGE_CLUSTERS; i++) {
                float pitch = 0.9f + 0.1f * i;
                float angle = phase + i * 360f / CAGE_CLUSTERS;
                float height = CAGE_HEIGHT * (0.88f + random.nextFloat() * 0.24f);
                BlockDisplay bar = keep(Displays.block(ctx(), owner, cell, cluster(), barPose(angle, 0.05f, 0), lifetime));
                bar.setViewRange(2.0f);
                bars.add(new Bar(bar, angle, height));
                ctx().runLater(owner, 1 + i, () -> {
                    Displays.animate(bar, barPose(angle, height, 0), (int) CAGE_GROW);
                    Location foot = cell.clone().add(-Math.sin(Math.toRadians(angle)) * CAGE_RADIUS, 0.1,
                            Math.cos(Math.toRadians(angle)) * CAGE_RADIUS);
                    world.spawnParticle(Particle.BLOCK, foot, Vfx.n(ctx(), 5), 0.15, 0.05, 0.15, 0.1, amethyst(),
                            true);
                    Fx.sound(foot, Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 0.9f, pitch);
                });
            }
            shell = keep(Displays.block(ctx(), owner, cell, glass(), shellPose(0.05f, 0), lifetime));
            shell.setViewRange(2.0f);
            Displays.outline(shell, PINK);
            BlockDisplay glass = shell;
            ctx().runLater(owner, CAGE_GROW, () -> {
                Displays.animate(glass, shellPose(1f, 0), (int) CAGE_GROW);
                Fx.sound(cell, Sound.BLOCK_GLASS_PLACE, 1.0f, 1.4f);
            });
            if (roomFor(1)) {
                BlockDisplay crown = keep(Displays.block(ctx(), owner, cell, hangingCluster(),
                        Displays.transform(0, SHELL_HEIGHT + 0.6f, 0, 0.05f, 0.05f, 0.05f, phase, 0, 0), lifetime));
                extras.add(crown);
                ctx().runLater(owner, CAGE_GROW + 2, () -> Displays.animate(crown,
                        Displays.transform(0, SHELL_HEIGHT - 0.1f, 0, 1.1f, 1.1f, 1.1f, phase + 45f, 0, 0),
                        (int) CAGE_GROW));
            }
            for (int i = 0; i < CAGE_BUDS && roomFor(1); i++) {
                float angle = phase + 30f + i * 120f;
                double radians = Math.toRadians(angle);
                Location at = cell.clone().add(-Math.sin(radians) * 1.35, 0, Math.cos(radians) * 1.35);
                Double ground = groundBelow(at.clone().add(0, 0.8, 0), 3);
                if (ground != null) {
                    at.setY(ground);
                }
                BlockDisplay bud = keep(Displays.block(ctx(), owner, at, budData(),
                        Displays.transform(0, 0.02f, 0, 0.05f, 0.05f, 0.05f, angle, 0, 0), lifetime));
                extras.add(bud);
                Displays.animateNextTick(ctx(), owner, bud,
                        Displays.transform(0, 0.3f, 0, 0.75f, 0.75f, 0.75f, angle, -18f, 0), (int) CAGE_GROW + 2);
            }
        }

        @Override
        public boolean test(long tick) {
            if (shattered) {
                return false;
            }
            if (!Combat.isTargetable(ctx(), player(), prisoner) || !prisoner.getWorld().equals(world)
                    || prisoner.getLocation().distance(anchor) > BREAKOUT_DISTANCE) {
                shatter();
                return false;
            }
            if (tick >= hold) {
                shatter();
                return false;
            }
            holdPrisoner(tick == 0);
            shimmer(tick);
            if (hold - tick <= SHIVER_TICKS) {
                shiver(tick);
            }
            return true;
        }

        /**
         * Keeps the prisoner in its cell: no velocity; on the first tick it is set exactly onto the cell floor, later
         * it is put back when something shoved it. Only ever onto a safe spot (it stood there a moment ago).
         */
        private void holdPrisoner(boolean first) {
            prisoner.setVelocity(new Vector());
            prisoner.setFallDistance(0);
            Location at = prisoner.getLocation();
            double slack = first ? PLACE_EPSILON : HOLD_SLACK;
            if (at.distanceSquared(anchor) > slack * slack) {
                Location back = anchor.clone();
                back.setYaw(at.getYaw());
                back.setPitch(at.getPitch());
                if (Spots.isSafe(ctx(), back, Body.standing(prisoner))) {
                    prisoner.teleport(back);
                }
            }
        }

        /** Light glints over the shell, sparks crack through the crystal. */
        private void shimmer(long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            if (tick % 2 == 0) {
                for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                    double angle = random.nextDouble(Math.PI * 2);
                    Location at = cell.clone().add(Math.cos(angle) * SHELL_WIDTH * 0.55,
                            random.nextDouble(SHELL_HEIGHT), Math.sin(angle) * SHELL_WIDTH * 0.55);
                    Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, i == 0 ? BLUSH : GLOW_SMALL, true);
                }
            }
            if (tick % 6 == 3) {
                Location spark = cell.clone().add(Fx.jitter(0.6f), 0.3 + random.nextDouble(SHELL_HEIGHT - 0.5),
                        Fx.jitter(0.6f));
                world.spawnParticle(Particle.ELECTRIC_SPARK, spark, Vfx.n(ctx(), 3), 0.1, 0.1, 0.1, 0.05, null, true);
                Vfx.dot(spark, Particle.WAX_OFF, null, true);
                Fx.sound(spark, Sound.BLOCK_AMETHYST_CLUSTER_HIT, 0.8f, (float) Math.min(2.0, 0.8 + 1.0 * tick / hold));
            }
            if (tick % 8 == 0) {
                Vfx.ring(ctx(), cell.clone().add(0, 0.06, 0), 1.0, 14, tick * 0.15, Particle.DUST, LILAC, true);
            }
        }

        /** The cage shivers and a ring closes in on the shatter radius: it is about to burst. */
        private void shiver(long tick) {
            long left = hold - tick;
            if (tick % 2 == 0) {
                for (Bar bar : bars) {
                    if (bar.display().isValid()) {
                        Displays.animate(bar.display(), barPose(bar.angle(), bar.height(), Fx.jitter(5f)), 2);
                    }
                }
                if (shell != null && shell.isValid()) {
                    Displays.animate(shell, shellPose(1f + 0.03f * (SHIVER_TICKS - left) / SHIVER_TICKS, Fx.jitter(4f)),
                            2);
                }
            }
            double radius = Math.max(0.5, params().num("shatter_radius"));
            double closing = radius * left / SHIVER_TICKS + 0.6;
            Vfx.ring(ctx(), cell.clone().add(0, 0.1, 0), closing, (int) Math.max(12, closing * 6), tick * 0.3,
                    Particle.DUST_COLOR_TRANSITION, GLOW, true);
            if (left % 4 == 0) {
                Fx.sound(cell, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f,
                        (float) Math.min(2.0, 1.0 + 0.08 * (SHIVER_TICKS - left)));
            }
        }

        /** The cage bursts: the prisoner and everyone around are hurt, crystal flies everywhere. */
        private void shatter() {
            if (shattered) {
                return;
            }
            shattered = true;
            Player owner = player();
            unroot();

            Location center = cell.clone().add(0, 1.0, 0);
            double radius = Math.max(0.5, params().num("shatter_radius"));
            flingBars();
            Vfx.dot(center, Particle.FLASH, PINK, true);
            Vfx.sphere(ctx(), center, 1.0, 30, Particle.DUST_COLOR_TRANSITION, BLUSH, true);
            world.spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 40), 0.6, 0.9, 0.6, 0.3, amethyst(), true);
            world.spawnParticle(Particle.END_ROD, center, Vfx.n(ctx(), 20), 0.3, 0.5, 0.3, 0.25, null, true);
            world.spawnParticle(Particle.ELECTRIC_SPARK, center, Vfx.n(ctx(), 16), 0.5, 0.8, 0.5, 0.3, null, true);
            Vfx.shockwave(ctx(), owner, cell.clone().add(0, 0.12, 0), 0.6, radius, 6, Particle.DUST_COLOR_TRANSITION,
                    GLOW, true);
            Loop.repeat(ctx(), owner, 1, 1, 4, run -> {
                double r = 0.8 + (radius * 0.7 - 0.8) * (run + 1) / 4.0;
                Vfx.sphere(ctx(), center, r, 28, Particle.DUST_COLOR_TRANSITION, FADE, true);
                return true;
            });
            Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.6f, 0.6f);
            Fx.sound(center, Sound.BLOCK_GLASS_BREAK, 1.4f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.4f, 1.0f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f + Fx.jitter(0.05f));

            int hits = 0;
            boolean caught = Combat.isTargetable(ctx(), owner, prisoner) && prisoner.getWorld().equals(world)
                    && prisoner.getLocation().distance(anchor) <= BREAKOUT_DISTANCE;
            if (caught) {
                Combat.hit(ctx(), owner, prisoner, params().num("shatter_damage"));
                if (Combat.isTargetable(ctx(), owner, prisoner)) {
                    hits++;
                    Motion.push(prisoner, new Vector(0, PRISONER_POP, 0), MAX_SPEED);
                }
            }
            double splash = params().num("splash_damage");
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                if (enemy.equals(prisoner)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, splash);
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                hits++;
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(cell.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                    away = new Vector(Math.cos(angle), 0, Math.sin(angle));
                }
                Motion.push(enemy, away.multiply(SHATTER_PUSH).setY(SHATTER_LIFT), MAX_SPEED);
                Vfx.trail(center, Fx.body(enemy), PINK, 5, true);
            }
            residue();
            if (hits > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">크리스탈 감옥 <white>산산조각! " + hits + "명 적중</white></"
                        + COLOR_TAG + ">"));
            }
        }

        /** The clusters are flung outwards tumbling, the shell bursts, shards fly. */
        private void flingBars() {
            Player owner = player();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (Bar bar : bars) {
                BlockDisplay display = bar.display();
                if (!display.isValid()) {
                    continue;
                }
                double radians = Math.toRadians(bar.angle());
                Vector out = new Vector(-Math.sin(radians), 0, Math.cos(radians)).multiply(2.2 + random.nextDouble(1.2))
                        .setY(0.4 + random.nextDouble(0.8));
                display.setTeleportDuration(6);
                Displays.moveTo(display, display.getLocation().add(out));
                Displays.animate(display, barPose(bar.angle(), bar.height() * 0.6f, 70f + random.nextFloat() * 40f), 6);
                ctx().runLater(owner, 6, () -> {
                    if (display.isValid()) {
                        Displays.shrinkAway(ctx(), owner, display, 4);
                    }
                });
            }
            BlockDisplay glass = shell;
            if (glass != null && glass.isValid()) {
                Displays.animate(glass, shellPose(1.7f, 20f), 3);
                ctx().runLater(owner, 3, () -> {
                    if (glass.isValid()) {
                        Displays.shrinkAway(ctx(), owner, glass, 3);
                    }
                });
            }
            for (BlockDisplay extra : extras) {
                if (extra.isValid()) {
                    Displays.shrinkAway(ctx(), owner, extra, 4);
                }
            }
            Location center = cell.clone().add(0, 1.2, 0);
            for (int i = 0; i < FLYING_SHARDS && roomFor(1); i++) {
                double angle = i * Math.PI * 2 / FLYING_SHARDS + random.nextDouble(0.5);
                ItemDisplay shard = keep(Displays.item(ctx(), owner, center, ItemStack.of(SHARD_ITEM),
                        Displays.centered(0, 0, 0, 0.6f, 0.6f, 0.6f, random.nextFloat() * 360f, 0, 0), 16));
                shard.setTeleportDuration(8);
                Vector out = new Vector(Math.cos(angle), 0, Math.sin(angle)).multiply(2.8 + random.nextDouble(1.5))
                        .setY(-0.6 + random.nextDouble(1.2));
                ctx().runLater(owner, 1, () -> {
                    if (!shard.isValid()) {
                        return;
                    }
                    Displays.moveTo(shard, center.clone().add(out));
                    Displays.animate(shard, Displays.centered(0, 0, 0, 0.6f, 0.6f, 0.6f, random.nextFloat() * 360f,
                            random.nextFloat() * 360f, random.nextFloat() * 360f), 8);
                });
                ctx().runLater(owner, 9, () -> {
                    if (shard.isValid()) {
                        Displays.shrinkAway(ctx(), owner, shard, 5);
                    }
                });
            }
        }

        /** A few crystals are left where the prison stood: they resonate with the owner. */
        private void residue() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double phase = random.nextDouble(Math.PI * 2);
            for (int i = 0; i < RESIDUE_SHARDS; i++) {
                double angle = phase + i * Math.PI * 2 / RESIDUE_SHARDS;
                Location at = cell.clone().add(Math.cos(angle) * 0.9, 0, Math.sin(angle) * 0.9);
                Double ground = groundBelow(at.clone().add(0, 0.8, 0), 4);
                if (ground == null) {
                    continue;
                }
                at.setY(ground);
                Location spot = at;
                ctx().runLater(player(), 6 + i * 2L, () -> leaveShard(spot, null));
            }
        }

        /** The loop ended: make sure the cage broke (the shatter normally ran already) and the root is lifted. */
        void release() {
            shatter();
        }

        /** Lifts the root (the modifiers would also run out on their own shortly after the prison's time). */
        void unroot() {
            for (NamespacedKey key : keys) {
                ctx().removeModifier(prisoner, key);
            }
            keys.clear();
            prisons.remove(this);
        }
    }

    /**
     * A cage cluster standing at compass angle {@code angle} around the cell, its foot on the ground and its top
     * leaning in over the prisoner.
     */
    private static Transformation barPose(float angle, float height, float jitter) {
        double lean = Math.toRadians(-CAGE_LEAN);
        double centerRadius = CAGE_RADIUS + height / 2 * Math.sin(lean);
        double centerY = height / 2 * Math.cos(lean) - 0.05;
        double radians = Math.toRadians(angle);
        float x = (float) (-Math.sin(radians) * centerRadius);
        float z = (float) (Math.cos(radians) * centerRadius);
        return Displays.transform(x, (float) centerY, z, CAGE_WIDTH, height, CAGE_WIDTH, angle + jitter,
                -CAGE_LEAN + jitter, 0);
    }

    private static Transformation shellPose(float grow, float wobble) {
        return Displays.transform(0, SHELL_HEIGHT * grow / 2 - 0.05f, 0, SHELL_WIDTH * grow, SHELL_HEIGHT * grow,
                SHELL_WIDTH * grow, 45f + wobble, 0, 0);
    }

    // ------------------------------------------------------------------ helpers

    /** The top of the first solid block (with room above it) at most {@code depth} blocks below {@code point}. */
    private static @Nullable Double groundBelow(Location point, int depth) {
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int start = Math.min(point.getBlockY(), world.getMaxHeight() - 2);
        int end = Math.max(world.getMinHeight(), start - depth);
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable() && block.getRelative(BlockFace.UP).isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /** False for an enemy player hidden from the owner by stealth. */
    private static boolean seen(Player owner, LivingEntity enemy) {
        return !(enemy instanceof Player hidden) || owner.canSee(hidden);
    }

    /** True if {@code count} more displays fit in {@link #MAX_DISPLAYS}. */
    private boolean roomFor(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    private static BlockData cluster() {
        return CLUSTER.createBlockData();
    }

    /** A cluster pointing down, hanging from the top of the cage. */
    private static BlockData hangingCluster() {
        BlockData data = CLUSTER.createBlockData();
        if (data instanceof Directional directional && directional.getFaces().contains(BlockFace.DOWN)) {
            directional.setFacing(BlockFace.DOWN);
        }
        return data;
    }

    private static BlockData budData() {
        return BUD.createBlockData();
    }

    private static BlockData amethyst() {
        return AMETHYST.createBlockData();
    }

    private static BlockData glass() {
        return SHELL.createBlockData();
    }

    /** {@code at} without its rotation, so display entities placed there keep their own orientation. */
    private static Location flat(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }
}
