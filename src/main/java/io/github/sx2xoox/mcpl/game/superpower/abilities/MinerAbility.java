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
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 광부 — a miner with a lantern and a pickaxe. Every melee hit chips at the enemy like at a rock face (its arms grow
 * heavy with mining fatigue) and digs out a bit of ore that quickens the miner's own pick; the active brings the
 * ceiling down on an aimed spot: rocks hang overhead for a moment, crash down one after another and leave rubble that
 * blocks the way; the ultimate lays a rail track along the ground and sends a runaway mine cart down it, which scoops
 * up every enemy on the rails and dumps them at the end of the line.
 *
 * <p>Unlike the 창술사's charge (the lancer itself rushes and skewers), the miner stays put: the track is laid first,
 * so it can be seen and stepped off, it follows the ground, and it is the cart that carries the enemies away.
 *
 * <p>Look: stone grey, ore colors and lantern orange. Hits throw stone crumbs and a glint of ore; the dug ores (coal,
 * gold, diamond) circle the miner's waist while its pick is quickened. The rockfall opens with a crack in the air,
 * dust and pebbles trickling down and dark marks on the ground under each hanging rock; the rocks slam down with dust
 * plumes and turn into rubble of cobble, tuff and ore. The ultimate clicks rails down one length after another, ends
 * them with a red buffer stop and pushes a cauldron-like cart heaped with ore and hung with a lantern down the line,
 * clattering faster and faster; at the end it tips over, ore flying everywhere.
 */
