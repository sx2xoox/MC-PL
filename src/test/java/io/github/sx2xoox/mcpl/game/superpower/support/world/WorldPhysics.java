package io.github.sx2xoox.mcpl.game.superpower.support.world;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import org.mockbukkit.mockbukkit.entity.EntityMock;

/**
 * A small, deterministic stand-in for the server's entity tick in a {@link FlatWorld}, run once per server tick
 * <em>after</em> the scheduler (like the real server, where plugin tasks run before the worlds tick). MockBukkit has
 * no entity ticking at all, so without this a velocity set by an ability never moves anything.
 *
 * <p>Simulated, with vanilla constants:
 * <ul>
 *     <li>living entities (players included, standing in for their client): velocity integration with full-cube
 *     block collisions, gravity from the {@link Attribute#GRAVITY} attribute, slow falling, creative-style flight,
 *     vertical drag 0.98 and horizontal friction (0.546 on the ground, 0.91 in the air), fall distance and fall
 *     damage ({@link Attribute#SAFE_FALL_DISTANCE}, {@link Attribute#FALL_DAMAGE_MULTIPLIER}), burning (1 damage
 *     every 20 fire ticks) and the invulnerability-frame countdown;</li>
 *     <li>arrows: gravity 0.05, drag 0.99, block and entity hits (entity boxes inflated by 0.3, the shooter is
 *     ignored until the arrow has left it) with a {@link ProjectileHitEvent}; an uncancelled entity hit deals the
 *     vanilla {@code ceil(speed * damage)} arrow damage, an uncancelled block hit makes the arrow stick;</li>
 *     <li>primed TNT: gravity 0.04, drag 0.98, ground friction (MockBukkit itself counts the fuse down and fires an
 *     {@code ExplosionPrimeEvent} when it runs out).</li>
 * </ul>
 * Not simulated: mob AI/pathfinding, knockback, water, potion effect durations, step-up and partial blocks.
 */
public final class WorldPhysics {

    private static final double DEFAULT_GRAVITY = 0.08;
    private static final double EPSILON = 1.0E-7;

    private final FlatWorld world;
    private boolean enabled = true;
    private final List<Location> arrowImpacts = new ArrayList<>();

