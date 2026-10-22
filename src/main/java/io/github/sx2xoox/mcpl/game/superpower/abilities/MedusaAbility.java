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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 메두사 — the gorgon. The snakes in her hair bite whoever strikes her in melee, her gaze turns to stone every enemy
 * that is looking at her when her eyes flash (and only those: looking away saves you), and as the ultimate a huge
 * gorgon eye opens over her head and slowly turns to stone everyone who keeps staring at her.
 *
 * <p>Look (stone grey, snake green, gold): five green snakes writhe out of her head (hidden from her own view) and
 * rear up while she gathers her gaze; her eyes light up gold, a golden cone is traced on the ground, and the gaze is a
 * wave of golden rings rolling through the cone. The petrified are encased in a stone statue of themselves that grows
 * from the feet up, sheds grit, shivers and cracks before it bursts apart. The gorgon eye is a billboarded lens (a
 * green lid, a white eye, a gold iris and a black slit) that opens over Medusa and stares at every viewer; whoever
 * meets its gaze is visibly turned to stone from the feet up, and a golden thread of light runs from the eye into them.
 */
public final class MedusaAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("medusa", "메두사", Material.CHISELED_STONE_BRICKS)
            .color(TextColor.color(0x9BCB5B))
            .difficulty(2)
            .passive("뱀머리",
                    "나를 근접 공격한 적을 머리카락의 뱀이 물어 {snake_damage} 피해와 {snake_poison}초간 독을 겁니다"
                            + "(같은 적은 {snake_cooldown}초마다). 독에 걸린 적은 석화가 {venom_petrify}초 더 오래갑니다.")
            .active("석화의 시선",
                    "{gaze_windup}초간 눈을 빛낸 뒤 앞쪽 {gaze_range}블록 부채꼴({gaze_angle}°)을 노려봅니다. 그 순간 나를 "
                            + "바라보고 있던 적은 {petrify_duration}초간 돌로 굳어 움직이거나 근접 공격하거나 능력을 쓸 수 없고, "
                            + "석화가 풀릴 때 {petrify_damage} 피해를 입습니다. 고개를 돌린 적은 다리만 굳어 "
                            + "{glance_slow}초간 구속 II에 걸립니다.",
                    11)
            .ultimate("고르곤의 눈",
                    "{ult_duration}초간 머리 위에 고르곤의 눈을 뜹니다. 반경 {ult_radius}블록 안에서 나를 바라보는 적은 "
                            + "발끝부터 돌로 굳어 가고, 합쳐서 {stare_time}초 동안 바라본 적은 {ult_petrify}초간 석화되며 "
                            + "풀릴 때 {ult_damage} 피해를 입습니다(적마다 한 번). 시선을 돌리거나 눈에서 멀어지면 굳어 가던 "
                            + "돌이 서서히 풀립니다.")
            .param("snake_damage", 1)
            .param("snake_poison", 3)
            .param("snake_cooldown", 3)
            .param("venom_petrify", 0.5)
            .param("gaze_windup", 0.4)
            .param("gaze_range", 10)
            .param("gaze_angle", 70)
            .param("petrify_duration", 1.5)
            .param("petrify_damage", 3)
            .param("glance_slow", 1)
            .param("ult_duration", 6)
            .param("ult_radius", 18)
            .param("stare_time", 1)
            .param("ult_petrify", 2)
            .param("ult_damage", 5)
            .build();

    private static final String GOLD_TAG = "#E8C547";
    private static final String SNAKE_TAG = "#9BCB5B";
    private static final String STONE_TAG = "#B9B7AE";

    // ------------------------------------------------------------------ look
    /** Pale gold glow, snake green, the dark grey of old stone. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF1B0, 0x7FB347, 0x45463F);
    private static final Color GOLD = Color.fromRGB(0xE8C547);
    private static final Color PALE_GOLD = Color.fromRGB(0xFFF1B0);
    private static final Particle.DustTransition GOLD_GLOW = new Particle.DustTransition(PALE_GOLD, GOLD, 1.0f);
    private static final Particle.DustTransition GOLD_TO_STONE = new Particle.DustTransition(GOLD,
            Color.fromRGB(0x9C9A92), 1.2f);
    private static final Particle.DustOptions STONE_DUST = new Particle.DustOptions(Color.fromRGB(0x9C9A92), 1.1f);
    private static final Particle.DustOptions DARK_STONE = new Particle.DustOptions(Color.fromRGB(0x5E5D57), 0.9f);
    private static final Particle.DustOptions VENOM = new Particle.DustOptions(Color.fromRGB(0x7FB347), 0.8f);

    /** Block types of the visuals; block data is created on use (it needs a running server). */
    private static final Material SNAKE_DARK = Material.GREEN_CONCRETE;
    private static final Material SNAKE_LIGHT = Material.LIME_CONCRETE;
    private static final Material SNAKE_HEAD = Material.MOSS_BLOCK;
    private static final Material EYE_GLOW = Material.YELLOW_CONCRETE;
    private static final Material LID = Material.GREEN_CONCRETE;
    private static final Material SCLERA = Material.WHITE_CONCRETE;
    private static final Material IRIS = Material.YELLOW_CONCRETE;
    private static final Material PUPIL = Material.BLACK_CONCRETE;
    private static final Material STONE = Material.STONE;

    /** Most display entities one Medusa keeps alive at once. */
    private static final int MAX_DISPLAYS = 24;

    // passive: snake hair
    private static final int SNAKES = 5;
    private static final float SNAKE_LENGTH = 0.36f;
    private static final float SNAKE_WIDTH = 0.075f;
    /** Snakes sprout from a ring this far around the middle of the head, behind the face. */
    private static final float HAIR_RADIUS = 0.2f;
    private static final float HAIR_LIFT = 0.18f;
    private static final long HISS_PERIOD = 70;
    private static final long WISP_PERIOD = 12;
    private static final int BITE_REACH_TICKS = 2;
    private static final int BITE_HOLD_TICKS = 2;
    private static final int BITE_RETRACT_TICKS = 3;
    private static final double BITE_MAX_DISTANCE = 6;

    // active: petrifying gaze
    /** An enemy whose view is within this angle of Medusa's eyes is looking at her. */
    private static final double LOOK_COS = Math.cos(Math.toRadians(55));
    /** Enemies this close are in the gaze whatever the angle. */
    private static final double POINT_BLANK = 1.2;
    private static final int GAZE_WAVES = 5;
    private static final long PETRIFY_GROW_TICKS = 5;
    /** The statue starts to shiver this long before it bursts. */
    private static final long SHIVER_TICKS = 8;
    private static final double REST_FALL = -0.08 * 0.98;

    // ultimate: gorgon eye
    private static final double EYE_ABOVE = 1.35;
    private static final float EYE_WIDTH = 1.7f;
    private static final float EYE_HEIGHT = 0.95f;
    private static final int EYE_OPEN_TICKS = 8;
    /** Viewers whose look is within this angle of the gorgon (or her eye) meet its gaze. */
    private static final double STARE_COS = Math.cos(Math.toRadians(40));
    /** Looking away, the stone thaws at this share of the speed it crept up. */
    private static final double THAW_RATE = 0.5;
    private static final long WARN_PERIOD = 10;

    /** One part of a stone statue: its place on a 1.8 block tall body (+Z front, +X left) and when it forms. */
    private record Part(float x, float y, float z, float sx, float sy, float sz, float from, float to,
                        Material block) {
    }

    private static final Part[] STATUE = {
            new Part(0, 0.39f, 0, 0.58f, 0.78f, 0.34f, 0.0f, 0.35f, Material.COBBLESTONE),
            new Part(0, 1.16f, 0, 0.6f, 0.78f, 0.36f, 0.3f, 0.7f, STONE),
            new Part(0.41f, 1.16f, 0, 0.27f, 0.78f, 0.32f, 0.35f, 0.75f, Material.ANDESITE),
            new Part(-0.41f, 1.16f, 0, 0.27f, 0.78f, 0.32f, 0.35f, 0.75f, Material.ANDESITE),
            new Part(0, 1.8f, 0, 0.58f, 0.56f, 0.58f, 0.7f, 1.0f, Material.POLISHED_ANDESITE),
    };

    // ------------------------------------------------------------------ state

    /** Next tick each attacker may be bitten again, by entity id. */
    private final Map<UUID, Long> bitten = new HashMap<>();
    /** Enemies currently turned to stone, by entity id. */
    private final Map<UUID, Statue> statues = new HashMap<>();
    /** Display entities of this instance, pruned when counted (the budget). */
    private final List<Display> live = new ArrayList<>();
    private final List<BlockDisplay> hair = new ArrayList<>();
    private final float[] snakePhase = new float[SNAKES];
    /** Ticks left of the hair rearing up (gaze wind-up, the gorgon eye). */
    private long rearUntil = Long.MIN_VALUE / 2;
    private @Nullable Gorgon gorgon;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        growHair();
    }

    @Override
    public void onRevoke() {
        // Every modifier and silence is timed and every display tracked: only forget the bookkeeping.
        statues.clear();
        bitten.clear();
        hair.clear();
        live.clear();
        gorgon = null;
    }

    // ------------------------------------------------------------------ passive: snake hair

    private void growHair() {
        Player owner = player();
        if (!reserve(SNAKES)) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location eye = owner.getEyeLocation();
        for (int i = 0; i < SNAKES; i++) {
            snakePhase[i] = random.nextFloat() * 6.28f;
            BlockData data = (i % 2 == 0 ? SNAKE_DARK : SNAKE_LIGHT).createBlockData();
            BlockDisplay snake = keep(Displays.block(ctx(), owner, eye, data, snakePose(i, 0, 0.01f, 0), 0));
            snake.setTeleportDuration(1);
            // The snakes coil around her own head: only the others see them.
            owner.hideEntity(ctx().plugin(), snake);
            hair.add(snake);
        }
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        if (!hair.isEmpty()) {
            float rear = now < rearUntil ? 1f : 0f;
            Location eye = owner.getEyeLocation();
            float yaw = eye.getYaw();
            float grow = Math.min(1f, (tick + 1) / 6f);
            for (int i = 0; i < hair.size(); i++) {
                BlockDisplay snake = hair.get(i);
                if (!snake.isValid() || !snake.getWorld().equals(owner.getWorld())) {
                    continue;
                }
                Displays.moveTo(snake, eye);
                Displays.animate(snake, snakePose(i, yaw, grow, tick * (0.18f + 0.1f * rear) + snakePhase[i]), 1);
            }
        }
        if (tick % WISP_PERIOD == 0) {
            // A green glint between the snakes now and then; a soft hiss less often.
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location crown = owner.getEyeLocation().add(random.nextDouble(-0.3, 0.3), 0.3 + random.nextDouble(0.2),
                    random.nextDouble(-0.3, 0.3));
            Vfx.dot(crown, Particle.DUST, VENOM, false);
            if (tick % HISS_PERIOD == 0) {
                Fx.sound(crown, Sound.ENTITY_CAT_HISS, 0.25f, 1.6f + Fx.jitter(0.2f));
            }
        }
        if (tick % 100 == 0 && !bitten.isEmpty()) {
            bitten.values().removeIf(until -> until <= now);
        }
    }

    /**
     * Snake {@code index}: a thin green strand sprouting from behind the face and writhing outwards; it stands up
     * and flares forward like a hood while {@link #rearUntil} lasts. The turn to the head's yaw is in the
     * transformation; the display itself is never rotated.
     */
    private Transformation snakePose(int index, float yaw, float grow, float phase) {
        boolean rearing = ctx().currentTick() < rearUntil;
        // Spread over the back half of the head (angle 0 = straight ahead).
        double around = Math.toRadians(100 + index * (160.0 / (SNAKES - 1)));
        Vector3f base = new Vector3f((float) Math.sin(around) * HAIR_RADIUS, HAIR_LIFT,
                (float) Math.cos(around) * HAIR_RADIUS);
        Vector3f out = new Vector3f(base.x(), 0, base.z()).normalize();
        float wiggle = (float) Math.sin(phase) * 0.45f;
        float lift = rearing ? 1.6f : 0.55f + (float) Math.cos(phase * 0.7f) * 0.15f;
        Vector3f direction = new Vector3f(out).mul(rearing ? 0.55f : 1f)
                .add(new Vector3f(-out.z(), 0, out.x()).mul(wiggle)).add(0, lift, rearing ? 0.35f : 0f).normalize();
        Quaternionf turn = yawTurn(yaw);
        float length = SNAKE_LENGTH * (rearing ? 1.2f : 1f) * Math.max(0.01f, grow);
        Vector3f start = turn.transform(new Vector3f(base));
        Vector3f along = turn.transform(new Vector3f(direction)).mul(length);
        return beam(start, along, SNAKE_WIDTH);
    }

    /** A melee blow on Medusa: a snake from her hair strikes the attacker. */
    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || ctx().isAbilityDamage() || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        DamageCause cause = event.getCause();
        if (cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        if (!(byEntity.getDamager() instanceof LivingEntity attacker) || !ctx().isEnemy(player(), attacker)) {
            return;
        }
        long now = ctx().currentTick();
        Long ready = bitten.get(attacker.getUniqueId());
        if (ready != null && now < ready) {
            return;
        }
        bitten.put(attacker.getUniqueId(), now + params().ticks("snake_cooldown"));
        snakeStrike(attacker);
    }

    /** A snake lunges out of the hair at {@code attacker}, bites and coils back. */
    private void snakeStrike(LivingEntity attacker) {
        Player owner = player();
        boolean shown = seen(owner, attacker);
        Location from = owner.getEyeLocation().add(0, HAIR_LIFT, 0);
        BlockDisplay body = null;
        BlockDisplay head = null;
        if (shown && reserve(2)) {
            long life = BITE_REACH_TICKS + BITE_HOLD_TICKS + BITE_RETRACT_TICKS + 4;
            Vector3f delta = toVector3f(neck(attacker).toVector().subtract(from.toVector()));
            body = keep(Displays.block(ctx(), owner, from, SNAKE_DARK.createBlockData(),
                    beam(new Vector3f(), new Vector3f(delta).mul(0.05f), SNAKE_WIDTH * 1.3f), life));
            head = keep(Displays.block(ctx(), owner, from, SNAKE_HEAD.createBlockData(),
                    snakeHead(new Vector3f(delta).mul(0.05f), delta), life));
            body.setTeleportDuration(1);
            head.setTeleportDuration(1);
        }
        BlockDisplay strand = body;
        BlockDisplay jaws = head;
        Fx.sound(from, Sound.ENTITY_CAT_HISS, 0.8f, 1.3f + Fx.jitter(0.15f));
        Loop.repeat(ctx(), owner, 1, 1, BITE_REACH_TICKS + BITE_HOLD_TICKS + BITE_RETRACT_TICKS, run -> {
            Location mouth = owner.getEyeLocation().add(0, HAIR_LIFT, 0);
            boolean alive = attacker.isValid() && !attacker.isDead()
                    && attacker.getWorld().equals(owner.getWorld())
                    && attacker.getLocation().distance(owner.getLocation()) <= BITE_MAX_DISTANCE;
            Vector3f delta = alive ? toVector3f(neck(attacker).toVector().subtract(mouth.toVector()))
                    : new Vector3f(0, 0.4f, 0);
            float reach;
            if (run < BITE_REACH_TICKS) {
                reach = (run + 1f) / BITE_REACH_TICKS;
            } else if (run < BITE_REACH_TICKS + BITE_HOLD_TICKS) {
                reach = 1f;
                if (run == BITE_REACH_TICKS && alive) {
                    bite(attacker);
                }
            } else {
                reach = Math.max(0.02f, 1f - (run - BITE_REACH_TICKS - BITE_HOLD_TICKS + 1f) / BITE_RETRACT_TICKS);
            }
            Vector3f along = new Vector3f(delta).mul(reach);
            if (strand != null && strand.isValid()) {
                Displays.moveTo(strand, mouth);
                Displays.animate(strand, beam(new Vector3f(), along, SNAKE_WIDTH * 1.3f), 1);
            }
            if (jaws != null && jaws.isValid()) {
                Displays.moveTo(jaws, mouth);
                Displays.animate(jaws, snakeHead(along, delta), 1);
            }
            if (run < BITE_REACH_TICKS + BITE_HOLD_TICKS) {
                Vfx.dot(mouth.clone().add(along.x(), along.y(), along.z()), Particle.DUST, VENOM, false);
            }
            return true;
        }, () -> {
            for (BlockDisplay part : new BlockDisplay[] {strand, jaws}) {
                if (part != null && part.isValid()) {
                    part.remove();
                }
            }
        });
    }

    /** The bite lands: a little damage and poison. */
    private void bite(LivingEntity attacker) {
        Player owner = player();
        if (!Combat.isTargetable(ctx(), owner, attacker)) {
            return;
        }
        Combat.hit(ctx(), owner, attacker, params().num("snake_damage"));
        long poison = params().ticks("snake_poison");
        if (poison > 0 && Combat.isTargetable(ctx(), owner, attacker)) {
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.POISON, (int) Math.min(Integer.MAX_VALUE,
                    poison), 0, false, true, true));
        }
        Location at = neck(attacker);
        if (seen(owner, attacker)) {
            Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 8, 0.2, 0, PALETTE.glow(0.9f), false);
            Vfx.cloud(ctx(), at, Particle.ITEM_SLIME, 4, 0.15, 0.05, null, false);
            Vfx.dot(at, Particle.CRIT, null, false);
        }
        Fx.sound(at, Sound.ENTITY_FOX_BITE, 0.9f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_PUFFER_FISH_STING, 0.6f, 1.2f + Fx.jitter(0.1f));
        owner.sendActionBar(Text.mm("<" + SNAKE_TAG + ">🐍 뱀머리</" + SNAKE_TAG + "> <gray>반격의 독니</gray>"));
    }

    private static Location neck(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() * 0.8, 0);
    }

    /** The snake's head at the tip of its body, a little box pointing along the strike. */
    private static Transformation snakeHead(Vector3f tip, Vector3f direction) {
        Quaternionf rotation = pointing(direction);
        Vector3f size = new Vector3f(0.13f, 0.1f, 0.2f);
        return box(tip, size, rotation);
    }

    // ------------------------------------------------------------------ active: petrifying gaze

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        long windup = Math.max(1, params().ticks("gaze_windup"));
        rearUntil = ctx().currentTick() + windup + 6;
        Eyes eyes = new Eyes(owner, windup + 2);
        Location eye = owner.getEyeLocation();
        Vfx.cloud(ctx(), eye, Particle.ENCHANT, 10, 0.3, 0.6, null, false);
        Fx.sound(eye, Sound.ENTITY_CAT_HISS, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.7f, 1.4f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.6f, 0.5f);
        Loop.repeat(ctx(), owner, 0, 1, windup, run -> {
            eyes.follow(run);
            telegraph(owner, run, windup);
            return true;
        }, () -> {
            eyes.close();
            gaze(owner);
        });
        return ActivationResult.success();
    }

    /** The wind-up: the cone about to be swept is traced in gold on the ground, wherever Medusa now faces. */
    private void telegraph(Player owner, long run, long windup) {
        if (run % 2 == 1) {
            return;
        }
        double range = Math.max(1, params().num("gaze_range"));
        double half = Math.clamp(params().num("gaze_angle"), 5, 170) / 2;
        Location feet = owner.getLocation().add(0, 0.12, 0);
        float yaw = feet.getYaw();
        double progress = Math.min(1.0, (run + 1.0) / windup);
        for (double side : new double[] {-half, half}) {
            Vector edge = facing(yaw + (float) side).multiply(range * progress);
            Vfx.line(ctx(), feet, feet.clone().add(edge), 1.2, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, true);
        }
        Vfx.arc(ctx(), feet, range * progress, yaw, half * 2, 14, Particle.DUST_COLOR_TRANSITION, GOLD_TO_STONE, true);
        Fx.sound(owner.getEyeLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 0.8f + 0.4f * (float) progress);
    }

    /** The gaze: everyone in the cone who looks at Medusa turns to stone, the others only stiffen at the legs. */
    private void gaze(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 1.0E-6) {
            direction = new Vector(0, 0, 1);
        }
        direction.normalize();
        double range = Math.max(1, params().num("gaze_range"));
        double halfAngle = Math.toRadians(Math.clamp(params().num("gaze_angle"), 5, 170) / 2);
        double cosHalf = Math.cos(halfAngle);
        gazeWave(owner, eye, direction, range, halfAngle);

        int stoned = 0;
        int glanced = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, eye, range)) {
            if (!seen(owner, enemy)) {
                continue;
            }
            Location body = Fx.body(enemy);
            Vector to = body.toVector().subtract(eye.toVector());
            double distance = to.length();
            if (distance > POINT_BLANK && to.dot(direction) / distance < cosHalf) {
                continue;
            }
            if (!Spots.clearPath(eye, enemy.getEyeLocation()) && !Spots.clearPath(eye, body)) {
                continue;
            }
            if (isLookingAt(enemy, eye, LOOK_COS)) {
                if (petrify(enemy, params().ticks("petrify_duration"), params().num("petrify_damage"))) {
                    stoned++;
                    Vfx.trail(eye, enemy.getEyeLocation(), GOLD, 5, false);
                }
            } else {
                glance(enemy);
                glanced++;
            }
        }
        if (stoned + glanced > 0) {
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">◉ 석화의 시선</" + GOLD_TAG + "> "
                    + (stoned > 0 ? "<white>" + stoned + "명 석화</white>" : "<gray>석화 없음</gray>")
                    + (glanced > 0 ? "<dark_gray> · </dark_gray><gray>" + glanced + "명이 시선을 피했습니다</gray>"
                    : "")));
        }
    }

    /** The flash of the eyes and a wave of golden rings rolling out through the cone. */
    private void gazeWave(Player owner, Location eye, Vector direction, double range, double halfAngle) {
        Location from = eye.clone().add(direction.clone().multiply(0.4));
        Vfx.dot(from, Particle.FLASH, GOLD, true);
        Vfx.cloud(ctx(), from, Particle.END_ROD, 6, 0.1, 0.12, null, false);
        Fx.sound(eye, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.7f, 1.6f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ENTITY_ENDERMAN_STARE, 0.6f, 1.4f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.BLOCK_STONE_PLACE, 1.0f, 0.5f);
        Vector[] basis = basis(direction);
        Loop.repeat(ctx(), owner, 0, 1, GAZE_WAVES, run -> {
            double distance = range * (run + 1) / GAZE_WAVES;
            double radius = Math.max(0.3, Math.tan(halfAngle) * distance * 0.75);
            Location center = eye.clone().add(direction.clone().multiply(distance));
            int points = Vfx.n(ctx(), (int) Math.min(18, 8 + radius * 3));
            World world = center.getWorld();
            for (int i = 0; i < points; i++) {
                double angle = i * Math.PI * 2 / points + run * 0.3;
                Vector offset = basis[0].clone().multiply(Math.cos(angle) * radius)
                        .add(basis[1].clone().multiply(Math.sin(angle) * radius));
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, center.clone().add(offset), 1, 0, 0, 0, 0,
                        run < 2 ? GOLD_GLOW : GOLD_TO_STONE, true);
            }
            Vfx.dot(center, Particle.WAX_OFF, null, false);
            return true;
        });
    }

    /** Looked away in time: only the legs stiffen. */
    private void glance(LivingEntity enemy) {
        long slow = params().ticks("glance_slow");
        if (slow > 0) {
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) Math.min(Integer.MAX_VALUE, slow),
                    1, false, true, true));
        }
        Location feet = enemy.getLocation().add(0, 0.1, 0);
        Vfx.ring(ctx(), feet, 0.5, 10, 0, Particle.DUST, STONE_DUST, false);
        enemy.getWorld().spawnParticle(Particle.BLOCK, feet.clone().add(0, 0.3, 0), Vfx.n(ctx(), 6), 0.2, 0.2, 0.2,
                0, STONE.createBlockData());
        Fx.sound(feet, Sound.BLOCK_STONE_HIT, 0.7f, 1.2f + Fx.jitter(0.1f));
    }

    /** True if {@code enemy} looks at {@code point} within the angle whose cosine is {@code cos}. */
    private static boolean isLookingAt(LivingEntity enemy, Location point, double cos) {
        Location eye = enemy.getEyeLocation();
        Vector toPoint = point.toVector().subtract(eye.toVector());
        if (toPoint.lengthSquared() < 1.0E-6) {
            return true;
        }
        Vector look = eye.getDirection();
        if (look.lengthSquared() < 1.0E-6) {
            return false;
        }
        return look.normalize().dot(toPoint.normalize()) >= cos;
    }

    /** Medusa's eyes light up gold for the wind-up (two tiny glowing cubes over her eyes, hidden from her). */
    private final class Eyes {

        private final List<BlockDisplay> parts = new ArrayList<>();
        private final Player owner;

        Eyes(Player owner, long life) {
            this.owner = owner;
            if (!reserve(2)) {
                return;
            }
            for (int side = 0; side < 2; side++) {
                BlockDisplay glint = keep(Displays.block(ctx(), owner, owner.getEyeLocation(),
                        EYE_GLOW.createBlockData(), eyePose(owner, side, 0.02f), life));
                glint.setTeleportDuration(1);
                Displays.outline(glint, GOLD);
                owner.hideEntity(ctx().plugin(), glint);
                parts.add(glint);
            }
        }

        void follow(long run) {
            for (int side = 0; side < parts.size(); side++) {
                BlockDisplay glint = parts.get(side);
                if (glint.isValid()) {
                    Displays.moveTo(glint, owner.getEyeLocation());
                    Displays.animate(glint, eyePose(owner, side, 0.07f + 0.02f * (run % 2)), 1);
                }
            }
            Location eye = owner.getEyeLocation();
            Vector front = eye.getDirection().multiply(0.35);
            Vfx.dot(eye.clone().add(front).add(0, 0.05, 0), Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, false);
        }

        void close() {
            for (BlockDisplay glint : parts) {
                if (glint.isValid()) {
                    Displays.shrinkAway(ctx(), owner, glint, 3);
                }
            }
        }
    }

    /** One glowing eye ({@code side} 0 = left) just in front of the face, turned with the head. */
    private static Transformation eyePose(Player owner, int side, float size) {
        Location eye = owner.getEyeLocation();
        Quaternionf turn = yawTurn(eye.getYaw());
        Vector3f center = turn.transform(new Vector3f(side == 0 ? 0.11f : -0.11f, 0.02f, 0.27f));
        return box(center, new Vector3f(size, size * 0.6f, size * 0.3f), turn);
    }

    // ------------------------------------------------------------------ petrification

    /**
     * Turns {@code victim} to stone for {@code ticks} (longer if it is poisoned): it cannot walk, jump, strike or use
     * skills, and the statue bursts for {@code damage} when it ends. False if it already is a statue.
     */
    private boolean petrify(LivingEntity victim, long ticks, double damage) {
        return petrify(victim, ticks, damage, null);
    }

    private boolean petrify(LivingEntity victim, long ticks, double damage, @Nullable Shell creeping) {
        Statue existing = statues.get(victim.getUniqueId());
        if (existing != null && !existing.done) {
            return false;
        }
        Player owner = player();
        if (!Combat.isTargetable(ctx(), owner, victim)) {
            return false;
        }
        long duration = Math.max(1, ticks);
        if (victim.hasPotionEffect(PotionEffectType.POISON)) {
            duration += params().ticks("venom_petrify");
        }
        Statue statue = new Statue(victim, duration, damage, creeping);
        statues.put(victim.getUniqueId(), statue);
        statue.begin();
        Loop.repeat(ctx(), owner, 1, 1, duration, statue, statue::shatter);
        return true;
    }

    /** True while {@code entity} is encased in one of Medusa's statues. */
    private boolean isStatue(LivingEntity entity) {
        Statue statue = statues.get(entity.getUniqueId());
        return statue != null && !statue.done;
    }

    /** A stone statue around a petrified enemy, from the cast until it bursts. */
    private final class Statue implements LongPredicate {

        private final LivingEntity victim;
        private final long duration;
        private final double damage;
        private final Shell shell;
        boolean done;

        Statue(LivingEntity victim, long duration, double damage, @Nullable Shell creeping) {
            this.victim = victim;
            this.duration = duration;
            this.damage = damage;
            // A creeping stone the display budget could not show is replaced: the statue itself must be seen.
            this.shell = creeping != null && creeping.shown ? creeping : new Shell(victim, duration + 16);
        }

        void begin() {
            Player owner = player();
            // Rooted, disarmed and silenced for the whole time; a statue does not budge under blows either.
            long lease = duration + 1;
            ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, lease);
            ctx().addModifier(owner, victim, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, lease);
            // Bees, vexes and phantoms fly by their flying speed: a statue does not fly on.
            ctx().addModifier(owner, victim, Attribute.FLYING_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, lease);
            ctx().addModifier(owner, victim, Attribute.ATTACK_DAMAGE, -1.0, Operation.MULTIPLY_SCALAR_1, lease);
            ctx().addModifier(owner, victim, Attribute.KNOCKBACK_RESISTANCE, 1.0, Operation.ADD_NUMBER, lease);
            if (victim instanceof Player petrified) {
                ctx().silence(petrified, lease);
            }
            // Stop where it stands. A player's server velocity is stale, so its fall is not read back.
            double fall = victim instanceof Player ? REST_FALL : Math.min(0, victim.getVelocity().getY());
            victim.setVelocity(new Vector(0, fall, 0));
            shell.grow(PETRIFY_GROW_TICKS);

            Location body = Fx.body(victim);
            if (shell.shown) {
                World world = body.getWorld();
                world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 14), 0.3, 0.6, 0.3, 0, STONE.createBlockData());
                world.spawnParticle(Particle.FALLING_DUST, body.clone().add(0, 0.6, 0), Vfx.n(ctx(), 8), 0.3, 0.4,
                        0.3, 0, STONE.createBlockData());
                Vfx.ring(ctx(), victim.getLocation().add(0, 0.1, 0), 0.8, 14, 0, Particle.DUST_COLOR_TRANSITION,
                        GOLD_TO_STONE, false);
            }
            Fx.sound(body, Sound.BLOCK_STONE_PLACE, 1.2f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.BLOCK_DEEPSLATE_PLACE, 0.9f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.BLOCK_GRINDSTONE_USE, 0.5f, 0.6f + Fx.jitter(0.05f));
            if (victim instanceof Player petrified) {
                petrified.sendActionBar(Text.mm("<" + STONE_TAG + "><b>석화!</b></" + STONE_TAG + "> <gray>"
                        + Fx.seconds(duration) + "초간 움직일 수 없습니다</gray>"));
            }
        }

        @Override
        public boolean test(long tick) {
            if (!Combat.isTargetable(ctx(), player(), victim)) {
                crumble();
                return false;
            }
            if (!(victim instanceof Player)) {
                // A mob's velocity is its own (a player's server velocity is stale and left alone): the stone holds it.
                victim.setVelocity(new Vector(0, Math.min(0, victim.getVelocity().getY()), 0));
            }
            long left = duration - tick;
            float shiver = left <= SHIVER_TICKS ? 0.03f * (1f - (float) left / SHIVER_TICKS) + 0.01f : 0f;
            shell.follow(shiver);
            if (shell.shown && tick % 6 == 3) {
                victim.getWorld().spawnParticle(Particle.FALLING_DUST, Fx.body(victim), Vfx.n(ctx(), 2), 0.25, 0.5,
                        0.25, 0, STONE.createBlockData());
            }
            if (left <= SHIVER_TICKS && left % 3 == 0) {
                Fx.sound(Fx.body(victim), Sound.BLOCK_STONE_HIT, 0.7f, 0.8f + 0.6f * (1f - (float) left
                        / SHIVER_TICKS));
            }
            return true;
        }

        /** The time is up: the statue bursts and hurts the one inside. */
        void shatter() {
            if (done) {
                return;
            }
            done = true;
            statues.remove(victim.getUniqueId(), this);
            Player owner = player();
            if (!Combat.isTargetable(ctx(), owner, victim)) {
                shell.burst(false);
                return;
            }
            Combat.hit(ctx(), owner, victim, damage);
            shell.burst(true);
            Location body = Fx.body(victim);
            Fx.sound(body, Sound.BLOCK_DECORATED_POT_SHATTER, 1.1f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.BLOCK_STONE_BREAK, 1.2f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.BLOCK_DEEPSLATE_BREAK, 0.8f, 0.6f + Fx.jitter(0.05f));
        }

        /** The victim died or left: the empty statue crumbles. */
        private void crumble() {
            if (done) {
                return;
            }
            done = true;
            statues.remove(victim.getUniqueId(), this);
            shell.burst(false);
        }
    }

    /**
     * The stone that covers a victim: five boxes shaped like a body (legs, torso, two arms, head), each forming from
     * its bottom up as {@link #progress} grows, following the victim and turned to its body's yaw. Shown to nobody
     * (no displays) when the victim is hidden from Medusa or the display budget is spent.
     */
    private final class Shell {

        private final LivingEntity victim;
        private final BlockDisplay[] parts = new BlockDisplay[STATUE.length];
        final boolean shown;
        float progress;

        Shell(LivingEntity victim, long life) {
            this.victim = victim;
            Player owner = player();
            this.shown = seen(owner, victim) && reserve(STATUE.length);
            if (!shown) {
                return;
            }
            Location feet = victim.getLocation();
            for (int i = 0; i < STATUE.length; i++) {
                parts[i] = keep(Displays.block(ctx(), owner, feet, STATUE[i].block().createBlockData(),
                        partPose(i, 0, 0), life));
                parts[i].setTeleportDuration(1);
                parts[i].setViewRange(1.5f);
            }
        }

        /** Grows to a full statue over {@code ticks}. */
        void grow(long ticks) {
            float from = progress;
            Loop.repeat(ctx(), player(), 1, 1, Math.max(1, ticks), run -> {
                progress = from + (1f - from) * (run + 1f) / Math.max(1, ticks);
                return true;
            });
        }

        void follow(float shiver) {
            if (!shown) {
                return;
            }
            Location feet = victim.getLocation();
            for (int i = 0; i < parts.length; i++) {
                BlockDisplay part = parts[i];
                if (part != null && part.isValid()) {
                    Displays.moveTo(part, feet);
                    Displays.animate(part, partPose(i, progress, shiver), 1);
                }
            }
        }

        /** Part {@code index} of the statue at {@code progress} (0..1), trembling by {@code shiver} blocks. */
        private Transformation partPose(int index, float progress, float shiver) {
            Part part = STATUE[index];
            float formed = Math.clamp((progress - part.from()) / (part.to() - part.from()), 0f, 1f);
            float wide = (float) Math.max(0.3, victim.getWidth() / 0.6);
            float tall = (float) Math.max(0.3, victim.getHeight() / 1.8);
            float height = Math.max(0.001f, part.sy() * formed);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            float jx = shiver > 0 ? (random.nextFloat() * 2 - 1) * shiver : 0;
            float jz = shiver > 0 ? (random.nextFloat() * 2 - 1) * shiver : 0;
            Vector3f local = new Vector3f(part.x() * wide + jx, (part.y() - part.sy() / 2 + height / 2) * tall,
                    part.z() * wide + jz);
            Vector3f size = new Vector3f(part.sx() * wide * (formed > 0 ? 1 : 0.01f), height * tall,
                    part.sz() * wide * (formed > 0 ? 1 : 0.01f));
            Quaternionf turn = yawTurn(victim.getBodyYaw());
            return box(turn.transform(local), size, turn);
        }

        /** The stone splits into chunks that tumble outwards and vanish. */
        void burst(boolean violent) {
            Player owner = player();
            Location feet = victim.getLocation();
            if (shown && feet.getWorld().equals(owner.getWorld())) {
                World world = feet.getWorld();
                Location body = Fx.body(victim);
                world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), violent ? 30 : 14), 0.35, 0.6, 0.35, 0,
                        STONE.createBlockData());
                world.spawnParticle(Particle.BLOCK_CRUMBLE, body, Vfx.n(ctx(), 10), 0.3, 0.5, 0.3, 0,
                        Material.COBBLESTONE.createBlockData());
                Vfx.cloud(ctx(), body, Particle.DUST, 10, 0.4, 0, STONE_DUST, false);
                if (violent) {
                    Vfx.dot(body, Particle.FLASH, PALE_GOLD, false);
                    Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 8, 0.4, 0, GOLD_TO_STONE, false);
                }
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (BlockDisplay part : parts) {
                if (part == null || !part.isValid()) {
                    continue;
                }
                Vector out = new Vector(random.nextDouble(-1, 1), random.nextDouble(0.2, 0.8), random.nextDouble(-1, 1));
                part.setTeleportDuration(6);
                Displays.moveTo(part, part.getLocation().add(out.multiply(violent ? 0.9 : 0.4)));
                Displays.shrinkAway(ctx(), owner, part, 6);
            }
        }

        /** Thaws back to nothing (the gorgon's creeping stone of a viewer that looked away for good). */
        void thawAway() {
            for (BlockDisplay part : parts) {
                if (part != null && part.isValid()) {
                    Displays.shrinkAway(ctx(), player(), part, 4);
                }
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: gorgon eye

    @Override
    public ActivationResult onUltimate() {
        if (gorgon != null && !gorgon.closed) {
            return ActivationResult.fail("고르곤의 눈이 이미 떠 있습니다");
        }
        Player owner = player();
        Gorgon eye = new Gorgon(owner);
        gorgon = eye;
        eye.open();
        return ActivationResult.success();
    }

    /** How long one viewer has stared at the gorgon, and the stone creeping up its body. */
    private static final class Stare {
        final LivingEntity viewer;
        double amount;
        boolean stoned;
        @Nullable Shell shell;
        long lastWarning = Long.MIN_VALUE / 2;

        Stare(LivingEntity viewer) {
            this.viewer = viewer;
        }

        /** The creeping stone thaws away at once (the viewer is gone, hidden or already a statue). */
        void dropShell() {
            if (shell != null) {
                shell.thawAway();
                shell = null;
            }
        }
    }

    /**
     * The gorgon eye: a lens of four billboarded boxes over Medusa's head (each viewer sees it staring right at them)
     * that opens, follows her and watches for its lifetime. Every enemy in range that looks at Medusa (or the eye) and
     * has a clear line to it stares; the stone creeps up its body with the stare and thaws when it looks away, and a
     * full stare turns it to stone.
     */
    private final class Gorgon implements LongPredicate {

        private final Player owner;
        private final long life;
        private final double radius;
        private final long stareTicks;
        private final Map<UUID, Stare> stares = new HashMap<>();
        private final List<BlockDisplay> eye = new ArrayList<>();
        private int stoned;
        boolean closed;

        Gorgon(Player owner) {
            this.owner = owner;
            this.life = Math.max(20, params().ticks("ult_duration"));
            this.radius = Math.max(1, params().num("ult_radius"));
            this.stareTicks = Math.max(1, params().ticks("stare_time"));
        }

        void open() {
            Location at = eyeSpot(0);
            long lifetime = life + EYE_OPEN_TICKS + 8;
            Material[] layers = {LID, SCLERA, IRIS, PUPIL};
            if (reserve(layers.length)) {
                for (int i = 0; i < layers.length; i++) {
                    BlockDisplay layer = keep(Displays.block(ctx(), owner, at, layers[i].createBlockData(),
                            eyeLayer(i, 0.04f, 1f), lifetime));
                    layer.setBillboard(Display.Billboard.CENTER);
                    layer.setTeleportDuration(1);
                    layer.setViewRange(3.0f);
                    if (i == 0) {
                        Displays.outline(layer, GOLD);
                    }
                    eye.add(layer);
                }
            }
            rearUntil = ctx().currentTick() + life;
            Location center = owner.getEyeLocation();
            Vfx.dot(at, Particle.FLASH, GOLD, true);
            Vfx.sphere(ctx(), at, 1.2, 28, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, true);
            Vfx.shockwave(ctx(), owner, owner.getLocation().add(0, 0.1, 0), 0.5, radius, 8,
                    Particle.DUST_COLOR_TRANSITION, GOLD_TO_STONE, true);
            Fx.sound(center, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.4f, 0.8f);
            Fx.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.6f);
            Fx.sound(center, Sound.ENTITY_CAT_HISS, 1.2f, 0.6f);
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>◉ 고르곤의 눈</b></" + GOLD_TAG + "> <gray>"
                    + Fx.seconds(life) + "초간 나를 보는 자는 돌이 됩니다</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, life, this, this::close);
        }

        /** Over Medusa's head, bobbing a little. */
        private Location eyeSpot(long tick) {
            return owner.getEyeLocation().add(0, EYE_ABOVE + 0.08 * Math.sin(tick * 0.15), 0);
        }

        @Override
        public boolean test(long tick) {
            Location spot = eyeSpot(tick);
            float open = Math.min(1f, (tick + 1f) / EYE_OPEN_TICKS);
            float blink = tick % 50 >= 47 ? 0.25f : 1f;
            float pupil = stoned > 0 && tick % 20 < 6 ? 0.55f : 1f;
            for (int i = 0; i < eye.size(); i++) {
                BlockDisplay layer = eye.get(i);
                if (layer.isValid()) {
                    Displays.moveTo(layer, spot);
                    Displays.animate(layer, eyeLayer(i, open * blink, i == 3 ? pupil : 1f), 1);
                }
            }
            watch(spot, tick);
            if (tick % 4 == 0) {
                Vfx.ring(ctx(), spot, 1.15, 12, tick * 0.12, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, true);
            }
            if (tick % 20 == 0) {
                Fx.sound(spot, Sound.BLOCK_BEACON_AMBIENT, 0.8f, 0.6f + 0.4f * tick / life);
            }
            return true;
        }

        /**
         * Every viewer's stare grows while it looks at Medusa and thaws while it looks away. A viewer that is already a
         * statue (her gaze) stares on only once that statue has burst, so the two never chain into one long stun.
         */
        private void watch(Location spot, long tick) {
            Location gorgonEyes = owner.getEyeLocation();
            double step = 1.0 / stareTicks;
            Set<UUID> watched = new HashSet<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, gorgonEyes, radius)) {
                if (!seen(owner, enemy)) {
                    continue;
                }
                watched.add(enemy.getUniqueId());
                Stare stare = stares.computeIfAbsent(enemy.getUniqueId(), id -> new Stare(enemy));
                if (stare.stoned) {
                    continue;
                }
                if (isStatue(enemy)) {
                    // Stone already: its creeping stone gives way to the statue, and the stare waits.
                    stare.dropShell();
                    continue;
                }
                Location eyes = enemy.getEyeLocation();
                boolean looking = (isLookingAt(enemy, gorgonEyes, STARE_COS) || isLookingAt(enemy, spot, STARE_COS))
                        && (Spots.clearPath(eyes, gorgonEyes) || Spots.clearPath(eyes, spot));
                stare.amount = Math.clamp(stare.amount + (looking ? step : -step * THAW_RATE), 0, 1);
                if (looking) {
                    if (tick % 2 == 0) {
                        Vfx.trail(spot, eyes, tick % 4 == 0 ? GOLD : PALE_GOLD, 6, true);
                    }
                    warn(enemy, stare, tick);
                }
                creep(enemy, stare, tick);
                if (stare.amount >= 1 - 1.0E-9) {
                    Shell shell = stare.shell;
                    stare.shell = null;
                    stare.stoned = true;
                    if (petrify(enemy, params().ticks("ult_petrify"), params().num("ult_damage"), shell)) {
                        stoned++;
                        Vfx.line(ctx(), spot, Fx.body(enemy), 3, Particle.DUST_COLOR_TRANSITION, GOLD_GLOW, true);
                        Fx.sound(spot, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 1.4f);
                        owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">◉ 고르곤의 눈</" + GOLD_TAG + "> <white>"
                                + stoned + "명 석화</white>"));
                    } else if (shell != null) {
                        shell.thawAway();
                    }
                }
            }
            unwatched(watched, step, tick);
        }

        /**
         * The viewers the eye no longer watches: out of its reach the stone thaws as it walks away (and goes with it);
         * a viewer that died, left or became hidden loses its stone at once, so nothing is left floating where it
         * stood or marks where it vanished.
         */
        private void unwatched(Set<UUID> watched, double step, long tick) {
            for (Iterator<Stare> it = stares.values().iterator(); it.hasNext(); ) {
                Stare stare = it.next();
                LivingEntity viewer = stare.viewer;
                if (stare.stoned || watched.contains(viewer.getUniqueId())) {
                    continue;
                }
                boolean gone = !Combat.isTargetable(ctx(), owner, viewer)
                        || !viewer.getWorld().equals(owner.getWorld());
                if (gone) {
                    stare.dropShell();
                    it.remove();
                    continue;
                }
                if (isStatue(viewer)) {
                    stare.dropShell();
                    continue;
                }
                stare.amount = Math.max(0, stare.amount - step * THAW_RATE);
                if (!seen(owner, viewer)) {
                    stare.dropShell();
                    continue;
                }
                creep(viewer, stare, tick);
            }
        }

        /**
         * The stone creeping up a staring viewer, shown as a statue forming from the feet. When the display budget was
         * spent, the stone is shown as soon as there is room again, and until then as a ring of grit rising up the
         * body, so every viewer sees its warning.
         */
        private void creep(LivingEntity enemy, Stare stare, long tick) {
            if (stare.amount <= 0) {
                stare.dropShell();
                return;
            }
            if (stare.shell == null || !stare.shell.shown && reserve(STATUE.length)) {
                stare.dropShell();
                // Long enough to become the statue of a stare completed at the very end of the eye.
                stare.shell = new Shell(enemy, life + 2 * params().ticks("ult_petrify")
                        + params().ticks("venom_petrify") + 30);
            }
            Shell shell = stare.shell;
            // Stops short of the head: the last part forms only when the statue is complete.
            shell.progress = (float) (stare.amount * 0.82);
            shell.follow(0);
            Location rising = enemy.getLocation().add(0, enemy.getHeight() * stare.amount * 0.82, 0);
            if (shell.shown) {
                if (ThreadLocalRandom.current().nextInt(5) == 0) {
                    enemy.getWorld().spawnParticle(Particle.FALLING_DUST, rising, 1, 0.2, 0.05, 0.2, 0,
                            STONE.createBlockData());
                }
            } else if (tick % 3 == 0) {
                Vfx.ring(ctx(), rising, Math.max(0.3, enemy.getWidth() * 0.7), 8, tick * 0.4, Particle.DUST,
                        STONE_DUST, false);
            }
        }

        /** The staring viewer hears its heart race and is told to look away. */
        private void warn(LivingEntity enemy, Stare stare, long tick) {
            if (tick - stare.lastWarning < WARN_PERIOD) {
                return;
            }
            stare.lastWarning = tick;
            Fx.sound(enemy.getEyeLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.8f, 1.0f + (float) stare.amount);
            Fx.sound(enemy.getLocation(), Sound.BLOCK_STONE_HIT, 0.5f, 0.6f + (float) stare.amount * 0.6f);
            if (enemy instanceof Player viewer) {
                int filled = (int) Math.round(stare.amount * 5);
                viewer.sendActionBar(Text.mm("<" + GOLD_TAG + ">◉ 고르곤의 눈과 마주쳤습니다!</" + GOLD_TAG + "> <"
                        + STONE_TAG + ">" + "▮".repeat(filled) + "</" + STONE_TAG + "><dark_gray>"
                        + "▯".repeat(5 - filled) + "</dark_gray> <gray>시선을 돌리세요</gray>"));
            }
        }

        /** The eye shuts and fades; stone that never finished thaws away. */
        void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (BlockDisplay layer : eye) {
                if (layer.isValid()) {
                    Displays.shrinkAway(ctx(), owner, layer, 6);
                }
            }
            for (Stare stare : stares.values()) {
                stare.dropShell();
            }
            stares.clear();
            Location spot = eyeSpot(life);
            Vfx.cloud(ctx(), spot, Particle.DUST_COLOR_TRANSITION, 16, 0.5, 0, GOLD_TO_STONE, true);
            Vfx.cloud(ctx(), spot, Particle.END_ROD, 6, 0.4, 0.05, null, false);
            Fx.sound(spot, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.7f);
            if (gorgon == this) {
                gorgon = null;
            }
        }
    }

    /**
     * Layer {@code index} of the gorgon eye (0 lid, 1 white, 2 iris, 3 slit), opened to {@code open} of its height.
     * Lid and white are lenses (a cube turned 45° about the view axis and squashed), the iris a diamond, the slit a
     * thin bar; deeper layers are thicker so they show through from either side of the billboard.
     */
    private static Transformation eyeLayer(int index, float open, float pupilWidth) {
        float h = Math.max(0.02f, open);
        return switch (index) {
            case 0 -> lens(EYE_WIDTH * 1.08f, EYE_HEIGHT * 1.12f * h, 0.04f);
            case 1 -> lens(EYE_WIDTH, EYE_HEIGHT * h, 0.07f);
            case 2 -> lens(EYE_HEIGHT * 0.78f, EYE_HEIGHT * 0.78f * h, 0.1f);
            default -> Displays.transform(0, 0, 0, 0.09f * pupilWidth, EYE_HEIGHT * 0.66f * h, 0.13f, 0, 0, 0);
        };
    }

    /** A flat lens of the given width and height (a cube turned 45° about Z, then squashed), centered. */
    private static Transformation lens(float width, float height, float depth) {
        float root = (float) Math.sqrt(2);
        Quaternionf diamond = new Quaternionf().rotateZ((float) Math.toRadians(45));
        Vector3f scale = new Vector3f(width / root, height / root, depth);
        Vector3f center = diamond.transform(new Vector3f(0.5f)).mul(scale);
        return new Transformation(center.negate(), new Quaternionf(), scale, diamond);
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

    /** The rotation of a Minecraft yaw (0 faces +Z, 90 faces -X) about the vertical. */
    private static Quaternionf yawTurn(float yaw) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw));
    }

    /** The horizontal unit vector a Minecraft yaw faces. */
    private static Vector facing(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0, Math.cos(radians));
    }

    /** A box of {@code size} centered on {@code center} (relative to the display), turned by {@code rotation}. */
    private static Transformation box(Vector3f center, Vector3f size, Quaternionf rotation) {
        Vector3f translation = new Vector3f(center).sub(new Quaternionf(rotation).transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    /** A thin square bar of {@code width} from {@code start} along {@code along} (both relative to the display). */
    private static Transformation beam(Vector3f start, Vector3f along, float width) {
        float length = Math.max(0.001f, along.length());
        Quaternionf rotation = pointing(along);
        Vector3f center = new Vector3f(start).add(new Vector3f(along).mul(0.5f));
        return box(center, new Vector3f(width, width, length), rotation);
    }

    /** The rotation turning +Z onto {@code direction} (+Z if it is zero). */
    private static Quaternionf pointing(Vector3f direction) {
        if (direction.lengthSquared() < 1.0E-8f) {
            return new Quaternionf();
        }
        return new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(direction).normalize());
    }

    private static Vector3f toVector3f(Vector vector) {
        return new Vector3f((float) vector.getX(), (float) vector.getY(), (float) vector.getZ());
    }

    /** Two unit vectors perpendicular to {@code axis} and to each other. */
    private static Vector[] basis(Vector axis) {
        Vector reference = Math.abs(axis.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = axis.clone().crossProduct(reference).normalize();
        Vector v = axis.clone().crossProduct(u).normalize();
        return new Vector[] {u, v};
    }
}
