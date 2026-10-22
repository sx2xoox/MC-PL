package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
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
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 외계인 — a visitor from space. Its telepathy feels hidden enemies nearby (shown to the alien alone) and hits them
 * harder, the active fires a green ray gun that follows the aim and burns hotter the longer it stays on one target, and
 * the ultimate calls down a flying saucer that hunts the nearest enemy, beams it up, probes it and drops it far away.
 *
 * <p>The saucer's tractor beam is crowd control: abductees are steered along a planned path with damped corrections (a
 * player's position on the server trails its client), hang under a slow-falling safety net, cannot walk, and are let go
 * if they get stuck. Their landing hurts through the ability (vanilla fall damage is switched off for the drop), and the
 * drop spot must have safe ground inside the arena. The ultimate needs no grant, ticks or hooks, so the gambler may
 * borrow it.
 *
 * <p>Look: alien green and saucer silver. The alien wears two bobbing antennae with glowing green tips (hidden from its
 * own view) that pulse when it senses someone; sensed enemies show green brainwave rings and a thread of thought that
 * only the alien sees. The ray gun is a small silver gun with a green emitter, its beam a glowing glass rod with a white
 * core and green pulses running down it, sparks and a scorch where it hits. The saucer is an octagonal silver disc with a
 * glass dome, a little green pilot and chasing rim lights; it descends with a searchlight circle, hunts with a translucent
 * green shaft of light, lifts its catch in a spiral, probes it with beeps and scanning rings, carries it off and zips away.
 */
