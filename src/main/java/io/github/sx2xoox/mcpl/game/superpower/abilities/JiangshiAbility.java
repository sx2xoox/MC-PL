package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Inputs;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import io.github.sx2xoox.mcpl.game.superpower.ability.Prompt;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptEndReason;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptHandle;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 강시 — the hopping corpse with a yellow talisman on its forehead and its arms stretched forward. It cannot run, but
 * every jump is a long hop that thumps whoever stands where it lands; it hunts the breath of the living (out-of-breath
 * runners take more from its claws). Its talisman nails an enemy into the same stiff body — no walking, only hops — and
 * its ultimate is "hold your breath!": on every great inhale, whoever is moving or fighting gives its breath away.
 *
 * <p>Look (talisman yellow, official-robe navy, corpse green): a yellow paper slip lettered "勅令" in red over the
 * forehead and two navy sleeves stretched forward, on the jiangshi and on everyone its talisman holds; green dust rings
 * where it lands; on an inhale, pale breath streams from the betrayed into its mouth.
 *
 * <p>Sprinting is denied by keeping the jiangshi's food at the sprint threshold every tick (eating cannot lift it), which
 * also switches vanilla natural regeneration off; the jiangshi's qi heals it instead. The breath rule judges voluntary
 * input only (kit {@link Inputs}): pushed or pulled enemies, and those standing still or crouching still, pass.
 */