public final class MinerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("miner", "광부", Material.IRON_PICKAXE)
            .color(TextColor.color(0xE8A33D))
            .difficulty(2)
            .passive("광맥",
                    "근접 공격한 적에게 {fatigue_duration}초간 채굴 피로 {fatigue_level}단계(단계당 공격 속도 -10%)를 "
                            + "겁니다. 적중할 때마다 광맥을 캐내 {vein_duration}초간 성급함이 한 단계씩 쌓입니다(최대 "
                            + "{vein_max}단계, 단계당 공격 속도 +10%).")
            .active("낙석",
                    "조준한 곳({rock_range}블록 이내)의 머리 위에 금이 가고 {rock_delay}초 뒤 반경 {rock_radius}블록에 "
                            + "바위 {rock_count}개가 차례로 쏟아집니다. 바위에 맞은 적은 {rock_damage} 피해를 입고 "
                            + "{rock_slow_duration}초간 이동 속도가 {rock_slow}% 느려지며(한 적은 최대 {rock_max_hits}번), "
                            + "떨어진 바위는 {rubble_duration}초간 잔해로 남아 길을 막습니다.",
                    10)
            .ultimate("광산 수레",
                    "앞쪽 땅을 따라 {cart_length}블록 레일을 깔고 {cart_delay}초 뒤 광석을 가득 실은 수레를 밀어 보냅니다. "
                            + "점점 빨라지는 수레는 레일 위의 적을 쓸어 담아 {cart_damage} 피해를 주고 싣고 달리다가, "
                            + "레일 끝에서 뒤집히며 실린 적에게 {crash_damage} 피해를 주고 앞으로 내동댕이칩니다.")
            .param("fatigue_duration", 3)
            .param("fatigue_level", 2)
            .param("vein_duration", 5)
            .param("vein_max", 3)
            .param("rock_range", 20)
            .param("rock_delay", 0.8)
            .param("rock_radius", 3)
            .param("rock_count", 6)
            .param("rock_damage", 2)
            .param("rock_hit_radius", 1.3)
            .param("rock_slow", 35)
            .param("rock_slow_duration", 1.5)
            .param("rock_max_hits", 2)
            .param("rubble_duration", 4)
            .param("cart_length", 16)
            .param("cart_delay", 0.6)
            .param("cart_damage", 4)
            .param("crash_damage", 4)
            .build();

    private static final String COLOR_TAG = "#E8A33D";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** Lantern light, lantern orange, the dark of the mine. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFE2A8, 0xE8A33D, 0x2B2622);
    private static final Particle.DustTransition LANTERN = PALETTE.glow(1.0f);
    private static final Particle.DustOptions STONE_DUST = new Particle.DustOptions(Color.fromRGB(0x8A8580), 1.3f);
    private static final Particle.DustOptions DARK_MARK = new Particle.DustOptions(Color.fromRGB(0x2B2622), 1.2f);
    private static final Particle.DustOptions RAIL_DUST = new Particle.DustOptions(Color.fromRGB(0x9C9C9C), 0.8f);

    /** Block and item types of the visuals (block data is created on use: it needs a running server). */
    private static final Material[] ROCKS = {Material.STONE, Material.COBBLESTONE, Material.TUFF,
            Material.COBBLED_DEEPSLATE, Material.ANDESITE};
    private static final Material[] ORES = {Material.COAL_ORE, Material.IRON_ORE, Material.GOLD_ORE,
            Material.DEEPSLATE_IRON_ORE, Material.COPPER_ORE};
    private static final Material[] RUBBLE = {Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.TUFF,
            Material.COBBLED_DEEPSLATE, Material.ANDESITE, Material.COAL_ORE, Material.IRON_ORE};
    private static final Material[] GEMS = {Material.COAL, Material.RAW_GOLD, Material.DIAMOND, Material.EMERALD};
    private static final Material RAIL = Material.RAIL;
    private static final Material CART = Material.CAULDRON;
    private static final Material LAMP = Material.LANTERN;
    private static final Material BUFFER = Material.RED_TERRACOTTA;

    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    /** Caps every velocity this ability applies. */
    private static final double MAX_SPEED = 2.0;

    // passive: vein
    private static final long GEM_PERIOD = 2;
    private static final double GEM_RADIUS = 0.6;

    // active: rockfall
    private static final double HANG_HEIGHT = 7;
    private static final int GROUND_SNAP = 8;
    private static final long ROCK_GAP = 3;
    private static final int ROCK_FALL_TICKS = 5;
    private static final double MIN_SPACING = 1.1;

    // ultimate: runaway cart
    private static final double TRACK_STEP = 0.5;
    /**
     * The width the track needs free at each point: just the rails. The track points sit a few tenths from block edges,
     * so a body-wide check would refuse a point right before a one-block step (rubble, a stair) it can climb.
     */
    private static final double RAIL_GAUGE = 0.3;
    private static final double TRACK_START = 1.2;
    private static final double MAX_STEP_UP = 1.05;
    private static final double MAX_STEP_DOWN = 2.5;
    private static final double RAIL_SEGMENT = 2.0;
    private static final double CART_START_SPEED = 0.45;
    private static final double CART_ACCEL = 0.06;
    private static final double CART_MAX_SPEED = 1.2;
    private static final double SCOOP_RADIUS = 1.25;
    private static final double SCOOP_HEIGHT = 1.6;
    private static final double RIDE_HEIGHT = 0.7;
    private static final double RIDE_PULL = 0.55;
    private static final double RIDE_LEASH = 3.0;
    private static final double FLING_FORWARD = 1.3;
    private static final double FLING_UP = 0.65;
    private static final long MAX_LAG_TICKS = 6;
    private static final double MILLIS_PER_TICK = 50.0;
    private static final long RAIL_LINGER_TICKS = 16;

    // vein state
    private int veins;
    private long veinUntil;
    private final List<ItemDisplay> gems = new ArrayList<>();
    private final List<Display> displays = new ArrayList<>();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        veins = 0;
        gems.clear();
        displays.clear();
    }

    // ------------------------------------------------------------------ passive: vein

    /** The owner's own melee swing at an enemy (not ability damage, projectiles or summons). */
    private boolean isOwnMelee(EntityDamageByEntityEvent event, LivingEntity victim) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && event.getCause() == DamageCause.ENTITY_ATTACK && ctx().isEnemy(player(), victim);
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnMelee(event, victim)) {
            return;
        }
        Player owner = player();
        int fatigue = (int) Math.min(Integer.MAX_VALUE, params().ticks("fatigue_duration"));
        int level = Math.clamp(params().integer("fatigue_level"), 0, 5);
        if (fatigue > 0 && level > 0) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, fatigue, level - 1, false, true,
                    true));
        }
        int max = Math.max(0, params().integer("vein_max"));
        long duration = params().ticks("vein_duration");
        if (max > 0 && duration > 0) {
            int before = veins;
            veins = Math.min(max, veins + 1);
            veinUntil = ctx().currentTick() + duration;
            owner.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, (int) Math.min(Integer.MAX_VALUE, duration),
                    veins - 1, true, false, true));
            if (veins > before) {
                addGem(owner);
            }
        }
        chipFx(owner, victim);
    }

    /** A pick striking stone: crumbs of rock and a glint of ore on the victim, a ringing clink. */
    private void chipFx(Player owner, LivingEntity victim) {
        Location body = Fx.body(victim);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        World world = body.getWorld();
        world.spawnParticle(Particle.BLOCK_CRUMBLE, body, Vfx.n(ctx(), 6), 0.25, 0.35, 0.25, 0,
                ROCKS[random.nextInt(ROCKS.length)].createBlockData());
        world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 5), 0.2, 0.3, 0.2, 0.1,
                ORES[random.nextInt(ORES.length)].createBlockData());
        Vfx.cloud(ctx(), body, Particle.WAX_OFF, 2, 0.2, 0.2, null, false);
        Fx.sound(body, Sound.BLOCK_STONE_HIT, 0.9f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE, 0.45f, 1.2f + 0.15f * veins);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⛏ 광맥</" + COLOR_TAG + "> <white>" + meter(veins,
                Math.max(1, params().integer("vein_max"))) + "</white> <gray>성급함 " + veins + "단계</gray>"));
    }

    /** A dug-out ore joins the ring circling the miner's waist. */
    private void addGem(Player owner) {
        gems.removeIf(gem -> !gem.isValid());
        if (gems.size() >= veins || !reserve(1)) {
            return;
        }
        Material type = GEMS[Math.min(GEMS.length - 1, gems.size())];
        ItemDisplay gem = Displays.item(ctx(), owner, waist(owner), new ItemStack(type),
                Displays.centered(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), 0);
        gem.setTeleportDuration((int) GEM_PERIOD);
        gems.add(gem);
        displays.add(gem);
        Displays.animateNextTick(ctx(), owner, gem, Displays.centered(0, 0, 0, 0.3f, 0.3f, 0.3f, 0, 0, 0), 4);
        Fx.sound(waist(owner), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f + 0.2f * gems.size());
    }

    @Override
    public void onTick(long tick) {
        if (veins <= 0) {
            return;
        }
        Player owner = player();
        if (ctx().currentTick() >= veinUntil) {
            veins = 0;
            owner.removePotionEffect(PotionEffectType.HASTE);
            dropGems(owner);
            return;
        }
        if (tick % GEM_PERIOD == 0) {
            orbit(owner, tick);
        }
    }

    /** The ores circle the waist, bobbing, while a lantern glow hangs at the miner's side. */
    private void orbit(Player owner, long tick) {
        gems.removeIf(gem -> !gem.isValid());
        Location waist = waist(owner);
        int count = gems.size();
        for (int i = 0; i < count; i++) {
            double angle = tick * 0.18 + i * TAU / Math.max(1, count);
            Location at = waist.clone().add(Math.cos(angle) * GEM_RADIUS, 0.08 * Math.sin(tick * 0.25 + i),
                    Math.sin(angle) * GEM_RADIUS);
            ItemDisplay gem = gems.get(i);
            Displays.moveTo(gem, at);
            Displays.animate(gem, Displays.centered(0, 0, 0, 0.3f, 0.3f, 0.3f, (float) (tick * 12 % 360), 0, 0),
                    (int) GEM_PERIOD);
        }
        if (tick % 8 == 0) {
            Vfx.dot(waist.clone().add(0, 0.25, 0), Particle.DUST_COLOR_TRANSITION, LANTERN, false);
        }
    }

    /** The quickening is over: the ores drop and crumble. */
    private void dropGems(Player owner) {
        for (ItemDisplay gem : gems) {
            if (gem.isValid()) {
                Vfx.cloud(ctx(), gem.getLocation(), Particle.BLOCK_CRUMBLE, 3, 0.05, 0, Material.STONE.createBlockData(),
                        false);
                Displays.shrinkAway(ctx(), owner, gem, 4);
            }
        }
        gems.clear();
        Fx.sound(owner.getLocation(), Sound.BLOCK_STONE_FALL, 0.6f, 1.2f);
    }

    private static Location waist(Player owner) {
        return owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
    }

    private static String meter(int filled, int max) {
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < max; i++) {
            bar.append(i < filled ? '◆' : '◇');
        }
        return bar.toString();
    }

    // ------------------------------------------------------------------ active: rockfall

    @Override
    public ActivationResult onActive() {
        Location center = aimPoint(params().num("rock_range"));
        if (center == null) {
            return ActivationResult.fail("바위를 떨어뜨릴 땅이 없습니다");
        }
        new Rockfall(player(), center).start();
        return ActivationResult.success();
    }

    /**
     * Where the rocks fall: around the aimed enemy, or where the view ray hits a block (or ends), snapped down to the
     * ground. Null when there is no ground close below.
     */
    private @Nullable Location aimPoint(double range) {
        Player owner = player();
        LivingEntity aimed = ctx().findTarget(owner, range);
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (!(direction.lengthSquared() > 1.0E-8)) {
            direction = Motion.horizontalFacing(eye);
        }
        direction.normalize();
        Location point;
        if (aimed != null) {
            point = aimed.getLocation().add(0, 0.5, 0);
        } else {
            RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            point = hit == null
                    ? eye.clone().add(direction.clone().multiply(range))
                    : hit.getHitPosition().toLocation(owner.getWorld()).subtract(direction.clone().multiply(0.3));
        }
        return ground(point);
    }

    /** {@code point} lowered onto the ground below it (the probe starts above the surface), null if there is none. */
    private static @Nullable Location ground(Location point) {
        Location probe = point.clone().add(0, 0.05, 0);
        double before = probe.getY();
        Spots.snapToGround(probe.getWorld(), probe, GROUND_SNAP);
        if (probe.getY() >= before - 1.0E-6) {
            return null;
        }
        probe.setYaw(0);
        probe.setPitch(0);
        return probe;
    }

    /** One rock: where it lands and its display hanging overhead. */
    private record Rock(Location landing, @Nullable BlockDisplay display, Material type, float size) {
    }

    /**
     * The cave-in: a crack opens overhead, rocks gather and hang over their landing spots (marked on the ground), then
     * fall one after another; each lands as rubble where there is room.
     */
    private final class Rockfall implements LongPredicate {

        private final Player owner;
        private final Location center;
        private final World world;
        private final long delay;
        private final double radius;
        private final List<Rock> rocks = new ArrayList<>();
        private final Map<UUID, Integer> hits = new HashMap<>();
        private int landed;
        private int struck;

        Rockfall(Player owner, Location center) {
            this.owner = owner;
            this.center = center;
            this.world = center.getWorld();
            this.delay = Math.max(4, params().ticks("rock_delay"));
            this.radius = Math.max(0.5, params().num("rock_radius"));
        }

        void start() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int count = Math.max(1, params().integer("rock_count"));
            List<Location> spots = new ArrayList<>();
            spots.add(center.clone());
            for (int attempt = 0; spots.size() < count && attempt < count * 8; attempt++) {
                Location candidate = Vfx.scatter(center, radius);
                candidate.setY(center.getY() + 2.0);
                Location floor = ground(candidate);
                if (floor == null || Math.abs(floor.getY() - center.getY()) > 2.5) {
                    continue;
                }
                boolean spaced = true;
                for (Location other : spots) {
                    if (horizontal(other, floor) < MIN_SPACING) {
                        spaced = false;
                        break;
                    }
                }
                if (spaced) {
                    spots.add(floor);
                }
            }
            for (Location spot : spots) {
                Material type = ROCKS[random.nextInt(ROCKS.length)];
                float size = 0.65f + random.nextFloat() * 0.35f;
                BlockDisplay display = null;
                if (reserve(1)) {
                    Location hang = hangSpot(spot);
                    Quaternionf tumble = randomRotation(random);
                    display = Displays.block(ctx(), owner, hang, type.createBlockData(), cube(0.05f, tumble),
                            delay + ROCK_GAP * spots.size() + ROCK_FALL_TICKS + 10);
                    display.setTeleportDuration(1);
                    displays.add(display);
                    Displays.animateNextTick(ctx(), owner, display, cube(size, tumble), 6);
                }
                rocks.add(new Rock(spot, display, type, size));
            }
            Location crack = center.clone().add(0, HANG_HEIGHT + 0.6, 0);
            world.spawnParticle(Particle.BLOCK, crack, Vfx.n(ctx(), 18), radius * 0.4, 0.2, radius * 0.4, 0.05,
                    Material.STONE.createBlockData(), true);
            Fx.sound(center, Sound.BLOCK_DEEPSLATE_HIT, 1.2f, 0.5f);
            Fx.sound(center, Sound.BLOCK_GRAVEL_FALL, 1.0f, 0.6f);
            Fx.sound(owner.getLocation(), Sound.BLOCK_STONE_HIT, 0.8f, 0.6f);
            long runs = delay + ROCK_GAP * Math.max(0, rocks.size() - 1) + ROCK_FALL_TICKS + 1;
            Loop.repeat(ctx(), owner, 1, 1, runs, this, this::finish);
        }

        private Location hangSpot(Location landing) {
            return landing.clone().add(0, HANG_HEIGHT, 0);
        }

        @Override
        public boolean test(long tick) {
            if (tick < delay) {
                telegraph(tick);
            }
            for (int i = 0; i < rocks.size(); i++) {
                long fallStart = delay - ROCK_FALL_TICKS + ROCK_GAP * i;
                long t = tick - fallStart;
                Rock rock = rocks.get(i);
                if (t >= 0 && t < ROCK_FALL_TICKS) {
                    double drop = (t + 1) / (double) ROCK_FALL_TICKS;
                    BlockDisplay display = rock.display();
                    if (display != null && display.isValid()) {
                        Location at = hangSpot(rock.landing()).subtract(0, HANG_HEIGHT * drop * drop, 0);
                        at.add(0, rock.size() / 2, 0);
                        Displays.moveTo(display, at);
                        Vfx.dot(at.clone().add(0, 0.6, 0), Particle.DUST, STONE_DUST, false);
                    }
                    if (t == 0) {
                        Fx.sound(rock.landing(), Sound.BLOCK_POINTED_DRIPSTONE_FALL, 0.8f, 0.7f + Fx.jitter(0.1f));
                    }
                } else if (t == ROCK_FALL_TICKS) {
                    impact(rock);
                }
            }
            return true;
        }

        /** Dust and pebbles trickle from the crack, each landing spot darkens and the warning ring closes in. */
        private void telegraph(long tick) {
            Location crack = center.clone().add(0, HANG_HEIGHT + 0.7, 0);
            if (tick % 2 == 0) {
                world.spawnParticle(Particle.FALLING_DUST, crack, Vfx.n(ctx(), 4), radius * 0.5, 0.1, radius * 0.5, 0,
                        Material.GRAVEL.createBlockData());
                Vfx.ring(ctx(), center.clone().add(0, 0.06, 0), radius, (int) Math.max(14, radius * 7), tick * 0.15,
                        Particle.DUST_COLOR_TRANSITION, LANTERN, true);
                for (Rock rock : rocks) {
                    double r = 0.6 * Math.min(1.0, (tick + 2) / (double) delay);
                    Vfx.ring(ctx(), rock.landing().clone().add(0, 0.05, 0), r, 8, tick * 0.3, Particle.DUST,
                            DARK_MARK, false);
                }
            }
            if (tick % 5 == 0) {
                Fx.sound(crack, Sound.BLOCK_GRAVEL_FALL, 0.6f, 0.7f + Fx.jitter(0.1f));
            }
        }

        /** A rock lands: it hits the enemies close by (each at most a few times) and is left as rubble. */
        private void impact(Rock rock) {
            landed++;
            Location spot = rock.landing();
            BlockDisplay display = rock.display();
            world.spawnParticle(Particle.BLOCK, spot.clone().add(0, 0.3, 0), Vfx.n(ctx(), 14), 0.35, 0.2, 0.35, 0.12,
                    rock.type().createBlockData(), true);
            world.spawnParticle(Particle.DUST_PLUME, spot.clone().add(0, 0.1, 0), Vfx.n(ctx(), 6), 0.3, 0.05, 0.3, 0.03,
                    null, false);
            Vfx.ring(ctx(), spot.clone().add(0, 0.08, 0), 0.9, 12, 0, Particle.DUST, STONE_DUST, false);
            Fx.sound(spot, Sound.BLOCK_STONE_BREAK, 1.2f, 0.6f + Fx.jitter(0.08f));
            Fx.sound(spot, Sound.BLOCK_DEEPSLATE_FALL, 1.0f, 0.7f + Fx.jitter(0.08f));
            Fx.sound(spot, Sound.ITEM_MACE_SMASH_GROUND, 0.6f, 1.1f + Fx.jitter(0.08f));

            double damage = params().num("rock_damage");
            double reach = Math.max(0.3, params().num("rock_hit_radius"));
            int maxHits = Math.max(1, params().integer("rock_max_hits"));
            long slowTicks = params().ticks("rock_slow_duration");
            double slow = Math.clamp(params().num("rock_slow") / 100.0, 0.0, 1.0);
            for (LivingEntity enemy : ctx().enemiesNear(owner, spot.clone().add(0, 0.5, 0), reach)) {
                if (Math.abs(enemy.getLocation().getY() - spot.getY()) > 2.2) {
                    continue;
                }
                int count = hits.merge(enemy.getUniqueId(), 1, Integer::sum);
                if (count > maxHits) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                struck++;
                if (Combat.isTargetable(ctx(), owner, enemy) && slowTicks > 0 && slow > 0) {
                    ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                            slowTicks);
                }
                if (!isHidden(owner, enemy)) {
                    Vfx.cloud(ctx(), Fx.body(enemy), Particle.BLOCK_CRUMBLE, 6, 0.25, 0, rock.type().createBlockData(),
                            false);
                }
            }
            boolean rubble = placeRubble(spot);
            if (display != null && display.isValid()) {
                if (rubble) {
                    display.remove();
                } else {
                    // No room for rubble (someone stands there): the rock bursts apart.
                    Displays.shrinkAway(ctx(), owner, display, 3);
                }
            }
        }

        /** The rock stays as a block of rubble when the spot is open air on solid ground with nobody in it. */
        private boolean placeRubble(Location spot) {
            long ticks = params().ticks("rubble_duration");
            if (ticks <= 0) {
                return false;
            }
            Block block = world.getBlockAt(spot.getBlockX(), (int) Math.floor(spot.getY() + 1.0E-3), spot.getBlockZ());
            Block below = block.getRelative(0, -1, 0);
            if (!below.getType().isSolid() || isOccupied(block)) {
                return false;
            }
            Material type = RUBBLE[ThreadLocalRandom.current().nextInt(RUBBLE.length)];
            return ctx().placeTempBlock(block, type.createBlockData(), ticks);
        }

        /** The dust settles. */
        void finish() {
            for (Rock rock : rocks) {
                BlockDisplay display = rock.display();
                if (display != null && display.isValid()) {
                    display.remove();
                }
            }
            Loop.repeat(ctx(), owner, 0, 3, 4, run -> {
                Vfx.cloud(ctx(), Vfx.scatter(center, radius).add(0, 0.5 + run * 0.2, 0), Particle.DUST, 3, 0.4, 0,
                        STONE_DUST, false);
                return true;
            });
            if (struck > 0) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⛏ 낙석</" + COLOR_TAG + "> <white>" + struck
                        + "회 적중</white> <gray>· 바위 " + landed + "개</gray>"));
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: runaway mine cart

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        List<Location> track = layTrack(owner, Math.max(2, params().num("cart_length")));
        if (track.size() < 4) {
            return ActivationResult.fail("레일을 깔 자리가 없습니다");
        }
        new MineCart(owner, track).start();
        return ActivationResult.success();
    }

    /**
     * The track: points every half block along the miner's facing, each on the ground. It ends before a step up of
     * more than a block, a drop deeper than a few blocks (or the void), a wall, or the arena border.
     */
    private List<Location> layTrack(Player owner, double length) {
        World world = owner.getWorld();
        Location feet = owner.getLocation();
        Vector forward = Motion.horizontalFacing(feet);
        List<Location> points = new ArrayList<>();
        double previousY = feet.getY();
        for (double d = TRACK_START; d <= TRACK_START + length + 1.0E-6; d += TRACK_STEP) {
            Location probe = feet.clone().add(forward.clone().multiply(d));
            probe.setY(previousY + MAX_STEP_UP + 0.05);
            Location floor = probe.clone();
            Spots.snapToGround(world, floor, MAX_STEP_UP + MAX_STEP_DOWN + 0.1);
            if (floor.getY() >= probe.getY() - 1.0E-6 || previousY - floor.getY() > MAX_STEP_DOWN
                    || floor.getY() - previousY > MAX_STEP_UP || !Spots.isFree(world, floor, RAIL_GAUGE, 1.0)
                    || !Spots.hasGroundBelow(world, floor) || !ctx().isInsideArena(floor)) {
                break;
            }
            // Checked above a step's height, so a one-block step up does not count as a wall; the cart also needs
            // half a step of room ahead of every rail, so the line ends (and the buffer stop stands) before a wall.
            Location chest = floor.clone().add(0, 1.2, 0);
            if (!points.isEmpty() && !Spots.clearPath(points.getLast().clone().add(0, 1.2, 0), chest)
                    || !Spots.clearPath(chest, chest.clone().add(forward.clone().multiply(TRACK_STEP)))) {
                break;
            }
            floor.setYaw(0);
            floor.setPitch(0);
            points.add(floor);
            previousY = floor.getY();
        }
        return points;
    }

    /**
     * One enemy riding in the cart, with where it will be once it has followed every push sent so far (dead
     * reckoning): a player's position on the server trails its client by its ping.
     */
    private static final class Rider {
        final LivingEntity entity;
        final int seat;
        double x;
        double z;

        Rider(LivingEntity entity, int seat) {
            this.entity = entity;
            this.seat = seat;
            this.x = entity.getLocation().getX();
            this.z = entity.getLocation().getZ();
        }
    }

    /**
     * The runaway cart: the rails click down along the track, the cart is pushed off and speeds up down the line,
     * scooping enemies off the rails and carrying them along, and tips over at the buffer stop, throwing them out.
     */
    private final class MineCart implements LongPredicate {

        private final Player owner;
        private final List<Location> track;
        private final World world;
        private final Vector forward;
        private final float yaw;
        private final long delay;
        private final double length;
        private final List<double[]> railSegments;
        private final List<BlockDisplay> rails = new ArrayList<>();
        private final List<BlockDisplay> cart = new ArrayList<>();
        private @Nullable BlockDisplay buffer;
        private final List<Rider> riders = new ArrayList<>();
        private final Set<UUID> scooped = new HashSet<>();
        private double travelled;
        private double speed = CART_START_SPEED;
        private long rideTick = -1;
        private boolean crashed;

        MineCart(Player owner, List<Location> track) {
            this.owner = owner;
            this.track = track;
            this.world = owner.getWorld();
            this.forward = Motion.horizontalFacing(owner.getLocation());
            this.yaw = owner.getLocation().getYaw();
            this.delay = Math.max(6, params().ticks("cart_delay"));
            this.length = (track.size() - 1) * TRACK_STEP;
            this.railSegments = segments(track);
        }

        /**
         * The rail lengths to lay: {from, to, ground height}, at most {@link #RAIL_SEGMENT} long, split where the ground
         * steps up or down so every rail lies on its own ground.
         */
        private static List<double[]> segments(List<Location> track) {
            List<double[]> segments = new ArrayList<>();
            double start = 0;
            double height = track.getFirst().getY();
            for (int i = 1; i < track.size(); i++) {
                double at = i * TRACK_STEP;
                double y = track.get(i).getY();
                if (Math.abs(y - height) > 1.0E-3) {
                    double step = at - TRACK_STEP / 2;
                    segments.add(new double[] {start, step, height});
                    start = step;
                    height = y;
                } else if (at - start >= RAIL_SEGMENT - 1.0E-6) {
                    segments.add(new double[] {start, at, height});
                    start = at;
                }
            }
            double end = (track.size() - 1) * TRACK_STEP;
            if (end - start > 1.0E-3) {
                segments.add(new double[] {start, end, height});
            }
            return segments;
        }

        void start() {
            Location first = track.getFirst();
            Fx.sound(first, Sound.BLOCK_METAL_PLACE, 1.0f, 0.8f);
            Fx.sound(owner.getLocation(), Sound.BLOCK_LANTERN_PLACE, 0.8f, 1.0f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⛏ 광산 수레</b></" + COLOR_TAG + "> <gray>레일 "
                    + AbilityInfo.formatNumber(Math.round(length * 10) / 10.0) + "블록</gray>"));
            long maxRide = (long) Math.ceil(length / CART_START_SPEED) + 4;
            Loop.repeat(ctx(), owner, 1, 1, delay + maxRide + 1, this, this::end);
        }

        @Override
        public boolean test(long tick) {
            if (tick < delay) {
                layRails(tick);
                return true;
            }
            if (rideTick < 0) {
                rideTick = tick;
                push();
            }
            return ride(tick - rideTick);
        }

        // ---------------------------------------------------------- laying the track

        /** The rails click down one length after another; the buffer stop goes up at the end. */
        private void layRails(long tick) {
            int segments = railSegments.size();
            long perSegment = Math.max(1, (delay - 2) / Math.max(1, segments));
            if (tick % perSegment == 0) {
                int index = (int) (tick / perSegment);
                if (index < segments) {
                    placeRail(index, segments);
                }
            }
            if (tick == 0) {
                spawnCart();
            }
            if (tick == delay - 2) {
                placeBuffer();
            }
            if (tick % 2 == 0) {
                // The lane lights up in lantern orange along both sides of the rails.
                Vector sideways = new Vector(-forward.getZ(), 0, forward.getX()).multiply(0.75);
                Location a = track.getFirst().clone().add(0, 0.08, 0);
                Location b = point(Math.min(length, length * (tick + 2) / (double) delay)).add(0, 0.08, 0);
                Vfx.line(ctx(), a.clone().add(sideways), b.clone().add(sideways), 1.2, Particle.DUST_COLOR_TRANSITION,
                        LANTERN, true);
                Vfx.line(ctx(), a.clone().subtract(sideways), b.clone().subtract(sideways), 1.2,
                        Particle.DUST_COLOR_TRANSITION, LANTERN, true);
            }
        }

        private void placeRail(int index, int segments) {
            double[] segment = railSegments.get(index);
            double from = segment[0];
            double to = segment[1];
            if (to - from < 0.25 || !reserve(1)) {
                return;
            }
            Location start = point(from);
            start.setY(segment[2]);
            float span = (float) (to - from);
            // The rail block lies on the ground; its length runs along the track (turned in the transformation only).
            BlockDisplay rail = Displays.block(ctx(), owner, start, RAIL.createBlockData(),
                    railPose(span, 0.05f), delay + (long) Math.ceil(length / CART_START_SPEED) + RAIL_LINGER_TICKS + 20);
            rail.setBrightness(null);
            rails.add(rail);
            displays.add(rail);
            Displays.animateNextTick(ctx(), owner, rail, railPose(span, 1f), 3);
            Location mid = point((from + to) / 2).add(0, 0.1, 0);
            Vfx.cloud(ctx(), mid, Particle.DUST, 4, 0.4, 0, RAIL_DUST, false);
            Fx.sound(mid, Sound.BLOCK_METAL_PLACE, 0.8f, 1.2f + 0.04f * index);
            if (index == segments - 1) {
                Fx.sound(mid, Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.0f);
            }
        }

        /** A rail block lying flat from the display's spot along the track, {@code span} long, grown by {@code grow}. */
        private Transformation railPose(float span, float grow) {
            float length = Math.max(0.01f, span * grow);
            double angle = Math.toRadians(yaw);
            float half = length / 2;
            return Displays.transform((float) (-Math.sin(angle) * half), 0.5f, (float) (Math.cos(angle) * half), 1.0f,
                    1.0f, length, yaw, 0, 0);
        }

        private void placeBuffer() {
            if (!reserve(1)) {
                return;
            }
            Location end = point(length).add(forward.clone().multiply(0.6));
            buffer = Displays.block(ctx(), owner, end, BUFFER.createBlockData(),
                    Displays.transform(0, 0.35f, 0, 1.0f, 0.05f, 0.35f, yaw, 0, 0),
                    (long) Math.ceil(length / CART_START_SPEED) + RAIL_LINGER_TICKS + 20);
            displays.add(buffer);
            Displays.animateNextTick(ctx(), owner, buffer, Displays.transform(0, 0.35f, 0, 1.0f, 0.7f, 0.35f, yaw, 0,
                    0), 3);
            Fx.sound(end, Sound.BLOCK_WOOD_PLACE, 1.0f, 0.8f);
        }

        // ---------------------------------------------------------- the cart

        /** The cart: a cauldron-like tub, a heap of ore in it and a lantern hung at its front. */
        private void spawnCart() {
            if (!reserve(4)) {
                return;
            }
            Location at = track.getFirst();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            cart.add(Displays.block(ctx(), owner, at, CART.createBlockData(), cartPose(CartPart.TUB, 0.05f, 0),
                    delay + (long) Math.ceil(length / CART_START_SPEED) + 30));
            cart.add(Displays.block(ctx(), owner, at, ORES[random.nextInt(ORES.length)].createBlockData(),
                    cartPose(CartPart.ORE_A, 0.05f, 0), delay + (long) Math.ceil(length / CART_START_SPEED) + 30));
            cart.add(Displays.block(ctx(), owner, at, Material.GOLD_ORE.createBlockData(),
                    cartPose(CartPart.ORE_B, 0.05f, 0), delay + (long) Math.ceil(length / CART_START_SPEED) + 30));
            cart.add(Displays.block(ctx(), owner, at, LAMP.createBlockData(), cartPose(CartPart.LAMP, 0.05f, 0),
                    delay + (long) Math.ceil(length / CART_START_SPEED) + 30));
            for (int i = 0; i < cart.size(); i++) {
                BlockDisplay part = cart.get(i);
                part.setTeleportDuration(1);
                part.setViewRange(2.0f);
                displays.add(part);
                Displays.animateNextTick(ctx(), owner, part, cartPose(CartPart.ALL[i], 1f, 0), 4);
            }
            Fx.sound(at, Sound.BLOCK_METAL_PLACE, 1.0f, 0.6f);
            Fx.sound(at, Sound.BLOCK_STONE_PLACE, 1.0f, 0.8f);
        }

        /** The miner shoves the cart off. */
        private void push() {
            Location at = track.getFirst();
            Vfx.cloud(ctx(), at.clone().add(0, 0.3, 0), Particle.DUST_PLUME, 8, 0.3, 0.03, null, true);
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f);
            Fx.sound(at, Sound.BLOCK_PISTON_EXTEND, 0.8f, 0.7f);
            Fx.sound(at, Sound.ENTITY_MINECART_RIDING, 1.2f, 0.8f);
        }

        /** One tick down the line: the cart speeds up, scoops whoever is on the rails and carries the riders. */
        private boolean ride(long tick) {
            speed = Math.min(CART_MAX_SPEED, speed + CART_ACCEL);
            travelled = Math.min(length, travelled + speed);
            Location at = point(travelled);
            moveCart(at, tick);
            scoop(at);
            carry(at);
            clatter(at, tick);
            if (travelled >= length - 1.0E-6) {
                crash(at);
                return false;
            }
            return true;
        }

        private void moveCart(Location at, long tick) {
            float rock = (float) (Math.sin(tick * 1.3) * 3);
            for (int i = 0; i < cart.size() && i < CartPart.ALL.length; i++) {
                BlockDisplay part = cart.get(i);
                if (part.isValid()) {
                    Displays.moveTo(part, at);
                    if (tick % 2 == 0) {
                        Displays.animate(part, cartPose(CartPart.ALL[i], 1f, rock), 2);
                    }
                }
            }
        }

        /** Enemies on the rails right in front of the cart tumble into it. */
        private void scoop(Location at) {
            Location front = at.clone().add(forward.clone().multiply(0.4)).add(0, 0.6, 0);
            for (LivingEntity enemy : ctx().enemiesNear(owner, front, SCOOP_RADIUS)) {
                if (scooped.contains(enemy.getUniqueId())
                        || Math.abs(enemy.getLocation().getY() - at.getY()) > SCOOP_HEIGHT) {
                    continue;
                }
                scooped.add(enemy.getUniqueId());
                Combat.hit(ctx(), owner, enemy, params().num("cart_damage"));
                Location body = Fx.body(enemy);
                if (!isHidden(owner, enemy)) {
                    world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 12), 0.3, 0.3, 0.3, 0.1,
                            ORES[ThreadLocalRandom.current().nextInt(ORES.length)].createBlockData());
                    Vfx.cloud(ctx(), body, Particle.CRIT, 8, 0.3, 0.2, null, false);
                }
                Fx.sound(body, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.8f, 1.3f + Fx.jitter(0.1f));
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f);
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    riders.add(new Rider(enemy, riders.size()));
                }
            }
        }

        /** Riders are held on top of the ore heap; one stopped by something (a wall, immunity) falls out. */
        private void carry(Location at) {
            riders.removeIf(rider -> {
                LivingEntity entity = rider.entity;
                if (!Combat.isTargetable(ctx(), owner, entity) || !entity.getWorld().equals(world)) {
                    return true;
                }
                Location seat = at.clone().add(forward.clone().multiply(0.3 - 0.45 * rider.seat))
                        .add(0, RIDE_HEIGHT, 0);
                // Stuck behind something (a wall) while the cart rolls on: it falls out. A player gets one tick of
                // cart travel more for every tick of its ping, since the server sees it move that much later.
                double leash = RIDE_LEASH + (entity instanceof Player riding ? speed * lagTicks(riding) : 0);
                if (seat.distance(entity.getLocation()) > leash) {
                    return true;
                }
                double factor = Motion.knockbackFactor(entity);
                if (factor <= 0.01) {
                    return true;
                }
                // Steered by where the rider will be (dead reckoning), so a lagging player is not yanked past its seat.
                Vector offset = new Vector(seat.getX() - rider.x, seat.getY() - entity.getLocation().getY(),
                        seat.getZ() - rider.z);
                Vector velocity = forward.clone().multiply(speed).add(offset.multiply(RIDE_PULL));
                velocity.setY(Math.clamp(velocity.getY(), -0.2, 0.45));
                Vector sent = Motion.clamp(velocity.multiply(factor), MAX_SPEED);
                entity.setVelocity(sent);
                rider.x += sent.getX();
                rider.z += sent.getZ();
                entity.setFallDistance(0);
                return false;
            });
        }

        /** The wheels clatter faster and faster; sparks fly from the rails. */
        private void clatter(Location at, long tick) {
            Location wheels = at.clone().add(0, 0.1, 0);
            if (tick % Math.max(1, 4 - (int) (speed * 2.5)) == 0) {
                Fx.sound(wheels, Sound.BLOCK_METAL_STEP, 0.9f, 0.8f + (float) speed * 0.6f);
            }
            if (tick % 2 == 0) {
                Vfx.cloud(ctx(), wheels, Particle.ELECTRIC_SPARK, 2, 0.3, 0.05, null, false);
                Vfx.trail(wheels, wheels.clone().subtract(forward.clone().multiply(1.6)), PALETTE.body(), 6, false);
            }
            Vfx.dot(at.clone().add(0, 0.9, 0).add(forward.clone().multiply(0.45)), Particle.DUST_COLOR_TRANSITION,
                    LANTERN, false);
        }

        /** The buffer stop: the cart tips over, the ore flies and the riders are thrown out ahead. */
        private void crash(Location at) {
            crashed = true;
            double damage = params().num("crash_damage");
            int thrown = 0;
            for (Rider rider : riders) {
                LivingEntity entity = rider.entity;
                Combat.hit(ctx(), owner, entity, damage);
                if (!Combat.isTargetable(ctx(), owner, entity)) {
                    continue;
                }
                thrown++;
                Motion.push(entity, forward.clone().multiply(FLING_FORWARD).setY(FLING_UP), MAX_SPEED);
                if (!isHidden(owner, entity)) {
                    Vfx.burst(ctx(), Fx.body(entity), PALETTE, 0.6, Particle.CRIT, true);
                }
            }
            riders.clear();
            Location front = at.clone().add(forward.clone().multiply(0.8)).add(0, 0.6, 0);
            Vfx.dot(front, Particle.FLASH, PALETTE.core(), true);
            world.spawnParticle(Particle.BLOCK, front, Vfx.n(ctx(), 30), 0.6, 0.4, 0.6, 0.2,
                    Material.IRON_ORE.createBlockData(), true);
            world.spawnParticle(Particle.BLOCK, front, Vfx.n(ctx(), 20), 0.6, 0.4, 0.6, 0.2,
                    Material.GOLD_ORE.createBlockData(), true);
            world.spawnParticle(Particle.DUST_PLUME, at, Vfx.n(ctx(), 12), 0.6, 0.1, 0.6, 0.05, null, true);
            Vfx.shockwave(ctx(), owner, at.clone().add(0, 0.1, 0), 0.5, 2.8, 5, Particle.DUST, STONE_DUST, true);
            Fx.sound(at, Sound.BLOCK_ANVIL_LAND, 1.2f, 0.6f);
            Fx.sound(at, Sound.BLOCK_STONE_BREAK, 1.4f, 0.6f);
            Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.4f);
            Fx.sound(at, Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.0f, 0.7f);
            tipOver(at);
            BlockDisplay stop = buffer;
            if (stop != null && stop.isValid()) {
                Displays.animate(stop, Displays.transform(0, 0.3f, 0, 1.1f, 0.6f, 0.35f, yaw, 18, 0), 3);
            }
            if (thrown > 0) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⛏ 광산 수레</b></" + COLOR_TAG + "> <white>" + thrown
                        + "명 하차!</white>"));
            } else if (!scooped.isEmpty()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⛏ 광산 수레</" + COLOR_TAG + "> <white>" + scooped.size()
                        + "명 적중</white>"));
            }
        }

        /** The cart pitches forward onto its nose and its load spills out ahead. */
        private void tipOver(Location at) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < cart.size() && i < CartPart.ALL.length; i++) {
                BlockDisplay part = cart.get(i);
                if (!part.isValid()) {
                    continue;
                }
                if (CartPart.ALL[i].isLoad()) {
                    part.setTeleportDuration(6);
                    Location spill = at.clone().add(forward.clone().multiply(1.6 + random.nextDouble()))
                            .add(random.nextDouble(-0.6, 0.6), 0, random.nextDouble(-0.6, 0.6));
                    Displays.moveTo(part, spill);
                }
                Displays.animate(part, cartPose(CartPart.ALL[i], 1f, 70), 4);
                ctx().runLater(owner, 10, () -> {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 6);
                    }
                });
            }
        }

        /** The loop is over: everything still standing is cleared away, the rails last. */
        void end() {
            if (!crashed) {
                for (BlockDisplay part : cart) {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 4);
                    }
                }
            }
            for (int i = 0; i < rails.size(); i++) {
                BlockDisplay rail = rails.get(i);
                ctx().runLater(owner, RAIL_LINGER_TICKS + i, () -> {
                    if (rail.isValid()) {
                        Displays.shrinkAway(ctx(), owner, rail, 4);
                    }
                });
            }
            BlockDisplay stop = buffer;
            if (stop != null) {
                ctx().runLater(owner, RAIL_LINGER_TICKS + rails.size(), () -> {
                    if (stop.isValid()) {
                        Displays.shrinkAway(ctx(), owner, stop, 4);
                    }
                });
            }
        }

        /** The point {@code distance} blocks down the track (between its sampled points). */
        private Location point(double distance) {
            double index = Math.clamp(distance / TRACK_STEP, 0, track.size() - 1);
            int i = (int) Math.floor(index);
            if (i >= track.size() - 1) {
                return track.getLast().clone();
            }
            double t = index - i;
            Location a = track.get(i);
            Location b = track.get(i + 1);
            return new Location(world, a.getX() + (b.getX() - a.getX()) * t, a.getY() + (b.getY() - a.getY()) * t,
                    a.getZ() + (b.getZ() - a.getZ()) * t);
        }

        /** A part of the cart turned to the track (its yaw in the transformation), pitched forward by {@code tip}. */
        private Transformation cartPose(CartPart part, float grow, float tip) {
            Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
            Quaternionf rotation = new Quaternionf(turn).rotateX((float) Math.toRadians(tip));
            Vector3f size = new Vector3f(part.sx, part.sy, part.sz).mul(Math.max(0.01f, grow));
            // Parts pivot round the front bottom edge of the tub, so the cart tips onto its nose.
            Vector3f pivot = new Vector3f(0, 0, 0.5f).mul(grow);
            Vector3f arm = new Vector3f(part.x, part.y, part.z).mul(grow).sub(pivot);
            Vector3f center = rotation.transform(arm).add(turn.transform(new Vector3f(pivot)));
            Vector3f translation = center.sub(rotation.transform(new Vector3f(size).mul(0.5f)));
            return new Transformation(translation, rotation, size, new Quaternionf());
        }
    }

    /** One block of the mine cart, in the cart's frame (+z forward, y up from the rails). */
    private record CartPart(float x, float y, float z, float sx, float sy, float sz) {
        static final CartPart TUB = new CartPart(0, 0.5f, 0, 1.0f, 1.0f, 1.0f);
        static final CartPart ORE_A = new CartPart(-0.12f, 0.95f, -0.05f, 0.55f, 0.45f, 0.55f);
        static final CartPart ORE_B = new CartPart(0.18f, 1.05f, 0.12f, 0.4f, 0.4f, 0.4f);
        static final CartPart LAMP = new CartPart(0, 0.9f, 0.62f, 0.4f, 0.55f, 0.4f);
        static final CartPart[] ALL = {TUB, ORE_A, ORE_B, LAMP};

        boolean isLoad() {
            return this == ORE_A || this == ORE_B;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Ticks the server sees a player's movement late: one round trip, capped. */
    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / MILLIS_PER_TICK));
    }

    /** Reserves room for {@code count} more displays under {@link #MAX_DISPLAYS}; false when there is none. */
    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    /** True for a player the miner cannot see (stealth): no visual may give it away. */
    private static boolean isHidden(Player owner, LivingEntity entity) {
        return entity instanceof Player hidden && !owner.canSee(hidden);
    }

    private static boolean isOccupied(Block block) {
        BoundingBox cell = BoundingBox.of(block);
        return !block.getWorld().getNearbyEntities(cell, entity -> entity instanceof LivingEntity).isEmpty();
    }

    private static double horizontal(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** A cube of edge {@code size} centered on the block display's origin, turned by {@code rotation}. */
    private static Transformation cube(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    private static Quaternionf randomRotation(ThreadLocalRandom random) {
        return new Quaternionf().rotateXYZ(random.nextFloat() * 6.28f, random.nextFloat() * 6.28f,
                random.nextFloat() * 6.28f);
    }
}
