package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Frog;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 슬라임 — a bouncy brawler. Never takes fall damage and springs back up from big falls, splits off little slimes
 * that hop after enemies and burst into sticky goo, and as the ultimate swells into a crowned King Slime whose every
 * landing sends out a shockwave.
 *
 * <p>Look: lime jelly. Bounces splash rings of slime; the split squeezes a wobbling cube of jelly out of the owner
 * and flings goo to each little slime; a burst leaves a puddle of goo that seeps away. The King Slime wears a jiggling
 * shell of slime that stretches as it rises and squashes as it lands, a golden crown turning above its head, and every
 * slam flings jelly cubes out over a lime shockwave.
 */
public final class SlimeAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("slime", "슬라임", Material.SLIME_BALL)
            .color(TextColor.color(0x8CD94A))
            .difficulty(1)
            .passive("탄력",
                    "낙하 피해를 받지 않고, 높은 곳에서 떨어지면 떨어진 높이의 {bounce_ratio}%만큼(최대 "
                            + "{bounce_max_height}블록) 다시 튀어 오릅니다. 웅크리면 튀지 않습니다. 넉백을 "
                            + "{knockback_resistance}% 덜 받습니다.")
            .active("분열",
                    "작은 슬라임 {slime_count}마리(체력 {slime_health})로 분열해 {slime_duration}초간 적에게 튀어 "
                            + "달려들게 합니다. 슬라임은 적에게 닿으면 터져 반경 {burst_radius}블록의 적에게 "
                            + "{burst_damage} 피해를 주고 {sticky_duration}초간 이동 속도를 {sticky_slow}% 늦춥니다.",
                    12)
            .ultimate("킹 슬라임",
                    "{ult_duration}초간 {ult_scale}배 크기의 킹 슬라임이 되어 약 {ult_jump_height}블록 높이로 뛸 수 "
                            + "있습니다. 공중에서 내려와 착지할 때마다 반경 {slam_radius}블록의 적에게 {slam_damage} "
                            + "피해를 주고 튕겨 올립니다. 같은 적은 {slam_rehit}초에 한 번만 맞습니다.")
            .param("bounce_ratio", 60)
            .param("bounce_max_height", 6)
            .param("knockback_resistance", 35)
            .param("slime_count", 3)
            .param("slime_health", 4)
            .param("slime_duration", 8)
            .param("slime_speed", 4)
            .param("burst_radius", 1.5)
            .param("burst_damage", 2)
            .param("sticky_duration", 1.5)
            .param("sticky_slow", 30)
            .param("ult_duration", 8)
            .param("ult_scale", 2)
            .param("ult_jump_height", 3)
            .param("slam_radius", 3.5)
            .param("slam_damage", 3)
            .param("slam_rehit", 1.5)
            .build();

    private static final String LIME = "#8CD94A";
    private static final double TAU = Math.PI * 2;
    private static final double MAX_VELOCITY = 2.5;

    // Bounce
    /** A bounce lower than this is not worth it (it only jiggles). */
    private static final double MIN_BOUNCE = 0.6;
    private static final int PAD_TICKS = 22;
    /** Fewest ticks between two jiggles of the elastic body when it is hit. */
    private static final long JIGGLE_COOLDOWN = 8;

    // Little slimes
    private static final double SLIME_SCALE = 1.3;
    /** Hitbox width of a little slime: its carrier (a frog, 0.5 wide) at {@link #SLIME_SCALE}. */
    private static final double SLIME_WIDTH = 0.5 * SLIME_SCALE;
    private static final float JELLY_SIZE = 0.62f;
    private static final float EYE_WIDTH = 0.1f;
    private static final float EYE_HEIGHT = 0.13f;
    private static final float EYE_GAP = 0.13f;
    /** Height of the eyes on the jelly, as a share of its height. */
    private static final float EYE_LEVEL = 0.62f;
    private static final double SPAWN_DISTANCE = 1.6;
    private static final long CONTROL_PERIOD = 1;
    private static final long HOP_INTERVAL = 12;
    private static final double HOP_UP = 0.42;
    private static final double AIR_DRAG = 0.91;
    /** Gap between a slime and an enemy's hitbox that counts as touching. */
    private static final double CONTACT_GAP = 0.35;
    private static final double AGGRO_RANGE = 16;
    private static final double FOLLOW_DISTANCE = 3;

    // King Slime
    private static final int GROW_STEPS = 5;
    private static final long GROW_INTERVAL = 2;
    private static final double STANDING_HEIGHT = 1.8;
    private static final double HEADROOM_HALF_WIDTH = 0.25;
    private static final long MIN_AIR_TICKS = 4;
    /** A landing only slams after falling at least this far from the top of the jump. */
    private static final double MIN_SLAM_DROP = 0.8;
    private static final long SLAM_COOLDOWN = 6;
    private static final double SLAM_OUT = 0.5;
    private static final double SLAM_UP = 0.55;
    /** Extra fall height the King Slime shrugs off on top of its jump height (kept for a borrowed copy). */
    private static final double SAFE_FALL_MARGIN = 2;

    // ------------------------------------------------------------------ look: pale lime, lime, deep green
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE8FFB0, 0x8CD94A, 0x3E7A1E);
    private static final Particle.DustOptions GOO = PALETTE.dust(1.2f);
    private static final Particle.DustOptions GOO_SMALL = PALETTE.dust(0.7f);
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(0xFFD54F), 0.8f);
    private static final BlockData SLIME_BLOCK = Material.SLIME_BLOCK.createBlockData();
    private static final BlockData LIME_GLASS = Material.LIME_STAINED_GLASS.createBlockData();
    private static final BlockData EYE = Material.BLACK_CONCRETE.createBlockData();
    private static final int MAX_DISPLAYS = 24;
    private static final int SPLASH_CUBES = 8;
    private static final int CUBE_FLIGHT_TICKS = 7;
    private static final int PUDDLE_TICKS = 24;

    private final List<LittleSlime> slimes = new ArrayList<>();
    private int liveDisplays;
    private long lastJiggle = Long.MIN_VALUE / 2;
    private @Nullable King king;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: bouncy body

    @Override
    public void onGrant() {
        Player owner = player();
        double resistance = params().num("knockback_resistance") / 100.0;
        if (resistance > 0) {
            ctx().addModifier(owner, owner, Attribute.KNOCKBACK_RESISTANCE, Math.min(1.0, resistance),
                    Operation.ADD_NUMBER, 0);
        }
    }

    @Override
    public void onRevoke() {
        // The context removes the summons, displays and modifiers; only forget them here.
        for (LittleSlime slime : slimes) {
            slime.gone = true;
        }
        slimes.clear();
        king = null;
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (!Combat.isFall(event)) {
            if (event instanceof EntityDamageByEntityEvent byEntity && event.getDamage() > 0) {
                jiggle(byEntity);
            }
            return;
        }
        event.setCancelled(true);
        Player owner = player();
        if (owner.isSneaking() || king != null) {
            splat(owner.getLocation(), 0.6, false);
            return;
        }
        double multiplier = attribute(owner, Attribute.FALL_DAMAGE_MULTIPLIER, 1.0);
        double safe = attribute(owner, Attribute.SAFE_FALL_DISTANCE, 3.0);
        double fallen = Math.max(owner.getFallDistance(), event.getDamage() / Math.max(0.1, multiplier) + safe);
        double height = Math.min(Math.max(0, params().num("bounce_max_height")),
                fallen * Math.max(0, params().num("bounce_ratio")) / 100.0);
        owner.setFallDistance(0);
        splat(owner.getLocation(), Math.min(1.6, 0.6 + fallen * 0.08), true);
        if (height < MIN_BOUNCE) {
            return;
        }
        double up = Motion.launchSpeed(height);
        // Bounced a tick later, once the landing the damage came from is over.
        ctx().runLater(owner, 1, () -> {
            if (!ctx().isAlive(owner) || owner.isSneaking()) {
                return;
            }
            Vector velocity = owner.getVelocity();
            owner.setVelocity(Motion.clamp(new Vector(velocity.getX(), up, velocity.getZ()), MAX_VELOCITY));
            Location feet = owner.getLocation();
            Vfx.helix(ctx(), feet, 0.5, 1.4, 1, 2, 8, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), false);
            Fx.sound(feet, Sound.BLOCK_SLIME_BLOCK_FALL, 1.0f, 1.2f + Fx.jitter(0.1f));
            Fx.sound(feet, Sound.ENTITY_SLIME_JUMP, 0.7f, 1.4f + Fx.jitter(0.1f));
        });
        springPad(owner);
    }

    /**
     * A pad of jelly springs out of the ground under the bounce: it squashes flat as the slime lands on it, shoots up
     * as it is thrown back into the air, then seeps away.
     */
    private void springPad(Player owner) {
        if (!reserveDisplays(1, PAD_TICKS + 2)) {
            return;
        }
        float width = (float) (owner.getWidth() + 0.7);
        Location ground = flat(owner.getLocation());
        BlockDisplay pad = Displays.block(ctx(), owner, ground, SLIME_BLOCK, padShape(width * 1.2f, 0.12f),
                PAD_TICKS + 2);
        Displays.animateNextTick(ctx(), owner, pad, padShape(width * 0.85f, 0.55f), 3);
        ctx().runLater(owner, 5, () -> Displays.animate(pad, padShape(width, 0.25f), 4));
        ctx().runLater(owner, PAD_TICKS - 6, () -> Displays.shrinkAway(ctx(), owner, pad, 6));
    }

    /** A jelly slab {@code width} wide and {@code height} tall, standing on the ground. */
    private static Transformation padShape(float width, float height) {
        return Displays.transform(0, height / 2, 0, width, height, width, 0, 0, 0);
    }

    /**
     * The elastic body soaks up a hit: the jelly jiggles on the side the blow came from and wobbles back (at most
     * every {@link #JIGGLE_COOLDOWN} ticks).
     */
    private void jiggle(EntityDamageByEntityEvent event) {
        long now = ctx().currentTick();
        if (now - lastJiggle < JIGGLE_COOLDOWN) {
            return;
        }
        lastJiggle = now;
        Player owner = player();
        Location chest = Fx.body(owner);
        Vector toward = Motion.horizontal(event.getDamager().getLocation().toVector().subtract(chest.toVector()));
        if (toward.lengthSquared() < 1.0E-6) {
            toward = Motion.horizontalFacing(owner.getLocation());
        }
        Location side = chest.clone().add(toward.multiply(owner.getWidth() * 0.5 + 0.15));
        World world = side.getWorld();
        world.spawnParticle(Particle.ITEM_SLIME, side, Vfx.n(ctx(), 6), 0.15, 0.3, 0.15, 0.08);
        world.spawnParticle(Particle.DUST, side, Vfx.n(ctx(), 5), 0.15, 0.3, 0.15, 0, GOO);
        Vfx.ring(ctx(), chest, owner.getWidth() * 0.5 + 0.25, 10, now * 0.3, Particle.DUST, GOO_SMALL, false);
        Fx.sound(side, Sound.ENTITY_SLIME_HURT, 0.5f, 1.3f + Fx.jitter(0.1f));
    }

    /** A slime landing: a ring of goo splashing out, jelly bits and a wet slap. */
    private void splat(Location feet, double size, boolean loud) {
        Location ground = feet.clone().add(0, 0.1, 0);
        World world = ground.getWorld();
        Vfx.ring(ctx(), ground, 0.5 * size + 0.3, 12, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST, GOO,
                false);
        world.spawnParticle(Particle.ITEM_SLIME, ground, Vfx.n(ctx(), (int) (10 * size)), 0.4 * size, 0.1,
                0.4 * size, 0.1);
        world.spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), (int) (6 * size)), 0.3 * size, 0.05, 0.3 * size,
                0.1, SLIME_BLOCK);
        Fx.sound(feet, Sound.BLOCK_SLIME_BLOCK_FALL, loud ? 1.0f : 0.6f, 0.9f + Fx.jitter(0.1f));
        if (loud) {
            Fx.sound(feet, Sound.ENTITY_SLIME_SQUISH, 0.9f, 1.0f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ active: split

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        int count = Math.max(1, params().integer("slime_count"));
        long lifetime = Math.max(1, params().ticks("slime_duration"));
        Location origin = owner.getLocation();
        Location chest = Fx.body(owner);
        LivingEntity aimed = ctx().findTarget(owner, AGGRO_RANGE);
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            double angle = count == 1 ? 0 : -50 + 100.0 * i / (count - 1);
            Location spot = findSpot(origin, angle);
            Frog body = spawnCarrier(spot);
            if (body == null) {
                continue;
            }
            spawned++;
            LittleSlime slime = new LittleSlime(body, lifetime, aimed, spot.getYaw());
            slimes.add(slime);
            slime.start();
            Location blob = spot.clone().add(0, 0.4, 0);
            for (int k = 0; k < Vfx.n(ctx(), 3); k++) {
                Vfx.trail(Vfx.scatter(chest, 0.2), blob, k % 2 == 0 ? PALETTE.body() : PALETTE.core(), 5 + k, false);
            }
            blob.getWorld().spawnParticle(Particle.ITEM_SLIME, blob, Vfx.n(ctx(), 8), 0.25, 0.2, 0.25, 0.05);
        }
        if (spawned == 0) {
            return ActivationResult.fail("슬라임이 나올 공간이 없습니다");
        }
        splitFx(owner, chest);
        owner.sendActionBar(Text.mm("<" + LIME + ">분열!</" + LIME + "> <gray>슬라임 " + spawned + "마리</gray>"));
        return ActivationResult.success();
    }

    /** The split: a wobbling cube of jelly squeezes out of the owner's chest and pops. */
    private void splitFx(Player owner, Location chest) {
        Vfx.burst(ctx(), chest, PALETTE, 0.7, Particle.ITEM_SLIME, false);
        Fx.sound(chest, Sound.ENTITY_SLIME_SQUISH, 1.0f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(chest, Sound.ENTITY_SLIME_JUMP, 0.9f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(chest, Sound.BLOCK_SLIME_BLOCK_PLACE, 0.8f, 0.8f);
        if (!reserveDisplays(1, 12)) {
            return;
        }
        Location at = flat(chest).add(Motion.horizontalFacing(owner.getLocation()).multiply(0.6));
        BlockDisplay cube = Displays.block(ctx(), owner, at, SLIME_BLOCK, Displays.scaled(0.2f), 12);
        Displays.animateNextTick(ctx(), owner, cube, Displays.transform(0, 0, 0, 0.75f, 0.45f, 0.75f, 20, 0, 0), 3);
        ctx().runLater(owner, 4, () -> Displays.animate(cube, Displays.transform(0, 0, 0, 0.45f, 0.8f, 0.45f, 40, 0,
                0), 3));
        ctx().runLater(owner, 7, () -> Displays.shrinkAway(ctx(), owner, cube, 4));
    }

    /**
     * A free spot for a little slime around {@code origin}: {@link #SPAWN_DISTANCE} blocks in the facing direction
     * turned by {@code angle} degrees (then the other side, then behind), on the ground, inside the arena and in view
     * of the owner. The owner's own spot if nothing fits.
     */
    private Location findSpot(Location origin, double angle) {
        World world = origin.getWorld();
        Vector forward = Motion.horizontalFacing(origin);
        Location eye = origin.clone().add(0, 1.0, 0);
        double[] turns = {angle, -angle, angle + 180};
        for (double turn : turns) {
            Location candidate = origin.clone().add(forward.clone().rotateAroundY(Math.toRadians(-turn))
                    .multiply(SPAWN_DISTANCE));
            RayTraceResult down = world.rayTraceBlocks(candidate.clone().add(0, 1.0, 0), new Vector(0, -1, 0), 3,
                    FluidCollisionMode.NEVER, true);
            if (down == null) {
                continue;
            }
            Location ground = down.getHitPosition().toLocation(world).add(0, 0.01, 0);
            if (Spots.isFree(world, ground, SLIME_WIDTH, SLIME_WIDTH) && ctx().isInsideArena(ground)
                    && Spots.clearPath(eye, ground.clone().add(0, 0.4, 0))) {
                ground.setYaw(origin.getYaw());
                return ground;
            }
        }
        Location self = origin.clone();
        self.setPitch(0);
        return self;
    }

    /**
     * The little slime's body: an invisible, silent frog (a small hitbox enemies can hit and kill, with physics but
     * no AI of its own) wearing a jelly body made of display entities.
     */
    private @Nullable Frog spawnCarrier(Location at) {
        double health = Math.max(1, params().num("slime_health"));
        Frog frog = at.getWorld().spawn(at, Frog.class, spawned -> {
            spawned.setPersistent(false);
            spawned.setInvisible(true);
            spawned.setSilent(true);
            // The controller drives every hop: no vanilla AI (frogs would eat small slimes), physics only.
            spawned.setAware(false);
            setBase(spawned, Attribute.SCALE, SLIME_SCALE);
            setBase(spawned, Attribute.MAX_HEALTH, health);
            spawned.setHealth(Math.min(health, Combat.maxHealth(spawned)));
        });
        if (!frog.isValid()) {
            return null;
        }
        ctx().summon(player(), frog);
        return frog;
    }

    /**
     * One little slime: its carrier, its jelly body (a translucent slime cube with two dark eyes that stretches when
     * it hops and squashes when it lands) and the controller that hops it after its prey until it bursts, melts or is
     * killed.
     */
    private final class LittleSlime {
        private final Frog body;
        private final long expiresAt;
        private final @Nullable BlockDisplay jelly;
        private final @Nullable BlockDisplay leftEye;
        private final @Nullable BlockDisplay rightEye;
        private @Nullable LivingEntity prey;
        private long nextHop;
        private boolean gone;
        private boolean airborne;
        private double lastHealth;
        private float yaw;
        private int squash;
        private int stretch;
        private float shownX = -1;
        private float shownY = -1;
        private Location lastSeen;

        LittleSlime(Frog body, long lifetime, @Nullable LivingEntity prey, float yaw) {
            this.body = body;
            this.expiresAt = ctx().currentTick() + lifetime;
            this.prey = prey;
            this.yaw = yaw;
            this.nextHop = ctx().currentTick() + 3 + ThreadLocalRandom.current().nextInt(5);
            this.lastHealth = body.getHealth();
            this.lastSeen = body.getLocation();
            Player owner = player();
            long skinLife = lifetime + 12;
            int parts = reserveDisplays(3, skinLife) ? 3 : reserveDisplays(1, skinLife) ? 1 : 0;
            Location at = skinAnchor();
            this.jelly = parts > 0 ? Displays.block(ctx(), owner, at, SLIME_BLOCK, jellyShape(0.2f, 0.2f), skinLife)
                    : null;
            this.leftEye = parts > 1 ? Displays.block(ctx(), owner, at, EYE, eyeShape(true, 0.2f, 0.2f), skinLife)
                    : null;
            this.rightEye = parts > 1 ? Displays.block(ctx(), owner, at, EYE, eyeShape(false, 0.2f, 0.2f), skinLife)
                    : null;
            if (jelly == null) {
                // No budget left for a jelly body: show the carrier itself rather than an invisible enemy.
                body.setInvisible(false);
            }
            for (BlockDisplay part : skin()) {
                part.setTeleportDuration(1);
            }
            // Pops out of the owner: grows from a blob into a full slime.
            stretch = 3;
        }

        private List<BlockDisplay> skin() {
            List<BlockDisplay> parts = new ArrayList<>(3);
            for (BlockDisplay part : new BlockDisplay[] {jelly, leftEye, rightEye}) {
                if (part != null) {
                    parts.add(part);
                }
            }
            return parts;
        }

        void start() {
            long runs = expiresAt - ctx().currentTick() + 1;
            Loop.repeat(ctx(), player(), 1, CONTROL_PERIOD, runs, this::control, () -> pop(false));
        }

        private boolean control(long run) {
            if (gone) {
                return false;
            }
            if (!body.isValid() || body.isDead()) {
                killed();
                return false;
            }
            lastSeen = body.getLocation();
            long now = ctx().currentTick();
            if (now >= expiresAt) {
                return false;
            }
            Player owner = player();
            double health = body.getHealth();
            if (health < lastHealth - 1.0E-3) {
                hurt();
            }
            lastHealth = health;
            BoundingBox reach = body.getBoundingBox().expand(CONTACT_GAP);
            for (LivingEntity enemy : ctx().enemiesNear(owner, Fx.body(body), 2.0)) {
                if (enemy.getBoundingBox().overlaps(reach)) {
                    pop(true);
                    return false;
                }
            }
            boolean grounded = Motion.isGrounded(body) || body.isInWater();
            if (grounded && airborne) {
                land();
            }
            airborne = !grounded;
            if (grounded && now >= nextHop) {
                hop(owner, chooseTarget(owner));
                nextHop = now + HOP_INTERVAL;
            } else if (airborne && run % 3 == 0) {
                Vfx.dot(Fx.body(body), Particle.DUST, GOO_SMALL, false);
            }
            follow(run);
            return true;
        }

        private @Nullable LivingEntity chooseTarget(Player owner) {
            LivingEntity current = prey;
            if (current != null && isValidPrey(owner, current)) {
                return current;
            }
            prey = null;
            for (LivingEntity candidate : ctx().enemiesNear(owner, body.getLocation(), AGGRO_RANGE)) {
                if (isValidPrey(owner, candidate)) {
                    prey = candidate;
                    return candidate;
                }
            }
            return null;
        }

        /** Enemies the owner cannot see (stealth) are not chased. */
        private boolean isValidPrey(Player owner, LivingEntity candidate) {
            return Combat.isTargetable(ctx(), owner, candidate) && candidate.getWorld().equals(body.getWorld())
                    && candidate.getLocation().distanceSquared(body.getLocation()) <= AGGRO_RANGE * AGGRO_RANGE * 2.25
                    && (!(candidate instanceof Player hidden) || owner.canSee(hidden));
        }

        /** A hop towards the prey (or back to the owner when there is none). */
        private void hop(Player owner, @Nullable LivingEntity target) {
            Location from = body.getLocation();
            Location goal;
            if (target != null) {
                goal = target.getLocation();
            } else if (owner.getWorld().equals(body.getWorld())
                    && owner.getLocation().distanceSquared(from) > FOLLOW_DISTANCE * FOLLOW_DISTANCE) {
                goal = owner.getLocation();
            } else {
                // Idle: a little bounce on the spot.
                body.setVelocity(new Vector(0, HOP_UP * 0.6, 0));
                stretch = 2;
                return;
            }
            Vector flat = goal.toVector().subtract(from.toVector()).setY(0);
            double distance = flat.length();
            if (distance < 1.0E-3) {
                return;
            }
            flat.multiply(1 / distance);
            double perHop = Math.max(0, params().num("slime_speed")) * HOP_INTERVAL / 20.0;
            double stride = Math.min(perHop, distance);
            long air = airTicks(HOP_UP);
            double speed = stride * (1 - AIR_DRAG) / (1 - Math.pow(AIR_DRAG, air));
            body.setVelocity(Motion.clamp(flat.clone().multiply(speed).setY(HOP_UP), MAX_VELOCITY));
            yaw = (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
            body.setRotation(yaw, 0);
            stretch = 3;
            Location feet = from.clone().add(0, 0.05, 0);
            feet.getWorld().spawnParticle(Particle.ITEM_SLIME, feet, Vfx.n(ctx(), 4), 0.2, 0.02, 0.2, 0.05);
            Vfx.ring(ctx(), feet, SLIME_WIDTH * 0.6, 6, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST,
                    GOO_SMALL, false);
            Fx.sound(from, Sound.ENTITY_SLIME_JUMP_SMALL, 0.6f, 1.1f + Fx.jitter(0.15f));
        }

        /** Touch-down: the jelly squashes flat for a moment with a soft squelch. */
        private void land() {
            squash = 3;
            Location feet = body.getLocation().add(0, 0.05, 0);
            Vfx.ring(ctx(), feet, SLIME_WIDTH * 0.7, 6, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST,
                    GOO_SMALL, false);
            Fx.sound(feet, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.5f, 1.2f + Fx.jitter(0.15f));
        }

        /** Something hit the little slime: it wobbles and spits goo. */
        private void hurt() {
            squash = 2;
            Location center = Fx.body(body);
            Vfx.cloud(ctx(), center, Particle.ITEM_SLIME, 6, 0.25, 0.05, null, false);
            Fx.sound(center, Sound.ENTITY_SLIME_HURT_SMALL, 0.8f, 1.0f + Fx.jitter(0.15f));
        }

        /** The jelly body follows its carrier and plays its squash-and-stretch. */
        private void follow(long run) {
            Location at = skinAnchor();
            float sx;
            float sy;
            if (squash > 0) {
                squash--;
                sx = 1.3f;
                sy = 0.62f;
            } else if (stretch > 0) {
                stretch--;
                sx = 0.8f;
                sy = 1.3f;
            } else if (airborne) {
                sx = 0.92f;
                sy = 1.1f;
            } else {
                // Breathing jiggle while it waits for the next hop.
                double wobble = Math.sin(run * 0.6) * 0.04;
                sx = (float) (1 + wobble);
                sy = (float) (1 - wobble);
            }
            boolean reshape = Math.abs(sx - shownX) > 1.0E-3 || Math.abs(sy - shownY) > 1.0E-3;
            shownX = sx;
            shownY = sy;
            if (jelly != null && jelly.isValid()) {
                jelly.teleport(at);
                if (reshape) {
                    Displays.animate(jelly, jellyShape(sx, sy), 2);
                }
            }
            for (boolean left : new boolean[] {true, false}) {
                BlockDisplay eye = left ? leftEye : rightEye;
                if (eye != null && eye.isValid()) {
                    eye.teleport(at);
                    if (reshape) {
                        Displays.animate(eye, eyeShape(left, sx, sy), 2);
                    }
                }
            }
        }

        private Location skinAnchor() {
            Location at = body.getLocation();
            at.setYaw(yaw);
            at.setPitch(0);
            return at;
        }

        /** The slime is done: it bursts in a splash of goo ({@code burst}) or just melts away. */
        void pop(boolean burst) {
            if (gone) {
                return;
            }
            gone = true;
            slimes.remove(this);
            Location center = body.isValid() ? Fx.body(body) : lastSeen.clone().add(0, 0.3, 0);
            if (body.isValid()) {
                body.remove();
            }
            if (burst) {
                removeSkin(0);
                burst(center);
            } else {
                removeSkin(6);
                Vfx.cloud(ctx(), center, Particle.ITEM_SLIME, 8, 0.25, 0.05, null, false);
                Vfx.cloud(ctx(), center, Particle.DUST, 5, 0.2, 0, GOO_SMALL, false);
                Fx.sound(center, Sound.ENTITY_SLIME_DEATH_SMALL, 0.6f, 1.2f + Fx.jitter(0.1f));
            }
        }

        /** An enemy killed the little slime: it splatters harmlessly. */
        private void killed() {
            gone = true;
            slimes.remove(this);
            removeSkin(0);
            Location center = lastSeen.clone().add(0, 0.3, 0);
            center.getWorld().spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 10), 0.25, 0.2, 0.25, 0.1,
                    SLIME_BLOCK);
            Vfx.cloud(ctx(), center, Particle.ITEM_SLIME, 10, 0.3, 0.08, null, false);
            Fx.sound(center, Sound.ENTITY_SLIME_DEATH_SMALL, 0.9f, 0.9f + Fx.jitter(0.1f));
        }

        private void removeSkin(int fadeTicks) {
            for (BlockDisplay part : skin()) {
                if (!part.isValid()) {
                    continue;
                }
                if (fadeTicks > 0) {
                    Displays.shrinkAway(ctx(), player(), part, fadeTicks);
                } else {
                    part.remove();
                }
            }
        }
    }

    /** The jelly cube, {@code sx} wide and {@code sy} tall (relative), standing on the carrier's feet. */
    private static Transformation jellyShape(float sx, float sy) {
        float width = JELLY_SIZE * sx;
        float height = JELLY_SIZE * sy;
        return Displays.transform(0, height / 2, 0, width, height, width, 0, 0, 0);
    }

    /** One eye on the jelly's front face (local +Z is the facing), following the jelly's squash and stretch. */
    private static Transformation eyeShape(boolean left, float sx, float sy) {
        float width = JELLY_SIZE * sx;
        float height = JELLY_SIZE * sy;
        return Displays.transform((left ? EYE_GAP : -EYE_GAP) * sx, height * EYE_LEVEL, width / 2 + 0.012f,
                EYE_WIDTH * sx, EYE_HEIGHT * sy, 0.02f, 0, 0, 0);
    }

    /** A little slime bursts: every enemy around takes damage and gets stuck in goo for a moment. */
    private void burst(Location center) {
        Player owner = player();
        double radius = Math.max(0.3, params().num("burst_radius"));
        double damage = params().num("burst_damage");
        long sticky = params().ticks("sticky_duration");
        double slow = Math.clamp(params().num("sticky_slow") / 100.0, 0.0, 1.0);
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            Combat.hit(ctx(), owner, enemy, damage);
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            if (sticky > 0 && slow > 0) {
                ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, sticky);
                gooDrip(enemy, sticky);
            }
        }
        World world = center.getWorld();
        Vfx.burst(ctx(), center, PALETTE, 0.8, Particle.ITEM_SLIME, false);
        world.spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 16), 0.3, 0.3, 0.3, 0.15, SLIME_BLOCK);
        Location ground = groundLevel(center);
        Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.1, 0), 0.2, radius, 4, Particle.DUST, GOO, false);
        puddle(ground, (float) Math.min(2.0, radius * 0.9));
        Fx.sound(center, Sound.ENTITY_SLIME_SQUISH, 1.0f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(center, Sound.ENTITY_SLIME_ATTACK, 0.8f, 1.1f + Fx.jitter(0.1f));
        Fx.sound(center, Sound.BLOCK_SLIME_BLOCK_BREAK, 0.9f, 0.9f + Fx.jitter(0.1f));
    }

    /** Goo dripping off a slowed enemy while the stickiness lasts. */
    private void gooDrip(LivingEntity enemy, long ticks) {
        Loop.repeat(ctx(), player(), 2, 4, Math.max(1, ticks / 4), run -> {
            if (!enemy.isValid() || enemy.isDead()) {
                return false;
            }
            Location at = enemy.getLocation().add(Fx.jitter(0.25f), enemy.getHeight() * 0.4, Fx.jitter(0.25f));
            Vfx.dot(at, Particle.ITEM_SLIME, null, false);
            Vfx.dot(enemy.getLocation().add(0, 0.1, 0), Particle.DUST, GOO, false);
            return true;
        });
    }

    /** A flat puddle of goo on the ground that seeps away. */
    private void puddle(Location ground, float size) {
        if (!reserveDisplays(1, PUDDLE_TICKS + 2)) {
            return;
        }
        Player owner = player();
        float yaw = (float) ThreadLocalRandom.current().nextDouble(90);
        BlockDisplay goo = Displays.block(ctx(), owner, flat(ground).add(0, 0.03, 0), LIME_GLASS,
                Displays.transform(0, 0, 0, 0.2f, 0.04f, 0.2f, yaw, 0, 0), PUDDLE_TICKS + 2);
        Displays.animateNextTick(ctx(), owner, goo, Displays.transform(0, 0, 0, size, 0.04f, size, yaw, 0, 0), 3);
        ctx().runLater(owner, PUDDLE_TICKS - 10, () -> Displays.shrinkAway(ctx(), owner, goo, 10));
    }

    // ------------------------------------------------------------------ ultimate: King Slime

    @Override
    public ActivationResult onUltimate() {
        if (king != null) {
            return ActivationResult.fail("이미 킹 슬라임입니다");
        }
        Player owner = player();
        King form = new King(Body.scaleOf(owner));
        king = form;
        form.begin();
        return ActivationResult.success();
    }

    /** One King Slime: grows, slams on every landing, then shrinks back and cleans up. */
    private final class King {
        private final double baseScale;
        private final double growth;
        private final List<NamespacedKey> buffs = new ArrayList<>();
        private final Map<UUID, Long> lastHit = new HashMap<>();
        private @Nullable NamespacedKey scaleKey;
        private @Nullable BlockDisplay shell;
        private @Nullable ItemDisplay crown;
        private int step;
        private boolean blocked;
        private long endTick;
        private long airTicks;
        private double peakY;
        private double lastY;
        private long lastSlam = Long.MIN_VALUE / 2;
        private int squash;

        King(double baseScale) {
            this.baseScale = baseScale;
            this.growth = Math.max(0, params().num("ult_scale") * baseScale - baseScale);
        }

        void begin() {
            Player owner = player();
            long duration = Math.max(1, params().ticks("ult_duration"));
            endTick = ctx().currentTick() + duration;
            double jumpHeight = Math.max(0, params().num("ult_jump_height"));
            double jumpBoost = Motion.launchSpeed(jumpHeight) - attribute(owner, Attribute.JUMP_STRENGTH, 0.42);
            if (jumpBoost > 1.0E-3) {
                buffs.add(ctx().addModifier(owner, owner, Attribute.JUMP_STRENGTH, jumpBoost, Operation.ADD_NUMBER,
                        0));
            }
            buffs.add(ctx().addModifier(owner, owner, Attribute.SAFE_FALL_DISTANCE, jumpHeight + SAFE_FALL_MARGIN,
                    Operation.ADD_NUMBER, 0));
            lastY = owner.getLocation().getY();
            peakY = lastY;

            Location at = owner.getLocation();
            crownFx(owner, at);
            shell = spawnShell(owner, duration);
            crown = spawnCrown(owner, duration);
            Loop.repeat(ctx(), owner, 0, 1, duration, this::tick, this::shrink);
        }

        private boolean tick(long tick) {
            Player owner = player();
            if (tick % GROW_INTERVAL == 0 && step < GROW_STEPS && growth > 0 && !blocked) {
                grow(owner);
            }
            Location at = owner.getLocation();
            boolean grounded = Motion.isGrounded(owner) || owner.isInWater();
            if (!grounded) {
                airTicks++;
                peakY = Math.max(peakY, at.getY());
                if (at.getY() < lastY - 0.05) {
                    telegraph(owner, tick);
                }
            } else {
                if (airTicks >= MIN_AIR_TICKS && peakY - at.getY() >= MIN_SLAM_DROP
                        && ctx().currentTick() - lastSlam >= SLAM_COOLDOWN) {
                    lastSlam = ctx().currentTick();
                    slam(owner, at);
                }
                airTicks = 0;
                peakY = at.getY();
            }
            lastY = at.getY();
            follow(owner, tick, grounded);
            if (tick % 5 == 0) {
                long left = Math.max(0, endTick - ctx().currentTick());
                owner.sendActionBar(Text.mm("<" + LIME + ">♛ 킹 슬라임</" + LIME + "> <gray>" + Fx.seconds(left)
                        + "초</gray>"));
            }
            return true;
        }

        private void grow(Player owner) {
            int next = step + 1;
            double nextScale = baseScale + growth * next / GROW_STEPS;
            if (!hasHeadroom(owner, nextScale) || !Spots.isFree(owner.getWorld(), owner.getLocation(),
                    Body.PLAYER.width() * nextScale, Body.PLAYER.height() * nextScale)) {
                blocked = true;
                return;
            }
            setStep(owner, next);
            Location feet = owner.getLocation();
            Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), owner.getWidth() * 0.7, 10, next * 0.5, Particle.DUST, GOO,
                    true);
            Fx.sound(feet, Sound.ENTITY_SLIME_SQUISH, 0.8f, 1.2f - next * 0.1f);
        }

        private void setStep(Player owner, int value) {
            NamespacedKey previous = scaleKey;
            if (previous != null) {
                ctx().removeModifier(owner, previous);
                scaleKey = null;
            }
            step = value;
            if (value > 0) {
                scaleKey = ctx().addModifier(owner, owner, Attribute.SCALE, growth * value / GROW_STEPS,
                        Operation.ADD_NUMBER, 0);
            }
        }

        /** Falling: a lime ring on the ground where the King Slime will land, so enemies can get out of the way. */
        private void telegraph(Player owner, long tick) {
            if (tick % 2 != 0) {
                return;
            }
            Location from = owner.getLocation();
            RayTraceResult floor = from.getWorld().rayTraceBlocks(from, new Vector(0, -1, 0), 24,
                    FluidCollisionMode.ALWAYS, true);
            if (floor == null) {
                return;
            }
            Location ground = floor.getHitPosition().toLocation(from.getWorld()).add(0, 0.12, 0);
            double radius = slamRadius();
            Vfx.ring(ctx(), ground, radius, (int) Math.max(14, radius * 6), tick * 0.2, Particle.DUST, GOO, true);
            Vfx.ring(ctx(), ground, radius * 0.5, (int) Math.max(8, radius * 3), -tick * 0.2,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), true);
        }

        private double slamRadius() {
            return Math.max(0.5, params().num("slam_radius"));
        }

        /** The King Slime lands: damage and a toss for every enemy around, jelly flying out over a shockwave. */
        private void slam(Player owner, Location at) {
            double radius = slamRadius();
            double damage = params().num("slam_damage");
            long rehit = params().ticks("slam_rehit");
            long now = ctx().currentTick();
            Location center = at.clone().add(0, 0.5, 0);
            int struck = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                Long last = lastHit.get(enemy.getUniqueId());
                if (last != null && now - last < rehit) {
                    continue;
                }
                lastHit.put(enemy.getUniqueId(), now);
                Combat.hit(ctx(), owner, enemy, damage);
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                struck++;
                Vector away = enemy.getLocation().toVector().subtract(at.toVector()).setY(0);
                if (away.lengthSquared() < 1.0E-4) {
                    double angle = ThreadLocalRandom.current().nextDouble(TAU);
                    away = new Vector(Math.cos(angle), 0, Math.sin(angle));
                }
                Motion.push(enemy, away.normalize().multiply(SLAM_OUT).setY(SLAM_UP), MAX_VELOCITY);
                Location body = Fx.body(enemy);
                enemy.getWorld().spawnParticle(Particle.ITEM_SLIME, body, Vfx.n(ctx(), 10), 0.3, 0.4, 0.3, 0.1);
            }
            squash = 4;
            slamFx(owner, at, radius);
            if (struck > 0) {
                owner.sendActionBar(Text.mm("<" + LIME + ">쿵!</" + LIME + "> <gray>" + struck + "명 적중</gray>"));
            }
        }

        private void slamFx(Player owner, Location at, double radius) {
            World world = at.getWorld();
            Location ground = at.clone().add(0, 0.15, 0);
            world.spawnParticle(Particle.FLASH, ground.clone().add(0, 0.3, 0), 1, 0, 0, 0, 0, PALETTE.core(), true);
            Vfx.shockwave(ctx(), owner, ground, 0.5, radius, 6, Particle.DUST, GOO, true);
            Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.15, 0), 0.3, radius * 0.85, 6,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.3f), true);
            world.spawnParticle(Particle.ITEM_SLIME, ground, Vfx.n(ctx(), 40), radius * 0.4, 0.2, radius * 0.4, 0.2,
                    null, true);
            world.spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 30), radius * 0.35, 0.1, radius * 0.35, 0.15,
                    SLIME_BLOCK, true);
            BlockData floor = groundUnder(at);
            if (floor != null) {
                Vfx.ring(ctx(), ground, radius * 0.6, 8, ThreadLocalRandom.current().nextDouble(TAU),
                        Particle.DUST_PILLAR, floor, true);
            }
            splashCubes(owner, at, radius);
            Fx.sound(at, Sound.ENTITY_SLIME_SQUISH, 1.6f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ENTITY_SLIME_ATTACK, 1.2f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.BLOCK_SLIME_BLOCK_FALL, 1.4f, 0.6f);
            Fx.sound(at, Sound.ITEM_MACE_SMASH_GROUND, 0.7f, 0.8f + Fx.jitter(0.05f));
        }

        /** Cubes of jelly fly out of the landing, tumble and melt. */
        private void splashCubes(Player owner, Location at, double radius) {
            int granted = SPLASH_CUBES;
            long lifetime = CUBE_FLIGHT_TICKS + 6;
            while (granted > 0 && !reserveDisplays(granted, lifetime)) {
                granted--;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double phase = random.nextDouble(TAU);
            Location start = flat(at).add(0, 0.4, 0);
            for (int i = 0; i < granted; i++) {
                double angle = phase + i * TAU / granted + random.nextDouble(-0.2, 0.2);
                float size = (float) random.nextDouble(0.25, 0.42);
                BlockDisplay cube = Displays.block(ctx(), owner, start, SLIME_BLOCK, Displays.scaled(size), lifetime);
                cube.setTeleportDuration(CUBE_FLIGHT_TICKS);
                double reach = radius * random.nextDouble(0.6, 0.95);
                Location end = start.clone().add(Math.cos(angle) * reach, -0.25, Math.sin(angle) * reach);
                float spin = (float) random.nextDouble(120, 300);
                ctx().runLater(owner, 1, () -> {
                    if (cube.isValid()) {
                        cube.teleport(end);
                        Displays.animate(cube, Displays.transform(0, 0, 0, size * 1.2f, size * 0.6f, size * 1.2f,
                                spin, 0, 0), CUBE_FLIGHT_TICKS);
                    }
                });
                ctx().runLater(owner, CUBE_FLIGHT_TICKS + 1, () -> {
                    if (cube.isValid()) {
                        Displays.shrinkAway(ctx(), owner, cube, 4);
                    }
                });
            }
        }

        /** The jelly shell and the crown follow the King Slime; the shell stretches when rising, squashes on landing. */
        private void follow(Player owner, long tick, boolean grounded) {
            Location feet = flat(owner.getLocation());
            BlockDisplay body = shell;
            if (body != null) {
                if (!body.isValid() || !body.getWorld().equals(owner.getWorld())) {
                    shell = null;
                } else {
                    body.teleport(feet);
                    float width = (float) (owner.getWidth() + 0.5);
                    float height = (float) (owner.getHeight() * 0.72);
                    float sx = width;
                    float sy = height;
                    if (squash > 0) {
                        squash--;
                        sx = width * 1.25f;
                        sy = height * 0.7f;
                    } else if (!grounded) {
                        sx = width * 0.9f;
                        sy = height * 1.12f;
                    } else if (tick % 10 < 5) {
                        sx = width * 1.03f;
                        sy = height * 0.97f;
                    }
                    Displays.animate(body, shellShape(sx, sy), 3);
                }
            }
            ItemDisplay hat = crown;
            if (hat != null) {
                if (!hat.isValid() || !hat.getWorld().equals(owner.getWorld())) {
                    crown = null;
                } else {
                    double bob = Math.sin(tick * 0.2) * 0.06;
                    hat.teleport(feet.clone().add(0, owner.getHeight() + 0.25 + bob, 0));
                    if (tick % 4 == 0) {
                        float size = (float) (0.75 * Body.scaleOf(owner));
                        Displays.animate(hat, Displays.centered(0, 0, 0, size, size, size, tick * 4f, 0, 0), 4);
                    }
                    if (tick % 6 == 0) {
                        Vfx.dot(feet.clone().add(Fx.jitter(0.3f), owner.getHeight() + 0.45, Fx.jitter(0.3f)),
                                Particle.DUST, GOLD, false);
                    }
                }
            }
        }

        private void shrink() {
            Player owner = player();
            Fx.sound(owner.getLocation(), Sound.ENTITY_SLIME_DEATH, 0.8f, 1.2f);
            Fx.sound(owner.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.4f);
            for (Display display : new Display[] {shell, crown}) {
                if (display != null && display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 6);
                }
            }
            shell = null;
            crown = null;
            Loop.repeat(ctx(), owner, 0, GROW_INTERVAL, Math.max(1, step), tick -> {
                setStep(owner, Math.max(0, step - 1));
                Vfx.cloud(ctx(), Fx.body(owner), Particle.ITEM_SLIME, 6, 0.4, 0.05, null, false);
                return step > 0;
            }, this::end);
        }

        private void end() {
            Player owner = player();
            setStep(owner, 0);
            for (NamespacedKey key : buffs) {
                ctx().removeModifier(owner, key);
            }
            buffs.clear();
            if (king == this) {
                king = null;
            }
            splat(owner.getLocation(), 1.0, true);
        }
    }

    /** The coronation: a gold flash, a fountain of jelly and a fanfare of squelches. */
    private void crownFx(Player owner, Location at) {
        World world = at.getWorld();
        Location body = at.clone().add(0, owner.getHeight() * 0.6, 0);
        world.spawnParticle(Particle.FLASH, body, 1, 0, 0, 0, 0, Color.fromRGB(0xFFE082), true);
        Vfx.helix(ctx(), at, 1.0, 3.2, 1.5, 3, 12, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.2f), true);
        world.spawnParticle(Particle.ITEM_SLIME, body, Vfx.n(ctx(), 40), 0.8, 0.8, 0.8, 0.2, null, true);
        Vfx.shockwave(ctx(), owner, at.clone().add(0, 0.15, 0), 0.4, 3.0, 6, Particle.DUST, GOO, true);
        Vfx.cloud(ctx(), body.clone().add(0, 1.2, 0), Particle.DUST, 12, 0.5, 0, GOLD, true);
        Fx.sound(at, Sound.ENTITY_SLIME_SQUISH, 1.4f, 0.5f);
        Fx.sound(at, Sound.ENTITY_SLIME_JUMP, 1.2f, 0.5f + Fx.jitter(0.05f));
        Fx.sound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.4f);
        Fx.sound(at, Sound.ITEM_ARMOR_EQUIP_GOLD, 1.0f, 0.8f);
    }

    private @Nullable BlockDisplay spawnShell(Player owner, long duration) {
        if (!reserveDisplays(1, duration + 10)) {
            return null;
        }
        BlockDisplay shell = Displays.block(ctx(), owner, flat(owner.getLocation()), SLIME_BLOCK,
                shellShape(0.2f, 0.2f), duration + 10);
        shell.setBrightness(null);
        shell.setTeleportDuration(1);
        return shell;
    }

    private @Nullable ItemDisplay spawnCrown(Player owner, long duration) {
        if (!reserveDisplays(1, duration + 10)) {
            return null;
        }
        Location at = flat(owner.getLocation()).add(0, owner.getHeight() + 0.25, 0);
        ItemDisplay crown = Displays.item(ctx(), owner, at, new ItemStack(Material.GOLDEN_HELMET),
                Displays.centered(0, 0, 0, 0.01f, 0.01f, 0.01f, 0, 0, 0), duration + 10);
        crown.setTeleportDuration(1);
        return crown;
    }

    /** A jelly box of {@code width} x {@code height} standing on the entity location. */
    private static Transformation shellShape(float width, float height) {
        return Displays.transform(0, height / 2, 0, width, height, width, 0, 0, 0);
    }

    // ------------------------------------------------------------------ helpers

    private static long airTicks(double up) {
        double y = 0;
        double v = up;
        for (int t = 1; t < 200; t++) {
            y += v;
            v = (v - Motion.GRAVITY) * Motion.DRAG;
            if (y <= 0) {
                return t;
            }
        }
        return 200;
    }

    /** True if a player at its current position has room above its head for a body of {@code scale}. */
    private static boolean hasHeadroom(Player player, double scale) {
        Location at = player.getLocation();
        BoundingBox column = new BoundingBox(at.getX() - HEADROOM_HALF_WIDTH, at.getY() + 0.1,
                at.getZ() - HEADROOM_HALF_WIDTH, at.getX() + HEADROOM_HALF_WIDTH,
                at.getY() + STANDING_HEIGHT * scale + 0.05, at.getZ() + HEADROOM_HALF_WIDTH);
        return !player.getWorld().hasCollisionsIn(column);
    }

    /** The ground level below {@code at} (within 3 blocks), or {@code at} itself. */
    private static Location groundLevel(Location at) {
        RayTraceResult down = at.getWorld().rayTraceBlocks(at, new Vector(0, -1, 0), 3, FluidCollisionMode.ALWAYS,
                true);
        return down == null ? at.clone() : down.getHitPosition().toLocation(at.getWorld());
    }

    /** The solid block right under {@code at}, null over air, fluids and plants. */
    private static @Nullable BlockData groundUnder(Location at) {
        BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        return ground.getMaterial().isSolid() ? ground : null;
    }

    private static double attribute(LivingEntity entity, Attribute attribute, double fallback) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    private static void setBase(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    /** {@code at} without its rotation, so display entities placed there keep their own orientation. */
    private static Location flat(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }

    /** Claims {@code count} display entities for {@code lifetime} ticks if that stays within {@link #MAX_DISPLAYS}. */
    private boolean reserveDisplays(int count, long lifetime) {
        if (count <= 0 || liveDisplays + count > MAX_DISPLAYS) {
            return false;
        }
        liveDisplays += count;
        ctx().runLater(player(), Math.max(1, lifetime), () -> liveDisplays -= count);
        return true;
    }
}
