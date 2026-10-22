package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 궁수 — a kiting marksman. Starts with a Power I bow whose arrows hit harder (and slowly regrow), blasts a
 * point-blank fan of arrows with a small recoil hop, and calls down a rain of arrows on an aimed area.
 *
 * <p>Ability arrows are real, tagged arrows. Their vanilla entity hit is cancelled in {@link #onProjectileHit} and
 * the configured damage is dealt explicitly instead (as arrow damage, so projectile defences such as dodges and
 * reflections apply), so it is exact, credited and team-safe. Arrows shot from the archer's own bow are tracked as
 * well, so none of them outlives the match.
 *
 * <p>Visuals: every arrow leaves a green-white streak; the volley flashes a spectral bow and a fan of sparks; the
 * rain is announced by a dashed reticle of glowing bars that closes in on the zone, a salvo of motes shot into the
 * sky, and falling light streaks that splash where they land.
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
            .param("volley_damage", 5)
            .param("volley_overlap", 50)
            .param("rain_range", 40)
            .param("rain_radius", 5)
            .param("rain_duration", 3)
            .param("rain_arrows", 30)
            .param("rain_damage", 5)
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

    /** Bow arrows of the owner are picked up for tracking within this box half-size, in their first ticks. */
    private static final double SHOT_SCAN = 6;
    private static final int SHOT_FRESH_TICKS = 3;
    /** Arrows (bow and ability) leave a streak for at most this many ticks of flight, and at most this many at once. */
    private static final int TRAIL_TICKS = 60;
    private static final int MAX_TRAILS = 24;

    // ------------------------------------------------------------------ visuals

    /** White core, pale leaf-green body, deep green edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xC5E1A5, 0x7CB342);
    private static final Color VOLLEY_TINT = Color.fromRGB(0x9BE15D);
    private static final Color RAIN_TINT = Color.fromRGB(0xE6F5C9);
    /** Bars of the rain reticle: a dashed ring of {@link #RETICLE_BARS} plus four inward crosshair ticks. */
    private static final int RETICLE_BARS = 16;
    private static final double RETICLE_START = 1.6;
    private static final float BAR_WIDTH = 0.16f;
    private static final float BAR_HEIGHT = 0.05f;

    /** Volley arrows in flight, by arrow id. */
    private final Map<UUID, Volley> volleyArrows = new HashMap<>();
    /** Rain arrows in flight, by arrow id. */
    private final Map<UUID, Rain> rainArrows = new HashMap<>();
    /** Arrows (bow shots and ability arrows) that currently leave a streak, by arrow id. */
    private final Map<UUID, AbstractArrow> streaks = new LinkedHashMap<>();
    /** Bow shots of the owner already tracked, so a stuck arrow nearby is not looked at again. */
    private final Set<UUID> bowShots = new HashSet<>();
    private @Nullable NamespacedKey itemKey;
    private long regenProgress;
    /** The target of the ability hit being dealt right now, and whether it landed (see {@link #onDamageDealt}). */
    private @Nullable LivingEntity pendingTarget;
    private boolean landed;

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
        streaks.clear();
        bowShots.clear();
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
        watchBowShots();
        drawStreaks();
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
            // A quiet "nock" for the archer and a few green motes at the quiver.
            Location back = player().getLocation().add(0, 1.2, 0)
                    .subtract(Motion.horizontalFacing(player().getLocation()).multiply(0.3));
            Vfx.cloud(ctx(), back, Particle.DUST_COLOR_TRANSITION, 4, 0.15, 0, PALETTE.glow(0.6f), false);
            player().playSound(player(), Sound.ITEM_ARMOR_EQUIP_LEATHER, SoundCategory.PLAYERS, 0.35f,
                    1.6f + Fx.jitter(0.1f));
        }
    }

    /**
     * Picks up the owner's freshly shot bow arrows: they are tracked (removed with the match, never saved with the
     * chunk) and get the archer's streak. There is no shoot hook, so new arrows are found next to the owner.
     */
    private void watchBowShots() {
        for (Entity entity : player().getNearbyEntities(SHOT_SCAN, SHOT_SCAN, SHOT_SCAN)) {
            if (!(entity instanceof AbstractArrow arrow) || entity.getTicksLived() > SHOT_FRESH_TICKS) {
                continue;
            }
            UUID id = arrow.getUniqueId();
            if (bowShots.contains(id) || volleyArrows.containsKey(id) || rainArrows.containsKey(id)
                    || !player().equals(arrow.getShooter())) {
                continue;
            }
            bowShots.add(id);
            arrow.setPersistent(false);
            ctx().track(player(), arrow);
            addStreak(arrow);
        }
        if (bowShots.size() > 256) {
            bowShots.removeIf(id -> ctx().plugin().getServer().getEntity(id) == null);
        }
    }

    private void addStreak(AbstractArrow arrow) {
        if (streaks.size() < MAX_TRAILS) {
            streaks.put(arrow.getUniqueId(), arrow);
        }
    }

    /** A fading white-to-green streak behind every arrow in flight, over the distance it covered this tick. */
    private void drawStreaks() {
        for (Iterator<AbstractArrow> it = streaks.values().iterator(); it.hasNext(); ) {
            AbstractArrow arrow = it.next();
            if (!arrow.isValid() || arrow.isInBlock() || arrow.getTicksLived() > TRAIL_TICKS) {
                it.remove();
                continue;
            }
            Location at = arrow.getLocation();
            Location behind = at.clone().subtract(Motion.clamp(arrow.getVelocity(), MAX_SPEED));
            Vfx.line(ctx(), behind, at, 1.5, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.6f), false);
        }
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isBowShot(event)) {
            return;
        }
        double bonus = params().num("arrow_bonus") / 100.0;
        if (bonus != 0) {
            event.setDamage(Math.max(0, event.getDamage() * (1 + bonus)));
        }
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage()) {
            if (victim.equals(pendingTarget)) {
                landed = true;
            }
            return;
        }
        if (isBowShot(event)) {
            // Marksman hit: a small green ring snaps around the target and white sparks fly.
            Location body = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
            Vfx.ring(ctx(), body, 0.5, 10, ThreadLocalRandom.current().nextDouble(Math.PI),
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.7f), false);
            Vfx.cloud(ctx(), body, Particle.ENCHANTED_HIT, 6, 0.25, 0.15, null, false);
        }
    }

    /** A plain arrow from the archer's bow (not an ability arrow, not ability damage). */
    private boolean isBowShot(EntityDamageByEntityEvent event) {
        if (ctx().isAbilityDamage() || !(event.getDamager() instanceof AbstractArrow arrow)) {
            return false;
        }
        UUID id = arrow.getUniqueId();
        return !volleyArrows.containsKey(id) && !rainArrows.containsKey(id);
    }

    /**
     * Deals an ability arrow's damage as arrow damage (so projectile dodges and reflections apply) and tells whether
     * it landed: on-hit effects (shove, impact, hit sound) only follow a hit nothing cancelled.
     */
    private boolean arrowHit(LivingEntity target, double damage) {
        if (!(damage > 0)) {
            return Combat.isTargetable(ctx(), player(), target); // nothing to dodge
        }
        pendingTarget = target;
        landed = false;
        try {
            Combat.hit(ctx(), player(), target, damage, DamageType.ARROW);
        } finally {
            pendingTarget = null;
        }
        return landed;
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
        List<Vector> directions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = count == 1 ? 0 : -fan / 2 + fan * i / (count - 1);
            Vector direction = look.clone().rotateAroundAxis(up, angle);
            directions.add(direction);
            Arrow arrow = world.spawnArrow(origin, direction, VOLLEY_SPEED, 0f);
            prepare(arrow, VOLLEY_TINT, TAG_VOLLEY);
            volleyArrows.put(arrow.getUniqueId(), volley);
        }

        Vector recoil = look.clone().setY(0);
        if (recoil.lengthSquared() > 1.0E-4) {
            recoil.normalize().multiply(-VOLLEY_RECOIL).setY(0.12);
            player().setVelocity(Motion.clamp(player().getVelocity().add(recoil), MAX_SPEED));
        }

        volleyFlash(eye, look, directions);
        Fx.sound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1.0f, 1.1f + Fx.jitter(0.08f));
        Fx.sound(eye, Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.75f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1.5f + Fx.jitter(0.1f));
        return ActivationResult.success();
    }

    /**
     * The volley's muzzle: a spectral bow that flashes in front of the archer, a white flash, a green arc spanning
     * the fan and a spark streaking along every arrow.
     */
    private void volleyFlash(Location eye, Vector look, List<Vector> directions) {
        World world = eye.getWorld();
        Location muzzle = eye.clone().add(look.clone().multiply(1.0)).subtract(0, 0.25, 0);
        // Low in the archer's view and short-lived, so it never hides where the arrows go.
        Location bowAt = eye.clone().add(look.clone().multiply(1.4)).subtract(0, 0.5, 0);
        bowAt.setDirection(look);
        ItemDisplay bow = Displays.item(ctx(), player(), bowAt, ItemStack.of(Material.BOW),
                Displays.centered(0, 0, 0, 0.3f, 0.3f, 0.3f, 0, 0, -45), 12);
        Displays.animateNextTick(ctx(), player(), bow, Displays.centered(0, 0, 0, 0.9f, 0.9f, 0.35f, 0, 0, -45), 2);
        ctx().runLater(player(), 3, () -> {
            if (bow.isValid()) {
                Displays.shrinkAway(ctx(), player(), bow, 4);
            }
        });

        world.spawnParticle(Particle.FLASH, muzzle, 1, 0, 0, 0, 0, PALETTE.core(), false);
        Location chest = eye.clone().subtract(0, 0.35, 0);
        Vfx.arc(ctx(), chest, 1.3, eye.getYaw(), VOLLEY_FAN_DEGREES + 10, 12, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(0.9f), false);
        for (Vector direction : directions) {
            // count 0: the offsets are a velocity, so each spark streaks along its arrow
            world.spawnParticle(Particle.CRIT, muzzle.getX(), muzzle.getY(), muzzle.getZ(), 0, direction.getX(),
                    direction.getY(), direction.getZ(), 0.9, null, false);
            world.spawnParticle(Particle.END_ROD, muzzle.getX(), muzzle.getY(), muzzle.getZ(), 0, direction.getX(),
                    direction.getY(), direction.getZ(), 0.35, null, false);
        }
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
        streaks.remove(arrow.getUniqueId());
        Vector flight = arrow.getVelocity();
        arrow.remove();

        double damage = params().num("volley_damage");
        if (volley != null && !volley.struck.add(target.getUniqueId())) {
            damage *= Math.max(0, params().num("volley_overlap")) / 100.0;
        }
        if (!arrowHit(target, damage)) {
            return;
        }
        arrowImpact(target.getLocation().add(0, target.getHeight() * 0.6, 0));
        player().playSound(player(), Sound.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.5f,
                1.0f + Fx.jitter(0.1f));
        if (target.isValid() && !target.isDead()) {
            Vector push = flight.setY(0);
            if (push.lengthSquared() > 1.0E-4) {
                push.normalize().multiply(VOLLEY_PUSH * Motion.knockbackFactor(target)).setY(0.1);
                target.setVelocity(Motion.clamp(target.getVelocity().add(push), MAX_SPEED));
            }
        }
    }

    /** An arrow striking a body: white core sparks, a puff of green and a tiny ring. */
    private void arrowImpact(Location at) {
        Vfx.cloud(ctx(), at, Particle.CRIT, 6, 0.2, 0.25, null, false);
        Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 5, 0.2, 0, PALETTE.glow(0.9f), false);
        Vfx.ring(ctx(), at, 0.35, 8, ThreadLocalRandom.current().nextDouble(Math.PI), Particle.DUST,
                new Particle.DustOptions(PALETTE.edge(), 0.6f), false);
        Fx.sound(at, Sound.ENTITY_ARROW_HIT, 0.7f, 1.2f + Fx.jitter(0.15f));
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
        Fx.sound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1.2f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(eye, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.ITEM_CROSSBOW_QUICK_CHARGE_3, 1.5f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BELL, 1.2f, 1.6f + Fx.jitter(0.05f));
        skySalvo(eye);
        player().sendActionBar(Text.mm("<#C5E1A5>➶ 화살비 투하!</#C5E1A5>"));

        rain.openReticle(RAIN_WINDUP + duration + 2);
        Loop.repeat(ctx(), player(), 0, 1, RAIN_WINDUP + duration, tick -> {
            rain.drawZone(tick);
            if (tick < RAIN_WINDUP) {
                return true;
            }
            long t = tick - RAIN_WINDUP;
            int due = (int) ((t + 1) * arrows / duration - t * arrows / duration);
            if (due > 0) {
                rain.drop(due);
            }
            return true;
        }, rain::closeReticle);
        return ActivationResult.success();
    }

    /** The cast: a salvo of light motes shot straight up from the archer's bow hand. */
    private void skySalvo(Location eye) {
        World world = eye.getWorld();
        Location hand = eye.clone().subtract(0, 0.3, 0);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int motes = Vfx.n(ctx(), 9);
        for (int i = 0; i < motes; i++) {
            Location sky = hand.clone().add(random.nextGaussian() * 0.8, 9 + random.nextDouble(4),
                    random.nextGaussian() * 0.8);
            Vfx.trail(hand, sky, i % 3 == 0 ? PALETTE.edge() : PALETTE.core(), 8 + random.nextInt(5), true);
        }
        world.spawnParticle(Particle.FLASH, hand, 1, 0, 0, 0, 0, PALETTE.core(), true);
        Vfx.helix(ctx(), player().getLocation(), 0.55, 2.4, 1.5, 2, 12, random.nextDouble(Math.PI * 2),
                Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), false);
    }

    /** One arrow rain: its zone, the reticle that marks it and the per-target hit throttle. */
    private final class Rain {
        private final Location center;
        private final double radius;
        private final Map<UUID, Long> lastHit = new HashMap<>();
        private final List<BlockDisplay> bars = new ArrayList<>();
        private final List<BlockDisplay> ticks = new ArrayList<>();
        private final double startAngle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);

        Rain(Location center, double radius) {
            this.center = center;
            this.radius = radius;
        }

        /**
         * A dashed ring of glowing bars on the ground plus four inward crosshair ticks, spawned wide and closing in
         * on the zone during the wind-up; afterwards it turns slowly until the rain is over.
         */
        void openReticle(long lifetime) {
            Location at = center.clone().add(0, 0.04, 0);
            at.setYaw(0);
            at.setPitch(0);
            long life = lifetime + 10;
            for (int i = 0; i < RETICLE_BARS; i++) {
                double angle = startAngle + i * Math.PI * 2 / RETICLE_BARS;
                BlockDisplay bar = Displays.block(ctx(), player(), at, Material.LIME_STAINED_GLASS.createBlockData(),
                        bar(angle, radius * RETICLE_START), life);
                bars.add(bar);
                Displays.animateNextTick(ctx(), player(), bar, bar(angle, radius), (int) RAIN_WINDUP);
            }
            for (int i = 0; i < 4; i++) {
                double angle = startAngle + Math.PI / RETICLE_BARS + i * Math.PI / 2;
                BlockDisplay tick = Displays.block(ctx(), player(), at, Material.WHITE_CONCRETE.createBlockData(),
                        crosshair(angle, radius * RETICLE_START), life);
                Displays.outline(tick, PALETTE.edge());
                ticks.add(tick);
                Displays.animateNextTick(ctx(), player(), tick, crosshair(angle, radius), (int) RAIN_WINDUP);
            }
        }

        /** Turns the reticle to its angle {@code tick} ticks into the rain, over the next 10 ticks. */
        private void turnReticle(long tick) {
            double spin = tick * 0.012;
            for (int i = 0; i < bars.size(); i++) {
                Displays.animate(bars.get(i), bar(startAngle + spin + i * Math.PI * 2 / RETICLE_BARS, radius), 10);
            }
            for (int i = 0; i < ticks.size(); i++) {
                double angle = startAngle + spin + Math.PI / RETICLE_BARS + i * Math.PI / 2;
                Displays.animate(ticks.get(i), crosshair(angle, radius), 10);
            }
        }

        void closeReticle() {
            for (BlockDisplay display : bars) {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), player(), display, 6);
                }
            }
            for (BlockDisplay display : ticks) {
                if (display.isValid()) {
                    Displays.shrinkAway(ctx(), player(), display, 6);
                }
            }
            // Afterglow: the zone edge fades out in a ring of rising motes.
            Location edge = center.clone().add(0, 0.2, 0);
            Vfx.ring(ctx(), edge, radius, (int) Math.round(radius * 6), startAngle, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.fade(1.1f), true);
            Vfx.ring(ctx(), edge.clone().add(0, 0.3, 0), radius * 0.9, (int) Math.round(radius * 2), startAngle,
                    Particle.END_ROD, null, false);
            Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f + Fx.jitter(0.1f));
        }

        void drawZone(long tick) {
            if (tick >= RAIN_WINDUP && (tick - RAIN_WINDUP) % 10 == 0) {
                turnReticle(tick - RAIN_WINDUP + 10);
            }
            if (tick % 4 == 0) {
                // A sparse particle ring for spectators far away and a soft glow drifting inside the zone.
                Vfx.ring(ctx(), center.clone().add(0, 0.12, 0), radius, (int) Math.max(12, Math.min(36, radius * 5)),
                        -tick * 0.04, Particle.DUST, PALETTE.dust(1.0f), true);
                Vfx.dot(Vfx.scatter(center, radius).add(0, 0.2, 0), Particle.DUST_COLOR_TRANSITION,
                        PALETTE.glow(0.8f), false);
            }
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
                prepare(arrow, RAIN_TINT, TAG_RAIN);
                rainArrows.put(arrow.getUniqueId(), this);
                // A falling streak of light ahead of the arrow (same speed), so the rain reads from afar.
                int fall = (int) Math.max(1, Math.round(height / RAIN_SPEED));
                Vfx.trail(spawn.clone().add(0, 1.5, 0), base, random.nextBoolean() ? PALETTE.core() : PALETTE.body(),
                        fall + 1, true);
            }
        }

        void impact(Location at) {
            World world = at.getWorld();
            Vfx.cloud(ctx(), at, Particle.CRIT, 4, 0.15, 0.15, null, false);
            Vfx.ring(ctx(), at.clone().add(0, 0.1, 0), 0.4, 7, ThreadLocalRandom.current().nextDouble(Math.PI),
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.7f), false);
            Block below = at.clone().subtract(0, 0.2, 0).getBlock();
            if (below.getType().isSolid()) {
                world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 4), 0.15, 0.05, 0.15, 0.1, below.getBlockData());
            }
            long now = ctx().currentTick();
            long interval = params().ticks("rain_hit_interval");
            double damage = params().num("rain_damage");
            for (LivingEntity target : ctx().enemiesNear(player(), at, RAIN_SPLASH)) {
                Long last = lastHit.get(target.getUniqueId());
                if (last != null && now - last < interval) {
                    continue;
                }
                lastHit.put(target.getUniqueId(), now);
                if (arrowHit(target, damage)) {
                    arrowImpact(target.getLocation().add(0, target.getHeight() * 0.6, 0));
                    player().playSound(player(), Sound.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.4f,
                            1.1f + Fx.jitter(0.1f));
                }
            }
        }
    }

    /** A thin glowing bar lying on the ground, tangent to the circle of {@code distance} at {@code angle}. */
    private static Transformation bar(double angle, double distance) {
        float length = (float) (2 * distance * Math.sin(Math.PI / RETICLE_BARS) * 0.72);
        // rotateY(90° - angle) lays the bar's length (local X) along the tangent; transform() takes -yaw.
        return Displays.transform((float) (Math.cos(angle) * distance), 0, (float) (Math.sin(angle) * distance),
                length, BAR_HEIGHT, BAR_WIDTH, (float) Math.toDegrees(angle) - 90f, 0, 0);
    }

    /** A short crosshair tick pointing from the circle of {@code distance} towards its center. */
    private static Transformation crosshair(double angle, double distance) {
        float length = (float) Math.max(0.4, distance * 0.22);
        double middle = distance - length * 0.5 - 0.15;
        return Displays.transform((float) (Math.cos(angle) * middle), 0.01f, (float) (Math.sin(angle) * middle),
                length, BAR_HEIGHT, BAR_WIDTH * 0.8f, (float) Math.toDegrees(angle), 0, 0);
    }

    private void rainHit(ProjectileHitEvent event, AbstractArrow arrow) {
        Entity hit = event.getHitEntity();
        if (hit != null) {
            event.setCancelled(true);
            if (!(hit instanceof LivingEntity target) || !ctx().isEnemy(player(), target)) {
                return; // pass through allies and the owner, the arrow keeps falling
            }
            Rain rain = rainArrows.remove(arrow.getUniqueId());
            streaks.remove(arrow.getUniqueId());
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
        addStreak(arrow);
        removeLater(arrow, ARROW_LIFETIME);
    }

    /** Removes an ability arrow after {@code ticks}; a stuck one dissolves into green motes. */
    private void removeLater(AbstractArrow arrow, long ticks) {
        UUID id = arrow.getUniqueId();
        ctx().runLater(player(), ticks, () -> {
            volleyArrows.remove(id);
            rainArrows.remove(id);
            streaks.remove(id);
            if (arrow.isValid()) {
                if (arrow.isInBlock()) {
                    Vfx.cloud(ctx(), arrow.getLocation(), Particle.DUST_COLOR_TRANSITION, 4, 0.12, 0,
                            PALETTE.fade(0.7f), false);
                }
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
