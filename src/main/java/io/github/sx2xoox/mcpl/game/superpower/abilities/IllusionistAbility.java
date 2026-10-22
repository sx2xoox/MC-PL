package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import com.destroystokyo.paper.ClientOption;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
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
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.entity.LookAnchor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 환영술사 — a master of decoys. A mirage shield swallows one enemy hit every so often, the active leaves a perfect
 * copy (a mannequin wearing the owner's skin and gear) behind while the owner vanishes, and the ultimate splits the
 * owner into a whole squad of copies and trades places with one of them. Copies imitate a player: they keep running,
 * turn to face and chase enemies and swing at them. Striking a copy shatters it and blinds the striker.
 */
public final class IllusionistAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("illusionist", "환영술사", Material.ENDER_EYE)
            .color(TextColor.color(0xA98CFF))
            .difficulty(3)
            .passive("환영 보호막",
                    "{shield_cooldown}초마다 적의 공격을 한 번 완전히 막아 내는 환영 보호막이 생깁니다.")
            .active("분신",
                    "제자리에 나와 똑같은 분신을 남기고 {invis_duration}초간 적의 눈에서 사라집니다. 분신은 "
                            + "{clone_duration}초간 나를 흉내 내며, 분신을 공격한 적은 {clone_blind}초간 실명합니다.",
                    12)
            .ultimate("환영 군단",
                    "주변에 분신 {legion_clones}개를 불러내 그중 무작위 분신과 자리를 바꾸고 {legion_invis}초간 "
                            + "사라집니다. 분신들은 {legion_duration}초간 흩어져 적을 쫓습니다.")
            // Copies need the damage listener and the stealth needs onRevoke to show the owner again.
            .ultimateNotStandalone()
            .param("shield_cooldown", 20)
            .param("invis_duration", 2)
            .param("clone_duration", 6)
            .param("clone_blind", 1.5)
            .param("legion_clones", 4)
            .param("legion_spread", 3)
            .param("legion_invis", 4)
            .param("legion_duration", 8)
            .build();

    private static final String LILAC = "#A98CFF";

    // ------------------------------------------------------------------ look: pale lilac, mirage violet, deep indigo
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF3EDFF, 0xA98CFF, 0x5E35B1);
    private static final Particle.DustTransition MIRAGE_DUST = PALETTE.glow(1.0f);
    private static final Particle.DustTransition SHATTER_DUST = PALETTE.fade(1.2f);
    private static final Particle.DustOptions SHIELD_GLINT = new Particle.DustOptions(PALETTE.core(), 0.6f);
    private static final Particle.DustOptions SHIMMER = new Particle.DustOptions(PALETTE.body(), 0.7f);
    /** Glass the mirrors are made of; its break particles are the shards of a shattered illusion. */
    private static final BlockData MIRROR_GLASS = Material.PURPLE_STAINED_GLASS.createBlockData();
    private static final float MIRROR_WIDTH = 0.95f;
    private static final float MIRROR_HEIGHT = 1.9f;
    private static final float MIRROR_THICKNESS = 0.04f;
    private static final int MIRROR_TURN_TICKS = 8;
    private static final int SHIMMER_TICKS = 6;
    private static final int SHARDS = 5;
    private static final int SHARD_FLIGHT_TICKS = 8;
    /** The legion's hall of mirrors: panes circling the origin, then shattering outwards. */
    private static final int HALL_MIRRORS = 6;
    private static final double HALL_RADIUS = 1.6;
    private static final int HALL_STEPS = 4;
    private static final int HALL_STEP_TICKS = 4;
    private static final double HALL_STEP_DEGREES = 22.5;
    private static final long SHIELD_BLOCK_TICKS = 3;
    /** At most this many shard displays per {@link #SHARD_WINDOW_TICKS}, e.g. when an area hit shatters every copy. */
    private static final int MAX_SHARDS_PER_WINDOW = 12;
    private static final long SHARD_WINDOW_TICKS = SHARD_FLIGHT_TICKS + 8;

    // Copies
    private static final int MAX_CLONES = 8;
    private static final double RUN_SPEED_MIN = 0.16;
    private static final double RUN_SPEED_MAX = 0.3;
    private static final double LEGION_RUN_SPEED = 0.28;
    private static final double WALK_SPEED = 0.2;
    private static final long DECOY_RUN_TICKS = 30;
    private static final long LEGION_RUN_TICKS = 24;
    private static final double CHASE_RANGE = 20;
    private static final double SWING_RANGE = 2.6;
    private static final long THINK_INTERVAL = 5;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double MOVING_THRESHOLD = 0.08;
    private static final int GROUND_SEARCH_DEPTH = 3;

    // Placement
    private static final double[] LIFTS = {0, 0.5, 1.0, -0.5, -1.0, 1.5, -1.5};
    private static final double GROUND_SNAP = 2.1;
    private static final double MIN_SPOT_SPACING = 1.3;
    /** Ring sample order (bit-reversed indices of 16 angles), so the first picks are spread far apart. */
    private static final int[] RING_ORDER = {0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15};

    private static final double AGGRO_RANGE = 32;

    private final List<Copy> copies = new ArrayList<>();
    private @Nullable CopyGuard guard;

    // Mirage shield
    private long shieldReadyTick;
    private boolean shieldAnnounced = true;

    // Stealth
    private final Stealth stealth = new Stealth();

    // Shard display budget
    private long shardWindowStart = Long.MIN_VALUE / 2;
    private int shardsInWindow;

    // Owner motion (sampled every few ticks), so a decoy can keep running the way the owner was going.
    private @Nullable Location motionSample;
    private Vector motion = new Vector();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        reveal(false);
        for (Copy copy : copies) {
            copy.body.remove();
        }
        copies.clear();
        unregisterGuard();
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        if (tick % 4 == 0) {
            sampleMotion(owner);
        }
        tickShield(owner, tick);
        if (stealth.isActive()) {
            tickStealth(owner, tick);
        }
        if (!copies.isEmpty()) {
            tickCopies(tick);
        }
    }

    private void sampleMotion(Player owner) {
        Location now = owner.getLocation();
        Location last = motionSample;
        motionSample = now;
        if (last == null || last.getWorld() != now.getWorld()) {
            motion = new Vector();
            return;
        }
        motion = now.toVector().subtract(last.toVector()).multiply(0.25).setY(0);
    }

    // ------------------------------------------------------------------ passive: mirage shield

    private void tickShield(Player owner, long tick) {
        if (tick < shieldReadyTick) {
            return;
        }
        if (!shieldAnnounced) {
            shieldAnnounced = true;
            owner.playSound(owner, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.8f, 1.5f);
            owner.sendActionBar(Text.mm("<" + LILAC + ">◈ 환영 보호막 준비</" + LILAC + ">"));
            if (!stealth.isActive()) {
                // The mirage re-forms: a shimmering ring sweeps up the body.
                shimmerUp(owner.getLocation(), owner.getHeight(), 0.55 * Body.scaleOf(owner));
            }
        }
        if (tick % 4 == 0) {
            // Two glints orbiting the chest tell everyone the shield is up. The copies wear them too, so they cannot
            // be told apart from the real one.
            if (!stealth.isActive()) {
                shieldGlints(owner, tick);
            }
            for (Copy copy : copies) {
                if (!copy.shattered && copy.body.isValid()) {
                    shieldGlints(copy.body, tick);
                }
            }
        }
    }

    private static void shieldGlints(LivingEntity body, long tick) {
        double angle = tick * 0.3;
        double radius = 0.55 * Body.scaleOf(body);
        Location chest = body.getLocation().add(0, body.getHeight() * 0.55, 0);
        for (int i = 0; i < 2; i++) {
            double a = angle + i * Math.PI;
            Vfx.dot(chest.clone().add(Math.cos(a) * radius, Math.sin(tick * 0.15 + i) * 0.15, Math.sin(a) * radius),
                    Particle.DUST, i == 0 ? SHIELD_GLINT : SHIMMER, false);
        }
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0 || ctx().currentTick() < shieldReadyTick) {
            return;
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity) || !isHostile(byEntity.getDamager())) {
            return;
        }
        event.setCancelled(true);
        shieldReadyTick = ctx().currentTick() + params().ticks("shield_cooldown");
        shieldAnnounced = false;

        Player owner = player();
        shieldBlock(owner, byEntity.getDamager());
        owner.sendActionBar(Text.mm("<" + LILAC + ">◈ 환영 보호막</" + LILAC + "> <white>공격을 흘려보냈습니다</white>"));
    }

    /** A mirror flashes up between the owner and the attacker, takes the blow and shatters. */
    private void shieldBlock(Player owner, Entity damager) {
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        Vector toward = Motion.horizontal(damager.getLocation().toVector().subtract(chest.toVector()));
        if (toward.lengthSquared() < 1.0E-6) {
            toward = Motion.horizontalFacing(owner.getLocation());
        }
        float scale = (float) Body.scaleOf(owner);
        Location at = chest.clone().add(toward.clone().multiply(0.6 * scale));
        at.setYaw(0);
        at.setPitch(0);
        float yaw = (float) Math.toDegrees(Math.atan2(-toward.getX(), toward.getZ()));
        BlockDisplay mirror = Displays.block(ctx(), owner, at, MIRROR_GLASS, Displays.transform(0, 0, 0,
                MIRROR_WIDTH * scale * 0.3f, MIRROR_HEIGHT * scale * 0.3f, MIRROR_THICKNESS, yaw, 0, 0),
                SHIELD_BLOCK_TICKS + 2);
        Displays.animateNextTick(ctx(), owner, mirror, Displays.transform(0, 0, 0, MIRROR_WIDTH * scale * 1.2f,
                MIRROR_HEIGHT * scale * 0.8f, MIRROR_THICKNESS, yaw, 0, 0), 2);
        Vfx.dot(at, Particle.FLASH, PALETTE.core(), false);
        Vfx.cloud(ctx(), at, Particle.ENCHANTED_HIT, 10, 0.3, 0.3, null, false);
        ctx().runLater(owner, SHIELD_BLOCK_TICKS, () -> {
            if (mirror.isValid()) {
                mirror.remove();
            }
            glassShatter(at, 0.5, 18);
            shardBurst(at, SHARDS - 1, 1.2);
        });
        Fx.sound(chest, Sound.BLOCK_GLASS_BREAK, 0.9f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(chest, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8f, 1.3f);
        Fx.sound(chest, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.7f, 1.6f + Fx.jitter(0.1f));
    }

    /** True if the damager is (or belongs to) an enemy of the owner. */
    private boolean isHostile(Entity damager) {
        Player owner = player();
        Player attacker = ctx().resolveAttacker(damager);
        if (attacker != null) {
            return !attacker.equals(owner) && ctx().isEnemy(owner, attacker);
        }
        return ctx().isEnemy(owner, damager);
    }

    // ------------------------------------------------------------------ active: decoy

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 분신을 만들 수 없습니다");
        }
        Location at = owner.getLocation();
        Copy copy = spawnCopy(at, params().ticks("clone_duration"));
        if (copy == null) {
            return ActivationResult.fail("분신을 만들 수 없습니다");
        }
        // The decoy carries on the way the owner was moving, while the real one slips away unseen.
        double speed = motion.length();
        if (speed > MOVING_THRESHOLD) {
            copy.run(motion.clone().normalize(), Math.clamp(speed, RUN_SPEED_MIN, RUN_SPEED_MAX),
                    ctx().currentTick() + DECOY_RUN_TICKS);
        }
        // The trick: a mirror turns edge-on in front of the copy while the real one steps out of sight.
        copyShimmer(copy, true);
        Vfx.cloud(ctx(), at.clone().add(0, owner.getHeight() * 0.5, 0), Particle.REVERSE_PORTAL, 16, 0.3, 0.04, null,
                false);
        Fx.sound(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.1f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.6f, 1.4f);
        Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.2f + Fx.jitter(0.1f));

        startStealth(owner, params().ticks("invis_duration"));
        owner.sendActionBar(Text.mm("<" + LILAC + ">◈ 분신</" + LILAC + "> <gray>분신을 남기고 사라졌습니다</gray>"));
        return ActivationResult.success();
    }

    // ------------------------------------------------------------------ ultimate: legion of mirages

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 사용할 수 없습니다");
        }
        int count = Math.clamp(params().integer("legion_clones"), 1, MAX_CLONES);
        long lifetime = params().ticks("legion_duration");
        Location origin = owner.getLocation();
        List<Location> spots = ringSpots(owner, origin, count, Math.max(1.5, params().num("legion_spread")));

        // Trade places with one of the copies: the owner goes to a random spot, a copy takes the old place.
        List<Location> copySpots = new ArrayList<>(spots);
        boolean swapped = false;
        if (!copySpots.isEmpty()) {
            Location target = copySpots.remove(ThreadLocalRandom.current().nextInt(copySpots.size()));
            target.setYaw(origin.getYaw());
            target.setPitch(origin.getPitch());
            if (owner.teleport(target, TeleportCause.PLUGIN)) {
                owner.setFallDistance(0);
                swapped = true;
            } else {
                copySpots.add(target);
            }
        }
        copySpots.addFirst(origin);
        while (copySpots.size() > count) {
            copySpots.removeLast();
        }

        Vector facing = Motion.horizontal(origin.getDirection());
        int made = 0;
        for (Location spot : copySpots) {
            Copy copy = spawnCopy(spot, lifetime);
            if (copy == null) {
                continue;
            }
            made++;
            Vector away = Motion.horizontal(spot.toVector().subtract(origin.toVector()));
            Vector direction = away.lengthSquared() > 1.0E-4 ? away : facing;
            if (direction.lengthSquared() > 1.0E-4) {
                copy.run(direction, LEGION_RUN_SPEED, ctx().currentTick() + LEGION_RUN_TICKS);
            }
            copyShimmer(copy, false);
            if (spot != origin) {
                // Blink lines out of the origin, only to the copies: the real one's spot stays dark.
                Location chest = origin.clone().add(0, 1.2, 0);
                Location to = spot.clone().add(0, 1.2, 0);
                Vfx.trail(chest, to, PALETTE.core(), 5, true);
                Vfx.trail(chest.clone().add(0, -0.4, 0), to.clone().add(0, -0.4, 0), PALETTE.body(), 6, true);
            }
        }
        if (made == 0) {
            if (swapped) {
                owner.teleport(origin, TeleportCause.PLUGIN);
            }
            return ActivationResult.fail("분신을 만들 수 없습니다");
        }

        Location center = origin.clone().add(0, 1.0, 0);
        Vfx.burst(ctx(), center, PALETTE, 1.2, Particle.REVERSE_PORTAL, true);
        Vfx.shockwave(ctx(), owner, origin.clone().add(0, 0.15, 0), 0.5,
                Math.max(1.5, params().num("legion_spread")) + 0.8, 6, Particle.DUST_COLOR_TRANSITION, MIRAGE_DUST,
                true);
        hallOfMirrors(origin);
        Fx.sound(origin, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.0f, 1.0f);
        Fx.sound(origin, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 0.8f);
        Fx.sound(origin, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 0.7f);

        startStealth(owner, params().ticks("legion_invis"));
        owner.sendActionBar(Text.mm("<" + LILAC + ">◈ 환영 군단</" + LILAC + "> <white>분신 " + made + "개</white>"
                + (swapped ? " <gray>· 자리를 바꿨습니다</gray>" : "")));
        return ActivationResult.success();
    }

    /**
     * Up to {@code count} safe standing spots around {@code origin} ({@link Spots#isSafe}, so also inside the arena),
     * spread apart and in line of sight. They fit a full-size player, since copies are full size.
     */
    private List<Location> ringSpots(Player owner, Location origin, int count, double distance) {
        List<Location> spots = new ArrayList<>();
        World world = origin.getWorld();
        double scale = Body.scaleOf(owner);
        Body body = new Body(Body.PLAYER.width() * Math.max(1.0, scale), Body.PLAYER.height() * Math.max(1.0, scale),
                Body.PLAYER.eyeHeight() * scale);
        Location eye = origin.clone().add(0, body.eyeHeight(), 0);
        double baseAngle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        for (double d : new double[] {distance, distance * 0.65}) {
            for (int index : RING_ORDER) {
                if (spots.size() >= count) {
                    return spots;
                }
                double angle = baseAngle + Math.PI * 2 * index / RING_ORDER.length;
                Location base = origin.clone().add(Math.cos(angle) * d, 0, Math.sin(angle) * d);
                Location spot = standingSpotNear(world, base, body);
                if (spot == null || tooClose(spot, spots) || spot.distanceSquared(origin) < 1.0
                        || !Spots.clearPath(eye, spot.clone().add(0, Body.PLAYER.eyeHeight(), 0))) {
                    continue;
                }
                spot.setYaw((float) Math.toDegrees(Math.atan2(-(spot.getX() - origin.getX()),
                        spot.getZ() - origin.getZ())));
                spots.add(spot);
            }
        }
        return spots;
    }

    private @Nullable Location standingSpotNear(World world, Location base, Body body) {
        for (double lift : LIFTS) {
            Location feet = base.clone().add(0, lift, 0);
            Spots.snapToGround(world, feet, GROUND_SNAP);
            if (Spots.isSafe(ctx(), feet, body)) {
                return feet;
            }
        }
        return null;
    }

    private static boolean tooClose(Location spot, List<Location> others) {
        for (Location other : others) {
            if (other.distanceSquared(spot) < MIN_SPOT_SPACING * MIN_SPOT_SPACING) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ copies

    /** One mannequin copy of the owner and its little imitation of a player. */
    private final class Copy {
        private final Mannequin body;
        private final long expireTick;
        private @Nullable Vector runDirection;
        private double runSpeed;
        private long runUntilTick;
        private @Nullable LivingEntity focus;
        private long nextThinkTick;
        private long nextSwingTick;
        private boolean shattered;

        Copy(Mannequin body, long expireTick) {
            this.body = body;
            this.expireTick = expireTick;
        }

        void run(Vector direction, double speed, long untilTick) {
            this.runDirection = direction.clone().setY(0).normalize();
            this.runSpeed = speed;
            this.runUntilTick = untilTick;
            face(runDirection);
        }

        void step(long tick) {
            if (runDirection != null && tick < runUntilTick) {
                if (!move(runDirection, runSpeed)) {
                    runDirection = null;
                }
                return;
            }
            runDirection = null;
            if (tick >= nextThinkTick) {
                nextThinkTick = tick + THINK_INTERVAL;
                List<LivingEntity> enemies = ctx().enemiesNear(player(), body.getLocation(), CHASE_RANGE);
                focus = enemies.isEmpty() ? null : enemies.getFirst();
            }
            LivingEntity target = focus;
            if (target == null || !target.isValid() || target.getWorld() != body.getWorld()) {
                halt();
                return;
            }
            Location eye = target.getEyeLocation();
            body.lookAt(eye.getX(), eye.getY(), eye.getZ(), LookAnchor.EYES);
            body.setBodyYaw(body.getLocation().getYaw());
            Vector toward = Motion.horizontal(target.getLocation().toVector().subtract(body.getLocation().toVector()));
            double distance = target.getLocation().distance(body.getLocation());
            if (distance > SWING_RANGE) {
                if (toward.lengthSquared() > 1.0E-4) {
                    move(toward.normalize(), WALK_SPEED);
                }
                return;
            }
            halt();
            if (tick >= nextSwingTick) {
                body.swingMainHand();
                nextSwingTick = tick + 8 + ThreadLocalRandom.current().nextInt(8);
            }
        }

        /** Walks one tick along {@code direction}; returns false if the way is blocked or unsafe. */
        private boolean move(Vector direction, double speed) {
            Location feet = body.getLocation();
            World world = feet.getWorld();
            Location ahead = feet.clone().add(direction.clone().multiply(0.7));
            if (!hasFooting(world, ahead)) {
                halt();
                return false;
            }
            double vy = body.getVelocity().getY();
            if (!passable(world, ahead, 0.2)) {
                if (!body.isOnGround() || !passable(world, ahead, 1.2) || !passable(world, ahead, 2.2)
                        || !passable(world, feet, 2.2)) {
                    halt();
                    return false;
                }
                vy = JUMP_VELOCITY;
            }
            body.setVelocity(new Vector(direction.getX() * speed, vy, direction.getZ() * speed));
            if (runDirection != null) {
                face(direction);
            }
            return true;
        }

        private void halt() {
            Vector velocity = body.getVelocity();
            if (Math.abs(velocity.getX()) > 1.0E-3 || Math.abs(velocity.getZ()) > 1.0E-3) {
                body.setVelocity(new Vector(0, velocity.getY(), 0));
            }
        }

        private void face(Vector direction) {
            float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
            body.setRotation(yaw, 0);
            body.setBodyYaw(yaw);
        }
    }

    private @Nullable Copy spawnCopy(Location at, long lifetime) {
        Player owner = player();
        while (copies.size() >= MAX_CLONES) {
            Copy oldest = copies.removeFirst();
            shatter(oldest, false);
        }
        Mannequin body;
        try {
            body = at.getWorld().spawn(at, Mannequin.class, mannequin -> dress(mannequin, owner, at));
        } catch (RuntimeException e) {
            ctx().plugin().getLogger().log(Level.WARNING, "Could not spawn an illusionist copy", e);
            return null;
        }
        if (!body.isValid()) {
            // The spawn was cancelled (e.g. by a protection plugin).
            return null;
        }
        ctx().summon(owner, body);
        registerGuard();
        Copy copy = new Copy(body, ctx().currentTick() + Math.max(1, lifetime));
        copies.add(copy);
        return copy;
    }

    /** Makes the mannequin look exactly like the owner: skin, name, hand, armor and a real weapon. */
    private static void dress(Mannequin mannequin, Player owner, Location at) {
        mannequin.setPersistent(false);
        mannequin.setRotation(at.getYaw(), at.getPitch());
        mannequin.setBodyYaw(at.getYaw());
        mannequin.setProfile(ResolvableProfile.resolvableProfile(owner.getPlayerProfile()));
        try {
            mannequin.setSkinParts(owner.getClientOption(ClientOption.SKIN_PARTS));
        } catch (RuntimeException ignored) {
            // Skin parts are cosmetic; the default (all parts) is fine.
        }
        mannequin.setMainHand(owner.getMainHand());
        mannequin.setDescription(null);
        mannequin.customName(owner.teamDisplayName());
        mannequin.setCustomNameVisible(nameTagShown(owner));
        // Several copies spawn close together; they should not shove each other (or the owner) around.
        mannequin.setCollidable(false);

        // No drop chances: a mannequin is not a Mob (Paper rejects them), and copies are summons, whose death drops
        // the match clears anyway.
        EntityEquipment equipment = mannequin.getEquipment();
        EntityEquipment worn = owner.getEquipment();
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.OFF_HAND}) {
            equipment.setItem(slot, copyOf(worn.getItem(slot)));
        }
        equipment.setItem(EquipmentSlot.HAND, weaponOf(owner));
    }

    /** Copies hold a real weapon rather than the ability item the owner just used. */
    private static @Nullable ItemStack weaponOf(Player owner) {
        PlayerInventory inventory = owner.getInventory();
        ItemStack held = inventory.getItemInMainHand();
        if (isWeapon(held)) {
            return held.clone();
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && isWeapon(item)) {
                return item.clone();
            }
        }
        return held.isEmpty() || held.getType() == Material.IRON_INGOT ? null : held.clone();
    }

    private static boolean isWeapon(ItemStack item) {
        if (item.isEmpty()) {
            return false;
        }
        Material type = item.getType();
        return Tag.ITEMS_SWORDS.isTagged(type) || Tag.ITEMS_AXES.isTagged(type) || type == Material.BOW
                || type == Material.CROSSBOW || type == Material.TRIDENT || type == Material.MACE;
    }

    private static @Nullable ItemStack copyOf(@Nullable ItemStack item) {
        return item == null || item.isEmpty() ? null : item.clone();
    }

    /** Copies only show a name tag if enemies would see the owner's. */
    private static boolean nameTagShown(Player owner) {
        try {
            Team team = owner.getScoreboard().getEntityTeam(owner);
            if (team == null) {
                return true;
            }
            Team.OptionStatus status = team.getOption(Team.Option.NAME_TAG_VISIBILITY);
            return status == Team.OptionStatus.ALWAYS || status == Team.OptionStatus.FOR_OTHER_TEAMS;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void tickCopies(long tick) {
        Iterator<Copy> iterator = copies.iterator();
        while (iterator.hasNext()) {
            Copy copy = iterator.next();
            if (copy.shattered || !copy.body.isValid()) {
                iterator.remove();
                continue;
            }
            if (tick >= copy.expireTick) {
                iterator.remove();
                shatter(copy, false);
                continue;
            }
            copy.step(tick);
        }
        if (copies.isEmpty()) {
            unregisterGuard();
        }
    }

    private @Nullable Copy findCopy(Entity entity) {
        if (!(entity instanceof Mannequin)) {
            return null;
        }
        for (Copy copy : copies) {
            if (copy.body.equals(entity)) {
                return copy;
            }
        }
        return null;
    }

    /** Removes a copy in a burst of mirage dust; {@code struck} adds the glass-shatter flourish. */
    private void shatter(Copy copy, boolean struck) {
        copy.shattered = true;
        Mannequin body = copy.body;
        if (body.isValid()) {
            Location feet = body.getLocation();
            Location center = feet.clone().add(0, body.getHeight() * 0.5, 0);
            if (struck) {
                // Shattered like glass: a flash, shards of purple glass and crystal splinters flying off.
                Vfx.dot(center, Particle.FLASH, PALETTE.core(), false);
                glassShatter(center, 0.45, 28);
                Vfx.cloud(ctx(), center, Particle.DUST_COLOR_TRANSITION, 16, 0.4, 0, SHATTER_DUST, false);
                Vfx.cloud(ctx(), center, Particle.ENCHANTED_HIT, 10, 0.35, 0.3, null, false);
                shardBurst(center, SHARDS, 1.6);
                Fx.sound(center, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.2f + Fx.jitter(0.1f));
                Fx.sound(center, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.9f, 1.6f);
                Fx.sound(center, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.8f, 1.1f + Fx.jitter(0.1f));
            } else {
                // Fades like a mirage: the body dissolves upwards into lilac motes.
                dissolve(feet, body.getHeight());
                Fx.sound(center, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.5f, 1.8f + Fx.jitter(0.1f));
            }
            body.remove();
        }
    }

    /** An enemy struck a copy: it shatters and the striker is blinded by the mirage. */
    private void onCopyStruck(Copy copy, Entity damager) {
        if (copy.shattered || !isHostile(damager)) {
            return;
        }
        Player owner = player();
        LivingEntity striker = null;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof LivingEntity living && ctx().isEnemy(owner, living)) {
                striker = living;
            }
        } else if (damager instanceof LivingEntity living && ctx().isEnemy(owner, living)) {
            striker = living;
        }
        if (striker == null) {
            Player attacker = ctx().resolveAttacker(damager);
            if (attacker != null && ctx().isEnemy(owner, attacker)) {
                striker = attacker;
            }
        }
        copies.remove(copy);
        shatter(copy, true);
        if (striker == null) {
            return;
        }
        int blind = (int) params().ticks("clone_blind");
        if (blind > 0) {
            striker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blind, 0, false, true, true));
        }
        if (striker instanceof Mob mob && owner.equals(mob.getTarget())) {
            mob.setTarget(null);
        }
        Location eye = striker.getEyeLocation();
        Location from = copy.body.getLocation().add(0, copy.body.getHeight() * 0.6, 0);
        Vfx.trail(from, eye, PALETTE.body(), 4, false);
        Vfx.trail(from.clone().add(0, 0.2, 0), eye, PALETTE.core(), 5, false);
        Vfx.cloud(ctx(), eye, Particle.DUST_COLOR_TRANSITION, 8, 0.25, 0, SHATTER_DUST, false);
        if (striker instanceof Player victim) {
            victim.playSound(victim, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, SoundCategory.PLAYERS, 0.9f, 1.2f);
            victim.sendActionBar(Text.mm("<" + LILAC + ">환영이었습니다!</" + LILAC + ">"));
        }
        if (owner.isOnline()) {
            owner.sendActionBar(Text.mm("<" + LILAC + ">◈ 분신이 깨졌습니다</" + LILAC + "> <gray>→</gray> "
                    + "<white><name></white> <gray>실명</gray>", Placeholder.component("name", striker.name())));
        }
    }

    /**
     * Copies never take real damage: every hit is cancelled first thing (lowest priority, before kill credit,
     * gauges and on-hit effects see it). Only hits by enemies shatter them.
     */
    private final class CopyGuard implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void onDamage(EntityDamageEvent event) {
            Copy copy = findCopy(event.getEntity());
            if (copy == null) {
                return;
            }
            event.setCancelled(true);
            try {
                if (event instanceof EntityDamageByEntityEvent byEntity) {
                    onCopyStruck(copy, byEntity.getDamager());
                } else if (event.getCause() == DamageCause.VOID) {
                    copies.remove(copy);
                    shatter(copy, false);
                }
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Illusionist copy hit handling failed", e);
            }
        }
    }

    private void registerGuard() {
        if (guard == null) {
            guard = new CopyGuard();
            Bukkit.getPluginManager().registerEvents(guard, ctx().plugin());
        }
    }

    private void unregisterGuard() {
        if (guard != null) {
            HandlerList.unregisterAll(guard);
            guard = null;
        }
    }

    // ------------------------------------------------------------------ stealth

    private void startStealth(Player owner, long ticks) {
        if (ticks <= 0) {
            return;
        }
        // Allies and spectators keep seeing the vanilla translucent body; enemies do not see the owner at all.
        owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) ticks + 5, 0, false, false, true));
        stealth.begin(ctx(), owner, ticks, () -> reveal(true));
        redirectAggro(owner);
    }

    private void tickStealth(Player owner, long tick) {
        if (tick % 5 == 0) {
            owner.sendActionBar(Text.mm("<" + LILAC + ">◈ 투명</" + LILAC + "> <gray>"
                    + Fx.seconds(stealth.ticksLeft(ctx().currentTick())) + "초</gray>"));
        }
        if (tick % 20 == 0) {
            stealth.hideFromEnemies(ctx(), owner);
            redirectAggro(owner);
        }
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        // Only the owner's own attacks give it away; hits routed from its summons (the copies) do not.
        if (stealth.isActive() && !event.isCancelled() && isOwnHit(event)) {
            reveal(true);
        }
    }

    /** The owner's own hits: melee, its projectiles and its ability damage, but not its summons'. */
    private boolean isOwnHit(EntityDamageByEntityEvent event) {
        Player owner = player();
        Entity damager = event.getDamager();
        return owner.equals(damager)
                || damager instanceof Projectile projectile && owner.equals(projectile.getShooter());
    }

    /** Enemy mobs chasing the owner turn on the nearest copy instead (or lose track). */
    private void redirectAggro(Player owner) {
        Stealth.redirectAggro(ctx(), owner, AGGRO_RANGE, mob -> nearestCopy(mob.getLocation()));
    }

    private @Nullable LivingEntity nearestCopy(Location from) {
        Mannequin best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Copy copy : copies) {
            if (copy.shattered || !copy.body.isValid() || copy.body.getWorld() != from.getWorld()) {
                continue;
            }
            double distance = copy.body.getLocation().distanceSquared(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = copy.body;
            }
        }
        return best;
    }

    private void reveal(boolean feedback) {
        Player owner = player();
        if (!stealth.end(ctx(), owner) || !owner.isOnline()) {
            return;
        }
        PotionEffect invisibility = owner.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (invisibility != null && !invisibility.hasParticles()) {
            owner.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
        if (feedback) {
            // The mirage peels away: a shimmer of lilac motes around the body as it becomes visible again.
            Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
            Vfx.sphere(ctx(), body, 0.6 * Body.scaleOf(owner), 14, Particle.DUST_COLOR_TRANSITION, MIRAGE_DUST,
                    false);
            Vfx.dot(body, Particle.ENCHANTED_HIT, null, false);
            Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.5f, 1.5f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 1.7f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ visuals

    /** A shimmering ring sweeping up a body from the feet to the head. */
    private void shimmerUp(Location feet, double height, double radius) {
        Location base = feet.clone();
        Loop.repeat(ctx(), player(), 0, 1, SHIMMER_TICKS, run -> {
            Location at = base.clone().add(0, height * run / (SHIMMER_TICKS - 1.0), 0);
            Vfx.ring(ctx(), at, radius, 10, run * 0.6, Particle.DUST_COLOR_TRANSITION, MIRAGE_DUST, false);
            if (run % 2 == 0) {
                Vfx.ring(ctx(), at, radius * 0.8, 3, run * 0.6 + 0.5, Particle.ENCHANTED_HIT, null, false);
            }
            return true;
        });
    }

    /**
     * A copy shimmers into being; with {@code mirror} a mirror pane stands in front of it and turns edge-on, as if
     * the copy stepped out of it.
     */
    private void copyShimmer(Copy copy, boolean mirror) {
        Mannequin body = copy.body;
        Location feet = body.getLocation();
        shimmerUp(feet, body.getHeight(), 0.5);
        if (!mirror) {
            return;
        }
        Location at = feet.clone().add(0, body.getHeight() * 0.5, 0).add(Motion.horizontalFacing(feet).multiply(0.45));
        at.setYaw(0);
        at.setPitch(0);
        float yaw = feet.getYaw();
        BlockDisplay pane = Displays.block(ctx(), player(), at, MIRROR_GLASS, Displays.transform(0, 0, 0,
                MIRROR_WIDTH, MIRROR_HEIGHT, MIRROR_THICKNESS, yaw, 0, 0), MIRROR_TURN_TICKS + 2);
        Displays.animateNextTick(ctx(), player(), pane, Displays.transform(0, 0, 0, 0.001f, MIRROR_HEIGHT,
                MIRROR_THICKNESS, yaw, 0, 0), MIRROR_TURN_TICKS);
    }

    /** Shards of purple glass bursting out of {@code at}. */
    private void glassShatter(Location at, double spread, int count) {
        at.getWorld().spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), count), spread, spread * 1.3, spread, 0.1,
                MIRROR_GLASS, false);
    }

    /** Crystal splinters (item displays) flying out of {@code center}, tumbling, then fading. */
    private void shardBurst(Location center, int wanted, double reach) {
        long now = ctx().currentTick();
        if (now - shardWindowStart >= SHARD_WINDOW_TICKS) {
            shardWindowStart = now;
            shardsInWindow = 0;
        }
        int count = Math.min(wanted, MAX_SHARDS_PER_WINDOW - shardsInWindow);
        if (count <= 0) {
            return;
        }
        shardsInWindow += count;
        Player owner = player();
        Location at = center.clone();
        at.setYaw(0);
        at.setPitch(0);
        ItemStack shard = new ItemStack(Material.AMETHYST_SHARD);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double base = random.nextDouble(Math.PI * 2);
        for (int i = 0; i < count; i++) {
            double angle = base + i * Math.PI * 2 / count + random.nextDouble(-0.3, 0.3);
            ItemDisplay display = Displays.item(ctx(), owner, at, shard, Displays.centered(0, 0, 0, 0.3f, 0.3f, 0.3f,
                    (float) Math.toDegrees(angle), 0, 0), SHARD_FLIGHT_TICKS + 10);
            double distance = reach * random.nextDouble(0.7, 1.0);
            Transformation out = Displays.centered((float) (Math.cos(angle) * distance),
                    (float) random.nextDouble(-0.3, 0.6), (float) (Math.sin(angle) * distance), 0.4f, 0.4f, 0.4f,
                    random.nextInt(360), random.nextInt(-90, 90), random.nextInt(360));
            Displays.animateNextTick(ctx(), owner, display, out, SHARD_FLIGHT_TICKS);
            ctx().runLater(owner, SHARD_FLIGHT_TICKS + 1, () -> Displays.shrinkAway(ctx(), owner, display, 6));
        }
    }

    /** An expiring copy fades like a mirage: rings of lilac motes rising off it. */
    private void dissolve(Location feet, double height) {
        Location base = feet.clone();
        Loop.repeat(ctx(), player(), 0, 2, 4, run -> {
            Location at = base.clone().add(0, height * (0.2 + 0.25 * run), 0);
            Vfx.ring(ctx(), at, 0.4 - 0.08 * run, 8, run * 0.8, Particle.DUST_COLOR_TRANSITION, MIRAGE_DUST, false);
            Vfx.cloud(ctx(), at, Particle.REVERSE_PORTAL, 3, 0.2, 0.02, null, false);
            return true;
        });
        Vfx.cloud(ctx(), base.clone().add(0, height * 0.5, 0), Particle.POOF, 4, 0.25, 0.02, null, false);
    }

    /**
     * The legion's centrepiece: a ring of mirrors rises around the origin, turns a quarter circle like a carousel
     * and shatters outwards.
     */
    private void hallOfMirrors(Location origin) {
        Player owner = player();
        Location at = origin.clone();
        at.setYaw(0);
        at.setPitch(0);
        double base = ThreadLocalRandom.current().nextDouble(360);
        BlockDisplay[] mirrors = new BlockDisplay[HALL_MIRRORS];
        long lifetime = (long) HALL_STEPS * HALL_STEP_TICKS + 12;
        for (int i = 0; i < HALL_MIRRORS; i++) {
            double angle = base + i * 360.0 / HALL_MIRRORS;
            mirrors[i] = Displays.block(ctx(), owner, at, MIRROR_GLASS, hallPane(angle, 0.05f), lifetime);
        }
        Loop.repeat(ctx(), owner, 1, HALL_STEP_TICKS, HALL_STEPS, run -> {
            for (int i = 0; i < HALL_MIRRORS; i++) {
                double angle = base + i * 360.0 / HALL_MIRRORS + (run + 1) * HALL_STEP_DEGREES;
                Displays.animate(mirrors[i], hallPane(angle, 1f), HALL_STEP_TICKS);
            }
            return true;
        }, () -> {
            for (int i = 0; i < HALL_MIRRORS; i++) {
                if (!mirrors[i].isValid()) {
                    continue;
                }
                double angle = Math.toRadians(base + i * 360.0 / HALL_MIRRORS + HALL_STEPS * HALL_STEP_DEGREES);
                Location center = at.clone().add(Math.cos(angle) * HALL_RADIUS, MIRROR_HEIGHT * 0.45,
                        Math.sin(angle) * HALL_RADIUS);
                glassShatter(center, 0.35, 10);
                Displays.shrinkAway(ctx(), owner, mirrors[i], 4);
            }
            Fx.sound(origin, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.9f + Fx.jitter(0.1f));
            Fx.sound(origin, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.9f, 0.8f + Fx.jitter(0.1f));
        });
    }

    /** One hall mirror: a pane standing on the ring at {@code angleDegrees}, facing the centre. */
    private static Transformation hallPane(double angleDegrees, float size) {
        double angle = Math.toRadians(angleDegrees);
        float height = MIRROR_HEIGHT * 0.9f;
        // A display's yaw turns its local +Z towards (-sin yaw, cos yaw): yaw = angle - 90 points it along the radius.
        return Displays.transform((float) (Math.cos(angle) * HALL_RADIUS), height * 0.5f,
                (float) (Math.sin(angle) * HALL_RADIUS), MIRROR_WIDTH * size, height * size, MIRROR_THICKNESS,
                (float) (angleDegrees - 90), 0, 0);
    }

    // ------------------------------------------------------------------ world checks

    /** A copy only walks where there is solid, safe ground within a short drop (never off a cliff or into lava). */
    private static boolean hasFooting(World world, Location ahead) {
        int x = ahead.getBlockX();
        int z = ahead.getBlockZ();
        int y = (int) Math.floor(ahead.getY() + 0.01);
        if (Spots.isHazard(world.getBlockAt(x, y, z))) {
            return false;
        }
        for (int depth = 0; depth <= GROUND_SEARCH_DEPTH; depth++) {
            Block below = world.getBlockAt(x, y - 1 - depth, z);
            if (Spots.isHazard(below) || below.isLiquid()) {
                return false;
            }
            if (below.isSolid()) {
                return true;
            }
        }
        return false;
    }

    private static boolean passable(World world, Location feet, double heightAbove) {
        return world.getBlockAt(feet.getBlockX(), (int) Math.floor(feet.getY() + heightAbove), feet.getBlockZ())
                .isPassable();
    }
}
