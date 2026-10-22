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
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Stealth;
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
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 문어 — a slippery ink-slinger from the deep. Struck, it may squirt a cloud of ink and vanish from its enemies' eyes
 * for a moment; it shoots blinding ink blobs that leave slick puddles behind, and as the ultimate it floods the ground
 * with an ink sea from which eight tentacles burst to seize every enemy, hoist it into the air and slam it down.
 *
 * <p>Look: deep purple, ink black and coral. The ink cloud is a dark, swelling blot of smoky glass with ink spraying at
 * the attacker's face; while hidden, only a few drops of ink fall where the octopus walks. The ink blob is a spinning
 * ink sac trailing black drops, it splats into a glossy black puddle with a purple rim that bubbles and makes enemies
 * skid. For the kraken an ink sea spreads over the floor (rings around everyone about to be seized), then purple, coral
 * tipped tentacles — each three tapering segments on a curve — erupt in a splash of earth and ink, coil round their
 * prey, sway, crash down and sink back into the ground.
 */
public final class OctopusAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("octopus", "문어", Material.INK_SAC)
            .color(TextColor.color(0xB07CF0))
            .difficulty(2)
            .passive("먹물",
                    "적에게 공격받으면 {ink_chance}% 확률로 먹물을 뿜어 {ink_stealth}초간 적의 눈에서 사라지고 이동 속도가 "
                            + "{ink_speed}% 빨라집니다. 공격이 적중하면 모습이 드러나며, 한 번 뿜은 뒤 {ink_cooldown}초 동안은 "
                            + "다시 뿜지 않습니다.")
            .active("먹물 발사",
                    "바라보는 방향으로 먹물 덩어리를 쏩니다({shot_range}블록). 처음 맞은 적이나 벽·땅에서 터져 반경 "
                            + "{splash_radius}블록의 적에게 {shot_damage} 피해를 주고 {shot_blind}초간 실명시키며, "
                            + "{puddle_duration}초간 반경 {puddle_radius}블록의 미끄러운 먹물 웅덩이를 남깁니다. 웅덩이를 딛고 "
                            + "선 적은 이동 속도가 {puddle_slow}% 느려지고, 걸으면 발이 옆으로 미끄러져 휘청입니다.",
                    9)
            .ultimate("크라켄",
                    "발밑에 반경 {kraken_radius}블록의 먹물 바다를 펼치고, {kraken_delay}초 뒤 촉수 {tentacles}개가 "
                            + "솟아오릅니다. 촉수는 바다 안의 적을 하나씩 붙잡아 {grab_damage} 피해를 주고 {kraken_hold}초간 "
                            + "공중에 들어 올렸다가 땅에 내리쳐 {slam_damage} 피해를 주고 {slam_slow}초간 구속합니다. 빈 촉수는 "
                            + "주변을 내리쳐 맞은 적에게 {lash_damage} 피해를 줍니다.")
            .param("ink_chance", 30)
            .param("ink_stealth", 1.5)
            .param("ink_speed", 30)
            .param("ink_cooldown", 10)
            .param("shot_range", 20)
            .param("splash_radius", 1.5)
            .param("shot_damage", 4)
            .param("shot_blind", 2)
            .param("puddle_duration", 4)
            .param("puddle_radius", 3)
            .param("puddle_slow", 30)
            .param("kraken_radius", 7)
            .param("kraken_delay", 0.75)
            .param("tentacles", 8)
            .param("grab_damage", 2)
            .param("kraken_hold", 1.25)
            .param("slam_damage", 5)
            .param("slam_slow", 1.5)
            .param("lash_damage", 3)
            .build();

    private static final String COLOR_TAG = "#B07CF0";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** Coral glow, deep purple, ink black. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFF8F7A, 0x7B3FBF, 0x140A1F);
    private static final Particle.DustOptions INK = new Particle.DustOptions(Color.fromRGB(0x140A1F), 1.4f);
    private static final Particle.DustOptions INK_SMALL = new Particle.DustOptions(Color.fromRGB(0x140A1F), 0.8f);
    private static final Particle.DustTransition SHEEN = new Particle.DustTransition(Color.fromRGB(0x9A5BE0),
            Color.fromRGB(0x140A1F), 0.9f);
    private static final Particle.DustOptions SUCKER = new Particle.DustOptions(Color.fromRGB(0xFFB3A6), 0.7f);
    private static final Particle.DustTransition RIM = PALETTE.fade(1.2f);

    /** Materials of the visuals; block data and items are created when used (they need a running server). */
    private static final Material CLOUD_GLASS = Material.BLACK_STAINED_GLASS;
    private static final Material PUDDLE_BLOCK = Material.BLACK_CONCRETE;
    private static final Material BLOB_ITEM = Material.INK_SAC;
    /** The three segments of a tentacle, root to tip: deep purple, purple coral, pink coral suckers. */
    private static final Material[] TENTACLE_BLOCKS = {Material.PURPLE_CONCRETE, Material.BUBBLE_CORAL_BLOCK,
            Material.BRAIN_CORAL_BLOCK};
    private static final float[] TENTACLE_WIDTHS = {0.55f, 0.42f, 0.28f};
    /** Where the segments' joints sit on the tentacle's curve (0 = root, 1 = tip). */
    private static final double[] JOINTS = {0, 0.4, 0.72, 1};
    /** A slimmer tentacle of two segments (root and sucker tip), used when the display budget is short. */
    private static final Material[] SHORT_TENTACLE_BLOCKS = {Material.PURPLE_CONCRETE, Material.BRAIN_CORAL_BLOCK};
    private static final float[] SHORT_TENTACLE_WIDTHS = {0.55f, 0.34f};
    private static final double[] SHORT_JOINTS = {0, 0.55, 1};

    private static final double MAX_SPEED = 2.0;
    /** At most this many of this octopus's displays are alive at once (the kraken's tentacles come first). */
    private static final int DISPLAY_BUDGET = 24;
    private static final long MAX_LAG_TICKS = 8;
    private static final double MILLIS_PER_TICK = 50.0;

    // ------------------------------------------------------------------ passive: ink cloud
    private static final double AGGRO_RANGE = 24;
    private static final long CLOUD_TICKS = 26;
    private static final int CLOUD_PUFFS = 3;
    private static final long DRIP_PERIOD = 6;
    private static final long READY_DRIP_PERIOD = 40;

    // ------------------------------------------------------------------ active: ink shot
    private static final double SHOT_SPEED = 1.3;
    private static final double SHOT_GRAVITY = 0.025;
    /** Enemy hitboxes are inflated by this much for the blob (forgiving aim). */
    private static final double SHOT_RADIUS = 0.35;
    private static final int SHOT_EXTRA_TICKS = 8;
    private static final double PUDDLE_DROP = 5;
    /** An enemy counts as standing in the puddle up to this high above it (small hops still skid). */
    private static final double PUDDLE_HEIGHT = 0.7;
    private static final long PUDDLE_SLOW_REFRESH = 10;
    private static final long PUDDLE_EFFECT_PERIOD = 2;
    /** Moving more than this in one effect period counts as moving (and makes the enemy skid). */
    private static final double SLIP_MIN_STEP = 0.1;
    /** A skid throws the feet sideways, off the line the enemy walks, and robs a little of its stride. */
    private static final double SLIP_SIDE = 0.35;
    private static final double SLIP_BACK = 0.15;
    private static final long SLIP_COOLDOWN = 12;
    private static final int PUDDLE_GROW_TICKS = 5;
    private static final int PUDDLE_FADE_TICKS = 10;

    // ------------------------------------------------------------------ ultimate: kraken
    private static final double KRAKEN_GROUND_DROP = 6;
    private static final int RISE_TICKS = 5;
    private static final int LIFT_TICKS = 6;
    private static final int SLAM_TICKS = 3;
    private static final int RETREAT_DELAY = 6;
    private static final int RETREAT_TICKS = 8;
    private static final int POSE_PERIOD = 2;
    private static final int MAX_TENTACLES = 8;
    private static final double KRAKEN_LIFT = 2.5;
    private static final double LIFT_STEP = 0.5;
    /** A seizing tentacle bursts out this far from its prey, on the side of the kraken's centre. */
    private static final double GRAB_BASE_OFFSET = 1.1;
    /** Free tentacles stand on a ring of this share of the sea's radius. */
    private static final double FREE_RING = 0.6;
    private static final double IDLE_HEIGHT = 3.0;
    private static final double LASH_LENGTH = 2.7;
    private static final double LASH_RADIUS = 1.6;
    private static final double LASH_PUSH = 0.6;
    private static final double LASH_LIFT = 0.3;
    /** A held enemy dragged further than this from where the tentacle holds it slips free. */
    private static final double HOLD_LEASH = 2.5;
    private static final double ROOT_DEPTH = 0.45;
    /** Size of the probe that makes sure a ground ray does not start inside a block. */
    private static final double PROBE_SIZE = 0.1;
    private static final float ANCHOR_LIFT = 0.05f;

    private final Stealth stealth = new Stealth();
    private final List<Display> live = new ArrayList<>();
    private long nextInk = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
    private @Nullable Puddle puddle;
    private @Nullable Kraken kraken;
    private @Nullable Location lastDrip;

    private enum Reveal { ATTACK, EXPIRED, REVOKE }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        reveal(Reveal.REVOKE);
        puddle = null;
        kraken = null;
        live.clear();
    }

    /** How many more displays fit in this octopus's display budget. */
    private int room() {
        live.removeIf(display -> !display.isValid());
        return Math.max(0, DISPLAY_BUDGET - live.size());
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    // ------------------------------------------------------------------ passive: ink cloud

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0 || !(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Player owner = player();
        Entity damager = byEntity.getDamager();
        Player attacker = ctx().resolveAttacker(damager);
        if (attacker == null || !ctx().isEnemy(owner, attacker)) {
            return;
        }
        long now = ctx().currentTick();
        if (now < nextInk || stealth.isActive() || event.getFinalDamage() >= owner.getHealth()) {
            return;
        }
        double chance = Math.clamp(params().num("ink_chance") / 100.0, 0.0, 1.0);
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        nextInk = now + params().ticks("ink_cooldown");
        squirt(owner, damager);
    }

    /** Squirts the ink: hidden from enemies (allies see the vanilla invisibility) and a burst of speed to escape. */
    private void squirt(Player owner, Entity attacker) {
        long ticks = Math.max(1, params().ticks("ink_stealth"));
        int effectTicks = (int) Math.min(Integer.MAX_VALUE - 5, ticks);
        owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, effectTicks + 5, 0, false, false,
                true));
        double speed = Math.max(0, params().num("ink_speed")) / 100.0;
        if (speed > 0) {
            ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, speed, Operation.MULTIPLY_SCALAR_1, ticks);
        }
        stealth.begin(ctx(), owner, ticks, () -> reveal(Reveal.EXPIRED));
        Stealth.dropAggro(ctx(), owner, AGGRO_RANGE);
        lastDrip = owner.getLocation();
        inkCloud(owner, attacker);
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>먹물!</b></" + COLOR_TAG + "> <gray>"
                + Fx.seconds(ticks) + "초간 모습을 감춥니다</gray>"));
    }

    /**
     * The ink cloud left behind: a jet sprayed into the attacker's face, a black blot of smoky glass swelling and
     * thinning out where the octopus stood, and ink rain falling out of it.
     */
    private void inkCloud(Player owner, Entity attacker) {
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
        World world = chest.getWorld();
        if (attacker.getWorld().equals(world)) {
            Location face = attacker instanceof LivingEntity living ? living.getEyeLocation()
                    : attacker.getLocation().add(0, 1, 0);
            if (face.distanceSquared(chest) < 64) {
                Vfx.line(ctx(), chest, face, 4, Particle.DUST, INK_SMALL, false);
                Vfx.cloud(ctx(), face, Particle.SQUID_INK, 8, 0.15, 0.04, null, false);
            }
        }
        world.spawnParticle(Particle.SQUID_INK, chest, Vfx.n(ctx(), 30), 0.45, 0.5, 0.45, 0.06);
        world.spawnParticle(Particle.DUST, chest, Vfx.n(ctx(), 24), 0.7, 0.6, 0.7, 0, INK);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, chest, Vfx.n(ctx(), 14), 0.5, 0.5, 0.5, 0, SHEEN);
        Vfx.dot(chest, Particle.FLASH, Color.fromRGB(0x2A1240), false);
        Fx.sound(chest, Sound.ENTITY_SQUID_SQUIRT, 1.2f, 0.8f + Fx.jitter(0.08f));
        Fx.sound(chest, Sound.ENTITY_GLOW_SQUID_SQUIRT, 0.7f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(chest, Sound.ENTITY_PLAYER_SPLASH, 0.5f, 1.4f + Fx.jitter(0.1f));

        Location center = chest.clone();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int puffs = Math.min(CLOUD_PUFFS, room());
        for (int i = 0; i < puffs; i++) {
            Quaternionf turn = new Quaternionf().rotateXYZ(random.nextFloat() * 6.28f, random.nextFloat() * 6.28f,
                    random.nextFloat() * 6.28f);
            Location at = center.clone().add(random.nextDouble(-0.3, 0.3), random.nextDouble(-0.2, 0.3),
                    random.nextDouble(-0.3, 0.3));
            BlockDisplay puff = keep(Displays.block(ctx(), owner, at, CLOUD_GLASS.createBlockData(),
                    cubePose(0.3f, turn), CLOUD_TICKS + 4));
            float size = 1.3f + 0.25f * i;
            Displays.animateNextTick(ctx(), owner, puff, cubePose(size, new Quaternionf(turn).rotateY(0.6f)), 6);
            ctx().runLater(owner, 8 + 2L * i, () -> {
                if (puff.isValid()) {
                    Displays.shrinkAway(ctx(), owner, puff, (int) CLOUD_TICKS - 10);
                }
            });
        }
        Loop.repeat(ctx(), owner, 2, 2, CLOUD_TICKS / 2, run -> {
            double fade = 1 - (double) run / (CLOUD_TICKS / 2.0);
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Location drop = Vfx.scatter(center, 0.9 * fade + 0.2).add(0, random.nextDouble(-0.4, 0.5), 0);
                Vfx.dot(drop, Particle.DUST, INK, false);
            }
            if (run % 2 == 0) {
                Vfx.cloud(ctx(), center, Particle.SQUID_INK, 2, 0.4, 0.01, null, false);
            }
            return true;
        });
    }

    /** A hit landed from the ink shows the octopus again (a dodged or blocked one does not). */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (stealth.isActive()) {
            reveal(Reveal.ATTACK);
        }
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        if (stealth.isActive()) {
            if (tick % DRIP_PERIOD == 0) {
                inkDrops(owner);
            }
            if (tick % 5 == 0) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">≈ 먹물 은신</" + COLOR_TAG + "> <gray>"
                        + Fx.seconds(stealth.ticksLeft(ctx().currentTick())) + "초</gray>"));
            }
            return;
        }
        // A full ink sac drips a little now and then: the tell that the next hit may vanish into ink.
        if (tick % READY_DRIP_PERIOD == 0 && ctx().currentTick() >= nextInk) {
            Location hip = owner.getLocation().add(0, owner.getHeight() * 0.35, 0);
            Vfx.dot(Vfx.scatter(hip, 0.3), Particle.DRIPPING_OBSIDIAN_TEAR, null, false);
        }
    }

    /** The only tell while hidden: drops of ink falling where the octopus walks. */
    private void inkDrops(Player owner) {
        Location feet = owner.getLocation();
        Location last = lastDrip;
        if (last != null && last.getWorld().equals(feet.getWorld()) && last.distanceSquared(feet) < 0.36) {
            return;
        }
        lastDrip = feet.clone();
        Vfx.dot(feet.clone().add(0, 0.05, 0), Particle.DUST, INK_SMALL, false);
        Vfx.dot(feet.clone().add(0, 0.4, 0), Particle.FALLING_OBSIDIAN_TEAR, null, false);
    }

    /** Ends the stealth (if any), showing the octopus to everyone it was hidden from. */
    private void reveal(Reveal reason) {
        Player owner = player();
        if (!stealth.end(ctx(), owner) || !owner.isOnline()) {
            return;
        }
        lastDrip = null;
        PotionEffect effect = owner.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (effect != null && effect.getAmplifier() == 0 && !effect.hasParticles()) {
            owner.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
        if (reason == Reveal.REVOKE) {
            return;
        }
        Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
        Vfx.cloud(ctx(), body, Particle.SQUID_INK, 10, 0.3, 0.04, null, false);
        body.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 10), 0.3, 0.5, 0.3, 0,
                SHEEN);
        Fx.sound(body, Sound.ENTITY_SQUID_AMBIENT, 0.7f, 1.3f + Fx.jitter(0.1f));
        if (reason == Reveal.EXPIRED) {
            owner.sendActionBar(Text.mm("<gray>먹물이 걷혔습니다</gray>"));
        }
    }

    // ------------------------------------------------------------------ active: ink shot

    @Override
    public ActivationResult onActive() {
        new InkShot(player()).launch();
        return ActivationResult.success();
    }

    /** The ink blob: flies along the aim with a slight drop, bursts on the first enemy, block or at its range. */
    private final class InkShot implements LongPredicate {

        private final Player owner;
        private final World world;
        private final Location position;
        private final Vector velocity;
        private final double range;
        private @Nullable ItemDisplay blob;
        private double traveled;
        private boolean burst;
        private float spin;

        InkShot(Player owner) {
            this.owner = owner;
            Location eye = owner.getEyeLocation();
            this.world = eye.getWorld();
            this.position = eye.clone();
            Vector direction = eye.getDirection();
            this.velocity = direction.lengthSquared() < 1.0E-6 ? new Vector(0, 0, SHOT_SPEED)
                    : direction.normalize().multiply(SHOT_SPEED);
            this.range = Math.max(1, params().num("shot_range"));
        }

        void launch() {
            Vector direction = velocity.clone().normalize();
            Location muzzle = position.clone().add(direction.clone().multiply(0.6)).subtract(0, 0.25, 0);
            long life = (long) Math.ceil(range / SHOT_SPEED) + SHOT_EXTRA_TICKS + 4;
            if (room() >= 1) {
                ItemDisplay display = keep(Displays.item(ctx(), owner, muzzle, new ItemStack(BLOB_ITEM),
                        Displays.centered(0, 0, 0, 0.3f, 0.3f, 0.3f, 0, 0, 0), life));
                display.setTeleportDuration(1);
                blob = display;
                Displays.animateNextTick(ctx(), owner, display, Displays.centered(0, 0, 0, 0.75f, 0.75f, 0.75f,
                        yawOf(direction), 0, 0), 2);
            }
            world.spawnParticle(Particle.SQUID_INK, muzzle, Vfx.n(ctx(), 10), 0.12, 0.12, 0.12, 0.08);
            world.spawnParticle(Particle.DUST, muzzle, Vfx.n(ctx(), 8), 0.15, 0.15, 0.15, 0, INK);
            Vfx.dot(muzzle, Particle.FLASH, PALETTE.body(), false);
            Fx.sound(muzzle, Sound.ENTITY_SQUID_SQUIRT, 1.0f, 1.2f + Fx.jitter(0.1f));
            Fx.sound(muzzle, Sound.ENTITY_LLAMA_SPIT, 0.8f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(muzzle, Sound.ITEM_BUCKET_EMPTY, 0.6f, 1.4f + Fx.jitter(0.1f));
            long maxTicks = (long) Math.ceil(range / SHOT_SPEED) + SHOT_EXTRA_TICKS;
            Loop.repeat(ctx(), owner, 0, 1, maxTicks, this, this::expire);
        }

        @Override
        public boolean test(long tick) {
            double length = velocity.length();
            if (!(length > 1.0E-6)) {
                return burstAt(position.clone(), null);
            }
            Vector direction = velocity.clone().multiply(1 / length);
            RayTraceResult block = world.rayTraceBlocks(position, direction, length, FluidCollisionMode.NEVER, true);
            double limit = block == null ? length : block.getHitPosition().distance(position.toVector());
            LivingEntity struck = null;
            double nearest = limit;
            Location middle = position.clone().add(direction.clone().multiply(length / 2));
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, length / 2 + 1.5)) {
                RayTraceResult hit = enemy.getBoundingBox().expand(SHOT_RADIUS).rayTrace(position.toVector(),
                        direction, limit);
                if (hit == null) {
                    continue;
                }
                double distance = hit.getHitPosition().distance(position.toVector());
                if (distance <= nearest) {
                    nearest = distance;
                    struck = enemy;
                }
            }
            if (struck != null) {
                return burstAt(position.clone().add(direction.clone().multiply(nearest)), struck);
            }
            if (block != null) {
                return burstAt(block.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.15)),
                        null);
            }
            position.add(velocity);
            traveled += length;
            velocity.setY(velocity.getY() - SHOT_GRAVITY);
            fly(direction, tick);
            if (traveled >= range) {
                return burstAt(position.clone(), null);
            }
            return true;
        }

        /** The blob tumbles along, shedding black drops and a purple sheen. */
        private void fly(Vector direction, long tick) {
            ItemDisplay display = blob;
            spin += 40f;
            if (display != null && display.isValid()) {
                Displays.moveTo(display, position.clone().subtract(0, 0.15, 0));
                Displays.animate(display, Displays.centered(0, 0, 0, 0.75f, 0.75f, 0.75f, yawOf(direction), spin,
                        0), 1);
            }
            Location back = position.clone().subtract(direction.clone().multiply(0.4)).subtract(0, 0.15, 0);
            Vfx.dot(back, Particle.DUST, INK, false);
            Vfx.dot(back.clone().subtract(direction.clone().multiply(0.4)), Particle.DUST_COLOR_TRANSITION, SHEEN,
                    false);
            if (tick % 2 == 0) {
                Vfx.dot(back, Particle.SQUID_INK, null, false);
                Vfx.dot(back.clone().subtract(0, 0.2, 0), Particle.FALLING_OBSIDIAN_TEAR, null, false);
            }
        }

        /** The loop ran out without the blob landing (it was still falling): it bursts where it is. */
        private void expire() {
            if (!burst) {
                burstAt(position.clone(), null);
            }
        }

        private boolean burstAt(Location at, @Nullable LivingEntity direct) {
            if (burst) {
                return false;
            }
            burst = true;
            ItemDisplay display = blob;
            if (display != null && display.isValid()) {
                display.remove();
            }
            splash(owner, at, direct);
            return false;
        }
    }

    /** The blob bursts: ink in every nearby enemy's eyes, and a slick puddle on the ground below. */
    private void splash(Player owner, Location at, @Nullable LivingEntity direct) {
        double radius = Math.max(0, params().num("splash_radius"));
        double damage = params().num("shot_damage");
        long blind = params().ticks("shot_blind");
        int hits = 0;
        List<LivingEntity> victims = new ArrayList<>(ctx().enemiesNear(owner, at, radius));
        if (direct != null && !victims.contains(direct) && Combat.isTargetable(ctx(), owner, direct)) {
            victims.add(direct);
        }
        for (LivingEntity enemy : victims) {
            if (!enemy.equals(direct) && !Spots.clearPath(at, Fx.body(enemy))) {
                continue;
            }
            Combat.hit(ctx(), owner, enemy, damage);
            hits++;
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            if (blind > 0) {
                int duration = (int) Math.min(Integer.MAX_VALUE, blind);
                enemy.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, duration, 0, false, true, true));
            }
            if (enemy instanceof Player hidden && !owner.canSee(hidden)) {
                continue; // no ink drawn on someone the octopus cannot see
            }
            Location eyes = enemy.getEyeLocation();
            Vfx.cloud(ctx(), eyes, Particle.SQUID_INK, 6, 0.15, 0.03, null, false);
            eyes.getWorld().spawnParticle(Particle.DUST, eyes, Vfx.n(ctx(), 6), 0.2, 0.1, 0.2, 0, INK_SMALL);
        }
        splatFx(at, radius);
        Location ground = groundUnder(at, PUDDLE_DROP);
        if (ground != null) {
            Puddle old = puddle;
            Puddle fresh = new Puddle(owner, ground);
            puddle = fresh;
            if (old != null) {
                old.retire();
            }
            fresh.start();
        }
        if (hits > 0) {
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">먹물 발사</" + COLOR_TAG + "> <white>" + hits
                    + "명 적중</white> <gray>· 실명 " + Fx.seconds(blind) + "초</gray>"));
        }
    }

    private void splatFx(Location at, double radius) {
        World world = at.getWorld();
        world.spawnParticle(Particle.SQUID_INK, at, Vfx.n(ctx(), 30), 0.3, 0.3, 0.3, 0.12);
        world.spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 24), radius * 0.4, 0.3, radius * 0.4, 0, INK);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, Vfx.n(ctx(), 14), 0.4, 0.3, 0.4, 0, SHEEN);
        Vfx.dot(at, Particle.FLASH, PALETTE.core(), false);
        Vfx.sphere(ctx(), at, Math.max(0.6, radius * 0.6), 20, Particle.DUST, INK_SMALL, false);
        Fx.sound(at, Sound.ENTITY_SLIME_SQUISH, 1.2f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(at, Sound.ENTITY_GENERIC_SPLASH, 1.0f, 0.8f + Fx.jitter(0.1f));
        Fx.sound(at, Sound.ENTITY_SQUID_HURT, 0.6f, 0.7f + Fx.jitter(0.1f));
    }

    /** Per-enemy state in the puddle: its slow modifier and where it was, to tell whether it moves. */
    private static final class Slip {
        @Nullable NamespacedKey slowKey;
        long slowUntil;
        @Nullable Location last;
        long nextSlip;
        /** Whether the enemy stood on the ink at the previous check. */
        boolean grounded;
    }

    /**
     * A slick ink puddle: an eight-pointed star of flat black plates (its points reach the puddle's edge) with a
     * bubbling purple rim on the edge. Enemies standing in it are slowed, and walking makes their feet slip out
     * sideways: the skid throws them off their line and robs them of a little ground, it never carries them on. One
     * puddle at a time.
     */
    private final class Puddle implements LongPredicate {

        private final Player owner;
        private final Location center;
        private final double radius;
        private final long life;
        private final BlockDisplay[] plates = new BlockDisplay[2];
        private final Map<UUID, Slip> slips = new HashMap<>();
        private boolean retired;

        Puddle(Player owner, Location center) {
            this.owner = owner;
            this.center = Displays.unrotated(center);
            this.radius = Math.max(0.5, params().num("puddle_radius"));
            this.life = Math.max(1, params().ticks("puddle_duration"));
        }

        void start() {
            int count = Math.min(plates.length, room());
            for (int i = 0; i < count; i++) {
                float yaw = 45f * i;
                float lift = 0.015f + 0.006f * i;
                BlockDisplay plate = keep(Displays.block(ctx(), owner, center, PUDDLE_BLOCK.createBlockData(),
                        Displays.transform(0, lift, 0, 0.2f, 0.01f, 0.2f, yaw, 0, 0), life + PUDDLE_FADE_TICKS + 6));
                plate.setBrightness(null);
                // Two squares turned 45° apart make an eight-pointed star whose points (the squares' corners) touch
                // the puddle's edge: the ink never looks bigger than the area that slows.
                float size = (float) (radius * Math.sqrt(2));
                Displays.animateNextTick(ctx(), owner, plate, Displays.transform(0, lift, 0, size, 0.01f, size, yaw, 0,
                        0), PUDDLE_GROW_TICKS);
                plates[i] = plate;
            }
            Fx.sound(center, Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.8f, 0.6f + Fx.jitter(0.05f));
            Loop.repeat(ctx(), owner, 1, 1, life, this, this::retire);
        }

        @Override
        public boolean test(long tick) {
            if (retired || puddle != this) {
                retire();
                return false;
            }
            if (tick % PUDDLE_EFFECT_PERIOD == 0) {
                affect();
            }
            draw(tick);
            return true;
        }

        private void affect() {
            long now = ctx().currentTick();
            double slow = Math.clamp(params().num("puddle_slow") / 100.0, 0.0, 1.0);
            Set<UUID> inside = new HashSet<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius + 1.5)) {
                Location feet = enemy.getLocation();
                double dy = feet.getY() - center.getY();
                if (dy < -0.3 || dy > PUDDLE_HEIGHT || horizontalDistance(feet, center) > radius) {
                    continue;
                }
                inside.add(enemy.getUniqueId());
                Slip slip = slips.computeIfAbsent(enemy.getUniqueId(), id -> new Slip());
                if (slow > 0 && (slip.slowKey == null || now >= slip.slowUntil - PUDDLE_EFFECT_PERIOD)) {
                    if (slip.slowKey != null) {
                        ctx().removeModifier(enemy, slip.slowKey);
                    }
                    slip.slowKey = ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow,
                            Operation.MULTIPLY_SCALAR_1, PUDDLE_SLOW_REFRESH);
                    slip.slowUntil = now + PUDDLE_SLOW_REFRESH;
                }
                Location last = slip.last;
                slip.last = feet.clone();
                // Only feet planted on the ink slip (seen on the ground at this check and the one before): a jump
                // is never cut short by a skid.
                boolean planted = Motion.isGrounded(enemy);
                boolean wasPlanted = slip.grounded;
                slip.grounded = planted;
                if (last == null || now < slip.nextSlip || !last.getWorld().equals(feet.getWorld()) || !planted
                        || !wasPlanted) {
                    continue;
                }
                Vector moved = feet.toVector().subtract(last.toVector()).setY(0);
                if (moved.length() < SLIP_MIN_STEP) {
                    continue;
                }
                // The ink gives no grip: the feet slip out to one side and the stride is lost, never carried on.
                Vector heading = moved.normalize();
                double side = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                Vector skid = new Vector(-heading.getZ(), 0, heading.getX()).multiply(SLIP_SIDE * side)
                        .subtract(heading.multiply(SLIP_BACK));
                if (Motion.push(enemy, skid, MAX_SPEED)) {
                    slip.nextSlip = now + SLIP_COOLDOWN;
                    skidFx(enemy, skid);
                }
            }
            // Leaving the puddle ends its slow at once.
            slips.entrySet().removeIf(entry -> {
                if (inside.contains(entry.getKey())) {
                    return false;
                }
                release(entry.getKey(), entry.getValue());
                return true;
            });
        }

        private void skidFx(LivingEntity enemy, Vector skid) {
            Location feet = enemy.getLocation().add(0, 0.1, 0);
            Vfx.line(ctx(), feet, feet.clone().add(skid.clone().setY(0).multiply(2.2)), 5, Particle.DUST, INK_SMALL,
                    false);
            Vfx.cloud(ctx(), feet, Particle.SQUID_INK, 3, 0.15, 0.03, null, false);
            Fx.sound(feet, Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.8f, 1.3f + Fx.jitter(0.15f));
            Fx.sound(feet, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.6f, 1.2f + Fx.jitter(0.1f));
        }

        private void draw(long tick) {
            World world = center.getWorld();
            Location surface = center.clone().add(0, 0.06, 0);
            if (tick % 4 == 0) {
                Vfx.ring(ctx(), surface, radius, (int) Math.min(30, Math.max(12, radius * 7)), tick * 0.05,
                        Particle.DUST_COLOR_TRANSITION, RIM, false);
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            if (tick % 2 == 0) {
                for (int i = 0; i < Vfx.n(ctx(), 2); i++) {
                    Vfx.dot(Vfx.scatter(surface, radius * 0.85), Particle.DUST_COLOR_TRANSITION, SHEEN, false);
                }
            }
            if (tick % 6 == 0) {
                Location pop = Vfx.scatter(surface, radius * 0.8);
                Vfx.dot(pop, Particle.BUBBLE_POP, null, false);
                if (random.nextBoolean()) {
                    world.spawnParticle(Particle.SQUID_INK, pop, 1, 0, 0.02, 0, 0.02);
                }
            }
            if (tick % 30 == 15) {
                Fx.sound(center, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 0.5f, 0.7f + Fx.jitter(0.1f));
            }
        }

        private void release(UUID id, Slip slip) {
            NamespacedKey key = slip.slowKey;
            slip.slowKey = null;
            if (key == null) {
                return;
            }
            Entity entity = owner.getServer().getEntity(id);
            if (entity instanceof LivingEntity living && living.isValid()) {
                ctx().removeModifier(living, key);
            }
        }

        /** The puddle dries up (or a newer one took over): the plates shrink away and every slow ends. */
        void retire() {
            if (retired) {
                return;
            }
            retired = true;
            if (puddle == this) {
                puddle = null;
            }
            slips.forEach(this::release);
            slips.clear();
            for (BlockDisplay plate : plates) {
                if (plate != null && plate.isValid()) {
                    Displays.shrinkAway(ctx(), owner, plate, PUDDLE_FADE_TICKS);
                }
            }
            Vfx.cloud(ctx(), center.clone().add(0, 0.1, 0), Particle.SQUID_INK, 6, radius * 0.4, 0.01, null, false);
        }
    }

    // ------------------------------------------------------------------ ultimate: kraken

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (kraken != null) {
            return ActivationResult.fail("크라켄이 이미 깨어 있습니다");
        }
        Location ground = groundUnder(owner.getLocation(), KRAKEN_GROUND_DROP);
        if (ground == null) {
            return ActivationResult.fail("발밑에 먹물 바다를 펼칠 땅이 없습니다");
        }
        double radius = Math.max(1, params().num("kraken_radius"));
        // Only enemies the octopus can see count: the tentacles never seize a hidden one (that would show where it
        // hides), and a kraken that answers "someone is there" would give it away just the same.
        boolean prey = false;
        for (LivingEntity enemy : ctx().enemiesNear(owner, ground, radius)) {
            if (!(enemy instanceof Player hidden && !owner.canSee(hidden))) {
                prey = true;
                break;
            }
        }
        if (!prey) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        Kraken fresh = new Kraken(owner, ground, radius);
        kraken = fresh;
        fresh.start();
        return ActivationResult.success();
    }

    /**
     * The kraken: an ink sea spreads over the floor around where the octopus stood, then tentacles burst out of it.
     * Each enemy in the sea (seen by the octopus) is seized by a tentacle rising right next to it, hoisted up, held and
     * slammed down; the tentacles left without prey sway at the sea's edge and crash down outwards.
     */
    private final class Kraken implements LongPredicate {

        private final Player owner;
        private final Location center;
        private final World world;
        private final double radius;
        private final long delay;
        private final long slamStart;
        private final long impact;
        private final long retreatStart;
        private final long end;
        private final List<Tentacle> tentacles = new ArrayList<>();
        private final Set<UUID> lashed = new HashSet<>();
        /** Everyone a tentacle seized: they are slammed, never lashed on top. */
        private final Set<UUID> seizedIds = new HashSet<>();
        private int seized;
        private int slammed;

        Kraken(Player owner, Location center, double radius) {
            this.owner = owner;
            this.center = Displays.unrotated(center);
            this.world = center.getWorld();
            this.radius = radius;
            this.delay = Math.max(1, params().ticks("kraken_delay"));
            this.slamStart = Math.max(LIFT_TICKS + 4, params().ticks("kraken_hold"));
            this.impact = slamStart + SLAM_TICKS;
            this.retreatStart = impact + RETREAT_DELAY;
            this.end = retreatStart + RETREAT_TICKS;
        }

        void start() {
            Fx.sound(center, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 0.8f);
            Fx.sound(center, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, 1.2f, 0.6f);
            Fx.sound(center, Sound.AMBIENT_UNDERWATER_ENTER, 1.0f, 0.7f);
            Location chest = owner.getLocation().add(0, owner.getHeight() * 0.6, 0);
            world.spawnParticle(Particle.SQUID_INK, chest, Vfx.n(ctx(), 24), 0.4, 0.4, 0.4, 0.1, null, true);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>≈ 크라켄 ≈</b></" + COLOR_TAG + "> <gray>"
                    + Fx.seconds(delay) + "초 뒤 촉수가 솟아오릅니다</gray>"));
            Loop.repeat(ctx(), owner, 0, 1, delay + end + 1, this, this::finish);
        }

        @Override
        public boolean test(long tick) {
            if (tick < delay) {
                spread(tick);
                return true;
            }
            long e = tick - delay;
            if (e == 0) {
                erupt();
                if (tentacles.isEmpty()) {
                    return false;
                }
            }
            for (Tentacle tentacle : tentacles) {
                tentacle.step(e);
            }
            if (e == impact) {
                strike();
            }
            sea(e);
            return e < end;
        }

        // -------------------------------------------------------------- telegraph

        /** The ink sea spreads out from the octopus's feet, with a dark ring bubbling under everyone in it. */
        private void spread(long tick) {
            double progress = Math.min(1.0, (tick + 1) / (delay * 0.6));
            double r = 0.6 + (radius - 0.6) * progress;
            Location floor = center.clone().add(0, 0.08, 0);
            Vfx.ring(ctx(), floor, r, (int) Math.min(40, Math.max(14, r * 5)), tick * 0.1,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.4f), true);
            for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                Vfx.dot(Vfx.scatter(floor, r), Particle.DUST, INK, true);
            }
            if (tick % 3 == 0) {
                Vfx.dot(Vfx.scatter(floor, r), Particle.SQUID_INK, null, true);
                Vfx.dot(Vfx.scatter(floor, r), Particle.BUBBLE_POP, null, true);
            }
            if (tick % 4 == 0) {
                for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                    if (enemy instanceof Player hidden && !owner.canSee(hidden)) {
                        continue;
                    }
                    Vfx.ring(ctx(), enemy.getLocation().add(0, 0.1, 0), 0.7 - 0.05 * (tick % 8), 10, tick * 0.4,
                            Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f), true);
                }
            }
            if (tick % 6 == 0) {
                Fx.sound(center, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, 0.8f, 0.6f + 0.4f * (float) progress);
            }
        }

        // -------------------------------------------------------------- eruption

        private void erupt() {
            int count = Math.clamp(params().integer("tentacles"), 1, MAX_TENTACLES);
            List<LivingEntity> prey = new ArrayList<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                if (prey.size() >= count) {
                    break;
                }
                if (enemy instanceof Player hidden && !owner.canSee(hidden)) {
                    continue; // seizing it would show where it hides
                }
                prey.add(enemy);
            }
            double phase = ThreadLocalRandom.current().nextDouble(TAU);
            List<Double> slots = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                slots.add(phase + i * TAU / count);
            }
            for (LivingEntity enemy : prey) {
                Location feet = enemy.getLocation();
                Vector inward = Motion.horizontal(center.toVector().subtract(feet.toVector()));
                if (inward.lengthSquared() < 1.0E-6) {
                    inward = Motion.horizontalFacing(owner.getLocation()).multiply(-1);
                }
                Location base = groundUnder(feet.clone().add(inward.clone().multiply(GRAB_BASE_OFFSET)).add(0, 1.2, 0),
                        3.0);
                if (base == null) {
                    // A wall or pillar right beside the prey: the tentacle bursts out under its feet instead.
                    base = groundUnder(feet.clone().add(0, 0.5, 0), 3.0);
                }
                if (base == null) {
                    continue;
                }
                double angle = Math.atan2(base.getZ() - center.getZ(), base.getX() - center.getX());
                removeClosest(slots, angle);
                tentacles.add(new Tentacle(base, outwardOf(base), enemy));
            }
            for (double angle : slots) {
                Location spot = center.clone().add(Math.cos(angle) * radius * FREE_RING, 1.5,
                        Math.sin(angle) * radius * FREE_RING);
                Location base = groundUnder(spot, 4.0);
                if (base == null) {
                    base = groundUnder(spot.clone().subtract(0, 1.0, 0), 3.0); // under a low ceiling
                }
                if (base != null) {
                    tentacles.add(new Tentacle(base, outwardOf(base), null));
                }
            }
            // The tentacles share what is left of the display budget: three segments each while it lasts (the
            // seizing ones first), two for the rest.
            int left = room();
            for (int i = 0; i < tentacles.size(); i++) {
                int rest = tentacles.size() - i - 1;
                int segments = left - 3 >= 2 * rest ? 3 : left - 2 >= 2 * rest ? 2 : Math.max(0, Math.min(2, left));
                left -= segments;
                tentacles.get(i).spawn(segments);
            }
            Fx.sound(center, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.4f, 0.6f);
            Fx.sound(center, Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 1.0f, 0.6f);
            Fx.sound(center, Sound.ENTITY_WARDEN_DIG, 0.8f, 1.4f);
            if (seized > 0) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>≈ 크라켄 ≈</b></" + COLOR_TAG + "> <white>" + seized
                        + "명 포획</white>"));
            }
        }

        private Vector outwardOf(Location base) {
            Vector out = Motion.horizontal(base.toVector().subtract(center.toVector()));
            if (out.lengthSquared() < 1.0E-6) {
                double angle = ThreadLocalRandom.current().nextDouble(TAU);
                out = new Vector(Math.cos(angle), 0, Math.sin(angle));
            }
            return out;
        }

        // -------------------------------------------------------------- the strike

        /** The free tentacles crash down outwards; enemies under them (not already seized) are lashed. */
        private void strike() {
            double damage = params().num("lash_damage");
            for (Tentacle tentacle : tentacles) {
                if (tentacle.seizing) {
                    continue;
                }
                Location point = tentacle.lashPoint();
                lashFx(point, tentacle.outward);
                for (LivingEntity enemy : ctx().enemiesNear(owner, point.clone().add(0, 0.5, 0), LASH_RADIUS)) {
                    if (seizedIds.contains(enemy.getUniqueId()) || !lashed.add(enemy.getUniqueId())) {
                        continue;
                    }
                    Combat.hit(ctx(), owner, enemy, damage);
                    if (Combat.isTargetable(ctx(), owner, enemy)) {
                        Motion.push(enemy, tentacle.outward.clone().multiply(LASH_PUSH).setY(LASH_LIFT), MAX_SPEED);
                    }
                }
            }
            if (slammed + lashed.size() > 0) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>≈ 크라켄 ≈</b></" + COLOR_TAG + "> <white>"
                        + slammed + "명 내리침</white>" + (lashed.isEmpty() ? ""
                        : " <gray>· 휩쓸림 " + lashed.size() + "명</gray>")));
            }
        }

        private void lashFx(Location point, Vector outward) {
            Location ground = point.clone().add(0, 0.1, 0);
            BlockData floor = floorAt(point);
            world.spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 14), 0.5, 0.1, 0.5, 0.1, floor, true);
            world.spawnParticle(Particle.SQUID_INK, ground, Vfx.n(ctx(), 10), 0.4, 0.1, 0.4, 0.08, null, true);
            Vfx.arc(ctx(), ground, 1.2, yawOf(outward), 160, 12, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.2f),
                    true);
            Fx.sound(ground, Sound.ENTITY_GUARDIAN_FLOP, 1.0f, 0.5f + Fx.jitter(0.05f));
            Fx.sound(ground, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 0.6f + Fx.jitter(0.05f));
        }

        /** The ink sea while the tentacles are out: dark ripples, ink spots and bubbles, fading as they sink. */
        private void sea(long e) {
            Location floor = center.clone().add(0, 0.08, 0);
            boolean fading = e >= retreatStart;
            if (e % 3 == 0) {
                Vfx.ring(ctx(), floor, radius, (int) Math.min(40, Math.max(14, radius * 4)), e * 0.03,
                        Particle.DUST_COLOR_TRANSITION, fading ? PALETTE.fade(0.9f) : PALETTE.fade(1.3f), true);
            }
            if (!fading || e % 2 == 0) {
                for (int i = 0; i < Vfx.n(ctx(), 2); i++) {
                    Vfx.dot(Vfx.scatter(floor, radius), Particle.DUST, INK, false);
                }
            }
            if (e % 5 == 0) {
                Vfx.dot(Vfx.scatter(floor, radius), Particle.BUBBLE_POP, null, false);
                Vfx.dot(Vfx.scatter(floor, radius), Particle.SQUID_INK, null, false);
            }
            if (e % 20 == 10 && e < retreatStart) {
                Fx.sound(center, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, 0.7f, 0.6f);
            }
            if (e == retreatStart) {
                Fx.sound(center, Sound.AMBIENT_UNDERWATER_EXIT, 1.0f, 0.6f);
                Fx.sound(center, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 1.0f, 0.5f);
            }
        }

        /** The ultimate ran its course: whatever is still held is let go (it is already on the ground). */
        private void finish() {
            if (kraken == this) {
                kraken = null;
            }
            for (Tentacle tentacle : tentacles) {
                tentacle.vanish();
            }
        }

        // -------------------------------------------------------------- one tentacle

        /**
         * One tentacle: three (or, on a tight display budget, two) block displays anchored just above its base (lit
         * by the air there), each drawn as a box between two joints of a quadratic curve from below the ground to the
         * tip. All of its pose lives in the transformations; the displays themselves never move or turn.
         *
         * <p>A seized player moves on its own client and the server sees it a ping late, so it is steered by where
         * the velocities sent to it have put it (dead reckoning), never by the server's lagging view: steering by
         * that would keep pushing a body that is already there and fling it past the hold. A mob moves on the server,
         * whose view of it is exact.
         */
        private final class Tentacle {

            private final Location base;
            private final Location anchor;
            private final Vector outward;
            private final Vector side;
            private final double sway;
            private BlockDisplay[] segments = new BlockDisplay[0];
            private double[] jointsAt = JOINTS;
            private @Nullable LivingEntity prey;
            private final boolean seizing;
            private @Nullable Location preyStart;
            private double preyGround;
            private double lift;
            private @Nullable Location planned;
            /** Where the velocities sent to a seized player have put it (its client follows them). */
            private @Nullable Vector reckoned;
            private double slamFrom;

            Tentacle(Location base, Vector outward, @Nullable LivingEntity prey) {
                this.base = Displays.unrotated(base);
                this.anchor = this.base.clone().add(0, ANCHOR_LIFT, 0);
                this.outward = outward;
                this.side = new Vector(-outward.getZ(), 0, outward.getX());
                this.sway = ThreadLocalRandom.current().nextDouble(TAU);
                this.prey = prey;
                this.seizing = prey != null;
            }

            void spawn(int count) {
                long life = end + 6;
                boolean full = count >= TENTACLE_BLOCKS.length;
                Material[] blocks = full ? TENTACLE_BLOCKS : SHORT_TENTACLE_BLOCKS;
                jointsAt = full ? JOINTS : SHORT_JOINTS;
                segments = new BlockDisplay[Math.min(count, blocks.length)];
                for (int i = 0; i < segments.length; i++) {
                    BlockDisplay segment = keep(Displays.block(ctx(), owner, anchor, blocks[i].createBlockData(),
                            new Transformation(new Vector3f(0, -0.5f, 0), new Quaternionf(), new Vector3f(0.01f),
                                    new Quaternionf()), life));
                    segment.setBrightness(null);
                    segment.setViewRange(1.6f);
                    segments[i] = segment;
                }
                BlockData floor = floorAt(base);
                Location at = base.clone().add(0, 0.2, 0);
                world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 16), 0.35, 0.15, 0.35, 0.15, floor, true);
                world.spawnParticle(Particle.SQUID_INK, at, Vfx.n(ctx(), 8), 0.3, 0.2, 0.3, 0.12, null, true);
                world.spawnParticle(Particle.SPLASH, at, Vfx.n(ctx(), 14), 0.4, 0.2, 0.4, 0.2, null, true);
                Fx.sound(at, Sound.ENTITY_GUARDIAN_ATTACK, 0.6f, 1.6f + Fx.jitter(0.1f));
                LivingEntity enemy = prey;
                if (enemy != null) {
                    seize(enemy);
                }
            }

            private void seize(LivingEntity enemy) {
                Combat.hit(ctx(), owner, enemy, params().num("grab_damage"));
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    prey = null;
                    return;
                }
                seized++;
                seizedIds.add(enemy.getUniqueId());
                Location start = enemy.getLocation();
                preyStart = start;
                Location ground = groundUnder(start.clone().add(0, 0.3, 0), 6.0);
                preyGround = ground != null ? ground.getY() : start.getY();
                lift = headroom(enemy, start);
                reckoned = start.toVector();
                enemy.setFallDistance(0);
                // A safety net while held: should the tentacle let go in the air (the octopus fell, the prey
                // slipped free), it floats down instead of taking a fall.
                holdSafely(enemy, impact + 4);
                Location body = Fx.body(enemy);
                Vfx.burst(ctx(), body, PALETTE, 0.6, Particle.SQUID_INK, true);
                Fx.sound(body, Sound.ENTITY_SLIME_SQUISH, 1.0f, 0.5f + Fx.jitter(0.05f));
                Fx.sound(body, Sound.ENTITY_SQUID_HURT, 0.8f, 0.6f + Fx.jitter(0.05f));
            }

            /** How high the enemy can be lifted without its body touching a block on the way. */
            private double headroom(LivingEntity enemy, Location start) {
                double width = enemy.getWidth();
                double height = enemy.getHeight();
                for (double h = KRAKEN_LIFT; h > 0; h -= LIFT_STEP) {
                    if (Spots.isFree(world, start.clone().add(0, h, 0), width, height)
                            && Spots.isFree(world, start, width, height + h)) {
                        return h;
                    }
                }
                return 0;
            }

            void step(long e) {
                LivingEntity enemy = prey;
                if (enemy != null && e <= impact) {
                    hold(enemy, e);
                }
                // Never on the spawn tick: a display's first interpolation needs a tick after it appeared.
                if (e >= 1 && (e % POSE_PERIOD == 1 || e == impact || e == slamStart)) {
                    pose(e, e == impact || e == slamStart ? 1 : POSE_PERIOD);
                }
                if (e > RISE_TICKS && e < retreatStart && e % 4 == (seizing ? 0 : 2)) {
                    suckers(e);
                }
            }

            /** Hoists, holds and slams the seized enemy (steered to a planned path, like a held body). */
            private void hold(LivingEntity enemy, long e) {
                Location start = preyStart;
                if (start == null) {
                    return;
                }
                if (!Combat.isTargetable(ctx(), owner, enemy) || !enemy.getWorld().equals(world) || slipped(enemy)) {
                    prey = null;
                    return; // its safety net stays on: it floats down
                }
                double y;
                if (e < LIFT_TICKS) {
                    double s = (e + 1.0) / LIFT_TICKS;
                    y = start.getY() + lift * (1 - (1 - s) * (1 - s));
                } else if (e < slamStart) {
                    y = start.getY() + lift + Math.sin(e * 0.45) * 0.12 * Math.min(1, lift);
                } else if (e < impact) {
                    if (e == slamStart) {
                        Location at = planned;
                        slamFrom = at != null ? at.getY() : start.getY() + lift;
                        slamCue(enemy);
                    }
                    double s = (e - slamStart + 1.0) / SLAM_TICKS;
                    y = slamFrom - (slamFrom - preyGround) * s * s;
                } else {
                    slam(enemy);
                    return;
                }
                Location next = new Location(world, start.getX(), y, start.getZ());
                steer(enemy, next);
                planned = next;
                if (e % 3 == 0) {
                    Location waist = next.clone().add(0, enemy.getHeight() * 0.45, 0);
                    Vfx.ring(ctx(), waist, enemy.getWidth() * 0.5 + 0.25, 8, e * 0.6, Particle.DUST_COLOR_TRANSITION,
                            PALETTE.glow(0.8f), false);
                }
            }

            /**
             * Steers the held body to {@code next}: a player from where the velocities sent so far have put it, a mob
             * from where the server sees it.
             */
            private void steer(LivingEntity enemy, Location next) {
                Vector from = enemy instanceof Player && reckoned != null ? reckoned : enemy.getLocation().toVector();
                Vector velocity = Motion.clamp(next.toVector().subtract(from), MAX_SPEED);
                reckoned = from.clone().add(velocity);
                enemy.setVelocity(velocity);
                enemy.setFallDistance(0);
            }

            /**
             * True when the held enemy got away from the tentacle (a teleport or a dash). A player's server position
             * trails the hold by up to its ping, so it gets that much more leash.
             */
            private boolean slipped(LivingEntity enemy) {
                Location at = planned;
                if (at == null) {
                    return false;
                }
                Location now = enemy.getLocation();
                long lag = enemy instanceof Player player ? lagTicks(player) : 0;
                return horizontalDistance(now, at) > HOLD_LEASH + 0.3 * lag
                        || Math.abs(now.getY() - at.getY()) > HOLD_LEASH + 1 + 1.5 * lag;
            }

            private void slamCue(LivingEntity enemy) {
                Location body = Fx.body(enemy);
                Fx.sound(body, Sound.ENTITY_PHANTOM_SWOOP, 0.8f, 0.6f + Fx.jitter(0.05f));
                Fx.sound(body, Sound.ITEM_MACE_SMASH_AIR, 0.8f, 0.7f + Fx.jitter(0.05f));
            }

            private void slam(LivingEntity enemy) {
                prey = null;
                clearSafety(enemy);
                enemy.setFallDistance(0);
                Combat.hit(ctx(), owner, enemy, params().num("slam_damage"));
                Location feet = enemy.getLocation();
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    slammed++;
                    enemy.setVelocity(new Vector(0, 0.15, 0));
                    long slow = params().ticks("slam_slow");
                    if (slow > 0) {
                        enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                                (int) Math.min(Integer.MAX_VALUE, slow), 1, false, true, true));
                    }
                }
                Location ground = feet.clone();
                ground.setY(preyGround + 0.1);
                BlockData floor = floorAt(ground);
                world.spawnParticle(Particle.BLOCK, ground, Vfx.n(ctx(), 24), 0.6, 0.1, 0.6, 0.15, floor, true);
                world.spawnParticle(Particle.SQUID_INK, ground.clone().add(0, 0.3, 0), Vfx.n(ctx(), 16), 0.5, 0.2,
                        0.5, 0.1, null, true);
                Vfx.dot(ground.clone().add(0, 0.6, 0), Particle.FLASH, PALETTE.core(), true);
                Vfx.shockwave(ctx(), owner, ground, 0.4, 2.2, 5, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.3f),
                        true);
                Fx.sound(ground, Sound.ENTITY_GUARDIAN_FLOP, 1.4f, 0.4f);
                Fx.sound(ground, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 0.9f, 0.8f + Fx.jitter(0.05f));
                Fx.sound(ground, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.9f, 0.7f + Fx.jitter(0.05f));
            }

            /** Pink sucker dots along the tentacle and ink dripping from its tip. */
            private void suckers(long e) {
                Location[] joints = joints(e);
                for (int i = 1; i < joints.length; i++) {
                    Vfx.dot(joints[i].clone().add(side.clone().multiply(0.2)), Particle.DUST, SUCKER, false);
                }
                Location tip = joints[joints.length - 1];
                Vfx.dot(tip.clone().subtract(0, 0.2, 0), Particle.FALLING_OBSIDIAN_TEAR, null, false);
                if (e % 8 == 0) {
                    Fx.sound(tip, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.5f, 0.6f + Fx.jitter(0.1f));
                }
            }

            /** The lashing tentacle's tip lands here (short of a wall in its way: then on its own base). */
            Location lashPoint() {
                Location point = base.clone().add(outward.clone().multiply(LASH_LENGTH));
                Location ground = groundUnder(point.clone().add(0, 1.2, 0), 3.0);
                if (ground == null) {
                    ground = groundUnder(point.clone().add(0, 0.3, 0), 2.0);
                }
                return ground != null ? ground : base.clone();
            }

            private void pose(long e, int interpolation) {
                Location[] joints = joints(e);
                for (int i = 0; i < segments.length; i++) {
                    BlockDisplay segment = segments[i];
                    if (segment == null || !segment.isValid()) {
                        continue;
                    }
                    Vector from = joints[i].toVector().subtract(anchor.toVector());
                    Vector delta = joints[i + 1].toVector().subtract(joints[i].toVector());
                    float width = jointsAt == JOINTS ? TENTACLE_WIDTHS[i] : SHORT_TENTACLE_WIDTHS[i];
                    Displays.animate(segment, segmentPose(from, delta, width), interpolation);
                }
            }

            /** The tentacle's joints (root to tip) at relative tick {@code e}. */
            Location[] joints(long e) {
                Location root = base.clone().subtract(0, ROOT_DEPTH, 0);
                Vector up = new Vector(0, 1, 0);
                Location tip;
                Location control;
                LivingEntity enemy = prey;
                Location held = planned;
                if (enemy != null && seizing && e <= impact) {
                    Location at = held != null ? held : enemy.getLocation();
                    Location waist = at.clone().add(0, enemy.getHeight() * 0.45, 0);
                    Vector across = Motion.horizontal(waist.toVector().subtract(base.toVector()));
                    tip = waist.clone().add(across.multiply(0.25));
                    double rise = Math.max(1.2, waist.getY() - base.getY() + 0.9);
                    control = base.clone().add(0, rise, 0)
                            .add(Motion.horizontal(waist.toVector().subtract(base.toVector())).multiply(0.3));
                } else if (e >= slamStart) {
                    double s = Math.clamp((e - slamStart + 1.0) / SLAM_TICKS, 0.0, 1.0);
                    double angle = Math.PI / 2 * s * s;
                    double length = seizing ? 1.6 : LASH_LENGTH;
                    tip = base.clone().add(up.clone().multiply(Math.cos(angle) * length + 0.15))
                            .add(outward.clone().multiply(Math.sin(angle) * length));
                    if (e >= impact) {
                        tip.add(side.clone().multiply(Math.sin(e * 0.7 + sway) * 0.15));
                    }
                    control = base.clone().add(up.clone().multiply(1.2)).add(outward.clone().multiply(0.4 * s));
                } else {
                    double wave = Math.sin(e * 0.25 + sway);
                    tip = base.clone().add(0, IDLE_HEIGHT, 0).add(outward.clone().multiply(0.5))
                            .add(side.clone().multiply(wave * 0.5));
                    control = base.clone().add(0, IDLE_HEIGHT * 0.55, 0).subtract(outward.clone().multiply(0.25))
                            .add(side.clone().multiply(wave * 0.25));
                }
                double growth;
                if (e < RISE_TICKS) {
                    double s = (e + 1.0) / RISE_TICKS;
                    growth = 1 - (1 - s) * (1 - s);
                } else if (e >= retreatStart) {
                    growth = Math.max(0.02, 1 - (e - retreatStart + 1.0) / RETREAT_TICKS);
                } else {
                    growth = 1;
                }
                Location[] joints = new Location[jointsAt.length];
                for (int i = 0; i < jointsAt.length; i++) {
                    Location point = bezier(root, control, tip, jointsAt[i]);
                    joints[i] = root.clone().add(point.toVector().subtract(root.toVector()).multiply(growth));
                }
                return joints;
            }

            /** The tentacle sinks away at once (the ultimate is over or the octopus is gone). */
            void vanish() {
                prey = null;
                for (BlockDisplay segment : segments) {
                    if (segment != null && segment.isValid()) {
                        Displays.shrinkAway(ctx(), owner, segment, 4);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Slow falling as a safety net for a held body, removed with {@link #clearSafety} when it is slammed down. */
    private static void holdSafely(LivingEntity held, long holdTicks) {
        int ticks = (int) Math.min(Integer.MAX_VALUE, holdTicks + 60);
        held.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, ticks, 0, true, false, false));
    }

    /** Removes the safety net of {@link #holdSafely}, never a slow falling the body got elsewhere. */
    private static void clearSafety(LivingEntity held) {
        PotionEffect effect = held.getPotionEffect(PotionEffectType.SLOW_FALLING);
        if (effect != null && effect.isAmbient() && !effect.hasParticles() && !effect.hasIcon()
                && effect.getAmplifier() == 0) {
            held.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
    }

    /** Ticks the server sees a player's movement late: one round trip, capped (ping can read 0 after joining). */
    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / MILLIS_PER_TICK));
    }

    private static void removeClosest(List<Double> angles, double angle) {
        int best = -1;
        double bestGap = Double.MAX_VALUE;
        for (int i = 0; i < angles.size(); i++) {
            double gap = Math.abs(Math.IEEEremainder(angles.get(i) - angle, TAU));
            if (gap < bestGap) {
                bestGap = gap;
                best = i;
            }
        }
        if (best >= 0) {
            angles.remove(best);
        }
    }

    /**
     * The ground under {@code point}: the top of the first solid block at most {@code maxDrop} blocks below it, or
     * null. The probe starts a little above the point (a ray starting on a face reports a hit inside the block), and
     * there is no ground to find from a point inside a block (a wall, a pillar, a low ceiling): null too.
     */
    private static @Nullable Location groundUnder(Location point, double maxDrop) {
        Location from = point.clone().add(0, 0.05, 0);
        if (!Spots.isFree(point.getWorld(), from, PROBE_SIZE, PROBE_SIZE)) {
            return null;
        }
        RayTraceResult ground = point.getWorld().rayTraceBlocks(from, new Vector(0, -1, 0), maxDrop + 0.05,
                FluidCollisionMode.NEVER, true);
        if (ground == null) {
            return null;
        }
        Location at = point.clone();
        at.setY(ground.getHitPosition().getY());
        return at;
    }

    /** The block a spot stands on, for crumble particles (stone if it is air). */
    private static BlockData floorAt(Location at) {
        Block below = at.clone().subtract(0, 0.2, 0).getBlock();
        return below.getType().isAir() ? Material.STONE.createBlockData() : below.getBlockData();
    }

    private static Location bezier(Location a, Location b, Location c, double t) {
        double u = 1 - t;
        return new Location(a.getWorld(),
                u * u * a.getX() + 2 * u * t * b.getX() + t * t * c.getX(),
                u * u * a.getY() + 2 * u * t * b.getY() + t * t * c.getY(),
                u * u * a.getZ() + 2 * u * t * b.getZ() + t * t * c.getZ());
    }

    /**
     * A box of width {@code width} from {@code from} (relative to the display) along {@code delta}, slightly longer
     * so neighbouring segments overlap at their joint.
     */
    static Transformation segmentPose(Vector from, Vector delta, float width) {
        Vector3f direction = new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ());
        float length = direction.length();
        if (!(length > 1.0E-4f)) {
            return new Transformation(new Vector3f((float) from.getX(), (float) from.getY(), (float) from.getZ()),
                    new Quaternionf(), new Vector3f(0.001f), new Quaternionf());
        }
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), direction.normalize());
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, -width / 2, -width * 0.25f))
                .add((float) from.getX(), (float) from.getY(), (float) from.getZ());
        return new Transformation(translation, rotation, new Vector3f(width, width, length + width * 0.5f),
                new Quaternionf());
    }

    /** A cube of edge {@code size} centered on the display's origin, rotated around its center. */
    private static Transformation cubePose(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    private static double horizontalDistance(Location a, Location b) {
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
}
