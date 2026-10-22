package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 자석술사 — magnetism. Ranged attacks sometimes slide off the magnet's field (each one charges the next polarity),
 * the active repels everything in a frontal cone or, crouching, yanks it in (iron armor makes it stronger), and the
 * ultimate raises a magnetic storm that drags every enemy around into the magnet, smashes them together and leaves
 * them sluggish.
 *
 * <p>Look: north is red, south is blue. Every polarity summons a horseshoe magnet of red and blue bars in front of
 * the caster, field-line loops bulge out of it and arcs sweep through the cone (outwards and red for a push, inwards
 * and blue for a pull). The storm spins a lodestone over the magnet's head inside two counter-rotating tilted rings
 * of red and blue charge, with iron debris orbiting closer and closer until everything slams together in a flash.
 */
public final class MagnetAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("magnet", "자석술사", Material.LODESTONE)
            .color(TextColor.color(0xFF4B5C))
            .difficulty(2)
            .passive("자기 편향",
                    "투사체와 {deflect_range}블록 이상 떨어진 적의 원거리 공격을 {deflect_chance}% 확률로 빗나가게 합니다. "
                            + "빗나가게 할 때마다 자기 충전이 쌓이고(최대 {charge_max}), 적을 붙잡은 다음 극성의 힘과 피해가 "
                            + "충전당 {charge_bonus}% 커집니다.")
            .active("극성",
                    "전방 {cone_range}블록 부채꼴({cone_angle}°)의 적에게 {polarity_damage} 피해를 주고 약 "
                            + "{push_distance}블록 밀쳐냅니다. 웅크리고 쓰면 대신 내 앞으로 끌어옵니다. 대상의 철 갑옷 "
                            + "한 부위마다 힘과 피해가 {iron_bonus}% 커집니다.",
                    7)
            .ultimate("자기 폭풍",
                    "반경 {storm_radius}블록의 적을 {storm_pull}초간 나에게 끌어모은 뒤 서로 부딪히게 해 {storm_damage} "
                            + "피해(함께 부딪힌 적 한 명마다 +{storm_crash_bonus}, 최대 {storm_crash_max}명)를 주고 "
                            + "{storm_slow_duration}초간 이동 속도를 {storm_slow}% 낮춥니다.")
            .param("deflect_chance", 25)
            .param("deflect_range", 6)
            .param("charge_max", 3)
            .param("charge_bonus", 15)
            .param("cone_range", 8)
            .param("cone_angle", 70)
            .param("polarity_damage", 3)
            .param("push_distance", 6)
            .param("iron_bonus", 10)
            .param("storm_radius", 10)
            .param("storm_pull", 1.2)
            .param("storm_damage", 5)
            .param("storm_crash_bonus", 1)
            .param("storm_crash_max", 3)
            .param("storm_slow", 40)
            .param("storm_slow_duration", 3)
            .build();

    private static final String COLOR_TAG = "#FF4B5C";
    private static final String NORTH_TAG = "#FF4B5C";
    private static final String SOUTH_TAG = "#4B8BFF";
    /** North pole: white-pink core, magnet red, dark red. */
    private static final Vfx.Palette NORTH = Vfx.Palette.of(0xFFE3E6, 0xFF3B4E, 0x6A0010);
    /** South pole: white-blue core, magnet blue, navy. */
    private static final Vfx.Palette SOUTH = Vfx.Palette.of(0xE3EEFF, 0x3B7BFF, 0x0A1F66);
    /** A field line: north red flowing into south blue. */
    private static final Particle.DustTransition FIELD = new Particle.DustTransition(NORTH.body(), SOUTH.body(), 0.9f);
    private static final Particle.DustOptions IRON_DUST = new Particle.DustOptions(Color.fromRGB(0xB8C0CC), 0.8f);

    private static final BlockData NORTH_BAR = Material.RED_CONCRETE.createBlockData();
    private static final BlockData SOUTH_BAR = Material.BLUE_CONCRETE.createBlockData();
    private static final BlockData IRON = Material.IRON_BLOCK.createBlockData();
    private static final BlockData LODESTONE = Material.LODESTONE.createBlockData();
    private static final BlockData[] DEBRIS = {
            Material.IRON_BLOCK.createBlockData(),
            Material.RAW_IRON_BLOCK.createBlockData(),
            Material.ANVIL.createBlockData(),
            Material.IRON_BARS.createBlockData(),
            Material.HEAVY_CORE.createBlockData(),
            Material.IRON_CHAIN.createBlockData(),
    };

    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;
    /** Horizontal launch speed per block of push distance (with {@link #PUSH_LIFT}, about seven blocks per 1.0). */
    private static final double PUSH_SPEED_PER_BLOCK = 0.14;
    private static final double PUSH_LIFT = 0.42;

    // active: polarity
    private static final int MAGNET_TICKS = 12;
    private static final int SWEEP_TICKS = 6;
    private static final int PULL_MAX_TICKS = 12;
    private static final double PULL_SPEED = 1.4;
    private static final double PULL_ARRIVE = 1.3;
    private static final double PULL_STOP = 1.8;
    private static final int TRAIL_TICKS = 6;
    private static final int DEFLECT_TICKS = 8;
    /**
     * Minimum ticks between two deflection shows (bars, arcs, sounds): a volley or a burning beam is deflected hit by
     * hit, but drawn at most this often so its displays stay few.
     */
    private static final long DEFLECT_FX_COOLDOWN = 5;
    /** How far above or below the eyes the polarity fan reaches. */
    private static final double CONE_HEIGHT = 2.5;

    // ultimate: magnetic storm
    private static final double CORE_HEIGHT = 2.9;
    private static final float CORE_SIZE = 0.6f;
    private static final double DEBRIS_ORBIT = 2.6;
    private static final int POSE_PERIOD = 2;
    /** Enemies pulled into the magnet stop this far from its centre. */
    private static final double STORM_HOLD = 1.7;
    private static final double STORM_SPEED = 0.95;
    /** Pulled enemies within this distance of each other at the crash count as having collided. */
    private static final double CRASH_RADIUS = 2.6;
    private static final double CRASH_PUSH = 0.55;
    private static final Vector3f CORE_AXIS = new Vector3f(0.25f, 1f, 0.15f).normalize();

    /** Charges stored by deflections, consumed by the next polarity. */
    private int charges;
    private long lastDeflectFx = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        charges = 0;
    }

    // ------------------------------------------------------------------ passive: magnetic deflection

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0 || !isRangedHit(event)) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() * 100.0 >= params().num("deflect_chance")) {
            return;
        }
        event.setCancelled(true);
        Player owner = player();
        int max = Math.max(0, params().integer("charge_max"));
        if (charges < max) {
            charges++;
        }
        long now = ctx().currentTick();
        if (now - lastDeflectFx < DEFLECT_FX_COOLDOWN) {
            return;
        }
        lastDeflectFx = now;
        Vector incoming = event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager().getWorld().equals(owner.getWorld())
                ? owner.getLocation().toVector().subtract(byEntity.getDamager().getLocation().toVector())
                : owner.getLocation().getDirection();
        deflectFx(owner, incoming);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⇋ 자기 편향!</" + COLOR_TAG + "> <gray>충전 " + charges + "/"
                + max + "</gray>"));
    }

    @Override
    public void onTick(long tick) {
        if (charges <= 0 || tick % 4 != 0) {
            return;
        }
        // Stored charge: tiny red and blue motes circling the waist, one pair per charge.
        Location waist = player().getLocation().add(0, 0.9, 0);
        for (int i = 0; i < charges; i++) {
            double a = tick * 0.2 + i * Math.PI * 2 / charges;
            Location north = waist.clone().add(Math.cos(a) * 0.6, 0.15 * Math.sin(a * 2), Math.sin(a) * 0.6);
            Location south = waist.clone().add(-Math.cos(a) * 0.6, -0.15 * Math.sin(a * 2), -Math.sin(a) * 0.6);
            Vfx.dot(north, Particle.DUST, NORTH.dust(0.6f), false);
            Vfx.dot(south, Particle.DUST, SOUTH.dust(0.6f), false);
        }
    }

    /**
     * A vanilla projectile, or a hit whose direct damager is an attacker at least {@code deflect_range} blocks away
     * (ability "projectiles" are delivered as ability damage from the caster, so the distance tells them from melee).
     */
    private boolean isRangedHit(EntityDamageEvent event) {
        if (event.getCause() == DamageCause.PROJECTILE) {
            return true;
        }
        Tag<DamageType> projectile = DamageTypeTags.IS_PROJECTILE;
        if (projectile != null && projectile.isTagged(event.getDamageSource().getDamageType())) {
            return true;
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return false;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof Projectile) {
            return true;
        }
        Player owner = player();
        Player attacker = ctx().resolveAttacker(damager);
        if (attacker == null || attacker.equals(owner) || !damager.getWorld().equals(owner.getWorld())) {
            return false;
        }
        double range = params().num("deflect_range");
        return damager.getLocation().distanceSquared(owner.getLocation()) >= range * range;
    }

    /** The attack slides around the body: a red-blue arc bends past it, sparks fly and the field rings. */
    private void deflectFx(Player owner, Vector incoming) {
        Location chest = Fx.body(owner);
        Vector in = Motion.horizontal(incoming);
        if (in.lengthSquared() < 1.0E-6) {
            in = Motion.horizontalFacing(owner.getLocation()).multiply(-1);
        }
        Vector side = new Vector(-in.getZ(), 0, in.getX());
        if (ThreadLocalRandom.current().nextBoolean()) {
            side.multiply(-1);
        }
        // The bent path: comes in, curves around the shoulder and leaves to the side.
        Location before = chest.clone().subtract(in.clone().multiply(1.6));
        Location around = chest.clone().add(side.clone().multiply(0.8));
        Location after = chest.clone().add(in.clone().multiply(1.4)).add(side.clone().multiply(1.6));
        Vfx.trail(before, around, NORTH.body(), 3, false);
        Vfx.trail(around, after, SOUTH.body(), 4, false);
        Vfx.line(ctx(), before, around, 3, Particle.DUST_COLOR_TRANSITION, FIELD, false);
        Vfx.line(ctx(), around, after, 3, Particle.DUST_COLOR_TRANSITION, FIELD, false);
        Vfx.ring(ctx(), chest, 0.65, 14, 0, Particle.DUST, NORTH.dust(0.7f), false);
        Vfx.ring(ctx(), chest.clone().add(0, -0.35, 0), 0.65, 14, 0.3, Particle.DUST, SOUTH.dust(0.7f), false);
        Vfx.cloud(ctx(), around, Particle.ELECTRIC_SPARK, 6, 0.15, 0.1, null, false);
        // Two charged bars, red and blue, cross where the attack was turned aside and spin apart.
        Location cross = chest.clone().add(side.clone().multiply(0.55)).subtract(in.clone().multiply(0.3));
        cross.setYaw(0);
        cross.setPitch(0);
        float yaw = (float) Math.toDegrees(Math.atan2(-in.getX(), in.getZ()));
        for (int i = 0; i < 2; i++) {
            float roll = i == 0 ? 45f : -45f;
            BlockDisplay bar = Displays.block(ctx(), owner, cross, i == 0 ? NORTH_BAR : SOUTH_BAR,
                    Displays.transform(0, 0, 0, 0.06f, 0.5f, 0.06f, yaw, 0, roll), DEFLECT_TICKS + 4);
            Displays.outline(bar, i == 0 ? NORTH.body() : SOUTH.body());
            Displays.animateNextTick(ctx(), owner, bar,
                    Displays.transform(0, 0, 0, 0.07f, 0.95f, 0.07f, yaw, 0, roll + (i == 0 ? 60f : -60f)),
                    DEFLECT_TICKS - 2);
            ctx().runLater(owner, DEFLECT_TICKS - 2, () -> {
                if (bar.isValid()) {
                    Displays.shrinkAway(ctx(), owner, bar, 3);
                }
            });
        }
        Fx.sound(chest, Sound.ITEM_LODESTONE_COMPASS_LOCK, 1.0f, 1.4f + Fx.jitter(0.1f));
        Fx.sound(chest, Sound.BLOCK_CHAIN_HIT, 0.8f, 1.6f + Fx.jitter(0.15f));
    }

    // ------------------------------------------------------------------ active: polarity

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        boolean pull = owner.isSneaking();
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        double range = Math.max(1, params().num("cone_range"));
        double halfAngle = Math.toRadians(Math.clamp(params().num("cone_angle"), 1.0, 360.0) / 2);

        // A horizontal fan in front of the magnet (whatever the pitch), reaching a little above and below.
        Vector facing = Motion.horizontal(look);
        if (facing.lengthSquared() < 1.0E-6) {
            facing = Motion.horizontalFacing(eye);
        }
        List<LivingEntity> caught = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, eye, range)) {
            Location body = Fx.body(enemy);
            Vector to = body.toVector().subtract(eye.toVector());
            Vector flat = Motion.horizontal(to);
            boolean close = Math.hypot(to.getX(), to.getZ()) < 0.8;
            boolean inCone = close || flat.lengthSquared() > 0 && flat.angle(facing) <= halfAngle;
            if (inCone && Math.abs(to.getY()) <= CONE_HEIGHT && Spots.clearPath(eye, body)) {
                caught.add(enemy);
            }
        }

        // Stored charge empowers this polarity, but a polarity that catches nobody keeps it.
        boolean charged = charges > 0 && !caught.isEmpty();
        double charge = charged ? 1 + charges * Math.max(0, params().num("charge_bonus")) / 100.0 : 1.0;
        if (charged) {
            charges = 0;
        }
        Pole pole = pull ? Pole.SOUTH : Pole.NORTH;
        castFx(owner, eye, look, range, halfAngle, pole, charged);
        double damage = params().num("polarity_damage");
        List<LivingEntity> pulled = new ArrayList<>();
        for (LivingEntity enemy : caught) {
            double force = charge * ironFactor(enemy);
            Combat.hit(ctx(), owner, enemy, damage * force);
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            hitFx(enemy, pole, ironPieces(enemy) > 0);
            if (pull) {
                pulled.add(enemy);
            } else {
                repel(owner, enemy, force);
            }
        }
        if (!pulled.isEmpty()) {
            yank(pulled);
        }
        if (!caught.isEmpty()) {
            String tag = pull ? SOUTH_TAG : NORTH_TAG;
            owner.sendActionBar(Text.mm("<" + tag + ">" + (pull ? "S극 · 끌어당기기" : "N극 · 밀어내기") + " <white>"
                    + caught.size() + "명</white></" + tag + ">" + (charged ? " <gray>(충전 강화)</gray>" : "")));
        }
        return ActivationResult.success();
    }

    /** The pole a skill uses: north (red) repels, south (blue) attracts. */
    private enum Pole {
        NORTH(MagnetAbility.NORTH), SOUTH(MagnetAbility.SOUTH);

        private final Vfx.Palette colors;

        Pole(Vfx.Palette colors) {
            this.colors = colors;
        }
    }

    /** Knockback away from the magnet (respects knockback resistance), with a short red streak behind the target. */
    private void repel(Player owner, LivingEntity enemy, double force) {
        Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(owner.getLocation().toVector()));
        if (away.lengthSquared() < 1.0E-6) {
            away = Motion.horizontalFacing(owner.getLocation());
        }
        double speed = Math.max(0, params().num("push_distance")) * PUSH_SPEED_PER_BLOCK * force;
        if (!Motion.push(enemy, away.multiply(speed).setY(PUSH_LIFT), MAX_SPEED)) {
            return;
        }
        Loop.repeat(ctx(), owner, 1, 1, TRAIL_TICKS, run -> {
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return false;
            }
            Vfx.dot(Fx.body(enemy), Particle.DUST_COLOR_TRANSITION, NORTH.glow(1.0f), false);
            Vfx.dot(enemy.getLocation().add(0, 0.2, 0), Particle.ELECTRIC_SPARK, null, false);
            return true;
        });
    }

    /**
     * The pull: the targets are steered to a spot in front of the magnet over a few ticks (crowd control: ignores
     * knockback resistance), each tethered by a blue stream of motes.
     */
    private void yank(List<LivingEntity> targets) {
        Player owner = player();
        Map<UUID, LivingEntity> moving = new LinkedHashMap<>();
        for (LivingEntity target : targets) {
            moving.put(target.getUniqueId(), target);
        }
        Loop.repeat(ctx(), owner, 0, 1, PULL_MAX_TICKS, run -> {
            Location destination = pullDestination(owner);
            for (var it = moving.values().iterator(); it.hasNext(); ) {
                LivingEntity target = it.next();
                if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(owner.getWorld())) {
                    it.remove();
                    continue;
                }
                Vector to = destination.toVector().subtract(target.getLocation().toVector());
                double distance = to.length();
                if (distance <= PULL_ARRIVE || run >= PULL_MAX_TICKS - 1) {
                    arrive(target);
                    it.remove();
                    continue;
                }
                double speed = Math.min(PULL_SPEED, Math.max(0.3, distance * 0.45));
                Vector velocity = Motion.horizontal(to).multiply(speed);
                boolean grounded = Motion.isGrounded(target) || target.isInWater();
                velocity.setY(Math.clamp(to.getY() * 0.3, -0.6, 0.6) + (grounded ? 0.2 : 0.0));
                target.setVelocity(Motion.clamp(velocity, MAX_SPEED));
                target.setFallDistance(0);
                Vfx.trail(Fx.body(target), hand(owner), SOUTH.body(), 4, false);
                if (run % 2 == 0) {
                    Vfx.dot(Fx.body(target), Particle.DUST_COLOR_TRANSITION, SOUTH.glow(0.9f), false);
                }
            }
            return !moving.isEmpty();
        });
    }

    private void arrive(LivingEntity target) {
        target.setVelocity(new Vector(0, Math.min(0, target.getVelocity().getY()), 0));
        target.setFallDistance(0);
        Location body = Fx.body(target);
        Vfx.dot(body, Particle.FLASH, SOUTH.body(), false);
        Vfx.ring(ctx(), target.getLocation().add(0, 0.1, 0), 0.8, 14, 0, Particle.DUST_COLOR_TRANSITION,
                SOUTH.glow(1.0f), false);
        Fx.sound(body, Sound.BLOCK_ANVIL_LAND, 0.35f, 1.9f + Fx.jitter(0.1f));
    }

    /** The spot in front of the magnet where pulled enemies are dropped (its own spot if a wall is in the way). */
    private static Location pullDestination(Player owner) {
        Location feet = owner.getLocation();
        Vector facing = Motion.horizontal(feet.getDirection());
        if (facing.lengthSquared() < 1.0E-6) {
            return feet;
        }
        RayTraceResult wall = owner.getWorld().rayTraceBlocks(feet.clone().add(0, 0.5, 0), facing, PULL_STOP,
                FluidCollisionMode.NEVER, true);
        return wall != null ? feet : feet.add(facing.multiply(PULL_STOP));
    }

    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.add(eye.getDirection().multiply(0.6)).subtract(0, 0.35, 0);
    }

    /** 1 plus {@code iron_bonus} per iron (or chainmail) armor piece the target wears; an iron golem counts as four. */
    private double ironFactor(LivingEntity target) {
        return 1 + ironPieces(target) * Math.max(0, params().num("iron_bonus")) / 100.0;
    }

    private static int ironPieces(LivingEntity target) {
        if (target instanceof IronGolem) {
            return 4;
        }
        EntityEquipment equipment = target.getEquipment();
        if (equipment == null) {
            return 0;
        }
        int pieces = 0;
        for (ItemStack armor : new ItemStack[] {equipment.getHelmet(), equipment.getChestplate(),
                equipment.getLeggings(), equipment.getBoots()}) {
            if (armor != null && isIron(armor.getType())) {
                pieces++;
            }
        }
        return pieces;
    }

    private static boolean isIron(Material type) {
        return switch (type) {
            case IRON_HELMET, IRON_CHESTPLATE, IRON_LEGGINGS, IRON_BOOTS, CHAINMAIL_HELMET, CHAINMAIL_CHESTPLATE,
                 CHAINMAIL_LEGGINGS, CHAINMAIL_BOOTS -> true;
            default -> false;
        };
    }

    /**
     * The cast: a horseshoe magnet of red and blue bars appears in the hands, field-line loops bulge out of it and arcs
     * sweep through the cone (outwards and red for a push, inwards and blue for a pull).
     */
    private void castFx(Player owner, Location eye, Vector look, double range, double halfAngle, Pole pole,
                        boolean charged) {
        boolean pull = pole == Pole.SOUTH;
        Location hand = hand(owner);
        horseshoe(owner, hand, eye.getYaw(), eye.getPitch(), pole);
        fieldLoops(hand, look);
        double width = Math.toDegrees(halfAngle) * 2;
        Location chest = eye.clone().subtract(0, 0.45, 0);
        Loop.repeat(ctx(), owner, 0, 1, SWEEP_TICKS, run -> {
            double t = (double) (run + 1) / SWEEP_TICKS;
            double radius = pull ? range * (1 - t) + 0.8 * t : 0.8 + (range - 0.8) * t;
            int points = (int) Math.max(6, Math.min(28, radius * width / 18));
            Vfx.arc(ctx(), chest, radius, eye.getYaw(), width, points, Particle.DUST_COLOR_TRANSITION,
                    pole.colors.glow(1.1f), false);
            Vfx.arc(ctx(), chest.clone().add(0, 0.5, 0), radius * 0.92, eye.getYaw(), width * 0.85,
                    Math.max(4, points / 2), Particle.DUST, pole.colors.dust(0.8f), false);
            return true;
        });
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int motes = Vfx.n(ctx(), 10);
        for (int i = 0; i < motes; i++) {
            double yaw = Math.toRadians(eye.getYaw()) + (random.nextDouble() * 2 - 1) * halfAngle;
            Vector direction = new Vector(-Math.sin(yaw), look.getY() + random.nextGaussian() * 0.15, Math.cos(yaw));
            Location far = hand.clone().add(direction.normalize().multiply(range * (0.6 + random.nextDouble() * 0.4)));
            if (pull) {
                Vfx.trail(far, hand, i % 2 == 0 ? SOUTH.body() : SOUTH.core(), 7 + random.nextInt(3), false);
            } else {
                Vfx.trail(hand, far, i % 2 == 0 ? NORTH.body() : NORTH.core(), 6 + random.nextInt(3), false);
            }
        }
        Vfx.dot(hand, Particle.FLASH, pole.colors.body(), false);
        Vfx.cloud(ctx(), hand, Particle.ELECTRIC_SPARK, charged ? 16 : 8, 0.2, 0.15, null, false);
        Fx.sound(eye, Sound.ITEM_LODESTONE_COMPASS_LOCK, 1.2f, (pull ? 0.7f : 1.1f) + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, (pull ? 0.6f : 1.4f) + Fx.jitter(0.05f));
        if (pull) {
            Fx.sound(eye, Sound.BLOCK_PISTON_CONTRACT, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(eye, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.7f, 1.5f + Fx.jitter(0.1f));
        } else {
            Fx.sound(eye, Sound.BLOCK_PISTON_EXTEND, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(eye, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.8f, 1.3f + Fx.jitter(0.1f));
        }
        if (charged) {
            Fx.sound(eye, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.6f);
        }
    }

    /**
     * A horseshoe magnet held in front of the caster: a red arm (north) and a blue arm (south) joined by an iron
     * bridge, with iron tips glowing in the color of the pole being used. Five block displays turned with the view.
     */
    private void horseshoe(Player owner, Location at, float yaw, float pitch, Pole pole) {
        Location anchor = at.clone();
        anchor.setYaw(0);
        anchor.setPitch(0);
        Quaternionf facing = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch));
        float arm = 0.12f;
        float gap = 0.13f;
        List<BlockDisplay> parts = new ArrayList<>();
        parts.add(magnetPart(owner, anchor, NORTH_BAR, facing, -gap, 0, 0.12f, arm, arm, 0.36f));
        parts.add(magnetPart(owner, anchor, SOUTH_BAR, facing, gap, 0, 0.12f, arm, arm, 0.36f));
        parts.add(magnetPart(owner, anchor, IRON, facing, 0, 0, -0.1f, gap * 2 + arm, arm, arm));
        BlockDisplay northTip = magnetPart(owner, anchor, IRON, facing, -gap, 0, 0.35f, arm + 0.02f, arm + 0.02f, 0.1f);
        BlockDisplay southTip = magnetPart(owner, anchor, IRON, facing, gap, 0, 0.35f, arm + 0.02f, arm + 0.02f, 0.1f);
        Displays.outline(northTip, pole.colors.body());
        Displays.outline(southTip, pole.colors.body());
        parts.add(northTip);
        parts.add(southTip);
        for (BlockDisplay part : parts) {
            ctx().runLater(owner, MAGNET_TICKS - 4L, () -> {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 4);
                }
            });
        }
    }

    /** One part of the horseshoe: it snaps out of the hand (from a third of its size) into place. */
    private BlockDisplay magnetPart(Player owner, Location anchor, BlockData data, Quaternionf facing, float cx,
                                    float cy, float cz, float sx, float sy, float sz) {
        BlockDisplay part = Displays.block(ctx(), owner, anchor, data,
                framed(facing, cx * 0.3f, cy * 0.3f, cz * 0.3f, sx * 0.3f, sy * 0.3f, sz * 0.3f), MAGNET_TICKS);
        Displays.animateNextTick(ctx(), owner, part, framed(facing, cx, cy, cz, sx, sy, sz), 3);
        return part;
    }

    /** A box of the given size centered at {@code (cx, cy, cz)} in the frame turned by {@code facing}. */
    private static Transformation framed(Quaternionf facing, float cx, float cy, float cz, float sx, float sy,
                                         float sz) {
        Vector3f size = new Vector3f(sx, sy, sz);
        Vector3f translation = new Quaternionf(facing).transform(new Vector3f(cx, cy, cz))
                .sub(new Quaternionf(facing).transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, new Quaternionf(facing), size, new Quaternionf());
    }

    /** Three field-line loops bulging forward from the magnet, red at the north end and blue at the south. */
    private void fieldLoops(Location hand, Vector look) {
        Vector right = look.getCrossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vector(1, 0, 0);
        }
        right.normalize();
        Vector up = right.getCrossProduct(look).normalize();
        World world = hand.getWorld();
        int points = Vfx.n(ctx(), 14);
        for (int loop = 0; loop < 3; loop++) {
            double reach = 0.9 + loop * 0.7;
            double width = 0.35 + loop * 0.25;
            Vector tilt = up.clone().multiply((loop - 1) * 0.25);
            for (int i = 0; i <= points; i++) {
                double t = Math.PI * i / points;
                Vector offset = right.clone().multiply(-Math.cos(t) * width)
                        .add(look.clone().multiply(Math.sin(t) * reach)).add(tilt.clone().multiply(Math.sin(t)));
                Location at = hand.clone().add(offset);
                Color from = i < points / 2 ? NORTH.body() : SOUTH.body();
                world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(from, 0.7f));
            }
        }
    }

    private void hitFx(LivingEntity enemy, Pole pole, boolean iron) {
        Location body = Fx.body(enemy);
        Vfx.ring(ctx(), body, 0.6, 14, ThreadLocalRandom.current().nextDouble(Math.PI), Particle.DUST_COLOR_TRANSITION,
                pole.colors.glow(1.0f), false);
        Vfx.cloud(ctx(), body, Particle.ELECTRIC_SPARK, 6, 0.25, 0.1, null, false);
        Vfx.cloud(ctx(), body, Particle.DUST, 4, 0.25, 0, IRON_DUST, false);
        Vfx.trail(hand(player()), body, pole.colors.core(), 4, false);
        if (iron) {
            // Iron rings as the field grabs the armor.
            Fx.sound(body, Sound.BLOCK_ANVIL_LAND, 0.35f, 1.7f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.BLOCK_CHAIN_HIT, 0.8f, 1.2f + Fx.jitter(0.1f));
        } else {
            Fx.sound(body, Sound.ENTITY_SHULKER_BULLET_HIT, 0.7f, 1.3f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ ultimate: magnetic storm

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = params().num("storm_radius");
        List<LivingEntity> caught = ctx().enemiesNear(owner, owner.getLocation(), radius);
        if (caught.isEmpty()) {
            return ActivationResult.fail("자기 폭풍 범위(" + AbilityInfo.formatNumber(radius) + "블록) 안에 적이 없습니다");
        }
        Storm storm = new Storm(radius, caught);
        storm.open();
        return ActivationResult.success();
    }

    /**
     * The storm: drags every caught enemy into the magnet for {@code storm_pull}, then smashes them together.
     */
    private final class Storm {

        private final double radius;
        private final long pullTicks;
        private final Map<UUID, LivingEntity> caught = new LinkedHashMap<>();
        private final BlockDisplay[] debris = new BlockDisplay[DEBRIS.length];
        private @Nullable BlockDisplay core;
        private final double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);

        Storm(double radius, List<LivingEntity> targets) {
            this.radius = radius;
            this.pullTicks = Math.max(1, params().ticks("storm_pull"));
            for (LivingEntity target : targets) {
                caught.put(target.getUniqueId(), target);
            }
        }

        void open() {
            Player owner = player();
            Location feet = owner.getLocation();
            long life = pullTicks + 12;
            Location coreAt = coreAt();
            core = Displays.block(ctx(), owner, coreAt, LODESTONE, cube(0.05f, new Quaternionf()), life);
            core.setTeleportDuration(1);
            Displays.outline(core, NORTH.body());
            core.setViewRange(2.0f);
            for (int i = 0; i < debris.length; i++) {
                debris[i] = Displays.block(ctx(), owner, debrisPoint(i, 0, DEBRIS_ORBIT), DEBRIS[i],
                        cube(0.3f, randomRotation()), life + 10);
                debris[i].setTeleportDuration(POSE_PERIOD);
            }
            // Telegraph: the storm's reach spreads over the ground in red and blue.
            Location ground = feet.clone().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, ground, 0.6, radius, 8, Particle.DUST_COLOR_TRANSITION, FIELD, true);
            Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.3, 0), 0.4, radius * 0.9, 9, Particle.DUST,
                    SOUTH.dust(1.1f), true);
            Vfx.dot(coreAt, Particle.FLASH, NORTH.body(), true);
            Vfx.cloud(ctx(), coreAt, Particle.ELECTRIC_SPARK, 30, 0.6, 0.3, null, true);
            Fx.sound(feet, Sound.BLOCK_BEACON_ACTIVATE, 1.3f, 0.8f);
            Fx.sound(feet, Sound.ITEM_LODESTONE_COMPASS_LOCK, 1.5f, 0.5f);
            Fx.sound(feet, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(feet, Sound.ENTITY_WARDEN_SONIC_CHARGE, 0.8f, 1.6f);
            for (LivingEntity target : caught.values()) {
                Vfx.ring(ctx(), Fx.body(target), 0.9, 14, 0, Particle.DUST_COLOR_TRANSITION, FIELD, true);
                Fx.sound(Fx.body(target), Sound.BLOCK_CHAIN_PLACE, 0.8f, 0.8f + Fx.jitter(0.1f));
            }
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⇋ 자기 폭풍 <white>" + caught.size() + "명</white></"
                    + COLOR_TAG + ">"));
            Loop.repeat(ctx(), owner, 1, 1, pullTicks, this::pull, this::crash);
        }

        private Location coreAt() {
            Location at = player().getLocation().add(0, CORE_HEIGHT, 0);
            at.setYaw(0);
            at.setPitch(0);
            return at;
        }

        /** One tick of the pull: every caught enemy is dragged towards the magnet. */
        private boolean pull(long tick) {
            Player owner = player();
            Location center = owner.getLocation();
            double progress = (double) (tick + 1) / pullTicks;
            for (var it = caught.values().iterator(); it.hasNext(); ) {
                LivingEntity enemy = it.next();
                if (!Combat.isTargetable(ctx(), owner, enemy) || !enemy.getWorld().equals(owner.getWorld())) {
                    it.remove();
                    continue;
                }
                Vector to = center.toVector().subtract(enemy.getLocation().toVector());
                double distance = Math.sqrt(to.getX() * to.getX() + to.getZ() * to.getZ());
                Vector velocity;
                if (distance <= STORM_HOLD) {
                    velocity = new Vector(0, Math.min(0, enemy.getVelocity().getY()), 0);
                } else {
                    double speed = Math.min(STORM_SPEED, Math.max(0.25, (distance - STORM_HOLD) * 0.35));
                    velocity = Motion.horizontal(to).multiply(speed);
                    boolean grounded = Motion.isGrounded(enemy) || enemy.isInWater();
                    velocity.setY(Math.clamp(to.getY() * 0.25, -0.5, 0.4) + (grounded ? 0.15 : 0.0));
                }
                enemy.setVelocity(Motion.clamp(velocity, MAX_SPEED));
                enemy.setFallDistance(0);
                if (tick % 2 == 0) {
                    Vfx.trail(Fx.body(enemy), coreAt(), (tick / 2) % 2 == 0 ? NORTH.body() : SOUTH.body(), 7, true);
                }
                if (tick % 4 == 0) {
                    Vfx.ring(ctx(), enemy.getLocation().add(0, 0.1, 0), 0.55, 10, tick * 0.3, Particle.DUST,
                            (tick / 4) % 2 == 0 ? NORTH.dust(0.8f) : SOUTH.dust(0.8f), false);
                }
            }
            if (tick % POSE_PERIOD == 0) {
                pose(tick + POSE_PERIOD, progress);
            }
            drawRings(tick, progress);
            if (tick % 6 == 0) {
                Fx.sound(center, Sound.BLOCK_BEACON_AMBIENT, 1.0f, (float) (0.8 + progress));
                Fx.sound(center, Sound.BLOCK_CHAIN_HIT, 0.6f, (float) (0.6 + progress) + Fx.jitter(0.1f));
            }
            return true;
        }

        /** The lodestone spins over the magnet's head; the iron debris orbits ever closer. */
        private void pose(long targetTick, double progress) {
            if (core != null && core.isValid()) {
                core.teleport(coreAt());
                float grow = (float) Math.min(1.0, targetTick / 6.0);
                Quaternionf spin = new Quaternionf().rotateAxis(targetTick * 0.25f, CORE_AXIS);
                Displays.animate(core, cube(CORE_SIZE * grow, spin), POSE_PERIOD);
            }
            double orbit = DEBRIS_ORBIT * (1 - 0.5 * progress);
            for (int i = 0; i < debris.length; i++) {
                BlockDisplay piece = debris[i];
                if (piece == null || !piece.isValid()) {
                    continue;
                }
                piece.teleport(debrisPoint(i, targetTick, orbit));
                Transformation current = piece.getTransformation();
                Quaternionf turn = new Quaternionf(current.getLeftRotation()).rotateAxis(0.45f, CORE_AXIS);
                Displays.animate(piece, cube(current.getScale().x(), turn.normalize()), POSE_PERIOD);
            }
        }

        /** Two tilted rings of charge spinning in opposite directions, with sparks jumping between them. */
        private void drawRings(long tick, double progress) {
            Location center = player().getLocation().add(0, 1.0, 0);
            World world = center.getWorld();
            double r = 2.8 - 0.8 * progress;
            int points = Vfx.n(ctx(), 18);
            for (int i = 0; i < points; i++) {
                double a = phase + tick * 0.3 + i * Math.PI * 2 / points;
                double b = phase - tick * 0.3 + i * Math.PI * 2 / points;
                world.spawnParticle(Particle.DUST, center.getX() + Math.cos(a) * r,
                        center.getY() + Math.sin(a) * r * 0.35, center.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0,
                        NORTH.dust(0.9f), true);
                world.spawnParticle(Particle.DUST, center.getX() + Math.cos(b) * r * 1.1,
                        center.getY() - Math.sin(b) * r * 0.35, center.getZ() + Math.sin(b) * r * 1.1, 1, 0, 0, 0, 0,
                        SOUTH.dust(0.9f), true);
            }
            if (tick % 3 == 0) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                double a = random.nextDouble(Math.PI * 2);
                Location from = center.clone().add(Math.cos(a) * r, Math.sin(a) * r * 0.35, Math.sin(a) * r);
                Location to = coreAt();
                Vfx.line(ctx(), from, to, 3, Particle.ELECTRIC_SPARK, null, false);
            }
        }

        /** The pull is over: everything slams together. */
        private void crash() {
            Player owner = player();
            Location center = owner.getLocation();
            Location coreAt = coreAt();
            List<LivingEntity> hit = new ArrayList<>();
            for (LivingEntity enemy : caught.values()) {
                if (Combat.isTargetable(ctx(), owner, enemy) && enemy.getWorld().equals(owner.getWorld())
                        && enemy.getLocation().distance(center) <= radius) {
                    hit.add(enemy);
                }
            }
            double damage = params().num("storm_damage");
            double crashBonus = Math.max(0, params().num("storm_crash_bonus"));
            int crashMax = Math.max(0, params().integer("storm_crash_max"));
            double slow = Math.clamp(params().num("storm_slow") / 100.0, 0.0, 1.0);
            long slowTicks = params().ticks("storm_slow_duration");
            for (LivingEntity enemy : hit) {
                int crashed = 0;
                for (LivingEntity other : hit) {
                    if (other != enemy && other.getLocation().distance(enemy.getLocation()) <= CRASH_RADIUS) {
                        crashed++;
                    }
                }
                Combat.hit(ctx(), owner, enemy, damage + Math.min(crashed, crashMax) * crashBonus);
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                if (slow > 0 && slowTicks > 0) {
                    ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow,
                            AttributeModifier.Operation.MULTIPLY_SCALAR_1, slowTicks);
                    magnetized(enemy, slowTicks);
                }
                // The poles flip: the pile bursts apart a little instead of staying stacked inside the magnet.
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(center.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                    away = new Vector(Math.cos(angle), 0, Math.sin(angle));
                }
                Motion.push(enemy, away.multiply(CRASH_PUSH).setY(0.25), MAX_SPEED);
                Location body = Fx.body(enemy);
                Vfx.dot(body, Particle.FLASH, Color.WHITE, false);
                Vfx.cloud(ctx(), body, Particle.CRIT, 10, 0.3, 0.3, null, false);
            }
            flingDebris();
            if (core != null && core.isValid()) {
                Displays.shrinkAway(ctx(), owner, core, 4);
            }
            Location ground = center.clone().add(0, 0.1, 0);
            Vfx.dot(coreAt, Particle.FLASH, Color.WHITE, true);
            Vfx.dot(Fx.body(owner), Particle.EXPLOSION, null, true);
            Vfx.sphere(ctx(), coreAt, 1.2, 36, Particle.DUST_COLOR_TRANSITION, FIELD, true);
            Vfx.cloud(ctx(), center.clone().add(0, 1, 0), Particle.ELECTRIC_SPARK, 40, 1.2, 0.4, null, true);
            Vfx.shockwave(ctx(), owner, ground, 0.6, 4.5, 6, Particle.DUST_COLOR_TRANSITION, NORTH.glow(1.4f), true);
            Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.25, 0), 0.4, 4.0, 7, Particle.DUST_COLOR_TRANSITION,
                    SOUTH.glow(1.3f), true);
            Fx.sound(center, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.4f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.BLOCK_ANVIL_LAND, 1.2f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.7f);
            if (!hit.isEmpty() && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⇋ 자기 폭풍 충돌 <white>" + hit.size()
                        + "명!</white></" + COLOR_TAG + ">"));
            }
        }

        private void flingDebris() {
            Player owner = player();
            Location center = owner.getLocation().add(0, 1, 0);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (BlockDisplay piece : debris) {
                if (piece == null || !piece.isValid()) {
                    continue;
                }
                Vector out = piece.getLocation().toVector().subtract(center.toVector()).setY(0);
                if (out.lengthSquared() < 1.0E-4) {
                    out = new Vector(random.nextGaussian(), 0, random.nextGaussian());
                }
                out.normalize().multiply(3 + random.nextDouble() * 1.5).setY(0.4 + random.nextDouble() * 0.8);
                piece.setTeleportDuration(6);
                piece.teleport(piece.getLocation().add(out));
                Transformation current = piece.getTransformation();
                Displays.animate(piece, cube(current.getScale().x(), randomRotation()), 6);
                ctx().runLater(owner, 6, () -> {
                    if (piece.isValid()) {
                        Displays.shrinkAway(ctx(), owner, piece, 4);
                    }
                });
            }
        }

        private Location debrisPoint(int index, long tick, double orbit) {
            double a = phase + tick * 0.22 + index * Math.PI * 2 / debris.length;
            double y = 1.0 + 0.45 * Math.sin(tick * 0.18 + index * 1.7);
            Location at = player().getLocation().add(Math.cos(a) * orbit, y, Math.sin(a) * orbit);
            at.setYaw(0);
            at.setPitch(0);
            return at;
        }
    }

    /** Afterglow on a slowed enemy: a blue ring sliding down its body while the magnetism holds it. */
    private void magnetized(LivingEntity enemy, long ticks) {
        Loop.repeat(ctx(), player(), 3, 3, Math.max(1, ticks / 3), run -> {
            if (!Combat.isTargetable(ctx(), player(), enemy)) {
                return false;
            }
            double cycle = (run % 4) / 3.0;
            Location at = enemy.getLocation().add(0, enemy.getHeight() * (1 - cycle) + 0.05, 0);
            Vfx.ring(ctx(), at, 0.5 - 0.1 * cycle, 8, run * 0.5, Particle.DUST_COLOR_TRANSITION, SOUTH.fade(0.8f),
                    false);
            if (run % 3 == 0) {
                Vfx.dot(Fx.body(enemy), Particle.ELECTRIC_SPARK, null, false);
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ display helpers

    /** A cube of edge {@code size} centered on the block display's origin, rotated around its center. */
    private static Transformation cube(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    private static Quaternionf randomRotation() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new Quaternionf().rotateXYZ(random.nextFloat() * 6.28f, random.nextFloat() * 6.28f,
                random.nextFloat() * 6.28f);
    }
}
