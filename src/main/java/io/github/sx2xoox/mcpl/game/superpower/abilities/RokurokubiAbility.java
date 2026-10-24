package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import com.destroystokyo.paper.ClientOption;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Body;
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
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 로쿠로쿠비 — the Japanese yōkai whose neck stretches without end at night, and its cousin the nukekubi, whose head
 * leaves the body and flies (and cannot come back if the body was moved). Crouching still, its neck rises over cover and
 * every enemy the raised head can see starts to glow; its active sends the head out on a neck that bends wherever it
 * looks, around corners, to bite the first enemy it meets (harder if it went round a corner to get there); and the
 * ultimate tears the head off: a small flying head that bites and heals, while the headless body stays behind as a
 * weak spot — everything the body takes the head takes too, and a battered or shoved body does not take its head back
 * cleanly.
 *
 * <p>Look (pale skin, kimono purple, paper-lantern orange): the neck is a long pale column with a purple collar at its
 * root and the owner's own head at its end; lantern-orange motes drift along it. The stretching neck is built segment
 * by segment along the path the head flew, and reels back in after the bite. The flying head is the owner's own head
 * trailing orange wisps; the body it left behind slumps with a glowing paper lantern where its head was.
 */
public final class RokurokubiAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("rokurokubi", "로쿠로쿠비", Material.LEAD)
            .color(TextColor.color(0xF29A38))
            .difficulty(3)
            .passive("목 빼고 엿보기",
                    "웅크린 채 {peek_delay}초 가만히 있으면 목이 {peek_height}블록 위로 늘어나 엄폐물 너머를 엿봅니다. "
                            + "그 높이에서 보이는 {peek_range}블록 안의 적은 발광합니다. 엿보는 머리는 모두에게 보입니다.")
            .active("늘어나는 목",
                    "머리를 날려 목을 최대 {neck_length}블록까지 늘입니다. 머리는 내 시선을 따라 휘어져 모퉁이를 돌 수 "
                            + "있고(벽은 못 뚫음), 처음 닿은 적을 물어 {bite_damage} 피해를 주고 {bite_slow_time}초간 "
                            + "{bite_slow}% 느리게 합니다. 모퉁이 너머의 적은 {corner_bonus} 피해를 더 받습니다. 그동안 몸은 "
                            + "제자리에 묶입니다.",
                    8)
            .ultimate("누케쿠비",
                    "{head_duration}초간 머리가 몸에서 빠져 날아다니며, 닿는 적을 물어 {head_bite} 피해를 주고 "
                            + "{head_heal} 회복합니다. 남은 몸이 받는 피해는 {body_share}배로 나에게 들어옵니다. 끝날 때(F로 "
                            + "일찍 끝낼 수 있음) 몸이 {body_limit} 이상 피해를 입었거나 밀려났다면 {misfit_stun}초간 기절합니다.")
            .ultimateNotStandalone()
            .param("peek_delay", 0.5)
            .param("peek_height", 3)
            .param("peek_range", 20)
            .param("peek_glow", 2)
            .param("peek_interval", 1)
            .param("neck_length", 14)
            .param("neck_time", 0.8)
            .param("bite_damage", 4)
            .param("bite_slow", 40)
            .param("bite_slow_time", 1)
            .param("corner_bonus", 2)
            .param("head_duration", 6)
            .param("head_scale", 0.5)
            .param("head_speed", 20)
            .param("head_bite", 2)
            .param("head_heal", 1)
            .param("head_bite_interval", 1)
            .param("body_share", 1.5)
            .param("body_limit", 6)
            .param("body_push", 1)
            .param("misfit_stun", 1)
            .build();

    private static final String LANTERN_TAG = "#F29A38";
    private static final String PURPLE_TAG = "#9C7BC4";

    // ------------------------------------------------------------------ look
    /** Pale skin, paper-lantern orange, kimono purple. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xF1DCC9, 0xF29A38, 0x5B3C7A);
    private static final Color SKIN = Color.fromRGB(0xF1DCC9);
    private static final Color LANTERN = Color.fromRGB(0xF29A38);
    private static final Color PURPLE = Color.fromRGB(0x5B3C7A);
    private static final Particle.DustOptions LANTERN_DUST = new Particle.DustOptions(LANTERN, 0.8f);
    private static final Particle.DustOptions SKIN_DUST = new Particle.DustOptions(SKIN, 0.9f);
    private static final Particle.DustOptions PURPLE_DUST = new Particle.DustOptions(PURPLE, 0.9f);
    private static final Particle.DustTransition WISP = new Particle.DustTransition(LANTERN, PURPLE, 0.8f);

    /** Block types; block data is created on use (it needs a running server). */
    private static final Material NECK = Material.WHITE_TERRACOTTA;
    private static final Material COLLAR = Material.PURPLE_WOOL;
    private static final Material PAPER_LANTERN = Material.SHROOMLIGHT;

    private static final int MAX_DISPLAYS = 24;
    /**
     * Ticks a display following a player glides to each new spot: as long as a client lerps a moving player's body
     * (3 steps), so a follower moved every tick keeps pace with the body instead of trailing or jittering.
     */
    private static final int FOLLOW_LERP = 3;
    private static final float NECK_WIDTH = 0.17f;
    private static final float HEAD_SIZE = 0.9f;
    private static final float FLYING_HEAD = 0.8f;

    // passive: peeking
    private static final long VISIBILITY_PERIOD = 10;
    private static final int PEEK_GROW_TICKS = 6;
    private static final double MIN_PEEK = 0.6;

    // active: the stretching neck
    private static final double BITE_HITBOX = 0.35;
    private static final int RETRACT_TICKS = 6;

    // ultimate: the flying head
    private static final double HEAD_REACH = 0.45;
    /** A player's sprinting speed on flat ground, blocks per second. */
    private static final double SPRINT_SPEED = 5.612;
    /**
     * Blocks per second a sprint-flying player reaches per unit of Bukkit fly speed: the client accelerates by the
     * fly speed (Bukkit's value is twice the internal one, sprinting doubles it again) times its 0.98 input scale, and
     * air drag keeps 0.91 of the speed every tick, so it settles at speed * 0.98 / 0.09 blocks per tick.
     */
    private static final double SPRINT_FLY_PER_SPEED = 0.98 / 0.09 * 20;
    private static final double BODY_HEALTH = 1024;
    private static final long LANDING_TICKS = 60;
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND};

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final Set<UUID> hiddenFrom = new HashSet<>();
    private final LandingGuard landing = new LandingGuard();
    private @Nullable RokuListener listener;
    private int lastSeen = -1;
    private boolean forward;
    private boolean backward;
    private boolean left;
    private boolean right;
    private boolean jump;
    private boolean sneakKey;
    private int stillTicks;
    private @Nullable Peek peek;
    private @Nullable Neck neck;
    private @Nullable Nukekubi flight;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        RokuListener created = new RokuListener();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        listener = created;
        // Keys held since before the grant send no input event until they change: start from what is held now.
        try {
            readKeys(player().getCurrentInput());
        } catch (RuntimeException ignored) {
            // No input known yet (a server or test double without it): the next input event fills it in.
        }
    }

    private void readKeys(Input keys) {
        forward = keys.isForward();
        backward = keys.isBackward();
        left = keys.isLeft();
        right = keys.isRight();
        jump = keys.isJump();
        sneakKey = keys.isSneak();
    }

    @Override
    public void onRevoke() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        Nukekubi current = flight;
        flight = null;
        if (current != null) {
            current.restore(false);
        }
        peek = null;
        neck = null;
        landing.disarm();
        hiddenFrom.clear();
        live.clear();
    }

    /** The owner's keys (the peek judges input, not position) and the headless body's wounds. */
    private final class RokuListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onInput(PlayerInputEvent event) {
            if (!event.getPlayer().equals(player())) {
                return;
            }
            readKeys(event.getInput());
        }

        /** The body is immune to the world (falls, fire, suffocation): only blows and skills reach it. */
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onBodyHurt(EntityDamageEvent event) {
            Nukekubi current = flight;
            if (current == null || !event.getEntity().equals(current.body)) {
                return;
            }
            if (!(event instanceof EntityDamageByEntityEvent byEntity)
                    || ctx().resolveAttacker(byEntity.getDamager()) == null) {
                event.setCancelled(true);
            }
        }

        /** What the body takes, the head takes too (more). */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBodyWounded(EntityDamageEvent event) {
            Nukekubi current = flight;
            if (current != null && event.getEntity().equals(current.body)
                    && event instanceof EntityDamageByEntityEvent byEntity) {
                current.wounded(byEntity);
            }
        }
    }

    // ================================================================== passive: peeking over cover

    @Override
    public void onTick(long tick) {
        Player owner = player();
        landing.update(owner, ctx().currentTick());
        if (tick % VISIBILITY_PERIOD == 0) {
            syncVisibility(owner);
        }
        boolean busy = neck != null || flight != null;
        boolean still = !busy && (owner.isSneaking() || sneakKey) && !forward && !backward && !left && !right
                && !jump && Motion.isGrounded(owner) && !owner.isFlying();
        stillTicks = still ? stillTicks + 1 : 0;
        Peek current = peek;
        if (current == null) {
            if (stillTicks >= Math.max(1, params().ticks("peek_delay"))) {
                double height = peekHeight(owner);
                if (height >= MIN_PEEK) {
                    peek = Peek.start(this, owner, height);
                }
            }
            return;
        }
        if (!still) {
            current.lower();
            peek = null;
            return;
        }
        current.tick(tick);
    }

    /** The raised neck while peeking: a pale column from the head up to the peeking head. */
    private static final class Peek {
        private final RokurokubiAbility ability;
        private final Player owner;
        private final double height;
        private final @Nullable BlockDisplay column;
        private final @Nullable ItemDisplay head;
        private long ticks;

        private Peek(RokurokubiAbility ability, Player owner, double height, @Nullable BlockDisplay column,
                     @Nullable ItemDisplay head) {
            this.ability = ability;
            this.owner = owner;
            this.height = height;
            this.column = column;
            this.head = head;
        }

        static Peek start(RokurokubiAbility ability, Player owner, double height) {
            ability.lastSeen = -1;
            BlockDisplay column = null;
            ItemDisplay head = null;
            Location feet = Displays.unrotated(owner.getLocation());
            if (ability.reserve(2)) {
                column = ability.keep(Displays.block(ability.ctx(), owner, feet, NECK.createBlockData(),
                        columnPose(owner, 0.01), 0));
                column.setTeleportDuration(FOLLOW_LERP);
                Displays.animateNextTick(ability.ctx(), owner, column, columnPose(owner, height), PEEK_GROW_TICKS);
                head = ability.keep(Displays.item(ability.ctx(), owner, feet, headOf(owner),
                        headPose(owner, owner.getEyeHeight() + 0.3, owner.getLocation().getYaw(),
                                owner.getLocation().getPitch()), 0));
                head.setTeleportDuration(FOLLOW_LERP);
                ItemDisplay grown = head;
                Displays.animateNextTick(ability.ctx(), owner, grown, headPose(owner, peekEyeHeight(owner, height),
                        owner.getLocation().getYaw(), owner.getLocation().getPitch()), PEEK_GROW_TICKS);
                ability.hideFromThoseWhoCannotSee(owner, column);
                ability.hideFromThoseWhoCannotSee(owner, head);
            }
            Location top = owner.getLocation().add(0, peekEyeHeight(owner, height), 0);
            Fx.sound(top, Sound.ENTITY_SLIME_SQUISH, 0.5f, 0.6f);
            Fx.sound(top, Sound.ENTITY_PHANTOM_SWOOP, 0.3f, 1.6f);
            Vfx.cloud(ability.ctx(), top, Particle.DUST, 4, 0.15, 0, LANTERN_DUST, false);
            return new Peek(ability, owner, height, column, head);
        }

        void tick(long tick) {
            ticks++;
            Location feet = owner.getLocation();
            float yaw = feet.getYaw();
            float pitch = feet.getPitch();
            if (column != null && column.isValid()) {
                Displays.moveTo(column, feet);
                if (ticks > PEEK_GROW_TICKS) {
                    Displays.animate(column, columnPose(owner, height), 1);
                }
            }
            if (head != null && head.isValid()) {
                Displays.moveTo(head, feet);
                if (ticks > PEEK_GROW_TICKS) {
                    Displays.animate(head, headPose(owner, peekEyeHeight(owner, height), yaw, pitch), 1);
                }
            }
            long interval = Math.max(1, ability.params().ticks("peek_interval"));
            if (ticks >= PEEK_GROW_TICKS && (ticks - PEEK_GROW_TICKS) % interval == 0) {
                ability.lookAround(owner, owner.getLocation().add(0, peekEyeHeight(owner, height), 0));
            }
            if (tick % 6 == 0) {
                double t = ThreadLocalRandom.current().nextDouble();
                Location along = feet.clone().add(0, owner.getHeight() + t * height, 0);
                Vfx.dot(along.add(Fx.jitter(0.15f), 0, Fx.jitter(0.15f)), Particle.DUST, LANTERN_DUST, false);
            }
        }

        void lower() {
            if (column != null && column.isValid()) {
                Displays.animate(column, columnPose(owner, 0.01), 4);
                ability.ctx().runLater(owner, 4, () -> {
                    if (column.isValid()) {
                        column.remove();
                    }
                });
            }
            if (head != null && head.isValid()) {
                Displays.animate(head, headPose(owner, owner.getEyeHeight() + 0.3, owner.getLocation().getYaw(), 0),
                        4);
                ability.ctx().runLater(owner, 4, () -> {
                    if (head.isValid()) {
                        head.remove();
                    }
                });
            }
            Fx.sound(owner.getLocation(), Sound.ENTITY_SLIME_JUMP_SMALL, 0.4f, 0.8f);
        }
    }

    /**
     * How far the neck may rise over the head: at most {@code peek_height}, and a ceiling stops it with the peeking
     * head under it. Less than {@link #MIN_PEEK} means there is no room to peek at all (a neck and eyes pushed through
     * a roof would see, and show, what is on top of it).
     */
    private double peekHeight(Player owner) {
        double wanted = Math.max(MIN_PEEK, params().num("peek_height"));
        Location top = owner.getLocation().add(0, owner.getHeight() + 0.05, 0);
        RayTraceResult roof = owner.getWorld().rayTraceBlocks(top, new Vector(0, 1, 0), wanted + 0.5,
                FluidCollisionMode.NEVER, true);
        if (roof == null) {
            return wanted;
        }
        return Math.min(wanted, roof.getHitPosition().getY() - top.getY() - 0.45);
    }

    /** The peeking head's eyes, above the feet. */
    private static double peekEyeHeight(Player owner, double height) {
        return owner.getHeight() + height + 0.25;
    }

    /** The pale neck column from the top of the head up {@code height} blocks. */
    private static Transformation columnPose(Player owner, double height) {
        float h = (float) Math.max(0.01, height);
        return Displays.transform(0, (float) (owner.getHeight() + h / 2 - 0.05), 0, NECK_WIDTH, h + 0.1f,
                NECK_WIDTH, 0, 0, 0);
    }

    /** The peeking eyes see the enemies in reach, through no wall: they glow for a moment. */
    private void lookAround(Player owner, Location eyes) {
        double range = Math.max(1, params().num("peek_range"));
        int glow = (int) Math.max(1, params().ticks("peek_glow")) + 2;
        int seen = 0;
        for (LivingEntity enemy : ctx().enemiesNear(owner, eyes, range)) {
            if (!seen(owner, enemy) || enemy instanceof Mannequin && isMyBody(enemy)) {
                continue;
            }
            if (!Spots.clearPath(eyes, enemy.getEyeLocation()) && !Spots.clearPath(eyes, Fx.body(enemy))) {
                continue;
            }
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glow, 0, false, false, true));
            seen++;
        }
        if (seen == lastSeen) {
            // Only news is worth the action bar (other abilities use it too).
            return;
        }
        lastSeen = seen;
        if (seen > 0) {
            owner.playSound(owner, Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.6f);
            owner.sendActionBar(Text.mm("<" + LANTERN_TAG + "><b>엿보는 중</b></" + LANTERN_TAG + "> <gray>적 </gray>"
                    + "<white>" + seen + "</white><gray>명이 보입니다</gray>"));
        } else {
            owner.sendActionBar(Text.mm("<" + LANTERN_TAG + "><b>엿보는 중</b></" + LANTERN_TAG + "> <gray>아무도 "
                    + "보이지 않습니다</gray>"));
        }
    }

    // ================================================================== active: the stretching neck

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (flight != null) {
            return ActivationResult.fail("머리만 날아다니는 동안은 목을 늘일 수 없습니다");
        }
        if (neck != null) {
            return ActivationResult.fail("이미 목을 늘이고 있습니다");
        }
        Peek raised = peek;
        if (raised != null) {
            raised.lower();
            peek = null;
        }
        Neck stretch = new Neck(owner);
        neck = stretch;
        stretch.launch();
        return ActivationResult.success();
    }

    /**
     * The head flies out along the owner's gaze, a little each tick, the neck following it segment by segment; the
     * owner's body is held in place meanwhile. It bites the first visible enemy it touches, bonks on a wall, or runs
     * out of neck; then the neck reels back in.
     */
    private final class Neck {
        private final Player owner;
        private final World world;
        private final List<Vector> path = new ArrayList<>();
        private final List<BlockDisplay> segments = new ArrayList<>();
        private final List<NamespacedKey> holds = new ArrayList<>();
        private final int steps;
        private final double step;
        private @Nullable ItemDisplay head;
        private @Nullable BlockDisplay collar;
        private Vector at;
        private Vector heading;
        private boolean done;
        private boolean snapped;

        Neck(Player owner) {
            this.owner = owner;
            this.world = owner.getWorld();
            this.steps = (int) Math.max(1, params().ticks("neck_time"));
            this.step = Math.max(0.5, params().num("neck_length")) / steps;
            this.at = owner.getEyeLocation().toVector();
            this.heading = owner.getEyeLocation().getDirection();
            path.add(at.clone());
        }

        void launch() {
            long hold = steps + RETRACT_TICKS + 2;
            holds.add(ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -1, Operation.MULTIPLY_SCALAR_1, hold));
            holds.add(ctx().addModifier(owner, owner, Attribute.JUMP_STRENGTH, -1, Operation.MULTIPLY_SCALAR_1, hold));
            owner.setVelocity(new Vector());
            Location eye = owner.getEyeLocation();
            Fx.sound(eye, Sound.ENTITY_PHANTOM_SWOOP, 0.9f, 1.3f + Fx.jitter(0.1f));
            Fx.sound(eye, Sound.ENTITY_SLIME_SQUISH, 0.7f, 0.5f);
            Fx.sound(eye, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 0.7f);
            Vfx.cloud(ctx(), eye, Particle.DUST, 6, 0.2, 0, LANTERN_DUST, false);
            if (reserve(2)) {
                Location base = Displays.unrotated(owner.getLocation());
                collar = keep(Displays.block(ctx(), owner, base, COLLAR.createBlockData(), Displays.transform(0,
                        (float) (owner.getHeight() * 0.8), 0, 0.42f, 0.12f, 0.42f, owner.getLocation().getYaw(), 0, 0),
                        0));
                head = keep(Displays.item(ctx(), owner, Displays.unrotated(eye), headOf(owner), headTurn(heading, 1f),
                        0));
                head.setTeleportDuration(1);
                hideFromThoseWhoCannotSee(owner, collar);
                hideFromThoseWhoCannotSee(owner, head);
            }
            Loop.repeat(ctx(), owner, 1, 1, steps, this::stretch, this::reelIn);
        }

        private boolean stretch(long run) {
            if (done) {
                return false;
            }
            Vector look = owner.getEyeLocation().getDirection();
            if (look.lengthSquared() > 1.0E-6) {
                heading = look.normalize();
            }
            LivingEntity bitten = firstEnemy(heading);
            RayTraceResult wall = world.rayTraceBlocks(at.toLocation(world), heading, step, FluidCollisionMode.NEVER,
                    true);
            double wallDistance = wall == null ? Double.MAX_VALUE : wall.getHitPosition().distance(at);
            if (bitten != null && hitDistance(bitten, heading) <= wallDistance) {
                Vector reached = at.clone().add(heading.clone().multiply(Math.min(step, hitDistance(bitten, heading))));
                grow(reached);
                bite(bitten);
                return false;
            }
            if (wall != null) {
                grow(wall.getHitPosition().subtract(heading.clone().multiply(0.15)));
                bonk();
                return false;
            }
            Vector next = at.clone().add(heading.clone().multiply(step));
            if (!ctx().isInsideArena(next.toLocation(world))) {
                return false;
            }
            grow(next);
            if (run % 2 == 0) {
                Fx.sound(next.toLocation(world), Sound.ENTITY_SLIME_SQUISH, 0.3f, 0.6f + 0.05f * run);
            }
            return true;
        }

        /** The nearest enemy (seen by the owner, not its own body) this step of the head passes through. */
        private @Nullable LivingEntity firstEnemy(Vector direction) {
            LivingEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            Location middle = at.toLocation(world).add(direction.clone().multiply(step / 2));
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, step / 2 + 1.2)) {
                if (!seen(owner, enemy)) {
                    continue;
                }
                double distance = hitDistance(enemy, direction);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = enemy;
                }
            }
            return best;
        }

        private double hitDistance(LivingEntity enemy, Vector direction) {
            BoundingBox box = enemy.getBoundingBox().expand(BITE_HITBOX);
            if (box.contains(at)) {
                return 0;
            }
            RayTraceResult hit = box.rayTrace(at, direction, step);
            return hit == null ? Double.MAX_VALUE : hit.getHitPosition().distance(at);
        }

        /** The head moves on to {@code next}; a neck segment fills the way it came. */
        private void grow(Vector next) {
            Vector from = at.clone();
            at = next.clone();
            path.add(at.clone());
            ItemDisplay shown = head;
            if (shown != null) {
                Displays.moveTo(shown, at.toLocation(world));
                Displays.animate(shown, headTurn(heading, 1f), 1);
            }
            Vector delta = at.clone().subtract(from);
            double length = delta.length();
            if (length > 0.05 && reserve(1)) {
                Vector middle = from.clone().add(delta.clone().multiply(0.5));
                BlockDisplay segment = keep(Displays.block(ctx(), owner, middle.toLocation(world),
                        NECK.createBlockData(), segmentPose(delta, length), 0));
                hideFromThoseWhoCannotSee(owner, segment);
                segments.add(segment);
            }
            Location spot = at.toLocation(world);
            Vfx.dot(spot, Particle.DUST, SKIN_DUST, false);
            if (path.size() % 3 == 0) {
                Vfx.dot(spot, Particle.DUST_COLOR_TRANSITION, WISP, false);
            }
        }

        /** The head sinks its teeth into the enemy. */
        private void bite(LivingEntity enemy) {
            done = true;
            Location body = Fx.body(enemy);
            double damage = params().num("bite_damage");
            boolean corner = !owner.hasLineOfSight(enemy);
            if (corner) {
                damage += Math.max(0, params().num("corner_bonus"));
            }
            Combat.hit(ctx(), owner, enemy, damage);
            if (Combat.isTargetable(ctx(), owner, enemy)) {
                double slow = Math.clamp(params().num("bite_slow") / 100.0, 0.0, 1.0);
                ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                        Math.max(1, params().ticks("bite_slow_time")));
            }
            Vfx.burst(ctx(), body, PALETTE, 0.5, Particle.CRIT, false);
            Fx.sound(body, Sound.ENTITY_FOX_BITE, 1.0f, 0.8f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.ENTITY_PHANTOM_BITE, 0.8f, 1.1f + Fx.jitter(0.1f));
            if (corner) {
                Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 10, 0.4, 0, WISP, false);
                Fx.sound(body, Sound.ENTITY_VEX_CHARGE, 0.7f, 1.4f);
                owner.sendActionBar(Text.mm("<" + LANTERN_TAG + "><b>모퉁이 너머로 물었다!</b></" + LANTERN_TAG
                        + "> <gray>+" + AbilityInfo.formatNumber(params().num("corner_bonus")) + " 피해</gray>"));
            }
            if (enemy instanceof Player victim) {
                victim.sendActionBar(Text.mm("<" + LANTERN_TAG + "><b>목이 늘어난 머리가 물었다!</b></" + LANTERN_TAG
                        + ">"));
            }
        }

        /** The head knocks against a wall. */
        private void bonk() {
            done = true;
            Location spot = at.toLocation(world);
            Fx.sound(spot, Sound.BLOCK_WOOD_HIT, 1.0f, 0.7f);
            Fx.sound(spot, Sound.ENTITY_PLAYER_HURT, 0.4f, 1.5f);
            Vfx.cloud(ctx(), spot, Particle.CRIT, 4, 0.15, 0.05, null, false);
        }

        /** The neck is gone at once (the ultimate tore the head off): its pieces scatter, the body is free. */
        void snap() {
            if (snapped) {
                return;
            }
            snapped = true;
            done = true;
            for (BlockDisplay segment : segments) {
                if (segment.isValid() && ThreadLocalRandom.current().nextInt(3) == 0) {
                    Vfx.cloud(ctx(), segment.getLocation(), Particle.DUST, 2, 0.2, 0, SKIN_DUST, false);
                }
            }
            finish();
        }

        /** The neck reels back in from the head's end, then the body is free. */
        private void reelIn() {
            if (snapped) {
                return;
            }
            done = true;
            int total = segments.size();
            int perTick = Math.max(1, (int) Math.ceil(total / (double) RETRACT_TICKS));
            Fx.sound(owner.getLocation(), Sound.ENTITY_SLIME_JUMP_SMALL, 0.7f, 0.6f);
            Loop.repeat(ctx(), owner, 1, 1, RETRACT_TICKS + 1, run -> {
                for (int i = 0; i < perTick && !segments.isEmpty(); i++) {
                    BlockDisplay last = segments.removeLast();
                    if (last.isValid()) {
                        last.remove();
                    }
                    if (path.size() > 1) {
                        path.removeLast();
                    }
                }
                ItemDisplay shown = head;
                if (shown != null && !path.isEmpty()) {
                    Displays.moveTo(shown, path.getLast().toLocation(world));
                }
                return !segments.isEmpty();
            }, this::finish);
        }

        private void finish() {
            for (BlockDisplay segment : segments) {
                if (segment.isValid()) {
                    segment.remove();
                }
            }
            segments.clear();
            ItemDisplay shown = head;
            head = null;
            if (shown != null && shown.isValid()) {
                shown.remove();
            }
            BlockDisplay ring = collar;
            collar = null;
            if (ring != null && ring.isValid()) {
                Displays.shrinkAway(ctx(), owner, ring, 3);
            }
            for (NamespacedKey key : holds) {
                ctx().removeModifier(owner, key);
            }
            holds.clear();
            if (neck == this) {
                neck = null;
            }
        }
    }

    /** A neck segment along {@code delta}, centered on its display. */
    private static Transformation segmentPose(Vector delta, double length) {
        Quaternionf rotation = pointing(new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ()));
        return box(new Vector3f(), new Vector3f(NECK_WIDTH, NECK_WIDTH, (float) length + 0.06f), rotation);
    }

    /** The head turned to look along {@code direction}. */
    private static Transformation headTurn(Vector direction, float scale) {
        double yaw = Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        double flat = Math.hypot(direction.getX(), direction.getZ());
        double pitch = Math.toDegrees(Math.atan2(-direction.getY(), flat));
        float size = HEAD_SIZE * scale;
        return Displays.centered(0, 0, 0, size, size, size, (float) yaw, (float) pitch, 0);
    }

    /** The peeking (or flying) head at {@code height} over the feet, turned like the owner's head. */
    private static Transformation headPose(Player owner, double height, float yaw, float pitch) {
        float size = HEAD_SIZE * (float) Body.scaleOf(owner);
        return Displays.centered(0, (float) height, 0, size, size, size, yaw, pitch, 0);
    }

    /** The flying head at the (shrunk) owner's eyes: a head of its own size, not shrunk with the body. */
    private static Transformation flyingHeadPose(Player owner, float yaw, float pitch) {
        return Displays.centered(0, (float) owner.getEyeHeight(), 0, FLYING_HEAD, FLYING_HEAD, FLYING_HEAD, yaw,
                pitch, 0);
    }

    // ================================================================== ultimate: nukekubi

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (flight != null) {
            return ActivationResult.fail("이미 머리가 날아다니고 있습니다");
        }
        Neck stretched = neck;
        if (stretched != null) {
            // The head tears off the end of its stretched neck.
            stretched.snap();
        }
        Peek raised = peek;
        if (raised != null) {
            raised.lower();
            peek = null;
        }
        Nukekubi head = new Nukekubi(owner);
        if (!head.tearOff()) {
            return ActivationResult.fail("몸을 남겨 둘 수 없습니다");
        }
        flight = head;
        return ActivationResult.success();
    }

    /** F again while the head flies: it goes back to its body now. */
    @Override
    public boolean onUltimateKey() {
        Nukekubi current = flight;
        if (current == null) {
            return false;
        }
        current.recall = true;
        return true;
    }

    /**
     * The flying head: the owner, shrunk, invisible (its armour and items hidden from everyone with fake empty
     * equipment) and flying, shown as its own head; its body a mannequin wearing its skin and armour, with a paper
     * lantern where the head was. Blows on the body reach the head. When it returns, a body that was battered or moved
     * does not take it back cleanly.
     */
    private final class Nukekubi {
        private final Player owner;
        private final long ticks = Math.max(1, params().ticks("head_duration"));
        private final Map<UUID, Long> bitten = new HashMap<>();
        private @Nullable Mannequin body;
        private @Nullable ItemDisplay head;
        private @Nullable NamespacedKey shrink;
        /** Where the body came to rest (it may first drop to the ground); pushes are measured from here. */
        private Location bodyStart;
        private boolean settled;
        private boolean allowedFlight;
        private boolean wasFlying;
        private float flySpeed;
        private double bodyDamage;
        boolean recall;
        private boolean restored;

        Nukekubi(Player owner) {
            this.owner = owner;
            this.bodyStart = owner.getLocation();
        }

        boolean tearOff() {
            Location at = owner.getLocation();
            Spots.snapToGround(at.getWorld(), at, 2);
            Mannequin left;
            try {
                left = at.getWorld().spawn(at, Mannequin.class, mannequin -> dress(mannequin, owner, at));
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Could not leave the rokurokubi's body", e);
                return false;
            }
            if (!left.isValid()) {
                return false;
            }
            ctx().summon(owner, left);
            body = left;
            bodyStart = left.getLocation();

            allowedFlight = owner.getAllowFlight();
            wasFlying = owner.isFlying();
            flySpeed = owner.getFlySpeed();
            double scale = Math.clamp(params().num("head_scale"), 0.1, 1.0);
            shrink = ctx().addModifier(owner, owner, Attribute.SCALE, scale - 1, Operation.MULTIPLY_SCALAR_1, 0);
            owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) ticks + 40, 0, false, false,
                    true));
            owner.setAllowFlight(true);
            owner.setFlying(true);
            owner.setFlySpeed(headFlySpeed(owner));
            owner.setVelocity(new Vector(0, 0.3, 0));
            hideEquipment();
            if (reserve(1)) {
                head = keep(Displays.item(ctx(), owner, Displays.unrotated(owner.getLocation()), headOf(owner),
                        flyingHeadPose(owner, owner.getLocation().getYaw(), owner.getLocation().getPitch()), 0));
                head.setTeleportDuration(FOLLOW_LERP);
                owner.hideEntity(ctx().plugin(), head);
                hideFromThoseWhoCannotSee(owner, head);
            }
            Location neckStump = at.clone().add(0, 1.4, 0);
            Vfx.dot(neckStump, Particle.FLASH, LANTERN, true);
            Vfx.cloud(ctx(), neckStump, Particle.DUST_COLOR_TRANSITION, 14, 0.3, 0, WISP, true);
            Vfx.cloud(ctx(), neckStump, Particle.SOUL, 6, 0.2, 0.03, null, true);
            Fx.sound(neckStump, Sound.ENTITY_PHANTOM_FLAP, 1.0f, 0.6f);
            Fx.sound(neckStump, Sound.ENTITY_VEX_AMBIENT, 1.0f, 0.7f);
            Fx.sound(neckStump, Sound.BLOCK_LANTERN_PLACE, 0.8f, 0.8f);
            owner.sendActionBar(Text.mm("<" + LANTERN_TAG + "><b>누케쿠비</b></" + LANTERN_TAG + "> <gray>머리가 "
                    + "날아올랐습니다 · 몸을 지키세요 · F: 돌아가기</gray>"));
            Loop.repeat(ctx(), owner, 0, 1, ticks, this::fly, () -> restore(true));
            return true;
        }

        /** The body wears the owner's skin and armour, a paper lantern hangs where its head was, and it slumps. */
        private void dress(Mannequin mannequin, Player owner, Location at) {
            mannequin.setPersistent(false);
            mannequin.setRotation(owner.getLocation().getYaw(), 0);
            mannequin.setBodyYaw(owner.getLocation().getYaw());
            mannequin.setProfile(ResolvableProfile.resolvableProfile(owner.getPlayerProfile()));
            try {
                mannequin.setSkinParts(owner.getClientOption(ClientOption.SKIN_PARTS));
            } catch (RuntimeException ignored) {
                // Cosmetic only.
            }
            mannequin.setMainHand(owner.getMainHand());
            mannequin.setDescription(null);
            mannequin.customName(owner.teamDisplayName());
            mannequin.setCustomNameVisible(true);
            mannequin.setCollidable(false);
            mannequin.setPose(Pose.SNEAKING, true);
            AttributeInstance health = mannequin.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(BODY_HEALTH);
            }
            mannequin.setHealth(Math.min(BODY_HEALTH, Combat.maxHealth(mannequin)));
            EntityEquipment equipment = mannequin.getEquipment();
            EntityEquipment worn = owner.getEquipment();
            for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET}) {
                ItemStack item = worn.getItem(slot);
                equipment.setItem(slot, item.isEmpty() ? null : item.clone());
            }
            equipment.setItem(EquipmentSlot.HEAD, ItemStack.of(PAPER_LANTERN));
        }

        private boolean fly(long run) {
            if (restored) {
                return false;
            }
            Mannequin left = body;
            if (left == null || !left.isValid() || left.isDead()
                    || left.getLocation().getY() < left.getWorld().getMinHeight() + 1) {
                return false;
            }
            if (recall && run > 0) {
                return false;
            }
            if (!owner.isFlying() && owner.getAllowFlight()) {
                owner.setFlying(true);
            }
            // Flight ignores the movement speed attribute: slows and roots reach the head through its fly speed.
            float speed = headFlySpeed(owner);
            if (Math.abs(owner.getFlySpeed() - speed) > 1.0E-4f) {
                owner.setFlySpeed(speed);
            }
            if (!settled && (Motion.isGrounded(left) || left.isInWater())) {
                settled = true;
                bodyStart = left.getLocation();
            }
            owner.setFallDistance(0);
            hideEquipment();
            Location feet = owner.getLocation();
            ItemDisplay shown = head;
            if (shown != null) {
                Displays.moveTo(shown, feet);
                Displays.animate(shown, flyingHeadPose(owner, feet.getYaw(), feet.getPitch()), 1);
            }
            biteAround(feet);
            if (run % 2 == 0 && !hiddenOwner(owner)) {
                Location eyes = owner.getEyeLocation();
                Vector back = eyes.getDirection().multiply(-0.3);
                Vfx.dot(eyes.clone().add(back), Particle.DUST_COLOR_TRANSITION, WISP, false);
                if (run % 6 == 0) {
                    Vfx.dot(eyes.clone().add(back.multiply(2)), Particle.SMALL_FLAME, null, false);
                }
            }
            if (run % 10 == 0) {
                // The lantern where the body's head was flickers.
                Location stump = left.getLocation().add(0, left.getHeight() * 0.85, 0);
                Vfx.dot(stump, Particle.DUST, LANTERN_DUST, false);
                Vfx.dot(stump.clone().add(0, 0.3, 0), Particle.SMALL_FLAME, null, false);
            }
            long remaining = ticks - run;
            if (remaining == 40 || remaining == 20) {
                owner.sendActionBar(Text.mm("<" + LANTERN_TAG + "><b>누케쿠비</b></" + LANTERN_TAG + "> <gray>"
                        + Fx.seconds(remaining) + "초 뒤 몸으로 돌아갑니다</gray>"));
                owner.playSound(owner, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 0.8f);
            }
            return true;
        }

        /** The head bites every enemy it touches (each at most every interval), and every bite feeds it. */
        private void biteAround(Location feet) {
            long now = ctx().currentTick();
            long interval = Math.max(1, params().ticks("head_bite_interval"));
            BoundingBox reach = owner.getBoundingBox().expand(HEAD_REACH);
            for (LivingEntity enemy : ctx().enemiesNear(owner, owner.getEyeLocation(), 1.6)) {
                if (!enemy.getBoundingBox().overlaps(reach) || !seen(owner, enemy)) {
                    continue;
                }
                Long last = bitten.get(enemy.getUniqueId());
                if (last != null && now - last < interval) {
                    continue;
                }
                bitten.put(enemy.getUniqueId(), now);
                Combat.hit(ctx(), owner, enemy, params().num("head_bite"));
                ctx().heal(owner, params().num("head_heal"));
                Location at = Fx.body(enemy);
                Vfx.cloud(ctx(), at, Particle.CRIT, 6, 0.25, 0.1, null, false);
                Vfx.cloud(ctx(), at, Particle.DUST, 4, 0.25, 0, LANTERN_DUST, false);
                Vfx.cloud(ctx(), owner.getEyeLocation(), Particle.HEART, 1, 0.1, 0, null, false);
                Fx.sound(at, Sound.ENTITY_FOX_BITE, 0.9f, 1.2f + Fx.jitter(0.1f));
            }
        }

        /** A blow on the body: the head feels it too (more), and the body remembers it. */
        void wounded(EntityDamageByEntityEvent event) {
            double amount = event.getFinalDamage();
            if (!(amount > 0) || restored) {
                return;
            }
            bodyDamage += amount;
            Mannequin left = body;
            if (left != null) {
                ctx().runLater(owner, 1, () -> {
                    if (left.isValid() && !left.isDead()) {
                        left.setHealth(Combat.maxHealth(left));
                    }
                });
                Location at = left.getLocation().add(0, 1.0, 0);
                Vfx.cloud(ctx(), at, Particle.DUST, 5, 0.3, 0, PURPLE_DUST, false);
            }
            Player attacker = ctx().resolveAttacker(event.getDamager());
            double mirrored = amount * Math.max(0, params().num("body_share"));
            if (attacker == null || !ctx().isEnemy(attacker, owner) || !(mirrored > 0)) {
                return;
            }
            Player wounder = attacker;
            ctx().runLater(owner, 0, () -> {
                if (ctx().isAlive(owner) && !restored) {
                    ctx().damage(wounder, owner, mirrored);
                    owner.sendActionBar(Text.mm("<red><b>몸이 공격당하고 있다!</b></red> <gray>("
                            + AbilityInfo.formatNumber(bodyDamage) + "/" + AbilityInfo.formatNumber(
                            params().num("body_limit")) + ")</gray>"));
                    owner.playSound(owner, Sound.ENTITY_PLAYER_HURT, 0.6f, 0.7f);
                }
            });
        }

        private void hideEquipment() {
            Map<EquipmentSlot, ItemStack> empty = new EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot slot : SLOTS) {
                empty.put(slot, ItemStack.empty());
            }
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(owner) && viewer.getWorld().equals(owner.getWorld())) {
                    viewer.sendEquipmentChange(owner, empty);
                }
            }
        }

        private void showEquipment() {
            Map<EquipmentSlot, ItemStack> real = new EnumMap<>(EquipmentSlot.class);
            EntityEquipment worn = owner.getEquipment();
            for (EquipmentSlot slot : SLOTS) {
                real.put(slot, worn.getItem(slot));
            }
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(owner)) {
                    viewer.sendEquipmentChange(owner, real);
                }
            }
        }

        /**
         * The head goes back to its body (or the flight is broken off with the owner's ability, {@code back} false):
         * size, flight, visibility and equipment are restored; a battered or moved body stuns the owner.
         */
        void restore(boolean back) {
            if (restored) {
                return;
            }
            restored = true;
            if (flight == this) {
                flight = null;
            }
            if (shrink != null) {
                ctx().removeModifier(owner, shrink);
                shrink = null;
            }
            PotionEffect invisible = owner.getPotionEffect(PotionEffectType.INVISIBILITY);
            if (invisible != null && !invisible.hasParticles() && invisible.getDuration() <= ticks + 40) {
                owner.removePotionEffect(PotionEffectType.INVISIBILITY);
            }
            if (owner.isOnline()) {
                owner.setFlying(wasFlying && allowedFlight);
                owner.setAllowFlight(allowedFlight);
                owner.setFlySpeed(flySpeed);
                showEquipment();
            }
            ItemDisplay shown = head;
            head = null;
            if (shown != null && shown.isValid()) {
                shown.remove();
            }
            Mannequin left = body;
            body = null;
            if (!back) {
                if (left != null && left.isValid()) {
                    left.remove();
                }
                return;
            }
            boolean misfit = bodyDamage >= Math.max(0, params().num("body_limit")) - 1.0E-9;
            Location target = null;
            if (left != null && left.isValid()) {
                Location home = left.getLocation();
                misfit |= moved(home, bodyStart, settled) > Math.max(0, params().num("body_push"));
                Location feet = home.clone();
                Spots.snapToGround(feet.getWorld(), feet, 3);
                if (Spots.isSafe(ctx(), feet, Body.PLAYER)) {
                    target = feet;
                } else {
                    target = Spots.around(ctx(), owner, left, 0);
                }
                Vfx.cloud(ctx(), home.clone().add(0, 1, 0), Particle.POOF, 8, 0.3, 0.02, null, false);
                left.remove();
            } else {
                misfit = true;
            }
            Location from = owner.getEyeLocation();
            if (target != null) {
                target.setYaw(owner.getLocation().getYaw());
                target.setPitch(owner.getLocation().getPitch());
                Vfx.trail(from, target.clone().add(0, 1.5, 0), LANTERN, 8, false);
                owner.teleport(target);
            }
            owner.setVelocity(new Vector());
            owner.setFallDistance(0);
            landing.armIfAirborne(owner, ctx().currentTick(), LANDING_TICKS);
            Location neckAt = owner.getLocation().add(0, 1.5, 0);
            Fx.sound(neckAt, Sound.ENTITY_SLIME_SQUISH, 0.9f, 0.7f);
            Fx.sound(neckAt, Sound.BLOCK_LANTERN_PLACE, 0.7f, 1.1f);
            if (misfit) {
                long stun = Math.max(1, params().ticks("misfit_stun"));
                ctx().addModifier(owner, owner, Attribute.MOVEMENT_SPEED, -1, Operation.MULTIPLY_SCALAR_1, stun);
                ctx().addModifier(owner, owner, Attribute.JUMP_STRENGTH, -1, Operation.MULTIPLY_SCALAR_1, stun);
                ctx().silence(owner, stun);
                Vfx.ring(ctx(), owner.getLocation().add(0, owner.getHeight() + 0.25, 0), 0.4, 8, 0, Particle.DUST,
                        PURPLE_DUST, false);
                Vfx.cloud(ctx(), neckAt, Particle.CRIT, 6, 0.25, 0.05, null, false);
                Fx.sound(neckAt, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 0.6f);
                owner.sendActionBar(Text.mm("<red><b>머리가 삐뚤게 붙었다!</b></red> <gray>" + Fx.seconds(stun)
                        + "초 기절</gray>"));
            } else {
                Vfx.cloud(ctx(), neckAt, Particle.DUST_COLOR_TRANSITION, 8, 0.3, 0, WISP, false);
                owner.sendActionBar(Text.mm("<" + PURPLE_TAG + ">머리가 제자리로 돌아왔습니다</" + PURPLE_TAG + ">"));
            }
        }
    }

    /** A return into mid-air (the body was left on a ledge) is not a fall. */
    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        landing.absorb(event, ctx().currentTick());
    }

    /**
     * How far the body was moved from where it came to rest: up, down or sideways once it {@code settled}, sideways
     * only if it never touched the ground (it was left in mid-air and is still falling).
     */
    private static double moved(Location now, Location rest, boolean settled) {
        if (!now.getWorld().equals(rest.getWorld())) {
            return Double.MAX_VALUE;
        }
        return settled ? now.distance(rest) : Math.hypot(now.getX() - rest.getX(), now.getZ() - rest.getZ());
    }

    /**
     * The flying head's fly speed: sprint-flying it is {@code head_speed} percent faster than a sprinting player, times
     * whatever its movement speed attribute is slowed (or sped up) by.
     */
    private float headFlySpeed(Player owner) {
        double factor = 1;
        AttributeInstance speed = owner.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null && speed.getBaseValue() > 1.0E-6) {
            factor = Math.clamp(speed.getValue() / speed.getBaseValue(), 0.0, 2.0);
        }
        double wanted = SPRINT_SPEED * (1 + Math.max(0, params().num("head_speed")) / 100.0) * factor;
        return (float) Math.clamp(wanted / SPRINT_FLY_PER_SPEED, 0.0, 1.0);
    }

    // ================================================================== helpers

    /** The owner's own head as an item (its skin). */
    private static ItemStack headOf(Player owner) {
        ItemStack head = ItemStack.of(Material.PLAYER_HEAD);
        head.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(owner.getPlayerProfile()));
        return head;
    }

    private boolean isMyBody(Entity entity) {
        Nukekubi current = flight;
        return current != null && entity.equals(current.body);
    }

    /** Hides {@code display} from every viewer that cannot see the owner (stealth must not be given away). */
    private void hideFromThoseWhoCannotSee(Player owner, Display display) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(owner) && !viewer.canSee(owner)) {
                viewer.hideEntity(ctx().plugin(), display);
            }
        }
    }

    /** Keeps the neck and heads hidden from whoever cannot see the owner, shown to the others again. */
    private void syncVisibility(Player owner) {
        live.removeIf(display -> !display.isValid());
        Nukekubi current = flight;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(owner)) {
                continue;
            }
            boolean hide = !viewer.canSee(owner);
            UUID id = viewer.getUniqueId();
            if (hide) {
                hiddenFrom.add(id);
                for (Display display : live) {
                    viewer.hideEntity(ctx().plugin(), display);
                }
            } else if (hiddenFrom.remove(id)) {
                for (Display display : live) {
                    if (current == null || display != current.head || viewer.canSee(owner)) {
                        viewer.showEntity(ctx().plugin(), display);
                    }
                }
            }
        }
    }

    /** True if the owner cannot be seen at all (invisibility from elsewhere): its wisps must not give it away. */
    private boolean hiddenOwner(Player owner) {
        Nukekubi current = flight;
        return current == null && (owner.isInvisible() || owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
    }

    /** True unless {@code entity} is a player hidden from the owner (its visuals would give it away). */
    private static boolean seen(Player owner, Entity entity) {
        return !(entity instanceof Player player) || owner.canSee(player);
    }

    /** The rotation turning +Z onto {@code direction} (+Z if it is zero). */
    private static Quaternionf pointing(Vector3f direction) {
        if (direction.lengthSquared() < 1.0E-8f) {
            return new Quaternionf();
        }
        return new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(direction).normalize());
    }

    /** A box of {@code size} centered on {@code center} (relative to the display), turned by {@code rotation}. */
    private static Transformation box(Vector3f center, Vector3f size, Quaternionf rotation) {
        Vector3f translation = new Vector3f(center).sub(new Quaternionf(rotation).transform(new Vector3f(size)
                .mul(0.5f)));
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    /** Whether the neck is raised over the head right now (for tests). */
    public boolean isPeeking() {
        return peek != null;
    }

    /** Where the stretching neck's head is, null if the neck is not out (for tests). */
    public @Nullable Location neckTip() {
        Neck stretch = neck;
        return stretch == null ? null : stretch.at.toLocation(stretch.world);
    }

    /** Whether the head is flying on its own (for tests). */
    public boolean isHeadFlying() {
        return flight != null;
    }

    /** The headless body left behind, null if the head is home (for tests). */
    public @Nullable LivingEntity body() {
        Nukekubi current = flight;
        return current == null ? null : current.body;
    }
}
