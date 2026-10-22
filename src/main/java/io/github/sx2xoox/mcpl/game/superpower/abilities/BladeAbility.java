package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 검성 — a sword master. Sword hits now and then release a crescent of cutting wind, the active flashes through
 * everything in front in one draw (the cuts open a moment after the blade is sheathed), and the ultimate summons a
 * halo of spectral swords that dive one after another onto the enemies around.
 *
 * <p>Look: steel and cyan light. The wind crescent is a layered arc with a thin blade of light riding in front. The
 * dash leaves a long glowing cut line, speed streaks and an afterimage where the master stood; the struck enemies
 * burst open in crossing cuts with a sheathing click. 천검 raises six swords of light (glass blade, white core,
 * steel guard) that circle overhead, turn their points on a target, home in under a marker ring and stay planted
 * in the ground for a moment before they dissolve.
 */
public final class BladeAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("blade", "검성", Material.NETHERITE_SWORD)
            .color(TextColor.color(0x7FE3FF))
            .difficulty(2)
            .passive("검기",
                    "검으로 근접 공격하면 {wave_chance}% 확률로 참격파가 앞으로 {wave_range}블록 뻗어 나가 "
                            + "닿은 적마다 {wave_damage} 피해를 줍니다.")
            .active("질풍참",
                    "바라보는 방향으로 최대 {dash_distance}블록을 순식간에 베고 지나갑니다. 지나간 길 양옆 "
                            + "{dash_width}블록 안의 적은 잠시 뒤 베여 {dash_damage} 피해를 입습니다.",
                    8)
            .ultimate("천검",
                    "빛의 검 {ult_swords}자루를 머리 위에 띄운 뒤 반경 {ult_radius}블록의 적에게 {ult_interval}초마다 "
                            + "하나씩 내리꽂습니다. 검이 꽂힌 자리 반경 {ult_impact_radius}블록의 적은 {ult_damage} 피해를 "
                            + "입습니다. 떨어질 곳은 바닥에 표시됩니다.")
            .param("wave_chance", 25)
            .param("wave_damage", 3)
            .param("wave_range", 5)
            .param("dash_distance", 6)
            .param("dash_damage", 5)
            .param("dash_width", 1.2)
            .param("ult_radius", 12)
            .param("ult_swords", 6)
            .param("ult_damage", 2.5)
            .param("ult_interval", 0.3)
            .param("ult_impact_radius", 1.3)
            .build();

    private static final String COLOR_TAG = "#7FE3FF";
    private static final double TAU = Math.PI * 2;

    // Passive: sword wave
    private static final double WAVE_SPEED = 1.3;
    /** How far from the wave's path (to the hitbox) an enemy is still cut. */
    private static final double WAVE_REACH = 0.9;
    private static final double WAVE_ARC_RADIUS = 1.0;
    private static final double WAVE_ARC_DEGREES = 120;

    /** How far behind the start of a wave or a dash (along its direction) an enemy's body may be and still be cut. */
    private static final double BEHIND_TOLERANCE = 0.5;

    // Active: gale slash
    private static final double MIN_DASH = 1.0;
    private static final double DASH_STEP = 0.5;
    private static final double[] DASH_LIFTS = {0, 0.5, 1.0, -0.5, -1.0};
    private static final double GROUND_SNAP = 1.1;
    /** Ticks between the dash and the cuts opening on the struck enemies. */
    private static final long CUT_DELAY = 5;
    /** The fall after a dash that ended over a drop is harmless for at most this long. */
    private static final long LANDING_GRACE_TICKS = 100;

    // Ultimate: heavenly swords
    private static final long SUMMON_TICKS = 10;
    private static final long HOLD_TICKS = 4;
    private static final long AIM_TICKS = 4;
    /**
     * The dive stops following its target this many ticks before it lands (the marker stays put, dodge now): long
     * enough for a sprinting enemy (0.28 blocks a tick) to get its whole body out of the impact radius.
     */
    private static final long COMMIT_TICKS = 8;
    private static final double DIVE_SPEED = 1.6;
    /** At least {@link #COMMIT_TICKS}, so even a short dive leaves the full dodge window after the last aim. */
    private static final long MIN_DIVE_TICKS = 8;
    private static final long MAX_DIVE_TICKS = 16;
    private static final long PLANTED_TICKS = 14;
    private static final int FADE_TICKS = 5;
    private static final double HALO_RADIUS = 1.5;
    private static final double HALO_HEIGHT = 2.7;
    private static final double HALO_SPIN = 0.07;
    /** How deep a landed sword sinks into the ground. */
    private static final double PLANT_DEPTH = 0.45;

    // Visuals
    /** White core, cyan light, steel blue edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF2FDFF, 0x7FE3FF, 0x2F5D7C);
    private static final Particle.DustOptions CORE_DUST = new Particle.DustOptions(PALETTE.core(), 0.7f);
    private static final Material GLASS = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final Material CORE = Material.WHITE_CONCRETE;
    private static final Material STEEL = Material.IRON_BLOCK;

    /** Spectral sword parts, in the sword's frame (+z is the point): length 1.6 from pommel to point. */
    private static final Part BLADE = new Part(GLASS, 0.2f, 0.04f, 1.15f, 0.275f);
    private static final Part SPINE = new Part(CORE, 0.05f, 0.05f, 1.55f, 0.025f);
    private static final Part GUARD = new Part(STEEL, 0.52f, 0.09f, 0.09f, -0.32f);
    private static final Part[] SWORD_PARTS = {BLADE, SPINE, GUARD};
    /** Distance from the sword's center to its point. */
    private static final double POINT = 0.85;

    /** Most display entities one 검성 keeps alive at once; decorative cuts are left out beyond it. */
    private static final int MAX_DISPLAYS = 24;

    private record Part(Material material, float width, float thickness, float length, float offset) {
    }

    /** Generation of the running landing protection; a newer one retires the older loop. */
    private int fallGuardToken;
    /** Displays this ability spawned that may still be alive (pruned whenever the budget is checked). */
    private final List<BlockDisplay> displays = new ArrayList<>();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        // The displays themselves are tracked and removed with the ability.
        displays.clear();
    }

    // ------------------------------------------------------------------ passive: sword aura

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() || !isOwnMelee(event) || !holdsSword(player())) {
            return;
        }
        double chance = Math.clamp(params().num("wave_chance") / 100.0, 0.0, 1.0);
        if (chance <= 0 || ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        Location from = player().getLocation();
        Vector direction = Motion.horizontal(victim.getLocation().toVector().subtract(from.toVector()));
        if (direction.lengthSquared() < 1.0E-6) {
            direction = Motion.horizontalFacing(from);
        }
        Vector heading = direction;
        // The wave leaves on the next tick, after the sword hit itself has been applied.
        ctx().runLater(player(), 1, () -> launchWave(heading));
    }

    /** The owner's own direct melee swing (not the sweep's side hits, so one swing releases one wave at most). */
    private boolean isOwnMelee(EntityDamageByEntityEvent event) {
        return event.getCause() == DamageCause.ENTITY_ATTACK && player().equals(event.getDamager());
    }

    private static boolean holdsSword(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        Material type = hand.getType();
        Tag<Material> swords = Tag.ITEMS_SWORDS;
        return swords != null ? swords.isTagged(type) : type.name().endsWith("_SWORD");
    }

    /** A crescent of cutting wind that flies {@code wave_range} blocks and cuts every enemy it passes once. */
    private void launchWave(Vector direction) {
        Player owner = player();
        double range = Math.max(0, params().num("wave_range"));
        double damage = params().num("wave_damage");
        if (range <= 0) {
            return;
        }
        Location start = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        World world = start.getWorld();
        Set<UUID> struck = new HashSet<>();
        Location[] at = {start.clone()};
        double[] travelled = {0};
        long maxTicks = (long) Math.ceil(range / WAVE_SPEED);
        float roll = Fx.jitter(25);
        BlockDisplay[] blade = waveBlade(start, direction, roll, maxTicks + 4);

        Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 1.45f + Fx.jitter(0.1f));
        Fx.sound(start, Sound.ENTITY_BREEZE_WIND_BURST, 0.35f, 1.7f + Fx.jitter(0.1f));
        world.spawnParticle(Particle.SWEEP_ATTACK, start.clone().add(direction.clone().multiply(0.8)), 1, 0, 0, 0, 0);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚔ 검기</" + COLOR_TAG + "> <gray>참격파</gray>"));

        Loop.repeat(ctx(), owner, 0, 1, maxTicks, tick -> {
            double step = Math.min(WAVE_SPEED, range - travelled[0]);
            Location from = at[0];
            RayTraceResult wall = world.rayTraceBlocks(from, direction, step, FluidCollisionMode.NEVER, true);
            double advance = wall == null ? step : Math.max(0, wall.getHitPosition().distance(from.toVector()) - 0.1);
            Location to = from.clone().add(direction.clone().multiply(advance));
            for (double d = 0; d <= advance + 1.0E-6; d += 0.5) {
                Location sample = from.clone().add(direction.clone().multiply(Math.min(d, advance)));
                for (LivingEntity enemy : ctx().enemiesNear(owner, sample, WAVE_REACH)) {
                    if (ahead(start, direction, enemy) && struck.add(enemy.getUniqueId())) {
                        Combat.hit(ctx(), owner, enemy, damage);
                        waveHit(enemy);
                    }
                }
            }
            at[0] = to;
            travelled[0] += advance;
            crescent(to, direction, roll, 1.0f, false);
            for (BlockDisplay part : blade) {
                if (part.isValid()) {
                    part.teleport(oriented(to, direction));
                }
            }
            if (wall != null) {
                waveBreak(to, direction);
                return false;
            }
            return travelled[0] < range - 1.0E-6;
        }, () -> {
            for (BlockDisplay part : blade) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 3);
                }
            }
            Vfx.cloud(ctx(), at[0], Particle.DUST_COLOR_TRANSITION, 6, 0.3, 0, PALETTE.fade(0.8f), false);
        });
    }

    /** The thin blade of light riding in front of the wind crescent: a cyan sheet with a white edge. */
    private BlockDisplay[] waveBlade(Location start, Vector direction, float roll, long lifetime) {
        if (!roomFor(2)) {
            return new BlockDisplay[0]; // the crescent of particles alone still reads as the wave
        }
        Player owner = player();
        Location at = oriented(start, direction);
        BlockDisplay sheet = display(at, GLASS, Displays.transform(0, 0, 0.35f, 0.4f, 0.03f, 0.12f, 0, 0, roll),
                lifetime);
        BlockDisplay edge = display(at, CORE, Displays.transform(0, 0, 0.42f, 0.3f, 0.02f, 0.03f, 0, 0, roll),
                lifetime);
        sheet.setTeleportDuration(1);
        edge.setTeleportDuration(1);
        Displays.animateNextTick(ctx(), owner, sheet, Displays.transform(0, 0, 0.35f, 1.9f, 0.03f, 0.18f, 0, 0, roll),
                2);
        Displays.animateNextTick(ctx(), owner, edge, Displays.transform(0, 0, 0.45f, 1.6f, 0.02f, 0.04f, 0, 0, roll),
                2);
        return new BlockDisplay[] {sheet, edge};
    }

    private void waveHit(LivingEntity enemy) {
        Location body = Fx.body(enemy);
        Vfx.burst(ctx(), body, PALETTE, 0.4, Particle.ENCHANTED_HIT, false);
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.6f, 1.4f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.5f, 1.6f + Fx.jitter(0.1f));
    }

    private void waveBreak(Location at, Vector direction) {
        World world = at.getWorld();
        world.spawnParticle(Particle.ELECTRIC_SPARK, at, Vfx.n(ctx(), 8), 0.2, 0.3, 0.2, 0.08);
        Vfx.arc(ctx(), at, 0.6, yawOf(direction) + 180, 140, 8, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.8f),
                false);
        Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.6f, 0.9f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ active: gale slash

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 사용할 수 없습니다");
        }
        Location start = owner.getLocation();
        Vector direction = Motion.horizontalFacing(start);
        Location end = dashEnd(owner, start, direction, Math.max(MIN_DASH, params().num("dash_distance")));
        if (end == null) {
            return ActivationResult.fail("앞이 막혀 있습니다");
        }
        double height = owner.getHeight();
        Location startBody = start.clone().add(0, height * 0.5, 0);
        Location endBody = end.clone().add(0, height * 0.5, 0);
        List<LivingEntity> struck = enemiesAlong(owner, startBody, endBody, Math.max(0, params().num("dash_width")));

        if (!owner.teleport(end, TeleportCause.PLUGIN)) {
            return ActivationResult.fail("앞이 막혀 있습니다");
        }
        owner.setFallDistance(0);
        // The draw ends dead still: no momentum carries the master past the spot it chose.
        owner.setVelocity(new Vector());
        guardLanding(owner);

        dashVisual(owner, start, end, direction);
        double damage = params().num("dash_damage");
        ctx().runLater(owner, CUT_DELAY, () -> {
            int cut = 0;
            for (LivingEntity enemy : struck) {
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                crossCut(enemy, direction, cut++);
            }
            sheathe(owner, cut);
        });
        return ActivationResult.success();
    }

    /**
     * The farthest spot along {@code direction} (at most {@code maxDistance}) the owner can stand on with nothing
     * solid in between: each sample is tried level, slightly up and slightly down (snapped onto the ground below),
     * must pass {@link Spots#isSafe} and be reachable in a straight line at waist and eye height.
     */
    private @Nullable Location dashEnd(Player owner, Location start, Vector direction, double maxDistance) {
        World world = start.getWorld();
        Body body = Body.standing(owner);
        Location waist = start.clone().add(0, body.height() * 0.5, 0);
        Location eye = start.clone().add(0, body.eyeHeight(), 0);
        for (double d = maxDistance; d >= MIN_DASH - 1.0E-6; d -= DASH_STEP) {
            Location point = start.clone().add(direction.clone().multiply(d));
            for (double lift : DASH_LIFTS) {
                Location feet = point.clone().add(0, lift, 0);
                Spots.snapToGround(world, feet, GROUND_SNAP);
                if (!Spots.isSafe(ctx(), feet, body)
                        || !Spots.clearPath(waist, feet.clone().add(0, body.height() * 0.5, 0))
                        || !Spots.clearPath(eye, feet.clone().add(0, body.eyeHeight(), 0))) {
                    continue;
                }
                feet.setYaw(start.getYaw());
                feet.setPitch(start.getPitch());
                return feet;
            }
        }
        return null;
    }

    /**
     * Enemies within {@code width} of the segment {@code from} to {@code to}, in the order the dash meets them. Those
     * behind the starting point (pressed against the master's back) are not on the path.
     */
    private List<LivingEntity> enemiesAlong(Player owner, Location from, Location to, double width) {
        Map<UUID, LivingEntity> found = new LinkedHashMap<>();
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        int samples = Math.max(1, (int) Math.ceil(length / 0.5));
        for (int i = 0; i <= samples; i++) {
            Location sample = from.clone().add(delta.clone().multiply((double) i / samples));
            for (LivingEntity enemy : ctx().enemiesNear(owner, sample, width)) {
                if (ahead(from, delta, enemy)) {
                    found.putIfAbsent(enemy.getUniqueId(), enemy);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** False for an enemy whose body is more than half a block behind {@code from} along {@code direction}. */
    private static boolean ahead(Location from, Vector direction, LivingEntity enemy) {
        Vector flat = Motion.horizontal(direction);
        if (flat.lengthSquared() < 1.0E-6) {
            return true;
        }
        Vector offset = Fx.body(enemy).toVector().subtract(from.toVector()).setY(0);
        return offset.dot(flat) >= -BEHIND_TOLERANCE;
    }

    /**
     * Keeps the owner's fall distance at zero until it stands on the ground or swims (at most
     * {@link #LANDING_GRACE_TICKS}), so a dash that ends over a drop never turns into fall damage.
     */
    private void guardLanding(Player owner) {
        int token = ++fallGuardToken;
        Loop.repeat(ctx(), owner, 0, 1, LANDING_GRACE_TICKS, run -> {
            if (token != fallGuardToken) {
                return false;
            }
            owner.setFallDistance(0);
            return run < 2 || (!Motion.isGrounded(owner) && !owner.isInWater());
        });
    }

    /** The draw: an afterimage left behind, a long cut of light along the path and streaks racing after it. */
    private void dashVisual(Player owner, Location start, Location end, Vector direction) {
        World world = start.getWorld();
        double height = owner.getHeight();
        Location startBody = start.clone().add(0, height * 0.5, 0);
        Location endBody = end.clone().add(0, height * 0.5, 0);

        // Afterimage: a steel-blue silhouette that dissolves where the master stood.
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, startBody, Vfx.n(ctx(), 18), 0.2, height * 0.3, 0.2, 0,
                PALETTE.fade(1.1f));
        world.spawnParticle(Particle.CLOUD, start.clone().add(0, 0.1, 0), Vfx.n(ctx(), 5), 0.2, 0.02, 0.2, 0.03);
        world.spawnParticle(Particle.GUST, startBody, 1, 0, 0, 0, 0);

        cutLine(startBody.clone().subtract(0, height * 0.12, 0), endBody.clone().subtract(0, height * 0.12, 0));

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int streaks = Vfx.n(ctx(), 10);
        for (int i = 0; i < streaks; i++) {
            double ox = random.nextDouble(-0.35, 0.35);
            double oy = random.nextDouble(0.1, height * 0.95);
            double oz = random.nextDouble(-0.35, 0.35);
            Vfx.trail(start.clone().add(ox, oy, oz), end.clone().add(ox, oy, oz),
                    i % 3 == 0 ? PALETTE.core() : PALETTE.body(), 3 + random.nextInt(3), false);
        }
        Vfx.line(ctx(), startBody, endBody, 2.5, Particle.END_ROD, null, false);
        world.spawnParticle(Particle.SWEEP_ATTACK, endBody.clone().add(direction.clone().multiply(0.6)), 1, 0, 0, 0,
                0);
        Vfx.ring(ctx(), end.clone().add(0, 0.08, 0), 0.7, 12, random.nextDouble(TAU), Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(0.9f), false);

        // Afterglow: motes of light hang along the path for a moment and drift up.
        Loop.repeat(ctx(), owner, 2, 2, 6, run -> {
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                double t = ThreadLocalRandom.current().nextDouble();
                Location mote = startBody.clone().add(endBody.toVector().subtract(startBody.toVector()).multiply(t))
                        .add(0, ThreadLocalRandom.current().nextDouble(-0.5, 0.4), 0);
                Vfx.dot(mote, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.6f), false);
                if (i == 0) {
                    Vfx.trail(mote, mote.clone().add(0, 0.8, 0), PALETTE.core(), 10, false);
                }
            }
            return true;
        });

        Fx.sound(start, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.9f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.75f + Fx.jitter(0.05f));
        Fx.sound(end, Sound.ENTITY_BREEZE_WIND_BURST, 0.7f, 1.3f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚔ 질풍참</" + COLOR_TAG + ">"));
    }

    /** A long thin cut of light from {@code from} to {@code to}: it whips out along the path, glows, thins away. */
    private void cutLine(Location from, Location to) {
        Player owner = player();
        Vector delta = to.toVector().subtract(from.toVector());
        float length = (float) delta.length();
        if (length < 0.2f || !roomFor(2)) {
            return;
        }
        Location middle = oriented(from.clone().add(delta.clone().multiply(0.5)), delta);
        float roll = Fx.jitter(12);
        long life = 14;
        BlockDisplay sheet = display(middle, GLASS,
                Displays.transform(0, 0, -length / 2 + 0.05f, 0.34f, 0.03f, 0.1f, 0, 0, roll), life);
        BlockDisplay core = display(middle, CORE,
                Displays.transform(0, 0, -length / 2 + 0.05f, 0.06f, 0.035f, 0.1f, 0, 0, roll), life);
        Displays.animateNextTick(ctx(), owner, sheet, Displays.transform(0, 0, 0, 0.34f, 0.03f, length, 0, 0, roll),
                2);
        Displays.animateNextTick(ctx(), owner, core, Displays.transform(0, 0, 0, 0.06f, 0.035f, length, 0, 0, roll),
                2);
        ctx().runLater(owner, 6, () -> {
            Displays.animate(sheet, Displays.transform(0, 0, 0.2f, 0.001f, 0.001f, length * 1.05f, 0, 0, roll), 6);
            Displays.animate(core, Displays.transform(0, 0, 0.2f, 0.001f, 0.001f, length * 1.05f, 0, 0, roll), 6);
        });
    }

    /**
     * The cuts open on a struck enemy: two blades of light crossing in the plane across the dash, a burst, sparks
     * and a ringing slice. The blades are left out (the burst stays) once the display budget is spent.
     */
    private void crossCut(LivingEntity enemy, Vector direction, int index) {
        Player owner = player();
        Location body = Fx.body(enemy);
        Location at = oriented(body, direction);
        float tilt = 38 + Fx.jitter(10);
        float first = index % 2 == 0 ? tilt : -tilt;
        float[] rolls = roomFor(4) ? new float[] {first, -first} : new float[0];
        for (float roll : rolls) {
            for (boolean core : new boolean[] {false, true}) {
                float depth = core ? 0.04f : 0.16f;
                BlockDisplay part = display(at, core ? CORE : GLASS,
                        Displays.transform(0, 0, 0, 0.3f, 0.03f, depth, 0, 0, roll), 12);
                Displays.animateNextTick(ctx(), owner, part,
                        Displays.transform(0, 0, 0, 2.0f, core ? 0.035f : 0.03f, depth, 0, 0, roll), 2);
                ctx().runLater(owner, 5, () -> Displays.animate(part,
                        Displays.transform(0, 0, 0, 2.3f, 0.001f, 0.001f, 0, 0, roll), 5));
            }
        }
        World world = body.getWorld();
        Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.CRIT, false);
        world.spawnParticle(Particle.SWEEP_ATTACK, body, 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.ELECTRIC_SPARK, body, Vfx.n(ctx(), 8), 0.25, 0.35, 0.25, 0.1);
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.25f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.1f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ITEM_TRIDENT_HIT, 0.7f, 1.4f + Fx.jitter(0.1f));
    }

    /** The blade returns to its sheath: a clean metallic click and a ring of light at the master's feet. */
    private void sheathe(Player owner, int cut) {
        Location at = owner.getLocation();
        Fx.sound(at, Sound.BLOCK_CHAIN_PLACE, 0.9f, 1.8f + Fx.jitter(0.05f));
        Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.7f + Fx.jitter(0.1f));
        Vfx.ring(ctx(), at.clone().add(0, 0.1, 0), 0.6, 10, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST,
                CORE_DUST, false);
        if (cut > 0) {
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚔ 질풍참</" + COLOR_TAG + "> <gray>" + cut + "명 베기</gray>"));
        }
    }

    // ------------------------------------------------------------------ ultimate: heavenly swords

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = Math.max(1, params().num("ult_radius"));
        if (ctx().enemiesNear(owner, owner.getLocation(), radius).stream().noneMatch(enemy -> seen(owner, enemy))) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        Halo halo = new Halo(radius);
        halo.summon();
        Loop.repeat(ctx(), owner, 1, 1, halo.maxTicks(), halo::tick, halo::finish);
        return ActivationResult.success();
    }

    private enum Phase { HOVER, AIM, DIVE, PLANTED, GONE }

    /** One spectral sword: three block displays sharing one location and facing (+z is the point). */
    private final class Sword {

        private final BlockDisplay[] parts = new BlockDisplay[SWORD_PARTS.length];
        private final int slot;
        private Location center;
        private Vector facing = new Vector(0, -1, 0);
        private Phase phase = Phase.HOVER;
        private @Nullable LivingEntity target;
        private Location aim;
        private long phaseTicks;
        private long diveTicks;
        private float roll;

        Sword(Location spawn, int slot, long lifetime) {
            this.slot = slot;
            this.center = spawn.clone();
            this.aim = spawn.clone();
            this.roll = ThreadLocalRandom.current().nextFloat() * 180f;
            Location at = oriented(spawn, facing);
            for (int i = 0; i < SWORD_PARTS.length; i++) {
                parts[i] = display(at, SWORD_PARTS[i].material(), pose(SWORD_PARTS[i], 0.1f, roll), lifetime);
                parts[i].setTeleportDuration(1);
            }
        }

        void grow() {
            for (int i = 0; i < parts.length; i++) {
                Displays.animate(parts[i], pose(SWORD_PARTS[i], 1f, roll), 6);
            }
        }

        void place(Location newCenter, Vector newFacing) {
            center = newCenter;
            if (newFacing.lengthSquared() > 1.0E-8) {
                facing = newFacing.clone().normalize();
            }
            Location at = oriented(center, facing);
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    part.teleport(at);
                }
            }
        }

        Location point() {
            return center.clone().add(facing.clone().multiply(POINT));
        }

        boolean isAlive() {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    return true;
                }
            }
            return false;
        }

        void dissolve() {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), player(), part, FADE_TICKS);
                }
            }
            Vfx.cloud(ctx(), center, Particle.DUST_COLOR_TRANSITION, 8, 0.25, 0, PALETTE.fade(0.9f), false);
            Vfx.cloud(ctx(), center, Particle.END_ROD, 4, 0.2, 0.02, null, false);
            phase = Phase.GONE;
        }
    }

    /** One 천검: the halo of swords, the launch order and the round-robin of targets. */
    private final class Halo {

        private final double radius;
        private final int count = Math.max(1, params().integer("ult_swords"));
        private final long interval = Math.max(1, params().ticks("ult_interval"));
        private final double damage = params().num("ult_damage");
        private final double impactRadius = Math.max(0.3, params().num("ult_impact_radius"));
        private final List<Sword> swords = new ArrayList<>();
        private final Map<UUID, Integer> assigned = new HashMap<>();
        private final double phase0 = ThreadLocalRandom.current().nextDouble(TAU);
        private int launched;
        private int hits;

        Halo(double radius) {
            this.radius = radius;
        }

        long maxTicks() {
            return SUMMON_TICKS + HOLD_TICKS + count * interval + AIM_TICKS + MAX_DIVE_TICKS + PLANTED_TICKS
                    + FADE_TICKS + 4;
        }

        void summon() {
            Player owner = player();
            Location at = owner.getLocation();
            Location chest = at.clone().add(0, owner.getHeight() * 0.6, 0);
            World world = at.getWorld();
            long lifetime = maxTicks() + 10;
            for (int i = 0; i < count; i++) {
                swords.add(new Sword(chest, i, lifetime));
            }
            ctx().runLater(owner, 1, () -> swords.forEach(Sword::grow));

            world.spawnParticle(Particle.FLASH, chest, 1, 0, 0, 0, 0, PALETTE.core(), true);
            Vfx.helix(ctx(), at, 0.7, owner.getHeight() + 1.4, 1.5, 3, 14, phase0, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.2f), true);
            Vfx.shockwave(ctx(), owner, at.clone().add(0, 0.1, 0), 0.5, 3.0, 6, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.3f), true);
            world.spawnParticle(Particle.END_ROD, chest, Vfx.n(ctx(), 16), 0.4, 0.6, 0.4, 0.08, null, true);
            Fx.sound(at, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.2f, 0.8f);
            Fx.sound(at, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.4f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 1.2f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.BLOCK_BELL_RESONATE, 0.6f, 1.6f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⚔ 천검</b></" + COLOR_TAG + "> <gray>검 " + count
                    + "자루</gray>"));
        }

        boolean tick(long tick) {
            Player owner = player();
            Location feet = owner.getLocation();
            double spin = phase0 + tick * HALO_SPIN;
            long launchStart = SUMMON_TICKS + HOLD_TICKS;
            if (tick >= launchStart && launched < count && (tick - launchStart) % interval == 0) {
                launchNext(owner);
            }
            boolean busy = false;
            for (Sword sword : swords) {
                switch (sword.phase) {
                    case HOVER -> {
                        hover(sword, feet, spin, tick);
                        busy = true;
                    }
                    case AIM -> {
                        aim(sword);
                        busy = true;
                    }
                    case DIVE -> {
                        dive(sword);
                        busy = true;
                    }
                    case PLANTED -> {
                        if (++sword.phaseTicks >= PLANTED_TICKS) {
                            sword.dissolve();
                        } else {
                            busy = true;
                        }
                    }
                    case GONE -> {
                    }
                }
            }
            if (tick % 3 == 0) {
                haloGlow(owner, feet, spin);
            }
            return busy || launched < count;
        }

        void finish() {
            for (Sword sword : swords) {
                if (sword.phase != Phase.GONE) {
                    sword.dissolve();
                }
            }
            if (hits > 0) {
                player().sendActionBar(Text.mm("<" + COLOR_TAG + ">⚔ 천검</" + COLOR_TAG + "> <gray>" + hits
                        + "회 적중</gray>"));
            }
        }

        /** Circling over the master's head, points down and slightly out, bobbing. */
        private void hover(Sword sword, Location feet, double spin, long tick) {
            double angle = spin + sword.slot * TAU / count;
            double rise = Math.min(1.0, (tick + 1) / (double) SUMMON_TICKS);
            double eased = 1 - (1 - rise) * (1 - rise);
            double bob = Math.sin(tick * 0.25 + sword.slot) * 0.08;
            Vector out = new Vector(Math.cos(angle), 0, Math.sin(angle));
            Location target = feet.clone().add(out.clone().multiply(HALO_RADIUS * eased))
                    .add(0, 1.0 + (HALO_HEIGHT - 1.0) * eased + bob, 0);
            Vector facing = new Vector(0, -1, 0).add(out.clone().multiply(0.3));
            sword.place(target, facing);
        }

        private void launchNext(Player owner) {
            Sword sword = null;
            for (Sword candidate : swords) {
                if (candidate.phase == Phase.HOVER) {
                    sword = candidate;
                    break;
                }
            }
            if (sword == null) {
                launched = count;
                return;
            }
            launched++;
            LivingEntity target = pickTarget(owner);
            if (target == null) {
                sword.dissolve();
                return;
            }
            assigned.merge(target.getUniqueId(), 1, Integer::sum);
            sword.target = target;
            sword.aim = target.getLocation();
            sword.phase = Phase.AIM;
            sword.phaseTicks = 0;
            Fx.sound(sword.center, Sound.ITEM_TRIDENT_RETURN, 0.9f, 1.5f + Fx.jitter(0.1f));
            Fx.sound(sword.center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.4f + Fx.jitter(0.15f));
        }

        /**
         * The enemy within range with the fewest swords on it so far, nearest first. Enemies the master can see
         * come first: breaking line of sight is the way to shake the swords off while others are in view. Enemies
         * hidden by stealth are never hunted (a marker ring under them would give them away).
         */
        private @Nullable LivingEntity pickTarget(Player owner) {
            LivingEntity best = null;
            int fewest = Integer.MAX_VALUE;
            boolean bestVisible = false;
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), radius)) {
                if (!seen(owner, enemy)) {
                    continue;
                }
                int taken = assigned.getOrDefault(enemy.getUniqueId(), 0);
                boolean visible = owner.hasLineOfSight(enemy);
                if (best == null || (visible && !bestVisible) || (visible == bestVisible && taken < fewest)) {
                    best = enemy;
                    fewest = taken;
                    bestVisible = visible;
                }
            }
            return best;
        }

        /** The sword rises a little and turns its point on the target; the landing marker appears. */
        private void aim(Sword sword) {
            sword.phaseTicks++;
            follow(sword);
            Vector toTarget = sword.aim.toVector().subtract(sword.center.toVector());
            double t = Math.min(1.0, sword.phaseTicks / (double) AIM_TICKS);
            Vector facing = sword.facing.clone().multiply(1 - t).add(safeNormal(toTarget).multiply(t));
            sword.place(sword.center.clone().add(0, 0.15, 0), facing);
            marker(sword, false);
            if (sword.phaseTicks >= AIM_TICKS) {
                double distance = sword.point().distance(sword.aim);
                sword.diveTicks = Math.clamp(Math.round(distance / DIVE_SPEED), MIN_DIVE_TICKS, MAX_DIVE_TICKS);
                sword.phase = Phase.DIVE;
                sword.phaseTicks = 0;
                Fx.sound(sword.center, Sound.ITEM_TRIDENT_THROW, 1.0f, 1.2f + Fx.jitter(0.1f));
                Fx.sound(sword.center, Sound.ENTITY_PHANTOM_FLAP, 0.5f, 1.6f + Fx.jitter(0.1f));
            }
        }

        /** Homes in on the target until it commits, then drops straight onto the marked spot. */
        private void dive(Sword sword) {
            long left = sword.diveTicks - sword.phaseTicks;
            if (left > COMMIT_TICKS) {
                follow(sword);
            }
            sword.phaseTicks++;
            Location point = sword.point();
            Vector remaining = sword.aim.toVector().subtract(point.toVector());
            Vector step = remaining.clone().multiply(1.0 / Math.max(1, left));
            Location next = point.clone().add(step);
            Vector facing = remaining.lengthSquared() > 1.0E-6 ? remaining : sword.facing;
            sword.place(next.clone().subtract(safeNormal(facing).multiply(POINT)), facing);
            Vfx.line(ctx(), point, next, 3, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.8f), true);
            Vfx.dot(next, Particle.END_ROD, null, false);
            marker(sword, left <= COMMIT_TICKS);
            if (left <= 1) {
                impact(sword);
            }
        }

        /** While not committed the aim follows the target's feet (not into stealth: it keeps the last spot then). */
        private void follow(Sword sword) {
            LivingEntity target = sword.target;
            if (target != null && Combat.isTargetable(ctx(), player(), target) && seen(player(), target)
                    && target.getWorld().equals(sword.aim.getWorld())) {
                sword.aim = target.getLocation();
            }
        }

        /** The landing marker: a cyan ring at the aim that turns white once the sword has committed. */
        private void marker(Sword sword, boolean committed) {
            Location ring = sword.aim.clone().add(0, 0.08, 0);
            double spin = sword.phaseTicks * 0.3 + sword.slot;
            Vfx.ring(ctx(), ring, impactRadius, 14, spin, Particle.DUST,
                    committed ? CORE_DUST : PALETTE.dust(0.9f), true);
            if (sword.phaseTicks % 2 == 0) {
                Vfx.ring(ctx(), ring, impactRadius * 0.45, 7, -spin, Particle.DUST_COLOR_TRANSITION,
                        PALETTE.fade(0.8f), false);
            }
        }

        private void impact(Sword sword) {
            Player owner = player();
            Location ground = sword.aim.clone();
            Location waist = ground.clone().add(0, 0.9, 0);
            for (LivingEntity enemy : ctx().enemiesNear(owner, waist, impactRadius)) {
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                Vfx.burst(ctx(), Fx.body(enemy), PALETTE, 0.5, Particle.ENCHANTED_HIT, true);
            }
            // Planted: the point sinks into the ground, the sword leans as it landed.
            Vector facing = sword.facing.clone();
            if (facing.getY() > -0.5) {
                facing.setY(-0.5);
            }
            facing = safeNormal(facing);
            Location point = ground.clone().add(facing.clone().multiply(PLANT_DEPTH));
            sword.place(point.clone().subtract(facing.clone().multiply(POINT)), facing);
            sword.phase = Phase.PLANTED;
            sword.phaseTicks = 0;
            impactVisual(ground);
        }

        private void impactVisual(Location ground) {
            World world = ground.getWorld();
            Location at = ground.clone().add(0, 0.15, 0);
            world.spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, PALETTE.core(), true);
            Vfx.burst(ctx(), at.clone().add(0, 0.3, 0), PALETTE, 0.7, Particle.END_ROD, true);
            Vfx.shockwave(ctx(), player(), at, 0.3, impactRadius + 0.4, 5, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.1f), true);
            BlockData floor = ground.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
            if (floor.getMaterial().isSolid()) {
                world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 14), 0.4, 0.1, 0.4, 0.15, floor);
            }
            world.spawnParticle(Particle.ELECTRIC_SPARK, at, Vfx.n(ctx(), 10), 0.3, 0.2, 0.3, 0.15, null, true);
            Fx.sound(at, Sound.ITEM_TRIDENT_HIT_GROUND, 1.2f, 0.9f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.ITEM_MACE_SMASH_GROUND, 0.6f, 1.5f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.8f, 1.3f + Fx.jitter(0.15f));
        }

        /** Motes drifting between the hovering swords and a faint ring of light under them. */
        private void haloGlow(Player owner, Location feet, double spin) {
            boolean hovering = false;
            for (Sword sword : swords) {
                if (sword.phase == Phase.HOVER && sword.isAlive()) {
                    hovering = true;
                    Vfx.dot(sword.point(), Particle.END_ROD, null, false);
                }
            }
            if (hovering) {
                Vfx.ring(ctx(), feet.clone().add(0, HALO_HEIGHT + 0.6, 0), HALO_RADIUS, 16, -spin,
                        Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.7f), false);
                Vfx.ring(ctx(), feet.clone().add(0, 0.08, 0), HALO_RADIUS * 0.8, 12, spin, Particle.DUST,
                        PALETTE.dust(0.8f), false);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Spawns a block display of the ability and counts it against {@link #MAX_DISPLAYS}. */
    private BlockDisplay display(Location at, Material material, Transformation pose, long lifetime) {
        BlockDisplay display = Displays.block(ctx(), player(), at, material.createBlockData(), pose, lifetime);
        displays.add(display);
        return display;
    }

    /** True if {@code count} more displays fit in {@link #MAX_DISPLAYS}. */
    private boolean roomFor(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    /** False for an enemy player hidden from the owner by stealth. */
    private static boolean seen(Player owner, LivingEntity enemy) {
        return !(enemy instanceof Player player) || owner.canSee(player);
    }

    /** The pose of one sword part at {@code scale} (1 = full size), rolled {@code roll} degrees about its length. */
    private static Transformation pose(Part part, float scale, float roll) {
        return Displays.transform(0, 0, part.offset() * scale, part.width() * scale, part.thickness() * scale,
                part.length() * scale, 0, 0, roll);
    }

    /**
     * A layered crescent of wind at {@code center}, opening towards {@code direction} and tilted {@code roll}
     * degrees: a white core line, a cyan glow and a steel edge.
     */
    private void crescent(Location center, Vector direction, float roll, float size, boolean force) {
        Vector forward = Motion.horizontal(direction);
        if (forward.lengthSquared() < 1.0E-6) {
            return;
        }
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        double tilt = Math.toRadians(roll);
        Vector sweep = right.multiply(Math.cos(tilt)).add(new Vector(0, Math.sin(tilt), 0));
        Location pivot = center.clone().subtract(forward.clone().multiply(WAVE_ARC_RADIUS));
        int count = Math.max(3, Vfx.n(ctx(), 9));
        double half = Math.toRadians(WAVE_ARC_DEGREES / 2);
        Object[] layers = {new Particle.DustOptions(PALETTE.core(), size * 0.6f), PALETTE.glow(size),
                PALETTE.fade(size * 0.8f)};
        for (int layer = 0; layer < layers.length; layer++) {
            double r = WAVE_ARC_RADIUS * (0.9 + layer * 0.12);
            Particle particle = layer == 0 ? Particle.DUST : Particle.DUST_COLOR_TRANSITION;
            for (int i = 0; i < count; i++) {
                double a = -half + 2 * half * i / (count - 1);
                Location at = pivot.clone().add(forward.clone().multiply(Math.cos(a) * r))
                        .add(sweep.clone().multiply(Math.sin(a) * r));
                Vfx.dot(at, particle, layers[layer], force);
            }
        }
    }

    /** A copy of {@code at} turned to face {@code direction} (display entities rotate with their location). */
    private static Location oriented(Location at, Vector direction) {
        Location copy = at.clone();
        if (direction.lengthSquared() > 1.0E-8 && Double.isFinite(direction.lengthSquared())) {
            copy.setDirection(direction);
        } else {
            copy.setYaw(0);
            copy.setPitch(0);
        }
        return copy;
    }

    private static Vector safeNormal(Vector vector) {
        double length = vector.length();
        if (!(length > 1.0E-6) || !Double.isFinite(length)) {
            return new Vector(0, -1, 0);
        }
        return vector.clone().multiply(1 / length);
    }

    /** The Minecraft yaw (degrees) of a horizontal direction. */
    private static float yawOf(Vector direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }
}
