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
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
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
 * 무당 — the shaman. Its bell rings now and then and shakes one harmful effect off itself and every ally nearby; the
 * active raises jangseung (village guardian totems) that curse enemies around them (weaker, slower) and mend allies;
 * the ultimate holds a gut (a shaman ritual) in a ring of five-coloured flags: inside, enemies take more damage from
 * everything and are cut by the dancing blades every second, while the shaman's side is shielded and healed.
 *
 * <p>The gut needs no passive state, so the Gambler can borrow the ultimate.
 *
 * <p>Look: the five ritual colours (obangsaek: blue, red, yellow, white, black) with a warm golden glow. The bell
 * swings over the shaman's hand when it rings and a five-coloured ripple runs out over the allies; a cleansed body
 * sheds black smoke that is drawn up into the bell. A jangseung rises out of the ground: a weathered post with staring
 * black eyes, a white grin, a hat and its title (천하대장군 / 지하여장군) beside it; a ring of red or blue light marks its
 * reach, cursed enemies trail dark wisps and dark threads run from its eyes to them, healed allies get green motes. The
 * gut raises five flags one by one to drum beats (blue east, red south, white west, black north and yellow in the
 * middle behind the straw-cutter blades), a turning five-coloured ring and streamers mark the ground, the shaman
 * dances in a coloured whirl, every second the blades flash across the enemies inside to a drum and a bell, and at the
 * end the flags burn away in a five-coloured flare.
 */
