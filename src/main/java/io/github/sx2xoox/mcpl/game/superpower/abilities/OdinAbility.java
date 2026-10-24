package io.github.sx2xoox.mcpl.game.superpower.abilities;

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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 오딘 — the one-eyed allfather: Huginn and Muninn, the ravens of thought and memory, sit on his shoulders; he gave an
 * eye to Mímir's well for wisdom; his spear Gungnir never misses.
 *
 * <p>The passive is a public grudge: whoever hits Odin has Muninn circling over its head for a while, for everyone to
 * see, and Odin's next hit on it brings Huginn diving down for revenge. The active trades health for sight and a rune
 * strike. The ultimate is a duel that cannot be run from: the spear flies around walls and ignores everything but its
 * target, and the only defence is the timing parry when the rune ring flashes and the spear slows for its last stretch.
 *
 * <p>Look: storm grey, raven black and rune gold. The ravens are little block-built birds that perch, turn their heads
 * and fly with beating wings; Mímir's sight puts golden runes over every enemy around (for Odin's side only, through
 * walls); Gungnir is a dark ash shaft with a golden diamond head, outlined in gold, trailing storm smoke and gold sparks.
 */
public final class OdinAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("odin", "오딘", Material.SPECTRAL_ARROW)
            .color(TextColor.color(0xE2B54A))
            .difficulty(2)
            .passive("후긴과 무닌",
                    "까마귀 두 마리가 어깨에 앉아 있습니다. 적이 나를 공격하면 무닌(기억)이 마지막으로 공격한 그 적의 "
                            + "머리 위로 날아가 {memory_time}초간 맴돕니다(모두에게 보입니다). 그동안 내가 그 적을 맞히면 "
                            + "후긴(생각)이 함께 내리꽂혀 {revenge_damage} 복수 피해를 줍니다(한 번). 복수한 뒤 두 까마귀는 "
                            + "어깨로 돌아와 {raven_rest}초간 쉽니다.")
            .active("미미르의 샘",
                    "지혜의 대가로 체력 {wisdom_cost}를 바칩니다(체력 {wisdom_floor} 아래로는 내려가지 않습니다). "
                            + "{sight_time}초간 {sight_radius}블록 안의 적(가까운 {sight_max}명까지) 머리 위에 나와 아군에게만 "
                            + "보이는 룬이 떠 벽 너머로도 보이고, 그동안 내 다음 적중(무엇이든)에 {rune_damage} 룬 피해가 "
                            + "더해집니다.",
                    10)
            .ultimate("궁니르",
                    "조준한 적({spear_range}블록 이내)에게 절대 빗나가지 않는 창을 던집니다. 창은 초속 {spear_speed}블록으로 "
                            + "벽을 돌아 날아가며 대상 말고는 무엇에도 막히지 않습니다. 대상 {warn_distance}블록 앞에서 대상 "
                            + "주위에 룬 고리가 번쩍이고(날카로운 소리) 마지막 {slow_distance}블록은 초속 {slow_speed}블록으로 "
                            + "느려집니다. 그 사이 대상이 근접 공격을 휘두르면(핑만큼 여유) 창을 쳐 내어 {parry_damage} 피해만 "
                            + "받고 {parry_haste}초간 신속을 얻지만('자격 있는 자'), 못 쳐 내면 {spear_damage} 피해를 받고 약 "
                            + "{spear_knockback}블록 밀려납니다. 대상이 먼저 쓰러지거나 내 눈에서 사라지면 창이 돌아와 게이지 "
                            + "{spear_refund}%를 돌려받습니다. 창은 늦어도 {spear_time}초 안에 닿습니다.")
            .ultimateNotStandalone()
            .param("memory_time", 8)
            .param("revenge_damage", 3)
            .param("raven_rest", 6)
            .param("wisdom_cost", 2)
            .param("wisdom_floor", 2)
            .param("sight_time", 4)
            .param("sight_radius", 32)
            .param("sight_max", 6)
            .param("rune_damage", 4)
            .param("spear_range", 40)
            .param("spear_speed", 14)
            .param("warn_distance", 8)
            .param("slow_distance", 4)
            .param("slow_speed", 6)
            .param("parry_damage", 3)
            .param("parry_haste", 2)
            .param("spear_damage", 8)
            .param("spear_knockback", 4)
            .param("spear_refund", 30)
            .param("spear_time", 5)
            .build();

    private static final String GOLD_TAG = "#E2B54A";
    private static final String STORM_TAG = "#9AA0A8";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: storm grey, raven black, rune gold
    private static final Vfx.Palette RUNE = Vfx.Palette.of(0xFFF1C2, 0xE2B54A, 0x7A5A12);
    private static final Vfx.Palette STORM = Vfx.Palette.of(0xD8DCE2, 0x6B717A, 0x16161C);
    private static final Color GOLD = Color.fromRGB(0xE2B54A);
    private static final Color PALE_GOLD = Color.fromRGB(0xFFF1C2);
    private static final Color RAVEN = Color.fromRGB(0x16161C);
    private static final Particle.DustOptions GOLD_DUST = RUNE.dust(0.9f);
    private static final Particle.DustOptions GOLD_SMALL = RUNE.dust(0.55f);
    private static final Particle.DustOptions RAVEN_DUST = new Particle.DustOptions(RAVEN, 0.8f);
    private static final Particle.DustOptions STORM_DUST = STORM.dust(1.1f);
    private static final Particle.DustTransition RUNE_GLOW = RUNE.glow(1.1f);
    private static final Particle.DustTransition STORM_FADE = STORM.fade(1.3f);

    private static final Material FEATHERS = Material.BLACK_CONCRETE;
    private static final Material BEAK = Material.GRAY_CONCRETE;
    private static final Material SHAFT = Material.STRIPPED_DARK_OAK_WOOD;
    private static final Material SPEARHEAD = Material.GOLD_BLOCK;
    private static final Material COLLAR = Material.RAW_GOLD_BLOCK;
    private static final String[] RUNES = {"ᚷ", "ᛟ", "ᚦ", "ᚨ", "ᛉ", "ᚱ"};

    /** Display entities this ability keeps alive at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    /** Followers of a moving player glide this many ticks, like a client's interpolation of the player. */
    private static final int FOLLOW_GLIDE = 3;

    // the ravens
    private static final double SHOULDER_SIDE = 0.36;
    private static final double SHOULDER_HEIGHT = 0.8;
    private static final double RAVEN_SPEED = 0.85;
    private static final double CIRCLE_RADIUS = 0.85;
    private static final double CIRCLE_ABOVE = 0.9;
    private static final long DIVE_TICKS = 5;
    private static final float RAVEN_SCALE = 1.0f;

    // Mímir's sight
    private static final double MARK_ABOVE = 0.7;

    // Gungnir
    private static final float SHAFT_LENGTH = 2.4f;
    private static final double AVOID_LOOKAHEAD = 1.6;
    private static final int MAX_SLACK = 6;
    private static final double KNOCK_UP = 0.36;
    private static final double MAX_PUSH = 1.6;
    private static final long RETURN_TICKS = 12;
    private static final long STUCK_TICKS = 18;

    // ------------------------------------------------------------------ state

    private final List<Display> displays = new ArrayList<>();
    private @Nullable Raven huginn;
    private @Nullable Raven muninn;
    private @Nullable OdinListener listener;

    /** The enemy Muninn remembers, and until when. */
    private @Nullable LivingEntity remembered;
    private long memoryUntil;
    /** The ravens rest on the shoulders until then after a revenge. */
    private long restUntil = Long.MIN_VALUE / 2;

    private long sightUntil = Long.MIN_VALUE / 2;
    private long runeUntil = Long.MIN_VALUE / 2;
    private final Map<UUID, Mark> marks = new HashMap<>();
    /** True while Odin deals his own bonus damage (revenge, rune): it triggers nothing. */
    private boolean bonusHit;
    private @Nullable Gungnir spear;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        Player owner = player();
        huginn = new Raven(owner, true);
        muninn = new Raven(owner, false);
        OdinListener created = new OdinListener();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        listener = created;
    }

    @Override
    public void onRevoke() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        huginn = null;
        muninn = null;
        remembered = null;
        marks.clear();
        spear = null;
        displays.clear();
    }

    /** The Gungnir target's arm swings. */
    private final class OdinListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onSwing(PlayerArmSwingEvent event) {
            Gungnir flying = spear;
            if (flying != null) {
                flying.swung(event.getPlayer());
            }
        }
    }

    // ================================================================== passive: Huginn and Muninn

    /**
     * One raven: a little block-built bird that perches on a shoulder, flies with beating wings and circles over a
     * head. Its parts share one location (the bird's feet); every part's place and turn is in its transformation.
     */
    private final class Raven {

        private final Player owner;
        /** Huginn sits on the right shoulder, Muninn on the left. */
        private final boolean right;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private State state = State.PERCHED;
        private Location at;
        private float yaw;
        private long stateTick;
        private @Nullable LivingEntity target;
        private @Nullable Location diveFrom;

        Raven(Player owner, boolean right) {
            this.owner = owner;
            this.right = right;
            this.at = shoulder();
            this.yaw = owner.getBodyYaw();
            spawn();
        }

        private void spawn() {
            parts.removeIf(part -> !part.isValid());
            if (!parts.isEmpty() || !reserve(5)) {
                return;
            }
            for (int i = 0; i < 5; i++) {
                Material type = i == 2 ? BEAK : FEATHERS;
                BlockDisplay part = keep(Displays.block(ctx(), owner, at, type.createBlockData(), pose(i, 0, 0), 0));
                part.setTeleportDuration(FOLLOW_GLIDE);
                part.setViewRange(1.0f);
                parts.add(part);
            }
        }

        /** Where the raven perches: on its shoulder, turned with the body. */
        private Location shoulder() {
            Location feet = owner.getLocation();
            float body = owner.getBodyYaw();
            Vector side = rotate(new Vector(right ? -SHOULDER_SIDE : SHOULDER_SIDE, 0, -0.04), body);
            return Displays.unrotated(feet.add(side).add(0, owner.getHeight() * SHOULDER_HEIGHT, 0));
        }

        /**
         * Part {@code index} (0 body, 1 head, 2 beak, 3 left wing, 4 right wing) of the raven turned {@code yaw},
         * wings spread by {@code spread} (0 folded, 1 open) and raised {@code flap} degrees.
         */
        private Transformation pose(int index, double spread, double flap) {
            return switch (index) {
                case 0 -> part(0, 0.12, -0.02, 0.15, 0.15, 0.32, -8, 0);
                case 1 -> part(0, 0.26, 0.13, 0.13, 0.13, 0.13, 0, 0);
                case 2 -> part(0, 0.245, 0.235, 0.045, 0.045, 0.1, 12, 0);
                default -> {
                    int side = index == 3 ? 1 : -1;
                    double raise = Math.toRadians(spread * flap);
                    double half = 0.05 + 0.11 * spread;
                    double x = side * (0.07 + Math.cos(raise) * half);
                    double y = 0.15 + Math.sin(raise) * half;
                    float sx = (float) (0.03 + 0.2 * spread);
                    float sz = (float) (0.26 - 0.06 * spread);
                    yield part(x, y, -0.02, sx, 0.03f, sz, 0, (float) (side * spread * flap));
                }
            };
        }

        private Transformation part(double x, double y, double z, double sx, double sy, double sz, float pitch,
                                    float roll) {
            Vector offset = rotate(new Vector(x, y, z), yaw).multiply(RAVEN_SCALE);
            return Displays.transform((float) offset.getX(), (float) offset.getY(), (float) offset.getZ(),
                    (float) sx * RAVEN_SCALE, (float) sy * RAVEN_SCALE, (float) sz * RAVEN_SCALE, yaw, pitch, roll);
        }

        private void render(double spread, double flap, int glide, boolean turn) {
            spawn();
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay part = parts.get(i);
                if (!part.isValid()) {
                    continue;
                }
                if (part.getTeleportDuration() != glide) {
                    part.setTeleportDuration(glide);
                }
                Displays.moveTo(part, at);
                if (turn) {
                    Displays.animate(part, pose(i, spread, flap), Math.min(2, glide));
                }
            }
        }

        boolean home() {
            return state == State.PERCHED;
        }

        /** Muninn: fly to {@code enemy} and circle over its head. */
        void remember(LivingEntity enemy) {
            target = enemy;
            if (state != State.CIRCLING) {
                state = State.FLYING;
            }
            stateTick = ctx().currentTick();
        }

        /** Huginn: dive onto {@code enemy}. */
        void dive(LivingEntity enemy) {
            target = enemy;
            state = State.DIVING;
            diveFrom = at.clone();
            stateTick = ctx().currentTick();
        }

        void recall() {
            target = null;
            if (state != State.PERCHED) {
                state = State.RETURNING;
                stateTick = ctx().currentTick();
            }
        }

        void tick(long tick) {
            long age = ctx().currentTick() - stateTick;
            LivingEntity enemy = target;
            switch (state) {
                case PERCHED -> {
                    at = shoulder();
                    float body = owner.getBodyYaw();
                    // Now and then the raven turns its head to look around.
                    float look = (float) (Math.sin((tick + (right ? 37 : 0)) * 0.05) * 25);
                    boolean turn = Math.abs(angle(yaw, body + look)) > 4 || tick % 10 == 0;
                    yaw = body + look;
                    render(0, 0, FOLLOW_GLIDE, turn);
                }
                case FLYING -> {
                    if (enemy == null || !enemy.isValid()) {
                        recall();
                        return;
                    }
                    Location goal = circleSpot(enemy, tick);
                    if (fly(goal, tick) < 0.4) {
                        state = State.CIRCLING;
                        stateTick = ctx().currentTick();
                    }
                }
                case CIRCLING -> {
                    if (enemy == null || !enemy.isValid()) {
                        recall();
                        return;
                    }
                    Location goal = circleSpot(enemy, tick);
                    Vector heading = goal.toVector().subtract(at.toVector());
                    at = goal;
                    if (heading.lengthSquared() > 1.0E-6) {
                        yaw = yawOf(heading);
                    }
                    render(1, 30 * Math.sin(tick * 0.9), 1, true);
                    if (tick % 4 == 0) {
                        Vfx.dot(at, Particle.DUST, RAVEN_DUST, false);
                    }
                    if (tick % 30 == 0) {
                        Fx.sound(at, Sound.ENTITY_PARROT_AMBIENT, 0.5f, 0.55f + Fx.jitter(0.05f));
                    }
                }
                case DIVING -> {
                    if (enemy == null || !enemy.isValid() || diveFrom == null) {
                        recall();
                        return;
                    }
                    double t = Math.min(1.0, (age + 1) / (double) DIVE_TICKS);
                    Location to = Fx.body(enemy);
                    Vector path = to.toVector().subtract(diveFrom.toVector());
                    at = Displays.unrotated(diveFrom.clone().add(path.clone().multiply(t))
                            .add(0, Math.sin(t * Math.PI) * 0.8, 0));
                    yaw = yawOf(path);
                    render(0.6, 60, 1, true);
                    Vfx.trail(at, at.clone().subtract(path.clone().normalize().multiply(1.2)), RAVEN, 4, false);
                    Vfx.dot(at, Particle.DUST, GOLD_SMALL, false);
                }
                case RETURNING -> {
                    Location goal = shoulder();
                    if (fly(goal, tick) < 0.35 || age > 60) {
                        state = State.PERCHED;
                        at = goal;
                        render(0, 0, FOLLOW_GLIDE, true);
                        Fx.sound(at, Sound.ENTITY_PARROT_STEP, 0.6f, 1.0f);
                    }
                }
            }
        }

        /** One wing-beat towards {@code goal}; returns the distance left. */
        private double fly(Location goal, long tick) {
            Vector path = goal.toVector().subtract(at.toVector());
            double distance = path.length();
            if (distance > 1.0E-6) {
                double step = Math.min(distance, RAVEN_SPEED);
                at = Displays.unrotated(at.clone().add(path.clone().multiply(step / distance)));
                yaw = yawOf(path);
            }
            render(1, 40 * Math.sin(tick * 1.2), 1, true);
            if (tick % 6 == 0) {
                Fx.sound(at, Sound.ENTITY_PARROT_FLY, 0.5f, 0.7f + Fx.jitter(0.1f));
            }
            if (tick % 3 == 0) {
                Vfx.dot(at, Particle.DUST, RAVEN_DUST, false);
            }
            return Math.max(0, distance - RAVEN_SPEED);
        }

        private Location circleSpot(LivingEntity enemy, long tick) {
            double angle = tick * 0.16 + (right ? Math.PI : 0);
            return Displays.unrotated(enemy.getLocation().add(Math.cos(angle) * CIRCLE_RADIUS,
                    enemy.getHeight() + CIRCLE_ABOVE + Math.sin(tick * 0.2) * 0.12, Math.sin(angle) * CIRCLE_RADIUS));
        }

        Location location() {
            return at.clone();
        }
    }

    private enum State {
        PERCHED, FLYING, CIRCLING, DIVING, RETURNING
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        LivingEntity memory = remembered;
        if (memory != null && (now >= memoryUntil || !Combat.isTargetable(ctx(), owner, memory)
                || isHidden(owner, memory) || !memory.getWorld().equals(owner.getWorld()))) {
            forget();
        }
        if (huginn != null) {
            huginn.tick(tick);
        }
        if (muninn != null) {
            muninn.tick(tick);
        }
        updateSight(owner, now, tick);
        if (now < runeUntil && tick % 2 == 0) {
            // The rune strike waits in Odin's hand.
            Location hand = hand(owner);
            Vfx.dot(Vfx.scatter(hand, 0.18), Particle.DUST_COLOR_TRANSITION, RUNE_GLOW, false);
            if (tick % 6 == 0) {
                Vfx.dot(hand, Particle.ENCHANT, null, false);
            }
        }
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getFinalDamage() <= 0 || !(event instanceof EntityDamageByEntityEvent hit)) {
            return;
        }
        Player owner = player();
        LivingEntity attacker = attackerOf(owner, hit.getDamager());
        if (attacker == null || isHidden(owner, attacker)) {
            return;
        }
        long now = ctx().currentTick();
        Raven memory = muninn;
        Raven thought = huginn;
        if (memory == null || thought == null || now < restUntil || !thought.home()) {
            return;
        }
        boolean fresh = !attacker.equals(remembered);
        remembered = attacker;
        memoryUntil = now + Math.max(1, params().ticks("memory_time"));
        memory.remember(attacker);
        if (fresh) {
            Location head = attacker.getLocation().add(0, attacker.getHeight() + 0.55, 0);
            popText(owner, head, "<" + STORM_TAG + "><i>기억해 두었다</i></" + STORM_TAG + ">", 0.7f, 30);
            Fx.sound(memory.location(), Sound.ENTITY_PARROT_AMBIENT, 0.9f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(memory.location(), Sound.ENTITY_PHANTOM_FLAP, 0.6f, 1.4f);
            owner.sendActionBar(Text.mm("<" + STORM_TAG + ">✦ 무닌</" + STORM_TAG + "> <gray>— <white><enemy></white>"
                    + "을(를) 기억합니다</gray>", Placeholder.component("enemy", attacker.name())));
        }
    }

    /** The enemy behind a blow: its player (a summon's master), or the enemy mob itself. */
    private @Nullable LivingEntity attackerOf(Player owner, Entity damager) {
        Player master = ctx().resolveAttacker(damager);
        if (master != null && !master.equals(owner) && ctx().isEnemy(owner, master)) {
            return master;
        }
        if (damager instanceof LivingEntity living && ctx().isEnemy(owner, living)) {
            return living;
        }
        return null;
    }

    private void forget() {
        remembered = null;
        if (muninn != null) {
            muninn.recall();
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (bonusHit) {
            return;
        }
        Player owner = player();
        Entity damager = event.getDamager();
        if (!owner.equals(damager) && !owner.equals(ctx().resolveAttacker(damager))) {
            return;
        }
        long now = ctx().currentTick();
        if (now < runeUntil) {
            runeUntil = Long.MIN_VALUE / 2;
            runeStrike(owner, victim);
        }
        Raven thought = huginn;
        if (victim.equals(remembered) && now < memoryUntil && thought != null && thought.home()) {
            revenge(owner, victim, thought);
        }
    }

    /** Huginn dives onto the remembered enemy; both ravens fly home and rest. */
    private void revenge(Player owner, LivingEntity victim, Raven thought) {
        remembered = null;
        restUntil = ctx().currentTick() + DIVE_TICKS + Math.max(0, params().ticks("raven_rest"));
        thought.dive(victim);
        Fx.sound(thought.location(), Sound.ENTITY_PARROT_AMBIENT, 1.0f, 0.6f);
        Fx.sound(thought.location(), Sound.ENTITY_PHANTOM_SWOOP, 0.8f, 1.5f);
        ctx().runLater(owner, DIVE_TICKS, () -> {
            if (Combat.isTargetable(ctx(), owner, victim)) {
                bonus(owner, victim, params().num("revenge_damage"));
                if (!isHidden(owner, victim)) {
                    Location body = Fx.body(victim);
                    Vfx.burst(ctx(), body, STORM, 0.6, Particle.SQUID_INK, false);
                    Vfx.cloud(ctx(), body, Particle.DUST, 10, 0.4, 0, RAVEN_DUST, false);
                    popText(owner, body.clone().add(0, 1.2, 0), "<" + STORM_TAG + "><b>복수</b></" + STORM_TAG + ">",
                            0.7f, 18);
                }
                Fx.sound(Fx.body(victim), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 0.8f);
                Fx.sound(Fx.body(victim), Sound.ENTITY_PARROT_HURT, 0.8f, 0.6f);
                owner.sendActionBar(Text.mm("<" + STORM_TAG + ">✦ 후긴의 복수</" + STORM_TAG + "> <white>+"
                        + AbilityInfo.formatNumber(params().num("revenge_damage")) + "</white>"));
            }
            Raven h = huginn;
            if (h != null) {
                h.recall();
            }
            Raven m = muninn;
            if (m != null) {
                m.recall();
            }
        });
    }

    /** Damage of Odin's own bonus effects: it never triggers another bonus. */
    private void bonus(Player owner, LivingEntity victim, double amount) {
        bonusHit = true;
        try {
            Combat.hit(ctx(), owner, victim, amount);
        } finally {
            bonusHit = false;
        }
    }

    // ================================================================== active: Mímir's well

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double health = owner.getHealth();
        double floor = Math.max(0.5, params().num("wisdom_floor"));
        double paid = Math.max(Math.min(health, floor), health - Math.max(0, params().num("wisdom_cost")));
        if (paid < health) {
            owner.setHealth(Math.clamp(paid, 0.0, Combat.maxHealth(owner)));
        }
        long now = ctx().currentTick();
        sightUntil = now + Math.max(1, params().ticks("sight_time"));
        runeUntil = sightUntil;
        well(owner);
        updateSight(owner, now, 0);
        owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">ᛟ 미미르의 샘</" + GOLD_TAG + "> <gray>— 적 " + marks.size()
                + "명이 보입니다 · 다음 적중 +" + AbilityInfo.formatNumber(params().num("rune_damage")) + "</gray>"));
        return ActivationResult.success();
    }

    /** An eye given to the well: the water flashes gold under Odin and runes rise around him. */
    private void well(Player owner) {
        Location feet = owner.getLocation().add(0, 0.1, 0);
        Location eye = owner.getEyeLocation();
        Vfx.ring(ctx(), feet, 1.2, 20, 0, Particle.DUST_COLOR_TRANSITION, RUNE_GLOW, false);
        Vfx.ring(ctx(), feet, 0.7, 12, 0.4, Particle.DUST, new Particle.DustOptions(Color.fromRGB(0x5FB3C9), 1.0f),
                false);
        Vfx.cloud(ctx(), feet.clone().add(0, 0.2, 0), Particle.SPLASH, 16, 0.6, 0.1, null, false);
        Vfx.helix(ctx(), feet, 0.9, 2.2, 1.5, 2, 10, 0, Particle.ENCHANT, null, false);
        Vfx.dot(eye, Particle.FLASH, PALE_GOLD, false);
        Vfx.cloud(ctx(), eye, Particle.DUST, 6, 0.1, 0, new Particle.DustOptions(Color.fromRGB(0xB0001E), 0.6f),
                false);
        Fx.sound(eye, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.6f);
        Fx.sound(eye, Sound.ITEM_BUCKET_FILL, 0.7f, 0.8f);
        Fx.sound(eye, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 0.7f + Fx.jitter(0.05f));
        owner.playSound(eye, Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 0.5f, 0.7f);
    }

    /**
     * Keeps a golden rune over every enemy around while the sight lasts, shown to Odin, his allies and spectators but
     * never to an enemy (it would tell it nothing new, and it must not see its teammates' marks), and never over an
     * enemy hidden from Odin (it would give it away).
     */
    private void updateSight(Player owner, long now, long tick) {
        if (now >= sightUntil) {
            if (!marks.isEmpty()) {
                for (Mark mark : marks.values()) {
                    mark.remove(owner);
                }
                marks.clear();
            }
            return;
        }
        Set<UUID> seen = new HashSet<>();
        double radius = Math.max(1, params().num("sight_radius"));
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), radius)) {
            if (isHidden(owner, enemy) || seen.size() >= Math.max(1, params().integer("sight_max"))) {
                continue;
            }
            seen.add(enemy.getUniqueId());
            Mark mark = marks.get(enemy.getUniqueId());
            if (mark == null || mark.rune == null || !mark.rune.isValid()) {
                if (!reserve(1)) {
                    continue;
                }
                mark = new Mark(enemy, owner);
                marks.put(enemy.getUniqueId(), mark);
            }
            mark.follow(owner, tick);
        }
        Iterator<Map.Entry<UUID, Mark>> it = marks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Mark> entry = it.next();
            if (!seen.contains(entry.getKey())) {
                entry.getValue().remove(owner);
                it.remove();
            }
        }
    }

    /** A golden rune over one enemy, see-through, for Odin's side only. */
    private final class Mark {

        private final LivingEntity enemy;
        private final @Nullable TextDisplay rune;
        private final Set<UUID> hiddenFrom = new HashSet<>();

        Mark(LivingEntity enemy, Player owner) {
            this.enemy = enemy;
            String glyph = RUNES[ThreadLocalRandom.current().nextInt(RUNES.length)];
            TextDisplay display = keep(Displays.spawn(ctx(), owner, spot(enemy), TextDisplay.class,
                    Math.max(1, params().ticks("sight_time")) + 10, d -> {
                        d.text(Text.mm("<b><" + GOLD_TAG + ">" + glyph + "</" + GOLD_TAG + "></b>"));
                        d.setBillboard(Display.Billboard.CENTER);
                        d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                        d.setSeeThrough(true);
                        d.setShadowed(false);
                        d.setViewRange(3.0f);
                        d.setTransformation(Displays.centered(0, 0, 0, 1.4f, 1.4f, 1.4f, 0, 0, 0));
                    }));
            display.setTeleportDuration(FOLLOW_GLIDE);
            this.rune = display;
            hideFromEnemies(owner);
            if (!isHidden(owner, enemy)) {
                Vfx.cloud(ctx(), spot(enemy), Particle.ENCHANT, 6, 0.2, 0.3, null, false);
            }
        }

        private Location spot(LivingEntity target) {
            return Displays.unrotated(target.getLocation().add(0, target.getHeight() + MARK_ABOVE, 0));
        }

        /** Hidden from every enemy of Odin's; re-checked as players join or switch sides. */
        private void hideFromEnemies(Player owner) {
            TextDisplay display = rune;
            if (display == null) {
                return;
            }
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(owner) && ctx().isEnemy(owner, online)
                        && hiddenFrom.add(online.getUniqueId())) {
                    online.hideEntity(ctx().plugin(), display);
                }
            }
        }

        void follow(Player owner, long tick) {
            TextDisplay display = rune;
            if (display == null || !display.isValid()) {
                return;
            }
            hideFromEnemies(owner);
            Displays.moveTo(display, spot(enemy));
            if (tick % 10 == 0) {
                Vfx.dot(spot(enemy).add(0, -0.25, 0), Particle.DUST, GOLD_SMALL, false);
            }
        }

        void remove(Player owner) {
            TextDisplay display = rune;
            if (display != null && display.isValid()) {
                Displays.shrinkAway(ctx(), owner, display, 4);
            }
        }
    }

    /** The rune strike: a burst of gold on the victim, a moment after the blow it rides on. */
    private void runeStrike(Player owner, LivingEntity victim) {
        double amount = params().num("rune_damage");
        ctx().runLater(owner, 1, () -> {
            if (!Combat.isTargetable(ctx(), owner, victim)) {
                return;
            }
            bonus(owner, victim, amount);
            if (!isHidden(owner, victim)) {
                Location body = Fx.body(victim);
                Vfx.dot(body, Particle.FLASH, PALE_GOLD, false);
                Vfx.burst(ctx(), body, RUNE, 0.7, Particle.ENCHANT, false);
                Vfx.ring(ctx(), body, 0.8, 14, 0, Particle.DUST_COLOR_TRANSITION, RUNE_GLOW, false);
                popText(owner, body.clone().add(0, 1.1, 0), "<b><" + GOLD_TAG + ">"
                        + RUNES[ThreadLocalRandom.current().nextInt(RUNES.length)] + "</" + GOLD_TAG + "></b>", 1.0f, 14);
            }
            Fx.sound(Fx.body(victim), Sound.BLOCK_ANVIL_LAND, 0.4f, 1.8f + Fx.jitter(0.1f));
            Fx.sound(Fx.body(victim), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 1.2f);
            Fx.sound(Fx.body(victim), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.7f, 0.8f);
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">ᛟ 룬 일격</" + GOLD_TAG + "> <white>+"
                    + AbilityInfo.formatNumber(amount) + "</white>"));
        });
    }

    // ================================================================== ultimate: Gungnir

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (spear != null) {
            return ActivationResult.fail("궁니르가 이미 날아가고 있습니다");
        }
        LivingEntity target = ctx().findTarget(owner, Math.max(1, params().num("spear_range")));
        if (target == null || isHidden(owner, target)) {
            return ActivationResult.fail("궁니르를 던질 적을 조준하세요 (" + AbilityInfo.formatNumber(
                    params().num("spear_range")) + "블록 이내)");
        }
        Gungnir thrown = new Gungnir(owner, target);
        spear = thrown;
        thrown.start();
        return ActivationResult.success();
    }

    /**
     * Gungnir in flight. It cruises at the spear speed (faster if it would not arrive in time otherwise), steering
     * around walls, until it is the warning distance from its target; then the rune ring flashes and the time to impact
     * is fixed: the next stretch at the cruise speed, the last one slow. A swing of the target in that window (and a
     * ping's worth after the impact) parries it.
     */
    private final class Gungnir implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final World world;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private final List<TextDisplay> ring = new ArrayList<>();
        private final double cruise;
        private final double slowSpeed;
        private final double warnDistance;
        private final double slowDistance;
        private final long maxTicks;
        private Location tip;
        private Vector heading;
        private long tick;
        /** Ticks since the throw at which the ring flashed, the spear slows, and it strikes; -1 before the flash. */
        private long flashAt = -1;
        private long slowAt;
        private long impactAt;
        private long slack;
        private boolean resolved;
        private boolean finished;

        Gungnir(Player owner, LivingEntity target) {
            this.owner = owner;
            this.target = target;
            this.world = owner.getWorld();
            this.cruise = Math.max(0.1, params().num("spear_speed") / 20.0);
            this.slowSpeed = Math.max(0.05, params().num("slow_speed") / 20.0);
            this.warnDistance = Math.max(0.5, params().num("warn_distance"));
            this.slowDistance = Math.clamp(params().num("slow_distance"), 0.1, warnDistance);
            this.maxTicks = Math.max(20, params().ticks("spear_time"));
            Location eye = owner.getEyeLocation();
            this.heading = eye.getDirection();
            if (heading.lengthSquared() < 1.0E-6) {
                heading = new Vector(0, 0, 1);
            }
            heading.normalize();
            this.tip = Displays.unrotated(eye.clone().add(heading.clone().multiply(0.8)).subtract(0, 0.2, 0));
        }

        void start() {
            if (reserve(3)) {
                long life = maxTicks + RETURN_TICKS + STUCK_TICKS + 20;
                parts.add(keep(Displays.block(ctx(), owner, tip, SHAFT.createBlockData(), shaftPose(0.2f), life)));
                parts.add(keep(Displays.block(ctx(), owner, tip, SPEARHEAD.createBlockData(), headPose(0.2f), life)));
                parts.add(keep(Displays.block(ctx(), owner, tip, COLLAR.createBlockData(), collarPose(0.2f), life)));
                for (BlockDisplay part : parts) {
                    part.setTeleportDuration(1);
                    part.setViewRange(2.5f);
                }
                Displays.outline(parts.get(0), GOLD);
                Displays.outline(parts.get(1), PALE_GOLD);
            }
            Location eye = owner.getEyeLocation();
            Vfx.dot(eye, Particle.FLASH, PALE_GOLD, true);
            Vfx.burst(ctx(), tip, RUNE, 0.6, Particle.ELECTRIC_SPARK, true);
            Vfx.cloud(ctx(), tip, Particle.ENCHANT, 20, 0.4, 0.6, null, false);
            Fx.sound(eye, Sound.ITEM_TRIDENT_THROW, 1.4f, 0.6f);
            Fx.sound(eye, Sound.ITEM_TRIDENT_THUNDER, 0.6f, 1.4f);
            Fx.sound(eye, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.6f);
            ctx().announce(Text.mm("<" + GOLD_TAG + ">ᛟ</" + GOLD_TAG + "> <white><owner></white><gray>이(가) "
                            + "<white><target></white>에게 </gray><" + GOLD_TAG + "><b>궁니르</b></" + GOLD_TAG + "><gray>를 "
                            + "던졌습니다!</gray>", Placeholder.component("owner", owner.name()),
                    Placeholder.component("target", target.name())));
            if (target instanceof Player hunted) {
                hunted.sendActionBar(Text.mm("<" + GOLD_TAG + ">ᛟ 궁니르가 당신을 노립니다</" + GOLD_TAG + "> <gray>— "
                        + "룬 고리가 번쩍이면 휘둘러 쳐 내세요</gray>"));
            }
            Loop.repeat(ctx(), owner, 1, 1, maxTicks + MAX_SLACK + 4, this, this::timeUp);
        }

        private float yaw() {
            return yawOf(heading);
        }

        private float pitch() {
            double horizontal = Math.sqrt(heading.getX() * heading.getX() + heading.getZ() * heading.getZ());
            return (float) Math.toDegrees(Math.atan2(-heading.getY(), horizontal));
        }

        /** The shaft trails behind the tip, along the flight. */
        private Transformation shaftPose(float grow) {
            Vector back = heading.clone().multiply(-SHAFT_LENGTH * grow * 0.5 - 0.15);
            return Displays.transform((float) back.getX(), (float) back.getY(), (float) back.getZ(), 0.09f, 0.09f,
                    SHAFT_LENGTH * grow, yaw(), pitch(), 0);
        }

        /** The golden head: a diamond in the vertical plane of the flight. */
        private Transformation headPose(float grow) {
            float size = 0.3f * Math.max(0.3f, grow);
            return Displays.transform(0, 0, 0, 0.03f, size, size, yaw(), pitch() + 45, 0);
        }

        private Transformation collarPose(float grow) {
            Vector back = heading.clone().multiply(-0.3);
            float size = 0.14f * Math.max(0.3f, grow);
            return Displays.transform((float) back.getX(), (float) back.getY(), (float) back.getZ(), size, size,
                    0.12f, yaw(), pitch(), 0);
        }

        private void pose() {
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay part = parts.get(i);
                Displays.moveTo(part, tip);
                Displays.animate(part, i == 0 ? shaftPose(1) : i == 1 ? headPose(1) : collarPose(1), 1);
            }
        }

        @Override
        public boolean test(long run) {
            tick = run;
            if (finished || resolved) {
                return false;
            }
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(world)
                    || isHidden(owner, target)) {
                returnHome();
                return false;
            }
            Location aim = Fx.body(target);
            Vector toTarget = aim.toVector().subtract(tip.toVector());
            double distance = toTarget.length();
            if (flashAt < 0) {
                cruise(toTarget, distance);
                if (Fx.body(target).distance(tip) <= warnDistance + 1.0E-6) {
                    flash();
                }
            } else if (tick < impactAt) {
                close(aim, toTarget, distance);
            } else {
                // Arrived: the spear hangs at its target for a ping's worth, a late swing still parries it.
                tip = Displays.unrotated(aim.clone().subtract(heading.clone().multiply(0.3)));
                pose();
                if (tick >= impactAt + slack) {
                    strike();
                    return false;
                }
            }
            trail();
            return true;
        }

        /** Cruising: straight at the target, around walls, fast enough to flash before the time is up. */
        private void cruise(Vector toTarget, double distance) {
            long deadline = Math.max(1, maxTicks - windowTicks() - tick);
            double speed = Math.max(cruise, (distance - warnDistance) / deadline);
            Vector desired = distance > 1.0E-6 ? toTarget.clone().multiply(1 / distance) : heading.clone();
            Vector chosen = avoid(desired, speed);
            heading = chosen;
            tip = Displays.unrotated(tip.clone().add(chosen.clone().multiply(Math.min(speed, distance))));
            pose();
        }

        /** Ticks from the flash to the impact: the stretch at cruise speed, then the slow one. */
        private long windowTicks() {
            return (long) Math.ceil((warnDistance - slowDistance) / cruise) + (long) Math.ceil(slowDistance
                    / slowSpeed);
        }

        /**
         * The way around a wall: straight on if clear, else the first clear turn (up first, then to the sides, then
         * down); nothing clear, it flies through (a god's spear is not stopped by stone).
         */
        private Vector avoid(Vector desired, double speed) {
            double look = Math.max(AVOID_LOOKAHEAD, speed + 0.5);
            if (clear(desired, look)) {
                return desired;
            }
            Vector up = new Vector(0, 1, 0);
            Vector lateral = up.clone().crossProduct(desired);
            if (lateral.lengthSquared() < 1.0E-6) {
                lateral = new Vector(1, 0, 0);
            }
            lateral.normalize();
            // Up and over first, then around either side, then under.
            Vector[] ways = {up, lateral, lateral.clone().multiply(-1), up.clone().multiply(-1)};
            for (double angle : new double[] {35, 60, 85}) {
                double rad = Math.toRadians(angle);
                for (Vector way : ways) {
                    Vector turned = desired.clone().multiply(Math.cos(rad)).add(way.clone().multiply(Math.sin(rad)));
                    if (turned.lengthSquared() > 1.0E-6) {
                        turned.normalize();
                        if (clear(turned, look)) {
                            return turned;
                        }
                    }
                }
            }
            return desired;
        }

        private boolean clear(Vector direction, double distance) {
            return world.rayTraceBlocks(tip, direction, distance, FluidCollisionMode.NEVER, true) == null;
        }

        /** The rune ring flashes around the target: from now on the time to impact is fixed. */
        private void flash() {
            flashAt = tick;
            slowAt = tick + (long) Math.ceil((warnDistance - slowDistance) / cruise);
            impactAt = tick + windowTicks();
            slack = target instanceof Player hunted ? Math.min(MAX_SLACK, 1 + (long) Math.ceil(Math.max(0,
                    hunted.getPing()) / 50.0)) : 0;
            Location body = Fx.body(target);
            for (int i = 0; i < 3 && reserve(1); i++) {
                TextDisplay rune = keep(Displays.text(ctx(), owner, body, Text.mm("<b><" + GOLD_TAG + ">" + RUNES[i]
                        + "</" + GOLD_TAG + "></b>"), 1.1f, windowTicks() + slack + 10));
                rune.setTeleportDuration(1);
                rune.setSeeThrough(true);
                rune.setViewRange(2.0f);
                ring.add(rune);
            }
            Vfx.ring(ctx(), body, 1.4, 26, 0, Particle.DUST_COLOR_TRANSITION, RUNE_GLOW, true);
            Vfx.dot(body, Particle.FLASH, GOLD, true);
            Fx.sound(body, Sound.BLOCK_ANVIL_LAND, 0.7f, 2.0f);
            Fx.sound(body, Sound.BLOCK_BELL_USE, 1.2f, 1.8f);
            Fx.sound(body, Sound.ITEM_TRIDENT_RIPTIDE_3, 0.8f, 1.6f);
            if (target instanceof Player hunted) {
                hunted.playSound(hunted.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1.0f, 2.0f);
                hunted.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">ᛟ 지금! 휘둘러 궁니르를 쳐 내세요</" + GOLD_TAG + "></b>"));
            }
        }

        /** After the flash: the next stretch at cruise speed, then slow, reaching the target exactly at impact. */
        private void close(Location aim, Vector toTarget, double distance) {
            long now = tick;
            double step;
            if (now < slowAt) {
                double goal = Math.max(0, distance - slowDistance);
                step = goal / Math.max(1, slowAt - now);
            } else {
                step = distance / Math.max(1, impactAt - now);
            }
            if (distance > 1.0E-6) {
                heading = toTarget.clone().multiply(1 / distance);
            }
            tip = Displays.unrotated(tip.clone().add(heading.clone().multiply(Math.min(step, distance))));
            pose();
            double left = Math.max(0, impactAt - now) / (double) Math.max(1, impactAt - flashAt);
            double radius = 0.7 + 0.8 * left;
            for (int i = 0; i < ring.size(); i++) {
                TextDisplay rune = ring.get(i);
                double angle = now * 0.35 + i * TAU / 3;
                Displays.moveTo(rune, aim.clone().add(Math.cos(angle) * radius, 0.1, Math.sin(angle) * radius));
            }
            if (now % 2 == 0) {
                Vfx.ring(ctx(), aim, radius, 14, now * 0.2, Particle.DUST, GOLD_DUST, true);
            }
            if (now >= slowAt && now % 3 == 0) {
                Fx.sound(tip, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.8f + (float) (1 - left) * 1.0f);
            }
        }

        private void trail() {
            Vector back = heading.clone().multiply(-1);
            Location tail = tip.clone().add(back.clone().multiply(SHAFT_LENGTH));
            Vfx.dot(tip, Particle.ELECTRIC_SPARK, null, true);
            Vfx.trail(tip, tail.clone().add(back.clone().multiply(1.5)), GOLD, 6, true);
            Vfx.dot(tail, Particle.DUST_COLOR_TRANSITION, STORM_FADE, false);
            if (tick % 2 == 0) {
                Vfx.dot(Vfx.scatter(tail, 0.3), Particle.CAMPFIRE_COSY_SMOKE, null, false);
                Vfx.dot(Vfx.scatter(tail, 0.4), Particle.DUST, STORM_DUST, false);
            }
            if (tick % 5 == 0) {
                Fx.sound(tip, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.5f, 1.4f + Fx.jitter(0.1f));
            }
        }

        /** The target swung its weapon: inside the window, Gungnir is struck aside. */
        void swung(Player swinger) {
            if (resolved || flashAt < 0 || !swinger.equals(target) || tick > impactAt + slack) {
                return;
            }
            resolved = true;
            parried();
        }

        private void parried() {
            Player hunted = (Player) target;
            Combat.hit(ctx(), owner, target, params().num("parry_damage"));
            int haste = (int) Math.min(Integer.MAX_VALUE, params().ticks("parry_haste"));
            if (haste > 0 && hunted.isValid() && !hunted.isDead()) {
                hunted.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, haste, 0, false, true, true));
            }
            Location body = Fx.body(target);
            Vfx.dot(body, Particle.FLASH, PALE_GOLD, true);
            Vfx.burst(ctx(), tip, RUNE, 0.8, Particle.ELECTRIC_SPARK, true);
            Vfx.cloud(ctx(), body, Particle.CRIT, 16, 0.4, 0.4, null, false);
            Fx.sound(body, Sound.ITEM_SHIELD_BLOCK, 1.2f, 0.7f);
            Fx.sound(body, Sound.BLOCK_ANVIL_LAND, 0.8f, 1.4f);
            Fx.sound(body, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
            popText(owner, body.clone().add(0, 1.3, 0), "<b><" + GOLD_TAG + ">자격 있는 자</" + GOLD_TAG + "></b>", 0.9f,
                    30);
            hunted.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">ᛟ 궁니르를 쳐 냈습니다!</" + GOLD_TAG + "></b> <gray>— 자격 "
                    + "있는 자</gray>"));
            owner.sendActionBar(Text.mm("<" + STORM_TAG + ">ᛟ <white><target></white>이(가) 궁니르를 쳐 냈습니다</"
                    + STORM_TAG + ">", Placeholder.component("target", target.name())));
            // The spear is struck aside: it tumbles off and fades.
            Vector off = heading.clone().multiply(-0.4).add(new Vector(0, 0.5, 0));
            Location away = tip.clone().add(off.multiply(3));
            Loop.repeat(ctx(), owner, 0, 1, 8, run -> {
                heading = heading.clone().rotateAroundY(0.7).add(new Vector(0, -0.15, 0)).normalize();
                tip = Displays.unrotated(tip.clone().add(away.toVector().subtract(tip.toVector()).multiply(0.3)));
                pose();
                return true;
            }, this::finish);
        }

        /** Not parried: the full weight of Gungnir. */
        private void strike() {
            if (resolved) {
                return;
            }
            resolved = true;
            Combat.hit(ctx(), owner, target, params().num("spear_damage"));
            double knock = Math.max(0, params().num("spear_knockback"));
            if (knock > 0 && Combat.isTargetable(ctx(), owner, target)) {
                Vector push = Motion.horizontal(heading);
                if (push.lengthSquared() < 1.0E-6) {
                    push = Motion.horizontal(target.getLocation().toVector().subtract(owner.getLocation().toVector()));
                }
                Motion.push(target, push.multiply(knock * 0.19).setY(KNOCK_UP), MAX_PUSH);
            }
            Location body = Fx.body(target);
            Vfx.dot(body, Particle.FLASH, GOLD, true);
            Vfx.burst(ctx(), body, RUNE, 1.0, Particle.ELECTRIC_SPARK, true);
            Vfx.shockwave(ctx(), owner, target.getLocation().add(0, 0.2, 0), 0.4, 3.0, 6,
                    Particle.DUST_COLOR_TRANSITION, RUNE_GLOW, true);
            Vfx.cloud(ctx(), body, Particle.CAMPFIRE_COSY_SMOKE, 8, 0.4, 0.02, null, false);
            Fx.sound(body, Sound.ITEM_TRIDENT_HIT, 1.4f, 0.6f);
            Fx.sound(body, Sound.ITEM_TRIDENT_THUNDER, 0.8f, 1.2f);
            Fx.sound(body, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.6f);
            owner.sendActionBar(Text.mm("<b><" + GOLD_TAG + ">ᛟ 궁니르 적중</" + GOLD_TAG + "></b> <white>"
                    + AbilityInfo.formatNumber(params().num("spear_damage")) + "</white>"));
            // It stays stuck in the ground beside the target for a moment.
            Location ground = target.getLocation().add(heading.clone().setY(0).multiply(0.6));
            heading = new Vector(heading.getX() * 0.3, -1, heading.getZ() * 0.3).normalize();
            tip = Displays.unrotated(ground.clone().add(0, 0.1, 0));
            pose();
            for (TextDisplay rune : ring) {
                if (rune.isValid()) {
                    Displays.shrinkAway(ctx(), owner, rune, 4);
                }
            }
            ctx().runLater(owner, STUCK_TICKS, this::finish);
        }

        /** The target fell first (or vanished): Gungnir flies back to Odin's hand and part of the gauge returns. */
        private void returnHome() {
            if (resolved) {
                return;
            }
            resolved = true;
            double refund = Math.clamp(params().num("spear_refund"), 0.0, 100.0);
            for (TextDisplay rune : ring) {
                if (rune.isValid()) {
                    rune.remove();
                }
            }
            Location from = tip.clone();
            Loop.repeat(ctx(), owner, 0, 1, RETURN_TICKS, run -> {
                double t = (run + 1) / (double) RETURN_TICKS;
                Location hand = hand(owner);
                Vector path = hand.toVector().subtract(from.toVector());
                if (path.lengthSquared() > 1.0E-6) {
                    heading = path.clone().normalize();
                }
                tip = Displays.unrotated(from.clone().add(path.multiply(t)));
                pose();
                Vfx.dot(tip, Particle.DUST, GOLD_DUST, false);
                return true;
            }, () -> {
                if (refund > 0) {
                    ctx().addGauge(owner, refund);
                }
                Location hand = hand(owner);
                Vfx.burst(ctx(), hand, RUNE, 0.4, Particle.ENCHANT, false);
                Fx.sound(hand, Sound.ITEM_TRIDENT_RETURN, 1.0f, 1.0f);
                owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">ᛟ 궁니르가 돌아왔습니다</" + GOLD_TAG + "> <gray>— 게이지 "
                        + AbilityInfo.formatNumber(refund) + "% 반환</gray>"));
                finish();
            });
        }

        /** The loop ran out (it never should before the impact): the spear strikes at once. */
        void timeUp() {
            if (!resolved) {
                strike();
            }
        }

        void finish() {
            if (finished) {
                return;
            }
            finished = true;
            if (spear == this) {
                spear = null;
            }
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Vfx.dot(part.getLocation(), Particle.ENCHANT, null, false);
                    Displays.shrinkAway(ctx(), owner, part, 5);
                }
            }
            for (TextDisplay rune : ring) {
                if (rune.isValid()) {
                    rune.remove();
                }
            }
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

    /** {@code local} (x left, y up, z forward) turned to face {@code yaw}. */
    private static Vector rotate(Vector local, float yaw) {
        double rad = Math.toRadians(yaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new Vector(local.getX() * cos - local.getZ() * sin, local.getY(), local.getX() * sin + local.getZ() * cos);
    }

    /** A word that pops up over {@code at} and shrinks away, for everyone. */
    private void popText(Player owner, Location at, String miniMessage, float scale, long life) {
        if (!reserve(1)) {
            return;
        }
        TextDisplay text = keep(Displays.text(ctx(), owner, at, Text.mm(miniMessage), scale * 0.4f, life));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0, 0, 0), 4);
        ctx().runLater(owner, Math.max(2, life - 5), () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 4);
            }
        });
    }

    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector side = rotate(new Vector(-0.35, 0, 0.3), owner.getLocation().getYaw());
        return eye.add(side).subtract(0, 0.45, 0);
    }

    /** The signed difference between two angles in degrees, in -180..180. */
    private static float angle(float a, float b) {
        float d = (b - a) % 360;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
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
}
