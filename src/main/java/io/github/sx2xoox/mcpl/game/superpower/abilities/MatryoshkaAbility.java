package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
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
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 마트료시카 — the Russian nesting doll: a big lacquered doll, a smaller one inside it, a smaller one inside that.
 *
 * <p>The owner wears its doll shells as layers of armour: every shell makes it bigger, sturdier and slower. A hard blow
 * (or enough small ones) cracks the outer shell, whose two halves spring off and stand up as a little doll-shaped cover
 * on the side the blow came from, and the owner comes out one size smaller and quicker — a tank that turns into a
 * runner the more it is beaten, down to the bare core, which is small and fast but fragile. Left alone for a while, a
 * shell closes over it again. The active throws a doll that pops open where it lands, and the dolls inside hop on to
 * the next enemies, one size smaller each time (crouching throws one of its own shells for a harder first doll). The
 * ultimate drops a giant doll shell over the owner: enemies around are knocked out of the way, every shell comes back
 * plus a fourth one of gold, and while the gold lasts the halves of every cracked shell fly at the nearest enemies.
 *
 * <p>Look (lacquer red, gold leaf, face cream, flower blue): little dolls of block displays — a red body and head, a
 * cream face, a gold band at the seam and a blue flower on the belly — and red-and-gold flowers of dust. The shell count
 * floats over the owner's head as red flowers (the outer one paling as it takes damage, a gold one for the golden
 * shell, a countdown while a shell grows back) and a gold seam glints at its waist. A cracked shell bursts into red
 * chips with a wooden crack, its halves fly to the cover spot and become a doll silhouette of blocks (a cream face with
 * two eyes, a gold seam and a cornflower); a regrown shell closes over the owner like a lid. The thrown doll rocks in
 * the air, pops its lid with a "퐁" and the inner dolls hop out after the next enemy. The ultimate is a giant doll
 * falling out of the sky onto its own shadow, a red-and-gold shockwave, and a golden shimmer around the owner.
 */