public final class JiangshiAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("jiangshi", "강시", Material.YELLOW_BANNER)
            .color(TextColor.color(0xF2C230))
            .difficulty(2)
            .passive("굳은 몸",
                    "달릴 수 없는 대신 점프하면 바라보는 쪽으로 약 {hop_distance}블록 뛰어오르고, 착지할 때 "
                            + "{landing_radius}블록 안의 적에게 {landing_damage} 피해를 줍니다. 달리던 적에게 주는 "
                            + "근접 피해가 {breath_bonus}% 늘고, 자연 회복 대신 {qi_interval}초마다 {qi_heal} "
                            + "회복합니다.")
            .active("부적 붙이기",
                    "부적을 날려({range}블록) 처음 맞은 적에게 {talisman_damage} 피해를 주고 {talisman_seconds}초간 "
                            + "강시처럼 굳힙니다. 굳은 적은 걷거나 달릴 수 없고 점프로만 약 {talisman_hop}블록씩 "
                            + "움직입니다(공격과 능력은 가능). 빗나가면 쿨타임이 {miss_refund}% 돌아옵니다.",
                    9)
            .ultimate("숨 참아!",
                    "{breath_duration}초간 반경 {breath_radius}블록에서 {breath_interval}초마다 숨을 "
                            + "들이켭니다({breath_warning}초 전에 예고). 그 순간 스스로 움직이거나 공격하던 적은 "
                            + "{caught_damage} 피해와 {caught_talisman}초 부적을 받고(적마다 최대 {caught_max}번), "
                            + "가만히 멈춘 적은 무사합니다.")
            .param("hop_distance", 3)
            .param("fall_guard", 50)
            .param("landing_radius", 1.5)
            .param("landing_damage", 2)
            .param("landing_interval", 1)
            .param("breath_bonus", 20)
            .param("breath_window", 1)
            .param("qi_interval", 4)
            .param("qi_heal", 1)
            .param("range", 18)
            .param("talisman_damage", 3)
            .param("talisman_seconds", 2.5)
            .param("talisman_hop", 1.5)
            .param("miss_refund", 30)
            .param("breath_duration", 6)
            .param("breath_radius", 12)
            .param("breath_interval", 1.2)
            .param("breath_warning", 0.4)
            .param("caught_damage", 3)
            .param("caught_talisman", 1.5)
            .param("caught_max", 3)
            .build();

    // ------------------------------------------------------------------ look
    private static final String YELLOW_TAG = "#F2C230";
    private static final String RED_TAG = "#C8231E";
    /** Pale yellow, talisman yellow, old ochre. */
    private static final Vfx.Palette TALISMAN = Vfx.Palette.of(0xFFF3B0, 0xF2C230, 0x7A5A10);
    /** Corpse: pale green, corpse green, grave grey. */
    private static final Vfx.Palette CORPSE = Vfx.Palette.of(0xD6E4C4, 0x8FA67A, 0x2F3A28);
    private static final Particle.DustOptions GREEN_DUST = new Particle.DustOptions(Color.fromRGB(0x8FA67A), 1.0f);
    private static final Particle.DustOptions YELLOW_DUST = new Particle.DustOptions(Color.fromRGB(0xF2C230), 0.8f);
    private static final Particle.DustOptions NAVY_DUST = new Particle.DustOptions(Color.fromRGB(0x1E2B58), 0.9f);
    private static final Particle.DustTransition CORPSE_FADE = CORPSE.fade(1.2f);
    private static final Particle.DustTransition TALISMAN_GLOW = TALISMAN.glow(1.0f);
    private static final Color PAPER = Color.fromARGB(235, 0xF2, 0xC2, 0x30);
    private static final Color BREATH = Color.fromRGB(0xE8F0F0);
    private static final Material SLEEVE = Material.BLUE_CONCRETE;

    private static final int MAX_DISPLAYS = 24;
    /** Food at the sprint threshold: sprinting is impossible, natural regeneration is off. */
    private static final int STIFF_FOOD = 6;
    private static final double HOP_UP = 0.5;
    private static final double TALISMAN_HOP_UP = 0.42;
    private static final double MAX_HOP_SPEED = 1.0;
    private static final long HOP_GAP = 8;
    /** A jump press counts as a hop take-off this long after the feet were on the ground. */
    private static final long TAKEOFF_WINDOW = 3;
    private static final double TALISMAN_SPEED = 1.2;
    private static final double TALISMAN_HITBOX = 0.35;
    /** A swing, shot or cast at most this long before the inhale gives the breath away. */
    private static final long ATTACK_SLACK = 4;
    /** The mounted look is sent again once the head turned this far (degrees), by at most {@link #MAX_TURN}. */
    private static final float TURN_EPSILON = 2f;
    private static final float MAX_TURN = 60f;
    /** A turn's interpolation: the 3 ticks a client takes to lerp a remote player's head. */
    private static final int TURN_TICKS = 3;
    /** The slip flutters in steps this long (interpolated over the whole step). */
    private static final int FLUTTER_TICKS = 4;
    /** Text displays are bottom-anchored: a line is 10/40 of a block per unit scale, plus a 1/40 margin. */
    private static final double SLIP_HEIGHT = (2 * 10 + 1) / 40.0;
    private static final float THROWN_SCALE = 0.6f;
    /** A missed slip flutters down over this long before it burns away. */
    private static final int MISS_DROP_TICKS = 10;
    private static final double READY = 0.05 + 1.0E-6;
    private static final double COOLDOWN_SET = 0.04;
    private static final double GAUGE_DROP = 50;

    // ------------------------------------------------------------------ state
    private final List<Display> live = new ArrayList<>();
    private final Map<UUID, Long> lastSprint = new HashMap<>();
    private final Map<UUID, Long> lastThump = new HashMap<>();
    private final Map<UUID, Seal> seals = new HashMap<>();
    private @Nullable Stiffness look;
    private long lastGrounded = Long.MIN_VALUE / 2;
    private long lastHop = Long.MIN_VALUE / 2;
    private long lastJumpPress = -1;
    private boolean hopping;
    private int savedFood = 20;
    private float savedSaturation = 5f;
    private boolean granted;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        Player owner = player();
        granted = true;
        savedFood = Math.max(STIFF_FOOD, owner.getFoodLevel());
        savedSaturation = owner.getSaturation();
        stiffen(owner);
        look = new Stiffness(owner, owner, 0);
    }

    @Override
    public void onRevoke() {
        Player owner = player();
        if (granted && owner.isOnline()) {
            owner.setFoodLevel(Math.max(owner.getFoodLevel(), savedFood));
            owner.setSaturation(Math.max(owner.getSaturation(), savedSaturation));
        }
        granted = false;
        look = null;
        seals.clear();
        lastSprint.clear();
        lastThump.clear();
        live.clear();
    }

    // ================================================================== passive: the stiff body

    /** No sprinting: food held at the threshold (and no starving below it), sprint switched off. */
    private static void stiffen(Player body) {
        if (body.getFoodLevel() != STIFF_FOOD) {
            body.setFoodLevel(STIFF_FOOD);
        }
        if (body.getSaturation() > 0) {
            body.setSaturation(0);
        }
        body.setExhaustion(0);
        if (body.isSprinting()) {
            body.setSprinting(false);
        }
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        stiffen(owner);
        boolean grounded = Motion.isGrounded(owner) || owner.isInWater();
        if (grounded) {
            lastGrounded = now;
        }
        long press = ctx().lastPressTick(owner, InputKey.JUMP);
        boolean pressed = press > lastJumpPress;
        lastJumpPress = Math.max(lastJumpPress, press);
        boolean held = Inputs.isHeld(owner, InputKey.JUMP);
        if ((pressed || (held && grounded)) && now - lastGrounded <= TAKEOFF_WINDOW && now - lastHop >= HOP_GAP
                && !owner.isFlying() && !owner.isGliding() && !owner.isInWater() && !owner.isInsideVehicle()) {
            hop(owner, now);
        } else if (hopping && now - lastHop >= 4 && Motion.isGrounded(owner)) {
            land(owner, now);
        }
        long qi = Math.max(1, params().ticks("qi_interval"));
        if (tick > 0 && tick % qi == 0) {
            qi(owner);
        }
        if (tick % 2 == 0) {
            for (LivingEntity entity : ctx().enemiesNear(owner, owner.getLocation(), 24)) {
                if (entity instanceof Player enemy && enemy.isSprinting()) {
                    lastSprint.put(enemy.getUniqueId(), now);
                }
            }
        }
        Stiffness body = look;
        if (body != null) {
            body.pose();
        }
    }

    /** The take-off: a long hop where the jiangshi looks, its sleeves and talisman fluttering. */
    private void hop(Player owner, long now) {
        Location feet = owner.getLocation();
        Vector facing = Motion.horizontalFacing(feet);
        double distance = Spots.arenaReach(ctx(), feet, facing, Math.max(0, params().num("hop_distance")));
        double speed = Math.min(MAX_HOP_SPEED, distance / airSum(HOP_UP));
        owner.setVelocity(Motion.clamp(facing.multiply(speed).setY(HOP_UP), 1.5));
        owner.setFallDistance(0);
        lastHop = now;
        hopping = true;
        Vfx.cloud(ctx(), feet.clone().add(0, 0.1, 0), Particle.POOF, 3, 0.2, 0.02, null, false);
        Vfx.ring(ctx(), feet.clone().add(0, 0.05, 0), 0.4, 8, 0, Particle.DUST, GREEN_DUST, false);
        Fx.sound(feet, Sound.ENTITY_SLIME_JUMP_SMALL, 0.7f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 1.4f);
    }

    /** Blocks travelled per unit of horizontal speed during a hop launched upwards at {@code up}. */
    private static double airSum(double up) {
        double y = 0;
        double v = up;
        double sum = 0;
        double drag = 1;
        for (int t = 0; t < 60; t++) {
            sum += drag;
            drag *= 0.91;
            y += v;
            v = (v - Motion.GRAVITY) * Motion.DRAG;
            if (y <= 0 && t > 0) {
                break;
            }
        }
        return Math.max(1, sum);
    }

    /** "쿵": whoever stands where the jiangshi lands is thumped. */
    private void land(Player owner, long now) {
        hopping = false;
        Location feet = owner.getLocation();
        double radius = Math.max(0, params().num("landing_radius"));
        long interval = params().ticks("landing_interval");
        boolean thumped = false;
        for (LivingEntity enemy : ctx().enemiesNear(owner, feet, radius)) {
            if (enemy instanceof Player hidden && ctx().isConcealedFrom(hidden, owner)) {
                continue;
            }
            Long last = lastThump.get(enemy.getUniqueId());
            if (last != null && now - last < interval) {
                continue;
            }
            lastThump.put(enemy.getUniqueId(), now);
            Combat.hit(ctx(), owner, enemy, Math.max(0, params().num("landing_damage")));
            Vfx.cloud(ctx(), Fx.body(enemy), Particle.CRIT, 5, 0.3, 0.1, null, false);
            thumped = true;
        }
        Block ground = feet.clone().add(0, -0.2, 0).getBlock();
        if (!ground.getType().isAir()) {
            Vfx.cloud(ctx(), feet.clone().add(0, 0.1, 0), Particle.BLOCK, 8, 0.4, 0.05, ground.getBlockData(),
                    false);
        }
        Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.08, 0), 0.3, Math.max(0.6, radius), 4,
                Particle.DUST_COLOR_TRANSITION, CORPSE_FADE, false);
        Fx.sound(feet, Sound.BLOCK_BAMBOO_WOOD_HIT, 0.9f, 0.7f + Fx.jitter(0.05f));
        if (thumped) {
            Fx.sound(feet, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.5f, 1.3f);
        }
    }

    /** The jiangshi's qi: a slow heal instead of natural regeneration. */
    private void qi(Player owner) {
        double max = Combat.maxHealth(owner);
        if (owner.getHealth() >= max - 1.0E-9) {
            return;
        }
        ctx().heal(owner, Math.max(0, params().num("qi_heal")));
        Location chest = owner.getLocation().add(0, 1.0, 0);
        Vfx.helix(ctx(), owner.getLocation(), 0.5, 1.8, 1.0, 1, 10, 0, Particle.DUST_COLOR_TRANSITION, CORPSE_FADE,
                false);
        Vfx.cloud(ctx(), chest, Particle.HAPPY_VILLAGER, 3, 0.3, 0, null, false);
        Fx.sound(chest, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 0.6f);
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (!event.isCancelled() && Combat.isFall(event)) {
            double guard = Math.clamp(params().num("fall_guard") / 100.0, 0.0, 1.0);
            event.setDamage(event.getDamage() * (1 - guard));
        }
    }

    private boolean isOwnMelee(EntityDamageByEntityEvent event) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && (event.getCause() == DamageCause.ENTITY_ATTACK || event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK);
    }

    /** Out-of-breath runners take more from the jiangshi's claws. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOwnMelee(event) || !(victim instanceof Player enemy)) {
            return;
        }
        Long last = lastSprint.get(enemy.getUniqueId());
        boolean breathless = enemy.isSprinting()
                || (last != null && ctx().currentTick() - last <= params().ticks("breath_window"));
        if (!breathless) {
            return;
        }
        event.setDamage(event.getDamage() * (1 + Math.max(0, params().num("breath_bonus")) / 100.0));
        Location mouth = enemy.getEyeLocation().add(Motion.horizontalFacing(enemy.getLocation()).multiply(0.3));
        Vfx.cloud(ctx(), mouth, Particle.CLOUD, 4, 0.12, 0.02, null, false);
        Vfx.trail(mouth, player().getEyeLocation(), BREATH, 8, false);
    }

    // ================================================================== the stiff look (talisman + sleeves)

    /**
     * The jiangshi's look on a body: the talisman over the forehead and two sleeves stretched forward, seen by everyone
     * but the body itself (it would cover its own view) and those it is hidden from.
     *
     * <p>The parts ride the body as passengers, so every client moves them with the body's own model (per-tick
     * teleports would lead or trail a remote player's lerped model). Their transformations hold the offsets from the
     * seat (the top of the body's box) and turn with the head; they are sent again only when the head turned (by at
     * most {@link #MAX_TURN} per update, so a flick never swings them through the head) or the slip flutters.
     */
    private final class Stiffness {

        private final LivingEntity body;
        private final List<Display> parts = new ArrayList<>(3);
        private @Nullable TextDisplay slip;
        private float sentYaw = Float.NaN;
        private double sentHeight = -1;
        private long sentFlutter = Long.MIN_VALUE;

        Stiffness(Player owner, LivingEntity body, long lifetime) {
            this.body = body;
            Predicate<Player> viewers = viewer -> !viewer.equals(body) && !ctx().isConcealedFrom(body, viewer);
            Location at = seat(body);
            float yaw = body.getLocation().getYaw();
            if (reserve(1)) {
                slip = keep(Displays.spawn(ctx(), owner, at, TextDisplay.class, lifetime, viewers, d -> {
                    d.text(Text.mm("<b><" + RED_TAG + ">勅</" + RED_TAG + "></b>\n<b><" + RED_TAG + ">令</" + RED_TAG
                            + "></b>"));
                    d.setBillboard(Display.Billboard.FIXED);
                    d.setBackgroundColor(PAPER);
                    d.setShadowed(false);
                    d.setLineWidth(30);
                    d.setTransformation(slipPose(yaw, 0.03, 0));
                }));
                parts.add(slip);
            }
            for (int i = 0; i < 2 && reserve(1); i++) {
                BlockDisplay sleeve = keep(Displays.block(ctx(), owner, at, SLEEVE.createBlockData(),
                        sleevePose(yaw, i == 0 ? 1 : -1, 0.03), lifetime, viewers));
                parts.add(sleeve);
            }
            for (Display part : parts) {
                ride(body, part);
            }
            // Posed from the next tick on: the parts grow into place.
        }

        boolean valid() {
            return body.isValid() && !body.isDead();
        }

        /** The body's standing frame (a player's sneaking or swimming lowers the seat, and the parts with it). */
        private Body frame() {
            return body instanceof Player ? Body.standing(body)
                    : new Body(body.getWidth(), body.getHeight(), body.getEyeHeight());
        }

        /** The slip over the forehead, {@code grow} of its full size, fluttering by {@code flutter} degrees. */
        private Transformation slipPose(float yaw, double grow, float flutter) {
            Body frame = frame();
            double scale = frame.height() / 1.8;
            Vector at = facing(yaw).multiply(0.31 * scale)
                    .setY(frame.eyeHeight() + 0.08 * scale - frame.height());
            float s = (float) (0.42 * scale * grow);
            return Displays.centered((float) at.getX(), (float) at.getY(), (float) at.getZ(), s, s, s, yaw,
                    -8 + flutter, 0);
        }

        /** A sleeve stretched straight ahead from the shoulder ({@code sign} 1: right, -1: left). */
        private Transformation sleevePose(float yaw, double sign, double grow) {
            double scale = frame().height() / 1.8;
            Vector facing = facing(yaw);
            Vector right = new Vector(-facing.getZ(), 0, facing.getX());
            Vector at = right.multiply(0.33 * scale * sign).add(facing.multiply(0.42 * scale))
                    .setY((1.32 - 1.8) * scale);
            float w = (float) (0.22 * scale * grow);
            return Displays.transform((float) at.getX(), (float) at.getY(), (float) at.getZ(), w, w,
                    (float) (0.72 * scale * grow), yaw, 0, 0);
        }

        /** Keeps the parts seated and turned with the head: the slip over the forehead, the sleeves straight ahead. */
        void pose() {
            if (!valid()) {
                return;
            }
            for (Display part : parts) {
                ride(body, part);
            }
            float yaw = body.getLocation().getYaw();
            double height = frame().height();
            long flutter = ctx().currentTick() / FLUTTER_TICKS;
            boolean turned = Float.isNaN(sentYaw) || Math.abs(wrap(yaw - sentYaw)) >= TURN_EPSILON
                    || Math.abs(height - sentHeight) > 1.0E-3;
            if (!turned && flutter == sentFlutter) {
                return;
            }
            if (turned) {
                sentYaw = Float.isNaN(sentYaw) ? yaw : turnToward(sentYaw, yaw, MAX_TURN);
                sentHeight = height;
            }
            sentFlutter = flutter;
            int ticks = turned ? TURN_TICKS : FLUTTER_TICKS;
            TextDisplay paper = slip;
            if (paper != null && paper.isValid()) {
                float wave = (float) Math.sin(flutter * Math.PI / 2) * 4f;
                Displays.animate(paper, slipPose(sentYaw, 1, wave), ticks);
            }
            if (!turned) {
                return;
            }
            int side = 0;
            for (Display part : parts) {
                if (part instanceof BlockDisplay sleeve && sleeve.isValid()) {
                    Displays.animate(sleeve, sleevePose(sentYaw, side++ == 0 ? 1 : -1, 1), ticks);
                }
            }
        }

        void remove() {
            for (Display part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), player(), part, 4);
                }
            }
            parts.clear();
        }
    }

    // ================================================================== the talisman's hold

    /** The direction an enemy's held walking keys point (horizontal unit), or null when none is held. */
    private static @Nullable Vector walkDirection(Player player, Input input) {
        double forward = (input.isForward() ? 1 : 0) - (input.isBackward() ? 1 : 0);
        double left = (input.isLeft() ? 1 : 0) - (input.isRight() ? 1 : 0);
        if (forward == 0 && left == 0) {
            return null;
        }
        Vector facing = Motion.horizontalFacing(player.getLocation());
        Vector leftward = new Vector(facing.getZ(), 0, -facing.getX());
        Vector walk = facing.multiply(forward).add(leftward.multiply(left));
        return walk.lengthSquared() < 1.0E-6 ? null : walk.normalize();
    }

    /** Puts (or renews) the talisman on {@code target} for {@code ticks}. */
    private void seal(Player owner, LivingEntity target, long ticks) {
        Seal old = seals.remove(target.getUniqueId());
        if (old != null) {
            old.end();
        }
        Seal seal = new Seal(owner, target, ticks);
        seals.put(target.getUniqueId(), seal);
        seal.start();
    }

    /**
     * A talisman on an enemy: walking is locked (its own move speed cut to nothing), only jump-key hops move it, in the
     * direction its keys ask for; it can still fight.
     */
    private final class Seal implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final @Nullable Player enemy;
        private final long ticks;
        private final List<NamespacedKey> holds = new ArrayList<>(1);
        private @Nullable Stiffness stiff;
        private @Nullable PromptHandle note;
        private long lastPress;
        private long lastGround = Long.MIN_VALUE / 2;
        private long lastHop = Long.MIN_VALUE / 2;
        private boolean over;

        Seal(Player owner, LivingEntity target, long ticks) {
            this.owner = owner;
            this.target = target;
            this.enemy = target instanceof Player p ? p : null;
            this.ticks = Math.max(1, ticks);
            this.lastPress = enemy == null ? -1 : ctx().lastPressTick(enemy, InputKey.JUMP);
        }

        void start() {
            holds.add(ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1,
                    ticks));
            stiff = new Stiffness(owner, target, ticks + 10);
            if (enemy != null) {
                enemy.setSprinting(false);
                Prompt prompt = Prompt.builder(Text.mm("<b><" + YELLOW_TAG + ">勅令 부적!</" + YELLOW_TAG + "></b> "
                                + "<white>몸이 굳었습니다 — 점프로 콩콩 뛰어서만 움직일 수 있어요</white>"), ticks)
                        .priority(Prompt.LOW)
                        .reactionSlack(false)
                        .maxWait(0)
                        .build();
                note = ctx().prompt(owner, enemy, prompt);
            }
            Location head = target.getLocation().add(0, target.getHeight(), 0);
            Vfx.burst(ctx(), head, TALISMAN, 0.5, Particle.WAX_ON, false);
            Fx.sound(head, Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 0.6f);
            Fx.sound(head, Sound.ENTITY_ZOMBIE_INFECT, 0.6f, 1.2f);
            Loop.repeat(ctx(), owner, 1, 1, ticks, this, this::end);
        }

        @Override
        public boolean test(long run) {
            if (over) {
                return false;
            }
            if (!target.isValid() || target.isDead() || (enemy != null && !ctx().isAlive(enemy))) {
                end();
                return false;
            }
            Stiffness body = stiff;
            if (body != null) {
                body.pose();
            }
            if (enemy == null) {
                return true;
            }
            long now = ctx().currentTick();
            if (enemy.isSprinting()) {
                enemy.setSprinting(false);
            }
            if (Motion.isGrounded(enemy) || enemy.isInWater()) {
                lastGround = now;
            }
            long press = ctx().lastPressTick(enemy, InputKey.JUMP);
            boolean pressed = press > lastPress;
            lastPress = Math.max(lastPress, press);
            boolean held = Inputs.isHeld(enemy, InputKey.JUMP) && Motion.isGrounded(enemy);
            if ((pressed || held) && now - lastGround <= TAKEOFF_WINDOW && now - lastHop >= HOP_GAP) {
                lastHop = now;
                Vector walk = walkDirection(enemy, enemy.getCurrentInput());
                Vector dir = walk != null ? walk : Motion.horizontalFacing(enemy.getLocation());
                double distance = Spots.arenaReach(ctx(), enemy.getLocation(), dir,
                        Math.max(0, params().num("talisman_hop")));
                double speed = Math.min(MAX_HOP_SPEED, distance / airSum(TALISMAN_HOP_UP));
                ctx().setVoluntaryVelocity(enemy, Motion.clamp(dir.multiply(speed).setY(TALISMAN_HOP_UP), 1.2));
                Location feet = enemy.getLocation();
                Vfx.cloud(ctx(), feet.clone().add(0, 0.1, 0), Particle.POOF, 2, 0.15, 0.02, null, false);
                Fx.sound(feet, Sound.ENTITY_SLIME_JUMP_SMALL, 0.6f, 0.9f + Fx.jitter(0.1f));
            }
            if (run % 6 == 0) {
                Vfx.dot(target.getLocation().add(Fx.jitter(0.3f), target.getHeight() + 0.1, Fx.jitter(0.3f)),
                        Particle.DUST, YELLOW_DUST, false);
            }
            return true;
        }

        void end() {
            if (over) {
                return;
            }
            over = true;
            if (seals.get(target.getUniqueId()) == this) {
                seals.remove(target.getUniqueId());
            }
            for (NamespacedKey key : holds) {
                ctx().removeModifier(target, key);
            }
            holds.clear();
            Stiffness body = stiff;
            if (body != null) {
                body.remove();
            }
            PromptHandle shown = note;
            if (shown != null) {
                shown.cancel();
            }
            if (target.isValid() && !target.isDead()) {
                Location head = target.getLocation().add(0, target.getHeight(), 0);
                Vfx.cloud(ctx(), head, Particle.SMALL_FLAME, 3, 0.15, 0.01, null, false);
                Vfx.cloud(ctx(), head, Particle.WHITE_ASH, 6, 0.3, 0.01, null, false);
            }
        }
    }

    // ================================================================== active: the talisman

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 1.0E-8) {
            return ActivationResult.fail("바라보는 방향이 없습니다");
        }
        direction.normalize();
        new Throw(owner, eye.clone().add(direction.clone().multiply(0.5)), direction).start();
        Fx.sound(eye, Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.2f);
        Fx.sound(eye, Sound.ENTITY_EVOKER_CAST_SPELL, 0.6f, 1.5f);
        Fx.sound(eye, Sound.ENTITY_PHANTOM_FLAP, 0.6f, 1.8f);
        Vfx.cloud(ctx(), eye.clone().add(direction.clone().multiply(0.6)), Particle.WAX_ON, 4, 0.15, 0.05, null,
                false);
        return ActivationResult.success();
    }

    /** The flying talisman: a yellow slip spinning along its path until it hits an enemy or a wall. */
    private final class Throw implements LongPredicate {

        private final Player owner;
        private final Location at;
        private final Vector direction;
        private final double range;
        private double travelled;
        private @Nullable TextDisplay paper;
        private boolean over;

        Throw(Player owner, Location from, Vector direction) {
            this.owner = owner;
            this.at = from.clone();
            this.direction = direction.clone();
            this.range = Math.max(1, params().num("range"));
        }

        void start() {
            if (reserve(1)) {
                // Camera-facing, so the thrower and everyone around see the slip in flight, not only its target.
                paper = keep(Displays.spawn(ctx(), owner, at, TextDisplay.class, (long) (range / TALISMAN_SPEED) + 24,
                        d -> {
                            d.text(Text.mm("<b><" + RED_TAG + ">勅</" + RED_TAG + "></b>\n<b><" + RED_TAG + ">令</"
                                    + RED_TAG + "></b>"));
                            d.setBillboard(Display.Billboard.CENTER);
                            d.setBackgroundColor(PAPER);
                            d.setShadowed(false);
                            d.setLineWidth(30);
                            d.setTeleportDuration(1);
                            d.setTransformation(spinning(0));
                        }));
            }
            Loop.repeat(ctx(), owner, 1, 1, (long) Math.ceil(range / TALISMAN_SPEED) + 2, this, this::miss);
        }

        /** The flying slip turned by {@code roll} degrees in screen space, around its own middle. */
        private static Transformation spinning(float roll) {
            Vector3f middle = new Vector3f(0, (float) (SLIP_HEIGHT * THROWN_SCALE / 2), 0);
            Quaternionf turn = new Quaternionf().rotateZ((float) Math.toRadians(roll));
            return new Transformation(turn.transform(new Vector3f(middle)).negate(), turn,
                    new Vector3f(THROWN_SCALE), new Quaternionf());
        }

        @Override
        public boolean test(long run) {
            if (over) {
                return false;
            }
            double step = Math.min(TALISMAN_SPEED, range - travelled);
            if (step <= 1.0E-3) {
                miss();
                return false;
            }
            Location next = at.clone().add(direction.clone().multiply(step));
            LivingEntity hit = firstEnemy(at, step);
            if (hit != null) {
                stick(hit);
                return false;
            }
            RayTraceResult wall = at.getWorld().rayTraceBlocks(at, direction, step, FluidCollisionMode.NEVER, true);
            if (wall != null) {
                at.setX(wall.getHitPosition().getX());
                at.setY(wall.getHitPosition().getY());
                at.setZ(wall.getHitPosition().getZ());
                miss();
                return false;
            }
            Vfx.line(ctx(), at, next, 3, Particle.DUST, YELLOW_DUST, false);
            if (run % 2 == 0) {
                Vfx.dot(at, Particle.WAX_ON, null, false);
            }
            at.add(direction.clone().multiply(step));
            travelled += step;
            TextDisplay slip = paper;
            if (slip != null && slip.isValid()) {
                Displays.moveTo(slip, at);
                Displays.animate(slip, spinning(run * 40f), 1);
            }
            return true;
        }

        private @Nullable LivingEntity firstEnemy(Location from, double step) {
            Location mid = from.clone().add(direction.clone().multiply(step / 2));
            LivingEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            for (LivingEntity enemy : ctx().enemiesNear(owner, mid, step / 2 + 1.5)) {
                if (enemy instanceof Player hidden && (ctx().isConcealedFrom(hidden, owner) || !owner.canSee(hidden))) {
                    continue;
                }
                BoundingBox box = enemy.getBoundingBox().expand(TALISMAN_HITBOX);
                RayTraceResult result = box.contains(from.toVector()) ? null
                        : box.rayTrace(from.toVector(), direction, step);
                double distance = box.contains(from.toVector()) ? 0
                        : result == null ? Double.MAX_VALUE : result.getHitPosition().distance(from.toVector());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = enemy;
                }
            }
            if (best == null) {
                return null;
            }
            RayTraceResult wall = from.getWorld().rayTraceBlocks(from, direction, Math.max(0.01, bestDistance),
                    FluidCollisionMode.NEVER, true);
            return wall == null ? best : null;
        }

        private void stick(LivingEntity target) {
            over = true;
            TextDisplay slip = paper;
            if (slip != null && slip.isValid()) {
                slip.remove();
            }
            Location head = target.getLocation().add(0, target.getHeight() * 0.85, 0);
            Vfx.burst(ctx(), head, TALISMAN, 0.6, Particle.WAX_ON, false);
            Vfx.ring(ctx(), head, 0.5, 10, 0, Particle.DUST_COLOR_TRANSITION, TALISMAN_GLOW, false);
            Fx.sound(head, Sound.ENTITY_EVOKER_FANGS_ATTACK, 0.6f, 1.6f);
            Fx.sound(head, Sound.BLOCK_BAMBOO_WOOD_HIT, 1.0f, 1.4f);
            Combat.hit(ctx(), owner, target, Math.max(0, params().num("talisman_damage")));
            if (Combat.isTargetable(ctx(), owner, target)) {
                seal(owner, target, params().ticks("talisman_seconds"));
            }
        }

        /** The slip flutters down and burns away; part of the cooldown comes back. */
        private void miss() {
            if (over) {
                return;
            }
            over = true;
            TextDisplay slip = paper;
            if (slip != null && slip.isValid()) {
                // The slower glide must reach the clients before the move, or they lerp the drop in one tick.
                slip.setTeleportDuration(MISS_DROP_TICKS);
                ctx().runLater(owner, 1, () -> Displays.moveTo(slip, slip.getLocation().add(0, -0.8, 0)));
                ctx().runLater(owner, MISS_DROP_TICKS - 1, () -> {
                    if (slip.isValid()) {
                        Vfx.cloud(ctx(), slip.getLocation(), Particle.SMALL_FLAME, 3, 0.1, 0.01, null, false);
                        Displays.shrinkAway(ctx(), owner, slip, 4);
                    }
                });
            }
            double refund = Math.clamp(params().num("miss_refund"), 0, 100) / 100.0;
            if (refund > 0) {
                double left = ctx().cooldownRemaining(owner);
                if (left > 0) {
                    ctx().reduceCooldown(owner, left * refund);
                }
            }
        }
    }

    // ================================================================== ultimate: hold your breath

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        new Breath(owner).start();
        return ActivationResult.success();
    }

    /** One enemy's breath: casts it made (polled), how often it was caught. */
    private static final class Lungs {
        double lastCooldown;
        double lastGauge;
        long lastCast = Long.MIN_VALUE / 2;
        int caught;
        boolean baselined;
    }

    /** "숨 참아!": a great inhale every interval; whoever moves or fights at that moment is found out. */
    private final class Breath implements LongPredicate {

        private final Player owner;
        private final long duration;
        private final long interval;
        private final long warning;
        private final double radius;
        private final Map<UUID, Lungs> lungs = new HashMap<>();

        Breath(Player owner) {
            this.owner = owner;
            this.duration = Math.max(1, params().ticks("breath_duration"));
            this.interval = Math.max(2, params().ticks("breath_interval"));
            this.warning = Math.clamp(params().ticks("breath_warning"), 1, interval - 1);
            this.radius = Math.max(1, params().num("breath_radius"));
        }

        void start() {
            Location chest = owner.getLocation().add(0, 1.2, 0);
            Fx.sound(chest, Sound.ENTITY_ZOMBIE_VILLAGER_AMBIENT, 1.0f, 0.6f);
            Fx.sound(chest, Sound.ENTITY_HUSK_AMBIENT, 1.0f, 0.5f);
            Fx.sound(chest, Sound.BLOCK_BELL_RESONATE, 0.5f, 0.7f);
            Vfx.shockwave(ctx(), owner, owner.getLocation().add(0, 0.1, 0), 0.5, radius, 8,
                    Particle.DUST_COLOR_TRANSITION, CORPSE_FADE, true);
            ctx().announce(Text.mm("<b><" + YELLOW_TAG + ">숨 참아!</" + YELLOW_TAG + "></b> <white>강시가 숨결을 "
                    + "쫓습니다 — 들이켤 때 움직이지 마세요</white> <gray>(" + Fx.seconds(duration) + "초)</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, duration + 12, this);
        }

        @Override
        public boolean test(long run) {
            long now = ctx().currentTick();
            for (LivingEntity entity : ctx().enemiesNear(owner, owner.getLocation(), radius + 4)) {
                if (entity instanceof Player enemy) {
                    poll(enemy, now);
                }
            }
            long tick = run + 1;
            if (tick <= duration && tick % interval == interval - warning) {
                warn();
            }
            if (tick <= duration && tick % interval == 0) {
                inhale();
            }
            if (run % 4 == 0 && tick <= duration) {
                Location feet = owner.getLocation().add(0, 0.1, 0);
                Vfx.ring(ctx(), feet, radius, (int) Math.round(radius * 3.5), run * 0.03, Particle.DUST, GREEN_DUST,
                        false);
                Vfx.dot(owner.getEyeLocation().add(Fx.jitter(0.2f), -0.2, Fx.jitter(0.2f)), Particle.DUST, NAVY_DUST,
                        false);
            }
            return tick <= duration + 10;
        }

        /** Casts are found by their traces: a cooldown that started, a gauge that emptied. */
        private void poll(Player enemy, long now) {
            Lungs lung = lungs.computeIfAbsent(enemy.getUniqueId(), id -> new Lungs());
            double cooldown = ctx().cooldownRemaining(enemy);
            double gauge = ctx().gauge(enemy);
            if (lung.baselined && ((lung.lastCooldown <= READY && cooldown > lung.lastCooldown + COOLDOWN_SET)
                    || lung.lastGauge - gauge >= GAUGE_DROP)) {
                lung.lastCast = now;
            }
            lung.baselined = true;
            lung.lastCooldown = cooldown;
            lung.lastGauge = gauge;
        }

        /** "흐읍—": the warning on every enemy near; the judging moment is when it runs out. */
        private void warn() {
            Location mouth = owner.getEyeLocation();
            Fx.sound(mouth, Sound.ENTITY_BREEZE_INHALE, 1.4f, 0.7f);
            Fx.sound(mouth, Sound.ENTITY_PLAYER_BREATH, 1.0f, 0.6f);
            Vfx.cloud(ctx(), mouth, Particle.DUST_COLOR_TRANSITION, 8, 0.4, 0, CORPSE_FADE, false);
            for (LivingEntity entity : ctx().enemiesNear(owner, owner.getLocation(), radius)) {
                if (!(entity instanceof Player enemy) || ctx().isConcealedFrom(enemy, owner)) {
                    continue;
                }
                Lungs lung = lungs.computeIfAbsent(enemy.getUniqueId(), id -> new Lungs());
                if (lung.caught >= Math.max(0, params().integer("caught_max"))) {
                    continue;
                }
                Prompt prompt = Prompt.builder(Text.mm("<b><" + YELLOW_TAG + ">흐읍— 숨 참아!</" + YELLOW_TAG + "></b> "
                                + "<white>들이켜는 순간 움직이거나(이동·점프) 공격하면 들킵니다</white> <gray>— 가만히 "
                                + "멈추세요</gray>"), warning)
                        .priority(Prompt.HIGH)
                        .overhead(Text.mm("<b><" + YELLOW_TAG + ">흐읍—!</" + YELLOW_TAG + "></b>"))
                        .maxWait(0)
                        .onEnd((handle, reason) -> {
                            if (reason == PromptEndReason.EXPIRED) {
                                judge(enemy, lung);
                            }
                        })
                        .build();
                ctx().prompt(owner, enemy, prompt);
            }
        }

        /** The inhale itself: breath streams in from all around. */
        private void inhale() {
            Location mouth = owner.getEyeLocation().add(Motion.horizontalFacing(owner.getLocation()).multiply(0.3));
            Fx.sound(mouth, Sound.ENTITY_BREEZE_INHALE, 1.6f, 0.5f);
            Fx.sound(mouth, Sound.ENTITY_ZOMBIE_AMBIENT, 0.8f, 0.6f);
            for (int i = 0; i < Vfx.n(ctx(), 14); i++) {
                Location from = Vfx.scatter(owner.getLocation(), radius).add(0, 1.0 + Math.random(), 0);
                Vfx.trail(from, mouth, BREATH, 10, false);
            }
            Vfx.ring(ctx(), owner.getLocation().add(0, 1.0, 0), 1.2, 16, 0, Particle.DUST_COLOR_TRANSITION,
                    CORPSE_FADE, true);
        }

        /** Moving keys, a swing or a cast at the moment of the inhale give the breath away. */
        private void judge(Player enemy, Lungs lung) {
            if (!ctx().isAlive(owner) || !Combat.isTargetable(ctx(), owner, enemy)
                    || lung.caught >= Math.max(0, params().integer("caught_max"))) {
                return;
            }
            long now = ctx().currentTick();
            boolean moved = Inputs.breaksWalking(ctx(), enemy, now, owner)
                    || Inputs.breaks(ctx(), enemy, InputKey.JUMP, now, owner);
            boolean fought = ctx().lastAttackTick(enemy) >= now - ATTACK_SLACK
                    || ctx().lastSwingTick(enemy) >= now - ATTACK_SLACK || lung.lastCast >= now - ATTACK_SLACK;
            if (!moved && !fought) {
                Location head = enemy.getLocation().add(0, enemy.getHeight() + 0.3, 0);
                Vfx.cloud(ctx(), head, Particle.WHITE_ASH, 3, 0.2, 0, null, false);
                return;
            }
            lung.caught++;
            Location mouth = enemy.getEyeLocation();
            for (int i = 0; i < Vfx.n(ctx(), 5); i++) {
                Vfx.trail(mouth.clone().add(Fx.jitter(0.2f), Fx.jitter(0.2f), Fx.jitter(0.2f)), owner.getEyeLocation(),
                        BREATH, 10, true);
            }
            Vfx.cloud(ctx(), mouth, Particle.CLOUD, 5, 0.15, 0.02, null, false);
            Fx.sound(mouth, Sound.ENTITY_ZOMBIE_INFECT, 1.0f, 0.8f);
            Fx.sound(mouth, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 0.6f, 0.6f);
            pop(owner, enemy, "<b><" + YELLOW_TAG + ">들켰다!</" + YELLOW_TAG + "></b>", 1.2f);
            Combat.hit(ctx(), owner, enemy, Math.max(0, params().num("caught_damage")));
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                seal(owner, enemy, params().ticks("caught_talisman"));
            }
        }
    }

    // ================================================================== helpers

    /** A word popping up over {@code target}'s head (hidden from those who cannot see it). */
    private void pop(Player owner, LivingEntity target, String mini, float scale) {
        if (!reserve(1)) {
            return;
        }
        Location at = target.getLocation().add(0, target.getHeight() + 2.0, 0);
        TextDisplay text = keep(Displays.spawn(ctx(), owner, at, TextDisplay.class, 24,
                viewer -> !ctx().isConcealedFrom(target, viewer), d -> {
                    d.text(Text.mm(mini));
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    d.setShadowed(true);
                    d.setTransformation(Displays.centered(0, 0, 0, 0.3f, 0.3f, 0.3f, 0, 0, 0));
                }));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0, 0, 0), 4);
        ctx().runLater(owner, 16, () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 5);
            }
        });
    }

    /** Where a display riding {@code body} sits before its transformation: the top of the body's box. */
    private static Location seat(LivingEntity body) {
        return body.getLocation().add(0, body.getHeight(), 0);
    }

    /**
     * Seats {@code part} on {@code body} as a passenger (again, after anything dismounted it: a death, a respawn). A
     * mounted part is never teleported (that would dismount it); its transformation holds its offset from the seat.
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

    /** The horizontal unit vector a Minecraft yaw (degrees) faces. */
    private static Vector facing(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0, Math.cos(radians));
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

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
