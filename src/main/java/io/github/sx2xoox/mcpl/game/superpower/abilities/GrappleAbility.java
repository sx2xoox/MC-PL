package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
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
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 갈고리술사 — hook and chain. Lands softly, throws a hook that either reels the owner to a block or drags an enemy
 * in, and as the ultimate chains every nearby enemy to the owner so nobody can run away.
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
    private static final Particle.DustOptions CHAIN_DUST =
            new Particle.DustOptions(Color.fromRGB(0x9A, 0xA0, 0xA8), 0.8f);
    private static final Particle.DustOptions TAUT_DUST =
            new Particle.DustOptions(Color.fromRGB(0xD0, 0x5A, 0x48), 0.9f);

    private static final double MAX_VELOCITY = 2.4;
    private static final double CHAIN_SPACING = 0.8;
    private static final int MAX_CHAIN_POINTS = 40;

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
        Location feet = player().getLocation();
        feet.getWorld().spawnParticle(Particle.CLOUD, feet.clone().add(0, 0.1, 0), 6, 0.3, 0.05, 0.3, 0.02);
        Fx.sound(feet, Sound.BLOCK_CHAIN_STEP, 0.8f, 1.2f + Fx.jitter(0.1f));
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
        Fx.sound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 0.7f + Fx.jitter(0.08f));
        Fx.sound(owner.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 0.7f, 1.3f + Fx.jitter(0.1f));
        long maxTicks = MAX_THROW_TICKS + Math.max(REEL_TIMEOUT_TICKS, PULL_TIMEOUT_TICKS) + 2;
        Loop.repeat(ctx(), player(), 0, 1, maxTicks, hook);
    }

    private enum Phase { THROW, REEL, PULL }

    /** A thrown hook: flies to the anchor (or homes in on the victim), latches, then reels or drags. */
    private final class Hook implements LongPredicate {

        private final int token = ++hookToken;
        private final @Nullable LivingEntity victim;
        private final @Nullable Vector anchor;
        private final @Nullable BlockData anchorBlock;
        private final ItemDisplay head;
        private Vector headPosition;
        private Phase phase = Phase.THROW;
        private long phaseTicks;
        private double lastDistance = Double.MAX_VALUE;
        private int stuckTicks;

        Hook(@Nullable LivingEntity victim, @Nullable Vector anchor, @Nullable BlockData anchorBlock) {
            this.victim = victim;
            this.anchor = anchor;
            this.anchorBlock = anchorBlock;
            Location hand = hand(player());
            // A hand inside a wall (the owner hugging it) would stop the hook at once: throw it from the eyes then.
            Location eye = player().getEyeLocation();
            this.headPosition = Spots.clearPath(eye, hand) ? hand.toVector() : eye.toVector();
            this.head = ctx().track(player(), hand.getWorld().spawn(hand, ItemDisplay.class, display -> {
                display.setItemStack(ItemStack.of(Material.TRIPWIRE_HOOK));
                display.setBillboard(Display.Billboard.CENTER);
                display.setTeleportDuration(1);
                display.setPersistent(false);
                display.setBrightness(new Display.Brightness(15, 15));
                // Dropped-item size: big enough to follow on stream, small enough not to block the view.
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            }));
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
                    drawChain(hand(player()).toVector(), headPosition, CHAIN_DUST);
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
            drawChain(hand(player()).toVector(), headPosition, CHAIN_DUST);
            return true;
        }

        private void latch() {
            World world = head.getWorld();
            Location at = headPosition.toLocation(world);
            if (victim != null) {
                Combat.hit(ctx(), player(), victim, params().num("pull_damage"));
                world.spawnParticle(Particle.CRIT, at, 14, 0.25, 0.3, 0.25, 0.3);
                Fx.sound(at, Sound.ITEM_TRIDENT_HIT, 1.0f, 1.1f + Fx.jitter(0.1f));
                Fx.sound(at, Sound.BLOCK_CHAIN_HIT, 1.0f, 0.8f + Fx.jitter(0.1f));
                if (victim instanceof Player caught) {
                    caught.sendActionBar(Text.mm("<" + COLOR_TAG + ">⛓ 갈고리에 걸렸습니다!</" + COLOR_TAG + ">"));
                }
                phase = Phase.PULL;
            } else {
                if (anchorBlock != null && !anchorBlock.getMaterial().isAir()) {
                    world.spawnParticle(Particle.BLOCK, at, 12, 0.15, 0.15, 0.15, 0.1, anchorBlock);
                }
                world.spawnParticle(Particle.CRIT, at, 8, 0.15, 0.15, 0.15, 0.2);
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
            owner.setVelocity(Motion.clamp(toAnchor.multiply(Math.min(speed, distance) / distance), MAX_VELOCITY));
            owner.setFallDistance(0);
            drawChain(hand(owner).toVector(), anchor, CHAIN_DUST);
            if (phaseTicks % 2 == 0) {
                Location feet = owner.getLocation();
                feet.getWorld().spawnParticle(Particle.CLOUD, feet, 1, 0.1, 0.1, 0.1, 0.01);
            }
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
            drawChain(hand(owner).toVector(), headPosition, TAUT_DUST);
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
                world.spawnParticle(Particle.BLOCK, at, 10, 0.12, 0.12, 0.12, 0.1, block.getBlockData());
            }
            world.spawnParticle(Particle.CRIT, at, 8, 0.12, 0.12, 0.12, 0.25);
            Fx.sound(at, Sound.ITEM_TRIDENT_HIT_GROUND, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_CHAIN_HIT, 0.8f, 1.3f + Fx.jitter(0.1f));
        }

        private boolean isHookable(LivingEntity entity) {
            return Combat.isTargetable(ctx(), player(), entity)
                    && entity.getWorld().equals(head.getWorld());
        }

        private void moveHead() {
            Location to = headPosition.toLocation(head.getWorld());
            head.teleport(to);
        }

        private void finish() {
            if (head.isValid()) {
                Location at = head.getLocation();
                at.getWorld().spawnParticle(Particle.CRIT, at, 4, 0.1, 0.1, 0.1, 0.05);
                head.remove();
            }
        }
    }

    /** Ends a reel: the owner keeps their momentum and the landing that follows deals no fall damage. */
    private void release(Player owner) {
        landing.arm(ctx().currentTick(), FALL_GRACE_CAP_TICKS);
        owner.setFallDistance(0);
        Fx.sound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 1.3f + Fx.jitter(0.1f));
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
            enemy.getWorld().spawnParticle(Particle.ITEM, at, 8, 0.25, 0.4, 0.25, 0.05,
                    ItemStack.of(Material.IRON_CHAIN));
            Fx.sound(at, Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.8f + Fx.jitter(0.1f));
            if (enemy instanceof Player victim) {
                victim.sendActionBar(Text.mm("<" + COLOR_TAG + ">⛓ 사슬에 묶였습니다!</" + COLOR_TAG + ">"));
            }
        }

        Location origin = owner.getLocation();
        World world = origin.getWorld();
        int points = 40;
        for (int i = 0; i < points; i++) {
            double angle = i * (Math.PI * 2 / points);
            world.spawnParticle(Particle.DUST, origin.getX() + Math.cos(angle) * radius, origin.getY() + 0.2,
                    origin.getZ() + Math.sin(angle) * radius, 1, 0, 0.05, 0, 0, CHAIN_DUST);
        }
        Fx.sound(origin, Sound.BLOCK_CHAIN_PLACE, 1.5f, 0.6f);
        Fx.sound(origin, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.5f, 0.7f);
        Fx.sound(origin, Sound.ENTITY_IRON_GOLEM_REPAIR, 1.0f, 0.8f);

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

        Binding(List<LivingEntity> bound, double length, double radius, long duration) {
            this.bound = bound;
            this.length = length;
            this.snapDistance = Math.max(length, radius) + CHAIN_SNAP_MARGIN;
            this.duration = duration;
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
                    }
                } else {
                    taut.remove(enemy);
                }
                if (tick % 2 == 0) {
                    drawChain(anchor, center, isTaut ? TAUT_DUST : CHAIN_DUST);
                }
            }

            boolean last = tick >= duration - 1 || bound.isEmpty();
            if (tick % 10 == 0 && !last) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⛓ 사슬 결박</" + COLOR_TAG + "> <white>"
                        + bound.size() + "명</white> <gray>" + Fx.seconds(duration - tick) + "초</gray>"));
            }
            if (last) {
                bound.forEach(GrappleAbility::shatter);
                bound.clear();
                return false;
            }
            return true;
        }
    }

    private static void shatter(LivingEntity enemy) {
        Location at = enemy.getLocation().add(0, enemy.getHeight() * 0.5, 0);
        at.getWorld().spawnParticle(Particle.ITEM, at, 10, 0.3, 0.4, 0.3, 0.12, ItemStack.of(Material.IRON_CHAIN));
        Fx.sound(at, Sound.BLOCK_CHAIN_BREAK, 1.0f, 0.9f + Fx.jitter(0.1f));
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
        return eye.add(direction.multiply(0.35)).add(right).subtract(0, 0.45, 0);
    }

    private static Vector center(Entity entity) {
        return entity.getLocation().toVector().add(new Vector(0, entity.getHeight() * 0.5, 0));
    }

    private void drawChain(Vector from, Vector to, Particle.DustOptions dust) {
        World world = player().getWorld();
        Vector delta = to.clone().subtract(from);
        double length = delta.length();
        if (length < 0.2) {
            return;
        }
        int points = (int) Math.min(MAX_CHAIN_POINTS, Math.ceil(length / CHAIN_SPACING));
        Vector step = delta.multiply(1.0 / points);
        Vector at = from.clone();
        for (int i = 0; i <= points; i++) {
            world.spawnParticle(Particle.DUST, at.getX(), at.getY(), at.getZ(), 1, 0, 0, 0, 0, dust);
            at.add(step);
        }
    }
}
