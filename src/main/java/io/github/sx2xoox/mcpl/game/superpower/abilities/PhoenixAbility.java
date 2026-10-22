package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.EntityEffect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * 불사조 — rises from its own ashes once per match, soars on wings of flame and, as the ultimate, dives forward as a
 * blazing phoenix that scorches everything in its path.
 */
public final class PhoenixAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("phoenix", "불사조", Material.TOTEM_OF_UNDYING)
            .color(TextColor.color(0xFF7A1A))
            .difficulty(1)
            .passive("환생",
                    "경기당 한 번, 치명상을 입으면 쓰러지지 않고 최대 체력의 {revive_health}%로 되살아나 "
                            + "{revive_grace}초간 피해를 받지 않습니다. 이때 주변 {revive_radius}블록 안의 적에게 "
                            + "{revive_damage} 피해를 주고 {revive_burn}초간 불태웁니다.")
            .active("화염 날개",
                    "약 {leap_height}블록 높이로 날아올라 화염 꼬리를 남기며 {glide_duration}초간 천천히 내려옵니다. "
                            + "웅크리면 급강하하며, 이 비행으로는 낙하 피해를 받지 않습니다.",
                    9)
            .ultimate("불사조 강림",
                    "바라보는 방향으로 최대 {dash_distance}블록 화염 돌진합니다. 경로 위의 적에게 {dash_damage} 피해를 "
                            + "주고 {dash_burn}초간 불태웁니다.")
            .param("revive_health", 50)
            .param("revive_grace", 1)
            .param("revive_radius", 5)
            .param("revive_damage", 4)
            .param("revive_burn", 3)
            .param("leap_height", 8)
            .param("glide_duration", 3)
            .param("dash_distance", 15)
            .param("dash_radius", 2)
            .param("dash_damage", 6)
            .param("dash_burn", 3)
            .build();

    private static final String FLAME = "#FF7A1A";
    private static final Color EMBER = Color.fromRGB(0xFF, 0xC2, 0x3A);
    private static final Color BLAZE = Color.fromRGB(0xFF, 0x5A, 0x1A);
    private static final Color CRIMSON = Color.fromRGB(0xC8, 0x1E, 0x1E);
    private static final Particle.DustTransition WING_DUST = new Particle.DustTransition(EMBER, CRIMSON, 1.1f);
    private static final Particle.DustTransition WING_EDGE = new Particle.DustTransition(BLAZE, EMBER, 0.8f);
    private static final Particle.DustOptions ASH_DUST = new Particle.DustOptions(Color.fromRGB(0x5A, 0x4A, 0x40), 1.0f);

    /**
     * Wing outline in body space: x = distance out from the spine, y = height relative to the shoulders. Points run
     * along the leading edge to the tip and back along the trailing feathers.
     */
    private static final double[][] WING = {
            {0.15, 0.05}, {0.35, 0.25}, {0.6, 0.45}, {0.9, 0.6}, {1.2, 0.62}, {1.45, 0.5},
            {1.25, 0.3}, {1.0, 0.12}, {0.75, -0.05}, {0.5, -0.2}, {0.3, -0.25}};
    private static final double SHOULDER_HEIGHT = 1.35;

    // Leap
    private static final double LEAP_FORWARD = 0.45;
    private static final double MAX_LEAP_VELOCITY = 2.5;
    /** Upper bound on how long a leap may last before its state is dropped (the glide itself is a potion). */
    private static final long LEAP_TIMEOUT_TICKS = 20 * 20;
    private static final long LEAP_MIN_AIR_TICKS = 6;
    /** If the server never sees the owner leave the ground (blocked by a ceiling, extreme lag), give up. */
    private static final long LEAP_NEVER_AIRBORNE_TICKS = 40;
    private static final double DIVE_VELOCITY = -1.2;
    private static final long FALL_GRACE_TICKS = 10;

    // Dash
    private static final double DASH_SPEED = 1.5;
    private static final double MIN_DASH = 2.0;
    private static final double MAX_DASH_PITCH = 25.0;
    private static final double DASH_LIFT = 0.1;
    private static final double WALL_MARGIN = 0.7;
    /** Extra ticks after the last push during which hits are still checked (client latency). */
    private static final int DASH_LATENCY_TICKS = 6;
    private static final int SCORCH_TICKS = 24;

    private boolean rebirthUsed;
    private long rebirthGraceUntil = -1;
    private boolean leaping;
    /** The server has seen the owner off the ground since the leap began (positions arrive with client lag). */
    private boolean leftGround;
    private boolean dived;
    private long leapStartTick;
    private long fallGraceUntil = -1;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: rebirth

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        long now = ctx().currentTick();
        if (now <= rebirthGraceUntil && !isInescapable(event)) {
            event.setCancelled(true);
            return;
        }
        if (isFall(event) && (leaping || now <= fallGraceUntil)) {
            event.setCancelled(true);
            return;
        }
        Player owner = player();
        if (rebirthUsed || event.getFinalDamage() < owner.getHealth() || isInescapable(event)) {
            return;
        }
        event.setCancelled(true);
        rebirth(owner);
    }

    /** Falling out of the world or being killed by command cannot be survived (it would just repeat). */
    private static boolean isInescapable(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        if (cause == DamageCause.VOID || cause == DamageCause.KILL || cause == DamageCause.SUICIDE) {
            return true;
        }
        DamageType type = event.getDamageSource().getDamageType();
        return type == DamageType.OUT_OF_WORLD || type == DamageType.GENERIC_KILL;
    }

    private void rebirth(Player owner) {
        rebirthUsed = true;
        long now = ctx().currentTick();
        rebirthGraceUntil = now + params().ticks("revive_grace");
        double max = maxHealth(owner);
        owner.setHealth(Math.clamp(max * params().num("revive_health") / 100.0, 1.0, max));
        owner.setFireTicks(0);
        owner.setFreezeTicks(0);
        owner.removePotionEffect(PotionEffectType.POISON);
        owner.removePotionEffect(PotionEffectType.WITHER);
        owner.setFallDistance(0);
        owner.playEffect(EntityEffect.PROTECTED_FROM_DEATH);

        Location feet = owner.getLocation();
        World world = feet.getWorld();
        Location body = feet.clone().add(0, owner.getHeight() * 0.5, 0);
        world.spawnParticle(Particle.FLAME, body, 50, 0.3, 0.8, 0.3, 0.12);
        world.spawnParticle(Particle.LAVA, body, 12, 0.4, 0.4, 0.4, 0);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 30, 0.5, 0.9, 0.5, 0, WING_DUST);
        drawWings(owner, 0.25, 1.4);
        sound(feet, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.6f);
        sound(feet, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.3f);
        sound(feet, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 0.8f);
        owner.sendActionBar(Text.mm("<" + FLAME + "><b>✹ 환생!</b></" + FLAME + "> <white>잿더미에서 되살아났습니다</white>"));
        Component notice = Text.mm("<" + FLAME + ">✹</" + FLAME + "> <white><player></white><gray>이(가) "
                + "</gray><" + FLAME + ">불사조</" + FLAME + "><gray>의 힘으로 잿더미에서 되살아났습니다!</gray>",
                Placeholder.unparsed("player", owner.getName()));
        for (Player viewer : world.getPlayers()) {
            viewer.sendMessage(notice);
        }
        // The shockwave is dealt a tick later, outside the damage event that triggered the rebirth.
        ctx().runLater(owner, 1, this::rebirthShockwave);
    }

    private void rebirthShockwave() {
        Player owner = player();
        if (!owner.isOnline() || !ctx().isAlive(owner)) {
            return;
        }
        Location center = owner.getLocation();
        double radius = params().num("revive_radius");
        double damage = params().num("revive_damage");
        long burn = params().ticks("revive_burn");
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            ctx().damage(owner, enemy, damage);
            ignite(enemy, burn);
            Vector push = enemy.getLocation().toVector().subtract(center.toVector()).setY(0);
            if (push.lengthSquared() < 1.0E-4) {
                push = new Vector(ThreadLocalRandom.current().nextDouble() - 0.5, 0,
                        ThreadLocalRandom.current().nextDouble() - 0.5);
            }
            push.normalize().multiply(0.9).setY(0.4);
            enemy.setVelocity(clamp(enemy.getVelocity().add(push)));
            enemy.getWorld().spawnParticle(Particle.FLAME, enemy.getLocation().add(0, enemy.getHeight() * 0.5, 0),
                    10, 0.25, 0.4, 0.25, 0.04);
        }
        sound(center, Sound.ITEM_FIRECHARGE_USE, 1.0f, 0.7f);
        sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.5f);
        // An expanding ring of fire, one frame per tick.
        int[] frame = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = ctx().runTimer(owner, 0, 1, () -> {
            frame[0]++;
            double r = radius * frame[0] / 4.0;
            ring(center.clone().add(0, 0.2, 0), r, Math.max(8, (int) (r * 6)), Particle.FLAME, 0.02);
            if (frame[0] >= 4) {
                center.getWorld().spawnParticle(Particle.LAVA, center, 8, radius / 3, 0.2, radius / 3, 0);
                task[0].cancel();
            }
        });
    }

    // ------------------------------------------------------------------ active: flame wings

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 날아오를 수 없습니다");
        }
        double up = Math.min(MAX_LEAP_VELOCITY, launchVelocity(params().num("leap_height")));
        Vector forward = horizontalLook(owner).multiply(LEAP_FORWARD);
        owner.setVelocity(clamp(new Vector(forward.getX(), up, forward.getZ())));
        owner.setFallDistance(0);
        int glide = (int) params().ticks("glide_duration");
        // Slow falling has no effect while rising, so the ascent time is simply added on top.
        int ascent = ascentTicks(up);
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, ascent + glide, 0, false, false, true));
        leaping = true;
        leftGround = false;
        dived = false;
        leapStartTick = ctx().currentTick();

        Location feet = owner.getLocation();
        World world = feet.getWorld();
        world.spawnParticle(Particle.FLAME, feet.clone().add(0, 0.1, 0), 30, 0.4, 0.05, 0.4, 0.08);
        ring(feet.clone().add(0, 0.1, 0), 1.2, 16, Particle.SMALL_FLAME, 0.01);
        world.spawnParticle(Particle.LARGE_SMOKE, feet, 6, 0.3, 0.05, 0.3, 0.02);
        sound(feet, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.2f + jitter(0.1f));
        sound(feet, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.9f + jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + FLAME + ">✹ 화염 날개</" + FLAME + "> <gray>웅크리면 급강하</gray>"));
        return ActivationResult.success();
    }

    @Override
    public void onTick(long tick) {
        if (leaping) {
            tickLeap(tick);
        } else if (!rebirthUsed && tick % 30 == 0) {
            // The tell that the rebirth is still available: embers drifting off the owner.
            Player owner = player();
            Location head = owner.getLocation().add(0, owner.getHeight() * 0.8, 0);
            owner.getWorld().spawnParticle(Particle.SMALL_FLAME, head, 2, 0.25, 0.3, 0.25, 0.01);
        }
    }

    private void tickLeap(long tick) {
        Player owner = player();
        long airborne = tick - leapStartTick;
        owner.setFallDistance(0);
        boolean grounded = isGrounded(owner) || owner.isInWater();
        if (!grounded) {
            leftGround = true;
        }
        if (airborne > LEAP_TIMEOUT_TICKS
                || (leftGround && airborne >= LEAP_MIN_AIR_TICKS && grounded)
                || (!leftGround && airborne > LEAP_NEVER_AIRBORNE_TICKS)) {
            endLeap(owner, tick);
            return;
        }
        if (!dived && airborne >= LEAP_MIN_AIR_TICKS && owner.isSneaking()) {
            dived = true;
            owner.removePotionEffect(PotionEffectType.SLOW_FALLING);
            Vector velocity = owner.getVelocity();
            owner.setVelocity(clamp(new Vector(velocity.getX(), Math.min(velocity.getY(), DIVE_VELOCITY),
                    velocity.getZ())));
            sound(owner.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1.0f, 0.7f);
        }
        if (tick % 2 != 0) {
            return;
        }
        Location feet = owner.getLocation();
        World world = feet.getWorld();
        world.spawnParticle(Particle.FLAME, feet.clone().add(0, 0.2, 0), 3, 0.15, 0.15, 0.15, 0.01);
        world.spawnParticle(Particle.SMALL_FLAME, feet.clone().add(0, 0.5, 0), 2, 0.2, 0.25, 0.2, 0.01);
        if (!dived && tick % 4 == 0) {
            double flap = 0.3 + 0.35 * (0.5 + 0.5 * Math.sin(tick * 0.5));
            drawWings(owner, flap, 1.0);
        }
        if (tick % 12 == 0 && !dived) {
            sound(feet, Sound.ENTITY_PHANTOM_FLAP, 0.4f, 1.3f + jitter(0.1f));
        }
    }

    private void endLeap(Player owner, long tick) {
        leaping = false;
        fallGraceUntil = tick + FALL_GRACE_TICKS;
        owner.setFallDistance(0);
        PotionEffect glide = owner.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (glide != null && !glide.hasParticles() && glide.getAmplifier() == 0) {
            owner.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
        Location feet = owner.getLocation();
        if (dived) {
            ring(feet.clone().add(0, 0.15, 0), 1.6, 18, Particle.FLAME, 0.03);
            sound(feet, Sound.ITEM_FIRECHARGE_USE, 0.7f, 1.2f);
        } else {
            feet.getWorld().spawnParticle(Particle.SMALL_FLAME, feet.clone().add(0, 0.1, 0), 10, 0.4, 0.05, 0.4, 0.02);
        }
    }

    // ------------------------------------------------------------------ ultimate: phoenix descent

    /**
     * Velocity based (the client resolves collisions, so the owner can never be pushed through a wall) and
     * self-contained: it only uses owner-scoped tasks and resets the fall distance itself, so it also works when
     * borrowed by the Gambler.
     */
    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 돌진할 수 없습니다");
        }
        Vector direction = dashDirection(owner);
        double reach = freeDistance(owner, direction, params().num("dash_distance"));
        if (reach < MIN_DASH) {
            return ActivationResult.fail("앞이 막혀 돌진할 수 없습니다");
        }
        new Dash(owner, direction, reach).start();
        return ActivationResult.success();
    }

    private final class Dash implements Runnable {
        private final Player owner;
        private final Vector direction;
        private final int pushes;
        private final double speed;
        private final double radius;
        private final Set<UUID> hit = new HashSet<>();
        private final List<Location> path = new ArrayList<>();
        private Location previous;
        private int age;
        private BukkitTask task;

        Dash(Player owner, Vector direction, double reach) {
            this.owner = owner;
            this.direction = direction;
            this.pushes = Math.max(1, (int) Math.ceil(reach / DASH_SPEED));
            this.speed = reach / pushes;
            this.radius = Math.max(0.5, params().num("dash_radius"));
            this.previous = owner.getLocation();
        }

        void start() {
            Location feet = owner.getLocation();
            World world = feet.getWorld();
            Location body = feet.clone().add(0, owner.getHeight() * 0.5, 0);
            world.spawnParticle(Particle.FLAME, body, 40, 0.4, 0.6, 0.4, 0.15);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 20, 0.5, 0.7, 0.5, 0, WING_DUST);
            drawWings(owner, 0.15, 1.6);
            sound(feet, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.7f);
            sound(feet, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.5f);
            sound(feet, Sound.ITEM_FIRECHARGE_USE, 1.0f, 0.8f);
            task = ctx().runTimer(owner, 0, 1, this);
        }

        @Override
        public void run() {
            if (!owner.isOnline() || !ctx().isAlive(owner) || owner.isInsideVehicle()) {
                task.cancel();
                return;
            }
            age++;
            owner.setFallDistance(0);
            if (age <= pushes) {
                Vector velocity = direction.clone().multiply(speed);
                velocity.setY(velocity.getY() + DASH_LIFT);
                owner.setVelocity(clamp(velocity));
            } else if (age == pushes + 1) {
                // Brake, or the air drag would carry the owner much further than the dash.
                owner.setVelocity(direction.clone().multiply(0.3).setY(0.05));
            }
            Location now = owner.getLocation();
            sweep(previous, now);
            trail(now);
            previous = now;
            if (age >= pushes + DASH_LATENCY_TICKS) {
                finish(now);
                task.cancel();
            }
        }

        /** Damages every enemy within the dash radius of the segment travelled since the last tick. */
        private void sweep(Location from, Location to) {
            Vector a = from.toVector().add(new Vector(0, owner.getHeight() * 0.5, 0));
            Vector b = to.toVector().add(new Vector(0, owner.getHeight() * 0.5, 0));
            Location mid = a.clone().add(b).multiply(0.5).toLocation(to.getWorld());
            double search = a.distance(b) / 2 + radius;
            double damage = params().num("dash_damage");
            long burn = params().ticks("dash_burn");
            for (LivingEntity enemy : ctx().enemiesNear(owner, mid, search)) {
                if (hit.contains(enemy.getUniqueId())) {
                    continue;
                }
                Vector center = enemy.getLocation().toVector().add(new Vector(0, enemy.getHeight() * 0.5, 0));
                if (distanceToSegment(center, a, b) > radius + enemy.getWidth() / 2) {
                    continue;
                }
                hit.add(enemy.getUniqueId());
                ctx().damage(owner, enemy, damage);
                ignite(enemy, burn);
                // Flung aside and up, away from the phoenix's path.
                Vector side = new Vector(-direction.getZ(), 0, direction.getX());
                if (center.clone().subtract(a).dot(side) < 0) {
                    side.multiply(-1);
                }
                if (side.lengthSquared() > 1.0E-6) {
                    side.normalize();
                }
                enemy.setVelocity(clamp(side.multiply(0.6).add(direction.clone().multiply(0.4)).setY(0.45)));
                Location at = enemy.getLocation().add(0, enemy.getHeight() * 0.5, 0);
                enemy.getWorld().spawnParticle(Particle.FLAME, at, 16, 0.25, 0.4, 0.25, 0.08);
                enemy.getWorld().spawnParticle(Particle.LAVA, at, 3, 0.2, 0.3, 0.2, 0);
                sound(at, Sound.ENTITY_BLAZE_HURT, 0.8f, 1.2f + jitter(0.1f));
            }
        }

        private void trail(Location feet) {
            World world = feet.getWorld();
            Location body = feet.clone().add(0, owner.getHeight() * 0.5, 0);
            world.spawnParticle(Particle.FLAME, body, 8, 0.3, 0.35, 0.3, 0.03);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 4, 0.35, 0.4, 0.35, 0, WING_DUST);
            if (age <= pushes) {
                drawWings(owner, 0.2 + 0.1 * Math.sin(age * 1.3), 1.4);
                path.add(feet.clone().add(0, 0.15, 0));
            }
            if (age % 3 == 0) {
                sound(feet, Sound.BLOCK_FIRE_AMBIENT, 0.8f, 1.4f + jitter(0.2f));
            }
        }

        private void finish(Location feet) {
            World world = feet.getWorld();
            Location body = feet.clone().add(0, owner.getHeight() * 0.5, 0);
            world.spawnParticle(Particle.EXPLOSION, body, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.FLAME, body, 30, 0.5, 0.5, 0.5, 0.12);
            world.spawnParticle(Particle.LAVA, body, 6, 0.4, 0.3, 0.4, 0);
            sound(feet, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.6f);
            if (!hit.isEmpty()) {
                owner.sendActionBar(Text.mm("<" + FLAME + ">✹ 불사조 강림</" + FLAME + "> <white>" + hit.size()
                        + "명 적중</white>"));
            }
            scorch(path);
        }
    }

    /** Embers and ash linger along the path for a moment (visual only). */
    private void scorch(List<Location> path) {
        if (path.isEmpty()) {
            return;
        }
        Player owner = player();
        int[] age = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = ctx().runTimer(owner, 4, 4, () -> {
            age[0] += 4;
            for (Location point : path) {
                point.getWorld().spawnParticle(Particle.FLAME, point, 1, 0.3, 0.05, 0.3, 0.005);
                if (age[0] % 8 == 0) {
                    point.getWorld().spawnParticle(Particle.DUST, point, 1, 0.3, 0.05, 0.3, 0, ASH_DUST);
                }
            }
            if (age[0] >= SCORCH_TICKS) {
                task[0].cancel();
            }
        });
    }

    /** The look direction with its pitch limited, so the dash stays roughly level. */
    private static Vector dashDirection(Player owner) {
        Location eye = owner.getEyeLocation();
        double pitch = Math.toRadians(Math.clamp(eye.getPitch(), -MAX_DASH_PITCH, MAX_DASH_PITCH));
        double yaw = Math.toRadians(eye.getYaw());
        return new Vector(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    /**
     * How far the owner can dash along {@code direction} before a wall. Walls are searched horizontally at chest and
     * head height; floors and ceilings are left to the client's collision (the owner just slides along them), so
     * aiming slightly down at an enemy does not cut the dash short.
     */
    private static double freeDistance(Player owner, Vector direction, double max) {
        Vector horizontal = direction.clone().setY(0);
        double cosPitch = horizontal.length();
        if (cosPitch < 1.0E-3) {
            return 0;
        }
        horizontal.multiply(1 / cosPitch);
        World world = owner.getWorld();
        Location feet = owner.getLocation();
        double maxHorizontal = max * cosPitch;
        double reach = maxHorizontal;
        for (double height : new double[] {owner.getHeight() * 0.5, owner.getHeight() * 0.9}) {
            Location start = feet.clone().add(0, height, 0);
            RayTraceResult wall = world.rayTraceBlocks(start, horizontal, maxHorizontal, FluidCollisionMode.NEVER,
                    true);
            if (wall != null) {
                reach = Math.min(reach, wall.getHitPosition().distance(start.toVector()) - WALL_MARGIN);
            }
        }
        return Math.max(0, reach) / cosPitch;
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

    // ------------------------------------------------------------------ visuals

    /**
     * Draws a pair of flame wings behind the owner's back.
     *
     * @param flap  how far the wings are swept back, in radians (0 = spread flat to the sides)
     * @param size  wing span multiplier on top of the owner's scale
     */
    private static void drawWings(Player owner, double flap, double size) {
        double scale = scaleOf(owner) * size;
        double yaw = Math.toRadians(owner.getBodyYaw());
        Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vector back = new Vector(Math.sin(yaw), 0, -Math.cos(yaw));
        Location shoulder = owner.getLocation().add(0, SHOULDER_HEIGHT * scaleOf(owner), 0)
                .add(back.clone().multiply(0.2 * scaleOf(owner)));
        World world = owner.getWorld();
        for (int side = -1; side <= 1; side += 2) {
            Vector out = right.clone().multiply(side * Math.cos(flap)).add(back.clone().multiply(Math.sin(flap)));
            for (int i = 0; i < WING.length; i++) {
                double[] point = WING[i];
                Location at = shoulder.clone().add(out.clone().multiply(point[0] * scale)).add(0, point[1] * scale, 0);
                if (i == WING.length / 2) {
                    world.spawnParticle(Particle.FLAME, at, 1, 0, 0, 0, 0);
                }
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0,
                        i < 6 ? WING_EDGE : WING_DUST);
            }
        }
    }

    private static void ring(Location center, double radius, int points, Particle particle, double speed) {
        World world = center.getWorld();
        double offset = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        for (int i = 0; i < points; i++) {
            double angle = offset + Math.PI * 2 * i / points;
            Location at = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            world.spawnParticle(particle, at, 1, 0, 0.05, 0, speed);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Initial upward velocity that peaks at roughly {@code height} blocks (vanilla gravity 0.08, drag 0.98). */
    private static double launchVelocity(double height) {
        if (height <= 0) {
            return 0.42;
        }
        double low = 0;
        double high = MAX_LEAP_VELOCITY;
        for (int i = 0; i < 30; i++) {
            double mid = (low + high) / 2;
            if (peakHeight(mid) < height) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return high;
    }

    private static double peakHeight(double velocity) {
        double y = 0;
        double v = velocity;
        while (v > 0) {
            y += v;
            v = (v - 0.08) * 0.98;
        }
        return y;
    }

    private static int ascentTicks(double velocity) {
        int ticks = 0;
        double v = velocity;
        while (v > 0 && ticks < 200) {
            v = (v - 0.08) * 0.98;
            ticks++;
        }
        return ticks;
    }

    private static Vector horizontalLook(Player owner) {
        Vector look = owner.getLocation().getDirection().setY(0);
        return look.lengthSquared() < 1.0E-6 ? new Vector() : look.normalize();
    }

    private static void ignite(LivingEntity target, long ticks) {
        if (ticks <= 0 || !target.isValid()) {
            return;
        }
        int fireTicks = (int) Math.min(Integer.MAX_VALUE, ticks);
        if (target.getFireTicks() < fireTicks) {
            target.setFireTicks(fireTicks);
        }
    }

    private static boolean isFall(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.FALL) {
            return true;
        }
        Tag<DamageType> fall = DamageTypeTags.IS_FALL;
        return fall != null && fall.isTagged(event.getDamageSource().getDamageType());
    }

    /** True when a solid surface is right below the entity's feet (server-side, not the client's claim). */
    private static boolean isGrounded(LivingEntity entity) {
        BoundingBox box = entity.getBoundingBox();
        BoundingBox below = new BoundingBox(box.getMinX() + 0.05, box.getMinY() - 0.15, box.getMinZ() + 0.05,
                box.getMaxX() - 0.05, box.getMinY() - 0.01, box.getMaxZ() - 0.05);
        return entity.getWorld().hasCollisionsIn(below);
    }

    private static double maxHealth(LivingEntity entity) {
        AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? 20.0 : Math.max(1.0, max.getValue());
    }

    private static double scaleOf(LivingEntity entity) {
        AttributeInstance scale = entity.getAttribute(Attribute.SCALE);
        return scale == null ? 1.0 : Math.max(0.1, scale.getValue());
    }

    /** Caps a velocity so nobody is flung out of the arena. */
    private static Vector clamp(Vector velocity) {
        double length = velocity.length();
        if (!Double.isFinite(length)) {
            return new Vector();
        }
        return length > 3.0 ? velocity.multiply(3.0 / length) : velocity;
    }

    private static void sound(Location at, Sound sound, float volume, float pitch) {
        at.getWorld().playSound(at, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private static float jitter(float amount) {
        return (ThreadLocalRandom.current().nextFloat() * 2 - 1) * amount;
    }
}
