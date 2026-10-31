package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 큐피드 — the god of love. Glides on feathered wings (and beats them once more in mid-air), shoots heart-tipped
 * arrows that charm their target into strolling towards Cupid unable to hurt her, and as the ultimate ties two enemies
 * together with the red string of fate, which pulls them into each other until they collide in a burst of hearts.
 *
 * <p>Look (pink, white, gold): two wings of white and pink feathers on Cupid's back, folded while she walks, spread
 * and fluttering as she glides with petals drifting off them, beating hard with a gust when she flaps. The love arrow
 * is a birch shaft with white fletching and a glowing pink diamond tip, trailing pink motes and hearts. A charmed enemy
 * has a pulsing heart over its head and a pink thread of motes running to Cupid; its blows on her burst into hearts.
 * The red string is a thin red cord between the two victims, trembling and knotted around their waists, that shortens
 * as it reels them in; the collision draws a huge heart of light across the impact and pops a giant heart.
 */
public final class CupidAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("cupid", "큐피드", Material.PINK_TULIP)
            .color(TextColor.color(0xFF7EB6))
            .difficulty(2)
            .passive("사랑의 날개",
                    "점프보다 높은 곳에서 떨어지면 날개를 펴고 천천히 내려오며 낙하 피해를 받지 않습니다. 공중에서 "
                            + "웅크리면 날갯짓으로 {flap_height}블록 더 날아올라 날개를 폅니다({flap_cooldown}초마다). 날개를 "
                            + "편 채 쏜 사랑의 화살은 피해가 {air_bonus}% 늘고 매혹이 {air_charm}초 길어집니다.")
            .active("사랑의 화살",
                    "하트 화살을 쏩니다({arrow_range}블록). 처음 맞은 적은 {arrow_damage} 피해를 입고 {charm_duration}초간 "
                            + "매혹되어 나에게 천천히 걸어오며, 그동안 나를 공격해도 피해를 주지 못합니다.",
                    9)
            .ultimate("붉은 실",
                    "조준한 적({thread_range}블록 이내)과 그 적에게서 가장 가까운 다른 적({thread_link}블록 이내, 벽 너머 "
                            + "제외)을 붉은 실로 묶습니다. {thread_delay}초 뒤 실이 당겨져 두 적이 서로에게 끌려가 부딪히고, "
                            + "하트가 터지며 둘 다 {ult_damage} 피해를 입고 반경 {burst_radius}블록의 다른 적은 "
                            + "{burst_damage} 피해를 입습니다. 휘말린 적은 모두 {ult_slow}초간 구속 II에 걸리고 튕겨 나갑니다. "
                            + "묶을 다른 적이 없으면 실이 나와 이어져, 그 적이 내 앞으로 끌려와 똑같이 부딪힙니다. 벽에 막혀 "
                            + "만나지 못하면 실이 끊어집니다.")
            .param("flap_height", 3)
            .param("flap_cooldown", 5)
            .param("air_bonus", 40)
            .param("air_charm", 0.5)
            .param("arrow_range", 28)
            .param("arrow_damage", 3)
            .param("charm_duration", 2)
            .param("thread_range", 24)
            .param("thread_link", 12)
            .param("thread_delay", 0.5)
            .param("ult_damage", 6)
            .param("burst_radius", 3)
            .param("burst_damage", 3)
            .param("ult_slow", 2)
            .build();

    private static final String PINK_TAG = "#FF7EB6";
    private static final String RED_TAG = "#E0465E";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** White glow, rose pink, the deep red of the string of fate. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF4FA, 0xFF8FC7, 0xC2185B);
    private static final Color PINK = Color.fromRGB(0xFF8FC7);
    private static final Color WHITE = Color.fromRGB(0xFFF4FA);
    private static final Color RED = Color.fromRGB(0xE0465E);
    private static final Color GOLD = Color.fromRGB(0xFFD36E);
    private static final Particle.DustTransition PINK_GLOW = PALETTE.glow(0.9f);
    private static final Particle.DustOptions PINK_DUST = PALETTE.dust(0.8f);
    private static final Particle.DustOptions RED_DUST = new Particle.DustOptions(RED, 0.9f);
    private static final Particle.DustTransition RED_GLOW = new Particle.DustTransition(Color.fromRGB(0xFF9AAA), RED,
            1.2f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 0.7f);

    /** Block types of the visuals; block data is created on use (it needs a running server). */
    private static final Material FEATHER = Material.WHITE_WOOL;
    private static final Material INNER_FEATHER = Material.PINK_WOOL;
    private static final Material SHAFT = Material.BIRCH_PLANKS;
    private static final Material TIP = Material.PINK_CONCRETE;
    private static final Material FLETCHING = Material.WHITE_WOOL;
    private static final Material CORD = Material.RED_WOOL;

    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 1.6;
    private static final double REST_FALL = -Motion.GRAVITY * Motion.DRAG;

    // passive: wings
    /**
     * The wings open once Cupid has fallen this far below the top of her flight: further than a plain jump ever
     * drops (1.25 blocks), so a hop stays a hop (and keeps its critical hit), well short of a damaging fall.
     */
    private static final double GLIDE_DROP = 1.6;
    private static final int SLOW_FALL_TICKS = 10;
    /** Elevation (degrees above outwards) of the three feathers of a wing, relative to its pose. */
    private static final float[] FEATHER_TILT = {28f, 4f, -20f};
    private static final float[] FEATHER_LENGTH = {1.2f, 1.0f, 0.75f};
    private static final float FEATHER_WIDTH = 0.17f;
    private static final float FEATHER_THICKNESS = 0.045f;
    private static final float WING_ROOT_HEIGHT = 1.35f;
    private static final float WING_ROOT_BACK = 0.2f;
    private static final float WING_ROOT_SIDE = 0.12f;
    private static final int FLAP_TICKS = 8;
    private static final double FLAP_FORWARD = 0.35;
    private static final long FLAP_GUARD_TICKS = 160;

    // active: love arrow
    private static final double ARROW_SPEED = 1.8;
    private static final double ARROW_PAD = 0.3;
    private static final int STUCK_TICKS = 12;
    /** A charmed enemy stops this far from Cupid, gazing at her. */
    private static final double CHARM_STOP = 1.8;
    private static final double CHARM_SPEED = 0.2;
    private static final long MAX_LAG_TICKS = 8;

    // ultimate: red string
    private static final int PULL_MAX_TICKS = 24;
    private static final double PULL_SPEED = 0.9;
    /** Two pulled enemies collide when their middles are this close. */
    private static final double COLLIDE_DISTANCE = 1.3;
    /** A lone enemy tied to Cupid is reeled to this spot in front of her. */
    private static final double SOLO_STOP = 2.2;
    /** A lone enemy's reckoned stride counts as arrived this close to its spot in front of Cupid. */
    private static final double SOLO_ARRIVE = 0.4;
    private static final double BOUNCE_SPEED = 0.55;
    private static final double BOUNCE_LIFT = 0.35;
    private static final float CORD_WIDTH = 0.05f;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final BlockDisplay[] wings = new BlockDisplay[6];
    private final LandingGuard landing = new LandingGuard();
    private final Map<UUID, Charm> charmed = new HashMap<>();
    private int airTicks;
    /** The wings are open: after a flap, or once a fall has gone deeper than a jump. */
    private boolean gliding;
    /** The highest point of the current time in the air. */
    private double peakY;
    private boolean wasSneaking;
    private long flapReady = Long.MIN_VALUE / 2;
    private long flapStart = Long.MIN_VALUE / 2;
    private double flutter;

    /**
     * One charmed enemy: until when, the heart over its head, and where its stroll has taken it by the steps sent so
     * far (dead reckoning, see {@code MagnetAbility}: the server sees a player a round trip late).
     */
    private static final class Charm {
        final LivingEntity victim;
        long until;
        @Nullable TextDisplay heart;
        boolean hidden;
        double x;
        double z;

        Charm(LivingEntity victim) {
            this.victim = victim;
            Location at = victim.getLocation();
            this.x = at.getX();
            this.z = at.getZ();
        }
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        Player owner = player();
        if (!reserve(wings.length)) {
            return;
        }
        Location feet = owner.getLocation();
        for (int i = 0; i < wings.length; i++) {
            boolean left = i < 3;
            int feather = i % 3;
            BlockData data = (feather == 2 ? INNER_FEATHER : FEATHER).createBlockData();
            wings[i] = keep(Displays.block(ctx(), owner, feet, data,
                    featherPose(left, feather, -55f, 75f, 0.05f, owner.getBodyYaw(), 1f, rootHeight(owner)), 0));
            wings[i].setTeleportDuration(1);
        }
    }

    @Override
    public void onRevoke() {
        Player owner = player();
        if (owner.isOnline()) {
            removeOwnSlowFalling(owner);
        }
        charmed.clear();
        live.clear();
        landing.disarm();
    }

    // ------------------------------------------------------------------ passive: wings of love

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        landing.update(owner, now);
        boolean grounded = Motion.isGrounded(owner) || owner.isInWater() || owner.isFlying();
        double y = owner.getLocation().getY();
        if (grounded) {
            airTicks = 0;
            gliding = false;
        } else {
            peakY = airTicks == 0 ? y : Math.max(peakY, y);
            airTicks++;
            if (peakY - y >= GLIDE_DROP) {
                gliding = true;
            }
        }

        boolean sneaking = owner.isSneaking();
        // A silenced (petrified, stunned...) Cupid cannot beat her wings.
        if (sneaking && !wasSneaking && airTicks >= 2 && now >= flapReady && !ctx().isSilenced(owner)) {
            flap(owner, now);
        }
        wasSneaking = sneaking;

        if (gliding) {
            owner.setFallDistance(0);
            PotionEffect current = owner.getPotionEffect(PotionEffectType.SLOW_FALLING);
            if (current == null || current.getDuration() < SLOW_FALL_TICKS / 2) {
                owner.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, SLOW_FALL_TICKS, 0, false,
                        false, true));
            }
            if (tick % 3 == 0) {
                glideFx(owner, tick);
            }
        } else if (airTicks == 0) {
            removeOwnSlowFalling(owner);
        }
        poseWings(owner, now, gliding);
        tickCharms(owner, now);
    }

    /** The wings: folded along the back on the ground, spread and fluttering in a glide, beating after a flap. */
    private void poseWings(Player owner, long now, boolean gliding) {
        Location feet = owner.getLocation();
        float yaw = owner.getBodyYaw();
        float scale = (float) Body.scaleOf(owner);
        long sinceFlap = now - flapStart;
        float elevation;
        float sweep;
        float length;
        if (sinceFlap >= 0 && sinceFlap < FLAP_TICKS) {
            // One deep beat: up, then hard down.
            double t = (double) sinceFlap / FLAP_TICKS;
            elevation = (float) (45 - 85 * Math.sin(t * Math.PI));
            sweep = 15f;
            length = 1.1f;
        } else if (gliding) {
            flutter += 0.35;
            elevation = 8f + (float) Math.sin(flutter) * 7f;
            sweep = 22f;
            length = 1f;
        } else {
            elevation = -58f;
            sweep = 72f;
            length = 0.62f;
        }
        for (int i = 0; i < wings.length; i++) {
            BlockDisplay wing = wings[i];
            if (wing == null || !wing.isValid() || !wing.getWorld().equals(owner.getWorld())) {
                continue;
            }
            Displays.moveTo(wing, feet);
            Displays.animate(wing, featherPose(i < 3, i % 3, elevation, sweep, length, yaw, scale,
                    rootHeight(owner)), 2);
        }
    }

    /**
     * Feather {@code feather} (0 top .. 2 bottom) of the left or right wing: a flat box from the wing root on the
     * upper back, pointing outwards, raised by {@code elevation} plus the feather's own tilt and swept back by
     * {@code sweep} degrees, then turned with the body's yaw. The display itself is never rotated.
     */
    private static Transformation featherPose(boolean left, int feather, float elevation, float sweep, float length,
                                              float bodyYaw, float scale, float rootHeight) {
        double tilt = Math.toRadians(elevation + FEATHER_TILT[feather]);
        double back = Math.toRadians(sweep + feather * 8);
        Quaternionf wing = left
                ? new Quaternionf().rotateY((float) back).rotateZ((float) tilt)
                : new Quaternionf().rotateY((float) -back).rotateZ((float) (Math.PI - tilt));
        Quaternionf turn = yawTurn(bodyYaw);
        Quaternionf rotation = new Quaternionf(turn).mul(wing);
        float size = FEATHER_LENGTH[feather] * length * scale;
        Vector3f root = new Vector3f((left ? WING_ROOT_SIDE : -WING_ROOT_SIDE) * scale, rootHeight,
                -WING_ROOT_BACK * scale);
        Vector3f along = rotation.transform(new Vector3f(1, 0, 0)).mul(size / 2);
        Vector3f center = turn.transform(root).add(along);
        return box(center, new Vector3f(Math.max(0.001f, size), FEATHER_WIDTH * scale, FEATHER_THICKNESS * scale),
                rotation);
    }

    /** Height of the wing roots on the owner's back: lower while it crouches, scaled with its size. */
    private static float rootHeight(Player owner) {
        return (float) (WING_ROOT_HEIGHT * owner.getHeight() / 1.8);
    }

    /** Petals and feather motes drifting off the spread wings. */
    private void glideFx(Player owner, long tick) {
        Location back = owner.getLocation().add(0, owner.getHeight() * 0.72, 0)
                .subtract(Motion.horizontalFacing(owner.getLocation()).multiply(0.35));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int side = -1; side <= 1; side += 2) {
            Vector across = Motion.horizontalFacing(owner.getLocation()).crossProduct(new Vector(0, 1, 0))
                    .multiply(side * (0.5 + random.nextDouble(0.4)));
            Location tip = back.clone().add(across);
            Vfx.dot(tip, Particle.DUST_COLOR_TRANSITION, PINK_GLOW, false);
            if (tick % 6 == 0) {
                Vfx.dot(tip, Particle.CHERRY_LEAVES, null, false);
            }
        }
        if (tick % 12 == 0) {
            Fx.sound(back, Sound.ITEM_ELYTRA_FLYING, 0.06f, 1.9f);
        }
    }

    /** The wing beat: Cupid shoots up (a fresh velocity: a player's server velocity is stale) and glides on. */
    private void flap(Player owner, long now) {
        flapReady = now + params().ticks("flap_cooldown");
        flapStart = now;
        double height = Math.max(0, params().num("flap_height"));
        Vector forward = Motion.horizontalFacing(owner.getLocation()).multiply(FLAP_FORWARD);
        owner.setVelocity(Motion.clamp(forward.setY(Motion.launchSpeed(height)), MAX_SPEED));
        owner.setFallDistance(0);
        removeOwnSlowFalling(owner);
        landing.arm(now, FLAP_GUARD_TICKS);
        gliding = true; // the wings stay open after the beat

        Location back = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        World world = back.getWorld();
        world.spawnParticle(Particle.GUST_EMITTER_SMALL, owner.getLocation().add(0, 0.2, 0), 1, 0, 0, 0, 0, null,
                false);
        Vfx.ring(ctx(), owner.getLocation().add(0, 0.1, 0), 1.0, 14, 0, Particle.DUST_COLOR_TRANSITION, PINK_GLOW,
                false);
        Vfx.cloud(ctx(), back, Particle.CHERRY_LEAVES, 8, 0.6, 0.05, null, false);
        Vfx.cloud(ctx(), back, Particle.WHITE_ASH, 10, 0.5, 0.02, null, false);
        Fx.sound(back, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.6f, 1.7f + Fx.jitter(0.1f));
        Fx.sound(back, Sound.ENTITY_PARROT_FLY, 1.0f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(back, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.8f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + PINK_TAG + ">♥ 날갯짓</" + PINK_TAG + "> <gray>공중 사격 강화</gray>"));
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        long now = ctx().currentTick();
        if (landing.absorb(event, now)) {
            return;
        }
        if (Combat.isFall(event) && gliding) {
            // Gliding wings never land hard.
            event.setCancelled(true);
            return;
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Charm charm = charmOf(byEntity.getDamager(), now);
        if (charm == null) {
            return;
        }
        // Love will not let the charmed raise a hand against Cupid.
        event.setCancelled(true);
        Location at = Fx.body(player()).add(Fx.body(charm.victim).subtract(Fx.body(player())).toVector()
                .multiply(0.4));
        Vfx.cloud(ctx(), at, Particle.HEART, 3, 0.25, 0, null, false);
        Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 6, 0.2, 0, PINK_GLOW, false);
        Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.6f + Fx.jitter(0.1f));
        if (charm.victim instanceof Player lover) {
            lover.sendActionBar(Text.mm("<" + PINK_TAG + ">♥ 매혹</" + PINK_TAG + "> <gray>큐피드를 해칠 수 없습니다</gray>"));
        }
    }

    /** The charm of whoever is behind {@code damager} (itself, a projectile's shooter, a summon's owner), if any. */
    private @Nullable Charm charmOf(Entity damager, long now) {
        Charm charm = charmed.get(damager.getUniqueId());
        if (charm == null) {
            Player attacker = ctx().resolveAttacker(damager);
            charm = attacker == null ? null : charmed.get(attacker.getUniqueId());
        }
        return charm != null && charm.until > now ? charm : null;
    }

    /** Removes slow falling only if it is the (particle-less, short) one the wings applied. */
    private static void removeOwnSlowFalling(Player owner) {
        PotionEffect current = owner.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (current != null && !current.hasParticles() && current.getDuration() <= SLOW_FALL_TICKS) {
            owner.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
    }

    /** The wings are open (a flap, or a fall deeper than a jump): the shot is an air shot. */
    private boolean winged(Player owner) {
        return gliding && !(Motion.isGrounded(owner) || owner.isInWater());
    }

    /** Ticks the server sees a player's movement late: one round trip, capped. */
    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / 50.0));
    }

    // ------------------------------------------------------------------ active: love arrow

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        boolean aloft = winged(owner);
        new Arrow(owner, aloft).loose();
        return ActivationResult.success();
    }

    /** One love arrow in flight: three displays pointing along the flight, stepped and hit-tested every tick. */
    private final class Arrow implements LongPredicate {

        private final Player owner;
        private final boolean aloft;
        private final Vector direction;
        private final double range;
        private final World world;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private Location position;
        private double travelled;
        private boolean done;

        Arrow(Player owner, boolean aloft) {
            this.owner = owner;
            this.aloft = aloft;
            Location eye = owner.getEyeLocation();
            Vector look = eye.getDirection();
            this.direction = look.lengthSquared() < 1.0E-6 ? new Vector(0, 0, 1) : look.normalize();
            this.range = Math.max(1, params().num("arrow_range"));
            this.world = owner.getWorld();
            this.position = eye.clone();
        }

        void loose() {
            long life = (long) Math.ceil(range / ARROW_SPEED) + STUCK_TICKS + 8;
            Location bow = bowHand();
            if (reserve(3)) {
                Material[] materials = {SHAFT, TIP, FLETCHING};
                for (int i = 0; i < materials.length; i++) {
                    BlockDisplay part = keep(Displays.block(ctx(), owner, bow, materials[i].createBlockData(),
                            arrowPart(i, direction, 0.3f), life));
                    part.setTeleportDuration(1);
                    if (i == 1) {
                        Displays.outline(part, PINK);
                    }
                    parts.add(part);
                    Displays.animateNextTick(ctx(), owner, part, arrowPart(i, direction, 1f), 2);
                }
            }
            Vfx.dot(bow, Particle.FLASH, PINK, false);
            Vfx.cloud(ctx(), bow, Particle.HEART, 2, 0.15, 0, null, false);
            Vfx.ring(ctx(), bow, 0.35, 10, 0, Particle.DUST_COLOR_TRANSITION, PINK_GLOW, false);
            Fx.sound(bow, Sound.ENTITY_ARROW_SHOOT, 1.0f, 1.5f + Fx.jitter(0.08f));
            Fx.sound(bow, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f + Fx.jitter(0.1f));
            Fx.sound(bow, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.4f, 1.8f + Fx.jitter(0.1f));
            if (aloft) {
                Vfx.cloud(ctx(), bow, Particle.CHERRY_LEAVES, 5, 0.3, 0.02, null, false);
                Vfx.dot(bow, Particle.WAX_OFF, null, false);
            }
            Loop.repeat(ctx(), owner, 1, 1, (long) Math.ceil(range / ARROW_SPEED) + 1, this, this::fizzle);
        }

        private Location bowHand() {
            Location eye = owner.getEyeLocation();
            return eye.clone().add(direction.clone().multiply(0.7)).subtract(0, 0.2, 0);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            double step = Math.min(ARROW_SPEED, range - travelled);
            if (step <= 1.0E-3) {
                return false;
            }
            Location before = position.clone();
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.NEVER, true);
            double reach = wall == null ? step : wall.getHitPosition().distance(position.toVector());
            LivingEntity victim = firstEnemy(position.toVector(), reach);
            if (victim != null) {
                position = Fx.body(victim);
                place(before);
                strike(victim);
                return false;
            }
            if (wall != null) {
                position = wall.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.25));
                place(before);
                stick(wall.getHitBlock());
                return false;
            }
            position = position.clone().add(direction.clone().multiply(step));
            travelled += step;
            place(before);
            return true;
        }

        /** Moves the arrow and draws its wake from {@code before}. */
        private void place(Location before) {
            for (BlockDisplay part : parts) {
                Displays.moveTo(part, position);
            }
            Vfx.line(ctx(), before, position, 2.5, Particle.DUST, PINK_DUST, false);
            Vfx.trail(before, position, WHITE, 3, false);
            if (travelled % 5 < ARROW_SPEED) {
                Vfx.dot(before, Particle.HEART, null, false);
            }
        }

        /** The first enemy whose (padded) hitbox this tick's flight crosses. */
        private @Nullable LivingEntity firstEnemy(Vector start, double reach) {
            Location middle = start.clone().add(direction.clone().multiply(reach / 2)).toLocation(world);
            LivingEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, reach / 2 + 1.5)) {
                BoundingBox box = enemy.getBoundingBox().expand(ARROW_PAD);
                double distance;
                if (box.contains(start)) {
                    distance = 0;
                } else {
                    RayTraceResult crossing = box.rayTrace(start, direction, reach);
                    if (crossing == null) {
                        continue;
                    }
                    distance = crossing.getHitPosition().distance(start);
                }
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = enemy;
                }
            }
            return best;
        }

        private void strike(LivingEntity victim) {
            done = true;
            double damage = Math.max(0, params().num("arrow_damage"));
            if (aloft) {
                damage *= 1 + Math.max(0, params().num("air_bonus")) / 100.0;
            }
            Combat.hit(ctx(), owner, victim, damage);
            Location at = Fx.body(victim);
            boolean shown = seen(owner, victim);
            if (shown) {
                Vfx.burst(ctx(), at, PALETTE, 0.6, Particle.HEART, false);
                Vfx.sphere(ctx(), at, 0.6, 14, Particle.DUST_COLOR_TRANSITION, PINK_GLOW, false);
            }
            Fx.sound(at, Sound.ENTITY_ARROW_HIT_PLAYER, 0.8f, 1.5f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_AMETHYST_CLUSTER_HIT, 0.9f, 1.3f + Fx.jitter(0.1f));
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 3);
                }
            }
            if (Combat.isTargetable(ctx(), owner, victim)) {
                long ticks = params().ticks("charm_duration") + (aloft ? params().ticks("air_charm") : 0);
                charm(victim, ticks);
            }
            owner.sendActionBar(Text.mm("<" + PINK_TAG + ">♥ 사랑의 화살 적중!</" + PINK_TAG + ">"
                    + (aloft ? " <" + RED_TAG + ">공중 사격 +" + AbilityInfo.formatNumber(params().num("air_bonus"))
                    + "%</" + RED_TAG + ">" : "")));
        }

        /** The arrow sinks into a block, quivers and fades. */
        private void stick(@Nullable Block block) {
            done = true;
            if (block != null) {
                world.spawnParticle(Particle.BLOCK, position, Vfx.n(ctx(), 5), 0.08, 0.08, 0.08, 0,
                        block.getBlockData());
            }
            Vfx.cloud(ctx(), position, Particle.DUST_COLOR_TRANSITION, 6, 0.15, 0, PINK_GLOW, false);
            Fx.sound(position, Sound.ENTITY_ARROW_HIT, 0.8f, 1.2f + Fx.jitter(0.1f));
            ctx().runLater(owner, STUCK_TICKS, () -> {
                for (BlockDisplay part : parts) {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 4);
                    }
                }
            });
        }

        /** Out of range: the arrow dissolves into petals. */
        private void fizzle() {
            if (done) {
                return;
            }
            done = true;
            Vfx.cloud(ctx(), position, Particle.CHERRY_LEAVES, 6, 0.3, 0.02, null, false);
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 3);
                }
            }
        }
    }

    /**
     * Part {@code index} of the love arrow (0 shaft, 1 tip, 2 fletching) pointing along {@code direction}, grown to
     * {@code grow}: the whole arrow is built along +Z and turned onto the flight, the display itself stays unrotated.
     */
    private static Transformation arrowPart(int index, Vector direction, float grow) {
        Vector3f dir = new Vector3f((float) direction.getX(), (float) direction.getY(), (float) direction.getZ());
        Quaternionf along = pointing(dir);
        float g = Math.max(0.05f, grow);
        return switch (index) {
            case 0 -> box(along.transform(new Vector3f(0, 0, -0.3f * g)), new Vector3f(0.045f, 0.045f, 0.75f * g),
                    along);
            case 1 -> box(along.transform(new Vector3f(0, 0, 0.12f * g)), new Vector3f(0.13f * g, 0.13f * g, 0.13f * g),
                    new Quaternionf(along).rotateZ((float) Math.toRadians(45)).rotateX((float) Math.toRadians(45)));
            default -> box(along.transform(new Vector3f(0, 0, -0.62f * g)), new Vector3f(0.2f * g, 0.02f, 0.16f * g),
                    new Quaternionf(along).rotateZ((float) Math.toRadians(45)));
        };
    }

    // ------------------------------------------------------------------ charm

    /** Charms {@code victim} for {@code ticks} (refreshing a running charm). */
    private void charm(LivingEntity victim, long ticks) {
        Player owner = player();
        long now = ctx().currentTick();
        Charm charm = charmed.get(victim.getUniqueId());
        boolean fresh = charm == null || charm.until <= now;
        if (fresh) {
            charm = new Charm(victim);
            charmed.put(victim.getUniqueId(), charm);
        }
        charm.until = Math.max(charm.until, now + Math.max(1, ticks));
        if (fresh && seen(owner, victim)) {
            TextDisplay heart = keepIfRoom(() -> Displays.text(ctx(), owner, overHead(victim),
                    Text.mm("<b><gradient:#FFC1E0:#FF4F8B>♥</gradient></b>"), 0.4f, 0));
            if (heart != null) {
                heart.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, heart, Displays.centered(0, 0, 0, 1.4f, 1.4f, 1.4f, 0, 0, 0),
                        3);
            }
            charm.heart = heart;
            Location body = Fx.body(victim);
            Vfx.cloud(ctx(), body, Particle.HEART, 5, 0.35, 0, null, false);
            Vfx.helix(ctx(), victim.getLocation(), 0.55, victim.getHeight(), 1.5, 2, 10, 0,
                    Particle.DUST_COLOR_TRANSITION, PINK_GLOW, false);
        }
        Fx.sound(Fx.body(victim), Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 0.9f, 1.4f + Fx.jitter(0.1f));
        if (victim instanceof Player lover) {
            lover.sendActionBar(Text.mm("<" + PINK_TAG + "><b>♥ 매혹!</b></" + PINK_TAG + "> <gray>"
                    + Fx.seconds(ticks) + "초간 큐피드에게 끌립니다</gray>"));
        }
    }

    /** Every charmed enemy strolls towards Cupid, its heart pulsing; charms that ran out end. */
    private void tickCharms(Player owner, long now) {
        if (charmed.isEmpty()) {
            return;
        }
        for (var it = charmed.values().iterator(); it.hasNext(); ) {
            Charm charm = it.next();
            LivingEntity victim = charm.victim;
            if (charm.until <= now || !Combat.isTargetable(ctx(), owner, victim)
                    || !victim.getWorld().equals(owner.getWorld())) {
                release(charm);
                it.remove();
                continue;
            }
            stroll(owner, charm);
            boolean hidden = !seen(owner, victim);
            TextDisplay heart = charm.heart;
            if (heart != null && heart.isValid()) {
                Displays.moveTo(heart, overHead(victim));
                if (hidden != charm.hidden) {
                    heart.setViewRange(hidden ? 0f : 1.5f);
                }
                if (!hidden && now % 6 == 0) {
                    float pulse = now % 12 == 0 ? 1.65f : 1.25f;
                    Displays.animate(heart, Displays.centered(0, 0, 0, pulse, pulse, pulse, 0, 0, 0), 5);
                }
            }
            charm.hidden = hidden;
            if (!hidden && now % 3 == 0) {
                Location chest = Fx.body(owner);
                Vfx.trail(Fx.body(victim), chest, now % 6 == 0 ? PINK : WHITE, 10, false);
                if (now % 9 == 0) {
                    Vfx.dot(overHead(victim).add(Fx.jitter(0.3f), -0.2, Fx.jitter(0.3f)), Particle.HEART, null, false);
                }
            }
        }
    }

    /**
     * One step of the charmed stroll towards Cupid: a fresh walking velocity, only while the victim is on the ground (a
     * player's server velocity is stale, so its jumps and falls are left alone), slowing down as it arrives. Steered by
     * where the steps sent so far have taken it, not by its late server position, so a lagging player stops beside
     * Cupid instead of walking on into her.
     */
    private void stroll(Player owner, Charm charm) {
        LivingEntity victim = charm.victim;
        Location actual = victim.getLocation();
        double lag = victim instanceof Player lover ? lagTicks(lover) : 0;
        // Beyond a round trip of steps the reckoning has lost it (a wall stopped it, it was knocked away).
        double slack = CHARM_SPEED * (lag + 2) + 0.5;
        if (Math.hypot(actual.getX() - charm.x, actual.getZ() - charm.z) > slack) {
            charm.x = actual.getX();
            charm.z = actual.getZ();
        }
        if (!(Motion.isGrounded(victim) || victim.isInWater())) {
            charm.x = actual.getX();
            charm.z = actual.getZ();
            return;
        }
        Location feet = owner.getLocation();
        Vector to = new Vector(feet.getX() - charm.x, 0, feet.getZ() - charm.z);
        double distance = to.length();
        if (distance <= CHARM_STOP) {
            return;
        }
        double speed = Math.min(CHARM_SPEED, (distance - CHARM_STOP) * 0.5);
        Vector velocity = Motion.horizontal(to).multiply(speed).setY(victim instanceof Player ? REST_FALL : 0);
        Vector sent = Motion.clamp(velocity, MAX_SPEED);
        charm.x += sent.getX();
        charm.z += sent.getZ();
        victim.setVelocity(sent);
    }

    /** The charm wears off: the heart cracks and drifts away. */
    private void release(Charm charm) {
        TextDisplay heart = charm.heart;
        if (heart != null && heart.isValid()) {
            heart.text(Text.mm("<b><gray>♡</gray></b>"));
            heart.setTeleportDuration(6);
            Displays.moveTo(heart, heart.getLocation().add(0, 0.5, 0));
            Displays.shrinkAway(ctx(), player(), heart, 6);
        }
        if (seen(player(), charm.victim) && charm.victim.isValid()) {
            Vfx.cloud(ctx(), overHead(charm.victim), Particle.DUST, 5, 0.2, 0, PINK_DUST, false);
            Fx.sound(overHead(charm.victim), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.5f, 1.6f + Fx.jitter(0.1f));
        }
    }

    private static Location overHead(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() + 0.45, 0);
    }

    // ------------------------------------------------------------------ ultimate: red string of fate

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        LivingEntity first = ctx().findTarget(owner, params().num("thread_range"));
        if (first == null || !seen(owner, first)) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        // The nearest other enemy the string can reach: never one behind a wall.
        LivingEntity second = null;
        for (LivingEntity enemy : ctx().enemiesNear(owner, first.getLocation(), params().num("thread_link"))) {
            if (!enemy.equals(first) && seen(owner, enemy) && inSight(first, enemy)) {
                second = enemy;
                break;
            }
        }
        new Fate(owner, first, second).tie();
        return ActivationResult.success();
    }

    /** True if nothing solid stands between the two bodies (chest to chest or eye to eye). */
    private static boolean inSight(LivingEntity a, LivingEntity b) {
        return Spots.clearPath(Fx.body(a), Fx.body(b)) || Spots.clearPath(a.getEyeLocation(), b.getEyeLocation());
    }

    /** Dead-reckoned pull of one tied enemy (see {@code MagnetAbility}): where it will be after the pulls sent. */
    private static final class Reel {
        final LivingEntity body;
        /** Ticks the server sees this body late (its ping for a player). */
        final long lag;
        double x;
        double z;

        Reel(LivingEntity body) {
            this.body = body;
            this.lag = body instanceof Player player ? lagTicks(player) : 0;
            Location at = body.getLocation();
            this.x = at.getX();
            this.z = at.getZ();
        }

        Location predicted() {
            Location at = body.getLocation();
            at.setX(x);
            at.setZ(z);
            return at;
        }

        /** Beyond a round trip of pulls the reckoning has lost the body (a wall stopped it): start over from it. */
        void reconcile() {
            Location actual = body.getLocation();
            if (Math.hypot(actual.getX() - x, actual.getZ() - z) > PULL_SPEED * (lag + 2) + 0.5) {
                x = actual.getX();
                z = actual.getZ();
            }
        }

        /** How far the server may still see this body behind where the pulls have taken it. */
        double contactSlack() {
            return PULL_SPEED * lag + 0.4;
        }
    }

    /**
     * The red string: tied, trembling for {@code thread_delay}, then reeling both ends in until they collide (or, for
     * a lone enemy, until it stands in front of Cupid), where hearts burst. They collide only when the server sees
     * them meet with nothing between them; a pair a wall keeps apart never meets, and the string snaps.
     */
    private final class Fate implements LongPredicate {

        private final Player owner;
        private final Reel first;
        private final @Nullable Reel second;
        private final long delay;
        private @Nullable BlockDisplay cord;
        private boolean finished;

        Fate(Player owner, LivingEntity first, @Nullable LivingEntity second) {
            this.owner = owner;
            this.first = new Reel(first);
            this.second = second == null ? null : new Reel(second);
            this.delay = Math.max(1, params().ticks("thread_delay"));
        }

        void tie() {
            Location a = Fx.body(first.body);
            Location b = otherEnd();
            if (reserve(1)) {
                cord = keep(Displays.block(ctx(), owner, a, CORD.createBlockData(), cordPose(a, b), delay
                        + PULL_MAX_TICKS + 10));
                cord.setTeleportDuration(1);
                Displays.outline(cord, RED);
                cord.setViewRange(2.0f);
            }
            Location eye = owner.getEyeLocation();
            Vfx.trail(eye, a, RED, 6, true);
            Vfx.line(ctx(), eye.clone().add(eye.getDirection().multiply(0.6)), a, 2, Particle.DUST, RED_DUST, true);
            knot(first.body);
            if (second != null) {
                knot(second.body);
            }
            Fx.sound(eye, Sound.ENTITY_ARROW_SHOOT, 1.0f, 1.2f);
            Fx.sound(a, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 1.4f);
            Fx.sound(a, Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 0.8f);
            owner.sendActionBar(Text.mm("<" + RED_TAG + "><b>❤ 붉은 실</b></" + RED_TAG + "> <gray>"
                    + (second != null ? "운명의 두 사람을 묶었습니다" : "그 적과 나를 이었습니다") + "</gray>"));
            long lag = Math.max(first.lag, second != null ? second.lag : lagTicks(owner));
            Loop.repeat(ctx(), owner, 1, 1, delay + PULL_MAX_TICKS + lag, this, this::snap);
        }

        /** The end the first enemy is tied to: the second enemy, or Cupid herself. */
        private Location otherEnd() {
            return second != null ? Fx.body(second.body) : Fx.body(owner);
        }

        @Override
        public boolean test(long tick) {
            if (!alive(first.body) || second != null && !alive(second.body)) {
                snap();
                return false;
            }
            Location a = Fx.body(first.body);
            Location b = otherEnd();
            drawCord(a, b, tick);
            if (tick < delay) {
                // The string trembles taut before it pulls; a rising note counts it in.
                if (tick % 3 == 0) {
                    Fx.sound(a, Sound.BLOCK_NOTE_BLOCK_HARP, 0.9f, 0.8f + 0.8f * tick / delay);
                }
                return true;
            }
            return reel(tick - delay);
        }

        private boolean alive(LivingEntity entity) {
            return Combat.isTargetable(ctx(), owner, entity) && entity.getWorld().equals(owner.getWorld());
        }

        /**
         * Pulls both ends towards their middle (or the lone end towards Cupid). Once the pulls sent have brought them
         * together it waits for the server to see them meet, then they collide (false: done).
         */
        private boolean reel(long run) {
            first.reconcile();
            if (second == null) {
                Location spot = soloSpot();
                Vector to = spot.toVector().subtract(first.predicted().toVector()).setY(0);
                if (to.length() > SOLO_ARRIVE && run < PULL_MAX_TICKS) {
                    steer(first, to);
                    return true;
                }
                Location actual = first.body.getLocation();
                if (Math.hypot(actual.getX() - spot.getX(), actual.getZ() - spot.getZ())
                        <= SOLO_ARRIVE + first.contactSlack() && inSight(owner, first.body)) {
                    collide();
                    return false;
                }
                return true;
            }
            second.reconcile();
            Location a = first.predicted();
            Location b = second.predicted();
            Vector between = b.toVector().subtract(a.toVector()).setY(0);
            if (between.length() > COLLIDE_DISTANCE) {
                if (run < PULL_MAX_TICKS) {
                    // Each is drawn to the middle, so they meet halfway.
                    Vector half = between.multiply(0.5);
                    steer(first, half.clone().add(Motion.horizontal(half).multiply(COLLIDE_DISTANCE * -0.45)));
                    Vector back = half.clone().multiply(-1);
                    steer(second, back.clone().add(Motion.horizontal(back).multiply(COLLIDE_DISTANCE * -0.45)));
                }
                return true;
            }
            Location actualA = first.body.getLocation();
            Location actualB = second.body.getLocation();
            boolean met = Math.hypot(actualA.getX() - actualB.getX(), actualA.getZ() - actualB.getZ())
                    <= COLLIDE_DISTANCE + first.contactSlack() + second.contactSlack()
                    && Math.abs(actualA.getY() - actualB.getY()) <= 1.5
                    && inSight(first.body, second.body);
            if (met) {
                collide();
                return false;
            }
            return true;
        }

        /** One reel step of {@code reel} along {@code to} (crowd control: ignores knockback resistance). */
        private void steer(Reel reel, Vector to) {
            double distance = to.length();
            if (distance < 1.0E-3) {
                return;
            }
            double speed = Math.min(PULL_SPEED, Math.max(0.25, distance * 0.5));
            Vector velocity = Motion.horizontal(to).multiply(Math.min(speed, distance));
            boolean grounded = Motion.isGrounded(reel.body) || reel.body.isInWater();
            // A hop over bumps on the ground; in the air a player falls on (its server velocity is stale, so its fall
            // speed is not read back), a mob keeps its own.
            double fall = reel.body instanceof Player ? REST_FALL : Math.min(0, reel.body.getVelocity().getY());
            velocity.setY(grounded ? 0.18 : fall);
            Vector sent = Motion.clamp(velocity, MAX_SPEED);
            reel.x += sent.getX();
            reel.z += sent.getZ();
            reel.body.setVelocity(sent);
            reel.body.setFallDistance(0);
            if (seen(player(), reel.body)) {
                Vfx.dot(Fx.body(reel.body), Particle.DUST_COLOR_TRANSITION, RED_GLOW, false);
            }
        }

        /** In front of Cupid, where a lone enemy is reeled to (her own spot if a wall is right there). */
        private Location soloSpot() {
            Location feet = owner.getLocation();
            Vector facing = Motion.horizontalFacing(feet);
            if (!Spots.clearPath(feet.clone().add(0, 0.5, 0), feet.clone().add(0, 0.5, 0)
                    .add(facing.clone().multiply(SOLO_STOP)))) {
                return feet;
            }
            return feet.add(facing.multiply(SOLO_STOP));
        }

        private void drawCord(Location a, Location b, long tick) {
            // An end hidden from Cupid (stealth) must not be given away by the string.
            boolean hidden = !seen(owner, first.body) || second != null && !seen(owner, second.body);
            BlockDisplay line = cord;
            if (line != null && line.isValid()) {
                Displays.moveTo(line, a);
                Displays.animate(line, cordPose(a, b), 1);
                line.setViewRange(hidden ? 0f : 2.0f);
            }
            if (hidden) {
                return;
            }
            Vector delta = b.toVector().subtract(a.toVector());
            double length = delta.length();
            if (length < 0.2) {
                return;
            }
            // A red ripple runs along the string.
            double t = (tick % 8) / 8.0;
            Location pulse = a.clone().add(delta.clone().multiply(t));
            Vfx.dot(pulse, Particle.DUST_COLOR_TRANSITION, RED_GLOW, true);
            if (tick % 2 == 0) {
                Vfx.line(ctx(), a, b, 1.2, Particle.DUST, RED_DUST, true);
            }
            if (tick % 5 == 0) {
                Vfx.dot(a.clone().add(delta.multiply(0.5)).add(0, 0.25, 0), Particle.HEART, null, true);
            }
        }

        /** A red loop knotted around a tied waist. */
        private void knot(LivingEntity tied) {
            Location waist = tied.getLocation().add(0, tied.getHeight() * 0.45, 0);
            Vfx.ring(ctx(), waist, 0.45, 14, 0, Particle.DUST_COLOR_TRANSITION, RED_GLOW, true);
            Vfx.ring(ctx(), waist.clone().add(0, 0.12, 0), 0.42, 10, 0.3, Particle.DUST, GOLD_DUST, true);
            Fx.sound(waist, Sound.ITEM_LEAD_TIED, 0.9f, 1.2f + Fx.jitter(0.1f));
        }

        /** They meet: hearts burst, both are hurt, slowed and bounced apart; anyone close is caught too. */
        private void collide() {
            if (finished) {
                return;
            }
            finished = true;
            removeCord();
            if (!alive(first.body) || second != null && !alive(second.body)) {
                return;
            }
            Location a = Fx.body(first.body);
            Location b = second != null ? Fx.body(second.body) : a;
            Location middle = a.clone().add(b.toVector().subtract(a.toVector()).multiply(0.5));
            double damage = params().num("ult_damage");
            double splash = params().num("burst_damage");
            double radius = Math.max(0, params().num("burst_radius"));
            long slow = params().ticks("ult_slow");
            List<LivingEntity> struck = new ArrayList<>();
            struck.add(first.body);
            if (second != null) {
                struck.add(second.body);
            }
            for (LivingEntity tied : List.copyOf(struck)) {
                Combat.hit(ctx(), owner, tied, damage);
            }
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, radius)) {
                if (!struck.contains(enemy) && Spots.clearPath(middle, Fx.body(enemy))) {
                    Combat.hit(ctx(), owner, enemy, splash);
                    struck.add(enemy);
                }
            }
            for (LivingEntity enemy : struck) {
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                if (slow > 0) {
                    enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) Math.min(Integer.MAX_VALUE,
                            slow), 1, false, true, true));
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(middle.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    away = second == null ? Motion.horizontalFacing(owner.getLocation())
                            : Motion.horizontal(b.toVector().subtract(a.toVector()));
                    if (enemy.equals(first.body)) {
                        away.multiply(-1);
                    }
                }
                if (second == null) {
                    // A lone enemy is bounced away from Cupid.
                    away = Motion.horizontal(enemy.getLocation().toVector().subtract(owner.getLocation().toVector()));
                }
                Motion.push(enemy, away.multiply(BOUNCE_SPEED).setY(BOUNCE_LIFT), MAX_SPEED);
            }
            heartBurst(middle, second != null ? Motion.horizontal(b.toVector().subtract(a.toVector()))
                    : Motion.horizontal(a.toVector().subtract(Fx.body(owner).toVector())));
            owner.sendActionBar(Text.mm("<" + RED_TAG + "><b>❤ 운명의 충돌!</b></" + RED_TAG + "> <white>"
                    + struck.size() + "명</white>"));
        }

        /** The string snaps when an end is gone or the two never met (a wall kept them apart). */
        private void snap() {
            if (finished) {
                return;
            }
            finished = true;
            owner.sendActionBar(Text.mm("<" + RED_TAG + ">❤ 붉은 실</" + RED_TAG + "> <gray>실이 끊어졌습니다</gray>"));
            BlockDisplay line = cord;
            if (line != null && line.isValid()) {
                Vfx.cloud(ctx(), line.getLocation(), Particle.DUST, 6, 0.2, 0, RED_DUST, false);
                Fx.sound(line.getLocation(), Sound.ITEM_LEAD_BREAK, 0.9f, 1.2f);
            }
            removeCord();
        }

        private void removeCord() {
            BlockDisplay line = cord;
            if (line != null && line.isValid()) {
                Displays.shrinkAway(ctx(), owner, line, 3);
            }
            cord = null;
        }
    }

    /** A thin red cord from the display's origin ({@code a}) to {@code b}. */
    private static Transformation cordPose(Location a, Location b) {
        Vector3f delta = new Vector3f((float) (b.getX() - a.getX()), (float) (b.getY() - a.getY()),
                (float) (b.getZ() - a.getZ()));
        float length = Math.max(0.001f, delta.length());
        Quaternionf rotation = pointing(delta);
        return box(rotation.transform(new Vector3f(0, 0, length / 2)), new Vector3f(CORD_WIDTH, CORD_WIDTH, length),
                rotation);
    }

    /**
     * The collision: a big heart of light drawn upright across the line of the impact, hearts bursting out, and a
     * giant heart popping over the spot.
     */
    private void heartBurst(Location at, Vector line) {
        Player owner = player();
        Vector across = line.lengthSquared() < 1.0E-6 ? new Vector(1, 0, 0)
                : new Vector(-line.getZ(), 0, line.getX()).normalize();
        World world = at.getWorld();
        Vfx.dot(at, Particle.FLASH, PINK, true);
        Vfx.cloud(ctx(), at, Particle.HEART, 14, 0.8, 0, null, true);
        Vfx.burst(ctx(), at, PALETTE, 1.2, Particle.END_ROD, true);
        Vfx.shockwave(ctx(), owner, at.clone().subtract(0, 0.8, 0), 0.4, Math.max(1, params().num("burst_radius")), 6,
                Particle.DUST_COLOR_TRANSITION, PINK_GLOW, true);
        // The heart curve, growing over three ticks.
        Loop.repeat(ctx(), owner, 0, 1, 3, run -> {
            double size = 0.09 + 0.05 * run;
            int points = Vfx.n(ctx(), 40);
            for (int i = 0; i < points; i++) {
                double t = i * TAU / points;
                double hx = 16 * Math.pow(Math.sin(t), 3);
                double hy = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
                Location point = at.clone().add(across.clone().multiply(hx * size)).add(0, hy * size + 0.3, 0);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, point, 1, 0, 0, 0, 0, run == 2 ? RED_GLOW
                        : PINK_GLOW, true);
            }
            return true;
        });
        if (reserve(1)) {
            TextDisplay heart = keep(Displays.text(ctx(), owner, at.clone().add(0, 0.6, 0),
                    Text.mm("<b><gradient:#FF9AC8:#E0465E>♥</gradient></b>"), 0.5f, 22));
            heart.setViewRange(2.0f);
            Displays.animateNextTick(ctx(), owner, heart, Displays.centered(0, 0.8f, 0, 4f, 4f, 4f, 0, 0, 0), 5);
            ctx().runLater(owner, 14, () -> {
                if (heart.isValid()) {
                    Displays.shrinkAway(ctx(), owner, heart, 6);
                }
            });
        }
        Fx.sound(at, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.6f);
        Fx.sound(at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.2f, 1.0f);
        Fx.sound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.5f);
    }

    // ------------------------------------------------------------------ helpers

    /** True unless {@code entity} is a player hidden from {@code owner} (stealth): nothing may reveal it. */
    private static boolean seen(Player owner, Entity entity) {
        return !(entity instanceof Player hidden) || owner.canSee(hidden);
    }

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    private <T extends Display> @Nullable T keepIfRoom(java.util.function.Supplier<T> spawn) {
        return reserve(1) ? keep(spawn.get()) : null;
    }

    /** The rotation of a Minecraft yaw (0 faces +Z, 90 faces -X) about the vertical. */
    private static Quaternionf yawTurn(float yaw) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw));
    }

    /** A box of {@code size} centered on {@code center} (relative to the display), turned by {@code rotation}. */
    private static Transformation box(Vector3f center, Vector3f size, Quaternionf rotation) {
        Vector3f translation = new Vector3f(center).sub(new Quaternionf(rotation).transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    /** The rotation turning +Z onto {@code direction} (+Z if it is zero). */
    private static Quaternionf pointing(Vector3f direction) {
        if (direction.lengthSquared() < 1.0E-8f) {
            return new Quaternionf();
        }
        return new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(direction).normalize());
    }
}