    WorldPhysics(FlatWorld world) {
        this.world = world;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Where arrows stuck into blocks. */
    public List<Location> arrowImpacts() {
        return arrowImpacts;
    }

    /** One server tick of entity physics. */
    public void tick() {
        if (!enabled) {
            return;
        }
        for (Entity entity : List.copyOf(world.getEntities())) {
            if (!entity.isValid() || entity.isDead() || entity instanceof Display) {
                continue;
            }
            if (entity instanceof LivingEntity living) {
                tickLiving(living);
            } else if (entity instanceof AbstractArrow arrow) {
                tickArrow(arrow);
            } else if (entity instanceof TNTPrimed tnt) {
                tickTnt(tnt);
            }
        }
    }

    // ------------------------------------------------------------------ living entities

    private void tickLiving(LivingEntity living) {
        if (living instanceof Player player && (!player.isOnline() || player.getGameMode() == GameMode.SPECTATOR)) {
            return;
        }
        if (living.getNoDamageTicks() > 0) {
            living.setNoDamageTicks(living.getNoDamageTicks() - 1);
        }
        int fire = living.getFireTicks();
        if (fire > 0) {
            if (fire % 20 == 0) {
                living.damage(1.0, DamageSource.builder(DamageType.ON_FIRE).build());
            }
            // Like vanilla: re-read after the hurt, a damage handler may have put the fire out.
            int left = living.getFireTicks();
            if (living.isValid() && left > 0) {
                living.setFireTicks(left - 1);
            }
        }
        if (living.isValid() && !living.isDead()) {
            move(living);
        }
    }

    private void move(LivingEntity living) {
        Vector velocity = living.getVelocity().clone();
        boolean flying = living instanceof Player player && player.isFlying();
        boolean groundedBefore = isGrounded(living.getBoundingBox());
        if (groundedBefore && velocity.getY() <= 0 && horizontalSquared(velocity) < 1.0E-12) {
            if (velocity.lengthSquared() > 0) {
                setVelocity(living, new Vector());
            }
            land(living);
            setOnGround(living, true);
            return;
        }

        BoundingBox box = living.getBoundingBox();
        double dy = sweep(box, 1, velocity.getY());
        box = box.shift(0, dy, 0);
        double dx = sweep(box, 0, velocity.getX());
        box = box.shift(dx, 0, 0);
        double dz = sweep(box, 2, velocity.getZ());
        box = box.shift(0, 0, dz);
        Location to = living.getLocation().add(dx, dy, dz);
        setLocation(living, to);

        boolean blockedDown = velocity.getY() < 0 && dy > velocity.getY() + EPSILON;
        boolean onGround = blockedDown || (velocity.getY() <= 0 && isGrounded(box));
        boolean slowFalling = living.hasPotionEffect(PotionEffectType.SLOW_FALLING);
        if (dy < 0 && !flying && !slowFalling) {
            living.setFallDistance(living.getFallDistance() + (float) -dy);
        } else if (flying || slowFalling) {
            living.setFallDistance(0f);
        }
        if (onGround) {
            land(living);
        }

        double vx = Math.abs(dx - velocity.getX()) > EPSILON ? 0 : velocity.getX();
        double vz = Math.abs(dz - velocity.getZ()) > EPSILON ? 0 : velocity.getZ();
        double vy = Math.abs(dy - velocity.getY()) > EPSILON ? 0 : velocity.getY();
        double gravity = gravityOf(living);
        if (slowFalling && vy <= 0) {
            gravity = Math.min(gravity, 0.01);
        }
        if (flying) {
            vy *= 0.6;
        } else {
            vy = (vy - gravity) * 0.98;
        }
        if (onGround && vy < 0) {
            vy = 0;
        }
        double friction = onGround ? 0.546 : 0.91;
        vx = tiny(vx * friction);
        vz = tiny(vz * friction);
        vy = tiny(vy);
        setVelocity(living, new Vector(vx, vy, vz));
        setOnGround(living, onGround);
    }

    /** Physics is the entity's own movement: a player's runs on its client and fires no velocity event. */
    private static void setVelocity(LivingEntity living, Vector velocity) {
        if (living instanceof ClientMovement client) {
            client.setClientVelocity(velocity);
        } else {
            living.setVelocity(velocity);
        }
    }

    /** Applies (and resets) the accumulated fall distance like vanilla {@code LivingEntity#causeFallDamage}. */
    private void land(LivingEntity living) {
        float distance = living.getFallDistance();
        if (distance <= 0) {
            return;
        }
        living.setFallDistance(0f);
        if (living instanceof Player player && player.getAllowFlight() && player.isFlying()) {
            return;
        }
        double safe = attribute(living, Attribute.SAFE_FALL_DISTANCE, 3.0);
        double multiplier = attribute(living, Attribute.FALL_DAMAGE_MULTIPLIER, 1.0);
        int damage = (int) Math.ceil((distance - safe) * multiplier);
        if (damage > 0) {
            living.damage(damage, DamageSource.builder(DamageType.FALL).build());
        }
    }

    private static double gravityOf(LivingEntity living) {
        return attribute(living, Attribute.GRAVITY, DEFAULT_GRAVITY);
    }

    private static double attribute(LivingEntity living, Attribute attribute, double fallback) {
        AttributeInstance instance = living.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    // ------------------------------------------------------------------ arrows

    private void tickArrow(AbstractArrow arrow) {
        if (arrow.isInBlock()) {
            return;
        }
        Vector velocity = arrow.getVelocity().clone();
        Location from = arrow.getLocation();
        Vector start = from.toVector();
        Entity shooter = shooterOf(arrow);
        if (arrow instanceof MockEntities.TestArrow test && !test.leftShooter) {
            test.leftShooter = shooter == null
                    || !arrowBox(start).expand(velocity.getX(), velocity.getY(), velocity.getZ()).expand(1.0)
                    .overlaps(shooter.getBoundingBox());
        }
        double length = velocity.length();
        if (length < 1.0E-9) {
            velocity.setY(velocity.getY() - 0.05);
            arrow.setVelocity(velocity);
            return;
        }
        RayTraceResult blockHit = RayTracing.blocks(world, start, velocity, length, FluidCollisionMode.NEVER, true,
                null);
        Vector end = blockHit != null ? blockHit.getHitPosition() : start.clone().add(velocity);

        Entity hitEntity = null;
        BlockFace hitFace = null;
        double travel = end.distance(start);
        double nearest = Double.MAX_VALUE;
        if (travel > 1.0E-9) {
            for (Entity candidate : world.getEntities()) {
                if (!canArrowHit(arrow, shooter, candidate)) {
                    continue;
                }
                BoundingBox box = candidate.getBoundingBox().expand(0.3);
                if (box.contains(start)) {
                    // Like vanilla AABB#clip: a box the arrow starts inside is not hit (Bukkit's rayTrace would
                    // return its exit point, so an arrow passing through someone would re-hit them every tick).
                    continue;
                }
                RayTraceResult hit = box.rayTrace(start, velocity, travel);
                if (hit != null && start.distanceSquared(hit.getHitPosition()) < nearest) {
                    nearest = start.distanceSquared(hit.getHitPosition());
                    hitEntity = candidate;
                    hitFace = hit.getHitBlockFace();
                }
            }
        }
        if (hitEntity != null) {
            ProjectileHitEvent event = new ProjectileHitEvent(arrow, hitEntity, null, hitFace);
            boolean proceed = event.callEvent();
            if (!arrow.isValid()) {
                return;
            }
            if (proceed) {
                arrowDamage(arrow, shooter, hitEntity, length);
                arrow.remove();
                return;
            }
        } else if (blockHit != null && blockHit.getHitBlock() != null) {
            ProjectileHitEvent event = new ProjectileHitEvent(arrow, null, blockHit.getHitBlock(),
                    blockHit.getHitBlockFace());
            if (event.callEvent()) {
                if (arrow.isValid()) {
                    Location stuck = end.toLocation(world, from.getYaw(), from.getPitch());
                    setLocation(arrow, stuck);
                    arrow.setVelocity(new Vector());
                    if (arrow instanceof MockEntities.TestArrow test) {
                        test.stick(blockHit.getHitBlock());
                    }
                    arrowImpacts.add(stuck);
                }
                return;
            }
        }
        if (!arrow.isValid()) {
            return;
        }
        setLocation(arrow, end.toLocation(world, from.getYaw(), from.getPitch()));
        velocity.multiply(0.99).setY(velocity.getY() - 0.05);
        arrow.setVelocity(velocity);
        if (end.getY() < world.getMinHeight() - 64) {
            arrow.remove();
        }
    }

    private static @Nullable Entity shooterOf(AbstractArrow arrow) {
        ProjectileSource source = arrow.getShooter();
        return source instanceof Entity entity ? entity : null;
    }

    private static boolean canArrowHit(AbstractArrow arrow, @Nullable Entity shooter, Entity candidate) {
        if (!(candidate instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
            return false;
        }
        if (candidate instanceof Player player && player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        boolean left = !(arrow instanceof MockEntities.TestArrow test) || test.leftShooter;
        return left || !candidate.equals(shooter);
    }

    /** Vanilla arrow hit damage: {@code ceil(speed * baseDamage)} (no critical bonus, to stay deterministic). */
    private static void arrowDamage(AbstractArrow arrow, @Nullable Entity shooter, Entity hit, double speed) {
        if (!(hit instanceof LivingEntity living)) {
            return;
        }
        int damage = (int) Math.ceil(Math.max(0, speed * arrow.getDamage()));
        if (damage <= 0) {
            return;
        }
        DamageSource.Builder source = DamageSource.builder(DamageType.ARROW).withDirectEntity(arrow);
        source.withCausingEntity(shooter != null ? shooter : arrow);
        living.damage(damage, source.build());
    }

    private static BoundingBox arrowBox(Vector at) {
        return new BoundingBox(at.getX() - 0.25, at.getY(), at.getZ() - 0.25, at.getX() + 0.25, at.getY() + 0.5,
                at.getZ() + 0.25);
    }

    // ------------------------------------------------------------------ primed TNT

    private void tickTnt(TNTPrimed tnt) {
        Vector velocity = tnt.getVelocity().clone();
        velocity.setY(velocity.getY() - 0.04);
        Location at = tnt.getLocation();
        BoundingBox box = new BoundingBox(at.getX() - 0.49, at.getY(), at.getZ() - 0.49, at.getX() + 0.49,
                at.getY() + 0.98, at.getZ() + 0.49);
        double dy = sweep(box, 1, velocity.getY());
        box = box.shift(0, dy, 0);
        double dx = sweep(box, 0, velocity.getX());
        box = box.shift(dx, 0, 0);
        double dz = sweep(box, 2, velocity.getZ());
        setLocation(tnt, at.clone().add(dx, dy, dz));
        boolean onGround = velocity.getY() < 0 && dy > velocity.getY() + EPSILON;
        double vx = Math.abs(dx - velocity.getX()) > EPSILON ? 0 : velocity.getX();
        double vz = Math.abs(dz - velocity.getZ()) > EPSILON ? 0 : velocity.getZ();
        double vy = Math.abs(dy - velocity.getY()) > EPSILON ? 0 : velocity.getY();
        Vector next = new Vector(vx, vy, vz).multiply(0.98);
        if (onGround) {
            next.multiply(new Vector(0.7, -0.5, 0.7));
        }
        tnt.setVelocity(new Vector(tiny(next.getX()), tiny(next.getY()), tiny(next.getZ())));
        // The fuse is counted down by MockBukkit's own TNTPrimedMock#tick (ExplosionPrimeEvent at zero).
    }

    // ------------------------------------------------------------------ collision helpers

    /** True if a solid block is directly below the box (touching its bottom face). */
    public boolean isGrounded(BoundingBox box) {
        BoundingBox below = new BoundingBox(box.getMinX() + 0.001, box.getMinY() - 0.01, box.getMinZ() + 0.001,
                box.getMaxX() - 0.001, box.getMinY(), box.getMaxZ() - 0.001);
        return world.hasCollisionsIn(below);
    }

    /**
     * How far {@code box} can move by {@code delta} along {@code axis} (0 = x, 1 = y, 2 = z) before touching a
     * solid cube. Blocks the box already overlaps are ignored (so a stuck entity can move out).
     */
    double sweep(BoundingBox box, int axis, double delta) {
        if (Math.abs(delta) < 1.0E-12) {
            return 0;
        }
        double low = axis == 0 ? box.getMinX() : axis == 1 ? box.getMinY() : box.getMinZ();
        double high = axis == 0 ? box.getMaxX() : axis == 1 ? box.getMaxY() : box.getMaxZ();
        int[] aRange = crossRange(box, (axis + 1) % 3);
        int[] bRange = crossRange(box, (axis + 2) % 3);
        if (delta > 0) {
            int first = (int) Math.ceil(high - EPSILON);
            int last = (int) Math.floor(high + delta);
            for (int cell = first; cell <= last; cell++) {
                if (solidLayer(axis, cell, aRange, bRange)) {
                    return Math.min(delta, Math.max(0, cell - high));
                }
            }
        } else {
            int first = (int) Math.floor(low + EPSILON) - 1;
            int last = (int) Math.floor(low + delta);
            for (int cell = first; cell >= last; cell--) {
                if (solidLayer(axis, cell, aRange, bRange)) {
                    return Math.max(delta, Math.min(0, cell + 1 - low));
                }
            }
        }
        return delta;
    }

    private static int[] crossRange(BoundingBox box, int axis) {
        double low = axis == 0 ? box.getMinX() : axis == 1 ? box.getMinY() : box.getMinZ();
        double high = axis == 0 ? box.getMaxX() : axis == 1 ? box.getMaxY() : box.getMaxZ();
        return new int[] {(int) Math.floor(low + EPSILON), (int) Math.floor(high - EPSILON)};
    }

    private boolean solidLayer(int axis, int cell, int[] aRange, int[] bRange) {
        for (int a = aRange[0]; a <= aRange[1]; a++) {
            for (int b = bRange[0]; b <= bRange[1]; b++) {
                int x;
                int y;
                int z;
                switch (axis) {
                    case 0 -> {
                        x = cell;
                        y = a;
                        z = b;
                    }
                    case 1 -> {
                        y = cell;
                        z = a;
                        x = b;
                    }
                    default -> {
                        z = cell;
                        x = a;
                        y = b;
                    }
                }
                if (y >= world.getMinHeight() && y < world.getMaxHeight()
                        && FlatBlock.collides(world.getBlockAt(x, y, z).getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double horizontalSquared(Vector vector) {
        return vector.getX() * vector.getX() + vector.getZ() * vector.getZ();
    }

    private static double tiny(double value) {
        return Math.abs(value) < 0.003 ? 0 : value;
    }

    private static void setLocation(Entity entity, Location location) {
        if (entity instanceof EntityMock mock) {
            mock.setLocation(location);
        } else {
            entity.teleport(location);
        }
    }

    private static void setOnGround(Entity entity, boolean onGround) {
        if (entity instanceof EntityMock mock) {
            mock.setOnGround(onGround);
        }
    }
}
