package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
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
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 격투가 — a wrestler who fights at arm's length with grabs, throws and counters. Taking a melee hit fires up its
 * fighting spirit, so the next melee blow within a moment is a counter that hits harder and staggers; the active grabs
 * the enemy in front and suplexes it over the owner's head into the ground behind; the ultimate dashes in, grabs an
 * enemy, leaps high with it and piledrives it into the floor.
 *
 * <p>Unlike the 무도가 (combo strikes, ranged wind palm, punch barrage), everything here is a hold: the enemy is lifted,
 * carried and slammed, and the damage lands where the body hits the ground.
 *
 * <p>Look: the colours of a wrestling ring — vermilion fighting spirit with a cream-white core and navy shadows — and
 * comic-book impacts: an eight-pointed impact star that always faces the viewer and onomatopoeia pop-ups ("반격!",
 * "쾅!"). An armed counter makes the fists smoulder. The suplex is telegraphed by a dotted path and a landing ring and
 * drawn as a bright swing arc over the owner's head. The piledriver streaks in, launches the pair on a spiral of dust,
 * rings the bell at the apex while a reticle closes on the landing spot, and hits the floor with a flash, shockwaves,
 * a crater (a dark pit, cracks, tipped-up floor plates, flying debris and a few short-lived rubble blocks) and an
 * announcer's banner: "파일드라이버!!", or "K.O.!" with three bells when it kills.
 */
