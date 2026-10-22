package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.Arrays;
import java.util.List;
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
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 천문학자 — calls the sky down. The night sky quickens its studies (shorter cooldown), meteors that strike enemies
 * light up a constellation over its head that pays out ultimate gauge when it is complete, the active rains a few
 * small meteors onto a marked spot, and the ultimate drops a giant meteor after a long, readable warning.
 *
 * <p>The ultimate needs no grant, ticks or hooks (the constellation only counts the active's meteors), so the
 * gambler may borrow it.
 *
 * <p>Look (white, star blue, deep night blue): at night faint stars twinkle around the astronomer and the unlit stars
 * of its constellation wait as dim points in a slowly turning crown over its head; every meteor hit lights one as a
 * bright four-pointed star, linked to the last by a thread of light, and the completed constellation flares with a
 * chime. Small meteors are glowing sea-lantern hearts in a blue glass corona that streak in at an angle from behind the
 * astronomer, trailing stardust, onto rings that close in as they approach; impacts flash and splash star sparks. The
 * great meteor is a tumbling boulder of blackstone and deepslate in a blue-hot glass shell dragging a long comet tail
 * across the sky, while its shadow spreads over a target seal of rings and a crosshair with a countdown; it lands in a
 * flash and a blast of rubble that heaves up around the crater, glowing blue cracks run out across the ground, the
 * shockwave rolls out to its edge and stardust drifts down afterwards.
 */
public final class AstronomerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("astronomer", "천문학자", Material.RECOVERY_COMPASS)
            .color(TextColor.color(0x6F8CFF))
            .difficulty(2)
            .passive("별자리",
                    "밤에는 액티브 쿨타임이 {night_cooldown}% 줄어듭니다. 유성으로 적을 맞힐 때마다 머리 위 별자리에 별이 "
                            + "하나씩 켜지고, {constellation_stars}개가 모두 켜지면 별자리가 완성되어 궁극기 게이지를 "
                            + "{constellation_gauge}% 얻습니다.")
            .active("유성",
                    "조준한 곳({meteor_range}블록 이내)에 낙하 지점을 표시하고, {meteor_delay}초 뒤부터 작은 유성 "
                            + "{meteor_count}개를 차례로 떨어뜨립니다. 유성은 떨어진 곳 반경 {meteor_radius}블록의 적에게 "
                            + "{meteor_damage} 피해를 주고 살짝 띄웁니다.",
                    9)
            .ultimate("대운석",
                    "조준한 곳({ult_range}블록 이내)에 {ult_delay}초 뒤 거대한 운석이 떨어집니다. 낙하 지점 반경 "
                            + "{ult_radius}블록의 적은 {ult_damage} 피해를 받고 튕겨 오르며, 반경 {shock_radius}블록까지 퍼지는 "
                            + "충격파가 그 바깥의 적에게 {shock_damage} 피해를 주고 밀쳐내며 {shock_slow}초간 구속시킵니다.")
            .param("night_cooldown", 30)
            .param("constellation_stars", 5)
            .param("constellation_gauge", 15)
            .param("meteor_range", 28)
            .param("meteor_count", 3)
            .param("meteor_delay", 0.75)
            .param("meteor_interval", 0.25)
            .param("meteor_radius", 2)
            .param("meteor_damage", 2)
            .param("ult_range", 35)
            .param("ult_delay", 2)
            .param("ult_radius", 4.5)
            .param("ult_damage", 8)
            .param("shock_radius", 9)
            .param("shock_damage", 3)
            .param("shock_slow", 2)
            .build();

    private static final String BLUE = "#6F8CFF";
    private static final String PALE = "#CFE0FF";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: white stars, star blue, night blue
    /** White starlight core, star blue body, deep night blue edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0x6F8CFF, 0x0B1340);
    private static final Color STARDUST = Color.fromRGB(0xCFE0FF);
    private static final Particle.DustOptions STAR_DUST = new Particle.DustOptions(STARDUST, 0.7f);
    private static final Particle.DustOptions FAINT_STAR = new Particle.DustOptions(Color.fromRGB(0x8696C8), 0.5f);
    private static final Particle.DustOptions SHADOW = new Particle.DustOptions(Color.fromRGB(0x070B26), 1.6f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.2f);
    private static final Particle.DustTransition GLOW_SMALL = PALETTE.glow(0.7f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.1f);

    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;
    /** Display entities this ability keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    private static final double GROUND_SNAP = 24;

    // passive
    private static final long NIGHT_CHECK_PERIOD = 20;
    private static final long TWINKLE_PERIOD = 6;
    private static final long CROWN_PERIOD = 10;
    private static final double CROWN_RADIUS = 0.55;
    private static final double CROWN_HEIGHT = 0.55;
    private static final float STAR_SCALE = 0.55f;
    private static final int STAR_POP_TICKS = 4;

    // active: meteor shower
    /** Ticks a small meteor is in the air before it lands. */
    private static final long FALL_TICKS = 9;
    private static final double FALL_DISTANCE = 16;
    /** Meteors land around the aim point on a circle of this radius (the first one in the middle for one meteor). */
    private static final double METEOR_SPREAD = 1.2;
    private static final double METEOR_PUSH = 0.3;
    private static final double METEOR_LIFT = 0.38;
    private static final Material METEOR_CORE = Material.SEA_LANTERN;
    private static final Material METEOR_SHELL = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final float METEOR_CORE_SIZE = 0.32f;
    private static final float METEOR_SHELL_SIZE = 0.58f;

    // ultimate: great meteor
    private static final double SKY_DISTANCE = 38;
    private static final float ROCK_SIZE = 2.1f;
    private static final float CORONA_SIZE = 2.7f;
    private static final float TAIL_WIDTH = 1.1f;
    private static final float TAIL_LENGTH = 7.5f;
    private static final float WAKE_WIDTH = 2.0f;
    private static final float WAKE_LENGTH = 4.5f;
    private static final int METEOR_GROW_TICKS = 10;
    private static final int POSE_PERIOD = 2;
    private static final Material[] ROCK_BLOCKS = {Material.BLACKSTONE, Material.COBBLED_DEEPSLATE,
            Material.POLISHED_BLACKSTONE};
    private static final Material CORONA_BLOCK = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final Material TAIL_BLOCK = Material.WHITE_STAINED_GLASS;
    private static final Material WAKE_BLOCK = Material.BLUE_STAINED_GLASS;
    private static final Material CRACK_BLOCK = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final Material RUBBLE_FALLBACK = Material.COBBLED_DEEPSLATE;
    private static final Quaternionf[] ROCK_ORIENTATIONS = {
            new Quaternionf(),
            new Quaternionf().rotateY((float) Math.toRadians(45)).rotateX((float) Math.toRadians(35.26)),
            new Quaternionf().rotateZ((float) Math.toRadians(45)).rotateY((float) Math.toRadians(30)),
    };
    private static final Vector3f TUMBLE_AXIS = new Vector3f(0.4f, 1f, 0.25f).normalize();
    /** After the impact the boulder's rocks fly apart for this long, then crumble away over {@link #CRUMBLE_TICKS}. */
    private static final int SHATTER_TICKS = 5;
    private static final int CRUMBLE_TICKS = 8;
    private static final int RUBBLE = 8;
    private static final int CRACKS = 6;
    private static final long RUBBLE_HOLD_TICKS = 30;
    private static final long CRACK_HOLD_TICKS = 40;
    private static final int AFTERGLOW_TICKS = 30;
    private static final double INNER_PUSH = 0.6;
    private static final double SHOCK_PUSH = 1.0;
    private static final double SHOCK_LIFT = 0.3;
    private static final int SHOCK_SLOW_AMPLIFIER = 1;

    /**
     * The constellation's star positions on the crown: angle (fraction of a turn) and height, a zig-zag that reads
     * as a "W" (Cassiopeia) from the side. Only the first {@code constellation_stars} are used.
     */
    private static final double[][] CROWN = {
            {0.0, 0.1}, {0.2, -0.05}, {0.4, 0.12}, {0.6, -0.04}, {0.8, 0.14},
            {0.1, 0.25}, {0.3, 0.22}, {0.5, 0.26}, {0.7, 0.2}, {0.9, 0.24},
    };

    private boolean night;
    private boolean granted;
    private int liveDisplays;
    /** Lit stars of the constellation, 0..{@code constellation_stars}. */
    private int stars;
    /** The lit stars' displays by slot (null where the display budget was spent: the star still counts). */
    private final @Nullable TextDisplay[] starLabels = new TextDisplay[CROWN.length];

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: constellation

    @Override
    public void onGrant() {
        granted = true;
        updateNight(true);
    }

    @Override
    public void onRevoke() {
        // The context removes the displays; only forget them here.
        granted = false;
        stars = 0;
        Arrays.fill(starLabels, null);
        night = false;
    }

    /** At night the active comes back sooner. */
    @Override
    public double cooldownMultiplier() {
        if (!isNight()) {
            return 1.0;
        }
        return 1 - Math.clamp(params().num("night_cooldown") / 100.0, 0.0, 0.95);
    }

    /** Night in a world with a day cycle; the nether and the end (fixed time) have no night sky. */
    private boolean isNight() {
        World world = player().getWorld();
        return !world.isFixedTime() && !world.isDayTime();
    }

    @Override
    public void onTick(long tick) {
        if (tick % NIGHT_CHECK_PERIOD == 0) {
            updateNight(false);
        }
        Player owner = player();
        if (stars > 0) {
            followStars(owner, tick);
        }
        if (tick % CROWN_PERIOD == 0 && (night || stars > 0)) {
            drawCrown(owner, tick);
        }
        if (night && tick % TWINKLE_PERIOD == 0) {
            // Faint stars twinkling around the astronomer under the night sky.
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location at = owner.getLocation().add(random.nextDouble(-1.2, 1.2), random.nextDouble(0.3, 2.3),
                    random.nextDouble(-1.2, 1.2));
            if (random.nextInt(3) == 0) {
                Vfx.dot(at, Particle.END_ROD, null, false);
            } else {
                Vfx.dot(at, Particle.DUST, STAR_DUST, false);
            }
        }
    }

    private void updateNight(boolean first) {
        boolean now = isNight();
        if (now == night && !first) {
            return;
        }
        night = now;
        if (now) {
            Player owner = player();
            Location head = owner.getEyeLocation().add(0, 0.6, 0);
            Vfx.ring(ctx(), head, 0.8, 16, 0, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
            Vfx.cloud(ctx(), head, Particle.END_ROD, 6, 0.5, 0.02, null, false);
            Fx.sound(head, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
            Fx.sound(head, Sound.BLOCK_BEACON_AMBIENT, 0.5f, 1.8f);
            owner.sendActionBar(Text.mm("<" + PALE + ">☾ 밤하늘이 열렸습니다</" + PALE + "> <gray>쿨타임 -"
                    + AbilityInfo.formatNumber(params().num("night_cooldown")) + "%</gray>"));
        }
    }

    private int starsNeeded() {
        return Math.clamp(params().integer("constellation_stars"), 0, CROWN.length);
    }

    /** Where star {@code index} of the crown is now: a slowly turning ring over the head. */
    private Location starPoint(Player owner, int index, long tick) {
        double[] slot = CROWN[index];
        double angle = slot[0] * TAU + tick * 0.02;
        Location head = owner.getLocation().add(0, owner.getHeight() + CROWN_HEIGHT + slot[1], 0);
        head.add(Math.cos(angle) * CROWN_RADIUS, 0, Math.sin(angle) * CROWN_RADIUS);
        head.setYaw(0);
        head.setPitch(0);
        return head;
    }

    private void followStars(Player owner, long tick) {
        for (int i = 0; i < stars; i++) {
            TextDisplay star = starLabels[i];
            if (star != null && star.isValid() && star.getWorld().equals(owner.getWorld())) {
                Displays.moveTo(star, starPoint(owner, i, tick));
            }
        }
    }

    /** The unlit stars as dim points and the lit ones joined by faint threads of light. */
    private void drawCrown(Player owner, long tick) {
        int needed = starsNeeded();
        for (int i = 0; i < needed; i++) {
            Location point = starPoint(owner, i, tick);
            if (i >= stars) {
                Vfx.dot(point, Particle.DUST, FAINT_STAR, false);
            } else {
                if (starLabels[i] == null) {
                    Vfx.dot(point, Particle.END_ROD, null, false);
                }
                if (i > 0) {
                    Vfx.line(ctx(), starPoint(owner, i - 1, tick), point, 4, Particle.DUST, STAR_DUST, false);
                }
            }
        }
    }

    /** A meteor of the active struck an enemy: one more star lights up; a full constellation pays out gauge. */
    private void addStar() {
        int needed = starsNeeded();
        if (!granted || needed <= 0) {
            return;
        }
        Player owner = player();
        long tick = ctx().currentTick();
        int index = Math.min(stars, CROWN.length - 1);
        Location point = starPoint(owner, index, tick);
        if (claim(1)) {
            TextDisplay star = Displays.text(ctx(), owner, point,
                    Text.mm("<b><gradient:#FFFFFF:#CFE0FF>✦</gradient></b>"), 0.1f, 0);
            star.setTeleportDuration(2);
            Displays.animateNextTick(ctx(), owner, star, Displays.centered(0, 0, 0, STAR_SCALE * 1.6f,
                    STAR_SCALE * 1.6f, STAR_SCALE * 1.6f, 0, 0, 0), 2);
            ctx().runLater(owner, 3, () -> Displays.animate(star, Displays.centered(0, 0, 0, STAR_SCALE, STAR_SCALE,
                    STAR_SCALE, 0, 0, 0), STAR_POP_TICKS));
            starLabels[index] = star;
        }
        stars = index + 1;
        Vfx.dot(point, Particle.FLASH, PALETTE.core(), false);
        Vfx.cloud(ctx(), point, Particle.END_ROD, 4, 0.1, 0.03, null, false);
        Fx.sound(point, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.0f + index * 0.15f);
        Fx.sound(point, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 0.9f + index * 0.2f);
        if (stars >= needed) {
            completeConstellation(owner, tick);
        } else {
            owner.sendActionBar(Text.mm("<" + PALE + ">✦ 별자리</" + PALE + "> <white>" + stars + "/" + needed
                    + "</white>"));
        }
    }

    /** All stars lit: the constellation flares, chimes, grants gauge and the stars drift apart. */
    private void completeConstellation(Player owner, long tick) {
        int needed = stars;
        for (int i = 0; i < needed; i++) {
            Location point = starPoint(owner, i, tick);
            Location next = starPoint(owner, (i + 1) % needed, tick);
            Vfx.line(ctx(), point, next, 8, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Vfx.dot(point, Particle.FLASH, PALETTE.core(), true);
        }
        Location head = owner.getLocation().add(0, owner.getHeight() + CROWN_HEIGHT, 0);
        Vfx.ring(ctx(), head, 1.1, 20, 0, Particle.DUST_COLOR_TRANSITION, GLOW, true);
        Vfx.cloud(ctx(), head, Particle.FIREWORK, 14, 0.4, 0.08, null, true);
        Fx.sound(head, Sound.BLOCK_BELL_RESONATE, 0.8f, 1.6f);
        Fx.sound(head, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
        Fx.sound(head, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.2f);
        double gauge = Math.max(0, params().num("constellation_gauge"));
        if (gauge > 0) {
            ctx().addGauge(owner, gauge);
        }
        owner.sendActionBar(Text.mm("<" + PALE + "><b>✦ 별자리 완성!</b></" + PALE + "> <white>궁극기 +"
                + AbilityInfo.formatNumber(gauge) + "%</white>"));
        for (int i = 0; i < starLabels.length; i++) {
            TextDisplay star = starLabels[i];
            starLabels[i] = null;
            if (star == null) {
                continue;
            }
            ctx().runLater(owner, 11, () -> release(1));
            if (!star.isValid()) {
                continue;
            }
            Displays.animate(star, Displays.centered(0, 0.4f, 0, STAR_SCALE * 1.8f, STAR_SCALE * 1.8f,
                    STAR_SCALE * 1.8f, 0, 0, 0), 4);
            ctx().runLater(owner, 5, () -> {
                if (star.isValid()) {
                    Displays.shrinkAway(ctx(), owner, star, 6);
                }
            });
        }
        stars = 0;
    }

    // ------------------------------------------------------------------ active: meteor shower

    @Override
    public ActivationResult onActive() {
        double range = Math.max(1, params().num("meteor_range"));
        Location center = aimPoint(range);
        if (center == null) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안의 땅이나 적을 조준하세요");
        }
        Player owner = player();
        int count = Math.max(1, params().integer("meteor_count"));
        long first = Math.max(FALL_TICKS / 2, params().ticks("meteor_delay"));
        long interval = Math.max(1, params().ticks("meteor_interval"));
        Vector sky = skyDirection(owner);
        double phase = ThreadLocalRandom.current().nextDouble(TAU);
        for (int i = 0; i < count; i++) {
            Location spot = center.clone();
            if (count > 1) {
                double angle = phase + i * TAU / count;
                Location shifted = spot.clone().add(Math.cos(angle) * METEOR_SPREAD, 0, Math.sin(angle) * METEOR_SPREAD);
                // Keep a meteor on its side of a wall: the middle stands in for a spot behind one.
                if (Spots.clearPath(center.clone().add(0, 0.5, 0), shifted.clone().add(0, 0.5, 0))) {
                    Double ground = groundBelow(shifted.clone().add(0, 1.5, 0));
                    if (ground != null) {
                        shifted.setY(ground);
                    }
                    spot = shifted;
                }
            }
            new SmallMeteor(spot, sky, first + i * interval).start();
        }
        castFx(owner, center);
        return ActivationResult.success();
    }

    /** The cast: a ring of starlight at the astronomer's raised hand and a pointer of light to the marked spot. */
    private void castFx(Player owner, Location center) {
        Location hand = owner.getEyeLocation().add(owner.getEyeLocation().getDirection().multiply(0.6)).add(0, 0.2, 0);
        Vfx.ring(ctx(), hand, 0.35, 10, 0, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
        Vfx.cloud(ctx(), hand, Particle.END_ROD, 4, 0.1, 0.03, null, false);
        Vfx.trail(hand, center.clone().add(0, 0.3, 0), STARDUST, 10, false);
        Fx.sound(hand, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(hand, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 0.6f, 1.4f + Fx.jitter(0.1f));
    }

    /** The meteors come in from high behind the astronomer, so they streak overhead towards the target. */
    private static Vector skyDirection(Player owner) {
        Vector back = Motion.horizontalFacing(owner.getLocation()).multiply(-0.55);
        return back.add(new Vector(0, 1, 0)).normalize();
    }

    /**
     * One small meteor: its landing spot is marked at once by a ring that closes in until it lands; it streaks in
     * over the last {@link #FALL_TICKS} ticks and strikes every enemy around the spot.
     */
    private final class SmallMeteor implements LongPredicate {
        private final Location spot;
        private final Location sky;
        private final long landTick;
        private final double radius;
        private @Nullable BlockDisplay core;
        private @Nullable BlockDisplay shell;
        private float spin;

        SmallMeteor(Location spot, Vector skyDirection, long landTick) {
            this.spot = spot;
            this.sky = spot.clone().add(skyDirection.clone().multiply(FALL_DISTANCE));
            this.landTick = landTick;
            this.radius = Math.max(0.3, params().num("meteor_radius"));
        }

        void start() {
            Loop.repeat(ctx(), player(), 0, 1, landTick + 1, this);
        }

        @Override
        public boolean test(long tick) {
            long left = landTick - tick;
            if (left <= 0) {
                land();
                return false;
            }
            if (tick % 2 == 0) {
                telegraph(left);
            }
            long fallStart = Math.max(0, landTick - FALL_TICKS);
            if (tick == fallStart) {
                // Spawned at the top of its streak; it moves from the next tick on, so the client can interpolate.
                spawn();
            } else if (tick > fallStart) {
                fall((double) (tick - fallStart) / (landTick - fallStart));
            }
            return true;
        }

        /** The landing ring and a ring closing in from it: when they meet, the meteor lands. */
        private void telegraph(long left) {
            Location floor = spot.clone().add(0, 0.1, 0);
            double t = Math.min(1.0, (double) left / landTick);
            // The landing ring is a telegraph victims must read, so it is visible from afar.
            Vfx.ring(ctx(), floor, radius, (int) Math.max(12, radius * 8), left * 0.05, Particle.DUST,
                    STAR_DUST, true);
            Vfx.ring(ctx(), floor, Math.max(0.15, radius * t), 10, -left * 0.2, Particle.DUST_COLOR_TRANSITION,
                    GLOW_SMALL, false);
            if (left % 6 == 0) {
                Vfx.dot(floor.clone().add(0, 0.15, 0), Particle.END_ROD, null, false);
            }
        }

        private void spawn() {
            Player owner = player();
            if (!claim(2)) {
                return;
            }
            long life = FALL_TICKS + 4;
            ctx().runLater(owner, life, () -> release(2));
            Location at = plain(sky);
            core = Displays.block(ctx(), owner, at, METEOR_CORE.createBlockData(), cube(METEOR_CORE_SIZE,
                    new Quaternionf()), life);
            shell = Displays.block(ctx(), owner, at, METEOR_SHELL.createBlockData(), cube(METEOR_SHELL_SIZE,
                    new Quaternionf()), life);
            core.setTeleportDuration(1);
            shell.setTeleportDuration(1);
            Fx.sound(spot, Sound.ENTITY_PHANTOM_SWOOP, 0.7f, 1.6f + Fx.jitter(0.1f));
        }

        /** Moves the meteor along its streak ({@code t} from 0 to 1) with a stardust trail. */
        private void fall(double t) {
            Location from = sky.clone().add(spot.clone().subtract(sky).toVector().multiply(Math.max(0, t - 0.1)));
            Location at = sky.clone().add(spot.clone().subtract(sky).toVector().multiply(t)).add(0, 0.3, 0);
            spin += 35f;
            Quaternionf rotation = new Quaternionf().rotateAxis((float) Math.toRadians(spin), TUMBLE_AXIS);
            if (core != null && core.isValid()) {
                Displays.moveTo(core, at);
                Displays.animate(core, cube(METEOR_CORE_SIZE, rotation), 1);
            }
            if (shell != null && shell.isValid()) {
                Displays.moveTo(shell, at);
                Displays.animate(shell, cube(METEOR_SHELL_SIZE, new Quaternionf(rotation).conjugate()), 1);
            }
            Vfx.line(ctx(), from, at, 3, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
            Vfx.dot(at, Particle.END_ROD, null, false);
            World world = at.getWorld();
            world.spawnParticle(Particle.FIREWORK, at, Vfx.n(ctx(), 2), 0.12, 0.12, 0.12, 0.02);
            Vfx.trail(at, from.clone().add(Fx.jitter(0.3f), Fx.jitter(0.3f), Fx.jitter(0.3f)), STARDUST, 8, false);
        }

        /** The meteor lands: every enemy around the spot is struck and tossed up a little. */
        private void land() {
            for (BlockDisplay display : new BlockDisplay[] {core, shell}) {
                if (display != null && display.isValid()) {
                    display.remove();
                }
            }
            Player owner = player();
            Location center = spot.clone().add(0, 0.5, 0);
            double damage = params().num("meteor_damage");
            boolean struck = false;
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                if (!Spots.clearPath(center, Fx.body(enemy))) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                struck = true;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = enemy.getLocation().toVector().subtract(spot.toVector()).setY(0);
                Vector push = away.lengthSquared() < 1.0E-4 ? new Vector() : away.normalize().multiply(METEOR_PUSH);
                Motion.push(enemy, push.setY(METEOR_LIFT), MAX_SPEED);
                Vfx.cloud(ctx(), Fx.body(enemy), Particle.END_ROD, 3, 0.2, 0.05, null, false);
            }
            impactFx(spot);
            if (struck) {
                addStar();
            }
        }
    }

    /** A small meteor's impact: a flash, a splash of star sparks, a ring of stardust and ground debris. */
    private void impactFx(Location spot) {
        World world = spot.getWorld();
        Location at = spot.clone().add(0, 0.3, 0);
        Vfx.burst(ctx(), at, PALETTE, 0.9, Particle.END_ROD, false);
        world.spawnParticle(Particle.FIREWORK, at, Vfx.n(ctx(), 10), 0.2, 0.15, 0.2, 0.18);
        Vfx.shockwave(ctx(), player(), spot.clone().add(0, 0.1, 0), 0.3, Math.max(0.5, params().num("meteor_radius")),
                4, Particle.DUST_COLOR_TRANSITION, FADE, false);
        BlockData ground = groundData(spot);
        if (ground != null) {
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 10), 0.35, 0.1, 0.35, 0.15, ground);
        }
        Location glow = spot.clone();
        Loop.repeat(ctx(), player(), 2, 2, 6, run -> {
            Location mote = glow.clone().add(Fx.jitter(0.6f), 0.15 + run * 0.15, Fx.jitter(0.6f));
            Vfx.dot(mote, Particle.DUST, STAR_DUST, false);
            return true;
        });
        Fx.sound(at, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 0.7f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.9f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.6f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ ultimate: great meteor

    @Override
    public ActivationResult onUltimate() {
        double range = Math.max(1, params().num("ult_range"));
        Location impact = aimPoint(range);
        if (impact == null) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안의 땅이나 적을 조준하세요");
        }
        new GreatMeteor(impact, skyDirection(player())).begin();
        return ActivationResult.success();
    }

    /**
     * The great meteor: visible from the moment it is called, it falls along a straight line from high in the sky,
     * accelerating, while its target seal and growing shadow mark where it will land.
     */
    private final class GreatMeteor implements LongPredicate {
        private final Location impact;
        private final Location sky;
        private final Vector travel;
        private final World world;
        private final long delay;
        private final double radius;
        private final double shockRadius;
        private final BlockDisplay[] rocks = new BlockDisplay[ROCK_ORIENTATIONS.length];
        private @Nullable BlockDisplay corona;
        private @Nullable BlockDisplay tail;
        private @Nullable BlockDisplay wake;
        private @Nullable TextDisplay countdown;
        private boolean shown;

        GreatMeteor(Location impact, Vector skyDirection) {
            this.impact = impact;
            this.world = impact.getWorld();
            this.sky = impact.clone().add(skyDirection.clone().multiply(SKY_DISTANCE));
            this.travel = impact.toVector().subtract(sky.toVector());
            this.delay = Math.max(10, params().ticks("ult_delay"));
            this.radius = Math.max(0.5, params().num("ult_radius"));
            this.shockRadius = Math.max(radius, params().num("shock_radius"));
        }

        void begin() {
            Player owner = player();
            long life = delay + 6;
            // The rocks outlive the impact: they are hurled apart and crumble away (see shatterRocks).
            long rockLife = delay + SHATTER_TICKS + CRUMBLE_TICKS + 5;
            if (claim(ROCK_ORIENTATIONS.length + 3)) {
                shown = true;
                ctx().runLater(owner, life, () -> release(3));
                ctx().runLater(owner, rockLife, () -> release(ROCK_ORIENTATIONS.length));
                Location at = plain(sky);
                for (int i = 0; i < rocks.length; i++) {
                    rocks[i] = Displays.block(ctx(), owner, at, ROCK_BLOCKS[i % ROCK_BLOCKS.length].createBlockData(),
                            cube(0.1f, ROCK_ORIENTATIONS[i]), rockLife);
                    rocks[i].setTeleportDuration(POSE_PERIOD);
                    rocks[i].setViewRange(3f);
                }
                corona = Displays.block(ctx(), owner, at, CORONA_BLOCK.createBlockData(), cube(0.1f, new Quaternionf()),
                        life);
                tail = Displays.block(ctx(), owner, at, TAIL_BLOCK.createBlockData(), streak(TAIL_WIDTH * 0.1f,
                        TAIL_LENGTH * 0.1f), life);
                wake = Displays.block(ctx(), owner, at, WAKE_BLOCK.createBlockData(), streak(WAKE_WIDTH * 0.1f,
                        WAKE_LENGTH * 0.1f), life);
                for (BlockDisplay display : new BlockDisplay[] {corona, tail, wake}) {
                    display.setTeleportDuration(POSE_PERIOD);
                    display.setViewRange(3f);
                }
                Displays.outline(corona, PALETTE.body());
            }
            if (claim(1)) {
                ctx().runLater(owner, life, () -> release(1));
                countdown = Displays.text(ctx(), owner, impact.clone().add(0, 2.4, 0), Text.mm(" "), 1.6f, life);
                countdown.setViewRange(3f);
            }
            Location floor = floor();
            Vfx.shockwave(ctx(), owner, floor, 0.5, radius, 6, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Vfx.cloud(ctx(), sky, Particle.END_ROD, 30, 1.2, 0.1, null, true);
            Vfx.dot(sky, Particle.FLASH, PALETTE.core(), true);
            Fx.sound(owner.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 0.6f);
            Fx.sound(owner.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 0.5f, 1.4f);
            Fx.sound(impact, Sound.BLOCK_BELL_RESONATE, 1.4f, 0.6f);
            Loop.repeat(ctx(), owner, 0, 1, delay + 1, this);
        }

        @Override
        public boolean test(long tick) {
            if (tick >= delay) {
                strike();
                return false;
            }
            double t = (double) tick / delay;
            if (shown && tick % POSE_PERIOD == 0) {
                pose(tick + POSE_PERIOD);
            }
            seal(tick, t);
            trail(tick, t);
            long left = delay - tick;
            TextDisplay label = countdown;
            if (label != null && label.isValid() && tick % 2 == 0) {
                String color = left <= 10 ? "#FFFFFF" : PALE;
                label.text(Text.mm("<b><" + color + ">☄ " + Fx.seconds(left) + "</" + color + "></b>"));
            }
            if (tick % 10 == 0) {
                Location now = point(t);
                Fx.sound(now, Sound.ITEM_ELYTRA_FLYING, 0.6f + (float) t, 0.6f + 0.6f * (float) t);
                Fx.sound(impact, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 0.5f + (float) t);
            }
            if (left == 12) {
                Fx.sound(impact, Sound.ENTITY_PHANTOM_SWOOP, 2.0f, 0.5f);
                Fx.sound(impact, Sound.ITEM_TRIDENT_THUNDER, 0.8f, 1.4f);
            }
            return true;
        }

        /** Where the meteor is at progress {@code t}: it accelerates towards the ground. */
        private Location point(double t) {
            double eased = t * t;
            return sky.clone().add(travel.clone().multiply(eased));
        }

        /** Moves, grows and tumbles the meteor; the tail stays aligned with its fall. */
        private void pose(long targetTick) {
            double t = Math.min(1.0, (double) targetTick / delay);
            Location at = plain(point(t)).add(0, ROCK_SIZE * 0.4, 0);
            float growth = Math.min(1f, (float) targetTick / METEOR_GROW_TICKS);
            float angle = targetTick * 0.12f;
            for (int i = 0; i < rocks.length; i++) {
                BlockDisplay rock = rocks[i];
                if (rock == null || !rock.isValid()) {
                    continue;
                }
                Displays.moveTo(rock, at);
                Quaternionf rotation = new Quaternionf().rotateAxis(angle * (1 + 0.2f * i), TUMBLE_AXIS)
                        .mul(ROCK_ORIENTATIONS[i]);
                Displays.animate(rock, cube(ROCK_SIZE * growth, rotation), POSE_PERIOD);
            }
            float pulse = 1f + 0.05f * (float) Math.sin(targetTick * 0.6);
            if (corona != null && corona.isValid()) {
                Displays.moveTo(corona, at);
                Displays.animate(corona, cube(CORONA_SIZE * growth * pulse, new Quaternionf().rotateAxis(-angle,
                        TUMBLE_AXIS)), POSE_PERIOD);
            }
            if (tail != null && tail.isValid()) {
                Displays.moveTo(tail, at);
                Displays.animate(tail, streak(TAIL_WIDTH * growth, TAIL_LENGTH * growth), POSE_PERIOD);
            }
            if (wake != null && wake.isValid()) {
                Displays.moveTo(wake, at);
                Displays.animate(wake, streak(WAKE_WIDTH * growth * pulse, WAKE_LENGTH * growth), POSE_PERIOD);
            }
        }

        /**
         * A box of the given width that starts at the meteor and runs back up its path, for the comet tail. The
         * display stands at the meteor; its +Z axis is turned to point back along the fall.
         */
        private Transformation streak(float width, float length) {
            Vector back = travel.clone().multiply(-1);
            Vector3f direction = new Vector3f((float) back.getX(), (float) back.getY(), (float) back.getZ());
            if (direction.lengthSquared() < 1.0E-6f) {
                direction.set(0, 1, 0);
            }
            Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), direction.normalize());
            Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, 0));
            return new Transformation(translation, rotation, new Vector3f(width, width, length), new Quaternionf());
        }

        /** The target seal: the impact ring, the shockwave's edge, a crosshair and the meteor's growing shadow. */
        private void seal(long tick, double t) {
            Location floor = floor();
            if (tick % 2 == 0) {
                Vfx.ring(ctx(), floor, radius, (int) Math.min(48, Math.max(16, radius * 7)), tick * 0.03,
                        Particle.DUST_COLOR_TRANSITION, GLOW, true);
                // The shadow spreads as the meteor nears.
                double shadow = radius * Math.min(1.0, t * t * 1.1);
                if (shadow > 0.3) {
                    Vfx.ring(ctx(), floor.clone().add(0, 0.02, 0), shadow, (int) Math.max(10, shadow * 6), -tick * 0.05,
                            Particle.DUST, SHADOW, true);
                    Vfx.ring(ctx(), floor.clone().add(0, 0.02, 0), shadow * 0.5, (int) Math.max(8, shadow * 3),
                            tick * 0.07, Particle.DUST, SHADOW, true);
                }
            }
            if (tick % 4 == 0) {
                Vfx.ring(ctx(), floor, shockRadius, (int) Math.min(56, Math.max(20, shockRadius * 5)), -tick * 0.02,
                        Particle.DUST, STAR_DUST, true);
                double arm = radius * 0.7;
                double turn = tick * 0.02;
                for (int k = 0; k < 4; k++) {
                    double angle = turn + k * Math.PI / 2;
                    Location from = floor.clone().add(Math.cos(angle) * radius * 0.25, 0, Math.sin(angle) * radius * 0.25);
                    Location to = floor.clone().add(Math.cos(angle) * arm, 0, Math.sin(angle) * arm);
                    Vfx.line(ctx(), from, to, 2.5, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, true);
                }
            }
        }

        /** The meteor's burning trail: blue fire, stardust, sparks and smoke along its fall. */
        private void trail(long tick, double t) {
            Location at = point(t).add(0, ROCK_SIZE * 0.4, 0);
            Vector back = travel.clone().normalize().multiply(-1);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int count = Vfx.n(ctx(), 4);
            for (int i = 0; i < count; i++) {
                Location from = at.clone().add(random.nextGaussian() * 0.7, random.nextGaussian() * 0.7,
                        random.nextGaussian() * 0.7);
                Location to = from.clone().add(back.clone().multiply(3 + random.nextDouble() * 4));
                Vfx.trail(from, to, i % 2 == 0 ? STARDUST : PALETTE.body(), 10 + random.nextInt(6), true);
            }
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, Vfx.n(ctx(), 4), 0.6, 0.6, 0.6, 0.03, null, true);
            world.spawnParticle(Particle.END_ROD, at, Vfx.n(ctx(), 3), 0.8, 0.8, 0.8, 0.05, null, true);
            if (tick % 2 == 0) {
                Location smoke = at.clone().add(back.clone().multiply(2.5));
                world.spawnParticle(Particle.LARGE_SMOKE, smoke, Vfx.n(ctx(), 2), 0.5, 0.5, 0.5, 0.01, null, true);
            }
            if (!shown) {
                // Without display budget the meteor is drawn with particles alone.
                Vfx.sphere(ctx(), at, ROCK_SIZE * 0.5, 22, Particle.DUST_COLOR_TRANSITION, FADE, true);
            }
        }

        /** Impact: the crater blast, the shockwave and the debris. */
        private void strike() {
            Player owner = player();
            for (BlockDisplay display : new BlockDisplay[] {corona, tail, wake}) {
                if (display != null && display.isValid()) {
                    display.remove();
                }
            }
            if (countdown != null && countdown.isValid()) {
                countdown.remove();
            }
            shatterRocks();
            Location center = impact.clone().add(0, 0.5, 0);
            double damage = params().num("ult_damage");
            double shockDamage = params().num("shock_damage");
            int slow = (int) Math.min(Integer.MAX_VALUE, params().ticks("shock_slow"));
            int hits = 0;
            List<LivingEntity> innerCircle = ctx().enemiesNear(owner, center, radius);
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, shockRadius)) {
                if (!Spots.clearPath(center.clone().add(0, 0.5, 0), Fx.body(enemy))) {
                    continue;
                }
                Vector away = enemy.getLocation().toVector().subtract(impact.toVector()).setY(0);
                double distance = away.length();
                if (distance < 1.0E-3) {
                    double angle = ThreadLocalRandom.current().nextDouble(TAU);
                    away = new Vector(Math.cos(angle), 0, Math.sin(angle));
                } else {
                    away.multiply(1 / distance);
                }
                boolean inner = innerCircle.contains(enemy);
                Combat.hit(ctx(), owner, enemy, inner ? damage : shockDamage);
                hits++;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                if (inner) {
                    Motion.push(enemy, away.multiply(INNER_PUSH).setY(Motion.launchSpeed(2.0)), MAX_SPEED);
                } else {
                    Motion.push(enemy, away.multiply(SHOCK_PUSH).setY(SHOCK_LIFT), MAX_SPEED);
                    if (slow > 0) {
                        enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, SHOCK_SLOW_AMPLIFIER,
                                false, true, true));
                    }
                }
                Vfx.dot(Fx.body(enemy), Particle.FLASH, PALETTE.body(), true);
            }
            craterFx(owner);
            if (hits > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + BLUE + "><b>☄ 대운석!</b></" + BLUE + "> <white>" + hits
                        + "명 적중</white>"));
            }
        }

        /** The boulder breaks apart: its three rocks are hurled outwards, tumbling, and crumble away. */
        private void shatterRocks() {
            Player owner = player();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (BlockDisplay rock : rocks) {
                if (rock == null || !rock.isValid()) {
                    continue;
                }
                double angle = random.nextDouble(TAU);
                Location to = impact.clone().add(Math.cos(angle) * radius * 0.8, 0.6 + random.nextDouble(),
                        Math.sin(angle) * radius * 0.8);
                rock.setTeleportDuration(SHATTER_TICKS);
                Displays.moveTo(rock, to);
                Displays.animate(rock, cube(ROCK_SIZE * 0.45f, randomRotation(random)), SHATTER_TICKS);
                ctx().runLater(owner, SHATTER_TICKS, () -> {
                    if (rock.isValid()) {
                        Displays.shrinkAway(ctx(), owner, rock, CRUMBLE_TICKS);
                    }
                });
            }
        }

        /** The crater: flash, blast, shockwave rings, heaved rubble, glowing cracks and stardust drifting down. */
        private void craterFx(Player owner) {
            Location floor = floor();
            Location blast = impact.clone().add(0, 1, 0);
            Vfx.dot(blast, Particle.FLASH, PALETTE.core(), true);
            world.spawnParticle(Particle.EXPLOSION_EMITTER, blast, 1, 0, 0, 0, 0, null, true);
            Vfx.sphere(ctx(), blast, 1.2, 40, Particle.END_ROD, null, true);
            world.spawnParticle(Particle.FIREWORK, blast, Vfx.n(ctx(), 40), 0.6, 0.4, 0.6, 0.35, null, true);
            Vfx.shockwave(ctx(), owner, floor, 0.5, shockRadius, 8, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Vfx.shockwave(ctx(), owner, floor.clone().add(0, 0.4, 0), 0.5, shockRadius * 0.8, 12, Particle.DUST,
                    STAR_DUST, true);
            BlockData ground = groundData(impact);
            if (ground != null) {
                Vfx.ring(ctx(), floor, radius * 0.6, 14, 0, Particle.DUST_PILLAR, ground, true);
                world.spawnParticle(Particle.BLOCK, blast, Vfx.n(ctx(), 40), radius * 0.4, 0.3, radius * 0.4, 0.3,
                        ground, true);
            }
            world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, floor, Vfx.n(ctx(), 8), radius * 0.3, 0.2,
                    radius * 0.3, 0.02, null, true);
            rubble(owner, ground);
            cracks(owner);
            afterglow(owner);
            Fx.sound(blast, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.5f);
            Fx.sound(blast, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.5f, 0.7f);
            Fx.sound(blast, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.6f, 0.6f);
            Fx.sound(blast, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.2f, 0.5f);
            Fx.sound(blast, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.7f, 1.5f);
        }

        /** Chunks of ground heave up around the crater rim, hang a moment and sink back. */
        private void rubble(Player owner, @Nullable BlockData ground) {
            int count = Math.min(RUBBLE, Math.max(0, MAX_DISPLAYS - liveDisplays));
            if (count <= 0 || !claim(count)) {
                return;
            }
            long life = RUBBLE_HOLD_TICKS + 14;
            ctx().runLater(owner, life, () -> release(count));
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double phase = random.nextDouble(TAU);
            for (int i = 0; i < count; i++) {
                double angle = phase + i * TAU / count + random.nextDouble(-0.2, 0.2);
                double r = radius * random.nextDouble(0.55, 0.85);
                Location at = impact.clone().add(Math.cos(angle) * r, 0.05, Math.sin(angle) * r);
                at.setYaw(0);
                at.setPitch(0);
                BlockData data = ground != null ? ground : RUBBLE_FALLBACK.createBlockData();
                float size = (float) random.nextDouble(0.6, 1.1);
                BlockDisplay chunk = Displays.block(ctx(), owner, at, data, cube(size * 0.2f, new Quaternionf()), life);
                chunk.setBrightness(null);
                // Tilted away from the crater, like broken ground thrown up by the blow.
                Quaternionf tilt = new Quaternionf().rotateY((float) -angle)
                        .rotateZ((float) Math.toRadians(random.nextDouble(25, 50)));
                Transformation up = cubeAt(size, tilt, 0, size * 0.35f, 0);
                Displays.animateNextTick(ctx(), owner, chunk, up, 3);
                ctx().runLater(owner, RUBBLE_HOLD_TICKS, () -> {
                    if (chunk.isValid()) {
                        Displays.animate(chunk, cubeAt(size * 0.8f, tilt, 0, -size * 0.6f, 0), 12);
                    }
                });
            }
        }

        /** Glowing blue cracks running out across the ground from the crater, fading after a while. */
        private void cracks(Player owner) {
            if (!claim(CRACKS)) {
                return;
            }
            long life = CRACK_HOLD_TICKS + 12;
            ctx().runLater(owner, life, () -> release(CRACKS));
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double phase = random.nextDouble(TAU);
            Location at = floor().add(0, -0.08, 0);
            at.setYaw(0);
            at.setPitch(0);
            for (int i = 0; i < CRACKS; i++) {
                double angle = phase + i * TAU / CRACKS + random.nextDouble(-0.25, 0.25);
                float length = (float) (radius * random.nextDouble(0.8, 1.3));
                Quaternionf rotation = new Quaternionf().rotateY((float) (Math.PI / 2 - angle));
                Transformation start = new Transformation(rotation.transform(new Vector3f(-0.08f, 0, 0)), rotation,
                        new Vector3f(0.16f, 0.03f, 0.05f), new Quaternionf());
                Transformation open = new Transformation(rotation.transform(new Vector3f(-0.08f, 0, 0)), rotation,
                        new Vector3f(0.16f, 0.03f, length), new Quaternionf());
                BlockDisplay crack = Displays.block(ctx(), owner, at, CRACK_BLOCK.createBlockData(), start, life);
                Displays.animateNextTick(ctx(), owner, crack, open, 4);
                ctx().runLater(owner, CRACK_HOLD_TICKS, () -> {
                    if (crack.isValid()) {
                        Displays.shrinkAway(ctx(), owner, crack, 10);
                    }
                });
            }
        }

        /** Stardust drifting down over the crater and wisps of smoke rising from it. */
        private void afterglow(Player owner) {
            Loop.repeat(ctx(), owner, 4, 2, AFTERGLOW_TICKS / 2, run -> {
                double fade = 1 - (double) run / (AFTERGLOW_TICKS / 2.0);
                int count = (int) Math.round(4 * fade) + 1;
                for (int i = 0; i < count; i++) {
                    Location mote = Vfx.scatter(impact, radius).add(0, 1.5 + ThreadLocalRandom.current().nextDouble(2),
                            0);
                    Vfx.dot(mote, Particle.END_ROD, null, false);
                    Vfx.dot(mote.clone().add(0, -0.3, 0), Particle.DUST, STAR_DUST, false);
                }
                if (run % 2 == 0) {
                    Vfx.dot(Vfx.scatter(impact, radius * 0.5).add(0, 0.3, 0), Particle.SMOKE, null, false);
                }
                return true;
            });
        }

        private Location floor() {
            return impact.clone().add(0, 0.1, 0);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The ground under the aimed enemy (if the astronomer can see it) or under the aimed block, null if nothing is in
     * range. Without ground close below, the aimed point itself.
     */
    private @Nullable Location aimPoint(double range) {
        Player owner = player();
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed instanceof Player hidden && !owner.canSee(hidden)) {
            aimed = null;
        }
        Location point;
        if (aimed != null) {
            point = aimed.getLocation();
        } else {
            Location eye = owner.getEyeLocation();
            RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, eye.getDirection(), range,
                    FluidCollisionMode.SOURCE_ONLY, true);
            if (hit == null) {
                return null;
            }
            point = hit.getHitPosition().toLocation(owner.getWorld()).subtract(eye.getDirection().multiply(0.2));
        }
        Double ground = groundBelow(point);
        if (ground != null) {
            point.setY(ground);
        }
        point.setYaw(0);
        point.setPitch(0);
        return point;
    }

    /** The top of the first solid block at most {@link #GROUND_SNAP} blocks below {@code point}, null if none. */
    private static @Nullable Double groundBelow(Location point) {
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int start = Math.min(point.getBlockY(), world.getMaxHeight() - 1);
        int end = Math.max(world.getMinHeight(), (int) Math.floor(point.getY() - GROUND_SNAP));
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /** The solid block right under {@code at}, null over air, fluids and plants. */
    private static @Nullable BlockData groundData(Location at) {
        BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        return ground.getMaterial().isSolid() ? ground : null;
    }

    /** {@code at} without rotation, so a display's transformation alone decides its orientation. */
    private static Location plain(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }

    /** A cube of edge {@code size} centered on the block display's origin, rotated around its center. */
    private static Transformation cube(float size, Quaternionf rotation) {
        return cubeAt(size, rotation, 0, 0, 0);
    }

    /** A cube of edge {@code size} centered on the given offset, rotated around its center. */
    private static Transformation cubeAt(float size, Quaternionf rotation, float x, float y, float z) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate().add(x, y, z);
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    private static Quaternionf randomRotation(ThreadLocalRandom random) {
        return new Quaternionf().rotateXYZ(random.nextFloat() * 6.28f, random.nextFloat() * 6.28f,
                random.nextFloat() * 6.28f);
    }

    /** Claims {@code count} display entities if that stays within {@link #MAX_DISPLAYS}; release them later. */
    private boolean claim(int count) {
        if (count <= 0 || liveDisplays + count > MAX_DISPLAYS) {
            return false;
        }
        liveDisplays += count;
        return true;
    }

    private void release(int count) {
        liveDisplays = Math.max(0, liveDisplays - count);
    }
}
