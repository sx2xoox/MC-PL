package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Accent;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Palette;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Weight;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttackSettings;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttackSettings.Feel;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicFx;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicFx.Moment;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicFx.Part;
import io.github.sx2xoox.mcpl.game.superpower.basic.FxSprite;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityItems;
import io.github.sx2xoox.mcpl.util.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Basic attacks (평타) of a hosted match ({@link MatchHost#basicAttacks()}): a left-click with the ability weapon
 * fires the ability's {@link BasicAttack} instead of a vanilla sword hit.
 *
 * <ul>
 *     <li>Input: every main-hand arm swing of a fighting participant holding the weapon (a click on air, a block or
 *     an entity). Swings that come with a right-click or a drop in the same tick are not attacks.</li>
 *     <li>Vanilla hits with the weapon are cancelled before any damage ({@link PrePlayerAttackEntityEvent}, and any
 *     melee damage event the weapon would still cause), so nothing is dealt twice.</li>
 *     <li>Cadence: one attack per {@link BasicAttack#cooldown()} ticks; a click up to {@value #BUFFER_TICKS} ticks
 *     early is kept and fires when ready. The player's attack speed is set so the crosshair attack indicator fills
 *     in exactly that time.</li>
 *     <li>Combo ({@link Feel}): attacks in a row (each within the reset time of being ready) count up; the last of a
 *     combo is a finisher (bigger look, more damage, a push). Any attack may be a rare critical hit.</li>
 *     <li>Damage: {@link MatchContext#basicHit} (a melee hit: armor applies, hooks and kill credit as a sword hit; only
 *     enemies, so team mode, spectators and the host's targets work), times the host's
 *     {@link MatchHost#basicAttackMultiplier}, the configured scale, the attacker's attack damage bonuses and the
 *     finisher / critical factors.</li>
 *     <li>Look: display entities from {@link BasicFx} played by {@link BasicFxPlayer} (slash arcs, beams, flying
 *     items, hit sparks), a few particles as accents, and separate sounds for the attack and the hit.</li>
 *     <li>Everything it creates is owner-scoped (tasks, displays), so a death or the match end removes it.</li>
 * </ul>
 */
final class BasicAttackRunner implements Listener {

    /** A click this many ticks before the attack is ready still fires (when it is). */
    static final long BUFFER_TICKS = 3;
    /** Attack speed of a sword (the ability weapon's base): the crosshair indicator fills in 20 / this ticks. */
    static final double SWORD_ATTACK_SPEED = 1.6;
    /** Ticks between damage-over-time pulses. */
    static final long DOT_PERIOD = 10;
    /** Ticks between chain jumps (so the eye can follow the zap). */
    static final long CHAIN_STEP = 2;
    /** Gravity of a lobbed object, blocks per tick². */
    static final double LOB_GRAVITY = 0.06;
    /** A lobbed object bursts at the latest after this many ticks. */
    static final int LOB_MAX_TICKS = 60;
    /** The vanilla item attack damage modifier (the weapon's own damage, not a bonus). */
    private static final NamespacedKey ITEM_DAMAGE = NamespacedKey.minecraft("base_attack_damage");

    private final SuperpowerMatch match;
    private final GameSession session;
    private final AbilityItems items;
    private final MatchHost host;
    private final BasicAttackSettings settings;
    private final Feel feel;
    private final NamespacedKey speedKey;
    private final Map<UUID, State> states = new HashMap<>();
    private @Nullable BasicFxPlayer fx;

    /** One attacker's cadence, combo and its running effects on others. */
    private static final class State {
        long readyAt = Long.MIN_VALUE;
        boolean queued;
        /** A right-click or drop happened in this tick: its arm swing is not an attack. */
        long blockedTick = -1;
        @Nullable UUID hint;
        long hintTick = -1;
        /** Attacks in a row so far in the current combo, and the tick the combo starts over after. */
        int combo;
        long comboUntil = Long.MIN_VALUE;
        final Map<UUID, NamespacedKey> slows = new HashMap<>();
        final Map<UUID, NamespacedKey> weakens = new HashMap<>();
        final Map<UUID, BukkitTask> dots = new HashMap<>();
    }

    /** One attack being carried out: what fired, where it is in the combo, its damage factor and display budget. */
    private record Act(Player owner, BasicAttack attack, Moment moment, double multiplier, Accent accent,
                       BasicFxPlayer.Budget budget) {
    }

    BasicAttackRunner(SuperpowerMatch match, MatchHost host, BasicAttackSettings settings) {
        this.match = match;
        this.session = match.session();
        this.items = match.items();
        this.host = host;
        this.settings = settings;
        this.feel = settings.feel();
        this.speedKey = session.hub().key("basic_attack_speed");
    }

    BasicAttackSettings settings() {
        return settings;
    }

    private MatchContext ctx() {
        return match.context();
    }

    private BasicFxPlayer fx() {
        if (fx == null) {
            fx = new BasicFxPlayer(ctx(), session, feel);
        }
        return fx;
    }

    private State state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), id -> new State());
    }

    /** The attacker's combo step of its next attack (0 = a new combo), tests. */
    int comboStep(Player player) {
        State state = states.get(player.getUniqueId());
        return state == null || session.tick() > state.comboUntil ? 0 : state.combo;
    }

    /** Basic attack displays showing now, all attackers (tests). */
    int liveDisplays() {
        return fx == null ? 0 : fx.liveTotal();
    }

    // =================================================================== input

    /** True if {@code player} fights holding its ability weapon (its clicks are basic attacks). */
    boolean wields(Player player) {
        if (match.phase() != MatchPhase.FIGHT || !session.isParticipant(player) || !session.isInPlay(player)) {
            return false;
        }
        Participant participant = match.participant(player.getUniqueId());
        return participant != null && participant.ability != null && participant.abilityId != null
                && items.isAbilityItem(player.getInventory().getItemInMainHand());
    }

    /** A melee hit with the weapon: never the vanilla hit; remembered as a hint for the swing that follows. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttackEntity(PrePlayerAttackEntityEvent event) {
        Player player = event.getPlayer();
        if (!wields(player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getAttacked() instanceof LivingEntity living) {
            State state = state(player);
            state.hint = living.getUniqueId();
            state.hintTick = session.tick();
        }
    }

    /**
     * Any melee damage the weapon still causes (e.g. a hitbox that forwards the hit with {@code Player#attack}) is
     * cancelled: the basic attack already dealt it.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVanillaHit(EntityDamageByEntityEvent event) {
        DamageCause cause = event.getCause();
        if ((cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK)
                || !(event.getDamager() instanceof Player player)) {
            return;
        }
        MatchContext ctx = ctx();
        if (!ctx.isBasicHit() && !ctx.isAbilityDamage() && wields(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerArmSwingEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (wields(player) && state(player).blockedTick != session.tick()) {
            trigger(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            block(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseEntity(PlayerInteractEntityEvent event) {
        block(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        block(event.getPlayer());
    }

    private void block(Player player) {
        if (session.isParticipant(player)) {
            state(player).blockedTick = session.tick();
        }
    }

    /**
     * A basic attack click of {@code player}: fires now if ready, later if it is ready within
     * {@value #BUFFER_TICKS} ticks, otherwise nothing.
     *
     * @return true if it fired now
     */
    boolean trigger(Player player) {
        if (!wields(player)) {
            return false;
        }
        State state = state(player);
        long now = session.tick();
        if (now < state.readyAt) {
            long wait = state.readyAt - now;
            if (wait <= BUFFER_TICKS && !state.queued) {
                state.queued = true;
                ctx().runLater(player, wait, () -> {
                    state.queued = false;
                    if (player.isOnline() && wields(player)) {
                        fire(player, state);
                    }
                });
            }
            return false;
        }
        fire(player, state);
        return true;
    }

    private void fire(Player player, State state) {
        Participant participant = match.participant(player.getUniqueId());
        String abilityId = participant == null ? null : participant.abilityId;
        if (abilityId == null) {
            return;
        }
        BasicAttack attack = settings.attack(abilityId);
        long now = session.tick();
        if (now > state.comboUntil) {
            state.combo = 0;
        }
        int step = state.combo;
        boolean finisher = feel.isFinisher(step);
        state.combo = finisher ? 0 : step + 1;
        state.readyAt = now + attack.cooldown();
        state.comboUntil = state.readyAt + feel.comboResetTicks();
        boolean crit = feel.critChance() > 0 && session.random().nextDouble() < feel.critChance();
        Moment moment = new Moment(step, finisher, crit, session.random().nextLong());
        double multiplier = (finisher ? feel.finisherDamage() : 1) * (crit ? feel.critDamage() : 1);
        Accent accent = crit ? Accent.CRITICAL : finisher ? Accent.FINISH : Accent.NONE;
        LivingEntity hint = null;
        if (state.hintTick == now && state.hint != null
                && player.getWorld().getEntity(state.hint) instanceof LivingEntity living) {
            hint = living;
        }
        state.hint = null;
        LivingEntity aimed = hint;
        Act act = new Act(player, attack, moment, multiplier, accent, fx().budget(player));
        ctx().runOwnCode(player.getUniqueId(),
                () -> match.guard().run(abilityId, "basicAttack", () -> execute(act, aimed)));
    }

    // =================================================================== shapes

    private void execute(Act act, @Nullable LivingEntity hint) {
        Player owner = act.owner();
        BasicAttack attack = act.attack();
        if (attack.effect() == BasicAttack.Effect.LUNGE) {
            Vector forward = Motion.horizontalFacing(owner.getLocation()).multiply(attack.effectPower());
            ctx().setVoluntaryVelocity(owner, forward.setY(Math.max(0.0, owner.getVelocity().getY())));
        }
        Palette palette = attack.palette();
        Location at = owner.getLocation();
        sound(at, palette.sound(), palette.pitch(), 0.6f);
        if (act.moment().finisher()) {
            sound(at, Sound.ITEM_MACE_SMASH_AIR, 1.3f, 0.45f);
            sound(at, palette.sound(), palette.pitch() * 0.85f, 0.5f);
        }
        switch (attack.shape()) {
            case SWING -> swing(act, 0, true, hint);
            case COMBO -> repeat(act, index -> swing(act, index, index == attack.hits() - 1,
                    index == 0 ? hint : null));
            case THRUST -> thrust(act);
            case SHOT -> repeat(act, index -> shot(act, index));
            case PROJECTILE -> projectiles(act);
            case LOB -> lob(act);
            case CHAIN -> chain(act);
            case CONE -> repeat(act, index -> cone(act, index));
            case NOVA -> nova(act);
            case STRIKE -> strike(act);
        }
    }

    /** Runs {@code hit} for each of the attack's hits, {@code interval} ticks apart (the first one now). */
    private void repeat(Act act, java.util.function.IntConsumer hit) {
        Player owner = act.owner();
        BasicAttack attack = act.attack();
        hit.accept(0);
        for (int i = 1; i < attack.hits(); i++) {
            int index = i;
            ctx().runLater(owner, Math.max(1, (long) attack.interval() * i), () -> {
                if (session.isInPlay(owner)) {
                    sound(owner.getLocation(), attack.palette().sound(), attack.palette().pitch() + 0.08f * index,
                            0.45f);
                    hit.accept(index);
                }
            });
        }
    }

    /** A horizontal arc in front: every enemy inside (up to {@code pierce}), the clicked one first. */
    private void swing(Act act, int index, boolean last, @Nullable LivingEntity hint) {
        Player owner = act.owner();
        BasicAttack attack = act.attack();
        Location eye = owner.getEyeLocation();
        Vector look = horizontal(eye.getDirection(), owner);
        List<LivingEntity> found = new ArrayList<>();
        if (hint != null && ctx().isEnemy(owner, hint)
                && MatchContext.distanceToBox(eye, ctx().boxOf(hint)) <= attack.range() + 0.5) {
            found.add(hint);
        }
        for (LivingEntity enemy : ctx().enemiesNear(owner, eye, attack.range())) {
            if (!found.contains(enemy) && withinArc(eye, look, enemy, attack.angle()) && canSee(owner, enemy)) {
                found.add(enemy);
            }
        }
        Palette palette = attack.palette();
        Location chest = eye.clone().subtract(0, BasicFx.CHEST_DROP, 0);
        double width = Math.max(40, attack.angle());
        if (displays()) {
            fx().play(owner, chest, yawFrame(eye.getYaw()), BasicFx.swing(attack, act.moment(), index, last),
                    act.budget());
            Vfx.arc(ctx(), chest, attack.range() * 0.7, eye.getYaw(), width, 4, palette.particle(), null, false);
        } else {
            double height = index % 2 == 0 ? -0.35 : -0.15;
            Location center = eye.clone().add(0, height, 0);
            int points = (int) Math.max(6, Math.round(width / 12));
            Vfx.arc(ctx(), center, attack.range() * 0.75, eye.getYaw(), width, points, Particle.DUST,
                    dust(palette, 1.1f), false);
            Vfx.arc(ctx(), center, attack.range() * 0.55, eye.getYaw(), width * 0.8, Math.max(3, points / 2),
                    palette.particle(), null, false);
        }
        hitAll(act, limit(found, attack.pierce()), attack.damage());
    }

    /** A straight poke through enemies, stopped by walls. */
    private void thrust(Act act) {
        Player owner = act.owner();
        BasicAttack attack = act.attack();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double reach = reach(eye, direction, attack.range());
        List<LivingEntity> found = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, eye, reach + attack.radius())) {
            if (onRay(eye, direction, reach, enemy, attack.radius())) {
                found.add(enemy);
            }
        }
        Palette palette = attack.palette();
        Location tip = eye.clone().add(0, -0.25, 0).add(direction.clone().multiply(reach));
        if (displays()) {
            fx().play(owner, eye.clone().subtract(0, BasicFx.AIM_DROP, 0), aimFrame(direction),
                    BasicFx.thrust(attack, act.moment(), reach), act.budget());
        } else {
            Location from = eye.clone().add(0, -0.25, 0).add(direction.clone().multiply(0.6));
            Vfx.line(ctx(), from, tip, 4, Particle.DUST, dust(palette, 0.8f), false);
        }
        Vfx.cloud(ctx(), tip, palette.particle(), 3, 0.1, 0.02, null, false);
        hitAll(act, limit(found, attack.pierce()), attack.damage());
    }

    /** An instant shot at the aimed enemy (aim assist, walls block), with a tracer. */
    private void shot(Act act, int index) {
        Player owner = act.owner();
        BasicAttack attack = act.attack();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        LivingEntity target = ctx().findTarget(owner, attack.range());
        Location muzzle = eye.clone().add(0, -0.2, 0).add(direction.clone().multiply(0.7));
        Location end = target != null ? Fx.body(target) : eye.clone().add(direction.clone()
                .multiply(reach(eye, direction, attack.range())));
        Palette palette = attack.palette();
        if (displays()) {
            Vector toward = end.toVector().subtract(muzzle.toVector());
            double length = toward.length();
            fx().play(owner, muzzle, aimFrame(length > 1.0E-3 ? toward : direction),
                    BasicFx.tracer(attack, act.moment(), length, index), act.budget());
        } else {
            Vfx.line(ctx(), muzzle, end, 2.5, Particle.DUST, dust(palette, 0.6f), false);
        }
        Vfx.cloud(ctx(), muzzle, palette.particle(), 2, 0.05, 0.01, null, false);
        if (index > 0) {
            sound(muzzle, palette.sound(), palette.pitch() + 0.1f, 0.4f);
        }
        if (target != null) {
            hitAll(act, List.of(target), attack.damage());
        } else {
            Vfx.cloud(ctx(), end, palette.particle(), 3, 0.1, 0.02, null, false);
        }
    }

    /** {@code hits} projectiles fanned over {@code angle} (or one after another when {@code interval} is set). */
    private void projectiles(Act act) {
        Player owner = act.owner();
        BasicAttack attack = act.attack();
        int count = attack.hits();
        for (int i = 0; i < count; i++) {
            double offset = count > 1 && attack.interval() == 0
                    ? -attack.angle() / 2 + attack.angle() * i / (count - 1) : 0;
            if (attack.interval() > 0 && i > 0) {
                ctx().runLater(owner, (long) attack.interval() * i, () -> {
                    if (session.isInPlay(owner)) {
                        launch(act, 0);
                    }
                });
            } else {
                launch(act, offset);
            }
        }
    }

    private void launch(Act act, double yawOffset) {
        Location eye = act.owner().getEyeLocation();
        Vector direction = eye.getDirection().normalize().rotateAroundY(Math.toRadians(-yawOffset));
        Location start = eye.clone().add(0, -0.15, 0).add(direction.clone().multiply(0.4));
        new Flight(act, start, direction).begin();
    }

    /** A projectile's look in flight: its parts' displays and the trail it leaves. */
    private final class Body {
        final Act act;
        final List<Part> parts;
        final List<Display> displays = new ArrayList<>();
        final BasicFxPlayer.@Nullable Run trail;
        final Quaternionf frame;
        @Nullable Location last;
        int tick;

        Body(Act act, Location at, Vector heading) {
            this.act = act;
            this.frame = aimFrame(heading);
            if (!displays()) {
                parts = List.of();
                trail = null;
                return;
            }
            parts = BasicFx.projectile(act.attack(), act.moment());
            for (Part part : parts) {
                if (!act.budget().take()) {
                    break;
                }
                Display display = fx().spawn(act.owner(), part.kind(), part.material(), null, at,
                        BasicFxPlayer.transformation(part.kind(), BasicFx.partPose(act.attack(), part, 0), frame));
                if (display != null) {
                    displays.add(display);
                }
            }
            trail = fx().run(act.owner());
            last = at.clone();
        }

        /** The projectile is at {@code at} now. */
        void move(Location at) {
            tick++;
            for (int i = 0; i < displays.size(); i++) {
                Display display = displays.get(i);
                if (display.isValid()) {
                    Location spot = at.clone();
                    spot.setYaw(0);
                    spot.setPitch(0);
                    display.teleport(spot);
                    Part part = parts.get(i);
                    BasicFxPlayer.animate(display, BasicFxPlayer.transformation(part.kind(),
                            BasicFx.partPose(act.attack(), part, tick), frame));
                }
            }
            if (trail != null && last != null) {
                Vector back = last.toVector().subtract(at.toVector());
                trail.add(BasicFx.trail(act.attack(), act.moment(),
                        new Vector3f((float) back.getX(), (float) back.getY(), (float) back.getZ()), tick), at,
                        new Quaternionf(), act.budget());
                trail.step();
            }
            last = at.clone();
        }

        void end() {
            for (Display display : displays) {
                fx().remove(act.owner().getUniqueId(), display);
            }
            displays.clear();
            if (trail != null) {
                trail.drain();
            }
        }
    }

    /** One flying projectile: moves {@code speed} blocks a tick, hits enemies on its path, heals allies it passes. */
    private final class Flight {
        final Act act;
        final Player owner;
        final BasicAttack attack;
        final Location position;
        final Vector direction;
        final Set<UUID> touched = new HashSet<>();
        final Body body;
        double traveled;
        int hits;
        @Nullable BukkitTask task;

        Flight(Act act, Location start, Vector direction) {
            this.act = act;
            this.owner = act.owner();
            this.attack = act.attack();
            this.position = start;
            this.direction = direction;
            this.body = new Body(act, start, direction);
        }

        void begin() {
            if (step()) {
                task = ctx().runTimer(owner, 1, 1, () -> {
                    if (!step()) {
                        end();
                    }
                });
            } else {
                end();
            }
        }

        /** One tick of flight; false when it is over. */
        boolean step() {
            double length = Math.min(attack.speed(), attack.range() - traveled);
            if (!(length > 0) || !session.isInPlay(owner)) {
                return false;
            }
            double free = reach(position, direction, length);
            Location mid = position.clone().add(direction.clone().multiply(free / 2));
            double search = free / 2 + attack.radius() + 1.5;
            List<Hit> path = new ArrayList<>();
            for (LivingEntity enemy : ctx().enemiesNear(owner, mid, search)) {
                double along = along(position, direction, free, enemy, attack.radius());
                if (along >= 0 && !touched.contains(enemy.getUniqueId())) {
                    path.add(new Hit(enemy, along));
                }
            }
            path.sort((a, b) -> Double.compare(a.along, b.along));
            double stop = free;
            boolean over = free < length;
            for (Hit hit : path) {
                touched.add(hit.entity.getUniqueId());
                hitAll(act, List.of(hit.entity), attack.damage());
                hits++;
                if (attack.pierce() > 0 && hits >= attack.pierce()) {
                    stop = hit.along;
                    over = true;
                    break;
                }
            }
            if (attack.effect() == BasicAttack.Effect.HEAL_ALLIES) {
                for (Player ally : ctx().alliesNear(owner, mid, search)) {
                    if (along(position, direction, stop, ally, attack.radius() + 0.4) >= 0
                            && touched.add(ally.getUniqueId())) {
                        healAlly(owner, attack, ally);
                    }
                }
            }
            Location next = position.clone().add(direction.clone().multiply(stop));
            Palette palette = attack.palette();
            if (displays()) {
                Vfx.dot(next, palette.particle(), null, false);
            } else {
                Vfx.line(ctx(), position, next, 2, Particle.DUST, dust(palette, 0.7f), false);
                Vfx.dot(next, palette.particle(), null, false);
            }
            position.setX(next.getX());
            position.setY(next.getY());
            position.setZ(next.getZ());
            traveled += stop;
            body.move(position);
            return !over && traveled < attack.range() - 1.0E-6;
        }

        void end() {
            if (task != null) {
                task.cancel();
                task = null;
            }
            Vfx.cloud(ctx(), position, attack.palette().particle(), 3, 0.08, 0.02, null, false);
            body.end();
        }
    }

    private record Hit(LivingEntity entity, double along) {
    }

    /** A thrown object falling in an arc; bursts on a wall, the ground or an enemy and splashes {@code radius}. */
    private void lob(Act act) {
        Player owner = act.owner();
        BasicAttack attack = act.attack();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location position = eye.clone().add(0, -0.2, 0).add(direction.clone().multiply(0.5));
        Vector velocity = direction.clone().multiply(attack.speed()).add(new Vector(0, 0.15, 0));
        Location start = position.clone();
        Body body = new Body(act, position, horizontal(direction, owner));
        BukkitTask[] task = new BukkitTask[1];
        int[] ticks = {0};
        Runnable step = () -> {
            ticks[0]++;
            double length = velocity.length();
            Vector heading = velocity.clone().normalize();
            double free = reach(position, heading, length);
            Location burst = null;
            for (LivingEntity enemy : ctx().enemiesNear(owner, position, length + 1.5)) {
                double along = along(position, heading, free, enemy, 0.3);
                if (along >= 0) {
                    burst = position.clone().add(heading.clone().multiply(along));
                    break;
                }
            }
            if (burst == null && free < length) {
                burst = position.clone().add(heading.clone().multiply(Math.max(0, free - 0.1)));
            }
            Location next = position.clone().add(heading.clone().multiply(free));
            Vfx.dot(next, attack.palette().particle(), null, false);
            position.setX(next.getX());
            position.setY(next.getY());
            position.setZ(next.getZ());
            body.move(position);
            velocity.setY(velocity.getY() - LOB_GRAVITY).multiply(0.99);
            double horizontal = Math.hypot(position.getX() - start.getX(), position.getZ() - start.getZ());
            if (burst == null && (ticks[0] >= LOB_MAX_TICKS || horizontal >= attack.range()
                    || !session.isInPlay(owner))) {
                burst = position.clone();
            }
            if (burst != null) {
                if (task[0] != null) {
                    task[0].cancel();
                }
                body.end();
                if (session.isInPlay(owner)) {
                    splash(act, burst);
                }
            }
        };
        task[0] = ctx().runTimer(owner, 1, 1, step);
    }

    /** The burst of a lob: every enemy within {@code radius}. */
    private void splash(Act act, Location at) {
        Player owner = act.owner();
        BasicAttack attack = act.attack();
        Palette palette = attack.palette();
        Location center = at.clone().add(0, 0.3, 0);
        if (displays()) {
            fx().play(owner, at, new Quaternionf(), BasicFx.splash(attack, act.moment()), act.budget());
        } else {
            Vfx.ring(ctx(), center, attack.radius(), 14, 0, Particle.DUST, dust(palette, 1.2f), false);
        }
        Vfx.cloud(ctx(), center, palette.particle(), 6, attack.radius() * 0.4, 0.05, null, false);
        if (attack.radius() >= 1.8) {
            Vfx.dot(center, Particle.EXPLOSION, null, false);
        }
        sound(center, palette.impact(), palette.impactPitch(), 0.7f);
        hitAll(act, ctx().enemiesNear(owner, center, attack.radius()), attack.damage());
        healAlliesNear(owner, attack, center, attack.radius());
    }

    /** A zap on the aimed enemy that jumps on to the nearest ones it has not hit. */
    private void chain(Act act) {
        Player owner = act.owner();
        BasicAttack attack = act.attack();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location hand = eye.clone().add(0, -0.25, 0).add(direction.clone().multiply(0.6));
        LivingEntity first = ctx().findTarget(owner, attack.range());
        if (first == null) {
            zap(act, hand, eye.clone().add(direction.multiply(reach(eye, direction, attack.range()))), 0);
            return;
        }
        zap(act, hand, Fx.body(first), 0);
        hitAll(act, List.of(first), attack.damage());
        Set<UUID> struck = new HashSet<>();
        struck.add(first.getUniqueId());
        jump(act, first, struck, 1, attack.damage() * attack.falloff());
    }

    private void jump(Act act, LivingEntity from, Set<UUID> struck, int index, double damage) {
        Player owner = act.owner();
        BasicAttack attack = act.attack();
        if (index >= attack.hits() || !(damage > 0)) {
            return;
        }
        ctx().runLater(owner, CHAIN_STEP, () -> {
            if (!session.isInPlay(owner)) {
                return;
            }
            Location origin = from.isValid() ? Fx.body(from) : from.getLocation();
            LivingEntity next = null;
            for (LivingEntity enemy : ctx().enemiesNear(owner, origin, attack.radius())) {
                if (!struck.contains(enemy.getUniqueId()) && (!from.isValid() || ctx().canSee(from, enemy))) {
                    next = enemy;
                    break;
                }
            }
            if (next == null) {
                return;
            }
            struck.add(next.getUniqueId());
            zap(act, origin, Fx.body(next), index);
            hitAll(act, List.of(next), damage);
            jump(act, next, struck, index + 1, damage * attack.falloff());
        });
    }

    /** A jagged bolt from {@code from} to {@code to}. */
    private void zap(Act act, Location from, Location to, int index) {
        BasicAttack attack = act.attack();
        Palette palette = attack.palette();
        if (displays()) {
            Vector delta = to.toVector().subtract(from.toVector());
            fx().play(act.owner(), from, new Quaternionf(), BasicFx.bolt(attack, act.moment(),
                    new Vector3f((float) delta.getX(), (float) delta.getY(), (float) delta.getZ()),
                    act.moment().seed() + index), act.budget());
            Vfx.dot(to, palette.particle(), null, false);
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vector delta = to.toVector().subtract(from.toVector());
        int segments = (int) Math.max(2, Math.min(8, Math.round(delta.length() / 1.2)));
        Location previous = from.clone();
        for (int i = 1; i <= segments; i++) {
            Location point = from.clone().add(delta.clone().multiply((double) i / segments));
            if (i < segments) {
                point.add(random.nextDouble(-0.25, 0.25), random.nextDouble(-0.25, 0.25),
                        random.nextDouble(-0.25, 0.25));
            }
            Vfx.line(ctx(), previous, point, 4, Particle.DUST, dust(palette, 0.6f), false);
            Vfx.dot(point, palette.particle(), null, false);
            previous = point;
        }
    }

    /** A short cone in front: every enemy inside (up to {@code pierce}). */
    private void cone(Act act, int index) {
        Player owner = act.owner();
        BasicAttack attack = act.attack();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        List<LivingEntity> found = new ArrayList<>();
        for (LivingEntity enemy : ctx().enemiesNear(owner, eye, attack.range())) {
            if (withinCone(eye, direction, enemy, attack.angle()) && canSee(owner, enemy)) {
                found.add(enemy);
            }
        }
        Palette palette = attack.palette();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double half = Math.toRadians(attack.angle() / 2);
        if (displays()) {
            fx().play(owner, eye.clone().subtract(0, BasicFx.AIM_DROP, 0), aimFrame(direction),
                    BasicFx.cone(attack, act.moment(), index), act.budget());
        }
        int puffs = Vfx.n(ctx(), displays() ? 4 : 10);
        World world = eye.getWorld();
        for (int i = 0; i < puffs; i++) {
            Vector spray = direction.clone()
                    .rotateAroundY(random.nextDouble(-half, half))
                    .multiply(random.nextDouble(0.8, attack.range()));
            Location at = eye.clone().add(0, -0.3 + random.nextDouble(-0.2, 0.2), 0).add(spray);
            world.spawnParticle(i % 2 == 0 ? palette.particle() : Particle.DUST, at, 1, 0, 0, 0, 0,
                    i % 2 == 0 ? null : dust(palette, 1.0f), false);
        }
        hitAll(act, limit(found, attack.pierce()), attack.damage());
    }

    /** A burst around the attacker: every enemy within {@code range}; allies inside may heal. */
    private void nova(Act act) {
        Player owner = act.owner();
        BasicAttack attack = act.attack();
        Location center = owner.getLocation().add(0, 1, 0);
        Palette palette = attack.palette();
        if (displays()) {
            fx().play(owner, center, yawFrame(owner.getLocation().getYaw()), BasicFx.nova(attack, act.moment()),
                    act.budget());
        } else {
            for (int i = 0; i < 3; i++) {
                double radius = attack.range() * (i + 1) / 3.0;
                if (i == 0) {
                    Vfx.ring(ctx(), center, radius, 10, 0, Particle.DUST, dust(palette, 1.0f), false);
                } else {
                    ctx().runLater(owner, i, () -> Vfx.ring(ctx(), center, radius, (int) Math.round(radius * 5),
                            0.3, Particle.DUST, dust(palette, 1.0f), false));
                }
            }
        }
        Vfx.ring(ctx(), center, attack.range() * 0.5, 6, 0.2, palette.particle(), null, false);
        hitAll(act, ctx().enemiesNear(owner, center, attack.range()), attack.damage());
        healAlliesNear(owner, attack, center, attack.range());
    }

    /** A blow on the aimed spot (the aimed enemy, or the block looked at) after {@code interval} ticks. */
    private void strike(Act act) {
        Player owner = act.owner();
        BasicAttack attack = act.attack();
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        LivingEntity aimed = ctx().findTarget(owner, attack.range());
        Location spot;
        if (aimed != null) {
            spot = aimed.getLocation();
        } else {
            double reach = reach(eye, direction, attack.range());
            spot = ground(eye.clone().add(direction.clone().multiply(Math.max(0, reach - 0.2))));
        }
        Palette palette = attack.palette();
        if (displays()) {
            List<FxSprite> sprites = new ArrayList<>(BasicFx.telegraph(attack, act.moment(), attack.interval()));
            BasicFxPlayer.Run run = fx().run(owner);
            run.add(sprites, spot, new Quaternionf(), act.budget());
            List<FxSprite> landing = new ArrayList<>();
            for (FxSprite sprite : BasicFx.eruption(attack, act.moment())) {
                landing.add(sprite.delayed(attack.interval()));
            }
            run.add(landing, spot, yawFrame(eye.getYaw()), act.budget());
            run.drain();
        } else {
            Vfx.ring(ctx(), spot.clone().add(0, 0.1, 0), attack.radius(), 12, 0, Particle.DUST, dust(palette, 0.9f),
                    false);
        }
        Runnable land = () -> {
            if (!session.isInPlay(owner)) {
                return;
            }
            Location center = spot.clone().add(0, 0.6, 0);
            Vfx.cloud(ctx(), center, palette.particle(), displays() ? 6 : 12, attack.radius() * 0.4, 0.05, null,
                    false);
            sound(center, palette.impact(), palette.impactPitch(), 0.7f);
            hitAll(act, ctx().enemiesNear(owner, center, attack.radius()), attack.damage());
            healAlliesNear(owner, attack, center, attack.radius());
        };
        if (attack.interval() > 0) {
            ctx().runLater(owner, attack.interval(), land);
        } else {
            land.run();
        }
    }

    // =================================================================== hits & effects

    private void hitAll(Act act, List<LivingEntity> targets, double damage) {
        for (LivingEntity target : targets) {
            if (!session.isInPlay(act.owner()) || !ctx().isEnemy(act.owner(), target)) {
                continue;
            }
            hit(act, target, damage);
        }
    }

    /** One hit: the damage, its look and sound, the side effect and the finisher's push. */
    private void hit(Act act, LivingEntity target, double damage) {
        Player owner = act.owner();
        BasicAttack attack = act.attack();
        Moment moment = act.moment();
        double amount = scaled(owner, target, damage) * act.multiplier();
        Location body = Fx.body(target);
        Location spark = impactPoint(owner.getEyeLocation(), target);
        double lost = ctx().basicHit(owner, target, amount, DamageType.PLAYER_ATTACK, act.accent());
        Palette palette = attack.palette();
        if (displays()) {
            Vector toward = body.toVector().subtract(owner.getEyeLocation().toVector()).setY(0);
            fx().play(owner, spark, toward.lengthSquared() < 1.0E-4 ? yawFrame(owner.getLocation().getYaw())
                    : aimFrame(toward), BasicFx.impact(attack, moment, moment.seed() ^ target.getUniqueId()
                    .getLeastSignificantBits()), act.budget());
            Vfx.cloud(ctx(), spark, palette.particle(), 3, 0.2, 0.03, null, false);
        } else {
            Vfx.cloud(ctx(), spark, palette.particle(), 5, 0.2, 0.03, null, false);
            Vfx.cloud(ctx(), spark, Particle.DUST, 4, 0.25, 0, dust(palette, 1.0f), false);
        }
        if (moment.crit()) {
            Vfx.cloud(ctx(), spark, Particle.CRIT, 8, 0.25, 0.25, null, false);
        }
        hitSounds(body, attack, moment);
        if (target.isDead() || !target.isValid()) {
            return;
        }
        long ticks = Math.max(1, Math.round(attack.effectSeconds() * 20));
        State state = state(owner);
        double push = moment.finisher() ? feel.finisherPush() : 0;
        switch (attack.effect()) {
            case SLOW -> timedModifier(owner, target, Attribute.MOVEMENT_SPEED, -attack.effectPower(),
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1, ticks, state.slows);
            case WEAKEN -> timedModifier(owner, target, Attribute.ATTACK_DAMAGE, -attack.effectPower(),
                    AttributeModifier.Operation.ADD_NUMBER, ticks, state.weakens);
            case DOT -> damageOverTime(owner, attack, target, ticks, state);
            case KNOCKBACK -> {
                pushAway(owner, target, attack.effectPower() + push);
                push = 0;
            }
            case PULL -> {
                push = 0;
                if (!match.isHostTarget(target)) {
                    Vector toward = owner.getLocation().toVector().subtract(target.getLocation().toVector())
                            .setY(0);
                    if (toward.lengthSquared() > 1.0) {
                        Motion.push(target, toward.normalize().multiply(attack.effectPower()).setY(0.15), 2.0);
                    }
                }
            }
            case LIFESTEAL -> {
                if (lost > 0) {
                    ctx().heal(owner, lost * attack.effectPower());
                }
            }
            case HEAL_ALLIES -> {
                if (attack.shape() != BasicAttack.Shape.PROJECTILE && attack.shape() != BasicAttack.Shape.NOVA
                        && attack.shape() != BasicAttack.Shape.LOB && attack.shape() != BasicAttack.Shape.STRIKE) {
                    healAlliesNear(owner, attack, body, 2.0);
                }
            }
            default -> {
            }
        }
        if (push > 0) {
            pushAway(owner, target, push);
        }
    }

    /**
     * Where a hit's spark shows on {@code target}: on its side facing {@code eye} (the attacker's), at about the
     * attacker's eye height within its box (a little below), never inside the body or model where nobody sees it. The
     * box is the host's for a building ({@link MatchContext#boxOf}).
     */
    Location impactPoint(Location eye, LivingEntity target) {
        BoundingBox box = ctx().boxOf(target);
        double cx = box.getCenterX();
        double cz = box.getCenterZ();
        double dx = eye.getX() - cx;
        double dz = eye.getZ() - cz;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double half = Math.min(box.getWidthX(), box.getWidthZ()) * 0.5;
        double reach = distance < 1.0E-6 ? 0 : Math.min(half, Math.max(0, distance - 0.2)) / distance;
        double low = box.getMinY() + box.getHeight() * 0.25;
        double high = box.getMinY() + box.getHeight() * 0.85;
        double y = Math.max(low, Math.min(high, eye.getY() - 0.35));
        return new Location(target.getWorld(), cx + dx * reach, y, cz + dz * reach);
    }

    /** Pushes {@code target} away from the attacker (never the host's targets: towers, minions, monsters). */
    private void pushAway(Player owner, LivingEntity target, double power) {
        if (match.isHostTarget(target) || !(power > 0)) {
            return;
        }
        Vector away = target.getLocation().toVector().subtract(owner.getLocation().toVector()).setY(0);
        away = away.lengthSquared() < 1.0E-4 ? Motion.horizontalFacing(owner.getLocation()) : away.normalize();
        Motion.push(target, away.multiply(power).setY(Math.min(0.45, 0.2 + power * 0.2)), 2.0);
    }

    /** The hit's sounds: the ability's own impact, a layer for its weight, and a ring for a finisher or critical. */
    private void hitSounds(Location at, BasicAttack attack, Moment moment) {
        Palette palette = attack.palette();
        sound(at, palette.impact(), palette.impactPitch(), 0.55f);
        Weight weight = attack.fx().weight();
        switch (weight) {
            case LIGHT -> sound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.6f, 0.35f);
            case SHARP -> sound(at, Sound.ITEM_SPEAR_HIT, 1.5f, 0.4f);
            case HEAVY -> sound(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.7f, 0.5f);
            case MAGIC -> sound(at, Sound.BLOCK_AMETHYST_CLUSTER_HIT, 1.4f, 0.45f);
        }
        if (moment.crit()) {
            sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.8f);
            sound(at, Sound.BLOCK_ANVIL_LAND, 2.0f, 0.25f);
        }
        if (moment.finisher()) {
            sound(at, weight == Weight.HEAVY ? Sound.ITEM_MACE_SMASH_GROUND : Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK,
                    weight == Weight.HEAVY ? 1.2f : 0.9f, 0.6f);
        }
    }

    /**
     * The damage of one hit: the attack's number × the configured scale × the host's factor × the attacker's attack
     * damage bonuses (added bonus scaled by the hit's share of {@link BasicAttackSettings#bonusReference()}, scalar
     * bonuses as they are; the weapon's own damage is not a bonus).
     */
    double scaled(Player owner, LivingEntity target, double damage) {
        double multiplier = host.basicAttackMultiplier(owner, target);
        if (!Double.isFinite(multiplier) || multiplier < 0) {
            multiplier = 1.0;
        }
        return damage * settings.damageScale() * multiplier * bonusFactor(owner);
    }

    /** The factor the attacker's attack damage modifiers (items, buffs, weakness) put on a basic attack. */
    double bonusFactor(Player owner) {
        AttributeInstance attack = owner.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack == null) {
            return 1.0;
        }
        double added = 0;
        double scalar = 0;
        double multiplied = 1;
        for (AttributeModifier modifier : attack.getModifiers()) {
            if (ITEM_DAMAGE.equals(modifier.getKey())) {
                continue;
            }
            switch (modifier.getOperation()) {
                case ADD_NUMBER -> added += modifier.getAmount();
                case ADD_SCALAR -> scalar += modifier.getAmount();
                case MULTIPLY_SCALAR_1 -> multiplied *= 1 + modifier.getAmount();
            }
        }
        double reference = settings.bonusReference();
        double flat = reference > 0 ? 1 + added / reference : 1;
        double factor = flat * (1 + scalar) * multiplied;
        return Double.isFinite(factor) ? Math.max(0.1, factor) : 1.0;
    }

    /** A slow or weaken from this attacker on {@code target}, replacing its previous one (never stacking). */
    private void timedModifier(Player owner, LivingEntity target, Attribute attribute, double amount,
                               AttributeModifier.Operation operation, long ticks, Map<UUID, NamespacedKey> running) {
        if (!(amount < 0)) {
            return;
        }
        NamespacedKey previous = running.remove(target.getUniqueId());
        if (previous != null) {
            ctx().removeModifier(target, previous);
        }
        running.put(target.getUniqueId(), ctx().addModifier(owner, target, attribute, amount, operation, ticks));
    }

    /** {@code effectPower} more damage over {@code effectSeconds}, in pulses; a new hit restarts it. */
    private void damageOverTime(Player owner, BasicAttack attack, LivingEntity target, long ticks, State state) {
        BukkitTask previous = state.dots.remove(target.getUniqueId());
        if (previous != null) {
            previous.cancel();
        }
        int pulses = (int) Math.max(1, ticks / DOT_PERIOD);
        double each = attack.effectPower() / pulses;
        if (!(each > 0)) {
            return;
        }
        int[] left = {pulses};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = ctx().runTimer(owner, DOT_PERIOD, DOT_PERIOD, () -> {
            if (left[0]-- <= 0 || !target.isValid() || target.isDead() || !ctx().isEnemy(owner, target)) {
                task[0].cancel();
                state.dots.remove(target.getUniqueId(), task[0]);
                return;
            }
            ctx().basicHit(owner, target, scaled(owner, target, each), DamageType.MAGIC);
            Vfx.cloud(ctx(), Fx.body(target), attack.palette().particle(), 3, 0.25, 0.01, null, false);
        });
        state.dots.put(target.getUniqueId(), task[0]);
    }

    private void healAlliesNear(Player owner, BasicAttack attack, Location center, double radius) {
        if (attack.effect() != BasicAttack.Effect.HEAL_ALLIES) {
            return;
        }
        for (Player ally : ctx().alliesNear(owner, center, radius)) {
            healAlly(owner, attack, ally);
        }
    }

    private void healAlly(Player owner, BasicAttack attack, Player ally) {
        ctx().heal(ally, attack.effectPower());
        Vfx.cloud(ctx(), Fx.body(ally), Particle.HAPPY_VILLAGER, 4, 0.3, 0, null, false);
    }

    // =================================================================== attack speed (crosshair indicator)

    /**
     * Keeps every participant's attack speed matching its basic attack's cadence while it fights with an ability
     * (the crosshair indicator fills when the next attack is ready) and removes it otherwise.
     */
    void syncAttackSpeed() {
        for (Participant participant : match.participants()) {
            Player player = participant.player();
            if (player == null) {
                continue;
            }
            boolean fighting = match.phase() == MatchPhase.FIGHT && participant.ability != null
                    && participant.abilityId != null && session.isInPlay(player);
            AttributeInstance speed = player.getAttribute(Attribute.ATTACK_SPEED);
            if (speed == null) {
                continue;
            }
            if (!fighting) {
                speed.removeModifier(speedKey);
                continue;
            }
            BasicAttack attack = settings.attack(participant.abilityId);
            double amount = 20.0 / attack.cooldown() - SWORD_ATTACK_SPEED;
            AttributeModifier current = speed.getModifier(speedKey);
            if (current != null && Math.abs(current.getAmount() - amount) < 1.0E-9) {
                continue;
            }
            speed.removeModifier(speedKey);
            speed.addModifier(new AttributeModifier(speedKey, amount, AttributeModifier.Operation.ADD_NUMBER,
                    EquipmentSlotGroup.ANY));
        }
    }

    /** The match ended: every attack speed change is undone and every display goes. */
    void dispose() {
        for (Participant participant : match.participants()) {
            Player player = participant.player();
            AttributeInstance speed = player == null ? null : player.getAttribute(Attribute.ATTACK_SPEED);
            if (speed != null) {
                speed.removeModifier(speedKey);
            }
        }
        for (State state : states.values()) {
            state.dots.values().forEach(BukkitTask::cancel);
        }
        states.clear();
        if (fx != null) {
            fx.dispose();
        }
    }

    /** The weapon's lore gets a line naming its basic attack. */
    void describe(ItemStack weapon, String abilityId) {
        BasicAttack attack = settings.attack(abilityId);
        ItemLore lore = weapon.getData(DataComponentTypes.LORE);
        List<Component> lines = new ArrayList<>(lore == null ? List.of() : lore.lines());
        lines.add(Component.empty());
        lines.add(Text.mm("<gray>좌클릭 평타: <white>" + attack.name() + "</white> <dark_gray>("
                + attack.shape().korean() + ")").decoration(TextDecoration.ITALIC, false));
        lines.add(Text.mm("<dark_gray>" + attack.description()).decoration(TextDecoration.ITALIC, false));
        if (feel.comboLength() > 1) {
            lines.add(Text.mm("<dark_gray>" + feel.comboLength() + "번째 연속 평타는 피니시 (더 세고 크게)")
                    .decoration(TextDecoration.ITALIC, false));
        }
        weapon.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
    }

    // =================================================================== geometry & looks

    private boolean displays() {
        return feel.displays();
    }

    private static Quaternionf yawFrame(float yaw) {
        return BasicFx.yawFrame(yaw);
    }

    private static Quaternionf aimFrame(Vector direction) {
        return BasicFx.aimFrame(direction.getX(), direction.getY(), direction.getZ());
    }

    /**
     * How far a ray from {@code from} gets before a solid block (at most {@code length}); the host's see-through blocks
     * (a 공성전 building's walls) do not stop it.
     */
    private double reach(Location from, Vector direction, double length) {
        World world = from.getWorld();
        if (world == null || !(length > 0)) {
            return 0;
        }
        RayTraceResult wall = ctx().wallAlong(from, direction, length);
        return wall == null ? length : Math.min(length, wall.getHitPosition().distance(from.toVector()));
    }

    /**
     * Where along a ray of {@code length} the entity's box widened by {@code radius} is entered (0 if the ray starts
     * inside), or −1 if it is missed.
     */
    private double along(Location from, Vector direction, double length, Entity entity, double radius) {
        BoundingBox box = ctx().boxOf(entity).clone().expand(radius);
        Vector start = from.toVector();
        if (box.contains(start)) {
            return 0;
        }
        if (!(length > 0)) {
            return -1;
        }
        RayTraceResult hit = box.rayTrace(start, direction, length);
        return hit == null ? -1 : hit.getHitPosition().distance(start);
    }

    private boolean onRay(Location from, Vector direction, double length, Entity entity, double radius) {
        return along(from, direction, length, entity, radius) >= 0;
    }

    /** True if the entity is within {@code angle} degrees (total) of {@code look} on the horizontal plane. */
    private boolean withinArc(Location eye, Vector look, LivingEntity entity, double angle) {
        Vector to = aimPoint(eye, entity).subtract(eye.toVector()).setY(0);
        double distance = to.length();
        if (distance < 0.8) {
            return true;
        }
        double slack = Math.toDegrees(Math.atan2(entity.getWidth() / 2, distance));
        return Math.toDegrees(look.angle(to)) <= angle / 2 + slack;
    }

    /** True if the entity is within {@code angle} degrees (total) of {@code direction}. */
    private boolean withinCone(Location eye, Vector direction, LivingEntity entity, double angle) {
        Vector to = aimPoint(eye, entity).subtract(eye.toVector());
        double distance = to.length();
        if (distance < 0.8) {
            return true;
        }
        double slack = Math.toDegrees(Math.atan2(Math.max(entity.getWidth(), entity.getHeight()) / 2, distance));
        return Math.toDegrees(direction.angle(to)) <= angle / 2 + slack;
    }

    private boolean canSee(Player owner, LivingEntity entity) {
        return ctx().canSee(owner, entity);
    }

    /**
     * Where an attack aims at {@code entity}: its body, or for a host target bigger than its hitbox (a 공성전 building)
     * the point of the building nearest {@code eye}, so a swing at its wall counts.
     */
    private Vector aimPoint(Location eye, LivingEntity entity) {
        BoundingBox box = ctx().boxOf(entity);
        if (box.equals(entity.getBoundingBox())) {
            return Fx.body(entity).toVector();
        }
        return new Vector(Math.clamp(eye.getX(), box.getMinX(), box.getMaxX()),
                Math.clamp(eye.getY(), box.getMinY(), box.getMaxY()),
                Math.clamp(eye.getZ(), box.getMinZ(), box.getMaxZ()));
    }

    /** The horizontal part of {@code direction}, or the player's facing when it looks straight up or down. */
    private static Vector horizontal(Vector direction, Player player) {
        Vector flat = direction.clone().setY(0);
        return flat.lengthSquared() < 1.0E-6 ? Motion.horizontalFacing(player.getLocation()) : flat.normalize();
    }

    /** The ground under {@code at} (up to 6 blocks down), or {@code at} itself. */
    private static Location ground(Location at) {
        World world = at.getWorld();
        RayTraceResult down = world == null ? null
                : world.rayTraceBlocks(at, new Vector(0, -1, 0), 6, FluidCollisionMode.NEVER, true);
        return down == null ? at : down.getHitPosition().toLocation(world);
    }

    private static <T> List<T> limit(List<T> list, int max) {
        return max > 0 && list.size() > max ? new ArrayList<>(list.subList(0, max)) : list;
    }

    private static Particle.DustOptions dust(Palette palette, float size) {
        return new Particle.DustOptions(palette.color(), size);
    }

    private static void sound(Location at, Sound sound, float pitch, float volume) {
        at.getWorld().playSound(at, sound, SoundCategory.PLAYERS, volume,
                Math.max(0.5f, Math.min(2.0f, pitch + Fx.jitter(0.05f))));
    }
}
