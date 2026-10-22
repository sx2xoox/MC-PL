package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
 * 인형술사 — the puppet master. Every hit ties a red thread to the victim that tugs it back when it runs off, the
 * active hooks the control strings into one enemy that must then copy every step and jump the puppeteer makes, and the
 * ultimate hangs everyone around from a giant control cross for a puppet show: they are hoisted into the air, swung to
 * wherever the puppeteer looks, clapped together on the beat and finally dashed against the floor.
 *
 * <p>The control strings are crowd control, not knockback: the puppet's velocity is set every tick (resistance does not
 * help), its own walking and jumping are switched off and it cannot use its abilities (silence), like every doll of the
 * show. The puppet copies the puppeteer's movement (the change of its position on the server each tick), never a
 * velocity read back from a player (stale on a server), a jump is replayed by its own predicted arc, and a step that
 * would walk it into lava, fire or off a ledge is not copied. The show steers its dolls along a planned path: a player
 * doll by dead reckoning (its position on the server trails its client by the ping, and correcting by that lagging
 * position swung the dolls by blocks), hangs them under a slow-falling safety net and lets them go if they stray from
 * the path. Neither needs a grant, ticks or hooks: the gambler may borrow the show (threads only widen its reach).
 *
 * <p>Look: wood and crimson thread. Threads are drawn from the puppeteer's fingers to every threaded enemy, sagging
 * while slack and pulled straight and bright when they tug. A controlled puppet wears a little wooden control cross
 * over its head whose strings run down to its head and hands, mirrored by the cross in the puppeteer's hand; the cross
 * tilts as the puppet is walked about and splinters when the strings snap. The show's giant cross hangs over the stage
 * with red knobs at its ends and strings to every doll, jerks up on every clap ("짝!") and breaks apart at the finale.
 */