public final class FighterAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("fighter", "격투가", Material.LEATHER)
            .color(TextColor.color(0xFF6B3D))
            .difficulty(2)
            .passive("투지",
                    "넉백 저항이 {knockback_resist}% 높습니다. 근접 공격을 받고 {counter_window}초 안에 가하는 근접 공격은 "
                            + "반격이 되어 {counter_bonus} 추가 피해를 주고, 상대를 {counter_stagger}초간 휘청이게 합니다"
                            + "(이동·공격 속도 {stagger_slow}% 감소, 반격 후 {counter_cooldown}초 재충전).")
            .active("잡기 메치기",
                    "앞의 적({grab_range}블록 이내)을 붙잡아 머리 위로 넘겨 등 뒤에 내리꽂습니다. {suplex_damage} 피해와 "
                            + "{suplex_stun}초 기절을 주고, 떨어진 자리 반경 {suplex_radius}블록의 다른 적도 {suplex_splash} "
                            + "피해를 입습니다. 잡을 적이 없으면 헛손질만 하며 쿨타임은 돌지 않습니다.",
                    9)
            .ultimate("파일드라이버",
                    "조준한 적(없으면 가장 가까운 적, {ult_range}블록 이내)에게 돌진해 붙잡고 약 {ult_height}블록 뛰어올라 "
                            + "땅에 내리꽂습니다. {ult_damage} 피해와 {ult_stun}초 기절을 주고, 충격파가 반경 {ult_radius}블록의 "
                            + "다른 적에게 {ult_splash} 피해를 줍니다. 붙잡힌 적은 능력을 쓸 수 없고, 나는 낙하 피해를 받지 "
                            + "않습니다. 떨어진 자리는 {crater_duration}초간 움푹 파입니다.")
            .param("knockback_resist", 30)
            .param("counter_window", 1.5)
            .param("counter_bonus", 3)
            .param("counter_stagger", 0.6)
            .param("stagger_slow", 50)
            .param("counter_cooldown", 3)
            .param("grab_range", 4)
            .param("suplex_damage", 5)
            .param("suplex_stun", 0.75)
            .param("suplex_radius", 2.5)
            .param("suplex_splash", 3)
            .param("whiff_lunge", 1.5)
            .param("ult_range", 14)
            .param("ult_height", 6)
            .param("ult_damage", 10)
            .param("ult_stun", 1)
            .param("ult_radius", 4)
            .param("ult_splash", 4)
            .param("crater_duration", 3)
            .build();

    private static final String COLOR_TAG = "#FF6B3D";
    private static final String CREAM_TAG = "#FFF1E0";
    private static final String GOLD_TAG = "#FFD45E";
    /** The colours of a wrestling ring: cream-white core, vermilion fighting spirit, navy shadow. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF1E0, 0xFF5A36, 0x23306B);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition SPARK = PALETTE.glow(0.7f);
    private static final Particle.DustOptions BODY = PALETTE.dust(0.9f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.1f);
    private static final Particle.DustOptions SHADOW = new Particle.DustOptions(PALETTE.edge(), 0.8f);
    private static final Particle.DustOptions SHADOW_BIG = new Particle.DustOptions(PALETTE.edge(), 1.4f);
    private static final double TAU = Math.PI * 2;
    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.5;

    // passive: fighting spirit
    /** The stumble of a staggered enemy, added once vanilla knockback has been applied. */
    private static final double STUMBLE_PUSH = 0.45;
    private static final double STUMBLE_LIFT = 0.2;
    private static final long COUNTER_LABEL_TICKS = 16;
    private static final long COUNTER_STAR_TICKS = 7;
    /** Where the fists are on a full-size body: height above the feet, out to the side, forward. */
    private static final double FIST_HEIGHT = 0.95;
    private static final double FIST_SIDE = 0.38;
    private static final double FIST_FORWARD = 0.3;

    // active: grab and suplex
    /** Cosine of the half-angle of the cone in front in which an un-aimed grab still finds an enemy (60 degrees). */
    private static final double GRAB_CONE_COS = 0.5;
    private static final int GRIP_TICKS = 3;
    private static final int ARC_TICKS = 8;
    private static final int SLAM_TICKS = 3;
    private static final int SUPLEX_TICKS = GRIP_TICKS + ARC_TICKS + SLAM_TICKS;
    /** The swing turns around the owner's shoulders, at this share of its height. */
    private static final double PIVOT_SHARE = 0.7;
    /** Clearance between the owner's head and a lifted body's feet. */
    private static final double HEAD_GAP = 0.1;
    private static final double LANDING_GAP = 0.45;
    /** A thrown body may land this far below the owner's feet (steps and slopes, not off cliffs). */
    private static final double LANDING_DROP = 3.0;
    /** The throw breaks when the held body is seen this far from everywhere it was steered through lately. */
    private static final double SUPLEX_LEASH = 3.0;
    private static final double SLAM_BOUNCE = 0.25;
    private static final double SPLASH_PUSH = 0.6;
    private static final double SPLASH_LIFT = 0.4;
    /** Roughly the blocks a small forward hop travels per block/tick of launch speed (air drag, then friction). */
    private static final double LUNGE_PER_SPEED = 6.0;
    private static final double LUNGE_LIFT = 0.18;
    /** A whiff right after another one does not lunge again, so spamming the key is no free movement. */
    private static final long WHIFF_LOCKOUT_TICKS = 12;
    private static final long SLAM_LABEL_TICKS = 14;
    private static final long SLAM_STAR_TICKS = 9;

    // ultimate: piledriver
    /** Within this horizontal distance (and in sight) the owner grabs without dashing. */
    private static final double ULT_GRAB_REACH = 2.6;
    private static final int LIFT_TICKS = 4;
    private static final int RISE_TICKS = 9;
    private static final int TURN_TICKS = 4;
    private static final int DRIVE_TICKS = 6;
    /** Extra ticks the drive keeps pushing when the ground is reached late (deep ground, a laggy client). */
    private static final int DRIVE_SLACK_TICKS = 12;
    private static final int RISE_START = LIFT_TICKS;
    private static final int TURN_START = RISE_START + RISE_TICKS;
    private static final int DRIVE_START = TURN_START + TURN_TICKS;
    private static final int DRIVE_END = DRIVE_START + DRIVE_TICKS;
    private static final int PILEDRIVER_TICKS = DRIVE_END + DRIVE_SLACK_TICKS + 1;
    /** The pair takes off only above ground at most this deep (never over the void). */
    private static final double GROUND_PROBE = 16;
    private static final double CEILING_MARGIN = 0.2;
    private static final double LEAP_STEP = 0.5;
    /** Gap between the owner's and the held body's hitboxes where it is driven down. */
    private static final double DRIVE_GAP = 0.15;
    /** The held body is turned over this high above the owner's feet before the drive. */
    private static final double TURN_HEIGHT = 0.3;
    /** While carried overhead the body sits this little bit forward, so the two hitboxes are never stacked exactly. */
    private static final double OVERHEAD_FORWARD = 0.1;
    /** The piledriver lets go when the held body is seen this far from everywhere it was steered through lately. */
    private static final double ESCAPE_LEASH = 3.5;
    /** It also lets go when the owner is seen this far off its own path (pushed or teleported away). */
    private static final double DRIFT_LIMIT = 7.0;
    /** Slow falling given to a held body as a safety net beyond the planned hold, so a lost grip never hurts it. */
    private static final long SAFETY_FALL_TICKS = 60;
    private static final long LANDING_GRACE_TICKS = 160;
    private static final double REBOUND_BACK = 0.3;
    private static final double REBOUND_UP = 0.42;
    /** Roughly where the rebound hop lands, and how far below the owner that may be. */
    private static final double REBOUND_REACH = 2.2;
    private static final double REBOUND_DROP = 1.0;
    private static final double BLAST_PUSH = 0.9;
    private static final double BLAST_LIFT = 0.55;
    private static final long ANNOUNCER_TICKS = 34;
    private static final double ANNOUNCER_HEIGHT = 3.0;
    private static final long[] BELL_DELAYS = {1, 6, 11};
    private static final long ULT_STAR_TICKS = 11;

    // crater
    private static final int CRACKS = 5;
    private static final float CRACK_WIDTH = 0.07f;
    private static final int RIM_PLATES = 7;
    private static final int DEBRIS = 4;
    private static final long DEBRIS_TICKS = 26;
    /** Shares of its full reach a chunk of debris tries, farthest first, when a wall or a step is in the way. */
    private static final double[] DEBRIS_REACH_SHARES = {1.0, 0.65, 0.35};
    /** Half-size of the box that must be free of blocks around a point where a display without own light goes. */
    private static final double OPEN_PROBE = 0.05;
    private static final int RUBBLE_BLOCKS = 5;
    private static final int RUBBLE_TRIES = 12;
    private static final double RUBBLE_RADIUS = 2.4;
    private static final int SINK_TICKS = 8;
    private static final int AFTERGLOW_RUNS = 10;

    private static final int STAR_RAYS = 4;
    private static final BlockData STAR_LONG = Material.ORANGE_CONCRETE.createBlockData();
    private static final BlockData STAR_SHORT = Material.WHITE_CONCRETE.createBlockData();
    private static final BlockData CRACK = Material.BLACK_CONCRETE.createBlockData();
    private static final BlockData PIT = Material.COAL_BLOCK.createBlockData();
    private static final BlockData FALLBACK_FLOOR = Material.COBBLESTONE.createBlockData();
    /** Broken floor thrown up at the crater's rim (never blocks that fall). */
    private static final BlockData[] RUBBLE = {
            Material.CRACKED_STONE_BRICKS.createBlockData(),
            Material.COBBLESTONE.createBlockData(),
            Material.CRACKED_STONE_BRICKS.createBlockData(),
            Material.TUFF.createBlockData()};

    /** Tick until which the next own melee hit is a counter, -1 while not armed. */
    private long armedUntil = -1;
    /** No counter is armed before this tick (the recharge after a counter). */
    private long counterReadyAt;
    /** A grab (the suplex or the piledriver) is running. */
    private boolean grappling;
    private long lastWhiff = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow

    /** Where a thrown body comes down: behind the owner (a suplex) or, with no room there, in front of it. */
    private record Landing(Location feet, boolean behind) {
    }

    /** Where the piledriver drives the held body down: an offset from the owner's column and its ground height. */
    private record Drive(Vector offset, double groundY) {
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        double resistance = Math.clamp(params().num("knockback_resist") / 100.0, 0.0, 1.0);
        if (resistance > 0) {
            ctx().addModifier(player(), player(), Attribute.KNOCKBACK_RESISTANCE, resistance, Operation.ADD_NUMBER, 0);
        }
    }

    @Override
    public void onRevoke() {
        armedUntil = -1;
        grappling = false;
    }

    /** While a counter is armed the fists smoulder; when the window runs out they give off a last puff. */
    @Override
    public void onTick(long tick) {
        if (armedUntil < 0) {
            return;
        }
        long now = ctx().currentTick();
        Player owner = player();
        if (now > armedUntil) {
            armedUntil = -1;
            for (Location fist : fists(owner)) {
                Vfx.dot(fist, Particle.SMOKE, null, false);
            }
            return;
        }
        if (now % 2 != 0) {
            return;
        }
        Location[] fists = fists(owner);
        for (Location fist : fists) {
            Vfx.dot(fist, Particle.DUST_COLOR_TRANSITION, SPARK, false);
        }
        if (now % 6 == 0) {
            Vfx.dot(fists[(int) (now / 6 % 2)].add(0, 0.12, 0), Particle.CRIT, null, false);
        }
    }

    // ------------------------------------------------------------------ passive: fighting spirit

    /** An enemy's melee hit arms the counter (unless the last counter is still recharging). */
    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || ctx().isAbilityDamage() || !(event instanceof EntityDamageByEntityEvent hit)
                || !isMelee(event.getCause()) || !ctx().isEnemy(player(), hit.getDamager())) {
            return;
        }
        long window = params().ticks("counter_window");
        long now = ctx().currentTick();
        if (window <= 0 || now < counterReadyAt) {
            return;
        }
        boolean fresh = !isArmed(now);
        armedUntil = now + window;
        if (fresh) {
            armFx();
        }
    }

    /** The counter's bonus: only changes the damage, since the victim may still cancel the hit. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOwnMelee(event, victim) || !isArmed(ctx().currentTick())) {
            return;
        }
        event.setDamage(event.getDamage() + Math.max(0, params().num("counter_bonus")));
    }

    /** The counter landed: it is used up and the victim staggers. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        long now = ctx().currentTick();
        if (!isOwnMelee(event, victim) || !isArmed(now)) {
            return;
        }
        armedUntil = -1;
        counterReadyAt = now + params().ticks("counter_cooldown");
        counter(victim);
    }

    private static boolean isMelee(DamageCause cause) {
        return cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK;
    }

    private boolean isArmed(long now) {
        return armedUntil >= 0 && now <= armedUntil;
    }

    /** The owner's own swing at an enemy (not ability damage, sweeps, projectiles or summon bites). */
    private boolean isOwnMelee(EntityDamageByEntityEvent event, LivingEntity victim) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && event.getCause() == DamageCause.ENTITY_ATTACK && ctx().isEnemy(player(), victim);
    }

    /** The spirit flares up: a ring round the chest, sparks at the fists and a heartbeat. */
    private void armFx() {
        Player owner = player();
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        Vfx.ring(ctx(), chest, 0.75, 14, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_COLOR_TRANSITION,
                GLOW, false);
        for (Location fist : fists(owner)) {
            Vfx.cloud(ctx(), fist, Particle.DUST_COLOR_TRANSITION, 3, 0.06, 0, SPARK, false);
        }
        Fx.sound(chest, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.9f, 0.6f);
        Fx.sound(chest, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1.3f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>투지!</b></" + COLOR_TAG + "> <white>다음 근접 공격은 반격"
                + "</white> <gray>(" + AbilityInfo.formatNumber(params().num("counter_window")) + "초)</gray>"));
    }

    /** The counter: the victim staggers (slower feet and swings) and stumbles back under a comic impact star. */
    private void counter(LivingEntity victim) {
        Player owner = player();
        long ticks = params().ticks("counter_stagger");
        double slow = Math.clamp(params().num("stagger_slow") / 100.0, 0.0, 1.0);
        if (ticks > 0 && slow > 0) {
            ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, ticks);
            ctx().addModifier(owner, victim, Attribute.ATTACK_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, ticks);
            daze(victim, ticks, false);
        }
        // Vanilla knockback is applied after this event: stumble a tick later so it is not overwritten.
        ctx().runLater(owner, 1, () -> {
            if (Combat.isTargetable(ctx(), owner, victim)) {
                Motion.push(victim, facingTo(owner, victim).multiply(STUMBLE_PUSH).setY(STUMBLE_LIFT), MAX_SPEED);
            }
        });

        Vector toward = facingTo(owner, victim);
        Location point = Fx.body(victim).subtract(toward.clone().multiply(victim.getWidth() * 0.5 + 0.1));
        impactStar(point, 1.1f, COUNTER_STAR_TICKS, false);
        popText(victim.getLocation().add(0, victim.getHeight() + 0.55, 0),
                "<b><gradient:" + CREAM_TAG + ":" + COLOR_TAG + ">반격!</gradient></b>", 1.0f, COUNTER_LABEL_TICKS);
        Vfx.dot(point, Particle.FLASH, PALETTE.body(), false);
        Vfx.cloud(ctx(), point, Particle.CRIT, 12, 0.25, 0.45, null, false);
        Vfx.cloud(ctx(), point, Particle.DUST_COLOR_TRANSITION, 8, 0.2, 0, GLOW, false);
        Location center = point.clone();
        Loop.repeat(ctx(), owner, 0, 1, 3, run -> {
            ringAround(center.clone().add(toward.clone().multiply(0.2 * run)), toward, 0.45 + run * 0.3, 12,
                    run * 0.5, Particle.DUST_COLOR_TRANSITION, GLOW, false);
            return true;
        });
        Fx.sound(point, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.8f + Fx.jitter(0.08f));
        Fx.sound(point, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.8f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(point, Sound.ITEM_SHIELD_BLOCK, 0.5f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(point, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.0f, 0.7f);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>반격!</b></" + COLOR_TAG + "> <white>+"
                + AbilityInfo.formatNumber(params().num("counter_bonus")) + " 피해</white> <gray>휘청 "
                + AbilityInfo.formatNumber(params().num("counter_stagger")) + "초</gray>"));
    }

    // ------------------------------------------------------------------ active: grab and suplex

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 잡을 수 없습니다");
        }
        if (grappling) {
            return ActivationResult.fail("이미 상대를 붙잡고 있습니다");
        }
        LivingEntity target = grabTarget(owner, Math.max(1, params().num("grab_range")));
        if (target == null) {
            whiff(owner);
            return ActivationResult.fail("손이 닿는 곳에 적이 없습니다");
        }
        Vector forward = facingTo(owner, target);
        double radius = swingRadius(owner, target);
        Landing landing = landing(owner, target, forward, radius);
        if (landing == null) {
            return ActivationResult.fail("메칠 자리가 없습니다");
        }
        new Suplex(owner, target, forward, radius, landing).begin();
        return ActivationResult.success();
    }

    /** The aimed enemy within reach, else the nearest one in a cone in front with nothing solid in between. */
    private @Nullable LivingEntity grabTarget(Player owner, double range) {
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed != null) {
            return aimed;
        }
        Location feet = owner.getLocation();
        Location eye = owner.getEyeLocation();
        Vector facing = Motion.horizontalFacing(feet);
        for (LivingEntity enemy : ctx().enemiesNear(owner, feet, range)) {
            if (!enemy.getWorld().equals(owner.getWorld())) {
                continue;
            }
            Vector to = Motion.horizontal(enemy.getLocation().toVector().subtract(feet.toVector()));
            if (to.lengthSquared() > 1.0E-6 && to.dot(facing) < GRAB_CONE_COS) {
                continue;
            }
            if (Spots.clearPath(eye, Fx.body(enemy))) {
                return enemy;
            }
        }
        return null;
    }

    /** The radius of the swing around the shoulders that carries {@code target} just clear of the owner's head. */
    private static double swingRadius(Player owner, LivingEntity target) {
        return owner.getHeight() * (1 - PIVOT_SHARE) + HEAD_GAP + target.getHeight() / 2;
    }

    /**
     * Where the throw comes down: behind the owner (where the swing ends, or closer), else in front of it. Every spot
     * must be safe ground for the thrown body (no wall, hazard or void, inside the arena), at most
     * {@link #LANDING_DROP} below the owner and in a clear line from its shoulders. Null if there is none.
     */
    private @Nullable Landing landing(Player owner, LivingEntity target, Vector forward, double radius) {
        Location feet = owner.getLocation();
        Body body = Body.standing(target);
        Location shoulders = feet.clone().add(0, owner.getHeight() * PIVOT_SHARE, 0);
        double near = owner.getWidth() / 2 + body.width() / 2 + LANDING_GAP;
        for (int side : new int[] {-1, 1}) {
            for (double distance : new double[] {radius, near}) {
                Location above = feet.clone().add(forward.clone().multiply(side * distance)).add(0, 1.0, 0);
                Location ground = groundBelow(above, LANDING_DROP + 1.0);
                if (ground != null && Spots.isSafe(ctx(), ground, body)
                        && Spots.clearPath(shoulders, ground.clone().add(0, body.height() * 0.5, 0))) {
                    return new Landing(ground, side < 0);
                }
            }
        }
        return null;
    }

    /** Nobody in reach: the hands snap shut on thin air, with a short lunge (not when spammed or airborne). */
    private void whiff(Player owner) {
        Location feet = owner.getLocation();
        Vector forward = Motion.horizontalFacing(feet);
        long now = ctx().currentTick();
        boolean lunge = now - lastWhiff >= WHIFF_LOCKOUT_TICKS && (Motion.isGrounded(owner) || owner.isInWater());
        lastWhiff = now;
        if (lunge) {
            double distance = Spots.arenaReach(ctx(), feet, forward, Math.max(0, params().num("whiff_lunge")));
            if (distance > 0) {
                owner.setVelocity(Motion.clamp(forward.clone().multiply(distance / LUNGE_PER_SPEED).setY(LUNGE_LIFT),
                        MAX_SPEED));
            }
        }
        Location chest = feet.clone().add(0, owner.getHeight() * 0.6, 0);
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        Location meet = chest.clone().add(forward.clone().multiply(1.6));
        for (int hand = -1; hand <= 1; hand += 2) {
            Location from = chest.clone().add(side.clone().multiply(0.7 * hand)).add(forward.clone().multiply(0.4));
            Vfx.line(ctx(), from, meet, 5, Particle.DUST_COLOR_TRANSITION, SPARK, false);
            Vfx.trail(from, meet, PALETTE.body(), 4, false);
        }
        Vfx.cloud(ctx(), meet, Particle.CLOUD, 4, 0.15, 0.02, null, false);
        Vfx.cloud(ctx(), feet.clone().add(0, 0.1, 0), Particle.CLOUD, 3, 0.2, 0.01, null, false);
        Fx.sound(chest, Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 1.0f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(chest, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.4f, 1.5f + Fx.jitter(0.1f));
    }

    /**
     * 잡기 메치기: the owner braces (rooted), grips the enemy, swings it over its head around the shoulders and slams it
     * down behind (or, with no room behind, back down in front). The held body is steered by velocity from where it is
     * reckoned to be (see {@link Reckoning}), so walls stop it like any movement, it can never end up inside a block
     * and a lagging player follows the swing instead of being flung past it.
     */
    private final class Suplex implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final Vector forward;
        private final double radius;
        private final Landing landing;
        private final Location pivot;
        private final Location start;
        private final double halfHeight;
        private final NamespacedKey[] brace;
        private final Reckoning held;
        private Location lastCenter;

        Suplex(Player owner, LivingEntity target, Vector forward, double radius, Landing landing) {
            this.owner = owner;
            this.target = target;
            this.forward = forward;
            this.radius = radius;
            this.landing = landing;
            this.pivot = owner.getLocation().add(0, owner.getHeight() * PIVOT_SHARE, 0);
            this.start = Fx.body(target);
            this.halfHeight = target.getHeight() / 2;
            this.brace = root(owner, SUPLEX_TICKS + 4);
            this.held = new Reckoning(target);
            this.lastCenter = start.clone();
        }

        void begin() {
            grappling = true;
            held.send(new Vector());
            target.setFallDistance(0);
            holdSafely(target, SUPLEX_TICKS + 2);
            telegraph();
            gripFx(owner, target, false);
            Fx.sound(start, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.9f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(start, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.7f, 1.2f + Fx.jitter(0.1f));
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>잡았다!</b></" + COLOR_TAG + ">"));
            Loop.repeat(ctx(), owner, 0, 1, SUPLEX_TICKS + 2, this, this::end);
        }

        @Override
        public boolean test(long tick) {
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(owner.getWorld())) {
                letGo(false);
                return false;
            }
            if (held.observe(SUPLEX_LEASH) > SUPLEX_LEASH) {
                letGo(true);
                return false;
            }
            // The slam lands once the body is down where it really is (a lagging player's client is there already).
            long slam = GRIP_TICKS + ARC_TICKS;
            if (tick >= SUPLEX_TICKS || (tick > slam && held.grounded())) {
                impact();
                return false;
            }
            Location center = centerAt(tick);
            held.steer(center.clone().subtract(0, halfHeight, 0));
            swing(center, tick);
            lastCenter = center;
            return true;
        }

        /** The held body's centre: gripped and lifted, swung along the arc, then slammed onto the landing spot. */
        private Location centerAt(long tick) {
            if (tick < GRIP_TICKS) {
                return lerp(start, arc(0), smooth((tick + 1.0) / GRIP_TICKS));
            }
            long swing = tick - GRIP_TICKS;
            if (swing < ARC_TICKS) {
                return arc(angle(smooth((swing + 1.0) / ARC_TICKS)));
            }
            double s = Math.min(1, (swing - ARC_TICKS + 1.0) / SLAM_TICKS);
            return lerp(arc(angle(1)), landing.feet().clone().add(0, halfHeight, 0), s * s);
        }

        /** 0 is in front of the owner, half pi over its head, pi behind it; the front slam goes up and comes back. */
        private double angle(double t) {
            return landing.behind() ? Math.PI * t : Math.PI / 2 * Math.sin(Math.PI * t);
        }

        private Location arc(double angle) {
            return pivot.clone().add(forward.clone().multiply(radius * Math.cos(angle))).add(0, radius * Math.sin(angle),
                    0);
        }

        /** The path of the throw, dotted, and the ring where it comes down. */
        private void telegraph() {
            int points = Vfx.n(ctx(), 14);
            for (int i = 0; i <= points; i++) {
                Vfx.dot(arc(angle((double) i / points)), Particle.DUST, BODY, false);
            }
            Location ground = landing.feet().clone().add(0, 0.1, 0);
            double splash = Math.max(0.5, params().num("suplex_radius"));
            Vfx.ring(ctx(), ground, splash, (int) Math.max(12, splash * 7), 0, Particle.DUST_COLOR_TRANSITION, FADE,
                    false);
            Vfx.ring(ctx(), ground, 0.5, 8, 0.3, Particle.DUST_COLOR_TRANSITION, GLOW, false);
        }

        /** The swing arc drawn behind the body, a grip ring round its waist and a whoosh over the top. */
        private void swing(Location center, long tick) {
            Vfx.line(ctx(), lastCenter, center, 4, Particle.DUST_COLOR_TRANSITION, GLOW, false);
            Vfx.trail(center, lastCenter, PALETTE.core(), 5, false);
            if (tick % 2 == 0) {
                Vfx.ring(ctx(), center, target.getWidth() * 0.5 + 0.2, 8, tick * 0.6, Particle.DUST, BODY, false);
            }
            if (tick == GRIP_TICKS + ARC_TICKS / 2) {
                Vfx.dot(center, Particle.SWEEP_ATTACK, null, false);
                Fx.sound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 0.6f + Fx.jitter(0.05f));
                Fx.sound(center, Sound.ENTITY_PHANTOM_SWOOP, 0.5f, 1.5f + Fx.jitter(0.1f));
            }
        }

        private void impact() {
            unroot(owner, brace);
            Location feet = held.location();
            target.setFallDistance(0);
            clearSafety(target);
            Combat.hit(ctx(), owner, target, Math.max(0, params().num("suplex_damage")));
            if (Combat.isTargetable(ctx(), owner, target)) {
                long stun = params().ticks("suplex_stun");
                if (stun > 0) {
                    root(target, stun);
                    daze(target, stun, true);
                }
                target.setVelocity(new Vector(0, SLAM_BOUNCE, 0));
            }
            double splashRadius = Math.max(0, params().num("suplex_radius"));
            int splashed = splash(feet, splashRadius, params().num("suplex_splash"), target, SPLASH_PUSH, SPLASH_LIFT,
                    false);
            slamFx(feet, splashRadius, target.getHeight());
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>" + (landing.behind() ? "메치기!" : "내리꽂기!")
                    + "</b></" + COLOR_TAG + ">" + (splashed > 0 ? " <gray>충격파 " + splashed + "명</gray>" : "")));
        }

        /** The grip broke (the body died, left or was carried off): a puff where it was. */
        private void letGo(boolean escaped) {
            unroot(owner, brace);
            Vfx.cloud(ctx(), lastCenter, Particle.CLOUD, 6, 0.3, 0.03, null, false);
            Vfx.cloud(ctx(), lastCenter, Particle.DUST_COLOR_TRANSITION, 6, 0.4, 0, FADE, false);
            Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 0.8f, 0.7f);
            if (escaped) {
                owner.sendActionBar(Text.mm("<gray>상대가 빠져나갔습니다</gray>"));
            }
        }

        private void end() {
            grappling = false;
            unroot(owner, brace);
        }
    }

    /** A body slammed into the ground: flash, star, "쾅!", a shockwave of dust and torn-up floor. */
    private void slamFx(Location feet, double radius, double height) {
        Player owner = player();
        World world = feet.getWorld();
        Location ground = feet.clone().add(0, 0.1, 0);
        BlockData floor = floorAt(feet);
        Location chest = feet.clone().add(0, 0.6, 0);
        Vfx.dot(chest, Particle.FLASH, PALETTE.core(), false);
        impactStar(chest, 1.5f, SLAM_STAR_TICKS, false);
        popText(feet.clone().add(0, height + 0.6, 0), "<b><gradient:" + CREAM_TAG + ":" + COLOR_TAG + ">쾅!</gradient></b>",
                1.1f, SLAM_LABEL_TICKS);
        Vfx.shockwave(ctx(), owner, ground, 0.3, Math.max(0.8, radius), 5, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(1.2f), false);
        Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.1, 0), 0.2, Math.max(0.6, radius * 0.8), 6, Particle.CLOUD,
                null, false);
        world.spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 24), 0.6, 0.15, 0.6, 0.25, floor);
        world.spawnParticle(Particle.DUST_PLUME, ground, Vfx.n(ctx(), 6), 0.5, 0.05, 0.5, 0.03);
        Vfx.cloud(ctx(), chest, Particle.CRIT, 14, 0.3, 0.5, null, false);
        Fx.sound(feet, Sound.ITEM_MACE_SMASH_GROUND, 1.0f, 0.9f + Fx.jitter(0.08f));
        Fx.sound(feet, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.ENTITY_PLAYER_BIG_FALL, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.BLOCK_ANVIL_LAND, 0.35f, 0.6f + Fx.jitter(0.05f));
        afterglow(feet, Math.max(1, radius * 0.6), floor);
    }

    // ------------------------------------------------------------------ ultimate: piledriver

    /**
     * Self-contained: it only uses owner-scoped tasks and guards the owner's landing with its own loop, so it also
     * works when borrowed by the Gambler.
     */
    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 사용할 수 없습니다");
        }
        if (grappling) {
            return ActivationResult.fail("이미 상대를 붙잡고 있습니다");
        }
        double range = params().num("ult_range");
        List<LivingEntity> candidates = new ArrayList<>();
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed != null) {
            candidates.add(aimed);
        }
        List<LivingEntity> nearby = new ArrayList<>(ctx().enemiesNear(owner, owner.getLocation(), range));
        nearby.sort(Comparator.comparingInt(enemy -> owner.hasLineOfSight(enemy) ? 0 : 1));
        for (LivingEntity enemy : nearby) {
            if (!candidates.contains(enemy)) {
                candidates.add(enemy);
            }
        }
        if (candidates.isEmpty()) {
            return ActivationResult.fail(AbilityInfo.formatNumber(range) + "블록 안에 적이 없습니다");
        }
        for (LivingEntity target : candidates) {
            if (!target.getWorld().equals(owner.getWorld())) {
                continue;
            }
            Location from = owner.getLocation();
            boolean close = horizontalDistance(from, target.getLocation()) <= ULT_GRAB_REACH
                    && owner.hasLineOfSight(target) && ctx().isInsideArena(from)
                    && groundBelow(from, GROUND_PROBE) != null;
            Location spot = close ? null : Spots.around(ctx(), owner, target, 180);
            if (!close && (spot == null || groundBelow(spot, GROUND_PROBE) == null)) {
                continue;
            }
            if (spot != null && !owner.teleport(spot, TeleportCause.PLUGIN)) {
                return ActivationResult.fail("돌진이 막혔습니다");
            }
            owner.setFallDistance(0);
            new Piledriver(owner, target).begin(spot != null ? from : null);
            return ActivationResult.success();
        }
        return ActivationResult.fail("적에게 다가갈 공간이 없습니다");
    }

    /**
     * 파일드라이버: the owner hoists the enemy over its head, leaps straight up with it, tips it over in front at the
     * apex and drives both down, the enemy first. Both bodies are steered by velocity along a planned path from where
     * they are reckoned to be (see {@link Reckoning}), so walls and ceilings stop them like any movement and lagging
     * players neither overshoot the leap nor swing off the path. The leap is lowered under a ceiling, the drive spot
     * avoids walls, hazards, the void and the arena border, and the takeoff itself is always above ground.
     *
     * <p>The held enemy is silenced while held and gets slow falling as a safety net, removed at the impact: if the
     * grip is lost in the air (the enemy dies or is carried off, the owner dies or is pushed away), it floats down
     * instead of taking fall damage. The owner's landing is guarded by its own loop.
     */
    private final class Piledriver implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final World world;
        private final Location takeoff;
        private final Vector forward;
        private final double headLift;
        private final double ownerGround;
        private final double leap;
        private final @Nullable Drive drive;
        private final Location targetStart;
        private final double liftReach;
        private final Reckoning ownerBody;
        private final Reckoning heldBody;
        private Location lastBody;

        Piledriver(Player owner, LivingEntity target) {
            this.owner = owner;
            this.target = target;
            this.world = owner.getWorld();
            this.takeoff = owner.getLocation();
            this.forward = facingTo(owner, target);
            this.headLift = owner.getHeight() + HEAD_GAP;
            Location ground = groundBelow(takeoff, GROUND_PROBE);
            this.ownerGround = ground != null ? ground.getY() : takeoff.getY();
            this.leap = leapHeight();
            this.drive = driveSpot();
            this.targetStart = target.getLocation();
            double gap = owner.getWidth() / 2 + target.getWidth() / 2 + DRIVE_GAP;
            this.liftReach = Math.clamp(horizontalDistance(takeoff, targetStart), gap, 1.6);
            this.ownerBody = new Reckoning(owner);
            this.heldBody = new Reckoning(target);
            this.lastBody = Fx.body(target);
        }

        void begin(@Nullable Location dashedFrom) {
            grappling = true;
            if (target instanceof Player held) {
                ctx().silence(held, PILEDRIVER_TICKS);
            }
            holdSafely(target, PILEDRIVER_TICKS);
            heldBody.send(new Vector());
            target.setFallDistance(0);
            guardLanding(owner);
            if (dashedFrom != null) {
                dashFx(dashedFrom, owner.getLocation());
            }
            grabFx();
            Loop.repeat(ctx(), owner, 0, 1, PILEDRIVER_TICKS, this, this::end);
        }

        // -------------------------------------------------------------- planning

        private double apexY() {
            return takeoff.getY() + leap;
        }

        /**
         * How high the pair can leap: the configured height, lowered in steps until the column above the owner (its
         * body with the held body on top) is free of blocks and below the world's ceiling.
         */
        private double leapHeight() {
            double half = Math.max(owner.getWidth(), target.getWidth()) / 2 - 0.01;
            double stack = headLift + target.getHeight() + CEILING_MARGIN;
            for (double h = Math.max(0, params().num("ult_height")); h > 0; h -= LEAP_STEP) {
                if (columnFree(takeoff, half, takeoff.getY() + 0.01, takeoff.getY() + h + stack)) {
                    return h;
                }
            }
            return 0;
        }

        /**
         * Where the held body is driven down: in front of the owner, else to a side or behind. The spot must be safe
         * ground for the body about level with the owner's, and its whole column up to the apex must be free. Null if
         * there is no such spot (then the body stays on the owner's shoulders all the way down).
         */
        private @Nullable Drive driveSpot() {
            Body body = Body.standing(target);
            double gap = owner.getWidth() / 2 + body.width() / 2 + DRIVE_GAP;
            double top = apexY() + TURN_HEIGHT + body.height() + CEILING_MARGIN;
            for (double angle : new double[] {0, 90, -90, 180}) {
                Vector offset = forward.clone().rotateAroundY(Math.toRadians(angle)).multiply(gap);
                Location column = takeoff.clone().add(offset);
                column.setY(ownerGround + 1.0);
                Location ground = groundBelow(column, 2.0);
                if (ground == null || !Spots.isSafe(ctx(), ground, body)
                        || !columnFree(ground, body.width() / 2 - 0.01, ground.getY() + 0.01, top)) {
                    continue;
                }
                return new Drive(offset, ground.getY());
            }
            return null;
        }

        private boolean columnFree(Location at, double half, double minY, double maxY) {
            if (maxY >= world.getMaxHeight()) {
                return false;
            }
            return !world.hasCollisionsIn(new BoundingBox(at.getX() - half, minY, at.getZ() - half, at.getX() + half,
                    maxY, at.getZ() + half));
        }

        /** Where the held body hits the ground. */
        private Location impactPoint() {
            Drive spot = drive;
            Location point = takeoff.clone();
            if (spot == null) {
                point.setY(ownerGround);
            } else {
                point.add(spot.offset());
                point.setY(spot.groundY());
            }
            return point;
        }

        // -------------------------------------------------------------- the hold

        @Override
        public boolean test(long tick) {
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(world)
                    || !owner.getWorld().equals(world)) {
                release(false);
                return false;
            }
            // Carried off, teleported or pushed away: seen far from everywhere either body was steered through lately.
            if (heldBody.observe(ESCAPE_LEASH) > ESCAPE_LEASH || ownerBody.observe(DRIFT_LIMIT) > DRIFT_LIMIT) {
                release(true);
                return false;
            }
            // The drive lands once the body hits the ground where it really is (a lagging client is there already).
            boolean down = drive == null ? ownerBody.grounded() : heldBody.grounded() || heldBody.inWater();
            if ((tick > DRIVE_START && down) || tick >= PILEDRIVER_TICKS - 1) {
                impact();
                return false;
            }
            Location ownerFeet = ownerAt(tick);
            Location targetFeet = targetAt(tick, ownerFeet);
            ownerBody.steer(ownerFeet);
            heldBody.steer(targetFeet);
            phaseFx(tick);
            return true;
        }

        /** The owner's feet: still while hoisting, rising with an ease-out, a beat at the apex, then the drive. */
        private Location ownerAt(long tick) {
            double y;
            if (tick < RISE_START) {
                y = takeoff.getY();
            } else if (tick < TURN_START) {
                double s = (tick - RISE_START + 1.0) / RISE_TICKS;
                y = takeoff.getY() + leap * (1 - (1 - s) * (1 - s));
            } else if (tick < DRIVE_START) {
                y = apexY();
            } else {
                double s = Math.min(1, (tick - DRIVE_START + 1.0) / DRIVE_TICKS);
                y = apexY() - (apexY() - ownerGround) * s * s;
            }
            return new Location(world, takeoff.getX(), y, takeoff.getZ());
        }

        /** The held body's feet: hoisted over the head, carried up, tipped over in front and driven into the ground. */
        private Location targetAt(long tick, Location ownerFeet) {
            Location overhead = ownerFeet.clone().add(forward.clone().multiply(OVERHEAD_FORWARD)).add(0, headLift, 0);
            if (tick < RISE_START) {
                Location control = takeoff.clone().add(forward.clone().multiply(liftReach)).add(0, headLift + 0.4, 0);
                return bezier(targetStart, control, overhead, smooth((tick + 1.0) / LIFT_TICKS));
            }
            Drive spot = drive;
            if (tick < TURN_START || spot == null) {
                return overhead;
            }
            Location front = takeoff.clone().add(spot.offset());
            if (tick < DRIVE_START) {
                Location top = front.clone();
                top.setY(apexY() + TURN_HEIGHT);
                Location control = top.clone().add(0, headLift, 0);
                return bezier(overhead, control, top, smooth((tick - TURN_START + 1.0) / TURN_TICKS));
            }
            double s = Math.min(1, (tick - DRIVE_START + 1.0) / DRIVE_TICKS);
            double from = apexY() + TURN_HEIGHT;
            front.setY(from - (from - spot.groundY()) * s * s);
            return front;
        }

        // -------------------------------------------------------------- outcomes

        /** The drive lands where the bodies really are (a lagging player's server position still trails them). */
        private void impact() {
            Location point = drive == null ? ownerBody.location() : heldBody.location();
            clearSafety(target);
            target.setFallDistance(0);
            owner.setFallDistance(0);
            Combat.hit(ctx(), owner, target, Math.max(0, params().num("ult_damage")));
            boolean knockedOut = isDown(target);
            if (!knockedOut && Combat.isTargetable(ctx(), owner, target)) {
                long stun = params().ticks("ult_stun");
                if (stun > 0) {
                    root(target, stun);
                    daze(target, stun, true);
                }
                target.setVelocity(new Vector(0, SLAM_BOUNCE, 0));
            }
            double radius = Math.max(0, params().num("ult_radius"));
            int splashed = splash(point, radius, params().num("ult_splash"), target, BLAST_PUSH, BLAST_LIFT, true);
            owner.setVelocity(rebound());
            impactFx(point, radius);
            crater(point);
            announcer(point, knockedOut);
            if (knockedOut) {
                knockout(point, target);
            }
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>파일드라이버!</b></" + COLOR_TAG + ">"
                    + (knockedOut ? " <" + GOLD_TAG + "><b>K.O.</b></" + GOLD_TAG + ">" : "")
                    + (splashed > 0 ? " <gray>충격파 " + splashed + "명</gray>" : "")));
        }

        /**
         * The owner rebounds off the body it just drove into the ground: a hop backwards, or straight up when the spot
         * behind is not safe ground inside the arena (a ledge, the void, a wall, the border).
         */
        private Vector rebound() {
            Location feet = ownerBody.location();
            Vector back = forward.clone().multiply(-1);
            Location landing = feet.clone().add(back.clone().multiply(REBOUND_REACH));
            Location ground = groundBelow(landing.clone().add(0, 1.0, 0), REBOUND_DROP + 1.0);
            boolean safe = ground != null && Spots.isSafe(ctx(), ground, Body.standing(owner))
                    && Spots.clearPath(feet.clone().add(0, 0.5, 0), ground.clone().add(0, 0.5, 0));
            return safe ? back.multiply(REBOUND_BACK).setY(REBOUND_UP) : new Vector(0, REBOUND_UP, 0);
        }

        /**
         * The grip is lost: the owner drops on its own (its landing is guarded), the held body keeps its slow falling.
         */
        private void release(boolean escaped) {
            Location at = Fx.body(owner);
            Vfx.cloud(ctx(), at, Particle.CLOUD, 8, 0.35, 0.03, null, true);
            Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 8, 0.45, 0, FADE, true);
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 1.0f, 0.6f);
            // Only the fall of the last step sent goes on (a player's velocity on the server is never read back).
            owner.setVelocity(new Vector(0, Math.min(0, ownerBody.lastSent().getY()), 0));
            owner.setFallDistance(0);
            if (escaped) {
                owner.sendActionBar(Text.mm("<gray>상대가 빠져나갔습니다</gray>"));
            }
        }

        private void end() {
            grappling = false;
        }

        // -------------------------------------------------------------- visuals

        /** The grab: a lock-on ring snapping shut on the enemy, a flash and the grip. */
        private void grabFx() {
            Location body = Fx.body(target);
            Vfx.dot(body, Particle.FLASH, PALETTE.body(), true);
            Location feet = target.getLocation().add(0, 0.1, 0);
            Loop.repeat(ctx(), owner, 0, 1, 3, run -> {
                Vfx.ring(ctx(), feet, 1.5 - 0.4 * run, 16, run * 0.5, Particle.DUST_COLOR_TRANSITION, GLOW, true);
                return true;
            });
            gripFx(owner, target, true);
            Fx.sound(body, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 0.6f);
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.5f);
            Fx.sound(body, Sound.ENTITY_RAVAGER_ROAR, 0.5f, 1.5f + Fx.jitter(0.05f));
        }

        private void phaseFx(long tick) {
            Location body = Fx.body(target);
            if (tick < RISE_START) {
                Vfx.ring(ctx(), body, target.getWidth() * 0.5 + 0.35 - 0.05 * tick, 10, tick * 0.6, Particle.DUST,
                        BODY, true);
                if (tick % 2 == 0) {
                    Vfx.cloud(ctx(), takeoff.clone().add(0, 0.1, 0), Particle.CLOUD, 2, 0.3, 0.01, null, false);
                }
            } else {
                Vfx.line(ctx(), lastBody, body, 3, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            }
            if (tick == RISE_START) {
                takeoffFx();
            }
            if (tick >= RISE_START && tick < TURN_START) {
                speedLines(-1, tick);
                if (tick % 2 == 0) {
                    Location base = owner.getLocation();
                    Vfx.helix(ctx(), base, 0.75, headLift + target.getHeight(), 1.25, 2, 10, tick * 0.5,
                            Particle.DUST_COLOR_TRANSITION, FADE, true);
                }
            }
            if (tick == TURN_START) {
                apexFx(body);
            }
            if (tick >= TURN_START) {
                reticle(tick);
            }
            if (tick == DRIVE_START) {
                Fx.sound(body, Sound.ENTITY_PHANTOM_SWOOP, 1.2f, 0.5f);
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.5f);
            }
            if (tick >= DRIVE_START) {
                speedLines(1, tick);
                Vfx.cloud(ctx(), body, Particle.CRIT, 3, 0.2, 0.1, null, true);
            }
            lastBody = body;
        }

        /** Streaks of light flying off the pair: down while rising ({@code -1}), up while driving down ({@code 1}). */
        private void speedLines(int direction, long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location from = Fx.body(direction < 0 ? owner : target);
            int streaks = Vfx.n(ctx(), 3);
            for (int i = 0; i < streaks; i++) {
                Location start = from.clone().add(random.nextDouble(-0.6, 0.6), random.nextDouble(-0.6, 0.6),
                        random.nextDouble(-0.6, 0.6));
                Vfx.trail(start, start.clone().add(0, direction * random.nextDouble(2.0, 3.2), 0),
                        (i + tick) % 3 == 0 ? PALETTE.core() : PALETTE.body(), 5, true);
            }
        }

        /** Lift-off: a ring of dust bursting from the floor and a battle cry. */
        private void takeoffFx() {
            Location ground = takeoff.clone();
            ground.setY(ownerGround + 0.1);
            BlockData floor = floorAt(ground);
            Vfx.shockwave(ctx(), owner, ground, 0.4, 2.4, 5, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.3f), true);
            Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.1, 0), 0.3, 1.8, 5, Particle.CLOUD, null, false);
            world.spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 20), 0.6, 0.1, 0.6, 0.2, floor, true);
            world.spawnParticle(Particle.DUST_PLUME, ground, Vfx.n(ctx(), 6), 0.4, 0.05, 0.4, 0.04, null, true);
            Fx.sound(ground, Sound.ENTITY_GOAT_LONG_JUMP, 1.2f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(ground, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.5f);
            Fx.sound(ground, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7f, 0.6f + Fx.jitter(0.05f));
        }

        /** The apex: a flash round the pair and the ring bell before the drop. */
        private void apexFx(Location body) {
            Vfx.dot(body, Particle.FLASH, PALETTE.core(), true);
            Location waist = owner.getLocation().add(0, owner.getHeight(), 0);
            Vfx.ring(ctx(), waist, 1.4, 20, 0, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Vfx.ring(ctx(), waist, 1.0, 14, 0.4, Particle.DUST, SHADOW, true);
            Fx.sound(body, Sound.BLOCK_BELL_USE, 1.4f, 1.0f);
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.6f);
        }

        /** The landing telegraph: a ring at the blast radius and a reticle closing on the impact point. */
        private void reticle(long tick) {
            Location ground = impactPoint().add(0, 0.12, 0);
            double radius = Math.max(1, params().num("ult_radius"));
            long span = DRIVE_END - TURN_START;
            double t = Math.min(1, (double) (tick - TURN_START) / span);
            Vfx.ring(ctx(), ground, radius * (1 - t) + 0.5 * t, (int) Math.max(10, radius * 5 * (1 - t)), tick * 0.3,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.3f), true);
            if (tick % 2 == 0) {
                Vfx.ring(ctx(), ground, radius, (int) Math.max(16, radius * 6), tick * 0.05, Particle.DUST,
                        SHADOW_BIG, true);
            }
        }

        /** The dash: streaks from where the owner was to the grab, dust skidding along the floor. */
        private void dashFx(Location from, Location to) {
            double height = owner.getHeight();
            Location fromBody = from.clone().add(0, height * 0.55, 0);
            Location toBody = to.clone().add(0, height * 0.55, 0);
            Vfx.cloud(ctx(), from.clone().add(0, 0.2, 0), Particle.CLOUD, 8, 0.3, 0.04, null, true);
            if (from.getWorld().equals(to.getWorld()) && from.distanceSquared(to) > 0.25) {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int streaks = Vfx.n(ctx(), 12);
                for (int i = 0; i < streaks; i++) {
                    Vector jitter = new Vector(random.nextDouble(-0.35, 0.35), random.nextDouble(-0.7, 0.7),
                            random.nextDouble(-0.35, 0.35));
                    Vfx.trail(fromBody.clone().add(jitter), toBody.clone().add(jitter),
                            i % 3 == 0 ? PALETTE.core() : i % 3 == 1 ? PALETTE.body() : PALETTE.edge(),
                            3 + random.nextInt(4), true);
                }
                Vfx.line(ctx(), from.clone().add(0, 0.1, 0), to.clone().add(0, 0.1, 0), 1.2, Particle.CLOUD, null,
                        false);
                BlockData floor = floorAt(to);
                world.spawnParticle(Particle.BLOCK, to.clone().add(0, 0.1, 0), Vfx.n(ctx(), 10), 0.3, 0.05, 0.3, 0.1,
                        floor, true);
            }
            Fx.sound(from, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(from, Sound.ENTITY_RAVAGER_STEP, 1.0f, 1.2f + Fx.jitter(0.1f));
        }
    }

    /** The ground gives way: flash, rings racing out to the blast radius, a spray of floor and a giant star. */
    private void impactFx(Location point, double radius) {
        Player owner = player();
        World world = point.getWorld();
        Location ground = point.clone().add(0, 0.1, 0);
        Location body = point.clone().add(0, 0.8, 0);
        BlockData floor = floorAt(point);
        double reach = Math.max(1, radius);
        world.spawnParticle(Particle.FLASH, body, 1, 0, 0, 0, 0, PALETTE.core(), true);
        Vfx.dot(body, Particle.EXPLOSION, null, true);
        Vfx.shockwave(ctx(), owner, ground, 0.5, reach, 6, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.6f), true);
        Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.15, 0), 0.3, reach * 0.8, 8, Particle.CLOUD, null, true);
        Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.05, 0), 0.2, reach * 1.15, 9, Particle.DUST, SHADOW_BIG,
                true);
        world.spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 60), reach * 0.35, 0.3, reach * 0.35, 0.4, floor, true);
        world.spawnParticle(Particle.DUST_PLUME, ground, Vfx.n(ctx(), 14), 0.8, 0.1, 0.8, 0.05, null, true);
        Vfx.cloud(ctx(), body, Particle.CRIT, 30, 0.4, 0.8, null, true);
        impactStar(body.clone().add(0, 0.4, 0), 2.4f, ULT_STAR_TICKS, true);
        Fx.sound(point, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.3f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(point, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(point, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.8f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(point, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.5f);
        Fx.sound(point, Sound.ENTITY_PLAYER_BIG_FALL, 1.2f, 0.6f);
        afterglow(point, reach * 0.6, floor);
    }

    /**
     * The crater: a dark pit, cracks running out over the floor, plates of floor tipped up round the rim and chunks of
     * debris thrown out (all display entities, sinking back after {@code crater_duration}), plus a few rubble blocks.
     */
    private void crater(Location point) {
        Player owner = player();
        long life = Math.max(20, params().ticks("crater_duration"));
        Location center = flat(point);
        center.add(0, 0.01, 0);
        BlockData floor = floorAt(point);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        float pitYaw = random.nextFloat() * 90;
        BlockDisplay pit = Displays.block(ctx(), owner, center, PIT,
                Displays.transform(0, 0.005f, 0, 0.3f, 0.02f, 0.3f, pitYaw, 0, 0), life + SINK_TICKS + 2);
        Displays.animateNextTick(ctx(), owner, pit, Displays.transform(0, 0.005f, 0, 1.5f, 0.02f, 1.5f, pitYaw, 0, 0),
                3);
        sinkLater(pit, life);

        double crackBase = random.nextDouble(TAU);
        for (int i = 0; i < CRACKS; i++) {
            Vector dir = unit(crackBase + i * TAU / CRACKS + random.nextDouble(-0.3, 0.3));
            float yaw = yawOf(dir);
            float length = (float) random.nextDouble(1.4, 2.6);
            BlockDisplay crack = Displays.block(ctx(), owner, center, CRACK, crackPose(dir, 0.05f, yaw),
                    life + SINK_TICKS + 2);
            Displays.animateNextTick(ctx(), owner, crack, crackPose(dir, length, yaw), 4);
            sinkLater(crack, life);
        }

        double rimBase = random.nextDouble(TAU);
        for (int i = 0; i < RIM_PLATES; i++) {
            Vector dir = unit(rimBase + i * TAU / RIM_PLATES + random.nextDouble(-0.2, 0.2));
            float yaw = yawOf(dir);
            float r = (float) random.nextDouble(1.15, 1.5);
            float tilt = (float) random.nextDouble(22, 40);
            float width = (float) random.nextDouble(0.75, 1.0);
            float depth = (float) random.nextDouble(0.5, 0.7);
            float dx = (float) dir.getX();
            float dz = (float) dir.getZ();
            BlockDisplay plate = Displays.block(ctx(), owner, center, floor,
                    Displays.transform(dx * 0.4f, -0.1f, dz * 0.4f, width * 0.4f, 0.18f, depth * 0.4f, yaw, 0, 0),
                    life + SINK_TICKS + 2);
            plate.setBrightness(null); // lit like the floor it was torn from
            Displays.animateNextTick(ctx(), owner, plate,
                    Displays.transform(dx * r, 0.02f, dz * r, width, 0.2f, depth, yaw, -tilt, 0), 3);
            ctx().runLater(owner, life, () -> {
                if (plate.isValid()) {
                    Displays.animate(plate, Displays.transform(dx * r, -0.35f, dz * r, width * 0.8f, 0.2f,
                            depth * 0.8f, yaw, -tilt * 0.4f, 0), SINK_TICKS);
                    ctx().runLater(owner, SINK_TICKS, plate::remove);
                }
            });
        }

        // The rubble first: the debris must see those blocks to stay out of them.
        rubble(point, life);
        for (int i = 0; i < DEBRIS; i++) {
            debris(center, floor, unit(random.nextDouble(TAU)));
        }
    }

    /**
     * A chunk of floor thrown out of the crater in an arc, tumbling, then crumbling away where it lands. It is lit
     * like the floor (no brightness of its own), so it must never fly into or land inside a block, where it would
     * render black: a throw that would hit a wall or a step falls shorter, and with no room at all there is no chunk.
     */
    private void debris(Location center, BlockData floor, Vector dir) {
        Player owner = player();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        float size = (float) random.nextDouble(0.2, 0.32);
        double reach = random.nextDouble(1.8, 3.2);
        double rise = random.nextDouble(1.2, 2.0);
        Location start = center.clone().add(0, 0.4, 0);
        Location peak = null;
        Location land = null;
        for (double share : DEBRIS_REACH_SHARES) {
            Location top = center.clone().add(dir.clone().multiply(reach * share * 0.5)).add(0, rise, 0);
            Location end = center.clone().add(dir.clone().multiply(reach * share)).add(0, size / 2, 0);
            if (isOpen(top) && isOpen(end) && Spots.clearPath(start, top) && Spots.clearPath(top, end)) {
                peak = top;
                land = end;
                break;
            }
        }
        if (peak == null || !isOpen(start)) {
            return;
        }
        BlockDisplay chunk = Displays.block(ctx(), owner, start, floor,
                Displays.transform(0, 0, 0, size, size, size, random.nextFloat() * 90, random.nextFloat() * 90, 0),
                DEBRIS_TICKS);
        chunk.setBrightness(null);
        chunk.setTeleportDuration(5);
        Transformation tumbled = Displays.transform(0, 0, 0, size, size, size, random.nextFloat() * 360,
                random.nextFloat() * 360, random.nextFloat() * 360);
        Location top = peak;
        Location end = land;
        ctx().runLater(owner, 1, () -> {
            if (chunk.isValid()) {
                Displays.moveTo(chunk, top);
                Displays.animate(chunk, tumbled, 10);
            }
        });
        ctx().runLater(owner, 6, () -> {
            if (chunk.isValid()) {
                Displays.moveTo(chunk, end);
            }
        });
        ctx().runLater(owner, DEBRIS_TICKS - 8, () -> {
            if (chunk.isValid()) {
                chunk.getWorld().spawnParticle(Particle.BLOCK, chunk.getLocation(), Vfx.n(ctx(), 4), 0.1, 0.05, 0.1, 0,
                        floor);
                Displays.shrinkAway(ctx(), owner, chunk, 6);
            }
        });
    }

    /**
     * A few rubble blocks thrown up round the crater's rim for {@code ticks}, only where it is safe: on solid floor, with
     * room above (a bump, never a wall), inside the arena and clear of every living body.
     */
    private void rubble(Location point, long ticks) {
        World world = point.getWorld();
        int floorY = (int) Math.floor(point.getY() - 0.01);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double base = random.nextDouble(TAU);
        int placed = 0;
        for (int i = 0; i < RUBBLE_TRIES && placed < RUBBLE_BLOCKS; i++) {
            Vector dir = unit(base + i * TAU / RUBBLE_TRIES + random.nextDouble(-0.15, 0.15));
            double r = RUBBLE_RADIUS + random.nextDouble(-0.3, 0.5);
            int x = (int) Math.floor(point.getX() + dir.getX() * r);
            int z = (int) Math.floor(point.getZ() + dir.getZ() * r);
            if (floorY < world.getMinHeight() || floorY + 2 >= world.getMaxHeight()) {
                return;
            }
            Block floor = world.getBlockAt(x, floorY, z);
            Block spot = floor.getRelative(BlockFace.UP);
            Block above = spot.getRelative(BlockFace.UP);
            if (!floor.getType().isSolid() || !floor.getType().isOccluding() || spot.isLiquid()
                    || !(spot.isEmpty() || spot.isReplaceable()) || !above.isPassable() || above.isLiquid()
                    || !ctx().isInsideArena(spot.getLocation().add(0.5, 0, 0.5))) {
                continue;
            }
            BoundingBox box = BoundingBox.of(spot).expand(0.3);
            if (!world.getNearbyEntities(box, entity -> entity instanceof LivingEntity).isEmpty()) {
                continue;
            }
            BlockData data = RUBBLE[placed % RUBBLE.length];
            if (ctx().placeTempBlock(spot, data, ticks)) {
                placed++;
                Location at = spot.getLocation().add(0.5, 0.5, 0.5);
                world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 6), 0.3, 0.3, 0.3, 0.1, data);
            }
        }
    }

    /** True if no block collides around {@code at}: a display there takes the light of open air. */
    private static boolean isOpen(Location at) {
        return !at.getWorld().hasCollisionsIn(BoundingBox.of(at, OPEN_PROBE, OPEN_PROBE, OPEN_PROBE));
    }

    /** A display of the crater that shrinks into the floor once the crater closes. */
    private void sinkLater(BlockDisplay display, long life) {
        Player owner = player();
        ctx().runLater(owner, life, () -> {
            if (display.isValid()) {
                Displays.shrinkAway(ctx(), owner, display, SINK_TICKS);
            }
        });
    }

    /** A thin dark crack lying on the floor, running {@code length} blocks out from the pit's edge. */
    private static Transformation crackPose(Vector dir, float length, float yaw) {
        float along = 0.5f + length / 2;
        return Displays.transform((float) dir.getX() * along, 0.012f, (float) dir.getZ() * along, CRACK_WIDTH, 0.015f,
                length, yaw, 0, 0);
    }

    /** Fading dust and ash settling over an impact. */
    private void afterglow(Location point, double radius, BlockData floor) {
        Location center = point.clone();
        Loop.repeat(ctx(), player(), 6, 2, AFTERGLOW_RUNS, run -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double fade = 1 - (double) run / AFTERGLOW_RUNS;
            int motes = Vfx.n(ctx(), (int) Math.round(4 * fade) + 1);
            for (int i = 0; i < motes; i++) {
                Location at = Vfx.scatter(center, radius).add(0, random.nextDouble(0.1, 1.2), 0);
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, FADE, false);
                if (i % 2 == 0) {
                    Vfx.dot(at, Particle.WHITE_ASH, null, false);
                }
            }
            if (run % 3 == 0) {
                center.getWorld().spawnParticle(Particle.BLOCK, center.clone().add(0, 0.2, 0), Vfx.n(ctx(), 4),
                        radius * 0.5, 0.1, radius * 0.5, 0, floor);
            }
            return true;
        });
    }

    /** The ring announcer's banner over the impact: "파일드라이버!!", or "K.O.!" when it killed, shimmering. */
    private void announcer(Location point, boolean knockedOut) {
        Player owner = player();
        String word = knockedOut ? "K.O.!" : "파일드라이버!!";
        float size = knockedOut ? 2.6f : 1.5f;
        TextDisplay banner = Displays.text(ctx(), owner, flat(point.clone().add(0, ANNOUNCER_HEIGHT, 0)),
                Text.mm(bannerText(word, knockedOut, -1)), 0.2f, ANNOUNCER_TICKS);
        banner.setViewRange(2.0f);
        float pop = size * 1.35f;
        Displays.animateNextTick(ctx(), owner, banner, Displays.centered(0, 0, 0, pop, pop, pop, 0, 0, -6), 3);
        ctx().runLater(owner, 4, () -> Displays.animate(banner, Displays.centered(0, 0.1f, 0, size, size, size, 0, 0,
                0), 4));
        Loop.repeat(ctx(), owner, 2, 2, ANNOUNCER_TICKS / 2 - 4, run -> {
            if (!banner.isValid()) {
                return false;
            }
            banner.text(Text.mm(bannerText(word, knockedOut, (run % 10) / 5.0 - 1)));
            return true;
        });
        ctx().runLater(owner, ANNOUNCER_TICKS - 6, () -> {
            if (banner.isValid()) {
                Displays.animate(banner, Displays.centered(0, 0.6f, 0, size * 0.6f, size * 0.6f, size * 0.6f, 0, 0, 0),
                        4);
                ctx().runLater(owner, 4, () -> {
                    if (banner.isValid()) {
                        Displays.shrinkAway(ctx(), owner, banner, 2);
                    }
                });
            }
        });
    }

    /** The banner text with its gradient shifted by {@code phase} (-1..1), so it shimmers. */
    private static String bannerText(String word, boolean knockedOut, double phase) {
        String colors = knockedOut ? GOLD_TAG + ":" + COLOR_TAG + ":" + GOLD_TAG
                : CREAM_TAG + ":" + COLOR_TAG + ":" + CREAM_TAG;
        return "<b><gradient:" + colors + ":" + String.format(Locale.ROOT, "%.2f", Math.clamp(phase, -1.0, 1.0)) + ">"
                + word + "</gradient></b>";
    }

    /** K.O.: three bells, a shower of sparks and the call to everyone watching. */
    private void knockout(Location point, LivingEntity victim) {
        Player owner = player();
        Location bell = point.clone().add(0, ANNOUNCER_HEIGHT, 0);
        for (long delay : BELL_DELAYS) {
            ctx().runLater(owner, delay, () -> Fx.sound(bell, Sound.BLOCK_BELL_USE, 1.6f, 1.0f));
        }
        Fx.sound(bell, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.2f, 1.0f);
        Vfx.cloud(ctx(), bell, Particle.FIREWORK, 30, 0.6, 0.15, null, true);
        Vfx.cloud(ctx(), bell, Particle.DUST_COLOR_TRANSITION, 20, 0.9, 0, PALETTE.glow(1.3f), true);
        if (victim instanceof Player down) {
            ctx().announce(Text.mm("<" + GOLD_TAG + "><b>K.O.!</b></" + GOLD_TAG + "> <white><owner></white><gray>의 "
                            + "</gray><" + COLOR_TAG + ">파일드라이버</" + COLOR_TAG + "><gray>에 </gray><white><victim>"
                            + "</white><gray>이(가) 매트에 꽂혔습니다!</gray>",
                    Placeholder.unparsed("owner", owner.getName()), Placeholder.unparsed("victim", down.getName())));
        }
    }

    // ------------------------------------------------------------------ shared mechanics

    /**
     * Where a held body is. Any entity but a player is moved by the server itself, so its own position is exact. A
     * player moves on its own client, which the server hears from only a round trip later: steering by that lagging
     * position is a delayed correction that swings the body off its path and overshoots by blocks once the ping
     * reaches a few ticks. A player is therefore tracked by dead reckoning (like the puppeteer's and the alien's
     * holds): where it was grabbed plus every velocity sent to it since, each step cut short where a block would stop
     * the client.
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
        /** Halvings that find where a step meets a block: to a few thousandths of a block. */
        private static final int SWEEP_HALVINGS = 10;
        private final LivingEntity body;
        private final Vector at;
        private final ArrayDeque<Vector> trail = new ArrayDeque<>();
        private Vector sent = new Vector();

        Reckoning(LivingEntity body) {
            this.body = body;
            this.at = body.getLocation().toVector();
            trail.addLast(at.clone());
        }

        /** The velocity sent last (zero before the first). */
        Vector lastSent() {
            return sent.clone();
        }

        /** Where the body is: for a player, where it will be once it has followed every velocity sent so far. */
        Vector position() {
            if (!(body instanceof Player)) {
                at.copy(body.getLocation().toVector());
            }
            return at.clone();
        }

        Location location() {
            return position().toLocation(body.getWorld());
        }

        /** Steers the body onto {@code feet} this tick (as fast as allowed); fall distance never builds up held. */
        void steer(Location feet) {
            send(Motion.clamp(feet.toVector().subtract(position()), MAX_SPEED));
            body.setFallDistance(0);
        }

        /** Sends {@code velocity} to the body; a player's reckoning moves with it, up to the first block in the way. */
        void send(Vector velocity) {
            body.setVelocity(velocity);
            sent = velocity.clone();
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
         * reckoned to be lately, a round trip late, so it is compared with the nearest of those; what is left within
         * {@code trusted} blocks is real drift (a knockback, its own air control), which nudges the reckoning slowly
         * towards it.
         */
        double observe(double trusted) {
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
            if (best < trusted) {
                at.add(Motion.clamp(seen.subtract(nearest).multiply(DRIFT_GAIN), MAX_NUDGE));
            }
            return best;
        }

        /** Whether the body stands on something where it is (a player: where it is reckoned to be). */
        boolean grounded() {
            if (!(body instanceof Player)) {
                return Motion.isGrounded(body);
            }
            double half = body.getWidth() / 2;
            return body.getWorld().hasCollisionsIn(new BoundingBox(at.getX() - half + 0.05, at.getY() - 0.15,
                    at.getZ() - half + 0.05, at.getX() + half - 0.05, at.getY() - 0.01, at.getZ() + half - 0.05));
        }

        /** Whether the body is in water where it is (a player: where it is reckoned to be). */
        boolean inWater() {
            if (!(body instanceof Player)) {
                return body.isInWater();
            }
            Block feet = body.getWorld().getBlockAt(at.getBlockX(), at.getBlockY(), at.getBlockZ());
            return feet.getType() == Material.WATER
                    || feet.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
        }

        /** The step a client takes with {@code velocity}: like vanilla, axis by axis (y first), up to any block. */
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

        /** How much of a move along one axis is free of blocks: the moved length along that axis. */
        private double sweep(BoundingBox box, double x, double y, double z) {
            World world = body.getWorld();
            if (!world.hasCollisionsIn(box.clone().shift(x, y, z))) {
                return x + y + z;
            }
            double free = 0;
            double blocked = 1;
            for (int i = 0; i < SWEEP_HALVINGS; i++) {
                double half = (free + blocked) / 2;
                if (world.hasCollisionsIn(box.clone().shift(x * half, y * half, z * half))) {
                    blocked = half;
                } else {
                    free = half;
                }
            }
            return (x + y + z) * free;
        }
    }

    /** Slow falling as a safety net for a held body (removed with {@link #clearSafety} at the impact). */
    private static void holdSafely(LivingEntity held, long holdTicks) {
        int ticks = (int) Math.min(Integer.MAX_VALUE, holdTicks + SAFETY_FALL_TICKS);
        held.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, ticks, 0, true, false, false));
    }

    /** Removes the safety-net slow falling of {@link #holdSafely}, never a slow falling the body got elsewhere. */
    private static void clearSafety(LivingEntity held) {
        PotionEffect effect = held.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (effect != null && effect.isAmbient() && !effect.hasParticles() && !effect.hasIcon()
                && effect.getAmplifier() == 0) {
            held.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
    }

    /**
     * Keeps the owner's fall distance at zero until it has stood on the ground for a moment (at most
     * {@link #LANDING_GRACE_TICKS}). A loop rather than a {@link LandingGuard}, so it also protects a Gambler that
     * borrowed the ultimate.
     */
    private void guardLanding(Player owner) {
        int[] grounded = {0};
        Loop.repeat(ctx(), owner, 0, 1, LANDING_GRACE_TICKS, run -> {
            owner.setFallDistance(0);
            grounded[0] = Motion.isGrounded(owner) || owner.isInWater() ? grounded[0] + 1 : 0;
            return run < PILEDRIVER_TICKS || grounded[0] < LandingGuard.SETTLE_TICKS;
        });
    }

    /**
     * Ability damage to every enemy within {@code radius} of {@code center} but {@code spared}, thrown away from it
     * (knockback, so resistance applies). Returns how many were hit.
     */
    private int splash(Location center, double radius, double damage, LivingEntity spared, double push, double lift,
                       boolean force) {
        if (radius <= 0) {
            return 0;
        }
        Player owner = player();
        int hits = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            if (enemy.equals(spared)) {
                continue;
            }
            Combat.hit(ctx(), owner, enemy, damage);
            hits++;
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(center.toVector()));
            if (away.lengthSquared() < 1.0E-6) {
                away = unit(ThreadLocalRandom.current().nextDouble(TAU));
            }
            Motion.push(enemy, away.multiply(push).setY(lift), MAX_SPEED);
            Vfx.burst(ctx(), Fx.body(enemy), PALETTE, 0.5, Particle.CRIT, force);
        }
        return hits;
    }

    /** Roots {@code target} (no walking, no jumping) for {@code ticks}. Returns the modifier keys. */
    private NamespacedKey[] root(LivingEntity target, long ticks) {
        return new NamespacedKey[] {
                ctx().addModifier(player(), target, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, ticks),
                ctx().addModifier(player(), target, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, ticks)};
    }

    private void unroot(LivingEntity target, NamespacedKey[] keys) {
        for (NamespacedKey key : keys) {
            ctx().removeModifier(target, key);
        }
    }

    private boolean isDown(LivingEntity target) {
        return target.isDead() || !target.isValid() || target instanceof Player victim && !ctx().isAlive(victim);
    }

    // ------------------------------------------------------------------ shared visuals

    /** The hands closing on the enemy: motes from both fists to its waist and a ring cinching round it. */
    private void gripFx(Player owner, LivingEntity target, boolean force) {
        Location waist = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        Vector toward = facingTo(owner, target);
        Vector side = new Vector(-toward.getZ(), 0, toward.getX());
        for (Location fist : fists(owner)) {
            for (int hand = -1; hand <= 1; hand += 2) {
                Vfx.trail(fist, waist.clone().add(side.clone().multiply(hand * target.getWidth() * 0.5)),
                        hand < 0 ? PALETTE.core() : PALETTE.body(), 3, force);
            }
        }
        Location center = waist.clone();
        double width = target.getWidth() * 0.5;
        Loop.repeat(ctx(), owner, 0, 1, 3, run -> {
            Vfx.ring(ctx(), center, width + 0.6 - 0.2 * run, 12, run * 0.7, Particle.DUST_COLOR_TRANSITION, GLOW,
                    force);
            return true;
        });
        Vfx.cloud(ctx(), center, Particle.CRIT, 8, 0.25, 0.25, null, force);
    }

    /**
     * A comic impact star facing the viewer: four thin glowing bars crossing at {@code at} (eight points), popping out
     * and shrinking away. {@code outline} makes it pop through walls (the ultimate).
     */
    private void impactStar(Location at, float size, long life, boolean outline) {
        Player owner = player();
        Location anchor = flat(at);
        float spin = ThreadLocalRandom.current().nextFloat() * 45f;
        for (int i = 0; i < STAR_RAYS; i++) {
            boolean main = i % 2 == 0;
            float roll = spin + i * (180f / STAR_RAYS);
            float length = size * (main ? 1f : 0.62f);
            float width = size * (main ? 0.075f : 0.06f);
            BlockDisplay ray = Displays.block(ctx(), owner, anchor, main ? STAR_LONG : STAR_SHORT,
                    rayPose(length * 0.2f, width, roll), life);
            ray.setBillboard(Display.Billboard.CENTER);
            if (outline) {
                Displays.outline(ray, PALETTE.body());
            }
            Displays.animateNextTick(ctx(), owner, ray, rayPose(length, width, roll), 2);
            ctx().runLater(owner, 3, () -> {
                if (ray.isValid()) {
                    Displays.shrinkAway(ctx(), owner, ray, (int) Math.max(1, life - 4));
                }
            });
        }
    }

    private static Transformation rayPose(float length, float width, float roll) {
        return Displays.transform(0, 0, 0, width, length, 0.02f, 0, 0, roll);
    }

    /** An onomatopoeia popping up with an overshoot and a slight tilt, then shrinking away. */
    private void popText(Location at, String text, float scale, long life) {
        Player owner = player();
        float roll = (float) ThreadLocalRandom.current().nextDouble(-12, 12);
        TextDisplay label = Displays.text(ctx(), owner, flat(at), Text.mm(text), scale * 0.3f, life);
        float pop = scale * 1.3f;
        Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0.15f, 0, pop, pop, pop, 0, 0, roll), 2);
        ctx().runLater(owner, 3, () -> Displays.animate(label, Displays.centered(0, 0.25f, 0, scale, scale, scale, 0,
                0, roll), 3));
        ctx().runLater(owner, Math.max(4, life - 5), () -> {
            if (label.isValid()) {
                Displays.shrinkAway(ctx(), owner, label, 4);
            }
        });
    }

    /** A tilted ring wobbling over a dazed head: small and navy for a stagger, vermilion for a stun. */
    private void daze(LivingEntity victim, long ticks, boolean stun) {
        Loop.repeat(ctx(), player(), 2, 2, Math.max(1, ticks / 2), run -> {
            if (!victim.isValid() || victim.isDead()) {
                return false;
            }
            Location head = victim.getLocation().add(0, victim.getHeight() + 0.25, 0);
            double angle = run * 0.9;
            Vector normal = new Vector(Math.cos(angle) * 0.5, 1, Math.sin(angle) * 0.5);
            if (stun) {
                ringAround(head, normal, 0.48, 10, run * 0.8, Particle.DUST_COLOR_TRANSITION, SPARK, false);
                if (run % 2 == 0) {
                    Vfx.dot(head.clone().add(Math.cos(run * 1.7) * 0.48, 0, Math.sin(run * 1.7) * 0.48),
                            Particle.WAX_ON, null, false);
                }
            } else {
                ringAround(head, normal, 0.38, 8, run * 0.8, Particle.DUST, SHADOW, false);
            }
            return true;
        });
    }

    /** A ring around {@code normal} (its axis) at {@code center}. */
    private <T> void ringAround(Location center, Vector normal, double radius, int points, double phase,
                                Particle particle, @Nullable T data, boolean force) {
        Vector n = normal.lengthSquared() < 1.0E-6 ? new Vector(0, 1, 0) : normal.clone().normalize();
        Vector helper = Math.abs(n.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = n.clone().crossProduct(helper).normalize();
        Vector v = n.clone().crossProduct(u).normalize();
        int count = Vfx.n(ctx(), points);
        for (int i = 0; i < count; i++) {
            double angle = phase + i * TAU / count;
            Location at = center.clone().add(u.clone().multiply(Math.cos(angle) * radius))
                    .add(v.clone().multiply(Math.sin(angle) * radius));
            Vfx.dot(at, particle, data, force);
        }
    }

    /** The owner's two fists, where the spirit smoulders. */
    private static Location[] fists(Player owner) {
        Location feet = owner.getLocation();
        double scale = Body.scaleOf(owner);
        double yaw = Math.toRadians(owner.getBodyYaw());
        Vector forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        Location[] fists = new Location[2];
        for (int i = 0; i < fists.length; i++) {
            fists[i] = feet.clone().add(side.clone().multiply((i == 0 ? -1 : 1) * FIST_SIDE * scale))
                    .add(forward.clone().multiply(FIST_FORWARD * scale)).add(0, FIST_HEIGHT * scale, 0);
        }
        return fists;
    }

    // ------------------------------------------------------------------ helpers

    /** The horizontal direction from the owner to {@code target}, or the owner's facing when they overlap. */
    private static Vector facingTo(Player owner, LivingEntity target) {
        Vector to = Motion.horizontal(target.getLocation().toVector().subtract(owner.getLocation().toVector()));
        return to.lengthSquared() < 1.0E-6 ? Motion.horizontalFacing(owner.getLocation()) : to;
    }

    /**
     * The first ground (block or fluid surface) at most {@code depth} below {@code from}, as a location with
     * {@code from}'s x/z; null over the void or a deeper drop.
     */
    private static @Nullable Location groundBelow(Location from, double depth) {
        World world = from.getWorld();
        Location start = from.clone().add(0, 0.05, 0);
        if (start.getY() <= world.getMinHeight()) {
            return null;
        }
        RayTraceResult hit = world.rayTraceBlocks(start, new Vector(0, -1, 0), depth + 0.05, FluidCollisionMode.ALWAYS,
                true);
        if (hit == null) {
            return null;
        }
        Location ground = from.clone();
        ground.setY(hit.getHitPosition().getY());
        return ground;
    }

    /** The floor block under {@code feet} for particles and debris (cobblestone over anything but a solid cube). */
    private static BlockData floorAt(Location feet) {
        Block block = feet.clone().subtract(0, 0.2, 0).getBlock();
        Material type = block.getType();
        return type.isSolid() && type.isOccluding() ? block.getBlockData() : FALLBACK_FLOOR;
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** Smoothstep: eases in and out over 0..1. */
    private static double smooth(double t) {
        double s = Math.clamp(t, 0.0, 1.0);
        return s * s * (3 - 2 * s);
    }

    private static Location lerp(Location from, Location to, double t) {
        return from.clone().add(to.toVector().subtract(from.toVector()).multiply(t));
    }

    /** A point on the quadratic Bezier curve from {@code from} over {@code control} to {@code to}. */
    private static Location bezier(Location from, Location control, Location to, double t) {
        double a = (1 - t) * (1 - t);
        double b = 2 * (1 - t) * t;
        double c = t * t;
        Vector point = from.toVector().multiply(a).add(control.toVector().multiply(b)).add(to.toVector().multiply(c));
        return point.toLocation(from.getWorld());
    }

    private static Vector unit(double angle) {
        return new Vector(Math.cos(angle), 0, Math.sin(angle));
    }

    /** The Minecraft yaw (degrees) of a horizontal direction. */
    private static float yawOf(Vector direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    /** A copy without yaw and pitch, so display entities are not turned by the entity rotation as well. */
    private static Location flat(Location location) {
        Location copy = location.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }
}
