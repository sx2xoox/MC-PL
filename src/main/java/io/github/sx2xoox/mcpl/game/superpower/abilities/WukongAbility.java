package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 제천대성 — the Great Sage Equal to Heaven of the classic novel Journey to the West, with the staff that grows and
 * shrinks at will (the novel's monkey king, not any comic character).
 *
 * <p>One weapon, three uses. Every melee blow is a staff sweep that also clips the enemies beside the one struck. The
 * active shoots the staff out at once up to its length: an enemy on it is skewered and driven to the staff's end (and
 * crushed against the wall there); with nobody on it, the staff plants on the wall or the floor it hits and the sage
 * vaults off that face like a ball off a wall, straight up off the floor. The ultimate grows it into a pillar that
 * reaches the sky, draws its shadow on the ground, and topples along it: the only straight-line nuke among the new
 * abilities, dodged by stepping out of the shadow.
 *
 * <p>Look: monkey-king gold, cloud white and imperial red. The staff is a red shaft with gold caps that rides on his
 * back; it streaks out with cloud puffs, and the pillar is a red column with golden bands that crashes down in a wave
 * of dust and cracked ground.
 */
public final class WukongAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("wukong", "제천대성", Material.BLAZE_ROD)
            .color(TextColor.color(0xF0B429))
            .difficulty(2)
            .passive("여의봉 휘두르기",
                    "근접 공격이 여의봉 휘두르기가 되어, 맞은 적 옆 {sweep_radius}블록 안의 다른 적에게도 그 피해의 "
                            + "{sweep_share}%를 줍니다.")
            .active("여의봉아 늘어나라",
                    "여의봉이 바라보는 방향으로 순식간에 최대 {pole_length}블록 늘어나 처음 닿는 블록에서 멈춥니다. 처음 "
                            + "찔린 적은 {pole_damage} 피해를 입고 봉 끝까지 밀려나며, 봉 끝과 벽 사이에 끼면 {pin_damage} "
                            + "추가 피해와 함께 {pin_stagger}초간 휘청입니다(이동·공격 속도 {stagger_slow}% 감소). 적 없이 벽이나 땅을 "
                            + "찌르면 그 면에서 튕기듯 장대뛰기로 약 {vault_height}블록 튀어 나갑니다(바닥을 보고 찌르면 "
                            + "수직으로). 허공을 찌르면 쿨타임이 {miss_refund}% 돌아옵니다.",
                    8)
            .ultimate("여의봉 천주",
                    "여의봉이 {pillar_rise}초에 걸쳐 앞에서 {pillar_height}블록 높이로 솟구치고, 바라보는 방향의 땅에 "
                            + "그림자 선(폭 {pillar_width}블록, 길이 {pillar_length}블록)이 그려집니다. 이어 기둥이 그 선을 "
                            + "따라 쓰러져 깔린 적에게 {pillar_damage} 피해를 주고 {pillar_flat}초간 납작하게 눌러 이동 속도를 "
                            + "{pillar_slow}% 늦춥니다(전투 맵에서는 선을 따라 지형도 갈라집니다).")
            .param("sweep_radius", 1.5)
            .param("sweep_share", 50)
            .param("pole_length", 12)
            .param("pole_damage", 4)
            .param("pin_damage", 2)
            .param("pin_stagger", 0.75)
            .param("stagger_slow", 50)
            .param("vault_height", 6)
            .param("miss_refund", 50)
            .param("pillar_rise", 0.8)
            .param("pillar_height", 20)
            .param("pillar_width", 2)
            .param("pillar_length", 18)
            .param("pillar_damage", 8)
            .param("pillar_flat", 1)
            .param("pillar_slow", 50)
            .param("pillar_terrain", 1.2)
            .build();

    private static final String GOLD_TAG = "#F0B429";
    private static final String RED_TAG = "#C4222A";

    // ------------------------------------------------------------------ look: gold, cloud white, imperial red
    private static final Vfx.Palette GOLDEN = Vfx.Palette.of(0xFFF3C4, 0xF0B429, 0x8A5A00);
    private static final Vfx.Palette IMPERIAL = Vfx.Palette.of(0xFF9A8A, 0xC4222A, 0x4A0A0E);
    private static final Color GOLD = Color.fromRGB(0xF0B429);
    private static final Color CLOUD = Color.fromRGB(0xF7F7F2);
    private static final Color RED = Color.fromRGB(0xC4222A);
    private static final Particle.DustOptions GOLD_DUST = GOLDEN.dust(1.0f);
    private static final Particle.DustOptions RED_DUST = IMPERIAL.dust(0.9f);
    private static final Particle.DustOptions CLOUD_DUST = new Particle.DustOptions(CLOUD, 1.4f);
    private static final Particle.DustOptions SHADOW_DUST = new Particle.DustOptions(Color.fromRGB(0x1A1014), 1.6f);
    private static final Particle.DustTransition GOLD_GLOW = GOLDEN.glow(1.2f);
    private static final Particle.DustTransition RED_FADE = IMPERIAL.fade(1.3f);

    private static final Material SHAFT = Material.RED_CONCRETE;
    private static final Material CAP = Material.GOLD_BLOCK;
    private static final Material SHADOW = Material.BLACK_STAINED_GLASS;

    /** Display entities this ability keeps alive at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    private static final int FOLLOW_GLIDE = 3;
    private static final long FEEDBACK_COOLDOWN = 6;

    // the staff on the back
    private static final float BACK_LENGTH = 1.5f;
    private static final float STAFF_THICKNESS = 0.08f;
    private static final float CAP_LENGTH = 0.16f;

    // the active
    private static final double AIM_ASSIST = 0.25;
    private static final long EXTEND_TICKS = 2;
    private static final long HOLD_TICKS = 4;
    private static final long RETRACT_TICKS = 3;
    private static final double DRIVE_SPEED = 1.25;
    private static final double DRIVE_SINK = -0.06;
    private static final double MAX_SPEED = 2.5;
    /** Every vault rises at least this much (a wall kick pops up as well as away). */
    private static final double MIN_RISE = 0.45;
    /** Air ticks a vault flies, to judge how far it carries (for the arena border). */
    private static final double VAULT_AIR_TICKS = 14;

    // the pillar
    private static final float PILLAR_THICKNESS = 1.1f;
    private static final float BAND_THICKNESS = 1.22f;
    private static final long TOPPLE_TICKS = 10;
    private static final long LIE_TICKS = 10;
    private static final long SHRINK_TICKS = 8;
    private static final double PILLAR_OFFSET = 1.6;
    private static final double REACH_UP = 2.6;
    private static final double REACH_DOWN = 1.5;
    private static final double CRACK_STEP = 2.5;

    // ------------------------------------------------------------------ state

    private final List<Display> displays = new ArrayList<>();
    private final LandingGuard landing = new LandingGuard();
    private final List<BlockDisplay> backStaff = new ArrayList<>();
    /** The staff is in use (extended or a pillar): the one on the back is hidden. */
    private int inUse;
    private float backYaw = Float.NaN;
    private long lastSweep = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        backStaff.clear();
        displays.clear();
        landing.disarm();
        inUse = 0;
    }

    // ================================================================== passive: the staff sweep

    @Override
    public void onTick(long tick) {
        Player owner = player();
        landing.update(owner, tick);
        followBack(owner);
        if (tick % 20 == 0 && inUse == 0) {
            Location tip = backSpot(owner).add(0, 0.75, 0);
            Vfx.dot(Vfx.scatter(tip, 0.15), Particle.DUST, GOLD_DUST, false);
        }
    }

    /** The staff rides diagonally across the sage's back, turned with his body. */
    private void followBack(Player owner) {
        backStaff.removeIf(part -> !part.isValid());
        if (backStaff.isEmpty()) {
            if (!reserve(3)) {
                return;
            }
            Location at = backSpot(owner);
            backYaw = owner.getBodyYaw();
            for (int i = 0; i < 3; i++) {
                BlockDisplay part = keep(Displays.block(ctx(), owner, at, (i == 0 ? SHAFT : CAP).createBlockData(),
                        backPose(i, backYaw, inUse == 0), 0));
                part.setTeleportDuration(FOLLOW_GLIDE);
                part.setViewRange(1.0f);
                backStaff.add(part);
            }
            return;
        }
        Location at = backSpot(owner);
        float body = owner.getBodyYaw();
        boolean turn = Float.isNaN(backYaw) || Math.abs(body - backYaw) > 3;
        if (turn) {
            backYaw = body;
        }
        for (int i = 0; i < backStaff.size(); i++) {
            BlockDisplay part = backStaff.get(i);
            Displays.moveTo(part, at);
            if (turn) {
                Displays.animate(part, backPose(i, backYaw, inUse == 0), 2);
            }
        }
    }

    private void showBack(boolean shown) {
        for (int i = 0; i < backStaff.size(); i++) {
            BlockDisplay part = backStaff.get(i);
            if (part.isValid()) {
                Displays.animate(part, backPose(i, Float.isNaN(backYaw) ? 0 : backYaw, shown), 3);
            }
        }
    }

    private static Location backSpot(Player owner) {
        Vector behind = rotate(new Vector(0, 0, -0.24), owner.getBodyYaw());
        return Displays.unrotated(owner.getLocation().add(behind).add(0, owner.getHeight() * 0.62, 0));
    }

    /** Part {@code index} (0 shaft, 1 and 2 the gold caps) of the staff on the back, slanted across it. */
    private static Transformation backPose(int index, float yaw, boolean shown) {
        float grow = shown ? 1f : 0.001f;
        float length = BACK_LENGTH * grow;
        // Slanted across the back: the shaft's own axis is local Z turned 90 degrees (across) and tilted 40.
        float axisYaw = yaw + 90;
        float pitch = -40;
        if (index == 0) {
            return Displays.transform(0, 0, 0, STAFF_THICKNESS * grow, STAFF_THICKNESS * grow, length, axisYaw, pitch,
                    0);
        }
        Vector along = direction(axisYaw, pitch).multiply((index == 1 ? 1 : -1) * (length / 2 - CAP_LENGTH * grow / 2));
        float cap = (STAFF_THICKNESS + 0.03f) * grow;
        return Displays.transform((float) along.getX(), (float) along.getY(), (float) along.getZ(), cap, cap,
                CAP_LENGTH * grow, axisYaw, pitch, 0);
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        Player owner = player();
        if (ctx().isAbilityDamage() || !owner.equals(event.getDamager()) || event.getCause() != DamageCause.ENTITY_ATTACK) {
            return;
        }
        double share = Math.clamp(params().num("sweep_share") / 100.0, 0.0, 1.0);
        double radius = Math.max(0, params().num("sweep_radius"));
        double amount = event.getFinalDamage() * share;
        List<LivingEntity> beside = new ArrayList<>();
        if (amount > 0 && radius > 0) {
            for (LivingEntity other : ctx().enemiesNear(owner, Fx.body(victim), radius + victim.getWidth() / 2)) {
                if (!other.equals(victim)) {
                    beside.add(other);
                }
            }
        }
        sweepVisual(owner, victim, beside);
        if (beside.isEmpty()) {
            return;
        }
        ctx().runLater(owner, 1, () -> {
            for (LivingEntity other : beside) {
                if (Combat.isTargetable(ctx(), owner, other)) {
                    Combat.hit(ctx(), owner, other, amount);
                    if (!isHidden(owner, other)) {
                        Location body = Fx.body(other);
                        Vfx.burst(ctx(), body, GOLDEN, 0.4, Particle.CRIT, false);
                    }
                }
            }
            Fx.sound(Fx.body(victim), Sound.BLOCK_BAMBOO_WOOD_HIT, 1.0f, 0.8f + Fx.jitter(0.1f));
        });
    }

    /** A golden arc where the staff swept, with a line to each enemy it clipped beside the one struck. */
    private void sweepVisual(Player owner, LivingEntity victim, List<LivingEntity> beside) {
        long now = ctx().currentTick();
        if (now - lastSweep < FEEDBACK_COOLDOWN && beside.isEmpty()) {
            return;
        }
        lastSweep = now;
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        Vector toward = Motion.horizontal(victim.getLocation().toVector().subtract(owner.getLocation().toVector()));
        float yaw = toward.lengthSquared() > 0 ? yawOf(toward) : owner.getLocation().getYaw();
        double reach = Math.max(1.6, owner.getLocation().distance(victim.getLocation()));
        Vfx.arc(ctx(), chest, reach, yaw, 140, 14, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
        Vfx.arc(ctx(), chest.clone().add(0, -0.1, 0), reach * 0.85, yaw, 110, 9, Particle.DUST, RED_DUST, false);
        for (LivingEntity other : beside) {
            if (!isHidden(owner, other)) {
                Vfx.trail(Fx.body(victim), Fx.body(other), GOLD, 4, false);
            }
        }
        Fx.sound(chest, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.25f + Fx.jitter(0.1f));
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        landing.absorb(event, ctx().currentTick());
    }

    // ================================================================== active: grow, staff!

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector dir = eye.getDirection();
        if (dir.lengthSquared() < 1.0E-8) {
            return ActivationResult.fail("바라보는 방향을 알 수 없습니다");
        }
        dir.normalize();
        double length = Math.max(1, params().num("pole_length"));
        World world = eye.getWorld();
        RayTraceResult wall = world.rayTraceBlocks(eye, dir, length, FluidCollisionMode.NEVER, true);
        double blockDistance = wall == null ? length : wall.getHitPosition().distance(eye.toVector());
        LivingEntity skewered = null;
        double enemyDistance = Double.MAX_VALUE;
        for (LivingEntity enemy : ctx().enemiesNear(owner, eye, length + 1)) {
            if (isHidden(owner, enemy)) {
                continue;
            }
            BoundingBox box = enemy.getBoundingBox().expand(AIM_ASSIST);
            double distance;
            if (box.contains(eye.toVector())) {
                distance = 0;
            } else {
                RayTraceResult hit = box.rayTrace(eye.toVector(), dir, length);
                if (hit == null) {
                    continue;
                }
                distance = hit.getHitPosition().distance(eye.toVector());
            }
            if (distance < enemyDistance && distance <= blockDistance + 1.0E-6) {
                enemyDistance = distance;
                skewered = enemy;
            }
        }
        Location hand = hand(owner);
        Fx.sound(eye, Sound.ITEM_TRIDENT_RIPTIDE_1, 1.0f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(eye, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 1.2f);
        Vfx.cloud(ctx(), hand, Particle.CLOUD, 8, 0.15, 0.04, null, false);
        if (skewered != null) {
            new Drive(owner, hand, dir, skewered, enemyDistance, wall != null, blockDistance).start();
            return ActivationResult.success();
        }
        Pole pole = new Pole(owner, hand, dir, blockDistance, null);
        pole.start();
        if (wall != null && wall.getHitBlockFace() != null) {
            vault(owner, dir, wall);
        } else {
            missed(owner);
        }
        return ActivationResult.success();
    }

    /** The staff hit only air: it snaps back and part of the cooldown returns. */
    private void missed(Player owner) {
        double refund = Math.clamp(params().num("miss_refund"), 0, 100) / 100.0;
        ctx().runLater(owner, 1, () -> {
            double left = ctx().cooldownRemaining(owner);
            if (left > 0 && refund > 0) {
                ctx().reduceCooldown(owner, left * refund);
            }
        });
        owner.sendActionBar(Text.mm("<gray>여의봉이 허공을 찔렀습니다</gray> <dark_gray>(쿨타임 "
                + AbilityInfo.formatNumber(refund * 100) + "% 반환)</dark_gray>"));
    }

    /**
     * The staff plants on the face it hit and the sage vaults off it: along the look direction mirrored by the face (a
     * ball bouncing off it), so a wall kicks him back and up, a floor ahead throws him forward and up, and the floor
     * straight below throws him straight up.
     */
    private void vault(Player owner, Vector dir, RayTraceResult wall) {
        BlockFace face = wall.getHitBlockFace();
        Vector normal = face == null ? new Vector(0, 1, 0) : face.getDirection();
        Vector bounce = dir.clone().subtract(normal.clone().multiply(2 * dir.dot(normal)));
        if (bounce.getY() < MIN_RISE) {
            bounce.setY(MIN_RISE);
        }
        bounce.normalize();
        double speed = Motion.launchSpeed(Math.max(0.5, params().num("vault_height")));
        Vector velocity = bounce.multiply(speed);
        // Never out of the arena: the horizontal part shrinks to what fits inside the border.
        Vector flat = new Vector(velocity.getX(), 0, velocity.getZ());
        double carry = flat.length() * VAULT_AIR_TICKS;
        if (carry > 0.1) {
            double reach = Spots.arenaReach(ctx(), owner.getLocation(), flat, carry);
            if (reach < carry) {
                double keep = reach / carry;
                velocity.setX(velocity.getX() * keep);
                velocity.setZ(velocity.getZ() * keep);
            }
        }
        owner.setVelocity(Motion.clamp(velocity, MAX_SPEED));
        owner.setFallDistance(0);
        landing.arm(ctx().currentTick(), 100);
        Location feet = owner.getLocation().add(0, 0.2, 0);
        Location contact = wall.getHitPosition().toLocation(owner.getWorld());
        Vfx.cloud(ctx(), feet, Particle.CLOUD, 14, 0.4, 0.05, null, false);
        Vfx.ring(ctx(), feet, 0.9, 14, 0, Particle.DUST, CLOUD_DUST, false);
        Vfx.burst(ctx(), contact, GOLDEN, 0.5, Particle.CRIT, false);
        Block block = wall.getHitBlock();
        if (block != null && !block.getType().isAir()) {
            owner.getWorld().spawnParticle(Particle.BLOCK, contact, Vfx.n(ctx(), 12), 0.2, 0.2, 0.2, 0.1,
                    block.getBlockData());
        }
        Fx.sound(feet, Sound.ENTITY_GOAT_LONG_JUMP, 1.0f, 1.1f + Fx.jitter(0.1f));
        Fx.sound(contact, Sound.BLOCK_BAMBOO_WOOD_HIT, 1.0f, 0.7f);
        Fx.sound(feet, Sound.ENTITY_BREEZE_JUMP, 0.8f, 1.2f);
        // A trail of cloud behind the sage while he flies.
        Loop.repeat(ctx(), owner, 1, 1, 12, run -> {
            Location body = owner.getLocation().add(0, 0.6, 0);
            Vfx.dot(Vfx.scatter(body, 0.3), Particle.DUST, CLOUD_DUST, false);
            if (run % 2 == 0) {
                Vfx.dot(body, Particle.CLOUD, null, false);
            }
            return true;
        });
    }

    /**
     * The extended staff: a red shaft from the hand with a gold cap on its tip, grown at once (or as far as it drives a
     * skewered enemy), held a moment and pulled back.
     */
    private final class Pole implements LongPredicate {

        private final Player owner;
        private final Location base;
        private final Vector dir;
        private final float yaw;
        private final float pitch;
        private final @Nullable Drive drive;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private double length;
        private long held;
        private boolean retracting;
        private long retractRun;

        Pole(Player owner, Location base, Vector dir, double length, @Nullable Drive drive) {
            this.owner = owner;
            this.base = Displays.unrotated(base);
            this.dir = dir.clone();
            this.yaw = yawOf(dir);
            double horizontal = Math.sqrt(dir.getX() * dir.getX() + dir.getZ() * dir.getZ());
            this.pitch = (float) Math.toDegrees(Math.atan2(-dir.getY(), horizontal));
            this.length = Math.max(0.3, length);
            this.drive = drive;
        }

        void start() {
            inUse++;
            showBack(false);
            if (reserve(2)) {
                parts.add(keep(Displays.block(ctx(), owner, base, SHAFT.createBlockData(), shaft(0.3), 40)));
                parts.add(keep(Displays.block(ctx(), owner, base, CAP.createBlockData(), cap(0.3), 40)));
                for (BlockDisplay part : parts) {
                    part.setViewRange(2.0f);
                }
            }
            if (drive == null) {
                ctx().runLater(owner, 1, () -> grow(length, (int) EXTEND_TICKS));
            }
            Loop.repeat(ctx(), owner, 1, 1, 40, this, this::done);
        }

        /** The shaft from the hand to {@code reach}: its box starts at the hand, so only its length interpolates. */
        private Transformation shaft(double reach) {
            float t = STAFF_THICKNESS * 1.4f;
            return along(-t / 2, -t / 2, 0, t, t, (float) reach);
        }

        private Transformation cap(double reach) {
            float t = STAFF_THICKNESS * 1.4f + 0.05f;
            return along(-t / 2, -t / 2, (float) reach - CAP_LENGTH * 1.5f, t, t, CAP_LENGTH * 1.5f);
        }

        /** A box at local {@code (x, y, z)} of size {@code (sx, sy, sz)} in the staff's frame (z along the staff). */
        private Transformation along(float x, float y, float z, float sx, float sy, float sz) {
            Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                    .rotateX((float) Math.toRadians(pitch));
            Vector3f translation = rotation.transform(new Vector3f(x, y, z));
            return new Transformation(translation, rotation, new Vector3f(sx, sy, sz), new Quaternionf());
        }

        void grow(double reach, int ticks) {
            length = reach;
            if (parts.size() == 2) {
                Displays.animate(parts.get(0), shaft(reach), ticks);
                Displays.animate(parts.get(1), cap(reach), ticks);
            }
        }

        Location tip() {
            return base.clone().add(dir.clone().multiply(length));
        }

        @Override
        public boolean test(long tick) {
            if (retracting) {
                retractRun++;
                return retractRun < RETRACT_TICKS + 1;
            }
            if (drive != null && !drive.over) {
                return true;
            }
            if (tick % 2 == 0) {
                Location tip = tip();
                Vfx.dot(tip, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
                Vfx.line(ctx(), base, tip, 1.5, Particle.DUST, RED_DUST, false);
            }
            if (++held >= EXTEND_TICKS + HOLD_TICKS) {
                retract();
            }
            return true;
        }

        void retract() {
            if (retracting) {
                return;
            }
            retracting = true;
            grow(0.2, (int) RETRACT_TICKS);
            Fx.sound(base, Sound.ITEM_TRIDENT_RETURN, 0.7f, 1.6f);
            Vfx.cloud(ctx(), base, Particle.CLOUD, 4, 0.1, 0.02, null, false);
        }

        void done() {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    part.remove();
                }
            }
            inUse = Math.max(0, inUse - 1);
            if (inUse == 0) {
                showBack(true);
            }
        }
    }

    /**
     * A skewered enemy driven along the staff to its end. A player flies on its own client and the server learns
     * where it is a moment later, so the drive steers by its own reckoning of where the steps it sent have carried the
     * enemy (mobs are moved by the server: their hitbox is used); the crush against the wall is judged on that path.
     */
    private final class Drive implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final Vector flat;
        private final boolean walled;
        private final boolean clientDriven;
        private final Pole pole;
        private final double startX;
        private final double startZ;
        private double distance;
        private double traveled;
        boolean over;

        Drive(Player owner, Location hand, Vector dir, LivingEntity target, double enemyDistance, boolean walled,
              double poleLength) {
            this.owner = owner;
            this.target = target;
            this.walled = walled;
            this.clientDriven = target instanceof Player;
            Vector horizontal = Motion.horizontal(dir);
            this.flat = horizontal.lengthSquared() > 0 ? horizontal
                    : Motion.horizontal(target.getLocation().toVector().subtract(owner.getLocation().toVector()));
            Location feet = target.getLocation();
            this.startX = feet.getX();
            this.startZ = feet.getZ();
            // Drive it until the front of its body meets the staff's end (along the ground).
            double flatShare = Math.sqrt(dir.getX() * dir.getX() + dir.getZ() * dir.getZ());
            double toEnd = (poleLength - enemyDistance - AIM_ASSIST) * flatShare - 0.05;
            this.distance = Math.max(0, Spots.arenaReach(ctx(), feet, flat, Math.max(0, toEnd)));
            this.pole = new Pole(owner, hand, dir, Math.max(0.3, enemyDistance), this);
        }

        void start() {
            pole.start();
            Combat.hit(ctx(), owner, target, params().num("pole_damage"));
            ctx().runLater(owner, 1, () -> pole.grow(pole.length, 1));
            Location body = Fx.body(target);
            if (!isHidden(owner, target)) {
                Vfx.burst(ctx(), body, GOLDEN, 0.6, Particle.CRIT, false);
            }
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.BLOCK_BAMBOO_WOOD_HIT, 1.0f, 1.2f);
            Loop.repeat(ctx(), owner, 1, 1, (long) Math.ceil(distance / DRIVE_SPEED) + 3, this, this::finish);
        }

        @Override
        public boolean test(long tick) {
            if (over) {
                return false;
            }
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return false;
            }
            double remaining = distance - traveled;
            if (remaining <= 0.02) {
                arrive();
                return false;
            }
            double step = Math.min(DRIVE_SPEED, remaining);
            BoundingBox box = onPath();
            BoundingBox next = box.clone().shift(flat.getX() * step, 0, flat.getZ() * step);
            // The probe skips the feet, so sliding over the floor or a small step never counts as a wall.
            BoundingBox probe = new BoundingBox(next.getMinX() + 0.05, next.getMinY() + 0.6, next.getMinZ() + 0.05,
                    next.getMaxX() - 0.05, next.getMaxY() - 0.05, next.getMaxZ() - 0.05);
            if (target.getWorld().hasCollisionsIn(probe)) {
                pin(box);
                return false;
            }
            target.setVelocity(Motion.clamp(flat.clone().multiply(step).setY(DRIVE_SINK), MAX_SPEED));
            target.setFallDistance(0);
            traveled += step;
            pole.grow(pole.length + step, 1);
            Location body = Fx.body(target);
            Vfx.trail(body, body.clone().subtract(flat.clone().multiply(1.5)), GOLD, 5, false);
            Vfx.dot(body.clone().subtract(flat.clone().multiply(0.4)), Particle.CLOUD, null, false);
            return true;
        }

        /** The enemy's hitbox where the drive has carried it (a player by the steps sent, a mob by the server). */
        private BoundingBox onPath() {
            BoundingBox live = target.getBoundingBox();
            if (!clientDriven) {
                return live;
            }
            double half = live.getWidthX() / 2;
            double x = startX + flat.getX() * traveled;
            double z = startZ + flat.getZ() * traveled;
            return new BoundingBox(x - half, live.getMinY(), z - half, x + half, live.getMaxY(), z + half);
        }

        /** The staff's end: crushed against the wall there, or just let go in the open. */
        private void arrive() {
            if (walled) {
                pin(onPath());
                return;
            }
            over = true;
            target.setVelocity(flat.clone().multiply(0.15));
            target.setFallDistance(0);
            pole.retract();
        }

        /** Crushed between the staff and the wall. */
        private void pin(BoundingBox box) {
            over = true;
            target.setVelocity(new Vector(0, -0.05, 0));
            target.setFallDistance(0);
            Combat.hit(ctx(), owner, target, params().num("pin_damage"));
            long stagger = params().ticks("pin_stagger");
            if (stagger > 0 && Combat.isTargetable(ctx(), owner, target)) {
                double slow = Math.clamp(params().num("stagger_slow") / 100.0, 0.0, 1.0);
                ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                        stagger);
                ctx().addModifier(owner, target, Attribute.ATTACK_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, stagger);
            }
            World world = target.getWorld();
            Location body = new Location(world, box.getCenterX(), box.getCenterY(), box.getCenterZ());
            Location contact = body.clone().add(flat.clone().multiply(box.getWidthX() / 2 + 0.2));
            Block block = contact.getBlock();
            if (block.isPassable()) {
                block = contact.clone().add(flat.clone().multiply(0.5)).getBlock();
            }
            if (!block.getType().isAir()) {
                world.spawnParticle(Particle.BLOCK_CRUMBLE, contact, Vfx.n(ctx(), 18), 0.3, 0.5, 0.3, 0.1,
                        block.getBlockData());
            }
            if (!isHidden(owner, target)) {
                Vfx.dot(contact, Particle.FLASH, GOLD, false);
                Vfx.burst(ctx(), contact, IMPERIAL, 0.8, Particle.CRIT, false);
                popText(owner, body.clone().add(0, 1.3, 0), "<b><" + RED_TAG + ">쾅!</" + RED_TAG + "></b>", 0.9f, 16);
            }
            Fx.sound(contact, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(contact, Sound.BLOCK_ANVIL_LAND, 0.5f, 1.3f + Fx.jitter(0.1f));
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">⚡ 벽에 끼웠다!</" + GOLD_TAG + "> <white>+"
                    + AbilityInfo.formatNumber(params().num("pin_damage")) + "</white>"));
            pole.retract();
        }

        void finish() {
            if (!over) {
                over = true;
                if (target.isValid() && !target.isDead()) {
                    target.setVelocity(new Vector(0, -0.05, 0));
                }
                pole.retract();
            }
        }
    }

    // ================================================================== ultimate: the pillar that holds up heaven

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Vector dir = Motion.horizontalFacing(owner.getLocation());
        Location base = owner.getLocation().add(dir.clone().multiply(PILLAR_OFFSET));
        base.setY(owner.getLocation().getY() + 1.0);
        Spots.snapToGround(owner.getWorld(), base, 4);
        new Pillar(owner, Displays.unrotated(base), dir).start();
        return ActivationResult.success();
    }

    /**
     * The pillar: it rises over the rise time while its shadow is drawn on the ground, then topples along the shadow
     * (each tick a new pose, so its golden bands stay on the shaft), crashes, lies a moment and shrinks back.
     */
    private final class Pillar implements LongPredicate {

        private final Player owner;
        private final Location base;
        private final Vector dir;
        private final Vector side;
        private final float yaw;
        private final double height;
        private final double length;
        private final double halfWidth;
        private final long rise;
        private final World world;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private @Nullable BlockDisplay shadow;

        Pillar(Player owner, Location base, Vector dir) {
            this.owner = owner;
            this.base = base;
            this.dir = dir;
            this.side = new Vector(-dir.getZ(), 0, dir.getX());
            this.yaw = yawOf(dir);
            this.height = Math.max(2, params().num("pillar_height"));
            this.length = Math.max(1, params().num("pillar_length"));
            this.halfWidth = Math.max(0.25, params().num("pillar_width") / 2);
            this.rise = Math.max(2, params().ticks("pillar_rise"));
            this.world = base.getWorld();
        }

        private long total() {
            return rise + TOPPLE_TICKS + LIE_TICKS + SHRINK_TICKS + 2;
        }

        void start() {
            inUse++;
            showBack(false);
            long life = total() + 10;
            if (reserve(6)) {
                parts.add(keep(Displays.block(ctx(), owner, base, SHAFT.createBlockData(), shaft(0.05, 0), life)));
                for (int band = 0; band < 4; band++) {
                    parts.add(keep(Displays.block(ctx(), owner, base, CAP.createBlockData(), band(band, 0.05, 0),
                            life)));
                }
                for (BlockDisplay part : parts) {
                    part.setViewRange(4.0f);
                }
                Displays.outline(parts.get(1), GOLD);
                BlockDisplay plate = keep(Displays.block(ctx(), owner, base, SHADOW.createBlockData(), shadowPose(0.01),
                        life));
                plate.setViewRange(3.0f);
                Displays.outline(plate, RED);
                shadow = plate;
            }
            Location head = base.clone().add(0, 2, 0);
            Vfx.burst(ctx(), base.clone().add(0, 0.5, 0), GOLDEN, 1.0, Particle.CLOUD, true);
            Fx.sound(head, Sound.BLOCK_PISTON_EXTEND, 1.2f, 0.5f);
            Fx.sound(head, Sound.ENTITY_WARDEN_EMERGE, 0.6f, 1.4f);
            Fx.sound(head, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 0.7f);
            ctx().announce(Text.mm("<" + GOLD_TAG + ">☁</" + GOLD_TAG + "> <white><owner></white><gray>의 </gray><"
                            + RED_TAG + "><b>여의봉</b></" + RED_TAG + "><gray>이 하늘까지 솟구칩니다!</gray>",
                    Placeholder.component("owner", owner.name())));
            Loop.repeat(ctx(), owner, 1, 1, total(), this, this::end);
        }

        /** The red shaft from the ground up to {@code grown} of the full height, tipped {@code tilt} degrees. */
        private Transformation shaft(double grown, double tilt) {
            return upright(-PILLAR_THICKNESS / 2, 0, -PILLAR_THICKNESS / 2, PILLAR_THICKNESS,
                    (float) (height * grown), PILLAR_THICKNESS, tilt);
        }

        /** Gold band {@code index}: the foot, a third, two thirds and the top of the shaft. */
        private Transformation band(int index, double grown, double tilt) {
            double h = height * grown;
            double thick = index == 0 || index == 3 ? 1.3 : 0.55;
            double from = switch (index) {
                case 0 -> 0;
                case 1 -> h / 3;
                case 2 -> h * 2 / 3;
                default -> h - 1.3;
            };
            from = Math.max(0, Math.min(from, h - thick));
            return upright(-BAND_THICKNESS / 2, (float) from, -BAND_THICKNESS / 2, BAND_THICKNESS, (float) thick,
                    BAND_THICKNESS, tilt);
        }

        /**
         * A box at {@code (x, y, z)} of size {@code (sx, sy, sz)} in the standing pillar's frame (y up from the base,
         * z towards the fall), tipped {@code tilt} degrees over towards the fall: the base stays put.
         */
        private Transformation upright(float x, float y, float z, float sx, float sy, float sz, double tilt) {
            Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                    .rotateX((float) Math.toRadians(tilt));
            Vector3f translation = rotation.transform(new Vector3f(x, y, z));
            return new Transformation(translation, rotation, new Vector3f(sx, sy, sz), new Quaternionf());
        }

        /** The shadow on the ground, {@code grown} of its full length out from the base. */
        private Transformation shadowPose(double grown) {
            float w = (float) (halfWidth * 2);
            return upright(-w / 2, 0.03f, 0, w, 0.02f, (float) (length * grown), 0);
        }

        private void pose(double grown, double tilt, int ticks) {
            for (int i = 0; i < parts.size(); i++) {
                Displays.animate(parts.get(i), i == 0 ? shaft(grown, tilt) : band(i - 1, grown, tilt), ticks);
            }
        }

        @Override
        public boolean test(long tick) {
            if (tick < rise) {
                double grown = (tick + 1) / (double) rise;
                if (tick % 2 == 0) {
                    pose(Math.min(1, (tick + 2) / (double) rise), 0, 2);
                }
                BlockDisplay plate = shadow;
                if (plate != null && tick % 2 == 0) {
                    Displays.animate(plate, shadowPose(Math.min(1, (tick + 2) / (double) rise)), 2);
                }
                drawShadow(grown);
                Location top = base.clone().add(0, height * grown, 0);
                Vfx.dot(top, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, true);
                Vfx.cloud(ctx(), base.clone().add(0, 0.2, 0), Particle.CLOUD, 2, 0.6, 0.02, null, false);
                if (tick % 4 == 0) {
                    Fx.sound(base, Sound.BLOCK_PISTON_EXTEND, 0.7f, 0.5f + (float) grown * 0.6f);
                }
                return true;
            }
            long fall = tick - rise;
            if (fall < TOPPLE_TICKS) {
                // It tips slowly, then ever faster.
                double t = (fall + 1) / (double) TOPPLE_TICKS;
                pose(1, 90 * t * t, 1);
                drawShadow(1);
                if (fall == 0) {
                    Fx.sound(base, Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.0f, 0.5f);
                    Fx.sound(base, Sound.BLOCK_WOOD_BREAK, 1.2f, 0.5f);
                }
                if (fall == TOPPLE_TICKS - 1) {
                    crash();
                }
                return true;
            }
            long lie = fall - TOPPLE_TICKS;
            if (lie < LIE_TICKS) {
                if (lie % 3 == 0) {
                    Location spot = base.clone().add(dir.clone().multiply(ThreadLocalRandom.current().nextDouble(length)));
                    Vfx.cloud(ctx(), spot.add(0, 0.5, 0), Particle.CAMPFIRE_COSY_SMOKE, 2, 0.4, 0.01, null, false);
                }
                return true;
            }
            long shrink = lie - LIE_TICKS;
            if (shrink == 0) {
                pose(0.02, 90, (int) SHRINK_TICKS);
                Fx.sound(base, Sound.ITEM_TRIDENT_RETURN, 1.0f, 0.6f);
                BlockDisplay plate = shadow;
                if (plate != null && plate.isValid()) {
                    Displays.shrinkAway(ctx(), owner, plate, 6);
                }
            }
            return shrink < SHRINK_TICKS;
        }

        /** The telegraph: the pillar's shadow on the ground, dark dust with a red rim. */
        private void drawShadow(double grown) {
            double reach = length * grown;
            int points = Vfx.n(ctx(), 6);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < points; i++) {
                double along = random.nextDouble(Math.max(0.1, reach));
                double across = random.nextDouble(-halfWidth, halfWidth);
                Location at = ground(base.clone().add(dir.clone().multiply(along)).add(side.clone().multiply(across)));
                Vfx.dot(at.add(0, 0.1, 0), Particle.DUST, SHADOW_DUST, true);
            }
            for (int edge = -1; edge <= 1; edge += 2) {
                Location at = ground(base.clone().add(dir.clone().multiply(random.nextDouble(Math.max(0.1, reach))))
                        .add(side.clone().multiply(edge * halfWidth)));
                Vfx.dot(at.add(0, 0.12, 0), Particle.DUST, RED_DUST, true);
            }
        }

        private Location ground(Location at) {
            Location probe = at.clone();
            probe.setY(base.getY() + 2);
            Spots.snapToGround(world, probe, 5);
            return probe;
        }

        /** The pillar lands along its shadow: everyone under it is crushed flat. */
        private void crash() {
            double damage = params().num("pillar_damage");
            long flat = params().ticks("pillar_flat");
            double slow = Math.clamp(params().num("pillar_slow") / 100.0, 0.0, 1.0);
            int hits = 0;
            Location middle = base.clone().add(dir.clone().multiply(length / 2));
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, length / 2 + halfWidth + 3)) {
                if (!under(enemy)) {
                    continue;
                }
                hits++;
                Combat.hit(ctx(), owner, enemy, damage);
                if (flat > 0 && slow > 0 && Combat.isTargetable(ctx(), owner, enemy)) {
                    ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, flat);
                    enemy.setVelocity(new Vector(0, -0.2, 0));
                }
                if (!isHidden(owner, enemy)) {
                    Location body = Fx.body(enemy);
                    Vfx.burst(ctx(), body, IMPERIAL, 0.8, Particle.CRIT, true);
                    popText(owner, enemy.getLocation().add(0, enemy.getHeight() + 0.5, 0), "<b><" + GOLD_TAG
                            + ">납작!</" + GOLD_TAG + "></b>", 1.0f, 20);
                }
            }
            // Dust and the ground's own debris burst up all along the pillar.
            Block floor = base.clone().subtract(0, 0.5, 0).getBlock();
            for (double along = 0.5; along <= length; along += 1.5) {
                Location at = ground(base.clone().add(dir.clone().multiply(along))).add(0, 0.3, 0);
                Vfx.cloud(ctx(), at, Particle.CLOUD, 2, 0.5, 0.05, null, true);
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, RED_FADE, true);
                if (floor.getType().isSolid()) {
                    world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 5), 0.5, 0.2, 0.5, 0.15,
                            floor.getBlockData(), true);
                }
            }
            Location end = base.clone().add(dir.clone().multiply(length));
            Vfx.shockwave(ctx(), owner, middle.clone().add(0, 0.2, 0), 1, length / 2 + 1, 6, Particle.DUST,
                    CLOUD_DUST, true);
            Fx.sound(middle, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 2.0f, 0.6f);
            Fx.sound(middle, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.7f);
            Fx.sound(end, Sound.BLOCK_ANVIL_LAND, 1.0f, 0.5f);
            Fx.sound(base, Sound.ENTITY_IRON_GOLEM_DEATH, 0.8f, 0.6f);
            breakTerrain();
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>☁ 여의봉 천주</b></" + GOLD_TAG + ">"
                    + (hits > 0 ? " <white>" + hits + "명</white><gray>을 깔아뭉갰습니다</gray>" : " <gray>아무도 깔리지 "
                    + "않았습니다</gray>")));
        }

        /** True if {@code enemy}'s body is under the fallen pillar (inside the shadow, near its height). */
        private boolean under(LivingEntity enemy) {
            if (!enemy.getWorld().equals(world)) {
                return false;
            }
            BoundingBox box = enemy.getBoundingBox();
            if (box.getMinY() > base.getY() + REACH_UP || box.getMaxY() < base.getY() - REACH_DOWN) {
                return false;
            }
            Vector rel = new Vector(box.getCenterX() - base.getX(), 0, box.getCenterZ() - base.getZ());
            double along = rel.dot(dir);
            double reach = enemy.getWidth() / 2;
            if (along < -reach - 0.3 || along > length + reach) {
                return false;
            }
            return Math.abs(rel.dot(side)) <= halfWidth + reach;
        }

        /** On battle maps the ground cracks along the fall and the cover under it is smashed. */
        private void breakTerrain() {
            double radius = params().num("pillar_terrain");
            if (radius <= 0) {
                return;
            }
            Location from = base.clone().add(0, 0.6, 0);
            Location to = base.clone().add(dir.clone().multiply(length)).add(0, 0.6, 0);
            ctx().breakBlocksAlong(owner, from, to, radius);
            for (double along = CRACK_STEP; along <= length; along += CRACK_STEP) {
                ctx().breakBlocks(owner, ground(base.clone().add(dir.clone().multiply(along))), radius * 0.75);
            }
        }

        void end() {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    part.remove();
                }
            }
            BlockDisplay plate = shadow;
            if (plate != null && plate.isValid()) {
                plate.remove();
            }
            inUse = Math.max(0, inUse - 1);
            if (inUse == 0) {
                showBack(true);
            }
        }
    }

    // ================================================================== helpers

    /** The yaw (degrees) a direction faces, in the display convention (0 = +Z south, 90 = -X west). */
    private static float yawOf(Vector direction) {
        if (direction.getX() * direction.getX() + direction.getZ() * direction.getZ() < 1.0E-9) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    /** {@code local} (x left, y up, z forward) turned to face {@code yaw}. */
    private static Vector rotate(Vector local, float yaw) {
        double rad = Math.toRadians(yaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new Vector(local.getX() * cos - local.getZ() * sin, local.getY(), local.getX() * sin + local.getZ() * cos);
    }

    /** The unit vector a display yaw and pitch point along (yaw 0 = +Z, pitch positive = down). */
    private static Vector direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new Vector(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector side = rotate(new Vector(-0.32, 0, 0.25), owner.getLocation().getYaw());
        return eye.add(side).subtract(0, 0.38, 0);
    }

    /** A word that pops up over {@code at} and shrinks away, for everyone. */
    private void popText(Player owner, Location at, String miniMessage, float scale, long life) {
        if (!reserve(1)) {
            return;
        }
        TextDisplay text = keep(Displays.text(ctx(), owner, at, Text.mm(miniMessage), scale * 0.4f, life));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0, 0, 0), 4);
        ctx().runLater(owner, Math.max(2, life - 5), () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 4);
            }
        });
    }

    private <T extends Display> T keep(T display) {
        displays.add(display);
        return display;
    }

    /** Reserves room for {@code count} more displays under {@link #MAX_DISPLAYS}; false when there is none. */
    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    private static boolean isHidden(Player owner, LivingEntity entity) {
        return entity instanceof Player hidden && !owner.canSee(hidden);
    }
}
