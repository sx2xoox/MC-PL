package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 태양신 라 — the Egyptian sun god who sails the sky from east to west in his solar barque.
 *
 * <p>Everything here is a game of cover against a light source that is somewhere else. The passive wants the open sky
 * over the owner's head. The active's beam does not come from Ra but from the sun, whose side of the sky is fixed for
 * the match (morning: the east, noon: overhead, evening: the west): a target under a roof, an eave or a wall on the
 * sun's side is safe, and the block that shaded it is scorched instead. The ultimate makes a second sun cross the sky
 * from east to west; whoever its light reaches burns, so the shade moves, and a wall that hid an enemy at first gives
 * it away later. The shade of the moment is painted on the ground for everyone. Heat, not fire: nothing burns.
 *
 * <p>Look: sun gold, dazzling white and turquoise. A small sun disc (two gold and amber plates crossed into a star)
 * floats over Ra's head, bright under the sky and dim under a roof. The beam's target gets a gold-and-turquoise sight
 * ring and a little sun on the side the light will come from; the beam itself is a column of yellow glass with a white
 * core. The ultimate's sun is a great star riding a golden raft across the sky, with shafts of light reaching the
 * enemies it sees and dark motes where its shade falls.
 */
public final class RaAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("ra", "태양신 라", Material.GLOWSTONE)
            .color(TextColor.color(0xFFB627))
            .difficulty(2)
            .passive("태양의 아들",
                    "머리 위가 하늘로 뚫려 있는 동안 액티브 쿨타임이 {sun_haste}% 빨리 돌고 {sun_heal_period}초마다 "
                            + "{sun_heal} 회복합니다. 지붕 아래에서는 둘 다 잃습니다.")
            .active("태양 광선",
                    "조준한 적({ray_range}블록 이내)에게 {ray_delay}초 뒤 해가 있는 쪽에서 광선을 내려 {ray_damage} "
                            + "피해를 주고 {heat_time}초간 일사병(발광, 이동 속도 -{heat_slow}%)을 겁니다. 해는 "
                            + "경기마다 동쪽·머리 위·서쪽 중 한 곳에 뜨며, 대상에서 해 쪽으로 {shade_reach}블록 안에 "
                            + "지붕이나 벽이 있으면 그 블록이 대신 맞습니다(전투 맵에서는 부서짐).",
                    9)
            .ultimate("태양의 배",
                    "{barque_time}초간 두 번째 해가 {barque_radius}블록 안의 하늘을 동쪽에서 서쪽으로 가로지릅니다. 그 "
                            + "빛을 직접 받는 적은 0.5초마다 {barque_damage} 피해를 받고 이동 속도가 {barque_slow}% "
                            + "느려지며, 그늘의 적은 무사합니다(그늘은 바닥에 표시되고 해를 따라 움직입니다). 빛 속의 "
                            + "나와 아군은 주는 피해가 {barque_bonus}% 늘어납니다.")
            .ultimateNotStandalone()
            .param("sun_haste", 25)
            .param("sun_heal", 1)
            .param("sun_heal_period", 4)
            .param("ray_range", 24)
            .param("ray_delay", 0.6)
            .param("ray_damage", 5)
            .param("heat_time", 3)
            .param("heat_slow", 15)
            .param("shade_reach", 16)
            .param("ray_terrain", 1)
            .param("barque_time", 7)
            .param("barque_radius", 30)
            .param("barque_damage", 0.5)
            .param("barque_slow", 20)
            .param("barque_bonus", 15)
            .build();

    private static final String GOLD_TAG = "#FFB627";

    // ------------------------------------------------------------------ look: sun gold, dazzling white, turquoise
    private static final Vfx.Palette SUN = Vfx.Palette.of(0xFFF8E1, 0xFFB627, 0xB35A00);
    private static final Vfx.Palette NILE = Vfx.Palette.of(0xC9FFF7, 0x2EC4B6, 0x0E5A54);
    private static final Color GOLD = Color.fromRGB(0xFFB627);
    private static final Color WHITE = Color.fromRGB(0xFFF8E1);
    private static final Particle.DustOptions GOLD_DUST = SUN.dust(1.0f);
    private static final Particle.DustOptions GOLD_SMALL = SUN.dust(0.6f);
    private static final Particle.DustOptions TURQUOISE_DUST = NILE.dust(0.9f);
    private static final Particle.DustOptions SHADE_DUST = new Particle.DustOptions(Color.fromRGB(0x2A2140), 1.7f);
    private static final Particle.DustTransition SUN_GLOW = SUN.glow(1.2f);
    private static final Particle.DustTransition HEAT_FADE = SUN.fade(0.9f);

    private static final Material DISC_GOLD = Material.GOLD_BLOCK;
    private static final Material DISC_AMBER = Material.SHROOMLIGHT;
    private static final Material BEAM_OUTER = Material.YELLOW_STAINED_GLASS;
    private static final Material BEAM_CORE = Material.WHITE_CONCRETE;
    private static final Material BARQUE = Material.BAMBOO_RAFT;

    /** Display entities this ability keeps alive at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    private static final int FOLLOW_GLIDE = 3;
    private static final Display.Brightness DIM = new Display.Brightness(5, 5);

    // the passive
    private static final long SKY_PERIOD = 5;
    private static final double SKY_REACH = 96;
    private static final double DISC_ABOVE = 0.75;
    private static final float DISC_SIZE = 0.42f;
    /** How far the disc leans towards the sun's side of the sky. */
    private static final double DISC_LEAN = 0.45;

    // the active
    /** The sun's elevation in the morning and the evening (degrees above the horizon). */
    private static final double LOW_SUN = 35;
    private static final double SMALL_SUN_GAP = 3.2;
    private static final double BEAM_LENGTH = 24;
    private static final long BEAM_TICKS = 8;

    // the ultimate
    private static final double SUN_DISTANCE = 26;
    private static final double SUN_FROM = 30;
    private static final double SUN_TO = 150;
    private static final long ULT_PULSE = 10;
    private static final long ULT_SLOW_TICKS = 14;
    /** The shade is painted over the whole reach, and more densely within this of everyone in it. */
    private static final double SHADE_NEAR = 3.5;
    private static final int SHADE_SAMPLES = 12;
    private static final double TAU = Math.PI * 2;
    private static final float BIG_SUN = 3.6f;

    /** The side of the sky the match's sun stands on. */
    public enum Sun {
        MORNING("아침", "동쪽"),
        NOON("한낮", "머리 위"),
        EVENING("저녁", "서쪽");

        final String time;
        final String side;

        Sun(String time, String side) {
            this.time = time;
            this.side = side;
        }

        /** The unit vector from the ground towards the sun. */
        public Vector toward() {
            double low = Math.toRadians(LOW_SUN);
            return switch (this) {
                case MORNING -> new Vector(Math.cos(low), Math.sin(low), 0);
                case NOON -> new Vector(0, 1, 0);
                case EVENING -> new Vector(-Math.cos(low), Math.sin(low), 0);
            };
        }
    }

    /**
     * The sun of each match, chosen once: every Ra of a match sees the same one, and so does a Ra granted again
     * after a practice respawn or an ability switch. Keyed weakly by the match's context.
     */
    private static final Map<AbilityContext, Sun> MATCH_SUNS = new WeakHashMap<>();

    // ------------------------------------------------------------------ state

    private final List<Display> displays = new ArrayList<>();
    private @Nullable Sun sun;
    private boolean granted;
    private boolean skyOpen;
    private long sunTicks;
    private final List<BlockDisplay> disc = new ArrayList<>();
    private @Nullable Barque barque;
    private @Nullable BarqueListener listener;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        granted = true;
        Sun chosen = sun();
        player().sendActionBar(Text.mm("<" + GOLD_TAG + ">☀ 오늘의 해</" + GOLD_TAG + "> <white>" + chosen.time
                + "</white> <gray>— " + chosen.side + "에서 비춥니다</gray>"));
    }

    @Override
    public void onRevoke() {
        granted = false;
        unregister();
        barque = null;
        disc.clear();
        displays.clear();
    }

    /** The match's sun (chosen once per match, at random). */
    public Sun sun() {
        Sun chosen = sun;
        if (chosen == null) {
            chosen = MATCH_SUNS.computeIfAbsent(ctx(),
                    key -> Sun.values()[ThreadLocalRandom.current().nextInt(Sun.values().length)]);
            sun = chosen;
        }
        return chosen;
    }

    // ================================================================== passive: son of the sun

    @Override
    public void onTick(long tick) {
        if (!granted) {
            return;
        }
        Player owner = player();
        boolean hidden = concealed(owner);
        if (tick % SKY_PERIOD == 0) {
            boolean open = underSky(owner);
            if (open != skyOpen) {
                skyOpen = open;
                lightDisc(open);
                if (open && !hidden) {
                    Fx.sound(owner.getEyeLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.4f, 1.9f);
                }
            }
            if (open) {
                double haste = Math.max(0, params().num("sun_haste")) / 100.0;
                if (haste > 0 && ctx().cooldownRemaining(owner) > 0) {
                    ctx().reduceCooldown(owner, SKY_PERIOD / 20.0 * haste);
                }
                sunTicks += SKY_PERIOD;
                long period = Math.max(SKY_PERIOD, params().ticks("sun_heal_period"));
                if (sunTicks >= period) {
                    sunTicks -= period;
                    heal(owner, hidden);
                }
            }
        }
        followDisc(owner, tick, hidden);
    }

    /** True if nothing but sky is over the owner's head. */
    private static boolean underSky(LivingEntity entity) {
        Location eye = entity.getLocation().add(0, entity.getHeight() + 0.05, 0);
        return entity.getWorld().rayTraceBlocks(eye, new Vector(0, 1, 0), SKY_REACH, FluidCollisionMode.NEVER, true)
                == null;
    }

    private void heal(Player owner, boolean hidden) {
        double amount = params().num("sun_heal");
        if (amount <= 0 || owner.getHealth() >= Combat.maxHealth(owner)) {
            return;
        }
        ctx().heal(owner, amount);
        if (hidden) {
            return;
        }
        Location chest = owner.getLocation().add(0, 1.0, 0);
        Vfx.helix(ctx(), owner.getLocation().add(0, 0.1, 0), 0.5, 1.9, 1, 2, 6, ctx().currentTick() * 0.2,
                Particle.DUST, GOLD_SMALL, false);
        Vfx.dot(chest, Particle.HEART, null, false);
        Fx.sound(chest, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.4f, 1.6f + Fx.jitter(0.1f));
    }

    /**
     * The small sun disc over the head: two crossed plates facing every viewer, bright under the sky. It hangs a little
     * towards the side of the sky the match's sun stands on, so everyone can tell where Ra's rays will come from.
     */
    private void followDisc(Player owner, long tick, boolean hidden) {
        disc.removeIf(part -> !part.isValid());
        if (hidden) {
            // Nothing may float over an invisible Ra and give him away.
            for (BlockDisplay plate : disc) {
                Displays.shrinkAway(ctx(), owner, plate, 3);
            }
            disc.clear();
            return;
        }
        Vector toward = sun().toward();
        Location at = Displays.unrotated(owner.getLocation().add(toward.getX() * DISC_LEAN, owner.getHeight()
                + DISC_ABOVE, toward.getZ() * DISC_LEAN));
        if (disc.isEmpty()) {
            if (!reserve(2)) {
                return;
            }
            for (int i = 0; i < 2; i++) {
                BlockDisplay plate = keep(Displays.block(ctx(), owner, at, (i == 0 ? DISC_GOLD : DISC_AMBER)
                        .createBlockData(), discPose(i, skyOpen, tick), 0));
                plate.setBillboard(Display.Billboard.CENTER);
                plate.setTeleportDuration(FOLLOW_GLIDE);
                plate.setViewRange(1.0f);
                plate.setBrightness(skyOpen ? Displays.FULL_BRIGHT : DIM);
                disc.add(plate);
            }
            return;
        }
        for (int i = 0; i < disc.size(); i++) {
            BlockDisplay plate = disc.get(i);
            Displays.moveTo(plate, at);
            if (tick % 10 == 0) {
                // The disc turns slowly.
                Displays.animate(plate, discPose(i, skyOpen, tick), 10);
            }
        }
        if (skyOpen && tick % 12 == 0) {
            Vfx.dot(Vfx.scatter(at, 0.3), Particle.END_ROD, null, false);
        }
    }

    private void lightDisc(boolean open) {
        long tick = ctx().currentTick();
        for (int i = 0; i < disc.size(); i++) {
            BlockDisplay plate = disc.get(i);
            if (plate.isValid()) {
                plate.setBrightness(open ? Displays.FULL_BRIGHT : DIM);
                Displays.animate(plate, discPose(i, open, tick), 4);
            }
        }
    }

    /** Plate {@code index} of the disc (0 gold square, 1 amber square turned 45 degrees), in the camera's frame. */
    private static Transformation discPose(int index, boolean open, long tick) {
        float size = (open ? DISC_SIZE : DISC_SIZE * 0.65f) * (index == 0 ? 1f : 0.82f);
        float roll = (index == 0 ? 0 : 45) + (tick % 360) * 1.5f;
        return Displays.transform(0, 0, index * 0.01f, size, size, 0.02f, 0, 0, roll);
    }

    // ================================================================== active: the sun's ray

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        LivingEntity target = ctx().findTarget(owner, Math.max(1, params().num("ray_range")));
        if (target == null || isHidden(owner, target)) {
            return ActivationResult.fail("햇빛을 내릴 적을 조준하세요 (" + AbilityInfo.formatNumber(params().num(
                    "ray_range")) + "블록 이내)");
        }
        new Ray(owner, target, sun()).start();
        Location eye = owner.getEyeLocation();
        Vfx.dot(eye, Particle.FLASH, GOLD, false);
        Vfx.trail(eye, Fx.body(target), GOLD, 8, false);
        Fx.sound(eye, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.8f);
        Fx.sound(eye, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.4f);
        return ActivationResult.success();
    }

    /**
     * One ray of the sun: a sight ring follows the target and a little sun hangs on the side the light will come from;
     * when the time is up the beam comes down from that side onto the target, or onto whatever block shades it.
     */
    private final class Ray implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final Sun from;
        private final Vector toward;
        private final long delay;
        private final List<BlockDisplay> smallSun = new ArrayList<>();
        private boolean done;

        Ray(Player owner, LivingEntity target, Sun from) {
            this.owner = owner;
            this.target = target;
            this.from = from;
            this.toward = from.toward();
            this.delay = Math.max(1, params().ticks("ray_delay"));
        }

        void start() {
            Location at = smallSunSpot();
            if (reserve(2)) {
                for (int i = 0; i < 2; i++) {
                    BlockDisplay plate = keep(Displays.block(ctx(), owner, at, (i == 0 ? DISC_GOLD : DISC_AMBER)
                            .createBlockData(), Displays.transform(0, 0, i * 0.01f, 0.05f, 0.05f, 0.02f, 0, 0, i * 45f),
                            delay + 10));
                    plate.setBillboard(Display.Billboard.CENTER);
                    plate.setTeleportDuration(1);
                    plate.setViewRange(2.0f);
                    Displays.animateNextTick(ctx(), owner, plate, Displays.transform(0, 0, i * 0.01f, 0.6f, 0.6f, 0.02f,
                            0, 0, i * 45f + 30f), (int) Math.min(8, delay));
                    smallSun.add(plate);
                }
            }
            Fx.sound(Fx.body(target), Sound.BLOCK_BEACON_AMBIENT, 1.0f, 2.0f);
            if (target instanceof Player hunted) {
                hunted.sendActionBar(Text.mm("<" + GOLD_TAG + ">☀ 태양 광선</" + GOLD_TAG + "> <gray>— " + from.side
                        + "에서 햇빛이 내립니다. 그늘로!</gray>"));
            }
            Loop.repeat(ctx(), owner, 1, 1, delay, this, this::strike);
        }

        private Location smallSunSpot() {
            return Displays.unrotated(Fx.body(target).add(toward.clone().multiply(SMALL_SUN_GAP)));
        }

        @Override
        public boolean test(long tick) {
            if (!Combat.isTargetable(ctx(), owner, target)) {
                done = true;
                cleanup();
                return false;
            }
            Location feet = target.getLocation().add(0, 0.1, 0);
            Location body = Fx.body(target);
            boolean seen = !isHidden(owner, target);
            if (seen) {
                double tighten = 1 - (tick + 1) / (double) delay;
                Vfx.ring(ctx(), feet, 0.7 + tighten * 0.8, 16, tick * 0.3, Particle.DUST, GOLD_DUST, true);
                Vfx.ring(ctx(), feet, 0.45, 8, -tick * 0.4, Particle.DUST, TURQUOISE_DUST, false);
                Location sunSpot = smallSunSpot();
                for (BlockDisplay plate : smallSun) {
                    Displays.moveTo(plate, sunSpot);
                }
                if (tick % 2 == 0) {
                    Vfx.line(ctx(), sunSpot, body, 2.0, Particle.DUST, GOLD_SMALL, false);
                }
            } else {
                for (BlockDisplay plate : smallSun) {
                    if (plate.isValid()) {
                        plate.remove();
                    }
                }
            }
            return true;
        }

        /** The beam comes down from the sun's side. */
        private void strike() {
            if (done) {
                return;
            }
            done = true;
            cleanup();
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return;
            }
            Location body = Fx.body(target);
            World world = body.getWorld();
            // Only cover near the target shades it: a tower or a canyon rim far off would make the ray never land.
            double reach = Math.max(1, params().num("shade_reach"));
            RayTraceResult shade = world.rayTraceBlocks(body, toward, reach, FluidCollisionMode.NEVER, true);
            if (shade == null) {
                burn(body);
            } else {
                scorch(body, shade);
            }
        }

        private void burn(Location body) {
            Combat.hit(ctx(), owner, target, params().num("ray_damage"));
            int heat = (int) Math.min(Integer.MAX_VALUE, params().ticks("heat_time"));
            double slow = Math.clamp(params().num("heat_slow") / 100.0, 0.0, 1.0);
            if (heat > 0 && Combat.isTargetable(ctx(), owner, target)) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, heat, 0, false, false, true));
                if (slow > 0) {
                    ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, heat);
                }
                heatstroke(owner, target, heat);
            }
            beamFrom(body, BEAM_LENGTH);
            Vfx.dot(body, Particle.FLASH, WHITE, true);
            Vfx.burst(ctx(), body, SUN, 0.8, Particle.END_ROD, true);
            Vfx.shockwave(ctx(), owner, target.getLocation().add(0, 0.1, 0), 0.3, 1.8, 5, Particle.DUST_COLOR_TRANSITION,
                    SUN_GLOW, false);
            Fx.sound(body, Sound.ENTITY_GUARDIAN_ATTACK, 0.8f, 1.8f);
            Fx.sound(body, Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 1.4f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.BLOCK_BEACON_DEACTIVATE, 0.9f, 2.0f);
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">☀ 일사병!</" + GOLD_TAG + "> <white><target></white> <gray>+"
                    + AbilityInfo.formatNumber(params().num("ray_damage")) + "</gray>",
                    Placeholder.component("target", target.name())));
        }

        /** The block between the target and the sun takes the light: it is scorched and the target is spared. */
        private void scorch(Location body, RayTraceResult shade) {
            Location hit = shade.getHitPosition().toLocation(body.getWorld());
            double reach = hit.distance(body);
            beamFrom(hit, BEAM_LENGTH);
            Block block = shade.getHitBlock();
            if (block != null) {
                Location center = block.getLocation().add(0.5, 0.5, 0.5);
                body.getWorld().spawnParticle(Particle.BLOCK_CRUMBLE, hit, Vfx.n(ctx(), 14), 0.3, 0.3, 0.3, 0.1,
                        block.getBlockData());
                Vfx.cloud(ctx(), hit, Particle.SMOKE, 12, 0.25, 0.03, null, false);
                Vfx.cloud(ctx(), hit, Particle.DUST_COLOR_TRANSITION, 8, 0.3, 0, HEAT_FADE, false);
                double radius = params().num("ray_terrain");
                if (radius > 0) {
                    ctx().breakBlocks(owner, center, radius);
                }
            }
            Fx.sound(hit, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(hit, Sound.ENTITY_GUARDIAN_ATTACK, 0.5f, 1.6f);
            owner.sendActionBar(Text.mm("<gray>그늘이 햇빛을 막았습니다</gray> <dark_gray>(" + AbilityInfo.formatNumber(
                    Math.round(reach * 10) / 10.0) + "블록 위의 블록이 그을렸습니다)</dark_gray>"));
        }

        /** A column of yellow glass with a white core from {@code base} up towards the sun. */
        private void beamFrom(Location base, double length) {
            if (!reserve(2)) {
                Vfx.line(ctx(), base, base.clone().add(toward.clone().multiply(length)), 2, Particle.END_ROD, null,
                        true);
                return;
            }
            Location at = Displays.unrotated(base);
            BlockDisplay outer = keep(Displays.block(ctx(), owner, at, BEAM_OUTER.createBlockData(),
                    column(toward, 0.75f, (float) length), BEAM_TICKS + 4));
            BlockDisplay core = keep(Displays.block(ctx(), owner, at, BEAM_CORE.createBlockData(),
                    column(toward, 0.25f, (float) length), BEAM_TICKS + 4));
            outer.setViewRange(3.0f);
            core.setViewRange(3.0f);
            ctx().runLater(owner, 2, () -> {
                Displays.animate(outer, column(toward, 0.02f, (float) length), (int) BEAM_TICKS);
                Displays.animate(core, column(toward, 0.01f, (float) length), (int) BEAM_TICKS);
            });
            for (double d = 0; d < Math.min(length, 12); d += 1.5) {
                Vfx.dot(base.clone().add(toward.clone().multiply(d)), Particle.END_ROD, null, true);
            }
        }

        private void cleanup() {
            for (BlockDisplay plate : smallSun) {
                if (plate.isValid()) {
                    Displays.shrinkAway(ctx(), owner, plate, 3);
                }
            }
            smallSun.clear();
        }
    }

    /** Heat shimmer rising off a heatstruck target for as long as it lasts. */
    private void heatstroke(Player owner, LivingEntity target, long ticks) {
        Loop.repeat(ctx(), owner, 2, 3, Math.max(1, ticks / 3), run -> {
            if (!target.isValid() || target.isDead()) {
                return false;
            }
            if (!isHidden(owner, target)) {
                Location head = target.getLocation().add(0, target.getHeight() + 0.1, 0);
                Vfx.dot(Vfx.scatter(head, 0.3), Particle.DUST_COLOR_TRANSITION, HEAT_FADE, false);
                if (run % 2 == 0) {
                    Vfx.dot(Vfx.scatter(head, 0.25), Particle.WHITE_ASH, null, false);
                }
            }
            return true;
        });
    }

    /**
     * A beam column from the display's location along {@code dir}, {@code width} thick: its box starts at the base,
     * so only its thickness interpolates when it fades.
     */
    private static Transformation column(Vector dir, float width, float length) {
        float yaw = yawOf(dir);
        double horizontal = Math.sqrt(dir.getX() * dir.getX() + dir.getZ() * dir.getZ());
        float pitch = (float) Math.toDegrees(Math.atan2(-dir.getY(), horizontal));
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch));
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, 0));
        return new Transformation(translation, rotation, new Vector3f(width, width, length), new Quaternionf());
    }

    // ================================================================== ultimate: the solar barque

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (barque != null) {
            return ActivationResult.fail("태양의 배가 이미 하늘을 건너고 있습니다");
        }
        Barque sailing = new Barque(owner);
        barque = sailing;
        unregister();
        BarqueListener created = new BarqueListener();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        listener = created;
        sailing.start();
        return ActivationResult.success();
    }

    private void unregister() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
    }

    /** While the barque sails, the blows of Ra and his allies standing in its light hit harder. */
    private final class BarqueListener implements Listener {

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onDamage(EntityDamageByEntityEvent event) {
            Barque sailing = barque;
            if (sailing == null || !(event.getEntity() instanceof LivingEntity victim)) {
                return;
            }
            Player owner = player();
            Player attacker = ctx().resolveAttacker(event.getDamager());
            if (attacker == null || !ctx().isEnemy(owner, victim)
                    || !(attacker.equals(owner) || ctx().isAlly(owner, attacker)) || !sailing.isLit(attacker)) {
                return;
            }
            event.setDamage(event.getDamage() * (1 + Math.max(0, params().num("barque_bonus")) / 100.0));
        }
    }

    /**
     * The second sun on its barque: it rises in the east and sets in the west of the place Ra raised it, over the ult
     * time. Every pulse, the enemies its light reaches burn; the shade it casts is painted on the ground.
     */
    private final class Barque implements LongPredicate {

        private final Player owner;
        private final Location center;
        private final World world;
        private final long duration;
        private final double radius;
        private final List<BlockDisplay> star = new ArrayList<>();
        private @Nullable ItemDisplay raft;
        private final Map<UUID, NamespacedKey> slows = new HashMap<>();
        private final Set<UUID> lit = new HashSet<>();
        private Location sunAt;
        private boolean over;

        Barque(Player owner) {
            this.owner = owner;
            Location feet = owner.getLocation();
            Spots.snapToGround(feet.getWorld(), feet, 4);
            this.center = Displays.unrotated(feet);
            this.world = center.getWorld();
            this.duration = Math.max(1, params().ticks("barque_time"));
            this.radius = Math.max(2, params().num("barque_radius"));
            this.sunAt = sunPosition(0);
        }

        /** Where the sun is {@code progress} (0..1) of the way across: from 30 degrees over the east to 150. */
        private Location sunPosition(double progress) {
            double angle = Math.toRadians(SUN_FROM + (SUN_TO - SUN_FROM) * Math.clamp(progress, 0, 1));
            return Displays.unrotated(center.clone().add(Math.cos(angle) * SUN_DISTANCE, Math.sin(angle) * SUN_DISTANCE,
                    0));
        }

        void start() {
            if (reserve(3)) {
                for (int i = 0; i < 2; i++) {
                    BlockDisplay plate = keep(Displays.block(ctx(), owner, sunAt, (i == 0 ? DISC_GOLD : DISC_AMBER)
                            .createBlockData(), Displays.transform(0, 0, i * 0.02f, 0.2f, 0.2f, 0.05f, 0, 0, i * 45f),
                            duration + 20));
                    plate.setBillboard(Display.Billboard.CENTER);
                    plate.setTeleportDuration(2);
                    plate.setViewRange(6.0f);
                    Displays.outline(plate, GOLD);
                    Displays.animateNextTick(ctx(), owner, plate, Displays.transform(0, 0, i * 0.02f, BIG_SUN, BIG_SUN,
                            0.05f, 0, 0, i * 45f), 10);
                    star.add(plate);
                }
                ItemDisplay boat = keep(Displays.item(ctx(), owner, raftSpot(), new ItemStack(BARQUE),
                        Displays.centered(0, 0, 0, 0.5f, 0.5f, 0.5f, 0, 0, 0), duration + 20));
                boat.setBillboard(Display.Billboard.VERTICAL);
                boat.setTeleportDuration(2);
                boat.setViewRange(6.0f);
                Displays.animateNextTick(ctx(), owner, boat, Displays.centered(0, 0, 0, 5.5f, 5.5f, 5.5f, 0, 0, 0), 10);
                raft = boat;
            }
            Vfx.dot(sunAt, Particle.FLASH, GOLD, true);
            Vfx.ring(ctx(), center.clone().add(0, 0.2, 0), 3, 24, 0, Particle.DUST_COLOR_TRANSITION, SUN_GLOW, true);
            Fx.sound(owner.getEyeLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.4f, 0.8f);
            Fx.sound(owner.getEyeLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 1.2f);
            Fx.sound(owner.getEyeLocation(), Sound.BLOCK_BELL_RESONATE, 1.0f, 1.4f);
            ctx().announce(Text.mm("<" + GOLD_TAG + ">☀</" + GOLD_TAG + "> <white><owner></white><gray>의 </gray><"
                            + GOLD_TAG + "><b>태양의 배</b></" + GOLD_TAG + "><gray>가 하늘을 건넙니다 — 그늘로 숨으세요!</gray>",
                    Placeholder.component("owner", owner.name())));
            Loop.repeat(ctx(), owner, 0, 1, duration, this, this::end);
        }

        private Location raftSpot() {
            return sunAt.clone().subtract(0, BIG_SUN * 0.75, 0);
        }

        /** True if {@code entity} stands in the barque's light: nothing between its chest and the sun. */
        boolean isLit(LivingEntity entity) {
            return lit.contains(entity.getUniqueId());
        }

        private boolean seesSun(LivingEntity entity) {
            if (!entity.getWorld().equals(world)) {
                return false;
            }
            Location body = Fx.body(entity);
            return clear(body);
        }

        private boolean clear(Location from) {
            Vector path = sunAt.toVector().subtract(from.toVector());
            double distance = path.length();
            if (distance < 1.0E-3) {
                return true;
            }
            return world.rayTraceBlocks(from, path.multiply(1 / distance), distance, FluidCollisionMode.NEVER, true)
                    == null;
        }

        @Override
        public boolean test(long tick) {
            if (over) {
                return false;
            }
            double progress = (tick + 1) / (double) duration;
            sunAt = sunPosition(progress);
            for (BlockDisplay plate : star) {
                Displays.moveTo(plate, sunAt);
            }
            ItemDisplay boat = raft;
            if (boat != null) {
                Displays.moveTo(boat, raftSpot());
            }
            if (tick % 2 == 0) {
                Vfx.cloud(ctx(), sunAt, Particle.END_ROD, 3, 1.2, 0.02, null, true);
            }
            if (tick % 5 == 0) {
                refreshLit();
            }
            paintShade(tick);
            sweep(tick);
            if (tick % ULT_PULSE == 0) {
                pulse();
            }
            if (tick % 20 == 0) {
                Fx.sound(center, Sound.BLOCK_BEACON_AMBIENT, 1.2f, 0.7f + (float) progress * 0.6f);
            }
            return true;
        }

        /** Who of Ra's side and the enemies stands in the light right now. */
        private void refreshLit() {
            lit.clear();
            List<LivingEntity> around = new ArrayList<>(ctx().enemiesNear(owner, center, radius));
            around.addAll(ctx().alliesNear(owner, center, radius));
            around.add(owner);
            for (LivingEntity entity : around) {
                if (entity.getLocation().distance(center) <= radius + 1 && seesSun(entity)) {
                    lit.add(entity.getUniqueId());
                }
            }
        }

        /**
         * Dark motes on the ground where the sun's shade falls right now (shown to everyone): spread over the whole
         * reach of the barque, and denser around everyone in it, where the shade decides who burns.
         */
        private void paintShade(long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int points = Vfx.n(ctx(), SHADE_SAMPLES);
            for (int i = 0; i < points; i++) {
                double r = radius * Math.sqrt(random.nextDouble());
                double a = random.nextDouble(TAU);
                shadeAt(center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), random);
            }
            if (tick % 2 != 0) {
                return;
            }
            List<LivingEntity> around = new ArrayList<>(ctx().enemiesNear(owner, center, radius));
            around.addAll(ctx().alliesNear(owner, center, radius));
            around.add(owner);
            int near = Vfx.n(ctx(), 2);
            for (LivingEntity entity : around) {
                if (!entity.getWorld().equals(world) || isHidden(owner, entity)) {
                    continue;
                }
                for (int i = 0; i < near; i++) {
                    double r = SHADE_NEAR * Math.sqrt(random.nextDouble());
                    double a = random.nextDouble(TAU);
                    shadeAt(entity.getLocation().add(Math.cos(a) * r, 0, Math.sin(a) * r), random);
                }
            }
        }

        private void shadeAt(Location spot, ThreadLocalRandom random) {
            spot.setY(center.getY() + 3);
            Spots.snapToGround(world, spot, 7);
            Location probe = spot.clone().add(0, 0.3, 0);
            if (!clear(probe)) {
                Vfx.dot(probe.add(0, random.nextDouble(0.1), 0), Particle.DUST, SHADE_DUST, true);
            }
        }

        /** Two shafts of sunlight sweep around the ground below the sun, stopped by the shade they meet. */
        private void sweep(long tick) {
            if (tick % 2 != 0) {
                return;
            }
            for (int k = 0; k < 2; k++) {
                double a = tick * 0.11 + k * Math.PI;
                double r = radius * (0.4 + 0.3 * Math.sin(tick * 0.045 + k * 1.7));
                Location spot = center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                spot.setY(center.getY() + 3);
                Spots.snapToGround(world, spot, 7);
                Location ground = spot.add(0, 0.15, 0);
                if (clear(ground)) {
                    Vfx.line(ctx(), sunAt, ground, 2.6, Particle.DUST, GOLD_SMALL, true);
                    Vfx.dot(ground, Particle.END_ROD, null, true);
                    Vfx.dot(Vfx.scatter(ground, 0.4), Particle.DUST_COLOR_TRANSITION, SUN_GLOW, true);
                }
            }
        }

        private void pulse() {
            double damage = params().num("barque_damage");
            double slow = Math.clamp(params().num("barque_slow") / 100.0, 0.0, 1.0);
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                NamespacedKey old = slows.remove(enemy.getUniqueId());
                if (old != null) {
                    ctx().removeModifier(enemy, old);
                }
                if (!seesSun(enemy)) {
                    continue;
                }
                lit.add(enemy.getUniqueId());
                Combat.hit(ctx(), owner, enemy, damage);
                if (slow > 0 && Combat.isTargetable(ctx(), owner, enemy)) {
                    slows.put(enemy.getUniqueId(), ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow,
                            Operation.MULTIPLY_SCALAR_1, ULT_SLOW_TICKS));
                }
                if (!isHidden(owner, enemy)) {
                    Location body = Fx.body(enemy);
                    Vfx.trail(sunAt, body, GOLD, 12, true);
                    Vfx.cloud(ctx(), body.clone().add(0, 0.5, 0), Particle.DUST_COLOR_TRANSITION, 4, 0.3, 0, HEAT_FADE,
                            false);
                    Vfx.dot(body.add(0, enemy.getHeight() * 0.5, 0), Particle.WHITE_ASH, null, false);
                }
                Fx.sound(Fx.body(enemy), Sound.BLOCK_FIRE_EXTINGUISH, 0.35f, 1.8f + Fx.jitter(0.1f));
            }
            if (isLit(owner)) {
                Vfx.ring(ctx(), owner.getLocation().add(0, 0.1, 0), 0.6, 8, 0, Particle.DUST, GOLD_SMALL, false);
            }
            for (Player ally : ctx().alliesNear(owner, center, radius)) {
                if (isLit(ally)) {
                    Vfx.ring(ctx(), ally.getLocation().add(0, 0.1, 0), 0.6, 8, 0, Particle.DUST, GOLD_SMALL, false);
                }
            }
        }

        void end() {
            if (over) {
                return;
            }
            over = true;
            if (barque == this) {
                barque = null;
            }
            unregister();
            for (BlockDisplay plate : star) {
                if (plate.isValid()) {
                    Displays.shrinkAway(ctx(), owner, plate, 10);
                }
            }
            ItemDisplay boat = raft;
            if (boat != null && boat.isValid()) {
                Displays.shrinkAway(ctx(), owner, boat, 10);
            }
            Vfx.cloud(ctx(), sunAt, Particle.END_ROD, 20, 1.5, 0.05, null, true);
            Fx.sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.2f, 0.8f);
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">☀ 태양의 배가 서쪽으로 졌습니다</" + GOLD_TAG + ">"));
        }
    }

    // ================================================================== helpers

    /** The yaw (degrees) a direction faces, in the display convention (0 = +Z south, 90 = -X west). */
    private static float yawOf(Vector direction) {
        if (direction.getX() * direction.getX() + direction.getZ() * direction.getZ() < 1.0E-9) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    private <T extends Display> T keep(T display) {
        displays.add(display);
        return display;
    }

    /** Reserves room for {@code count} more displays under {@link #MAX_DISPLAYS}; false when there is none. */
    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    private static boolean isHidden(Player owner, LivingEntity entity) {
        return entity instanceof Player hidden && !owner.canSee(hidden);
    }

    /** True while Ra is invisible: his sun disc and its light must not give him away. */
    private static boolean concealed(Player owner) {
        return owner.isInvisible() || owner.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }
}
