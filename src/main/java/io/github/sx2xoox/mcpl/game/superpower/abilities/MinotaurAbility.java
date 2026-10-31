package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

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
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 미노타우로스 — the bull of the labyrinth. A run builds up a charge that turns the next blow into a gore, the red cloth
 * provokes an enemy into charging at the minotaur like a bull at a cape (and the horns meet it), and as the ultimate a
 * stone labyrinth rises around it that splits the enemies apart, while the minotaur rushes through its walls.
 *
 * <p>Look (bronze, blood red, labyrinth stone): two ivory horns with black tips on the minotaur's head (hidden from its
 * own view) that glow red and lower when the charge is up, steam snorting from its nose and dust kicked up by its
 * hooves. The cloth is a red cape with a gold rod waved in the right hand; the provoked enemy wears a red "!!" and
 * leaves a trail of dust as it is driven in, and the gore is an upward sweep of the horns with bronze sparks and a
 * spray of red. The labyrinth is first traced on the ground in grey dust, then rises ring by ring out of pillars of
 * stone dust (stone bricks, mossy and cracked, chiseled gate posts), and its walls explode into rubble where the
 * minotaur rushes through them; when it sinks back, the walls crumble.
 */
public final class MinotaurAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("minotaur", "미노타우로스", Material.NETHERITE_AXE)
            .color(TextColor.color(0xC8803A))
            .difficulty(2)
            .passive("황소",
                    "{charge_time}초 넘게 달리면 콧김을 뿜으며 돌진 기세가 오릅니다(달리기를 멈춰도 {charge_linger}초 "
                            + "유지). 기세가 오른 채 가하는 다음 근접 공격은 들이받기가 되어 피해가 {gore_bonus}% 늘고 적을 "
                            + "약 {gore_knockback}블록 날려 보냅니다.")
            .active("붉은 천",
                    "조준한 적({cloth_range}블록 이내)에게 붉은 천을 흔들어 도발합니다. 그 적은 최대 {taunt_duration}초간 "
                            + "투우처럼 나에게 돌진하고, 나와 부딪히거나 나를 근접 공격하는 순간 뿔에 받혀 {counter_damage} "
                            + "피해를 입고 높이 떠올라 날아갑니다. 도발 중 그 적에게 받는 피해는 {counter_block}% 줄어듭니다.",
                    10)
            .ultimate("미궁",
                    "주위 반경 {maze_radius}블록에 {maze_duration}초간 돌벽 미궁이 솟아 적을 갈라놓습니다(미궁에는 늘 "
                            + "빠져나갈 틈이 있습니다). 그동안 이동 속도가 {maze_speed}% 빨라지고 돌진 기세가 늘 올라 있으며, "
                            + "액티브가 {rush_cooldown}초마다 쓸 수 있는 미궁 돌진으로 바뀌어 바라보는 방향으로 "
                            + "{rush_distance}블록 돌진하며 미궁 벽을 부수고, 부딪힌 적에게 {rush_damage} 피해를 주고 뿔로 받아 "
                            + "날려 보냅니다. 전투 맵에서는 앞을 막는 벽과 지형도 폭 {rush_block_width}블록으로 뚫고 지나갑니다.")
            .ultimateNotStandalone()
            .param("charge_time", 1.2)
            .param("charge_linger", 1)
            .param("gore_bonus", 40)
            .param("gore_knockback", 5)
            .param("cloth_range", 14)
            .param("taunt_duration", 1.2)
            .param("counter_damage", 5)
            .param("counter_block", 50)
            .param("maze_radius", 9)
            .param("maze_duration", 7)
            .param("maze_speed", 25)
            .param("rush_cooldown", 2.5)
            .param("rush_distance", 10)
            .param("rush_damage", 3.5)
            .param("rush_block_width", 3)
            .build();

    private static final String BRONZE_TAG = "#C8803A";
    private static final String BLOOD_TAG = "#B3261E";

    // ------------------------------------------------------------------ look
    /** Hot bronze highlight, bronze, the dark red of blood. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFD9A0, 0xB5652B, 0x5A0E0E);
    private static final Color BLOOD = Color.fromRGB(0xB3261E);
    private static final Color BRONZE = Color.fromRGB(0xC8803A);
    private static final Particle.DustOptions BLOOD_DUST = new Particle.DustOptions(BLOOD, 1.0f);
    private static final Particle.DustTransition BRONZE_GLOW = PALETTE.glow(1.0f);
    private static final Particle.DustTransition EMBER = new Particle.DustTransition(BRONZE, BLOOD, 1.1f);
    private static final Particle.DustOptions STONE_DUST = new Particle.DustOptions(Color.fromRGB(0x8E8A7E), 1.3f);

    /** Block types of the visuals and the labyrinth; block data is created on use (it needs a running server). */
    private static final Material HORN = Material.BONE_BLOCK;
    private static final Material HORN_TIP = Material.POLISHED_BLACKSTONE;
    private static final Material CLOTH = Material.RED_WOOL;
    private static final Material ROD = Material.GOLD_BLOCK;
    private static final Material WALL = Material.STONE_BRICKS;
    private static final Material MOSSY_WALL = Material.MOSSY_STONE_BRICKS;
    private static final Material CRACKED_WALL = Material.CRACKED_STONE_BRICKS;
    private static final Material GATE_POST = Material.CHISELED_STONE_BRICKS;

    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 2.0;
    private static final double REST_FALL = -Motion.GRAVITY * Motion.DRAG;

    // passive: bull
    private static final long SNORT_PERIOD = 12;
    private static final long HOOF_PERIOD = 4;
    private static final double GORE_LIFT = 0.42;
    /** Ticks a gored enemy flies before it lands (vanilla air drag 0.91, see {@code GoatAbility}). */
    private static final int GORE_AIR_TICKS = 11;
    private static final double AIR_DRAG = 0.91;
    /** The heated horn tips while the charge is up (a lit block, never an outline: that shows through walls). */
    private static final Material HOT_TIP = Material.REDSTONE_BLOCK;

    // active: red cloth
    private static final double TAUNT_SPEED = 0.6;
    /** The provoked enemy meets the horns this close (between the two middles, horizontally). */
    private static final double CONTACT_DISTANCE = 1.7;
    private static final double TOSS_HEIGHT = 2.5;
    private static final double TOSS_PUSH = 0.6;
    /** A provoked blow is countered only from this close (horizontally, plus the attacker's ping): melee reach. */
    private static final double COUNTER_REACH = 4.0;
    private static final int CLOTH_FADE_TICKS = 5;

    // ultimate: labyrinth
    private static final double RING_SPACING = 3.0;
    private static final int GAPS_PER_RING = 3;
    /** Width (blocks) of the openings in a ring. */
    private static final double GAP_WIDTH = 2.4;
    private static final int WALL_HEIGHT = 2;
    private static final long TRACE_TICKS = 10;
    private static final long RING_STAGGER = 3;
    private static final long CRUMBLE_LEAD = 2;
    // the rush
    private static final double RUSH_SPEED = 0.9;
    private static final long RUSH_GRACE_TICKS = 3;
    private static final double STALL_EPSILON = 0.12;
    private static final int STALL_LIMIT = 3;
    private static final long MAX_LAG_TICKS = 8;
    /** Bodies keep this much room from a rising wall, plus what their client may be ahead of the server. */
    private static final double BODY_MARGIN = 0.05;
    private static final double LAG_MARGIN_PER_TICK = 0.3;
    private static final double MAX_LAG_MARGIN = 1.2;
    /** Walls closer than this to the rush's path (horizontally) are smashed. */
    private static final double SMASH_REACH = 1.25;
    /**
     * The rush tunnels through terrain along a line this high over the bottom of the block its feet are in (between
     * the feet and head blocks), so the floor under the hooves (a slab or a stair too) is never touched.
     */
    private static final double RUSH_TUNNEL_HEIGHT = 1.0;
    /** The tunnel ends this far past the end of the rush (the front of the body). */
    private static final double TUNNEL_LEAD = 0.3;
    /** The way ahead is checked (and the tunnel cut) in steps of this many blocks. */
    private static final double WAY_STEP = 0.4;
    private static final double GORE_AHEAD = 1.2;
    private static final double GORE_REACH = 1.4;
    private static final long LANDING_GUARD_TICKS = 100;

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final BlockDisplay[] horns = new BlockDisplay[4];
    private final LandingGuard landing = new LandingGuard();
    private int sprintTicks;
    private long chargedUntil = Long.MIN_VALUE / 2;
    private boolean heated;
    private @Nullable Taunt taunt;
    private @Nullable Labyrinth maze;
    private boolean rushing;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        Player owner = player();
        if (!reserve(horns.length)) {
            return;
        }
        Location eye = owner.getEyeLocation();
        for (int i = 0; i < horns.length; i++) {
            Material material = i % 2 == 0 ? HORN : HORN_TIP;
            horns[i] = keep(Displays.block(ctx(), owner, eye, material.createBlockData(),
                    hornPose(i, eye.getYaw(), 0f, 0.05f), 0));
            horns[i].setTeleportDuration(1);
            // The horns sit on its own head and would block its view: only the others see them.
            owner.hideEntity(ctx().plugin(), horns[i]);
        }
    }

    @Override
    public void onRevoke() {
        taunt = null;
        maze = null;
        rushing = false;
        live.clear();
        landing.disarm();
    }

    // ------------------------------------------------------------------ passive: bull

    /** Charged: the next melee blow is a gore. Always during the labyrinth. */
    private boolean charged() {
        return maze != null || ctx().currentTick() < chargedUntil;
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        landing.update(owner, now);
        boolean sprinting = owner.isSprinting();
        boolean was = charged();
        if (sprinting) {
            sprintTicks++;
            if (sprintTicks >= params().ticks("charge_time")) {
                chargedUntil = now + Math.max(1, params().ticks("charge_linger"));
            }
        } else {
            sprintTicks = 0;
        }
        boolean charged = charged();
        if (charged && !was) {
            chargeUp(owner);
        }
        if (charged != heated) {
            heated = charged;
            // The tips heat up red (no outline: it would show the minotaur through walls).
            for (int i = 1; i < horns.length; i += 2) {
                BlockDisplay tip = horns[i];
                if (tip != null && tip.isValid()) {
                    tip.setBlock((charged ? HOT_TIP : HORN_TIP).createBlockData());
                }
            }
        }
        if (charged && tick % 4 == 0) {
            hornEmbers(owner);
        }
        poseHorns(owner, tick, charged);
        if (charged && tick % SNORT_PERIOD == 0) {
            snort(owner, false);
        }
        if (sprinting && tick % HOOF_PERIOD == 0 && Motion.isGrounded(owner)) {
            Block ground = owner.getLocation().subtract(0, 0.2, 0).getBlock();
            if (!ground.isPassable()) {
                owner.getWorld().spawnParticle(Particle.BLOCK, owner.getLocation().add(0, 0.1, 0), Vfx.n(ctx(), 3),
                        0.2, 0.05, 0.2, 0, ground.getBlockData());
            }
        }
        Labyrinth labyrinth = maze;
        if (labyrinth != null) {
            labyrinth.ambient(tick);
        }
    }

    private void poseHorns(Player owner, long tick, boolean lowered) {
        Location eye = owner.getEyeLocation();
        float yaw = eye.getYaw();
        float grow = Math.min(1f, (tick + 1) / 8f);
        float dip = lowered || rushing ? 30f : 0f;
        for (int i = 0; i < horns.length; i++) {
            BlockDisplay horn = horns[i];
            if (horn == null || !horn.isValid() || !horn.getWorld().equals(owner.getWorld())) {
                continue;
            }
            Displays.moveTo(horn, eye);
            Displays.animate(horn, hornPose(i, yaw, dip, grow), 2);
        }
    }

    /**
     * Horn part {@code index} (0 left base, 1 left tip, 2 right base, 3 right tip) on a head turned to {@code yaw},
     * dipped forward by {@code dip} degrees: the base runs out and up from the temple, the black tip curls up and
     * forward. Built in head space (+Z front, +X left) and turned in the transformation only.
     */
    private static Transformation hornPose(int index, float yaw, float dip, float grow) {
        boolean left = index < 2;
        float side = left ? 1f : -1f;
        boolean tip = index % 2 == 1;
        Vector3f start = tip ? new Vector3f(0.44f * side, 0.2f, 0.06f) : new Vector3f(0.2f * side, 0.12f, 0.02f);
        Vector3f along = tip ? new Vector3f(0.06f * side, 0.26f, 0.13f) : new Vector3f(0.25f * side, 0.08f, 0.04f);
        Quaternionf head = yawTurn(yaw).rotateX((float) Math.toRadians(dip));
        Vector3f from = head.transform(new Vector3f(start).mul(grow));
        Vector3f to = head.transform(new Vector3f(along).mul(grow));
        return beam(from, to, (tip ? 0.07f : 0.1f) * Math.max(0.05f, grow));
    }

    /** Embers off the heated horn tips (seen by the others: the horns are hidden from the minotaur itself). */
    private void hornEmbers(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector forward = Motion.horizontalFacing(eye);
        Vector side = new Vector(-forward.getZ(), 0, forward.getX()).multiply(0.5);
        Location top = eye.clone().add(0, 0.38, 0).add(forward.multiply(0.1));
        int which = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        Vfx.dot(top.clone().add(side.multiply(which)), Particle.DUST_COLOR_TRANSITION, EMBER, false);
    }

    /** The charge is up: a snort, a stamp and the horns flare red. */
    private void chargeUp(Player owner) {
        snort(owner, true);
        Location feet = owner.getLocation().add(0, 0.1, 0);
        Vfx.ring(ctx(), feet, 0.7, 12, 0, Particle.DUST_COLOR_TRANSITION, EMBER, false);
        Fx.sound(feet, Sound.ENTITY_RAVAGER_STEP, 0.8f, 0.8f + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<" + BLOOD_TAG + ">🐂 돌진 기세!</" + BLOOD_TAG + "> <gray>다음 근접 공격이 "
                + "들이받기가 됩니다</gray>"));
    }

    /** Two puffs of steam from the nostrils. */
    private void snort(Player owner, boolean loud) {
        Location eye = owner.getEyeLocation();
        Vector forward = Motion.horizontalFacing(eye);
        Vector side = new Vector(-forward.getZ(), 0, forward.getX()).multiply(0.08);
        Location nose = eye.clone().add(forward.clone().multiply(0.38)).subtract(0, 0.18, 0);
        World world = eye.getWorld();
        for (int s = -1; s <= 1; s += 2) {
            Location nostril = nose.clone().add(side.clone().multiply(s));
            Vector puff = forward.clone().multiply(0.12).add(new Vector(0, -0.04, 0)).add(side.clone().multiply(s));
            world.spawnParticle(Particle.CLOUD, nostril, 0, puff.getX(), puff.getY(), puff.getZ(), 1.0);
            world.spawnParticle(Particle.WHITE_SMOKE, nostril, 0, puff.getX() * 0.6, puff.getY(), puff.getZ() * 0.6,
                    1.0);
        }
        Fx.sound(nose, Sound.ENTITY_HORSE_BREATHE, loud ? 1.0f : 0.5f, 0.6f + Fx.jitter(0.05f));
        if (loud) {
            Fx.sound(nose, Sound.ENTITY_HORSE_ANGRY, 0.7f, 0.5f + Fx.jitter(0.05f));
        }
    }

    /** The owner's own melee swing at an enemy (not ability damage, projectiles or summon bites). */
    private boolean isOwnMelee(EntityDamageByEntityEvent event, LivingEntity victim) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && event.getCause() == DamageCause.ENTITY_ATTACK && ctx().isEnemy(player(), victim);
    }

    /** The gore bonus: only changes the damage, since the victim may still cancel the hit. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !isOwnMelee(event, victim) || !charged()) {
            return;
        }
        double bonus = Math.max(0, params().num("gore_bonus")) / 100.0;
        event.setDamage(event.getDamage() * (1 + bonus));
    }

    /** The gore landed: the charge is spent and the victim is thrown. */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnMelee(event, victim) || !charged()) {
            return;
        }
        Player owner = player();
        if (maze == null) {
            chargedUntil = Long.MIN_VALUE / 2;
            sprintTicks = 0;
        }
        Vector away = Motion.horizontal(victim.getLocation().toVector().subtract(owner.getLocation().toVector()));
        if (away.lengthSquared() < 1.0E-6) {
            away = Motion.horizontalFacing(owner.getLocation());
        }
        // The speed that carries it gore_knockback blocks through the air (drag 0.91 a tick) before it lands.
        double distance = Math.max(0, params().num("gore_knockback"));
        double speed = distance * (1 - AIR_DRAG) / (1 - Math.pow(AIR_DRAG, GORE_AIR_TICKS));
        Vector throwing = away.multiply(speed).setY(GORE_LIFT);
        // Vanilla knockback is applied after this event; throw on the next tick so it is not overwritten.
        ctx().runLater(owner, 1, () -> {
            if (Combat.isTargetable(ctx(), owner, victim)) {
                Motion.push(victim, throwing, MAX_SPEED);
            }
        });
        goreFx(owner, victim, "<b>들이받기!</b>");
    }

    /** The horns sweep up through the victim: bronze sparks, a spray of red and a bellow. */
    private void goreFx(Player owner, LivingEntity victim, String label) {
        Location body = Fx.body(victim);
        Fx.sound(body, Sound.ENTITY_RAVAGER_ATTACK, 1.0f, 0.9f + Fx.jitter(0.08f));
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_HORSE_ANGRY, 0.6f, 0.6f + Fx.jitter(0.05f));
        if (!seen(owner, victim)) {
            return; // nothing may give away a victim hidden from the minotaur
        }
        Vector toward = Motion.horizontal(body.toVector().subtract(owner.getLocation().toVector()));
        Vector[] plane = {toward.lengthSquared() < 1.0E-6 ? new Vector(0, 0, 1) : toward, new Vector(0, 1, 0)};
        World world = body.getWorld();
        int points = Vfx.n(ctx(), 12);
        for (int i = 0; i < points; i++) {
            double angle = Math.toRadians(-70 + 140.0 * i / Math.max(1, points - 1));
            Location point = body.clone().subtract(plane[0].clone().multiply(0.6))
                    .add(plane[0].clone().multiply(Math.cos(angle) * 0.9)).add(plane[1].clone().multiply(Math.sin(angle)
                            * 0.9));
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, point, 1, 0, 0, 0, 0, i % 2 == 0 ? BRONZE_GLOW : EMBER);
        }
        Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.CRIT, false);
        Vfx.cloud(ctx(), body, Particle.DUST, 8, 0.3, 0, BLOOD_DUST, false);
        popText(body.clone().add(0, 0.9, 0), "<gradient:#FFD9A0:#B3261E>" + label + "</gradient>");
    }

    private void popText(Location at, String mini) {
        if (!reserve(1)) {
            return;
        }
        Player owner = player();
        TextDisplay text = keep(Displays.text(ctx(), owner, at, Text.mm(mini), 0.5f, 18));
        Displays.animateNextTick(ctx(), owner, text, Displays.centered(0, 0.35f, 0, 1.1f, 1.1f, 1.1f, 0, 0, 0), 4);
        ctx().runLater(owner, 12, () -> {
            if (text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 5);
            }
        });
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (landing.absorb(event, ctx().currentTick())) {
            return;
        }
        Taunt current = taunt;
        if (current == null || current.done || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Entity damager = byEntity.getDamager();
        Player attacker = ctx().resolveAttacker(damager);
        UUID id = current.target.getUniqueId();
        if (!damager.getUniqueId().equals(id) && (attacker == null || !attacker.getUniqueId().equals(id))) {
            return;
        }
        // Every blow of the provoked bull is blunted...
        double block = Math.clamp(params().num("counter_block") / 100.0, 0.0, 1.0);
        event.setDamage(event.getDamage() * (1 - block));
        // ...and one struck at arm's length walks it right onto the horns: the counter comes at once.
        if (current.inReach(damager, event.getCause())) {
            ctx().runLater(player(), 1, () -> current.counter());
        }
    }

    // ------------------------------------------------------------------ active: red cloth / labyrinth rush

    @Override
    public double cooldownMultiplier() {
        if (maze == null) {
            return 1.0;
        }
        double base = params().cooldownSeconds();
        return base <= 1.0E-3 ? 1.0 : Math.max(0, params().num("rush_cooldown")) / base;
    }

    @Override
    public ActivationResult onActive() {
        if (maze != null) {
            if (rushing) {
                return ActivationResult.fail("이미 돌진 중입니다");
            }
            return rush();
        }
        Player owner = player();
        LivingEntity target = ctx().findTarget(owner, params().num("cloth_range"));
        if (target == null || !seen(owner, target)) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        Taunt provoked = new Taunt(owner, target);
        taunt = provoked;
        provoked.wave();
        return ActivationResult.success();
    }

    /**
     * The red cloth: the provoked enemy is driven at the minotaur like a bull at a cape (steered by dead reckoning, see
     * {@code MagnetAbility}, so a player lagging behind on the server is not yanked past it) until it meets the horns,
     * strikes the minotaur, or the provocation runs out.
     */
    private final class Taunt implements LongPredicate {

        private final Player owner;
        private final LivingEntity target;
        private final long duration;
        private final List<BlockDisplay> cloth = new ArrayList<>();
        private @Nullable TextDisplay rage;
        private double x;
        private double z;
        boolean done;

        Taunt(Player owner, LivingEntity target) {
            this.owner = owner;
            this.target = target;
            this.duration = Math.max(1, params().ticks("taunt_duration"));
            Location at = target.getLocation();
            this.x = at.getX();
            this.z = at.getZ();
        }

        void wave() {
            Location hand = clothHand(owner);
            if (reserve(2)) {
                for (Material material : new Material[] {CLOTH, ROD}) {
                    BlockDisplay part = keep(Displays.block(ctx(), owner, hand, material.createBlockData(),
                            clothPose(material == ROD, owner.getEyeLocation().getYaw(), 0f, 0.05f),
                            duration + CLOTH_FADE_TICKS + 4));
                    part.setTeleportDuration(1);
                    cloth.add(part);
                }
            }
            if (seen(owner, target) && reserve(1)) {
                rage = keep(Displays.text(ctx(), owner, overHead(target), Text.mm("<b><" + BLOOD_TAG + ">!!</"
                        + BLOOD_TAG + "></b>"), 0.5f, duration + 4));
                rage.setTeleportDuration(1);
                Displays.animateNextTick(ctx(), owner, rage, Displays.centered(0, 0, 0, 1.4f, 1.4f, 1.4f, 0, 0, 0), 3);
            }
            Location body = Fx.body(target);
            Vfx.line(ctx(), hand, body, 2, Particle.DUST, BLOOD_DUST, false);
            Vfx.cloud(ctx(), body, Particle.ANGRY_VILLAGER, 3, 0.3, 0, null, false);
            Fx.sound(hand, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.2f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(owner.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.8f, 1.1f + Fx.jitter(0.05f));
            Fx.sound(body, Sound.ENTITY_HOGLIN_ANGRY, 0.9f, 0.8f + Fx.jitter(0.05f));
            if (target instanceof Player provoked) {
                provoked.sendActionBar(Text.mm("<" + BLOOD_TAG + "><b>도발!</b></" + BLOOD_TAG + "> <gray>붉은 천을 향해 "
                        + "돌진합니다</gray>"));
            }
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::finish);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            if (!Combat.isTargetable(ctx(), owner, target) || !target.getWorld().equals(owner.getWorld())) {
                done = true;
                return false;
            }
            waveCloth(tick);
            Location feet = owner.getLocation();
            Location actual = target.getLocation();
            // The server sees a player a round trip late; beyond that the reckoning lost it (a wall stopped it).
            double slack = TAUNT_SPEED * ((target instanceof Player lagging ? lagTicks(lagging) : 0) + 2) + 0.5;
            if (Math.hypot(actual.getX() - x, actual.getZ() - z) > slack) {
                x = actual.getX();
                z = actual.getZ();
            }
            Vector to = new Vector(feet.getX() - x, 0, feet.getZ() - z);
            double distance = to.length();
            if (distance <= CONTACT_DISTANCE && horizontal(feet, actual) <= CONTACT_DISTANCE + slack
                    && Spots.clearPath(Fx.body(owner), Fx.body(target))) {
                counter();
                return false;
            }
            double step = Math.min(TAUNT_SPEED, Math.max(0.2, distance - CONTACT_DISTANCE + 0.3));
            Vector velocity = Motion.horizontal(to).multiply(step);
            boolean grounded = Motion.isGrounded(target) || target.isInWater();
            velocity.setY(grounded ? 0.08 : 0.0);
            Vector sent = Motion.clamp(velocity, MAX_SPEED);
            if (grounded || !(target instanceof Player)) {
                x += sent.getX();
                z += sent.getZ();
                target.setVelocity(sent);
            } else {
                // A player in the air is left to its own fall (its server velocity is stale).
                x = actual.getX();
                z = actual.getZ();
            }
            target.setFallDistance(0);
            boolean shown = seen(owner, target);
            TextDisplay mark = rage;
            if (mark != null && mark.isValid()) {
                Displays.moveTo(mark, overHead(target).add(Fx.jitter(0.06f), 0, Fx.jitter(0.06f)));
                mark.setViewRange(shown ? 1.5f : 0f);
            }
            if (shown) {
                Location heels = target.getLocation().add(0, 0.1, 0);
                Block ground = heels.clone().subtract(0, 0.3, 0).getBlock();
                if (!ground.isPassable()) {
                    target.getWorld().spawnParticle(Particle.BLOCK, heels, Vfx.n(ctx(), 2), 0.15, 0.05, 0.15, 0,
                            ground.getBlockData());
                }
                Vfx.dot(Fx.body(target), Particle.DUST, BLOOD_DUST, false);
                if (tick % 4 == 0) {
                    Fx.sound(heels, Sound.ENTITY_RAVAGER_STEP, 0.4f, 1.4f + Fx.jitter(0.1f));
                }
            }
            return true;
        }

        /** A melee blow of the provoked enemy itself, struck within reach and with nothing in between. */
        boolean inReach(Entity damager, DamageCause cause) {
            if (!damager.equals(target)
                    || cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK) {
                return false;
            }
            double lag = target instanceof Player lagging ? lagTicks(lagging) : 0;
            return horizontal(owner.getLocation(), target.getLocation()) <= COUNTER_REACH + TAUNT_SPEED * lag
                    && Spots.clearPath(Fx.body(owner), Fx.body(target));
        }

        private void waveCloth(long tick) {
            Location hand = clothHand(owner);
            float yaw = owner.getEyeLocation().getYaw();
            float swing = (float) Math.sin(tick * 0.7) * 28f;
            for (int i = 0; i < cloth.size(); i++) {
                BlockDisplay part = cloth.get(i);
                if (part.isValid()) {
                    Displays.moveTo(part, hand);
                    Displays.animate(part, clothPose(i == 1, yaw, swing, 1f), 1);
                }
            }
            if (tick % 3 == 0) {
                Vfx.dot(hand.clone().add(0, -0.3, 0), Particle.DUST, BLOOD_DUST, false);
            }
        }

        /** The horns meet the provoked enemy: it is hurt and tossed high over the minotaur's head. */
        void counter() {
            if (done) {
                return;
            }
            done = true;
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return;
            }
            Combat.hit(ctx(), owner, target, params().num("counter_damage"));
            if (Combat.isTargetable(ctx(), owner, target)) {
                Vector away = Motion.horizontal(target.getLocation().toVector().subtract(owner.getLocation().toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    away = Motion.horizontalFacing(owner.getLocation());
                }
                Motion.push(target, away.multiply(TOSS_PUSH).setY(Motion.launchSpeed(TOSS_HEIGHT)), MAX_SPEED);
                target.setFallDistance(0);
            }
            goreFx(owner, target, "<b>반격!</b>");
            Location feet = target.getLocation().add(0, 0.1, 0);
            Vfx.shockwave(ctx(), owner, feet, 0.3, 1.8, 5, Particle.DUST_COLOR_TRANSITION, EMBER, false);
            Fx.sound(feet, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.7f);
            owner.sendActionBar(Text.mm("<" + BRONZE_TAG + ">🐂 붉은 천</" + BRONZE_TAG + "> <white>반격 들이받기!</white>"));
            finish();
        }

        /** The provocation ends: the cloth is swept away. */
        void finish() {
            done = true;
            for (BlockDisplay part : cloth) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, CLOTH_FADE_TICKS);
                }
            }
            TextDisplay mark = rage;
            if (mark != null && mark.isValid()) {
                Displays.shrinkAway(ctx(), owner, mark, 3);
            }
            if (taunt == this) {
                taunt = null;
            }
        }
    }

    /** In front of the right shoulder, where the cloth is held. */
    private static Location clothHand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector forward = Motion.horizontalFacing(eye);
        Vector right = new Vector(-forward.getZ(), 0, forward.getX()).multiply(-1);
        return eye.add(forward.multiply(0.55)).add(right.multiply(0.45)).subtract(0, 0.35, 0);
    }

    /**
     * The cape (a thin red sheet hanging from the rod) or the gold rod, held across the front and swung by
     * {@code swing} degrees about the vertical; turned with the head's yaw in the transformation.
     */
    private static Transformation clothPose(boolean rod, float yaw, float swing, float grow) {
        Quaternionf turn = yawTurn(yaw).rotateY((float) Math.toRadians(swing));
        float g = Math.max(0.05f, grow);
        if (rod) {
            return box(turn.transform(new Vector3f(0.25f * g, 0.05f, 0)), new Vector3f(0.9f * g, 0.05f, 0.05f), turn);
        }
        return box(turn.transform(new Vector3f(0.25f * g, -0.35f * g, 0)), new Vector3f(0.85f * g, 0.75f * g, 0.03f),
                turn);
    }

    private static Location overHead(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() + 0.5, 0);
    }

    // ------------------------------------------------------------------ the labyrinth rush

    private ActivationResult rush() {
        Player owner = player();
        Location start = owner.getLocation();
        Vector direction = Motion.horizontalFacing(start);
        double distance = Spots.arenaReach(ctx(), start, direction, Math.max(1, params().num("rush_distance")));
        if (distance < 1.5) {
            return ActivationResult.fail("자기장 밖으로는 돌진할 수 없습니다");
        }
        if (!Spots.hasGroundBelow(start.getWorld(), start.clone().add(direction.clone().multiply(RUSH_SPEED + 0.6)))) {
            return ActivationResult.fail("앞이 낭떠러지입니다");
        }
        Rush run = new Rush(owner, direction, distance);
        rushing = true;
        run.launch();
        long lag = lagTicks(owner);
        Loop.repeat(ctx(), owner, 1, 1, (long) Math.ceil(distance / RUSH_SPEED) + 6 + lag, run, run::end);
        return ActivationResult.success();
    }

    /**
     * The distance from the rush's line within which blocks break for a tunnel {@code width} blocks wide: the centers
     * of the feet and head blocks lie half a block below and above the line, the outermost ones
     * {@code (width - 1) / 2} to its side. The floor under the hooves (1.5 below) always stays: a charge never digs
     * more than one block below its line.
     */
    private static double tunnelRadius(double width) {
        double side = Math.max(0, (width - 1) / 2);
        return Math.sqrt(side * side + 0.25) + 0.01;
    }

    /** Ticks the server sees the owner's movement late: one round trip, capped. */
    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / 50.0));
    }

    /**
     * One labyrinth rush, measured on the steps sent to the owner (its client moves by them at once, the server
     * sees it there only a round trip later, see {@code LancerAbility}): it smashes the labyrinth walls ahead of
     * where it is being carried, gores every enemy it meets once, and stops at real walls and at the edge of the void.
     */
    private final class Rush implements LongPredicate {

        private final Player owner;
        private final Vector direction;
        private final double distance;
        private final long lag;
        private final Set<UUID> gored = new HashSet<>();
        private Location start;
        private Location last;
        private double sent;
        /** How far from the start the tunnel through real terrain has been cut. */
        private double tunnelled;
        /** How far from the start the first real wall that stays standing is (the rush ends there). */
        private double wall = Double.POSITIVE_INFINITY;
        private int stalls;
        private boolean moved;
        private boolean ended;

        Rush(Player owner, Vector direction, double distance) {
            this.owner = owner;
            this.direction = direction;
            this.distance = distance;
            this.lag = lagTicks(owner);
            this.start = owner.getLocation();
            this.last = start;
        }

        void launch() {
            sent = Math.min(RUSH_SPEED, distance);
            owner.setVelocity(direction.clone().multiply(sent).setY(REST_FALL));
            owner.setFallDistance(0);
            smashAhead();
            Location chest = Fx.body(owner);
            Vfx.dot(chest, Particle.FLASH, BRONZE, false);
            owner.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, start.clone().add(0, 0.2, 0), 1, 0, 0, 0, 0,
                    null, false);
            Fx.sound(start, Sound.ENTITY_RAVAGER_ROAR, 1.0f, 1.0f + Fx.jitter(0.05f));
            Fx.sound(start, Sound.ENTITY_HORSE_ANGRY, 0.8f, 0.6f);
        }

        @Override
        public boolean test(long tick) {
            Location now = owner.getLocation();
            if (!now.getWorld().equals(start.getWorld()) || sent >= distance - 0.05) {
                return false;
            }
            if (!moved && horizontal(start, now) >= STALL_EPSILON) {
                moved = true;
            }
            if (moved || tick >= RUSH_GRACE_TICKS + lag) {
                stalls = horizontal(last, now) < STALL_EPSILON ? stalls + 1 : 0;
                if (stalls >= STALL_LIMIT) {
                    return false;
                }
            }
            last = now;
            Location ahead = start.clone().add(direction.clone().multiply(sent + RUSH_SPEED + 0.6));
            ahead.setY(now.getY());
            if (!Spots.hasGroundBelow(now.getWorld(), ahead)) {
                return false;
            }
            double step = Math.min(RUSH_SPEED, distance - sent);
            sent += step;
            owner.setVelocity(direction.clone().multiply(step).setY(REST_FALL));
            owner.setFallDistance(0);
            smashAhead();
            gore(now);
            trail(now, tick);
            return true;
        }

        /**
         * Smashes the labyrinth walls in the way of where the rush is carrying the owner (plus its ping's worth), and
         * on battle maps a tunnel through the real walls and terrain in the way: cut step by step while the way is
         * checked, so it never runs past a block that stays standing (the rush stops there) nor past the end of the
         * rush.
         */
        private void smashAhead() {
            Labyrinth labyrinth = maze;
            if (labyrinth == null) {
                return;
            }
            double back = Math.max(0, sent - RUSH_SPEED);
            if (back >= wall) {
                return; // stopped by a real wall: nothing behind it may break
            }
            Location from = start.clone().add(direction.clone().multiply(back));
            double reach = Math.min(sent + RUSH_SPEED * (2 + lag) + 0.8, wall) - back;
            double width = params().num("rush_block_width");
            double tunnelEnd = width > 0 ? Math.min(back + reach, distance + TUNNEL_LEAD) : 0;
            // The block the feet are in (a slab or a stair under them rounds up to the block above it).
            double feet = Math.floor(start.getY() + 0.5);
            for (double d = 0; d <= reach; d += WAY_STEP) {
                double along = back + d;
                if (along > tunnelled && tunnelled < tunnelEnd) {
                    double end = Math.min(tunnelEnd, along);
                    Location line = start.clone().add(direction.clone().multiply(tunnelled));
                    line.setY(feet + RUSH_TUNNEL_HEIGHT);
                    ctx().breakBlocksAlong(owner, line, line.clone().add(direction.clone().multiply(end - tunnelled)),
                            tunnelRadius(width));
                    tunnelled = end;
                }
                // Never past a real wall: the rush stops there, and nothing behind it may break.
                Location at = start.clone().add(direction.clone().multiply(along));
                at.setY(feet + 0.5);
                Block body = at.getBlock();
                if (labyrinth.blocksTheWay(body) || labyrinth.blocksTheWay(body.getRelative(0, 1, 0))) {
                    reach = d;
                    wall = along;
                    break;
                }
            }
            labyrinth.smash(from, direction, reach, start.getY());
        }

        private void gore(Location now) {
            Location point = now.clone().add(0, owner.getHeight() * 0.55, 0).add(direction.clone().multiply(GORE_AHEAD));
            double damage = params().num("rush_damage");
            for (LivingEntity enemy : ctx().enemiesNear(owner, point, GORE_REACH)) {
                if (!gored.add(enemy.getUniqueId())) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Vector side = new Vector(-direction.getZ(), 0, direction.getX());
                    Vector offset = enemy.getLocation().toVector().subtract(now.toVector());
                    if (offset.dot(side) < 0) {
                        side.multiply(-1);
                    }
                    Vector toss = direction.clone().multiply(0.7).add(side.multiply(0.55))
                            .setY(Motion.launchSpeed(2.0));
                    Motion.push(enemy, toss, MAX_SPEED);
                }
                goreFx(owner, enemy, "<b>들이받기!</b>");
            }
        }

        private void trail(Location now, long tick) {
            Location hips = now.clone().add(0, 0.9, 0);
            Vector side = new Vector(-direction.getZ(), 0, direction.getX()).multiply(0.3);
            Vfx.dot(hips.clone().add(side), Particle.DUST_COLOR_TRANSITION, EMBER, false);
            Vfx.dot(hips.clone().subtract(side), Particle.DUST_COLOR_TRANSITION, EMBER, false);
            Block ground = now.clone().subtract(0, 0.2, 0).getBlock();
            if (!ground.isPassable()) {
                now.getWorld().spawnParticle(Particle.BLOCK, now.clone().add(0, 0.1, 0), Vfx.n(ctx(), 4), 0.25, 0.05,
                        0.25, 0, ground.getBlockData());
            }
            if (tick % 3 == 0) {
                Fx.sound(now, Sound.ENTITY_RAVAGER_STEP, 0.9f, 1.0f + Fx.jitter(0.1f));
            }
        }

        void end() {
            if (ended) {
                return;
            }
            ended = true;
            rushing = false;
            owner.setVelocity(direction.clone().multiply(0.15).setY(REST_FALL));
            landing.armIfAirborne(owner, ctx().currentTick(), LANDING_GUARD_TICKS);
            Location feet = owner.getLocation().add(0, 0.1, 0);
            Vfx.arc(ctx(), feet, 1.2, Motion.horizontal(direction).lengthSquared() > 0 ? yawOf(direction) : 0, 140, 12,
                    Particle.DUST_COLOR_TRANSITION, BRONZE_GLOW, false);
            if (!gored.isEmpty()) {
                owner.sendActionBar(Text.mm("<" + BRONZE_TAG + ">🐂 미궁 돌진</" + BRONZE_TAG + "> <white>"
                        + gored.size() + "명 들이받음</white>"));
            }
        }
    }

    private static double horizontal(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    // ------------------------------------------------------------------ ultimate: labyrinth

    @Override
    public ActivationResult onUltimate() {
        if (maze != null) {
            return ActivationResult.fail("이미 미궁이 서 있습니다");
        }
        Player owner = player();
        Labyrinth labyrinth = new Labyrinth(owner);
        if (labyrinth.plan.isEmpty()) {
            return ActivationResult.fail("미궁을 세울 땅이 없습니다");
        }
        maze = labyrinth;
        labyrinth.begin();
        return ActivationResult.success();
    }

    /** One wall cell of the labyrinth: its column, the ring it belongs to, the block it shows. */
    private record Cell(int x, int z, int floor, int ring, BlockData data) {
    }

    /** A placed labyrinth block and what it replaced. */
    private record Placed(Block block, BlockData original) {
    }

    /**
     * The labyrinth: concentric rings of two-block-high stone walls around the minotaur, each with
     * {@link #GAPS_PER_RING} openings, and short walls between the rings right next to the openings of the outer ring,
     * so every corridor still has a way out. Cells where someone stands stay open. It is traced on the ground first,
     * rises ring by ring, stands for {@code maze_duration} and crumbles; the rush smashes its walls.
     */
    private final class Labyrinth {

        private final Player owner;
        private final Location center;
        private final long duration;
        private final long endTick;
        private final List<Cell> plan = new ArrayList<>();
        private final Map<Long, Placed> walls = new LinkedHashMap<>();
        private final int rings;

        Labyrinth(Player owner) {
            this.owner = owner;
            this.center = owner.getLocation();
            this.duration = Math.max(40, params().ticks("maze_duration"));
            this.endTick = ctx().currentTick() + TRACE_TICKS + duration;
            this.rings = Math.max(1, (int) Math.floor(Math.max(RING_SPACING, params().num("maze_radius"))
                    / RING_SPACING));
            design();
        }

        /** Lays out the rings, their openings and the walls between them. */
        private void design() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            // The labyrinth stands on the ground under the minotaur, even when it casts in mid-jump.
            Location ground = center.clone().add(0, 0.05, 0);
            Spots.snapToGround(center.getWorld(), ground, 4);
            int floor = (int) Math.floor(ground.getY() + 1.0E-3);
            double previous = random.nextDouble(Math.PI * 2);
            Set<Long> taken = new HashSet<>();
            for (int ring = 1; ring <= rings; ring++) {
                double radius = ring * RING_SPACING;
                double gapHalf = GAP_WIDTH / 2 / radius;
                // Each ring's openings are turned against the inner ring's, so the way out winds.
                double phase = previous + Math.PI / GAPS_PER_RING + random.nextDouble(-0.3, 0.3);
                previous = phase;
                double[] gaps = new double[GAPS_PER_RING];
                for (int g = 0; g < GAPS_PER_RING; g++) {
                    gaps[g] = phase + g * Math.PI * 2 / GAPS_PER_RING + random.nextDouble(-0.25, 0.25);
                }
                int span = (int) Math.ceil(radius) + 1;
                for (int dx = -span; dx <= span; dx++) {
                    for (int dz = -span; dz <= span; dz++) {
                        double r = Math.hypot(dx + 0.5 - (center.getX() - Math.floor(center.getX())),
                                dz + 0.5 - (center.getZ() - Math.floor(center.getZ())));
                        if (r < radius - 0.5 || r >= radius + 0.5) {
                            continue;
                        }
                        double angle = Math.atan2(dz + 0.5 - (center.getZ() - Math.floor(center.getZ())),
                                dx + 0.5 - (center.getX() - Math.floor(center.getX())));
                        double nearest = Double.MAX_VALUE;
                        for (double gap : gaps) {
                            nearest = Math.min(nearest, angularDistance(angle, gap));
                        }
                        if (nearest < gapHalf) {
                            continue;
                        }
                        boolean post = nearest < gapHalf + 1.2 / radius;
                        addCell(taken, center.getBlockX() + dx, center.getBlockZ() + dz, floor, ring, post);
                    }
                }
                if (ring > 1) {
                    // A wall from the inner ring to this one just past each of this ring's openings: every stretch of
                    // corridor between two such walls still holds one opening of this ring, so there is always a way.
                    for (double gap : gaps) {
                        double angle = gap + gapHalf + 1.6 / radius;
                        for (double r = radius - RING_SPACING + 0.5; r <= radius - 0.6; r += 0.5) {
                            int x = (int) Math.floor(center.getX() + Math.cos(angle) * r);
                            int z = (int) Math.floor(center.getZ() + Math.sin(angle) * r);
                            addCell(taken, x, z, floor, ring, false);
                        }
                    }
                }
            }
        }

        private void addCell(Set<Long> taken, int x, int z, int floor, int ring, boolean post) {
            if (!taken.add(key(x, 0, z))) {
                return;
            }
            // Each wall stands on the ground of its own column (a step up or down on uneven ground).
            Integer column = columnFloor(center.getWorld(), x, z, floor);
            if (column == null) {
                return;
            }
            Location at = new Location(center.getWorld(), x + 0.5, column, z + 0.5);
            if (!ctx().isInsideArena(at)) {
                return;
            }
            plan.add(new Cell(x, z, column, ring, wallData(post)));
        }

        /** The height a wall in column (x, z) stands on: free above solid ground, within two blocks of {@code near}. */
        private static @Nullable Integer columnFloor(World world, int x, int z, int near) {
            for (int y = near + 2; y >= near - 2; y--) {
                if (y - 1 < world.getMinHeight() || y + WALL_HEIGHT > world.getMaxHeight()) {
                    continue;
                }
                Block block = world.getBlockAt(x, y, z);
                if ((block.isEmpty() || block.isReplaceable()) && !block.getRelative(0, -1, 0).isPassable()) {
                    return y;
                }
            }
            return null;
        }

        private BlockData wallData(boolean post) {
            if (post) {
                return GATE_POST.createBlockData();
            }
            int roll = ThreadLocalRandom.current().nextInt(20);
            Material material = roll < 12 ? WALL : roll < 17 ? MOSSY_WALL : CRACKED_WALL;
            return material.createBlockData();
        }

        void begin() {
            // The bull's speed and the gore stay up while the labyrinth stands.
            double speed = Math.max(0, params().num("maze_speed")) / 100.0;
            if (speed > 0) {
                ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1,
                        TRACE_TICKS + duration);
            }
            // The first rush is ready at once.
            ctx().reduceCooldown(owner, Math.max(0, ctx().cooldownRemaining(owner)) + 1);
            Location feet = owner.getLocation();
            Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.5, rings * RING_SPACING + 0.5, 8,
                    Particle.DUST, STONE_DUST, true);
            Fx.sound(feet, Sound.ENTITY_RAVAGER_ROAR, 1.5f, 0.7f);
            Fx.sound(feet, Sound.BLOCK_DEEPSLATE_PLACE, 1.2f, 0.5f);
            Fx.sound(feet, Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 0.6f);
            owner.sendActionBar(Text.mm("<" + BRONZE_TAG + "><b>🐂 미궁</b></" + BRONZE_TAG + "> <gray>"
                    + Fx.seconds(duration) + "초간 미궁 돌진</gray>"));
            Loop.repeat(ctx(), owner, 0, 1, TRACE_TICKS + duration, this::step, this::close);
        }

        private boolean step(long tick) {
            if (tick < TRACE_TICKS) {
                if (tick % 3 == 0) {
                    trace(tick);
                }
                if (tick % 4 == 0) {
                    Fx.sound(center, Sound.BLOCK_DEEPSLATE_PLACE, 0.8f, 0.5f + 0.05f * tick);
                }
            }
            for (int ring = 1; ring <= rings; ring++) {
                if (tick == TRACE_TICKS + (ring - 1) * RING_STAGGER) {
                    raise(ring);
                }
            }
            if (tick == TRACE_TICKS + duration - CRUMBLE_LEAD) {
                crumble();
            }
            if (tick >= TRACE_TICKS) {
                freeTrapped();
            }
            return true;
        }

        /** The plan, traced on the ground in grey dust. */
        private void trace(long tick) {
            World world = center.getWorld();
            int every = Math.max(1, plan.size() / Vfx.n(ctx(), 60));
            for (int i = (int) (tick % every); i < plan.size(); i += every) {
                Cell cell = plan.get(i);
                world.spawnParticle(Particle.DUST, cell.x() + 0.5, cell.floor() + 0.1, cell.z() + 0.5, 1, 0.2, 0,
                        0.2, 0, STONE_DUST, true);
            }
        }

        /** Raises the walls of ring {@code ring}: every free cell becomes a temporary stone wall. */
        private void raise(int ring) {
            World world = center.getWorld();
            long expire = Math.max(1, endTick - ctx().currentTick());
            double reach = ring * RING_SPACING + 3;
            List<BoundingBox> bodies = bodiesNear(reach);
            int placed = 0;
            for (Cell cell : plan) {
                if (cell.ring() != ring) {
                    continue;
                }
                for (int h = 0; h < WALL_HEIGHT; h++) {
                    Block block = world.getBlockAt(cell.x(), cell.floor() + h, cell.z());
                    if (occupied(block, bodies)) {
                        break; // the cell stays open around someone standing in it
                    }
                    BlockData original = block.getBlockData();
                    if (ctx().placeTempBlock(block, cell.data(), expire)) {
                        walls.put(key(block.getX(), block.getY(), block.getZ()), new Placed(block, original));
                        placed++;
                    } else {
                        break;
                    }
                }
                if (ThreadLocalRandom.current().nextInt(3) == 0) {
                    world.spawnParticle(Particle.DUST_PILLAR, cell.x() + 0.5, cell.floor() + 0.2, cell.z() + 0.5,
                            Vfx.n(ctx(), 2), 0.2, 0.1, 0.2, 0, cell.data());
                }
            }
            if (placed > 0) {
                Location ringPoint = center.clone().add(ring * RING_SPACING, 0, 0);
                Fx.sound(center, Sound.BLOCK_STONE_PLACE, 1.4f, 0.5f + 0.1f * ring);
                Fx.sound(ringPoint, Sound.BLOCK_DEEPSLATE_BREAK, 1.0f, 0.5f);
                Fx.sound(center, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.6f, 0.5f);
            }
        }

        /**
         * The bodies around: a player's grown by how far its client may already be ahead of the server (its ping's
         * worth of running), so no wall rises where a player really stands.
         */
        private List<BoundingBox> bodiesNear(double reach) {
            List<BoundingBox> bodies = new ArrayList<>();
            for (Entity entity : center.getWorld().getNearbyEntities(center, reach, reach, reach)) {
                if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
                    double margin = BODY_MARGIN + (living instanceof Player player
                            ? Math.min(MAX_LAG_MARGIN, LAG_MARGIN_PER_TICK * lagTicks(player)) : 0);
                    BoundingBox box = living.getBoundingBox();
                    bodies.add(box.clone().expand(margin, 0, margin));
                }
            }
            return bodies;
        }

        /**
         * Opens every wall block someone stands in (a client that was ahead of the server when the ring rose):
         * nobody is ever stuck in a wall to suffocate.
         */
        private void freeTrapped() {
            if (walls.isEmpty()) {
                return;
            }
            double reach = rings * RING_SPACING + 3;
            for (Entity entity : center.getWorld().getNearbyEntities(center, reach, reach, reach)) {
                if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                    continue;
                }
                BoundingBox box = living.getBoundingBox();
                for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX() - 1.0E-7); x++) {
                    for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY() - 1.0E-7); y++) {
                        for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ() - 1.0E-7);
                             z++) {
                            Placed wall = walls.remove(key(x, y, z));
                            if (wall != null) {
                                open(wall, new Vector(), false);
                            }
                        }
                    }
                }
            }
        }

        /** True if any body overlaps the block's cell (a little margin, so nobody is walled in to the hair). */
        private boolean occupied(Block block, List<BoundingBox> bodies) {
            BoundingBox cell = BoundingBox.of(block);
            for (BoundingBox body : bodies) {
                if (body.overlaps(cell)) {
                    return true;
                }
            }
            return false;
        }

        /** True for a solid block that is not one of the labyrinth's own walls (those the rush breaks). */
        boolean blocksTheWay(Block block) {
            return !block.isPassable() && !walls.containsKey(key(block.getX(), block.getY(), block.getZ()));
        }

        /** Dust drifting down the walls now and then. */
        void ambient(long tick) {
            if (tick % 5 != 0 || walls.isEmpty()) {
                return;
            }
            List<Placed> all = new ArrayList<>(walls.values());
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Placed wall = all.get(random.nextInt(all.size()));
                Block block = wall.block();
                block.getWorld().spawnParticle(Particle.FALLING_DUST, block.getX() + random.nextDouble(),
                        block.getY() + 1.0, block.getZ() + random.nextDouble(), 1, 0, 0, 0, 0, block.getBlockData());
            }
        }

        /**
         * Smashes every wall block of the labyrinth within {@link #SMASH_REACH} of the path from {@code from} along
         * {@code direction} for {@code length} blocks (feet height {@code y}): it is put back to what it replaced,
         * with a burst of rubble. Only the labyrinth's own temporary blocks are touched.
         */
        void smash(Location from, Vector direction, double length, double y) {
            if (walls.isEmpty()) {
                return;
            }
            List<Long> broken = new ArrayList<>();
            for (Map.Entry<Long, Placed> entry : walls.entrySet()) {
                Block block = entry.getValue().block();
                if (block.getY() < Math.floor(y) - 1 || block.getY() > y + 2.5) {
                    continue;
                }
                Vector point = new Vector(block.getX() + 0.5 - from.getX(), 0, block.getZ() + 0.5 - from.getZ());
                double along = point.dot(direction);
                if (along < -0.5 || along > length) {
                    continue;
                }
                double across = point.clone().subtract(direction.clone().multiply(along)).length();
                if (across <= SMASH_REACH) {
                    broken.add(entry.getKey());
                }
            }
            if (broken.isEmpty()) {
                return;
            }
            Location sound = null;
            for (Long key : broken) {
                sound = open(walls.remove(key), direction, true);
            }
            if (sound != null) {
                Vfx.dot(sound, Particle.EXPLOSION, null, false);
                Fx.sound(sound, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.0f, 0.6f + Fx.jitter(0.05f));
                Fx.sound(sound, Sound.BLOCK_STONE_BREAK, 1.2f, 0.7f + Fx.jitter(0.05f));
                Fx.sound(sound, Sound.BLOCK_DEEPSLATE_BREAK, 1.0f, 0.6f + Fx.jitter(0.05f));
            }
        }

        /**
         * Opens one wall block: it is put back to what it replaced (only if it is still ours: nothing else replaced
         * it; the temporary block service restores the same data again when the labyrinth expires), with a burst of
         * grit and, when {@code smashed} by the rush, a chunk of rubble flying on along {@code direction}.
         */
        private Location open(Placed wall, Vector direction, boolean smashed) {
            Block block = wall.block();
            BlockData shown = block.getBlockData();
            if (shown.getMaterial() == WALL || shown.getMaterial() == MOSSY_WALL
                    || shown.getMaterial() == CRACKED_WALL || shown.getMaterial() == GATE_POST) {
                block.setBlockData(wall.original(), false);
            }
            Location middle = block.getLocation().add(0.5, 0.5, 0.5);
            World world = block.getWorld();
            world.spawnParticle(Particle.BLOCK, middle, Vfx.n(ctx(), smashed ? 8 : 5), 0.3, 0.3, 0.3, 0, shown);
            world.spawnParticle(Particle.BLOCK_CRUMBLE, middle, Vfx.n(ctx(), 3), 0.3, 0.3, 0.3, 0, shown);
            if (smashed) {
                rubble(middle, direction, shown);
            } else {
                Fx.sound(middle, Sound.BLOCK_STONE_BREAK, 0.8f, 0.9f + Fx.jitter(0.1f));
            }
            return middle;
        }

        /** A chunk of the smashed wall flies on ahead of the rush, tumbling, and is gone. */
        private void rubble(Location at, Vector direction, BlockData data) {
            if (ThreadLocalRandom.current().nextInt(3) != 0 || !reserve(1)) {
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            float size = 0.25f + random.nextFloat() * 0.15f;
            BlockDisplay chunk = keep(Displays.block(ctx(), owner, at, data, Displays.scaled(size), 14));
            chunk.setTeleportDuration(8);
            Location to = at.clone().add(direction.clone().multiply(1.5 + random.nextDouble()))
                    .add(random.nextDouble(-0.6, 0.6), random.nextDouble(0.2, 0.9), random.nextDouble(-0.6, 0.6));
            ctx().runLater(owner, 1, () -> {
                if (chunk.isValid()) {
                    Displays.moveTo(chunk, to);
                    Displays.animate(chunk, Displays.transform(0, 0, 0, size, size, size, random.nextFloat() * 360,
                            random.nextFloat() * 180, random.nextFloat() * 90), 8);
                }
            });
            ctx().runLater(owner, 8, () -> {
                if (chunk.isValid()) {
                    Displays.shrinkAway(ctx(), owner, chunk, 4);
                }
            });
        }

        /** The walls start to crumble just before they sink back into the ground. */
        private void crumble() {
            World world = center.getWorld();
            int every = Math.max(1, walls.size() / Vfx.n(ctx(), 50));
            int i = 0;
            for (Placed wall : walls.values()) {
                if (i++ % every != 0) {
                    continue;
                }
                Block block = wall.block();
                world.spawnParticle(Particle.BLOCK_CRUMBLE, block.getX() + 0.5, block.getY() + 0.5,
                        block.getZ() + 0.5, 1, 0.3, 0.3, 0.3, 0, block.getBlockData(), true);
            }
            Fx.sound(center, Sound.BLOCK_DEEPSLATE_BREAK, 1.4f, 0.5f);
            Fx.sound(center, Sound.BLOCK_GRAVEL_BREAK, 1.2f, 0.6f);
        }

        void close() {
            walls.clear();
            if (maze == this) {
                maze = null;
            }
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    private static double angularDistance(double a, double b) {
        double d = Math.abs(a - b) % (Math.PI * 2);
        return d > Math.PI ? Math.PI * 2 - d : d;
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

    /** Minecraft's yaw (degrees: 0 = +Z, 90 = -X) of the horizontal part of {@code direction}. */
    private static float yawOf(Vector direction) {
        Vector flat = Motion.horizontal(direction);
        if (flat.lengthSquared() < 1.0E-6) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
    }

    /** The rotation of a Minecraft yaw (0 faces +Z, 90 faces -X) about the vertical. */
    private static Quaternionf yawTurn(float yaw) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw));
    }

    /** A box of {@code size} centered on {@code center} (relative to the display), turned by {@code rotation}. */
    private static Transformation box(Vector3f center, Vector3f size, Quaternionf rotation) {
        Vector3f translation = new Vector3f(center).sub(new Quaternionf(rotation).transform(new Vector3f(size).mul(0.5f)));
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    /** A square bar of {@code width} from {@code start} along {@code along} (both relative to the display). */
    private static Transformation beam(Vector3f start, Vector3f along, float width) {
        float length = Math.max(0.001f, along.length());
        Quaternionf rotation = along.lengthSquared() < 1.0E-8f ? new Quaternionf()
                : new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(along).normalize());
        Vector3f center = new Vector3f(start).add(new Vector3f(along).mul(0.5f));
        return box(center, new Vector3f(width, width, length), rotation);
    }
}
