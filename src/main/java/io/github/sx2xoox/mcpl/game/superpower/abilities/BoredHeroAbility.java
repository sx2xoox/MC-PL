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
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 심드렁 히어로 — the hero who is far too strong for any fight to be interesting. It shrugs off (and barely returns)
 * the blows of whoever has not yet hurt it much, until that enemy finally catches its interest; its light flick lands a
 * moment late and sends the victim straight up (or, once it is interested, slams it into the ground), and as its
 * ultimate it finally puts some effort in: a single blow at a single enemy, as heavy as that enemy is healthy.
 *
 * <p>Look (stretched t-shirt grey, yawn sky blue, slipper navy): it yawns now and then over its head, a big "!" pops
 * over an enemy that caught its interest (and a small one stays there), the flick is a crisp "딱!" with a little ring on
 * the forehead and a late "띠잉—" that shoots the victim up a streak of light to a twinkle in the sky. The hard blow
 * is staged with focus lines rushing in and its one line over its head, then a white flash, a shock ring and a star
 * twinkling high up where the victim flew.
 */
public final class BoredHeroAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("boredhero", "심드렁 히어로", Material.BAKED_POTATO)
            .color(TextColor.color(0xA8D8F0))
            .difficulty(1)
            .passive("관심 없음",
                    "적마다 따로, 나에게 {interest_threshold} 피해를 주기 전까지는 관심이 없어 그 적에게 받는 피해가 "
                            + "{bored_guard}%, 주는 피해가 {bored_weak}% 줄어듭니다. 그 뒤로는 흥미가 생겨 주는 피해가 "
                            + "{interested_bonus}% 늘고, {interest_reset}초간 서로 피해가 없으면 다시 식습니다.")
            .active("가볍게 딱밤",
                    "앞의 적({flick_range}블록 이내)에게 딱밤을 놓아 {flick_damage} 피해를 주고, {flick_delay}초 뒤 약 "
                            + "{flick_height}블록 곧게 쏘아 올립니다(떨어지며 받는 낙하 피해 최대 {flick_fall_max}도 "
                            + "내 공격). 흥미가 생긴 적은 대신 땅에 처박혀 {slam_damage} 피해를 입고 {slam_stun}초간 "
                            + "기절합니다. 앞에 적이 없으면 쿨타임이 돌지 않습니다.",
                    8)
            .ultimate("이번엔 좀 세게",
                    "{hard_windup}초간 소매를 걷어붙인 뒤(그동안 밀려나지 않음) 앞의 첫 적({hard_range}블록 이내)에게 "
                            + "한 방을 날려 지금 체력의 {hard_share}% + {hard_bonus} 피해(최소 {hard_min}, 최대 "
                            + "{hard_max})를 주고 멀리 날려 버립니다(낙하 피해 최대 {flick_fall_max}도 내 공격). 앞에 "
                            + "아무도 없으면 궁극기 게이지 {hard_refund}%를 돌려받습니다.")
            .param("interest_threshold", 6)
            .param("bored_guard", 20)
            .param("bored_weak", 15)
            .param("interested_bonus", 20)
            .param("interest_reset", 15)
            .param("flick_range", 3)
            .param("flick_damage", 1)
            .param("flick_delay", 0.5)
            .param("flick_height", 6)
            .param("flick_fall_max", 4)
            .param("slam_damage", 3)
            .param("slam_stun", 1)
            .param("hard_windup", 1)
            .param("hard_range", 4)
            .param("hard_share", 50)
            .param("hard_bonus", 2)
            .param("hard_min", 5)
            .param("hard_max", 10)
            .param("hard_refund", 50)
            .build();

    private static final String SKY_TAG = "#A8D8F0";
    private static final String GREY_TAG = "#9AA0A6";
    private static final String NAVY_TAG = "#2B3A67";

    // ------------------------------------------------------------------ look
    /** White, yawn sky blue, slipper navy. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xA8D8F0, 0x2B3A67);
    private static final Color WHITE = Color.fromRGB(0xFFFFFF);
    private static final Color SKY = Color.fromRGB(0xA8D8F0);
    private static final Particle.DustOptions SKY_DUST = new Particle.DustOptions(SKY, 0.9f);
    private static final Particle.DustOptions GREY_DUST = new Particle.DustOptions(Color.fromRGB(0x9AA0A6), 0.8f);
    private static final Particle.DustOptions LINE_DUST = new Particle.DustOptions(WHITE, 0.7f);
    private static final Particle.DustOptions NAVY_DUST = new Particle.DustOptions(Color.fromRGB(0x2B3A67), 1.1f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.2f);

    private static final int MAX_DISPLAYS = 24;
    private static final double TAU = Math.PI * 2;
    private static final double MAX_SPEED = 2.5;
    /** Vanilla's safe fall distance, for entities without the attribute. */
    private static final double SAFE_FALL = 3.0;

    // passive
    private static final long INTEREST_PERIOD = 10;
    private static final long YAWN_PERIOD = 120;
    private static final double MARK_ABOVE = 0.6;

    // active: flick
    /** Enemies within this angle of the look are in reach of the flick (when the aim itself missed). */
    private static final double FLICK_CONE_COS = Math.cos(Math.toRadians(35));
    private static final double SLAM_DOWN = -1.2;
    private static final long FLIGHT_MAX_TICKS = 140;
    private static final long FLIGHT_MIN_TICKS = 3;
    /** A flight never seen off the ground within this long (a ceiling right above) has no fall to credit. */
    private static final long FLIGHT_NEVER_AIRBORNE_TICKS = 12;

    // ultimate: the hard blow
    private static final double HARD_CONE_COS = Math.cos(Math.toRadians(40));
    private static final double PUNCH_SPEED = 1.7;
    private static final double PUNCH_LIFT = 0.85;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    /** Interest per rival (the participant behind a hit, or the entity itself), by id. */
    private final Map<UUID, Interest> interest = new HashMap<>();
    /**
     * True while damage that the interest never scales lands: the ultimate's blow and every credited fall (its cap is
     * the whole fall damage).
     */
    private boolean unscaled;
    private boolean windingUp;
    private long nextYawn = Long.MIN_VALUE / 2;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        nextYawn = ctx().currentTick() + YAWN_PERIOD / 2;
    }

    @Override
    public void onRevoke() {
        interest.clear();
        live.clear();
        unscaled = false;
        windingUp = false;
    }

    // ------------------------------------------------------------------ passive: not interested

    /** How the hero feels about one enemy. */
    private static final class Interest {

        final LivingEntity rival;
        double taken;
        boolean interested;
        long lastExchange;
        @Nullable TextDisplay mark;

        Interest(LivingEntity rival, long now) {
            this.rival = rival;
            this.lastExchange = now;
        }
    }

    /** The participant behind {@code entity} (a player, or the owner of a summon or projectile), else the entity. */
    private @Nullable LivingEntity rivalOf(@Nullable Entity entity) {
        if (entity == null) {
            return null;
        }
        Player behind = ctx().resolveAttacker(entity);
        if (behind != null) {
            return behind;
        }
        return entity instanceof LivingEntity living ? living : null;
    }

    private boolean isInterested(LivingEntity entity) {
        LivingEntity rival = rivalOf(entity);
        Interest feeling = rival == null ? null : interest.get(rival.getUniqueId());
        return feeling != null && feeling.interested;
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Player owner = player();
        LivingEntity rival = rivalOf(byEntity.getDamager());
        if (rival == null || rival.equals(owner) || !ctx().isEnemy(owner, rival)) {
            return;
        }
        long now = ctx().currentTick();
        Interest feeling = interest.computeIfAbsent(rival.getUniqueId(), id -> new Interest(rival, now));
        if (!feeling.interested) {
            double guard = Math.clamp(params().num("bored_guard") / 100.0, 0.0, 1.0);
            event.setDamage(event.getDamage() * (1 - guard));
            if (event.getDamage() > 0 && now % 4 == 0) {
                Vfx.dot(owner.getEyeLocation().add(0, 0.4, 0), Particle.DUST, GREY_DUST, false);
            }
        }
        feeling.lastExchange = now;
        feeling.taken += Math.max(0, event.getFinalDamage());
        if (!feeling.interested && feeling.taken > params().num("interest_threshold") - 1.0E-9) {
            feeling.interested = true;
            intrigued(owner, feeling);
        }
    }

    /** The interest multiplier: only changes the damage (the victim may still cancel the hit). */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || unscaled) {
            return;
        }
        double scale = isInterested(victim)
                ? 1 + Math.max(0, params().num("interested_bonus")) / 100.0
                : 1 - Math.clamp(params().num("bored_weak") / 100.0, 0.0, 1.0);
        event.setDamage(event.getDamage() * scale);
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        LivingEntity rival = rivalOf(victim);
        if (rival == null) {
            return;
        }
        long now = ctx().currentTick();
        interest.computeIfAbsent(rival.getUniqueId(), id -> new Interest(rival, now)).lastExchange = now;
    }

    /** "…흥미가 생겼다": a big "!" pops over the enemy, a small one stays there while the interest lasts. */
    private void intrigued(Player owner, Interest feeling) {
        LivingEntity rival = feeling.rival;
        Location head = markPoint(rival);
        Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.6f);
        Fx.sound(owner.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 0.6f);
        say(owner, "<i><" + SKY_TAG + ">…흥미가 생겼다</" + SKY_TAG + "></i>", 30);
        owner.sendActionBar(Text.mm("<b><" + SKY_TAG + ">!</" + SKY_TAG + "></b> <white><name></white><gray>에게 흥미가 "
                + "생겼다 — 주는 피해 +" + AbilityInfo.formatNumber(params().num("interested_bonus")) + "%</gray>",
                Placeholder.component("name", rival.name())));
        if (rival instanceof Player victim) {
            victim.sendActionBar(Text.mm("<b><" + SKY_TAG + ">!</" + SKY_TAG + "></b> <white><name></white><gray>이(가) "
                    + "당신에게 흥미를 보입니다…</gray>", Placeholder.component("name", owner.name())));
        }
        if (!seen(owner, rival)) {
            return;
        }
        if (reserve(1)) {
            TextDisplay big = keep(Displays.text(ctx(), owner, head, Text.mm("<b><" + SKY_TAG + ">!</" + SKY_TAG
                    + "></b>"), 0.4f, 24));
            big.setTeleportDuration(1);
            Displays.animateNextTick(ctx(), owner, big, Displays.centered(0, 0.3f, 0, 3.2f, 3.2f, 3.2f, 0, 0, 0), 4);
            ctx().runLater(owner, 14, () -> {
                if (big.isValid()) {
                    Displays.shrinkAway(ctx(), owner, big, 6);
                }
            });
        }
        Vfx.burst(ctx(), head, PALETTE, 0.4, Particle.END_ROD, false);
        if (reserve(1)) {
            TextDisplay small = keep(Displays.text(ctx(), owner, head, Text.mm("<b><" + SKY_TAG + ">!</" + SKY_TAG
                    + "></b>"), 0.1f, 0));
            small.setTeleportDuration(1);
            Displays.animateNextTick(ctx(), owner, small, Displays.centered(0, 0, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0), 4);
            feeling.mark = small;
        }
    }

    private static Location markPoint(LivingEntity rival) {
        return rival.getLocation().add(0, rival.getHeight() + MARK_ABOVE, 0);
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        boolean anyInterest = false;
        Iterator<Interest> feelings = interest.values().iterator();
        while (feelings.hasNext()) {
            Interest feeling = feelings.next();
            LivingEntity rival = feeling.rival;
            boolean gone = !rival.isValid() || rival.isDead()
                    || rival instanceof Player player && !ctx().isAlive(player);
            if (gone || (tick % INTEREST_PERIOD == 0
                    && now - feeling.lastExchange > Math.max(1, params().ticks("interest_reset")))) {
                if (feeling.interested && !gone) {
                    bored(owner, feeling);
                }
                dropMark(owner, feeling);
                feelings.remove();
                continue;
            }
            anyInterest |= feeling.interested;
            TextDisplay mark = feeling.mark;
            if (mark != null && mark.isValid()) {
                boolean shown = seen(owner, rival) && rival.getWorld().equals(mark.getWorld());
                mark.setViewRange(shown ? 1.5f : 0f);
                if (shown) {
                    Location at = markPoint(rival);
                    at.add(0, Math.sin(tick * 0.2) * 0.05, 0);
                    Displays.moveTo(mark, at);
                }
            }
        }
        if (!anyInterest && !windingUp && now >= nextYawn) {
            nextYawn = now + YAWN_PERIOD;
            yawn(owner);
        }
    }

    /** The interest is gone again: the "!" fades and the hero is bored once more. */
    private void bored(Player owner, Interest feeling) {
        if (seen(owner, feeling.rival)) {
            Vfx.dot(markPoint(feeling.rival), Particle.POOF, null, false);
        }
        say(owner, "<i><" + GREY_TAG + ">…시시해</" + GREY_TAG + "></i>", 24);
        Fx.sound(owner.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.4f, 0.7f);
        nextYawn = ctx().currentTick() + 20;
    }

    private void dropMark(Player owner, Interest feeling) {
        TextDisplay mark = feeling.mark;
        feeling.mark = null;
        if (mark != null && mark.isValid()) {
            Displays.shrinkAway(ctx(), owner, mark, 4);
        }
    }

    /** A long yawn over its head and a puff of breath in front of its mouth. */
    private void yawn(Player owner) {
        say(owner, "<" + GREY_TAG + ">하아암~</" + GREY_TAG + ">", 34);
        Location eye = owner.getEyeLocation();
        Location mouth = eye.clone().add(Motion.horizontalFacing(eye).multiply(0.4)).subtract(0, 0.15, 0);
        Vfx.cloud(ctx(), mouth, Particle.CLOUD, 3, 0.08, 0.01, null, false);
        Vfx.cloud(ctx(), mouth, Particle.DUST, 4, 0.15, 0, SKY_DUST, false);
        Fx.sound(eye, Sound.ENTITY_PLAYER_BREATH, 0.6f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ENTITY_FOX_SLEEP, 0.3f, 1.2f + Fx.jitter(0.1f));
    }

    /** A short line of text over the hero's head that everyone can read. */
    private void say(Player owner, String mini, long ticks) {
        if (!reserve(1)) {
            return;
        }
        Location at = owner.getLocation().add(0, owner.getHeight() + 0.55, 0);
        TextDisplay line = keep(Displays.text(ctx(), owner, at, Text.mm(mini), 0.2f, ticks + 6));
        line.setTeleportDuration(2);
        Displays.animateNextTick(ctx(), owner, line, Displays.centered(0, 0, 0, 0.8f, 0.8f, 0.8f, 0, 0, 0), 4);
        Loop.repeat(ctx(), owner, 1, 1, ticks, run -> {
            if (!line.isValid()) {
                return false;
            }
            Displays.moveTo(line, owner.getLocation().add(0, owner.getHeight() + 0.55 + run * 0.006, 0));
            return true;
        }, () -> {
            if (line.isValid()) {
                Displays.shrinkAway(ctx(), owner, line, 5);
            }
        });
    }

    // ------------------------------------------------------------------ active: light flick

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(0.5, params().num("flick_range"));
        LivingEntity target = inFront(owner, range, FLICK_CONE_COS);
        if (target == null) {
            return ActivationResult.fail("딱밤을 놓을 이마가 앞에 없습니다");
        }
        boolean slam = isInterested(target);
        flick(owner, target, slam);
        return ActivationResult.success();
    }

    /** The aimed enemy within {@code range}, else the nearest one in front of the hero (seen, nothing in between). */
    private @Nullable LivingEntity inFront(Player owner, double range, double cone) {
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed != null && seen(owner, aimed)) {
            return aimed;
        }
        Location eye = owner.getEyeLocation();
        Vector look = Motion.horizontalFacing(eye);
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), range)) {
            Vector to = Motion.horizontal(enemy.getLocation().toVector().subtract(owner.getLocation().toVector()));
            if (seen(owner, enemy) && (to.lengthSquared() < 1.0E-6 || to.dot(look) >= cone)
                    && Spots.clearPath(eye, Fx.body(enemy))) {
                return enemy;
            }
        }
        return null;
    }

    /** "딱!" now, the force arriving late: up into the sky, or (an enemy it cares about) down into the ground. */
    private void flick(Player owner, LivingEntity target, boolean slam) {
        Location forehead = target.getEyeLocation().add(0, 0.15, 0);
        Combat.hit(ctx(), owner, target, params().num("flick_damage"));
        Fx.sound(forehead, Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 1.8f + Fx.jitter(0.05f));
        Fx.sound(forehead, Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.9f, 1.4f + Fx.jitter(0.05f));
        Fx.sound(forehead, Sound.BLOCK_BAMBOO_WOOD_BUTTON_CLICK_ON, 1.0f, 1.6f);
        boolean shown = seen(owner, target);
        if (shown) {
            Vfx.dot(forehead, Particle.FLASH, WHITE, false);
            verticalRing(forehead, toward(owner, target), 0.35, 12, Particle.DUST, SKY_DUST);
            Vfx.cloud(ctx(), forehead, Particle.CRIT, 5, 0.1, 0.2, null, false);
            pop(owner, forehead.clone().add(0, 0.35, 0), "<b><white>딱!</white></b>", 1.1f);
        }
        owner.sendActionBar(Text.mm("<" + SKY_TAG + ">딱!</" + SKY_TAG + "> <gray>" + (slam ? "…이번엔 처박는다"
                : "…가볍게") + "</gray>"));
        long delay = Math.max(1, params().ticks("flick_delay"));
        // The force is on its way: a ring glows on the forehead until it lands.
        Loop.repeat(ctx(), owner, 1, 2, Math.max(1, delay / 2), run -> {
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return false;
            }
            if (seen(owner, target)) {
                Location head = target.getEyeLocation().add(0, 0.15, 0);
                verticalRing(head, toward(owner, target), 0.2 + run * 0.06, 8, Particle.DUST_COLOR_TRANSITION, GLOW);
            }
            return true;
        });
        ctx().runLater(owner, delay, () -> {
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return;
            }
            if (slam) {
                slam(owner, target);
            } else {
                launch(owner, target);
            }
        });
    }

    /** "띠잉—": straight up, not an inch to the side. */
    private void launch(Player owner, LivingEntity target) {
        double speed = Motion.launchSpeed(Math.max(0, params().num("flick_height")));
        target.setVelocity(new Vector(0, Math.min(MAX_SPEED, speed * Motion.knockbackFactor(target)), 0));
        target.setFallDistance(0);
        new Flight(owner, target).start();
        Location feet = target.getLocation().add(0, 0.1, 0);
        Fx.sound(feet, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f);
        Fx.sound(feet, Sound.ENTITY_BREEZE_WIND_BURST, 0.9f, 1.3f + Fx.jitter(0.05f));
        Fx.sound(feet, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.7f, 1.6f);
        if (!seen(owner, target)) {
            return;
        }
        Vfx.ring(ctx(), feet, 0.8, 14, 0, Particle.DUST_COLOR_TRANSITION, GLOW, false);
        Vfx.cloud(ctx(), feet, Particle.CLOUD, 8, 0.3, 0.05, null, false);
        target.getWorld().spawnParticle(Particle.GUST, feet, 1, 0, 0, 0, 0, null, false);
        pop(owner, Fx.body(target).add(0, 0.9, 0), "<b><" + SKY_TAG + ">띠잉—</" + SKY_TAG + "></b>", 1.2f);
    }

    /** An enemy it cares about is pressed into the ground and stunned there. */
    private void slam(Player owner, LivingEntity target) {
        Combat.hit(ctx(), owner, target, params().num("slam_damage"));
        if (!Combat.isTargetable(ctx(), owner, target)) {
            return;
        }
        boolean grounded = Motion.isGrounded(target) || target.isInWater();
        target.setVelocity(new Vector(0, grounded ? 0 : SLAM_DOWN, 0));
        target.setFallDistance(0);
        long stun = params().ticks("slam_stun");
        if (stun > 0) {
            ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, stun);
            ctx().addModifier(owner, target, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, stun);
        }
        Location ground = target.getLocation();
        Spots.snapToGround(target.getWorld(), ground, 3);
        ground.add(0, 0.1, 0);
        Fx.sound(ground, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.6f);
        Fx.sound(ground, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(ground, Sound.ENTITY_GENERIC_BIG_FALL, 1.0f, 0.7f);
        owner.sendActionBar(Text.mm("<" + SKY_TAG + "><b>쿵!</b></" + SKY_TAG + "> <gray>" + AbilityInfo.formatNumber(
                params().num("slam_stun")) + "초 기절</gray>"));
        if (!seen(owner, target)) {
            return;
        }
        Vfx.shockwave(ctx(), owner, ground, 0.3, 1.8, 5, Particle.DUST_COLOR_TRANSITION, FADE, false);
        Block below = ground.clone().subtract(0, 0.4, 0).getBlock();
        if (!below.isPassable()) {
            ground.getWorld().spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 16), 0.4, 0.05, 0.4, 0,
                    below.getBlockData());
            ground.getWorld().spawnParticle(Particle.DUST_PILLAR, ground, Vfx.n(ctx(), 8), 0.3, 0.1, 0.3, 0,
                    below.getBlockData());
        }
        pop(owner, Fx.body(target).add(0, 1.0, 0), "<b><" + NAVY_TAG + ">쿵!</" + NAVY_TAG + "></b>", 1.4f);
        stars(owner, target, stun);
    }

    /** Little sky-blue stars turning around the stunned head. */
    private void stars(Player owner, LivingEntity target, long ticks) {
        if (ticks <= 0) {
            return;
        }
        Loop.repeat(ctx(), owner, 0, 2, Math.max(1, ticks / 2), run -> {
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return false;
            }
            if (!seen(owner, target)) {
                return true;
            }
            Location head = target.getLocation().add(0, target.getHeight() + 0.2, 0);
            for (int i = 0; i < 3; i++) {
                double angle = run * 0.8 + i * TAU / 3;
                Vfx.dot(head.clone().add(Math.cos(angle) * 0.4, 0, Math.sin(angle) * 0.4),
                        Particle.DUST_COLOR_TRANSITION, GLOW, false);
            }
            return true;
        });
    }

    /**
     * One enemy sent flying by the hero: its fall distance is kept at zero on the way (a vanilla fall would be nobody's
     * kill), the fall is measured like vanilla measures it and dealt as the hero's damage on landing (capped), and a
     * star twinkles where it tops out. A player's server position lags its client: the flight only lands once the
     * server has seen it leave the ground (see {@code BatterAbility}).
     */
    private final class Flight implements LongPredicate {

        private final Player owner;
        private final LivingEntity enemy;
        private double peak;
        private double fallTop;
        private Location last;
        private boolean airborne;
        private boolean twinkled;

        Flight(Player owner, LivingEntity enemy) {
            this.owner = owner;
            this.enemy = enemy;
            this.last = Fx.body(enemy);
            this.peak = enemy.getLocation().getY();
            this.fallTop = peak;
        }

        void start() {
            Loop.repeat(ctx(), owner, 1, 1, FLIGHT_MAX_TICKS, this);
        }

        @Override
        public boolean test(long tick) {
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return false;
            }
            enemy.setFallDistance(0);
            double y = enemy.getLocation().getY();
            fallTop = breaksFall(enemy) ? y : Math.max(fallTop, y);
            boolean grounded = Motion.isGrounded(enemy) || enemy.isInWater();
            if (!grounded) {
                airborne = true;
            }
            boolean shown = seen(owner, enemy);
            if (y > peak) {
                peak = y;
            } else if (!twinkled && tick > 2 && y < peak - 0.1) {
                twinkled = true;
                if (shown) {
                    twinkle();
                }
            }
            Location now = Fx.body(enemy);
            if (shown && now.distanceSquared(last) > 1.0E-3) {
                Vfx.line(ctx(), last, now, 2, Particle.DUST, LINE_DUST, false);
                if (tick % 2 == 0) {
                    Vfx.dot(now, Particle.END_ROD, null, false);
                }
            }
            last = now;
            if (airborne && tick >= FLIGHT_MIN_TICKS && grounded) {
                land(shown);
                return false;
            }
            return airborne || tick < FLIGHT_NEVER_AIRBORNE_TICKS;
        }

        /** The top of the flight: "반짝". */
        private void twinkle() {
            Location at = enemy.getLocation().add(0, enemy.getHeight() + 0.4, 0);
            Vfx.dot(at, Particle.FLASH, WHITE, true);
            Vfx.cloud(ctx(), at, Particle.END_ROD, 8, 0.1, 0.12, null, true);
            Vfx.ring(ctx(), at, 0.5, 8, 0, Particle.DUST, SKY_DUST, true);
            Fx.sound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE_FAR, 1.0f, 1.6f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.9f);
        }

        private void land(boolean shown) {
            Location feet = enemy.getLocation();
            double damage = enemy.isInWater() ? 0 : fallDamage(enemy, fallTop - feet.getY());
            if (damage > 0) {
                hitUnscaled(owner, enemy, damage, DamageType.FALL);
            }
            if (!shown) {
                return;
            }
            Location ground = feet.clone().add(0, 0.1, 0);
            Vfx.ring(ctx(), ground, 0.9, 14, 0, Particle.DUST, GREY_DUST, false);
            Block below = feet.clone().add(0, -0.5, 0).getBlock();
            if (!below.isPassable()) {
                feet.getWorld().spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 12), 0.4, 0.05, 0.4, 0,
                        below.getBlockData());
            }
            Vfx.cloud(ctx(), ground, Particle.CLOUD, 5, 0.3, 0.03, null, false);
            Fx.sound(ground, Sound.ENTITY_PLAYER_BIG_FALL, 1.0f, 0.9f + Fx.jitter(0.05f));
        }
    }

    /** True while a vanilla fall ends or never builds up: slow falling, water, a ladder, flight. */
    private static boolean breaksFall(LivingEntity entity) {
        return entity.isInWater() || entity.isClimbing() || entity.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || entity instanceof Player flier && flier.isFlying();
    }

    /** The fall damage vanilla would deal for {@code fallen} blocks (safe distance, multiplier), capped. */
    private double fallDamage(LivingEntity entity, double fallen) {
        double safe = attribute(entity, Attribute.SAFE_FALL_DISTANCE, SAFE_FALL);
        double multiplier = Math.max(0, attribute(entity, Attribute.FALL_DAMAGE_MULTIPLIER, 1.0));
        return Math.clamp((fallen - safe) * multiplier, 0, Math.max(0, params().num("flick_fall_max")));
    }

    private static double attribute(LivingEntity entity, Attribute attribute, double fallback) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    // ------------------------------------------------------------------ ultimate: a bit harder this time

    /**
     * Self-contained (owner-scoped tasks and modifiers, the interest is not used), so a Gambler can borrow it.
     */
    @Override
    public ActivationResult onUltimate() {
        if (windingUp) {
            return ActivationResult.fail("이미 소매를 걷어붙였습니다");
        }
        Player owner = player();
        long windup = Math.max(1, params().ticks("hard_windup"));
        windingUp = true;
        ctx().addModifier(owner, owner, Attribute.KNOCKBACK_RESISTANCE, 1.0, Operation.ADD_NUMBER, windup + 2);
        Location feet = owner.getLocation();
        Fx.sound(feet, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 0.8f);
        ctx().runLater(owner, 5, () -> Fx.sound(owner.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 1.0f));
        Fx.sound(feet, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
        say(owner, "<b><white>…이번엔 좀 세게 간다</white></b>", windup + 4);
        owner.sendActionBar(Text.mm("<b><white>…이번엔 좀 세게 간다</white></b>"));
        Loop.repeat(ctx(), owner, 0, 1, windup, run -> {
            focusLines(owner, run, windup);
            if (run % 6 == 0) {
                Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f + 0.1f * run / 6);
            }
            return true;
        }, () -> {
            windingUp = false;
            punch(owner);
        });
        return ActivationResult.success();
    }

    /** Manga focus lines: white streaks rushing in at the hero from every side, a navy ring tightening on the ground. */
    private void focusLines(Player owner, long run, long windup) {
        Location chest = Fx.body(owner);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 2; i++) {
            double angle = random.nextDouble(TAU);
            double tilt = random.nextDouble(-0.5, 0.6);
            Vector out = new Vector(Math.cos(angle), tilt, Math.sin(angle)).normalize();
            Location far = chest.clone().add(out.clone().multiply(3.6));
            Location near = chest.clone().add(out.clone().multiply(1.4));
            Vfx.line(ctx(), far, near, 3, Particle.DUST, LINE_DUST, true);
        }
        double radius = 2.4 - 1.6 * run / Math.max(1.0, windup);
        Vfx.ring(ctx(), owner.getLocation().add(0, 0.1, 0), radius, 16, run * 0.2, Particle.DUST, NAVY_DUST, true);
    }

    /** A single blow at a single enemy, as heavy as the enemy is healthy; nobody there: "…귀찮네". */
    private void punch(Player owner) {
        if (!ctx().isAlive(owner)) {
            return;
        }
        double range = Math.max(0.5, params().num("hard_range"));
        LivingEntity target = inFront(owner, range, HARD_CONE_COS);
        if (target == null) {
            double refund = Math.clamp(params().num("hard_refund"), 0, 100);
            if (refund > 0) {
                ctx().addGauge(owner, refund);
            }
            say(owner, "<i><" + GREY_TAG + ">…귀찮네</" + GREY_TAG + "></i>", 30);
            owner.sendActionBar(Text.mm("<" + GREY_TAG + ">…귀찮네</" + GREY_TAG + "> <gray>궁극기 게이지 "
                    + AbilityInfo.formatNumber(refund) + "% 반환</gray>"));
            Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_BREATH, 0.8f, 0.6f);
            return;
        }
        double share = Math.max(0, params().num("hard_share")) / 100.0;
        double low = Math.max(0, params().num("hard_min"));
        double high = Math.max(low, params().num("hard_max"));
        double damage = Math.clamp(target.getHealth() * share + params().num("hard_bonus"), low, high);
        Location contact = Fx.body(target);
        Vector way = toward(owner, target);
        hitUnscaled(owner, target, damage, DamageType.MAGIC);
        if (Combat.isTargetable(ctx(), owner, target)) {
            Motion.push(target, Motion.horizontal(way).multiply(PUNCH_SPEED).setY(PUNCH_LIFT), MAX_SPEED);
            target.setFallDistance(0);
            new Flight(owner, target).start();
        }
        punchFx(owner, contact, way, target);
        owner.sendActionBar(Text.mm("<b><white>퍽.</white></b> <gray>" + AbilityInfo.formatNumber(
                Math.round(damage * 10) / 10.0) + " 피해</gray>"));
        ctx().runLater(owner, 30, () -> say(owner, "<i><" + GREY_TAG + ">…끝났나</" + GREY_TAG + "></i>", 30));
    }

    /** A white flash, a shock ring standing across the punch, and a deep, flat thud. */
    private void punchFx(Player owner, Location contact, Vector way, LivingEntity target) {
        Fx.sound(contact, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.4f, 0.5f);
        Fx.sound(contact, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f);
        Fx.sound(contact, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.6f);
        Fx.sound(contact, Sound.ITEM_TRIDENT_THUNDER, 0.5f, 1.8f);
        if (!seen(owner, target)) {
            return;
        }
        World world = contact.getWorld();
        world.spawnParticle(Particle.FLASH, contact, 1, 0, 0, 0, 0, WHITE, true);
        Vfx.dot(contact, Particle.SONIC_BOOM, null, true);
        for (int ring = 0; ring < 3; ring++) {
            int delay = ring * 2;
            double radius = 0.6 + ring * 0.55;
            ctx().runLater(owner, Math.max(1, delay), () -> verticalRing(contact, way, radius, 20,
                    Particle.DUST_COLOR_TRANSITION, GLOW));
        }
        Vfx.cloud(ctx(), contact, Particle.CLOUD, 10, 0.3, 0.15, null, true);
        Vfx.cloud(ctx(), contact, Particle.CRIT, 12, 0.3, 0.4, null, false);
        pop(owner, contact.clone().add(0, 0.9, 0), "<b><white>퍽!</white></b>", 2.0f);
    }

    // ------------------------------------------------------------------ helpers

    /** Ability damage the interest does not scale (see {@link #unscaled}). */
    private void hitUnscaled(Player owner, LivingEntity target, double damage, DamageType type) {
        boolean before = unscaled;
        unscaled = true;
        try {
            Combat.hit(ctx(), owner, target, damage, type);
        } finally {
            unscaled = before;
        }
    }

    /** A ring standing across {@code normal} (horizontal part), centred on {@code center}. */
    private <T> void verticalRing(Location center, Vector normal, double radius, int points, Particle particle,
                                  T data) {
        Vector flat = Motion.horizontal(normal);
        if (flat.lengthSquared() < 1.0E-6) {
            flat = new Vector(0, 0, 1);
        }
        Vector side = new Vector(-flat.getZ(), 0, flat.getX());
        Vector up = new Vector(0, 1, 0);
        int count = Vfx.n(ctx(), points);
        for (int i = 0; i < count; i++) {
            double angle = i * TAU / count;
            Location at = center.clone().add(side.clone().multiply(Math.cos(angle) * radius))
                    .add(up.clone().multiply(Math.sin(angle) * radius));
            Vfx.dot(at, particle, data, false);
        }
    }

    private static Vector toward(Player owner, LivingEntity target) {
        Vector way = target.getLocation().toVector().subtract(owner.getLocation().toVector());
        way.setY(0);
        return way.lengthSquared() < 1.0E-6 ? Motion.horizontalFacing(owner.getLocation()) : way.normalize();
    }

    /** A word popping up and fading. */
    private void pop(Player owner, Location at, String mini, float scale) {
        if (!reserve(1)) {
            return;
        }
        TextDisplay text = keep(Displays.text(ctx(), owner, at, Text.mm(mini), 0.3f, 20));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0, 0, 0), 4);
        ctx().runLater(owner, 12, () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 5);
            }
        });
    }

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
}
