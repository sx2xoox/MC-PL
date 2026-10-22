package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 구미호 — the nine-tailed fox. Every hit it lands grows another tail (up to nine), and each tail makes it a little
 * faster; tails left unused fall away again. The fox fire turns all of its tails into homing fox fires split among the
 * enemies around, and the ultimate sends out the fox bead: it latches onto an enemy like a kiss, drinks its life and
 * its ultimate gauge, then flies back and pours everything into the fox, growing new tails.
 *
 * <p>The ultimate needs no passive state (the tails it grows are skipped on a borrowed instance), so the Gambler can
 * borrow it.
 *
 * <p>Look: white fox fire, pink and violet. The tails are a fan of white tails on the lower back that wag and spread
 * wider the more there are, their tips glinting pink; a fallen tail drops and fades. Fox fires are pale glass flames
 * that leave the tail tips, swirl up and then dive at their targets trailing white-to-violet dust and witch sparks,
 * bursting in pink. The fox bead is a glowing pearlescent core in a pink glass shell; latched onto a victim it hovers
 * at its lips while threads of life stream into it and it swells, then it flies home on a violet ribbon and bursts
 * into the fox in a ring of petals.
 */
public final class GumihoAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("gumiho", "구미호", Material.SWEET_BERRIES)
            .color(TextColor.color(0xFF8FD0))
            .difficulty(2)
            .passive("아홉 꼬리",
                    "근접 공격이나 화살로 적을 맞힐 때마다 꼬리가 하나 자랍니다(최대 {tail_max}개). 꼬리 하나마다 이동 "
                            + "속도가 {tail_speed}% 빨라지고, {tail_keep}초 동안 적을 맞히지 못하면 꼬리가 "
                            + "{tail_shed}초마다 하나씩 빠집니다.")
            .active("여우불",
                    "꼬리를 모두 거둬 그 수만큼(최소 {fire_min}개) 여우불을 날립니다. 여우불은 {fire_range}블록 안의 "
                            + "적들을 나눠 쫓아가 하나에 {fire_damage} 피해를 줍니다.",
                    8)
            .ultimate("여우구슬",
                    "조준한 적(없으면 {bead_range}블록 안의 가장 가까운 적)에게 여우구슬을 날립니다. 구슬은 {bead_damage} "
                            + "피해를 주고 달라붙어 {bead_drain_time}초간 {bead_drain} 피해만큼 기운을 빨아들이며(그동안 "
                            + "적 이동 속도 -{bead_slow}%), 궁극기 게이지를 최대 {bead_gauge}% 빼앗습니다. 돌아온 구슬은 "
                            + "빨아들인 체력과 게이지를 나에게 주고 꼬리 {bead_tails}개를 자라게 합니다.")
            .param("tail_max", 9)
            .param("tail_speed", 2)
            .param("tail_keep", 8)
            .param("tail_shed", 1)
            .param("fire_min", 2)
            .param("fire_range", 16)
            .param("fire_damage", 1.2)
            .param("bead_range", 20)
            .param("bead_damage", 2)
            .param("bead_drain_time", 2)
            .param("bead_drain", 6)
            .param("bead_slow", 35)
            .param("bead_gauge", 20)
            .param("bead_tails", 3)
            .build();

    private static final String PINK = "#FF8FD0";
    private static final String VIOLET = "#B583FF";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: white fox fire, pink, violet
    /** White fox fire core, fox pink, deep violet. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xFF8FD0, 0x7B3FD1);
    private static final Particle.DustTransition FIRE_GLOW = PALETTE.glow(0.9f);
    private static final Particle.DustTransition FIRE_SMALL = PALETTE.glow(0.55f);
    private static final Particle.DustTransition FIRE_FADE = PALETTE.fade(1.0f);
    private static final Particle.DustOptions PINK_DUST = PALETTE.dust(0.7f);
    private static final Particle.DustOptions VIOLET_DUST = new Particle.DustOptions(Color.fromRGB(0xB583FF), 0.8f);
    private static final Particle.DustOptions WHITE_DUST = new Particle.DustOptions(Color.fromRGB(0xFFFFFF), 0.6f);

    /** Block types of the visuals; block data is created on use (it needs a running server). */
    private static final Material TAIL_BLOCK = Material.WHITE_WOOL;
    private static final Material FIRE_BLOCK = Material.WHITE_STAINED_GLASS;
    private static final Material BEAD_SHELL = Material.PINK_STAINED_GLASS;
    private static final Material BEAD_CORE = Material.PEARLESCENT_FROGLIGHT;

    /** Display entities one gumiho keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    /** "/ 2": now - this must not overflow. */
    private static final long NEVER = Long.MIN_VALUE / 2;
    private static final int HARD_TAIL_CAP = 9;

    // passive: the tails
    /** Where the tails grow from, in body space (local +Z = facing, +X = the owner's left). */
    private static final Vector3f TAIL_BASE = new Vector3f(0f, 0.9f, -0.2f);
    private static final float TAIL_LENGTH = 0.95f;
    private static final float TAIL_WIDTH = 0.17f;
    /** Widest fan (degrees) of a full set of tails, and the gap between two neighbours. */
    private static final double TAIL_FAN_MAX = 150;
    private static final double TAIL_FAN_STEP = 19;
    private static final double TAIL_WAG = Math.toRadians(7);
    private static final int TAIL_GROW_TICKS = 4;
    private static final int TAIL_FALL_TICKS = 8;
    private static final long TAIL_POSE_PERIOD = 2;

    // active: fox fire
    private static final float FIRE_SCALE = 0.3f;
    private static final long FIRE_STAGGER = 2;
    private static final double FIRE_START_SPEED = 0.45;
    private static final double FIRE_TOP_SPEED = 0.95;
    private static final double FIRE_ACCEL = 0.06;
    private static final double FIRE_TURN = Math.toRadians(14);
    private static final double FIRE_CLOSE_TURN = Math.toRadians(40);
    /** The longer a fire flies, the harder it turns, so it never circles its target forever. */
    private static final double FIRE_TURN_GROWTH = Math.toRadians(1.5);
    /** Close to its target a fire slows down to this plus a share of the distance, so it can make the turn. */
    private static final double FIRE_CLOSE_SPEED = 0.35;
    private static final long FIRE_MAX_TICKS = 60;
    private static final double FIRE_RETARGET = 8;
    private static final double FIRE_HITBOX = 0.4;

    // ultimate: fox bead
    private static final float BEAD_SHELL_SCALE = 0.42f;
    private static final float BEAD_CORE_SCALE = 0.22f;
    private static final double BEAD_SPEED = 0.85;
    private static final double BEAD_TURN = Math.toRadians(20);
    private static final long BEAD_OUT_MAX = 40;
    private static final double BEAD_RETURN_SPEED = 1.1;
    private static final long BEAD_RETURN_MAX = 60;
    private static final long BEAD_PULSE = 5;
    private static final double BEAD_HITBOX = 0.45;

    private int tails;
    private boolean granted;
    private long lastHit = NEVER;
    private long lastShed = NEVER;
    private @Nullable NamespacedKey speedKey;
    private final List<@Nullable BlockDisplay> tailDisplays = new ArrayList<>();
    private final List<Display> displays = new ArrayList<>();
    private float posedYaw = Float.NaN;
    /** The fan is not re-posed before this tick (a new tail needs a tick between spawning and growing). */
    private long poseFrom = NEVER;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        granted = true;
    }

    @Override
    public void onRevoke() {
        // The context removes the displays and the speed modifier; only forget them here.
        granted = false;
        tails = 0;
        tailDisplays.clear();
        speedKey = null;
    }

    private int tailMax() {
        return Math.clamp(params().integer("tail_max"), 0, HARD_TAIL_CAP);
    }

    // ------------------------------------------------------------------ passive: nine tails

    /** A landed hit of the fox's own (a melee swing or an arrow it shot) grows a tail. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!granted || ctx().isAbilityDamage() || !ctx().isEnemy(player(), victim)) {
            return;
        }
        boolean melee = player().equals(event.getDamager()) && event.getCause() == DamageCause.ENTITY_ATTACK;
        boolean arrow = event.getDamager() instanceof Projectile projectile
                && player().equals(projectile.getShooter());
        if (!melee && !arrow) {
            return;
        }
        lastHit = ctx().currentTick();
        growTails(1, Fx.body(victim));
    }

    /** Grows up to {@code count} tails; {@code from} is where the stolen spirit came from (for the wisp). */
    private void growTails(int count, @Nullable Location from) {
        int max = tailMax();
        int before = tails;
        tails = Math.min(max, tails + Math.max(0, count));
        if (tails == before) {
            return;
        }
        Player owner = player();
        float scale = (float) Body.scaleOf(owner);
        float yaw = owner.getBodyYaw();
        for (int i = before; i < tails; i++) {
            if (canSpawn(1)) {
                Location feet = Displays.unrotated(owner.getLocation());
                BlockDisplay tail = Displays.block(ctx(), owner, feet, TAIL_BLOCK.createBlockData(),
                        tailPose(tailDirection(i, tails, 0), 0.05f, yaw, scale), 0);
                tail.setTeleportDuration(1);
                remember(tail);
                tailDisplays.add(tail);
            } else {
                tailDisplays.add(null);
            }
        }
        posedYaw = Float.NaN; // re-pose the whole fan (it spreads wider)
        poseFrom = ctx().currentTick() + 1;
        // Fresh tails are kept for the full time, however they grew.
        lastHit = ctx().currentTick();
        lastShed = ctx().currentTick();
        updateSpeed();
        Location tip = tipPoint(owner, tails - 1, tails, 0);
        if (from != null && from.getWorld().equals(tip.getWorld())) {
            Vfx.trail(from, tip, PALETTE.body(), 8, false);
        }
        Vfx.cloud(ctx(), tip, Particle.DUST_COLOR_TRANSITION, 6, 0.12, 0, FIRE_GLOW, false);
        Vfx.dot(tip, Particle.END_ROD, null, false);
        Fx.sound(tip, Sound.ENTITY_FOX_AMBIENT, 0.35f, 1.4f + tails * 0.05f + Fx.jitter(0.05f));
        Fx.sound(tip, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 0.9f + tails * 0.1f);
        if (tails == max && before < max) {
            fullFan(owner);
        }
        owner.sendActionBar(tailText());
    }

    /** All nine tails: a pink ring flares around the fox. */
    private void fullFan(Player owner) {
        Location feet = owner.getLocation().add(0, 0.1, 0);
        Vfx.shockwave(ctx(), owner, feet, 0.4, 2.2, 6, Particle.DUST_COLOR_TRANSITION, FIRE_GLOW, false);
        Vfx.helix(ctx(), feet, 0.7, owner.getHeight() * 1.1, 1.5, 3, 10, 0, Particle.DUST, PINK_DUST, false);
        Fx.sound(feet, Sound.ENTITY_FOX_SCREECH, 0.6f, 1.3f);
        Fx.sound(feet, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.8f);
    }

    private net.kyori.adventure.text.Component tailText() {
        StringBuilder fan = new StringBuilder();
        int max = tailMax();
        for (int i = 0; i < max; i++) {
            fan.append(i < tails ? "<" + PINK + ">✦</" + PINK + ">" : "<dark_gray>✧</dark_gray>");
        }
        return Text.mm("<" + PINK + ">아홉 꼬리</" + PINK + "> " + fan + " <gray>이동 속도 +"
                + AbilityInfo.formatNumber(tails * Math.max(0, params().num("tail_speed"))) + "%</gray>");
    }

    /** Drops the newest {@code count} tails (they fall off and fade). */
    private void loseTails(int count, boolean fall) {
        Player owner = player();
        int lose = Math.min(count, tails);
        for (int i = 0; i < lose; i++) {
            tails--;
            BlockDisplay tail = tailDisplays.remove(tailDisplays.size() - 1);
            if (tail == null || !tail.isValid()) {
                continue;
            }
            if (fall) {
                Transformation current = tail.getTransformation();
                Transformation dropped = new Transformation(new Vector3f(current.getTranslation()).add(0, -0.7f, 0),
                        current.getLeftRotation(), new Vector3f(current.getScale()).mul(0.7f), new Quaternionf());
                Displays.animate(tail, dropped, TAIL_FALL_TICKS);
                ctx().runLater(owner, TAIL_FALL_TICKS, () -> {
                    if (tail.isValid()) {
                        Displays.shrinkAway(ctx(), owner, tail, 4);
                    }
                });
            } else {
                Displays.shrinkAway(ctx(), owner, tail, 3);
            }
        }
        if (lose > 0) {
            posedYaw = Float.NaN;
            updateSpeed();
        }
    }

    /** The speed bonus follows the tail count. */
    private void updateSpeed() {
        Player owner = player();
        NamespacedKey previous = speedKey;
        if (previous != null) {
            ctx().removeModifier(owner, previous);
            speedKey = null;
        }
        double bonus = tails * Math.max(0, params().num("tail_speed")) / 100.0;
        if (bonus > 0 && granted) {
            speedKey = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, bonus, Operation.MULTIPLY_SCALAR_1,
                    0);
        }
    }

    @Override
    public void onTick(long tick) {
        if (!granted || tails <= 0) {
            return;
        }
        Player owner = player();
        long now = ctx().currentTick();
        long keep = params().ticks("tail_keep");
        long shed = Math.max(1, params().ticks("tail_shed"));
        if (now - lastHit > keep && now - lastShed >= shed) {
            lastShed = now;
            Location tip = tipPoint(owner, tails - 1, tails, 0);
            loseTails(1, true);
            Vfx.cloud(ctx(), tip, Particle.WHITE_ASH, 4, 0.15, 0.01, null, false);
            Fx.sound(tip, Sound.BLOCK_WOOL_BREAK, 0.4f, 1.3f + Fx.jitter(0.1f));
            if (tails == 0) {
                owner.sendActionBar(Text.mm("<gray>꼬리가 모두 빠졌습니다</gray>"));
            }
            if (tails <= 0) {
                return;
            }
        }
        followTails(owner, tick);
        if (tick % 6 == 0) {
            // A glint on one tail tip, and white motes drifting off the fan.
            int index = ThreadLocalRandom.current().nextInt(tails);
            Location tip = tipPoint(owner, index, tails, wag(tick, index));
            Vfx.dot(tip, Particle.DUST, index % 2 == 0 ? PINK_DUST : VIOLET_DUST, false);
            if (tick % 18 == 0) {
                Vfx.dot(tip.add(0, 0.1, 0), Particle.END_ROD, null, false);
            }
        }
    }

    /** Keeps the fan on the fox's back: moved every tick, re-posed (wagging) every other tick or when it turned. */
    private void followTails(Player owner, long tick) {
        Location feet = Displays.unrotated(owner.getLocation());
        float yaw = owner.getBodyYaw();
        // A freshly grown tail is posed a tick after it spawned, so the client interpolates its growth.
        boolean settled = ctx().currentTick() >= poseFrom;
        boolean repose = settled && (tick % TAIL_POSE_PERIOD == 0 || Float.isNaN(posedYaw)
                || Math.abs(yaw - posedYaw) > 2f);
        float scale = (float) Body.scaleOf(owner);
        for (int i = 0; i < tailDisplays.size(); i++) {
            BlockDisplay tail = tailDisplays.get(i);
            if (tail == null || !tail.isValid()) {
                continue;
            }
            if (!tail.getWorld().equals(owner.getWorld())) {
                continue;
            }
            Displays.moveTo(tail, feet);
            if (repose) {
                Displays.animate(tail, tailPose(tailDirection(i, tails, wag(tick, i)), TAIL_LENGTH, yaw, scale),
                        Float.isNaN(posedYaw) ? TAIL_GROW_TICKS : (int) TAIL_POSE_PERIOD);
            }
        }
        if (repose) {
            posedYaw = yaw;
        }
    }

    private static double wag(long tick, int index) {
        return Math.sin(tick * 0.18 + index * 0.7) * TAIL_WAG;
    }

    /**
     * The direction of tail {@code index} of {@code count} in body space: fanned out sideways behind the fox, all
     * pointing up and back, the fan wider the more tails there are.
     */
    static Vector3f tailDirection(int index, int count, double wag) {
        double spread = count <= 1 ? 0 : Math.toRadians(Math.min(TAIL_FAN_MAX, TAIL_FAN_STEP * (count - 1)));
        double angle = (count <= 1 ? 0 : -spread / 2 + spread * index / (count - 1)) + wag;
        return new Vector3f((float) (Math.sin(angle) * 0.95), (float) (Math.cos(angle) * 0.85 + 0.25), -0.6f)
                .normalize();
    }

    /**
     * A tail growing from the lower back along {@code direction} (body space), turned to the body's {@code yaw}: the
     * box's own +Y runs along the tail, its base centered on the root. The display stays unrotated; everything lives
     * in the transformation.
     */
    static Transformation tailPose(Vector3f direction, float length, float yaw, float scale) {
        Quaternionf facing = yawRotation(yaw);
        Quaternionf rotation = new Quaternionf(facing).mul(new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                direction));
        Vector3f root = facing.transform(new Vector3f(TAIL_BASE).mul(scale));
        Vector3f size = new Vector3f(TAIL_WIDTH, Math.max(0.01f, length), TAIL_WIDTH).mul(scale);
        Vector3f translation = root.sub(rotation.transform(new Vector3f(size.x() * 0.5f, 0, size.z() * 0.5f)));
        return new Transformation(translation, rotation, size, new Quaternionf());
    }

    /** The world position of tail {@code index}'s tip. */
    private static Location tipPoint(Player owner, int index, int count, double wag) {
        float scale = (float) Body.scaleOf(owner);
        Quaternionf facing = yawRotation(owner.getBodyYaw());
        Vector3f local = new Vector3f(tailDirection(Math.max(0, index), Math.max(1, count), wag))
                .mul(TAIL_LENGTH).add(TAIL_BASE).mul(scale);
        facing.transform(local);
        return Displays.unrotated(owner.getLocation().add(local.x(), local.y(), local.z()));
    }

    private static Quaternionf yawRotation(float yaw) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw));
    }

    // ------------------------------------------------------------------ active: fox fire

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("fire_range"));
        List<LivingEntity> targets = targets(owner, range);
        if (targets.isEmpty()) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안에 보이는 적이 없습니다");
        }
        int spent = tails;
        int count = Math.max(Math.max(1, params().integer("fire_min")), spent);
        List<Location> launches = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            launches.add(i < spent ? tipPoint(owner, i, spent, 0)
                    : owner.getLocation().add(0, owner.getHeight() * 0.75, 0)
                    .add(Vfx.scatter(new Location(owner.getWorld(), 0, 0, 0), 0.5).toVector()));
        }
        if (spent > 0) {
            loseTails(spent, false);
            lastShed = ctx().currentTick();
        }
        Volley volley = new Volley(count);
        for (int i = 0; i < count; i++) {
            LivingEntity target = targets.get(i % targets.size());
            Location from = launches.get(i);
            int index = i;
            ctx().runLater(owner, 1 + i * FIRE_STAGGER, () -> launch(owner, from, target, index, volley));
        }
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        Vfx.ring(ctx(), chest, 0.9, 16, 0, Particle.DUST_COLOR_TRANSITION, FIRE_GLOW, false);
        Vfx.cloud(ctx(), chest, Particle.WITCH, 8, 0.4, 0.02, null, false);
        Fx.sound(chest, Sound.ENTITY_FOX_SCREECH, 0.7f, 1.4f + Fx.jitter(0.05f));
        Fx.sound(chest, Sound.ENTITY_EVOKER_CAST_SPELL, 0.6f, 1.6f + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<" + PINK + "><b>여우불</b></" + PINK + "> <white>" + count + "발</white>"
                + (spent > 0 ? " <gray>꼬리 " + spent + "개</gray>" : "")));
        return ActivationResult.success();
    }

    /** Visible enemies in range with a clear line, the aimed one first, then the nearest. */
    private List<LivingEntity> targets(Player owner, double range) {
        List<LivingEntity> targets = new ArrayList<>();
        Location eye = owner.getEyeLocation();
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed != null && isVisible(aimed)) {
            targets.add(aimed);
        }
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            if (!targets.contains(enemy) && isVisible(enemy) && Spots.clearPath(eye, Fx.body(enemy))) {
                targets.add(enemy);
            }
        }
        return targets;
    }

    /** Players hidden from the owner (stealth) are never targeted, so the fires do not give them away. */
    private boolean isVisible(LivingEntity target) {
        return !(target instanceof Player hidden) || player().canSee(hidden);
    }

    /** Counts the fires of one cast that hit, for the summary. */
    private static final class Volley {
        private final int count;
        private int done;
        private int hits;

        Volley(int count) {
            this.count = count;
        }
    }

    private void launch(Player owner, Location from, LivingEntity target, int index, Volley volley) {
        if (!from.getWorld().equals(owner.getWorld())) {
            return;
        }
        // Each fire first swirls up and out from its tail, then dives.
        Vector out = from.toVector().subtract(owner.getLocation().add(0, 1, 0).toVector()).setY(0);
        if (out.lengthSquared() < 1.0E-4) {
            double angle = index * 2.4;
            out = new Vector(Math.cos(angle), 0, Math.sin(angle));
        }
        Vector heading = out.normalize().multiply(0.6).add(new Vector(0, 0.8, 0)).normalize();
        FoxFire fire = new FoxFire(from, heading, target, volley);
        Loop.repeat(ctx(), owner, 1, 1, FIRE_MAX_TICKS, fire, fire::fizzle);
        Vfx.dot(from, Particle.FLASH, PALETTE.core(), false);
        Vfx.cloud(ctx(), from, Particle.DUST_COLOR_TRANSITION, 4, 0.1, 0, FIRE_GLOW, false);
        Fx.sound(from, Sound.ENTITY_BLAZE_SHOOT, 0.35f, 1.7f + index * 0.04f + Fx.jitter(0.05f));
        Fx.sound(from, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 1.4f + index * 0.08f);
    }

    /** One fox fire: a pale glass flame that homes in on its target, turning harder as it closes in. */
    private final class FoxFire implements LongPredicate {
        private final World world;
        private final Location position;
        private final Vector direction;
        private final Volley volley;
        private final @Nullable BlockDisplay visual;
        private LivingEntity target;
        private boolean done;
        private float spin;

        FoxFire(Location start, Vector heading, LivingEntity target, Volley volley) {
            this.world = start.getWorld();
            this.position = start.clone();
            this.direction = heading.clone();
            this.target = target;
            this.volley = volley;
            if (canSpawn(1)) {
                BlockDisplay display = Displays.block(ctx(), player(), Displays.unrotated(start),
                        FIRE_BLOCK.createBlockData(), Displays.scaled(0.05f), FIRE_MAX_TICKS + 6);
                display.setTeleportDuration(1);
                remember(display);
                visual = display;
            } else {
                visual = null;
            }
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            Player owner = player();
            if (!Combat.isTargetable(ctx(), owner, target) || !isVisible(target)
                    || !target.getWorld().equals(world)) {
                LivingEntity next = retarget(owner);
                if (next == null) {
                    fizzle();
                    return false;
                }
                target = next;
            }
            Location aim = Fx.body(target);
            Vector desired = aim.toVector().subtract(position.toVector());
            double distance = desired.length();
            double speed = Math.min(Math.min(FIRE_TOP_SPEED, FIRE_START_SPEED + tick * FIRE_ACCEL),
                    FIRE_CLOSE_SPEED + distance * 0.35);
            if (distance <= target.getWidth() * 0.5 + FIRE_HITBOX + speed) {
                position.set(aim.getX(), aim.getY(), aim.getZ());
                strike(owner);
                return false;
            }
            if (tick >= 3 && distance > 1.0E-3) {
                double turn = FIRE_TURN + tick * FIRE_TURN_GROWTH;
                if (distance < 3) {
                    turn = Math.max(turn, FIRE_CLOSE_TURN);
                }
                desired.multiply(1 / distance);
                double angle = direction.angle(desired);
                if (!Double.isFinite(angle) || angle <= turn) {
                    direction.copy(desired);
                } else {
                    double t = turn / angle;
                    Vector turned = direction.clone().multiply(1 - t).add(desired.multiply(t));
                    if (turned.lengthSquared() > 1.0E-6) {
                        direction.copy(turned.normalize());
                    }
                }
            }
            RayTraceResult wall = world.rayTraceBlocks(position, direction, speed, FluidCollisionMode.NEVER, true);
            if (wall != null) {
                position.set(wall.getHitPosition().getX(), wall.getHitPosition().getY(),
                        wall.getHitPosition().getZ());
                fizzle();
                return false;
            }
            Location from = position.clone();
            position.add(direction.clone().multiply(speed));
            fly(from, tick);
            return true;
        }

        /** The target is gone: the nearest other visible enemy close to the fire, if any. */
        private @Nullable LivingEntity retarget(Player owner) {
            for (LivingEntity enemy : ctx().enemiesNear(owner, position, FIRE_RETARGET)) {
                if (isVisible(enemy) && enemy.getWorld().equals(world) && Spots.clearPath(position, Fx.body(enemy))) {
                    return enemy;
                }
            }
            return null;
        }

        private void fly(Location from, long tick) {
            BlockDisplay display = visual;
            if (display != null && display.isValid()) {
                Displays.moveTo(display, position);
                spin += 37f;
                float size = tick == 0 ? FIRE_SCALE * 0.6f : FIRE_SCALE;
                Displays.animate(display, Displays.transform(0, 0, 0, size, size, size, spin, spin * 0.6f, 45f), 1);
            }
            Vfx.line(ctx(), from, position, 3, Particle.DUST_COLOR_TRANSITION, FIRE_SMALL, false);
            if (tick % 2 == 0) {
                Vfx.dot(position, Particle.WITCH, null, false);
                Vfx.trail(position, from.clone().add(Fx.jitter(0.2f), 0.2 + Fx.jitter(0.1f), Fx.jitter(0.2f)),
                        PALETTE.edge(), 7, false);
            } else {
                Vfx.dot(position, Particle.END_ROD, null, false);
            }
        }

        private void strike(Player owner) {
            done = true;
            removeVisual();
            Combat.hit(ctx(), owner, target, params().num("fire_damage"));
            Location body = position.clone();
            Vfx.burst(ctx(), body, PALETTE, 0.5, Particle.WITCH, false);
            Vfx.cloud(ctx(), body, Particle.DUST, 4, 0.2, 0, WHITE_DUST, false);
            Fx.sound(body, Sound.ENTITY_BLAZE_SHOOT, 0.3f, 2.0f);
            Fx.sound(body, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.5f, 1.5f + Fx.jitter(0.15f));
            volley.hits++;
            finish(owner);
        }

        void fizzle() {
            if (done) {
                return;
            }
            done = true;
            removeVisual();
            Vfx.cloud(ctx(), position, Particle.DUST_COLOR_TRANSITION, 4, 0.1, 0, FIRE_FADE, false);
            Vfx.dot(position, Particle.SMOKE, null, false);
            Fx.sound(position, Sound.BLOCK_FIRE_EXTINGUISH, 0.2f, 1.9f);
            finish(player());
        }

        private void finish(Player owner) {
            volley.done++;
            if (volley.done == volley.count && volley.hits > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + PINK + ">여우불</" + PINK + "> <white>" + volley.hits + "/"
                        + volley.count + " 명중</white>"));
            }
        }

        private void removeVisual() {
            if (visual != null && visual.isValid()) {
                visual.remove();
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: fox bead

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double range = Math.max(1, params().num("bead_range"));
        LivingEntity target = ctx().findTarget(owner, range);
        if (target != null && !isVisible(target)) {
            target = null;
        }
        if (target == null) {
            Location eye = owner.getEyeLocation();
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
                if (isVisible(enemy) && Spots.clearPath(eye, Fx.body(enemy))) {
                    target = enemy;
                    break;
                }
            }
        }
        if (target == null) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안에 보이는 적이 없습니다");
        }
        new Bead(owner, target).start();
        return ActivationResult.success();
    }

    private enum BeadPhase { OUT, LATCHED, RETURN }

    /**
     * The fox bead: flies out to its victim, latches on for the drain (damage in pulses, a slow, the gauge drained
     * off), then flies back and pours what it drank into the fox.
     */
    private final class Bead implements LongPredicate {
        private final Player owner;
        private final LivingEntity victim;
        private final World world;
        private final Location position;
        private final Vector direction;
        private final long drainTicks;
        private final int pulses;
        private @Nullable BlockDisplay shell;
        private @Nullable BlockDisplay core;
        private BeadPhase phase = BeadPhase.OUT;
        private long phaseStart;
        private int pulsed;
        private double drank;
        private double stolen;
        private float spin;

        Bead(Player owner, LivingEntity victim) {
            this.owner = owner;
            this.victim = victim;
            Location eye = owner.getEyeLocation();
            this.world = eye.getWorld();
            this.position = eye.clone().add(eye.getDirection().multiply(0.6)).subtract(0, 0.2, 0);
            Vector to = Fx.body(victim).toVector().subtract(position.toVector());
            this.direction = to.lengthSquared() > 1.0E-6 ? to.normalize() : eye.getDirection();
            this.drainTicks = Math.max(1, params().ticks("bead_drain_time"));
            this.pulses = (int) Math.max(1, drainTicks / BEAD_PULSE);
        }

        void start() {
            long life = BEAD_OUT_MAX + drainTicks + BEAD_RETURN_MAX + 10;
            if (canSpawn(2)) {
                Location at = Displays.unrotated(position);
                BlockDisplay glass = Displays.block(ctx(), owner, at, BEAD_SHELL.createBlockData(),
                        Displays.scaled(0.05f), life);
                glass.setTeleportDuration(1);
                remember(glass);
                BlockDisplay light = Displays.block(ctx(), owner, at, BEAD_CORE.createBlockData(),
                        Displays.scaled(0.05f), life);
                light.setTeleportDuration(1);
                Displays.outline(light, PALETTE.body());
                remember(light);
                shell = glass;
                core = light;
            }
            Vfx.dot(position, Particle.FLASH, PALETTE.core(), true);
            Vfx.ring(ctx(), position, 0.5, 14, 0, Particle.DUST_COLOR_TRANSITION, FIRE_GLOW, true);
            Fx.sound(position, Sound.ENTITY_FOX_SNIFF, 0.9f, 1.3f);
            Fx.sound(position, Sound.ENTITY_ALLAY_ITEM_THROWN, 1.0f, 0.8f);
            Fx.sound(position, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f);
            owner.sendActionBar(Text.mm("<" + PINK + "><b>여우구슬</b></" + PINK + ">"));
            Loop.repeat(ctx(), owner, 1, 1, BEAD_OUT_MAX + drainTicks + BEAD_RETURN_MAX, this, this::absorb);
        }

        @Override
        public boolean test(long tick) {
            return switch (phase) {
                case OUT -> out(tick);
                case LATCHED -> latched(tick);
                case RETURN -> back(tick);
            };
        }

        private boolean out(long tick) {
            if (!Combat.isTargetable(ctx(), owner, victim) || !victim.getWorld().equals(world)
                    || tick > BEAD_OUT_MAX) {
                turnBack(tick);
                return true;
            }
            Location aim = Fx.body(victim);
            Vector desired = aim.toVector().subtract(position.toVector());
            double distance = desired.length();
            if (distance <= victim.getWidth() * 0.5 + BEAD_HITBOX + BEAD_SPEED) {
                latch(tick);
                return true;
            }
            desired.multiply(1 / distance);
            double angle = direction.angle(desired);
            if (!Double.isFinite(angle) || angle <= BEAD_TURN) {
                direction.copy(desired);
            } else {
                double t = BEAD_TURN / angle;
                Vector turned = direction.clone().multiply(1 - t).add(desired.multiply(t));
                if (turned.lengthSquared() > 1.0E-6) {
                    direction.copy(turned.normalize());
                }
            }
            RayTraceResult wall = world.rayTraceBlocks(position, direction, BEAD_SPEED, FluidCollisionMode.NEVER,
                    true);
            if (wall != null) {
                position.set(wall.getHitPosition().getX(), wall.getHitPosition().getY(),
                        wall.getHitPosition().getZ()).subtract(direction.clone().multiply(0.3));
                Vfx.cloud(ctx(), position, Particle.DUST_COLOR_TRANSITION, 6, 0.15, 0, FIRE_FADE, false);
                Fx.sound(position, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.6f, 0.8f);
                turnBack(tick);
                return true;
            }
            Location from = position.clone();
            position.add(direction.clone().multiply(BEAD_SPEED));
            pose(1f);
            Vfx.line(ctx(), from, position, 3, Particle.DUST_COLOR_TRANSITION, FIRE_GLOW, true);
            if (tick % 2 == 0) {
                Vfx.dot(position, Particle.WITCH, null, false);
                Vfx.dot(Vfx.scatter(position, 0.2), Particle.CHERRY_LEAVES, null, false);
            }
            return true;
        }

        /** The bead reaches its victim: a sting, then it clings to its lips and starts to drink. */
        private void latch(long tick) {
            phase = BeadPhase.LATCHED;
            phaseStart = tick;
            // The sting itself is not drunk; only the drain that follows flows back into the fox.
            Combat.hit(ctx(), owner, victim, params().num("bead_damage"));
            if (!Combat.isTargetable(ctx(), owner, victim)) {
                turnBack(tick);
                return;
            }
            double slow = Math.clamp(params().num("bead_slow") / 100.0, 0.0, 1.0);
            if (slow > 0) {
                ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                        drainTicks);
            }
            Location lips = lips();
            position.set(lips.getX(), lips.getY(), lips.getZ());
            pose(1f);
            Location body = Fx.body(victim);
            Vfx.burst(ctx(), body, PALETTE, 0.7, Particle.CHERRY_LEAVES, true);
            Vfx.dot(lips.clone().add(0, 0.3, 0), Particle.HEART, null, true);
            Fx.sound(body, Sound.ENTITY_FOX_BITE, 1.0f, 1.2f);
            Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.7f);
            Fx.sound(body, Sound.ENTITY_ALLAY_ITEM_GIVEN, 0.9f, 0.7f);
        }

        /** Hovering at the victim's lips: every pulse drinks a sip of life and gauge, and the bead swells. */
        private boolean latched(long tick) {
            long held = tick - phaseStart;
            if (!Combat.isTargetable(ctx(), owner, victim) || !victim.getWorld().equals(world)) {
                turnBack(tick);
                return true;
            }
            Location lips = lips();
            position.set(lips.getX(), lips.getY(), lips.getZ());
            float swell = 1f + 0.6f * pulsed / pulses;
            pose(swell);
            if (held > 0 && held % BEAD_PULSE == 0 && pulsed < pulses) {
                pulse();
            }
            if (held >= drainTicks || pulsed >= pulses) {
                turnBack(tick);
            }
            return true;
        }

        private void pulse() {
            pulsed++;
            double sip = Math.max(0, params().num("bead_drain")) / pulses;
            double before = health(victim);
            Combat.hit(ctx(), owner, victim, sip);
            double after = victim.isDead() ? 0 : health(victim);
            drank += Math.max(0, before - after);
            if (victim instanceof Player drained && ctx().isAlive(drained)) {
                double share = Math.max(0, params().num("bead_gauge")) / pulses;
                double taken = Math.min(ctx().gauge(drained), share);
                if (taken > 0) {
                    ctx().addGauge(drained, -taken);
                    stolen += taken;
                }
            }
            Location body = Fx.body(victim);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                Location from = body.clone().add(random.nextDouble(-0.3, 0.3), random.nextDouble(-0.5, 0.4),
                        random.nextDouble(-0.3, 0.3));
                Vfx.trail(from, position, i % 2 == 0 ? PALETTE.core() : PALETTE.body(), 5 + random.nextInt(3),
                        false);
            }
            if (pulsed % 2 == 0) {
                Vfx.dot(position.clone().add(0, 0.35, 0), Particle.HEART, null, false);
            }
            Fx.sound(position, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.0f + 0.12f * pulsed);
            Fx.sound(position, Sound.ITEM_BOTTLE_FILL_DRAGONBREATH, 0.4f, 1.5f + Fx.jitter(0.1f));
        }

        private void turnBack(long tick) {
            if (phase == BeadPhase.RETURN) {
                return;
            }
            phase = BeadPhase.RETURN;
            phaseStart = tick;
            Fx.sound(position, Sound.ENTITY_FOX_AMBIENT, 0.7f, 1.6f);
        }

        /** Flying home on a violet ribbon, straight at the fox's chest. */
        private boolean back(long tick) {
            if (!owner.getWorld().equals(world)) {
                return false;
            }
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
            Vector to = chest.toVector().subtract(position.toVector());
            double distance = to.length();
            double speed = BEAD_RETURN_SPEED + 0.03 * (tick - phaseStart);
            if (distance <= speed + 0.5 || tick - phaseStart > BEAD_RETURN_MAX) {
                position.set(chest.getX(), chest.getY(), chest.getZ());
                return false;
            }
            Location from = position.clone();
            position.add(to.multiply(speed / distance));
            pose(1f + 0.6f * pulsed / pulses);
            Vfx.line(ctx(), from, position, 3, Particle.DUST_COLOR_TRANSITION, FIRE_FADE, false);
            if (tick % 2 == 0) {
                Vfx.dot(position, Particle.END_ROD, null, false);
            }
            return true;
        }

        /** Home: the bead bursts into the fox, healing it, handing over the gauge and growing tails. */
        private void absorb() {
            for (BlockDisplay display : new BlockDisplay[] {shell, core}) {
                if (display != null && display.isValid()) {
                    display.remove();
                }
            }
            if (!owner.isOnline() || !ctx().isAlive(owner)) {
                return;
            }
            if (drank > 0) {
                ctx().heal(owner, drank);
            }
            if (stolen > 0) {
                ctx().addGauge(owner, stolen);
            }
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
            Location feet = owner.getLocation().add(0, 0.1, 0);
            Vfx.dot(chest, Particle.FLASH, PALETTE.core(), true);
            Vfx.sphere(ctx(), chest, 0.9, 24, Particle.DUST_COLOR_TRANSITION, FIRE_GLOW, true);
            Vfx.helix(ctx(), feet, 0.6, owner.getHeight(), 1.5, 2, 10, 0, Particle.DUST, PINK_DUST, true);
            Vfx.cloud(ctx(), chest, Particle.CHERRY_LEAVES, 10, 0.5, 0.02, null, true);
            Vfx.shockwave(ctx(), owner, feet, 0.3, 1.8, 5, Particle.DUST, VIOLET_DUST, true);
            Fx.sound(chest, Sound.ENTITY_FOX_AMBIENT, 1.0f, 1.0f);
            Fx.sound(chest, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.6f);
            Fx.sound(chest, Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 1.8f);
            if (granted) {
                growTails(params().integer("bead_tails"), null);
            }
            owner.sendActionBar(Text.mm("<" + PINK + "><b>여우구슬</b></" + PINK + "> <gray>체력</gray> <white>+"
                    + AbilityInfo.formatNumber(Math.round(drank * 10) / 10.0) + "</white> <dark_gray>|</dark_gray> "
                    + "<gray>게이지</gray> <" + VIOLET + ">+" + AbilityInfo.formatNumber(Math.round(stolen * 10) / 10.0)
                    + "%</" + VIOLET + ">"));
        }

        /** Where the bead hovers while drinking: in front of the victim's face. */
        private Location lips() {
            Location eye = victim.getEyeLocation();
            Vector facing = eye.getDirection().setY(0);
            if (facing.lengthSquared() < 1.0E-4) {
                facing = owner.getLocation().toVector().subtract(victim.getLocation().toVector()).setY(0);
            }
            if (facing.lengthSquared() < 1.0E-4) {
                facing = new Vector(0, 0, 1);
            }
            Location lips = eye.clone().add(facing.normalize().multiply(victim.getWidth() * 0.5 + 0.35))
                    .subtract(0, 0.15, 0);
            lips.add(0, 0.06 * Math.sin(spin * 0.1), 0);
            return Displays.unrotated(lips);
        }

        private void pose(float swell) {
            spin += 23f;
            BlockDisplay glass = shell;
            if (glass != null && glass.isValid()) {
                Displays.moveTo(glass, position);
                float size = BEAD_SHELL_SCALE * swell;
                Displays.animate(glass, Displays.transform(0, 0, 0, size, size, size, spin, 0, 30f), 1);
            }
            BlockDisplay light = core;
            if (light != null && light.isValid()) {
                Displays.moveTo(light, position);
                float size = BEAD_CORE_SCALE * swell;
                Displays.animate(light, Displays.transform(0, 0, 0, size, size, size, -spin * 1.3f, 45f, 0), 1);
            }
        }
    }

    private static double health(LivingEntity entity) {
        return entity.getHealth() + entity.getAbsorptionAmount();
    }

    // ------------------------------------------------------------------ display budget

    /** True if {@code count} more displays stay within {@link #MAX_DISPLAYS}. */
    private boolean canSpawn(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    private void remember(Display display) {
        displays.add(display);
    }
}
