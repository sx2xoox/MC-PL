package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 기공 전사 — the ki fighter of battle manga. It reads the power of whoever it looks at for everyone to see (an enemy
 * close to its ultimate cannot even be measured), gathers ki between its hands for a burst of quick shots or one slow,
 * heavy blast, and as its ultimate raises a giant ball of everyone's ki over its head, fed by allies raising their hands
 * and by every blow it takes while it stands there defenceless, then lobs it slowly across the field.
 *
 * <p>Look (aura gold, ki sky blue, stone chips): a power reading floats over the measured enemy's head in gold digits
 * (red and shattering when it cannot be measured, with a gold diamond only the warrior's side sees through walls). A
 * ki ball is a white core in a sky-blue glass shell spinning between the hands, with a gold aura swirling up the body and
 * chips of stone lifting off the ground; the warrior's own reading climbs over its head while it charges. The giant
 * ball has a third shell with a sky-blue outline, light streams into it from all around and from the helping allies,
 * and its shadow darkens the ground where it is going to land.
 */
public final class KiWarriorAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("kiwarrior", "기공 전사", Material.NETHER_STAR)
            .color(TextColor.color(0xFFD23F))
            .difficulty(1)
            .passive("전투력 측정",
                    "바라보는 적({scan_range}블록 이내)의 머리 위에 그 적의 전투력(궁극기 게이지 ×100)이 "
                            + "{scan_duration}초간 모두에게 보입니다(한 번에 한 명). 게이지가 {scan_alert}% 이상인 적은 "
                            + "'측정 불가!!' — 숫자가 깨져 흩어지고, 벽 너머로도 보이는 표식이 {scan_glow}초간 나와 아군에게만 "
                            + "그 적의 머리 위에 떠오릅니다(같은 적은 {scan_alert_cooldown}초마다). 기를 모으는 동안에는 "
                            + "오르는 내 전투력도 모두에게 보입니다.")
            .active("기탄",
                    "두 손 사이에 기를 모읍니다(최대 {charge_max}초, 그동안 이동 속도 -{charge_slow}%, 받는 피해 "
                            + "+{charge_vulnerable}%, 무엇에도 끊기지 않음). 웅크리거나 가득 차면 발사합니다. {quick_time}초 "
                            + "안에 쏘면 빠른 작은 기탄 {small_count}발({small_range}블록, 각 {small_damage} 피해), 가득 모으면 느리고 큰 기탄"
                            + "({big_range}블록)이 터져 반경 {big_radius}블록의 적에게 {big_damage} 피해를 주고 밀쳐냅니다. 그 "
                            + "사이는 모은 시간에 비례해 커집니다. 전투 맵에서는 큰 기탄이 반경 {big_break_radius}블록의 지형도 "
                            + "부숩니다.",
                    8)
            .ultimate("모두의 기",
                    "두 손을 하늘로 들고 {gather_time}초간 머리 위에 거대한 기 구슬을 모읍니다(그동안 움직일 수 없고, "
                            + "쓰러져야만 흩어집니다). 반경 {ally_range}블록 안에서 나를 볼 수 있는 아군이 웅크려 손을 들면 "
                            + "1명마다(최대 +{ally_bonus_max}), 그동안 내가 받은 피해 {hurt_per_bonus}마다(최대 "
                            + "+{hurt_bonus_max}) 구슬이 커집니다(합쳐 최대 +{bonus_max}). 다 모이면 바라보는 쪽으로 초당 "
                            + "{throw_speed}블록씩 천천히 던지고, 땅·벽·적에 닿거나 {throw_range}블록을 날아가면 터져 반경 "
                            + "{ult_radius}블록의 적에게 {ult_damage} 피해를 주고 약 {ult_launch}블록 띄웁니다(커질 때마다 반경 "
                            + "+{bonus_radius}, 피해 +{bonus_damage}). 떨어질 자리에는 그림자가 집니다. 전투 맵에서는 반경 "
                            + "{ult_break_radius}블록의 지형도 부숩니다.")
            .param("scan_range", 32)
            .param("scan_duration", 3)
            .param("scan_alert", 90)
            .param("scan_glow", 4)
            .param("scan_alert_cooldown", 15)
            .param("charge_max", 1.6)
            .param("quick_time", 0.4)
            .param("charge_slow", 40)
            .param("charge_vulnerable", 20)
            .param("small_count", 3)
            .param("small_damage", 1.5)
            .param("small_range", 20)
            .param("big_damage", 6)
            .param("big_radius", 2.5)
            .param("big_range", 24)
            .param("big_break_radius", 1.5)
            .param("gather_time", 2.5)
            .param("ally_range", 16)
            .param("ally_bonus_max", 2)
            .param("hurt_per_bonus", 2)
            .param("hurt_bonus_max", 2)
            .param("bonus_max", 3)
            .param("throw_speed", 7)
            .param("throw_range", 30)
            .param("ult_radius", 3)
            .param("ult_damage", 8)
            .param("bonus_radius", 0.5)
            .param("bonus_damage", 1)
            .param("ult_launch", 3)
            .param("ult_break_radius", 3)
            .build();

    private static final String GOLD_TAG = "#FFD23F";
    private static final String SKY_TAG = "#8FE3FF";
    private static final String RED_TAG = "#FF4D4D";

    // ------------------------------------------------------------------ look
    /** The ki itself: white core, sky blue, deep blue edge. */
    private static final Vfx.Palette KI = Vfx.Palette.of(0xFFFFFF, 0x8FE3FF, 0x2F7FB8);
    /** The aura: pale gold, gold, old gold. */
    private static final Vfx.Palette AURA = Vfx.Palette.of(0xFFF6C8, 0xFFD23F, 0xB8860B);
    private static final Color GOLD = Color.fromRGB(0xFFD23F);
    private static final Color SKY = Color.fromRGB(0x8FE3FF);
    private static final Color WHITE = Color.fromRGB(0xFFFFFF);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 1.0f);
    private static final Particle.DustOptions SKY_DUST = new Particle.DustOptions(SKY, 1.2f);
    private static final Particle.DustOptions STONE_DUST = new Particle.DustOptions(Color.fromRGB(0x8A6E4B), 1.3f);
    private static final Particle.DustOptions SHADOW_DUST = new Particle.DustOptions(Color.fromRGB(0x1E1E28), 1.6f);
    private static final Particle.DustTransition AURA_GLOW = AURA.glow(1.0f);
    private static final Particle.DustTransition KI_GLOW = KI.glow(1.3f);

    /** Block types of the visuals (block data is created on use: it needs a running server). */
    private static final Material CORE = Material.WHITE_CONCRETE;
    private static final Material SHELL = Material.LIGHT_BLUE_STAINED_GLASS;
    private static final Material OUTER_SHELL = Material.WHITE_STAINED_GLASS;
    private static final Material MARKER = Material.GOLD_BLOCK;
    private static final Material[] CHIPS = {Material.PACKED_MUD, Material.COARSE_DIRT, Material.TUFF,
            Material.MUD_BRICKS};

    private static final int MAX_DISPLAYS = 24;
    private static final double TAU = Math.PI * 2;
    private static final double MAX_SPEED = 2.0;

    // passive: power reading
    private static final long SCAN_PERIOD = 4;
    private static final double LABEL_ABOVE = 0.75;
    private static final double MARKER_ABOVE = 1.35;
    private static final float LABEL_SCALE = 0.9f;
    private static final int SHARDS = 3;
    private static final long SHAKE_TICKS = 10;
    /** The warrior's own reading climbs by up to this much while it charges a ki blast. */
    private static final int CHARGE_POWER = 5000;

    // active: ki blast
    private static final int CHIP_COUNT = 4;
    private static final long SMALL_SPACING = 3;
    private static final double SMALL_SPEED = 1.4;
    private static final double SMALL_SPREAD = 0.06;
    private static final double SMALL_HIT = 0.35;
    private static final double SMALL_PUSH = 0.25;
    private static final double BIG_SPEED = 0.55;
    private static final double MID_SPEED = 1.1;
    private static final double MIN_BLAST_RADIUS = 1.0;
    private static final float SMALL_SIZE = 0.22f;
    private static final float MIN_SIZE = 0.35f;
    private static final float BIG_SIZE = 0.85f;
    private static final double PUSH = 0.7;
    private static final double PUSH_LIFT = 0.35;
    /** Below this radius the blast breaks nothing (a dent no one would see). */
    private static final double MIN_BREAK = 0.75;

    // ultimate: everyone's ki
    private static final double ORB_ABOVE = 1.6;
    private static final float ORB_BASE = 1.6f;
    private static final float ORB_PER_BONUS = 0.35f;
    private static final double MAX_UPWARD = 0.25;
    private static final int GATHER_CHIPS = 6;
    private static final long ALLY_CALL_PERIOD = 10;
    private static final double LAUNCH_AWAY = 0.35;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final Map<UUID, Long> nextAlert = new HashMap<>();
    private final List<Marker> markers = new ArrayList<>();
    private @Nullable Reading reading;
    private @Nullable Charge charge;
    private @Nullable Gathering gathering;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        reading = null;
        charge = null;
        gathering = null;
        markers.clear();
        live.clear();
    }

    // ------------------------------------------------------------------ passive: power reading

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        if (tick % SCAN_PERIOD == 0) {
            scan(owner, now);
        }
        Reading current = reading;
        if (current != null && !current.update(now, tick)) {
            current.drop();
        }
        markers.removeIf(marker -> !marker.update(now, tick));
    }

    /** Reads the enemy player the warrior is looking at (findTarget never returns one hidden from it). */
    private void scan(Player owner, long now) {
        LivingEntity aimed = ctx().findTarget(owner, Math.max(1, params().num("scan_range")));
        if (!(aimed instanceof Player target) || !ctx().isAlive(target) || !seen(owner, target)) {
            return;
        }
        Reading current = reading;
        if (current == null || !current.target.equals(target) || !current.label.isValid()) {
            if (current != null) {
                current.drop();
            }
            if (!reserve(1)) {
                return;
            }
            current = new Reading(owner, target);
            reading = current;
            current.pop();
        }
        current.seen = now;
        double gauge = ctx().gauge(target);
        if (gauge >= params().num("scan_alert") && now >= nextAlert.getOrDefault(target.getUniqueId(),
                Long.MIN_VALUE / 2)) {
            nextAlert.put(target.getUniqueId(), now + params().ticks("scan_alert_cooldown"));
            current.alert();
        }
    }

    /** The power of a participant as the warrior reads it: its ultimate gauge times 100. */
    private int power(Player target) {
        return (int) Math.round(Math.clamp(ctx().gauge(target), 0, 100) * 100);
    }

    private static String number(int value) {
        return String.format("%,d", value);
    }

    /** A reading gone haywire: digits and junk flickering past the scale. */
    private static String glitch() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String junk = "0123456789?!#%";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            text.append(junk.charAt(random.nextInt(junk.length())));
        }
        return text.toString();
    }

    /** The power reading over the head of the enemy the warrior looks at. */
    private final class Reading {

        private final Player owner;
        private final Player target;
        private final TextDisplay label;
        long seen;
        private long shakeUntil = Long.MIN_VALUE / 2;

        Reading(Player owner, Player target) {
            this.owner = owner;
            this.target = target;
            this.label = keep(Displays.text(ctx(), owner, labelPoint(target), Text.mm(text()), 0.1f, 0));
            label.setTeleportDuration(1);
            this.seen = ctx().currentTick();
        }

        /** The reading pops in, and a scan line sweeps up the measured body. */
        void pop() {
            Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0, 0, LABEL_SCALE, LABEL_SCALE,
                    LABEL_SCALE, 0, 0, 0), 3);
            Location feet = target.getLocation();
            double height = target.getHeight();
            Loop.repeat(ctx(), owner, 0, 1, 5, step -> {
                if (!Combat.isTargetable(ctx(), owner, target) || !seen(owner, target)) {
                    return false;
                }
                Location ring = target.getLocation().add(0, height * step / 4.0, 0);
                Vfx.ring(ctx(), ring, 0.45, 10, step * 0.4, Particle.DUST, SKY_DUST, false);
                return true;
            });
            Fx.sound(feet, Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, 1.9f + Fx.jitter(0.05f));
            Fx.sound(feet, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.6f + Fx.jitter(0.1f));
        }

        private String text() {
            if (!ctx().isAlive(target)) {
                return "";
            }
            double gauge = ctx().gauge(target);
            if (gauge >= params().num("scan_alert")) {
                return "<b><" + RED_TAG + ">측정 불가!!</" + RED_TAG + "></b>";
            }
            String color = gauge >= 50 ? GOLD_TAG : "#FFFFFF";
            return "<" + GOLD_TAG + ">전투력</" + GOLD_TAG + "> <b><" + color + ">" + number(power(target)) + "</"
                    + color + "></b>";
        }

        /** False once the reading should go: looked away too long, the target is gone or hidden. */
        boolean update(long now, long tick) {
            if (!label.isValid() || !Combat.isTargetable(ctx(), owner, target) || !seen(owner, target)
                    || !target.getWorld().equals(owner.getWorld())
                    || now - seen > Math.max(1, params().ticks("scan_duration"))) {
                return false;
            }
            Location at = labelPoint(target);
            if (now < shakeUntil) {
                at.add(Fx.jitter(0.12f), Fx.jitter(0.06f), Fx.jitter(0.12f));
                if (tick % 2 == 0) {
                    label.text(Text.mm("<b><" + RED_TAG + ">" + glitch() + "</" + RED_TAG + "></b>"));
                }
            } else if (tick % SCAN_PERIOD == 0) {
                label.text(Text.mm(text()));
            }
            Displays.moveTo(label, at);
            return true;
        }

        /** Over 90%: the digits glitch, shatter and the label turns red; the target is marked for the warrior's side. */
        void alert() {
            long now = ctx().currentTick();
            shakeUntil = now + SHAKE_TICKS;
            Location head = labelPoint(target);
            Fx.sound(head, Sound.BLOCK_GLASS_BREAK, 0.9f, 1.4f + Fx.jitter(0.1f));
            Fx.sound(head, Sound.ENTITY_ITEM_BREAK, 0.8f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(head, Sound.BLOCK_BEACON_DEACTIVATE, 0.7f, 1.8f);
            ctx().runLater(owner, SHAKE_TICKS, () -> {
                if (label.isValid() && Combat.isTargetable(ctx(), owner, target) && seen(owner, target)) {
                    shatter(labelPoint(target));
                    label.text(Text.mm(text()));
                    Displays.animate(label, Displays.centered(0, 0, 0, LABEL_SCALE * 1.25f, LABEL_SCALE * 1.25f,
                            LABEL_SCALE * 1.25f, 0, 0, 0), 3);
                }
            });
            owner.sendActionBar(Text.mm("<b><" + RED_TAG + ">⚠ 측정 불가!!</" + RED_TAG + "></b> <white><name></white> "
                    + "<gray>궁극기가 임박했습니다</gray>", Placeholder.component("name", target.name())));
            for (Player ally : ctx().alivePlayers()) {
                if (ctx().isAlly(owner, ally)) {
                    ally.sendActionBar(Text.mm("<b><" + RED_TAG + ">⚠ 측정 불가!!</" + RED_TAG + "></b> <white><name>"
                            + "</white><gray>의 궁극기가 임박했습니다</gray>", Placeholder.component("name",
                            target.name())));
                }
            }
            markers.removeIf(marker -> {
                if (marker.target.equals(target)) {
                    marker.remove();
                    return true;
                }
                return false;
            });
            if (reserve(1)) {
                markers.add(new Marker(owner, target, now + Math.max(1, params().ticks("scan_glow"))));
            }
        }

        /** Digits flying apart from the broken reading, and a puff of gold. */
        private void shatter(Location at) {
            Vfx.burst(ctx(), at, AURA, 0.5, Particle.CRIT, false);
            Fx.sound(at, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.8f);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < SHARDS && reserve(1); i++) {
                String digit = String.valueOf(random.nextInt(10));
                TextDisplay shard = keep(Displays.text(ctx(), owner, at, Text.mm("<b><" + GOLD_TAG + ">" + digit
                        + "</" + GOLD_TAG + "></b>"), 0.7f, 16));
                shard.setTeleportDuration(10);
                double angle = random.nextDouble(TAU);
                Location to = at.clone().add(Math.cos(angle) * 0.9, random.nextDouble(-0.6, 0.3),
                        Math.sin(angle) * 0.9);
                ctx().runLater(owner, 1, () -> Displays.moveTo(shard, to));
                ctx().runLater(owner, 8, () -> {
                    if (shard.isValid()) {
                        Displays.shrinkAway(ctx(), owner, shard, 6);
                    }
                });
            }
        }

        void drop() {
            if (reading == this) {
                reading = null;
            }
            if (label.isValid()) {
                Displays.shrinkAway(ctx(), owner, label, 3);
            }
        }
    }

    private static Location labelPoint(LivingEntity target) {
        return target.getLocation().add(0, target.getHeight() + LABEL_ABOVE, 0);
    }

    /**
     * The "cannot be measured" mark: a gold diamond over the enemy's head with an outline that shows through walls,
     * hidden from everyone but the warrior and its allies.
     */
    private final class Marker {

        private final Player owner;
        private final Player target;
        private final long until;
        private final BlockDisplay diamond;
        private final Set<UUID> hiddenFrom = new HashSet<>();

        Marker(Player owner, Player target, long until) {
            this.owner = owner;
            this.target = target;
            this.until = until;
            this.diamond = keep(Displays.block(ctx(), owner, markerPoint(target), MARKER.createBlockData(),
                    diamondPose(0, 0.02f), 0));
            diamond.setTeleportDuration(1);
            Displays.outline(diamond, GOLD);
            hideFromOthers();
            Displays.animateNextTick(ctx(), owner, diamond, diamondPose(45, 0.28f), 4);
        }

        /** Everyone but the warrior's side is kept from seeing the mark (checked again for late arrivals). */
        private void hideFromOthers() {
            for (Player viewer : owner.getWorld().getPlayers()) {
                if (!viewer.equals(owner) && !ctx().isAlly(owner, viewer) && hiddenFrom.add(viewer.getUniqueId())) {
                    viewer.hideEntity(ctx().plugin(), diamond);
                }
            }
        }

        boolean update(long now, long tick) {
            if (!diamond.isValid()) {
                return false;
            }
            if (now > until || !Combat.isTargetable(ctx(), owner, target) || !seen(owner, target)
                    || !target.getWorld().equals(owner.getWorld())) {
                remove();
                return false;
            }
            if (tick % 10 == 0) {
                hideFromOthers();
            }
            Displays.moveTo(diamond, markerPoint(target));
            if (tick % 4 == 0) {
                Displays.animate(diamond, diamondPose((float) (45 + tick * 9 % 360), 0.28f), 4);
            }
            return true;
        }

        void remove() {
            if (diamond.isValid()) {
                Displays.shrinkAway(ctx(), owner, diamond, 3);
            }
        }
    }

    private static Location markerPoint(LivingEntity target) {
        return target.getLocation().add(0, target.getHeight() + MARKER_ABOVE, 0);
    }

    /** A cube stood on its corner (a diamond), spun about the vertical. */
    private static Transformation diamondPose(float spin, float size) {
        return Displays.transform(0, 0, 0, size, size, size, spin, 45, 35.26f);
    }

    // ------------------------------------------------------------------ active: ki blast

    @Override
    public ActivationResult onActive() {
        if (gathering != null) {
            return ActivationResult.fail("모두의 기를 모으는 중입니다");
        }
        if (charge != null) {
            return ActivationResult.fail("이미 기를 모으고 있습니다");
        }
        Charge started = new Charge(player());
        charge = started;
        started.start();
        return ActivationResult.success();
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || charge == null) {
            return;
        }
        // Gathering ki leaves the warrior wide open.
        double extra = Math.max(0, params().num("charge_vulnerable")) / 100.0;
        event.setDamage(event.getDamage() * (1 + extra));
    }

    /**
     * Ki gathering between the hands: the ball grows, the aura swirls and stones lift off the ground until the warrior
     * crouches (a fresh crouch) or the charge is full; then it fires.
     */
    private final class Charge implements LongPredicate {

        private final Player owner;
        private final long started;
        private final long full;
        private final List<BlockDisplay> chips = new ArrayList<>();
        private final double[] chipAngles = new double[CHIP_COUNT];
        private @Nullable NamespacedKey slow;
        private @Nullable BlockDisplay core;
        private @Nullable BlockDisplay shell;
        private @Nullable TextDisplay power;
        private final int basePower;
        private boolean wasSneaking;
        private boolean done;

        Charge(Player owner) {
            this.owner = owner;
            this.started = ctx().currentTick();
            this.full = Math.max(1, params().ticks("charge_max"));
            this.wasSneaking = owner.isSneaking();
            this.basePower = power(owner);
        }

        void start() {
            double slowness = Math.clamp(params().num("charge_slow") / 100.0, 0.0, 1.0);
            if (slowness > 0) {
                slow = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -slowness,
                        Operation.MULTIPLY_SCALAR_1, full + 2);
            }
            Location hands = hands(owner);
            if (reserve(2)) {
                core = keep(Displays.block(ctx(), owner, hands, CORE.createBlockData(), Displays.scaled(0.02f),
                        full + 40));
                shell = keep(Displays.block(ctx(), owner, hands, SHELL.createBlockData(), Displays.scaled(0.02f),
                        full + 40));
                core.setTeleportDuration(1);
                shell.setTeleportDuration(1);
            }
            if (reserve(1)) {
                power = keep(Displays.text(ctx(), owner, labelPoint(owner), Text.mm(selfPower(0)), 0.1f, full + 20));
                power.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, power, Displays.centered(0, 0, 0, LABEL_SCALE, LABEL_SCALE,
                        LABEL_SCALE, 0, 0, 0), 3);
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location feet = owner.getLocation();
            for (int i = 0; i < CHIP_COUNT && reserve(1); i++) {
                chipAngles[i] = random.nextDouble(TAU);
                Location at = feet.clone().add(Math.cos(chipAngles[i]) * 1.1, 0.1, Math.sin(chipAngles[i]) * 1.1);
                Material type = CHIPS[random.nextInt(CHIPS.length)];
                BlockDisplay chip = keep(Displays.block(ctx(), owner, at, type.createBlockData(),
                        chipPose(random.nextFloat() * 360, 0.02f), full + 30));
                chip.setTeleportDuration(2);
                chips.add(chip);
                float size = 0.12f + random.nextFloat() * 0.1f;
                Displays.animateNextTick(ctx(), owner, chip, chipPose(random.nextFloat() * 360, size), 6);
            }
            Fx.sound(hands, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.3f + Fx.jitter(0.05f));
            Fx.sound(hands, Sound.BLOCK_BEACON_POWER_SELECT, 0.5f, 1.8f);
            owner.sendActionBar(Text.mm("<" + SKY_TAG + ">✦ 기를 모으는 중</" + SKY_TAG + "> <gray>웅크리면 발사</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, full, this, this::release);
        }

        private String selfPower(double fraction) {
            int shown = basePower + (int) Math.round(CHARGE_POWER * fraction);
            return "<" + GOLD_TAG + ">전투력</" + GOLD_TAG + "> <b><white>" + number(shown) + "</white></b> <"
                    + GOLD_TAG + ">▲</" + GOLD_TAG + ">";
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            boolean sneaking = owner.isSneaking();
            boolean pressed = sneaking && !wasSneaking;
            wasSneaking = sneaking;
            if (pressed) {
                return false;
            }
            long held = ctx().currentTick() - started;
            double fraction = Math.min(1, (double) held / full);
            Location hands = hands(owner);
            float size = (float) (0.12 + 0.45 * fraction);
            float spin = held * 23f;
            if (core != null && core.isValid()) {
                Displays.moveTo(core, hands);
                Displays.animate(core, Displays.transform(0, 0, 0, size * 0.6f, size * 0.6f, size * 0.6f, spin,
                        spin * 0.7f, 0), 1);
            }
            if (shell != null && shell.isValid()) {
                Displays.moveTo(shell, hands);
                Displays.animate(shell, Displays.transform(0, 0, 0, size, size, size, -spin * 0.8f, 30, spin * 0.5f),
                        1);
            }
            TextDisplay label = power;
            if (label != null && label.isValid()) {
                Displays.moveTo(label, labelPoint(owner));
                if (tick % 2 == 0) {
                    label.text(Text.mm(selfPower(fraction)));
                }
            }
            auraFx(owner, fraction, tick);
            liftChips(owner, held);
            Vfx.trail(Vfx.scatter(hands, 1.4).add(0, ThreadLocalRandom.current().nextDouble(-0.6, 0.6), 0), hands,
                    tick % 2 == 0 ? SKY : WHITE, 5, false);
            if (tick % 4 == 0) {
                Fx.sound(hands, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.6f, (float) (0.7 + fraction));
            }
            if (held + 1 == full) {
                Fx.sound(hands, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 1.4f);
                Vfx.dot(hands, Particle.FLASH, SKY, false);
            }
            return true;
        }

        /** Gold aura swirling up the body, brighter as the charge grows. */
        private void auraFx(Player owner, double fraction, long tick) {
            Location feet = owner.getLocation();
            double phase = tick * 0.45;
            int points = 2 + (int) Math.round(fraction * 3);
            for (int i = 0; i < points; i++) {
                double angle = phase + i * TAU / points;
                double height = (tick * 0.12 + i * 0.37) % 1.9;
                Location at = feet.clone().add(Math.cos(angle) * 0.55, height, Math.sin(angle) * 0.55);
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, AURA_GLOW, false);
            }
            if (tick % 3 == 0) {
                Vfx.dot(feet.clone().add(Fx.jitter(0.4f), 0.1, Fx.jitter(0.4f)), Particle.ELECTRIC_SPARK, null, false);
            }
        }

        private void liftChips(Player owner, long held) {
            Location feet = owner.getLocation();
            for (int i = 0; i < chips.size(); i++) {
                BlockDisplay chip = chips.get(i);
                if (!chip.isValid()) {
                    continue;
                }
                double angle = chipAngles[i] + held * 0.05;
                double rise = Math.min(1.4, held * 0.05) + Math.sin(held * 0.3 + i) * 0.06;
                Displays.moveTo(chip, feet.clone().add(Math.cos(angle) * 1.1, 0.1 + rise, Math.sin(angle) * 1.1));
            }
        }

        /** Fires what was gathered: a burst of quick shots, or one blast as big as the charge. */
        void release() {
            if (done) {
                return;
            }
            done = true;
            if (charge == this) {
                charge = null;
            }
            if (slow != null) {
                ctx().removeModifier(owner, slow);
                slow = null;
            }
            long held = Math.max(1, ctx().currentTick() - started);
            fade();
            if (!ctx().isAlive(owner)) {
                return;
            }
            long quick = params().ticks("quick_time");
            if (held <= quick) {
                quickShots(owner);
                return;
            }
            double span = Math.max(1, full - quick);
            double f = Math.clamp((held - quick) / span, 0.0, 1.0);
            double damage = lerp(params().num("small_damage") * 2, params().num("big_damage"), f);
            double radius = lerp(MIN_BLAST_RADIUS, Math.max(MIN_BLAST_RADIUS, params().num("big_radius")), f);
            double range = lerp(params().num("small_range"), params().num("big_range"), f);
            double speed = lerp(MID_SPEED, BIG_SPEED, f);
            float size = (float) lerp(MIN_SIZE, BIG_SIZE, f);
            double breaks = params().num("big_break_radius") * f;
            Vector direction = aim(owner);
            new Blast(owner, muzzle(owner, direction), direction, speed, Math.max(1, range), damage, radius,
                    breaks >= MIN_BREAK ? breaks : 0, size, false).launch();
            Location hands = hands(owner);
            Fx.sound(hands, Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, (float) (1.3 - 0.5 * f));
            Fx.sound(hands, Sound.ITEM_TRIDENT_RIPTIDE_3, (float) (0.5 + 0.5 * f), 1.5f);
            Fx.sound(hands, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, (float) (1.6 - 0.5 * f));
            Vfx.dot(hands, Particle.FLASH, SKY, false);
            owner.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, owner.getLocation().add(0, 0.1, 0), 1, 0, 0,
                    0, 0, null, false);
            owner.sendActionBar(Text.mm("<" + SKY_TAG + ">✦ 기탄!</" + SKY_TAG + "> <gray>" + Math.round(f * 100)
                    + "% 모음</gray>"));
        }

        /** A quick release: the ball splits into a rattle of small shots, each aimed where the warrior looks then. */
        private void quickShots(Player owner) {
            int count = Math.max(1, params().integer("small_count"));
            double damage = params().num("small_damage");
            double range = Math.max(1, params().num("small_range"));
            Loop.repeat(ctx(), owner, 0, SMALL_SPACING, count, shot -> {
                Vector direction = aim(owner);
                ThreadLocalRandom random = ThreadLocalRandom.current();
                if (shot > 0) {
                    direction.add(new Vector(random.nextDouble(-SMALL_SPREAD, SMALL_SPREAD),
                            random.nextDouble(-SMALL_SPREAD, SMALL_SPREAD) * 0.5,
                            random.nextDouble(-SMALL_SPREAD, SMALL_SPREAD))).normalize();
                }
                new Blast(owner, muzzle(owner, direction), direction, SMALL_SPEED, range, damage, 0, 0, SMALL_SIZE,
                        true).launch();
                Location hands = hands(owner);
                Fx.sound(hands, Sound.ENTITY_BREEZE_SHOOT, 0.7f, 1.7f + Fx.jitter(0.1f));
                Fx.sound(hands, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.8f + Fx.jitter(0.1f));
                Vfx.dot(hands, Particle.FLASH, SKY, false);
                return true;
            });
            owner.sendActionBar(Text.mm("<" + SKY_TAG + ">✦ 연속 기탄!</" + SKY_TAG + ">"));
        }

        private void fade() {
            for (Display display : new Display[] {core, shell, power}) {
                if (display != null && display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 3);
                }
            }
            for (BlockDisplay chip : chips) {
                if (chip.isValid()) {
                    Displays.moveTo(chip, chip.getLocation().subtract(0, 0.6, 0));
                    Displays.shrinkAway(ctx(), owner, chip, 6);
                }
            }
        }
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    /** Where the warrior looks (a unit vector; straight ahead if the look is undefined). */
    private static Vector aim(Player owner) {
        Vector direction = owner.getEyeLocation().getDirection();
        if (!Double.isFinite(direction.lengthSquared()) || direction.lengthSquared() < 1.0E-6) {
            return Motion.horizontalFacing(owner.getLocation());
        }
        return direction.normalize();
    }

    /** Between the hands: chest height, half a block ahead. */
    private static Location hands(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector forward = Motion.horizontalFacing(eye);
        return eye.add(forward.multiply(0.55)).subtract(0, 0.45, 0);
    }

    /** Where a blast leaves the hands: never on the far side of a wall the warrior is hugging. */
    private static Location muzzle(Player owner, Vector direction) {
        Location eye = owner.getEyeLocation();
        Location out = eye.clone().subtract(0, 0.3, 0).add(direction.clone().multiply(0.6));
        return Spots.clearPath(eye, out) ? out : eye;
    }

    /** A small chip of stone, tumbled. */
    private static Transformation chipPose(float spin, float size) {
        return Displays.transform(0, 0, 0, size, size * 0.8f, size, spin, spin * 0.5f, 20);
    }

    /**
     * One ki blast flying straight: blocks stop it; a small one hurts the first enemy it meets, the others burst there
     * (and at the end of their range) over an area.
     */
    private final class Blast implements LongPredicate {

        private final Player owner;
        private final Vector direction;
        private final double speed;
        private final double range;
        private final double damage;
        private final double radius;
        private final double breakRadius;
        private final float size;
        private final boolean small;
        private final double hitRadius;
        private Location position;
        private double travelled;
        private @Nullable BlockDisplay core;
        private @Nullable BlockDisplay shell;
        private boolean done;

        Blast(Player owner, Location from, Vector direction, double speed, double range, double damage, double radius,
              double breakRadius, float size, boolean small) {
            this.owner = owner;
            this.position = from.clone();
            this.direction = direction.clone();
            this.speed = speed;
            this.range = range;
            this.damage = damage;
            this.radius = radius;
            this.breakRadius = breakRadius;
            this.size = size;
            this.small = small;
            this.hitRadius = small ? SMALL_HIT : Math.max(SMALL_HIT, size * 0.55);
        }

        void launch() {
            long life = (long) Math.ceil(range / speed) + 6;
            if (reserve(2)) {
                core = keep(Displays.block(ctx(), owner, position, CORE.createBlockData(),
                        Displays.scaled(size * 0.55f), life));
                shell = keep(Displays.block(ctx(), owner, position, SHELL.createBlockData(), Displays.scaled(size),
                        life));
                core.setTeleportDuration(1);
                shell.setTeleportDuration(1);
            }
            Loop.repeat(ctx(), owner, 1, 1, (long) Math.ceil(range / speed) + 1, this, this::end);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            double step = Math.min(speed, range - travelled);
            if (step <= 1.0E-3) {
                return false;
            }
            World world = position.getWorld();
            LivingEntity struck = firstEnemy(step);
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step + hitRadius * 0.5,
                    FluidCollisionMode.NEVER, true);
            double wallAt = wall == null ? Double.POSITIVE_INFINITY
                    : wall.getHitPosition().distance(position.toVector());
            if (struck != null && distanceAlong(struck) <= wallAt) {
                Location at = position.clone().add(direction.clone().multiply(Math.max(0, distanceAlong(struck))));
                impact(at, struck);
                return false;
            }
            if (wall != null) {
                Location at = wall.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.25));
                impact(at, null);
                return false;
            }
            Location next = position.clone().add(direction.clone().multiply(step));
            trail(position, next, tick);
            position = next;
            travelled += step;
            float spin = tick * 37f;
            if (core != null && core.isValid()) {
                Displays.moveTo(core, position);
                Displays.animate(core, Displays.transform(0, 0, 0, size * 0.55f, size * 0.55f, size * 0.55f, spin,
                        spin, 0), 1);
            }
            if (shell != null && shell.isValid()) {
                Displays.moveTo(shell, position);
                Displays.animate(shell, Displays.transform(0, 0, 0, size, size, size, -spin, 25, spin * 0.5f), 1);
            }
            return travelled < range - 1.0E-3;
        }

        private double distanceAlong(LivingEntity enemy) {
            RayTraceResult hit = enemy.getBoundingBox().expand(hitRadius).rayTrace(position.toVector(), direction,
                    speed + hitRadius);
            return hit == null ? 0 : hit.getHitPosition().distance(position.toVector());
        }

        /** The nearest enemy whose (slightly enlarged) body the blast passes through this step. */
        private @Nullable LivingEntity firstEnemy(double step) {
            Location middle = position.clone().add(direction.clone().multiply(step / 2));
            LivingEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, step / 2 + hitRadius + 2)) {
                BoundingBox box = enemy.getBoundingBox().expand(hitRadius);
                RayTraceResult hit = box.rayTrace(position.toVector(), direction, step);
                double distance = hit != null ? hit.getHitPosition().distance(position.toVector())
                        : box.contains(position.toVector()) ? 0 : Double.NaN;
                if (!Double.isNaN(distance) && distance < bestDistance) {
                    best = enemy;
                    bestDistance = distance;
                }
            }
            return best;
        }

        private void trail(Location from, Location to, long tick) {
            Vfx.line(ctx(), from, to, small ? 2 : 3, Particle.DUST_COLOR_TRANSITION, KI_GLOW, false);
            Vfx.dot(to, Particle.END_ROD, null, false);
            if (!small) {
                Vfx.cloud(ctx(), to, Particle.ELECTRIC_SPARK, 2, size * 0.4, 0.02, null, false);
                if (tick % 2 == 0) {
                    Vfx.ring(ctx(), to, size * 0.75, 8, tick * 0.5, Particle.DUST, GOLD_DUST, false);
                }
                if (tick % 5 == 0) {
                    Fx.sound(to, Sound.BLOCK_BEACON_AMBIENT, 0.6f, 1.8f);
                }
            }
        }

        /** It reached the end of its range in the open: a big blast bursts there, a small one fizzles out. */
        void end() {
            if (done) {
                return;
            }
            impact(position, null);
        }

        private void impact(Location at, @Nullable LivingEntity struck) {
            if (done) {
                return;
            }
            done = true;
            for (BlockDisplay display : new BlockDisplay[] {core, shell}) {
                if (display != null && display.isValid()) {
                    Displays.moveTo(display, at);
                    Displays.shrinkAway(ctx(), owner, display, 2);
                }
            }
            if (small) {
                smallHit(at, struck);
            } else {
                explode(at);
            }
        }

        private void smallHit(Location at, @Nullable LivingEntity struck) {
            if (struck != null && Combat.isTargetable(ctx(), owner, struck)) {
                Combat.hit(ctx(), owner, struck, damage);
                if (Combat.isTargetable(ctx(), owner, struck)) {
                    Motion.push(struck, Motion.horizontal(direction).multiply(SMALL_PUSH).setY(0.1), MAX_SPEED);
                }
            }
            if (struck == null || seen(owner, struck)) {
                Vfx.burst(ctx(), at, KI, 0.3, Particle.ELECTRIC_SPARK, false);
            }
            Fx.sound(at, Sound.ENTITY_BREEZE_WIND_BURST, 0.5f, 1.8f + Fx.jitter(0.1f));
            if (struck != null) {
                Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.7f, 1.4f + Fx.jitter(0.1f));
            }
        }

        /** The burst: every enemy in the radius with nothing solid in between is hurt and blown away. */
        private void explode(Location at) {
            int hits = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, at, radius)) {
                if (!Spots.clearPath(at, Fx.body(enemy)) && !Spots.clearPath(at, enemy.getEyeLocation())) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(at.toVector()));
                    if (away.lengthSquared() < 1.0E-6) {
                        away = Motion.horizontal(direction);
                    }
                    Motion.push(enemy, away.multiply(PUSH * (0.5 + radius / 5)).setY(PUSH_LIFT), MAX_SPEED);
                }
            }
            blastFx(owner, at, radius);
            if (breakRadius > 0) {
                ctx().breakBlocks(owner, at, breakRadius);
            }
            if (hits > 0) {
                owner.sendActionBar(Text.mm("<" + SKY_TAG + ">✦ 기탄 명중</" + SKY_TAG + "> <white>" + hits
                        + "명</white>"));
            }
        }
    }

    /** A ki burst: flash, sky-blue body, gold sparks, a ring rolling out over the ground and a deep thump. */
    private void blastFx(Player owner, Location at, double radius) {
        World world = at.getWorld();
        world.spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, WHITE, true);
        Vfx.burst(ctx(), at, KI, radius * 0.6, Particle.END_ROD, true);
        Vfx.sphere(ctx(), at, radius * 0.7, (int) Math.round(18 * radius), Particle.DUST_COLOR_TRANSITION, KI_GLOW,
                false);
        Vfx.cloud(ctx(), at, Particle.ELECTRIC_SPARK, 14, radius * 0.4, 0.15, null, false);
        Location floor = at.clone();
        Spots.snapToGround(world, floor, 3);
        Vfx.shockwave(ctx(), owner, floor.add(0, 0.1, 0), 0.3, radius + 0.5, 6, Particle.DUST_COLOR_TRANSITION,
                AURA_GLOW, false);
        Block below = floor.clone().subtract(0, 0.3, 0).getBlock();
        if (!below.isPassable()) {
            world.spawnParticle(Particle.BLOCK, floor, Vfx.n(ctx(), 16), radius * 0.4, 0.1, radius * 0.4, 0,
                    below.getBlockData());
        }
        Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 1.4f + Fx.jitter(0.08f));
        Fx.sound(at, Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.2f);
    }

    // ------------------------------------------------------------------ ultimate: everyone's ki

    /**
     * Self-contained (owner-scoped tasks and modifiers, the blows taken are read off the health), so a Gambler can
     * borrow it.
     */
    @Override
    public ActivationResult onUltimate() {
        if (gathering != null) {
            return ActivationResult.fail("이미 기를 모으고 있습니다");
        }
        Charge running = charge;
        if (running != null) {
            running.release(); // the ki in the hands goes first
        }
        Gathering started = new Gathering(player());
        gathering = started;
        started.begin();
        return ActivationResult.success();
    }

    /**
     * The giant ball gathering over the warrior's head: it stands rooted, allies who crouch to raise their hands and
     * the blows it takes make the ball bigger; then it is thrown.
     */
    private final class Gathering implements LongPredicate {

        private final Player owner;
        private final long duration;
        private final List<NamespacedKey> roots = new ArrayList<>();
        private final Set<UUID> helpers = new HashSet<>();
        private final List<BlockDisplay> chips = new ArrayList<>();
        private final double[] chipAngles = new double[GATHER_CHIPS];
        private @Nullable BlockDisplay core;
        private @Nullable BlockDisplay shell;
        private @Nullable BlockDisplay outer;
        private @Nullable TextDisplay power;
        private double lastHealth;
        private double taken;
        private int allyBonus;
        private int hurtBonus;
        private long tick;

        Gathering(Player owner) {
            this.owner = owner;
            this.duration = Math.max(1, params().ticks("gather_time"));
            this.lastHealth = owner.getHealth() + owner.getAbsorptionAmount();
        }

        int bonus() {
            return Math.min(Math.max(0, params().integer("bonus_max")), allyBonus + hurtBonus);
        }

        void begin() {
            roots.add(ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1,
                    duration + 1));
            roots.add(ctx().addModifier(owner, owner, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1,
                    duration + 1));
            Location at = orbPoint(owner, 0);
            long life = duration + (long) Math.ceil(params().num("throw_range") * 20 / Math.max(0.5,
                    params().num("throw_speed"))) + 20;
            if (reserve(3)) {
                core = keep(Displays.block(ctx(), owner, at, CORE.createBlockData(), Displays.scaled(0.05f), life));
                shell = keep(Displays.block(ctx(), owner, at, SHELL.createBlockData(), Displays.scaled(0.05f), life));
                outer = keep(Displays.block(ctx(), owner, at, OUTER_SHELL.createBlockData(), Displays.scaled(0.05f),
                        life));
                for (BlockDisplay part : new BlockDisplay[] {core, shell, outer}) {
                    part.setTeleportDuration(1);
                }
                Displays.outline(outer, SKY);
            }
            if (reserve(1)) {
                power = keep(Displays.text(ctx(), owner, labelPoint(owner), Text.mm(selfPower(0)), 0.1f,
                        duration + 10));
                power.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, power, Displays.centered(0, 0, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0),
                        3);
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location feet = owner.getLocation();
            for (int i = 0; i < GATHER_CHIPS && reserve(1); i++) {
                chipAngles[i] = random.nextDouble(TAU);
                Material type = CHIPS[random.nextInt(CHIPS.length)];
                Location spot = feet.clone().add(Math.cos(chipAngles[i]) * 1.8, 0.1, Math.sin(chipAngles[i]) * 1.8);
                BlockDisplay chip = keep(Displays.block(ctx(), owner, spot, type.createBlockData(),
                        chipPose(random.nextFloat() * 360, 0.02f), duration + 20));
                chip.setTeleportDuration(2);
                chips.add(chip);
                Displays.animateNextTick(ctx(), owner, chip, chipPose(random.nextFloat() * 360,
                        0.18f + random.nextFloat() * 0.14f), 8);
            }
            Fx.sound(feet, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2f, 0.7f);
            Fx.sound(feet, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.2f);
            Fx.sound(feet, Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 0.8f);
            Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.5, 4, 8, Particle.DUST_COLOR_TRANSITION,
                    AURA_GLOW, true);
            owner.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">✦ 모두의 기</" + GOLD_TAG + "></b> <gray>아군이 웅크리면 "
                    + "구슬이 커집니다</gray>"));
            callAllies();
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::release);
        }

        /** Allies in range who have not helped yet are asked to raise their hands. */
        private void callAllies() {
            double range = Math.max(1, params().num("ally_range"));
            for (Player ally : ctx().alliesNear(owner, owner.getLocation(), range)) {
                if (!helpers.contains(ally.getUniqueId())) {
                    ally.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">✦ <owner>의 모두의 기!</" + GOLD_TAG + "></b> "
                            + "<white>웅크려 손을 들면 기를 보탭니다</white>", Placeholder.component("owner",
                            owner.name())));
                }
            }
        }

        private String selfPower(double progress) {
            int shown = 10_000 + (int) Math.round(progress * 6000) + bonus() * 2500;
            return "<" + GOLD_TAG + ">전투력</" + GOLD_TAG + "> <b><white>" + number(shown) + "</white></b> <"
                    + GOLD_TAG + ">▲▲</" + GOLD_TAG + ">";
        }

        @Override
        public boolean test(long run) {
            tick = run;
            double progress = Math.min(1, (run + 1.0) / duration);
            int before = bonus();
            helpers(run);
            blows();
            if (bonus() > before) {
                swell();
            }
            Location orb = orbPoint(owner, bonus());
            float size = orbSize(bonus()) * (float) (0.25 + 0.75 * progress);
            pose(orb, size, run);
            TextDisplay label = power;
            if (label != null && label.isValid()) {
                Displays.moveTo(label, labelPoint(owner));
                if (run % 2 == 0) {
                    label.text(Text.mm(selfPower(progress)));
                }
            }
            gatherFx(owner, orb, size, run);
            if (run % ALLY_CALL_PERIOD == 0) {
                callAllies();
            }
            return true;
        }

        /** Allies in range who see the warrior and crouch lend their ki (each once). */
        private void helpers(long run) {
            double range = Math.max(1, params().num("ally_range"));
            int max = Math.max(0, params().integer("ally_bonus_max"));
            Location orb = orbPoint(owner, bonus());
            for (Player ally : ctx().alliesNear(owner, owner.getLocation(), range)) {
                boolean helping = helpers.contains(ally.getUniqueId());
                if (!helping && allyBonus < max && ally.isSneaking() && ally.hasLineOfSight(owner)) {
                    helpers.add(ally.getUniqueId());
                    allyBonus++;
                    helping = true;
                    ally.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">✦ 기를 보탰습니다!</" + GOLD_TAG + "></b>"));
                    Fx.sound(ally.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.6f);
                }
                if (helping && run % 2 == 0) {
                    Location hands = ally.getEyeLocation().add(0, 0.5, 0);
                    Vfx.trail(hands, orb, run % 4 == 0 ? GOLD : SKY, 8, true);
                    Vfx.dot(hands, Particle.END_ROD, null, false);
                }
            }
        }

        /** Every blow taken while standing there is read off the health (and absorption) and feeds the ball. */
        private void blows() {
            double now = owner.getHealth() + owner.getAbsorptionAmount();
            if (now < lastHealth - 1.0E-9) {
                taken += lastHealth - now;
            }
            lastHealth = now;
            double per = params().num("hurt_per_bonus");
            int max = Math.max(0, params().integer("hurt_bonus_max"));
            hurtBonus = per <= 1.0E-6 ? 0 : (int) Math.min(max, Math.floor(taken / per + 1.0E-9));
        }

        /** The ball grows a size: a pulse of light and a deep hum. */
        private void swell() {
            Location orb = orbPoint(owner, bonus());
            Vfx.sphere(ctx(), orb, orbSize(bonus()) * 0.6, 40, Particle.DUST_COLOR_TRANSITION, KI_GLOW, true);
            Vfx.dot(orb, Particle.FLASH, SKY, true);
            Fx.sound(orb, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2f, 0.9f + 0.15f * bonus());
            Fx.sound(orb, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.0f + 0.1f * bonus());
            owner.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">✦ 기가 커집니다</" + GOLD_TAG + "></b> <white>+"
                    + bonus() + "</white>"));
        }

        private void pose(Location orb, float size, long run) {
            float spin = run * 11f;
            if (core != null && core.isValid()) {
                Displays.moveTo(core, orb);
                Displays.animate(core, Displays.transform(0, 0, 0, size * 0.5f, size * 0.5f, size * 0.5f, spin,
                        spin * 0.6f, 0), 1);
            }
            if (shell != null && shell.isValid()) {
                Displays.moveTo(shell, orb);
                Displays.animate(shell, Displays.transform(0, 0, 0, size * 0.8f, size * 0.8f, size * 0.8f, -spin,
                        30, spin * 0.4f), 1);
            }
            if (outer != null && outer.isValid()) {
                Displays.moveTo(outer, orb);
                Displays.animate(outer, Displays.transform(0, 0, 0, size, size, size, spin * 0.5f, 45, 35), 1);
            }
        }

        /** Light streaming in from all around, the aura and the stones lifting, sparks crackling. */
        private void gatherFx(Player owner, Location orb, float size, long run) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                double angle = random.nextDouble(TAU);
                double y = random.nextDouble(-0.5, 1.0);
                double r = Math.sqrt(Math.max(0, 1 - y * y)) * 6;
                Location from = orb.clone().add(Math.cos(angle) * r, y * 6, Math.sin(angle) * r);
                Vfx.trail(from, orb, i % 2 == 0 ? SKY : WHITE, 10, true);
            }
            Location feet = owner.getLocation();
            for (int i = 0; i < 3; i++) {
                double angle = run * 0.4 + i * TAU / 3;
                double height = (run * 0.15 + i * 0.6) % 2.4;
                Vfx.dot(feet.clone().add(Math.cos(angle) * 0.6, height, Math.sin(angle) * 0.6),
                        Particle.DUST_COLOR_TRANSITION, AURA_GLOW, false);
            }
            if (run % 3 == 0) {
                Vfx.cloud(ctx(), orb, Particle.ELECTRIC_SPARK, 4, size * 0.5, 0.05, null, true);
                Vfx.line(ctx(), owner.getEyeLocation().add(0, 0.4, 0), orb, 2, Particle.DUST, GOLD_DUST, false);
            }
            for (int i = 0; i < chips.size(); i++) {
                BlockDisplay chip = chips.get(i);
                if (chip.isValid()) {
                    double angle = chipAngles[i] + run * 0.07;
                    double rise = Math.min(2.6, run * 0.05) + Math.sin(run * 0.25 + i) * 0.1;
                    Displays.moveTo(chip, feet.clone().add(Math.cos(angle) * 1.8, 0.1 + rise,
                            Math.sin(angle) * 1.8));
                }
            }
            if (run % 5 == 0) {
                Fx.sound(orb, Sound.BLOCK_BEACON_AMBIENT, 1.2f, 0.6f + 0.4f * (float) run / duration);
            }
            if (run % 10 == 0) {
                Fx.sound(feet, Sound.ENTITY_WARDEN_HEARTBEAT, 0.8f, 1.0f + 0.2f * (float) run / duration);
            }
        }

        /** Thrown: the ball sets off slowly where the warrior looks (never steeply up into the sky). */
        void release() {
            if (gathering == this) {
                gathering = null;
            }
            for (NamespacedKey key : roots) {
                ctx().removeModifier(owner, key);
            }
            roots.clear();
            TextDisplay label = power;
            if (label != null && label.isValid()) {
                Displays.shrinkAway(ctx(), owner, label, 4);
            }
            for (BlockDisplay chip : chips) {
                if (chip.isValid()) {
                    Displays.shrinkAway(ctx(), owner, chip, 6);
                }
            }
            if (!ctx().isAlive(owner)) {
                return;
            }
            int bonus = bonus();
            Location orb = orbPoint(owner, bonus);
            Vector direction = toward(owner, orb);
            if (direction.getY() > MAX_UPWARD) {
                Vector flat = Motion.horizontal(direction);
                if (flat.lengthSquared() < 1.0E-6) {
                    flat = Motion.horizontalFacing(owner.getLocation());
                }
                direction = flat.multiply(Math.sqrt(1 - MAX_UPWARD * MAX_UPWARD)).setY(MAX_UPWARD).normalize();
            }
            Fx.sound(orb, Sound.ENTITY_BREEZE_WIND_BURST, 1.4f, 0.6f);
            Fx.sound(orb, Sound.ITEM_TRIDENT_RIPTIDE_3, 1.2f, 0.7f);
            Fx.sound(orb, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 0.8f);
            owner.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">✦ 받아라, 모두의 기!</" + GOLD_TAG + "></b>"
                    + (bonus > 0 ? " <white>+" + bonus + "</white>" : "")));
            new Comet(owner, orb, direction, bonus, core, shell, outer, tick).launch();
        }
    }

    /**
     * The way from the ball over the head to what the warrior looks at: the aimed enemy, else the first block on its
     * line of sight, else the end of the throw.
     */
    private Vector toward(Player owner, Location orb) {
        Location eye = owner.getEyeLocation();
        Vector look = aim(owner);
        double range = Math.max(1, params().num("throw_range"));
        LivingEntity aimed = ctx().findTarget(owner, range);
        Location point;
        if (aimed != null) {
            point = Fx.body(aimed);
        } else {
            RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, look, range, FluidCollisionMode.NEVER, true);
            point = hit != null ? hit.getHitPosition().toLocation(eye.getWorld())
                    : eye.clone().add(look.clone().multiply(range));
        }
        Vector way = point.toVector().subtract(orb.toVector());
        return way.lengthSquared() < 1.0E-4 ? look : way.normalize();
    }

    /** Over the warrior's raised hands: higher as the ball grows, so it never sits on the head. */
    private static Location orbPoint(Player owner, int bonus) {
        return owner.getEyeLocation().add(0, ORB_ABOVE + orbSize(bonus) * 0.5, 0);
    }

    private static float orbSize(int bonus) {
        return ORB_BASE + ORB_PER_BONUS * bonus;
    }

    /**
     * The thrown ball: it sails slowly in a straight line (its shadow marks where it will come down) and bursts on the
     * first ground, wall or enemy it touches, or at the end of its range.
     */
    private final class Comet implements LongPredicate {

        private final Player owner;
        private final Vector direction;
        private final double speed;
        private final double range;
        private final double radius;
        private final double damage;
        private final float size;
        private final @Nullable BlockDisplay core;
        private final @Nullable BlockDisplay shell;
        private final @Nullable BlockDisplay outer;
        private final long spinBase;
        private Location position;
        private double travelled;
        private boolean done;

        Comet(Player owner, Location from, Vector direction, int bonus, @Nullable BlockDisplay core,
              @Nullable BlockDisplay shell, @Nullable BlockDisplay outer, long spinBase) {
            this.owner = owner;
            this.position = from.clone();
            this.direction = direction;
            this.speed = Math.max(0.5, params().num("throw_speed")) / 20.0;
            this.range = Math.max(1, params().num("throw_range"));
            this.radius = Math.max(0.5, params().num("ult_radius") + params().num("bonus_radius") * bonus);
            this.damage = params().num("ult_damage") + params().num("bonus_damage") * bonus;
            this.size = orbSize(bonus);
            this.core = core;
            this.shell = shell;
            this.outer = outer;
            this.spinBase = spinBase;
        }

        void launch() {
            Loop.repeat(ctx(), owner, 1, 1, (long) Math.ceil(range / speed) + 1, this, this::end);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            double step = Math.min(speed, range - travelled);
            World world = position.getWorld();
            double reach = size * 0.5;
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step + reach * 0.8,
                    FluidCollisionMode.NEVER, true);
            if (wall != null) {
                explode(wall.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.3)));
                return false;
            }
            // The ball touches the ground below it (the ground may rise to meet it).
            RayTraceResult ground = world.rayTraceBlocks(position, new Vector(0, -1, 0), reach * 0.8,
                    FluidCollisionMode.NEVER, true);
            if (ground != null) {
                explode(ground.getHitPosition().toLocation(world).add(0, 0.2, 0));
                return false;
            }
            Location next = position.clone().add(direction.clone().multiply(step));
            if (!ctx().enemiesNear(owner, next, reach).isEmpty()) {
                explode(next);
                return false;
            }
            Vfx.line(ctx(), position, next, 2, Particle.DUST_COLOR_TRANSITION, KI_GLOW, true);
            position = next;
            travelled += step;
            long spin = spinBase + tick;
            float turn = spin * 11f;
            if (core != null && core.isValid()) {
                Displays.moveTo(core, position);
                Displays.animate(core, Displays.transform(0, 0, 0, size * 0.5f, size * 0.5f, size * 0.5f, turn,
                        turn * 0.6f, 0), 1);
            }
            if (shell != null && shell.isValid()) {
                Displays.moveTo(shell, position);
                Displays.animate(shell, Displays.transform(0, 0, 0, size * 0.8f, size * 0.8f, size * 0.8f, -turn,
                        30, turn * 0.4f), 1);
            }
            if (outer != null && outer.isValid()) {
                Displays.moveTo(outer, position);
                Displays.animate(outer, Displays.transform(0, 0, 0, size, size, size, turn * 0.5f, 45, 35), 1);
            }
            flightFx(tick);
            return travelled < range - 1.0E-3;
        }

        /** The trail, and the shadow on the ground where the ball is going to come down. */
        private void flightFx(long tick) {
            Vfx.cloud(ctx(), position, Particle.END_ROD, 2, size * 0.35, 0.02, null, true);
            Vfx.dot(position.clone().subtract(direction.clone().multiply(size * 0.6)), Particle.CLOUD, null, false);
            if (tick % 2 == 0) {
                Location landing = landing();
                Location shadow = landing.clone().add(0, 0.05, 0);
                Spots.snapToGround(shadow.getWorld(), shadow, 6);
                shadow.add(0, 0.08, 0);
                Vfx.ring(ctx(), shadow, radius, (int) Math.round(radius * 9), tick * 0.1, Particle.DUST, SHADOW_DUST,
                        true);
                Vfx.ring(ctx(), shadow, radius * 0.5, (int) Math.round(radius * 5), -tick * 0.1, Particle.DUST,
                        SHADOW_DUST, true);
                Vfx.dot(shadow, Particle.SMOKE, null, true);
            }
            if (tick % 8 == 0) {
                Fx.sound(position, Sound.BLOCK_BEACON_AMBIENT, 1.4f, 0.7f);
            }
        }

        /** Where the ball would burst if nothing stepped into its way: the first block on its line, or its end. */
        private Location landing() {
            double left = Math.max(0.1, range - travelled);
            RayTraceResult hit = position.getWorld().rayTraceBlocks(position, direction, left,
                    FluidCollisionMode.NEVER, true);
            return hit != null ? hit.getHitPosition().toLocation(position.getWorld())
                    : position.clone().add(direction.clone().multiply(left));
        }

        void end() {
            if (!done) {
                explode(position);
            }
        }

        /** Everyone's ki bursts: enemies in the radius (not behind walls) are hurt and thrown up. */
        private void explode(Location at) {
            if (done) {
                return;
            }
            done = true;
            for (BlockDisplay display : new BlockDisplay[] {core, shell, outer}) {
                if (display != null && display.isValid()) {
                    Displays.moveTo(display, at);
                    Displays.animate(display, Displays.scaled(size * 1.6f), 3);
                    ctx().runLater(owner, 3, () -> {
                        if (display.isValid()) {
                            Displays.shrinkAway(ctx(), owner, display, 4);
                        }
                    });
                }
            }
            int hits = 0;
            double lift = Motion.launchSpeed(Math.max(0, params().num("ult_launch")));
            for (LivingEntity enemy : ctx().enemiesNear(owner, at, radius)) {
                if (!Spots.clearPath(at, Fx.body(enemy)) && !Spots.clearPath(at, enemy.getEyeLocation())) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(at.toVector()));
                    Motion.push(enemy, away.multiply(LAUNCH_AWAY).setY(lift), MAX_SPEED);
                }
            }
            impactFx(at);
            double breaks = params().num("ult_break_radius");
            if (breaks > 0) {
                ctx().breakBlocks(owner, at, breaks);
            }
            owner.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">✦ 모두의 기 작렬!</" + GOLD_TAG + "></b> "
                    + (hits > 0 ? "<white>" + hits + "명 적중</white>" : "<gray>아무도 없었다…</gray>")));
        }

        private void impactFx(Location at) {
            World world = at.getWorld();
            world.spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, WHITE, true);
            world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1, 0, 0, 0, 0, null, true);
            Vfx.burst(ctx(), at, KI, radius * 0.7, Particle.END_ROD, true);
            Vfx.sphere(ctx(), at, radius * 0.8, 70, Particle.DUST_COLOR_TRANSITION, KI_GLOW, true);
            Vfx.cloud(ctx(), at, Particle.ELECTRIC_SPARK, 30, radius * 0.5, 0.25, null, true);
            Location floor = at.clone();
            Spots.snapToGround(world, floor, 4);
            floor.add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, floor, 0.5, radius + 1.5, 8, Particle.DUST_COLOR_TRANSITION, AURA_GLOW, true);
            Vfx.shockwave(ctx(), owner, floor.clone().add(0, 0.3, 0), 0.3, radius + 0.5, 10, Particle.DUST, SKY_DUST,
                    true);
            Block ground = floor.clone().subtract(0, 0.4, 0).getBlock();
            if (!ground.isPassable()) {
                world.spawnParticle(Particle.DUST_PILLAR, floor, Vfx.n(ctx(), 24), radius * 0.5, 0.2, radius * 0.5, 0,
                        ground.getBlockData(), true);
            }
            Vfx.cloud(ctx(), floor, Particle.DUST, 20, radius * 0.5, 0, STONE_DUST, false);
            Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.7f);
            Fx.sound(at, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.3f);
            Fx.sound(at, Sound.ITEM_TRIDENT_THUNDER, 0.8f, 1.4f);
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.6f);
            // Embers of ki drift down for a moment.
            Location glow = at.clone();
            Loop.repeat(ctx(), owner, 4, 2, 8, step -> {
                Vfx.cloud(ctx(), glow, Particle.END_ROD, 3, radius * 0.6, 0.01, null, false);
                Vfx.cloud(ctx(), glow, Particle.DUST_COLOR_TRANSITION, 3, radius * 0.6, 0, AURA_GLOW, false);
                return true;
            });
        }
    }

    // ------------------------------------------------------------------ helpers

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
