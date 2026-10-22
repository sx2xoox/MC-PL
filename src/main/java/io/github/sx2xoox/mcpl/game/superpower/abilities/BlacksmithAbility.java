package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.kyori.adventure.text.format.TextColor;
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
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 대장장이 — the smith. Every melee hit heats the blade, and the hit that brings it to a white heat is a crushing blow
 * that cracks the victim's armor; the active drops an anvil out of the sky onto an aimed spot after its shadow gives
 * it away; the ultimate sets up a forge on the spot: everyone on the smith's side near it fights with red-hot weapons
 * while a giant hammer pounds the anvil and every blow sends a shockwave through the enemies around.
 *
 * <p>Unlike the 무도가 (third hit on the same enemy in a row stuns) the smith's heat builds on any enemy and its blow
 * breaks armor; unlike the 거인's leap and slam, the forge is a fixed workshop that arms the team and keeps pounding.
 *
 * <p>Look: forge orange, iron grey and soot. The heat shows as embers and little flames at the smith's hand that grow
 * with every step; the blow rings an anvil, throws a fountain of sparks and a cracked chestplate flies off the victim
 * while iron flakes keep falling from it as long as its armor is broken. The anvil's shadow spreads on the ground
 * (a dark plate with an orange warning ring) until the anvil plummets out of the sky with a whistle; it lands with a
 * clang, a ring of dust and stars circling over the dazed. The forge is an anvil, a lit blast furnace and a bed of
 * glowing coals in a heat ring, with a giant hammer of dark oak and iron that rises, glows and comes down on the anvil.
 */
