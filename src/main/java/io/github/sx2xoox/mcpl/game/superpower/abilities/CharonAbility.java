package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 카론 — the ferryman of the Styx, who carries the dead across for a coin. He paints ghost rivers on the battlefield (not
 * real water: translucent soul-blue sheets and particles that follow the ground and stop at walls): everyone in one is
 * carried by its current. In his river he and his allies go faster with the current and he takes less damage. The
 * active draws a channel whose current flows towards Charon, bringing the enemies in it to him while drowned hands slow
 * and hurt them; cast sneaking, it flows away from him instead, an escape road or a highway for his side. The ultimate
 * splits the field with a great Styx: Charon rows his boat along it, enemies inside are dragged downstream, and an enemy
 * that crosses it (in on one bank, out on the other) pays the fare: ultimate gauge, or health when it has too little.
 *
 * <p>Unlike quicksand pulling to a point, a tidal wave pushing once or ice and paint speeding up their maker, the river
 * carries everyone along a road: Charon chooses between a current that brings enemies in and a road that takes his side
 * away, and the ultimate turns terrain into a toll line. Nothing is pushed while standing still on the allied side: allies
 * only go faster when they move with the current.
 *
 * <p>Look: Styx teal, soul blue and ash black. The river is a translucent teal sheet over the ground with soul-blue banks;
 * motes of light run down it, glowing specks drift and souls rise now and then. Enemies in it are ringed by pale hands
 * reaching up from the water. The boat is a dark oak hull with a pointed bow and a soul lantern on a pole; coins fly
 * from a fare-payer into Charon's hand with a clink.
 */
