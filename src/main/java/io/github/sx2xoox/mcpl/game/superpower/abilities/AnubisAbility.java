package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 아누비스 — the jackal god who weighs hearts against the feather of Ma'at. It reads what every enemy has been doing:
 * an enemy's "sin" is all the damage it dealt to anyone (players, summons) over the last seconds. The aggressive pay:
 * its blows land harder on them, its scale crushes heavy hearts, and its ultimate raises the jackal and lets Ammit, the
 * devourer, close her jaws on the guilty. The restrained walk away light, with a feather that doubles what they sin next.
 *
 * <p>Look (obsidian black, lapis, polished gold): small golden scales tilting beside every enemy's head for the jackal
 * alone, a great public scale with a red heart and a white feather that sink or rise, a black jackal head with gold ears
 * rising behind the god, and the green jaws of Ammit snapping out of the ground under the guilty.
 */
public final class AnubisAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("anubis", "아누비스", Material.LIGHT_WEIGHTED_PRESSURE_PLATE)
            .color(TextColor.color(0xD9A93A))
            .difficulty(2)
            .passive("심장의 무게",
                    "{sin_range}블록 안 적마다 최근 {sin_window}초간 누구에게든 준 피해를 죄로 셉니다. 죄 1당 그 적에게 "
                            + "주는 내 피해가 {sin_bonus}% 늘어납니다(최대 {sin_bonus_max}%, 저울질·아미트의 심판 제외).")
            .active("저울질",
                    "{weigh_delay}초 뒤 조준한 적({range}블록 이내)의 죄를 잽니다. 죄가 {heavy_threshold} 이상이면 "
                            + "{heavy_base} + 죄×{heavy_per_sin} 피해(최대 {heavy_max})를 주고 {sand_hand}초간 점프를 막고 "
                            + "{sand_slow}% 느리게 합니다. 가벼우면 {light_damage} 피해를 주고, {feather_seconds}초간 그 적이 "
                            + "얻는 죄가 {feather_multiplier}배가 됩니다.",
                    10)
            .ultimate("아미트의 심판",
                    "{weigh_seconds}초간 {judge_radius}블록 안 모든 적의 죄를 잰 뒤 심판합니다. 죄가 {guilty_threshold} "
                            + "이상인 적은 {guilty_base} + 죄×{guilty_per_sin} 피해(최대 {guilty_max})와 {jaw_hold}초 붙잡기를 "
                            + "받고, 죄가 가장 많은 적은 궁극기 게이지 {gauge_loss}%를 잃습니다. 무고한 적은 {innocent_damage} "
                            + "피해를 받고 {feather_seconds}초간 얻는 죄가 {feather_multiplier}배가 됩니다.")
            .ultimateNotStandalone()
            .param("sin_range", 24)
            .param("sin_window", 10)
            .param("sin_bonus", 2)
            .param("sin_bonus_max", 30)
            .param("range", 18)
            .param("weigh_delay", 0.4)
            .param("heavy_threshold", 6)
            .param("heavy_base", 3)
            .param("heavy_per_sin", 0.4)
            .param("heavy_max", 7)
            .param("sand_hand", 1)
            .param("sand_slow", 40)
            .param("light_damage", 2)
            .param("feather_seconds", 6)
            .param("feather_multiplier", 2)
            .param("judge_radius", 16)
            .param("weigh_seconds", 1)
            .param("guilty_threshold", 5)
            .param("guilty_base", 4)
            .param("guilty_per_sin", 0.5)
            .param("guilty_max", 9)
            .param("jaw_hold", 1)
            .param("gauge_loss", 20)
            .param("innocent_damage", 2)
            .build();

    // ------------------------------------------------------------------ look
    private static final String GOLD_TAG = "#D9A93A";
    private static final String HEART_TAG = "#C0283A";
    /** Pale gold, polished gold, obsidian. */
    private static final Vfx.Palette GOLDEN = Vfx.Palette.of(0xFFF0B8, 0xD9A93A, 0x17141F);
    /** Lapis glow: pale blue, lapis, night. */
    private static final Vfx.Palette LAPIS = Vfx.Palette.of(0xBFD0FF, 0x2849A0, 0x17141F);
    /** Ammit: pale green, crocodile green, swamp black. */
    private static final Vfx.Palette AMMIT = Vfx.Palette.of(0xC8E6A0, 0x4E6B2A, 0x1C2410);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(Color.fromRGB(0xD9A93A), 0.8f);
    private static final Particle.DustOptions LAPIS_DUST = new Particle.DustOptions(Color.fromRGB(0x2849A0), 0.9f);
    private static final Particle.DustTransition GOLD_GLOW = GOLDEN.glow(1.1f);
    private static final Particle.DustTransition LAPIS_FADE = LAPIS.fade(1.2f);
    private static final Particle.DustTransition AMMIT_FADE = AMMIT.fade(1.3f);
    private static final Color NO_BACKGROUND = Color.fromARGB(0, 0, 0, 0);
    private static final Material GOLD = Material.GOLD_BLOCK;
    private static final Material JACKAL = Material.BLACK_CONCRETE;
    private static final Material JAW = Material.GREEN_TERRACOTTA;
    private static final Material SAND = Material.SANDSTONE;

    private static final int MAX_DISPLAYS = 24;
    private static final int PASSIVE_SCALES = 5;
    private static final double TAU = Math.PI * 2;
    /** The tilt of a scale: the feather side down when innocent, the heart side down when sinful. */
    private static final float LIGHT_TILT = -10f;
    private static final float HEAVY_TILT = 30f;
    private static final long VERDICT_TICKS = 6;
    private static final long STATUS_PERIOD = 20;
    /** Mounted scales are sent again once they turned this far (degrees, by at most MAX_TURN) or tilted this far. */
    private static final float TURN_EPSILON = 2f;
    private static final float MAX_TURN = 60f;
    private static final float TILT_EPSILON = 0.5f;
    /** The judgement's swaying scales and the orbiting feather step this often, gliding over each step. */
    private static final int SWAY_STEP = 2;
    private static final int FEATHER_STEP = 2;

    // ------------------------------------------------------------------ state
    private final List<Display> live = new ArrayList<>();
    /** Each participant's recent damage dealt: (tick, amount). */
    private final Map<UUID, Deque<long[]>> ledger = new HashMap<>();
    private final Map<UUID, Long> feathers = new HashMap<>();
    private final Map<UUID, ItemDisplay> featherMarks = new HashMap<>();
    private final Map<UUID, LivingEntity> featherBodies = new HashMap<>();
    /** The tick each feather mark appeared (it grows in from the next tick on). */
    private final Map<UUID, Long> featherBorn = new HashMap<>();
    private final Map<UUID, MiniScale> scales = new LinkedHashMap<>();
    private final Map<UUID, Integer> shownSin = new HashMap<>();
    private @Nullable SinListener listener;
    /** True while the scale's or Ammit's own damage is dealt: the passive bonus is not applied to it. */
    private boolean judging;
    /** True while the ultimate's great scales are up: the jackal's small private scales step aside. */
    private boolean weighing;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        SinListener created = new SinListener();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        listener = created;
    }

    @Override
    public void onRevoke() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        ledger.clear();
        feathers.clear();
        featherMarks.clear();
        featherBodies.clear();
        featherBorn.clear();
        scales.clear();
        shownSin.clear();
        live.clear();
    }

    // ================================================================== passive: the weight of the heart

    /** Writes every hit a participant lands (on anyone) into the ledger: that is its sin. */
    private final class SinListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(EntityDamageByEntityEvent event) {
            if (!(event.getEntity() instanceof LivingEntity victim)) {
                return;
            }
            Player attacker = ctx().resolveAttacker(event.getDamager());
            if (attacker == null || attacker.equals(player()) || attacker.equals(victim)
                    || ctx().abilityOf(attacker) == null) {
                return;
            }
            double amount = event.getFinalDamage();
            if (!(amount > 0)) {
                return;
            }
            if (isFeathered(attacker)) {
                amount *= Math.max(1, params().num("feather_multiplier"));
            }
            ledger.computeIfAbsent(attacker.getUniqueId(), id -> new ArrayDeque<>())
                    .addLast(new long[] {ctx().currentTick(), Double.doubleToLongBits(amount)});
        }
    }

    /** {@code entity}'s sin: the damage it dealt over the last window (0 for anything but a participant). */
    double sinOf(Entity entity) {
        Deque<long[]> entries = ledger.get(entity.getUniqueId());
        if (entries == null) {
            return 0;
        }
        long oldest = ctx().currentTick() - Math.max(1, params().ticks("sin_window"));
        while (!entries.isEmpty() && entries.peekFirst()[0] < oldest) {
            entries.pollFirst();
        }
        double sum = 0;
        for (long[] entry : entries) {
            sum += Double.longBitsToDouble(entry[1]);
        }
        return sum;
    }

    private static String sinText(double sin) {
        return AbilityInfo.formatNumber(Math.round(sin * 10) / 10.0);
    }

    private boolean isFeathered(Entity entity) {
        Long until = feathers.get(entity.getUniqueId());
        return until != null && until > ctx().currentTick();
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || judging || !(victim instanceof Player)) {
            return;
        }
        double sin = sinOf(victim);
        double bonus = Math.min(Math.max(0, params().num("sin_bonus_max")), Math.max(0, params().num("sin_bonus")) * sin);
        if (bonus <= 0) {
            return;
        }
        event.setDamage(event.getDamage() * (1 + bonus / 100.0));
        if (bonus >= 10) {
            Location at = Fx.body(victim);
            Vfx.cloud(ctx(), at, Particle.DUST, 4, 0.3, 0, GOLD_DUST, false);
            Vfx.dot(at.clone().add(0, 0.5, 0), Particle.WAX_OFF, null, false);
        }
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        tickFeathers(owner, now);
        if (tick % 2 == 0) {
            tickScales(owner, tick);
        }
        if (tick % STATUS_PERIOD == 0) {
            tellSins(owner);
        }
    }

    /** The jackal's own small scales beside the heads of the nearest enemies (only it sees them). */
    private void tickScales(Player owner, long tick) {
        double range = Math.max(1, params().num("sin_range"));
        List<Player> near = new ArrayList<>();
        if (!weighing) {
            for (LivingEntity entity : ctx().enemiesNear(owner, owner.getLocation(), range)) {
                if (entity instanceof Player enemy && !ctx().isConcealedFrom(enemy, owner)) {
                    near.add(enemy);
                    if (near.size() >= PASSIVE_SCALES) {
                        break;
                    }
                }
            }
        }
        Iterator<Map.Entry<UUID, MiniScale>> it = scales.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MiniScale> entry = it.next();
            if (near.stream().noneMatch(p -> p.getUniqueId().equals(entry.getKey()))) {
                entry.getValue().remove();
                it.remove();
            }
        }
        for (Player enemy : near) {
            MiniScale scale = scales.get(enemy.getUniqueId());
            if (scale == null || !scale.valid()) {
                if (!reserve(2)) {
                    continue;
                }
                scale = new MiniScale(owner, enemy);
                scales.put(enemy.getUniqueId(), scale);
            }
            scale.update(sinOf(enemy), tick);
        }
    }

    /** Each enemy near reads its own sin on its action bar (never over a prompt). */
    private void tellSins(Player owner) {
        double range = Math.max(1, params().num("sin_range"));
        for (LivingEntity entity : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            if (!(entity instanceof Player enemy) || ctx().hasShownPrompt(enemy)) {
                continue;
            }
            double sin = sinOf(enemy);
            int rounded = (int) Math.round(sin * 10);
            Integer last = shownSin.get(enemy.getUniqueId());
            if (rounded == 0 && (last == null || last == 0) && !isFeathered(enemy)) {
                continue;
            }
            shownSin.put(enemy.getUniqueId(), rounded);
            String color = sin >= params().num("heavy_threshold") ? HEART_TAG : GOLD_TAG;
            String feather = isFeathered(enemy) ? " <white>· 깃털 표식: 죄 ×"
                    + AbilityInfo.formatNumber(params().num("feather_multiplier")) + "</white>" : "";
            enemy.sendActionBar(Text.mm("<b><" + color + ">⚖ 죄 " + sinText(sin) + "</" + color + "></b> <gray>— "
                    + "아누비스가 저울질합니다 (최근 " + AbilityInfo.formatNumber(params().num("sin_window"))
                    + "초간 준 피해)</gray>" + feather));
        }
    }

    /** A small private scale: a gold beam tilting by the sin, and the number. */
    private final class MiniScale {

        private final Player owner;
        private final Player enemy;
        private final BlockDisplay beam;
        private final TextDisplay label;
        private int lastShown = Integer.MIN_VALUE;

        MiniScale(Player owner, Player enemy) {
            this.owner = owner;
            this.enemy = enemy;
            Location at = spot();
            java.util.function.Predicate<Player> viewers = viewer -> viewer.equals(owner)
                    && !ctx().isConcealedFrom(enemy, viewer);
            this.beam = keep(Displays.block(ctx(), owner, at, GOLD.createBlockData(),
                    Displays.transform(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), 0, viewers));
            beam.setTeleportDuration(2);
            this.label = keep(Displays.spawn(ctx(), owner, at.clone().add(0, 0.22, 0), TextDisplay.class, 0,
                    viewers, d -> {
                        d.text(Text.mm(""));
                        d.setBillboard(Display.Billboard.CENTER);
                        d.setBackgroundColor(NO_BACKGROUND);
                        d.setShadowed(true);
                        d.setTeleportDuration(2);
                        d.setTransformation(Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f, 0, 0, 0));
                    }));
        }

        boolean valid() {
            return beam.isValid() && label.isValid();
        }

        /** Beside the head, to the jackal's right as it looks at the enemy. */
        private Location spot() {
            Vector toEnemy = Motion.horizontal(enemy.getLocation().toVector().subtract(owner.getLocation().toVector()));
            Vector right = toEnemy.lengthSquared() < 1.0E-6 ? new Vector(1, 0, 0)
                    : new Vector(-toEnemy.getZ(), 0, toEnemy.getX());
            return enemy.getLocation().add(0, enemy.getHeight() + 0.15, 0).add(right.multiply(0.75));
        }

        void update(double sin, long tick) {
            Location at = spot();
            Displays.moveTo(beam, at);
            Displays.moveTo(label, at.clone().add(0, 0.22, 0));
            float yaw = yawToward(enemy.getLocation(), owner.getLocation());
            float tilt = tiltFor(sin) + (float) Math.sin(tick * 0.2) * 2f;
            Displays.animate(beam, Displays.transform(0, 0, 0, 0.62f, 0.045f, 0.045f, yaw, 0, tilt), 2);
            int shown = (int) Math.round(sin * 10);
            if (shown != lastShown) {
                lastShown = shown;
                String color = sin >= params().num("heavy_threshold") ? HEART_TAG : GOLD_TAG;
                label.text(Text.mm("<" + color + ">罪 " + sinText(sin) + "</" + color + ">"));
            }
        }

        void remove() {
            if (beam.isValid()) {
                beam.remove();
            }
            if (label.isValid()) {
                label.remove();
            }
        }
    }

    private float tiltFor(double sin) {
        double heavy = Math.max(1, params().num("heavy_threshold"));
        double share = Math.clamp(sin / (heavy * 1.5), 0.0, 1.0);
        return (float) (LIGHT_TILT + (HEAVY_TILT - LIGHT_TILT) * share);
    }

    /** The Minecraft yaw (degrees) from {@code from} looking at {@code to}, horizontally. */
    private static float yawToward(Location from, Location to) {
        Vector d = to.toVector().subtract(from.toVector());
        if (d.getX() * d.getX() + d.getZ() * d.getZ() < 1.0E-8) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
    }

    // ------------------------------------------------------------------ feathers

    /** The feather of Ma'at over {@code target}: sin it gains counts double for a while. */
    private void feather(Player owner, LivingEntity target) {
        long ticks = Math.max(1, params().ticks("feather_seconds"));
        feathers.put(target.getUniqueId(), ctx().currentTick() + ticks);
        featherBodies.put(target.getUniqueId(), target);
        ItemDisplay mark = featherMarks.get(target.getUniqueId());
        if ((mark == null || !mark.isValid()) && reserve(1)) {
            // Rides the head; its orbit is its offset from the seat, stepped every FEATHER_STEP ticks.
            long now = ctx().currentTick();
            mark = keep(Displays.spawn(ctx(), owner, seat(target), ItemDisplay.class, 0,
                    viewer -> !ctx().isConcealedFrom(target, viewer), d -> {
                        d.setItemStack(new ItemStack(Material.FEATHER));
                        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                        d.setTransformation(featherPose(now, 0.1f));
                    }));
            ride(target, mark);
            featherMarks.put(target.getUniqueId(), mark);
            featherBorn.put(target.getUniqueId(), now);
        }
        Location head = target.getLocation().add(0, target.getHeight() + 0.4, 0);
        Vfx.cloud(ctx(), head, Particle.END_ROD, 5, 0.3, 0.02, null, false);
        Fx.sound(head, Sound.ENTITY_PHANTOM_FLAP, 0.6f, 1.6f);
    }

    /** The feather circling the head at {@code tick}: its offset from the seat, its spin and sway. */
    private static Transformation featherPose(long tick, float scale) {
        double angle = tick * 0.15;
        return Displays.centered((float) (Math.cos(angle) * 0.45), (float) (0.35 + Math.sin(tick * 0.3) * 0.05),
                (float) (Math.sin(angle) * 0.45), scale, scale, scale, tick * 6f, 0, (float) Math.sin(tick * 0.2) * 20f);
    }

    private void tickFeathers(Player owner, long now) {
        Iterator<Map.Entry<UUID, Long>> it = feathers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            LivingEntity body = featherBodies.get(entry.getKey());
            ItemDisplay mark = featherMarks.get(entry.getKey());
            boolean gone = body == null || !body.isValid() || body.isDead()
                    || (body instanceof Player p && !ctx().isAlive(p));
            if (entry.getValue() <= now || gone) {
                it.remove();
                featherBodies.remove(entry.getKey());
                featherMarks.remove(entry.getKey());
                featherBorn.remove(entry.getKey());
                if (mark != null && mark.isValid()) {
                    Displays.shrinkAway(ctx(), owner, mark, 4);
                }
                continue;
            }
            if (mark != null && mark.isValid()) {
                ride(body, mark);
                if (now % FEATHER_STEP == 0 && featherBorn.getOrDefault(entry.getKey(), now - 1) < now) {
                    Displays.animate(mark, featherPose(now + FEATHER_STEP, 0.55f), FEATHER_STEP);
                }
            }
        }
    }

    // ================================================================== active: the weighing

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        LivingEntity target = ctx().findTarget(owner, Math.max(1, params().num("range")));
        if (target == null) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        Location hand = owner.getEyeLocation().add(owner.getLocation().getDirection().multiply(0.6));
        Location head = target.getLocation().add(0, target.getHeight() + 0.9, 0);
        Vfx.ring(ctx(), hand, 0.35, 10, 0, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
        for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
            Vfx.trail(hand, head.clone().add(Fx.jitter(0.4f), 0, Fx.jitter(0.4f)), Color.fromRGB(0xD9A93A), 8, false);
        }
        Vfx.trail(hand, head, Color.fromRGB(0x2849A0), 8, true);
        Fx.sound(owner.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.7f);
        Fx.sound(owner.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.6f, 1.4f);
        Fx.sound(head, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.6f);
        new GreatScale(owner, target, 1.1f, true).weighIn(Math.max(1, params().ticks("weigh_delay")), sin -> {
            boolean heavy = sin >= params().num("heavy_threshold");
            if (heavy) {
                double damage = Math.min(params().num("heavy_max"), params().num("heavy_base")
                        + params().num("heavy_per_sin") * sin);
                judge(owner, target, damage);
                sandHand(owner, target);
            } else {
                judge(owner, target, Math.max(0, params().num("light_damage")));
                if (target.isValid() && !target.isDead()) {
                    feather(owner, target);
                }
            }
            return heavy;
        });
        return ActivationResult.success();
    }

    /** The scale's own damage: the passive bonus does not apply (the sin already weighs in). */
    private void judge(Player owner, LivingEntity target, double damage) {
        judging = true;
        try {
            Combat.hit(ctx(), owner, target, Math.max(0, damage));
        } finally {
            judging = false;
        }
    }

    /** Hands of sand close around the feet: no jumping, slowed. */
    private void sandHand(Player owner, LivingEntity target) {
        long ticks = Math.max(1, params().ticks("sand_hand"));
        if (!Combat.isTargetable(ctx(), owner, target)) {
            return;
        }
        ctx().addModifier(owner, target, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, ticks);
        double slow = Math.clamp(params().num("sand_slow") / 100.0, 0.0, 0.9);
        if (slow > 0) {
            ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, ticks);
        }
        List<BlockDisplay> fingers = new ArrayList<>();
        for (int i = 0; i < 4 && reserve(1); i++) {
            double angle = i * TAU / 4 + 0.4;
            Location at = target.getLocation().add(Math.cos(angle) * 0.45, 0.1, Math.sin(angle) * 0.45);
            float yaw = yawToward(at, target.getLocation());
            BlockDisplay finger = keep(Displays.block(ctx(), owner, at, SAND.createBlockData(),
                    Displays.transform(0, -0.5f, 0, 0.14f, 0.7f, 0.14f, yaw, 0, 0), ticks + 10));
            Displays.animateNextTick(ctx(), owner, finger, Displays.transform(0, 0.25f, 0, 0.14f, 0.7f, 0.14f, yaw,
                    28, 0), 4);
            fingers.add(finger);
        }
        ctx().runLater(owner, ticks, () -> {
            for (BlockDisplay finger : fingers) {
                if (finger.isValid()) {
                    Vfx.cloud(ctx(), finger.getLocation().add(0, 0.3, 0), Particle.FALLING_DUST, 3, 0.1, 0,
                            SAND.createBlockData(), false);
                    Displays.shrinkAway(ctx(), owner, finger, 5);
                }
            }
        });
        Location feet = target.getLocation().add(0, 0.2, 0);
        Vfx.cloud(ctx(), feet, Particle.BLOCK, 16, 0.4, 0.1, SAND.createBlockData(), false);
        Vfx.ring(ctx(), feet, 0.6, 12, 0, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
        Fx.sound(feet, Sound.BLOCK_SUSPICIOUS_SAND_BREAK, 1.0f, 0.6f);
        Fx.sound(feet, Sound.BLOCK_SAND_BREAK, 1.0f, 0.8f);
    }

    /** What the scale decides once the heart is on it; returns whether the heart was heavy. */
    @FunctionalInterface
    private interface Verdict {
        boolean decide(double sin);
    }

    /**
     * A public scale over a head: a gold beam with two pans, the red heart and the white feather on them, the sin
     * written above. It tilts with the sin and then gives its verdict.
     */
    private final class GreatScale {

        private final Player owner;
        private final LivingEntity target;
        private final float size;
        private final boolean withHeart;
        private @Nullable BlockDisplay beam;
        private @Nullable BlockDisplay leftPan;
        private @Nullable BlockDisplay rightPan;
        private @Nullable TextDisplay heart;
        private @Nullable ItemDisplay feather;
        private @Nullable TextDisplay label;
        private float tilt = LIGHT_TILT;
        private float sentYaw = Float.NaN;
        private float sentTilt = Float.NaN;
        /** The tick heart and feather were put on the pans (-1: not yet), and whether they were posed since. */
        private long weightsAt = -1;
        private boolean weightsPosed;
        private @Nullable String shownSin;
        private boolean over;

        GreatScale(Player owner, LivingEntity target, float size, boolean withHeart) {
            this.owner = owner;
            this.target = target;
            this.size = size;
            this.withHeart = withHeart;
            Location at = seat(target);
            java.util.function.Predicate<Player> viewers = viewer -> !ctx().isConcealedFrom(target, viewer);
            float lift = above();
            if (reserve(1)) {
                beam = keep(Displays.block(ctx(), owner, at, GOLD.createBlockData(),
                        Displays.transform(0, lift, 0, 0.02f, 0.02f, 0.02f, 0, 0, 0), 0, viewers));
            }
            BlockData pan = Material.GOLD_BLOCK.createBlockData();
            if (reserve(2)) {
                leftPan = keep(Displays.block(ctx(), owner, at, pan,
                        Displays.transform(0, lift, 0, 0.02f, 0.02f, 0.02f, 0, 0, 0), 0, viewers));
                rightPan = keep(Displays.block(ctx(), owner, at, pan,
                        Displays.transform(0, lift, 0, 0.02f, 0.02f, 0.02f, 0, 0, 0), 0, viewers));
            }
            if (reserve(1)) {
                // Upright (VERTICAL): its rise over the seat stays vertical whatever the camera's pitch.
                label = keep(Displays.spawn(ctx(), owner, at, TextDisplay.class, 0, viewers, d -> {
                    d.text(Text.mm("<" + GOLD_TAG + ">⚖</" + GOLD_TAG + ">"));
                    d.setBillboard(Display.Billboard.VERTICAL);
                    d.setBackgroundColor(NO_BACKGROUND);
                    d.setShadowed(true);
                    d.setTransformation(Displays.centered(0, lift + 0.45f * size, 0, 0.9f * size, 0.9f * size,
                            0.9f * size, 0, 0, 0));
                }));
            }
            for (Display part : mounted()) {
                ride(target, part);
            }
            ctx().runLater(owner, 1, () -> pose(2));
        }

        /** How high over the seat (the top of the head) the beam hangs. */
        private float above() {
            return 1.0f + 0.3f * size;
        }

        private Location anchor() {
            return seat(target).add(0, above(), 0);
        }

        /** The pans' offsets from the beam's middle for the current pose: [heart side, feather side]. */
        private Vector[] sides(float yaw, float tilt) {
            float half = 0.55f * size;
            Vector facing = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
            Vector right = new Vector(-facing.getZ(), 0, facing.getX());
            double drop = Math.sin(Math.toRadians(tilt)) * half;
            double reach = Math.cos(Math.toRadians(tilt)) * half;
            // A positive roll lowers the local -X end (the heart's side).
            return new Vector[] {
                    right.clone().multiply(reach).add(new Vector(0, -drop - 0.2 * size, 0)),
                    right.clone().multiply(-reach).add(new Vector(0, drop - 0.2 * size, 0))};
        }

        /** Where the heart lies, in the world (it is a camera-facing text, so it cannot ride with an offset). */
        private Location heartSpot() {
            float yaw = Float.isNaN(sentYaw) ? yawToward(target.getLocation(), owner.getLocation()) : sentYaw;
            Vector side = sides(yaw, Float.isNaN(sentTilt) ? tilt : sentTilt)[0];
            return anchor().add(side).add(0, 0.15 * size, 0);
        }

        /**
         * Sends beam, pans and weights again when the scale turned toward the jackal or tilted (interpolated over
         * {@code ticks}, the caller's update period); weights just put on the pans drop in from the next tick on.
         */
        private void pose(int ticks) {
            if (over) {
                return;
            }
            float yaw = yawToward(target.getLocation(), owner.getLocation());
            long now = ctx().currentTick();
            boolean weightsDue = weightsAt >= 0 && now > weightsAt && !weightsPosed;
            boolean moved = Float.isNaN(sentYaw) || Math.abs(wrap(yaw - sentYaw)) >= TURN_EPSILON
                    || Math.abs(tilt - sentTilt) >= TILT_EPSILON;
            if (!moved && !weightsDue) {
                return;
            }
            if (moved) {
                sentYaw = Float.isNaN(sentYaw) ? yaw : turnToward(sentYaw, yaw, MAX_TURN);
                sentTilt = tilt;
            }
            float lift = above();
            Vector[] sides = sides(sentYaw, sentTilt);
            Vector heartSide = sides[0].clone().add(new Vector(0, lift, 0));
            Vector featherSide = sides[1].clone().add(new Vector(0, lift, 0));
            if (moved) {
                BlockDisplay bar = beam;
                if (bar != null && bar.isValid()) {
                    Displays.animate(bar, Displays.transform(0, lift, 0, 1.1f * size, 0.06f * size, 0.06f * size,
                            sentYaw, 0, sentTilt), ticks);
                }
                placePan(leftPan, heartSide, ticks);
                placePan(rightPan, featherSide, ticks);
            }
            if (weightsAt < 0 || now <= weightsAt) {
                return; // not on the pans yet, or put there this very tick (they would snap instead of dropping in)
            }
            weightsPosed = true;
            TextDisplay red = heart;
            if (red != null && red.isValid()) {
                Displays.animate(red, Displays.centered(0, 0, 0, 0.9f * size, 0.9f * size, 0.9f * size, 0, 0, 0),
                        ticks);
            }
            ItemDisplay white = feather;
            if (white != null && white.isValid()) {
                Displays.animate(white, Displays.centered((float) featherSide.getX(),
                        (float) featherSide.getY() + 0.2f * size, (float) featherSide.getZ(), 0.45f * size, 0.45f * size,
                        0.45f * size, sentYaw, 0, 30), ticks);
            }
        }

        private void placePan(@Nullable BlockDisplay pan, Vector offset, int ticks) {
            if (pan != null && pan.isValid()) {
                Displays.animate(pan, Displays.transform((float) offset.getX(), (float) offset.getY(),
                        (float) offset.getZ(), 0.32f * size, 0.04f * size, 0.32f * size, 0, 0, 0), ticks);
            }
        }

        /** Keeps the parts seated on the head; the heart glides after its pan (3 ticks, like a client's lerp). */
        private void follow() {
            for (Display part : mounted()) {
                ride(target, part);
            }
            TextDisplay red = heart;
            if (red != null && red.isValid() && ctx().currentTick() > weightsAt) {
                Displays.moveTo(red, heartSpot());
            }
        }

        /** Everything but the heart rides the head. */
        private List<Display> mounted() {
            List<Display> all = new ArrayList<>(5);
            for (Display display : new Display[] {beam, leftPan, rightPan, feather, label}) {
                if (display != null) {
                    all.add(display);
                }
            }
            return all;
        }

        private List<Display> parts() {
            List<Display> all = mounted();
            if (heart != null) {
                all.add(heart);
            }
            return all;
        }

        /** Puts heart and feather on the pans after {@code delay} ticks, tilts, then decides. */
        void weighIn(long delay, Verdict verdict) {
            Loop.repeat(ctx(), owner, 1, 1, delay + VERDICT_TICKS + 1, run -> {
                if (over) {
                    return false;
                }
                if (!target.isValid() || target.isDead() || (target instanceof Player p && !ctx().isAlive(p))) {
                    vanish(false);
                    return false;
                }
                follow();
                if (run + 1 == delay) {
                    placeWeights();
                }
                if (run + 1 >= delay) {
                    double sin = sinOf(target);
                    tilt = tiltFor(sin);
                    pose(3);
                    writeSin(sin);
                }
                if (run + 1 >= delay + VERDICT_TICKS) {
                    double sin = sinOf(target);
                    boolean heavy = verdict.decide(sin);
                    announceVerdict(heavy);
                    vanish(heavy);
                    return false;
                }
                return true;
            });
        }

        /** Sways with the live sin until {@link #vanish} (posed every {@link #SWAY_STEP} ticks). */
        void sway() {
            follow();
            double sin = sinOf(target);
            long now = ctx().currentTick();
            tilt = tiltFor(sin) + (float) Math.sin(now * 0.4) * 3f;
            if (now % SWAY_STEP == 0) {
                pose(SWAY_STEP);
            }
            writeSin(sin);
        }

        void placeWeights() {
            Location at = anchor();
            java.util.function.Predicate<Player> viewers = viewer -> !ctx().isConcealedFrom(target, viewer);
            weightsAt = ctx().currentTick();
            if (withHeart && reserve(1)) {
                // Starts over its pan and drops onto it (the first glide after the spawn uses its own 3 ticks).
                heart = keep(Displays.spawn(ctx(), owner, heartSpot().add(0, 0.6, 0), TextDisplay.class, 0, viewers,
                        d -> {
                            d.text(Text.mm("<" + HEART_TAG + ">❤</" + HEART_TAG + ">"));
                            d.setBillboard(Display.Billboard.CENTER);
                            d.setBackgroundColor(NO_BACKGROUND);
                            d.setShadowed(true);
                            d.setTeleportDuration(3);
                            d.setTransformation(Displays.centered(0, 0, 0, 0.1f, 0.1f, 0.1f, 0, 0, 0));
                        }));
            }
            if (withHeart && reserve(1)) {
                float yaw = Float.isNaN(sentYaw) ? 0 : sentYaw;
                Vector side = sides(yaw, Float.isNaN(sentTilt) ? tilt : sentTilt)[1];
                feather = keep(Displays.spawn(ctx(), owner, seat(target), ItemDisplay.class, 0, viewers, d -> {
                    d.setItemStack(new ItemStack(Material.FEATHER));
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    d.setTransformation(Displays.centered((float) side.getX(),
                            (float) side.getY() + above() + 0.8f * size, (float) side.getZ(), 0.1f, 0.1f, 0.1f, yaw, 0,
                            30));
                }));
                ride(target, feather);
            }
            Vfx.cloud(ctx(), at, Particle.END_ROD, 4, 0.4, 0.02, null, false);
            Fx.sound(at, Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.3f);
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
        }

        void writeSin(double sin) {
            TextDisplay text = label;
            if (text != null && text.isValid()) {
                String color = sin >= params().num("guilty_threshold") ? HEART_TAG : GOLD_TAG;
                String line = "<b><" + color + ">罪 " + sinText(sin) + "</" + color + "></b>";
                if (!line.equals(shownSin)) {
                    shownSin = line;
                    text.text(Text.mm(line));
                }
            }
        }

        void announceVerdict(boolean heavy) {
            Location head = anchor();
            if (heavy) {
                pop(owner, target, "<b><" + HEART_TAG + ">무거운 심장</" + HEART_TAG + "></b>", 1.3f);
                Vfx.burst(ctx(), head, GOLDEN, 0.7, Particle.CRIMSON_SPORE, false);
                Fx.sound(head, Sound.BLOCK_ANVIL_LAND, 0.6f, 0.6f);
                Fx.sound(head, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.4f, 1.6f);
            } else {
                pop(owner, target, "<b><white>가벼운 심장</white></b>", 1.2f);
                Vfx.cloud(ctx(), head, Particle.END_ROD, 8, 0.4, 0.03, null, false);
                Fx.sound(head, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.6f);
                Fx.sound(head, Sound.ENTITY_PHANTOM_FLAP, 0.5f, 1.8f);
            }
        }

        /** The scale fades (or, for a heavy heart, the heart sinks first). */
        void vanish(boolean heavy) {
            if (over) {
                return;
            }
            over = true;
            for (Display part : parts()) {
                if (part.isValid()) {
                    if (heavy && part == heart) {
                        Displays.moveTo(part, part.getLocation().add(0, -1.2 * size, 0));
                        Displays.animate(part, Displays.centered(0, 0, 0, 1.2f * size, 1.2f * size, 1.2f * size, 0, 0,
                                0), 4);
                        ctx().runLater(owner, 4, () -> {
                            if (part.isValid()) {
                                Displays.shrinkAway(ctx(), owner, part, 3);
                            }
                        });
                    } else {
                        Displays.shrinkAway(ctx(), owner, part, 5);
                    }
                }
            }
        }
    }

    // ================================================================== ultimate: the judgement of Ammit

    @Override
    public ActivationResult onUltimate() {
        if (weighing) {
            return ActivationResult.fail("이미 심판 중입니다");
        }
        Player owner = player();
        double radius = Math.max(1, params().num("judge_radius"));
        List<LivingEntity> accused = new ArrayList<>();
        for (LivingEntity entity : ctx().enemiesNear(owner, owner.getLocation(), radius)) {
            if (entity instanceof Player enemy && ctx().isConcealedFrom(enemy, owner)) {
                continue;
            }
            accused.add(entity);
        }
        if (accused.isEmpty()) {
            return ActivationResult.fail("주위 " + AbilityInfo.formatNumber(radius) + "블록 안에 심판할 적이 없습니다");
        }
        weighing = true;
        for (MiniScale scale : scales.values()) {
            scale.remove();
        }
        scales.clear();
        long weigh = Math.max(1, params().ticks("weigh_seconds"));
        raiseJackal(owner, weigh + 16);
        List<GreatScale> great = new ArrayList<>();
        List<LivingEntity> weighed = new ArrayList<>();
        for (LivingEntity entity : accused) {
            if (great.size() >= 4) {
                weighed.add(entity);
                continue;
            }
            great.add(new GreatScale(owner, entity, 1.6f, false));
            weighed.add(entity);
        }
        Fx.sound(owner.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 0.7f);
        Fx.sound(owner.getLocation(), Sound.BLOCK_BELL_RESONATE, 1.2f, 0.6f);
        Fx.sound(owner.getLocation(), Sound.ENTITY_WOLF_GROWL, 1.2f, 0.5f);
        ctx().announce(Text.mm("<b><" + GOLD_TAG + ">아미트의 심판</" + GOLD_TAG + "></b> <white>아누비스가 "
                + AbilityInfo.formatNumber(radius) + "블록 안의 심장을 저울질합니다</white>"));
        Loop.repeat(ctx(), owner, 1, 1, weigh, run -> {
            for (GreatScale scale : great) {
                scale.sway();
            }
            if (run % 4 == 0) {
                Vfx.ring(ctx(), owner.getLocation().add(0, 0.1, 0), radius, (int) Math.round(radius * 4), run * 0.05,
                        Particle.DUST, LAPIS_DUST, true);
            }
            return true;
        }, () -> {
            for (GreatScale scale : great) {
                scale.vanish(false);
            }
            verdicts(owner, weighed);
            weighing = false;
        });
        return ActivationResult.success();
    }

    /** The judgement: Ammit for the guilty, the feather for the innocent; the worst sinner loses gauge. */
    private void verdicts(Player owner, List<LivingEntity> weighed) {
        double threshold = params().num("guilty_threshold");
        Player worst = null;
        double worstSin = -1;
        for (LivingEntity entity : weighed) {
            if (!Combat.isTargetable(ctx(), owner, entity)) {
                continue;
            }
            double sin = sinOf(entity);
            if (sin >= threshold) {
                double damage = Math.min(params().num("guilty_max"), params().num("guilty_base")
                        + params().num("guilty_per_sin") * sin);
                ammit(owner, entity, damage);
                if (entity instanceof Player enemy && sin > worstSin) {
                    worst = enemy;
                    worstSin = sin;
                }
            } else {
                pop(owner, entity, "<b><white>무고</white></b>", 1.1f);
                judge(owner, entity, Math.max(0, params().num("innocent_damage")));
                if (entity.isValid() && !entity.isDead()) {
                    feather(owner, entity);
                }
            }
        }
        if (worst != null && ctx().isAlive(worst)) {
            double loss = Math.max(0, params().num("gauge_loss"));
            if (loss > 0) {
                ctx().addGauge(worst, -loss);
                Location head = worst.getLocation().add(0, worst.getHeight() + 0.4, 0);
                Vfx.cloud(ctx(), head, Particle.SOUL, 8, 0.4, 0.04, null, false);
                pop(owner, worst, "<b><" + HEART_TAG + ">가장 무거운 죄</" + HEART_TAG + "></b> <gray>궁극기 -"
                        + AbilityInfo.formatNumber(loss) + "%</gray>", 1.0f);
            }
        }
    }

    /** Ammit's jaws burst from the ground and snap shut on {@code target}. */
    private void ammit(Player owner, LivingEntity target, double damage) {
        Location feet = target.getLocation();
        float yaw = yawToward(feet, owner.getLocation());
        if (reserve(2)) {
            BlockDisplay lower = keep(Displays.block(ctx(), owner, feet, JAW.createBlockData(),
                    Displays.transform(0, -0.8f, 0, 1.3f, 0.35f, 1.1f, yaw, 0, 0), 24));
            BlockDisplay upper = keep(Displays.block(ctx(), owner, feet, JAW.createBlockData(),
                    Displays.transform(0, -0.4f, 0, 1.3f, 0.4f, 1.1f, yaw, -70, 0), 24));
            Displays.animateNextTick(ctx(), owner, lower, Displays.transform(0, 0.1f, 0, 1.3f, 0.35f, 1.1f, yaw, 0, 0),
                    3);
            Displays.animateNextTick(ctx(), owner, upper, Displays.transform(0, 1.3f, 0, 1.3f, 0.4f, 1.1f, yaw, -70, 0),
                    3);
            ctx().runLater(owner, 5, () -> Displays.animate(upper, Displays.transform(0, 0.55f, 0, 1.3f, 0.4f, 1.1f,
                    yaw, 0, 0), 2));
            ctx().runLater(owner, 18, () -> {
                for (BlockDisplay jaw : List.of(lower, upper)) {
                    if (jaw.isValid()) {
                        Displays.shrinkAway(ctx(), owner, jaw, 5);
                    }
                }
            });
        }
        Vfx.cloud(ctx(), feet.clone().add(0, 0.3, 0), Particle.BLOCK, 20, 0.6, 0.15, Material.MUD.createBlockData(),
                true);
        Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 1.1, 16, 0, Particle.DUST_COLOR_TRANSITION, AMMIT_FADE, true);
        Fx.sound(feet, Sound.ENTITY_RAVAGER_ROAR, 0.8f, 1.4f);
        Fx.sound(feet, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.2f, 0.6f);
        ctx().runLater(owner, 6, () -> {
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return;
            }
            Location chest = Fx.body(target);
            Vfx.burst(ctx(), chest, AMMIT, 0.8, Particle.CRIT, true);
            Vfx.cloud(ctx(), chest, Particle.END_ROD, 6, 0.4, 0.05, null, false);
            Fx.sound(chest, Sound.ENTITY_RAVAGER_ATTACK, 1.0f, 0.7f);
            Fx.sound(chest, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.5f);
            pop(owner, target, "<b><" + HEART_TAG + ">유죄</" + HEART_TAG + "></b>", 1.3f);
            judge(owner, target, damage);
            long hold = params().ticks("jaw_hold");
            if (hold > 0 && Combat.isTargetable(ctx(), owner, target)) {
                ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, hold);
                ctx().addModifier(owner, target, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, hold);
            }
        });
    }

    /**
     * The jackal head rises behind the god: black, gold-eared, its eyes burning lapis. Its parts ride the god (it stays
     * behind it while it walks during the weighing), turned as it faced at the cast.
     */
    private void raiseJackal(Player owner, long life) {
        Location feet = owner.getLocation();
        float yaw = feet.getYaw();
        Vector facing = Motion.horizontalFacing(feet);
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());
        Location anchor = feet.clone().subtract(facing.clone().multiply(0.9)).add(0, 2.6, 0);
        anchor.setYaw(0);
        anchor.setPitch(0);
        // The anchor's offset from the seat the parts ride.
        Vector base = facing.clone().multiply(-0.9).setY(2.6 - owner.getHeight());
        record Part(Material type, double x, double y, double z, float sx, float sy, float sz, float roll) {
        }
        List<Part> parts = List.of(
                new Part(JACKAL, 0, 0, 0, 0.9f, 0.8f, 0.9f, 0),
                new Part(JACKAL, 0, -0.15, 0.68, 0.45f, 0.38f, 0.7f, 0),
                new Part(JACKAL, -0.28, 0.72, -0.1, 0.18f, 0.75f, 0.12f, -8),
                new Part(JACKAL, 0.28, 0.72, -0.1, 0.18f, 0.75f, 0.12f, 8),
                new Part(GOLD, 0, -0.52, 0, 1.15f, 0.16f, 1.0f, 0),
                new Part(Material.LAPIS_BLOCK, -0.2, 0.12, 0.455, 0.14f, 0.08f, 0.02f, 0),
                new Part(Material.LAPIS_BLOCK, 0.2, 0.12, 0.455, 0.14f, 0.08f, 0.02f, 0));
        for (Part part : parts) {
            if (!reserve(1)) {
                break;
            }
            Vector offset = right.clone().multiply(part.x()).add(new Vector(0, part.y(), 0))
                    .add(facing.clone().multiply(part.z())).add(base);
            BlockDisplay piece = keep(Displays.block(ctx(), owner, seat(owner), part.type().createBlockData(),
                    Displays.transform((float) offset.getX(), (float) offset.getY() - 1.6f, (float) offset.getZ(),
                            part.sx(), part.sy(), part.sz(), yaw, 0, part.roll()), life));
            ride(owner, piece);
            if (part.type() == Material.LAPIS_BLOCK) {
                Displays.outline(piece, Color.fromRGB(0x4F7BFF));
            }
            Displays.animateNextTick(ctx(), owner, piece, Displays.transform((float) offset.getX(),
                    (float) offset.getY(), (float) offset.getZ(), part.sx(), part.sy(), part.sz(), yaw, 0, part.roll()),
                    8);
            ctx().runLater(owner, Math.max(2, life - 6), () -> {
                if (piece.isValid()) {
                    Displays.shrinkAway(ctx(), owner, piece, 5);
                }
            });
        }
        Vfx.helix(ctx(), feet, 1.0, 3.2, 1.5, 2, 18, 0, Particle.DUST_COLOR_TRANSITION, LAPIS_FADE, true);
        Vfx.cloud(ctx(), anchor, Particle.SOUL, 10, 0.6, 0.03, null, true);
    }

    // ================================================================== helpers

    /** Where a display riding {@code body} sits before its transformation: the top of the body's box. */
    private static Location seat(LivingEntity body) {
        return body.getLocation().add(0, body.getHeight(), 0);
    }

    /**
     * Seats {@code part} on {@code body} as a passenger (again, after anything dismounted it), so every client moves it
     * with the body's own model. A mounted part is never teleported (that would dismount it); its transformation
     * holds its offset from the seat.
     */
    private static void ride(LivingEntity body, Display part) {
        if (!part.isValid() || !body.isValid() || body.isDead() || body.equals(part.getVehicle())) {
            return;
        }
        if (part.isInsideVehicle()) {
            part.leaveVehicle();
        }
        Displays.moveTo(part, seat(body));
        body.addPassenger(part);
    }

    /** {@code degrees} wrapped into [-180, 180). */
    private static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    /** {@code from} turned toward {@code to} the short way, by at most {@code max} degrees. */
    private static float turnToward(float from, float to, float max) {
        return from + Math.clamp(wrap(to - from), -max, max);
    }

    /** A word popping up over {@code target}'s head (hidden from those who cannot see it). */
    private void pop(Player owner, LivingEntity target, String mini, float scale) {
        if (!reserve(1)) {
            return;
        }
        Location at = target.getLocation().add(0, target.getHeight() + 2.4, 0);
        TextDisplay text = keep(Displays.spawn(ctx(), owner, at, TextDisplay.class, 26,
                viewer -> !ctx().isConcealedFrom(target, viewer), d -> {
                    d.text(Text.mm(mini));
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setBackgroundColor(Color.fromARGB(110, 0x17, 0x14, 0x1F));
                    d.setShadowed(true);
                    d.setTransformation(Displays.centered(0, 0, 0, 0.3f, 0.3f, 0.3f, 0, 0, 0));
                }));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0, 0, 0), 4);
        ctx().runLater(owner, 18, () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 5);
            }
        });
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
