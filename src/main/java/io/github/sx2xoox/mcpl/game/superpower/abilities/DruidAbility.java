package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
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
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 식물술사 — the living forest. Standing on grass, moss or leaves slowly heals the owner; the active makes roots
 * burst out of the ground under the aimed enemy and bind everyone around it, and the ultimate grows a small grove
 * around the owner that heals allies inside while its thorns slow and prick enemies.
 *
 * <p>Look (pale leaf, leaf green, bark brown): roots race underground towards the target, then mangrove-root
 * tendrils erupt around its legs and curl in, tipped with azalea leaves, on a patch of moss. The grove spreads a moss
 * carpet, grows three flowering azalea trees and a scatter of flowers, with spores, fireflies and falling leaves
 * drifting through it and a green rim marking its edge.
 */
public final class DruidAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("druid", "식물술사", Material.OAK_SAPLING)
            .color(TextColor.color(0x5BBF4A))
            .difficulty(2)
            .passive("광합성",
                    "풀·이끼·잎·흙 같은 자연 위에 서 있으면 {regen_interval}초마다 체력을 {regen_amount} 회복합니다.")
            .active("덩굴 속박",
                    "조준한 적({snare_range}블록 이내)의 발밑에서 {snare_delay}초 뒤 덩굴이 솟아올라 반경 "
                            + "{snare_radius}블록의 적을 {root_duration}초간 묶습니다. 묶인 적은 움직이거나 점프할 수 없고, "
                            + "{snare_damage} 피해를 입고 {poison_duration}초간 독에 걸립니다.",
                    10)
            .ultimate("생명의 숲",
                    "{ult_duration}초간 내 주변 반경 {ult_radius}블록에 꽃과 나무가 피어나는 숲을 펼칩니다. 숲 안의 나와 "
                            + "아군은 {ult_interval}초마다 체력을 {ult_heal} 회복하고, 적은 이동 속도가 {ult_slow}% 느려지며 "
                            + "가시에 찔려 {ult_interval}초마다 {ult_thorns} 피해를 입습니다.")
            // 3 s: photosynthesis works on any grass (most arenas), so it matches the healer's always-on 1 per 3 s.
            .param("regen_interval", 3)
            .param("regen_amount", 1)
            .param("snare_range", 18)
            .param("snare_delay", 0.25)
            .param("snare_radius", 2)
            .param("root_duration", 1.75)
            .param("snare_damage", 3)
            .param("poison_duration", 3)
            .param("ult_duration", 7)
            .param("ult_radius", 7)
            .param("ult_interval", 1)
            .param("ult_heal", 1.5)
            .param("ult_slow", 40)
            .param("ult_thorns", 1)
            .build();

    private static final String COLOR_TAG = "#5BBF4A";
    /** Pale new leaf, leaf green, bark brown. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE6FFB3, 0x5BBF4A, 0x5D4037);
    private static final Color LEAF = Color.fromRGB(0x4E9F3D);
    private static final Color BLOSSOM = Color.fromRGB(0xF8B7D4);
    private static final Particle.DustOptions MOSS = PALETTE.dust(1.1f);
    private static final Particle.DustOptions BARK = new Particle.DustOptions(PALETTE.edge(), 1.2f);
    private static final Particle.DustOptions THORN = new Particle.DustOptions(Color.fromRGB(0x3E2723), 0.9f);
    private static final Particle.DustTransition SPROUT = PALETTE.glow(1.0f);
    private static final Particle.DustTransition ROOT = PALETTE.fade(1.1f);
    private static final BlockData ROOTS = Material.MANGROVE_ROOTS.createBlockData();
    private static final BlockData ROOTED_DIRT = Material.ROOTED_DIRT.createBlockData();
    private static final BlockData MOSS_BLOCK = Material.MOSS_BLOCK.createBlockData();
    private static final BlockData AZALEA_LEAVES = Material.AZALEA_LEAVES.createBlockData();
    private static final BlockData FLOWERING_LEAVES = Material.FLOWERING_AZALEA_LEAVES.createBlockData();
    private static final BlockData LOG = Material.OAK_LOG.createBlockData();
    private static final Material[] FLOWERS = {Material.POPPY, Material.DANDELION, Material.ALLIUM,
            Material.CORNFLOWER, Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.BLUE_ORCHID};

    /** Display entities this ability keeps alive at most at once (extras fall back to particles). */
    private static final int MAX_DISPLAYS = 24;

    // passive
    private static final long PASSIVE_PERIOD = 5;
    /** How far below the feet the ground still feeds the druid, so a jump never interrupts photosynthesis. */
    private static final int NATURE_REACH = 2;

    // active: vine snare
    /** At most this many enemies are bound by one snare (the aimed one first). */
    private static final int MAX_ROOTED = 4;
    private static final int TENDRILS = 4;
    private static final double TENDRIL_RADIUS = 0.62;
    private static final float TENDRIL_WIDTH = 0.2f;
    private static final float TENDRIL_HEIGHT = 1.45f;
    private static final float TENDRIL_LEAN = -24f;
    private static final float LEAF_SIZE = 0.34f;
    private static final int TENDRIL_GROW_TICKS = 4;
    private static final int TENDRIL_SINK_TICKS = 5;
    private static final long ROOT_FX_PERIOD = 4;

    // ultimate: grove of life
    private static final int TREES = 3;
    private static final double TREE_RING = 0.62;
    private static final float TRUNK_WIDTH = 0.32f;
    private static final float TRUNK_HEIGHT = 2.1f;
    private static final float CANOPY_SIZE = 1.6f;
    private static final float CROWN_SIZE = 1.1f;
    private static final int FLOWER_COUNT = 6;
    private static final float FLOWER_SIZE = 0.7f;
    private static final long GROW_TICKS = 10;
    private static final long SWAY_PERIOD = 10;
    private static final long WITHER_TICKS = 10;
    private static final long SLOW_CHECK_PERIOD = 2;
    /** Each slow modifier lasts this long and is renewed halfway while the enemy stays in the grove. */
    private static final long SLOW_LEASE = 20;

    // passive state
    private long nextRegen = -1;
    /** Display entities spawned by this instance that are still alive. */
    private int liveDisplays;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    // ------------------------------------------------------------------ passive: photosynthesis

    @Override
    public void onTick(long tick) {
        if (tick % PASSIVE_PERIOD != 0) {
            return;
        }
        Player owner = player();
        if (!isOnNature(owner)) {
            nextRegen = -1;
            return;
        }
        long now = ctx().currentTick();
        long interval = Math.max(1, params().ticks("regen_interval"));
        if (nextRegen < 0) {
            nextRegen = now + interval;
        } else if (now >= nextRegen) {
            nextRegen = now + interval;
            if (owner.getHealth() < Combat.maxHealth(owner)) {
                ctx().heal(owner, params().num("regen_amount"));
                photosynthesisFx(owner);
            }
        }
    }

    /**
     * True if the owner stands in something green and growing (grass, flowers, carpets) or above green ground: the
     * first solid block at most {@link #NATURE_REACH} blocks below its feet. Looking down that far keeps the timer
     * running through jumps and crit hops, which a check of the block right under the feet would reset every time.
     */
    private static boolean isOnNature(Player owner) {
        Location feet = owner.getLocation();
        if (isNature(feet.getBlock().getType())) {
            return true;
        }
        World world = feet.getWorld();
        int x = feet.getBlockX();
        int z = feet.getBlockZ();
        int top = Math.min((int) Math.floor(feet.getY() - 0.2), world.getMaxHeight() - 1);
        int bottom = Math.max(world.getMinHeight(), top - NATURE_REACH);
        for (int y = top; y >= bottom; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (isNature(block.getType())) {
                return true;
            }
            if (block.isLiquid() || !block.isPassable()) {
                return false;
            }
        }
        return false;
    }

    private static boolean isNature(Material type) {
        if (tagged(Tag.DIRT, type) || tagged(Tag.LEAVES, type) || tagged(Tag.FLOWERS, type)
                || tagged(Tag.SAPLINGS, type)) {
            return true;
        }
        return switch (type) {
            case GRASS_BLOCK, MOSS_BLOCK, MOSS_CARPET, PALE_MOSS_BLOCK, PALE_MOSS_CARPET, SHORT_GRASS, TALL_GRASS,
                 FERN, LARGE_FERN, PINK_PETALS, WILDFLOWERS, LEAF_LITTER, BUSH, FIREFLY_BUSH, SHORT_DRY_GRASS,
                 TALL_DRY_GRASS, VINE, AZALEA, FLOWERING_AZALEA -> true;
            default -> false;
        };
    }

    private static boolean tagged(@Nullable Tag<Material> tag, Material type) {
        return tag != null && tag.isTagged(type);
    }

    /** Green motes drawn up out of the ground into the owner. */
    private void photosynthesisFx(Player owner) {
        Location chest = Fx.body(owner);
        int motes = Vfx.n(ctx(), 4);
        for (int i = 0; i < motes; i++) {
            Location from = Vfx.scatter(owner.getLocation(), 0.9).add(0, 0.1, 0);
            Vfx.trail(from, chest, i % 2 == 0 ? PALETTE.core() : PALETTE.body(), 10, false);
        }
        owner.getWorld().spawnParticle(Particle.COMPOSTER, chest, Vfx.n(ctx(), 4), 0.3, 0.4, 0.3, 0);
        Fx.sound(chest, Sound.BLOCK_AZALEA_LEAVES_STEP, 0.5f, 1.4f + Fx.jitter(0.15f));
    }

    // ------------------------------------------------------------------ active: vine snare

    @Override
    public ActivationResult onActive() {
        LivingEntity target = ctx().findTarget(player(), params().num("snare_range"));
        if (target == null) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        Snare snare = new Snare(target);
        snare.cast();
        Loop.repeat(ctx(), player(), 1, 1, snare.delay, snare);
        return ActivationResult.success();
    }

    /**
     * The vine snare: roots race underground from the owner to the spot where the aimed enemy stood, the spot cracks
     * open for {@code snare_delay}, then roots burst out and bind every enemy around it.
     */
    private final class Snare implements LongPredicate {

        private final LivingEntity aimed;
        private final Location spot;
        private final Location start;
        private final long delay;
        private final double radius;

        Snare(LivingEntity aimed) {
            this.aimed = aimed;
            // The roots break out of the ground under the enemy, also when it was aimed at mid-jump.
            Location under = flat(aimed.getLocation());
            Double groundY = groundBelow(under.clone().add(0, 0.2, 0), 4);
            if (groundY != null) {
                under.setY(groundY);
            }
            this.spot = under;
            this.start = flat(player().getLocation());
            this.delay = Math.max(1, params().ticks("snare_delay"));
            this.radius = Math.max(0.5, params().num("snare_radius"));
        }

        void cast() {
            Player owner = player();
            Location hand = owner.getEyeLocation().add(owner.getEyeLocation().getDirection().multiply(0.6))
                    .subtract(0, 0.35, 0);
            Location to = spot.clone().add(0, 0.1, 0);
            // A seed of light flies to the target while the roots race there underground (see crack).
            Vfx.trail(hand, Fx.body(aimed), PALETTE.body(), (int) delay + 2, false);
            Vfx.trail(hand, to, PALETTE.core(), (int) delay + 1, false);
            hand.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, hand, Vfx.n(ctx(), 4), 0.15, 0.15, 0.15, 0);
            Fx.sound(hand, Sound.ITEM_BONE_MEAL_USE, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(to, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.0f, 0.7f + Fx.jitter(0.1f));
            Fx.sound(to, Sound.BLOCK_HANGING_ROOTS_PLACE, 0.9f, 0.6f + Fx.jitter(0.1f));
        }

        @Override
        public boolean test(long tick) {
            if (tick + 1 < delay) {
                crack(tick);
                return true;
            }
            erupt();
            return false;
        }

        /**
         * The roots race along the ground from the owner to the spot, which cracks open: a ring closing in and dirt
         * jumping up.
         */
        private void crack(long tick) {
            double steps = Math.max(1, delay - 1);
            Vector path = spot.toVector().subtract(start.toVector());
            Location from = start.clone().add(path.clone().multiply(tick / steps)).add(0, 0.1, 0);
            Location to = start.clone().add(path.clone().multiply((tick + 1) / steps)).add(0, 0.1, 0);
            Vfx.line(ctx(), from, to, 1.6, Particle.DUST, BARK, false);
            Vfx.line(ctx(), from.clone().add(0, 0.05, 0), to, 0.6, Particle.COMPOSTER, null, false);
            to.getWorld().spawnParticle(Particle.BLOCK, to, Vfx.n(ctx(), 3), 0.15, 0.05, 0.15, 0.05, ROOTED_DIRT);
            Location floor = spot.clone().add(0, 0.1, 0);
            double closing = radius * (1 - (double) (tick + 1) / delay) + 0.4;
            Vfx.ring(ctx(), floor, closing, (int) Math.max(10, closing * 7), tick * 0.4, Particle.DUST_COLOR_TRANSITION,
                    ROOT, false);
            Vfx.ring(ctx(), floor, radius, (int) Math.max(12, radius * 6), -tick * 0.2, Particle.DUST, MOSS, false);
            floor.getWorld().spawnParticle(Particle.BLOCK, floor, Vfx.n(ctx(), 4), radius * 0.4, 0.05, radius * 0.4,
                    0.05, ROOTED_DIRT);
        }

        private void erupt() {
            Player owner = player();
            Location center = spot.clone().add(0, 0.9, 0);
            World world = spot.getWorld();
            world.spawnParticle(Particle.BLOCK, spot.clone().add(0, 0.2, 0), Vfx.n(ctx(), 24), radius * 0.4, 0.15,
                    radius * 0.4, 0.15, ROOTED_DIRT);
            world.spawnParticle(Particle.DUST_PILLAR, spot.clone().add(0, 0.1, 0), Vfx.n(ctx(), 10), radius * 0.3, 0,
                    radius * 0.3, 0.1, ROOTED_DIRT);
            world.spawnParticle(Particle.COMPOSTER, center, Vfx.n(ctx(), 10), radius * 0.4, 0.5, radius * 0.4, 0);
            Fx.sound(spot, Sound.BLOCK_MANGROVE_ROOTS_PLACE, 1.2f, 0.7f + Fx.jitter(0.05f));
            Fx.sound(spot, Sound.BLOCK_ROOTS_PLACE, 1.0f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(spot, Sound.BLOCK_BIG_DRIPLEAF_TILT_DOWN, 0.7f, 1.2f + Fx.jitter(0.1f));

            List<LivingEntity> bound = new ArrayList<>(ctx().enemiesNear(owner, center, radius));
            // The aimed enemy is bound first if it is still in reach.
            if (bound.remove(aimed)) {
                bound.addFirst(aimed);
            }
            int count = 0;
            for (LivingEntity enemy : bound) {
                if (count >= MAX_ROOTED) {
                    break;
                }
                if (root(enemy)) {
                    count++;
                }
            }
            if (count > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">덩굴 속박 <white>" + count + "명 묶음!</white></"
                        + COLOR_TAG + ">"));
            }
        }
    }

    /** Binds one enemy: damage, poison, no movement or jumping, and roots curling around its legs. */
    private boolean root(LivingEntity enemy) {
        Player owner = player();
        Combat.hit(ctx(), owner, enemy, params().num("snare_damage"));
        if (!Combat.isTargetable(ctx(), owner, enemy)) {
            return false;
        }
        long ticks = Math.max(1, params().ticks("root_duration"));
        ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                ticks);
        ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                ticks);
        Vector velocity = enemy.getVelocity();
        enemy.setVelocity(new Vector(0, Math.min(0, velocity.getY()), 0));
        long poison = params().ticks("poison_duration");
        if (poison > 0) {
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.POISON, (int) Math.min(Integer.MAX_VALUE, poison),
                    0, false, true, true));
        }
        // The roots grow from the ground, also under an enemy caught mid-jump.
        Location feet = flat(enemy.getLocation());
        Double groundY = groundBelow(feet.clone().add(0, 0.2, 0), 4);
        if (groundY != null) {
            feet.setY(groundY);
        }
        List<BlockDisplay> parts = growTendrils(feet, ticks);
        Location body = Fx.body(enemy);
        Vfx.helix(ctx(), feet.clone().add(0, 0.05, 0), 0.55, enemy.getHeight() * 0.8, 1.5, 2, 10,
                ThreadLocalRandom.current().nextDouble(Math.PI * 2), Particle.DUST_COLOR_TRANSITION, ROOT, false);
        enemy.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, body, Vfx.n(ctx(), 5), 0.3, 0.4, 0.3, 0);
        Fx.sound(body, Sound.BLOCK_AZALEA_LEAVES_PLACE, 0.8f, 0.8f + Fx.jitter(0.1f));
        Loop.repeat(ctx(), owner, ROOT_FX_PERIOD, ROOT_FX_PERIOD, Math.max(1, ticks / ROOT_FX_PERIOD), run -> {
            Location ground = feet.clone().add(0, 0.1, 0);
            Vfx.ring(ctx(), ground, 0.7, 10, run * 0.5, Particle.DUST, MOSS, false);
            Vfx.dot(ground.clone().add(Fx.jitter(0.5f), 0.5 + run * 0.1, Fx.jitter(0.5f)), Particle.SPORE_BLOSSOM_AIR,
                    null, false);
            return true;
        }, () -> release(feet, parts));
        return true;
    }

    /**
     * Mangrove-root tendrils burst out of the ground around {@code feet} and curl in over the legs, each tipped with
     * an azalea leaf cluster, on a patch of moss. Fewer (or none) when the display budget is spent.
     */
    private List<BlockDisplay> growTendrils(Location feet, long ticks) {
        Player owner = player();
        List<BlockDisplay> parts = new ArrayList<>();
        long lifetime = ticks + TENDRIL_SINK_TICKS + 4;
        if (reserveDisplays(1, lifetime)) {
            BlockDisplay moss = Displays.block(ctx(), owner, feet, MOSS_BLOCK, flatPlate(0.3f, 0, 0.03f), lifetime);
            Displays.animateNextTick(ctx(), owner, moss, flatPlate(1.5f, 15, 0.03f), TENDRIL_GROW_TICKS);
            parts.add(moss);
        }
        double phase = ThreadLocalRandom.current().nextDouble(360);
        for (int i = 0; i < TENDRILS; i++) {
            if (!reserveDisplays(i % 2 == 0 ? 2 : 1, lifetime)) {
                break;
            }
            float yaw = (float) (phase + i * 360.0 / TENDRILS);
            BlockDisplay tendril = Displays.block(ctx(), owner, feet, ROOTS, tendrilPose(yaw, 0.05f), lifetime);
            Displays.animateNextTick(ctx(), owner, tendril, tendrilPose(yaw, TENDRIL_HEIGHT), TENDRIL_GROW_TICKS);
            parts.add(tendril);
            if (i % 2 == 0) {
                Vector tip = tendrilTip(yaw, TENDRIL_HEIGHT);
                BlockDisplay leaf = Displays.block(ctx(), owner, feet, i % 4 == 0 ? FLOWERING_LEAVES : AZALEA_LEAVES,
                        Displays.transform((float) tip.getX(), 0.1f, (float) tip.getZ(), 0.05f, 0.05f, 0.05f, yaw, 0,
                                0), lifetime);
                Displays.animateNextTick(ctx(), owner, leaf, Displays.transform((float) tip.getX(), (float) tip.getY(),
                        (float) tip.getZ(), LEAF_SIZE, LEAF_SIZE, LEAF_SIZE, yaw + 30, 20, 10), TENDRIL_GROW_TICKS);
                parts.add(leaf);
            }
        }
        return parts;
    }

    /** The roots let go: they sink back into the ground in a burst of leaves. */
    private void release(Location feet, List<BlockDisplay> parts) {
        for (BlockDisplay part : parts) {
            if (part.isValid()) {
                Displays.shrinkAway(ctx(), player(), part, TENDRIL_SINK_TICKS);
            }
        }
        Location at = feet.clone().add(0, 0.6, 0);
        at.getWorld().spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 10), 0.35, 0.4, 0.35, 0.05, AZALEA_LEAVES);
        at.getWorld().spawnParticle(Particle.TINTED_LEAVES, at, Vfx.n(ctx(), 6), 0.4, 0.4, 0.4, 0, LEAF);
        Fx.sound(at, Sound.BLOCK_MANGROVE_ROOTS_BREAK, 0.8f, 1.0f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.BLOCK_AZALEA_LEAVES_BREAK, 0.6f, 1.1f + Fx.jitter(0.1f));
    }

    /**
     * A root tendril standing at compass angle {@code yaw} around the feet, its foot on the ground and its top leaning
     * in over the legs.
     */
    private static Transformation tendrilPose(float yaw, float height) {
        double lean = Math.toRadians(TENDRIL_LEAN);
        // The box is centered on its offset; move the center so the foot stays at the tendril radius on the ground.
        double centerRadius = TENDRIL_RADIUS + height / 2 * Math.sin(lean);
        double centerY = height / 2 * Math.cos(lean) - 0.1;
        double angle = Math.toRadians(yaw);
        float x = (float) (-Math.sin(angle) * centerRadius);
        float z = (float) (Math.cos(angle) * centerRadius);
        return Displays.transform(x, (float) centerY, z, TENDRIL_WIDTH, height, TENDRIL_WIDTH, yaw, TENDRIL_LEAN,
                0);
    }

    /** Where the top of a full-grown tendril at {@code yaw} ends, relative to the feet. */
    private static Vector tendrilTip(float yaw, float height) {
        double lean = Math.toRadians(TENDRIL_LEAN);
        double radius = TENDRIL_RADIUS + height * Math.sin(lean);
        double angle = Math.toRadians(yaw);
        return new Vector(-Math.sin(angle) * radius, height * Math.cos(lean) - 0.1, Math.cos(angle) * radius);
    }

    // ------------------------------------------------------------------ ultimate: grove of life

    @Override
    public ActivationResult onUltimate() {
        Location feet = player().getLocation();
        Location center = flat(feet);
        Double ground = groundBelow(feet.clone().add(0, 0.2, 0), 3);
        if (ground != null) {
            center.setY(ground);
        }
        Grove grove = new Grove(center);
        grove.bloom();
        Loop.repeat(ctx(), player(), 0, 1, grove.life, grove, grove::wither);
        return ActivationResult.success();
    }

    /**
     * An enemy slowed by the grove: the modifier doing it is a short lease, renewed while the enemy stays inside, so
     * the slow fades within a second once the grove is gone, even if its owner died.
     */
    private static final class Slowed {
        final LivingEntity entity;
        NamespacedKey key;
        long renewAt;

        Slowed(LivingEntity entity, NamespacedKey key, long renewAt) {
            this.entity = entity;
            this.key = key;
            this.renewAt = renewAt;
        }
    }

    /** One grove tree: a trunk, a flowering canopy and a green crown, turned {@code yaw} degrees. */
    private record Tree(BlockDisplay trunk, BlockDisplay canopy, BlockDisplay crown, float yaw) {
    }

    /**
     * The grove of life: a moss carpet with three flowering trees and scattered flowers. Every {@code ult_interval} it
     * heals the owner and allies inside and pricks enemies inside with thorns; enemies inside are slowed for as long
     * as they stay.
     */
    private final class Grove implements LongPredicate {

        private final Location center;
        private final World world;
        private final double radius;
        private final long life;
        private final long interval;
        private final List<BlockDisplay> carpet = new ArrayList<>();
        private final List<Tree> trees = new ArrayList<>();
        private final List<ItemDisplay> flowers = new ArrayList<>();
        private final List<Location> canopies = new ArrayList<>();
        private final Map<UUID, Slowed> slowed = new LinkedHashMap<>();
        private int healed;

        Grove(Location center) {
            this.center = center;
            this.world = center.getWorld();
            this.radius = Math.max(1, params().num("ult_radius"));
            this.life = Math.max(1, params().ticks("ult_duration"));
            this.interval = Math.max(1, params().ticks("ult_interval"));
        }

        void bloom() {
            Player owner = player();
            long lifetime = life + WITHER_TICKS + 4;
            float size = (float) (radius * 1.8);
            for (int i = 0; i < 2; i++) {
                if (!reserveDisplays(1, lifetime)) {
                    break;
                }
                float yaw = i * 45f;
                float height = 0.025f + i * 0.006f;
                BlockDisplay moss = Displays.block(ctx(), owner, center, MOSS_BLOCK, flatPlate(0.4f, yaw, height),
                        lifetime);
                Displays.animateNextTick(ctx(), owner, moss, flatPlate(size, yaw, height), (int) GROW_TICKS);
                carpet.add(moss);
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double phase = random.nextDouble(Math.PI * 2);
            for (int i = 0; i < TREES; i++) {
                double angle = phase + i * Math.PI * 2 / TREES;
                Location base = center.clone().add(Math.cos(angle) * radius * TREE_RING, 0,
                        Math.sin(angle) * radius * TREE_RING);
                canopies.add(base.clone().add(0, TRUNK_HEIGHT + 0.4, 0));
                plantTree(base, 2 + i * 3L, lifetime);
            }
            for (int i = 0; i < FLOWER_COUNT; i++) {
                if (!reserveDisplays(1, lifetime)) {
                    break;
                }
                Location at = Vfx.scatter(center, radius * 0.85);
                ItemDisplay flower = Displays.item(ctx(), owner, at, ItemStack.of(FLOWERS[i % FLOWERS.length]),
                        Displays.centered(0, -0.3f, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), lifetime);
                flower.setBillboard(Display.Billboard.VERTICAL);
                long when = 3 + random.nextLong(8);
                ctx().runLater(owner, when, () -> Displays.animate(flower,
                        Displays.centered(0, FLOWER_SIZE * 0.5f, 0, FLOWER_SIZE, FLOWER_SIZE, FLOWER_SIZE, 0, 0, 0),
                        6));
                flowers.add(flower);
            }

            Location floor = center.clone().add(0, 0.15, 0);
            Vfx.shockwave(ctx(), owner, floor, 0.5, radius, GROW_TICKS, Particle.DUST_COLOR_TRANSITION, SPROUT, true);
            world.spawnParticle(Particle.COMPOSTER, floor, Vfx.n(ctx(), 30), radius * 0.4, 0.3, radius * 0.4, 0, null,
                    true);
            world.spawnParticle(Particle.HAPPY_VILLAGER, center.clone().add(0, 1, 0), Vfx.n(ctx(), 12), 0.6, 0.6, 0.6,
                    0, null, true);
            Vfx.helix(ctx(), center, 0.8, 3.0, 1.5, 2, 14, 0, Particle.DUST_COLOR_TRANSITION, SPROUT, true);
            Fx.sound(center, Sound.ITEM_BONE_MEAL_USE, 1.4f, 0.7f);
            Fx.sound(center, Sound.BLOCK_FLOWERING_AZALEA_PLACE, 1.2f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.BLOCK_SPORE_BLOSSOM_PLACE, 1.0f, 0.8f + Fx.jitter(0.05f));
            Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.3f);
        }

        /** A small azalea tree that sprouts after {@code delay} ticks and grows to full size. */
        private void plantTree(Location base, long delay, long lifetime) {
            if (!reserveDisplays(3, lifetime)) {
                return;
            }
            Player owner = player();
            float yaw = ThreadLocalRandom.current().nextFloat() * 90f;
            BlockDisplay trunk = Displays.block(ctx(), owner, base, LOG, trunkPose(0.05f), lifetime);
            BlockDisplay canopy = Displays.block(ctx(), owner, base, FLOWERING_LEAVES, canopyPose(0.05f, yaw, 0),
                    lifetime);
            BlockDisplay crown = Displays.block(ctx(), owner, base, AZALEA_LEAVES, crownPose(0.05f, yaw, 0), lifetime);
            trees.add(new Tree(trunk, canopy, crown, yaw));
            ctx().runLater(owner, delay, () -> {
                Displays.animate(trunk, trunkPose(1f), (int) GROW_TICKS);
                Displays.animate(canopy, canopyPose(1f, yaw, 0), (int) GROW_TICKS);
                Displays.animate(crown, crownPose(1f, yaw, 0), (int) GROW_TICKS);
                base.getWorld().spawnParticle(Particle.COMPOSTER, base.clone().add(0, 0.3, 0), Vfx.n(ctx(), 8), 0.3,
                        0.2, 0.3, 0, null, true);
                Fx.sound(base, Sound.BLOCK_AZALEA_LEAVES_PLACE, 1.0f, 0.7f + Fx.jitter(0.1f));
                Fx.sound(base, Sound.BLOCK_GROWING_PLANT_CROP, 1.0f, 0.8f + Fx.jitter(0.1f));
            });
        }

        @Override
        public boolean test(long tick) {
            if (tick % SLOW_CHECK_PERIOD == 0) {
                updateSlows(tick);
            }
            if (tick % interval == interval - 1) {
                pulse();
            }
            if (tick > GROW_TICKS && tick % SWAY_PERIOD == 0) {
                sway(tick);
            }
            ambience(tick);
            return true;
        }

        private boolean inside(LivingEntity entity) {
            Location at = entity.getLocation();
            double dx = at.getX() - center.getX();
            double dz = at.getZ() - center.getZ();
            double dy = at.getY() - center.getY();
            return dx * dx + dz * dz <= radius * radius && dy > -2 && dy < 4;
        }

        /**
         * Enemies inside are slowed (one leased modifier each, renewed while they stay); leavers are let go at once.
         */
        private void updateSlows(long tick) {
            Player owner = player();
            double slow = Math.clamp(params().num("ult_slow") / 100.0, 0.0, 1.0);
            Iterator<Slowed> iterator = slowed.values().iterator();
            while (iterator.hasNext()) {
                Slowed entry = iterator.next();
                if (!Combat.isTargetable(ctx(), owner, entry.entity) || !inside(entry.entity)) {
                    ctx().removeModifier(entry.entity, entry.key);
                    iterator.remove();
                } else if (tick >= entry.renewAt) {
                    ctx().removeModifier(entry.entity, entry.key);
                    entry.key = ctx().addModifier(owner, entry.entity, Attribute.MOVEMENT_SPEED, -slow,
                            AttributeModifier.Operation.MULTIPLY_SCALAR_1, SLOW_LEASE);
                    entry.renewAt = tick + SLOW_LEASE / 2;
                }
            }
            if (slow <= 0) {
                return;
            }
            for (LivingEntity enemy : ctx().enemiesNear(owner, center.clone().add(0, 1, 0), radius + 1)) {
                if (slowed.containsKey(enemy.getUniqueId()) || !inside(enemy)) {
                    continue;
                }
                NamespacedKey key = ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, SLOW_LEASE);
                slowed.put(enemy.getUniqueId(), new Slowed(enemy, key, tick + SLOW_LEASE / 2));
                Location feet = enemy.getLocation().add(0, 0.1, 0);
                Vfx.ring(ctx(), feet, 0.6, 10, 0, Particle.DUST, THORN, false);
                world.spawnParticle(Particle.BLOCK, feet, Vfx.n(ctx(), 6), 0.3, 0.1, 0.3, 0.05, AZALEA_LEAVES);
                Fx.sound(feet, Sound.BLOCK_SWEET_BERRY_BUSH_PLACE, 0.8f, 0.8f + Fx.jitter(0.1f));
            }
        }

        /** The grove breathes: heals friends inside, thorns prick enemies inside. */
        private void pulse() {
            Player owner = player();
            double heal = params().num("ult_heal");
            List<LivingEntity> friends = new ArrayList<>(ctx().alliesNear(owner, center.clone().add(0, 1, 0),
                    radius + 1));
            friends.addFirst(owner);
            for (LivingEntity friend : friends) {
                if (!inside(friend)) {
                    continue;
                }
                if (friend.getHealth() < Combat.maxHealth(friend)) {
                    healed++;
                }
                ctx().heal(friend, heal);
                Location chest = Fx.body(friend);
                Location canopy = nearestCanopy(chest);
                if (canopy != null) {
                    Vfx.trail(canopy, chest, PALETTE.core(), 10, true);
                }
                world.spawnParticle(Particle.HAPPY_VILLAGER, chest, Vfx.n(ctx(), 4), 0.3, 0.45, 0.3, 0, null, true);
                world.spawnParticle(Particle.HEART, chest.clone().add(0, 0.8, 0), 1, 0.2, 0.1, 0.2, 0, null, true);
            }
            double thorns = params().num("ult_thorns");
            for (LivingEntity enemy : ctx().enemiesNear(owner, center.clone().add(0, 1, 0), radius + 1)) {
                if (!inside(enemy)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, thorns);
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Location body = Fx.body(enemy);
                    world.spawnParticle(Particle.CRIT, body, Vfx.n(ctx(), 6), 0.3, 0.4, 0.3, 0.1);
                    world.spawnParticle(Particle.DUST, body, Vfx.n(ctx(), 5), 0.3, 0.45, 0.3, 0, THORN);
                    Fx.sound(body, Sound.ENCHANT_THORNS_HIT, 0.7f, 1.0f + Fx.jitter(0.15f));
                }
            }
            Fx.sound(center, Sound.BLOCK_AZALEA_LEAVES_STEP, 0.8f, 0.7f + Fx.jitter(0.1f));
        }

        private @Nullable Location nearestCanopy(Location to) {
            Location best = null;
            double bestDistance = Double.MAX_VALUE;
            for (Location canopy : canopies) {
                double distance = canopy.distanceSquared(to);
                if (distance < bestDistance) {
                    best = canopy;
                    bestDistance = distance;
                }
            }
            return best;
        }

        /** The canopies sway gently in the wind. */
        private void sway(long tick) {
            for (int i = 0; i < trees.size(); i++) {
                Tree tree = trees.get(i);
                float angle = 4f * (float) Math.sin(tick * 0.2 + i * 1.7);
                if (tree.canopy().isValid()) {
                    Displays.animate(tree.canopy(), canopyPose(1f, tree.yaw(), angle), (int) SWAY_PERIOD);
                }
                if (tree.crown().isValid()) {
                    Displays.animate(tree.crown(), crownPose(1f, tree.yaw(), -angle), (int) SWAY_PERIOD);
                }
            }
        }

        /** Spores, fireflies and falling leaves drift through the grove; its rim glows green. */
        private void ambience(long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location mote = Vfx.scatter(center, radius).add(0, 0.4 + random.nextDouble(2.0), 0);
            Vfx.dot(mote, tick % 2 == 0 ? Particle.SPORE_BLOSSOM_AIR : Particle.FIREFLY, null, true);
            if (!canopies.isEmpty() && tick > GROW_TICKS && tick % 2 == 0) {
                Location canopy = canopies.get(random.nextInt(canopies.size()));
                Location leaf = canopy.clone().add(Fx.jitter(0.8f), Fx.jitter(0.4f), Fx.jitter(0.8f));
                if (random.nextInt(3) == 0) {
                    Vfx.dot(leaf, Particle.CHERRY_LEAVES, null, true);
                } else {
                    world.spawnParticle(Particle.TINTED_LEAVES, leaf, 1, 0, 0, 0, 0, LEAF, true);
                }
            }
            if (tick % 4 == 0) {
                Location rim = center.clone().add(0, 0.15, 0);
                Vfx.ring(ctx(), rim, radius, (int) Math.min(48, Math.max(16, radius * 4)), tick * 0.02,
                        Particle.DUST_COLOR_TRANSITION, SPROUT, true);
                Vfx.ring(ctx(), rim, radius - 0.3, (int) Math.max(8, radius * 2), -tick * 0.03, Particle.DUST, MOSS,
                        true);
            }
            if (tick % 6 == 0) {
                Location blossom = Vfx.scatter(center, radius * 0.8).add(0, 0.2, 0);
                world.spawnParticle(Particle.DUST, blossom, 1, 0.1, 0.05, 0.1, 0,
                        new Particle.DustOptions(BLOSSOM, 1.0f), true);
            }
        }

        /** The grove withers: everything sinks back into the ground in a flurry of leaves; slows end. */
        void wither() {
            Player owner = player();
            for (Slowed entry : slowed.values()) {
                ctx().removeModifier(entry.entity, entry.key);
            }
            slowed.clear();
            List<BlockDisplay> blocks = new ArrayList<>(carpet);
            for (Tree tree : trees) {
                blocks.add(tree.trunk());
                blocks.add(tree.canopy());
                blocks.add(tree.crown());
            }
            for (BlockDisplay block : blocks) {
                if (block.isValid()) {
                    Displays.shrinkAway(ctx(), owner, block, (int) WITHER_TICKS);
                }
            }
            for (ItemDisplay flower : flowers) {
                if (flower.isValid()) {
                    Displays.shrinkAway(ctx(), owner, flower, (int) WITHER_TICKS / 2);
                }
            }
            for (Location canopy : canopies) {
                world.spawnParticle(Particle.TINTED_LEAVES, canopy, Vfx.n(ctx(), 10), 0.7, 0.5, 0.7, 0, LEAF, true);
                world.spawnParticle(Particle.CHERRY_LEAVES, canopy, Vfx.n(ctx(), 5), 0.7, 0.5, 0.7, 0, null, true);
            }
            world.spawnParticle(Particle.COMPOSTER, center.clone().add(0, 0.3, 0), Vfx.n(ctx(), 16), radius * 0.4, 0.2,
                    radius * 0.4, 0, null, true);
            Fx.sound(center, Sound.BLOCK_AZALEA_LEAVES_BREAK, 1.2f, 0.7f);
            Fx.sound(center, Sound.BLOCK_GRASS_BREAK, 1.0f, 0.6f);
            if (healed > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">생명의 숲 <white>" + healed + "번 치유</white></"
                        + COLOR_TAG + ">"));
            }
        }
    }

    /** The trunk of a grove tree: a log standing on the ground, {@code grow} 0..1. */
    private static Transformation trunkPose(float grow) {
        float height = Math.max(0.05f, TRUNK_HEIGHT * grow);
        float width = Math.max(0.05f, TRUNK_WIDTH * grow);
        return Displays.transform(0, height / 2, 0, width, height, width, 0, 0, 0);
    }

    /** The flowering canopy on top of the trunk, tilted {@code sway} degrees. */
    private static Transformation canopyPose(float grow, float yaw, float sway) {
        float size = Math.max(0.05f, CANOPY_SIZE * grow);
        return Displays.transform(0, TRUNK_HEIGHT * grow + size * 0.25f, 0, size, size * 0.85f, size, yaw, sway,
                sway * 0.5f);
    }

    /** The small green crown on top of the canopy. */
    private static Transformation crownPose(float grow, float yaw, float sway) {
        float size = Math.max(0.05f, CROWN_SIZE * grow);
        return Displays.transform(0, TRUNK_HEIGHT * grow + CANOPY_SIZE * 0.6f * grow + size * 0.3f, 0, size,
                size * 0.8f, size, yaw + 45f, sway, -sway * 0.5f);
    }

    // ------------------------------------------------------------------ helpers

    /** The top of the first solid block at most {@code depth} blocks below {@code point}, null if none. */
    private static @Nullable Double groundBelow(Location point, int depth) {
        World world = point.getWorld();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int start = Math.min(point.getBlockY(), world.getMaxHeight() - 1);
        int end = Math.max(world.getMinHeight(), start - depth);
        for (int y = start; y >= end; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable() && block.getRelative(BlockFace.UP).isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return null;
    }

    /**
     * Claims {@code count} display entities for {@code lifetime} ticks if that stays within {@link #MAX_DISPLAYS}.
     */
    private boolean reserveDisplays(int count, long lifetime) {
        if (count <= 0 || liveDisplays + count > MAX_DISPLAYS) {
            return false;
        }
        liveDisplays += count;
        ctx().runLater(player(), Math.max(1, lifetime), () -> liveDisplays -= count);
        return true;
    }

    /** A flat square plate of edge {@code size}, turned {@code yaw} degrees, lying {@code height} above the origin. */
    private static Transformation flatPlate(float size, float yaw, float height) {
        return Displays.transform(0, height, 0, size, 0.02f, size, yaw, 0, 0);
    }

    /** {@code at} without its rotation, so display entities placed there keep their own orientation. */
    private static Location flat(Location at) {
        Location copy = at.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }
}
