package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 요르문간드 — the world serpent that circles Midgard biting its own tail. At low health it sheds its skin (cleansed,
 * shielded, quick), the active raises a giant tail out of the ground that sweeps a half circle around the aimed spot,
 * and as the ultimate its body closes into a ring: inside, space is joined end to end, so an enemy walking out at one
 * side comes back in at the other, while the ring tightens like a battle royale circle.
 *
 * <p>Look (deep sea green, venom yellow, teal scales): scales glint on the serpent now and then and its skin starts to
 * peel when the shedding is near. The shed skin is a pale glass husk coiled on the ground. The tail is a tapering chain
 * of dark and teal prismarine with a venom yellow tip; it erupts from cracked ground behind a traced half circle with
 * chevrons showing the way it will sweep. The ring is sixteen tapering prismarine segments undulating around the
 * fighters, the dark head with venom yellow eyes biting the tail tip, with teal motes along the body and a wake of ink
 * and nautilus shimmer where someone is turned back by the joined space.
 */
public final class JormungandrAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("jormungandr", "요르문간드", Material.PRISMARINE_SHARD)
            .color(TextColor.color(0x3FA59B))
            .difficulty(2)
            .passive("허물 벗기",
                    "체력이 {shed_threshold}% 아래로 떨어지면 허물을 벗습니다({shed_cooldown}초마다). 제자리에 빈 허물이 "
                            + "남고, 해로운 효과(독·둔화 같은 상태 이상과 불)가 씻겨 나가며 흡수 체력 {shed_absorption}"
                            + "({shed_absorb_duration}초)와 {shed_speed_duration}초간 이동 속도 +{shed_speed}%를 "
                            + "얻습니다.")
            .active("꼬리 휩쓸기",
                    "조준한 땅({tail_range}블록 이내)에서 거대한 꼬리가 솟아오릅니다. {tail_delay}초 동안 꼬리 끝과 휩쓸 "
                            + "방향이 표시된 뒤, 꼬리가 그곳을 축으로 먼 쪽 반원(반경 {tail_radius}블록)을 "
                            + "{tail_sweep}초에 걸쳐 휩쓸어 닿은 적에게 {tail_damage} 피해를 주고 옆으로 쳐내며 "
                            + "{tail_slow_duration}초간 이동 속도를 {tail_slow}% 늦춥니다. 꼬리가 낮아 점프로 넘으면 "
                            + "피할 수 있습니다.",
                    9)
            .ultimate("미드가르드의 고리",
                    "나를 중심으로 반경 {ring_radius}블록의 세계 뱀 고리를 {ring_duration}초간 두릅니다. 고리 안은 이어진 "
                            + "공간이라 바깥으로 나가려는 적은 반대편에서 안쪽을 보며 나타납니다. 고리는 점점 좁아져 반경 "
                            + "{ring_end_radius}블록이 되고, 좁아지는 몸에 닿은 적은 {ring_touch_damage} 피해를 입고 "
                            + "안쪽으로 밀려납니다(적마다 최대 {ring_touch_max}번). 아군은 자유롭게 드나듭니다. 고리가 "
                            + "다하면 안에 남은 적을 조여 {ring_crush_damage} 피해를 줍니다.")
            .param("shed_threshold", 40)
            .param("shed_cooldown", 30)
            .param("shed_absorption", 4)
            .param("shed_absorb_duration", 10)
            .param("shed_speed", 30)
            .param("shed_speed_duration", 1.5)
            .param("tail_range", 18)
            .param("tail_delay", 0.5)
            .param("tail_radius", 6)
            .param("tail_sweep", 0.5)
            .param("tail_damage", 4)
            .param("tail_slow", 30)
            .param("tail_slow_duration", 1)
            .param("ring_radius", 12)
            .param("ring_duration", 7)
            .param("ring_end_radius", 6)
            .param("ring_touch_damage", 2)
            .param("ring_touch_max", 3)
            .param("ring_crush_damage", 4)
            .build();

    private static final String SCALE_TAG = "#3FA59B";
    private static final String VENOM_TAG = "#D8D33A";

    // ------------------------------------------------------------------ look
    /** Venom yellow, teal scales, deep sea green. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xD8D33A, 0x3FA59B, 0x1C4D45);
    private static final Color VENOM = Color.fromRGB(0xD8D33A);
    private static final Color SCALE = Color.fromRGB(0x3FA59B);
    private static final Color DEEP = Color.fromRGB(0x1C4D45);
    private static final Particle.DustOptions SCALE_DUST = new Particle.DustOptions(SCALE, 1.0f);
    private static final Particle.DustOptions DEEP_DUST = new Particle.DustOptions(DEEP, 1.3f);
    private static final Particle.DustOptions VENOM_DUST = new Particle.DustOptions(VENOM, 0.9f);
    private static final Particle.DustTransition SCALE_GLINT = new Particle.DustTransition(VENOM, SCALE, 0.7f);
    private static final Particle.DustOptions SKIN_DUST = new Particle.DustOptions(Color.fromRGB(0xD9E3D6), 0.8f);

    /** Block types of the displays; block data is created on use (it needs a running server). */
    private static final Material DARK_SCALE = Material.DARK_PRISMARINE;
    private static final Material TEAL_SCALE = Material.PRISMARINE;
    private static final Material VENOM_TIP = Material.SPONGE;
    private static final Material EYE = Material.YELLOW_CONCRETE;
    private static final Material[] HUSK = {Material.WHITE_STAINED_GLASS, Material.LIGHT_GRAY_STAINED_GLASS};

    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 2.0;
    /** A player's client runs this many ticks ahead of the server at most (its ping, for steering). */
    private static final long MAX_LAG_TICKS = 10;
    private static final double MILLIS_PER_TICK = 50.0;

    // passive: shedding
    private static final int HUSK_PIECES = 3;
    private static final long HUSK_TICKS = 100;
    /** Movement and attack attributes whose lowering (or, for gravity, raising) other skills use as a hindrance. */
    private static final Attribute[] HINDERED = {Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH,
            Attribute.ATTACK_SPEED, Attribute.ATTACK_DAMAGE};

    // active: tail sweep
    private static final int TAIL_SEGMENTS = 6;
    private static final double TAIL_HEIGHT = 0.9;
    private static final double TAIL_HALF_WIDTH = 0.45;
    private static final double BASE_REACH = 1.0;
    private static final double TAIL_PUSH = 0.9;
    private static final double TAIL_LIFT = 0.35;
    private static final int TAIL_SINK_TICKS = 6;
    private static final double GROUND_SNAP = 6;

    // ultimate: Midgard ring
    private static final int RING_SEGMENTS = 16;
    /** The head: a skull and two eyes. */
    private static final int HEAD_PARTS = 3;
    /** With less room than this the ring's body is left out (the dust ring still shows it). */
    private static final int MIN_RING_SEGMENTS = 8;
    private static final long FORM_TICKS = 8;
    private static final long POSE_PERIOD = 2;
    private static final double BODY_HEIGHT = 0.55;
    /** Enemies this close to the body (from inside) meet it. */
    private static final double BODY_INNER = 0.5;
    /** Outward speed (blocks per tick) that counts as walking out rather than being caught by the tightening body. */
    private static final double WALK_OUT = 0.06;
    private static final double RING_WINDOW_UP = 6;
    private static final double RING_WINDOW_DOWN = 4;
    private static final double WARP_INSET = 1.8;
    /** A tracked enemy this far beyond the body has left the joined space (thrown or teleported far out). */
    private static final double ESCAPE_GAP = 4;
    private static final long WARP_COOLDOWN = 10;
    private static final long TOUCH_COOLDOWN = 20;
    private static final double TOUCH_PUSH = 0.75;
    private static final int CRUSH_TICKS = 5;
    private static final double[] WARP_ANGLES = {0, 12, -12, 24, -24, 36, -36, 50, -50};

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    /** The shed husk's pieces: the first displays to go when the ring needs the room. */
    private final List<BlockDisplay> husks = new ArrayList<>();
    private long nextShed = Long.MIN_VALUE / 2;
    private @Nullable Ring ring;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        ring = null;
        live.clear();
        husks.clear();
    }

    // ------------------------------------------------------------------ passive: shedding

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        double max = Combat.maxHealth(owner);
        double threshold = Math.clamp(params().num("shed_threshold") / 100.0, 0.0, 1.0);
        double health = owner.getHealth();
        boolean ready = now >= nextShed;
        if (ready && health > 0 && health < max * threshold) {
            shed(owner, now);
        } else if (tick % 20 == 0 && !owner.isInvisible()) {
            scales(owner, ready && health < max * Math.min(1.0, threshold + 0.2));
        }
    }

    /** Scales glint on the serpent's body; near the shedding its skin starts to peel. */
    private void scales(Player owner, boolean peeling) {
        Location body = Fx.body(owner);
        Vfx.dot(body.clone().add(Fx.jitter(0.3f), Fx.jitter(0.6f), Fx.jitter(0.3f)), Particle.DUST_COLOR_TRANSITION,
                SCALE_GLINT, false);
        if (peeling) {
            Vfx.cloud(ctx(), body, Particle.WHITE_ASH, 6, 0.3, 0.01, null, false);
            Vfx.dot(body.clone().add(Fx.jitter(0.3f), Fx.jitter(0.6f), Fx.jitter(0.3f)), Particle.DUST, SKIN_DUST, false);
        }
    }

    /** The serpent slips out of its skin: cleansed, shielded and quick, the empty husk left behind. */
    private void shed(Player owner, long now) {
        nextShed = now + Math.max(20, params().ticks("shed_cooldown"));
        int cleansed = cleanse(owner);
        double absorption = Math.max(0, params().num("shed_absorption"));
        long absorbTicks = Math.max(1, params().ticks("shed_absorb_duration"));
        if (absorption > 0) {
            ctx().addModifier(owner, owner, Attribute.MAX_ABSORPTION, absorption, Operation.ADD_NUMBER, absorbTicks);
            owner.setAbsorptionAmount(owner.getAbsorptionAmount() + absorption);
            // Once the shield's maximum is gone, clamp what is left of it.
            ctx().runLater(owner, absorbTicks + 1, () -> owner.setAbsorptionAmount(owner.getAbsorptionAmount()));
        }
        double speed = Math.max(0, params().num("shed_speed")) / 100.0;
        long speedTicks = params().ticks("shed_speed_duration");
        if (speed > 0 && speedTicks > 0) {
            ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1, speedTicks);
        }
        husk(owner);
        Location body = Fx.body(owner);
        Vfx.burst(ctx(), body, PALETTE, 0.8, Particle.WHITE_ASH, false);
        Vfx.helix(ctx(), owner.getLocation(), 0.55, owner.getHeight(), 1.5, 2, 10, 0, Particle.DUST_COLOR_TRANSITION,
                SCALE_GLINT, false);
        Vfx.cloud(ctx(), body, Particle.NAUTILUS, 10, 0.4, 0.4, null, false);
        Fx.sound(body, Sound.ENTITY_CAT_HISS, 1.0f, 0.5f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ITEM_ARMOR_EQUIP_TURTLE, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_SLIME_SQUISH, 0.8f, 0.6f + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<" + SCALE_TAG + "><b>🐍 허물 벗기!</b></" + SCALE_TAG + "> <gray>"
                + (cleansed > 0 ? "해로운 효과 " + cleansed + "개를 씻어 냈습니다 · " : "") + "흡수 체력 +"
                + AbilityInfo.formatNumber(absorption) + "</gray>"));
    }

    /**
     * Washes off harmful potion effects, fire and freezing, and the slows other skills put on the serpent (their
     * attribute modifiers). Returns how many hindrances went.
     */
    private int cleanse(Player owner) {
        int count = 0;
        for (PotionEffect effect : new ArrayList<>(owner.getActivePotionEffects())) {
            if (effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) {
                owner.removePotionEffect(effect.getType());
                count++;
            }
        }
        if (owner.getFireTicks() > 0) {
            owner.setFireTicks(0);
            count++;
        }
        if (owner.getFreezeTicks() > 0) {
            owner.setFreezeTicks(0);
        }
        String namespace = new NamespacedKey(ctx().plugin(), "shed").getNamespace();
        for (Attribute attribute : HINDERED) {
            count += removeHindrances(owner, attribute, namespace, false);
        }
        count += removeHindrances(owner, Attribute.GRAVITY, namespace, true);
        return count;
    }

    /** Removes the mode's modifiers on {@code attribute} that lower it (or raise it, for {@code raising}). */
    private int removeHindrances(Player owner, Attribute attribute, String namespace, boolean raising) {
        AttributeInstance instance = owner.getAttribute(attribute);
        if (instance == null) {
            return 0;
        }
        int count = 0;
        for (AttributeModifier modifier : new ArrayList<>(instance.getModifiers())) {
            boolean hinders = raising ? modifier.getAmount() > 0 : modifier.getAmount() < 0;
            if (hinders && modifier.getKey().getNamespace().equals(namespace)) {
                ctx().removeModifier(owner, modifier.getKey());
                count++;
            }
        }
        return count;
    }

    /** The empty skin: a pale glass husk coiled where the serpent stood, slowly crumbling. */
    private void husk(Player owner) {
        if (!reserve(HUSK_PIECES)) {
            return;
        }
        Location spot = owner.getLocation();
        Spots.snapToGround(spot.getWorld(), spot, 3);
        spot = Displays.unrotated(spot.add(0, 0.2, 0));
        float base = owner.getLocation().getYaw();
        for (int i = 0; i < HUSK_PIECES; i++) {
            float yaw = base + 70f * i - 70f;
            double angle = Math.toRadians(yaw + 90);
            float x = (float) (Math.cos(angle) * 0.45);
            float z = (float) (Math.sin(angle) * 0.45);
            float thick = 0.42f - 0.08f * i;
            BlockDisplay piece = keep(Displays.block(ctx(), owner, spot, HUSK[i % HUSK.length].createBlockData(),
                    Displays.transform(x, thick / 2, z, thick, thick, 0.05f, yaw, 0, 0), HUSK_TICKS));
            husks.removeIf(old -> !old.isValid());
            husks.add(piece);
            Displays.animateNextTick(ctx(), owner, piece, Displays.transform(x, thick / 2, z, thick, thick * 0.9f, 1.1f,
                    yaw, 0, 6f * (i - 1)), 5);
            ctx().runLater(owner, HUSK_TICKS - 12, () -> {
                if (piece.isValid()) {
                    Displays.shrinkAway(ctx(), owner, piece, 10);
                }
            });
        }
        Location husk = spot;
        Loop.repeat(ctx(), owner, 20, 20, HUSK_TICKS / 20 - 1, run -> {
            Vfx.cloud(ctx(), husk.clone().add(0, 0.3, 0), Particle.WHITE_ASH, 3, 0.4, 0.01, null, false);
            return true;
        });
    }

    // ------------------------------------------------------------------ active: tail sweep

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("tail_range"));
        Location pivot = aimGround(owner, range);
        if (pivot == null) {
            return ActivationResult.fail("꼬리를 솟게 할 땅이 보이지 않습니다");
        }
        Vector toPivot = Motion.horizontal(pivot.toVector().subtract(owner.getLocation().toVector()));
        if (toPivot.lengthSquared() < 1.0E-6) {
            toPivot = Motion.horizontalFacing(owner.getLocation());
        }
        double far = Math.atan2(toPivot.getZ(), toPivot.getX());
        int direction = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        Tail tail = new Tail(owner, pivot, far - direction * Math.PI / 2, direction);
        tail.begin();
        return ActivationResult.success();
    }

    /**
     * The tail: it erupts out of the ground at the pivot pointing to one side, the half circle it will sweep and its way
     * round are traced on the ground, then it swings through the far half around the pivot, low enough to jump over.
     */
    private final class Tail implements LongPredicate {

        private final Player owner;
        private final Location pivot;
        private final double start;
        private final int direction;
        private final double radius = Math.max(1, params().num("tail_radius"));
        private final long delay = Math.max(1, params().ticks("tail_delay"));
        private final long sweep = Math.max(2, params().ticks("tail_sweep"));
        private final List<BlockDisplay> segments = new ArrayList<>();
        private final Set<UUID> struck = new HashSet<>();
        private @Nullable BlockData groundData;
        private double angle;

        Tail(Player owner, Location pivot, double start, int direction) {
            this.owner = owner;
            this.pivot = pivot;
            this.start = start;
            this.direction = direction;
            this.angle = start;
        }

        void begin() {
            long lifetime = delay + sweep + TAIL_SINK_TICKS + 10;
            if (reserve(TAIL_SEGMENTS)) {
                for (int i = 0; i < TAIL_SEGMENTS; i++) {
                    Material material = i == TAIL_SEGMENTS - 1 ? VENOM_TIP : i % 2 == 0 ? DARK_SCALE : TEAL_SCALE;
                    BlockDisplay segment = keep(Displays.block(ctx(), owner, pivot, material.createBlockData(),
                            pose(i, start, -1.4, 0), lifetime));
                    segment.setViewRange(1.8f);
                    segments.add(segment);
                }
                ctx().runLater(owner, 1, () -> {
                    for (int i = 0; i < segments.size(); i++) {
                        Displays.animate(segments.get(i), pose(i, start, 0, 0), (int) Math.max(1, delay - 1));
                    }
                });
            }
            World world = pivot.getWorld();
            Block ground = pivot.clone().subtract(0, 0.3, 0).getBlock();
            if (!ground.isPassable()) {
                groundData = ground.getBlockData();
                world.spawnParticle(Particle.BLOCK, pivot.clone().add(0, 0.2, 0), Vfx.n(ctx(), 18), 0.6, 0.15, 0.6, 0,
                        ground.getBlockData(), true);
                world.spawnParticle(Particle.DUST_PILLAR, pivot.clone().add(0, 0.1, 0), Vfx.n(ctx(), 8), 0.5, 0.1, 0.5,
                        0, ground.getBlockData(), true);
            }
            Fx.sound(pivot, Sound.ENTITY_WARDEN_DIG, 1.2f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(pivot, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.0f, 0.6f);
            Fx.sound(pivot, Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 0.8f, 0.5f);
            Loop.repeat(ctx(), owner, 0, 1, delay + sweep, this, this::sink);
        }

        @Override
        public boolean test(long tick) {
            if (tick < delay) {
                if (tick % 2 == 0) {
                    telegraph(tick);
                }
                if (tick == delay - 2) {
                    Fx.sound(pivot, Sound.ENTITY_CAT_HISS, 1.2f, 0.5f);
                }
                return true;
            }
            long step = tick - delay + 1;
            double previous = angle;
            angle = start + direction * Math.PI * Math.min(1.0, (double) step / sweep);
            for (int i = 0; i < segments.size(); i++) {
                Displays.animate(segments.get(i), pose(i, angle, 0.08 * Math.sin(step * 0.9 + i), step), 1);
            }
            strike(previous, angle);
            trail(angle, step);
            if (step == 1) {
                Fx.sound(pivot, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, 0.5f);
                Fx.sound(pivot, Sound.ENTITY_RAVAGER_ATTACK, 1.0f, 0.7f + Fx.jitter(0.05f));
            } else if (step % 3 == 0) {
                Fx.sound(tip(angle), Sound.ENTITY_BREEZE_JUMP, 0.6f, 0.6f + 0.05f * step);
            }
            return true;
        }

        /** The half circle and the way the tail will sweep, traced on the ground; the tip shows where it starts. */
        private void telegraph(long tick) {
            Location floor = pivot.clone().add(0, 0.1, 0);
            Vfx.arc(ctx(), floor, radius, yawOf(start + direction * Math.PI / 2), 180,
                    (int) Math.max(18, radius * 6), Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.2f), true);
            // Chevrons along the arc point the way round.
            int chevrons = 4;
            for (int c = 1; c <= chevrons; c++) {
                double a = start + direction * Math.PI * (c - 0.5 + (tick % 4) / 8.0) / chevrons;
                for (int arm = -1; arm <= 1; arm += 2) {
                    double back = a - direction * 0.14;
                    double r = radius * 0.7 + arm * 0.35;
                    Location point = floor.clone().add(Math.cos(back) * r, 0, Math.sin(back) * r);
                    Location head = floor.clone().add(Math.cos(a) * radius * 0.7, 0, Math.sin(a) * radius * 0.7);
                    Vfx.line(ctx(), point, head, 4, Particle.DUST, VENOM_DUST, true);
                }
            }
            Location tipStart = floor.clone().add(Math.cos(start) * radius, 0, Math.sin(start) * radius);
            Vfx.ring(ctx(), tipStart, 0.5, 8, tick * 0.4, Particle.DUST, VENOM_DUST, true);
            BlockData crumbs = groundData;
            if (crumbs != null) {
                Vfx.cloud(ctx(), floor, Particle.BLOCK_CRUMBLE, 3, 0.4, 0, crumbs, false);
            }
        }

        /** Every enemy the tail passed over since the last tick (once per sweep), unless it jumped over it. */
        private void strike(double from, double to) {
            double span = Math.abs(to - from);
            double damage = params().num("tail_damage");
            for (LivingEntity enemy : ctx().enemiesNear(owner, pivot, radius + 1)) {
                if (struck.contains(enemy.getUniqueId())) {
                    continue;
                }
                Location at = enemy.getLocation();
                double dy = at.getY() - pivot.getY();
                if (dy > TAIL_HEIGHT || dy < -1.5) {
                    continue; // jumped over the low tail (or below it)
                }
                double dx = at.getX() - pivot.getX();
                double dz = at.getZ() - pivot.getZ();
                double distance = Math.hypot(dx, dz);
                double half = enemy.getWidth() / 2;
                if (distance > radius + half) {
                    continue;
                }
                boolean hit;
                if (distance < BASE_REACH + half) {
                    hit = true;
                } else {
                    double pad = Math.asin(Math.min(1.0, (half + TAIL_HALF_WIDTH) / distance));
                    double relative = wrap((Math.atan2(dz, dx) - from) * direction);
                    hit = relative >= -pad && relative <= span + pad;
                }
                if (!hit) {
                    continue;
                }
                struck.add(enemy.getUniqueId());
                Combat.hit(ctx(), owner, enemy, damage);
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector tangent = new Vector(-Math.sin(to), 0, Math.cos(to)).multiply(direction);
                Vector radial = new Vector(Math.cos(to), 0, Math.sin(to));
                Motion.push(enemy, tangent.multiply(TAIL_PUSH).add(radial.multiply(0.25)).setY(TAIL_LIFT), MAX_SPEED);
                double slow = Math.clamp(params().num("tail_slow") / 100.0, 0.0, 1.0);
                long slowTicks = params().ticks("tail_slow_duration");
                if (slow > 0 && slowTicks > 0) {
                    ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                            slowTicks);
                }
                hitFx(enemy);
            }
        }

        private void hitFx(LivingEntity enemy) {
            Location body = Fx.body(enemy);
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 0.8f, 1.1f + Fx.jitter(0.08f));
            if (!seen(owner, enemy)) {
                return;
            }
            Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.SWEEP_ATTACK, false);
            Vfx.cloud(ctx(), body, Particle.DUST, 6, 0.3, 0, VENOM_DUST, false);
        }

        /** Torn ground and venom spray along the tail as it sweeps. */
        private void trail(double at, long step) {
            World world = pivot.getWorld();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                double r = 0.8 + random.nextDouble() * (radius - 0.8);
                Location point = pivot.clone().add(Math.cos(at) * r, 0.15, Math.sin(at) * r);
                Block ground = point.clone().subtract(0, 0.3, 0).getBlock();
                if (!ground.isPassable()) {
                    world.spawnParticle(Particle.BLOCK, point, 1, 0.15, 0.05, 0.15, 0, ground.getBlockData());
                }
                Vfx.dot(point.add(0, 0.4, 0), Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.1f), false);
            }
            if (step % 2 == 0) {
                Vfx.dot(tip(at).add(0, 0.3, 0), Particle.DUST, VENOM_DUST, false);
                world.spawnParticle(Particle.FALLING_DUST, tip(at).add(0, 0.5, 0), 1, 0.1, 0.1, 0.1, 0,
                        VENOM_TIP.createBlockData());
            }
        }

        private Location tip(double at) {
            return pivot.clone().add(Math.cos(at) * radius, 0, Math.sin(at) * radius);
        }

        /** The tail sinks back into the ground and is gone. */
        void sink() {
            for (int i = 0; i < segments.size(); i++) {
                BlockDisplay segment = segments.get(i);
                if (segment.isValid()) {
                    Displays.animate(segment, pose(i, angle, -1.4, 0), TAIL_SINK_TICKS);
                }
            }
            ctx().runLater(owner, TAIL_SINK_TICKS, () -> {
                for (BlockDisplay segment : segments) {
                    if (segment.isValid()) {
                        segment.remove();
                    }
                }
            });
            Fx.sound(pivot, Sound.BLOCK_ROOTED_DIRT_PLACE, 1.0f, 0.6f);
            if (!struck.isEmpty()) {
                owner.sendActionBar(Text.mm("<" + SCALE_TAG + ">🐍 꼬리 휩쓸기</" + SCALE_TAG + "> <white>"
                        + struck.size() + "명 적중!</white>"));
            }
        }

        /**
         * Segment {@code index} of the tail lying along the math angle {@code along} (0 = +X, turning towards +Z) from
         * the pivot, raised by {@code lift}, tapering from the thick base to the venom tip.
         */
        private Transformation pose(int index, double along, double lift, long step) {
            double length = radius / TAIL_SEGMENTS;
            double t = (double) index / (TAIL_SEGMENTS - 1);
            float thick = (float) (1.05 - 0.65 * t);
            double r = (index + 0.5) * length;
            float x = (float) (Math.cos(along) * r);
            float z = (float) (Math.sin(along) * r);
            float y = (float) (thick * 0.42 + lift);
            float wiggle = (float) (step > 0 ? 6 * Math.sin(step * 0.8 + index * 0.7) : 0);
            return Displays.transform(x, y, z, thick, thick * 0.8f, (float) (length * 1.12), yawOf(along) + wiggle, 0, 0);
        }
    }

    // ------------------------------------------------------------------ ultimate: Midgard ring

    @Override
    public ActivationResult onUltimate() {
        if (ring != null) {
            return ActivationResult.fail("이미 세계 뱀이 고리를 두르고 있습니다");
        }
        Player owner = player();
        Location center = owner.getLocation();
        Spots.snapToGround(center.getWorld(), center, GROUND_SNAP);
        Ring midgard = new Ring(owner, Displays.unrotated(center));
        ring = midgard;
        midgard.begin();
        return ActivationResult.success();
    }

    /** Per enemy inside the ring: the last distance from its center and how often the body has met it. */
    private static final class Inside {
        final LivingEntity enemy;
        double distance;
        int touches;
        long nextTouch = Long.MIN_VALUE / 2;
        long nextWarp = Long.MIN_VALUE / 2;
        /** When the body may push the enemy in again (once its client had time to follow the last push). */
        long nextNudge = Long.MIN_VALUE / 2;

        Inside(LivingEntity enemy, double distance) {
            this.enemy = enemy;
            this.distance = distance;
        }
    }

    /**
     * The world serpent's ring: it coils around the owner (the head chasing round to bite the tail), then holds every
     * enemy inside in a joined space while it tightens. An enemy that walks (or is thrown, or teleports) out is turned
     * back in on the far side, facing the middle; one the tightening body catches up with is hurt and shoved inward.
     * When the ring ends it constricts on those still inside.
     */
    private final class Ring implements LongPredicate {

        private final Player owner;
        private final Location center;
        private final double startRadius = Math.max(3, params().num("ring_radius"));
        private final double endRadius;
        private final long duration = Math.max(FORM_TICKS + 20, params().ticks("ring_duration"));
        private final double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        private final List<BlockDisplay> body = new ArrayList<>();
        private final List<BlockDisplay> head = new ArrayList<>();
        private final Map<UUID, Inside> inside = new HashMap<>();
        /** How many body segments go round the circle (fewer, longer ones when the display budget is short). */
        private int count = RING_SEGMENTS;
        private double radius;
        private int warps;

        Ring(Player owner, Location center) {
            this.owner = owner;
            this.center = center;
            this.endRadius = Math.clamp(params().num("ring_end_radius"), 1.5, startRadius);
            this.radius = startRadius;
        }

        void begin() {
            long lifetime = duration + CRUSH_TICKS + 10;
            int segments = bodyRoom();
            count = Math.max(1, segments);
            for (int i = 0; i < segments; i++) {
                Material material = i % 2 == 0 ? DARK_SCALE : TEAL_SCALE;
                if (i >= segments - 2) {
                    material = i == segments - 1 ? VENOM_TIP : TEAL_SCALE;
                }
                BlockDisplay segment = keep(Displays.block(ctx(), owner, center, material.createBlockData(),
                        segmentPose(i, radius, 0, 0.05f), lifetime));
                segment.setViewRange(2.5f);
                body.add(segment);
            }
            if (segments > 0) {
                BlockDisplay skull = keep(Displays.block(ctx(), owner, center, DARK_SCALE.createBlockData(),
                        headPose(radius, 0.05f), lifetime));
                skull.setViewRange(2.5f);
                head.add(skull);
                for (int side = -1; side <= 1; side += 2) {
                    BlockDisplay eye = keep(Displays.block(ctx(), owner, center, EYE.createBlockData(),
                            eyePose(radius, side, 0.05f), lifetime));
                    eye.setViewRange(2.5f);
                    head.add(eye);
                }
            }
            Location floor = center.clone().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, floor, 0.5, startRadius, (int) FORM_TICKS, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.fade(1.4f), true);
            Fx.sound(center, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 0.6f);
            Fx.sound(center, Sound.ENTITY_WARDEN_EMERGE, 1.2f, 0.8f);
            Fx.sound(center, Sound.BLOCK_CONDUIT_ACTIVATE, 1.2f, 0.6f);
            owner.sendActionBar(Text.mm("<" + SCALE_TAG + "><b>🐍 미드가르드의 고리</b></" + SCALE_TAG + "> <gray>"
                    + Fx.seconds(duration) + "초간 아무도 빠져나갈 수 없습니다</gray>"));
            Loop.repeat(ctx(), owner, 0, 1, duration, this, this::constrict);
        }

        @Override
        public boolean test(long tick) {
            long now = ctx().currentTick();
            if (tick < FORM_TICKS) {
                // The head chases round the circle, the body unrolling behind it.
                double grown = (double) (tick + 1) / FORM_TICKS;
                if (tick % POSE_PERIOD == 0) {
                    pose(tick, grown);
                }
                double headAngle = phase + Math.PI * 2 * grown;
                Location at = center.clone().add(Math.cos(headAngle) * radius, BODY_HEIGHT, Math.sin(headAngle) * radius);
                Vfx.cloud(ctx(), at, Particle.NAUTILUS, 4, 0.3, 0.2, null, true);
                Vfx.dot(at, Particle.DUST, VENOM_DUST, true);
                if (tick == FORM_TICKS - 1) {
                    Fx.sound(center, Sound.ENTITY_PHANTOM_BITE, 1.4f, 0.5f);
                    Fx.sound(center, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.2f, 0.6f);
                    for (LivingEntity enemy : enemiesInRing(radius - 0.3)) {
                        inside.put(enemy.getUniqueId(), new Inside(enemy, flat(enemy.getLocation())));
                        if (enemy instanceof Player trapped) {
                            trapped.sendActionBar(Text.mm("<" + SCALE_TAG + "><b>🐍 세계 뱀의 고리</b></" + SCALE_TAG
                                    + "> <gray>고리 밖으로 나가면 반대편으로 돌아옵니다</gray>"));
                        }
                    }
                }
                return true;
            }
            double progress = (double) (tick - FORM_TICKS) / Math.max(1, duration - FORM_TICKS);
            radius = startRadius + (endRadius - startRadius) * Math.min(1.0, progress);
            if (tick % POSE_PERIOD == 0) {
                pose(tick, 1.0);
            }
            hold(now);
            ambient(tick);
            return true;
        }

        /**
         * How many body segments fit in the display budget with the head: all of them when there is room, else the
         * shed husk (only for show) makes way, and the body sheds detail (fewer, longer segments) rather than vanish
         * when the tail sweep is still up; 0 (the dust ring alone) only below {@link #MIN_RING_SEGMENTS}.
         */
        private int bodyRoom() {
            if (!reserve(RING_SEGMENTS + HEAD_PARTS)) {
                for (BlockDisplay piece : husks) {
                    if (piece.isValid()) {
                        piece.remove();
                    }
                }
                husks.clear();
            }
            live.removeIf(display -> !display.isValid());
            int room = MAX_DISPLAYS - live.size() - HEAD_PARTS;
            return room >= MIN_RING_SEGMENTS ? Math.min(RING_SEGMENTS, room) : 0;
        }

        /** Enemies within {@code within} blocks of the center (horizontally) and within the ring's height. */
        private List<LivingEntity> enemiesInRing(double within) {
            List<LivingEntity> found = new ArrayList<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, within + RING_WINDOW_UP)) {
                Location at = enemy.getLocation();
                double dy = at.getY() - center.getY();
                if (dy <= RING_WINDOW_UP && dy >= -RING_WINDOW_DOWN && flat(at) < within) {
                    found.add(enemy);
                }
            }
            return found;
        }

        private double flat(Location at) {
            return Math.hypot(at.getX() - center.getX(), at.getZ() - center.getZ());
        }

        /** The joined space: walking out turns you back in on the far side; the tightening body shoves you inward. */
        private void hold(long now) {
            for (LivingEntity enemy : enemiesInRing(radius - BODY_INNER - 0.1)) {
                inside.computeIfAbsent(enemy.getUniqueId(), id -> new Inside(enemy, flat(enemy.getLocation())));
            }
            for (Map.Entry<UUID, Inside> entry : new ArrayList<>(inside.entrySet())) {
                Inside state = entry.getValue();
                LivingEntity enemy = state.enemy;
                if (!Combat.isTargetable(ctx(), owner, enemy) || !enemy.getWorld().equals(center.getWorld())) {
                    inside.remove(entry.getKey());
                    continue;
                }
                Location at = enemy.getLocation();
                double dy = at.getY() - center.getY();
                double distance = flat(at);
                double outward = distance - state.distance;
                state.distance = distance;
                if (dy > RING_WINDOW_UP || dy < -RING_WINDOW_DOWN || distance < radius - BODY_INNER) {
                    continue;
                }
                // Walking (or thrown, or teleported) out: the joined space turns the enemy back in on the far side.
                boolean escaping = outward > WALK_OUT || distance > radius + ESCAPE_GAP;
                if (escaping && now >= state.nextWarp) {
                    state.nextWarp = now + WARP_COOLDOWN;
                    if (warp(enemy)) {
                        state.distance = flat(enemy.getLocation());
                        continue;
                    }
                }
                if (distance > radius + ESCAPE_GAP) {
                    inside.remove(entry.getKey()); // no room anywhere on the far side: it got away
                    continue;
                }
                shoveIn(enemy, state, now);
            }
        }

        /** Turns {@code enemy} back in on the far side of the ring, facing the middle. False if there is no room. */
        private boolean warp(LivingEntity enemy) {
            Location from = enemy.getLocation();
            double angle = Math.atan2(from.getZ() - center.getZ(), from.getX() - center.getX()) + Math.PI;
            double inset = Math.max(1.0, radius - WARP_INSET);
            Body shape = Body.standing(enemy);
            for (double offset : WARP_ANGLES) {
                for (double r : new double[] {inset, Math.max(1.0, inset - 1.5)}) {
                    double a = angle + Math.toRadians(offset);
                    Location feet = center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                    feet.setY(center.getY() + 2.5);
                    Spots.snapToGround(feet.getWorld(), feet, 6);
                    if (!Spots.isSafe(ctx(), feet, shape)) {
                        continue;
                    }
                    Vector look = center.toVector().subtract(feet.toVector()).setY(0);
                    if (look.lengthSquared() > 1.0E-6) {
                        feet.setDirection(look);
                    }
                    boolean shown = seen(owner, enemy);
                    if (shown) {
                        wake(from);
                    }
                    enemy.teleport(feet);
                    enemy.setFallDistance(0);
                    warps++;
                    if (shown) {
                        wake(feet);
                    }
                    Fx.sound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.6f + Fx.jitter(0.05f));
                    Fx.sound(feet, Sound.ENTITY_ELDER_GUARDIAN_FLOP, 0.9f, 0.7f + Fx.jitter(0.05f));
                    if (enemy instanceof Player turned) {
                        turned.sendActionBar(Text.mm("<" + SCALE_TAG + ">🐍 이어진 공간</" + SCALE_TAG + "> <gray>"
                                + "고리를 넘자 반대편으로 돌아왔습니다</gray>"));
                    }
                    return true;
                }
            }
            return false;
        }

        /** Ink and nautilus shimmer where someone passes through the joined space. */
        private void wake(Location at) {
            Location body = at.clone().add(0, 1, 0);
            Vfx.cloud(ctx(), body, Particle.NAUTILUS, 14, 0.4, 0.6, null, true);
            Vfx.cloud(ctx(), body, Particle.SQUID_INK, 6, 0.3, 0.05, null, false);
            Vfx.ring(ctx(), at.clone().add(0, 0.1, 0), 0.8, 12, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f),
                    true);
        }

        /**
         * The body meets an enemy standing in its way: hurt (a few times at most) and shoved inward. A player is pushed
         * again only once its client has had a round trip to follow the last push: the server's position trails it by
         * the ping, and pushed every tick it still looked at the body it was flung far inward.
         */
        private void shoveIn(LivingEntity enemy, Inside state, long now) {
            Vector inward = Motion.horizontal(center.toVector().subtract(enemy.getLocation().toVector()));
            if (inward.lengthSquared() < 1.0E-6 || now < state.nextNudge) {
                return;
            }
            state.nextNudge = now + 1 + lagTicks(enemy);
            if (now < state.nextTouch) {
                Motion.push(enemy, inward.multiply(TOUCH_PUSH * 0.5).setY(0.1), MAX_SPEED);
                return;
            }
            state.nextTouch = now + TOUCH_COOLDOWN;
            if (state.touches < params().integer("ring_touch_max")) {
                state.touches++;
                Combat.hit(ctx(), owner, enemy, params().num("ring_touch_damage"));
            }
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            Motion.push(enemy, inward.multiply(TOUCH_PUSH).setY(0.25), MAX_SPEED);
            Location body = Fx.body(enemy);
            Fx.sound(body, Sound.ENTITY_GUARDIAN_ATTACK, 0.8f, 0.8f + Fx.jitter(0.08f));
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 0.7f);
            if (seen(owner, enemy)) {
                Vfx.cloud(ctx(), body, Particle.DUST, 8, 0.3, 0, SCALE_DUST, false);
                Vfx.dot(body, Particle.FLASH, SCALE, false);
            }
        }

        /** Teal motes along the body, venom drips, the slow pulse of the world serpent. */
        private void ambient(long tick) {
            if (tick % 2 == 0) {
                int points = Vfx.n(ctx(), (int) Math.min(30, Math.max(12, radius * 2.2)));
                World world = center.getWorld();
                for (int i = 0; i < points; i++) {
                    double a = phase + tick * 0.01 + i * Math.PI * 2 / points;
                    double y = center.getY() + BODY_HEIGHT + 0.65 + 0.1 * Math.sin(tick * 0.3 + i);
                    world.spawnParticle(Particle.DUST, center.getX() + Math.cos(a) * radius, y,
                            center.getZ() + Math.sin(a) * radius, 1, 0, 0, 0, 0, i % 5 == 0 ? VENOM_DUST : SCALE_DUST,
                            true);
                }
            }
            if (tick % 5 == 0) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                double a = random.nextDouble(Math.PI * 2);
                Location drip = center.clone().add(Math.cos(a) * radius, BODY_HEIGHT + 0.4, Math.sin(a) * radius);
                center.getWorld().spawnParticle(Particle.FALLING_DUST, drip, 1, 0.2, 0, 0.2, 0,
                        VENOM_TIP.createBlockData());
                Vfx.ring(ctx(), center.clone().add(0, 0.12, 0), radius, (int) Math.max(16, radius * 3), -tick * 0.02,
                        Particle.DUST, DEEP_DUST, false);
            }
            if (tick % 20 == 0) {
                Fx.sound(center, Sound.ENTITY_WARDEN_HEARTBEAT, 1.2f, 0.6f);
                Fx.sound(center, Sound.BLOCK_CONDUIT_AMBIENT, 1.0f, 0.6f);
            }
        }

        /** Every segment (and the head) on the circle of the current radius, for {@code tick}. */
        private void pose(long tick, double grown) {
            for (int i = 0; i < body.size(); i++) {
                double shown = (i + 1.0) / body.size() <= grown + 1.0E-6 ? 1.0 : 0.05;
                Displays.animate(body.get(i), segmentPose(i, radius, tick, (float) shown), (int) POSE_PERIOD);
            }
            float headShown = grown >= 1.0 - 1.0E-6 ? 1f : 0.05f;
            if (!head.isEmpty()) {
                Displays.animate(head.get(0), headPose(radius, headShown), (int) POSE_PERIOD);
                for (int side = 0; side < 2 && side + 1 < head.size(); side++) {
                    Displays.animate(head.get(side + 1), eyePose(radius, side == 0 ? -1 : 1, headShown),
                            (int) POSE_PERIOD);
                }
            }
        }

        /**
         * Body segment {@code index} on the circle of {@code r}: a long box along the tangent, undulating, tapering
         * towards the tail tip in the mouth.
         */
        private Transformation segmentPose(int index, double r, long tick, float grow) {
            double step = Math.PI * 2 / count;
            double a = phase + (index + 0.5) * step;
            double t = (double) index / Math.max(1, count - 1);
            float thick = (float) (1.15 - 0.6 * Math.pow(t, 1.5)) * grow;
            float length = (float) (2 * r * Math.sin(step / 2) * 1.08) * Math.max(0.05f, grow);
            float bob = (float) (0.12 * Math.sin(tick * 0.25 + index * 0.9));
            float x = (float) (Math.cos(a) * r);
            float z = (float) (Math.sin(a) * r);
            float y = (float) (BODY_HEIGHT + bob) * Math.max(0.05f, grow);
            return Displays.transform(x, y, z, thick, thick * 0.9f, length, yawOf(a + Math.PI / 2), 0, 0);
        }

        /** The head at the start of the body, facing back along the circle to bite the tail tip. */
        private Transformation headPose(double r, float grow) {
            double a = phase + 0.9 / r;
            float x = (float) (Math.cos(a) * r);
            float z = (float) (Math.sin(a) * r);
            float g = Math.max(0.05f, grow);
            return Displays.transform(x, (float) (BODY_HEIGHT + 0.15) * g, z, 1.5f * g, 1.25f * g, 1.9f * g,
                    yawOf(a - Math.PI / 2), 0, 0);
        }

        private Transformation eyePose(double r, int side, float grow) {
            double a = phase + 0.9 / r;
            Vector facing = new Vector(Math.sin(a), 0, -Math.cos(a));
            Vector across = new Vector(Math.cos(a), 0, Math.sin(a)).multiply(side * 0.62);
            Vector at = new Vector(Math.cos(a) * r, 0, Math.sin(a) * r).add(facing.multiply(0.5)).add(across);
            float g = Math.max(0.05f, grow);
            return Displays.transform((float) at.getX(), (float) (BODY_HEIGHT + 0.5) * g, (float) at.getZ(), 0.24f * g,
                    0.24f * g, 0.24f * g, yawOf(a - Math.PI / 2), 0, 0);
        }

        /** The end: the coils constrict on everyone still inside. */
        private void constrict() {
            double crush = params().num("ring_crush_damage");
            int crushed = 0;
            for (Inside state : inside.values()) {
                LivingEntity enemy = state.enemy;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Location at = enemy.getLocation();
                double dy = at.getY() - center.getY();
                if (flat(at) > radius + 0.6 || dy > RING_WINDOW_UP || dy < -RING_WINDOW_DOWN) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, crush);
                crushed++;
                Location body = Fx.body(enemy);
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.6f);
                if (seen(owner, enemy)) {
                    Vfx.helix(ctx(), at, 0.7, enemy.getHeight(), 1.5, 2, 10, 0, Particle.DUST_COLOR_TRANSITION,
                            PALETTE.glow(1.1f), false);
                    Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.CRIT, false);
                }
            }
            double tight = Math.max(1.0, radius * 0.45);
            for (int i = 0; i < body.size(); i++) {
                BlockDisplay segment = body.get(i);
                if (segment.isValid()) {
                    Displays.animate(segment, segmentPose(i, tight, 0, 1f), CRUSH_TICKS);
                }
            }
            if (!head.isEmpty() && head.get(0).isValid()) {
                Displays.animate(head.get(0), headPose(tight, 1f), CRUSH_TICKS);
                for (int side = 0; side < 2 && side + 1 < head.size(); side++) {
                    Displays.animate(head.get(side + 1), eyePose(tight, side == 0 ? -1 : 1, 1f), CRUSH_TICKS);
                }
            }
            List<BlockDisplay> parts = new ArrayList<>(body);
            parts.addAll(head);
            ctx().runLater(owner, CRUSH_TICKS, () -> {
                for (BlockDisplay part : parts) {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 6);
                    }
                }
                Vfx.cloud(ctx(), center.clone().add(0, 1, 0), Particle.NAUTILUS, 30, tight, 0.3, null, true);
            });
            Location floor = center.clone().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, floor, radius, 0.5, CRUSH_TICKS, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.4f), true);
            Fx.sound(center, Sound.ENTITY_ELDER_GUARDIAN_DEATH, 1.0f, 0.8f);
            Fx.sound(center, Sound.BLOCK_CONDUIT_DEACTIVATE, 1.2f, 0.6f);
            Fx.sound(center, Sound.ENTITY_PHANTOM_BITE, 1.4f, 0.4f);
            owner.sendActionBar(Text.mm("<" + SCALE_TAG + ">🐍 미드가르드의 고리</" + SCALE_TAG + "> <white>"
                    + crushed + "명 조임" + (warps > 0 ? " · " + warps + "번 되돌림" : "") + "</white>"));
            if (ring == this) {
                ring = null;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Where the tail rises: under the aimed enemy, else on top of the first block the view ray meets (or the ground
     * below where it ends); null without ground there.
     */
    private @Nullable Location aimGround(Player owner, double range) {
        LivingEntity aimed = ctx().findTarget(owner, range);
        Location point;
        if (aimed != null) {
            point = aimed.getLocation();
        } else {
            Location eye = owner.getEyeLocation();
            Vector direction = eye.getDirection();
            RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            point = hit == null ? eye.clone().add(direction.clone().multiply(range))
                    : hit.getHitPosition().toLocation(owner.getWorld()).subtract(direction.clone().multiply(0.3));
        }
        Location ground = point.clone().add(0, 0.5, 0);
        double before = ground.getY();
        Spots.snapToGround(ground.getWorld(), ground, GROUND_SNAP);
        if (ground.getY() >= before - 1.0E-6) {
            if (aimed == null || !Spots.hasGroundBelow(point.getWorld(), point)) {
                return null; // no ground near where the view ends
            }
            ground = point.clone(); // an aimed enemy stuck in (or hovering over) something: right where it is
        }
        if (!ctx().isInsideArena(ground)) {
            return null;
        }
        return Displays.unrotated(ground);
    }

    /** How many ticks a player's client runs ahead of the server's view of it (its ping), 0 for any other entity. */
    private static long lagTicks(LivingEntity entity) {
        return entity instanceof Player player
                ? Math.clamp(Math.round(player.getPing() / MILLIS_PER_TICK), 0, MAX_LAG_TICKS)
                : 0;
    }

    /** Minecraft's yaw (degrees: 0 = +Z, 90 = -X) of the math angle {@code angle} (0 = +X, towards +Z). */
    private static float yawOf(double angle) {
        return (float) Math.toDegrees(angle) - 90f;
    }

    /** {@code angle} wrapped into [-π, π). */
    private static double wrap(double angle) {
        double a = (angle + Math.PI) % (Math.PI * 2);
        if (a < 0) {
            a += Math.PI * 2;
        }
        return a - Math.PI;
    }

    /** True unless {@code entity} is a player hidden from {@code owner} (stealth): nothing may reveal it. */
    private static boolean seen(Player owner, Entity entity) {
        return !(entity instanceof Player hidden) || hidden.equals(owner) || owner.canSee(hidden);
    }

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