public final class PuppeteerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("puppeteer", "인형술사", Material.STRING)
            .color(TextColor.color(0xE0314B))
            .difficulty(3)
            .passive("실",
                    "근접 공격이나 능력으로 맞힌 적에게 {thread_duration}초간 붉은 실을 엮습니다. 실에 엮인 적이 나에게서 "
                            + "{leash_range}블록보다 멀어지면 실이 팽팽하게 당겨져 이동 속도가 {leash_slow}% 느려집니다.")
            .active("꼭두각시",
                    "조준한 적(없으면 실에 엮인 가장 가까운 적, {puppet_range}블록 이내)에게 조종 실을 걸어 {attach_damage} "
                            + "피해를 주고, {puppet_duration}초간 내 움직임(걷기·점프)을 그대로 따라 하게 만듭니다. 조종당하는 "
                            + "적은 스스로 움직이거나 능력을 쓸 수 없고(용암·불이나 낭떠러지로는 끌려가지 않습니다), 벽에 "
                            + "부딪히면 {bump_damage} 피해(최대 {bump_max}번)를 받으며, 조종이 끝나면 실이 끊어지며 "
                            + "{snap_damage} 피해를 받습니다. 실에 엮인 적은 {thread_bonus}초 더 오래 조종합니다.",
                    12)
            .ultimate("인형극",
                    "거대한 십자 조종대를 띄우고 {ult_windup}초 뒤 반경 {ult_radius}블록 안의 적(실에 엮인 적은 "
                            + "{thread_reach}블록, 최대 {max_dolls}명)을 실로 매달아 {lift_height}블록 들어 올립니다. 매달린 "
                            + "적은 능력을 쓸 수 없고, {show_duration}초 동안 내가 바라보는 쪽으로(초당 {stage_speed}블록) "
                            + "끌려다니며 {clap_interval}초마다 서로 맞부딪혀(혼자면 바닥에 내리꽂혀) {clap_damage} 피해를 "
                            + "받다가, 마지막 박자에 바닥에 내동댕이쳐져 {finale_damage} 피해를 받습니다.")
            .param("thread_duration", 8)
            .param("leash_range", 10)
            .param("leash_slow", 25)
            .param("puppet_range", 14)
            .param("attach_damage", 2)
            .param("puppet_duration", 2)
            .param("bump_damage", 2)
            .param("bump_max", 2)
            .param("snap_damage", 2)
            .param("thread_bonus", 0.5)
            .param("ult_windup", 0.6)
            .param("ult_radius", 9)
            .param("thread_reach", 16)
            .param("max_dolls", 6)
            .param("lift_height", 2.5)
            .param("show_duration", 2)
            .param("stage_speed", 2.8)
            .param("clap_interval", 0.5)
            .param("clap_damage", 1.5)
            .param("finale_damage", 3)
            .build();

    private static final String CRIMSON = "#E0314B";
    private static final String CREAM = "#FFD6DC";
    private static final String WOOD_TAG = "#C08A52";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: wood and crimson thread
    /** Pale pink core, crimson thread, the dark red of old velvet curtains. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFD6DC, 0xE0314B, 0x3A0D14);
    private static final Color WOOD = Color.fromRGB(0xC08A52);
    private static final Particle.DustOptions SLACK = new Particle.DustOptions(PALETTE.body(), 0.5f);
    private static final Particle.DustTransition TAUT = PALETTE.glow(0.6f);
    private static final Particle.DustOptions STRING = new Particle.DustOptions(Color.fromRGB(0xF2E6D8), 0.45f);
    private static final Particle.DustOptions KNOT = new Particle.DustOptions(PALETTE.body(), 0.7f);
    private static final Particle.DustOptions SAWDUST = new Particle.DustOptions(WOOD, 0.9f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.1f);

    /** Block types of the visuals; block data is created on use (it needs a running server). */
    private static final Material WOOD_BLOCK = Material.OAK_PLANKS;
    private static final Material KNOB_BLOCK = Material.RED_WOOL;

    /** Display entities one puppeteer keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    /** Caps every velocity this ability applies. */
    private static final double MAX_SPEED = 1.6;
    private static final double REST_FALL = -Motion.GRAVITY * Motion.DRAG;
    private static final double JUMP_SPEED = 0.42;

    // passive
    private static final long THREAD_PERIOD = 3;
    private static final int MAX_THREAD_POINTS = 26;
    private static final long CREAK_GAP = 30;

    // active: the marionette
    /** A step of the puppeteer longer than this in one tick is a teleport, not a step: the puppet stays. */
    private static final double TELEPORT_STEP = 2.0;
    /** The puppet copies at most this much horizontal movement per tick (a sprint jump is about 0.6). */
    private static final double MAX_COPY = 0.75;
    /** The puppeteer rising this much in a tick after standing is a jump. */
    private static final double JUMP_RISE = 0.2;
    private static final double BUMP_PROBE = 0.3;
    private static final double BUMP_MIN_SPEED = 0.08;
    private static final long BUMP_GAP = 10;
    /** How many copied steps ahead the puppet's way is checked for lava, fire and drops. */
    private static final int STEP_LOOKAHEAD = 3;
    /** The deepest drop the puppet is walked down (a fall of more than 3 blocks would hurt it). */
    private static final double MAX_STEP_DOWN = 2.0;
    /** Extra drop allowed while the puppet is in the air, at most the height of a jump. */
    private static final double JUMP_ALLOWANCE = 1.3;
    private static final float CROSS_LENGTH = 0.75f;
    private static final float CROSS_DEPTH = 0.5f;
    private static final float CROSS_WIDTH = 0.07f;
    private static final double CROSS_ABOVE = 1.15;
    private static final float HAND_CROSS_SCALE = 0.55f;

    // ultimate: the puppet show
    private static final long HOIST_TICKS = 8;
    private static final long CLAP_TICKS = 6;
    private static final long CLAP_IMPACT = 2;
    private static final long FINALE_TICKS = 4;
    private static final double SLOT_RADIUS = 1.3;
    private static final double CLAP_RADIUS = 0.33;
    private static final double STAGE_MIN_DISTANCE = 2.0;
    private static final int GROUND_SEARCH = 8;
    private static final double STRAY_DISTANCE = 3.5;
    private static final long STRAY_TICKS = 10;
    private static final long SAFETY_FALL_TICKS = 60;
    private static final float GIANT_LENGTH = 3.2f;
    private static final float GIANT_DEPTH = 2.2f;
    private static final float GIANT_WIDTH = 0.2f;
    private static final float KNOB_SIZE = 0.26f;
    private static final double GIANT_ABOVE = 3.4;

    private final Map<UUID, Thread> threads = new HashMap<>();
    private final Set<UUID> onStage = new HashSet<>();
    private final List<Display> live = new ArrayList<>();
    private @Nullable Marionette marionette;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        threads.clear();
        onStage.clear();
        marionette = null;
    }

    // ================================================================== passive: red thread

    /** A red thread tied to one enemy: when it wears off and the slow it causes while pulled taut. */
    private static final class Thread {
        final LivingEntity victim;
        long expires;
        @Nullable NamespacedKey tug;
        long lastCreak = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow

        Thread(LivingEntity victim) {
            this.victim = victim;
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!player().equals(ctx().resolveAttacker(event.getDamager()))) {
            return;
        }
        tie(victim);
    }

    private void tie(LivingEntity victim) {
        if (!Combat.isTargetable(ctx(), player(), victim) || params().num("thread_duration") <= 0) {
            return;
        }
        boolean fresh = !threads.containsKey(victim.getUniqueId());
        Thread thread = threads.computeIfAbsent(victim.getUniqueId(), id -> new Thread(victim));
        thread.expires = ctx().currentTick() + params().ticks("thread_duration");
        if (fresh && !isHiddenFromOwner(victim)) {
            Location waist = waist(victim);
            Vfx.ring(ctx(), waist, victim.getWidth() * 0.5 + 0.25, 10, 0, Particle.DUST_COLOR_TRANSITION, GLOW, false);
            Vfx.trail(hand(player()), waist, PALETTE.body(), 6, false);
            Fx.sound(waist, Sound.ITEM_LEAD_TIED, 0.7f, 1.3f + Fx.jitter(0.1f));
        }
    }

    private boolean isThreaded(LivingEntity victim) {
        Thread thread = threads.get(victim.getUniqueId());
        return thread != null && ctx().currentTick() < thread.expires;
    }

    /** Threads wear off, tug on enemies that run too far and are drawn from the puppeteer's fingers. */
    @Override
    public void onTick(long tick) {
        if (threads.isEmpty()) {
            return;
        }
        Player owner = player();
        long now = ctx().currentTick();
        double leash = Math.max(1, params().num("leash_range"));
        for (Iterator<Thread> it = threads.values().iterator(); it.hasNext(); ) {
            Thread thread = it.next();
            LivingEntity victim = thread.victim;
            if (now >= thread.expires || !Combat.isTargetable(ctx(), owner, victim)
                    || !victim.getWorld().equals(owner.getWorld())) {
                loosen(thread);
                it.remove();
                continue;
            }
            double distance = victim.getLocation().distance(owner.getLocation());
            boolean taut = distance > leash && !onStage.contains(victim.getUniqueId());
            if (taut && thread.tug == null) {
                double slow = Math.clamp(params().num("leash_slow") / 100.0, 0, 1);
                if (slow > 0) {
                    thread.tug = ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -slow,
                            Operation.MULTIPLY_SCALAR_1, 0);
                }
            } else if (!taut) {
                loosen(thread);
            }
            if (tick % THREAD_PERIOD != 0 || isHiddenFromOwner(victim)
                    || marionette != null && victim.equals(marionette.victim)) {
                continue;
            }
            drawThread(hand(owner), waist(victim), taut, tick);
            if (taut && now - thread.lastCreak >= CREAK_GAP) {
                thread.lastCreak = now;
                Location at = waist(victim);
                Vfx.cloud(ctx(), at, Particle.CRIT, 4, 0.2, 0.1, null, false);
                Fx.sound(at, Sound.ITEM_CROSSBOW_LOADING_END, 0.6f, 0.6f + Fx.jitter(0.05f));
                Fx.sound(at, Sound.BLOCK_TRIPWIRE_ATTACH, 0.6f, 0.7f + Fx.jitter(0.05f));
            } else if (tick % (THREAD_PERIOD * 4) == 0) {
                Vfx.ring(ctx(), waist(victim), victim.getWidth() * 0.5 + 0.2, 7, tick * 0.2, Particle.DUST, KNOT,
                        false);
            }
        }
    }

    private void loosen(Thread thread) {
        if (thread.tug != null) {
            ctx().removeModifier(thread.victim, thread.tug);
            thread.tug = null;
        }
    }

    /** A thread: sagging and dull while slack, straight, bright and trembling while it tugs. */
    private void drawThread(Location from, Location to, boolean taut, long tick) {
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        if (length < 0.3) {
            return;
        }
        int points = (int) Math.min(MAX_THREAD_POINTS, Math.max(4, length * (taut ? 2.2 : 1.5) * ctx().effectScale()));
        double sag = taut ? 0 : Math.min(1.2, 0.09 * length);
        World world = from.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 1; i < points; i++) {
            double t = (double) i / points;
            double drop = sag * 4 * t * (1 - t);
            double tremble = taut ? 0.03 * Math.sin(tick * 1.7 + i) : 0;
            Location at = from.clone().add(delta.clone().multiply(t)).subtract(0, drop - tremble, 0);
            if (taut) {
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0, TAUT);
            } else if (random.nextInt(5) != 0) {
                world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, SLACK);
            }
        }
    }

    // ================================================================== active: the marionette

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("puppet_range"));
        LivingEntity target = ctx().findTarget(owner, range);
        if (target == null) {
            target = nearestThreaded(owner, range);
        }
        if (target == null) {
            return ActivationResult.fail("조종할 적이 없습니다");
        }
        if (onStage.contains(target.getUniqueId())) {
            return ActivationResult.fail("이미 무대 위에 매달린 인형입니다");
        }
        Marionette current = marionette;
        if (current != null && !current.done) {
            current.end(false);
        }
        long ticks = Math.max(1, params().ticks("puppet_duration"))
                + (isThreaded(target) ? params().ticks("thread_bonus") : 0);
        Marionette puppet = new Marionette(owner, target, ticks);
        marionette = puppet;
        puppet.start();
        return ActivationResult.success();
    }

    /** The nearest threaded enemy within {@code range} that the puppeteer sees and has a clear line to. */
    private @Nullable LivingEntity nearestThreaded(Player owner, double range) {
        Location eye = owner.getEyeLocation();
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Thread thread : threads.values()) {
            LivingEntity victim = thread.victim;
            if (!isThreaded(victim) || !Combat.isTargetable(ctx(), owner, victim) || isHiddenFromOwner(victim)
                    || !victim.getWorld().equals(owner.getWorld())) {
                continue;
            }
            double distance = victim.getLocation().distance(owner.getLocation());
            if (distance <= range && distance < bestDistance && Spots.clearPath(eye, waist(victim))) {
                best = victim;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * One controlled enemy: its own walking and jumping are off, and every tick it is given the puppeteer's last step
     * (and its jumps) as its velocity. Walls it is walked into hurt; the strings snap when the time is up.
     */
    private final class Marionette implements LongPredicate {

        private final Player owner;
        final LivingEntity victim;
        private final long ticks;
        private final List<NamespacedKey> modifiers = new ArrayList<>();
        private final BlockDisplay[] cross = new BlockDisplay[2];
        private final BlockDisplay[] handCross = new BlockDisplay[2];
        private Location last;
        private boolean ownerWasGrounded;
        private boolean airborne;
        private int airTicks;
        private double vy;
        private int bumps;
        private long lastBump = Long.MIN_VALUE / 2;
        private Vector sent = new Vector();
        boolean done;

        Marionette(Player owner, LivingEntity victim, long ticks) {
            this.owner = owner;
            this.victim = victim;
            this.ticks = ticks;
            this.last = owner.getLocation();
            this.ownerWasGrounded = Motion.isGrounded(owner);
        }

        void start() {
            Location head = crossSpot();
            Location hand = hand(owner);
            for (int i = 0; i < Vfx.n(ctx(), 5); i++) {
                Vfx.trail(hand, waist(victim).add(0, 0.25 * i - 0.5, 0), i % 2 == 0 ? PALETTE.body() : PALETTE.core(),
                        5 + i, false);
            }
            Vfx.line(ctx(), hand, head, 3, Particle.DUST_COLOR_TRANSITION, TAUT, false);
            Fx.sound(hand, Sound.ITEM_LEAD_TIED, 1.0f, 1.0f + Fx.jitter(0.05f));
            Fx.sound(hand, Sound.BLOCK_TRIPWIRE_ATTACH, 1.0f, 1.3f + Fx.jitter(0.05f));
            Fx.sound(head, Sound.BLOCK_WOOD_HIT, 0.8f, 1.5f + Fx.jitter(0.1f));
            Fx.sound(head, Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 0.7f, 1.4f);

            Combat.hit(ctx(), owner, victim, Math.max(0, params().num("attach_damage")));
            if (!Combat.isTargetable(ctx(), owner, victim)) {
                done = true;
                return;
            }
            modifiers.add(ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1,
                    0));
            modifiers.add(ctx().addModifier(owner, victim, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1,
                    0));
            if (victim instanceof Player puppet) {
                // No blink, charge or flight out of the strings.
                ctx().silence(puppet, ticks);
            }
            float yaw = owner.getLocation().getYaw();
            if (!isHiddenFromOwner(victim) && room(4)) {
                BlockData wood = WOOD_BLOCK.createBlockData();
                cross[0] = keep(Displays.block(ctx(), owner, head, wood, crossBar(1f, yaw, 0, 0, true), ticks + 10));
                cross[1] = keep(Displays.block(ctx(), owner, head, wood, crossBar(1f, yaw, 0, 0, false), ticks + 10));
                Location grip = hand(owner);
                handCross[0] = keep(Displays.block(ctx(), owner, grip, wood,
                        crossBar(HAND_CROSS_SCALE, yaw, 0, 0, true), ticks + 10));
                handCross[1] = keep(Displays.block(ctx(), owner, grip, wood,
                        crossBar(HAND_CROSS_SCALE, yaw, 0, 0, false), ticks + 10));
                for (BlockDisplay bar : new BlockDisplay[] {cross[0], cross[1], handCross[0], handCross[1]}) {
                    bar.setTeleportDuration(1);
                }
            }
            owner.sendActionBar(Text.mm("<" + CRIMSON + "><b>꼭두각시</b></" + CRIMSON + "> <gray>"
                    + AbilityInfo.formatNumber(ticks / 20.0) + "초간 내 움직임을 따라 합니다</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, ticks, this, () -> end(true));
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            if (!Combat.isTargetable(ctx(), owner, victim) || !victim.getWorld().equals(owner.getWorld())) {
                end(false);
                return false;
            }
            Location now = owner.getLocation();
            Vector step = now.toVector().subtract(last.toVector());
            boolean ownerGrounded = Motion.isGrounded(owner);
            boolean jumped = step.getY() > JUMP_RISE && ownerWasGrounded;
            ownerWasGrounded = ownerGrounded;
            last = now;

            Vector flat = new Vector(step.getX(), 0, step.getZ());
            if (step.length() > TELEPORT_STEP) {
                flat = new Vector();
            }
            flat = Motion.clamp(flat, MAX_COPY);
            if (flat.lengthSquared() > 1.0E-8 && !isSafeWay(flat)) {
                flat = new Vector();
            }
            double speed = flat.length();
            boolean grounded = Motion.isGrounded(victim) || victim.isInWater();
            if (jumped && grounded && !airborne) {
                airborne = true;
                airTicks = 0;
                vy = JUMP_SPEED;
                jumpFx();
            } else if (airborne) {
                airTicks++;
                vy = (vy - Motion.GRAVITY) * Motion.DRAG;
                if (airTicks > 3 && grounded && vy < 0) {
                    airborne = false;
                    vy = REST_FALL;
                }
            } else if (!grounded) {
                airborne = true;
                airTicks = 0;
                vy = 0;
            } else {
                vy = REST_FALL;
            }
            sent = new Vector(flat.getX(), vy, flat.getZ());
            victim.setVelocity(Motion.clamp(sent.clone(), MAX_SPEED));
            if (speed > BUMP_MIN_SPEED) {
                checkBump(flat.clone().multiply(1 / speed));
            }
            draw(tick, flat);
            return true;
        }

        /**
         * True if the puppet may be walked {@code flat} per tick for the next few ticks: inside the arena, never into
         * lava or fire, never off a ledge it would get hurt falling from. Walls are fine: the puppet slams into them.
         */
        private boolean isSafeWay(Vector flat) {
            Location feet = victim.getLocation();
            for (int i = 1; i <= STEP_LOOKAHEAD; i++) {
                Location ahead = feet.clone().add(flat.clone().multiply(i));
                if (!ctx().isInsideArena(ahead) || !isSafeFooting(ahead)) {
                    return false;
                }
            }
            return true;
        }

        private boolean isSafeFooting(Location feet) {
            World world = feet.getWorld();
            // The ray starts above the feet: one that starts on a block's face reports a hit inside it.
            Location from = feet.clone().add(0, 0.5, 0);
            double reach = 0.5 + MAX_STEP_DOWN + (airborne ? JUMP_ALLOWANCE : 0);
            RayTraceResult ground = world.rayTraceBlocks(from, new Vector(0, -1, 0), reach, FluidCollisionMode.ALWAYS,
                    true);
            if (ground == null) {
                return false;
            }
            Block floor = ground.getHitBlock();
            if (floor != null && (Spots.isHazard(floor) || Spots.isHazard(floor.getRelative(BlockFace.UP)))) {
                return false;
            }
            // Fire and lava burn whatever part of the body touches them.
            double half = victim.getWidth() / 2;
            int bottom = (int) Math.floor(feet.getY() + 0.01);
            int top = (int) Math.floor(feet.getY() + victim.getHeight());
            for (int x = (int) Math.floor(feet.getX() - half); x <= (int) Math.floor(feet.getX() + half); x++) {
                for (int z = (int) Math.floor(feet.getZ() - half); z <= (int) Math.floor(feet.getZ() + half); z++) {
                    for (int y = bottom; y <= top; y++) {
                        if (Spots.isHazard(world.getBlockAt(x, y, z))) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        /** Walked into a wall: the puppet slams into it (a few times at most). */
        private void checkBump(Vector direction) {
            int max = Math.max(0, params().integer("bump_max"));
            long now = ctx().currentTick();
            if (bumps >= max || now - lastBump < BUMP_GAP) {
                return;
            }
            BoundingBox box = victim.getBoundingBox();
            BoundingBox ahead = new BoundingBox(box.getMinX() + 0.05, box.getMinY() + 0.6, box.getMinZ() + 0.05,
                    box.getMaxX() - 0.05, box.getMaxY() - 0.1, box.getMaxZ() - 0.05)
                    .shift(direction.clone().multiply(BUMP_PROBE));
            if (!victim.getWorld().hasCollisionsIn(ahead)) {
                return;
            }
            bumps++;
            lastBump = now;
            Combat.hit(ctx(), owner, victim, Math.max(0, params().num("bump_damage")));
            if (isHiddenFromOwner(victim)) {
                // The slam would show where a puppet that slipped into stealth is.
                return;
            }
            Location face = Fx.body(victim).add(direction.clone().multiply(victim.getWidth() * 0.5 + 0.1));
            Vfx.cloud(ctx(), face, Particle.CRIT, 10, 0.2, 0.3, null, false);
            Vfx.cloud(ctx(), face, Particle.DUST, 6, 0.2, 0, SAWDUST, false);
            Vfx.dot(face, Particle.FLASH, PALETTE.core(), false);
            Fx.sound(face, Sound.BLOCK_WOOD_HIT, 1.0f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(face, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.9f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(face, Sound.ENTITY_ARMOR_STAND_HIT, 0.8f, 0.9f + Fx.jitter(0.05f));
            popText(face.clone().add(0, 0.6, 0), "<b><" + CRIMSON + ">쿵!</" + CRIMSON + "></b>", 1.0f, 14);
        }

        private void jumpFx() {
            if (isHiddenFromOwner(victim)) {
                return;
            }
            Location feet = victim.getLocation().add(0, 0.05, 0);
            Vfx.ring(ctx(), feet, 0.45, 8, 0, Particle.DUST, SAWDUST, false);
            Fx.sound(feet, Sound.BLOCK_WOOD_STEP, 0.8f, 1.4f + Fx.jitter(0.1f));
        }

        private Location crossSpot() {
            return Displays.unrotated(victim.getLocation().add(0, victim.getHeight() + CROSS_ABOVE, 0));
        }

        /** The crosses follow, tilt with the walk; strings run from the cross to the puppet's head and hands. */
        private void draw(long tick, Vector flat) {
            float yaw = owner.getLocation().getYaw();
            Vector facing = Motion.horizontalFacing(owner.getLocation());
            Vector right = new Vector(-facing.getZ(), 0, facing.getX());
            float pitch = (float) Math.clamp(flat.dot(facing) * 60, -25, 25);
            float roll = (float) Math.clamp(-flat.dot(right) * 60, -25, 25) + 4f * (float) Math.sin(tick * 0.5);
            Location spot = crossSpot();
            boolean hidden = isHiddenFromOwner(victim);
            for (int i = 0; i < 2; i++) {
                BlockDisplay bar = cross[i];
                if (bar != null && bar.isValid()) {
                    Displays.moveTo(bar, spot);
                    Displays.animate(bar, crossBar(1f, yaw, pitch, roll, i == 0), 1);
                    // The cross would give away a puppet that slipped into stealth.
                    float range = hidden ? 0f : 1.5f;
                    if (bar.getViewRange() != range) {
                        bar.setViewRange(range);
                    }
                }
                BlockDisplay grip = handCross[i];
                if (grip != null && grip.isValid()) {
                    Displays.moveTo(grip, hand(owner));
                    Displays.animate(grip, crossBar(HAND_CROSS_SCALE, yaw, -pitch, -roll, i == 0), 1);
                }
            }
            if (hidden || tick % 2 != 0) {
                return;
            }
            Quaternionf turn = crossRotation(yaw, pitch, roll);
            Location head = victim.getLocation().add(0, victim.getHeight() - 0.05, 0);
            Location waist = waist(victim);
            Vector side = right.clone().multiply(victim.getWidth() * 0.5 + 0.15);
            strand(tip(spot, turn, 0, 0, CROSS_DEPTH / 2), head);
            strand(tip(spot, turn, CROSS_LENGTH / 2, 0, 0), waist.clone().add(side).add(0, 0.15, 0));
            strand(tip(spot, turn, -CROSS_LENGTH / 2, 0, 0), waist.clone().subtract(side).add(0, 0.15, 0));
            if (tick % 6 == 0) {
                drawThread(hand(owner), spot, true, tick);
            }
        }

        private void strand(Location from, Location to) {
            Vfx.line(ctx(), from, to, 3.5, Particle.DUST, STRING, false);
        }

        /** The control ends: by time (the strings snap and hurt), quietly when the show takes the puppet over. */
        void end(boolean snap) {
            if (done) {
                return;
            }
            done = true;
            if (marionette == this) {
                marionette = null;
            }
            for (NamespacedKey key : modifiers) {
                ctx().removeModifier(victim, key);
            }
            modifiers.clear();
            if (victim.isValid() && !victim.isDead() && !onStage.contains(victim.getUniqueId())) {
                // Stop the copied walk; a fall in progress goes on (the predicted fall, never a stale read-back).
                victim.setVelocity(new Vector(0, airborne ? Math.min(0, vy) : REST_FALL, 0));
            }
            Location spot = crossSpot();
            for (BlockDisplay bar : new BlockDisplay[] {cross[0], cross[1], handCross[0], handCross[1]}) {
                if (bar != null && bar.isValid()) {
                    Displays.shrinkAway(ctx(), owner, bar, 4);
                }
            }
            if (!snap) {
                return;
            }
            if (Combat.isTargetable(ctx(), owner, victim)) {
                Combat.hit(ctx(), owner, victim, Math.max(0, params().num("snap_damage")));
                owner.sendActionBar(Text.mm("<" + WOOD_TAG + ">툭!</" + WOOD_TAG + "> <gray>조종 실이 끊어졌습니다"
                        + (bumps > 0 ? " · 벽 충돌 " + bumps + "번" : "") + "</gray>"));
            }
            if (isHiddenFromOwner(victim)) {
                return;
            }
            World world = spot.getWorld();
            world.spawnParticle(Particle.BLOCK, spot, Vfx.n(ctx(), 14), 0.3, 0.1, 0.3, 0.1, WOOD_BLOCK.createBlockData());
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 6); i++) {
                Vector out = new Vector(random.nextGaussian(), random.nextDouble(-0.4, 0.8), random.nextGaussian());
                if (out.lengthSquared() > 1.0E-6) {
                    Vfx.trail(spot, spot.clone().add(out.normalize().multiply(1.2)), PALETTE.body(), 6, false);
                }
            }
            Vfx.cloud(ctx(), Fx.body(victim), Particle.DUST_COLOR_TRANSITION, 10, 0.3, 0, FADE, false);
            Fx.sound(spot, Sound.ITEM_LEAD_BREAK, 1.0f, 1.0f + Fx.jitter(0.08f));
            Fx.sound(spot, Sound.BLOCK_WOOD_BREAK, 0.8f, 1.2f + Fx.jitter(0.08f));
            Fx.sound(spot, Sound.BLOCK_TRIPWIRE_DETACH, 0.9f, 0.8f);
            popText(spot.clone().add(0, 0.3, 0), "<b><" + CREAM + ">툭!</" + CREAM + "></b>", 0.9f, 14);
        }
    }

    // ================================================================== ultimate: the puppet show

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        List<LivingEntity> audience = audience(owner, owner.getLocation().add(0, owner.getHeight() + GIANT_ABOVE, 0));
        if (audience.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(params().num("ult_radius"))
                    + "블록 안에 매달 적이 없습니다");
        }
        new Show(owner).start(audience);
        return ActivationResult.success();
    }

    /**
     * The enemies the show can catch: within the radius of the puppeteer (threaded ones from further away), visible to
     * it and with a clear line from {@code strings} (where the strings come down from) or from its eyes, nearest first.
     */
    private List<LivingEntity> audience(Player owner, Location strings) {
        double radius = Math.max(1, params().num("ult_radius"));
        double reach = Math.max(radius, params().num("thread_reach"));
        int max = Math.max(1, params().integer("max_dolls"));
        Location eye = owner.getEyeLocation();
        List<LivingEntity> found = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), reach)) {
            if (found.size() >= max || isHiddenFromOwner(enemy)) {
                continue;
            }
            double distance = enemy.getLocation().distance(owner.getLocation());
            if (distance > radius && !isThreaded(enemy)) {
                continue;
            }
            Location head = enemy.getLocation().add(0, enemy.getHeight(), 0);
            if (Spots.clearPath(strings, head) || Spots.clearPath(eye, waist(enemy))) {
                found.add(enemy);
            }
        }
        return found;
    }

    /** One hung enemy: where it was caught, where it is reckoned to be and the modifiers that hold it. */
    private static final class Doll {
        final LivingEntity victim;
        final List<NamespacedKey> modifiers = new ArrayList<>();
        final Location origin;
        final Reckoning at;
        int stray;
        boolean released;

        Doll(LivingEntity victim) {
            this.victim = victim;
            this.origin = victim.getLocation();
            this.at = new Reckoning(victim);
        }
    }

    /**
     * The show: a wind-up while the giant cross descends and strings come down onto the audience, the hoist, the swing
     * with claps on the beat, and the finale slam.
     */
    private final class Show implements LongPredicate {

        private final Player owner;
        private final long windup;
        private final long showTicks;
        private final long clapTicks;
        private final double lift;
        private final List<Doll> dolls = new ArrayList<>();
        private final BlockDisplay[] cross = new BlockDisplay[2];
        private final BlockDisplay[] knobs = new BlockDisplay[4];
        private final double stageSpeed;
        private List<LivingEntity> audience = List.of();
        private Location stage;
        private double floorY;
        private double hover;
        private float yaw;
        private float jerk;
        /** The cross as last posed (where it hangs, its size and tilt): the strings hang from its knobs. */
        private Location poseAt;
        private float poseScale;
        private float posePitch;
        private float poseRoll;

        Show(Player owner) {
            this.owner = owner;
            this.windup = Math.max(1, params().ticks("ult_windup"));
            this.showTicks = Math.max(1, params().ticks("show_duration"));
            this.clapTicks = Math.max(CLAP_TICKS + 1, params().ticks("clap_interval"));
            this.lift = Math.max(0.5, params().num("lift_height"));
            this.stageSpeed = Math.max(0, params().num("stage_speed")) / 20.0;
            this.stage = owner.getLocation();
            this.yaw = owner.getLocation().getYaw();
            this.poseAt = stage.clone();
        }

        void start(List<LivingEntity> first) {
            audience = first;
            stage = centroid(first);
            floorY = floorUnder(stage);
            hover = hoverHeight();
            Location crossAt = crossSpot(0);
            long life = windup + HOIST_TICKS + showTicks + FINALE_TICKS + 20;
            if (room(6)) {
                BlockData wood = WOOD_BLOCK.createBlockData();
                BlockData knob = KNOB_BLOCK.createBlockData();
                Location high = crossAt.clone().add(0, 2.5, 0);
                for (int i = 0; i < 2; i++) {
                    cross[i] = keep(Displays.block(ctx(), owner, high, wood, giantBar(0.1f, yaw, 0, 0, i == 0), life));
                    cross[i].setTeleportDuration(2);
                    cross[i].setViewRange(2.0f);
                }
                for (int i = 0; i < 4; i++) {
                    knobs[i] = keep(Displays.block(ctx(), owner, high, knob, knobPose(i, 0.1f, yaw, 0, 0), life));
                    knobs[i].setTeleportDuration(2);
                    knobs[i].setViewRange(2.0f);
                }
                Displays.outline(cross[0], PALETTE.body());
            }
            Fx.sound(crossAt, Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 1.2f, 0.6f);
            Fx.sound(crossAt, Sound.ITEM_LEAD_TIED, 1.2f, 0.7f);
            Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 0.6f);
            owner.sendActionBar(Text.mm("<" + CRIMSON + "><b>인형극</b></" + CRIMSON + "> <gray>막이 오릅니다...</gray>"));
            long total = windup + HOIST_TICKS + showTicks + FINALE_TICKS;
            Loop.repeat(ctx(), owner, 1, 1, total, this, this::curtain);
        }

        @Override
        public boolean test(long tick) {
            if (tick < windup) {
                descend(tick);
                return true;
            }
            if (tick == windup && !hang()) {
                return false;
            }
            dolls.removeIf(doll -> doll.released);
            if (dolls.isEmpty()) {
                return false;
            }
            long t = tick - windup;
            if (t < HOIST_TICKS) {
                hoist(t);
                return true;
            }
            long show = t - HOIST_TICKS;
            if (show < showTicks) {
                perform(show);
                return true;
            }
            long finale = show - showTicks;
            finale(finale);
            return finale < FINALE_TICKS - 1;
        }

        // ------------------------------------------------------------ wind-up

        /** The cross sinks down over the stage while strings come down onto everyone it will hang. */
        private void descend(long tick) {
            double progress = (tick + 1.0) / windup;
            Location crossAt = crossSpot(0).add(0, 2.5 * (1 - progress), 0);
            poseCross(crossAt, (float) progress, 0, 0);
            if (tick % 2 == 0) {
                for (LivingEntity enemy : audience) {
                    if (!Combat.isTargetable(ctx(), owner, enemy) || isHiddenFromOwner(enemy)) {
                        continue;
                    }
                    Location head = enemy.getLocation().add(0, enemy.getHeight(), 0);
                    Location end = crossAt.clone().add(head.toVector().subtract(crossAt.toVector()).multiply(progress));
                    Vfx.line(ctx(), crossAt, end, 2.5, Particle.DUST_COLOR_TRANSITION, TAUT, true);
                    Vfx.ring(ctx(), enemy.getLocation().add(0, 0.1, 0), 0.8 - 0.4 * progress, 10, tick * 0.4,
                            Particle.DUST_COLOR_TRANSITION, GLOW, true);
                }
            }
            if (tick % 3 == 0) {
                Location floor = owner.getLocation().add(0, 0.1, 0);
                double radius = Math.max(1, params().num("ult_radius"));
                Vfx.ring(ctx(), floor, radius, (int) Math.min(48, Math.max(16, radius * 4)), tick * 0.05, Particle.DUST,
                        KNOT, true);
            }
            if (tick % 4 == 0) {
                Fx.sound(crossAt, Sound.BLOCK_NOTE_BLOCK_HARP, 0.8f, 0.7f + 0.3f * (float) progress);
            }
        }

        /** The strings catch: whoever is still in reach is hung; the rest are let go. Null-safe on an empty catch. */
        private boolean hang() {
            Location crossAt = crossSpot(0);
            List<LivingEntity> caught = audience(owner, crossAt);
            if (caught.isEmpty()) {
                owner.sendActionBar(Text.mm("<gray>모두 줄을 피했습니다</gray>"));
                return false;
            }
            stage = centroid(caught);
            floorY = floorUnder(stage);
            hover = hoverHeight();
            long safety = HOIST_TICKS + showTicks + FINALE_TICKS + SAFETY_FALL_TICKS;
            for (LivingEntity enemy : caught) {
                if (!Spots.clearPath(waist(enemy), stage.clone().add(0, 1.0, 0))) {
                    continue;
                }
                Marionette puppet = marionette;
                if (puppet != null && enemy.equals(puppet.victim)) {
                    puppet.end(false);
                }
                Doll doll = new Doll(enemy);
                doll.modifiers.add(ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -1.0,
                        Operation.MULTIPLY_SCALAR_1, 0));
                doll.modifiers.add(ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1.0,
                        Operation.MULTIPLY_SCALAR_1, 0));
                // A safety net: should the show stop without the finale, the dolls float down instead of dropping.
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,
                        (int) Math.min(Integer.MAX_VALUE, safety), 0, true, false, false));
                if (enemy instanceof Player held) {
                    // A doll on its strings cannot cast (no blink or charge off the stage) until the curtain call.
                    ctx().silence(held, HOIST_TICKS + showTicks + FINALE_TICKS);
                }
                onStage.add(enemy.getUniqueId());
                dolls.add(doll);
                Location waist = waist(enemy);
                Vfx.cloud(ctx(), waist, Particle.DUST_COLOR_TRANSITION, 8, 0.3, 0, GLOW, true);
                Fx.sound(waist, Sound.ITEM_LEAD_TIED, 1.0f, 0.9f + Fx.jitter(0.1f));
            }
            if (dolls.isEmpty()) {
                return false;
            }
            Fx.sound(crossAt, Sound.BLOCK_PISTON_EXTEND, 0.8f, 1.6f);
            Fx.sound(crossAt, Sound.ITEM_CROSSBOW_LOADING_END, 1.0f, 0.8f);
            owner.sendActionBar(Text.mm("<" + CRIMSON + "><b>인형극</b></" + CRIMSON + "> <white>" + dolls.size()
                    + "명</white><gray> 무대에 오름</gray>"));
            return true;
        }

        // ------------------------------------------------------------ hoist and show

        private void hoist(long t) {
            double progress = smooth((t + 1.0) / HOIST_TICKS);
            for (int i = 0; i < dolls.size(); i++) {
                Doll doll = dolls.get(i);
                if (!doll.released) {
                    steer(doll, lerp(doll.origin, slot(i, SLOT_RADIUS, 0), progress));
                }
            }
            poseCross(crossSpot(0), 1f, 0, 0);
            drawStrings(t);
            if (t == 0) {
                for (Doll doll : dolls) {
                    if (!isHiddenFromOwner(doll.victim)) {
                        Vfx.cloud(ctx(), doll.victim.getLocation().add(0, 0.1, 0), Particle.CLOUD, 6, 0.3, 0.03, null,
                                true);
                    }
                }
                Fx.sound(stage, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f);
            }
        }

        /** The show itself: the stage follows the puppeteer's gaze, the dolls dangle and clap together on the beat. */
        private void perform(long t) {
            moveStage();
            long phase = clapPhase(t);
            boolean lone = dolls.size() == 1;
            for (int i = 0; i < dolls.size(); i++) {
                Doll doll = dolls.get(i);
                if (doll.released) {
                    continue;
                }
                double radius = SLOT_RADIUS;
                double drop = 0;
                if (phase >= 0) {
                    double squeeze = phase <= CLAP_IMPACT ? (phase + 1.0) / (CLAP_IMPACT + 1)
                            : 1 - (phase - CLAP_IMPACT) / (double) (CLAP_TICKS - CLAP_IMPACT);
                    if (lone) {
                        drop = hover * squeeze;
                    } else {
                        radius = SLOT_RADIUS - (SLOT_RADIUS - CLAP_RADIUS) * squeeze;
                    }
                }
                double sway = 0.12 * Math.sin(t * 0.35 + i * 1.7);
                steer(doll, slot(i, radius, sway).subtract(0, drop, 0));
            }
            jerk = phase == CLAP_IMPACT ? 12f : jerk * 0.6f;
            float tilt = 6f * (float) Math.sin(t * 0.3);
            poseCross(crossSpot(jerk * 0.02), 1f, tilt + jerk, -tilt);
            drawStrings(t);
            if (phase == CLAP_IMPACT) {
                clap(lone);
            } else if (t % 10 == 0) {
                Fx.sound(stage, Sound.BLOCK_NOTE_BLOCK_HARP, 0.6f, 0.9f + Fx.jitter(0.2f));
            }
        }

        /**
         * Where show tick {@code t} is in a clap: claps strike on every beat ({@code clap_interval}) before the
         * finale, each over {@link #CLAP_TICKS} ticks with the impact at {@link #CLAP_IMPACT}; -1 between claps.
         */
        private long clapPhase(long t) {
            long beat = (t + CLAP_IMPACT + 1) / clapTicks;
            if (beat < 1) {
                return -1;
            }
            long impact = beat * clapTicks - 1;
            long phase = t - (impact - CLAP_IMPACT);
            return phase >= 0 && phase < CLAP_TICKS && impact < showTicks - 1 ? phase : -1;
        }

        /** Every doll takes the clap: knocked together in the middle, or a lone doll slammed into the floor. */
        private void clap(boolean lone) {
            double damage = Math.max(0, params().num("clap_damage"));
            Location middle = stage.clone();
            middle.setY(lone ? floorY + 0.2 : floorY + hover + 0.9);
            boolean seen = false;
            for (Doll doll : dolls) {
                if (!doll.released) {
                    Combat.hit(ctx(), owner, doll.victim, damage);
                    seen |= !isHiddenFromOwner(doll.victim);
                }
            }
            if (!seen) {
                // Nothing to see: every doll slipped into stealth, and the clap would show where they are.
                return;
            }
            Vfx.dot(middle, Particle.FLASH, PALETTE.core(), true);
            Vfx.cloud(ctx(), middle, Particle.CRIT, 16, 0.4, 0.4, null, true);
            Vfx.cloud(ctx(), middle, Particle.DUST, 10, 0.4, 0, SAWDUST, true);
            if (lone) {
                Location floor = stage.clone();
                floor.setY(floorY + 0.1);
                Vfx.shockwave(ctx(), owner, floor, 0.3, 1.6, 4, Particle.DUST_COLOR_TRANSITION, GLOW, true);
                Fx.sound(floor, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.2f, 0.7f + Fx.jitter(0.05f));
                Fx.sound(floor, Sound.ENTITY_PLAYER_BIG_FALL, 1.0f, 0.9f);
            } else {
                Vfx.ring(ctx(), middle, 0.8, 14, 0, Particle.DUST_COLOR_TRANSITION, GLOW, true);
                Fx.sound(middle, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.2f, 1.1f + Fx.jitter(0.05f));
                Fx.sound(middle, Sound.ENTITY_ARMOR_STAND_HIT, 1.2f, 0.8f + Fx.jitter(0.05f));
            }
            Fx.sound(middle, Sound.BLOCK_WOOD_HIT, 1.2f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(middle, Sound.BLOCK_NOTE_BLOCK_SNARE, 1.0f, 1.0f);
            popText(middle.clone().add(0, 1.2, 0), "<b><gradient:" + CREAM + ":" + CRIMSON + ">짝!</gradient></b>", 1.3f,
                    12);
        }

        /** The stage swings round to where the puppeteer looks, at its distance; walls and drops stop it. */
        private void moveStage() {
            Location from = owner.getLocation();
            double distance = Math.max(STAGE_MIN_DISTANCE, Math.hypot(stage.getX() - from.getX(),
                    stage.getZ() - from.getZ()));
            distance = Math.min(distance, Math.max(1, params().num("ult_radius")));
            Vector facing = Motion.horizontalFacing(from);
            Vector want = new Vector(from.getX() + facing.getX() * distance - stage.getX(), 0,
                    from.getZ() + facing.getZ() * distance - stage.getZ());
            double length = want.length();
            if (length < 0.05) {
                return;
            }
            if (stageSpeed <= 0) {
                return;
            }
            Vector step = want.multiply(Math.min(stageSpeed, length) / length);
            Location next = stage.clone().add(step);
            Location body = stage.clone();
            body.setY(floorY + hover + 0.9);
            Location bodyNext = next.clone();
            bodyNext.setY(floorY + hover + 0.9);
            if (!ctx().isInsideArena(next) || !Spots.clearPath(body, bodyNext)) {
                return;
            }
            double ground = groundY(next, floorY + hover);
            if (Double.isNaN(ground) || Math.abs(ground - floorY) > 1.5) {
                return;
            }
            Location feet = next.clone();
            feet.setY(ground + hover);
            if (!Spots.hasGroundBelow(next.getWorld(), feet)) {
                return;
            }
            stage = next;
            floorY = ground;
            yaw = from.getYaw();
        }

        private void finale(long t) {
            for (int i = 0; i < dolls.size(); i++) {
                Doll doll = dolls.get(i);
                if (doll.released) {
                    continue;
                }
                double s = Math.min(1, (t + 1.0) / (FINALE_TICKS - 1));
                Location slot = slot(i, SLOT_RADIUS * 0.8, 0);
                slot.setY(floorY + hover * (1 - s * s));
                steer(doll, slot);
            }
            poseCross(crossSpot(-0.3 * t), 1f, -10f * t, 0);
            drawStrings(t);
            if (t < FINALE_TICKS - 1) {
                return;
            }
            double damage = Math.max(0, params().num("finale_damage"));
            for (Doll doll : dolls) {
                if (doll.released) {
                    continue;
                }
                Combat.hit(ctx(), owner, doll.victim, damage);
                if (isHiddenFromOwner(doll.victim)) {
                    continue;
                }
                Location feet = doll.victim.getLocation();
                World world = feet.getWorld();
                Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.3, 1.5, 4, Particle.DUST_COLOR_TRANSITION,
                        GLOW, true);
                BlockData ground = feet.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
                if (ground.getMaterial().isSolid()) {
                    world.spawnParticle(Particle.BLOCK, feet.clone().add(0, 0.1, 0), Vfx.n(ctx(), 14), 0.4, 0.1, 0.4,
                            0.15, ground);
                }
                Vfx.cloud(ctx(), Fx.body(doll.victim), Particle.CRIT, 10, 0.3, 0.4, null, true);
            }
            Fx.sound(stage, Sound.ENTITY_PLAYER_BIG_FALL, 1.2f, 0.8f);
            Fx.sound(stage, Sound.ITEM_MACE_SMASH_GROUND, 1.0f, 1.1f + Fx.jitter(0.05f));
            Fx.sound(stage, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 0.6f);
            popText(crossSpot(0).add(0, 0.8, 0), "<b><gradient:" + CREAM + ":" + CRIMSON + ">커튼콜!</gradient></b>",
                    1.4f, 22);
        }

        /** The curtain falls (also when the show ends early): everyone is let go and the cross breaks apart. */
        private void curtain() {
            for (Doll doll : dolls) {
                release(doll);
            }
            dolls.clear();
            Location crossAt = crossSpot(0);
            World world = crossAt.getWorld();
            for (BlockDisplay piece : cross) {
                if (piece != null && piece.isValid()) {
                    Displays.shrinkAway(ctx(), owner, piece, 6);
                }
            }
            for (BlockDisplay knob : knobs) {
                if (knob != null && knob.isValid()) {
                    knob.setTeleportDuration(6);
                    knob.teleport(knob.getLocation().subtract(0, 1.5, 0));
                    Displays.shrinkAway(ctx(), owner, knob, 6);
                }
            }
            world.spawnParticle(Particle.BLOCK, crossAt, Vfx.n(ctx(), 20), 0.8, 0.1, 0.8, 0.1,
                    WOOD_BLOCK.createBlockData());
            Vfx.cloud(ctx(), crossAt, Particle.DUST, 10, 0.8, 0, SAWDUST, true);
            Fx.sound(crossAt, Sound.BLOCK_WOOD_BREAK, 1.0f, 0.8f);
            Fx.sound(crossAt, Sound.ITEM_LEAD_BREAK, 1.0f, 0.7f);
        }

        private void release(Doll doll) {
            if (doll.released) {
                return;
            }
            doll.released = true;
            LivingEntity victim = doll.victim;
            onStage.remove(victim.getUniqueId());
            for (NamespacedKey key : doll.modifiers) {
                ctx().removeModifier(victim, key);
            }
            doll.modifiers.clear();
            clearSafety(victim);
            if (victim.isValid() && !victim.isDead()) {
                victim.setVelocity(new Vector(0, REST_FALL, 0));
                victim.setFallDistance(0);
            }
            if (isHiddenFromOwner(victim)) {
                return;
            }
            Location waist = waist(victim);
            Vfx.cloud(ctx(), waist, Particle.DUST_COLOR_TRANSITION, 8, 0.3, 0, FADE, false);
            Fx.sound(waist, Sound.BLOCK_TRIPWIRE_DETACH, 0.8f, 0.9f + Fx.jitter(0.1f));
        }

        /**
         * Steers one doll to {@code next} on its path, from where it is reckoned to be (a player by dead reckoning, see
         * {@link Reckoning}); a doll that strays far off its path (pushed or teleported away) is let go.
         */
        private void steer(Doll doll, Location next) {
            LivingEntity victim = doll.victim;
            if (!Combat.isTargetable(ctx(), owner, victim) || !victim.getWorld().equals(next.getWorld())) {
                release(doll);
                return;
            }
            if (doll.at.observe() > STRAY_DISTANCE) {
                if (++doll.stray > STRAY_TICKS) {
                    release(doll);
                    return;
                }
            } else {
                doll.stray = 0;
            }
            doll.at.send(Motion.clamp(next.toVector().subtract(doll.at.position()), MAX_SPEED));
            victim.setFallDistance(0);
        }

        /** Strings from the cross's red knobs (as it is posed now) to every doll's head, and a slack one to its waist. */
        private void drawStrings(long t) {
            if (t % 2 != 0) {
                return;
            }
            Quaternionf turn = crossRotation(yaw, posePitch, poseRoll);
            for (int i = 0; i < dolls.size(); i++) {
                if (dolls.get(i).released || isHiddenFromOwner(dolls.get(i).victim)) {
                    continue;
                }
                LivingEntity victim = dolls.get(i).victim;
                Location head = victim.getLocation().add(0, victim.getHeight(), 0);
                Vector3f end = knobEnd(i % knobs.length).mul(poseScale);
                Location knob = tip(poseAt, turn, end.x(), end.y(), end.z());
                Vfx.line(ctx(), knob, head, 2.5, Particle.DUST, STRING, true);
                Vfx.line(ctx(), poseAt, waist(victim), 1.5, Particle.DUST, SLACK, true);
            }
            if (t % 6 == 0) {
                drawThread(hand(owner), poseAt, true, t);
            }
        }

        private void poseCross(Location at, float scale, float pitch, float roll) {
            poseAt = at.clone();
            poseScale = scale;
            posePitch = pitch;
            poseRoll = roll;
            for (int i = 0; i < 2; i++) {
                BlockDisplay bar = cross[i];
                if (bar != null && bar.isValid()) {
                    Displays.moveTo(bar, at);
                    Displays.animate(bar, giantBar(scale, yaw, pitch, roll, i == 0), 2);
                }
            }
            for (int i = 0; i < 4; i++) {
                BlockDisplay knob = knobs[i];
                if (knob != null && knob.isValid()) {
                    Displays.moveTo(knob, at);
                    Displays.animate(knob, knobPose(i, scale, yaw, pitch, roll), 2);
                }
            }
        }

        /** Where the giant cross hangs over the stage. */
        private Location crossSpot(double raise) {
            Location at = stage.clone();
            at.setY(floorY + hover + GIANT_ABOVE + raise);
            return Displays.unrotated(at);
        }

        /** Doll {@code i}'s hanging spot: around the stage at {@code radius}, across the puppeteer's view. */
        private Location slot(int i, double radius, double sway) {
            int count = Math.max(1, dolls.size());
            double base = Math.toRadians(yaw);
            double angle = base + (count == 1 ? 0 : i * TAU / count);
            double r = count == 1 ? 0 : radius;
            Location at = stage.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
            at.setY(floorY + hover + sway);
            return at;
        }

        /** How high the dolls hang: the lift, lowered under a low ceiling. */
        private double hoverHeight() {
            Location floor = stage.clone();
            floor.setY(floorY + 0.1);
            RayTraceResult ceiling = floor.getWorld().rayTraceBlocks(floor, new Vector(0, 1, 0), lift + 2.5,
                    FluidCollisionMode.NEVER, true);
            if (ceiling == null) {
                return lift;
            }
            return Math.max(0.3, Math.min(lift, ceiling.getHitPosition().getY() - floorY - 2.1));
        }
    }

    /** The middle of the caught enemies (their feet), or the nearest one's spot if the middle has no floor. */
    private Location centroid(List<LivingEntity> enemies) {
        double x = 0;
        double y = 0;
        double z = 0;
        for (LivingEntity enemy : enemies) {
            Location at = enemy.getLocation();
            x += at.getX();
            y += at.getY();
            z += at.getZ();
        }
        int count = Math.max(1, enemies.size());
        Location first = enemies.getFirst().getLocation();
        Location middle = new Location(first.getWorld(), x / count, y / count, z / count);
        double ground = groundY(middle, middle.getY() + 1.0);
        if (Double.isNaN(ground) || !ctx().isInsideArena(middle)) {
            return Displays.unrotated(first);
        }
        return Displays.unrotated(middle);
    }

    /** The floor under the feet at {@code at}; the feet's own height over a deep drop. */
    private static double floorUnder(Location at) {
        double ground = groundY(at, at.getY() + 1.0);
        return Double.isNaN(ground) ? at.getY() : ground;
    }

    /** The top of the floor under {@code at}, searching down from {@code fromY}; NaN if there is none in reach. */
    private static double groundY(Location at, double fromY) {
        World world = at.getWorld();
        Location start = at.clone();
        start.setY(fromY);
        RayTraceResult ground = world.rayTraceBlocks(start, new Vector(0, -1, 0), GROUND_SEARCH,
                FluidCollisionMode.ALWAYS, true);
        return ground == null ? Double.NaN : ground.getHitPosition().getY();
    }

    /** Removes the safety-net slow falling of the show, never a slow falling the body got elsewhere. */
    private static void clearSafety(LivingEntity held) {
        PotionEffect effect = held.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (effect != null && effect.isAmbient() && !effect.hasParticles() && !effect.hasIcon()
                && effect.getAmplifier() == 0) {
            held.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
    }

    // ================================================================== helpers

    private boolean isHiddenFromOwner(LivingEntity entity) {
        return entity instanceof Player hidden && !player().canSee(hidden);
    }

    /** Where the puppeteer holds its strings: just in front of the right hand. */
    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector facing = Motion.horizontalFacing(owner.getLocation());
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());
        return Displays.unrotated(eye.add(facing.multiply(0.45)).add(right.multiply(0.3)).subtract(0, 0.45, 0));
    }

    private static Location waist(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() * 0.55, 0);
    }

    private static double smooth(double t) {
        double c = Math.clamp(t, 0, 1);
        return c * c * (3 - 2 * c);
    }

    private static Location lerp(Location from, Location to, double t) {
        return from.clone().add(to.toVector().subtract(from.toVector()).multiply(t));
    }

    /** Claims room for {@code count} more displays (dead ones are forgotten first). */
    private boolean room(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    /** A word popping up and shrinking away. */
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

    /** The rotation of a control cross: turned to {@code yaw}, tilted forward by {@code pitch} and sideways by roll. */
    private static Quaternionf crossRotation(float yaw, float pitch, float roll) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch))
                .rotateZ((float) Math.toRadians(roll));
    }

    /** A point on a cross at {@code at}: the local offset turned like the cross. */
    private static Location tip(Location at, Quaternionf turn, float x, float y, float z) {
        Vector3f offset = new Quaternionf(turn).transform(new Vector3f(x, y, z));
        return at.clone().add(offset.x(), offset.y(), offset.z());
    }

    /**
     * One bar of the small control cross: the long bar across the puppeteer's facing (local x), or the short bar along
     * it (local z). The display is never rotated; the turn lives in the transformation.
     */
    static Transformation crossBar(float scale, float yaw, float pitch, float roll, boolean across) {
        float length = (across ? CROSS_LENGTH : CROSS_DEPTH) * scale;
        float width = CROSS_WIDTH * scale;
        return across ? Displays.transform(0, 0, 0, length, width, width, yaw, pitch, roll)
                : Displays.transform(0, 0, 0, width, width, length, yaw, pitch, roll);
    }

    /** One bar of the show's giant cross. */
    private static Transformation giantBar(float scale, float yaw, float pitch, float roll, boolean across) {
        float length = (across ? GIANT_LENGTH : GIANT_DEPTH) * scale;
        float width = GIANT_WIDTH * Math.max(0.1f, scale);
        return across ? Displays.transform(0, 0, 0, length, width, width, yaw, pitch, roll)
                : Displays.transform(0, 0, 0, width, width, length, yaw, pitch, roll);
    }

    /** One of the four red knobs at the giant cross's ends. */
    private static Transformation knobPose(int index, float scale, float yaw, float pitch, float roll) {
        Quaternionf turn = crossRotation(yaw, pitch, roll);
        Vector3f center = new Quaternionf(turn).transform(knobEnd(index).mul(scale));
        float size = KNOB_SIZE * Math.max(0.1f, scale);
        return Displays.transform(center.x(), center.y(), center.z(), size, size, size, yaw, pitch, roll);
    }

    /** The end of a bar of the giant cross where knob {@code index} sits, in the cross's own (unturned) frame. */
    private static Vector3f knobEnd(int index) {
        return switch (index) {
            case 0 -> new Vector3f(GIANT_LENGTH / 2, 0, 0);
            case 1 -> new Vector3f(-GIANT_LENGTH / 2, 0, 0);
            case 2 -> new Vector3f(0, 0, GIANT_DEPTH / 2);
            default -> new Vector3f(0, 0, -GIANT_DEPTH / 2);
        };
    }

    /**
     * Where a steered body is. Any entity but a player is moved by the server itself, so its own position is exact. A
     * player moves on its own client, which the server hears from only a round trip later: correcting by that lagging
     * position is a delayed correction that overshoots and swings the body by blocks once the ping reaches a few ticks.
     * A player is therefore tracked by dead reckoning (like the magnet's pull): where it was caught plus every velocity
     * sent to it since, each step cut short where a block would stop the client.
     */
    private static final class Reckoning {
        /** Reckoned positions kept to compare the lagging server position with: a second of round trip. */
        private static final int TRAIL = 20;
        /**
         * Share of the drift seen on the server that is taken into the reckoning per tick. The server shows the effect
         * of a nudge only a round trip later, so it must stay small: a correction delayed by d ticks settles while its
         * gain is below about pi / (2d + 1), and 0.05 settles up to a ping of about 1.5 s.
         */
        private static final double DRIFT_GAIN = 0.05;
        private static final double MAX_NUDGE = 0.1;
        private final LivingEntity body;
        private final Vector at;
        private final ArrayDeque<Vector> trail = new ArrayDeque<>();

        Reckoning(LivingEntity body) {
            this.body = body;
            this.at = body.getLocation().toVector();
            trail.addLast(at.clone());
        }

        /** Where the body is: for a player, where it will be once it has followed every velocity sent so far. */
        Vector position() {
            if (!(body instanceof Player)) {
                at.copy(body.getLocation().toVector());
            }
            return at.clone();
        }

        /** Sends {@code velocity} to the body; a player's reckoning moves with it, up to the first block in the way. */
        void send(Vector velocity) {
            body.setVelocity(velocity);
            if (body instanceof Player) {
                at.add(blockedStep(velocity));
            } else {
                at.copy(body.getLocation().toVector()).add(velocity);
            }
            trail.addLast(at.clone());
            while (trail.size() > TRAIL) {
                trail.removeFirst();
            }
        }

        /**
         * How far the body (as the server sees it) is from where it should be. Anything but a player is compared with
         * where the last velocity should have taken it. A player's server position is one of the places it was
         * reckoned to be lately, a round trip late, so it is compared with the nearest of those; what is left is real
         * drift (a knockback, its own air control), which nudges the reckoning slowly towards it.
         */
        double observe() {
            Vector seen = body.getLocation().toVector();
            if (!(body instanceof Player)) {
                return seen.distance(trail.getLast());
            }
            Vector nearest = trail.getLast();
            double best = Double.MAX_VALUE;
            for (Vector place : trail) {
                double distance = seen.distance(place);
                if (distance < best) {
                    best = distance;
                    nearest = place;
                }
            }
            if (best < STRAY_DISTANCE) {
                at.add(Motion.clamp(seen.subtract(nearest).multiply(DRIFT_GAIN), MAX_NUDGE));
            }
            return best;
        }

        /** The step a client takes with {@code velocity}: like vanilla, axis by axis (y first) up to the first block. */
        private Vector blockedStep(Vector velocity) {
            double half = body.getWidth() / 2 - 1.0E-3;
            BoundingBox box = new BoundingBox(at.getX() - half, at.getY() + 1.0E-3, at.getZ() - half,
                    at.getX() + half, at.getY() + body.getHeight() - 1.0E-3, at.getZ() + half);
            double dy = sweep(box, 0, velocity.getY(), 0);
            box.shift(0, dy, 0);
            double dx = sweep(box, velocity.getX(), 0, 0);
            box.shift(dx, 0, 0);
            double dz = sweep(box, 0, 0, velocity.getZ());
            return new Vector(dx, dy, dz);
        }

        /** How much of a move along one axis is free (in quarters): the moved length along that axis. */
        private double sweep(BoundingBox box, double x, double y, double z) {
            World world = body.getWorld();
            for (int quarters = 4; quarters > 0; quarters--) {
                double f = quarters / 4.0;
                if (!world.hasCollisionsIn(box.clone().shift(x * f, y * f, z * f))) {
                    return (x + y + z) * f;
                }
            }
            return 0;
        }
    }
}
