package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.ability.Prompt;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptHandle;
import io.github.sx2xoox.mcpl.util.Text;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier.Operation;
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
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 청개구리 — the contrary green frog of the Korean fable that always did the opposite of what its mother said. Its own
 * knockback works backwards (a blow that should push it away pulls it towards the attacker), its croak turns an
 * enemy's movement keys around (forward walks back, left walks right), and its monsoon rain falls upwards: everyone
 * caught in it walks backwards, their blows pull instead of push, and the frog and its friends splash along faster.
 *
 * <p>Look (frog green, rain sky blue, mud brown): a translucent throat bubble swells under the chin and pops into green
 * ripples that run out in a fan; reversed enemies carry a green "⇅" over their heads. Reversed knockback throws a
 * "거꾸로!" droplet. The rain is sky-blue streaks rising from splashing ground into a grey cloud overhead, with
 * croaks and a mud-brown edge ring.
 */
public final class FrogAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("frog", "청개구리", Material.LILY_PAD)
            .color(TextColor.color(0x7ACC3B))
            .difficulty(2)
            .passive("청개구리 심보",
                    "근접·투사체에 받는 바닐라 넉백이 거꾸로 작용합니다: 나를 밀쳐내는 공격은 같은 힘의 "
                            + "{reverse_share}%로 나를 공격자 쪽으로 끌어당깁니다(뒤집힐 때마다 '거꾸로!' 물방울).")
            .active("거꾸로 개굴",
                    "{croak_windup}초간 볼을 부풀렸다가 개굴 울며 앞쪽 {croak_range}블록 부채꼴({croak_angle}°)로 거꾸로 "
                            + "파동을 퍼뜨립니다. 파동에 닿은 적은 {croak_damage} 피해를 입고 {reverse_duration}초간 "
                            + "청개구리 심보에 걸려, 땅 위에서 이동 조작이 뒤집힙니다(앞↔뒤, 왼쪽↔오른쪽). 걸린 적의 머리 "
                            + "위에는 뒤집힌 화살표가 떠 모두에게 보입니다.",
                    10)
            .ultimate("장맛비",
                    "{rain_duration}초간 이 자리 반경 {rain_radius}블록에 장맛비가 내립니다(빗방울은 거꾸로 하늘로 "
                            + "솟구칩니다). 비를 맞는 적은 이동 조작이 뒤집히고, 그 적들이 주는 넉백은 끌어당기는 힘이 "
                            + "되며, {rain_interval}초마다 개굴 소리와 함께 {rain_damage} 피해를 입습니다. 빗속의 나와 "
                            + "아군은 이동 속도가 {rain_speed}% 빨라집니다.")
            .ultimateNotStandalone()
            .param("reverse_share", 60)
            .param("croak_windup", 0.3)
            .param("croak_range", 7)
            .param("croak_angle", 70)
            .param("croak_damage", 3)
            .param("reverse_duration", 2)
            .param("rain_duration", 6)
            .param("rain_radius", 9)
            .param("rain_interval", 1)
            .param("rain_damage", 1)
            .param("rain_speed", 20)
            .build();

    private static final String FROG_TAG = "#7ACC3B";
    private static final String RAIN_TAG = "#8EC9E8";

    // ------------------------------------------------------------------ look
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE4FFC8, 0x7ACC3B, 0x2E5A12);
    private static final Color FROG = Color.fromRGB(0x7ACC3B);
    private static final Color RAIN = Color.fromRGB(0x8EC9E8);
    private static final Particle.DustOptions FROG_DUST = new Particle.DustOptions(FROG, 1.0f);
    private static final Particle.DustOptions RAIN_DUST = new Particle.DustOptions(RAIN, 0.8f);
    private static final Particle.DustOptions MUD_DUST = new Particle.DustOptions(Color.fromRGB(0x6B4F2E), 1.3f);
    private static final Particle.DustTransition RIPPLE = PALETTE.glow(1.1f);

    /** Block types (block data is created on use: it needs a running server). */
    private static final Material BUBBLE = Material.WHITE_STAINED_GLASS;
    private static final Material CLOUD = Material.GRAY_WOOL;
    private static final Material CLOUD_LIGHT = Material.LIGHT_GRAY_WOOL;

    /** Vanilla walking speed on the ground, blocks per tick: a reversed walk moves this fast. */
    private static final double WALK = 0.2;
    private static final double SNEAK_SHARE = 0.3;
    private static final double MAX_PULL = 1.0;
    private static final long BUBBLE_FX_GAP = 8;
    private static final int RIPPLE_STEPS = 6;
    private static final double CONE_HEIGHT = 3;
    private static final long RAIN_REFRESH = 6;
    private static final int RAIN_DROPS = 14;
    private static final int CLOUD_PUFFS = 6;
    private static final double CLOUD_HEIGHT = 5.5;
    private static final int FOLLOW_LERP = 3;
    private static final int MAX_DISPLAYS = 24;

    // ------------------------------------------------------------------ state

    /** An enemy player whose movement keys are turned around. */
    private final class Reversal {
        final Player victim;
        long until;
        @Nullable NamespacedKey lock;
        @Nullable TextDisplay arrow;
        @Nullable PromptHandle notice;

        Reversal(Player victim, long until) {
            this.victim = victim;
            this.until = until;
        }
    }

    /** The monsoon of the ultimate. */
    private static final class Rain {
        final Location center;
        final double radius;
        final Set<UUID> soaked = new HashSet<>();
        final Map<UUID, NamespacedKey> boosted = new HashMap<>();
        final List<BlockDisplay> cloud = new ArrayList<>();

        Rain(Location center, double radius) {
            this.center = center;
            this.radius = radius;
        }
    }

    private final Map<UUID, Reversal> reversed = new HashMap<>();
    private final List<Display> live = new ArrayList<>();
    private @Nullable FrogListener listener;
    private @Nullable Rain rain;
    private long lastBubble = Long.MIN_VALUE / 2;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        FrogListener created = new FrogListener();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        listener = created;
    }

    @Override
    public void onRevoke() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        reversed.clear();
        rain = null;
        live.clear();
    }

    // ================================================================== test hooks

    /** True while {@code player}'s movement keys are turned around by this frog. */
    public boolean isReversed(Player player) {
        return reversed.containsKey(player.getUniqueId());
    }

    /** True while the monsoon falls. */
    public boolean isRaining() {
        return rain != null;
    }

    // ================================================================== passive: contrary knockback

    private final class FrogListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onKnockback(EntityKnockbackEvent event) {
            Player owner = player();
            EntityKnockbackEvent.Cause cause = event.getCause();
            if (cause == EntityKnockbackEvent.Cause.EXPLOSION || cause == EntityKnockbackEvent.Cause.PUSH
                    || cause == EntityKnockbackEvent.Cause.SHIELD_BLOCK) {
                return;
            }
            if (event.getEntity().equals(owner)) {
                if (cause == EntityKnockbackEvent.Cause.ENTITY_ATTACK || cause == EntityKnockbackEvent.Cause.SWEEP_ATTACK
                        || cause == EntityKnockbackEvent.Cause.DAMAGE) {
                    double share = Math.clamp(params().num("reverse_share") / 100.0, 0.0, 2.0);
                    event.setKnockback(flip(event.getKnockback(), share));
                    bubble(owner);
                }
                return;
            }
            Rain current = rain;
            if (current == null || !(event instanceof EntityPushedByEntityAttackEvent pushed)) {
                return;
            }
            Player attacker = ctx().resolveAttacker(pushed.getPushedBy());
            Entity source = attacker != null ? attacker : pushed.getPushedBy();
            if (current.soaked.contains(source.getUniqueId())) {
                event.setKnockback(flip(event.getKnockback(), 1.0));
                Vfx.dot(event.getEntity().getLocation().add(0, 1.2, 0), Particle.SPLASH, null, false);
            }
        }
    }

    /** The horizontal push turned around ({@code share} of its strength), a small lift kept. */
    private static Vector flip(Vector knock, double share) {
        Vector pull = new Vector(-knock.getX(), 0, -knock.getZ()).multiply(share);
        Motion.clamp(pull, MAX_PULL);
        return new Vector(pull.getX(), Math.min(knock.getY(), 0.36) * share, pull.getZ());
    }

    /** "거꾸로!": a droplet of text pops over the frog. */
    private void bubble(Player owner) {
        long now = ctx().currentTick();
        Location head = owner.getLocation().add(0, owner.getHeight() + 0.4, 0);
        Vfx.cloud(ctx(), head, Particle.SPLASH, 10, 0.25, 0.05, null, false);
        Fx.sound(head, Sound.ENTITY_FROG_AMBIENT, 0.9f, 1.4f + Fx.jitter(0.1f));
        if (now - lastBubble < BUBBLE_FX_GAP) {
            return;
        }
        lastBubble = now;
        if (room(1)) {
            TextDisplay text = keep(Displays.text(ctx(), owner, head, Text.mm("<b><" + RAIN_TAG + ">거꾸로!</"
                    + RAIN_TAG + "></b>"), 0.7f, 14, viewer -> !ctx().isConcealedFrom(owner, viewer)));
            Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.5f, 0, 0.9f, 0.9f, 0.9f, 0, 0, 0), 10);
        }
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        if (tick % 30 == 0) {
            Vfx.dot(owner.getLocation().add(Fx.jitter(0.3f), 0.1, Fx.jitter(0.3f)), Particle.DUST, FROG_DUST, false);
        }
        Iterator<Reversal> it = reversed.values().iterator();
        while (it.hasNext()) {
            Reversal r = it.next();
            Player victim = r.victim;
            if (!victim.isValid() || victim.isDead() || !ctx().isEnemy(owner, victim) || now >= r.until) {
                it.remove();
                release(r);
                continue;
            }
            drive(victim);
            TextDisplay arrow = r.arrow;
            if (arrow != null && arrow.isValid()) {
                Displays.moveTo(arrow, victim.getLocation().add(0, victim.getHeight() + 0.55, 0));
            }
        }
    }

    // ================================================================== reversed controls

    /** Turns the victim's keys around for {@code ticks} (longer if already turned); a notice for {@code noticeTicks}. */
    private void reverse(Player owner, Player victim, long ticks, long noticeTicks) {
        long now = ctx().currentTick();
        Reversal r = reversed.get(victim.getUniqueId());
        if (r != null) {
            r.until = Math.max(r.until, now + ticks);
            return;
        }
        r = new Reversal(victim, now + ticks);
        reversed.put(victim.getUniqueId(), r);
        r.lock = ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -1, Operation.MULTIPLY_SCALAR_1, 0);
        if (room(1)) {
            TextDisplay arrow = keep(Displays.text(ctx(), owner, victim.getLocation().add(0, victim.getHeight() + 0.55,
                    0), Text.mm("<b><" + FROG_TAG + ">⇅</" + FROG_TAG + "></b>"), 1.3f, 0,
                    viewer -> !ctx().isConcealedFrom(victim, viewer)));
            arrow.setTeleportDuration(FOLLOW_LERP);
            r.arrow = arrow;
        }
        if (noticeTicks > 0) {
            r.notice = ctx().prompt(owner, victim, Prompt.builder(Text.mm("<b><" + FROG_TAG + ">거꾸로!</" + FROG_TAG
                            + "></b> <gray>이동 조작이 뒤집혔습니다 (앞↔뒤 · 왼쪽↔오른쪽)</gray>"), noticeTicks)
                    .priority(Prompt.LOW)
                    .maxWait(10)
                    .build());
        }
    }

    private void release(Reversal r) {
        if (r.lock != null) {
            ctx().removeModifier(r.victim, r.lock);
            r.lock = null;
        }
        TextDisplay arrow = r.arrow;
        if (arrow != null && arrow.isValid()) {
            arrow.remove();
        }
        PromptHandle notice = r.notice;
        if (notice != null && notice.isActive()) {
            notice.cancel();
        }
        if (r.victim.isValid()) {
            Vfx.cloud(ctx(), r.victim.getLocation().add(0, 0.2, 0), Particle.SPLASH, 6, 0.2, 0.02, null, false);
        }
    }

    /**
     * The victim's own walk, turned around: its client cannot walk (speed locked), so every tick on the ground its keys
     * are read and the opposite walk is set as a fresh velocity (its own input asked for it, so input rules still judge
     * it). In the air it keeps its momentum.
     */
    private void drive(Player victim) {
        if (!Motion.isGrounded(victim)) {
            return;
        }
        Input input = victim.getCurrentInput();
        int forward = (input.isForward() ? 1 : 0) - (input.isBackward() ? 1 : 0);
        int side = (input.isRight() ? 1 : 0) - (input.isLeft() ? 1 : 0);
        double up = input.isJump() ? jumpSpeed(victim) : 0;
        Vector facing = Motion.horizontalFacing(victim.getLocation());
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());
        Vector intent = facing.multiply(forward).add(right.multiply(side));
        if (intent.lengthSquared() < 1.0E-6) {
            if (up > 0) {
                ctx().setVoluntaryVelocity(victim, new Vector(0, up, 0));
            }
            return;
        }
        double speed = WALK * (input.isSneak() ? SNEAK_SHARE : 1);
        Vector walk = intent.normalize().multiply(-speed);
        walk.setY(up);
        ctx().setVoluntaryVelocity(victim, walk);
    }

    private static double jumpSpeed(LivingEntity entity) {
        AttributeInstance jump = entity.getAttribute(Attribute.JUMP_STRENGTH);
        return jump == null ? 0.42 : Math.max(0, jump.getValue());
    }

    // ================================================================== active: contrary croak

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        long windup = Math.max(1, params().ticks("croak_windup"));
        BlockDisplay[] sac = new BlockDisplay[2];
        for (int i = 0; i < 2; i++) {
            if (room(1)) {
                sac[i] = keep(Displays.block(ctx(), owner, chin(owner), BUBBLE.createBlockData(),
                        Displays.transform(0, 0, 0, 0.1f, 0.1f, 0.1f, i * 45f, i * 45f, 0), windup + 2));
                sac[i].setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, sac[i], Displays.transform(0, 0, 0, 0.55f, 0.5f, 0.55f,
                        i * 45f, i * 45f, 0), (int) Math.max(1, windup - 1));
            }
        }
        Fx.sound(owner.getLocation(), Sound.ENTITY_FROG_AMBIENT, 1.0f, 0.7f);
        Fx.sound(owner.getLocation(), Sound.ENTITY_PUFFER_FISH_BLOW_UP, 0.8f, 1.2f);
        Loop.repeat(ctx(), owner, 0, 1, windup, i -> {
            for (BlockDisplay part : sac) {
                if (part != null) {
                    Displays.moveTo(part, chin(owner));
                }
            }
            return true;
        }, () -> {
            for (BlockDisplay part : sac) {
                if (part != null && part.isValid()) {
                    Vfx.cloud(ctx(), part.getLocation(), Particle.SPLASH, 10, 0.2, 0.05, null, false);
                    part.remove();
                }
            }
            croak(owner);
        });
        return ActivationResult.success();
    }

    private static Location chin(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.add(Motion.horizontalFacing(owner.getLocation()).multiply(0.3)).add(0, -0.4, 0);
    }

    /** The croak: green ripples run out in a fan, and each enemy the wave reaches is turned around. */
    private void croak(Player owner) {
        Location origin = owner.getLocation().add(0, 0.9, 0);
        Vector facing = Motion.horizontalFacing(owner.getLocation());
        float yaw = owner.getLocation().getYaw();
        double range = Math.max(0.5, params().num("croak_range"));
        double half = Math.toRadians(Math.clamp(params().num("croak_angle"), 1, 360) / 2);
        double cos = Math.cos(half);
        Set<UUID> hit = new HashSet<>();
        Fx.sound(origin, Sound.ENTITY_FROG_AMBIENT, 1.4f, 0.5f);
        Fx.sound(origin, Sound.ENTITY_FROG_LONG_JUMP, 1.0f, 0.6f);
        Fx.sound(origin, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 1.0f, 0.8f);
        Loop.repeat(ctx(), owner, 0, 1, RIPPLE_STEPS, i -> {
            double reach = range * (i + 1) / RIPPLE_STEPS;
            Vfx.arc(ctx(), origin, reach, yaw, Math.toDegrees(half * 2), (int) (8 + reach * 3), Particle.DUST_COLOR_TRANSITION,
                    RIPPLE, false);
            Vfx.arc(ctx(), origin.clone().add(0, -0.7, 0), reach, yaw, Math.toDegrees(half * 2), (int) (4 + reach),
                    Particle.BUBBLE_POP, null, false);
            for (LivingEntity enemy : ctx().enemiesNear(owner, origin, reach)) {
                if (hit.contains(enemy.getUniqueId())) {
                    continue;
                }
                Vector to = enemy.getLocation().toVector().subtract(owner.getLocation().toVector());
                Vector flat = new Vector(to.getX(), 0, to.getZ());
                if (Math.abs(to.getY()) > CONE_HEIGHT || flat.lengthSquared() > reach * reach + 1.0E-6) {
                    continue;
                }
                if (flat.lengthSquared() > 1.0E-4 && facing.dot(flat.clone().normalize()) < cos) {
                    continue;
                }
                if (!owner.hasLineOfSight(enemy)) {
                    continue;
                }
                hit.add(enemy.getUniqueId());
                strike(owner, enemy);
            }
            return true;
        });
    }

    private void strike(Player owner, LivingEntity enemy) {
        Location at = Fx.body(enemy);
        Combat.hit(ctx(), owner, enemy, params().num("croak_damage"));
        Vfx.burst(ctx(), at, PALETTE, 0.5, Particle.SPLASH, false);
        Fx.sound(at, Sound.ENTITY_FROG_HURT, 0.8f, 1.2f + Fx.jitter(0.1f));
        if (enemy instanceof Player victim && Combat.isTargetable(ctx(), owner, victim)) {
            long ticks = Math.max(1, params().ticks("reverse_duration"));
            reverse(owner, victim, ticks, ticks);
        }
    }

    // ================================================================== ultimate: the monsoon

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (rain != null) {
            return ActivationResult.fail("<gray>이미 장맛비가 내리고 있습니다</gray>");
        }
        Location center = owner.getLocation();
        center.setYaw(0);
        center.setPitch(0);
        double radius = Math.max(1, params().num("rain_radius"));
        Rain created = new Rain(center, radius);
        rain = created;
        long duration = Math.max(1, params().ticks("rain_duration"));
        long interval = Math.max(1, params().ticks("rain_interval"));
        for (int k = 0; k < CLOUD_PUFFS; k++) {
            if (!room(1)) {
                break;
            }
            double angle = k * Math.PI * 2 / CLOUD_PUFFS;
            double spread = k == 0 ? 0 : radius * 0.35;
            Location puff = center.clone().add(Math.cos(angle) * spread, CLOUD_HEIGHT + (k % 2) * 0.4,
                    Math.sin(angle) * spread);
            float size = k == 0 ? 3.2f : 2.4f;
            BlockDisplay part = keep(Displays.block(ctx(), owner, puff, (k % 2 == 0 ? CLOUD : CLOUD_LIGHT)
                    .createBlockData(), Displays.transform(0, 0, 0, 0.1f, 0.1f, 0.1f, k * 23f, 0, 0), duration + 12));
            part.setViewRange(2.5f);
            Displays.animateNextTick(ctx(), owner, part,
                    Displays.transform(0, 0, 0, size, size * 0.45f, size, k * 23f, 0, 0), 10);
            created.cloud.add(part);
        }
        owner.showTitle(Title.title(Text.mm("<b><gradient:#8EC9E8:#7ACC3B>장맛비</gradient></b>"),
                Text.mm("<gray>비가 거꾸로 솟습니다 · 빗속의 적은 모든 게 반대로</gray>")));
        Fx.sound(center, Sound.WEATHER_RAIN, 1.5f, 0.8f);
        Fx.sound(center, Sound.ENTITY_FROG_AMBIENT, 1.4f, 0.6f);
        Fx.sound(center, Sound.ITEM_TRIDENT_THUNDER, 0.5f, 1.4f);
        Loop.repeat(ctx(), owner, 0, 1, duration, i -> rainStep(owner, created, i, interval),
                () -> endRain(owner, created));
        return ActivationResult.success();
    }

    private boolean rainStep(Player owner, Rain r, long i, long interval) {
        if (rain != r) {
            return false;
        }
        Location ground = r.center.clone().add(0, 0.1, 0);
        for (int k = 0; k < Vfx.n(ctx(), RAIN_DROPS); k++) {
            Location from = Vfx.scatter(ground, r.radius);
            Vfx.trail(from, from.clone().add(Fx.jitter(0.2f), CLOUD_HEIGHT - 0.5, Fx.jitter(0.2f)), RAIN, 12, true);
        }
        if (i % 2 == 0) {
            Vfx.dot(Vfx.scatter(ground, r.radius), Particle.SPLASH, null, false);
            Vfx.dot(Vfx.scatter(ground, r.radius).add(0, 0.2, 0), Particle.DUST, RAIN_DUST, false);
        }
        if (i % 6 == 0) {
            Vfx.ring(ctx(), ground, r.radius, 32, i * 0.04, Particle.DUST, MUD_DUST, true);
        }
        Set<UUID> inside = new HashSet<>();
        boolean pulse = i % interval == interval - 1;
        for (LivingEntity enemy : ctx().enemiesNear(owner, r.center, r.radius + 1)) {
            if (flatDistance(enemy.getLocation(), r.center) > r.radius) {
                continue;
            }
            inside.add(enemy.getUniqueId());
            if (enemy instanceof Player victim) {
                reverse(owner, victim, RAIN_REFRESH, Math.max(RAIN_REFRESH, params().ticks("rain_duration") - i));
            }
            if (pulse) {
                Location at = Fx.body(enemy);
                Combat.hit(ctx(), owner, enemy, params().num("rain_damage"));
                Fx.sound(at, Sound.ENTITY_FROG_AMBIENT, 0.9f, 0.8f + Fx.jitter(0.2f));
                Vfx.cloud(ctx(), at, Particle.SPLASH, 8, 0.3, 0.05, null, false);
            }
        }
        r.soaked.clear();
        r.soaked.addAll(inside);
        boost(owner, r);
        return true;
    }

    /** The frog and its allies in the rain splash along faster; those who left it lose the boost. */
    private void boost(Player owner, Rain r) {
        double speed = Math.max(0, params().num("rain_speed")) / 100.0;
        Set<UUID> wet = new HashSet<>();
        List<Player> friends = new ArrayList<>(ctx().alliesNear(owner, r.center, r.radius + 1));
        friends.add(owner);
        for (Player friend : friends) {
            if (flatDistance(friend.getLocation(), r.center) > r.radius) {
                continue;
            }
            wet.add(friend.getUniqueId());
            if (speed > 0 && !r.boosted.containsKey(friend.getUniqueId())) {
                r.boosted.put(friend.getUniqueId(), ctx().addModifier(owner, friend, Attribute.MOVEMENT_SPEED, speed,
                        Operation.MULTIPLY_SCALAR_1, 0));
                Vfx.cloud(ctx(), friend.getLocation().add(0, 0.2, 0), Particle.SPLASH, 8, 0.3, 0.05, null, false);
            }
        }
        Iterator<Map.Entry<UUID, NamespacedKey>> it = r.boosted.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, NamespacedKey> entry = it.next();
            if (!wet.contains(entry.getKey())) {
                Entity entity = Bukkit.getEntity(entry.getKey());
                if (entity instanceof LivingEntity living) {
                    ctx().removeModifier(living, entry.getValue());
                }
                it.remove();
            }
        }
    }

    private void endRain(Player owner, Rain r) {
        if (rain == r) {
            rain = null;
        }
        r.soaked.clear();
        for (Map.Entry<UUID, NamespacedKey> entry : r.boosted.entrySet()) {
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof LivingEntity living) {
                ctx().removeModifier(living, entry.getValue());
            }
        }
        r.boosted.clear();
        for (BlockDisplay part : r.cloud) {
            if (part.isValid()) {
                Displays.shrinkAway(ctx(), owner, part, 10);
            }
        }
        r.cloud.clear();
        Fx.sound(r.center, Sound.ENTITY_FROG_AMBIENT, 1.0f, 1.2f);
        Vfx.cloud(ctx(), r.center.clone().add(0, CLOUD_HEIGHT, 0), Particle.CLOUD, 12, 1.5, 0.02, null, false);
    }

    private static double flatDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    // ================================================================== display budget

    private boolean room(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
