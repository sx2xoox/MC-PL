package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 수호자 — the tank. Takes less damage from everything, bashes enemies away with a shield charge, and becomes
 * nearly unbreakable for a while, shielding nearby allies too.
 *
 * <p>Look: white and sky blue light. A blocked hit flashes a pane of blue glass towards the attacker. The charge
 * pushes a glowing bow wave ahead of a big shield and ends in a wall of force rolling forward. 불굴 assembles a slowly
 * turning dome of glass plates (open at eye height) with three shields orbiting inside; hits flash the plate they
 * strike, the shields shatter as the absorption drains, and the dome bursts outwards at the end.
 */
public final class GuardianAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("guardian", "수호자", Material.SHIELD)
            .color(TextColor.color(0x5B8DEF))
            .difficulty(1)
            .passive("철벽", "받는 모든 피해가 {damage_reduction}% 감소합니다.")
            .active("방패 돌격",
                    "방패를 앞세워 {bash_distance}블록 돌진합니다. 부딪힌 곳 앞 반경 {bash_radius}블록의 적에게 "
                            + "{bash_damage} 피해를 주고 강하게 밀쳐내며 {bash_slow}초간 구속 II를 겁니다.",
                    8)
            .ultimate("불굴",
                    "{ult_duration}초간 흡수 체력 {ult_absorption}과 저항 II를 얻습니다. 반경 {ult_ally_radius}블록 안의 "
                            + "아군도 저항 I을 받습니다.")
            .param("damage_reduction", 15)
            .param("bash_distance", 4)
            .param("bash_radius", 2.5)
            .param("bash_damage", 3)
            .param("bash_slow", 1)
            .param("ult_duration", 8)
            .param("ult_absorption", 10)
            .param("ult_ally_radius", 8)
            .build();

    private static final double TAU = Math.PI * 2;

    // Shield charge
    private static final double BASH_SPEED = 0.95;
    private static final double BASH_SINK = -0.08;
    private static final double CONTACT_RADIUS = 1.0;
    /** Ticks before stall detection starts (the client needs a moment to pick up the new velocity). */
    private static final long BASH_GRACE_TICKS = 3;
    private static final double STALL_EPSILON = 0.1;
    /** Ticks in a row without movement that end the charge; three, so one late movement packet does not. */
    private static final int STALL_LIMIT = 3;
    /** Most ticks a slow connection may add to the grace period and the charge length. */
    private static final long MAX_LAG_TICKS = 8;
    private static final double MILLIS_PER_TICK = 50.0;
    private static final double KNOCK_FORWARD = 1.5;
    private static final double KNOCK_AWAY = 0.35;
    private static final double KNOCK_UP = 0.45;
    private static final double MAX_VELOCITY = 2.5;
    private static final int BASH_SLOW_AMPLIFIER = 1;
    private static final float CHARGE_SHIELD_SCALE = 1.8f;
    /** The charge shield outlives the longest charge by this much (pop and fade at the end). */
    private static final long CHARGE_SHIELD_SPARE_TICKS = 15;

    // Bulwark
    private static final int RESISTANCE_AMPLIFIER = 1;
    private static final int ALLY_RESISTANCE_AMPLIFIER = 0;
    private static final int ALLY_PULSE_TICKS = 20;
    private static final int ALLY_EFFECT_TICKS = 30;
    private static final int SHIELD_COUNT = 3;
    private static final double ORBIT_SPEED = 0.16;
    private static final float ORBIT_SHIELD_SCALE = 1.1f;
    private static final int FADE_TICKS = 5;

    /** Minimum ticks between two passive block feedbacks, so a flurry of hits does not spam effects. */
    private static final long BLOCK_FEEDBACK_COOLDOWN = 10;

    // Visuals
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xB3E5FC, 0x4FC3F7);
    private static final float PLATE_THICKNESS = 0.04f;
    private static final float WARD_WIDTH = 0.95f;
    private static final float WARD_HEIGHT = 1.1f;
    private static final long WARD_HOLD_TICKS = 4;
    private static final int WARD_FADE_TICKS = 3;
    private static final int BASH_WAVE_TICKS = 4;
    private static final long FORCE_WALL_HOLD_TICKS = 5;
    private static final int FORCE_WALL_FADE_TICKS = 3;
    /** How far the wall of force rolls forward from the shield. */
    private static final float FORCE_WALL_PUSH = 0.9f;
    /** Gap between the guardian's hitbox and the dome. */
    private static final double DOME_GAP = 1.9;
    /** Height of the dome's centre above the feet. */
    private static final double DOME_BASE = 0.05;
    /** Dome rings: elevation (degrees), plate count, plate size, angular offset in plate slots. The band at eye height stays open. */
    private static final DomeRing[] DOME_RINGS = {
            new DomeRing(15, 8, 0.95f, 0),
            new DomeRing(58, 6, 0.85f, 0.5),
            new DomeRing(90, 1, 0.9f, 0)};
    /** Degrees the dome turns per tick. */
    private static final float DOME_SPIN = 1.5f;
    private static final int DOME_ASSEMBLE_TICKS = 6;
    private static final long DOME_RING_STAGGER = 2;
    /** Plates fly in from (and burst out to) this multiple of the dome radius. */
    private static final double DOME_FLY_IN = 1.7;
    private static final double DOME_BURST = 1.5;
    private static final int DOME_BURST_TICKS = 6;
    private static final long DOME_FLASH_TICKS = 3;
    private static final long DOME_SHIMMER_PERIOD = 4;

    private boolean bashing;
    private long lastBlockFeedback = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
    private @Nullable Bulwark bulwark;

    private record DomeRing(double elevation, int count, float size, double offset) {
    }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        bashing = false;
        bulwark = null;
    }

    // ------------------------------------------------------------------ passive: iron wall

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Tag<DamageType> bypass = DamageTypeTags.BYPASSES_INVULNERABILITY;
        if (bypass != null && bypass.isTagged(event.getDamageSource().getDamageType())) {
            return;
        }
        if (event.getDamage() <= 0) {
            return;
        }
        double reduction = Math.max(0, Math.min(1, params().num("damage_reduction") / 100.0));
        if (reduction > 0) {
            event.setDamage(event.getDamage() * (1 - reduction));
        }
        Bulwark active = bulwark;
        if (active != null) {
            active.dome.onHit(event);
        } else if (reduction > 0) {
            ward(event);
        }
    }

    /** A blocked hit: a pane of blue glass flashes up between the guardian and the attacker. */
    private void ward(EntityDamageEvent event) {
        long now = ctx().currentTick();
        if (event.getDamage() < 1.5 || now - lastBlockFeedback < BLOCK_FEEDBACK_COOLDOWN) {
            return;
        }
        lastBlockFeedback = now;
        Player owner = player();
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        World world = chest.getWorld();
        Fx.sound(chest, Sound.ITEM_SHIELD_BLOCK, 0.35f, 1.5f + Fx.jitter(0.1f));
        Fx.sound(chest, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.5f, 1.4f + Fx.jitter(0.1f));
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            world.spawnParticle(Particle.ENCHANTED_HIT, chest, Vfx.n(ctx(), 4), 0.25, 0.3, 0.25, 0.1);
            return;
        }
        Vector toward = Motion.horizontal(byEntity.getDamager().getLocation().toVector().subtract(chest.toVector()));
        if (toward.lengthSquared() < 1.0E-6) {
            toward = Motion.horizontalFacing(owner.getLocation());
        }
        Location at = chest.clone().add(toward.clone().multiply(owner.getWidth() * 0.5 + 0.45));
        at.setYaw(0);
        at.setPitch(0);
        float yaw = yawOf(toward);
        BlockDisplay pane = Displays.block(ctx(), owner, at, Material.LIGHT_BLUE_STAINED_GLASS.createBlockData(),
                Displays.transform(0, 0, 0, WARD_WIDTH * 0.6f, WARD_HEIGHT * 0.6f, PLATE_THICKNESS, yaw, 0, 0),
                WARD_HOLD_TICKS + WARD_FADE_TICKS + 3);
        Displays.animateNextTick(ctx(), owner, pane,
                Displays.transform(0, 0, 0, WARD_WIDTH, WARD_HEIGHT, PLATE_THICKNESS, yaw, 0, 0), 2);
        ctx().runLater(owner, WARD_HOLD_TICKS, () -> {
            if (pane.isValid()) {
                Displays.shrinkAway(ctx(), owner, pane, WARD_FADE_TICKS);
            }
        });
        world.spawnParticle(Particle.ENCHANTED_HIT, at, Vfx.n(ctx(), 5), 0.25, 0.3, 0.25, 0.1);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, Vfx.n(ctx(), 5), 0.3, 0.35, 0.3, 0,
                PALETTE.glow(0.9f));
    }

    // ------------------------------------------------------------------ active: shield charge

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 돌격할 수 없습니다");
        }
        if (bashing) {
            return ActivationResult.fail("이미 돌격 중입니다");
        }
        Vector direction = Motion.horizontalFacing(owner.getLocation());
        Location start = owner.getLocation();
        // The charge never carries the owner past the arena border.
        double distance = Spots.arenaReach(ctx(), start, direction, Math.max(1, params().num("bash_distance")));
        if (distance < 1) {
            return ActivationResult.fail("자기장 밖으로는 돌격할 수 없습니다");
        }
        World world = start.getWorld();
        // The server sees the owner move only a round trip after the velocity is sent: wait that long for the
        // first movement before calling the charge stalled, and give the charge that much more time.
        long lag = lagTicks(owner);
        long maxTicks = (long) Math.ceil(distance / BASH_SPEED) + 6 + lag;
        float facing = yawOf(direction);
        ItemDisplay shield = Displays.item(ctx(), owner, shieldSpot(owner, direction, 0), new ItemStack(Material.SHIELD),
                shieldPose(CHARGE_SHIELD_SCALE, facing), maxTicks + CHARGE_SHIELD_SPARE_TICKS);
        shield.setTeleportDuration(1);
        bashing = true;

        Fx.sound(start, Sound.ITEM_ARMOR_EQUIP_IRON, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(start, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.8f + Fx.jitter(0.1f));
        owner.setVelocity(direction.clone().multiply(BASH_SPEED).setY(BASH_SINK));
        owner.setFallDistance(0);

        Location[] last = {start};
        int[] stalls = {0};
        boolean[] moved = {false};
        Loop.repeat(ctx(), player(), 0, 1, maxTicks, tick -> {
            Location now = owner.getLocation();
            if (!now.getWorld().equals(world)) {
                return false;
            }
            // Lead by one tick of travel: the server position trails the moving player.
            Displays.moveTo(shield, shieldSpot(owner, direction, BASH_SPEED));
            Location front = now.clone().add(direction.clone().multiply(0.8)).add(0, owner.getHeight() * 0.5, 0);
            if (!ctx().enemiesNear(owner, front, CONTACT_RADIUS).isEmpty()
                    || horizontalDistance(start, now) >= distance - 0.2) {
                return false;
            }
            if (!moved[0] && horizontalDistance(start, now) >= STALL_EPSILON) {
                moved[0] = true;
            }
            if (moved[0] || tick >= BASH_GRACE_TICKS + lag) {
                stalls[0] = horizontalDistance(last[0], now) < STALL_EPSILON ? stalls[0] + 1 : 0;
                if (stalls[0] >= STALL_LIMIT) {
                    return false;
                }
            }
            last[0] = now;
            owner.setVelocity(direction.clone().multiply(BASH_SPEED).setY(BASH_SINK));
            owner.setFallDistance(0);
            bashTrail(owner, now, direction);
            return true;
        }, () -> {
            bashing = false;
            bash(direction, shield);
        });
        return ActivationResult.success();
    }

    /** Ticks the server sees the owner's movement late: one round trip, capped (ping can read 0 after joining). */
    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / MILLIS_PER_TICK));
    }

    /** One tick of the charge: a glowing bow wave curving round the shield and light streaks flying back. */
    private void bashTrail(Player owner, Location at, Vector direction) {
        World world = at.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double height = owner.getHeight();
        Location chest = at.clone().add(0, height * 0.5, 0);
        Vfx.arc(ctx(), chest, 0.9 + BASH_SPEED + 0.35, yawOf(direction), 90, 9, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(1.0f), false);
        Vector side = new Vector(-direction.getZ(), 0, direction.getX());
        int streaks = Vfx.n(ctx(), 2);
        for (int i = 0; i < streaks; i++) {
            Location from = at.clone().add(side.clone().multiply(random.nextDouble(-0.5, 0.5)))
                    .add(0, random.nextDouble(0.2, height * 0.9), 0).add(direction.clone().multiply(0.6));
            Location to = from.clone().subtract(direction.clone().multiply(random.nextDouble(1.2, 2.0)));
            Vfx.trail(from, to, i == 0 ? PALETTE.core() : PALETTE.edge(), 5, false);
        }
        world.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.1, 0), 1, 0.1, 0.02, 0.1, 0.01);
    }

    /** The shield's impact at the end of the charge: hits every enemy in a short frontal area. */
    private void bash(Vector direction, ItemDisplay shield) {
        Player owner = player();
        Location at = owner.getLocation();
        owner.setVelocity(direction.clone().multiply(0.05).setY(BASH_SINK));

        double radius = Math.max(0.5, params().num("bash_radius"));
        double damage = Math.max(0, params().num("bash_damage"));
        int slowTicks = (int) Math.min(Integer.MAX_VALUE, params().ticks("bash_slow"));
        Location front = at.clone().add(direction.clone().multiply(1.0)).add(0, owner.getHeight() * 0.5, 0);
        int struck = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, front, radius)) {
            Vector offset = enemy.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (offset.lengthSquared() > 1.0E-4 && offset.clone().normalize().dot(direction) < -0.3) {
                continue; // behind the guardian
            }
            Combat.hit(ctx(), player(), enemy, damage);
            struck++;
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            Vector away = offset.lengthSquared() > 1.0E-4 ? offset.normalize() : direction.clone();
            Vector knock = direction.clone().multiply(KNOCK_FORWARD).add(away.multiply(KNOCK_AWAY)).setY(KNOCK_UP);
            Motion.push(enemy, knock, MAX_VELOCITY);
            if (slowTicks > 0) {
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks, BASH_SLOW_AMPLIFIER,
                        false, true, true));
            }
            Vfx.burst(ctx(), Fx.body(enemy), PALETTE, 0.5, Particle.ENCHANTED_HIT, false);
        }
        bashImpact(at, front, direction, struck);
        if (struck > 0) {
            owner.sendActionBar(Text.mm("<#5B8DEF>방패 돌격!</#5B8DEF> <gray>" + struck + "명 적중</gray>"));
        }

        if (shield.isValid()) {
            Displays.moveTo(shield, shieldSpot(owner, direction, 0));
            Displays.animate(shield, shieldPose(CHARGE_SHIELD_SCALE * 1.35f, yawOf(direction)), 3);
            ctx().runLater(owner, 4, () -> {
                if (shield.isValid()) {
                    Displays.shrinkAway(ctx(), owner, shield, FADE_TICKS);
                }
            });
        }
    }

    /** The bash lands: a wall of force rolls forward, a cone of light spreads out and the ground is torn up. */
    private void bashImpact(Location at, Location front, Vector direction, int struck) {
        World world = at.getWorld();
        float yaw = yawOf(direction);
        Fx.sound(front, Sound.ITEM_SHIELD_BLOCK, 1.2f, 0.75f + Fx.jitter(0.05f));
        Fx.sound(front, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.2f + Fx.jitter(0.1f));
        forceWall(front, direction, yaw, struck > 0);
        Location chest = front.clone().subtract(direction);
        Location feet = at.clone().add(0, 0.15, 0);
        Loop.repeat(ctx(), player(), 0, 1, BASH_WAVE_TICKS, tick -> {
            double r = 1.0 + tick * 0.7;
            Vfx.arc(ctx(), chest, r, yaw, 110 - tick * 12, 9 + (int) tick * 2, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.2f), false);
            Vfx.arc(ctx(), feet, r, yaw, 100, 6 + (int) tick, Particle.CLOUD, null, false);
            return true;
        });
        BlockData ground = groundUnder(at);
        if (ground != null) {
            world.spawnParticle(Particle.BLOCK, at.clone().add(direction).add(0, 0.1, 0), Vfx.n(ctx(), 14), 0.5, 0.1,
                    0.5, 0.15, ground);
        }
        if (struck > 0) {
            world.spawnParticle(Particle.FLASH, front, 1, 0, 0, 0, 0, PALETTE.core());
            Fx.sound(front, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.7f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(front, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.9f + Fx.jitter(0.05f));
        }
    }

    /** A pane of light that grows out of the shield and rolls forward, bigger when the bash hit someone. */
    private void forceWall(Location front, Vector direction, float yaw, boolean hit) {
        Player owner = player();
        Location at = front.clone();
        at.setYaw(0);
        at.setPitch(0);
        float width = hit ? 2.8f : 2.0f;
        float height = hit ? 2.2f : 1.6f;
        BlockDisplay wall = Displays.block(ctx(), owner, at, Material.LIGHT_BLUE_STAINED_GLASS.createBlockData(),
                Displays.transform(0, 0, 0, 1.0f, 1.2f, PLATE_THICKNESS, yaw, 0, 0),
                FORCE_WALL_HOLD_TICKS + FORCE_WALL_FADE_TICKS + 3);
        Displays.animateNextTick(ctx(), owner, wall,
                Displays.transform((float) direction.getX() * FORCE_WALL_PUSH, 0,
                        (float) direction.getZ() * FORCE_WALL_PUSH, width, height, PLATE_THICKNESS, yaw, 0, 0),
                4);
        ctx().runLater(owner, FORCE_WALL_HOLD_TICKS, () -> {
            if (wall.isValid()) {
                Displays.shrinkAway(ctx(), owner, wall, FORCE_WALL_FADE_TICKS);
            }
        });
    }

    /** Where the charge shield floats: {@code lead} blocks beyond the front of the owner's chest. */
    private static Location shieldSpot(Player owner, Vector direction, double lead) {
        return Displays.unrotated(owner.getLocation().add(direction.clone().multiply(0.9 + lead))
                .add(0, owner.getHeight() * 0.5, 0));
    }

    /**
     * A shield item of {@code scale} facing {@code yaw} (degrees, Minecraft convention). The display itself keeps
     * yaw and pitch 0; its transformation turns the shield.
     */
    private static Transformation shieldPose(float scale, float yaw) {
        return Displays.centered(0, 0, 0, scale, scale, scale, yaw, 0, 0);
    }

    // ------------------------------------------------------------------ ultimate: indomitable

    @Override
    public ActivationResult onUltimate() {
        if (bulwark != null) {
            return ActivationResult.fail("이미 불굴 상태입니다");
        }
        Bulwark created = new Bulwark();
        bulwark = created;
        created.begin();
        return ActivationResult.success();
    }

    /**
     * One activation of 불굴: absorption, resistance, an ally aura, a glass dome and orbiting shields that break as
     * it drains.
     */
    private final class Bulwark {

        private record Orbiter(ItemDisplay display, int slot) {
        }

        private final double absorption = Math.max(0, params().num("ult_absorption"));
        private final double allyRadius = Math.max(0, params().num("ult_ally_radius"));
        private final long duration = Math.max(1, params().ticks("ult_duration"));
        private final List<Orbiter> shields = new ArrayList<>();
        private final Dome dome = new Dome();
        private @Nullable NamespacedKey absorptionKey;
        private boolean teamNearby;

        void begin() {
            Player owner = player();
            if (absorption > 0) {
                absorptionKey = ctx().addModifier(owner, owner, Attribute.MAX_ABSORPTION, absorption,
                        Operation.ADD_NUMBER, 0);
                owner.setAbsorptionAmount(owner.getAbsorptionAmount() + absorption);
            }
            owner.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                    (int) Math.min(Integer.MAX_VALUE, duration), RESISTANCE_AMPLIFIER, false, true, true));

            for (int slot = 0; slot < SHIELD_COUNT; slot++) {
                ItemDisplay display = Displays.item(ctx(), owner, orbitSpot(owner, slot, 0),
                        new ItemStack(Material.SHIELD), shieldPose(ORBIT_SHIELD_SCALE, orbitYaw(slot, 0)),
                        duration + FADE_TICKS + 10);
                shields.add(new Orbiter(display, slot));
            }
            dome.raise(owner, duration);
            cast(owner);
            pulseAllies(owner);

            Loop.repeat(ctx(), player(), 0, 1, duration, this::tick, this::end);
        }

        /** The cast: a flash, a ring of light spreading to the dome's edge and absorption sparkles. */
        private void cast(Player owner) {
            Location at = owner.getLocation();
            World world = at.getWorld();
            Location chest = at.clone().add(0, owner.getHeight() * 0.55, 0);
            Fx.sound(at, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 0.8f);
            Fx.sound(at, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.0f, 0.7f);
            Fx.sound(at, Sound.BLOCK_ANVIL_PLACE, 0.5f, 1.4f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 0.8f + Fx.jitter(0.05f));
            world.spawnParticle(Particle.FLASH, chest, 1, 0, 0, 0, 0, PALETTE.core(), true);
            Vfx.shockwave(ctx(), owner, at.clone().add(0, 0.1, 0), 0.5, dome.radius(owner), DOME_ASSEMBLE_TICKS,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.3f), true);
            Vfx.helix(ctx(), at, 0.8, owner.getHeight() + 0.6, 1.5, 2, 14, 0, Particle.END_ROD, null, true);
            world.spawnParticle(Particle.GLOW, chest, Vfx.n(ctx(), 12), 0.5, 0.6, 0.5, 0.02, null, true);
        }

        private boolean tick(long tick) {
            Player owner = player();
            for (Orbiter orbiter : shields) {
                ItemDisplay display = orbiter.display();
                if (display.isValid()) {
                    Displays.moveTo(display, orbitSpot(owner, orbiter.slot(), tick));
                    // Turned with the same smoothing as the move (the display's teleport duration).
                    Displays.animate(display, shieldPose(ORBIT_SHIELD_SCALE, orbitYaw(orbiter.slot(), tick)),
                            display.getTeleportDuration());
                }
            }
            dome.follow(owner);
            if (tick % DOME_SHIMMER_PERIOD == 0) {
                dome.shimmer(owner);
                if (absorption > 0) {
                    breakSpentShields(owner);
                }
            }
            if (tick > 0 && tick % ALLY_PULSE_TICKS == 0) {
                pulseAllies(owner);
            }
            if (teamNearby && tick % 10 == 0) {
                drawAuraRing(owner.getLocation(), tick);
            }
            return true;
        }

        /** One shield per third of the granted absorption: they shatter as the absorption is used up. */
        private void breakSpentShields(Player owner) {
            double perShield = absorption / SHIELD_COUNT;
            double remaining = Math.min(absorption, owner.getAbsorptionAmount());
            int intact = (int) Math.ceil(remaining / perShield - 1.0E-6);
            while (shields.size() > Math.max(0, intact)) {
                ItemDisplay display = shields.removeLast().display();
                if (display.isValid()) {
                    Location at = display.getLocation();
                    at.getWorld().spawnParticle(Particle.ITEM, at, Vfx.n(ctx(), 14), 0.15, 0.15, 0.15, 0.08,
                            new ItemStack(Material.SHIELD));
                    Vfx.burst(ctx(), at, PALETTE, 0.4, Particle.ELECTRIC_SPARK, false);
                    Fx.sound(at, Sound.ITEM_SHIELD_BREAK, 1.0f, 0.9f + Fx.jitter(0.1f));
                    display.remove();
                }
            }
        }

        private void pulseAllies(Player owner) {
            if (allyRadius <= 0) {
                return;
            }
            Location at = owner.getLocation();
            Location chest = at.clone().add(0, owner.getHeight() * 0.55, 0);
            List<Player> allies = ctx().alliesNear(owner, at, allyRadius);
            for (Player ally : allies) {
                ally.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ALLY_EFFECT_TICKS,
                        ALLY_RESISTANCE_AMPLIFIER, true, true, true));
                Location allyChest = ally.getLocation().add(0, ally.getHeight() * 0.55, 0);
                if (allyChest.getWorld().equals(chest.getWorld())) {
                    int travel = (int) Math.max(4, Math.min(20, chest.distance(allyChest) * 2));
                    int motes = Vfx.n(ctx(), 4);
                    for (int i = 0; i < motes; i++) {
                        Vfx.trail(Vfx.scatter(chest, 0.3), allyChest, i % 2 == 0 ? PALETTE.body() : PALETTE.core(),
                                travel + i, false);
                    }
                    Vfx.ring(ctx(), ally.getLocation().add(0, 0.1, 0), 0.55, 10, 0, Particle.DUST_COLOR_TRANSITION,
                            PALETTE.glow(0.9f), false);
                }
            }
            teamNearby = !allies.isEmpty() || !ctx().alliesNear(owner, at, allyRadius * 2).isEmpty();
        }

        private void drawAuraRing(Location center, long tick) {
            int points = (int) Math.max(16, Math.min(48, allyRadius * 5));
            Vfx.ring(ctx(), center.clone().add(0, 0.2, 0), allyRadius, points, tick * 0.02, Particle.DUST,
                    PALETTE.dust(1.0f), false);
        }

        private void end() {
            Player owner = player();
            NamespacedKey key = absorptionKey;
            if (key != null) {
                ctx().removeModifier(owner, key);
                absorptionKey = null;
                // Re-set to clamp any leftover absorption to the (now lower) maximum.
                owner.setAbsorptionAmount(owner.getAbsorptionAmount());
            }
            for (Orbiter orbiter : shields) {
                if (orbiter.display().isValid()) {
                    Displays.shrinkAway(ctx(), owner, orbiter.display(), FADE_TICKS);
                }
            }
            shields.clear();
            dome.burst(owner);
            Fx.sound(owner.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.9f);
            if (bulwark == this) {
                bulwark = null;
            }
        }
    }

    /**
     * The glass dome of 불굴: light blue plates on a hemisphere around the guardian, all anchored at its feet and
     * turned together through the entity yaw, so following and spinning is one smooth teleport per plate.
     */
    private final class Dome {

        private record Plate(BlockDisplay display, double azimuth, double elevation, float size) {
        }

        private final List<Plate> plates = new ArrayList<>();
        private float spin;
        private long lastFlash = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow

        double radius(Player owner) {
            return owner.getWidth() * 0.5 + DOME_GAP;
        }

        /** Spawns the plates far out and small; ring by ring they fly in and assemble the dome. */
        void raise(Player owner, long duration) {
            double radius = radius(owner);
            Location anchor = anchor(owner);
            BlockData glass = Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
            long lifetime = duration + DOME_RINGS.length * DOME_RING_STAGGER + DOME_ASSEMBLE_TICKS
                    + DOME_BURST_TICKS + 10;
            for (int ring = 0; ring < DOME_RINGS.length; ring++) {
                DomeRing spec = DOME_RINGS[ring];
                long delay = 1 + ring * DOME_RING_STAGGER;
                for (int i = 0; i < spec.count(); i++) {
                    double azimuth = (i + spec.offset()) * TAU / spec.count();
                    BlockDisplay display = Displays.block(ctx(), owner, anchor, glass,
                            platePose(azimuth, spec.elevation(), radius * DOME_FLY_IN, spec.size() * 0.2f),
                            lifetime);
                    Transformation pose = platePose(azimuth, spec.elevation(), radius, spec.size());
                    ctx().runLater(owner, delay, () -> Displays.animate(display, pose, DOME_ASSEMBLE_TICKS));
                    plates.add(new Plate(display, azimuth, spec.elevation(), spec.size()));
                }
            }
            Fx.sound(owner.getLocation(), Sound.BLOCK_GLASS_PLACE, 1.0f, 1.2f + Fx.jitter(0.1f));
        }

        /**
         * Keeps the dome on the guardian and turns it a little. Unlike every other display of this ability, the
         * plates deliberately take the anchor's yaw: it spins the whole dome around the guardian, on top of the
         * plate poses, which are laid out relative to the anchor (see {@link #plateNormal}).
         */
        void follow(Player owner) {
            spin = (spin + DOME_SPIN) % 360;
            Location anchor = anchor(owner);
            for (Plate plate : plates) {
                if (plate.display().isValid()) {
                    plate.display().teleport(anchor);
                }
            }
        }

        /** Absorption sparkles: a few motes of light on random plates. */
        void shimmer(Player owner) {
            if (plates.isEmpty()) {
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double radius = radius(owner);
            int sparkles = Vfx.n(ctx(), 2);
            for (int i = 0; i < sparkles; i++) {
                Plate plate = plates.get(random.nextInt(plates.size()));
                Location at = plateCenter(owner, plate, radius);
                Vfx.dot(at, Particle.END_ROD, null, false);
                Vfx.dot(at.add(random.nextDouble(-0.3, 0.3), random.nextDouble(-0.3, 0.3),
                        random.nextDouble(-0.3, 0.3)), Particle.GLOW, null, false);
            }
        }

        /** A hit on the guardian lights up the plate facing the attacker. */
        void onHit(EntityDamageEvent event) {
            long now = ctx().currentTick();
            if (plates.isEmpty() || now - lastFlash < DOME_FLASH_TICKS
                    || !(event instanceof EntityDamageByEntityEvent byEntity)) {
                return;
            }
            lastFlash = now;
            Player owner = player();
            double radius = radius(owner);
            Location center = owner.getLocation().add(0, DOME_BASE, 0);
            Vector toward = byEntity.getDamager().getLocation().add(0, 1, 0).toVector().subtract(center.toVector());
            if (toward.lengthSquared() < 1.0E-6) {
                return;
            }
            toward.normalize();
            Plate best = null;
            double bestDot = -2;
            for (Plate plate : plates) {
                double dot = plateNormal(plate).dot(toward);
                if (plate.display().isValid() && dot > bestDot) {
                    bestDot = dot;
                    best = plate;
                }
            }
            if (best == null) {
                return;
            }
            BlockDisplay display = best.display();
            display.setBlock(Material.WHITE_STAINED_GLASS.createBlockData());
            ctx().runLater(owner, DOME_FLASH_TICKS, () -> {
                if (display.isValid()) {
                    display.setBlock(Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
                }
            });
            Location at = plateCenter(owner, best, radius);
            at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, Vfx.n(ctx(), 6), 0.3, 0.3, 0.3, 0.05);
            at.getWorld().spawnParticle(Particle.GLOW, at, Vfx.n(ctx(), 3), 0.25, 0.25, 0.25, 0);
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.8f, 1.2f + Fx.jitter(0.1f));
        }

        /** The dome bursts outwards and dissolves into motes of light. */
        void burst(Player owner) {
            double radius = radius(owner);
            for (Plate plate : plates) {
                BlockDisplay display = plate.display();
                if (!display.isValid()) {
                    continue;
                }
                Displays.animate(display, platePose(plate.azimuth(), plate.elevation(), radius * DOME_BURST,
                        plate.size() * 0.05f), DOME_BURST_TICKS);
                ctx().runLater(owner, DOME_BURST_TICKS, () -> {
                    if (display.isValid()) {
                        display.remove();
                    }
                });
            }
            plates.clear();
            Location at = owner.getLocation();
            Vfx.sphere(ctx(), at.clone().add(0, DOME_BASE, 0), radius, 36, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.fade(1.2f), true);
            Fx.sound(at, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.0f + Fx.jitter(0.1f));
        }

        private Location anchor(Player owner) {
            Location anchor = owner.getLocation();
            anchor.setYaw(spin);
            anchor.setPitch(0);
            return anchor;
        }

        /** The outward normal of a plate in the world, with the dome's current turn. */
        private Vector plateNormal(Plate plate) {
            double azimuth = plate.azimuth() + Math.toRadians(spin);
            double elevation = Math.toRadians(plate.elevation());
            return new Vector(Math.cos(azimuth) * Math.cos(elevation), Math.sin(elevation),
                    Math.sin(azimuth) * Math.cos(elevation));
        }

        private Location plateCenter(Player owner, Plate plate, double radius) {
            return owner.getLocation().add(0, DOME_BASE, 0).add(plateNormal(plate).multiply(radius));
        }
    }

    /**
     * A thin plate tangent to the dome at {@code azimuth} (radians) and {@code elevation} (degrees), relative to the
     * dome anchor: its thin side faces outwards.
     */
    private static Transformation platePose(double azimuth, double elevation, double radius, float size) {
        double e = Math.toRadians(elevation);
        float x = (float) (Math.cos(azimuth) * Math.cos(e) * radius);
        float y = (float) (DOME_BASE + Math.sin(e) * radius);
        float z = (float) (Math.sin(azimuth) * Math.cos(e) * radius);
        float yaw = (float) Math.toDegrees(azimuth) - 90;
        return Displays.transform(x, y, z, size, size, PLATE_THICKNESS, yaw, (float) -elevation, 0);
    }

    /** Position of one orbiting shield, {@code tick} ticks into the ultimate. */
    private static Location orbitSpot(Player owner, int slot, long tick) {
        double angle = orbitAngle(slot, tick);
        double radius = owner.getWidth() * 0.5 + 0.9;
        return Displays.unrotated(owner.getLocation().add(Math.cos(angle) * radius, owner.getHeight() * 0.45,
                Math.sin(angle) * radius));
    }

    /** The yaw (degrees) an orbiting shield faces: outwards, away from the guardian. */
    private static float orbitYaw(int slot, long tick) {
        double angle = orbitAngle(slot, tick);
        return (float) Math.toDegrees(Math.atan2(-Math.cos(angle), Math.sin(angle)));
    }

    /** The angle (radians, from +x towards +z) of one orbiting shield around the guardian. */
    private static double orbitAngle(int slot, long tick) {
        return tick * ORBIT_SPEED + slot * (TAU / SHIELD_COUNT);
    }

    // ------------------------------------------------------------------ helpers

    /** The Minecraft yaw (degrees) of a horizontal direction. */
    private static float yawOf(Vector direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
    }

    /** The solid block right under {@code at}, null over air, fluids and plants. */
    private static @Nullable BlockData groundUnder(Location at) {
        BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        return ground.getMaterial().isSolid() ? ground : null;
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }
}
