package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
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
import io.papermc.paper.math.Angle;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
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
 * 정신술사 — a telekinetic. Whoever strikes it has its mind scrambled (its next skill comes back late), the active
 * lifts the aimed enemy in a psychic bubble and hurls it where the psychic looks (into walls and other enemies for a
 * crunch), and the ultimate collapses the minds of everyone nearby: nausea, silence and helpless staggering.
 *
 * <p>Look (pale pink, magenta, deep violet): a disrupted attacker wears a wobbling magenta star and crackling static
 * over its head until the disruption takes hold and the star pops. The lifted enemy floats in a magenta glass bubble
 * circled by amethyst shards while a dotted arrow shows the throw, then streaks away with a pink trail and bursts the
 * bubble on impact (wall dust and a crunch on a slam). The collapse is a psychic scream of expanding rings, threads
 * of thought snapping onto every victim and a spinning brain coral crown over each head that pops at every stagger.
 */
public final class PsychicAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("psychic", "정신술사", Material.BRAIN_CORAL)
            .color(TextColor.color(0xE040FB))
            .difficulty(2)
            .passive("정신 교란",
                    "나를 공격한 적의 정신을 흐트러뜨립니다. 그 적은 액티브 쿨타임이 끝나는 순간 {disrupt_delay}초간 "
                            + "침묵해 다음 스킬이 늦어집니다(같은 적은 {disrupt_cooldown}초마다).")
            .active("염동 투척",
                    "조준한 적({throw_range}블록 이내)을 들어 올려 {throw_damage} 피해를 주고, {lift_time}초 뒤 내가 "
                            + "바라보는 방향으로 {throw_distance}블록 내던집니다. 날아가다 벽이나 다른 적과 부딪히면 부딪힌 "
                            + "적 모두 {slam_damage} 피해를 받고 {slam_slow}초간 구속됩니다.",
                    10)
            .ultimate("정신 붕괴",
                    "반경 {ult_radius}블록의 적에게 {ult_damage} 피해를 주고 {ult_duration}초간 정신을 무너뜨립니다. "
                            + "무너진 적은 멀미와 침묵에 걸리고 {ult_jolt}초마다 무작위 방향으로 휘청거립니다.")
            .param("disrupt_delay", 1)
            .param("disrupt_cooldown", 6)
            .param("throw_range", 18)
            .param("throw_damage", 3)
            .param("lift_time", 0.6)
            .param("throw_distance", 9)
            .param("slam_damage", 4)
            .param("slam_slow", 1)
            .param("ult_radius", 12)
            .param("ult_damage", 3)
            .param("ult_duration", 3)
            .param("ult_jolt", 0.5)
            .build();

    private static final String MAGENTA = "#E040FB";
    /** Pale pink core, magenta body, deep violet edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFD6F5, 0xE040FB, 0x5E1A8C);
    private static final Particle.DustTransition GLOW = PALETTE.glow(1.1f);
    private static final Particle.DustTransition GLOW_SMALL = PALETTE.glow(0.7f);
    private static final Particle.DustTransition FADE = PALETTE.fade(1.0f);
    private static final Particle.DustOptions PINK_DUST = PALETTE.dust(0.8f);
    private static final Particle.DustOptions VIOLET_DUST = new Particle.DustOptions(PALETTE.edge(), 1.0f);

    /** Hard cap for every velocity this ability applies. */
    private static final double MAX_SPEED = 2.0;

    // passive
    private static final long DISRUPT_PERIOD = 2;
    private static final long STATIC_PERIOD = 8;
    private static final long CLEANUP_PERIOD = 200;
    /** Hard lifetime of the mark over a disrupted attacker (it pops much earlier, when its cooldown ends). */
    private static final long GLYPH_MAX_TICKS = 600;

    // active
    private static final double LIFT_HEIGHT = 1.6;
    /** A target that moves farther than this in one tick teleported away and breaks free. */
    private static final double GRIP_BREAK = 3.5;
    /** How far inside the arena border a throw ends at the latest. */
    private static final double ARENA_MARGIN = 1.0;
    private static final double FLIGHT_SPEED = 1.15;
    private static final double FLIGHT_SINK = -0.05;
    private static final double SLAM_BOUNCE = 0.25;
    private static final double BODY_PUSH = 0.9;
    private static final int SHARDS = 3;
    private static final int SLAM_SLOW_AMPLIFIER = 2;
    private static final BlockData BUBBLE = Material.MAGENTA_STAINED_GLASS.createBlockData();
    private static final ItemStack SHARD = ItemStack.of(Material.AMETHYST_SHARD);

    // ultimate
    private static final double JOLT_SPEED = 0.5;
    private static final double JOLT_LIFT = 0.22;
    private static final float CAMERA_YAW = 35;
    private static final float CAMERA_PITCH = 12;
    private static final int MAX_CROWNS = 12;
    private static final ItemStack CROWN = ItemStack.of(Material.BRAIN_CORAL);
    /** Nausea needs a moment to warp the view, so it outlasts the collapse a little. */
    private static final int NAUSEA_EXTRA_TICKS = 40;

    /** Attackers whose minds are scrambled, by player id. */
    private final Map<UUID, Disruption> disruptions = new HashMap<>();

    private static final class Disruption {
        boolean armed;
        long nextAllowed;
        /** The spinning mark over the attacker's head while the disruption waits. */
        @Nullable TextDisplay glyph;
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        // The glyphs are tracked displays: they are removed with the ability.
        disruptions.clear();
    }

    // ------------------------------------------------------------------ passive: mind disruption

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getFinalDamage() <= 0
                || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Player owner = player();
        Player attacker = ctx().resolveAttacker(byEntity.getDamager());
        if (attacker == null || attacker.equals(owner) || !ctx().isEnemy(owner, attacker)) {
            return;
        }
        long now = ctx().currentTick();
        Disruption disruption = disruptions.computeIfAbsent(attacker.getUniqueId(), id -> new Disruption());
        if (disruption.armed || now < disruption.nextAllowed) {
            return;
        }
        disruption.armed = true;
        disruption.nextAllowed = now + params().ticks("disrupt_cooldown");
        Location head = attacker.getEyeLocation().add(0, 0.4, 0);
        Vfx.trail(Fx.body(owner), head, PALETTE.body(), 6, false);
        Vfx.cloud(ctx(), head, Particle.WITCH, 6, 0.25, 0.02, null, false);
        Fx.sound(head, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 1.8f + Fx.jitter(0.1f));
        attacker.playSound(attacker, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.8f, 1.9f);
        if (disruption.glyph != null && disruption.glyph.isValid()) {
            disruption.glyph.remove();
        }
        TextDisplay glyph = Displays.text(ctx(), owner, glyphPoint(attacker),
                Text.mm("<b><gradient:#FFD6F5:#E040FB:#5E1A8C>✺</gradient></b>"), 0.1f, GLYPH_MAX_TICKS);
        glyph.setTeleportDuration((int) DISRUPT_PERIOD);
        Displays.animateNextTick(ctx(), owner, glyph, glyphPose(0.75f, 0), 4);
        disruption.glyph = glyph;
        owner.sendActionBar(Text.mm("<" + MAGENTA + ">✺ 정신 교란</" + MAGENTA + "> <white>" + attacker.getName()
                + "</white>"));
    }

    @Override
    public void onTick(long tick) {
        if (tick % DISRUPT_PERIOD != 0 || disruptions.isEmpty()) {
            return;
        }
        long now = ctx().currentTick();
        if (tick % CLEANUP_PERIOD == 0) {
            disruptions.values().removeIf(d -> !d.armed && now >= d.nextAllowed);
        }
        for (Map.Entry<UUID, Disruption> entry : disruptions.entrySet()) {
            Disruption disruption = entry.getValue();
            if (!disruption.armed) {
                continue;
            }
            Player attacker = player().getServer().getPlayer(entry.getKey());
            if (attacker == null || !ctx().isAlive(attacker) || !ctx().isEnemy(player(), attacker)) {
                disruption.armed = false;
                dropGlyph(disruption, false);
                continue;
            }
            if (ctx().cooldownRemaining(attacker) <= 0) {
                disruption.armed = false;
                long delay = params().ticks("disrupt_delay");
                if (delay > 0) {
                    ctx().silence(attacker, delay);
                    snapFx(attacker);
                }
                dropGlyph(disruption, true);
                continue;
            }
            TextDisplay glyph = disruption.glyph;
            if (glyph != null && glyph.isValid()) {
                glyph.teleport(glyphPoint(attacker));
                if (tick % (DISRUPT_PERIOD * 2) == 0) {
                    // A slow wobble, as if the thought kept slipping.
                    Displays.animate(glyph, glyphPose(0.75f, (float) Math.sin(tick * 0.2) * 25), 4);
                }
            }
            if (tick % STATIC_PERIOD == 0) {
                staticFx(attacker, tick);
            }
        }
    }

    private static Location glyphPoint(Player attacker) {
        return attacker.getLocation().add(0, attacker.getHeight() + 0.55, 0);
    }

    private static Transformation glyphPose(float scale, float roll) {
        return Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, roll);
    }

    /** The mark over the attacker's head pops (the disruption took hold) or simply fades (it was called off). */
    private void dropGlyph(Disruption disruption, boolean pop) {
        TextDisplay glyph = disruption.glyph;
        disruption.glyph = null;
        if (glyph == null || !glyph.isValid()) {
            return;
        }
        Player owner = player();
        if (pop) {
            Displays.animate(glyph, glyphPose(1.5f, 90), 2);
            ctx().runLater(owner, 2, () -> {
                if (glyph.isValid()) {
                    Displays.shrinkAway(ctx(), owner, glyph, 3);
                }
            });
        } else {
            Displays.shrinkAway(ctx(), owner, glyph, 4);
        }
    }

    /** Magenta static crackling over a disrupted attacker's head while its cooldown runs. */
    private void staticFx(Player attacker, long tick) {
        Location head = attacker.getEyeLocation().add(0, 0.45, 0);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location from = head.clone().add(random.nextDouble(-0.3, 0.3), random.nextDouble(-0.1, 0.1),
                random.nextDouble(-0.3, 0.3));
        Location to = head.clone().add(random.nextDouble(-0.3, 0.3), random.nextDouble(-0.1, 0.1),
                random.nextDouble(-0.3, 0.3));
        Vfx.line(ctx(), from, to, 6, Particle.DUST, PINK_DUST, false);
        Vfx.ring(ctx(), head, 0.28, 5, tick * 0.3, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
    }

    /** The scrambled mind catches up with the attacker: a burst of static as the silence takes hold. */
    private void snapFx(Player attacker) {
        Location head = attacker.getEyeLocation().add(0, 0.4, 0);
        Vfx.burst(ctx(), head, PALETTE, 0.4, Particle.WITCH, false);
        Vfx.ring(ctx(), head, 0.5, 12, 0, Particle.DUST_COLOR_TRANSITION, GLOW, false);
        Fx.sound(head, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.8f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(head, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 0.5f, 1.8f);
    }

    // ------------------------------------------------------------------ active: telekinetic throw

    @Override
    public ActivationResult onActive() {
        LivingEntity target = ctx().findTarget(player(), params().num("throw_range"));
        if (target == null) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        new Throw(target).begin();
        return ActivationResult.success();
    }

    /** Where the telekinetic grip leaves the psychic: just in front of the raised hand. */
    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.add(eye.getDirection().multiply(0.6)).subtract(0, 0.25, 0);
    }

    /**
     * The throw: the target is lifted into a bubble for the lift time, then hurled along the psychic's horizontal
     * view, crunching into the first wall or enemy in its way.
     */
    private final class Throw implements LongPredicate {

        private final LivingEntity target;
        private final long liftTicks;
        private final ItemDisplay[] shards = new ItemDisplay[SHARDS];
        private @Nullable BlockDisplay bubble;
        private @Nullable Location hold;
        private Vector direction = new Vector(0, 0, 1);
        private double distance;
        private double traveled;
        private boolean slammed;
        /** Where the target was on the previous tick, to notice a teleport out of the grip. */
        private @Nullable Location last;

        Throw(LivingEntity target) {
            this.target = target;
            this.liftTicks = Math.max(1, params().ticks("lift_time"));
        }

        void begin() {
            Player owner = player();
            Combat.hit(ctx(), owner, target, params().num("throw_damage"));
            Location body = Fx.body(target);
            Vfx.trail(hand(owner), body, PALETTE.body(), 4, false);
            Vfx.cloud(ctx(), body, Particle.WITCH, 10, 0.35, 0.05, null, false);
            Vfx.sphere(ctx(), body, 0.9, 18, Particle.DUST_COLOR_TRANSITION, GLOW, false);
            Fx.sound(body, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 1.4f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.6f);
            Fx.sound(owner.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.6f, 1.8f);
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return;
            }
            hold = target.getLocation().add(0, headroom(), 0);
            long life = liftTicks + flightBudget() + 12;
            float width = (float) (target.getWidth() + 0.5);
            float height = (float) (target.getHeight() + 0.4);
            bubble = Displays.block(ctx(), owner, target.getLocation(), BUBBLE, box(width * 0.3f, -0.2f, height * 0.3f),
                    life);
            bubble.setTeleportDuration(1);
            Displays.animateNextTick(ctx(), owner, bubble, box(width, -0.2f, height), 4);
            for (int i = 0; i < SHARDS; i++) {
                shards[i] = Displays.item(ctx(), owner, body, SHARD,
                        Displays.centered(0, 0, 0, 0.45f, 0.45f, 0.45f, 0, 0, 45), life);
                shards[i].setTeleportDuration(1);
            }
            Loop.repeat(ctx(), owner, 1, 1, liftTicks + flightBudget(), this, this::finish);
        }

        /** How high the target can be lifted without bumping its head. */
        private double headroom() {
            Location top = target.getLocation().add(0, target.getHeight(), 0);
            RayTraceResult ceiling = target.getWorld().rayTraceBlocks(top, new Vector(0, 1, 0), LIFT_HEIGHT + 0.3,
                    FluidCollisionMode.NEVER, true);
            if (ceiling == null) {
                return LIFT_HEIGHT;
            }
            return Math.max(0, Math.min(LIFT_HEIGHT, ceiling.getHitPosition().getY() - top.getY() - 0.3));
        }

        private long flightBudget() {
            return (long) Math.ceil(Math.max(0, params().num("throw_distance")) / FLIGHT_SPEED) + 2;
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(owner.getWorld())) {
                return false;
            }
            Location now = target.getLocation();
            if (last != null && now.distanceSquared(last) > GRIP_BREAK * GRIP_BREAK) {
                // The target teleported out of the grip (its own skill, a swap...): let it go.
                return false;
            }
            last = now;
            if (tick < liftTicks - 1) {
                lift(tick);
                return true;
            }
            if (tick == liftTicks - 1) {
                release();
                return distance > 0.05;
            }
            return fly(tick);
        }

        /** Held in the air: steered to the hold point while a dotted arrow shows where it will be thrown. */
        private void lift(long tick) {
            Location point = hold == null ? target.getLocation() : hold;
            Vector to = point.toVector().subtract(target.getLocation().toVector());
            target.setVelocity(Motion.clamp(to.multiply(0.4), 0.6));
            target.setFallDistance(0);
            follow(tick);
            Location body = Fx.body(target);
            Vfx.trail(hand(player()), body, PALETTE.core(), 3, false);
            if (tick % 2 == 0) {
                Vfx.helix(ctx(), target.getLocation(), target.getWidth() * 0.9, target.getHeight(), 1, 2, 8,
                        tick * 0.4, Particle.DUST_COLOR_TRANSITION, GLOW_SMALL, false);
                aimArrow(body);
            }
            if (tick == 0) {
                Fx.sound(body, Sound.ENTITY_ALLAY_ITEM_TAKEN, 0.8f, 0.6f);
            }
        }

        /** The throw preview: dots along the psychic's view from the target, up to the throw distance. */
        private void aimArrow(Location body) {
            Vector facing = throwDirection();
            double reach = Math.max(0, params().num("throw_distance"));
            RayTraceResult wall = body.getWorld().rayTraceBlocks(body, facing, reach, FluidCollisionMode.NEVER, true);
            double length = wall == null ? reach : wall.getHitPosition().distance(body.toVector());
            for (double d = 0.8; d <= length; d += 0.7) {
                Vfx.dot(body.clone().add(facing.clone().multiply(d)), Particle.DUST, PINK_DUST, false);
            }
            Location tip = body.clone().add(facing.clone().multiply(length));
            Vector side = new Vector(-facing.getZ(), 0, facing.getX()).multiply(0.35);
            Vector back = facing.clone().multiply(-0.4);
            Vfx.line(ctx(), tip, tip.clone().add(back).add(side), 8, Particle.DUST, PINK_DUST, false);
            Vfx.line(ctx(), tip, tip.clone().add(back).subtract(side), 8, Particle.DUST, PINK_DUST, false);
        }

        private Vector throwDirection() {
            Player owner = player();
            Vector facing = Motion.horizontal(owner.getEyeLocation().getDirection());
            if (facing.lengthSquared() < 1.0E-6) {
                facing = Motion.horizontal(target.getLocation().toVector().subtract(owner.getLocation().toVector()));
            }
            return facing.lengthSquared() < 1.0E-6 ? new Vector(0, 0, 1) : facing;
        }

        private void release() {
            direction = throwDirection();
            // Never out of the arena: the flight ends a little short of the border, so the landing slide stays in.
            double wanted = Math.max(0, params().num("throw_distance"));
            double reach = Spots.arenaReach(ctx(), target.getLocation(), direction, wanted + ARENA_MARGIN);
            distance = Math.clamp(reach - ARENA_MARGIN, 0, wanted);
            Location body = Fx.body(target);
            Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.WITCH, false);
            Vfx.trail(hand(player()), body, PALETTE.body(), 3, false);
            Fx.sound(body, Sound.ITEM_TRIDENT_THROW, 1.0f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.6f);
            Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 0.8f);
            if (distance <= 0.05) {
                // Nowhere to throw (the arena edge is right there): it just drops.
                target.setVelocity(new Vector());
                target.setFallDistance(0);
            }
        }

        /** One tick of flight. False when it crashed or flew the whole distance. */
        private boolean fly(long tick) {
            World world = target.getWorld();
            double remaining = distance - traveled;
            if (remaining <= 0.05) {
                land();
                return false;
            }
            double speed = Math.min(FLIGHT_SPEED, remaining);
            BoundingBox box = target.getBoundingBox();
            BoundingBox next = box.clone().shift(direction.getX() * speed, 0, direction.getZ() * speed);
            // The probe skips the feet, so sliding over the floor or a small step never counts as a wall.
            BoundingBox probe = new BoundingBox(next.getMinX() + 0.05, next.getMinY() + 0.6, next.getMinZ() + 0.05,
                    next.getMaxX() - 0.05, next.getMaxY() - 0.05, next.getMaxZ() - 0.05);
            if (world.hasCollisionsIn(probe)) {
                wallSlam();
                return false;
            }
            BoundingBox reach = next.clone().expand(0.3);
            for (LivingEntity other : ctx().enemiesNear(player(), Fx.body(target), 2.5)) {
                if (!other.equals(target) && other.getBoundingBox().overlaps(reach)) {
                    bodySlam(other);
                    return false;
                }
            }
            Location before = Fx.body(target);
            target.setVelocity(Motion.clamp(direction.clone().multiply(speed).setY(FLIGHT_SINK), MAX_SPEED));
            target.setFallDistance(0);
            traveled += speed;
            follow(tick);
            Location ahead = before.clone().add(direction.clone().multiply(speed));
            Vfx.line(ctx(), before, ahead, 4, Particle.DUST_COLOR_TRANSITION, FADE, false);
            Vfx.trail(ahead, before.clone().subtract(direction.clone().multiply(1.5)), PALETTE.body(), 6, false);
            if (tick % 2 == 0) {
                Vfx.cloud(ctx(), before, Particle.WITCH, 2, 0.2, 0, null, false);
            }
            return true;
        }

        /** The bubble and the shards follow the target. */
        private void follow(long tick) {
            Location feet = target.getLocation();
            if (bubble != null && bubble.isValid()) {
                bubble.teleport(feet);
            }
            Location body = Fx.body(target);
            double radius = target.getWidth() * 0.5 + 0.55;
            for (int i = 0; i < SHARDS; i++) {
                ItemDisplay shard = shards[i];
                if (shard == null || !shard.isValid()) {
                    continue;
                }
                double angle = tick * 0.45 + i * Math.PI * 2 / SHARDS;
                Location at = body.clone().add(Math.cos(angle) * radius, Math.sin(tick * 0.3 + i * 2) * 0.35,
                        Math.sin(angle) * radius);
                at.setYaw((float) Math.toDegrees(angle));
                shard.teleport(at);
            }
        }

        private void land() {
            target.setVelocity(direction.clone().multiply(0.2).setY(Math.min(0, target.getVelocity().getY())));
            target.setFallDistance(0);
            Location feet = target.getLocation().add(0, 0.1, 0);
            Vfx.ring(ctx(), feet, 0.8, 12, 0, Particle.DUST_COLOR_TRANSITION, FADE, false);
            Fx.sound(feet, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 1.4f);
        }

        /** Crunch into a wall: hurt and stagger the target, the wall's face bursts. */
        private void wallSlam() {
            slammed = true;
            Player owner = player();
            Location body = Fx.body(target);
            Location contact = body.clone().add(direction.clone().multiply(target.getWidth() * 0.5 + 0.2));
            Block block = contact.getBlock();
            if (block.isPassable()) {
                block = contact.clone().add(direction.clone().multiply(0.5)).getBlock();
            }
            if (!block.getType().isAir()) {
                body.getWorld().spawnParticle(Particle.BLOCK_CRUMBLE, contact, Vfx.n(ctx(), 18), 0.3, 0.5, 0.3, 0.1,
                        block.getBlockData());
            }
            impactFx(contact);
            Fx.sound(contact, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.9f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(contact, Sound.BLOCK_ANVIL_LAND, 0.4f, 1.4f + Fx.jitter(0.1f));
            slam(target);
            if (Combat.isTargetable(ctx(), owner, target)) {
                target.setVelocity(direction.clone().multiply(-SLAM_BOUNCE).setY(0.2));
                target.setFallDistance(0);
            }
            owner.sendActionBar(Text.mm("<" + MAGENTA + ">✺ 벽에 충돌!</" + MAGENTA + "> <white>+"
                    + AbilityInfo.formatNumber(params().num("slam_damage")) + "</white>"));
        }

        /** Crunch into another enemy: both are hurt and staggered, the other is knocked on. */
        private void bodySlam(LivingEntity other) {
            slammed = true;
            Player owner = player();
            Location contact = Fx.body(target).add(Fx.body(other)).multiply(0.5);
            impactFx(contact);
            Fx.sound(contact, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.7f + Fx.jitter(0.1f));
            Fx.sound(contact, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.6f, 1.2f + Fx.jitter(0.1f));
            slam(target);
            slam(other);
            if (Combat.isTargetable(ctx(), owner, other)) {
                Motion.push(other, direction.clone().multiply(BODY_PUSH).setY(0.3), MAX_SPEED);
            }
            if (Combat.isTargetable(ctx(), owner, target)) {
                target.setVelocity(direction.clone().multiply(0.1).setY(0.15));
                target.setFallDistance(0);
            }
            owner.sendActionBar(Text.mm("<" + MAGENTA + ">✺ 충돌!</" + MAGENTA + "> <white>2명 +"
                    + AbilityInfo.formatNumber(params().num("slam_damage")) + "</white>"));
        }

        private void slam(LivingEntity victim) {
            Combat.hit(ctx(), player(), victim, params().num("slam_damage"));
            int slow = (int) Math.min(Integer.MAX_VALUE, params().ticks("slam_slow"));
            if (slow > 0 && Combat.isTargetable(ctx(), player(), victim)) {
                victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, SLAM_SLOW_AMPLIFIER, false,
                        true, true));
            }
        }

        private void impactFx(Location contact) {
            World world = contact.getWorld();
            world.spawnParticle(Particle.FLASH, contact, 1, 0, 0, 0, 0, PALETTE.core());
            Vfx.burst(ctx(), contact, PALETTE, 0.8, Particle.WITCH, false);
            Vector[] basis = basis(direction);
            for (int i = 0; i < 2; i++) {
                double radius = 0.5 + i * 0.45;
                int points = Vfx.n(ctx(), 12 + i * 4);
                for (int k = 0; k < points; k++) {
                    double angle = k * Math.PI * 2 / points;
                    Vector offset = basis[0].clone().multiply(Math.cos(angle) * radius)
                            .add(basis[1].clone().multiply(Math.sin(angle) * radius));
                    Vfx.dot(contact.clone().add(offset), Particle.DUST_COLOR_TRANSITION, i == 0 ? GLOW : FADE, false);
                }
            }
        }

        /** The loop ended by itself: the bubble bursts and the shards fly apart. */
        void finish() {
            Player owner = player();
            if (bubble != null && bubble.isValid()) {
                Location at = Fx.body(target);
                Vfx.cloud(ctx(), at, Particle.DUST, 12, 0.5, 0, PINK_DUST, false);
                Vfx.cloud(ctx(), at, Particle.END_ROD, slammed ? 8 : 3, 0.3, 0.08, null, false);
                Fx.sound(at, Sound.BLOCK_GLASS_BREAK, slammed ? 0.9f : 0.5f, 1.4f + Fx.jitter(0.1f));
                Displays.shrinkAway(ctx(), owner, bubble, 3);
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (ItemDisplay shard : shards) {
                if (shard == null || !shard.isValid()) {
                    continue;
                }
                Vector out = shard.getLocation().toVector().subtract(Fx.body(target).toVector());
                if (out.lengthSquared() < 1.0E-4) {
                    out = new Vector(random.nextGaussian(), 0.3, random.nextGaussian());
                }
                shard.setTeleportDuration(5);
                shard.teleport(shard.getLocation().add(out.normalize().multiply(1.4)).add(0, 0.4, 0));
                Displays.shrinkAway(ctx(), owner, shard, 5);
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: mind collapse

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = Math.max(1, params().num("ult_radius"));
        List<LivingEntity> victims = ctx().enemiesNear(owner, owner.getLocation(), radius);
        if (victims.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        new Collapse(victims, radius).begin();
        return ActivationResult.success();
    }

    /** The mind collapse: every victim staggers in random directions under a spinning brain coral crown. */
    private final class Collapse implements LongPredicate {

        private final List<LivingEntity> victims;
        private final double radius;
        private final Map<UUID, ItemDisplay> crowns = new HashMap<>();
        private final long duration;
        private final long joltEvery;

        Collapse(List<LivingEntity> victims, double radius) {
            this.victims = new ArrayList<>(victims);
            this.radius = radius;
            this.duration = Math.max(1, params().ticks("ult_duration"));
            this.joltEvery = Math.max(2, params().ticks("ult_jolt"));
        }

        void begin() {
            Player owner = player();
            scream(owner);
            double damage = params().num("ult_damage");
            int nausea = (int) Math.min(Integer.MAX_VALUE, duration + NAUSEA_EXTRA_TICKS);
            Location head = owner.getEyeLocation().add(0, 0.3, 0);
            for (LivingEntity victim : victims) {
                Combat.hit(ctx(), owner, victim, damage);
                Location victimHead = victim.getEyeLocation().add(0, 0.3, 0);
                Vfx.trail(head, victimHead, PALETTE.body(), 6, true);
                Vfx.trail(head.clone().add(0, 0.2, 0), victimHead, PALETTE.core(), 8, true);
                if (!Combat.isTargetable(ctx(), owner, victim)) {
                    continue;
                }
                victim.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, nausea, 0, false, true, true));
                if (victim instanceof Player player) {
                    ctx().silence(player, duration);
                    player.playSound(player, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.PLAYERS, 0.7f, 1.4f);
                }
                if (crowns.size() < MAX_CROWNS) {
                    ItemDisplay crown = Displays.item(ctx(), owner, crownPoint(victim, 0), CROWN,
                            Displays.centered(0, 0, 0, 0.1f, 0.1f, 0.1f, 0, 0, 0), duration + 12);
                    crown.setTeleportDuration(1);
                    Displays.animateNextTick(ctx(), owner, crown, crownPose(0.6f), 4);
                    crowns.put(victim.getUniqueId(), crown);
                }
            }
            owner.sendActionBar(Text.mm("<" + MAGENTA + ">✺ 정신 붕괴</" + MAGENTA + "> <white>" + victims.size()
                    + "명</white>"));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::end);
        }

        /** The psychic scream: a flash at the head, brain-wave spheres and a ring racing over the ground. */
        private void scream(Player owner) {
            Location chest = owner.getLocation().add(0, 1.2, 0);
            World world = owner.getWorld();
            world.spawnParticle(Particle.FLASH, chest, 1, 0, 0, 0, 0, PALETTE.body(), true);
            Vfx.shockwave(ctx(), owner, owner.getLocation().add(0, 0.1, 0), 0.5, radius, 7,
                    Particle.DUST_COLOR_TRANSITION, GLOW, true);
            Loop.repeat(ctx(), owner, 0, 1, 5, step -> {
                double r = 1.0 + step * 1.1;
                Vfx.sphere(ctx(), chest, r, 26 + (int) step * 6, Particle.DUST_COLOR_TRANSITION,
                        step < 2 ? GLOW : FADE, true);
                if (step % 2 == 0) {
                    Vfx.ring(ctx(), chest, r * 1.1, 18, step * 0.5, Particle.WITCH, null, true);
                }
                return true;
            });
            Vfx.cloud(ctx(), chest, Particle.ENCHANT, 30, 0.6, 1.0, null, true);
            Fx.sound(chest, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 1.3f);
            Fx.sound(chest, Sound.ENTITY_EVOKER_CAST_SPELL, 1.2f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(chest, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.8f);
            Fx.sound(chest, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 1.0f, 0.6f);
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            boolean jolt = (tick + 1) % joltEvery == 0;
            boolean anyone = false;
            for (LivingEntity victim : victims) {
                ItemDisplay crown = crowns.get(victim.getUniqueId());
                if (!Combat.isTargetable(ctx(), owner, victim)) {
                    if (crown != null && crown.isValid()) {
                        crown.remove();
                    }
                    continue;
                }
                anyone = true;
                if (crown != null && crown.isValid()) {
                    crown.teleport(crownPoint(victim, tick));
                }
                if (tick % 2 == 0) {
                    swirl(victim, tick);
                }
                if (jolt) {
                    jolt(victim, crown);
                }
            }
            return anyone;
        }

        private Location crownPoint(LivingEntity victim, long tick) {
            Location at = victim.getLocation().add(0, victim.getHeight() + 0.45 + Math.sin(tick * 0.35) * 0.08, 0);
            at.setYaw((float) (tick * 18 % 360));
            at.setPitch(0);
            return at;
        }

        /** Thoughts spinning out of control: a wobbling ring of dust around the head. */
        private void swirl(LivingEntity victim, long tick) {
            Location head = victim.getEyeLocation().add(0, 0.15, 0);
            double tilt = Math.sin(tick * 0.4) * 0.15;
            int points = Vfx.n(ctx(), 8);
            for (int i = 0; i < points; i++) {
                double angle = tick * 0.35 + i * Math.PI * 2 / points;
                Location at = head.clone().add(Math.cos(angle) * 0.45, Math.sin(angle) * tilt, Math.sin(angle) * 0.45);
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, i % 2 == 0 ? GLOW_SMALL : FADE, false);
            }
            if (tick % 6 == 0) {
                Vfx.dot(head.clone().add(0, 0.3, 0), Particle.WITCH, null, false);
            }
        }

        /** A stagger: a nudge in a random direction and a jerk of the view. */
        private void jolt(LivingEntity victim, @Nullable ItemDisplay crown) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double angle = random.nextDouble(Math.PI * 2);
            Vector push = new Vector(Math.cos(angle) * JOLT_SPEED, JOLT_LIFT, Math.sin(angle) * JOLT_SPEED);
            Motion.push(victim, push, MAX_SPEED);
            victim.setFallDistance(0);
            float yaw = (random.nextBoolean() ? 1 : -1) * (CAMERA_YAW * 0.5f + random.nextFloat() * CAMERA_YAW * 0.5f);
            float pitch = (random.nextFloat() * 2 - 1) * CAMERA_PITCH;
            if (victim instanceof Player player) {
                player.setRotation(Angle.relative(yaw), Angle.relative(pitch));
            } else {
                Location look = victim.getLocation();
                victim.setRotation(look.getYaw() + yaw, Math.clamp(look.getPitch() + pitch, -90f, 90f));
            }
            Location head = victim.getEyeLocation();
            Vfx.cloud(ctx(), head, Particle.WITCH, 5, 0.25, 0.05, null, false);
            Vfx.arc(ctx(), head, 0.6, (float) Math.toDegrees(angle) - 90, 120, 9, Particle.DUST, VIOLET_DUST, false);
            Fx.sound(head, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 0.6f + random.nextFloat() * 1.2f);
            if (crown != null && crown.isValid()) {
                Displays.animate(crown, crownPose(0.85f), 2);
                ctx().runLater(player(), 3, () -> {
                    if (crown.isValid()) {
                        Displays.animate(crown, crownPose(0.6f), 4);
                    }
                });
            }
        }

        /** The minds come back: each crown shatters in a pop of pink. */
        void end() {
            Player owner = player();
            for (LivingEntity victim : victims) {
                ItemDisplay crown = crowns.get(victim.getUniqueId());
                if (crown != null && crown.isValid()) {
                    Location at = crown.getLocation();
                    Vfx.cloud(ctx(), at, Particle.DUST, 10, 0.25, 0, PINK_DUST, false);
                    Vfx.cloud(ctx(), at, Particle.END_ROD, 3, 0.15, 0.05, null, false);
                    Fx.sound(at, Sound.BLOCK_CORAL_BLOCK_BREAK, 0.8f, 1.4f + Fx.jitter(0.1f));
                    Displays.shrinkAway(ctx(), owner, crown, 4);
                }
            }
        }
    }

    // ------------------------------------------------------------------ geometry helpers

    /** A box of {@code width} x {@code height} centered horizontally on the display's origin, from {@code bottom}. */
    private static Transformation box(float width, float bottom, float height) {
        return new Transformation(new Vector3f(-width / 2, bottom, -width / 2), new Quaternionf(),
                new Vector3f(width, height, width), new Quaternionf());
    }

    private static Transformation crownPose(float scale) {
        return Displays.centered(0, 0, 0, scale, scale, scale, 0, -15, 0);
    }

    /** Two unit vectors perpendicular to {@code axis} and to each other. */
    private static Vector[] basis(Vector axis) {
        Vector helper = Math.abs(axis.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = axis.clone().crossProduct(helper).normalize();
        Vector v = axis.clone().crossProduct(u).normalize();
        return new Vector[] {u, v};
    }
}
