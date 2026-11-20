package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;
import java.util.logging.Level;

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
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 기린 — the auspicious beast that walks on clouds and will not even tread on a blade of grass. Its melee is gentle
 * but every blow heals the most hurt ally and charges a cloud step for a jump in mid-air, the active lays a cloud
 * bridge (flat over gaps, or a walkable stair up or down), and the ultimate spreads an auspicious cloud high in the air
 * and carries the team up onto it: high ground that heals and hits harder, while the hooves drum on the enemies in its
 * shadow.
 *
 * <p>Look (auspicious gold, jade green, cloud white): golden hoof prints while running, a jade-and-gold wisp under the
 * feet while a cloud step is charged, and a little cloud blooming out of thin air for the jump. Healing flows as a gold
 * mote from the struck enemy to the ally. The bridge surges forward as a wave of cloud, wool and quartz steps with
 * gold sparks along its edges and mist over the ground. The great cloud billows out from its middle under a golden
 * sigil, its rim glowing; the riders rise in a gold helix, and hoof beats fall from the cloud as gold rings.
 */
public final class QilinAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("qilin", "기린", Material.WHITE_WOOL)
            .color(TextColor.color(0xE8B931))
            .difficulty(1)
            .passive("인자한 짐승",
                    "근접 피해가 {melee_penalty}% 약한 대신, 근접 공격이 맞을 때마다 {heal_radius}블록 안에서 가장 다친 "
                            + "아군을 {heal_amount} 회복시키고 구름 발판을 얻어 공중에서 한 번 더 뛸 수 있습니다. 낙하 피해를 "
                            + "받지 않습니다.")
            .active("구름다리",
                    "바라보는 방향으로 {bridge_length}블록 길이의 구름다리를 {bridge_duration}초간 놓습니다. 위나 아래를 "
                            + "보고 쓰면 최대 {bridge_rise}블록 높이까지 오르내리는 계단이 됩니다. 누구나 건널 수 있고 다리 "
                            + "위의 나와 아군은 {bridge_speed}% 빨라지며, 다리가 솟는 길목의 적은 {bridge_damage} 피해를 입고 "
                            + "떠오릅니다.",
                    10)
            .ultimate("상서로운 구름",
                    "{cloud_height}블록 위에 반경 {cloud_radius}블록의 구름을 펼치고 주변 아군과 함께 올라탑니다. "
                            + "{cloud_duration}초 동안 구름 위의 나와 아군은 1초마다 {cloud_heal} 회복하고 아래의 적에게 주는 피해가 "
                            + "{cloud_bonus}% 늘며, 적은 구름에 오를 수 없습니다. 구름 그림자 아래의 적은 {shadow_slow}% "
                            + "느려지고 1초마다 {hoof_damage} 피해를 입습니다. 구름이 걷히면 천천히 내려옵니다.")
            .ultimateNotStandalone()
            .param("melee_penalty", 20)
            .param("heal_radius", 10)
            .param("heal_amount", 1)
            .param("step_duration", 6)
            .param("step_height", 1.6)
            .param("bridge_length", 14)
            .param("bridge_width", 2)
            .param("bridge_duration", 6)
            .param("bridge_rise", 5)
            .param("bridge_speed", 15)
            .param("bridge_damage", 2)
            .param("bridge_lift", 1.5)
            .param("cloud_radius", 6)
            .param("cloud_height", 5)
            .param("cloud_duration", 8)
            .param("cloud_heal", 0.5)
            .param("cloud_bonus", 15)
            .param("shadow_slow", 25)
            .param("hoof_damage", 1.5)
            .build();

    private static final String GOLD_TAG = "#E8B931";
    private static final String JADE_TAG = "#3BAE8A";

    // ------------------------------------------------------------------ look
    /** Pale gold, auspicious gold, jade. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF3C4, 0xE8B931, 0x3BAE8A);
    private static final Color GOLD = Color.fromRGB(0xE8B931);
    private static final Color JADE = Color.fromRGB(0x3BAE8A);
    private static final Color CLOUD = Color.fromRGB(0xF7F7F2);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 0.9f);
    private static final Particle.DustOptions JADE_DUST = new Particle.DustOptions(JADE, 0.8f);
    private static final Particle.DustOptions CLOUD_DUST = new Particle.DustOptions(CLOUD, 1.6f);
    private static final Particle.DustOptions SHADOW_DUST = new Particle.DustOptions(Color.fromRGB(0x5A5F66), 1.2f);
    private static final Particle.DustTransition WISP = new Particle.DustTransition(JADE, GOLD, 0.8f);

    /** Block types; block data is created on use (it needs a running server). */
    private static final Material CLOUD_BLOCK = Material.WHITE_WOOL;
    private static final Material CLOUD_PUFF = Material.SNOW_BLOCK;
    private static final Material CLOUD_STEP = Material.SMOOTH_QUARTZ_SLAB;

    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 2.0;
    /** A player's client runs this many ticks ahead of the server at most (its ping, for steering). */
    private static final long MAX_LAG_TICKS = 10;
    private static final double MILLIS_PER_TICK = 50.0;

    // passive
    private static final double STEP_PUSH = 0.3;
    private static final long HOOF_PERIOD = 6;
    private static final int PUFF_CUBES = 3;

    // active: cloud bridge
    /** Pitch (degrees) beyond which the bridge climbs (looking up) or descends (looking down). */
    private static final float CLIMB_PITCH = -15f;
    private static final float DESCEND_PITCH = 30f;
    private static final long REVEAL_TICKS = 8;
    private static final long RETRY_PERIOD = 3;
    private static final long BOOST_PERIOD = 4;
    /** Allies this far from the qilin are still checked for (and lose) the bridge boost. */
    private static final double BOOST_REACH = 64;
    private static final double LANE_REACH_UP = 1.3;
    private static final double LANE_REACH_DOWN = 0.7;
    /** Allies on the bridge's blocks get a slow fall this many ticks before the blocks expire. */
    private static final long SINK_LEAD = 6;
    /** Someone this high over the bridge's walking surface (jumping) still stands on it. */
    private static final double DECK_REACH_UP = 3;

    // ultimate: auspicious cloud
    private static final int MIN_CLOUD_HEIGHT = 3;
    private static final long SPREAD_TICKS = 6;
    private static final long LIFT_WINDOW = 40;
    private static final double LIFT_MARGIN = 1.3;
    private static final double SLIP_PUSH = 0.55;
    private static final long SINK_SLOW_FALL = 100;
    /** A rider this far below the highest point it reached is falling (it missed the cloud). */
    private static final double FALL_SLACK = 0.25;
    private static final long DISSOLVE_TICKS = 6;
    private static final double SHADOW_DEPTH = 8;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final InputWatch input = new InputWatch();
    private boolean watching;
    private boolean jumpHeld;
    private int steps;
    private long stepUntil = Long.MIN_VALUE / 2;
    private @Nullable Bridge bridge;
    private @Nullable Cloud cloud;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        Bukkit.getPluginManager().registerEvents(input, ctx().plugin());
        watching = true;
    }

    @Override
    public void onRevoke() {
        if (watching) {
            HandlerList.unregisterAll(input);
            watching = false;
        }
        Cloud current = cloud;
        if (current != null) {
            current.release(true);
        }
        Bridge laid = bridge;
        if (laid != null) {
            laid.abort();
        }
        cloud = null;
        bridge = null;
        steps = 0;
        live.clear();
    }

    // ------------------------------------------------------------------ passive: the gentle beast

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        if (steps > 0 && now > stepUntil) {
            steps = 0;
            Vfx.cloud(ctx(), owner.getLocation().add(0, 0.1, 0), Particle.CLOUD, 3, 0.2, 0.01, null, false);
        }
        if (owner.isInvisible()) {
            return;
        }
        Location feet = owner.getLocation().add(0, 0.05, 0);
        if (steps > 0 && tick % 4 == 0) {
            // A charged cloud step: a jade-and-gold wisp curls under the hooves.
            double angle = tick * 0.35;
            Vfx.dot(feet.clone().add(Math.cos(angle) * 0.35, 0.05, Math.sin(angle) * 0.35),
                    Particle.DUST_COLOR_TRANSITION, WISP, false);
            Vfx.dot(feet.clone().add(-Math.cos(angle) * 0.35, 0.05, -Math.sin(angle) * 0.35), Particle.DUST,
                    CLOUD_DUST, false);
        }
        if (owner.isSprinting() && tick % HOOF_PERIOD == 0 && Motion.isGrounded(owner)) {
            // Golden hoof prints: it treads so lightly the grass stays standing.
            Vector side = Motion.horizontalFacing(feet);
            side = new Vector(-side.getZ(), 0, side.getX()).multiply((tick / HOOF_PERIOD) % 2 == 0 ? 0.18 : -0.18);
            Vfx.dot(feet.clone().add(side), Particle.DUST, GOLD_DUST, false);
        }
    }

    /** The owner's own melee swing at an enemy (not ability damage, projectiles or summon bites). */
    private boolean isOwnMelee(EntityDamageByEntityEvent event, LivingEntity victim) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && (event.getCause() == DamageCause.ENTITY_ATTACK || event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK)
                && ctx().isEnemy(player(), victim);
    }

    /** The gentle beast holds back: only changes the damage, since the victim may still cancel the hit. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOwnMelee(event, victim)) {
            return;
        }
        double penalty = Math.clamp(params().num("melee_penalty") / 100.0, 0.0, 1.0);
        event.setDamage(event.getDamage() * (1 - penalty));
    }

    /** The blow landed: the most hurt ally nearby is healed, and a cloud step is charged. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnMelee(event, victim)) {
            return;
        }
        Player owner = player();
        Player hurt = mostHurtAlly(owner, params().num("heal_radius"));
        if (hurt != null) {
            ctx().heal(hurt, params().num("heal_amount"));
            Location from = Fx.body(victim);
            Location to = Fx.body(hurt);
            if (seen(owner, victim)) {
                Vfx.trail(from, to, GOLD, 12, false);
            }
            Vfx.cloud(ctx(), to.clone().add(0, 0.4, 0), Particle.HEART, 1, 0.2, 0, null, false);
            Vfx.cloud(ctx(), to, Particle.DUST, 5, 0.3, 0, GOLD_DUST, false);
            Fx.sound(to, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.4f + Fx.jitter(0.1f));
        }
        boolean fresh = steps == 0;
        steps = 1;
        stepUntil = ctx().currentTick() + Math.max(1, params().ticks("step_duration"));
        if (fresh) {
            Vfx.cloud(ctx(), owner.getLocation().add(0, 0.1, 0), Particle.CLOUD, 4, 0.25, 0.01, null, false);
            Fx.sound(owner.getLocation(), Sound.BLOCK_WOOL_STEP, 0.8f, 1.4f + Fx.jitter(0.1f));
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">☁ 구름 발판</" + GOLD_TAG + "> <gray>공중에서 점프하면 한 번 "
                    + "더 뛰어오릅니다</gray>"));
        }
    }

    /** The ally (not the owner) missing the most health within {@code radius}, null if nobody is hurt. */
    private @Nullable Player mostHurtAlly(Player owner, double radius) {
        Player best = null;
        double missing = 1.0E-6;
        for (Player ally : ctx().alliesNear(owner, owner.getLocation(), radius)) {
            double gap = Combat.maxHealth(ally) - ally.getHealth();
            if (gap > missing && !ally.isDead()) {
                missing = gap;
                best = ally;
            }
        }
        return best;
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !Combat.isFall(event)) {
            return;
        }
        // It walks on clouds: no fall ever hurts it.
        event.setCancelled(true);
        Location feet = player().getLocation().add(0, 0.1, 0);
        Vfx.cloud(ctx(), feet, Particle.CLOUD, 6, 0.3, 0.02, null, false);
        Fx.sound(feet, Sound.BLOCK_WOOL_FALL, 0.8f, 1.2f + Fx.jitter(0.1f));
    }

    /** Watches the owner's jump key: a jump pressed in mid-air uses the cloud step. */
    private final class InputWatch implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onInput(PlayerInputEvent event) {
            if (!event.getPlayer().equals(player())) {
                return;
            }
            try {
                Input keys = event.getInput();
                boolean jump = keys.isJump();
                if (jump && !jumpHeld) {
                    cloudStep(keys);
                }
                jumpHeld = jump;
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Qilin cloud step failed", e);
            }
        }
    }

    /** A cloud blooms under the hooves in mid-air and carries the qilin up once more. */
    private void cloudStep(Input keys) {
        Player owner = player();
        if (steps <= 0 || ctx().currentTick() > stepUntil || !ctx().isAlive(owner)) {
            return;
        }
        if (Motion.isGrounded(owner) || owner.isInWater() || owner.isFlying() || owner.isGliding()) {
            return; // an ordinary jump
        }
        steps = 0;
        Vector forward = Motion.horizontalFacing(owner.getLocation());
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        Vector push = forward.clone().multiply((keys.isForward() ? 1 : 0) - (keys.isBackward() ? 1 : 0))
                .add(right.multiply((keys.isRight() ? 1 : 0) - (keys.isLeft() ? 1 : 0)));
        if (push.lengthSquared() > 1.0E-6) {
            push.normalize().multiply(STEP_PUSH);
        }
        push.setY(Motion.launchSpeed(Math.max(0, params().num("step_height"))));
        owner.setVelocity(Motion.clamp(push, MAX_SPEED));
        owner.setFallDistance(0);
        bloom(owner);
    }

    /** A little cloud puffs up out of thin air under the feet and fades. */
    private void bloom(Player owner) {
        Location feet = Displays.unrotated(owner.getLocation());
        Vfx.ring(ctx(), feet.clone().add(0, 0.05, 0), 0.6, 12, 0, Particle.DUST, CLOUD_DUST, false);
        Vfx.cloud(ctx(), feet, Particle.CLOUD, 8, 0.3, 0.02, null, false);
        Vfx.cloud(ctx(), feet, Particle.DUST, 4, 0.3, 0, GOLD_DUST, false);
        Fx.sound(feet, Sound.BLOCK_WOOL_PLACE, 1.0f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(feet, Sound.ENTITY_HORSE_JUMP, 0.6f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(feet, Sound.ENTITY_BREEZE_JUMP, 0.5f, 1.4f + Fx.jitter(0.1f));
        if (!reserve(PUFF_CUBES)) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < PUFF_CUBES; i++) {
            double angle = i * Math.PI * 2 / PUFF_CUBES + random.nextDouble(0.4);
            float x = (float) (Math.cos(angle) * 0.25);
            float z = (float) (Math.sin(angle) * 0.25);
            float size = 0.32f + random.nextFloat() * 0.12f;
            BlockDisplay puff = keep(Displays.block(ctx(), owner, feet, (i == 0 ? CLOUD_PUFF : CLOUD_BLOCK)
                    .createBlockData(), Displays.transform(x, -0.2f, z, 0.05f, 0.05f, 0.05f, 0, 0, 0), 14));
            Displays.animateNextTick(ctx(), owner, puff, Displays.transform(x * 1.4f, -0.25f, z * 1.4f, size, size * 0.6f,
                    size, random.nextFloat() * 90, 0, 0), 3);
            ctx().runLater(owner, 5, () -> {
                if (puff.isValid()) {
                    Displays.shrinkAway(ctx(), owner, puff, 8);
                }
            });
        }
    }

    // ------------------------------------------------------------------ active: cloud bridge

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location feet = owner.getLocation();
        float pitch = owner.getEyeLocation().getPitch();
        int slope = pitch <= CLIMB_PITCH ? 1 : pitch >= DESCEND_PITCH ? -1 : 0;
        Vector direction = Motion.horizontalFacing(feet);
        Bridge laid = new Bridge(owner, feet, direction, slope);
        if (laid.cells.isEmpty()) {
            return ActivationResult.fail("구름다리를 놓을 자리가 없습니다");
        }
        bridge = laid;
        laid.begin();
        return ActivationResult.success();
    }

    /** One block column of the bridge: where its walking surface is and the block that carries it. */
    private static final class Span {
        final int x;
        final int z;
        final double along;
        final double surface;
        final int blockY;
        final boolean slab;
        boolean revealed;
        boolean placed;
        /** Waiting for someone standing where its block goes. */
        boolean blocked;
        /** The block type put in, if {@link #placed}. */
        @Nullable Material material;

        Span(int x, int z, double along, double surface) {
            this.x = x;
            this.z = z;
            this.along = along;
            this.surface = surface;
            double whole = Math.floor(surface + 1.0E-6);
            this.slab = surface - whole > 0.25;
            this.blockY = slab ? (int) whole : (int) whole - 1;
        }
    }

    /**
     * The cloud bridge: a lane of block columns ahead of the owner, each with a walking surface that climbs (or falls)
     * half a block per block when cast looking up (or down), up to {@code bridge_rise}. It surges forward over
     * {@link #REVEAL_TICKS}; where the surface lies above air a cloud block (or a quartz half step) is put there for the
     * bridge's time, never into anyone's body (it waits for them to move). Over solid ground the lane is a cloud mist
     * that is still the bridge: the owner and allies on it are faster, and enemies in the surge are hit and lifted.
     * Just before the blocks go (and at once when the qilin falls: then the bridge melts with it) the allies standing on
     * them get a slow fall, so a five-block stair never drops them.
     */
    private final class Bridge implements LongPredicate {

        private final Player owner;
        private final World world;
        private final long duration = Math.max(20, params().ticks("bridge_duration"));
        private final long endTick;
        private final List<Span> cells = new ArrayList<>();
        private final Map<Long, Span> lane = new HashMap<>();
        private final Set<UUID> lifted = new HashSet<>();
        private final Map<UUID, NamespacedKey> boosted = new HashMap<>();
        private final Set<UUID> sunk = new HashSet<>();
        private final Vector direction;
        private final double length;

        Bridge(Player owner, Location feet, Vector direction, int slope) {
            this.owner = owner;
            this.world = feet.getWorld();
            this.direction = direction;
            this.length = Math.max(2, params().num("bridge_length"));
            this.endTick = ctx().currentTick() + REVEAL_TICKS + duration;
            double width = Math.max(1, params().num("bridge_width"));
            int maxSteps = (int) Math.round(Math.max(0, params().num("bridge_rise")) * 2);
            double base = Math.floor(feet.getY() * 2 + 1.0E-6) / 2;
            Vector across = new Vector(-direction.getZ(), 0, direction.getX());
            double reach = length + width + 1;
            for (int x = (int) Math.floor(feet.getX() - reach); x <= (int) Math.floor(feet.getX() + reach); x++) {
                for (int z = (int) Math.floor(feet.getZ() - reach); z <= (int) Math.floor(feet.getZ() + reach); z++) {
                    double rx = x + 0.5 - feet.getX();
                    double rz = z + 0.5 - feet.getZ();
                    double along = rx * direction.getX() + rz * direction.getZ();
                    double side = rx * across.getX() + rz * across.getZ();
                    if (along < 0.6 || along > length + 0.5 || side <= -width / 2 || side > width / 2) {
                        continue;
                    }
                    int level = slope == 0 ? 0 : slope * Math.min(maxSteps, (int) Math.floor(along + 0.5));
                    Span span = new Span(x, z, along, base + level * 0.5);
                    if (!ctx().isInsideArena(new Location(world, x + 0.5, span.surface, z + 0.5))) {
                        continue;
                    }
                    cells.add(span);
                    lane.put(column(x, z), span);
                }
            }
            cells.sort(Comparator.comparingDouble(span -> span.along));
        }

        void begin() {
            Location feet = owner.getLocation();
            Fx.sound(feet, Sound.BLOCK_WOOL_PLACE, 1.2f, 0.8f);
            Fx.sound(feet, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f + Fx.jitter(0.1f));
            Fx.sound(feet, Sound.ENTITY_HORSE_JUMP, 0.8f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(feet, Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.8f);
            Vfx.cloud(ctx(), Fx.body(owner), Particle.DUST, 8, 0.3, 0, GOLD_DUST, false);
            Loop.repeat(ctx(), owner, 0, 1, REVEAL_TICKS + duration, this, this::finish);
        }

        @Override
        public boolean test(long tick) {
            if (tick < REVEAL_TICKS) {
                double front = length * (tick + 1) / REVEAL_TICKS;
                for (Span span : cells) {
                    if (!span.revealed && span.along <= front) {
                        reveal(span);
                    }
                }
                if (tick % 2 == 0) {
                    Location tip = owner.getLocation().add(direction.clone().multiply(Math.min(front, length)));
                    Fx.sound(tip, Sound.BLOCK_WOOL_PLACE, 0.8f, 1.0f + 0.1f * tick);
                }
            } else if (tick % RETRY_PERIOD == 0) {
                for (Span span : cells) {
                    if (span.blocked) {
                        place(span);
                    }
                }
            }
            if (tick % BOOST_PERIOD == 0) {
                boost();
            }
            if (tick % 3 == 0) {
                mist(tick);
            }
            long left = endTick - ctx().currentTick();
            if (left == 10) {
                Fx.sound(owner.getLocation(), Sound.BLOCK_WOOL_BREAK, 0.8f, 1.2f);
            }
            if (left <= SINK_LEAD) {
                sink();
            }
            return true;
        }

        /** The surge reaches a column: the cloud block goes in, and an enemy standing in the way is thrown up. */
        private void reveal(Span span) {
            span.revealed = true;
            Location top = new Location(world, span.x + 0.5, span.surface, span.z + 0.5);
            BoundingBox cell = BoundingBox.of(world.getBlockAt(span.x, span.blockY, span.z));
            for (LivingEntity enemy : ctx().enemiesNear(owner, top, 2.0)) {
                if (lifted.contains(enemy.getUniqueId())
                        || !inColumn(enemy, span) && !enemy.getBoundingBox().overlaps(cell)) {
                    continue;
                }
                lifted.add(enemy.getUniqueId());
                Combat.hit(ctx(), owner, enemy, params().num("bridge_damage"));
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Motion.push(enemy, new Vector(0, Motion.launchSpeed(Math.max(0, params().num("bridge_lift"))), 0),
                            MAX_SPEED);
                }
                Location body = Fx.body(enemy);
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 1.2f + Fx.jitter(0.08f));
                if (seen(owner, enemy)) {
                    Vfx.burst(ctx(), enemy.getLocation().add(0, 0.3, 0), PALETTE, 0.5, Particle.CLOUD, false);
                }
            }
            place(span);
            Vfx.cloud(ctx(), top.clone().add(0, 0.15, 0), Particle.CLOUD, 2, 0.25, 0.01, null, false);
            if (ThreadLocalRandom.current().nextInt(3) == 0) {
                Vfx.dot(top.clone().add(Fx.jitter(0.4f), 0.3, Fx.jitter(0.4f)), Particle.DUST, GOLD_DUST, false);
            }
        }

        /** Puts the column's cloud block in, unless someone's body is in the way (then it waits). */
        private void place(Span span) {
            Block block = world.getBlockAt(span.x, span.blockY, span.z);
            if (bodyIn(BoundingBox.of(block))) {
                span.blocked = true;
                return;
            }
            span.blocked = false;
            long left = endTick - ctx().currentTick();
            if (left <= 2 || !block.isEmpty() && !block.isReplaceable()) {
                return;
            }
            Material material = span.slab ? CLOUD_STEP : (span.x + span.z) % 3 == 0 ? CLOUD_PUFF : CLOUD_BLOCK;
            if (ctx().placeTempBlock(block, material.createBlockData(), left)) {
                span.placed = true;
                span.material = material;
            }
        }

        private boolean bodyIn(BoundingBox cell) {
            Location middle = cell.getCenter().toLocation(world);
            for (Entity entity : world.getNearbyEntities(middle, 2.5, 3, 2.5)) {
                if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()
                        && living.getBoundingBox().expand(0.05, 0, 0.05).overlaps(cell)) {
                    return true;
                }
            }
            return false;
        }

        /** True if the entity stands in the span's column, at (or just over) its walking surface. */
        private boolean inColumn(LivingEntity entity, Span span) {
            BoundingBox box = entity.getBoundingBox();
            BoundingBox column = new BoundingBox(span.x, span.surface - LANE_REACH_DOWN, span.z, span.x + 1,
                    span.surface + LANE_REACH_UP, span.z + 1);
            return box.getMinY() <= column.getMaxY() && box.getMinY() >= column.getMinY() - 1.0E-6
                    && box.getMinX() < column.getMaxX() && box.getMaxX() > column.getMinX()
                    && box.getMinZ() < column.getMaxZ() && box.getMaxZ() > column.getMinZ();
        }

        private boolean onLane(LivingEntity entity) {
            Location at = entity.getLocation();
            Span span = lane.get(column(at.getBlockX(), at.getBlockZ()));
            return span != null && span.revealed && at.getY() >= span.surface - LANE_REACH_DOWN
                    && at.getY() <= span.surface + LANE_REACH_UP;
        }

        /** The owner and allies on the bridge are faster; the boost goes as soon as they step off. */
        private void boost() {
            double speed = Math.max(0, params().num("bridge_speed")) / 100.0;
            if (speed <= 0) {
                return;
            }
            List<Player> riders = new ArrayList<>(ctx().alliesNear(owner, owner.getLocation(), BOOST_REACH));
            riders.add(owner);
            long left = Math.max(1, endTick - ctx().currentTick());
            for (Player rider : riders) {
                UUID id = rider.getUniqueId();
                NamespacedKey key = boosted.get(id);
                boolean on = onLane(rider);
                if (on && key == null) {
                    boosted.put(id, ctx().addModifier(owner, rider, Attribute.MOVEMENT_SPEED, speed,
                            Operation.MULTIPLY_SCALAR_1, left + 2));
                    Vfx.cloud(ctx(), rider.getLocation().add(0, 0.2, 0), Particle.DUST, 4, 0.25, 0, GOLD_DUST, false);
                } else if (!on && key != null) {
                    ctx().removeModifier(rider, key);
                    boosted.remove(id);
                }
            }
        }

        /** Cloud mist drifts over the lane, with jade-and-gold wisps rising from it. */
        private void mist(long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int revealed = 0;
            for (Span span : cells) {
                if (span.revealed) {
                    revealed++;
                }
            }
            if (revealed == 0) {
                return;
            }
            for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                Span span = cells.get(random.nextInt(cells.size()));
                if (!span.revealed) {
                    continue;
                }
                Location top = new Location(world, span.x + random.nextDouble(), span.surface + 0.1,
                        span.z + random.nextDouble());
                Vfx.dot(top, Particle.DUST, CLOUD_DUST, false);
                if (i == 0 && tick % 6 == 0) {
                    Vfx.dot(top.add(0, 0.3, 0), Particle.DUST_COLOR_TRANSITION, WISP, false);
                }
            }
        }

        /** True if {@code entity}'s body stands over one of the bridge's blocks (or jumps just above it). */
        private boolean onDeck(LivingEntity entity) {
            BoundingBox box = entity.getBoundingBox();
            double feet = box.getMinY();
            for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX() - 1.0E-6); x++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ() - 1.0E-6); z++) {
                    Span span = lane.get(column(x, z));
                    if (span != null && span.placed && feet >= span.surface - LANE_REACH_DOWN
                            && feet <= span.surface + DECK_REACH_UP) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** The blocks are about to go: the allies on them float down instead of falling (the qilin never falls). */
        private void sink() {
            for (Player ally : ctx().alliesNear(owner, owner.getLocation(), BOOST_REACH)) {
                if (ally.equals(owner) || sunk.contains(ally.getUniqueId()) || !ctx().isAlive(ally) || !onDeck(ally)) {
                    continue;
                }
                sunk.add(ally.getUniqueId());
                ally.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, (int) SINK_SLOW_FALL, 0, false,
                        false, true));
                ally.setFallDistance(0);
                Vfx.cloud(ctx(), ally.getLocation().add(0, 0.1, 0), Particle.CLOUD, 4, 0.3, 0.02, null, false);
            }
        }

        /** The speed boosts end (they are timed, so they would outlast a fallen qilin). */
        private void unboost() {
            for (Map.Entry<UUID, NamespacedKey> entry : boosted.entrySet()) {
                Player rider = Bukkit.getPlayer(entry.getKey());
                if (rider != null) {
                    ctx().removeModifier(rider, entry.getValue());
                }
            }
            boosted.clear();
        }

        /**
         * The qilin is gone mid-bridge (the loop stops without {@link #finish}): the bridge melts at once under a slow
         * fall for the allies on it, and the boosts end.
         */
        void abort() {
            sink();
            unboost();
            if (ctx().currentTick() < endTick) {
                for (Span span : cells) {
                    Block block = world.getBlockAt(span.x, span.blockY, span.z);
                    if (span.placed && block.getType() == span.material) {
                        ctx().restoreTempBlock(block);
                        if (ThreadLocalRandom.current().nextInt(3) == 0) {
                            Vfx.cloud(ctx(), block.getLocation().add(0.5, 0.5, 0.5), Particle.CLOUD, 1, 0.3, 0.01,
                                    null, false);
                        }
                    }
                    span.placed = false;
                }
            }
            if (bridge == this) {
                bridge = null;
            }
        }

        /** The bridge is gone (its blocks expire with it): the boosts end. */
        void finish() {
            sink();
            unboost();
            for (Span span : cells) {
                if (span.placed && ThreadLocalRandom.current().nextInt(4) == 0) {
                    Vfx.cloud(ctx(), new Location(world, span.x + 0.5, span.surface, span.z + 0.5), Particle.CLOUD, 1,
                            0.3, 0.01, null, false);
                }
            }
            if (bridge == this) {
                bridge = null;
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: auspicious cloud

    @Override
    public ActivationResult onUltimate() {
        if (cloud != null) {
            return ActivationResult.fail("이미 상서로운 구름이 떠 있습니다");
        }
        Player owner = player();
        Location ground = owner.getLocation();
        Spots.snapToGround(ground.getWorld(), ground, 3);
        double radius = Math.max(2, params().num("cloud_radius"));
        Integer layer = cloudLayer(ground, radius);
        if (layer == null) {
            return ActivationResult.fail("머리 위가 막혀 구름을 펼칠 수 없습니다");
        }
        Cloud spread = new Cloud(owner, Displays.unrotated(ground), layer, radius);
        cloud = spread;
        spread.begin();
        return ActivationResult.success();
    }

    /**
     * The block layer the cloud is laid in: {@code cloud_height} blocks over the ground (its top surface), lower when a
     * ceiling is in the way, at least {@link #MIN_CLOUD_HEIGHT}; null if there is no room overhead at all.
     */
    private @Nullable Integer cloudLayer(Location ground, double radius) {
        World world = ground.getWorld();
        int feet = (int) Math.floor(ground.getY() + 1.0E-3);
        int height = Math.max(MIN_CLOUD_HEIGHT, (int) Math.round(params().num("cloud_height")));
        double probe = Math.min(radius, 2.5);
        for (int h = height; h >= MIN_CLOUD_HEIGHT; h--) {
            int layer = feet + h - 1;
            if (layer + 3 >= world.getMaxHeight()) {
                continue;
            }
            boolean clear = true;
            // The way up over the owner's head, and room to stand on the cloud's middle.
            for (int y = feet + 2; y <= layer + 2 && clear; y++) {
                clear = world.getBlockAt(ground.getBlockX(), y, ground.getBlockZ()).isPassable();
            }
            int total = 0;
            int free = 0;
            for (int dx = (int) -Math.ceil(probe); dx <= Math.ceil(probe) && clear; dx++) {
                for (int dz = (int) -Math.ceil(probe); dz <= Math.ceil(probe); dz++) {
                    if (dx * dx + dz * dz > probe * probe) {
                        continue;
                    }
                    total++;
                    boolean open = true;
                    for (int y = layer; y <= layer + 2; y++) {
                        Block block = world.getBlockAt(ground.getBlockX() + dx, y, ground.getBlockZ() + dz);
                        open &= block.isEmpty() || block.isReplaceable();
                    }
                    if (open) {
                        free++;
                    }
                }
            }
            if (clear && free >= total * 0.85) {
                return layer;
            }
        }
        return null;
    }

    /** One cell of the cloud. */
    private static final class Puff {
        final int x;
        final int z;
        final double distance;
        boolean placed;
        boolean done;
        @Nullable Material material;

        Puff(int x, int z, double distance) {
            this.x = x;
            this.z = z;
            this.distance = distance;
        }
    }

    /**
     * The auspicious cloud: a disc of cloud blocks laid straight at its height (the blocks never move) while the owner
     * and the allies around are launched up onto it; the columns over a rider stay open until it is above them. For the
     * cloud's time the riders heal and hit harder downwards (a scoped damage listener: the allies' hits do not reach
     * the owner's hooks), enemies who climb on slip off, and enemies in its shadow are slowed and drummed on by the
     * hooves. Then the riders get a slow fall and the cloud melts from the rim inwards.
     *
     * <p>Only allies with open sky up to the cloud are lifted (one under an overhang would hit it and drop). A rider
     * who misses the cloud anyway (pushed aside, off the rim) gets a slow fall as soon as it starts to fall, and one who
     * steps off later as it leaves; when the qilin falls mid-cloud every rider still in the air floats down too.
     */
    private final class Cloud implements LongPredicate, Listener {

        private final Player owner;
        private final Location ground;
        private final World world;
        private final int layer;
        private final double top;
        private final double radius;
        private final long duration = Math.max(40, params().ticks("cloud_duration"));
        private final List<Puff> puffs = new ArrayList<>();
        private final Map<UUID, Player> riders = new HashMap<>();
        private final Set<UUID> landed = new HashSet<>();
        private final Set<UUID> softened = new HashSet<>();
        private final Map<UUID, NamespacedKey> shaded = new HashMap<>();
        /** The highest a rider has been since the lift: below it by {@link #FALL_SLACK}, it is falling. */
        private final Map<UUID, Double> peaks = new HashMap<>();
        /** When an enemy on the cloud may be pushed again (once its client had time to follow the last push). */
        private final Map<UUID, Long> slipAt = new HashMap<>();
        private @Nullable TextDisplay sigil;
        private boolean registered;
        private boolean done;
        private int stomps;

        Cloud(Player owner, Location ground, int layer, double radius) {
            this.owner = owner;
            this.ground = ground;
            this.world = ground.getWorld();
            this.layer = layer;
            this.top = layer + 1;
            this.radius = radius;
            int span = (int) Math.ceil(radius);
            for (int dx = -span; dx <= span; dx++) {
                for (int dz = -span; dz <= span; dz++) {
                    int x = ground.getBlockX() + dx;
                    int z = ground.getBlockZ() + dz;
                    double distance = Math.hypot(x + 0.5 - ground.getX(), z + 0.5 - ground.getZ());
                    if (distance <= radius && ctx().isInsideArena(new Location(world, x + 0.5, top, z + 0.5))) {
                        puffs.add(new Puff(x, z, distance));
                    }
                }
            }
        }

        void begin() {
            riders.put(owner.getUniqueId(), owner);
            for (Player ally : ctx().alliesNear(owner, ground, radius)) {
                if (Math.abs(ally.getLocation().getY() - ground.getY()) <= 3 && canRise(ally)) {
                    riders.put(ally.getUniqueId(), ally);
                }
            }
            for (Player rider : riders.values()) {
                double rise = top - rider.getLocation().getY() + LIFT_MARGIN;
                rider.setVelocity(new Vector(0, Motion.launchSpeed(Math.max(0.5, rise)), 0));
                rider.setFallDistance(0);
                Vfx.helix(ctx(), rider.getLocation(), 0.6, 2.2, 1.5, 2, 12, 0, Particle.DUST_COLOR_TRANSITION,
                        PALETTE.glow(1.0f), true);
                Vfx.cloud(ctx(), rider.getLocation().add(0, 0.2, 0), Particle.CLOUD, 10, 0.4, 0.05, null, true);
                if (!rider.equals(owner)) {
                    rider.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>☁ 상서로운 구름</b></" + GOLD_TAG + "> <gray>"
                            + "기린이 구름 위로 데려갑니다</gray>"));
                }
            }
            Bukkit.getPluginManager().registerEvents(this, ctx().plugin());
            registered = true;
            if (reserve(1)) {
                sigil = keep(Displays.text(ctx(), owner, ground.clone().add(0, top - ground.getY() + 3.2, 0),
                        Text.mm("<b><gradient:#FFF3C4:#E8B931>祥</gradient></b>"), 0.2f, duration + 20));
                Displays.outline(sigil, GOLD);
                Displays.animateNextTick(ctx(), owner, sigil, Displays.centered(0, 0, 0, 3.2f, 3.2f, 3.2f, 0, 0, 0), 8);
            }
            Location floor = ground.clone().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, floor, 0.5, radius, (int) SPREAD_TICKS, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.4f), true);
            Fx.sound(ground, Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1.4f);
            Fx.sound(ground, Sound.ENTITY_HORSE_GALLOP, 1.2f, 0.7f);
            Fx.sound(ground, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 1.2f);
            Fx.sound(ground, Sound.ENTITY_PHANTOM_FLAP, 1.0f, 0.6f);
            Loop.repeat(ctx(), owner, 0, 1, duration, this, this::end);
        }

        /**
         * True if the ally can be carried onto the cloud: it stands under a cloud cell, with open sky from its head up
         * to where it will stand on the cloud.
         */
        private boolean canRise(Player ally) {
            Location at = ally.getLocation();
            int x = at.getBlockX();
            int z = at.getBlockZ();
            boolean under = false;
            for (Puff puff : puffs) {
                if (puff.x == x && puff.z == z) {
                    under = true;
                    break;
                }
            }
            if (!under) {
                return false;
            }
            BoundingBox box = ally.getBoundingBox();
            int from = (int) Math.floor(at.getY() + 1.0E-3) + 2;
            for (int bx = (int) Math.floor(box.getMinX()); bx <= (int) Math.floor(box.getMaxX() - 1.0E-6); bx++) {
                for (int bz = (int) Math.floor(box.getMinZ()); bz <= (int) Math.floor(box.getMaxZ() - 1.0E-6); bz++) {
                    for (int y = from; y <= layer + 2; y++) {
                        if (!world.getBlockAt(bx, y, bz).isPassable()) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            double front = tick < SPREAD_TICKS ? radius * (tick + 1) / SPREAD_TICKS : radius + 1;
            for (Puff puff : puffs) {
                if (!puff.placed && !puff.done && puff.distance <= front) {
                    lay(puff, tick);
                }
            }
            if (tick % 20 == 0 && tick > 0) {
                heal();
            }
            if (tick % 20 == 10) {
                stomp();
            }
            slip();
            shade();
            soften();
            ambient(tick);
            return true;
        }

        /** Lays one cloud block, unless a rider is still rising through its column or someone stands in it. */
        private void lay(Puff puff, long tick) {
            Block block = world.getBlockAt(puff.x, layer, puff.z);
            BoundingBox cell = BoundingBox.of(block);
            BoundingBox shaft = new BoundingBox(puff.x, ground.getY(), puff.z, puff.x + 1, top + 0.05, puff.z + 1);
            for (Player rider : riders.values()) {
                if (tick < LIFT_WINDOW && rider.isValid() && rider.getLocation().getY() < top + 0.05
                        && rider.getBoundingBox().expand(0.3, 0, 0.3).overlaps(shaft)) {
                    return; // the rider still has to pass through
                }
            }
            for (Entity entity : world.getNearbyEntities(cell.getCenter().toLocation(world), 2, 3, 2)) {
                if (entity instanceof LivingEntity living && living.isValid()
                        && living.getBoundingBox().expand(0.05, 0, 0.05).overlaps(cell)) {
                    return;
                }
            }
            if (!block.isEmpty() && !block.isReplaceable()) {
                puff.done = true;
                return;
            }
            long left = duration - tick + DISSOLVE_TICKS + 4;
            Material material = ThreadLocalRandom.current().nextInt(10) < 3 ? CLOUD_PUFF : CLOUD_BLOCK;
            if (ctx().placeTempBlock(block, material.createBlockData(), left)) {
                puff.placed = true;
                puff.material = material;
                if (ThreadLocalRandom.current().nextInt(3) == 0) {
                    Vfx.cloud(ctx(), new Location(world, puff.x + 0.5, top + 0.2, puff.z + 0.5), Particle.CLOUD, 1, 0.3,
                            0.01, null, true);
                }
            } else {
                puff.done = true;
            }
        }

        /** True if {@code entity} stands on the cloud (or jumps just over it). */
        private boolean onCloud(Entity entity) {
            Location at = entity.getLocation();
            double flat = Math.hypot(at.getX() - ground.getX(), at.getZ() - ground.getZ());
            return entity.getWorld().equals(world) && flat <= radius + 0.4 && at.getY() >= top - 0.3
                    && at.getY() <= top + 3;
        }

        /** True if {@code entity} is under the cloud, in its shadow. */
        private boolean underCloud(Entity entity) {
            Location at = entity.getLocation();
            double flat = Math.hypot(at.getX() - ground.getX(), at.getZ() - ground.getZ());
            return entity.getWorld().equals(world) && flat <= radius && at.getY() < top - 1.0
                    && at.getY() > ground.getY() - SHADOW_DEPTH;
        }

        /** Everyone of the qilin's side on the cloud right now (the owner, lifted riders, allies who climbed up). */
        private List<Player> onTop() {
            List<Player> found = new ArrayList<>();
            if (onCloud(owner)) {
                found.add(owner);
            }
            for (Player ally : ctx().alliesNear(owner, ground.clone().add(0, top - ground.getY(), 0), radius + 3)) {
                if (onCloud(ally)) {
                    found.add(ally);
                }
            }
            return found;
        }

        private void heal() {
            double amount = Math.max(0, params().num("cloud_heal"));
            for (Player rider : onTop()) {
                if (amount > 0) {
                    ctx().heal(rider, amount);
                }
                Location feet = rider.getLocation();
                Vfx.trail(feet.clone().add(0, 0.1, 0), feet.clone().add(0, 1.9, 0), GOLD, 12, false);
                Vfx.cloud(ctx(), Fx.body(rider), Particle.DUST, 3, 0.3, 0, GOLD_DUST, false);
            }
        }

        /** The hooves drum on the cloud: every enemy in its shadow is struck from above. */
        private void stomp() {
            double damage = params().num("hoof_damage");
            int struck = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, ground, radius + SHADOW_DEPTH)) {
                if (!underCloud(enemy)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                struck++;
                if (!seen(owner, enemy)) {
                    continue;
                }
                Location head = enemy.getLocation().add(0, enemy.getHeight(), 0);
                Location under = new Location(world, head.getX(), layer - 0.05, head.getZ());
                Vfx.trail(under, head, GOLD, 6, true);
                Vfx.ring(ctx(), enemy.getLocation().add(0, 0.1, 0), 0.7, 12, stomps * 0.5,
                        Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.1f), false);
                Vfx.cloud(ctx(), under, Particle.CLOUD, 2, 0.2, 0.01, null, false);
            }
            if (struck > 0) {
                stomps++;
                Location sound = ground.clone().add(0, top - ground.getY(), 0);
                Fx.sound(sound, Sound.ENTITY_HORSE_GALLOP, 1.4f, 0.6f + Fx.jitter(0.05f));
                Fx.sound(ground, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.2f, 0.6f);
                Fx.sound(ground, Sound.ENTITY_HORSE_LAND, 1.0f, 0.7f + Fx.jitter(0.05f));
            }
        }

        /**
         * Enemies who climb onto the cloud slip off its rim. A player is pushed again only once its client has had a
         * round trip to follow the last push: the server's position trails the client by the ping, and pushing every
         * tick it still looked on the cloud sent it flying far past the rim.
         */
        private void slip() {
            long now = ctx().currentTick();
            for (LivingEntity enemy : ctx().enemiesNear(owner, ground.clone().add(0, top - ground.getY(), 0),
                    radius + 3)) {
                if (!onCloud(enemy) || now < slipAt.getOrDefault(enemy.getUniqueId(), Long.MIN_VALUE / 2)) {
                    continue;
                }
                slipAt.put(enemy.getUniqueId(), now + 1 + lagTicks(enemy));
                Vector out = Motion.horizontal(enemy.getLocation().toVector().subtract(ground.toVector()));
                if (out.lengthSquared() < 1.0E-6) {
                    out = new Vector(1, 0, 0);
                }
                Motion.push(enemy, out.multiply(SLIP_PUSH).setY(0.05), MAX_SPEED);
                if (seen(owner, enemy)) {
                    Vfx.cloud(ctx(), enemy.getLocation().add(0, 0.1, 0), Particle.CLOUD, 2, 0.2, 0.02, null, false);
                }
                if (enemy instanceof Player climber && ctx().currentTick() % 10 == 0) {
                    climber.sendActionBar(Text.mm("<gray>구름이 미끄러워 발을 디딜 수 없습니다</gray>"));
                }
            }
        }

        /** The shadow slows the enemies under it (the slow comes off as soon as they leave it). */
        private void shade() {
            double slow = Math.clamp(params().num("shadow_slow") / 100.0, 0.0, 1.0);
            Set<UUID> under = new HashSet<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, ground, radius + SHADOW_DEPTH)) {
                if (!underCloud(enemy)) {
                    continue;
                }
                under.add(enemy.getUniqueId());
                if (slow > 0 && !shaded.containsKey(enemy.getUniqueId())) {
                    shaded.put(enemy.getUniqueId(), ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow,
                            Operation.MULTIPLY_SCALAR_1, duration + DISSOLVE_TICKS));
                }
            }
            shaded.entrySet().removeIf(entry -> {
                if (under.contains(entry.getKey())) {
                    return false;
                }
                Entity entity = Bukkit.getEntity(entry.getKey());
                if (entity instanceof LivingEntity living) {
                    ctx().removeModifier(living, entry.getValue());
                }
                return true;
            });
        }

        /**
         * A rider who stepped off the cloud, or who never made it onto it (pushed aside on the way up, off the rim),
         * floats down slowly instead of falling (the qilin itself never takes fall damage anyway).
         */
        private void soften() {
            for (Player rider : riders.values()) {
                UUID id = rider.getUniqueId();
                if (!rider.isValid()) {
                    continue;
                }
                double y = rider.getLocation().getY();
                double peak = Math.max(y, peaks.getOrDefault(id, y));
                peaks.put(id, peak);
                if (onCloud(rider)) {
                    landed.add(id);
                    continue;
                }
                if (rider.equals(owner) || softened.contains(id) || Motion.isGrounded(rider)
                        || !ctx().isAlive(rider)) {
                    continue;
                }
                if (!landed.contains(id) && y > peak - FALL_SLACK) {
                    continue; // still rising towards the cloud
                }
                floatDown(rider);
            }
        }

        /** {@code rider} floats down slowly (once per cloud). */
        private void floatDown(Player rider) {
            softened.add(rider.getUniqueId());
            rider.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, (int) SINK_SLOW_FALL, 0, false,
                    false, true));
            rider.setFallDistance(0);
            Vfx.cloud(ctx(), rider.getLocation(), Particle.CLOUD, 6, 0.3, 0.02, null, false);
        }

        /** The glowing rim, drifting jade wisps, the shadow traced on the ground, the sigil turning gold. */
        private void ambient(long tick) {
            if (tick % 2 == 0) {
                Location rim = ground.clone().add(0, top - ground.getY() + 0.15, 0);
                Vfx.ring(ctx(), rim, radius + 0.3, (int) Math.max(16, radius * 4), tick * 0.03,
                        Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f), true);
            }
            if (tick % 4 == 0) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                Location wisp = Vfx.scatter(ground, radius).add(0, top - ground.getY() + 0.3 + random.nextDouble(), 0);
                Vfx.dot(wisp, Particle.DUST_COLOR_TRANSITION, WISP, false);
                Vfx.dot(Vfx.scatter(ground, radius).add(0, top - ground.getY() - 0.2, 0), Particle.CLOUD, null, false);
            }
            if (tick % 10 == 0) {
                Vfx.ring(ctx(), ground.clone().add(0, 0.1, 0), radius, (int) Math.max(14, radius * 3), 0, Particle.DUST,
                        SHADOW_DUST, false);
            }
            TextDisplay mark = sigil;
            if (mark != null && mark.isValid() && tick % 10 == 0) {
                float pulse = 3.2f + 0.25f * (float) Math.sin(tick * 0.2);
                Displays.animate(mark, Displays.centered(0, 0, 0, pulse, pulse, pulse, 0, 0, 0), 10);
            }
        }

        /** The time is up: the riders float down slowly and the cloud melts away from the rim in. */
        private void end() {
            for (Player rider : onTop()) {
                if (!softened.contains(rider.getUniqueId())) {
                    floatDown(rider);
                }
            }
            List<Puff> order = new ArrayList<>(puffs);
            order.sort(Comparator.comparingDouble((Puff puff) -> puff.distance).reversed());
            Loop.repeat(ctx(), owner, 0, 1, DISSOLVE_TICKS, run -> {
                // From the rim in, the middle last (the last run reaches it).
                double from = radius * (1 - (double) (run + 1) / DISSOLVE_TICKS);
                for (Puff puff : order) {
                    if (puff.placed && puff.distance >= from - 1.0E-6) {
                        puff.placed = false;
                        Block block = world.getBlockAt(puff.x, layer, puff.z);
                        restore(puff, block);
                        if (ThreadLocalRandom.current().nextInt(3) == 0) {
                            Vfx.cloud(ctx(), block.getLocation().add(0.5, 0.5, 0.5), Particle.CLOUD, 2, 0.3, 0.02,
                                    null, false);
                        }
                    }
                }
                return true;
            });
            Fx.sound(ground, Sound.BLOCK_WOOL_BREAK, 1.2f, 0.8f);
            Fx.sound(ground, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.4f);
            TextDisplay mark = sigil;
            if (mark != null && mark.isValid()) {
                Displays.shrinkAway(ctx(), owner, mark, 8);
            }
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">☁ 상서로운 구름이 걷힙니다</" + GOLD_TAG + ">"
                    + (stomps > 0 ? " <gray>발굽 " + stomps + "번</gray>" : "")));
            release(false);
        }

        /**
         * The riders' downward bonus: a hit by the owner or an ally standing on the cloud on an enemy below it (only
         * changes the damage, before the victim's own hooks).
         */
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onHit(EntityDamageByEntityEvent event) {
            if (done || !(event.getEntity() instanceof LivingEntity victim)) {
                return;
            }
            try {
                Player attacker = ctx().resolveAttacker(event.getDamager());
                if (attacker == null || !(attacker.equals(owner) || ctx().isAlly(owner, attacker))
                        || !ctx().isEnemy(owner, victim) || !onCloud(attacker)
                        || victim.getLocation().getY() >= top - 0.5) {
                    return;
                }
                double bonus = Math.max(0, params().num("cloud_bonus")) / 100.0;
                event.setDamage(event.getDamage() * (1 + bonus));
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Qilin cloud bonus failed", e);
            }
        }

        /** Restores a cloud block early, only if it is still the one this cloud put there. */
        private void restore(Puff puff, Block block) {
            if (block.getType() == puff.material) {
                ctx().restoreTempBlock(block);
            }
        }

        /**
         * Unregisters the bonus and lifts the shadow's slows. Released {@code early} (the qilin is gone mid-cloud), the
         * cloud melts at once under a slow fall for everyone of its side on it or still in the air on the way up,
         * rather than dropping them later.
         */
        void release(boolean early) {
            if (early && !done) {
                Map<UUID, Player> sinking = new HashMap<>();
                for (Player rider : onTop()) {
                    sinking.put(rider.getUniqueId(), rider);
                }
                for (Player rider : riders.values()) {
                    if (rider.isValid() && !Motion.isGrounded(rider)) {
                        sinking.put(rider.getUniqueId(), rider);
                    }
                }
                for (Player rider : sinking.values()) {
                    if (ctx().isAlive(rider)) {
                        rider.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, (int) SINK_SLOW_FALL, 0,
                                false, false, true));
                        rider.setFallDistance(0);
                    }
                }
                for (Puff puff : puffs) {
                    if (puff.placed) {
                        puff.placed = false;
                        restore(puff, world.getBlockAt(puff.x, layer, puff.z));
                    }
                }
            }
            done = true;
            if (registered) {
                HandlerList.unregisterAll(this);
                registered = false;
            }
            for (Map.Entry<UUID, NamespacedKey> entry : shaded.entrySet()) {
                Entity entity = Bukkit.getEntity(entry.getKey());
                if (entity instanceof LivingEntity living) {
                    ctx().removeModifier(living, entry.getValue());
                }
            }
            shaded.clear();
            if (cloud == this) {
                cloud = null;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** How many ticks a player's client runs ahead of the server's view of it (its ping), 0 for any other entity. */
    private static long lagTicks(LivingEntity entity) {
        return entity instanceof Player player
                ? Math.clamp(Math.round(player.getPing() / MILLIS_PER_TICK), 0, MAX_LAG_TICKS)
                : 0;
    }

    private static long column(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** True unless {@code entity} is a player hidden from {@code owner} (stealth): nothing may reveal it. */
    private static boolean seen(Player owner, Entity entity) {
        return !(entity instanceof Player hidden) || hidden.equals(owner) || owner.canSee(hidden);
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
