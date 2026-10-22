package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 거북이 — slow and sure. Its shell takes the sting out of every hit from behind, it pulls in and spins across the
 * field like a ricocheting shell (bouncing off walls and bowling enemies over) and, as the ultimate, hides under a
 * giant shell that shrugs off almost everything and lashes every nearby enemy with thorns when struck.
 *
 * <p>Look: sea green and brown. The turtle always wears its shell on its back (three plates: an olive rim, a mossy
 * dome and a central scute) that flashes and clacks when a hit glances off it. For the spin the turtle shrinks into
 * the shell, which flips flat around it and whirls, throwing dust and sparks off every wall it bounces from; it pops
 * back out a little dizzy. The giant shell is a dome of plates (brown rim, mossy flanks, a lime cap) open in front,
 * turning with the turtle; struck, it shoots dripstone thorns out of its flanks and a green ring of thorns rolls out.
 */
public final class TurtleAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("turtle", "거북이", Material.TURTLE_HELMET)
            .color(TextColor.color(0x3FA66B))
            .difficulty(1)
            .passive("등껍질",
                    "등 뒤({back_angle}° 범위)에서 날아온 공격의 피해를 {back_reduction}% 덜 받습니다. 근접 공격, "
                            + "투사체, 능력 공격 모두 해당합니다.")
            .active("껍질 회전",
                    "등껍질 속으로 움츠려 바라보는 방향으로 빙글빙글 돌며 {spin_distance}블록 미끄러집니다(받는 피해 "
                            + "-{spin_reduction}%). 벽에 닿으면 튕겨 나오고(최대 {spin_bounces}번), 부딪힌 적에게 "
                            + "{spin_damage} 피해를 주고 튕겨 냅니다.",
                    8)
            .ultimate("거대 등껍질",
                    "{ult_duration}초간 거대한 등껍질을 짊어집니다. 받는 피해가 {ult_reduction}% 줄고 밀려나지 않지만 "
                            + "이동 속도가 {ult_slow}% 느려집니다. 그동안 적에게 맞은 피해의 {ult_thorns}%가 가시가 되어 "
                            + "반경 {ult_radius}블록 안의 모든 적에게 돌아갑니다.")
            .param("back_reduction", 50)
            .param("back_angle", 120)
            .param("spin_distance", 12)
            .param("spin_bounces", 2)
            .param("spin_damage", 4)
            .param("spin_reduction", 50)
            .param("ult_duration", 6)
            .param("ult_reduction", 80)
            .param("ult_slow", 40)
            .param("ult_thorns", 40)
            .param("ult_radius", 4)
            .ultimateNotStandalone()
            .build();

    private static final String GREEN = "#3FA66B";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** Pale sea-foam core, sea green, the brown of the shell's rim. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xD8F5C8, 0x3FA66B, 0x5B4026);
    private static final Material RIM = Material.GREEN_TERRACOTTA;
    private static final Material DOME = Material.MOSS_BLOCK;
    private static final Material SCUTE = Material.LIME_TERRACOTTA;
    private static final Material FLASH = Material.LIME_CONCRETE;
    private static final Material GIANT_RIM = Material.BROWN_TERRACOTTA;
    private static final Material GIANT_FLANK = Material.MOSS_BLOCK;
    private static final Material GIANT_UPPER = Material.GREEN_TERRACOTTA;
    private static final Material GIANT_CAP = Material.LIME_TERRACOTTA;
    private static final Material THORN = Material.POINTED_DRIPSTONE;
    private static final int DISPLAY_BUDGET = 24;

    // ------------------------------------------------------------------ passive: back shell
    private static final long BLOCK_FEEDBACK_COOLDOWN = 8;
    private static final long FLASH_TICKS = 3;
    private static final int POSE_TICKS = 3;
    /** The shell plates are re-turned once the body has turned more than this (degrees). */
    private static final float YAW_EPSILON = 1.5f;

    // ------------------------------------------------------------------ active: shell spin
    /** The turtle pulls in to this share of its size while spinning. */
    private static final double SPIN_SHRINK = 0.35;
    private static final double SPIN_SPEED = 0.7;
    private static final double SPIN_SINK = -0.08;
    private static final float SPIN_TURN = 38f;
    private static final double CONTACT_RADIUS = 0.9;
    private static final double WALL_PROBE = 0.3;
    private static final double WALL_PROBE_FEET = 0.3;
    private static final long SPIN_GRACE_TICKS = 3;
    private static final double STALL_EPSILON = 0.08;
    private static final int STALL_LIMIT = 3;
    private static final long MAX_LAG_TICKS = 8;
    private static final double MILLIS_PER_TICK = 50.0;
    private static final double KNOCK_SIDE = 0.8;
    private static final double KNOCK_FORWARD = 0.5;
    private static final double KNOCK_UP = 0.4;
    private static final double MAX_SPEED = 2.2;
    /** Without headroom the turtle stays small for at most this long before it grows back anyway. */
    private static final long GROW_BACK_TIMEOUT = 60;
    private static final long SPIN_LANDING_TICKS = 60;
    private static final long DIZZY_TICKS = 20;

    // ------------------------------------------------------------------ ultimate: giant shell
    private static final double GIANT_RADIUS = 2.0;
    private static final float PLATE_THICKNESS = 0.14f;
    /** Rings of the giant shell: elevation (degrees), slots, plate size, material, and the open front (degrees). */
    private static final GiantRing[] GIANT_RINGS = {
            new GiantRing(8, 8, 1.35f, GIANT_RIM, 0),
            new GiantRing(40, 7, 1.2f, GIANT_FLANK, 55),
            new GiantRing(68, 4, 1.0f, GIANT_UPPER, 50),
            new GiantRing(90, 1, 1.0f, GIANT_CAP, 0)};
    private static final int GIANT_ASSEMBLE_TICKS = 6;
    private static final long THORN_PERIOD = 6;
    private static final int THORN_SPIKES = 4;
    private static final long THORN_LIFE = 5;
    private static final long PLATE_FLASH_TICKS = 3;

    private final BackShell shell = new BackShell();
    private final LandingGuard landing = new LandingGuard();
    private final List<Display> displays = new ArrayList<>();
    private @Nullable Spin spin;
    private @Nullable GiantShell giant;
    private long lastBlockFeedback = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow

    private record GiantRing(double elevation, int slots, float size, Material material, double openFront) {
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        spin = null;
        giant = null;
        landing.disarm();
        shell.forget();
        displays.clear();
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        landing.update(owner, ctx().currentTick());
        shell.tick(owner);
    }

    // ------------------------------------------------------------------ damage: back shell, spin, giant shell

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        long now = ctx().currentTick();
        if (landing.absorb(event, now)) {
            return;
        }
        if (event.getDamage() <= 0) {
            return;
        }
        Tag<DamageType> bypass = DamageTypeTags.BYPASSES_INVULNERABILITY;
        if (bypass != null && bypass.isTagged(event.getDamageSource().getDamageType())) {
            return;
        }
        double original = event.getDamage();
        GiantShell big = giant;
        if (big != null) {
            event.setDamage(original * (1 - fraction("ult_reduction")));
            big.struck(event, original);
            return;
        }
        if (spin != null) {
            event.setDamage(original * (1 - fraction("spin_reduction")));
            spinClack();
            return;
        }
        Vector from = attackDirection(event);
        if (from == null) {
            return;
        }
        Vector back = Motion.horizontalFacing(bodyFacing(player())).multiply(-1);
        double half = Math.toRadians(Math.clamp(params().num("back_angle"), 0, 360) / 2);
        if (from.dot(back) >= Math.cos(half) - 1.0E-9) {
            event.setDamage(original * (1 - fraction("back_reduction")));
            glance(from);
        }
    }

    private double fraction(String key) {
        return Math.clamp(params().num(key) / 100.0, 0.0, 1.0);
    }

    /**
     * The horizontal direction from the turtle towards where the hit came from: the attacker's position, or for a
     * projectile the way it flew in. Null for hits without a source (fall, fire...) or straight from above.
     */
    private @Nullable Vector attackDirection(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        Entity damager = byEntity.getDamager();
        Location feet = player().getLocation();
        Vector from;
        if (damager instanceof Projectile projectile && projectile.getVelocity().lengthSquared() > 1.0E-4) {
            from = projectile.getVelocity().clone().multiply(-1);
        } else {
            from = damager.getLocation().toVector().subtract(feet.toVector());
        }
        Vector flat = Motion.horizontal(from);
        return flat.lengthSquared() < 1.0E-6 ? null : flat;
    }

    /** A hit glances off the shell: the plates flash, scute chips fly and the shell clacks. */
    private void glance(Vector from) {
        long now = ctx().currentTick();
        if (now - lastBlockFeedback < BLOCK_FEEDBACK_COOLDOWN) {
            return;
        }
        lastBlockFeedback = now;
        Player owner = player();
        Location back = owner.getLocation().add(0, owner.getHeight() * 0.62, 0).add(from.clone().multiply(0.45));
        Fx.sound(back, Sound.ENTITY_ARMADILLO_HURT_REDUCED, 1.0f, 1.1f + Fx.jitter(0.1f));
        Fx.sound(back, Sound.ITEM_ARMOR_EQUIP_TURTLE, 0.6f, 1.3f + Fx.jitter(0.1f));
        Vfx.cloud(ctx(), back, Particle.ITEM, 5, 0.15, 0.08, new ItemStack(Material.TURTLE_SCUTE), false);
        Vfx.arc(ctx(), back, 0.35, yawOf(from), 120, 8, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), false);
        shell.flash(owner);
        owner.sendActionBar(Text.mm("<" + GREEN + ">등껍질!</" + GREEN + "> <gray>뒤에서 온 피해 -"
                + AbilityInfo.formatNumber(params().num("back_reduction")) + "%</gray>"));
    }

    private void spinClack() {
        long now = ctx().currentTick();
        if (now - lastBlockFeedback < BLOCK_FEEDBACK_COOLDOWN) {
            return;
        }
        lastBlockFeedback = now;
        Location at = Fx.body(player());
        Fx.sound(at, Sound.ENTITY_ARMADILLO_HURT_REDUCED, 1.0f, 1.4f + Fx.jitter(0.1f));
        Vfx.cloud(ctx(), at, Particle.ENCHANTED_HIT, 5, 0.3, 0.1, null, false);
    }

    /** The direction the turtle's body (and so its shell) faces. */
    private static Location bodyFacing(Player owner) {
        Location at = owner.getLocation();
        at.setYaw(owner.getBodyYaw());
        at.setPitch(0);
        return at;
    }

    // ------------------------------------------------------------------ the shell on the back

    /**
     * Three plates riding on the turtle's back. They follow the body every tick, turn into the whirling shell of the
     * spin and step aside (shrink away) for the giant shell or while the turtle is invisible.
     */
    private final class BackShell {

        private final BlockDisplay[] parts = new BlockDisplay[3];
        private boolean spinning;
        private boolean hidden;
        private float spinYaw;
        private double poseHeight = -1;
        private double poseScale = -1;
        /** The yaw the plates' transformations are turned to (the displays themselves keep yaw and pitch 0). */
        private float poseYaw;

        void tick(Player owner) {
            boolean wantHidden = giant != null || concealed(owner);
            if (wantHidden != hidden) {
                hidden = wantHidden;
                if (hidden) {
                    for (int i = 0; i < parts.length; i++) {
                        if (parts[i] != null && parts[i].isValid()) {
                            Displays.shrinkAway(ctx(), owner, parts[i], POSE_TICKS);
                        }
                        parts[i] = null;
                    }
                    return;
                }
                poseHeight = -1;
            }
            if (hidden) {
                return;
            }
            if (!ensure(owner)) {
                return;
            }
            Location anchor = owner.getLocation();
            for (BlockDisplay part : parts) {
                Displays.moveTo(part, anchor);
            }
            float yaw;
            if (spinning) {
                spinYaw = (spinYaw + SPIN_TURN) % 360;
                yaw = spinYaw;
            } else {
                yaw = owner.getBodyYaw();
            }
            double height = owner.getHeight();
            double scale = Body.scaleOf(owner);
            boolean reshaped = Math.abs(height - poseHeight) > 1.0E-3 || Math.abs(scale - poseScale) > 1.0E-3;
            if (reshaped || spinning || Math.abs(angleBetween(yaw, poseYaw)) > YAW_EPSILON) {
                poseHeight = height;
                poseScale = scale;
                poseYaw = yaw;
                pose(owner, reshaped ? POSE_TICKS : spinning ? 1 : 2);
            }
        }

        /** Spawns missing plates; false if the display budget is spent. */
        private boolean ensure(Player owner) {
            Material[] materials = {RIM, DOME, SCUTE};
            boolean spawned = false;
            for (int i = 0; i < parts.length; i++) {
                if (parts[i] != null && parts[i].isValid()) {
                    continue;
                }
                if (!fits(1)) {
                    return false;
                }
                parts[i] = Displays.block(ctx(), owner, owner.getLocation(), materials[i].createBlockData(),
                        turned(Displays.transform(0, (float) (owner.getHeight() * 0.62), -0.3f, 0.01f, 0.01f, 0.01f,
                                0, 0, 0), owner.getBodyYaw()), 0);
                parts[i].setTeleportDuration(2);
                displays.add(parts[i]);
                spawned = true;
            }
            if (spawned) {
                // Grow into place from the next tick (an animation started on the spawn tick would snap).
                poseHeight = owner.getHeight();
                poseScale = Body.scaleOf(owner);
                poseYaw = owner.getBodyYaw();
                ctx().runLater(owner, 1, () -> pose(owner, POSE_TICKS));
            }
            return true;
        }

        /**
         * Moves the plates to the back (or into the whirling spin shell), turned to {@link #poseYaw}: poses are laid
         * out in the body's frame (local +Z = facing, -Z = the back) and turned in the transformation.
         */
        void pose(Player owner, int ticks) {
            float s = (float) Body.scaleOf(owner);
            Transformation[] poses;
            if (spinning) {
                // Around the shrunken turtle: a wide rim, the dome over it, the scute on top; the head pokes out.
                float k = s / (float) (1 - SPIN_SHRINK);
                poses = new Transformation[] {
                        Displays.transform(0, 0.27f * k, 0, 1.0f * k, 0.3f * k, 1.0f * k, 0, 0, 0),
                        Displays.transform(0, 0.62f * k, 0, 0.84f * k, 0.46f * k, 0.84f * k, 45, 0, 0),
                        Displays.transform(0, 0.9f * k, 0, 0.46f * k, 0.16f * k, 0.46f * k, 0, 0, 0)};
            } else {
                float chest = (float) (owner.getHeight() * 0.62);
                poses = new Transformation[] {
                        Displays.transform(0, chest, -0.2f * s, 0.66f * s, 0.74f * s, 0.14f * s, 0, 0, 0),
                        Displays.transform(0, chest + 0.02f * s, -0.31f * s, 0.52f * s, 0.6f * s, 0.12f * s, 0, 0, 0),
                        Displays.transform(0, chest + 0.04f * s, -0.39f * s, 0.26f * s, 0.3f * s, 0.06f * s, 0, 0, 0)};
            }
            for (int i = 0; i < parts.length; i++) {
                if (parts[i] != null && parts[i].isValid()) {
                    Displays.animate(parts[i], turned(poses[i], poseYaw), ticks);
                }
            }
        }

        void startSpin(Player owner) {
            spinning = true;
            spinYaw = owner.getBodyYaw();
            poseHeight = -1;
        }

        void endSpin() {
            spinning = false;
            poseHeight = -1;
        }

        /** A hit glanced off: the dome flashes bright for a moment. */
        void flash(Player owner) {
            BlockDisplay dome = parts[1];
            if (dome == null || !dome.isValid()) {
                return;
            }
            dome.setBlock(FLASH.createBlockData());
            ctx().runLater(owner, FLASH_TICKS, () -> {
                if (dome.isValid()) {
                    dome.setBlock(DOME.createBlockData());
                }
            });
        }

        /** The ability was revoked: the context removes the plates, only the references are dropped. */
        void forget() {
            for (int i = 0; i < parts.length; i++) {
                parts[i] = null;
            }
            spinning = false;
            hidden = false;
        }
    }

    // ------------------------------------------------------------------ active: shell spin

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 구를 수 없습니다");
        }
        if (spin != null) {
            return ActivationResult.fail("이미 구르는 중입니다");
        }
        if (giant != null) {
            return ActivationResult.fail("거대 등껍질을 짊어진 채로는 구를 수 없습니다");
        }
        Vector direction = Motion.horizontalFacing(owner.getLocation());
        if (direction.lengthSquared() < 1.0E-6) {
            direction = new Vector(0, 0, 1);
        }
        Spin started = new Spin(owner, direction);
        spin = started;
        started.start();
        return ActivationResult.success();
    }

    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / MILLIS_PER_TICK));
    }

    /**
     * The spin: the turtle shrinks into its shell and slides along, velocity-driven (the client resolves collisions).
     * When the shell runs into a wall (a block right in front of its body) or the arena border, it bounces off; the
     * distance travelled, the bounces and a timeout end it.
     */
    private final class Spin implements LongPredicate {

        private final Player owner;
        private Vector direction;
        private final double distance;
        private final int bounces;
        private final long maxTicks;
        private final long lag;
        private final Set<UUID> hit = new HashSet<>();
        private @Nullable NamespacedKey shrinkKey;
        private Location last;
        private double traveled;
        private int bounced;
        private int stalls;
        private boolean moved;
        /** Ticks since the last bounce; starts high, so a wall right in front bounces the shell at once. */
        private long sinceBounce = 100;

        Spin(Player owner, Vector direction) {
            this.owner = owner;
            this.direction = direction.clone();
            this.distance = Math.max(1, params().num("spin_distance"));
            this.bounces = Math.max(0, params().integer("spin_bounces"));
            this.lag = lagTicks(owner);
            this.maxTicks = (long) Math.ceil(distance / SPIN_SPEED) + 8 + lag * (bounces + 1);
            this.last = owner.getLocation();
        }

        void start() {
            shrinkKey = ctx().addModifier(owner, owner, Attribute.SCALE, -SPIN_SHRINK, Operation.MULTIPLY_SCALAR_1,
                    0);
            shell.startSpin(owner);
            landing.arm(ctx().currentTick(), maxTicks + SPIN_LANDING_TICKS);
            Location feet = owner.getLocation();
            Fx.sound(feet, Sound.ENTITY_ARMADILLO_ROLL, 1.0f, 0.9f + Fx.jitter(0.05f));
            Fx.sound(feet, Sound.ENTITY_TURTLE_SHAMBLE, 1.0f, 1.2f + Fx.jitter(0.1f));
            Fx.sound(feet, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1.4f + Fx.jitter(0.1f));
            Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 0.7, 12, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f),
                    false);
            Vfx.cloud(ctx(), feet.clone().add(0, 0.2, 0), Particle.POOF, 5, 0.25, 0.02, null, false);
            push();
            Loop.repeat(ctx(), owner, 0, 1, maxTicks, this, this::stop);
        }

        private void push() {
            owner.setVelocity(direction.clone().multiply(SPIN_SPEED).setY(SPIN_SINK));
            owner.setFallDistance(0);
        }

        @Override
        public boolean test(long tick) {
            Location now = owner.getLocation();
            if (!now.getWorld().equals(last.getWorld()) || owner.isInsideVehicle()) {
                return false;
            }
            double step = horizontalDistance(last, now);
            traveled += step;
            sinceBounce++;
            if (!moved && step >= STALL_EPSILON) {
                moved = true;
            }
            strike(last, now);
            if (traveled >= distance) {
                return false;
            }
            if (wallAhead(now) || !ctx().isInsideArena(now.clone().add(direction.clone().multiply(SPIN_SPEED * 2)))) {
                if (!bounce(now)) {
                    return false;
                }
            } else if (moved || tick >= SPIN_GRACE_TICKS + lag) {
                stalls = step < STALL_EPSILON && sinceBounce > lag + 2 ? stalls + 1 : 0;
                if (stalls >= STALL_LIMIT && !bounce(now)) {
                    return false;
                }
            }
            last = now;
            push();
            trail(now, tick);
            return true;
        }

        /** A block right in front of the shell at body height (the feet are skipped, so steps never count). */
        private boolean wallAhead(Location at) {
            BoundingBox box = owner.getBoundingBox();
            BoundingBox probe = new BoundingBox(box.getMinX() + 0.02, box.getMinY() + WALL_PROBE_FEET,
                    box.getMinZ() + 0.02, box.getMaxX() - 0.02, Math.max(box.getMinY() + WALL_PROBE_FEET + 0.1,
                    box.getMaxY() - 0.05), box.getMaxZ() - 0.02)
                    .shift(direction.getX() * WALL_PROBE, 0, direction.getZ() * WALL_PROBE);
            return at.getWorld().hasCollisionsIn(probe);
        }

        /** Bounces off the wall in front (reflects along the blocked axis); false when out of bounces. */
        private boolean bounce(Location at) {
            if (bounced >= bounces || sinceBounce <= 1) {
                return bounced < bounces;
            }
            bounced++;
            sinceBounce = 0;
            stalls = 0;
            Vector reflected = reflect(at);
            Location contact = at.clone().add(0, owner.getHeight() * 0.5, 0).add(direction.clone().multiply(0.45));
            direction = reflected;
            BlockData wall = contact.clone().add(direction.clone().multiply(-0.4)).getBlock().getBlockData();
            if (!wall.getMaterial().isAir()) {
                at.getWorld().spawnParticle(Particle.BLOCK_CRUMBLE, contact, Vfx.n(ctx(), 10), 0.2, 0.3, 0.2, 0.05,
                        wall);
            }
            Vfx.burst(ctx(), contact, PALETTE, 0.5, Particle.CRIT, false);
            Vfx.dot(contact, Particle.FLASH, PALETTE.core(), false);
            Fx.sound(contact, Sound.ENTITY_ARMADILLO_HURT_REDUCED, 1.2f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(contact, Sound.BLOCK_DECORATED_POT_HIT, 1.0f, 0.7f + Fx.jitter(0.1f));
            Fx.sound(contact, Sound.ITEM_SHIELD_BLOCK, 0.6f, 1.3f + Fx.jitter(0.1f));
            owner.sendActionBar(Text.mm("<" + GREEN + ">팅!</" + GREEN + "> <gray>튕김 " + bounced + "/" + bounces
                    + "</gray>"));
            return true;
        }

        /** The new direction off whatever stopped the shell: flips the blocked axis (or turns back). */
        private Vector reflect(Location at) {
            BoundingBox box = owner.getBoundingBox();
            BoundingBox base = new BoundingBox(box.getMinX() + 0.02, box.getMinY() + WALL_PROBE_FEET,
                    box.getMinZ() + 0.02, box.getMaxX() - 0.02, Math.max(box.getMinY() + WALL_PROBE_FEET + 0.1,
                    box.getMaxY() - 0.05), box.getMaxZ() - 0.02);
            World world = at.getWorld();
            boolean xBlocked = Math.abs(direction.getX()) > 1.0E-3
                    && world.hasCollisionsIn(base.clone().shift(Math.signum(direction.getX()) * WALL_PROBE, 0, 0));
            boolean zBlocked = Math.abs(direction.getZ()) > 1.0E-3
                    && world.hasCollisionsIn(base.clone().shift(0, 0, Math.signum(direction.getZ()) * WALL_PROBE));
            Vector out = direction.clone();
            if (xBlocked) {
                out.setX(-out.getX());
            }
            if (zBlocked) {
                out.setZ(-out.getZ());
            }
            if (!xBlocked && !zBlocked) {
                out.multiply(-1); // the border or a corner: straight back
            }
            out.setY(0);
            return out.lengthSquared() < 1.0E-6 ? direction.clone().multiply(-1) : out.normalize();
        }

        /** Every enemy the shell touched on the way since the last tick is bowled over (once per spin). */
        private void strike(Location from, Location to) {
            Vector a = from.toVector().add(new Vector(0, owner.getHeight() * 0.5, 0));
            Vector b = to.toVector().add(new Vector(0, owner.getHeight() * 0.5, 0));
            Location mid = a.clone().add(b).multiply(0.5).toLocation(to.getWorld());
            double reach = a.distance(b) / 2 + CONTACT_RADIUS + 0.4;
            double damage = Math.max(0, params().num("spin_damage"));
            for (LivingEntity enemy : ctx().enemiesNear(owner, mid, reach)) {
                if (hit.contains(enemy.getUniqueId())) {
                    continue;
                }
                Vector center = enemy.getLocation().toVector().add(new Vector(0, enemy.getHeight() * 0.5, 0));
                if (distanceToSegment(center, a, b) > CONTACT_RADIUS + enemy.getWidth() / 2) {
                    continue;
                }
                hit.add(enemy.getUniqueId());
                Combat.hit(ctx(), owner, enemy, damage);
                Location body = Fx.body(enemy);
                if (visible(owner, enemy)) {
                    Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.CRIT, false);
                }
                Fx.sound(body, Sound.ENTITY_ARMADILLO_HURT_REDUCED, 1.0f, 1.3f + Fx.jitter(0.1f));
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.0f + Fx.jitter(0.1f));
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                // Bowled aside like a pin, off to whichever side of the shell it stood on.
                Vector side = new Vector(-direction.getZ(), 0, direction.getX());
                if (center.clone().subtract(a).dot(side) < 0) {
                    side.multiply(-1);
                }
                Vector knock = side.multiply(KNOCK_SIDE).add(direction.clone().multiply(KNOCK_FORWARD)).setY(KNOCK_UP);
                Motion.push(enemy, knock, MAX_SPEED);
                enemy.setFallDistance(0);
            }
        }

        /** One tick of the spin: a ring of dust and water spray thrown off the whirling shell. */
        private void trail(Location at, long tick) {
            Location ground = at.clone().add(0, 0.1, 0);
            double phase = tick * 0.9;
            Vfx.arc(ctx(), ground.clone().add(0, 0.25, 0), 0.65, Math.toDegrees(phase), 160, 8,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), false);
            Vfx.dot(ground.clone().subtract(direction.clone().multiply(0.5)), Particle.CLOUD, null, false);
            if (tick % 2 == 0) {
                BlockData dirt = groundUnder(at);
                at.getWorld().spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 3), 0.25, 0.03, 0.25, 0.08, dirt);
                Vfx.dot(ground.clone().add(0, 0.4, 0), Particle.SPLASH, null, false);
            }
            if (tick % 4 == 0) {
                Fx.sound(at, Sound.ENTITY_ARMADILLO_ROLL, 0.5f, 1.3f + Fx.jitter(0.1f));
            }
        }

        /** The spin ends: the turtle pops back out of its shell (once there is room) and shakes its head. */
        private void stop() {
            if (spin == this) {
                spin = null;
            }
            owner.setVelocity(direction.clone().multiply(0.1).setY(Math.min(0, owner.getVelocity().getY())));
            shell.endSpin();
            growBack(0);
            Location feet = owner.getLocation();
            Fx.sound(feet, Sound.ENTITY_ARMADILLO_UNROLL_FINISH, 1.0f, 1.0f + Fx.jitter(0.1f));
            Vfx.cloud(ctx(), feet.clone().add(0, 0.3, 0), Particle.POOF, 4, 0.2, 0.02, null, false);
            if (!hit.isEmpty()) {
                owner.sendActionBar(Text.mm("<" + GREEN + ">껍질 회전</" + GREEN + "> <white>" + hit.size()
                        + "명 적중</white>"));
            }
            dizzy();
        }

        /** Restores the turtle's size as soon as a full-size body fits where it stands (or after a timeout). */
        private void growBack(long waited) {
            NamespacedKey key = shrinkKey;
            if (key == null) {
                return;
            }
            Body body = Body.standing(owner);
            double full = body.height() / (1 - SPIN_SHRINK);
            double width = body.width() / (1 - SPIN_SHRINK);
            if (waited < GROW_BACK_TIMEOUT && !Spots.isFree(owner.getWorld(), owner.getLocation(), width, full)) {
                ctx().runLater(owner, 2, () -> growBack(waited + 2));
                return;
            }
            shrinkKey = null;
            ctx().removeModifier(owner, key);
        }

        /** Little stars circling the turtle's head for a moment. */
        private void dizzy() {
            Loop.repeat(ctx(), owner, 2, 2, DIZZY_TICKS / 2, run -> {
                if (concealed(owner)) {
                    return true;
                }
                Location head = owner.getEyeLocation().add(0, 0.45, 0);
                double angle = run * 0.9;
                for (int i = 0; i < 3; i++) {
                    double a = angle + i * TAU / 3;
                    Vfx.dot(head.clone().add(Math.cos(a) * 0.35, 0, Math.sin(a) * 0.35), Particle.WAX_OFF, null,
                            false);
                }
                return true;
            });
        }
    }

    // ------------------------------------------------------------------ ultimate: giant shell

    @Override
    public ActivationResult onUltimate() {
        if (giant != null) {
            return ActivationResult.fail("이미 거대 등껍질을 짊어지고 있습니다");
        }
        if (spin != null) {
            return ActivationResult.fail("구르는 중에는 쓸 수 없습니다");
        }
        GiantShell created = new GiantShell();
        giant = created;
        created.begin();
        return ActivationResult.success();
    }

    /**
     * The giant shell: damage reduction, no knockback and a slow (modifiers with a duration, so they also go if the
     * turtle dies), a dome of plates open in front that turns with the turtle, and thorns: a share of every enemy hit
     * is stored and released in bursts at all enemies around.
     */
    private final class GiantShell implements LongPredicate {

        private record Plate(BlockDisplay display, double azimuth, double elevation, float size, Material material) {
        }

        private final long duration = Math.max(20, params().ticks("ult_duration"));
        private final double radius = Math.max(0, params().num("ult_radius"));
        private final double thorns = fraction("ult_thorns");
        private final List<Plate> plates = new ArrayList<>();
        private double pending;
        private long lastFlash = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
        private double totalReflected;
        /** The yaw the dome is turned to, in the plates' transformations (the displays keep yaw and pitch 0). */
        private float shellYaw;
        /** The dome follows the turtle's turns only once every plate has flown into place. */
        private final long assembled = 1 + (GIANT_RINGS.length - 1) * 2L + GIANT_ASSEMBLE_TICKS;

        void begin() {
            Player owner = player();
            double slow = fraction("ult_slow");
            if (slow > 0) {
                ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                        duration);
            }
            ctx().addModifier(owner, owner, Attribute.KNOCKBACK_RESISTANCE, 1, Operation.ADD_NUMBER, duration);
            assemble(owner);
            Location feet = owner.getLocation();
            Fx.sound(feet, Sound.ITEM_ARMOR_EQUIP_TURTLE, 1.2f, 0.6f);
            Fx.sound(feet, Sound.ENTITY_ARMADILLO_ROLL, 1.0f, 0.6f);
            Fx.sound(feet, Sound.BLOCK_ANVIL_PLACE, 0.5f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(feet, Sound.ENTITY_TURTLE_AMBIENT_LAND, 1.0f, 0.6f);
            Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.5, Math.max(radius, GIANT_RADIUS),
                    GIANT_ASSEMBLE_TICKS, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.3f), true);
            Vfx.cloud(ctx(), feet.clone().add(0, 0.3, 0), Particle.BLOCK, 14, 1.0, 0.1, groundUnder(feet), false);
            owner.sendActionBar(Text.mm("<" + GREEN + "><b>거대 등껍질</b></" + GREEN + "> <gray>받는 피해 -"
                    + AbilityInfo.formatNumber(params().num("ult_reduction")) + "% · " + Fx.seconds(duration)
                    + "초</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::end);
        }

        /** The plates fly in from around the turtle and lock into the dome, ring by ring. */
        private void assemble(Player owner) {
            Location anchor = owner.getLocation();
            shellYaw = owner.getBodyYaw();
            float yaw = shellYaw;
            long lifetime = duration + GIANT_ASSEMBLE_TICKS * 3L + 20;
            for (int r = 0; r < GIANT_RINGS.length; r++) {
                GiantRing ring = GIANT_RINGS[r];
                for (int i = 0; i < ring.slots(); i++) {
                    double azimuth = (i + 0.5 * (r % 2)) * TAU / ring.slots();
                    if (ring.openFront() > 0 && Math.abs(frontAngle(azimuth)) < ring.openFront()) {
                        continue;
                    }
                    if (!fits(1)) {
                        return;
                    }
                    BlockDisplay display = Displays.block(ctx(), owner, anchor, ring.material().createBlockData(),
                            turned(giantPose(azimuth, ring.elevation(), GIANT_RADIUS * 1.8, ring.size() * 0.2f), yaw),
                            lifetime);
                    display.setTeleportDuration(2);
                    display.setViewRange(2.0f);
                    displays.add(display);
                    Transformation pose = turned(giantPose(azimuth, ring.elevation(), GIANT_RADIUS, ring.size()),
                            yaw);
                    ctx().runLater(owner, 1 + r * 2L, () -> Displays.animate(display, pose, GIANT_ASSEMBLE_TICKS));
                    plates.add(new Plate(display, azimuth, ring.elevation(), ring.size(), ring.material()));
                }
            }
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            Location anchor = owner.getLocation();
            float yaw = owner.getBodyYaw();
            boolean turn = tick > assembled && Math.abs(angleBetween(yaw, shellYaw)) > YAW_EPSILON;
            if (turn) {
                shellYaw = yaw;
            }
            for (Plate plate : plates) {
                if (plate.display().isValid()) {
                    Displays.moveTo(plate.display(), anchor);
                    if (turn) {
                        Displays.animate(plate.display(), turned(giantPose(plate.azimuth(), plate.elevation(),
                                GIANT_RADIUS, plate.size()), shellYaw), 2);
                    }
                }
            }
            if (tick % THORN_PERIOD == 0 && pending > 0) {
                release(owner);
            }
            if (tick % 10 == 0) {
                Location feet = owner.getLocation().add(0, 0.1, 0);
                if (radius > 0) {
                    Vfx.ring(ctx(), feet, radius, (int) Math.max(16, Math.min(40, radius * 6)), tick * 0.03,
                            Particle.DUST, PALETTE.dust(0.9f), false);
                }
                long left = duration - tick;
                owner.sendActionBar(Text.mm("<" + GREEN + ">거대 등껍질</" + GREEN + "> <white>" + Fx.seconds(left)
                        + "초</white>" + (totalReflected > 0 ? " <gray>가시 " + AbilityInfo.formatNumber(
                        Math.round(totalReflected * 10) / 10.0) + "</gray>" : "")));
            }
            if (tick % 6 == 0) {
                Location feet = owner.getLocation();
                Vfx.dot(Vfx.scatter(feet, GIANT_RADIUS).add(0, 0.1, 0), Particle.DUST_COLOR_TRANSITION,
                        PALETTE.fade(1.0f), false);
            }
            return true;
        }

        /** A hit landed on the shell: store the thorns, flash the plate it struck. */
        void struck(EntityDamageEvent event, double original) {
            if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
                return;
            }
            Player owner = player();
            Entity damager = byEntity.getDamager();
            Player attacker = ctx().resolveAttacker(damager);
            boolean hostile = attacker != null ? ctx().isEnemy(owner, attacker) : ctx().isEnemy(owner, damager);
            if (!hostile) {
                return;
            }
            pending += original * thorns;
            flashPlate(owner, damager);
        }

        /** The stored thorns burst out at every enemy around: spikes from the flanks and a ring of thorns. */
        private void release(Player owner) {
            double amount = pending;
            pending = 0;
            Location feet = owner.getLocation();
            Location center = feet.clone().add(0, 0.9, 0);
            int struck = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                Combat.hit(ctx(), owner, enemy, amount);
                struck++;
                Location body = Fx.body(enemy);
                if (visible(owner, enemy)) {
                    Vfx.burst(ctx(), body, PALETTE, 0.4, Particle.CRIT, false);
                    Vfx.trail(center, body, PALETTE.body(), 4, false);
                }
                Fx.sound(body, Sound.ENCHANT_THORNS_HIT, 1.0f, 1.0f + Fx.jitter(0.1f));
            }
            if (struck > 0) {
                totalReflected += amount * struck;
            }
            spikes(owner);
            Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.2, 0), GIANT_RADIUS, Math.max(GIANT_RADIUS, radius), 4,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.2f), false);
            Fx.sound(feet, Sound.ENCHANT_THORNS_HIT, 1.2f, 0.7f + Fx.jitter(0.1f));
            Fx.sound(feet, Sound.BLOCK_POINTED_DRIPSTONE_LAND, 1.0f, 1.2f + Fx.jitter(0.1f));
        }

        /** Dripstone thorns shoot out of the flanks in a fan and snap back. */
        private void spikes(Player owner) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location at = owner.getLocation();
            at.setYaw(0);
            at.setPitch(0);
            double offset = random.nextDouble(TAU);
            for (int i = 0; i < THORN_SPIKES; i++) {
                if (!fits(1)) {
                    return;
                }
                double azimuth = offset + i * TAU / THORN_SPIKES;
                float yaw = (float) Math.toDegrees(azimuth) - 90;
                double h = 0.9;
                float x = (float) (Math.cos(azimuth) * GIANT_RADIUS * 0.8);
                float z = (float) (Math.sin(azimuth) * GIANT_RADIUS * 0.8);
                BlockDisplay spike = Displays.block(ctx(), owner, at, THORN.createBlockData(),
                        Displays.transform(x, (float) h, z, 0.3f, 0.05f, 0.3f, yaw, 90, 0), THORN_LIFE + 4);
                displays.add(spike);
                float out = 1.6f;
                Displays.animateNextTick(ctx(), owner, spike, Displays.transform(x * out, (float) h, z * out, 0.45f,
                        1.6f, 0.45f, yaw, 90, 0), 2);
                ctx().runLater(owner, 3, () -> {
                    if (spike.isValid()) {
                        Displays.shrinkAway(ctx(), owner, spike, (int) THORN_LIFE - 2);
                    }
                });
            }
        }

        private void flashPlate(Player owner, Entity damager) {
            long now = ctx().currentTick();
            if (plates.isEmpty() || now - lastFlash < PLATE_FLASH_TICKS) {
                return;
            }
            lastFlash = now;
            Location center = owner.getLocation().add(0, 0.6, 0);
            Vector toward = damager.getLocation().add(0, 1, 0).toVector().subtract(center.toVector());
            if (toward.lengthSquared() < 1.0E-6) {
                return;
            }
            toward.normalize();
            float yaw = shellYaw;
            Plate best = null;
            double bestDot = -2;
            for (Plate plate : plates) {
                double dot = normal(plate, yaw).dot(toward);
                if (plate.display().isValid() && dot > bestDot) {
                    bestDot = dot;
                    best = plate;
                }
            }
            if (best == null) {
                return;
            }
            BlockDisplay display = best.display();
            Material material = best.material();
            display.setBlock(FLASH.createBlockData());
            ctx().runLater(owner, PLATE_FLASH_TICKS, () -> {
                if (display.isValid()) {
                    display.setBlock(material.createBlockData());
                }
            });
            Location at = owner.getLocation().add(normal(best, yaw).multiply(GIANT_RADIUS));
            Vfx.cloud(ctx(), at, Particle.ITEM, 4, 0.2, 0.08, new ItemStack(Material.TURTLE_SCUTE), false);
            Fx.sound(at, Sound.ENTITY_ARMADILLO_HURT_REDUCED, 1.0f, 0.7f + Fx.jitter(0.1f));
        }

        /** Time is up: the last thorns fly, the plates burst outwards and the turtle shakes free. */
        private void end() {
            Player owner = player();
            if (pending > 0) {
                release(owner);
            }
            for (Plate plate : plates) {
                BlockDisplay display = plate.display();
                if (!display.isValid()) {
                    continue;
                }
                Displays.animate(display, turned(giantPose(plate.azimuth(), plate.elevation(), GIANT_RADIUS * 1.6,
                        plate.size() * 0.1f), shellYaw), 6);
                ctx().runLater(owner, 6, () -> {
                    if (display.isValid()) {
                        display.remove();
                    }
                });
            }
            plates.clear();
            Location feet = owner.getLocation();
            Vfx.cloud(ctx(), feet.clone().add(0, 1, 0), Particle.ITEM, 12, 1.0, 0.1,
                    new ItemStack(Material.TURTLE_SCUTE), false);
            Fx.sound(feet, Sound.ENTITY_ARMADILLO_UNROLL_FINISH, 1.0f, 0.7f);
            Fx.sound(feet, Sound.ITEM_ARMOR_EQUIP_TURTLE, 1.0f, 1.2f);
            if (giant == this) {
                giant = null;
            }
        }
    }

    /**
     * {@code local}, a pose in a frame turned to the Minecraft yaw {@code yawDegrees} (local +Z = that facing, +X = its
     * left), as the transformation of a display that itself keeps yaw and pitch 0 (same convention as the client's
     * entity rotation: {@code rotY(-yaw)}).
     */
    private static Transformation turned(Transformation local, float yawDegrees) {
        Quaternionf yaw = new Quaternionf().rotateY((float) Math.toRadians(-yawDegrees));
        Vector3f translation = yaw.transform(new Vector3f(local.getTranslation()));
        Quaternionf left = new Quaternionf(yaw).mul(local.getLeftRotation());
        return new Transformation(translation, left, new Vector3f(local.getScale()),
                new Quaternionf(local.getRightRotation()));
    }

    /** The signed difference {@code a - b} of two yaws in degrees, in -180..180. */
    private static float angleBetween(float a, float b) {
        float difference = (a - b) % 360;
        return ((difference + 540) % 360) - 180;
    }

    /** The world-space outward normal of a giant-shell plate, with the shell turned to {@code yaw}. */
    private static Vector normal(GiantShell.Plate plate, float yaw) {
        double azimuth = plate.azimuth() + Math.toRadians(yaw);
        double elevation = Math.toRadians(plate.elevation());
        return new Vector(Math.cos(azimuth) * Math.cos(elevation), Math.sin(elevation),
                Math.sin(azimuth) * Math.cos(elevation));
    }

    /**
     * How far (degrees, -180..180) a plate's azimuth lies from the shell's front. Plates are laid out in the anchor's
     * local frame, where the front (+Z, the body's facing) is azimuth 90°.
     */
    private static double frontAngle(double azimuth) {
        double degrees = Math.toDegrees(azimuth) - 90;
        return ((degrees % 360) + 540) % 360 - 180;
    }

    /** A thick plate tangent to the giant dome at {@code azimuth} (radians) and {@code elevation} (degrees). */
    private static Transformation giantPose(double azimuth, double elevation, double radius, float size) {
        double e = Math.toRadians(elevation);
        float x = (float) (Math.cos(azimuth) * Math.cos(e) * radius);
        float y = (float) (0.1 + Math.sin(e) * radius);
        float z = (float) (Math.sin(azimuth) * Math.cos(e) * radius);
        float yaw = (float) Math.toDegrees(azimuth) - 90;
        return Displays.transform(x, y, z, size, size, PLATE_THICKNESS, yaw, (float) -elevation, 0);
    }

    // ------------------------------------------------------------------ helpers

    /** False for players hidden from the owner (stealth): no effect may give their position away. */
    private static boolean visible(Player owner, LivingEntity entity) {
        return !(entity instanceof Player hidden) || owner.canSee(hidden);
    }

    /** True while the owner is invisible: the shell must not give its position away. */
    private static boolean concealed(Player owner) {
        return owner.isInvisible() || owner.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    /** Forgets removed displays and says whether {@code count} more fit in the display budget. */
    private boolean fits(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= DISPLAY_BUDGET;
    }

    /** The solid block right under {@code at}, dirt over air, fluids and plants. */
    private static BlockData groundUnder(Location at) {
        BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        return ground.getMaterial().isSolid() ? ground : Material.DIRT.createBlockData();
    }

    /** The Minecraft yaw (degrees) of a horizontal direction. */
    private static float yawOf(Vector direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static double distanceToSegment(Vector point, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double lengthSquared = ab.lengthSquared();
        if (lengthSquared < 1.0E-9) {
            return point.distance(a);
        }
        double t = Math.clamp(point.clone().subtract(a).dot(ab) / lengthSquared, 0.0, 1.0);
        return point.distance(a.clone().add(ab.multiply(t)));
    }
}
