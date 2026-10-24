package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 카산드라 — the prophetess of Troy whose prophecies always come true and whom nobody believes. She does not strike
 * where an enemy is, but where it <em>will be</em>: the active reads the aimed enemy's last steps, foretells where it
 * stands a moment later (on walkable ground, never through a wall) and drops the spear of fate there; standing still
 * means it falls at your feet, running straight means it falls in your path, so the only escape is to change course.
 * An enemy that cheats a prophecy is fated: the next one falls on it sooner and wider. The ultimate foretells the fall
 * of Troy for every enemy around in three waves, a second apart, each at the spot every enemy is about to reach.
 *
 * <p>Unlike the 뇌신, 천문학자 or 빛의 사도, who strike the aimed or the present spot, the prophecy aims at the future, so
 * the way to dodge it is the reverse of theirs: keep running and it hits, change your mind and it misses. Prediction
 * reads where the server saw each enemy on the last ticks (a player's server-side velocity is stale, never read).
 *
 * <p>Look: prophecy violet, bone white and Trojan bronze. A seal of two turning violet stars with an open eye in the
 * middle burns on the ground where the prophecy will come true, outlined so it shows through walls, a bronze ring
 * closing in on it as the moment nears and violet motes rising; a bronze spear with a bone-white head falls out of
 * the sky onto it, flashes and stays stuck in the ground while the seal fades. A fated enemy carries a small eye
 * circling over its head. The ultimate opens a giant eye over Cassandra that blinks with every wave while a bell
 * tolls for Troy.
 */
public final class CassandraAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("cassandra", "카산드라", Material.TINTED_GLASS)
            .color(TextColor.color(0x7B3FC4))
            .difficulty(3)
            .passive("바뀌지 않는 운명",
                    "예언(액티브)을 피한 대상에게는 {fate_duration}초간 '운명'이 붙어 머리 위에 눈이 맴돕니다. 그 적에게 "
                            + "내리는 다음 예언(액티브)은 {fate_haste}% 더 빨리 떨어지고 범위가 {fate_radius}블록 넓어집니다. 예언이 "
                            + "적을 맞히면 예언 쿨타임이 {hit_refund}초 줄어듭니다.")
            .active("예언",
                    "조준한 적({prophecy_range}블록 이내)이 {prophecy_delay}초 뒤에 서 있을 곳을 예언합니다. 최근 움직임대로 "
                            + "나아간 곳(최대 {prophecy_lead}블록 앞, 벽 너머나 낭떠러지 밖은 제외)에 모두에게 보이는 "
                            + "파멸의 인장이 새겨지고, {prophecy_delay}초 뒤 하늘에서 운명의 창이 떨어져 반경 "
                            + "{prophecy_radius}블록 안의 적에게 {prophecy_damage} 피해를 주고 {prophecy_slow}초간 이동 "
                            + "속도를 {prophecy_slow_amount}% 늦춥니다. 가만히 있던 적에게는 발밑에, 달리던 적에게는 가는 "
                            + "길에 떨어지니 피하려면 움직임을 바꿔야 합니다.",
                    9)
            .ultimate("트로이의 멸망",
                    "{ult_windup}초간 머리 위에 거대한 눈을 뜬 뒤 반경 {ult_radius}블록 안의 적 모두(최대 "
                            + "{ult_targets}명)에게 멸망을 예언합니다. {ult_interval}초 간격으로 {ult_waves}번, 매번 적마다 "
                            + "{ult_delay}초 뒤에 서 있을 곳에 인장이 새겨지고 창이 떨어져 반경 {ult_seal_radius}블록 안의 "
                            + "적에게 {ult_damage} 피해를 줍니다(창이 겹쳐도 매번 적마다 한 번만). 곧장 달려도 가만히 "
                            + "있어도 맞으니 예측할 수 없게 움직여야 합니다.")
            .param("fate_duration", 6)
            .param("fate_haste", 30)
            .param("fate_radius", 0.6)
            .param("hit_refund", 2)
            .param("prophecy_range", 26)
            .param("prophecy_delay", 1.2)
            .param("prophecy_lead", 10)
            .param("prophecy_radius", 1.8)
            .param("prophecy_damage", 6)
            .param("prophecy_slow", 1)
            .param("prophecy_slow_amount", 30)
            .param("ult_windup", 0.5)
            .param("ult_radius", 20)
            .param("ult_targets", 6)
            .param("ult_waves", 3)
            .param("ult_interval", 1)
            .param("ult_delay", 1)
            .param("ult_seal_radius", 1.6)
            .param("ult_damage", 3.5)
            .build();

    private static final String COLOR_TAG = "#7B3FC4";
    private static final String BONE_TAG = "#EFE9DD";
    private static final String BRONZE_TAG = "#A8783A";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: violet, bone and bronze
    /** Bone white, prophecy violet, the deep purple of doom. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xEFE9DD, 0x7B3FC4, 0x2A1048);
    /** Bronze of Troy: pale gold, bronze, burnt bronze. */
    private static final Vfx.Palette BRONZE = Vfx.Palette.of(0xF2D7A0, 0xA8783A, 0x4A2E12);
    private static final Color VIOLET = Color.fromRGB(0x7B3FC4);
    private static final Color BONE = Color.fromRGB(0xEFE9DD);
    private static final Particle.DustOptions VIOLET_DUST = PALETTE.dust(1.0f);
    private static final Particle.DustOptions VIOLET_SMALL = PALETTE.dust(0.6f);
    private static final Particle.DustOptions BRONZE_DUST = BRONZE.dust(0.9f);
    private static final Particle.DustOptions BONE_DUST = new Particle.DustOptions(BONE, 0.7f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.1f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.2f);
    private static final Particle.DustTransition BRONZE_FADE = BRONZE.fade(1.0f);

    /** Block and item types of the visuals (block data and items are created on use: they need a running server). */
    private static final Material STAR_BLOCK = Material.PURPLE_STAINED_GLASS;
    private static final Material SHAFT_BLOCK = Material.CUT_COPPER;
    private static final Material HEAD_BLOCK = Material.BONE_BLOCK;
    private static final Material EYE_ITEM = Material.ENDER_EYE;

    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;

    // prediction
    /** Ticks of history kept per enemy, and the ticks the velocity is averaged over. */
    private static final int HISTORY = 8;
    private static final int VELOCITY_TICKS = 5;
    /** Enemies farther than this are not watched (beyond every range of the ability). */
    private static final double WATCH_RANGE = 40;
    /** Below this lead (blocks) an enemy counts as standing still: the spear falls at its feet. */
    private static final double STILL_LEAD = 0.35;
    private static final double WALK_STEP = 0.25;
    /** The prophecy follows the enemy up a step of at most this height (a jump) and down at most this drop. */
    private static final double STEP_UP = 1.1;
    private static final double MAX_DROP = 4;
    private static final double GROUND_PROBE = 8;

    // the seal and the spear
    private static final long SPEAR_FALL = 6;
    private static final double SPEAR_HEIGHT = 10;
    /** The spear hits whatever stands in its column up to this height over the seal. */
    private static final double SPEAR_REACH_UP = 2.6;
    private static final long SPEAR_STAY = 14;
    /**
     * The ultimate's spears stay a shorter while: they must be gone before the next wave's spears fall (a wave's
     * spears fall 14 ticks after its seals appear, on the tick the previous wave struck), or the display budget runs
     * out with three enemies or more.
     */
    private static final long ULT_SPEAR_STAY = 6;
    /** Above this many doomed enemies the ultimate's seals have one star and its spears no crossguard (the budget). */
    private static final int FULL_LOOK_TARGETS = 3;
    private static final float STAR_SCALE = 1.35f;
    private static final float STAR_SPIN = 2.5f;

    // fate
    private static final double FATE_ORBIT = 0.45;
    private static final double FATE_ABOVE = 0.55;
    private static final int MAX_FATE_MARKS = 6;

    /** Where every watched enemy was on the last ticks. */
    private final Map<UUID, Track> tracks = new HashMap<>();
    /** Fated enemies, by entity id. */
    private final Map<UUID, Fate> fates = new HashMap<>();
    private final List<Display> displays = new ArrayList<>();

    /** One sampled position. */
    private record Sample(long tick, double x, double y, double z) {
    }

    /** The last positions of one enemy, newest last. */
    private static final class Track {
        final ArrayDeque<Sample> samples = new ArrayDeque<>();
        @Nullable World world;
        long seen;
    }

    private static final class Fate {
        final LivingEntity target;
        long until;
        @Nullable ItemDisplay eye;

        Fate(LivingEntity target) {
            this.target = target;
        }
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        for (Fate fate : fates.values()) {
            removeEye(fate);
        }
        fates.clear();
        tracks.clear();
        displays.clear();
    }

    // ------------------------------------------------------------------ the watch: every enemy's last steps

    @Override
    public void onTick(long tick) {
        Player owner = player();
        watch(owner);
        updateFates(owner, tick);
        if (tick % 12 == 0) {
            // The prophetess's eyes glow faintly violet: she is always seeing a moment ahead.
            Vfx.dot(jittered(owner.getEyeLocation().add(0, 0.15, 0), 0.25), Particle.DUST, VIOLET_SMALL, false);
        }
    }

    /** Samples where every enemy around is this tick (once per tick, whoever asks). */
    private void watch(Player owner) {
        long now = ctx().currentTick();
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), WATCH_RANGE)) {
            record(enemy, now);
        }
        if (now % 40 == 0) {
            tracks.values().removeIf(track -> now - track.seen > 40);
        }
    }

    private void record(LivingEntity entity, long now) {
        Track track = tracks.computeIfAbsent(entity.getUniqueId(), id -> new Track());
        Location at = entity.getLocation();
        if (track.world != null && !track.world.equals(at.getWorld())) {
            track.samples.clear();
        }
        track.world = at.getWorld();
        track.seen = now;
        Sample last = track.samples.peekLast();
        if (last != null && last.tick() == now) {
            return;
        }
        if (last != null && now - last.tick() > 2) {
            // A gap in the watch: the old steps say nothing about the current run.
            track.samples.clear();
        }
        track.samples.addLast(new Sample(now, at.getX(), at.getY(), at.getZ()));
        while (track.samples.size() > HISTORY) {
            track.samples.removeFirst();
        }
    }

    /**
     * The enemy's horizontal speed (blocks per tick) over its last few server-side positions. A player's own velocity
     * is never read: on a server it is only what was last sent to it. A mob without a watched past uses its velocity,
     * which the server does simulate.
     */
    private Vector velocityOf(LivingEntity target) {
        Track track = tracks.get(target.getUniqueId());
        if (track != null && track.samples.size() >= 2) {
            Sample newest = track.samples.peekLast();
            Sample oldest = null;
            for (Iterator<Sample> it = track.samples.descendingIterator(); it.hasNext(); ) {
                Sample sample = it.next();
                if (newest.tick() - sample.tick() > VELOCITY_TICKS) {
                    break;
                }
                oldest = sample;
            }
            if (oldest != null && newest.tick() > oldest.tick()) {
                double dt = newest.tick() - oldest.tick();
                return new Vector((newest.x() - oldest.x()) / dt, 0, (newest.z() - oldest.z()) / dt);
            }
        }
        if (!(target instanceof Player)) {
            Vector velocity = target.getVelocity();
            return Double.isFinite(velocity.getX()) && Double.isFinite(velocity.getZ())
                    ? new Vector(velocity.getX(), 0, velocity.getZ()) : new Vector();
        }
        return new Vector();
    }

    /**
     * Where {@code target} will stand {@code ticks} from now if it keeps going as it went: its feet carried along its
     * current speed (at most {@code maxLead} blocks), following the ground up a jump-high step and down a short drop,
     * and stopped short of walls, cliffs, hazards and the arena's edge. The result is on the ground.
     */
    private Location predict(LivingEntity target, long ticks, double maxLead) {
        World world = target.getWorld();
        Location feet = target.getLocation();
        Location start = feet.clone();
        Spots.snapToGround(world, start, GROUND_PROBE);
        Vector lead = velocityOf(target).multiply(Math.max(0, ticks));
        double length = lead.length();
        if (!(length > STILL_LEAD)) {
            return unrotated(start);
        }
        if (length > maxLead) {
            lead.multiply(maxLead / length);
            length = maxLead;
        }
        Body body = Body.standing(target);
        int steps = (int) Math.ceil(length / WALK_STEP);
        Location last = start.clone();
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            Location next = new Location(world, start.getX() + lead.getX() * t, last.getY(),
                    start.getZ() + lead.getZ() * t);
            Location ground = groundUnder(world, next, last.getY());
            if (ground == null || !Spots.fits(world, ground, body.width(), body.height())
                    || !Spots.hasGroundBelow(world, ground) || !ctx().isInsideArena(ground)) {
                break;
            }
            last = ground;
        }
        return unrotated(last);
    }

    /**
     * The walkable ground at the column of {@code at}, coming from a ground at {@code fromY}: a ray down from one
     * jump above it (started above the surface, never inside a block) finds the floor within the drop. Null for a
     * wall (the ray starts inside a block) or a drop too deep.
     */
    private static @Nullable Location groundUnder(World world, Location at, double fromY) {
        Location top = at.clone();
        top.setY(fromY + STEP_UP);
        RayTraceResult hit = world.rayTraceBlocks(top, new Vector(0, -1, 0), STEP_UP + MAX_DROP,
                FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitPosition().getY() > top.getY() - 1.0E-4) {
            return null;
        }
        Location ground = top.clone();
        ground.setY(hit.getHitPosition().getY());
        return ground;
    }

    // ------------------------------------------------------------------ passive: fate

    private boolean isFated(LivingEntity target) {
        Fate fate = fates.get(target.getUniqueId());
        return fate != null && fate.until > ctx().currentTick();
    }

    /** {@code target} cheated a prophecy: it is fated, and an eye of fate circles over its head. */
    private void markFate(Player owner, LivingEntity target) {
        long ticks = params().ticks("fate_duration");
        if (ticks <= 0 || !Combat.isTargetable(ctx(), owner, target)) {
            return;
        }
        Fate fate = fates.computeIfAbsent(target.getUniqueId(), id -> new Fate(target));
        fate.until = ctx().currentTick() + ticks;
        if (isHidden(owner, target)) {
            return;
        }
        Location head = fateSpot(target, 0);
        Vfx.ring(ctx(), target.getLocation().add(0, 0.1, 0), 0.8, 14, 0, Particle.DUST_COLOR_TRANSITION, FADE, true);
        Vfx.cloud(ctx(), head, Particle.REVERSE_PORTAL, 10, 0.25, 0.04, null, false);
        Fx.sound(head, Sound.ENTITY_ENDER_EYE_DEATH, 0.8f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(head, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 0.6f);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">◉ 운명</" + COLOR_TAG + "> <white><target></white><gray>이(가) "
                        + "예언을 피했습니다 — 다음 예언은 피할 수 없습니다</gray>",
                Placeholder.component("target", target.name())));
        if (target instanceof Player fated) {
            fated.sendActionBar(Text.mm("<" + COLOR_TAG + ">◉ 운명이 당신을 쫓습니다</" + COLOR_TAG + ">"));
        }
    }

    /** Keeps the eyes of fate circling over the fated and takes them away when fate runs out. */
    private void updateFates(Player owner, long tick) {
        if (fates.isEmpty()) {
            return;
        }
        long now = ctx().currentTick();
        int shown = 0;
        Iterator<Fate> entries = fates.values().iterator();
        while (entries.hasNext()) {
            Fate fate = entries.next();
            LivingEntity target = fate.target;
            if (fate.until <= now || !Combat.isTargetable(ctx(), owner, target)
                    || !target.getWorld().equals(owner.getWorld())) {
                if (fate.eye != null && fate.eye.isValid()) {
                    Vfx.cloud(ctx(), fate.eye.getLocation(), Particle.REVERSE_PORTAL, 6, 0.1, 0.02, null, false);
                }
                removeEye(fate);
                entries.remove();
                continue;
            }
            if (isHidden(owner, target) || shown >= MAX_FATE_MARKS) {
                // A hidden enemy's eye would give it away.
                removeEye(fate);
                continue;
            }
            shown++;
            Location spot = fateSpot(target, tick);
            if (fate.eye == null || !fate.eye.isValid()) {
                if (!reserve(1)) {
                    continue;
                }
                fate.eye = Displays.item(ctx(), owner, spot, new ItemStack(EYE_ITEM),
                        Displays.centered(0, 0, 0, 0.32f, 0.32f, 0.32f, 0, 0, 0), 0);
                fate.eye.setBillboard(Display.Billboard.CENTER);
                fate.eye.setTeleportDuration(1);
                displays.add(fate.eye);
                continue;
            }
            Displays.moveTo(fate.eye, spot);
            if (tick % 3 == 0) {
                Vfx.dot(spot, Particle.DUST, VIOLET_SMALL, false);
            }
        }
    }

    private static Location fateSpot(LivingEntity target, long tick) {
        double angle = tick * 0.22;
        return target.getLocation().add(Math.cos(angle) * FATE_ORBIT, target.getHeight() + FATE_ABOVE,
                Math.sin(angle) * FATE_ORBIT);
    }

    private static void removeEye(Fate fate) {
        if (fate.eye != null && fate.eye.isValid()) {
            fate.eye.remove();
        }
        fate.eye = null;
    }

    // ------------------------------------------------------------------ active: prophecy

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        LivingEntity target = ctx().findTarget(owner, params().num("prophecy_range"));
        if (target == null || isHidden(owner, target)) {
            return ActivationResult.fail("예언할 적을 조준하세요");
        }
        watch(owner);
        boolean fated = isFated(target);
        double haste = fated ? Math.clamp(params().num("fate_haste") / 100.0, 0.0, 0.9) : 0.0;
        long delay = Math.max(2, Math.round(params().ticks("prophecy_delay") * (1 - haste)));
        double wider = fated ? Math.max(0, params().num("fate_radius")) : 0;
        double radius = Math.max(0.3, params().num("prophecy_radius") + wider);
        Location center = predict(target, delay, Math.max(0, params().num("prophecy_lead")));
        Prophecy prophecy = new Prophecy(owner, target, center, radius, delay, params().num("prophecy_damage"), true,
                fated, null, false);
        if (fated) {
            // The fate is spent on this prophecy.
            Fate fate = fates.remove(target.getUniqueId());
            if (fate != null) {
                removeEye(fate);
            }
        }
        castFlourish(owner, center, fated);
        prophecy.start();
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">◉ 예언</" + COLOR_TAG + "> <white><target></white><gray> — "
                        + Fx.seconds(delay) + "초 뒤</gray>" + (fated ? " <" + BONE_TAG + ">운명!</" + BONE_TAG + ">" : ""),
                Placeholder.component("target", target.name())));
        return ActivationResult.success();
    }

    /** Cassandra sees it: violet light gathers in her eyes and runs to the seal, and she whispers. */
    private void castFlourish(Player owner, Location center, boolean fated) {
        Location eye = owner.getEyeLocation();
        Vfx.dot(eye, Particle.FLASH, VIOLET, false);
        Vfx.cloud(ctx(), eye, Particle.ENCHANT, 14, 0.3, 0.6, null, false);
        Location to = center.clone().add(0, 0.2, 0);
        for (int i = 0; i < Vfx.n(ctx(), 6); i++) {
            Vfx.trail(jittered(eye, 0.3), to, i % 2 == 0 ? VIOLET : BONE, 8 + i, true);
        }
        Fx.sound(eye, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.9f, 1.3f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.7f + Fx.jitter(0.1f));
        if (fated) {
            Fx.sound(eye, Sound.ENTITY_ENDER_EYE_LAUNCH, 1.0f, 0.6f);
        }
    }

    /**
     * One prophecy: a seal on the ground where it will come true, then the spear of fate falling onto it. The active's
     * prophecy also slows, refunds its cooldown on a hit and fates the aimed enemy when it misses; the ultimate's
     * waves only hurt, and the prophecies of one wave share who they struck, so overlapping spears hit an enemy once.
     */
    private final class Prophecy implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final Location center;
        private final double radius;
        private final long delay;
        private final double damage;
        private final boolean active;
        private final boolean fated;
        /** Who the prophecies of this one's wave struck already (the ultimate), or null (the active). */
        private final @Nullable Set<UUID> wave;
        /** One star and a spear without its crossguard: the ultimate on many enemies (the display budget). */
        private final boolean lite;
        private final World world;
        private final List<Display> seal = new ArrayList<>();
        private final List<BlockDisplay> spear = new ArrayList<>();
        private float spin;
        private boolean done;

        Prophecy(Player owner, LivingEntity target, Location center, double radius, long delay, double damage,
                 boolean active, boolean fated, @Nullable Set<UUID> wave, boolean lite) {
            this.owner = owner;
            this.target = target;
            this.center = center;
            this.radius = radius;
            this.delay = Math.max(2, delay);
            this.damage = damage;
            this.active = active;
            this.fated = fated;
            this.wave = wave;
            this.lite = lite;
            this.world = center.getWorld();
            this.spin = ThreadLocalRandom.current().nextFloat() * 90f;
        }

        void start() {
            drawSeal();
            Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.9f, 1.4f + Fx.jitter(0.1f));
            Fx.sound(center, Sound.BLOCK_BEACON_AMBIENT, 1.0f, 1.8f);
            Loop.repeat(ctx(), owner, 1, 1, delay, this, this::end);
        }

        /** The seal: two violet stars and an open eye, outlined so the doomed see it through anything. */
        private void drawSeal() {
            Location at = center.clone().add(0, 0.02, 0);
            float side = (float) (radius * STAR_SCALE);
            long life = delay + 12;
            int wanted = lite ? 1 : 2;
            int stars = reserve(wanted) ? wanted : reserve(1) ? 1 : 0;
            for (int i = 0; i < stars; i++) {
                BlockDisplay star = Displays.block(ctx(), owner, at, STAR_BLOCK.createBlockData(),
                        starPose(0.05f, spin + i * 45f), life);
                star.setViewRange(2.0f);
                Displays.outline(star, VIOLET);
                seal.add(star);
                displays.add(star);
                Displays.animateNextTick(ctx(), owner, star, starPose(side, spin + i * 45f + 20f), 5);
            }
            if (active && reserve(1)) {
                ItemDisplay eye = Displays.item(ctx(), owner, at.clone().add(0, 0.04, 0), new ItemStack(EYE_ITEM),
                        eyePose(0.05f, spin), life);
                eye.setViewRange(2.0f);
                seal.add(eye);
                displays.add(eye);
                Displays.animateNextTick(ctx(), owner, eye, eyePose((float) Math.min(1.1, radius * 0.6), spin), 6);
            }
            Vfx.ring(ctx(), at.clone().add(0, 0.05, 0), radius, (int) Math.round(radius * 10), spin,
                    Particle.DUST_COLOR_TRANSITION, GLOW, true);
            if (fated) {
                // Fate is certain: a second, bone-white ring seals it.
                Vfx.ring(ctx(), at.clone().add(0, 0.1, 0), radius + 0.15, (int) Math.round(radius * 10), -spin,
                        Particle.DUST, BONE_DUST, true);
            }
            world.spawnParticle(Particle.REVERSE_PORTAL, at, Vfx.n(ctx(), 10), radius * 0.4, 0.05, radius * 0.4, 0.02,
                    null, true);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            long left = delay - tick - 1;
            double progress = (double) (tick + 1) / delay;
            spin += STAR_SPIN;
            // The ultimate draws many seals at once: their rings are a little sparser.
            long ringPeriod = active ? 2 : 3;
            if (tick % ringPeriod == 0) {
                // A bronze ring closes in on the seal as the moment nears.
                double ring = radius * (1.0 + 0.8 * (1 - progress));
                int points = (int) Math.max(8, Math.round(ring * (active ? 8 : 5)));
                Vfx.ring(ctx(), center.clone().add(0, 0.08, 0), ring, points, tick * 0.2, Particle.DUST, BRONZE_DUST,
                        true);
            }
            if (tick % 2 == 0) {
                for (int i = 0; i < seal.size(); i++) {
                    Display part = seal.get(i);
                    if (part instanceof BlockDisplay star && star.isValid()) {
                        Displays.animate(star, starPose((float) (radius * STAR_SCALE), spin + i * 45f + 20f), 2);
                    }
                }
            }
            if (tick % 3 == 0) {
                Location mote = Vfx.scatter(center, radius * 0.8)
                        .add(0, 0.1 + ThreadLocalRandom.current().nextDouble(0.4), 0);
                Vfx.dot(mote, Particle.ENCHANT, null, false);
                Vfx.dot(mote.add(0, 0.3, 0), Particle.DUST, VIOLET_SMALL, false);
            }
            if (left == fallTicks()) {
                dropSpear();
            }
            if (left > 0 && left <= fallTicks() && left % 2 == 0) {
                Fx.sound(center, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.5f, 1.6f + (SPEAR_FALL - left) * 0.05f);
            }
            if (left <= 0) {
                strike();
                return false;
            }
            return true;
        }

        /** Ticks the landed spear stays stuck in the ground before it fades. */
        private long stay() {
            return active ? SPEAR_STAY : ULT_SPEAR_STAY;
        }

        /** Ticks the spear takes to fall: it appears at least one tick after the seal. */
        private long fallTicks() {
            return Math.max(1, Math.min(SPEAR_FALL, delay - 1));
        }

        /** The spear appears high above the seal and falls onto it (smoothly, through the client's interpolation). */
        private void dropSpear() {
            int wanted = lite ? 2 : 3;
            int count = reserve(wanted) ? wanted : reserve(2) ? 2 : 0;
            if (!spear.isEmpty() || count == 0) {
                return;
            }
            long fall = fallTicks();
            Location high = center.clone().add(0, SPEAR_HEIGHT, 0);
            long life = fall + stay() + 10;
            List<BlockDisplay> parts = new ArrayList<>();
            parts.add(Displays.block(ctx(), owner, high, SHAFT_BLOCK.createBlockData(), shaftPose(), life));
            BlockDisplay blade = Displays.block(ctx(), owner, high, HEAD_BLOCK.createBlockData(), headPose(0), life);
            parts.add(blade);
            if (count >= 3) {
                parts.add(Displays.block(ctx(), owner, high, HEAD_BLOCK.createBlockData(), headPose(90), life));
            }
            for (BlockDisplay part : parts) {
                part.setViewRange(2.0f);
                part.setTeleportDuration((int) Math.min(59, fall));
                spear.add(part);
                displays.add(part);
            }
            Displays.outline(blade, BONE);
            Location ground = center.clone().subtract(0, 0.3, 0);
            ctx().runLater(owner, 1, () -> {
                for (BlockDisplay part : spear) {
                    Displays.moveTo(part, ground);
                }
            });
            Fx.sound(high, Sound.ITEM_TRIDENT_THROW, 1.2f, 0.7f);
            Vfx.cloud(ctx(), high, Particle.END_ROD, 6, 0.2, 0.05, null, true);
        }

        /** The prophecy comes true. */
        private void strike() {
            done = true;
            Location at = center.clone().add(0, 0.1, 0);
            List<LivingEntity> struck = new ArrayList<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius + SPEAR_REACH_UP)) {
                // Two seals of one wave that overlap (enemies side by side) strike each of them once.
                if (inColumn(enemy, center, radius) && (wave == null || wave.add(enemy.getUniqueId()))) {
                    struck.add(enemy);
                }
            }
            long slow = active ? params().ticks("prophecy_slow") : 0;
            double slowAmount = Math.clamp(params().num("prophecy_slow_amount") / 100.0, 0.0, 1.0);
            boolean hitAny = false;
            boolean hitTarget = false;
            for (LivingEntity enemy : struck) {
                Combat.hit(ctx(), owner, enemy, damage);
                hitAny = true;
                hitTarget |= enemy.equals(target);
                if (slow > 0 && slowAmount > 0 && Combat.isTargetable(ctx(), owner, enemy)) {
                    ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slowAmount, Operation.MULTIPLY_SCALAR_1,
                            slow);
                }
                if (!isHidden(owner, enemy)) {
                    Location body = Fx.body(enemy);
                    Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.ENCHANTED_HIT, true);
                    Vfx.trail(body, body.clone().add(0, 1.6, 0), BONE, 10, false);
                }
            }
            impactVisuals(at, hitAny);
            if (active) {
                resolve(hitAny, hitTarget);
            }
            for (Display part : seal) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 6);
                }
            }
            List<BlockDisplay> stuck = new ArrayList<>(spear);
            ctx().runLater(owner, stay(), () -> {
                for (BlockDisplay part : stuck) {
                    if (part.isValid()) {
                        Displays.shrinkAway(ctx(), owner, part, 6);
                    }
                }
            });
            afterglow(at);
        }

        private void impactVisuals(Location at, boolean hit) {
            Vfx.dot(at.clone().add(0, 0.6, 0), Particle.FLASH, BONE, true);
            Vfx.burst(ctx(), at.clone().add(0, 0.5, 0), PALETTE, radius * 0.6, Particle.END_ROD, true);
            Block ground = center.clone().subtract(0, 0.5, 0).getBlock();
            if (ground.getType().isSolid()) {
                world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 18), radius * 0.4, 0.1, radius * 0.4, 0.15,
                        ground.getBlockData(), true);
            }
            Vfx.shockwave(ctx(), owner, at, 0.3, radius + 0.4, 5, Particle.DUST_COLOR_TRANSITION, BRONZE_FADE, true);
            Fx.sound(at, Sound.ITEM_TRIDENT_HIT_GROUND, 1.4f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.BLOCK_BELL_USE, 1.0f, 0.55f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ITEM_TRIDENT_THUNDER, 0.35f, 1.7f);
            if (hit) {
                Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0f, 0.8f);
            }
        }

        /** Violet motes rising from the spot where fate fell, for a little while. */
        private void afterglow(Location at) {
            Loop.repeat(ctx(), owner, 2, 2, 8, run -> {
                Location mote = Vfx.scatter(at, radius * 0.7).add(0, 0.2 + run * 0.12, 0);
                Vfx.dot(mote, Particle.DUST_COLOR_TRANSITION, FADE, false);
                if (run % 2 == 0) {
                    Vfx.dot(mote.clone().add(0, 0.2, 0), Particle.WHITE_ASH, null, false);
                }
                return true;
            });
        }

        /** The active's prophecy: a hit shortens the cooldown, a miss fates the aimed enemy. */
        private void resolve(boolean hitAny, boolean hitTarget) {
            if (hitAny) {
                double refund = Math.max(0, params().num("hit_refund"));
                if (refund > 0 && ctx().cooldownRemaining(owner) > 0) {
                    ctx().reduceCooldown(owner, refund);
                    Location hand = owner.getEyeLocation().subtract(0, 0.4, 0);
                    Vfx.cloud(ctx(), hand, Particle.ENCHANT, 10, 0.3, 0.4, null, false);
                    owner.playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS,
                            1.0f, 1.6f);
                }
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>◉ 예언 적중</b></" + COLOR_TAG + ">"
                        + (refund > 0 ? " <" + BRONZE_TAG + ">쿨타임 -" + AbilityInfo.formatNumber(refund) + "초</"
                        + BRONZE_TAG + ">" : "")));
            }
            if (!hitTarget && Combat.isTargetable(ctx(), owner, target)) {
                markFate(owner, target);
            }
        }

        void end() {
            if (!done) {
                done = true;
                for (Display part : seal) {
                    if (part.isValid()) {
                        part.remove();
                    }
                }
                for (BlockDisplay part : spear) {
                    if (part.isValid()) {
                        part.remove();
                    }
                }
            }
        }
    }

    /** True if {@code enemy}'s hitbox reaches into the falling spear's column over {@code center}. */
    private static boolean inColumn(LivingEntity enemy, Location center, double radius) {
        BoundingBox box = enemy.getBoundingBox();
        double dx = Math.max(Math.max(box.getMinX() - center.getX(), 0), center.getX() - box.getMaxX());
        double dz = Math.max(Math.max(box.getMinZ() - center.getZ(), 0), center.getZ() - box.getMaxZ());
        return dx * dx + dz * dz <= radius * radius && box.getMinY() <= center.getY() + SPEAR_REACH_UP
                && box.getMaxY() >= center.getY() - 0.5;
    }

    // ------------------------------------------------------------------ ultimate: the fall of Troy

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (doomed(owner).isEmpty()) {
            return ActivationResult.fail("반경 안에 멸망을 예언할 적이 없습니다");
        }
        new FallOfTroy(owner).start();
        return ActivationResult.success();
    }

    /** The enemies the fall of Troy is foretold for: the nearest ones around, never one hidden from Cassandra. */
    private List<LivingEntity> doomed(Player owner) {
        List<LivingEntity> doomed = new ArrayList<>();
        int max = Math.max(1, params().integer("ult_targets"));
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), params().num("ult_radius"))) {
            if (!isHidden(owner, enemy)) {
                doomed.add(enemy);
                if (doomed.size() >= max) {
                    break;
                }
            }
        }
        return doomed;
    }

    /**
     * The ultimate: a giant eye opens over Cassandra while the bell tolls, then three waves of prophecies, one per
     * enemy around, each at the spot the enemy is about to reach. It watches the enemies itself every tick, so it works
     * on an instance that never ticked (the gambler borrows it).
     */
    private final class FallOfTroy implements LongPredicate {

        private final Player owner;
        private final long windup;
        private final long interval;
        private final int waves;
        private final long seal;
        private int cast;
        private @Nullable ItemDisplay eye;
        private @Nullable TextDisplay title;

        FallOfTroy(Player owner) {
            this.owner = owner;
            this.windup = Math.max(1, params().ticks("ult_windup"));
            this.interval = Math.max(1, params().ticks("ult_interval"));
            this.waves = Math.max(1, params().integer("ult_waves"));
            this.seal = Math.max(2, params().ticks("ult_delay"));
        }

        void start() {
            Location over = eyeSpot();
            if (reserve(1)) {
                eye = Displays.item(ctx(), owner, over, new ItemStack(EYE_ITEM),
                        Displays.centered(0, 0, 0, 0.1f, 0.1f, 0.1f, 0, 0, 0), total() + 20);
                eye.setBillboard(Display.Billboard.CENTER);
                eye.setTeleportDuration(2);
                eye.setViewRange(3.0f);
                Displays.outline(eye, VIOLET);
                displays.add(eye);
                Displays.animateNextTick(ctx(), owner, eye, eyePoseBig(2.2f), (int) Math.min(10, windup));
            }
            if (reserve(1)) {
                title = Displays.text(ctx(), owner, over.clone().add(0, 1.3, 0),
                        Text.mm("<b><gradient:" + BONE_TAG + ":" + COLOR_TAG + ">트로이는 멸망한다</gradient></b>"),
                        0.9f, windup + interval * waves + 10);
                title.setViewRange(3.0f);
                displays.add(title);
            }
            Location feet = owner.getLocation().add(0, 0.1, 0);
            double radius = params().num("ult_radius");
            Vfx.ring(ctx(), feet, radius, (int) Math.min(120, Math.round(radius * 5)), 0,
                    Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Vfx.shockwave(ctx(), owner, feet, 0.5, Math.min(radius, 8), 8, Particle.DUST, VIOLET_DUST, true);
            Location head = owner.getEyeLocation();
            Fx.sound(head, Sound.BLOCK_BELL_USE, 2.0f, 0.5f);
            Fx.sound(head, Sound.BLOCK_BELL_RESONATE, 1.5f, 0.8f);
            Fx.sound(head, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.2f, 0.7f);
            Fx.sound(head, Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.0f, 0.6f);
            ctx().announce(Text.mm("<" + COLOR_TAG + ">◉</" + COLOR_TAG + "> <white><owner></white><gray>이(가) "
                            + "</gray><" + COLOR_TAG + "><b>트로이의 멸망</b></" + COLOR_TAG + "><gray>을 예언합니다!</gray>",
                    Placeholder.component("owner", owner.name())));
            Loop.repeat(ctx(), owner, 0, 1, total(), this, this::close);
        }

        private long total() {
            return windup + interval * (waves - 1) + seal + 2;
        }

        @Override
        public boolean test(long tick) {
            watch(owner);
            ItemDisplay giant = eye;
            if (giant != null && giant.isValid()) {
                Displays.moveTo(giant, eyeSpot());
            }
            TextDisplay label = title;
            if (label != null && label.isValid()) {
                Displays.moveTo(label, eyeSpot().add(0, 1.3, 0));
            }
            if (tick % 4 == 0) {
                Location head = eyeSpot();
                Vfx.ring(ctx(), head, 1.4, 14, tick * 0.3, Particle.DUST, VIOLET_DUST, true);
                Vfx.dot(head.clone().add(0, -0.8, 0), Particle.REVERSE_PORTAL, null, false);
            }
            if (cast < waves && tick == windup + cast * interval) {
                wave(cast);
                cast++;
            }
            return true;
        }

        /** One wave: a prophecy for every doomed enemy, the eye blinks and the bell tolls. */
        private void wave(int index) {
            List<LivingEntity> doomed = doomed(owner);
            Location from = eyeSpot();
            double radius = Math.max(0.3, params().num("ult_seal_radius"));
            double damage = params().num("ult_damage");
            Set<UUID> struck = new HashSet<>();
            boolean lite = doomed.size() > FULL_LOOK_TARGETS;
            for (LivingEntity enemy : doomed) {
                Location center = predict(enemy, seal, Math.max(0, params().num("prophecy_lead")));
                new Prophecy(owner, enemy, center, radius, seal, damage, false, false, struck, lite).start();
                for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                    Vfx.trail(from, center.clone().add(0, 0.3, 0), i % 2 == 0 ? VIOLET : BONE, 10 + i * 2, true);
                }
            }
            ItemDisplay giant = eye;
            if (giant != null && giant.isValid()) {
                // The eye blinks: it narrows, then opens wide.
                Displays.animate(giant, eyePoseBig(0.6f), 2);
                ctx().runLater(owner, 3, () -> Displays.animate(giant, eyePoseBig(2.4f), 4));
            }
            Vfx.dot(from, Particle.FLASH, VIOLET, true);
            Fx.sound(from, Sound.BLOCK_BELL_USE, 1.8f, 0.5f + index * 0.08f);
            Fx.sound(from, Sound.BLOCK_BELL_RESONATE, 1.0f, 0.7f + index * 0.1f);
            Fx.sound(from, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.8f, 1.2f + index * 0.1f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>◉ 트로이의 멸망</b></" + COLOR_TAG + "> <gray>"
                    + (index + 1) + "/" + waves + "번째 예언 — " + doomed.size() + "명</gray>"));
        }

        private Location eyeSpot() {
            return Displays.unrotated(owner.getLocation().add(0, owner.getHeight() + 1.6, 0));
        }

        /** The eye closes over Cassandra. */
        private void close() {
            ItemDisplay giant = eye;
            if (giant != null && giant.isValid()) {
                Vfx.cloud(ctx(), giant.getLocation(), Particle.REVERSE_PORTAL, 16, 0.5, 0.05, null, true);
                Displays.shrinkAway(ctx(), owner, giant, 6);
            }
            TextDisplay label = title;
            if (label != null && label.isValid()) {
                label.remove();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A flat star plate of side {@code side} lying on the ground, turned {@code yaw} degrees. */
    private static Transformation starPose(float side, float yaw) {
        return Displays.transform(0, 0.012f, 0, side, 0.02f, side, yaw, 0, 0);
    }

    /** The eye in the middle of a seal: an eye of ender lying face up, turned {@code yaw} degrees. */
    private static Transformation eyePose(float scale, float yaw) {
        return Displays.centered(0, 0.03f, 0, scale, scale, scale, yaw, -90, 0);
    }

    private static Transformation eyePoseBig(float scale) {
        return Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, 0);
    }

    /** The spear's bronze shaft, standing straight up from the tip (the display's location). */
    private static Transformation shaftPose() {
        return Displays.transform(0, 1.6f, 0, 0.1f, 2.7f, 0.1f, 0, 0, 0);
    }

    /** One blade of the bone-white spearhead: a thin diamond pointing down, turned {@code yaw} degrees. */
    private static Transformation headPose(float yaw) {
        return Displays.transform(0, 0.2f, 0, 0.24f, 0.24f, 0.04f, yaw, 0, 45);
    }

    /** {@code at} moved by a random offset of up to {@code spread} blocks on every axis. */
    private static Location jittered(Location at, double spread) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return at.clone().add(random.nextDouble(-spread, spread), random.nextDouble(-spread, spread),
                random.nextDouble(-spread, spread));
    }

    private static Location unrotated(Location at) {
        return Displays.unrotated(at);
    }

    /** Reserves room for {@code count} more displays under {@link #MAX_DISPLAYS}; false when there is none. */
    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    private static boolean isHidden(Player owner, LivingEntity entity) {
        return entity instanceof Player hidden && !owner.canSee(hidden);
    }
}
