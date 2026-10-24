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
import java.util.logging.Level;

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
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.damage.DamageType;
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
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 마법소녀 — the transforming magical girl of the anime: a heart wand, ribbons, and the power of friendship. Allies her
 * hearts heal stay tied to her with a pink ribbon that takes part of their pain onto her (and softens it); her wand's
 * heart bounces from enemy to ally to enemy, hurting and healing in turn; and her ultimate is the transformation scene
 * itself — invulnerable while the ribbons wrap her (attacking a girl mid-transformation is against the rules and is
 * punished), then a few seconds as a full magical girl with a double jump, a slow fall and a faster, longer heart chain,
 * ending in the finale: a giant heart falling from the sky, bigger with every friend nearby.
 *
 * <p>Look (pink, pastel sky, gold glitter): pink-and-white ribbons wave between her and the tied allies, with a little
 * bow on each ally's back. The heart is a glowing pink heart sprite trailing glitter that springs from target to target.
 * The transformation is a pillar of light with three pastel ribbons spiralling up, stars orbiting and rising around her
 * and a chime arpeggio; as a magical girl she wears a big pink bow on her back and leaves glitter wherever she goes.
 * The finale's heart grows over the target spot inside a pink warning ring and lands in a burst of hearts and
 * fireworks.
 */