public final class AlienAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("alien", "외계인", Material.BEACON)
            .color(TextColor.color(0x7CFF4F))
            .difficulty(2)
            .passive("텔레파시",
                    "{sense_range}블록 안에 숨은 적(은신·투명)이 있으면 나에게만 보이는 초록 뇌파로 위치를 느낍니다. "
                            + "텔레파시로 느낀 적에게 주는 피해가 {sense_bonus}% 증가합니다.")
            .active("광선총",
                    "{ray_duration}초간 시선을 따라 움직이는 녹색 광선을 쏩니다({ray_range}블록, 벽과 처음 맞은 적에서 "
                            + "멈춤). 광선에 닿은 적은 {ray_interval}초마다 {ray_damage} 피해를 입고, 같은 적을 계속 맞힐수록 "
                            + "피해가 {ray_ramp}씩 커집니다. 쏘는 동안 이동 속도가 {ray_slow}% 느려집니다.",
                    8)
            .ultimate("UFO 납치",
                    "조준한 곳({ult_range}블록 이내) 상공에 UFO를 부릅니다. {ufo_arrive}초 뒤 내려온 UFO는 {hunt_duration}초간 "
                            + "주변 {hunt_radius}블록 안의 가장 가까운 적을 쫓으며(초당 {ufo_speed}블록) 반경 {beam_radius}블록의 "
                            + "견인 광선을 비춥니다. 광선에 닿은 적은 끌려 올라가 {probe_duration}초간 붙잡힌 채 "
                            + "{probe_interval}초마다 {probe_damage} 피해를 받고, 내가 바라보는 쪽으로 최대 {carry_distance}블록 "
                            + "실려 간 뒤 떨어져 {drop_damage} 피해를 받습니다.")
            .param("sense_range", 16)
            .param("sense_bonus", 20)
            .param("ray_duration", 1)
            .param("ray_range", 18)
            .param("ray_interval", 0.2)
            .param("ray_damage", 0.6)
            .param("ray_ramp", 0.15)
            .param("ray_slow", 30)
            .param("ult_range", 26)
            .param("ufo_arrive", 0.75)
            .param("hunt_duration", 3)
            .param("hunt_radius", 10)
            .param("ufo_speed", 4.8)
            .param("beam_radius", 2.2)
            .param("probe_duration", 1)
            .param("probe_interval", 0.5)
            .param("probe_damage", 1.5)
            .param("carry_distance", 10)
            .param("drop_damage", 4)
            .build();

    private static final String GREEN = "#7CFF4F";
    private static final String PALE = "#E8FFE0";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: alien green, saucer silver
    /** Pale green glow, alien green, the deep green-black of space. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE8FFE0, 0x7CFF4F, 0x0B2E10);
    private static final Color SILVER = Color.fromRGB(0xC9D3DC);
    private static final Color YELLOW = Color.fromRGB(0xFFF06A);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition GLOW_SMALL = PALETTE.glow(0.6f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.1f);
    private static final Particle.DustOptions GREEN_DUST = PALETTE.dust(0.8f);
    private static final Particle.DustOptions GREEN_SMALL = PALETTE.dust(0.5f);
    private static final Particle.DustOptions SILVER_DUST = new Particle.DustOptions(SILVER, 0.9f);
    private static final Particle.DustOptions YELLOW_DUST = new Particle.DustOptions(YELLOW, 0.7f);
    private static final Particle.DustTransition BRAINWAVE = new Particle.DustTransition(Color.fromRGB(0xB8FF9A),
            Color.fromRGB(0x2E8B2E), 0.8f);

    /** Block types of the visuals; block data is created on use (it needs a running server). */
    private static final Material ANTENNA_STALK = Material.LIME_CONCRETE;
    private static final Material ANTENNA_TIP = Material.VERDANT_FROGLIGHT;
    private static final Material GUN_BODY = Material.IRON_BLOCK;
    private static final Material GUN_EMITTER = Material.LIME_STAINED_GLASS;
    private static final Material BEAM_GLASS = Material.LIME_STAINED_GLASS;
    private static final Material BEAM_CORE = Material.WHITE_CONCRETE;
    private static final Material HULL = Material.IRON_BLOCK;
    private static final Material UNDERSIDE = Material.GRAY_CONCRETE;
    private static final Material DOME = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final Material PILOT = Material.LIME_CONCRETE;
    private static final Material[] RIM_LIGHTS = {Material.LIME_CONCRETE, Material.YELLOW_CONCRETE};
    private static final Material SHAFT = Material.LIME_STAINED_GLASS;

    /** Display entities one alien keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    /** Caps every velocity this ability applies. */
    private static final double MAX_SPEED = 1.6;
    private static final double REST_FALL = -Motion.GRAVITY * Motion.DRAG;

    // passive
    private static final long SENSE_PERIOD = 5;
    private static final long THOUGHT_PERIOD = 10;
    private static final float STALK_LENGTH = 0.38f;
    private static final float STALK_WIDTH = 0.035f;
    private static final float TIP_SIZE = 0.13f;
    private static final float STALK_TILT = 20;
    private static final float STALK_SPREAD = 0.11f;

    // active: ray gun
    private static final double MUZZLE_FORWARD = 0.75;
    private static final float BEAM_WIDTH = 0.16f;
    private static final float CORE_WIDTH = 0.05f;
    private static final double TARGET_SLACK = 0.15;

    // ultimate: the saucer
    private static final double UFO_HEIGHT = 5.5;
    private static final double ARRIVE_FROM = 18;
    private static final double HOLD_GAP = 0.55;
    private static final long LIFT_TICKS = 15;
    private static final long CARRY_SPEED_TICKS_PER_BLOCK = 2;
    private static final long DROP_TICKS = 14;
    private static final long LEAVE_TICKS = 14;
    private static final double DROP_SPEED = 1.0;
    private static final int MAX_ABDUCTEES = 3;
    private static final double SLOT_RADIUS = 0.7;
    private static final double STEER_GAIN = 0.5;
    private static final double STRAY_DISTANCE = 3.5;
    private static final long STRAY_TICKS = 10;
    private static final long SAFETY_FALL_TICKS = 60;
    private static final int GROUND_SEARCH = 12;
    private static final float HULL_SIZE = 3.0f;
    private static final float RIM_RADIUS = 1.55f;
    private static final int RIM_COUNT = 6;

    private final List<Display> live = new ArrayList<>();
    private final Set<UUID> sensed = new HashSet<>();
    private final BlockDisplay[] antennae = new BlockDisplay[4];
    private float antennaPulse;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ================================================================== passive: telepathy

    @Override
    public void onGrant() {
        Player owner = player();
        if (!room(4)) {
            return;
        }
        Location head = headTop(owner);
        float yaw = owner.getLocation().getYaw();
        for (int i = 0; i < 4; i++) {
            Material type = i % 2 == 0 ? ANTENNA_STALK : ANTENNA_TIP;
            BlockDisplay part = keep(Displays.block(ctx(), owner, head, type.createBlockData(),
                    antennaPose(i / 2 == 0 ? 1 : -1, i % 2 == 1, yaw, 0, 1f), 0));
            part.setTeleportDuration(1);
            // The alien would see its own antennae from the inside of its head.
            owner.hideEntity(ctx().plugin(), part);
            antennae[i] = part;
        }
    }

    @Override
    public void onRevoke() {
        sensed.clear();
        for (BlockDisplay part : antennae) {
            if (part != null && part.isValid()) {
                part.remove();
            }
        }
    }

    /** True if {@code enemy} is a player the alien cannot see: stealthed, or invisible. */
    private boolean isHidden(LivingEntity enemy) {
        if (enemy instanceof Player hidden && !player().canSee(hidden)) {
            return true;
        }
        return enemy.isInvisible() || enemy.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    /** A hidden enemy close enough for the alien's telepathy. */
    private boolean isSensed(LivingEntity enemy) {
        Player owner = player();
        return isHidden(enemy) && enemy.getWorld().equals(owner.getWorld())
                && enemy.getLocation().distance(owner.getLocation()) <= Math.max(0, params().num("sense_range"));
    }

    /** Telepathy hits harder: sensed hidden enemies take more of every hit the alien deals. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !player().equals(ctx().resolveAttacker(event.getDamager())) || !isSensed(victim)) {
            return;
        }
        double bonus = Math.max(0, params().num("sense_bonus")) / 100.0;
        if (bonus > 0) {
            event.setDamage(event.getDamage() * (1 + bonus));
        }
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        updateAntennae(owner, tick);
        if (tick % SENSE_PERIOD != 0) {
            return;
        }
        double range = Math.max(0, params().num("sense_range"));
        Set<UUID> now = new HashSet<>();
        int fresh = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            if (!isHidden(enemy)) {
                continue;
            }
            now.add(enemy.getUniqueId());
            if (!sensed.contains(enemy.getUniqueId())) {
                fresh++;
            }
            brainwave(owner, enemy, tick);
        }
        if (fresh > 0) {
            Location ear = owner.getEyeLocation();
            owner.playSound(ear, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.8f, 1.8f);
            owner.playSound(ear, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.4f, 2.0f);
            owner.sendActionBar(Text.mm("<" + GREEN + ">◉ 텔레파시</" + GREEN + "> <gray>숨은 적</gray> <white>"
                    + now.size() + "명</white><gray> 감지</gray>"));
        }
        if (!now.isEmpty()) {
            antennaPulse = 1f;
        }
        sensed.clear();
        sensed.addAll(now);
    }

    /**
     * What only the alien sees: rings of green brainwaves around a hidden enemy and, now and then, a thought drifting
     * from the alien's head to it.
     */
    private void brainwave(Player owner, LivingEntity enemy, long tick) {
        Location feet = enemy.getLocation().add(0, 0.1, 0);
        double phase = tick * 0.25;
        int points = Vfx.n(ctx(), 10);
        for (int i = 0; i < points; i++) {
            double angle = phase + i * TAU / points;
            double radius = 0.55 + 0.1 * Math.sin(tick * 0.5);
            owner.spawnParticle(Particle.DUST_COLOR_TRANSITION, feet.getX() + Math.cos(angle) * radius,
                    feet.getY() + enemy.getHeight() * ((i % 3) / 2.0), feet.getZ() + Math.sin(angle) * radius, 1, 0, 0,
                    0, 0, BRAINWAVE);
        }
        if (tick % THOUGHT_PERIOD == 0) {
            Location from = headTop(owner).add(0, 0.3, 0);
            owner.spawnParticle(Particle.TRAIL, from, 1, 0, 0, 0, 0,
                    new Particle.Trail(Fx.body(enemy), PALETTE.body(), 14));
            owner.spawnParticle(Particle.DUST, Fx.body(enemy), 3, 0.2, 0.3, 0.2, 0, GREEN_SMALL);
        }
    }

    /** The antennae ride on the alien's head, bob a little and flare up while it senses someone. */
    private void updateAntennae(Player owner, long tick) {
        BlockDisplay first = antennae[0];
        if (first == null || !first.isValid()) {
            return;
        }
        Location head = headTop(owner);
        boolean invisible = owner.isInvisible() || owner.hasPotionEffect(PotionEffectType.INVISIBILITY);
        for (BlockDisplay part : antennae) {
            if (part != null && part.isValid()) {
                Displays.moveTo(part, head);
                float range = invisible ? 0f : 1.0f;
                if (part.getViewRange() != range) {
                    part.setViewRange(range);
                }
            }
        }
        if (tick % 2 != 0) {
            return;
        }
        float yaw = owner.getLocation().getYaw();
        float bob = (float) Math.sin(tick * 0.18) * 6f;
        float pulse = 1f + 0.6f * antennaPulse;
        antennaPulse = Math.max(0, antennaPulse - 0.12f);
        for (int i = 0; i < 4; i++) {
            BlockDisplay part = antennae[i];
            if (part != null && part.isValid()) {
                Displays.animate(part, antennaPose(i / 2 == 0 ? 1 : -1, i % 2 == 1, yaw, bob * (i / 2 == 0 ? 1 : -1),
                        pulse), 2);
            }
        }
        if (pulse > 1.05f && !invisible) {
            for (int side = -1; side <= 1; side += 2) {
                Location tip = tipSpot(head, side, yaw, bob * side);
                Vfx.dot(tip, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
            }
        }
    }

    private static Location headTop(Player owner) {
        return Displays.unrotated(owner.getLocation().add(0, owner.getHeight() + 0.02, 0));
    }

    /** The local rotation of one antenna: tilted outwards to its side and swayed by {@code bob}. */
    private static Quaternionf antennaRotation(int side, float yaw, float bob) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                .rotateZ((float) Math.toRadians(-side * STALK_TILT + bob));
    }

    /** One antenna part: its stalk, or the glowing tip on top of it. The display itself is never rotated. */
    private static Transformation antennaPose(int side, boolean tip, float yaw, float bob, float pulse) {
        Quaternionf rotation = antennaRotation(side, yaw, bob);
        Vector3f base = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                .transform(new Vector3f(side * STALK_SPREAD, 0, 0));
        Vector3f along = new Quaternionf(rotation).transform(new Vector3f(0, tip ? STALK_LENGTH : STALK_LENGTH / 2, 0));
        Vector3f center = base.add(along);
        Vector3f size = tip ? new Vector3f(TIP_SIZE * pulse) : new Vector3f(STALK_WIDTH, STALK_LENGTH, STALK_WIDTH);
        Vector3f corner = new Quaternionf(rotation).transform(new Vector3f(size).mul(0.5f)).negate();
        return new Transformation(center.add(corner), rotation, size, new Quaternionf());
    }

    private static Location tipSpot(Location head, int side, float yaw, float bob) {
        Vector3f base = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                .transform(new Vector3f(side * STALK_SPREAD, 0, 0));
        Vector3f along = antennaRotation(side, yaw, bob).transform(new Vector3f(0, STALK_LENGTH, 0));
        return head.clone().add(base.x() + along.x(), base.y() + along.y(), base.z() + along.z());
    }

    // ================================================================== active: ray gun

    @Override
    public ActivationResult onActive() {
        new RayGun(player()).start();
        return ActivationResult.success();
    }

    /**
     * The ray gun: a beam from the muzzle along the aim to the first wall or visible enemy, every pulse burning the
     * enemy it stops on (hotter for every pulse in a row on the same one) and any hidden enemy it passes through.
     */
    private final class RayGun implements LongPredicate {

        private final Player owner;
        private final long ticks;
        private final long interval;
        private @Nullable BlockDisplay beam;
        private @Nullable BlockDisplay core;
        private @Nullable BlockDisplay body;
        private @Nullable BlockDisplay emitter;
        private @Nullable LivingEntity lastTarget;
        private int streak;
        private double dealt;

        RayGun(Player owner) {
            this.owner = owner;
            this.ticks = Math.max(1, params().ticks("ray_duration"));
            this.interval = Math.max(1, params().ticks("ray_interval"));
        }

        void start() {
            double slow = Math.clamp(params().num("ray_slow") / 100.0, 0, 1);
            if (slow > 0) {
                ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                        ticks + 2);
            }
            Location muzzle = muzzle();
            Vector aim = aim();
            if (room(4)) {
                body = keep(Displays.block(ctx(), owner, muzzle, GUN_BODY.createBlockData(),
                        along(aim, 0.13f, 0.13f, 0.4f, -0.45f), ticks + 6));
                emitter = keep(Displays.block(ctx(), owner, muzzle, GUN_EMITTER.createBlockData(),
                        along(aim, 0.19f, 0.19f, 0.12f, -0.1f), ticks + 6));
                beam = keep(Displays.block(ctx(), owner, muzzle, BEAM_GLASS.createBlockData(),
                        along(aim, BEAM_WIDTH, BEAM_WIDTH, 0.1f, 0), ticks + 6));
                core = keep(Displays.block(ctx(), owner, muzzle, BEAM_CORE.createBlockData(),
                        along(aim, CORE_WIDTH, CORE_WIDTH, 0.1f, 0), ticks + 6));
                for (BlockDisplay part : new BlockDisplay[] {body, emitter, beam, core}) {
                    part.setTeleportDuration(1);
                }
                Displays.outline(beam, PALETTE.body());
            }
            Vfx.cloud(ctx(), muzzle, Particle.END_ROD, 6, 0.1, 0.05, null, false);
            Fx.sound(muzzle, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 2.0f);
            Fx.sound(muzzle, Sound.ENTITY_GUARDIAN_ATTACK, 0.8f, 1.7f + Fx.jitter(0.05f));
            Fx.sound(muzzle, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.6f, 2.0f);
            Loop.repeat(ctx(), owner, 0, 1, ticks, this, this::stop);
        }

        private Location muzzle() {
            Location eye = owner.getEyeLocation();
            Vector look = aim();
            Vector right = Motion.horizontal(new Vector(-look.getZ(), 0, look.getX()));
            return Displays.unrotated(eye.add(look.multiply(MUZZLE_FORWARD)).add(right.multiply(0.22))
                    .subtract(0, 0.22, 0));
        }

        private Vector aim() {
            Vector look = owner.getEyeLocation().getDirection();
            return look.lengthSquared() < 1.0E-6 ? Motion.horizontalFacing(owner.getLocation()) : look.normalize();
        }

        @Override
        public boolean test(long tick) {
            Location eye = owner.getEyeLocation();
            Vector aim = aim();
            double range = Math.max(1, params().num("ray_range"));
            World world = eye.getWorld();
            RayTraceResult wall = world.rayTraceBlocks(eye, aim, range, FluidCollisionMode.NEVER, true);
            double reach = wall == null ? range : wall.getHitPosition().distance(eye.toVector());
            LivingEntity target = null;
            List<LivingEntity> passed = new ArrayList<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, eye.clone().add(aim.clone().multiply(reach / 2)),
                    reach / 2 + 2)) {
                RayTraceResult hit = enemy.getBoundingBox().expand(TARGET_SLACK).rayTrace(eye.toVector(), aim, reach);
                if (hit == null) {
                    continue;
                }
                double distance = hit.getHitPosition().distance(eye.toVector());
                if (isHidden(enemy)) {
                    passed.add(enemy);
                } else if (distance < reach) {
                    reach = distance;
                    target = enemy;
                }
            }
            Location end = eye.clone().add(aim.clone().multiply(reach));
            Location muzzle = muzzle();
            pose(muzzle, end, aim, tick);
            drawBeam(muzzle, end, tick, target != null, wall != null && target == null);
            if (tick % interval == 0) {
                pulse(target, passed, eye, reach, end);
            }
            if (tick % 5 == 0) {
                Fx.sound(muzzle, Sound.BLOCK_BEACON_AMBIENT, 0.9f, 2.0f);
            }
            return true;
        }

        private void pose(Location muzzle, Location end, Vector aim, long tick) {
            Vector delta = end.toVector().subtract(muzzle.toVector());
            float length = (float) Math.max(0.05, delta.length());
            if (delta.lengthSquared() < 1.0E-6) {
                delta = aim.clone();
            }
            float breathe = 1f + 0.15f * (float) Math.sin(tick * 1.3);
            for (BlockDisplay part : new BlockDisplay[] {body, emitter, beam, core}) {
                if (part != null && part.isValid()) {
                    Displays.moveTo(part, muzzle);
                }
            }
            if (body != null && body.isValid()) {
                Displays.animate(body, along(aim, 0.13f, 0.13f, 0.4f, -0.45f), 1);
            }
            if (emitter != null && emitter.isValid()) {
                Displays.animate(emitter, along(aim, 0.19f * breathe, 0.19f * breathe, 0.12f, -0.1f), 1);
            }
            if (beam != null && beam.isValid()) {
                Displays.animate(beam, along(delta, BEAM_WIDTH * breathe, BEAM_WIDTH * breathe, length, 0), 1);
            }
            if (core != null && core.isValid()) {
                Displays.animate(core, along(delta, CORE_WIDTH, CORE_WIDTH, length, 0), 1);
            }
        }

        /** Green pulses racing down the beam, motes along it, and sparks or a scorch where it ends. */
        private void drawBeam(Location muzzle, Location end, long tick, boolean onEnemy, boolean onWall) {
            World world = muzzle.getWorld();
            Vfx.trail(muzzle, end, tick % 2 == 0 ? PALETTE.core() : PALETTE.body(), 4, false);
            Vector delta = end.toVector().subtract(muzzle.toVector());
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Location at = muzzle.clone().add(delta.clone().multiply(random.nextDouble()));
                world.spawnParticle(Particle.DUST, at, 1, 0.04, 0.04, 0.04, 0, GREEN_SMALL);
            }
            Vfx.dot(muzzle, Particle.END_ROD, null, false);
            if (onEnemy) {
                world.spawnParticle(Particle.ELECTRIC_SPARK, end, Vfx.n(ctx(), 3), 0.15, 0.15, 0.15, 0.2);
                if (tick % 2 == 0) {
                    Vfx.ring(ctx(), end, 0.35, 8, tick * 0.6, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
                }
            } else if (onWall) {
                world.spawnParticle(Particle.SMOKE, end, Vfx.n(ctx(), 2), 0.05, 0.05, 0.05, 0.01);
                world.spawnParticle(Particle.DUST, end, Vfx.n(ctx(), 2), 0.1, 0.1, 0.1, 0, GREEN_DUST);
                if (tick % 3 == 0) {
                    Vfx.dot(end, Particle.WAX_OFF, null, false);
                }
            }
        }

        /** A burning pulse: the enemy the beam stops on (hotter on a streak), and every hidden one it went through. */
        private void pulse(@Nullable LivingEntity target, List<LivingEntity> passed, Location eye, double reach,
                           Location end) {
            double base = Math.max(0, params().num("ray_damage"));
            double ramp = Math.max(0, params().num("ray_ramp"));
            for (LivingEntity hidden : passed) {
                if (hidden.getLocation().distance(eye) <= reach + 1) {
                    Combat.hit(ctx(), owner, hidden, base);
                }
            }
            if (target == null) {
                lastTarget = null;
                streak = 0;
                return;
            }
            if (target.equals(lastTarget)) {
                streak++;
            } else {
                lastTarget = target;
                streak = 0;
            }
            double damage = base + ramp * streak;
            double before = target.getHealth() + target.getAbsorptionAmount();
            Combat.hit(ctx(), owner, target, damage);
            double after = target.isDead() ? 0 : target.getHealth() + target.getAbsorptionAmount();
            dealt += Math.max(0, before - after);
            Location body = Fx.body(target);
            Vfx.dot(end, Particle.FLASH, PALETTE.body(), false);
            Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 4 + streak, 0.25, 0, GLOW, false);
            Fx.sound(body, Sound.BLOCK_FIRE_EXTINGUISH, 0.4f, 1.6f + 0.08f * streak);
            Fx.sound(body, Sound.ENTITY_GUARDIAN_ATTACK, 0.3f, 2.0f);
            if (streak > 0) {
                owner.sendActionBar(Text.mm("<" + GREEN + ">광선 집중</" + GREEN + "> <white>×" + (streak + 1)
                        + "</white><gray> " + AbilityInfo.formatNumber(Math.round(damage * 100) / 100.0)
                        + " 피해</gray>"));
            }
        }

        private void stop() {
            Location muzzle = muzzle();
            for (BlockDisplay part : new BlockDisplay[] {body, emitter, beam, core}) {
                if (part != null && part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 3);
                }
            }
            Vfx.cloud(ctx(), muzzle, Particle.SMOKE, 4, 0.08, 0.02, null, false);
            Vfx.cloud(ctx(), muzzle, Particle.DUST, 4, 0.1, 0, GREEN_DUST, false);
            Fx.sound(muzzle, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 2.0f);
            if (dealt > 0) {
                owner.sendActionBar(Text.mm("<" + GREEN + ">광선총</" + GREEN + "> <white>"
                        + AbilityInfo.formatNumber(Math.round(dealt * 10) / 10.0) + " 피해</white>"));
            }
        }
    }

    /**
     * A box {@code width} × {@code height} × {@code length} whose length runs along {@code direction}, starting
     * {@code start} blocks along it from the display's position. The display itself is never rotated.
     */
    static Transformation along(Vector direction, float width, float height, float length, float start) {
        Vector3f dir = new Vector3f((float) direction.getX(), (float) direction.getY(), (float) direction.getZ());
        if (dir.lengthSquared() < 1.0E-8f) {
            dir.set(0, 0, 1);
        }
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), dir.normalize());
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -height / 2, start));
        return new Transformation(translation, rotation, new Vector3f(width, height, length), new Quaternionf());
    }

    // ================================================================== ultimate: UFO abduction

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double range = Math.max(1, params().num("ult_range"));
        Location spot = aimSpot(owner, range);
        if (spot == null) {
            return ActivationResult.fail("조준한 곳이 너무 멉니다");
        }
        double hunt = Math.max(1, params().num("hunt_radius"));
        boolean prey = false;
        for (LivingEntity enemy : ctx().enemiesNear(owner, spot, hunt)) {
            if (!isHidden(enemy)) {
                prey = true;
                break;
            }
        }
        if (!prey) {
            return ActivationResult.fail("그곳 " + AbilityInfo.formatNumber(hunt) + "블록 안에 적이 없습니다");
        }
        new Saucer(owner, spot).start();
        return ActivationResult.success();
    }

    /** Where the saucer comes down: over the aimed enemy, or the aimed ground; null if the aim hits nothing. */
    private @Nullable Location aimSpot(Player owner, double range) {
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed != null) {
            return Displays.unrotated(aimed.getLocation());
        }
        Location eye = owner.getEyeLocation();
        RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER,
                true);
        if (hit == null) {
            return null;
        }
        Location at = hit.getHitPosition().toLocation(owner.getWorld()).subtract(eye.getDirection().multiply(0.3));
        double floor = groundY(at, at.getY() + 0.5);
        if (!Double.isNaN(floor)) {
            at.setY(floor);
        }
        return Displays.unrotated(at);
    }

    /** One enemy in the tractor beam: where it was caught, the path it was steered along and what holds it. */
    private static final class Abductee {
        final LivingEntity victim;
        final Location origin;
        final List<NamespacedKey> modifiers = new ArrayList<>();
        @Nullable Location planned;
        int stray;
        boolean released;
        boolean dropping;
        long dropTick;

        Abductee(LivingEntity victim) {
            this.victim = victim;
            this.origin = victim.getLocation();
        }
    }

    private enum Phase { ARRIVE, HUNT, LIFT, PROBE, CARRY, DROP, LEAVE }

    /** The flying saucer: it arrives, hunts, beams up, probes, carries off, drops and leaves. */
    private final class Saucer implements LongPredicate {

        private final Player owner;
        private final World world;
        private final long arriveTicks;
        private final long huntTicks;
        private final long probeTicks;
        private final long probeInterval;
        private final double beamRadius;
        private final double speed;
        private final List<Abductee> taken = new ArrayList<>();
        private final List<BlockDisplay> hull = new ArrayList<>();
        private final List<BlockDisplay> rim = new ArrayList<>();
        private final BlockDisplay[] shaft = new BlockDisplay[2];
        private Location center;
        private double floorY;
        private Phase phase = Phase.ARRIVE;
        private long phaseStart;
        private long carryTicks;
        private Vector carry = new Vector();
        private float spin;
        private float shaftScale;

        Saucer(Player owner, Location spot) {
            this.owner = owner;
            this.world = spot.getWorld();
            this.arriveTicks = Math.max(1, params().ticks("ufo_arrive"));
            this.huntTicks = Math.max(1, params().ticks("hunt_duration"));
            this.probeTicks = Math.max(1, params().ticks("probe_duration"));
            this.probeInterval = Math.max(1, params().ticks("probe_interval"));
            this.beamRadius = Math.max(0.5, params().num("beam_radius"));
            this.speed = Math.max(0, params().num("ufo_speed")) / 20.0;
            double floor = groundY(spot, spot.getY() + 1.0);
            this.floorY = Double.isNaN(floor) ? spot.getY() : floor;
            this.center = spot.clone();
            this.center.setY(hoverY(spot, floorY));
        }

        void start() {
            Location high = center.clone().add(0, ARRIVE_FROM, 0);
            if (room(14)) {
                BlockData steel = HULL.createBlockData();
                hull.add(keep(spawnPart(high, steel)));
                hull.add(keep(spawnPart(high, steel)));
                hull.add(keep(spawnPart(high, UNDERSIDE.createBlockData())));
                hull.add(keep(spawnPart(high, DOME.createBlockData())));
                hull.add(keep(spawnPart(high, DOME.createBlockData())));
                hull.add(keep(spawnPart(high, PILOT.createBlockData())));
                for (int i = 0; i < RIM_COUNT; i++) {
                    rim.add(keep(spawnPart(high, RIM_LIGHTS[i % RIM_LIGHTS.length].createBlockData())));
                }
                for (int i = 0; i < 2; i++) {
                    shaft[i] = keep(spawnPart(high, SHAFT.createBlockData()));
                }
                Displays.outline(hull.getFirst(), PALETTE.body());
            }
            pose(high, 0);
            Fx.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 0.6f);
            Fx.sound(center, Sound.BLOCK_CONDUIT_ACTIVATE, 1.2f, 1.4f);
            Fx.sound(owner.getEyeLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 0.5f);
            owner.sendActionBar(Text.mm("<" + GREEN + "><b>UFO 납치</b></" + GREEN + "> <gray>지구인을 찾는 중...</gray>"));
            long total = arriveTicks + huntTicks + LIFT_TICKS + probeTicks + maxCarryTicks() + DROP_TICKS + LEAVE_TICKS
                    + 4;
            Loop.repeat(ctx(), owner, 1, 1, total, this, this::vanish);
        }

        private BlockDisplay spawnPart(Location at, BlockData data) {
            BlockDisplay part = Displays.block(ctx(), owner, at, data, Displays.scaled(0.01f), 0);
            part.setTeleportDuration(1);
            part.setViewRange(2.5f);
            return part;
        }

        private long maxCarryTicks() {
            return Math.round(Math.max(0, params().num("carry_distance")) * CARRY_SPEED_TICKS_PER_BLOCK) + 1;
        }

        @Override
        public boolean test(long tick) {
            long t = tick - phaseStart;
            spin += phase == Phase.LEAVE ? 18 : 7;
            switch (phase) {
                case ARRIVE -> arrive(tick, t);
                case HUNT -> hunt(tick, t);
                case LIFT -> lift(tick, t);
                case PROBE -> probe(tick, t);
                case CARRY -> carry(tick, t);
                case DROP -> drop(tick, t);
                case LEAVE -> {
                    return leave(t);
                }
            }
            return true;
        }

        private void next(Phase to, long tick) {
            phase = to;
            phaseStart = tick + 1;
        }

        // ------------------------------------------------------------ arrive & hunt

        private void arrive(long tick, long t) {
            double progress = smooth((t + 1.0) / arriveTicks);
            Location at = center.clone().add(0, ARRIVE_FROM * (1 - progress), 0);
            pose(at, (float) progress);
            Location floor = floorSpot();
            if (t % 2 == 0) {
                Vfx.ring(ctx(), floor, beamRadius, (int) Math.max(14, beamRadius * 8), tick * 0.2,
                        Particle.DUST_COLOR_TRANSITION, GLOW, true);
                Vfx.ring(ctx(), floor, beamRadius * (1 - progress) + 0.2, 10, -tick * 0.3, Particle.DUST, YELLOW_DUST,
                        true);
            }
            Vfx.trail(at.clone().add(0, 1.5, 0), at, SILVER, 4, true);
            if (t % 5 == 0) {
                Fx.sound(at, Sound.BLOCK_BEACON_AMBIENT, 1.2f, 0.7f + 0.4f * (float) progress);
            }
            if (t + 1 >= arriveTicks) {
                Fx.sound(center, Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 1.0f, 1.6f);
                Fx.sound(center, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 0.8f);
                next(Phase.HUNT, tick);
            }
        }

        private void hunt(long tick, long t) {
            List<LivingEntity> caught = inBeam();
            if (!caught.isEmpty()) {
                capture(caught, tick);
                return;
            }
            if (t >= huntTicks) {
                owner.sendActionBar(Text.mm("<gray>UFO가 아무도 납치하지 못하고 떠납니다</gray>"));
                next(Phase.LEAVE, tick);
                return;
            }
            LivingEntity prey = nearestPrey();
            if (prey != null && speed > 0) {
                Vector toward = new Vector(prey.getLocation().getX() - center.getX(), 0,
                        prey.getLocation().getZ() - center.getZ());
                double distance = toward.length();
                if (distance > 0.05) {
                    glide(toward.multiply(Math.min(speed, distance) / distance));
                }
            }
            shaftScale = Math.min(1f, shaftScale + 0.25f);
            pose(center, 1f);
            drawShaft(tick);
            if (t % 20 == 0) {
                Fx.sound(center, Sound.BLOCK_BEACON_AMBIENT, 1.5f, 1.2f);
                Fx.sound(center, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.6f, 0.5f);
            }
        }

        /** The visible enemy nearest the saucer within the hunting radius, if any. */
        private @Nullable LivingEntity nearestPrey() {
            Location ground = center.clone();
            ground.setY(floorY);
            for (LivingEntity enemy : ctx().enemiesNear(owner, ground, Math.max(1, params().num("hunt_radius")))) {
                if (!isHidden(enemy)) {
                    return enemy;
                }
            }
            return null;
        }

        /** Every visible enemy standing in the shaft of light under the saucer (nothing solid in between). */
        private List<LivingEntity> inBeam() {
            List<LivingEntity> found = new ArrayList<>();
            Location ground = center.clone();
            ground.setY(floorY);
            for (LivingEntity enemy : ctx().enemiesNear(owner, ground, beamRadius + 3)) {
                if (found.size() >= MAX_ABDUCTEES || isHidden(enemy)) {
                    continue;
                }
                Location at = enemy.getLocation();
                double flat = Math.hypot(at.getX() - center.getX(), at.getZ() - center.getZ());
                if (flat > beamRadius || at.getY() > center.getY() || at.getY() < floorY - 1.5) {
                    continue;
                }
                Location bottom = center.clone().subtract(0, 0.4, 0);
                if (Spots.clearPath(bottom, enemy.getLocation().add(0, enemy.getHeight(), 0))) {
                    found.add(enemy);
                }
            }
            return found;
        }

        /** Moves the saucer by {@code step} if the way is clear, inside the arena and over ground. */
        private boolean glide(Vector step) {
            Location next = center.clone().add(step);
            if (!ctx().isInsideArena(next) || !Spots.clearPath(center, next)) {
                return false;
            }
            double floor = groundY(next, center.getY() - 0.5);
            if (Double.isNaN(floor) || floor > center.getY() - 2.5) {
                return false;
            }
            double targetY = hoverY(next, floor);
            next.setY(center.getY() + Math.clamp(targetY - center.getY(), -0.15, 0.15));
            center = next;
            floorY = floor;
            return true;
        }

        // ------------------------------------------------------------ abduction

        private void capture(List<LivingEntity> caught, long tick) {
            long safety = LIFT_TICKS + probeTicks + maxCarryTicks() + DROP_TICKS + SAFETY_FALL_TICKS;
            for (LivingEntity enemy : caught) {
                Abductee abductee = new Abductee(enemy);
                abductee.modifiers.add(ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -1.0,
                        Operation.MULTIPLY_SCALAR_1, 0));
                abductee.modifiers.add(ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1.0,
                        Operation.MULTIPLY_SCALAR_1, 0));
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,
                        (int) Math.min(Integer.MAX_VALUE, safety), 0, true, false, false));
                taken.add(abductee);
                Location body = Fx.body(enemy);
                Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 10, 0.3, 0, GLOW, true);
                Vfx.cloud(ctx(), body, Particle.END_ROD, 6, 0.3, 0.05, null, true);
            }
            Vector facing = Motion.horizontalFacing(owner.getLocation());
            if (facing.lengthSquared() < 1.0E-6) {
                facing = new Vector(0, 0, 1);
            }
            carry = facing;
            Fx.sound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.5f);
            Fx.sound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2f, 1.4f);
            Fx.sound(center, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 1.6f);
            owner.sendActionBar(Text.mm("<" + GREEN + "><b>납치 성공!</b></" + GREEN + "> <white>" + taken.size()
                    + "명</white><gray> 탑승</gray> <" + PALE + ">삐빅...</" + PALE + ">"));
            next(Phase.LIFT, tick);
        }

        private void lift(long tick, long t) {
            double progress = smooth((t + 1.0) / LIFT_TICKS);
            for (int i = 0; i < taken.size(); i++) {
                Abductee abductee = taken.get(i);
                if (!abductee.released) {
                    steer(abductee, lerp(abductee.origin, hold(i, abductee.victim, t * 25.0), progress));
                }
            }
            pose(center, 1f);
            drawShaft(tick);
            for (Abductee abductee : taken) {
                if (!abductee.released && t % 2 == 0) {
                    Vfx.helix(ctx(), abductee.victim.getLocation(), 0.55, abductee.victim.getHeight(), 1.0, 2, 6,
                            tick * 0.5, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, true);
                }
            }
            if (t % 4 == 0) {
                Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 0.6f + 0.1f * t);
            }
            if (t + 1 >= LIFT_TICKS) {
                next(Phase.PROBE, tick);
            }
        }

        private void probe(long tick, long t) {
            for (int i = 0; i < taken.size(); i++) {
                Abductee abductee = taken.get(i);
                if (!abductee.released) {
                    steer(abductee, hold(i, abductee.victim, (LIFT_TICKS + t) * 25.0));
                }
            }
            pose(center, 1f);
            drawShaft(tick);
            Location bottom = center.clone().subtract(0, 0.45, 0);
            for (Abductee abductee : taken) {
                if (abductee.released || isHidden(abductee.victim)) {
                    continue;
                }
                LivingEntity victim = abductee.victim;
                // A scanning ring sweeps up and down the body; probe lasers poke at it.
                double scan = 0.5 + 0.5 * Math.sin(t * 0.45);
                Vfx.ring(ctx(), victim.getLocation().add(0, victim.getHeight() * scan, 0), 0.5, 10, t * 0.5,
                        Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, true);
                if (t % 3 == 0) {
                    Vfx.line(ctx(), bottom, Fx.body(victim).add(Fx.jitter(0.3f), Fx.jitter(0.4f), Fx.jitter(0.3f)), 4,
                            Particle.DUST, GREEN_SMALL, true);
                }
            }
            if (t % 3 == 0) {
                Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BIT, 0.9f, 0.8f + ThreadLocalRandom.current().nextFloat());
            }
            if ((t + 1) % probeInterval == 0) {
                double damage = Math.max(0, params().num("probe_damage"));
                for (Abductee abductee : taken) {
                    if (abductee.released) {
                        continue;
                    }
                    Combat.hit(ctx(), owner, abductee.victim, damage);
                    Location body = Fx.body(abductee.victim);
                    Vfx.dot(body, Particle.FLASH, PALETTE.body(), true);
                    Vfx.cloud(ctx(), body, Particle.ELECTRIC_SPARK, 10, 0.3, 0.2, null, true);
                    Fx.sound(body, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.8f);
                    Fx.sound(body, Sound.ENTITY_GUARDIAN_ATTACK, 0.5f, 2.0f);
                }
                popText(center.clone().subtract(0, 0.9, 0), "<b><" + GREEN + ">삐빅!</" + GREEN + "></b>", 0.9f, 10);
            }
            if (t + 1 >= probeTicks) {
                planCarry();
                next(Phase.CARRY, tick);
            }
        }

        /** How far the saucer can carry its catch the way the alien looks: clear sky, safe ground, inside the arena. */
        private void planCarry() {
            double max = Math.max(0, params().num("carry_distance"));
            double reached = 0;
            Location from = center.clone();
            for (double d = 0.5; d <= max + 1.0E-6; d += 0.5) {
                Location next = center.clone().add(carry.clone().multiply(d));
                // The hull reaches out past the centre: keep its rim off walls too.
                Location rimAhead = next.clone().add(carry.clone().multiply(HULL_SIZE / 2));
                if (!ctx().isInsideArena(next) || !Spots.clearPath(from, rimAhead)) {
                    break;
                }
                Location body = next.clone().subtract(0, HOLD_GAP + 1.0, 0);
                Location bodyFrom = from.clone().subtract(0, HOLD_GAP + 1.0, 0);
                if (!Spots.clearPath(bodyFrom, body)) {
                    break;
                }
                double floor = groundY(next, center.getY() - 0.5);
                if (Double.isNaN(floor)) {
                    break;
                }
                Location feet = next.clone();
                feet.setY(floor + 0.1);
                if (!Spots.hasGroundBelow(world, feet.add(0, 0.5, 0))) {
                    break;
                }
                reached = d;
                from = next;
            }
            carryTicks = Math.max(1, Math.round(reached * CARRY_SPEED_TICKS_PER_BLOCK));
            carry = carry.clone().multiply(reached / carryTicks);
        }

        private void carry(long tick, long t) {
            if (carry.lengthSquared() > 1.0E-8) {
                Location next = center.clone().add(carry);
                double floor = groundY(next, center.getY() - 0.5);
                center = next;
                if (!Double.isNaN(floor)) {
                    floorY = floor;
                }
            }
            for (int i = 0; i < taken.size(); i++) {
                Abductee abductee = taken.get(i);
                if (!abductee.released) {
                    steer(abductee, hold(i, abductee.victim, (LIFT_TICKS + probeTicks + t) * 25.0));
                }
            }
            pose(center, 1f);
            drawShaft(tick);
            Vfx.trail(center, center.clone().subtract(carry.clone().multiply(6)), SILVER, 6, true);
            if (t % 6 == 0) {
                Fx.sound(center, Sound.ITEM_ELYTRA_FLYING, 0.4f, 1.6f);
            }
            if (t + 1 >= carryTicks) {
                release(tick);
                next(Phase.DROP, tick);
            }
        }

        /** The beam switches off: the catch falls (no vanilla fall damage) and is hurt when it lands. */
        private void release(long tick) {
            for (Abductee abductee : taken) {
                if (abductee.released) {
                    continue;
                }
                LivingEntity victim = abductee.victim;
                letGo(abductee);
                if (!Combat.isTargetable(ctx(), owner, victim)) {
                    continue;
                }
                ctx().addModifier(owner, victim, Attribute.FALL_DAMAGE_MULTIPLIER, -1.0, Operation.MULTIPLY_SCALAR_1,
                        DROP_TICKS + 20);
                victim.setVelocity(new Vector(0, -DROP_SPEED, 0));
                victim.setFallDistance(0);
                abductee.dropping = true;
                abductee.dropTick = tick;
            }
            shaftScale = 0;
            Fx.sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.4f, 0.8f);
            Fx.sound(center, Sound.ENTITY_ITEM_PICKUP, 1.0f, 0.5f);
        }

        private void drop(long tick, long t) {
            pose(center, 1f);
            boolean waiting = false;
            for (Abductee abductee : taken) {
                if (!abductee.dropping) {
                    continue;
                }
                LivingEntity victim = abductee.victim;
                boolean landed = tick - abductee.dropTick > 2 && (Motion.isGrounded(victim) || victim.isInWater());
                if (landed || t + 1 >= DROP_TICKS || !Combat.isTargetable(ctx(), owner, victim)) {
                    abductee.dropping = false;
                    crash(victim);
                } else {
                    waiting = true;
                    Vfx.dot(victim.getLocation().add(0, victim.getHeight() + 0.2, 0), Particle.DUST, GREEN_DUST, true);
                }
            }
            if (!waiting) {
                next(Phase.LEAVE, tick);
            }
        }

        /** Dropped from the sky: the landing hurts, with a green crater of light. */
        private void crash(LivingEntity victim) {
            if (!Combat.isTargetable(ctx(), owner, victim)) {
                return;
            }
            Combat.hit(ctx(), owner, victim, Math.max(0, params().num("drop_damage")));
            Location feet = victim.getLocation().add(0, 0.1, 0);
            Vfx.dot(Fx.body(victim), Particle.FLASH, PALETTE.core(), true);
            Vfx.shockwave(ctx(), owner, feet, 0.3, 2.0, 5, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            BlockData ground = feet.clone().subtract(0, 0.3, 0).getBlock().getBlockData();
            if (ground.getMaterial().isSolid()) {
                feet.getWorld().spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 16), 0.5, 0.1, 0.5, 0.15, ground);
            }
            Vfx.cloud(ctx(), feet, Particle.CLOUD, 6, 0.4, 0.03, null, true);
            Vfx.cloud(ctx(), Fx.body(victim), Particle.DUST_COLOR_TRANSITION, 8, 0.4, 0, FADE, true);
            Fx.sound(feet, Sound.ENTITY_PLAYER_BIG_FALL, 1.2f, 0.8f);
            Fx.sound(feet, Sound.ITEM_MACE_SMASH_GROUND, 0.9f, 1.2f + Fx.jitter(0.05f));
        }

        private boolean leave(long t) {
            double progress = (t + 1.0) / LEAVE_TICKS;
            Location at = center.clone().add(0, progress * progress * 14, 0);
            if (carry.lengthSquared() > 1.0E-8) {
                at.add(carry.clone().normalize().multiply(progress * 6));
            }
            pose(at, (float) Math.max(0.05, 1 - progress * 0.6));
            Vfx.trail(at, at.clone().subtract(0, 2, 0), SILVER, 5, true);
            Vfx.dot(at.clone().subtract(0, 0.3, 0), Particle.DUST, SILVER_DUST, true);
            if (t == 0) {
                Fx.sound(at, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.2f, 0.6f);
                Fx.sound(at, Sound.BLOCK_BEACON_DEACTIVATE, 1.2f, 1.2f);
            }
            return t + 1 < LEAVE_TICKS;
        }

        /** The end (also when cut short): the catch is let go and the saucer flashes out of sight. */
        private void vanish() {
            for (Abductee abductee : taken) {
                letGo(abductee);
            }
            for (BlockDisplay part : hull) {
                remove(part);
            }
            for (BlockDisplay part : rim) {
                remove(part);
            }
            for (BlockDisplay part : shaft) {
                remove(part);
            }
            Vfx.dot(center, Particle.FLASH, PALETTE.core(), true);
        }

        private void letGo(Abductee abductee) {
            if (abductee.released) {
                return;
            }
            abductee.released = true;
            LivingEntity victim = abductee.victim;
            for (NamespacedKey key : abductee.modifiers) {
                ctx().removeModifier(victim, key);
            }
            abductee.modifiers.clear();
            clearSafety(victim);
            if (victim.isValid() && !victim.isDead()) {
                victim.setFallDistance(0);
            }
        }

        /** Steers one abductee along its planned path with damped correction; a stuck one is let go. */
        private void steer(Abductee abductee, Location next) {
            LivingEntity victim = abductee.victim;
            if (!Combat.isTargetable(ctx(), owner, victim) || !victim.getWorld().equals(next.getWorld())) {
                letGo(abductee);
                return;
            }
            Location planned = abductee.planned;
            if (planned != null && victim.getLocation().distance(planned) > STRAY_DISTANCE) {
                if (++abductee.stray > STRAY_TICKS) {
                    letGo(abductee);
                    if (victim.isValid() && !victim.isDead()) {
                        victim.setVelocity(new Vector(0, REST_FALL, 0));
                    }
                    return;
                }
            } else {
                abductee.stray = 0;
            }
            Vector current = victim.getLocation().toVector();
            Vector velocity = planned == null ? next.toVector().subtract(current)
                    : next.toVector().subtract(planned.toVector())
                            .add(planned.toVector().subtract(current).multiply(STEER_GAIN));
            victim.setVelocity(Motion.clamp(velocity, MAX_SPEED));
            victim.setFallDistance(0);
            abductee.planned = next.clone();
        }

        /** Where abductee {@code i} hangs under the saucer (its feet), turning slowly around the beam. */
        private Location hold(int i, LivingEntity victim, double turnDegrees) {
            int count = Math.max(1, taken.size());
            double r = count == 1 ? 0 : SLOT_RADIUS;
            double angle = Math.toRadians(turnDegrees) * 0.2 + i * TAU / count;
            Location at = center.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
            at.setY(Math.max(floorY + 0.3, center.getY() - HOLD_GAP - victim.getHeight()));
            return at;
        }

        /** Green motes rising in the light, the searchlight on the ground and the rim lights' sparkle. */
        private void drawShaft(long tick) {
            Location floor = floorSpot();
            double height = center.getY() - floorY;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                Location at = Vfx.scatter(floor, beamRadius * 0.85).add(0, random.nextDouble() * height, 0);
                world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, i % 2 == 0 ? GREEN_DUST : GREEN_SMALL, true);
            }
            if (tick % 2 == 0) {
                Vfx.ring(ctx(), floor, beamRadius, (int) Math.max(14, beamRadius * 8), tick * 0.15,
                        Particle.DUST_COLOR_TRANSITION, GLOW, true);
                double climb = (tick % 20) / 20.0;
                Vfx.ring(ctx(), floor.clone().add(0, height * climb, 0), beamRadius * (1 - 0.4 * climb), 12,
                        -tick * 0.2, Particle.DUST, GREEN_SMALL, true);
            }
            if (tick % 4 == 0) {
                Vfx.dot(center.clone().add(Math.cos(tick * 0.4) * RIM_RADIUS, 0, Math.sin(tick * 0.4) * RIM_RADIUS),
                        Particle.END_ROD, null, true);
            }
        }

        private Location floorSpot() {
            Location floor = center.clone();
            floor.setY(floorY + 0.12);
            return floor;
        }

        /** Every part of the saucer around {@code at}, grown to {@code scale}, turning with {@link #spin}. */
        private void pose(Location at, float scale) {
            Location spot = Displays.unrotated(at);
            float s = Math.max(0.01f, scale);
            Transformation[] poses = {
                    Displays.transform(0, 0, 0, HULL_SIZE * s, 0.28f * s, HULL_SIZE * s, spin, 0, 0),
                    Displays.transform(0, 0, 0, HULL_SIZE * s, 0.28f * s, HULL_SIZE * s, spin + 45, 0, 0),
                    Displays.transform(0, -0.26f * s, 0, 2.0f * s, 0.3f * s, 2.0f * s, spin + 22.5f, 0, 0),
                    Displays.transform(0, 0.45f * s, 0, 1.3f * s, 0.75f * s, 1.3f * s, spin, 0, 0),
                    Displays.transform(0, 0.45f * s, 0, 1.3f * s, 0.75f * s, 1.3f * s, spin + 45, 0, 0),
                    Displays.transform(0, 0.42f * s, 0, 0.42f * s, 0.42f * s, 0.42f * s, spin * 0.3f, 0, 0),
            };
            for (int i = 0; i < hull.size() && i < poses.length; i++) {
                BlockDisplay part = hull.get(i);
                if (part.isValid()) {
                    Displays.moveTo(part, spot);
                    Displays.animate(part, poses[i], 1);
                }
            }
            for (int i = 0; i < rim.size(); i++) {
                BlockDisplay light = rim.get(i);
                if (!light.isValid()) {
                    continue;
                }
                double angle = Math.toRadians(-spin * 1.6) + i * TAU / RIM_COUNT;
                float size = 0.2f * s * (1f + 0.25f * (float) Math.sin(spin * 0.2 + i));
                Displays.moveTo(light, spot);
                Displays.animate(light, Displays.transform((float) (Math.cos(angle) * RIM_RADIUS * s), 0,
                        (float) (Math.sin(angle) * RIM_RADIUS * s), size, size, size, spin * 2, 0, 0), 1);
            }
            float height = (float) Math.max(0.1, at.getY() - 0.4 - floorY);
            float width = (float) (beamRadius * 1.8) * shaftScale;
            for (int i = 0; i < 2; i++) {
                BlockDisplay light = shaft[i];
                if (light != null && light.isValid()) {
                    Displays.moveTo(light, spot);
                    Displays.animate(light, Displays.transform(0, -0.4f - height / 2, 0, Math.max(0.001f, width),
                            shaftScale > 0 ? height : 0.001f, Math.max(0.001f, width), spin * 0.5f + i * 45, 0, 0), 2);
                }
            }
        }

        /** The saucer's height over {@code floor}, lowered under a ceiling. */
        private double hoverY(Location at, double floor) {
            Location base = at.clone();
            base.setY(floor + 0.1);
            RayTraceResult ceiling = world.rayTraceBlocks(base, new Vector(0, 1, 0), UFO_HEIGHT + 1,
                    FluidCollisionMode.NEVER, true);
            if (ceiling == null) {
                return floor + UFO_HEIGHT;
            }
            return Math.max(floor + 2.6, ceiling.getHitPosition().getY() - 0.7);
        }
    }

    // ================================================================== helpers

    /** The top of the floor under {@code at}, searching down from {@code fromY}; NaN if there is none in reach. */
    private static double groundY(Location at, double fromY) {
        Location start = at.clone();
        start.setY(fromY);
        RayTraceResult ground = at.getWorld().rayTraceBlocks(start, new Vector(0, -1, 0), GROUND_SEARCH,
                FluidCollisionMode.ALWAYS, true);
        return ground == null ? Double.NaN : ground.getHitPosition().getY();
    }

    /** Removes the safety-net slow falling of the beam, never a slow falling the body got elsewhere. */
    private static void clearSafety(LivingEntity held) {
        PotionEffect effect = held.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (effect != null && effect.isAmbient() && !effect.hasParticles() && !effect.hasIcon()
                && effect.getAmplifier() == 0) {
            held.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
    }

    private static double smooth(double t) {
        double c = Math.clamp(t, 0, 1);
        return c * c * (3 - 2 * c);
    }

    private static Location lerp(Location from, Location to, double t) {
        return from.clone().add(to.toVector().subtract(from.toVector()).multiply(t));
    }

    /** Claims room for {@code count} more displays (dead ones are forgotten first). */
    private boolean room(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    private static void remove(@Nullable Display display) {
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    /** A word popping up and shrinking away. */
    private void popText(Location at, String miniMessage, float scale, long life) {
        if (!room(1)) {
            return;
        }
        Player owner = player();
        TextDisplay text = keep(Displays.text(ctx(), owner, at, Text.mm(miniMessage), scale * 0.5f, life));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0, 0, 0), 4);
        ctx().runLater(owner, Math.max(2, life - 5), () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 4);
            }
        });
    }
}
