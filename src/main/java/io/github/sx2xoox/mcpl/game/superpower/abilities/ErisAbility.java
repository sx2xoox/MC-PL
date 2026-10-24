package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 에리스 — the goddess of discord who threw the golden apple "to the fairest" and started a war. She profits from every
 * fight she did not start (her hits land harder on enemies someone else just hurt), the active throws the apple of
 * discord where anyone may grab it — an enemy who does wears the crown of the fairest and becomes everyone's target, a
 * friend just gets a bite, and an apple nobody takes bursts into discord — and the ultimate sows discord: enemies
 * standing close together are chained and hurt each other just by being near.
 *
 * <p>Unlike the 주술사's linked pain or the 큐피드's pull, the ultimate punishes closeness itself: the chains push the
 * infected apart and only enemies with company are hurt. The apple is a bait that pays off either way.
 *
 * <p>Look: discord crimson, apple gold and deep purple. The apple spins in a high lob trailing gold and lies glowing
 * with its inscription floating over it ("가장 아름다운 자에게"); it shakes and pulses crimson before it bursts. The
 * fairest wears a turning gold crown with a ruby; the ultimate plants dark seeds over the infected heads and draws
 * sagging crimson chains between neighbours that jerk with every pulse.
 */
public final class ErisAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("eris", "에리스", Material.GOLDEN_APPLE)
            .color(TextColor.color(0xF0C23A))
            .difficulty(2)
            .passive("어부지리",
                    "최근 {opportunist_window}초 안에 다른 누군가(다른 플레이어나 소환수)에게 피해를 받은 적에게 주는 내 피해가 "
                            + "{opportunist_bonus}% 늘어납니다. 그런 적의 머리 위에는 나에게만 보이는 진홍 불씨가 깜박입니다.")
            .active("불화의 사과",
                    "조준한 곳({apple_range}블록 이내)에 황금 사과를 던집니다. 사과는 떨어지고 {apple_arm}초 뒤부터 "
                            + "{apple_duration}초간 빛나며 처음 닿은 사람이 가져갑니다. 적이 가지면 {bait_heal} 회복하는 대신 {fairest_duration}초간 '가장 아름다운 자'가 "
                            + "되어 빛나고(발광) 황금 왕관을 쓰며, 자신의 적들에게 받는 모든 피해가 {fairest_bonus}% 늘어납니다. "
                            + "나나 아군이 가지면 {friend_heal} 회복합니다. 아무도 줍지 않으면 사과가 터져 반경 {burst_radius}블록의 "
                            + "적에게 {burst_damage} 피해를 주고 {discord_duration}초간 불화에 빠뜨립니다(그동안 내 어부지리가 늘 "
                            + "적용됩니다).",
                    9)
            .ultimate("불화의 씨앗",
                    "반경 {ult_radius}블록의 적 모두에게 {ult_duration}초간 불화의 씨앗을 심습니다. 씨앗이 심긴 두 적이 "
                            + "{chain_range}블록 안에 있으면 붉은 사슬이 이어져 {chain_period}초마다 둘 다 {chain_damage} 피해를 입고 "
                            + "서로 밀려납니다(적마다 사슬은 최대 {max_chains}개). 혼자 떨어진 적은 무사합니다. 개인전에서는 씨앗이 "
                            + "심긴 적끼리 서로 주는 피해도 {ffa_bonus}% 늘어납니다.")
            .param("opportunist_window", 3)
            .param("opportunist_bonus", 20)
            .param("apple_range", 20)
            .param("apple_arm", 0.5)
            .param("apple_duration", 6)
            .param("bait_heal", 3)
            .param("fairest_duration", 6)
            .param("fairest_bonus", 25)
            .param("friend_heal", 2)
            .param("burst_radius", 4)
            .param("burst_damage", 3)
            .param("discord_duration", 3)
            .param("ult_radius", 14)
            .param("ult_duration", 6)
            .param("chain_range", 4)
            .param("chain_period", 0.5)
            .param("chain_damage", 0.5)
            .param("max_chains", 2)
            .param("chain_push", 0.45)
            .param("ffa_bonus", 20)
            .build();

    private static final String GOLD = "#F0C23A";
    private static final String CRIMSON = "#D23A4E";
    private static final String PURPLE = "#8E5BB8";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: crimson, gold, deep purple
    /** Apple gold, discord crimson, deep purple. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF0C23A, 0xB0172E, 0x3E1F55);
    private static final Color GOLD_COLOR = Color.fromRGB(0xF0C23A);
    private static final Color CRIMSON_COLOR = Color.fromRGB(0xB0172E);
    private static final Color PURPLE_COLOR = Color.fromRGB(0x3E1F55);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD_COLOR, 0.9f);
    private static final Particle.DustOptions GOLD_SMALL = new Particle.DustOptions(GOLD_COLOR, 0.55f);
    private static final Particle.DustOptions CRIMSON_DUST = PALETTE.dust(0.8f);
    private static final Particle.DustOptions CRIMSON_SMALL = PALETTE.dust(0.55f);
    private static final Particle.DustOptions PURPLE_DUST = new Particle.DustOptions(PURPLE_COLOR, 0.8f);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.1f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.0f);
    private static final Particle.DustTransition EMBER = new Particle.DustTransition(Color.fromRGB(0xFF6B7D),
            CRIMSON_COLOR, 0.6f);

    /** Item and block types of the visuals; block data and items are created on use (they need a running server). */
    private static final Material APPLE = Material.GOLDEN_APPLE;
    private static final Material SEED = Material.NETHER_WART;
    private static final Material CROWN_GOLD = Material.GOLD_BLOCK;
    private static final Material RUBY = Material.RED_STAINED_GLASS;

    /** Display entities one eris keeps alive at most at once (extra visuals fall back to particles). */
    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_PUSH = 0.9;
    private static final double HEAD_GAP = 0.15;

    // passive
    private static final long MARK_PERIOD = 6;
    private static final double MARK_RANGE = 28;
    /** The same victim shows the opportunist's flourish at most this often (ticks). */
    private static final long PROC_FX_PERIOD = 10;

    // active: the apple
    private static final double APPLE_SPEED = 0.95;
    private static final long MIN_FLIGHT = 6;
    private static final long MAX_FLIGHT = 22;
    private static final double PICK_REACH = 0.45;
    private static final double REST_HEIGHT = 0.3;
    private static final float APPLE_SCALE = 0.75f;
    private static final long WARN_TICKS = 30;
    private static final double MAX_DROP = 24;

    // the fairest
    private static final double CROWN_ABOVE = 0.22;
    /**
     * Ticks a display following a player glides to each new spot: as long as a client lerps a moving player's body
     * (3 steps), so a follower moved every tick keeps pace with the body instead of trailing or jittering.
     */
    private static final int FOLLOW_LERP = 3;
    private static final float CROWN_SIZE = 0.42f;
    private static final float BAND = 0.07f;
    private static final float SPIKE = 0.12f;
    private static final float CROWN_SPIN = 4f;

    // ultimate: discord seeds
    private static final int MAX_SEEDS = 8;
    private static final double SEED_ABOVE = 0.45;
    private static final float SEED_SCALE = 0.4f;
    private static final double CHAIN_SAG = 0.3;
    private static final int CHAIN_POINT_BUDGET = 40;

    /** When each entity was last hurt by somebody other than the eris (ticks), by entity id. */
    private final Map<UUID, Long> hurtByOthers = new HashMap<>();
    private final Map<UUID, Long> procFx = new HashMap<>();
    /** Enemies the burst threw into discord, until when. */
    private final Map<UUID, Long> discordUntil = new HashMap<>();
    private final Map<UUID, Fairest> fairest = new HashMap<>();
    private final List<Display> live = new ArrayList<>();
    private @Nullable Hooks hooks;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        Hooks listener = new Hooks();
        Bukkit.getPluginManager().registerEvents(listener, ctx().plugin());
        hooks = listener;
    }

    @Override
    public void onRevoke() {
        Hooks listener = hooks;
        hooks = null;
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
        hurtByOthers.clear();
        procFx.clear();
        discordUntil.clear();
        fairest.clear();
        live.clear();
    }

    /**
     * The eris's eyes on every fight: who got hurt by someone else (the opportunist) and the fairest's bad luck (every
     * enemy of the fairest hits it harder). Registered for the owner's whole match.
     */
    private final class Hooks implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onHurt(EntityDamageByEntityEvent event) {
            if (!(event.getEntity() instanceof LivingEntity victim) || !(event.getFinalDamage() > 0)) {
                return;
            }
            Player attacker = ctx().resolveAttacker(event.getDamager());
            if (attacker != null && !attacker.equals(player())) {
                hurtByOthers.put(victim.getUniqueId(), ctx().currentTick());
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onFairestHit(EntityDamageByEntityEvent event) {
            if (fairest.isEmpty() || !(event.getEntity() instanceof LivingEntity victim)) {
                return;
            }
            Fairest crowned = fairest.get(victim.getUniqueId());
            if (crowned == null || crowned.until <= ctx().currentTick()) {
                return;
            }
            Player attacker = ctx().resolveAttacker(event.getDamager());
            if (attacker == null || attacker.equals(victim) || !ctx().isEnemy(attacker, victim)) {
                return;
            }
            double bonus = Math.max(0, params().num("fairest_bonus")) / 100.0;
            if (bonus > 0) {
                event.setDamage(event.getDamage() * (1 + bonus));
            }
        }
    }

    // ================================================================== passive: the opportunist

    private boolean isOpportunity(LivingEntity victim, long now) {
        Long until = discordUntil.get(victim.getUniqueId());
        if (until != null && until > now) {
            return true;
        }
        Long hurt = hurtByOthers.get(victim.getUniqueId());
        return hurt != null && now - hurt <= params().ticks("opportunist_window");
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOpportunity(victim, ctx().currentTick())) {
            return;
        }
        double bonus = Math.max(0, params().num("opportunist_bonus")) / 100.0;
        if (bonus > 0) {
            event.setDamage(event.getDamage() * (1 + bonus));
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        long now = ctx().currentTick();
        if (!isOpportunity(victim, now) || isHidden(player(), victim)) {
            return;
        }
        Long last = procFx.get(victim.getUniqueId());
        if (last != null && now - last < PROC_FX_PERIOD) {
            return;
        }
        procFx.put(victim.getUniqueId(), now);
        // 어부지리: a little shower of gold where the eris cashes in on somebody else's fight.
        Location body = Fx.body(victim);
        Vfx.cloud(ctx(), body, Particle.WAX_ON, 6, 0.3, 0.05, null, false);
        Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 8, 0.3, 0, GLOW, false);
        Vfx.ring(ctx(), head(victim), 0.35, 8, now * 0.4, Particle.DUST, GOLD_SMALL, false);
        Fx.sound(body, Sound.ITEM_ARMOR_EQUIP_GOLD, 0.7f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(body, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.35f, 1.7f + Fx.jitter(0.1f));
        player().sendActionBar(Text.mm("<" + GOLD + "><b>어부지리</b></" + GOLD + "> <gray>+"
                + AbilityInfo.formatNumber(params().num("opportunist_bonus")) + "%</gray>"));
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        if (tick % MARK_PERIOD == 0) {
            markOpportunities(owner, now);
        }
        tickFairest(owner, tick, now);
        if (tick % 5 == 0) {
            tickDiscord(owner, now);
        }
        if (tick % 100 == 0) {
            long window = params().ticks("opportunist_window");
            hurtByOthers.values().removeIf(at -> now - at > window);
            procFx.values().removeIf(at -> now - at > PROC_FX_PERIOD);
            discordUntil.values().removeIf(until -> until <= now);
        }
    }

    /** Crimson embers only the eris sees over the heads it would profit from. */
    private void markOpportunities(Player owner, long now) {
        if (hurtByOthers.isEmpty() && discordUntil.isEmpty()) {
            return;
        }
        Location at = owner.getLocation();
        int count = Vfx.n(ctx(), 2);
        for (LivingEntity enemy : ctx().enemiesNear(owner, at, MARK_RANGE)) {
            if (!isOpportunity(enemy, now) || isHidden(owner, enemy)) {
                continue;
            }
            Location head = head(enemy).add(0, 0.2, 0);
            owner.spawnParticle(Particle.DUST_COLOR_TRANSITION, head.getX(), head.getY(), head.getZ(), count, 0.12,
                    0.08, 0.12, 0, EMBER);
            if ((now / MARK_PERIOD) % 3 == 0) {
                owner.spawnParticle(Particle.WAX_ON, head.getX(), head.getY() + 0.1, head.getZ(), 1, 0.05, 0.05, 0.05,
                        0);
            }
        }
    }

    /** Wisps of discord at the feet of everyone the burst caught. */
    private void tickDiscord(Player owner, long now) {
        for (Map.Entry<UUID, Long> entry : discordUntil.entrySet()) {
            if (entry.getValue() <= now) {
                continue;
            }
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity enemy) || !Combat.isTargetable(ctx(), owner, enemy)
                    || isHidden(owner, enemy) || !enemy.getWorld().equals(owner.getWorld())) {
                continue;
            }
            Location feet = enemy.getLocation().add(0, 0.15, 0);
            Vfx.ring(ctx(), feet, 0.45, 7, now * 0.3, Particle.DUST, CRIMSON_SMALL, false);
            Vfx.dot(feet.clone().add(0, 0.4, 0), Particle.DUST, PURPLE_DUST, false);
        }
    }

    // ================================================================== active: the apple of discord

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("apple_range"));
        Location landing = landing(owner, range);
        if (landing == null) {
            return ActivationResult.fail("<gray>사과를 던질 곳이 없습니다</gray>");
        }
        new Apple(owner, landing).launch();
        return ActivationResult.success();
    }

    /**
     * Where the apple comes down: at the feet of the aimed enemy, else where the aim meets the ground (or a wall) within
     * {@code range}, else straight under the end of the throw. Null if that is outside the arena or above nothing.
     */
    private @Nullable Location landing(Player owner, double range) {
        World world = owner.getWorld();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (!(direction.lengthSquared() > 1.0E-8)) {
            direction = Motion.horizontalFacing(eye);
        }
        direction.normalize();
        Location point;
        LivingEntity aimed = ctx().findTarget(owner, range);
        if (aimed != null) {
            point = aimed.getLocation().add(0, 0.3, 0);
        } else {
            RayTraceResult hit = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            point = hit == null ? eye.clone().add(direction.clone().multiply(range))
                    : hit.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.3));
        }
        Location ground = groundUnder(point);
        if (ground == null || !ctx().isInsideArena(ground) || !Spots.hasGroundBelow(world, ground.clone().add(0, 0.05,
                0))) {
            return null;
        }
        return ground;
    }

    /** The floor straight under {@code point} (within {@link #MAX_DROP}), the ray starting just above it. */
    private static @Nullable Location groundUnder(Location point) {
        World world = point.getWorld();
        Location from = point.clone().add(0, 0.05, 0);
        RayTraceResult ground = world.rayTraceBlocks(from, new Vector(0, -1, 0), MAX_DROP, FluidCollisionMode.ALWAYS,
                true);
        if (ground == null) {
            return null;
        }
        Location at = ground.getHitPosition().toLocation(world);
        at.setYaw(0);
        at.setPitch(0);
        return at;
    }

    /**
     * The golden apple: a high lob, a short moment to settle, then it lies glowing until somebody takes it or it bursts.
     */
    private final class Apple implements LongPredicate {

        private final Player owner;
        private final Location from;
        private final Location rest;
        private final long flight;
        private final long arm;
        private final long life;
        private final double arc;
        private @Nullable ItemDisplay body;
        private @Nullable TextDisplay inscription;
        private boolean landed;
        private boolean over;

        Apple(Player owner, Location landing) {
            this.owner = owner;
            this.from = hand(owner);
            this.rest = landing.clone().add(0, REST_HEIGHT, 0);
            double distance = from.distance(rest);
            this.flight = Math.clamp(Math.round(distance / APPLE_SPEED), MIN_FLIGHT, MAX_FLIGHT);
            // The apple can be taken a moment after it landed: room to step away from a throw at the feet.
            this.arm = flight + params().ticks("apple_arm");
            this.life = arm + Math.max(1, params().ticks("apple_duration"));
            this.arc = 1.0 + distance * 0.12;
        }

        void launch() {
            if (room(1)) {
                body = keep(Displays.item(ctx(), owner, from, new ItemStack(APPLE), applePose(0.2f, 0), life + 20));
                body.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, body, applePose(APPLE_SCALE, 40), 3);
            }
            Vfx.cloud(ctx(), from, Particle.WAX_ON, 5, 0.15, 0.05, null, false);
            Fx.sound(from, Sound.ENTITY_SPLASH_POTION_THROW, 0.8f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(from, Sound.ITEM_ARMOR_EQUIP_GOLD, 0.6f, 1.8f);
            Fx.sound(from, Sound.ENTITY_WITCH_CELEBRATE, 0.4f, 1.6f + Fx.jitter(0.1f));
            // Where it will land, so anyone can step aside (or walk up for a bite).
            Vfx.ring(ctx(), rest.clone().subtract(0, REST_HEIGHT - 0.05, 0), 0.5, 12, 0, Particle.DUST, GOLD_DUST,
                    true);
            Loop.repeat(ctx(), owner, 1, 1, life, this, this::burst);
        }

        @Override
        public boolean test(long run) {
            if (over) {
                return false;
            }
            long t = run + 1;
            if (t <= flight) {
                fly(t);
                return true;
            }
            if (!landed) {
                land();
            }
            float bob = (float) (0.06 * Math.sin(t * 0.25));
            boolean warning = life - t <= WARN_TICKS;
            ItemDisplay apple = body;
            if (apple != null && apple.isValid() && t % 2 == 0) {
                float shake = warning ? (float) (10 * Math.sin(t * 1.7)) : 0;
                Displays.animate(apple, applePose(APPLE_SCALE, t * 6f, bob, shake), 2);
            }
            glow(t, warning);
            if (t >= arm) {
                Player taker = taker();
                if (taker != null) {
                    take(taker);
                    return false;
                }
            }
            return true;
        }

        private void fly(long t) {
            double progress = (double) t / flight;
            Location at = from.clone().add(rest.toVector().subtract(from.toVector()).multiply(progress))
                    .add(0, arc * 4 * progress * (1 - progress), 0);
            ItemDisplay apple = body;
            if (apple != null && apple.isValid()) {
                Displays.moveTo(apple, at);
                if (t % 2 == 0) {
                    Displays.animate(apple, applePose(APPLE_SCALE, t * 40f), 2);
                }
            }
            Vfx.dot(at, Particle.DUST, GOLD_DUST, false);
            if (t % 2 == 0) {
                Vfx.dot(at, Particle.DUST, CRIMSON_SMALL, false);
                Vfx.dot(at, Particle.WAX_ON, null, false);
            }
        }

        private void land() {
            landed = true;
            ItemDisplay apple = body;
            if (apple != null && apple.isValid()) {
                Displays.moveTo(apple, rest);
                Displays.outline(apple, GOLD_COLOR);
            }
            Location ground = rest.clone().subtract(0, REST_HEIGHT - 0.05, 0);
            Vfx.burst(ctx(), rest, PALETTE, 0.5, Particle.WAX_ON, false);
            Vfx.shockwave(ctx(), owner, ground, 0.2, 1.4, 5, Particle.DUST, GOLD_DUST, false);
            Fx.sound(rest, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.4f + Fx.jitter(0.05f));
            Fx.sound(rest, Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.6f);
            Fx.sound(rest, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.2f);
            if (room(1)) {
                inscription = keep(Displays.text(ctx(), owner, rest.clone().add(0, 0.75, 0),
                        Text.mm("<i><" + GOLD + ">가장 아름다운 자에게</" + GOLD + "></i>"), 0.2f, life - flight + 5));
                Displays.animateNextTick(ctx(), owner, inscription, Displays.centered(0, 0, 0, 0.55f, 0.55f, 0.55f, 0, 0,
                        0), 6);
            }
        }

        /** Gold motes rising off the apple; in the last moments a crimson ring of the burst and a quickening tick. */
        private void glow(long t, boolean warning) {
            if (t % 3 == 0) {
                Vfx.dot(rest.clone().add(Math.cos(t * 0.6) * 0.3, 0.1 + (t % 12) * 0.04, Math.sin(t * 0.6) * 0.3),
                        Particle.DUST, GOLD_SMALL, false);
            }
            if (t % 8 == 0) {
                Vfx.dot(rest.clone().add(0, 0.25, 0), Particle.WAX_ON, null, false);
            }
            if (!warning) {
                if (t % 20 == 0) {
                    Fx.sound(rest, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 1.8f + Fx.jitter(0.05f));
                }
                return;
            }
            long left = life - t;
            double radius = Math.max(0.5, params().num("burst_radius"));
            if (t % 4 == 0) {
                Vfx.ring(ctx(), rest.clone().subtract(0, REST_HEIGHT - 0.1, 0), radius, (int) Math.max(14, radius * 7),
                        t * 0.2, Particle.DUST_COLOR_TRANSITION, FADE, true);
            }
            long beat = left > 15 ? 8 : 4;
            if (t % beat == 0) {
                Fx.sound(rest, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.2f + (WARN_TICKS - left) * 0.03f);
                Vfx.dot(rest, Particle.DUST, CRIMSON_DUST, false);
            }
        }

        /** The nearest player in play touching the apple, or null. Players hidden from the eris cannot take it. */
        private @Nullable Player taker() {
            Player nearest = null;
            double best = Double.MAX_VALUE;
            for (Player candidate : ctx().alivePlayers()) {
                if (!candidate.getWorld().equals(rest.getWorld()) || !candidate.isValid() || candidate.isDead()) {
                    continue;
                }
                if (!candidate.equals(owner) && ctx().isEnemy(owner, candidate) && isHidden(owner, candidate)) {
                    continue;
                }
                BoundingBox box = candidate.getBoundingBox().expand(PICK_REACH);
                if (!box.contains(rest.toVector())) {
                    continue;
                }
                double distance = candidate.getLocation().distanceSquared(rest);
                if (distance < best) {
                    best = distance;
                    nearest = candidate;
                }
            }
            return nearest;
        }

        /** Somebody took the apple: the fairest's crown for an enemy, a bite for a friend. */
        private void take(Player taker) {
            over = true;
            clearDisplays();
            Location at = Fx.body(taker);
            Vfx.cloud(ctx(), at, Particle.WAX_ON, 10, 0.35, 0.05, null, false);
            Vfx.cloud(ctx(), at, Particle.HEART, 2, 0.3, 0, null, false);
            Fx.sound(at, Sound.ENTITY_GENERIC_EAT, 1.0f, 1.0f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.ENTITY_PLAYER_BURP, 0.5f, 1.3f);
            if (ctx().isEnemy(owner, taker)) {
                ctx().heal(taker, Math.max(0, params().num("bait_heal")));
                crown(owner, taker);
                return;
            }
            ctx().heal(taker, Math.max(0, params().num("friend_heal")));
            Vfx.ring(ctx(), taker.getLocation().add(0, 0.1, 0), 0.6, 10, 0, Particle.DUST, GOLD_DUST, false);
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.6f);
            if (!taker.equals(owner)) {
                taker.sendActionBar(Text.mm("<" + GOLD + ">황금 사과</" + GOLD + "> <gray>+"
                        + AbilityInfo.formatNumber(params().num("friend_heal")) + " 회복</gray>"));
            }
            owner.sendActionBar(Text.mm("<" + GOLD + ">황금 사과</" + GOLD + "> <gray>"
                    + (taker.equals(owner) ? "한 입 베어 물었습니다" : "아군이 가져갔습니다") + "</gray>"));
        }

        /** Nobody wanted it: the apple bursts into discord. */
        private void burst() {
            if (over) {
                return;
            }
            over = true;
            clearDisplays();
            double radius = Math.max(0.5, params().num("burst_radius"));
            double damage = Math.max(0, params().num("burst_damage"));
            long discord = params().ticks("discord_duration");
            Location ground = rest.clone().subtract(0, REST_HEIGHT - 0.1, 0);
            Vfx.burst(ctx(), rest, PALETTE, 1.2, Particle.CRIMSON_SPORE, true);
            Vfx.shockwave(ctx(), owner, ground, 0.3, radius, 6, Particle.DUST_COLOR_TRANSITION, GLOW, true);
            World world = rest.getWorld();
            world.spawnParticle(Particle.ITEM, rest, Vfx.n(ctx(), 14), 0.2, 0.2, 0.2, 0.18, new ItemStack(APPLE));
            world.spawnParticle(Particle.DUST, rest, Vfx.n(ctx(), 16), radius * 0.4, 0.4, radius * 0.4, 0, PURPLE_DUST);
            Fx.sound(rest, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f + Fx.jitter(0.05f));
            Fx.sound(rest, Sound.BLOCK_GLASS_BREAK, 0.9f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(rest, Sound.ENTITY_WITCH_CELEBRATE, 0.9f, 0.8f);
            int caught = 0;
            long now = ctx().currentTick();
            for (LivingEntity enemy : ctx().enemiesNear(owner, rest, radius)) {
                if (isHidden(owner, enemy)) {
                    continue;
                }
                caught++;
                Combat.hit(ctx(), owner, enemy, damage);
                if (discord > 0) {
                    discordUntil.put(enemy.getUniqueId(), now + discord);
                }
                Vfx.trail(rest, Fx.body(enemy), CRIMSON_COLOR, 5, false);
            }
            owner.sendActionBar(Text.mm("<" + CRIMSON + "><b>불화!</b></" + CRIMSON + "> <gray>"
                    + (caught == 0 ? "아무도 휘말리지 않았습니다" : caught + "명이 불화에 빠졌습니다") + "</gray>"));
        }

        private void clearDisplays() {
            ItemDisplay apple = body;
            body = null;
            if (apple != null && apple.isValid()) {
                Displays.shrinkAway(ctx(), owner, apple, 3);
            }
            TextDisplay text = inscription;
            inscription = null;
            if (text != null && text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 3);
            }
        }
    }

    private static Transformation applePose(float scale, float yaw) {
        return applePose(scale, yaw, 0, 0);
    }

    private static Transformation applePose(float scale, float yaw, float bob, float roll) {
        return Displays.centered(0, bob, 0, scale, scale, scale, yaw, 0, roll);
    }

    // ================================================================== the fairest

    /** An enemy who took the apple: glowing, crowned, and everyone's target until {@link #until}. */
    private static final class Fairest {
        final LivingEntity enemy;
        long until;
        final List<BlockDisplay> crown = new ArrayList<>();
        @Nullable TextDisplay title;

        Fairest(LivingEntity enemy) {
            this.enemy = enemy;
        }
    }

    private void crown(Player owner, LivingEntity enemy) {
        long ticks = params().ticks("fairest_duration");
        if (ticks <= 0) {
            return;
        }
        long now = ctx().currentTick();
        Fairest crowned = fairest.computeIfAbsent(enemy.getUniqueId(), id -> new Fairest(enemy));
        crowned.until = now + ticks;
        enemy.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, (int) Math.min(Integer.MAX_VALUE, ticks), 0,
                false, false, true));
        if (crowned.crown.isEmpty()) {
            buildCrown(owner, crowned, ticks);
        }
        Location head = head(enemy);
        Vfx.dot(head, Particle.FLASH, GOLD_COLOR, true);
        Vfx.helix(ctx(), enemy.getLocation(), 0.55, enemy.getHeight() + 0.3, 1.5, 2, 12, 0, Particle.DUST, GOLD_DUST,
                true);
        Vfx.cloud(ctx(), head, Particle.WAX_ON, 12, 0.35, 0.1, null, true);
        Fx.sound(head, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.5f);
        Fx.sound(head, Sound.BLOCK_BELL_USE, 0.8f, 1.7f);
        if (enemy instanceof Player crownedPlayer) {
            crownedPlayer.sendActionBar(Text.mm("<" + GOLD + "><b>가장 아름다운 자</b></" + GOLD + "> <gray>"
                    + Fx.seconds(ticks) + "초간 모두의 표적이 됩니다</gray>"));
        }
        owner.sendActionBar(Text.mm("<" + GOLD + "><b>왕관!</b></" + GOLD + "> <white><name></white><gray>이(가) 사과를 "
                + "가져갔습니다</gray>", Placeholder.component("name", enemy.name())));
    }

    /**
     * A gold band of four bars with four spikes and a ruby in front, turning slowly over the fairest's head. It follows
     * the head every tick and glides as long as a client lerps a moving player ({@link #FOLLOW_LERP}), so it stays on
     * a running head instead of trailing behind it.
     */
    private void buildCrown(Player owner, Fairest crowned, long ticks) {
        Location at = crownSpot(crowned.enemy);
        if (room(10)) {
            for (int i = 0; i < 9; i++) {
                Material material = i == 8 ? RUBY : CROWN_GOLD;
                BlockDisplay part = keep(Displays.block(ctx(), owner, at, material.createBlockData(),
                        crownPart(i, 0, 0.05f), ticks + 10));
                part.setTeleportDuration(FOLLOW_LERP);
                crowned.crown.add(part);
            }
            for (int i = 0; i < crowned.crown.size(); i++) {
                Displays.animateNextTick(ctx(), owner, crowned.crown.get(i), crownPart(i, 0, 1), 5);
            }
            TextDisplay title = keep(Displays.text(ctx(), owner, titleSpot(crowned.enemy),
                    Text.mm("<b><" + GOLD + ">가장 아름다운 자</" + GOLD + "></b>"), 0.55f, ticks + 10));
            title.setTeleportDuration(FOLLOW_LERP);
            crowned.title = title;
        }
    }

    /**
     * Part {@code index} of the crown turned {@code yaw} degrees and scaled by {@code grow}: 0-3 the band's sides, 4-7
     * the spikes on its corners, 8 the ruby on the front side.
     */
    private static Transformation crownPart(int index, float yaw, float grow) {
        float half = CROWN_SIZE / 2;
        double angle = Math.toRadians(yaw);
        if (index < 4) {
            float side = index * 90 + yaw;
            double a = Math.toRadians(index * 90) + angle;
            float x = (float) (-Math.sin(a) * half);
            float z = (float) (Math.cos(a) * half);
            return Displays.transform(x, 0, z, CROWN_SIZE * grow, BAND * 1.6f * grow, BAND * grow, side, 0, 0);
        }
        if (index < 8) {
            double a = Math.toRadians((index - 4) * 90 + 45) + angle;
            float r = half * (float) Math.sqrt(2);
            float x = (float) (-Math.sin(a) * r);
            float z = (float) (Math.cos(a) * r);
            return Displays.transform(x, BAND * 1.6f, z, SPIKE * grow, SPIKE * grow, SPIKE * grow,
                    (index - 4) * 90 + 45 + yaw, 45, 35);
        }
        float x = (float) (-Math.sin(angle) * (half + BAND * 0.6f));
        float z = (float) (Math.cos(angle) * (half + BAND * 0.6f));
        return Displays.transform(x, 0.01f, z, 0.08f * grow, 0.08f * grow, 0.04f * grow, yaw, 0, 45);
    }

    private static Location crownSpot(LivingEntity enemy) {
        return enemy.getLocation().add(0, enemy.getHeight() + CROWN_ABOVE, 0);
    }

    private static Location titleSpot(LivingEntity enemy) {
        return crownSpot(enemy).add(0, 0.45, 0);
    }

    private void tickFairest(Player owner, long tick, long now) {
        if (fairest.isEmpty()) {
            return;
        }
        for (Iterator<Fairest> it = fairest.values().iterator(); it.hasNext(); ) {
            Fairest crowned = it.next();
            LivingEntity enemy = crowned.enemy;
            if (crowned.until <= now || !Combat.isTargetable(ctx(), owner, enemy)
                    || !enemy.getWorld().equals(owner.getWorld())) {
                uncrown(owner, crowned, crowned.until <= now && enemy.isValid());
                it.remove();
                continue;
            }
            // Every tick it follows the head; every other tick the crown turns a little further.
            boolean turn = tick % 2 == 0;
            boolean hidden = isHidden(owner, enemy);
            Location at = crownSpot(enemy);
            float yaw = (tick * CROWN_SPIN) % 360f;
            for (int i = 0; i < crowned.crown.size(); i++) {
                BlockDisplay part = crowned.crown.get(i);
                if (part.isValid()) {
                    part.setViewRange(hidden ? 0f : 1.5f);
                    Displays.moveTo(part, at);
                    if (turn) {
                        Displays.animate(part, crownPart(i, yaw, 1), 2);
                    }
                }
            }
            TextDisplay title = crowned.title;
            if (title != null && title.isValid()) {
                title.setViewRange(hidden ? 0f : 1.5f);
                Displays.moveTo(title, titleSpot(enemy));
            }
            if (!turn) {
                continue;
            }
            if (!hidden && tick % 6 == 0) {
                Vfx.dot(at.clone().add(Math.cos(tick * 0.5) * 0.35, 0.1, Math.sin(tick * 0.5) * 0.35), Particle.WAX_ON,
                        null, false);
            }
        }
    }

    private void uncrown(Player owner, Fairest crowned, boolean fx) {
        for (BlockDisplay part : crowned.crown) {
            if (part.isValid()) {
                Displays.shrinkAway(ctx(), owner, part, 4);
            }
        }
        crowned.crown.clear();
        TextDisplay title = crowned.title;
        crowned.title = null;
        if (title != null && title.isValid()) {
            Displays.shrinkAway(ctx(), owner, title, 4);
        }
        if (fx && !isHidden(owner, crowned.enemy)) {
            Location at = crownSpot(crowned.enemy);
            Vfx.cloud(ctx(), at, Particle.DUST, 6, 0.25, 0, GOLD_SMALL, false);
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 0.8f);
        }
    }

    // ================================================================== ultimate: seeds of discord

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = Math.max(1, params().num("ult_radius"));
        List<LivingEntity> infected = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), radius)) {
            if (!isHidden(owner, enemy)) {
                infected.add(enemy);
            }
        }
        if (infected.isEmpty()) {
            return ActivationResult.fail("<gray>반경 " + AbilityInfo.formatNumber(radius)
                    + "블록 안에 불화를 심을 적이 없습니다</gray>");
        }
        new Discord(owner, infected).start();
        return ActivationResult.success();
    }

    /** One infected enemy: its seed over the head and its chains this pulse. */
    private static final class Infected {
        final LivingEntity enemy;
        @Nullable ItemDisplay seed;
        int chains;

        Infected(LivingEntity enemy) {
            this.enemy = enemy;
        }
    }

    /** Two infected enemies close enough to be chained. */
    private record Link(Infected a, Infected b, double distance) {
    }

    /**
     * The seeds of discord: every infected pair close enough is chained, hurt and pushed apart each pulse; in a free
     * for all, infected enemies also hit each other harder (a listener for the ultimate's time only, released with its
     * seeds, so a gambler that borrows it cleans up too).
     */
    private final class Discord implements LongPredicate, Listener {

        private final Player owner;
        private final List<Infected> infected = new ArrayList<>();
        private final Map<UUID, Infected> byId = new HashMap<>();
        private final long ticks;
        private final long period;
        private List<Link> links = List.of();
        /** Always there (the seeds may be capped): its removal means the owner's ability was released. */
        private @Nullable TextDisplay anchor;
        private boolean registered;
        private boolean released;

        Discord(Player owner, List<LivingEntity> enemies) {
            this.owner = owner;
            for (LivingEntity enemy : enemies) {
                Infected entry = new Infected(enemy);
                infected.add(entry);
                byId.put(enemy.getUniqueId(), entry);
            }
            this.ticks = Math.max(1, params().ticks("ult_duration"));
            this.period = Math.max(1, params().ticks("chain_period"));
        }

        void start() {
            Location eye = owner.getEyeLocation();
            Vfx.dot(eye, Particle.FLASH, CRIMSON_COLOR, true);
            Vfx.cloud(ctx(), eye, Particle.WAX_ON, 20, 0.6, 0.2, null, true);
            Fx.sound(eye, Sound.ENTITY_WITCH_CELEBRATE, 1.2f, 0.7f);
            Fx.sound(eye, Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.6f);
            Fx.sound(eye, Sound.ENTITY_EVOKER_PREPARE_WOLOLO, 0.7f, 1.4f);
            int seeded = 0;
            for (Infected entry : infected) {
                LivingEntity enemy = entry.enemy;
                Location head = head(enemy);
                for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                    Vfx.trail(eye, head, i % 2 == 0 ? GOLD_COLOR : CRIMSON_COLOR, 8 + i * 2, true);
                }
                if (seeded < MAX_SEEDS && room(1)) {
                    seeded++;
                    ItemDisplay seed = keep(Displays.item(ctx(), owner, seedSpot(enemy), new ItemStack(SEED),
                            Displays.centered(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), ticks + 15));
                    seed.setTeleportDuration(FOLLOW_LERP);
                    Displays.animateNextTick(ctx(), owner, seed, seedPose(0), 6);
                    entry.seed = seed;
                }
                if (enemy instanceof Player victim) {
                    victim.sendActionBar(Text.mm("<" + CRIMSON + "><b>불화의 씨앗</b></" + CRIMSON + "> <gray>가까이 있는 "
                            + "적과 사슬로 묶여 함께 다칩니다</gray>"));
                }
            }
            anchor = Displays.text(ctx(), owner, owner.getLocation(), Text.mm(""), 0.01f, ticks + 20);
            anchor.setViewRange(0f);
            Bukkit.getPluginManager().registerEvents(this, ctx().plugin());
            registered = true;
            owner.sendActionBar(Text.mm("<" + CRIMSON + "><b>불화의 씨앗</b></" + CRIMSON + "> <white>" + infected.size()
                    + "</white><gray>명에게 심었습니다</gray>"));
            Loop.repeat(ctx(), owner, 4, 1, ticks, this, this::end);
        }

        @Override
        public boolean test(long run) {
            if (released) {
                return false;
            }
            infected.removeIf(entry -> {
                boolean gone = !Combat.isTargetable(ctx(), owner, entry.enemy)
                        || !entry.enemy.getWorld().equals(owner.getWorld());
                if (gone) {
                    ItemDisplay seed = entry.seed;
                    entry.seed = null;
                    remove(seed);
                    byId.remove(entry.enemy.getUniqueId());
                }
                return gone;
            });
            if (infected.isEmpty()) {
                return false;
            }
            if (run % period == 0) {
                links = link();
                pulse();
            }
            if (run % 2 == 0) {
                drawChains(run);
            }
            moveSeeds(run);
            return true;
        }

        /**
         * The chains of this pulse: the closest pairs first, each enemy in at most {@code max_chains} of them, hidden
         * enemies never (a chain would point at them).
         */
        private List<Link> link() {
            double range = Math.max(0, params().num("chain_range"));
            int max = Math.max(0, params().integer("max_chains"));
            List<Link> candidates = new ArrayList<>();
            for (int i = 0; i < infected.size(); i++) {
                Infected a = infected.get(i);
                a.chains = 0;
                if (isHidden(owner, a.enemy)) {
                    continue;
                }
                for (int j = i + 1; j < infected.size(); j++) {
                    Infected b = infected.get(j);
                    if (isHidden(owner, b.enemy)) {
                        continue;
                    }
                    double distance = a.enemy.getLocation().distance(b.enemy.getLocation());
                    if (distance <= range) {
                        candidates.add(new Link(a, b, distance));
                    }
                }
            }
            candidates.sort(Comparator.comparingDouble(Link::distance));
            List<Link> chosen = new ArrayList<>();
            for (Link link : candidates) {
                if (link.a().chains < max && link.b().chains < max) {
                    link.a().chains++;
                    link.b().chains++;
                    chosen.add(link);
                }
            }
            return chosen;
        }

        /** Every chain jerks: both ends are hurt and shoved away from each other. */
        private void pulse() {
            if (links.isEmpty()) {
                return;
            }
            double damage = Math.max(0, params().num("chain_damage"));
            double push = Math.max(0, params().num("chain_push"));
            for (Link link : links) {
                LivingEntity a = link.a().enemy;
                LivingEntity b = link.b().enemy;
                Location chestA = chest(a);
                Location chestB = chest(b);
                Vector apart = Motion.horizontal(chestA.toVector().subtract(chestB.toVector()));
                if (apart.lengthSquared() < 1.0E-6) {
                    apart = new Vector(1, 0, 0);
                }
                Combat.hit(ctx(), owner, a, damage);
                Combat.hit(ctx(), owner, b, damage);
                if (push > 0) {
                    shove(a, apart, push);
                    shove(b, apart.clone().multiply(-1), push);
                }
                Location middle = chestA.clone().add(chestB).multiply(0.5);
                Vfx.trail(chestA, middle, CRIMSON_COLOR, 4, false);
                Vfx.trail(chestB, middle, CRIMSON_COLOR, 4, false);
                Vfx.dot(middle, Particle.FLASH, CRIMSON_COLOR, false);
                Vfx.cloud(ctx(), chestA, Particle.DUST, 3, 0.15, 0, CRIMSON_DUST, false);
                Vfx.cloud(ctx(), chestB, Particle.DUST, 3, 0.15, 0, CRIMSON_DUST, false);
                Fx.sound(middle, Sound.BLOCK_CHAIN_HIT, 0.9f, 0.7f + Fx.jitter(0.15f));
                Fx.sound(middle, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 0.5f, 1.3f + Fx.jitter(0.1f));
            }
        }

        private void shove(LivingEntity target, Vector away, double speed) {
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return;
            }
            Motion.push(target, away.clone().multiply(speed).setY(0.18), MAX_PUSH);
        }

        /** Sagging crimson chains between the linked enemies, links alternating crimson and purple. */
        private void drawChains(long run) {
            if (links.isEmpty()) {
                return;
            }
            int perChain = Math.max(5, CHAIN_POINT_BUDGET / links.size());
            for (Link link : links) {
                Location a = chest(link.a().enemy);
                Location b = chest(link.b().enemy);
                if (!a.getWorld().equals(b.getWorld())) {
                    continue;
                }
                int points = Math.max(4, Vfx.n(ctx(), Math.min(perChain, (int) Math.ceil(link.distance() * 4) + 2)));
                Vector delta = b.toVector().subtract(a.toVector());
                for (int i = 0; i <= points; i++) {
                    double t = (double) i / points;
                    double sag = CHAIN_SAG * Math.sin(Math.PI * t) * (1 + 0.15 * Math.sin(run * 0.6));
                    Location at = a.clone().add(delta.clone().multiply(t)).subtract(0, sag, 0);
                    Vfx.dot(at, Particle.DUST, (i + run / 2) % 3 == 0 ? PURPLE_DUST : CRIMSON_DUST, false);
                }
            }
        }

        private void moveSeeds(long run) {
            for (Infected entry : infected) {
                ItemDisplay seed = entry.seed;
                if (seed == null || !seed.isValid()) {
                    continue;
                }
                boolean hidden = isHidden(owner, entry.enemy);
                seed.setViewRange(hidden ? 0f : 1.5f);
                Displays.moveTo(seed, seedSpot(entry.enemy));
                if (run % 2 == 0) {
                    Displays.animate(seed, seedPose(run * 9f), 2);
                }
                if (!hidden && run % 6 == 0) {
                    Vfx.dot(seedSpot(entry.enemy).add(0, -0.15, 0), Particle.DUST, entry.chains > 0 ? CRIMSON_SMALL
                            : PURPLE_DUST, false);
                }
            }
        }

        /** In a free for all, two infected enemies hit each other harder. */
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onInfectedHit(EntityDamageByEntityEvent event) {
            if (released || !byId.containsKey(event.getEntity().getUniqueId())
                    || !(event.getEntity() instanceof LivingEntity victim)) {
                return;
            }
            Player attacker = ctx().resolveAttacker(event.getDamager());
            if (attacker == null || attacker.equals(owner) || !byId.containsKey(attacker.getUniqueId())
                    || !ctx().isEnemy(attacker, victim)) {
                return;
            }
            double bonus = Math.max(0, params().num("ffa_bonus")) / 100.0;
            if (bonus > 0) {
                event.setDamage(event.getDamage() * (1 + bonus));
            }
        }

        /** The seeds go when the owner's ability is released (death, quitting, the match ending): so does this. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onRemove(EntityRemoveEvent event) {
            if (released) {
                return;
            }
            Entity removed = event.getEntity();
            if (removed.equals(anchor)) {
                release();
                return;
            }
            for (Infected entry : infected) {
                if (removed.equals(entry.seed)) {
                    release();
                    return;
                }
            }
        }

        void end() {
            release();
            remove(anchor);
            anchor = null;
            for (Infected entry : infected) {
                ItemDisplay seed = entry.seed;
                entry.seed = null;
                if (seed != null && seed.isValid()) {
                    if (!isHidden(owner, entry.enemy)) {
                        Vfx.cloud(ctx(), seedSpot(entry.enemy), Particle.DUST, 5, 0.2, 0, PURPLE_DUST, false);
                    }
                    Displays.shrinkAway(ctx(), owner, seed, 4);
                }
            }
            if (ctx().isAlive(owner)) {
                Fx.sound(owner.getEyeLocation(), Sound.BLOCK_CHAIN_BREAK, 0.8f, 0.8f);
            }
        }

        private void release() {
            if (released) {
                return;
            }
            released = true;
            if (registered) {
                HandlerList.unregisterAll(this);
                registered = false;
            }
        }
    }

    private static Transformation seedPose(float yaw) {
        return Displays.centered(0, 0, 0, SEED_SCALE, SEED_SCALE, SEED_SCALE, yaw, 0, 0);
    }

    private static Location seedSpot(LivingEntity enemy) {
        return enemy.getLocation().add(0, enemy.getHeight() + SEED_ABOVE, 0);
    }

    // ================================================================== helpers

    private static Location chest(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() * 0.6, 0);
    }

    private static Location head(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() + HEAD_GAP, 0);
    }

    /** Just in front of the right hand, where the apple leaves. */
    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector facing = Motion.horizontalFacing(eye);
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());
        return Displays.unrotated(eye.add(facing.multiply(0.5)).add(right.multiply(0.3)).subtract(0, 0.35, 0));
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
