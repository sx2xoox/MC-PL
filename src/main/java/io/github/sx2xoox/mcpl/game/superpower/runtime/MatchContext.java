package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.arena.TerrainDamage;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import io.github.sx2xoox.mcpl.game.superpower.ability.Prompt;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptEndReason;
import io.github.sx2xoox.mcpl.game.superpower.ability.PromptHandle;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The {@link AbilityContext} of one superpower match, shared by all abilities. Owns everything abilities create
 * (owner-scoped tasks, tracked entities, summons, tagged projectiles and attribute modifiers) and releases it on
 * revoke and at the end of the match. It also hosts the {@link PromptChannel}, the players' voluntary input and
 * displacement ({@link InputTracker}) and the per-viewer entity filters ({@link ViewerFilters}). See
 * {@link AbilityContext} for the contracts.
 */
public final class MatchContext implements AbilityContext {

    /** Hitboxes are enlarged by this much for {@link #findTarget} (forgiving aim). */
    static final double AIM_ASSIST = 0.35;

    private final SuperpowerMatch match;
    private final GameSession session;
    private final Hub hub;
    private final AbilityGuard guard;
    private final String nonce;
    private final TerrainFx terrainFx;

    private final Map<UUID, OwnerScope> scopes = new HashMap<>();
    /** Every entity created through the context (tracked, summoned, tagged), by entity id. */
    private final Map<UUID, Entity> managed = new HashMap<>();
    private final Map<UUID, Summon> summons = new LinkedHashMap<>();
    private final Map<UUID, Tagged> projectiles = new HashMap<>();
    private final Map<NamespacedKey, ModifierRecord> modifiers = new LinkedHashMap<>();
    private final Map<UUID, Integer> knockbackSuppressed = new HashMap<>();
    /** Owners whose ultimate code is running right now (nesting depth): {@link #asUltimate}. */
    private final Map<UUID, Integer> ultimateDepth = new HashMap<>();
    /** Blocks abilities placed through {@link #placeTempBlock} and what they placed (only these can be restored early). */
    private final Map<Block, BlockData> abilityTempBlocks = new HashMap<>();
    /** Entities created by ultimate code (tracked, summoned or tagged), by entity id. */
    private final Set<UUID> ultimateEntities = new HashSet<>();
    private int abilityDamageDepth;
    private long modifierSequence;
    private boolean closed;

    /** A summoned mob and its owner. */
    record Summon(LivingEntity entity, UUID owner) {
    }

    /** A projectile tagged for an owner. */
    public record Tagged(Projectile projectile, UUID owner, String tag) {
    }

    private static final class ModifierRecord {
        final NamespacedKey key;
        final UUID targetId;
        final LivingEntity target;
        final Attribute attribute;
        final @Nullable UUID scopeOwner;
        /** The owner whose ability added it. */
        final UUID addedBy;
        final double amount;
        final AttributeModifier.Operation operation;
        @Nullable BukkitTask expiry;

        ModifierRecord(NamespacedKey key, LivingEntity target, Attribute attribute, @Nullable UUID scopeOwner,
                       UUID addedBy, double amount, AttributeModifier.Operation operation) {
            this.key = key;
            this.targetId = target.getUniqueId();
            this.target = target;
            this.attribute = attribute;
            this.scopeOwner = scopeOwner;
            this.addedBy = addedBy;
            this.amount = amount;
            this.operation = operation;
        }

        /** True for hard crowd control: the attribute cut by {@link #LOCKING_CUT} or more. */
        boolean locks() {
            return (operation == AttributeModifier.Operation.MULTIPLY_SCALAR_1
                    || operation == AttributeModifier.Operation.ADD_SCALAR) && amount <= -LOCKING_CUT;
        }
    }

    /** A movement or jump modifier at least this strong (−90%) locks it ({@link #isMovementLocked}). */
    static final double LOCKING_CUT = 0.9;
    /** Ping beyond this many milliseconds earns no more {@link #reactionSlackTicks reaction slack}. */
    static final int MAX_SLACK_PING = 350;
    /** Per-viewer filters are asked again this often (and on joins, quits and eliminations). */
    static final long VIEW_REFRESH_TICKS = 5;
    /** Failures of the context's own services (prompt channel, viewer filters) are logged under this name. */
    private static final String RUNTIME = "runtime";

    private final InputTracker inputs = new InputTracker();
    private final ViewerFilters views;
    private final PromptChannel prompts;
    /** Ability code running right now, innermost last ({@link #asOwnCode}). */
    private final ArrayDeque<OwnFrame> ownFrames = new ArrayDeque<>();

    /** One run of an owner's ability code, and the owner's velocity when it began (or after others moved it). */
    private static final class OwnFrame {
        final UUID owner;
        final @Nullable Player player;
        @Nullable Vector before;

        OwnFrame(UUID owner, @Nullable Player player) {
            this.owner = owner;
            this.player = player;
            this.before = player == null ? null : player.getVelocity();
        }
    }

