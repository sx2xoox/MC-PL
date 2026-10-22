package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
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
 * 눈사람 — a living snowman. Every snowball and punch packs another layer of snow onto an enemy (slowing it more and
 * more) until the third layer turns it into a snowman of its own, frozen stiff for a moment; the active is a quick
 * barrage of snowballs aimed shot by shot, and the ultimate rolls a giant snowball that grows as it goes, rolls up
 * every enemy in its way and bursts into a blizzard at the end.
 *
 * <p>Unlike the 빙결술사's ice (one spear, one freeze), the snowman's control builds up: layer by layer, visibly
 * piling up on the victim, and the freeze only comes with the last layer (then the victim is immune for a while).
 *
 * <p>Look: snow white with a pale blue shadow, carrot orange and coal black. The snowman is always wrapped in a light
 * flurry and leaves powdery footprints. Snow layers pile up around a victim's legs as an octagonal mound of snow; a
 * victim frozen by the third layer becomes a snowman of round snow balls with coal eyes and a carrot nose pointing
 * the way it faced. Snowballs are spinning snowball sprites that splat in white puffs. The giant snowball is a tumbling
 * cluster of snow blocks rolling over a powdery track, swallowing whoever it touches, and bursts into flying chunks,
 * a white flash and a blizzard ring.
 */
