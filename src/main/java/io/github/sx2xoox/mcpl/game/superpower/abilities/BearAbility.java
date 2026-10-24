package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 곰 — a big, thick-skinned brawler. Its hide blunts every blow and roots it against knockback; it rears up and
 * mauls everything in front of it flat onto the ground; and as the ultimate it curls up for a winter's nap that heals
 * it behind a heavy hide — until it wakes, by itself or because someone poked it, with a roar and a fit of rage.
 *
 * <p>Look: honey and bear brown. Blunted hits throw tufts of brown fur. For the maul two big furry paws with pale claws
 * rise over the bear's head while a honey-coloured fan marks the ground in front, then crash down: three gouges are
 * torn into the floor and red claw marks flash across every victim, who lies pinned under a ring of dizzy stars. The
 * nap is a mossy den with a carpet of moss, rising "Z"s, hearts and honey drips; every hit taken while asleep flashes
 * an angry mark and counts down to an early, angrier wake. The roar is a burst of fur, dust and a sonic ring; during
 * the rage the bear's eyes glow red to everyone else and its breath steams.
 */
public final class BearAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("bear", "곰", Material.HONEY_BOTTLE)
            .color(TextColor.color(0xC8843C))
            .difficulty(1)
            .passive("두꺼운 가죽",
                    "근접 공격으로 받는 피해가 {hide_flat}만큼 줄어듭니다(최소 {hide_min}). 넉백을 {hide_knockback}% 덜 "
                            + "받습니다.")
            .active("곰 발바닥",
                    "뒷발로 일어섰다가 {paw_windup}초 뒤 앞발로 내리쳐, 앞쪽 {paw_range}블록·{paw_angle}° 부채꼴 안의 적에게 "
                            + "{paw_damage} 피해를 주고 {paw_knockdown}초간 쓰러뜨립니다(이동·점프 불가). 쓰러진 적에게 주는 "
                            + "근접 피해가 {downed_bonus}% 늘어납니다.",
                    8)
            .ultimate("동면",
                    "그 자리(공중이면 떨어진 땅)에 웅크려 최대 {sleep_duration}초간 겨울잠을 잡니다. 자는 동안 움직이거나 공격할 수 없지만 받는 "
                            + "피해가 {sleep_reduction}% 줄고 1초마다 체력을 {sleep_heal} 회복합니다. 웅크리면 일찍 깨어나고, "
                            + "적에게 {wake_hits}번 맞으면 화가 나 깨어납니다. 깨어나며 포효해 반경 {roar_radius}블록의 적에게 "
                            + "{roar_damage} 피해(억지로 깨웠다면 {rude_roar_damage})를 주고 밀쳐내며, {rage_duration}초간 "
                            + "분노해 근접 피해가 {rage_melee}%, 이동 속도가 {rage_speed}% 늘어납니다.")
            .param("hide_flat", 1.5)
            .param("hide_min", 1)
            .param("hide_knockback", 40)
            .param("paw_windup", 0.35)
            .param("paw_range", 4)
            .param("paw_angle", 120)
            .param("paw_damage", 5)
            .param("paw_knockdown", 1)
            .param("downed_bonus", 30)
            .param("sleep_duration", 4)
            .param("sleep_reduction", 70)
            .param("sleep_heal", 1.5)
            .param("wake_hits", 3)
            .param("roar_radius", 5)
            .param("roar_damage", 3)
            .param("rude_roar_damage", 6)
            .param("rage_duration", 6)
            .param("rage_melee", 30)
            .param("rage_speed", 20)
            .ultimateNotStandalone()
            .build();

    private static final String COLOR_TAG = "#C8843C";
    private static final String HONEY_TAG = "#FFC94A";
    private static final String RAGE_TAG = "#FF5A3C";

    // ------------------------------------------------------------------ look
    /** Honey glow, bear brown, the dark of the den. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFC94A, 0x8B5A2B, 0x2E1B0E);
    private static final Particle.DustOptions FUR = new Particle.DustOptions(Color.fromRGB(0x7A4A22), 1.2f);
    private static final Particle.DustOptions FUR_SMALL = new Particle.DustOptions(Color.fromRGB(0x9C6534), 0.8f);
    private static final Particle.DustTransition HONEY = PALETTE.glow(1.1f);
    private static final Particle.DustTransition RAGE = new Particle.DustTransition(Color.fromRGB(0xFF7A4A),
            Color.fromRGB(0x6A1408), 1.1f);
    private static final Particle.DustOptions RAGE_EYE = new Particle.DustOptions(Color.fromRGB(0xFF2A1A), 0.6f);
    private static final Particle.DustOptions STAR = new Particle.DustOptions(Color.fromRGB(0xFFE07A), 0.7f);

    /** Materials of the visuals; block data is created when used (it needs a running server). */
    private static final Material PAW = Material.BROWN_WOOL;
    private static final Material CLAW = Material.BONE_BLOCK;
    private static final Material GOUGE = Material.BLACK_TERRACOTTA;
    private static final Material SCRATCH = Material.RED_CONCRETE;
    private static final Material DEN_MAT = Material.MOSS_CARPET;
    private static final Material[] DEN_PIECES = {Material.MOSS_BLOCK, Material.AZALEA_LEAVES,
            Material.BROWN_MUSHROOM_BLOCK};

    private static final double MAX_SPEED = 1.8;

    // ------------------------------------------------------------------ passive: thick hide
    private static final long HIDE_FX_COOLDOWN = 6;

    // ------------------------------------------------------------------ active: maul
    private static final double WINDUP_SLOW = 0.6;
    private static final int SLAM_TICKS = 2;
    private static final int PAW_HOLD_TICKS = 8;
    private static final int PAW_FADE_TICKS = 5;
    private static final float PAW_SIZE = 0.42f;
    private static final double PAW_SIDE = 0.42;
    private static final double PAW_RAISED = 2.35;
    private static final double PAW_RAISED_FORWARD = 0.25;
    private static final double PAW_SLAM_FORWARD = 1.45;
    private static final float CLAW_LENGTH = 0.24f;
    private static final float CLAW_WIDTH = 0.05f;
    /** Enemies closer than this count as in front whatever their angle (they are under the bear's paws). */
    private static final double POINT_BLANK = 0.6;
    private static final long GOUGE_TICKS = 40;
    private static final float GOUGE_LENGTH = 1.9f;
    private static final long SCRATCH_TICKS = 8;
    private static final long DOWNED_CLEANUP_PERIOD = 100;

    // ------------------------------------------------------------------ ultimate: hibernation
    /** The bear must sleep this long before a sneak can wake it (so the key press that started it does not). */
    private static final long SNEAK_GRACE = 10;
    private static final long HEAL_PERIOD = 20;
    private static final long ZZZ_PERIOD = 20;
    private static final long ZZZ_LIFE = 34;
    private static final long SNORE_PERIOD = 30;
    private static final double DEN_RADIUS = 0.78;
    private static final int DEN_COUNT = 6;
    /** The den is made on the ground at most this far below a bear that falls asleep in the air. */
    private static final double DEN_DROP = 4;
    /** Size of the probe that makes sure a ground ray does not start inside a block. */
    private static final double PROBE_SIZE = 0.1;
    private static final double ROAR_PUSH = 1.0;
    private static final double ROAR_LIFT = 0.4;
    private static final long ROAR_LABEL_TICKS = 22;

    private final Map<UUID, Long> downedUntil = new HashMap<>();
    private @Nullable Maul maul;
    private @Nullable Hibernation sleep;
    private long rageUntil = Long.MIN_VALUE / 2;
    private long lastHideFx = Long.MIN_VALUE / 2;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        Player owner = player();
        double resistance = Math.clamp(params().num("hide_knockback") / 100.0, 0.0, 1.0);
        if (resistance > 0) {
            ctx().addModifier(owner, owner, Attribute.KNOCKBACK_RESISTANCE, resistance, Operation.ADD_NUMBER, 0);
        }
    }

    @Override
    public void onRevoke() {
        Hibernation nap = sleep;
        if (nap != null && !nap.woke) {
            nap.standUp();
        }
        maul = null;
        sleep = null;
        downedUntil.clear();
        rageUntil = Long.MIN_VALUE / 2;
    }

    @Override
    public void onTick(long tick) {
        if (tick % DOWNED_CLEANUP_PERIOD == 0 && !downedUntil.isEmpty()) {
            long now = ctx().currentTick();
            downedUntil.values().removeIf(until -> until <= now);
        }
    }

    private boolean asleep() {
        Hibernation nap = sleep;
        return nap != null && !nap.woke;
    }

    private boolean raging() {
        return ctx().currentTick() < rageUntil;
    }

    private boolean isDowned(LivingEntity victim) {
        Long until = downedUntil.get(victim.getUniqueId());
        return until != null && ctx().currentTick() < until;
    }

    // ------------------------------------------------------------------ damage hooks

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0) {
            return;
        }
        if (isMelee(event)) {
            thickHide(event);
        }
        Hibernation nap = sleep;
        if (nap == null || nap.woke) {
            return;
        }
        double reduction = Math.clamp(params().num("sleep_reduction") / 100.0, 0.0, 1.0);
        event.setDamage(event.getDamage() * (1 - reduction));
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = ctx().resolveAttacker(byEntity.getDamager());
            if (attacker != null && ctx().isEnemy(player(), attacker)) {
                nap.disturb();
            }
        }
    }

    /** A plain melee blow (a swing or a summon's bite), not ability damage. */
    private boolean isMelee(EntityDamageEvent event) {
        return event instanceof EntityDamageByEntityEvent && !ctx().isAbilityDamage()
                && (event.getCause() == DamageCause.ENTITY_ATTACK || event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK);
    }

    /** The thick hide takes a flat bite out of every melee blow, never below the minimum. */
    private void thickHide(EntityDamageEvent event) {
        double original = event.getDamage();
        double flat = Math.max(0, params().num("hide_flat"));
        double floor = Math.min(original, Math.max(0, params().num("hide_min")));
        double reduced = Math.max(floor, original - flat);
        if (reduced >= original - 1.0E-9) {
            return;
        }
        event.setDamage(reduced);
        long now = ctx().currentTick();
        if (now - lastHideFx < HIDE_FX_COOLDOWN) {
            return;
        }
        lastHideFx = now;
        Player owner = player();
        Location body = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager().getWorld().equals(body.getWorld())) {
            Vector toward = byEntity.getDamager().getLocation().toVector().subtract(owner.getLocation().toVector());
            body.add(Motion.horizontal(toward).multiply(0.35));
        }
        World world = body.getWorld();
        world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 8), 0.2, 0.25, 0.2, 0.05, PAW.createBlockData());
        world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 6), 0.25, 0.3, 0.25, 0, FUR_SMALL);
        Fx.sound(body, Sound.BLOCK_WOOL_HIT, 1.0f, 0.6f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.5f, 0.6f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">두꺼운 가죽</" + COLOR_TAG + "> <gray>-"
                + AbilityInfo.formatNumber(Math.round((original - reduced) * 10) / 10.0) + "</gray>"));
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || ctx().isAbilityDamage()) {
            return;
        }
        Player owner = player();
        if (asleep() && owner.equals(ctx().resolveAttacker(event.getDamager()))) {
            event.setCancelled(true); // a sleeping bear does not fight
            return;
        }
        if (!isOwnMelee(event)) {
            return;
        }
        double multiplier = 1;
        if (isDowned(victim)) {
            multiplier *= 1 + Math.max(0, params().num("downed_bonus")) / 100.0;
        }
        if (raging()) {
            multiplier *= 1 + Math.max(0, params().num("rage_melee")) / 100.0;
        }
        if (multiplier != 1) {
            event.setDamage(event.getDamage() * multiplier);
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() || !isOwnMelee(event)) {
            return;
        }
        boolean downed = isDowned(victim);
        boolean rage = raging();
        if (!downed && !rage) {
            return;
        }
        Location body = Fx.body(victim);
        float yaw = yawOf(body.toVector().subtract(player().getLocation().toVector()));
        Vfx.arc(ctx(), body, 0.55, yaw, 120, 9, Particle.DUST_COLOR_TRANSITION, rage ? RAGE : HONEY, false);
        Vfx.cloud(ctx(), body, Particle.CRIT, 6, 0.25, 0.2, null, false);
        if (downed) {
            Vfx.dot(body, Particle.SWEEP_ATTACK, null, false);
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 0.7f + Fx.jitter(0.08f));
        }
        if (rage) {
            Fx.sound(body, Sound.ENTITY_HOGLIN_ATTACK, 0.7f, 0.8f + Fx.jitter(0.1f));
        }
    }

    private boolean isOwnMelee(EntityDamageByEntityEvent event) {
        return player().equals(event.getDamager())
                && (event.getCause() == DamageCause.ENTITY_ATTACK || event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK);
    }

    // ------------------------------------------------------------------ active: maul

    @Override
    public ActivationResult onActive() {
        if (asleep()) {
            return ActivationResult.fail("겨울잠을 자는 중입니다");
        }
        Maul running = maul;
        if (running != null && !running.done) {
            return ActivationResult.fail("이미 앞발을 들어 올렸습니다");
        }
        Maul fresh = new Maul(player());
        maul = fresh;
        fresh.start();
        return ActivationResult.success();
    }

    /**
     * The maul: the bear rears up (its paws rise over its head, a fan marks the ground in front), then brings both
     * paws down. The fan follows the bear's facing during the wind-up; the blow lands where it faces at the end.
     */
    private final class Maul implements LongPredicate {

        private final Player owner;
        private final long windup;
        private final double range;
        private final double halfAngle;
        /** Two paws, each a pad and three claws: [pad, claw, claw, claw] per side, left then right. */
        private final BlockDisplay[] parts = new BlockDisplay[8];
        private @Nullable Location strikeFeet;
        private float strikeYaw;
        private boolean done;

        Maul(Player owner) {
            this.owner = owner;
            this.windup = Math.max(1, params().ticks("paw_windup"));
            this.range = Math.max(0.5, params().num("paw_range"));
            this.halfAngle = Math.clamp(params().num("paw_angle"), 1.0, 360.0) / 2;
        }

        void start() {
            float yaw = owner.getLocation().getYaw();
            Location feet = Displays.unrotated(owner.getLocation());
            long life = windup + SLAM_TICKS + PAW_HOLD_TICKS + PAW_FADE_TICKS + 6;
            for (int side = 0; side < 2; side++) {
                for (int k = 0; k < 4; k++) {
                    Material material = k == 0 ? PAW : CLAW;
                    BlockDisplay part = Displays.block(ctx(), owner, feet, material.createBlockData(),
                            partPose(side, k, 0.2, yaw, 0.3f), life);
                    part.setBrightness(null);
                    part.setTeleportDuration(1);
                    parts[side * 4 + k] = part;
                }
            }
            ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -WINDUP_SLOW, Operation.MULTIPLY_SCALAR_1,
                    windup);
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.7, 0);
            Vfx.cloud(ctx(), chest, Particle.DUST, 8, 0.3, 0, FUR, false);
            Fx.sound(chest, Sound.ENTITY_POLAR_BEAR_WARNING, 1.3f, 0.85f + Fx.jitter(0.05f));
            Fx.sound(chest, Sound.ENTITY_RAVAGER_STEP, 0.8f, 0.7f + Fx.jitter(0.05f));
            Loop.repeat(ctx(), owner, 1, 1, windup + SLAM_TICKS + PAW_HOLD_TICKS, this, this::finish);
        }

        void cancel() {
            if (done) {
                return;
            }
            done = true;
            for (BlockDisplay part : parts) {
                if (part != null && part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 3);
                }
            }
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            long t = tick + 1; // the loop starts a tick after the paws appeared
            if (t < windup) {
                rear(t);
                return true;
            }
            if (t == windup) {
                strike();
            }
            return true;
        }

        /** Rearing up: the paws rise over the head, trembling, while the fan of the blow marks the ground. */
        private void rear(long t) {
            Location feet = owner.getLocation();
            float yaw = feet.getYaw();
            double s = Math.min(1.0, (double) t / Math.max(1, windup - 1));
            for (BlockDisplay part : parts) {
                if (part != null && part.isValid()) {
                    Displays.moveTo(part, feet);
                }
            }
            pose(yaw, -s, 1);
            if (t % 2 == 1) {
                Location ground = feet.clone().add(0, 0.08, 0);
                Vfx.arc(ctx(), ground, range, yaw, halfAngle * 2, (int) Math.max(10, range * 5), Particle.DUST_COLOR_TRANSITION,
                        HONEY, false);
                for (int edge = -1; edge <= 1; edge += 2) {
                    double angle = Math.toRadians(yaw + edge * halfAngle);
                    Location tip = ground.clone().add(-Math.sin(angle) * range, 0, Math.cos(angle) * range);
                    Vfx.line(ctx(), ground, tip, 2, Particle.DUST, FUR_SMALL, false);
                }
            }
        }

        /** Poses the paws: {@code blend} -1 is fully raised, 0 halfway, 1 slammed onto the ground in front. */
        private void pose(float yaw, double blend, int ticks) {
            for (int side = 0; side < 2; side++) {
                for (int k = 0; k < 4; k++) {
                    BlockDisplay part = parts[side * 4 + k];
                    if (part != null && part.isValid()) {
                        Displays.animate(part, partPose(side, k, blend, yaw, 1f), ticks);
                    }
                }
            }
        }

        /** The paws crash down: everything in the fan is mauled and pinned. */
        private void strike() {
            Location feet = owner.getLocation();
            strikeYaw = feet.getYaw();
            strikeFeet = Displays.unrotated(feet);
            for (BlockDisplay part : parts) {
                if (part != null && part.isValid()) {
                    part.setTeleportDuration(0);
                    Displays.moveTo(part, strikeFeet);
                }
            }
            pose(strikeYaw, 1.0, SLAM_TICKS);
            Vector facing = Motion.horizontalFacing(feet);
            Location chest = feet.clone().add(0, owner.getHeight() * 0.55, 0);
            double damage = params().num("paw_damage");
            double cos = Math.cos(Math.toRadians(halfAngle));
            int hits = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, chest, range)) {
                Location body = Fx.body(enemy);
                Vector to = body.toVector().subtract(chest.toVector()).setY(0);
                if (to.length() > POINT_BLANK && to.clone().normalize().dot(facing) < cos - 1.0E-9) {
                    continue;
                }
                if (!Spots.clearPath(chest, body)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                scratch(enemy, facing);
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    knockDown(enemy);
                }
            }
            gouges(feet, facing);
            Location front = feet.clone().add(facing.clone().multiply(PAW_SLAM_FORWARD)).add(0, 0.1, 0);
            World world = front.getWorld();
            BlockData floor = floorAt(front);
            world.spawnParticle(Particle.BLOCK, front, Vfx.n(ctx(), 20), 0.6, 0.1, 0.6, 0.15, floor);
            world.spawnParticle(Particle.DUST_PLUME, front, Vfx.n(ctx(), 6), 0.5, 0.05, 0.5, 0.02);
            Vfx.arc(ctx(), front, 1.1, strikeYaw, 160, 14, Particle.DUST_COLOR_TRANSITION, HONEY, false);
            Loop.repeat(ctx(), owner, 0, 1, 4, run -> {
                double r = 0.8 + run * (range - 0.8) / 3.0;
                Vfx.arc(ctx(), feet.clone().add(0, 0.1, 0), r, strikeYaw, halfAngle * 2, (int) Math.max(8, r * 5),
                        Particle.DUST, FUR_SMALL, false);
                return true;
            });
            Fx.sound(front, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 0.9f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(front, Sound.ENTITY_POLAR_BEAR_HURT, 0.8f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(front, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f + Fx.jitter(0.05f));
            if (hits > 0) {
                Fx.sound(front, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.6f + Fx.jitter(0.05f));
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>곰 발바닥!</b></" + COLOR_TAG + "> <white>" + hits
                        + "명 쓰러뜨림</white>"));
            }
        }

        /** The paws stay a moment where they hit, then the bear lifts them away. */
        private void finish() {
            done = true;
            if (maul == this) {
                maul = null;
            }
            for (BlockDisplay part : parts) {
                if (part != null && part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, PAW_FADE_TICKS);
                }
            }
        }
    }

    /**
     * One paw part ({@code k} 0 = the furry pad, 1..3 = its claws) of the left ({@code side} 0) or right paw, in the
     * transformation of a display standing at the bear's feet, turned to the bear's {@code yaw}. {@code blend} runs
     * from -1 (raised over the head) to 1 (slammed down in front).
     */
    static Transformation partPose(int side, int k, double blend, float yaw, float grow) {
        double s = (blend + 1) / 2; // 0 raised .. 1 slammed
        double sideSign = side == 0 ? 1 : -1; // facing +Z, the left hand is +X
        double up = PAW_RAISED + (0.25 - PAW_RAISED) * s;
        double forward = PAW_RAISED_FORWARD + (PAW_SLAM_FORWARD - PAW_RAISED_FORWARD) * s;
        double across = PAW_SIDE * sideSign * (1 - 0.15 * s);
        // Claws point forward and up while raised, forward and down into the ground when slammed.
        float pitch = (float) (-50 + 75 * s);
        double localX = across;
        double localY = up;
        double localZ = forward;
        float sx = PAW_SIZE * grow;
        float sy = PAW_SIZE * grow;
        float sz = PAW_SIZE * grow;
        if (k > 0) {
            double spread = (k - 2) * 0.13;
            localX = across + spread;
            // From the front of the pad, along the claw (pitch positive = down).
            localY = up + PAW_SIZE * 0.15 - Math.sin(Math.toRadians(pitch)) * CLAW_LENGTH / 2;
            localZ = forward + PAW_SIZE / 2 + Math.cos(Math.toRadians(pitch)) * CLAW_LENGTH / 2;
            sx = CLAW_WIDTH * grow;
            sy = CLAW_WIDTH * grow;
            sz = CLAW_LENGTH * grow;
        }
        double radians = Math.toRadians(yaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        // Body space (x left, z forward) to world space for a body facing yaw (0 = +Z, 90 = -X).
        float worldX = (float) (localX * cos - localZ * sin);
        float worldZ = (float) (localX * sin + localZ * cos);
        return Displays.transform(worldX, (float) localY, worldZ, sx, sy, sz, yaw, k > 0 ? pitch : 0, 0);
    }

    /** Pinned to the ground: no moving or jumping for the knockdown, with a ring of dizzy stars over the head. */
    private void knockDown(LivingEntity enemy) {
        Player owner = player();
        long ticks = params().ticks("paw_knockdown");
        if (ticks <= 0) {
            return;
        }
        ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, ticks);
        ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, ticks);
        boolean grounded = Motion.isGrounded(enemy) || enemy.isInWater();
        enemy.setVelocity(new Vector(0, grounded ? 0 : -0.5, 0));
        downedUntil.put(enemy.getUniqueId(), ctx().currentTick() + ticks);
        if (enemy instanceof Player hidden && !owner.canSee(hidden)) {
            return; // no stars over someone the bear cannot see
        }
        TextDisplay label = Displays.text(ctx(), owner, enemy.getLocation().add(0, enemy.getHeight() + 0.5, 0),
                Text.mm("<b><" + HONEY_TAG + ">꽈당!</" + HONEY_TAG + "></b>"), 0.5f, 18);
        Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0.35f, 0, 1.0f, 1.0f, 1.0f, 0, 0, 0), 4);
        ctx().runLater(owner, 12, () -> {
            if (label.isValid()) {
                Displays.shrinkAway(ctx(), owner, label, 5);
            }
        });
        Loop.repeat(ctx(), owner, 1, 2, Math.max(1, ticks / 2), run -> {
            if (!enemy.isValid() || enemy.isDead() || !isDowned(enemy)) {
                return false;
            }
            Location head = enemy.getLocation().add(0, enemy.getHeight() + 0.25, 0);
            for (int i = 0; i < 3; i++) {
                double angle = run * 0.8 + i * Math.PI * 2 / 3;
                Vfx.dot(head.clone().add(Math.cos(angle) * 0.42, 0.05 * Math.sin(run + i), Math.sin(angle) * 0.42),
                        Particle.DUST, STAR, false);
            }
            if (run % 3 == 0) {
                Vfx.dot(head.clone().add(Math.cos(run * 1.3) * 0.4, 0.1, Math.sin(run * 1.3) * 0.4), Particle.WAX_ON,
                        null, false);
            }
            Vfx.ring(ctx(), enemy.getLocation().add(0, 0.05, 0), 0.55, 8, run * 0.3, Particle.DUST, FUR_SMALL, false);
            return true;
        });
    }

    /** Three red claw marks raking across the victim, as seen from the bear. */
    private void scratch(LivingEntity enemy, Vector facing) {
        Player owner = player();
        if (enemy instanceof Player hidden && !owner.canSee(hidden)) {
            return;
        }
        Location body = Fx.body(enemy).subtract(facing.clone().multiply(enemy.getWidth() * 0.55));
        float yaw = yawOf(facing);
        for (int i = -1; i <= 1; i++) {
            float offset = i * 0.2f;
            BlockDisplay mark = Displays.block(ctx(), owner, body, SCRATCH.createBlockData(),
                    Displays.transform(0, offset, 0, 0.15f, 0.05f, 0.04f, yaw, 0, 55), SCRATCH_TICKS + 6);
            Displays.animateNextTick(ctx(), owner, mark, Displays.transform(0, offset, 0, 0.95f, 0.05f, 0.04f, yaw, 0,
                    55), 2);
            ctx().runLater(owner, SCRATCH_TICKS, () -> {
                if (mark.isValid()) {
                    Displays.shrinkAway(ctx(), owner, mark, 4);
                }
            });
        }
        Location center = Fx.body(enemy);
        Vfx.cloud(ctx(), center, Particle.CRIT, 10, 0.3, 0.25, null, false);
        center.getWorld().spawnParticle(Particle.DUST, center, Vfx.n(ctx(), 8), 0.25, 0.3, 0.25, 0, FUR);
        Fx.sound(center, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 0.7f + Fx.jitter(0.08f));
    }

    /** Three gouges torn into the floor in front of the bear, fading after a while. */
    private void gouges(Location feet, Vector facing) {
        Player owner = player();
        float yaw = yawOf(facing);
        Vector side = new Vector(-facing.getZ(), 0, facing.getX());
        for (int i = -1; i <= 1; i++) {
            Location center = feet.clone().add(facing.clone().multiply(PAW_SLAM_FORWARD + GOUGE_LENGTH / 2 - 0.4))
                    .add(side.clone().multiply(i * 0.3));
            Location ground = groundUnder(center.clone().add(0, 0.6, 0), 1.6);
            if (ground == null) {
                continue;
            }
            BlockDisplay gouge = Displays.block(ctx(), owner, Displays.unrotated(ground), GOUGE.createBlockData(),
                    Displays.transform(0, 0.015f, 0, 0.1f, 0.02f, 0.2f, yaw, 0, 0), GOUGE_TICKS + 8);
            gouge.setBrightness(null);
            Displays.animateNextTick(ctx(), owner, gouge, Displays.transform(0, 0.015f, 0, 0.1f, 0.02f, GOUGE_LENGTH,
                    yaw, 0, 0), 2);
            ctx().runLater(owner, GOUGE_TICKS, () -> {
                if (gouge.isValid()) {
                    Displays.shrinkAway(ctx(), owner, gouge, 8);
                }
            });
        }
    }

    // ------------------------------------------------------------------ ultimate: hibernation

    @Override
    public ActivationResult onUltimate() {
        if (asleep()) {
            return ActivationResult.fail("이미 겨울잠을 자는 중입니다");
        }
        Maul running = maul;
        if (running != null) {
            running.cancel();
            maul = null;
        }
        Hibernation nap = new Hibernation(player());
        sleep = nap;
        nap.start();
        return ActivationResult.success();
    }

    /**
     * The winter nap: the bear curls up (everyone sees it crouched) in a mossy den on the ground, is rooted, takes far
     * less damage and heals every second. It wakes when the nap is over, when its owner sneaks, or — angrier — after
     * enough hits from enemies. Asleep in the air, it drops onto the den made on the ground below.
     */
    private final class Hibernation implements LongPredicate {

        private final Player owner;
        private final long duration;
        private final int wakeHits;
        private final List<NamespacedKey> holds = new ArrayList<>();
        private final List<BlockDisplay> den = new ArrayList<>();
        private boolean sneakArmed;
        /** The bear fell asleep high in the air: the den is made once it lands. */
        private boolean denPending;
        private int irritation;
        private double healed;
        private boolean woke;
        private long ticks;

        Hibernation(Player owner) {
            this.owner = owner;
            this.duration = Math.max(1, params().ticks("sleep_duration"));
            this.wakeHits = Math.max(1, params().integer("wake_hits"));
        }

        void start() {
            holds.add(ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, 0));
            holds.add(ctx().addModifier(owner, owner, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, 0));
            holds.add(ctx().addModifier(owner, owner, Attribute.KNOCKBACK_RESISTANCE, 1.0, Operation.ADD_NUMBER, 0));
            // Sends no motion but a drop: a bear asleep in mid-air falls onto its den, never hangs there.
            owner.setVelocity(new Vector(0, Motion.isGrounded(owner) ? 0 : -0.1, 0));
            sneakArmed = !owner.isSneaking();
            // Curled up: the others see the sleeping bear crouched (the bear's own client shows its own pose).
            owner.setPose(Pose.SNEAKING, true);
            Location floor = denFloor();
            if (floor != null) {
                buildDen(floor);
            } else {
                denPending = true;
            }
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
            Vfx.cloud(ctx(), chest, Particle.DUST, 12, 0.4, 0, FUR, false);
            Vfx.ring(ctx(), owner.getLocation().add(0, 0.1, 0), 1.2, 16, 0, Particle.DUST_COLOR_TRANSITION, HONEY,
                    false);
            Fx.sound(chest, Sound.ENTITY_POLAR_BEAR_AMBIENT, 1.0f, 0.6f);
            Fx.sound(chest, Sound.ENTITY_FOX_SLEEP, 1.0f, 0.5f);
            Fx.sound(chest, Sound.BLOCK_MOSS_CARPET_PLACE, 1.0f, 0.8f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>동면</b></" + COLOR_TAG + "> <gray>웅크리면 깨어납니다</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, () -> wake(false));
        }

        /** The ground the den lies on: right under the bear, or where a bear asleep in the air will land. */
        private @Nullable Location denFloor() {
            return groundUnder(owner.getLocation(), DEN_DROP);
        }

        /** A mossy den: a moss carpet under the bear and a ring of moss, azalea and mushroom tufts around it. */
        private void buildDen(Location floor) {
            Location feet = Displays.unrotated(floor);
            long life = duration + 40;
            BlockDisplay mat = Displays.block(ctx(), owner, feet, DEN_MAT.createBlockData(),
                    Displays.transform(0, 0.51f, 0, 0.3f, 1f, 0.3f, 0, 0, 0), life);
            mat.setBrightness(null);
            Displays.animateNextTick(ctx(), owner, mat, Displays.transform(0, 0.51f, 0, 1.6f, 1f, 1.6f, 15, 0, 0), 6);
            den.add(mat);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < DEN_COUNT; i++) {
                double angle = i * Math.PI * 2 / DEN_COUNT + random.nextDouble(-0.2, 0.2);
                float size = 0.28f + random.nextFloat() * 0.14f;
                float x = (float) (Math.cos(angle) * DEN_RADIUS);
                float z = (float) (Math.sin(angle) * DEN_RADIUS);
                float spin = random.nextFloat() * 90;
                BlockDisplay piece = Displays.block(ctx(), owner, feet, DEN_PIECES[i % DEN_PIECES.length]
                        .createBlockData(), Displays.transform(x, 0.05f, z, 0.05f, 0.05f, 0.05f, spin, 0, 0), life);
                piece.setBrightness(null);
                ctx().runLater(owner, 1 + i, () -> {
                    if (piece.isValid()) {
                        Displays.animate(piece, Displays.transform(x, size * 0.4f, z, size, size, size, spin, 0, 0),
                                4);
                    }
                });
                den.add(piece);
            }
        }

        @Override
        public boolean test(long tick) {
            if (woke || sleep != this) {
                return false;
            }
            ticks = tick + 1;
            if (denPending && (Motion.isGrounded(owner) || owner.isInWater())) {
                Location floor = denFloor();
                if (floor != null) {
                    denPending = false;
                    buildDen(floor);
                }
            }
            if (!owner.isSneaking()) {
                sneakArmed = true;
            } else if (sneakArmed && ticks >= SNEAK_GRACE) {
                wake(false);
                return false;
            }
            if (ticks % HEAL_PERIOD == 0) {
                heal();
            }
            if (ticks % ZZZ_PERIOD == 5) {
                zzz();
            }
            if (ticks % SNORE_PERIOD == 0) {
                Location head = owner.getEyeLocation();
                Fx.sound(head, Sound.ENTITY_FOX_SLEEP, 0.9f, 0.45f + Fx.jitter(0.05f));
                Fx.sound(head, Sound.ENTITY_POLAR_BEAR_AMBIENT_BABY, 0.4f, 0.5f);
                Vfx.cloud(ctx(), head, Particle.CLOUD, 2, 0.1, 0.01, null, false);
            }
            if (ticks % 4 == 0) {
                double breath = 0.9 + 0.15 * Math.sin(ticks * 0.15);
                Vfx.ring(ctx(), owner.getLocation().add(0, 0.12, 0), breath, 10, ticks * 0.05,
                        Particle.DUST_COLOR_TRANSITION, HONEY, false);
            }
            if (ticks % 5 == 0) {
                status();
            }
            return true;
        }

        private void heal() {
            double amount = Math.max(0, params().num("sleep_heal"));
            double before = owner.getHealth();
            ctx().heal(owner, amount);
            healed += owner.getHealth() - before;
            Location head = owner.getEyeLocation().add(0, 0.3, 0);
            Vfx.cloud(ctx(), head, Particle.HEART, 2, 0.3, 0, null, false);
            Vfx.cloud(ctx(), head, Particle.DRIPPING_HONEY, 3, 0.35, 0, null, false);
            Fx.sound(head, Sound.BLOCK_HONEY_BLOCK_STEP, 0.6f, 1.2f + Fx.jitter(0.1f));
        }

        /** A "Z" floating up from the sleeping bear and fading away. */
        private void zzz() {
            Location head = owner.getEyeLocation().add(0.25, 0.35, 0.25);
            boolean big = (ticks / ZZZ_PERIOD) % 2 == 0;
            TextDisplay z = Displays.text(ctx(), owner, head, Text.mm("<b><" + HONEY_TAG + ">Z</" + HONEY_TAG + "></b>"),
                    big ? 0.9f : 0.6f, ZZZ_LIFE);
            z.setTeleportDuration(ZZZ_LIFE > 59 ? 59 : (int) ZZZ_LIFE - 4);
            ctx().runLater(owner, 1, () -> {
                if (z.isValid()) {
                    Displays.moveTo(z, head.clone().add(0.35, 1.2, 0.2));
                }
            });
            ctx().runLater(owner, ZZZ_LIFE - 8, () -> {
                if (z.isValid()) {
                    Displays.shrinkAway(ctx(), owner, z, 6);
                }
            });
        }

        private void status() {
            long left = Math.max(0, duration - ticks);
            StringBuilder anger = new StringBuilder();
            for (int i = 0; i < wakeHits; i++) {
                anger.append(i < irritation ? "<" + RAGE_TAG + ">●</" + RAGE_TAG + ">" : "<dark_gray>○</dark_gray>");
            }
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">Zz 동면</" + COLOR_TAG + "> <white>" + Fx.seconds(left)
                    + "초</white> <dark_gray>|</dark_gray> <" + HONEY_TAG + ">+" + AbilityInfo.formatNumber(
                    Math.round(healed * 10) / 10.0) + "</" + HONEY_TAG + "> <dark_gray>|</dark_gray> " + anger));
        }

        /** An enemy hit the sleeping bear: an angry mark, and after enough of them it wakes up furious. */
        void disturb() {
            if (woke) {
                return;
            }
            irritation++;
            Location head = owner.getEyeLocation().add(0, 0.45, 0);
            Vfx.dot(head, Particle.ANGRY_VILLAGER, null, false);
            Fx.sound(head, Sound.ENTITY_POLAR_BEAR_HURT, 0.7f, 0.7f + 0.15f * irritation);
            status();
            if (irritation >= wakeHits) {
                // Not inside the damage event that disturbed it: the roar deals damage of its own.
                ctx().runLater(owner, 1, () -> wake(true));
            }
        }

        /** Wakes the bear: the den bursts apart, it roars and flies into a rage. */
        void wake(boolean rude) {
            if (woke) {
                return;
            }
            woke = true;
            if (sleep == this) {
                sleep = null;
            }
            for (NamespacedKey key : holds) {
                ctx().removeModifier(owner, key);
            }
            holds.clear();
            standUp();
            scatterDen();
            if (!owner.isOnline() || !ctx().isAlive(owner)) {
                return;
            }
            roar(rude);
            rage();
        }

        /** Lets the bear's pose follow its own movement again. */
        void standUp() {
            if (owner.hasFixedPose()) {
                owner.setPose(Pose.STANDING, false);
            }
        }

        private void scatterDen() {
            Location feet = owner.getLocation();
            for (BlockDisplay piece : den) {
                if (!piece.isValid()) {
                    continue;
                }
                // Each tuft is thrown out the way it lay from the bear (the moss carpet just shrinks).
                Vector3f offset = piece.getTransformation().getTranslation();
                Vector out = new Vector(offset.x(), 0, offset.z());
                if (out.lengthSquared() > 0.04 && piece.getBlock().getMaterial() != DEN_MAT) {
                    piece.setTeleportDuration(5);
                    Displays.moveTo(piece, piece.getLocation().add(out.normalize().multiply(0.7)).add(0, 0.3, 0));
                }
                Displays.shrinkAway(ctx(), owner, piece, 6);
            }
            den.clear();
            feet.getWorld().spawnParticle(Particle.BLOCK, feet.clone().add(0, 0.3, 0), Vfx.n(ctx(), 14), 0.5, 0.2, 0.5,
                    0.1, Material.MOSS_BLOCK.createBlockData());
        }

        /** The roar: a ring of dust and a sonic burst; enemies close by are hurt and thrown back. */
        private void roar(boolean rude) {
            Location feet = owner.getLocation();
            Location chest = feet.clone().add(0, owner.getHeight() * 0.6, 0);
            double radius = Math.max(0, params().num("roar_radius"));
            double damage = params().num(rude ? "rude_roar_damage" : "roar_damage");
            int hits = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, chest, radius)) {
                Location body = Fx.body(enemy);
                if (!Spots.clearPath(chest, body)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(feet.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    away = Motion.horizontalFacing(feet);
                }
                Motion.push(enemy, away.multiply(ROAR_PUSH).setY(ROAR_LIFT), MAX_SPEED);
                if (!(enemy instanceof Player hidden && !owner.canSee(hidden))) {
                    Vfx.cloud(ctx(), body, Particle.DUST, 6, 0.3, 0, FUR_SMALL, false);
                }
            }
            World world = chest.getWorld();
            Vfx.dot(chest, Particle.SONIC_BOOM, null, true);
            Vfx.dot(chest, Particle.FLASH, rude ? Color.fromRGB(0xFF5A3C) : PALETTE.core(), true);
            world.spawnParticle(Particle.DUST, chest, Vfx.n(ctx(), 20), 0.6, 0.6, 0.6, 0, FUR, true);
            world.spawnParticle(Particle.BLOCK, feet.clone().add(0, 0.2, 0), Vfx.n(ctx(), 20), 1.0, 0.1, 1.0, 0.15,
                    floorAt(feet), true);
            Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.5, Math.max(1, radius), 6,
                    Particle.DUST_COLOR_TRANSITION, rude ? RAGE : HONEY, true);
            Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.3, 0), 0.4, Math.max(1, radius) * 0.8, 5,
                    Particle.CLOUD, null, false);
            Fx.sound(chest, Sound.ENTITY_POLAR_BEAR_WARNING, 1.6f, 0.6f);
            Fx.sound(chest, Sound.ENTITY_RAVAGER_ROAR, 1.2f, rude ? 0.7f : 0.9f);
            Fx.sound(chest, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.6f);
            String shout = rude ? "크아아앙!!" : "크아앙!";
            TextDisplay label = Displays.text(ctx(), owner, owner.getEyeLocation().add(0, 0.8, 0),
                    Text.mm("<b><" + (rude ? RAGE_TAG : HONEY_TAG) + ">" + shout + "</" + (rude ? RAGE_TAG : HONEY_TAG)
                            + "></b>"), 0.6f, ROAR_LABEL_TICKS);
            Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0.3f, 0, rude ? 1.5f : 1.2f,
                    rude ? 1.5f : 1.2f, rude ? 1.5f : 1.2f, 0, 0, 0), 4);
            ctx().runLater(owner, ROAR_LABEL_TICKS - 6, () -> {
                if (label.isValid()) {
                    Displays.shrinkAway(ctx(), owner, label, 5);
                }
            });
            owner.sendActionBar(Text.mm("<" + (rude ? RAGE_TAG : COLOR_TAG) + "><b>" + (rude ? "성난 기상!" : "기상!")
                    + "</b></" + (rude ? RAGE_TAG : COLOR_TAG) + "> <gray>포효</gray> <white>" + hits + "명</white>"
                    + " <dark_gray>|</dark_gray> <" + HONEY_TAG + ">회복 +" + AbilityInfo.formatNumber(
                    Math.round(healed * 10) / 10.0) + "</" + HONEY_TAG + ">"));
        }

        /** The rage: faster and stronger in melee for a while, with glowing eyes and steaming breath. */
        private void rage() {
            long rageTicks = params().ticks("rage_duration");
            if (rageTicks <= 0) {
                return;
            }
            rageUntil = ctx().currentTick() + rageTicks;
            double speed = Math.max(0, params().num("rage_speed")) / 100.0;
            if (speed > 0) {
                ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1, rageTicks);
            }
            long token = rageUntil;
            Loop.repeat(ctx(), owner, 2, 2, rageTicks / 2, run -> {
                if (rageUntil != token) {
                    return false;
                }
                Location feet = owner.getLocation().add(0, 0.08, 0);
                Vfx.ring(ctx(), feet, 0.6, 8, run * 0.5, Particle.DUST_COLOR_TRANSITION, RAGE, false);
                eyes();
                if (run % 5 == 0) {
                    Vfx.dot(owner.getEyeLocation().add(0, 0.55, 0), Particle.ANGRY_VILLAGER, null, false);
                }
                if (run % 4 == 2) {
                    Location mouth = owner.getEyeLocation().add(Motion.horizontalFacing(owner.getLocation())
                            .multiply(0.45)).subtract(0, 0.2, 0);
                    Vfx.cloud(ctx(), mouth, Particle.POOF, 1, 0.05, 0.01, null, false);
                }
                if (run % 3 == 0) {
                    long left = Math.max(0, rageUntil - ctx().currentTick());
                    owner.sendActionBar(Text.mm("<" + RAGE_TAG + "><b>분노</b></" + RAGE_TAG + "> <white>"
                            + Fx.seconds(left) + "초</white> <gray>근접 +" + AbilityInfo.formatNumber(
                            params().num("rage_melee")) + "%</gray>"));
                }
                return true;
            });
        }

        /** Two red sparks where the eyes are, shown to everyone but the bear (they would sit in its own view). */
        private void eyes() {
            Location eye = owner.getEyeLocation();
            Vector facing = Motion.horizontalFacing(eye);
            Vector side = new Vector(-facing.getZ(), 0, facing.getX()).multiply(0.13);
            Location front = eye.clone().add(facing.multiply(0.3)).add(0, 0.02, 0);
            for (Player viewer : eye.getWorld().getPlayers()) {
                if (viewer.equals(owner) || !viewer.canSee(owner)
                        || viewer.getLocation().distanceSquared(eye) > 48 * 48) {
                    continue;
                }
                for (int i = -1; i <= 1; i += 2) {
                    Location at = front.clone().add(side.clone().multiply(i));
                    viewer.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, RAGE_EYE);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The ground under {@code point}: the top of the first solid block at most {@code maxDrop} blocks below it, or
     * null. The probe starts a little above the point (a ray starting on a face reports a hit inside the block), and
     * there is no ground to find from a point inside a block (a wall in front of the bear): null too.
     */
    private static @Nullable Location groundUnder(Location point, double maxDrop) {
        Location from = point.clone().add(0, 0.05, 0);
        if (!Spots.isFree(point.getWorld(), from, PROBE_SIZE, PROBE_SIZE)) {
            return null;
        }
        org.bukkit.util.RayTraceResult ground = point.getWorld().rayTraceBlocks(from, new Vector(0, -1, 0),
                maxDrop + 0.05, org.bukkit.FluidCollisionMode.NEVER, true);
        if (ground == null) {
            return null;
        }
        Location at = point.clone();
        at.setY(ground.getHitPosition().getY());
        return at;
    }

    /** Minecraft's yaw (degrees: 0 = +Z, 90 = -X) of the horizontal part of {@code direction}, 0 if it has none. */
    private static float yawOf(Vector direction) {
        Vector flat = Motion.horizontal(direction);
        if (flat.lengthSquared() < 1.0E-6) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
    }

    private static BlockData floorAt(Location at) {
        Block below = at.clone().subtract(0, 0.2, 0).getBlock();
        return below.getType().isAir() ? Material.DIRT.createBlockData() : below.getBlockData();
    }
}
