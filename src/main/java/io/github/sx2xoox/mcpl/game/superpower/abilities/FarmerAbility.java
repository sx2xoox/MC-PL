package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 농부 — a farmer. Melee hits make crops sprout and heal the farmer more the more enemies crowd around, the active
 * reaps a wide half circle with a big scythe (the blade's tip cuts deepest) and leaves the victims bleeding, and the
 * ultimate tills a wheat field around the farmer: enemies wade through it slowly and are bound by the ripe ears,
 * the farmer's team heals in it, and pumpkins sprout and roll into enemies to burst.
 *
 * <p>Look: wheat gold and leaf green. Crops are real wheat blocks shown as display entities that grow through their
 * ages; the scythe (dark oak snath, curved iron blade) is turned as one body through its anchor's yaw so the swing
 * reads cleanly from any angle, trailing a golden crescent and chaff. The field has a scarecrow with a carved pumpkin
 * head in the middle and jack o'lanterns that roll (spinning about their axle) towards their prey.
 */
public final class FarmerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("farmer", "농부", Material.IRON_HOE)
            .color(TextColor.color(0xE3B341))
            .difficulty(2)
            .passive("풍작",
                    "근접 공격이 적중하면 그 자리에 작물이 자라고, 반경 {harvest_radius}블록 안의 적 한 명당 준 피해의 "
                            + "{harvest_steal}%를 회복합니다(최대 {harvest_cap}명).")
            .active("낫질",
                    "큰 낫으로 앞쪽 {sweep_angle}도를 반경 {sweep_radius}블록까지 휩쓸어 {sweep_damage} 피해를 주고 "
                            + "{bleed_duration}초간 {bleed_interval}초마다 {bleed_damage} 출혈 피해를 줍니다. 날 끝(바깥쪽 "
                            + "{sweet_band}블록)에 베인 적은 {sweet_bonus} 피해를 더 받습니다.",
                    8)
            .ultimate("대풍년",
                    "주변 반경 {ult_radius}블록에 {ult_duration}초간 밀밭을 일굽니다. 밭 안의 적은 {ult_slow}% 느려지고, "
                            + "{ult_grow}초 뒤 밀이 익는 순간 밭에 있던 적은 {ult_root}초간 이삭에 묶입니다. 나와 아군은 "
                            + "밭에서 초당 {ult_heal} 회복하며, {pumpkin_interval}초마다 호박이 적에게 굴러가 터져 반경 "
                            + "{pumpkin_radius}블록에 {pumpkin_damage} 피해를 줍니다.")
            .param("harvest_radius", 6)
            .param("harvest_steal", 10)
            .param("harvest_cap", 3)
            .param("sweep_radius", 4)
            .param("sweep_angle", 180)
            .param("sweep_damage", 3)
            .param("sweet_band", 1.5)
            .param("sweet_bonus", 1.5)
            .param("bleed_duration", 2.5)
            .param("bleed_interval", 0.5)
            .param("bleed_damage", 0.5)
            .param("ult_duration", 6)
            .param("ult_radius", 7)
            .param("ult_slow", 35)
            .param("ult_grow", 1)
            .param("ult_root", 1.5)
            .param("ult_heal", 1)
            .param("pumpkin_interval", 1.5)
            .param("pumpkin_damage", 3.5)
            .param("pumpkin_radius", 1.8)
            .build();

    private static final String COLOR_TAG = "#E3B341";

    // ------------------------------------------------------------------ look: pale straw, wheat gold, leaf green
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF4C2, 0xE3B341, 0x5E8C31);
    private static final Particle.DustTransition GOLD_GLOW = PALETTE.glow(1.1f);
    private static final Particle.DustTransition RIPEN = PALETTE.fade(1.0f);
    private static final Particle.DustOptions LEAF = new Particle.DustOptions(Color.fromRGB(0x7DB043), 0.9f);
    private static final Particle.DustOptions BLOOD = new Particle.DustOptions(Color.fromRGB(0x8E1B1B), 0.8f);
    private static final Particle.DustOptions PUMPKIN_DUST = new Particle.DustOptions(Color.fromRGB(0xE38A1D), 1.2f);
    private static final Material CROP = Material.WHEAT;
    private static final Material CHAFF = Material.HAY_BLOCK;
    private static final Material BLOOD_BLOCK = Material.REDSTONE_BLOCK;
    private static final Material SNATH = Material.STRIPPED_DARK_OAK_LOG;
    private static final Material BLADE = Material.IRON_BLOCK;
    private static final Material PUMPKIN = Material.JACK_O_LANTERN;
    private static final Material PUMPKIN_BURST = Material.PUMPKIN;
    private static final Material SOIL = Material.FARMLAND;

    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.0;

    // ------------------------------------------------------------------ passive: bountiful harvest
    private static final int SPROUT_TICKS = 16;
    private static final int[] SPROUT_AGES = {1, 3, 5, 7};

    // ------------------------------------------------------------------ active: scythe
    private static final long WINDUP_TICKS = 2;
    private static final long SWING_TICKS = 4;
    private static final double BLADE_HEIGHT = 0.85;
    /** Slack (degrees) so an enemy whose hitbox pokes into the arc is still reaped. */
    private static final double ARC_SLACK = 12;
    private static final double REAP_PUSH = 0.35;
    private static final double REAP_LIFT = 0.15;
    private static final float SNATH_WIDTH = 0.09f;
    private static final float BLADE_LENGTH = 1.25f;
    private static final float BLADE_TIP_LENGTH = 0.7f;
    private static final float BLADE_WIDTH = 0.26f;
    private static final float BLADE_THICKNESS = 0.04f;
    /** Degrees the blade's tip curves back towards the farmer. */
    private static final float BLADE_CURVE = 35f;

    // ------------------------------------------------------------------ ultimate: great harvest
    private static final int GROUND_SNAP = 4;
    private static final int CROPS = 9;
    private static final double CROP_SPREAD = 0.88;
    private static final int RISE_TICKS = 8;
    private static final long PUMPKIN_SPROUT_TICKS = 4;
    private static final long PUMPKIN_MAX_ROLL = 40;
    private static final double PUMPKIN_SPEED = 0.42;
    private static final float PUMPKIN_SIZE = 0.8f;
    private static final double PUMPKIN_REACH = 0.9;
    private static final double PUMPKIN_KNOCK_UP = 0.35;
    /** Pumpkins sprout at least this far from their target, so they visibly roll. */
    private static final double PUMPKIN_MIN_ROLL = 2.5;
    private static final int MAX_BOUND = 3;
    private static final int HARVEST_TICKS = 8;
    private static final double FIELD_HEIGHT = 2.5;

    private final Map<UUID, Bleed> bleeding = new HashMap<>();
    private boolean swinging;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        bleeding.clear();
        swinging = false;
    }

    // ------------------------------------------------------------------ passive: bountiful harvest

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        Player owner = player();
        if (ctx().isAbilityDamage() || !owner.equals(event.getDamager()) || event.getCause() != DamageCause.ENTITY_ATTACK
                || !ctx().isEnemy(owner, victim)) {
            return;
        }
        int cap = Math.max(0, params().integer("harvest_cap"));
        int crowd = Math.max(1, Math.min(cap, visibleEnemies(owner, owner.getLocation(),
                params().num("harvest_radius")).size()));
        double heal = event.getFinalDamage() * Math.max(0, params().num("harvest_steal")) / 100.0 * crowd;
        if (cap > 0 && heal > 0) {
            ctx().heal(owner, heal);
        }
        sprout(owner, victim, cap > 0 ? crowd : 0);
    }

    /** A wheat sprout shoots up at the victim's feet and ripens in a moment; golden motes fly to the farmer. */
    private void sprout(Player owner, LivingEntity victim, int crowd) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location feet = victim.getLocation().add(random.nextDouble(-0.35, 0.35), 0, random.nextDouble(-0.35, 0.35));
        feet.setYaw(0);
        feet.setPitch(0);
        float yaw = random.nextFloat() * 90f;
        BlockDisplay crop = Displays.block(ctx(), owner, feet, crop(0), cropPose(0.35f, yaw), SPROUT_TICKS + 4);
        Displays.animateNextTick(ctx(), owner, crop, cropPose(0.9f, yaw), 6);
        for (int i = 0; i < SPROUT_AGES.length; i++) {
            int age = SPROUT_AGES[i];
            ctx().runLater(owner, 2L + i * 2L, () -> {
                if (crop.isValid()) {
                    crop.setBlock(crop(age));
                }
            });
        }
        ctx().runLater(owner, SPROUT_TICKS - 5, () -> {
            if (crop.isValid()) {
                Displays.shrinkAway(ctx(), owner, crop, 5);
            }
        });
        Location ground = feet.clone().add(0, 0.15, 0);
        Vfx.cloud(ctx(), ground, Particle.HAPPY_VILLAGER, 5, 0.3, 0, null, false);
        Vfx.cloud(ctx(), ground, Particle.COMPOSTER, 4, 0.25, 0, null, false);
        Fx.sound(ground, Sound.ITEM_BONE_MEAL_USE, 0.6f, 1.2f + Fx.jitter(0.15f));
        if (crowd <= 0) {
            return;
        }
        Location body = Fx.body(victim);
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        if (body.getWorld().equals(chest.getWorld())) {
            for (int i = 0; i < crowd; i++) {
                Vfx.trail(Vfx.scatter(body, 0.3), chest, i % 2 == 0 ? PALETTE.body() : PALETTE.core(), 10 + i * 2,
                        false);
            }
        }
        if (crowd >= 2) {
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">풍작</" + COLOR_TAG + "> <white>×" + crowd + "</white>"));
            Fx.sound(chest, Sound.BLOCK_COMPOSTER_FILL_SUCCESS, 0.5f, 1.0f + 0.15f * crowd);
        }
    }

    // ------------------------------------------------------------------ active: scythe

    @Override
    public ActivationResult onActive() {
        if (swinging) {
            return ActivationResult.fail("이미 낫을 휘두르는 중입니다");
        }
        swinging = true;
        new Sweep(player()).start();
        return ActivationResult.success();
    }

    /**
     * The scythe sweep: a short wind-up with the blade raised at the farmer's right, then the blade sweeps across the
     * front, reaping every enemy whose bearing it passes this tick (right to left), with bonus damage at the tip.
     */
    private final class Sweep implements LongPredicate {

        private final Player owner;
        private final float facing;
        private final double radius;
        private final double half;
        private final double damage;
        private final double sweetBand;
        private final double sweetBonus;
        private final Set<UUID> reaped = new HashSet<>();
        private final List<BlockDisplay> scythe = new ArrayList<>();
        private int struck;
        private int sweet;

        Sweep(Player owner) {
            this.owner = owner;
            this.facing = owner.getLocation().getYaw();
            this.radius = Math.max(1, params().num("sweep_radius"));
            this.half = Math.clamp(params().num("sweep_angle"), 10, 360) / 2;
            this.damage = Math.max(0, params().num("sweep_damage"));
            this.sweetBand = Math.max(0, params().num("sweet_band"));
            this.sweetBonus = Math.max(0, params().num("sweet_bonus"));
        }

        void start() {
            Location anchor = anchor(bladeYaw(0));
            long lifetime = WINDUP_TICKS + SWING_TICKS + 12;
            float reach = (float) radius;
            float snathStart = 0.45f;
            float snathLength = Math.max(0.5f, reach - 0.3f - snathStart);
            scythe.add(Displays.block(ctx(), owner, anchor, SNATH.createBlockData(),
                    Displays.transform(0, 0, snathStart + snathLength / 2, SNATH_WIDTH, SNATH_WIDTH, snathLength,
                            0, 0, 0), lifetime));
            // The blade leaves the end of the snath sideways (towards the swing), curving back to the farmer.
            float bladeZ = reach - 0.25f;
            scythe.add(Displays.block(ctx(), owner, anchor, BLADE.createBlockData(),
                    Displays.transform(BLADE_LENGTH / 2, 0, bladeZ, BLADE_LENGTH, BLADE_THICKNESS, BLADE_WIDTH,
                            0, 0, 0), lifetime));
            double tipAngle = Math.toRadians(BLADE_CURVE);
            float tipX = BLADE_LENGTH + (float) (Math.cos(tipAngle) * BLADE_TIP_LENGTH / 2);
            float tipZ = bladeZ - (float) (Math.sin(tipAngle) * BLADE_TIP_LENGTH / 2);
            scythe.add(Displays.block(ctx(), owner, anchor, BLADE.createBlockData(),
                    Displays.transform(tipX, 0, tipZ, BLADE_TIP_LENGTH, BLADE_THICKNESS, BLADE_WIDTH * 0.75f,
                            -BLADE_CURVE, 0, 0), lifetime));
            for (BlockDisplay part : scythe) {
                part.setTeleportDuration(1);
            }
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
            Fx.sound(chest, Sound.ITEM_HOE_TILL, 0.9f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(chest, Sound.ITEM_ARMOR_EQUIP_IRON, 0.7f, 1.3f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), owner, 1, 1, WINDUP_TICKS + SWING_TICKS, this, this::finish);
        }

        @Override
        public boolean test(long tick) {
            if (tick < WINDUP_TICKS) {
                // Wind-up: the blade glints at the farmer's right.
                pose(bladeYaw(0));
                Location tip = anchor(bladeYaw(0)).add(direction(bladeYaw(0)).multiply(radius));
                Vfx.dot(tip, Particle.END_ROD, null, false);
                Vfx.cloud(ctx(), tip, Particle.WAX_OFF, 2, 0.15, 0, null, false);
                return true;
            }
            long k = tick - WINDUP_TICKS + 1;
            double from = bladeYaw(k - 1);
            double to = bladeYaw(k);
            pose(to);
            trail(from, to);
            if (k == 1) {
                Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.65f + Fx.jitter(0.05f));
                Fx.sound(owner.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.5f, 1.6f + Fx.jitter(0.1f));
            }
            reap(k);
            return true;
        }

        /** The blade's yaw after {@code k} of the swing's ticks: from the right end of the arc to the left. */
        private double bladeYaw(long k) {
            return facing + half - 2 * half * Math.min(1.0, (double) k / SWING_TICKS);
        }

        private Location anchor(double yaw) {
            Location at = owner.getLocation().add(0, BLADE_HEIGHT, 0);
            at.setYaw((float) yaw);
            at.setPitch(0);
            return at;
        }

        private void pose(double yaw) {
            Location at = anchor(yaw);
            for (BlockDisplay part : scythe) {
                if (part.isValid()) {
                    part.teleport(at);
                }
            }
        }

        /** The golden crescent left by the blade over this tick's slice, with flying chaff. */
        private void trail(double from, double to) {
            Location center = owner.getLocation().add(0, BLADE_HEIGHT, 0);
            double middleYaw = (from + to) / 2;
            double width = Math.abs(from - to);
            Vfx.arc(ctx(), center, radius, middleYaw, width, 8, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
            Vfx.arc(ctx(), center, radius - 0.6, middleYaw, width, 6, Particle.DUST_COLOR_TRANSITION, RIPEN, false);
            Location mid = center.clone().add(direction(middleYaw).multiply(radius * 0.6));
            Vfx.dot(mid, Particle.SWEEP_ATTACK, null, false);
            Location tip = center.clone().add(direction(middleYaw).multiply(radius));
            tip.getWorld().spawnParticle(Particle.FALLING_DUST, tip, Vfx.n(ctx(), 3), 0.3, 0.2, 0.3, 0,
                    CHAFF.createBlockData());
            tip.getWorld().spawnParticle(Particle.ITEM, tip, Vfx.n(ctx(), 2), 0.2, 0.1, 0.2, 0.08,
                    new ItemStack(CROP));
        }

        /** Hits every enemy the blade passed during swing tick {@code k}. */
        private void reap(long k) {
            Location feet = owner.getLocation();
            Location chest = feet.clone().add(0, owner.getHeight() * 0.6, 0);
            double sliceStart = half - 2 * half * (double) k / SWING_TICKS - ARC_SLACK;
            for (LivingEntity enemy : ctx().enemiesNear(owner, chest, radius + 1)) {
                if (reaped.contains(enemy.getUniqueId())) {
                    continue;
                }
                Vector offset = enemy.getLocation().toVector().subtract(feet.toVector());
                double distance = Math.max(0, Math.hypot(offset.getX(), offset.getZ()) - enemy.getWidth() / 2);
                if (distance > radius || offset.getY() > owner.getHeight() + 0.5
                        || offset.getY() < -enemy.getHeight() - 0.5) {
                    continue;
                }
                double bearing = Math.hypot(offset.getX(), offset.getZ()) < 0.3 ? 0 : wrap(yawOf(offset) - facing);
                if (Math.abs(bearing) > half + ARC_SLACK || bearing < sliceStart) {
                    continue;
                }
                if (!Spots.clearPath(chest, Fx.body(enemy))) {
                    continue;
                }
                reaped.add(enemy.getUniqueId());
                boolean tip = sweetBand > 0 && distance >= radius - sweetBand;
                Combat.hit(ctx(), owner, enemy, damage + (tip ? sweetBonus : 0));
                struck++;
                if (tip) {
                    sweet++;
                }
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                // The blade drags the victim a little along the swing (to the farmer's left).
                Vector swing = direction(wrap(yawOf(offset)) - 90).setY(0);
                Vector away = Motion.horizontal(offset);
                Motion.push(enemy, swing.multiply(REAP_PUSH).add(away.multiply(REAP_PUSH * 0.5)).setY(REAP_LIFT),
                        MAX_SPEED);
                bleed(enemy);
                impact(enemy, tip);
            }
        }

        private void impact(LivingEntity enemy, boolean tip) {
            Location body = Fx.body(enemy);
            World world = body.getWorld();
            world.spawnParticle(Particle.SWEEP_ATTACK, body, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 8), 0.25, 0.3, 0.25, 0, BLOOD);
            world.spawnParticle(Particle.CRIT, body, Vfx.n(ctx(), 6), 0.25, 0.3, 0.25, 0.2);
            Fx.sound(body, Sound.BLOCK_CROP_BREAK, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 0.6f, 1.2f + Fx.jitter(0.1f));
            if (tip) {
                world.spawnParticle(Particle.FLASH, body, 1, 0, 0, 0, 0, PALETTE.core());
                Vfx.cloud(ctx(), body, Particle.ENCHANTED_HIT, 10, 0.3, 0.2, null, false);
                Vfx.ring(ctx(), body, 0.6, 12, 0, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.0f + Fx.jitter(0.1f));
            }
        }

        private void finish() {
            swinging = false;
            for (BlockDisplay part : scythe) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 4);
                }
            }
            scythe.clear();
            Location center = owner.getLocation().add(0, BLADE_HEIGHT, 0);
            // Afterglow: chaff drifting down along the whole cut.
            Loop.repeat(ctx(), owner, 0, 2, 6, run -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                double yaw = facing + half - 2 * half * random.nextDouble();
                Location at = center.clone().add(direction(yaw).multiply(radius * random.nextDouble(0.5, 1.0)));
                at.getWorld().spawnParticle(Particle.FALLING_DUST, at.add(0, 0.6, 0), Vfx.n(ctx(), 2), 0.4, 0.2, 0.4,
                        0, CHAFF.createBlockData());
                return true;
            });
            if (struck > 0) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">낫질</" + COLOR_TAG + "> <white>" + struck
                        + "명</white>" + (sweet > 0 ? " <gray>· 날 끝 <white>" + sweet + "</white></gray>" : "")));
            }
        }
    }

    // ------------------------------------------------------------------ bleeding

    /** One enemy's bleeding: ticks on its own and is refreshed (not stacked) by new cuts. */
    private final class Bleed {

        private final LivingEntity target;
        private long until;

        Bleed(LivingEntity target, long until) {
            this.target = target;
            this.until = until;
        }

        void start(long interval) {
            Player owner = player();
            long maxRuns = Math.max(1, (until - ctx().currentTick()) / interval + 2) * 4;
            Loop.repeat(ctx(), owner, interval, interval, maxRuns, run -> {
                if (ctx().currentTick() > until || !Combat.isTargetable(ctx(), owner, target)) {
                    return false;
                }
                Combat.hit(ctx(), owner, target, Math.max(0, params().num("bleed_damage")));
                Location body = Fx.body(target);
                body.getWorld().spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 4), 0.2, 0.3, 0.2, 0,
                        BLOOD_BLOCK.createBlockData());
                body.getWorld().spawnParticle(Particle.DUST, body.subtract(0, 0.3, 0), Vfx.n(ctx(), 3), 0.15, 0.1,
                        0.15, 0, BLOOD);
                return true;
            }, () -> bleeding.remove(target.getUniqueId(), this));
        }
    }

    private void bleed(LivingEntity enemy) {
        long duration = params().ticks("bleed_duration");
        long interval = Math.max(1, params().ticks("bleed_interval"));
        if (duration <= 0 || params().num("bleed_damage") <= 0) {
            return;
        }
        long until = ctx().currentTick() + duration;
        Bleed current = bleeding.get(enemy.getUniqueId());
        if (current != null) {
            current.until = Math.max(current.until, until);
            return;
        }
        Bleed created = new Bleed(enemy, until);
        bleeding.put(enemy.getUniqueId(), created);
        created.start(interval);
    }

    // ------------------------------------------------------------------ ultimate: great harvest

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location feet = owner.getLocation();
        Double ground = groundBelow(feet);
        if (ground == null) {
            return ActivationResult.fail("밭을 일굴 땅이 없습니다");
        }
        Location center = feet.clone();
        center.setY(ground);
        center.setPitch(0);
        new Field(owner, center).till();
        return ActivationResult.success();
    }

    /** One crop of the field: its display, its own size, and the tick it starts to grow. */
    private record Crop(BlockDisplay display, float size, float yaw, long delay) {
    }

    /**
     * The great harvest: a wheat field with a scarecrow that slows enemies in it, binds them when it ripens, heals the
     * farmer's team and sends rolling pumpkins at enemies.
     */
    private final class Field implements LongPredicate {

        private final Player owner;
        private final Location center;
        private final World world;
        private final double radius;
        private final long duration;
        private final long grow;
        private final double slow;
        private final long root;
        private final double heal;
        private final long pumpkinInterval;
        private final List<Crop> crops = new ArrayList<>();
        private final List<BlockDisplay> scarecrow = new ArrayList<>();
        private final List<Pumpkin> pumpkins = new ArrayList<>();
        private final Map<UUID, NamespacedKey> slowed = new HashMap<>();
        private long nextPumpkin;

        Field(Player owner, Location center) {
            this.owner = owner;
            this.center = center;
            this.world = center.getWorld();
            this.radius = Math.max(1, params().num("ult_radius"));
            this.duration = Math.max(20, params().ticks("ult_duration"));
            this.grow = Math.clamp(params().ticks("ult_grow"), 4, duration - 1);
            this.slow = Math.clamp(params().num("ult_slow") / 100.0, 0.0, 1.0);
            this.root = params().ticks("ult_root");
            this.heal = Math.max(0, params().num("ult_heal"));
            this.pumpkinInterval = Math.max(5, params().ticks("pumpkin_interval"));
            this.nextPumpkin = grow;
        }

        void till() {
            long lifetime = duration + HARVEST_TICKS + PUMPKIN_MAX_ROLL + 20;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double golden = Math.PI * (3 - Math.sqrt(5));
            double turn = random.nextDouble(Math.PI * 2);
            for (int i = 0; i < CROPS; i++) {
                // Even spread over the disc (sunflower pattern), skipping the scarecrow's spot.
                double r = Math.sqrt((i + 0.8) / CROPS) * radius * CROP_SPREAD;
                double angle = turn + i * golden;
                Location at = center.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
                Double top = groundBelow(at.clone().add(0, 1.5, 0));
                if (top != null) {
                    at.setY(top);
                }
                at.setYaw(0);
                at.setPitch(0);
                float size = 1.05f + random.nextFloat() * 0.35f;
                float yaw = random.nextFloat() * 90f;
                BlockDisplay display = Displays.block(ctx(), owner, at, crop(0), cropPose(0.2f, yaw), lifetime);
                display.setViewRange(2.0f);
                crops.add(new Crop(display, size, yaw, random.nextLong(5)));
            }
            raiseScarecrow(lifetime);

            Location floor = center.clone().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, floor, 0.4, radius, 8, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, true);
            Vfx.shockwave(ctx(), owner, floor.clone().add(0, 0.05, 0), 0.3, radius * 0.9, 10, Particle.DUST, LEAF,
                    true);
            world.spawnParticle(Particle.BLOCK, floor, Vfx.n(ctx(), 60), radius * 0.45, 0.05, radius * 0.45, 0.1,
                    SOIL.createBlockData(), true);
            world.spawnParticle(Particle.FLASH, floor.clone().add(0, 1, 0), 1, 0, 0, 0, 0, PALETTE.core(), true);
            Fx.sound(floor, Sound.ITEM_HOE_TILL, 1.4f, 0.7f);
            Fx.sound(floor, Sound.ITEM_HOE_TILL, 1.2f, 1.0f);
            Fx.sound(floor, Sound.BLOCK_ROOTED_DIRT_PLACE, 1.2f, 0.8f);
            Fx.sound(floor, Sound.ITEM_BONE_MEAL_USE, 1.2f, 0.7f);
            Fx.sound(floor, Sound.ENTITY_VILLAGER_WORK_FARMER, 1.0f, 0.9f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>대풍년</b></" + COLOR_TAG + "> <gray>"
                    + AbilityInfo.formatNumber(duration / 20.0) + "초</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, lifetime, this, this::finish);
        }

        /** The scarecrow: a fence post with a crossbar, a hay body and a carved pumpkin head, rising from the soil. */
        private void raiseScarecrow(long lifetime) {
            Location anchor = center.clone();
            anchor.setYaw(owner.getLocation().getYaw());
            anchor.setPitch(0);
            BlockData head = Material.CARVED_PUMPKIN.createBlockData();
            if (head instanceof Directional directional) {
                directional.setFacing(BlockFace.SOUTH);
            }
            Transformation[] poses = {
                    Displays.transform(0, 1.0f, 0, 1.0f, 2.0f, 1.0f, 0, 0, 0),
                    Displays.transform(0, 1.45f, 0, 1.4f, 0.12f, 0.12f, 0, 0, 0),
                    Displays.transform(0, 1.2f, 0, 0.55f, 0.65f, 0.35f, 0, 0, 0),
                    Displays.transform(0, 1.86f, 0, 0.56f, 0.56f, 0.56f, 0, 0, 0)};
            BlockData[] blocks = {Material.OAK_FENCE.createBlockData(), Material.OAK_PLANKS.createBlockData(),
                    CHAFF.createBlockData(), head};
            for (int i = 0; i < poses.length; i++) {
                BlockDisplay part = Displays.block(ctx(), owner, anchor, blocks[i], Displays.scaled(0.01f), lifetime);
                part.setViewRange(2.0f);
                scarecrow.add(part);
                Transformation pose = poses[i];
                ctx().runLater(owner, 1L + i, () -> Displays.animate(part, pose, RISE_TICKS));
            }
        }

        @Override
        public boolean test(long tick) {
            if (tick < duration) {
                growCrops(tick);
                if (tick == grow) {
                    ripen();
                }
                updateSlows();
                if (tick % 4 == 0) {
                    drawBorder(tick);
                }
                if (tick % 20 == 10) {
                    feed();
                }
                if (tick >= nextPumpkin) {
                    nextPumpkin = tick + pumpkinInterval;
                    sproutPumpkin();
                }
                if (tick % 40 == 20) {
                    swayScarecrow(tick);
                }
            } else if (tick == duration) {
                harvest();
            }
            Iterator<Pumpkin> it = pumpkins.iterator();
            while (it.hasNext()) {
                if (it.next().update()) {
                    it.remove();
                }
            }
            return tick < duration + HARVEST_TICKS || !pumpkins.isEmpty();
        }

        /** Each crop grows through the wheat ages until the field is ripe. */
        private void growCrops(long tick) {
            if (tick > grow) {
                return;
            }
            for (Crop crop : crops) {
                BlockDisplay display = crop.display();
                if (!display.isValid() || tick < crop.delay()) {
                    continue;
                }
                long span = Math.max(1, grow - crop.delay());
                double progress = Math.min(1.0, (double) (tick - crop.delay()) / span);
                int age = (int) Math.round(progress * 7);
                if (tick % 2 == 0 || tick == grow) {
                    display.setBlock(crop(age));
                    Displays.animate(display, cropPose((float) (0.2 + (crop.size() - 0.2) * progress), crop.yaw()),
                            2);
                }
                if ((tick + crop.delay()) % 6 == 0) {
                    Vfx.dot(display.getLocation().add(0, 0.4, 0), Particle.HAPPY_VILLAGER, null, false);
                }
            }
        }

        /** The field is ripe: a golden flash, and every enemy in it is bound by the ears of wheat. */
        private void ripen() {
            for (Crop crop : crops) {
                if (crop.display().isValid()) {
                    Location at = crop.display().getLocation().add(0, 0.8, 0);
                    Vfx.cloud(ctx(), at, Particle.WAX_ON, 3, 0.3, 0.05, null, false);
                    Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, true);
                }
            }
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.6, 0), 0.5, radius, 6, Particle.DUST_COLOR_TRANSITION,
                    GOLD_GLOW, true);
            Fx.sound(center, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            Fx.sound(center, Sound.BLOCK_COMPOSTER_READY, 1.2f, 0.9f);
            Fx.sound(center, Sound.BLOCK_GRASS_BREAK, 1.2f, 0.7f);
            if (root <= 0) {
                return;
            }
            int bound = 0;
            for (LivingEntity enemy : enemiesInField()) {
                ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, root);
                ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, root);
                if (visible(owner, enemy) && bound++ < MAX_BOUND) {
                    bind(enemy);
                }
            }
        }

        /** Two tall ripe wheat stalks crossed around the enemy's legs for the length of the bind. */
        private void bind(LivingEntity enemy) {
            Location feet = enemy.getLocation();
            feet.setYaw(0);
            feet.setPitch(0);
            List<BlockDisplay> stalks = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                float yaw = 45f * i + 20f;
                BlockDisplay stalk = Displays.block(ctx(), owner, feet, crop(7), cropPose(0.3f, yaw), root + 8);
                stalk.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, stalk, cropPose(1.35f, yaw), 4);
                stalks.add(stalk);
            }
            Loop.repeat(ctx(), owner, 1, 1, root, run -> {
                if (!enemy.isValid() || enemy.isDead()) {
                    return false;
                }
                Location at = enemy.getLocation();
                at.setYaw(0);
                at.setPitch(0);
                for (BlockDisplay stalk : stalks) {
                    if (stalk.isValid()) {
                        stalk.teleport(at);
                    }
                }
                if (run % 4 == 0) {
                    Vfx.ring(ctx(), at.clone().add(0, 0.3 + (run % 8) * 0.1, 0), 0.45, 8, run * 0.4, Particle.DUST,
                            LEAF, false);
                }
                return true;
            }, () -> {
                for (BlockDisplay stalk : stalks) {
                    if (stalk.isValid()) {
                        Displays.shrinkAway(ctx(), owner, stalk, 4);
                    }
                }
            });
            Location body = Fx.body(enemy);
            Vfx.cloud(ctx(), body, Particle.ITEM, 8, 0.3, 0.1, new ItemStack(CROP), false);
            Fx.sound(body, Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, 1.0f, 0.7f + Fx.jitter(0.1f));
        }

        /** Enemies wading through the field are slowed; leaving it ends the slow at once. */
        private void updateSlows() {
            if (slow <= 0) {
                return;
            }
            Set<UUID> inside = new HashSet<>();
            for (LivingEntity enemy : enemiesInField()) {
                inside.add(enemy.getUniqueId());
                if (!slowed.containsKey(enemy.getUniqueId())) {
                    slowed.put(enemy.getUniqueId(), ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow,
                            Operation.MULTIPLY_SCALAR_1, 0));
                }
            }
            Iterator<Map.Entry<UUID, NamespacedKey>> it = slowed.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, NamespacedKey> entry = it.next();
                if (!inside.contains(entry.getKey())) {
                    if (world.getEntity(entry.getKey()) instanceof LivingEntity enemy) {
                        ctx().removeModifier(enemy, entry.getValue());
                    }
                    it.remove();
                }
            }
        }

        private void clearSlows() {
            for (Map.Entry<UUID, NamespacedKey> entry : slowed.entrySet()) {
                if (world.getEntity(entry.getKey()) instanceof LivingEntity enemy) {
                    ctx().removeModifier(enemy, entry.getValue());
                }
            }
            slowed.clear();
        }

        private List<LivingEntity> enemiesInField() {
            List<LivingEntity> inside = new ArrayList<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius + FIELD_HEIGHT)) {
                Location at = enemy.getLocation();
                double horizontal = Math.hypot(at.getX() - center.getX(), at.getZ() - center.getZ())
                        - enemy.getWidth() / 2;
                double dy = at.getY() - center.getY();
                if (horizontal <= radius && dy > -FIELD_HEIGHT && dy < FIELD_HEIGHT) {
                    inside.add(enemy);
                }
            }
            return inside;
        }

        /** The farmer and allies standing in the field heal. */
        private void feed() {
            if (heal <= 0) {
                return;
            }
            List<Player> fed = new ArrayList<>();
            if (inField(owner)) {
                fed.add(owner);
            }
            for (Player ally : ctx().alliesNear(owner, center, radius + FIELD_HEIGHT)) {
                if (inField(ally)) {
                    fed.add(ally);
                }
            }
            for (Player player : fed) {
                ctx().heal(player, heal);
                Location feet = player.getLocation().add(0, 0.1, 0);
                Vfx.ring(ctx(), feet, 0.5, 10, 0, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
                for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                    Location from = Vfx.scatter(feet, 0.6);
                    Vfx.trail(from, from.clone().add(0, player.getHeight() + 0.3, 0), i % 2 == 0 ? PALETTE.body()
                            : PALETTE.core(), 14, false);
                }
                Vfx.cloud(ctx(), feet, Particle.HAPPY_VILLAGER, 3, 0.35, 0, null, false);
            }
        }

        private boolean inField(Player player) {
            Location at = player.getLocation();
            double dy = at.getY() - center.getY();
            return player.getWorld().equals(world) && Math.hypot(at.getX() - center.getX(),
                    at.getZ() - center.getZ()) <= radius && dy > -FIELD_HEIGHT && dy < FIELD_HEIGHT;
        }

        /** The edge of the field: a slowly turning ring of ripe gold over a green one. */
        private void drawBorder(long tick) {
            Location floor = center.clone().add(0, 0.15, 0);
            int points = (int) Math.min(48, Math.max(16, radius * 5));
            Vfx.ring(ctx(), floor, radius, points, tick * 0.01, Particle.DUST_COLOR_TRANSITION, RIPEN, false);
            if (tick % 8 == 0) {
                Vfx.ring(ctx(), floor.clone().add(0, 0.25, 0), radius - 0.3, points / 2, -tick * 0.02, Particle.DUST,
                        LEAF, false);
            }
        }

        /** A pumpkin sprouts from the field and starts rolling at the nearest visible enemy in it. */
        private void sproutPumpkin() {
            LivingEntity target = null;
            for (LivingEntity enemy : enemiesInField()) {
                if (visible(owner, enemy)) {
                    target = enemy;
                    break;
                }
            }
            if (target == null) {
                return;
            }
            Location at = pumpkinSpot(target);
            pumpkins.add(new Pumpkin(at, target));
        }

        /** A crop at least {@link #PUMPKIN_MIN_ROLL} from the target with a clear way to it, else the target's side. */
        private Location pumpkinSpot(LivingEntity target) {
            Location goal = target.getLocation();
            List<Location> options = new ArrayList<>();
            for (Crop crop : crops) {
                Location at = crop.display().getLocation();
                double distance = Math.hypot(at.getX() - goal.getX(), at.getZ() - goal.getZ());
                if (distance >= PUMPKIN_MIN_ROLL && distance <= radius
                        && Spots.clearPath(at.clone().add(0, 0.5, 0), goal.clone().add(0, 0.5, 0))) {
                    options.add(at);
                }
            }
            if (!options.isEmpty()) {
                return options.get(ThreadLocalRandom.current().nextInt(options.size())).clone();
            }
            Location spot = center.clone();
            spot.setY(goal.getY());
            return spot;
        }

        private void swayScarecrow(long tick) {
            Location anchor = center.clone();
            anchor.setYaw(owner.getLocation().getYaw());
            anchor.setPitch((tick / 40) % 2 == 0 ? 3f : -3f);
            for (BlockDisplay part : scarecrow) {
                if (part.isValid()) {
                    part.setTeleportDuration(20);
                    part.teleport(anchor);
                }
            }
        }

        /** The end: the field is reaped; crops sink with a burst of grain, the scarecrow folds away. */
        private void harvest() {
            clearSlows();
            for (Crop crop : crops) {
                BlockDisplay display = crop.display();
                if (display.isValid()) {
                    Location at = display.getLocation().add(0, 0.5, 0);
                    world.spawnParticle(Particle.ITEM, at, Vfx.n(ctx(), 3), 0.2, 0.2, 0.2, 0.12, new ItemStack(CROP));
                    Displays.shrinkAway(ctx(), owner, display, HARVEST_TICKS);
                }
            }
            for (BlockDisplay part : scarecrow) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, HARVEST_TICKS);
                }
            }
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.2, 0), radius, 0.5, 6, Particle.DUST_COLOR_TRANSITION,
                    GOLD_GLOW, true);
            Fx.sound(center, Sound.BLOCK_CROP_BREAK, 1.2f, 0.8f);
            Fx.sound(center, Sound.ENTITY_VILLAGER_CELEBRATE, 1.0f, 1.0f);
            Fx.sound(center, Sound.BLOCK_COMPOSTER_FILL_SUCCESS, 1.0f, 1.2f);
        }

        /** The loop ended: nothing is left over. */
        private void finish() {
            clearSlows();
            for (Crop crop : crops) {
                if (crop.display().isValid()) {
                    crop.display().remove();
                }
            }
            crops.clear();
            for (BlockDisplay part : scarecrow) {
                if (part.isValid()) {
                    part.remove();
                }
            }
            scarecrow.clear();
            for (Pumpkin pumpkin : pumpkins) {
                pumpkin.remove();
            }
            pumpkins.clear();
        }

        /** A jack o'lantern that sprouts, rolls (spinning about its axle) at its target and bursts. */
        private final class Pumpkin {

            private final BlockDisplay display;
            private final LivingEntity target;
            private final Location pos;
            private long age;
            private double spin;
            private float yaw;

            Pumpkin(Location at, LivingEntity target) {
                this.target = target;
                this.pos = at.clone();
                this.pos.setYaw(0);
                this.pos.setPitch(0);
                this.yaw = yawOf(target.getLocation().toVector().subtract(at.toVector()));
                BlockData data = PUMPKIN.createBlockData();
                if (data instanceof Directional directional) {
                    directional.setFacing(BlockFace.SOUTH);
                }
                this.display = Displays.block(ctx(), owner, pos, data, pumpkinPose(0.05f, yaw, 0),
                        PUMPKIN_SPROUT_TICKS + PUMPKIN_MAX_ROLL + 10);
                display.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, display, pumpkinPose(PUMPKIN_SIZE, yaw, 0),
                        (int) PUMPKIN_SPROUT_TICKS);
                Location ground = pos.clone().add(0, 0.2, 0);
                Vfx.cloud(ctx(), ground, Particle.HAPPY_VILLAGER, 6, 0.3, 0, null, false);
                Vfx.cloud(ctx(), ground, Particle.BLOCK, 8, 0.3, 0.05, SOIL.createBlockData(), false);
                Fx.sound(ground, Sound.ITEM_CROP_PLANT, 1.0f, 0.8f + Fx.jitter(0.1f));
                Fx.sound(ground, Sound.BLOCK_WOOD_PLACE, 0.8f, 0.7f + Fx.jitter(0.1f));
            }

            /** One tick; true once the pumpkin has burst (or vanished). */
            boolean update() {
                age++;
                if (!display.isValid()) {
                    return true;
                }
                if (age <= PUMPKIN_SPROUT_TICKS) {
                    return false;
                }
                if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(world)) {
                    burst();
                    return true;
                }
                Location goal = target.getLocation();
                Vector toward = goal.toVector().subtract(pos.toVector()).setY(0);
                double distance = toward.length();
                if (distance <= PUMPKIN_REACH + target.getWidth() / 2 || age > PUMPKIN_SPROUT_TICKS + PUMPKIN_MAX_ROLL) {
                    burst();
                    return true;
                }
                Vector step = toward.multiply(Math.min(PUMPKIN_SPEED, distance) / distance);
                Location next = pos.clone().add(step);
                Double top = groundBelow(next.clone().add(0, 1.0, 0));
                if (top == null || Math.abs(top - pos.getY()) > 1.1
                        || !next.clone().add(0, PUMPKIN_SIZE * 0.5 + 0.1, 0).getBlock().isPassable()) {
                    burst();
                    return true;
                }
                next.setY(top);
                pos.setX(next.getX());
                pos.setY(next.getY());
                pos.setZ(next.getZ());
                yaw = yawOf(step);
                spin += Math.toDegrees(step.length() / (PUMPKIN_SIZE * 0.5));
                display.teleport(pos);
                if (age % 2 == 0) {
                    Displays.animate(display, pumpkinPose(PUMPKIN_SIZE, yaw, (float) spin), 2);
                    Vfx.dot(pos.clone().add(0, 0.1, 0), Particle.DUST, PUMPKIN_DUST, false);
                }
                if (age % 6 == 0) {
                    Fx.sound(pos, Sound.BLOCK_WOOD_STEP, 0.7f, 0.6f + Fx.jitter(0.1f));
                    world.spawnParticle(Particle.BLOCK, pos.clone().add(0, 0.1, 0), Vfx.n(ctx(), 3), 0.2, 0.02, 0.2,
                            0, SOIL.createBlockData());
                }
                return false;
            }

            /** The pumpkin bursts: damage around it, a pumpkin-orange flash and a spray of flesh and seeds. */
            private void burst() {
                Location at = pos.clone().add(0, PUMPKIN_SIZE * 0.5, 0);
                display.remove();
                double damage = Math.max(0, params().num("pumpkin_damage"));
                double reach = Math.max(0.5, params().num("pumpkin_radius"));
                for (LivingEntity enemy : ctx().enemiesNear(owner, at, reach)) {
                    Combat.hit(ctx(), owner, enemy, damage);
                    if (Combat.isTargetable(ctx(), owner, enemy)) {
                        Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(at.toVector()));
                        Motion.push(enemy, away.multiply(0.3).setY(PUMPKIN_KNOCK_UP), MAX_SPEED);
                    }
                }
                world.spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, Color.fromRGB(0xFFB347));
                world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 30), 0.35, 0.35, 0.35, 0.2,
                        PUMPKIN_BURST.createBlockData());
                world.spawnParticle(Particle.ITEM, at, Vfx.n(ctx(), 10), 0.2, 0.2, 0.2, 0.18,
                        new ItemStack(Material.PUMPKIN_SEEDS));
                world.spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 12), 0.5, 0.4, 0.5, 0, PUMPKIN_DUST);
                Vfx.shockwave(ctx(), owner, pos.clone().add(0, 0.1, 0), 0.3, reach, 4, Particle.DUST_COLOR_TRANSITION,
                        GOLD_GLOW, false);
                Fx.sound(at, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.7f, 1.4f + Fx.jitter(0.1f));
                Fx.sound(at, Sound.ENTITY_SLIME_SQUISH, 1.0f, 0.6f + Fx.jitter(0.1f));
                Fx.sound(at, Sound.BLOCK_WOOD_BREAK, 1.0f, 0.8f + Fx.jitter(0.1f));
            }

            void remove() {
                if (display.isValid()) {
                    display.remove();
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<LivingEntity> visibleEnemies(Player owner, Location center, double radius) {
        List<LivingEntity> found = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            if (visible(owner, enemy)) {
                found.add(enemy);
            }
        }
        return found;
    }

    private static boolean visible(Player owner, LivingEntity entity) {
        return !(entity instanceof Player player) || owner.canSee(player);
    }

    /** A wheat crop block of the given age (0..7). */
    private static BlockData crop(int age) {
        BlockData data = CROP.createBlockData();
        if (data instanceof Ageable ageable) {
            ageable.setAge(Math.clamp(age, 0, ageable.getMaximumAge()));
        }
        return data;
    }

    /** A crop of edge {@code size}, standing on the display's origin, turned by {@code yaw}. */
    private static Transformation cropPose(float size, float yaw) {
        return Displays.transform(0, size / 2, 0, size, size, size, yaw, 0, 0);
    }

    /** A pumpkin of edge {@code size} resting on the origin, facing {@code yaw}, rolled forward by {@code spin}. */
    private static Transformation pumpkinPose(float size, float yaw, float spin) {
        return Displays.transform(0, size / 2, 0, size, size, size, yaw, spin, 0);
    }

    /** The top of the first solid block at most {@link #GROUND_SNAP} blocks below {@code point}, null if none. */
    private static @Nullable Double groundBelow(Location point) {
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int start = point.getBlockY();
        int end = Math.max(world.getMinHeight(), start - GROUND_SNAP);
        for (int y = Math.min(start, world.getMaxHeight() - 1); y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /** The horizontal unit vector of a Minecraft yaw. */
    private static Vector direction(double yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    /** The Minecraft yaw (degrees) of a direction. */
    private static float yawOf(Vector direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    /** {@code degrees} wrapped into -180..180. */
    private static double wrap(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped > 180) {
            wrapped -= 360;
        } else if (wrapped <= -180) {
            wrapped += 360;
        }
        return wrapped;
    }
}
