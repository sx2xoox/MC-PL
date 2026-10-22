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
import java.util.function.Consumer;
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
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.util.TriState;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 사령술사 — commands the dead. Enemies falling nearby feed the necromancer souls (a heal, and a charge for the next
 * skill), the active looses homing wraith skulls and the ultimate raises a small legion of zombies and skeletons out
 * of the ground in front of the owner.
 *
 * <p>Every summon is driven by its own owner-scoped controller task (targeting, the leash to the owner, lifetime,
 * name tag), so the ultimate also works when borrowed by the Gambler. Undead never pick a player the owner cannot see
 * (stealth). They wear a soul-cyan cap: it marks them as the necromancer's and keeps them from burning in daylight.
 *
 * <p>Look (bone white and soul cyan): stored souls circle the owner as soul flames; a reaped soul rises from the
 * corpse and arcs into the owner's chest; wraith skulls are skull item displays streaming soul fire and ghostly
 * motes; the legion is announced by skulls diving from above the owner into the ground, where earth shards and a bone
 * hand break the surface before the dead climb out.
 */
public final class NecromancerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("necromancer", "사령술사", Material.SKELETON_SKULL)
            .color(TextColor.color(0x7FE8E0))
            .difficulty(2)
            .passive("영혼 수확",
                    "내가 처치하거나 반경 {soul_radius}블록 안에서 적이 쓰러지면 영혼을 거둬 체력을 {soul_heal} 회복하고 "
                            + "영혼을 모읍니다(최대 {soul_max}개). 모은 영혼은 다음 스킬에 모두 깃들어 영혼 1개당 "
                            + "망령탄은 해골 +1발, 언데드 군단은 망자 +1구가 됩니다.")
            .active("망령탄",
                    "적을 쫓아가는 망령 해골 {skull_count}발을 날립니다. 해골은 {skull_range}블록까지 날아가며 맞은 적에게 "
                            + "{skull_damage} 피해를 줍니다.",
                    8)
            .ultimate("언데드 군단",
                    "앞쪽 땅에서 망자 {legion_count}구를 일으켜 {legion_duration}초간 함께 싸웁니다(좀비: 체력 "
                            + "{zombie_health}·공격력 {zombie_damage}, 해골 병사: 체력 {skeleton_health}·공격력 "
                            + "{skeleton_damage}). 망자가 솟는 자리 반경 {rise_radius}블록의 적은 {rise_damage} 피해를 입고 "
                            + "{rise_hold}초간 뼈 손에 붙잡힙니다.")
            .param("soul_radius", 12)
            .param("soul_heal", 3)
            .param("soul_max", 3)
            .param("skull_count", 2)
            .param("skull_damage", 3)
            .param("skull_range", 24)
            .param("legion_count", 3)
            .param("legion_duration", 15)
            .param("zombie_health", 24)
            .param("zombie_damage", 4)
            .param("skeleton_health", 16)
            .param("skeleton_damage", 3)
            .param("rise_radius", 2)
            .param("rise_damage", 2)
            .param("rise_hold", 1)
            .param("aggro_range", 16)
            .build();

    private static final String COLOR_TAG = "#7FE8E0";
    /** Bone white core, soul cyan body, the deep teal of soul fire's shadow. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF2EEDD, 0x4FE3E8, 0x0F3A40);
    private static final Color SOUL_CYAN = Color.fromRGB(0x4FE3E8);
    private static final Color BONE = Color.fromRGB(0xF2EEDD);
    private static final BlockData BONE_BLOCK = Material.BONE_BLOCK.createBlockData();
    private static final BlockData SOUL_SOIL = Material.SOUL_SOIL.createBlockData();
    private static final TextColor NAME_COLOR = TextColor.color(0x7FE8E0);
    private static final double TAU = Math.PI * 2;

    // passive
    /** Nearby enemies are checked this often for deaths. */
    private static final long WATCH_PERIOD = 4;
    /** An enemy that left the radius is still reaped if it dies within this many ticks. */
    private static final long WATCH_GRACE = 10;
    /** The same victim is reaped at most once within this many ticks (a kill is seen by the hook and the watch). */
    private static final long REAP_DEDUPE = 60;
    private static final int SOUL_FLIGHT_TICKS = 14;
    /** Stored souls circle the owner as little skulls this far out, at about shoulder height. */
    private static final double SOUL_ORBIT_RADIUS = 0.8;
    private static final double SOUL_ORBIT_HEIGHT = 1.3;
    private static final float SOUL_ORBIT_SCALE = 0.22f;
    /** Spent souls fly from the orbit into the casting hand this fast. */
    private static final int SOUL_SPEND_TICKS = 3;

    // active
    private static final long SKULL_STAGGER = 3;
    /** Ticks a skull flies on its launch heading before it starts homing, so the volley fans out first. */
    private static final long SKULL_RELEASE = 2;
    private static final double SKULL_START_SPEED = 0.55;
    private static final double SKULL_TOP_SPEED = 1.0;
    private static final double SKULL_ACCEL = 0.08;
    /** Heading change per tick while homing on a distant target (radians); it grows as the target gets close. */
    private static final double SKULL_TURN = Math.toRadians(15);
    /** Distance (blocks) at which the turn rate doubles; at half of it, it triples. */
    private static final double SKULL_CLOSE_TURN = 3.0;
    private static final double SKULL_MAX_TURN = Math.toRadians(70);
    /** Distance from a skull's path within which an enemy hitbox is struck. */
    private static final double SKULL_HITBOX = 0.55;
    private static final double SKULL_SAMPLE = 0.35;
    /** A skull that lost its target picks the nearest enemy within this range ahead of it. */
    private static final double SKULL_SEEK_RANGE = 10;
    private static final double SKULL_FAN_DEGREES = 28;
    private static final float SKULL_SCALE = 0.5f;
    private static final float SOUL_SKULL_SCALE = 0.6f;

    // ultimate
    private static final int RISE_TICKS = 12;
    private static final double LEGION_SPREAD = 120;
    private static final double LEGION_NEAR = 2.4;
    private static final double LEGION_FAR = 3.6;
    private static final double UNDEAD_WIDTH = 0.6;
    private static final double UNDEAD_HEIGHT = 1.99;
    private static final double ZOMBIE_SPEED = 0.3;
    private static final double SKELETON_SPEED = 0.32;
    private static final long CONTROL_PERIOD = 5;
    /** The owner's last hit stays the legion's focus this long. */
    private static final long FOCUS_TICKS = 100;
    /** An undead keeps chasing its target up to this multiple of the aggro range. */
    private static final double LEASH_FACTOR = 1.5;
    /** An idle undead farther than this from the owner sinks and rises again at its side. */
    private static final double RECALL_DISTANCE = 18;
    /** Peaceful only (vanilla mob attacks never land there): the controller strikes within this gap. */
    private static final double STRIKE_REACH = 0.8;
    private static final long STRIKE_TICKS = 20;
    /**
     * Display budget of one rising: per spot a diving skull and up to three ground pieces (earth shards and a bone
     * hand), and the bone fingers that hold grabbed enemies.
     */
    private static final int MAX_LEGION_DISPLAYS = 24;
    /** Enemies held by bone fingers (three each) per rising; further grabbed enemies only get particles. */
    private static final int MAX_GRAB_HANDS = 3;
    private static final int GRAB_FINGERS = 3;
    private static final int AFTERGLOW_TICKS = 14;

    /** Stored souls (0..soul_max), spent by the next skill. */
    private int souls;
    /** One little skull circling the owner per stored soul (shown once its soul has arrived). */
    private final List<ItemDisplay> soulSkulls = new ArrayList<>();
    /** Enemies seen within the soul radius, by entity id. */
    private final Map<UUID, Watch> watched = new HashMap<>();
    /** When each victim was last reaped. */
    private final Map<UUID, Long> reaped = new HashMap<>();
    /** Alive undead, oldest first. */
    private final List<Undead> legion = new ArrayList<>();
    private @Nullable LivingEntity focus;
    private long focusUntil;
    /** True while a summon's own (Peaceful) strike is dealt, so it does not count as the owner's hit. */
    private boolean summonStrike;

    /** An enemy last seen alive within the soul radius. */
    private static final class Watch {
        final LivingEntity entity;
        Location at;
        long seen;

        Watch(LivingEntity entity, Location at, long seen) {
            this.entity = entity;
            this.at = at;
            this.seen = seen;
        }
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        // The framework removes the undead and every display; only forget them here.
        for (Undead undead : legion) {
            undead.dismissed = true;
        }
        legion.clear();
        watched.clear();
        reaped.clear();
        soulSkulls.clear();
        focus = null;
        souls = 0;
    }

    // ------------------------------------------------------------------ passive: soul harvest

    @Override
    public void onTick(long tick) {
        if (!soulSkulls.isEmpty() && tick % 2 == 0) {
            orbitSouls(tick);
        }
        if (tick % WATCH_PERIOD != 0) {
            return;
        }
        Player owner = player();
        long now = ctx().currentTick();
        Iterator<Watch> it = watched.values().iterator();
        while (it.hasNext()) {
            Watch watch = it.next();
            if (hasFallen(watch.entity)) {
                it.remove();
                reap(watch.entity, watch.at);
            } else if (now - watch.seen > WATCH_GRACE) {
                it.remove();
            }
        }
        double radius = Math.max(0, params().num("soul_radius"));
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), radius)) {
            Watch watch = watched.get(enemy.getUniqueId());
            if (watch == null) {
                watched.put(enemy.getUniqueId(), new Watch(enemy, enemy.getLocation(), now));
            } else {
                watch.at = enemy.getLocation();
                watch.seen = now;
            }
        }
        if (tick % 100 == 0) {
            reaped.values().removeIf(at -> now - at > REAP_DEDUPE);
        }
    }

    @Override
    public void onKill(Player victim) {
        watched.remove(victim.getUniqueId());
        reap(victim, victim.getLocation());
    }

    /** A watched enemy died: a player was eliminated (not a quit), a mob ran out of health. */
    private boolean hasFallen(LivingEntity entity) {
        if (entity instanceof Player player) {
            return player.isOnline() && !ctx().isAlive(player);
        }
        return entity.isDead() && entity.getHealth() <= 0;
    }

    /** Takes the soul of {@code victim}, who fell at {@code at}: a heal and, below the cap, a stored soul. */
    private void reap(LivingEntity victim, Location at) {
        long now = ctx().currentTick();
        Long last = reaped.get(victim.getUniqueId());
        if (last != null && now - last < REAP_DEDUPE) {
            return;
        }
        reaped.put(victim.getUniqueId(), now);
        Player owner = player();
        ctx().heal(owner, Math.max(0, params().num("soul_heal")));
        int max = Math.max(0, params().integer("soul_max"));
        if (souls < max) {
            souls++;
        }
        soulFlight(at.getWorld().equals(owner.getWorld()) ? at.clone().add(0, 1.0, 0) : Fx.body(owner));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">☠ 영혼 수확</" + COLOR_TAG + "> <gray>영혼 <white>" + souls
                + "</white>/" + max + "</gray>"));
    }

    /**
     * A reaped soul: sculk souls burst from the corpse like at a catalyst, then a soul flame arcs high and dives into
     * the owner's chest, which flashes cyan.
     */
    private void soulFlight(Location from) {
        Player owner = player();
        World world = from.getWorld();
        Vfx.cloud(ctx(), from, Particle.SCULK_SOUL, 6, 0.3, 0.04, null, false);
        Vfx.ring(ctx(), from.clone().add(0, -0.9, 0), 0.9, 14, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f),
                false);
        Fx.sound(from, Sound.BLOCK_SCULK_CATALYST_BLOOM, 1.2f, 0.9f + Fx.jitter(0.1f));
        Fx.sound(from, Sound.PARTICLE_SOUL_ESCAPE, 1.4f, 0.7f + Fx.jitter(0.1f));
        Location start = from.clone();
        Location[] previous = {start.clone()};
        Loop.repeat(ctx(), owner, 1, 1, SOUL_FLIGHT_TICKS, run -> {
            if (!owner.getWorld().equals(world)) {
                return false;
            }
            double t = (double) (run + 1) / SOUL_FLIGHT_TICKS;
            Location end = Fx.body(owner);
            Location control = start.clone().add(end).multiply(0.5).add(0, 2.6, 0);
            Location point = bezier(start, control, end, t);
            Vfx.trail(previous[0], point, SOUL_CYAN, 3, false);
            Vfx.dot(point, Particle.SOUL_FIRE_FLAME, null, false);
            Vfx.dot(point, Particle.DUST, PALETTE.dust(1.1f), false);
            if (run % 3 == 0) {
                Vfx.dot(point, Particle.SOUL, null, false);
            }
            previous[0] = point;
            if (run == SOUL_FLIGHT_TICKS - 1) {
                Vfx.dot(end, Particle.FLASH, SOUL_CYAN, false);
                Vfx.sphere(ctx(), end, 0.55, 14, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), false);
                Fx.sound(end, Sound.PARTICLE_SOUL_ESCAPE, 1.0f, 1.3f + Fx.jitter(0.1f));
                Fx.sound(end, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 0.6f + Fx.jitter(0.05f));
            }
            return true;
        }, this::syncSoulSkulls);
    }

    /**
     * Matches the circling skulls to the stored souls: a new one grows out of the owner's chest, a surplus one (souls
     * spent meanwhile) is let go.
     */
    private void syncSoulSkulls() {
        Player owner = player();
        soulSkulls.removeIf(skull -> !skull.isValid());
        while (soulSkulls.size() < souls) {
            ItemDisplay skull = Displays.item(ctx(), owner, Fx.body(owner), ItemStack.of(Material.SKELETON_SKULL),
                    soulSkullPose(0.02f, 0), 0);
            skull.setBillboard(Display.Billboard.CENTER);
            skull.setTeleportDuration(2);
            Displays.animateNextTick(ctx(), owner, skull, soulSkullPose(SOUL_ORBIT_SCALE, 0), 5);
            soulSkulls.add(skull);
        }
        while (soulSkulls.size() > souls) {
            Displays.shrinkAway(ctx(), owner, soulSkulls.removeLast(), 4);
        }
        if (!soulSkulls.isEmpty()) {
            orbitSouls(ctx().currentTick());
        }
    }

    /**
     * Stored souls circle the owner's shoulders as little skulls trailing soul fire, a tell enemies can read too: the
     * more skulls, the bigger the next volley or legion.
     */
    private void orbitSouls(long tick) {
        Location base = player().getLocation();
        int count = soulSkulls.size();
        for (int i = 0; i < count; i++) {
            ItemDisplay skull = soulSkulls.get(i);
            double angle = tick * 0.16 + i * TAU / count;
            double height = SOUL_ORBIT_HEIGHT + 0.12 * Math.sin(tick * 0.2 + i * 2.1);
            Location at = base.clone().add(Math.cos(angle) * SOUL_ORBIT_RADIUS, height,
                    Math.sin(angle) * SOUL_ORBIT_RADIUS);
            if (skull.isValid()) {
                skull.teleport(at);
            }
            if (tick % 6 == i % 3 * 2) {
                Vfx.dot(at.clone().add(0, -0.15, 0), Particle.SOUL_FIRE_FLAME, null, false);
            }
        }
    }

    /**
     * The stored souls go into the skill being cast: each circling skull darts into the casting hand and is absorbed
     * in a puff of soul fire.
     */
    private void spendSouls(Location hand) {
        Player owner = player();
        for (ItemDisplay skull : soulSkulls) {
            if (!skull.isValid()) {
                continue;
            }
            Vfx.trail(skull.getLocation(), hand, SOUL_CYAN, SOUL_SPEND_TICKS + 1, false);
            skull.setTeleportDuration(SOUL_SPEND_TICKS);
            skull.teleport(hand);
            ctx().runLater(owner, SOUL_SPEND_TICKS, () -> {
                if (skull.isValid()) {
                    Displays.shrinkAway(ctx(), owner, skull, 2);
                }
            });
        }
        soulSkulls.clear();
        Vfx.cloud(ctx(), hand, Particle.SOUL_FIRE_FLAME, 6, 0.15, 0.03, null, false);
        Fx.sound(hand, Sound.PARTICLE_SOUL_ESCAPE, 1.3f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(hand, Sound.ENTITY_VEX_CHARGE, 0.8f, 0.7f + Fx.jitter(0.05f));
    }

    private static Transformation soulSkullPose(float scale, float roll) {
        return Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, roll);
    }

    /** The legion's focus: the enemy the owner last hit (melee, projectile or skill), never a summon's own bite. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (summonStrike) {
            return;
        }
        Entity damager = event.getDamager();
        if (damager.equals(player()) || damager instanceof Projectile projectile
                && player().equals(projectile.getShooter())) {
            focus = victim;
            focusUntil = ctx().currentTick() + FOCUS_TICKS;
        }
    }

    // ------------------------------------------------------------------ active: wraith bolts

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("skull_range"));
        int base = Math.max(1, params().integer("skull_count"));
        int empowered = souls;
        souls = 0;

        LivingEntity primary = ctx().findTarget(owner, range);
        if (primary == null) {
            primary = nearestAhead(owner.getEyeLocation(), owner.getEyeLocation().getDirection(), range, null);
        }
        List<LivingEntity> others = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            if (!enemy.equals(primary) && isVisible(enemy)) {
                others.add(enemy);
            }
        }

        castFlare(owner, empowered > 0);
        if (empowered > 0) {
            spendSouls(castPoint(owner));
        }
        int total = base + empowered;
        for (int i = 0; i < total; i++) {
            boolean soul = i >= base;
            // Soul skulls spread to the other enemies around; the base volley goes for the aimed one.
            LivingEntity target = soul && !others.isEmpty() ? others.get((i - base) % others.size()) : primary;
            int index = i;
            ctx().runLater(owner, 1 + i * SKULL_STAGGER, () -> launchSkull(index, target, soul));
        }
        if (empowered > 0) {
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">☠ 영혼이 깃든 망령탄</" + COLOR_TAG + "> <gray>+"
                    + empowered + "발</gray>"));
        }
        return ActivationResult.success();
    }

    /** Just in front of the casting hand. */
    private static Location castPoint(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.clone().add(eye.getDirection().multiply(0.8)).subtract(0, 0.3, 0);
    }

    /** The cast at the hand: a bone-white ring opening around the aim, soul motes drawn into the palm. */
    private void castFlare(Player owner, boolean empowered) {
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        Location hand = castPoint(owner);
        aimRing(hand, direction, 0.45, 12, PALETTE.glow(0.8f));
        if (empowered) {
            aimRing(hand, direction, 0.75, 16, PALETTE.fade(1.0f));
        }
        Vfx.cloud(ctx(), hand, Particle.SOUL, empowered ? 6 : 3, 0.15, 0.02, null, false);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int motes = Vfx.n(ctx(), 6);
        for (int i = 0; i < motes; i++) {
            Location from = hand.clone().add(random.nextGaussian() * 0.6, random.nextGaussian() * 0.5,
                    random.nextGaussian() * 0.6);
            Vfx.trail(from, hand, i % 2 == 0 ? SOUL_CYAN : BONE, 5, false);
        }
        Fx.sound(eye, Sound.ENTITY_EVOKER_CAST_SPELL, 0.9f, 1.4f + Fx.jitter(0.08f));
        Fx.sound(eye, Sound.ENTITY_SKELETON_AMBIENT, 0.8f, 0.6f + Fx.jitter(0.05f));
        if (empowered) {
            Fx.sound(eye, Sound.PARTICLE_SOUL_ESCAPE, 1.4f, 0.6f + Fx.jitter(0.05f));
        }
    }

    private void launchSkull(int index, @Nullable LivingEntity target, boolean soul) {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        double side = index % 2 == 0 ? 1 : -1;
        Vector right = new Vector(-look.getZ(), 0, look.getX());
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vector(1, 0, 0);
        }
        right.normalize();
        Location start = eye.clone().subtract(0, 0.35, 0).add(right.clone().multiply(0.45 * side))
                .add(look.clone().multiply(0.6));
        // A near target gets a tighter fan, so the skulls still have room to curve in.
        double spread = target == null || !target.getWorld().equals(eye.getWorld()) ? 1.0
                : Math.clamp(eye.distance(Fx.body(target)) / 8.0, 0.35, 1.0);
        double fan = SKULL_FAN_DEGREES * spread * (1 + (index / 2) * 0.35) * side;
        Vector heading = look.clone().rotateAroundY(Math.toRadians(-fan)).add(new Vector(0, 0.18, 0)).normalize();
        Skull skull = new Skull(start, heading, target, soul);
        long maxTicks = (long) Math.ceil(params().num("skull_range") / SKULL_START_SPEED) + 4;
        Loop.repeat(ctx(), owner, 0, 1, maxTicks, skull, skull::dissolve);
        Fx.sound(start, Sound.ENTITY_WITHER_SHOOT, 0.35f, 1.5f + index * 0.08f + Fx.jitter(0.05f));
        Fx.sound(start, Sound.ENTITY_VEX_CHARGE, 0.7f, 1.1f + Fx.jitter(0.1f));
        Vfx.cloud(ctx(), start, Particle.SOUL_FIRE_FLAME, 3, 0.08, 0.02, null, false);
    }

    /** The nearest enemy within {@code range} roughly ahead of {@code from} along {@code direction}. */
    private @Nullable LivingEntity nearestAhead(Location from, Vector direction, double range,
                                                @Nullable LivingEntity except) {
        Vector forward = direction.clone();
        if (forward.lengthSquared() < 1.0E-6) {
            return null;
        }
        forward.normalize();
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity enemy : ctx().enemiesNear(player(), from, range)) {
            if (enemy.equals(except) || !isVisible(enemy)) {
                continue;
            }
            Vector to = Fx.body(enemy).toVector().subtract(from.toVector());
            double distance = to.length();
            if (distance < 1.0E-3) {
                return enemy;
            }
            double dot = to.multiply(1 / distance).dot(forward);
            if (dot < 0.25) {
                continue;
            }
            // Prefer enemies near the line of flight over merely close ones.
            double score = distance * (2 - dot);
            if (score < bestScore) {
                bestScore = score;
                best = enemy;
            }
        }
        return best;
    }

    /**
     * A wraith skull: a skull item display that flies on its launch heading for a moment, then homes in on its target
     * with a limited turn rate, and bursts on the first enemy, block or the end of its range.
     */
    private final class Skull implements LongPredicate {

        private final World world;
        private final Location position;
        private final Vector direction;
        private final boolean soul;
        private final ItemDisplay visual;
        private @Nullable LivingEntity target;
        private double remaining;
        private boolean done;

        Skull(Location start, Vector heading, @Nullable LivingEntity target, boolean soul) {
            this.world = start.getWorld();
            this.position = start.clone();
            this.direction = heading.clone().normalize();
            this.target = target;
            this.soul = soul;
            this.remaining = Math.max(1, params().num("skull_range"));
            long lifetime = (long) Math.ceil(remaining / SKULL_START_SPEED) + 8;
            float scale = soul ? SOUL_SKULL_SCALE : SKULL_SCALE;
            ItemStack item = ItemStack.of(soul ? Material.WITHER_SKELETON_SKULL : Material.SKELETON_SKULL);
            this.visual = Displays.item(ctx(), player(), facing(start, direction), item,
                    Displays.centered(0, 0, 0, scale * 0.3f, scale * 0.3f, scale * 0.3f, 0, 0, 0), lifetime);
            visual.setTeleportDuration(1);
            if (soul) {
                Displays.outline(visual, SOUL_CYAN);
            }
            Displays.animateNextTick(ctx(), player(), visual,
                    Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, 0), 3);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            if (tick >= SKULL_RELEASE) {
                steer();
            }
            double speed = Math.min(SKULL_TOP_SPEED, SKULL_START_SPEED + tick * SKULL_ACCEL);
            double step = Math.min(speed, remaining);
            if (step <= 1.0E-3) {
                dissolve();
                return false;
            }
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.NEVER, true);
            double travel = wall == null ? step : wall.getHitPosition().distance(position.toVector());
            int samples = Math.max(1, (int) Math.ceil(travel / SKULL_SAMPLE));
            for (int i = 1; i <= samples; i++) {
                Location probe = position.clone().add(direction.clone().multiply(travel * i / samples));
                LivingEntity struck = struckAt(probe);
                if (struck != null) {
                    burst(probe, struck);
                    return false;
                }
            }
            Location from = position.clone();
            position.add(direction.clone().multiply(travel));
            remaining -= travel;
            fly(from, tick);
            if (wall != null) {
                burst(position.clone().subtract(direction.clone().multiply(0.2)), null);
                return false;
            }
            if (remaining <= 1.0E-3) {
                dissolve();
                return false;
            }
            return true;
        }

        /**
         * Turns the heading towards the target's chest by a limited angle per tick ({@link #SKULL_TURN}, more when
         * close). A target that died or hid is replaced by the nearest visible enemy ahead, if any.
         */
        private void steer() {
            LivingEntity current = target;
            if (current == null || !Combat.isTargetable(ctx(), player(), current) || !isVisible(current)
                    || !current.getWorld().equals(world)) {
                current = nearestAhead(position, direction, SKULL_SEEK_RANGE, null);
                target = current;
            }
            if (current == null) {
                return;
            }
            Vector desired = current.getLocation().add(0, current.getHeight() * 0.55, 0).toVector()
                    .subtract(position.toVector());
            if (desired.lengthSquared() < 1.0E-6) {
                return;
            }
            // Close in, the skull turns harder, so it dives into a near target instead of circling around it.
            double turn = Math.min(SKULL_MAX_TURN, SKULL_TURN * (1 + SKULL_CLOSE_TURN / Math.max(0.5,
                    desired.length())));
            desired.normalize();
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

        private @Nullable LivingEntity struckAt(Location probe) {
            LivingEntity aimed = target;
            if (aimed != null && Combat.isTargetable(ctx(), player(), aimed)
                    && aimed.getBoundingBox().expand(SKULL_HITBOX * 0.5).contains(probe.toVector())) {
                return aimed;
            }
            List<LivingEntity> near = ctx().enemiesNear(player(), probe, SKULL_HITBOX);
            return near.isEmpty() ? null : near.getFirst();
        }

        /** Moves the skull (nodding as it flies) and leaves a streak of bone-to-cyan dust, soul fire and wisps. */
        private void fly(Location from, long tick) {
            if (visual.isValid()) {
                visual.teleport(facing(position, direction));
                float scale = soul ? SOUL_SKULL_SCALE : SKULL_SCALE;
                float roll = (float) Math.sin(tick * 0.7) * 14f;
                if (tick > 0) {
                    Displays.animate(visual, Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, roll), 1);
                }
            }
            Vfx.line(ctx(), from, position, 2.0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(soul ? 1.0f : 0.75f),
                    false);
            Vector back = direction.clone().multiply(-1);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, position.getX(), position.getY(), position.getZ(), 0,
                    back.getX() + Fx.jitter(0.2f), back.getY() + Fx.jitter(0.2f), back.getZ() + Fx.jitter(0.2f), 0.06);
            if (tick % 2 == 0) {
                Location tail = position.clone().add(back.clone().multiply(1.3))
                        .add(Fx.jitter(0.25f), Fx.jitter(0.25f), Fx.jitter(0.25f));
                Vfx.trail(position, tail, soul ? SOUL_CYAN : BONE, 6, false);
            }
            if (tick % 4 == 1) {
                Vfx.dot(from, Particle.SOUL, null, false);
            }
            if (tick % 6 == 0) {
                Fx.sound(position, Sound.ENTITY_VEX_AMBIENT, 0.35f, 1.6f + Fx.jitter(0.15f));
            }
        }

        /** The skull shatters on a hit: a cyan flash, a sphere of soul dust, bone shards and freed souls. */
        private void burst(Location at, @Nullable LivingEntity struck) {
            done = true;
            visual.remove();
            if (struck != null) {
                Combat.hit(ctx(), player(), struck, params().num("skull_damage"));
            }
            Vfx.dot(at, Particle.FLASH, SOUL_CYAN, false);
            Vfx.sphere(ctx(), at, soul ? 0.6 : 0.45, soul ? 18 : 14, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.0f), false);
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 10), 0.2, 0.2, 0.2, 0.1, BONE_BLOCK);
            Vfx.cloud(ctx(), at, Particle.SOUL, 4, 0.2, 0.05, null, false);
            Vfx.cloud(ctx(), at, Particle.SCULK_SOUL, 2, 0.15, 0.03, null, false);
            if (struck != null) {
                // A wraith biting into its victim; a wall only hears the skull shatter.
                Fx.sound(at, Sound.ENTITY_WITHER_SKELETON_HURT, 0.6f, 1.3f + Fx.jitter(0.15f));
                Vfx.cloud(ctx(), Fx.body(struck), Particle.DUST_COLOR_TRANSITION, 6, 0.25, 0, PALETTE.fade(1.0f),
                        false);
            }
            Fx.sound(at, Sound.BLOCK_BONE_BLOCK_BREAK, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.PARTICLE_SOUL_ESCAPE, 1.2f, 1.0f + Fx.jitter(0.15f));
            Location glow = at.clone();
            Loop.repeat(ctx(), player(), 2, 2, 6, run -> {
                Location mote = glow.clone().add(Fx.jitter(0.4f), run * 0.12, Fx.jitter(0.4f));
                Vfx.dot(mote, Particle.SOUL_FIRE_FLAME, null, false);
                Vfx.dot(mote, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.7f), false);
                return true;
            });
        }

        /** Out of range (or the owner is gone): the skull crumbles into ash without hurting anyone. */
        void dissolve() {
            if (done) {
                return;
            }
            done = true;
            if (visual.isValid()) {
                visual.remove();
            }
            world.spawnParticle(Particle.BLOCK, position, Vfx.n(ctx(), 6), 0.15, 0.15, 0.15, 0.05, BONE_BLOCK);
            Vfx.cloud(ctx(), position, Particle.SMOKE, 3, 0.1, 0.01, null, false);
            Vfx.dot(position, Particle.SOUL, null, false);
            Fx.sound(position, Sound.BLOCK_BONE_BLOCK_HIT, 0.6f, 1.2f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ ultimate: undead legion

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        int count = Math.max(1, params().integer("legion_count")) + souls;
        List<Location> spots = legionSpots(owner, count);
        if (spots.isEmpty()) {
            return ActivationResult.fail("망자를 일으킬 공간이 없습니다");
        }
        int empowered = souls;
        souls = 0;
        if (empowered > 0) {
            spendSouls(owner.getLocation().add(0, 2.6, 0));
        }
        new Rising(spots).begin();
        if (empowered > 0) {
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">☠ 영혼이 깃든 군단</" + COLOR_TAG + "> <gray>+"
                    + empowered + "구</gray>"));
        }
        return ActivationResult.success();
    }

    /**
     * Rising spots fanned out in front of the owner (two rows when there are many). Each must stand on ground, fit an
     * undead, lie inside the arena and be visible from the owner. A blocked spot is moved sideways or dropped.
     */
    private List<Location> legionSpots(Player owner, int count) {
        Location origin = owner.getLocation();
        Vector forward = Motion.horizontalFacing(origin);
        Location eye = owner.getEyeLocation();
        List<Location> spots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = count == 1 ? 0 : -LEGION_SPREAD / 2 + LEGION_SPREAD * i / (count - 1);
            double distance = count > 4 && i % 2 == 1 ? LEGION_FAR : LEGION_NEAR;
            for (double nudge : new double[] {0, 18, -18, 36, -36}) {
                Location candidate = origin.clone().add(forward.clone().rotateAroundY(Math.toRadians(-(angle + nudge)))
                        .multiply(distance));
                Location ground = standable(candidate, eye);
                if (ground != null && spots.stream().noneMatch(s -> s.distanceSquared(ground) < 0.8)) {
                    ground.setYaw(origin.getYaw());
                    ground.setPitch(0);
                    spots.add(ground);
                    break;
                }
            }
        }
        return spots;
    }

    /** {@code point} lowered onto the ground below (at most 3 blocks), if an undead fits there; null otherwise. */
    private @Nullable Location standable(Location point, Location eye) {
        World world = point.getWorld();
        RayTraceResult down = world.rayTraceBlocks(point.clone().add(0, 1.0, 0), new Vector(0, -1, 0), 4.0,
                FluidCollisionMode.NEVER, true);
        if (down == null) {
            return null;
        }
        Location ground = down.getHitPosition().toLocation(world).add(0, 0.01, 0);
        if (!Spots.fits(world, ground, UNDEAD_WIDTH, UNDEAD_HEIGHT) || !Spots.hasGroundBelow(world, ground)
                || !ctx().isInsideArena(ground)
                || !Spots.clearPath(eye, ground.clone().add(0, 1.0, 0))) {
            return null;
        }
        return ground;
    }

    /**
     * The legion rising: skulls dive from above the owner into each spot while the ground cracks (earth shards and a
     * bone hand break the surface), then the dead climb out, grabbing enemies standing on the spot.
     */
    private final class Rising {

        private final List<Location> spots;
        private final Location origin;
        private final ItemDisplay[] skulls;
        private final Location[] skullStarts;
        private final List<BlockDisplay> debris = new ArrayList<>();

        Rising(List<Location> spots) {
            this.spots = spots;
            this.origin = player().getLocation();
            this.skulls = new ItemDisplay[spots.size()];
            this.skullStarts = new Location[spots.size()];
        }

        void begin() {
            Player owner = player();
            Location feet = origin.clone().add(0, 0.05, 0);
            Fx.sound(origin, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.2f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(origin, Sound.PARTICLE_SOUL_ESCAPE, 1.5f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(origin, Sound.ENTITY_WITHER_AMBIENT, 0.5f, 1.6f + Fx.jitter(0.05f));
            Vfx.shockwave(ctx(), owner, feet, 0.4, 2.2, 6, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.3f), true);
            Vfx.cloud(ctx(), feet.clone().add(0, 1.0, 0), Particle.SCULK_SOUL, 10, 0.4, 0.05, null, true);

            // What the bone fingers of the grabs leave of the budget is shared by the spots (a skull each first).
            int pieces = Math.min(3, (MAX_LEGION_DISPLAYS - MAX_GRAB_HANDS * GRAB_FINGERS) / spots.size() - 1);
            for (int i = 0; i < spots.size(); i++) {
                double angle = i * TAU / spots.size();
                Location start = origin.clone().add(Math.cos(angle) * 0.8, 2.6, Math.sin(angle) * 0.8);
                skullStarts[i] = start;
                skulls[i] = Displays.item(ctx(), owner, start, ItemStack.of(Material.SKELETON_SKULL),
                        Displays.centered(0, 0, 0, 0.1f, 0.1f, 0.1f, 0, 0, 0), RISE_TICKS + 4L);
                skulls[i].setTeleportDuration(1);
                skulls[i].setBillboard(Display.Billboard.CENTER);
                Displays.outline(skulls[i], SOUL_CYAN);
                Displays.animateNextTick(ctx(), owner, skulls[i], Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f,
                        0, 0, 0), 4);
                Vfx.trail(Fx.body(owner), start, SOUL_CYAN, 4, true);
                if (pieces >= 1) {
                    crackGround(spots.get(i), pieces);
                }
            }
            Loop.repeat(ctx(), owner, 1, 1, RISE_TICKS, this::step, this::rise);
        }

        /** Earth shards (soul soil) tilting up out of the ground around a spot, and a bone hand clawing up. */
        private void crackGround(Location spot, int pieces) {
            Player owner = player();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double phase = random.nextDouble(TAU);
            for (int k = 0; k < pieces; k++) {
                // The last piece is a bone hand clawing up through the middle (a single piece is always the hand).
                boolean hand = k == pieces - 1 && pieces != 2;
                double angle = phase + k * TAU / pieces;
                Location at = spot.clone().add(Math.cos(angle) * (hand ? 0.15 : 0.55), 0.02,
                        Math.sin(angle) * (hand ? 0.15 : 0.55));
                at.setYaw(0);
                at.setPitch(0);
                float yaw = (float) Math.toDegrees(angle) - 90f;
                BlockDisplay piece = Displays.block(ctx(), owner, at, hand ? BONE_BLOCK : SOUL_SOIL,
                        Displays.transform(0, 0, 0, 0.01f, 0.01f, 0.01f, yaw, 0, 0), RISE_TICKS + 12L);
                Transformation shape = hand
                        ? Displays.transform(0, 0.35f, 0, 0.11f, 0.75f, 0.11f, yaw, 0, 18 + random.nextFloat() * 14)
                        : Displays.transform(0, 0.12f, 0, 0.5f, 0.14f, 0.38f, yaw, -(35 + random.nextFloat() * 20), 0);
                int grow = Math.max(1, RISE_TICKS - 4 - k);
                ctx().runLater(owner, 3 + k, () -> Displays.animate(piece, shape, grow));
                debris.add(piece);
            }
        }

        /** The skulls dive along arcs into their spots; the spots glow and breathe soul smoke as they crack. */
        private boolean step(long run) {
            double t = (double) (run + 1) / RISE_TICKS;
            double eased = t * t;
            for (int i = 0; i < spots.size(); i++) {
                Location spot = spots.get(i);
                ItemDisplay skull = skulls[i];
                if (skull != null && skull.isValid()) {
                    Location from = skullStarts[i];
                    Location control = from.clone().add(spot).multiply(0.5).add(0, 2.2, 0);
                    skull.teleport(bezier(from, control, spot.clone().add(0, 0.4, 0), eased));
                    Vfx.dot(skull.getLocation(), Particle.SOUL_FIRE_FLAME, null, true);
                }
                if (run % 2 == 0) {
                    double radius = Math.max(0.6, params().num("rise_radius")) * Math.min(1, t * 1.4);
                    Vfx.ring(ctx(), spot.clone().add(0, 0.06, 0), radius, (int) Math.max(10, radius * 9),
                            run * 0.25, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.1f), true);
                }
                if (run % 3 == 1) {
                    spot.getWorld().spawnParticle(Particle.DUST_PILLAR, spot.clone().add(0, 0.1, 0), Vfx.n(ctx(), 3),
                            0.3, 0, 0.3, 0.05, SOUL_SOIL, true);
                    Vfx.cloud(ctx(), spot.clone().add(0, 0.3, 0), Particle.SOUL, 1, 0.3, 0.03, null, true);
                }
            }
            if (run == RISE_TICKS / 2) {
                Fx.sound(origin, Sound.ENTITY_WARDEN_DIG, 0.7f, 1.5f + Fx.jitter(0.05f));
            }
            return true;
        }

        /** The dead climb out: the shards burst away, the skulls shatter and each spot grabs the enemies on it. */
        private void rise() {
            Player owner = player();
            long lifetime = Math.max(1, params().ticks("legion_duration"));
            double radius = Math.max(0, params().num("rise_radius"));
            double damage = params().num("rise_damage");
            long hold = params().ticks("rise_hold");
            Set<UUID> grabbed = new HashSet<>();
            int raised = 0;
            for (int i = 0; i < spots.size(); i++) {
                Location spot = spots.get(i);
                ItemDisplay skull = skulls[i];
                if (skull != null && skull.isValid()) {
                    skull.remove();
                }
                boolean skeleton = i % 3 == 1;
                if (raiseUndead(spot, skeleton, lifetime) != null) {
                    raised++;
                }
                eruption(spot, radius);
                for (LivingEntity enemy : ctx().enemiesNear(owner, spot, radius)) {
                    if (grabbed.add(enemy.getUniqueId())) {
                        grab(enemy, damage, hold, grabbed.size() <= MAX_GRAB_HANDS);
                    }
                }
            }
            afterglow();
            for (BlockDisplay piece : debris) {
                if (piece.isValid()) {
                    Transformation current = piece.getTransformation();
                    Displays.animate(piece, new Transformation(new Vector3f(current.getTranslation()).add(0, 0.3f, 0),
                            current.getLeftRotation(), current.getScale(), current.getRightRotation()), 3);
                    ctx().runLater(owner, 3, () -> {
                        if (piece.isValid()) {
                            Displays.shrinkAway(ctx(), owner, piece, 5);
                        }
                    });
                }
            }
            Fx.sound(origin, Sound.ENTITY_WARDEN_EMERGE, 0.8f, 1.5f + Fx.jitter(0.05f));
            Fx.sound(origin, Sound.BLOCK_SOUL_SOIL_BREAK, 1.3f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(origin, Sound.ENTITY_ZOMBIE_AMBIENT, 1.0f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(origin, Sound.ENTITY_SKELETON_AMBIENT, 1.0f, 0.7f + Fx.jitter(0.05f));
            if (owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">☠ 언데드 군단</" + COLOR_TAG + "> <gray>망자 <white>"
                        + raised + "</white>구가 일어났습니다</gray>"));
            }
        }

        /** The ground bursting open at a spot: a cyan flash, a soul shockwave, soil and bone fragments, rising souls. */
        private void eruption(Location spot, double radius) {
            World world = spot.getWorld();
            Location ground = spot.clone().add(0, 0.1, 0);
            Vfx.dot(ground.clone().add(0, 0.6, 0), Particle.FLASH, SOUL_CYAN, true);
            Vfx.shockwave(ctx(), player(), ground, 0.3, Math.max(0.8, radius + 0.3), 5,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.3f), true);
            world.spawnParticle(Particle.BLOCK, ground.clone().add(0, 0.3, 0), Vfx.n(ctx(), 18), 0.35, 0.2, 0.35, 0.15,
                    SOUL_SOIL, true);
            world.spawnParticle(Particle.BLOCK, ground.clone().add(0, 0.6, 0), Vfx.n(ctx(), 8), 0.25, 0.3, 0.25, 0.1,
                    BONE_BLOCK, true);
            world.spawnParticle(Particle.DUST_PILLAR, ground, Vfx.n(ctx(), 10), 0.4, 0, 0.4, 0.15, SOUL_SOIL, true);
            Vfx.helix(ctx(), ground, 0.5, 2.0, 1.25, 2, 10, ThreadLocalRandom.current().nextDouble(TAU),
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), true);
            Vfx.cloud(ctx(), ground.clone().add(0, 1.0, 0), Particle.SOUL, 5, 0.3, 0.05, null, true);
        }

        /** Soul smoke still seeping from the broken ground: rising flames and a fading ring at every spot. */
        private void afterglow() {
            Loop.repeat(ctx(), player(), 2, 2, AFTERGLOW_TICKS / 2, run -> {
                double fade = 1 - (double) run / (AFTERGLOW_TICKS / 2.0);
                for (Location spot : spots) {
                    Location ground = spot.clone().add(0, 0.08, 0);
                    Vfx.ring(ctx(), ground, 0.5 + 0.4 * fade, (int) Math.max(6, Math.round(10 * fade)), run * 0.5,
                            Particle.DUST_COLOR_TRANSITION, PALETTE.fade((float) (0.6 + 0.6 * fade)), false);
                    Vfx.dot(Vfx.scatter(ground, 0.5).add(0, 0.1 + run * 0.08, 0), Particle.SOUL_FIRE_FLAME, null,
                            false);
                    if (run % 2 == 0) {
                        Vfx.dot(ground.clone().add(0, 0.3, 0), Particle.SMOKE, null, false);
                    }
                }
                return true;
            });
        }

        /**
         * Bony hands hold the enemy in place for a moment: damage and a heavy slow, bone fingers closing around its
         * feet (for the first few enemies, to keep the display budget) and a soul ring circling them.
         */
        private void grab(LivingEntity enemy, double damage, long hold, boolean fingers) {
            Combat.hit(ctx(), player(), enemy, damage);
            if (!Combat.isTargetable(ctx(), player(), enemy)) {
                return;
            }
            if (hold > 0) {
                int ticks = (int) Math.min(Integer.MAX_VALUE, hold);
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 3, false, true, true));
                Vector velocity = enemy.getVelocity();
                enemy.setVelocity(new Vector(0, Math.min(0, velocity.getY()), 0));
            }
            if (fingers) {
                boneFingers(enemy.getLocation(), Math.max(6, hold));
            }
            Fx.sound(enemy.getLocation(), Sound.ENTITY_SKELETON_HURT, 0.8f, 0.6f + Fx.jitter(0.08f));
            Fx.sound(enemy.getLocation(), Sound.BLOCK_BONE_BLOCK_PLACE, 1.0f, 0.7f + Fx.jitter(0.08f));
            long runs = Math.max(1, hold / 3);
            Loop.repeat(ctx(), player(), 0, 3, runs, run -> {
                if (!Combat.isTargetable(ctx(), player(), enemy)) {
                    return false;
                }
                Location feet = enemy.getLocation().add(0, 0.08, 0);
                Vfx.ring(ctx(), feet, 0.55, 9, run * 0.7, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.9f), false);
                Vfx.dot(feet.clone().add(Fx.jitter(0.3f), 0.2, Fx.jitter(0.3f)), Particle.SOUL_FIRE_FLAME, null, false);
                return true;
            });
        }

        /**
         * Three bone fingers burst up around {@code feet}, curled in towards the middle, hold for {@code hold} ticks
         * and sink back into the ground.
         */
        private void boneFingers(Location feet, long hold) {
            Player owner = player();
            Location center = feet.clone();
            center.setYaw(0);
            center.setPitch(0);
            double phase = ThreadLocalRandom.current().nextDouble(TAU);
            for (int k = 0; k < GRAB_FINGERS; k++) {
                double angle = phase + k * TAU / GRAB_FINGERS;
                Location base = center.clone().add(Math.cos(angle) * 0.5, 0.02, Math.sin(angle) * 0.5);
                // Facing the middle; a positive pitch leans the finger's top that way.
                float yaw = (float) Math.toDegrees(Math.atan2(Math.cos(angle), -Math.sin(angle)));
                BlockDisplay finger = Displays.block(ctx(), owner, base, BONE_BLOCK,
                        Displays.transform(0, 0, 0, 0.1f, 0.01f, 0.1f, yaw, 10, 0), hold + 10);
                Displays.animateNextTick(ctx(), owner, finger,
                        Displays.transform(0, 0.32f, 0, 0.1f, 0.7f, 0.1f, yaw, 28, 0), 3);
                ctx().runLater(owner, 4, () -> Displays.animate(finger,
                        Displays.transform(0, 0.3f, 0, 0.1f, 0.66f, 0.1f, yaw, 38, 0), 4));
                ctx().runLater(owner, Math.max(5, hold), () -> {
                    if (finger.isValid()) {
                        Displays.shrinkAway(ctx(), owner, finger, 5);
                    }
                });
            }
            center.getWorld().spawnParticle(Particle.BLOCK, center.clone().add(0, 0.2, 0), Vfx.n(ctx(), 8), 0.3, 0.1,
                    0.3, 0.1, SOUL_SOIL);
        }
    }

    /** Spawns one undead at {@code at} as the owner's summon and starts its controller. Null if it did not spawn. */
    private @Nullable Mob raiseUndead(Location at, boolean skeleton, long lifetime) {
        double health = Math.max(1, params().num(skeleton ? "skeleton_health" : "zombie_health"));
        double damage = Math.max(0, params().num(skeleton ? "skeleton_damage" : "zombie_damage"));
        boolean peaceful = at.getWorld().getDifficulty() == Difficulty.PEACEFUL;
        Consumer<Mob> setup = spawned -> {
            spawned.setPersistent(false);
            setBase(spawned, Attribute.MAX_HEALTH, health);
            setBase(spawned, Attribute.ATTACK_DAMAGE, damage);
            setBase(spawned, Attribute.MOVEMENT_SPEED, skeleton ? SKELETON_SPEED : ZOMBIE_SPEED);
            spawned.setHealth(Math.min(health, Combat.maxHealth(spawned)));
            dress(spawned.getEquipment(), skeleton);
            if (peaceful) {
                // Monsters are discarded on Peaceful the tick they spawn unless told otherwise.
                spawned.setDespawnInPeacefulOverride(TriState.FALSE);
            }
            spawned.setCustomNameVisible(true);
        };
        // No random data: always an adult, no random gear, no reinforcement calls.
        Mob mob = skeleton ? at.getWorld().spawn(at, Skeleton.class, false, setup)
                : at.getWorld().spawn(at, Zombie.class, false, setup);
        if (!mob.isValid()) {
            return null;
        }
        ctx().summon(player(), mob);
        Undead undead = new Undead(mob, skeleton ? "해골 병사" : "망자", damage, lifetime);
        legion.add(undead);
        undead.start();
        return mob;
    }

    /**
     * A soul-cyan leather cap (unbreakable: any helmet keeps the undead from burning in daylight) and, for skeletons, a
     * stone sword, which makes them fight in melee (no stray arrows left in the world). Nothing drops.
     */
    private static void dress(EntityEquipment equipment, boolean skeleton) {
        ItemStack cap = ItemStack.of(Material.LEATHER_HELMET);
        cap.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(SOUL_CYAN));
        cap.setData(DataComponentTypes.UNBREAKABLE);
        equipment.setHelmet(cap, true);
        if (skeleton) {
            equipment.setItemInMainHand(ItemStack.of(Material.STONE_SWORD), true);
        }
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.HAND}) {
            equipment.setDropChance(slot, 0f);
        }
    }

    /** One raised undead and the controller task that keeps it fighting until its time runs out. */
    private final class Undead {
        private final Mob mob;
        private final String label;
        private final double damage;
        private final long expiresAt;
        private boolean dismissed;
        private long nextStrike;

        Undead(Mob mob, String label, double damage, long lifetime) {
            this.mob = mob;
            this.label = label;
            this.damage = damage;
            this.expiresAt = ctx().currentTick() + Math.max(1, lifetime);
        }

        void start() {
            updateName(expiresAt - ctx().currentTick());
            long runs = (expiresAt - ctx().currentTick()) / CONTROL_PERIOD + 2;
            Loop.repeat(ctx(), player(), CONTROL_PERIOD, CONTROL_PERIOD, runs, this::control,
                    () -> dismiss(this, true));
        }

        private boolean control(long run) {
            if (dismissed) {
                return false;
            }
            if (!mob.isValid() || mob.isDead()) {
                legion.remove(this);
                dismissed = true;
                crumble(mob.getLocation(), false);
                return false;
            }
            long left = expiresAt - ctx().currentTick();
            if (left <= 0) {
                return false;
            }
            if (run % (20 / CONTROL_PERIOD) == 0) {
                updateName(left);
            }
            stopConversion();
            LivingEntity target = chooseTarget();
            if (target != mob.getTarget()) {
                mob.setTarget(target);
            }
            if (target instanceof Player victim && mob.getWorld().getDifficulty() == Difficulty.PEACEFUL) {
                strike(victim);
            }
            if (target == null) {
                recallIfStrayed();
            }
            wisps(run);
            return true;
        }

        /** A zombie under water or a skeleton in powder snow would turn into a new, untracked mob. */
        private void stopConversion() {
            if (mob instanceof Zombie zombie && zombie.isConverting()) {
                zombie.setConversionTime(-1);
            } else if (mob instanceof Skeleton skeleton && skeleton.isConverting()) {
                skeleton.setConversionTime(-1);
            }
        }

        private @Nullable LivingEntity chooseTarget() {
            double range = Math.max(1, params().num("aggro_range"));
            double leash = range * LEASH_FACTOR;
            LivingEntity focused = focus;
            if (focused != null && ctx().currentTick() <= focusUntil && isValidTarget(focused, leash)) {
                return focused;
            }
            LivingEntity current = mob.getTarget();
            if (current != null && isValidTarget(current, leash)) {
                return current;
            }
            for (LivingEntity candidate : ctx().enemiesNear(player(), mob.getLocation(), range)) {
                if (isVisible(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private boolean isValidTarget(LivingEntity target, double leash) {
            return Combat.isTargetable(ctx(), player(), target) && isVisible(target)
                    && target.getWorld().equals(mob.getWorld())
                    && target.getLocation().distanceSquared(mob.getLocation()) <= leash * leash;
        }

        /**
         * Peaceful only: vanilla mob attacks on players are dropped there, so the undead strikes through the context
         * (credited, enemy-only) at the vanilla attack pace once it is in reach.
         */
        private void strike(Player target) {
            if (!mob.getBoundingBox().expand(STRIKE_REACH).overlaps(target.getBoundingBox())) {
                mob.getPathfinder().moveTo(target, 1.2);
                return;
            }
            long now = ctx().currentTick();
            if (now < nextStrike) {
                return;
            }
            nextStrike = now + STRIKE_TICKS;
            summonStrike = true;
            try {
                Combat.hit(ctx(), player(), target, damage, DamageType.MOB_ATTACK);
            } finally {
                summonStrike = false;
            }
            Vfx.cloud(ctx(), Fx.body(target), Particle.DUST_COLOR_TRANSITION, 5, 0.25, 0, PALETTE.glow(0.8f), false);
        }

        /** Bound to the necromancer: an idle undead that strayed too far sinks and climbs out again at its side. */
        private void recallIfStrayed() {
            Player owner = player();
            if (owner.getWorld().equals(mob.getWorld())
                    && owner.getLocation().distanceSquared(mob.getLocation()) <= RECALL_DISTANCE * RECALL_DISTANCE) {
                return;
            }
            Location eye = owner.getEyeLocation();
            Vector back = Motion.horizontalFacing(owner.getLocation()).multiply(-1);
            for (double angle : new double[] {0, 50, -50, 100, -100}) {
                Location spot = standable(owner.getLocation().add(back.clone().rotateAroundY(Math.toRadians(angle))
                        .multiply(1.8)), eye);
                if (spot != null) {
                    crumble(mob.getLocation(), true);
                    mob.teleport(spot);
                    Vfx.helix(ctx(), spot.clone().add(0, 0.05, 0), 0.45, 1.8, 1.0, 2, 8, 0,
                            Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.8f), false);
                    spot.getWorld().spawnParticle(Particle.BLOCK, spot.clone().add(0, 0.2, 0), Vfx.n(ctx(), 10), 0.3,
                            0.1, 0.3, 0.1, SOUL_SOIL);
                    Fx.sound(spot, Sound.BLOCK_SOUL_SOIL_BREAK, 1.0f, 0.8f + Fx.jitter(0.1f));
                    return;
                }
            }
        }

        /** Soul fire licking off the undead: a flame or two per update and now and then a rising soul. */
        private void wisps(long run) {
            Location base = mob.getLocation();
            double height = mob.getHeight();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double angle = random.nextDouble(TAU);
            Location at = base.clone().add(Math.cos(angle) * 0.35, height * (0.3 + random.nextDouble() * 0.6),
                    Math.sin(angle) * 0.35);
            Vfx.dot(at, Particle.SOUL_FIRE_FLAME, null, false);
            if (run % 2 == 0) {
                Vfx.dot(base.clone().add(0, 0.05, 0), Particle.DUST, PALETTE.dust(0.9f), false);
            }
            if (run % 6 == 3) {
                Vfx.dot(base.clone().add(0, height * 0.9, 0), Particle.SOUL, null, false);
            }
        }

        private void updateName(long ticksLeft) {
            long seconds = Math.max(0, (ticksLeft + 19) / 20);
            mob.customName(Component.text(player().getName() + "의 " + label, NAME_COLOR)
                    .append(Component.text(" " + seconds + "초", NamedTextColor.GRAY)));
        }
    }

    /** Removes an undead, crumbling it into bone and soul soil. Safe to call more than once. */
    private void dismiss(Undead undead, boolean effects) {
        legion.remove(undead);
        if (undead.dismissed) {
            return;
        }
        undead.dismissed = true;
        Mob mob = undead.mob;
        if (!mob.isValid()) {
            return;
        }
        if (effects) {
            crumble(mob.getLocation(), false);
            Fx.sound(mob.getLocation(), mob instanceof Skeleton ? Sound.ENTITY_SKELETON_DEATH
                    : Sound.ENTITY_ZOMBIE_DEATH, 0.7f, 1.2f + Fx.jitter(0.1f));
        }
        mob.remove();
    }

    /** An undead falling apart: bone and soul soil fragments, fading cyan dust and its soul leaving upwards. */
    private void crumble(Location at, boolean quiet) {
        World world = at.getWorld();
        Location body = at.clone().add(0, 0.9, 0);
        world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 14), 0.25, 0.5, 0.25, 0.1, BONE_BLOCK);
        world.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.2, 0), Vfx.n(ctx(), 8), 0.3, 0.1, 0.3, 0.1, SOUL_SOIL);
        Vfx.sphere(ctx(), body, 0.5, 12, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.0f), false);
        Vfx.cloud(ctx(), body, Particle.SOUL, 3, 0.25, 0.04, null, false);
        if (!quiet) {
            Fx.sound(at, Sound.PARTICLE_SOUL_ESCAPE, 1.0f, 1.2f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_BONE_BLOCK_BREAK, 0.8f, 0.7f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Players hidden from the owner (stealth hides them from every enemy) are off-limits until they show themselves
     * again: skulls and undead never chase what the necromancer cannot see.
     */
    private boolean isVisible(LivingEntity target) {
        return !(target instanceof Player hidden) || player().canSee(hidden);
    }

    /** {@code at} looking along {@code direction} (the display's facing). */
    private static Location facing(Location at, Vector direction) {
        Location look = at.clone();
        if (direction.lengthSquared() > 1.0E-6) {
            look.setDirection(direction);
        }
        return look;
    }

    /** A point on the quadratic Bézier curve from {@code a} over {@code control} to {@code b}. */
    private static Location bezier(Location a, Location control, Location b, double t) {
        double u = 1 - t;
        return new Location(a.getWorld(),
                u * u * a.getX() + 2 * u * t * control.getX() + t * t * b.getX(),
                u * u * a.getY() + 2 * u * t * control.getY() + t * t * b.getY(),
                u * u * a.getZ() + 2 * u * t * control.getZ() + t * t * b.getZ());
    }

    /** A ring of dust perpendicular to {@code direction}, centered on {@code center}. */
    private void aimRing(Location center, Vector direction, double radius, int points,
                         Particle.DustTransition dust) {
        Vector axis = direction.clone().normalize();
        Vector u = axis.clone().crossProduct(Math.abs(axis.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0))
                .normalize();
        Vector v = axis.clone().crossProduct(u).normalize();
        int count = Vfx.n(ctx(), points);
        World world = center.getWorld();
        for (int i = 0; i < count; i++) {
            double angle = i * TAU / count;
            Location at = center.clone().add(u.clone().multiply(Math.cos(angle) * radius))
                    .add(v.clone().multiply(Math.sin(angle) * radius));
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0, dust);
        }
    }

    /** Sets an attribute's base value, registering the attribute first if this kind of mob lacks it. */
    private static void setBase(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            entity.registerAttribute(attribute);
            instance = entity.getAttribute(attribute);
        }
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }
}
