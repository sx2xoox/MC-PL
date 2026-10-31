package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Inputs;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
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
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 빨간 마스크 — the school ghost story: a woman in a white mask who asks "나 예뻐?" and runs a hundred metres in three
 * seconds. Running from her is the worst idea: she speeds up for every enemy that turns its back, her shears mark a
 * victim who is cut again for each second it keeps fleeing, and her question has no free answer — "yes" shows what is
 * under the mask, "no" earns the shears, and only "so-so" confuses her, which makes her angry and fast.
 *
 * <p>Look (blood red, mask white, dusk grey): big shears snapping over the marked heads, red slashes, a white mask over
 * her face while she asks, dropping to show a red grin; red after-images at her heels while she chases.
 *
 * <p>"Turning one's back and walking away" is judged by voluntary input only (the walking keys, relative to where the
 * enemy looks), never by position or velocity, with the usual exemptions for players pushed or under hard control.
 */
public final class RedMaskAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("redmask", "빨간 마스크", Material.SHEARS)
            .color(TextColor.color(0xB3121F))
            .difficulty(2)
            .passive("100미터 3초",
                    "{chase_range}블록 안에서 나에게 등을 보이고 멀어지는 적이 있으면 1초마다 이동 속도가 {chase_step}% "
                            + "빨라집니다(최대 {chase_max}%, 아무도 도망치지 않으면 1초마다 {chase_fade}%씩 사라짐). 도망치던 "
                            + "적에게 주는 첫 근접 공격은 {flee_bonus} 추가 피해를 줍니다.")
            .active("도망쳐도 소용없어",
                    "앞 {snip_range}블록 안의 적 하나를 가위로 베어 {snip_damage} 피해를 주고 {mark_duration}초간 가위 "
                            + "자국을 남깁니다. 자국이 난 적은 나에게 등을 보이고 멀어지는 동안 1초마다 {mark_damage} 피해를 "
                            + "받습니다(최대 {mark_max}번). 앞에 적이 없으면 쿨타임이 {miss_refund}% 돌아옵니다.",
                    10)
            .ultimate("나 예뻐?",
                    "반경 {question_radius}블록의 적에게 질문을 던지고 {answer_seconds}초간 답을 기다립니다(그동안 내 이동 "
                            + "속도 +{ask_speed}%). 끄덕이면(아래를 보면) {yes_damage} 피해와 {bleed_seconds}초 출혈, 고개를 "
                            + "크게 돌리거나 답하지 않으면 {no_damage} 피해와 {no_stun}초 기절을 받습니다. 웅크리면 무사하지만 "
                            + "{so_so_seconds}초간 나를 화나게 해, 그 적 근처에서 내 이동 속도가 {angry_speed}% 빨라지고 그 "
                            + "적에게 주는 다음 근접 공격이 {angry_bonus} 추가 피해와 {angry_stun}초 기절을 줍니다.")
            .ultimateNotStandalone()
            .param("chase_range", 16)
            .param("chase_step", 6)
            .param("chase_max", 30)
            .param("chase_fade", 6)
            .param("flee_bonus", 3)
            .param("snip_range", 4)
            .param("snip_damage", 3)
            .param("mark_duration", 6)
            .param("mark_damage", 1)
            .param("mark_max", 4)
            .param("miss_refund", 50)
            .param("question_radius", 12)
            .param("answer_seconds", 2)
            .param("answer_grace", 0.3)
            .param("ask_speed", 30)
            .param("yes_damage", 3)
            .param("bleed_seconds", 3)
            .param("bleed_damage", 1)
            .param("no_damage", 5)
            .param("no_stun", 1.5)
            .param("so_so_seconds", 4)
            .param("angry_speed", 40)
            .param("angry_bonus", 4)
            .param("angry_stun", 0.75)
            .build();

    // ------------------------------------------------------------------ look
    private static final String BLOOD_TAG = "#B3121F";
    private static final String MASK_TAG = "#F4F4F0";
    private static final String DUSK_TAG = "#5D5866";
    /** Pale red, blood red, dried blood. */
    private static final Vfx.Palette BLOOD = Vfx.Palette.of(0xFF8A8A, 0xB3121F, 0x3A0008);
    /** Mask white, dusk grey, night. */
    private static final Vfx.Palette DUSK = Vfx.Palette.of(0xF4F4F0, 0x5D5866, 0x1E1B24);
    private static final Particle.DustOptions BLOOD_DUST = new Particle.DustOptions(Color.fromRGB(0xB3121F), 1.0f);
    private static final Particle.DustOptions DUSK_DUST = new Particle.DustOptions(Color.fromRGB(0x5D5866), 0.8f);
    private static final Particle.DustTransition BLOOD_FADE = BLOOD.fade(1.1f);
    private static final Particle.DustTransition DUSK_FADE = DUSK.fade(1.2f);
    private static final Color NO_BACKGROUND = Color.fromARGB(0, 0, 0, 0);

    private static final int MAX_DISPLAYS = 24;
    /** Back turned: the enemy looks at most ~70° off straight away from the mask. */
    private static final double BACK_COS = 0.34;
    /** Walking away: the walk points at most ~65° off straight away. */
    private static final double AWAY_COS = 0.42;
    /** A flee episode ends after this long without fleeing; the next one earns a fresh bonus. */
    private static final long FLEE_EPISODE = 40;
    /** A melee blow still counts as "on a fleeing enemy" this long after it last fled. */
    private static final long FLEE_RECENT = 20;
    /** A marked enemy is cut for a second in which it fled at least this many ticks. */
    private static final int CUT_TICKS = 5;
    private static final long MARK_GRACE = 10;
    private static final double NOD_DEGREES = 30;
    private static final double TURN_DEGREES = 70;
    private static final double CONE_COS = Math.cos(Math.toRadians(45));
    /** Heights over the head (from the seat a riding display starts at) of the anger mark and the shears mark. */
    private static final float ANGRY_ABOVE = 0.55f;
    private static final float MARK_ABOVE = 0.6f;
    /** The shears snap open and shut in steps this long. */
    private static final int SNAP_TICKS = 4;
    /** The mask and shears are sent again once the head turned this far (degrees), by at most {@link #MAX_TURN}. */
    private static final float TURN_EPSILON = 2f;
    private static final float MAX_TURN = 60f;
    /** A turn's interpolation: the 3 ticks a client takes to lerp a remote player's head. */
    private static final int TURN_TICKS = 3;

    // ------------------------------------------------------------------ state
    private final List<Display> live = new ArrayList<>();
    private final Map<UUID, Long> lastFled = new HashMap<>();
    private final Map<UUID, Boolean> fleeBonus = new HashMap<>();
    private final Map<UUID, Mark> marks = new HashMap<>();
    /** "So-so" answers: until when the mask is angry with that enemy, and whether its bonus blow is still due. */
    private final Map<UUID, Long> angryUntil = new HashMap<>();
    private final Map<UUID, LivingEntity> angryBodies = new HashMap<>();
    private final Map<UUID, TextDisplay> angryMarks = new HashMap<>();
    private double chase;
    private boolean fledThisSecond;
    private long askingUntil = Long.MIN_VALUE / 2;
    private @Nullable NamespacedKey speedKey;
    private double speedShown;
    private @Nullable Asking asking;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        lastFled.clear();
        fleeBonus.clear();
        marks.clear();
        angryUntil.clear();
        angryBodies.clear();
        angryMarks.clear();
        speedKey = null;
        asking = null;
        live.clear();
    }

    // ================================================================== who is fleeing

    /** The horizontal direction the held walking keys move {@code player} in, or null when none is held. */
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

    /**
     * True while {@code enemy} has its back to the mask and walks away from it by its own keys (never when pushed,
     * stunned or rooted, see kit {@link Inputs}; the mask's own stun exempts too: a frozen enemy flees nowhere).
     */
    private boolean flees(Player owner, Player enemy) {
        if (Inputs.isExempt(ctx(), enemy, InputKey.FORWARD, null)) {
            return false;
        }
        Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(owner.getLocation().toVector()));
        if (away.lengthSquared() < 1.0E-6) {
            return false;
        }
        if (Motion.horizontalFacing(enemy.getLocation()).dot(away) < BACK_COS) {
            return false;
        }
        Vector walk = walkDirection(enemy, enemy.getCurrentInput());
        return walk != null && walk.dot(away) >= AWAY_COS;
    }

    // ================================================================== passive: a hundred metres in three seconds

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        if (tick % 2 == 0) {
            double range = Math.max(1, params().num("chase_range"));
            for (LivingEntity entity : ctx().enemiesNear(owner, owner.getLocation(), range)) {
                if (entity instanceof Player enemy && !ctx().isConcealedFrom(enemy, owner) && flees(owner, enemy)) {
                    Long last = lastFled.get(enemy.getUniqueId());
                    if (last == null || now - last > FLEE_EPISODE) {
                        fleeBonus.put(enemy.getUniqueId(), true);
                    }
                    lastFled.put(enemy.getUniqueId(), now);
                    fledThisSecond = true;
                }
            }
        }
        if (tick % 20 == 0) {
            double before = chase;
            if (fledThisSecond) {
                chase = Math.min(Math.max(0, params().num("chase_max")), chase + Math.max(0, params().num("chase_step")));
            } else {
                chase = Math.max(0, chase - Math.max(0, params().num("chase_fade")));
            }
            fledThisSecond = false;
            if (chase > before) {
                Fx.sound(owner.getLocation(), Sound.BLOCK_STONE_STEP, 0.8f, 1.6f + Fx.jitter(0.1f));
                Fx.sound(owner.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 0.25f, 1.7f);
            }
            if (chase > 0 && !ctx().hasShownPrompt(owner) && chase != before) {
                owner.sendActionBar(Text.mm("<b><" + BLOOD_TAG + ">100미터 3초</" + BLOOD_TAG + "></b> <white>+"
                        + AbilityInfo.formatNumber(chase) + "%</white>"));
            }
        }
        tickAnger(owner, now);
        updateSpeed(owner, now);
        if (chase > 0 && tick % 3 == 0) {
            Location heel = owner.getLocation().add(0, 0.15, 0)
                    .subtract(Motion.horizontalFacing(owner.getLocation()).multiply(0.4));
            Vfx.dot(heel, Particle.DUST, BLOOD_DUST, false);
            if (chase >= params().num("chase_max") - 1.0E-9) {
                Vfx.dot(heel.clone().add(0, 0.7, 0), Particle.DUST_COLOR_TRANSITION, DUSK_FADE, false);
            }
        }
    }

    /** One speed modifier for everything that makes the mask fast: the chase, the question, the anger. */
    private void updateSpeed(Player owner, long now) {
        double total = chase;
        if (now < askingUntil) {
            total += Math.max(0, params().num("ask_speed"));
        }
        if (angryNear(owner)) {
            total += Math.max(0, params().num("angry_speed"));
        }
        if (Math.abs(total - speedShown) < 1.0E-9) {
            return;
        }
        if (speedKey != null) {
            ctx().removeModifier(owner, speedKey);
            speedKey = null;
        }
        speedShown = total;
        if (total > 0) {
            speedKey = ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, total / 100.0,
                    Operation.MULTIPLY_SCALAR_1, 0);
        }
    }

    private boolean isOwnMelee(EntityDamageByEntityEvent event) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && (event.getCause() == DamageCause.ENTITY_ATTACK || event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK);
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOwnMelee(event)) {
            return;
        }
        long now = ctx().currentTick();
        double extra = 0;
        UUID id = victim.getUniqueId();
        Long fled = lastFled.get(id);
        if (fled != null && now - fled <= FLEE_RECENT && fleeBonus.getOrDefault(id, false)) {
            extra += Math.max(0, params().num("flee_bonus"));
        }
        if (isAngryAt(victim)) {
            extra += Math.max(0, params().num("angry_bonus"));
        }
        if (extra > 0) {
            event.setDamage(event.getDamage() + extra);
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnMelee(event)) {
            return;
        }
        UUID id = victim.getUniqueId();
        long now = ctx().currentTick();
        Long fled = lastFled.get(id);
        Player owner = player();
        if (fled != null && now - fled <= FLEE_RECENT && fleeBonus.getOrDefault(id, false)) {
            fleeBonus.put(id, false);
            Location at = Fx.body(victim);
            Vfx.cloud(ctx(), at, Particle.DUST, 8, 0.3, 0, BLOOD_DUST, false);
            Vfx.dot(at, Particle.SWEEP_ATTACK, null, false);
            Fx.sound(at, Sound.ENTITY_SHEEP_SHEAR, 1.0f, 0.7f);
            pop(owner, victim, "<b><" + BLOOD_TAG + ">잡았다</" + BLOOD_TAG + "></b>", 1.0f);
        }
        if (isAngryAt(victim)) {
            calm(id);
            long stun = params().ticks("angry_stun");
            ctx().runLater(owner, 1, () -> {
                if (Combat.isTargetable(ctx(), owner, victim)) {
                    stun(owner, victim, stun);
                }
            });
            Location at = Fx.body(victim);
            Vfx.burst(ctx(), at, BLOOD, 0.7, Particle.CRIT, false);
            Fx.sound(at, Sound.ENTITY_SHEEP_SHEAR, 1.0f, 0.5f);
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.8f);
            pop(owner, victim, "<b><" + BLOOD_TAG + ">그저 그래?</" + BLOOD_TAG + "></b>", 1.2f);
        }
    }

    /** Stunned: no walking, no jumping, no skills. */
    private void stun(Player owner, LivingEntity target, long ticks) {
        if (ticks <= 0) {
            return;
        }
        ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, ticks);
        ctx().addModifier(owner, target, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, ticks);
        if (target instanceof Player victim) {
            ctx().silence(victim, ticks);
        }
        Loop.repeat(ctx(), owner, 2, 3, Math.max(1, ticks / 3), run -> {
            if (!target.isValid() || target.isDead()) {
                return false;
            }
            Location head = target.getLocation().add(0, target.getHeight() + 0.2, 0);
            Vfx.ring(ctx(), head, 0.4, 8, run * 0.7, Particle.DUST, BLOOD_DUST, false);
            return true;
        });
    }

    // ------------------------------------------------------------------ anger

    private boolean isAngryAt(LivingEntity entity) {
        Long until = angryUntil.get(entity.getUniqueId());
        return until != null && until > ctx().currentTick();
    }

    private boolean angryNear(Player owner) {
        double range = Math.max(1, params().num("chase_range"));
        for (Map.Entry<UUID, Long> entry : angryUntil.entrySet()) {
            LivingEntity body = angryBodies.get(entry.getKey());
            if (body != null && entry.getValue() > ctx().currentTick() && body.isValid() && !body.isDead()
                    && body.getWorld().equals(owner.getWorld())
                    && body.getLocation().distanceSquared(owner.getLocation()) <= range * range) {
                return true;
            }
        }
        return false;
    }

    private void anger(Player owner, LivingEntity target) {
        long ticks = Math.max(1, params().ticks("so_so_seconds"));
        angryUntil.put(target.getUniqueId(), ctx().currentTick() + ticks);
        angryBodies.put(target.getUniqueId(), target);
        TextDisplay mark = angryMarks.get(target.getUniqueId());
        if ((mark == null || !mark.isValid()) && reserve(1)) {
            // Rides the head: no per-tick update, no lag behind a running target.
            mark = keep(Displays.spawn(ctx(), owner, seat(target), TextDisplay.class, 0,
                    viewer -> !ctx().isConcealedFrom(target, viewer), d -> {
                        d.text(Text.mm("<b><" + MASK_TAG + ">?</" + MASK_TAG + "></b>"));
                        d.setBillboard(Display.Billboard.CENTER);
                        d.setBackgroundColor(NO_BACKGROUND);
                        d.setShadowed(true);
                        d.setTransformation(Displays.centered(0, ANGRY_ABOVE, 0, 1.2f, 1.2f, 1.2f, 0, 0, 0));
                    }));
            ride(target, mark);
            angryMarks.put(target.getUniqueId(), mark);
            TextDisplay shown = mark;
            ctx().runLater(owner, 12, () -> {
                if (shown.isValid()) {
                    shown.text(Text.mm("<b><" + BLOOD_TAG + ">화남</" + BLOOD_TAG + "></b>"));
                    Fx.sound(shown.getLocation(), Sound.ENTITY_VEX_CHARGE, 0.7f, 0.6f);
                }
            });
        }
    }

    private void calm(UUID id) {
        angryUntil.remove(id);
        angryBodies.remove(id);
        TextDisplay mark = angryMarks.remove(id);
        if (mark != null && mark.isValid()) {
            Displays.shrinkAway(ctx(), player(), mark, 4);
        }
    }

    private void tickAnger(Player owner, long now) {
        for (UUID id : new ArrayList<>(angryUntil.keySet())) {
            LivingEntity body = angryBodies.get(id);
            if (body == null || !body.isValid() || body.isDead() || angryUntil.get(id) <= now
                    || (body instanceof Player p && !ctx().isAlive(p))) {
                calm(id);
                continue;
            }
            TextDisplay mark = angryMarks.get(id);
            if (mark != null) {
                ride(body, mark);
            }
            if (now % 5 == 0 && !(body instanceof Player p && ctx().isConcealedFrom(p, owner))) {
                Vfx.dot(Fx.body(owner).add(Fx.jitter(0.3f), 0.6, Fx.jitter(0.3f)), Particle.ANGRY_VILLAGER, null,
                        false);
            }
        }
    }

    // ================================================================== active: no use running

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("snip_range"));
        LivingEntity target = ctx().findTarget(owner, range);
        if (target == null) {
            target = inFront(owner, range);
        }
        snipVisuals(owner, target);
        if (target == null) {
            double refund = Math.clamp(params().num("miss_refund"), 0, 100) / 100.0;
            ctx().runLater(owner, 1, () -> {
                double left = ctx().cooldownRemaining(owner);
                if (left > 0 && refund > 0) {
                    ctx().reduceCooldown(owner, left * refund);
                }
            });
            return ActivationResult.success();
        }
        LivingEntity victim = target;
        Combat.hit(ctx(), owner, victim, Math.max(0, params().num("snip_damage")));
        Location at = Fx.body(victim);
        Vfx.burst(ctx(), at, BLOOD, 0.6, Particle.CRIT, false);
        Vfx.dot(at, Particle.SWEEP_ATTACK, null, false);
        Fx.sound(at, Sound.ENTITY_SHEEP_SHEAR, 1.2f, 0.8f);
        Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.3f);
        if (victim.isValid() && !victim.isDead()) {
            Mark old = marks.remove(victim.getUniqueId());
            if (old != null) {
                old.end();
            }
            Mark mark = new Mark(owner, victim);
            marks.put(victim.getUniqueId(), mark);
            mark.start();
        }
        return ActivationResult.success();
    }

    /** The nearest enemy in a cone in front, in reach, in sight and not hidden. */
    private @Nullable LivingEntity inFront(Player owner, double range) {
        Location eye = owner.getEyeLocation();
        Vector facing = Motion.horizontalFacing(owner.getLocation());
        for (LivingEntity entity : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            if (entity instanceof Player enemy && (ctx().isConcealedFrom(enemy, owner) || !owner.canSee(enemy))) {
                continue;
            }
            Vector to = Motion.horizontal(entity.getLocation().toVector().subtract(owner.getLocation().toVector()));
            if ((to.lengthSquared() < 1.0E-6 || to.dot(facing) >= CONE_COS) && owner.hasLineOfSight(entity)) {
                return entity;
            }
        }
        return null;
    }

    /** The shears open and snap shut in front of the mask. */
    private void snipVisuals(Player owner, @Nullable LivingEntity target) {
        Location eye = owner.getEyeLocation();
        Vector facing = Motion.horizontalFacing(owner.getLocation());
        Location front = eye.clone().add(facing.clone().multiply(1.2)).add(0, -0.35, 0);
        float yaw = owner.getLocation().getYaw();
        if (reserve(1)) {
            ItemDisplay shears = keep(Displays.item(ctx(), owner, front, new ItemStack(Material.SHEARS),
                    Displays.centered(0, 0, 0, 1.1f, 1.1f, 1.1f, yaw, 0, 45), 12));
            Displays.animateNextTick(ctx(), owner, shears, Displays.centered(0, 0, 0, 1.3f, 1.3f, 1.3f, yaw, 0, -45),
                    3);
        }
        Vfx.arc(ctx(), front, 1.0, yaw, 120, 14, Particle.DUST_COLOR_TRANSITION, BLOOD_FADE, false);
        Vfx.arc(ctx(), front.clone().add(0, 0.15, 0), 0.85, yaw, 90, 10, Particle.DUST, DUSK_DUST, false);
        Fx.sound(owner.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1.0f, 1.2f + Fx.jitter(0.1f));
        if (target == null) {
            Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 0.8f, 1.0f);
        }
    }

    /** The shears' mark: a red pair of shears over the head; fleeing from the mask is cut every second. */
    private final class Mark implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final @Nullable Player enemy;
        private final long duration;
        private @Nullable ItemDisplay shears;
        private @Nullable PromptHandle rule;
        private int fleeTicks;
        private int cuts;
        private long secondStart = -1;
        private boolean over;

        Mark(Player owner, LivingEntity target) {
            this.owner = owner;
            this.target = target;
            this.enemy = target instanceof Player p ? p : null;
            this.duration = Math.max(1, params().ticks("mark_duration"));
        }

        void start() {
            if (reserve(1)) {
                // Rides the head; its spin and snap step every SNAP_TICKS, interpolated over the whole step.
                shears = keep(Displays.spawn(ctx(), owner, seat(target), ItemDisplay.class, 0,
                        viewer -> !ctx().isConcealedFrom(target, viewer), d -> {
                            d.setItemStack(new ItemStack(Material.SHEARS));
                            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                            d.setTransformation(Displays.centered(0, MARK_ABOVE, 0, 0.1f, 0.1f, 0.1f, 0, 0, 45));
                        }));
                ride(target, shears);
                Displays.outline(shears, Color.fromRGB(0xB3121F));
            }
            if (enemy != null) {
                Prompt prompt = Prompt.builder(Text.mm("<b><" + BLOOD_TAG + ">✂ 가위 자국</" + BLOOD_TAG + "></b> <white>"
                                + "등을 보이고 도망치면 1초마다 베입니다</white> <gray>— 맞서면 괜찮아요</gray>"), duration)
                        .priority(Prompt.NORMAL)
                        .maxWait(20)
                        .onEnd((handle, reason) -> {
                            if (reason != PromptEndReason.EXPIRED) {
                                end();
                            }
                        })
                        .build();
                rule = ctx().prompt(owner, enemy, prompt);
            }
            if (!over) {
                Loop.repeat(ctx(), owner, 1, 1, duration + 40, this, this::end);
            }
        }

        private Location spot() {
            return target.getLocation().add(0, target.getHeight() + MARK_ABOVE, 0);
        }

        @Override
        public boolean test(long run) {
            if (over) {
                return false;
            }
            if (!target.isValid() || target.isDead() || (enemy != null && !ctx().isAlive(enemy)) || run >= duration
                    + 20) {
                end();
                return false;
            }
            ItemDisplay pair = shears;
            if (pair != null && pair.isValid()) {
                ride(target, pair);
                if (run % SNAP_TICKS == 0) {
                    float roll = run % (2 * SNAP_TICKS) == 0 ? 20 : 70;
                    Displays.animate(pair, Displays.centered(0, MARK_ABOVE, 0, 0.7f, 0.7f, 0.7f, run * 6f, 0, roll),
                            SNAP_TICKS);
                }
            }
            if (run % 5 == 0 && !(enemy != null && ctx().isConcealedFrom(enemy, owner))) {
                Vfx.dot(spot().add(Fx.jitter(0.2f), -0.25, Fx.jitter(0.2f)), Particle.DUST, BLOOD_DUST, false);
            }
            if (enemy == null) {
                return run < duration;
            }
            PromptHandle handle = rule;
            if (handle == null || !handle.isActive()) {
                return false;
            }
            long now = ctx().currentTick();
            if (!handle.isShown() || now < Inputs.judgeFrom(ctx(), handle, MARK_GRACE)) {
                secondStart = -1;
                fleeTicks = 0;
                return true;
            }
            if (secondStart < 0) {
                secondStart = now;
            }
            if (flees(owner, enemy)) {
                fleeTicks++;
            }
            if (now - secondStart >= 20) {
                if (fleeTicks >= CUT_TICKS && cuts < Math.max(0, params().integer("mark_max"))) {
                    cut(enemy);
                }
                secondStart = now;
                fleeTicks = 0;
            }
            return true;
        }

        /** "어디 가?" */
        private void cut(Player victim) {
            cuts++;
            Location at = Fx.body(victim);
            Location from = owner.getEyeLocation();
            Vfx.line(ctx(), from, at, 3, Particle.DUST_COLOR_TRANSITION, BLOOD_FADE, false);
            Vfx.dot(at, Particle.SWEEP_ATTACK, null, false);
            Vfx.cloud(ctx(), at, Particle.DUST, 6, 0.3, 0, BLOOD_DUST, false);
            Fx.sound(at, Sound.ENTITY_SHEEP_SHEAR, 1.0f, 1.0f + cuts * 0.08f);
            Fx.sound(owner.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 0.5f, 1.5f);
            pop(owner, victim, "<b><" + BLOOD_TAG + ">어디 가?</" + BLOOD_TAG + "></b>", 1.1f);
            Combat.hit(ctx(), owner, victim, Math.max(0, params().num("mark_damage")));
        }

        void end() {
            if (over) {
                return;
            }
            over = true;
            if (marks.get(target.getUniqueId()) == this) {
                marks.remove(target.getUniqueId());
            }
            ItemDisplay pair = shears;
            if (pair != null && pair.isValid()) {
                Displays.shrinkAway(ctx(), owner, pair, 4);
            }
            PromptHandle handle = rule;
            if (handle != null) {
                handle.cancel();
            }
        }
    }

    // ================================================================== ultimate: "am I pretty?"

    /** The three answers. */
    private enum Answer {
        YES, NO, SO_SO
    }

    @Override
    public ActivationResult onUltimate() {
        if (asking != null) {
            return ActivationResult.fail("이미 묻고 있습니다");
        }
        Player owner = player();
        double radius = Math.max(1, params().num("question_radius"));
        List<LivingEntity> targets = new ArrayList<>();
        for (LivingEntity entity : ctx().enemiesNear(owner, owner.getLocation(), radius)) {
            if (entity instanceof Player enemy && ctx().isConcealedFrom(enemy, owner)) {
                continue;
            }
            targets.add(entity);
        }
        if (targets.isEmpty()) {
            return ActivationResult.fail("주위 " + AbilityInfo.formatNumber(radius) + "블록 안에 물어볼 적이 없습니다");
        }
        Asking question = new Asking(owner);
        asking = question;
        question.start(targets);
        return ActivationResult.success();
    }

    /**
     * The question itself: the mask over the face, the shears in hand, an answer from everyone near. Mask and shears
     * ride her as passengers (so they stay on her face and in her hand however fast she runs), their offsets from the
     * seat in the transformation, sent again only when her head turned (by at most {@link #MAX_TURN} per update, so
     * a quick turn never swings them through her head) or the shears snap.
     */
    private final class Asking {

        private final Player owner;
        private final long window;
        private @Nullable BlockDisplay mask;
        private @Nullable ItemDisplay shears;
        private boolean unmasked;
        private int open;
        private float sentYaw = Float.NaN;
        private double sentHeight = -1;
        private long sentSnap = Long.MIN_VALUE;

        Asking(Player owner) {
            this.owner = owner;
            this.window = Math.max(1, params().ticks("answer_seconds"));
        }

        void start(List<LivingEntity> targets) {
            askingUntil = ctx().currentTick() + window + 8;
            java.util.function.Predicate<Player> others = viewer -> !viewer.equals(owner)
                    && !ctx().isConcealedFrom(owner, viewer);
            if (reserve(2)) {
                // They outlive the loop (window + 30) and its shrink.
                float yaw = owner.getLocation().getYaw();
                Location at = seat(owner);
                mask = keep(Displays.block(ctx(), owner, at, Material.WHITE_CONCRETE.createBlockData(),
                        maskPose(yaw, 0.05f, 0, 0), window + 40, others));
                shears = keep(Displays.item(ctx(), owner, at, new ItemStack(Material.SHEARS),
                        shearsPose(yaw, 0.02f, 30), window + 40, others));
                ride(owner, mask);
                ride(owner, shears);
            }
            Location at = owner.getLocation().add(0, 1.6, 0);
            Fx.sound(at, Sound.ENTITY_VEX_AMBIENT, 1.0f, 0.5f);
            Fx.sound(at, Sound.ENTITY_WITCH_AMBIENT, 0.9f, 1.4f);
            Fx.sound(at, Sound.BLOCK_BELL_RESONATE, 0.6f, 0.5f);
            Vfx.shockwave(ctx(), owner, owner.getLocation().add(0, 0.1, 0), 0.5,
                    Math.max(1, params().num("question_radius")), 8, Particle.DUST_COLOR_TRANSITION, DUSK_FADE, true);
            Vfx.cloud(ctx(), at, Particle.LARGE_SMOKE, 8, 0.5, 0.02, null, false);
            for (LivingEntity target : targets) {
                open++;
                new Question(owner, this, target).start();
            }
            Loop.repeat(ctx(), owner, 0, 1, window + 30, run -> {
                if (asking != this) {
                    return false;
                }
                pose(run);
                if (run % 4 == 0) {
                    Vfx.cloud(ctx(), owner.getLocation().add(0, 0.3, 0), Particle.DUST, 3, 0.4, 0, DUSK_DUST, false);
                }
                return true;
            }, this::finish);
        }

        /** Where the mask sits over the face, from the seat. */
        private Vector maskSpot(float yaw) {
            Body frame = Body.standing(owner);
            double scale = frame.height() / 1.8;
            return facing(yaw).multiply(0.34 * scale).setY(frame.eyeHeight() - 0.12 * scale - frame.height());
        }

        /** The mask over the face (its offset from the seat), {@code pitch}/{@code roll} tilted. */
        private Transformation maskPose(float yaw, float grow, float pitch, float roll) {
            double scale = Body.standing(owner).height() / 1.8;
            Vector at = maskSpot(yaw);
            return Displays.transform((float) at.getX(), (float) at.getY(), (float) at.getZ(),
                    (float) (0.42 * scale * grow), (float) (0.3 * scale * grow), (float) (0.04 * scale * grow), yaw, pitch,
                    roll);
        }

        /** The shears in the right hand (their offset from the seat), opened by {@code roll} degrees. */
        private Transformation shearsPose(float yaw, float grow, float roll) {
            Body frame = Body.standing(owner);
            double scale = frame.height() / 1.8;
            Vector facing = facing(yaw);
            Vector right = new Vector(-facing.getZ(), 0, facing.getX());
            Vector at = facing.multiply(0.45 * scale).add(right.multiply(0.4 * scale)).setY((1.0 - 1.8) * scale);
            float s = (float) (0.9 * scale * grow);
            return Displays.centered((float) at.getX(), (float) at.getY(), (float) at.getZ(), s, s, s, yaw, 0, roll);
        }

        /** Keeps mask and shears seated and turned with her head; the shears snap every {@link #SNAP_TICKS}. */
        private void pose(long run) {
            BlockDisplay face = mask;
            ItemDisplay pair = shears;
            for (Display part : new Display[] {unmasked ? null : face, pair}) {
                if (part != null) {
                    ride(owner, part);
                }
            }
            float yaw = owner.getLocation().getYaw();
            double height = Body.standing(owner).height();
            long snap = run / SNAP_TICKS;
            boolean turned = Float.isNaN(sentYaw) || Math.abs(wrap(yaw - sentYaw)) >= TURN_EPSILON
                    || Math.abs(height - sentHeight) > 1.0E-3;
            if (turned) {
                sentYaw = Float.isNaN(sentYaw) ? yaw : turnToward(sentYaw, yaw, MAX_TURN);
                sentHeight = height;
            }
            if (face != null && face.isValid() && !unmasked && turned) {
                Displays.animate(face, maskPose(sentYaw, 1, 0, 0), TURN_TICKS);
            }
            if (pair != null && pair.isValid() && (turned || snap != sentSnap)) {
                float roll = snap % 2 == 0 ? 30 : 75;
                Displays.animate(pair, shearsPose(sentYaw, 1, roll), turned ? TURN_TICKS : SNAP_TICKS);
                if (snap != sentSnap && snap % 2 == 0) {
                    Fx.sound(owner.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 0.5f, 1.5f);
                }
            }
            sentSnap = snap;
        }

        /** "이래도?" — the mask comes off (once), drops where she stood, and the red grin shows. */
        void unmask() {
            if (unmasked) {
                return;
            }
            unmasked = true;
            BlockDisplay face = mask;
            if (face != null && face.isValid()) {
                float yaw = Float.isNaN(sentYaw) ? owner.getLocation().getYaw() : sentYaw;
                Vector at = maskSpot(yaw);
                float scale = (float) (Body.standing(owner).height() / 1.8);
                face.leaveVehicle();
                // Off the face: it falls from the seat it was left at to the ground.
                Displays.animate(face, Displays.transform((float) at.getX(), (float) (0.1 - owner.getHeight()),
                        (float) at.getZ(), 0.42f * scale, 0.3f * scale, 0.04f * scale, yaw, 80, 30), 8);
                ctx().runLater(owner, 9, () -> {
                    if (face.isValid()) {
                        Displays.shrinkAway(ctx(), owner, face, 4);
                    }
                });
            }
            pop(owner, owner, "<b><" + BLOOD_TAG + ">이래도?</" + BLOOD_TAG + "></b>", 1.4f);
            Location mouth = owner.getEyeLocation().add(Motion.horizontalFacing(owner.getLocation()).multiply(0.4))
                    .add(0, -0.15, 0);
            Vfx.line(ctx(), mouth.clone().add(-0.3, 0.05, 0), mouth.clone().add(0.3, 0.05, 0), 12, Particle.DUST,
                    BLOOD_DUST, false);
            Fx.sound(mouth, Sound.ENTITY_WITCH_CELEBRATE, 1.0f, 0.7f);
            Fx.sound(mouth, Sound.ENTITY_GHAST_SCREAM, 0.4f, 1.6f);
        }

        void answered() {
            open = Math.max(0, open - 1);
            if (open == 0) {
                askingUntil = Math.min(askingUntil, ctx().currentTick() + 4);
            }
        }

        void finish() {
            if (asking == this) {
                asking = null;
            }
            for (Display display : new Display[] {unmasked ? null : mask, shears}) {
                if (display != null && display.isValid()) {
                    Displays.shrinkAway(ctx(), owner, display, 4);
                }
            }
        }
    }

    /**
     * One enemy's question: its answer is the first of a nod, a turn of the head or a crouch, made by its own input
     * once the question is on its screen. The head is judged against where it looked when the judging began (the
     * current showing, plus {@code answer_grace} and the ping slack, kit {@link Inputs#judgeFrom}): a camera already
     * swinging in a fight as the question appears is no answer. A forced view (pushed, teleported, held by someone
     * else's hard control) answers nothing; the head is looked at afresh afterwards. A crouch counts once it can be a
     * reaction to the shown question (from the showing plus the ping slack).
     */
    private final class Question implements LongPredicate {

        private final Player owner;
        private final Asking asking;
        private final LivingEntity target;
        private final @Nullable Player enemy;
        private @Nullable PromptHandle handle;
        private float baseYaw;
        private float basePitch;
        /** True once the head's starting look was taken for the current showing. */
        private boolean based;
        private boolean done;

        Question(Player owner, Asking asking, LivingEntity target) {
            this.owner = owner;
            this.asking = asking;
            this.target = target;
            this.enemy = target instanceof Player p ? p : null;
        }

        void start() {
            long window = Math.max(1, params().ticks("answer_seconds"));
            if (enemy == null) {
                ctx().runLater(owner, window, () -> resolve(Answer.NO));
                return;
            }
            String options = "<gray>▼ 끄덕이기 = 예 · ↔ 고개 돌리기 = 아니오 · 웅크리기 = 그저 그래요</gray>";
            Prompt prompt = Prompt.builder(Text.mm("<b><" + BLOOD_TAG + ">나 예뻐?</" + BLOOD_TAG + "></b> " + options),
                            window)
                    .priority(Prompt.HIGH)
                    .overhead(Text.mm("<b><" + BLOOD_TAG + ">나 예뻐?</" + BLOOD_TAG + "></b>"))
                    .title(Text.mm("<b><" + BLOOD_TAG + ">나 예뻐?</" + BLOOD_TAG + "></b>"), Text.mm(options))
                    .maxWait(10)
                    .maxPause(10)
                    .onEnd(this::ended)
                    .build();
            handle = ctx().prompt(owner, enemy, prompt);
            if (!done) {
                Loop.repeat(ctx(), owner, 1, 1, window + 30, this);
            }
        }

        @Override
        public boolean test(long run) {
            if (done) {
                return false;
            }
            PromptHandle shown = handle;
            if (enemy == null || shown == null || !shown.isActive()) {
                return false;
            }
            if (!shown.isShown()) {
                based = false; // pushed off the screen: the head is looked at afresh once it is back
                return true;
            }
            Answer answer = read(enemy, shown);
            if (answer != null) {
                resolve(answer);
                shown.cancel();
                return false;
            }
            return true;
        }

        /** The answer the target's own input gave during the current showing of {@code shown}, or null. */
        private @Nullable Answer read(Player victim, PromptHandle shown) {
            long now = ctx().currentTick();
            // A crouch pressed once the question could have been seen (the showing plus the ping slack).
            if (ctx().lastPressTick(victim, InputKey.SNEAK) >= Inputs.judgeFrom(ctx(), shown, 0)) {
                return Answer.SO_SO;
            }
            if (now < Inputs.judgeFrom(ctx(), shown, params().ticks("answer_grace"))) {
                return null;
            }
            if (ctx().wasDisplaced(victim, Inputs.DISPLACED_TICKS) || ctx().isMovementLocked(victim, owner)) {
                based = false; // a forced view is no answer
                return null;
            }
            Location look = victim.getLocation();
            if (!based) {
                based = true;
                baseYaw = look.getYaw();
                basePitch = look.getPitch();
                return null;
            }
            if (look.getPitch() - basePitch >= NOD_DEGREES) {
                return Answer.YES;
            }
            double turned = Math.abs(wrap(look.getYaw() - baseYaw));
            return turned >= TURN_DEGREES ? Answer.NO : null;
        }

        private void ended(PromptHandle prompt, PromptEndReason reason) {
            if (done) {
                return;
            }
            if (reason == PromptEndReason.EXPIRED) {
                resolve(Answer.NO); // silence is a "no"
            } else {
                done = true;
                asking.answered();
            }
        }

        private void resolve(Answer answer) {
            if (done) {
                return;
            }
            done = true;
            asking.answered();
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return;
            }
            switch (answer) {
                case YES -> yes();
                case NO -> no();
                case SO_SO -> soSo();
            }
        }

        /** "이래도?" — what is under the mask: a wound that keeps bleeding. */
        private void yes() {
            asking.unmask();
            pop(owner, target, "<b><white>예</white></b>", 1.0f);
            Location at = Fx.body(target);
            Vfx.burst(ctx(), at, BLOOD, 0.7, Particle.DAMAGE_INDICATOR, false);
            Fx.sound(at, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 1.0f, 0.7f);
            Combat.hit(ctx(), owner, target, Math.max(0, params().num("yes_damage")));
            long bleed = params().ticks("bleed_seconds");
            int beats = (int) (bleed / 20);
            if (beats > 0) {
                Loop.repeat(ctx(), owner, 20, 20, beats, run -> {
                    if (!Combat.isTargetable(ctx(), owner, target)) {
                        return false;
                    }
                    Location body = Fx.body(target);
                    Vfx.cloud(ctx(), body, Particle.DUST, 6, 0.25, 0, BLOOD_DUST, false);
                    Vfx.cloud(ctx(), body, Particle.DRIPPING_DRIPSTONE_LAVA, 2, 0.2, 0, null, false);
                    Fx.sound(body, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 0.6f, 1.2f);
                    Combat.hit(ctx(), owner, target, Math.max(0, params().num("bleed_damage")));
                    return true;
                });
            }
        }

        /** "아니오" — the shears. */
        private void no() {
            pop(owner, target, "<b><white>아니오</white></b>", 1.0f);
            Location at = Fx.body(target);
            Vfx.line(ctx(), owner.getEyeLocation(), at, 3, Particle.DUST_COLOR_TRANSITION, BLOOD_FADE, true);
            Vfx.burst(ctx(), at, BLOOD, 0.9, Particle.CRIT, true);
            Vfx.dot(at, Particle.SWEEP_ATTACK, null, false);
            Fx.sound(at, Sound.ENTITY_SHEEP_SHEAR, 1.2f, 0.6f);
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.7f);
            Combat.hit(ctx(), owner, target, Math.max(0, params().num("no_damage")));
            if (Combat.isTargetable(ctx(), owner, target)) {
                stun(owner, target, params().ticks("no_stun"));
            }
        }

        /** "그저 그래요" — she is lost for words, then angry. */
        private void soSo() {
            pop(owner, target, "<b><" + MASK_TAG + ">그저 그래요</" + MASK_TAG + "></b>", 1.0f);
            pop(owner, owner, "<b><" + DUSK_TAG + ">…?</" + DUSK_TAG + "></b>", 1.2f);
            Fx.sound(owner.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 0.6f);
            Fx.sound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            anger(owner, target);
        }
    }

    private static double wrap(double degrees) {
        double d = degrees % 360;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

    // ================================================================== helpers

    /** Where a display riding {@code body} sits before its transformation: the top of the body's box. */
    private static Location seat(LivingEntity body) {
        return body.getLocation().add(0, body.getHeight(), 0);
    }

    /**
     * Seats {@code part} on {@code body} as a passenger (again, after anything dismounted it: a death, a respawn), so
     * every client moves it with the body's own model. A mounted part is never teleported (that would dismount it);
     * its transformation holds its offset from the seat.
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

    /** {@code from} turned toward {@code to} the short way, by at most {@code max} degrees. */
    private static float turnToward(float from, float to, float max) {
        return from + (float) Math.clamp(wrap(to - from), -max, max);
    }

    /** A word popping up over {@code target}'s head (hidden from those who cannot see it). */
    private void pop(Player owner, LivingEntity target, String mini, float scale) {
        if (!reserve(1)) {
            return;
        }
        Location at = target.getLocation().add(0, target.getHeight() + 1.9, 0);
        TextDisplay text = keep(Displays.spawn(ctx(), owner, at, TextDisplay.class, 24,
                viewer -> !ctx().isConcealedFrom(target, viewer), d -> {
                    d.text(Text.mm(mini));
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setBackgroundColor(NO_BACKGROUND);
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

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
