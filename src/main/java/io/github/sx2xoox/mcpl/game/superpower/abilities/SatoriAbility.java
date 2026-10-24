package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.damage.DamageType;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 사토리 — the mountain yokai that reads minds and says your thought out loud before you can act on it. It always knows
 * who is aiming at it (a thought bubble only it can see, even through walls), the active reads one enemy's mind for a
 * moment and punishes whatever that enemy does meanwhile, and the ultimate hears every mind around: the next moves of
 * every enemy show to the whole team and each enemy's first blow at the satori is read and slips past. Only what has
 * no mind cannot be read: falls, walls and fire hurt it more.
 *
 * <p>The read never cancels the enemy's cast (nothing can); it punishes the moment: the enemy's damage to the satori is
 * void for a moment and the enemy is struck and stunned. Casts are noticed after the fact, by watching the enemy's
 * cooldown and gauge (like the 경찰's siren); attacks the moment they land.
 *
 * <p>Look: mountain-beast brown, mind-reading teal and white fur. Thought bubbles are pale speech balloons with a teal
 * eye; a read mind wears a slowly typed "…" under a turning teal halo, linked to the satori's head by a faint thread;
 * the counter is a teal spike into the enemy's head with a big "!" and the satori's line "지금 그거 쓰려고 했지?". The
 * ultimate sends teal whisper rings over the ground and hangs a balloon with each enemy's next moves over its head.
 */
public final class SatoriAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("satori", "사토리", Material.OBSERVER)
            .color(TextColor.color(0x3FC1B0))
            .difficulty(3)
            .passive("노리는 마음",
                    "{aim_radius}블록 안에서 나를 바라보며 노리는 적의 머리 위에 나에게만 보이는 생각 풍선이 떠오릅니다"
                            + "(벽 너머로도 보입니다). 대신 마음이 없는 '우연'은 읽지 못해, 낙하·벽 충돌·용암·불 같은 "
                            + "환경 피해를 {accident_weakness}% 더 받습니다.")
            .active("선읽기",
                    "조준한 적({read_range}블록 이내)의 마음을 {read_duration}초간 읽습니다(머리 위에 '…'). 그동안 그 적이 "
                            + "액티브나 궁극기를 쓰거나 나를 공격하면 \"지금 그거 쓰려고 했지?\" 받아쳐, {counter_guard}초간 "
                            + "그 적의 피해를 받지 않고 그 적은 {counter_damage} 피해와 {counter_stun}초 기절(움직일 수도, 능력을 "
                            + "쓸 수도 없음)을 입습니다. 아무 일 없이 읽기가 끝나면 쿨타임이 {read_refund}% 돌아옵니다.",
                    10)
            .ultimate("다 들린다",
                    "{ult_duration}초간 반경 {ult_radius}블록 안 모든 적의 마음이 들립니다. 읽힌 적의 머리 위 생각 풍선에 "
                            + "액티브까지 남은 시간과 궁극기 게이지가 떠올라 나와 아군에게 보이고, 적마다 나를 향한 첫 공격은 "
                            + "\"그럴 줄 알았어\" 미리 읽고 흘려 냅니다(그 피해 무효). 그동안 읽힌 적에게 주는 내 피해가 "
                            + "{ult_bonus}% 늘어납니다.")
            .ultimateNotStandalone()
            .param("aim_radius", 20)
            .param("aim_angle", 12)
            .param("accident_weakness", 25)
            .param("read_range", 14)
            .param("read_duration", 2.5)
            .param("counter_guard", 1.5)
            .param("counter_damage", 4)
            .param("counter_stun", 1)
            .param("read_refund", 50)
            .param("ult_duration", 6)
            .param("ult_radius", 16)
            .param("ult_bonus", 20)
            .build();

    private static final String TEAL = "#3FC1B0";
    private static final String FUR = "#F2EFE6";
    private static final String BROWN = "#7A5A3C";
    private static final String DARK = "#4A3524";
    private static final String GOLD = "#F0C23A";
    private static final String RED = "#E5484D";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: brown, teal and white fur
    /** White fur, mind-reading teal, mountain-beast brown. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF2EFE6, 0x3FC1B0, 0x7A5A3C);
    private static final Color TEAL_COLOR = Color.fromRGB(0x3FC1B0);
    private static final Color DEEP_TEAL = Color.fromRGB(0x1E7F74);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition GLOW_SMALL = PALETTE.glow(0.6f);
    private static final Particle.DustOptions TEAL_DUST = PALETTE.dust(0.8f);
    private static final Particle.DustOptions TEAL_SMALL = PALETTE.dust(0.5f);
    private static final Particle.DustOptions FUR_DUST = new Particle.DustOptions(Color.fromRGB(0xF2EFE6), 0.9f);
    private static final Particle.DustOptions BROWN_DUST = new Particle.DustOptions(Color.fromRGB(0x7A5A3C), 1.0f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.1f);
    /** A thought balloon: pale fur white, a little see-through. */
    private static final Color BALLOON = Color.fromARGB(170, 242, 239, 230);
    /** The ultimate's balloons: dark teal glass, so the numbers read at a glance. */
    private static final Color HEARD_BALLOON = Color.fromARGB(150, 14, 52, 48);
    private static final Color CLEAR = Color.fromARGB(0, 0, 0, 0);

    /** Display entities one satori keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    /** Caps every velocity this ability sets. */
    private static final double REST_FALL = -Motion.GRAVITY * Motion.DRAG;
    private static final double HEAD_GAP = 0.15;

    // passive: who is aiming
    private static final long SCAN_PERIOD = 2;
    /** Aiming this long (ticks) before the balloon shows: a glance is not aiming. */
    private static final long AIM_LOCK = 5;
    /** The balloon stays this long (ticks) after the enemy looked away, so it does not flicker. */
    private static final long AIM_FORGET = 8;
    /** Aiming this long (ticks) turns the balloon urgent: something is coming. */
    private static final long AIM_URGENT = 30;
    private static final int MAX_BALLOONS = 6;
    private static final double BALLOON_ABOVE = 0.55;
    private static final float BALLOON_SCALE = 0.7f;
    /** The aim cone widens by the body's half width seen from the enemy, so close aim counts too. */
    private static final double BODY_HALF_WIDTH = 0.45;
    private static final long AURA_PERIOD = 40;

    // active: the read
    /** An enemy's active counts as ready while its cooldown is at most this (seconds): one tick of a running cooldown. */
    private static final double READY = 0.05 + 1.0E-6;
    /** A rise of the cooldown above this (seconds) from one tick to the next: the active was just used. */
    private static final double COOLDOWN_SET = 0.04;
    /** A drop of the ultimate gauge at least this large from one tick to the next: the ultimate was just used. */
    private static final double GAUGE_DROP = 50;
    private static final long TYPE_PERIOD = 5;
    private static final float READ_LABEL_SCALE = 0.9f;
    /**
     * Ticks a display following a player glides to each new spot: as long as a client lerps a moving player's body
     * (3 steps), so a follower moved every tick keeps pace with the body instead of trailing or jittering.
     */
    private static final int FOLLOW_LERP = 3;
    private static final long THREAD_PERIOD = 3;

    // ultimate: everything is heard
    private static final int MAX_HEARD = 8;
    private static final long HEARD_TEXT_PERIOD = 5;
    private static final long HEAR_SCAN_PERIOD = 10;
    private static final long WHISPER_PERIOD = 20;
    private static final float HEARD_SCALE = 0.62f;
    private static final double HEARD_ABOVE = 0.6;
    /** A heard enemy farther than the radius plus this stops being heard. */
    private static final double HEAR_MARGIN = 2;

    /** Damage types of the "accidents" a mind reader cannot see coming (with no attacker behind them). */
    private static final Set<DamageType> ACCIDENTS = Set.of(DamageType.FALL, DamageType.FLY_INTO_WALL,
            DamageType.LAVA, DamageType.IN_FIRE, DamageType.ON_FIRE, DamageType.CAMPFIRE, DamageType.HOT_FLOOR,
            DamageType.CACTUS, DamageType.SWEET_BERRY_BUSH, DamageType.IN_WALL, DamageType.DROWN, DamageType.FREEZE,
            DamageType.FALLING_BLOCK, DamageType.FALLING_ANVIL, DamageType.FALLING_STALACTITE, DamageType.STALAGMITE);

    /** Enemies aiming at the satori, by entity id. */
    private final Map<UUID, Aimer> aimers = new HashMap<>();
    /** Until which tick each enemy's damage to the satori is void (the counter's guard), by entity id. */
    private final Map<UUID, Long> guardUntil = new HashMap<>();
    private final List<Display> live = new ArrayList<>();
    /** The balloons only some players may see, and who may (see {@link #privateText}). */
    private final Map<Display, Set<UUID>> privateDisplays = new HashMap<>();
    private int lastAimerCount;
    private @Nullable Read read;
    private @Nullable Hearing hearing;
    private @Nullable Listener newcomers;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        Listener listener = new Newcomers();
        Bukkit.getPluginManager().registerEvents(listener, ctx().plugin());
        newcomers = listener;
    }

    @Override
    public void onRevoke() {
        Listener listener = newcomers;
        newcomers = null;
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
        aimers.clear();
        guardUntil.clear();
        live.clear();
        privateDisplays.clear();
        read = null;
        hearing = null;
    }

    // ================================================================== passive: the aiming mind

    /** One enemy aiming at the satori: since when, when it last aimed, and its balloon. */
    private static final class Aimer {
        final LivingEntity enemy;
        final long since;
        long lastAim;
        boolean urgent;
        @Nullable TextDisplay balloon;

        Aimer(LivingEntity enemy, long since) {
            this.enemy = enemy;
            this.since = since;
            this.lastAim = since;
        }
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        if (tick % SCAN_PERIOD == 0) {
            scanAimers(owner, now);
        }
        followBalloons();
        if (tick % AURA_PERIOD == 0) {
            aura(owner);
        }
        if (tick % 100 == 0) {
            guardUntil.values().removeIf(until -> until <= now);
        }
    }

    /** Finds the enemies looking straight at the satori and keeps their balloons up to date. */
    private void scanAimers(Player owner, long now) {
        double radius = Math.max(0, params().num("aim_radius"));
        double angle = Math.toRadians(Math.clamp(params().num("aim_angle"), 0.5, 89));
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        if (radius > 0) {
            for (Player other : ctx().alivePlayers()) {
                if (other.equals(owner) || !ctx().isEnemy(owner, other) || !other.getWorld().equals(owner.getWorld())
                        || !owner.canSee(other)) {
                    continue;
                }
                if (!isAiming(other, chest, radius, angle)) {
                    continue;
                }
                Aimer aimer = aimers.get(other.getUniqueId());
                if (aimer == null) {
                    aimers.put(other.getUniqueId(), new Aimer(other, now));
                } else {
                    aimer.lastAim = now;
                }
            }
        }
        int shown = 0;
        for (Iterator<Aimer> it = aimers.values().iterator(); it.hasNext(); ) {
            Aimer aimer = it.next();
            LivingEntity enemy = aimer.enemy;
            boolean gone = !Combat.isTargetable(ctx(), owner, enemy) || !enemy.getWorld().equals(owner.getWorld())
                    || isHidden(owner, enemy);
            if (gone || now - aimer.lastAim > AIM_FORGET) {
                popBalloon(aimer, !gone);
                it.remove();
                continue;
            }
            if (now - aimer.since < AIM_LOCK) {
                continue;
            }
            if (aimer.balloon == null || !aimer.balloon.isValid()) {
                if (shown >= MAX_BALLOONS || !room(1)) {
                    continue;
                }
                showBalloon(owner, aimer);
            }
            shown++;
            boolean urgent = now - aimer.since >= AIM_URGENT;
            if (urgent != aimer.urgent && aimer.balloon != null) {
                aimer.urgent = urgent;
                aimer.balloon.text(balloonText(urgent));
                if (urgent) {
                    owner.playSound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.5f,
                            1.9f);
                }
            }
        }
        int count = 0;
        for (Aimer aimer : aimers.values()) {
            if (aimer.balloon != null && aimer.balloon.isValid()) {
                count++;
            }
        }
        if (count != lastAimerCount) {
            if (count > lastAimerCount) {
                // A whisper only the satori hears: someone's mind just turned to it.
                Location eye = owner.getEyeLocation();
                owner.playSound(eye, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.45f,
                        1.7f + Fx.jitter(0.1f));
                owner.playSound(eye, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, SoundCategory.PLAYERS, 0.35f, 0.6f);
                owner.sendActionBar(Text.mm("<" + TEAL + ">◉</" + TEAL + "> <" + FUR + ">노리는 마음</" + FUR + "> <white>"
                        + count + "</white><gray>명</gray>"));
            }
            lastAimerCount = count;
        }
    }

    /**
     * True if {@code enemy} looks at {@code chest} within {@code angle} (radians) widened by the body's apparent half
     * width, from no farther than {@code radius}. Walls do not matter: it is the mind that is read, not the line of
     * sight.
     */
    private static boolean isAiming(LivingEntity enemy, Location chest, double radius, double angle) {
        Location eye = enemy.getEyeLocation();
        Vector to = chest.toVector().subtract(eye.toVector());
        double distance = to.length();
        if (distance > radius || distance < 1.0E-3) {
            return false;
        }
        Vector look = eye.getDirection();
        if (!(look.lengthSquared() > 1.0E-8)) {
            return false;
        }
        double allowed = angle + Math.atan2(BODY_HALF_WIDTH, distance);
        return look.normalize().dot(to.multiply(1 / distance)) >= Math.cos(allowed);
    }

    private static Component balloonText(boolean urgent) {
        if (urgent) {
            return Text.mm("<" + TEAL + "><b>◉</b></" + TEAL + "> <" + RED + "><b>!</b></" + RED + "> <" + DARK
                    + ">곧 온다</" + DARK + ">\n<" + BROWN + ">° ∘</" + BROWN + ">");
        }
        return Text.mm("<" + TEAL + "><b>◉</b></" + TEAL + "> <" + DARK + ">날 노린다</" + DARK + ">\n<" + BROWN
                + ">° ∘</" + BROWN + ">");
    }

    /** The balloon only the satori sees, over the aiming enemy's head and through every wall. */
    private void showBalloon(Player owner, Aimer aimer) {
        Location at = balloonSpot(aimer.enemy);
        TextDisplay balloon = keep(privateText(owner, at, balloonText(false), BALLOON_SCALE * 0.4f, BALLOON, 0,
                List.of(owner)));
        balloon.setTeleportDuration(FOLLOW_LERP);
        Displays.animateNextTick(ctx(), owner, balloon, Displays.centered(0, 0, 0, BALLOON_SCALE, BALLOON_SCALE,
                BALLOON_SCALE, 0, 0, 0), 4);
        aimer.balloon = balloon;
        owner.spawnParticle(Particle.DUST, at.getX(), at.getY() - 0.3, at.getZ(), Vfx.n(ctx(), 4), 0.15, 0.1, 0.15, 0,
                TEAL_SMALL);
    }

    private void popBalloon(Aimer aimer, boolean gently) {
        TextDisplay balloon = aimer.balloon;
        aimer.balloon = null;
        if (balloon == null || !balloon.isValid()) {
            return;
        }
        if (gently) {
            Displays.shrinkAway(ctx(), player(), balloon, 3);
        } else {
            balloon.remove();
        }
    }

    /**
     * The balloons follow their enemies every tick and glide as long as a client lerps a moving player
     * ({@link #FOLLOW_LERP}), so they keep pace with the heads instead of trailing and jittering behind them.
     */
    private void followBalloons() {
        for (Aimer aimer : aimers.values()) {
            TextDisplay balloon = aimer.balloon;
            if (balloon != null && balloon.isValid() && balloon.getWorld().equals(aimer.enemy.getWorld())) {
                Displays.moveTo(balloon, balloonSpot(aimer.enemy));
            }
        }
    }

    private static Location balloonSpot(LivingEntity enemy) {
        return enemy.getLocation().add(0, enemy.getHeight() + BALLOON_ABOVE, 0);
    }

    /** Now and then a teal glint at the temples and a tuft of white fur: the mind at work. */
    private void aura(Player owner) {
        if (owner.isInvisible() || owner.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)) {
            return;
        }
        Location head = owner.getEyeLocation().add(0, 0.25, 0);
        Vector facing = Motion.horizontalFacing(owner.getLocation());
        Vector side = new Vector(-facing.getZ(), 0, facing.getX()).multiply(0.28);
        Vfx.dot(head.clone().add(side), Particle.DUST, TEAL_SMALL, false);
        Vfx.dot(head.clone().subtract(side), Particle.DUST, TEAL_SMALL, false);
        Vfx.cloud(ctx(), owner.getLocation().add(0, owner.getHeight() * 0.5, 0), Particle.WHITE_ASH, 3, 0.25, 0.01,
                null, false);
    }

    // ================================================================== damage: guard, counter, slip, accidents

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Player owner = player();
        long now = ctx().currentTick();
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            Player attacker = ctx().resolveAttacker(damager);
            Read current = read;
            if (current != null && !current.done && current.isFrom(damager, attacker)) {
                event.setCancelled(true);
                current.counter("<" + FUR + ">공격</" + FUR + ">");
                return;
            }
            if (isGuarded(damager, attacker, now)) {
                event.setCancelled(true);
                deflectFx(owner);
                return;
            }
            Hearing heard = hearing;
            if (heard != null && !heard.done && heard.slip(damager, attacker)) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getDamageSource().getCausingEntity() == null
                && ACCIDENTS.contains(event.getDamageSource().getDamageType())) {
            double weakness = Math.max(0, params().num("accident_weakness")) / 100.0;
            if (weakness > 0 && event.getDamage() > 0) {
                event.setDamage(event.getDamage() * (1 + weakness));
                accidentFx(owner);
            }
        }
    }

    private boolean isGuarded(Entity damager, @Nullable Player attacker, long now) {
        Long byDamager = guardUntil.get(damager.getUniqueId());
        if (byDamager != null && byDamager > now) {
            return true;
        }
        Long byAttacker = attacker == null ? null : guardUntil.get(attacker.getUniqueId());
        return byAttacker != null && byAttacker > now;
    }

    /** A blow the counter's guard turns aside: a teal shimmer where it would have landed. */
    private void deflectFx(Player owner) {
        Location body = Fx.body(owner);
        Vfx.ring(ctx(), body, 0.6, 10, ctx().currentTick() * 0.3, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
        Vfx.cloud(ctx(), body, Particle.ENCHANTED_HIT, 5, 0.3, 0.1, null, false);
        Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 1.6f + Fx.jitter(0.1f));
    }

    /** An accident the mind reader never saw coming: a startled "!?" and a puff of fur. */
    private void accidentFx(Player owner) {
        Location head = owner.getEyeLocation().add(0, 0.5, 0);
        Vfx.cloud(ctx(), Fx.body(owner), Particle.DUST, 6, 0.3, 0, FUR_DUST, false);
        Vfx.cloud(ctx(), Fx.body(owner), Particle.DUST, 4, 0.3, 0, BROWN_DUST, false);
        Fx.sound(head, Sound.ENTITY_FOX_HURT, 0.7f, 1.5f + Fx.jitter(0.1f));
        popText(head, "<b><" + GOLD + ">!?</" + GOLD + "></b>", 0.9f, 14);
    }

    /** The ultimate's ears make every hit on a heard enemy harder. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        Hearing heard = hearing;
        if (event.isCancelled() || heard == null || heard.done || !heard.hears(victim)) {
            return;
        }
        double bonus = Math.max(0, params().num("ult_bonus")) / 100.0;
        if (bonus > 0) {
            event.setDamage(event.getDamage() * (1 + bonus));
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        Hearing heard = hearing;
        if (heard != null && !heard.done && heard.hears(victim) && !isHidden(player(), victim)) {
            Location head = head(victim);
            Vfx.ring(ctx(), head, 0.3, 6, 0, Particle.DUST, TEAL_SMALL, false);
        }
    }

    // ================================================================== active: reading ahead

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("read_range"));
        LivingEntity target = ctx().findTarget(owner, range);
        if (target == null) {
            return ActivationResult.fail("<gray>마음을 읽을 적이 없습니다 (" + AbilityInfo.formatNumber(range)
                    + "블록 이내)</gray>");
        }
        Read previous = read;
        if (previous != null && !previous.done) {
            previous.finish(false);
        }
        Read reading = new Read(owner, target);
        read = reading;
        reading.start();
        return ActivationResult.success();
    }

    /**
     * Reading one enemy's mind: the typed "…" over its head, a faint thread to the satori, its cooldown and gauge
     * watched every tick. Whatever it does meanwhile is countered; if it does nothing, half the cooldown comes back.
     */
    private final class Read implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final long ticks;
        private double lastCooldown;
        private double lastGauge;
        private @Nullable TextDisplay label;
        private boolean labelHidden;
        boolean done;

        Read(Player owner, LivingEntity target) {
            this.owner = owner;
            this.target = target;
            this.ticks = Math.max(1, params().ticks("read_duration"));
            if (target instanceof Player enemy) {
                this.lastCooldown = ctx().cooldownRemaining(enemy);
                this.lastGauge = ctx().gauge(enemy);
            }
        }

        boolean isFrom(Entity damager, @Nullable Player attacker) {
            return damager.equals(target) || target.equals(attacker);
        }

        void start() {
            Location eye = owner.getEyeLocation();
            Location head = head(target);
            if (room(1)) {
                label = keep(Displays.text(ctx(), owner, head.clone().add(0, 0.25, 0), dots(0),
                        READ_LABEL_SCALE * 0.4f, ticks + 30));
                label.setTeleportDuration(FOLLOW_LERP);
                Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0, 0, READ_LABEL_SCALE,
                        READ_LABEL_SCALE, READ_LABEL_SCALE, 0, 0, 0), 4);
            }
            updateLabel();
            for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                Vfx.trail(eye, head, i % 2 == 0 ? TEAL_COLOR : PALETTE.core(), 5 + i * 2, false);
            }
            Vfx.ring(ctx(), head, 0.45, 12, 0, Particle.DUST_COLOR_TRANSITION, GLOW, false);
            Vfx.cloud(ctx(), head, Particle.ENCHANT, 10, 0.3, 0.6, null, false);
            Fx.sound(eye, Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.9f, 1.4f + Fx.jitter(0.1f));
            Fx.sound(eye, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.2f + Fx.jitter(0.05f));
            Fx.sound(head, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.6f, 0.5f);
            if (target instanceof Player enemy) {
                enemy.sendActionBar(Text.mm("<" + TEAL + "><b>마음이 읽히고 있다…</b></" + TEAL + "> <gray>지금 무엇을 "
                        + "하든 들킨다</gray>"));
            }
            owner.sendActionBar(Text.mm("<" + TEAL + "><b>선읽기</b></" + TEAL + "> <gray>"
                    + Fx.seconds(ticks) + "초간 마음을 읽습니다</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, ticks, this, () -> finish(false));
        }

        @Override
        public boolean test(long run) {
            if (done) {
                return false;
            }
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(owner.getWorld())) {
                finish(true);
                return false;
            }
            if (target instanceof Player enemy) {
                double cooldown = ctx().cooldownRemaining(enemy);
                double gauge = ctx().gauge(enemy);
                boolean active = lastCooldown <= READY && cooldown > lastCooldown + COOLDOWN_SET;
                boolean ultimate = lastGauge - gauge >= GAUGE_DROP;
                lastCooldown = cooldown;
                lastGauge = gauge;
                if (active || ultimate) {
                    counter(ultimate ? "<" + GOLD + ">궁극기</" + GOLD + ">" : "<" + FUR + ">스킬</" + FUR + ">");
                    return false;
                }
            }
            updateLabel();
            if (run % TYPE_PERIOD == 0 && label != null && label.isValid()) {
                label.text(dots((int) (run / TYPE_PERIOD)));
            }
            if (!labelHidden) {
                drawReading(run);
            }
            return true;
        }

        /** The label follows the head and hides while the target is hidden from the satori (it must not give it away). */
        private void updateLabel() {
            boolean hidden = isHidden(owner, target);
            TextDisplay text = label;
            if (text == null || !text.isValid()) {
                labelHidden = hidden;
                return;
            }
            if (hidden != labelHidden) {
                labelHidden = hidden;
                text.setViewRange(hidden ? 0f : 1.5f);
            }
            Displays.moveTo(text, head(target).add(0, 0.25, 0));
        }

        /** A turning teal halo over the read head and, now and then, the thread from the satori's mind to it. */
        private void drawReading(long run) {
            Location head = head(target);
            if (run % 2 == 0) {
                Vfx.ring(ctx(), head.clone().add(0, 0.05, 0), 0.38, 8, run * 0.35, Particle.DUST, TEAL_SMALL, false);
            }
            if (run % THREAD_PERIOD == 0) {
                Vfx.trail(owner.getEyeLocation().add(0, 0.2, 0), head, run % 2 == 0 ? TEAL_COLOR : DEEP_TEAL, 6,
                        false);
            }
            if (run % 8 == 0) {
                Vfx.cloud(ctx(), head, Particle.ENCHANT, 3, 0.25, 0.4, null, false);
                Fx.sound(head, Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.35f, 1.8f + Fx.jitter(0.1f));
            }
        }

        /**
         * "지금 그거 쓰려고 했지?": the enemy's damage to the satori is void for the guard, and it is struck and stunned.
         * Called from the satori's damage hook too, so the strike follows a tick later (the enemy's blow is still being
         * processed).
         */
        void counter(String what) {
            if (done) {
                return;
            }
            done = true;
            if (read == this) {
                read = null;
            }
            long now = ctx().currentTick();
            long guard = params().ticks("counter_guard");
            if (guard > 0) {
                guardUntil.put(target.getUniqueId(), now + guard);
            }
            long stun = params().ticks("counter_stun");
            boolean hidden = isHidden(owner, target);
            counterFx(what, hidden, guard);
            if (stun > 0) {
                stun(owner, target, stun, hidden);
            }
            double damage = Math.max(0, params().num("counter_damage"));
            ctx().runLater(owner, 1, () -> Combat.hit(ctx(), owner, target, damage));
            TextDisplay text = label;
            label = null;
            if (text != null && text.isValid()) {
                text.text(Text.mm("<b><" + RED + ">!</" + RED + "></b>"));
                Displays.animate(text, Displays.centered(0, 0.2f, 0, 1.8f, 1.8f, 1.8f, 0, 0, 0), 3);
                ctx().runLater(owner, 10, () -> {
                    if (text.isValid()) {
                        Displays.shrinkAway(ctx(), owner, text, 4);
                    }
                });
            }
        }

        private void counterFx(String what, boolean hidden, long guard) {
            Location eye = owner.getEyeLocation();
            owner.sendActionBar(Text.mm("<" + TEAL + "><b>받아치기!</b></" + TEAL + "> " + what + " <gray>읽힘 · "
                    + Fx.seconds(guard) + "초간 그 적의 피해 무효</gray>"));
            Fx.sound(eye, Sound.ENTITY_EVOKER_CAST_SPELL, 1.0f, 1.5f + Fx.jitter(0.05f));
            Fx.sound(eye, Sound.ENTITY_FOX_SCREECH, 0.6f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(eye, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.9f, 0.8f + Fx.jitter(0.05f));
            popText(eye.clone().add(0, 0.75, 0), "<b><" + TEAL + ">\"지금 그거 쓰려고 했지?\"</" + TEAL + "></b>", 1.0f,
                    30);
            guardAura(owner, guard);
            if (hidden) {
                return;
            }
            Location head = head(target);
            // The spike: a straight teal line from the satori's mind into the enemy's head, then a flash.
            Vfx.line(ctx(), eye.clone().add(0, 0.2, 0), head, 4, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Vfx.line(ctx(), eye.clone().add(0, 0.2, 0), head, 1.5, Particle.END_ROD, null, false);
            Vfx.burst(ctx(), head, PALETTE, 0.8, Particle.ENCHANTED_HIT, true);
            Vfx.shockwave(ctx(), owner, head, 0.2, 1.2, 4, Particle.DUST, TEAL_DUST, false);
            Fx.sound(head, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.8f + Fx.jitter(0.08f));
            Fx.sound(head, Sound.BLOCK_BELL_USE, 0.6f, 1.9f);
        }

        /** The read ran out (or the target slipped away): a gray "?" and, if nothing happened, the refund. */
        void finish(boolean lost) {
            if (done) {
                return;
            }
            done = true;
            if (read == this) {
                read = null;
            }
            TextDisplay text = label;
            label = null;
            if (text != null && text.isValid()) {
                text.text(Text.mm("<gray><b>?</b></gray>"));
                Displays.shrinkAway(ctx(), owner, text, 6);
            }
            if (!lost && !labelHidden && target.isValid()) {
                Location head = head(target);
                Vfx.cloud(ctx(), head, Particle.DUST, 6, 0.25, 0, FUR_DUST, false);
                Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 1.2f);
            }
            if (!ctx().isAlive(owner)) {
                return;
            }
            double left = ctx().cooldownRemaining(owner);
            double refund = Math.clamp(params().num("read_refund"), 0, 100) / 100.0;
            if (left > 0 && refund > 0) {
                ctx().reduceCooldown(owner, left * refund);
            }
            owner.sendActionBar(Text.mm("<gray>읽을 생각이 없었습니다</gray> <dark_gray>(쿨타임 "
                    + AbilityInfo.formatNumber(refund * 100) + "% 회복)</dark_gray>"));
        }

        private Component dots(int step) {
            String typed = switch (Math.floorMod(step, 3)) {
                case 0 -> ".";
                case 1 -> "..";
                default -> "...";
            };
            return Text.mm("<b><" + TEAL + ">" + typed + "</" + TEAL + "></b>");
        }
    }

    /** The counter's guard: a teal ring turning at the satori's feet while the enemy's blows cannot land. */
    private void guardAura(Player owner, long ticks) {
        if (ticks <= 0) {
            return;
        }
        Loop.repeat(ctx(), owner, 1, 2, Math.max(1, ticks / 2), run -> {
            Location feet = owner.getLocation().add(0, 0.1, 0);
            Vfx.ring(ctx(), feet, 0.7, 10, run * 0.5, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
            if (run % 3 == 0) {
                Vfx.dot(owner.getEyeLocation().add(0, 0.45, 0), Particle.END_ROD, null, false);
            }
            return true;
        });
    }

    /**
     * Stunned for {@code ticks}: no walking, no jumping, no skills, its momentum gone; a teal ring of stars wobbles over
     * its head.
     */
    private void stun(Player owner, LivingEntity target, long ticks, boolean hidden) {
        List<NamespacedKey> keys = new ArrayList<>(2);
        keys.add(ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, ticks));
        keys.add(ctx().addModifier(owner, target, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, ticks));
        if (target instanceof Player enemy) {
            ctx().silence(enemy, ticks);
            enemy.setVelocity(new Vector(0, REST_FALL, 0));
        } else {
            target.setVelocity(new Vector(0, Math.min(0, target.getVelocity().getY()), 0));
        }
        if (hidden) {
            return;
        }
        Loop.repeat(ctx(), owner, 2, 2, Math.max(1, ticks / 2), run -> {
            if (!target.isValid() || target.isDead() || isHidden(owner, target)) {
                return false;
            }
            Location head = head(target).add(0, 0.1, 0);
            double tilt = run * 0.9;
            Vector normal = new Vector(Math.cos(tilt) * 0.5, 1, Math.sin(tilt) * 0.5);
            ringAround(head, normal, 0.45, 9, run * 0.8, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
            if (run % 2 == 0) {
                Vfx.dot(head.clone().add(Math.cos(run * 1.7) * 0.45, 0, Math.sin(run * 1.7) * 0.45), Particle.WAX_OFF,
                        null, false);
            }
            return true;
        });
    }

    // ================================================================== ultimate: everything is heard

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = Math.max(1, params().num("ult_radius"));
        boolean anyone = false;
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), radius)) {
            if (!isHidden(owner, enemy)) {
                anyone = true;
                break;
            }
        }
        if (!anyone) {
            return ActivationResult.fail("<gray>반경 " + AbilityInfo.formatNumber(radius)
                    + "블록 안에 마음이 들리는 적이 없습니다</gray>");
        }
        Hearing previous = hearing;
        if (previous != null && !previous.done) {
            previous.end();
        }
        Hearing heard = new Hearing(owner, radius);
        hearing = heard;
        heard.start();
        return ActivationResult.success();
    }

    /** One heard enemy: its balloon (for the satori's team) and whether its first blow already slipped past. */
    private static final class Heard {
        final LivingEntity enemy;
        @Nullable TextDisplay balloon;
        boolean slipped;
        boolean inside = true;

        Heard(LivingEntity enemy) {
            this.enemy = enemy;
        }
    }

    /**
     * Every mind within the radius, for the ultimate's time: enemies inside are heard (balloons with their next moves
     * for the satori's team, a whisper on their screens), each one's first blow at the satori slips past, and the
     * satori hits them harder.
     */
    private final class Hearing implements LongPredicate {

        private final Player owner;
        private final double radius;
        private final long ticks;
        private final Map<UUID, Heard> heard = new HashMap<>();
        boolean done;

        Hearing(Player owner, double radius) {
            this.owner = owner;
            this.radius = radius;
            this.ticks = Math.max(1, params().ticks("ult_duration"));
        }

        void start() {
            Location feet = owner.getLocation().add(0, 0.1, 0);
            Location eye = owner.getEyeLocation();
            Vfx.dot(eye, Particle.FLASH, TEAL_COLOR, true);
            Vfx.shockwave(ctx(), owner, feet, 0.5, radius, 12, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Vfx.helix(ctx(), owner.getLocation(), 0.7, 2.4, 2, 2, 14, 0, Particle.DUST, TEAL_DUST, true);
            Vfx.cloud(ctx(), eye, Particle.ENCHANT, 30, 0.8, 1.0, null, true);
            Fx.sound(eye, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.2f, 0.8f);
            Fx.sound(eye, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.6f, 1.6f);
            Fx.sound(eye, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 0.6f);
            Fx.sound(eye, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.8f, 0.5f);
            popText(eye.clone().add(0, 0.8, 0), "<b><" + TEAL + ">다 들린다…</" + TEAL + "></b>", 1.3f, 26);
            scan();
            owner.sendActionBar(Text.mm("<" + TEAL + "><b>다 들린다</b></" + TEAL + "> <white>" + heard.size()
                    + "</white><gray>명의 마음</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, ticks, this, this::end);
        }

        boolean hears(LivingEntity entity) {
            Heard entry = heard.get(entity.getUniqueId());
            return entry != null && entry.inside;
        }

        /** The first blow of a heard enemy slips past ("그럴 줄 알았어"). */
        boolean slip(Entity damager, @Nullable Player attacker) {
            Heard entry = heard.get(damager.getUniqueId());
            if (entry == null && attacker != null) {
                entry = heard.get(attacker.getUniqueId());
            }
            if (entry == null || entry.slipped) {
                return false;
            }
            entry.slipped = true;
            slipFx(entry);
            return true;
        }

        private void slipFx(Heard entry) {
            Location body = Fx.body(owner);
            Location head = owner.getEyeLocation().add(0, 0.7, 0);
            Vfx.helix(ctx(), owner.getLocation(), 0.55, owner.getHeight(), 1.2, 2, 10, ctx().currentTick() * 0.4,
                    Particle.DUST_COLOR_TRANSITION, FADE, false);
            Vfx.cloud(ctx(), body, Particle.DUST, 8, 0.35, 0, FUR_DUST, false);
            Vfx.dot(body, Particle.FLASH, TEAL_COLOR, false);
            Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.4f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_BREEZE_WHIRL, 0.6f, 1.6f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_FOX_AMBIENT, 0.5f, 1.5f);
            popText(head, "<b><" + TEAL + ">\"그럴 줄 알았어\"</" + TEAL + "></b>", 0.95f, 24);
            owner.sendActionBar(Text.mm("<" + TEAL + "><b>흘려 냄</b></" + TEAL + "> <gray>읽어 둔 첫 공격</gray>"));
            TextDisplay balloon = entry.balloon;
            if (balloon != null && balloon.isValid()) {
                balloon.text(heardText(entry));
            }
        }

        @Override
        public boolean test(long run) {
            if (done) {
                return false;
            }
            if (run % HEAR_SCAN_PERIOD == 0) {
                scan();
            }
            boolean text = run % HEARD_TEXT_PERIOD == 0;
            for (Heard entry : heard.values()) {
                if (!entry.inside) {
                    continue;
                }
                LivingEntity enemy = entry.enemy;
                TextDisplay balloon = entry.balloon;
                boolean hidden = isHidden(owner, enemy);
                if (balloon != null && balloon.isValid()) {
                    balloon.setViewRange(hidden ? 0f : 1.5f);
                    if (balloon.getWorld().equals(enemy.getWorld())) {
                        Displays.moveTo(balloon, heardSpot(enemy));
                    }
                    if (text) {
                        balloon.text(heardText(entry));
                    }
                }
                if (!hidden && run % 4 == Math.floorMod(enemy.getUniqueId().hashCode(), 4)) {
                    Location head = head(enemy);
                    Vfx.ring(ctx(), head.clone().add(0, 0.05, 0), 0.35, 7, run * 0.3, Particle.DUST, TEAL_SMALL,
                            false);
                }
                if (run % WHISPER_PERIOD == 0 && enemy instanceof Player listener) {
                    listener.sendActionBar(Text.mm("<" + TEAL + ">생각이 읽히고 있다</" + TEAL + "> <dark_gray>·</dark_gray> "
                            + "<gray>" + Fx.seconds(ticks - run) + "초</gray>"));
                }
            }
            if (run % WHISPER_PERIOD == 0) {
                whisper(run);
            }
            return true;
        }

        /** Enemies coming into the radius are heard; those that walk well out of it are not any more. */
        private void scan() {
            Location center = owner.getLocation();
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                if (isHidden(owner, enemy)) {
                    continue;
                }
                Heard entry = heard.get(enemy.getUniqueId());
                if (entry == null) {
                    if (heard.size() >= MAX_HEARD * 2) {
                        continue;
                    }
                    entry = new Heard(enemy);
                    heard.put(enemy.getUniqueId(), entry);
                    hearFx(enemy);
                }
                entry.inside = true;
                if ((entry.balloon == null || !entry.balloon.isValid()) && countBalloons() < MAX_HEARD && room(1)) {
                    showHeard(entry);
                }
            }
            double limit = radius + HEAR_MARGIN;
            for (Heard entry : heard.values()) {
                LivingEntity enemy = entry.enemy;
                boolean out = !Combat.isTargetable(ctx(), owner, enemy) || !enemy.getWorld().equals(center.getWorld())
                        || enemy.getLocation().distanceSquared(center) > limit * limit;
                if (out && entry.inside) {
                    entry.inside = false;
                    remove(entry.balloon);
                    entry.balloon = null;
                }
            }
        }

        private int countBalloons() {
            int count = 0;
            for (Heard entry : heard.values()) {
                if (entry.balloon != null && entry.balloon.isValid()) {
                    count++;
                }
            }
            return count;
        }

        /** A balloon with the enemy's next moves, seen by the satori and its allies only. */
        private void showHeard(Heard entry) {
            List<Player> viewers = new ArrayList<>();
            viewers.add(owner);
            for (Player other : ctx().alivePlayers()) {
                if (ctx().isAlly(owner, other)) {
                    viewers.add(other);
                }
            }
            TextDisplay balloon = keep(privateText(owner, heardSpot(entry.enemy), heardText(entry), HEARD_SCALE * 0.4f,
                    HEARD_BALLOON, ticks + 10, viewers));
            balloon.setTeleportDuration(FOLLOW_LERP);
            Displays.animateNextTick(ctx(), owner, balloon, Displays.centered(0, 0, 0, HEARD_SCALE, HEARD_SCALE,
                    HEARD_SCALE, 0, 0, 0), 5);
            entry.balloon = balloon;
        }

        private void hearFx(LivingEntity enemy) {
            Location head = head(enemy);
            Vfx.trail(owner.getEyeLocation(), head, TEAL_COLOR, 8, false);
            Vfx.cloud(ctx(), head, Particle.ENCHANT, 8, 0.3, 0.5, null, false);
            Fx.sound(head, Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.6f, 1.2f + Fx.jitter(0.1f));
            if (enemy instanceof Player listener) {
                listener.playSound(listener.getEyeLocation(), Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM,
                        SoundCategory.PLAYERS, 0.7f, 0.5f);
            }
        }

        /** A whisper ring rolling out over the ground from the satori, marking how far it hears. */
        private void whisper(long run) {
            Location feet = owner.getLocation().add(0, 0.15, 0);
            Vfx.shockwave(ctx(), owner, feet, 1.0, radius, 10, Particle.DUST, TEAL_SMALL, false);
            Fx.sound(feet, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.45f, 1.3f + 0.1f * (run / WHISPER_PERIOD % 4));
        }

        void end() {
            if (done) {
                return;
            }
            done = true;
            if (hearing == this) {
                hearing = null;
            }
            for (Heard entry : heard.values()) {
                TextDisplay balloon = entry.balloon;
                entry.balloon = null;
                if (balloon != null && balloon.isValid()) {
                    Displays.shrinkAway(ctx(), owner, balloon, 5);
                }
            }
            heard.clear();
            if (ctx().isAlive(owner)) {
                Location eye = owner.getEyeLocation();
                Vfx.cloud(ctx(), eye, Particle.DUST, 8, 0.4, 0, FUR_DUST, false);
                Fx.sound(eye, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 0.5f);
                Fx.sound(eye, Sound.BLOCK_SCULK_SENSOR_CLICKING_STOP, 0.6f, 1.0f);
            }
        }
    }

    /** "액티브 3.5초 · 궁 72%" (players), and whether the first blow is still being read. */
    private Component heardText(Heard entry) {
        StringBuilder text = new StringBuilder();
        if (entry.enemy instanceof Player enemy) {
            if (ctx().isSilenced(enemy)) {
                text.append("<gray>침묵 중</gray>");
            } else {
                double cooldown = ctx().cooldownRemaining(enemy);
                if (cooldown <= READY) {
                    text.append("<#7CFFB2><b>액티브 준비</b></#7CFFB2>");
                } else {
                    text.append("<").append(FUR).append(">액티브</").append(FUR).append("> <white>")
                            .append(AbilityInfo.formatNumber(Math.ceil(cooldown * 10) / 10)).append("초</white>");
                }
            }
            int gauge = (int) Math.floor(ctx().gauge(enemy));
            text.append(" <dark_gray>·</dark_gray> ");
            if (gauge >= 100) {
                text.append("<").append(RED).append("><b>궁 100%!</b></").append(RED).append(">");
            } else {
                text.append("<").append(FUR).append(">궁</").append(FUR).append("> <").append(GOLD).append(">")
                        .append(gauge).append("%</").append(GOLD).append(">");
            }
        } else {
            text.append("<gray>…</gray>");
        }
        text.append("\n");
        if (entry.slipped) {
            text.append("<dark_gray>첫 공격 흘림 ✔</dark_gray>");
        } else {
            text.append("<").append(TEAL).append(">◉ 첫 공격을 읽는 중</").append(TEAL).append(">");
        }
        return Text.mm(text.toString());
    }

    private static Location heardSpot(LivingEntity enemy) {
        return enemy.getLocation().add(0, enemy.getHeight() + HEARD_ABOVE, 0);
    }

    // ================================================================== helpers

    /**
     * A text display only {@code viewers} see (through walls), with a balloon background. Hidden from everyone else in
     * the same tick it spawns (before the server sends it to anybody), so it never flashes for an enemy, and from
     * whoever joins later (an enemy rejoining the match is a new player object that would see every entity; see
     * {@link Newcomers}).
     */
    private TextDisplay privateText(Player owner, Location at, Component text, float scale, Color background,
                                    long lifetime, List<Player> viewers) {
        TextDisplay display = Displays.spawn(ctx(), owner, at, TextDisplay.class, lifetime, d -> {
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(background);
            d.setSeeThrough(true);
            d.setShadowed(false);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setTransformation(Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, 0));
        });
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!viewers.contains(online)) {
                online.hideEntity(ctx().plugin(), display);
            }
        }
        Set<UUID> allowed = new HashSet<>();
        for (Player viewer : viewers) {
            allowed.add(viewer.getUniqueId());
        }
        privateDisplays.put(display, allowed);
        return display;
    }

    /** Whoever joins (or rejoins) the server never sees the balloons that are not theirs. */
    private final class Newcomers implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            Player joined = event.getPlayer();
            privateDisplays.keySet().removeIf(display -> !display.isValid());
            for (Map.Entry<Display, Set<UUID>> entry : privateDisplays.entrySet()) {
                if (!entry.getValue().contains(joined.getUniqueId())) {
                    joined.hideEntity(ctx().plugin(), entry.getKey());
                }
            }
        }
    }

    /** A word popping up and shrinking away, for everyone. */
    private void popText(Location at, String miniMessage, float scale, long life) {
        if (!room(1)) {
            return;
        }
        Player owner = player();
        TextDisplay text = keep(Displays.text(ctx(), owner, at, Text.mm(miniMessage), scale * 0.5f, life));
        text.setBackgroundColor(CLEAR);
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.3f, 0, scale, scale, scale, 0, 0, 0), 4);
        ctx().runLater(owner, Math.max(2, life - 5), () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 4);
            }
        });
    }

    private <T> void ringAround(Location center, Vector normal, double radius, int points, double phase,
                                Particle particle, @Nullable T data, boolean force) {
        Vector n = normal.lengthSquared() < 1.0E-6 ? new Vector(0, 1, 0) : normal.clone().normalize();
        Vector helper = Math.abs(n.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = n.clone().crossProduct(helper).normalize();
        Vector v = n.clone().crossProduct(u).normalize();
        int count = Vfx.n(ctx(), points);
        World world = center.getWorld();
        for (int i = 0; i < count; i++) {
            double angle = phase + i * TAU / count;
            Location at = center.clone().add(u.clone().multiply(Math.cos(angle) * radius))
                    .add(v.clone().multiply(Math.sin(angle) * radius));
            world.spawnParticle(particle, at, 1, 0, 0, 0, 0, data, force);
        }
    }

    private static Location head(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() + HEAD_GAP, 0);
    }

    private static boolean isHidden(Player owner, LivingEntity entity) {
        return entity instanceof Player hidden && !owner.canSee(hidden);
    }

    private boolean room(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    private static void remove(@Nullable Display display) {
        if (display != null && display.isValid()) {
            display.remove();
        }
    }
}