public final class MatryoshkaAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("matryoshka", "마트료시카", Material.RED_GLAZED_TERRACOTTA)
            .color(TextColor.color(0xC8102E))
            .difficulty(2)
            .passive("겹겹이",
                    "인형 껍데기 {shells}겹이 1겹마다 받는 피해를 {shell_guard}%, 이동 속도를 {shell_slow}% 줄입니다. "
                            + "한 번에 {crack_hit} 이상 맞거나 피해가 {crack_total} 쌓이면 1겹이 갈라져 엄폐물이 되고, "
                            + "{regrow_delay}초간 맞지 않으면 다시 덮입니다. 껍데기가 없으면 이동 속도 +{core_speed}%, "
                            + "받는 피해 +{core_weakness}%입니다.")
            .active("인형 속 인형",
                    "조준한 곳({throw_range}블록 이내)에 큰 인형을 던져 반경 {big_radius}블록의 적에게 {big_damage} "
                            + "피해를 줍니다. 이어 중간 인형이 {hop_range}블록 안의 다른 적(없으면 같은 적)에게 튀어 "
                            + "{middle_damage} 피해를, 작은 인형이 {small_damage} 피해와 {small_slow_duration}초간 "
                            + "이동 속도 -{small_slow}%를 줍니다. 웅크린 채 쓰면 껍데기 1겹을 벗어 던져 큰 인형의 "
                            + "피해가 {shed_bonus} 늘어납니다.",
                    9)
            .ultimate("처음부터 다시",
                    "{drop_delay}초 뒤 거대한 인형 껍데기가 나를 덮으며 반경 {slam_radius}블록의 적에게 {slam_damage} "
                            + "피해를 주고 약 {slam_push}블록 밀쳐냅니다. 껍데기 {shells}겹을 되찾고 "
                            + "{gold_duration}초간 금빛 껍데기 1겹을 더 두르며, 그동안 갈라지는 껍데기 조각은 엄폐물 대신 "
                            + "{shard_range}블록 안의 가장 가까운 적에게 날아가 하나에 {shard_damage} 피해를 줍니다(최대 "
                            + "{shard_max}개). 머리 위 공간이 모자라면 쓸 수 없습니다.")
            // The shells, the crack hook and the golden layer all live in the granted passive.
            .ultimateNotStandalone()
            .param("shells", 3)
            .param("scale_core", 0.7)
            .param("scale_step", 0.15)
            .param("shell_guard", 8)
            .param("shell_slow", 4)
            .param("crack_hit", 4)
            .param("crack_total", 7)
            .param("crack_grace", 0.5)
            .param("cover_distance", 1.5)
            .param("cover_duration", 4)
            .param("core_speed", 10)
            .param("core_weakness", 10)
            .param("regrow_delay", 10)
            .param("throw_range", 16)
            .param("big_radius", 1.5)
            .param("big_damage", 3)
            .param("hop_range", 6)
            .param("middle_damage", 2)
            .param("small_damage", 1)
            .param("small_slow", 30)
            .param("small_slow_duration", 1)
            .param("doll_cap", 6)
            .param("shed_bonus", 2)
            .param("drop_delay", 0.8)
            .param("slam_radius", 4)
            .param("slam_damage", 4)
            .param("slam_push", 4)
            .param("gold_duration", 8)
            .param("shard_range", 8)
            .param("shard_damage", 2)
            .param("shard_max", 4)
            .build();

    // ------------------------------------------------------------------ look

    private static final String RED_TAG = "#C8102E";
    private static final String GOLD_TAG = "#E8B931";
    private static final String FACE_TAG = "#F6EBDD";
    private static final int RED_RGB = 0xC8102E;
    private static final int GOLD_RGB = 0xE8B931;
    private static final int FACE_RGB = 0xF6EBDD;

    /** Bright gold core, lacquer red body, dark lacquer edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFE58A, RED_RGB, 0x6E0A1A);
    /** The golden halves: pale gold core, gold leaf body, lacquer red edge. */
    private static final Vfx.Palette GOLD_PALETTE = Vfx.Palette.of(0xFFF4C2, GOLD_RGB, RED_RGB);
    private static final Color RED = Color.fromRGB(RED_RGB);
    private static final Color GOLD = Color.fromRGB(GOLD_RGB);
    private static final Color FACE = Color.fromRGB(FACE_RGB);
    private static final Color BLUE = Color.fromRGB(0x2E5EAA);
    private static final Particle.DustOptions RED_DUST = new Particle.DustOptions(RED, 1.0f);
    private static final Particle.DustOptions RED_SMALL = new Particle.DustOptions(RED, 0.6f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 0.9f);
    private static final Particle.DustOptions GOLD_SMALL = new Particle.DustOptions(GOLD, 0.5f);
    private static final Particle.DustOptions FACE_DUST = new Particle.DustOptions(FACE, 0.6f);
    private static final Particle.DustOptions BLUE_DUST = new Particle.DustOptions(BLUE, 0.8f);
    private static final Particle.DustOptions SHADOW = new Particle.DustOptions(Color.fromRGB(0x2A0E14), 1.4f);
    private static final Particle.DustTransition GOLD_FLASH = new Particle.DustTransition(Color.fromRGB(0xFFF4C2),
            GOLD, 1.1f);
    private static final Particle.DustTransition RED_FADE = PALETTE.fade(1.2f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);
    private static final Color GOLD_TRAIL = Color.fromRGB(0xFFD75E);

    /** Block types of the dolls and covers (block data is created on use: it needs a running server). */
    private static final Material LACQUER = Material.RED_CONCRETE;
    private static final Material ORNATE = Material.RED_GLAZED_TERRACOTTA;
    private static final Material GILT = Material.GOLD_BLOCK;
    private static final Material CREAM = Material.SMOOTH_QUARTZ;
    private static final Material PAINT = Material.LAPIS_BLOCK;
    private static final Material INK = Material.BLACK_CONCRETE;
    private static final Material CORNFLOWER = Material.CORNFLOWER;

    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 1.8;
    private static final double AIR_DRAG = 0.91;
    /** Ticks a pushed body flies before it lands, for a lift of about {@link #PUSH_LIFT}. */
    private static final int PUSH_AIR_TICKS = 11;
    private static final double PUSH_LIFT = 0.42;

    /**
     * Teleport duration of the models that follow the owner's body (the lid, the landed giant doll): other clients
     * draw a player from a position every 2 ticks smoothed over 3, so 3 keeps them on the body there.
     */
    private static final int FOLLOW_GLIDE = 3;

    // passive
    private static final long SEAM_PERIOD = 10;
    /** The badge rides the owner's head (top of its box): its text floats this far over it. */
    private static final float BADGE_LIFT = 0.75f;
    private static final float BADGE_SCALE = 0.85f;
    /** "딱" pops this far over the badge, never on top of it. */
    private static final double CRACK_WORD_LIFT = 0.45;
    /** A freshly grown body is not hurt by the blocks it grew into (the client settles it) for this long. */
    private static final long BREATH_TICKS = 20;
    private static final long CRAMP_FX_PERIOD = 40;
    private static final int HALF_FLIGHT = 4;
    private static final int ROLL_TICKS = 8;
    private static final int LID_TICKS = 6;
    /** The closed lid stays on (still following the owner) this long after the click, then shrinks away. */
    private static final int LID_HOLD = 3;
    private static final int LID_FADE = 3;
    /** How wide a shell's halves are, relative to the body they came off. */
    private static final float SHELL_WIDEN = 1.25f;
    /** A cover block stands on ground at most this far below its own floor (a path yes; a slab, a carpet no). */
    private static final double MAX_COVER_GAP = 0.2;
    /** A falling model's ground probe starts this far above its pivot (outside the ground it may dip into). */
    private static final double GROUND_PROBE_LIFT = 0.25;
    private static final int DROP_TICKS = 3;

    // active
    /** Heights of the big, middle and small doll. */
    private static final double[] DOLL_HEIGHTS = {0.8, 0.6, 0.42};
    private static final double DOLL_HIT = 0.45;
    private static final int MAX_FLIGHT = 60;
    /** The thrown doll leaves the hand this far in front of the eyes (per body size), out of the owner's face. */
    private static final double HAND_REACH = 0.7;
    /** It leaves the hand this small and grows to full size over its first {@link #THROW_GROW} ticks. */
    private static final float THROW_START_SCALE = 0.3f;
    private static final int THROW_GROW = 2;
    /** Rocking from side to side: degrees and radians per tick (one rock in about 14 ticks). */
    private static final double ROCK_DEGREES = 22;
    private static final double ROCK_RATE = 0.45;
    private static final long POP_DELAY = 4;
    private static final double HOP_HIT = 1.6;
    /** A hop shorter than this (horizontally) keeps the throw's facing instead of aiming at the enemy. */
    private static final double HOP_AIM_MIN = 0.4;
    /** A hopping doll turns after its enemy only while it is farther than this from it (horizontally). */
    private static final double HOP_REAIM = 0.5;
    private static final int HOP_MIN_TICKS = 7;
    private static final int HOP_MAX_TICKS = 16;
    private static final double MAX_THROW_SPEED = 2.0;

    // ultimate
    private static final double GIANT_HEIGHT = 4.2;
    private static final double GIANT_START = 6.5;
    private static final int SETTLE_TICKS = 6;
    private static final double SHARD_SPEED = 0.9;
    private static final int SHARD_TICKS = 24;
    private static final double SHARD_HIT = 0.9;
    private static final int HALVES = 2;

    // ------------------------------------------------------------------ models

    /**
     * One box of a little model: its material (and the material of its golden twin), its center and size in the
     * model's units (the model is scaled per axis when it is posed).
     */
    private record Part(Material material, Material golden, float cx, float cy, float cz, float sx, float sy,
                        float sz) {

        /** The block display transformation of this part, scaled by {@code k} and turned by {@code rotation}. */
        Transformation transform(Quaternionf rotation, Vector3f k, Vector3f lift) {
            Vector3f size = new Vector3f(sx * k.x, sy * k.y, sz * k.z);
            Vector3f center = new Quaternionf(rotation).transform(new Vector3f(cx * k.x, cy * k.y, cz * k.z)).add(lift);
            Vector3f translation = center.sub(new Quaternionf(rotation).transform(new Vector3f(size).mul(0.5f)));
            return new Transformation(translation, new Quaternionf(rotation), size, new Quaternionf());
        }
    }

    /** A doll one unit tall, its face to +Z: body, head, face, gold seam band, blue flower. */
    private static final List<Part> DOLL = List.of(
            new Part(LACQUER, GILT, 0, -0.2f, 0, 0.66f, 0.6f, 0.66f),
            new Part(LACQUER, GILT, 0, 0.28f, 0, 0.48f, 0.44f, 0.48f),
            new Part(CREAM, CREAM, 0, 0.27f, 0.245f, 0.34f, 0.3f, 0.02f),
            new Part(GILT, LACQUER, 0, 0.08f, 0, 0.69f, 0.07f, 0.69f),
            new Part(PAINT, PAINT, 0, -0.18f, 0.335f, 0.22f, 0.22f, 0.02f));
    /** Indices of the lid (head and face) in {@link #DOLL}. */
    private static final int HEAD = 1;
    private static final int FACE_PART = 2;
    /** How many parts the big, middle and small doll show. */
    private static final int[] DOLL_PARTS = {5, 4, 3};

    /** The upper half of a cracked shell (one unit wide, one unit tall, centered): lacquer cap with the face. */
    private static final List<Part> TOP_HALF = List.of(
            new Part(LACQUER, GILT, 0, 0, 0, 1, 1, 1),
            new Part(CREAM, CREAM, 0, 0.1f, 0.505f, 0.55f, 0.55f, 0.02f));
    /**
     * The lower half of a cracked shell: lacquer base with the gold seam on its rim (the band's top stays just under
     * the rim, never in the plane of the base's top face).
     */
    private static final List<Part> BOTTOM_HALF = List.of(
            new Part(LACQUER, GILT, 0, 0, 0, 1, 1, 1),
            new Part(GILT, LACQUER, 0, 0.44f, 0, 1.04f, 0.1f, 1.04f));

    /** The face painted on the cover's head block (relative to the block's center, its front to +Z). */
    private static final List<Part> COVER_FACE = List.of(
            new Part(CREAM, CREAM, 0, -0.03f, 0.505f, 0.62f, 0.56f, 0.02f),
            new Part(INK, INK, -0.13f, 0.03f, 0.52f, 0.08f, 0.1f, 0.02f),
            new Part(INK, INK, 0.13f, 0.03f, 0.52f, 0.08f, 0.1f, 0.02f));
    /** The gold seam around the top of the cover's body block (its top just under the block's top face). */
    private static final Part COVER_SEAM = new Part(GILT, GILT, 0, 0.46f, 0, 1.03f, 0.07f, 1.03f);

    // ------------------------------------------------------------------ state

    /** Every display alive, for the budget. */
    private final List<Display> live = new ArrayList<>();
    /**
     * The displays that make way (oldest first) when a doll that strikes needs room: the cover paint, the words that
     * pop up, the halves flying off, the lid and the little dolls that fizzle. The dolls that hit never go unseen.
     */
    private final List<Display> optional = new ArrayList<>();
    /**
     * Viewers who cannot see the owner: the head badge is hidden from them, and so is every model on or coming off
     * the owner's body (those filter their viewers inside their spawn, see {@link Sight}).
     */
    private final Set<UUID> veiled = new HashSet<>();
    /** Shells that cracked while the gold lasts, waiting for the golden loop to fling their halves. */
    private final Deque<Spill> pendingShards = new ArrayDeque<>();
    private @Nullable Hooks hooks;
    private @Nullable TextDisplay badge;
    private @Nullable String badgeText;
    private @Nullable NamespacedKey scaleKey;
    private @Nullable NamespacedKey speedKey;
    /** Shells worn now, the golden one included. */
    private int shells;
    /** The outer shell is the golden one of the ultimate. */
    private boolean gilded;
    /** Damage the outer shell took so far. */
    private double strain;
    private long graceUntil = Long.MIN_VALUE / 2;
    /** Damage or a lost shell last happened here: shells grow back after a quiet while from then. */
    private long quietFrom = Long.MIN_VALUE / 2;
    private long breathUntil = Long.MIN_VALUE / 2;
    private long goldUntil = Long.MIN_VALUE / 2;
    private int shardsLeft;
    private boolean descending;
    /** A shell is due but there is no headroom for it (said once, shown on the badge). */
    private boolean cramped;
    private long lastCrampFx = Long.MIN_VALUE / 2;

    /** A cracked shell: where it cracked, from where the blow came, its size and whether it was the golden one. */
    private record Spill(Location origin, @Nullable Vector from, float width, float height, float yaw,
                         boolean golden) {
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        Player owner = player();
        shells = maxShells();
        applySize(owner);
        Hooks created = new Hooks();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        hooks = created;
        if (makeRoom(1)) {
            // Its lift is in the transformation: it rides the head (the top of the owner's box), so every client
            // carries it with the body itself, never a moment behind or ahead of it.
            TextDisplay label = keep(Displays.spawn(ctx(), owner, badgeSpot(owner, bodyHeight()),
                    TextDisplay.class, 0, display -> {
                        display.text(Component.empty());
                        display.setBillboard(Display.Billboard.CENTER);
                        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                        display.setShadowed(true);
                        display.setTransformation(badgePose(BADGE_SCALE));
                    }));
            badge = label;
            mountBadge(owner, label);
        }
        refreshBadge(false);
    }

    @Override
    public void onRevoke() {
        if (hooks != null) {
            HandlerList.unregisterAll(hooks);
            hooks = null;
        }
        // Modifiers, tasks, displays and temporary blocks are released by the context.
        live.clear();
        optional.clear();
        veiled.clear();
        pendingShards.clear();
        badge = null;
        badgeText = null;
        scaleKey = null;
        speedKey = null;
        descending = false;
        cramped = false;
    }

    /** The owner's own hurt, once it has landed (nothing cancelled it): the shells take the strain. */
    private final class Hooks implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onHurt(EntityDamageEvent event) {
            Player owner = player();
            if (event.getEntity().equals(owner) && ctx().isAlive(owner)) {
                double taken = event.getFinalDamage();
                if (taken > 0) {
                    hurt(owner, event, taken);
                }
            }
        }
    }

    // ================================================================== passive: shells

    private int maxShells() {
        return Math.max(0, params().integer("shells"));
    }

    /** The body size with {@code count} shells. */
    private double scaleFor(int count) {
        return Math.max(0.1, params().num("scale_core") + Math.max(0, params().num("scale_step")) * count);
    }

    private boolean goldActive() {
        return ctx().currentTick() < goldUntil;
    }

    /** Size and speed for the shells worn now (each shell bigger and slower, the bare core quick). */
    private void applySize(Player owner) {
        NamespacedKey oldScale = scaleKey;
        double scale = scaleFor(shells);
        scaleKey = Math.abs(scale - 1) > 1.0E-9
                ? ctx().addModifier(owner, owner, Attribute.SCALE, scale - 1, Operation.ADD_NUMBER, 0) : null;
        if (oldScale != null) {
            ctx().removeModifier(owner, oldScale);
        }
        NamespacedKey oldSpeed = speedKey;
        double speed = shells > 0 ? -Math.max(0, params().num("shell_slow")) / 100.0 * shells
                : Math.max(0, params().num("core_speed")) / 100.0;
        speed = Math.max(-0.9, speed);
        speedKey = Math.abs(speed) > 1.0E-9
                ? ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1, 0)
                : null;
        if (oldSpeed != null) {
            ctx().removeModifier(owner, oldSpeed);
        }
        // A badge that rides the head goes up and down with it; one that could not get on keeps to the new head at
        // once (not a tick late, inside a grown head). The server resizes the box only on the owner's next tick.
        TextDisplay label = badge;
        if (label != null && label.isValid() && !owner.equals(label.getVehicle())) {
            Displays.moveTo(label, badgeSpot(owner, bodyHeight()));
        }
    }

    /**
     * The owner's standing height with the shells it wears now. Read this, not {@code getHeight()}, right after the
     * size changed: the server only resizes the owner's box on its next tick.
     */
    private double bodyHeight() {
        return Body.PLAYER.height() * scaleFor(shells);
    }

    /** The owner's standing width with the shells it wears now (see {@link #bodyHeight()}). */
    private double bodyWidth() {
        return Body.PLAYER.width() * scaleFor(shells);
    }

    /** True if the owner could stand up at {@code scale} where it is (no ceiling in its grown head). */
    private boolean roomFor(Player owner, double scale) {
        double height = Body.PLAYER.height() * scale;
        double width = Math.min(owner.getWidth(), Body.PLAYER.width() * scale);
        return Spots.isFree(owner.getWorld(), owner.getLocation(), width, height);
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !(event.getDamage() > 0)) {
            return;
        }
        if (event.getCause() == DamageCause.SUFFOCATION && ctx().currentTick() < breathUntil) {
            // The shell grew a moment ago: the client is still settling the bigger body.
            event.setCancelled(true);
            return;
        }
        DamageType type = event.getDamageSource().getDamageType();
        if (type == DamageType.GENERIC_KILL || type == DamageType.OUT_OF_WORLD || type == DamageType.OUTSIDE_BORDER) {
            return;
        }
        double factor;
        if (shells > 0) {
            factor = 1 - Math.min(0.9, Math.max(0, params().num("shell_guard")) / 100.0 * shells);
        } else {
            factor = 1 + Math.max(0, params().num("core_weakness")) / 100.0;
        }
        event.setDamage(event.getDamage() * factor);
    }

    /** A hit landed on the owner: the outer shell takes the strain and may crack. */
    private void hurt(Player owner, EntityDamageEvent event, double taken) {
        long now = ctx().currentTick();
        quietFrom = now;
        cramped = false;
        // The bare core has nothing to crack, a doll that falls cracks no more, and the next shell holds for a
        // moment after a crack.
        if (shells > 0 && taken < owner.getHealth() + owner.getAbsorptionAmount() && now >= graceUntil) {
            strain += taken;
            double total = Math.max(0.1, params().num("crack_total"));
            if (taken >= Math.max(0.1, params().num("crack_hit")) || strain >= total) {
                crack(owner, attackDirection(owner, event));
                return;
            }
            strainFx(owner, strain / total);
        }
        refreshBadge(false);
    }

    /** Where a blow came from, horizontally (null for a hurt without a source, like a fall). */
    private static @Nullable Vector attackDirection(Player owner, EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        Entity damager = byEntity.getDamager();
        if (damager.equals(owner)) {
            return null;
        }
        Vector way;
        if (damager instanceof Projectile projectile && projectile.getVelocity().lengthSquared() > 1.0E-4) {
            way = projectile.getVelocity().clone().multiply(-1);
        } else {
            way = damager.getLocation().toVector().subtract(owner.getLocation().toVector());
        }
        Vector flat = Motion.horizontal(way);
        return flat.lengthSquared() < 1.0E-6 ? null : flat;
    }

    /** The outer shell cracks: "딱" — one size smaller, its halves fly off as cover (or, under gold, at enemies). */
    private void crack(Player owner, @Nullable Vector from) {
        boolean golden = gilded;
        gilded = false;
        double oldScale = scaleFor(shells);
        // The shell that cracks is the one around the body as it was: its waist, before the owner shrinks.
        Spill spill = new Spill(waist(owner), from, (float) (Body.PLAYER.width() * oldScale * SHELL_WIDEN),
                (float) (Body.PLAYER.height() * oldScale), owner.getLocation().getYaw(), golden);
        shells--;
        strain = 0;
        cramped = false;
        long now = ctx().currentTick();
        graceUntil = now + params().ticks("crack_grace");
        quietFrom = now;
        applySize(owner);
        refreshBadge(true);
        crackFx(owner, spill);
        if (goldActive()) {
            // The golden loop (part of the ultimate) flings the halves on its next tick.
            pendingShards.add(spill);
        } else {
            spill(owner, spill);
        }
        if (shells <= 0) {
            owner.sendActionBar(Text.mm("<b><" + FACE_TAG + ">알맹이!</" + FACE_TAG + "></b> <gray>껍데기가 모두 "
                    + "깨졌습니다 · 작고 빠르지만 더 아픕니다</gray>"));
        } else {
            owner.sendActionBar(Text.mm("<b><" + RED_TAG + ">딱!</" + RED_TAG + "></b> <gray>"
                    + (golden ? "금빛 껍데기가" : "껍데기가") + " 갈라졌습니다 ·</gray> <white>" + shells
                    + "겹</white> <gray>남음</gray>"));
        }
    }

    /** Chips of lacquer, a gold flash at the seam and the wooden crack. */
    private void crackFx(Player owner, Spill spill) {
        Location waist = spill.origin();
        World world = waist.getWorld();
        Material shell = spill.golden() ? GILT : LACQUER;
        world.spawnParticle(Particle.BLOCK, waist, Vfx.n(ctx(), 16), spill.width() * 0.35, spill.height() * 0.3,
                spill.width() * 0.35, 0.1, shell.createBlockData());
        Vfx.ring(ctx(), waist, spill.width() * 0.75, 18, 0, Particle.DUST_COLOR_TRANSITION, GOLD_FLASH, false);
        Vfx.dot(waist, Particle.FLASH, spill.golden() ? GOLD : FACE, false);
        Vfx.cloud(ctx(), waist, Particle.CRIT, 10, 0.3, 0.35, null, false);
        flowers(waist, spill.width() * 0.6, 3);
        Fx.sound(waist, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.45f, 1.5f + Fx.jitter(0.08f));
        Fx.sound(waist, Sound.BLOCK_BAMBOO_WOOD_BREAK, 1.0f, 0.85f + Fx.jitter(0.08f));
        Fx.sound(waist, Sound.ENTITY_ITEM_BREAK, 0.6f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(waist, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 0.8f, 0.7f + 0.25f * Math.max(0, shells));
        if (spill.golden()) {
            Fx.sound(waist, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0f, 1.2f + Fx.jitter(0.08f));
        }
        // Over the badge (which pops at the same moment), never on top of it.
        pop(owner, owner.getLocation().add(0, bodyHeight() + BADGE_LIFT + CRACK_WORD_LIFT, 0),
                "<b><" + (spill.golden() ? GOLD_TAG : RED_TAG) + ">딱!</" + (spill.golden() ? GOLD_TAG : RED_TAG)
                        + "></b>", 1.3f, true);
    }

    /** A hit the shell held: a few chips and a knock that rises in pitch as the shell gives. */
    private void strainFx(Player owner, double ratio) {
        Location waist = waist(owner);
        waist.getWorld().spawnParticle(Particle.BLOCK, waist, Vfx.n(ctx(), 5), owner.getWidth() * 0.4,
                owner.getHeight() * 0.25, owner.getWidth() * 0.4, 0.05, LACQUER.createBlockData());
        Fx.sound(waist, Sound.BLOCK_BAMBOO_WOOD_HIT, 0.9f, 0.8f + (float) Math.min(1, ratio) * 0.7f
                + Fx.jitter(0.05f));
        if (ratio >= 0.6) {
            Vfx.cloud(ctx(), waist, Particle.CRIT, 3, 0.25, 0.1, null, false);
        }
    }

    /** The halves of a cracked shell spring off: to a cover spot on the side of the blow, or rolling away. */
    private void spill(Player owner, Spill spill) {
        Location origin = spill.origin();
        Vector from = spill.from();
        CoverPlan plan = from == null ? null : planCover(owner, from);
        Vector3f halfScale = new Vector3f(spill.width(), spill.height() / 2, spill.width());
        Location topStart = origin.clone().add(0, spill.height() / 4, 0);
        Location bottomStart = origin.clone().add(0, -spill.height() / 4, 0);
        float yaw = spill.yaw();
        // Out of the owner's own view while they leave its body, and never seen by whoever cannot see the owner.
        Prop top = new Prop(topStart, TOP_HALF, TOP_HALF.size(), halfScale, new Vector3f(), spill.golden(), yaw, 30,
                false, Sight.LEAVING);
        Prop bottom = new Prop(bottomStart, BOTTOM_HALF, BOTTOM_HALF.size(), halfScale, new Vector3f(),
                spill.golden(), yaw, 30, false, Sight.LEAVING);
        ctx().runLater(owner, 2, () -> showToOwner(top, bottom));
        if (plan == null) {
            Vector way = from != null ? from : Motion.horizontalFacing(owner.getLocation());
            roll(owner, top, topStart, way.clone().rotateAroundY(0.5), yaw, true);
            roll(owner, bottom, bottomStart, way.clone().rotateAroundY(-0.5), yaw, false);
            return;
        }
        Location headAt = plan.base().getRelative(BlockFace.UP).getLocation().add(0.5, 0.5, 0.5);
        Location bodyAt = plan.base().getLocation().add(0.5, 0.5, 0.5);
        // They turn to the cover's face on the way: the cap somersaults onto the head, the base spins onto the body.
        float turn = wrap(yawOf(plan.front()) - yaw);
        fly(owner, top, topStart, headAt, HALF_FLIGHT, 0.6, yaw, turn, 360, null);
        fly(owner, bottom, bottomStart, bodyAt, HALF_FLIGHT, 0.35, yaw, turn - 360, 0, () -> {
            boolean raised = raiseCover(owner, plan, spill.golden());
            top.vanish(raised ? 1 : 6);
            bottom.vanish(raised ? 1 : 6);
        });
    }

    /**
     * A half rolls away along the ground, tumbling over (the top one hops off in a somersault), and is gone. It
     * turns from {@code yaw} (the way it faced on the body) to the way it rolls on the way, never in one tick.
     */
    private void roll(Player owner, Prop half, Location start, Vector way, float yaw, boolean hop) {
        Vector flat = Motion.horizontal(way);
        if (flat.lengthSquared() < 1.0E-6) {
            flat = new Vector(0, 0, 1);
        }
        Location end = start.clone().add(flat.clone().multiply(1.6));
        if (!Spots.clearPath(start, end)) {
            end = start.clone();
        }
        // Onto the ground: the flight keeps the tumbling model's lowest point on it.
        Spots.snapToGround(end.getWorld(), end, 3);
        Location from = start.clone();
        Location to = end;
        fly(owner, half, from, to, ROLL_TICKS, hop ? 0.8 : 0.15, yaw, wrap(yawOf(flat) - yaw), hop ? 360 : 270, () -> {
            to.getWorld().spawnParticle(Particle.POOF, to, Vfx.n(ctx(), 4), 0.15, 0.1, 0.15, 0.02);
            Fx.sound(to, Sound.BLOCK_WOOD_FALL, 0.7f, 1.2f + Fx.jitter(0.1f));
            half.vanish(4);
        });
    }

    // ------------------------------------------------------------------ cover

    /**
     * Where a cover stands: its body block (the head goes on top), the face it shows and the step to its sides (the
     * arms go across the face, so a diagonal blow still gets one straight row facing its main direction).
     */
    private record CoverPlan(Block base, BlockFace front, int stepX, int stepZ) {
    }

    /** A free, flat spot on the ground about {@code cover_distance} towards the blow that the owner can see, or null. */
    private @Nullable CoverPlan planCover(Player owner, Vector from) {
        World world = owner.getWorld();
        BlockFace front = front(from);
        int stepX = front.getModZ() != 0 ? 1 : 0;
        int stepZ = front.getModX() != 0 ? 1 : 0;
        Location eye = waist(owner);
        double distance = Math.max(0.8, params().num("cover_distance"));
        for (double d : new double[] {distance, distance - 0.5}) {
            if (d < 0.8) {
                continue;
            }
            Location spot = owner.getLocation().add(from.clone().multiply(d)).add(0, 0.6, 0);
            double probe = spot.getY();
            Spots.snapToGround(world, spot, 2.6);
            if (spot.getY() >= probe - 1.0E-6) {
                continue; // no ground within reach (a ledge, the void)
            }
            Block base = spot.getBlock();
            if (spot.getY() - base.getY() > 1.0E-3) {
                base = base.getRelative(BlockFace.UP); // the ground is lower than a full block: a path, a slab...
            }
            // Never floating over a slab, a step or a carpet, nor on the far side of a wall or a fence.
            if (!placeable(base) || !flush(base) || !inSight(eye, base)) {
                continue;
            }
            return new CoverPlan(base, front, stepX, stepZ);
        }
        return null;
    }

    /**
     * True if {@code block} stands flush on the ground: the surface right under it is at most {@link #MAX_COVER_GAP}
     * below its own floor (a full block or a path under it, not a slab, the lower half of a step or a carpet).
     */
    private static boolean flush(Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        RayTraceResult ground = block.getWorld().rayTraceBlocks(center, new Vector(0, -1, 0), 0.5 + MAX_COVER_GAP,
                FluidCollisionMode.NEVER, true);
        return ground != null && ground.getHitPosition().getY() >= block.getY() - MAX_COVER_GAP - 1.0E-6;
    }

    /** True if the owner sees the cover spot from its waist (its body or its head block). */
    private static boolean inSight(Location eye, Block base) {
        Location body = base.getLocation().add(0.5, 0.5, 0.5);
        return Spots.clearPath(eye, body) || Spots.clearPath(eye, body.clone().add(0, 1, 0));
    }

    /** The block face pointing the most towards {@code way} (horizontal). */
    private static BlockFace front(Vector way) {
        if (Math.abs(way.getX()) > Math.abs(way.getZ())) {
            return way.getX() > 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return way.getZ() > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** Minecraft's yaw of a block face (0 = south, 90 = west). */
    private static float yawOf(BlockFace face) {
        return switch (face) {
            case WEST -> 90;
            case NORTH -> 180;
            case EAST -> 270;
            default -> 0;
        };
    }

    /** Air (or grass, flowers...) inside the arena with nobody standing in it. */
    private boolean placeable(Block block) {
        if (!(block.isEmpty() || block.isReplaceable())
                || !ctx().isInsideArena(block.getLocation().add(0.5, 0.5, 0.5))) {
            return false;
        }
        // Every body that reaches into the block, wherever its feet are (a bigger box first, then the real overlap).
        BoundingBox box = BoundingBox.of(block);
        for (Entity entity : block.getWorld().getNearbyEntities(box.clone().expand(2.5))) {
            if (entity instanceof LivingEntity && entity.getBoundingBox().overlaps(box)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The halves stand up as a doll of blocks: the body with the head on top (two high) and an ornate block at each
     * side, all temporary; then the face, the seam and the flower are painted on. False if the body block was taken.
     */
    private boolean raiseCover(Player owner, CoverPlan plan, boolean golden) {
        long ticks = Math.max(1, params().ticks("cover_duration"));
        Block base = plan.base();
        if (!placeable(base) || !flush(base) || !ctx().placeTempBlock(base, LACQUER.createBlockData(), ticks)) {
            return false;
        }
        Block head = base.getRelative(BlockFace.UP);
        boolean headUp = placeable(head) && ctx().placeTempBlock(head, (golden ? GILT : LACQUER).createBlockData(),
                ticks);
        for (int sign : new int[] {1, -1}) {
            Block side = base.getRelative(plan.stepX() * sign, 0, plan.stepZ() * sign);
            if (!side.equals(base) && placeable(side) && flush(side)) {
                BlockData data = ORNATE.createBlockData();
                if (data instanceof Directional directional && directional.getFaces().contains(plan.front())) {
                    directional.setFacing(plan.front());
                }
                ctx().placeTempBlock(side, data, ticks);
            }
        }
        paint(owner, plan, headUp, ticks);
        Location body = base.getLocation().add(0.5, 0.5, 0.5);
        World world = body.getWorld();
        world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 10), 0.45, 0.3, 0.45, 0.05,
                LACQUER.createBlockData());
        Vfx.ring(ctx(), base.getLocation().add(0.5, 0.05, 0.5), 0.9, 14, 0, Particle.DUST, GOLD_SMALL, false);
        Fx.sound(body, Sound.BLOCK_WOOD_PLACE, 1.0f, 0.8f + Fx.jitter(0.06f));
        Fx.sound(body, Sound.BLOCK_BAMBOO_WOOD_DOOR_CLOSE, 0.7f, 1.3f + Fx.jitter(0.06f));
        ctx().runLater(owner, ticks, () -> {
            // The blocks come back on their own; a puff of lacquer dust marks it.
            world.spawnParticle(Particle.POOF, body.clone().add(0, 0.3, 0), Vfx.n(ctx(), 8), 0.4, 0.5, 0.4, 0.02);
            world.spawnParticle(Particle.DUST, body.clone().add(0, 0.3, 0), Vfx.n(ctx(), 8), 0.4, 0.5, 0.4, 0,
                    RED_SMALL);
            Fx.sound(body, Sound.BLOCK_WOOD_FALL, 0.8f, 1.1f + Fx.jitter(0.1f));
        });
        return true;
    }

    /** The cover's paint: a cream face with two eyes on the head, a gold seam and a cornflower on the belly. */
    private void paint(Player owner, CoverPlan plan, boolean headUp, long ticks) {
        float yaw = yawOf(plan.front());
        Quaternionf rotation = rotation(yaw, 0, 0);
        Location body = plan.base().getLocation().add(0.5, 0.5, 0.5);
        Location head = body.clone().add(0, 1, 0);
        Vector3f unit = new Vector3f(1);
        Vector3f none = new Vector3f();
        long shown = Math.max(1, ticks - 3);
        List<Display> painted = new ArrayList<>();
        if (headUp) {
            for (Part part : COVER_FACE) {
                if (reserve(1)) {
                    painted.add(keepOptional(Displays.block(ctx(), owner, head, part.material().createBlockData(),
                            part.transform(rotation, unit, none), ticks)));
                }
            }
        }
        if (reserve(1)) {
            painted.add(keepOptional(Displays.block(ctx(), owner, body, COVER_SEAM.material().createBlockData(),
                    COVER_SEAM.transform(rotation, unit, none), ticks)));
        }
        if (reserve(1)) {
            Vector3f out = new Quaternionf(rotation).transform(new Vector3f(0, -0.05f, 0.53f));
            painted.add(keepOptional(Displays.item(ctx(), owner, body, new ItemStack(CORNFLOWER),
                    new Transformation(out, new Quaternionf(rotation), new Vector3f(0.55f), new Quaternionf()),
                    ticks)));
        }
        ctx().runLater(owner, shown, () -> {
            for (Display display : painted) {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 3);
                }
            }
        });
    }

    // ------------------------------------------------------------------ regrowth

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        TextDisplay label = badge;
        // Back on the head after it got off (a respawn, a world change); until then it keeps to the head itself.
        if (label != null && label.isValid() && !mountBadge(owner, label)) {
            Displays.moveTo(label, badgeSpot(owner, owner.getHeight()));
        }
        // Every tick: an owner who turns invisible or hidden must not be given away even for a moment.
        refreshBadge(false);
        veil(owner);
        regrow(owner, now);
        ambient(owner, tick);
    }

    /** Seats the badge on the owner's head; true once it rides it. */
    private static boolean mountBadge(Player owner, TextDisplay label) {
        if (owner.equals(label.getVehicle())) {
            return true;
        }
        if (!owner.isValid() || owner.isDead() || !owner.getWorld().equals(label.getWorld())) {
            return false;
        }
        if (label.isInsideVehicle()) {
            label.leaveVehicle();
        }
        return owner.addPassenger(label) && owner.equals(label.getVehicle());
    }

    /** After a quiet while a new shell closes over the owner, one at a time, if there is room to grow. */
    private void regrow(Player owner, long now) {
        if (descending || shells >= maxShells()) {
            return;
        }
        long wait = Math.max(1, params().ticks("regrow_delay"));
        if (now - quietFrom < wait) {
            return;
        }
        if (!roomFor(owner, scaleFor(shells + 1))) {
            if (!cramped) {
                // Said once (the badge keeps showing it): never over every other action bar line for as long as
                // the owner stays under a low ceiling.
                cramped = true;
                refreshBadge(true);
                owner.sendActionBar(Text.mm("<gray>껍데기가 덮일 자리가 비좁습니다 · 머리 위가 트이면 덮입니다</gray>"));
            }
            if (now - lastCrampFx >= CRAMP_FX_PERIOD && !concealed(owner)) {
                lastCrampFx = now;
                // The waiting shell bumps its head: a puff of lacquer chips over the owner.
                Location over = owner.getLocation().add(0, owner.getHeight() + 0.1, 0);
                over.getWorld().spawnParticle(Particle.BLOCK, over, Vfx.n(ctx(), 4), 0.2, 0.05, 0.2, 0.02,
                        LACQUER.createBlockData());
                Fx.sound(over, Sound.BLOCK_BAMBOO_WOOD_HIT, 0.4f, 0.6f + Fx.jitter(0.05f));
            }
            return;
        }
        cramped = false;
        shells++;
        strain = 0;
        quietFrom = now;
        breathUntil = now + BREATH_TICKS;
        applySize(owner);
        refreshBadge(true);
        closeLid(owner);
        owner.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">딸깍</" + GOLD_TAG + "></b> <gray>껍데기가 다시 "
                + "덮였습니다 ·</gray> <white>" + shells + "겹</white>"));
    }

    /**
     * A shell closes over the owner like a lid: the cap comes down from above, the base grows up out of the floor
     * like a cup (its rim rising, its bottom staying on the ground), and they meet at the waist with a click. The
     * closed shell stays on a moment, then shrinks away; it follows the owner all along. The shell is the size of
     * the regrown body (the server has not resized the owner's box yet).
     */
    private void closeLid(Player owner) {
        float width = (float) (bodyWidth() * SHELL_WIDEN);
        float height = (float) bodyHeight();
        Vector3f halfScale = new Vector3f(width, height / 2, width);
        float flat = height / 2 * 0.15f;
        float yaw = owner.getLocation().getYaw();
        Quaternionf facing = rotation(yaw, 0, 0);
        int clickAt = LID_TICKS + 1;
        int fadeAt = clickAt + LID_HOLD;
        int steps = fadeAt + LID_FADE;
        long lifetime = steps + 8;
        // The cap's pivot is its center; the cup's pivot stays on the floor at the owner's feet and its rim rises by
        // its own transformation, so its bottom never leaves the ground while it grows.
        Prop top = new Prop(owner.getLocation().add(0, height * 1.75, 0), TOP_HALF, TOP_HALF.size(), halfScale,
                new Vector3f(), false, yaw, lifetime, false, Sight.WORN);
        Prop bottom = new Prop(owner.getLocation(), BOTTOM_HALF, BOTTOM_HALF.size(), new Vector3f(width, flat, width),
                new Vector3f(0, flat / 2, 0), false, yaw, lifetime, false, Sight.WORN);
        top.glide(FOLLOW_GLIDE);
        bottom.glide(FOLLOW_GLIDE);
        Loop.repeat(ctx(), owner, 1, 1, steps, i -> {
            Location feet = owner.getLocation();
            Location center = feet.clone().add(0, height * 0.5, 0);
            if (i < LID_TICKS) {
                double t = (i + 1.0) / LID_TICKS;
                double eased = 1 - (1 - t) * (1 - t);
                top.moveTo(center.clone().add(0, height * (1 - eased) + height / 4, 0));
                if (i % 2 == 0) {
                    // Every 2 ticks, to where the rim is 2 ticks on: one interpolation flows into the next.
                    double ahead = Math.min(1, (i + 2.0) / LID_TICKS);
                    float rim = (float) (flat + (height / 2 - flat) * (1 - (1 - ahead) * (1 - ahead)));
                    bottom.pose(facing, new Vector3f(width, rim, width), new Vector3f(0, rim / 2, 0), 2);
                }
            } else {
                top.moveTo(center.clone().add(0, height / 4, 0));
            }
            bottom.moveTo(feet);
            if (i == clickAt) {
                Vfx.ring(ctx(), center, width * 0.7, 16, 0, Particle.DUST_COLOR_TRANSITION, GOLD_FLASH, false);
                flowers(center, width * 0.5, 2);
                Fx.sound(center, Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, 1.0f, 1.25f + Fx.jitter(0.05f));
                Fx.sound(center, Sound.BLOCK_BAMBOO_WOOD_BUTTON_CLICK_ON, 1.0f, 1.4f);
                Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 0.8f + 0.2f * shells);
            } else if (i == fadeAt) {
                top.vanish(LID_FADE);
                bottom.vanish(LID_FADE);
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ badge & ambience

    /** Where the badge rides: the top of the owner's box ({@code height} tall); its text floats over it. */
    private static Location badgeSpot(Player owner, double height) {
        return Displays.unrotated(owner.getLocation().add(0, height, 0));
    }

    /** The badge's pose: {@code scale}, lifted {@link #BADGE_LIFT} over the head it rides. */
    private static Transformation badgePose(float scale) {
        return Displays.centered(0, BADGE_LIFT, 0, scale, scale, scale, 0, 0, 0);
    }

    private static Location waist(Player owner) {
        return owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
    }

    private static boolean invisible(Player owner) {
        return owner.isInvisible() || owner.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    /** The shells over the owner's head: red flowers (the outer one paling under strain), gold for the gold. */
    private void refreshBadge(boolean pop) {
        TextDisplay label = badge;
        if (label == null || !label.isValid()) {
            return;
        }
        String mini = badgeMini();
        if (!mini.equals(badgeText)) {
            badgeText = mini;
            label.text(Text.mm(mini));
        }
        if (pop) {
            Displays.animate(label, badgePose(BADGE_SCALE * 1.4f), 2);
            ctx().runLater(player(), 3, () -> Displays.animate(label, badgePose(BADGE_SCALE), 4));
        }
    }

    private String badgeMini() {
        Player owner = player();
        if (invisible(owner)) {
            return "";
        }
        int max = maxShells();
        StringBuilder text = new StringBuilder();
        if (shells <= 0) {
            text.append("<b><").append(FACE_TAG).append(">알맹이</").append(FACE_TAG).append("></b>");
        } else {
            double ratio = Math.clamp(strain / Math.max(0.1, params().num("crack_total")), 0.0, 1.0);
            for (int i = 0; i < shells; i++) {
                boolean outer = i == shells - 1;
                int color = gilded && outer ? GOLD_RGB : RED_RGB;
                if (outer && ratio > 0) {
                    color = mix(color, FACE_RGB, ratio * 0.8);
                }
                String tag = String.format("#%06X", color);
                text.append('<').append(tag).append(">✿</").append(tag).append('>');
            }
        }
        for (int i = Math.max(0, shells); i < max; i++) {
            text.append("<dark_gray>✿</dark_gray>");
        }
        if (shells < max && cramped) {
            text.append(" <gray>↻ 비좁음</gray>");
        } else if (shells < max) {
            long wait = Math.max(1, params().ticks("regrow_delay"));
            long left = Math.max(0, wait - (ctx().currentTick() - quietFrom));
            text.append(" <").append(GOLD_TAG).append(">↻").append((left + 19) / 20).append("</").append(GOLD_TAG)
                    .append('>');
        }
        return text.toString();
    }

    private static int mix(int from, int to, double t) {
        int r = (int) Math.round(((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
        int b = (int) Math.round((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * The badge is hidden from everyone who cannot see the owner (it would give its place away), at once. The models
     * on or coming off its body filter their viewers by the same rule ({@link Prop#seenBy}): when somebody's view of
     * the owner changes, they are asked again right away instead of at the next periodic check.
     */
    private void veil(Player owner) {
        TextDisplay label = badge;
        boolean shown = label != null && label.isValid();
        boolean changed = false;
        Set<UUID> present = new HashSet<>();
        for (Player viewer : owner.getWorld().getPlayers()) {
            if (viewer.equals(owner)) {
                continue;
            }
            UUID id = viewer.getUniqueId();
            present.add(id);
            if (!viewer.canSee(owner)) {
                if (veiled.add(id)) {
                    changed = true;
                    if (shown) {
                        viewer.hideEntity(ctx().plugin(), label);
                    }
                }
            } else if (veiled.remove(id)) {
                changed = true;
                if (shown) {
                    viewer.showEntity(ctx().plugin(), label);
                }
            }
        }
        // Somebody who left the world no longer counts as one the owner hides from (they are veiled again on return).
        changed |= veiled.retainAll(present);
        if (changed) {
            ctx().refreshViewers();
        }
    }

    /** True if the owner is invisible or hidden from somebody: its own glints would give it away. */
    private boolean concealed(Player owner) {
        return invisible(owner) || !veiled.isEmpty();
    }

    /** The gold seam glinting at the waist, the bare core's cream glints, the golden shimmer. */
    private void ambient(Player owner, long tick) {
        if (concealed(owner)) {
            return;
        }
        if (tick % SEAM_PERIOD == 0 && shells > 0) {
            Vfx.ring(ctx(), waist(owner), owner.getWidth() * 0.8, 6 + 2 * shells, tick * 0.2, Particle.DUST,
                    gilded ? GOLD_DUST : GOLD_SMALL, false);
        }
        if (shells <= 0 && tick % 8 == 0) {
            // Over the little head, out of the owner's own eyes.
            Location head = owner.getLocation().add(Fx.jitter(0.3f), owner.getHeight() + 0.12 + Fx.jitter(0.08f),
                    Fx.jitter(0.3f));
            Vfx.dot(head, Particle.DUST, FACE_DUST, false);
        }
        if (goldActive() && tick % 2 == 0) {
            double angle = tick * 0.35;
            double radius = owner.getWidth() * 0.9;
            double y = owner.getHeight() * (0.5 + 0.4 * Math.sin(tick * 0.15));
            Location feet = owner.getLocation();
            for (int s = 0; s < 2; s++) {
                double a = angle + s * Math.PI;
                Vfx.dot(feet.clone().add(Math.cos(a) * radius, y, Math.sin(a) * radius), Particle.DUST, GOLD_DUST,
                        false);
            }
            if (tick % 6 == 0) {
                Vfx.dot(feet.clone().add(Fx.jitter(0.5f), owner.getHeight() * 0.6 + Fx.jitter(0.5f),
                        Fx.jitter(0.5f)), Particle.WAX_OFF, null, false);
            }
        }
    }

    // ================================================================== active: a doll inside a doll

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("throw_range"));
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        if (look.lengthSquared() < 1.0E-6) {
            look = Motion.horizontalFacing(eye);
        }
        look.normalize();
        Location aim;
        LivingEntity target = ctx().findTarget(owner, range);
        if (target != null) {
            aim = Fx.body(target);
        } else {
            RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, look, range, FluidCollisionMode.NEVER, true);
            aim = hit != null ? hit.getHitPosition().toLocation(eye.getWorld()) : eye.clone().add(look.multiply(range));
        }
        boolean shed = owner.isSneaking() && shells > 0;
        boolean golden = shed && gilded;
        if (shed) {
            shed(owner);
        } else if (owner.isSneaking()) {
            owner.sendActionBar(Text.mm("<gray>벗어 던질 껍데기가 없습니다 · 그냥 던집니다</gray>"));
        }
        new Throw(owner, hand(owner), aim, shed, golden).start();
        return ActivationResult.success();
    }

    /** Crouching: the owner slips out of its outer shell and throws that. */
    private void shed(Player owner) {
        gilded = false;
        shells--;
        strain = 0;
        cramped = false;
        quietFrom = ctx().currentTick();
        applySize(owner);
        refreshBadge(true);
        Location waist = waist(owner);
        waist.getWorld().spawnParticle(Particle.BLOCK, waist, Vfx.n(ctx(), 8), 0.3, 0.3, 0.3, 0.05,
                LACQUER.createBlockData());
        Fx.sound(waist, Sound.BLOCK_BAMBOO_WOOD_TRAPDOOR_OPEN, 1.0f, 1.1f + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">껍데기를 벗어 던집니다!</" + GOLD_TAG + "></b> <gray>큰 인형 +"
                + AbilityInfo.formatNumber(params().num("shed_bonus")) + " 피해 ·</gray> <white>" + shells
                + "겹</white> <gray>남음</gray>"));
    }

    /**
     * Where the doll leaves the hand: down to the right, an arm's length in front of the eyes (out of the owner's
     * face), never on the far side of a wall right in front of it.
     */
    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector facing = Motion.horizontalFacing(eye);
        // Facing +Z (south), the right hand is -X (west).
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());
        double scale = Body.scaleOf(owner);
        Vector reach = facing.multiply(HAND_REACH * scale).add(right.multiply(0.35 * scale))
                .add(new Vector(0, -0.45 * scale, 0));
        double length = reach.length();
        Vector unit = reach.clone().multiply(1 / length);
        RayTraceResult wall = eye.getWorld().rayTraceBlocks(eye, unit, length, FluidCollisionMode.NEVER, true);
        if (wall != null) {
            double clear = Math.max(0, wall.getHitPosition().distance(eye.toVector()) - 0.2);
            return eye.add(unit.multiply(clear));
        }
        return eye.add(reach);
    }

    /**
     * The big doll in flight: a lob to the aimed point (an enemy, a block, or the end of the range, beyond which it
     * keeps falling), rocking from side to side. It pops open at the first enemy or block it touches.
     */
    private final class Throw implements LongPredicate {

        private final Player owner;
        private final Location start;
        private final Vector delta;
        private final double apex;
        private final int arcTicks;
        private final boolean shed;
        private final Prop doll;
        private final Map<UUID, Double> dealt = new HashMap<>();
        private final Set<UUID> struck = new HashSet<>();
        private Location at;
        private Vector tail = new Vector();
        private boolean opened;

        Throw(Player owner, Location start, Location aim, boolean shed, boolean golden) {
            this.owner = owner;
            this.start = start;
            this.delta = aim.toVector().subtract(start.toVector());
            double length = delta.length();
            this.arcTicks = Math.clamp(Math.round(4 + length * 0.85), 5, 18);
            this.apex = 0.35 + length * 0.09;
            this.shed = shed;
            this.at = start.clone();
            // It leaves the hand small and grows on its way (a full-size doll would fill the owner's view).
            float size = (float) DOLL_HEIGHTS[0];
            this.doll = new Prop(start, DOLL, DOLL_PARTS[0], new Vector3f(size * THROW_START_SCALE), golden,
                    yawOf(delta), MAX_FLIGHT + 30, true);
        }

        void start() {
            Fx.sound(start, Sound.ENTITY_WITCH_THROW, 0.9f, 1.2f + Fx.jitter(0.08f));
            Fx.sound(start, Sound.BLOCK_BAMBOO_WOOD_HIT, 0.6f, 1.4f + Fx.jitter(0.08f));
            Vfx.cloud(ctx(), start, Particle.DUST, 5, 0.12, 0, GOLD_SMALL, false);
            Loop.repeat(ctx(), owner, 1, 1, MAX_FLIGHT, this, () -> {
                if (!opened) {
                    open(show(at), null);
                }
            });
        }

        @Override
        public boolean test(long i) {
            if (i == 0) {
                // Out of the hand, it grows to full size (a tick after its spawn, so the client animates it).
                doll.pose(rotation(yawOf(delta), 0, 0), new Vector3f((float) DOLL_HEIGHTS[0]), THROW_GROW);
            }
            Location next;
            if (i < arcTicks) {
                double t = (i + 1.0) / arcTicks;
                next = start.clone().add(delta.clone().multiply(t)).add(0, 4 * apex * t * (1 - t), 0);
                if (i == arcTicks - 1) {
                    tail = delta.clone().multiply(1.0 / arcTicks).add(new Vector(0, -4 * apex / arcTicks, 0));
                    Motion.clamp(tail, MAX_THROW_SPEED);
                }
            } else {
                tail.setY(tail.getY() - Motion.GRAVITY).multiply(0.99);
                Motion.clamp(tail, MAX_THROW_SPEED);
                next = at.clone().add(tail);
            }
            Vector step = next.toVector().subtract(at.toVector());
            double length = step.length();
            if (length > 1.0E-6) {
                Vector unit = step.clone().multiply(1 / length);
                RayTraceResult wall = at.getWorld().rayTraceBlocks(at, unit, length, FluidCollisionMode.NEVER, true);
                if (wall != null) {
                    // It pops where it struck, backed off the block and standing on the ground if that was the floor.
                    Location impact = wall.getHitPosition().toLocation(at.getWorld()).subtract(unit.multiply(0.35));
                    open(show(impact), null);
                    return false;
                }
            }
            LivingEntity hit = touching(at, next);
            if (hit != null) {
                open(show(next), hit);
                return false;
            }
            if (!ctx().isInsideArena(next)) {
                open(show(at), null);
                return false;
            }
            if (i % 2 == 0 && i > 0) {
                // Rocking slower than it is posed (every 2 ticks), so the rock reads as one smooth sway.
                float roll = (float) (Math.sin(i * ROCK_RATE) * ROCK_DEGREES);
                doll.pose(rotation(yawOf(step), 0, roll), doll.scale(), 2);
            }
            at = next;
            Location shown = show(at);
            Vfx.dot(shown, Particle.DUST, i % 2 == 0 ? RED_DUST : GOLD_SMALL, false);
            if (doll.isEmpty()) {
                ghost(shown, DOLL_HEIGHTS[0]);
            }
            return true;
        }

        /**
         * Shows the doll at {@code spot} (its flight point), lifted where its model would dip into the ground it
         * skims; returns where it is shown.
         */
        private Location show(Location spot) {
            Location shown = doll.above(spot.clone());
            doll.moveTo(shown);
            return shown;
        }

        /**
         * The first enemy whose body the doll touches between two points of its flight. It passes through an enemy
         * hidden from the owner: popping on it would give its place away.
         */
        private @Nullable LivingEntity touching(Location from, Location to) {
            Location mid = from.clone().add(to).multiply(0.5);
            for (Location point : List.of(mid, to)) {
                for (LivingEntity enemy : ctx().enemiesNear(owner, point, DOLL_HIT)) {
                    if (seen(owner, enemy)) {
                        return enemy;
                    }
                }
            }
            return null;
        }

        /** "퐁": the lid flies off, everyone around takes the big doll's blow, the middle doll gets ready to hop. */
        private void open(Location center, @Nullable LivingEntity direct) {
            if (opened) {
                return;
            }
            opened = true;
            double damage = Math.max(0, params().num("big_damage")) + (shed ? Math.max(0, params().num("shed_bonus"))
                    : 0);
            LivingEntity first = direct;
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, Math.max(0, params().num("big_radius")))) {
                if (strike(enemy, damage) && first == null) {
                    first = enemy;
                }
            }
            if (direct != null && !struck.contains(direct.getUniqueId())) {
                strike(direct, damage);
            }
            openFx(center);
            LivingEntity last = first;
            ctx().runLater(owner, POP_DELAY, () -> next(1, center, last));
        }

        private void openFx(Location center) {
            World world = center.getWorld();
            float size = (float) DOLL_HEIGHTS[0];
            Quaternionf facing = rotation(yawOf(delta), 0, 0);
            // The lid (head and face) flies up spinning, the body squashes and is gone.
            for (int index : new int[] {HEAD, FACE_PART}) {
                doll.posePart(index, rotation(yawOf(delta) + 150, 0, 25), new Vector3f(size),
                        new Vector3f(0, size * 0.75f, 0), 5);
            }
            // Squashed onto its own bottom (the model spans -0.5..0.5 of its size): the base stays where it was.
            for (int index = 0; index < DOLL_PARTS[0]; index++) {
                if (index != HEAD && index != FACE_PART) {
                    doll.posePart(index, facing, new Vector3f(size * 1.12f, size * 0.7f, size * 1.12f),
                            new Vector3f(0, -size * 0.15f, 0), 2);
                }
            }
            ctx().runLater(owner, 5, () -> doll.vanish(4));
            double radius = Math.max(0.3, params().num("big_radius"));
            Vfx.dot(center, Particle.FLASH, GOLD, false);
            Vfx.ring(ctx(), center, radius, 20, 0, Particle.DUST_COLOR_TRANSITION, GLOW, false);
            Vfx.ring(ctx(), center, radius * 0.6, 12, 0.3, Particle.DUST, GOLD_DUST, false);
            world.spawnParticle(Particle.BLOCK, center, Vfx.n(ctx(), 10), 0.25, 0.25, 0.25, 0.1,
                    LACQUER.createBlockData());
            world.spawnParticle(Particle.POOF, center, Vfx.n(ctx(), 5), 0.2, 0.2, 0.2, 0.03);
            flowers(center, radius * 0.7, 3);
            Fx.sound(center, Sound.ENTITY_CHICKEN_EGG, 1.2f, 0.8f + Fx.jitter(0.06f));
            Fx.sound(center, Sound.BLOCK_DECORATED_POT_INSERT, 1.0f, 1.3f + Fx.jitter(0.06f));
            Fx.sound(center, Sound.BLOCK_BAMBOO_WOOD_TRAPDOOR_OPEN, 0.9f, 1.5f + Fx.jitter(0.06f));
            Fx.sound(center, Sound.UI_HUD_BUBBLE_POP, 1.0f, 0.8f);
            pop(owner, center.clone().add(0, 0.7, 0), "<b><" + RED_TAG + ">퐁!</" + RED_TAG + "></b>", 1.4f, false);
        }

        /** Stage {@code stage} (1 middle, 2 small) hops out of the doll before it at {@code from}. */
        void next(int stage, Location from, @Nullable LivingEntity last) {
            if (stage >= DOLL_HEIGHTS.length) {
                return;
            }
            LivingEntity target = pick(from, last);
            if (target == null) {
                fizzle(stage, from);
                return;
            }
            new Hop(this, stage, from, target).start();
        }

        /**
         * The next doll's enemy within {@code hop_range}: one the throw has not hurt yet, then any other than the last
         * one, then the last one; the nearest first. Never an enemy hidden from the owner or behind a wall, nor one
         * that already took the most one throw may deal.
         */
        private @Nullable LivingEntity pick(Location from, @Nullable LivingEntity last) {
            Location eye = from.clone().add(0, 0.3, 0);
            LivingEntity best = null;
            int bestRank = Integer.MAX_VALUE;
            double bestDistance = Double.MAX_VALUE;
            for (LivingEntity enemy : ctx().enemiesNear(owner, from, Math.max(0, params().num("hop_range")))) {
                if (!seen(owner, enemy) || remaining(enemy) <= 1.0E-9 || !Spots.clearPath(eye, Fx.body(enemy))) {
                    continue;
                }
                int rank = !struck.contains(enemy.getUniqueId()) ? 0 : enemy.equals(last) ? 2 : 1;
                double distance = enemy.getLocation().distanceSquared(from);
                if (rank < bestRank || rank == bestRank && distance < bestDistance) {
                    best = enemy;
                    bestRank = rank;
                    bestDistance = distance;
                }
            }
            return best;
        }

        /** What the throw may still deal to {@code enemy}: the cap, plus the shed shell's bonus on top of it. */
        private double remaining(LivingEntity enemy) {
            double cap = Math.max(0, params().num("doll_cap")) + (shed ? Math.max(0, params().num("shed_bonus")) : 0);
            return cap - dealt.getOrDefault(enemy.getUniqueId(), 0.0);
        }

        /** Deals up to {@code amount} to an enemy, within the throw's cap per enemy. True if it was hit. */
        boolean strike(LivingEntity enemy, double amount) {
            double dealtNow = Math.min(amount, remaining(enemy));
            if (!(dealtNow > 1.0E-9) || !Combat.isTargetable(ctx(), owner, enemy)) {
                return false;
            }
            Combat.hit(ctx(), owner, enemy, dealtNow);
            dealt.merge(enemy.getUniqueId(), dealtNow, Double::sum);
            struck.add(enemy.getUniqueId());
            return true;
        }

        /** Nobody left to hop at: the remaining dolls pop out, look around and are gone. */
        private void fizzle(int stage, Location from) {
            Location ground = from.clone().add(0, 0.6, 0);
            Spots.snapToGround(ground.getWorld(), ground, 3);
            for (int s = stage; s < DOLL_HEIGHTS.length; s++) {
                float size = (float) DOLL_HEIGHTS[s];
                double side = (s - stage) * 0.45 - 0.2;
                Vector offset = new Vector(-delta.getZ(), 0, delta.getX());
                offset = offset.lengthSquared() < 1.0E-6 ? new Vector(side, 0, 0) : offset.normalize().multiply(side);
                Location spot = ground.clone().add(offset).add(0, size / 2, 0);
                Prop little = new Prop(spot, DOLL, DOLL_PARTS[s], new Vector3f(size * 0.2f), false, yawOf(delta), 30,
                        false);
                float full = size;
                ctx().runLater(owner, 1, () -> little.pose(rotation(yawOf(delta), 0, 0), new Vector3f(full), 3));
                ctx().runLater(owner, 6, () -> little.pose(rotation(yawOf(delta) + 40, 0, 0), new Vector3f(full), 4));
                ctx().runLater(owner, 12, () -> little.vanish(5));
            }
            Fx.sound(ground, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 0.7f, 0.6f);
            pop(owner, ground.clone().add(0, 1.0, 0), "<gray>…?</gray>", 1.0f, false);
        }
    }

    /**
     * A smaller doll hopping from the doll before it onto its enemy in a short arc that follows the enemy, stopped by
     * walls. On landing it strikes (the small one also slows), and the next doll pops out of it. An enemy that vanishes
     * from the owner's sight (stealth) is lost: the doll stops following it and drops where it is.
     */
    private final class Hop implements LongPredicate {

        private final Throw thrown;
        private final int stage;
        private final Location from;
        private final LivingEntity target;
        private final int ticks;
        private final double apex;
        private final Prop doll;
        private final float size;
        private Location at;
        private Location end;
        /**
         * The way it faces in flight: at its enemy, or (a hop that starts on top of it, onto the enemy the doll before
         * hit) the way the doll was thrown. It turns after a moving enemy only while that one is still a way off, so
         * it never spins round on the spot over the enemy it lands on.
         */
        private float yaw;

        Hop(Throw thrown, int stage, Location from, LivingEntity target) {
            this.thrown = thrown;
            this.stage = stage;
            this.from = from.clone();
            this.target = target;
            this.end = aimOf(target);
            double distance = end.distance(from);
            this.ticks = Math.clamp(Math.round(6 + distance * 1.2), HOP_MIN_TICKS, HOP_MAX_TICKS);
            this.apex = 0.8 + distance * 0.12;
            this.at = this.from.clone();
            this.size = (float) DOLL_HEIGHTS[stage];
            Vector way = level(end.toVector().subtract(from.toVector()));
            this.yaw = way.lengthSquared() >= HOP_AIM_MIN * HOP_AIM_MIN ? yawOf(way) : yawOf(thrown.delta);
            this.doll = new Prop(from, DOLL, DOLL_PARTS[stage], new Vector3f(size * 0.3f), false, yaw, ticks + 30,
                    true);
        }

        private static Location aimOf(LivingEntity target) {
            return target.getLocation().add(0, Math.min(0.9, target.getHeight() * 0.45), 0);
        }

        void start() {
            Player owner = thrown.owner;
            Fx.sound(from, Sound.ENTITY_RABBIT_JUMP, 1.0f, 1.0f + 0.3f * stage + Fx.jitter(0.05f));
            Fx.sound(from, Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.4f + 0.2f * stage);
            Vfx.cloud(ctx(), from, Particle.DUST, 4, 0.1, 0, GOLD_SMALL, false);
            Loop.repeat(ctx(), owner, 1, 1, ticks, this, null);
        }

        @Override
        public boolean test(long i) {
            if (!seen(thrown.owner, target)) {
                lose();
                return false;
            }
            // It follows its enemy, but not one that blinked far away (it lands where it was heading and misses).
            if (Combat.isTargetable(ctx(), thrown.owner, target) && target.getWorld().equals(from.getWorld())
                    && target.getLocation().distance(from) <= Math.max(1, params().num("hop_range")) * 1.5) {
                end = aimOf(target);
            }
            double t = (i + 1.0) / ticks;
            Location next = from.clone().add(end.toVector().subtract(from.toVector()).multiply(t))
                    .add(0, 4 * apex * t * (1 - t), 0);
            Vector step = next.toVector().subtract(at.toVector());
            double length = step.length();
            if (length > 1.0E-6) {
                Vector unit = step.clone().multiply(1 / length);
                RayTraceResult wall = at.getWorld().rayTraceBlocks(at, unit, length, FluidCollisionMode.NEVER, true);
                if (wall != null) {
                    bonk(wall.getHitPosition().toLocation(at.getWorld()).subtract(unit.multiply(0.2)));
                    return false;
                }
            }
            at = next;
            Vector left = level(end.toVector().subtract(at.toVector()));
            if (left.lengthSquared() > HOP_REAIM * HOP_REAIM) {
                yaw = yawOf(left);
            }
            if (i == 0) {
                doll.pose(rotation(yaw, 0, 0), new Vector3f(size * 0.85f, size * 1.2f, size * 0.85f), 2);
            } else if (i % 2 == 0) {
                doll.pose(rotation(yaw, (float) (t * 30 - 15), 0), new Vector3f(size), 2);
            }
            doll.moveTo(doll.above(at.clone()));
            Vfx.dot(at, Particle.DUST, stage == 1 ? RED_SMALL : BLUE_DUST, false);
            if (doll.isEmpty()) {
                ghost(at, size);
            }
            if (i + 1 >= ticks) {
                land();
                return false;
            }
            return true;
        }

        /** The doll lands on its enemy: a knock (and, the small one, a slow). */
        private void land() {
            Player owner = thrown.owner;
            boolean close = seen(owner, target) && Combat.isTargetable(ctx(), owner, target)
                    && at.distance(Fx.body(target)) <= HOP_HIT;
            double damage = Math.max(0, params().num(stage == 1 ? "middle_damage" : "small_damage"));
            boolean hit = close && thrown.strike(target, damage);
            // It lands facing the way it flew.
            doll.pose(rotation(yaw, 0, 0), new Vector3f(size * 1.2f, size * 0.75f, size * 1.2f), 2);
            ctx().runLater(owner, 4, () -> doll.vanish(4));
            if (hit) {
                if (stage == DOLL_HEIGHTS.length - 1) {
                    slow(owner, target);
                }
                Location body = Fx.body(target);
                Vfx.burst(ctx(), body, PALETTE, 0.35, Particle.CRIT, false);
                Fx.sound(body, Sound.BLOCK_BAMBOO_WOOD_HIT, 1.0f, 1.2f + 0.3f * stage + Fx.jitter(0.05f));
                Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 0.9f, stage == 1 ? 1.2f : 1.6f);
                if (seen(owner, target)) {
                    pop(owner, body.clone().add(0, 0.9, 0), "<b><" + (stage == 1 ? RED_TAG : GOLD_TAG) + ">"
                            + (stage == 1 ? "깡!" : "콩!") + "</" + (stage == 1 ? RED_TAG : GOLD_TAG) + "></b>",
                            1.1f, false);
                }
            } else {
                at.getWorld().spawnParticle(Particle.POOF, at, Vfx.n(ctx(), 3), 0.1, 0.1, 0.1, 0.02);
                Fx.sound(at, Sound.BLOCK_WOOD_FALL, 0.7f, 1.4f);
            }
            Location spot = at.clone();
            ctx().runLater(owner, POP_DELAY, () -> thrown.next(stage + 1, spot, target));
        }

        /** Its enemy vanished: the doll stops homing and drops straight down where it is, at a loss ("…?"). */
        private void lose() {
            Player owner = thrown.owner;
            Location top = at.clone();
            Location ground = at.clone();
            Spots.snapToGround(ground.getWorld(), ground, 8);
            doll.pose(rotation(yaw, 0, 0), new Vector3f(size), 2);
            Location rest = doll.above(ground);
            Vector drop = rest.toVector().subtract(top.toVector());
            Loop.repeat(ctx(), owner, 1, 1, DROP_TICKS, k -> {
                double t = (k + 1.0) / DROP_TICKS;
                doll.moveTo(top.clone().add(drop.clone().multiply(t * t)));
                return true;
            }, () -> {
                rest.getWorld().spawnParticle(Particle.POOF, rest, Vfx.n(ctx(), 3), 0.1, 0.05, 0.1, 0.02);
                Fx.sound(rest, Sound.BLOCK_WOOD_FALL, 0.7f, 1.1f + Fx.jitter(0.05f));
                pop(owner, rest.clone().add(0, 0.6, 0), "<gray>…?</gray>", 0.9f, false);
                ctx().runLater(owner, 3, () -> doll.vanish(4));
                ctx().runLater(owner, POP_DELAY, () -> thrown.next(stage + 1, rest, null));
            });
        }

        /** "통": the doll bounces off a wall and drops; the next one pops out of it there. */
        private void bonk(Location wall) {
            Player owner = thrown.owner;
            Location spot = doll.above(wall.clone());
            doll.moveTo(spot);
            ctx().runLater(owner, 2, () -> doll.vanish(4));
            spot.getWorld().spawnParticle(Particle.BLOCK, spot, Vfx.n(ctx(), 6), 0.15, 0.15, 0.15, 0.05,
                    LACQUER.createBlockData());
            Fx.sound(spot, Sound.BLOCK_BAMBOO_WOOD_HIT, 1.0f, 0.7f + Fx.jitter(0.05f));
            pop(owner, spot.clone().add(0, 0.6, 0), "<gray>통</gray>", 1.0f, false);
            ctx().runLater(owner, POP_DELAY, () -> thrown.next(stage + 1, spot, null));
        }
    }

    /** The smallest doll's knock slows its enemy for a moment (a blue flower twirls at its feet). */
    private void slow(Player owner, LivingEntity target) {
        long ticks = params().ticks("small_slow_duration");
        double amount = Math.clamp(params().num("small_slow") / 100.0, 0.0, 1.0);
        if (ticks > 0 && amount > 0 && Combat.isTargetable(ctx(), owner, target)) {
            ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -amount, Operation.MULTIPLY_SCALAR_1, ticks);
            if (seen(owner, target)) {
                Vfx.ring(ctx(), target.getLocation().add(0, 0.1, 0), 0.5, 10, 0, Particle.DUST, BLUE_DUST, false);
            }
            Fx.sound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.8f);
        }
    }

    // ================================================================== ultimate: from the beginning

    @Override
    public ActivationResult onUltimate() {
        if (descending) {
            return ActivationResult.fail("거대 인형이 이미 내려오고 있습니다");
        }
        if (goldActive()) {
            return ActivationResult.fail("이미 금빛 껍데기를 두르고 있습니다");
        }
        Player owner = player();
        if (!roomFor(owner, scaleFor(maxShells() + 1))) {
            return ActivationResult.fail("거대 인형이 내려앉을 공간이 없습니다");
        }
        descending = true;
        new Descent(owner).start();
        return ActivationResult.success();
    }

    /** The giant doll falls out of the sky onto its own shadow around the owner. */
    private final class Descent implements LongPredicate {

        private final Player owner;
        private final long ticks;
        private final Prop giant;
        private final double radius;
        private final float width;
        /**
         * The way it faces as it starts to fall (the owner's facing then): it turns a quarter from there on the way
         * down, whatever the owner does with its mouse meanwhile.
         */
        private final float baseYaw;

        Descent(Player owner) {
            this.owner = owner;
            this.ticks = Math.max(1, params().ticks("drop_delay"));
            this.radius = Math.max(0, params().num("slam_radius"));
            this.width = (float) (GIANT_HEIGHT * DOLL.getFirst().sx());
            this.baseYaw = owner.getLocation().getYaw();
            // It comes down onto the owner: nobody who cannot see the owner sees it either.
            this.giant = new Prop(spot(0), DOLL, DOLL.size(), new Vector3f((float) GIANT_HEIGHT), new Vector3f(),
                    false, baseYaw, ticks + SETTLE_TICKS + 30, true, Sight.VEILED);
        }

        /** The giant's center when it has fallen {@code t} (0..1) of the way. */
        private Location spot(double t) {
            double height = GIANT_START * (1 - t * t);
            return owner.getLocation().add(0, height + GIANT_HEIGHT / 2, 0);
        }

        void start() {
            Location feet = owner.getLocation();
            Fx.sound(feet, Sound.BLOCK_WOODEN_DOOR_OPEN, 1.2f, 0.5f);
            Fx.sound(feet, Sound.BLOCK_BELL_RESONATE, 0.8f, 0.7f);
            Fx.sound(feet, Sound.ENTITY_BREEZE_INHALE, 1.0f, 0.6f);
            owner.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">처음부터 다시!</" + GOLD_TAG + "></b>"));
            Loop.repeat(ctx(), owner, 1, 1, ticks, this, this::land);
        }

        @Override
        public boolean test(long i) {
            double t = (i + 1.0) / ticks;
            giant.moveTo(spot(t));
            if (i % 2 == 0) {
                // Every 2 ticks, to the turn it has 2 ticks on: each interpolation ends as the next one starts, and
                // the last one ends on the quarter turn it lands with.
                double ahead = Math.min(1, (i + 2.0) / ticks);
                giant.pose(rotation(baseYaw + (float) (ahead * 90), 0, 0), giant.scale(), 2);
            }
            Location ground = owner.getLocation().add(0, 0.08, 0);
            // The doll's shadow grows and darkens under it; the blast radius rings it.
            double shadow = width / 2 * (0.3 + 0.7 * t);
            Vfx.ring(ctx(), ground, shadow, 14, i * 0.3, Particle.DUST, SHADOW, true);
            Vfx.ring(ctx(), ground, shadow * 0.55, 8, -i * 0.3, Particle.DUST, SHADOW, true);
            if (i % 2 == 0) {
                Vfx.ring(ctx(), ground, radius, 22, i * 0.1, Particle.DUST_COLOR_TRANSITION, RED_FADE, true);
                Vfx.ring(ctx(), ground, radius - 0.15, 14, -i * 0.1, Particle.DUST, GOLD_SMALL, true);
            }
            return true;
        }

        private void land() {
            descending = false;
            giant.moveTo(owner.getLocation().add(0, GIANT_HEIGHT / 2, 0));
            // Over the owner now, around its camera; it follows the body as the other clients draw it.
            giant.hideFromOwner();
            giant.glide(FOLLOW_GLIDE);
            slam(owner, giant, baseYaw + 90);
        }
    }

    /**
     * The giant doll covers the owner: enemies around are knocked out, every shell comes back and a golden one. The
     * giant keeps the facing {@code yaw} it landed with.
     */
    private void slam(Player owner, Prop giant, float yaw) {
        Location feet = owner.getLocation();
        Location center = Fx.body(owner);
        double radius = Math.max(0, params().num("slam_radius"));
        double damage = Math.max(0, params().num("slam_damage"));
        double distance = Math.max(0, params().num("slam_push"));
        double speed = distance * (1 - AIR_DRAG) / (1 - Math.pow(AIR_DRAG, PUSH_AIR_TICKS));
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            Combat.hit(ctx(), owner, enemy, damage);
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(feet.toVector()));
            if (away.lengthSquared() < 1.0E-6) {
                away = Motion.horizontalFacing(feet);
            }
            if (Motion.push(enemy, away.multiply(speed).setY(PUSH_LIFT), MAX_SPEED)) {
                enemy.setFallDistance(0);
            }
        }

        // Every shell back (as far as there is room to grow), then the golden one on top.
        long now = ctx().currentTick();
        int max = maxShells();
        int fit = shells;
        for (int count = max + 1; count > shells; count--) {
            if (roomFor(owner, scaleFor(count))) {
                fit = count;
                break;
            }
        }
        shells = Math.max(shells, fit);
        gilded = shells > max;
        strain = 0;
        graceUntil = Long.MIN_VALUE / 2;
        quietFrom = now;
        breathUntil = now + BREATH_TICKS;
        long gold = Math.max(1, params().ticks("gold_duration"));
        goldUntil = now + gold;
        shardsLeft = Math.max(0, params().integer("shard_max"));
        pendingShards.clear();
        applySize(owner);
        refreshBadge(true);

        // The giant squashes flat onto the floor, then settles over the owner and shrinks onto its body (the regrown
        // body: the server resizes the owner's box only on its next tick), leaving a golden shimmer. Its pivot stays
        // half its height over the feet and follows them every tick until it is gone; the lift keeps its bottom on
        // the ground (the model spans -0.5..0.5 of its size) while it squashes and shrinks.
        float body = (float) bodyHeight();
        float giantHeight = (float) GIANT_HEIGHT;
        Quaternionf facing = rotation(yaw, 0, 0);
        giant.pose(facing, new Vector3f(giantHeight * 1.05f, giantHeight * 0.88f, giantHeight * 1.05f),
                new Vector3f(0, -giantHeight * 0.06f, 0), 2);
        long settleAt = 3;
        long goneAt = settleAt + SETTLE_TICKS;
        Loop.repeat(ctx(), owner, 1, 1, goneAt + 2, k -> {
            long tick = k + 1;
            giant.moveTo(owner.getLocation().add(0, giantHeight / 2, 0));
            if (tick == settleAt) {
                giant.pose(facing, new Vector3f(body * 1.1f), new Vector3f(0, body * 0.55f - giantHeight / 2, 0),
                        SETTLE_TICKS);
            } else if (tick == goneAt) {
                giant.vanish(2);
                Location waist = waist(owner);
                Vfx.sphere(ctx(), waist, owner.getWidth() * 1.1, 30, Particle.DUST_COLOR_TRANSITION, GOLD_FLASH,
                        true);
                Fx.sound(waist, Sound.ITEM_ARMOR_EQUIP_GOLD, 1.0f, 1.0f);
                Fx.sound(waist, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.2f);
            }
            return true;
        });
        World world = feet.getWorld();
        Location ground = feet.clone().add(0, 0.1, 0);
        Vfx.dot(center, Particle.FLASH, GOLD, true);
        Vfx.shockwave(ctx(), owner, ground, 0.6, Math.max(0.6, radius), 6, Particle.DUST_COLOR_TRANSITION, RED_FADE,
                true);
        world.spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 30), radius * 0.4, 0.2, radius * 0.4, 0.15,
                LACQUER.createBlockData(), true);
        world.spawnParticle(Particle.DUST, ground, Vfx.n(ctx(), 24), radius * 0.45, 0.3, radius * 0.45, 0, GOLD_DUST,
                true);
        for (int k = 0; k < 6; k++) {
            double angle = k * Math.PI / 3;
            flowers(ground.clone().add(Math.cos(angle) * radius * 0.7, 0.4, Math.sin(angle) * radius * 0.7), 0.2, 1);
        }
        Fx.sound(feet, Sound.BLOCK_ANVIL_LAND, 1.0f, 0.5f);
        Fx.sound(feet, Sound.BLOCK_BAMBOO_WOOD_DOOR_CLOSE, 1.5f, 0.5f);
        Fx.sound(feet, Sound.BLOCK_BELL_USE, 1.2f, 0.7f);
        Fx.sound(feet, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.7f);
        owner.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">처음부터 다시!</" + GOLD_TAG + "></b> <gray>껍데기</gray> "
                + "<white>" + shells + "겹</white> <gray>· " + (gilded ? "금빛 껍데기 " + Fx.seconds(gold) + "초"
                : "금빛 껍데기를 두를 자리가 비좁습니다 (" + Fx.seconds(gold) + "초간 금빛 파편)") + "</gray>"));
        Loop.repeat(ctx(), owner, 1, 1, gold, this::goldStep, this::peel);
    }

    /** While the gold lasts: cracked shells fling their halves at the nearest enemy. */
    private boolean goldStep(long i) {
        Spill spill;
        while ((spill = pendingShards.poll()) != null) {
            fling(player(), spill);
        }
        return true;
    }

    /** The gold runs out: the golden shell (if it is still on) peels off. */
    private void peel() {
        Player owner = player();
        goldUntil = Long.MIN_VALUE / 2;
        shardsLeft = 0;
        Spill spill;
        while ((spill = pendingShards.poll()) != null) {
            spill(owner, spill);
        }
        if (gilded) {
            gilded = false;
            shells--;
            applySize(owner);
            Location waist = waist(owner);
            waist.getWorld().spawnParticle(Particle.FALLING_DUST, waist, Vfx.n(ctx(), 14), owner.getWidth() * 0.5,
                    owner.getHeight() * 0.4, owner.getWidth() * 0.5, 0, GILT.createBlockData());
            Fx.sound(waist, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.8f);
            Fx.sound(waist, Sound.ITEM_ARMOR_EQUIP_GOLD, 0.8f, 0.6f);
            owner.sendActionBar(Text.mm("<gray>금빛 껍데기가 벗겨졌습니다 ·</gray> <white>" + shells + "겹</white>"));
        }
        refreshBadge(true);
    }

    /** The halves of a shell cracked under gold fly at the nearest enemy (or stand as cover if there is none). */
    private void fling(Player owner, Spill spill) {
        int count = Math.min(HALVES, shardsLeft);
        Location origin = spill.origin();
        LivingEntity target = null;
        for (LivingEntity enemy : ctx().enemiesNear(owner, origin, Math.max(0, params().num("shard_range")))) {
            if (seen(owner, enemy) && Spots.clearPath(origin, Fx.body(enemy))) {
                target = enemy; // nearest first
                break;
            }
        }
        if (count <= 0 || target == null) {
            spill(owner, spill);
            return;
        }
        Vector3f halfScale = new Vector3f(spill.width(), spill.height() / 2, spill.width());
        List<Prop> halves = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            shardsLeft--;
            List<Part> model = k == 0 ? TOP_HALF : BOTTOM_HALF;
            Location start = origin.clone().add(0, (k == 0 ? 1 : -1) * spill.height() / 4, 0);
            // Facing its enemy from the start (its flight keeps that facing), out of the owner's own view while it
            // leaves its body.
            float toward = yawOf(Fx.body(target).toVector().subtract(start.toVector()));
            Prop half = new Prop(start, model, model.size(), halfScale, new Vector3f(), true, toward,
                    SHARD_TICKS + 20, true, Sight.LEAVING);
            halves.add(half);
            new Shard(owner, half, start, target).start();
        }
        ctx().runLater(owner, 2, () -> showToOwner(halves.toArray(Prop[]::new)));
    }

    /**
     * A golden half tumbling at its enemy, stopped by walls. An enemy that vanishes from the owner's sight (stealth)
     * is lost: the half stops homing and drops where it is.
     */
    private final class Shard implements LongPredicate {

        private final Player owner;
        private final Prop half;
        private final LivingEntity target;
        private Location at;
        private boolean dropping;

        Shard(Player owner, Prop half, Location start, LivingEntity target) {
            this.owner = owner;
            this.half = half;
            this.target = target;
            this.at = start.clone();
        }

        void start() {
            Fx.sound(at, Sound.ENTITY_WITCH_THROW, 0.9f, 1.4f + Fx.jitter(0.08f));
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.5f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), owner, 1, 1, SHARD_TICKS, this, () -> {
                if (!dropping) {
                    half.vanish(4);
                }
            });
        }

        @Override
        public boolean test(long i) {
            if (!Combat.isTargetable(ctx(), owner, target)) {
                half.vanish(4);
                return false;
            }
            if (!seen(owner, target)) {
                drop();
                return false;
            }
            Location aim = Fx.body(target);
            Vector way = aim.toVector().subtract(at.toVector());
            double distance = way.length();
            if (distance <= SHARD_HIT) {
                hit(aim);
                return false;
            }
            Vector unit = way.multiply(1 / distance);
            double step = Math.min(SHARD_SPEED, distance);
            RayTraceResult wall = at.getWorld().rayTraceBlocks(at, unit, step, FluidCollisionMode.NEVER, true);
            if (wall != null) {
                Location spot = wall.getHitPosition().toLocation(at.getWorld()).subtract(unit.clone().multiply(0.2));
                half.moveTo(half.above(spot.clone()));
                spot.getWorld().spawnParticle(Particle.BLOCK, spot, Vfx.n(ctx(), 6), 0.15, 0.15, 0.15, 0.05,
                        GILT.createBlockData());
                Fx.sound(spot, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, 0.8f);
                half.vanish(4);
                return false;
            }
            Location previous = at;
            at = at.clone().add(unit.clone().multiply(step));
            // End over end towards the enemy, never dipping into the floor it skims.
            half.pose(rotation(yawOf(unit), (float) (i * 45), 0), half.scale(), 1);
            half.moveTo(half.above(at.clone()));
            Vfx.trail(previous, at, GOLD_TRAIL, 6, false);
            if (i % 2 == 0) {
                Vfx.dot(at, Particle.WAX_OFF, null, false);
            }
            if (half.isEmpty()) {
                ghost(at, 0.6);
            }
            if (Fx.body(target).distance(at) <= SHARD_HIT) {
                hit(Fx.body(target));
                return false;
            }
            return true;
        }

        /**
         * Its enemy vanished: the half drops straight down where it is, posed as it was in flight (no turn towards
         * anything), and breaks into gold dust.
         */
        private void drop() {
            dropping = true;
            Location top = at.clone();
            Location ground = at.clone();
            Spots.snapToGround(ground.getWorld(), ground, 8);
            Location rest = half.above(ground);
            Vector fall = rest.toVector().subtract(top.toVector());
            Loop.repeat(ctx(), owner, 1, 1, DROP_TICKS, k -> {
                double t = (k + 1.0) / DROP_TICKS;
                half.moveTo(top.clone().add(fall.clone().multiply(t * t)));
                return true;
            }, () -> {
                rest.getWorld().spawnParticle(Particle.BLOCK, rest, Vfx.n(ctx(), 5), 0.15, 0.1, 0.15, 0.05,
                        GILT.createBlockData());
                Fx.sound(rest, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.8f, 0.7f + Fx.jitter(0.05f));
                half.vanish(4);
            });
        }

        private void hit(Location body) {
            Combat.hit(ctx(), owner, target, Math.max(0, params().num("shard_damage")));
            half.moveTo(body);
            half.vanish(3);
            Vfx.burst(ctx(), body, GOLD_PALETTE, 0.4, Particle.WAX_OFF, false);
            Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, 1.2f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.2f + Fx.jitter(0.1f));
        }
    }

    // ================================================================== models in flight

    /** Who sees a model of the doll. */
    private enum Sight {
        /** Everyone (a thrown doll and the dolls inside it). */
        PUBLIC,
        /** Whoever sees the owner, the owner too: it comes down onto the owner's body (the giant doll). */
        VEILED,
        /**
         * Whoever sees the owner, and the owner itself once {@code showToOwner} lets it (the halves springing off its
         * body would fill its view while they leave it).
         */
        LEAVING,
        /** Whoever sees the owner, but not the owner: it closes over its body, around its camera (the lid). */
        WORN
    }

    /** Lets the owner see {@code props} it was kept from ({@link Sight#LEAVING}), at once. */
    private void showToOwner(Prop... props) {
        for (Prop prop : props) {
            prop.ownerSees = true;
        }
        ctx().refreshViewers();
    }

    /** A little model of block displays sharing one pivot (the parts' boxes are posed around it). */
    private final class Prop {

        private final List<BlockDisplay> parts = new ArrayList<>();
        private final List<Part> specs = new ArrayList<>();
        /** Only whoever sees the owner sees it (it sits on or comes off the owner's body). */
        private final boolean veiled;
        /** The owner sees it (read by the viewer filter, see {@link #seenBy}). */
        private boolean ownerSees;
        private Vector3f scale;

        /** A model everyone sees, posed around its pivot {@code at}. */
        Prop(Location at, List<Part> model, int count, Vector3f scale, boolean golden, float yaw, long lifetime,
             boolean essential) {
            this(at, model, count, scale, new Vector3f(), golden, yaw, lifetime, essential, Sight.PUBLIC);
        }

        /**
         * The first {@code count} parts of {@code model}, posed {@code lift} over the pivot {@code at}. An
         * {@code essential} model (a doll that strikes) makes the optional displays give way to it; any other is
         * skipped when the display budget is spent. Its viewers ({@code sight}) are filtered inside the spawn, so
         * nobody it is kept from ever receives it.
         */
        Prop(Location at, List<Part> model, int count, Vector3f scale, Vector3f lift, boolean golden, float yaw,
             long lifetime, boolean essential, Sight sight) {
            this.scale = new Vector3f(scale);
            this.veiled = sight != Sight.PUBLIC;
            this.ownerSees = sight == Sight.PUBLIC || sight == Sight.VEILED;
            int used = Math.min(count, model.size());
            if (!(essential ? makeRoom(used) : reserve(used))) {
                return;
            }
            Quaternionf rotation = rotation(yaw, 0, 0);
            for (Part part : model.subList(0, used)) {
                Material material = golden ? part.golden() : part.material();
                // The kit's teleport duration (2) for a model moved every tick: smooth even when two moves reach a
                // client in one of its ticks.
                BlockDisplay display = Displays.block(ctx(), player(), at, material.createBlockData(),
                        part.transform(rotation, this.scale, lift), lifetime, veiled ? this::seenBy : null);
                if (essential) {
                    keep(display);
                } else {
                    keepOptional(display);
                }
                parts.add(display);
                specs.add(part);
            }
        }

        Vector3f scale() {
            return new Vector3f(scale);
        }

        /** True once nothing of it is left (never spawned, made way or vanished). */
        boolean isEmpty() {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    return false;
                }
            }
            return true;
        }

        void moveTo(Location at) {
            for (BlockDisplay part : parts) {
                Displays.moveTo(part, at);
            }
        }

        void pose(Quaternionf rotation, Vector3f scale, int ticks) {
            pose(rotation, scale, new Vector3f(), ticks);
        }

        /** Poses every part (scaled by {@code scale}, raised by {@code lift} from the pivot). */
        void pose(Quaternionf rotation, Vector3f scale, Vector3f lift, int ticks) {
            this.scale = new Vector3f(scale);
            for (int i = 0; i < parts.size(); i++) {
                Displays.animate(parts.get(i), specs.get(i).transform(rotation, this.scale, lift), ticks);
            }
        }

        /** Poses one part on its own (scaled by {@code scale}, raised by {@code lift}). */
        void posePart(int index, Quaternionf rotation, Vector3f scale, Vector3f lift, int ticks) {
            if (index < parts.size()) {
                Displays.animate(parts.get(index), specs.get(index).transform(rotation, scale, lift), ticks);
            }
        }

        /** How far the lowest corner of the posed model is above its pivot (negative: below it). */
        double lowest() {
            double low = Double.MAX_VALUE;
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    low = Math.min(low, bottomOf(part.getTransformation()));
                }
            }
            return low == Double.MAX_VALUE ? 0 : low;
        }

        /**
         * {@code pivot} (changed in place), raised as far as needed for the posed model to rest on the ground under
         * it instead of reaching into it.
         */
        Location above(Location pivot) {
            double bottom = pivot.getY() + lowest();
            double ground = groundUnder(pivot, bottom);
            if (bottom < ground) {
                pivot.setY(pivot.getY() + ground - bottom + 0.005);
            }
            return pivot;
        }

        /**
         * The viewer filter of a veiled model: the owner as {@link #ownerSees} says, anybody else only while they see
         * the owner (asked again whenever that changes, see {@link #veil}).
         */
        boolean seenBy(Player viewer) {
            Player owner = player();
            return viewer.equals(owner) ? ownerSees : !veiled || viewer.canSee(owner);
        }

        /** Keeps it out of the owner's view from now on (it closed over the owner's body). */
        void hideFromOwner() {
            ownerSees = false;
            ctx().refreshViewers();
        }

        /** How smoothly (in ticks) every client slides it to where it is moved. */
        void glide(int ticks) {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    part.setTeleportDuration(ticks);
                }
            }
        }

        void vanish(int ticks) {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), player(), part, ticks);
                }
            }
        }
    }

    /** The lowest y of a block display's unit cube under {@code pose}, relative to the display's location. */
    private static double bottomOf(Transformation pose) {
        double low = Double.MAX_VALUE;
        Quaternionf left = pose.getLeftRotation();
        Quaternionf right = pose.getRightRotation();
        Vector3f size = pose.getScale();
        Vector3f shift = pose.getTranslation();
        Vector3f corner = new Vector3f();
        for (int c = 0; c < 8; c++) {
            corner.set(c & 1, (c >> 1) & 1, (c >> 2) & 1);
            right.transform(corner);
            corner.mul(size);
            left.transform(corner);
            low = Math.min(low, corner.y + shift.y);
        }
        return low;
    }

    /**
     * The height of the ground right under {@code pivot}, looking down to {@code bottom} (a little below it), or
     * negative infinity when there is none that close. The probe starts a little above the pivot, outside the
     * ground a sinking model may already reach into (and at the pivot itself under a low ceiling).
     */
    private static double groundUnder(Location pivot, double bottom) {
        World world = pivot.getWorld();
        Vector down = new Vector(0, -1, 0);
        for (double lift : new double[] {GROUND_PROBE_LIFT, 0}) {
            Location from = pivot.clone().add(0, lift, 0);
            double reach = from.getY() - bottom + 0.05;
            if (reach <= 0) {
                continue;
            }
            RayTraceResult ground = world.rayTraceBlocks(from, down, reach, FluidCollisionMode.NEVER, true);
            if (ground == null) {
                return Double.NEGATIVE_INFINITY;
            }
            double y = ground.getHitPosition().getY();
            if (y < from.getY() - 1.0E-4) {
                return y;
            }
            // The probe started inside a block (a ceiling right over the pivot): try again from the pivot.
        }
        return Double.NEGATIVE_INFINITY;
    }

    /**
     * Moves {@code prop} from {@code from} to {@code to} in {@code ticks} ticks along an arc {@code arc} blocks high,
     * turning it by {@code yawSpin} degrees about the vertical and tumbling it {@code pitchSpin} degrees forward on
     * the way (its lowest point kept on the ground it rolls over); {@code onArrive} runs at the end.
     */
    private void fly(Player owner, Prop prop, Location from, Location to, int ticks, double arc, float yaw,
                     float yawSpin, float pitchSpin, @Nullable Runnable onArrive) {
        Vector delta = to.toVector().subtract(from.toVector());
        Loop.repeat(ctx(), owner, 1, 1, ticks, i -> {
            double t = (i + 1.0) / ticks;
            prop.pose(rotation(yaw + (float) (yawSpin * t), (float) (pitchSpin * t), 0), prop.scale(), 1);
            prop.moveTo(prop.above(from.clone().add(delta.clone().multiply(t)).add(0, 4 * arc * t * (1 - t), 0)));
            return true;
        }, onArrive);
    }

    // ================================================================== small helpers

    /** A rotation by yaw (0 = south, 90 = west), then pitch (positive tips the front down), then roll (degrees). */
    private static Quaternionf rotation(float yawDeg, float pitchDeg, float rollDeg) {
        return new Quaternionf()
                .rotateY((float) Math.toRadians(-yawDeg))
                .rotateX((float) Math.toRadians(pitchDeg))
                .rotateZ((float) Math.toRadians(rollDeg));
    }

    /** Minecraft's yaw (degrees: 0 = +Z, 90 = -X) of the horizontal part of {@code direction}. */
    private static float yawOf(Vector direction) {
        Vector flat = Motion.horizontal(direction);
        if (flat.lengthSquared() < 1.0E-6) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
    }

    /** The horizontal part of {@code vector}, its length kept (unlike {@link Motion#horizontal}). */
    private static Vector level(Vector vector) {
        return new Vector(vector.getX(), 0, vector.getZ());
    }

    /** Little painted flowers of dust: a gold heart and five red or blue petals, lying flat. */
    private void flowers(Location center, double spread, int count) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int flowers = Vfx.n(ctx(), count);
        for (int f = 0; f < flowers; f++) {
            Location heart = center.clone().add(random.nextDouble(-spread, spread + 1.0E-9),
                    random.nextDouble(-0.2, 0.2), random.nextDouble(-spread, spread + 1.0E-9));
            Vfx.dot(heart, Particle.DUST, GOLD_SMALL, false);
            Particle.DustOptions petal = f % 2 == 0 ? RED_SMALL : BLUE_DUST;
            double phase = random.nextDouble(Math.PI * 2);
            for (int p = 0; p < 5; p++) {
                double angle = phase + p * Math.PI * 2 / 5;
                Vfx.dot(heart.clone().add(Math.cos(angle) * 0.12, 0, Math.sin(angle) * 0.12), Particle.DUST, petal,
                        false);
            }
        }
    }

    /**
     * A word popping up and fading; {@code veiledText}: never sent to whoever cannot see the owner (filtered inside
     * the spawn, so not even its text reaches them).
     */
    private void pop(Player owner, Location at, String mini, float scale, boolean veiledText) {
        if (!reserve(1)) {
            return;
        }
        TextDisplay text = keepOptional(Displays.text(ctx(), owner, at, Text.mm(mini), 0.3f, 20,
                veiledText ? viewer -> viewer.equals(owner) || viewer.canSee(owner) : null));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0, 0, 0), 4);
        ctx().runLater(owner, 12, () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 5);
            }
        });
    }

    /** True unless {@code entity} is a player hidden from {@code owner} (stealth): nothing may reveal it. */
    private static boolean seen(Player owner, Entity entity) {
        return !(entity instanceof Player hidden) || owner.canSee(hidden);
    }

    /** Degrees in -180..180. */
    private static float wrap(float degrees) {
        float wrapped = degrees % 360;
        if (wrapped > 180) {
            wrapped -= 360;
        } else if (wrapped < -180) {
            wrapped += 360;
        }
        return wrapped;
    }

    /**
     * A doll that strikes found no room in the budget at all (it should not happen: the optional displays make way):
     * a dense puff of lacquer dust stands in for it, so it never flies unseen.
     */
    private void ghost(Location at, double size) {
        Vfx.cloud(ctx(), at, Particle.DUST, 4, size * 0.3, 0, RED_DUST, false);
        Vfx.dot(at.clone().add(0, size * 0.3, 0), Particle.DUST, FACE_DUST, false);
    }

    /** Room for {@code count} more optional displays (they only take free room). */
    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        optional.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    /**
     * Room for {@code count} more essential displays (the dolls that strike, the badge): optional displays make way,
     * oldest first, so a doll that hits is never left unseen.
     */
    private boolean makeRoom(int count) {
        reserve(0);
        Iterator<Display> it = optional.iterator();
        while (live.size() + count > MAX_DISPLAYS && it.hasNext()) {
            Display display = it.next();
            it.remove();
            live.remove(display);
            display.remove();
        }
        return live.size() + count <= MAX_DISPLAYS;
    }

    /** Remembers an essential display for the budget. */
    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    /** Remembers a display that makes way for the essential ones when the budget is tight. */
    private <T extends Display> T keepOptional(T display) {
        optional.add(display);
        return keep(display);
    }
}
