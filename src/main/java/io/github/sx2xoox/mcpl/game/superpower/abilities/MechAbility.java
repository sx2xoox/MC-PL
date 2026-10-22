package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
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
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 메카 — a walking weapons platform. Its steel plating shrugs off knockback and blunts arrows, the shoulder pods fire
 * a volley of mini missiles that curve onto the aimed enemy, and the ultimate bolts a full steel suit onto the pilot:
 * bigger, heavier and harder hitting, with the right-click turned into a rocket punch.
 *
 * <p>The suit's melee bonus, damage reduction and rocket punch come from this instance's hooks and
 * {@link #cooldownMultiplier()}, so the ultimate is not standalone.
 *
 * <p>Look (steel gray, hot orange, gunmetal): arrows bouncing off the plating throw orange sparks with a metallic
 * ping, and a sprinting mech vents steam at its heels. The volley raises two missile pods over the shoulders that
 * recoil with every launch; each missile is a copper rod with a hot exhaust flame and a smoke trail, an orange
 * lock-on ring closes around the target with a beeping tone, and every hit is a flash, a fire bloom, smoke and a
 * small shock ring. The transformation bolts the suit on piece by piece (chest plate with a glowing orange core,
 * gunmetal backpack and thrusters, striped shoulder pads, a visored helmet with an antenna) with a clank per piece and
 * a burst of steam; the thrusters breathe fire while the suit lasts, heavy steps shake dust from the ground, and the
 * rocket punch flies out on a jet of flame and comes back to the arm. At the end the plates spring off and fall away.
 */
public final class MechAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("mech", "메카", Material.PISTON)
            .color(TextColor.color(0xFF8C1A))
            .difficulty(2)
            .passive("강철 장갑",
                    "넉백 저항이 {armor_knockback}% 오르고, 받는 투사체 피해가 {armor_projectile}% 줄어듭니다. "
                            + "장갑에 맞은 투사체는 불꽃을 튀기며 튕겨 나갑니다.")
            .active("미사일 포드",
                    "어깨의 포드에서 소형 미사일 {missile_count}발을 연달아 쏩니다. 미사일은 조준한 적(없으면 조준한 곳, "
                            + "{missile_range}블록 이내)을 향해 살짝 휘어 날아가, 부딪힌 곳 반경 {missile_radius}블록의 적에게 "
                            + "{missile_damage} 피해를 줍니다.",
                    9)
            .ultimate("메카 변신",
                    "{ult_duration}초간 강철 슈트를 입습니다. 크기가 {ult_scale}배가 되고 넉백에 면역이 되며, 받는 피해가 "
                            + "{ult_armor}% 줄고 근접 피해가 {ult_melee}% 늘어납니다. 그동안 액티브는 {punch_cooldown}초마다 "
                            + "쓸 수 있는 로켓 펀치로 바뀌어, 주먹을 {punch_range}블록 날려 처음 맞은 적에게 {punch_damage} "
                            + "피해를 주고 멀리 날려 보냅니다.")
            // The melee bonus, the armor and the rocket punch live in this instance's hooks.
            .ultimateNotStandalone()
            .param("armor_knockback", 40)
            .param("armor_projectile", 25)
            .param("missile_count", 4)
            .param("missile_interval", 0.15)
            .param("missile_range", 30)
            .param("missile_radius", 1.8)
            .param("missile_damage", 1.5)
            .param("ult_duration", 10)
            .param("ult_scale", 1.3)
            .param("ult_armor", 25)
            .param("ult_melee", 30)
            .param("punch_cooldown", 3)
            .param("punch_range", 14)
            .param("punch_damage", 4)
            .build();

    private static final String ORANGE = "#FF8C1A";
    private static final String STEEL = "#B8C2CC";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: hot orange, steel, gunmetal
    /** White-hot core, engine orange body, gunmetal edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFE0B2, 0xFF8C1A, 0x2E3238);
    private static final Color STEEL_COLOR = Color.fromRGB(0xB8C2CC);
    private static final Particle.DustOptions STEEL_DUST = new Particle.DustOptions(STEEL_COLOR, 0.9f);
    private static final Particle.DustOptions SPARK_DUST = new Particle.DustOptions(Color.fromRGB(0xFFC46B), 0.55f);
    private static final Particle.DustOptions ORANGE_DUST = PALETTE.dust(0.9f);
    private static final Particle.DustTransition HEAT = PALETTE.glow(1.1f);
    private static final Particle.DustTransition HEAT_SMALL = PALETTE.glow(0.6f);
    private static final Particle.DustTransition SOOT = PALETTE.fade(1.3f);

    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;
    /** Display entities this ability keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;

    // passive
    /** Fewest ticks between two armor feedbacks, so a flurry of arrows does not spam effects. */
    private static final long ARMOR_FX_GAP = 6;
    private static final long STEAM_PERIOD = 6;
    private static final long SERVO_PERIOD = 18;

    // active: missile pods
    private static final Material POD_CASING = Material.IRON_BLOCK;
    private static final Material POD_MUZZLE = Material.BLACK_CONCRETE;
    private static final Material POD_STRIPE = Material.ORANGE_CONCRETE;
    private static final int POD_RISE_TICKS = 3;
    /** Ticks the pods stay up after the last launch. */
    private static final long POD_HOLD_TICKS = 6;
    private static final int POD_FOLD_TICKS = 4;
    private static final int POD_RECOIL_TICKS = 2;
    private static final Material MISSILE_BLOCK = Material.LIGHTNING_ROD;
    private static final float MISSILE_SCALE = 0.7f;
    private static final double MISSILE_START_SPEED = 0.7;
    private static final double MISSILE_TOP_SPEED = 1.3;
    private static final double MISSILE_ACCEL = 0.1;
    /** Ticks a missile flies straight on its launch heading before it starts to steer. */
    private static final long MISSILE_BOOST_TICKS = 2;
    private static final double MISSILE_TURN = Math.toRadians(7);
    /** Close in, missiles turn harder (by this many blocks over the distance), so they dive into a near target. */
    private static final double MISSILE_CLOSE_TURN = 4;
    private static final double MISSILE_MAX_TURN = Math.toRadians(35);
    /**
     * The homing is weak: a missile steers at a point that follows its target at most this fast (blocks per tick),
     * slower than a sprinting player, so a target sprinting across the line of fire at range can outrun the volley.
     */
    private static final double MISSILE_TRACKING = 0.18;
    /** The launch climbs more for far targets (distance / this), between {@link #MISSILE_MIN_CLIMB} and max. */
    private static final double MISSILE_CLIMB_DISTANCE = 16;
    private static final double MISSILE_MIN_CLIMB = 0.1;
    private static final double MISSILE_MAX_CLIMB = 0.45;
    /** Proximity fuse: a missile this close to an enemy's hitbox detonates. */
    private static final double MISSILE_FUSE = 0.45;
    private static final double MISSILE_SAMPLE = 0.35;
    /** A missile without a locked target bursts this close to its goal (aimed at open air, it never hits a block). */
    private static final double AIRBURST = 0.7;
    /** Missiles aimed at the ground land around the aim point, this far apart. */
    private static final double MISSILE_SCATTER = 1.0;
    /** Missiles fly a little past their range before self-destructing. */
    private static final double MISSILE_OVERSHOOT = 6;
    private static final double BLAST_PUSH = 0.35;
    private static final double BLAST_LIFT = 0.25;
    private static final int LOCK_ON_TICKS = 6;

    // ultimate: steel suit
    private static final int GROW_STEPS = 4;
    private static final long GROW_INTERVAL = 2;
    private static final double STANDING_HEIGHT = 1.8;
    private static final double HEADROOM_HALF_WIDTH = 0.25;
    private static final long ASSEMBLE_STAGGER = 1;
    private static final int ASSEMBLE_TICKS = 5;
    private static final int EJECT_TICKS = 7;
    private static final long STEP_PERIOD = 8;
    private static final double MELEE_SHOVE = 0.4;

    // ultimate: rocket punch
    private static final double PUNCH_SPEED = 1.6;
    private static final double PUNCH_RETURN_SPEED = 2.0;
    private static final double PUNCH_HITBOX = 0.55;
    private static final long PUNCH_RETURN_MAX = 20;
    private static final double PUNCH_PUSH = 1.5;
    private static final double PUNCH_LIFT = 0.5;
    private static final float FIST_SCALE = 0.55f;
    private static final Material FIST_BLOCK = Material.PISTON;
    private static final Material FIST_FLAME = Material.ORANGE_STAINED_GLASS;

    /**
     * One steel suit piece: a block in body space (local +Z = facing, +X = the owner's left) on a full-size body,
     * rolled around the facing axis by {@code roll} degrees. Head pieces turn with the head and are hidden from the
     * pilot, so the helmet never blocks its view; glowing pieces are full bright, steel takes the world's light.
     */
    private record Piece(Material block, float x, float y, float z, float sx, float sy, float sz, float roll,
                         boolean head, boolean glow) {
    }

    private static final Piece[] SUIT = {
            new Piece(Material.IRON_BLOCK, 0f, 1.06f, 0f, 0.58f, 0.66f, 0.36f, 0f, false, false),
            new Piece(Material.SHROOMLIGHT, 0f, 1.16f, 0.185f, 0.14f, 0.14f, 0.03f, 45f, false, true),
            new Piece(Material.NETHERITE_BLOCK, 0f, 1.1f, -0.245f, 0.46f, 0.5f, 0.14f, 0f, false, false),
            new Piece(Material.NETHERITE_BLOCK, 0.13f, 0.8f, -0.26f, 0.12f, 0.2f, 0.12f, 0f, false, false),
            new Piece(Material.NETHERITE_BLOCK, -0.13f, 0.8f, -0.26f, 0.12f, 0.2f, 0.12f, 0f, false, false),
            new Piece(Material.IRON_BLOCK, 0.38f, 1.43f, 0f, 0.3f, 0.17f, 0.38f, -15f, false, false),
            new Piece(Material.IRON_BLOCK, -0.38f, 1.43f, 0f, 0.3f, 0.17f, 0.38f, 15f, false, false),
            new Piece(Material.ORANGE_CONCRETE, 0.4085f, 1.536f, 0f, 0.31f, 0.045f, 0.39f, -15f, false, true),
            new Piece(Material.ORANGE_CONCRETE, -0.4085f, 1.536f, 0f, 0.31f, 0.045f, 0.39f, 15f, false, true),
            new Piece(Material.NETHERITE_BLOCK, 0f, 1.645f, 0f, 0.54f, 0.52f, 0.54f, 0f, true, false),
            new Piece(Material.ORANGE_STAINED_GLASS, 0f, 1.66f, 0.275f, 0.4f, 0.09f, 0.03f, 0f, true, true),
            new Piece(Material.LIGHTNING_ROD, -0.2f, 1.98f, -0.08f, 0.3f, 0.3f, 0.3f, 0f, true, false),
    };
    /** Exhaust nozzle mouths in body space (under the two thrusters). */
    private static final Vector3f[] NOZZLES = {new Vector3f(0.13f, 0.68f, -0.26f), new Vector3f(-0.13f, 0.68f, -0.26f)};
    private static final Vector3f CORE_POINT = new Vector3f(0f, 1.16f, 0.21f);

    private @Nullable Suit suit;
    private int liveDisplays;
    private long lastArmorFx = Long.MIN_VALUE / 2;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: steel plating

    @Override
    public void onGrant() {
        double resistance = Math.clamp(params().num("armor_knockback") / 100.0, 0.0, 1.0);
        if (resistance > 0) {
            ctx().addModifier(player(), player(), Attribute.KNOCKBACK_RESISTANCE, resistance, Operation.ADD_NUMBER, 0);
        }
    }

    @Override
    public void onRevoke() {
        // The context removes the modifiers and displays; only forget them here.
        Suit worn = suit;
        suit = null;
        if (worn != null) {
            worn.forget();
        }
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        if (suit != null || !owner.isSprinting()) {
            return;
        }
        if (tick % STEAM_PERIOD == 0) {
            // Servo steam venting at the heels of a running mech.
            Location feet = owner.getLocation().add(0, 0.15, 0);
            Vector back = Motion.horizontalFacing(feet).multiply(-0.25);
            Vfx.dot(Vfx.scatter(feet.clone().add(back), 0.2), Particle.WHITE_SMOKE, null, false);
            Vfx.dot(Vfx.scatter(feet, 0.25), Particle.DUST, STEEL_DUST, false);
        }
        if (tick % SERVO_PERIOD == 0) {
            Fx.sound(owner.getLocation(), Sound.BLOCK_PISTON_EXTEND, 0.12f, 1.7f + Fx.jitter(0.1f));
        }
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0) {
            return;
        }
        Suit worn = suit;
        if (worn != null && event.getCause() == DamageCause.SUFFOCATION) {
            // The bigger suit may brush a low ceiling; never let it suffocate the pilot.
            event.setCancelled(true);
            return;
        }
        Tag<DamageType> bypass = DamageTypeTags.BYPASSES_INVULNERABILITY;
        if (bypass != null && bypass.isTagged(event.getDamageSource().getDamageType())) {
            return;
        }
        boolean projectile = isProjectile(event);
        double reduction = projectile ? Math.clamp(params().num("armor_projectile") / 100.0, 0.0, 1.0) : 0;
        if (worn != null) {
            double armor = Math.clamp(params().num("ult_armor") / 100.0, 0.0, 1.0);
            reduction = 1 - (1 - reduction) * (1 - armor);
        }
        if (reduction > 0) {
            event.setDamage(event.getDamage() * (1 - reduction));
        }
        if (projectile || event instanceof EntityDamageByEntityEvent) {
            armorFx(event, projectile);
        }
    }

    private static boolean isProjectile(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.PROJECTILE) {
            return true;
        }
        Tag<DamageType> projectile = DamageTypeTags.IS_PROJECTILE;
        return projectile != null && projectile.isTagged(event.getDamageSource().getDamageType());
    }

    /**
     * The plating takes a hit: sparks fly off where it struck, with a metallic ping for a deflected projectile and a
     * heavy clank for a blow the mech barely budges from.
     */
    private void armorFx(EntityDamageEvent event, boolean projectile) {
        long now = ctx().currentTick();
        if (now - lastArmorFx < ARMOR_FX_GAP) {
            return;
        }
        lastArmorFx = now;
        Player owner = player();
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.62, 0);
        Vector toward = Motion.horizontalFacing(owner.getLocation());
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Vector delta = byEntity.getDamager().getLocation().toVector().subtract(chest.toVector()).setY(0);
            if (delta.lengthSquared() > 1.0E-4) {
                toward = delta.normalize();
            }
        }
        Location surface = chest.clone().add(toward.clone().multiply(owner.getWidth() * 0.5 + 0.1));
        World world = surface.getWorld();
        world.spawnParticle(Particle.ELECTRIC_SPARK, surface, Vfx.n(ctx(), 6), 0.08, 0.12, 0.08, 0.25);
        world.spawnParticle(Particle.DUST, surface, Vfx.n(ctx(), 5), 0.1, 0.12, 0.1, 0, SPARK_DUST);
        if (projectile) {
            // The deflected round glances off in a short streak of orange.
            Vector glance = toward.clone().rotateAroundY(ThreadLocalRandom.current().nextBoolean() ? 1.1 : -1.1)
                    .setY(0.4);
            Vfx.trail(surface, surface.clone().add(glance.multiply(1.4)), PALETTE.body(), 5, false);
            Vfx.dot(surface, Particle.FLASH, PALETTE.core(), false);
            Fx.sound(surface, Sound.ITEM_SHIELD_BLOCK, 0.5f, 1.7f + Fx.jitter(0.1f));
            Fx.sound(surface, Sound.BLOCK_ANVIL_LAND, 0.15f, 2.0f);
        } else {
            Fx.sound(surface, Sound.ENTITY_IRON_GOLEM_HURT, 0.35f, 1.5f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ active: missile pods / rocket punch

    @Override
    public ActivationResult onActive() {
        if (suit != null) {
            return rocketPunch();
        }
        Player owner = player();
        double range = Math.max(4, params().num("missile_range"));
        LivingEntity target = ctx().findTarget(owner, range);
        if (target != null && !isVisible(target)) {
            target = null;
        }
        Location aim = target != null ? Fx.body(target) : aimPoint(owner, range);
        int count = Math.max(1, params().integer("missile_count"));
        long interval = Math.max(1, params().ticks("missile_interval"));
        Pods pods = new Pods(owner, (count - 1) * interval + POD_RISE_TICKS + POD_HOLD_TICKS);
        if (target != null) {
            lockOn(target);
        }
        Fx.sound(owner.getLocation(), Sound.BLOCK_PISTON_EXTEND, 0.8f, 1.3f + Fx.jitter(0.05f));
        Fx.sound(owner.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 0.9f, 0.7f + Fx.jitter(0.05f));
        LivingEntity locked = target;
        Volley volley = new Volley(count);
        Loop.repeat(ctx(), owner, POD_RISE_TICKS, interval, count, index -> {
            launch((int) index, locked, aim, pods, volley);
            return true;
        });
        return ActivationResult.success();
    }

    /** Where the aim ray hits a block (backed off a little), or the end of the range. */
    private static Location aimPoint(Player owner, double range) {
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        return hit == null
                ? eye.clone().add(direction.clone().multiply(range))
                : hit.getHitPosition().toLocation(owner.getWorld()).subtract(direction.clone().multiply(0.2));
    }

    /** Players hidden from the owner (stealth) are never locked onto, so the missiles do not give them away. */
    private boolean isVisible(LivingEntity target) {
        return !(target instanceof Player hidden) || player().canSee(hidden);
    }

    /** An orange ring closing in around the target, with a rising two-tone beep. */
    private void lockOn(LivingEntity target) {
        Player owner = player();
        Loop.repeat(ctx(), owner, 0, 1, LOCK_ON_TICKS, run -> {
            if (!Combat.isTargetable(ctx(), owner, target) || !isVisible(target)) {
                return false;
            }
            Location body = Fx.body(target);
            double radius = 1.5 - 0.95 * (run + 1) / LOCK_ON_TICKS;
            Vfx.ring(ctx(), body, radius, 14, run * 0.5, Particle.DUST_COLOR_TRANSITION, HEAT, false);
            if (run % 2 == 0) {
                Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, 1.4f + run * 0.1f);
            }
            if (run == LOCK_ON_TICKS - 1) {
                Vfx.ring(ctx(), body, 0.55, 12, 0, Particle.DUST, ORANGE_DUST, false);
                owner.playSound(owner, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 2.0f);
            }
            return true;
        });
    }

    /** Counts the missiles of one volley that hit something, for the summary at the end. */
    private static final class Volley {
        private final int count;
        private int landed;
        private int hits;

        Volley(int count) {
            this.count = count;
        }
    }

    private void launch(int index, @Nullable LivingEntity target, Location aim, Pods pods, Volley volley) {
        Player owner = player();
        boolean left = index % 2 == 0;
        Location muzzle = pods.muzzle(owner, left);
        Vector look = owner.getEyeLocation().getDirection();
        Vector side = new Vector(look.getZ(), 0, -look.getX());
        if (side.lengthSquared() < 1.0E-6) {
            side = new Vector(1, 0, 0);
        }
        // Left pod missiles break left, right pod missiles right, both climbing first (more for a far target).
        side.normalize().multiply(left ? 0.3 : -0.3);
        double climb = Math.clamp(owner.getEyeLocation().distance(aim) / MISSILE_CLIMB_DISTANCE, MISSILE_MIN_CLIMB,
                MISSILE_MAX_CLIMB);
        Vector heading = look.clone().normalize().add(new Vector(0, climb, 0)).add(side).normalize();
        Location goal = aim.clone();
        if (target == null) {
            double angle = index * TAU / Math.max(1, volley.count) + ThreadLocalRandom.current().nextDouble(0.6);
            goal.add(Math.cos(angle) * MISSILE_SCATTER, 0, Math.sin(angle) * MISSILE_SCATTER);
        }
        Missile missile = new Missile(muzzle, heading, target, goal, volley);
        long maxTicks = (long) Math.ceil((params().num("missile_range") + MISSILE_OVERSHOOT) / MISSILE_START_SPEED) + 4;
        Loop.repeat(ctx(), owner, 0, 1, maxTicks, missile, missile::selfDestruct);
        pods.recoil(left);

        World world = muzzle.getWorld();
        Vfx.dot(muzzle, Particle.FLASH, PALETTE.core(), false);
        world.spawnParticle(Particle.FLAME, muzzle, Vfx.n(ctx(), 4), 0.06, 0.06, 0.06, 0.04);
        world.spawnParticle(Particle.LARGE_SMOKE, muzzle, Vfx.n(ctx(), 3), 0.08, 0.05, 0.08, 0.02);
        world.spawnParticle(Particle.WHITE_SMOKE, muzzle, Vfx.n(ctx(), 4), 0.1, 0.05, 0.1, 0.03);
        Fx.sound(muzzle, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.9f, 0.85f + index * 0.06f + Fx.jitter(0.05f));
        Fx.sound(muzzle, Sound.BLOCK_DISPENSER_LAUNCH, 0.6f, 1.3f + Fx.jitter(0.1f));
    }

    /**
     * The two missile pods over the shoulders: an iron casing, a black muzzle face and an orange stripe each, rising
     * out of the shoulders, following the owner while the volley lasts, kicking back with every launch, then folding
     * away. Without display budget the volley still fires, from the shoulders.
     */
    private final class Pods {
        private final List<BlockDisplay> parts = new ArrayList<>();
        /** Part layout: casing, muzzle, stripe per side; left side first. */
        private static final int PER_SIDE = 3;
        private final float scale;
        private final long life;
        private long age;
        private boolean folded;

        Pods(Player owner, long life) {
            this.scale = (float) Body.scaleOf(owner);
            this.life = life;
            if (!reserveDisplays(PER_SIDE * 2, life + POD_FOLD_TICKS + 2)) {
                return;
            }
            Location at = anchor(owner, false);
            for (boolean left : new boolean[] {true, false}) {
                for (int part = 0; part < PER_SIDE; part++) {
                    Material block = part == 0 ? POD_CASING : part == 1 ? POD_MUZZLE : POD_STRIPE;
                    BlockDisplay display = Displays.block(ctx(), owner, at, block.createBlockData(),
                            podPose(left, part, 0.01f, 0), life + POD_FOLD_TICKS + 2);
                    display.setTeleportDuration(1);
                    if (part == 0) {
                        display.setBrightness(null);
                    }
                    Displays.animateNextTick(ctx(), owner, display, podPose(left, part, 1f, 0), POD_RISE_TICKS);
                    parts.add(display);
                }
            }
            Loop.repeat(ctx(), owner, 1, 1, life, this::follow, this::fold);
        }

        private boolean follow(long tick) {
            age = tick;
            Player owner = player();
            Location at = anchor(owner, false);
            for (BlockDisplay part : parts) {
                if (part.isValid() && part.getWorld().equals(owner.getWorld())) {
                    part.teleport(at);
                }
            }
            return true;
        }

        /** The pod kicks back along the owner's back and settles again. */
        void recoil(boolean left) {
            if (parts.isEmpty() || folded) {
                return;
            }
            int first = left ? 0 : PER_SIDE;
            for (int part = 0; part < PER_SIDE; part++) {
                BlockDisplay display = parts.get(first + part);
                int index = part;
                Displays.animate(display, podPose(left, index, 1f, -0.12f), 1);
                ctx().runLater(player(), POD_RECOIL_TICKS, () -> {
                    if (!folded) {
                        Displays.animate(display, podPose(left, index, 1f, 0), POD_RECOIL_TICKS);
                    }
                });
            }
            Location vent = muzzle(player(), left).add(Motion.horizontalFacing(player().getLocation()).multiply(-0.5));
            Vfx.dot(vent, Particle.WHITE_SMOKE, null, false);
        }

        private void fold() {
            folded = true;
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), player(), part, POD_FOLD_TICKS);
                }
            }
            if (!parts.isEmpty()) {
                Fx.sound(player().getLocation(), Sound.BLOCK_PISTON_CONTRACT, 0.6f, 1.4f + Fx.jitter(0.05f));
            }
        }

        /** The world position of a pod's muzzle (or the shoulder, when the pods could not be shown). */
        Location muzzle(Player owner, boolean left) {
            Location at = anchor(owner, false);
            float growth = age < POD_RISE_TICKS ? (age + 1f) / POD_RISE_TICKS : 1f;
            Vector3f local = new Vector3f(left ? 0.36f : -0.36f, 1.45f + 0.18f * growth, 0.27f).mul(scale);
            return bodyToWorld(at, local);
        }

        /**
         * One part of a pod in body space: the casing is a box above the shoulder, the muzzle a black plate on its
         * front face and the stripe an orange band on its top, all scaled by {@code grow} and pushed back by
         * {@code kick}.
         */
        private Transformation podPose(boolean left, int part, float grow, float kick) {
            float side = left ? 1f : -1f;
            float s = scale * Math.max(0.01f, grow);
            float baseY = 1.45f * scale + 0.18f * s;
            Vector3f center;
            Vector3f size;
            switch (part) {
                case 0 -> {
                    center = new Vector3f(0.36f * side * scale, baseY, kick * scale);
                    size = new Vector3f(0.26f, 0.24f, 0.46f).mul(s);
                }
                case 1 -> {
                    center = new Vector3f(0.36f * side * scale, baseY, kick * scale + 0.235f * s);
                    size = new Vector3f(0.2f, 0.18f, 0.02f).mul(s);
                }
                default -> {
                    center = new Vector3f(0.36f * side * scale, baseY + 0.125f * s, kick * scale);
                    size = new Vector3f(0.27f, 0.03f, 0.3f).mul(s);
                }
            }
            return box(center, size, 0);
        }
    }

    /**
     * A mini missile: a copper rod (lightning rod block display) that boosts out of its pod, then steers at its target
     * (the locked enemy, or its patch of the aim point) with a weak turn rate, trailing exhaust flame and smoke. It
     * detonates on the first enemy it gets close to, on a block, or self-destructs harmlessly past its range.
     */
    private final class Missile implements LongPredicate {

        private final World world;
        private final Location position;
        private final Vector direction;
        private final Location goal;
        private final Volley volley;
        private final @Nullable BlockDisplay visual;
        private @Nullable LivingEntity target;
        private double remaining;
        private boolean done;

        Missile(Location start, Vector heading, @Nullable LivingEntity target, Location goal, Volley volley) {
            this.world = start.getWorld();
            this.position = start.clone();
            this.direction = heading.clone().normalize();
            this.target = target;
            this.goal = goal;
            this.volley = volley;
            this.remaining = Math.max(1, params().num("missile_range")) + MISSILE_OVERSHOOT;
            long lifetime = (long) Math.ceil(remaining / MISSILE_START_SPEED) + 8;
            if (reserveDisplays(1, lifetime)) {
                BlockDisplay display = Displays.block(ctx(), player(), plain(start), rod(),
                        pointPose(direction, MISSILE_SCALE * 0.4f), lifetime);
                display.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), player(), display, pointPose(direction, MISSILE_SCALE), 2);
                visual = display;
            } else {
                visual = null;
            }
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            if (tick >= MISSILE_BOOST_TICKS) {
                steer();
            }
            double speed = Math.min(MISSILE_TOP_SPEED, MISSILE_START_SPEED + tick * MISSILE_ACCEL);
            double step = Math.min(speed, remaining);
            if (step <= 1.0E-3) {
                selfDestruct();
                return false;
            }
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.NEVER, true);
            double travel = wall == null ? step : wall.getHitPosition().distance(position.toVector());
            int samples = Math.max(1, (int) Math.ceil(travel / MISSILE_SAMPLE));
            for (int i = 1; i <= samples; i++) {
                Location probe = position.clone().add(direction.clone().multiply(travel * i / samples));
                if (!ctx().enemiesNear(player(), probe, MISSILE_FUSE).isEmpty()) {
                    position.setX(probe.getX());
                    position.setY(probe.getY());
                    position.setZ(probe.getZ());
                    detonate();
                    return false;
                }
            }
            Location from = position.clone();
            position.add(direction.clone().multiply(travel));
            remaining -= travel;
            fly(from, tick);
            if (wall != null) {
                position.subtract(direction.clone().multiply(0.25));
                detonate();
                return false;
            }
            if (target == null && tick >= MISSILE_BOOST_TICKS && position.distanceSquared(goal) < AIRBURST * AIRBURST) {
                // Aimed at open air: the missile bursts where it was sent.
                detonate();
                return false;
            }
            if (remaining <= 1.0E-3) {
                selfDestruct();
                return false;
            }
            return true;
        }

        /**
         * Turns towards the goal by a limited angle per tick, more when close. While the lock holds, the goal trails
         * the target's chest at {@link #MISSILE_TRACKING}; a lost lock leaves it where it was.
         */
        private void steer() {
            LivingEntity current = target;
            if (current != null && (!Combat.isTargetable(ctx(), player(), current) || !isVisible(current)
                    || !current.getWorld().equals(world))) {
                target = null;
                current = null;
            }
            if (current != null) {
                Vector follow = Fx.body(current).toVector().subtract(goal.toVector());
                double lag = follow.length();
                if (lag > MISSILE_TRACKING) {
                    follow.multiply(MISSILE_TRACKING / lag);
                }
                goal.add(follow);
            }
            Vector desired = goal.toVector().subtract(position.toVector());
            double distance = desired.length();
            if (distance < 1.0E-3) {
                return;
            }
            double turn = Math.min(MISSILE_MAX_TURN, MISSILE_TURN * (1 + MISSILE_CLOSE_TURN / Math.max(0.5, distance)));
            desired.multiply(1 / distance);
            double angle = direction.angle(desired);
            if (!Double.isFinite(angle) || angle <= turn) {
                direction.copy(desired);
                return;
            }
            double t = turn / angle;
            Vector turned = direction.clone().multiply(1 - t).add(desired.multiply(t));
            if (turned.lengthSquared() > 1.0E-6) {
                direction.copy(turned.normalize());
            }
        }

        /** Moves the rod and leaves exhaust: a hot flame at the tail, an orange streak and a smoke trail. */
        private void fly(Location from, long tick) {
            BlockDisplay display = visual;
            if (display != null && display.isValid()) {
                display.teleport(plain(position));
                if (tick > 1) {
                    Displays.animate(display, pointPose(direction, MISSILE_SCALE), 1);
                }
            }
            Vector back = direction.clone().multiply(-1);
            Location tail = position.clone().add(back.clone().multiply(MISSILE_SCALE * 0.5));
            world.spawnParticle(Particle.FLAME, tail.getX(), tail.getY(), tail.getZ(), 0, back.getX(), back.getY(),
                    back.getZ(), 0.08);
            Vfx.line(ctx(), from, tail, 2.0, Particle.DUST_COLOR_TRANSITION, HEAT_SMALL, false);
            if (tick % 2 == 0) {
                Vfx.dot(tail, Particle.SMOKE, null, false);
                Vfx.trail(tail, tail.clone().add(back.multiply(1.2)).add(Fx.jitter(0.15f), Fx.jitter(0.15f),
                        Fx.jitter(0.15f)), PALETTE.edge(), 8, false);
            }
            if (tick % 3 == 1) {
                Vfx.dot(from, Particle.CAMPFIRE_COSY_SMOKE, null, false);
            }
        }

        /** The warhead goes off: splash damage to every enemy in the radius it can see, a flash, fire and smoke. */
        private void detonate() {
            done = true;
            removeVisual();
            Player owner = player();
            double radius = Math.max(0.3, params().num("missile_radius"));
            double damage = params().num("missile_damage");
            int struck = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, position, radius)) {
                if (!Spots.clearPath(position, Fx.body(enemy))) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                struck++;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = enemy.getLocation().toVector().subtract(position.toVector()).setY(0);
                if (away.lengthSquared() < 1.0E-4) {
                    away = direction.clone().setY(0);
                }
                if (away.lengthSquared() > 1.0E-4) {
                    Motion.push(enemy, away.normalize().multiply(BLAST_PUSH).setY(BLAST_LIFT), MAX_SPEED);
                }
                Vfx.cloud(ctx(), Fx.body(enemy), Particle.DUST_COLOR_TRANSITION, 5, 0.25, 0, SOOT, false);
            }
            volley.landed++;
            if (struck > 0) {
                volley.hits++;
            }
            explosionFx(position, radius);
            report();
        }

        /** Out of range: the missile pops in a puff of smoke without hurting anyone. */
        void selfDestruct() {
            if (done) {
                return;
            }
            done = true;
            removeVisual();
            volley.landed++;
            Vfx.dot(position, Particle.FLASH, PALETTE.body(), false);
            Vfx.cloud(ctx(), position, Particle.SMOKE, 6, 0.15, 0.03, null, false);
            Vfx.cloud(ctx(), position, Particle.ELECTRIC_SPARK, 4, 0.1, 0.15, null, false);
            Fx.sound(position, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.5f, 1.6f + Fx.jitter(0.1f));
            report();
        }

        private void removeVisual() {
            if (visual != null && visual.isValid()) {
                visual.remove();
            }
        }

        private void report() {
            if (volley.landed == volley.count && volley.hits > 0 && player().isOnline()) {
                player().sendActionBar(Text.mm("<" + ORANGE + ">미사일</" + ORANGE + "> <white>" + volley.hits + "/"
                        + volley.count + " 명중</white>"));
            }
        }
    }

    /** A small explosion: flash, fire bloom, a black smoke puff, a shock ring on the ground and sparks. */
    private void explosionFx(Location at, double radius) {
        World world = at.getWorld();
        Vfx.burst(ctx(), at, PALETTE, Math.min(1.2, radius * 0.6), Particle.FLAME, false);
        world.spawnParticle(Particle.EXPLOSION, at, 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.LARGE_SMOKE, at, Vfx.n(ctx(), 6), 0.25, 0.2, 0.25, 0.03);
        world.spawnParticle(Particle.LAVA, at, Vfx.n(ctx(), 2), 0.15, 0.1, 0.15, 0);
        Vfx.cloud(ctx(), at, Particle.ELECTRIC_SPARK, 6, 0.2, 0.3, null, false);
        RayTraceResult floor = world.rayTraceBlocks(at, new Vector(0, -1, 0), 2.5, FluidCollisionMode.NEVER, true);
        if (floor != null) {
            Location ground = floor.getHitPosition().toLocation(world).add(0, 0.1, 0);
            Vfx.shockwave(ctx(), player(), ground, 0.3, radius, 4, Particle.DUST_COLOR_TRANSITION, SOOT, false);
        }
        Location glow = at.clone();
        Loop.repeat(ctx(), player(), 2, 2, 5, run -> {
            Location ember = glow.clone().add(Fx.jitter(0.5f), 0.1 + run * 0.12, Fx.jitter(0.5f));
            Vfx.dot(ember, Particle.DUST_COLOR_TRANSITION, HEAT_SMALL, false);
            if (run % 2 == 0) {
                Vfx.dot(ember, Particle.SMOKE, null, false);
            }
            return true;
        });
        Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.55f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.9f, 0.8f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ ultimate: steel suit

    @Override
    public ActivationResult onUltimate() {
        if (suit != null) {
            return ActivationResult.fail("이미 메카 슈트를 입고 있습니다");
        }
        Player owner = player();
        Suit worn = new Suit(owner);
        suit = worn;
        worn.begin();
        return ActivationResult.success();
    }

    /** While the suit is worn the active is the rocket punch, on its own short cooldown. */
    @Override
    public double cooldownMultiplier() {
        if (suit == null) {
            return 1.0;
        }
        double base = params().cooldownSeconds();
        return base <= 1.0E-3 ? 1.0 : Math.max(0, params().num("punch_cooldown")) / base;
    }

    /** The melee bonus of the suit: only changes the damage, since the victim may still cancel the hit. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (suit == null || event.isCancelled() || !isOwnMelee(event, victim)) {
            return;
        }
        double bonus = Math.max(-0.9, params().num("ult_melee") / 100.0);
        event.setDamage(event.getDamage() * (1 + bonus));
    }

    /** A suit punch landed: a steel impact with sparks, a clang, and the victim is shoved a little further. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (suit == null || !isOwnMelee(event, victim)) {
            return;
        }
        Player owner = player();
        Location body = Fx.body(victim);
        World world = body.getWorld();
        Vfx.dot(body, Particle.FLASH, PALETTE.core(), false);
        world.spawnParticle(Particle.ELECTRIC_SPARK, body, Vfx.n(ctx(), 8), 0.2, 0.25, 0.2, 0.3);
        world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 6), 0.2, 0.25, 0.2, 0, SPARK_DUST);
        Vfx.arc(ctx(), body, victim.getWidth() * 0.5 + 0.3, owner.getLocation().getYaw(), 120, 10,
                Particle.DUST_COLOR_TRANSITION, HEAT, false);
        Fx.sound(body, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.9f, 0.9f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.BLOCK_ANVIL_PLACE, 0.25f, 1.6f + Fx.jitter(0.1f));
        Vector shove = Motion.horizontalFacing(owner.getLocation()).multiply(MELEE_SHOVE).setY(0.08);
        // Vanilla knockback is applied after this event; add the shove on top of it next tick.
        ctx().runLater(owner, 1, () -> {
            if (Combat.isTargetable(ctx(), owner, victim)) {
                Vector extra = shove.clone().multiply(Motion.knockbackFactor(victim));
                victim.setVelocity(Motion.clamp(victim.getVelocity().add(extra), MAX_SPEED));
            }
        });
    }

    /** The owner's own melee swing at an enemy (not ability damage). */
    private boolean isOwnMelee(EntityDamageByEntityEvent event, LivingEntity victim) {
        DamageCause cause = event.getCause();
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && (cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK)
                && ctx().isEnemy(player(), victim);
    }

    /** One transformation: the suit bolts on, the pilot grows, fights, then the plates spring off. */
    private final class Suit {
        private final double baseScale;
        private final double growth;
        private final long duration;
        private final List<NamespacedKey> buffs = new ArrayList<>();
        private final BlockDisplay[] pieces = new BlockDisplay[SUIT.length];
        /** Pieces whose fly-in has started; only those follow the growth (the others still fly in). */
        private final boolean[] placed = new boolean[SUIT.length];
        private @Nullable Location lastStep;
        private @Nullable NamespacedKey scaleKey;
        private int step;
        private boolean blocked;
        private long endTick;
        private boolean ended;

        Suit(Player owner) {
            this.baseScale = Body.scaleOf(owner);
            this.growth = Math.max(0, params().num("ult_scale") * baseScale - baseScale);
            this.duration = Math.max(1, params().ticks("ult_duration"));
        }

        void begin() {
            Player owner = player();
            endTick = ctx().currentTick() + duration;
            buffs.add(ctx().addModifier(owner, owner, Attribute.KNOCKBACK_RESISTANCE, 1.0, Operation.ADD_NUMBER, 0));
            // The first rocket punch is ready the moment the suit is on.
            ctx().reduceCooldown(owner, Math.max(0, ctx().cooldownRemaining(owner)) + 1);
            assemble(owner);
            Loop.repeat(ctx(), owner, 0, 1, duration, this::tick, this::eject);
        }

        /** The suit flies in piece by piece from around the pilot and locks into place. */
        private void assemble(Player owner) {
            Location at = owner.getLocation();
            World world = at.getWorld();
            Location body = Fx.body(owner);
            world.spawnParticle(Particle.FLASH, body, 1, 0, 0, 0, 0, PALETTE.core(), true);
            Vfx.helix(ctx(), at, 0.9, owner.getHeight() * 1.3, 1.5, 2, 14, 0, Particle.DUST_COLOR_TRANSITION, HEAT,
                    true);
            Vfx.ring(ctx(), at.clone().add(0, 0.1, 0), 1.2, 18, 0, Particle.DUST, STEEL_DUST, true);
            world.spawnParticle(Particle.WHITE_SMOKE, at.clone().add(0, 0.3, 0), Vfx.n(ctx(), 18), 0.6, 0.2, 0.6,
                    0.04, null, true);
            Fx.sound(at, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.6f);
            Fx.sound(at, Sound.BLOCK_PISTON_EXTEND, 1.2f, 0.6f);
            Fx.sound(at, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.2f, 0.8f);
            if (!reserveDisplays(SUIT.length, duration + 4L * GROW_INTERVAL + EJECT_TICKS + 30)) {
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location anchor = anchor(owner, false);
            for (int i = 0; i < SUIT.length; i++) {
                Piece piece = SUIT[i];
                Location spawn = piece.head() ? anchor(owner, true) : anchor;
                double angle = random.nextDouble(TAU);
                Vector3f from = new Vector3f((float) Math.cos(angle) * 1.6f, 0.4f + random.nextFloat() * 0.8f,
                        (float) Math.sin(angle) * 1.6f);
                BlockDisplay display = Displays.block(ctx(), owner, spawn, piece.block().createBlockData(),
                        piecePose(piece, (float) baseScale, 0.25f, from), duration + 4L * GROW_INTERVAL + EJECT_TICKS
                                + 30);
                display.setTeleportDuration(1);
                if (!piece.glow()) {
                    display.setBrightness(null);
                }
                if (piece.head()) {
                    // The helmet would block the pilot's own view; only the others see it.
                    owner.hideEntity(ctx().plugin(), display);
                }
                pieces[i] = display;
                long delay = 1 + i * ASSEMBLE_STAGGER;
                int index = i;
                ctx().runLater(owner, delay, () -> {
                    if (display.isValid() && !ended) {
                        placed[index] = true;
                        Displays.animate(display, piecePose(piece, currentScale(), 1f, new Vector3f()),
                                ASSEMBLE_TICKS);
                    }
                });
                ctx().runLater(owner, delay + ASSEMBLE_TICKS, () -> {
                    if (display.isValid() && suit == this) {
                        Location point = bodyToWorld(anchor(owner, piece.head()),
                                new Vector3f(piece.x(), piece.y(), piece.z()).mul(currentScale()));
                        Vfx.cloud(ctx(), point, Particle.ELECTRIC_SPARK, 3, 0.08, 0.15, null, false);
                        Fx.sound(point, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.5f, 0.8f + Fx.jitter(0.15f));
                    }
                });
            }
        }

        private boolean tick(long tick) {
            Player owner = player();
            if (ended) {
                return false;
            }
            if (tick % GROW_INTERVAL == 0 && step < GROW_STEPS && growth > 0 && !blocked) {
                grow(owner);
            }
            follow(owner);
            if (tick % 2 == 0) {
                exhaust(owner, tick);
            }
            if (tick % 10 == 0) {
                Location core = bodyToWorld(anchor(owner, false), new Vector3f(CORE_POINT).mul(currentScale()));
                Vfx.ring(ctx(), core, 0.18, 6, tick * 0.3, Particle.DUST_COLOR_TRANSITION, HEAT_SMALL, false);
            }
            if (tick % STEP_PERIOD == 0) {
                stomp(owner);
            }
            if (tick % 5 == 0) {
                long left = Math.max(0, endTick - ctx().currentTick());
                double cooldown = ctx().cooldownRemaining(owner);
                String punch = cooldown <= 0 ? "<" + ORANGE + ">로켓 펀치 준비</" + ORANGE + ">"
                        : "<gray>로켓 펀치 " + AbilityInfo.formatNumber(Math.ceil(cooldown * 10) / 10) + "초</gray>";
                owner.sendActionBar(Text.mm("<" + STEEL + ">⚙ 메카</" + STEEL + "> <white>" + Fx.seconds(left)
                        + "초</white> <dark_gray>|</dark_gray> " + punch));
            }
            if (tick % 40 == 20) {
                Fx.sound(owner.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.4f, 1.8f);
            }
            return true;
        }

        /**
         * Heavy steps: a walking suit thuds and shakes dust off the ground. Movement is read from the position (a
         * player's server-side velocity is unreliable).
         */
        private void stomp(Player owner) {
            Location feet = owner.getLocation();
            Location previous = lastStep;
            lastStep = feet;
            if (previous == null || !previous.getWorld().equals(feet.getWorld()) || !Motion.isGrounded(owner)
                    || Math.hypot(feet.getX() - previous.getX(), feet.getZ() - previous.getZ()) < 0.3) {
                return;
            }
            Fx.sound(feet, Sound.ENTITY_IRON_GOLEM_STEP, 0.7f, 0.8f + Fx.jitter(0.08f));
            BlockData ground = feet.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
            if (ground.getMaterial().isSolid()) {
                feet.getWorld().spawnParticle(Particle.BLOCK, feet.clone().add(0, 0.1, 0), Vfx.n(ctx(), 6), 0.3,
                        0.02, 0.3, 0.05, ground);
            }
        }

        private void grow(Player owner) {
            int next = step + 1;
            double nextScale = baseScale + growth * next / GROW_STEPS;
            if (!hasHeadroom(owner, nextScale) || !Spots.isFree(owner.getWorld(), owner.getLocation(),
                    Body.PLAYER.width() * nextScale, Body.PLAYER.height() * nextScale)) {
                blocked = true;
                return;
            }
            setStep(owner, next);
            for (int i = 0; i < pieces.length; i++) {
                BlockDisplay display = pieces[i];
                if (display != null && display.isValid() && placed[i]) {
                    Displays.animate(display, piecePose(SUIT[i], currentScale(), 1f, new Vector3f()),
                            (int) GROW_INTERVAL);
                }
            }
            Location feet = owner.getLocation().add(0, 0.1, 0);
            Vfx.ring(ctx(), feet, owner.getWidth() * 0.8, 10, next * 0.6, Particle.DUST, STEEL_DUST, true);
            Fx.sound(feet, Sound.BLOCK_PISTON_EXTEND, 0.6f, 0.5f + next * 0.08f);
        }

        private void setStep(Player owner, int value) {
            NamespacedKey previous = scaleKey;
            if (previous != null) {
                ctx().removeModifier(owner, previous);
                scaleKey = null;
            }
            step = value;
            if (value > 0) {
                scaleKey = ctx().addModifier(owner, owner, Attribute.SCALE, growth * value / GROW_STEPS,
                        Operation.ADD_NUMBER, 0);
            }
        }

        private float currentScale() {
            return (float) (baseScale + growth * step / GROW_STEPS);
        }

        private void follow(Player owner) {
            Location body = anchor(owner, false);
            Location head = anchor(owner, true);
            for (int i = 0; i < pieces.length; i++) {
                BlockDisplay display = pieces[i];
                if (display != null && display.isValid() && display.getWorld().equals(owner.getWorld())) {
                    display.teleport(SUIT[i].head() ? head : body);
                }
            }
        }

        /** The thrusters breathe fire and smoke; harder while sprinting or in the air. */
        private void exhaust(Player owner, long tick) {
            Location anchor = anchor(owner, false);
            boolean boost = owner.isSprinting() || !Motion.isGrounded(owner);
            World world = anchor.getWorld();
            for (Vector3f nozzle : NOZZLES) {
                Location mouth = bodyToWorld(anchor, new Vector3f(nozzle).mul(currentScale()));
                world.spawnParticle(Particle.FLAME, mouth.getX(), mouth.getY(), mouth.getZ(), 0, Fx.jitter(0.1f),
                        -1, Fx.jitter(0.1f), boost ? 0.12 : 0.05);
                if (boost) {
                    world.spawnParticle(Particle.SMALL_FLAME, mouth, 1, 0.03, 0.03, 0.03, 0.02);
                    Vfx.dot(mouth.clone().subtract(0, 0.2, 0), Particle.DUST_COLOR_TRANSITION, HEAT_SMALL, false);
                }
                if (tick % 6 == 0) {
                    Vfx.dot(mouth.clone().subtract(0, 0.25, 0), Particle.SMOKE, null, false);
                }
            }
        }

        /** Time is up: the plates spring off and fall away, the pilot shrinks back and vents steam. */
        private void eject() {
            if (ended) {
                return;
            }
            ended = true;
            Player owner = player();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (BlockDisplay display : pieces) {
                if (display == null || !display.isValid()) {
                    continue;
                }
                Transformation current = display.getTransformation();
                double angle = random.nextDouble(TAU);
                Vector3f out = new Vector3f((float) Math.cos(angle) * 1.2f, -0.4f - random.nextFloat() * 0.6f,
                        (float) Math.sin(angle) * 1.2f);
                Quaternionf tumble = new Quaternionf(current.getLeftRotation())
                        .rotateXYZ(random.nextFloat() * 2f, random.nextFloat() * 2f, random.nextFloat() * 2f);
                Transformation flung = new Transformation(new Vector3f(current.getTranslation()).add(out), tumble,
                        new Vector3f(current.getScale()).mul(0.8f), new Quaternionf());
                Displays.animate(display, flung, EJECT_TICKS);
                ctx().runLater(owner, EJECT_TICKS, () -> {
                    if (display.isValid()) {
                        Displays.shrinkAway(ctx(), owner, display, 5);
                    }
                });
            }
            Location at = owner.getLocation();
            World world = at.getWorld();
            world.spawnParticle(Particle.WHITE_SMOKE, at.clone().add(0, 1, 0), Vfx.n(ctx(), 20), 0.5, 0.6, 0.5, 0.05);
            world.spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0, 1, 0), Vfx.n(ctx(), 10), 0.4, 0.5, 0.4,
                    0.3);
            Fx.sound(at, Sound.BLOCK_PISTON_CONTRACT, 1.0f, 0.6f);
            Fx.sound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 0.9f);
            Fx.sound(at, Sound.BLOCK_BEACON_DEACTIVATE, 0.7f, 1.4f);
            Loop.repeat(ctx(), owner, 0, GROW_INTERVAL, Math.max(1, step), run -> {
                setStep(owner, Math.max(0, step - 1));
                return step > 0;
            }, this::end);
        }

        private void end() {
            Player owner = player();
            setStep(owner, 0);
            for (NamespacedKey key : buffs) {
                ctx().removeModifier(owner, key);
            }
            buffs.clear();
            if (suit == this) {
                suit = null;
            }
            owner.sendActionBar(Text.mm("<" + STEEL + ">⚙ 메카 슈트 해제</" + STEEL + ">"));
        }

        /** The owner is gone: the context removes the displays and modifiers, only stop here. */
        void forget() {
            ended = true;
        }
    }

    // ------------------------------------------------------------------ rocket punch

    private ActivationResult rocketPunch() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 1.0E-6) {
            return ActivationResult.fail("조준 방향을 알 수 없습니다");
        }
        direction.normalize();
        Location hand = rightHand(owner);
        Punch punch = new Punch(hand, direction);
        long maxTicks = (long) Math.ceil(Math.max(1, params().num("punch_range")) / PUNCH_SPEED) + 2;
        Loop.repeat(ctx(), owner, 0, 1, maxTicks, punch, punch::recall);

        World world = hand.getWorld();
        Vfx.dot(hand, Particle.FLASH, PALETTE.core(), false);
        world.spawnParticle(Particle.FLAME, hand, Vfx.n(ctx(), 8), 0.1, 0.1, 0.1, 0.08);
        world.spawnParticle(Particle.WHITE_SMOKE, hand, Vfx.n(ctx(), 6), 0.15, 0.1, 0.15, 0.04);
        aimRing(hand, direction, 0.4, 12);
        Fx.sound(hand, Sound.ENTITY_BREEZE_SHOOT, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(hand, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(hand, Sound.BLOCK_PISTON_EXTEND, 1.0f, 0.8f);
        return ActivationResult.success();
    }

    /** The rocket fist's launch point: in front of the pilot's right hand. */
    private static Location rightHand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        Vector right = new Vector(-look.getZ(), 0, look.getX());
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vector(1, 0, 0);
        }
        double scale = Body.scaleOf(owner);
        return eye.clone().add(right.normalize().multiply(0.4 * scale)).subtract(0, 0.4 * scale, 0)
                .add(look.clone().normalize().multiply(0.6));
    }

    /** A ring of hot dust perpendicular to {@code direction}, like a shockwave off the launch. */
    private void aimRing(Location center, Vector direction, double radius, int points) {
        Vector axis = direction.clone().normalize();
        Vector u = axis.clone().crossProduct(Math.abs(axis.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0))
                .normalize();
        Vector v = axis.clone().crossProduct(u);
        int count = Vfx.n(ctx(), points);
        for (int i = 0; i < count; i++) {
            double angle = i * TAU / count;
            Vector offset = u.clone().multiply(Math.cos(angle) * radius).add(v.clone().multiply(Math.sin(angle) * radius));
            Vfx.dot(center.clone().add(offset), Particle.DUST_COLOR_TRANSITION, HEAT, false);
        }
    }

    /**
     * The rocket punch: a piston fist with a ring of orange glass at its wrist flies straight out on a jet of flame,
     * slams into the first enemy (damage and a big knockback) or a wall, then flies back to the pilot's arm.
     */
    private final class Punch implements LongPredicate {
        private final World world;
        private final Location position;
        private final Vector direction;
        private final @Nullable BlockDisplay fist;
        private final @Nullable BlockDisplay flame;
        private double remaining;
        private boolean returning;

        Punch(Location start, Vector direction) {
            this.world = start.getWorld();
            this.position = start.clone();
            this.direction = direction.clone();
            this.remaining = Math.max(1, params().num("punch_range"));
            long lifetime = (long) Math.ceil(remaining / PUNCH_SPEED) + PUNCH_RETURN_MAX + 10;
            if (reserveDisplays(2, lifetime)) {
                Player owner = player();
                fist = Displays.block(ctx(), owner, plain(start), pistonFacingUp(), pointPose(direction, FIST_SCALE),
                        lifetime);
                fist.setTeleportDuration(1);
                fist.setBrightness(null);
                flame = Displays.block(ctx(), owner, plain(start), FIST_FLAME.createBlockData(),
                        cuffPose(direction, FIST_SCALE), lifetime);
                flame.setTeleportDuration(1);
            } else {
                fist = null;
                flame = null;
            }
        }

        @Override
        public boolean test(long tick) {
            double step = Math.min(PUNCH_SPEED, remaining);
            if (step <= 1.0E-3) {
                recall();
                return false;
            }
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.NEVER, true);
            double travel = wall == null ? step : wall.getHitPosition().distance(position.toVector());
            int samples = Math.max(1, (int) Math.ceil(travel / 0.4));
            for (int i = 1; i <= samples; i++) {
                Location probe = position.clone().add(direction.clone().multiply(travel * i / samples));
                List<LivingEntity> near = ctx().enemiesNear(player(), probe, PUNCH_HITBOX);
                if (!near.isEmpty()) {
                    moveTo(probe);
                    smash(near.getFirst());
                    recall();
                    return false;
                }
            }
            Location from = position.clone();
            moveTo(position.clone().add(direction.clone().multiply(travel)));
            remaining -= travel;
            jet(from, tick);
            if (wall != null) {
                moveTo(position.clone().subtract(direction.clone().multiply(0.3)));
                clank();
                recall();
                return false;
            }
            return true;
        }

        private void moveTo(Location to) {
            position.setX(to.getX());
            position.setY(to.getY());
            position.setZ(to.getZ());
            for (BlockDisplay display : new BlockDisplay[] {fist, flame}) {
                if (display != null && display.isValid()) {
                    display.teleport(plain(position));
                }
            }
        }

        /** The jet behind the fist: flame, a hot streak and a smoke trail. */
        private void jet(Location from, long tick) {
            Vector back = direction.clone().multiply(-1);
            Location tail = position.clone().add(back.clone().multiply(FIST_SCALE * 0.7));
            world.spawnParticle(Particle.FLAME, tail.getX(), tail.getY(), tail.getZ(), 0, back.getX(), back.getY(),
                    back.getZ(), 0.12);
            world.spawnParticle(Particle.FLAME, tail, Vfx.n(ctx(), 2), 0.08, 0.08, 0.08, 0.02);
            Vfx.line(ctx(), from, tail, 2.5, Particle.DUST_COLOR_TRANSITION, HEAT, false);
            if (tick % 2 == 0) {
                Vfx.dot(tail, Particle.LARGE_SMOKE, null, false);
            }
            if (tick % 3 == 0) {
                Fx.sound(position, Sound.ENTITY_BLAZE_SHOOT, 0.35f, 1.3f + Fx.jitter(0.1f));
            }
        }

        /** The fist slams into an enemy: damage, a big knockback, a flash, a fire bloom and a heavy clang. */
        private void smash(LivingEntity enemy) {
            Player owner = player();
            Combat.hit(ctx(), owner, enemy, params().num("punch_damage"));
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                Vector push = direction.clone().setY(0);
                if (push.lengthSquared() < 1.0E-4) {
                    push = Motion.horizontalFacing(owner.getLocation());
                }
                Motion.push(enemy, push.normalize().multiply(PUNCH_PUSH).setY(PUNCH_LIFT), MAX_SPEED);
            }
            Location body = Fx.body(enemy);
            World w = body.getWorld();
            Vfx.burst(ctx(), body, PALETTE, 1.0, Particle.FLAME, false);
            w.spawnParticle(Particle.EXPLOSION, body, 1, 0, 0, 0, 0);
            w.spawnParticle(Particle.ELECTRIC_SPARK, body, Vfx.n(ctx(), 14), 0.3, 0.3, 0.3, 0.45);
            w.spawnParticle(Particle.CRIT, body, Vfx.n(ctx(), 12), 0.3, 0.3, 0.3, 0.5);
            aimRing(body, direction, 0.8, 16);
            aimRing(body.clone().add(direction.clone().multiply(0.6)), direction, 1.2, 20);
            Fx.sound(body, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.2f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ITEM_MACE_SMASH_GROUND, 0.8f, 0.9f + Fx.jitter(0.1f));
            owner.sendActionBar(Text.mm("<" + ORANGE + "><b>로켓 펀치!</b></" + ORANGE + ">"));
        }

        /** The fist hits a wall: sparks fly and it clangs off. */
        private void clank() {
            world.spawnParticle(Particle.ELECTRIC_SPARK, position, Vfx.n(ctx(), 10), 0.15, 0.15, 0.15, 0.4);
            world.spawnParticle(Particle.DUST, position, Vfx.n(ctx(), 6), 0.15, 0.15, 0.15, 0, SPARK_DUST);
            Fx.sound(position, Sound.BLOCK_ANVIL_LAND, 0.5f, 1.4f + Fx.jitter(0.1f));
        }

        /** The fist flies back to the pilot's arm and docks with a clank; without displays nothing needs to come back. */
        void recall() {
            if (returning) {
                return;
            }
            returning = true;
            BlockDisplay hand = fist;
            BlockDisplay cuff = flame;
            if (hand == null || !hand.isValid()) {
                return;
            }
            Player owner = player();
            Loop.repeat(ctx(), owner, 1, 1, PUNCH_RETURN_MAX, run -> {
                if (!hand.isValid() || !hand.getWorld().equals(owner.getWorld())) {
                    return false;
                }
                Location dock = rightHand(owner);
                Vector to = dock.toVector().subtract(position.toVector());
                double distance = to.length();
                if (distance <= PUNCH_RETURN_SPEED) {
                    return false;
                }
                Vector heading = to.multiply(1 / distance);
                Location from = position.clone();
                moveTo(position.clone().add(heading.clone().multiply(PUNCH_RETURN_SPEED)));
                // The fist keeps facing forward while it flies back, wrist first.
                Displays.animate(hand, pointPose(heading.clone().multiply(-1), FIST_SCALE), 1);
                if (cuff != null) {
                    Displays.animate(cuff, cuffPose(heading.clone().multiply(-1), FIST_SCALE), 1);
                }
                Vfx.line(ctx(), from, position, 1.5, Particle.DUST, ORANGE_DUST, false);
                return true;
            }, () -> dock(hand, cuff));
        }

        private void dock(BlockDisplay hand, @Nullable BlockDisplay cuff) {
            Player owner = player();
            Location at = rightHand(owner);
            for (BlockDisplay display : new BlockDisplay[] {hand, cuff}) {
                if (display != null && display.isValid()) {
                    display.teleport(plain(at));
                    Displays.shrinkAway(ctx(), owner, display, 2);
                }
            }
            Vfx.cloud(ctx(), at, Particle.WHITE_SMOKE, 4, 0.1, 0.02, null, false);
            Fx.sound(at, Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1.2f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The owner's position facing its body yaw (or its head yaw), pitch 0: the frame of body-space poses. */
    private static Location anchor(Player owner, boolean head) {
        Location at = owner.getLocation();
        at.setYaw(head ? at.getYaw() : owner.getBodyYaw());
        at.setPitch(0);
        return at;
    }

    /** A body-space point (local +Z = facing, +X = left) in world space. */
    private static Location bodyToWorld(Location anchor, Vector3f local) {
        double yaw = Math.toRadians(anchor.getYaw());
        double x = local.x() * Math.cos(yaw) - local.z() * Math.sin(yaw);
        double z = local.x() * Math.sin(yaw) + local.z() * Math.cos(yaw);
        return new Location(anchor.getWorld(), anchor.getX() + x, anchor.getY() + local.y(), anchor.getZ() + z);
    }

    /** {@code at} without rotation, so a display's transformation alone decides its orientation. */
    private static Location plain(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }

    /** A box of {@code size} centered on {@code center} (block display space), rolled around the Z axis. */
    private static Transformation box(Vector3f center, Vector3f size, float rollDegrees) {
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.toRadians(rollDegrees));
        Vector3f translation = new Vector3f(center).sub(rotation.transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, rotation, size, new Quaternionf());
    }

    /**
     * A suit piece's pose on a body of {@code scale}, grown to {@code grow} of its size and moved by {@code offset}
     * (body space, used for the fly-in).
     */
    private static Transformation piecePose(Piece piece, float scale, float grow, Vector3f offset) {
        Vector3f center = new Vector3f(piece.x(), piece.y(), piece.z()).mul(scale).add(offset);
        Vector3f size = new Vector3f(piece.sx(), piece.sy(), piece.sz()).mul(scale * Math.max(0.01f, grow));
        return box(center, size, piece.roll());
    }

    /** A block of edge {@code size} centered on the origin with its +Y axis along {@code direction}. */
    private static Transformation pointPose(Vector direction, float size) {
        Vector3f dir = new Vector3f((float) direction.getX(), (float) direction.getY(), (float) direction.getZ());
        if (dir.lengthSquared() < 1.0E-8f) {
            dir.set(0, 1, 0);
        }
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), dir.normalize());
        Vector3f translation = rotation.transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, rotation, new Vector3f(size), new Quaternionf());
    }

    /** The glowing wrist ring of the rocket fist: a flat box just behind the fist, across {@code direction}. */
    private static Transformation cuffPose(Vector direction, float size) {
        Vector3f dir = new Vector3f((float) direction.getX(), (float) direction.getY(), (float) direction.getZ());
        if (dir.lengthSquared() < 1.0E-8f) {
            dir.set(0, 1, 0);
        }
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), dir.normalize());
        Vector3f scale = new Vector3f(size * 1.15f, size * 0.22f, size * 1.15f);
        Vector3f center = rotation.transform(new Vector3f(0, -size * 0.62f, 0));
        Vector3f translation = center.sub(rotation.transform(new Vector3f(scale).mul(0.5f)));
        return new Transformation(translation, rotation, scale, new Quaternionf());
    }

    private static BlockData rod() {
        BlockData data = MISSILE_BLOCK.createBlockData();
        if (data instanceof Directional directional) {
            directional.setFacing(BlockFace.UP);
        }
        return data;
    }

    private static BlockData pistonFacingUp() {
        BlockData data = FIST_BLOCK.createBlockData();
        if (data instanceof Directional directional) {
            directional.setFacing(BlockFace.UP);
        }
        return data;
    }

    /** True if a player at its current position has room above its head for a body of {@code scale}. */
    private static boolean hasHeadroom(Player player, double scale) {
        Location at = player.getLocation();
        BoundingBox column = new BoundingBox(at.getX() - HEADROOM_HALF_WIDTH, at.getY() + 0.1,
                at.getZ() - HEADROOM_HALF_WIDTH, at.getX() + HEADROOM_HALF_WIDTH,
                at.getY() + STANDING_HEIGHT * scale + 0.05, at.getZ() + HEADROOM_HALF_WIDTH);
        return !player.getWorld().hasCollisionsIn(column);
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
