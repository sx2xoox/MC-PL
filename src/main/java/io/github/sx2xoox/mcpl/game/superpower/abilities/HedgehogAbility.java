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
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 고슴도치 — small, round and all spikes. Whoever hits it in melee is pricked and left with a quill stuck in them;
 * it shoots a ring of quills in every direction (tearing out the quills already stuck in its victims for extra
 * damage); and as the ultimate it curls into a spiky ball that rolls about at speed, bowls enemies over and strews
 * caltrops behind it.
 *
 * <p>Look: chestnut and cream. Quills are pointed dripstone: they stick out of whoever pricked the hedgehog, fly out
 * in a bristling ring with cream streaks, and stick quivering into walls. The ball is a tumbling chestnut core
 * bristling with eight quills that turns the way it rolls, kicking up dust; the caltrops are little spikes left on
 * the ground that snap when stepped on.
 */
public final class HedgehogAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("hedgehog", "고슴도치", Material.POINTED_DRIPSTONE)
            .color(TextColor.color(0xC98A52))
            .difficulty(1)
            .passive("가시",
                    "나를 근접 공격한 적은 가시에 찔려 {thorn_damage} 피해를 입고 몸에 가시가 박힙니다(최대 {quill_max}개, "
                            + "{quill_duration}초 유지). 같은 적은 {thorn_cooldown}초마다 찔립니다.")
            .active("가시 발사",
                    "몸을 부풀려 사방으로 가시 {quill_count}개를 한꺼번에 쏩니다. 가시는 {volley_range}블록 안의 적마다 "
                            + "하나씩 날아가 꽂혀 {volley_damage} 피해를 주고, 그 적에게 박혀 있던 가시는 하나당 "
                            + "{quill_burst} 피해를 더 주며 뽑혀 나갑니다. 벽은 뚫지 못합니다.",
                    9)
            .ultimate("가시 공",
                    "{ball_duration}초간 몸을 말아 가시 공이 됩니다. 이동 속도가 {ball_speed}% 빨라지고 받는 피해가 "
                            + "{ball_reduction}% 줄며 밀려나지 않습니다. 굴러가며 닿은 적에게 {ball_damage} 피해를 주고 "
                            + "튕겨 내며(같은 적은 {ball_hit_interval}초마다, 최대 {ball_max_hits}번), {spike_interval}초마다 "
                            + "지나간 자리에 가시를 흩뿌립니다. 가시는 {spike_duration}초간 남아 밟은 적에게 {spike_damage} "
                            + "피해를 주고 {spike_slow}초간 이동 속도를 {spike_slow_amount}% 늦춥니다.")
            .param("thorn_damage", 1)
            .param("quill_max", 3)
            .param("quill_duration", 8)
            .param("thorn_cooldown", 0.5)
            .param("quill_count", 16)
            .param("volley_range", 7)
            .param("volley_damage", 4)
            .param("quill_burst", 1.5)
            .param("ball_duration", 6)
            .param("ball_speed", 60)
            .param("ball_reduction", 30)
            .param("ball_damage", 3)
            .param("ball_hit_interval", 1)
            .param("ball_max_hits", 3)
            .param("spike_interval", 0.5)
            .param("spike_duration", 5)
            .param("spike_damage", 2)
            .param("spike_slow", 1)
            .param("spike_slow_amount", 35)
            .ultimateNotStandalone()
            .build();

    private static final String COLOR_TAG = "#C98A52";
    private static final String CREAM_TAG = "#FFF1D0";
    private static final double TAU = Math.PI * 2;
    private static final double GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));

    // ------------------------------------------------------------------ look
    /** Cream glow, chestnut, dark brown. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF1D0, 0xA0612F, 0x3A2412);
    private static final Particle.DustOptions CHESTNUT = new Particle.DustOptions(Color.fromRGB(0xA0612F), 1.0f);
    private static final Particle.DustOptions CREAM = new Particle.DustOptions(Color.fromRGB(0xFFF1D0), 0.7f);
    private static final Particle.DustTransition QUILL_STREAK = PALETTE.glow(0.7f);

    /** Materials of the visuals; block data is created when used (it needs a running server). */
    private static final Material QUILL = Material.POINTED_DRIPSTONE;
    private static final Material[] CORE = {Material.BROWN_WOOL, Material.BROWN_TERRACOTTA, Material.BROWN_WOOL};
    private static final int DISPLAY_BUDGET = 24;

    private static final double MAX_SPEED = 1.6;

    // ------------------------------------------------------------------ passive: quills
    /**
     * Stuck quills follow their host every tick, gliding to each new spot over about the time a client takes to
     * glide a moving player to its new position, so they ride on the body instead of trailing it.
     */
    private static final int QUILL_GLIDE_TICKS = 3;
    /** The quills turn with their host once its body has turned this far (degrees). */
    private static final float QUILL_TURN_STEP = 4;
    private static final int STUCK_DISPLAY_CAP = 9;
    private static final float STUCK_WIDTH = 0.12f;
    private static final float STUCK_LENGTH = 0.5f;
    /** Where stuck quills sit around a body: angle (degrees), height (share of the body) and tilt upwards. */
    private static final double[][] STUCK_SLOTS = {{200, 0.72, 0.6}, {320, 0.55, 0.4}, {80, 0.85, 0.8},
            {140, 0.4, 0.3}, {20, 0.65, 0.5}};

    // ------------------------------------------------------------------ active: quill volley
    private static final int MAX_QUILLS = 32;
    private static final double QUILL_SPEED = 1.2;
    /** Enemy hitboxes are inflated by this much for a quill. */
    private static final double QUILL_RADIUS = 0.35;
    private static final float QUILL_WIDTH = 0.16f;
    private static final float QUILL_LENGTH = 0.75f;
    private static final long WALL_STICK_TICKS = 12;

    // ------------------------------------------------------------------ ultimate: spike ball
    private static final double BALL_SHRINK = 0.4;
    private static final float CORE_SIZE = 0.9f;
    private static final int BALL_SPIKES = 8;
    private static final float BALL_SPIKE_WIDTH = 0.22f;
    private static final float BALL_SPIKE_LENGTH = 0.6f;
    private static final float BALL_SPIKE_BASE = 0.32f;
    private static final double BALL_REACH = 1.0;
    private static final double BALL_PUSH = 0.9;
    private static final double BALL_LIFT = 0.35;
    private static final double DROP_SPACING = 0.7;
    private static final double CALTROP_RADIUS = 0.7;
    private static final long CALTROP_PERIOD = 2;
    private static final long GROW_BACK_TIMEOUT = 60;
    /** Fibonacci directions of the ball's spikes, fixed so the ball keeps its shape as it rolls. */
    private static final Vector3f[] SPIKE_DIRECTIONS = new Vector3f[BALL_SPIKES];

    static {
        for (int i = 0; i < BALL_SPIKES; i++) {
            double y = 1 - (i + 0.5) * 2.0 / BALL_SPIKES;
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double angle = i * GOLDEN_ANGLE;
            SPIKE_DIRECTIONS[i] = new Vector3f((float) (Math.cos(angle) * r), (float) y, (float) (Math.sin(angle) * r));
        }
    }

    private static final Quaternionf[] CORE_ORIENTATIONS = {
            new Quaternionf(),
            new Quaternionf().rotateY((float) Math.toRadians(45)).rotateX((float) Math.toRadians(35.26)),
            new Quaternionf().rotateZ((float) Math.toRadians(45)).rotateY((float) Math.toRadians(30)),
    };

    /** A quill display stuck in an enemy at one of the {@link #STUCK_SLOTS}. */
    private record Stuck(BlockDisplay display, int slot) {
    }

    /** Quills stuck in one enemy and when that enemy may be pricked again. */
    private static final class Quills {
        int count;
        long expires;
        long nextThorn = Long.MIN_VALUE / 2;
        /** The body yaw the quills were last turned to. */
        float yaw;
        final List<Stuck> displays = new ArrayList<>();
    }

    private final Map<UUID, Quills> stuck = new HashMap<>();
    private final List<Display> live = new ArrayList<>();
    private final Map<UUID, NamespacedKey> caltropSlows = new HashMap<>();
    private @Nullable Ball ball;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        stuck.clear();
        live.clear();
        caltropSlows.clear();
        ball = null;
    }

    /** True if {@code count} more displays fit in this hedgehog's display budget. */
    private boolean room(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= DISPLAY_BUDGET;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    // ------------------------------------------------------------------ passive: quills

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0) {
            return;
        }
        Ball rolling = ball;
        if (rolling != null && !rolling.ended) {
            double reduction = Math.clamp(params().num("ball_reduction") / 100.0, 0.0, 1.0);
            event.setDamage(event.getDamage() * (1 - reduction));
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity) || ctx().isAbilityDamage()
                || event.getCause() != DamageCause.ENTITY_ATTACK && event.getCause() != DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        Player owner = player();
        if (!(byEntity.getDamager() instanceof LivingEntity attacker) || !ctx().isEnemy(owner, attacker)) {
            return;
        }
        Quills quills = stuck.computeIfAbsent(attacker.getUniqueId(), id -> new Quills());
        long now = ctx().currentTick();
        if (now < quills.nextThorn) {
            return;
        }
        quills.nextThorn = now + params().ticks("thorn_cooldown");
        // On the next tick: never a second damage event inside this one.
        ctx().runLater(owner, 1, () -> prick(attacker));
    }

    /** The attacker is pricked: thorn damage and a quill stuck in its body. */
    private void prick(LivingEntity attacker) {
        Player owner = player();
        if (!Combat.isTargetable(ctx(), owner, attacker) || !attacker.getWorld().equals(owner.getWorld())) {
            return;
        }
        Combat.hit(ctx(), owner, attacker, params().num("thorn_damage"));
        bristle(owner, 0.6);
        if (!hidden(attacker)) {
            Location from = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
            Location body = Fx.body(attacker);
            Vfx.trail(from, body, PALETTE.core(), 3, false);
            Vfx.cloud(ctx(), body, Particle.CRIT, 6, 0.2, 0.15, null, false);
            body.getWorld().spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 5), 0.2, 0.25, 0.2, 0, CHESTNUT);
            Fx.sound(body, Sound.ENCHANT_THORNS_HIT, 1.0f, 1.0f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_PUFFER_FISH_STING, 0.6f, 1.3f + Fx.jitter(0.1f));
        }
        if (!Combat.isTargetable(ctx(), owner, attacker)) {
            return;
        }
        Quills quills = stuck.computeIfAbsent(attacker.getUniqueId(), id -> new Quills());
        int max = Math.max(0, params().integer("quill_max"));
        if (max <= 0) {
            return;
        }
        quills.expires = ctx().currentTick() + Math.max(1, params().ticks("quill_duration"));
        if (quills.count < max) {
            quills.count++;
            stickQuill(attacker, quills);
        }
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">가시!</" + COLOR_TAG + "> <gray>박힌 가시</gray> <white>"
                + quills.count + "/" + max + "</white>"));
    }

    /** A quill sticking out of the attacker's body (while the display budget allows). */
    private void stickQuill(LivingEntity attacker, Quills quills) {
        int slot = quills.count - 1;
        if (slot >= STUCK_SLOTS.length || totalStuckDisplays() >= STUCK_DISPLAY_CAP || !room(1)) {
            return;
        }
        Player owner = player();
        float yaw = attacker.getBodyYaw();
        if (quills.displays.isEmpty()) {
            quills.yaw = yaw;
        }
        BlockDisplay quill = keep(Displays.block(ctx(), owner, Displays.unrotated(attacker.getLocation()),
                QUILL.createBlockData(), stuckPose(attacker, slot, 0.2f, quills.yaw), 0));
        quill.setTeleportDuration(QUILL_GLIDE_TICKS);
        Displays.animateNextTick(ctx(), owner, quill, stuckPose(attacker, slot, 1f, quills.yaw), 3);
        if (hidden(attacker)) {
            quill.setViewRange(0);
        }
        quills.displays.add(new Stuck(quill, slot));
    }

    private int totalStuckDisplays() {
        int total = 0;
        for (Quills quills : stuck.values()) {
            quills.displays.removeIf(quill -> !quill.display().isValid());
            total += quills.displays.size();
        }
        return total;
    }

    /** True if {@code enemy} is a player hidden from the hedgehog: nothing may be drawn that shows where it is. */
    private boolean hidden(LivingEntity enemy) {
        return enemy instanceof Player player && !player().canSee(player);
    }

    /**
     * A stuck quill: its base on the body surface at a slot, pointing outwards and a little up. The slots are
     * around the body as it faces {@code yaw} (0 = +Z, 90 = -X), so the quills turn with it.
     */
    static Transformation stuckPose(LivingEntity body, int slot, float grow, float yaw) {
        double[] spot = STUCK_SLOTS[slot % STUCK_SLOTS.length];
        double angle = Math.toRadians(spot[0]);
        double radius = body.getWidth() * 0.4;
        // Body space (x left, z forward) to world space for a body facing yaw.
        double turn = Math.toRadians(yaw);
        double cos = Math.cos(turn);
        double sin = Math.sin(turn);
        double localX = Math.cos(angle);
        double localZ = Math.sin(angle);
        float outX = (float) (localX * cos - localZ * sin);
        float outZ = (float) (localX * sin + localZ * cos);
        Vector3f base = new Vector3f((float) (outX * radius), (float) (body.getHeight() * spot[1]),
                (float) (outZ * radius));
        Vector3f direction = new Vector3f(outX, (float) spot[2], outZ).normalize();
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), direction);
        Vector3f size = new Vector3f(STUCK_WIDTH, STUCK_LENGTH, STUCK_WIDTH).mul(grow);
        // The quill's base (bottom centre of the block) sits on the body, sunk in a little.
        Vector3f translation = base.sub(rotation.transform(new Vector3f(size.x() / 2, size.y() * 0.15f, size.z() / 2)));
        return new Transformation(translation, rotation, size, new Quaternionf());
    }

    @Override
    public void onTick(long tick) {
        if (stuck.isEmpty()) {
            return;
        }
        Player owner = player();
        long now = ctx().currentTick();
        Iterator<Map.Entry<UUID, Quills>> entries = stuck.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, Quills> entry = entries.next();
            Quills quills = entry.getValue();
            Entity entity = owner.getServer().getEntity(entry.getKey());
            boolean gone = !(entity instanceof LivingEntity body) || !Combat.isTargetable(ctx(), owner, body)
                    || !body.getWorld().equals(owner.getWorld());
            if (quills.count > 0 && (gone || now >= quills.expires)) {
                dropQuills(quills, entity instanceof LivingEntity body ? body : null);
            }
            if (gone || quills.count == 0 && now >= quills.nextThorn) {
                entries.remove(); // nothing stuck and free to be pricked again: forget the enemy
                continue;
            }
            if (!(entity instanceof LivingEntity body)) {
                continue;
            }
            boolean hidden = hidden(body);
            Location feet = Displays.unrotated(body.getLocation());
            float yaw = body.getBodyYaw();
            boolean turned = Math.abs(wrapDegrees(yaw - quills.yaw)) > QUILL_TURN_STEP;
            if (turned) {
                quills.yaw = yaw;
            }
            for (Stuck quill : quills.displays) {
                BlockDisplay display = quill.display();
                if (display.isValid()) {
                    Displays.moveTo(display, feet);
                    display.setViewRange(hidden ? 0 : 1.5f);
                    if (turned) {
                        Displays.animate(display, stuckPose(body, quill.slot(), 1f, yaw), 2);
                    }
                }
            }
            if (quills.count > 0 && tick % 20 == 0 && !hidden) {
                Vfx.dot(body.getLocation().add(0, body.getHeight() * 0.7, 0), Particle.DUST, CREAM, false);
            }
        }
    }

    /** The stuck quills fall out (they expired, or the enemy is gone). */
    private void dropQuills(Quills quills, @Nullable LivingEntity body) {
        quills.count = 0;
        for (Stuck stuckQuill : quills.displays) {
            BlockDisplay quill = stuckQuill.display();
            if (quill.isValid()) {
                quill.setTeleportDuration(6);
                Displays.moveTo(quill, quill.getLocation().subtract(0, 0.6, 0));
                Displays.shrinkAway(ctx(), player(), quill, 6);
            }
        }
        quills.displays.clear();
        if (body != null && !hidden(body)) {
            Vfx.cloud(ctx(), Fx.body(body), Particle.DUST, 3, 0.2, 0, CREAM, false);
        }
    }

    /** The quills tear out of the enemy (the volley hit it): they burst off its body. */
    private int tearQuills(LivingEntity enemy) {
        Quills quills = stuck.get(enemy.getUniqueId());
        if (quills == null || quills.count == 0) {
            return 0;
        }
        int torn = quills.count;
        quills.count = 0;
        Player owner = player();
        Location body = Fx.body(enemy);
        for (Stuck stuckQuill : quills.displays) {
            BlockDisplay quill = stuckQuill.display();
            if (!quill.isValid()) {
                continue;
            }
            Vector out = new Vector(ThreadLocalRandom.current().nextDouble(-1, 1), 0.8,
                    ThreadLocalRandom.current().nextDouble(-1, 1)).normalize().multiply(1.2);
            quill.setTeleportDuration(5);
            Displays.moveTo(quill, quill.getLocation().add(out));
            Displays.shrinkAway(ctx(), owner, quill, 5);
        }
        quills.displays.clear();
        if (hidden(enemy)) {
            return torn; // torn out unseen: no burst that would show where the enemy is
        }
        Vfx.cloud(ctx(), body, Particle.CRIT, 8 + 4 * torn, 0.3, 0.3, null, false);
        Vfx.burst(ctx(), body, PALETTE, 0.4 + 0.15 * torn, Particle.CRIT, false);
        Fx.sound(body, Sound.BLOCK_POINTED_DRIPSTONE_BREAK, 1.0f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 0.8f + Fx.jitter(0.1f));
        return torn;
    }

    /** The hedgehog bristles: a burst of cream and chestnut points standing out around its body. */
    private void bristle(Player owner, double strength) {
        Location center = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        World world = center.getWorld();
        int points = Vfx.n(ctx(), (int) Math.round(10 * strength));
        double phase = ThreadLocalRandom.current().nextDouble(TAU);
        for (int i = 0; i < points; i++) {
            double y = 1 - (i + 0.5) * 2.0 / points;
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double angle = phase + i * GOLDEN_ANGLE;
            Vector direction = new Vector(Math.cos(angle) * r, y * 0.8, Math.sin(angle) * r);
            Location inner = center.clone().add(direction.clone().multiply(0.35));
            Location outer = center.clone().add(direction.clone().multiply(0.35 + 0.45 * strength));
            world.spawnParticle(Particle.DUST, inner, 1, 0, 0, 0, 0, CHESTNUT);
            world.spawnParticle(Particle.DUST, outer, 1, 0, 0, 0, 0, CREAM);
        }
    }

    // ------------------------------------------------------------------ active: quill volley

    @Override
    public ActivationResult onActive() {
        new Volley(player()).fire();
        return ActivationResult.success();
    }

    /** One quill of a volley. */
    private static final class Quill {
        final Vector direction;
        final @Nullable LivingEntity target;
        final Location position;
        @Nullable BlockDisplay display;
        double traveled;
        boolean done;

        Quill(Location from, Vector direction, @Nullable LivingEntity target) {
            this.position = from.clone();
            this.direction = direction;
            this.target = target;
        }
    }

    /**
     * The volley: quills in a ring around the hedgehog; every enemy in range with a clear line gets the quill nearest
     * its direction turned exactly onto it. Each quill stops at the first enemy or block it meets; an enemy is hit by
     * one quill at most.
     */
    private final class Volley implements LongPredicate {

        private final Player owner;
        private final World world;
        private final Location chest;
        private final double range;
        private final List<Quill> quills = new ArrayList<>();
        private final Set<UUID> struck = new HashSet<>();
        private int hits;
        private int torn;

        Volley(Player owner) {
            this.owner = owner;
            this.world = owner.getWorld();
            this.chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
            this.range = Math.max(1, params().num("volley_range"));
        }

        void fire() {
            int count = Math.clamp(params().integer("quill_count"), 1, MAX_QUILLS);
            double phase = ThreadLocalRandom.current().nextDouble(TAU);
            List<Vector> directions = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                double angle = phase + i * TAU / count;
                directions.add(new Vector(Math.cos(angle), 0, Math.sin(angle)));
            }
            List<LivingEntity> targets = new ArrayList<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, chest, range)) {
                if (targets.size() >= count) {
                    break;
                }
                // A quill turned exactly onto a hidden enemy would point it out; radial quills may still hit it.
                if (!(enemy instanceof Player hidden && !owner.canSee(hidden)) && Spots.clearPath(chest, Fx.body(enemy))) {
                    targets.add(enemy);
                }
            }
            for (LivingEntity enemy : targets) {
                Vector toward = Fx.body(enemy).toVector().subtract(chest.toVector());
                if (toward.lengthSquared() < 1.0E-6) {
                    toward = new Vector(0, 0, 1);
                }
                toward.normalize();
                int best = 0;
                double bestDot = -2;
                for (int i = 0; i < directions.size(); i++) {
                    double dot = directions.get(i).dot(Motion.horizontal(toward));
                    if (dot > bestDot) {
                        bestDot = dot;
                        best = i;
                    }
                }
                directions.remove(best);
                quills.add(new Quill(chest, toward, enemy));
            }
            for (Vector direction : directions) {
                quills.add(new Quill(chest, direction, null));
            }
            long life = (long) Math.ceil(range / QUILL_SPEED) + 3;
            for (Quill quill : quills) {
                if (room(1)) {
                    Location at = chest.clone().add(quill.direction.clone().multiply(0.4));
                    BlockDisplay display = keep(Displays.block(ctx(), owner, Displays.unrotated(at),
                            QUILL.createBlockData(), quillPose(quill.direction, 0.4f), life + WALL_STICK_TICKS + 8));
                    display.setTeleportDuration(1);
                    quill.display = display;
                }
            }
            bristle(owner, 1.2);
            Vfx.ring(ctx(), chest, 0.7, 20, phase, Particle.DUST_COLOR_TRANSITION, QUILL_STREAK, false);
            world.spawnParticle(Particle.DUST, chest, Vfx.n(ctx(), 12), 0.35, 0.3, 0.35, 0, CHESTNUT);
            Fx.sound(chest, Sound.ENTITY_PUFFER_FISH_BLOW_UP, 1.2f, 1.1f + Fx.jitter(0.1f));
            Fx.sound(chest, Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(chest, Sound.ENCHANT_THORNS_HIT, 0.8f, 0.6f + Fx.jitter(0.05f));
            Loop.repeat(ctx(), owner, 1, 1, life, this, this::finish);
        }

        @Override
        public boolean test(long tick) {
            boolean flying = false;
            for (Quill quill : quills) {
                if (quill.done) {
                    continue;
                }
                advance(quill, tick);
                flying |= !quill.done;
            }
            if (tick % 2 == 0) {
                Fx.sound(chest, Sound.ENTITY_ARROW_SHOOT, 0.4f, 1.6f + Fx.jitter(0.2f));
            }
            return flying;
        }

        private void advance(Quill quill, long tick) {
            double step = Math.min(QUILL_SPEED, range - quill.traveled);
            if (step <= 1.0E-3) {
                fade(quill);
                return;
            }
            Vector direction = quill.direction;
            RayTraceResult block = world.rayTraceBlocks(quill.position, direction, step, FluidCollisionMode.NEVER,
                    true);
            double limit = block == null ? step : block.getHitPosition().distance(quill.position.toVector());
            LivingEntity hit = null;
            double nearest = limit;
            Location middle = quill.position.clone().add(direction.clone().multiply(step / 2));
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, step / 2 + 1.5)) {
                if (struck.contains(enemy.getUniqueId())) {
                    continue;
                }
                RayTraceResult box = enemy.getBoundingBox().expand(QUILL_RADIUS).rayTrace(quill.position.toVector(),
                        direction, limit);
                if (box == null) {
                    continue;
                }
                double distance = box.getHitPosition().distance(quill.position.toVector());
                if (distance <= nearest) {
                    nearest = distance;
                    hit = enemy;
                }
            }
            if (hit != null) {
                quill.position.add(direction.clone().multiply(nearest));
                strike(quill, hit);
                return;
            }
            if (block != null) {
                quill.position.add(direction.clone().multiply(Math.max(0, limit - 0.2)));
                stickInWall(quill, block);
                return;
            }
            Location before = quill.position.clone();
            quill.position.add(direction.clone().multiply(step));
            quill.traveled += step;
            BlockDisplay display = quill.display;
            if (display != null && display.isValid()) {
                Displays.moveTo(display, quill.position);
                if (tick == 0) {
                    Displays.animate(display, quillPose(direction, 1f), 1);
                }
            }
            Vfx.dot(before, Particle.DUST_COLOR_TRANSITION, QUILL_STREAK, false);
            if (display == null) {
                Vfx.line(ctx(), before, quill.position, 3, Particle.DUST, CHESTNUT, false);
            }
        }

        private void strike(Quill quill, LivingEntity enemy) {
            quill.done = true;
            struck.add(enemy.getUniqueId());
            int burst = tearQuills(enemy);
            double damage = params().num("volley_damage") + burst * Math.max(0, params().num("quill_burst"));
            boolean unseen = hidden(enemy);
            Combat.hit(ctx(), owner, enemy, damage);
            hits++;
            torn += burst;
            BlockDisplay display = quill.display;
            if (unseen) {
                // A radial quill found an enemy hidden from the hedgehog: no burst, no sound and no quill left
                // where it hit, nothing that would show where it is.
                if (display != null && display.isValid()) {
                    display.remove();
                }
                return;
            }
            Location body = Fx.body(enemy);
            Vfx.cloud(ctx(), body, Particle.CRIT, 6, 0.2, 0.2, null, false);
            body.getWorld().spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 5), 0.2, 0.2, 0.2, 0, CREAM);
            Fx.sound(body, Sound.ENTITY_ARROW_HIT, 1.0f, 1.1f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_PUFFER_FISH_STING, 0.6f, 1.0f + Fx.jitter(0.1f));
            if (display != null && display.isValid()) {
                Displays.moveTo(display, quill.position);
                Displays.shrinkAway(ctx(), owner, display, 3);
            }
        }

        private void stickInWall(Quill quill, RayTraceResult block) {
            quill.done = true;
            Block hit = block.getHitBlock();
            if (hit != null && !hit.getType().isAir()) {
                world.spawnParticle(Particle.BLOCK_CRUMBLE, quill.position, Vfx.n(ctx(), 4), 0.05, 0.05, 0.05, 0.02,
                        hit.getBlockData());
            }
            Fx.sound(quill.position, Sound.BLOCK_POINTED_DRIPSTONE_LAND, 0.6f, 1.5f + Fx.jitter(0.1f));
            BlockDisplay display = quill.display;
            if (display == null || !display.isValid()) {
                return;
            }
            Displays.moveTo(display, quill.position);
            // A quiver as it sticks, then it crumbles away.
            Displays.animate(display, quillPose(quill.direction.clone().add(new Vector(0, 0.15, 0)), 1f), 2);
            ctx().runLater(owner, 2, () -> Displays.animate(display, quillPose(quill.direction, 1f), 2));
            ctx().runLater(owner, WALL_STICK_TICKS, () -> {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 6);
                }
            });
        }

        private void fade(Quill quill) {
            quill.done = true;
            BlockDisplay display = quill.display;
            if (display != null && display.isValid()) {
                Displays.shrinkAway(ctx(), owner, display, 3);
            }
            Vfx.dot(quill.position, Particle.DUST, CREAM, false);
        }

        private void finish() {
            for (Quill quill : quills) {
                if (!quill.done) {
                    fade(quill);
                }
            }
            if (hits > 0) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">가시 발사</" + COLOR_TAG + "> <white>" + hits
                        + "명 적중</white>" + (torn > 0 ? " <" + CREAM_TAG + ">· 뽑힌 가시 " + torn + "개</" + CREAM_TAG
                        + ">" : "")));
            }
        }
    }

    /** A flying quill: the dripstone's tip (its +Y) along {@code direction}, centred on the display. */
    static Transformation quillPose(Vector direction, float grow) {
        Vector3f axis = new Vector3f((float) direction.getX(), (float) direction.getY(), (float) direction.getZ());
        if (axis.lengthSquared() < 1.0E-8f) {
            axis.set(0, 0, 1);
        }
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), axis.normalize());
        Vector3f size = new Vector3f(QUILL_WIDTH, QUILL_LENGTH, QUILL_WIDTH).mul(grow);
        Vector3f translation = rotation.transform(new Vector3f(size).mul(0.5f)).negate();
        return new Transformation(translation, rotation, size, new Quaternionf());
    }

    // ------------------------------------------------------------------ ultimate: spike ball

    @Override
    public ActivationResult onUltimate() {
        if (ball != null) {
            return ActivationResult.fail("이미 가시 공이 되어 있습니다");
        }
        Ball fresh = new Ball(player());
        ball = fresh;
        fresh.curl();
        return ActivationResult.success();
    }

    /** Per-enemy contact bookkeeping of the ball. */
    private static final class Contact {
        int hits;
        long next;
    }

    /**
     * The spike ball: the hedgehog shrinks and runs much faster (its own steering), bristling displays turn with the
     * distance it rolls, every enemy it touches is bowled over (a few times at most), and caltrops are strewn behind.
     */
    private final class Ball implements LongPredicate {

        private final Player owner;
        private final long duration;
        private final List<NamespacedKey> boosts = new ArrayList<>();
        private final BlockDisplay[] core = new BlockDisplay[CORE_ORIENTATIONS.length];
        private final BlockDisplay[] spikes = new BlockDisplay[BALL_SPIKES];
        private final Map<UUID, Contact> contacts = new HashMap<>();
        private final Quaternionf roll = new Quaternionf();
        private @Nullable NamespacedKey shrinkKey;
        private Location last;
        private @Nullable Location lastDrop;
        private long nextDrop;
        private int bowled;
        private int strewn;
        private boolean ended;

        Ball(Player owner) {
            this.owner = owner;
            this.duration = Math.max(1, params().ticks("ball_duration"));
            this.last = owner.getLocation();
            // The first caltrop falls once the ball has rolled a little way, not where it curled up.
            this.lastDrop = last.clone();
        }

        void curl() {
            shrinkKey = ctx().addModifier(owner, owner, Attribute.SCALE, -BALL_SHRINK, Operation.MULTIPLY_SCALAR_1, 0);
            double speed = Math.max(0, params().num("ball_speed")) / 100.0;
            if (speed > 0) {
                boosts.add(ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1,
                        0));
            }
            boosts.add(ctx().addModifier(owner, owner, Attribute.KNOCKBACK_RESISTANCE, 1.0, Operation.ADD_NUMBER, 0));
            Location center = center();
            long life = duration + 20;
            for (int i = 0; i < core.length; i++) {
                core[i] = keep(Displays.block(ctx(), owner, center, CORE[i].createBlockData(),
                        cubePose(0.1f, CORE_ORIENTATIONS[i]), life));
                core[i].setTeleportDuration(1);
                core[i].setBrightness(null);
            }
            for (int i = 0; i < spikes.length; i++) {
                spikes[i] = keep(Displays.block(ctx(), owner, center, QUILL.createBlockData(),
                        spikePose(SPIKE_DIRECTIONS[i], roll, 0.2f), life));
                spikes[i].setTeleportDuration(1);
                spikes[i].setBrightness(null);
                // The spikes would stab into the hedgehog's own view from inside the ball; the others see them.
                owner.hideEntity(ctx().plugin(), spikes[i]);
            }
            Location feet = owner.getLocation();
            bristle(owner, 1.4);
            Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 1.1, 16, 0, Particle.DUST_COLOR_TRANSITION, QUILL_STREAK, true);
            Vfx.cloud(ctx(), feet.clone().add(0, 0.3, 0), Particle.POOF, 6, 0.3, 0.02, null, true);
            Fx.sound(feet, Sound.ENTITY_ARMADILLO_ROLL, 1.2f, 0.9f);
            Fx.sound(feet, Sound.ENTITY_PUFFER_FISH_BLOW_UP, 1.0f, 0.8f);
            Fx.sound(feet, Sound.ENTITY_ARMADILLO_SCUTE_DROP, 1.0f, 0.7f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>가시 공!</b></" + COLOR_TAG + ">"));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::uncurl);
        }

        private Location center() {
            return Displays.unrotated(owner.getLocation().add(0, owner.getHeight() * 0.5, 0));
        }

        @Override
        public boolean test(long tick) {
            if (ended || ball != this) {
                return false;
            }
            Location feet = owner.getLocation();
            if (!feet.getWorld().equals(last.getWorld())) {
                return false;
            }
            Vector moved = feet.toVector().subtract(last.toVector()).setY(0);
            double distance = moved.length();
            if (distance > 1.0E-3 && distance < 4) {
                // Rolling along the ground: turn about the horizontal axis across the motion.
                Vector3f axis = new Vector3f((float) moved.getZ(), 0, (float) -moved.getX()).normalize();
                float angle = (float) (distance / (CORE_SIZE * 0.5));
                roll.premul(new Quaternionf().rotateAxis(angle, axis)).normalize();
            }
            last = feet;
            pose();
            bowl(tick);
            strew(feet, tick);
            trail(feet, distance, tick);
            if (tick % 5 == 0) {
                long left = Math.max(0, duration - tick - 1);
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">● 가시 공</" + COLOR_TAG + "> <white>"
                        + Fx.seconds(left) + "초</white> <dark_gray>|</dark_gray> <gray>튕겨 낸 적</gray> <white>"
                        + bowled + "</white>"));
            }
            return true;
        }

        private void pose() {
            Location center = center();
            for (int i = 0; i < core.length; i++) {
                BlockDisplay cube = core[i];
                if (cube != null && cube.isValid()) {
                    Displays.moveTo(cube, center);
                    Displays.animate(cube, cubePose(CORE_SIZE, new Quaternionf(roll).mul(CORE_ORIENTATIONS[i])), 1);
                }
            }
            for (int i = 0; i < spikes.length; i++) {
                BlockDisplay spike = spikes[i];
                if (spike != null && spike.isValid()) {
                    Displays.moveTo(spike, center);
                    Displays.animate(spike, spikePose(SPIKE_DIRECTIONS[i], roll, 1f), 1);
                }
            }
        }

        /** Every enemy the ball touches is hit and bowled aside (once per interval, a few times at most). */
        private void bowl(long tick) {
            Location center = center();
            long now = ctx().currentTick();
            long interval = Math.max(1, params().ticks("ball_hit_interval"));
            int max = Math.max(0, params().integer("ball_max_hits"));
            double damage = params().num("ball_damage");
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, BALL_REACH)) {
                Contact contact = contacts.computeIfAbsent(enemy.getUniqueId(), id -> new Contact());
                if (contact.hits >= max || now < contact.next) {
                    continue;
                }
                contact.hits++;
                contact.next = now + interval;
                Combat.hit(ctx(), owner, enemy, damage);
                bowled++;
                if (!hidden(enemy)) {
                    Location body = Fx.body(enemy);
                    Vfx.burst(ctx(), body, PALETTE, 0.5, Particle.CRIT, false);
                    Fx.sound(body, Sound.ENCHANT_THORNS_HIT, 1.0f, 0.8f + Fx.jitter(0.1f));
                    Fx.sound(body, Sound.ENTITY_ARMADILLO_HURT_REDUCED, 0.8f, 1.2f + Fx.jitter(0.1f));
                }
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(owner.getLocation().toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    away = Motion.horizontalFacing(owner.getLocation());
                }
                Motion.push(enemy, away.multiply(BALL_PUSH).setY(BALL_LIFT), MAX_SPEED);
                enemy.setFallDistance(0);
            }
        }

        /** Every so often (if it rolled far enough) a caltrop is left on the ground behind the ball. */
        private void strew(Location feet, long tick) {
            long now = ctx().currentTick();
            if (now < nextDrop) {
                return;
            }
            Location previous = lastDrop;
            if (previous != null && previous.getWorld().equals(feet.getWorld())
                    && horizontalDistance(previous, feet) < DROP_SPACING) {
                return;
            }
            Location ground = groundUnder(feet.clone().add(0, 0.3, 0), 2.5);
            if (ground == null) {
                return;
            }
            nextDrop = now + Math.max(1, params().ticks("spike_interval"));
            lastDrop = feet.clone();
            strewn++;
            new Caltrop(owner, ground).place();
        }

        private void trail(Location feet, double distance, long tick) {
            Location ground = feet.clone().add(0, 0.1, 0);
            if (distance > 0.05) {
                if (tick % 2 == 0) {
                    BlockData floor = floorAt(feet);
                    ground.getWorld().spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 3), 0.2, 0.03, 0.2, 0.08,
                            floor);
                }
                Vfx.dot(ground.clone().add(0, 0.2, 0), Particle.DUST, CHESTNUT, false);
                if (tick % 6 == 0) {
                    Fx.sound(feet, Sound.ENTITY_ARMADILLO_ROLL, 0.5f, 1.2f + Fx.jitter(0.1f));
                }
            }
            if (tick % 3 == 0) {
                Location center = center();
                Vfx.ring(ctx(), center, 0.75, 8, tick * 0.4, Particle.DUST_COLOR_TRANSITION, QUILL_STREAK, false);
            }
        }

        /** The ball unrolls: spikes fly off, the hedgehog grows back and loses its speed. */
        void uncurl() {
            if (ended) {
                return;
            }
            ended = true;
            if (ball == this) {
                ball = null;
            }
            for (NamespacedKey key : boosts) {
                ctx().removeModifier(owner, key);
            }
            boosts.clear();
            growBack(0);
            Location center = center();
            for (BlockDisplay cube : core) {
                if (cube != null && cube.isValid()) {
                    Displays.shrinkAway(ctx(), owner, cube, 5);
                }
            }
            for (int i = 0; i < spikes.length; i++) {
                BlockDisplay spike = spikes[i];
                if (spike == null || !spike.isValid()) {
                    continue;
                }
                Vector3f out = new Quaternionf(roll).transform(new Vector3f(SPIKE_DIRECTIONS[i]));
                spike.setTeleportDuration(5);
                Displays.moveTo(spike, center.clone().add(out.x() * 1.2, out.y() * 0.6 + 0.4, out.z() * 1.2));
                Displays.shrinkAway(ctx(), owner, spike, 6);
            }
            bristle(owner, 1.0);
            Vfx.cloud(ctx(), center, Particle.POOF, 6, 0.3, 0.02, null, false);
            Fx.sound(center, Sound.ENTITY_ARMADILLO_UNROLL_FINISH, 1.0f, 1.0f);
            Fx.sound(center, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, 0.8f, 1.0f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">가시 공 끝</" + COLOR_TAG + "> <gray>튕겨 낸 적 " + bowled
                    + " · 흩뿌린 가시 " + strewn + "</gray>"));
        }

        /** Restores the hedgehog's size as soon as a full-size body fits where it stands (or after a timeout). */
        private void growBack(long waited) {
            NamespacedKey key = shrinkKey;
            if (key == null) {
                return;
            }
            Body body = Body.standing(owner);
            double full = body.height() / (1 - BALL_SHRINK);
            double width = body.width() / (1 - BALL_SHRINK);
            if (waited < GROW_BACK_TIMEOUT && !Spots.isFree(owner.getWorld(), owner.getLocation(), width, full)) {
                ctx().runLater(owner, 2, () -> growBack(waited + 2));
                return;
            }
            shrinkKey = null;
            ctx().removeModifier(owner, key);
        }
    }

    /** One of the ball's spikes: from just outside the core along its (rolled) direction. */
    static Transformation spikePose(Vector3f direction, Quaternionf roll, float grow) {
        Vector3f axis = new Quaternionf(roll).transform(new Vector3f(direction));
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), axis);
        Vector3f size = new Vector3f(BALL_SPIKE_WIDTH, BALL_SPIKE_LENGTH, BALL_SPIKE_WIDTH).mul(grow);
        Vector3f base = new Vector3f(axis).mul(BALL_SPIKE_BASE * grow);
        Vector3f translation = base.sub(rotation.transform(new Vector3f(size.x() / 2, 0, size.z() / 2)));
        return new Transformation(translation, rotation, size, new Quaternionf());
    }

    /**
     * A caltrop on the ground: a little spike that snaps on the first enemy stepping on it, hurting and slowing it.
     * Shown with a display while the budget allows, otherwise with a glint of particles.
     */
    private final class Caltrop implements LongPredicate {

        private final Player owner;
        private final Location at;
        private @Nullable BlockDisplay display;
        private boolean sprung;

        Caltrop(Player owner, Location at) {
            this.owner = owner;
            this.at = Displays.unrotated(at);
        }

        void place() {
            long life = Math.max(1, params().ticks("spike_duration"));
            if (room(1)) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                float yaw = random.nextFloat() * 360;
                float tilt = random.nextFloat() * 20 - 10;
                BlockDisplay spike = keep(Displays.block(ctx(), owner, at, QUILL.createBlockData(),
                        Displays.transform(0, 0.05f, 0, 0.3f, 0.1f, 0.3f, yaw, tilt, 0), life + 10));
                spike.setBrightness(null);
                Displays.animateNextTick(ctx(), owner, spike, Displays.transform(0, 0.22f, 0, 0.32f, 0.45f, 0.32f, yaw,
                        tilt, 0), 3);
                display = spike;
            }
            at.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, 0.15, 0), Vfx.n(ctx(), 3), 0.1, 0.05, 0.1, 0,
                    CREAM);
            Fx.sound(at, Sound.BLOCK_POINTED_DRIPSTONE_LAND, 0.5f, 1.8f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), owner, 2, CALTROP_PERIOD, Math.max(1, life / CALTROP_PERIOD), this, this::wither);
        }

        @Override
        public boolean test(long tick) {
            if (sprung) {
                return false;
            }
            if (display == null && tick % 5 == 0) {
                Vfx.dot(at.clone().add(0, 0.2, 0), Particle.DUST, CREAM, false);
                Vfx.dot(at.clone().add(0, 0.1, 0), Particle.DUST, CHESTNUT, false);
            }
            for (LivingEntity enemy : ctx().enemiesNear(owner, at.clone().add(0, 0.3, 0), CALTROP_RADIUS + 0.6)) {
                Location feet = enemy.getLocation();
                double dy = feet.getY() - at.getY();
                if (dy < -0.3 || dy > 0.8 || horizontalDistance(feet, at) > CALTROP_RADIUS + enemy.getWidth() / 2) {
                    continue;
                }
                spring(enemy);
                return false;
            }
            return true;
        }

        private void spring(LivingEntity enemy) {
            sprung = true;
            Combat.hit(ctx(), owner, enemy, params().num("spike_damage"));
            long slow = params().ticks("spike_slow");
            double amount = Math.clamp(params().num("spike_slow_amount") / 100.0, 0.0, 1.0);
            if (slow > 0 && amount > 0 && Combat.isTargetable(ctx(), owner, enemy)) {
                NamespacedKey previous = caltropSlows.remove(enemy.getUniqueId());
                if (previous != null) {
                    ctx().removeModifier(enemy, previous);
                }
                caltropSlows.put(enemy.getUniqueId(), ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED,
                        -amount, Operation.MULTIPLY_SCALAR_1, slow));
            }
            if (!hidden(enemy)) {
                // (An enemy hidden from the hedgehog springs it silently: the snap would show where it walks.)
                Location point = at.clone().add(0, 0.2, 0);
                at.getWorld().spawnParticle(Particle.BLOCK, point, Vfx.n(ctx(), 8), 0.15, 0.1, 0.15, 0.05,
                        QUILL.createBlockData());
                Vfx.cloud(ctx(), Fx.body(enemy), Particle.CRIT, 6, 0.2, 0.15, null, false);
                Fx.sound(point, Sound.BLOCK_POINTED_DRIPSTONE_BREAK, 1.0f, 1.4f + Fx.jitter(0.1f));
                Fx.sound(point, Sound.ENTITY_PUFFER_FISH_STING, 0.8f, 1.2f + Fx.jitter(0.1f));
            }
            BlockDisplay spike = display;
            if (spike != null && spike.isValid()) {
                Displays.shrinkAway(ctx(), owner, spike, 3);
            }
        }

        /** The caltrop's time is up (or it snapped): it crumbles away. */
        private void wither() {
            BlockDisplay spike = display;
            if (spike != null && spike.isValid()) {
                Displays.shrinkAway(ctx(), owner, spike, 6);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A cube of edge {@code size} centered on the display's origin, rotated around its center. */
    private static Transformation cubePose(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    /** {@code degrees} wrapped into -180..180. */
    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360;
        if (wrapped >= 180) {
            wrapped -= 360;
        } else if (wrapped < -180) {
            wrapped += 360;
        }
        return wrapped;
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static @Nullable Location groundUnder(Location point, double maxDrop) {
        Location from = point.clone().add(0, 0.05, 0);
        RayTraceResult ground = point.getWorld().rayTraceBlocks(from, new Vector(0, -1, 0), maxDrop + 0.05,
                FluidCollisionMode.NEVER, true);
        if (ground == null) {
            return null;
        }
        Location at = point.clone();
        at.setY(ground.getHitPosition().getY());
        return at;
    }

    private static BlockData floorAt(Location at) {
        Block below = at.clone().subtract(0, 0.2, 0).getBlock();
        return below.getType().isAir() ? Material.DIRT.createBlockData() : below.getBlockData();
    }
}
