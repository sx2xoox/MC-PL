package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
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
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 독수리 — a bird of prey. From up high its keen eyes pick out every enemy below (they glow, and the eagle strikes
 * them harder); it swoops onto an enemy, snatches it and carries it off before dropping it; and as the ultimate it
 * seizes one enemy, soars high into the sky, circles while choosing where to strike, then dives and slams it into the
 * ground among its friends.
 *
 * <p>Look: sky blue and golden brown. The eagle grows broad brown wings with dark primaries and a white tail fan that
 * flap as it glides (beating faster when it climbs, swept back in a dive), shedding feathers and streaks of wind. The
 * prey dangles under its talons in a ring of sky-blue light. For the ultimate a golden reticle on the ground shows
 * everyone where the dive will land, the dive is a falling streak of light, and the impact bursts in feathers, dust, a
 * shockwave and cracks in the ground. Spotted enemies glow, and only the eagle sees the golden sights around them.
 */
public final class EagleAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("eagle", "독수리", Material.FEATHER)
            .color(TextColor.color(0x7CC8FF))
            .difficulty(3)
            .passive("매의 눈",
                    "나보다 {eye_height}블록 이상 낮은 곳에 있는 {eye_range}블록 안의 적은 내 눈에 띄어 발광하고, 그 적에게 "
                            + "주는 모든 피해가 {eye_bonus}% 늘어납니다.")
            .active("낚아채기",
                    "바라보는 방향(조준한 적이 있으면 그 적)으로 최대 {snatch_range}블록 활강합니다. 도중에 부딪힌 적을 움켜쥐어 "
                            + "{snatch_damage} 피해를 주고, 함께 {carry_height}블록 높이로 날아올라 바라보는 방향으로 "
                            + "{carry_time}초간 실어 나른 뒤 떨어뜨립니다. 떨어진 적은 땅에 부딪혀 {drop_damage} 피해를 입고 "
                            + "{drop_slow}초간 구속됩니다. 아무도 잡지 못하면 쿨타임이 {miss_refund}% 돌아오며, 이 비행으로는 "
                            + "낙하 피해를 받지 않습니다.",
                    9)
            .ultimate("하늘의 제왕",
                    "조준한 적(없으면 가장 가까운 적, {ult_range}블록 이내)에게 날아가 움켜쥐고 {ult_height}블록 높이로 "
                            + "솟구쳐 {ult_circle}초간 하늘을 선회합니다. 그동안 바라보는 땅(선회 지점에서 {dive_reach}블록 "
                            + "이내)에 낙하 지점이 표시되고, 선회가 끝나면 그곳으로 급강하해 적을 내리꽂아 {ult_damage} 피해를 "
                            + "주며 반경 {impact_radius}블록의 다른 적에게 {impact_damage} 피해를 주고 띄웁니다. 붙잡힌 적은 "
                            + "능력을 쓸 수 없고, 나는 낙하 피해를 받지 않습니다.")
            .param("eye_height", 3)
            .param("eye_range", 32)
            .param("eye_bonus", 15)
            .param("snatch_range", 10)
            .param("snatch_damage", 3)
            .param("carry_height", 4)
            .param("carry_time", 0.8)
            .param("drop_damage", 3)
            .param("drop_slow", 1)
            .param("miss_refund", 50)
            .param("ult_range", 16)
            .param("ult_height", 9)
            .param("ult_circle", 1)
            .param("dive_reach", 12)
            .param("ult_damage", 8)
            .param("impact_radius", 3.5)
            .param("impact_damage", 4)
            .build();

    private static final String COLOR_TAG = "#7CC8FF";
    private static final String GOLD_TAG = "#E6B450";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** White glow, sky blue, golden brown. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0x79C7FF, 0x8C5A1F);
    private static final Color GOLD = Color.fromRGB(0xE6B450);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 0.9f);
    private static final Particle.DustOptions SIGHT = new Particle.DustOptions(GOLD, 0.6f);
    private static final Particle.DustTransition SKY = PALETTE.glow(1.0f);
    private static final Particle.DustTransition GOLD_FADE = new Particle.DustTransition(GOLD,
            Color.fromRGB(0x8C5A1F), 1.2f);
    private static final Particle.DustOptions BROWN = new Particle.DustOptions(Color.fromRGB(0x6B4420), 1.0f);

    /** Materials of the visuals; block data and items are created when used (they need a running server). */
    private static final Material COVERT = Material.BROWN_WOOL;
    private static final Material PRIMARY = Material.BROWN_TERRACOTTA;
    private static final Material TAIL = Material.WHITE_WOOL;
    private static final Material CRACK = Material.BLACK_CONCRETE;
    private static final Material FEATHER = Material.FEATHER;

    private static final double MAX_SPEED = 2.4;
    private static final double STEER_GAIN = 0.5;
    private static final long MAX_LAG_TICKS = 8;
    private static final double MILLIS_PER_TICK = 50.0;

    // ------------------------------------------------------------------ passive: hawk's eye
    private static final long EYE_PERIOD = 10;

    // ------------------------------------------------------------------ wings
    private static final float WING_COVERT_LENGTH = 0.85f;
    private static final float WING_COVERT_CHORD = 0.5f;
    private static final float WING_PRIMARY_LENGTH = 0.8f;
    private static final float WING_PRIMARY_CHORD = 0.36f;
    private static final float WING_THICKNESS = 0.05f;
    private static final float SHOULDER_Y = 1.38f;
    private static final float SHOULDER_BACK = 0.2f;
    private static final float SHOULDER_SIDE = 0.12f;

    // ------------------------------------------------------------------ active: snatch
    private static final double GLIDE_SPEED = 1.0;
    private static final double MIN_PITCH_Y = -0.57; // about 35° down
    private static final double MAX_PITCH_Y = 0.42; // about 25° up
    private static final double HOMING = 0.35;
    private static final double GRAB_REACH = 1.3;
    private static final double HANG_GAP = 0.15;
    private static final double CARRY_SPEED = 0.3;
    private static final double CARRY_TURN = 0.25;
    private static final double LIFT_STEP = 0.5;
    private static final long DROP_WATCH_TICKS = 60;
    private static final double ESCAPE_LEASH = 3.5;
    private static final double DRIFT_LIMIT = 8.0;
    private static final long LANDING_GRACE_TICKS = 200;

    // ------------------------------------------------------------------ ultimate: king of the sky
    private static final double SWOOP_SPEED = 1.4;
    private static final double ULT_GRAB_REACH = 1.7;
    private static final int RISE_TICKS = 12;
    private static final int DIVE_TICKS = 7;
    private static final int MAX_DIVE_TICKS = 16;
    /** A dive of {@code n} ticks moves about {@code 2 / n} of its length in its last tick: keep that under the cap. */
    private static final double DIVE_STEP = 1.1;
    private static final double ORBIT_RADIUS = 2.0;
    private static final int ORBIT_EASE = 5;
    private static final double MARKER_RANGE = 48;
    private static final double IMPACT_PUSH = 0.8;
    private static final double IMPACT_LIFT = 0.6;
    private static final double REBOUND_UP = 0.55;
    private static final int CRACKS = 5;
    private static final long CRACK_TICKS = 34;

    private @Nullable Snatch snatch;
    private @Nullable Sovereign sovereign;
    private int lastSpotted;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        snatch = null;
        sovereign = null;
    }

    // ------------------------------------------------------------------ passive: hawk's eye

    /** True when {@code enemy} is far enough below the eagle (and within range) to be spotted. */
    private boolean spotted(LivingEntity enemy) {
        Player owner = player();
        if (!enemy.getWorld().equals(owner.getWorld()) || enemy instanceof Player hidden && !owner.canSee(hidden)) {
            return false;
        }
        double gap = owner.getLocation().getY() - enemy.getLocation().getY();
        double range = Math.max(0, params().num("eye_range"));
        return gap >= params().num("eye_height") && owner.getLocation().distanceSquared(enemy.getLocation())
                <= range * range;
    }

    @Override
    public void onTick(long tick) {
        if (tick % EYE_PERIOD != 0) {
            return;
        }
        Player owner = player();
        int count = 0;
        double range = Math.max(0, params().num("eye_range"));
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            if (!spotted(enemy) || !owner.hasLineOfSight(enemy)) {
                continue;
            }
            count++;
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, (int) EYE_PERIOD + 6, 0, false, false,
                    false));
            sights(owner, enemy, tick);
        }
        if (count > lastSpotted) {
            owner.playSound(owner.getLocation(), Sound.ENTITY_PHANTOM_AMBIENT, 0.5f, 1.7f);
        }
        if (count > 0 && sovereign == null && snatch == null) {
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">◉ 매의 눈</" + GOLD_TAG + "> <white>" + count
                    + "명 포착</white> <gray>+" + AbilityInfo.formatNumber(params().num("eye_bonus")) + "%</gray>"));
        }
        lastSpotted = count;
    }

    /** Golden sights around a spotted enemy, drawn for the eagle alone. */
    private void sights(Player owner, LivingEntity enemy, long tick) {
        Location body = Fx.body(enemy);
        double radius = enemy.getWidth() * 0.5 + 0.45;
        int points = Vfx.n(ctx(), 10);
        for (int i = 0; i < points; i++) {
            double angle = tick * 0.2 + i * TAU / points;
            owner.spawnParticle(Particle.DUST, body.getX() + Math.cos(angle) * radius, body.getY(),
                    body.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0, SIGHT);
        }
        Location head = enemy.getLocation().add(0, enemy.getHeight() + 0.35, 0);
        owner.spawnParticle(Particle.DUST, head, 1, 0, 0, 0, 0, GOLD_DUST);
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !ctx().isEnemy(player(), victim) || !spotted(victim)) {
            return;
        }
        double bonus = Math.max(0, params().num("eye_bonus")) / 100.0;
        if (bonus > 0) {
            event.setDamage(event.getDamage() * (1 + bonus));
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!spotted(victim)) {
            return;
        }
        Location body = Fx.body(victim);
        Vfx.cloud(ctx(), body, Particle.ENCHANTED_HIT, 5, 0.25, 0.1, null, false);
        Vfx.dot(body, Particle.DUST_COLOR_TRANSITION, GOLD_FADE, false);
    }

    // ------------------------------------------------------------------ wings

    /**
     * The eagle's wings and tail: five plates (two per wing, one tail fan) standing at the owner's feet, with every
     * bit of their pose — the shoulder, the flap, the sweep and the owner's facing — in their transformations.
     */
    private final class Wings {

        private final Player owner;
        private final float size;
        private final BlockDisplay[] plates = new BlockDisplay[5];
        private float flap;
        private boolean folded;

        Wings(Player owner, float size) {
            this.owner = owner;
            this.size = size;
        }

        void spawn(long lifetime) {
            Location feet = Displays.unrotated(owner.getLocation());
            float yaw = owner.getLocation().getYaw();
            Material[] materials = {COVERT, PRIMARY, COVERT, PRIMARY, TAIL};
            for (int i = 0; i < plates.length; i++) {
                BlockDisplay plate = Displays.block(ctx(), owner, feet, materials[i].createBlockData(),
                        platePose(i, yaw, 0, 0.6f, 0.15f), lifetime);
                plate.setBrightness(null);
                plate.setTeleportDuration(1);
                plates[i] = plate;
            }
        }

        /** Follows the owner: {@code beat} advances the flap, {@code sweep} folds the wings back (1 = a dive). */
        void follow(double beat, double sweep) {
            if (folded) {
                return;
            }
            flap += (float) beat;
            Location feet = owner.getLocation();
            float yaw = feet.getYaw();
            float flapAngle = (float) (Math.sin(flap) * 38 * (1 - 0.7 * sweep));
            for (int i = 0; i < plates.length; i++) {
                BlockDisplay plate = plates[i];
                if (plate == null || !plate.isValid()) {
                    continue;
                }
                Displays.moveTo(plate, feet);
                Displays.animate(plate, platePose(i, yaw, flapAngle, (float) (0.25 + 0.55 * sweep), size), 1);
            }
        }

        /** The wings fold away. */
        void fold() {
            if (folded) {
                return;
            }
            folded = true;
            for (BlockDisplay plate : plates) {
                if (plate != null && plate.isValid()) {
                    Displays.shrinkAway(ctx(), owner, plate, 5);
                }
            }
        }
    }

    /**
     * One wing plate in the transformation of a display at the owner's feet: plates 0/1 are the left wing's covert and
     * primaries, 2/3 the right wing's, 4 the tail fan. {@code flap} raises the wings (degrees), {@code sweep} (0..1)
     * folds them back, {@code yaw} is the owner's facing (0 = +Z, 90 = -X).
     */
    static Transformation platePose(int index, float yaw, float flap, float sweep, float scale) {
        Quaternionf body = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
        if (index == 4) {
            // The tail fan: a small plate behind the hips, tilted down and back.
            Vector3f size = new Vector3f(0.36f, WING_THICKNESS, 0.5f).mul(scale);
            Quaternionf rotation = new Quaternionf(body).rotateX((float) Math.toRadians(-25 - 30 * sweep));
            Vector3f pivot = body.transform(new Vector3f(0, 0.98f, -0.12f).mul(scale));
            Vector3f anchor = new Vector3f(size.x() / 2, size.y() / 2, size.z());
            Vector3f translation = pivot.sub(new Quaternionf(rotation).transform(anchor));
            return new Transformation(translation, rotation, size, new Quaternionf());
        }
        boolean left = index < 2;
        boolean primary = index % 2 == 1;
        float side = left ? 1 : -1; // facing +Z, the left side is +X
        float flapRad = (float) Math.toRadians(flap) * side;
        float sweepRad = (float) Math.toRadians(15 + 45 * sweep) * side;
        Quaternionf wing = new Quaternionf(body).rotateZ(flapRad).rotateY(sweepRad);
        Vector3f shoulder = new Vector3f(SHOULDER_SIDE * side, SHOULDER_Y, -SHOULDER_BACK).mul(scale);
        Vector3f pivot = body.transform(new Vector3f(shoulder));
        Quaternionf rotation = wing;
        float length = WING_COVERT_LENGTH;
        float chord = WING_COVERT_CHORD;
        if (primary) {
            // The primaries continue from the covert's outer leading corner, bent further back and down.
            Vector3f wrist = new Quaternionf(wing).transform(new Vector3f(WING_COVERT_LENGTH * side * scale, 0, 0));
            pivot.add(wrist);
            rotation = new Quaternionf(wing).rotateZ((float) Math.toRadians(-12) * side)
                    .rotateY((float) Math.toRadians(20 + 25 * sweep) * side);
            length = WING_PRIMARY_LENGTH;
            chord = WING_PRIMARY_CHORD;
        }
        Vector3f size = new Vector3f(length, WING_THICKNESS, chord).mul(scale);
        // The plate hangs off its pivot at its inner, leading edge (x = 0 for the left wing, x = length for the right).
        Vector3f anchor = new Vector3f(left ? 0 : size.x(), size.y() / 2, size.z());
        Vector3f translation = pivot.sub(new Quaternionf(rotation).transform(anchor));
        return new Transformation(translation, rotation, size, new Quaternionf());
    }

    // ------------------------------------------------------------------ active: snatch

    @Override
    public ActivationResult onActive() {
        if (sovereign != null) {
            return ActivationResult.fail("하늘을 선회하는 중입니다");
        }
        Snatch running = snatch;
        if (running != null) {
            running.abort();
        }
        Snatch fresh = new Snatch(player());
        snatch = fresh;
        fresh.start();
        return ActivationResult.success();
    }

    /**
     * The snatch: a low glide along the aim (bending towards an aimed enemy), and if it runs into an enemy, a carry —
     * the pair climbs and flies on along the eagle's view — and a drop that hurts when the prey hits the ground.
     */
    private final class Snatch implements LongPredicate {

        private final Player owner;
        private final World world;
        private final @Nullable LivingEntity aimed;
        private final double distance;
        private final long carryTicks;
        private final Wings wings;
        private Vector direction;
        private Location planned;
        private double flown;
        private @Nullable LivingEntity prey;
        private @Nullable Location preyPlanned;
        private Location preyStart;
        private Vector carryDirection = new Vector();
        private double lift;
        private long carryStart = -1;
        private boolean ended;

        Snatch(Player owner) {
            this.owner = owner;
            this.world = owner.getWorld();
            double range = Math.max(1, params().num("snatch_range"));
            this.aimed = ctx().findTarget(owner, range);
            Location feet = owner.getLocation();
            this.direction = initialDirection(owner, aimed);
            this.distance = Spots.arenaReach(ctx(), feet, direction, range);
            this.carryTicks = Math.max(4, params().ticks("carry_time"));
            this.planned = feet.clone();
            this.preyStart = feet.clone();
            this.wings = new Wings(owner, 1.0f);
        }

        void start() {
            long life = (long) Math.ceil(distance / GLIDE_SPEED) + carryTicks + 20;
            wings.spawn(life + 10);
            guardLanding(owner);
            takeoffFx(owner, false);
            Loop.repeat(ctx(), owner, 0, 1, life, this, this::end);
        }

        @Override
        public boolean test(long tick) {
            if (ended || !owner.getWorld().equals(world)) {
                return false;
            }
            if (owner.getLocation().distance(planned) > DRIFT_LIMIT) {
                return false; // carried off by something else (a teleport, a pull)
            }
            LivingEntity held = prey;
            if (held == null) {
                return glide(tick);
            }
            return carry(held, tick - carryStart);
        }

        private boolean glide(long tick) {
            if (flown >= distance - 1.0E-3) {
                miss();
                return false;
            }
            LivingEntity target = aimed;
            if (target != null && Combat.isTargetable(ctx(), owner, target) && target.getWorld().equals(world)) {
                Vector toward = Fx.body(target).toVector().subtract(chestOf(planned).toVector());
                if (toward.lengthSquared() > 1.0E-4) {
                    direction = clampPitch(direction.clone().multiply(1 - HOMING).add(toward.normalize()
                            .multiply(HOMING)));
                }
            }
            double step = Math.min(GLIDE_SPEED, distance - flown);
            Location next = planned.clone().add(direction.clone().multiply(step));
            Body body = Body.standing(owner);
            if (!Spots.isFree(world, next, body.width(), body.height())) {
                Location over = next.clone().add(0, 0.6, 0);
                if (!Spots.isFree(world, over, body.width(), body.height())) {
                    bonk(next);
                    return false;
                }
                next = over;
            }
            steer(owner, planned, next);
            planned = next;
            flown += step;
            wings.follow(0.9, 0.6);
            glideFx(tick);
            LivingEntity hit = contact();
            if (hit != null) {
                grab(hit, tick);
            }
            return true;
        }

        /** The first enemy (seen by the eagle) its body runs into, by where it is planned to be or seen to be. */
        private @Nullable LivingEntity contact() {
            for (Location at : new Location[] {chestOf(planned), owner.getLocation().add(0, owner.getHeight() * 0.6, 0)}) {
                for (LivingEntity enemy : ctx().enemiesNear(owner, at, GRAB_REACH)) {
                    if (enemy instanceof Player hidden && !owner.canSee(hidden)) {
                        continue;
                    }
                    return enemy;
                }
            }
            return null;
        }

        private void grab(LivingEntity enemy, long tick) {
            Combat.hit(ctx(), owner, enemy, params().num("snatch_damage"));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return; // it fell to the talons; the glide goes on
            }
            prey = enemy;
            carryStart = tick + 1;
            preyStart = enemy.getLocation();
            carryDirection = Motion.horizontal(direction);
            if (carryDirection.lengthSquared() < 1.0E-6) {
                carryDirection = Motion.horizontalFacing(owner.getLocation());
            }
            lift = headroom(enemy, preyStart, Math.max(0, params().num("carry_height")));
            holdSafely(enemy, carryTicks);
            enemy.setFallDistance(0);
            grabFx(enemy);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>낚아채기!</b></" + COLOR_TAG + ">"));
        }

        /** How high the pair can climb: the prey with the eagle on top must fit all the way up. */
        private double headroom(LivingEntity enemy, Location start, double wanted) {
            double width = Math.max(owner.getWidth(), enemy.getWidth());
            double stack = enemy.getHeight() + HANG_GAP + owner.getHeight();
            for (double h = wanted; h > 0; h -= LIFT_STEP) {
                if (Spots.isFree(world, start, width, stack + h)) {
                    return h;
                }
            }
            return 0;
        }

        private boolean carry(LivingEntity held, long c) {
            Location heldPlan = preyPlanned;
            if (!Combat.isTargetable(ctx(), owner, held) || !held.getWorld().equals(world)
                    || heldPlan != null && horizontalDistance(held.getLocation(), heldPlan) > ESCAPE_LEASH + lagSlack(held)) {
                release(false);
                return false;
            }
            // The eagle steers the carry with its view.
            Vector look = Motion.horizontalFacing(owner.getLocation());
            carryDirection = Motion.horizontal(carryDirection.clone().multiply(1 - CARRY_TURN).add(look.multiply(CARRY_TURN)));
            if (carryDirection.lengthSquared() < 1.0E-6) {
                carryDirection = look;
            }
            Location from = heldPlan != null ? heldPlan : preyStart;
            double s = (c + 1.0) / carryTicks;
            double y = preyStart.getY() + lift * (1 - (1 - s) * (1 - s));
            Location next = from.clone().add(carryDirection.clone().multiply(CARRY_SPEED));
            next.setY(y);
            if (!stackFits(held, next)) {
                next = from.clone();
                next.setY(y);
                if (!stackFits(held, next)) {
                    next = from.clone();
                }
            }
            Location ownerNext = next.clone().add(0, held.getHeight() + HANG_GAP, 0);
            // The eagle settles on top of its prey over the first few ticks.
            double settle = Math.min(1.0, (c + 1.0) / 4);
            ownerNext = planned.clone().add(ownerNext.toVector().subtract(planned.toVector()).multiply(settle));
            steer(held, heldPlan, next);
            steer(owner, planned, ownerNext);
            preyPlanned = next;
            planned = ownerNext;
            wings.follow(0.55, 0.1);
            carryFx(held, c);
            if (c + 1 >= carryTicks) {
                release(true);
                return false;
            }
            return true;
        }

        private boolean stackFits(LivingEntity held, Location feet) {
            double width = Math.max(owner.getWidth(), held.getWidth());
            return Spots.isFree(world, feet, width, held.getHeight() + HANG_GAP + owner.getHeight())
                    && ctx().isInsideArena(feet);
        }

        /** Lets go: the prey drops (and is hurt where it lands), the eagle beats its wings once and glides down. */
        private void release(boolean dropped) {
            LivingEntity held = prey;
            prey = null;
            if (held == null) {
                return;
            }
            clearSafety(held);
            if (Combat.isTargetable(ctx(), owner, held)) {
                held.setFallDistance(0);
                held.setVelocity(carryDirection.clone().multiply(0.15).setY(-0.2));
                dropWatch(held);
            }
            owner.setVelocity(carryDirection.clone().multiply(0.25).setY(0.3));
            owner.setFallDistance(0);
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
            Fx.sound(chest, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.6f, 1.8f + Fx.jitter(0.1f));
            featherBurst(chest, 6, false);
            if (dropped) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">떨어뜨리기!</" + COLOR_TAG + ">"));
            }
        }

        /** Nobody caught: the glide eases out and part of the cooldown comes back. */
        private void miss() {
            owner.setVelocity(direction.clone().multiply(0.35).setY(0.05));
            owner.setFallDistance(0);
            double refund = Math.clamp(params().num("miss_refund") / 100.0, 0.0, 1.0);
            if (refund > 0) {
                ctx().reduceCooldown(owner, params().cooldownSeconds() * refund);
            }
            owner.sendActionBar(Text.mm("<gray>낚아채지 못했습니다 · 쿨타임 " + AbilityInfo.formatNumber(
                    Math.round(params().cooldownSeconds() * refund * 10) / 10.0) + "초 회복</gray>"));
        }

        private void bonk(Location at) {
            owner.setVelocity(new Vector(0, 0, 0));
            owner.setFallDistance(0);
            Location chest = chestOf(at);
            Vfx.cloud(ctx(), chest, Particle.CLOUD, 6, 0.25, 0.02, null, false);
            featherBurst(chest, 5, false);
            Fx.sound(chest, Sound.ENTITY_PARROT_HURT, 0.8f, 0.7f);
            miss();
        }

        /** Stops the snatch right away (the ultimate takes over): a held prey is dropped as usual. */
        void abort() {
            if (ended) {
                return;
            }
            if (prey != null) {
                release(true);
            }
            end();
        }

        private void end() {
            if (ended) {
                return;
            }
            ended = true;
            LivingEntity held = prey;
            if (held != null) {
                release(true);
            }
            wings.fold();
            if (snatch == this) {
                snatch = null;
            }
        }

        // -------------------------------------------------------------- visuals

        private void glideFx(long tick) {
            Location chest = chestOf(planned);
            Location back = chest.clone().subtract(direction.clone().multiply(0.9));
            Vfx.trail(back, back.clone().subtract(direction.clone().multiply(1.8)), PALETTE.body(), 5, false);
            Vfx.dot(back, Particle.DUST_COLOR_TRANSITION, SKY, false);
            if (tick % 2 == 0) {
                Vfx.dot(back, Particle.SMALL_GUST, null, false);
            }
            if (tick % 3 == 0) {
                Fx.sound(chest, Sound.ENTITY_PHANTOM_FLAP, 0.6f, 1.6f + Fx.jitter(0.1f));
            }
        }

        private void carryFx(LivingEntity held, long c) {
            Location waist = held.getLocation().add(0, held.getHeight() * 0.7, 0);
            if (c % 2 == 0) {
                Vfx.ring(ctx(), waist, held.getWidth() * 0.5 + 0.3, 10, c * 0.5, Particle.DUST_COLOR_TRANSITION, SKY,
                        false);
            }
            if (c % 4 == 0) {
                Fx.sound(waist, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.5f, 1.6f + Fx.jitter(0.1f));
                featherBurst(waist.clone().add(0, 1.2, 0), 2, false);
            }
            Location ground = held.getLocation().clone();
            Location floor = groundUnder(ground, 16);
            if (floor != null && c % 2 == 1) {
                Vfx.ring(ctx(), floor.add(0, 0.05, 0), 0.5, 8, c * 0.3, Particle.DUST, BROWN, false);
            }
        }
    }

    /** The glide's direction: towards the aimed enemy or along the view, kept to a gentle climb or descent. */
    private static Vector initialDirection(Player owner, @Nullable LivingEntity aimed) {
        Vector direction;
        if (aimed != null) {
            direction = Fx.body(aimed).toVector().subtract(owner.getLocation().add(0, owner.getHeight() * 0.6, 0)
                    .toVector());
        } else {
            direction = owner.getEyeLocation().getDirection();
        }
        return clampPitch(direction);
    }

    private static Vector clampPitch(Vector direction) {
        Vector flat = Motion.horizontal(direction);
        if (flat.lengthSquared() < 1.0E-6) {
            return new Vector(0, 0, 1);
        }
        double length = direction.length();
        double y = length > 1.0E-9 ? Math.clamp(direction.getY() / length, MIN_PITCH_Y, MAX_PITCH_Y) : 0;
        double horizontal = Math.sqrt(1 - y * y);
        return flat.multiply(horizontal).setY(y);
    }

    /** The prey hits the ground: the drop hurts and stuns its legs for a moment. */
    private void dropWatch(LivingEntity held) {
        Player owner = player();
        Loop.repeat(ctx(), owner, 1, 1, DROP_WATCH_TICKS, run -> {
            if (!Combat.isTargetable(ctx(), owner, held)) {
                return false;
            }
            held.setFallDistance(0);
            if (run >= 1 && (Motion.isGrounded(held) || held.isInWater())) {
                land(held);
                return false;
            }
            if (run % 2 == 0) {
                Vfx.dot(held.getLocation().add(0, held.getHeight() + 0.2, 0), Particle.DUST_COLOR_TRANSITION, SKY,
                        false);
            }
            return true;
        });
    }

    private void land(LivingEntity held) {
        Player owner = player();
        Combat.hit(ctx(), owner, held, params().num("drop_damage"));
        long slow = params().ticks("drop_slow");
        if (slow > 0 && Combat.isTargetable(ctx(), owner, held)) {
            held.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) Math.min(Integer.MAX_VALUE, slow), 1,
                    false, true, true));
        }
        Location feet = held.getLocation().add(0, 0.1, 0);
        World world = feet.getWorld();
        world.spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 14), 0.4, 0.05, 0.4, 0.12, floorAt(feet));
        world.spawnParticle(Particle.DUST_PLUME, feet, Vfx.n(ctx(), 5), 0.4, 0.05, 0.4, 0.02);
        Vfx.shockwave(ctx(), owner, feet, 0.3, 1.6, 4, Particle.DUST, BROWN, false);
        Fx.sound(feet, Sound.ENTITY_PLAYER_BIG_FALL, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.ITEM_MACE_SMASH_GROUND, 0.6f, 1.2f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ ultimate: king of the sky

    @Override
    public ActivationResult onUltimate() {
        if (sovereign != null) {
            return ActivationResult.fail("이미 하늘을 날고 있습니다");
        }
        Player owner = player();
        double range = Math.max(1, params().num("ult_range"));
        LivingEntity target = ctx().findTarget(owner, range);
        if (target == null) {
            target = nearestPrey(owner, range);
        }
        if (target == null) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안에 노릴 먹잇감이 없습니다");
        }
        Snatch running = snatch;
        if (running != null) {
            running.abort();
        }
        Sovereign flight = new Sovereign(owner, target);
        sovereign = flight;
        flight.start();
        return ActivationResult.success();
    }

    /** The nearest enemy the eagle can see and reach in a straight line. */
    private @Nullable LivingEntity nearestPrey(Player owner, double range) {
        Location eye = owner.getEyeLocation();
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            if (enemy instanceof Player hidden && !owner.canSee(hidden)) {
                continue;
            }
            if (Spots.clearPath(eye, Fx.body(enemy))) {
                return enemy;
            }
        }
        return null;
    }

    private enum Phase { SWOOP, RISE, CIRCLE, DIVE, DONE }

    /**
     * King of the sky: a swoop onto the prey, a climb with it, a lap of the sky while the eagle aims its dive, and the
     * dive that slams the prey into the aimed ground. Both bodies follow planned paths (the prey hangs under the
     * eagle's talons); the prey cannot use its skills and floats down safely should the eagle fall out of the sky.
     */
    private final class Sovereign implements LongPredicate {

        private final Player owner;
        private final LivingEntity prey;
        private final World world;
        private final Wings wings;
        private final long swoopMax;
        private final long circleTicks;
        private final double reach;
        private Phase phase = Phase.SWOOP;
        private long phaseStart;
        private Location planned;
        private @Nullable Location preyPlanned;
        private Location column;
        private double apexY;
        private Location marker;
        private Location diveFrom;
        private long diveTicks = DIVE_TICKS;
        private boolean ended;

        Sovereign(Player owner, LivingEntity prey) {
            this.owner = owner;
            this.prey = prey;
            this.world = owner.getWorld();
            this.wings = new Wings(owner, 1.25f);
            double range = Math.max(1, params().num("ult_range"));
            this.swoopMax = (long) Math.ceil(range / SWOOP_SPEED) + 6 + lagTicks(owner);
            this.circleTicks = Math.max(4, params().ticks("ult_circle"));
            this.reach = Math.max(0, params().num("dive_reach"));
            this.planned = owner.getLocation();
            this.column = prey.getLocation();
            this.marker = prey.getLocation();
            this.diveFrom = prey.getLocation();
        }

        void start() {
            long life = swoopMax + RISE_TICKS + circleTicks + MAX_DIVE_TICKS + 10;
            wings.spawn(life + 20);
            guardLanding(owner);
            takeoffFx(owner, true);
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
            Fx.sound(chest, Sound.ENTITY_PHANTOM_AMBIENT, 1.4f, 1.5f);
            Fx.sound(chest, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.2f, 1.2f);
            Loop.repeat(ctx(), owner, 0, 1, life, this, this::end);
        }

        @Override
        public boolean test(long tick) {
            if (ended || !owner.getWorld().equals(world)) {
                return false;
            }
            if (owner.getLocation().distance(planned) > DRIFT_LIMIT) {
                abort("<gray>비행이 끊겼습니다</gray>");
                return false;
            }
            if (phase != Phase.SWOOP && (!Combat.isTargetable(ctx(), owner, prey) || !prey.getWorld().equals(world)
                    || escaped())) {
                abort("<gray>먹잇감을 놓쳤습니다</gray>");
                return false;
            }
            long c = tick - phaseStart;
            return switch (phase) {
                case SWOOP -> swoop(tick);
                case RISE -> rise(tick, c);
                case CIRCLE -> circle(tick, c);
                case DIVE -> dive(c);
                case DONE -> false;
            };
        }

        private boolean escaped() {
            Location at = preyPlanned;
            return at != null && horizontalDistance(prey.getLocation(), at) > ESCAPE_LEASH + lagSlack(prey);
        }

        private boolean swoop(long tick) {
            if (!Combat.isTargetable(ctx(), owner, prey) || !prey.getWorld().equals(world)) {
                abort("<gray>먹잇감이 사라졌습니다</gray>");
                return false;
            }
            Location chest = chestOf(planned);
            Vector toward = Fx.body(prey).toVector().subtract(chest.toVector());
            double gap = toward.length();
            Location actual = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
            if (gap <= ULT_GRAB_REACH || actual.distance(Fx.body(prey)) <= ULT_GRAB_REACH) {
                grab(tick);
                return true;
            }
            if (tick >= swoopMax) {
                abort("<gray>먹잇감에 닿지 못했습니다</gray>");
                return false;
            }
            Vector direction = toward.multiply(1 / gap);
            double step = Math.min(SWOOP_SPEED, gap - ULT_GRAB_REACH * 0.6);
            Location next = planned.clone().add(direction.clone().multiply(step));
            Body body = Body.standing(owner);
            if (!Spots.isFree(world, next, body.width(), body.height())) {
                Location over = next.clone().add(0, 0.7, 0);
                if (!Spots.isFree(world, over, body.width(), body.height())) {
                    abort("<gray>장애물에 막혔습니다</gray>");
                    return false;
                }
                next = over;
            }
            steer(owner, planned, next);
            planned = next;
            wings.follow(0.8, 0.8);
            Location back = chestOf(planned).subtract(direction.clone().multiply(0.8));
            Vfx.trail(back, back.clone().subtract(direction.clone().multiply(2.2)), PALETTE.core(), 5, true);
            Vfx.dot(back, Particle.DUST_COLOR_TRANSITION, SKY, true);
            if (tick % 2 == 0) {
                Fx.sound(back, Sound.ENTITY_PHANTOM_FLAP, 0.8f, 1.4f + Fx.jitter(0.1f));
            }
            return true;
        }

        private void grab(long tick) {
            if (prey instanceof Player held) {
                ctx().silence(held, RISE_TICKS + circleTicks + MAX_DIVE_TICKS + 4);
            }
            holdSafely(prey, RISE_TICKS + circleTicks + MAX_DIVE_TICKS);
            prey.setFallDistance(0);
            column = prey.getLocation();
            Location ground = groundUnder(column.clone().add(0, 0.3, 0), 16);
            marker = ground != null ? ground : column.clone();
            double leap = headroom(column, Math.max(0, params().num("ult_height")));
            apexY = column.getY() + leap;
            phase = Phase.RISE;
            phaseStart = tick + 1;
            grabFx(prey);
            Fx.sound(Fx.body(prey), Sound.ENTITY_PHANTOM_BITE, 1.0f, 0.7f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>하늘의 제왕</b></" + COLOR_TAG + "> <gray>내리꽂을 곳을 "
                    + "바라보세요</gray>"));
        }

        /** How high the pair can climb from {@code start}: the prey with the eagle on top must fit all the way. */
        private double headroom(Location start, double wanted) {
            double width = Math.max(owner.getWidth(), prey.getWidth()) + 0.1;
            double stack = prey.getHeight() + HANG_GAP + owner.getHeight();
            for (double h = wanted; h > 0; h -= LIFT_STEP) {
                if (Spots.isFree(world, start, width, stack + h) && start.getY() + h + stack < world.getMaxHeight()) {
                    return h;
                }
            }
            return 0;
        }

        private boolean rise(long tick, long c) {
            double s = Math.min(1.0, (c + 1.0) / RISE_TICKS);
            double y = column.getY() + (apexY - column.getY()) * (1 - (1 - s) * (1 - s));
            Location preyNext = new Location(world, column.getX(), y, column.getZ());
            moveStack(preyNext, Math.min(1.0, (c + 1.0) / 4));
            wings.follow(0.75, 0.0);
            speedLines(-1, tick);
            if (c == 0) {
                Location ground = marker.clone().add(0, 0.1, 0);
                Vfx.shockwave(ctx(), owner, ground, 0.3, 2.2, 5, Particle.CLOUD, null, false);
                Fx.sound(ground, Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, 0.8f);
            }
            if (c + 1 >= RISE_TICKS) {
                phase = Phase.CIRCLE;
                phaseStart = tick + 1;
                Fx.sound(chestOf(planned), Sound.ENTITY_PHANTOM_AMBIENT, 1.5f, 1.3f);
            }
            return true;
        }

        private boolean circle(long tick, long c) {
            double ease = Math.min(1.0, (c + 1.0) / ORBIT_EASE);
            double angle = (c + 1.0) / circleTicks * TAU;
            Location preyNext = new Location(world, column.getX() + Math.cos(angle) * ORBIT_RADIUS * ease,
                    apexY + Math.sin(c * 0.4) * 0.15, column.getZ() + Math.sin(angle) * ORBIT_RADIUS * ease);
            if (!stackFits(preyNext)) {
                Location at = preyPlanned;
                preyNext = at != null ? at.clone() : preyNext;
            }
            moveStack(preyNext, 1.0);
            wings.follow(0.35, 0.0);
            Location aimed = aimMarker();
            if (aimed != null) {
                marker = aimed;
            }
            if (tick % 2 == 0) {
                reticle(c);
            }
            if (c % 5 == 0) {
                long left = Math.max(0, circleTicks - c);
                owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>◎ 급강하까지 " + Fx.seconds(left) + "초</b></" + GOLD_TAG
                        + "> <gray>바라보는 땅에 내리꽂습니다</gray>"));
            }
            if (c + 1 >= circleTicks) {
                Location from = preyPlanned;
                diveFrom = from != null ? from.clone() : preyNext.clone();
                if (!Spots.clearPath(chestAbove(diveFrom), chestAbove(marker))) {
                    marker = fallbackMarker();
                }
                // Long dives take a little longer, so the last (fastest) steps stay under the speed cap.
                double length = diveFrom.distance(marker);
                diveTicks = Math.clamp((long) Math.ceil(length / DIVE_STEP), DIVE_TICKS, MAX_DIVE_TICKS);
                phase = Phase.DIVE;
                phaseStart = tick + 1;
                Fx.sound(chestOf(planned), Sound.ENTITY_PHANTOM_SWOOP, 1.4f, 0.6f);
                Fx.sound(chestOf(planned), Sound.ITEM_ELYTRA_FLYING, 0.8f, 1.6f);
            }
            return true;
        }

        private boolean dive(long c) {
            if (c >= diveTicks) {
                // The last step was sent last tick: the pair has arrived (impacting earlier would hit in mid-air).
                impact();
                return false;
            }
            double s = Math.min(1.0, (c + 1.0) / diveTicks);
            double eased = s * s;
            Location preyNext = diveFrom.clone().add(marker.toVector().subtract(diveFrom.toVector()).multiply(eased));
            moveStack(preyNext, 1.0);
            wings.follow(0.2, 1.0);
            Vector fall = marker.toVector().subtract(diveFrom.toVector());
            if (fall.lengthSquared() > 1.0E-6) {
                fall.normalize();
                Location body = chestOf(preyNext);
                for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                    Location start = body.clone().add(ThreadLocalRandom.current().nextDouble(-0.4, 0.4),
                            ThreadLocalRandom.current().nextDouble(-0.4, 0.4),
                            ThreadLocalRandom.current().nextDouble(-0.4, 0.4));
                    Vfx.trail(start, start.clone().subtract(fall.clone().multiply(2.5)), i % 2 == 0 ? PALETTE.core()
                            : GOLD, 5, true);
                }
            }
            reticle(c);
            return true;
        }

        /** Moves the prey to {@code preyNext} and the eagle onto it ({@code settle} blends the eagle into place). */
        private void moveStack(Location preyNext, double settle) {
            Location ownerNext = preyNext.clone().add(0, prey.getHeight() + HANG_GAP, 0);
            if (settle < 1) {
                ownerNext = planned.clone().add(ownerNext.toVector().subtract(planned.toVector()).multiply(settle));
            }
            steer(prey, preyPlanned, preyNext);
            steer(owner, planned, ownerNext);
            preyPlanned = preyNext;
            planned = ownerNext;
            // The prey dangles in a ring of sky-blue light under the talons.
            Location waist = preyNext.clone().add(0, prey.getHeight() * 0.7, 0);
            Vfx.ring(ctx(), waist, prey.getWidth() * 0.5 + 0.3, 8, preyNext.getY() * 2, Particle.DUST_COLOR_TRANSITION,
                    SKY, true);
        }

        private boolean stackFits(Location preyFeet) {
            double width = Math.max(owner.getWidth(), prey.getWidth());
            return Spots.isFree(world, preyFeet, width, prey.getHeight() + HANG_GAP + owner.getHeight());
        }

        /** Where the eagle's view meets the ground, within reach of the column and safe for the prey; null if not. */
        private @Nullable Location aimMarker() {
            Location eye = owner.getEyeLocation();
            Vector look = eye.getDirection();
            RayTraceResult hit = world.rayTraceBlocks(eye, look, MARKER_RANGE, FluidCollisionMode.NEVER, true);
            if (hit == null) {
                return null;
            }
            Vector point = hit.getHitPosition();
            Vector offset = point.clone().subtract(column.toVector()).setY(0);
            if (offset.length() > reach) {
                offset.normalize().multiply(reach);
            }
            double top = Math.min(apexY, point.getY() + 3);
            Location probe = new Location(world, column.getX() + offset.getX(), top, column.getZ() + offset.getZ());
            Location feet = groundUnder(probe, Math.max(2, top - point.getY() + 8));
            Location from = preyPlanned;
            if (feet == null || !Spots.isSafe(ctx(), feet, Body.standing(prey))
                    || from != null && !Spots.clearPath(chestAbove(from), chestAbove(feet))) {
                return null;
            }
            return feet;
        }

        /** The ground right under the column (where the prey was seized), or the seizing spot itself. */
        private Location fallbackMarker() {
            Location ground = groundUnder(column.clone().add(0, 0.3, 0), 16);
            return ground != null ? ground : column.clone();
        }

        private Location chestAbove(Location feet) {
            return feet.clone().add(0, prey.getHeight() * 0.6, 0);
        }

        /** The golden reticle on the marked ground: everyone can see where the eagle will strike. */
        private void reticle(long c) {
            Location ground = marker.clone().add(0, 0.1, 0);
            double radius = Math.max(0.5, params().num("impact_radius"));
            Vfx.ring(ctx(), ground, radius, (int) Math.min(36, Math.max(12, radius * 7)), c * 0.12,
                    Particle.DUST_COLOR_TRANSITION, GOLD_FADE, true);
            Vfx.ring(ctx(), ground, radius * 0.35, 8, -c * 0.3, Particle.DUST, GOLD_DUST, true);
            for (int i = 0; i < 4; i++) {
                double angle = c * 0.12 + i * Math.PI / 2;
                Location inner = ground.clone().add(Math.cos(angle) * radius * 0.5, 0, Math.sin(angle) * radius * 0.5);
                Location outer = ground.clone().add(Math.cos(angle) * radius * 1.1, 0, Math.sin(angle) * radius * 1.1);
                Vfx.line(ctx(), inner, outer, 3, Particle.DUST, GOLD_DUST, true);
            }
            if (c % 4 == 0) {
                Vfx.dot(ground.clone().add(0, 0.3, 0), Particle.END_ROD, null, true);
            }
        }

        private void speedLines(int direction, long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location from = Fx.body(prey);
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Location start = from.clone().add(random.nextDouble(-0.6, 0.6), random.nextDouble(-0.6, 0.6),
                        random.nextDouble(-0.6, 0.6));
                Vfx.trail(start, start.clone().add(0, direction * random.nextDouble(2.0, 3.0), 0),
                        (i + tick) % 2 == 0 ? PALETTE.core() : PALETTE.body(), 5, true);
            }
            if (tick % 3 == 0) {
                featherBurst(chestOf(planned), 2, true);
            }
        }

        /** The dive lands: the prey is slammed into the ground, its friends around it thrown up. */
        private void impact() {
            phase = Phase.DONE;
            clearSafety(prey);
            prey.setFallDistance(0);
            Combat.hit(ctx(), owner, prey, params().num("ult_damage"));
            if (Combat.isTargetable(ctx(), owner, prey)) {
                prey.setVelocity(new Vector(0, 0.2, 0));
            }
            Location point = marker.clone();
            double radius = Math.max(0, params().num("impact_radius"));
            int splashed = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, point.clone().add(0, 0.5, 0), radius)) {
                if (enemy.equals(prey)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, params().num("impact_damage"));
                splashed++;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(point.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    double angle = ThreadLocalRandom.current().nextDouble(TAU);
                    away = new Vector(Math.cos(angle), 0, Math.sin(angle));
                }
                Motion.push(enemy, away.multiply(IMPACT_PUSH).setY(IMPACT_LIFT), MAX_SPEED);
            }
            owner.setFallDistance(0);
            Vector back = Motion.horizontal(diveFrom.toVector().subtract(marker.toVector()));
            owner.setVelocity(back.multiply(0.25).setY(REBOUND_UP));
            impactFx(point, radius);
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>하늘의 제왕!</b></" + GOLD_TAG + "> <white>"
                    + AbilityInfo.formatNumber(params().num("ult_damage")) + " 피해</white>"
                    + (splashed > 0 ? " <gray>· 충격파 " + splashed + "명</gray>" : "")));
        }

        private void impactFx(Location point, double radius) {
            Location ground = point.clone().add(0, 0.1, 0);
            BlockData floor = floorAt(ground);
            world.spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 40), radius * 0.4, 0.15, radius * 0.4, 0.2, floor,
                    true);
            world.spawnParticle(Particle.DUST_PLUME, ground, Vfx.n(ctx(), 12), radius * 0.3, 0.1, radius * 0.3, 0.03,
                    null, true);
            Vfx.dot(ground.clone().add(0, 0.8, 0), Particle.FLASH, PALETTE.core(), true);
            Vfx.dot(ground.clone().add(0, 0.8, 0), Particle.SONIC_BOOM, null, true);
            Vfx.shockwave(ctx(), owner, ground, 0.5, Math.max(1, radius), 6, Particle.DUST_COLOR_TRANSITION, GOLD_FADE,
                    true);
            Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.2, 0), 0.4, Math.max(1, radius) * 0.8, 5, Particle.CLOUD,
                    null, false);
            featherBurst(ground.clone().add(0, 1.0, 0), 16, true);
            cracks(ground, radius);
            Fx.sound(ground, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.4f, 0.7f);
            Fx.sound(ground, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.4f + Fx.jitter(0.05f));
            Fx.sound(ground, Sound.ENTITY_PHANTOM_BITE, 1.0f, 0.5f);
            Fx.sound(ground, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 0.9f);
            Location center = point.clone();
            Loop.repeat(ctx(), owner, 3, 2, 8, run -> {
                for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                    Location mote = Vfx.scatter(center, radius * 0.7).add(0, 0.2, 0);
                    Vfx.trail(mote, mote.clone().add(0, 1.2, 0), run % 2 == 0 ? GOLD : PALETTE.body(), 12, false);
                }
                return true;
            });
        }

        /** Dark cracks radiating from the impact, fading after a moment. */
        private void cracks(Location ground, double radius) {
            double phase = ThreadLocalRandom.current().nextDouble(TAU);
            Location floor = ground.clone();
            floor.setY(marker.getY());
            Location anchor = Displays.unrotated(floor);
            for (int i = 0; i < CRACKS; i++) {
                double angle = phase + i * TAU / CRACKS;
                float length = (float) (radius * (0.55 + 0.25 * ThreadLocalRandom.current().nextDouble()));
                float yaw = (float) Math.toDegrees(Math.atan2(-Math.cos(angle), Math.sin(angle)));
                float x = (float) (Math.cos(angle) * length / 2);
                float z = (float) (Math.sin(angle) * length / 2);
                BlockDisplay crack = Displays.block(ctx(), owner, anchor, CRACK.createBlockData(),
                        Displays.transform(x * 0.2f, 0.015f, z * 0.2f, 0.08f, 0.02f, length * 0.2f, yaw, 0, 0),
                        CRACK_TICKS + 10);
                crack.setBrightness(null);
                Displays.animateNextTick(ctx(), owner, crack, Displays.transform(x, 0.015f, z, 0.08f, 0.02f, length,
                        yaw, 0, 0), 3);
                ctx().runLater(owner, CRACK_TICKS, () -> {
                    if (crack.isValid()) {
                        Displays.shrinkAway(ctx(), owner, crack, 8);
                    }
                });
            }
        }

        /** The flight breaks off: the prey (with its safety net) and the eagle drop where they are. */
        private void abort(String reason) {
            if (phase == Phase.DONE) {
                return;
            }
            phase = Phase.DONE;
            owner.setVelocity(new Vector(0, 0.1, 0));
            owner.setFallDistance(0);
            if (Combat.isTargetable(ctx(), owner, prey)) {
                prey.setVelocity(new Vector(0, 0, 0));
                prey.setFallDistance(0);
            }
            owner.sendActionBar(Text.mm(reason));
        }

        private void end() {
            if (ended) {
                return;
            }
            ended = true;
            wings.fold();
            if (sovereign == this) {
                sovereign = null;
            }
        }
    }

    // ------------------------------------------------------------------ shared visuals

    /** Lift-off: a gust under the eagle, a ring of wind on the ground and a few feathers. */
    private void takeoffFx(Player owner, boolean force) {
        Location feet = owner.getLocation();
        Location chest = feet.clone().add(0, owner.getHeight() * 0.6, 0);
        World world = chest.getWorld();
        world.spawnParticle(Particle.GUST, feet.clone().add(0, 0.4, 0), 1, 0, 0, 0, 0, null, force);
        Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 1.0, 14, 0, Particle.DUST_COLOR_TRANSITION, SKY, force);
        featherBurst(chest, 8, force);
        Fx.sound(chest, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.9f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(chest, Sound.ENTITY_PHANTOM_FLAP, 1.0f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(chest, Sound.ENTITY_BREEZE_WIND_BURST, 0.6f, 1.4f + Fx.jitter(0.1f));
    }

    /** Talons closing on the prey: a sky-blue ring snapping shut, a flash and a few torn feathers. */
    private void grabFx(LivingEntity prey) {
        Location body = Fx.body(prey);
        Vfx.dot(body, Particle.FLASH, PALETTE.body(), true);
        Vfx.cloud(ctx(), body, Particle.CRIT, 10, 0.3, 0.25, null, true);
        featherBurst(body, 6, true);
        Location feet = prey.getLocation().add(0, 0.1, 0);
        Loop.repeat(ctx(), player(), 0, 1, 3, run -> {
            Vfx.ring(ctx(), feet.clone().add(0, prey.getHeight() * 0.6, 0), 1.2 - 0.3 * run, 14, run * 0.6,
                    Particle.DUST_COLOR_TRANSITION, SKY, true);
            return true;
        });
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ENTITY_PHANTOM_AMBIENT, 1.0f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1.2f);
    }

    /** Feathers drifting off (item particles of a feather). */
    private void featherBurst(Location at, int count, boolean force) {
        at.getWorld().spawnParticle(Particle.ITEM, at, Vfx.n(ctx(), count), 0.3, 0.3, 0.3, 0.06,
                new ItemStack(FEATHER), force);
    }

    // ------------------------------------------------------------------ flight helpers

    /**
     * Keeps the owner's fall distance at zero until it has stood on the ground for a moment (at most
     * {@link #LANDING_GRACE_TICKS}). A loop rather than a {@link LandingGuard}, so a Gambler that borrowed the
     * ultimate is protected too.
     */
    private void guardLanding(Player owner) {
        int[] grounded = {0};
        Loop.repeat(ctx(), owner, 0, 1, LANDING_GRACE_TICKS, run -> {
            owner.setFallDistance(0);
            boolean flying = snatch != null || sovereign != null;
            grounded[0] = !flying && (Motion.isGrounded(owner) || owner.isInWater()) ? grounded[0] + 1 : 0;
            return grounded[0] < LandingGuard.SETTLE_TICKS;
        });
    }

    /**
     * Steers a body to {@code next}: the step from the last planned spot plus a share of the gap between that spot and
     * where the server sees the body (a player's server position lags its client, so the plan leads).
     */
    private static void steer(LivingEntity entity, @Nullable Location planned, Location next) {
        Vector current = entity.getLocation().toVector();
        Vector velocity = planned == null ? next.toVector().subtract(current)
                : next.toVector().subtract(planned.toVector())
                        .add(planned.toVector().subtract(current).multiply(STEER_GAIN));
        entity.setVelocity(Motion.clamp(velocity, MAX_SPEED));
        entity.setFallDistance(0);
    }

    /** Slow falling as a safety net for a carried body, removed with {@link #clearSafety} when it is let go. */
    private static void holdSafely(LivingEntity held, long holdTicks) {
        int ticks = (int) Math.min(Integer.MAX_VALUE, holdTicks + 60);
        held.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, ticks, 0, true, false, false));
    }

    /** Removes the safety net of {@link #holdSafely}, never a slow falling the body got elsewhere. */
    private static void clearSafety(LivingEntity held) {
        PotionEffect effect = held.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (effect != null && effect.isAmbient() && !effect.hasParticles() && !effect.hasIcon()
                && effect.getAmplifier() == 0) {
            held.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
    }

    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / MILLIS_PER_TICK));
    }

    /** Extra leash for a player whose server position trails its client. */
    private static double lagSlack(LivingEntity entity) {
        return entity instanceof Player player ? lagTicks(player) * 0.4 : 0;
    }

    private static Location chestOf(Location feet) {
        return feet.clone().add(0, 1.0, 0);
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /**
     * The ground under {@code point}: the top of the first solid block at most {@code maxDrop} blocks below it, or
     * null. The probe starts a little above the point (a ray starting on a face reports a hit inside the block).
     */
    private static @Nullable Location groundUnder(Location point, double maxDrop) {
        Location from = point.clone().add(0, 0.05, 0);
        RayTraceResult ground = point.getWorld().rayTraceBlocks(from, new Vector(0, -1, 0), maxDrop + 0.05,
                FluidCollisionMode.NEVER, true);
        if (ground == null) {
            return null;
        }
        Location at = point.clone();
        at.setY(ground.getHitPosition().getY());
        return at;
    }

    private static BlockData floorAt(Location at) {
        Block below = at.clone().subtract(0, 0.2, 0).getBlock();
        return below.getType().isAir() ? Material.DIRT.createBlockData() : below.getBlockData();
    }
}