public final class SnowmanAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("snowman", "눈사람", Material.SNOWBALL)
            .color(TextColor.color(0xBFE9FF))
            .difficulty(1)
            .passive("눈 범벅",
                    "내 눈덩이나 근접 공격에 맞은 적에게 눈이 한 겹씩 쌓입니다(최대 {snow_max}겹, {snow_duration}초 유지, "
                            + "근접 공격은 같은 적에게 {melee_stack_cooldown}초마다). 한 겹마다 이동 속도가 {snow_slow}% "
                            + "느려지고, {snow_max}겹이 쌓이면 눈사람이 되어 {freeze_damage} 피해를 입고 {freeze_duration}초간 "
                            + "꽁꽁 얼어붙습니다(같은 적은 {freeze_immunity}초마다).")
            .active("눈덩이 연사",
                    "눈덩이 {ball_count}개를 {ball_interval}초 간격으로 연달아 던집니다({ball_range}블록). 눈덩이는 던지는 "
                            + "순간 바라보는 곳으로 날아가 처음 맞은 적에게 {ball_damage} 피해를 주고 살짝 밀쳐내며 눈을 한 겹 "
                            + "쌓습니다.",
                    8)
            .ultimate("거대 눈덩이",
                    "{ult_windup}초간 눈을 뭉친 뒤 바라보는 방향으로 거대한 눈덩이를 {ult_distance}블록까지 굴려 보냅니다. "
                            + "눈덩이는 구를수록 커지며(반경 최대 {ult_max_radius}블록), 닿은 적에게 {ult_pickup_damage} 피해를 "
                            + "주고 속에 말아 넣은 채 굴러갑니다. 벽에 부딪히거나 끝까지 구르면 터져 말려 든 적과 반경 "
                            + "{ult_burst_radius}블록의 적에게 {ult_burst_damage} 피해를 주고 사방으로 날려 보내며 눈을 "
                            + "{ult_burst_stacks}겹 쌓습니다.")
            .param("snow_max", 3)
            .param("snow_duration", 4)
            .param("snow_slow", 10)
            .param("melee_stack_cooldown", 1)
            .param("freeze_damage", 2)
            .param("freeze_duration", 1.25)
            .param("freeze_immunity", 5)
            .param("ball_count", 5)
            .param("ball_interval", 0.15)
            .param("ball_range", 24)
            .param("ball_damage", 0.8)
            .param("ult_windup", 0.5)
            .param("ult_distance", 16)
            .param("ult_start_radius", 1)
            .param("ult_max_radius", 2.4)
            .param("ult_pickup_damage", 2)
            .param("ult_burst_radius", 4)
            .param("ult_burst_damage", 5)
            .param("ult_burst_stacks", 2)
            .build();

    private static final String COLOR_TAG = "#BFE9FF";
    private static final String CARROT_TAG = "#FF9A3C";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** Sparkling white, powder snow, the pale blue of snow in shadow. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFFFFF, 0xE4F6FF, 0x8CC4EC);
    private static final Particle.DustTransition POWDER = PALETTE.glow(1.1f);
    private static final Particle.DustTransition SHADOW = PALETTE.fade(1.0f);
    private static final Particle.DustOptions SNOW_DUST = PALETTE.dust(1.2f);
    private static final Particle.DustOptions CARROT_DUST = new Particle.DustOptions(Color.fromRGB(0xFF8C1A), 0.9f);
    private static final Particle.DustOptions COAL_DUST = new Particle.DustOptions(Color.fromRGB(0x2B2B2B), 0.8f);

    /** Block and item types of the visuals; block data and items are created on use (they need a running server). */
    private static final Material SNOW = Material.SNOW_BLOCK;
    private static final Material POWDER_BLOCK = Material.POWDER_SNOW;
    private static final Material BALL_ITEM = Material.SNOWBALL;
    private static final Material NOSE_ITEM = Material.CARROT;
    private static final Material EYE_ITEM = Material.COAL;

    /** Display entities this ability keeps alive at most at once (optional pieces are skipped beyond it). */
    private static final int MAX_DISPLAYS = 24;
    /** Caps every velocity this ability applies. */
    private static final double MAX_SPEED = 1.6;

    // passive: snow layers
    private static final long FLURRY_PERIOD = 4;
    private static final long STEP_PERIOD = 6;
    /** A layer of snow on a victim reaches this high per layer (in blocks, the mound stops at the hips). */
    private static final float LAYER_HEIGHT = 0.38f;
    private static final float MOUND_MARGIN = 0.32f;
    private static final int MOUND_GROW_TICKS = 4;
    private static final long FLAKE_PERIOD = 5;
    /** The frozen victim's snow balls. */
    private static final float BASE_SIZE = 1.15f;
    private static final float HEAD_SIZE = 0.72f;
    private static final int SHELL_GROW_TICKS = 4;

    // active: snowball barrage
    private static final double BALL_SPEED = 1.45;
    private static final double BALL_GRAVITY = 0.03;
    private static final double BALL_HITBOX = 0.35;
    private static final double SAMPLE_STEP = 0.4;
    private static final double BALL_PUSH = 0.18;
    private static final float BALL_SCALE = 0.42f;

    // ultimate: giant snowball
    private static final double ROLL_SPEED = 0.42;
    /** The rolling ball looks this far ahead for walls (beyond its own radius). */
    private static final double WALL_LOOKAHEAD = 0.25;
    /** Ground under the rolling ball is searched this far above (steps) and below (slopes) its foot. */
    private static final double STEP_UP = 1.1;
    private static final double STEP_DOWN = 2.2;
    private static final double CARRY_PULL = 0.5;
    private static final double CARRY_LEASH = 3.0;
    private static final long MAX_LAG_TICKS = 8;
    private static final double BURST_PUSH = 0.95;
    private static final double BURST_LIFT = 0.55;
    /** The ball is a cluster of snow cubes in different orientations, which together read as a rough sphere. */
    private static final Quaternionf[] BALL_ORIENTATIONS = {
            new Quaternionf(),
            new Quaternionf().rotateY((float) Math.toRadians(45)).rotateX((float) Math.toRadians(35.26)),
            new Quaternionf().rotateZ((float) Math.toRadians(45)).rotateY((float) Math.toRadians(30)),
    };
    /** A cube of edge {@code radius * BALL_EDGE} reads as a ball of about that radius. */
    private static final float BALL_EDGE = 1.55f;

    /** Every display this ability currently has alive, for the budget. */
    private final List<Display> displays = new ArrayList<>();
    /** Snow on enemies, by entity id. */
    private final Map<UUID, Snow> snow = new HashMap<>();
    private boolean snowTicking;
    private @Nullable Location lastStep;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        // Displays and modifiers are owner-scoped: the framework removes them. Only forget them here.
        snow.clear();
        displays.clear();
        lastStep = null;
    }

    // ------------------------------------------------------------------ passive: flurry around the snowman

    /** The snowman is always wrapped in a light flurry and leaves powdery footprints when it walks. */
    @Override
    public void onTick(long tick) {
        Player owner = player();
        if (tick % FLURRY_PERIOD == 0) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Location head = owner.getLocation().add(0, owner.getHeight() + 0.35, 0);
            for (int i = 0; i < Vfx.n(ctx(), 2); i++) {
                Location flake = head.clone().add(random.nextDouble(-0.7, 0.7), random.nextDouble(0, 0.4),
                        random.nextDouble(-0.7, 0.7));
                owner.getWorld().spawnParticle(Particle.SNOWFLAKE, flake, 0, 0, -1, 0, 0.03);
            }
        }
        if (tick % STEP_PERIOD == 0) {
            Location feet = owner.getLocation();
            Location previous = lastStep;
            lastStep = feet;
            if (previous != null && previous.getWorld().equals(feet.getWorld())
                    && previous.distanceSquared(feet) > 0.25 && Motion.isGrounded(owner)) {
                Location print = feet.clone().add(0, 0.05, 0);
                Vfx.dot(print, Particle.DUST, SNOW_DUST, false);
                Vfx.cloud(ctx(), print, Particle.WHITE_ASH, 2, 0.15, 0.01, null, false);
            }
        }
    }

    // ------------------------------------------------------------------ passive: snow layers

    /** A punch packs snow onto the victim (once per cooldown per enemy). */
    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage() || !player().equals(event.getDamager())
                || event.getCause() != DamageCause.ENTITY_ATTACK || !ctx().isEnemy(player(), victim)) {
            return;
        }
        long now = ctx().currentTick();
        Snow state = snow.computeIfAbsent(victim.getUniqueId(), id -> new Snow(victim));
        if (now - state.lastMelee < params().ticks("melee_stack_cooldown")) {
            return;
        }
        state.lastMelee = now;
        ensureSnowTicking();
        // The hit is still being applied: pack the snow on the next tick, when the victim may already be dead.
        ctx().runLater(player(), 1, () -> {
            if (Combat.isTargetable(ctx(), player(), victim)) {
                addLayers(victim, 1);
            }
        });
    }

    /** Snow piled up on one enemy: its layers, their slow, the mound around its legs and the freeze. */
    private final class Snow {
        final LivingEntity enemy;
        int layers;
        long expires;
        long immuneUntil = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
        long lastMelee = Long.MIN_VALUE / 2;
        @Nullable NamespacedKey slowKey;
        final List<BlockDisplay> mound = new ArrayList<>();
        final List<Display> shell = new ArrayList<>();
        /** Offsets of the shell pieces from the victim's feet, by index (the shell rides along). */
        final List<Vector> shellOffsets = new ArrayList<>();
        boolean frozen;
        boolean hidden;

        Snow(LivingEntity enemy) {
            this.enemy = enemy;
        }
    }

    /**
     * Packs {@code count} layers of snow onto {@code enemy}: refreshes and deepens the slow, raises the mound and
     * freezes it into a snowman when the last layer lands. A frozen or still immune enemy only sheds the snow.
     */
    private void addLayers(LivingEntity enemy, int count) {
        if (count <= 0 || !Combat.isTargetable(ctx(), player(), enemy)) {
            return;
        }
        Snow state = snow.computeIfAbsent(enemy.getUniqueId(), id -> new Snow(enemy));
        long now = ctx().currentTick();
        ensureSnowTicking();
        if (state.frozen || now < state.immuneUntil) {
            shed(enemy);
            return;
        }
        int max = Math.max(1, params().integer("snow_max"));
        state.layers = Math.min(max, state.layers + count);
        state.expires = now + Math.max(1, params().ticks("snow_duration"));
        if (state.layers >= max) {
            freeze(state);
        } else {
            applySlow(state);
            raiseMound(state);
            packFx(enemy, state.layers, max);
        }
    }

    private void applySlow(Snow state) {
        clearSlow(state);
        double slow = Math.min(0.9, Math.max(0, params().num("snow_slow")) * state.layers / 100.0);
        if (slow > 0) {
            state.slowKey = ctx().addModifier(player(), state.enemy, Attribute.MOVEMENT_SPEED, -slow,
                    Operation.MULTIPLY_SCALAR_1, Math.max(1, params().ticks("snow_duration")));
        }
    }

    private void clearSlow(Snow state) {
        NamespacedKey key = state.slowKey;
        state.slowKey = null;
        if (key != null) {
            ctx().removeModifier(state.enemy, key);
        }
    }

    /** The mound of snow around the victim's legs: two boxes turned 45° apart (an octagon), one layer higher each. */
    private void raiseMound(Snow state) {
        LivingEntity enemy = state.enemy;
        float width = (float) enemy.getWidth() + MOUND_MARGIN;
        float height = Math.min((float) enemy.getHeight() * 0.6f, LAYER_HEIGHT * state.layers);
        Location feet = Displays.unrotated(enemy.getLocation());
        if (state.mound.isEmpty()) {
            for (int i = 0; i < 2 && roomFor(1); i++) {
                BlockDisplay piece = remember(Displays.block(ctx(), player(), feet, SNOW.createBlockData(),
                        moundPose(width, 0.01f, i * 45f), 0));
                piece.setTeleportDuration(1);
                state.mound.add(piece);
            }
        }
        for (int i = 0; i < state.mound.size(); i++) {
            BlockDisplay piece = state.mound.get(i);
            Transformation pose = moundPose(width, height, i * 45f);
            Displays.animateNextTick(ctx(), player(), piece, pose, MOUND_GROW_TICKS);
        }
        applyHidden(state, true);
    }

    /** A box of snow standing on the victim's feet, turned {@code yaw} degrees about the vertical. */
    static Transformation moundPose(float width, float height, float yaw) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
        Vector3f translation = rotation.transform(new Vector3f(-width / 2, 0, -width / 2)).add(0, 0.005f, 0);
        return new Transformation(translation, rotation, new Vector3f(width, height, width), new Quaternionf());
    }

    private void meltMound(Snow state) {
        for (BlockDisplay piece : state.mound) {
            if (piece.isValid()) {
                Displays.shrinkAway(ctx(), player(), piece, 6);
            }
        }
        state.mound.clear();
    }

    /** A layer lands: a white puff on the victim and the crunch of packed snow, higher with every layer. */
    private void packFx(LivingEntity enemy, int layers, int max) {
        Location body = Fx.body(enemy);
        World world = body.getWorld();
        if (!isHiddenFromOwner(enemy)) {
            world.spawnParticle(Particle.ITEM_SNOWBALL, body, Vfx.n(ctx(), 8), 0.25, 0.35, 0.25, 0.05);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 6), 0.3, 0.4, 0.3, 0, POWDER);
            Vfx.ring(ctx(), enemy.getLocation().add(0, 0.1 + LAYER_HEIGHT * layers, 0), enemy.getWidth() * 0.5 + 0.3,
                    10, ThreadLocalRandom.current().nextDouble(TAU), Particle.DUST, SNOW_DUST, false);
        }
        Fx.sound(body, Sound.BLOCK_SNOW_PLACE, 0.9f, 0.8f + 0.2f * layers + Fx.jitter(0.05f));
        Fx.sound(body, Sound.BLOCK_POWDER_SNOW_STEP, 0.7f, 1.1f + 0.15f * layers);
        player().sendActionBar(Text.mm("<" + COLOR_TAG + ">❄ 눈 범벅 <white>" + meter(layers, max) + "</white></"
                + COLOR_TAG + ">"));
    }

    /** A frozen or immune enemy shakes the snow off: a little puff, nothing sticks. */
    private void shed(LivingEntity enemy) {
        if (isHiddenFromOwner(enemy)) {
            return;
        }
        Location body = Fx.body(enemy);
        Vfx.cloud(ctx(), body, Particle.SNOWFLAKE, 5, 0.3, 0.03, null, false);
        Fx.sound(body, Sound.BLOCK_SNOW_HIT, 0.6f, 1.4f + Fx.jitter(0.1f));
    }

    private static String meter(int filled, int max) {
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < max; i++) {
            bar.append(i < filled ? '❄' : '·');
        }
        return bar.toString();
    }

    // ------------------------------------------------------------------ passive: the freeze

    /**
     * The last layer: the victim becomes a snowman, frozen stiff (no moving, no jumping) and hurt; it is immune to
     * new snow for a while, so it cannot be kept frozen.
     */
    private void freeze(Snow state) {
        LivingEntity enemy = state.enemy;
        Player owner = player();
        long now = ctx().currentTick();
        long ticks = Math.max(1, params().ticks("freeze_duration"));
        state.frozen = true;
        state.layers = 0;
        state.immuneUntil = now + Math.max(ticks, params().ticks("freeze_immunity"));
        clearSlow(state);
        meltMound(state);
        ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -1.0, Operation.MULTIPLY_SCALAR_1, ticks);
        ctx().addModifier(owner, enemy, Attribute.JUMP_STRENGTH, -1.0, Operation.MULTIPLY_SCALAR_1, ticks);
        // Vanilla frost overlay, below the fully frozen threshold so it never deals vanilla freeze damage.
        int overlay = (int) Math.min(enemy.getMaxFreezeTicks() - 1L, ticks * 2 + 20);
        if (enemy.getFreezeTicks() < overlay) {
            enemy.setFreezeTicks(overlay);
        }
        buildSnowman(state);
        freezeFx(enemy);
        Combat.hit(ctx(), owner, enemy, params().num("freeze_damage"));
        owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>☃ 눈사람 완성!</b></" + COLOR_TAG + "> <gray>"
                + AbilityInfo.formatNumber(ticks / 20.0) + "초 빙결</gray>"));
        ctx().runLater(owner, ticks, () -> thaw(state));
    }

    /**
     * The victim's snowman: a big snow ball over its legs and a smaller one around its head (each a pair of cubes in
     * different orientations), coal eyes and a carrot nose pointing the way the victim faced.
     */
    private void buildSnowman(Snow state) {
        LivingEntity enemy = state.enemy;
        Player owner = player();
        float yaw = enemy.getLocation().getYaw();
        double height = enemy.getHeight();
        Location feet = Displays.unrotated(enemy.getLocation());
        long lifetime = params().ticks("freeze_duration") + 10;
        Vector forward = Motion.horizontalFacing(enemy.getLocation());
        if (forward.lengthSquared() < 1.0E-6) {
            forward = new Vector(0, 0, 1);
        }
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        double headY = height * 0.82;
        float headSize = HEAD_SIZE * (float) Math.max(0.6, height / 1.8);
        float baseSize = BASE_SIZE * (float) Math.max(0.6, height / 1.8);
        // Body: two cubes turned apart, from the knees to the chest.
        shellBlock(state, feet, new Vector(0, height * 0.36, 0), baseSize, BALL_ORIENTATIONS[0], lifetime);
        shellBlock(state, feet, new Vector(0, height * 0.36, 0), baseSize, BALL_ORIENTATIONS[1], lifetime);
        shellBlock(state, feet, new Vector(0, headY, 0), headSize, new Quaternionf().rotateY(
                (float) Math.toRadians(45 - yaw)), lifetime);
        // The carrot nose points out of the face, the way the victim was looking.
        Vector nose = forward.clone().multiply(headSize * 0.62).setY(headY);
        if (roomFor(1)) {
            ItemDisplay carrot = remember(Displays.item(ctx(), owner, feet.clone().add(nose),
                    new ItemStack(NOSE_ITEM), nosePose(0.05f, yaw), lifetime));
            carrot.setTeleportDuration(1);
            Displays.animateNextTick(ctx(), owner, carrot, nosePose(0.5f, yaw), SHELL_GROW_TICKS);
            state.shell.add(carrot);
            state.shellOffsets.add(nose);
        }
        for (int side = -1; side <= 1; side += 2) {
            if (!roomFor(1)) {
                break;
            }
            Vector eye = forward.clone().multiply(headSize * 0.52).add(right.clone().multiply(side * headSize * 0.2))
                    .setY(headY + headSize * 0.14);
            ItemDisplay coal = remember(Displays.item(ctx(), owner, feet.clone().add(eye), new ItemStack(EYE_ITEM),
                    Displays.centered(0, 0, 0, 0.02f, 0.02f, 0.02f, yaw, 0, 0), lifetime));
            coal.setTeleportDuration(1);
            Displays.animateNextTick(ctx(), owner, coal, Displays.centered(0, 0, 0, 0.2f, 0.2f, 0.2f, yaw, 0, 0),
                    SHELL_GROW_TICKS);
            state.shell.add(coal);
            state.shellOffsets.add(eye);
        }
        applyHidden(state, true);
    }

    private void shellBlock(Snow state, Location feet, Vector offset, float size, Quaternionf rotation,
                            long lifetime) {
        if (!roomFor(1)) {
            return;
        }
        BlockDisplay piece = remember(Displays.block(ctx(), player(), feet.clone().add(offset),
                SNOW.createBlockData(), cubePose(0.05f, rotation), lifetime));
        piece.setTeleportDuration(1);
        Displays.animateNextTick(ctx(), player(), piece, cubePose(size, rotation), SHELL_GROW_TICKS);
        state.shell.add(piece);
        state.shellOffsets.add(offset);
    }

    /**
     * The carrot sprite (its tip at the sprite's lower left) rolled so the tip points down, tipped forward so it
     * points along +Z, then turned to {@code yaw}: the tip points the way the victim faced.
     */
    static Transformation nosePose(float scale, float yaw) {
        return Displays.centered(0, 0, 0, scale, scale, scale, yaw, -90, 45);
    }

    /** The direction of the carrot's tip in the sprite's own coordinates. */
    static Vector3f noseTip() {
        return new Vector3f(-1, -1, 0).normalize();
    }

    private void freezeFx(LivingEntity enemy) {
        Location body = Fx.body(enemy);
        World world = body.getWorld();
        boolean visible = !isHiddenFromOwner(enemy);
        if (visible) {
            Vfx.dot(body, Particle.FLASH, PALETTE.core(), false);
            world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 20), 0.4, 0.6, 0.4, 0.1, SNOW.createBlockData());
            world.spawnParticle(Particle.SNOWFLAKE, body, Vfx.n(ctx(), 16), 0.4, 0.6, 0.4, 0.06);
            Vfx.shockwave(ctx(), player(), enemy.getLocation().add(0, 0.1, 0), 0.3, 1.8, 5,
                    Particle.DUST_COLOR_TRANSITION, POWDER, false);
            Vfx.helix(ctx(), enemy.getLocation(), 0.75, enemy.getHeight() + 0.3, 1.5, 2, 10, 0, Particle.SNOWFLAKE,
                    null, false);
        }
        Fx.sound(body, Sound.BLOCK_POWDER_SNOW_PLACE, 1.2f, 0.8f);
        Fx.sound(body, Sound.ENTITY_SNOW_GOLEM_HURT, 0.9f, 0.7f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.0f, 1.0f);
        Fx.sound(body, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.7f + Fx.jitter(0.1f));
    }

    /** The freeze ends: the snowman crumbles into a puff of snow. */
    private void thaw(Snow state) {
        state.frozen = false;
        LivingEntity enemy = state.enemy;
        for (Display piece : state.shell) {
            if (!piece.isValid()) {
                continue;
            }
            if (piece instanceof BlockDisplay) {
                Displays.shrinkAway(ctx(), player(), piece, 4);
            } else {
                piece.remove();
            }
        }
        state.shell.clear();
        state.shellOffsets.clear();
        if (!enemy.isValid() || enemy.isDead() || isHiddenFromOwner(enemy)) {
            return;
        }
        Location body = Fx.body(enemy);
        World world = body.getWorld();
        world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 16), 0.4, 0.6, 0.4, 0.15, SNOW.createBlockData());
        world.spawnParticle(Particle.ITEM_SNOWBALL, body, Vfx.n(ctx(), 10), 0.35, 0.5, 0.35, 0.1);
        Vfx.dot(body.clone().add(0, 0.5, 0), Particle.DUST, CARROT_DUST, false);
        Fx.sound(body, Sound.BLOCK_SNOW_BREAK, 1.0f, 0.8f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_SNOW_GOLEM_DEATH, 0.5f, 1.3f + Fx.jitter(0.1f));
    }

    // ------------------------------------------------------------------ passive: upkeep

    private void ensureSnowTicking() {
        if (snowTicking) {
            return;
        }
        snowTicking = true;
        Loop.repeat(ctx(), player(), 1, 1, Long.MAX_VALUE, this::tickSnow, () -> snowTicking = false);
    }

    /** Every tick: the mounds and snowmen ride along with their victims, old snow melts away. */
    private boolean tickSnow(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        Iterator<Snow> it = snow.values().iterator();
        while (it.hasNext()) {
            Snow state = it.next();
            LivingEntity enemy = state.enemy;
            if (!Combat.isTargetable(ctx(), owner, enemy) || !enemy.getWorld().equals(owner.getWorld())) {
                clearSlow(state);
                meltMound(state);
                for (Display piece : state.shell) {
                    piece.remove();
                }
                state.shell.clear();
                state.shellOffsets.clear();
                it.remove();
                continue;
            }
            if (state.layers > 0 && now >= state.expires) {
                state.layers = 0;
                clearSlow(state);
                meltMound(state);
                Fx.sound(enemy.getLocation(), Sound.BLOCK_SNOW_STEP, 0.5f, 0.7f);
            }
            follow(state, tick);
            if (state.layers == 0 && !state.frozen && now >= state.immuneUntil
                    && now - state.lastMelee >= params().ticks("melee_stack_cooldown")) {
                it.remove();
            }
        }
        return !snow.isEmpty();
    }

    /** Keeps the mound and the snowman on the victim, hidden while the victim is hidden from the snowman. */
    private void follow(Snow state, long tick) {
        LivingEntity enemy = state.enemy;
        Location feet = Displays.unrotated(enemy.getLocation());
        for (BlockDisplay piece : state.mound) {
            Displays.moveTo(piece, feet);
        }
        for (int i = 0; i < state.shell.size(); i++) {
            Displays.moveTo(state.shell.get(i), feet.clone().add(state.shellOffsets.get(i)));
        }
        applyHidden(state, false);
        if (!state.hidden && (state.layers > 0 || state.frozen) && tick % FLAKE_PERIOD == 0) {
            Location above = feet.clone().add(0, enemy.getHeight() + 0.5, 0);
            Vfx.dot(Vfx.scatter(above, 0.4), Particle.SNOWFLAKE, null, false);
            if (state.frozen) {
                Vfx.dot(Vfx.scatter(feet.clone().add(0, 0.1, 0), 0.7), Particle.DUST, SNOW_DUST, false);
            }
        }
    }

    /** A victim hidden from the snowman (stealth) must not be given away by the snow on it. */
    private void applyHidden(Snow state, boolean force) {
        boolean hidden = isHiddenFromOwner(state.enemy);
        if (hidden == state.hidden && !force) {
            return;
        }
        state.hidden = hidden;
        float range = hidden ? 0f : 1.5f;
        for (BlockDisplay piece : state.mound) {
            piece.setViewRange(range);
        }
        for (Display piece : state.shell) {
            piece.setViewRange(range);
        }
    }

    private boolean isHiddenFromOwner(LivingEntity enemy) {
        return enemy instanceof Player victim && !player().canSee(victim);
    }

    /** Snow layers on {@code enemy} right now (0 when none, also while it is frozen). */
    int layersOn(LivingEntity enemy) {
        Snow state = snow.get(enemy.getUniqueId());
        return state == null ? 0 : state.layers;
    }

    // ------------------------------------------------------------------ active: snowball barrage

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        int count = Math.max(1, params().integer("ball_count"));
        long interval = Math.max(1, params().ticks("ball_interval"));
        Barrage barrage = new Barrage(count);
        Fx.sound(owner.getEyeLocation(), Sound.ENTITY_SNOW_GOLEM_AMBIENT, 0.7f, 1.2f + Fx.jitter(0.1f));
        Loop.repeat(ctx(), owner, 0, interval, count, barrage::throwNext, barrage::done);
        return ActivationResult.success();
    }

    /** One barrage: counts the throws and the hits for the summary. */
    private final class Barrage {
        private final int count;
        private int thrown;
        private int landed;
        private int flying;
        private boolean allThrown;

        Barrage(int count) {
            this.count = count;
        }

        boolean throwNext(long run) {
            Player owner = player();
            Location eye = owner.getEyeLocation();
            Vector direction = eye.getDirection();
            if (direction.lengthSquared() < 1.0E-6) {
                return false;
            }
            thrown++;
            flying++;
            Ball ball = new Ball(this, eye, direction.normalize(), thrown == count);
            long maxTicks = (long) Math.ceil(Math.max(1, params().num("ball_range")) / BALL_SPEED) + 2;
            Loop.repeat(ctx(), owner, 0, 1, maxTicks, ball, ball::expire);
            Location hand = eye.clone().add(direction.clone().multiply(0.6)).subtract(0, 0.25, 0);
            Vfx.cloud(ctx(), hand, Particle.ITEM_SNOWBALL, 3, 0.08, 0.05, null, false);
            Fx.sound(hand, Sound.ENTITY_SNOWBALL_THROW, 0.8f, 0.6f + 0.08f * thrown + Fx.jitter(0.05f));
            if (thrown == count) {
                Fx.sound(hand, Sound.ENTITY_SNOW_GOLEM_SHOOT, 0.8f, 0.9f + Fx.jitter(0.05f));
            }
            return true;
        }

        void done() {
            allThrown = true;
            summarize();
        }

        void ballLanded(boolean hit) {
            flying--;
            if (hit) {
                landed++;
            }
            summarize();
        }

        private void summarize() {
            if (allThrown && flying <= 0 && landed > 0 && player().isOnline()) {
                player().sendActionBar(Text.mm("<" + COLOR_TAG + ">❄ 눈덩이 연사 <white>" + landed + "/" + thrown
                        + " 적중</white></" + COLOR_TAG + ">"));
            }
        }
    }

    /** A thrown snowball: a spinning snowball sprite that flies on a gentle arc until it hits an enemy or a block. */
    private final class Ball implements LongPredicate {

        private final Barrage barrage;
        private final World world;
        private final Location position;
        private final Vector velocity;
        private final boolean last;
        private final @Nullable ItemDisplay sprite;
        private double remaining;
        private boolean finished;

        Ball(Barrage barrage, Location eye, Vector direction, boolean last) {
            this.barrage = barrage;
            this.world = eye.getWorld();
            this.position = eye.clone().add(direction.clone().multiply(0.4)).subtract(0, 0.12, 0);
            this.velocity = direction.clone().multiply(BALL_SPEED);
            this.last = last;
            this.remaining = Math.max(1, params().num("ball_range"));
            long lifetime = (long) Math.ceil(remaining / BALL_SPEED) + 6;
            if (roomFor(1)) {
                float scale = last ? BALL_SCALE * 1.35f : BALL_SCALE;
                ItemDisplay display = remember(Displays.item(ctx(), player(), position, new ItemStack(BALL_ITEM),
                        Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, 0), lifetime));
                display.setBillboard(Display.Billboard.CENTER);
                display.setTeleportDuration(1);
                this.sprite = display;
            } else {
                this.sprite = null;
            }
        }

        @Override
        public boolean test(long tick) {
            double speed = velocity.length();
            double step = Math.min(speed, remaining);
            if (step <= 1.0E-3) {
                splat(position, null);
                return false;
            }
            Vector direction = velocity.clone().multiply(1 / speed);
            RayTraceResult wall = world.rayTraceBlocks(position, direction, step, FluidCollisionMode.NEVER, true);
            double travel = wall == null ? step : wall.getHitPosition().distance(position.toVector());
            int samples = Math.max(1, (int) Math.ceil(travel / SAMPLE_STEP));
            for (int i = 1; i <= samples; i++) {
                Location probe = position.clone().add(direction.clone().multiply(travel * i / samples));
                List<LivingEntity> enemies = ctx().enemiesNear(player(), probe, BALL_HITBOX);
                if (!enemies.isEmpty()) {
                    splat(probe, enemies.getFirst());
                    return false;
                }
            }
            if (wall != null) {
                splat(wall.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.1)), null);
                return false;
            }
            Location from = position.clone();
            position.add(direction.clone().multiply(step));
            remaining -= step;
            velocity.setY(velocity.getY() - BALL_GRAVITY);
            fly(from, tick);
            if (remaining <= 1.0E-3) {
                splat(position, null);
                return false;
            }
            return true;
        }

        private void fly(Location from, long tick) {
            ItemDisplay display = sprite;
            if (display != null && display.isValid()) {
                Displays.moveTo(display, position);
                float scale = last ? BALL_SCALE * 1.35f : BALL_SCALE;
                Displays.animate(display, Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, 40f * (tick + 1)), 1);
            }
            if (tick % 2 == 0) {
                Vfx.dot(from, Particle.SNOWFLAKE, null, false);
            }
            Vfx.dot(from, Particle.DUST, SNOW_DUST, false);
        }

        /** Runs when the flight loop ran out without a splat (it never does in practice: the range ends it). */
        void expire() {
            if (!finished) {
                splat(position, null);
            }
        }

        private void splat(Location at, @Nullable LivingEntity target) {
            if (finished) {
                return;
            }
            finished = true;
            ItemDisplay display = sprite;
            if (display != null) {
                display.remove();
            }
            world.spawnParticle(Particle.ITEM_SNOWBALL, at, Vfx.n(ctx(), last ? 14 : 8), 0.15, 0.15, 0.15, 0.08);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, Vfx.n(ctx(), 5), 0.2, 0.2, 0.2, 0, POWDER);
            Fx.sound(at, Sound.BLOCK_SNOW_BREAK, 0.7f, 1.2f + Fx.jitter(0.15f));
            boolean hit = false;
            if (target != null) {
                Player owner = player();
                Combat.hit(ctx(), owner, target, params().num("ball_damage"));
                if (Combat.isTargetable(ctx(), owner, target)) {
                    hit = true;
                    Vector push = Motion.horizontal(velocity).multiply(BALL_PUSH).setY(0.08);
                    Motion.push(target, push, MAX_SPEED);
                    addLayers(target, 1);
                    Vfx.dot(at, Particle.FLASH, PALETTE.core(), false);
                    Fx.sound(at, Sound.ENTITY_SNOW_GOLEM_HURT, 0.5f, 1.6f + Fx.jitter(0.1f));
                }
            }
            barrage.ballLanded(hit);
        }
    }

    // ------------------------------------------------------------------ ultimate: giant snowball

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location start = owner.getLocation();
        Vector direction = Motion.horizontalFacing(start);
        if (direction.lengthSquared() < 1.0E-6) {
            return ActivationResult.fail("굴릴 방향이 없습니다");
        }
        Location spot = start.clone().add(direction.clone().multiply(1.6));
        if (!Spots.hasGroundBelow(start.getWorld(), spot.clone().add(0, 0.5, 0))) {
            return ActivationResult.fail("눈덩이를 굴릴 땅이 없습니다");
        }
        new GiantSnowball(owner, start, direction).begin();
        return ActivationResult.success();
    }

    /**
     * The giant snowball: packed during the wind-up in front of the snowman, then rolled along the ground, growing,
     * swallowing enemies it touches and carrying them along until it hits a wall or reaches its end and bursts.
     */
    private final class GiantSnowball implements LongPredicate {

        private final Player owner;
        private final World world;
        private final Vector direction;
        private final Vector axis;
        private final long windup;
        private final double distance;
        private final double startRadius;
        private final double maxRadius;
        private final List<LivingEntity> carried = new ArrayList<>();
        private final Set<UUID> struck = new HashSet<>();
        private final List<BlockDisplay> ball = new ArrayList<>();
        private final Location ground;
        private double travelled;
        private double radius;
        private float roll;
        private boolean burst;

        GiantSnowball(Player owner, Location start, Vector direction) {
            this.owner = owner;
            this.world = start.getWorld();
            this.direction = direction.clone();
            this.axis = new Vector(direction.getZ(), 0, -direction.getX());
            this.windup = Math.max(1, params().ticks("ult_windup"));
            this.startRadius = Math.max(0.5, params().num("ult_start_radius"));
            this.maxRadius = Math.max(startRadius, params().num("ult_max_radius"));
            this.ground = start.clone().add(direction.clone().multiply(1.2 + startRadius));
            this.ground.setYaw(0);
            this.ground.setPitch(0);
            Spots.snapToGround(world, ground, STEP_DOWN);
            this.distance = Spots.arenaReach(ctx(), ground, direction, Math.max(1, params().num("ult_distance")));
            this.radius = 0.2;
        }

        void begin() {
            long lifetime = windup + (long) Math.ceil(distance / ROLL_SPEED) + 30;
            for (Quaternionf orientation : BALL_ORIENTATIONS) {
                if (!roomFor(1)) {
                    break;
                }
                BlockDisplay piece = remember(Displays.block(ctx(), owner, center(), SNOW.createBlockData(),
                        cubePose(0.1f, orientation), lifetime));
                piece.setTeleportDuration(1);
                piece.setViewRange(2.0f);
                ball.add(piece);
            }
            Fx.sound(ground, Sound.ENTITY_SNOW_GOLEM_AMBIENT, 1.2f, 0.6f);
            Fx.sound(ground, Sound.BLOCK_POWDER_SNOW_PLACE, 1.0f, 0.6f + Fx.jitter(0.05f));
            Fx.sound(ground, Sound.ITEM_BUCKET_FILL_POWDER_SNOW, 1.0f, 0.8f);
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>☃ 거대 눈덩이</b></" + COLOR_TAG + "> <gray>뭉치는 중...</gray>"));
            Loop.repeat(ctx(), owner, 0, 1, windup + (long) Math.ceil(distance / ROLL_SPEED) + 2, this, this::burst);
        }

        private Location center() {
            return ground.clone().add(0, radius, 0);
        }

        @Override
        public boolean test(long tick) {
            if (!owner.getWorld().equals(world)) {
                return false;
            }
            if (tick < windup) {
                pack(tick);
                return true;
            }
            if (tick == windup) {
                launchFx();
            }
            return rollOn(tick - windup);
        }

        /** The wind-up: the ball is packed up to its starting size, and its lane is marked on the ground. */
        private void pack(long tick) {
            double progress = (tick + 1) / (double) windup;
            radius = 0.2 + (startRadius - 0.2) * progress;
            roll += 0.25f;
            pose(1);
            Location from = ground.clone().add(0, 0.1, 0);
            Location to = from.clone().add(direction.clone().multiply(distance * progress));
            Vfx.line(ctx(), from, to, 2, Particle.DUST_COLOR_TRANSITION, SHADOW, true);
            if (tick % 2 == 0) {
                Vector side = axis.clone().multiply(maxRadius);
                Vfx.line(ctx(), from.clone().add(side), to.clone().add(side), 0.8, Particle.SNOWFLAKE, null, true);
                Vfx.line(ctx(), from.clone().subtract(side), to.clone().subtract(side), 0.8, Particle.SNOWFLAKE, null,
                        true);
                Fx.sound(ground, Sound.BLOCK_SNOW_STEP, 0.9f, 0.6f + 0.6f * (float) progress);
            }
            Vfx.cloud(ctx(), center(), Particle.ITEM_SNOWBALL, 4, radius * 0.6, 0.06, null, true);
        }

        private void launchFx() {
            Location at = center();
            Vfx.cloud(ctx(), at, Particle.SNOWFLAKE, 24, 0.5, 0.15, null, true);
            Vfx.dot(at, Particle.GUST_EMITTER_SMALL, null, true);
            Fx.sound(at, Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, 0.6f);
            Fx.sound(at, Sound.BLOCK_SNOW_BREAK, 1.2f, 0.5f);
        }

        /** One tick of rolling. False once the ball bursts. */
        private boolean rollOn(long tick) {
            if (travelled >= distance - 1.0E-6) {
                return false;
            }
            double step = Math.min(ROLL_SPEED, distance - travelled);
            Location next = ground.clone().add(direction.clone().multiply(step));
            if (blocked(step) || !groundAt(next)) {
                return false;
            }
            ground.setX(next.getX());
            ground.setY(next.getY());
            ground.setZ(next.getZ());
            travelled += step;
            radius = startRadius + (maxRadius - startRadius) * Math.min(1.0, travelled / Math.max(1.0, distance));
            roll += (float) (step / Math.max(0.3, radius));
            pose(1);
            sweep();
            carry();
            track(tick);
            return true;
        }

        /** True if a wall stands in front of the ball (a one-block step is rolled over). */
        private boolean blocked(double step) {
            double reach = step + radius + WALL_LOOKAHEAD;
            for (double height : new double[] {1.15, Math.max(1.15, radius * 1.4)}) {
                Location from = ground.clone().add(0, height, 0);
                RayTraceResult wall = world.rayTraceBlocks(from, direction, reach, FluidCollisionMode.NEVER, true);
                if (wall != null) {
                    return true;
                }
            }
            return false;
        }

        /** Moves {@code next} onto the ground there (up one step, or down a slope); false over a drop or a hazard. */
        private boolean groundAt(Location next) {
            Location probe = next.clone().add(0, STEP_UP, 0);
            if (!Spots.hasGroundBelow(world, probe) || !ctx().isInsideArena(next)) {
                return false;
            }
            double before = probe.getY();
            Spots.snapToGround(world, probe, STEP_UP + STEP_DOWN);
            if (probe.getY() >= before - 1.0E-6) {
                return false;
            }
            next.setY(probe.getY());
            return true;
        }

        /** Every enemy the ball touches is hurt once and rolled up inside it. */
        private void sweep() {
            Location at = center();
            for (LivingEntity enemy : ctx().enemiesNear(owner, at, radius + 0.2)) {
                if (!struck.add(enemy.getUniqueId())) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, params().num("ult_pickup_damage"));
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                carried.add(enemy);
                Location body = Fx.body(enemy);
                if (!isHiddenFromOwner(enemy)) {
                    world.spawnParticle(Particle.BLOCK, body, Vfx.n(ctx(), 14), 0.4, 0.5, 0.4, 0.1,
                            SNOW.createBlockData(), true);
                    Vfx.cloud(ctx(), body, Particle.SNOWFLAKE, 10, 0.4, 0.08, null, true);
                }
                Fx.sound(body, Sound.BLOCK_POWDER_SNOW_FALL, 1.2f, 0.7f + Fx.jitter(0.05f));
                Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 0.7f);
            }
        }

        /** Keeps the rolled-up enemies inside the ball, on the ground under its middle; one stuck behind slips out. */
        private void carry() {
            Location at = ground.clone();
            for (int i = carried.size() - 1; i >= 0; i--) {
                LivingEntity enemy = carried.get(i);
                if (!Combat.isTargetable(ctx(), owner, enemy) || !enemy.getWorld().equals(world)) {
                    carried.remove(i);
                    continue;
                }
                double angle = i * 2.4;
                Location spot = at.clone().add(Math.cos(angle) * 0.35 * Math.min(1, i), 0,
                        Math.sin(angle) * 0.35 * Math.min(1, i));
                Vector offset = spot.toVector().subtract(enemy.getLocation().toVector());
                double leash = CARRY_LEASH + radius + (enemy instanceof Player rider ? ROLL_SPEED * lagTicks(rider) : 0);
                if (offset.length() > leash) {
                    carried.remove(i);
                    continue;
                }
                Vector velocity = direction.clone().multiply(ROLL_SPEED).add(offset.multiply(CARRY_PULL));
                velocity.setY(Math.max(0.0, Math.min(0.25, velocity.getY())));
                enemy.setVelocity(Motion.clamp(velocity, MAX_SPEED));
                enemy.setFallDistance(0);
            }
        }

        /** The powdery track behind the ball and snow flying off it. */
        private void track(long tick) {
            Location back = ground.clone().add(direction.clone().multiply(-radius * 0.6)).add(0, 0.06, 0);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Location dot = back.clone().add(axis.clone().multiply(random.nextDouble(-radius, radius) * 0.8));
                Vfx.dot(dot, Particle.DUST, SNOW_DUST, true);
            }
            Location top = center().add(0, radius * 0.8, 0);
            world.spawnParticle(Particle.SNOWFLAKE, top, Vfx.n(ctx(), 3), radius * 0.4, 0.1, radius * 0.4, 0.04, null,
                    true);
            world.spawnParticle(Particle.ITEM_SNOWBALL, back.clone().add(0, 0.3, 0), Vfx.n(ctx(), 2), 0.3, 0.1, 0.3,
                    0.08, null, false);
            if (tick % 3 == 0) {
                Fx.sound(ground, Sound.BLOCK_SNOW_STEP, 1.2f, 0.5f + Fx.jitter(0.05f));
                Fx.sound(ground, Sound.BLOCK_POWDER_SNOW_STEP, 0.9f, 0.6f + Fx.jitter(0.05f));
            }
        }

        /** Tumbles the ball's cubes around the rolling axis at the current size. */
        private void pose(int ticks) {
            Location at = center();
            Quaternionf tumble = new Quaternionf().rotateAxis(roll, (float) axis.getX(), 0, (float) axis.getZ());
            for (int i = 0; i < ball.size(); i++) {
                BlockDisplay piece = ball.get(i);
                if (!piece.isValid()) {
                    continue;
                }
                Displays.moveTo(piece, at);
                Quaternionf rotation = new Quaternionf(tumble).mul(BALL_ORIENTATIONS[i]);
                Displays.animate(piece, cubePose((float) radius * BALL_EDGE, rotation), ticks);
            }
        }

        /** The ball bursts: everyone inside and around is hurt, thrown outwards and covered in snow. */
        void burst() {
            if (burst) {
                return;
            }
            burst = true;
            Location at = center();
            double burstRadius = Math.max(radius, params().num("ult_burst_radius"));
            Set<LivingEntity> victims = new HashSet<>(carried);
            victims.addAll(ctx().enemiesNear(owner, at, burstRadius));
            double damage = params().num("ult_burst_damage");
            int stacks = Math.max(0, params().integer("ult_burst_stacks"));
            int hits = 0;
            for (LivingEntity enemy : victims) {
                Combat.hit(ctx(), owner, enemy, damage);
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                hits++;
                Vector away = Motion.horizontal(enemy.getLocation().toVector().subtract(at.toVector()));
                if (away.lengthSquared() < 1.0E-6) {
                    away = direction.clone();
                }
                Motion.push(enemy, away.multiply(BURST_PUSH).setY(BURST_LIFT), MAX_SPEED);
                addLayers(enemy, stacks);
            }
            carried.clear();
            burstFx(at, burstRadius);
            if (hits > 0 && owner.isOnline()) {
                owner.sendActionBar(Text.mm("<" + COLOR_TAG + "><b>☃ 거대 눈덩이</b></" + COLOR_TAG + "> <white>" + hits
                        + "명 적중!</white>"));
            }
        }

        private void burstFx(Location at, double burstRadius) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (BlockDisplay piece : ball) {
                if (!piece.isValid()) {
                    continue;
                }
                Vector out = new Vector(random.nextGaussian(), 0.6 + random.nextDouble(), random.nextGaussian());
                out.normalize().multiply(burstRadius * 0.7);
                piece.setTeleportDuration(6);
                Displays.moveTo(piece, at.clone().add(out));
                Displays.animate(piece, cubePose((float) radius * 0.6f, new Quaternionf().rotateXYZ(
                        random.nextFloat() * 6.28f, random.nextFloat() * 6.28f, random.nextFloat() * 6.28f)), 6);
                ctx().runLater(owner, 6, () -> {
                    if (piece.isValid()) {
                        Displays.shrinkAway(ctx(), owner, piece, 5);
                    }
                });
            }
            BlockData snowData = SNOW.createBlockData();
            Vfx.dot(at, Particle.FLASH, PALETTE.core(), true);
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 60), radius * 0.6, radius * 0.6, radius * 0.6, 0.3,
                    snowData, true);
            world.spawnParticle(Particle.BLOCK, at, Vfx.n(ctx(), 20), radius * 0.6, radius * 0.6, radius * 0.6, 0.3,
                    POWDER_BLOCK.createBlockData(), true);
            Vfx.sphere(ctx(), at, radius * 0.9, 40, Particle.SNOWFLAKE, null, true);
            Vfx.cloud(ctx(), at, Particle.ITEM_SNOWBALL, 40, radius * 0.5, 0.25, null, true);
            Vfx.shockwave(ctx(), owner, ground.clone().add(0, 0.12, 0), 0.5, burstRadius, 7,
                    Particle.DUST_COLOR_TRANSITION, POWDER, true);
            Loop.repeat(ctx(), owner, 2, 2, 8, run -> {
                double r = burstRadius * (0.5 + 0.06 * run);
                Vfx.ring(ctx(), ground.clone().add(0, 0.4 + 0.25 * run, 0), r, (int) Math.max(12, r * 4), run * 0.5,
                        Particle.SNOWFLAKE, null, true);
                if (run % 2 == 0) {
                    Vfx.dot(Vfx.scatter(ground.clone().add(0, 0.1, 0), burstRadius), Particle.DUST, COAL_DUST, false);
                }
                return true;
            });
            Fx.sound(at, Sound.BLOCK_SNOW_BREAK, 1.6f, 0.5f);
            Fx.sound(at, Sound.BLOCK_POWDER_SNOW_BREAK, 1.5f, 0.6f);
            Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.4f + Fx.jitter(0.05f));
            Fx.sound(at, Sound.ENTITY_SNOW_GOLEM_DEATH, 1.0f, 0.6f);
        }
    }

    /** Ticks the server sees a player's movement late: one round trip, capped. */
    private static long lagTicks(Player player) {
        return Math.min(MAX_LAG_TICKS, (long) Math.ceil(Math.max(0, player.getPing()) / 50.0));
    }

    // ------------------------------------------------------------------ display helpers

    /** A cube of edge {@code size} centered on the block display's origin, rotated around its center. */
    private static Transformation cubePose(float size, Quaternionf rotation) {
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    private boolean roomFor(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T remember(T display) {
        displays.add(display);
        return display;
    }
}
