package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Inputs;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
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
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
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
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 밟기 달인 — the hero of the classic platform games who beats enemies by jumping on their heads. Landing on a head
 * squashes it and bounces the stomper high, and every further stomp before touching the ground hits harder, with candy
 * bursts of healing along the chain. The active springs up a launch pad that throws the enemies standing on it into the
 * air and catapults the stomper and its allies high, a stage for stomp chains. The ultimate is the shining candy:
 * a few seconds of rainbow invincibility and music, running and jumping further, stomping twice as hard and shaking the
 * ground at every landing, but unable to strike and still open to pulls and roots.
 *
 * <p>Look (tracksuit green, candy pink, spring silver): green motes trail a falling stomper, each stomp pops a pink
 * "뽁!" with a squash ring and stars, the chain count grows in candy colours over the head. The pad is a pink plate on
 * silver springs that shoots up and settles, wobbling with a "boing" whenever someone bounces off it. The candy turns
 * the stomper into a rainbow: cycling colour spirals, two orbiting stars of stained glass, a rainbow trail and a jaunty
 * note-block tune that speeds up in its last second.
 */
public final class StomperAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("stomper", "밟기 달인", Material.RABBIT_FOOT)
            .color(TextColor.color(0x3FAF5A))
            .difficulty(1)
            .passive("밟기",
                    "떨어지며 적의 머리를 밟으면 '뽁!' {stomp_damage} 피해를 주고 {flatten_duration}초간 납작하게 "
                            + "만들며(이동 속도 -{flatten_slow}%), 나는 약 {bounce_height}블록 높이로 다시 튀어 오릅니다. "
                            + "땅에 닿지 않고 연속으로 밟으면 한 번마다 피해가 {stomp_bonus}씩 늘어납니다(최대 "
                            + "{stomp_max}, 같은 적은 {stomp_repeat}초마다). {candy_chain}연속이면 별사탕이 터져 체력 {candy_heal}, {bonus_chain}연속이면 "
                            + "\"보너스!\" 체력 {bonus_heal}을 회복합니다. 받는 낙하 피해가 {fall_reduction}% 줄어듭니다.")
            .active("용수철 발판",
                    "바라보는 곳({pad_range}블록) 바닥에 폭 {pad_width}블록의 용수철 발판을 {pad_duration}초간 깝니다"
                            + "(최대 {pad_max}개, 더 깔면 가장 오래된 것이 사라집니다). 발판이 {pad_pop}블록 높이로 튕겨 솟을 "
                            + "때 그 위의 적은 {pad_damage} 피해를 입고 약 {pad_lift}블록 떠올라 밟기 좋은 먹잇감이 됩니다. "
                            + "발판을 밟은 나와 아군은 '뿅' 하고 약 {pad_bounce}블록 높이 튀어 오르며, 그 착지에는 낙하 "
                            + "피해를 받지 않습니다.",
                    8)
            .ultimate("무적 사탕",
                    "반짝이는 무지개 사탕을 깨물어 {candy_duration}초간 무지개빛으로 번쩍이며 신나는 음악이 흐릅니다. "
                            + "그동안 받는 피해를 모두 무시하고 이동 속도 +{candy_speed}%, 점프력 +{candy_jump}%, 밟기 "
                            + "피해가 {candy_stomp}배가 되며, 착지할 때마다 반경 {quake_radius}블록에 작은 충격파로 "
                            + "{quake_damage} 피해를 줍니다(같은 적은 {quake_cooldown}초마다). 대신 근접 공격을 할 수 없고, "
                            + "끌어당기기·속박 같은 제어는 그대로 받습니다. 끝나기 1초 전에 음악이 빨라집니다.")
            .ultimateNotStandalone()
            .param("stomp_damage", 3)
            .param("stomp_bonus", 1)
            .param("stomp_max", 6)
            .param("flatten_duration", 0.5)
            .param("flatten_slow", 60)
            .param("bounce_height", 2.5)
            .param("stomp_repeat", 1.5)
            .param("candy_chain", 3)
            .param("candy_heal", 2)
            .param("bonus_chain", 5)
            .param("bonus_heal", 4)
            .param("fall_reduction", 50)
            .param("pad_range", 8)
            .param("pad_width", 3)
            .param("pad_duration", 6)
            .param("pad_max", 2)
            .param("pad_pop", 2.5)
            .param("pad_damage", 3)
            .param("pad_lift", 4)
            .param("pad_bounce", 5)
            .param("candy_duration", 4.5)
            .param("candy_speed", 40)
            .param("candy_jump", 50)
            .param("candy_stomp", 2)
            .param("quake_radius", 2)
            .param("quake_damage", 1.5)
            .param("quake_cooldown", 1)
            .build();

    private static final String PINK_TAG = "#FF8FC8";
    private static final String[] RAINBOW_TAGS = {"#FF5A5A", "#FFA94D", "#FFE15A", "#5FD35F", "#4FB6E8", "#9B6BFF"};

    // ------------------------------------------------------------------ look
    /** Spring silver, candy pink, tracksuit green. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xFF8FC8, 0x3FAF5A);
    private static final Color GREEN = Color.fromRGB(0x3FAF5A);
    private static final Color PINK = Color.fromRGB(0xFF8FC8);
    private static final Color SILVER = Color.fromRGB(0xC9CED6);
    private static final Particle.DustOptions GREEN_DUST = new Particle.DustOptions(GREEN, 0.8f);
    private static final Particle.DustOptions PINK_DUST = new Particle.DustOptions(PINK, 1.0f);
    private static final Particle.DustOptions SILVER_DUST = new Particle.DustOptions(SILVER, 0.8f);
    private static final Color[] RAINBOW = {Color.fromRGB(0xFF5A5A), Color.fromRGB(0xFFA94D), Color.fromRGB(0xFFE15A),
            Color.fromRGB(0x5FD35F), Color.fromRGB(0x4FB6E8), Color.fromRGB(0x9B6BFF)};
    private static final Material[] RAINBOW_GLASS = {Material.RED_STAINED_GLASS, Material.ORANGE_STAINED_GLASS,
            Material.YELLOW_STAINED_GLASS, Material.LIME_STAINED_GLASS, Material.LIGHT_BLUE_STAINED_GLASS,
            Material.PURPLE_STAINED_GLASS};

    private static final Material PLATE = Material.PINK_CONCRETE;
    private static final Material PLATE_RIM = Material.WHITE_CONCRETE;
    private static final Material SPRING = Material.IRON_BLOCK;
    private static final Material CANDY = Material.PINK_GLAZED_TERRACOTTA;

    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 2.0;
    /** Feet this far below the top of a head, falling past it, still stomp it (the server sees bodies late). */
    private static final double STOMP_BELOW = 0.6;
    private static final double STOMP_ABOVE = 0.35;
    private static final double STOMP_SIDE = 0.2;
    private static final double FALLING = -0.04;
    private static final double BOUNCE_FORWARD = 0.25;
    private static final long LANDING_GUARD = 100;
    private static final double PAD_DEPTH = 1.0;
    private static final double PAD_REACH_UP = 0.7;
    private static final double PAD_REACH_DOWN = 0.15;
    private static final long POP_TICKS = 4;
    private static final long SETTLE_TICKS = 6;
    private static final long ALLY_GUARD = 100;
    private static final long TUNE_STEP = 3;
    private static final long TUNE_FAST = 2;
    /** A jaunty tune in note-block pitches (0.5..2.0). */
    private static final float[] TUNE = {1.0f, 1.26f, 1.5f, 1.26f, 1.68f, 1.5f, 1.26f, 1.0f, 1.12f, 1.33f, 1.68f, 2.0f};

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final LandingGuard landing = new LandingGuard();
    private final Map<UUID, Long> lastStomp = new HashMap<>();
    private final Deque<Pad> pads = new ArrayDeque<>();
    private final Map<UUID, Long> allyGuards = new HashMap<>();
    private final FallGuard fallGuard = new FallGuard();
    private boolean listening;
    private double lastY = Double.NaN;
    private int airTicks;
    private int chain;
    private @Nullable Candy candy;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    /** Stomps made since the stomper last stood on the ground. */
    public int chain() {
        return chain;
    }

    /** True while the candy's invincibility lasts. */
    public boolean isInvincible() {
        Candy current = candy;
        return current != null && current.active();
    }

    /** The launch pads lying now. */
    public int padCount() {
        return pads.size();
    }

    @Override
    public void onGrant() {
        Bukkit.getPluginManager().registerEvents(fallGuard, ctx().plugin());
        listening = true;
    }

    @Override
    public void onRevoke() {
        if (listening) {
            HandlerList.unregisterAll(fallGuard);
            listening = false;
        }
        Candy current = candy;
        if (current != null) {
            current.end(false);
        }
        candy = null;
        pads.clear();
        allyGuards.clear();
        lastStomp.clear();
        live.clear();
    }

    // ================================================================== passive: stomp

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        landing.update(owner, now);
        Location feet = owner.getLocation();
        double y = feet.getY();
        boolean grounded = Motion.isGrounded(owner) || owner.isInWater();
        if (!Double.isNaN(lastY) && !grounded && y - lastY < FALLING) {
            stompCheck(owner, lastY, y);
        }
        if (grounded) {
            if (airTicks >= 3) {
                landed(owner);
            }
            airTicks = 0;
            chain = 0;
        } else {
            airTicks++;
        }
        lastY = y;
        if (!pads.isEmpty()) {
            for (Pad pad : List.copyOf(pads)) {
                pad.tick();
            }
        }
        if (chain > 0 && tick % 2 == 0 && !owner.isInvisible()) {
            // The chain glows under the falling stomper.
            Vfx.dot(feet.clone().add(Fx.jitter(0.2f), 0.05, Fx.jitter(0.2f)), Particle.DUST, chain >= 3 ? PINK_DUST
                    : GREEN_DUST, false);
        } else if (!grounded && airTicks > 4 && tick % 4 == 0 && !owner.isInvisible()) {
            Vfx.dot(feet.clone().add(0, 0.05, 0), Particle.DUST, GREEN_DUST, false);
        }
    }

    /** The stomper's feet went from {@code fromY} to {@code toY} this tick: any head they crossed is stomped. */
    private void stompCheck(Player owner, double fromY, double toY) {
        BoundingBox body = owner.getBoundingBox();
        long now = ctx().currentTick();
        for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getLocation(), 3)) {
            BoundingBox box = enemy.getBoundingBox();
            double top = box.getMaxY();
            if (fromY < top - STOMP_BELOW || toY > top + STOMP_ABOVE) {
                continue;
            }
            if (body.getMaxX() < box.getMinX() - STOMP_SIDE || body.getMinX() > box.getMaxX() + STOMP_SIDE
                    || body.getMaxZ() < box.getMinZ() - STOMP_SIDE || body.getMinZ() > box.getMaxZ() + STOMP_SIDE) {
                continue;
            }
            Long last = lastStomp.get(enemy.getUniqueId());
            // The same head only every so often: a lone enemy cannot be juggled forever.
            if (last != null && now - last < Math.max(1, params().ticks("stomp_repeat"))) {
                continue;
            }
            lastStomp.put(enemy.getUniqueId(), now);
            stomp(owner, enemy);
            return;
        }
    }

    /** "뽁!" — squashed, and up the stomper goes again. */
    private void stomp(Player owner, LivingEntity enemy) {
        chain++;
        double damage = Math.min(Math.max(0, params().num("stomp_max")),
                params().num("stomp_damage") + params().num("stomp_bonus") * (chain - 1));
        if (isInvincible()) {
            damage *= Math.max(1, params().num("candy_stomp"));
        }
        Combat.hit(ctx(), owner, enemy, damage);
        long flatten = params().ticks("flatten_duration");
        double slow = Math.clamp(params().num("flatten_slow") / 100.0, 0.0, 0.89);
        if (Combat.isTargetable(ctx(), owner, enemy) && flatten > 0 && slow > 0) {
            ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1, flatten);
        }
        // Up again: twice a jump, a little forward if the stomper leans that way.
        Vector bounce = Inputs.isHeld(owner, InputKey.FORWARD)
                ? Motion.horizontalFacing(owner.getLocation()).multiply(BOUNCE_FORWARD) : new Vector();
        bounce.setY(Motion.launchSpeed(Math.max(0, params().num("bounce_height"))));
        owner.setVelocity(Motion.clamp(bounce, MAX_SPEED));
        owner.setFallDistance(0);
        airTicks = 1;

        Location head = enemy.getLocation().add(0, enemy.getHeight(), 0);
        if (!ctx().isConcealedFrom(enemy, owner)) {
            Vfx.ring(ctx(), head.clone().add(0, 0.05, 0), 0.6, 12, 0, Particle.DUST, PINK_DUST, false);
            Vfx.ring(ctx(), head.clone().add(0, 0.05, 0), 0.9, 14, 0.2, Particle.DUST, SILVER_DUST, false);
            Vfx.cloud(ctx(), head, Particle.WAX_ON, 5, 0.3, 0.1, null, false);
            Vfx.cloud(ctx(), head, Particle.CRIT, 6, 0.3, 0.2, null, false);
            pop(owner, head.clone().add(0, 0.6, 0), "<b><" + PINK_TAG + ">뽁!</" + PINK_TAG + "></b>"
                    + (chain > 1 ? " <" + rainbowTag(chain) + ">×" + chain + "</" + rainbowTag(chain) + ">" : ""));
        }
        Fx.sound(head, Sound.ENTITY_SLIME_SQUISH, 1.0f, 1.4f + 0.08f * chain);
        Fx.sound(head, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, Math.min(2.0f, 0.9f + 0.15f * chain));
        Fx.sound(head, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.6f, 1.6f);

        int candyChain = Math.max(1, (int) Math.round(params().num("candy_chain")));
        int bonusChain = Math.max(1, (int) Math.round(params().num("bonus_chain")));
        if (chain == bonusChain) {
            ctx().heal(owner, Math.max(0, params().num("bonus_heal")));
            reward(owner, "<b><" + PINK_TAG + ">보너스!</" + PINK_TAG + "></b>", true);
        } else if (chain == candyChain) {
            ctx().heal(owner, Math.max(0, params().num("candy_heal")));
            reward(owner, "<" + PINK_TAG + ">별사탕!</" + PINK_TAG + ">", false);
        }
    }

    /** A star candy (or the big bonus) bursts over the stomper. */
    private void reward(Player owner, String line, boolean big) {
        Location over = owner.getLocation().add(0, owner.getHeight() + 0.4, 0);
        for (int i = 0; i < (big ? 6 : 3); i++) {
            Color color = RAINBOW[i % RAINBOW.length];
            Vfx.cloud(ctx(), over, Particle.DUST, big ? 4 : 3, 0.5, 0, new Particle.DustOptions(color, 1.1f), false);
        }
        Vfx.cloud(ctx(), over, Particle.HEART, big ? 3 : 1, 0.3, 0, null, false);
        Fx.sound(over, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, big ? 1.0f : 1.6f);
        Fx.sound(over, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.9f, big ? 1.5f : 2.0f);
        if (big) {
            Fx.sound(over, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        }
        owner.sendActionBar(Text.mm(line + " <gray>" + chain + "연속 밟기</gray>"));
    }

    /** A little sign that pops up and floats away. */
    private void pop(Player owner, Location at, String mini) {
        if (!reserve(1)) {
            return;
        }
        TextDisplay sign = keep(Displays.text(ctx(), owner, Displays.unrotated(at), Text.mm(mini), 0.4f, 16));
        Displays.animateNextTick(ctx(), owner, sign, Displays.centered(0, 0.5f, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0), 6);
        ctx().runLater(owner, 10, () -> {
            if (sign.isValid()) {
                Displays.animate(sign, Displays.centered(0, 0.8f, 0, 0.01f, 0.01f, 0.01f, 0, 0, 0), 5);
            }
        });
    }

    private static String rainbowTag(int index) {
        return RAINBOW_TAGS[Math.floorMod(index, RAINBOW_TAGS.length)];
    }

    /** Back on the ground after some air time. */
    private void landed(Player owner) {
        Candy current = candy;
        if (current != null && current.active()) {
            current.quake(owner);
        }
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        long now = ctx().currentTick();
        if (isInvincible()) {
            DamageType type = event.getDamageSource().getDamageType();
            if (type != DamageType.OUT_OF_WORLD && type != DamageType.OUTSIDE_BORDER && type != DamageType.GENERIC_KILL) {
                event.setCancelled(true);
                if (event.getCause() != DamageCause.FALL) {
                    Location body = Fx.body(player());
                    Vfx.cloud(ctx(), body, Particle.WAX_ON, 4, 0.4, 0.1, null, false);
                    Fx.sound(body, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 2.0f);
                }
                return;
            }
        }
        if (!Combat.isFall(event)) {
            return;
        }
        if (landing.absorb(event, now)) {
            Vfx.cloud(ctx(), player().getLocation().add(0, 0.1, 0), Particle.DUST, 6, 0.3, 0, PINK_DUST, false);
            return;
        }
        double reduction = Math.clamp(params().num("fall_reduction") / 100.0, 0.0, 1.0);
        event.setDamage(event.getDamage() * (1 - reduction));
    }

    /** The candy's stomper cannot strike with its hands (stomps are ability damage). */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isInvincible() || ctx().isAbilityDamage() || !player().equals(event.getDamager())) {
            return;
        }
        if (event.getCause() == DamageCause.ENTITY_ATTACK || event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK) {
            event.setCancelled(true);
        }
    }

    /** Allies catapulted by a pad land softly. */
    private final class FallGuard implements Listener {

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onFall(EntityDamageEvent event) {
            if (allyGuards.isEmpty() || !(event.getEntity() instanceof Player ally)) {
                return;
            }
            Long until = allyGuards.get(ally.getUniqueId());
            if (until == null) {
                return;
            }
            try {
                if (ctx().currentTick() > until) {
                    allyGuards.remove(ally.getUniqueId());
                } else if (Combat.isFall(event)) {
                    allyGuards.remove(ally.getUniqueId());
                    event.setCancelled(true);
                }
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Stomper fall guard failed", e);
            }
        }
    }

    // ================================================================== active: spring pad

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        Location center = padSpot(owner, Math.max(1, params().num("pad_range")));
        if (center == null) {
            return ActivationResult.fail("발판을 놓을 자리가 없습니다");
        }
        int max = Math.max(1, (int) Math.round(params().num("pad_max")));
        while (pads.size() >= max) {
            pads.pollFirst().fold();
        }
        Vector facing = Motion.horizontalFacing(owner.getLocation());
        Pad pad = new Pad(owner, center, facing);
        pads.addLast(pad);
        pad.spring();
        return ActivationResult.success();
    }

    /** The floor where the stomper aims: an aimed enemy's feet, the floor looked at, or the floor at the range. */
    private @Nullable Location padSpot(Player owner, double range) {
        World world = owner.getWorld();
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        LivingEntity aimed = ctx().findTarget(owner, range);
        RayTraceResult block = world.rayTraceBlocks(eye, look, range, FluidCollisionMode.NEVER, true);
        Location point;
        if (aimed != null && (block == null || block.getHitPosition().distance(eye.toVector())
                > aimed.getLocation().distance(eye))) {
            point = aimed.getLocation();
        } else if (block != null && block.getHitBlockFace() == BlockFace.UP) {
            point = block.getHitPosition().toLocation(world);
        } else if (block != null) {
            point = block.getHitPosition().toLocation(world).subtract(look.clone().multiply(0.5));
        } else {
            point = eye.clone().add(look.clone().multiply(range));
        }
        point = Displays.unrotated(point).add(0, 0.3, 0);
        Spots.snapToGround(world, point, 6);
        if (!Spots.hasGroundBelow(world, point) || !ctx().isInsideArena(point)
                || !Spots.isFree(world, point, 0.6, 1.0)) {
            return null;
        }
        return point;
    }

    /**
     * One spring pad: a pink plate on silver springs lying across the stomper's view. It shoots up as it is laid,
     * throwing the enemies on it into the air; afterwards the stomper and its allies who step onto it are catapulted.
     */
    private final class Pad {
        private final Player owner;
        private final Location center;
        private final Vector along;
        private final Vector across;
        private final float yaw;
        private final long endTick;
        private final Set<UUID> riding = new HashSet<>();
        private final List<BlockDisplay> parts = new ArrayList<>();
        private boolean folded;

        Pad(Player owner, Location center, Vector facing) {
            this.owner = owner;
            this.center = center;
            this.along = facing.clone();
            this.across = new Vector(-facing.getZ(), 0, facing.getX());
            this.yaw = (float) Math.toDegrees(Math.atan2(-facing.getX(), facing.getZ()));
            this.endTick = ctx().currentTick() + Math.max(20, params().ticks("pad_duration"));
        }

        private double width() {
            return Math.max(1, params().num("pad_width"));
        }

        /** True if {@code entity}'s feet are on the plate (or just over it). */
        boolean onPad(LivingEntity entity, double reachUp) {
            Location at = entity.getLocation();
            double dy = at.getY() - center.getY();
            if (dy < -PAD_REACH_DOWN || dy > reachUp) {
                return false;
            }
            Vector offset = at.toVector().subtract(center.toVector());
            double half = entity.getWidth() / 2;
            return Math.abs(offset.dot(across)) <= width() / 2 + half && Math.abs(offset.dot(along)) <= PAD_DEPTH / 2
                    + half;
        }

        /** Laid: the plate shoots up on its springs and settles; enemies standing there are thrown up. */
        void spring() {
            Fx.sound(center, Sound.BLOCK_PISTON_EXTEND, 1.0f, 1.4f);
            Fx.sound(center, Sound.ENTITY_SLIME_JUMP, 1.0f, 0.8f);
            Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 1.8f);
            if (reserve(4)) {
                parts.add(keep(Displays.block(ctx(), owner, center, PLATE.createBlockData(), plate(0), 0)));
                parts.add(keep(Displays.block(ctx(), owner, center, PLATE_RIM.createBlockData(), rim(0), 0)));
                parts.add(keep(Displays.block(ctx(), owner, center, SPRING.createBlockData(), spring(-1, 0), 0)));
                parts.add(keep(Displays.block(ctx(), owner, center, SPRING.createBlockData(), spring(1, 0), 0)));
                Displays.outline(parts.getFirst(), PINK);
            }
            double pop = Math.max(0, params().num("pad_pop"));
            ctx().runLater(owner, 1, () -> pose(pop, (int) POP_TICKS));
            ctx().runLater(owner, 1 + POP_TICKS, () -> pose(0, (int) SETTLE_TICKS));
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, width() + 1)) {
                if (!onPad(enemy, 1.2)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, params().num("pad_damage"));
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Motion.push(enemy, new Vector(0, Motion.launchSpeed(Math.max(0, params().num("pad_lift"))), 0),
                            MAX_SPEED);
                }
                if (!ctx().isConcealedFrom(enemy, owner)) {
                    Vfx.burst(ctx(), Fx.body(enemy), PALETTE, 0.6, Particle.WAX_ON, false);
                }
            }
            Vfx.line(ctx(), center.clone().add(across.clone().multiply(width() / 2)).add(0, 0.1, 0),
                    center.clone().subtract(across.clone().multiply(width() / 2)).add(0, 0.1, 0), 4, Particle.DUST,
                    PINK_DUST, false);
            Vfx.cloud(ctx(), center.clone().add(0, 0.2, 0), Particle.CLOUD, 8, 0.6, 0.05, null, false);
            // Those already standing on it when it is laid ride it until they step off (no surprise launch).
            for (Player rider : riders()) {
                if (onPad(rider, PAD_REACH_UP)) {
                    riding.add(rider.getUniqueId());
                }
            }
        }

        private List<Player> riders() {
            List<Player> riders = new ArrayList<>(ctx().alliesNear(owner, center, width() + 2));
            if (owner.getWorld().equals(center.getWorld())) {
                riders.add(owner);
            }
            return riders;
        }

        /** Every tick while it lies: whoever of the stomper's side steps onto it is catapulted. */
        void tick() {
            if (folded) {
                return;
            }
            if (ctx().currentTick() >= endTick || !owner.getWorld().equals(center.getWorld())) {
                fold();
                return;
            }
            for (Player rider : riders()) {
                UUID id = rider.getUniqueId();
                boolean on = onPad(rider, PAD_REACH_UP);
                if (on && !riding.contains(id)) {
                    riding.add(id);
                    launch(rider);
                } else if (!on) {
                    riding.remove(id);
                }
            }
            long left = endTick - ctx().currentTick();
            if (left % 10 == 0) {
                Vfx.dot(center.clone().add(across.clone().multiply(Fx.jitter((float) width() / 2))).add(0, 0.35, 0),
                        Particle.DUST, PINK_DUST, false);
            }
            if (left == 20) {
                Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BIT, 0.5f, 0.8f);
            }
        }

        /** "뿅!" — up it goes, and lands softly. */
        private void launch(Player rider) {
            double height = Math.max(0, params().num("pad_bounce"));
            Vector up = new Vector(0, Motion.launchSpeed(height), 0);
            if (rider.equals(owner)) {
                Vector lean = Inputs.isWalking(owner) ? Motion.horizontalFacing(owner.getLocation()).multiply(0.3)
                        : new Vector();
                owner.setVelocity(Motion.clamp(up.add(lean), MAX_SPEED));
                landing.arm(ctx().currentTick(), LANDING_GUARD);
                airTicks = 1;
            } else {
                rider.setVelocity(Motion.clamp(up, MAX_SPEED));
                allyGuards.put(rider.getUniqueId(), ctx().currentTick() + ALLY_GUARD);
            }
            rider.setFallDistance(0);
            pose(-0.15, 2);
            ctx().runLater(owner, 2, () -> pose(0.35, 2));
            ctx().runLater(owner, 4, () -> pose(0, 4));
            Location feet = rider.getLocation();
            Fx.sound(feet, Sound.ENTITY_SLIME_JUMP, 1.0f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(feet, Sound.BLOCK_NOTE_BLOCK_BIT, 0.9f, 2.0f);
            Vfx.ring(ctx(), feet.clone().add(0, 0.1, 0), 0.7, 12, 0, Particle.DUST, PINK_DUST, false);
            Vfx.cloud(ctx(), feet.clone().add(0, 0.2, 0), Particle.WAX_ON, 4, 0.3, 0.1, null, false);
        }

        /** The plate and its springs, the plate {@code lift} blocks over its rest. */
        private void pose(double lift, int ticks) {
            if (parts.size() < 4) {
                return;
            }
            Displays.animate(parts.get(0), plate(lift), ticks);
            Displays.animate(parts.get(1), rim(lift), ticks);
            Displays.animate(parts.get(2), spring(-1, lift), ticks);
            Displays.animate(parts.get(3), spring(1, lift), ticks);
        }

        private Transformation plate(double lift) {
            return part(0, 0.1f + (float) Math.max(0, lift), 0, (float) width(), 0.12f, (float) PAD_DEPTH);
        }

        private Transformation rim(double lift) {
            return part(0, 0.17f + (float) Math.max(0, lift), 0, (float) width() * 0.7f, 0.03f,
                    (float) PAD_DEPTH * 0.4f);
        }

        /** A silver spring under one end of the plate, stretched to reach it. */
        private Transformation spring(int side, double lift) {
            float height = 0.05f + (float) Math.max(0, lift);
            return part((float) (side * width() * 0.3), height / 2, 0, 0.16f, height, 0.16f);
        }

        /** A box in pad space (x across, z along the stomper's view) centered at the offset. */
        private Transformation part(float x, float y, float z, float sx, float sy, float sz) {
            Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
            Vector3f size = new Vector3f(sx, sy, sz);
            Vector3f corner = new Vector3f(x, y, z).sub(new Vector3f(size).mul(0.5f));
            return new Transformation(new Quaternionf(rotation).transform(corner), rotation, size, new Quaternionf());
        }

        /** The pad folds away. */
        void fold() {
            if (folded) {
                return;
            }
            folded = true;
            pads.remove(this);
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 5);
                }
            }
            parts.clear();
            Fx.sound(center, Sound.BLOCK_PISTON_CONTRACT, 0.6f, 1.6f);
            Vfx.cloud(ctx(), center.clone().add(0, 0.2, 0), Particle.POOF, 4, 0.5, 0.02, null, false);
        }
    }

    // ================================================================== ultimate: invincible candy

    @Override
    public ActivationResult onUltimate() {
        if (isInvincible()) {
            return ActivationResult.fail("이미 무적 사탕을 먹었습니다");
        }
        Candy started = new Candy(player());
        candy = started;
        started.begin();
        return ActivationResult.success();
    }

    /** The candy: invincible, fast, bouncy and loud for a few seconds; no hands, though. */
    private final class Candy {
        private final Player owner;
        private final long duration = Math.max(20, params().ticks("candy_duration"));
        private final long endTick;
        private final List<NamespacedKey> keys = new ArrayList<>();
        private final List<BlockDisplay> stars = new ArrayList<>();
        private final Map<UUID, Long> quaked = new HashMap<>();
        private boolean ended;
        private int note;
        private long nextNote;

        Candy(Player owner) {
            this.owner = owner;
            this.endTick = ctx().currentTick() + duration;
        }

        boolean active() {
            return !ended && ctx().currentTick() < endTick;
        }

        void begin() {
            double speed = Math.max(0, params().num("candy_speed")) / 100.0;
            double jump = Math.max(0, params().num("candy_jump")) / 100.0;
            if (speed > 0) {
                keys.add(ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1,
                        duration));
            }
            if (jump > 0) {
                keys.add(ctx().addModifier(owner, owner, Attribute.JUMP_STRENGTH, jump, Operation.MULTIPLY_SCALAR_1,
                        duration));
            }
            bite();
            owner.showTitle(Title.title(Text.mm(rainbow("무적 사탕!")), Text.mm("<gray>밟고 또 밟으세요</gray>")));
            if (reserve(2)) {
                Location at = Displays.unrotated(owner.getLocation().add(0, 1.0, 0));
                for (int i = 0; i < 2; i++) {
                    BlockDisplay star = keep(Displays.block(ctx(), owner, at, RAINBOW_GLASS[i * 3].createBlockData(),
                            Displays.transform(0, 0, 0, 0.22f, 0.22f, 0.22f, 45, 35, 0), duration + 2));
                    star.setTeleportDuration(1);
                    stars.add(star);
                }
            }
            Loop.repeat(ctx(), owner, 1, 1, duration, this::tick, () -> end(true));
        }

        /** Crunch: the candy appears in front of the mouth and bursts into rainbow. */
        private void bite() {
            Location eye = owner.getEyeLocation();
            Location mouth = Displays.unrotated(eye.clone().add(eye.getDirection().multiply(0.5)).subtract(0, 0.2, 0));
            if (reserve(1)) {
                BlockDisplay sweet = keep(Displays.block(ctx(), owner, mouth, CANDY.createBlockData(),
                        Displays.transform(0, 0, 0, 0.3f, 0.3f, 0.3f, 0, 0, 0), 8));
                Displays.animateNextTick(ctx(), owner, sweet, Displays.transform(0, 0, 0, 0.3f, 0.3f, 0.3f, 180, 0,
                        0), 5);
            }
            Fx.sound(eye, Sound.ENTITY_GENERIC_EAT, 1.0f, 1.2f);
            ctx().runLater(owner, 6, () -> {
                Location body = Fx.body(owner);
                for (Color color : RAINBOW) {
                    Vfx.cloud(ctx(), body, Particle.DUST, 4, 0.6, 0, new Particle.DustOptions(color, 1.3f), true);
                }
                Vfx.cloud(ctx(), body, Particle.FIREWORK, 12, 0.3, 0.2, null, true);
                Fx.sound(body, Sound.ENTITY_PLAYER_BURP, 0.8f, 1.6f);
                Fx.sound(body, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 2.0f);
            });
        }

        private boolean tick(long run) {
            if (ended) {
                return false;
            }
            long left = endTick - ctx().currentTick();
            music(left);
            Location feet = owner.getLocation();
            Color color = RAINBOW[(int) ((run / 2) % RAINBOW.length)];
            Particle.DustOptions dust = new Particle.DustOptions(color, 1.0f);
            if (!owner.isInvisible()) {
                double a = run * 0.5;
                for (int i = 0; i < 2; i++) {
                    double angle = a + i * Math.PI;
                    double y = (run % 10) / 10.0 * owner.getHeight();
                    Vfx.dot(feet.clone().add(Math.cos(angle) * 0.5, y, Math.sin(angle) * 0.5), Particle.DUST, dust,
                            false);
                }
                if (run % 2 == 0) {
                    Vfx.dot(feet.clone().add(0, 0.1, 0), Particle.DUST, new Particle.DustOptions(
                            RAINBOW[(int) ((run / 2 + 3) % RAINBOW.length)], 1.4f), false);
                }
                if (run % 5 == 0) {
                    Vfx.dot(Fx.body(owner).add(Fx.jitter(0.4f), Fx.jitter(0.5f), Fx.jitter(0.4f)), Particle.WAX_ON,
                            null, false);
                }
            }
            for (int i = 0; i < stars.size(); i++) {
                BlockDisplay star = stars.get(i);
                if (!star.isValid()) {
                    continue;
                }
                double angle = run * 0.35 + i * Math.PI;
                Displays.moveTo(star, Displays.unrotated(feet.clone().add(Math.cos(angle) * 0.8, 1.1, Math.sin(angle)
                        * 0.8)));
                if (run % 3 == 0) {
                    star.setBlock(RAINBOW_GLASS[(int) ((run / 3 + i * 3) % RAINBOW_GLASS.length)].createBlockData());
                }
            }
            if (run % 10 == 0 && !ctx().hasShownPrompt(owner)) {
                owner.sendActionBar(Text.mm(rainbow("무적!") + " <gray>" + Fx.seconds(left) + "초</gray>"));
            }
            return true;
        }

        /** A jaunty tune that speeds up in the last second. */
        private void music(long left) {
            long now = ctx().currentTick();
            if (now < nextNote) {
                return;
            }
            boolean fast = left <= 20;
            nextNote = now + (fast ? TUNE_FAST : TUNE_STEP);
            float pitch = Math.min(2.0f, TUNE[note % TUNE.length] * (fast ? 1.12f : 1.0f));
            Location at = owner.getLocation();
            Fx.sound(at, note % 2 == 0 ? Sound.BLOCK_NOTE_BLOCK_BIT : Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, pitch);
            if (note % 4 == 0) {
                Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, pitch * 0.5f);
            }
            if (note % 2 == 1) {
                Fx.sound(at, Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.4f);
            }
            note++;
        }

        /** A landing shakes the ground around the stomper (each enemy at most once a second). */
        void quake(Player owner) {
            double radius = Math.max(0, params().num("quake_radius"));
            long now = ctx().currentTick();
            long repeat = Math.max(1, params().ticks("quake_cooldown"));
            Location feet = owner.getLocation();
            for (LivingEntity enemy : ctx().enemiesNear(owner, feet, radius)) {
                Long last = quaked.get(enemy.getUniqueId());
                if (last != null && now - last < repeat) {
                    continue;
                }
                quaked.put(enemy.getUniqueId(), now);
                Combat.hit(ctx(), owner, enemy, params().num("quake_damage"));
            }
            Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.3, Math.max(0.5, radius), 5, Particle.DUST,
                    new Particle.DustOptions(RAINBOW[(int) (now % RAINBOW.length)], 1.2f), false);
            Vfx.cloud(ctx(), feet.clone().add(0, 0.1, 0), Particle.WAX_ON, 5, radius * 0.3, 0.05, null, false);
            Fx.sound(feet, Sound.ITEM_MACE_SMASH_GROUND, 0.7f, 1.5f + Fx.jitter(0.1f));
            Fx.sound(feet, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.8f, 0.8f);
        }

        void end(boolean visible) {
            if (ended) {
                return;
            }
            ended = true;
            for (NamespacedKey key : keys) {
                ctx().removeModifier(owner, key);
            }
            keys.clear();
            for (BlockDisplay star : stars) {
                if (star.isValid()) {
                    if (visible) {
                        Displays.shrinkAway(ctx(), owner, star, 4);
                    } else {
                        star.remove();
                    }
                }
            }
            stars.clear();
            if (visible) {
                Fx.sound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 0.6f);
                Vfx.cloud(ctx(), Fx.body(owner), Particle.POOF, 6, 0.4, 0.02, null, false);
            }
            if (candy == this) {
                candy = null;
            }
        }
    }

    private static String rainbow(String text) {
        StringBuilder out = new StringBuilder("<b>");
        int index = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                out.append(c);
                continue;
            }
            String tag = rainbowTag(index++);
            out.append('<').append(tag).append('>').append(c).append("</").append(tag).append('>');
        }
        return out.append("</b>").toString();
    }

    // ================================================================== helpers

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }
}
