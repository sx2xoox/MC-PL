package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.game.siege.arena.SiegeArena;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The creatures' visible shots (docs/siege-v2.md §2, §4), seen by everyone: a display entity flying a little every
 * tick. A <em>homing</em> shot (the archer's toy arrow, the rabbit's clock hand) follows its target and hits when it
 * reaches it; a <em>lobbed</em> shot (the cannon's shell, the jelly's goo) arcs to a place fixed when it was fired,
 * marked on the ground ({@link Telegraphs#circle}) until it lands there. A shot whose shooter dies or whose target is
 * gone fizzles. Every display is tracked by the match ({@link SiegeArena#track}) and removed by {@link #clear()}.
 */
public final class ToyShots {

    /** Ticks a homing shot may fly before it gives up. */
    static final int MAX_FLIGHT = 60;

    /** What a shot looks like. */
    public enum Look {
        /** A toy arrow (the arrow item). */
        ARROW,
        /** A golden clock hand, spinning. */
        CLOCK_HAND,
        /** A round black cannon shell, tumbling. */
        SHELL,
        /** A blob of pink jelly, wobbling. */
        GOO
    }

    private final SiegeArena arena;
    private final List<Shot> shots = new ArrayList<>();

    public ToyShots(SiegeArena arena) {
        this.arena = arena;
    }

    /** One shot in flight. */
    public final class Shot {
        private final Mob shooter;
        private final Look look;
        private final Display display;
        private final @Nullable LivingEntity target;
        private final @Nullable Consumer<LivingEntity> onHit;
        private final Location from;
        private final @Nullable Location to;
        private final double speed;
        private final int flight;
        private final double arc;
        private final double warnRadius;
        private final @Nullable Consumer<Location> onLand;
        private Location at;
        private int age;
        private boolean done;

        private Shot(Mob shooter, Look look, Display display, Location from, @Nullable LivingEntity target,
                @Nullable Consumer<LivingEntity> onHit, @Nullable Location to, double speed, int flight, double arc,
                double warnRadius, @Nullable Consumer<Location> onLand) {
            this.shooter = shooter;
            this.look = look;
            this.display = display;
            this.from = from;
            this.target = target;
            this.onHit = onHit;
            this.to = to;
            this.speed = speed;
            this.flight = flight;
            this.arc = arc;
            this.warnRadius = warnRadius;
            this.onLand = onLand;
            this.at = from.clone();
        }

        public Location location() {
            return at.clone();
        }

        public Look look() {
            return look;
        }

        public boolean isDone() {
            return done;
        }

        /** Where a lobbed shot lands (null for a homing one). */
        public @Nullable Location landing() {
            return to == null ? null : to.clone();
        }

        /** Ticks a lobbed shot flies (0 for a homing one). */
        public int flight() {
            return flight;
        }

        Display display() {
            return display;
        }

        private void tick() {
            age++;
            if (!shooter.isValid() || shooter.isDead()) {
                fizzle();
                return;
            }
            if (to != null) {
                lobbed();
            } else {
                homing();
            }
        }

        private void homing() {
            LivingEntity aimed = target;
            if (aimed == null || !aimed.isValid() || aimed.isDead() || !aimed.getWorld().equals(at.getWorld())
                    || age > MAX_FLIGHT) {
                fizzle();
                return;
            }
            Location aim = aimPoint(aimed, at);
            Vector step = aim.toVector().subtract(at.toVector());
            double left = step.length();
            if (left <= speed) {
                move(aim, step);
                finish();
                if (onHit != null) {
                    onHit.accept(aimed);
                }
                return;
            }
            move(at.clone().add(step.clone().multiply(speed / left)), step);
            if (age % 2 == 0) {
                at.getWorld().spawnParticle(look == Look.ARROW ? Particle.CRIT : Particle.WAX_OFF, at, 1, 0, 0, 0, 0);
            }
        }

        private void lobbed() {
            Location land = to;
            if (land == null) {
                return;
            }
            double t = Math.min(1, age / (double) flight);
            Vector along = land.toVector().subtract(from.toVector());
            Location next = from.clone().add(along.clone().multiply(t)).add(0, 4 * arc * t * (1 - t), 0);
            Vector step = next.toVector().subtract(at.toVector());
            move(next, step);
            if (warnRadius > 0 && age % 2 == 0) {
                Telegraphs.circle(land, warnRadius, t, look == Look.GOO ? Telegraphs.STICKY : Telegraphs.DANGER);
            }
            if (age % 3 == 0) {
                at.getWorld().spawnParticle(look == Look.GOO ? Particle.ITEM_SLIME : Particle.SMOKE, at, 1, 0, 0, 0,
                        0);
            }
            if (t >= 1) {
                finish();
                if (onLand != null) {
                    onLand.accept(land.clone());
                }
            }
        }

        private void move(Location next, Vector heading) {
            at = next;
            display.teleport(next);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
            display.setTransformation(pose(look, heading, age));
        }

        private void fizzle() {
            at.getWorld().spawnParticle(Particle.POOF, at, 2, 0.05, 0.05, 0.05, 0.01);
            finish();
        }

        private void finish() {
            done = true;
            display.remove();
            arena.untrack(display);
        }
    }

    /**
     * Fires a homing shot from {@code from} at {@code target}, {@code speed} blocks a tick; {@code onHit} runs when it
     * reaches the target (the hit's damage), nothing if it fizzles.
     */
    public @Nullable Shot homing(Mob shooter, Look look, Location from, LivingEntity target, double speed,
            Consumer<LivingEntity> onHit) {
        Display display = spawn(look, from);
        if (display == null) {
            onHit.accept(target); // no display (a world that refused it): the hit lands at once
            return null;
        }
        Shot shot = new Shot(shooter, look, display, from.clone(), target, onHit, null, Math.max(0.05, speed), 0, 0,
                0, null);
        shots.add(shot);
        return shot;
    }

    /**
     * Lobs a shot from {@code from} to {@code to} (fixed), covering {@code speed} blocks a tick along the ground in an
     * arc rising {@code arc} blocks, the landing marked {@code warnRadius} wide (0 = unmarked); {@code onLand} runs as
     * it lands.
     */
    public @Nullable Shot lob(Mob shooter, Look look, Location from, Location to, double speed, double arc,
            double warnRadius, Consumer<Location> onLand) {
        Display display = spawn(look, from);
        double distance = Math.sqrt(Creatures.flatDistanceSquared(from, to));
        int flight = (int) Math.max(4, Math.ceil(distance / Math.max(0.05, speed)));
        if (display == null) {
            onLand.accept(to.clone());
            return null;
        }
        Shot shot = new Shot(shooter, look, display, from.clone(), null, null, to.clone(), speed, flight, arc,
                warnRadius, onLand);
        shots.add(shot);
        return shot;
    }

    /** Every tick: the shots fly; the ones that landed, hit or fizzled are gone. */
    public void tick() {
        for (Shot shot : List.copyOf(shots)) {
            if (!shot.done) {
                shot.tick();
            }
        }
        shots.removeIf(shot -> shot.done);
    }

    /** Every shot in flight is gone (the round ended). */
    public void clear() {
        for (Shot shot : shots) {
            if (!shot.done) {
                shot.finish();
            }
        }
        shots.clear();
    }

    /** Shots of {@code shooter} fizzle (it died or went home). */
    public void cancel(Entity shooter) {
        for (Shot shot : shots) {
            if (!shot.done && shot.shooter.equals(shooter)) {
                shot.fizzle();
            }
        }
        shots.removeIf(shot -> shot.done);
    }

    public List<Shot> shots() {
        return List.copyOf(shots);
    }

    /** Where a homing shot flies to: the middle of a body, the near wall of a building. */
    private Location aimPoint(LivingEntity target, Location shot) {
        if (arena.isStructure(target)) {
            Location centre = target.getLocation();
            Vector out = shot.toVector().subtract(centre.toVector()).setY(0);
            double wall = Math.min(arena.structureHalfWidth(target), out.length());
            if (out.lengthSquared() > 1.0E-6) {
                centre.add(out.normalize().multiply(wall));
            }
            return centre.add(0, 1.5, 0);
        }
        return target.getLocation().add(0, target.getHeight() * 0.55, 0);
    }

    private @Nullable Display spawn(Look look, Location at) {
        Location spot = at.clone();
        spot.setYaw(0);
        spot.setPitch(0);
        try {
            Display display = switch (look) {
                case ARROW -> at.getWorld().spawn(spot, ItemDisplay.class, d -> {
                    d.setItemStack(ItemStack.of(Material.ARROW));
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                });
                case CLOCK_HAND -> at.getWorld().spawn(spot, BlockDisplay.class,
                        d -> d.setBlock(Material.GOLD_BLOCK.createBlockData()));
                case SHELL -> at.getWorld().spawn(spot, BlockDisplay.class,
                        d -> d.setBlock(Material.BLACK_CONCRETE.createBlockData()));
                case GOO -> at.getWorld().spawn(spot, BlockDisplay.class, d -> {
                    d.setBlock(Material.PINK_STAINED_GLASS.createBlockData());
                    d.setGlowColorOverride(Color.fromRGB(0xFF, 0x8C, 0xBE));
                });
            };
            display.setPersistent(false);
            display.setTeleportDuration(1);
            display.setShadowRadius(0);
            display.setViewRange(1.5f);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setTransformation(pose(look, new Vector(1, 0, 0), 0));
            arena.track(display);
            return display;
        } catch (RuntimeException e) {
            arena.plugin().getLogger().log(Level.FINE, "Could not spawn a creature shot", e);
            return null;
        }
    }

    /** A shot's pose flying along {@code heading}, {@code age} ticks into its flight. */
    static Transformation pose(Look look, Vector heading, int age) {
        Vector flat = heading.clone().setY(0);
        float yaw = flat.lengthSquared() < 1.0E-8 ? 0 : (float) Math.atan2(-flat.getZ(), flat.getX());
        float pitch = (float) Math.atan2(heading.getY(), Math.max(1.0E-6, flat.length()));
        return switch (look) {
            // the arrow item points up-right in its plane: a -45° turn lays it along +x, then along the heading
            case ARROW -> new Transformation(new Vector3f(), new Quaternionf().rotateY(yaw).rotateZ(pitch)
                    .rotateZ((float) (-Math.PI / 4)), new Vector3f(0.7f, 0.7f, 0.7f), new Quaternionf());
            case CLOCK_HAND -> centred(new Vector3f(0.7f, 0.08f, 0.12f),
                    new Quaternionf().rotateY(yaw + age * 0.9f));
            case SHELL -> centred(new Vector3f(0.36f, 0.36f, 0.36f),
                    new Quaternionf().rotateY(yaw).rotateZ(-age * 0.5f));
            case GOO -> {
                float wobble = 1 + 0.15f * (float) Math.sin(age * 1.3);
                yield centred(new Vector3f(0.45f * wobble, 0.45f / wobble, 0.45f * wobble), new Quaternionf());
            }
        };
    }

    /** A block display {@code size} big, centred on its location, turned by {@code rotation}. */
    private static Transformation centred(Vector3f size, Quaternionf rotation) {
        Vector3f half = new Vector3f(size).mul(-0.5f).rotate(rotation);
        return new Transformation(half, rotation, size, new Quaternionf());
    }
}