public final class BlacksmithAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("blacksmith", "대장장이", Material.SMITHING_TABLE)
            .color(TextColor.color(0xFF7A1A))
            .difficulty(1)
            .passive("단련",
                    "근접 공격이 적중할 때마다 무기가 달아오르고, {heat_max}번째 적중은 강타가 되어 {strike_damage} 추가 "
                            + "피해를 주고 {sunder_duration}초간 적의 방어력을 {sunder_armor} 깎습니다. {heat_decay}초간 "
                            + "적중하지 못하면 무기가 식습니다.")
            .active("모루 낙하",
                    "조준한 곳({anvil_range}블록 이내)에 그림자가 드리우고 {anvil_delay}초 뒤 하늘에서 모루가 떨어집니다. "
                            + "반경 {anvil_radius}블록의 적은 {anvil_damage} 피해를 입고 {anvil_stun}초간 기절합니다"
                            + "(이동·점프 불가).",
                    9)
            .ultimate("대장간",
                    "앞에 {forge_duration}초간 대장간을 세웁니다. 대장간 반경 {forge_radius}블록 안의 나와 아군은 무기가 "
                            + "달궈져 근접 공격력이 {forge_bonus} 오릅니다. 거대한 망치가 {hammer_period}초마다 모루를 "
                            + "내리쳐, 반경 {forge_radius}블록까지 퍼지는 충격파가 적에게 {hammer_damage} 피해를 주고 "
                            + "밀쳐냅니다.")
            .param("heat_max", 3)
            .param("heat_decay", 4)
            .param("strike_damage", 3)
            .param("sunder_armor", 4)
            .param("sunder_duration", 4)
            .param("anvil_range", 18)
            .param("anvil_delay", 0.7)
            .param("anvil_radius", 1.8)
            .param("anvil_damage", 5)
            .param("anvil_stun", 1)
            .param("forge_duration", 8)
            .param("forge_radius", 6)
            .param("forge_bonus", 2)
            .param("hammer_period", 2)
            .param("hammer_damage", 2.5)
            .build();

    private static final String COLOR_TAG = "#FF7A1A";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** White-hot core, forge orange, soot. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF0C2, 0xFF7A1A, 0x2A1E18);
    private static final Particle.DustTransition HOT = PALETTE.glow(1.0f);
    private static final Particle.DustTransition COOLING = PALETTE.fade(0.9f);
    private static final Particle.DustOptions ORANGE = PALETTE.dust(1.1f);
    private static final Particle.DustOptions SOOT = new Particle.DustOptions(PALETTE.edge(), 1.2f);
    private static final Particle.DustOptions IRON = new Particle.DustOptions(Color.fromRGB(0xA9B0B7), 0.8f);
    private static final Particle.DustOptions STAR = new Particle.DustOptions(Color.fromRGB(0xFFE066), 0.9f);
    private static final Particle.DustOptions SHADOW = new Particle.DustOptions(Color.fromRGB(0x15110E), 1.4f);

    /** Block and item types of the visuals (block data is created on use: it needs a running server). */
    private static final Material ANVIL = Material.ANVIL;
    private static final Material SHADOW_BLOCK = Material.BLACK_CONCRETE;
    private static final Material ARMOR_ITEM = Material.IRON_CHESTPLATE;
    private static final Material IRON_FLAKE = Material.IRON_BLOCK;
    private static final Material FURNACE = Material.BLAST_FURNACE;
    private static final Material COALS = Material.MAGMA_BLOCK;
    private static final Material HANDLE = Material.STRIPPED_DARK_OAK_LOG;
    private static final Material HEAD = Material.IRON_BLOCK;

    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 1.8;

    // passive: tempering
    private static final long HEAT_FX_PERIOD = 5;
    private static final long SUNDER_FX_PERIOD = 8;
    private static final int STRIKE_LABEL_TICKS = 16;

    // active: anvil drop
    private static final double DROP_HEIGHT = 9;
    private static final int GROUND_SNAP = 8;
    /** The anvil falls during this many ticks at the end of the delay. */
    private static final int FALL_TICKS = 5;
    private static final float ANVIL_SIZE = 1.0f;
    private static final long ANVIL_REST_TICKS = 14;
    private static final int STAR_COUNT = 3;

    // ultimate: forge
    private static final double FORGE_AHEAD = 1.6;
    private static final float HANDLE_LENGTH = 2.6f;
    private static final float RAISED_PITCH = -105f;
    private static final long RAISE_TICKS = 10;
    private static final long SLAM_TICKS = 2;
    private static final long WAVE_TICKS = 6;
    private static final long BUFF_PERIOD = 5;
    private static final double WAVE_BAND = 0.9;
    private static final double WAVE_HEIGHT = 2.5;
    private static final double WAVE_PUSH = 0.9;
    private static final double WAVE_LIFT = 0.35;

    // tempering state
    private int heat;
    private long lastHeat = Long.MIN_VALUE / 2;
    /** The armor-break modifier on each victim, so a new blow replaces the old one instead of stacking. */
    private final Map<UUID, NamespacedKey> sunders = new HashMap<>();
    private final List<Display> displays = new ArrayList<>();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        heat = 0;
        sunders.clear();
        displays.clear();
    }

    // ------------------------------------------------------------------ passive: tempering

    private int heatMax() {
        return Math.max(1, params().integer("heat_max"));
    }

    /** The owner's own melee swing at an enemy (not ability damage, projectiles or summons). */
    private boolean isOwnMelee(EntityDamageByEntityEvent event, LivingEntity victim) {
        return !ctx().isAbilityDamage() && player().equals(event.getDamager())
                && event.getCause() == DamageCause.ENTITY_ATTACK && ctx().isEnemy(player(), victim);
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (!isOwnMelee(event, victim)) {
            return;
        }
        Player owner = player();
        heat++;
        lastHeat = ctx().currentTick();
        if (heat >= heatMax()) {
            heat = 0;
            // The blow follows the swing: dealt a tick later, so it never runs inside this hit.
            ctx().runLater(owner, 1, () -> blow(owner, victim));
            return;
        }
        Location hand = hand(owner);
        Vfx.cloud(ctx(), Fx.body(victim), Particle.LAVA, 1 + heat, 0.2, 0, null, false);
        Vfx.cloud(ctx(), hand, Particle.FLAME, 2 + 2 * heat, 0.1, 0.03, null, false);
        Fx.sound(Fx.body(victim), Sound.BLOCK_ANVIL_USE, 0.35f, 1.4f + 0.2f * heat + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">✹ 단련</" + COLOR_TAG + "> <white>" + meter(heat, heatMax())
                + "</white>"));
    }

    /** The white-hot blow: extra damage that ignores armor, then the armor itself cracks for a while. */
    private void blow(Player owner, LivingEntity victim) {
        if (!Combat.isTargetable(ctx(), owner, victim)) {
            return;
        }
        Combat.hit(ctx(), owner, victim, params().num("strike_damage"));
        long ticks = params().ticks("sunder_duration");
        double armor = Math.max(0, params().num("sunder_armor"));
        Location body = Fx.body(victim);
        if (Combat.isTargetable(ctx(), owner, victim) && ticks > 0 && armor > 0) {
            NamespacedKey old = sunders.remove(victim.getUniqueId());
            if (old != null) {
                ctx().removeModifier(victim, old);
            }
            NamespacedKey key = ctx().addModifier(owner, victim, Attribute.ARMOR, -armor, Operation.ADD_NUMBER, ticks);
            sunders.put(victim.getUniqueId(), key);
            flakes(owner, victim, key, ticks);
        }
        if (!isHidden(owner, victim)) {
            blowFx(owner, victim, body);
        }
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⚒ 강타!</b></" + COLOR_TAG + "> <gray>방어력 -"
                + AbilityInfo.formatNumber(armor) + " · " + AbilityInfo.formatNumber(ticks / 20.0) + "초</gray>"));
    }

    private void blowFx(Player owner, LivingEntity victim, Location body) {
        World world = body.getWorld();
        Vfx.dot(body, Particle.FLASH, PALETTE.core(), false);
        Vfx.burst(ctx(), body, PALETTE, 0.7, Particle.FLAME, false);
        world.spawnParticle(Particle.LAVA, body, Vfx.n(ctx(), 6), 0.2, 0.2, 0.2, 0);
        world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 12), 0.3, 0.4, 0.3, 0.1, IRON_FLAKE.createBlockData());
        // A fountain of sparks thrown up from the blow.
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < Vfx.n(ctx(), 6); i++) {
            Vector up = new Vector(random.nextGaussian() * 0.25, 1, random.nextGaussian() * 0.25).normalize();
            world.spawnParticle(Particle.WAX_OFF, body.getX(), body.getY(), body.getZ(), 0, up.getX(), up.getY(),
                    up.getZ(), 0.6);
        }
        Fx.sound(body, Sound.BLOCK_ANVIL_LAND, 0.8f, 1.3f + Fx.jitter(0.08f));
        Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_ITEM_BREAK, 0.8f, 0.7f + Fx.jitter(0.05f));
        if (reserve(2)) {
            // The cracked chestplate flies off the victim, spinning, and is gone.
            Vector away = Motion.horizontal(body.toVector().subtract(owner.getLocation().toVector()));
            if (away.lengthSquared() < 1.0E-6) {
                away = Motion.horizontalFacing(owner.getLocation());
            }
            ItemDisplay plate = Displays.item(ctx(), owner, body, new ItemStack(ARMOR_ITEM),
                    Displays.centered(0, 0, 0, 0.7f, 0.7f, 0.7f, yawOf(away), 0, 0), 12);
            plate.setTeleportDuration(6);
            displays.add(plate);
            Location landing = body.clone().add(away.multiply(1.3)).add(0, 0.6, 0);
            ctx().runLater(owner, 1, () -> {
                if (plate.isValid()) {
                    Displays.moveTo(plate, landing);
                    Displays.animate(plate, Displays.centered(0, 0, 0, 0.5f, 0.5f, 0.5f, random.nextFloat() * 360, 70,
                            40), 6);
                }
            });
            ctx().runLater(owner, 7, () -> {
                if (plate.isValid()) {
                    Displays.shrinkAway(ctx(), owner, plate, 4);
                }
            });
            TextDisplay label = Displays.text(ctx(), owner, body.clone().add(0, 1.2, 0),
                    Text.mm("<b><gradient:#FFF0C2:" + COLOR_TAG + ">강타!</gradient></b>"), 0.55f, STRIKE_LABEL_TICKS);
            displays.add(label);
            Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0.3f, 0, 1.15f, 1.15f, 1.15f, 0, 0, 0),
                    4);
        }
    }

    /** While the armor is broken, iron flakes keep falling from the victim. */
    private void flakes(Player owner, LivingEntity victim, NamespacedKey key, long ticks) {
        BlockData flake = IRON_FLAKE.createBlockData();
        Loop.repeat(ctx(), owner, SUNDER_FX_PERIOD, SUNDER_FX_PERIOD, Math.max(1, ticks / SUNDER_FX_PERIOD), run -> {
            if (!Combat.isTargetable(ctx(), owner, victim) || !key.equals(sunders.get(victim.getUniqueId()))) {
                return false;
            }
            if (isHidden(owner, victim)) {
                return true;
            }
            Location chest = victim.getLocation().add(0, victim.getHeight() * 0.65, 0);
            chest.getWorld().spawnParticle(Particle.FALLING_DUST, chest, 2, 0.2, 0.2, 0.2, 0, flake);
            Vfx.dot(chest, Particle.DUST, IRON, false);
            return true;
        }, () -> {
            if (key.equals(sunders.get(victim.getUniqueId()))) {
                sunders.remove(victim.getUniqueId());
            }
        });
    }

    /** The blade glows with the heat it holds; it cools down when the smith stops landing hits. */
    @Override
    public void onTick(long tick) {
        if (heat <= 0) {
            return;
        }
        Player owner = player();
        if (ctx().currentTick() - lastHeat >= params().ticks("heat_decay")) {
            heat = 0;
            Location hand = hand(owner);
            Vfx.cloud(ctx(), hand, Particle.CLOUD, 3, 0.08, 0.02, null, false);
            Fx.sound(hand, Sound.BLOCK_FIRE_EXTINGUISH, 0.35f, 1.6f);
            return;
        }
        if (tick % HEAT_FX_PERIOD == 0) {
            Location hand = hand(owner);
            Vfx.dot(hand, Particle.SMALL_FLAME, null, false);
            Vfx.cloud(ctx(), hand, Particle.DUST_COLOR_TRANSITION, heat, 0.08, 0, heat >= heatMax() - 1 ? HOT : COOLING,
                    false);
            if (heat >= heatMax() - 1) {
                Vfx.dot(hand.clone().add(0, 0.1, 0), Particle.LAVA, null, false);
            }
        }
    }

    private static String meter(int filled, int max) {
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < max; i++) {
            bar.append(i < filled ? '▰' : '▱');
        }
        return bar.toString();
    }

    // ------------------------------------------------------------------ active: anvil drop

    @Override
    public ActivationResult onActive() {
        Location spot = aimPoint(params().num("anvil_range"));
        if (spot == null) {
            return ActivationResult.fail("모루를 떨어뜨릴 땅이 없습니다");
        }
        new AnvilDrop(player(), spot).start();
        return ActivationResult.success();
    }

    /**
     * Where the anvil lands: under the aimed enemy, or where the view ray hits a block (or ends), snapped down to the
     * ground. Null when there is no ground close below.
     */
    private @Nullable Location aimPoint(double range) {
        Player owner = player();
        LivingEntity aimed = ctx().findTarget(owner, range);
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        if (!(direction.lengthSquared() > 1.0E-8)) {
            direction = Motion.horizontalFacing(eye);
        }
        direction.normalize();
        Location point;
        if (aimed != null) {
            point = aimed.getLocation().add(0, 0.5, 0);
        } else {
            RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
            point = hit == null
                    ? eye.clone().add(direction.clone().multiply(range))
                    : hit.getHitPosition().toLocation(owner.getWorld()).subtract(direction.clone().multiply(0.3));
        }
        // Start the ground probe above the surface: a ray starting on a face would report a hit inside the block.
        point.add(0, 0.05, 0);
        double before = point.getY();
        Spots.snapToGround(owner.getWorld(), point, GROUND_SNAP);
        if (point.getY() >= before - 1.0E-6) {
            return null;
        }
        point.setYaw(0);
        point.setPitch(0);
        return point;
    }

    /** The anvil: its shadow spreads, a whistle, it plummets and lands with a clang; the dazed see stars. */
    private final class AnvilDrop implements LongPredicate {

        private final Player owner;
        private final Location ground;
        private final long delay;
        private final double radius;
        private final float yaw;
        private @Nullable BlockDisplay shadow;
        private @Nullable BlockDisplay anvil;

        AnvilDrop(Player owner, Location ground) {
            this.owner = owner;
            this.ground = ground;
            this.delay = Math.max(FALL_TICKS + 2L, params().ticks("anvil_delay"));
            this.radius = Math.max(0.5, params().num("anvil_radius"));
            this.yaw = owner.getLocation().getYaw();
        }

        void start() {
            if (reserve(1)) {
                shadow = Displays.block(ctx(), owner, ground.clone().add(0, 0.02, 0), SHADOW_BLOCK.createBlockData(),
                        Displays.transform(0, 0, 0, 0.2f, 0.01f, 0.2f, 45, 0, 0), delay + 6);
                shadow.setBrightness(new Display.Brightness(0, 0));
                displays.add(shadow);
                Displays.animateNextTick(ctx(), owner, shadow,
                        Displays.transform(0, 0, 0, (float) radius * 1.3f, 0.01f, (float) radius * 1.3f, 45, 0, 0),
                        (int) delay - 1);
            }
            if (reserve(1)) {
                anvil = Displays.block(ctx(), owner, sky(), ANVIL.createBlockData(), anvilPose(0.4f, 0), delay + 30);
                anvil.setTeleportDuration(1);
                anvil.setViewRange(2.0f);
                displays.add(anvil);
                Displays.animateNextTick(ctx(), owner, anvil, anvilPose(ANVIL_SIZE, 0), 4);
            }
            Fx.sound(ground, Sound.BLOCK_ANVIL_PLACE, 0.5f, 1.8f);
            Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 0.6f);
            Loop.repeat(ctx(), owner, 1, 1, delay, this, this::land);
        }

        private Location sky() {
            return ground.clone().add(0, DROP_HEIGHT, 0);
        }

        @Override
        public boolean test(long tick) {
            long left = delay - tick;
            double progress = (tick + 1) / (double) delay;
            // Telegraph: a warning ring that pulses in the shadow.
            if (tick % 2 == 0) {
                Vfx.ring(ctx(), ground.clone().add(0, 0.06, 0), radius, (int) Math.max(12, radius * 9), tick * 0.2,
                        Particle.DUST_COLOR_TRANSITION, HOT, true);
                Vfx.ring(ctx(), ground.clone().add(0, 0.05, 0), radius * progress, 10, -tick * 0.3, Particle.DUST,
                        SHADOW, false);
            }
            BlockDisplay falling = anvil;
            if (falling != null && falling.isValid() && left <= FALL_TICKS) {
                double drop = (FALL_TICKS - left + 1) / (double) FALL_TICKS;
                Location at = sky().subtract(0, DROP_HEIGHT * drop * drop, 0);
                Displays.moveTo(falling, at);
                Vfx.dot(at.clone().add(0, 1.2, 0), Particle.CLOUD, null, false);
                if (left == FALL_TICKS) {
                    Fx.sound(ground, Sound.ENTITY_ARROW_SHOOT, 0.9f, 0.5f);
                    Fx.sound(ground, Sound.ITEM_ELYTRA_FLYING, 0.3f, 1.8f);
                }
            } else if (tick % 4 == 0 && falling != null && falling.isValid()) {
                // The anvil sways high above, its rope creaking.
                Vfx.dot(sky().add(0, 1.1, 0), Particle.SMOKE, null, false);
            }
            return true;
        }

        /** Clang: everyone in the ring is hit and dazed; the anvil rests a moment, then sinks. */
        private void land() {
            BlockDisplay shade = shadow;
            if (shade != null && shade.isValid()) {
                shade.remove();
            }
            World world = ground.getWorld();
            BlockDisplay landed = anvil;
            if (landed != null && landed.isValid()) {
                Displays.moveTo(landed, ground);
                Displays.animate(landed, anvilPose(ANVIL_SIZE, 6), 2);
            }
            world.spawnParticle(Particle.BLOCK, ground.clone().add(0, 0.1, 0), Vfx.n(ctx(), 24), radius * 0.4, 0.1,
                    radius * 0.4, 0.15, ground.clone().subtract(0, 0.5, 0).getBlock().getBlockData(), true);
            world.spawnParticle(Particle.DUST_PLUME, ground, Vfx.n(ctx(), 10), radius * 0.4, 0.05, radius * 0.4, 0.03,
                    null, true);
            Vfx.dot(ground.clone().add(0, 0.6, 0), Particle.FLASH, PALETTE.core(), true);
            Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.08, 0), 0.4, radius + 0.6, 5, Particle.DUST, SOOT, true);
            Vfx.cloud(ctx(), ground.clone().add(0, 0.4, 0), Particle.WAX_OFF, 8, 0.4, 0.3, null, true);
            Fx.sound(ground, Sound.BLOCK_ANVIL_LAND, 1.5f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(ground, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.0f, 0.9f + Fx.jitter(0.05f));
            Fx.sound(ground, Sound.BLOCK_ANVIL_HIT, 1.0f, 0.6f);

            double damage = params().num("anvil_damage");
            long stun = params().ticks("anvil_stun");
            int hits = 0;
            Location center = ground.clone().add(0, 0.5, 0);
            LivingEntity crowned = null;
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                if (Math.abs(enemy.getLocation().getY() - ground.getY()) > 2.5) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                hits++;
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                if (stun > 0) {
                    ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, stun);
                    ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, stun);
                    stars(enemy, stun);
                }
                if (crowned == null && horizontal(enemy.getLocation(), ground) < 0.9 && !isHidden(owner, enemy)) {
                    crowned = enemy;
                }
            }
            if (landed != null && landed.isValid()) {
                rest(landed, crowned, Math.max(ANVIL_REST_TICKS, stun));
            }
            if (hits > 0) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚒ 모루 낙하</" + COLOR_TAG + "> <white>" + hits
                        + "명 적중!</white>"));
            }
        }

        /** The anvil stays where it fell (on the head of an enemy standing right under it), then sinks away. */
        private void rest(BlockDisplay landed, @Nullable LivingEntity crowned, long ticks) {
            Loop.repeat(ctx(), owner, 2, 1, ticks, run -> {
                if (!landed.isValid()) {
                    return false;
                }
                if (crowned != null && Combat.isTargetable(ctx(), owner, crowned) && !isHidden(owner, crowned)) {
                    Displays.moveTo(landed, crowned.getLocation().add(0, crowned.getHeight() + 0.05, 0));
                } else if (crowned != null) {
                    Displays.moveTo(landed, ground);
                }
                return true;
            }, () -> {
                if (landed.isValid()) {
                    Vfx.cloud(ctx(), landed.getLocation().add(0, 0.4, 0), Particle.POOF, 4, 0.3, 0.02, null, false);
                    Displays.shrinkAway(ctx(), owner, landed, 5);
                }
            });
        }

        /** Three little stars circling a dazed head. */
        private void stars(LivingEntity victim, long ticks) {
            Fx.sound(Fx.body(victim), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.6f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), owner, 1, 2, Math.max(1, ticks / 2), run -> {
                if (!Combat.isTargetable(ctx(), owner, victim) || isHidden(owner, victim)) {
                    return false;
                }
                Location head = victim.getLocation().add(0, victim.getHeight() + 0.3, 0);
                for (int i = 0; i < STAR_COUNT; i++) {
                    double angle = run * 0.9 + i * TAU / STAR_COUNT;
                    Vfx.dot(head.clone().add(Math.cos(angle) * 0.45, 0.05 * Math.sin(run + i), Math.sin(angle) * 0.45),
                            Particle.DUST, STAR, false);
                }
                return true;
            });
        }

        /** The anvil upright, turned to the smith's facing (in its transformation only), lightly tilted on impact. */
        private Transformation anvilPose(float size, float tilt) {
            return Displays.transform(0, size / 2, 0, size, size, size, yaw, tilt, 0);
        }
    }

    // ------------------------------------------------------------------ ultimate: forge

    @Override
    public ActivationResult onUltimate() {
        new Forge(player()).build();
        return ActivationResult.success();
    }

    /**
     * The forge: anvil, blast furnace and coals inside a heat ring; allies inside wield red-hot weapons, and a giant
     * hammer pounds the anvil every period, each blow a shockwave through the enemies around.
     */
    private final class Forge implements LongPredicate {

        private final Player owner;
        private final Location center;
        private final World world;
        private final float yaw;
        /** The hammer is swung from beyond the anvil, back towards the smith. */
        private final float hammerYaw;
        private final Vector forward;
        private final Vector side;
        private final long duration;
        private final long period;
        private final double radius;
        private final List<BlockDisplay> parts = new ArrayList<>();
        private @Nullable BlockDisplay handle;
        private @Nullable BlockDisplay head;
        private final Map<UUID, NamespacedKey> buffs = new HashMap<>();
        private final Map<UUID, Player> buffed = new HashMap<>();
        private long slamAt = -1;
        private long waveStart = -1;
        private final Set<UUID> waveHit = new HashSet<>();
        private int blows;
        private boolean cooled;

        Forge(Player owner) {
            this.owner = owner;
            this.world = owner.getWorld();
            this.yaw = owner.getLocation().getYaw();
            this.hammerYaw = yaw + 180;
            this.forward = Motion.horizontalFacing(owner.getLocation());
            this.side = new Vector(-forward.getZ(), 0, forward.getX());
            this.duration = Math.max(20, params().ticks("forge_duration"));
            this.period = Math.max(RAISE_TICKS + SLAM_TICKS + 4, params().ticks("hammer_period"));
            this.radius = Math.max(1, params().num("forge_radius"));
            this.center = site(owner);
        }

        /** In front of the smith when there is room and ground there, otherwise at its feet. */
        private Location site(Player owner) {
            Location feet = owner.getLocation();
            Location ahead = feet.clone().add(forward.clone().multiply(FORGE_AHEAD)).add(0, 0.05, 0);
            Spots.snapToGround(world, ahead, 2.5);
            boolean room = Spots.isFree(world, ahead, 1.0, 1.0) && ctx().isInsideArena(ahead)
                    && Math.abs(ahead.getY() - feet.getY()) <= 1.5
                    && Spots.clearPath(feet.clone().add(0, 0.5, 0), ahead.clone().add(0, 0.5, 0));
            Location spot = room ? ahead : feet.clone().add(0, 0.05, 0);
            if (!room) {
                Spots.snapToGround(world, spot, 4);
            }
            spot.setYaw(0);
            spot.setPitch(0);
            return spot;
        }

        void build() {
            BlockData furnace = FURNACE.createBlockData();
            if (furnace instanceof Lightable lit) {
                lit.setLit(true);
            }
            Location anvilAt = center.clone();
            Location furnaceAt = center.clone().add(side.clone().multiply(1.5)).add(forward.clone().multiply(-0.4));
            Location coalsAt = center.clone().add(side.clone().multiply(-1.4)).add(forward.clone().multiply(-0.3));
            long life = duration + 20;
            if (reserve(5)) {
                spawnPart(anvilAt, ANVIL.createBlockData(), Displays.transform(0, 0.5f, 0, 1, 1, 1, yaw + 90, 0, 0),
                        life);
                if (!furnaceAt.getBlock().getType().isOccluding()) {
                    spawnPart(furnaceAt, furnace, Displays.transform(0, 0.5f, 0, 1, 1, 1, yaw, 0, 0), life);
                }
                if (!coalsAt.getBlock().getType().isOccluding()) {
                    spawnPart(coalsAt, COALS.createBlockData(), Displays.transform(0, 0.15f, 0, 1.0f, 0.3f, 1.0f,
                            yaw + 20, 0, 0), life);
                }
                Location pivot = pivot();
                handle = spawnPart(pivot, HANDLE.createBlockData(), hammerPose(true, 0), life);
                head = spawnPart(pivot, HEAD.createBlockData(), hammerPose(false, 0), life);
            }
            // Raise: every piece pops up out of the ground with sparks and smoke.
            for (BlockDisplay part : parts) {
                Transformation full = part.getTransformation();
                part.setTransformation(new Transformation(full.getTranslation(), full.getLeftRotation(),
                        new Vector3f(full.getScale()).mul(0.05f), full.getRightRotation()));
                Displays.animateNextTick(ctx(), owner, part, full, 6);
            }
            Location ground = center.clone().add(0, 0.1, 0);
            world.spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 20), 1.2, 0.1, 1.2, 0.1, FURNACE.createBlockData(),
                    true);
            Vfx.cloud(ctx(), ground.clone().add(0, 0.8, 0), Particle.LAVA, 10, 0.8, 0, null, true);
            Vfx.shockwave(ctx(), owner, ground, 0.5, radius, 8, Particle.DUST_COLOR_TRANSITION, HOT, true);
            Fx.sound(center, Sound.BLOCK_ANVIL_PLACE, 1.4f, 0.8f);
            Fx.sound(center, Sound.BLOCK_SMITHING_TABLE_USE, 1.2f, 0.9f);
            Fx.sound(center, Sound.ITEM_FIRECHARGE_USE, 1.0f, 0.7f);
            Fx.sound(center, Sound.BLOCK_BLASTFURNACE_FIRE_CRACKLE, 1.5f, 1.0f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>⚒ 대장간</b></" + COLOR_TAG + "> <gray>"
                    + AbilityInfo.formatNumber(duration / 20.0) + "초</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, duration + WAVE_TICKS + 1, this, this::close);
        }

        private BlockDisplay spawnPart(Location at, BlockData data, Transformation pose, long life) {
            BlockDisplay part = Displays.block(ctx(), owner, at, data, pose, life);
            part.setViewRange(2.0f);
            parts.add(part);
            displays.add(part);
            return part;
        }

        @Override
        public boolean test(long tick) {
            if (tick < duration) {
                buffAllies(tick);
                hammer(tick);
                ambience(tick);
            } else if (tick == duration) {
                cool();
            }
            wave(tick);
            return true;
        }

        // ---------------------------------------------------------- red-hot weapons

        /** Allies (and the smith) inside the forge's ring carry red-hot weapons; leaving the ring cools them. */
        private void buffAllies(long tick) {
            if (tick % BUFF_PERIOD != 0) {
                return;
            }
            double bonus = params().num("forge_bonus");
            List<Player> inside = new ArrayList<>(ctx().alliesNear(owner, center, radius));
            if (owner.getLocation().distanceSquared(center) <= radius * radius) {
                inside.add(owner);
            }
            Set<UUID> present = new HashSet<>();
            long left = Math.max(1, duration - tick);
            for (Player smith : inside) {
                UUID id = smith.getUniqueId();
                present.add(id);
                if (!buffs.containsKey(id) && bonus > 0) {
                    buffs.put(id, ctx().addModifier(owner, smith, Attribute.ATTACK_DAMAGE, bonus, Operation.ADD_NUMBER,
                            left));
                    buffed.put(id, smith);
                    Location hand = hand(smith);
                    Vfx.cloud(ctx(), hand, Particle.FLAME, 8, 0.12, 0.04, null, false);
                    Fx.sound(hand, Sound.ITEM_FIRECHARGE_USE, 0.5f, 1.6f);
                    smith.sendActionBar(Text.mm("<" + COLOR_TAG + ">⚒ 무기 강화</" + COLOR_TAG + "> <white>+"
                            + AbilityInfo.formatNumber(bonus) + "</white> <gray>근접 공격력</gray>"));
                } else if (tick % (BUFF_PERIOD * 2) == 0) {
                    Location hand = hand(smith);
                    Vfx.dot(hand, Particle.SMALL_FLAME, null, false);
                    Vfx.dot(hand.clone().add(0, 0.08, 0), Particle.DUST_COLOR_TRANSITION, HOT, false);
                }
            }
            buffs.entrySet().removeIf(entry -> {
                if (present.contains(entry.getKey())) {
                    return false;
                }
                Player smith = buffed.remove(entry.getKey());
                if (smith != null) {
                    ctx().removeModifier(smith, entry.getValue());
                    Vfx.cloud(ctx(), hand(smith), Particle.CLOUD, 2, 0.05, 0.01, null, false);
                }
                return true;
            });
        }

        // ---------------------------------------------------------- the giant hammer

        /** The hammer's grip: beyond the anvil (seen from the smith), so the head comes down right on it. */
        private Location pivot() {
            return center.clone().add(forward.clone().multiply(HANDLE_LENGTH)).add(0, 1.35, 0);
        }

        /** The hammer is raised over the period and comes down on the anvil at its end. */
        private void hammer(long tick) {
            long phase = (tick + 1) % period;
            long raiseStart = period - SLAM_TICKS - RAISE_TICKS;
            if (phase >= raiseStart && phase < period - SLAM_TICKS && (phase - raiseStart) % 2 == 0) {
                // Raised in steps, so the head follows an arc rather than cutting across it.
                double t = (phase - raiseStart + 2) / (double) RAISE_TICKS;
                poseHammer((float) (RAISED_PITCH * Math.min(1.0, t)), 2);
                if (phase == raiseStart) {
                    Fx.sound(pivot(), Sound.BLOCK_PISTON_EXTEND, 0.6f, 0.6f);
                }
                if (head != null && head.isValid() && t >= 0.8) {
                    Displays.outline(head, PALETTE.body());
                }
            } else if (phase == period - SLAM_TICKS) {
                poseHammer(RAISED_PITCH * 0.5f, 1);
                Fx.sound(center, Sound.ITEM_MACE_SMASH_AIR, 1.0f, 0.6f);
                // Telegraph: the heat ring flares a moment before the blow.
                Vfx.ring(ctx(), center.clone().add(0, 0.1, 0), radius, (int) Math.min(64, radius * 8), 0,
                        Particle.DUST_COLOR_TRANSITION, HOT, true);
            } else if (phase == period - SLAM_TICKS + 1) {
                poseHammer(0, 1);
            } else if (phase == 0) {
                slam(tick);
            }
        }

        private void poseHammer(float pitch, int ticks) {
            if (handle != null && handle.isValid()) {
                Displays.animate(handle, hammerPose(true, pitch), ticks);
            }
            if (head != null && head.isValid()) {
                Displays.animate(head, hammerPose(false, pitch), ticks);
            }
        }

        /** The blow: a clang, a fountain of sparks off the anvil and a shockwave rolling out over the ground. */
        private void slam(long tick) {
            blows++;
            slamAt = tick;
            waveStart = tick;
            waveHit.clear();
            if (head != null && head.isValid()) {
                head.setGlowing(false);
            }
            Location top = center.clone().add(0, 1.05, 0);
            Vfx.dot(top, Particle.FLASH, PALETTE.core(), true);
            Vfx.cloud(ctx(), top, Particle.LAVA, 8, 0.3, 0, null, true);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 14); i++) {
                Vector spray = new Vector(random.nextGaussian() * 0.6, 0.8 + random.nextDouble() * 0.6,
                        random.nextGaussian() * 0.6).normalize();
                world.spawnParticle(Particle.WAX_OFF, top.getX(), top.getY(), top.getZ(), 0, spray.getX(), spray.getY(),
                        spray.getZ(), 0.9, null, true);
            }
            world.spawnParticle(Particle.BLOCK, center.clone().add(0, 0.1, 0), Vfx.n(ctx(), 16), 1.0, 0.1, 1.0, 0.1,
                    center.clone().subtract(0, 0.5, 0).getBlock().getBlockData(), true);
            Fx.sound(center, Sound.BLOCK_ANVIL_LAND, 1.6f, 0.6f + 0.05f * blows);
            Fx.sound(center, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.4f, 0.8f);
            Fx.sound(center, Sound.BLOCK_ANVIL_USE, 1.0f, 0.5f);
        }

        /** The shockwave runs outward from the anvil and strikes each enemy once as it passes. */
        private void wave(long tick) {
            if (waveStart < 0) {
                return;
            }
            long age = tick - waveStart;
            if (age > WAVE_TICKS) {
                waveStart = -1;
                return;
            }
            double front = 0.6 + (radius - 0.6) * age / WAVE_TICKS;
            Location ground = center.clone().add(0, 0.12, 0);
            Vfx.ring(ctx(), ground, front, (int) Math.min(60, Math.max(12, front * 8)), age * 0.2,
                    Particle.DUST_COLOR_TRANSITION, HOT, true);
            Vfx.ring(ctx(), ground.clone().add(0, 0.25, 0), Math.max(0.3, front - 0.4),
                    (int) Math.min(40, Math.max(8, front * 5)), -age * 0.2, Particle.DUST, SOOT, false);
            double damage = params().num("hammer_damage");
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, front + WAVE_BAND)) {
                if (waveHit.contains(enemy.getUniqueId())
                        || Math.abs(enemy.getLocation().getY() - center.getY()) > WAVE_HEIGHT
                        || horizontal(enemy.getLocation(), center) < front - WAVE_BAND * 2) {
                    continue;
                }
                waveHit.add(enemy.getUniqueId());
                Combat.hit(ctx(), owner, enemy, damage);
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(center.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    away = forward.clone();
                }
                Motion.push(enemy, away.multiply(WAVE_PUSH).setY(WAVE_LIFT), MAX_SPEED);
                if (!isHidden(owner, enemy)) {
                    Vfx.burst(ctx(), Fx.body(enemy), PALETTE, 0.5, Particle.FLAME, false);
                }
            }
        }

        /** Smoke from the furnace, embers from the coals and the heat ring breathing at the forge's edge. */
        private void ambience(long tick) {
            Location chimney = center.clone().add(side.clone().multiply(1.5)).add(forward.clone().multiply(-0.4))
                    .add(0, 1.2, 0);
            if (tick % 3 == 0) {
                world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, chimney, 0, 0, 0.06, 0, 1);
            }
            if (tick % 4 == 1) {
                Location coals = center.clone().add(side.clone().multiply(-1.4)).add(0, 0.35, 0);
                Vfx.dot(Vfx.scatter(coals, 0.45), Particle.LAVA, null, false);
                Vfx.dot(Vfx.scatter(coals, 0.4), Particle.SMALL_FLAME, null, false);
            }
            if (tick % 10 == 5 && tick - slamAt > WAVE_TICKS) {
                Vfx.ring(ctx(), center.clone().add(0, 0.08, 0), radius, (int) Math.min(48, radius * 6), tick * 0.01,
                        Particle.DUST, ORANGE, true);
            }
            if (tick % 20 == 0) {
                Fx.sound(center, Sound.BLOCK_BLASTFURNACE_FIRE_CRACKLE, 0.8f, 0.9f + Fx.jitter(0.1f));
            }
        }

        /** The loop is over (the last shockwave has rolled out). */
        void close() {
            cool();
        }

        /** The forge cools: the red-hot weapons go back to normal and the workshop sinks away in steam. */
        private void cool() {
            if (cooled) {
                return;
            }
            cooled = true;
            for (Map.Entry<UUID, NamespacedKey> entry : buffs.entrySet()) {
                Player smith = buffed.get(entry.getKey());
                if (smith != null) {
                    ctx().removeModifier(smith, entry.getValue());
                }
            }
            buffs.clear();
            buffed.clear();
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    part.setGlowing(false);
                    Displays.shrinkAway(ctx(), owner, part, 8);
                }
            }
            Vfx.cloud(ctx(), center.clone().add(0, 0.8, 0), Particle.CLOUD, 14, 1.0, 0.03, null, false);
            Fx.sound(center, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 0.8f);
            Fx.sound(center, Sound.BLOCK_LAVA_EXTINGUISH, 0.6f, 1.2f);
        }

        /**
         * One hammer part around the grip: the handle runs from the grip along the hammer's length, the head sits
         * across its far end. {@code pitch} 0 lays the hammer flat on the anvil, negative raises it.
         */
        private Transformation hammerPose(boolean isHandle, float pitch) {
            Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-hammerYaw))
                    .rotateX((float) Math.toRadians(pitch));
            Vector3f center = isHandle ? new Vector3f(0, 0, HANDLE_LENGTH / 2) : new Vector3f(0, -0.05f, HANDLE_LENGTH);
            Vector3f size = isHandle ? new Vector3f(0.2f, 0.2f, HANDLE_LENGTH) : new Vector3f(1.1f, 0.7f, 0.75f);
            Vector3f translation = rotation.transform(new Vector3f(center))
                    .sub(rotation.transform(new Vector3f(size).mul(0.5f)));
            return new Transformation(translation, rotation, size, new Quaternionf());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Reserves room for {@code count} more displays under {@link #MAX_DISPLAYS}; false when there is none. */
    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    /** True for a player the smith cannot see (stealth): no visual may give it away. */
    private static boolean isHidden(Player owner, LivingEntity entity) {
        return entity instanceof Player hidden && !owner.canSee(hidden);
    }

    private static double horizontal(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** Minecraft's yaw (degrees: 0 = +Z, 90 = -X) of the horizontal part of {@code direction}, 0 if it has none. */
    private static float yawOf(Vector direction) {
        Vector flat = Motion.horizontal(direction);
        if (flat.lengthSquared() < 1.0E-6) {
            return 0;
        }
        return (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
    }

    /** In front of the right hand, where the blade glows. */
    private static Location hand(Player owner) {
        Location feet = owner.getLocation();
        Vector forward = Motion.horizontalFacing(feet);
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        return feet.add(0, owner.getHeight() * 0.55, 0).add(right.multiply(0.35)).add(forward.multiply(0.45));
    }
}
