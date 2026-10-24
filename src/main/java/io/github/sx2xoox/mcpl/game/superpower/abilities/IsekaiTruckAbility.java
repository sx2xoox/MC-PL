package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 이세계 트럭 — the truck of the web-novel trope that sends whoever it hits to another world. Its victims are reborn as
 * "Lv.1": they lose part of their ultimate gauge and take more damage from the truck's owner for a while. The active lays
 * a road ahead on which a truck thunders past on a fixed beat, horn and headlights first; the ultimate turns a whole
 * crossing into rush hour, trucks crossing along and across its lanes one after another, the gaps between the lanes the
 * only safe ground.
 *
 * <p>Look (truck white, headlight yellow, rebirth sky blue): a dark road with white edge lines and a dashed yellow middle
 * line; each truck is a white box truck built of block displays (cab, windshield, cargo box with a sky-blue stripe,
 * glowing headlights, wheels) that the headlights announce as two yellow beams down its lane; a hit throws up dust and
 * sparks, and a sky-blue rebirth circle turns under the victim while a "Lv.1" sign floats over its head.
 */
public final class IsekaiTruckAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("isekaitruck", "이세계 트럭", Material.FURNACE_MINECART)
            .color(TextColor.color(0xFFE15A))
            .difficulty(1)
            .passive("전생 처리",
                    "트럭에 치인 적은 전생해 궁극기 게이지를 {mark_gauge}% 잃고, {mark_duration}초간 내가 주는 피해를 "
                            + "{mark_bonus}% 더 받습니다.")
            .active("트럭 도로",
                    "바라보는 방향으로 폭 {road_width}블록·길이 {road_length}블록의 도로를 {road_duration}초간 깝니다. "
                            + "{truck_interval}초마다 {truck_warning}초 경고 뒤 트럭이 도로를 질주해, 치인 적은 "
                            + "{truck_damage} 피해를 입고 옆으로 튕겨 나가며 전생합니다(적마다 최대 {road_hits}번). "
                            + "벽에 부딪힌 트럭은 반경 {crash_radius}블록의 적에게 {crash_damage} 피해를 줍니다.",
                    10)
            .ultimate("러시아워",
                    "조준한 곳({rush_range}블록 이내) 중심에 {rush_size}×{rush_size}블록 교차로를 엽니다. "
                            + "{rush_interval}초마다 차선 하나에 {rush_warning}초 경고 뒤 트럭이 가로·세로를 번갈아 "
                            + "질주합니다(모두 {rush_trucks}대). 치인 적은 {rush_damage} 피해를 입고 튕겨 나가며 "
                            + "전생해 궁극기 게이지를 {rush_gauge}% 잃습니다(적마다 최대 {rush_hits}번).")
            .param("mark_gauge", 15)
            .param("mark_duration", 10)
            .param("mark_bonus", 15)
            .param("road_width", 3)
            .param("road_length", 22)
            .param("road_duration", 5)
            .param("truck_interval", 1.25)
            .param("truck_warning", 0.6)
            .param("truck_damage", 4)
            .param("road_hits", 2)
            .param("crash_radius", 2)
            .param("crash_damage", 2)
            .param("rush_range", 24)
            .param("rush_size", 16)
            .param("rush_interval", 0.6)
            .param("rush_warning", 0.7)
            .param("rush_trucks", 6)
            .param("rush_damage", 3)
            .param("rush_gauge", 10)
            .param("rush_hits", 3)
            .build();

    private static final String YELLOW_TAG = "#FFE15A";
    private static final String SKY_TAG = "#6FD3FF";

    // ------------------------------------------------------------------ look
    /** Truck white, headlight yellow, rebirth sky blue. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF2F2F2, 0xFFE15A, 0x6FD3FF);
    private static final Color WHITE = Color.fromRGB(0xF2F2F2);
    private static final Color YELLOW = Color.fromRGB(0xFFE15A);
    private static final Color SKY = Color.fromRGB(0x6FD3FF);
    private static final Particle.DustOptions BEAM_DUST = new Particle.DustOptions(YELLOW, 1.1f);
    private static final Particle.DustOptions LINE_DUST = new Particle.DustOptions(WHITE, 0.8f);
    private static final Particle.DustOptions DASH_DUST = new Particle.DustOptions(YELLOW, 0.8f);
    private static final Particle.DustOptions SKY_DUST = new Particle.DustOptions(SKY, 1.0f);
    private static final Particle.DustTransition REBIRTH = new Particle.DustTransition(Color.fromRGB(0xE8FAFF), SKY,
            1.1f);

    private static final Material ASPHALT = Material.GRAY_CONCRETE;
    private static final Material EDGE = Material.WHITE_CONCRETE;
    private static final Material BODY = Material.WHITE_CONCRETE;
    private static final Material CAB = Material.SMOOTH_QUARTZ;
    private static final Material GLASS = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final Material LAMP = Material.OCHRE_FROGLIGHT;
    private static final Material WHEEL = Material.BLACK_CONCRETE;
    private static final Material GRILLE = Material.LIGHT_GRAY_CONCRETE;
    private static final Material STRIPE = Material.LIGHT_BLUE_CONCRETE;

    /** One part of the truck: its center and size in truck space (x right, y up, z forward; z 0 = the bumper). */
    private record Part(Material material, float cx, float cy, float cz, float sx, float sy, float sz) {
    }

    private static final Part[] TRUCK = {
            new Part(BODY, 0, 1.45f, -2.3f, 2.3f, 1.9f, 2.8f),
            new Part(STRIPE, 0, 1.15f, -2.3f, 2.34f, 0.25f, 2.84f),
            new Part(CAB, 0, 1.15f, -0.45f, 2.2f, 1.5f, 1.0f),
            new Part(GLASS, 0, 1.45f, 0.06f, 1.9f, 0.55f, 0.06f),
            new Part(GRILLE, 0, 0.5f, 0.02f, 2.2f, 0.32f, 0.1f),
            new Part(LAMP, -0.75f, 0.78f, 0.04f, 0.4f, 0.26f, 0.06f),
            new Part(LAMP, 0.75f, 0.78f, 0.04f, 0.4f, 0.26f, 0.06f),
            new Part(WHEEL, 0, 0.3f, -0.5f, 2.4f, 0.6f, 0.6f),
            new Part(WHEEL, 0, 0.3f, -3.0f, 2.4f, 0.6f, 0.6f),
    };
    /** Truck length from bumper to tail. */
    private static final double TRUCK_LENGTH = 3.7;
    private static final double TRUCK_HEIGHT = 2.6;
    /** Blocks per tick. */
    private static final double TRUCK_SPEED = 2.0;
    private static final double MAX_SPEED = 2.0;
    private static final double SIDE_PUSH = 1.1;
    private static final double LIFT = 0.45;
    private static final double AHEAD_PUSH = 0.35;
    private static final long ROAD_REVEAL = 6;
    private static final int MAX_DISPLAYS = 24;
    private static final double HEADLIGHT_Y = 0.8;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final Map<UUID, Mark> marks = new HashMap<>();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        for (Mark mark : List.copyOf(marks.values())) {
            mark.clear();
        }
        marks.clear();
        live.clear();
    }

    /** True while {@code entity} is reborn as Lv.1 (it takes more damage from the owner). */
    public boolean isMarked(LivingEntity entity) {
        Mark mark = marks.get(entity.getUniqueId());
        return mark != null && mark.active();
    }

    // ================================================================== passive: rebirth

    /** Lv.1 victims take more damage from the truck's owner; only changes the damage. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isMarked(victim) || !ctx().isEnemy(player(), victim)) {
            return;
        }
        event.setDamage(event.getDamage() * (1 + Math.max(0, params().num("mark_bonus")) / 100.0));
    }

    /** Someone the truck hit is reborn: gauge lost, a Lv.1 sign over the head, a rebirth circle under the feet. */
    private void rebirth(Player owner, LivingEntity victim, double gaugeLoss) {
        if (victim instanceof Player enemy && gaugeLoss > 0) {
            ctx().addGauge(enemy, -gaugeLoss);
        }
        long duration = Math.max(1, params().ticks("mark_duration"));
        Mark mark = marks.get(victim.getUniqueId());
        if (mark == null || !mark.active()) {
            mark = new Mark(owner, victim);
            marks.put(victim.getUniqueId(), mark);
            mark.start(duration);
        } else {
            mark.extend(duration);
        }
        if (seen(owner, victim)) {
            circle(owner, victim.getLocation().add(0, 0.05, 0));
        }
        Location head = victim.getLocation().add(0, victim.getHeight(), 0);
        Fx.sound(head, Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.8f + Fx.jitter(0.05f));
        Fx.sound(head, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.4f + Fx.jitter(0.1f));
        if (victim instanceof Player enemy && !ctx().hasShownPrompt(enemy)) {
            enemy.sendActionBar(Text.mm("<" + SKY_TAG + ">✦ 이세계로 전생했습니다…</" + SKY_TAG + "> <b><white>Lv.1"
                    + "</white></b> <gray>궁극기 게이지 -" + AbilityInfo.formatNumber(gaugeLoss) + "%</gray>"));
        }
    }

    /** The rebirth circle: two sky-blue rings and a star of light turning under the victim. */
    private void circle(Player owner, Location center) {
        Location at = center.clone();
        Loop.repeat(ctx(), owner, 0, 3, 7, run -> {
            double spin = run * 0.35;
            Vfx.ring(ctx(), at, 1.3, 20, spin, Particle.DUST_COLOR_TRANSITION, REBIRTH, false);
            Vfx.ring(ctx(), at, 0.8, 12, -spin, Particle.DUST, SKY_DUST, false);
            if (run % 2 == 0) {
                for (int i = 0; i < 6; i++) {
                    double a = spin + i * Math.PI / 3;
                    double b = spin + (i + 2) * Math.PI / 3;
                    Vfx.line(ctx(), at.clone().add(Math.cos(a) * 1.3, 0, Math.sin(a) * 1.3),
                            at.clone().add(Math.cos(b) * 1.3, 0, Math.sin(b) * 1.3), 2.0, Particle.DUST, SKY_DUST,
                            false);
                }
            }
            if (run == 0) {
                Vfx.cloud(ctx(), at.clone().add(0, 0.8, 0), Particle.END_ROD, 8, 0.4, 0.05, null, false);
            }
            return true;
        });
    }

    /** A Lv.1 rebirth: the sign over the head follows the victim until it wears off. */
    private final class Mark {
        private final Player owner;
        private final LivingEntity victim;
        private long until;
        private @Nullable TextDisplay sign;
        private boolean running;

        Mark(Player owner, LivingEntity victim) {
            this.owner = owner;
            this.victim = victim;
        }

        boolean active() {
            return running && ctx().currentTick() < until && victim.isValid() && !victim.isDead();
        }

        void start(long duration) {
            until = ctx().currentTick() + duration;
            running = true;
            Loop.repeat(ctx(), owner, 0, 1, Long.MAX_VALUE / 4, run -> {
                if (!active()) {
                    return false;
                }
                follow();
                return true;
            }, this::clear);
        }

        void extend(long duration) {
            until = Math.max(until, ctx().currentTick() + duration);
        }

        private void follow() {
            Location at = Displays.unrotated(victim.getLocation().add(0, victim.getHeight() + 0.55, 0));
            TextDisplay shown = sign;
            if (shown == null || !shown.isValid()) {
                if (!reserve(1)) {
                    return;
                }
                shown = Displays.text(ctx(), owner, at, Text.mm("<b><white>Lv.1</white></b> <" + SKY_TAG + ">전생자</"
                        + SKY_TAG + ">"), 0.7f, 0, viewer -> !ctx().isConcealedFrom(victim, viewer));
                shown.setTeleportDuration(2);
                keep(shown);
                sign = shown;
                return;
            }
            Displays.moveTo(shown, at);
        }

        void clear() {
            running = false;
            TextDisplay shown = sign;
            if (shown != null && shown.isValid()) {
                shown.remove();
            }
            sign = null;
            if (marks.get(victim.getUniqueId()) == this) {
                marks.remove(victim.getUniqueId());
            }
        }
    }

    // ================================================================== the truck

    /** A lane trucks drive along: from {@code start} along {@code direction} for {@code length}, {@code width} wide. */
    private record Lane(Location start, Vector direction, double length, double width) {

        Vector right() {
            return new Vector(-direction.getZ(), 0, direction.getX());
        }

        /** Where a point lies in lane space: x across (right positive), y along. */
        double along(Location point) {
            return point.toVector().subtract(start.toVector()).dot(direction);
        }

        double across(Location point) {
            return point.toVector().subtract(start.toVector()).dot(right());
        }

        Location at(double along) {
            return start.clone().add(direction.clone().multiply(along));
        }
    }

    /** What a truck does to whom it hits. */
    private record Load(double damage, double gaugeLoss, int maxHits, Map<UUID, Integer> hits) {
    }

    /**
     * One truck: the headlight warning down its lane, then the run from one end to the other, hitting every enemy in
     * its box once; it stops and crashes at a wall.
     */
    private final class Truck {
        private final Player owner;
        private final Lane lane;
        private final Load load;
        private final long warning;
        private final boolean wall;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private final Set<UUID> struck = new HashSet<>();
        private final float yaw;
        private double front;

        Truck(Player owner, Lane lane, Load load, long warning, boolean wall) {
            this.owner = owner;
            this.lane = lane;
            this.load = load;
            this.warning = Math.max(1, warning);
            this.wall = wall;
            this.yaw = (float) Math.toDegrees(Math.atan2(-lane.direction().getX(), lane.direction().getZ()));
        }

        void go() {
            honk(lane.start());
            long runTicks = (long) Math.ceil(lane.length() / TRUCK_SPEED) + 2;
            Loop.repeat(ctx(), owner, 0, 1, warning + runTicks, run -> {
                if (run < warning) {
                    headlights(run);
                    if (run == warning - 2) {
                        spawn();
                    }
                    return true;
                }
                return drive();
            }, this::finish);
        }

        /** "빵빵—!" */
        private void honk(Location at) {
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.6f, 1.6f);
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_BASS, 1.4f, 1.2f);
            ctx().runLater(owner, 4, () -> {
                Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.6f, 1.6f);
                Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_BASS, 1.4f, 1.2f);
            });
        }

        /** Two yellow beams reach down the lane from where the truck will come. */
        private void headlights(long run) {
            if (run % 2 != 0) {
                return;
            }
            double reach = lane.length() * Math.min(1.0, (run + 2) / (double) warning);
            Vector side = lane.right().multiply(lane.width() * 0.3);
            Location from = lane.start().clone().add(0, HEADLIGHT_Y, 0);
            Location to = lane.at(reach).add(0, HEADLIGHT_Y * 0.4, 0);
            Vfx.line(ctx(), from.clone().add(side), to.clone().add(side), 1.4, Particle.DUST, BEAM_DUST, true);
            Vfx.line(ctx(), from.clone().subtract(side), to.clone().subtract(side), 1.4, Particle.DUST, BEAM_DUST,
                    true);
            Vfx.dot(from.clone().add(side), Particle.END_ROD, null, true);
            Vfx.dot(from.clone().subtract(side), Particle.END_ROD, null, true);
        }

        /** The truck appears just behind the lane's start, so its bumper crosses the start as the run begins. */
        private void spawn() {
            if (!reserve(TRUCK.length)) {
                return;
            }
            Location at = Displays.unrotated(lane.start());
            Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
            for (Part part : TRUCK) {
                BlockDisplay display = keep(Displays.block(ctx(), owner, at, part.material().createBlockData(),
                        pose(part, rotation), 0));
                display.setTeleportDuration(1);
                if (part.material() == LAMP) {
                    Displays.outline(display, YELLOW);
                }
                parts.add(display);
            }
            front = 0;
        }

        /** One tick of the run; false once it has reached the end or a wall. */
        private boolean drive() {
            double previous = front;
            front = Math.min(lane.length(), front + TRUCK_SPEED);
            Location bumper = lane.at(front);
            for (BlockDisplay part : parts) {
                Displays.moveTo(part, Displays.unrotated(bumper));
            }
            sweep(previous - TRUCK_LENGTH, front);
            Location dust = lane.at(Math.max(0, front - TRUCK_LENGTH)).add(0, 0.2, 0);
            Vfx.cloud(ctx(), dust, Particle.CLOUD, 3, 0.6, 0.02, null, false);
            Vfx.dot(bumper.clone().add(0, HEADLIGHT_Y, 0), Particle.END_ROD, null, false);
            if (previous == 0) {
                Fx.sound(bumper, Sound.ENTITY_MINECART_RIDING, 1.4f, 1.4f);
                Fx.sound(bumper, Sound.ENTITY_BREEZE_WIND_BURST, 0.9f, 0.6f + Fx.jitter(0.05f));
            }
            if (front >= lane.length() - 1.0E-6) {
                if (wall) {
                    crash(bumper);
                }
                return false;
            }
            return true;
        }

        /** Every enemy in the truck's box between the two bumper positions is hit (once per truck). */
        private void sweep(double fromAlong, double toAlong) {
            Location middle = lane.at((fromAlong + toAlong) / 2);
            double reach = (toAlong - fromAlong) / 2 + lane.width() + 2;
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, reach)) {
                if (struck.contains(enemy.getUniqueId())) {
                    continue;
                }
                Location at = enemy.getLocation();
                double half = enemy.getWidth() / 2;
                double along = lane.along(at);
                double across = lane.across(at);
                double dy = at.getY() - lane.start().getY();
                if (along + half < fromAlong || along - half > toAlong || Math.abs(across) > lane.width() / 2 + half
                        || dy > TRUCK_HEIGHT || dy + enemy.getHeight() < -0.5) {
                    continue;
                }
                struck.add(enemy.getUniqueId());
                hit(enemy, across);
            }
        }

        private void hit(LivingEntity enemy, double across) {
            int count = load.hits().getOrDefault(enemy.getUniqueId(), 0);
            if (count >= load.maxHits()) {
                return;
            }
            load.hits().put(enemy.getUniqueId(), count + 1);
            Combat.hit(ctx(), owner, enemy, load.damage());
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            double side = Math.abs(across) < 0.05 ? (ThreadLocalRandom.current().nextBoolean() ? 1 : -1)
                    : Math.signum(across);
            Vector push = lane.right().multiply(side * SIDE_PUSH).add(lane.direction().clone().multiply(AHEAD_PUSH))
                    .setY(LIFT);
            Motion.push(enemy, push, MAX_SPEED);
            rebirth(owner, enemy, load.gaugeLoss());
            Location body = Fx.body(enemy);
            if (seen(owner, enemy)) {
                Vfx.burst(ctx(), body, PALETTE, 0.8, Particle.CRIT, false);
                Vfx.cloud(ctx(), enemy.getLocation().add(0, 0.2, 0), Particle.CLOUD, 6, 0.4, 0.05, null, false);
            }
            Fx.sound(body, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.9f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f + Fx.jitter(0.1f));
        }

        /** The truck runs into a wall: it stops dead and the crash hurts the enemies around it. */
        private void crash(Location bumper) {
            double radius = Math.max(0, params().num("crash_radius"));
            Location center = bumper.clone().add(0, 1.0, 0);
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                Combat.hit(ctx(), owner, enemy, params().num("crash_damage"));
            }
            Vfx.burst(ctx(), center, PALETTE, 1.4, Particle.LARGE_SMOKE, true);
            Vfx.cloud(ctx(), center, Particle.SMOKE, 16, 0.8, 0.04, null, false);
            Vfx.ring(ctx(), bumper.clone().add(0, 0.1, 0), Math.max(0.5, radius), 18, 0, Particle.DUST, BEAM_DUST,
                    false);
            Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.5f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.BLOCK_ANVIL_LAND, 1.0f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.0f, 0.7f);
        }

        /** The truck fades out where it stopped. */
        private void finish() {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 4);
                }
            }
            parts.clear();
        }
    }

    /** A truck part in truck space, turned by {@code rotation} about the bumper. */
    private static Transformation pose(Part part, Quaternionf rotation) {
        Vector3f size = new Vector3f(part.sx(), part.sy(), part.sz());
        Vector3f corner = new Vector3f(part.cx(), part.cy(), part.cz()).sub(new Vector3f(size).mul(0.5f));
        Vector3f translation = new Quaternionf(rotation).transform(corner);
        return new Transformation(translation, new Quaternionf(rotation), size, new Quaternionf());
    }

    /**
     * How far a truck can drive from {@code start} along {@code direction} (at most {@code length}) before a wall at body
     * height across its width.
     */
    private static double clearRun(Location start, Vector direction, double length, double width) {
        World world = start.getWorld();
        Vector right = new Vector(-direction.getZ(), 0, direction.getX());
        double reach = length;
        for (double side : new double[] {0, width / 2 - 0.3, -(width / 2 - 0.3)}) {
            for (double height : new double[] {0.5, 1.5}) {
                Location from = start.clone().add(right.clone().multiply(side)).add(0, height, 0);
                RayTraceResult hit = world.rayTraceBlocks(from, direction, length, FluidCollisionMode.NEVER, true);
                if (hit != null) {
                    reach = Math.min(reach, hit.getHitPosition().distance(from.toVector()) - 0.15);
                }
            }
        }
        return Math.max(0, reach);
    }

    // ================================================================== active: truck road

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location feet = owner.getLocation();
        Vector direction = Motion.horizontalFacing(feet);
        double width = Math.max(1, params().num("road_width"));
        double length = Math.max(2, params().num("road_length"));
        Location start = Displays.unrotated(feet);
        Spots.snapToGround(start.getWorld(), start, 1.5);
        double reach = Spots.arenaReach(ctx(), start, direction, length);
        double clear = clearRun(start, direction, reach, width);
        if (clear < 3) {
            return ActivationResult.fail("도로를 깔 자리가 없습니다");
        }
        Lane lane = new Lane(start, direction, clear, width);
        boolean wall = clear < reach - 0.5;
        new Road(owner, lane, wall).open();
        return ActivationResult.success();
    }

    /** The road: laid out over a few ticks, trucks on a fixed beat while it lasts. */
    private final class Road {
        private final Player owner;
        private final Lane lane;
        private final boolean wall;
        private final long duration = Math.max(20, params().ticks("road_duration"));
        private final long interval = Math.max(10, params().ticks("truck_interval"));
        private final long warning = Math.max(2, params().ticks("truck_warning"));
        private final Load load;
        private final List<BlockDisplay> surface = new ArrayList<>();

        Road(Player owner, Lane lane, boolean wall) {
            this.owner = owner;
            this.lane = lane;
            this.wall = wall;
            this.load = new Load(params().num("truck_damage"), Math.max(0, params().num("mark_gauge")),
                    Math.max(1, (int) Math.round(params().num("road_hits"))), new HashMap<>());
        }

        void open() {
            lay();
            long runTicks = (long) Math.ceil(lane.length() / TRUCK_SPEED) + 2;
            Loop.repeat(ctx(), owner, 0, 1, duration, run -> {
                if (run % interval == 0 && run + warning + runTicks <= duration + 2) {
                    new Truck(owner, lane, load, warning, wall).go();
                }
                if (run % 5 == 0) {
                    paint(run);
                }
                return true;
            }, this::close);
        }

        /** The asphalt and its edge lines roll out from the owner's feet. */
        private void lay() {
            Location start = lane.start();
            Fx.sound(start, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1.6f, 1.4f);
            Fx.sound(start, Sound.BLOCK_NOTE_BLOCK_BASS, 1.4f, 1.0f);
            Fx.sound(start, Sound.BLOCK_PISTON_EXTEND, 0.8f, 0.7f);
            if (!reserve(3)) {
                return;
            }
            float yaw = (float) Math.toDegrees(Math.atan2(-lane.direction().getX(), lane.direction().getZ()));
            Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
            float width = (float) lane.width();
            float length = (float) lane.length();
            Location at = Displays.unrotated(start);
            Part asphalt = new Part(ASPHALT, 0, 0.01f, length / 2, width, 0.02f, length);
            Part left = new Part(EDGE, -width / 2 + 0.12f, 0.03f, length / 2, 0.14f, 0.02f, length);
            Part right = new Part(EDGE, width / 2 - 0.12f, 0.03f, length / 2, 0.14f, 0.02f, length);
            for (Part part : new Part[] {asphalt, left, right}) {
                Part seed = new Part(part.material(), part.cx(), part.cy(), 0.05f, part.sx(), part.sy(), 0.1f);
                BlockDisplay display = keep(Displays.block(ctx(), owner, at, part.material().createBlockData(),
                        pose(seed, rotation), duration + ROAD_REVEAL + 10));
                Displays.animateNextTick(ctx(), owner, display, pose(part, rotation), (int) ROAD_REVEAL);
                surface.add(display);
            }
        }

        /** The dashed yellow middle line and a few motes over the road. */
        private void paint(long run) {
            for (double d = 1.0 + (run / 5 % 2); d < lane.length(); d += 2.0) {
                Location a = lane.at(d).add(0, 0.08, 0);
                Location b = lane.at(Math.min(lane.length(), d + 0.9)).add(0, 0.08, 0);
                Vfx.line(ctx(), a, b, 3, Particle.DUST, DASH_DUST, false);
            }
            if (run % 10 == 0) {
                Vector side = lane.right().multiply(lane.width() / 2);
                Vfx.dot(lane.at(lane.length()).add(side).add(0, 0.3, 0), Particle.DUST, LINE_DUST, false);
                Vfx.dot(lane.at(lane.length()).subtract(side).add(0, 0.3, 0), Particle.DUST, LINE_DUST, false);
            }
        }

        private void close() {
            for (BlockDisplay display : surface) {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 6);
                }
            }
            surface.clear();
        }
    }

    // ================================================================== ultimate: rush hour

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location center = aimPoint(owner, Math.max(1, params().num("rush_range")));
        if (center == null) {
            return ActivationResult.fail("교차로를 열 자리가 없습니다");
        }
        new Rush(owner, center).open();
        return ActivationResult.success();
    }

    /** The ground where the owner aims (an aimed enemy's feet, the block looked at, or the ground at the range). */
    private @Nullable Location aimPoint(Player owner, double range) {
        World world = owner.getWorld();
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        Location point;
        LivingEntity aimed = ctx().findTarget(owner, range);
        RayTraceResult block = world.rayTraceBlocks(eye, look, range, FluidCollisionMode.NEVER, true);
        if (aimed != null && (block == null || block.getHitPosition().distance(eye.toVector())
                > aimed.getLocation().distance(eye))) {
            point = aimed.getLocation();
        } else if (block != null) {
            point = block.getHitPosition().toLocation(world).add(0, 0.05, 0);
        } else {
            point = eye.clone().add(look.clone().multiply(range));
        }
        point = Displays.unrotated(point);
        point.add(0, 0.5, 0);
        Spots.snapToGround(world, point, 12);
        if (!Spots.hasGroundBelow(world, point) || !ctx().isInsideArena(point)) {
            Location feet = Displays.unrotated(owner.getLocation());
            return ctx().isInsideArena(feet) ? feet : null;
        }
        return point;
    }

    /** Rush hour: the crossing, its lanes along both axes, and the trucks taking turns. */
    private final class Rush {
        private final Player owner;
        private final Location center;
        private final double half;
        private final double width = Math.max(1, params().num("road_width"));
        private final long interval = Math.max(4, params().ticks("rush_interval"));
        private final long warning = Math.max(2, params().ticks("rush_warning"));
        private final int trucks = Math.max(1, (int) Math.round(params().num("rush_trucks")));
        private final Load load;
        private final double[] offsets;
        private @Nullable BlockDisplay floor;
        private @Nullable TextDisplay sign;

        Rush(Player owner, Location center) {
            this.owner = owner;
            this.center = center;
            this.half = Math.max(2, params().num("rush_size") / 2);
            this.load = new Load(params().num("rush_damage"), Math.max(0, params().num("rush_gauge")),
                    Math.max(1, (int) Math.round(params().num("rush_hits"))), new HashMap<>());
            // Three lanes per axis, the gaps between them the safe ground.
            double spacing = Math.max(width + 1, (half * 2 - width) / 2);
            double outer = Math.min(half - width / 2, spacing);
            this.offsets = new double[] {-outer, 0, outer};
        }

        void open() {
            Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 2.0f, 1.3f);
            Fx.sound(center, Sound.BLOCK_BELL_USE, 1.2f, 1.4f);
            Fx.sound(center, Sound.ENTITY_MINECART_RIDING, 1.2f, 0.8f);
            owner.showTitle(net.kyori.adventure.title.Title.title(Text.mm("<b><" + YELLOW_TAG + ">러시아워!</"
                    + YELLOW_TAG + "></b>"), Text.mm("<gray>차선 사이의 안전지대를 찾으세요</gray>")));
            if (reserve(2)) {
                float size = (float) (half * 2);
                BlockDisplay plate = keep(Displays.block(ctx(), owner, center.clone().add(0, 0.01, 0),
                        ASPHALT.createBlockData(), Displays.transform(0, 0, 0, 0.5f, 0.02f, 0.5f, 0, 0, 0), 0));
                Displays.animateNextTick(ctx(), owner, plate, Displays.transform(0, 0, 0, size, 0.02f, size, 0, 0, 0),
                        6);
                floor = plate;
                sign = keep(Displays.text(ctx(), owner, center.clone().add(0, 4.2, 0), Text.mm("<b><" + YELLOW_TAG
                        + ">러시아워</" + YELLOW_TAG + "></b>\n<white>RUSH HOUR</white>"), 1.4f, 0));
            }
            long runTicks = (long) Math.ceil((half * 2 + TRUCK_LENGTH + 2) / TRUCK_SPEED) + 2;
            long total = (trucks - 1) * interval + warning + runTicks + 4;
            // Each axis takes its lanes in a shuffled order, every lane once before any lane again.
            List<List<Integer>> order = List.of(shuffled(), shuffled());
            Loop.repeat(ctx(), owner, 0, 1, total, run -> {
                if (run % interval == 0 && run / interval < trucks) {
                    int index = (int) (run / interval);
                    int axis = index % 2;
                    List<Integer> lanes = order.get(axis);
                    launch(axis, offsets[lanes.get((index / 2) % lanes.size())]);
                }
                if (run % 6 == 0) {
                    markings(run);
                }
                return true;
            }, this::close);
        }

        private List<Integer> shuffled() {
            List<Integer> lanes = new ArrayList<>();
            for (int i = 0; i < offsets.length; i++) {
                lanes.add(i);
            }
            java.util.Collections.shuffle(lanes, ThreadLocalRandom.current());
            return lanes;
        }

        /** A truck along x (axis 0) or z (axis 1), on the lane {@code offset} from the middle, from a random end. */
        private void launch(int axis, double offset) {
            boolean flip = ThreadLocalRandom.current().nextBoolean();
            Vector direction = axis == 0 ? new Vector(flip ? -1 : 1, 0, 0) : new Vector(0, 0, flip ? -1 : 1);
            Vector across = new Vector(-direction.getZ(), 0, direction.getX());
            double entry = half + 1;
            Location start = center.clone().subtract(direction.clone().multiply(entry)).add(across.multiply(offset));
            double length = half * 2 + 2;
            double clear = clearRun(start, direction, length, width);
            boolean wall = clear < length - 0.5;
            if (clear < 1) {
                return;
            }
            new Truck(owner, new Lane(start, direction, clear, width), load, warning, wall).go();
        }

        /** The lane edges in white and the safe squares between them in sky blue. */
        private void markings(long run) {
            for (double offset : offsets) {
                for (double edge : new double[] {offset - width / 2, offset + width / 2}) {
                    if (run % 12 == 0) {
                        Vfx.line(ctx(), center.clone().add(-half, 0.08, edge), center.clone().add(half, 0.08, edge),
                                0.7, Particle.DUST, LINE_DUST, true);
                    } else {
                        Vfx.line(ctx(), center.clone().add(edge, 0.08, -half), center.clone().add(edge, 0.08, half),
                                0.7, Particle.DUST, LINE_DUST, true);
                    }
                }
            }
            for (int i = 0; i < offsets.length - 1; i++) {
                double mid = (offsets[i] + offsets[i + 1]) / 2;
                for (int j = 0; j < offsets.length - 1; j++) {
                    double other = (offsets[j] + offsets[j + 1]) / 2;
                    Vfx.ring(ctx(), center.clone().add(mid, 0.1, other), 0.6, 6, run * 0.2, Particle.DUST, SKY_DUST,
                            false);
                }
            }
        }

        private void close() {
            BlockDisplay plate = floor;
            if (plate != null && plate.isValid()) {
                Displays.shrinkAway(ctx(), owner, plate, 8);
            }
            TextDisplay shown = sign;
            if (shown != null && shown.isValid()) {
                Displays.shrinkAway(ctx(), owner, shown, 6);
            }
            floor = null;
            sign = null;
        }
    }

    // ================================================================== helpers

    /** True if the owner can see {@code target} (effects on a hidden enemy would give it away). */
    private boolean seen(Player owner, LivingEntity target) {
        return !ctx().isConcealedFrom(target, owner);
    }

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
