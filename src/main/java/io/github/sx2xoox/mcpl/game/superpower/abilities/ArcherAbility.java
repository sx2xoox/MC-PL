package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 궁수 — a kiting marksman. Starts with a Power I bow whose arrows hit harder (and slowly regrow), blasts a
 * point-blank fan of arrows with a small recoil hop, and calls down a rain of arrows on an aimed area.
 *
 * <p>Ability arrows are real, tagged arrows. Their vanilla entity hit is cancelled in {@link #onProjectileHit} and
 * the configured damage is dealt explicitly instead, so it is exact, credited and team-safe.
 */
public final class ArcherAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("archer", "궁수", Material.BOW)
            .color(TextColor.color(0x9BE15D))
            .difficulty(2)
            .passive("명사수",
                    "활(힘 I)과 화살 {arrows}개를 들고 시작하며 화살 피해가 {arrow_bonus}% 증가합니다. "
                            + "화살은 {arrow_regen}초마다 1개씩 다시 생깁니다.")
            .active("산탄",
                    "화살 {volley_arrows}발을 부채꼴로 발사해 각각 {volley_damage} 피해를 주고 살짝 뒤로 물러납니다. "
                            + "한 적이 여러 발을 맞으면 두 번째부터는 {volley_overlap}% 피해입니다.",
                    6)
            .ultimate("화살비",
                    "조준한 지점({rain_range}블록 이내) 반경 {rain_radius}블록에 {rain_duration}초간 화살 {rain_arrows}발을 "
                            + "퍼붓습니다. 화살이 꽂힌 자리의 적은 {rain_damage} 피해를 입습니다.")
            .ultimateNotStandalone()
            .param("arrows", 32)
            .param("arrow_bonus", 25)
            .param("arrow_regen", 4)
            .param("volley_arrows", 5)
            .param("volley_damage", 3)
            .param("volley_overlap", 50)
            .param("rain_range", 40)
            .param("rain_radius", 5)
            .param("rain_duration", 3)
            .param("rain_arrows", 30)
            .param("rain_damage", 3)
            .param("rain_focus", 30)
            .param("rain_hit_interval", 0.5)
            .build();

    private static final String TAG_VOLLEY = "archer_volley";
    private static final String TAG_RAIN = "archer_rain";
    private static final String ITEM_KEY = "archer_item";
    private static final int ARROW_STACK = 64;

    /** Total angle of the volley fan, edge to edge. */
    private static final double VOLLEY_FAN_DEGREES = 40;
    private static final float VOLLEY_SPEED = 2.8f;
    /** Backwards hop when firing the volley. */
    private static final double VOLLEY_RECOIL = 0.35;
    /** Shove a volley arrow gives its target (the vanilla arrow knockback is cancelled with the hit). */
    private static final double VOLLEY_PUSH = 0.3;

    private static final float RAIN_SPEED = 3.0f;
    /** Height above the zone the rain falls from (lowered under ceilings). */
    private static final double RAIN_HEIGHT = 12;
    /** Reach of a landing rain arrow around its impact point. */
    private static final double RAIN_SPLASH = 1.1;
    /** Ticks between the cast and the first arrows, so the zone marker is seen first. */
    private static final long RAIN_WINDUP = 8;
    /** Random spread of an arrow aimed at an enemy inside the zone. */
    private static final double RAIN_AIM_JITTER = 0.45;

    /** Ticks an ability arrow stays stuck in the ground before vanishing. */
    private static final long STUCK_TICKS = 20;
    /** Ability arrows that never land are removed after this many ticks. */
    private static final long ARROW_LIFETIME = 100;
    private static final double MAX_SPEED = 2.5;

    private static final Color VOLLEY_COLOR = Color.fromRGB(0x9BE15D);
    private static final Color RAIN_COLOR = Color.fromRGB(0xFFC94D);
    private static final Particle.DustOptions VOLLEY_DUST = new Particle.DustOptions(VOLLEY_COLOR, 1.0f);
    private static final Particle.DustOptions RAIN_DUST = new Particle.DustOptions(RAIN_COLOR, 1.3f);

    /** Volley arrows in flight, by arrow id. */
    private final Map<UUID, Volley> volleyArrows = new HashMap<>();
    /** Rain arrows in flight, by arrow id. */
    private final Map<UUID, Rain> rainArrows = new HashMap<>();
    private @Nullable NamespacedKey itemKey;
    private long regenProgress;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: marksman

    @Override
    public void onGrant() {
        player().getInventory().addItem(createBow());
        giveArrows(Math.max(0, params().integer("arrows")));
    }

    @Override
    public void onRevoke() {
        volleyArrows.clear();
        rainArrows.clear();
        if (itemKey == null) {
            return;
        }
        // Only the items this ability handed out carry the marker, so a restored inventory is never touched.
        PlayerInventory inventory = player().getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isMarked(contents[slot])) {
                inventory.setItem(slot, null);
            }
        }
        if (isMarked(player().getItemOnCursor())) {
            player().setItemOnCursor(null);
        }
    }

    @Override
    public void onTick(long tick) {
        if (tick % 20 != 0) {
            return;
        }
        long every = params().ticks("arrow_regen");
        if (every <= 0) {
            return;
        }
        regenProgress += 20;
        if (regenProgress < every) {
            return;
        }
        regenProgress = 0;
        if (countArrows() < params().integer("arrows")) {
            giveArrows(1);
        }
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() || !(event.getDamager() instanceof AbstractArrow arrow)) {
            return;
        }
        UUID id = arrow.getUniqueId();
        if (volleyArrows.containsKey(id) || rainArrows.containsKey(id)) {
            return;
        }
        double bonus = params().num("arrow_bonus") / 100.0;
        if (bonus != 0) {
            event.setDamage(Math.max(0, event.getDamage() * (1 + bonus)));
        }
        Location body = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        victim.getWorld().spawnParticle(Particle.ENCHANTED_HIT, body, 8, 0.25, 0.3, 0.25, 0.15);
    }

    private ItemStack createBow() {
        ItemStack bow = ItemStack.of(Material.BOW);
        bow.setData(DataComponentTypes.ITEM_NAME, Component.text("궁수의 활", INFO.color()));
        bow.setData(DataComponentTypes.UNBREAKABLE);
        bow.addEnchantment(Enchantment.POWER, 1);
        return mark(bow);
    }

    private void giveArrows(int amount) {
        PlayerInventory inventory = player().getInventory();
        int left = amount;
        while (left > 0) {
            int stack = Math.min(left, ARROW_STACK);
            inventory.addItem(mark(ItemStack.of(Material.ARROW, stack)));
            left -= stack;
        }
    }

    private int countArrows() {
        int count = 0;
        for (ItemStack item : player().getInventory().getContents()) {
            if (item != null && item.getType() == Material.ARROW) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private ItemStack mark(ItemStack item) {
        NamespacedKey key = itemKey();
        item.editPersistentDataContainer(data -> data.set(key, PersistentDataType.BOOLEAN, true));
        return item;
    }

    private boolean isMarked(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getPersistentDataContainer().has(itemKey());
    }

    private NamespacedKey itemKey() {
        if (itemKey == null) {
            itemKey = new NamespacedKey(ctx().plugin(), ITEM_KEY);
        }
        return itemKey;
    }

    // ------------------------------------------------------------------ active: scatter shot

    @Override
    public ActivationResult onActive() {
        Location eye = player().getEyeLocation();
        World world = eye.getWorld();
        Vector look = eye.getDirection();
        // Fire from inside the owner like a bow does: arrows ignore their shooter until they leave its hitbox, and
        // a muzzle in front of the eyes would start inside a hugging enemy, whose hitbox the ray would then miss.
        Location origin = eye.clone().subtract(0, 0.1, 0);

        // Fan out around the owner's own "up" axis so the spread stays sideways even when aiming up or down.
        Vector right = look.getCrossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            double yaw = Math.toRadians(eye.getYaw());
            right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        }
        Vector up = right.getCrossProduct(look).normalize();

        int count = Math.max(1, params().integer("volley_arrows"));
        double fan = Math.toRadians(VOLLEY_FAN_DEGREES);
        Volley volley = new Volley();
        for (int i = 0; i < count; i++) {
            double angle = count == 1 ? 0 : -fan / 2 + fan * i / (count - 1);
            Vector direction = look.clone().rotateAroundAxis(up, angle);
            Arrow arrow = world.spawnArrow(origin, direction, VOLLEY_SPEED, 0f);
            prepare(arrow, VOLLEY_COLOR, TAG_VOLLEY);
            volleyArrows.put(arrow.getUniqueId(), volley);
        }

        Vector recoil = look.clone().setY(0);
        if (recoil.lengthSquared() > 1.0E-4) {
            recoil.normalize().multiply(-VOLLEY_RECOIL).setY(0.12);
            player().setVelocity(Motion.clamp(player().getVelocity().add(recoil), MAX_SPEED));
        }

        Location muzzle = eye.clone().add(look.clone().multiply(1.0));
        world.spawnParticle(Particle.CRIT, muzzle, 12, 0.25, 0.15, 0.25, 0.25);
        world.spawnParticle(Particle.DUST, muzzle, 8, 0.3, 0.2, 0.3, 0, VOLLEY_DUST);
        Fx.sound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1.0f, 1.1f + Fx.jitter(0.08f));
        Fx.sound(eye, Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.75f + Fx.jitter(0.05f));
        return ActivationResult.success();
    }

    /** One firing of the scatter shot: remembers who was already struck so overlapping arrows deal less. */
    private static final class Volley {
        private final Set<UUID> struck = new HashSet<>();
    }

    private void volleyHit(ProjectileHitEvent event, AbstractArrow arrow) {
        Entity hit = event.getHitEntity();
        if (hit == null) {
            volleyArrows.remove(arrow.getUniqueId());
            removeLater(arrow, STUCK_TICKS);
            return;
        }
        // Never let the vanilla arrow hit happen; allies, the owner and bystanders are simply passed through.
        event.setCancelled(true);
        if (!(hit instanceof LivingEntity target) || !ctx().isEnemy(player(), target)) {
            return;
        }
        Volley volley = volleyArrows.remove(arrow.getUniqueId());
        Vector flight = arrow.getVelocity();
        arrow.remove();

        double damage = params().num("volley_damage");
        if (volley != null && !volley.struck.add(target.getUniqueId())) {
            damage *= Math.max(0, params().num("volley_overlap")) / 100.0;
        }
        Location body = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        target.getWorld().spawnParticle(Particle.CRIT, body, 6, 0.2, 0.25, 0.2, 0.2);
        target.getWorld().spawnParticle(Particle.DUST, body, 4, 0.2, 0.25, 0.2, 0, VOLLEY_DUST);
        player().playSound(player(), Sound.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.5f,
                1.0f + Fx.jitter(0.1f));
        Combat.hit(ctx(), player(), target, damage);

        if (target.isValid() && !target.isDead()) {
            Vector push = flight.setY(0);
            if (push.lengthSquared() > 1.0E-4) {
                push.normalize().multiply(VOLLEY_PUSH * Motion.knockbackFactor(target)).setY(0.1);
                target.setVelocity(Motion.clamp(target.getVelocity().add(push), MAX_SPEED));
            }
        }
    }

    // ------------------------------------------------------------------ ultimate: arrow rain

    @Override
    public ActivationResult onUltimate() {
        Location center = aimedGround(params().num("rain_range"));
        if (center == null) {
            return ActivationResult.fail("조준한 곳이 너무 멀거나 허공입니다");
        }
        Rain rain = new Rain(center, Math.max(0.5, params().num("rain_radius")));
        long duration = Math.max(1, params().ticks("rain_duration"));
        int arrows = Math.max(1, params().integer("rain_arrows"));

        Location eye = player().getEyeLocation();
        Fx.sound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1.2f, 0.7f);
        Fx.sound(eye, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 0.8f + Fx.jitter(0.05f));
        eye.getWorld().spawnParticle(Particle.CRIT, eye.clone().add(0, 0.6, 0), 20, 0.15, 0.8, 0.15, 0.3);
        Fx.sound(center, Sound.ITEM_CROSSBOW_QUICK_CHARGE_3, 1.5f, 0.6f);
        player().sendActionBar(Text.mm("<#FFC94D>➶ 화살비 투하!</#FFC94D>"));

        Loop.repeat(ctx(), player(), 0, 1, RAIN_WINDUP + duration, tick -> {
            if (tick % 6 == 0) {
                rain.drawZone();
            }
            if (tick < RAIN_WINDUP) {
                return true;
            }
            long t = tick - RAIN_WINDUP;
            int due = (int) ((t + 1) * arrows / duration - t * arrows / duration);
            if (due > 0) {
                rain.drop(due);
            }
            return true;
        });
        return ActivationResult.success();
    }

    /** One arrow rain: its zone and the per-target hit throttle. */
    private final class Rain {
        private final Location center;
        private final double radius;
        private final Map<UUID, Long> lastHit = new HashMap<>();

        Rain(Location center, double radius) {
            this.center = center;
            this.radius = radius;
        }

        void drop(int count) {
            World world = center.getWorld();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            List<LivingEntity> inZone = ctx().enemiesNear(player(), center, radius);
            double focus = params().num("rain_focus") / 100.0;
            for (int i = 0; i < count; i++) {
                double x;
                double z;
                if (!inZone.isEmpty() && random.nextDouble() < focus) {
                    LivingEntity target = inZone.get(random.nextInt(inZone.size()));
                    x = target.getX() + random.nextGaussian() * RAIN_AIM_JITTER;
                    z = target.getZ() + random.nextGaussian() * RAIN_AIM_JITTER;
                } else {
                    double distance = Math.sqrt(random.nextDouble()) * radius;
                    double angle = random.nextDouble() * Math.PI * 2;
                    x = center.getX() + Math.cos(angle) * distance;
                    z = center.getZ() + Math.sin(angle) * distance;
                }
                Location base = new Location(world, x, center.getY() + 0.5, z);
                for (int step = 0; step < 4 && !base.getBlock().isPassable(); step++) {
                    base.add(0, 1, 0);
                }
                double height = RAIN_HEIGHT;
                RayTraceResult ceiling = world.rayTraceBlocks(base, new Vector(0, 1, 0), RAIN_HEIGHT,
                        FluidCollisionMode.NEVER, true);
                if (ceiling != null) {
                    height = Math.max(0.3, ceiling.getHitPosition().getY() - base.getY() - 0.6);
                }
                Location spawn = base.clone().add(0, height, 0);
                Vector down = new Vector(random.nextGaussian() * 0.03, -1, random.nextGaussian() * 0.03);
                Arrow arrow = world.spawnArrow(spawn, down, RAIN_SPEED, 0f);
                prepare(arrow, RAIN_COLOR, TAG_RAIN);
                rainArrows.put(arrow.getUniqueId(), this);
            }
        }

        void impact(Location at) {
            World world = at.getWorld();
            world.spawnParticle(Particle.CRIT, at, 4, 0.15, 0.05, 0.15, 0.15);
            long now = ctx().currentTick();
            long interval = params().ticks("rain_hit_interval");
            double damage = params().num("rain_damage");
            for (LivingEntity target : ctx().enemiesNear(player(), at, RAIN_SPLASH)) {
                Long last = lastHit.get(target.getUniqueId());
                if (last != null && now - last < interval) {
                    continue;
                }
                lastHit.put(target.getUniqueId(), now);
                Location body = target.getLocation().add(0, target.getHeight() * 0.6, 0);
                world.spawnParticle(Particle.DUST, body, 6, 0.25, 0.3, 0.25, 0, RAIN_DUST);
                Fx.sound(body, Sound.ENTITY_ARROW_HIT, 0.8f, 1.2f + Fx.jitter(0.1f));
                player().playSound(player(), Sound.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.4f,
                        1.1f + Fx.jitter(0.1f));
                Combat.hit(ctx(), player(), target, damage);
            }
        }

        void drawZone() {
            int points = (int) Math.max(16, Math.min(48, radius * 7));
            Location edge = center.clone().add(0, 0.15, 0);
            Fx.ring(edge, radius, points, 0, Particle.DUST, RAIN_DUST);
            center.getWorld().spawnParticle(Particle.CRIT, edge.add(0, 0.2, 0), 3, 0.3, 0.1, 0.3, 0.05);
        }
    }

    private void rainHit(ProjectileHitEvent event, AbstractArrow arrow) {
        Entity hit = event.getHitEntity();
        if (hit != null) {
            event.setCancelled(true);
            if (!(hit instanceof LivingEntity target) || !ctx().isEnemy(player(), target)) {
                return; // pass through allies and the owner, the arrow keeps falling
            }
            Rain rain = rainArrows.remove(arrow.getUniqueId());
            arrow.remove();
            if (rain != null) {
                rain.impact(target.getLocation().add(0, target.getHeight() * 0.8, 0));
            }
            return;
        }
        Rain rain = rainArrows.remove(arrow.getUniqueId());
        // The arrow only reaches its impact point after this event; read it once it is stuck.
        ctx().runLater(player(), 1, () -> {
            if (rain != null && arrow.isValid()) {
                rain.impact(arrow.getLocation());
            }
        });
        removeLater(arrow, STUCK_TICKS + 10);
    }

    // ------------------------------------------------------------------ projectiles

    @Override
    public void onProjectileHit(ProjectileHitEvent event, String tag) {
        if (!(event.getEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        if (TAG_VOLLEY.equals(tag)) {
            volleyHit(event, arrow);
        } else if (TAG_RAIN.equals(tag)) {
            rainHit(event, arrow);
        }
    }

    /** Makes a freshly spawned arrow an ability arrow: owned, tagged, tracked, unpickable and short-lived. */
    private void prepare(Arrow arrow, Color color, String tag) {
        arrow.setShooter(player());
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setCritical(true);
        arrow.setColor(color);
        arrow.setDamage(0);
        arrow.setPersistent(false);
        ctx().tagProjectile(player(), arrow, tag);
        ctx().track(player(), arrow);
        removeLater(arrow, ARROW_LIFETIME);
    }

    private void removeLater(AbstractArrow arrow, long ticks) {
        UUID id = arrow.getUniqueId();
        ctx().runLater(player(), ticks, () -> {
            volleyArrows.remove(id);
            rainArrows.remove(id);
            if (arrow.isValid()) {
                arrow.remove();
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** The ground point the owner aims at within {@code range}: an aimed enemy's feet, else the aimed block. */
    private @Nullable Location aimedGround(double range) {
        LivingEntity target = ctx().findTarget(player(), range);
        if (target != null) {
            return target.getLocation();
        }
        Location eye = player().getEyeLocation();
        World world = eye.getWorld();
        RayTraceResult ray = world.rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        if (ray == null) {
            return null;
        }
        Location point = ray.getHitPosition().toLocation(world);
        BlockFace face = ray.getHitBlockFace();
        if (face != null) {
            point.add(face.getDirection().multiply(0.3));
        }
        RayTraceResult ground = world.rayTraceBlocks(point, new Vector(0, -1, 0), 8, FluidCollisionMode.NEVER, true);
        return ground == null ? point : ground.getHitPosition().toLocation(world);
    }
}
