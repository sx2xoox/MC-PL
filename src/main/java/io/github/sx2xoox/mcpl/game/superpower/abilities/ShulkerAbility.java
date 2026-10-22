package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
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
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 셜커 — the End city's sentry. Sneaking shuts its shell (much less damage, but rooted to the spot), the active
 * fires homing shulker bullets that zig-zag along the block axes and float whoever they hit, and the ultimate raises a
 * small fortress of shulker turrets that keep firing at everyone around.
 *
 * <p>Look: purpur purple and pale yellow. The shell is two purpur halves that close around the shulker and lift a
 * crack whenever it fires. A bullet is a little yellow froglight core spinning inside a purple glass cube, trailing
 * end rod sparks and purple dust; it turns sharp corners like the real thing. A floated enemy sheds sparkles under its
 * feet. Turrets rise out of a purple portal swirl, peek open to fire and sink back into the ground at the end.
 */
public final class ShulkerAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("shulker", "셜커", Material.SHULKER_SHELL)
            .color(TextColor.color(0xB383D6))
            .difficulty(2)
            .passive("껍질",
                    "땅에서 웅크리고 있으면 껍질을 닫아 받는 피해가 {shell_reduction}% 줄고 밀려나지 않지만, 그동안은 "
                            + "움직일 수 없습니다. 껍질 안에서도 유도탄은 쏠 수 있습니다.")
            .active("유도탄",
                    "셜커 탄 {bullet_count}발을 쏩니다. 탄은 조준한 적(없으면 앞쪽 {bullet_range}블록 안에서 보이는 "
                            + "가장 가까운 적)을 축을 따라 꺾어 가며 {bullet_life}초간 쫓아가, 맞은 적에게 "
                            + "{bullet_damage} 피해를 주고 {levitate}초간 공중에 띄웁니다. 벽에 막히면 터집니다.",
                    8)
            .ultimate("셜커 요새",
                    "주변에 셜커 포대 {turret_count}개를 세웁니다. {ult_duration}초간 각 포대가 {ult_interval}초마다 "
                            + "반경 {ult_range}블록 안의 가장 가까운 적에게 유도탄을 쏘아 {ult_damage} 피해를 주고 "
                            + "{ult_levitate}초간 띄웁니다. 적 한 명은 요새에 최대 {ult_max_levitate}초까지만 떠오릅니다.")
            .param("shell_reduction", 60)
            .param("bullet_count", 2)
            .param("bullet_range", 24)
            .param("bullet_damage", 3)
            .param("bullet_life", 5)
            .param("levitate", 2)
            .param("turret_count", 3)
            .param("ult_duration", 6)
            .param("ult_interval", 1.5)
            .param("ult_range", 14)
            .param("ult_damage", 2)
            .param("ult_levitate", 1)
            .param("ult_max_levitate", 3)
            .build();

    private static final String PURPLE = "#B383D6";
    private static final double TAU = Math.PI * 2;

    // ------------------------------------------------------------------ look
    /** Pale yellow bullet core, purpur purple, the deep violet of the End sky. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFF4B5, 0xA86FC9, 0x3E2350);
    private static final Material SHELL_BLOCK = Material.PURPUR_BLOCK;
    private static final Material SHELL_HIT = Material.PURPUR_PILLAR;
    private static final Material HEAD_BLOCK = Material.END_STONE;
    private static final Material BULLET_CORE = Material.OCHRE_FROGLIGHT;
    private static final Material BULLET_SHELL = Material.PURPLE_STAINED_GLASS;
    private static final int DISPLAY_BUDGET = 24;

    // ------------------------------------------------------------------ passive: shell
    /** Ticks of sneaking on the ground before the shell starts to close (a tap of sneak does nothing). */
    private static final long SHELL_DELAY = 2;
    private static final int SHELL_CLOSE_TICKS = 4;
    private static final int SHELL_OPEN_TICKS = 4;
    private static final float SHELL_GAP = 0.06f;
    private static final float PEEK_LIFT = 0.38f;
    private static final long PEEK_TICKS = 8;
    private static final long SHELL_FEEDBACK_COOLDOWN = 8;

    // ------------------------------------------------------------------ bullets
    private static final double BULLET_SPEED = 0.48;
    /** Within this distance the bullet stops zig-zagging and goes straight for the body. */
    private static final double FINAL_HOMING = 2.2;
    private static final double BULLET_RADIUS = 0.25;
    private static final long AXIS_MIN_TICKS = 5;
    private static final long AXIS_MAX_TICKS = 11;
    /** A lost bullet looks for a new target this close to itself. */
    private static final double REACQUIRE_RADIUS = 7;
    private static final float CORE_SIZE = 0.2f;
    private static final float GLASS_SIZE = 0.36f;
    private static final double MUZZLE_SIDE = 0.45;
    /** The active also locks onto visible enemies within this angle of the view when nothing is aimed at. */
    private static final double SEEK_CONE_COS = Math.cos(Math.toRadians(70));
    private static final long LOCK_RING_TICKS = 6;
    private static final long FLOAT_SPARKLE_PERIOD = 3;

    // ------------------------------------------------------------------ fortress
    private static final double TURRET_DISTANCE = 2.3;
    private static final float TURRET_BASE = 0.8f;
    private static final float TURRET_HEIGHT = 0.42f;
    private static final float TURRET_HEAD = 0.44f;
    private static final int TURRET_RISE_TICKS = 6;
    private static final int TURRET_SINK_TICKS = 6;
    private static final long TURRET_PEEK_TICKS = 8;
    private static final double TURRET_GROUND_SEARCH = 3.0;
    private static final long RANGE_RING_PERIOD = 20;

    private final Shell shell = new Shell();
    /** Every display this ability has alive (shell, bullets, turrets), for the display budget. */
    private final List<Display> displays = new ArrayList<>();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        shell.forget();
        displays.clear();
    }

    // ------------------------------------------------------------------ passive: shell

    @Override
    public void onTick(long tick) {
        shell.tick(player(), ctx().currentTick());
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0 || !shell.isShut(ctx().currentTick())) {
            return;
        }
        Tag<DamageType> bypass = DamageTypeTags.BYPASSES_INVULNERABILITY;
        if (bypass != null && bypass.isTagged(event.getDamageSource().getDamageType())) {
            return;
        }
        double reduction = Math.clamp(params().num("shell_reduction") / 100.0, 0.0, 1.0);
        event.setDamage(event.getDamage() * (1 - reduction));
        shell.struck(player());
    }

    /**
     * The shell: two purpur halves around the shulker. While shut it takes less damage, cannot move and is not
     * knocked back (attribute modifiers, removed the moment it opens or the ability is revoked).
     */
    private final class Shell {

        private long sneakingSince = -1;
        private long shutAt = -1;
        private boolean closing;
        private final List<NamespacedKey> modifiers = new ArrayList<>();
        private @Nullable BlockDisplay base;
        private @Nullable BlockDisplay lid;
        private long peekUntil = -1;
        private long lastFeedback = Long.MIN_VALUE / 2; // "/ 2": now - this must not overflow
        private @Nullable Location anchor;

        boolean isShut(long now) {
            return closing && now >= shutAt;
        }

        void tick(Player owner, long now) {
            boolean wanted = owner.isSneaking() && !owner.isInsideVehicle() && !owner.isGliding()
                    && (Motion.isGrounded(owner) || closing);
            if (!wanted) {
                sneakingSince = -1;
                if (closing) {
                    open(owner);
                }
                return;
            }
            if (sneakingSince < 0) {
                sneakingSince = now;
            }
            if (!closing && now - sneakingSince >= SHELL_DELAY) {
                close(owner, now);
            }
            if (closing) {
                follow(owner, now);
            }
        }

        private void close(Player owner, long now) {
            closing = true;
            shutAt = now + SHELL_CLOSE_TICKS;
            modifiers.add(ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -1, Operation.MULTIPLY_SCALAR_1,
                    0));
            modifiers.add(ctx().addModifier(owner, owner, Attribute.JUMP_STRENGTH, -1, Operation.MULTIPLY_SCALAR_1,
                    0));
            modifiers.add(ctx().addModifier(owner, owner, Attribute.KNOCKBACK_RESISTANCE, 1, Operation.ADD_NUMBER,
                    0));
            owner.setVelocity(new Vector(0, Math.min(0, owner.getVelocity().getY()), 0));

            Location at = anchor(owner);
            anchor = at;
            float width = width(owner);
            float half = half(owner);
            if (fits(2)) {
                base = Displays.block(ctx(), owner, at, SHELL_BLOCK.createBlockData(),
                        halfPose(width, 0.01f, 0, 0), 0);
                lid = Displays.block(ctx(), owner, at, SHELL_BLOCK.createBlockData(),
                        halfPose(width + 0.06f, 0.01f, half * 2 + 0.4f, 0), 0);
                displays.add(base);
                displays.add(lid);
                Displays.animateNextTick(ctx(), owner, base, halfPose(width, half, 0, 0), SHELL_CLOSE_TICKS);
                Displays.animateNextTick(ctx(), owner, lid, halfPose(width + 0.06f, half, half + SHELL_GAP, 0),
                        SHELL_CLOSE_TICKS);
            }
            Location chest = Fx.body(owner);
            Fx.sound(chest, Sound.ENTITY_SHULKER_CLOSE, 1.0f, 1.0f + Fx.jitter(0.08f));
            Fx.sound(chest, Sound.BLOCK_SHULKER_BOX_CLOSE, 0.6f, 0.8f + Fx.jitter(0.05f));
            Vfx.ring(ctx(), owner.getLocation().add(0, 0.1, 0), width * 0.8, 12, 0, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(0.9f), false);
            ctx().runLater(owner, SHELL_CLOSE_TICKS, () -> {
                if (closing && ctx().isAlive(owner)) {
                    Location feet = owner.getLocation().add(0, 0.05, 0);
                    Vfx.ring(ctx(), feet, width * 0.7, 10, 0.3, Particle.REVERSE_PORTAL, null, false);
                    Fx.sound(feet, Sound.BLOCK_STONE_PLACE, 0.5f, 1.4f + Fx.jitter(0.1f));
                    owner.sendActionBar(Text.mm("<" + PURPLE + ">껍질 닫힘</" + PURPLE + "> <gray>받는 피해 -"
                            + AbilityInfo.formatNumber(params().num("shell_reduction")) + "% · 이동 불가</gray>"));
                }
            });
        }

        private void open(Player owner) {
            closing = false;
            shutAt = -1;
            for (NamespacedKey key : modifiers) {
                ctx().removeModifier(owner, key);
            }
            modifiers.clear();
            float width = width(owner);
            float half = half(owner);
            BlockDisplay oldLid = lid;
            BlockDisplay oldBase = base;
            lid = null;
            base = null;
            anchor = null;
            if (oldLid != null && oldLid.isValid()) {
                Displays.animate(oldLid, halfPose(width + 0.06f, half * 0.6f, half * 2 + 0.5f, 0), SHELL_OPEN_TICKS);
                ctx().runLater(owner, SHELL_OPEN_TICKS, () -> {
                    if (oldLid.isValid()) {
                        Displays.shrinkAway(ctx(), owner, oldLid, 3);
                    }
                });
            }
            if (oldBase != null && oldBase.isValid()) {
                Displays.animate(oldBase, halfPose(width, 0.01f, 0, 0), SHELL_OPEN_TICKS);
                ctx().runLater(owner, SHELL_OPEN_TICKS, () -> {
                    if (oldBase.isValid()) {
                        oldBase.remove();
                    }
                });
            }
            Fx.sound(Fx.body(owner), Sound.ENTITY_SHULKER_OPEN, 0.8f, 1.1f + Fx.jitter(0.08f));
        }

        /** The halves stay on the shulker (it cannot walk, but it can still be moved by others) and the lid peeks. */
        private void follow(Player owner, long now) {
            Location at = anchor(owner);
            if (anchor == null || anchor.distanceSquared(at) > 1.0E-4 || !anchor.getWorld().equals(at.getWorld())) {
                anchor = at;
                if (base != null && base.isValid()) {
                    base.teleport(at);
                }
                if (lid != null && lid.isValid()) {
                    lid.teleport(at);
                }
            }
            if (peekUntil >= 0 && now >= peekUntil) {
                peekUntil = -1;
                if (lid != null && lid.isValid()) {
                    float half = half(owner);
                    Displays.animate(lid, halfPose(width(owner) + 0.06f, half, half + SHELL_GAP, 0), 3);
                    Fx.sound(Fx.body(owner), Sound.ENTITY_SHULKER_CLOSE, 0.5f, 1.3f + Fx.jitter(0.08f));
                }
            }
        }

        /** The lid lifts a crack, as a shulker does to shoot. */
        void peek(Player owner) {
            if (!closing || lid == null || !lid.isValid()) {
                return;
            }
            float half = half(owner);
            Displays.animate(lid, halfPose(width(owner) + 0.06f, half, half + SHELL_GAP + PEEK_LIFT, 0), 2);
            peekUntil = ctx().currentTick() + PEEK_TICKS;
            Fx.sound(Fx.body(owner), Sound.ENTITY_SHULKER_OPEN, 0.6f, 1.2f + Fx.jitter(0.08f));
        }

        /** A hit on the shut shell: a dull knock, purple sparks and the lid flashes. */
        void struck(Player owner) {
            long now = ctx().currentTick();
            if (now - lastFeedback < SHELL_FEEDBACK_COOLDOWN) {
                return;
            }
            lastFeedback = now;
            Location chest = Fx.body(owner);
            Fx.sound(chest, Sound.ENTITY_SHULKER_HURT_CLOSED, 1.0f, 1.0f + Fx.jitter(0.1f));
            Vfx.cloud(ctx(), chest, Particle.ENCHANTED_HIT, 6, 0.4, 0.1, null, false);
            Vfx.cloud(ctx(), chest, Particle.DUST_COLOR_TRANSITION, 5, 0.45, 0, PALETTE.fade(1.0f), false);
            BlockDisplay flash = lid;
            if (flash != null && flash.isValid()) {
                flash.setBlock(SHELL_HIT.createBlockData());
                ctx().runLater(owner, 3, () -> {
                    if (flash.isValid()) {
                        flash.setBlock(SHELL_BLOCK.createBlockData());
                    }
                });
            }
        }

        /** The ability was revoked: the context removes the modifiers and displays, only the state is dropped. */
        void forget() {
            closing = false;
            shutAt = -1;
            sneakingSince = -1;
            modifiers.clear();
            base = null;
            lid = null;
            anchor = null;
        }

        private Location anchor(Player owner) {
            Location at = owner.getLocation();
            at.setYaw(0);
            at.setPitch(0);
            return at;
        }

        private float width(Player owner) {
            return (float) (Body.scaleOf(owner) * 1.0);
        }

        /** Half of the shell's height: a sneaking body plus a little room. */
        private float half(Player owner) {
            return (float) (Body.scaleOf(owner) * 0.82);
        }
    }

    /** One half of the shell (or of a turret): a box of the given width and height whose bottom sits at {@code y}. */
    private static Transformation halfPose(float width, float height, float y, float yaw) {
        return Displays.transform(0, y + height / 2, 0, width, height, width, yaw, 0, 0);
    }

    // ------------------------------------------------------------------ active: homing bullets

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        double range = Math.max(1, params().num("bullet_range"));
        LivingEntity target = ctx().findTarget(owner, range);
        if (target != null && !visible(owner, target)) {
            target = null;
        }
        if (target == null) {
            target = seek(owner, range);
        }
        if (target == null) {
            return ActivationResult.fail("쫓을 적이 보이지 않습니다");
        }
        int count = Math.max(1, params().integer("bullet_count"));
        long life = Math.max(10, params().ticks("bullet_life"));
        double damage = Math.max(0, params().num("bullet_damage"));
        long levitate = params().ticks("levitate");

        shell.peek(owner);
        Location eye = owner.getEyeLocation();
        Vector facing = Motion.horizontalFacing(owner.getLocation());
        Vector side = new Vector(-facing.getZ(), 0, facing.getX());
        Location chest = owner.getLocation().add(0, owner.getHeight() * 0.7, 0);
        for (int i = 0; i < count; i++) {
            double offset = count == 1 ? 0 : (i / (double) (count - 1) - 0.5) * 2 * MUZZLE_SIDE;
            Location muzzle = chest.clone().add(side.clone().multiply(offset)).add(facing.clone().multiply(0.4));
            if (!muzzle.getBlock().isPassable()) {
                muzzle = eye.clone();
            }
            // The first legs fan out: up, then left and right, so the volley reads as separate bullets.
            Vector first = i == 0 ? new Vector(0, 1, 0) : side.clone().multiply(i % 2 == 0 ? -1 : 1);
            new Bullet(muzzle, target, first, damage, levitate, life, false, null).launch(i * 2L);
        }
        lockOn(owner, target);
        Fx.sound(eye, Sound.ENTITY_SHULKER_SHOOT, 1.0f, 1.0f + Fx.jitter(0.1f));
        Fx.sound(eye, Sound.ENTITY_SHULKER_AMBIENT, 0.5f, 1.3f + Fx.jitter(0.1f));
        Vfx.cloud(ctx(), chest, Particle.END_ROD, 6, 0.2, 0.05, null, false);
        Vfx.ring(ctx(), chest, 0.6, 12, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.9f), false);
        return ActivationResult.success();
    }

    /** The nearest enemy in front of the owner that it can see, within {@code range}. */
    private @Nullable LivingEntity seek(Player owner, double range) {
        Location eye = owner.getEyeLocation();
        Vector look = eye.getDirection();
        for (LivingEntity enemy : ctx().enemiesNear(owner, eye, range)) {
            Location body = Fx.body(enemy);
            Vector to = body.toVector().subtract(eye.toVector());
            if (to.lengthSquared() < 1.0E-6 || to.clone().normalize().dot(look) < SEEK_CONE_COS) {
                continue;
            }
            if (visible(owner, enemy) && Spots.clearPath(eye, body)) {
                return enemy;
            }
        }
        return null;
    }

    /** A purple ring closing in on the locked target. */
    private void lockOn(Player owner, LivingEntity target) {
        Loop.repeat(ctx(), owner, 0, 1, LOCK_RING_TICKS, run -> {
            if (!Combat.isTargetable(ctx(), owner, target)) {
                return false;
            }
            double radius = 1.3 - 0.8 * (run + 1) / LOCK_RING_TICKS;
            Vfx.ring(ctx(), Fx.body(target), radius, 14, run * 0.4, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.0f), false);
            return true;
        });
    }

    /** False for players hidden from the owner (stealth): they are never targeted or marked. */
    private static boolean visible(Player owner, LivingEntity entity) {
        return !(entity instanceof Player hidden) || owner.canSee(hidden);
    }

    /**
     * A shulker bullet: flies along one block axis at a time towards its target, turning sharp corners like the real
     * thing, and goes straight for the body once close. Pops on walls and at the end of its life.
     */
    private final class Bullet implements LongPredicate {

        private Location position;
        private @Nullable LivingEntity target;
        private Vector axis;
        private Vector heading;
        private long axisUntil;
        private final double damage;
        private final long levitate;
        private final long life;
        private final boolean fortress;
        private final @Nullable Fortress source;
        private @Nullable BlockDisplay core;
        private @Nullable BlockDisplay glass;
        private float spin;

        Bullet(Location from, LivingEntity target, Vector first, double damage, long levitate, long life,
               boolean fortress, @Nullable Fortress source) {
            this.position = from.clone();
            this.target = target;
            this.axis = first.clone().normalize();
            this.heading = axis.clone();
            this.damage = damage;
            this.levitate = levitate;
            this.life = life;
            this.fortress = fortress;
            this.source = source;
        }

        void launch(long delay) {
            Player owner = player();
            Location at = position.clone();
            at.setYaw(0);
            at.setPitch(0);
            long lifetime = life + delay + 4;
            if (fits(fortress ? 1 : 2)) {
                core = Displays.block(ctx(), owner, at, BULLET_CORE.createBlockData(), cube(0.05f, 0, 0), lifetime);
                core.setTeleportDuration(1);
                displays.add(core);
                Displays.animateNextTick(ctx(), owner, core, cube(CORE_SIZE, 0, 0), 2);
                if (!fortress) {
                    glass = Displays.block(ctx(), owner, at, BULLET_SHELL.createBlockData(), cube(0.05f, 45, 0),
                            lifetime);
                    glass.setTeleportDuration(1);
                    displays.add(glass);
                    Displays.animateNextTick(ctx(), owner, glass, cube(GLASS_SIZE, 45, 30), 2);
                }
            }
            axisUntil = AXIS_MIN_TICKS + delay;
            Loop.repeat(ctx(), owner, 1 + delay, 1, life, this, this::expire);
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            LivingEntity locked = target;
            if (locked != null && !trackable(owner, locked)) {
                locked = reacquire(owner);
                target = locked;
            }
            Vector move;
            if (locked != null) {
                Vector delta = Fx.body(locked).toVector().subtract(position.toVector());
                double distance = delta.length();
                if (distance < FINAL_HOMING) {
                    move = distance < 1.0E-6 ? heading.clone() : delta.multiply(1 / distance);
                } else {
                    if (tick >= axisUntil || axis.dot(delta) < 0.5) {
                        pickAxis(delta, tick);
                    }
                    move = axis.clone().multiply(0.9).add(delta.multiply(0.3 / distance)).normalize();
                }
            } else {
                move = heading.clone();
            }
            Vector step = move.multiply(BULLET_SPEED);
            Location next = position.clone().add(step);
            if (blocked(next)) {
                Vector detour = detour(locked);
                if (detour == null) {
                    pop();
                    return false;
                }
                step = detour.multiply(BULLET_SPEED);
                next = position.clone().add(step);
            }
            LivingEntity hit = struck(position, next);
            if (hit != null) {
                impact(hit);
                return false;
            }
            Location previous = position;
            position = next;
            heading = step.clone().normalize();
            draw(previous, tick);
            return true;
        }

        /** Picks a new axis towards the target, mostly the longest way, sometimes another one, never backwards. */
        private void pickAxis(Vector delta, long tick) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double ax = Math.abs(delta.getX());
            double ay = Math.abs(delta.getY()) * 0.8; // vertical legs a bit less often: they look like hops
            double az = Math.abs(delta.getZ());
            double total = ax + ay + az;
            if (total < 1.0E-6) {
                return;
            }
            double roll = random.nextDouble(total);
            Vector next;
            if (roll < ax) {
                next = new Vector(Math.signum(delta.getX()), 0, 0);
            } else if (roll < ax + ay) {
                next = new Vector(0, Math.signum(delta.getY()), 0);
            } else {
                next = new Vector(0, 0, Math.signum(delta.getZ()));
            }
            if (next.lengthSquared() > 0.5) {
                if (!next.equals(axis)) {
                    Vfx.dot(position, Particle.ELECTRIC_SPARK, null, false);
                }
                axis = next;
            }
            axisUntil = tick + random.nextLong(AXIS_MIN_TICKS, AXIS_MAX_TICKS + 1);
        }

        /** Another axis around an obstacle, the one that gets closest to the target; null if boxed in. */
        private @Nullable Vector detour(@Nullable LivingEntity locked) {
            Vector[] options = {new Vector(1, 0, 0), new Vector(-1, 0, 0), new Vector(0, 1, 0), new Vector(0, -1, 0),
                    new Vector(0, 0, 1), new Vector(0, 0, -1)};
            Vector goal = locked != null ? Fx.body(locked).toVector() : position.toVector().add(heading);
            Vector best = null;
            double bestDistance = Double.MAX_VALUE;
            for (Vector option : options) {
                if (option.dot(heading) < -0.9) {
                    continue;
                }
                Location probe = position.clone().add(option.clone().multiply(BULLET_SPEED));
                if (blocked(probe)) {
                    continue;
                }
                double distance = probe.toVector().distanceSquared(goal);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = option;
                }
            }
            if (best != null) {
                axis = best.clone();
            }
            return best;
        }

        private boolean blocked(Location at) {
            World world = at.getWorld();
            BoundingBox box = new BoundingBox(at.getX() - 0.1, at.getY() - 0.1, at.getZ() - 0.1, at.getX() + 0.1,
                    at.getY() + 0.1, at.getZ() + 0.1);
            return world.hasCollisionsIn(box) || !ctx().isInsideArena(at);
        }

        /** The first enemy whose (slightly enlarged) hitbox the segment passes through. */
        private @Nullable LivingEntity struck(Location from, Location to) {
            Vector start = from.toVector();
            Vector delta = to.toVector().subtract(start);
            double length = delta.length();
            Location mid = from.clone().add(delta.clone().multiply(0.5));
            for (LivingEntity enemy : ctx().enemiesNear(player(), mid, length / 2 + BULLET_RADIUS + 0.3)) {
                BoundingBox box = enemy.getBoundingBox().expand(BULLET_RADIUS);
                if (box.contains(start) || box.contains(to.toVector())
                        || length > 1.0E-6 && box.rayTrace(start, delta.clone().multiply(1 / length), length) != null) {
                    return enemy;
                }
            }
            return null;
        }

        private void draw(Location previous, long tick) {
            spin += 23;
            Location at = position.clone();
            at.setYaw(0);
            at.setPitch(0);
            if (core != null && core.isValid()) {
                core.teleport(at);
                if (tick % 2 == 0) {
                    Displays.animate(core, cube(CORE_SIZE, spin, spin * 0.6f), 2);
                }
            }
            if (glass != null && glass.isValid()) {
                glass.teleport(at);
                if (tick % 2 == 0) {
                    Displays.animate(glass, cube(GLASS_SIZE, -spin * 0.7f, 30 + spin * 0.4f), 2);
                }
            }
            Vfx.dot(previous, Particle.END_ROD, null, false);
            if (tick % 2 == 0) {
                Vfx.dot(previous, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.8f), false);
            }
            if (tick % 6 == 0) {
                Vfx.dot(position, Particle.REVERSE_PORTAL, null, false);
            }
        }

        private void impact(LivingEntity enemy) {
            Player owner = player();
            Location at = position.clone();
            remove();
            Combat.hit(ctx(), owner, enemy, damage);
            Vfx.burst(ctx(), at, PALETTE, 0.45, Particle.END_ROD, false);
            Fx.sound(at, Sound.ENTITY_SHULKER_BULLET_HIT, 1.0f, 1.0f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.7f, 1.4f + Fx.jitter(0.1f));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            long ticks = source != null ? source.allowance(enemy, levitate) : levitate;
            if (ticks > 0) {
                levitate(owner, enemy, ticks);
            }
        }

        /** Out of time: the bullet fizzles. Also runs on the last tick of the loop. */
        void expire() {
            if (core != null || glass != null) {
                pop();
            }
        }

        private void pop() {
            Location at = position.clone();
            remove();
            Vfx.cloud(ctx(), at, Particle.END_ROD, 4, 0.08, 0.06, null, false);
            Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 4, 0.12, 0, PALETTE.fade(0.8f), false);
            Vfx.dot(at, Particle.POOF, null, false);
            Fx.sound(at, Sound.ENTITY_SHULKER_BULLET_HURT, 0.7f, 1.2f + Fx.jitter(0.1f));
        }

        private void remove() {
            if (core != null && core.isValid()) {
                core.remove();
            }
            if (glass != null && glass.isValid()) {
                glass.remove();
            }
            core = null;
            glass = null;
        }

        private boolean trackable(Player owner, LivingEntity entity) {
            return Combat.isTargetable(ctx(), owner, entity) && visible(owner, entity)
                    && entity.getWorld().equals(position.getWorld());
        }

        /** A lost bullet keeps hunting: the nearest visible enemy close to it, if any. */
        private @Nullable LivingEntity reacquire(Player owner) {
            for (LivingEntity enemy : ctx().enemiesNear(owner, position, REACQUIRE_RADIUS)) {
                if (visible(owner, enemy) && Spots.clearPath(position, Fx.body(enemy))) {
                    return enemy;
                }
            }
            return null;
        }
    }

    /**
     * Floats a hit enemy: a levitation effect (refreshed, never stacked) and sparkles dripping from its feet for as
     * long as it lasts.
     */
    private void levitate(Player owner, LivingEntity enemy, long ticks) {
        int duration = (int) Math.min(Integer.MAX_VALUE, ticks);
        PotionEffect current = enemy.getPotionEffect(PotionEffectType.LEVITATION);
        if (current == null || current.getDuration() < duration) {
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, duration, 0, false, true, true));
        }
        enemy.setFallDistance(0);
        Location feet = enemy.getLocation().add(0, 0.1, 0);
        Vfx.ring(ctx(), feet, 0.7, 12, 0, Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.0f), false);
        Vfx.cloud(ctx(), feet, Particle.REVERSE_PORTAL, 6, 0.25, 0.03, null, false);
        Fx.sound(feet, Sound.ENTITY_SHULKER_TELEPORT, 0.5f, 1.6f + Fx.jitter(0.1f));
        Loop.repeat(ctx(), owner, FLOAT_SPARKLE_PERIOD, FLOAT_SPARKLE_PERIOD, Math.max(1, ticks / FLOAT_SPARKLE_PERIOD),
                run -> {
                    if (!Combat.isTargetable(ctx(), owner, enemy)
                            || !enemy.hasPotionEffect(PotionEffectType.LEVITATION)) {
                        return false;
                    }
                    if (!visible(owner, enemy)) {
                        return true;
                    }
                    Location under = enemy.getLocation();
                    Vfx.dot(under.clone().add(ThreadLocalRandom.current().nextDouble(-0.25, 0.25), -0.05,
                            ThreadLocalRandom.current().nextDouble(-0.25, 0.25)), Particle.END_ROD, null, false);
                    Vfx.ring(ctx(), under.add(0, -0.1, 0), 0.4, 6, run * 0.5, Particle.DUST, PALETTE.dust(0.6f),
                            false);
                    return true;
                });
    }

    // ------------------------------------------------------------------ ultimate: shulker fortress

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        List<Location> spots = turretSpots(owner, Math.max(1, params().integer("turret_count")));
        if (spots.isEmpty()) {
            return ActivationResult.fail("포대를 세울 자리가 없습니다");
        }
        new Fortress(spots).raise();
        return ActivationResult.success();
    }

    /** Ground spots around the owner, the first straight ahead, then evenly around. */
    private List<Location> turretSpots(Player owner, int count) {
        Location feet = owner.getLocation();
        Vector facing = Motion.horizontalFacing(feet);
        World world = feet.getWorld();
        List<Location> spots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Vector out = facing.clone().rotateAroundY(-i * TAU / count);
            for (double distance = TURRET_DISTANCE; distance >= 1.0; distance -= 0.6) {
                Location probe = feet.clone().add(out.clone().multiply(distance)).add(0, 1.0, 0);
                if (!Spots.clearPath(feet.clone().add(0, 1.0, 0), probe)) {
                    continue;
                }
                RayTraceResult ground = world.rayTraceBlocks(probe, new Vector(0, -1, 0), TURRET_GROUND_SEARCH + 1,
                        FluidCollisionMode.NEVER, true);
                if (ground == null) {
                    continue;
                }
                Location spot = ground.getHitPosition().toLocation(world);
                if (!Spots.isFree(world, spot, TURRET_BASE, TURRET_HEIGHT * 2) || !ctx().isInsideArena(spot)) {
                    continue;
                }
                spot.setYaw(0);
                spot.setPitch(0);
                spots.add(spot);
                break;
            }
        }
        return spots;
    }

    /**
     * The fortress: purpur turrets around the owner that peek open in turn and fire bullets at the nearest enemy in
     * range. Self-contained (owner-scoped tasks only), so a Gambler can borrow it.
     */
    private final class Fortress implements LongPredicate {

        private final class Turret {
            final Location spot;
            final Location muzzle;
            @Nullable BlockDisplay base;
            @Nullable BlockDisplay lid;
            @Nullable BlockDisplay head;
            long nextShot;
            long closeAt = -1;

            Turret(Location spot, long firstShot) {
                this.spot = spot;
                this.muzzle = spot.clone().add(0, TURRET_HEIGHT * 2 + 0.3, 0);
                this.nextShot = firstShot;
            }
        }

        private final List<Turret> turrets = new ArrayList<>();
        private final long duration = Math.max(20, params().ticks("ult_duration"));
        private final long interval = Math.max(5, params().ticks("ult_interval"));
        private final double range = Math.max(1, params().num("ult_range"));
        private final double damage = Math.max(0, params().num("ult_damage"));
        private final long levitate = params().ticks("ult_levitate");
        private final long maxLevitate = params().ticks("ult_max_levitate");
        private final Map<UUID, Long> floated = new HashMap<>();
        private final Location center;

        Fortress(List<Location> spots) {
            this.center = player().getLocation();
            long stagger = Math.max(1, interval / spots.size());
            for (int i = 0; i < spots.size(); i++) {
                turrets.add(new Turret(spots.get(i), TURRET_RISE_TICKS + 2 + i * stagger));
            }
        }

        void raise() {
            Player owner = player();
            long lifetime = duration + TURRET_SINK_TICKS + 10;
            for (Turret turret : turrets) {
                Location at = turret.spot;
                if (fits(3)) {
                    turret.base = Displays.block(ctx(), owner, at, SHELL_BLOCK.createBlockData(),
                            halfPose(TURRET_BASE, 0.01f, -0.3f, 0), lifetime);
                    turret.head = Displays.block(ctx(), owner, at, HEAD_BLOCK.createBlockData(),
                            Displays.transform(0, 0.1f, 0, 0.01f, 0.01f, 0.01f, 0, 0, 0), lifetime);
                    turret.lid = Displays.block(ctx(), owner, at, SHELL_BLOCK.createBlockData(),
                            halfPose(TURRET_BASE + 0.05f, 0.01f, 0.1f, 0), lifetime);
                    displays.add(turret.base);
                    displays.add(turret.head);
                    displays.add(turret.lid);
                    Displays.animateNextTick(ctx(), owner, turret.base, halfPose(TURRET_BASE, TURRET_HEIGHT, 0, 0),
                            TURRET_RISE_TICKS);
                    Displays.animateNextTick(ctx(), owner, turret.head, headPose(false), TURRET_RISE_TICKS);
                    Displays.animateNextTick(ctx(), owner, turret.lid, lidPose(false), TURRET_RISE_TICKS);
                    turret.lid.setViewRange(2.0f);
                    turret.base.setViewRange(2.0f);
                }
                Vfx.helix(ctx(), at, 0.55, 1.4, 1.5, 2, 10, 0, Particle.REVERSE_PORTAL, null, true);
                Vfx.ring(ctx(), at.clone().add(0, 0.05, 0), 0.7, 14, 0, Particle.DUST_COLOR_TRANSITION,
                        PALETTE.glow(1.2f), true);
                Fx.sound(at, Sound.ENTITY_SHULKER_TELEPORT, 1.0f, 0.8f + Fx.jitter(0.1f));
            }
            Location feet = owner.getLocation();
            Fx.sound(feet, Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.2f, 0.7f);
            Fx.sound(feet, Sound.BLOCK_SHULKER_BOX_OPEN, 1.0f, 0.6f);
            Fx.sound(feet, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.4f);
            Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.1, 0), 0.5, range, 8, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.fade(1.4f), true);
            owner.sendActionBar(Text.mm("<" + PURPLE + "><b>셜커 요새</b></" + PURPLE + "> <gray>포대 "
                    + turrets.size() + "개 · " + Fx.seconds(duration) + "초</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, this::collapse);
        }

        @Override
        public boolean test(long tick) {
            Player owner = player();
            for (Turret turret : turrets) {
                if (turret.closeAt >= 0 && tick >= turret.closeAt) {
                    turret.closeAt = -1;
                    animate(turret.lid, lidPose(false), 3);
                    animate(turret.head, headPose(false), 3);
                    Fx.sound(turret.spot, Sound.ENTITY_SHULKER_CLOSE, 0.6f, 1.2f + Fx.jitter(0.1f));
                }
                if (tick < turret.nextShot) {
                    continue;
                }
                turret.nextShot = tick + interval;
                LivingEntity target = nearest(owner, turret);
                if (target == null) {
                    continue;
                }
                fire(owner, turret, target);
                turret.closeAt = tick + TURRET_PEEK_TICKS;
            }
            if (tick % RANGE_RING_PERIOD == 0) {
                Vfx.ring(ctx(), center.clone().add(0, 0.15, 0), range, (int) Math.min(48, range * 3), tick * 0.01,
                        Particle.DUST, PALETTE.dust(1.0f), true);
            }
            if (tick % 4 == 0) {
                for (Turret turret : turrets) {
                    Vfx.dot(Vfx.scatter(turret.spot, 0.5).add(0, 0.2, 0), Particle.REVERSE_PORTAL, null, false);
                }
            }
            return true;
        }

        private @Nullable LivingEntity nearest(Player owner, Turret turret) {
            for (LivingEntity enemy : ctx().enemiesNear(owner, turret.spot, range)) {
                if (visible(owner, enemy) && Spots.clearPath(turret.muzzle, Fx.body(enemy))) {
                    return enemy;
                }
            }
            return null;
        }

        private void fire(Player owner, Turret turret, LivingEntity target) {
            animate(turret.lid, lidPose(true), 2);
            animate(turret.head, headPose(true), 2);
            // Every turret bullet pops straight up out of the lid first, then turns towards its target.
            new Bullet(turret.muzzle, target, new Vector(0, 1, 0), damage, levitate,
                    Math.max(10, params().ticks("bullet_life")), true, this).launch(0);
            Fx.sound(turret.muzzle, Sound.ENTITY_SHULKER_SHOOT, 0.9f, 1.1f + Fx.jitter(0.12f));
            Fx.sound(turret.muzzle, Sound.ENTITY_SHULKER_OPEN, 0.5f, 1.3f + Fx.jitter(0.1f));
            Vfx.cloud(ctx(), turret.muzzle, Particle.END_ROD, 4, 0.12, 0.04, null, false);
        }

        /** Levitation left for {@code enemy} under the fortress's per-enemy cap (at most {@code wanted}). */
        long allowance(LivingEntity enemy, long wanted) {
            long used = floated.getOrDefault(enemy.getUniqueId(), 0L);
            long granted = Math.max(0, Math.min(wanted, maxLevitate - used));
            if (granted > 0) {
                floated.put(enemy.getUniqueId(), used + granted);
            }
            return granted;
        }

        /** Time is up: the turrets close and sink back into the ground. */
        private void collapse() {
            Player owner = player();
            for (Turret turret : turrets) {
                for (BlockDisplay part : new BlockDisplay[] {turret.lid, turret.head, turret.base}) {
                    if (part == null || !part.isValid()) {
                        continue;
                    }
                    Transformation now = part.getTransformation();
                    Vector3f translation = new Vector3f(now.getTranslation()).sub(0, 0.6f, 0);
                    Displays.animate(part, new Transformation(translation, now.getLeftRotation(),
                            new Vector3f(now.getScale()).mul(0.2f), now.getRightRotation()), TURRET_SINK_TICKS);
                    ctx().runLater(owner, TURRET_SINK_TICKS, () -> {
                        if (part.isValid()) {
                            part.remove();
                        }
                    });
                }
                Vfx.cloud(ctx(), turret.spot.clone().add(0, 0.4, 0), Particle.REVERSE_PORTAL, 10, 0.3, 0.05, null,
                        false);
                Fx.sound(turret.spot, Sound.ENTITY_SHULKER_CLOSE, 0.8f, 0.8f + Fx.jitter(0.1f));
            }
            Fx.sound(owner.getLocation(), Sound.ENTITY_SHULKER_TELEPORT, 0.8f, 0.6f);
        }
    }

    private static void animate(@Nullable Display display, Transformation pose, int ticks) {
        if (display != null) {
            Displays.animate(display, pose, ticks);
        }
    }

    private static Transformation lidPose(boolean open) {
        float y = TURRET_HEIGHT + (open ? PEEK_LIFT : 0.02f);
        return halfPose(TURRET_BASE + 0.05f, TURRET_HEIGHT, y, 0);
    }

    private static Transformation headPose(boolean open) {
        float size = open ? TURRET_HEAD : TURRET_HEAD * 0.8f;
        return Displays.transform(0, TURRET_HEIGHT + (open ? 0.25f : 0.05f), 0, size, size, size, 0, 0, 0);
    }

    // ------------------------------------------------------------------ helpers

    /** Forgets removed displays and says whether {@code count} more fit in the display budget. */
    private boolean fits(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= DISPLAY_BUDGET;
    }

    /** A cube of edge {@code size} centered on the display, turned by yaw and pitch (degrees). */
    private static Transformation cube(float size, float yaw, float pitch) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(yaw))
                .rotateX((float) Math.toRadians(pitch));
        Vector3f translation = new Quaternionf(rotation).transform(new Vector3f(size / 2)).negate();
        return new Transformation(translation, rotation, new Vector3f(size), new Quaternionf());
    }
}
