package io.github.sx2xoox.mcpl.game.siege.minion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeArena;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureLook;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureMover;
import io.github.sx2xoox.mcpl.game.siege.creature.Creatures;
import io.github.sx2xoox.mcpl.game.siege.economy.Gold;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The minion waves of 공성전 v2 (docs/siege-v2.md §2). Every {@link MinionSettings#intervalTicks()} each side sends a
 * wave down both lanes from its base: melee and ranged minions (one more melee per few players), a cannon every few
 * waves. Minions walk {@link LaneSites#lanePath} (BLUE as given, RED reversed) with the pathfinder, re-path when
 * stuck and are put back on the path when that does not help; they fight the nearest enemy minion, else an enemy
 * tower or core in reach ({@link SiegeArena#structures}), else an enemy player or bot, and never chase farther than
 * {@link MinionSettings#leash()} from the lane. Vanilla AI is off: they target nothing by themselves and hurt only
 * through their own hits.
 *
 * <p>The player who lands the killing blow gets the minion's gold ({@link Gold#settings()}); enemies of the minion
 * nearby share its experience. Minions are tracked by the match, their lanes' chunks are held loaded, and
 * {@link #clear()} removes everything at the end of a round. Towers should shoot minions first:
 * {@link #nearestMinion}.
 */
public final class MinionWaves implements Listener {

    private final SiegeArena arena;
    private final MinionSettings settings;
    private final CreatureLook look;
    private final CreatureMover mover;
    private final Gold gold;
    private final Map<Mob, Minion> minions = new LinkedHashMap<>();
    private final List<int[]> heldChunks = new ArrayList<>();
    private final Map<LaneSites.Side, Long> empoweredUntil = new EnumMap<>(LaneSites.Side.class);
    /** True while a minion's own hit is being delivered (the only damage minions deal). */
    private boolean striking;
    private long now;
    private long nextWave;
    private int wave;

    public MinionWaves(SiegeArena arena, MinionSettings settings, CreatureLook look, CreatureMover mover, Gold gold) {
        this.arena = arena;
        this.settings = settings;
        this.look = look;
        this.mover = mover;
        this.gold = gold;
    }

    /** One live minion. */
    public final class Minion {
        private final Mob body;
        private final MinionKind kind;
        private final LaneSites.Side side;
        private final LaneSites.Lane lane;
        private final List<Location> path;
        private final double damage;
        private int next = 1;
        private @Nullable LivingEntity target;
        private long nextAttack;
        private Location lastCheck;
        private int stuck;
        private long checkedAt;
        private long repathedAt = -1;
        private @Nullable Location movingTo;
        private long movedAt;
        private @Nullable UUID lastHitter;
        private boolean healthDirty = true;

        private Minion(Mob body, MinionKind kind, LaneSites.Side side, LaneSites.Lane lane, List<Location> path,
                double damage) {
            this.body = body;
            this.kind = kind;
            this.side = side;
            this.lane = lane;
            this.path = path;
            this.damage = damage;
            this.lastCheck = body.getLocation();
        }

        public Mob body() {
            return body;
        }

        public MinionKind kind() {
            return kind;
        }

        public LaneSites.Side side() {
            return side;
        }

        public LaneSites.Lane lane() {
            return lane;
        }

        /** Index of the waypoint it walks to ({@link #path()}). */
        public int nextWaypoint() {
            return next;
        }

        /** Its waypoints in walking order. */
        public List<Location> path() {
            return Collections.unmodifiableList(path);
        }

        public @Nullable LivingEntity target() {
            return target;
        }

        public double damage() {
            return damage;
        }

        /** The waypoint it last reached: the leash anchor. */
        Location anchor() {
            return path.get(Math.max(0, next - 1));
        }
    }

    // ------------------------------------------------------------------ flow

    /** The fight of a round begins: the first wave comes after {@link MinionSettings#firstWaveTicks()}. */
    public void begin() {
        clear();
        holdChunks();
        nextWave = settings.firstWaveTicks();
    }

    /** Every tick of the fight. */
    public void tick() {
        now++;
        if (settings.enabled() && now >= nextWave) {
            nextWave = now + settings.intervalTicks();
            spawnWave();
        }
        List<Minion> gone = new ArrayList<>();
        // A copy: a hit may kill another minion (its death event removes it from the map).
        for (Minion minion : new ArrayList<>(minions.values())) {
            if (minions.get(minion.body) != minion) {
                continue;
            }
            if (!minion.body.isValid() || minion.body.isDead()) {
                gone.add(minion);
                continue;
            }
            look.tick(minion.body, minion.kind.creature());
            mover.tick(minion.body);
            if ((now + minion.body.getEntityId()) % 5 == 0) {
                think(minion);
            }
            if (minion.healthDirty) {
                minion.healthDirty = false;
                Creatures.showHealth(arena, minion.body, name(minion), colour(minion.side));
            }
        }
        for (Minion minion : gone) {
            remove(minion);
        }
    }

    /** The round is over: every minion goes. */
    public void clear() {
        for (Minion minion : new ArrayList<>(minions.values())) {
            minion.body.remove();
            remove(minion);
        }
        minions.clear();
        empoweredUntil.clear();
        wave = 0;
        now = 0;
        releaseChunks();
    }

    /** Sends a wave now (both sides, both lanes): tests and admins. */
    public void spawnWave() {
        wave++;
        int teamSize = Math.max(arena.members(LaneSites.Side.BLUE).size(), arena.members(LaneSites.Side.RED).size());
        List<MinionKind> kinds = new ArrayList<>();
        for (int i = 0; i < settings.melee() + settings.extra(teamSize); i++) {
            kinds.add(MinionKind.MELEE);
        }
        for (int i = 0; i < settings.ranged(); i++) {
            kinds.add(MinionKind.RANGED);
        }
        if (settings.cannonEvery() > 0 && wave % settings.cannonEvery() == 0) {
            kinds.add(MinionKind.CANNON);
        }
        for (LaneSites.Side side : LaneSites.Side.values()) {
            for (LaneSites.Lane lane : LaneSites.Lane.values()) {
                List<Location> path = path(side, lane);
                if (path.size() < 2) {
                    continue;
                }
                for (int i = 0; i < kinds.size(); i++) {
                    spawn(kinds.get(i), side, lane, path, i, kinds.size());
                }
            }
        }
    }

    private void spawn(MinionKind kind, LaneSites.Side side, LaneSites.Lane lane, List<Location> path, int index,
            int count) {
        Location start = path.getFirst();
        Vector forward = path.get(1).toVector().subtract(start.toVector()).setY(0);
        if (forward.lengthSquared() < 1.0E-6) {
            forward = new Vector(side == LaneSites.Side.BLUE ? 1 : -1, 0, 0);
        }
        forward.normalize();
        Vector across = new Vector(-forward.getZ(), 0, forward.getX());
        // Two ranks: the melee in front, the ranged and the cannon behind.
        int row = index % 3;
        int rank = index / 3;
        Location at = start.clone().add(across.clone().multiply((row - 1) * 1.2))
                .add(forward.clone().multiply(-1.4 * rank));
        at.setDirection(forward);
        MinionSettings.Stats stats = settings.stats(kind);
        double bonus = 1 + Math.max(0, wave - 1) * settings.healthPerWave() + (empowered(side)
                ? settings.empoweredBonus() : 0);
        double health = stats.health() * bonus;
        double damage = stats.damage() * (empowered(side) ? 1 + settings.empoweredBonus() : 1);
        Component name = arena.messages().get("minion.name." + kind.key()).color(colour(side));
        try {
            Mob body = look.spawn(kind.creature(), side, at, mob -> Creatures.prepare(mob, name, health,
                    stats.scale(), settings.aggroRadius() + 8));
            arena.track(body);
            minions.put(body, new Minion(body, kind, side, lane, path, damage));
        } catch (RuntimeException e) {
            arena.plugin().getLogger().log(Level.WARNING, "Could not spawn a " + kind + " minion", e);
        }
    }

    /** The waypoints of a lane in a side's walking order, as world locations. */
    List<Location> path(LaneSites.Side side, LaneSites.Lane lane) {
        List<Location> out = new ArrayList<>();
        for (LaneSites.Point point : arena.sites().lanePath(lane)) {
            out.add(arena.at(point.x(), point.y(), point.z()));
        }
        if (side == LaneSites.Side.RED) {
            Collections.reverse(out);
        }
        return out;
    }

    // ------------------------------------------------------------------ behaviour

    private void think(Minion minion) {
        if (!arena.isFighting()) {
            mover.stop(minion.body);
            return;
        }
        MinionSettings.Stats stats = settings.stats(minion.kind);
        LivingEntity target = minion.target;
        if (target != null && !keep(minion, target)) {
            target = null;
        }
        if (target == null) {
            target = findTarget(minion);
        }
        minion.target = target;
        Location at = minion.body.getLocation();
        if (target != null) {
            double reach = stats.range() + (arena.isStructure(target) ? target.getWidth() / 2 : 0);
            if (Creatures.flatDistanceSquared(at, target.getLocation()) <= reach * reach) {
                mover.stop(minion.body);
                Creatures.face(minion.body, target.getLocation());
                if (now >= minion.nextAttack) {
                    minion.nextAttack = now + stats.attackTicks();
                    strike(minion, target);
                }
            } else {
                mover.moveTo(minion.body, target.getLocation(), stats.speed());
            }
            minion.lastCheck = at;
            minion.checkedAt = now;
            minion.stuck = 0;
            minion.movingTo = null;
            return;
        }
        walk(minion, stats, at);
    }

    /** Follows the lane: the next waypoint, re-pathing when stuck. */
    private void walk(Minion minion, MinionSettings.Stats stats, Location at) {
        double reach = settings.waypointReach();
        while (minion.next < minion.path.size()
                && (Creatures.flatDistanceSquared(at, minion.path.get(minion.next)) <= reach * reach
                || passed(minion, at))) {
            minion.next++;
        }
        if (minion.next >= minion.path.size()) {
            // At the enemy base: stand there and fight whatever comes.
            mover.stop(minion.body);
            return;
        }
        Location goal = minion.path.get(minion.next);
        if (now - minion.checkedAt >= 20) {
            if (at.distanceSquared(minion.lastCheck) < 0.25) {
                minion.stuck += (int) (now - minion.checkedAt);
            } else {
                minion.stuck = 0;
            }
            minion.lastCheck = at;
            minion.checkedAt = now;
        }
        if (minion.stuck >= settings.stuckTicks() * 2) {
            // Re-pathing did not get it out: put it on its path.
            minion.body.teleport(goal.clone().setDirection(goal.toVector().subtract(at.toVector()).setY(0)));
            minion.stuck = 0;
            minion.lastCheck = minion.body.getLocation();
            minion.movingTo = null;
            return;
        }
        boolean repath = minion.stuck >= settings.stuckTicks() && minion.repathedAt < minion.checkedAt;
        if (repath) {
            mover.stop(minion.body);
            minion.repathedAt = now;
        }
        if (repath || !goal.equals(minion.movingTo) || now - minion.movedAt >= 40) {
            minion.movingTo = goal;
            minion.movedAt = now;
            mover.moveTo(minion.body, goal, stats.speed());
        }
    }

    /**
     * Whether a fight carried the minion past its next waypoint: it is nearer the one after than that waypoint is
     * (it should not walk back).
     */
    private static boolean passed(Minion minion, Location at) {
        if (minion.next + 1 >= minion.path.size()) {
            return false;
        }
        Location next = minion.path.get(minion.next);
        Location after = minion.path.get(minion.next + 1);
        return Creatures.flatDistanceSquared(at, after) < Creatures.flatDistanceSquared(next, after);
    }

    private boolean keep(Minion minion, LivingEntity target) {
        if (!target.isValid() || target.isDead() || !target.getWorld().equals(minion.body.getWorld())) {
            return false;
        }
        if (target instanceof Player player && (!arena.canFight(player) || arena.side(player.getUniqueId())
                == minion.side)) {
            return false;
        }
        UUID fighter = target instanceof Player ? null : arena.fighterOf(target);
        if (fighter != null && (arena.side(fighter) == minion.side
                || !arena.extraFighters(enemy(minion.side)).contains(target))) {
            return false;
        }
        if (arena.isStructure(target) && !arena.structures(enemy(minion.side)).contains(target)) {
            return false;
        }
        double leash = settings.leash();
        double chase = settings.aggroRadius() + 2;
        return Creatures.flatDistanceSquared(target.getLocation(), minion.anchor()) <= leash * leash
                && Creatures.flatDistanceSquared(target.getLocation(), minion.body.getLocation()) <= chase * chase;
    }

    /** The nearest enemy minion, else a structure in reach, else the nearest enemy player (all within the leash). */
    private @Nullable LivingEntity findTarget(Minion minion) {
        Location at = minion.body.getLocation();
        double radius = settings.aggroRadius();
        LivingEntity minionTarget = nearestMinion(enemy(minion.side), at, radius);
        if (minionTarget != null && inLeash(minion, minionTarget)) {
            return minionTarget;
        }
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity structure : arena.structures(enemy(minion.side))) {
            double reach = radius + structure.getWidth() / 2;
            double distance = Creatures.flatDistanceSquared(at, structure.getLocation());
            if (structure.getWorld().equals(at.getWorld()) && distance <= reach * reach && distance < bestDistance) {
                best = structure;
                bestDistance = distance;
            }
        }
        if (best != null) {
            return best;
        }
        List<LivingEntity> fighters = new ArrayList<>(arena.fighters(enemy(minion.side)));
        fighters.addAll(arena.extraFighters(enemy(minion.side)));
        for (LivingEntity fighter : fighters) {
            double distance = fighter.getWorld().equals(at.getWorld())
                    ? Creatures.flatDistanceSquared(at, fighter.getLocation()) : Double.MAX_VALUE;
            if (distance <= radius * radius && distance < bestDistance && inLeash(minion, fighter)) {
                best = fighter;
                bestDistance = distance;
            }
        }
        return best;
    }

    private boolean inLeash(Minion minion, LivingEntity target) {
        return Creatures.flatDistanceSquared(target.getLocation(), minion.anchor())
                <= settings.leash() * settings.leash();
    }

    private void strike(Minion minion, LivingEntity target) {
        striking = true;
        try {
            Creatures.strike(arena, minion.body, target, minion.damage, minion.side, minion.kind.ranged());
        } finally {
            striking = false;
        }
    }

    // ------------------------------------------------------------------ queries

    /**
     * The nearest live minion of {@code side} within {@code radius} of {@code at} (towers shoot minions first), or
     * null.
     */
    public @Nullable Mob nearestMinion(LaneSites.Side side, Location at, double radius) {
        Mob best = null;
        double bestDistance = radius * radius;
        for (Minion minion : minions.values()) {
            if (minion.side != side || !minion.body.isValid() || minion.body.isDead()
                    || !minion.body.getWorld().equals(at.getWorld())) {
                continue;
            }
            double distance = Creatures.flatDistanceSquared(at, minion.body.getLocation());
            if (distance <= bestDistance) {
                best = minion.body;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The minion of a body, or null. */
    public @Nullable Minion minionOf(@Nullable Entity entity) {
        return entity instanceof Mob mob ? minions.get(mob) : null;
    }

    public List<Minion> minions() {
        return List.copyOf(minions.values());
    }

    /** Live minion bodies (ability targets). */
    public List<LivingEntity> bodies() {
        List<LivingEntity> out = new ArrayList<>();
        for (Mob body : minions.keySet()) {
            if (body.isValid() && !body.isDead()) {
                out.add(body);
            }
        }
        return out;
    }

    /** Whether an ability of {@code owner} may hit the minion {@code entity}: only the enemy side's. */
    public boolean isEnemy(Player owner, Entity entity) {
        Minion minion = minionOf(entity);
        LaneSites.Side side = arena.side(owner.getUniqueId());
        return minion != null && side != null && side != minion.side && arena.isFighting();
    }

    public int wave() {
        return wave;
    }

    /** Ticks since the fight began (the minion clock). */
    public long now() {
        return now;
    }

    /** The dragon buff: {@code side}'s minions spawned in the next {@code ticks} are stronger. */
    public void empower(LaneSites.Side side, int ticks) {
        empoweredUntil.put(side, now + ticks);
    }

    public boolean empowered(LaneSites.Side side) {
        Long until = empoweredUntil.get(side);
        return until != null && until > now;
    }

    // ------------------------------------------------------------------ events

    /** Minions pick no targets of their own. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (minionOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (minionOf(event.getEntity()) != null && !(event instanceof EntityCombustByEntityEvent)) {
            event.setCancelled(true);
        }
    }

    /**
     * Who may hurt whom: a minion only through its own hits; a minion is hurt by enemy players in play, enemy minions'
     * hits, structures (towers shoot minions) and burns or poisons, nothing else (falls, walls, allies).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Entity causing = event.getDamageSource().getCausingEntity();
        Minion attackerMinion = minionOf(causing);
        if (attackerMinion != null && !striking) {
            event.setCancelled(true);
            return;
        }
        Minion minion = minionOf(event.getEntity());
        if (minion == null) {
            return;
        }
        if (!arena.isFighting()) {
            event.setCancelled(true);
            return;
        }
        if (attackerMinion != null) {
            if (attackerMinion.side == minion.side) {
                event.setCancelled(true);
            }
            return;
        }
        if (causing != null && arena.isStructure(causing)) {
            return;
        }
        Player attacker = arena.attacker(event);
        UUID bot = attacker == null ? arena.fighterAttacker(event) : null;
        if (bot != null) {
            // A 봇전 bot: like a player, only one of the other side.
            if (arena.side(bot) == null || arena.side(bot) == minion.side) {
                event.setCancelled(true);
            }
            return;
        }
        if (attacker == null) {
            switch (event.getCause()) {
                case FIRE_TICK, POISON, WITHER, KILL, VOID -> {
                }
                default -> event.setCancelled(true);
            }
            return;
        }
        if (!arena.canFight(attacker) || arena.side(attacker.getUniqueId()) == minion.side) {
            event.setCancelled(true);
        }
    }

    /** Remembers who would land the killing blow, and refreshes the health bar. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        Minion minion = minionOf(event.getEntity());
        if (minion == null) {
            return;
        }
        minion.healthDirty = true;
        arena.damageNumber(minion.body, event.getFinalDamage());
        if (event.getFinalDamage() >= minion.body.getHealth()) {
            Player attacker = arena.attacker(event);
            minion.lastHitter = attacker != null ? attacker.getUniqueId() : arena.fighterAttacker(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        Minion minion = minionOf(event.getEntity());
        if (minion == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        remove(minion);
        died(minion);
    }

    /** Gold for the last hitter, experience for the minion's enemies nearby. */
    private void died(Minion minion) {
        UUID hitter = minion.lastHitter;
        if (hitter != null && arena.side(hitter) != null && arena.side(hitter) != minion.side) {
            gold.add(hitter, minion.kind == MinionKind.CANNON ? gold.settings().cannon() : gold.settings().minion(),
                    "minion");
        }
        int xp = settings.stats(minion.kind).xp();
        if (xp <= 0) {
            return;
        }
        Location at = minion.body.getLocation();
        Set<UUID> given = new HashSet<>();
        for (Player player : arena.fighters(enemy(minion.side))) {
            if (player.getWorld().equals(at.getWorld())
                    && player.getLocation().distanceSquared(at) <= settings.xpRadius() * settings.xpRadius()) {
                given.add(player.getUniqueId());
                arena.addXp(player.getUniqueId(), xp);
            }
        }
        for (LivingEntity fighter : arena.extraFighters(enemy(minion.side))) {
            UUID id = arena.fighterOf(fighter);
            if (id != null && fighter.getWorld().equals(at.getWorld())
                    && fighter.getLocation().distanceSquared(at) <= settings.xpRadius() * settings.xpRadius()
                    && given.add(id)) {
                arena.addXp(id, xp);
            }
        }
        if (hitter != null && !given.contains(hitter) && arena.side(hitter) == enemy(minion.side)) {
            arena.addXp(hitter, xp);
        }
    }

    private void remove(Minion minion) {
        minions.remove(minion.body);
        arena.untrack(minion.body);
        arena.removeOverheadBar(minion.body);
        look.removed(minion.body);
    }

    // ------------------------------------------------------------------ chunks

    /** Keeps the chunks along both lanes loaded while the round runs. */
    private void holdChunks() {
        World world = arena.world();
        Set<Long> seen = new HashSet<>();
        for (LaneSites.Lane lane : LaneSites.Lane.values()) {
            for (Location point : path(LaneSites.Side.BLUE, lane)) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int cx = (point.getBlockX() >> 4) + dx;
                        int cz = (point.getBlockZ() >> 4) + dz;
                        if (seen.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL))
                                && world.addPluginChunkTicket(cx, cz, arena.plugin())) {
                            heldChunks.add(new int[] {cx, cz});
                        }
                    }
                }
            }
        }
    }

    private void releaseChunks() {
        World world = arena.world();
        for (int[] chunk : heldChunks) {
            world.removePluginChunkTicket(chunk[0], chunk[1], arena.plugin());
        }
        heldChunks.clear();
    }

    public int heldChunks() {
        return heldChunks.size();
    }

    // ------------------------------------------------------------------ looks

    private Component name(Minion minion) {
        return arena.messages().get("minion.name." + minion.kind.key()).color(colour(minion.side));
    }

    public static TextColor colour(LaneSites.Side side) {
        return side == LaneSites.Side.BLUE ? NamedTextColor.AQUA : NamedTextColor.RED;
    }

    public static LaneSites.Side enemy(LaneSites.Side side) {
        return side == LaneSites.Side.BLUE ? LaneSites.Side.RED : LaneSites.Side.BLUE;
    }
}
