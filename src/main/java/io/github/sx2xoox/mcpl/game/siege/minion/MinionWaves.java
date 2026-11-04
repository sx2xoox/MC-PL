package io.github.sx2xoox.mcpl.game.siege.minion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
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
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureSounds;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureMover;
import io.github.sx2xoox.mcpl.game.siege.creature.Creatures;
import io.github.sx2xoox.mcpl.game.siege.economy.Gold;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
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
 * waves. Minions walk {@link LaneSites#lanePath} (BLUE as given, RED reversed) with the pathfinder in a formation
 * ({@link Formation}: melee rows in front, the ranged row and the cannon behind, each on its own file), re-path when
 * stuck and are put back on the path when that does not help; they fight an enemy minion (the one facing their file
 * that the fewest allies fight; melee from just in front of it, ranged from the end of their range), else an enemy
 * tower or core in reach ({@link SiegeArena#structures}), else an enemy player or bot, and never chase farther than
 * {@link MinionSettings#leash()} from the lane. Vanilla AI is off: they target nothing by themselves and hurt only
 * through their own hits; their bodies do not push each other and their hits do not knock each other back.
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
        private final Wave group;
        /** Lane progress where it was last seen ({@link Formation.Frame#progress()}). */
        private double progress;
        private double movingSpeed;
        /** A ranged minion stepping back behind its front line while it shoots. */
        private boolean backing;
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
                double damage, Wave group) {
            this.body = body;
            this.group = group;
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

    /** The minions spawned together on a lane: they march and fight in one formation ({@link Formation}). */
    private static final class Wave {
        private final Formation.Lane geo;
        private final List<Minion> members = new ArrayList<>();
        private long formedAt = -1;
        private Map<Integer, Formation.Slot> slots = Map.of();
        private double front = Double.NaN;

        private Wave(Formation.Lane geo) {
            this.geo = geo;
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
            arrive(minion);
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
                spawnGroup(kinds, side, lane, path);
            }
        }
    }

    /** A new wave on a lane, in its formation: melee rows in front, the ranged row and the cannon behind. */
    private void spawnGroup(List<MinionKind> kinds, LaneSites.Side side, LaneSites.Lane lane, List<Location> path) {
        Formation.Lane geo = geometry(path);
        Formation.Settings formation = settings.formation();
        List<Formation.Member> placed = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            placed.add(new Formation.Member(i, kinds.get(i), new Formation.Frame(0, 0)));
        }
        Map<Integer, Formation.Slot> slots = Formation.assign(placed, formation);
        Wave group = new Wave(geo);
        Vector forward = flat(geo.direction(0));
        for (int i = 0; i < kinds.size(); i++) {
            Formation.Slot slot = slots.get(i);
            Formation.Vec spot = geo.at(-slot.row() * formation.rowSpacing(), slot.lateral());
            Location at = new Location(path.getFirst().getWorld(), spot.x(), path.getFirst().getY(), spot.z());
            at.setDirection(forward);
            spawn(kinds.get(i), side, lane, path, group, at);
        }
    }

    private void spawn(MinionKind kind, LaneSites.Side side, LaneSites.Lane lane, List<Location> path, Wave group,
            Location at) {
        MinionSettings.Stats stats = settings.stats(kind);
        double bonus = 1 + Math.max(0, wave - 1) * settings.healthPerWave() + (empowered(side)
                ? settings.empoweredBonus() : 0);
        double health = stats.health() * bonus;
        double damage = stats.damage() * (empowered(side) ? 1 + settings.empoweredBonus() : 1);
        Component name = arena.messages().get("minion.name." + kind.key()).color(colour(side));
        try {
            Mob body = look.spawn(kind.creature(), side, at, mob -> {
                Creatures.prepare(mob, name, health, stats.scale(), settings.aggroRadius() + 8);
                // Bodies do not shove each other out of the lines (the formation keeps them apart).
                mob.setCollidable(false);
            });
            arena.track(body);
            Minion minion = new Minion(body, kind, side, lane, path, damage, group);
            minion.progress = group.geo.frame(vec(body.getLocation()), Double.NaN, 0).progress();
            group.members.add(minion);
            minions.put(body, minion);
        } catch (RuntimeException e) {
            arena.plugin().getLogger().log(Level.WARNING, "Could not spawn a " + kind + " minion", e);
        }
    }

    private static Formation.Lane geometry(List<Location> path) {
        List<Formation.Vec> points = new ArrayList<>();
        for (Location point : path) {
            points.add(vec(point));
        }
        return new Formation.Lane(points);
    }

    private static Formation.Vec vec(Location at) {
        return new Formation.Vec(at.getX(), at.getZ());
    }

    private static Vector flat(Formation.Vec direction) {
        return new Vector(direction.x(), 0, direction.z());
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

    /** Blocks of lane progress around a minion's last progress in which its place is looked for. */
    private static final double FRAME_WINDOW = 16;

    /** Brings a wave's formation up to date (once a tick): who is left, their rows and files, the front line. */
    private void form(Wave group) {
        if (group.formedAt == now) {
            return;
        }
        group.formedAt = now;
        group.members.removeIf(m -> minions.get(m.body) != m || !m.body.isValid() || m.body.isDead());
        List<Formation.Member> members = new ArrayList<>();
        List<Formation.Member> marching = new ArrayList<>();
        for (Minion member : group.members) {
            Formation.Frame frame = group.geo.frame(vec(member.body.getLocation()), member.progress, FRAME_WINDOW);
            member.progress = frame.progress();
            Formation.Member seen = new Formation.Member(member.body.getEntityId(), member.kind, frame);
            members.add(seen);
            if (member.target == null) {
                marching.add(seen);
            }
        }
        group.slots = Formation.assign(members, settings.formation());
        group.front = Formation.front(marching, group.slots, settings.formation());
    }

    private Formation.Slot slot(Minion minion) {
        Formation.Slot slot = minion.group.slots.get(minion.body.getEntityId());
        return slot != null ? slot : new Formation.Slot(0, 0);
    }

    private void think(Minion minion) {
        if (!arena.isFighting()) {
            mover.stop(minion.body);
            return;
        }
        form(minion.group);
        Formation.Slot slot = slot(minion);
        MinionSettings.Stats stats = settings.stats(minion.kind);
        LivingEntity target = minion.target;
        if (target != null && !keep(minion, target)) {
            target = null;
        }
        if (target == null) {
            target = findTarget(minion, slot);
        }
        minion.target = target;
        Location at = minion.body.getLocation();
        if (target != null) {
            Formation.@Nullable Frame stand = stand(minion, target, slot, stats);
            if (inReach(minion, target)) {
                if (now >= minion.nextAttack) {
                    minion.nextAttack = now + stats.attackTicks();
                    strike(minion, target);
                }
                if (stand != null && minion.kind.ranged()
                        && minion.progress - stand.progress() > settings.formation().rangedMargin() + 0.5) {
                    // A ranged minion caught up in the front line steps back to shoot from behind it.
                    minion.backing = true;
                    goTo(minion, place(minion, stand, at), stats.speed());
                } else {
                    minion.backing = false;
                    mover.stop(minion.body);
                    minion.movingTo = null;
                    Creatures.face(minion.body, target.getLocation());
                }
            } else {
                minion.backing = false;
                goTo(minion, engage(minion, target, stand, at), stats.speed());
            }
            minion.lastCheck = at;
            minion.checkedAt = now;
            minion.stuck = 0;
            return;
        }
        walk(minion, stats, at, slot);
    }

    /** Whether a minion's target is within its hit range (for a structure: from its walls). */
    private boolean inReach(Minion minion, LivingEntity target) {
        double range = settings.stats(minion.kind).range();
        if (arena.isStructure(target)) {
            // a hair of slack: a minion pushed out of the walls stands right at them
            return arena.structureDistance(target, minion.body.getLocation()) <= range + 0.1;
        }
        return Creatures.flatDistanceSquared(minion.body.getLocation(), target.getLocation()) <= range * range;
    }

    /** A body's half width: the margin it keeps from the structures' walls. */
    private static double margin(LivingEntity body) {
        return Math.max(0.3, body.getWidth() / 2) + 0.05;
    }

    /**
     * Between thinks: a minion walking up to its target stops the tick it gets in reach (it would walk into the enemy's
     * line, or two minions walking at each other would pass through each other).
     */
    private void arrive(Minion minion) {
        LivingEntity target = minion.target;
        if (target == null || minion.movingTo == null || minion.backing || !target.isValid() || target.isDead()
                || !target.getWorld().equals(minion.body.getWorld()) || !inReach(minion, target)) {
            return;
        }
        mover.stop(minion.body);
        minion.movingTo = null;
        Creatures.face(minion.body, target.getLocation());
    }

    private void goTo(Minion minion, Location wanted, double speed) {
        // never a goal inside a building (the pathfinder cannot reach it, a stepped minion would walk in)
        Location goal = arena.clearOfStructures(wanted, margin(minion.body));
        if (minion.movingTo == null || Creatures.flatDistanceSquared(goal, minion.movingTo) > 0.25
                || now - minion.movedAt >= 20) {
            minion.movingTo = goal;
            minion.movedAt = now;
            mover.moveTo(minion.body, goal, speed);
        }
    }

    /**
     * Where (in its lane's frame) a minion fights {@code target} from: for an enemy minion or a structure its stand
     * point ({@link Formation#standPoint}: melee in front of it, ranged at range, on the minion's own file); null for
     * an enemy player or bot (chased).
     */
    private Formation.@Nullable Frame stand(Minion minion, LivingEntity target, Formation.Slot slot,
            MinionSettings.Stats stats) {
        boolean structure = arena.isStructure(target);
        if (!structure && minionOf(target) == null) {
            return null;
        }
        Formation.Frame frame = minion.group.geo.frame(vec(target.getLocation()), minion.progress, FRAME_WINDOW);
        return Formation.standPoint(frame, structure ? arena.structureHalfWidth(target) : 0, slot.lateral(),
                stats.range(), minion.kind.ranged(), settings.formation());
    }

    private Location place(Minion minion, Formation.Frame frame, Location at) {
        Formation.Vec spot = minion.group.geo.at(frame.progress(), frame.lateral());
        return new Location(at.getWorld(), spot.x(), at.getY(), spot.z());
    }

    /**
     * Where a minion goes to fight a target out of its reach: its stand point, or right at the target for one that is
     * chased or once the stand point does not reach (the ground rounds where it stands).
     */
    private Location engage(Minion minion, LivingEntity target, Formation.@Nullable Frame stand, Location at) {
        Location there = target.getLocation();
        if (stand == null) {
            return there;
        }
        Location goal = place(minion, stand, there);
        if (Creatures.flatDistanceSquared(at, goal) >= 1.0) {
            return goal;
        }
        if (arena.isStructure(target)) {
            // straight at a building's wall (its centre is inside the walls)
            org.bukkit.util.Vector towards = there.toVector().subtract(at.toVector()).setY(0);
            double gap = arena.structureDistance(target, at);
            if (towards.lengthSquared() > 1.0E-6) {
                return at.clone().add(towards.normalize().multiply(Math.max(0, gap - 0.2)));
            }
        }
        return there;
    }

    /**
     * Marches along the lane in formation: towards the lane point a little ahead on its file, slowing down or
     * hurrying to keep its row's place behind the wave's front; re-paths when stuck.
     */
    private void walk(Minion minion, MinionSettings.Stats stats, Location at, Formation.Slot slot) {
        Formation.Lane geo = minion.group.geo;
        Formation.Settings formation = settings.formation();
        minion.next = Math.max(minion.next, geo.nextWaypoint(minion.progress, settings.waypointReach()));
        if (minion.next >= minion.path.size()) {
            // At the enemy base: stand there and fight whatever comes.
            mover.stop(minion.body);
            minion.movingTo = null;
            return;
        }
        double lateral = Math.clamp(slot.lateral(), -formation.maxLateral(), formation.maxLateral());
        double ahead = Math.min(geo.length(), minion.progress + formation.lookahead());
        Formation.Vec spot = geo.at(ahead, lateral);
        Location goal = arena.clearOfStructures(new Location(at.getWorld(), spot.x(), height(minion.path, geo, ahead),
                spot.z()), margin(minion.body));
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
            // Re-pathing did not get it out: put it on its path (its file at the next waypoint).
            Formation.Vec put = geo.at(geo.walked(minion.next), lateral);
            Location waypoint = minion.path.get(minion.next);
            // the lane passes through its towers: never put inside one
            Location to = arena.clearOfStructures(new Location(at.getWorld(), put.x(), waypoint.getY(), put.z()),
                    margin(minion.body));
            minion.body.teleport(to.setDirection(to.toVector().subtract(at.toVector()).setY(0)));
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
        double speed = stats.speed() * Formation.pace(minion.progress, slot.row(), minion.group.front, formation);
        if (repath || minion.movingTo == null || Creatures.flatDistanceSquared(goal, minion.movingTo) > 0.25
                || speed != minion.movingSpeed || now - minion.movedAt >= 40) {
            minion.movingTo = goal;
            minion.movingSpeed = speed;
            minion.movedAt = now;
            mover.moveTo(minion.body, goal, speed);
        }
    }

    /** The lane's height at {@code progress}: between the heights of the waypoints around it. */
    private static double height(List<Location> path, Formation.Lane geo, double progress) {
        for (int i = 1; i < path.size(); i++) {
            if (progress <= geo.walked(i) || i == path.size() - 1) {
                double span = geo.walked(i) - geo.walked(i - 1);
                double t = span < 1.0E-9 ? 1 : Math.clamp((progress - geo.walked(i - 1)) / span, 0, 1);
                return path.get(i - 1).getY() + (path.get(i).getY() - path.get(i - 1).getY()) * t;
            }
        }
        return path.getLast().getY();
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

    /**
     * An enemy minion (the one facing the minion's file that the fewest allies already fight, {@link Formation#pick}),
     * else a structure in reach, else the nearest enemy player (all within the leash).
     */
    private @Nullable LivingEntity findTarget(Minion minion, Formation.Slot slot) {
        Location at = minion.body.getLocation();
        double radius = settings.aggroRadius();
        LivingEntity minionTarget = facingMinion(minion, slot, at, radius);
        if (minionTarget != null) {
            return minionTarget;
        }
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity structure : arena.structures(enemy(minion.side))) {
            double reach = radius + arena.structureHalfWidth(structure);
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

    /** The enemy minion within {@code radius} a minion should fight ({@link Formation#pick}), or null. */
    private @Nullable LivingEntity facingMinion(Minion minion, Formation.Slot slot, Location at, double radius) {
        Map<Mob, Integer> fightersOn = new HashMap<>();
        List<Minion> enemies = new ArrayList<>();
        for (Minion other : minions.values()) {
            if (other.side == minion.side) {
                if (other.target instanceof Mob fought && other != minion) {
                    fightersOn.merge(fought, 1, Integer::sum);
                }
            } else if (other.body.isValid() && !other.body.isDead() && other.body.getWorld().equals(at.getWorld())
                    && Creatures.flatDistanceSquared(at, other.body.getLocation()) <= radius * radius
                    && inLeash(minion, other.body)) {
                enemies.add(other);
            }
        }
        if (enemies.isEmpty()) {
            return null;
        }
        List<Formation.Candidate> candidates = new ArrayList<>();
        for (Minion enemy : enemies) {
            Location there = enemy.body.getLocation();
            candidates.add(new Formation.Candidate(enemy.body.getEntityId(),
                    minion.group.geo.frame(vec(there), minion.progress, FRAME_WINDOW),
                    Math.sqrt(Creatures.flatDistanceSquared(at, there)), fightersOn.getOrDefault(enemy.body, 0)));
        }
        int chosen = Formation.pick(slot.lateral(), candidates, settings.formation());
        return chosen < 0 ? null : enemies.get(chosen).body;
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
        look.attack(minion.body);
        CreatureSounds.attack(arena, minion.kind.creature(), minion.body.getLocation());
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

    /**
     * A minion's hit does not knock another minion back (vanilla would, and the lines would fall apart); players' and
     * bots' hits and abilities still push minions as they push anything.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        if (striking && !settings.formation().knockback() && minionOf(event.getEntity()) != null) {
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
        look.hurt(minion.body);
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