    MatchContext(SuperpowerMatch match) {
        this.match = match;
        this.session = match.session();
        this.hub = session.hub();
        this.guard = match.guard();
        this.nonce = Long.toString(ThreadLocalRandom.current().nextLong(1L << 40), 36);
        this.terrainFx = new TerrainFx(session);
        this.views = new ViewerFilters(hub.plugin(), guard);
        this.prompts = new PromptChannel(match, this);
    }

    // =================================================================== basics

    @Override
    public Plugin plugin() {
        return hub.plugin();
    }

    @Override
    public AbilityRegistry registry() {
        return match.game().registry();
    }

    @Override
    public AbilityParams paramsFor(String abilityId) {
        AbilityRegistry.Entry entry = registry().get(abilityId);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown ability: " + abilityId);
        }
        return match.abilities().params(entry.info());
    }

    @Override
    public boolean isEnabled(String abilityId) {
        return match.abilities().isEnabled(abilityId);
    }

    @Override
    public long currentTick() {
        return match.fightTicks();
    }

    // =================================================================== participants

    @Override
    public Collection<Player> alivePlayers() {
        return session.alivePlayers();
    }

    @Override
    public boolean isAlive(Player player) {
        return session.isInPlay(player);
    }

    @Override
    public boolean isEnemy(Player owner, @Nullable Entity entity) {
        if (!(entity instanceof LivingEntity living) || living.isDead()) {
            return false;
        }
        UUID ownerId = owner.getUniqueId();
        if (entity instanceof Player target) {
            return !target.getUniqueId().equals(ownerId) && session.isParticipant(target) && session.isInPlay(target)
                    && !session.sameTeam(ownerId, target.getUniqueId());
        }
        if (match.isDummy(entity)) {
            // Practice training dummies are everyone's enemy.
            return living.isValid();
        }
        Summon summon = summons.get(entity.getUniqueId());
        if (summon == null || !living.isValid() || summon.owner().equals(ownerId)) {
            return false;
        }
        return session.isAlive(summon.owner()) && !session.sameTeam(ownerId, summon.owner());
    }

    @Override
    public boolean isAlly(Player owner, @Nullable Entity entity) {
        if (!(entity instanceof LivingEntity living) || living.isDead()) {
            return false;
        }
        UUID ownerId = owner.getUniqueId();
        if (entity instanceof Player target) {
            return !target.getUniqueId().equals(ownerId) && session.isTeamMode() && session.isParticipant(target)
                    && session.isInPlay(target) && session.sameTeam(ownerId, target.getUniqueId());
        }
        Summon summon = summons.get(entity.getUniqueId());
        if (summon == null || !living.isValid()) {
            return false;
        }
        if (summon.owner().equals(ownerId)) {
            return true;
        }
        return session.isTeamMode() && session.isAlive(summon.owner()) && session.sameTeam(ownerId, summon.owner());
    }

    @Override
    public List<LivingEntity> enemiesNear(Player owner, Location center, double radius) {
        List<Ranked> found = new ArrayList<>();
        if (!(radius >= 0) || center.getWorld() == null) {
            return new ArrayList<>();
        }
        for (LivingEntity candidate : candidates()) {
            if (!center.getWorld().equals(candidate.getWorld()) || !isEnemy(owner, candidate)) {
                continue;
            }
            double distance = distanceToBox(center, candidate.getBoundingBox());
            if (distance <= radius) {
                found.add(new Ranked(candidate, distance));
            }
        }
        found.sort(Comparator.comparingDouble(Ranked::distance));
        List<LivingEntity> out = new ArrayList<>(found.size());
        for (Ranked ranked : found) {
            out.add(ranked.entity());
        }
        return out;
    }

    @Override
    public List<Player> alliesNear(Player owner, Location center, double radius) {
        List<Ranked> found = new ArrayList<>();
        if (!(radius >= 0) || center.getWorld() == null) {
            return new ArrayList<>();
        }
        for (Player player : session.alivePlayers()) {
            if (!center.getWorld().equals(player.getWorld()) || !isAlly(owner, player)) {
                continue;
            }
            double distance = distanceToBox(center, player.getBoundingBox());
            if (distance <= radius) {
                found.add(new Ranked(player, distance));
            }
        }
        found.sort(Comparator.comparingDouble(Ranked::distance));
        List<Player> out = new ArrayList<>(found.size());
        for (Ranked ranked : found) {
            out.add((Player) ranked.entity());
        }
        return out;
    }

    private record Ranked(LivingEntity entity, double distance) {
    }

    /** Alive participants, live summons and practice dummies: everything that can ever be an enemy. */
    private List<LivingEntity> candidates() {
        List<LivingEntity> all = new ArrayList<>(session.alivePlayers());
        for (Summon summon : summons.values()) {
            if (summon.entity().isValid() && !summon.entity().isDead()) {
                all.add(summon.entity());
            }
        }
        all.addAll(match.liveDummies());
        return all;
    }

    /** Distance from a point to the closest point of a box (0 inside). */
    static double distanceToBox(Location point, BoundingBox box) {
        double dx = Math.max(Math.max(box.getMinX() - point.getX(), 0), point.getX() - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - point.getY(), 0), point.getY() - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - point.getZ(), 0), point.getZ() - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public @Nullable LivingEntity findTarget(Player owner, double range) {
        if (!(range > 0)) {
            return null;
        }
        Location eye = owner.getEyeLocation();
        World world = eye.getWorld();
        Vector start = eye.toVector();
        Vector direction = eye.getDirection();
        if (world == null || direction.lengthSquared() < 1.0E-8) {
            return null;
        }
        direction.normalize();
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates()) {
            if (!world.equals(candidate.getWorld()) || !isEnemy(owner, candidate)) {
                continue;
            }
            // Aiming never locks onto (and so never reveals) a player hidden from the owner by stealth.
            if (candidate instanceof Player hidden && !owner.canSee(hidden)) {
                continue;
            }
            BoundingBox box = candidate.getBoundingBox().expand(AIM_ASSIST);
            double distance;
            if (box.contains(start)) {
                distance = 0;
            } else {
                RayTraceResult hit = box.rayTrace(start, direction, range);
                if (hit == null) {
                    continue;
                }
                distance = hit.getHitPosition().distance(start);
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        if (best == null) {
            return null;
        }
        if (bestDistance > 0) {
            RayTraceResult wall = world.rayTraceBlocks(eye, direction, bestDistance, FluidCollisionMode.NEVER, true);
            if (wall != null && wall.getHitBlock() != null) {
                return null;
            }
        }
        return best;
    }

    @Override
    public @Nullable Player resolveAttacker(@Nullable Entity damager) {
        Player player = hub.combat().resolveAttacker(damager);
        return player != null && session.isParticipant(player) ? player : null;
    }

    @Override
    public @Nullable Ability abilityOf(Player player) {
        Participant participant = match.participant(player.getUniqueId());
        return participant != null && session.isInPlay(player) ? participant.ability : null;
    }

    // =================================================================== combat

    @Override
    public void damage(Player source, LivingEntity target, double amount) {
        damage(source, target, amount, DamageType.MAGIC);
    }

    @Override
    public void damage(Player source, LivingEntity target, double amount, DamageType type) {
        if (closed || !(amount > 0) || !Double.isFinite(amount) || !session.isAlive(source)
                || !isEnemy(source, target)) {
            return;
        }
        DamageSource damageSource = DamageSource.builder(type)
                .withCausingEntity(source)
                .withDirectEntity(source)
                .build();
        deliver(target, amount, damageSource);
    }

    /**
     * Deals damage that ignores the target's invulnerability frames (and leaves them as they were, including the
     * damage the next melee hit is compared with, so ability hits never swallow or weaken a following melee hit) and
     * applies no vanilla knockback. Re-entrant: nested deliveries (a hook dealing damage) keep
     * {@link #isAbilityDamage()} true.
     */
    void deliver(LivingEntity target, double amount, DamageSource damageSource) {
        UUID id = target.getUniqueId();
        int previousNoDamageTicks = target.getNoDamageTicks();
        double previousLastDamage = target.getLastDamage();
        target.setNoDamageTicks(0);
        abilityDamageDepth++;
        knockbackSuppressed.merge(id, 1, Integer::sum);
        try {
            target.damage(amount, damageSource);
        } finally {
            abilityDamageDepth--;
            knockbackSuppressed.computeIfPresent(id, (key, count) -> count <= 1 ? null : count - 1);
            if (!target.isDead()) {
                target.setNoDamageTicks(previousNoDamageTicks);
                target.setLastDamage(previousLastDamage);
            }
        }
    }

    /** Border damage: no attacker (the combat tracker credits the last hit), ignores invulnerability frames. */
    void borderDamage(Player player, double amount) {
        if (!(amount > 0)) {
            return;
        }
        int previous = player.getNoDamageTicks();
        double previousLastDamage = player.getLastDamage();
        player.setNoDamageTicks(0);
        player.damage(amount, DamageSource.builder(DamageType.OUTSIDE_BORDER).build());
        if (!player.isDead()) {
            player.setNoDamageTicks(previous);
            player.setLastDamage(previousLastDamage);
        }
    }

    @Override
    public boolean isAbilityDamage() {
        return abilityDamageDepth > 0;
    }

    /** True while an ability damage call is hitting {@code entity} (its vanilla knockback is cancelled). */
    boolean suppressesKnockback(Entity entity) {
        return knockbackSuppressed.containsKey(entity.getUniqueId());
    }

    @Override
    public void heal(LivingEntity target, double amount) {
        if (!(amount > 0) || target.isDead() || !target.isValid()) {
            return;
        }
        double max = maxHealth(target);
        double health = target.getHealth();
        if (health < max) {
            target.setHealth(Math.min(max, health + amount));
        }
    }

    @Override
    public void addGauge(Player player, double amount) {
        match.addGauge(player, amount);
    }

    @Override
    public void addEarnedGauge(Player player, double amount) {
        // Like gauge from damage: an ultimate never refills its own gauge, and the post-ultimate lock drops it.
        if (amount > 0 && !isUltimateOrigin(player.getUniqueId())) {
            match.addEarnedGauge(player, amount);
        }
    }

    @Override
    public boolean isGaugeLocked(Player player) {
        return match.isGaugeLocked(player);
    }

    @Override
    public double gauge(Player player) {
        return match.gauge(player);
    }

    @Override
    public void reduceCooldown(Player player, double seconds) {
        match.reduceCooldown(player, seconds);
    }

    @Override
    public double cooldownRemaining(Player player) {
        return match.cooldownRemaining(player);
    }

    @Override
    public void silence(Player target, long ticks) {
        match.silence(target, ticks);
    }

    @Override
    public boolean isSilenced(Player player) {
        return match.isSilenced(player);
    }

    // =================================================================== arena & messaging

    @Override
    public boolean isInsideArena(Location location) {
        return session.arena().contains(location) && !match.border().isOutside(location);
    }

    @Override
    public void announce(Component message) {
        session.broadcast(match.messages().prefix().append(message));
    }

    @Override
    public double effectScale() {
        return match.settings().particleScale();
    }

    // =================================================================== prompts

    @Override
    public PromptHandle prompt(Player owner, Player target, Prompt prompt) {
        return prompts.submit(owner, target, prompt);
    }

    @Override
    public boolean hasShownPrompt(Player player) {
        return prompts.hasShown(player);
    }

    @Override
    public int reactionSlackTicks(Player player) {
        return reactionSlack(player.getPing());
    }

    /** {@code 1 + ceil(ping / 50 ms)} with the ping clamped to {@code 0..}{@link #MAX_SLACK_PING}. */
    static int reactionSlack(int pingMillis) {
        int ping = Math.clamp(pingMillis, 0, MAX_SLACK_PING);
        return 1 + (ping + 49) / 50;
    }

    // =================================================================== voluntary input

    @Override
    public long lastPressTick(Player player, InputKey key) {
        return inputs.lastPress(player.getUniqueId(), key);
    }

    @Override
    public long lastReleaseTick(Player player, InputKey key) {
        return inputs.lastRelease(player.getUniqueId(), key);
    }

    @Override
    public boolean usedVoluntarily(Player player, InputKey key, long sinceTick, @Nullable Player ruleOwner) {
        return inputs.usedVoluntarily(player, key, sinceTick, currentTick(),
                ruleOwner == null ? null : ruleOwner.getUniqueId());
    }

    @Override
    public long lastSwingTick(Player player) {
        return inputs.lastSwing(player.getUniqueId());
    }

    @Override
    public long lastAttackTick(Player player) {
        return inputs.lastAttack(player.getUniqueId());
    }

    @Override
    public long lastDisplacedTick(Player player) {
        return inputs.lastDisplaced(player.getUniqueId());
    }

    @Override
    public boolean wasDisplaced(Player player, long withinTicks) {
        long at = inputs.lastDisplaced(player.getUniqueId());
        return at >= 0 && currentTick() - at <= withinTicks;
    }

    @Override
    public void setVoluntaryVelocity(Player target, Vector velocity) {
        inputs.expectVoluntary(target.getUniqueId(), velocity, currentTick());
        target.setVelocity(velocity);
    }

    @Override
    public boolean isMovementLocked(Player player, @Nullable Player ignoring) {
        return isLocked(player, Attribute.MOVEMENT_SPEED, ignoring);
    }

    @Override
    public boolean isJumpLocked(Player player, @Nullable Player ignoring) {
        return isLocked(player, Attribute.JUMP_STRENGTH, ignoring);
    }

    private boolean isLocked(Player player, Attribute attribute, @Nullable Player ignoring) {
        UUID id = player.getUniqueId();
        UUID ignored = ignoring == null ? null : ignoring.getUniqueId();
        for (ModifierRecord record : modifiers.values()) {
            if (record.attribute == attribute && record.targetId.equals(id) && !record.addedBy.equals(ignored)
                    && record.locks()) {
                return true;
            }
        }
        return false;
    }

    /** True for a movement or jump lock on a player (recorded for the input exemptions). */
    private static boolean isInputLock(ModifierRecord record) {
        return record.target instanceof Player && record.locks()
                && (record.attribute == Attribute.MOVEMENT_SPEED || record.attribute == Attribute.JUMP_STRENGTH);
    }

    /** Input from a client ({@link MatchListener}). */
    void recordInput(Player player, Input input) {
        inputs.input(player, input, currentTick());
    }

    void recordSwing(Player player) {
        inputs.swing(player.getUniqueId(), currentTick());
    }

    /** {@code player} tried a melee attack, or shot or threw a projectile with an item (not from ability code). */
    void recordAttack(Player player) {
        inputs.attack(player.getUniqueId(), currentTick());
    }

    /**
     * The server sends {@code player} a velocity (pushes, launches, knockback; voluntary ones are recognised). A
     * velocity sent while the player's own ability code runs (a test server sends it at once) is its own movement.
     */
    void recordVelocity(Player player, Vector velocity) {
        if (isOwnCode(player)) {
            return;
        }
        inputs.velocity(player.getUniqueId(), velocity, currentTick());
    }

    /** {@code player} was moved by something else (knockback, a teleport); not while its own ability code runs. */
    void recordDisplaced(Player player) {
        if (isOwnCode(player)) {
            return;
        }
        inputs.displaced(player.getUniqueId(), currentTick());
    }

    // =================================================================== own code (whose ability is running)

    /**
     * Runs {@code action} as code of {@code owner}'s ability (a hook, a task, a prompt callback): movement it gives the
     * owner itself (a dash, a blink, a leap) is the owner's own and never counts as displacement. Nested code of
     * another owner that moves this owner is noticed and still counts.
     */
    <T> T asOwnCode(UUID owner, Supplier<T> action) {
        Player player = Bukkit.getPlayer(owner);
        OwnFrame outer = ownFrames.peekLast();
        Player outerPlayer = outer == null || outer.owner.equals(owner) ? null : outer.player;
        Vector outerBefore = outerPlayer == null ? null : outerPlayer.getVelocity();
        OwnFrame frame = new OwnFrame(owner, player);
        ownFrames.addLast(frame);
        try {
            return action.get();
        } finally {
            // By identity: robust even if the code ended the match in between.
            ownFrames.removeLastOccurrence(frame);
            if (player != null && frame.before != null) {
                Vector after = player.getVelocity();
                if (!sameVelocity(after, frame.before)) {
                    // On a server the velocity is sent (and its event fired) later in the tick: announce it.
                    inputs.expectVoluntary(owner, after, currentTick());
                }
            }
            if (outer != null && outerPlayer != null && outerBefore != null) {
                Vector now = outerPlayer.getVelocity();
                if (!sameVelocity(now, outerBefore)) {
                    // This code moved the outer owner: not the outer owner's own movement.
                    outer.before = now;
                }
            }
        }
    }

    /** {@link #asOwnCode} for code without a result. */
    void runOwnCode(UUID owner, Runnable action) {
        asOwnCode(owner, () -> {
            action.run();
            return null;
        });
    }

    /** True while {@code player}'s own ability code runs (innermost). */
    boolean isOwnCode(Player player) {
        OwnFrame frame = ownFrames.peekLast();
        return frame != null && frame.owner.equals(player.getUniqueId());
    }

    private static boolean sameVelocity(Vector a, Vector b) {
        return a.distanceSquared(b) < 1.0E-12;
    }

    // =================================================================== per-viewer visibility

    @Override
    public void showOnlyTo(Player owner, Entity entity, Predicate<? super Player> viewers) {
        UUID ownerId = owner.getUniqueId();
        if (closed || !match.canOwnResources(ownerId)) {
            // The owner is out: whatever it shows is being removed; never let it flash for everyone meanwhile.
            views.hideFromAll(entity);
            return;
        }
        views.add(ownerId, match.abilityIdOf(ownerId), entity, viewers);
    }

    @Override
    public boolean isConcealedFrom(LivingEntity target, Player viewer) {
        if (viewer.equals(target)) {
            return false;
        }
        if (!viewer.canSee(target)) {
            return true;
        }
        boolean invisible = target.isInvisible() || target.hasPotionEffect(PotionEffectType.INVISIBILITY);
        return invisible && session.isInPlay(viewer) && !isAlly(viewer, target);
    }

    @Override
    public void refreshViewers() {
        guard.run(RUNTIME, "viewer filters", views::refreshAll);
    }

    @Override
    public boolean isSpectator(Player player) {
        // Not session.isSpectator: a participant who is only dead for a moment or respawning still plays.
        return player.isOnline() && session.isMember(player) && !session.isAlive(player);
    }

    ViewerFilters views() {
        return views;
    }

    /** A player joined the server: its new client sees no filtered entity until accepted. */
    void viewerJoined(Player player) {
        views.joined(player);
    }

    void viewerQuit(Player player) {
        views.quit(player);
    }

    // =================================================================== owner-scoped resources

    @Override
    public BukkitTask runLater(Player owner, long delayTicks, Runnable task) {
        UUID ownerId = owner.getUniqueId();
        String abilityId = match.abilityIdOf(ownerId);
        boolean fromUltimate = isUltimateOrigin(ownerId);
        BukkitTask scheduled = session.runLater(delayTicks,
                () -> runAs(ownerId, fromUltimate, () -> guard.run(abilityId, "task", task)));
        scope(ownerId).addTask(scheduled);
        return scheduled;
    }

    @Override
    public BukkitTask runTimer(Player owner, long delayTicks, long periodTicks, Runnable task) {
        UUID ownerId = owner.getUniqueId();
        String abilityId = match.abilityIdOf(ownerId);
        boolean fromUltimate = isUltimateOrigin(ownerId);
        BukkitTask scheduled = session.runTimer(delayTicks, periodTicks,
                () -> runAs(ownerId, fromUltimate, () -> guard.run(abilityId, "timer", task)));
        scope(ownerId).addTask(scheduled);
        return scheduled;
    }

    @Override
    public <T extends Entity> T track(Player owner, T entity) {
        if (entity instanceof Player) {
            throw new IllegalArgumentException("Players cannot be tracked");
        }
        session.track(entity);
        hub.combat().registerOwner(entity, owner);
        managed.put(entity.getUniqueId(), entity);
        if (isUltimateOrigin(owner.getUniqueId())) {
            ultimateEntities.add(entity.getUniqueId());
        }
        if (!scope(owner.getUniqueId()).addEntity(entity)) {
            release(entity);
        }
        return entity;
    }

    @Override
    public <T extends LivingEntity> T summon(Player owner, T mob) {
        summons.put(mob.getUniqueId(), new Summon(mob, owner.getUniqueId()));
        track(owner, mob);
        if (mob instanceof Mob creature && creature.getTarget() != null && !isEnemy(owner, creature.getTarget())) {
            creature.setTarget(null);
        }
        return mob;
    }

    @Override
    public <T extends Projectile> T tagProjectile(Player owner, T projectile, String tag) {
        projectiles.put(projectile.getUniqueId(), new Tagged(projectile, owner.getUniqueId(), tag));
        track(owner, projectile);
        return projectile;
    }

    @Override
    public NamespacedKey addModifier(Player owner, LivingEntity target, Attribute attribute, double amount,
                                     AttributeModifier.Operation operation, long durationTicks) {
        NamespacedKey key = hub.key("sp_" + nonce + "_" + (++modifierSequence));
        AttributeInstance instance = target.getAttribute(attribute);
        if (instance == null || closed || !Double.isFinite(amount)) {
            return key;
        }
        instance.addModifier(new AttributeModifier(key, amount, operation, EquipmentSlotGroup.ANY));
        if (attribute == Attribute.MAX_HEALTH) {
            clampHealth(target);
        }
        ModifierRecord record = new ModifierRecord(key, target, attribute,
                durationTicks > 0 ? null : owner.getUniqueId(), owner.getUniqueId(), amount, operation);
        modifiers.put(key, record);
        if (isInputLock(record)) {
            inputs.lockStarted(record.targetId, key, record.addedBy, record.attribute == Attribute.JUMP_STRENGTH,
                    currentTick());
        }
        if (durationTicks > 0) {
            record.expiry = session.runLater(durationTicks, () -> removeModifier(key));
        } else if (!scope(owner.getUniqueId()).addModifier(key)) {
            removeModifier(key);
        }
        return key;
    }

    @Override
    public void removeModifier(LivingEntity target, NamespacedKey key) {
        ModifierRecord record = modifiers.get(key);
        if (record != null) {
            removeModifier(key);
            return;
        }
        boolean removed = false;
        for (Attribute attribute : Registry.ATTRIBUTE) {
            AttributeInstance instance = target.getAttribute(attribute);
            if (instance != null && instance.getModifier(key) != null) {
                instance.removeModifier(key);
                removed = true;
            }
        }
        if (removed) {
            clampHealth(target);
        }
    }

    private void removeModifier(NamespacedKey key) {
        ModifierRecord record = modifiers.remove(key);
        if (record == null) {
            return;
        }
        if (isInputLock(record)) {
            inputs.lockEnded(record.targetId, key, currentTick());
        }
        if (record.expiry != null) {
            record.expiry.cancel();
        }
        if (record.scopeOwner != null) {
            OwnerScope scope = scopes.get(record.scopeOwner);
            if (scope != null) {
                scope.forgetModifier(key);
            }
        }
        LivingEntity target = record.target;
        if (!target.isValid() && Bukkit.getEntity(record.targetId) instanceof LivingEntity live) {
            target = live;
        }
        AttributeInstance instance = target.getAttribute(record.attribute);
        if (instance != null && instance.getModifier(key) != null) {
            instance.removeModifier(key);
        }
        if (record.attribute == Attribute.MAX_HEALTH) {
            clampHealth(target);
        }
    }

    // =================================================================== match-scoped effects

    @Override
    public boolean placeTempBlock(Block block, BlockData data, long durationTicks) {
        if (closed) {
            return false;
        }
        boolean placed = session.placeTempBlock(block, data, durationTicks);
        if (placed) {
            abilityTempBlocks.put(block, block.getBlockData());
        }
        return placed;
    }

    @Override
    public boolean restoreTempBlock(Block block) {
        BlockData placed = closed ? null : abilityTempBlocks.remove(block);
        if (placed == null || !hub.tempBlocks().isTemporary(block) || !block.getBlockData().equals(placed)) {
            return false;
        }
        return hub.tempBlocks().restore(block);
    }

    @Override
    public int breakBlocks(Player owner, Location center, double radius) {
        if (closed || !(radius > 0) || !session.isAlive(owner) || center.getWorld() == null) {
            return 0;
        }
        List<TerrainDamage.Broken> broken = session.breakBlocks(center, radius, this::isInsideArena);
        terrainFx.play(center, broken, effectScale());
        return broken.size();
    }

    @Override
    public int breakBlocksAlong(Player owner, Location from, Location to, double radius) {
        if (closed || !(radius > 0) || !session.isAlive(owner) || from.getWorld() == null) {
            return 0;
        }
        // A charge never digs into the floor it runs on: nothing more than a block below its line breaks.
        double floor = Math.min(from.getY(), to.getY()) - 1.0 - 1.0E-6;
        List<TerrainDamage.Broken> broken = session.breakBlocksAlong(from, to, radius,
                at -> at.getY() >= floor && isInsideArena(at));
        Location middle = from.clone().add(to.clone().subtract(from).multiply(0.5));
        terrainFx.play(middle, broken, effectScale());
        return broken.size();
    }

    // =================================================================== framework side

    /** Opens the owner's resource scope (at grant). */
    void openScope(UUID owner) {
        OwnerScope existing = scopes.get(owner);
        if (existing == null || existing.isClosed()) {
            scopes.put(owner, new OwnerScope(owner, closed));
        }
    }

    private OwnerScope scope(UUID owner) {
        return scopes.computeIfAbsent(owner, id -> new OwnerScope(id, closed || !match.canOwnResources(id)));
    }

    /** Releases everything the owner's ability created (revoke). */
    void releaseOwner(UUID owner) {
        OwnerScope scope = scopes.computeIfAbsent(owner, id -> new OwnerScope(id, true));
        OwnerScope.Released released = scope.close();
        for (Entity entity : released.entities()) {
            release(entity);
        }
        for (NamespacedKey key : released.modifiers()) {
            removeModifier(key);
        }
        // Summons or projectiles registered for this owner but tracked elsewhere.
        for (Summon summon : List.copyOf(summons.values())) {
            if (summon.owner().equals(owner)) {
                release(summon.entity());
            }
        }
        for (Tagged tagged : List.copyOf(projectiles.values())) {
            if (tagged.owner().equals(owner)) {
                release(tagged.projectile());
            }
        }
        guard.run(RUNTIME, "prompt channel", () -> prompts.endOwnedBy(owner, PromptEndReason.OWNER_REVOKED));
        views.releaseOwner(owner);
    }

    /**
     * One fight tick, after the abilities' own ticks: riders are exempt from input rules, then the prompt channel and
     * the per-viewer filters.
     */
    void tickRuntime(long now) {
        for (Player player : session.alivePlayers()) {
            if (player.isInsideVehicle()) {
                inputs.riding(player.getUniqueId(), now);
            }
        }
        guard.run(RUNTIME, "prompt channel", prompts::tick);
        if (now % VIEW_REFRESH_TICKS == 0) {
            guard.run(RUNTIME, "viewer filters", views::refreshAll);
        }
    }

    /**
     * The end of a server tick ({@code ServerTickEndEvent}): the shown prompts take the action bar back from anything
     * sent later in the tick than the match's own tick (ability timers, events).
     */
    void serverTickEnd() {
        guard.run(RUNTIME, "prompt channel", prompts::reassertActionBars);
    }

    /** Who plays changed without a join, quit or elimination (a respawn, a practice join): filters are asked again. */
    void viewersChanged() {
        guard.run(RUNTIME, "viewer filters", views::refreshAll);
    }

    /**
     * {@code player} left play (eliminated, dead, quit, spectating): the prompts for it end and every filter is asked
     * again (an eliminated ally may no longer see team markers, a new spectator may see more).
     */
    void leftPlay(Player player) {
        guard.run(RUNTIME, "prompt channel", () -> prompts.endTargeting(player.getUniqueId(),
                PromptEndReason.TARGET_LEFT));
        guard.run(RUNTIME, "viewer filters", views::refreshAll);
    }

    /**
     * The match is ending: every prompt ends with {@link PromptEndReason#MATCH_END} before abilities are revoked, and
     * later submissions (also from those prompts' {@code onEnd}) end the same way.
     */
    void matchEnding() {
        guard.run(RUNTIME, "prompt channel", prompts::close);
    }

    /** Prompts active (shown or waiting) for {@code player} (tests/diagnostics). */
    int activePrompts(Player player) {
        return prompts.activeCount(player);
    }

    /** Number of live per-viewer filters (tests/diagnostics). */
    int viewerFilterCount() {
        return views.size();
    }

    private void release(Entity entity) {
        UUID id = entity.getUniqueId();
        managed.remove(id);
        summons.remove(id);
        projectiles.remove(id);
        ultimateEntities.remove(id);
        hub.combat().unregisterOwner(entity);
        session.untrack(entity);
        if (!(entity instanceof Player)) {
            Entity live = entity.isValid() ? entity : Bukkit.getEntity(id);
            if (live != null && !(live instanceof Player)) {
                live.remove();
            }
        }
    }

    /** Releases everything (match end). Later registrations are released immediately. */
    void dispose() {
        guard.run(RUNTIME, "prompt channel", prompts::close);
        closed = true;
        for (UUID owner : List.copyOf(scopes.keySet())) {
            releaseOwner(owner);
        }
        for (Summon summon : List.copyOf(summons.values())) {
            release(summon.entity());
        }
        for (Tagged tagged : List.copyOf(projectiles.values())) {
            release(tagged.projectile());
        }
        for (NamespacedKey key : List.copyOf(modifiers.keySet())) {
            removeModifier(key);
        }
        knockbackSuppressed.clear();
        ultimateEntities.clear();
        views.dispose();
        inputs.clear();
    }

    /** Forgets entries of entities that died or were removed. */
    void prune() {
        summons.values().removeIf(summon -> summon.entity().isDead());
        projectiles.values().removeIf(tagged -> tagged.projectile().isDead());
        managed.values().removeIf(entity -> {
            if (!entity.isDead()) {
                return false;
            }
            hub.combat().unregisterOwner(entity);
            return true;
        });
        ultimateEntities.retainAll(managed.keySet());
        for (ModifierRecord record : List.copyOf(modifiers.values())) {
            if (!(record.target instanceof Player) && record.target.isDead()) {
                if (record.expiry != null) {
                    record.expiry.cancel();
                }
                modifiers.remove(record.key);
            }
        }
    }

    // =================================================================== ultimate origin

    /**
     * Runs {@code action} as ultimate code of {@code owner}: damage the owner deals meanwhile, tasks it schedules
     * through the context (and their tasks, transitively) and entities it tracks, summons or tags all count as part of
     * the ultimate, so they never charge the owner's gauge.
     */
    <T> T asUltimate(UUID owner, Supplier<T> action) {
        ultimateDepth.merge(owner, 1, Integer::sum);
        try {
            return action.get();
        } finally {
            ultimateDepth.computeIfPresent(owner, (key, depth) -> depth <= 1 ? null : depth - 1);
        }
    }

    /**
     * Runs {@code action} as code of {@code owner}'s ability ({@link #asOwnCode}), as ultimate code when
     * {@code ultimate} is true.
     */
    void runAs(UUID owner, boolean ultimate, Runnable action) {
        if (!ultimate) {
            runOwnCode(owner, action);
            return;
        }
        asUltimate(owner, () -> {
            runOwnCode(owner, action);
            return null;
        });
    }

    /** True while ultimate code of {@code owner} is running (see {@link #asUltimate}). */
    boolean isUltimateOrigin(UUID owner) {
        return ultimateDepth.containsKey(owner);
    }

    /** True for an entity created by ultimate code (a summon, a tagged projectile or a tracked entity). */
    boolean isUltimateEntity(@Nullable Entity entity) {
        return entity != null && ultimateEntities.contains(entity.getUniqueId());
    }

    /**
     * True if a hit credited to {@code attacker} belongs to the attacker's ultimate: dealt while its ultimate code
     * runs, or by an entity the ultimate created (direct or causing entity).
     */
    boolean isUltimateHit(EntityDamageEvent event, UUID attacker) {
        if (isUltimateOrigin(attacker)) {
            return true;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity && isUltimateEntity(byEntity.getDamager())) {
            return true;
        }
        return isUltimateEntity(event.getDamageSource().getCausingEntity())
                || isUltimateEntity(event.getDamageSource().getDirectEntity());
    }

    /** The owner of a summon, or null. */
    @Nullable UUID summonOwner(Entity entity) {
        Summon summon = summons.get(entity.getUniqueId());
        return summon == null ? null : summon.owner();
    }

    /** True for entities created through the context (tracked, summoned or tagged). */
    boolean isManaged(Entity entity) {
        return managed.containsKey(entity.getUniqueId());
    }

    /** The tag of a projectile tagged through the context, or null. */
    @Nullable Tagged tagged(Entity entity) {
        return projectiles.get(entity.getUniqueId());
    }

    /** Number of live summons (diagnostics/tests). */
    int summonCount() {
        return summons.size();
    }

    /** Number of modifiers currently applied through the context (diagnostics/tests). */
    int modifierCount() {
        return modifiers.size();
    }

    static double maxHealth(LivingEntity entity) {
        AttributeInstance instance = entity.getAttribute(Attribute.MAX_HEALTH);
        return instance == null ? 20.0 : instance.getValue();
    }

    static void clampHealth(LivingEntity entity) {
        if (entity.isDead()) {
            return;
        }
        double max = maxHealth(entity);
        if (entity.getHealth() > max) {
            entity.setHealth(Math.max(0.5, max));
        }
    }
}
