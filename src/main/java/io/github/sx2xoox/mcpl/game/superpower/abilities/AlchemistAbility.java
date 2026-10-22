package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.PotionContents;
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
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
 * 연금술사 — brews. Good effects on the alchemist last longer and bad ones wear off sooner, the active throws an
 * unstable flask that curses enemies and blesses allies with a random brew each, and the ultimate forges the
 * philosopher's stone: a full heal, a cleanse and a short set of strong buffs, also cleansing and healing nearby
 * allies.
 *
 * <p>Look: two-tone potion magic, curses in violet and blessings in green. The flask tumbles in an arc with a trail
 * of witch sparkles and bursts in a splash of potion swirls; every target shows the brew it got as a colored swirl
 * and a floating label. The stone is forged inside a transmutation circle (rings and a hexagram on the ground, the
 * seven planetary symbols orbiting), a red glass gem spinning above the alchemist's head that sinks into its chest
 * and bursts in gold and crimson; three red shards then orbit the alchemist while the buffs last.
 */
public final class AlchemistAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("alchemist", "연금술사", Material.BREWING_STAND)
            .color(TextColor.color(0xA66BFF))
            .difficulty(2)
            .passive("약효 조절",
                    "내게 걸리는 이로운 효과는 {boon_extend}% 길게, 해로운 효과는 {bane_reduce}% 짧게 지속됩니다.")
            .active("실험 물약",
                    "조준한 지점({throw_range}블록 이내)에 물약을 던져 반경 {splash_radius}블록에 터뜨립니다. 적은 "
                            + "{splash_damage} 피해와 무작위 해로운 효과(독·구속 II·나약함 {bane_duration}초, 실명 "
                            + "{blind_duration}초)를, 나와 아군은 무작위 이로운 효과(신속 II·재생 II·저항 I "
                            + "{boon_duration}초)를 받습니다.",
                    9)
            .ultimate("현자의 돌",
                    "{stone_cast}초간 현자의 돌을 빚어 삼킵니다. 체력을 모두 회복하고 해로운 효과를 씻어내며 "
                            + "{stone_duration}초간 힘 I·신속 II·저항 I을 얻습니다. 반경 {cleanse_radius}블록의 아군도 "
                            + "정화되고 체력을 {ally_heal} 회복합니다.")
            .param("boon_extend", 30)
            .param("bane_reduce", 40)
            .param("throw_range", 18)
            .param("splash_radius", 3.5)
            .param("splash_damage", 3)
            .param("bane_duration", 4)
            .param("blind_duration", 1.5)
            .param("boon_duration", 5)
            .param("stone_cast", 0.5)
            .param("stone_duration", 6)
            .param("cleanse_radius", 8)
            .param("ally_heal", 6)
            .build();

    private static final String COLOR_TAG = "#A66BFF";
    /** Curses: pale lilac core, violet body, deep purple edge. */
    private static final Vfx.Palette CURSE = Vfx.Palette.of(0xF0D9FF, 0x9C4DFF, 0x3A1060);
    /** Blessings: pale lime core, herb green body, deep green edge. */
    private static final Vfx.Palette BLESS = Vfx.Palette.of(0xF4FFD6, 0x7DDC5A, 0x1E6B2E);
    /** The philosopher's stone: white-gold core, gold body, crimson edge. */
    private static final Vfx.Palette STONE = Vfx.Palette.of(0xFFF6D0, 0xFFC83D, 0xC1121F);
    /** Violet turning into green: the alchemist's two-tone signature. */
    private static final Particle.DustTransition TRANSMUTE =
            new Particle.DustTransition(CURSE.body(), BLESS.body(), 1.2f);
    private static final Particle.DustTransition GOLD_TO_CRIMSON = STONE.fade(1.1f);
    private static final Particle.DustOptions GOLD = STONE.dust(0.9f);
    private static final Particle.DustOptions VIOLET = CURSE.dust(0.9f);
    private static final Particle.DustOptions HERB = BLESS.dust(0.9f);

    private static final BlockData GEM_SHELL = Material.RED_STAINED_GLASS.createBlockData();
    private static final BlockData GEM_CORE = Material.REDSTONE_BLOCK.createBlockData();
    private static final BlockData SHARD = Material.RED_STAINED_GLASS.createBlockData();

    /** A brew a flask can give: the effect, its level (amplifier), its label and swirl color. */
    private record Brew(PotionEffectType type, int amplifier, String label, Color color, boolean blinding) {
    }

    private static final List<Brew> BANES = List.of(
            new Brew(PotionEffectType.POISON, 0, "독", Color.fromRGB(0x87A363), false),
            new Brew(PotionEffectType.SLOWNESS, 1, "구속", Color.fromRGB(0x5A6C9E), false),
            new Brew(PotionEffectType.WEAKNESS, 0, "나약함", Color.fromRGB(0x8A8AA0), false),
            new Brew(PotionEffectType.BLINDNESS, 0, "실명", Color.fromRGB(0x3A3050), true));
    private static final List<Brew> BOONS = List.of(
            new Brew(PotionEffectType.SPEED, 1, "신속", Color.fromRGB(0x33EBFF), false),
            new Brew(PotionEffectType.REGENERATION, 1, "재생", Color.fromRGB(0xFF7FB6), false),
            new Brew(PotionEffectType.RESISTANCE, 0, "저항", Color.fromRGB(0x9146F0), false));
    /** The stone's buffs: strength I, speed II, resistance I. */
    private static final List<Brew> STONE_BUFFS = List.of(
            new Brew(PotionEffectType.STRENGTH, 0, "힘", Color.fromRGB(0xFFC700), false),
            new Brew(PotionEffectType.SPEED, 1, "신속", Color.fromRGB(0x33EBFF), false),
            new Brew(PotionEffectType.RESISTANCE, 0, "저항", Color.fromRGB(0x9146F0), false));

    // active: experimental flask
    private static final double FLASK_SPEED = 1.3;
    private static final int FLASK_MIN_TICKS = 4;
    private static final int FLASK_MAX_TICKS = 16;
    private static final int GROUND_SNAP = 6;
    /** Height of the splash's hit-test centre above the ground. */
    private static final double SPLASH_CORE = 0.9;
    private static final int LABEL_TICKS = 22;
    private static final int MAX_LABELS = 8;
    private static final int MIST_TICKS = 16;

    // passive feedback
    private static final long POTION_POP_COOLDOWN = 10;
    private static final int POTION_POP_TICKS = 14;

    // ultimate: philosopher's stone
    private static final String[] PLANETS = {"☉", "☽", "☿", "♀", "♂", "♃", "♄"};
    private static final String[] PLANET_COLORS = {"#FFD54F", "#E0E0FF", "#B388FF", "#7DDC5A", "#FF6B6B", "#FFB74D",
            "#A1887F"};
    private static final double CIRCLE_RADIUS = 2.2;
    private static final double SYMBOL_ORBIT = 1.5;
    private static final double SYMBOL_HEIGHT = 1.1;
    private static final double GEM_HEIGHT = 2.7;
    private static final float GEM_SIZE = 0.55f;
    private static final Quaternionf GEM_TILT = new Quaternionf().rotateY((float) Math.toRadians(45))
            .rotateX((float) Math.toRadians(35.26));
    private static final Vector3f GEM_AXIS = new Vector3f(0.2f, 1f, 0.1f).normalize();
    private static final int SINK_TICKS = 3;
    private static final int SHARDS = 3;
    private static final double SHARD_ORBIT = 0.75;

    /** Last seen duration and level of each effect on the owner, to spot fresh applications (see {@link #onTick}). */
    private final Map<PotionEffectType, Seen> seen = new HashMap<>();
    /** The target of the ability hit being dealt right now, and whether it landed (see {@link #onDamageDealt}). */
    private @Nullable LivingEntity pendingTarget;
    private boolean landed;
    private long lastPotionPop = Long.MIN_VALUE / 2;

    private record Seen(int duration, int amplifier) {
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        seen.clear();
    }

    // ------------------------------------------------------------------ passive: potion mastery

    /**
     * Rescales every freshly applied (or refreshed) timed effect once: beneficial ones last longer, harmful ones
     * shorter. An effect counts as fresh when it is new, its level changed or its duration went up since last tick.
     */
    @Override
    public void onTick(long tick) {
        Player owner = player();
        if (owner.getActivePotionEffects().isEmpty()) {
            seen.clear();
            return;
        }
        Set<PotionEffectType> present = new HashSet<>();
        for (PotionEffect effect : List.copyOf(owner.getActivePotionEffects())) {
            PotionEffectType type = effect.getType();
            present.add(type);
            if (effect.isInfinite() || effect.getDuration() <= 0) {
                continue;
            }
            Seen last = seen.get(type);
            boolean fresh = last == null || last.amplifier() != effect.getAmplifier()
                    || effect.getDuration() > last.duration();
            seen.put(type, new Seen(fresh ? rescale(owner, effect) : effect.getDuration(), effect.getAmplifier()));
        }
        seen.keySet().retainAll(present);
    }

    /** Re-applies {@code effect} with its duration scaled by the passive; returns the new duration. */
    private int rescale(Player owner, PotionEffect effect) {
        PotionEffectType.Category category = effect.getType().getEffectCategory();
        double factor = switch (category) {
            case BENEFICIAL -> 1 + Math.max(0, params().num("boon_extend")) / 100.0;
            case HARMFUL -> 1 - Math.clamp(params().num("bane_reduce") / 100.0, 0.0, 1.0);
            default -> 1.0;
        };
        if (Math.abs(factor - 1) < 1.0E-6) {
            return effect.getDuration();
        }
        int duration = (int) Math.clamp(Math.round(effect.getDuration() * factor), 1L, Integer.MAX_VALUE);
        owner.removePotionEffect(effect.getType());
        owner.addPotionEffect(new PotionEffect(effect.getType(), duration, effect.getAmplifier(), effect.isAmbient(),
                effect.hasParticles(), effect.hasIcon()));
        // A curse is neutralised (violet turning green), a blessing glitters a moment longer.
        Location body = Fx.body(owner);
        potionPop(owner, category == PotionEffectType.Category.HARMFUL);
        if (category == PotionEffectType.Category.HARMFUL) {
            Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 6, 0.3, 0, TRANSMUTE, false);
            Vfx.cloud(ctx(), body, Particle.WHITE_SMOKE, 2, 0.2, 0.01, null, false);
            Fx.sound(body, Sound.BLOCK_BREWING_STAND_BREW, 0.35f, 1.8f + Fx.jitter(0.1f));
        } else {
            Vfx.cloud(ctx(), body, Particle.WAX_OFF, 4, 0.3, 0.05, null, false);
            Vfx.cloud(ctx(), body, Particle.DUST, 4, 0.3, 0, HERB, false);
        }
        return duration;
    }

    /**
     * A little flask pops out over the alchemist's head and is drained: violet when it neutralised a curse, green
     * when it stretched a blessing. At most one at a time.
     */
    private void potionPop(Player owner, boolean antidote) {
        long now = ctx().currentTick();
        if (now - lastPotionPop < POTION_POP_COOLDOWN) {
            return;
        }
        lastPotionPop = now;
        ItemStack item = ItemStack.of(Material.POTION);
        item.setData(DataComponentTypes.POTION_CONTENTS,
                PotionContents.potionContents().customColor(antidote ? CURSE.body() : BLESS.body()));
        Location above = owner.getLocation().add(0, owner.getHeight() + 0.45, 0);
        ItemDisplay flask = Displays.item(ctx(), owner, above, item,
                Displays.centered(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), POTION_POP_TICKS + 4);
        flask.setBillboard(Display.Billboard.VERTICAL);
        Displays.animateNextTick(ctx(), owner, flask, Displays.centered(0, 0.25f, 0, 0.4f, 0.4f, 0.4f, 0, 0, 20), 4);
        ctx().runLater(owner, POTION_POP_TICKS - 4, () -> {
            if (flask.isValid()) {
                Displays.shrinkAway(ctx(), owner, flask, 4);
            }
        });
        Fx.sound(above, antidote ? Sound.ENTITY_GENERIC_DRINK : Sound.ITEM_BOTTLE_FILL, 0.35f,
                1.5f + Fx.jitter(0.1f));
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() && victim.equals(pendingTarget)) {
            landed = true;
        }
    }

    // ------------------------------------------------------------------ active: experimental flask

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        Location landing = aimPoint(eye, direction, Math.max(1, params().num("throw_range")));
        Location start = eye.clone().add(direction.clone().multiply(0.6)).subtract(0, 0.2, 0);
        Flask flask = new Flask(start, landing);
        Loop.repeat(ctx(), owner, 0, 1, flask.flightTicks + 1L, flask);
        Vfx.cloud(ctx(), start, Particle.WITCH, 6, 0.1, 0.02, null, false);
        Fx.sound(eye, Sound.ENTITY_SPLASH_POTION_THROW, 1.0f, 0.9f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ENTITY_WITCH_THROW, 0.7f, 1.2f + Fx.jitter(0.1f));
        return ActivationResult.success();
    }

    /** Where the flask lands: the aimed enemy or block (or the end of the range), dropped onto the ground below. */
    private Location aimPoint(Location eye, Vector direction, double range) {
        Player owner = player();
        World world = owner.getWorld();
        RayTraceResult hit = world.rayTrace(eye, direction, range, FluidCollisionMode.NEVER, true, 0.25,
                entity -> entity instanceof LivingEntity && ctx().isEnemy(owner, entity));
        Location point;
        if (hit == null) {
            point = eye.clone().add(direction.clone().multiply(range));
        } else if (hit.getHitEntity() != null) {
            point = hit.getHitEntity().getLocation();
        } else {
            point = hit.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.3));
        }
        Double ground = groundBelow(point);
        if (ground != null) {
            point.setY(ground);
        }
        return point;
    }

    /** The top of the first solid block at most {@link #GROUND_SNAP} blocks below {@code point}, null if none. */
    private static @Nullable Double groundBelow(Location point) {
        World world = point.getWorld();
        int start = Math.min(point.getBlockY(), world.getMaxHeight() - 1);
        int end = Math.max(world.getMinHeight(), point.getBlockY() - GROUND_SNAP);
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(point.getBlockX(), y, point.getBlockZ());
            if (!block.isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /**
     * The thrown flask: a tumbling violet splash potion flying in an arc with a trail of witch sparkles, while a
     * two-tone ring on the ground marks where it will burst.
     */
    private final class Flask implements LongPredicate {

        private final Location start;
        private final Location landing;
        private final Location marker;
        private final Vector path;
        private final double arc;
        private final double radius;
        private final int flightTicks;
        private final ItemDisplay visual;

        Flask(Location start, Location landing) {
            this.start = start;
            this.landing = landing;
            this.marker = landing.clone().add(0, 0.1, 0);
            this.path = landing.toVector().subtract(start.toVector());
            double distance = path.length();
            this.flightTicks = Math.clamp((long) Math.ceil(distance / FLASK_SPEED), FLASK_MIN_TICKS, FLASK_MAX_TICKS);
            this.arc = Math.min(3.5, 0.5 + distance * 0.16);
            this.radius = Math.max(0.5, params().num("splash_radius"));
            ItemStack item = ItemStack.of(Material.SPLASH_POTION);
            item.setData(DataComponentTypes.POTION_CONTENTS, PotionContents.potionContents().customColor(CURSE.body()));
            this.visual = Displays.item(ctx(), player(), start, item,
                    Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f, 0, 0, 0), flightTicks + 2L);
            visual.setTeleportDuration(1);
        }

        @Override
        public boolean test(long tick) {
            if (tick >= flightTicks) {
                visual.remove();
                splash(landing, radius);
                return false;
            }
            double t = (double) (tick + 1) / flightTicks;
            Location position = start.clone().add(path.clone().multiply(t)).add(0, 4 * arc * t * (1 - t), 0);
            if (visual.isValid()) {
                visual.teleport(position);
                Displays.animate(visual, Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f, tick * 30f, tick * 45f, 0), 1);
            }
            Vfx.dot(position, Particle.WITCH, null, false);
            Vfx.dot(position, Particle.DUST, tick % 2 == 0 ? VIOLET : HERB, false);
            if (tick % 2 == 0) {
                // Telegraph: the splash zone, violet outside and a green ring closing in as the flask comes down.
                Vfx.ring(ctx(), marker, radius, (int) Math.max(12, radius * 6), tick * 0.1, Particle.DUST, VIOLET,
                        true);
                Vfx.ring(ctx(), marker, Math.max(0.3, radius * (1 - t)), (int) Math.max(8, radius * 4), -tick * 0.15,
                        Particle.DUST, HERB, true);
            }
            return true;
        }
    }

    /** The flask bursts: enemies are hurt and cursed, the alchemist and its allies blessed, each with its own brew. */
    private void splash(Location landing, double radius) {
        Player owner = player();
        World world = landing.getWorld();
        Location core = landing.clone().add(0, SPLASH_CORE, 0);
        Location floor = landing.clone().add(0, 0.1, 0);
        Location splash = landing.clone().add(0, 0.35, 0);
        ItemStack bottle = ItemStack.of(Material.SPLASH_POTION);

        Vfx.dot(splash, Particle.FLASH, CURSE.body(), false);
        world.spawnParticle(Particle.INSTANT_EFFECT, splash, Vfx.n(ctx(), 24), 0.7, 0.3, 0.7, 0.3,
                new Particle.Spell(CURSE.body(), 1.0f));
        world.spawnParticle(Particle.INSTANT_EFFECT, splash, Vfx.n(ctx(), 16), 0.6, 0.3, 0.6, 0.3,
                new Particle.Spell(BLESS.body(), 1.0f));
        world.spawnParticle(Particle.ITEM, splash, Vfx.n(ctx(), 10), 0.2, 0.1, 0.2, 0.15, bottle);
        Vfx.shockwave(ctx(), owner, floor, 0.3, radius, 6, Particle.DUST_COLOR_TRANSITION, TRANSMUTE, false);
        Vfx.cloud(ctx(), splash, Particle.WITCH, 12, 0.5, 0.1, null, false);
        Fx.sound(splash, Sound.ENTITY_SPLASH_POTION_BREAK, 1.1f, 0.9f + Fx.jitter(0.05f));
        Fx.sound(splash, Sound.BLOCK_BREWING_STAND_BREW, 0.9f, 1.2f + Fx.jitter(0.1f));
        Fx.sound(splash, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.7f, 1.3f);

        long baneTicks = params().ticks("bane_duration");
        long blindTicks = params().ticks("blind_duration");
        long boonTicks = params().ticks("boon_duration");
        double damage = params().num("splash_damage");
        int labels = 0;
        List<Brew> banes = shuffled(BANES);
        int next = 0;
        int cursed = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, core, radius)) {
            Brew brew = banes.get(next++ % banes.size());
            if (!strike(enemy, damage)) {
                continue; // a dodged or blocked splash curses nobody
            }
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            long ticks = brew.blinding() ? blindTicks : baneTicks;
            apply(enemy, brew, ticks);
            cursed++;
            brewFx(enemy, brew, labels++ < MAX_LABELS, CURSE);
        }
        List<LivingEntity> blessed = new ArrayList<>(ctx().alliesNear(owner, core, radius));
        if (distanceToBox(core, owner.getBoundingBox()) <= radius) {
            blessed.add(owner);
        }
        List<Brew> boons = shuffled(BOONS);
        next = 0;
        for (LivingEntity friend : blessed) {
            Brew brew = boons.get(next++ % boons.size());
            apply(friend, brew, boonTicks);
            brewFx(friend, brew, labels++ < MAX_LABELS, BLESS);
        }
        if (cursed + blessed.size() > 0) {
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚗ 실험 결과: <#C7A3FF>저주 " + cursed + "</#C7A3FF> · "
                    + "<#9BE07A>축복 " + blessed.size() + "</#9BE07A></" + COLOR_TAG + ">"));
        }
        mist(landing, radius);
    }

    /** Deals the splash damage and tells whether it landed (a dodge or shield cancels the curse with it). */
    private boolean strike(LivingEntity target, double damage) {
        if (!(damage > 0)) {
            return Combat.isTargetable(ctx(), player(), target);
        }
        pendingTarget = target;
        landed = false;
        try {
            Combat.hit(ctx(), player(), target, damage);
        } finally {
            pendingTarget = null;
        }
        return landed;
    }

    private static void apply(LivingEntity target, Brew brew, long ticks) {
        int duration = (int) Math.min(Integer.MAX_VALUE, ticks);
        if (duration > 0) {
            target.addPotionEffect(new PotionEffect(brew.type(), duration, brew.amplifier(), false, true, true));
        }
    }

    /** The brew takes: a swirl of its color climbing the body and, for the first few, a floating label. */
    private void brewFx(LivingEntity target, Brew brew, boolean withLabel, Vfx.Palette tone) {
        Location feet = target.getLocation();
        World world = feet.getWorld();
        double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        Vfx.helix(ctx(), feet.clone().add(0, 0.1, 0), 0.55, target.getHeight(), 1.2, 2, 8, phase, Particle.ENTITY_EFFECT,
                brew.color(), false);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, Fx.body(target), Vfx.n(ctx(), 6), 0.25, 0.4, 0.25, 0,
                tone.glow(1.0f));
        if (withLabel) {
            String hex = String.format("#%06X", brew.color().asRGB());
            String level = brew.amplifier() > 0 ? " " + "I".repeat(brew.amplifier() + 1) : "";
            Location above = target.getLocation().add(0, target.getHeight() + 0.35, 0);
            TextDisplay label = Displays.text(ctx(), player(), above,
                    Text.mm("<b><" + hex + ">" + brew.label() + level + "!</" + hex + "></b>"), 0.5f, LABEL_TICKS);
            Displays.animateNextTick(ctx(), player(), label, Displays.centered(0, 0.45f, 0, 1.0f, 1.0f, 1.0f, 0, 0, 0),
                    6);
            ctx().runLater(player(), LABEL_TICKS - 6, () -> {
                if (label.isValid()) {
                    Displays.shrinkAway(ctx(), player(), label, 5);
                }
            });
        }
    }

    /** Afterglow: a violet-green mist rising from the splash zone and thinning out. */
    private void mist(Location landing, double radius) {
        Loop.repeat(ctx(), player(), 2, 2, MIST_TICKS / 2, run -> {
            double fade = 1 - (double) run / (MIST_TICKS / 2.0);
            int count = (int) Math.round(Vfx.n(ctx(), 4) * fade);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < count; i++) {
                Location at = Vfx.scatter(landing, radius * 0.9).add(0, 0.2 + random.nextDouble() * 1.2, 0);
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, TRANSMUTE, false);
            }
            if (run % 2 == 0) {
                Vfx.dot(Vfx.scatter(landing, radius * 0.6).add(0, 0.3, 0), Particle.WITCH, null, false);
            }
            return true;
        });
    }

    private static List<Brew> shuffled(List<Brew> brews) {
        List<Brew> copy = new ArrayList<>(brews);
        Collections.shuffle(copy, ThreadLocalRandom.current());
        return copy;
    }

    // ------------------------------------------------------------------ ultimate: philosopher's stone

    @Override
    public ActivationResult onUltimate() {
        Ritual ritual = new Ritual();
        ritual.open();
        Loop.repeat(ctx(), player(), 1, 1, ritual.castTicks + 1L, ritual);
        return ActivationResult.success();
    }

    /**
     * The forging of the stone: a transmutation circle, the planetary symbols orbiting and a red gem growing above
     * the alchemist's head; when the cast time is over the gem sinks into the chest and the stone takes effect.
     */
    private final class Ritual implements LongPredicate {

        private final long castTicks = Math.max(1, params().ticks("stone_cast"));
        private final List<TextDisplay> symbols = new ArrayList<>();
        private @Nullable BlockDisplay shell;
        private @Nullable BlockDisplay core;
        private final double phase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);

        void open() {
            Player owner = player();
            Location feet = owner.getLocation();
            long life = castTicks + 12;
            for (int i = 0; i < PLANETS.length; i++) {
                TextDisplay symbol = Displays.text(ctx(), owner, symbolPoint(feet, i, 0),
                        Text.mm("<b><" + PLANET_COLORS[i] + ">" + PLANETS[i] + "</" + PLANET_COLORS[i] + "></b>"),
                        0.2f, life);
                symbol.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, symbol, Displays.centered(0, 0, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0),
                        5);
                symbols.add(symbol);
            }
            Location gemAt = feet.clone().add(0, GEM_HEIGHT, 0);
            gemAt.setYaw(0);
            gemAt.setPitch(0);
            shell = Displays.block(ctx(), owner, gemAt, GEM_SHELL, cube(0.02f, GEM_TILT), life);
            shell.setTeleportDuration(SINK_TICKS);
            Displays.outline(shell, STONE.edge());
            core = Displays.block(ctx(), owner, gemAt, GEM_CORE, cube(0.01f, GEM_TILT), life);
            core.setTeleportDuration(SINK_TICKS);

            Location ground = feet.clone().add(0, 0.08, 0);
            Vfx.shockwave(ctx(), owner, ground, 0.4, CIRCLE_RADIUS, 6, Particle.DUST_COLOR_TRANSITION, GOLD_TO_CRIMSON,
                    true);
            Vfx.cloud(ctx(), feet.clone().add(0, 1, 0), Particle.ENCHANT, 30, 0.8, 0.6, null, true);
            Fx.sound(feet, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.2f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(feet, Sound.BLOCK_BREWING_STAND_BREW, 1.2f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(feet, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.8f, 1.4f + Fx.jitter(0.05f));
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚗ 현자의 돌을 빚는 중...</" + COLOR_TAG + ">"));
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            Location feet = owner.getLocation();
            if (tick >= castTicks) {
                consume(feet);
                return false;
            }
            double progress = (double) (tick + 1) / castTicks;
            for (int i = 0; i < symbols.size(); i++) {
                TextDisplay symbol = symbols.get(i);
                if (symbol.isValid()) {
                    symbol.teleport(symbolPoint(feet, i, tick * 0.18));
                }
            }
            Location gemAt = feet.clone().add(0, GEM_HEIGHT, 0);
            float size = GEM_SIZE * (float) Math.min(1.0, progress * 1.2);
            Quaternionf spin = new Quaternionf().rotateAxis(tick * 0.35f, GEM_AXIS).mul(GEM_TILT);
            for (BlockDisplay part : new BlockDisplay[] {shell, core}) {
                if (part != null && part.isValid()) {
                    part.teleport(gemAt);
                    Displays.animate(part, cube(part == core ? size * 0.5f : size, spin), 1);
                }
            }
            if (tick % 2 == 0) {
                drawCircle(feet, tick);
            }
            // The symbols pour their essence into the gem.
            int index = (int) (tick % PLANETS.length);
            Color color = Color.fromRGB(Integer.parseInt(PLANET_COLORS[index].substring(1), 16));
            Vfx.trail(symbolPoint(feet, index, tick * 0.18), gemAt, color, 6, true);
            if (tick % 4 == 0) {
                Fx.sound(feet, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, (float) (0.8 + progress));
            }
            return true;
        }

        /** The ground circle: an outer gold ring, an inner violet ring and a hexagram, turning slowly. */
        private void drawCircle(Location feet, long tick) {
            Location ground = feet.clone().add(0, 0.08, 0);
            double turn = phase + tick * 0.05;
            Vfx.ring(ctx(), ground, CIRCLE_RADIUS, 32, turn, Particle.DUST, GOLD, true);
            Vfx.ring(ctx(), ground, CIRCLE_RADIUS * 0.72, 22, -turn, Particle.DUST, VIOLET, true);
            for (int triangle = 0; triangle < 2; triangle++) {
                for (int side = 0; side < 3; side++) {
                    double a = turn + triangle * Math.PI / 3 + side * Math.PI * 2 / 3;
                    double b = a + Math.PI * 2 / 3;
                    double r = CIRCLE_RADIUS * 0.72;
                    Location from = ground.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                    Location to = ground.clone().add(Math.cos(b) * r, 0, Math.sin(b) * r);
                    Vfx.line(ctx(), from, to, 2.2, Particle.DUST, triangle == 0 ? GOLD : HERB, true);
                }
            }
        }

        /** The gem sinks into the chest and bursts: the stone takes effect. */
        private void consume(Location feet) {
            Player owner = player();
            Location chest = Fx.body(owner);
            for (BlockDisplay part : new BlockDisplay[] {shell, core}) {
                if (part != null && part.isValid()) {
                    part.teleport(chest);
                    Displays.shrinkAway(ctx(), owner, part, SINK_TICKS);
                }
            }
            for (int i = 0; i < symbols.size(); i++) {
                TextDisplay symbol = symbols.get(i);
                if (symbol.isValid()) {
                    // The symbols fly outwards as the circle breaks.
                    Location at = symbol.getLocation();
                    Vector out = at.toVector().subtract(feet.toVector()).setY(0);
                    if (out.lengthSquared() > 1.0E-6) {
                        out.normalize().multiply(SYMBOL_ORBIT);
                    }
                    symbol.setTeleportDuration(6);
                    symbol.teleport(at.add(out).add(0, 0.6, 0));
                    Displays.shrinkAway(ctx(), owner, symbol, 6);
                }
            }
            ctx().runLater(owner, SINK_TICKS, AlchemistAbility.this::empower);
        }

        private Location symbolPoint(Location feet, int index, double turn) {
            double a = phase + turn + index * Math.PI * 2 / PLANETS.length;
            return feet.clone().add(Math.cos(a) * SYMBOL_ORBIT, SYMBOL_HEIGHT + 0.15 * Math.sin(a * 2),
                    Math.sin(a) * SYMBOL_ORBIT);
        }
    }

    /** The stone takes effect: full heal, cleanse, the buffs and the allies' cleanse and heal. */
    private void empower() {
        Player owner = player();
        Location chest = Fx.body(owner);
        ctx().heal(owner, Combat.maxHealth(owner));
        cleanse(owner);
        long ticks = Math.max(1, params().ticks("stone_duration"));
        for (Brew buff : STONE_BUFFS) {
            apply(owner, buff, ticks);
        }

        double radius = params().num("cleanse_radius");
        double heal = params().num("ally_heal");
        for (Player ally : ctx().alliesNear(owner, owner.getLocation(), radius)) {
            cleanse(ally);
            ctx().heal(ally, heal);
            Location body = Fx.body(ally);
            Vfx.trail(chest, body, STONE.body(), 8, true);
            Vfx.ring(ctx(), ally.getLocation().add(0, 0.1, 0), 0.7, 14, 0, Particle.DUST_COLOR_TRANSITION,
                    STONE.glow(1.0f), false);
            Vfx.cloud(ctx(), body, Particle.HEART, 3, 0.3, 0, null, false);
            Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.4f + Fx.jitter(0.1f));
        }

        World world = chest.getWorld();
        Vfx.dot(chest, Particle.FLASH, STONE.edge(), true);
        Vfx.dot(chest, Particle.FLASH, STONE.body(), true);
        Vfx.sphere(ctx(), chest, 1.1, 40, Particle.DUST_COLOR_TRANSITION, GOLD_TO_CRIMSON, true);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, chest, Vfx.n(ctx(), 30), 0.3, 0.5, 0.3, 0.45, null, true);
        Vfx.helix(ctx(), owner.getLocation(), 0.8, 2.8, 2, 3, 16, 0, Particle.DUST, GOLD, true);
        Vfx.shockwave(ctx(), owner, owner.getLocation().add(0, 0.1, 0), 0.5, Math.max(2.5, Math.min(radius, 8)), 8,
                Particle.DUST_COLOR_TRANSITION, STONE.glow(1.2f), true);
        Fx.sound(chest, Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1.3f);
        Fx.sound(chest, Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.2f);
        Fx.sound(chest, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(chest, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 0.4f, 1.8f);
        owner.sendActionBar(Text.mm("<b><gradient:#FFF6D0:#FFC83D:#C1121F>현자의 돌!</gradient></b>"));
        shards(ticks);
    }

    /** Removes harmful potion effects and fire. */
    private static void cleanse(LivingEntity target) {
        for (PotionEffect effect : List.copyOf(target.getActivePotionEffects())) {
            if (effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) {
                target.removePotionEffect(effect.getType());
            }
        }
        if (target.getFireTicks() > 0) {
            target.setFireTicks(0);
        }
    }

    /** While the buffs last, three red shards orbit the alchemist and gold motes rise from it. */
    private void shards(long ticks) {
        Player owner = player();
        List<BlockDisplay> pieces = new ArrayList<>();
        double start = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        for (int i = 0; i < SHARDS; i++) {
            BlockDisplay piece = Displays.block(ctx(), owner, shardPoint(owner, start, i, 0), SHARD,
                    cube(0.01f, GEM_TILT), ticks + 10);
            piece.setTeleportDuration(1);
            Displays.outline(piece, STONE.edge());
            Displays.animateNextTick(ctx(), owner, piece, cube(0.2f, GEM_TILT), 4);
            pieces.add(piece);
        }
        Loop.repeat(ctx(), owner, 1, 1, ticks, run -> {
            for (int i = 0; i < pieces.size(); i++) {
                BlockDisplay piece = pieces.get(i);
                if (piece.isValid()) {
                    piece.teleport(shardPoint(owner, start, i, run));
                }
            }
            if (run % 2 == 0) {
                Location at = Vfx.scatter(owner.getLocation(), 0.5).add(0, 0.2, 0);
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, GOLD_TO_CRIMSON, false);
            }
            if (run % 20 == 0 && run > 0) {
                owner.sendActionBar(Text.mm("<#FFC83D>현자의 돌 <white>" + Fx.seconds(ticks - run)
                        + "초</white></#FFC83D>"));
            }
            return true;
        }, () -> {
            for (BlockDisplay piece : pieces) {
                if (piece.isValid()) {
                    Displays.shrinkAway(ctx(), owner, piece, 5);
                }
            }
            Vfx.cloud(ctx(), Fx.body(owner), Particle.WAX_OFF, 6, 0.4, 0.05, null, false);
            Fx.sound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.7f);
        });
    }

    private static Location shardPoint(Player owner, double start, int index, long run) {
        double a = start + run * 0.22 + index * Math.PI * 2 / SHARDS;
        return owner.getLocation().add(Math.cos(a) * SHARD_ORBIT, 1.0 + 0.2 * Math.sin(run * 0.15 + index * 2),
                Math.sin(a) * SHARD_ORBIT);
    }

    // ------------------------------------------------------------------ helpers

    /** A cube of edge {@code size} centered on the block display's origin, rotated around its center. */
    private static Transformation cube(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    /** Distance from a point to the closest point of a box (0 inside). */
    private static double distanceToBox(Location point, BoundingBox box) {
        double dx = Math.max(Math.max(box.getMinX() - point.getX(), 0), point.getX() - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - point.getY(), 0), point.getY() - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - point.getZ(), 0), point.getZ() - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
