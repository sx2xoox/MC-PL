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
import net.kyori.adventure.text.Component;
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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
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
 * 최면술사 — puts enemies to sleep. Every hit makes the victim drowsier (slower, and it sleeps longer once hypnotised),
 * the active swings a golden pocket watch and sends a hypnotic wave that puts everyone in front to sleep, and the
 * ultimate hangs a giant watch over the hypnotist whose chime sends everyone around into a dream that turns into a
 * nightmare the moment they wake.
 *
 * <p>Sleep is soft crowd control: a sleeper cannot move, jump or deal melee damage and sees nothing, but any damage
 * wakes it at once (the hypnotist's own waking blow hits harder), and a woken enemy cannot be put back to sleep for a
 * moment. Sleep runs on its own owner-scoped loops and wakes by watching the sleeper's health, so the ultimate needs no
 * grant, ticks or hooks and the gambler may borrow it (without the waking-blow bonus, which is a hook).
 *
 * <p>Look: hypnotic violet and gold. The pocket watch (a clock item) swings on a thin gold chain in front of the
 * hypnotist's face while enchanting glyphs spiral into it; the wave rolls out as violet rings with a gold spiral turning
 * inside them. Sleepers breathe out floating Z letters under a slowly turning lilac halo; dreamers carry a glass dream
 * bubble with something lovely spinning inside, which turns black and bursts into the nightmare when they wake. The
 * ultimate's giant watch ticks over a turning spiral that marks its reach on the ground, then rings like a bell.
 */
public final class HypnotistAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("hypnotist", "최면술사", Material.GOLD_NUGGET)
            .color(TextColor.color(0xB98CFF))
            .difficulty(2)
            .passive("졸음",
                    "근접 공격이나 능력으로 맞힌 적에게 졸음을 1겹 쌓습니다(최대 {drowsy_max}겹, {drowsy_duration}초 유지, "
                            + "같은 적은 {drowsy_interval}초마다). 졸음 1겹마다 적의 이동 속도가 {drowsy_slow}% 느려지고, "
                            + "'최면'에 걸리면 졸음이 사라지는 대신 1겹마다 {drowsy_sleep}초 더 오래 잠듭니다.")
            .active("최면",
                    "{channel_time}초간 회중시계를 흔든 뒤 앞쪽 {cone_range}블록 부채꼴({cone_angle}°)로 최면파를 보냅니다. "
                            + "닿은 적은 {sleep_duration}초간 잠들어 움직이거나 근접 공격으로 피해를 줄 수 없고 앞이 보이지 "
                            + "않지만, 피해를 받으면 곧바로 깨어납니다. 내가 깨우는 일격은 {wake_bonus} 추가 피해를 주고, 깨어난 "
                            + "적은 {sleep_immunity}초 동안 다시 잠들지 않습니다. 시계를 흔드는 동안 공격받거나 아무도 잠들지 "
                            + "않으면 쿨타임이 절반만 돕니다.",
                    11)
            .ultimate("집단 최면",
                    "머리 위에 거대한 회중시계를 띄워 {ult_windup}초간 째깍입니다. 시계가 울리면 반경 {ult_radius}블록 "
                            + "안에서 시계가 보이는 적이 모두 {ult_sleep}초간 깊은 잠에 빠져 꿈을 꿉니다(피해를 받으면 깨어남). "
                            + "꿈에서 깨는 순간 악몽이 덮쳐 {nightmare_damage} 피해를 받고 {nightmare_nausea}초간 멀미에 "
                            + "걸립니다.")
            .param("drowsy_max", 3)
            .param("drowsy_duration", 6)
            .param("drowsy_interval", 0.5)
            .param("drowsy_slow", 5)
            .param("drowsy_sleep", 0.3)
            .param("channel_time", 0.6)
            .param("cone_range", 8)
            .param("cone_angle", 70)
            .param("sleep_duration", 2)
            .param("wake_bonus", 3)
            .param("sleep_immunity", 1.5)
            .param("ult_windup", 1)
            .param("ult_radius", 9)
            .param("ult_sleep", 3)
            .param("nightmare_damage", 4)
            .param("nightmare_nausea", 3)
            .build();

    private static final String VIOLET = "#B98CFF";
    private static final String LILAC = "#E6D3FF";
    private static final String GOLD_TAG = "#FFC93C";
    private static final String NIGHT_TAG = "#5B2A86";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: hypnotic violet and gold
    /** Pale gold glow, hypnotic violet, the deep purple of sleep. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF1C9, 0xB98CFF, 0x2A0F4A);
    private static final Color GOLD = Color.fromRGB(0xFFC93C);
    private static final Color NIGHT = Color.fromRGB(0x3B1259);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition GLOW_SMALL = PALETTE.glow(0.6f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.1f);
    private static final Particle.DustOptions VIOLET_DUST = PALETTE.dust(0.9f);
    private static final Particle.DustOptions LILAC_DUST = new Particle.DustOptions(Color.fromRGB(0xE6D3FF), 0.55f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 0.8f);
    private static final Particle.DustOptions GOLD_SMALL = new Particle.DustOptions(GOLD, 0.5f);
    private static final Particle.DustOptions THOUGHT_DUST = new Particle.DustOptions(Color.fromRGB(0xF4F0FF), 0.6f);
    private static final Particle.DustTransition NIGHTMARE_DUST = new Particle.DustTransition(Color.fromRGB(0x7A2BB5),
            Color.fromRGB(0x0A0410), 1.3f);

    /** Block and item types of the visuals; block data and items are created on use (they need a running server). */
    private static final Material WATCH_ITEM = Material.CLOCK;
    private static final Material CHAIN_BLOCK = Material.GOLD_BLOCK;
    private static final Material BUBBLE_BLOCK = Material.WHITE_STAINED_GLASS;
    private static final Material NIGHTMARE_BLOCK = Material.BLACK_STAINED_GLASS;
    private static final Material NIGHTMARE_ITEM = Material.WITHER_ROSE;
    /** The sweet things a hypnotised enemy dreams of. */
    private static final Material[] DREAMS = {Material.CAKE, Material.GOLDEN_APPLE, Material.DIAMOND, Material.COOKIE,
            Material.POPPY, Material.EMERALD, Material.SWEET_BERRIES, Material.HONEY_BOTTLE};

    /** Display entities one hypnotist keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    private static final double REFUND = 0.5;

    // passive
    private static final long LABEL_PERIOD = 2;
    private static final long EXPIRE_PERIOD = 5;
    private static final float LABEL_SCALE = 0.6f;
    private static final double LABEL_ABOVE = 0.45;

    // active: the pocket watch
    private static final double PIVOT_FORWARD = 0.75;
    private static final double PIVOT_DROP = 0.2;
    private static final float WATCH_SCALE = 0.55f;
    private static final float CHAIN_LENGTH = 0.5f;
    private static final float CHAIN_WIDTH = 0.025f;
    private static final float SWING_AMPLITUDE = 38;
    private static final double SWING_PERIOD = 12;
    private static final long TICK_TOCK = 6;
    private static final int WAVE_TICKS = 7;
    private static final double WAVE_MAX_RADIUS = 3.2;

    // sleep
    private static final long Z_PERIOD = 9;
    private static final long Z_LIFE = 22;
    private static final double HEAD_GAP = 0.15;
    private static final double BUBBLE_ABOVE = 1.05;
    private static final float BUBBLE_SIZE = 0.55f;
    private static final float DREAM_SCALE = 0.32f;
    private static final long NIGHTMARE_BURST = 5;
    /** Sleep never outlasts this, whatever the configured numbers (a safety bound for the sleep loop). */
    private static final long MAX_SLEEP_TICKS = 20 * 30;
    private static final double REST_FALL = -Motion.GRAVITY * Motion.DRAG;

    // ultimate: the giant watch
    private static final double GIANT_ABOVE = 2.9;
    private static final float GIANT_SCALE = 2.4f;
    private static final float GIANT_CHAIN = 1.3f;
    private static final float GIANT_SWING = 24;
    private static final float GIANT_SPIN = 4;
    private static final int SPIRAL_ARMS = 2;
    private static final int AFTERGLOW_TICKS = 16;

    private final Map<UUID, Drowsy> drowsy = new HashMap<>();
    private final Map<UUID, Sleeper> sleepers = new HashMap<>();
    private final Map<UUID, Long> immuneUntil = new HashMap<>();
    private final List<Display> live = new ArrayList<>();
    private @Nullable Channel channel;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        for (Drowsy state : drowsy.values()) {
            removeLabel(state);
        }
        drowsy.clear();
        for (Sleeper sleeper : new ArrayList<>(sleepers.values())) {
            sleeper.clearEyes();
        }
        sleepers.clear();
        immuneUntil.clear();
        channel = null;
    }

    // ================================================================== passive: drowsiness

    /** One enemy's drowsiness: its stacks, when they wear off, the slow they cause and the Z label over its head. */
    private static final class Drowsy {
        final LivingEntity victim;
        int stacks;
        long expires;
        long lastStack = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
        @Nullable NamespacedKey slow;
        @Nullable TextDisplay label;
        boolean labelHidden;

        Drowsy(LivingEntity victim) {
            this.victim = victim;
        }
    }

    /** The hypnotist's waking blow: a sleeping enemy struck by the hypnotist takes the bonus. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !sleepers.containsKey(victim.getUniqueId()) || !isOwnHit(event)) {
            return;
        }
        double bonus = Math.max(0, params().num("wake_bonus"));
        if (bonus > 0) {
            event.setDamage(event.getDamage() + bonus);
        }
    }

    /** A hit landed: it wakes a sleeper (rudely) and makes the victim drowsier. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnHit(event)) {
            return;
        }
        Sleeper sleeper = sleepers.get(victim.getUniqueId());
        if (sleeper != null) {
            sleeper.wake(Wake.STRUCK);
        }
        addDrowsiness(victim);
    }

    /** True for a hit the hypnotist itself dealt (melee, its arrows, its ability damage). */
    private boolean isOwnHit(EntityDamageByEntityEvent event) {
        return player().equals(ctx().resolveAttacker(event.getDamager()));
    }

    private void addDrowsiness(LivingEntity victim) {
        if (!Combat.isTargetable(ctx(), player(), victim)) {
            return;
        }
        int max = Math.max(0, params().integer("drowsy_max"));
        if (max <= 0) {
            return;
        }
        long now = ctx().currentTick();
        Drowsy state = drowsy.computeIfAbsent(victim.getUniqueId(), id -> new Drowsy(victim));
        state.expires = now + Math.max(1, params().ticks("drowsy_duration"));
        if (now - state.lastStack < params().ticks("drowsy_interval") || state.stacks >= max) {
            return;
        }
        state.lastStack = now;
        state.stacks++;
        applySlow(state);
        showLabel(state);
        drowsyFx(state);
    }

    /** Replaces the victim's drowsiness slow with one for its current stacks. */
    private void applySlow(Drowsy state) {
        if (state.slow != null) {
            ctx().removeModifier(state.victim, state.slow);
            state.slow = null;
        }
        double slow = Math.max(0, params().num("drowsy_slow")) / 100.0 * state.stacks;
        if (slow > 0 && state.stacks > 0) {
            state.slow = ctx().addModifier(player(), state.victim, Attribute.MOVEMENT_SPEED, -Math.min(1, slow),
                    Operation.MULTIPLY_SCALAR_1, 0);
        }
    }

    /** A gold mote sinks into the victim's head and the lullaby chimes a little higher for every stack. */
    private void drowsyFx(Drowsy state) {
        LivingEntity victim = state.victim;
        if (isHiddenFromOwner(victim)) {
            return;
        }
        Location head = head(victim);
        Vfx.ring(ctx(), head, 0.4, 8, state.stacks * 0.8, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
        Vfx.cloud(ctx(), head, Particle.ENCHANT, 6, 0.2, 0.6, null, false);
        Vfx.dot(head.clone().add(0, 0.3, 0), Particle.WAX_OFF, null, false);
        Fx.sound(head, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 0.9f + 0.18f * state.stacks + Fx.jitter(0.05f));
        int max = Math.max(1, params().integer("drowsy_max"));
        if (state.stacks >= max) {
            Fx.sound(head, Sound.ENTITY_FOX_SLEEP, 0.6f, 1.1f + Fx.jitter(0.05f));
        }
        player().sendActionBar(Text.mm("<" + VIOLET + ">졸음</" + VIOLET + "> <" + LILAC + ">" + "z".repeat(state.stacks)
                + "</" + LILAC + "><gray> " + state.stacks + "/" + max + "</gray>"));
    }

    private void showLabel(Drowsy state) {
        TextDisplay label = state.label;
        Component text = drowsyText(state.stacks);
        if (label != null && label.isValid()) {
            label.text(text);
            return;
        }
        if (!room(1)) {
            return;
        }
        label = keep(Displays.text(ctx(), player(), labelSpot(state.victim), text, LABEL_SCALE, 0));
        label.setTeleportDuration((int) LABEL_PERIOD);
        state.label = label;
        state.labelHidden = false;
        updateLabelVisibility(state);
    }

    private static Component drowsyText(int stacks) {
        StringBuilder text = new StringBuilder("<b>");
        for (int i = 0; i < stacks; i++) {
            text.append(i % 2 == 0 ? "<" + LILAC + ">z</" + LILAC + ">" : "<" + VIOLET + ">Z</" + VIOLET + ">");
        }
        return Text.mm(text.append("</b>").toString());
    }

    private static Location labelSpot(LivingEntity victim) {
        return victim.getLocation().add(0, victim.getHeight() + LABEL_ABOVE, 0);
    }

    /** The label hides while its victim sleeps (the floating Z letters take over) or is hidden from the hypnotist. */
    private void updateLabelVisibility(Drowsy state) {
        TextDisplay label = state.label;
        if (label == null || !label.isValid()) {
            return;
        }
        boolean hide = isHiddenFromOwner(state.victim) || sleepers.containsKey(state.victim.getUniqueId());
        if (hide != state.labelHidden) {
            state.labelHidden = hide;
            label.setViewRange(hide ? 0f : 1.5f);
        }
    }

    private void removeLabel(Drowsy state) {
        TextDisplay label = state.label;
        if (label != null && label.isValid()) {
            label.remove();
        }
        state.label = null;
    }

    /** Drowsy labels follow their victims; drowsiness wears off. */
    @Override
    public void onTick(long tick) {
        if (drowsy.isEmpty()) {
            return;
        }
        boolean expire = tick % EXPIRE_PERIOD == 0;
        boolean follow = tick % LABEL_PERIOD == 0;
        if (!expire && !follow) {
            return;
        }
        long now = ctx().currentTick();
        for (Iterator<Drowsy> it = drowsy.values().iterator(); it.hasNext(); ) {
            Drowsy state = it.next();
            if (expire && (now >= state.expires || !Combat.isTargetable(ctx(), player(), state.victim))) {
                clearDrowsy(state);
                it.remove();
                continue;
            }
            if (follow) {
                TextDisplay label = state.label;
                if (label != null && label.isValid() && label.getWorld().equals(state.victim.getWorld())) {
                    Displays.moveTo(label, labelSpot(state.victim));
                }
                updateLabelVisibility(state);
            }
        }
    }

    private void clearDrowsy(Drowsy state) {
        if (state.slow != null) {
            ctx().removeModifier(state.victim, state.slow);
            state.slow = null;
        }
        TextDisplay label = state.label;
        if (label != null && label.isValid()) {
            Displays.shrinkAway(ctx(), player(), label, 4);
        }
        state.label = null;
        state.stacks = 0;
    }

    /** Takes the victim's drowsiness away (it falls asleep) and returns how many stacks it had. */
    private int consumeDrowsiness(LivingEntity victim) {
        Drowsy state = drowsy.remove(victim.getUniqueId());
        if (state == null) {
            return 0;
        }
        int stacks = state.stacks;
        clearDrowsy(state);
        return stacks;
    }

    // ================================================================== active: hypnosis

    @Override
    public ActivationResult onActive() {
        Channel swing = new Channel(player());
        channel = swing;
        swing.start();
        return ActivationResult.success();
    }

    /** Being struck while the watch swings breaks the hypnotist's concentration. */
    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        Channel swing = channel;
        if (swing != null && !swing.done && !event.isCancelled() && event.getFinalDamage() > 0) {
            swing.interrupted = true;
        }
    }

    /**
     * The pocket watch swinging on its chain in front of the hypnotist's face for the channel time, then the hypnotic
     * wave. Being struck meanwhile breaks it off; either failure refunds half the cooldown.
     */
    private final class Channel implements LongPredicate {

        private final Player owner;
        private final long ticks;
        private @Nullable ItemDisplay watch;
        private @Nullable BlockDisplay chain;
        boolean interrupted;
        boolean done;

        Channel(Player owner) {
            this.owner = owner;
            this.ticks = Math.max(1, params().ticks("channel_time"));
        }

        void start() {
            Location pivot = pivot();
            float yaw = owner.getLocation().getYaw();
            if (room(2)) {
                watch = keep(Displays.item(ctx(), owner, pivot, new ItemStack(WATCH_ITEM),
                        watchPose(0.05f, CHAIN_LENGTH, yaw, 0), ticks + 12));
                watch.setTeleportDuration(1);
                chain = keep(Displays.block(ctx(), owner, pivot, CHAIN_BLOCK.createBlockData(),
                        chainPose(CHAIN_WIDTH, CHAIN_LENGTH, yaw, 0), ticks + 12));
                chain.setTeleportDuration(1);
            }
            Fx.sound(pivot, Sound.ITEM_ARMOR_EQUIP_GOLD, 0.8f, 1.4f + Fx.jitter(0.05f));
            Fx.sound(pivot, Sound.BLOCK_CHAIN_PLACE, 0.6f, 1.6f + Fx.jitter(0.1f));
            Fx.sound(pivot, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 0.5f, 1.4f + Fx.jitter(0.05f));
            Loop.repeat(ctx(), owner, 0, 1, ticks, this, this::release);
        }

        @Override
        public boolean test(long tick) {
            if (interrupted) {
                return false;
            }
            float yaw = owner.getLocation().getYaw();
            float swing = (float) (SWING_AMPLITUDE * Math.sin(TAU * (tick + 1) / SWING_PERIOD));
            Location pivot = pivot();
            ItemDisplay face = watch;
            if (face != null && face.isValid()) {
                Displays.moveTo(face, pivot);
                float grown = WATCH_SCALE * (float) Math.min(1, (tick + 1) / 3.0);
                Displays.animate(face, watchPose(grown, CHAIN_LENGTH, yaw, swing), 1);
            }
            BlockDisplay links = chain;
            if (links != null && links.isValid()) {
                Displays.moveTo(links, pivot);
                Displays.animate(links, chainPose(CHAIN_WIDTH, CHAIN_LENGTH, yaw, swing), 1);
            }
            Location center = hanging(pivot, CHAIN_LENGTH, yaw, swing);
            // Glyphs spiral into the watch, a faint violet ring pulses around it, gold glints at the turn.
            pivot.getWorld().spawnParticle(Particle.ENCHANT, center, Vfx.n(ctx(), 4), 0.6, 0.4, 0.6, 1.0);
            if (tick % 3 == 0) {
                ringAround(center, Motion.horizontalFacing(owner.getLocation()), 0.35 + 0.05 * (tick % 6), 10,
                        tick * 0.5, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
            }
            if (Math.abs(swing) > SWING_AMPLITUDE * 0.9) {
                Vfx.dot(center, Particle.WAX_OFF, null, false);
            }
            if (tick % TICK_TOCK == 0) {
                boolean tock = (tick / TICK_TOCK) % 2 == 1;
                Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, tock ? 1.3f : 1.7f);
                Fx.sound(center, Sound.BLOCK_COMPARATOR_CLICK, 0.4f, tock ? 0.8f : 1.1f);
            }
            return true;
        }

        /** In front of the hypnotist's face, a little below the eyes: where the chain is held. */
        private Location pivot() {
            Location eye = owner.getEyeLocation();
            Vector facing = Motion.horizontalFacing(owner.getLocation());
            return Displays.unrotated(eye.add(facing.multiply(PIVOT_FORWARD)).subtract(0, PIVOT_DROP, 0));
        }

        private void release() {
            done = true;
            if (channel == this) {
                channel = null;
            }
            ItemDisplay face = watch;
            BlockDisplay links = chain;
            if (interrupted) {
                Location pivot = pivot();
                Vfx.cloud(ctx(), pivot, Particle.SMOKE, 6, 0.2, 0.02, null, false);
                Vfx.cloud(ctx(), pivot, Particle.DUST, 6, 0.25, 0, VIOLET_DUST, false);
                Fx.sound(pivot, Sound.BLOCK_CHAIN_BREAK, 0.8f, 1.2f + Fx.jitter(0.1f));
                Fx.sound(pivot, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 0.6f);
                remove(face);
                remove(links);
                refund("<gray>집중이 깨져 최면이 풀렸습니다</gray>");
                return;
            }
            if (face != null && face.isValid()) {
                Displays.shrinkAway(ctx(), owner, face, 4);
            }
            if (links != null && links.isValid()) {
                Displays.shrinkAway(ctx(), owner, links, 4);
            }
            wave();
        }

        /** The hypnotic wave: everyone in the cone who sees it falls asleep. */
        private void wave() {
            Location eye = owner.getEyeLocation();
            Vector look = eye.getDirection();
            if (look.lengthSquared() < 1.0E-6) {
                look = Motion.horizontalFacing(owner.getLocation());
            }
            look.normalize();
            double range = Math.max(0.5, params().num("cone_range"));
            double half = Math.toRadians(Math.clamp(params().num("cone_angle"), 1, 360) / 2);
            long base = Math.max(1, params().ticks("sleep_duration"));
            long perStack = params().ticks("drowsy_sleep");
            drawWave(eye.clone(), look.clone(), range, half);
            Fx.sound(eye, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 1.2f + Fx.jitter(0.05f));
            Fx.sound(eye, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(eye, Sound.BLOCK_BELL_RESONATE, 0.5f, 1.6f);
            Fx.sound(eye, Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.7f + Fx.jitter(0.05f));

            int slept = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, eye, range)) {
                if (isHiddenFromOwner(enemy)) {
                    continue;
                }
                Location body = Fx.body(enemy);
                Vector to = body.toVector().subtract(eye.toVector());
                double distance = to.length();
                boolean inside = distance < 0.8 || to.multiply(1 / distance).angle(look) <= half;
                if (!inside || !Spots.clearPath(eye, enemy.getEyeLocation()) && !Spots.clearPath(eye, body)) {
                    continue;
                }
                if (isImmune(enemy)) {
                    resisted(enemy);
                    continue;
                }
                int stacks = consumeDrowsiness(enemy);
                long duration = base + perStack * stacks;
                if (fallAsleep(owner, enemy, duration, false)) {
                    slept++;
                    for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                        Vfx.trail(eye.clone().add(look.clone().multiply(PIVOT_FORWARD)), head(enemy),
                                i % 2 == 0 ? GOLD : PALETTE.body(), 6 + 2 * i, false);
                    }
                }
            }
            if (slept == 0) {
                refund("<gray>최면파에 걸린 적이 없습니다</gray>");
            } else {
                owner.sendActionBar(Text.mm("<" + VIOLET + "><b>최면</b></" + VIOLET + "> <white>" + slept
                        + "명</white><gray> 잠듦</gray>"));
            }
        }

        private void refund(String message) {
            double left = ctx().cooldownRemaining(owner);
            if (left > 0) {
                ctx().reduceCooldown(owner, left * REFUND);
            }
            owner.sendActionBar(Text.mm(message + " <dark_gray>(쿨타임 절반 회복)</dark_gray>"));
        }
    }

    /**
     * The wave rolling out: violet rings across the cone with a two-armed gold spiral turning inside each, and glyphs
     * drifting along.
     */
    private void drawWave(Location eye, Vector look, double range, double half) {
        Player owner = player();
        double tan = Math.tan(Math.min(half, Math.toRadians(80)));
        Location mouth = eye.clone().add(look.clone().multiply(PIVOT_FORWARD));
        Loop.repeat(ctx(), owner, 0, 1, WAVE_TICKS, run -> {
            double t = (run + 1.0) / WAVE_TICKS;
            double distance = PIVOT_FORWARD + (range - PIVOT_FORWARD) * t;
            Location center = eye.clone().add(look.clone().multiply(distance));
            double radius = Math.min(WAVE_MAX_RADIUS, Math.max(0.3, distance * tan));
            ringAround(center, look, radius, (int) Math.max(10, radius * 7), run * 0.4,
                    Particle.DUST_COLOR_TRANSITION, GLOW, false);
            spiral(center, look, radius * 0.85, run * 0.9);
            if (run % 2 == 0) {
                center.getWorld().spawnParticle(Particle.ENCHANT, center, Vfx.n(ctx(), 6), radius * 0.4, radius * 0.4,
                        radius * 0.4, 0.4);
            }
            return true;
        });
        Vfx.dot(mouth, Particle.FLASH, PALETTE.body(), false);
    }

    /** A two-armed spiral on the disc of {@code radius} around {@code axis} at {@code center}. */
    private void spiral(Location center, Vector axis, double radius, double phase) {
        Vector[] frame = frame(axis);
        int points = Vfx.n(ctx(), 7);
        World world = center.getWorld();
        for (int arm = 0; arm < SPIRAL_ARMS; arm++) {
            for (int k = 1; k <= points; k++) {
                double r = radius * k / points;
                double angle = phase + arm * Math.PI + k * 0.75;
                Location at = center.clone().add(frame[0].clone().multiply(Math.cos(angle) * r))
                        .add(frame[1].clone().multiply(Math.sin(angle) * r));
                world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, arm == 0 ? GOLD_SMALL : VIOLET_DUST);
            }
        }
    }

    // ================================================================== sleep

    private enum Wake {
        /** Hurt by anything but the hypnotist. */
        HURT,
        /** Struck by the hypnotist itself: the rude awakening. */
        STRUCK,
        /** Slept its time out. */
        TIMEOUT,
        /** Died, left or stopped being an enemy. */
        GONE
    }

    private boolean isImmune(LivingEntity victim) {
        Long until = immuneUntil.get(victim.getUniqueId());
        return until != null && ctx().currentTick() < until;
    }

    private boolean isHiddenFromOwner(LivingEntity entity) {
        return entity instanceof Player hidden && !player().canSee(hidden);
    }

    /**
     * Puts {@code victim} to sleep for {@code ticks} (a dream if {@code dream}). An enemy already asleep sleeps on for
     * the longer of the two and starts dreaming if this sleep is a dream.
     *
     * @return whether the victim is asleep now
     */
    private boolean fallAsleep(Player owner, LivingEntity victim, long ticks, boolean dream) {
        if (!Combat.isTargetable(ctx(), owner, victim) || ticks <= 0) {
            return false;
        }
        long duration = Math.min(MAX_SLEEP_TICKS, ticks);
        Sleeper current = sleepers.get(victim.getUniqueId());
        if (current != null && !current.done) {
            current.extend(duration, dream);
            return true;
        }
        if (isImmune(victim)) {
            resisted(victim);
            return false;
        }
        Sleeper sleeper = new Sleeper(owner, victim, duration, dream);
        sleepers.put(victim.getUniqueId(), sleeper);
        sleeper.start();
        return true;
    }

    /** A woken enemy shrugs the hypnosis off: a gold glint and a dull chime. */
    private void resisted(LivingEntity victim) {
        Location head = head(victim);
        Vfx.cloud(ctx(), head, Particle.WAX_OFF, 4, 0.2, 0.05, null, false);
        Vfx.ring(ctx(), head, 0.35, 8, 0, Particle.DUST, GOLD_SMALL, false);
        Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1.4f);
    }

    /**
     * One sleeping enemy: rooted (no walking, no jumping), its melee harmless and its eyes closed (blindness), until any
     * damage wakes it or the time is up. Health is watched every tick, so every source of damage wakes it.
     */
    private final class Sleeper implements LongPredicate {

        private final Player owner;
        private final LivingEntity victim;
        private final List<NamespacedKey> modifiers = new ArrayList<>();
        private final Material dreamItem;
        private long endTick;
        private boolean dream;
        private double lastHealth;
        private boolean hidden;
        private long run;
        private @Nullable BlockDisplay bubble;
        private @Nullable ItemDisplay vision;
        boolean done;

        Sleeper(Player owner, LivingEntity victim, long ticks, boolean dream) {
            this.owner = owner;
            this.victim = victim;
            this.endTick = ctx().currentTick() + ticks;
            this.dream = dream;
            this.lastHealth = victim.getHealth() + victim.getAbsorptionAmount();
            this.dreamItem = DREAMS[ThreadLocalRandom.current().nextInt(DREAMS.length)];
        }

        void start() {
            modifiers.add(ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1,
                    0));
            modifiers.add(ctx().addModifier(owner, victim, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1,
                    0));
            modifiers.add(ctx().addModifier(owner, victim, Attribute.ATTACK_DAMAGE, -1.0, Operation.MULTIPLY_SCALAR_1,
                    0));
            // Its momentum dies with its will: a sleeper keeps falling but stops running.
            if (victim instanceof Player) {
                victim.setVelocity(new Vector(0, REST_FALL, 0));
            } else {
                victim.setVelocity(new Vector(0, Math.min(0, victim.getVelocity().getY()), 0));
            }
            closeEyes();
            hidden = isHiddenFromOwner(victim);
            if (dream) {
                startDream();
            }
            fallFx();
            Loop.repeat(ctx(), owner, 1, 1, MAX_SLEEP_TICKS + 1, this, () -> wake(Wake.TIMEOUT));
        }

        /** Sleeps on for at least {@code ticks} more; a dream turns this sleep into a dream. */
        void extend(long ticks, boolean nowDreaming) {
            endTick = Math.max(endTick, ctx().currentTick() + ticks);
            closeEyes();
            if (nowDreaming && !dream) {
                dream = true;
                startDream();
            }
            fallFx();
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            if (!Combat.isTargetable(ctx(), owner, victim)) {
                wake(Wake.GONE);
                return false;
            }
            double health = victim.getHealth() + victim.getAbsorptionAmount();
            if (health < lastHealth - 1.0E-6) {
                wake(Wake.HURT);
                return false;
            }
            lastHealth = health;
            if (ctx().currentTick() >= endTick) {
                wake(Wake.TIMEOUT);
                return false;
            }
            run++;
            drawSleep();
            return true;
        }

        /** Eyes shut: blindness (marked ambient, particle-less, so waking removes this one and no other). */
        private void closeEyes() {
            long left = Math.max(1, endTick - ctx().currentTick()) + 10;
            victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, (int) Math.min(Integer.MAX_VALUE, left),
                    0, true, false, true));
        }

        void clearEyes() {
            PotionEffect effect = victim.getPotionEffect(PotionEffectType.BLINDNESS);
            if (effect != null && effect.isAmbient() && !effect.hasParticles() && effect.getAmplifier() == 0) {
                victim.removePotionEffect(PotionEffectType.BLINDNESS);
            }
        }

        void wake(Wake why) {
            if (done) {
                return;
            }
            done = true;
            sleepers.remove(victim.getUniqueId(), this);
            for (NamespacedKey key : modifiers) {
                ctx().removeModifier(victim, key);
            }
            modifiers.clear();
            clearEyes();
            if (why == Wake.GONE) {
                remove(bubble);
                remove(vision);
                return;
            }
            immuneUntil.put(victim.getUniqueId(), ctx().currentTick() + params().ticks("sleep_immunity"));
            wakeFx(why);
            if (dream) {
                nightmare();
            } else {
                remove(bubble);
                remove(vision);
            }
        }

        private Location bubbleSpot() {
            return victim.getLocation().add(0, victim.getHeight() + BUBBLE_ABOVE, 0);
        }

        private void startDream() {
            if (!room(2)) {
                return;
            }
            Location at = Displays.unrotated(bubbleSpot());
            long life = Math.max(1, endTick - ctx().currentTick()) + 40;
            BlockDisplay glass = keep(Displays.block(ctx(), owner, at, BUBBLE_BLOCK.createBlockData(),
                    Displays.scaled(0.05f), life));
            glass.setTeleportDuration(2);
            Displays.animateNextTick(ctx(), owner, glass, Displays.scaled(BUBBLE_SIZE), 6);
            bubble = glass;
            ItemDisplay inside = keep(Displays.item(ctx(), owner, at, new ItemStack(dreamItem),
                    Displays.centered(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), life));
            inside.setTeleportDuration(2);
            Displays.animateNextTick(ctx(), owner, inside,
                    Displays.centered(0, 0, 0, DREAM_SCALE, DREAM_SCALE, DREAM_SCALE, 0, 0, 0), 6);
            vision = inside;
            if (hidden) {
                glass.setViewRange(0f);
                inside.setViewRange(0f);
            }
            Location head = head(victim);
            Vfx.cloud(ctx(), head.clone().add(0, 0.6, 0), Particle.END_ROD, 5, 0.2, 0.02, null, false);
            Fx.sound(head, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.6f + Fx.jitter(0.1f));
        }

        /** Floating Z letters, a turning lilac halo and (dreaming) the bubble with its slowly spinning dream. */
        private void drawSleep() {
            boolean nowHidden = isHiddenFromOwner(victim);
            if (nowHidden != hidden) {
                hidden = nowHidden;
                for (Display display : new Display[] {bubble, vision}) {
                    if (display != null && display.isValid()) {
                        display.setViewRange(hidden ? 0f : 1.5f);
                    }
                }
            }
            if (run % 2 == 0) {
                Location spot = bubbleSpot();
                if (bubble != null && bubble.isValid()) {
                    Displays.moveTo(bubble, spot);
                }
                if (vision != null && vision.isValid()) {
                    Displays.moveTo(vision, spot);
                }
            }
            if (run % 4 == 0) {
                float turn = run * 6f;
                if (bubble != null && bubble.isValid()) {
                    Displays.animate(bubble, Displays.transform(0, 0, 0, BUBBLE_SIZE, BUBBLE_SIZE, BUBBLE_SIZE, turn,
                            8f * (float) Math.sin(run * 0.2), 0), 4);
                }
                if (vision != null && vision.isValid()) {
                    float bob = 0.04f * (float) Math.sin(run * 0.3);
                    Displays.animate(vision, Displays.centered(0, bob, 0, DREAM_SCALE, DREAM_SCALE, DREAM_SCALE,
                            -turn * 3, 0, 0), 4);
                }
            }
            if (hidden) {
                return;
            }
            Location head = head(victim);
            if (run % 3 == 0) {
                Vfx.ring(ctx(), head.clone().add(0, 0.2, 0), 0.32, 7, run * 0.35, Particle.DUST, LILAC_DUST, false);
            }
            if (run % Z_PERIOD == 1) {
                floatZ(victim, (int) (run / Z_PERIOD));
            }
            if (dream && run % 4 == 2) {
                // Thought bubbles rising from the head to the dream.
                Location top = bubbleSpot();
                for (int i = 1; i <= 2; i++) {
                    Location at = head.clone().add(top.clone().subtract(head).toVector().multiply(i / 3.0));
                    Vfx.dot(at, Particle.DUST, THOUGHT_DUST, false);
                }
            }
        }

        /** Falling asleep: the eyes roll, a soft lullaby, lilac dust sinking down the body. */
        private void fallFx() {
            if (hidden) {
                return;
            }
            Location head = head(victim);
            Vfx.helix(ctx(), victim.getLocation(), 0.45, victim.getHeight() + 0.2, 1.5, 2, 9, run * 0.3,
                    Particle.DUST_COLOR_TRANSITION, FADE, false);
            Vfx.cloud(ctx(), head, Particle.ENCHANT, 10, 0.3, 0.8, null, false);
            Fx.sound(head, Sound.ENTITY_FOX_SLEEP, 1.0f, 0.85f + Fx.jitter(0.05f));
            Fx.sound(head, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 0.7f + Fx.jitter(0.05f));
            floatZ(victim, 0);
        }

        /** Waking up: a gold "!" pops over a rudely woken head; a slow yawn of dust for a timed-out sleep. */
        private void wakeFx(Wake why) {
            if (hidden || !victim.isValid()) {
                return;
            }
            Location head = head(victim);
            if (why == Wake.TIMEOUT) {
                Vfx.cloud(ctx(), head, Particle.CLOUD, 4, 0.2, 0.02, null, false);
                Vfx.cloud(ctx(), head, Particle.DUST, 6, 0.25, 0, LILAC_DUST, false);
                Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.2f + Fx.jitter(0.05f));
                return;
            }
            boolean rude = why == Wake.STRUCK;
            Vfx.dot(head, Particle.FLASH, rude ? GOLD : PALETTE.core(), false);
            Vfx.cloud(ctx(), head, Particle.WAX_OFF, rude ? 8 : 4, 0.25, 0.1, null, false);
            if (rude) {
                Vfx.cloud(ctx(), Fx.body(victim), Particle.ENCHANTED_HIT, 10, 0.3, 0.2, null, false);
                Fx.sound(head, Sound.BLOCK_BELL_USE, 0.9f, 1.8f + Fx.jitter(0.05f));
                Fx.sound(head, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.1f + Fx.jitter(0.08f));
                owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>기상!</b></" + GOLD_TAG + "> <gray>깨우는 일격 +"
                        + AbilityInfo.formatNumber(params().num("wake_bonus")) + "</gray>"));
            } else {
                Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.6f + Fx.jitter(0.08f));
            }
            popText(head.clone().add(0, 0.35, 0), "<b><" + GOLD_TAG + ">!</" + GOLD_TAG + "></b>", 1.4f, 14);
        }

        /** The dream turns black and bursts: the nightmare's damage and the nausea it leaves. */
        private void nightmare() {
            BlockDisplay glass = bubble;
            ItemDisplay inside = vision;
            if (glass != null && glass.isValid()) {
                glass.setBlock(NIGHTMARE_BLOCK.createBlockData());
                Displays.animate(glass, Displays.transform(0, 0, 0, BUBBLE_SIZE * 1.5f, BUBBLE_SIZE * 1.5f,
                        BUBBLE_SIZE * 1.5f, 45, 20, 0), (int) NIGHTMARE_BURST);
            }
            if (inside != null && inside.isValid()) {
                inside.setItemStack(new ItemStack(NIGHTMARE_ITEM));
                Displays.animate(inside, Displays.centered(0, 0, 0, DREAM_SCALE * 1.6f, DREAM_SCALE * 1.6f,
                        DREAM_SCALE * 1.6f, 180, 0, 0), (int) NIGHTMARE_BURST);
            }
            Location spot = bubbleSpot();
            if (!hidden) {
                Vfx.cloud(ctx(), spot, Particle.SQUID_INK, 6, 0.2, 0.02, null, false);
                Fx.sound(spot, Sound.ENTITY_PHANTOM_AMBIENT, 1.0f, 0.6f + Fx.jitter(0.05f));
                Fx.sound(spot, Sound.AMBIENT_CAVE, 0.7f, 1.3f);
            }
            // The waking hit is still being processed when a hook wakes the sleeper: the nightmare follows a tick later.
            ctx().runLater(owner, 1, () -> {
                if (!Combat.isTargetable(ctx(), owner, victim)) {
                    return;
                }
                Combat.hit(ctx(), owner, victim, Math.max(0, params().num("nightmare_damage")));
                long nausea = params().ticks("nightmare_nausea");
                if (nausea > 0 && Combat.isTargetable(ctx(), owner, victim)) {
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA,
                            (int) Math.min(Integer.MAX_VALUE, nausea), 0, false, true, true));
                }
            });
            ctx().runLater(owner, NIGHTMARE_BURST, () -> {
                remove(glass);
                remove(inside);
                if (hidden || !victim.isValid()) {
                    return;
                }
                Location burst = bubbleSpot();
                World world = burst.getWorld();
                Vfx.dot(burst, Particle.FLASH, NIGHT, false);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, burst, Vfx.n(ctx(), 18), 0.35, 0.35, 0.35, 0,
                        NIGHTMARE_DUST);
                world.spawnParticle(Particle.SQUID_INK, burst, Vfx.n(ctx(), 10), 0.2, 0.2, 0.2, 0.08);
                world.spawnParticle(Particle.SOUL, burst, Vfx.n(ctx(), 5), 0.25, 0.2, 0.25, 0.03);
                Vfx.shockwave(ctx(), owner, victim.getLocation().add(0, 0.1, 0), 0.3, 1.4, 4, Particle.DUST_COLOR_TRANSITION,
                        NIGHTMARE_DUST, false);
                Fx.sound(burst, Sound.ENTITY_PHANTOM_BITE, 1.0f, 0.6f + Fx.jitter(0.05f));
                Fx.sound(burst, Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 1.2f);
                Fx.sound(burst, Sound.BLOCK_GLASS_BREAK, 0.8f, 0.6f + Fx.jitter(0.05f));
                popText(burst.clone().add(0, 0.2, 0), "<b><gradient:#7A2BB5:" + NIGHT_TAG + ">악몽!</gradient></b>",
                        1.0f, 18);
            });
        }
    }

    /** A Z drifting up from the sleeper's head, growing and fading. */
    private void floatZ(LivingEntity victim, int index) {
        if (!room(1)) {
            Vfx.dot(head(victim).add(0, 0.4, 0), Particle.DUST, LILAC_DUST, false);
            return;
        }
        Player owner = player();
        double angle = index * 2.1;
        Location at = head(victim).add(Math.cos(angle) * 0.25, 0.15, Math.sin(angle) * 0.25);
        boolean big = index % 3 == 2;
        String letter = big ? "<b><" + VIOLET + ">Z</" + VIOLET + "></b>" : "<b><" + LILAC + ">z</" + LILAC + "></b>";
        TextDisplay z = keep(Displays.text(ctx(), owner, at, Text.mm(letter), 0.45f, Z_LIFE));
        float drift = (float) (Math.cos(angle) * 0.25);
        float scale = big ? 1.1f : 0.85f;
        Displays.animateNextTick(ctx(), owner, z, Displays.centered(drift, 0.95f, 0, scale, scale, scale, 0, 0, 0),
                (int) Z_LIFE - 6);
        ctx().runLater(owner, Z_LIFE - 5, () -> {
            if (z.isValid()) {
                Displays.shrinkAway(ctx(), owner, z, 4);
            }
        });
    }

    /** A word popping up over a head and shrinking away. */
    private void popText(Location at, String miniMessage, float scale, long life) {
        if (!room(1)) {
            return;
        }
        Player owner = player();
        TextDisplay text = keep(Displays.text(ctx(), owner, at, Text.mm(miniMessage), scale * 0.5f, life));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0, 0, 0), 4);
        ctx().runLater(owner, Math.max(2, life - 5), () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 4);
            }
        });
    }

    private static Location head(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() + HEAD_GAP, 0);
    }

    // ================================================================== ultimate: mass hypnosis

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = Math.max(1, params().num("ult_radius"));
        boolean anyone = false;
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), radius)) {
            if (!isHiddenFromOwner(enemy)) {
                anyone = true;
                break;
            }
        }
        if (!anyone) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        new MassHypnosis(owner, radius).start();
        return ActivationResult.success();
    }

    /**
     * The giant pocket watch over the hypnotist ticks for the wind-up while a turning spiral marks its reach, then rings:
     * every enemy in reach that can see the watch falls into a dream.
     */
    private final class MassHypnosis implements LongPredicate {

        private final Player owner;
        private final double radius;
        private final long windup;
        private @Nullable ItemDisplay watch;
        private @Nullable BlockDisplay chain;
        private float spin;

        MassHypnosis(Player owner, double radius) {
            this.owner = owner;
            this.radius = radius;
            this.windup = Math.max(1, params().ticks("ult_windup"));
        }

        void start() {
            Location pivot = pivot();
            if (room(2)) {
                ItemDisplay face = keep(Displays.item(ctx(), owner, pivot, new ItemStack(WATCH_ITEM),
                        watchPose(0.1f, GIANT_CHAIN, 0, 0), windup + 20));
                face.setTeleportDuration(1);
                face.setViewRange(2.0f);
                Displays.outline(face, GOLD);
                watch = face;
                BlockDisplay links = keep(Displays.block(ctx(), owner, pivot, CHAIN_BLOCK.createBlockData(),
                        chainPose(CHAIN_WIDTH * 2.5f, GIANT_CHAIN, 0, 0), windup + 20));
                links.setTeleportDuration(1);
                links.setViewRange(2.0f);
                chain = links;
            }
            Location feet = owner.getLocation().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, feet, 0.5, radius, 6, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Vfx.cloud(ctx(), pivot, Particle.ENCHANT, 30, 0.8, 1.2, null, true);
            Fx.sound(pivot, Sound.BLOCK_BELL_RESONATE, 1.2f, 0.7f);
            Fx.sound(pivot, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.0f, 0.8f);
            Fx.sound(pivot, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.4f);
            owner.sendActionBar(Text.mm("<" + VIOLET + "><b>집단 최면</b></" + VIOLET + "> <gray>째깍, 째깍...</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, windup, this, this::chime);
        }

        @Override
        public boolean test(long tick) {
            spin += GIANT_SPIN;
            float swing = (float) (GIANT_SWING * Math.sin(TAU * tick / 16.0));
            Location pivot = pivot();
            ItemDisplay face = watch;
            if (face != null && face.isValid()) {
                Displays.moveTo(face, pivot);
                float grown = GIANT_SCALE * (float) Math.min(1, (tick + 1) / 6.0);
                Displays.animate(face, watchPose(grown, GIANT_CHAIN, spin, swing), 1);
            }
            BlockDisplay links = chain;
            if (links != null && links.isValid()) {
                Displays.moveTo(links, pivot);
                Displays.animate(links, chainPose(CHAIN_WIDTH * 2.5f, GIANT_CHAIN, spin, swing), 1);
            }
            Location center = hanging(pivot, GIANT_CHAIN, spin, swing);
            Location floor = floor();
            if (tick % 2 == 0) {
                groundSpiral(floor, tick);
            }
            if (tick % 4 == 0) {
                Vfx.ring(ctx(), floor, radius, (int) Math.min(48, Math.max(16, radius * 4)), -tick * 0.05,
                        Particle.DUST_COLOR_TRANSITION, FADE, true);
            }
            center.getWorld().spawnParticle(Particle.ENCHANT, center, Vfx.n(ctx(), 6), 1.2, 0.8, 1.2, 1.2, null, true);
            long left = windup - tick;
            long beat = left <= 8 ? 2 : 5;
            if (tick % beat == 0) {
                boolean tock = (tick / beat) % 2 == 1;
                Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_HAT, 1.2f, tock ? 1.2f : 1.6f);
                Fx.sound(center, Sound.BLOCK_COMPARATOR_CLICK, 0.8f, tock ? 0.7f : 1.0f);
            }
            return true;
        }

        private Location pivot() {
            Location top = owner.getLocation().add(0, owner.getHeight(), 0);
            double above = GIANT_ABOVE;
            RayTraceResult ceiling = owner.getWorld().rayTraceBlocks(top, new Vector(0, 1, 0), above + 1.5,
                    FluidCollisionMode.NEVER, true);
            if (ceiling != null) {
                above = Math.max(0.6, ceiling.getHitPosition().getY() - top.getY() - 0.6);
            }
            return Displays.unrotated(top.add(0, above, 0));
        }

        private Location floor() {
            Location feet = owner.getLocation();
            Spots.snapToGround(owner.getWorld(), feet, 3);
            return Displays.unrotated(feet.add(0, 0.12, 0));
        }

        /** A turning spiral of violet and gold on the ground out to the edge of the hypnosis. */
        private void groundSpiral(Location floor, long tick) {
            int points = Vfx.n(ctx(), 16);
            World world = floor.getWorld();
            for (int arm = 0; arm < SPIRAL_ARMS; arm++) {
                for (int k = 1; k <= points; k++) {
                    double r = radius * k / points;
                    double angle = tick * 0.12 + arm * Math.PI + k * 0.55;
                    world.spawnParticle(Particle.DUST, floor.getX() + Math.cos(angle) * r, floor.getY(),
                            floor.getZ() + Math.sin(angle) * r, 1, 0, 0, 0, 0, arm == 0 ? GOLD_DUST : VIOLET_DUST,
                            true);
                }
            }
        }

        /** The watch rings: everyone in reach who can see it falls into a dream. */
        private void chime() {
            Location pivot = pivot();
            Location center = hanging(pivot, GIANT_CHAIN, spin, 0);
            ItemDisplay face = watch;
            if (face != null && face.isValid()) {
                Displays.animate(face, watchPose(GIANT_SCALE * 1.25f, GIANT_CHAIN, spin, 0), 3);
                ctx().runLater(owner, 4, () -> {
                    if (face.isValid()) {
                        Displays.shrinkAway(ctx(), owner, face, 6);
                    }
                });
            }
            BlockDisplay links = chain;
            if (links != null && links.isValid()) {
                Displays.shrinkAway(ctx(), owner, links, 5);
            }
            Location floor = floor();
            Vfx.dot(center, Particle.FLASH, GOLD, true);
            Vfx.sphere(ctx(), center, 1.4, 30, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Vfx.shockwave(ctx(), owner, floor, 0.5, radius, 7, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Vfx.shockwave(ctx(), owner, floor.clone().add(0, 0.6, 0), 0.3, radius * 0.8, 9, Particle.DUST, GOLD_DUST,
                    true);
            Fx.sound(center, Sound.BLOCK_BELL_USE, 1.6f, 0.6f);
            Fx.sound(center, Sound.BLOCK_BELL_RESONATE, 1.2f, 0.8f);
            Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.5f);
            Fx.sound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 0.8f);
            afterglow(center);

            long sleep = Math.max(1, params().ticks("ult_sleep"));
            int dreaming = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), radius)) {
                if (isHiddenFromOwner(enemy)) {
                    continue;
                }
                if (!Spots.clearPath(center, enemy.getEyeLocation()) && !Spots.clearPath(center, Fx.body(enemy))) {
                    continue;
                }
                if (fallAsleep(owner, enemy, sleep, true)) {
                    dreaming++;
                    for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                        Vfx.trail(center, head(enemy), i % 2 == 0 ? GOLD : PALETTE.body(), 8 + 2 * i, true);
                    }
                }
            }
            owner.sendActionBar(Text.mm(dreaming > 0
                    ? "<" + VIOLET + "><b>집단 최면</b></" + VIOLET + "> <white>" + dreaming + "명</white><gray> 꿈나라로</gray>"
                    : "<gray>시계를 본 적이 없습니다</gray>"));
        }

        /** Gold sparkles drifting down where the watch rang. */
        private void afterglow(Location center) {
            Loop.repeat(ctx(), owner, 2, 2, AFTERGLOW_TICKS / 2, run -> {
                for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                    Location at = Vfx.scatter(center, 1.8).add(0, ThreadLocalRandom.current().nextDouble(-0.8, 0.8), 0);
                    Vfx.dot(at, Particle.DUST, i % 2 == 0 ? GOLD_SMALL : LILAC_DUST, false);
                }
                return true;
            });
        }
    }

    // ================================================================== display helpers

    /** Claims room for {@code count} more displays (dead ones are forgotten first). */
    private boolean room(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    private static void remove(@Nullable Display display) {
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    /** The rotation of a watch hanging from its pivot: turned to {@code yaw}, swung {@code swing} degrees sideways. */
    private static Quaternionf hangRotation(float yaw, float swing) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw)).rotateZ((float) Math.toRadians(swing));
    }

    /**
     * The pocket watch hanging {@code chain} below the pivot (the display's position), its face turned to {@code yaw}
     * and swung {@code swing} degrees around the facing axis. The display itself is never rotated.
     */
    static Transformation watchPose(float scale, float chain, float yaw, float swing) {
        Quaternionf rotation = hangRotation(yaw, swing);
        Vector3f offset = new Quaternionf(rotation).transform(new Vector3f(0, -(chain + scale * 0.4f), 0));
        return new Transformation(offset, rotation, new Vector3f(scale), new Quaternionf());
    }

    /** The thin chain from the pivot down to the watch, swung with it. */
    private static Transformation chainPose(float width, float length, float yaw, float swing) {
        Quaternionf rotation = hangRotation(yaw, swing);
        Vector3f center = new Quaternionf(rotation).transform(new Vector3f(0, -length / 2, 0));
        Vector3f corner = new Quaternionf(rotation).transform(new Vector3f(width / 2, length / 2, width / 2)).negate();
        return new Transformation(center.add(corner), rotation, new Vector3f(width, length, width), new Quaternionf());
    }

    /** Where the watch hangs for a pose: the pivot plus the swung chain. */
    private static Location hanging(Location pivot, float chain, float yaw, float swing) {
        Vector3f offset = hangRotation(yaw, swing).transform(new Vector3f(0, -chain, 0));
        return pivot.clone().add(offset.x(), offset.y(), offset.z());
    }

    /** Two unit vectors spanning the plane perpendicular to {@code axis}. */
    private static Vector[] frame(Vector axis) {
        Vector n = axis.lengthSquared() < 1.0E-6 ? new Vector(0, 1, 0) : axis.clone().normalize();
        Vector helper = Math.abs(n.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = n.clone().crossProduct(helper).normalize();
        Vector v = n.clone().crossProduct(u).normalize();
        return new Vector[] {u, v};
    }

    /** A ring around {@code normal} (its axis) at {@code center}. */
    private <T> void ringAround(Location center, Vector normal, double radius, int points, double phase,
                                Particle particle, @Nullable T data, boolean force) {
        Vector[] frame = frame(normal);
        int count = Vfx.n(ctx(), points);
        World world = center.getWorld();
        for (int i = 0; i < count; i++) {
            double angle = phase + i * TAU / count;
            Location at = center.clone().add(frame[0].clone().multiply(Math.cos(angle) * radius))
                    .add(frame[1].clone().multiply(Math.sin(angle) * radius));
            world.spawnParticle(particle, at, 1, 0, 0, 0, 0, data, force);
        }
    }
}