public final class MagicalGirlAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("magicalgirl", "마법소녀", Material.END_ROD)
            .color(TextColor.color(0xFF79B9))
            .difficulty(2)
            .passive("우정의 리본",
                    "하트로 치유한 아군은 {ribbon_duration}초간 리본으로 나와 이어져, 그 아군이 적에게 받는 피해의 "
                            + "{ribbon_share}%를 내가 {ribbon_cushion}% 줄여 대신 받습니다. {ribbon_range}블록 넘게 멀어지면 "
                            + "리본이 끊어집니다.")
            .active("하트 스틱",
                    "하트를 날립니다({heart_range}블록). 처음 맞은 적에게 {heart_damage} 피해를 주고 튕겨 나가 근처에서 "
                            + "가장 다친 아군(없으면 나)을 {heart_heal} 회복시키고, 다시 다른 적에게 {bounce_damage} 피해를 "
                            + "주는 식으로 적과 아군을 번갈아 최대 {bounces}번 맞힙니다.",
                    9)
            .ultimate("변신!",
                    "{transform_time}초간 변신하는 동안 피해와 넉백을 받지 않지만 움직일 수 없고, 그때 나를 공격한 적은 "
                            + "{star_damage} 피해를 입고 {star_root}초간 묶입니다. 이어서 {girl_duration}초간 이단 점프와 "
                            + "느린 낙하(낙하 피해 없음)를 얻고, 하트 스틱은 쿨타임이 {girl_cooldown}초로 줄고 최대 "
                            + "{girl_bounces}번 튕깁니다. 끝날 때(F를 다시 누르면 일찍) 조준한 곳에 거대한 하트가 떨어져 반경 {finale_radius}블록의 적에게 "
                            + "{finale_damage} 피해(주변 아군마다 +{finale_ally_bonus})를 주고 나와 아군을 {finale_heal} "
                            + "회복시킵니다.")
            .ultimateNotStandalone()
            .param("ribbon_duration", 6)
            .param("ribbon_share", 25)
            .param("ribbon_cushion", 50)
            .param("ribbon_range", 16)
            .param("heart_range", 20)
            .param("heart_damage", 4)
            .param("bounce_range", 10)
            .param("heart_heal", 3)
            .param("bounce_damage", 3)
            .param("bounces", 4)
            .param("transform_time", 1.5)
            .param("star_damage", 1)
            .param("star_root", 1)
            .param("girl_duration", 8)
            .param("double_jump", 1.3)
            .param("girl_cooldown", 3)
            .param("girl_bounces", 6)
            .param("finale_range", 24)
            .param("finale_delay", 1)
            .param("finale_radius", 4)
            .param("finale_damage", 5)
            .param("finale_ally_range", 10)
            .param("finale_ally_bonus", 1)
            .param("finale_ally_max", 3)
            .param("finale_heal", 4)
            .build();

    private static final String PINK_TAG = "#FF79B9";
    private static final String SKY_TAG = "#9ED8FF";
    private static final String GOLD_TAG = "#FFE27A";

    // ------------------------------------------------------------------ look
    /** Gold glitter, pink, pastel sky. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFE27A, 0xFF79B9, 0x9ED8FF);
    private static final Color PINK = Color.fromRGB(0xFF79B9);
    private static final Color SKY = Color.fromRGB(0x9ED8FF);
    private static final Color GOLD = Color.fromRGB(0xFFE27A);
    private static final Color WHITE = Color.fromRGB(0xFFFFFF);
    private static final Particle.DustOptions PINK_DUST = new Particle.DustOptions(PINK, 0.9f);
    private static final Particle.DustOptions SMALL_PINK = new Particle.DustOptions(PINK, 0.6f);
    private static final Particle.DustOptions WHITE_DUST = new Particle.DustOptions(WHITE, 0.7f);
    private static final Particle.DustOptions SKY_DUST = new Particle.DustOptions(SKY, 0.9f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 0.7f);
    private static final Particle.DustTransition PINK_GLOW = new Particle.DustTransition(PINK, SKY, 1.0f);
    private static final Particle.DustTransition GLITTER = new Particle.DustTransition(GOLD, PINK, 0.7f);

    /** Block types; block data is created on use (it needs a running server). */
    private static final Material BOW = Material.PINK_CONCRETE;
    private static final Material BOW_KNOT = Material.MAGENTA_CONCRETE;

    private static final int MAX_DISPLAYS = 24;
    /**
     * Ticks a display following a player glides to each new spot: as long as a client lerps a moving player's body
     * (3 steps), so a follower moved every tick keeps pace with the body instead of trailing or jittering.
     */
    private static final int FOLLOW_LERP = 3;
    private static final double TAU = Math.PI * 2;
    private static final double MAX_SPEED = 2.0;
    private static final String HEART = "❤";

    // passive: ribbons
    private static final long RIBBON_PERIOD = 2;
    private static final int MAX_BOWS = 3;
    private static final long TWINKLE_PERIOD = 16;

    // active: the heart
    private static final double HEART_SPEED = 1.4;
    private static final double HOP_SPEED = 1.1;
    private static final long HOP_MAX_TICKS = 30;
    private static final double HEART_HITBOX = 0.4;
    private static final float HEART_SCALE = 1.7f;

    // ultimate
    private static final float BOW_SIZE = 0.32f;
    private static final double PIN_SLACK = 0.25;
    private static final long FINALE_LIFT = 14;
    private static final long SLOW_FALL_TICKS = 30;
    private static final double JUMP_PUSH = 0.25;
    private static final long LANDING_TICKS = 100;
    private static final int TRANSFORM_STARS = 6;

    /** Ribbon damage moving to the girl must not move along another ribbon again. */
    private static boolean sharing;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final Map<UUID, Ribbon> ribbons = new HashMap<>();
    private final LandingGuard landing = new LandingGuard();
    private @Nullable GirlListener listener;
    private boolean jumpHeld;
    private boolean doubleJumped;
    private long transformUntil = Long.MIN_VALUE / 2;
    private long girlUntil = Long.MIN_VALUE / 2;
    private @Nullable Location pin;
    private final Set<UUID> starred = new HashSet<>();
    private final List<BlockDisplay> outfit = new ArrayList<>();
    private boolean finaleDone = true;
    private boolean finaleRequested;
    private final List<LivingEntity> pendingStars = new ArrayList<>();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        GirlListener created = new GirlListener();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        listener = created;
    }

    @Override
    public void onRevoke() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        Player owner = player();
        if (owner.isOnline() && inGirlMode()) {
            removeOwnSlowFalling(owner);
        }
        ribbons.clear();
        starred.clear();
        outfit.clear();
        live.clear();
        landing.disarm();
        pin = null;
        transformUntil = Long.MIN_VALUE / 2;
        girlUntil = Long.MIN_VALUE / 2;
        finaleDone = true;
        finaleRequested = false;
        pendingStars.clear();
    }

    /** The ribbons' shared pain, the double jump key, and nobody pushes a girl who is transforming. */
    private final class GirlListener implements Listener {

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onAllyHurt(EntityDamageEvent event) {
            if (sharing || !(event instanceof EntityDamageByEntityEvent byEntity)) {
                return;
            }
            Ribbon ribbon = ribbons.get(event.getEntity().getUniqueId());
            if (ribbon == null || ctx().currentTick() >= ribbon.until) {
                return;
            }
            shareThePain(ribbon, byEntity);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onInput(PlayerInputEvent event) {
            if (!event.getPlayer().equals(player())) {
                return;
            }
            try {
                Input keys = event.getInput();
                boolean jump = keys.isJump();
                if (jump && !jumpHeld) {
                    doubleJump(keys);
                }
                jumpHeld = jump;
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Magical girl double jump failed", e);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onKnockback(EntityKnockbackEvent event) {
            if (event.getEntity().equals(player()) && transforming()) {
                event.setCancelled(true);
            }
        }
    }

    // ================================================================== passive: ribbons of friendship

    /** An ally tied to the girl: until when, and its little bow. */
    private final class Ribbon {
        final Player ally;
        long until;
        final List<BlockDisplay> bow = new ArrayList<>();
        /** Viewers the bow is hidden from: they cannot see the ally (stealth), so the bow must not show it. */
        final Set<UUID> bowHiddenFrom = new HashSet<>();

        Ribbon(Player ally, long until) {
            this.ally = ally;
            this.until = until;
        }

        void untie(boolean snapped) {
            for (BlockDisplay part : bow) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), player(), part, 4);
                }
            }
            bow.clear();
            bowHiddenFrom.clear();
            if (snapped && ally.isValid() && !concealed(ally)) {
                Location back = Fx.body(ally);
                Vfx.cloud(ctx(), back, Particle.DUST, 5, 0.25, 0, PINK_DUST, false);
                Fx.sound(back, Sound.BLOCK_WOOL_BREAK, 0.6f, 1.5f);
            }
        }
    }

    /** Ties (or re-ties) {@code ally} to the girl. */
    private void tie(Player owner, Player ally) {
        long until = ctx().currentTick() + Math.max(1, params().ticks("ribbon_duration"));
        Ribbon ribbon = ribbons.get(ally.getUniqueId());
        if (ribbon != null) {
            ribbon.until = until;
            return;
        }
        ribbon = new Ribbon(ally, until);
        ribbons.put(ally.getUniqueId(), ribbon);
        int bows = 0;
        for (Ribbon other : ribbons.values()) {
            if (!other.bow.isEmpty()) {
                bows++;
            }
        }
        if (bows < MAX_BOWS && reserve(3)) {
            Location feet = Displays.unrotated(ally.getLocation());
            for (int i = 0; i < 3; i++) {
                BlockDisplay part = keep(Displays.block(ctx(), owner, feet, (i == 2 ? BOW_KNOT : BOW).createBlockData(),
                        bowPose(ally, i, 0.05f), 0));
                part.setTeleportDuration(FOLLOW_LERP);
                Displays.animateNextTick(ctx(), owner, part, bowPose(ally, i, 1f), 4);
                ribbon.bow.add(part);
            }
            syncBow(ribbon);
        }
        if (!concealed(ally)) {
            Fx.sound(ally.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.6f);
        }
        ally.sendActionBar(Text.mm("<" + PINK_TAG + "><b>우정의 리본</b></" + PINK_TAG + "> <gray>받는 피해를 마법소녀가 "
                + "나눠 받습니다</gray>"));
    }

    /**
     * The tied ally takes less from an enemy's blow, and the girl takes a cushioned share of it, credited to the same
     * attacker.
     */
    private void shareThePain(Ribbon ribbon, EntityDamageByEntityEvent event) {
        Player owner = player();
        Player attacker = ctx().resolveAttacker(event.getDamager());
        if (attacker == null || !ctx().isEnemy(owner, attacker) || !ctx().isAlive(owner)) {
            return;
        }
        double share = Math.clamp(params().num("ribbon_share") / 100.0, 0.0, 1.0);
        double cushion = Math.clamp(params().num("ribbon_cushion") / 100.0, 0.0, 1.0);
        double total = event.getFinalDamage();
        if (!(total > 0) || share <= 0) {
            return;
        }
        event.setDamage(event.getDamage() * (1 - share));
        double mine = total * share * (1 - cushion);
        Location from = Fx.body(ribbon.ally);
        Location to = Fx.body(owner);
        // A stealthed ally (or girl) is not given away by the shared pain.
        if (!concealed(ribbon.ally)) {
            if (!concealed(owner)) {
                Vfx.trail(from, to, PINK, 8, false);
            }
            Vfx.cloud(ctx(), from, Particle.DUST, 3, 0.2, 0, PINK_DUST, false);
            Fx.sound(from, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.6f, 1.5f + Fx.jitter(0.1f));
        }
        if (mine > 0) {
            sharing = true;
            try {
                ctx().damage(attacker, owner, mine);
            } finally {
                sharing = false;
            }
        }
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        landing.update(owner, now);
        tickRibbons(owner, now, tick);
        if (tick % TWINKLE_PERIOD == 0 && !hidden(owner)) {
            // The wand twinkles in her hand.
            Location hand = wandTip(owner);
            Vfx.dot(hand, Particle.DUST_COLOR_TRANSITION, GLITTER, false);
            if (tick % (TWINKLE_PERIOD * 2) == 0) {
                Vfx.dot(hand, Particle.END_ROD, null, false);
            }
        }
    }

    private void tickRibbons(Player owner, long now, long tick) {
        double range = Math.max(1, params().num("ribbon_range"));
        Iterator<Ribbon> iterator = ribbons.values().iterator();
        while (iterator.hasNext()) {
            Ribbon ribbon = iterator.next();
            Player ally = ribbon.ally;
            boolean gone = !ally.isValid() || ally.isDead() || !ctx().isAlly(owner, ally)
                    || !ally.getWorld().equals(owner.getWorld());
            boolean far = !gone && ally.getLocation().distanceSquared(owner.getLocation()) > range * range;
            if (gone || far || now >= ribbon.until) {
                iterator.remove();
                ribbon.untie(far);
                if (far) {
                    owner.sendActionBar(Text.mm("<" + PINK_TAG + ">리본이 끊어졌습니다</" + PINK_TAG + "> <gray>너무 "
                            + "멀어졌어요</gray>"));
                }
                continue;
            }
            for (int i = 0; i < ribbon.bow.size(); i++) {
                BlockDisplay part = ribbon.bow.get(i);
                Displays.moveTo(part, ally.getLocation());
                Displays.animate(part, bowPose(ally, i, 1f), 2);
            }
            syncBow(ribbon);
            // The ribbon's particles are seen by everyone near it: never drawn to (or from) someone in hiding.
            if (tick % RIBBON_PERIOD == 0 && !concealed(owner) && !concealed(ally)) {
                drawRibbon(Fx.body(owner), Fx.body(ally), tick);
            }
        }
    }

    /** The bow on a tied ally's back is shown exactly to the viewers who can see that ally. */
    private void syncBow(Ribbon ribbon) {
        if (ribbon.bow.isEmpty()) {
            return;
        }
        Player ally = ribbon.ally;
        boolean invisible = invisible(ally);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(ally)) {
                continue;
            }
            boolean hide = invisible || !viewer.canSee(ally);
            UUID id = viewer.getUniqueId();
            if (hide && ribbon.bowHiddenFrom.add(id)) {
                for (BlockDisplay part : ribbon.bow) {
                    viewer.hideEntity(ctx().plugin(), part);
                }
            } else if (!hide && ribbon.bowHiddenFrom.remove(id)) {
                for (BlockDisplay part : ribbon.bow) {
                    viewer.showEntity(ctx().plugin(), part);
                }
            }
        }
    }

    /** A pink and white ribbon waving between two points (two strands twisting around each other). */
    private void drawRibbon(Location from, Location to, long tick) {
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        if (length < 0.5) {
            return;
        }
        Vector along = delta.clone().multiply(1 / length);
        Vector side = along.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1.0E-6) {
            side = new Vector(1, 0, 0);
        }
        side.normalize();
        Vector up = side.clone().crossProduct(along).normalize();
        int points = Math.min(24, Vfx.n(ctx(), (int) Math.round(length * 1.2)));
        for (int i = 1; i < points; i++) {
            double t = (double) i / points;
            double sag = Math.sin(t * Math.PI) * 0.35;
            double wave = tick * 0.35 + t * 9;
            Location at = from.clone().add(delta.clone().multiply(t)).subtract(0, sag, 0);
            Vector twist = side.clone().multiply(Math.cos(wave) * 0.09).add(up.clone().multiply(Math.sin(wave) * 0.09));
            Vfx.dot(at.clone().add(twist), Particle.DUST, PINK_DUST, false);
            if (i % 2 == 0) {
                Vfx.dot(at.clone().subtract(twist), Particle.DUST, WHITE_DUST, false);
            }
        }
    }

    /** Part {@code index} of a ribbon bow on {@code wearer}'s back: two loops and the knot. */
    private static Transformation bowPose(LivingEntity wearer, int index, float grow) {
        float body = (float) Body.scaleOf(wearer);
        float scale = body * grow;
        float yaw = wearer.getLocation().getYaw();
        if (wearer instanceof Player player) {
            yaw = player.getBodyYaw();
        }
        Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
        Vector3f back = new Vector3f(0, (float) (wearer.getHeight() * 0.78), -0.2f * body);
        if (index == 2) {
            Vector3f center = turn.transform(new Vector3f(back));
            return box(center, new Vector3f(0.09f, 0.09f, 0.07f).mul(scale), new Quaternionf(turn));
        }
        float side = index == 0 ? 1 : -1;
        Quaternionf loop = new Quaternionf(turn).rotateZ((float) Math.toRadians(side * 28));
        Vector3f center = turn.transform(new Vector3f(back).add(side * BOW_SIZE * 0.55f * scale, 0.02f * scale, 0));
        return box(center, new Vector3f(BOW_SIZE * scale, BOW_SIZE * 0.55f * scale, 0.05f * scale), loop);
    }

    /** About where the wand's tip is (right hand, a little in front). */
    private static Location wandTip(Player owner) {
        Location feet = owner.getLocation();
        Vector forward = Motion.horizontalFacing(feet);
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        return feet.add(0, owner.getHeight() * 0.62, 0).add(forward.multiply(0.45)).add(right.multiply(-0.35));
    }

    // ================================================================== active: heart stick

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        int hops = Math.max(1, inGirlMode() ? params().integer("girl_bounces") : params().integer("bounces"));
        new Heart(owner, hops).fire();
        return ActivationResult.success();
    }

    @Override
    public double cooldownMultiplier() {
        if (!inGirlMode()) {
            return 1.0;
        }
        double normal = Math.max(0.05, params().cooldownSeconds());
        return Math.clamp(params().num("girl_cooldown") / normal, 0.0, 1.0);
    }

    /**
     * The heart: flies straight from the wand until it meets an enemy (or a wall), then springs from target to
     * target, alternating between hurting an enemy and healing the most hurt ally (or the girl), never the same one
     * twice and never through a wall.
     */
    private final class Heart {
        private final Player owner;
        private final World world;
        private final int maxHops;
        private final Set<UUID> visited = new HashSet<>();
        private Location at;
        private @Nullable TextDisplay sprite;
        private int landed;
        private boolean enemyNext = true;
        private @Nullable LivingEntity target;
        private long hopTicks;
        /**
         * The flight leg now under way. Each leg (the first throw, every hop) is its own loop, and a loop that ends
         * because its leg landed must not pop the heart the next leg carries on.
         */
        private int leg;

        Heart(Player owner, int maxHops) {
            this.owner = owner;
            this.world = owner.getWorld();
            this.maxHops = maxHops;
            this.at = wandTip(owner);
        }

        void fire() {
            Fx.sound(at, Sound.ENTITY_ALLAY_ITEM_THROWN, 1.0f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.5f);
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.5f, 1.8f);
            Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 8, 0.2, 0, GLITTER, false);
            Vfx.cloud(ctx(), at, Particle.END_ROD, 4, 0.1, 0.03, null, false);
            if (reserve(1)) {
                sprite = keep(Displays.text(ctx(), owner, Displays.unrotated(at), Text.mm("<" + PINK_TAG + ">" + HEART
                        + "</" + PINK_TAG + ">"), HEART_SCALE, 0));
                sprite.setTeleportDuration(1);
                sprite.setGlowing(false);
            }
            Vector direction = owner.getEyeLocation().getDirection();
            double range = Math.max(1, params().num("heart_range"));
            long ticks = (long) Math.ceil(range / HEART_SPEED) + 1;
            int first = ++leg;
            Loop.repeat(ctx(), owner, 1, 1, ticks, run -> straight(direction, run), () -> fizzle(first));
        }

        /** The first flight: straight ahead along the aim. */
        private boolean straight(Vector direction, long run) {
            if (target != null) {
                return false;
            }
            Vector step = direction.clone().multiply(HEART_SPEED);
            LivingEntity enemy = firstEnemy(direction, HEART_SPEED);
            RayTraceResult wall = world.rayTraceBlocks(at, direction, HEART_SPEED, FluidCollisionMode.NEVER, true);
            if (enemy != null && (wall == null || hitDistance(enemy, direction, HEART_SPEED)
                    <= wall.getHitPosition().distance(at.toVector()))) {
                at = Fx.body(enemy);
                move();
                land(enemy);
                return false;
            }
            if (wall != null) {
                at = wall.getHitPosition().toLocation(world);
                pop(false);
                return false;
            }
            at.add(step);
            move();
            trail(run);
            if (!ctx().isInsideArena(at)) {
                pop(false);
                return false;
            }
            return true;
        }

        private @Nullable LivingEntity firstEnemy(Vector direction, double length) {
            LivingEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            Location middle = at.clone().add(direction.clone().multiply(length / 2));
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, length / 2 + 1.5)) {
                if (!seen(owner, enemy)) {
                    continue;
                }
                double distance = hitDistance(enemy, direction, length);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = enemy;
                }
            }
            return best;
        }

        private double hitDistance(LivingEntity entity, Vector direction, double length) {
            BoundingBox box = entity.getBoundingBox().expand(HEART_HITBOX);
            if (box.contains(at.toVector())) {
                return 0;
            }
            RayTraceResult hit = box.rayTrace(at.toVector(), direction, length);
            return hit == null ? Double.MAX_VALUE : hit.getHitPosition().distance(at.toVector());
        }

        /** The heart lands on {@code entity}: a hit for an enemy, a heal (and a ribbon) for the girl's side. */
        private void land(LivingEntity entity) {
            visited.add(entity.getUniqueId());
            landed++;
            Location body = Fx.body(entity);
            if (ctx().isEnemy(owner, entity)) {
                double damage = landed == 1 ? params().num("heart_damage") : params().num("bounce_damage");
                Combat.hit(ctx(), owner, entity, damage);
                Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.END_ROD, false);
                Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.9f, 1.4f + Fx.jitter(0.1f));
                Fx.sound(body, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.6f, 1.6f + Fx.jitter(0.1f));
                enemyNext = false;
            } else {
                ctx().heal(entity, params().num("heart_heal"));
                if (entity instanceof Player friend && !friend.equals(owner)) {
                    tie(owner, friend);
                }
                Vfx.cloud(ctx(), body.clone().add(0, 0.6, 0), Particle.HEART, 3, 0.3, 0, null, false);
                Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 8, 0.4, 0, PINK_GLOW, false);
                Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.9f, 1.8f);
                Fx.sound(body, Sound.ENTITY_PLAYER_LEVELUP, 0.3f, 2.0f);
                enemyNext = true;
            }
            if (landed >= maxHops) {
                pop(true);
                return;
            }
            LivingEntity next = enemyNext ? nextEnemy() : nextFriend();
            if (next == null) {
                pop(true);
                return;
            }
            target = next;
            hopTicks = 0;
            Fx.sound(at, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 0.6f, 1.6f + 0.08f * landed);
            int hop = ++leg;
            Loop.repeat(ctx(), owner, 1, 1, HOP_MAX_TICKS, this::home, () -> fizzle(hop));
        }

        /** The most hurt ally within reach of the heart (seen through no wall), else the girl herself. */
        private @Nullable LivingEntity nextFriend() {
            double reach = Math.max(1, params().num("bounce_range"));
            Player best = null;
            double missing = -1;
            for (Player ally : ctx().alliesNear(owner, at, reach)) {
                // A heart springing to a stealthed ally would show everyone where it hides.
                if (visited.contains(ally.getUniqueId()) || !clear(ally) || concealed(ally)) {
                    continue;
                }
                double gap = Combat.maxHealth(ally) - ally.getHealth();
                if (gap > missing) {
                    missing = gap;
                    best = ally;
                }
            }
            if (best != null) {
                return best;
            }
            double back = Math.max(reach, params().num("heart_range"));
            if (!visited.contains(owner.getUniqueId()) && owner.getWorld().equals(world)
                    && Fx.body(owner).distanceSquared(at) <= back * back && clear(owner)) {
                return owner;
            }
            return null;
        }

        /** The nearest other enemy within reach of the heart, seen by the girl and not behind a wall. */
        private @Nullable LivingEntity nextEnemy() {
            double reach = Math.max(1, params().num("bounce_range"));
            for (LivingEntity enemy : ctx().enemiesNear(owner, at, reach)) {
                if (!visited.contains(enemy.getUniqueId()) && seen(owner, enemy) && clear(enemy)) {
                    return enemy;
                }
            }
            return null;
        }

        private boolean clear(LivingEntity entity) {
            return Spots.clearPath(at, Fx.body(entity)) || Spots.clearPath(at, entity.getEyeLocation());
        }

        /** A hop: the heart springs after its next target and lands on it. */
        private boolean home(long run) {
            LivingEntity next = target;
            if (next == null || !next.isValid() || next.isDead() || !next.getWorld().equals(world)) {
                pop(true);
                return false;
            }
            hopTicks++;
            Location goal = Fx.body(next);
            Vector delta = goal.toVector().subtract(at.toVector());
            double distance = delta.length();
            if (distance <= HOP_SPEED + 0.3) {
                at = goal;
                move();
                target = null;
                land(next);
                return false;
            }
            Vector direction = delta.multiply(1 / distance);
            if (world.rayTraceBlocks(at, direction, HOP_SPEED, FluidCollisionMode.NEVER, true) != null) {
                pop(true);
                return false;
            }
            // A little arc: it springs up a bit on the way.
            double lift = Math.sin(Math.min(1.0, hopTicks / 8.0) * Math.PI) * 0.12;
            at.add(direction.multiply(HOP_SPEED)).add(0, lift, 0);
            move();
            trail(run);
            return true;
        }

        private void move() {
            TextDisplay shown = sprite;
            if (shown != null) {
                Displays.moveTo(shown, at);
            }
        }

        private void trail(long run) {
            Vfx.dot(at, Particle.DUST, run % 2 == 0 ? PINK_DUST : SKY_DUST, false);
            Vfx.dot(at.clone().add(Fx.jitter(0.2f), Fx.jitter(0.2f), Fx.jitter(0.2f)), Particle.DUST_COLOR_TRANSITION,
                    GLITTER, false);
            if (run % 3 == 0) {
                Vfx.dot(at, Particle.END_ROD, null, false);
            }
        }

        /** The heart bursts into glitter (after its last target, or on a wall). */
        private void pop(boolean sweet) {
            TextDisplay shown = sprite;
            sprite = null;
            if (shown != null && shown.isValid()) {
                Displays.shrinkAway(ctx(), owner, shown, 4);
            }
            Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, sweet ? 10 : 5, 0.3, 0, GLITTER, false);
            Fx.sound(at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.6f, 1.6f + Fx.jitter(0.1f));
        }

        /** The loop of leg {@code which} ran out: the heart fizzles, unless a later leg already carries it on. */
        private void fizzle(int which) {
            if (which == leg && sprite != null) {
                pop(false);
            }
        }
    }

    // ================================================================== ultimate: transformation!

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (transforming() || inGirlMode()) {
            return ActivationResult.fail("이미 변신했습니다");
        }
        long now = ctx().currentTick();
        long transform = Math.max(1, params().ticks("transform_time"));
        transformUntil = now + transform;
        girlUntil = transformUntil + Math.max(1, params().ticks("girl_duration"));
        finaleDone = false;
        starred.clear();
        pin = owner.getLocation();
        ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -1, Operation.MULTIPLY_SCALAR_1, transform);
        ctx().addModifier(owner, owner, Attribute.JUMP_STRENGTH, -1, Operation.MULTIPLY_SCALAR_1, transform);
        owner.setVelocity(new Vector());
        Location feet = owner.getLocation();
        Fx.sound(feet, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.6f);
        Fx.sound(feet, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.2f);
        Fx.sound(feet, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 0.8f, 1.6f);
        orbitStars(owner, transform);
        owner.sendActionBar(Text.mm("<" + PINK_TAG + "><b>변신!</b></" + PINK_TAG + "> <gray>변신 중엔 공격 "
                + "금지!</gray>"));
        finaleRequested = false;
        pendingStars.clear();
        // The whole show (transformation, magical girl time and finale) runs in the ultimate's own task.
        long total = girlUntil - now + 2;
        Loop.repeat(ctx(), owner, 0, 1, total, run -> ultStep(owner), () -> endGirl(owner));
        return ActivationResult.success();
    }

    /** One tick of the ultimate: the pinned transformation, then the magical girl time, then the finale. */
    private boolean ultStep(Player owner) {
        if (finaleDone) {
            return false;
        }
        long now = ctx().currentTick();
        tickTransformation(owner, now);
        while (!pendingStars.isEmpty()) {
            star(owner, pendingStars.removeFirst());
        }
        if (now < transformUntil) {
            return true;
        }
        if (finaleRequested || now >= girlUntil) {
            girlUntil = Math.min(girlUntil, now);
            endGirl(owner);
            return false;
        }
        girlStep(owner, now);
        return true;
    }

    private boolean transforming() {
        return ctx().currentTick() < transformUntil;
    }

    private boolean inGirlMode() {
        long now = ctx().currentTick();
        return now >= transformUntil && now < girlUntil;
    }

    /** F during the magical girl time: the finale comes early. Ignored (but taken) while transforming. */
    @Override
    public boolean onUltimateKey() {
        if (transforming()) {
            return true;
        }
        if (!inGirlMode() || finaleDone) {
            return false;
        }
        finaleRequested = true;
        return true;
    }

    /** Stars orbit and rise around the girl while the ribbons wrap her. */
    private void orbitStars(Player owner, long ticks) {
        List<TextDisplay> stars = new ArrayList<>();
        if (reserve(TRANSFORM_STARS)) {
            Location feet = Displays.unrotated(owner.getLocation());
            for (int i = 0; i < TRANSFORM_STARS; i++) {
                String glyph = i % 2 == 0 ? "<" + GOLD_TAG + ">★</" + GOLD_TAG + ">" : "<" + PINK_TAG + ">" + HEART
                        + "</" + PINK_TAG + ">";
                TextDisplay star = keep(Displays.text(ctx(), owner, feet, Text.mm(glyph), 1.1f, ticks + 6));
                star.setTeleportDuration(1);
                stars.add(star);
            }
        }
        Location base = owner.getLocation();
        Loop.repeat(ctx(), owner, 0, 1, ticks, run -> {
            double t = (double) run / Math.max(1, ticks);
            for (int i = 0; i < stars.size(); i++) {
                double angle = run * 0.32 + i * TAU / Math.max(1, stars.size());
                double radius = 1.1 - 0.35 * t;
                Location spot = base.clone().add(Math.cos(angle) * radius, 0.2 + t * 2.0 + (i % 2) * 0.25,
                        Math.sin(angle) * radius);
                Displays.moveTo(stars.get(i), spot);
            }
            Vfx.helix(ctx(), base, 0.75, 2.4, 1.5, 3, 7, run * 0.4, Particle.DUST, run % 2 == 0 ? PINK_DUST
                    : SKY_DUST, true);
            if (run % 2 == 0) {
                Vfx.cloud(ctx(), base.clone().add(0, 1.2, 0), Particle.END_ROD, 3, 0.3, 0.02, null, true);
                Vfx.dot(base.clone().add(0, 0.1 + t * 2.6, 0), Particle.WAX_OFF, null, true);
            }
            if (run % 5 == 0) {
                Fx.sound(base, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.9f, 0.8f + (float) (run / 5) * 0.18f);
            }
            return true;
        }, () -> {
            for (TextDisplay star : stars) {
                if (star.isValid()) {
                    star.remove();
                }
            }
        });
    }

    private void tickTransformation(Player owner, long now) {
        if (now >= transformUntil) {
            return;
        }
        Location anchor = pin;
        if (anchor != null && anchor.getWorld().equals(owner.getWorld())
                && owner.getLocation().distanceSquared(anchor) > PIN_SLACK * PIN_SLACK) {
            Location back = anchor.clone();
            back.setYaw(owner.getLocation().getYaw());
            back.setPitch(owner.getLocation().getPitch());
            owner.teleport(back);
            owner.setVelocity(new Vector());
        }
        if (now == transformUntil - 1) {
            // Transformed: the light bursts open.
            Location chest = Fx.body(owner);
            Vfx.dot(chest, Particle.FLASH, PINK, true);
            Vfx.sphere(ctx(), chest, 1.4, 30, Particle.DUST_COLOR_TRANSITION, PINK_GLOW, true);
            Vfx.cloud(ctx(), chest, Particle.FIREWORK, 14, 0.4, 0.12, null, true);
            Fx.sound(chest, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f, 1.2f);
            Fx.sound(chest, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
            dress(owner);
            long girl = Math.max(0, girlUntil - transformUntil);
            double cap = Math.max(0, params().num("girl_cooldown"));
            double left = ctx().cooldownRemaining(owner);
            if (left > cap) {
                ctx().reduceCooldown(owner, left - cap);
            }
            owner.sendActionBar(Text.mm("<" + PINK_TAG + "><b>마법소녀!</b></" + PINK_TAG + "> <gray>" + Fx.seconds(girl)
                    + "초 · 공중 점프, 빠른 하트 · F: 사랑의 피날레</gray>"));
        }
    }

    /** The magical girl's outfit: a big pink bow on her back. */
    private void dress(Player owner) {
        for (BlockDisplay part : outfit) {
            if (part.isValid()) {
                part.remove();
            }
        }
        outfit.clear();
        if (!reserve(3)) {
            return;
        }
        Location feet = Displays.unrotated(owner.getLocation());
        long life = Math.max(1, girlUntil - ctx().currentTick()) + 5;
        for (int i = 0; i < 3; i++) {
            BlockDisplay part = keep(Displays.block(ctx(), owner, feet, (i == 2 ? BOW_KNOT : BOW).createBlockData(),
                    bigBowPose(owner, i, 0.05f), life));
            part.setTeleportDuration(FOLLOW_LERP);
            Displays.animateNextTick(ctx(), owner, part, bigBowPose(owner, i, 1f), 5);
            outfit.add(part);
        }
    }

    /** Her own bow is the big one. */
    private static Transformation bigBowPose(Player owner, int index, float grow) {
        return bowPose(owner, index, grow * 1.8f);
    }

    private void girlStep(Player owner, long tick) {
        for (int i = 0; i < outfit.size(); i++) {
            BlockDisplay part = outfit.get(i);
            Displays.moveTo(part, owner.getLocation());
            Displays.animate(part, bigBowPose(owner, i, 1f), 2);
        }
        boolean grounded = Motion.isGrounded(owner) || owner.isInWater();
        if (grounded) {
            doubleJumped = false;
            removeOwnSlowFalling(owner);
        } else if (!owner.isSneaking()) {
            owner.setFallDistance(0);
            PotionEffect current = owner.getPotionEffect(PotionEffectType.SLOW_FALLING);
            if (current == null || current.getDuration() < SLOW_FALL_TICKS / 2) {
                owner.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, (int) SLOW_FALL_TICKS, 0,
                        false, false, true));
            }
        }
        if (tick % 2 == 0 && !hidden(owner)) {
            Location back = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
            Vfx.dot(back.add(Fx.jitter(0.3f), Fx.jitter(0.4f), Fx.jitter(0.3f)), Particle.DUST_COLOR_TRANSITION,
                    GLITTER, false);
            if (tick % 6 == 0) {
                Vfx.dot(owner.getLocation().add(0, 0.1, 0), Particle.END_ROD, null, false);
            }
        }
    }

    /** One more jump in mid-air while she is a magical girl. */
    private void doubleJump(Input keys) {
        Player owner = player();
        if (!inGirlMode() || doubleJumped || !ctx().isAlive(owner) || ctx().isSilenced(owner)) {
            return;
        }
        if (Motion.isGrounded(owner) || owner.isInWater() || owner.isFlying()) {
            return;
        }
        doubleJumped = true;
        removeOwnSlowFalling(owner);
        Vector forward = Motion.horizontalFacing(owner.getLocation());
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        Vector push = forward.clone().multiply((keys.isForward() ? 1 : 0) - (keys.isBackward() ? 1 : 0))
                .add(right.multiply((keys.isRight() ? 1 : 0) - (keys.isLeft() ? 1 : 0)));
        if (push.lengthSquared() > 1.0E-6) {
            push.normalize().multiply(JUMP_PUSH);
        }
        push.setY(Motion.launchSpeed(Math.max(0, params().num("double_jump"))));
        owner.setVelocity(Motion.clamp(push, MAX_SPEED));
        owner.setFallDistance(0);
        Location feet = owner.getLocation();
        Vfx.ring(ctx(), feet.clone().add(0, 0.05, 0), 0.6, 12, 0, Particle.DUST_COLOR_TRANSITION, PINK_GLOW, false);
        Vfx.cloud(ctx(), feet, Particle.END_ROD, 4, 0.2, 0.02, null, false);
        Fx.sound(feet, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.8f);
        Fx.sound(feet, Sound.ENTITY_BREEZE_JUMP, 0.5f, 1.6f);
    }

    /** The magical girl time is over: the outfit goes and the finale falls. */
    private void endGirl(Player owner) {
        if (finaleDone) {
            return;
        }
        finaleDone = true;
        for (BlockDisplay part : outfit) {
            if (part.isValid()) {
                Displays.shrinkAway(ctx(), owner, part, 5);
            }
        }
        outfit.clear();
        removeOwnSlowFalling(owner);
        landing.armIfAirborne(owner, ctx().currentTick(), LANDING_TICKS);
        finale(owner);
    }

    /** The finale's spot: the block aimed at (within range), or the ground under the end of the aim. */
    private Location finaleSpot(Player owner) {
        double range = Math.max(1, params().num("finale_range"));
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        Location spot = hit != null ? hit.getHitPosition().toLocation(owner.getWorld())
                .subtract(direction.clone().multiply(0.2)) : eye.clone().add(direction.multiply(range));
        Location ground = spot.clone().add(0, 0.3, 0);
        Spots.snapToGround(owner.getWorld(), ground, 16);
        if (ground.getY() >= spot.getY() + 0.25 || !ctx().isInsideArena(ground)) {
            Location feet = owner.getLocation();
            Spots.snapToGround(feet.getWorld(), feet, 3);
            return Displays.unrotated(feet);
        }
        return Displays.unrotated(ground);
    }

    /** 사랑의 피날레: a giant heart grows over the spot and falls on it. */
    private void finale(Player owner) {
        Location spot = finaleSpot(owner);
        long delay = Math.max(1, params().ticks("finale_delay"));
        double radius = Math.max(0.5, params().num("finale_radius"));
        TextDisplay giant = null;
        if (reserve(1)) {
            giant = keep(Displays.text(ctx(), owner, spot.clone().add(0, FINALE_LIFT, 0), Text.mm("<" + PINK_TAG + ">"
                    + HEART + "</" + PINK_TAG + ">"), 0.5f, delay + 4));
            giant.setTeleportDuration((int) Math.min(59, delay));
            TextDisplay grown = giant;
            ctx().runLater(owner, 1, () -> {
                Displays.animate(grown, Displays.centered(0, 0, 0, 14f, 14f, 14f, 0, 0, 0), (int) Math.min(40, delay));
                Displays.moveTo(grown, spot.clone().add(0, 0.6, 0));
            });
        }
        Fx.sound(owner.getLocation(), Sound.BLOCK_BELL_RESONATE, 1.0f, 1.8f);
        Fx.sound(spot, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.4f);
        owner.sendActionBar(Text.mm("<" + PINK_TAG + "><b>사랑의 피날레!</b></" + PINK_TAG + ">"));
        Location ring = spot.clone().add(0, 0.1, 0);
        Loop.repeat(ctx(), owner, 0, 1, delay, run -> {
            if (run % 2 == 0) {
                Vfx.ring(ctx(), ring, radius, (int) Math.round(radius * 7), run * 0.1, Particle.DUST, PINK_DUST, true);
                Vfx.ring(ctx(), ring, radius * (1 - (double) run / delay), 10, -run * 0.1, Particle.DUST, SKY_DUST,
                        true);
            }
            if (run % 5 == 0) {
                Fx.sound(spot, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.0f + 0.15f * (run / 5));
            }
            return true;
        }, () -> land(owner, spot, radius));
    }

    private void land(Player owner, Location spot, double radius) {
        int friends = 0;
        double allyRange = Math.max(0, params().num("finale_ally_range"));
        for (Player ally : ctx().alliesNear(owner, owner.getLocation(), allyRange)) {
            if (!ally.isDead()) {
                friends++;
            }
        }
        double bonus = Math.min(Math.max(0, params().num("finale_ally_max")),
                friends * Math.max(0, params().num("finale_ally_bonus")));
        double damage = params().num("finale_damage") + bonus;
        for (LivingEntity enemy : ctx().enemiesNear(owner, spot, radius)) {
            Combat.hit(ctx(), owner, enemy, damage);
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                Vector away = enemy.getLocation().toVector().subtract(spot.toVector()).setY(0);
                Vector push = away.lengthSquared() > 1.0E-4 ? away.normalize().multiply(0.4) : new Vector();
                Motion.push(enemy, push.setY(0.35), MAX_SPEED);
            }
        }
        double heal = Math.max(0, params().num("finale_heal"));
        List<Player> healed = new ArrayList<>(ctx().alliesNear(owner, spot, radius));
        if (Fx.body(owner).distance(spot) <= radius + 1.0) {
            healed.add(owner);
        }
        for (Player friend : healed) {
            ctx().heal(friend, heal);
            Vfx.cloud(ctx(), Fx.body(friend).add(0, 0.6, 0), Particle.HEART, 3, 0.3, 0, null, false);
        }
        Location center = spot.clone().add(0, 0.6, 0);
        Vfx.dot(center, Particle.FLASH, PINK, true);
        Vfx.burst(ctx(), center, PALETTE, radius * 0.6, Particle.END_ROD, true);
        Vfx.shockwave(ctx(), owner, spot.clone().add(0, 0.15, 0), 0.5, radius + 0.5, 8, Particle.DUST_COLOR_TRANSITION,
                PINK_GLOW, true);
        Vfx.cloud(ctx(), center, Particle.HEART, 12, radius * 0.4, 0.05, null, true);
        Vfx.cloud(ctx(), center, Particle.FIREWORK, 24, radius * 0.3, 0.15, null, true);
        Fx.sound(spot, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.2f, 1.1f);
        Fx.sound(spot, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f, 1.3f);
        Fx.sound(spot, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 0.9f);
        Fx.sound(spot, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        // Afterglow: glitter drifts down for a moment.
        Loop.repeat(ctx(), owner, 2, 2, 10, run -> {
            Vfx.dot(Vfx.scatter(spot, radius).add(0, 0.5 + ThreadLocalRandom.current().nextDouble(1.5), 0),
                    Particle.DUST_COLOR_TRANSITION, GLITTER, false);
            Vfx.dot(Vfx.scatter(spot, radius).add(0, 0.3, 0), Particle.DUST, SMALL_PINK, false);
            return true;
        });
    }

    // ================================================================== damage hooks

    /** Nothing hurts a girl mid-transformation, and whoever tries is punished by a star. */
    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        long now = ctx().currentTick();
        if (now < transformUntil && !isInescapable(event)) {
            event.setCancelled(true);
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                punish(byEntity);
            }
            return;
        }
        if (landing.absorb(event, now)) {
            return;
        }
        if (inGirlMode() && Combat.isFall(event)) {
            event.setCancelled(true);
        }
    }

    private static boolean isInescapable(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        if (cause == DamageCause.VOID || cause == DamageCause.KILL || cause == DamageCause.SUICIDE) {
            return true;
        }
        DamageType type = event.getDamageSource().getDamageType();
        return type == DamageType.OUT_OF_WORLD || type == DamageType.GENERIC_KILL;
    }

    /** "변신 중엔 공격 금지!": a star flies back at the attacker, stings and binds it (once per attacker). */
    private void punish(EntityDamageByEntityEvent event) {
        Player owner = player();
        Entity damager = event.getDamager();
        Player attacker = ctx().resolveAttacker(damager);
        LivingEntity culprit = attacker != null && ctx().isEnemy(owner, attacker) ? attacker
                : damager instanceof LivingEntity living && ctx().isEnemy(owner, living) ? living : null;
        if (culprit == null || !starred.add(culprit.getUniqueId())) {
            return;
        }
        // The star flies from the ultimate's own task (it is part of the ultimate).
        pendingStars.add(culprit);
    }

    /** The star of "변신 중엔 공격 금지!" hits its culprit: a sting and a moment bound in place. */
    private void star(Player owner, LivingEntity target) {
        if (!Combat.isTargetable(ctx(), owner, target)) {
            return;
        }
        Combat.hit(ctx(), owner, target, params().num("star_damage"));
        long root = Math.max(1, params().ticks("star_root"));
        if (Combat.isTargetable(ctx(), owner, target)) {
            ctx().addModifier(owner, target, Attribute.MOVEMENT_SPEED, -1, Operation.MULTIPLY_SCALAR_1, root);
            ctx().addModifier(owner, target, Attribute.JUMP_STRENGTH, -1, Operation.MULTIPLY_SCALAR_1, root);
        }
        Location body = Fx.body(target);
        Vfx.trail(Fx.body(owner), body, GOLD, 6, false);
        Vfx.burst(ctx(), body, PALETTE, 0.5, Particle.WAX_OFF, false);
        Fx.sound(body, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, 1.6f);
        Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.6f);
        if (seen(owner, target) && reserve(1)) {
            TextDisplay sign = keep(Displays.text(ctx(), owner, Displays.unrotated(target.getLocation().add(0,
                    target.getHeight() + 0.5, 0)), Text.mm("<" + GOLD_TAG + "><b>★ 변신 중엔 공격 금지! ★</b></"
                    + GOLD_TAG + ">"), 1.0f, 30));
            sign.setTeleportDuration(20);
            ctx().runLater(owner, 1, () -> Displays.moveTo(sign, sign.getLocation().add(0, 0.6, 0)));
        }
        if (target instanceof Player victim) {
            victim.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>변신 중엔 공격 금지!</b></" + GOLD_TAG + ">"));
        }
    }

    // ================================================================== helpers

    private static void removeOwnSlowFalling(Player owner) {
        PotionEffect effect = owner.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (effect != null && effect.getDuration() <= SLOW_FALL_TICKS && !effect.hasParticles()) {
            owner.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
    }

    /** True if the girl cannot be seen (invisibility): her glitter must not give her away. */
    private static boolean hidden(Player owner) {
        return invisible(owner);
    }

    private static boolean invisible(Player player) {
        return player.isInvisible() || player.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    /**
     * True if {@code player} is hiding from someone: invisible, or hidden from any other player in its world
     * (stealth). Particles and sounds are seen by everyone near them, so none may be drawn on such a player.
     */
    private static boolean concealed(Player player) {
        if (invisible(player)) {
            return true;
        }
        for (Player viewer : player.getWorld().getPlayers()) {
            if (!viewer.equals(player) && !viewer.canSee(player)) {
                return true;
            }
        }
        return false;
    }

    /** True unless {@code entity} is a player hidden from the owner (its visuals would give it away). */
    private static boolean seen(Player owner, Entity entity) {
        return !(entity instanceof Player player) || owner.canSee(player);
    }

    /** A box of {@code size} centered on {@code center} (relative to the display), turned by {@code rotation}. */
    private static Transformation box(Vector3f center, Vector3f size, Quaternionf rotation) {
        Vector3f translation = new Vector3f(center).sub(new Quaternionf(rotation).transform(new Vector3f(size)
                .mul(0.5f)));
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    /** Whether {@code ally} is tied to the girl by a ribbon (for tests). */
    public boolean isTied(Entity ally) {
        Ribbon ribbon = ribbons.get(ally.getUniqueId());
        return ribbon != null && ctx().currentTick() < ribbon.until;
    }

    /** Whether the girl is transformed right now (for tests). */
    public boolean isMagicalGirl() {
        return inGirlMode() && !finaleDone;
    }
}
