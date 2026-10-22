package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Vibration;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 음파술사 — a warden-like listener in the dark. It feels the vibrations of enemies that run or use skills nearby
 * and makes them glow, fires a charged sonic boom that pierces every enemy in a line, and lets out a shriek that
 * spreads a curtain of darkness which blinds and silences everyone it washes over.
 *
 * <p>Look (pale cyan, sculk teal, deep sculk): detected enemies send a vibration flying to the listener, who hears a
 * sculk click from their direction. The boom charges as three echo shards spiralling into the chest inside teal rings
 * with a heartbeat, then the warden's sonic rings race down the line with expanding shock rings, the shards riding
 * the front, and soul sparks on every hit. For the shriek, sculk shriekers push up out of the ground around the
 * listener while souls are drawn in, then a wall of dark dust and souls sweeps outwards over sliding sculk patches,
 * leaving struck enemies with a shrinking silence ring.
 */
public final class SonicAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("sonic", "음파술사", Material.ECHO_SHARD)
            .color(TextColor.color(0x29DFCB))
            .difficulty(2)
            .passive("진동 감지",
                    "{sense_radius}블록 안에서 달리거나 스킬을 쓴 적의 진동을 느껴 {sense_glow}초간 발광시킵니다. "
                            + "웅크린 채 움직이면 발소리는 감출 수 있지만, 스킬을 쓰는 진동은 숨길 수 없습니다.")
            .active("음파 폭발",
                    "{boom_charge}초간 음파를 모은 뒤 바라보는 방향으로 {boom_range}블록 음파를 쏩니다. 경로의 모든 적을 "
                            + "관통해 {boom_damage} 피해(방어구 무시)를 주고 밀쳐내며, 벽은 뚫지 못합니다.",
                    9)
            .ultimate("어둠의 장막",
                    "날카로운 비명과 함께 어둠이 반경 {ult_radius}블록으로 퍼집니다. 닿은 적은 {ult_damage} 피해를 받고 "
                            + "{ult_darkness}초간 어둠에 갇히며 {ult_silence}초간 침묵합니다.")
            .param("sense_radius", 12)
            .param("sense_glow", 1)
            .param("boom_charge", 0.4)
            .param("boom_range", 16)
            .param("boom_width", 1)
            .param("boom_damage", 6)
            .param("ult_radius", 15)
            .param("ult_damage", 3)
            .param("ult_darkness", 6)
            .param("ult_silence", 2)
            .build();

    private static final String TEAL = "#29DFCB";
    /** Pale cyan core, sculk teal body, deep sculk edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xCFFFF8, 0x29DFCB, 0x0A2E36);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.1f);
    private static final Particle.DustTransition GLOW_SMALL = PALETTE.glow(0.7f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.2f);
    private static final Particle.DustOptions TEAL_DUST = PALETTE.dust(0.9f);
    private static final Particle.DustOptions DARK_DUST = new Particle.DustOptions(PALETTE.edge(), 1.7f);
    private static final Particle.DustOptions DARK_SMALL = new Particle.DustOptions(PALETTE.edge(), 1.1f);

    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.0;

    // passive
    private static final long SENSE_PERIOD = 4;
    /** A jump in an enemy's remaining active cooldown larger than this (seconds) means it just used its active. */
    private static final double COOLDOWN_JUMP = 0.3;
    /** A drop of the ultimate gauge at least this large means the enemy just used its ultimate. */
    private static final double GAUGE_DROP = 50;
    private static final long SENSE_FX_GAP = 12;
    private static final long CLEANUP_PERIOD = 200;

    // active
    private static final double FRONT_SPEED = 6;
    private static final double BOOM_PUSH = 1.1;
    private static final double BOOM_LIFT = 0.35;
    private static final int CHARGE_RINGS = 3;
    private static final int CHARGE_SHARDS = 3;
    private static final long SHARD_FLIGHT_LIFE = 14;
    private static final ItemStack ECHO_SHARD = ItemStack.of(Material.ECHO_SHARD);

    // ultimate
    private static final long SHRIEK_TICKS = 8;
    private static final double WAVE_SPEED = 1.4;
    private static final int SCULK_PATCHES = 10;
    private static final int SHRIEKERS = 4;
    private static final double SHRIEKER_DISTANCE = 1.5;
    private static final float SHRIEKER_SIZE = 0.75f;
    private static final BlockData SHRIEKER = Material.SCULK_SHRIEKER.createBlockData();
    private static final BlockData SCULK = Material.SCULK.createBlockData();
    /** Sculk stays dim: darkness spreading, not light. */
    private static final Display.Brightness SCULK_LIGHT = new Display.Brightness(7, 7);
    private static final int AFTERGLOW_TICKS = 16;

    /** What the listener last heard from each enemy. */
    private final Map<UUID, Echo> echoes = new HashMap<>();

    private static final class Echo {
        boolean known;
        double cooldown;
        double gauge;
        long nextFx;
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        echoes.clear();
    }

    // ------------------------------------------------------------------ passive: vibration sense

    @Override
    public void onTick(long tick) {
        if (tick % SENSE_PERIOD != 0) {
            return;
        }
        Player owner = player();
        if (tick % CLEANUP_PERIOD == 0) {
            echoes.keySet().removeIf(id -> {
                Player other = owner.getServer().getPlayer(id);
                return other == null || !ctx().isAlive(other);
            });
        }
        double radius = params().num("sense_radius");
        Location at = owner.getLocation();
        for (Player other : ctx().alivePlayers()) {
            if (other.equals(owner) || !ctx().isEnemy(owner, other)) {
                continue;
            }
            Echo echo = echoes.computeIfAbsent(other.getUniqueId(), id -> new Echo());
            double cooldown = ctx().cooldownRemaining(other);
            double gauge = ctx().gauge(other);
            boolean skill = echo.known
                    && (cooldown > echo.cooldown + COOLDOWN_JUMP || echo.gauge - gauge >= GAUGE_DROP);
            echo.known = true;
            echo.cooldown = cooldown;
            echo.gauge = gauge;
            boolean running = other.isSprinting() && !other.isSneaking();
            if (!skill && !running || !other.getWorld().equals(owner.getWorld())
                    || other.getLocation().distanceSquared(at) > radius * radius) {
                continue;
            }
            sense(other, echo, skill);
        }
    }

    /** The enemy's vibration reaches the listener: it glows, and the listener sees and hears where it came from. */
    private void sense(Player enemy, Echo echo, boolean skill) {
        int glow = (int) Math.min(Integer.MAX_VALUE, params().ticks("sense_glow"));
        if (glow > 0) {
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glow + (int) SENSE_PERIOD, 0, false,
                    false, true));
        }
        long now = ctx().currentTick();
        if (!skill && now < echo.nextFx) {
            return;
        }
        echo.nextFx = now + SENSE_FX_GAP;
        Player owner = player();
        Location feet = enemy.getLocation().add(0, 0.2, 0);
        int arrival = (int) Math.clamp(feet.distance(owner.getLocation()) * 0.8, 5, 20);
        enemy.getWorld().spawnParticle(Particle.VIBRATION, feet, 1, 0, 0, 0, 0,
                new Vibration(new Vibration.Destination.EntityDestination(owner), arrival));
        Vfx.ring(ctx(), feet, skill ? 0.8 : 0.55, skill ? 12 : 8, now * 0.3, Particle.DUST_COLOR_TRANSITION,
                GLOW_SMALL, false);
        Vfx.cloud(ctx(), feet, Particle.SCULK_CHARGE_POP, skill ? 5 : 2, 0.3, 0.02, null, false);
        // Only the listener hears it, from the enemy's direction.
        owner.playSound(feet, Sound.BLOCK_SCULK_SENSOR_CLICKING, SoundCategory.PLAYERS, 0.9f,
                (skill ? 0.8f : 1.2f) + Fx.jitter(0.1f));
        if (skill) {
            owner.playSound(feet, Sound.BLOCK_SCULK_CATALYST_BLOOM, SoundCategory.PLAYERS, 0.8f, 1.2f);
        }
        owner.sendActionBar(Text.mm("<" + TEAL + ">◎ 진동 감지</" + TEAL + "> <white>" + enemy.getName()
                + "</white> <gray>" + (skill ? "스킬 사용" : "달리는 중") + "</gray>"));
    }

    // ------------------------------------------------------------------ active: sonic boom

    @Override
    public ActivationResult onActive() {
        new Charge(params().ticks("boom_charge")).begin();
        return ActivationResult.success();
    }

    /** Where the boom gathers and leaves: just in front of the chest. */
    private static Location chest(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.add(eye.getDirection().normalize().multiply(0.6)).subtract(0, 0.3, 0);
    }

    /**
     * The charge: echo shards spiral into the chest, teal rings close in and sculk souls are drawn in with a
     * heartbeat; then the boom fires along the owner's view at that moment.
     */
    private final class Charge implements LongPredicate {

        private final long ticks;
        private final ItemDisplay[] shards = new ItemDisplay[CHARGE_SHARDS];

        Charge(long ticks) {
            this.ticks = ticks;
        }

        void begin() {
            Player owner = player();
            Fx.sound(owner.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.0f, 1.8f + Fx.jitter(0.05f));
            Fx.sound(owner.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 1.3f);
            if (ticks <= 0) {
                fire(new ItemDisplay[0]);
                return;
            }
            for (int i = 0; i < shards.length; i++) {
                shards[i] = Displays.item(ctx(), owner, shardPoint(i, 0), ECHO_SHARD,
                        Displays.centered(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), ticks + SHARD_FLIGHT_LIFE);
                shards[i].setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, shards[i],
                        Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f, 0, 0, 0), 3);
            }
            Loop.repeat(ctx(), owner, 0, 1, ticks + 1, this);
        }

        @Override
        public boolean test(long tick) {
            if (tick >= ticks) {
                fire(shards);
                return false;
            }
            rings(tick);
            for (int i = 0; i < shards.length; i++) {
                if (shards[i] != null && shards[i].isValid()) {
                    shards[i].teleport(shardPoint(i, tick + 1));
                }
            }
            if (tick == ticks / 2) {
                Fx.sound(player().getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 1.6f);
            }
            return true;
        }

        /** A shard on its spiral into the chest, its tip pointing at the chest. */
        private Location shardPoint(int index, long tick) {
            Player owner = player();
            Location chest = chest(owner);
            Vector direction = owner.getEyeLocation().getDirection().normalize();
            Vector[] basis = basis(direction);
            double progress = Math.min(1.0, (double) tick / Math.max(1, ticks));
            double radius = 0.25 + 1.0 * (1 - progress);
            double angle = tick * 0.7 + index * Math.PI * 2 / CHARGE_SHARDS;
            Vector offset = basis[0].clone().multiply(Math.cos(angle) * radius)
                    .add(basis[1].clone().multiply(Math.sin(angle) * radius));
            Location at = chest.add(offset).add(direction.clone().multiply(0.3 * (1 - progress)));
            at.setDirection(offset.lengthSquared() > 1.0E-6 ? offset.clone().multiply(-1) : direction);
            return at;
        }

        /** Teal rings close in on the chest while sculk souls are drawn into it. */
        private void rings(long tick) {
            Player owner = player();
            Vector direction = owner.getEyeLocation().getDirection().normalize();
            Location chest = chest(owner);
            Vector[] basis = basis(direction);
            double progress = (double) (tick + 1) / ticks;
            for (int i = 0; i < CHARGE_RINGS; i++) {
                double radius = (1.6 - 1.3 * progress) * (1 - i * 0.25);
                perpendicularRing(chest.clone().add(direction.clone().multiply(i * 0.25)), basis,
                        Math.max(0.15, radius), 10, tick * 0.5 + i, Particle.DUST_COLOR_TRANSITION,
                        i == 0 ? GLOW : FADE);
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 2); i++) {
                Vector offset = basis[0].clone().multiply(random.nextGaussian())
                        .add(basis[1].clone().multiply(random.nextGaussian())).normalize().multiply(1.5);
                // Count 0: the soul flies from chest + offset into the chest.
                owner.getWorld().spawnParticle(Particle.SCULK_SOUL, chest.clone().add(offset), 0, -offset.getX(),
                        -offset.getY(), -offset.getZ(), 0.12);
            }
        }
    }

    /**
     * The boom leaves the chest: a front racing down the line, hitting every enemy it passes once; the charged echo
     * shards ride the front to the end of the line and dissolve there.
     */
    private void fire(ItemDisplay[] shards) {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        World world = owner.getWorld();
        double range = Math.max(1, params().num("boom_range"));
        RayTraceResult wall = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        double length = wall == null ? range : wall.getHitPosition().distance(eye.toVector());
        double width = Math.max(0, params().num("boom_width"));
        double damage = params().num("boom_damage");
        Vector[] basis = basis(direction);
        Location origin = eye.clone().subtract(0, 0.2, 0);

        Location muzzle = origin.clone().add(direction.clone().multiply(0.8));
        world.spawnParticle(Particle.FLASH, muzzle, 1, 0, 0, 0, 0, PALETTE.core());
        Vfx.cloud(ctx(), muzzle, Particle.SCULK_SOUL, 8, 0.2, 0.08, null, false);
        perpendicularRing(muzzle, basis, 0.7, 14, 0, Particle.DUST_COLOR_TRANSITION, GLOW);
        Fx.sound(muzzle, Sound.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 1.0f + Fx.jitter(0.06f));
        Fx.sound(muzzle, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.6f, 1.6f + Fx.jitter(0.1f));

        Set<UUID> struck = new HashSet<>();
        int[] hits = {0};
        long steps = Math.max(1, (long) Math.ceil(length / FRONT_SPEED));
        Location end = origin.clone().add(direction.clone().multiply(Math.max(0.5, length - 0.4)));
        for (ItemDisplay shard : shards) {
            if (shard == null || !shard.isValid()) {
                continue;
            }
            shard.setTeleportDuration((int) Math.min(59, steps + 1));
            Vector spread = shard.getLocation().toVector().subtract(muzzle.toVector()).multiply(0.5);
            Location target = end.clone().add(spread);
            target.setDirection(direction);
            shard.teleport(target);
            Displays.animate(shard, Displays.centered(0, 0, 0, 0.8f, 0.8f, 0.8f, 0, 0, 90), (int) steps + 1);
            ctx().runLater(owner, steps + 1, () -> {
                if (shard.isValid()) {
                    Vfx.cloud(ctx(), shard.getLocation(), Particle.SCULK_CHARGE_POP, 3, 0.1, 0.02, null, false);
                    Displays.shrinkAway(ctx(), owner, shard, 4);
                }
            });
        }
        Loop.repeat(ctx(), owner, 0, 1, steps, step -> {
            double from = step * FRONT_SPEED;
            double to = Math.min(length, from + FRONT_SPEED);
            drawSegment(origin, direction, basis, from, to, step);
            Location start = origin.clone().add(direction.clone().multiply(from));
            double segment = to - from;
            for (LivingEntity enemy : ctx().enemiesNear(owner, start, segment + width + 2)) {
                if (struck.contains(enemy.getUniqueId())) {
                    continue;
                }
                BoundingBox box = enemy.getBoundingBox().expand(width / 2);
                if (box.rayTrace(start.toVector(), direction, segment) == null && !box.contains(start.toVector())) {
                    continue;
                }
                struck.add(enemy.getUniqueId());
                hits[0]++;
                strike(enemy, direction, damage);
            }
            return true;
        }, () -> {
            if (wall != null) {
                wallImpact(wall, direction);
            }
            if (hits[0] > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + TEAL + ">≋ 음파 폭발</" + TEAL + "> <white>" + hits[0]
                        + "명 관통</white>"));
            }
        });
    }

    /** One stretch of the boom: the warden's sonic rings, a shock ring and soul sparks along it. */
    private void drawSegment(Location origin, Vector direction, Vector[] basis, double from, double to, long step) {
        World world = origin.getWorld();
        for (double d = Math.max(1, Math.ceil(from)); d <= to; d += 1) {
            Location at = origin.clone().add(direction.clone().multiply(d));
            world.spawnParticle(Particle.SONIC_BOOM, at, 1, 0, 0, 0, 0);
            if ((int) d % 2 == 0) {
                perpendicularRing(at, basis, 0.55 + 0.04 * d, 10, d * 0.4, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL);
            }
            if ((int) d % 3 == 1) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                Vfx.dot(at.clone().add(random.nextDouble(-0.4, 0.4), random.nextDouble(-0.4, 0.4),
                        random.nextDouble(-0.4, 0.4)), Particle.SCULK_CHARGE_POP, null, false);
            }
        }
        Location front = origin.clone().add(direction.clone().multiply(to));
        perpendicularRing(front, basis, 0.9, 14, step * 0.7, Particle.DUST, TEAL_DUST);
    }

    private void strike(LivingEntity enemy, Vector direction, double damage) {
        Player owner = player();
        // Magic damage like every ability: armor does not help, and (unlike the vanilla sonic boom type) the number
        // does not change with the server difficulty.
        Combat.hit(ctx(), owner, enemy, damage);
        Location body = Fx.body(enemy);
        World world = enemy.getWorld();
        world.spawnParticle(Particle.SONIC_BOOM, body, 1, 0, 0, 0, 0);
        Vfx.cloud(ctx(), body, Particle.SCULK_SOUL, 8, 0.3, 0.08, null, false);
        Vfx.sphere(ctx(), body, 0.65, 16, Particle.DUST_COLOR_TRANSITION, GLOW, false);
        Fx.sound(body, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 1.0f, 1.0f + Fx.jitter(0.1f));
        if (!Combat.isTargetable(ctx(), owner, enemy)) {
            return;
        }
        Vector push = Motion.horizontal(direction).multiply(BOOM_PUSH).setY(BOOM_LIFT);
        Motion.push(enemy, push, MAX_SPEED);
    }

    /** The boom breaks on a wall: dust from the block and a flat shock ring on its face. */
    private void wallImpact(RayTraceResult wall, Vector direction) {
        Block block = wall.getHitBlock();
        Location at = wall.getHitPosition().toLocation(player().getWorld()).subtract(direction.clone().multiply(0.2));
        if (block != null && !block.getType().isAir()) {
            at.getWorld().spawnParticle(Particle.BLOCK_CRUMBLE, at, Vfx.n(ctx(), 14), 0.4, 0.4, 0.4, 0.1,
                    block.getBlockData());
        }
        perpendicularRing(at, basis(direction), 1.1, 16, 0, Particle.DUST_COLOR_TRANSITION, FADE);
        Vfx.cloud(ctx(), at, Particle.SCULK_CHARGE_POP, 6, 0.4, 0.03, null, false);
        Fx.sound(at, Sound.BLOCK_SCULK_BREAK, 1.0f, 0.7f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ ultimate: veil of darkness

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = Math.max(1, params().num("ult_radius"));
        if (ctx().enemiesNear(owner, owner.getLocation(), radius).isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        new Veil(owner.getLocation(), radius).begin();
        return ActivationResult.success();
    }

    /**
     * The veil: a shriek, then a curtain of darkness sweeping out to the radius that strikes every enemy it
     * reaches once.
     */
    private final class Veil implements LongPredicate {

        private final Location center;
        private final World world;
        private final double radius;
        private final long waveTicks;
        private final Set<UUID> struck = new HashSet<>();
        private final List<BlockDisplay> patches = new ArrayList<>();
        private final List<BlockDisplay> shriekers = new ArrayList<>();
        private final List<Float> shriekerYaws = new ArrayList<>();
        private int hits;

        Veil(Location at, double radius) {
            this.center = at.clone();
            this.center.setPitch(0);
            this.world = at.getWorld();
            this.radius = radius;
            this.waveTicks = Math.max(2, (long) Math.ceil(radius / WAVE_SPEED));
        }

        void begin() {
            Player owner = player();
            Fx.sound(center, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 2.0f, 0.8f);
            Fx.sound(center, Sound.ENTITY_WARDEN_ROAR, 1.2f, 1.3f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.ENTITY_WARDEN_HEARTBEAT, 1.5f, 0.8f);
            for (int i = 0; i < 4; i++) {
                world.spawnParticle(Particle.SHRIEK, center.clone().add(0, 0.6, 0), 1, 0, 0, 0, 0, i * 2, true);
            }
            raiseShriekers(owner);
            Loop.repeat(ctx(), owner, 0, 1, SHRIEK_TICKS + waveTicks + 1, this, this::afterglow);
        }

        /** Sculk shriekers push up out of the ground around the listener (when it stands on something). */
        private void raiseShriekers(Player owner) {
            RayTraceResult ground = world.rayTraceBlocks(center.clone().add(0, 0.2, 0), new Vector(0, -1, 0), 3,
                    FluidCollisionMode.NEVER, true);
            if (ground == null) {
                return;
            }
            double floor = ground.getHitPosition().getY();
            double phase = Math.toRadians(center.getYaw()) + Math.PI / 4;
            for (int i = 0; i < SHRIEKERS; i++) {
                double angle = phase + i * Math.PI * 2 / SHRIEKERS;
                Location at = center.clone().add(Math.cos(angle) * SHRIEKER_DISTANCE, 0,
                        Math.sin(angle) * SHRIEKER_DISTANCE);
                at.setY(floor);
                float yaw = (float) Math.toDegrees(angle);
                BlockDisplay shrieker = Displays.block(ctx(), owner, at, SHRIEKER, rise(SHRIEKER_SIZE, 0.01f, yaw),
                        SHRIEK_TICKS + 14);
                shrieker.setBrightness(SCULK_LIGHT);
                Displays.animateNextTick(ctx(), owner, shrieker, rise(SHRIEKER_SIZE, SHRIEKER_SIZE, yaw),
                        (int) Math.max(2, SHRIEK_TICKS - 2));
                shriekers.add(shrieker);
                shriekerYaws.add(yaw);
            }
        }

        @Override
        public boolean test(long tick) {
            if (tick < SHRIEK_TICKS) {
                shriek(tick);
                return true;
            }
            long step = tick - SHRIEK_TICKS;
            if (step == 0) {
                release();
            }
            double reach = radius * Math.min(1.0, (double) (step + 1) / waveTicks);
            curtain(reach, step);
            for (LivingEntity enemy : ctx().enemiesNear(player(), center, reach)) {
                if (struck.add(enemy.getUniqueId())) {
                    strike(enemy);
                }
            }
            return step < waveTicks;
        }

        /** Darkness gathers into the listener: souls drawn in, dark rings closing on it. */
        private void shriek(long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location chest = player().getLocation().add(0, 1.0, 0);
            for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                Vector offset = new Vector(random.nextGaussian(), random.nextGaussian() * 0.5, random.nextGaussian())
                        .normalize().multiply(3 + random.nextDouble() * 2);
                world.spawnParticle(Particle.SCULK_SOUL, chest.clone().add(offset), 0, -offset.getX(), -offset.getY(),
                        -offset.getZ(), 0.18, null, true);
            }
            double closing = 3.5 * (1 - (double) (tick + 1) / SHRIEK_TICKS) + 0.4;
            Vfx.ring(ctx(), center.clone().add(0, 0.1, 0), closing, 18, tick * 0.4, Particle.DUST, DARK_DUST, true);
            Vfx.ring(ctx(), center.clone().add(0, 0.12, 0), closing * 0.8, 12, -tick * 0.4,
                    Particle.DUST_COLOR_TRANSITION, GLOW, true);
            if (tick % 3 == 0) {
                world.spawnParticle(Particle.SHRIEK, center.clone().add(0, 0.6, 0), 1, 0, 0, 0, 0, 0, true);
            }
            if (tick % 4 == 2) {
                for (BlockDisplay shrieker : shriekers) {
                    if (shrieker.isValid()) {
                        world.spawnParticle(Particle.SHRIEK, shrieker.getLocation().add(0, 0.5, 0), 1, 0, 0, 0, 0,
                                0, true);
                    }
                }
            }
        }

        /** The wave breaks loose: a flash of teal and sculk patches sliding out along the ground with it. */
        private void release() {
            Player owner = player();
            Location chest = center.clone().add(0, 1.0, 0);
            world.spawnParticle(Particle.FLASH, chest, 1, 0, 0, 0, 0, PALETTE.body(), true);
            world.spawnParticle(Particle.SONIC_BOOM, chest, 1, 0, 0, 0, 0, null, true);
            Vfx.sphere(ctx(), chest, 1.2, 30, Particle.DUST_COLOR_TRANSITION, FADE, true);
            Fx.sound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.6f, 0.6f);
            Fx.sound(center, Sound.BLOCK_SCULK_CATALYST_BLOOM, 1.5f, 0.6f);
            Fx.sound(center, Sound.BLOCK_SCULK_SPREAD, 1.5f, 0.7f);
            for (int i = 0; i < shriekers.size(); i++) {
                BlockDisplay shrieker = shriekers.get(i);
                if (shrieker.isValid()) {
                    // The shriekers sink back into the ground as the darkness leaves them.
                    Displays.animate(shrieker, rise(SHRIEKER_SIZE, 0.001f, shriekerYaws.get(i)), 8);
                    Vfx.cloud(ctx(), shrieker.getLocation().add(0, 0.3, 0), Particle.SCULK_CHARGE_POP, 4, 0.3, 0.02,
                            null, false);
                }
            }
            double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            long life = waveTicks + 14;
            for (int i = 0; i < SCULK_PATCHES; i++) {
                double angle = phase + i * Math.PI * 2 / SCULK_PATCHES;
                Vector out = new Vector(Math.cos(angle), 0, Math.sin(angle));
                Location start = center.clone().add(out.clone().multiply(0.8)).add(0, 0.03, 0);
                float yaw = (float) Math.toDegrees(angle);
                BlockDisplay patch = Displays.block(ctx(), owner, start, SCULK, patchPose(0.3f, yaw), life);
                patch.setBrightness(SCULK_LIGHT);
                patch.setTeleportDuration((int) Math.min(59, waveTicks));
                patches.add(patch);
                Location end = center.clone().add(out.multiply(radius * 0.92)).add(0, 0.03, 0);
                ctx().runLater(owner, 1, () -> {
                    if (patch.isValid()) {
                        patch.teleport(end);
                        Displays.animate(patch, patchPose(1.1f, yaw + 40), (int) waveTicks);
                    }
                });
            }
        }

        /** The curtain: a wall of dark dust and rising souls at the wave front, a faint teal ring behind it. */
        private void curtain(double reach, long step) {
            int points = (int) Math.min(110, 10 + reach * 7);
            double phase = step * 0.17;
            Location base = center.clone().add(0, 0.1, 0);
            Vfx.ring(ctx(), base, reach, points, phase, Particle.DUST, DARK_DUST, true);
            Vfx.ring(ctx(), base.clone().add(0, 0.9, 0), reach, points * 2 / 3, phase + 0.1,
                    Particle.DUST_COLOR_TRANSITION, FADE, true);
            Vfx.ring(ctx(), base.clone().add(0, 1.7, 0), reach, points / 3, phase + 0.2, Particle.DUST, DARK_SMALL,
                    true);
            Vfx.ring(ctx(), base, Math.max(0.3, reach - 1.2), points / 2, -phase, Particle.DUST_COLOR_TRANSITION,
                    GLOW_SMALL, true);
            if (step % 2 == 0) {
                Vfx.ring(ctx(), base.clone().add(0, 0.5, 0), reach, Math.max(6, points / 6), phase * 2,
                        Particle.SCULK_SOUL, null, true);
            }
        }

        /** The darkness reaches an enemy: hurt, plunged into darkness and silenced, with a ring of silence. */
        private void strike(LivingEntity enemy) {
            Player owner = player();
            Combat.hit(ctx(), owner, enemy, params().num("ult_damage"));
            hits++;
            Location body = Fx.body(enemy);
            Vfx.cloud(ctx(), body, Particle.SCULK_SOUL, 10, 0.3, 0.06, null, false);
            Vfx.cloud(ctx(), body, Particle.DUST, 12, 0.35, 0, DARK_DUST, false);
            Fx.sound(body, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 0.8f, 0.7f + Fx.jitter(0.1f));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            int darkness = (int) Math.min(Integer.MAX_VALUE, params().ticks("ult_darkness"));
            if (darkness > 0) {
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, darkness, 0, false, true, true));
            }
            long silence = params().ticks("ult_silence");
            if (enemy instanceof Player victim) {
                ctx().silence(victim, silence);
                victim.playSound(victim, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.0f, 0.7f);
                victim.playSound(victim, Sound.ENTITY_WARDEN_NEARBY_CLOSEST, SoundCategory.PLAYERS, 0.8f, 1.0f);
            }
            silenceRing(enemy, Math.max(4, silence));
        }

        /** A teal ring around the head that tightens until the silence ends. */
        private void silenceRing(LivingEntity enemy, long ticks) {
            long runs = Math.max(1, ticks / 3);
            Loop.repeat(ctx(), player(), 2, 3, runs, run -> {
                if (!Combat.isTargetable(ctx(), player(), enemy)) {
                    return false;
                }
                double left = 1 - (double) run / runs;
                Location head = enemy.getEyeLocation().add(0, 0.35, 0);
                Vfx.ring(ctx(), head, 0.25 + 0.35 * left, 9, run * 0.6, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL,
                        false);
                if (run % 3 == 0) {
                    Vfx.dot(head, Particle.SCULK_CHARGE_POP, null, false);
                }
                return true;
            });
        }

        /** Dark motes settle over the ground the veil swept, and the sculk patches sink away. */
        private void afterglow() {
            Player owner = player();
            for (BlockDisplay patch : patches) {
                if (patch.isValid()) {
                    Displays.shrinkAway(ctx(), owner, patch, 10);
                }
            }
            if (hits > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + TEAL + ">◉ 어둠의 장막</" + TEAL + "> <white>" + hits
                        + "명 침묵</white>"));
            }
            Loop.repeat(ctx(), owner, 1, 1, AFTERGLOW_TICKS, step -> {
                double fade = 1 - (double) step / AFTERGLOW_TICKS;
                int motes = (int) Math.round(Vfx.n(ctx(), 5) * fade + 0.4);
                for (int i = 0; i < motes; i++) {
                    Location at = Vfx.scatter(center, radius).add(0, 0.2 + Math.random() * 1.2, 0);
                    Vfx.dot(at, Particle.DUST, DARK_SMALL, false);
                }
                if (step % 4 == 0) {
                    Vfx.dot(Vfx.scatter(center, radius * 0.6).add(0, 0.3, 0), Particle.SCULK_SOUL, null, false);
                }
                return true;
            });
        }
    }

    // ------------------------------------------------------------------ geometry helpers

    /** A block of {@code width} standing on the display's origin, {@code height} tall, turned by {@code yawDeg}. */
    private static Transformation rise(float width, float height, float yawDeg) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yawDeg));
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, 0, -width / 2));
        return new Transformation(translation, rotation, new Vector3f(width, height, width), new Quaternionf());
    }

    /** A flat square patch lying on the ground, centered on the display's origin and turned by {@code yawDeg}. */
    private static Transformation patchPose(float size, float yawDeg) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yawDeg));
        float height = 0.06f;
        Vector3f translation = rotation.transform(new Vector3f(-size / 2, 0, -size / 2));
        return new Transformation(translation, rotation, new Vector3f(size, height, size), new Quaternionf());
    }

    /** Two unit vectors perpendicular to {@code axis} and to each other. */
    private static Vector[] basis(Vector axis) {
        Vector helper = Math.abs(axis.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = axis.clone().crossProduct(helper).normalize();
        Vector v = axis.clone().crossProduct(u).normalize();
        return new Vector[] {u, v};
    }

    /** A ring of particles around {@code center} in the plane spanned by {@code basis}. */
    private <T> void perpendicularRing(Location center, Vector[] basis, double radius, int points, double phase,
                                       Particle particle, T data) {
        int count = Vfx.n(ctx(), points);
        World world = center.getWorld();
        for (int i = 0; i < count; i++) {
            double angle = phase + i * Math.PI * 2 / count;
            Vector offset = basis[0].clone().multiply(Math.cos(angle) * radius)
                    .add(basis[1].clone().multiply(Math.sin(angle) * radius));
            world.spawnParticle(particle, center.getX() + offset.getX(), center.getY() + offset.getY(),
                    center.getZ() + offset.getZ(), 1, 0, 0, 0, 0, data);
        }
    }
}