public final class CharonAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("charon", "카론", Material.DARK_OAK_BOAT)
            .color(TextColor.color(0x2F8F8A))
            .difficulty(2)
            .passive("노 젓기",
                    "내 강물 안에서 나와 아군이 물살 쪽을 보고 움직이면 이동 속도가 {row_speed}% 빨라집니다(물살을 "
                            + "거슬러도 느려지지 않습니다). 내 강물 위에 서 있는 동안 받는 피해가 {row_guard}% 줄어듭니다.")
            .active("스틱스 물길",
                    "바라보는 방향으로 폭 {channel_width}·길이 {channel_length}블록의 유령 강을 {channel_duration}초간 "
                            + "그립니다(지형을 따라 흐르고 벽에서 멈춥니다). 물살은 나를 향해 흐르고, 강에 들어온 적은 물에 "
                            + "빠진 손에 붙잡혀 이동 속도가 {channel_slow}% 느려진 채 초속 {channel_current}블록으로 내 "
                            + "쪽으로 실려 오며 {channel_period}초마다 {channel_damage} 피해를 입습니다(적마다 최대 "
                            + "{channel_max_hits}번). 웅크리고 쓰면 물살이 나에게서 멀어지는 쪽으로 흘러 나와 아군의 "
                            + "도주로가 됩니다.",
                    10)
            .ultimate("뱃삯을 내라",
                    "바라보는 방향으로 폭 {styx_width}·길이 {styx_length}블록의 큰 스틱스강이 {styx_duration}초간 전장을 "
                            + "가르고 내 배가 나타납니다. 강 위에서는 웅크리지 않는 한 바라보는 방향으로 초속 "
                            + "{boat_speed}블록으로 노를 저어 나아갑니다. 강을 건너는 적(한쪽 둑으로 들어가 반대쪽 둑으로 "
                            + "나감)은 뱃삯으로 궁극기 게이지 {fare_gauge}%를 빼앗기고(모자라면 {fare_damage} 피해), 강 안의 "
                            + "적은 초속 {styx_current}블록으로 하류로 끌려가며 {styx_period}초마다 {styx_damage} 피해를 "
                            + "입습니다(적마다 최대 {styx_max_hits}번).")
            .param("row_speed", 60)
            .param("row_guard", 20)
            .param("channel_length", 16)
            .param("channel_width", 2)
            .param("channel_duration", 6)
            .param("channel_current", 2.5)
            .param("channel_slow", 30)
            .param("channel_damage", 1)
            .param("channel_period", 0.5)
            .param("channel_max_hits", 4)
            .param("styx_length", 30)
            .param("styx_width", 5)
            .param("styx_duration", 8)
            .param("boat_speed", 8)
            .param("styx_current", 2)
            .param("styx_damage", 1)
            .param("styx_period", 0.5)
            .param("styx_max_hits", 6)
            .param("fare_gauge", 20)
            .param("fare_damage", 4)
            .build();

    private static final String COLOR_TAG = "#2F8F8A";
    private static final String SOUL_TAG = "#5FD4E8";
    private static final String COIN_TAG = "#F2C94C";

    // ------------------------------------------------------------------ look: Styx teal, soul blue, ash
    /** Soul blue, Styx teal, ash black. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0x5FD4E8, 0x2F8F8A, 0x1E1F22);
    private static final Color SOUL_BLUE = Color.fromRGB(0x5FD4E8);
    private static final Color TEAL = Color.fromRGB(0x2F8F8A);
    private static final Color PALE_HAND = Color.fromRGB(0xCFEFF2);
    private static final Color COIN = Color.fromRGB(0xF2C94C);
    private static final Particle.DustOptions BANK_DUST = new Particle.DustOptions(SOUL_BLUE, 0.8f);
    private static final Particle.DustOptions TEAL_DUST = PALETTE.dust(1.0f);
    private static final Particle.DustOptions HAND_DUST = new Particle.DustOptions(PALE_HAND, 0.7f);
    private static final Particle.DustOptions ASH_DUST = new Particle.DustOptions(Color.fromRGB(0x1E1F22), 0.8f);
    private static final Particle.DustTransition SOUL_GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition SOUL_FADE = PALETTE.fade(1.2f);

    /** Block and item types of the visuals (block data and items are created on use: they need a running server). */
    private static final Material WATER_SHEET = Material.CYAN_STAINED_GLASS;
    private static final Material HULL = Material.DARK_OAK_PLANKS;
    private static final Material POLE = Material.DARK_OAK_LOG;
    private static final Material LANTERN_ITEM = Material.SOUL_LANTERN;
    private static final Material COIN_ITEM = Material.GOLD_NUGGET;

    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    /** Caps every velocity this ability applies. */
    private static final double MAX_SPEED = 1.2;
    /** Vanilla's vertical speed of a body resting on the ground: one tick of gravity. */
    private static final double REST_FALL = -Motion.GRAVITY * Motion.DRAG;
    /** Horizontal speed kept per tick by a walker on the ground (block friction 0.6 times 0.91). */
    private static final double GROUND_FRICTION = 0.546;

    // the river's shape
    private static final double STEP_UP = 1.1;
    private static final double MAX_DROP = 4;
    /** A river shorter than this (blocks) cannot be drawn. */
    private static final int MIN_LENGTH = 2;
    /** How far below and above the river's surface a body still counts as in it. */
    private static final double BELOW = 0.6;
    private static final double ABOVE = 1.6;
    private static final double END_SLACK = 0.25;
    /** Facing at least this much downstream counts as moving with the current. */
    private static final double WITH_CURRENT = 0.35;
    private static final long TUG_PERIOD = 2;
    private static final double SHEET_HEIGHT = 0.06;
    /** How far out of the river an enemy is still watched for the bank it stands on. */
    private static final double BANK_WATCH = 3;

    // the boat
    private static final long COIN_FLIGHT = 9;

    /** Rivers flowing right now (the active's and the ultimate's), for the passive guard. */
    private final List<River> rivers = new ArrayList<>();
    private final List<Display> displays = new ArrayList<>();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        rivers.clear();
        displays.clear();
    }

    // ------------------------------------------------------------------ passive: the ferryman's guard

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !(event.getDamage() > 0)) {
            return;
        }
        Player owner = player();
        rivers.removeIf(River::isOver);
        for (River river : rivers) {
            if (river.contains(owner)) {
                double guard = Math.clamp(params().num("row_guard") / 100.0, 0.0, 1.0);
                if (guard > 0) {
                    event.setDamage(event.getDamage() * (1 - guard));
                    Location feet = owner.getLocation().add(0, 0.15, 0);
                    Vfx.ring(ctx(), feet, 0.6, 10, 0, Particle.DUST, BANK_DUST, false);
                    Vfx.cloud(ctx(), feet, Particle.SPLASH, 6, 0.3, 0.05, null, false);
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ active: the Styx channel

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        boolean away = owner.isSneaking();
        River river = River.draw(this, owner, params().num("channel_length"), params().num("channel_width"));
        if (river == null) {
            return ActivationResult.fail("앞이 막혀 강을 그릴 수 없습니다");
        }
        river.configure(params().ticks("channel_duration"), away ? 1 : -1, params().num("channel_current"),
                params().num("channel_slow"), params().num("channel_damage"), params().ticks("channel_period"),
                params().integer("channel_max_hits"), false);
        rivers.add(river);
        river.start();
        Location feet = owner.getLocation();
        Fx.sound(feet, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, 1.0f, 0.8f);
        Fx.sound(feet, Sound.ENTITY_GENERIC_SPLASH, 1.0f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.PARTICLE_SOUL_ESCAPE, 1.0f, 0.8f);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>≈ 스틱스 물길</b></" + COLOR_TAG + "> <gray>"
                + (away ? "물살이 나에게서 멀어집니다 — 도주로" : "물살이 나를 향해 흐릅니다") + "</gray>"));
        return ActivationResult.success();
    }

    /**
     * One ghost river: a straight band over the ground in front of its maker, following the terrain up a step or down
     * a drop and ending at a wall. Its current carries enemies downstream (drowned hands slow and hurt them), and lets
     * its maker and his allies go faster when they move with it. The ultimate's Styx also has a boat and takes a fare.
     */
    private static final class River implements LongPredicate {

        private final CharonAbility ability;
        private final Player owner;
        private final World world;
        private final Location start;
        private final Vector direction;
        private final Vector side;
        private final double width;
        private final double[] ground;
        private final double length;
        private final List<BlockDisplay> sheets = new ArrayList<>();
        private final Map<UUID, Grip> grips = new HashMap<>();
        private final Map<UUID, NamespacedKey> boosts = new HashMap<>();
        private long duration;
        private Vector downstream = new Vector();
        private double current;
        private double slow;
        private double damage;
        private long period;
        private int maxHits;
        private boolean styx;
        private long endTick;
        private boolean over;
        private @Nullable Boat boat;

        /** An enemy in (or by) the river: its slow, its hits, and the bank it came from. */
        private static final class Grip {
            final LivingEntity entity;
            @Nullable NamespacedKey slowKey;
            int hits;
            long since;
            boolean inside;
            int lastSide;
            int entrySide;

            Grip(LivingEntity entity) {
                this.entity = entity;
            }
        }

        private River(CharonAbility ability, Player owner, Location start, Vector direction, double width,
                      double[] ground) {
            this.ability = ability;
            this.owner = owner;
            this.world = start.getWorld();
            this.start = start;
            this.direction = direction;
            this.side = new Vector(-direction.getZ(), 0, direction.getX());
            this.width = width;
            this.ground = ground;
            this.length = ground.length - 1;
        }

        /**
         * Lays a river out from the owner's feet along its facing: a ground height every block, following a step up
         * or a drop down, ending before a wall, a cliff, a hazard or the arena's edge. Null when it would be too short.
         */
        static @Nullable River draw(CharonAbility ability, Player owner, double length, double width) {
            World world = owner.getWorld();
            Vector direction = Motion.horizontalFacing(owner.getLocation());
            Location start = owner.getLocation();
            Spots.snapToGround(world, start, MAX_DROP);
            start = Displays.unrotated(start);
            int steps = (int) Math.max(0, Math.floor(length));
            List<Double> heights = new ArrayList<>();
            heights.add(start.getY());
            double y = start.getY();
            for (int i = 1; i <= steps; i++) {
                Location at = start.clone().add(direction.clone().multiply(i));
                Location next = groundUnder(world, at, y);
                if (next == null || !Spots.isFree(world, next, 0.6, 1.0) || !Spots.hasGroundBelow(world, next)
                        || !ability.ctx().isInsideArena(next)) {
                    break;
                }
                y = next.getY();
                heights.add(y);
            }
            if (heights.size() - 1 < MIN_LENGTH) {
                return null;
            }
            double[] ground = new double[heights.size()];
            for (int i = 0; i < ground.length; i++) {
                ground[i] = heights.get(i);
            }
            return new River(ability, owner, start, direction, Math.max(0.5, width), ground);
        }

        void configure(long ticks, int flow, double currentPerSecond, double slowPercent, double damage, long period,
                       int maxHits, boolean styx) {
            this.duration = Math.max(1, ticks);
            this.downstream = direction.clone().multiply(flow);
            this.current = Math.max(0, currentPerSecond) / 20.0;
            this.slow = Math.clamp(slowPercent / 100.0, 0.0, 0.95);
            this.damage = damage;
            this.period = Math.max(1, period);
            this.maxHits = Math.max(0, maxHits);
            this.styx = styx;
        }

        void attach(Boat boat) {
            this.boat = boat;
        }

        void start() {
            endTick = ability.ctx().currentTick() + duration;
            layWater();
            Loop.repeat(ability.ctx(), owner, 1, 1, duration, this, this::dry);
        }

        boolean isOver() {
            return over || ability.ctx().currentTick() >= endTick;
        }

        /** The ground height under {@code along} blocks down the river. */
        double groundAt(double along) {
            int index = (int) Math.round(Math.clamp(along, 0, length));
            return ground[index];
        }

        /** Where {@code entity}'s feet are on the river: {along, lateral}. */
        double[] project(LivingEntity entity) {
            Location feet = entity.getLocation();
            Vector offset = feet.toVector().subtract(start.toVector());
            return new double[] {offset.dot(direction), offset.dot(side)};
        }

        boolean contains(LivingEntity entity) {
            if (isOver() || !entity.getWorld().equals(world)) {
                return false;
            }
            double[] at = project(entity);
            if (at[0] < -END_SLACK || at[0] > length + END_SLACK || Math.abs(at[1]) > width / 2) {
                return false;
            }
            double height = entity.getLocation().getY() - groundAt(at[0]);
            return height >= -BELOW && height <= ABOVE;
        }

        /** The translucent teal sheets of water, one per stretch of level ground, unrolling from Charon's feet. */
        private void layWater() {
            int stretch = styx ? 3 : 2;
            float yaw = yawOf(direction);
            // Every sample owns the block of river around it; samples on level ground share one sheet, and no sheet
            // ever spans a step (it would float over the lower side).
            int i = 0;
            int laid = 0;
            int last = ground.length - 1;
            while (i <= last) {
                int end = i;
                while (end < last && end - i + 1 < stretch && Math.abs(ground[end + 1] - ground[i]) < 1.0E-3) {
                    end++;
                }
                double from = Math.max(0, i - 0.5);
                double to = Math.min(length, end + 0.5);
                double y = ground[i];
                if (to - from > 1.0E-3 && ability.reserve(1)) {
                    Location center = start.clone().add(direction.clone().multiply((from + to) / 2));
                    center.setY(y);
                    float span = (float) (to - from + 0.02);
                    BlockDisplay sheet = Displays.block(ability.ctx(), owner, Displays.unrotated(center),
                            WATER_SHEET.createBlockData(), sheetPose(0.05f, span, yaw), duration + 12);
                    sheet.setViewRange(styx ? 3.0f : 2.0f);
                    sheets.add(sheet);
                    ability.displays.add(sheet);
                    long delay = 1 + laid;
                    ability.ctx().runLater(owner, delay, () -> Displays.animate(sheet,
                            sheetPose((float) width, span, yaw), 4));
                    laid++;
                }
                i = end + 1;
            }
            for (int k = 0; k <= length; k += 2) {
                Location point = pointAt(k, 0);
                Vfx.cloud(ability.ctx(), point.clone().add(0, 0.1, 0), Particle.SPLASH, 3, width * 0.3, 0.05, null,
                        styx);
            }
        }

        private Transformation sheetPose(float across, float span, float yaw) {
            return Displays.transform(0, (float) (SHEET_HEIGHT / 2 + 0.01), 0, across, (float) SHEET_HEIGHT, span,
                    yaw, 0, 0);
        }

        /** A point on the river's surface, {@code along} blocks down it and {@code lateral} blocks to the side. */
        Location pointAt(double along, double lateral) {
            Location at = start.clone().add(direction.clone().multiply(along)).add(side.clone().multiply(lateral));
            at.setY(groundAt(along));
            return at;
        }

        @Override
        public boolean test(long tick) {
            if (over) {
                return false;
            }
            flow(tick);
            carry(tick);
            Boat ferry = boat;
            if (ferry != null) {
                ferry.row(tick);
            }
            return true;
        }

        /** The river runs: motes of light flow downstream, specks glow, the banks shimmer and souls rise. */
        private void flow(long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int motes = Vfx.n(ability.ctx(), styx ? 6 : 3);
            double run = 2.5;
            for (int i = 0; i < motes; i++) {
                double along = random.nextDouble(0, length);
                double lateral = random.nextDouble(-width / 2, width / 2);
                Location from = pointAt(along, lateral).add(0, 0.12, 0);
                double next = Math.clamp(along + (downstream.dot(direction) > 0 ? run : -run), 0, length);
                Location to = pointAt(next, lateral).add(0, 0.12, 0);
                Vfx.trail(from, to, i % 2 == 0 ? SOUL_BLUE : TEAL, 10, styx);
            }
            if (tick % 2 == 0) {
                Location speck = pointAt(random.nextDouble(0, length), random.nextDouble(-width / 2, width / 2));
                Vfx.dot(speck.add(0, 0.15, 0), Particle.GLOW, null, false);
            }
            if (tick % 6 == 0) {
                int points = (int) Math.max(4, Math.round(length * (styx ? 1.0 : 1.4)));
                for (int k = 0; k <= points; k++) {
                    double along = length * k / points;
                    for (int bank = -1; bank <= 1; bank += 2) {
                        Vfx.dot(pointAt(along, bank * width / 2).add(0, 0.1, 0), Particle.DUST, BANK_DUST, styx);
                    }
                }
            }
            if (tick % 10 == 0) {
                Location soul = pointAt(random.nextDouble(0, length), random.nextDouble(-width / 2, width / 2));
                Vfx.dot(soul.add(0, 0.2, 0), Particle.SOUL, null, false);
            }
            if (tick % 30 == 0) {
                Fx.sound(pointAt(length / 2, 0), Sound.BLOCK_WATER_AMBIENT, styx ? 1.2f : 0.8f, 0.7f);
            }
        }

        /** The current at work: enemies are gripped and carried, allies who go with it are sped along. */
        private void carry(long tick) {
            long now = ability.ctx().currentTick();
            Location middle = pointAt(length / 2, 0);
            double reach = length / 2 + width + BANK_WATCH;
            for (LivingEntity enemy : ability.ctx().enemiesNear(owner, middle, reach)) {
                Grip grip = grips.computeIfAbsent(enemy.getUniqueId(), id -> new Grip(enemy));
                double[] at = project(enemy);
                boolean inside = contains(enemy);
                if (inside) {
                    if (!grip.inside) {
                        enter(grip, now);
                    }
                    hold(grip, at, tick, now);
                } else {
                    int bank = Math.abs(at[1]) > width / 2 && at[0] >= 0 && at[0] <= length
                            && Math.abs(at[1]) <= width / 2 + BANK_WATCH ? (int) Math.signum(at[1]) : 0;
                    if (grip.inside) {
                        leave(grip, bank);
                    }
                    grip.lastSide = bank;
                }
            }
            for (Iterator<Grip> it = grips.values().iterator(); it.hasNext(); ) {
                Grip grip = it.next();
                if (!Combat.isTargetable(ability.ctx(), owner, grip.entity)) {
                    release(grip);
                    it.remove();
                } else if (grip.inside && !contains(grip.entity)) {
                    leave(grip, 0);
                }
            }
            speedAlong(owner);
            if (tick % 6 == 0 && contains(owner)) {
                // The ferryman stands on his own water: a ripple shows his guard is up.
                Vfx.ring(ability.ctx(), owner.getLocation().add(0, 0.08, 0), 0.7, 10, tick * 0.3, Particle.DUST,
                        TEAL_DUST, false);
            }
            for (Player ally : ability.ctx().alliesNear(owner, middle, reach)) {
                speedAlong(ally);
            }
            for (Iterator<Map.Entry<UUID, NamespacedKey>> it = boosts.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, NamespacedKey> entry = it.next();
                Player rider = owner.getServer().getPlayer(entry.getKey());
                // A rider that left the river some other way than walking out (a blink, a teleport) lets go too.
                if (rider == null || !rider.equals(owner) && !ability.ctx().isAlly(owner, rider) || !contains(rider)) {
                    if (rider != null) {
                        ability.ctx().removeModifier(rider, entry.getValue());
                    }
                    it.remove();
                }
            }
        }

        /** An enemy steps into the river: the drowned hands grab its legs. */
        private void enter(Grip grip, long now) {
            grip.inside = true;
            grip.entrySide = grip.lastSide;
            grip.since = now;
            LivingEntity enemy = grip.entity;
            if (slow > 0 && grip.slowKey == null) {
                grip.slowKey = ability.ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow,
                        Operation.MULTIPLY_SCALAR_1, 0);
            }
            if (!isHidden(owner, enemy)) {
                Location feet = enemy.getLocation().add(0, 0.1, 0);
                Vfx.cloud(ability.ctx(), feet, Particle.SPLASH, 10, 0.3, 0.1, null, false);
                Vfx.ring(ability.ctx(), feet, 0.5, 10, 0, Particle.DUST, HAND_DUST, false);
                Fx.sound(feet, Sound.ENTITY_PLAYER_SPLASH, 0.7f, 0.8f + Fx.jitter(0.1f));
                Fx.sound(feet, Sound.ENTITY_DROWNED_AMBIENT_WATER, 0.8f, 0.7f + Fx.jitter(0.1f));
            }
        }

        /** The hands hold on: the current carries it downstream, and every so often they drag it under. */
        private void hold(Grip grip, double[] at, long tick, long now) {
            LivingEntity enemy = grip.entity;
            if (tick % TUG_PERIOD == 0 && current > 0) {
                double toEnd = downstream.dot(direction) > 0 ? length - at[0] : at[0];
                boolean grounded = Motion.isGrounded(enemy) || enemy.isInWater();
                if (toEnd > 0.5 && grounded) {
                    // One tug per period, sized so a walker on the ground drifts at the current's speed.
                    double speed = current * TUG_PERIOD / (1 + GROUND_FRICTION);
                    Vector tug = downstream.clone().multiply(Math.min(speed, toEnd / (1 + GROUND_FRICTION)));
                    double fall = enemy instanceof Player ? REST_FALL : Math.min(0, enemy.getVelocity().getY());
                    // The current is crowd control, not knockback: it ignores knockback resistance.
                    enemy.setVelocity(Motion.clamp(tug.setY(fall), MAX_SPEED));
                }
            }
            long held = now - grip.since;
            if (damage > 0 && grip.hits < maxHits && held > 0 && held % period == 0) {
                grip.hits++;
                Combat.hit(ability.ctx(), owner, enemy, damage);
                if (!isHidden(owner, enemy)) {
                    Location feet = enemy.getLocation();
                    Vfx.dot(Fx.body(enemy), Particle.DUST, ASH_DUST, false);
                    Fx.sound(feet, Sound.ENTITY_DROWNED_HURT_WATER, 0.6f, 0.8f + Fx.jitter(0.1f));
                }
            }
            if (!isHidden(owner, enemy) && tick % 4 == 0) {
                hands(enemy, tick);
            }
        }

        /** Pale hands reaching up out of the water around the gripped enemy's legs. */
        private void hands(LivingEntity enemy, long tick) {
            Location feet = enemy.getLocation();
            for (int i = 0; i < 2; i++) {
                double angle = tick * 0.3 + i * Math.PI;
                Location base = feet.clone().add(Math.cos(angle) * 0.45, 0.05, Math.sin(angle) * 0.45);
                Vfx.line(ability.ctx(), base, base.clone().add(0, 0.6, 0), 6, Particle.DUST, HAND_DUST, false);
                Vfx.dot(base.clone().add(0, 0.65, 0), Particle.SOUL, null, false);
            }
            Vfx.ring(ability.ctx(), feet.clone().add(0, 0.08, 0), 0.55, 10, tick * 0.2, Particle.DUST_COLOR_TRANSITION,
                    SOUL_GLOW, false);
        }

        /** It got out: the hands let go. On the ultimate's Styx, getting out on the far bank costs the fare. */
        private void leave(Grip grip, int bank) {
            grip.inside = false;
            release(grip);
            if (styx && grip.entrySide != 0 && bank == -grip.entrySide) {
                ability.fare(owner, grip.entity);
            }
            grip.entrySide = 0;
        }

        private void release(Grip grip) {
            if (grip.slowKey != null) {
                ability.ctx().removeModifier(grip.entity, grip.slowKey);
                grip.slowKey = null;
            }
        }

        /** Charon or an ally in the river, moving with the current, goes faster; against it, as fast as ever. */
        private void speedAlong(Player rider) {
            NamespacedKey key = boosts.get(rider.getUniqueId());
            boolean rowing = rider.equals(owner) && boat != null && boat.isRowing();
            boolean with = !rowing && contains(rider)
                    && Motion.horizontalFacing(rider.getLocation()).dot(downstream) >= WITH_CURRENT;
            double bonus = Math.max(0, ability.params().num("row_speed")) / 100.0;
            if (with && key == null && bonus > 0) {
                boosts.put(rider.getUniqueId(), ability.ctx().addModifier(owner, rider, Attribute.MOVEMENT_SPEED,
                        bonus, Operation.MULTIPLY_SCALAR_1, 0));
            } else if (!with && key != null) {
                ability.ctx().removeModifier(rider, key);
                boosts.remove(rider.getUniqueId());
            }
            if (with && ability.ctx().currentTick() % 3 == 0) {
                Location feet = rider.getLocation();
                Vfx.dot(feet.clone().add(0, 0.1, 0), Particle.SPLASH, null, false);
                Vfx.trail(feet.clone().add(0, 0.15, 0), feet.clone().add(downstream.clone().multiply(1.2)).add(0, 0.15,
                        0), SOUL_BLUE, 6, false);
            }
        }

        /** The river runs dry: every grip and boost lets go, the water sinks away. */
        private void dry() {
            if (over) {
                return;
            }
            over = true;
            for (Grip grip : grips.values()) {
                release(grip);
            }
            grips.clear();
            for (Map.Entry<UUID, NamespacedKey> entry : boosts.entrySet()) {
                Player rider = owner.getServer().getPlayer(entry.getKey());
                if (rider != null) {
                    ability.ctx().removeModifier(rider, entry.getValue());
                }
            }
            boosts.clear();
            for (BlockDisplay sheet : sheets) {
                if (sheet.isValid()) {
                    Displays.shrinkAway(ability.ctx(), owner, sheet, 8);
                }
            }
            for (int k = 0; k <= length; k += 3) {
                Vfx.cloud(ability.ctx(), pointAt(k, 0).add(0, 0.2, 0), Particle.CLOUD, 2, width * 0.3, 0.02, null,
                        false);
            }
            Fx.sound(pointAt(length / 2, 0), Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 0.6f);
            Boat ferry = boat;
            if (ferry != null) {
                ferry.sink();
            }
            ability.rivers.remove(this);
        }
    }

    // ------------------------------------------------------------------ the fare

    /** {@code payer} crossed the Styx: the fare is its gauge, or its health when it has too little. */
    private void fare(Player owner, LivingEntity payer) {
        if (!Combat.isTargetable(ctx(), owner, payer)) {
            return;
        }
        double toll = Math.max(0, params().num("fare_gauge"));
        boolean gauge = payer instanceof Player player && toll > 0 && ctx().gauge(player) >= toll;
        String paid;
        if (gauge) {
            ctx().addGauge((Player) payer, -toll);
            paid = "궁 -" + AbilityInfo.formatNumber(toll) + "%";
        } else {
            Combat.hit(ctx(), owner, payer, params().num("fare_damage"));
            paid = "♥ -" + AbilityInfo.formatNumber(params().num("fare_damage"));
        }
        Location body = Fx.body(payer);
        if (!isHidden(owner, payer)) {
            flipCoin(owner, body);
            popWord(owner, payer.getLocation().add(0, payer.getHeight() + 0.6, 0),
                    "<" + COIN_TAG + "><b>뱃삯</b> " + paid + "</" + COIN_TAG + ">");
            Vfx.cloud(ctx(), body, Particle.WAX_OFF, 8, 0.3, 0.05, null, false);
        }
        Fx.sound(body, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.6f);
        Fx.sound(body, Sound.ITEM_ARMOR_EQUIP_GOLD, 1.0f, 1.4f);
        owner.sendActionBar(Text.mm("<" + COIN_TAG + ">⛀ 뱃삯</" + COIN_TAG + "> <white><payer></white> <gray>" + paid
                + "</gray>", Placeholder.component("payer", payer.name())));
        if (payer instanceof Player player) {
            player.sendActionBar(Text.mm("<" + COIN_TAG + ">⛀ 스틱스를 건넌 뱃삯</" + COIN_TAG + "> <gray>" + paid
                    + "</gray>"));
        }
    }

    /** A coin flips from the payer into Charon's hand. */
    private void flipCoin(Player owner, Location from) {
        Location hand = owner.getEyeLocation().subtract(0, 0.5, 0);
        Vfx.trail(from, hand, COIN, (int) COIN_FLIGHT, false);
        if (!reserve(1)) {
            return;
        }
        ItemDisplay coin = Displays.item(ctx(), owner, from, new ItemStack(COIN_ITEM),
                Displays.centered(0, 0, 0, 0.4f, 0.4f, 0.4f, 0, 0, 0), COIN_FLIGHT + 4);
        coin.setTeleportDuration((int) COIN_FLIGHT);
        coin.setBillboard(Display.Billboard.CENTER);
        displays.add(coin);
        ctx().runLater(owner, 1, () -> Displays.moveTo(coin, owner.getEyeLocation().subtract(0, 0.5, 0)));
        ctx().runLater(owner, COIN_FLIGHT, () -> {
            if (owner.isOnline()) {
                Fx.sound(owner.getLocation(), Sound.BLOCK_CHAIN_STEP, 0.8f, 1.8f);
                Vfx.cloud(ctx(), owner.getEyeLocation().subtract(0, 0.5, 0), Particle.WAX_OFF, 5, 0.15, 0.02, null,
                        false);
            }
        });
    }

    private void popWord(Player owner, Location at, String text) {
        if (!reserve(1)) {
            return;
        }
        TextDisplay word = Displays.text(ctx(), owner, at, Text.mm(text), 0.6f, 20);
        word.setViewRange(2.0f);
        displays.add(word);
        Displays.animateNextTick(ctx(), owner, word, Displays.centered(0, 0.3f, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0), 5);
    }

    // ------------------------------------------------------------------ ultimate: pay the fare

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        River styx = River.draw(this, owner, params().num("styx_length"), params().num("styx_width"));
        if (styx == null) {
            return ActivationResult.fail("앞이 막혀 스틱스강을 부를 수 없습니다");
        }
        styx.configure(params().ticks("styx_duration"), 1, params().num("styx_current"), 0,
                params().num("styx_damage"), params().ticks("styx_period"), params().integer("styx_max_hits"), true);
        Boat boat = new Boat(owner, styx);
        styx.attach(boat);
        rivers.add(styx);
        styx.start();
        boat.launch();
        Location feet = owner.getLocation();
        Vfx.dot(feet.clone().add(0, 1, 0), Particle.FLASH, SOUL_BLUE, true);
        Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.5, 5, 8, Particle.DUST_COLOR_TRANSITION, SOUL_FADE,
                true);
        Fx.sound(feet, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, 1.4f, 0.6f);
        Fx.sound(feet, Sound.ENTITY_GENERIC_SPLASH, 1.4f, 0.5f);
        Fx.sound(feet, Sound.BLOCK_BELL_USE, 1.2f, 0.5f);
        Fx.sound(feet, Sound.AMBIENT_UNDERWATER_ENTER, 1.0f, 0.8f);
        ctx().announce(Text.mm("<" + COLOR_TAG + ">≈</" + COLOR_TAG + "> <white><owner></white><gray>이(가) </gray><"
                        + SOUL_TAG + "><b>스틱스강</b></" + SOUL_TAG + "><gray>을 불렀습니다 — 건너려면 </gray><" + COIN_TAG
                        + ">뱃삯</" + COIN_TAG + "><gray>을 내야 합니다!</gray>",
                Placeholder.component("owner", owner.name())));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>≈ 뱃삯을 내라</b></" + COLOR_TAG + "> <gray>바라보는 쪽으로 "
                + "노를 젓습니다 (웅크리면 멈춤)</gray>"));
        return ActivationResult.success();
    }

    /**
     * Charon's boat on the Styx: a dark hull under his feet that turns where he looks. On the river (and not sneaking)
     * he rows forward at the boat's speed; off it, the boat waits by the bank. Rowing sets a fresh velocity each tick (a
     * player's server-side velocity is stale and never read) and he never takes fall damage from it.
     */
    private final class Boat {

        private final Player owner;
        private final River river;
        private final List<Display> parts = new ArrayList<>();
        private boolean rowing;
        private Location at;

        Boat(Player owner, River river) {
            this.owner = owner;
            this.river = river;
            this.at = Displays.unrotated(owner.getLocation());
        }

        void launch() {
            float yaw = owner.getLocation().getYaw();
            long life = river.duration + 20;
            if (reserve(BoatPart.ALL.length + 1)) {
                for (BoatPart part : BoatPart.ALL) {
                    BlockDisplay display = Displays.block(ctx(), owner, at, part.material.createBlockData(),
                            part.pose(yaw, 0.05f), life);
                    display.setTeleportDuration(1);
                    display.setViewRange(2.5f);
                    parts.add(display);
                    displays.add(display);
                    Displays.animateNextTick(ctx(), owner, display, part.pose(yaw, 1f), 6);
                }
                ItemDisplay lantern = Displays.item(ctx(), owner, at, new ItemStack(LANTERN_ITEM),
                        lanternPose(yaw, 0), life);
                lantern.setTeleportDuration(1);
                lantern.setViewRange(2.5f);
                Displays.outline(lantern, SOUL_BLUE);
                parts.add(lantern);
                displays.add(lantern);
            }
            Vfx.cloud(ctx(), at.clone().add(0, 0.3, 0), Particle.SPLASH, 20, 0.8, 0.1, null, true);
        }

        boolean isRowing() {
            return rowing;
        }

        /** One tick on the water: rows Charon along where he looks and keeps the boat under him. */
        void row(long tick) {
            boolean aboard = river.contains(owner);
            rowing = aboard && !owner.isSneaking();
            if (aboard) {
                owner.setFallDistance(0);
                at = Displays.unrotated(owner.getLocation());
                if (rowing) {
                    Vector look = Motion.horizontalFacing(owner.getLocation());
                    double speed = Math.max(0, params().num("boat_speed")) / 20.0;
                    boolean grounded = Motion.isGrounded(owner) || owner.isInWater();
                    if (grounded && speed > 0) {
                        owner.setVelocity(Motion.clamp(look.multiply(speed).setY(REST_FALL), MAX_SPEED));
                    }
                    if (tick % 8 == 0) {
                        Fx.sound(at, Sound.ENTITY_BOAT_PADDLE_WATER, 1.0f, 0.8f + Fx.jitter(0.1f));
                    }
                    if (tick % 2 == 0) {
                        Vector side = new Vector(-look.getZ(), 0, look.getX()).multiply(0.7);
                        Vfx.dot(at.clone().add(side).add(0, 0.15, 0), Particle.SPLASH, null, false);
                        Vfx.dot(at.clone().subtract(side).add(0, 0.15, 0), Particle.SPLASH, null, false);
                        Vfx.trail(at.clone().add(0, 0.12, 0), at.clone().subtract(look.clone().multiply(2)).add(0, 0.12,
                                0), SOUL_BLUE, 8, false);
                    }
                }
            }
            float yaw = owner.getLocation().getYaw();
            for (int i = 0; i < parts.size(); i++) {
                Display part = parts.get(i);
                if (!part.isValid()) {
                    continue;
                }
                Displays.moveTo(part, at);
                if (tick % 2 == 0) {
                    float bob = (float) Math.sin(tick * 0.2) * 0.04f;
                    Transformation pose = i < BoatPart.ALL.length ? BoatPart.ALL[i].pose(yaw, 1f, bob)
                            : lanternPose(yaw, bob);
                    Displays.animate(part, pose, 2);
                }
            }
            if (tick % 6 == 0) {
                Vector front = Motion.horizontalFacing(owner.getLocation()).multiply(1.3);
                Location lamp = at.clone().add(front).add(0, 1.4, 0);
                Vfx.dot(lamp, Particle.SOUL_FIRE_FLAME, null, false);
            }
        }

        void sink() {
            for (Display part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 8);
                }
            }
            Vfx.cloud(ctx(), at.clone().add(0, 0.3, 0), Particle.SPLASH, 12, 0.6, 0.1, null, false);
            Fx.sound(at, Sound.ENTITY_GENERIC_SPLASH, 0.8f, 0.6f);
        }

        /** The soul lantern hanging from the pole at the bow. */
        private Transformation lanternPose(float yaw, float bob) {
            Vector bow = facing(yaw).multiply(1.25);
            return Displays.centered((float) bow.getX(), 1.42f + bob, (float) bow.getZ(), 0.5f, 0.5f, 0.5f, yaw, 0, 0);
        }
    }

    /** One block of the boat, in the boat's frame (+z the bow, y up from the water). */
    private record BoatPart(Material material, float z, float y, float sx, float sy, float sz, float turn) {

        static final BoatPart[] ALL = {
                new BoatPart(HULL, 0f, 0.2f, 1.1f, 0.38f, 2.2f, 0f),
                new BoatPart(HULL, 1.15f, 0.2f, 0.76f, 0.38f, 0.76f, 45f),
                new BoatPart(HULL, -1.18f, 0.24f, 1.0f, 0.46f, 0.26f, 0f),
                new BoatPart(POLE, 1.25f, 0.85f, 0.08f, 1.1f, 0.08f, 0f),
        };

        Transformation pose(float yaw, float scale) {
            return pose(yaw, scale, 0);
        }

        /** This part turned to the boat's {@code yaw} around its middle, scaled by {@code scale}, bobbing. */
        Transformation pose(float yaw, float scale, float bob) {
            Vector offset = facing(yaw).multiply(z * scale);
            return Displays.transform((float) offset.getX(), y * scale + bob, (float) offset.getZ(), sx * scale,
                    sy * scale, sz * scale, yaw + turn, 0, 0);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The walkable ground at the column of {@code at}, coming from a ground at {@code fromY}: a ray down from a step
     * above it (started above the surface, never inside a block) finds the floor within the drop. Null for a wall
     * (the ray starts inside a block) or a drop too deep.
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

    /** The horizontal unit vector a Minecraft yaw faces (0 is +Z, 90 is -X). */
    private static Vector facing(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0, Math.cos(radians));
    }

    /** Minecraft's yaw (degrees: 0 = +Z, 90 = -X) of the horizontal part of {@code direction}, 0 if it has none. */
    private static float yawOf(Vector direction) {
        Vector flat = Motion.horizontal(direction);
        if (flat.lengthSquared() < 1.0E-6) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
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
