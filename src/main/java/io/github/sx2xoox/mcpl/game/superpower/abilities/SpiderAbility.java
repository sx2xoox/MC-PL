package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 거미 — a patient trapper. Climbs walls like a spider and hangs from them, binds enemies with a shot of sticky web
 * (temporary cobwebs where it hits) and spins a huge nest on the ground that drags enemies down while the spider
 * runs across it. Whatever is caught in a web takes extra damage from the spider.
 *
 * <p>Look: silk white, ash gray and a dark spider-eye red. Climbing leaves silk at the hands; a hanging spider dangles
 * from a silk thread spun to the wall above it. The web shot is a spinning cobweb still tied to the spider's hand by a
 * silk strand for its first meters; a bound enemy is wrapped by a white spiral and hangs inside a web. The nest spins
 * out from the spider's feet as six silk spokes and two hexagonal rings around a turning cobweb hub, rimmed by a red
 * warning circle; silk pulses run outwards and sticky motes cling to everyone caught in it.
 */
public final class SpiderAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("spider", "거미", Material.COBWEB)
            .color(TextColor.color(0xC0504D))
            .difficulty(2)
            .passive("거미 다리",
                    "벽을 마주 보고 붙으면 초당 {climb_speed}블록 속도로 기어오르고, 웅크리면 거미줄을 걸고 벽에 "
                            + "매달립니다. 거미줄이나 둥지에 걸린 적에게 주는 피해가 {web_bonus}% 늘어납니다.")
            .active("거미줄 발사",
                    "끈적한 거미줄 뭉치를 쏩니다(최대 {web_range}블록). 맞은 적은 {web_damage} 피해를 입고 "
                            + "{root_duration}초간 거미줄에 묶여 움직이거나 뛸 수 없습니다. 빗나가면 닿은 자리에 "
                            + "{trap_duration}초간 거미줄 덫이 남습니다.",
                    8)
            .ultimate("거미 둥지",
                    "발밑에 반경 {nest_radius}블록의 거미 둥지를 {nest_duration}초간 칩니다. 둥지 안의 적은 이동 "
                            + "속도가 {nest_slow}%, 점프력이 {nest_jump}% 줄고 {nest_interval}초마다 {nest_damage} 피해를 "
                            + "입습니다. 나는 둥지 위에서 이동 속도가 {nest_speed}% 오릅니다.")
            .param("climb_speed", 4)
            .param("web_bonus", 30)
            .param("web_range", 24)
            .param("web_damage", 3)
            .param("root_duration", 1.5)
            .param("trap_duration", 6)
            .param("nest_radius", 7)
            .param("nest_duration", 6)
            .param("nest_slow", 50)
            .param("nest_jump", 60)
            .param("nest_damage", 1)
            .param("nest_interval", 1)
            .param("nest_speed", 30)
            .build();

    private static final String RED = "#D0504D";
    private static final double TAU = Math.PI * 2;

    // Climbing
    /** How far in front of the body a wall still counts as touched. */
    private static final double WALL_REACH = 0.12;
    /** A facing component at least this large (cos 60 degrees) makes that axis's side count as faced. */
    private static final double WALL_AXIS = 0.5;
    /** A wall is only climbed when the spider does not look steeply down (degrees of pitch). */
    private static final float MAX_CLIMB_PITCH = 40f;
    /** Speed pressing the climber against the wall, so the client keeps touching it. */
    private static final double WALL_PRESS = 0.06;
    private static final double LEDGE_HOP_UP = 0.36;
    private static final double LEDGE_HOP_FORWARD = 0.18;
    /** The cling thread is fixed this far above the spider's head, and spun anew if it would get longer than that. */
    private static final double ANCHOR_HEIGHT = 0.9;
    private static final double ANCHOR_MAX_LENGTH = 3.0;
    private static final float ANCHOR_WIDTH = 0.035f;
    private static final float TETHER_WIDTH = 0.045f;

    // Web shot
    private static final double WEB_SPEED = 1.4;
    private static final double WEB_DROP = 0.012;
    private static final double WEB_HITBOX = 0.45;
    private static final double SAMPLE_STEP = 0.45;
    private static final long STRAND_TICKS = 5;
    /** A bound enemy counts as caught in the web this long after the root ends. */
    private static final long WEB_LINGER_TICKS = 10;

    // Nest
    private static final int SPOKES = 6;
    private static final double INNER_RING = 0.55;
    private static final double NEST_HEIGHT = 2.0;
    private static final long NEST_CHECK_PERIOD = 5;
    private static final int SPIN_TICKS = 8;
    private static final int UNSPIN_TICKS = 8;
    /** Size of the cobweb hub at the nest's center, as a share of the nest radius. */
    private static final double HUB_SIZE = 0.4;
    private static final long HUB_TURN_TICKS = 10;

    // ------------------------------------------------------------------ look: silk white, ash gray, eye red
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF4F4F4, 0x9E9E9E, 0x6B0F1A);
    private static final Particle.DustOptions SILK = new Particle.DustOptions(Color.fromRGB(0xF4F4F4), 0.7f);
    private static final Particle.DustOptions SILK_FINE = new Particle.DustOptions(Color.fromRGB(0xDADADA), 0.5f);
    private static final Particle.DustOptions EYE_RED = new Particle.DustOptions(Color.fromRGB(0xE02020), 0.9f);
    private static final Particle.DustOptions WARN_RED = new Particle.DustOptions(Color.fromRGB(0xB0202A), 1.2f);
    private static final BlockData COBWEB = Material.COBWEB.createBlockData();
    private static final BlockData THREAD = Material.WHITE_CONCRETE.createBlockData();
    private static final float THREAD_WIDTH = 0.05f;
    private static final float THREAD_HEIGHT = 0.03f;
    private static final int MAX_DISPLAYS = 24;

    private final Map<UUID, Long> boundUntil = new HashMap<>();
    private boolean wasClimbing;
    private @Nullable BlockDisplay anchor;
    private @Nullable Location anchorPoint;
    private long lastClimbFx = Long.MIN_VALUE / 2;
    private int liveDisplays;
    private @Nullable Nest nest;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        boundUntil.clear();
        wasClimbing = false;
        anchor = null;
        anchorPoint = null;
        nest = null;
    }

    // ------------------------------------------------------------------ passive: spider legs

    @Override
    public void onTick(long tick) {
        Player owner = player();
        climb(owner, tick);
        if (tick % 40 == 0 && !boundUntil.isEmpty()) {
            boundUntil.values().removeIf(until -> until < tick);
        }
    }

    /**
     * Climbs a wall the owner faces and touches, like a vanilla spider: up while it keeps facing the wall, hanging
     * still while sneaking. Topping out hops the owner onto the ledge.
     */
    private void climb(Player owner, long tick) {
        GameMode mode = owner.getGameMode();
        if (owner.isFlying() || owner.isInsideVehicle() || owner.isGliding() || owner.isSwimming()
                || (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE)) {
            wasClimbing = false;
            return;
        }
        Location at = owner.getLocation();
        Vector facing = Motion.horizontalFacing(at);
        boolean wall = at.getPitch() <= MAX_CLIMB_PITCH && touchesWall(owner, facing);
        if (!wall) {
            if (wasClimbing && !owner.isSneaking() && !Motion.isGrounded(owner)) {
                // Topped out: hop over the ledge.
                owner.setVelocity(facing.clone().multiply(LEDGE_HOP_FORWARD).setY(LEDGE_HOP_UP));
                Fx.sound(at, Sound.ENTITY_SPIDER_STEP, 0.6f, 1.2f + Fx.jitter(0.1f));
            }
            wasClimbing = false;
            releaseAnchor(owner);
            return;
        }
        Vector press = facing.clone().multiply(WALL_PRESS);
        if (owner.isSneaking()) {
            if (!Motion.isGrounded(owner)) {
                owner.setVelocity(press.setY(0));
                owner.setFallDistance(0);
                wasClimbing = true;
                cling(owner, facing, tick);
            } else {
                releaseAnchor(owner);
            }
            return;
        }
        releaseAnchor(owner);
        double speed = Math.max(0, params().num("climb_speed")) / 20.0;
        if (speed <= 0) {
            return;
        }
        owner.setVelocity(press.setY(Math.min(speed, 1.0)));
        owner.setFallDistance(0);
        wasClimbing = true;
        if (tick - lastClimbFx >= 6) {
            lastClimbFx = tick;
            climbFx(owner, facing);
        }
    }

    /**
     * True if a solid block is right in front of the body, on a side the owner faces (within about 60 degrees). The
     * lowest part of the body is left out, so the floor or a step in front does not count as a wall.
     */
    private static boolean touchesWall(Player owner, Vector facing) {
        double ex = Math.abs(facing.getX()) >= WALL_AXIS ? Math.signum(facing.getX()) : 0;
        double ez = Math.abs(facing.getZ()) >= WALL_AXIS ? Math.signum(facing.getZ()) : 0;
        if (ex == 0 && ez == 0) {
            return false;
        }
        BoundingBox box = owner.getBoundingBox();
        BoundingBox probe = new BoundingBox(box.getMinX(), box.getMinY() + Math.min(0.3, box.getHeight() * 0.2),
                box.getMinZ(), box.getMaxX(), box.getMaxY() - 0.1, box.getMaxZ());
        probe.expand(ex, 0, ez, WALL_REACH);
        return owner.getWorld().hasCollisionsIn(probe);
    }

    /** Climbing: silk motes where the hands grip the wall and the tap of spider legs. */
    private void climbFx(Player owner, Vector facing) {
        Location hands = owner.getLocation().add(0, owner.getHeight() * 0.7, 0)
                .add(facing.clone().multiply(owner.getWidth() * 0.5 + 0.05));
        Vector side = new Vector(-facing.getZ(), 0, facing.getX()).multiply(0.25);
        Vfx.dot(hands.clone().add(side), Particle.DUST, SILK, false);
        Vfx.dot(hands.clone().subtract(side), Particle.DUST, SILK, false);
        Vfx.dot(hands.clone().subtract(0, owner.getHeight() * 0.5, 0), Particle.DUST, SILK_FINE, false);
        Fx.sound(hands, Sound.ENTITY_SPIDER_STEP, 0.4f, 1.1f + Fx.jitter(0.15f));
    }

    /**
     * Hanging from the wall: the spider fixes a silk thread to the wall above it and hangs from it. The thread (a thin
     * block display) stays anchored where it was spun and follows the spider's back until it lets go.
     */
    private void cling(Player owner, Vector facing, long tick) {
        Location back = owner.getLocation().add(0, owner.getHeight() * 0.8, 0);
        if (anchorPoint == null || !anchorPoint.getWorld().equals(owner.getWorld())
                || anchorPoint.distanceSquared(back) > ANCHOR_MAX_LENGTH * ANCHOR_MAX_LENGTH) {
            releaseAnchor(owner);
            Location point = owner.getLocation().add(0, owner.getHeight() + ANCHOR_HEIGHT, 0)
                    .add(facing.clone().multiply(owner.getWidth() * 0.5 + 0.02));
            point.setYaw(0);
            point.setPitch(0);
            anchorPoint = point;
            anchor = Displays.block(ctx(), owner, point, THREAD, beamPose(back.toVector().subtract(point.toVector()),
                    ANCHOR_WIDTH), 0);
            Vfx.cloud(ctx(), point, Particle.DUST, 5, 0.08, 0, SILK, false);
            Vfx.dot(point, Particle.WHITE_ASH, null, false);
            Fx.sound(point, Sound.BLOCK_COBWEB_PLACE, 0.5f, 1.3f + Fx.jitter(0.1f));
        }
        Location point = anchorPoint;
        BlockDisplay thread = anchor;
        if (thread != null && thread.isValid()) {
            Vector delta = back.toVector().subtract(point.toVector());
            if (delta.lengthSquared() > 1.0E-4) {
                Displays.animate(thread, beamPose(delta, ANCHOR_WIDTH), 1);
            }
        }
        if (tick % 12 == 0) {
            Vfx.dot(back, Particle.DUST, SILK_FINE, false);
            Vfx.dot(point, Particle.DUST, SILK, false);
        }
    }

    /** The spider lets go of its anchor thread: the thread snaps and shrinks back to the wall. */
    private void releaseAnchor(Player owner) {
        BlockDisplay thread = anchor;
        anchor = null;
        anchorPoint = null;
        if (thread != null && thread.isValid()) {
            Displays.shrinkAway(ctx(), owner, thread, 3);
        }
    }

    // ------------------------------------------------------------------ passive: prey in the web

    /** Enemies stuck in a cobweb, bound by the web shot or standing in the nest take more damage from the spider. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isWebbed(victim)) {
            return;
        }
        double bonus = Math.max(0, params().num("web_bonus")) / 100.0;
        if (bonus > 0) {
            event.setDamage(event.getDamage() * (1 + bonus));
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() || !isWebbed(victim) || !(params().num("web_bonus") > 0)) {
            return;
        }
        // Silk tears off the struggling prey.
        Location body = Fx.body(victim);
        victim.getWorld().spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 8), 0.25, 0.35, 0.25, 0.08, COBWEB);
        Vfx.cloud(ctx(), body, Particle.DUST, 5, 0.3, 0, EYE_RED, false);
        Fx.sound(body, Sound.BLOCK_COBWEB_BREAK, 0.6f, 1.2f + Fx.jitter(0.1f));
    }

    private boolean isWebbed(LivingEntity victim) {
        Long until = boundUntil.get(victim.getUniqueId());
        if (until != null && until >= ctx().currentTick()) {
            return true;
        }
        Nest current = nest;
        if (current != null && current.contains(victim)) {
            return true;
        }
        return inCobweb(victim);
    }

    /** True if any block the entity's hitbox overlaps is a cobweb. */
    private static boolean inCobweb(LivingEntity entity) {
        BoundingBox box = entity.getBoundingBox();
        World world = entity.getWorld();
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX() - 1.0E-7); x++) {
            for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY() - 1.0E-7); y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ() - 1.0E-7); z++) {
                    if (y >= world.getMinHeight() && y < world.getMaxHeight()
                            && world.getBlockAt(x, y, z).getType() == Material.COBWEB) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ active: web shot

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        WebShot shot = new WebShot(eye, direction);
        long maxTicks = (long) Math.ceil(Math.max(1, params().num("web_range")) / WEB_SPEED) + 2;
        Loop.repeat(ctx(), owner, 0, 1, maxTicks, shot, shot::expire);

        Location hand = hand(owner, direction);
        Vfx.ring(ctx(), hand.clone().add(direction.clone().multiply(0.3)), 0.25, 8, 0, Particle.DUST, SILK, false);
        Vfx.cloud(ctx(), hand, Particle.DUST, 4, 0.1, 0, SILK_FINE, false);
        Fx.sound(eye, Sound.ENTITY_SPIDER_AMBIENT, 0.8f, 1.4f + Fx.jitter(0.1f));
        Fx.sound(eye, Sound.ENTITY_LLAMA_SPIT, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.BLOCK_COBWEB_PLACE, 0.8f, 0.8f);
        return ActivationResult.success();
    }

    /** The owner's shooting hand (right side, a bit below the eyes). */
    private static Location hand(Player owner, Vector direction) {
        Vector right = new Vector(-direction.getZ(), 0, direction.getX());
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vector(1, 0, 0);
        }
        return owner.getEyeLocation().add(right.normalize().multiply(0.3)).add(0, -0.35, 0)
                .add(direction.clone().multiply(0.4));
    }

    /** A simulated glob of web flying in a slight arc until it hits an enemy or a block. */
    private final class WebShot implements LongPredicate {
        private final World world;
        private final Location position;
        private final Vector velocity;
        private final @Nullable ItemDisplay glob;
        /** The silk strand still joining the glob to the spider's hand for the first few ticks. */
        private @Nullable BlockDisplay strand;
        private double remaining;
        private boolean done;

        WebShot(Location eye, Vector direction) {
            this.world = eye.getWorld();
            this.position = eye.clone();
            this.velocity = direction.clone().multiply(WEB_SPEED);
            this.remaining = Math.max(1, params().num("web_range"));
            long lifetime = (long) Math.ceil(remaining / WEB_SPEED) + 6;
            Location spawn = hand(player(), direction);
            spawn.setYaw(0);
            spawn.setPitch(0);
            this.glob = reserveDisplays(1, lifetime)
                    ? Displays.item(ctx(), player(), spawn, new ItemStack(Material.COBWEB),
                    Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f, 0, 0, 0), lifetime)
                    : null;
            if (glob != null) {
                glob.setTeleportDuration(1);
            }
            Location hand = flat(hand(player(), direction));
            this.strand = reserveDisplays(1, STRAND_TICKS + 6)
                    ? Displays.block(ctx(), player(), hand, THREAD,
                    beamPose(direction.clone().multiply(0.3), TETHER_WIDTH), STRAND_TICKS + 6)
                    : null;
            if (strand != null) {
                strand.setTeleportDuration(1);
            }
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            double step = Math.min(velocity.length(), remaining);
            if (step <= 1.0E-3) {
                expire();
                return false;
            }
            Vector direction = velocity.clone().normalize();
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.NEVER, true);
            double travel = wall == null ? step : wall.getHitPosition().distance(position.toVector());
            int samples = Math.max(1, (int) Math.ceil(travel / SAMPLE_STEP));
            for (int i = 1; i <= samples; i++) {
                Location probe = position.clone().add(direction.clone().multiply(travel * i / samples));
                List<LivingEntity> enemies = ctx().enemiesNear(player(), probe, WEB_HITBOX);
                if (!enemies.isEmpty()) {
                    hitEnemy(probe, enemies.getFirst());
                    return false;
                }
            }
            if (wall != null) {
                hitBlock(wall, direction);
                return false;
            }
            Location from = position.clone();
            position.add(direction.clone().multiply(step));
            remaining -= step;
            velocity.setY(velocity.getY() - WEB_DROP);
            fly(from, tick);
            return true;
        }

        private void fly(Location from, long tick) {
            if (glob != null && glob.isValid()) {
                Location at = position.clone();
                at.setYaw(0);
                at.setPitch(0);
                glob.teleport(at);
                float spin = (tick * 47f) % 360f;
                Displays.animate(glob, Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f, spin, spin * 0.6f, 0), 1);
            }
            Vfx.line(ctx(), from, position, 1.2, Particle.DUST, SILK, false);
            BlockDisplay line = strand;
            if (line != null && line.isValid()) {
                if (tick + 1 < STRAND_TICKS) {
                    // The silk strand still connects the glob to the spider's hand.
                    Location hand = flat(hand(player(), velocity.clone().normalize()));
                    line.teleport(hand);
                    Displays.animate(line, beamPose(position.toVector().subtract(hand.toVector()), TETHER_WIDTH), 1);
                } else {
                    snapStrand();
                }
            }
            if (tick % 3 == 0) {
                Vfx.dot(from, Particle.WHITE_ASH, null, false);
            }
        }

        void expire() {
            if (done) {
                return;
            }
            done = true;
            removeGlob();
            splat(position.clone(), 0.6f);
        }

        private void removeGlob() {
            if (glob != null && glob.isValid()) {
                glob.remove();
            }
            snapStrand();
        }

        /** The strand comes loose from the hand and shrinks away. */
        private void snapStrand() {
            BlockDisplay line = strand;
            strand = null;
            if (line != null && line.isValid()) {
                Displays.shrinkAway(ctx(), player(), line, 3);
            }
        }

        private void hitEnemy(Location at, LivingEntity target) {
            done = true;
            removeGlob();
            splat(at, 1.0f);
            Player owner = player();
            Combat.hit(ctx(), owner, target, params().num("web_damage"));
            if (Combat.isTargetable(ctx(), owner, target)) {
                bind(target);
            }
        }

        private void hitBlock(RayTraceResult wall, Vector direction) {
            done = true;
            removeGlob();
            Location at = wall.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.15));
            splat(at, 0.8f);
            Block hit = wall.getHitBlock();
            BlockFace face = wall.getHitBlockFace();
            if (hit == null || face == null) {
                return;
            }
            long duration = params().ticks("trap_duration");
            Block trap = hit.getRelative(face);
            if (duration > 0 && ctx().placeTempBlock(trap, COBWEB, duration)) {
                Location center = trap.getLocation().add(0.5, 0.5, 0.5);
                Vfx.cloud(ctx(), center, Particle.BLOCK, 10, 0.3, 0.05, COBWEB, false);
                Fx.sound(center, Sound.BLOCK_COBWEB_PLACE, 1.0f, 0.9f + Fx.jitter(0.1f));
                player().sendActionBar(Text.mm("<" + RED + ">거미줄 덫</" + RED + "> <gray>"
                        + AbilityInfo.formatNumber(params().num("trap_duration")) + "초</gray>"));
            }
        }
    }

    /** The glob bursts: silk shreds, a puff of web and a sticky sound. */
    private void splat(Location at, float size) {
        World world = at.getWorld();
        world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), (int) (14 * size)), 0.25 * size, 0.25 * size,
                0.25 * size, 0.1, COBWEB);
        world.spawnParticle(Particle.DUST, at, Vfx.n(ctx(), (int) (10 * size)), 0.3 * size, 0.3 * size, 0.3 * size,
                0, SILK);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, Vfx.n(ctx(), (int) (6 * size)), 0.25 * size,
                0.25 * size, 0.25 * size, 0, PALETTE.fade(0.9f));
        Vfx.dot(at, Particle.FLASH, PALETTE.core(), false);
        Fx.sound(at, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.BLOCK_COBWEB_HIT, 1.0f, 0.8f + Fx.jitter(0.1f));
    }

    /**
     * Binds {@code target}: it cannot move or jump for the root duration, a temporary cobweb grows in its cell, a
     * white spiral wraps it and a web hangs on its body until it breaks free.
     */
    private void bind(LivingEntity target) {
        Player owner = player();
        long ticks = params().ticks("root_duration");
        if (ticks <= 0) {
            return;
        }
        boundUntil.put(target.getUniqueId(), ctx().currentTick() + ticks + WEB_LINGER_TICKS);
        target.setVelocity(new Vector());
        ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, ticks);
        ctx().addModifier(owner, target, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, ticks);
        ctx().placeTempBlock(target.getLocation().getBlock(), COBWEB, ticks);

        Location feet = target.getLocation();
        Vfx.helix(ctx(), feet, target.getWidth() * 0.6 + 0.15, target.getHeight(), 2.5, 2, 12,
                ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST, SILK, false);
        Fx.sound(feet, Sound.BLOCK_COBWEB_PLACE, 1.0f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.ENTITY_SPIDER_AMBIENT, 0.7f, 0.8f + Fx.jitter(0.1f));
        if (target instanceof Player victim) {
            victim.sendActionBar(Text.mm("<gray>거미줄에 묶였습니다!</gray>"));
        }
        owner.sendActionBar(Text.mm("<" + RED + ">명중!</" + RED + "> <gray>" + target.getName() + " 속박 "
                + AbilityInfo.formatNumber(params().num("root_duration")) + "초</gray>"));

        BlockDisplay web = reserveDisplays(1, ticks + 4) ? Displays.block(ctx(), owner, flat(Fx.body(target)), COBWEB,
                Displays.scaled(0.2f), ticks + 4) : null;
        float size = (float) Math.max(target.getWidth() + 0.5, target.getHeight() * 0.8);
        if (web != null) {
            web.setBrightness(null);
            Displays.animateNextTick(ctx(), owner, web, Displays.scaled(size), 4);
        }
        Loop.repeat(ctx(), owner, 2, 2, Math.max(1, ticks / 2), run -> {
            if (!target.isValid() || target.isDead()) {
                return false;
            }
            Location body = Fx.body(target);
            if (web != null && web.isValid()) {
                web.teleport(flat(body));
            }
            if (run % 3 == 0) {
                Vfx.ring(ctx(), target.getLocation().add(0, 0.1, 0), target.getWidth() * 0.6 + 0.2, 8, run * 0.4,
                        Particle.DUST, SILK, false);
            }
            return true;
        }, () -> {
            if (web != null && web.isValid()) {
                Displays.shrinkAway(ctx(), owner, web, 3);
            }
            if (target.isValid()) {
                Location body = Fx.body(target);
                body.getWorld().spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 10), 0.3, 0.4, 0.3, 0.1, COBWEB);
                Fx.sound(body, Sound.BLOCK_COBWEB_BREAK, 0.8f, 1.0f + Fx.jitter(0.1f));
            }
        });
    }

    // ------------------------------------------------------------------ ultimate: nest

    @Override
    public ActivationResult onUltimate() {
        if (nest != null) {
            return ActivationResult.fail("이미 둥지를 치고 있습니다");
        }
        Player owner = player();
        Location center = groundBelow(owner.getLocation());
        if (center == null) {
            return ActivationResult.fail("둥지를 칠 바닥이 없습니다");
        }
        Nest spun = new Nest(center);
        nest = spun;
        spun.spin();
        return ActivationResult.success();
    }

    /** One nest: silk displays on the ground, a snare on the enemies in it and a speed boost for the spider. */
    private final class Nest {
        private final Location center;
        private final double radius;
        private final long duration;
        private final List<BlockDisplay> threads = new ArrayList<>();
        private final Map<UUID, Snare> snared = new HashMap<>();
        private @Nullable ItemDisplay hub;
        private @Nullable NamespacedKey ownerSpeed;
        private long endTick;
        private final double phase = ThreadLocalRandom.current().nextDouble(TAU);

        Nest(Location center) {
            this.center = center;
            this.radius = Math.max(1, params().num("nest_radius"));
            this.duration = Math.max(1, params().ticks("nest_duration"));
        }

        boolean contains(LivingEntity entity) {
            if (!entity.getWorld().equals(center.getWorld())) {
                return false;
            }
            Location at = entity.getLocation();
            double dy = at.getY() - center.getY();
            double dx = at.getX() - center.getX();
            double dz = at.getZ() - center.getZ();
            return dy > -1.0 && dy <= NEST_HEIGHT && dx * dx + dz * dz <= radius * radius;
        }

        void spin() {
            Player owner = player();
            endTick = ctx().currentTick() + duration;
            spinThreads(owner);
            castFx(owner);
            long interval = Math.max(1, params().ticks("nest_interval"));
            Loop.repeat(ctx(), owner, 1, 1, duration, tick -> {
                if (tick % NEST_CHECK_PERIOD == 0) {
                    snare(owner);
                }
                if (tick > 0 && tick % interval == 0) {
                    bite(owner);
                }
                ambient(owner, tick);
                if (tick % HUB_TURN_TICKS == 0) {
                    turnHub(tick);
                }
                return true;
            }, this::unspin);
        }

        /** Six silk spokes and two hexagonal rings spin out from the center over a few ticks. */
        private void spinThreads(Player owner) {
            long lifetime = duration + UNSPIN_TICKS + 4;
            double[] rings = {INNER_RING, 1.0};
            int wanted = SPOKES * (1 + rings.length);
            if (!reserveDisplays(wanted, lifetime)) {
                return;
            }
            Location base = flat(center).add(0, 0.04, 0);
            for (int i = 0; i < SPOKES; i++) {
                double angle = phase + i * TAU / SPOKES;
                threads.add(thread(owner, base, angle, 0, radius, lifetime, 0));
            }
            for (int r = 0; r < rings.length; r++) {
                double ringRadius = radius * rings[r];
                for (int i = 0; i < SPOKES; i++) {
                    double a = phase + i * TAU / SPOKES;
                    double b = phase + (i + 1) * TAU / SPOKES;
                    Vector p = new Vector(Math.cos(a) * ringRadius, 0, Math.sin(a) * ringRadius);
                    Vector q = new Vector(Math.cos(b) * ringRadius, 0, Math.sin(b) * ringRadius);
                    Vector mid = p.clone().add(q).multiply(0.5);
                    Vector along = q.clone().subtract(p);
                    double segmentAngle = Math.atan2(along.getZ(), along.getX());
                    threads.add(thread(owner, base.clone().add(mid), segmentAngle, -along.length() / 2,
                            along.length() / 2, lifetime, 2 + r * 2));
                }
            }
            hub = spinHub(owner, lifetime);
        }

        /** The hub of the web: a big cobweb lying flat on the center, unfolding and then turning slowly. */
        private @Nullable ItemDisplay spinHub(Player owner, long lifetime) {
            if (!reserveDisplays(1, lifetime)) {
                return null;
            }
            float size = (float) Math.min(3.0, radius * HUB_SIZE);
            float yaw = (float) Math.toDegrees(phase);
            ItemDisplay web = Displays.item(ctx(), owner, flat(center).add(0, 0.06, 0), new ItemStack(Material.COBWEB),
                    Displays.centered(0, 0, 0, 0.1f, 0.1f, 0.1f, yaw, 90, 0), lifetime);
            Displays.animateNextTick(ctx(), owner, web, Displays.centered(0, 0, 0, size, size, size, yaw + 30, 90,
                    0), SPIN_TICKS);
            return web;
        }

        /** Turns the hub a little further (called every {@link #HUB_TURN_TICKS}). */
        private void turnHub(long tick) {
            ItemDisplay web = hub;
            if (web == null || !web.isValid() || tick < SPIN_TICKS) {
                return;
            }
            float size = (float) Math.min(3.0, radius * HUB_SIZE);
            float yaw = (float) Math.toDegrees(phase) + 30 + tick * 1.5f;
            Displays.animate(web, Displays.centered(0, 0, 0, size, size, size, yaw, 90, 0), (int) HUB_TURN_TICKS);
        }

        /**
         * A thin silk thread lying on the ground at {@code at} along {@code angle} (radians from +X), from
         * {@code from} to {@code to} blocks along that direction; it grows in from its start after {@code delay}
         * ticks.
         */
        private BlockDisplay thread(Player owner, Location at, double angle, double from, double to, long lifetime,
                                    int delay) {
            float yaw = (float) Math.toDegrees(angle) - 90f;
            double length = Math.max(0.05, to - from);
            Vector dir = new Vector(Math.cos(angle), 0, Math.sin(angle));
            Vector startCenter = dir.clone().multiply(from + 0.01);
            Vector fullCenter = dir.clone().multiply((from + to) / 2);
            Transformation seed = Displays.transform((float) startCenter.getX(), 0, (float) startCenter.getZ(),
                    THREAD_WIDTH, THREAD_HEIGHT, 0.02f, yaw, 0, 0);
            Transformation grown = Displays.transform((float) fullCenter.getX(), 0, (float) fullCenter.getZ(),
                    THREAD_WIDTH, THREAD_HEIGHT, (float) length, yaw, 0, 0);
            BlockDisplay display = Displays.block(ctx(), owner, at, THREAD, seed, lifetime);
            ctx().runLater(owner, 1 + delay, () -> Displays.animate(display, grown, SPIN_TICKS - delay / 2));
            return display;
        }

        /** The cast: a red warning circle at the edge, silk bursting from the center and a hiss of spiders. */
        private void castFx(Player owner) {
            Location ground = center.clone().add(0, 0.12, 0);
            Vfx.ring(ctx(), ground, radius, (int) Math.min(56, radius * 7), phase, Particle.DUST, WARN_RED, true);
            Vfx.shockwave(ctx(), owner, ground, 0.3, radius, SPIN_TICKS, Particle.DUST, SILK, true);
            Vfx.burst(ctx(), center.clone().add(0, 0.6, 0), PALETTE, 0.9, Particle.WHITE_ASH, true);
            center.getWorld().spawnParticle(Particle.BLOCK, center.clone().add(0, 0.3, 0), Vfx.n(ctx(), 30),
                    radius * 0.3, 0.1, radius * 0.3, 0.1, COBWEB, true);
            Fx.sound(center, Sound.ENTITY_SPIDER_AMBIENT, 1.4f, 0.6f);
            Fx.sound(center, Sound.ENTITY_SPIDER_AMBIENT, 1.0f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(center, Sound.BLOCK_COBWEB_PLACE, 1.4f, 0.6f);
            Fx.sound(center, Sound.BLOCK_SCULK_SPREAD, 1.0f, 0.7f + Fx.jitter(0.05f));
        }

        /** Enemies in the nest are snared (slow, heavy legs); the spider runs faster across its own silk. */
        private void snare(Player owner) {
            long left = Math.max(1, endTick - ctx().currentTick());
            double slow = Math.clamp(params().num("nest_slow") / 100.0, 0.0, 1.0);
            double jump = Math.clamp(params().num("nest_jump") / 100.0, 0.0, 1.0);
            List<LivingEntity> inside = new ArrayList<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius + NEST_HEIGHT)) {
                if (contains(enemy)) {
                    inside.add(enemy);
                }
            }
            for (LivingEntity enemy : inside) {
                if (!snared.containsKey(enemy.getUniqueId())) {
                    NamespacedKey speedKey = slow > 0 ? ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED,
                            -slow, Operation.MULTIPLY_SCALAR_1, left) : null;
                    NamespacedKey jumpKey = jump > 0 ? ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH,
                            -jump, Operation.MULTIPLY_SCALAR_1, left) : null;
                    snared.put(enemy.getUniqueId(), new Snare(enemy, speedKey, jumpKey));
                    Location feet = enemy.getLocation().add(0, 0.1, 0);
                    Vfx.ring(ctx(), feet, 0.6, 10, 0, Particle.DUST, SILK, false);
                    Fx.sound(feet, Sound.BLOCK_COBWEB_STEP, 1.0f, 0.8f + Fx.jitter(0.1f));
                }
            }
            Iterator<Snare> it = snared.values().iterator();
            while (it.hasNext()) {
                Snare snare = it.next();
                if (!inside.contains(snare.target())) {
                    snare.release(ctx());
                    it.remove();
                }
            }
            boolean ownerInside = contains(owner);
            double boost = params().num("nest_speed") / 100.0;
            if (ownerInside && ownerSpeed == null && Math.abs(boost) > 1.0E-6) {
                ownerSpeed = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, Math.max(-0.9, boost),
                        Operation.MULTIPLY_SCALAR_1, left);
            } else if (!ownerInside && ownerSpeed != null) {
                ctx().removeModifier(owner, ownerSpeed);
                ownerSpeed = null;
            }
        }

        /** Every interval, the nest bites the enemies caught in it. */
        private void bite(Player owner) {
            double damage = params().num("nest_damage");
            for (Snare snare : List.copyOf(snared.values())) {
                LivingEntity enemy = snare.target();
                if (!Combat.isTargetable(ctx(), owner, enemy) || !contains(enemy)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                Location body = Fx.body(enemy);
                Vfx.trail(center.clone().add(0, 0.2, 0), body, PALETTE.core(), 6, false);
                body.getWorld().spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 5), 0.25, 0.3, 0.25, 0, EYE_RED);
                Fx.sound(body, Sound.ENTITY_SPIDER_HURT, 0.4f, 1.6f + Fx.jitter(0.1f));
            }
        }

        /** Silk pulses running along the threads, sticky motes on the snared and the red rim now and then. */
        private void ambient(Player owner, long tick) {
            Location ground = center.clone().add(0, 0.1, 0);
            if (tick % 10 == 0) {
                double r = radius * ((tick / 10 % 4) + 1) / 4.0;
                Vfx.ring(ctx(), ground, r, (int) Math.max(8, r * 5), phase + tick * 0.05, Particle.DUST, SILK_FINE,
                        false);
            }
            if (tick % 20 == 0) {
                Vfx.ring(ctx(), ground.clone().add(0, 0.05, 0), radius, (int) Math.min(48, radius * 6), phase,
                        Particle.DUST, WARN_RED, true);
            }
            if (tick % 4 == 0) {
                for (Snare snare : snared.values()) {
                    LivingEntity enemy = snare.target();
                    if (enemy.isValid()) {
                        Location feet = enemy.getLocation().add(0, 0.15, 0);
                        Vfx.dot(Vfx.scatter(feet, enemy.getWidth() * 0.6), Particle.DUST, SILK, false);
                        Vfx.dot(feet.clone().add(0, enemy.getHeight() * 0.4, 0), Particle.WHITE_ASH, null, false);
                    }
                }
                if (ownerSpeed != null) {
                    Location feet = owner.getLocation().add(0, 0.2, 0);
                    Vfx.dot(feet, Particle.DUST, EYE_RED, false);
                }
            }
        }

        private void unspin() {
            Player owner = player();
            for (Snare snare : snared.values()) {
                snare.release(ctx());
            }
            snared.clear();
            if (ownerSpeed != null) {
                ctx().removeModifier(owner, ownerSpeed);
                ownerSpeed = null;
            }
            for (BlockDisplay display : threads) {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, UNSPIN_TICKS);
                }
            }
            threads.clear();
            ItemDisplay web = hub;
            hub = null;
            if (web != null && web.isValid()) {
                Displays.shrinkAway(ctx(), owner, web, UNSPIN_TICKS);
            }
            center.getWorld().spawnParticle(Particle.BLOCK, center.clone().add(0, 0.3, 0), Vfx.n(ctx(), 20),
                    radius * 0.35, 0.1, radius * 0.35, 0.05, COBWEB);
            Vfx.ring(ctx(), center.clone().add(0, 0.15, 0), radius * 0.5, 16, phase, Particle.WHITE_ASH, null, false);
            Fx.sound(center, Sound.BLOCK_COBWEB_BREAK, 1.2f, 0.7f);
            if (nest == this) {
                nest = null;
            }
        }
    }

    /** An enemy snared by the nest and the modifiers holding it. */
    private record Snare(LivingEntity target, @Nullable NamespacedKey speed, @Nullable NamespacedKey jump) {
        /** Frees the enemy early (it walked out); the modifiers would expire with the nest anyway. */
        void release(AbilityContext ctx) {
            if (speed != null) {
                ctx.removeModifier(target, speed);
            }
            if (jump != null) {
                ctx.removeModifier(target, jump);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A thin beam from the display's location along {@code delta}, {@code width} thick. */
    private static Transformation beamPose(Vector delta, float width) {
        Vector3f direction = new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ());
        float length = direction.length();
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), direction.normalize());
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, 0));
        return new Transformation(translation, rotation, new Vector3f(width, width, length), new Quaternionf());
    }

    /** The ground right below {@code at} (within 3 blocks), null over the void. */
    private static @Nullable Location groundBelow(Location at) {
        World world = at.getWorld();
        RayTraceResult down = world.rayTraceBlocks(at.clone().add(0, 0.5, 0), new Vector(0, -1, 0), 3.5,
                FluidCollisionMode.ALWAYS, true);
        return down == null ? null : down.getHitPosition().toLocation(world);
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
