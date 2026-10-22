package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 양봉가 — keeps bees. Getting hit sometimes shakes a drop of honey loose (absorption), the active sends a swarm that
 * chases the aimed enemy around walls and stings it, and the ultimate calls a giant queen bee whose waves of bees sting
 * every enemy around while her honey heals the keeper's team.
 *
 * <p>Bees are tiny billboarded bee items that steer themselves (seek, a little wobble, deflection off blocks); the
 * swarm leader picks a detour waypoint whenever a wall blocks the way. Enemies standing in water cannot be stung.
 * Past the display budget extra bees are drawn with particles only, so they still work in big fights.
 *
 * <p>Look: honey amber, cream and black. Honey drops fall as glowing honey cubes and splash on landing; the swarm
 * pours out of a beehive in the keeper's hand and leaves a faint yellow buzz trail; stings flash and drip; the queen
 * is a big block-built bee with a gold crown and beating glass wings, raining honey on her allies.
 */
public final class BeekeeperAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("beekeeper", "양봉가", Material.HONEYCOMB)
            .color(TextColor.color(0xF5B21B))
            .difficulty(1)
            .passive("꿀단지",
                    "적에게 공격받으면 {honey_chance}% 확률로 꿀 방울이 떨어져 흡수 체력 {honey_absorption}을 얻습니다"
                            + "(꿀로 최대 {honey_max}, {honey_duration}초 유지, {honey_cooldown}초에 한 번).")
            .active("벌떼",
                    "조준한 적({swarm_range}블록 이내)에게 벌 {swarm_bees}마리를 보냅니다. 벌떼는 벽을 돌아 쫓아가 "
                            + "{sting_duration}초간 {sting_interval}초마다 {sting_damage} 피해를 주고 독을 겁니다. "
                            + "물속에 있는 적은 쏘지 못합니다.",
                    11)
            .ultimate("여왕벌",
                    "거대한 여왕벌을 {ult_duration}초간 거느립니다. {ult_wave_interval}초마다 반경 {ult_radius}블록의 "
                            + "모든 적에게 벌을 {ult_bees}마리씩 보내 마리당 {ult_sting_damage} 피해와 독을 주고, "
                            + "떨어지는 꿀이 반경 {ult_heal_radius}블록의 나와 아군을 초당 {ult_heal} 회복시킵니다.")
            .param("honey_chance", 30)
            .param("honey_absorption", 2)
            .param("honey_max", 6)
            .param("honey_duration", 10)
            .param("honey_cooldown", 2)
            .param("swarm_range", 20)
            .param("swarm_bees", 7)
            .param("swarm_chase", 3)
            .param("sting_duration", 3)
            .param("sting_interval", 0.5)
            .param("sting_damage", 0.5)
            .param("sting_poison", 1.5)
            .param("ult_duration", 6)
            .param("ult_radius", 12)
            .param("ult_wave_interval", 1.5)
            .param("ult_bees", 3)
            .param("ult_sting_damage", 0.6)
            .param("ult_poison", 1.5)
            .param("ult_heal", 1)
            .param("ult_heal_radius", 8)
            .build();

    private static final String COLOR_TAG = "#F5B21B";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look: cream gold, honey amber, dark brown
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF1B8, 0xF5B21B, 0x3B2A10);
    private static final Particle.DustTransition HONEY_GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition HONEY_FADE = PALETTE.fade(0.9f);
    private static final Particle.DustOptions BUZZ = new Particle.DustOptions(Color.fromRGB(0xFFD23F), 0.45f);
    private static final Particle.DustOptions BEE_BODY = new Particle.DustOptions(Color.fromRGB(0xFFC81E), 0.7f);
    private static final Particle.DustOptions BEE_STRIPE = new Particle.DustOptions(Color.fromRGB(0x2A1C0C), 0.5f);

    private static final Material BEE_ITEM = Material.BEE_SPAWN_EGG;
    private static final Material HONEY_BLOCK = Material.HONEY_BLOCK;
    private static final Material HIVE_BLOCK = Material.BEEHIVE;

    /** Upper bound of display entities alive at once for one beekeeper; extra bees are drawn with particles. */
    private static final int DISPLAY_BUDGET = 24;
    private static final float BEE_SCALE = 0.36f;

    // ------------------------------------------------------------------ passive: honey drop
    private static final int HONEY_FALL_TICKS = 5;
    private static final double HONEY_DROP_HEIGHT = 1.1;
    private static final long HONEY_DRIP_PERIOD = 9;

    // ------------------------------------------------------------------ active: swarm
    /** At most this many swarms at once (practice mode without cooldowns); the oldest flies home early. */
    private static final int MAX_SWARMS = 2;
    private static final double CHASE_SPEED = 0.8;
    private static final double CHASE_ACCEL = 0.2;
    private static final double STING_SPEED = 1.1;
    private static final double STING_ACCEL = 0.4;
    private static final double RETURN_SPEED = 0.9;
    /** The swarm starts stinging once its middle is this close to the target's body. */
    private static final double STING_REACH = 1.8;
    /** A stinging swarm whose target got this far away chases it again. */
    private static final double LOSE_REACH = 4.0;
    /** A lost target is replaced by the nearest visible enemy this close to the swarm. */
    private static final double RETARGET_RADIUS = 6;
    private static final long RETARGET_CHASE_TICKS = 40;
    private static final long RETURN_MAX_TICKS = 40;
    private static final double ORBIT_RADIUS = 0.65;
    private static final long WAYPOINT_PERIOD = 4;
    private static final long LOCK_ON_TICKS = 5;
    private static final long BUZZ_SOUND_PERIOD = 18;
    private static final long HIVE_TICKS = 12;
    /** Vanilla Poison I hurts once per this many ticks, counted on the effect's remaining duration. */
    private static final int POISON_PERIOD = 25;
    private static final int[] DEFLECT_DEGREES = {50, -50, 100, -100, 150, -150};
    private static final double[] DETOUR_SIDE = {2, -2, 4, -4, 6, -6};
    private static final double[] DETOUR_UP = {0, 2.5, 4.5};

    // ------------------------------------------------------------------ ultimate: queen bee
    private static final float QUEEN_SCALE = 1.5f;
    private static final double QUEEN_HEIGHT = 3.0;
    private static final double QUEEN_BACK = 1.1;
    private static final double QUEEN_FOLLOW = 0.3;
    private static final int QUEEN_GROW_TICKS = 8;
    private static final long QUEEN_FIRST_WAVE = 10;
    private static final long QUEEN_LEAVE_TICKS = 12;
    private static final long STRIKE_MAX_AGE = 40;
    private static final double STRIKE_SPEED = 0.95;
    private static final double STRIKE_ACCEL = 0.3;
    private static final double STRIKE_REACH = 0.45;
    private static final long FLAP_PERIOD = 2;
    private static final float WING_UP = 55f;
    private static final float WING_DOWN = 12f;
    private static final float LUNGE_PITCH = 18f;
    private static final float LEAVE_PITCH = -30f;
    private static final int LUNGE_TICKS = 6;
    /** Share of the remaining turn towards the keeper's view the queen makes per wing beat. */
    private static final double QUEEN_TURN = 0.5;

    private final List<Display> displays = new ArrayList<>();
    private final List<Swarm> swarms = new ArrayList<>();
    private long lastHoney = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
    private long honeyUntil = Long.MIN_VALUE / 2;
    private @Nullable NamespacedKey honeyKey;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        // Tasks, displays and the honey modifier are owner-scoped: the context releases them.
        swarms.clear();
        displays.clear();
        honeyKey = null;
    }

    @Override
    public void onTick(long tick) {
        if (honeyKey == null) {
            return;
        }
        Player owner = player();
        long now = ctx().currentTick();
        if (now >= honeyUntil || owner.getAbsorptionAmount() <= 0) {
            endHoney(now >= honeyUntil);
            return;
        }
        if (tick % HONEY_DRIP_PERIOD == 0) {
            // Visible passive: honey keeps dripping off the keeper while it lasts.
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location at = owner.getLocation().add(random.nextDouble(-0.3, 0.3),
                    owner.getHeight() * random.nextDouble(0.35, 0.9), random.nextDouble(-0.3, 0.3));
            Vfx.dot(at, Particle.DRIPPING_HONEY, null, false);
        }
    }

    // ------------------------------------------------------------------ passive: honey pot

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        // getDamage, not getFinalDamage: a blow soaked up by honey (absorption) still shakes more honey loose.
        if (event.isCancelled() || event.getDamage() <= 0
                || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Player owner = player();
        Player attacker = ctx().resolveAttacker(byEntity.getDamager());
        if (attacker == null || attacker.equals(owner) || !ctx().isEnemy(owner, attacker)) {
            return;
        }
        long now = ctx().currentTick();
        if (now - lastHoney < params().ticks("honey_cooldown")) {
            return;
        }
        double chance = Math.clamp(params().num("honey_chance") / 100.0, 0.0, 1.0);
        if (chance <= 0 || ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        lastHoney = now;
        dropHoney(owner);
    }

    /** A glowing drop of honey forms over the keeper's head and falls into it; the absorption lands with it. */
    private void dropHoney(Player owner) {
        Location head = owner.getLocation().add(0, owner.getHeight() + HONEY_DROP_HEIGHT, 0);
        head.setYaw(0);
        head.setPitch(0);
        BlockDisplay drop = spawnBlock(owner, head, HONEY_BLOCK.createBlockData(), Displays.scaled(0.05f),
                HONEY_FALL_TICKS + 8);
        if (drop != null) {
            drop.setTeleportDuration(1);
            Displays.animateNextTick(ctx(), owner, drop, Displays.transform(0, 0, 0, 0.26f, 0.34f, 0.26f, 0, 0, 0),
                    2);
        }
        Fx.sound(head, Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.6f, 1.4f + Fx.jitter(0.1f));
        Fx.sound(head, Sound.BLOCK_BEEHIVE_DRIP, 0.9f, 1.0f + Fx.jitter(0.1f));
        Loop.repeat(ctx(), owner, 1, 1, HONEY_FALL_TICKS, run -> {
            double t = (double) (run + 1) / HONEY_FALL_TICKS;
            Location at = owner.getLocation().add(0, owner.getHeight() + HONEY_DROP_HEIGHT * (1 - t * t), 0);
            at.setYaw(0);
            at.setPitch(0);
            if (drop != null && drop.isValid()) {
                Displays.moveTo(drop, at);
            }
            Vfx.dot(at.clone().add(0, 0.2, 0), Particle.FALLING_HONEY, null, false);
            return true;
        }, () -> {
            if (drop != null && drop.isValid()) {
                Displays.shrinkAway(ctx(), owner, drop, 3);
            }
            grantHoney(owner);
        });
    }

    private void grantHoney(Player owner) {
        double max = Math.max(0, params().num("honey_max"));
        double amount = Math.max(0, params().num("honey_absorption"));
        long duration = params().ticks("honey_duration");
        if (max <= 0 || amount <= 0 || duration <= 0) {
            return;
        }
        if (honeyKey == null) {
            honeyKey = ctx().addModifier(owner, owner, Attribute.MAX_ABSORPTION, max, Operation.ADD_NUMBER, 0);
        }
        owner.setAbsorptionAmount(owner.getAbsorptionAmount() + amount);
        honeyUntil = ctx().currentTick() + duration;

        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        World world = chest.getWorld();
        world.spawnParticle(Particle.LANDING_HONEY, chest.clone().add(0, owner.getHeight() * 0.4, 0), Vfx.n(ctx(), 6),
                0.25, 0.05, 0.25, 0);
        Vfx.ring(ctx(), chest, 0.55, 12, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST_COLOR_TRANSITION,
                HONEY_GLOW, false);
        Vfx.ring(ctx(), chest.clone().subtract(0, 0.45, 0), 0.45, 10, 0, Particle.DUST, PALETTE.dust(0.8f), false);
        Vfx.cloud(ctx(), chest, Particle.WAX_ON, 4, 0.3, 0.02, null, false);
        Fx.sound(chest, Sound.ITEM_HONEY_BOTTLE_DRINK, 0.7f, 1.3f + Fx.jitter(0.1f));
        Fx.sound(chest, Sound.BLOCK_HONEY_BLOCK_FALL, 0.8f, 1.2f + Fx.jitter(0.1f));
        String held = AbilityInfo.formatNumber(Math.min(owner.getAbsorptionAmount(), max));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⬢ 꿀 방울</" + COLOR_TAG + "> <gray>흡수 <white>+"
                + AbilityInfo.formatNumber(amount) + "</white> (" + held + ")</gray>"));
    }

    /** The honey wore off (or was used up): the extra absorption capacity goes away. */
    private void endHoney(boolean expired) {
        Player owner = player();
        NamespacedKey key = honeyKey;
        honeyKey = null;
        if (key != null) {
            ctx().removeModifier(owner, key);
            // Re-set to clamp any leftover absorption to the (now lower) maximum.
            owner.setAbsorptionAmount(owner.getAbsorptionAmount());
        }
        if (expired) {
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
            Vfx.cloud(ctx(), chest, Particle.FALLING_HONEY, 4, 0.3, 0, null, false);
            Fx.sound(chest, Sound.BLOCK_HONEY_BLOCK_STEP, 0.5f, 0.8f + Fx.jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ active: swarm

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        LivingEntity target = ctx().findTarget(owner, params().num("swarm_range"));
        if (target == null || !visible(owner, target)) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        int count = Math.max(1, params().integer("swarm_bees"));
        while (swarms.size() >= MAX_SWARMS) {
            swarms.removeFirst().recall();
        }
        Swarm swarm = new Swarm(target, count);
        swarms.add(swarm);
        swarm.launch();
        return ActivationResult.success();
    }

    /** Where bees leave the keeper: the hand in front of the chest. */
    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.add(eye.getDirection().multiply(0.6)).subtract(0, 0.35, 0);
    }

    /**
     * One swarm of the active: pours out of a beehive, chases the target (detouring round walls), stings it in a
     * buzzing ring for a while and flies home.
     */
    private final class Swarm implements LongPredicate {

        private final List<Bee> bees = new ArrayList<>();
        private final int count;
        private final long stingTicks;
        private final long stingInterval;
        private final double stingDamage;
        private final long poisonTicks;
        private LivingEntity target;
        private Phase phase = Phase.CHASE;
        private long chaseLeft;
        private long stingLeft;
        private long nextSting;
        private long returnTicks;
        private int stingTurn;
        private boolean stung;
        private @Nullable Location waypoint;
        private long waypointAge = WAYPOINT_PERIOD;

        Swarm(LivingEntity target, int count) {
            this.target = target;
            this.count = count;
            this.chaseLeft = Math.max(1, params().ticks("swarm_chase"));
            this.stingTicks = Math.max(1, params().ticks("sting_duration"));
            this.stingInterval = Math.max(1, params().ticks("sting_interval"));
            this.stingDamage = Math.max(0, params().num("sting_damage"));
            this.poisonTicks = params().ticks("sting_poison");
        }

        void launch() {
            Player owner = player();
            Location hand = hand(owner);
            Vector forward = owner.getEyeLocation().getDirection();
            long lifetime = chaseLeft + stingTicks + RETARGET_CHASE_TICKS * 2 + RETURN_MAX_TICKS + 20;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < count; i++) {
                Vector spray = forward.clone().multiply(0.35).add(randomUnit(random).multiply(0.22));
                bees.add(new Bee(Vfx.scatter(hand, 0.2), spray, lifetime));
            }
            hive(owner, hand, forward);
            lockOn(owner, target);
            Fx.sound(hand, Sound.BLOCK_BEEHIVE_EXIT, 1.0f, 1.0f + Fx.jitter(0.08f));
            Fx.sound(hand, Sound.ENTITY_BEE_LOOP_AGGRESSIVE, 0.9f, 1.1f + Fx.jitter(0.1f));
            Fx.sound(hand, Sound.BLOCK_BEEHIVE_WORK, 0.8f, 1.2f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), owner, 1, 1, lifetime, this, this::finish);
        }

        /** A beehive pops up in the keeper's hand, its entrance towards the target, and the bees pour out. */
        private void hive(Player owner, Location hand, Vector forward) {
            BlockData data = HIVE_BLOCK.createBlockData();
            if (data instanceof Directional directional) {
                directional.setFacing(BlockFace.SOUTH);
            }
            Location at = hand.clone();
            at.setYaw(0);
            at.setPitch(0);
            float yaw = yawOf(forward);
            BlockDisplay hive = spawnBlock(owner, at, data, Displays.transform(0, 0, 0, 0.1f, 0.1f, 0.1f, yaw, 0, 0),
                    HIVE_TICKS + 6);
            if (hive != null) {
                Displays.animateNextTick(ctx(), owner, hive, Displays.transform(0, 0, 0, 0.5f, 0.5f, 0.5f, yaw, 0, 0),
                        3);
                ctx().runLater(owner, HIVE_TICKS - 4, () -> {
                    if (hive.isValid()) {
                        Displays.shrinkAway(ctx(), owner, hive, 4);
                    }
                });
            }
            World world = hand.getWorld();
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, hand, Vfx.n(ctx(), 10), 0.2, 0.2, 0.2, 0,
                    HONEY_GLOW);
            world.spawnParticle(Particle.WAX_ON, hand, Vfx.n(ctx(), 6), 0.25, 0.25, 0.25, 0.05);
            hexRing(hand, forward, 0.45, Particle.DUST, PALETTE.dust(0.8f), false);
        }

        /** The lock-on: a ring of honey closing in around the target. */
        private void lockOn(Player owner, LivingEntity aimed) {
            Loop.repeat(ctx(), owner, 0, 1, LOCK_ON_TICKS, run -> {
                if (!aimed.isValid()) {
                    return false;
                }
                double radius = 1.3 - 0.8 * (run + 1) / LOCK_ON_TICKS;
                Vfx.ring(ctx(), Fx.body(aimed), radius, 12, run * 0.5, Particle.DUST_COLOR_TRANSITION, HONEY_GLOW,
                        false);
                return true;
            });
        }

        @Override
        public boolean test(long tick) {
            bees.removeIf(bee -> bee.gone);
            if (bees.isEmpty()) {
                return false;
            }
            Location center = centroid(bees);
            switch (phase) {
                case CHASE -> chase(center);
                case STING -> sting(center, tick);
                case RETURN -> {
                    if (fly(center)) {
                        return false;
                    }
                }
            }
            for (Bee bee : bees) {
                bee.draw(tick);
            }
            if (tick % BUZZ_SOUND_PERIOD == 0) {
                Fx.sound(center, Sound.ENTITY_BEE_LOOP_AGGRESSIVE, 0.7f, 1.0f + Fx.jitter(0.12f));
            }
            return true;
        }

        private void chase(Location center) {
            if (!holdsTarget() && !retarget(center)) {
                recall();
                return;
            }
            Location body = Fx.body(target);
            if (center.distance(body) <= STING_REACH) {
                phase = Phase.STING;
                stingLeft = stingTicks;
                nextSting = 1;
                return;
            }
            if (--chaseLeft < 0) {
                recall();
                return;
            }
            Location goal = route(center, body);
            for (Bee bee : bees) {
                bee.steer(goal.clone().add(bee.offset.clone().multiply(0.8)), CHASE_SPEED, CHASE_ACCEL);
            }
        }

        private void sting(Location center, long tick) {
            if (!holdsTarget()) {
                if (retarget(center)) {
                    phase = Phase.CHASE;
                    chaseLeft = RETARGET_CHASE_TICKS;
                } else {
                    recall();
                }
                return;
            }
            Location body = Fx.body(target);
            if (center.distance(body) > LOSE_REACH) {
                // The target outran the swarm: the bees take up the chase again.
                phase = Phase.CHASE;
                chaseLeft = Math.max(chaseLeft, RETARGET_CHASE_TICKS);
                return;
            }
            boolean submerged = target.isInWater();
            double hover = submerged ? target.getHeight() * 0.5 + 1.0 : 0;
            int i = 0;
            for (Bee bee : bees) {
                double angle = bee.phase + tick * (i % 2 == 0 ? 0.32 : -0.27);
                double radius = ORBIT_RADIUS + target.getWidth() * 0.4;
                Location goal = body.clone().add(Math.cos(angle) * radius,
                        hover + Math.sin(tick * 0.3 + bee.phase) * target.getHeight() * 0.3,
                        Math.sin(angle) * radius);
                bee.steer(goal, STING_SPEED, STING_ACCEL);
                i++;
            }
            if (--nextSting <= 0) {
                nextSting = stingInterval;
                if (!submerged) {
                    stingOnce(body);
                } else if (tick % 4 == 0) {
                    Vfx.cloud(ctx(), target.getLocation().add(0, target.getHeight() + 0.6, 0), Particle.SPLASH, 6,
                            0.4, 0.05, null, false);
                }
            }
            if (--stingLeft <= 0) {
                recall();
            }
        }

        /** One bee darts in and stings: credited damage and a refreshed poison. */
        private void stingOnce(Location body) {
            Player owner = player();
            Bee bee = bees.get(stingTurn++ % bees.size());
            Location from = bee.pos.clone();
            bee.dart(body);
            Vfx.trail(from, body, PALETTE.body(), 3, false);
            Combat.hit(ctx(), owner, target, stingDamage);
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return;
            }
            poison(target, poisonTicks);
            World world = body.getWorld();
            world.spawnParticle(Particle.CRIT, body, Vfx.n(ctx(), 3), 0.15, 0.2, 0.15, 0.15);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 3), 0.2, 0.25, 0.2, 0,
                    HONEY_FADE);
            Fx.sound(body, Sound.ENTITY_BEE_STING, 0.8f, 1.05f + Fx.jitter(0.15f));
            if (!stung) {
                stung = true;
                Vfx.ring(ctx(), target.getLocation().add(0, 0.1, 0), 0.7, 14, 0, Particle.DUST_COLOR_TRANSITION,
                        HONEY_GLOW, false);
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⬢ 벌떼</" + COLOR_TAG + "> <gray>→</gray> <white>"
                        + "<name></white>", Placeholder.component("name", target.name())));
            }
        }

        /** Flies home and returns true once every bee is back (or the time is up). */
        private boolean fly(Location center) {
            Player owner = player();
            Location home = hand(owner);
            for (Bee bee : bees) {
                if (bee.pos.distanceSquared(home) < 0.8 * 0.8 || !bee.pos.getWorld().equals(home.getWorld())) {
                    bee.vanish(true);
                } else {
                    bee.steer(home, RETURN_SPEED, CHASE_ACCEL);
                }
            }
            if (++returnTicks >= RETURN_MAX_TICKS) {
                for (Bee bee : bees) {
                    bee.vanish(true);
                }
                return true;
            }
            if (returnTicks == 1 && center.getWorld().equals(home.getWorld()) && center.distance(home) > 3) {
                Fx.sound(center, Sound.ENTITY_BEE_POLLINATE, 0.6f, 1.2f + Fx.jitter(0.1f));
            }
            bees.removeIf(bee -> bee.gone);
            return bees.isEmpty();
        }

        /** The target is still a visible, live enemy in this world. */
        private boolean holdsTarget() {
            Player owner = player();
            return Combat.isTargetable(ctx(), owner, target) && target.getWorld().equals(owner.getWorld())
                    && visible(owner, target);
        }

        /** Picks the nearest visible enemy around the swarm. */
        private boolean retarget(Location center) {
            Player owner = player();
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, RETARGET_RADIUS)) {
                if (visible(owner, enemy)) {
                    target = enemy;
                    stung = false;
                    waypoint = null;
                    waypointAge = WAYPOINT_PERIOD;
                    return true;
                }
            }
            return false;
        }

        /**
         * Where the swarm heads: straight at the target if nothing is in the way, otherwise the shortest detour
         * (sideways and/or over the top) from which the target can be seen.
         */
        private Location route(Location center, Location body) {
            if (Spots.clearPath(center, body)) {
                waypoint = null;
                return body;
            }
            if (waypoint != null && waypointAge++ < WAYPOINT_PERIOD) {
                return waypoint;
            }
            waypointAge = 0;
            Vector toward = body.toVector().subtract(center.toVector());
            Vector flat = Motion.horizontal(toward);
            if (flat.lengthSquared() < 1.0E-6) {
                flat = new Vector(1, 0, 0);
            }
            Vector side = new Vector(-flat.getZ(), 0, flat.getX());
            Location best = null;
            double bestCost = Double.MAX_VALUE;
            for (double up : DETOUR_UP) {
                for (double sideways : DETOUR_SIDE) {
                    Location candidate = center.clone().add(flat.clone().multiply(1.5))
                            .add(side.clone().multiply(sideways)).add(0, up, 0);
                    if (!isAir(candidate) || !Spots.clearPath(center, candidate)
                            || !Spots.clearPath(candidate, body)) {
                        continue;
                    }
                    double cost = center.distance(candidate) + candidate.distance(body);
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = candidate;
                    }
                }
            }
            if (best == null) {
                // No clear detour: climb, which usually finds a way over.
                Location climb = center.clone().add(0, 2, 0);
                best = isAir(climb) ? climb.add(flat.multiply(0.5)) : body;
            }
            waypoint = best;
            return best;
        }

        /** Sends the swarm home now. */
        void recall() {
            if (phase != Phase.RETURN) {
                phase = Phase.RETURN;
                returnTicks = 0;
            }
        }

        /** The loop ended (all bees home or the time ran out): nothing is left behind. */
        private void finish() {
            for (Bee bee : bees) {
                bee.vanish(false);
            }
            bees.clear();
            swarms.remove(this);
        }
    }

    private enum Phase { CHASE, STING, RETURN }

    // ------------------------------------------------------------------ bees

    /**
     * One bee: a tiny billboarded bee item that steers itself. It deflects off blocks instead of flying through them,
     * and is drawn with particles alone when the display budget is used up.
     */
    private final class Bee {

        private final @Nullable ItemDisplay display;
        private final Location pos;
        private final Vector vel;
        private final Vector offset;
        private final double phase;
        private boolean gone;

        Bee(Location start, Vector initial, long lifetime) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            this.pos = start.clone();
            this.pos.setYaw(0);
            this.pos.setPitch(0);
            this.vel = initial.clone();
            this.offset = randomUnit(random).multiply(ORBIT_RADIUS);
            this.phase = random.nextDouble(TAU);
            this.display = spawnBee(pos, lifetime);
        }

        /** Seeks {@code goal} at up to {@code speed} blocks per tick, turning by at most {@code accel} per tick. */
        void steer(Location goal, double speed, double accel) {
            if (gone || !goal.getWorld().equals(pos.getWorld())) {
                return;
            }
            Vector desired = goal.toVector().subtract(pos.toVector());
            double distance = desired.length();
            if (distance > 1.0E-6) {
                // Slow down on arrival so bees settle into place instead of overshooting.
                desired.multiply(Math.min(speed, distance * 0.6 + 0.05) / distance);
            }
            Vector turn = Motion.clamp(desired.subtract(vel), accel);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            vel.add(turn).add(new Vector(random.nextDouble(-0.04, 0.04), random.nextDouble(-0.04, 0.04),
                    random.nextDouble(-0.04, 0.04)));
            Motion.clamp(vel, speed);
            move();
        }

        /** Moves by the velocity, deflecting off (never through) solid blocks. */
        private void move() {
            Location next = pos.clone().add(vel);
            if (!isAir(next) || !Spots.clearPath(pos, next)) {
                Vector detour = deflect();
                if (detour == null) {
                    vel.multiply(0.2);
                    return;
                }
                vel.copy(detour);
                next = pos.clone().add(vel);
            }
            pos.setX(next.getX());
            pos.setY(next.getY());
            pos.setZ(next.getZ());
        }

        private @Nullable Vector deflect() {
            double speed = Math.max(0.15, vel.length());
            Vector base = vel.lengthSquared() < 1.0E-6 ? new Vector(0, 1, 0) : vel.clone().normalize();
            List<Vector> candidates = new ArrayList<>();
            candidates.add(base.clone().setY(base.getY() + 0.9).normalize());
            for (int degrees : DEFLECT_DEGREES) {
                candidates.add(base.clone().rotateAroundY(Math.toRadians(degrees)));
            }
            candidates.add(new Vector(0, 1, 0));
            for (Vector candidate : candidates) {
                Vector step = candidate.multiply(speed);
                Location next = pos.clone().add(step);
                if (isAir(next) && Spots.clearPath(pos, next)) {
                    return step;
                }
            }
            return null;
        }

        /** Darts into {@code body} for a sting, then bounces back out. */
        void dart(Location body) {
            if (!body.getWorld().equals(pos.getWorld())) {
                return;
            }
            Vector back = pos.toVector().subtract(body.toVector());
            pos.setX(body.getX() + back.getX() * 0.25);
            pos.setY(body.getY() + back.getY() * 0.25);
            pos.setZ(body.getZ() + back.getZ() * 0.25);
            vel.copy(back.lengthSquared() < 1.0E-6 ? new Vector(0, 0.2, 0) : back.normalize().multiply(0.35));
        }

        void draw(long tick) {
            if (gone) {
                return;
            }
            if (display != null && display.isValid()) {
                Displays.moveTo(display, pos);
            } else {
                // Over the display budget: a particle bee, a yellow body with a dark band.
                Vfx.dot(pos, Particle.DUST, BEE_BODY, false);
                Vfx.dot(pos.clone().subtract(vel.clone().multiply(0.3)), Particle.DUST, BEE_STRIPE, false);
            }
            if ((tick + (long) (phase * 10)) % 2 == 0) {
                Vfx.dot(pos.clone().subtract(vel.clone().multiply(0.8)), Particle.DUST, BUZZ, false);
            }
        }

        /** Removes the bee, with a small puff of pollen when {@code poof}. */
        void vanish(boolean poof) {
            if (gone) {
                return;
            }
            gone = true;
            if (display != null && display.isValid()) {
                display.remove();
            }
            if (poof) {
                Vfx.cloud(ctx(), pos, Particle.DUST, 2, 0.08, 0, BUZZ, false);
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: queen bee

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = Math.max(1, params().num("ult_radius"));
        if (visibleEnemies(owner, owner.getLocation(), radius).isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        new Queen(owner).summon();
        return ActivationResult.success();
    }

    /** One part of the queen: its block and its pose relative to the queen's anchor (facing +z), at scale 1. */
    private record QueenPart(BlockDisplay display, float x, float y, float z, float w, float h, float l, float pitch,
                             int wing) {
    }

    /**
     * The queen bee: a big block-built bee with a gold crown that hovers behind the keeper, beats her glass wings,
     * sends a wave of bees at every enemy around and rains honey on the keeper's team.
     */
    private final class Queen implements LongPredicate {

        private final List<QueenPart> parts = new ArrayList<>();
        private final List<Strike> strikes = new ArrayList<>();
        private final long duration;
        private final double radius;
        private final long waveInterval;
        private final int beesPerEnemy;
        private final double stingDamage;
        private final long poisonTicks;
        private final double heal;
        private final double healRadius;
        /** Where the queen's body is centred: a plain position, her turn lives in {@link #yaw}/{@link #pitch}. */
        private Location anchor;
        private float yaw;
        private float pitch;
        private float wings = WING_UP;
        private long nextWave = QUEEN_FIRST_WAVE;
        private long leaveStart = -1;
        private int lunge;

        Queen(Player owner) {
            this.duration = Math.max(20, params().ticks("ult_duration"));
            this.radius = Math.max(1, params().num("ult_radius"));
            this.waveInterval = Math.max(5, params().ticks("ult_wave_interval"));
            this.beesPerEnemy = Math.max(1, params().integer("ult_bees"));
            this.stingDamage = Math.max(0, params().num("ult_sting_damage"));
            this.poisonTicks = params().ticks("ult_poison");
            this.heal = Math.max(0, params().num("ult_heal"));
            this.healRadius = Math.max(0, params().num("ult_heal_radius"));
            this.anchor = Displays.unrotated(owner.getLocation().add(0, owner.getHeight() + 0.3, 0));
            this.yaw = owner.getLocation().getYaw();
        }

        void summon() {
            Player owner = player();
            long lifetime = duration + STRIKE_MAX_AGE + QUEEN_LEAVE_TICKS + 20;
            build(owner, lifetime);
            pose(0.05f, 0);
            ctx().runLater(owner, 1, () -> pose(1f, QUEEN_GROW_TICKS));

            Location at = anchor.clone();
            World world = at.getWorld();
            world.spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, PALETTE.core(), true);
            Vfx.sphere(ctx(), at, 1.2, 30, Particle.DUST_COLOR_TRANSITION, HONEY_GLOW, true);
            Vfx.cloud(ctx(), at, Particle.WAX_ON, 18, 0.8, 0.1, null, true);
            Vfx.shockwave(ctx(), owner, owner.getLocation().add(0, 0.1, 0), 0.5, radius, 8,
                    Particle.DUST_COLOR_TRANSITION, HONEY_GLOW, true);
            Vfx.helix(ctx(), owner.getLocation(), 0.9, owner.getHeight() + 1.4, 1.5, 2, 14, 0, Particle.DUST,
                    PALETTE.dust(1.0f), true);
            Fx.sound(at, Sound.ITEM_GOAT_HORN_SOUND_1, 1.2f, 1.3f);
            Fx.sound(at, Sound.BLOCK_BEEHIVE_EXIT, 1.2f, 0.6f);
            Fx.sound(at, Sound.ENTITY_BEE_LOOP_AGGRESSIVE, 1.4f, 0.55f);
            Fx.sound(at, Sound.BLOCK_HONEY_BLOCK_PLACE, 1.0f, 0.8f + Fx.jitter(0.05f));
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>♛ 여왕벌</b></" + COLOR_TAG + "> <gray>"
                    + AbilityInfo.formatNumber(duration / 20.0) + "초</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, lifetime, this, this::finish);
        }

        /** Spawns the parts: thorax, banded abdomen, head with crown and feelers, stinger and two wings. */
        private void build(Player owner, long lifetime) {
            add(owner, Material.YELLOW_CONCRETE, 0, 0, 0.15f, 0.8f, 0.75f, 0.75f, 0, 0, lifetime);
            add(owner, Material.YELLOW_CONCRETE, 0, -0.08f, -0.6f, 0.86f, 0.8f, 1.0f, 8, 0, lifetime);
            add(owner, Material.BLACK_CONCRETE, 0, -0.06f, -0.42f, 0.9f, 0.84f, 0.16f, 8, 0, lifetime);
            add(owner, Material.BLACK_CONCRETE, 0, -0.11f, -0.82f, 0.9f, 0.84f, 0.16f, 8, 0, lifetime);
            add(owner, Material.BROWN_CONCRETE, 0, 0.05f, 0.72f, 0.62f, 0.6f, 0.45f, 0, 0, lifetime);
            add(owner, Material.GOLD_BLOCK, 0, 0.42f, 0.72f, 0.44f, 0.14f, 0.44f, 0, 0, lifetime);
            add(owner, Material.BLACK_CONCRETE, -0.16f, 0.45f, 1.0f, 0.05f, 0.05f, 0.4f, -40, 0, lifetime);
            add(owner, Material.BLACK_CONCRETE, 0.16f, 0.45f, 1.0f, 0.05f, 0.05f, 0.4f, -40, 0, lifetime);
            add(owner, Material.BLACK_CONCRETE, 0, -0.2f, -1.22f, 0.1f, 0.1f, 0.32f, 8, 0, lifetime);
            add(owner, Material.WHITE_STAINED_GLASS, 0, 0.4f, -0.05f, 0.85f, 0.04f, 1.0f, 0, -1, lifetime);
            add(owner, Material.WHITE_STAINED_GLASS, 0, 0.4f, -0.05f, 0.85f, 0.04f, 1.0f, 0, 1, lifetime);
        }

        private void add(Player owner, Material material, float x, float y, float z, float w, float h, float l,
                         float pitch, int wing, long lifetime) {
            BlockDisplay display = Displays.block(ctx(), owner, anchor, material.createBlockData(),
                    Displays.scaled(0.01f), lifetime);
            display.setViewRange(2.0f);
            display.setTeleportDuration(2);
            displays.add(display);
            parts.add(new QueenPart(display, x, y, z, w, h, l, pitch, wing));
        }

        /**
         * Poses every part at {@code scale} with the wings at their current beat, interpolated over {@code ticks}. The
         * queen turns and leans as one body by {@link #yaw} and {@link #pitch}, folded into every part's
         * transformation (the displays themselves stay unrotated).
         */
        private void pose(float scale, int ticks) {
            float s = scale * QUEEN_SCALE;
            float wingAngle = wings;
            for (QueenPart part : parts) {
                BlockDisplay display = part.display();
                if (!display.isValid()) {
                    continue;
                }
                Transformation pose;
                if (part.wing() == 0) {
                    pose = Displays.transform(part.x() * s, part.y() * s, part.z() * s, part.w() * s, part.h() * s,
                            part.l() * s, 0, part.pitch(), 0);
                } else {
                    // A wing hinged at the side of the back: rotate around the hinge, not the plate's center.
                    float roll = part.wing() * wingAngle;
                    Quaternionf rotation = new Quaternionf().rotateZ((float) Math.toRadians(roll));
                    Vector3f center = rotation.transform(new Vector3f(part.wing() * part.w() * 0.5f, 0, 0))
                            .add(part.wing() * 0.2f, part.y(), part.z()).mul(s);
                    pose = Displays.transform(center.x, center.y, center.z, part.w() * s, part.h() * s,
                            part.l() * s, 0, 0, roll);
                }
                Displays.animate(display, turned(pose, yaw, pitch), ticks);
            }
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            if (tick >= duration && leaveStart < 0) {
                leave(owner, tick);
            }
            if (leaveStart < 0) {
                follow(owner, tick);
                if (tick >= nextWave && tick < duration) {
                    nextWave = tick + waveInterval;
                    wave(owner);
                }
                if (tick % 20 == 10 && tick < duration) {
                    rainHoney(owner);
                }
                if (tick % 30 == 0) {
                    Fx.sound(anchor, Sound.ENTITY_BEE_LOOP_AGGRESSIVE, 1.0f, 0.55f + Fx.jitter(0.04f));
                }
            }
            updateStrikes(owner);
            return leaveStart < 0 || tick - leaveStart < QUEEN_LEAVE_TICKS || !strikes.isEmpty();
        }

        /** Hovers behind the keeper's shoulder, bobbing, facing where the keeper looks. */
        private void follow(Player owner, long tick) {
            Location feet = owner.getLocation();
            Vector back = Motion.horizontalFacing(feet).multiply(-QUEEN_BACK);
            Location goal = feet.clone().add(back).add(0, QUEEN_HEIGHT + Math.sin(tick * 0.22) * 0.18, 0);
            if (!goal.getWorld().equals(anchor.getWorld())) {
                anchor = goal;
            } else {
                anchor.add(goal.toVector().subtract(anchor.toVector()).multiply(QUEEN_FOLLOW));
            }
            for (QueenPart part : parts) {
                Displays.moveTo(part.display(), anchor);
            }
            if (tick > QUEEN_GROW_TICKS && tick % FLAP_PERIOD == 0) {
                // Turn towards where the keeper looks (smoothly), dip the head for a moment when a wave leaves, beat.
                yaw += (float) (wrap(feet.getYaw() - yaw) * QUEEN_TURN);
                pitch = lunge > 0 ? LUNGE_PITCH : 0;
                wings = (tick / FLAP_PERIOD) % 2 == 0 ? WING_UP : WING_DOWN;
                pose(1f, (int) FLAP_PERIOD);
            }
            lunge = Math.max(0, lunge - 1);
            if (tick % 3 == 0) {
                Location tail = stingerPoint();
                Vfx.dot(tail, Particle.DUST_COLOR_TRANSITION, HONEY_FADE, false);
                Vfx.dot(Vfx.scatter(anchor, 0.8).add(0, 0.6, 0), Particle.WAX_ON, null, false);
            }
        }

        /** A wave: bees burst from the stinger towards every visible enemy in range. */
        private void wave(Player owner) {
            List<LivingEntity> enemies = visibleEnemies(owner, owner.getLocation(), radius);
            if (enemies.isEmpty()) {
                return;
            }
            lunge = LUNGE_TICKS;
            Location from = stingerPoint();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (LivingEntity enemy : enemies) {
                for (int i = 0; i < beesPerEnemy; i++) {
                    Vector toward = Fx.body(enemy).toVector().subtract(from.toVector());
                    Vector spray = (toward.lengthSquared() > 1.0E-6 ? toward.normalize() : new Vector(0, -1, 0))
                            .multiply(0.3).add(randomUnit(random).multiply(0.25));
                    strikes.add(new Strike(new Bee(Vfx.scatter(from, 0.25), spray, STRIKE_MAX_AGE + 6), enemy));
                }
                Vfx.trail(from, Fx.body(enemy), PALETTE.core(), 10, true);
            }
            World world = from.getWorld();
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, from, Vfx.n(ctx(), 14), 0.3, 0.3, 0.3, 0,
                    HONEY_GLOW, true);
            hexRing(from, facing(yaw), 0.7, Particle.DUST_COLOR_TRANSITION, HONEY_GLOW, true);
            Fx.sound(from, Sound.BLOCK_BEEHIVE_EXIT, 1.2f, 0.9f + Fx.jitter(0.1f));
            Fx.sound(from, Sound.ENTITY_BEE_POLLINATE, 1.0f, 0.7f + Fx.jitter(0.1f));
            Fx.sound(from, Sound.ENTITY_BEE_LOOP_AGGRESSIVE, 1.0f, 1.2f + Fx.jitter(0.1f));
        }

        private void updateStrikes(Player owner) {
            Iterator<Strike> it = strikes.iterator();
            while (it.hasNext()) {
                Strike strike = it.next();
                if (strike.update(owner)) {
                    it.remove();
                }
            }
        }

        /** Honey drips from the queen onto the keeper and every ally nearby, healing them. */
        private void rainHoney(Player owner) {
            if (heal <= 0) {
                return;
            }
            List<Player> fed = new ArrayList<>();
            fed.add(owner);
            fed.addAll(ctx().alliesNear(owner, owner.getLocation(), healRadius));
            Location from = anchor.clone().subtract(0, 0.4, 0);
            for (Player ally : fed) {
                ctx().heal(ally, heal);
                Location chest = ally.getLocation().add(0, ally.getHeight() * 0.6, 0);
                if (chest.getWorld().equals(from.getWorld())) {
                    Vfx.trail(Vfx.scatter(from, 0.3), chest, PALETTE.body(), 8, false);
                    Vfx.trail(Vfx.scatter(from, 0.3), chest, PALETTE.core(), 10, false);
                }
                Vfx.cloud(ctx(), ally.getLocation().add(0, ally.getHeight() + 0.4, 0), Particle.FALLING_HONEY, 4,
                        0.25, 0, null, false);
                Vfx.ring(ctx(), ally.getLocation().add(0, 0.1, 0), 0.5, 10, 0, Particle.DUST_COLOR_TRANSITION,
                        HONEY_GLOW, false);
                Vfx.dot(chest.clone().add(0, 0.5, 0), Particle.HEART, null, false);
            }
            Fx.sound(from, Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.7f, 1.2f + Fx.jitter(0.1f));
        }

        /** The time is up: the queen rises and shrinks away in a shower of honey. */
        private void leave(Player owner, long tick) {
            leaveStart = tick;
            Location at = anchor.clone();
            pitch = LEAVE_PITCH;
            wings = WING_UP;
            pose(0.05f, (int) QUEEN_LEAVE_TICKS);
            Location up = anchor.clone().add(0, 4, 0);
            for (QueenPart part : parts) {
                BlockDisplay display = part.display();
                if (display.isValid()) {
                    display.setTeleportDuration((int) QUEEN_LEAVE_TICKS);
                    Displays.moveTo(display, up);
                }
            }
            Vfx.cloud(ctx(), at, Particle.FALLING_HONEY, 16, 1.0, 0, null, true);
            Vfx.sphere(ctx(), at, 1.4, 26, Particle.DUST_COLOR_TRANSITION, HONEY_FADE, true);
            Fx.sound(at, Sound.BLOCK_BEEHIVE_ENTER, 1.2f, 0.6f);
            Fx.sound(at, Sound.ENTITY_BEE_POLLINATE, 1.0f, 0.5f);
            ctx().runLater(owner, QUEEN_LEAVE_TICKS, this::removeParts);
        }

        private void removeParts() {
            for (QueenPart part : parts) {
                if (part.display().isValid()) {
                    part.display().remove();
                }
            }
            parts.clear();
        }

        /** Runs when the loop ends by itself; everything still out is removed. */
        private void finish() {
            removeParts();
            for (Strike strike : strikes) {
                strike.bee().vanish(false);
            }
            strikes.clear();
        }

        /** The tip of the queen's abdomen, where her bees leave. */
        private Location stingerPoint() {
            return anchor.clone().add(facing(yaw).multiply(-1.0 * QUEEN_SCALE)).subtract(0, 0.25 * QUEEN_SCALE, 0);
        }

        /** One bee of a wave homing on its enemy; it stings once and drops away. */
        private final class Strike {

            private final Bee bee;
            private final LivingEntity target;
            private long age;

            Strike(Bee bee, LivingEntity target) {
                this.bee = bee;
                this.target = target;
            }

            Bee bee() {
                return bee;
            }

            /** One tick; true when the strike is over. */
            boolean update(Player owner) {
                age++;
                if (bee.gone) {
                    return true;
                }
                if (age > STRIKE_MAX_AGE || !Combat.isTargetable(ctx(), owner, target)
                        || !target.getWorld().equals(bee.pos.getWorld()) || !visible(owner, target)) {
                    bee.vanish(true);
                    return true;
                }
                Location body = Fx.body(target);
                double reach = STRIKE_REACH + target.getWidth() * 0.5;
                if (bee.pos.distanceSquared(body) <= reach * reach + target.getHeight() * 0.25) {
                    if (target.isInWater()) {
                        // Bees cannot reach under water: it buzzes above and gives up.
                        Vfx.cloud(ctx(), body.clone().add(0, 1, 0), Particle.SPLASH, 4, 0.3, 0.05, null, false);
                        bee.vanish(true);
                        return true;
                    }
                    stingFrom(owner);
                    return true;
                }
                bee.steer(body, STRIKE_SPEED, STRIKE_ACCEL);
                bee.draw(age);
                return false;
            }

            private void stingFrom(Player owner) {
                Location body = Fx.body(target);
                Vfx.trail(bee.pos, body, PALETTE.body(), 2, false);
                bee.vanish(false);
                Combat.hit(ctx(), owner, target, stingDamage);
                World world = body.getWorld();
                world.spawnParticle(Particle.CRIT, body, Vfx.n(ctx(), 3), 0.2, 0.25, 0.2, 0.15);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 3), 0.25, 0.3, 0.25, 0,
                        HONEY_FADE);
                Fx.sound(body, Sound.ENTITY_BEE_STING, 0.8f, 1.0f + Fx.jitter(0.2f));
                if (Combat.isTargetable(ctx(), owner, target)) {
                    poison(target, poisonTicks);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Enemies around {@code center} that the owner can see (stealthed players are skipped), nearest first. */
    private List<LivingEntity> visibleEnemies(Player owner, Location center, double radius) {
        List<LivingEntity> found = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
            if (visible(owner, enemy)) {
                found.add(enemy);
            }
        }
        return found;
    }

    private static boolean visible(Player owner, LivingEntity entity) {
        return !(entity instanceof Player player) || owner.canSee(player);
    }

    /**
     * Poison I lasting at least {@code ticks} more. A running Poison I is extended in whole {@link #POISON_PERIOD}
     * steps: vanilla hurts whenever the remaining duration is a multiple of that period, so resetting it to a fresh
     * duration on every sting (every 0.5 s) would make the poison hurt on every sting instead of every 1.25 s.
     * A stronger or endless poison is left alone.
     */
    private static void poison(LivingEntity target, long ticks) {
        if (ticks <= 0) {
            return;
        }
        int wanted = (int) Math.min(Integer.MAX_VALUE / 2, ticks);
        PotionEffect current = target.getPotionEffect(PotionEffectType.POISON);
        int duration = wanted;
        if (current != null && current.getDuration() > 0) {
            if (current.getAmplifier() != 0 || current.isInfinite() || current.getDuration() >= wanted) {
                return;
            }
            int left = current.getDuration();
            duration = left + (int) Math.ceil((wanted - left) / (double) POISON_PERIOD) * POISON_PERIOD;
        }
        target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, duration, 0, false, true, true));
    }

    /** A hexagon of particles (honeycomb cell) facing {@code normal} around {@code center}. */
    private <T> void hexRing(Location center, Vector normal, double radius, Particle particle, @Nullable T data,
                             boolean force) {
        Vector n = normal.lengthSquared() < 1.0E-6 ? new Vector(0, 0, 1) : normal.clone().normalize();
        Vector helper = Math.abs(n.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = n.clone().crossProduct(helper).normalize();
        Vector v = n.clone().crossProduct(u).normalize();
        int perSide = Math.max(1, Vfx.n(ctx(), 3));
        for (int corner = 0; corner < 6; corner++) {
            double a0 = corner * TAU / 6;
            double a1 = (corner + 1) * TAU / 6;
            for (int i = 0; i < perSide; i++) {
                double t = (double) i / perSide;
                double a = Math.cos(a0) * (1 - t) + Math.cos(a1) * t;
                double b = Math.sin(a0) * (1 - t) + Math.sin(a1) * t;
                Location at = center.clone().add(u.clone().multiply(a * radius)).add(v.clone().multiply(b * radius));
                Vfx.dot(at, particle, data, force);
            }
        }
    }

    /** A bee item display (billboarded so it always reads as a bee), or null past the display budget. */
    private @Nullable ItemDisplay spawnBee(Location at, long lifetime) {
        if (!hasDisplayRoom()) {
            return null;
        }
        ItemDisplay display = Displays.item(ctx(), player(), at, new ItemStack(BEE_ITEM),
                Displays.centered(0, 0, 0, BEE_SCALE, BEE_SCALE, BEE_SCALE, 0, 0, 0), lifetime);
        display.setBillboard(Display.Billboard.CENTER);
        display.setTeleportDuration(1);
        displays.add(display);
        return display;
    }

    private @Nullable BlockDisplay spawnBlock(Player owner, Location at, BlockData data, Transformation start,
                                              long lifetime) {
        if (!hasDisplayRoom()) {
            return null;
        }
        BlockDisplay display = Displays.block(ctx(), owner, at, data, start, lifetime);
        displays.add(display);
        return display;
    }

    private boolean hasDisplayRoom() {
        displays.removeIf(display -> !display.isValid());
        return displays.size() < DISPLAY_BUDGET;
    }

    /** True if a bee fits at {@code at}: the block there has no collision. */
    private static boolean isAir(Location at) {
        return at.getBlock().isPassable();
    }

    private static Location centroid(List<Bee> bees) {
        Location first = bees.getFirst().pos;
        double x = 0;
        double y = 0;
        double z = 0;
        for (Bee bee : bees) {
            x += bee.pos.getX();
            y += bee.pos.getY();
            z += bee.pos.getZ();
        }
        int n = bees.size();
        return new Location(first.getWorld(), x / n, y / n, z / n);
    }

    /**
     * {@code local} (a pose in the rig's own frame, nose along +z) turned as part of a rigid body by {@code yaw} and
     * {@code pitch} (Minecraft degrees: yaw 0 = +z, 90 = -x; positive pitch looks down). The rotation lives in the
     * transformation only, so the display entity keeps yaw/pitch 0 and is moved with {@link Displays#moveTo}.
     */
    private static Transformation turned(Transformation local, float yaw, float pitch) {
        Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-yaw))
                .rotateX((float) Math.toRadians(pitch));
        return new Transformation(turn.transform(new Vector3f(local.getTranslation())),
                new Quaternionf(turn).mul(local.getLeftRotation()), new Vector3f(local.getScale()),
                new Quaternionf(local.getRightRotation()));
    }

    /** The horizontal unit vector a Minecraft yaw faces. */
    private static Vector facing(double yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    /** {@code degrees} wrapped into -180..180. */
    private static double wrap(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped > 180) {
            wrapped -= 360;
        } else if (wrapped <= -180) {
            wrapped += 360;
        }
        return wrapped;
    }

    /** The Minecraft yaw (degrees) of a direction. */
    private static float yawOf(Vector direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    private static Vector randomUnit(ThreadLocalRandom random) {
        Vector vector = new Vector(random.nextGaussian(), random.nextGaussian() * 0.6, random.nextGaussian());
        return vector.lengthSquared() < 1.0E-6 ? new Vector(1, 0, 0) : vector.normalize();
    }
}
