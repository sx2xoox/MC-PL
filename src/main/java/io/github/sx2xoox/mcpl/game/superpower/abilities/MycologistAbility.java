package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 버섯술사 — a fungal trapper. Melee hits dust enemies with spores that make them dizzy (and soften them up for the
 * mines), the active sows a cluster of mushroom mines that burst into a spore cloud when an enemy walks onto them,
 * and the ultimate raises a giant mushroom whose spore cloud wears enemies down while it heals the owner's side.
 *
 * <p>Look (spore cream, mushroom red, spore violet): a spored enemy grows a tiny toadstool on its head and trails
 * spores. Mine seeds are little mushroom caps lobbed in arcs; where they land a mycelium patch spreads and a chunky
 * voxel mushroom sprouts, pops to full size when armed and breathes softly; a triggered mine swells, wobbles and
 * bursts in a red-violet spore puff. The giant mushroom erupts out of a spreading mycelium carpet (stem, gills, a
 * spotted cap and a crown) with small mushrooms at its foot, exhales spore rings every pulse, and swells and bursts
 * at the end before sinking back into the ground.
 */
public final class MycologistAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("mycologist", "버섯술사", Material.RED_MUSHROOM)
            .color(TextColor.color(0xE5483F))
            .difficulty(2)
            .passive("포자",
                    "근접 공격한 적에게 포자를 묻혀 {spore_nausea}초간 멀미를 겁니다(같은 적은 {spore_cooldown}초마다). "
                            + "포자가 묻은 적은 버섯 지뢰 피해를 {spore_bonus}% 더 받습니다.")
            .active("버섯 지뢰",
                    "조준한 바닥({mine_range}블록 이내)에 버섯 지뢰 {mine_count}개를 가로로 심습니다. {mine_arm}초 뒤 다 자란 버섯을 "
                            + "적이 밟으면 포자가 터져 반경 {mine_radius}블록의 적에게 {mine_damage} 피해를 주고 "
                            + "{mine_confuse}초간 멀미와 구속을 겁니다. 버섯은 {mine_duration}초간 남고, 새로 심으면 이전 버섯은 "
                            + "시듭니다.",
                    10)
            .ultimate("거대 버섯",
                    "조준한 지점({ult_range}블록 이내)에 거대 버섯이 솟아 반경 {erupt_radius}블록의 적에게 {erupt_damage} 피해를 "
                            + "주고 띄웁니다. {ult_duration}초간 반경 {ult_radius}블록의 포자 구름 속 적은 {ult_interval}초마다 "
                            + "{ult_damage} 피해(머물수록 {ult_ramp}씩 증가, 최대 {ult_damage_max})를 입고 {ult_slow}% 느려지며, "
                            + "나와 아군은 {ult_heal} 회복합니다. 마지막에 버섯이 터지며 구름 속 적에게 {burst_damage} 피해를 줍니다.")
            .param("spore_nausea", 3)
            .param("spore_cooldown", 4)
            .param("spore_bonus", 50)
            .param("mine_range", 14)
            .param("mine_count", 3)
            .param("mine_spread", 2.2)
            .param("mine_arm", 0.75)
            .param("mine_duration", 20)
            .param("mine_trigger", 0.9)
            .param("mine_radius", 2.5)
            .param("mine_damage", 4)
            .param("mine_confuse", 2)
            .param("ult_range", 20)
            .param("erupt_radius", 3)
            .param("erupt_damage", 3)
            .param("erupt_lift", 1.5)
            .param("ult_duration", 5)
            .param("ult_radius", 6)
            .param("ult_interval", 1)
            .param("ult_damage", 1)
            .param("ult_ramp", 0.5)
            .param("ult_damage_max", 2.5)
            .param("ult_slow", 30)
            .param("ult_heal", 1.5)
            .param("burst_damage", 3)
            .build();

    private static final String COLOR_TAG = "#E5483F";
    /** Spore cream, mushroom red, spore violet. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF1E6, 0xE5483F, 0x7B3FA8);
    private static final Color VIOLET = Color.fromRGB(0x9C5BD6);
    private static final Color CREAM = Color.fromRGB(0xF4E3C8);
    private static final Particle.DustOptions RED_DUST = PALETTE.dust(1.0f);
    private static final Particle.DustOptions RED_SPECK = PALETTE.dust(0.6f);
    private static final Particle.DustOptions CREAM_DUST = new Particle.DustOptions(CREAM, 0.8f);
    private static final Particle.DustOptions VIOLET_DUST = new Particle.DustOptions(VIOLET, 1.0f);
    private static final Particle.DustTransition SPORE_GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition SPORE_FADE = PALETTE.fade(1.2f);
    private static final Particle.DustTransition SPORE_MOTE = new Particle.DustTransition(PALETTE.body(), VIOLET, 0.8f);

    // Block data needs a running server: only the materials are constants, the data is created when used.
    private static final Material CAP = Material.RED_MUSHROOM_BLOCK;
    private static final Material STEM = Material.MUSHROOM_STEM;
    private static final Material MYCELIUM = Material.MYCELIUM;
    private static final Material[] SMALL_MUSHROOMS = {Material.RED_MUSHROOM, Material.BROWN_MUSHROOM};

    /** Display entities this ability keeps alive at most at once (optional extras fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 1.8;

    // passive
    /** Spored enemies wearing a toadstool at once (display budget); further ones only trail spores. */
    private static final int MAX_SPORE_CAPS = 4;
    private static final long SPORE_FOLLOW_PERIOD = 2;
    private static final long FOOTPRINT_PERIOD = 8;
    private static final double FOOTPRINT_MIN_MOVE = 0.25;
    private static final long CLEANUP_PERIOD = 200;
    /** Nausea needs a moment to start swirling; it is given this much longer than the spore itself. */
    private static final int NAUSEA_LEAD = 20;

    // active: mushroom mines
    /** Steps the planting ray takes down from an aimed point to find the floor. */
    private static final int GROUND_SEARCH = 6;
    private static final long SEED_FLIGHT = 7;
    private static final long SEED_STAGGER = 2;
    private static final float SEED_SIZE = 0.26f;
    private static final long SPROUT_TICKS = 5;
    private static final long FUSE_TICKS = 6;
    private static final long WILT_TICKS = 8;
    /** One enemy is hurt by mines at most once in this many ticks, so a cluster never stacks in one step. */
    private static final long MINE_HIT_GAP = 10;
    private static final long TRIGGER_PERIOD = 2;
    private static final long BREATH_PERIOD = 20;
    private static final float PATCH_SIZE = 1.15f;
    private static final float STEM_WIDTH = 0.2f;
    private static final float STEM_HEIGHT = 0.36f;
    private static final float CAP_WIDTH = 0.56f;
    private static final float CAP_HEIGHT = 0.26f;
    private static final double POP_PUSH = 0.25;
    private static final double POP_LIFT = 0.32;
    private static final long DIZZY_FX_PERIOD = 4;

    // ultimate: giant mushroom
    private static final long TELEGRAPH_TICKS = 12;
    private static final long ERUPT_TICKS = 6;
    private static final long SWELL_TICKS = 4;
    private static final long SINK_TICKS = 10;
    private static final float GIANT_STEM_WIDTH = 1.05f;
    private static final float GIANT_STEM_HEIGHT = 3.4f;
    private static final float GIANT_CAP_WIDTH = 4.6f;
    private static final float GIANT_CAP_HEIGHT = 1.1f;
    private static final float GIANT_CROWN_WIDTH = 3.0f;
    private static final float GIANT_CROWN_HEIGHT = 0.7f;
    private static final float GILLS_WIDTH = 4.0f;
    private static final int FOOT_MUSHROOMS = 4;
    private static final long SLOW_CHECK_PERIOD = 2;
    /** Each slow modifier lasts this long and is renewed halfway while the enemy stays in the cloud. */
    private static final long SLOW_LEASE = 20;
    private static final int ULT_NAUSEA_TICKS = 70;
    private static final double ERUPT_PUSH = 0.35;

    // passive state
    private final Map<UUID, Spore> spores = new HashMap<>();
    private @Nullable Location lastFeet;
    // active state
    private final List<Mine> mines = new ArrayList<>();
    private final Map<UUID, Long> lastMineHit = new HashMap<>();
    /** Display entities spawned by this instance (pruned when counted). */
    private final List<Display> live = new ArrayList<>();

    /** A spored enemy: until when the spore lasts, when it can be spored again, and its toadstool. */
    private static final class Spore {
        long until;
        long next;
        /** True while the follow-up visuals (toadstool, drifting spores) run for this enemy. */
        boolean following;
        @Nullable BlockDisplay cap;
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        spores.clear();
        mines.clear();
        lastMineHit.clear();
        live.clear();
    }

    // ------------------------------------------------------------------ passive: spores

    @Override
    public void onTick(long tick) {
        if (tick % FOOTPRINT_PERIOD == 0) {
            footprints();
        }
        if (tick % CLEANUP_PERIOD == 0) {
            long now = ctx().currentTick();
            spores.values().removeIf(spore -> spore.next <= now && spore.until <= now);
            lastMineHit.values().removeIf(at -> now - at > MINE_HIT_GAP);
            mines.removeIf(Mine::isDone);
        }
    }

    /** The owner leaves faint mycelium footprints while walking. */
    private void footprints() {
        Player owner = player();
        Location feet = owner.getLocation();
        Location last = lastFeet;
        lastFeet = feet;
        if (last == null || !last.getWorld().equals(feet.getWorld()) || !Motion.isGrounded(owner)) {
            return;
        }
        double dx = feet.getX() - last.getX();
        double dz = feet.getZ() - last.getZ();
        if (dx * dx + dz * dz < FOOTPRINT_MIN_MOVE * FOOTPRINT_MIN_MOVE) {
            return;
        }
        Location at = feet.clone().add(0, 0.05, 0);
        owner.getWorld().spawnParticle(Particle.MYCELIUM, at, Vfx.n(ctx(), 3), 0.2, 0.02, 0.2, 0);
        if (ThreadLocalRandom.current().nextInt(3) == 0) {
            Vfx.dot(at.add(Fx.jitter(0.2f), 0.1, Fx.jitter(0.2f)), Particle.DUST, RED_SPECK, false);
        }
    }

    /** The spore itself is an on-hit side effect: it only sticks to hits that landed. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() || !player().equals(event.getDamager())
                || event.getCause() != DamageCause.ENTITY_ATTACK || !ctx().isEnemy(player(), victim)) {
            return;
        }
        long now = ctx().currentTick();
        Spore spore = spores.computeIfAbsent(victim.getUniqueId(), id -> new Spore());
        if (now < spore.next) {
            return;
        }
        long duration = Math.max(1, params().ticks("spore_nausea"));
        spore.next = now + params().ticks("spore_cooldown");
        spore.until = now + duration;
        victim.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, clampTicks(duration + NAUSEA_LEAD), 0, false,
                true, true));
        sporeBurst(victim);
        if (!spore.following) {
            growToadstool(victim, spore);
        }
    }

    /** True while {@code enemy} carries the owner's spores. */
    private boolean isSpored(LivingEntity enemy) {
        Spore spore = spores.get(enemy.getUniqueId());
        return spore != null && ctx().currentTick() < spore.until;
    }

    /** A puff of spores bursting off the struck enemy's head. */
    private void sporeBurst(LivingEntity victim) {
        Location head = victim.getEyeLocation().add(0, 0.2, 0);
        World world = victim.getWorld();
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, head, Vfx.n(ctx(), 10), 0.3, 0.25, 0.3, 0, SPORE_GLOW);
        world.spawnParticle(Particle.DUST, head, Vfx.n(ctx(), 6), 0.35, 0.3, 0.35, 0, VIOLET_DUST);
        world.spawnParticle(Particle.CRIMSON_SPORE, head, Vfx.n(ctx(), 8), 0.4, 0.3, 0.4, 0);
        world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, head, Vfx.n(ctx(), 3), 0.3, 0.2, 0.3, 0);
        Fx.sound(head, Sound.BLOCK_FUNGUS_BREAK, 0.9f, 1.3f + Fx.jitter(0.15f));
        Fx.sound(head, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, 0.6f, 1.6f + Fx.jitter(0.1f));
    }

    /**
     * A tiny toadstool sprouts on the spored enemy's head and wobbles there, shedding spores, until the spore wears
     * off (a refreshed spore keeps it longer). Only a few enemies wear one at once; the others just shed spores.
     */
    private void growToadstool(LivingEntity victim, Spore spore) {
        Player owner = player();
        spore.following = true;
        int shown = 0;
        for (Spore other : spores.values()) {
            if (other.cap != null && other.cap.isValid()) {
                shown++;
            }
        }
        BlockDisplay cap = null;
        if (shown < MAX_SPORE_CAPS && roomFor(1)) {
            cap = keep(Displays.block(ctx(), owner, headTop(victim), data(CAP), toadstoolPose(0.05f, 0), 0));
            cap.setTeleportDuration((int) SPORE_FOLLOW_PERIOD);
            Displays.animateNextTick(ctx(), owner, cap, toadstoolPose(1f, 0), 4);
        }
        spore.cap = cap;
        BlockDisplay worn = cap;
        Loop.repeat(ctx(), owner, SPORE_FOLLOW_PERIOD, SPORE_FOLLOW_PERIOD, Long.MAX_VALUE, run -> {
            if (!Combat.isTargetable(ctx(), owner, victim) || ctx().currentTick() >= spore.until) {
                return false;
            }
            if (worn != null && worn.isValid()) {
                Displays.moveTo(worn, headTop(victim));
                if (run % 4 == 0) {
                    Displays.animate(worn, toadstoolPose(1f, (float) Math.sin(run * 0.9) * 14f), 8);
                }
            }
            if (run % 3 == 0) {
                Location head = victim.getEyeLocation().add(Fx.jitter(0.3f), 0.15, Fx.jitter(0.3f));
                Vfx.dot(head, Particle.DUST_COLOR_TRANSITION, SPORE_MOTE, false);
            }
            if (run % 5 == 2) {
                Vfx.dot(victim.getEyeLocation().add(0, 0.5, 0), Particle.SPORE_BLOSSOM_AIR, null, false);
            }
            return true;
        }, () -> {
            if (worn != null && worn.isValid()) {
                Displays.shrinkAway(ctx(), owner, worn, 4);
            }
            spore.cap = null;
            spore.following = false;
        });
    }

    private static Location headTop(LivingEntity victim) {
        return flat(victim.getLocation().add(0, victim.getHeight() + 0.02, 0));
    }

    /** A little spotted cap sitting on top of the head, {@code grow} 0..1, tilted {@code wobble} degrees. */
    private static Transformation toadstoolPose(float grow, float wobble) {
        float size = Math.max(0.03f, 0.34f * grow);
        return Displays.transform(0, size * 0.22f, 0, size, size * 0.45f, size, 0, wobble, wobble * 0.5f);
    }

    // ------------------------------------------------------------------ active: mushroom mines

    @Override
    public ActivationResult onActive() {
        Location center = aimGround(params().num("mine_range"));
        if (center == null) {
            return ActivationResult.fail("버섯을 심을 땅이 없습니다");
        }
        List<Location> spots = mineSpots(center);
        if (spots.isEmpty()) {
            return ActivationResult.fail("버섯을 심을 땅이 없습니다");
        }
        // The old patch withers away: only one cluster of mines grows at a time.
        for (Mine mine : mines) {
            mine.wither();
        }
        mines.clear();

        Player owner = player();
        Location hand = hand(owner);
        hand.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, hand, Vfx.n(ctx(), 8), 0.15, 0.15, 0.15, 0,
                SPORE_GLOW);
        hand.getWorld().spawnParticle(Particle.MYCELIUM, hand, Vfx.n(ctx(), 6), 0.2, 0.2, 0.2, 0);
        Fx.sound(hand, Sound.ENTITY_SNIFFER_DROP_SEED, 1.0f, 1.1f + Fx.jitter(0.1f));
        Fx.sound(hand, Sound.BLOCK_FUNGUS_PLACE, 0.8f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(hand, Sound.ITEM_BONE_MEAL_USE, 0.6f, 0.7f + Fx.jitter(0.1f));
        for (int i = 0; i < spots.size(); i++) {
            sow(hand, spots.get(i), SEED_FLIGHT + i * SEED_STAGGER);
        }
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">버섯 지뢰 <white>" + spots.size() + "개 심음</white></"
                + COLOR_TAG + ">"));
        return ActivationResult.success();
    }

    /**
     * The mines' spots: {@code mine_count} points in a row across the owner's view, {@code mine_spread} apart and
     * centered on the aimed floor point (so the middle one lands right under an aimed enemy), each on solid ground
     * with nothing solid between it and the center.
     */
    private List<Location> mineSpots(Location center) {
        int count = Math.max(1, params().integer("mine_count"));
        double spread = Math.max(0, params().num("mine_spread"));
        Vector across = Motion.horizontalFacing(player().getLocation()).rotateAroundY(Math.PI / 2);
        Location middle = center.clone().add(0, 0.5, 0);
        List<Location> spots = new ArrayList<>();
        // The middle first, then outwards: the seeds fly out in that order.
        List<Double> slots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            slots.add(i - (count - 1) / 2.0);
        }
        slots.sort((a, b) -> Double.compare(Math.abs(a), Math.abs(b)));
        for (double slot : slots) {
            Vector offset = across.clone().multiply(slot * spread);
            Location probe = center.clone().add(offset).add(0, 1.2, 0);
            Double ground = groundBelow(probe, 3);
            if (ground == null) {
                continue;
            }
            Location spot = flat(probe);
            spot.setY(ground);
            if (!Spots.clearPath(middle, spot.clone().add(0, 0.5, 0)) || !ctx().isInsideArena(spot)) {
                continue;
            }
            spots.add(spot);
        }
        return spots;
    }

    /** A spotted seed cap lobbed from the hand to {@code spot}, where a mine sprouts. */
    private void sow(Location hand, Location spot, long flight) {
        Player owner = player();
        Location from = hand.clone();
        Location to = spot.clone().add(0, 0.15, 0);
        double arc = 0.8 + 0.1 * from.distance(to);
        BlockDisplay seed = roomFor(1)
                ? keep(Displays.block(ctx(), owner, flat(from), data(CAP), cubePose(SEED_SIZE, 0, 0), flight + 4))
                : null;
        if (seed != null) {
            seed.setTeleportDuration(1);
        }
        Location[] previous = {from};
        Loop.repeat(ctx(), owner, 1, 1, flight, run -> {
            double t = (double) (run + 1) / flight;
            Location at = from.clone().add(to.toVector().subtract(from.toVector()).multiply(t))
                    .add(0, 4 * arc * t * (1 - t), 0);
            if (seed != null && seed.isValid()) {
                Displays.moveTo(seed, at);
                Displays.animate(seed, cubePose(SEED_SIZE, run * 40f, run * 25f), 1);
            }
            Vfx.line(ctx(), previous[0], at, 3, Particle.DUST, run % 2 == 0 ? RED_SPECK : CREAM_DUST, false);
            if (run % 2 == 1) {
                Vfx.dot(at, Particle.CRIMSON_SPORE, null, false);
            }
            previous[0] = at;
            return true;
        }, () -> {
            if (seed != null && seed.isValid()) {
                seed.remove();
            }
            plant(spot);
        });
    }

    private void plant(Location spot) {
        Mine mine = new Mine(spot);
        mines.add(mine);
        mine.sprout();
        Loop.repeat(ctx(), player(), 1, 1, mine.maxRuns(), mine, mine::wither);
    }

    private enum MineState { SPROUTING, ARMED, FUSING, DONE }

    /**
     * One mushroom mine: it sprouts, arms after {@code mine_arm}, waits up to {@code mine_duration} for an enemy to
     * step on it, then swells for a moment and bursts into spores. Unused mines wither away harmlessly.
     */
    private final class Mine implements LongPredicate {

        private final Location spot;
        private final World world;
        private final float yaw;
        private final long armTicks;
        private final long lifeTicks;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private @Nullable BlockDisplay patch;
        private @Nullable BlockDisplay stem;
        private @Nullable BlockDisplay cap;
        private MineState state = MineState.SPROUTING;
        private long fuse;

        Mine(Location spot) {
            this.spot = spot;
            this.world = spot.getWorld();
            this.yaw = ThreadLocalRandom.current().nextFloat() * 90f;
            this.armTicks = Math.max(1, params().ticks("mine_arm"));
            this.lifeTicks = Math.max(1, params().ticks("mine_duration"));
        }

        long maxRuns() {
            return armTicks + lifeTicks + FUSE_TICKS + 2;
        }

        boolean isDone() {
            return state == MineState.DONE;
        }

        /** A mycelium patch spreads and a small mushroom pushes out of it. */
        void sprout() {
            Player owner = player();
            long lifetime = maxRuns() + WILT_TICKS + 4;
            if (roomFor(3)) {
                patch = keep(Displays.block(ctx(), owner, spot, data(MYCELIUM), plate(0.1f, yaw, 0.012f), lifetime));
                stem = keep(Displays.block(ctx(), owner, spot, data(STEM), stemPose(0.1f), lifetime));
                cap = keep(Displays.block(ctx(), owner, spot, data(CAP), capPose(0.1f, 0.1f, 1f, 0), lifetime));
                parts.add(patch);
                parts.add(stem);
                parts.add(cap);
                Displays.animateNextTick(ctx(), owner, patch, plate(PATCH_SIZE, yaw, 0.012f), (int) SPROUT_TICKS);
                Displays.animateNextTick(ctx(), owner, stem, stemPose(0.75f), (int) SPROUT_TICKS);
                Displays.animateNextTick(ctx(), owner, cap, capPose(0.75f, 0.75f, 1f, 0), (int) SPROUT_TICKS);
            }
            Location at = spot.clone().add(0, 0.1, 0);
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 8), 0.3, 0.05, 0.3, 0.05, data(MYCELIUM));
            world.spawnParticle(Particle.MYCELIUM, at, Vfx.n(ctx(), 8), 0.4, 0.05, 0.4, 0);
            Vfx.ring(ctx(), at, 0.5, 10, yaw, Particle.DUST, RED_SPECK, false);
            Fx.sound(at, Sound.BLOCK_FUNGUS_PLACE, 0.9f, 1.0f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_NYLIUM_PLACE, 0.7f, 0.9f + Fx.jitter(0.1f));
        }

        @Override
        public boolean test(long tick) {
            switch (state) {
                case SPROUTING -> {
                    if (tick >= armTicks) {
                        arm();
                    }
                    return true;
                }
                case ARMED -> {
                    if (tick >= armTicks + lifeTicks) {
                        wither();
                        return false;
                    }
                    idle(tick);
                    if (tick % TRIGGER_PERIOD == 0 && stepper() != null) {
                        light();
                    }
                    return true;
                }
                case FUSING -> {
                    fuse++;
                    swell();
                    if (fuse >= FUSE_TICKS) {
                        burst();
                        return false;
                    }
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        /** Grown: the mushroom pops to full size with a ring showing its trigger area. */
        private void arm() {
            state = MineState.ARMED;
            Player owner = player();
            if (stem != null && cap != null) {
                Displays.animate(stem, stemPose(1.1f), 3);
                Displays.animate(cap, capPose(1.18f, 1.1f, 1f, 0), 3);
                BlockDisplay grown = cap;
                BlockDisplay grownStem = stem;
                ctx().runLater(owner, 3, () -> {
                    Displays.animate(grownStem, stemPose(1f), 3);
                    Displays.animate(grown, capPose(1f, 1f, 1f, 0), 3);
                });
            }
            Location at = spot.clone().add(0, 0.08, 0);
            double trigger = Math.max(0.3, params().num("mine_trigger"));
            Vfx.ring(ctx(), at, trigger + 0.3, 14, yaw, Particle.DUST_COLOR_TRANSITION, SPORE_GLOW, false);
            Vfx.cloud(ctx(), at.clone().add(0, 0.45, 0), Particle.CRIMSON_SPORE, 5, 0.2, 0, null, false);
            Fx.sound(at, Sound.BLOCK_SHROOMLIGHT_PLACE, 0.7f, 1.5f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 1.8f + Fx.jitter(0.1f));
        }

        /** An armed mushroom breathes and sheds a spore now and then. */
        private void idle(long tick) {
            long since = tick - armTicks;
            if (since > 6 && since % BREATH_PERIOD == 0 && cap != null && cap.isValid()) {
                boolean in = since / BREATH_PERIOD % 2 == 0;
                Displays.animate(cap, capPose(in ? 1.06f : 1f, 1f, in ? 0.94f : 1f, 0), (int) BREATH_PERIOD);
            }
            if (since % 10 == 3) {
                Location mote = spot.clone().add(Fx.jitter(0.25f), 0.5 + ThreadLocalRandom.current().nextDouble(0.3),
                        Fx.jitter(0.25f));
                Vfx.dot(mote, Particle.DUST_COLOR_TRANSITION, SPORE_MOTE, false);
            }
            if (since % 30 == 15) {
                Vfx.ring(ctx(), spot.clone().add(0, 0.06, 0), Math.max(0.3, params().num("mine_trigger")) + 0.3, 8,
                        since * 0.1, Particle.DUST, RED_SPECK, false);
            }
        }

        /** The first enemy standing on the mine (the owner never sees enemies hidden by stealth trigger it). */
        private @Nullable LivingEntity stepper() {
            double trigger = Math.max(0.1, params().num("mine_trigger"));
            for (LivingEntity enemy : ctx().enemiesNear(player(), spot.clone().add(0, 0.4, 0), trigger)) {
                if (seen(player(), enemy)) {
                    return enemy;
                }
            }
            return null;
        }

        /** Stepped on: the fuse is lit, the cap swells and wobbles. */
        private void light() {
            state = MineState.FUSING;
            fuse = 0;
            Location at = spot.clone().add(0, 0.4, 0);
            Fx.sound(at, Sound.ENTITY_PUFFER_FISH_BLOW_UP, 1.0f, 1.3f + Fx.jitter(0.08f));
            Fx.sound(at, Sound.BLOCK_FUNGUS_STEP, 1.0f, 0.6f + Fx.jitter(0.05f));
            Vfx.ring(ctx(), spot.clone().add(0, 0.08, 0), Math.max(0.5, params().num("mine_radius")), 18, yaw,
                    Particle.DUST_COLOR_TRANSITION, SPORE_FADE, true);
        }

        private void swell() {
            float grow = 1f + 0.55f * fuse / FUSE_TICKS;
            float wobble = (fuse % 2 == 0 ? 1 : -1) * 9f;
            if (cap != null && cap.isValid()) {
                Displays.animate(cap, capPose(grow, 1f + 0.2f * fuse / FUSE_TICKS, 1f, wobble), 1);
            }
            Location at = spot.clone().add(0, 0.5, 0);
            Vfx.ring(ctx(), at, 0.35 + 0.08 * fuse, 8, fuse * 0.6, Particle.DUST, RED_DUST, false);
            if (fuse % 2 == 0) {
                Vfx.dot(at.clone().add(0, 0.25, 0), Particle.CRIMSON_SPORE, null, false);
            }
        }

        /** The mushroom bursts into a spore cloud: damage, dizziness and a small hop for every enemy around it. */
        private void burst() {
            state = MineState.DONE;
            Player owner = player();
            Location center = spot.clone().add(0, 0.5, 0);
            for (BlockDisplay part : parts) {
                if (part == cap && part.isValid()) {
                    part.remove();
                } else if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, part == patch ? 10 : 4);
                }
            }
            double radius = Math.max(0.5, params().num("mine_radius"));
            sporePuff(center, radius);
            Fx.sound(center, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, 1.2f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.BLOCK_SHROOMLIGHT_BREAK, 1.0f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.ENTITY_SLIME_SQUISH, 0.9f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(center, Sound.BLOCK_FUNGUS_BREAK, 1.0f, 0.7f + Fx.jitter(0.1f));

            double damage = params().num("mine_damage");
            double bonus = Math.max(0, params().num("spore_bonus")) / 100.0;
            long confuse = Math.max(1, params().ticks("mine_confuse"));
            long now = ctx().currentTick();
            int hits = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                Long last = lastMineHit.get(enemy.getUniqueId());
                if (last != null && now - last < MINE_HIT_GAP) {
                    continue;
                }
                lastMineHit.put(enemy.getUniqueId(), now);
                boolean spored = isSpored(enemy);
                Combat.hit(ctx(), owner, enemy, damage * (spored ? 1 + bonus : 1));
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                hits++;
                dizzy(enemy, confuse);
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(spot.toVector()));
                Motion.push(enemy, away.multiply(POP_PUSH).setY(POP_LIFT), MAX_SPEED);
                Location body = Fx.body(enemy);
                Vfx.trail(center, body, spored ? VIOLET : PALETTE.body(), 6, false);
                if (spored) {
                    Vfx.dot(body, Particle.FLASH, VIOLET, false);
                    Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.8f, 0.6f + Fx.jitter(0.05f));
                }
            }
            if (hits > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">포자 폭발 <white>" + hits + "명 적중!</white></"
                        + COLOR_TAG + ">"));
            }
        }

        /** The mine withers without bursting: the cap droops and everything sinks into the ground. */
        void wither() {
            if (state == MineState.DONE || state == MineState.FUSING) {
                return;
            }
            state = MineState.DONE;
            Player owner = player();
            if (cap != null && cap.isValid()) {
                Displays.animate(cap, capPose(0.8f, 0.6f, 1f, 28f), (int) (WILT_TICKS / 2));
            }
            for (BlockDisplay part : parts) {
                BlockDisplay piece = part;
                ctx().runLater(owner, WILT_TICKS / 2, () -> {
                    if (piece.isValid()) {
                        Displays.shrinkAway(ctx(), owner, piece, (int) WILT_TICKS);
                    }
                });
            }
            Location at = spot.clone().add(0, 0.3, 0);
            world.spawnParticle(Particle.MYCELIUM, at, Vfx.n(ctx(), 6), 0.3, 0.15, 0.3, 0);
            world.spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 4), 0.25, 0.15, 0.25, 0,
                    new Particle.DustOptions(PALETTE.edge(), 0.7f));
            Fx.sound(at, Sound.BLOCK_FUNGUS_BREAK, 0.5f, 0.5f + Fx.jitter(0.05f));
        }

        private Transformation stemPose(float grow) {
            float height = Math.max(0.02f, STEM_HEIGHT * grow);
            float width = Math.max(0.02f, STEM_WIDTH * Math.min(1f, grow));
            return Displays.transform(0, height / 2, 0, width, height, width, yaw, 0, 0);
        }

        /** The cap on top of the stem: {@code grow} scales its width, {@code tall} its height. */
        private Transformation capPose(float grow, float tall, float stemGrow, float wobble) {
            float width = Math.max(0.02f, CAP_WIDTH * grow);
            float height = Math.max(0.02f, CAP_HEIGHT * tall);
            float top = STEM_HEIGHT * Math.min(1.1f, stemGrow * Math.min(1f, grow));
            return Displays.transform(0, top + height / 2, 0, width, height, width, yaw + wobble, wobble * 0.6f,
                    -wobble * 0.4f);
        }
    }

    /** Leaves {@code enemy} dizzy: nausea and slowness, spores swirling around its head meanwhile. */
    private void dizzy(LivingEntity enemy, long ticks) {
        enemy.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, clampTicks(ticks + NAUSEA_LEAD), 0, false,
                true, true));
        enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, clampTicks(ticks), 1, false, true, true));
        Player owner = player();
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        Loop.repeat(ctx(), owner, DIZZY_FX_PERIOD, DIZZY_FX_PERIOD, Math.max(1, ticks / DIZZY_FX_PERIOD), run -> {
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return false;
            }
            // Spores circling the head like little stars.
            Location head = enemy.getEyeLocation().add(0, 0.35, 0);
            for (int i = 0; i < 3; i++) {
                double angle = phase + run * 0.7 + i * Math.PI * 2 / 3;
                Location at = head.clone().add(Math.cos(angle) * 0.4, 0, Math.sin(angle) * 0.4);
                Vfx.dot(at, Particle.DUST, i == 0 ? VIOLET_DUST : RED_SPECK, false);
            }
            return true;
        });
    }

    /** A spore puff: a red-violet cloud, an expanding spore ring and crumbs of mushroom flying out. */
    private void sporePuff(Location center, double radius) {
        World world = center.getWorld();
        Vfx.dot(center, Particle.FLASH, PALETTE.body(), false);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, center, Vfx.n(ctx(), 26), radius * 0.35, 0.4,
                radius * 0.35, 0, SPORE_FADE);
        world.spawnParticle(Particle.DUST, center, Vfx.n(ctx(), 14), radius * 0.4, 0.45, radius * 0.4, 0, CREAM_DUST);
        world.spawnParticle(Particle.CRIMSON_SPORE, center, Vfx.n(ctx(), 20), radius * 0.4, 0.4, radius * 0.4, 0);
        world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, center, Vfx.n(ctx(), 8), radius * 0.35, 0.4, radius * 0.35, 0);
        world.spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 12), 0.3, 0.2, 0.3, 0.15, data(CAP));
        Vfx.shockwave(ctx(), player(), center.clone().add(0, -0.4, 0), 0.4, radius, 5,
                Particle.DUST_COLOR_TRANSITION, SPORE_GLOW, false);
        Loop.repeat(ctx(), player(), 3, 2, 6, run -> {
            Location mote = Vfx.scatter(center, radius * 0.8).add(0, ThreadLocalRandom.current().nextDouble(0.8), 0);
            Vfx.dot(mote, Particle.DUST_COLOR_TRANSITION, SPORE_MOTE, false);
            Vfx.dot(mote.add(0, 0.3, 0), Particle.SPORE_BLOSSOM_AIR, null, false);
            return true;
        });
    }

    // ------------------------------------------------------------------ ultimate: giant mushroom

    @Override
    public ActivationResult onUltimate() {
        Location base = aimGround(params().num("ult_range"));
        if (base == null) {
            // Aimed at the sky: the mushroom bursts out right under the owner instead.
            Location feet = flat(player().getLocation());
            Double ground = groundBelow(feet.clone().add(0, 0.2, 0), GROUND_SEARCH);
            if (ground == null) {
                return ActivationResult.fail("버섯이 자랄 땅이 없습니다");
            }
            feet.setY(ground);
            base = feet;
        }
        GiantMushroom mushroom = new GiantMushroom(base);
        mushroom.telegraph();
        Loop.repeat(ctx(), player(), 1, 1, mushroom.maxRuns(), mushroom, mushroom::release);
        return ActivationResult.success();
    }

    /** An enemy slowed by the cloud: its slow is a short lease renewed while it stays inside. */
    private static final class Slowed {
        final LivingEntity entity;
        NamespacedKey key;
        long renewAt;

        Slowed(LivingEntity entity, NamespacedKey key, long renewAt) {
            this.entity = entity;
            this.key = key;
            this.renewAt = renewAt;
        }
    }

    /**
     * The giant mushroom: a short telegraph of spreading mycelium, the eruption (damage and launch at its foot), a
     * spore cloud that pulses every {@code ult_interval} (damage growing the longer an enemy stays, healing for the
     * owner's side, a leased slow and nausea) and a final burst before it sinks into the ground.
     */
    private final class GiantMushroom implements LongPredicate {

        private final Location base;
        private final World world;
        private final double radius;
        private final long life;
        private final long interval;
        private final float yaw;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private final Map<UUID, Slowed> slowed = new LinkedHashMap<>();
        private final Map<UUID, Integer> dose = new HashMap<>();
        private @Nullable BlockDisplay stem;
        private @Nullable BlockDisplay gills;
        private @Nullable BlockDisplay cap;
        private @Nullable BlockDisplay crown;
        private boolean finished;
        private int healed;
        private int stung;

        GiantMushroom(Location base) {
            this.base = base;
            this.world = base.getWorld();
            this.radius = Math.max(1, params().num("ult_radius"));
            this.life = Math.max(1, params().ticks("ult_duration"));
            this.interval = Math.max(1, params().ticks("ult_interval"));
            this.yaw = ThreadLocalRandom.current().nextFloat() * 90f;
        }

        long maxRuns() {
            return TELEGRAPH_TICKS + life + 1;
        }

        /** The ground cracks and mycelium spreads: everyone sees where the mushroom will burst out. */
        void telegraph() {
            Location floor = base.clone().add(0, 0.1, 0);
            double erupt = Math.max(0.5, params().num("erupt_radius"));
            Vfx.shockwave(ctx(), player(), floor, 0.4, radius, TELEGRAPH_TICKS, Particle.DUST_COLOR_TRANSITION,
                    SPORE_FADE, true);
            Vfx.ring(ctx(), floor, erupt, (int) Math.max(14, erupt * 7), yaw, Particle.DUST_COLOR_TRANSITION,
                    SPORE_GLOW, true);
            Fx.sound(base, Sound.BLOCK_MUDDY_MANGROVE_ROOTS_BREAK, 1.3f, 0.5f);
            Fx.sound(base, Sound.ENTITY_SNIFFER_DIGGING, 1.2f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(base, Sound.BLOCK_NYLIUM_BREAK, 1.0f, 0.5f);
        }

        @Override
        public boolean test(long tick) {
            if (tick < TELEGRAPH_TICKS) {
                rumble(tick);
                return true;
            }
            long t = tick - TELEGRAPH_TICKS;
            if (t == 0) {
                erupt();
            }
            if (t >= life) {
                burst();
                return false;
            }
            if (t % SLOW_CHECK_PERIOD == 0) {
                updateSlows(t);
            }
            if (t % interval == interval - 1) {
                pulse();
            }
            ambience(t);
            return true;
        }

        /** Dirt and mycelium jump around the eruption spot, faster as it nears. */
        private void rumble(long tick) {
            double erupt = Math.max(0.5, params().num("erupt_radius"));
            Location floor = base.clone().add(0, 0.1, 0);
            double closing = erupt * (1 - (double) tick / TELEGRAPH_TICKS) + 0.3;
            Vfx.ring(ctx(), floor, closing, (int) Math.max(10, closing * 7), tick * 0.35, Particle.DUST, RED_DUST,
                    true);
            world.spawnParticle(Particle.BLOCK, floor, Vfx.n(ctx(), 4), erupt * 0.4, 0.05, erupt * 0.4, 0.05,
                    data(MYCELIUM), true);
            world.spawnParticle(Particle.MYCELIUM, floor, Vfx.n(ctx(), 6), radius * 0.45, 0.05, radius * 0.45, 0,
                    null, true);
            if (tick % 4 == 0) {
                Fx.sound(base, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.9f, 0.6f + 0.05f * tick);
            }
        }

        /** The mushroom bursts out of the ground: everything grows at once, enemies at its foot are thrown up. */
        private void erupt() {
            Player owner = player();
            long lifetime = life + SWELL_TICKS + SINK_TICKS + 6;
            stem = part(data(STEM), stemPose(0.02f), lifetime);
            gills = part(gillsData(), gillsPose(0.02f, 1f), lifetime);
            cap = part(data(CAP), capPose(0.02f, 1f, 0), lifetime);
            crown = part(data(CAP), crownPose(0.02f, 1f, 0), lifetime);
            Displays.animateNextTick(ctx(), owner, stem, stemPose(1f), (int) ERUPT_TICKS);
            Displays.animateNextTick(ctx(), owner, gills, gillsPose(1f, 1f), (int) ERUPT_TICKS);
            Displays.animateNextTick(ctx(), owner, cap, capPose(1f, 1f, 0), (int) ERUPT_TICKS);
            Displays.animateNextTick(ctx(), owner, crown, crownPose(1f, 1f, 0), (int) ERUPT_TICKS);
            for (BlockDisplay giant : List.of(stem, gills, cap, crown)) {
                giant.setViewRange(2.0f);
            }
            // A mycelium carpet and small mushrooms around the foot: optional, within the display budget.
            for (int i = 0; i < 2 && roomFor(1); i++) {
                float plateYaw = yaw + i * 45f;
                BlockDisplay carpet = part(data(MYCELIUM), plate(0.4f, plateYaw, 0.014f + i * 0.005f), lifetime);
                Displays.animateNextTick(ctx(), owner, carpet,
                        plate((float) (radius * 1.75), plateYaw, 0.014f + i * 0.005f), (int) ERUPT_TICKS + 4);
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < FOOT_MUSHROOMS && roomFor(1); i++) {
                double angle = yaw + i * Math.PI * 2 / FOOT_MUSHROOMS + random.nextDouble(0.6);
                double distance = 1.4 + random.nextDouble(radius * 0.45);
                Location at = base.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
                Double ground = groundBelow(at.clone().add(0, 1.0, 0), 3);
                if (ground == null) {
                    continue;
                }
                at.setY(ground);
                float size = 1.3f + random.nextFloat() * 0.8f;
                BlockDisplay small = part(data(SMALL_MUSHROOMS[i % SMALL_MUSHROOMS.length]),
                        Displays.transform(0, 0.02f, 0, 0.05f, 0.05f, 0.05f, random.nextFloat() * 90f, 0, 0),
                        lifetime);
                ctx().runLater(owner, 2 + random.nextInt(6), () -> Displays.animate(small,
                        Displays.transform(0, size * 0.5f - 0.02f, 0, size, size, size, random.nextFloat() * 90f, 0, 0),
                        5));
            }

            Location foot = base.clone().add(0, 0.2, 0);
            Location top = base.clone().add(0, GIANT_STEM_HEIGHT, 0);
            world.spawnParticle(Particle.BLOCK, foot, Vfx.n(ctx(), 30), 1.2, 0.2, 1.2, 0.2, data(MYCELIUM), true);
            world.spawnParticle(Particle.DUST_PILLAR, foot, Vfx.n(ctx(), 14), 1.0, 0, 1.0, 0.15, data(MYCELIUM), true);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, top, Vfx.n(ctx(), 40), 1.8, 0.6, 1.8, 0, SPORE_GLOW,
                    true);
            world.spawnParticle(Particle.CRIMSON_SPORE, top, Vfx.n(ctx(), 30), 2.0, 0.8, 2.0, 0, null, true);
            Vfx.helix(ctx(), foot, 0.9, GIANT_STEM_HEIGHT, 1.5, 2, 16, yaw, Particle.DUST_COLOR_TRANSITION, SPORE_FADE,
                    true);
            Vfx.shockwave(ctx(), owner, foot, 0.5, Math.max(0.5, params().num("erupt_radius")) + 0.5, 5,
                    Particle.DUST, RED_DUST, true);
            Fx.sound(base, Sound.BLOCK_FUNGUS_PLACE, 1.6f, 0.5f);
            Fx.sound(base, Sound.ENTITY_PUFFER_FISH_BLOW_UP, 1.4f, 0.55f);
            Fx.sound(base, Sound.BLOCK_SHROOMLIGHT_PLACE, 1.3f, 0.6f);
            Fx.sound(base, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f + Fx.jitter(0.05f));

            double damage = params().num("erupt_damage");
            double lift = Motion.launchSpeed(Math.max(0, params().num("erupt_lift")));
            double erupt = Math.max(0.5, params().num("erupt_radius"));
            int launched = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, base.clone().add(0, 0.6, 0), erupt)) {
                Combat.hit(ctx(), owner, enemy, damage);
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                launched++;
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(base.toVector()));
                Motion.push(enemy, away.multiply(ERUPT_PUSH).setY(lift), MAX_SPEED);
                Location body = Fx.body(enemy);
                Vfx.dot(body, Particle.FLASH, PALETTE.body(), false);
                world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 8), 0.3, 0.4, 0.3, 0.1, data(CAP));
            }
            if (launched > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">거대 버섯 <white>" + launched + "명 띄움!</white></"
                        + COLOR_TAG + ">"));
            }
        }

        private BlockDisplay part(BlockData data, Transformation start, long lifetime) {
            BlockDisplay display = keep(Displays.block(ctx(), player(), base, data, start, lifetime));
            parts.add(display);
            return display;
        }

        private boolean inside(LivingEntity entity) {
            Location at = entity.getLocation();
            double dx = at.getX() - base.getX();
            double dz = at.getZ() - base.getZ();
            double dy = at.getY() - base.getY();
            return dx * dx + dz * dz <= radius * radius && dy > -2 && dy < 5;
        }

        /** Enemies inside are slowed by a short lease renewed while they stay; leavers are let go at once. */
        private void updateSlows(long tick) {
            Player owner = player();
            double slow = Math.clamp(params().num("ult_slow") / 100.0, 0.0, 1.0);
            Iterator<Slowed> iterator = slowed.values().iterator();
            while (iterator.hasNext()) {
                Slowed entry = iterator.next();
                if (!Combat.isTargetable(ctx(), owner, entry.entity) || !inside(entry.entity)) {
                    ctx().removeModifier(entry.entity, entry.key);
                    iterator.remove();
                } else if (tick >= entry.renewAt) {
                    ctx().removeModifier(entry.entity, entry.key);
                    entry.key = ctx().addModifier(owner, entry.entity, Attribute.MOVEMENT_SPEED, -slow,
                            AttributeModifier.Operation.MULTIPLY_SCALAR_1, SLOW_LEASE);
                    entry.renewAt = tick + SLOW_LEASE / 2;
                }
            }
            if (slow <= 0) {
                return;
            }
            for (LivingEntity enemy : ctx().enemiesNear(owner, base.clone().add(0, 1, 0), radius + 1)) {
                if (slowed.containsKey(enemy.getUniqueId()) || !inside(enemy)) {
                    continue;
                }
                NamespacedKey key = ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, SLOW_LEASE);
                slowed.put(enemy.getUniqueId(), new Slowed(enemy, key, tick + SLOW_LEASE / 2));
                Location feet = enemy.getLocation().add(0, 0.1, 0);
                Vfx.ring(ctx(), feet, 0.6, 10, 0, Particle.DUST, VIOLET_DUST, false);
                world.spawnParticle(Particle.MYCELIUM, feet, Vfx.n(ctx(), 6), 0.3, 0.05, 0.3, 0);
                Fx.sound(feet, Sound.BLOCK_FUNGUS_STEP, 0.8f, 0.7f + Fx.jitter(0.1f));
            }
        }

        /**
         * The mushroom exhales: a spore ring rolls out, enemies inside are stung harder the longer they stayed,
         * the owner and allies inside are healed.
         */
        private void pulse() {
            Player owner = player();
            if (cap != null && cap.isValid() && crown != null && crown.isValid()) {
                Displays.animate(cap, capPose(1.07f, 0.92f, 0), 3);
                Displays.animate(crown, crownPose(1.07f, 0.92f, 0), 3);
                BlockDisplay puffed = cap;
                BlockDisplay puffedCrown = crown;
                ctx().runLater(owner, 3, () -> {
                    Displays.animate(puffed, capPose(1f, 1f, 0), 6);
                    Displays.animate(puffedCrown, crownPose(1f, 1f, 0), 6);
                });
            }
            Location floor = base.clone().add(0, 0.25, 0);
            Vfx.shockwave(ctx(), owner, floor, 1.0, radius, 6, Particle.DUST_COLOR_TRANSITION, SPORE_FADE, true);
            Location gillsAt = base.clone().add(0, GIANT_STEM_HEIGHT - 0.1, 0);
            world.spawnParticle(Particle.FALLING_SPORE_BLOSSOM, gillsAt, Vfx.n(ctx(), 10), 1.6, 0.05, 1.6, 0, null,
                    true);
            Fx.sound(base, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, 1.0f, 0.55f + Fx.jitter(0.05f));
            Fx.sound(base, Sound.BLOCK_SPORE_BLOSSOM_PLACE, 1.0f, 0.6f + Fx.jitter(0.05f));

            double first = params().num("ult_damage");
            double ramp = Math.max(0, params().num("ult_ramp"));
            double max = Math.max(first, params().num("ult_damage_max"));
            List<UUID> stayed = new ArrayList<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, base.clone().add(0, 1, 0), radius + 1)) {
                if (!inside(enemy)) {
                    continue;
                }
                int stacks = dose.getOrDefault(enemy.getUniqueId(), 0) + 1;
                dose.put(enemy.getUniqueId(), stacks);
                stayed.add(enemy.getUniqueId());
                Combat.hit(ctx(), owner, enemy, Math.min(max, first + ramp * (stacks - 1)));
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                stung++;
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, ULT_NAUSEA_TICKS, 0, false, true,
                        true));
                Location body = Fx.body(enemy);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 4 + 2 * Math.min(4, stacks)),
                        0.3, 0.45, 0.3, 0, SPORE_FADE, true);
                world.spawnParticle(Particle.CRIMSON_SPORE, body, Vfx.n(ctx(), 4), 0.3, 0.4, 0.3, 0, null, true);
                Fx.sound(body, Sound.ENTITY_PUFFER_FISH_STING, 0.6f, 1.0f + 0.1f * Math.min(5, stacks));
            }
            // A dose only builds up while the enemy stays: stepping out resets it.
            dose.keySet().retainAll(stayed);

            double heal = params().num("ult_heal");
            List<LivingEntity> friends = new ArrayList<>(ctx().alliesNear(owner, base.clone().add(0, 1, 0),
                    radius + 1));
            friends.addFirst(owner);
            for (LivingEntity friend : friends) {
                if (!inside(friend) || heal <= 0) {
                    continue;
                }
                if (friend.getHealth() < Combat.maxHealth(friend)) {
                    healed++;
                }
                ctx().heal(friend, heal);
                Location chest = Fx.body(friend);
                Vfx.trail(gillsAt, chest, CREAM, 10, true);
                world.spawnParticle(Particle.HEART, chest.clone().add(0, 0.7, 0), 1, 0.2, 0.1, 0.2, 0, null, true);
                world.spawnParticle(Particle.DUST, chest, Vfx.n(ctx(), 4), 0.3, 0.4, 0.3, 0, CREAM_DUST, true);
            }
        }

        /** Spores drift low through the cloud, fall from the gills, and the rim glows. */
        private void ambience(long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Location mote = Vfx.scatter(base, radius).add(0, 0.2 + random.nextDouble(1.2), 0);
                Vfx.dot(mote, Particle.DUST_COLOR_TRANSITION, SPORE_MOTE, true);
            }
            if (tick % 2 == 0) {
                Location drift = Vfx.scatter(base, radius * 0.9).add(0, 0.3 + random.nextDouble(1.5), 0);
                Vfx.dot(drift, Particle.CRIMSON_SPORE, null, true);
                Location fall = Vfx.scatter(base, 1.8).add(0, GIANT_STEM_HEIGHT - 0.2, 0);
                Vfx.dot(fall, Particle.FALLING_SPORE_BLOSSOM, null, true);
            }
            if (tick % 4 == 0) {
                Location rim = base.clone().add(0, 0.15, 0);
                Vfx.ring(ctx(), rim, radius, (int) Math.min(40, Math.max(16, radius * 4)), tick * 0.02,
                        Particle.DUST_COLOR_TRANSITION, SPORE_GLOW, true);
                Vfx.ring(ctx(), rim, radius - 0.35, (int) Math.max(8, radius * 2), -tick * 0.03, Particle.DUST,
                        VIOLET_DUST, true);
            }
            if (tick % 10 == 5) {
                world.spawnParticle(Particle.MYCELIUM, base.clone().add(0, 0.1, 0), Vfx.n(ctx(), 6), radius * 0.4,
                        0.05, radius * 0.4, 0, null, true);
            }
            if (tick % 20 == 10) {
                Fx.sound(base, Sound.BLOCK_SPORE_BLOSSOM_STEP, 0.7f, 0.5f + Fx.jitter(0.05f));
            }
        }

        /** The end: the cap swells, bursts into a last spore blast over the whole cloud and the mushroom sinks. */
        private void burst() {
            finished = true;
            Player owner = player();
            releaseSlows();
            if (cap != null && cap.isValid() && crown != null && crown.isValid()) {
                Displays.animate(cap, capPose(1.25f, 1.2f, 0), (int) SWELL_TICKS);
                Displays.animate(crown, crownPose(1.25f, 1.2f, 0), (int) SWELL_TICKS);
            }
            Fx.sound(base, Sound.ENTITY_PUFFER_FISH_BLOW_UP, 1.5f, 0.45f);
            ctx().runLater(owner, SWELL_TICKS, () -> {
                Location top = base.clone().add(0, GIANT_STEM_HEIGHT + 0.6, 0);
                Vfx.dot(top, Particle.FLASH, PALETTE.body(), true);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, top, Vfx.n(ctx(), 60), 2.4, 0.8, 2.4, 0,
                        SPORE_FADE, true);
                world.spawnParticle(Particle.CRIMSON_SPORE, base.clone().add(0, 1, 0), Vfx.n(ctx(), 50), radius * 0.45,
                        0.6, radius * 0.45, 0, null, true);
                world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, base.clone().add(0, 1, 0), Vfx.n(ctx(), 20),
                        radius * 0.4, 0.6, radius * 0.4, 0, null, true);
                world.spawnParticle(Particle.BLOCK, top, Vfx.n(ctx(), 30), 1.8, 0.4, 1.8, 0.2, data(CAP), true);
                Vfx.shockwave(ctx(), owner, base.clone().add(0, 0.3, 0), 0.8, radius + 0.5, 7,
                        Particle.DUST_COLOR_TRANSITION, SPORE_GLOW, true);
                Fx.sound(base, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, 1.6f, 0.5f);
                Fx.sound(base, Sound.BLOCK_SHROOMLIGHT_BREAK, 1.4f, 0.5f);
                Fx.sound(base, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.2f + Fx.jitter(0.05f));
                double damage = params().num("burst_damage");
                for (LivingEntity enemy : ctx().enemiesNear(owner, base.clone().add(0, 1, 0), radius + 1)) {
                    if (!inside(enemy)) {
                        continue;
                    }
                    Combat.hit(ctx(), owner, enemy, damage);
                    if (Combat.isTargetable(ctx(), owner, enemy)) {
                        stung++;
                        enemy.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, ULT_NAUSEA_TICKS, 0, false,
                                true, true));
                        Vfx.trail(top, Fx.body(enemy), PALETTE.body(), 8, true);
                    }
                }
                sink();
                if (owner.isOnline() && (stung > 0 || healed > 0)) {
                    owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">거대 버섯 <white>포자 " + stung + "회 · 치유 "
                            + healed + "회</white></" + COLOR_TAG + ">"));
                }
            });
        }

        /** Everything sinks back into the ground. */
        private void sink() {
            Player owner = player();
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, (int) SINK_TICKS);
                }
            }
        }

        /** The loop ended (normally after the burst, or early on an error): nothing may stay behind. */
        void release() {
            releaseSlows();
            if (!finished) {
                finished = true;
                sink();
            }
        }

        private void releaseSlows() {
            for (Slowed entry : slowed.values()) {
                ctx().removeModifier(entry.entity, entry.key);
            }
            slowed.clear();
        }

        private Transformation stemPose(float grow) {
            float height = Math.max(0.02f, GIANT_STEM_HEIGHT * grow);
            float width = Math.max(0.02f, GIANT_STEM_WIDTH * Math.min(1f, grow * 1.2f));
            return Displays.transform(0, height / 2, 0, width, height, width, yaw, 0, 0);
        }

        private Transformation gillsPose(float grow, float swell) {
            float width = Math.max(0.02f, GILLS_WIDTH * grow * swell);
            return Displays.transform(0, GIANT_STEM_HEIGHT * grow + 0.04f, 0, width, 0.1f * Math.max(0.2f, grow),
                    width, yaw + 45f, 0, 0);
        }

        private Transformation capPose(float grow, float tall, float tilt) {
            float width = Math.max(0.02f, GIANT_CAP_WIDTH * grow);
            float height = Math.max(0.02f, GIANT_CAP_HEIGHT * tall * Math.min(1f, grow));
            float bottom = GIANT_STEM_HEIGHT * Math.min(1f, grow) + 0.08f;
            return Displays.transform(0, bottom + height / 2, 0, width, height, width, yaw + 45f, tilt, 0);
        }

        private Transformation crownPose(float grow, float tall, float tilt) {
            float width = Math.max(0.02f, GIANT_CROWN_WIDTH * grow);
            float height = Math.max(0.02f, GIANT_CROWN_HEIGHT * tall * Math.min(1f, grow));
            float bottom = (GIANT_STEM_HEIGHT + GIANT_CAP_HEIGHT) * Math.min(1f, grow) + 0.02f;
            return Displays.transform(0, bottom + height / 2 - 0.1f, 0, width, height, width, yaw, tilt, 0);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The floor point the owner aims at within {@code range}: under the aimed enemy, else where the view ray meets a
     * block (or ends), dropped onto the ground below. Null without ground.
     */
    private @Nullable Location aimGround(double range) {
        Player owner = player();
        World world = owner.getWorld();
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed != null && seen(owner, aimed)) {
            Location feet = flat(aimed.getLocation());
            Double ground = groundBelow(feet.clone().add(0, 0.2, 0), GROUND_SEARCH);
            if (ground != null) {
                feet.setY(ground);
                return feet;
            }
        }
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 1.0E-8) {
            return null;
        }
        direction.normalize();
        RayTraceResult hit = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        Location probe = hit == null
                ? eye.clone().add(direction.clone().multiply(range))
                : hit.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.3));
        Double ground = groundBelow(probe.clone().add(0, 0.2, 0), GROUND_SEARCH);
        if (ground == null) {
            return null;
        }
        Location at = flat(probe);
        at.setY(ground);
        return at;
    }

    /** The top of the first solid block (with room above it) at most {@code depth} blocks below {@code point}. */
    private static @Nullable Double groundBelow(Location point, int depth) {
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int start = Math.min(point.getBlockY(), world.getMaxHeight() - 2);
        int end = Math.max(world.getMinHeight(), start - depth);
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable() && block.getRelative(BlockFace.UP).isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /** Where thrown things leave the owner: just in front of the casting hand. */
    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.add(eye.getDirection().multiply(0.55)).subtract(0, 0.3, 0);
    }

    /** False for an enemy player hidden from the owner by stealth. */
    private static boolean seen(Player owner, LivingEntity enemy) {
        return !(enemy instanceof Player hidden) || owner.canSee(hidden);
    }

    /** True if {@code count} more displays fit in {@link #MAX_DISPLAYS}. */
    private boolean roomFor(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    private static BlockData data(Material material) {
        return material.createBlockData();
    }

    /** The pale, porous inside of a mushroom cap (every outer face turned off), for the gills. */
    private static BlockData gillsData() {
        BlockData data = CAP.createBlockData();
        if (data instanceof MultipleFacing faces) {
            for (BlockFace face : faces.getAllowedFaces()) {
                faces.setFace(face, false);
            }
        }
        return data;
    }

    /** A flat square plate of edge {@code size}, turned {@code yaw} degrees, lying {@code height} above the origin. */
    private static Transformation plate(float size, float yaw, float height) {
        return Displays.transform(0, height, 0, size, 0.02f, size, yaw, 0, 0);
    }

    /** A cube of edge {@code size} centered on the origin, turned {@code yaw}/{@code pitch} degrees. */
    private static Transformation cubePose(float size, float yaw, float pitch) {
        return Displays.transform(0, 0, 0, size, size, size, yaw, pitch, 0);
    }

    /** {@code at} without its rotation, so display entities placed there keep their own orientation. */
    private static Location flat(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }

    private static int clampTicks(long ticks) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, ticks));
    }
}
