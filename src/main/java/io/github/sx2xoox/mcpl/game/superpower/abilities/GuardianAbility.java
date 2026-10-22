package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
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
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 수호자 — the tank. Takes less damage from everything, bashes enemies away with a shield charge, and becomes
 * nearly unbreakable for a while, shielding nearby allies too.
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

    // Shield charge
    private static final double BASH_SPEED = 0.95;
    private static final double BASH_SINK = -0.08;
    private static final double CONTACT_RADIUS = 1.0;
    private static final long BASH_GRACE_TICKS = 3;
    private static final double STALL_EPSILON = 0.1;
    private static final int STALL_LIMIT = 2;
    private static final double KNOCK_FORWARD = 1.5;
    private static final double KNOCK_AWAY = 0.35;
    private static final double KNOCK_UP = 0.45;
    private static final double MAX_VELOCITY = 2.5;
    private static final int BASH_SLOW_AMPLIFIER = 1;
    private static final float CHARGE_SHIELD_SCALE = 1.8f;

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

    private static final Color STEEL = Color.fromRGB(91, 141, 239);
    private static final Color GOLD = Color.fromRGB(255, 205, 80);
    private static final Particle.DustOptions STEEL_DUST = new Particle.DustOptions(STEEL, 1.2f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 1.2f);

    private boolean bashing;
    private long lastBlockFeedback = Long.MIN_VALUE;
    private @Nullable Bulwark bulwark;

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
        double reduction = Math.max(0, Math.min(1, params().num("damage_reduction") / 100.0));
        if (reduction <= 0 || event.getDamage() <= 0) {
            return;
        }
        event.setDamage(event.getDamage() * (1 - reduction));

        long now = ctx().currentTick();
        if (event.getDamage() >= 1.5 && now - lastBlockFeedback >= BLOCK_FEEDBACK_COOLDOWN) {
            lastBlockFeedback = now;
            Player owner = player();
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
            owner.getWorld().spawnParticle(Particle.DUST, chest, 4, 0.3, 0.3, 0.3, 0, STEEL_DUST);
            owner.getWorld().spawnParticle(Particle.ENCHANTED_HIT, chest, 4, 0.25, 0.3, 0.25, 0.1);
            Fx.sound(chest, Sound.ITEM_SHIELD_BLOCK, 0.35f, 1.5f + Fx.jitter(0.1f));
        }
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
        ItemDisplay shield = spawnShield(shieldSpot(owner, direction, 0), CHARGE_SHIELD_SCALE, 1);
        bashing = true;

        Fx.sound(start, Sound.ITEM_ARMOR_EQUIP_IRON, 1.0f, 0.8f);
        Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.7f);
        owner.setVelocity(direction.clone().multiply(BASH_SPEED).setY(BASH_SINK));
        owner.setFallDistance(0);

        long maxTicks = (long) Math.ceil(distance / BASH_SPEED) + 6;
        Location[] last = {start};
        int[] stalls = {0};
        Loop.repeat(ctx(), player(), 0, 1, maxTicks, tick -> {
            Location now = owner.getLocation();
            if (!now.getWorld().equals(world)) {
                return false;
            }
            if (shield.isValid()) {
                // Lead by one tick of travel: the server position trails the moving player.
                shield.teleport(shieldSpot(owner, direction, BASH_SPEED));
            }
            Location front = now.clone().add(direction.clone().multiply(0.8)).add(0, owner.getHeight() * 0.5, 0);
            if (!ctx().enemiesNear(owner, front, CONTACT_RADIUS).isEmpty()
                    || horizontalDistance(start, now) >= distance - 0.2) {
                return false;
            }
            if (tick >= BASH_GRACE_TICKS) {
                stalls[0] = horizontalDistance(last[0], now) < STALL_EPSILON ? stalls[0] + 1 : 0;
                if (stalls[0] >= STALL_LIMIT) {
                    return false;
                }
            }
            last[0] = now;
            owner.setVelocity(direction.clone().multiply(BASH_SPEED).setY(BASH_SINK));
            owner.setFallDistance(0);
            world.spawnParticle(Particle.DUST, now.clone().add(0, 0.4, 0), 2, 0.2, 0.2, 0.2, 0, STEEL_DUST);
            world.spawnParticle(Particle.CLOUD, now.clone().add(0, 0.1, 0), 1, 0.1, 0.02, 0.1, 0.01);
            return true;
        }, () -> {
            bashing = false;
            bash(direction, shield);
        });
        return ActivationResult.success();
    }

    /** The shield's impact at the end of the charge: hits every enemy in a short frontal area. */
    private void bash(Vector direction, ItemDisplay shield) {
        Player owner = player();
        Location at = owner.getLocation();
        World world = at.getWorld();
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
            Location body = enemy.getLocation().add(0, enemy.getHeight() * 0.5, 0);
            world.spawnParticle(Particle.CRIT, body, 12, 0.3, 0.4, 0.3, 0.3);
        }

        world.spawnParticle(Particle.SWEEP_ATTACK, front, 2, 0.3, 0.2, 0.3, 0);
        world.spawnParticle(Particle.DUST, front, 12, 0.5, 0.4, 0.5, 0, STEEL_DUST);
        BlockData ground = at.clone().subtract(0, 0.2, 0).getBlock().getBlockData();
        if (!ground.getMaterial().isAir()) {
            world.spawnParticle(Particle.BLOCK, at.clone().add(direction).add(0, 0.1, 0), 14, 0.5, 0.1, 0.5, 0.15,
                    ground);
        }
        Fx.sound(front, Sound.ITEM_SHIELD_BLOCK, 1.2f, 0.75f + Fx.jitter(0.05f));
        if (struck > 0) {
            world.spawnParticle(Particle.FLASH, front, 1, 0, 0, 0, 0, Color.WHITE);
            Fx.sound(front, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.7f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(front, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.9f);
            owner.sendActionBar(Text.mm("<#5B8DEF>방패 돌격!</#5B8DEF> <gray>" + struck + "명 적중</gray>"));
        }

        if (shield.isValid()) {
            shield.teleport(shieldSpot(owner, direction, 0));
            fadeOut(shield, CHARGE_SHIELD_SCALE * 1.35f);
        }
    }

    /** Where the charge shield floats: {@code lead} blocks beyond the front of the owner's chest, facing forward. */
    private static Location shieldSpot(Player owner, Vector direction, double lead) {
        Location spot = owner.getLocation().add(direction.clone().multiply(0.9 + lead))
                .add(0, owner.getHeight() * 0.5, 0);
        spot.setDirection(direction);
        spot.setPitch(0);
        return spot;
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

    /** One activation of 불굴: absorption, resistance, an ally aura and orbiting shields that break as it drains. */
    private final class Bulwark {

        private record Orbiter(ItemDisplay display, int slot) {
        }

        private final double absorption = Math.max(0, params().num("ult_absorption"));
        private final double allyRadius = Math.max(0, params().num("ult_ally_radius"));
        private final List<Orbiter> shields = new ArrayList<>();
        private @Nullable NamespacedKey absorptionKey;
        private boolean teamNearby;

        void begin() {
            Player owner = player();
            long duration = Math.max(1, params().ticks("ult_duration"));
            if (absorption > 0) {
                absorptionKey = ctx().addModifier(owner, owner, Attribute.MAX_ABSORPTION, absorption,
                        Operation.ADD_NUMBER, 0);
                owner.setAbsorptionAmount(owner.getAbsorptionAmount() + absorption);
            }
            owner.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                    (int) Math.min(Integer.MAX_VALUE, duration), RESISTANCE_AMPLIFIER, false, true, true));

            for (int slot = 0; slot < SHIELD_COUNT; slot++) {
                shields.add(new Orbiter(spawnShield(orbitSpot(owner, slot, 0), ORBIT_SHIELD_SCALE, 2), slot));
            }

            Location at = owner.getLocation();
            World world = at.getWorld();
            Fx.sound(at, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 0.8f);
            Fx.sound(at, Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1.0f, 0.7f);
            Fx.sound(at, Sound.BLOCK_ANVIL_PLACE, 0.5f, 1.4f);
            world.spawnParticle(Particle.DUST, at.clone().add(0, owner.getHeight() * 0.5, 0), 30, 0.5,
                    owner.getHeight() * 0.4, 0.5, 0, GOLD_DUST);
            world.spawnParticle(Particle.END_ROD, at.clone().add(0, 0.2, 0), 16, 0.5, 0.1, 0.5, 0.08);
            pulseAllies(owner);

            Loop.repeat(ctx(), player(), 0, 1, duration, this::tick, this::end);
        }

        private boolean tick(long tick) {
            Player owner = player();
            for (Orbiter orbiter : shields) {
                if (orbiter.display().isValid()) {
                    orbiter.display().teleport(orbitSpot(owner, orbiter.slot(), tick));
                }
            }
            if (tick % 4 == 0 && absorption > 0) {
                breakSpentShields(owner);
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
                    at.getWorld().spawnParticle(Particle.ITEM, at, 14, 0.15, 0.15, 0.15, 0.08,
                            new ItemStack(Material.SHIELD));
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
                    int duration = (int) Math.max(4, Math.min(20, chest.distance(allyChest) * 2));
                    chest.getWorld().spawnParticle(Particle.TRAIL, chest, 4, 0.15, 0.15, 0.15, 0,
                            new Particle.Trail(allyChest, GOLD, duration));
                }
            }
            teamNearby = !allies.isEmpty() || !ctx().alliesNear(owner, at, allyRadius * 2).isEmpty();
        }

        private void drawAuraRing(Location center, long tick) {
            int points = (int) Math.max(16, Math.min(48, allyRadius * 5));
            Fx.ring(center.clone().add(0, 0.2, 0), allyRadius, points, tick * 0.02, Particle.DUST, GOLD_DUST);
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
                fadeOut(orbiter.display(), 0.0f);
            }
            shields.clear();
            Fx.sound(owner.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.9f);
            if (bulwark == this) {
                bulwark = null;
            }
        }
    }

    /** Position and facing of one orbiting shield, {@code tick} ticks into the ultimate. */
    private static Location orbitSpot(Player owner, int slot, long tick) {
        Location center = owner.getLocation();
        double angle = tick * ORBIT_SPEED + slot * (Math.PI * 2 / SHIELD_COUNT);
        double radius = owner.getWidth() * 0.5 + 0.9;
        double dx = Math.cos(angle);
        double dz = Math.sin(angle);
        Location spot = center.clone().add(dx * radius, owner.getHeight() * 0.45, dz * radius);
        spot.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        spot.setPitch(0);
        return spot;
    }

    // ------------------------------------------------------------------ helpers

    /** A tracked, non-persistent shield display; {@code smoothing} is its teleport interpolation in ticks. */
    private ItemDisplay spawnShield(Location at, float scale, int smoothing) {
        ItemStack item = new ItemStack(Material.SHIELD);
        return ctx().track(player(), at.getWorld().spawn(at, ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setItemStack(item);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setBillboard(Display.Billboard.FIXED);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setTeleportDuration(smoothing);
            display.setTransformation(shape(scale));
        }));
    }

    /** Smoothly scales a display to {@code scale} and removes it once the animation has played. */
    private void fadeOut(ItemDisplay display, float scale) {
        if (!display.isValid()) {
            return;
        }
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(FADE_TICKS);
        display.setTransformation(shape(scale));
        ctx().runLater(player(), FADE_TICKS + 1, () -> {
            if (display.isValid()) {
                display.remove();
            }
        });
    }

    private static Transformation shape(float scale) {
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale),
                new Quaternionf());
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }
}
