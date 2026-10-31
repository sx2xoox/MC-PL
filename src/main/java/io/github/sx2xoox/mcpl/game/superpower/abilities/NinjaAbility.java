package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Stealth;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 닌자 — agile skirmisher. Runs up walls while crouching against them and lands softly, throws a fan of spinning
 * shuriken that make their targets bleed, and as the ultimate bursts a smoke bomb: inside the cloud the ninja is
 * invisible to enemies and fast, and the first strike out of the smoke is a critical hit.
 *
 * <p>Look: black and blood red. Shuriken are steel eight-pointed stars (two crossed plates and a red core) that spin
 * as they fly, trailing red; they bite into walls and stay there a moment. Bleeding enemies drip red. The smoke bomb
 * pops in a red flash under a rising crimson 忍; the cloud is layered campfire smoke, black puffs and dark dust with
 * a smouldering red rim on the ground, and the ambush cuts a red X with 斬 over the victim.
 */
public final class NinjaAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("ninja", "닌자", Material.FLINT)
            .color(TextColor.color(0xD7263D))
            .difficulty(3)
            .passive("인술: 벽 타기",
                    "벽에 붙어 웅크리면 초당 {climb_speed}블록 속도로 벽을 타고 오르고, 벽 끝에 닿으면 가볍게 넘어갑니다. "
                            + "낙하 피해가 {fall_reduction}% 감소합니다.")
            .active("수리검",
                    "수리검 {shuriken_count}개를 부채꼴로 던집니다({shuriken_range}블록). 맞은 적은 {shuriken_damage} 피해를 "
                            + "입고 {bleed_duration}초간 {bleed_interval}초마다 {bleed_damage} 출혈 피해를 입습니다. 한 적이 여러 개를 맞으면 "
                            + "두 번째부터는 {shuriken_overlap}% 피해입니다.",
                    7)
            .ultimate("연막 습격",
                    "발밑에 연막탄을 터뜨려 {smoke_duration}초간 반경 {smoke_radius}블록의 짙은 연막을 만듭니다. 연막 안에서는 "
                            + "적에게 보이지 않고 신속 II를 얻습니다. 연막 속에서의 첫 근접 공격은 피해가 {ult_crit}% 증가하는 "
                            + "치명타이며 출혈을 일으킵니다. 공격하거나 공격받으면 {ult_reveal}초간 모습이 드러납니다.")
            .ultimateNotStandalone()
            .param("climb_speed", 5)
            .param("fall_reduction", 50)
            .param("shuriken_count", 3)
            .param("shuriken_damage", 3)
            .param("shuriken_overlap", 50)
            .param("shuriken_range", 16)
            .param("bleed_duration", 2)
            .param("bleed_damage", 0.5)
            .param("bleed_interval", 0.5)
            .param("smoke_radius", 5)
            .param("smoke_duration", 6)
            .param("ult_crit", 100)
            .param("ult_reveal", 1)
            .build();

    private static final String COLOR_TAG = "#D7263D";
    private static final double TAU = Math.PI * 2;

    // Passive: wall climbing
    /** How far around the body a wall still counts as touched. */
    private static final double WALL_REACH = 0.12;
    /** The hop over the top edge of a climbed wall. */
    private static final double VAULT_UP = 0.42;
    private static final double VAULT_FORWARD = 0.28;
    private static final long CLIMB_FX_PERIOD = 3;
    private static final double MAX_CLIMB = 1.0;

    // Shuriken
    private static final double FAN_DEGREES = 24;
    private static final double SHURIKEN_SPEED = 1.6;
    private static final double SHURIKEN_REACH = 0.5;
    private static final double SAMPLE_STEP = 0.4;
    private static final float SPIN_PER_TICK = 50f;
    private static final long STUCK_TICKS = 14;
    private static final float STAR_SIZE = 0.42f;
    private static final float STAR_THICKNESS = 0.03f;
    private static final long BLEED_FX_PERIOD = 2;

    // Smoke ambush
    private static final double SMOKE_HEIGHT = 3.2;
    private static final int SPEED_AMPLIFIER = 1;
    private static final long SPEED_REFRESH = 10;
    private static final double AGGRO_DROP_RANGE = 24;
    private static final double GROUND_SNAP = 6;
    /** Ticks the smoke bomb takes from the hand to the ground. */
    private static final long BOMB_TICKS = 3;

    // Visuals
    /** Pale blush core, blood red body, near-black edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFE1E1, 0xD7263D, 0x1A0B0E);
    private static final Particle.DustOptions SMOKE_DUST = new Particle.DustOptions(Color.fromRGB(0x2B2B30), 3.2f);
    private static final Particle.DustOptions ASH_DUST = new Particle.DustOptions(Color.fromRGB(0x45434A), 2.0f);
    private static final Material STEEL = Material.IRON_BLOCK;
    private static final Material RED_CORE = Material.RED_CONCRETE;
    private static final Material BLOOD = Material.REDSTONE_BLOCK;

    private final Stealth stealth = new Stealth();
    private final Map<UUID, Bleed> bleeding = new HashMap<>();
    private boolean bleedLoop;
    private boolean climbing;
    private long climbTicks;
    private @Nullable Smoke smoke;
    /** The victim of a smoke ambush whose damage was raised, waiting for the hit to land. */
    private @Nullable LivingEntity ambushVictim;
    /** The target of the star hit being dealt right now, and whether it landed (see {@link #onDamageDealt}). */
    private @Nullable LivingEntity pendingTarget;
    private boolean landed;

    /** One bleeding enemy: the drops left to deal and the ticks until the next one. */
    private static final class Bleed {
        private final LivingEntity target;
        private long hitsLeft;
        private long untilNext;

        Bleed(LivingEntity target, long hits, long interval) {
            this.target = target;
            this.hitsLeft = hits;
            this.untilNext = interval;
        }
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        double reduction = Math.clamp(params().num("fall_reduction") / 100.0, 0.0, 1.0);
        if (reduction > 0) {
            ctx().addModifier(player(), player(), Attribute.FALL_DAMAGE_MULTIPLIER, -reduction,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1, 0);
        }
    }

    @Override
    public void onRevoke() {
        hideEnd(player());
        smoke = null;
        bleeding.clear();
        climbing = false;
        ambushVictim = null;
    }

    // ------------------------------------------------------------------ passive: wall climbing, soft landing

    @Override
    public void onTick(long tick) {
        Player owner = player();
        boolean canClimb = owner.isSneaking() && !owner.isFlying() && !owner.isInsideVehicle()
                && !owner.isGliding();
        if (canClimb && touchingWall(owner)) {
            double speed = Math.max(0, params().num("climb_speed")) / 20.0;
            if (speed <= 0) {
                return;
            }
            // Straight up the wall. Not the owner's velocity slowed down: on the server a player's velocity is only the
            // last one sent to it (its client moves it), so that would hand back a stale push, sideways.
            owner.setVelocity(Motion.clamp(new Vector(0, speed, 0), MAX_CLIMB));
            owner.setFallDistance(0);
            if (!climbing) {
                climbing = true;
                climbTicks = 0;
                Fx.sound(owner.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 1.6f + Fx.jitter(0.1f));
            }
            if (climbTicks++ % CLIMB_FX_PERIOD == 0) {
                climbVisual(owner);
            }
            return;
        }
        if (climbing) {
            climbing = false;
            if (canClimb && !Motion.isGrounded(owner)) {
                vault(owner);
            }
        }
    }

    /** True if a wall touches the body anywhere between the knees and the head (the floor and ceiling never count). */
    private static boolean touchingWall(Player owner) {
        BoundingBox box = owner.getBoundingBox();
        BoundingBox probe = new BoundingBox(box.getMinX() - WALL_REACH, box.getMinY() + 0.3, box.getMinZ() - WALL_REACH,
                box.getMaxX() + WALL_REACH, box.getMaxY() - 0.25, box.getMaxZ() + WALL_REACH);
        return owner.getWorld().hasCollisionsIn(probe);
    }

    /** Over the top: a light hop forward with a flick of red, the fall after it stays soft. */
    private void vault(Player owner) {
        Vector forward = Motion.horizontalFacing(owner.getLocation()).multiply(VAULT_FORWARD);
        owner.setVelocity(forward.setY(VAULT_UP));
        owner.setFallDistance(0);
        Location feet = owner.getLocation();
        Vfx.arc(ctx(), feet.clone().add(0, 0.2, 0), 0.6, feet.getYaw() + 180, 160, 9, Particle.DUST_COLOR_TRANSITION,
                PALETTE.fade(0.9f), false);
        Vfx.cloud(ctx(), feet, Particle.SMOKE, 5, 0.2, 0.02, null, false);
        Fx.sound(feet, Sound.ENTITY_PHANTOM_FLAP, 0.6f, 1.6f + Fx.jitter(0.1f));
    }

    /** Grit from the wall under the hands and a faint red smear left behind. */
    private void climbVisual(Player owner) {
        Location body = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        Block wall = wallBlock(owner);
        World world = body.getWorld();
        if (wall != null && wall.getType().isSolid()) {
            Location contact = body.clone().add(wall.getLocation().add(0.5, 0.5, 0.5).toVector()
                    .subtract(body.toVector()).setY(0).normalize().multiply(0.35));
            world.spawnParticle(Particle.BLOCK, contact, Vfx.n(ctx(), 3), 0.1, 0.15, 0.1, 0.05, wall.getBlockData());
        }
        Vfx.dot(owner.getLocation().add(0, 0.2, 0), Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.7f), false);
        if (climbTicks % (CLIMB_FX_PERIOD * 3) == 0) {
            Fx.sound(body, Sound.BLOCK_LADDER_STEP, 0.4f, 1.3f + Fx.jitter(0.1f));
        }
    }

    private static @Nullable Block wallBlock(Player owner) {
        Block feet = owner.getLocation().add(0, 0.9, 0).getBlock();
        for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block side = feet.getRelative(face);
            if (side.getType().isSolid()) {
                return side;
            }
        }
        return null;
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (Combat.isFall(event)) {
            // The fall damage multiplier already softened it; this is the roll that goes with it.
            if (params().num("fall_reduction") > 0) {
                softLanding(player());
            }
            return;
        }
        Smoke current = smoke;
        if (current != null && stealth.isActive() && event.getDamage() > 0
                && event instanceof EntityDamageByEntityEvent) {
            current.expose(Reveal.HIT);
        }
    }

    /** A rolled landing: a low ring of dust and a soft thud. */
    private void softLanding(Player owner) {
        Location feet = owner.getLocation().add(0, 0.1, 0);
        Vfx.ring(ctx(), feet, 0.7, 12, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_COLOR_TRANSITION,
                PALETTE.fade(0.8f), false);
        Vfx.cloud(ctx(), feet, Particle.POOF, 4, 0.25, 0.02, null, false);
        Fx.sound(feet, Sound.BLOCK_WOOL_FALL, 0.8f, 0.8f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ active: shuriken

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        if (!(look.lengthSquared() > 1.0E-6)) {
            return ActivationResult.fail("던질 방향이 없습니다");
        }
        look.normalize();
        // Fan out around the owner's own "up" axis so the spread stays sideways when aiming up or down.
        Vector right = look.getCrossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            double yaw = Math.toRadians(eye.getYaw());
            right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        }
        Vector up = right.getCrossProduct(look).normalize();

        int count = Math.max(1, params().integer("shuriken_count"));
        double fan = Math.toRadians(FAN_DEGREES);
        double range = Math.max(1, params().num("shuriken_range"));
        Volley volley = new Volley();
        // Thrown from inside the body like a real throw, so a hugging enemy is not skipped.
        Location origin = eye.clone().subtract(0, 0.25, 0);
        for (int i = 0; i < count; i++) {
            double angle = count == 1 ? 0 : -fan / 2 + fan * i / (count - 1);
            Vector direction = look.clone().rotateAroundAxis(up, angle).normalize();
            Shuriken shuriken = new Shuriken(origin, direction, range, volley, i);
            long maxTicks = (long) Math.ceil(range / SHURIKEN_SPEED) + STUCK_TICKS + 4;
            Loop.repeat(ctx(), owner, 1, 1, maxTicks, shuriken, shuriken::finish);
        }

        World world = eye.getWorld();
        Location hand = eye.clone().subtract(0, 0.3, 0).add(look.clone().multiply(0.7));
        world.spawnParticle(Particle.FLASH, hand, 1, 0, 0, 0, 0, PALETTE.body());
        Vfx.arc(ctx(), eye.clone().subtract(0, 0.4, 0), 1.0, eye.getYaw(), FAN_DEGREES + 30, 9,
                Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.8f), false);
        world.spawnParticle(Particle.SWEEP_ATTACK, hand, 1, 0, 0, 0, 0);
        Fx.sound(eye, Sound.ENTITY_WITCH_THROW, 0.8f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.8f + Fx.jitter(0.1f));
        Fx.sound(eye, Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.6f, 1.9f + Fx.jitter(0.05f));
        return ActivationResult.success();
    }

    /** One throw: who was already struck, so the second star on the same enemy deals less. */
    private static final class Volley {
        private final Map<UUID, Integer> struck = new HashMap<>();
    }

    /** A flying star: two crossed steel plates and a red core, lying flat and spinning. */
    private final class Shuriken implements LongPredicate {

        private final World world;
        private final Vector direction;
        private final double range;
        private final Volley volley;
        private final BlockDisplay[] parts = new BlockDisplay[3];
        private Location at;
        private double travelled;
        private float spin;
        private boolean done;

        Shuriken(Location origin, Vector direction, double range, Volley volley, int index) {
            this.world = origin.getWorld();
            this.direction = direction;
            this.range = range;
            this.volley = volley;
            this.at = origin.clone();
            this.spin = index * 15f;
            Location place = flat(at);
            long lifetime = (long) Math.ceil(range / SHURIKEN_SPEED) + STUCK_TICKS + 10;
            BlockData steel = STEEL.createBlockData();
            parts[0] = Displays.block(ctx(), player(), place, steel, plate(0.4f, spin), lifetime);
            parts[1] = Displays.block(ctx(), player(), place, steel, plate(0.4f, spin + 45), lifetime);
            parts[2] = Displays.block(ctx(), player(), place, RED_CORE.createBlockData(), core(0.4f, spin), lifetime);
            for (BlockDisplay part : parts) {
                part.setTeleportDuration(1);
            }
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            if (done || !owner.getWorld().equals(world)) {
                return false;
            }
            double step = Math.min(SHURIKEN_SPEED, range - travelled);
            RayTraceResult wall = world.rayTraceBlocks(at, direction, step, FluidCollisionMode.NEVER, true);
            double advance = wall == null ? step : Math.max(0, wall.getHitPosition().distance(at.toVector()) - 0.12);
            Location from = at.clone();
            for (double d = 0; d <= advance + 1.0E-6; d += SAMPLE_STEP) {
                Location sample = from.clone().add(direction.clone().multiply(Math.min(d, advance)));
                for (LivingEntity enemy : ctx().enemiesNear(owner, sample, SHURIKEN_REACH)) {
                    strike(owner, enemy, sample);
                    return false;
                }
            }
            at = from.clone().add(direction.clone().multiply(advance));
            travelled += advance;
            spin = (spin + SPIN_PER_TICK) % 360f;
            place(at, 1f);
            Vfx.line(ctx(), from, at, 3, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.6f), false);
            Vfx.dot(at, Particle.CRIT, null, false);
            if (wall != null) {
                stick(wall);
                return false;
            }
            return travelled < range - 1.0E-6;
        }

        private void strike(Player owner, LivingEntity enemy, Location where) {
            done = true;
            int previous = volley.struck.merge(enemy.getUniqueId(), 1, Integer::sum) - 1;
            double damage = params().num("shuriken_damage");
            if (previous > 0) {
                damage *= Math.max(0, params().num("shuriken_overlap")) / 100.0;
            }
            // A star that was dodged, deflected or blocked opens no wound.
            if (land(enemy, damage) && Combat.isTargetable(ctx(), owner, enemy)) {
                bleed(enemy);
            }
            Location body = Fx.body(enemy);
            Vfx.burst(ctx(), body, PALETTE, 0.4, Particle.CRIT, false);
            world.spawnParticle(Particle.FALLING_DUST, body, Vfx.n(ctx(), 6), 0.2, 0.25, 0.2, 0,
                    BLOOD.createBlockData());
            Fx.sound(body, Sound.ENTITY_ARROW_HIT_PLAYER, 0.5f, 1.6f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.6f, 1.5f + Fx.jitter(0.1f));
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    part.remove();
                }
            }
            Vfx.dot(where, Particle.FLASH, PALETTE.core(), false);
        }

        /** The star bites into the wall, tilted, and stays there a moment. */
        private void stick(RayTraceResult wall) {
            done = true;
            Location hit = wall.getHitPosition().toLocation(world);
            if (wall.getHitBlock() != null) {
                world.spawnParticle(Particle.BLOCK, hit, Vfx.n(ctx(), 6), 0.08, 0.08, 0.08, 0.05,
                        wall.getHitBlock().getBlockData());
            }
            world.spawnParticle(Particle.ELECTRIC_SPARK, hit, Vfx.n(ctx(), 4), 0.05, 0.05, 0.05, 0.05);
            Fx.sound(hit, Sound.ITEM_TRIDENT_HIT_GROUND, 0.6f, 1.8f + Fx.jitter(0.1f));
            ctx().runLater(player(), STUCK_TICKS, () -> {
                for (BlockDisplay part : parts) {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), player(), part, 4);
                    }
                }
            });
        }

        private void place(Location where, float scale) {
            Location flat = flat(where);
            Transformation[] poses = {plate(scale, spin), plate(scale, spin + 45), core(scale, spin)};
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isValid()) {
                    parts[i].teleport(flat);
                    Displays.animate(parts[i], poses[i], 1);
                }
            }
        }

        void finish() {
            if (done) {
                return;
            }
            done = true;
            // Out of range: the star drops its spin and fades.
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), player(), part, 4);
                }
            }
            Vfx.cloud(ctx(), at, Particle.SMOKE, 3, 0.1, 0.01, null, false);
        }
    }

    /** Deals a star's damage and tells whether it landed (nothing cancelled it). */
    private boolean land(LivingEntity target, double damage) {
        if (!(damage > 0)) {
            return Combat.isTargetable(ctx(), player(), target);
        }
        pendingTarget = target;
        landed = false;
        try {
            Combat.hit(ctx(), player(), target, damage);
        } finally {
            pendingTarget = null;
        }
        return landed;
    }

    /** One plate of the star: a thin square lying flat, turned {@code yaw} degrees. */
    private static Transformation plate(float scale, float yaw) {
        float size = STAR_SIZE * scale;
        return Displays.transform(0, 0, 0, size, STAR_THICKNESS * scale, size, yaw, 0, 0);
    }

    private static Transformation core(float scale, float yaw) {
        float size = STAR_SIZE * 0.3f * scale;
        return Displays.transform(0, 0, 0, size, STAR_THICKNESS * 1.6f * scale, size, yaw + 22.5f, 0, 0);
    }

    // ------------------------------------------------------------------ bleeding

    /**
     * Starts (or refreshes) the bleeding of {@code enemy}: one drop of damage every half second for the bleed
     * duration, and red drips. A refresh restarts the count without delaying the next drop.
     */
    private void bleed(LivingEntity enemy) {
        long interval = bleedInterval();
        long hits = params().ticks("bleed_duration") / interval;
        if (hits <= 0 || params().num("bleed_damage") <= 0) {
            return;
        }
        Bleed existing = bleeding.get(enemy.getUniqueId());
        if (existing != null && existing.target.equals(enemy)) {
            existing.hitsLeft = hits;
        } else {
            bleeding.put(enemy.getUniqueId(), new Bleed(enemy, hits, interval));
        }
        if (bleedLoop) {
            return;
        }
        bleedLoop = true;
        Loop.repeat(ctx(), player(), BLEED_FX_PERIOD, BLEED_FX_PERIOD, Long.MAX_VALUE, this::bleedStep,
                () -> bleedLoop = false);
    }

    /** Ticks between two bleed drops, at least the drip period. */
    private long bleedInterval() {
        return Math.max(BLEED_FX_PERIOD, params().ticks("bleed_interval"));
    }

    private boolean bleedStep(long run) {
        Player owner = player();
        double damage = params().num("bleed_damage");
        for (Iterator<Bleed> it = bleeding.values().iterator(); it.hasNext(); ) {
            Bleed bleed = it.next();
            LivingEntity target = bleed.target;
            if (bleed.hitsLeft <= 0 || !Combat.isTargetable(ctx(), owner, target)) {
                it.remove();
                continue;
            }
            Location body = Fx.body(target);
            World world = body.getWorld();
            world.spawnParticle(Particle.FALLING_DUST, body, Vfx.n(ctx(), 2), 0.2, 0.3, 0.2, 0,
                    BLOOD.createBlockData());
            Vfx.dot(body.clone().add(0, -0.2, 0), Particle.DUST, PALETTE.dust(0.7f), false);
            bleed.untilNext -= BLEED_FX_PERIOD;
            if (bleed.untilNext <= 0) {
                bleed.untilNext += bleedInterval();
                bleed.hitsLeft--;
                Combat.hit(ctx(), owner, target, damage);
                world.spawnParticle(Particle.DAMAGE_INDICATOR, body, Vfx.n(ctx(), 2), 0.15, 0.2, 0.15, 0.05);
            }
        }
        return !bleeding.isEmpty();
    }

    // ------------------------------------------------------------------ ultimate: smoke ambush

    private enum Reveal { ATTACK, HIT }

    @Override
    public ActivationResult onUltimate() {
        if (smoke != null) {
            return ActivationResult.fail("이미 연막 속입니다");
        }
        Smoke created = new Smoke(player().getLocation());
        smoke = created;
        created.drop();
        Loop.repeat(ctx(), player(), 0, 1, created.duration, created, created::end);
        return ActivationResult.success();
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        Smoke current = smoke;
        if (event.isCancelled() || current == null || ctx().isAbilityDamage()
                || event.getCause() != DamageCause.ENTITY_ATTACK || !player().equals(event.getDamager())) {
            return;
        }
        ambushVictim = null;
        if (stealth.isActive() && current.critReady) {
            double bonus = Math.max(0, params().num("ult_crit")) / 100.0;
            event.setDamage(event.getDamage() * (1 + bonus));
            ambushVictim = victim;
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() && victim.equals(pendingTarget)) {
            landed = true;
        }
        Smoke current = smoke;
        if (current == null || ctx().isAbilityDamage() || event.getCause() != DamageCause.ENTITY_ATTACK
                || !player().equals(event.getDamager())) {
            return;
        }
        if (victim.equals(ambushVictim)) {
            ambushVictim = null;
            current.critReady = false;
            ambushVisual(victim);
            // The ambush cuts deep: the victim starts bleeding once the blow is through.
            ctx().runLater(player(), 1, () -> {
                if (Combat.isTargetable(ctx(), player(), victim)) {
                    bleed(victim);
                }
            });
        }
        if (stealth.isActive()) {
            current.expose(Reveal.ATTACK);
        }
    }

    /** One smoke bomb: the cloud, hiding the ninja while inside and the one ambush it grants. */
    private final class Smoke implements LongPredicate {

        private final Location center;
        private final double radius = Math.max(1, params().num("smoke_radius"));
        private final long duration = Math.max(1, params().ticks("smoke_duration"));
        private final long revealTicks = params().ticks("ult_reveal");
        private boolean critReady = true;
        private long exposedUntil = -1;

        Smoke(Location center) {
            // Thrown down from a jump, the bomb bursts on the ground below.
            this.center = center.clone();
            Spots.snapToGround(this.center.getWorld(), this.center, GROUND_SNAP);
        }

        /**
         * The bomb: a dark ball tossed down from the hand that hits the ground and pops a moment later. The smoke
         * already hides the ninja from the throw on; the pop is the show.
         */
        void drop() {
            Player owner = player();
            Location hand = owner.getEyeLocation().subtract(0, 0.5, 0)
                    .add(Motion.horizontalFacing(owner.getLocation()).multiply(0.4));
            hand.setYaw(0);
            hand.setPitch(0);
            ItemDisplay bomb = Displays.item(ctx(), owner, hand, ItemStack.of(Material.FIREWORK_STAR),
                    Displays.centered(0, 0, 0, 0.45f, 0.45f, 0.45f, 0, 0, 0), BOMB_TICKS + 2);
            bomb.setTeleportDuration((int) BOMB_TICKS);
            Location ground = center.clone().add(0, 0.25, 0);
            ground.setYaw(0);
            ground.setPitch(0);
            ctx().runLater(owner, 1, () -> {
                if (bomb.isValid()) {
                    bomb.teleport(ground);
                    Displays.animate(bomb, Displays.centered(0, 0, 0, 0.45f, 0.45f, 0.45f, 0, 0, 170),
                            (int) BOMB_TICKS);
                }
            });
            ctx().runLater(owner, BOMB_TICKS, () -> {
                if (bomb.isValid()) {
                    bomb.remove();
                }
                burst();
            });
            Vfx.dot(hand, Particle.SMOKE, null, false);
            Fx.sound(hand, Sound.ENTITY_SNOWBALL_THROW, 0.8f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(hand, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 0.8f + Fx.jitter(0.1f));
        }

        /** The bomb pops: a red flash, a black shockwave of smoke, a crimson 忍 rising out of it. */
        void burst() {
            Player owner = player();
            World world = center.getWorld();
            Location at = center.clone().add(0, 0.4, 0);
            world.spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, PALETTE.body(), true);
            world.spawnParticle(Particle.EXPLOSION, at, 1, 0, 0, 0, 0, null, true);
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.15, 0), 0.5, radius, 6, Particle.LARGE_SMOKE, null,
                    true);
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.1, 0), 0.4, radius, 7, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.fade(1.4f), true);
            Vfx.cloud(ctx(), at, Particle.CAMPFIRE_SIGNAL_SMOKE, 14, 0.8, 0.02, null, true);
            Vfx.cloud(ctx(), at, Particle.LARGE_SMOKE, 30, 1.2, 0.08, null, true);
            sigil(at.clone().add(0, 1.6, 0));
            Fx.sound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.2f, 0.6f);
            Fx.sound(center, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.ENTITY_BREEZE_WIND_BURST, 0.9f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8f, 0.7f);
            Stealth.dropAggro(ctx(), owner, AGGRO_DROP_RANGE);
        }

        @Override
        public boolean test(long run) {
            Player owner = player();
            drawCloud(run);
            boolean inside = contains(owner.getLocation());
            boolean exposed = ctx().currentTick() < exposedUntil;
            if (inside && !exposed && !stealth.isActive()) {
                hideBegin(owner, duration - run);
            } else if ((!inside || exposed) && stealth.isActive()) {
                hideEnd(owner);
                if (!inside) {
                    owner.sendActionBar(Text.mm("<gray>연막 밖으로 나왔습니다</gray>"));
                }
            }
            if (stealth.isActive()) {
                if (run % SPEED_REFRESH == 0) {
                    owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) SPEED_REFRESH + 5,
                            SPEED_AMPLIFIER, false, false, true));
                }
                if (run % 20 == 0) {
                    stealth.hideFromEnemies(ctx(), owner);
                    Stealth.dropAggro(ctx(), owner, AGGRO_DROP_RANGE);
                }
                if (run % 5 == 0) {
                    owner.sendActionBar(Text.mm("<dark_gray>☁</dark_gray> <" + COLOR_TAG + ">연막 습격</" + COLOR_TAG
                            + "> <gray>" + Fx.seconds(duration - run) + "초" + (critReady ? " · 치명타 준비" : "")
                            + "</gray>"));
                }
            }
            return true;
        }

        boolean contains(Location location) {
            if (!location.getWorld().equals(center.getWorld())) {
                return false;
            }
            double dy = location.getY() - center.getY();
            double dx = location.getX() - center.getX();
            double dz = location.getZ() - center.getZ();
            return dy > -2 && dy < SMOKE_HEIGHT + 1 && dx * dx + dz * dz <= radius * radius;
        }

        /** Attacking or being struck shows the ninja for a moment; the smoke hides it again afterwards. */
        void expose(Reveal reason) {
            exposedUntil = ctx().currentTick() + Math.max(1, revealTicks);
            Player owner = player();
            hideEnd(owner);
            if (reason == Reveal.HIT) {
                owner.sendActionBar(Text.mm("<gray>공격받아 모습이 드러났습니다</gray>"));
            }
        }

        /** Layered smoke: lingering campfire smoke, black puffs, dark dust and a smouldering red rim. */
        private void drawCloud(long run) {
            World world = center.getWorld();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int lingering = Vfx.n(ctx(), 3);
            for (int i = 0; i < lingering; i++) {
                Location at = Vfx.scatter(center, radius * 0.95).add(0, random.nextDouble(0.2, SMOKE_HEIGHT), 0);
                // count 0: the offsets are a velocity, so the smoke drifts slowly instead of rising like a campfire.
                world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at.getX(), at.getY(), at.getZ(), 0,
                        random.nextGaussian(), random.nextDouble(-0.2, 0.6), random.nextGaussian(), 0.012, null,
                        false);
            }
            int puffs = Vfx.n(ctx(), 5);
            for (int i = 0; i < puffs; i++) {
                Location at = Vfx.scatter(center, radius).add(0, random.nextDouble(0.1, SMOKE_HEIGHT * 0.9), 0);
                world.spawnParticle(Particle.LARGE_SMOKE, at, 1, 0.2, 0.2, 0.2, 0.01);
            }
            int dust = Vfx.n(ctx(), 5);
            for (int i = 0; i < dust; i++) {
                Location at = Vfx.scatter(center, radius).add(0, random.nextDouble(0.3, SMOKE_HEIGHT), 0);
                Vfx.dot(at, Particle.DUST, i % 2 == 0 ? SMOKE_DUST : ASH_DUST, false);
            }
            if (run % 4 == 0) {
                Location rim = center.clone().add(0, 0.12, 0);
                Vfx.ring(ctx(), rim, radius, (int) Math.max(16, Math.min(40, radius * 6)), run * 0.05,
                        Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.1f), true);
                Vfx.ring(ctx(), rim.clone().add(0, 0.4, 0), radius * 0.97, (int) Math.max(8, radius * 2), -run * 0.05,
                        Particle.SMOKE, null, false);
            }
            if (run % 3 == 0) {
                Location ember = Vfx.scatter(center, radius * 0.9).add(0, 0.1, 0);
                Vfx.trail(ember, ember.clone().add(0, random.nextDouble(1.0, 2.2), 0),
                        random.nextBoolean() ? PALETTE.body() : PALETTE.edge(), 12, false);
            }
        }

        void end() {
            Player owner = player();
            hideEnd(owner);
            if (smoke == this) {
                smoke = null;
            }
            ambushVictim = null;
            World world = center.getWorld();
            Vfx.cloud(ctx(), center.clone().add(0, 1.2, 0), Particle.LARGE_SMOKE, 24, radius * 0.4, 0.05, null,
                    false);
            Vfx.ring(ctx(), center.clone().add(0, 0.2, 0), radius, 24, 0, Particle.CLOUD, null, false);
            world.playSound(center, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.4f);
            if (owner.isOnline()) {
                owner.sendActionBar(Text.mm("<gray>연막이 걷혔습니다</gray>"));
            }
        }
    }

    private void hideBegin(Player owner, long ticks) {
        if (ticks <= 0) {
            return;
        }
        owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) Math.min(Integer.MAX_VALUE,
                ticks + 5), 0, false, false, true));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) SPEED_REFRESH + 5, SPEED_AMPLIFIER, false,
                false, true));
        stealth.begin(ctx(), owner, ticks, () -> hideEnd(owner));
        // Vanishing: the body comes apart into smoke.
        Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
        Vfx.cloud(ctx(), body, Particle.LARGE_SMOKE, 8, 0.3, 0.02, null, false);
        body.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 8), 0.25, 0.5, 0.25, 0,
                PALETTE.fade(1.1f));
        Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 1.3f + Fx.jitter(0.1f));
    }

    /** Ends the smoke's stealth (if any): the ninja is shown again and loses the smoke's invisibility and speed. */
    private void hideEnd(Player owner) {
        if (!stealth.end(ctx(), owner) || !owner.isOnline()) {
            return;
        }
        removeOwnEffect(owner, PotionEffectType.INVISIBILITY, 0);
        removeOwnEffect(owner, PotionEffectType.SPEED, SPEED_AMPLIFIER);
    }

    /** Removes the effect if it is ours (same amplifier); a stronger one from another source is left alone. */
    private static void removeOwnEffect(Player owner, PotionEffectType type, int amplifier) {
        PotionEffect effect = owner.getPotionEffect(type);
        if (effect != null && effect.getAmplifier() == amplifier) {
            owner.removePotionEffect(type);
        }
    }

    /** The ambush: a red X through the victim, 斬 flashing over its head, a spray of blood and a heavy slice. */
    private void ambushVisual(LivingEntity victim) {
        Player owner = player();
        Location body = Fx.body(victim);
        World world = body.getWorld();
        Vector toward = Motion.horizontal(body.toVector().subtract(owner.getLocation().toVector()));
        if (toward.lengthSquared() < 1.0E-6) {
            toward = Motion.horizontalFacing(owner.getLocation());
        }
        // The X stands across the blow: its facing is part of its pose, the displays themselves are unturned.
        float yaw = (float) Math.toDegrees(Math.atan2(-toward.getX(), toward.getZ()));
        for (float roll : new float[] {40, -40}) {
            BlockDisplay cut = Displays.block(ctx(), owner, body, RED_CORE.createBlockData(),
                    Displays.transform(0, 0, 0, 0.3f, 0.04f, 0.06f, yaw, 0, roll), 12);
            Displays.animateNextTick(ctx(), owner, cut, Displays.transform(0, 0, 0, 2.2f, 0.05f, 0.06f, yaw, 0, roll),
                    2);
            ctx().runLater(owner, 5, () -> Displays.animate(cut,
                    Displays.transform(0, 0, 0, 2.5f, 0.001f, 0.001f, yaw, 0, roll), 5));
        }
        TextDisplay mark = Displays.text(ctx(), owner, body.clone().add(0, victim.getHeight() * 0.6 + 0.4, 0),
                Component.text("斬", TextColor.color(0xD7263D), TextDecoration.BOLD), 0.5f, 18);
        Displays.animateNextTick(ctx(), owner, mark, Displays.centered(0, 0.3f, 0, 2.2f, 2.2f, 2.2f, 0, 0, 0), 3);
        ctx().runLater(owner, 10, () -> {
            if (mark.isValid()) {
                Displays.shrinkAway(ctx(), owner, mark, 6);
            }
        });
        world.spawnParticle(Particle.FLASH, body, 1, 0, 0, 0, 0, PALETTE.body(), true);
        world.spawnParticle(Particle.FALLING_DUST, body, Vfx.n(ctx(), 14), 0.3, 0.4, 0.3, 0, BLOOD.createBlockData());
        Vfx.burst(ctx(), body, PALETTE, 0.8, Particle.CRIT, true);
        world.spawnParticle(Particle.SWEEP_ATTACK, body, 1, 0, 0, 0, 0);
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ITEM_TRIDENT_HIT, 0.8f, 0.8f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>斬 연막 습격!</b></" + COLOR_TAG + "> <gray>+"
                + AbilityInfo.formatNumber(params().num("ult_crit")) + "%</gray>"));
    }

    /** A crimson 忍 that rises out of the smoke bomb and fades. */
    private void sigil(Location at) {
        Player owner = player();
        TextDisplay sigil = Displays.text(ctx(), owner, at,
                Component.text("忍", TextColor.color(0xD7263D), TextDecoration.BOLD), 0.6f, 30);
        Displays.animateNextTick(ctx(), owner, sigil, Displays.centered(0, 0.8f, 0, 3.4f, 3.4f, 3.4f, 0, 0, 0), 6);
        ctx().runLater(owner, 16, () -> {
            if (sigil.isValid()) {
                Displays.animate(sigil, Displays.centered(0, 1.6f, 0, 3.8f, 3.8f, 3.8f, 0, 0, 0), 10);
                sigil.setTextOpacity((byte) 40);
            }
        });
    }

    /** A copy without yaw and pitch, so flat displays stay flat. */
    private static Location flat(Location location) {
        Location copy = location.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }
}
