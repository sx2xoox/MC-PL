package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
                    "인형 껍데기 {shells}겹을 두르고 시작합니다. 껍데기 1겹마다 몸이 {scale_step}배씩 커지고(알맹이는 "
                            + "{scale_core}배) 받는 피해가 {shell_guard}% 줄지만 이동 속도가 {shell_slow}% 느려집니다. 한 번에 "
                            + "{crack_hit} 이상 피해를 받거나 바깥 껍데기에 피해가 {crack_total} 쌓이면 껍데기가 '딱' 갈라지고"
                            + "(그 뒤 {crack_grace}초간은 다음 껍데기가 버팀), 반쪽들이 공격받은 쪽 약 {cover_distance}블록 앞에 "
                            + "{cover_duration}초간 높이 2의 인형 엄폐물로 섭니다(공격한 쪽이 없거나 빈자리가 없으면 굴러가 "
                            + "사라짐). 껍데기를 모두 잃은 알맹이는 이동 속도가 {core_speed}% 빨라지는 대신 받는 피해가 "
                            + "{core_weakness}% 늘어납니다. {regrow_delay}초간 피해를 받지 않으면 껍데기가 1겹씩 다시 덮입니다.")
            .active("인형 속 인형",
                    "큰 인형을 조준한 곳({throw_range}블록 이내)으로 포물선을 그리며 던집니다. 적이나 땅에 닿으면 '퐁' 열려 "
                            + "반경 {big_radius}블록의 적에게 {big_damage} 피해를 주고, 안에서 나온 중간 인형이 "
                            + "{hop_range}블록 안의 다른 적(없으면 같은 적)에게 깡충 뛰어 {middle_damage} 피해를, 그 안의 가장 "
                            + "작은 인형이 다시 튀어 {small_damage} 피해와 {small_slow_duration}초간 이동 속도 "
                            + "-{small_slow}%를 줍니다. 인형은 벽에 막히고, 한 번 던질 때 한 적이 받는 피해는 최대 "
                            + "{doll_cap}입니다. 웅크린 채 쓰면 내 껍데기 1겹을 벗어 던져 큰 인형의 피해가 {shed_bonus} "
                            + "늘어납니다.",
                    9)
            .ultimate("처음부터 다시",
                    "머리 위에서 거대한 인형 껍데기가 {drop_delay}초에 걸쳐 내려와(땅에 그림자로 예고) 나를 덮습니다. 반경 "
                            + "{slam_radius}블록의 적은 {slam_damage} 피해를 입고 약 {slam_push}블록 밖으로 밀려나며, 나는 "
                            + "껍데기 {shells}겹을 모두 되찾고 {gold_duration}초간 금빛 껍데기 1겹을 더 두릅니다. 그동안 "
                            + "껍데기가 갈라지면 반쪽들이 엄폐물 대신 {shard_range}블록 안의 가장 가까운 적에게 날아가 하나에 "
                            + "{shard_damage} 피해를 줍니다(최대 {shard_max}개, 적이 없으면 엄폐물). 끝날 때 남은 금빛 "
                            + "껍데기는 벗겨져 사라집니다.")
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
            .param("drop_delay", 0.5)
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

    // passive
    private static final long BADGE_REFRESH = 5;
    private static final long VEIL_PERIOD = 10;
    private static final long SEAM_PERIOD = 10;
    private static final double BADGE_LIFT = 0.75;
    private static final float BADGE_SCALE = 0.85f;
    /** A freshly grown body is not hurt by the blocks it grew into (the client settles it) for this long. */
    private static final long BREATH_TICKS = 20;
    private static final long CRAMP_FX_PERIOD = 40;
    private static final int HALF_FLIGHT = 4;
    private static final int ROLL_TICKS = 8;
    private static final int LID_TICKS = 6;
    /** The cover's blocks: the head on the middle of the body, two "arms" at its sides. */
    private static final float SHELL_WIDEN = 1.25f;

    // active
    /** Heights of the big, middle and small doll. */
    private static final double[] DOLL_HEIGHTS = {0.8, 0.6, 0.42};
    private static final double DOLL_HIT = 0.45;
    private static final int MAX_FLIGHT = 60;
    private static final long POP_DELAY = 4;
    private static final double HOP_HIT = 1.6;
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
    /** The lower half of a cracked shell: lacquer base with the gold seam on its rim. */
    private static final List<Part> BOTTOM_HALF = List.of(
            new Part(LACQUER, GILT, 0, 0, 0, 1, 1, 1),
            new Part(GILT, LACQUER, 0, 0.45f, 0, 1.04f, 0.1f, 1.04f));

    /** The face painted on the cover's head block (relative to the block's center, its front to +Z). */
    private static final List<Part> COVER_FACE = List.of(
            new Part(CREAM, CREAM, 0, -0.03f, 0.505f, 0.62f, 0.56f, 0.02f),
            new Part(INK, INK, -0.13f, 0.03f, 0.52f, 0.08f, 0.1f, 0.02f),
            new Part(INK, INK, 0.13f, 0.03f, 0.52f, 0.08f, 0.1f, 0.02f));
    /** The gold seam around the top of the cover's body block. */
    private static final Part COVER_SEAM = new Part(GILT, GILT, 0, 0.47f, 0, 1.03f, 0.07f, 1.03f);

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    /** Viewers the head badge is hidden from (they cannot see the owner). */
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
        if (reserve(1)) {
            TextDisplay label = keep(Displays.text(ctx(), owner, badgeSpot(owner), Component.empty(), BADGE_SCALE, 0));
            label.setTeleportDuration(1);
            badge = label;
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
        veiled.clear();
        pendingShards.clear();
        badge = null;
        badgeText = null;
        scaleKey = null;
        speedKey = null;
        descending = false;
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
        // The badge keeps to the new head at once (not a tick late, inside a grown head).
        TextDisplay label = badge;
        if (label != null && label.isValid()) {
            Displays.moveTo(label, badgeSpot(owner));
        }
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
        shells--;
        strain = 0;
        long now = ctx().currentTick();
        graceUntil = now + params().ticks("crack_grace");
        quietFrom = now;
        applySize(owner);
        refreshBadge(true);
        Spill spill = new Spill(waist(owner), from, (float) (Body.PLAYER.width() * oldScale * SHELL_WIDEN),
                (float) (Body.PLAYER.height() * oldScale), owner.getLocation().getYaw(), golden);
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
        pop(owner, owner.getLocation().add(0, owner.getHeight() + 0.35, 0),
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
        float yaw = from != null ? yawOf(from) : spill.yaw();
        Prop top = new Prop(topStart, TOP_HALF, TOP_HALF.size(), halfScale, spill.golden(), yaw, 30);
        Prop bottom = new Prop(bottomStart, BOTTOM_HALF, BOTTOM_HALF.size(), halfScale, spill.golden(), yaw, 30);
        for (Prop half : List.of(top, bottom)) {
            veil(half);
            // Out of the owner's own view while they leave its body.
            half.hideFrom(owner);
            ctx().runLater(owner, 2, () -> half.showTo(owner));
        }
        if (plan == null) {
            Vector way = from != null ? from : Motion.horizontalFacing(owner.getLocation());
            roll(owner, top, topStart, way.clone().rotateAroundY(0.5), true);
            roll(owner, bottom, bottomStart, way.clone().rotateAroundY(-0.5), false);
            return;
        }
        Location headAt = plan.base().getRelative(BlockFace.UP).getLocation().add(0.5, 0.5, 0.5);
        Location bodyAt = plan.base().getLocation().add(0.5, 0.5, 0.5);
        fly(owner, top, topStart, headAt, HALF_FLIGHT, 0.6, yaw, 180, null);
        fly(owner, bottom, bottomStart, bodyAt, HALF_FLIGHT, 0.35, yaw, -90, () -> {
            boolean raised = raiseCover(owner, plan, spill.golden());
            top.vanish(raised ? 1 : 6);
            bottom.vanish(raised ? 1 : 6);
        });
    }

    /** A half rolls away along the ground (or hops off, the top one) and is gone. */
    private void roll(Player owner, Prop half, Location start, Vector way, boolean hop) {
        Vector flat = Motion.horizontal(way);
        if (flat.lengthSquared() < 1.0E-6) {
            flat = new Vector(0, 0, 1);
        }
        Location end = start.clone().add(flat.multiply(1.6));
        if (!Spots.clearPath(start, end)) {
            end = start.clone();
        }
        Spots.snapToGround(end.getWorld(), end, 3);
        end.add(0, 0.2, 0);
        Location from = start.clone();
        Location to = end;
        fly(owner, half, from, to, ROLL_TICKS, hop ? 0.8 : 0.15, yawOf(flat), hop ? 360 : 540, () -> {
            to.getWorld().spawnParticle(Particle.POOF, to, Vfx.n(ctx(), 4), 0.15, 0.1, 0.15, 0.02);
            Fx.sound(to, Sound.BLOCK_WOOD_FALL, 0.7f, 1.2f + Fx.jitter(0.1f));
            half.vanish(4);
        });
    }

    // ------------------------------------------------------------------ cover

    /** Where a cover stands: its body block (the head goes on top), the face it shows and the step to its sides. */
    private record CoverPlan(Block base, BlockFace front, int stepX, int stepZ) {
    }

    /** A free spot on the ground about {@code cover_distance} towards the blow, or null. */
    private @Nullable CoverPlan planCover(Player owner, Vector from) {
        World world = owner.getWorld();
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
                base = base.getRelative(BlockFace.UP); // the ground is a slab or a step: the cover stands on it
            }
            if (!placeable(base) || !base.getRelative(BlockFace.DOWN).isCollidable()) {
                continue;
            }
            Vector side = new Vector(-from.getZ(), 0, from.getX());
            return new CoverPlan(base, front(from), (int) Math.round(side.getX()), (int) Math.round(side.getZ()));
        }
        return null;
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
        if (!placeable(base) || !base.getRelative(BlockFace.DOWN).isCollidable()
                || !ctx().placeTempBlock(base, LACQUER.createBlockData(), ticks)) {
            return false;
        }
        Block head = base.getRelative(BlockFace.UP);
        boolean headUp = placeable(head) && ctx().placeTempBlock(head, (golden ? GILT : LACQUER).createBlockData(),
                ticks);
        for (int sign : new int[] {1, -1}) {
            Block side = base.getRelative(plan.stepX() * sign, 0, plan.stepZ() * sign);
            if (!side.equals(base) && placeable(side) && side.getRelative(BlockFace.DOWN).isCollidable()) {
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
                    painted.add(keep(Displays.block(ctx(), owner, head, part.material().createBlockData(),
                            part.transform(rotation, unit, none), ticks)));
                }
            }
        }
        if (reserve(1)) {
            painted.add(keep(Displays.block(ctx(), owner, body, COVER_SEAM.material().createBlockData(),
                    COVER_SEAM.transform(rotation, unit, none), ticks)));
        }
        if (reserve(1)) {
            Vector3f out = new Quaternionf(rotation).transform(new Vector3f(0, -0.05f, 0.53f));
            painted.add(keep(Displays.item(ctx(), owner, body, new ItemStack(CORNFLOWER),
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
        if (label != null && label.isValid()) {
            Displays.moveTo(label, badgeSpot(owner));
        }
        if (tick % BADGE_REFRESH == 0) {
            refreshBadge(false);
        }
        if (tick % VEIL_PERIOD == 0) {
            veilBadge(owner);
        }
        regrow(owner, now);
        ambient(owner, tick);
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
            if (now - lastCrampFx >= CRAMP_FX_PERIOD) {
                lastCrampFx = now;
                owner.sendActionBar(Text.mm("<gray>껍데기가 덮일 자리가 비좁습니다</gray>"));
            }
            return;
        }
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

    /** A shell closes over the owner like a lid: halves from above and below meet at its waist with a click. */
    private void closeLid(Player owner) {
        float width = (float) (owner.getWidth() * SHELL_WIDEN);
        float height = (float) owner.getHeight();
        Vector3f halfScale = new Vector3f(width, height / 2, width);
        float yaw = owner.getLocation().getYaw();
        Location waist = waist(owner);
        Prop top = new Prop(waist.clone().add(0, height, 0), TOP_HALF, TOP_HALF.size(), halfScale, false, yaw,
                LID_TICKS + 8);
        Prop bottom = new Prop(waist.clone().add(0, -height * 0.6, 0), BOTTOM_HALF, BOTTOM_HALF.size(), halfScale,
                false, yaw, LID_TICKS + 8);
        for (Prop half : List.of(top, bottom)) {
            half.hideFrom(owner);
            veil(half);
        }
        Loop.repeat(ctx(), owner, 1, 1, LID_TICKS, i -> {
            double t = (i + 1.0) / LID_TICKS;
            double eased = 1 - (1 - t) * (1 - t);
            Location center = waist(owner);
            top.moveTo(center.clone().add(0, height * (1 - eased) + height / 4, 0));
            bottom.moveTo(center.clone().add(0, -height * 0.6 * (1 - eased) - height / 4, 0));
            return true;
        }, () -> {
            Location center = waist(owner);
            Vfx.ring(ctx(), center, width * 0.7, 16, 0, Particle.DUST_COLOR_TRANSITION, GOLD_FLASH, false);
            flowers(center, width * 0.5, 2);
            Fx.sound(center, Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, 1.0f, 1.25f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.BLOCK_BAMBOO_WOOD_BUTTON_CLICK_ON, 1.0f, 1.4f);
            Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 0.8f + 0.2f * shells);
            top.vanish(3);
            bottom.vanish(3);
        });
    }

    // ------------------------------------------------------------------ badge & ambience

    private static Location badgeSpot(Player owner) {
        return Displays.unrotated(owner.getLocation().add(0, owner.getHeight() + BADGE_LIFT, 0));
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
            float big = BADGE_SCALE * 1.4f;
            Displays.animate(label, Displays.centered(0, 0, 0, big, big, big, 0, 0, 0), 2);
            ctx().runLater(player(), 3, () -> Displays.animate(label,
                    Displays.centered(0, 0, 0, BADGE_SCALE, BADGE_SCALE, BADGE_SCALE, 0, 0, 0), 4));
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
        if (shells < max) {
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

    /** The badge is hidden from everyone who cannot see the owner (it would give its place away). */
    private void veilBadge(Player owner) {
        TextDisplay label = badge;
        if (label == null || !label.isValid()) {
            return;
        }
        for (Player viewer : owner.getWorld().getPlayers()) {
            if (viewer.equals(owner)) {
                continue;
            }
            UUID id = viewer.getUniqueId();
            if (!viewer.canSee(owner)) {
                if (veiled.add(id)) {
                    viewer.hideEntity(ctx().plugin(), label);
                }
            } else if (veiled.remove(id)) {
                viewer.showEntity(ctx().plugin(), label);
            }
        }
    }

    /** Displays on the owner's body are hidden from whoever cannot see the owner. */
    private void veil(Prop prop) {
        Player owner = player();
        for (Player viewer : owner.getWorld().getPlayers()) {
            if (!viewer.equals(owner) && !viewer.canSee(owner)) {
                prop.hideFrom(viewer);
            }
        }
    }

    /** The gold seam glinting at the waist, the bare core's cream glints, the golden shimmer. */
    private void ambient(Player owner, long tick) {
        if (invisible(owner)) {
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

    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector facing = Motion.horizontalFacing(eye);
        // Facing +Z (south), the right hand is -X (west).
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());
        double scale = owner.getHeight() / Body.PLAYER.height();
        return eye.add(facing.multiply(0.4 * scale)).add(right.multiply(0.35 * scale)).subtract(0, 0.45 * scale, 0);
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
            float size = (float) DOLL_HEIGHTS[0];
            this.doll = new Prop(start, DOLL, DOLL_PARTS[0], new Vector3f(size), golden, yawOf(delta),
                    MAX_FLIGHT + 30);
        }

        void start() {
            Fx.sound(start, Sound.ENTITY_WITCH_THROW, 0.9f, 1.2f + Fx.jitter(0.08f));
            Fx.sound(start, Sound.BLOCK_BAMBOO_WOOD_HIT, 0.6f, 1.4f + Fx.jitter(0.08f));
            Vfx.cloud(ctx(), start, Particle.DUST, 5, 0.12, 0, GOLD_SMALL, false);
            Loop.repeat(ctx(), owner, 1, 1, MAX_FLIGHT, this, () -> {
                if (!opened) {
                    open(at, null);
                }
            });
        }

        @Override
        public boolean test(long i) {
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
                    open(wall.getHitPosition().toLocation(at.getWorld()).subtract(unit.multiply(0.15)), null);
                    return false;
                }
            }
            LivingEntity hit = touching(at, next);
            if (hit != null) {
                open(next, hit);
                return false;
            }
            if (!ctx().isInsideArena(next)) {
                open(at, null);
                return false;
            }
            at = next;
            doll.moveTo(at);
            if (i % 2 == 0) {
                float roll = (float) (Math.sin(i * 0.9) * 22);
                doll.pose(rotation(yawOf(step), 0, roll), doll.scale(), 2);
            }
            Vfx.dot(at, Particle.DUST, i % 2 == 0 ? RED_DUST : GOLD_SMALL, false);
            return true;
        }

        /** The first enemy whose body the doll touches between two points of its flight. */
        private @Nullable LivingEntity touching(Location from, Location to) {
            Location mid = from.clone().add(to).multiply(0.5);
            for (Location point : List.of(mid, to)) {
                List<LivingEntity> near = ctx().enemiesNear(owner, point, DOLL_HIT);
                if (!near.isEmpty()) {
                    return near.getFirst();
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
                doll.posePart(index, rotation(yawOf(delta) + 150, 0, 25), doll.scale(),
                        new Vector3f(0, size * 0.75f, 0), 5);
            }
            for (int index = 0; index < DOLL_PARTS[0]; index++) {
                if (index != HEAD && index != FACE_PART) {
                    doll.posePart(index, facing, new Vector3f(size * 1.12f, size * 0.7f, size * 1.12f),
                            new Vector3f(0, -size * 0.12f, 0), 2);
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

        private double remaining(LivingEntity enemy) {
            return Math.max(0, params().num("doll_cap")) - dealt.getOrDefault(enemy.getUniqueId(), 0.0);
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
                Prop little = new Prop(spot, DOLL, DOLL_PARTS[s], new Vector3f(size * 0.2f), false, yawOf(delta), 30);
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
     * walls. On landing it strikes (the small one also slows), and the next doll pops out of it.
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
            this.doll = new Prop(from, DOLL, DOLL_PARTS[stage], new Vector3f(size * 0.3f), false,
                    yawOf(end.toVector().subtract(from.toVector())), ticks + 30);
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
            doll.moveTo(at);
            float yaw = yawOf(end.toVector().subtract(at.toVector()));
            if (i == 0) {
                doll.pose(rotation(yaw, 0, 0), new Vector3f(size * 0.85f, size * 1.2f, size * 0.85f), 2);
            } else if (i % 2 == 0) {
                doll.pose(rotation(yaw, (float) (t * 30 - 15), 0), new Vector3f(size), 2);
            }
            Vfx.dot(at, Particle.DUST, stage == 1 ? RED_SMALL : BLUE_DUST, false);
            if (i + 1 >= ticks) {
                land();
                return false;
            }
            return true;
        }

        /** The doll lands on its enemy: a knock (and, the small one, a slow). */
        private void land() {
            Player owner = thrown.owner;
            boolean close = Combat.isTargetable(ctx(), owner, target) && at.distance(Fx.body(target)) <= HOP_HIT;
            double damage = Math.max(0, params().num(stage == 1 ? "middle_damage" : "small_damage"));
            boolean hit = close && thrown.strike(target, damage);
            doll.pose(rotation(yawOf(end.toVector().subtract(from.toVector())), 0, 0),
                    new Vector3f(size * 1.2f, size * 0.75f, size * 1.2f), 2);
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

        /** "통": the doll bounces off a wall and drops; the next one pops out of it there. */
        private void bonk(Location spot) {
            Player owner = thrown.owner;
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

        Descent(Player owner) {
            this.owner = owner;
            this.ticks = Math.max(1, params().ticks("drop_delay"));
            this.radius = Math.max(0, params().num("slam_radius"));
            this.width = (float) (GIANT_HEIGHT * DOLL.getFirst().sx());
            this.giant = new Prop(spot(0), DOLL, DOLL.size(), new Vector3f((float) GIANT_HEIGHT), false,
                    owner.getLocation().getYaw(), ticks + SETTLE_TICKS + 30);
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
            giant.pose(rotation(owner.getLocation().getYaw() + (float) (t * 90), 0, 0), giant.scale(), 2);
            Location ground = owner.getLocation().add(0, 0.08, 0);
            // The doll's shadow grows and darkens under it; the blast radius rings it.
            double shadow = width / 2 * (0.3 + 0.7 * t);
            Vfx.ring(ctx(), ground, shadow, 14, i * 0.3, Particle.DUST, SHADOW, true);
            Vfx.ring(ctx(), ground, shadow * 0.55, 8, -i * 0.3, Particle.DUST, SHADOW, true);
            if (i % 2 == 0) {
                Vfx.ring(ctx(), ground, radius, 28, i * 0.1, Particle.DUST_COLOR_TRANSITION, RED_FADE, true);
                Vfx.ring(ctx(), ground, radius - 0.15, 20, -i * 0.1, Particle.DUST, GOLD_SMALL, true);
            }
            return true;
        }

        private void land() {
            descending = false;
            giant.moveTo(owner.getLocation().add(0, GIANT_HEIGHT / 2, 0));
            giant.hideFrom(owner);
            slam(owner, giant);
        }
    }

    /** The giant doll covers the owner: enemies around are knocked out, every shell comes back and a golden one. */
    private void slam(Player owner, Prop giant) {
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

        // The giant settles over the owner and shrinks onto its body, leaving a golden shimmer.
        float body = (float) owner.getHeight();
        giant.pose(rotation(feet.getYaw(), 0, 0), new Vector3f((float) GIANT_HEIGHT * 1.05f,
                (float) GIANT_HEIGHT * 0.88f, (float) GIANT_HEIGHT * 1.05f), 2);
        ctx().runLater(owner, 3, () -> {
            giant.moveTo(owner.getLocation().add(0, body / 2, 0));
            giant.pose(rotation(feet.getYaw(), 0, 0), new Vector3f(body * 1.1f), SETTLE_TICKS);
        });
        ctx().runLater(owner, 3 + SETTLE_TICKS, () -> {
            giant.vanish(2);
            Location waist = waist(owner);
            Vfx.sphere(ctx(), waist, owner.getWidth() * 1.1, 30, Particle.DUST_COLOR_TRANSITION, GOLD_FLASH, true);
            Fx.sound(waist, Sound.ITEM_ARMOR_EQUIP_GOLD, 1.0f, 1.0f);
            Fx.sound(waist, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.2f);
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
        for (int k = 0; k < count; k++) {
            shardsLeft--;
            List<Part> model = k == 0 ? TOP_HALF : BOTTOM_HALF;
            Location start = origin.clone().add(0, (k == 0 ? 1 : -1) * spill.height() / 4, 0);
            Prop half = new Prop(start, model, model.size(), halfScale, true, spill.yaw(), SHARD_TICKS + 20);
            veil(half);
            half.hideFrom(owner);
            ctx().runLater(owner, 2, () -> half.showTo(owner));
            new Shard(owner, half, start, target).start();
        }
    }

    /** A golden half homing in on its enemy, stopped by walls. */
    private final class Shard implements LongPredicate {

        private final Player owner;
        private final Prop half;
        private final LivingEntity target;
        private Location at;

        Shard(Player owner, Prop half, Location start, LivingEntity target) {
            this.owner = owner;
            this.half = half;
            this.target = target;
            this.at = start.clone();
        }

        void start() {
            Fx.sound(at, Sound.ENTITY_WITCH_THROW, 0.9f, 1.4f + Fx.jitter(0.08f));
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.5f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), owner, 1, 1, SHARD_TICKS, this, () -> half.vanish(4));
        }

        @Override
        public boolean test(long i) {
            if (!Combat.isTargetable(ctx(), owner, target)) {
                half.vanish(4);
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
                half.moveTo(spot);
                spot.getWorld().spawnParticle(Particle.BLOCK, spot, Vfx.n(ctx(), 6), 0.15, 0.15, 0.15, 0.05,
                        GILT.createBlockData());
                Fx.sound(spot, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, 0.8f);
                half.vanish(4);
                return false;
            }
            Location previous = at;
            at = at.clone().add(unit.clone().multiply(step));
            half.moveTo(at);
            half.pose(rotation(yawOf(unit), 0, (float) (i * 40)), half.scale(), 1);
            Vfx.trail(previous, at, GOLD_TRAIL, 6, false);
            if (i % 2 == 0) {
                Vfx.dot(at, Particle.WAX_OFF, null, false);
            }
            if (Fx.body(target).distance(at) <= SHARD_HIT) {
                hit(Fx.body(target));
                return false;
            }
            return true;
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

    /** A little model of block displays sharing one pivot (the parts' boxes are posed around it). */
    private final class Prop {

        private final List<BlockDisplay> parts = new ArrayList<>();
        private final List<Part> specs = new ArrayList<>();
        private Vector3f scale;

        /** The first {@code count} parts of {@code model}, or nothing at all when the display budget is spent. */
        Prop(Location at, List<Part> model, int count, Vector3f scale, boolean golden, float yaw, long lifetime) {
            this.scale = new Vector3f(scale);
            int used = Math.min(count, model.size());
            if (!reserve(used)) {
                return;
            }
            Quaternionf rotation = rotation(yaw, 0, 0);
            Vector3f none = new Vector3f();
            for (Part part : model.subList(0, used)) {
                Material material = golden ? part.golden() : part.material();
                BlockDisplay display = keep(Displays.block(ctx(), player(), at, material.createBlockData(),
                        part.transform(rotation, this.scale, none), lifetime));
                display.setTeleportDuration(1);
                parts.add(display);
                specs.add(part);
            }
        }

        Vector3f scale() {
            return new Vector3f(scale);
        }

        void moveTo(Location at) {
            for (BlockDisplay part : parts) {
                Displays.moveTo(part, at);
            }
        }

        void pose(Quaternionf rotation, Vector3f scale, int ticks) {
            this.scale = new Vector3f(scale);
            Vector3f none = new Vector3f();
            for (int i = 0; i < parts.size(); i++) {
                Displays.animate(parts.get(i), specs.get(i).transform(rotation, this.scale, none), ticks);
            }
        }

        /** Poses one part on its own (scaled by {@code scale}, raised by {@code lift}). */
        void posePart(int index, Quaternionf rotation, Vector3f scale, Vector3f lift, int ticks) {
            if (index < parts.size()) {
                Displays.animate(parts.get(index), specs.get(index).transform(rotation, scale, lift), ticks);
            }
        }

        void hideFrom(Player viewer) {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    viewer.hideEntity(ctx().plugin(), part);
                }
            }
        }

        void showTo(Player viewer) {
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    viewer.showEntity(ctx().plugin(), part);
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

    /**
     * Moves {@code prop} from {@code from} to {@code to} in {@code ticks} ticks along an arc {@code arc} blocks high,
     * turning it by {@code spin} degrees on the way; {@code onArrive} runs at the end.
     */
    private void fly(Player owner, Prop prop, Location from, Location to, int ticks, double arc, float yaw,
                     float spin, @Nullable Runnable onArrive) {
        Vector delta = to.toVector().subtract(from.toVector());
        Loop.repeat(ctx(), owner, 1, 1, ticks, i -> {
            double t = (i + 1.0) / ticks;
            prop.moveTo(from.clone().add(delta.clone().multiply(t)).add(0, 4 * arc * t * (1 - t), 0));
            prop.pose(rotation(yaw + (float) (spin * t), 0, 0), prop.scale(), 1);
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

    /** A word popping up and fading; {@code veiled}: hidden from whoever cannot see the owner. */
    private void pop(Player owner, Location at, String mini, float scale, boolean veiledText) {
        if (!reserve(1)) {
            return;
        }
        TextDisplay text = keep(Displays.text(ctx(), owner, at, Text.mm(mini), 0.3f, 20));
        if (veiledText) {
            for (Player viewer : owner.getWorld().getPlayers()) {
                if (!viewer.equals(owner) && !viewer.canSee(owner)) {
                    viewer.hideEntity(ctx().plugin(), text);
                }
            }
        }
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

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
