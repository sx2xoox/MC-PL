package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
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
 * 갈고리술사 — hook and chain. Lands softly, throws a hook that either reels the owner to a block or drags an enemy
 * in, and as the ultimate chains every nearby enemy to the owner so nobody can run away.
 *
 * <p>Look: real chains made of iron chain block displays, laid link by link every tick. The thrown hook spins at the
 * end of its chain; the ultimate's chains shoot out to every enemy, hang slack while they stay close and snap taut
 * (with sparks) when they try to run, then shatter.
 */
public final class GrappleAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("grapple", "갈고리술사", Material.TRIPWIRE_HOOK)
            .color(TextColor.color(0xC9A86A))
            .difficulty(2)
            .passive("낙법",
                    "낙하 피해를 {fall_reduction}% 덜 받습니다. 갈고리로 날아간 뒤의 착지에는 낙하 피해를 받지 않습니다.")
            .active("갈고리",
                    "조준한 곳({range}블록 이내)으로 갈고리를 던집니다. 블록에 걸리면 그곳으로 날아가고(웅크리면 놓음), "
                            + "적에게 걸리면 {pull_damage} 피해를 주고 내 앞으로 끌어옵니다.",
                    5)
            .ultimate("사슬 결박",
                    "반경 {ult_radius}블록의 모든 적을 {ult_duration}초간 나에게 사슬로 묶고 구속 I을 겁니다. "
                            + "사슬 길이({chain_length}블록)보다 멀어지면 내 쪽으로 끌려옵니다.")
            .param("fall_reduction", 50)
            .param("range", 30)
            .param("pull_damage", 2)
            .param("ult_radius", 10)
            .param("ult_duration", 4)
            .param("chain_length", 5)
            .build();

    private static final String COLOR_TAG = "#C9A86A";
    /** Polished brass core, old gold body, dark bronze edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE8D3A0, 0xC9A86A, 0x6B5A3A);
    /** Sparks of a chain under strain. */
    private static final Particle.DustOptions TAUT_DUST =
            new Particle.DustOptions(Color.fromRGB(0xE0, 0x6A, 0x48), 0.9f);

    private static final double MAX_VELOCITY = 2.4;

    // Hook
    private static final double HOOK_SPEED = 5.0;
    private static final long MAX_THROW_TICKS = 12;
    private static final double MIN_HOOK_DISTANCE = 2.5;
    private static final double REEL_START_SPEED = 0.9;
    private static final double REEL_ACCELERATION = 0.15;
    private static final double REEL_MAX_SPEED = 1.7;
    private static final double ARRIVE_DISTANCE = 1.3;
    private static final long REEL_TIMEOUT_TICKS = 60;
    private static final double STUCK_PROGRESS = 0.06;
    private static final long STUCK_GRACE_TICKS = 4;
    private static final int STUCK_TICKS = 6;
    private static final double ARRIVAL_HOP = 0.48;
    private static final double PULL_MAX_SPEED = 1.3;
    /** The victim is dragged to a point this far in front of the owner. */
    private static final double PULL_GOAL_DISTANCE = 1.8;
    private static final double PULL_STOP_DISTANCE = 2.3;
    private static final long PULL_TIMEOUT_TICKS = 30;

    /** The landing after a reel is protected from fall damage for at most this long. */
    private static final long FALL_GRACE_CAP_TICKS = 200;

    // Chain bind
    private static final double TUG_BASE = 0.15;
    private static final double TUG_GAIN = 0.35;
    private static final double TUG_MAX = 1.3;
    private static final double CHAIN_SNAP_MARGIN = 15;

    // Visuals
    /** Preferred length of one chain link display; long chains stretch their links instead of adding more. */
    private static final double LINK_LENGTH = 0.9;
    /** Chain links are this much thicker than the block model, so they read on a stream. */
    private static final float LINK_THICKNESS = 1.5f;
    private static final int HOOK_LINKS = 12;
    /** All ultimate chains together use about this many links (2 to 6 per chain). */
    private static final int BIND_LINKS = 12;
    private static final double MAX_SAG = 1.4;
    /** The ultimate's chains need this long to reach their targets. */
    private static final long CHAIN_SHOOT_TICKS = 3;
    private static final float HOOK_SPIN = 50;

    /** Generation counter of the current hook; a new hook retires the old one. */
    private int hookToken;
    /** Cancels the fall damage of the landing after a reel. */
    private final LandingGuard landing = new LandingGuard();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: break fall

    @Override
    public void onGrant() {
        double reduction = Math.clamp(params().num("fall_reduction") / 100.0, 0.0, 1.0);
        if (reduction > 0) {
            ctx().addModifier(player(), player(), Attribute.FALL_DAMAGE_MULTIPLIER, -reduction,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1, 0);
        }
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (!landing.absorb(event, ctx().currentTick())) {
            return;
        }
        // A rolling landing: a ring of dust kicked up around the feet.
        Player owner = player();
        Location feet = flat(owner.getLocation().add(0, 0.06, 0));
        Vfx.shockwave(ctx(), owner, feet, 0.25, 1.2, 4, Particle.DUST, PALETTE.dust(0.9f), false);
        Vfx.cloud(ctx(), feet, Particle.CLOUD, 6, 0.3, 0.02, null, false);
        Block ground = feet.clone().subtract(0, 0.2, 0).getBlock();
        if (ground.getType().isSolid()) {
            feet.getWorld().spawnParticle(Particle.BLOCK_CRUMBLE, feet, Vfx.n(ctx(), 8), 0.35, 0.05, 0.35, 0,
                    ground.getBlockData());
        }
        Fx.sound(feet, Sound.BLOCK_CHAIN_STEP, 0.8f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(feet, Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.5f, 1.4f + Fx.jitter(0.1f));
    }

    @Override
    public void onTick(long tick) {
        if (tick % 2 == 0) {
            landing.update(player(), tick);
        }
    }

    // ------------------------------------------------------------------ active: hook

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 갈고리를 쓸 수 없습니다");
        }
        double range = params().num("range");
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 1.0E-6) {
            return ActivationResult.fail("갈고리를 걸 곳이 없습니다");
        }
        direction.normalize();

        LivingEntity enemy = ctx().findTarget(owner, range);
        if (enemy != null) {
            launch(new Hook(enemy, null, null));
            return ActivationResult.success();
        }

        RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        if (hit == null) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안에 갈고리를 걸 곳이 없습니다");
        }
        Vector anchor = hit.getHitPosition();
        if (anchor.distance(eye.toVector()) < MIN_HOOK_DISTANCE) {
            return ActivationResult.fail("너무 가까워 갈고리를 걸 수 없습니다");
        }
        if (!ctx().isInsideArena(anchor.toLocation(eye.getWorld()))) {
            return ActivationResult.fail("자기장 밖에는 갈고리를 걸 수 없습니다");
        }
        Block block = hit.getHitBlock();
        launch(new Hook(null, anchor, block == null ? null : block.getBlockData()));
        return ActivationResult.success();
    }

    private void launch(Hook hook) {
        Player owner = player();
        Location hand = hand(owner);
        Vfx.cloud(ctx(), hand, Particle.CRIT, 5, 0.08, 0.15, null, false);
        hand.getWorld().spawnParticle(Particle.DUST, hand, Vfx.n(ctx(), 5), 0.1, 0.1, 0.1, 0, PALETTE.dust(0.7f));
        Fx.sound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 0.7f + Fx.jitter(0.08f));
        Fx.sound(owner.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 0.7f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(owner.getLocation(), Sound.BLOCK_CHAIN_FALL, 0.8f, 1.4f + Fx.jitter(0.1f));
        Loop.repeat(ctx(), player(), 0, 1, hookLifetime(), hook);
    }

    private static long hookLifetime() {
        return MAX_THROW_TICKS + Math.max(REEL_TIMEOUT_TICKS, PULL_TIMEOUT_TICKS) + 2;
    }

    private enum Phase { THROW, REEL, PULL }

    /** A thrown hook: flies to the anchor (or homes in on the victim), latches, then reels or drags. */
    private final class Hook implements LongPredicate {

        private final int token = ++hookToken;
        private final @Nullable LivingEntity victim;
        private final @Nullable Vector anchor;
        private final @Nullable BlockData anchorBlock;
        private final ItemDisplay head;
        private final Rope rope = new Rope(HOOK_LINKS, hookLifetime() + 10);
        private Vector headPosition;
        private Phase phase = Phase.THROW;
        private long phaseTicks;
        private double lastDistance = Double.MAX_VALUE;
        private int stuckTicks;
        private float spin;

        Hook(@Nullable LivingEntity victim, @Nullable Vector anchor, @Nullable BlockData anchorBlock) {
            this.victim = victim;
            this.anchor = anchor;
            this.anchorBlock = anchorBlock;
            Player owner = player();
            Location hand = hand(owner);
            // A hand inside a wall (the owner hugging it) would stop the hook at once: throw it from the eyes then.
            Location eye = owner.getEyeLocation();
            this.headPosition = Spots.clearPath(eye, hand) ? hand.toVector() : eye.toVector();
            this.head = Displays.item(ctx(), owner, headPosition.toLocation(owner.getWorld()),
                    ItemStack.of(Material.TRIPWIRE_HOOK), Displays.centered(0, 0, 0, 0.75f, 0.75f, 0.75f, 0, 0, 0),
                    hookLifetime() + 10);
            head.setBillboard(Display.Billboard.CENTER);
            head.setTeleportDuration(1);
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            if (token != hookToken || !head.isValid() || !owner.getWorld().equals(head.getWorld())) {
                finish();
                return false;
            }
            phaseTicks++;
            boolean more = switch (phase) {
                case THROW -> fly();
                case REEL -> reel(owner);
                case PULL -> pull(owner);
            };
            if (!more) {
                finish();
            }
            return more;
        }

        private boolean fly() {
            Vector target;
            if (victim != null) {
                if (!isHookable(victim)) {
                    return false;
                }
                target = aimPoint(victim);
            } else {
                target = anchor;
            }
            if (target == null || phaseTicks > MAX_THROW_TICKS) {
                return false;
            }
            Vector previous = headPosition.clone();
            Vector toTarget = target.clone().subtract(headPosition);
            double distance = toTarget.length();
            if (victim != null && distance > 1.0E-6) {
                // The victim may duck behind cover while the hook flies: the hook stops at the first block.
                World world = head.getWorld();
                RayTraceResult wall = world.rayTraceBlocks(headPosition.toLocation(world),
                        toTarget.clone().multiply(1 / distance), Math.min(HOOK_SPEED, distance),
                        FluidCollisionMode.NEVER, true);
                if (wall != null) {
                    headPosition = wall.getHitPosition();
                    moveHead();
                    rope.lay(hand(player()).toVector(), headPosition, 0);
                    fizzle(wall.getHitBlock());
                    return false;
                }
            }
            if (distance <= HOOK_SPEED) {
                headPosition = target.clone();
                latch();
            } else {
                headPosition.add(toTarget.multiply(HOOK_SPEED / distance));
            }
            moveHead();
            rope.lay(hand(player()).toVector(), headPosition, 0);
            // A brass glint streaking behind the hook head.
            World world = head.getWorld();
            Vfx.trail(previous.toLocation(world), headPosition.toLocation(world), PALETTE.core(), 2, false);
            Vfx.dot(headPosition.toLocation(world), Particle.DUST, PALETTE.dust(0.8f), false);
            return true;
        }

        private void latch() {
            World world = head.getWorld();
            Location at = headPosition.toLocation(world);
            if (victim != null) {
                Combat.hit(ctx(), player(), victim, params().num("pull_damage"));
                Vfx.burst(ctx(), at, PALETTE, 0.45, Particle.CRIT, false);
                Vfx.ring(ctx(), flat(victim.getLocation().add(0, victim.getHeight() * 0.55, 0)), 0.6, 12, 0,
                        Particle.DUST, PALETTE.dust(1.0f), false);
                Fx.sound(at, Sound.ITEM_TRIDENT_HIT, 1.0f, 1.1f + Fx.jitter(0.1f));
                Fx.sound(at, Sound.BLOCK_CHAIN_HIT, 1.0f, 0.8f + Fx.jitter(0.1f));
                if (victim instanceof Player caught) {
                    caught.sendActionBar(Text.mm("<" + COLOR_TAG + ">⛓ 갈고리에 걸렸습니다!</" + COLOR_TAG + ">"));
                }
                phase = Phase.PULL;
            } else {
                if (anchorBlock != null && !anchorBlock.getMaterial().isAir()) {
                    world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 12), 0.15, 0.15, 0.15, 0.1, anchorBlock);
                    world.spawnParticle(Particle.BLOCK_CRUMBLE, at, Vfx.n(ctx(), 6), 0.2, 0.2, 0.2, 0, anchorBlock);
                }
                Vfx.burst(ctx(), at, PALETTE, 0.3, Particle.CRIT, false);
                Fx.sound(at, Sound.ITEM_TRIDENT_HIT_GROUND, 1.0f, 1.2f + Fx.jitter(0.1f));
                Fx.sound(at, Sound.BLOCK_CHAIN_HIT, 0.9f, 1.0f + Fx.jitter(0.1f));
                Fx.sound(player().getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 0.8f);
                phase = Phase.REEL;
            }
            phaseTicks = 0;
            lastDistance = Double.MAX_VALUE;
            stuckTicks = 0;
        }

        /** Pulls the owner towards the anchor, accelerating; sneaking lets go. */
        private boolean reel(Player owner) {
            if (anchor == null) {
                return false;
            }
            Vector toAnchor = anchor.clone().subtract(center(owner));
            double distance = toAnchor.length();
            if (distance <= ARRIVE_DISTANCE) {
                Vector hop = distance > 1.0E-3 ? toAnchor.multiply(0.25 / distance) : new Vector();
                hop.setY(Math.max(hop.getY(), 0) + ARRIVAL_HOP);
                owner.setVelocity(Motion.clamp(hop, MAX_VELOCITY));
                release(owner);
                return false;
            }
            if ((owner.isSneaking() && phaseTicks > 2) || phaseTicks > REEL_TIMEOUT_TICKS || isStuck(distance)) {
                release(owner);
                return false;
            }
            double speed = Math.min(REEL_MAX_SPEED, REEL_START_SPEED + phaseTicks * REEL_ACCELERATION);
            Vector velocity = Motion.clamp(toAnchor.multiply(Math.min(speed, distance) / distance), MAX_VELOCITY);
            owner.setVelocity(velocity);
            owner.setFallDistance(0);
            rope.lay(hand(owner).toVector(), anchor, 0);
            speedLines(owner, velocity);
            return true;
        }

        /** Drags the victim to a point just in front of the owner. */
        private boolean pull(Player owner) {
            if (victim == null || !isHookable(victim)) {
                return false;
            }
            Location ownerFeet = owner.getLocation();
            Location victimFeet = victim.getLocation();
            if (victimFeet.distance(ownerFeet) <= PULL_STOP_DISTANCE || phaseTicks > PULL_TIMEOUT_TICKS) {
                victim.setVelocity(new Vector(0, Math.min(0, victim.getVelocity().getY()), 0));
                return false;
            }
            Vector forward = ownerFeet.getDirection().setY(0);
            if (forward.lengthSquared() < 1.0E-6) {
                forward = new Vector();
            } else {
                forward.normalize().multiply(PULL_GOAL_DISTANCE);
            }
            Vector toGoal = ownerFeet.toVector().add(forward).subtract(victimFeet.toVector());
            double distance = toGoal.length();
            if (distance < 0.6 || isStuck(distance)) {
                return false;
            }
            Vector velocity = toGoal.multiply(Math.min(PULL_MAX_SPEED, distance * 0.5 + 0.3) / distance);
            if (Motion.isGrounded(victim)) {
                // A little lift so ground friction and small steps do not stop the drag.
                velocity.setY(Math.max(velocity.getY(), 0.12));
            }
            victim.setVelocity(Motion.clamp(velocity, MAX_VELOCITY));
            victim.setFallDistance(0);
            headPosition = center(victim);
            moveHead();
            Vector hand = hand(owner).toVector();
            rope.lay(hand, headPosition, 0);
            // The chain under strain: sparks at the victim, brass motes running down the chain into the hand.
            World world = head.getWorld();
            if (phaseTicks % 2 == 0) {
                Vfx.cloud(ctx(), headPosition.toLocation(world), Particle.CRIT, 2, 0.15, 0.15, null, false);
                Vfx.trail(headPosition.toLocation(world), hand.toLocation(world), PALETTE.core(), 4, false);
            }
            return true;
        }

        /** True after several ticks without progress. The first ticks are ignored: player motion lags by ping. */
        private boolean isStuck(double distance) {
            boolean progressed = lastDistance - distance >= STUCK_PROGRESS;
            lastDistance = distance;
            if (phaseTicks <= STUCK_GRACE_TICKS || progressed) {
                stuckTicks = 0;
                return false;
            }
            return ++stuckTicks >= STUCK_TICKS;
        }

        /**
         * Where the hook homes in on the victim: its centre, or its eyes when only those are in the open from the
         * hook (a victim seen over low cover). With both covered it keeps flying at the centre and hits the cover.
         */
        private Vector aimPoint(LivingEntity entity) {
            World world = head.getWorld();
            Location from = headPosition.toLocation(world);
            Vector center = center(entity);
            if (Spots.clearPath(from, center.toLocation(world))) {
                return center;
            }
            Vector eye = entity.getEyeLocation().toVector();
            return Spots.clearPath(from, eye.toLocation(world)) ? eye : center;
        }

        /** The hook clangs off a block that got in its way. */
        private void fizzle(@Nullable Block block) {
            World world = head.getWorld();
            Location at = headPosition.toLocation(world);
            if (block != null && !block.getType().isAir()) {
                world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 10), 0.12, 0.12, 0.12, 0.1,
                        block.getBlockData());
            }
            Vfx.cloud(ctx(), at, Particle.CRIT, 8, 0.12, 0.25, null, false);
            Vfx.cloud(ctx(), at, Particle.SMOKE, 3, 0.08, 0.02, null, false);
            Fx.sound(at, Sound.ITEM_TRIDENT_HIT_GROUND, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_CHAIN_HIT, 0.8f, 1.3f + Fx.jitter(0.1f));
        }

        private boolean isHookable(LivingEntity entity) {
            return Combat.isTargetable(ctx(), player(), entity)
                    && entity.getWorld().equals(head.getWorld());
        }

        /** Moves the hook head and gives it another quarter turn of spin. */
        private void moveHead() {
            head.teleport(headPosition.toLocation(head.getWorld()));
            spin += HOOK_SPIN;
            Displays.animate(head, Displays.centered(0, 0, 0, 0.75f, 0.75f, 0.75f, 0, 0, spin), 1);
        }

        /** The chain is pulled back: links and hook shrink away. */
        private void finish() {
            rope.drop(3);
            if (head.isValid()) {
                Vfx.cloud(ctx(), head.getLocation(), Particle.CRIT, 4, 0.1, 0.05, null, false);
                Displays.shrinkAway(ctx(), player(), head, 3);
            }
        }
    }

    /** Ends a reel: the owner keeps their momentum and the landing that follows deals no fall damage. */
    private void release(Player owner) {
        landing.arm(ctx().currentTick(), FALL_GRACE_CAP_TICKS);
        owner.setFallDistance(0);
        Fx.sound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(owner.getLocation(), Sound.BLOCK_CHAIN_BREAK, 0.4f, 1.6f + Fx.jitter(0.1f));
    }

    /** Wind streaks peeling off the owner while the chain reels them in. */
    private void speedLines(Player owner, Vector velocity) {
        double speed = velocity.length();
        if (speed < 0.3) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vector back = velocity.clone().multiply(-1.6 / speed);
        Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
        int lines = Vfx.n(ctx(), 2);
        for (int i = 0; i < lines; i++) {
            Location from = body.clone().add((random.nextDouble() - 0.5) * 0.8, (random.nextDouble() - 0.5) * 1.4,
                    (random.nextDouble() - 0.5) * 0.8);
            Vfx.trail(from, from.clone().add(back), i % 2 == 0 ? PALETTE.core() : Color.WHITE, 4, false);
        }
    }

    // ------------------------------------------------------------------ ultimate: chain bind

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = params().num("ult_radius");
        List<LivingEntity> enemies = ctx().enemiesNear(owner, owner.getLocation(), radius);
        if (enemies.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        long duration = Math.max(1, params().ticks("ult_duration"));
        double length = Math.max(1, params().num("chain_length"));
        int effectTicks = (int) Math.min(Integer.MAX_VALUE, duration);

        List<LivingEntity> bound = new ArrayList<>();
        for (LivingEntity enemy : enemies) {
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, effectTicks, 0, false, true, true));
            bound.add(enemy);
            Location at = enemy.getLocation().add(0, enemy.getHeight() * 0.5, 0);
            enemy.getWorld().spawnParticle(Particle.ITEM, at, Vfx.n(ctx(), 8), 0.25, 0.4, 0.25, 0.05,
                    ItemStack.of(Material.IRON_CHAIN), true);
            Fx.sound(at, Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.8f + Fx.jitter(0.1f));
            if (enemy instanceof Player victim) {
                victim.sendActionBar(Text.mm("<" + COLOR_TAG + ">⛓ 사슬에 묶였습니다!</" + COLOR_TAG + ">"));
            }
        }

        // Telegraph: a brass ring sweeps out to the edge of the bind, then the chains shoot out.
        Location origin = owner.getLocation();
        Location ground = flat(origin.clone().add(0, 0.15, 0));
        Vfx.shockwave(ctx(), owner, ground, 1.0, radius, 6, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.3f), true);
        Vfx.ring(ctx(), ground, radius, (int) Math.round(radius * 6), 0, Particle.DUST, PALETTE.dust(1.0f), true);
        origin.getWorld().spawnParticle(Particle.ITEM, origin.clone().add(0, owner.getHeight() * 0.55, 0),
                Vfx.n(ctx(), 10), 0.3, 0.3, 0.3, 0.15, ItemStack.of(Material.IRON_CHAIN), true);
        Fx.sound(origin, Sound.BLOCK_CHAIN_PLACE, 1.5f, 0.6f);
        Fx.sound(origin, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.5f, 0.7f);
        Fx.sound(origin, Sound.ENTITY_IRON_GOLEM_REPAIR, 1.0f, 0.8f);
        Fx.sound(origin, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.8f, 1.3f + Fx.jitter(0.1f));

        Loop.repeat(ctx(), player(), 0, 1, duration, new Binding(bound, length, radius, duration));
        return ActivationResult.success();
    }

    /** Keeps every bound enemy within chain length of the owner by tugging them back when the chain is taut. */
    private final class Binding implements LongPredicate {

        private final List<LivingEntity> bound;
        private final double length;
        private final double snapDistance;
        private final long duration;
        private final List<LivingEntity> taut = new ArrayList<>();
        private final Map<LivingEntity, Rope> chains = new HashMap<>();

        Binding(List<LivingEntity> bound, double length, double radius, long duration) {
            this.bound = bound;
            this.length = length;
            this.snapDistance = Math.max(length, radius) + CHAIN_SNAP_MARGIN;
            this.duration = duration;
            int links = Math.clamp(BIND_LINKS / Math.max(1, bound.size()), 2, 6);
            for (LivingEntity enemy : bound) {
                chains.put(enemy, new Rope(links, duration + 10));
            }
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            Vector anchor = owner.getLocation().toVector().add(new Vector(0, owner.getHeight() * 0.55, 0));
            Iterator<LivingEntity> it = bound.iterator();
            while (it.hasNext()) {
                LivingEntity enemy = it.next();
                if (!Combat.isTargetable(ctx(), owner, enemy)
                        || !enemy.getWorld().equals(owner.getWorld())) {
                    it.remove();
                    taut.remove(enemy);
                    dropChain(enemy, false);
                    continue;
                }
                Vector center = center(enemy);
                Vector toOwner = anchor.clone().subtract(center);
                double distance = toOwner.length();
                if (distance > snapDistance) {
                    shatter(enemy);
                    it.remove();
                    taut.remove(enemy);
                    continue;
                }
                boolean isTaut = distance > length;
                if (isTaut) {
                    double strength = Math.min(TUG_MAX, TUG_BASE + (distance - length) * TUG_GAIN);
                    Vector tug = toOwner.multiply(strength / distance);
                    if (Motion.isGrounded(enemy)) {
                        tug.setY(Math.max(tug.getY(), 0.1));
                    }
                    enemy.setVelocity(Motion.clamp(tug, MAX_VELOCITY));
                    enemy.setFallDistance(0);
                    if (!taut.contains(enemy)) {
                        taut.add(enemy);
                        Fx.sound(enemy.getLocation(), Sound.BLOCK_CHAIN_HIT, 0.9f, 0.7f + Fx.jitter(0.1f));
                        Vfx.cloud(ctx(), center.toLocation(enemy.getWorld()), Particle.CRIT, 8, 0.2, 0.3, null,
                                true);
                    }
                } else {
                    taut.remove(enemy);
                }
                drawChain(enemy, anchor, center, distance, isTaut, tick);
            }

            boolean last = tick >= duration - 1 || bound.isEmpty();
            if (tick % 10 == 0 && !last) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⛓ 사슬 결박</" + COLOR_TAG + "> <white>"
                        + bound.size() + "명</white> <gray>" + Fx.seconds(duration - tick) + "초</gray>"));
            }
            if (last) {
                bound.forEach(this::shatter);
                bound.clear();
                return false;
            }
            return true;
        }

        /**
         * Lays the chain from the owner to the enemy: it shoots out over the first ticks, hangs slack (sagging like a
         * real chain) while the enemy stays within its length and pulls straight, sparking, once it is taut.
         */
        private void drawChain(LivingEntity enemy, Vector anchor, Vector center, double distance, boolean isTaut,
                               long tick) {
            Rope rope = chains.get(enemy);
            if (rope == null) {
                return;
            }
            double reach = Math.min(1.0, (tick + 1) / (double) CHAIN_SHOOT_TICKS);
            Vector end = anchor.clone().add(center.clone().subtract(anchor).multiply(reach));
            double sag = reach < 1 || isTaut ? 0 : Math.min(MAX_SAG, Math.sqrt(3 * distance * (length - distance) / 8));
            rope.lay(anchor, end, sag);
            World world = enemy.getWorld();
            if (tick % 4 == 0) {
                // A shackle of brass dust turning around the enemy's waist.
                Vfx.ring(ctx(), flat(enemy.getLocation().add(0, enemy.getHeight() * 0.5, 0)),
                        Math.max(0.45, enemy.getWidth() * 0.85), 10, tick * 0.15, Particle.DUST, PALETTE.dust(0.8f),
                        false);
            }
            if (isTaut && tick % 2 == 0) {
                Location mid = anchor.clone().add(center.clone().subtract(anchor).multiply(0.5)).toLocation(world);
                world.spawnParticle(Particle.DUST, mid, Vfx.n(ctx(), 3), 0.25, 0.25, 0.25, 0, TAUT_DUST, true);
                Vfx.cloud(ctx(), center.toLocation(world), Particle.ELECTRIC_SPARK, 2, 0.15, 0.05, null, false);
            }
        }

        private void shatter(LivingEntity enemy) {
            dropChain(enemy, true);
            Location at = enemy.getLocation().add(0, enemy.getHeight() * 0.5, 0);
            at.getWorld().spawnParticle(Particle.ITEM, at, Vfx.n(ctx(), 10), 0.3, 0.4, 0.3, 0.12,
                    ItemStack.of(Material.IRON_CHAIN), true);
            Vfx.burst(ctx(), at, PALETTE, 0.4, Particle.CRIT, true);
            Fx.sound(at, Sound.BLOCK_CHAIN_BREAK, 1.0f, 0.9f + Fx.jitter(0.1f));
        }

        private void dropChain(LivingEntity enemy, boolean burst) {
            Rope rope = chains.remove(enemy);
            if (rope != null) {
                rope.shatter(burst);
            }
        }
    }

    // ------------------------------------------------------------------ chain displays

    /**
     * A chain of iron chain block displays from one point to another, laid again every tick. Each link is one
     * display stretched to its share of the length (at most {@code maxLinks} links), so the chain can bend into a
     * sagging curve. All links are owner-tracked and expire on their own.
     */
    private final class Rope {

        private final List<BlockDisplay> links = new ArrayList<>();
        private final int maxLinks;
        private final long lifetime;

        Rope(int maxLinks, long lifetime) {
            this.maxLinks = Math.max(1, maxLinks);
            this.lifetime = lifetime;
        }

        /** Lays the chain from {@code from} to {@code to}, hanging {@code sag} blocks low in the middle. */
        void lay(Vector from, Vector to, double sag) {
            World world = player().getWorld();
            Vector delta = to.clone().subtract(from);
            double length = delta.length();
            if (length < 0.2) {
                drop(1);
                return;
            }
            int count = Math.clamp((long) Math.ceil(length / LINK_LENGTH), 1, maxLinks);
            while (links.size() > count) {
                BlockDisplay extra = links.removeLast();
                if (extra.isValid()) {
                    extra.remove();
                }
            }
            Vector previous = point(from, delta, sag, 0);
            for (int i = 0; i < count; i++) {
                Vector next = point(from, delta, sag, (double) (i + 1) / count);
                Vector segment = next.clone().subtract(previous);
                double segmentLength = Math.max(1.0E-3, segment.length());
                segment.multiply(1 / segmentLength);
                Quaternionf rotation = new Quaternionf().rotationTo(0, 1, 0, (float) segment.getX(),
                        (float) segment.getY(), (float) segment.getZ());
                Transformation shape = link(rotation, (float) segmentLength);
                Location at = previous.toLocation(world);
                if (i < links.size()) {
                    BlockDisplay display = links.get(i);
                    if (display.isValid()) {
                        display.teleport(at);
                        Displays.animate(display, shape, 1);
                    }
                } else {
                    BlockDisplay display = Displays.block(ctx(), player(), at,
                            Material.IRON_CHAIN.createBlockData(), shape, lifetime);
                    display.setTeleportDuration(1);
                    links.add(display);
                }
                previous = next;
            }
        }

        /** Every link shrinks away. */
        void drop(int ticks) {
            for (BlockDisplay display : links) {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), player(), display, ticks);
                }
            }
            links.clear();
        }

        /** The chain bursts apart: iron bits fly off every other link and the links vanish. */
        void shatter(boolean burst) {
            if (burst) {
                for (int i = 0; i < links.size(); i += 2) {
                    BlockDisplay display = links.get(i);
                    if (display.isValid()) {
                        display.getWorld().spawnParticle(Particle.ITEM, display.getLocation(), Vfx.n(ctx(), 2),
                                0.1, 0.1, 0.1, 0.08, ItemStack.of(Material.IRON_CHAIN), true);
                    }
                }
            }
            drop(4);
        }

        private static Vector point(Vector from, Vector delta, double sag, double t) {
            return from.clone().add(delta.clone().multiply(t)).subtract(new Vector(0, sag * 4 * t * (1 - t), 0));
        }

        /** One link: the chain block's centre line runs from the display's location along {@code rotation}'s y. */
        private static Transformation link(Quaternionf rotation, float length) {
            Vector3f scale = new Vector3f(LINK_THICKNESS, length, LINK_THICKNESS);
            Vector3f anchor = new Vector3f(0.5f, 0, 0.5f).mul(scale).rotate(rotation);
            return new Transformation(anchor.negate(), rotation, scale, new Quaternionf());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Roughly where the owner holds the hook: in front of and to the right of the eyes. */
    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        Vector right = direction.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vector(1, 0, 0);
        }
        right.normalize().multiply(0.35);
        return flat(eye.add(direction.multiply(0.35)).add(right).subtract(0, 0.45, 0));
    }

    private static Vector center(Entity entity) {
        return entity.getLocation().toVector().add(new Vector(0, entity.getHeight() * 0.5, 0));
    }

    /** A copy without yaw and pitch, so display entities are not turned by the entity rotation as well. */
    private static Location flat(Location location) {
        Location copy = location.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }
}
