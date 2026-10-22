package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
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
 * 염소 — the mountain goat. It jumps higher and shrugs off falls, rams whoever stands in its way clean across the
 * field (into a wall, for extra pain), and blows its horn to bring a landslide of boulders down the hill behind it.
 *
 * <p>Look: goat white and horn tan. The ram is staged: hooves scrape and a dashed arrow marks the charge on the
 * ground while two horns lower in front of the goat's head, then a streak of dust, a crunching impact flash and, on a
 * wall, a crack ring with a "쾅!" popping out of the stone. The landslide is announced by the horn and a rumbling
 * outline of the slope; then waves of tumbling boulders (stone, andesite, tuff, cobble) drop over the goat's head
 * and roll down the band, kicking up dust and crumbling at walls or at the bottom.
 */
public final class GoatAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("goat", "염소", Material.GOAT_HORN)
            .color(TextColor.color(0xD9BE8C))
            .difficulty(2)
            .passive("산양",
                    "점프력이 {jump_boost}% 높아지고 낙하 피해를 {fall_reduction}% 덜 받습니다.")
            .active("박치기",
                    "{ram_windup}초간 발을 구르며 뿔을 낮춘 뒤 바라보는 방향으로 최대 {ram_distance}블록 돌진합니다. "
                            + "처음 부딪힌 적에게 {ram_damage} 피해를 주고 약 {ram_knockback}블록 날려 보내며, 날아간 "
                            + "적이 벽에 부딪히면 {wall_damage} 추가 피해를 입고 {wall_stun}초간 휘청입니다.",
                    8)
            .ultimate("산사태",
                    "뿔나팔을 불면 {slide_delay}초 뒤 등 뒤의 언덕에서 바위가 쏟아져 앞쪽 {slide_length}블록, 폭 "
                            + "{slide_width}블록을 {slide_waves}차례 휩쓸고 지나갑니다. 휩쓸린 적은 물결마다 "
                            + "{boulder_damage} 피해를 입고 밀려나며, 바위는 벽에 부딪히면 부서집니다.")
            .param("jump_boost", 30)
            .param("fall_reduction", 60)
            .param("ram_windup", 0.5)
            .param("ram_distance", 8)
            .param("ram_damage", 4)
            .param("ram_knockback", 8)
            .param("wall_damage", 3)
            .param("wall_stun", 1)
            .param("slide_delay", 1)
            .param("slide_length", 16)
            .param("slide_width", 7)
            .param("slide_waves", 3)
            .param("boulder_damage", 3)
            .build();

    private static final String TAN = "#D9BE8C";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** Goat white, warm horn tan, the dark brown of the horn's base. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xD9BE8C, 0x6B4A2E);
    /** Rock dust of the landslide. */
    private static final Vfx.Palette ROCK = Vfx.Palette.of(0xE8E2D6, 0x9C9284, 0x4A443C);
    private static final Particle.DustOptions ARROW_DUST = new Particle.DustOptions(Color.fromRGB(0xF5E9D0), 1.1f);
    private static final Material[] BOULDERS = {Material.COBBLESTONE, Material.STONE, Material.ANDESITE,
            Material.TUFF, Material.MOSSY_COBBLESTONE};
    private static final Material FALLBACK_GROUND = Material.DIRT;
    private static final int DISPLAY_BUDGET = 24;

    // ------------------------------------------------------------------ passive
    private static final double LANDING_FEEDBACK_FALL = 2.5;
    private static final long JUMP_PUFF_COOLDOWN = 8;

    // ------------------------------------------------------------------ ram
    private static final double RAM_SPEED = 1.05;
    private static final double RAM_SINK = -0.08;
    private static final double CONTACT_RADIUS = 1.0;
    private static final double WINDUP_SLOW = 0.7;
    private static final long RAM_GRACE_TICKS = 3;
    private static final double STALL_EPSILON = 0.1;
    private static final int STALL_LIMIT = 3;
    private static final long MAX_LAG_TICKS = 8;
    private static final double MILLIS_PER_TICK = 50.0;
    private static final double KNOCK_UP = 0.42;
    /** Ticks a knocked-back enemy flies before it is low and slow enough to land (vanilla air drag 0.91). */
    private static final int KNOCK_AIR_TICKS = 11;
    private static final double AIR_DRAG = 0.91;
    private static final double MAX_SPEED = 2.5;
    /** A rammed enemy is watched this long for a wall (plus the owner's ping, its position arrives late). */
    private static final long WALL_WATCH_TICKS = 22;
    private static final double WALL_PROBE = 0.35;
    /** The probe skips the feet, so sliding over the floor or a small step never counts as a wall. */
    private static final double WALL_PROBE_FEET = 0.55;
    private static final double WALL_BOUNCE = 0.25;
    private static final int STUN_AMPLIFIER = 2;
    private static final float HORN_SCALE = 0.55f;
    private static final long RAM_LANDING_TICKS = 60;

    // ------------------------------------------------------------------ landslide
    private static final int BOULDERS_PER_WAVE = 4;
    private static final long WAVE_INTERVAL = 8;
    private static final long DROP_TICKS = 8;
    private static final double DROP_BEHIND = 2.5;
    private static final double DROP_HEIGHT = 3.5;
    private static final double DROP_LAND = 1.5;
    private static final double ROLL_SPEED = 0.62;
    private static final double BOULDER_MIN = 0.9;
    private static final double BOULDER_MAX = 1.35;
    /** A boulder reaches enemies this far beyond its own surface. */
    private static final double BOULDER_REACH = 0.7;
    /** A step up of more than this is a wall: the boulder crumbles against it. */
    private static final double MAX_CLIMB = 1.1;
    private static final double MAX_DROP_PER_TICK = 0.6;
    private static final double SWEEP_PUSH = 0.95;
    private static final double SWEEP_LIFT = 0.42;
    private static final double CARRY_PUSH = 0.55;
    private static final long RUMBLE_PERIOD = 6;
    private static final long HORN_HOLD_TICKS = 24;

    private final LandingGuard landing = new LandingGuard();
    private boolean ramming;
    private boolean wasGrounded = true;
    private double lastY;
    private float maxFall;
    private long lastJumpPuff = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
    private final List<Display> displays = new ArrayList<>();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        double boost = Math.max(0, params().num("jump_boost")) / 100.0;
        if (boost > 0) {
            ctx().addModifier(player(), player(), Attribute.JUMP_STRENGTH, boost, Operation.MULTIPLY_SCALAR_1, 0);
        }
        lastY = player().getLocation().getY();
    }

    @Override
    public void onRevoke() {
        ramming = false;
        landing.disarm();
        displays.clear();
    }

    // ------------------------------------------------------------------ passive: mountain goat

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !Combat.isFall(event)) {
            return;
        }
        if (landing.absorb(event, ctx().currentTick())) {
            hoofLanding(player(), true);
            return;
        }
        double reduction = Math.clamp(params().num("fall_reduction") / 100.0, 0.0, 1.0);
        event.setDamage(event.getDamage() * (1 - reduction));
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        landing.update(owner, now);
        boolean grounded = Motion.isGrounded(owner) || owner.isInWater();
        double y = owner.getLocation().getY();
        if (!grounded) {
            maxFall = Math.max(maxFall, owner.getFallDistance());
            if (wasGrounded && y > lastY + 0.05 && now - lastJumpPuff >= JUMP_PUFF_COOLDOWN && !ramming) {
                lastJumpPuff = now;
                jumpPuff(owner);
            }
        } else if (!wasGrounded) {
            if (maxFall >= LANDING_FEEDBACK_FALL) {
                hoofLanding(owner, false);
            }
            maxFall = 0;
        }
        wasGrounded = grounded;
        lastY = y;
    }

    /** A springy jump: a puff of dust and a soft hop sound. */
    private void jumpPuff(Player owner) {
        Location feet = owner.getLocation().add(0, 0.05, 0);
        Vfx.ring(ctx(), feet, 0.35, 6, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.7f), false);
        Vfx.dot(feet, Particle.POOF, null, false);
        Fx.sound(feet, Sound.ENTITY_GOAT_STEP, 0.4f, 1.3f + Fx.jitter(0.1f));
    }

    /** Hooves clatter on a high landing: stone chips and a dust ring. */
    private void hoofLanding(Player owner, boolean guarded) {
        Location feet = owner.getLocation().add(0, 0.05, 0);
        BlockData ground = groundUnder(feet);
        feet.getWorld().spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 10), 0.3, 0.05, 0.3, 0.1, ground);
        Vfx.shockwave(ctx(), owner, feet, 0.2, 1.2, 4, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.9f), false);
        Fx.sound(feet, Sound.ENTITY_GOAT_STEP, 0.9f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(feet, Sound.ENTITY_GOAT_LONG_JUMP, 0.5f, 1.3f + Fx.jitter(0.1f));
        if (guarded) {
            Fx.sound(feet, Sound.ENTITY_HORSE_GALLOP, 0.4f, 1.6f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ active: ram

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 들이받을 수 없습니다");
        }
        if (ramming) {
            return ActivationResult.fail("이미 돌진 중입니다");
        }
        ramming = true;
        new Ram(owner).windUp();
        return ActivationResult.success();
    }

    /** Ticks the server sees the owner's movement late: one round trip, capped (ping can read 0 after joining). */
    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / MILLIS_PER_TICK));
    }

    /** The ram: a wind-up with the horns lowered, then a charge that slams into the first enemy in the way. */
    private final class Ram {

        private final Player owner;
        private final long windup;
        private final ItemDisplay[] horns = new ItemDisplay[2];
        private Vector direction;

        Ram(Player owner) {
            this.owner = owner;
            this.windup = Math.max(1, params().ticks("ram_windup"));
            this.direction = facing(owner);
        }

        void windUp() {
            ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -WINDUP_SLOW, Operation.MULTIPLY_SCALAR_1,
                    windup + 1);
            Location head = owner.getEyeLocation();
            Fx.sound(head, Sound.ENTITY_GOAT_PREPARE_RAM, 1.2f, 1.0f + Fx.jitter(0.08f));
            Fx.sound(head, Sound.ENTITY_GOAT_SCREAMING_PREPARE_RAM, 0.4f, 1.1f + Fx.jitter(0.08f));
            long lifetime = windup + (long) Math.ceil(params().num("ram_distance") / RAM_SPEED) + MAX_LAG_TICKS + 20;
            for (int side = 0; side < 2; side++) {
                if (!fits(1)) {
                    break;
                }
                horns[side] = Displays.item(ctx(), owner, hornAnchor(owner), new ItemStack(Material.GOAT_HORN),
                        hornPose(side, 0.0, 0.1f), lifetime);
                horns[side].setTeleportDuration(1);
                displays.add(horns[side]);
                Displays.animateNextTick(ctx(), owner, horns[side], hornPose(side, 1.0, HORN_SCALE), (int) windup);
            }
            Loop.repeat(ctx(), owner, 0, 1, windup, this::scrape, this::charge);
        }

        /** Wind-up: the hooves scrape, the horns follow the head and the charge path is marked on the ground. */
        private boolean scrape(long tick) {
            direction = facing(owner);
            followHorns(0);
            Location feet = owner.getLocation();
            if (tick % 2 == 0) {
                BlockData ground = groundUnder(feet);
                Location kick = feet.clone().subtract(direction.clone().multiply(0.4)).add(0, 0.1, 0);
                kick.getWorld().spawnParticle(Particle.BLOCK, kick, Vfx.n(ctx(), 4), 0.15, 0.05, 0.15, 0.1, ground);
                drawArrow(feet, tick);
            }
            if (tick % 4 == 0) {
                Fx.sound(feet, Sound.ENTITY_GOAT_STEP, 0.8f, 0.7f + Fx.jitter(0.1f));
                Vfx.dot(feet.clone().add(0, 0.1, 0), Particle.POOF, null, false);
            }
            return true;
        }

        /** A dashed arrow on the ground along the charge, visible to everyone in the way. */
        private void drawArrow(Location feet, long tick) {
            double distance = Spots.arenaReach(ctx(), feet, direction, Math.max(1, params().num("ram_distance")));
            Location start = feet.clone().add(0, 0.12, 0);
            double offset = (tick % 4) * 0.25;
            for (double d = 0.8 + offset; d < distance; d += 1.0) {
                Location a = start.clone().add(direction.clone().multiply(d));
                Location b = start.clone().add(direction.clone().multiply(Math.min(distance, d + 0.45)));
                Vfx.line(ctx(), a, b, 4, Particle.DUST, ARROW_DUST, true);
            }
            Location tip = start.clone().add(direction.clone().multiply(distance));
            Vector side = new Vector(-direction.getZ(), 0, direction.getX());
            Vfx.line(ctx(), tip, tip.clone().subtract(direction.clone().multiply(0.6)).add(side.clone().multiply(0.5)),
                    5, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.1f), true);
            Vfx.line(ctx(), tip, tip.clone().subtract(direction.clone().multiply(0.6)).subtract(side.multiply(0.5)),
                    5, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.1f), true);
        }

        /** The charge itself, velocity-driven (the client resolves collisions, so it never passes through walls). */
        private void charge() {
            if (!ctx().isAlive(owner)) {
                ramming = false;
                return;
            }
            Location start = owner.getLocation();
            double distance = Spots.arenaReach(ctx(), start, direction, Math.max(1, params().num("ram_distance")));
            if (distance < 1) {
                ramming = false;
                stumble(start);
                return;
            }
            long lag = lagTicks(owner);
            long maxTicks = (long) Math.ceil(distance / RAM_SPEED) + 6 + lag;
            landing.arm(ctx().currentTick(), maxTicks + RAM_LANDING_TICKS);
            Fx.sound(start, Sound.ENTITY_GOAT_LONG_JUMP, 1.0f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(start, Sound.ENTITY_HORSE_GALLOP, 0.9f, 1.2f + Fx.jitter(0.1f));
            Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 0.6f + Fx.jitter(0.05f));
            owner.setVelocity(direction.clone().multiply(RAM_SPEED).setY(RAM_SINK));
            owner.setFallDistance(0);

            Location[] last = {start};
            int[] stalls = {0};
            boolean[] moved = {false};
            LivingEntity[] struck = {null};
            Loop.repeat(ctx(), owner, 0, 1, maxTicks, tick -> {
                Location now = owner.getLocation();
                if (!now.getWorld().equals(start.getWorld())) {
                    return false;
                }
                followHorns(RAM_SPEED);
                Location front = now.clone().add(direction.clone().multiply(0.7)).add(0, owner.getHeight() * 0.5, 0);
                List<LivingEntity> contact = ctx().enemiesNear(owner, front, CONTACT_RADIUS);
                if (!contact.isEmpty()) {
                    struck[0] = contact.getFirst();
                    return false;
                }
                if (horizontalDistance(start, now) >= distance - 0.2) {
                    return false;
                }
                if (!moved[0] && horizontalDistance(start, now) >= STALL_EPSILON) {
                    moved[0] = true;
                }
                if (moved[0] || tick >= RAM_GRACE_TICKS + lag) {
                    stalls[0] = horizontalDistance(last[0], now) < STALL_EPSILON ? stalls[0] + 1 : 0;
                    if (stalls[0] >= STALL_LIMIT) {
                        return false;
                    }
                }
                last[0] = now;
                owner.setVelocity(direction.clone().multiply(RAM_SPEED).setY(RAM_SINK));
                owner.setFallDistance(0);
                streak(now, tick);
                return true;
            }, () -> {
                ramming = false;
                LivingEntity hit = struck[0];
                if (hit != null && Combat.isTargetable(ctx(), owner, hit)) {
                    impact(hit);
                } else {
                    owner.setVelocity(direction.clone().multiply(0.1).setY(RAM_SINK));
                    stumble(owner.getLocation());
                }
                dropHorns();
            });
        }

        /** One tick of the charge: dust kicked up behind, speed lines streaming back. */
        private void streak(Location at, long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            BlockData ground = groundUnder(at);
            at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.1, 0), Vfx.n(ctx(), 3), 0.2, 0.05, 0.2,
                    0.1, ground);
            Vfx.dot(at.clone().add(0, 0.15, 0).subtract(direction.clone().multiply(0.5)), Particle.CLOUD, null, false);
            Vector side = new Vector(-direction.getZ(), 0, direction.getX());
            for (int i = 0; i < Vfx.n(ctx(), 2); i++) {
                Location from = at.clone().add(side.clone().multiply(random.nextDouble(-0.45, 0.45)))
                        .add(0, random.nextDouble(0.3, owner.getHeight()), 0).add(direction.clone().multiply(0.5));
                Vfx.trail(from, from.clone().subtract(direction.clone().multiply(random.nextDouble(1.5, 2.4))),
                        i == 0 ? PALETTE.core() : PALETTE.body(), 5, false);
            }
            if (tick % 3 == 0) {
                Fx.sound(at, Sound.ENTITY_GOAT_STEP, 0.7f, 1.0f + Fx.jitter(0.15f));
            }
        }

        /** The horns hit: damage, a launch across the field and a wall watch. */
        private void impact(LivingEntity enemy) {
            Location contact = Fx.body(enemy).subtract(direction.clone().multiply(enemy.getWidth() * 0.5));
            World world = contact.getWorld();
            owner.setVelocity(direction.clone().multiply(-0.25).setY(0.15));
            Combat.hit(ctx(), owner, enemy, params().num("ram_damage"));
            world.spawnParticle(Particle.FLASH, contact, 1, 0, 0, 0, 0, PALETTE.core());
            Vfx.burst(ctx(), contact, PALETTE, 0.7, Particle.CRIT, false);
            Vfx.arc(ctx(), contact, 0.9, yawOf(direction), 140, 12, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.2f), false);
            Fx.sound(contact, Sound.ENTITY_GOAT_RAM_IMPACT, 1.4f, 1.0f + Fx.jitter(0.08f));
            Fx.sound(contact, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(contact, Sound.ITEM_MACE_SMASH_GROUND, 0.6f, 1.4f + Fx.jitter(0.1f));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            double distance = Math.max(0, params().num("ram_knockback"));
            double speed = distance * (1 - AIR_DRAG) / (1 - Math.pow(AIR_DRAG, KNOCK_AIR_TICKS));
            Vector launch = direction.clone().multiply(speed).setY(KNOCK_UP);
            if (Motion.push(enemy, launch, MAX_SPEED)) {
                enemy.setFallDistance(0);
                long window = WALL_WATCH_TICKS + (enemy instanceof Player victim ? lagTicks(victim) : 0);
                Loop.repeat(ctx(), owner, 1, 1, window, new WallWatch(enemy, direction));
                owner.sendActionBar(Text.mm("<" + TAN + "><b>박치기!</b></" + TAN + ">"));
            }
        }

        /** Nobody in the way: a stumble and a snort. */
        private void stumble(Location at) {
            Vfx.cloud(ctx(), at.clone().add(0, 0.2, 0), Particle.POOF, 6, 0.3, 0.02, null, false);
            Fx.sound(at, Sound.ENTITY_GOAT_AMBIENT, 0.8f, 0.9f + Fx.jitter(0.1f));
        }

        private void followHorns(double lead) {
            Location anchor = hornAnchor(owner).add(direction.clone().multiply(lead));
            for (ItemDisplay horn : horns) {
                if (horn != null && horn.isValid()) {
                    horn.teleport(anchor);
                }
            }
        }

        private void dropHorns() {
            for (ItemDisplay horn : horns) {
                if (horn != null && horn.isValid()) {
                    Displays.shrinkAway(ctx(), owner, horn, 5);
                }
            }
        }
    }

    /** Where the horns sit: in front of the head, turned with the body. */
    private static Location hornAnchor(Player owner) {
        Location at = owner.getEyeLocation();
        at.setYaw(owner.getBodyYaw());
        at.setPitch(0);
        return at;
    }

    /**
     * One horn in head space (local +Z = facing, +X = the owner's left). {@code lowered} 0 = upright beside the head,
     * 1 = pointing forward like a ram's.
     */
    private static Transformation hornPose(int side, double lowered, float scale) {
        float sign = side == 0 ? 1 : -1;
        float pitch = (float) (lowered * 70);
        return Displays.centered(sign * 0.2f, 0.12f - (float) lowered * 0.1f, 0.15f + (float) lowered * 0.25f, scale,
                scale, scale, sign * 90f, 0, sign * (20f + pitch));
    }

    /**
     * Watches a rammed enemy in flight: if it hits a wall (a block right in front of its body in the flight direction),
     * it takes the wall damage and staggers.
     */
    private final class WallWatch implements LongPredicate {

        private final LivingEntity victim;
        private final Vector direction;

        WallWatch(LivingEntity victim, Vector direction) {
            this.victim = victim;
            this.direction = direction;
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            if (!Combat.isTargetable(ctx(), owner, victim)) {
                return false;
            }
            BoundingBox box = victim.getBoundingBox();
            BoundingBox probe = new BoundingBox(box.getMinX() + 0.05, box.getMinY() + WALL_PROBE_FEET,
                    box.getMinZ() + 0.05, box.getMaxX() - 0.05, box.getMaxY() - 0.05, box.getMaxZ() - 0.05)
                    .shift(direction.getX() * WALL_PROBE, 0, direction.getZ() * WALL_PROBE);
            if (!victim.getWorld().hasCollisionsIn(probe)) {
                if (tick % 2 == 0 && visible(owner, victim)) {
                    Vfx.dot(Fx.body(victim), Particle.CLOUD, null, false);
                }
                return true;
            }
            slam(owner, box);
            return false;
        }

        private void slam(Player owner, BoundingBox box) {
            World world = victim.getWorld();
            Location body = new Location(world, box.getCenterX(), box.getMinY() + box.getHeight() * 0.6,
                    box.getCenterZ());
            Location contact = body.clone().add(direction.clone().multiply(box.getWidthX() * 0.5 + 0.15));
            Block block = contact.clone().add(direction.clone().multiply(0.3)).getBlock();
            Combat.hit(ctx(), owner, victim, params().num("wall_damage"));
            if (Combat.isTargetable(ctx(), owner, victim)) {
                victim.setVelocity(direction.clone().multiply(-WALL_BOUNCE).setY(0.2));
                victim.setFallDistance(0);
                int stun = (int) Math.min(Integer.MAX_VALUE, params().ticks("wall_stun"));
                if (stun > 0) {
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, stun, STUN_AMPLIFIER, false,
                            true, true));
                }
            }
            Fx.sound(contact, Sound.ENTITY_GOAT_RAM_IMPACT, 1.2f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(contact, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.9f, 0.7f + Fx.jitter(0.1f));
            Fx.sound(contact, Sound.BLOCK_ANVIL_LAND, 0.4f, 1.3f + Fx.jitter(0.1f));
            owner.sendActionBar(Text.mm("<" + TAN + "><b>쾅! 벽에 꽂혔다</b></" + TAN + "> <white>+"
                    + AbilityInfo.formatNumber(params().num("wall_damage")) + "</white>"));
            if (!visible(owner, victim)) {
                return; // the crash is heard, never seen
            }
            if (!block.getType().isAir()) {
                world.spawnParticle(Particle.BLOCK_CRUMBLE, contact, Vfx.n(ctx(), 20), 0.35, 0.5, 0.35, 0.1,
                        block.getBlockData());
            }
            crackRing(contact);
            Vfx.burst(ctx(), contact, PALETTE, 0.8, Particle.CRIT, false);
            pop(owner, contact.clone().subtract(direction.clone().multiply(0.4)).add(0, 0.5, 0));
        }

        /** Cracks spreading over the wall face: two rings in the wall's plane. */
        private void crackRing(Location contact) {
            Vector up = new Vector(0, 1, 0);
            Vector side = new Vector(-direction.getZ(), 0, direction.getX());
            for (int ring = 0; ring < 2; ring++) {
                double radius = 0.45 + ring * 0.45;
                int points = Vfx.n(ctx(), 10 + ring * 6);
                for (int i = 0; i < points; i++) {
                    double angle = i * TAU / points + ring * 0.3;
                    Location at = contact.clone().add(side.clone().multiply(Math.cos(angle) * radius))
                            .add(up.clone().multiply(Math.sin(angle) * radius));
                    Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, ring == 0 ? PALETTE.glow(1.0f) : PALETTE.fade(1.1f),
                            false);
                }
            }
        }

        /** A "쾅!" popping off the wall. */
        private void pop(Player owner, Location at) {
            if (!fits(1)) {
                return;
            }
            TextDisplay label = Displays.text(ctx(), owner, at,
                    Text.mm("<b><gradient:#FFFFFF:#D9BE8C>쾅!</gradient></b>"), 0.6f, 18);
            displays.add(label);
            Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0.35f, 0, 1.5f, 1.5f, 1.5f, 0, 0, 0),
                    4);
            ctx().runLater(owner, 12, () -> {
                if (label.isValid()) {
                    Displays.shrinkAway(ctx(), owner, label, 5);
                }
            });
        }
    }

    // ------------------------------------------------------------------ ultimate: landslide

    /**
     * Self-contained (owner-scoped tasks, no hooks or passive state), so a Gambler can borrow it. The slide is fixed
     * where the goat stood when it blew the horn.
     */
    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location origin = owner.getLocation();
        Vector direction = facing(owner);
        double length = Spots.arenaReach(ctx(), origin, direction, Math.max(2, params().num("slide_length")));
        if (length < 2) {
            return ActivationResult.fail("자기장 밖으로는 산사태를 일으킬 수 없습니다");
        }
        new Landslide(origin, direction, length).start();
        return ActivationResult.success();
    }

    private final class Landslide implements LongPredicate {

        private final Location origin;
        private final Vector direction;
        private final Vector side;
        private final double length;
        private final double width;
        private final long delay;
        private final int waves;
        private final double damage;
        private final List<Boulder> boulders = new ArrayList<>();
        private final List<Set<UUID>> swept = new ArrayList<>();
        private @Nullable ItemDisplay horn;

        Landslide(Location origin, Vector direction, double length) {
            this.origin = origin.clone();
            this.direction = direction;
            this.side = new Vector(-direction.getZ(), 0, direction.getX());
            this.length = length;
            this.width = Math.max(1, params().num("slide_width"));
            this.delay = Math.max(DROP_TICKS, params().ticks("slide_delay"));
            this.waves = Math.max(1, params().integer("slide_waves"));
            this.damage = Math.max(0, params().num("boulder_damage"));
            for (int i = 0; i < waves; i++) {
                swept.add(new HashSet<>());
            }
        }

        void start() {
            Player owner = player();
            Location head = owner.getEyeLocation();
            Sound[] calls = {Sound.ITEM_GOAT_HORN_SOUND_0, Sound.ITEM_GOAT_HORN_SOUND_1, Sound.ITEM_GOAT_HORN_SOUND_2,
                    Sound.ITEM_GOAT_HORN_SOUND_3};
            Fx.sound(head, calls[ThreadLocalRandom.current().nextInt(calls.length)], 2.0f, 1.0f);
            Fx.sound(head, Sound.ENTITY_GOAT_SCREAMING_AMBIENT, 1.0f, 0.9f + Fx.jitter(0.05f));
            raiseHorn(owner);
            long total = delay + (waves - 1) * WAVE_INTERVAL + (long) Math.ceil(length / ROLL_SPEED) + 20;
            Loop.repeat(ctx(), owner, 0, 1, total, this, this::finish);
        }

        /** The goat lifts its horn to blow it; sound rings spread from the bell. */
        private void raiseHorn(Player owner) {
            if (!fits(1)) {
                return;
            }
            Location at = hornAnchor(owner);
            ItemDisplay display = Displays.item(ctx(), owner, at, new ItemStack(Material.GOAT_HORN),
                    Displays.centered(0, -0.4f, 0.3f, 0.1f, 0.1f, 0.1f, 90, 0, 0), HORN_HOLD_TICKS + 10);
            displays.add(display);
            horn = display;
            Displays.animateNextTick(ctx(), owner, display,
                    Displays.centered(0, 0.05f, 0.55f, 0.8f, 0.8f, 0.8f, 90, 0, -35), 5);
            ctx().runLater(owner, HORN_HOLD_TICKS, () -> {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 5);
                }
            });
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            if (horn != null && horn.isValid() && tick < HORN_HOLD_TICKS) {
                horn.teleport(hornAnchor(owner));
                if (tick % 4 == 0) {
                    Location bell = owner.getEyeLocation().add(Motion.horizontalFacing(owner.getLocation())
                            .multiply(0.9 + tick * 0.08));
                    Vfx.ring(ctx(), bell, 0.3 + tick * 0.05, 10, tick * 0.2, Particle.DUST_COLOR_TRANSITION,
                            PALETTE.fade(0.8f), true);
                }
            }
            if (tick < delay) {
                telegraph(tick);
            }
            for (int wave = 0; wave < waves; wave++) {
                if (tick == delay - DROP_TICKS + wave * WAVE_INTERVAL) {
                    launchWave(owner, wave);
                }
            }
            boolean rolling = false;
            for (Boulder boulder : boulders) {
                if (boulder.alive) {
                    boulder.step();
                    rolling = true;
                }
            }
            if (rolling && tick % RUMBLE_PERIOD == 0) {
                Location lead = leadPoint();
                Fx.sound(lead, Sound.BLOCK_GRAVEL_BREAK, 1.2f, 0.5f + Fx.jitter(0.05f));
                Fx.sound(lead, Sound.BLOCK_BASALT_BREAK, 1.0f, 0.6f + Fx.jitter(0.05f));
            }
            return tick < delay || rolling || tick < delay + (waves - 1) * WAVE_INTERVAL + 1;
        }

        /** The slope outlined on the ground, rumbling and shedding dust until the rocks come. */
        private void telegraph(long tick) {
            Location base = origin.clone().add(0, 0.12, 0);
            if (tick % 3 == 0) {
                Location leftNear = base.clone().add(side.clone().multiply(width / 2));
                Location rightNear = base.clone().subtract(side.clone().multiply(width / 2));
                Vector reach = direction.clone().multiply(length);
                Vfx.line(ctx(), leftNear, leftNear.clone().add(reach), 1.2, Particle.DUST_COLOR_TRANSITION,
                        ROCK.fade(1.2f), true);
                Vfx.line(ctx(), rightNear, rightNear.clone().add(reach), 1.2, Particle.DUST_COLOR_TRANSITION,
                        ROCK.fade(1.2f), true);
                Vfx.line(ctx(), leftNear.clone().add(reach), rightNear.clone().add(reach), 1.5, Particle.DUST,
                        ARROW_DUST, true);
                // A front of dust rolling down the slope ahead of the rocks.
                double progress = (double) tick / Math.max(1, delay);
                Location front = base.clone().add(direction.clone().multiply(length * progress));
                Vfx.line(ctx(), front.clone().add(side.clone().multiply(width / 2)),
                        front.clone().subtract(side.clone().multiply(width / 2)), 1.5, Particle.DUST_COLOR_TRANSITION,
                        ROCK.glow(1.0f), true);
            }
            if (tick % 2 == 0) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                    Location at = base.clone().add(direction.clone().multiply(random.nextDouble(length)))
                            .add(side.clone().multiply(random.nextDouble(-width / 2, width / 2)));
                    BlockData ground = groundUnder(at);
                    at.getWorld().spawnParticle(Particle.BLOCK_CRUMBLE, at, 2, 0.2, 0.02, 0.2, 0.02, ground, false);
                }
            }
            if (tick % RUMBLE_PERIOD == 0) {
                Fx.sound(origin, Sound.ENTITY_WARDEN_DIG, 0.9f, 0.7f + Fx.jitter(0.05f));
            }
        }

        private void launchWave(Player owner, int wave) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location behind = origin.clone().subtract(direction.clone().multiply(DROP_BEHIND)).add(0, DROP_HEIGHT, 0);
            for (int i = 0; i < BOULDERS_PER_WAVE; i++) {
                double lane = -width / 2 + width * (i + 0.5) / BOULDERS_PER_WAVE
                        + random.nextDouble(-0.35, 0.35);
                double size = random.nextDouble(BOULDER_MIN, BOULDER_MAX);
                Location from = behind.clone().add(side.clone().multiply(lane * 0.6))
                        .add(0, random.nextDouble(-0.4, 0.6), 0);
                Location land = origin.clone().add(direction.clone().multiply(DROP_LAND + random.nextDouble(0.8)))
                        .add(side.clone().multiply(lane));
                Boulder boulder = new Boulder(owner, wave, from, land, size,
                        ROLL_SPEED + random.nextDouble(0.0, 0.12), random.nextLong(0, 3));
                boulders.add(boulder);
            }
            Fx.sound(behind, Sound.BLOCK_STONE_BREAK, 1.5f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(behind, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 0.8f, 0.6f + Fx.jitter(0.05f));
        }

        private Location leadPoint() {
            Location lead = origin.clone();
            double best = -1;
            for (Boulder boulder : boulders) {
                if (boulder.alive && boulder.traveled > best) {
                    best = boulder.traveled;
                    lead = boulder.position.clone();
                }
            }
            return lead;
        }

        private void finish() {
            for (Boulder boulder : boulders) {
                if (boulder.alive) {
                    boulder.crumble();
                }
            }
            boulders.clear();
        }

        /** One boulder: drops over the goat's head, then rolls down the band, following the ground. */
        private final class Boulder {

            private final Player owner;
            private final int wave;
            private final Location from;
            private final Location land;
            private final double size;
            private final double speed;
            private final long hold;
            private final Material material;
            private Location position;
            private @Nullable BlockDisplay display;
            private long age;
            private double traveled;
            private double rolled;
            private boolean alive = true;

            Boulder(Player owner, int wave, Location from, Location land, double size, double speed, long hold) {
                this.owner = owner;
                this.wave = wave;
                this.from = from;
                this.size = size;
                this.speed = speed;
                this.hold = hold;
                this.material = BOULDERS[ThreadLocalRandom.current().nextInt(BOULDERS.length)];
                Double ground = groundY(land, 2.0);
                this.land = land.clone();
                this.land.setY((ground != null ? ground : land.getY()) + size / 2);
                this.position = from.clone();
                if (fits(1)) {
                    Location at = position.clone();
                    at.setYaw(0);
                    at.setPitch(0);
                    display = Displays.block(ctx(), owner, at, material.createBlockData(),
                            rock(0.1f, new Quaternionf()), DROP_TICKS + (long) Math.ceil(length / speed) + 40);
                    display.setTeleportDuration(2);
                    display.setViewRange(2.0f);
                    displays.add(display);
                    Displays.animateNextTick(ctx(), owner, display, rock((float) size, new Quaternionf()), 4);
                }
                Vfx.cloud(ctx(), from, Particle.BLOCK_CRUMBLE, 6, 0.4, 0.05, material.createBlockData(), true);
            }

            void step() {
                age++;
                if (age <= hold) {
                    return;
                }
                long t = age - hold;
                if (t <= DROP_TICKS) {
                    drop(t);
                    return;
                }
                roll();
            }

            /** The fall from the hill: an arc over the goat's head down onto the slope. */
            private void drop(long t) {
                double p = (double) t / DROP_TICKS;
                Location at = from.clone().add(land.clone().subtract(from).toVector().multiply(p));
                at.add(0, Math.sin(p * Math.PI) * 1.2, 0);
                moveTo(at, p * 0.6);
                if (t == DROP_TICKS) {
                    BlockData ground = groundUnder(land);
                    land.getWorld().spawnParticle(Particle.BLOCK, land, Vfx.n(ctx(), 10), size * 0.4, 0.1, size * 0.4,
                            0.12, ground, true);
                    Vfx.dot(land.clone().subtract(0, size / 2, 0), Particle.DUST_PLUME, null, true);
                    Fx.sound(land, Sound.BLOCK_STONE_PLACE, 1.0f, 0.5f + Fx.jitter(0.1f));
                    sweep();
                }
            }

            private void roll() {
                if (traveled >= length - DROP_LAND) {
                    crumble();
                    return;
                }
                Location next = position.clone().add(direction.clone().multiply(speed));
                Double ground = groundY(next, MAX_CLIMB + size);
                double groundNow = position.getY() - size / 2;
                if (ground == null || !ctx().isInsideArena(next)) {
                    crumble();
                    return;
                }
                if (ground > groundNow + MAX_CLIMB || blockedAt(next)) {
                    crumble();
                    return;
                }
                double targetY = ground + size / 2;
                double y = targetY < position.getY() ? Math.max(targetY, position.getY() - MAX_DROP_PER_TICK) : targetY;
                double bounce = Math.abs(Math.sin(traveled * 1.4)) * 0.12;
                next.setY(y + bounce);
                traveled += speed;
                moveTo(next, speed / (size / 2));
                if (age % 2 == 0) {
                    BlockData dirt = groundUnder(next.clone().subtract(0, size / 2, 0));
                    next.getWorld().spawnParticle(Particle.BLOCK, next.clone().subtract(0, size / 2 - 0.1, 0),
                            Vfx.n(ctx(), 2), size * 0.3, 0.05, size * 0.3, 0.05, dirt, false);
                    Vfx.dot(next.clone().subtract(direction.clone().multiply(size * 0.6)).subtract(0, size * 0.3, 0),
                            Particle.DUST_COLOR_TRANSITION, ROCK.fade(1.4f), false);
                }
                if (age % 5 == 0) {
                    Vfx.dot(next.clone().subtract(direction.clone().multiply(size * 0.5)), Particle.CAMPFIRE_COSY_SMOKE,
                            null, false);
                }
                sweep();
            }

            private void moveTo(Location at, double turn) {
                position = at;
                rolled += turn;
                if (display != null && display.isValid()) {
                    Location to = at.clone();
                    to.setYaw(0);
                    to.setPitch(0);
                    display.teleport(to);
                    if (age % 2 == 0) {
                        Vector3f axis = new Vector3f((float) side.getX(), 0, (float) side.getZ());
                        Quaternionf spin = new Quaternionf().rotateAxis((float) -rolled, axis.normalize())
                                .rotateY(wave * 0.7f);
                        Displays.animate(display, rock((float) size, spin), 2);
                    }
                }
            }

            /** Everyone the boulder rolls into: the first touch of each wave hurts, the rest just carries them. */
            private void sweep() {
                Set<UUID> hit = swept.get(wave);
                for (LivingEntity enemy : ctx().enemiesNear(owner, position, size / 2 + BOULDER_REACH)) {
                    if (hit.add(enemy.getUniqueId())) {
                        Combat.hit(ctx(), owner, enemy, damage);
                        if (Combat.isTargetable(ctx(), owner, enemy)) {
                            Motion.push(enemy, direction.clone().multiply(SWEEP_PUSH).setY(SWEEP_LIFT), MAX_SPEED);
                            enemy.setFallDistance(0);
                        }
                        Location body = Fx.body(enemy);
                        if (visible(owner, enemy)) {
                            Vfx.burst(ctx(), body, ROCK, 0.6, Particle.CRIT, false);
                            body.getWorld().spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 8), 0.3, 0.3, 0.3, 0.1,
                                    material.createBlockData());
                        }
                        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.6f + Fx.jitter(0.1f));
                        Fx.sound(body, Sound.BLOCK_STONE_HIT, 1.0f, 0.6f + Fx.jitter(0.1f));
                    } else if (Combat.isTargetable(ctx(), owner, enemy)) {
                        Vector carry = direction.clone().multiply(CARRY_PUSH);
                        carry.setY(Math.max(enemy.getVelocity().getY(), 0.1));
                        Motion.push(enemy, carry, MAX_SPEED);
                        enemy.setFallDistance(0);
                    }
                }
            }

            /** The boulder breaks apart: crumbs, dust and a crack. */
            void crumble() {
                alive = false;
                BlockData data = material.createBlockData();
                position.getWorld().spawnParticle(Particle.BLOCK_CRUMBLE, position, Vfx.n(ctx(), 14), size * 0.35,
                        size * 0.35, size * 0.35, 0.1, data);
                Vfx.cloud(ctx(), position, Particle.POOF, 4, size * 0.3, 0.03, null, false);
                Fx.sound(position, Sound.BLOCK_STONE_BREAK, 1.0f, 0.7f + Fx.jitter(0.1f));
                if (display != null && display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 4);
                }
                display = null;
            }

            private boolean blockedAt(Location center) {
                double half = size * 0.35;
                BoundingBox box = new BoundingBox(center.getX() - half, center.getY() - size / 2 + 0.6,
                        center.getZ() - half, center.getX() + half, center.getY() + half, center.getZ() + half);
                return center.getWorld().hasCollisionsIn(box);
            }
        }
    }

    /** The top of the first solid block at most {@code depth} below a point a little above {@code at}. */
    private static @Nullable Double groundY(Location at, double depth) {
        Location start = at.clone().add(0, Math.min(1.2, depth), 0);
        RayTraceResult ground = at.getWorld().rayTraceBlocks(start, new Vector(0, -1, 0), depth + Math.min(1.2, depth)
                + 1.5, FluidCollisionMode.NEVER, true);
        return ground == null ? null : ground.getHitPosition().getY();
    }

    /** A cube of edge {@code size} centered on the display, turned by {@code rotation}. */
    private static Transformation rock(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    // ------------------------------------------------------------------ helpers

    /** False for players hidden from the owner (stealth): no effect may give their position away. */
    private static boolean visible(Player owner, LivingEntity entity) {
        return !(entity instanceof Player hidden) || owner.canSee(hidden);
    }

    private static Vector facing(Player owner) {
        Vector facing = Motion.horizontalFacing(owner.getLocation());
        return facing.lengthSquared() < 1.0E-6 ? new Vector(0, 0, 1) : facing;
    }

    /** Forgets removed displays and says whether {@code count} more fit in the display budget. */
    private boolean fits(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= DISPLAY_BUDGET;
    }

    /** The solid block right under {@code at}, dirt over air, fluids and plants. */
    private static BlockData groundUnder(Location at) {
        BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        return ground.getMaterial().isSolid() ? ground : FALLBACK_GROUND.createBlockData();
    }

    /** The Minecraft yaw (degrees) of a horizontal direction. */
    private static float yawOf(Vector direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }
}