public final class ShamanAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("shaman", "무당", Material.BELL)
            .color(TextColor.color(0xE0453A))
            .difficulty(2)
            .passive("방울",
                    "{bell_period}초마다 방울을 울려 나와 반경 {bell_radius}블록 안 아군의 해로운 효과를 하나씩(없으면 몸에 "
                            + "붙은 불을) 씻어냅니다.")
            .active("장승",
                    "조준한 바닥({totem_range}블록 이내)에 {totem_duration}초간 장승을 세웁니다(최대 {totem_max}개). "
                            + "장승 반경 {totem_radius}블록 안의 적은 공격력이 {totem_weaken}%, 이동 속도가 {totem_slow}% "
                            + "줄고, 나와 아군은 1초마다 체력을 {totem_heal} 회복합니다.",
                    10)
            .ultimate("굿판",
                    "내 자리에 반경 {gut_radius}블록의 굿판을 {gut_duration}초간 벌입니다. 판 안의 적은 받는 모든 피해가 "
                            + "{gut_vulnerable}% 늘고 작두날에 1초마다 {gut_damage} 피해를 입으며, 나와 아군은 저항 I과 "
                            + "1초마다 체력 {gut_heal} 회복을 받습니다. 굿이 끝날 때 판 안의 적은 {gut_finale} 피해를 "
                            + "입습니다.")
            .param("bell_period", 5)
            .param("bell_radius", 8)
            .param("totem_range", 7)
            .param("totem_duration", 14)
            .param("totem_max", 2)
            .param("totem_radius", 5)
            .param("totem_weaken", 25)
            .param("totem_slow", 25)
            .param("totem_heal", 0.5)
            .param("gut_radius", 7)
            .param("gut_duration", 7)
            .param("gut_vulnerable", 25)
            .param("gut_damage", 1)
            .param("gut_heal", 0.5)
            .param("gut_finale", 2)
            .build();

    private static final String RED_TAG = "#E0453A";
    private static final String BLUE_TAG = "#4F7BE8";
    private static final String GOLD_TAG = "#F2C230";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: the five ritual colours
    /** Warm white-gold glow, ritual red, ritual blue. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF4D6, 0xE0453A, 0x1F4FB8);
    /** Obangsaek in ring order: blue (east), red (south), yellow (centre), white (west), black (north). */
    private static final Color[] OBANG = {Color.fromRGB(0x2D5BD3), Color.fromRGB(0xE0453A), Color.fromRGB(0xF2C230),
            Color.fromRGB(0xF5F5F0), Color.fromRGB(0x26262E)};
    private static final Particle.DustOptions[] OBANG_DUST = new Particle.DustOptions[OBANG.length];
    private static final Particle.DustOptions[] OBANG_SMALL = new Particle.DustOptions[OBANG.length];

    static {
        for (int i = 0; i < OBANG.length; i++) {
            OBANG_DUST[i] = new Particle.DustOptions(OBANG[i], 1.1f);
            OBANG_SMALL[i] = new Particle.DustOptions(OBANG[i], 0.7f);
        }
    }

    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition GLOW_SMALL = PALETTE.glow(0.6f);
    private static final Particle.DustOptions CURSE_DUST = new Particle.DustOptions(Color.fromRGB(0x3A1020), 0.9f);
    private static final Particle.DustOptions MEND_DUST = new Particle.DustOptions(Color.fromRGB(0x9BE8A0), 0.8f);
    private static final Particle.DustOptions SMOKE_DUST = new Particle.DustOptions(Color.fromRGB(0x202024), 1.2f);
    private static final Color CURSE_THREAD = Color.fromRGB(0x7A1830);
    private static final Color MEND_THREAD = Color.fromRGB(0x9BE8A0);

    /** Item and block types of the visuals; items and block data are created on use (they need a server). */
    private static final Material BELL_ITEM = Material.BELL;
    private static final Material TOTEM_POST = Material.STRIPPED_SPRUCE_LOG;
    private static final Material TOTEM_EYE = Material.BLACK_CONCRETE;
    private static final Material TOTEM_TEETH = Material.WHITE_CONCRETE;
    private static final Material GENERAL_HAT = Material.BLACK_CONCRETE;
    private static final Material LADY_HAT = Material.BLUE_TERRACOTTA;
    private static final Material FLAG_POLE = Material.DARK_OAK_LOG;
    private static final Material[] FLAG_CLOTH = {Material.BLUE_WOOL, Material.RED_WOOL, Material.YELLOW_WOOL,
            Material.WHITE_WOOL, Material.BLACK_WOOL};
    private static final Material BLADE = Material.IRON_BLOCK;

    /** Display entities one shaman keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    /** "/ 2": now - this must not overflow. */
    private static final long NEVER = Long.MIN_VALUE / 2;

    // passive: the bell
    /** Harmful effects in the order the bell shakes them off (any other harmful effect comes after). */
    private static final List<PotionEffectType> CLEANSE_ORDER = List.of(PotionEffectType.BLINDNESS,
            PotionEffectType.DARKNESS, PotionEffectType.LEVITATION, PotionEffectType.SLOWNESS, PotionEffectType.POISON,
            PotionEffectType.WITHER, PotionEffectType.WEAKNESS, PotionEffectType.NAUSEA,
            PotionEffectType.MINING_FATIGUE, PotionEffectType.HUNGER);
    private static final int BELL_SWING_TICKS = 12;
    private static final int RIPPLE_TICKS = 8;

    // active: jangseung
    private static final long TOTEM_PULSE = 10;
    private static final long TOTEM_CURSE_TICKS = 14;
    private static final int TOTEM_RISE_TICKS = 6;
    private static final int TOTEM_SINK_TICKS = 8;
    private static final double TOTEM_FALLBACK = 2.5;
    private static final double TOTEM_SNAP = 4;
    private static final int MAX_TOTEMS = 4;

    /** One block of a jangseung in its own space (origin on the ground, local +Z = the way the face looks). */
    private record TotemPart(Material block, float x, float y, float z, float sx, float sy, float sz,
                             boolean hat) {
    }

    private static final TotemPart[] GENERAL = {
            new TotemPart(TOTEM_POST, 0f, 1.15f, 0f, 0.5f, 2.3f, 0.5f, false),
            new TotemPart(GENERAL_HAT, 0f, 2.42f, 0f, 0.66f, 0.24f, 0.66f, true),
            new TotemPart(TOTEM_EYE, 0.12f, 1.86f, 0.255f, 0.13f, 0.11f, 0.03f, false),
            new TotemPart(TOTEM_EYE, -0.12f, 1.86f, 0.255f, 0.13f, 0.11f, 0.03f, false),
            new TotemPart(TOTEM_TEETH, 0f, 1.56f, 0.255f, 0.32f, 0.1f, 0.03f, false),
    };

    // ultimate: the gut
    private static final int FLAG_COUNT = 4;
    private static final float POLE_HEIGHT = 2.7f;
    private static final float POLE_WIDTH = 0.11f;
    private static final float CLOTH_WIDTH = 0.85f;
    private static final float CLOTH_HEIGHT = 0.55f;
    private static final long FLAG_STAGGER = 2;
    private static final int FLAG_RISE_TICKS = 6;
    /** The ritual starts once the last flag stands. */
    private static final long GUT_SETUP = FLAG_STAGGER * FLAG_COUNT + FLAG_RISE_TICKS;
    private static final long GUT_BEAT = 10;
    private static final long GUT_PULSE = 20;
    private static final int RESISTANCE_TICKS = 30;

    private boolean granted;
    private long nextRing = NEVER;
    private final Deque<Totem> totems = new ArrayDeque<>();
    private int totemsRaised;
    private final List<Display> displays = new ArrayList<>();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        granted = true;
        nextRing = ctx().currentTick() + Math.max(1, params().ticks("bell_period"));
    }

    @Override
    public void onRevoke() {
        // Tasks, displays and modifiers are owner-scoped: the context releases them.
        granted = false;
        totems.clear();
    }

    // ------------------------------------------------------------------ passive: the bell

    @Override
    public void onTick(long tick) {
        if (!granted) {
            return;
        }
        long now = ctx().currentTick();
        if (now >= nextRing) {
            nextRing = now + Math.max(1, params().ticks("bell_period"));
            ring(player());
        }
    }

    /** The bell rings: one harmful effect (or the fire) is shaken off the shaman and each ally in reach. */
    private void ring(Player owner) {
        double radius = Math.max(0, params().num("bell_radius"));
        List<LivingEntity> blessed = new ArrayList<>();
        blessed.add(owner);
        blessed.addAll(ctx().alliesNear(owner, owner.getLocation(), radius));
        Location hand = bellPoint(owner);
        int cleansed = 0;
        String first = null;
        for (LivingEntity body : blessed) {
            String removed = cleanseOne(body);
            if (removed != null) {
                cleansed++;
                if (first == null) {
                    first = removed;
                }
                shedFx(body, hand);
            }
        }
        bellFx(owner, hand, cleansed > 0);
        ripple(owner, radius, cleansed > 0);
        if (cleansed > 0) {
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">♪ 방울</" + GOLD_TAG + "> <white>" + first + "</white>"
                    + "<gray> 정화</gray>" + (cleansed > 1 ? " <gray>(" + cleansed + "명)</gray>" : "")));
        }
    }

    /**
     * Removes one harmful effect from {@code body}, the worst first ({@link #CLEANSE_ORDER}, then any other harmful
     * one), or puts out its fire if it has none. Returns the Korean name of what was removed, null if nothing.
     */
    static @Nullable String cleanseOne(LivingEntity body) {
        for (PotionEffectType type : CLEANSE_ORDER) {
            if (body.hasPotionEffect(type)) {
                body.removePotionEffect(type);
                return effectName(type);
            }
        }
        for (PotionEffect effect : List.copyOf(body.getActivePotionEffects())) {
            if (effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) {
                body.removePotionEffect(effect.getType());
                return effectName(effect.getType());
            }
        }
        if (body.getFireTicks() > 0) {
            body.setFireTicks(0);
            return "불";
        }
        return null;
    }

    private static String effectName(PotionEffectType type) {
        if (type.equals(PotionEffectType.BLINDNESS)) {
            return "실명";
        } else if (type.equals(PotionEffectType.DARKNESS)) {
            return "어둠";
        } else if (type.equals(PotionEffectType.LEVITATION)) {
            return "공중 부양";
        } else if (type.equals(PotionEffectType.SLOWNESS)) {
            return "구속";
        } else if (type.equals(PotionEffectType.POISON)) {
            return "독";
        } else if (type.equals(PotionEffectType.WITHER)) {
            return "시듦";
        } else if (type.equals(PotionEffectType.WEAKNESS)) {
            return "나약함";
        } else if (type.equals(PotionEffectType.NAUSEA)) {
            return "멀미";
        } else if (type.equals(PotionEffectType.MINING_FATIGUE)) {
            return "채굴 피로";
        } else if (type.equals(PotionEffectType.HUNGER)) {
            return "허기";
        }
        return "해로운 효과";
    }

    /** Where the bell is held: over the shaman's right hand. */
    private static Location bellPoint(Player owner) {
        Location at = owner.getLocation();
        Quaternionf yaw = yawRotation(owner.getBodyYaw());
        Vector3f hand = yaw.transform(new Vector3f(-0.45f, (float) (owner.getHeight() * 0.85), 0.3f));
        return Displays.unrotated(at.add(hand.x(), hand.y(), hand.z()));
    }

    /** The bell swings over the hand with a bright jingle (softer when there was nothing to cleanse). */
    private void bellFx(Player owner, Location hand, boolean cleansed) {
        float volume = cleansed ? 0.9f : 0.35f;
        Fx.sound(hand, Sound.BLOCK_BELL_USE, volume * 0.6f, 1.8f + Fx.jitter(0.05f));
        Fx.sound(hand, Sound.BLOCK_NOTE_BLOCK_BELL, volume, 1.6f);
        ctx().runLater(owner, 3, () -> Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, volume * 0.8f,
                1.9f));
        Vfx.dot(hand, Particle.WAX_OFF, null, false);
        if (!canSpawn(1)) {
            Vfx.cloud(ctx(), hand, Particle.DUST_COLOR_TRANSITION, 4, 0.1, 0, GLOW_SMALL, false);
            return;
        }
        ItemDisplay bell = Displays.item(ctx(), owner, hand, new ItemStack(BELL_ITEM),
                Displays.centered(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), BELL_SWING_TICKS + 6);
        bell.setTeleportDuration(1);
        bell.setBillboard(Display.Billboard.VERTICAL);
        remember(bell);
        Loop.repeat(ctx(), owner, 1, 1, BELL_SWING_TICKS, run -> {
            if (!bell.isValid()) {
                return false;
            }
            Displays.moveTo(bell, bellPoint(owner));
            float swing = (float) (Math.sin(run * 1.3) * 30 * (1 - run / (double) BELL_SWING_TICKS));
            float size = run < 2 ? 0.45f : 0.42f;
            Displays.animate(bell, Displays.centered(0, 0, 0, size, size, size, 0, 0, swing), 1);
            if (run % 3 == 0) {
                Vfx.dot(bellPoint(owner).add(0, -0.15, 0), Particle.DUST, OBANG_SMALL[(int) (run / 3) % OBANG.length],
                        false);
            }
            return true;
        }, () -> {
            if (bell.isValid()) {
                Displays.shrinkAway(ctx(), owner, bell, 3);
            }
        });
    }

    /** A five-coloured ripple running out from the shaman to the bell's reach. */
    private void ripple(Player owner, double radius, boolean strong) {
        Location center = owner.getLocation().add(0, 0.12, 0);
        int density = strong ? 7 : 3;
        Loop.repeat(ctx(), owner, 0, 1, RIPPLE_TICKS, run -> {
            double r = 0.5 + (radius - 0.5) * (run + 1) / RIPPLE_TICKS;
            obangRing(center, r, (int) Math.max(10, r * density), run * 0.2, false);
            return true;
        });
    }

    /** A body shakes off its affliction: black smoke rises out of it and is drawn up into the bell. */
    private void shedFx(LivingEntity body, Location hand) {
        Location at = Fx.body(body);
        World world = at.getWorld();
        world.spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 10), 0.25, 0.45, 0.25, 0, SMOKE_DUST);
        world.spawnParticle(Particle.SMOKE, at, Vfx.n(ctx(), 6), 0.2, 0.4, 0.2, 0.02);
        Vfx.cloud(ctx(), at.clone().add(0, 0.5, 0), Particle.END_ROD, 3, 0.2, 0.02, null, false);
        if (world.equals(hand.getWorld())) {
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Vfx.trail(at.clone().add(Fx.jitter(0.25f), Fx.jitter(0.4f), Fx.jitter(0.25f)), hand,
                        OBANG[4], 8 + i * 2, false);
            }
        }
        Fx.sound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.4f, 1.6f + Fx.jitter(0.1f));
    }

    /** A ground ring in the five colours, one coloured segment each, turned by {@code phase}. */
    private void obangRing(Location center, double radius, int points, double phase, boolean force) {
        int count = Vfx.n(ctx(), points);
        World world = center.getWorld();
        for (int i = 0; i < count; i++) {
            double angle = phase + i * TAU / count;
            int color = (int) Math.floor((double) i * OBANG.length / count) % OBANG.length;
            world.spawnParticle(Particle.DUST, center.getX() + Math.cos(angle) * radius, center.getY(),
                    center.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0, OBANG_SMALL[color], force);
        }
    }

    // ------------------------------------------------------------------ active: jangseung

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location spot = totemSpot(owner);
        int max = Math.clamp(params().integer("totem_max"), 1, MAX_TOTEMS);
        while (totems.size() >= max) {
            totems.removeFirst().sink();
        }
        boolean general = totemsRaised++ % 2 == 0;
        Totem totem = new Totem(owner, spot, owner.getLocation().getYaw(), general);
        totems.addLast(totem);
        totem.raise();
        return ActivationResult.success();
    }

    /**
     * Where the jangseung goes: the floor the shaman aims at within range; aiming past it, the floor under the end
     * of the range; at a wall, the floor in front of it. Falls back to the floor in front of the shaman.
     */
    private Location totemSpot(Player owner) {
        World world = owner.getWorld();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        double range = Math.max(1, params().num("totem_range"));
        Location spot = null;
        if (direction.lengthSquared() > 1.0E-6) {
            direction.normalize();
            RayTraceResult hit = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            if (hit != null && hit.getHitBlockFace() == BlockFace.UP) {
                spot = hit.getHitPosition().toLocation(world);
            } else {
                Location end = hit != null ? hit.getHitPosition().toLocation(world)
                        .subtract(direction.clone().multiply(0.4)) : eye.clone().add(direction.clone().multiply(range));
                spot = ground(world, end);
            }
        }
        if (spot == null || !standable(world, spot)) {
            Location front = owner.getLocation().add(Motion.horizontalFacing(owner.getLocation())
                    .multiply(TOTEM_FALLBACK));
            spot = ground(world, front.add(0, 1, 0));
            if (spot == null || !standable(world, spot)) {
                spot = owner.getLocation();
            }
        }
        return Displays.unrotated(spot);
    }

    /** The ground under {@code at} (within {@link #TOTEM_SNAP} blocks), or null. */
    private static @Nullable Location ground(World world, Location at) {
        Location probe = at.clone();
        if (!Spots.isFree(world, probe, 0.3, 0.2)) {
            return null;
        }
        Spots.snapToGround(world, probe, TOTEM_SNAP);
        return onGround(world, probe) ? probe : null;
    }

    /** True if a solid surface is right under {@code feet}. */
    private static boolean onGround(World world, Location feet) {
        return world.hasCollisionsIn(new BoundingBox(feet.getX() - 0.1, feet.getY() - 0.1, feet.getZ() - 0.1,
                feet.getX() + 0.1, feet.getY() - 0.01, feet.getZ() + 0.1));
    }

    /** True if a jangseung fits at {@code feet} (a post's room above solid ground, inside the arena). */
    private boolean standable(World world, Location feet) {
        return Spots.isFree(world, feet, 0.5, 2.2) && Spots.hasGroundBelow(world, feet) && ctx().isInsideArena(feet);
    }

    /** One jangseung: rises, curses and mends around it every half second, then sinks back into the ground. */
    private final class Totem implements LongPredicate {
        private final Player owner;
        private final Location base;
        private final float yaw;
        private final boolean general;
        private final long duration;
        private final double radius;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private final Map<UUID, Curse> curses = new HashMap<>();
        private @Nullable TextDisplay title;
        private boolean ended;
        private long age;

        Totem(Player owner, Location base, float yaw, boolean general) {
            this.owner = owner;
            this.base = base.clone();
            this.yaw = yaw;
            this.general = general;
            this.duration = Math.max(1, params().ticks("totem_duration"));
            this.radius = Math.max(0.5, params().num("totem_radius"));
        }

        void raise() {
            long life = duration + TOTEM_SINK_TICKS + 10;
            if (canSpawn(GENERAL.length + 1)) {
                for (TotemPart part : GENERAL) {
                    Material block = part.hat() && !general ? LADY_HAT : part.block();
                    BlockDisplay display = Displays.block(ctx(), owner, base, block.createBlockData(),
                            totemPose(part, yaw, 0.03f), life);
                    if (block == TOTEM_POST) {
                        display.setBrightness(null);
                    }
                    remember(display);
                    parts.add(display);
                }
                String name = general ? "천\n하\n대\n장\n군" : "지\n하\n여\n장\n군";
                String color = general ? RED_TAG : BLUE_TAG;
                Vector3f front = yawRotation(yaw).transform(new Vector3f(0.42f, 1.05f, 0.2f));
                TextDisplay text = Displays.text(ctx(), owner, base.clone().add(front.x(), front.y(), front.z()),
                        Text.mm("<b><" + color + ">" + name + "</" + color + "></b>"), 0.05f, life);
                text.setBillboard(Display.Billboard.VERTICAL);
                remember(text);
                Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f, 0, 0, 0),
                        TOTEM_RISE_TICKS);
                title = text;
                for (int i = 0; i < parts.size(); i++) {
                    BlockDisplay display = parts.get(i);
                    TotemPart part = GENERAL[i];
                    Displays.animateNextTick(ctx(), owner, display, totemPose(part, yaw, 1f), TOTEM_RISE_TICKS);
                }
            }
            World world = base.getWorld();
            org.bukkit.block.data.BlockData floor = base.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
            if (floor.getMaterial().isSolid()) {
                world.spawnParticle(Particle.BLOCK, base.clone().add(0, 0.1, 0), Vfx.n(ctx(), 16), 0.4, 0.05, 0.4,
                        0.1, floor);
                world.spawnParticle(Particle.DUST_PILLAR, base.clone().add(0, 0.1, 0), Vfx.n(ctx(), 8), 0.3, 0.05, 0.3,
                        0.1, floor);
            }
            Vfx.shockwave(ctx(), owner, base.clone().add(0, 0.1, 0), 0.3, radius, 7, Particle.DUST,
                    OBANG_DUST[general ? 1 : 0], false);
            Fx.sound(base, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.0f, 0.6f);
            Fx.sound(base, Sound.BLOCK_WOOD_PLACE, 1.0f, 0.6f);
            Fx.sound(base, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.8f, 0.7f);
            owner.sendActionBar(Text.mm("<" + (general ? RED_TAG : BLUE_TAG) + ">장승</" + (general ? RED_TAG
                    : BLUE_TAG) + "> <white>" + (general ? "천하대장군" : "지하여장군") + "</white> <gray>"
                    + Fx.seconds(duration) + "초</gray>"));
            Loop.repeat(ctx(), owner, TOTEM_RISE_TICKS, 1, duration, this, this::sink);
        }

        @Override
        public boolean test(long tick) {
            if (ended) {
                return false;
            }
            age = tick;
            if (tick % TOTEM_PULSE == 0) {
                pulse(tick);
            }
            if (tick % 5 == 0) {
                Location ring = base.clone().add(0, 0.12, 0);
                Vfx.ring(ctx(), ring, radius, (int) Math.max(14, radius * 5), tick * 0.04, Particle.DUST,
                        OBANG_SMALL[general ? 1 : 0], false);
            }
            if (tick % 20 == 0) {
                Location eyes = eyes();
                Vfx.dot(eyes, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
                Fx.sound(base, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.35f, 0.5f);
            }
            return true;
        }

        private Location eyes() {
            Vector3f at = yawRotation(yaw).transform(new Vector3f(0, 1.86f, 0.32f));
            return base.clone().add(at.x(), at.y(), at.z());
        }

        /** Every half second: curse the enemies in reach (refreshing weakness and slowness), mend the allies. */
        private void pulse(long tick) {
            Location center = base.clone().add(0, 1, 0);
            Location eyes = eyes();
            double weaken = Math.clamp(params().num("totem_weaken") / 100.0, 0.0, 1.0);
            double slow = Math.clamp(params().num("totem_slow") / 100.0, 0.0, 1.0);
            Set<UUID> inside = new HashSet<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                inside.add(enemy.getUniqueId());
                Curse old = curses.remove(enemy.getUniqueId());
                if (old != null) {
                    old.lift();
                }
                NamespacedKey[] keys = new NamespacedKey[2];
                keys[0] = ctx().addModifier(owner, enemy, Attribute.ATTACK_DAMAGE, -weaken,
                        Operation.MULTIPLY_SCALAR_1, TOTEM_CURSE_TICKS);
                keys[1] = ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                        TOTEM_CURSE_TICKS);
                curses.put(enemy.getUniqueId(), new Curse(enemy, keys));
                if (isVisible(enemy)) {
                    Location feet = enemy.getLocation().add(0, 0.15, 0);
                    Vfx.dot(Vfx.scatter(feet, 0.35), Particle.DUST, CURSE_DUST, false);
                    if (tick % 20 == 0) {
                        Vfx.dot(Fx.body(enemy), Particle.SQUID_INK, null, false);
                        Vfx.trail(eyes, Fx.body(enemy), CURSE_THREAD, 10, false);
                    }
                }
            }
            curses.keySet().removeIf(id -> !inside.contains(id));
            if (tick % 20 != 0) {
                return;
            }
            double heal = Math.max(0, params().num("totem_heal"));
            List<LivingEntity> mended = new ArrayList<>();
            if (owner.getLocation().distanceSquared(base) <= radius * radius) {
                mended.add(owner);
            }
            mended.addAll(ctx().alliesNear(owner, center, radius));
            for (LivingEntity ally : mended) {
                if (heal > 0) {
                    ctx().heal(ally, heal);
                }
                Location chest = Fx.body(ally);
                Vfx.cloud(ctx(), chest, Particle.DUST, 3, 0.3, 0, MEND_DUST, false);
                Vfx.trail(eyes, chest, MEND_THREAD, 10, false);
                if (tick % 40 == 0) {
                    Vfx.dot(chest.clone().add(0, 0.7, 0), Particle.HAPPY_VILLAGER, null, false);
                }
            }
        }

        /** Time is up (or a newer jangseung replaced it): it sinks back into the ground. */
        void sink() {
            if (ended) {
                return;
            }
            ended = true;
            totems.remove(this);
            for (Curse curse : curses.values()) {
                curse.lift();
            }
            curses.clear();
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay display = parts.get(i);
                if (display.isValid()) {
                    Displays.animate(display, totemPose(GENERAL[i], yaw, 0.03f), TOTEM_SINK_TICKS);
                    ctx().runLater(owner, TOTEM_SINK_TICKS, () -> {
                        if (display.isValid()) {
                            display.remove();
                        }
                    });
                }
            }
            TextDisplay text = title;
            if (text != null && text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, TOTEM_SINK_TICKS / 2);
            }
            World world = base.getWorld();
            world.spawnParticle(Particle.CLOUD, base.clone().add(0, 0.3, 0), Vfx.n(ctx(), 8), 0.3, 0.1, 0.3, 0.02);
            Fx.sound(base, Sound.BLOCK_WOOD_BREAK, 0.8f, 0.6f);
            Fx.sound(base, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.6f, 0.5f);
        }
    }

    /** The weakness and slowness one jangseung keeps on one enemy. */
    private final class Curse {
        private final LivingEntity enemy;
        private final NamespacedKey[] keys;

        Curse(LivingEntity enemy, NamespacedKey[] keys) {
            this.enemy = enemy;
            this.keys = keys;
        }

        void lift() {
            for (NamespacedKey key : keys) {
                ctx().removeModifier(enemy, key);
            }
        }
    }

    /** Players hidden from the owner (stealth) get no visible mark, so nothing gives them away. */
    private boolean isVisible(LivingEntity target) {
        return !(target instanceof Player hidden) || player().canSee(hidden);
    }

    /**
     * A jangseung part in its own space, grown to {@code grow} of its height from the ground (it rises out of the
     * earth), turned so the face looks to {@code yaw}. The display stays unrotated on the ground; all of this lives in
     * the transformation.
     */
    private static Transformation totemPose(TotemPart part, float yaw, float grow) {
        float g = Math.max(0.01f, grow);
        Quaternionf rotation = yawRotation(yaw);
        Vector3f center = new Vector3f(part.x(), part.y() * g, part.z());
        Vector3f size = new Vector3f(part.sx(), part.sy() * g, part.sz());
        Vector3f translation = rotation.transform(center).sub(rotation.transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, rotation, size, new Quaternionf());
    }

    // ------------------------------------------------------------------ ultimate: the gut

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        new Gut(owner, owner.getLocation(), owner.getLocation().getYaw()).start();
        return ActivationResult.success();
    }

    /**
     * The gut: five flags rise around the shaman's spot; inside the ring enemies take more damage from everything (the
     * drop in their health since the last tick is answered with the extra share), the blades cut them every second
     * and the shaman's side is shielded and healed. It ends in a flare that cuts once more.
     */
    private final class Gut implements LongPredicate {
        private final Player owner;
        private final Location center;
        private final float yaw;
        private final double radius;
        private final long duration;
        private final List<BlockDisplay> poles = new ArrayList<>();
        private final List<BlockDisplay> cloths = new ArrayList<>();
        private final List<Float> clothYaws = new ArrayList<>();
        private final List<Location> flagSpots = new ArrayList<>();
        /** The obangsaek colour of each flag (index into {@link #OBANG}). */
        private final int[] flagColors = {0, 1, 3, 4, 2};
        private final List<BlockDisplay> blades = new ArrayList<>();
        private final Map<UUID, Double> lastHealth = new HashMap<>();
        private int cuts;

        Gut(Player owner, Location at, float yaw) {
            this.owner = owner;
            this.center = Displays.unrotated(at);
            this.yaw = yaw;
            this.radius = Math.max(1, params().num("gut_radius"));
            this.duration = Math.max(1, params().ticks("gut_duration"));
        }

        void start() {
            long life = GUT_SETUP + duration + 30;
            World world = center.getWorld();
            // Four flags at the cardinal points (blue east, red south, white west, black north) and the yellow one
            // in the middle, behind the blades.
            for (int i = 0; i < FLAG_COUNT; i++) {
                double angle = i * TAU / FLAG_COUNT;
                Location spot = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
                flagSpots.add(flagGround(world, spot));
                clothYaws.add((float) Math.toDegrees(angle) + 90f);
            }
            Vector back = Motion.horizontalFacing(center).multiply(-0.9);
            flagSpots.add(center.clone().add(back));
            clothYaws.add(yaw + 90f);
            int[] order = flagColors;
            if (canSpawn(flagSpots.size() * 2 + 2)) {
                for (int i = 0; i < flagSpots.size(); i++) {
                    Location spot = flagSpots.get(i);
                    BlockDisplay pole = Displays.block(ctx(), owner, spot, FLAG_POLE.createBlockData(),
                            polePose(0.02f), life);
                    pole.setBrightness(null);
                    remember(pole);
                    poles.add(pole);
                    BlockDisplay cloth = Displays.block(ctx(), owner, spot, FLAG_CLOTH[order[i]].createBlockData(),
                            clothPose(clothYaws.get(i), 0f, 0.02f), life);
                    remember(cloth);
                    cloths.add(cloth);
                }
                for (int side = -1; side <= 1; side += 2) {
                    BlockDisplay blade = Displays.block(ctx(), owner, center, BLADE.createBlockData(),
                            bladePose(yaw, side, 0.02f), life);
                    remember(blade);
                    blades.add(blade);
                }
            }
            for (int i = 0; i < flagSpots.size(); i++) {
                int index = i;
                ctx().runLater(owner, 1 + i * FLAG_STAGGER, () -> raiseFlag(index, order[index]));
            }
            ctx().runLater(owner, 1, () -> {
                for (int i = 0; i < blades.size(); i++) {
                    Displays.animate(blades.get(i), bladePose(yaw, i == 0 ? -1 : 1, 1f), FLAG_RISE_TICKS);
                }
            });
            Vfx.dot(center.clone().add(0, 1, 0), Particle.FLASH, PALETTE.core(), true);
            Fx.sound(center, Sound.BLOCK_BELL_USE, 1.2f, 0.6f);
            Fx.sound(center, Sound.BLOCK_BELL_RESONATE, 0.8f, 0.8f);
            Fx.sound(center, Sound.ENTITY_EVOKER_PREPARE_WOLOLO, 0.8f, 1.2f);
            owner.sendActionBar(Text.mm("<" + RED_TAG + "><b>굿판</b></" + RED_TAG + "> <gray>"
                    + Fx.seconds(duration) + "초 · 반경 " + AbilityInfo.formatNumber(radius) + "블록</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, GUT_SETUP + duration, this, this::finale);
        }

        /** The flag spot on the ring, on the ground there if there is ground nearby. */
        private Location flagGround(World world, Location spot) {
            Location probe = spot.clone().add(0, 1.5, 0);
            if (Spots.isFree(world, probe, 0.2, 0.2)) {
                Spots.snapToGround(world, probe, 4);
                if (probe.getY() < spot.getY() + 1.5 - 1.0E-6) {
                    return Displays.unrotated(probe);
                }
            }
            return Displays.unrotated(spot);
        }

        private void raiseFlag(int index, int color) {
            if (index < poles.size()) {
                Displays.animate(poles.get(index), polePose(1f), FLAG_RISE_TICKS);
                Displays.animate(cloths.get(index), clothPose(clothYaws.get(index), 0f, 1f), FLAG_RISE_TICKS);
            }
            Location spot = flagSpots.get(index);
            Vfx.cloud(ctx(), spot.clone().add(0, 0.3, 0), Particle.DUST, 8, 0.25, 0, OBANG_DUST[color], true);
            Vfx.helix(ctx(), spot, 0.35, POLE_HEIGHT, 1.0, 1, 10, 0, Particle.DUST, OBANG_SMALL[color], true);
            Fx.sound(spot, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.2f, 0.6f + index * 0.06f);
            Fx.sound(spot, Sound.BLOCK_WOOL_PLACE, 0.9f, 0.8f);
        }

        @Override
        public boolean test(long tick) {
            if (tick < GUT_SETUP) {
                if (tick % 4 == 0) {
                    obangRing(center.clone().add(0, 0.1, 0), radius * (tick + 1) / GUT_SETUP,
                            (int) Math.max(16, radius * 6), tick * 0.1, true);
                }
                return true;
            }
            long ritual = tick - GUT_SETUP;
            amplify();
            if (ritual % GUT_PULSE == 0) {
                cut();
                bless();
            }
            if (ritual % GUT_BEAT == 0) {
                beat(ritual);
            }
            if (tick % 2 == 0) {
                waveFlags(tick);
            }
            if (tick % 4 == 0) {
                obangRing(center.clone().add(0, 0.1, 0), radius, (int) Math.max(18, radius * 6), tick * 0.05, true);
            }
            if (tick % 10 == 5) {
                streamers();
            }
            dance(tick);
            return true;
        }

        /** Everything that hurt an enemy in the ring since the last tick hurts it a share more. */
        private void amplify() {
            double share = Math.max(0, params().num("gut_vulnerable")) / 100.0;
            Set<UUID> inside = new HashSet<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                UUID id = enemy.getUniqueId();
                inside.add(id);
                double now = health(enemy);
                Double before = lastHealth.get(id);
                if (before != null && now < before - 1.0E-9 && share > 0) {
                    Combat.hit(ctx(), owner, enemy, (before - now) * share);
                    now = enemy.isDead() ? 0 : health(enemy);
                    if (isVisible(enemy)) {
                        Vfx.dot(Fx.body(enemy).add(0, 0.6, 0), Particle.DAMAGE_INDICATOR, null, false);
                    }
                }
                lastHealth.put(id, now);
            }
            lastHealth.keySet().removeIf(id -> !inside.contains(id));
        }

        /** The blades flash across every enemy in the ring. */
        private void cut() {
            double damage = params().num("gut_damage");
            Location bladeTop = center.clone().add(0, 0.4, 0);
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                Combat.hit(ctx(), owner, enemy, damage);
                cuts++;
                if (!isVisible(enemy)) {
                    continue;
                }
                Location body = Fx.body(enemy);
                Vector across = body.toVector().subtract(center.toVector()).setY(0);
                Vector side = across.lengthSquared() > 1.0E-4 ? across.normalize().crossProduct(new Vector(0, 1, 0))
                        : new Vector(1, 0, 0);
                Location a = body.clone().add(side.clone().multiply(0.5)).add(0, 0.45, 0);
                Location b = body.clone().subtract(side.clone().multiply(0.5)).subtract(0, 0.45, 0);
                Vfx.line(ctx(), a, b, 7, Particle.DUST, OBANG_SMALL[3], false);
                Vfx.line(ctx(), a.clone().subtract(0, 0.9, 0), b.clone().add(0, 0.9, 0), 7, Particle.DUST,
                        OBANG_SMALL[4], false);
                Vfx.dot(body, Particle.SWEEP_ATTACK, null, false);
                Vfx.trail(bladeTop, body, OBANG[3], 6, false);
                Fx.sound(body, Sound.ITEM_AXE_STRIP, 0.5f, 1.6f + Fx.jitter(0.1f));
            }
            Fx.sound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.2f + Fx.jitter(0.05f));
            for (BlockDisplay blade : blades) {
                if (blade.isValid()) {
                    Vfx.dot(blade.getLocation().add(0, 0.35, 0), Particle.WAX_OFF, null, false);
                }
            }
        }

        /** The shaman's side in the ring is shielded (Resistance I) and healed. */
        private void bless() {
            double heal = Math.max(0, params().num("gut_heal"));
            List<Player> blessed = new ArrayList<>();
            if (ctx().isAlive(owner) && owner.getLocation().distanceSquared(center) <= radius * radius) {
                blessed.add(owner);
            }
            blessed.addAll(ctx().alliesNear(owner, center, radius));
            for (Player ally : blessed) {
                ally.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, RESISTANCE_TICKS, 0, false, false,
                        true));
                if (heal > 0) {
                    ctx().heal(ally, heal);
                }
                Location chest = Fx.body(ally);
                Vfx.cloud(ctx(), chest, Particle.DUST_COLOR_TRANSITION, 4, 0.3, 0, GLOW, false);
            }
        }

        /** The drum of the gut, with a bell on the downbeat. */
        private void beat(long ritual) {
            boolean down = ritual % (GUT_BEAT * 4) == 0;
            Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, down ? 1.3f : 0.8f, down ? 0.55f : 0.7f);
            if (down) {
                Fx.sound(center, Sound.BLOCK_BELL_USE, 0.5f, 1.4f);
            } else {
                Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.2f + Fx.jitter(0.1f));
            }
        }

        /** The cloths flutter on their poles. */
        private void waveFlags(long tick) {
            for (int i = 0; i < cloths.size(); i++) {
                BlockDisplay cloth = cloths.get(i);
                if (cloth.isValid()) {
                    float wave = (float) (Math.sin(tick * 0.25 + i * 1.3) * 22);
                    Displays.animate(cloth, clothPose(clothYaws.get(i), wave, 1f), 2);
                }
            }
        }

        /** Coloured streamers from the flag tops to the yellow pole in the middle. */
        private void streamers() {
            Location top = flagSpots.get(flagSpots.size() - 1).clone().add(0, POLE_HEIGHT, 0);
            for (int i = 0; i < FLAG_COUNT && i < flagSpots.size() - 1; i++) {
                Location from = flagSpots.get(i).clone().add(0, POLE_HEIGHT, 0);
                Vfx.trail(from, top, OBANG[flagColors[i]], 12, false);
            }
        }

        /** While the shaman stands in the ring it dances in a coloured whirl. */
        private void dance(long tick) {
            if (!ctx().isAlive(owner) || !owner.getWorld().equals(center.getWorld())
                    || owner.getLocation().distanceSquared(center) > radius * radius) {
                return;
            }
            if (tick % 3 != 0) {
                return;
            }
            Location feet = owner.getLocation().add(0, 0.1, 0);
            for (int i = 0; i < OBANG.length; i++) {
                double angle = tick * 0.35 + i * TAU / OBANG.length;
                double height = 0.3 + ((tick / 3 + i) % 6) * 0.3;
                Location at = feet.clone().add(Math.cos(angle) * 0.7, height, Math.sin(angle) * 0.7);
                Vfx.dot(at, Particle.DUST, OBANG_SMALL[i], false);
            }
        }

        /** The gut ends: the flags flare up in five colours and the blades cut a last time. */
        private void finale() {
            double damage = params().num("gut_finale") * (1 + Math.max(0, params().num("gut_vulnerable")) / 100.0);
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                Combat.hit(ctx(), owner, enemy, damage);
                if (isVisible(enemy)) {
                    Vfx.burst(ctx(), Fx.body(enemy), PALETTE, 0.7, Particle.SWEEP_ATTACK, true);
                }
            }
            for (int i = 0; i < flagSpots.size(); i++) {
                Location top = flagSpots.get(i).clone().add(0, POLE_HEIGHT * 0.8, 0);
                Vfx.cloud(ctx(), top, Particle.FLAME, 8, 0.25, 0.02, null, true);
                Vfx.cloud(ctx(), top, Particle.DUST, 8, 0.3, 0, OBANG_DUST[flagColors[i % flagColors.length]],
                        true);
            }
            for (BlockDisplay display : poles) {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 8);
                }
            }
            for (BlockDisplay display : cloths) {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 6);
                }
            }
            for (BlockDisplay display : blades) {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 6);
                }
            }
            Location ground = center.clone().add(0, 0.1, 0);
            for (int i = 0; i < 3; i++) {
                double r = radius * (0.4 + 0.3 * i);
                obangRing(ground, r, (int) Math.max(16, r * 6), i * 0.5, true);
            }
            Fx.sound(center, Sound.BLOCK_BELL_USE, 1.3f, 0.8f);
            Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.4f, 0.5f);
            Fx.sound(center, Sound.ITEM_FIRECHARGE_USE, 0.8f, 0.8f);
            if (owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + RED_TAG + ">굿판</" + RED_TAG + "> <gray>작두날</gray> <white>" + cuts
                        + "번</white>"));
            }
        }
    }

    private static double health(LivingEntity entity) {
        return entity.getHealth() + entity.getAbsorptionAmount();
    }

    /** A flag pole from the ground, grown to {@code grow} of its height. */
    private static Transformation polePose(float grow) {
        float h = POLE_HEIGHT * Math.max(0.01f, grow);
        return new Transformation(new Vector3f(-POLE_WIDTH / 2, 0, -POLE_WIDTH / 2), new Quaternionf(),
                new Vector3f(POLE_WIDTH, h, POLE_WIDTH), new Quaternionf());
    }

    /**
     * A flag cloth hanging off the top of its pole, reaching out to the local +X side turned to {@code yaw} plus a
     * flutter of {@code wave} degrees about the pole. The display stays unrotated at the pole's foot.
     */
    private static Transformation clothPose(float yaw, float wave, float grow) {
        float g = Math.max(0.01f, grow);
        Quaternionf rotation = yawRotation(yaw + wave);
        Vector3f size = new Vector3f(CLOTH_WIDTH, CLOTH_HEIGHT, 0.05f).mul(g);
        Vector3f corner = new Vector3f(POLE_WIDTH / 2, (POLE_HEIGHT - CLOTH_HEIGHT - 0.05f) * g, -0.025f * g);
        return new Transformation(rotation.transform(corner), rotation, size, new Quaternionf());
    }

    /** One straw-cutter blade standing on its edge, along {@code yaw} ({@code side} -1 or 1). */
    private static Transformation bladePose(float yaw, int side, float grow) {
        float g = Math.max(0.01f, grow);
        Quaternionf rotation = yawRotation(yaw);
        Vector3f size = new Vector3f(0.05f, 0.3f * g, 1.1f);
        Vector3f center = new Vector3f(side * 0.16f, 0.15f * g, 0);
        Vector3f translation = rotation.transform(center).sub(rotation.transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, rotation, size, new Quaternionf());
    }

    private static Quaternionf yawRotation(float yaw) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw));
    }

    // ------------------------------------------------------------------ display budget

    /** True if {@code count} more displays stay within {@link #MAX_DISPLAYS}. */
    private boolean canSpawn(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    private void remember(Display display) {
        displays.add(display);
    }
}
