package io.github.sx2xoox.mcpl.game.siege.jungle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.ObjectiveKind;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeArena;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureKind;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureLook;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Attack;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureMover;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureSounds;
import io.github.sx2xoox.mcpl.game.siege.creature.Creatures;
import io.github.sx2xoox.mcpl.game.siege.creature.Telegraphs;
import io.github.sx2xoox.mcpl.game.siege.creature.ToyShots;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * How each jungle monster fights (docs/siege-v2.md §4): its basic attack, its skills with their warnings, how it moves
 * in a fight. Numbers are {@link JungleSettings} ({@code jungle.<kind>.*}, {@code jungle.<kind>.<skill>.*}).
 *
 * <ul>
 *     <li>젤리: 몸통 박치기 (basic) + 끈적 젤리 웅덩이 — spits a blob of jelly where its target stands; it lands after
 *     the warning circle fills and leaves a sticky puddle that slows the fighters in it.</li>
 *     <li>태엽 쥐: surround the target and nibble (small quick bites) + 흩어졌다 모이기 — they rear up squeaking, run
 *     off in every direction, then rush back in for a harder bite.</li>
 *     <li>곰인형 대장: 앞발 내려치기 (basic, a small circle ahead marked first) + 솜뭉치 충격파 (a wide circle round it,
 *     marked first, that hurts and pushes).</li>
 *     <li>시계 토끼: 시곗바늘 던지기 (basic, at range; a hit slows: 시간 지연), keeps its distance, and below half
 *     health 태엽 감기: winds itself up and pops up far away.</li>
 *     <li>장난감 드래곤: 깨물기 (basic) + 숨결 (a marked cone) + 꼬리 휩쓸기 (a marked circle) + 날개 돌풍 (a wide
 *     circle that shoves everyone away); below its rage health it roars and is 격노 (shorter cooldowns, harder hits).</li>
 * </ul>
 *
 * A skill marks its area on the ground the moment it starts ({@link Telegraphs}), the monster shivers ({@code
 * CreatureLook#charge}) and stands still, then plays the skill's motion so its blow lands as the mark fills. Damage
 * goes through the camp's own hit ({@link Host#deal}: the jungle's rules, kill credit, difficulty compensation).
 */
final class MonsterSkills {

    /** What the skills need of the jungle. */
    interface Host {
        /** Fighters (players and bots in play) inside the camp's leash. */
        List<LivingEntity> fighters(JungleObjectives.Site site);

        /** A monster's blow on {@code victim} (the only damage monsters deal). */
        void deal(Mob mob, LivingEntity victim, double damage);

        /** A monster's basic attack on {@code target}: its hit, motion and sound. */
        void basic(JungleObjectives.Site site, Mob mob, LivingEntity target, double damage);

        long now();
    }

    /** Blocks round its target where each mouse of a swarm stands to nibble. */
    static final double SURROUND = 1.1;
    /** How far a mouse may be from its spot round the target and still bite. */
    private static final double SPOT_SLACK = 0.7;
    /** The hand's slow on a hit of the rabbit's throw (its level). */
    private static final int TIME_SLOW_LEVEL = 1;

    private final SiegeArena arena;
    private final JungleSettings settings;
    private final CreatureLook look;
    private final CreatureMover mover;
    private final Host host;
    private final ToyShots shots;
    private final Map<Mob, State> states = new HashMap<>();
    private final List<Puddle> puddles = new ArrayList<>();

    MonsterSkills(SiegeArena arena, JungleSettings settings, CreatureLook look, CreatureMover mover, Host host) {
        this.arena = arena;
        this.settings = settings;
        this.look = look;
        this.mover = mover;
        this.host = host;
        this.shots = new ToyShots(arena);
    }

    /** One monster's fight state. */
    static final class State {
        /** Skill → the tick it is ready again. */
        final Map<String, Long> ready = new HashMap<>();
        final int index;
        long nextBasic;
        @Nullable Cast cast;
        /** Until when its last motion keeps it standing. */
        long busyUntil;
        /** Mice: running off until this tick, then rushing back. */
        long scatterUntil;
        boolean regroup;
        @Nullable Location scatterTo;
        boolean enraged;

        State(int index) {
            this.index = index;
        }
    }

    /** A skill on its way: marked from {@code start}, landing at {@code land}. */
    final class Cast {
        final JungleObjectives.Site site;
        final Mob mob;
        final String skill;
        final Attack motion;
        final long start;
        final long land;
        final Location centre;
        final Vector direction;
        final LivingEntity target;
        final Shape shape;
        final double radius;
        final double angle;
        boolean motionPlayed;

        Cast(JungleObjectives.Site site, Mob mob, String skill, Attack motion, int windup, Location centre,
                Vector direction, LivingEntity target, Shape shape, double radius, double angle) {
            this.site = site;
            this.mob = mob;
            this.skill = skill;
            this.motion = motion;
            this.start = host.now();
            this.land = start + Math.max(windup, motion.impact());
            this.centre = centre;
            this.direction = direction;
            this.target = target;
            this.shape = shape;
            this.radius = radius;
            this.angle = angle;
        }

        double progress() {
            return land <= start ? 1 : (host.now() - start) / (double) (land - start);
        }
    }

    /** How a cast marks its area. */
    enum Shape {
        NONE, CIRCLE, CONE
    }

    /** A sticky jelly puddle on the ground. */
    private final class Puddle {
        final JungleObjectives.Site site;
        final Location centre;
        final double radius;
        final long until;
        final int level;
        final List<BlockDisplay> look = new ArrayList<>();

        Puddle(JungleObjectives.Site site, Location centre, double radius, long until, int level) {
            this.site = site;
            this.centre = centre;
            this.radius = radius;
            this.until = until;
            this.level = level;
        }
    }

    // ------------------------------------------------------------------ flow

    /** Every tick: casts mark, play and land; shots fly; puddles stick. */
    void tick() {
        long now = host.now();
        for (State state : new ArrayList<>(states.values())) {
            Cast cast = state.cast;
            if (cast == null) {
                continue;
            }
            if (!cast.mob.isValid() || cast.mob.isDead()) {
                state.cast = null;
                continue;
            }
            if (!cast.motionPlayed && now >= cast.land - cast.motion.impact()) {
                cast.motionPlayed = true;
                look.attack(cast.mob, cast.motion);
            }
            if (now < cast.land) {
                if ((now - cast.start) % 2 == 0) {
                    mark(cast);
                }
                continue;
            }
            state.cast = null;
            state.busyUntil = cast.land + Math.max(0, cast.motion.ticks() - cast.motion.impact());
            try {
                land(cast, state);
            } catch (RuntimeException e) {
                arena.plugin().getLogger().log(Level.WARNING, "A jungle skill failed: " + cast.skill, e);
            }
        }
        shots.tick();
        puddles();
    }

    /** A monster with a target to fight (every few ticks): its skills, its basic attack, where it goes. */
    void fight(JungleObjectives.Site site, Mob mob, LivingEntity target) {
        State state = state(site, mob);
        long now = host.now();
        if (state.cast != null || now < state.busyUntil) {
            mover.stop(mob);
            return;
        }
        switch (site.kind()) {
            case JELLY -> jelly(site, mob, target, state);
            case MICE -> mouse(site, mob, target, state);
            case TEDDY -> teddy(site, mob, target, state);
            case RABBIT -> rabbit(site, mob, target, state);
            case DRAGON -> dragon(site, mob, target, state);
            default -> {
            }
        }
    }

    /** Whether a monster is casting or still playing its last skill (it stands). */
    boolean busy(Mob mob) {
        State state = states.get(mob);
        return state != null && (state.cast != null || host.now() < state.busyUntil);
    }

    /** The cast a monster is on (tests), or null. */
    @Nullable Cast cast(Mob mob) {
        State state = states.get(mob);
        return state == null ? null : state.cast;
    }

    /** Whether the dragon is enraged. */
    boolean enraged(Mob mob) {
        State state = states.get(mob);
        return state != null && state.enraged;
    }

    /** The camp went home: no casts, fresh cooldowns, its puddles dry up. */
    void reset(JungleObjectives.Site site) {
        for (Mob mob : site.mobs()) {
            forget(mob);
        }
        puddles.removeIf(puddle -> {
            if (puddle.site == site) {
                dry(puddle);
                return true;
            }
            return false;
        });
    }

    /** A monster is gone. */
    void forget(Mob mob) {
        State state = states.remove(mob);
        if (state != null && state.enraged) {
            look.enraged(mob, false);
        }
        shots.cancel(mob);
    }

    /** The round is over. */
    void clear() {
        states.clear();
        shots.clear();
        for (Puddle puddle : puddles) {
            dry(puddle);
        }
        puddles.clear();
    }

    ToyShots shots() {
        return shots;
    }

    int puddleCount() {
        return puddles.size();
    }

    private State state(JungleObjectives.Site site, Mob mob) {
        State state = states.get(mob);
        if (state != null) {
            return state;
        }
        int index = site.mobs().indexOf(mob);
        State fresh = new State(Math.max(0, index));
        long now = host.now();
        // the first use of each skill comes half a cooldown into the fight (a camp's members a little apart)
        for (Map.Entry<String, JungleSettings.Skill> entry : skills(site.kind()).entrySet()) {
            fresh.ready.put(entry.getKey(), now + entry.getValue().cooldownTicks() / 2 + fresh.index * 40L);
        }
        states.put(mob, fresh);
        return fresh;
    }

    private Map<String, JungleSettings.Skill> skills(ObjectiveKind kind) {
        return settings.skills().getOrDefault(kind, Map.of());
    }

    private boolean ready(State state, String skill) {
        return host.now() >= state.ready.getOrDefault(skill, Long.MAX_VALUE);
    }

    /** Uses a skill: it is ready again after its cooldown (shorter for an enraged dragon). */
    private void spend(State state, String skill, int cooldown) {
        double haste = state.enraged ? settings.dragonRageHaste() : 1;
        state.ready.put(skill, host.now() + Math.round(cooldown * haste));
    }

    private double damage(State state, double damage) {
        return state.enraged ? damage * settings.dragonRageDamage() : damage;
    }

    /** Starts a cast: marks it, the monster stands, shivers and warns, facing its target. */
    private Cast begin(State state, JungleObjectives.Site site, Mob mob, String skill, Attack motion, int windup,
            Location centre, Vector direction, LivingEntity target, Shape shape, double radius, double angle) {
        mover.stop(mob);
        Creatures.face(mob, target.getLocation());
        Cast cast = new Cast(site, mob, skill, motion, windup, centre, direction, target, shape, radius, angle);
        state.cast = cast;
        int charge = (int) (cast.land - cast.start) - motion.impact();
        if (charge > 0) {
            look.charge(mob, charge);
        }
        CreatureSounds.warn(arena, motion, mob.getLocation());
        mark(cast);
        return cast;
    }

    private void mark(Cast cast) {
        Color colour = switch (cast.skill) {
            case JungleSettings.GUST -> Telegraphs.PUSH;
            case JungleSettings.GOO -> Telegraphs.STICKY;
            default -> Telegraphs.DANGER;
        };
        switch (cast.shape) {
            case CIRCLE -> Telegraphs.circle(cast.centre, cast.radius, cast.progress(), colour);
            case CONE -> Telegraphs.cone(cast.centre, cast.direction, cast.angle, cast.radius, cast.progress(),
                    colour);
            case NONE -> {
            }
        }
    }

    /** The moment a cast lands. */
    private void land(Cast cast, State state) {
        Mob mob = cast.mob;
        JungleObjectives.Site site = cast.site;
        ObjectiveKind kind = site.kind();
        CreatureSounds.skill(arena, cast.motion, mob.getLocation());
        switch (cast.skill) {
            case JungleSettings.GOO -> {
                JungleSettings.Skill goo = settings.skill(kind, JungleSettings.GOO);
                Location from = mob.getLocation().add(0, Math.max(0.4, mob.getHeight() * 0.6), 0);
                double distance = Math.sqrt(Creatures.flatDistanceSquared(from, cast.centre));
                double speed = Math.max(0.25, distance / Math.max(4, goo.windupTicks()));
                shots.lob(mob, ToyShots.Look.GOO, from, cast.centre, speed, 1.6 + distance * 0.15, goo.radius(),
                        at -> puddle(site, at, goo));
            }
            case JungleSettings.SCATTER -> scatter(cast, state);
            case JungleSettings.SLAM -> {
                JungleSettings.Skill slam = settings.skill(kind, JungleSettings.SLAM);
                area(cast, site.kindSettings().damage(), slam.strength(), Particle.CLOUD);
            }
            case JungleSettings.SHOCKWAVE -> {
                JungleSettings.Skill wave = settings.skill(kind, JungleSettings.SHOCKWAVE);
                area(cast, wave.damage(), wave.strength(), Particle.WHITE_ASH);
                shockRings(cast.centre, cast.radius);
            }
            case JungleSettings.BLINK -> blink(cast);
            case JungleSettings.BREATH -> {
                JungleSettings.Skill breath = settings.skill(kind, JungleSettings.BREATH);
                breathPuffs(cast);
                for (LivingEntity victim : host.fighters(site)) {
                    if (Telegraphs.inCone(cast.centre, cast.direction, cast.angle, cast.radius,
                            victim.getLocation())) {
                        host.deal(mob, victim, damage(state, breath.damage()));
                    }
                }
            }
            case JungleSettings.TAIL -> {
                JungleSettings.Skill tail = settings.skill(kind, JungleSettings.TAIL);
                area(cast, damage(state, tail.damage()), tail.strength(), Particle.CLOUD);
            }
            case JungleSettings.GUST -> {
                JungleSettings.Skill gust = settings.skill(kind, JungleSettings.GUST);
                area(cast, damage(state, gust.damage()), gust.strength(), Particle.CLOUD);
                shockRings(cast.centre, cast.radius);
            }
            case RAGE -> {
                state.enraged = true;
                look.enraged(mob, true);
                arena.broadcast(arena.messages().prefixed("jungle.dragon-enraged"));
                mob.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, mob.getLocation().add(0, 2.5, 0), 8, 1, 0.6, 1,
                        0);
            }
            default -> {
            }
        }
    }

    /** Hurts and pushes every fighter inside a circle cast's area. */
    private void area(Cast cast, double damage, double push, Particle particle) {
        Telegraphs.burst(cast.centre, cast.radius, particle);
        for (LivingEntity victim : host.fighters(cast.site)) {
            Location at = victim.getLocation();
            if (Creatures.flatDistanceSquared(at, cast.centre) <= cast.radius * cast.radius
                    && Math.abs(at.getY() - cast.centre.getY()) < 3) {
                if (damage > 0) {
                    host.deal(cast.mob, victim, damage);
                }
                shove(victim, cast.centre, push);
            }
        }
    }

    /** A push away from {@code from}, {@code strength} blocks-ish, a little up. */
    static void shove(LivingEntity victim, Location from, double strength) {
        if (strength <= 0 || !victim.isValid() || victim.isDead()) {
            return;
        }
        Vector away = victim.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (away.lengthSquared() < 1.0E-6) {
            away = new Vector(1, 0, 0);
        }
        victim.setVelocity(away.normalize().multiply(0.45 * strength).setY(0.18 + 0.12 * Math.min(1, strength)));
    }

    // ------------------------------------------------------------------ jelly

    private void jelly(JungleObjectives.Site site, Mob mob, LivingEntity target, State state) {
        JungleSettings.Kind kind = site.kindSettings();
        JungleSettings.Skill goo = settings.skill(ObjectiveKind.JELLY, JungleSettings.GOO);
        double distance = Math.sqrt(Creatures.flatDistanceSquared(mob.getLocation(), target.getLocation()));
        if (ready(state, JungleSettings.GOO) && distance <= 10) {
            spend(state, JungleSettings.GOO, goo.cooldownTicks());
            begin(state, site, mob, JungleSettings.GOO, Attack.GOOP, Attack.GOOP.impact(), target.getLocation(),
                    new Vector(), target, Shape.NONE, goo.radius(), 0);
            return;
        }
        melee(site, mob, target, state, kind.speed(), null);
    }

    /** A sticky puddle where the goo lands. */
    private void puddle(JungleObjectives.Site site, Location at, JungleSettings.Skill goo) {
        if (goo.effectTicks() <= 0 || goo.radius() <= 0) {
            return;
        }
        Location centre = ground(at);
        Puddle puddle = new Puddle(site, centre, goo.radius(), host.now() + goo.effectTicks(),
                (int) Math.max(0, Math.round(goo.strength())));
        float side = (float) (goo.radius() * 1.75);
        for (int i = 0; i < 2; i++) {
            Quaternionf turn = new Quaternionf().rotateY((float) (Math.PI / 4 * i));
            Vector3f size = new Vector3f(side, 0.04f, side);
            Vector3f corner = new Vector3f(size).mul(-0.5f).rotate(turn).add(0, 0.02f + i * 0.005f, 0);
            try {
                BlockDisplay display = centre.getWorld().spawn(centre, BlockDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setBlock(Material.PINK_STAINED_GLASS.createBlockData());
                    d.setTransformation(new Transformation(corner, turn, size, new Quaternionf()));
                    d.setBrightness(new Display.Brightness(12, 12));
                    d.setShadowRadius(0);
                });
                arena.track(display);
                puddle.look.add(display);
            } catch (RuntimeException e) {
                arena.plugin().getLogger().log(Level.FINE, "Could not draw a jelly puddle", e);
            }
        }
        centre.getWorld().spawnParticle(Particle.ITEM_SLIME, centre.clone().add(0, 0.2, 0), 14, goo.radius() / 2, 0.1,
                goo.radius() / 2, 0.05);
        puddles.add(puddle);
    }

    private void puddles() {
        long now = host.now();
        puddles.removeIf(puddle -> {
            if (now >= puddle.until) {
                dry(puddle);
                return true;
            }
            if (now % 5 == 0) {
                for (LivingEntity victim : host.fighters(puddle.site)) {
                    Location at = victim.getLocation();
                    if (Creatures.flatDistanceSquared(at, puddle.centre) <= puddle.radius * puddle.radius
                            && Math.abs(at.getY() - puddle.centre.getY()) < 1.5) {
                        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, puddle.level, false,
                                false, true));
                    }
                }
                puddle.centre.getWorld().spawnParticle(Particle.ITEM_SLIME, puddle.centre.clone().add(0, 0.15, 0), 2,
                        puddle.radius / 2, 0.02, puddle.radius / 2, 0);
            }
            return false;
        });
    }

    private void dry(Puddle puddle) {
        for (BlockDisplay display : puddle.look) {
            display.remove();
            arena.untrack(display);
        }
        puddle.look.clear();
    }

    // ------------------------------------------------------------------ mice

    private void mouse(JungleObjectives.Site site, Mob mob, LivingEntity target, State state) {
        JungleSettings.Kind kind = site.kindSettings();
        JungleSettings.Skill scatter = settings.skill(ObjectiveKind.MICE, JungleSettings.SCATTER);
        long now = host.now();
        if (now < state.scatterUntil && state.scatterTo != null) {
            mover.moveTo(mob, state.scatterTo, kind.speed() * scatter.strength());
            return;
        }
        if (state.regroup) {
            // rush back in: the first bite on arrival is a hard one
            if (Creatures.flatDistanceSquared(mob.getLocation(), target.getLocation()) <= kind.range() * kind.range()) {
                state.regroup = false;
                mover.stop(mob);
                state.nextBasic = now + kind.attackTicks();
                host.basic(site, mob, target, scatter.damage());
            } else {
                mover.moveTo(mob, target.getLocation(), kind.speed() * scatter.strength());
            }
            return;
        }
        if (ready(state, JungleSettings.SCATTER)) {
            // the whole swarm scatters together
            for (Mob other : site.mobs()) {
                State them = state(site, other);
                if (them.cast == null && other.isValid() && !other.isDead()) {
                    spend(them, JungleSettings.SCATTER, scatter.cooldownTicks());
                    begin(them, site, other, JungleSettings.SCATTER, Attack.SQUEAK, scatter.windupTicks(),
                            other.getLocation(), new Vector(), target, Shape.NONE, scatter.radius(), 0);
                }
            }
            return;
        }
        // surround the target, each on its own spot, and nibble
        List<Mob> swarm = site.mobs();
        int count = Math.max(1, swarm.size());
        int place = Math.max(0, swarm.indexOf(mob));
        Location centre = target.getLocation();
        double angle = 2 * Math.PI * place / count;
        Location spot = centre.clone().add(Math.cos(angle) * SURROUND, 0, Math.sin(angle) * SURROUND);
        melee(site, mob, target, state, kind.speed(), spot);
    }

    /** The squeak is over: each mouse runs off away from the target. */
    private void scatter(Cast cast, State state) {
        JungleSettings.Skill scatter = settings.skill(ObjectiveKind.MICE, JungleSettings.SCATTER);
        Location at = cast.mob.getLocation();
        Vector away = at.toVector().subtract(cast.target.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1.0E-4) {
            double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            away = new Vector(Math.cos(a), 0, Math.sin(a));
        }
        Location to = at.clone().add(away.normalize().multiply(scatter.radius()));
        double room = Math.max(2, cast.site.kindSettings().leash() - 2);
        Vector fromHome = to.toVector().subtract(cast.site.at().toVector()).setY(0);
        if (fromHome.lengthSquared() > room * room) {
            to = cast.site.at().add(fromHome.normalize().multiply(room));
        }
        to.setY(at.getY());
        state.scatterTo = to;
        state.scatterUntil = host.now() + scatter.effectTicks();
        state.regroup = true;
        mover.moveTo(cast.mob, to, cast.site.kindSettings().speed() * scatter.strength());
    }

    // ------------------------------------------------------------------ teddy

    private void teddy(JungleObjectives.Site site, Mob mob, LivingEntity target, State state) {
        JungleSettings.Kind kind = site.kindSettings();
        JungleSettings.Skill wave = settings.skill(ObjectiveKind.TEDDY, JungleSettings.SHOCKWAVE);
        JungleSettings.Skill slam = settings.skill(ObjectiveKind.TEDDY, JungleSettings.SLAM);
        Location at = mob.getLocation();
        double distance = Math.sqrt(Creatures.flatDistanceSquared(at, target.getLocation()));
        if (ready(state, JungleSettings.SHOCKWAVE) && distance <= wave.radius() - 0.5) {
            spend(state, JungleSettings.SHOCKWAVE, wave.cooldownTicks());
            begin(state, site, mob, JungleSettings.SHOCKWAVE, Attack.SHOCKWAVE, wave.windupTicks(), at.clone(),
                    new Vector(), target, Shape.CIRCLE, wave.radius(), 0);
            return;
        }
        if (distance <= kind.range()) {
            if (host.now() >= state.nextBasic) {
                state.nextBasic = host.now() + kind.attackTicks();
                Vector ahead = flat(target.getLocation().toVector().subtract(at.toVector()), at);
                Location centre = at.clone().add(ahead.clone().multiply(slam.radius() * 0.9));
                begin(state, site, mob, JungleSettings.SLAM, Attack.SLAM, slam.windupTicks(), centre, ahead, target,
                        Shape.CIRCLE, slam.radius(), 0);
            } else {
                mover.stop(mob);
                Creatures.face(mob, target.getLocation());
            }
            return;
        }
        mover.moveTo(mob, target.getLocation(), kind.speed());
    }

    /** Shockwave rings running out over the area (decoration). */
    private void shockRings(Location centre, double radius) {
        for (double r = 1; r <= radius; r += 1) {
            int dots = (int) Math.max(8, r * 6);
            for (int i = 0; i < dots; i++) {
                double a = 2 * Math.PI * i / dots;
                centre.getWorld().spawnParticle(Particle.CLOUD, centre.getX() + Math.cos(a) * r, centre.getY() + 0.2,
                        centre.getZ() + Math.sin(a) * r, 0, Math.cos(a), 0.05, Math.sin(a), 0.12);
            }
        }
    }

    // ------------------------------------------------------------------ rabbit

    private void rabbit(JungleObjectives.Site site, Mob mob, LivingEntity target, State state) {
        JungleSettings.Kind kind = site.kindSettings();
        JungleSettings.Skill blink = settings.skill(ObjectiveKind.RABBIT, JungleSettings.BLINK);
        JungleSettings.Skill toss = settings.skill(ObjectiveKind.RABBIT, JungleSettings.THROW);
        Location at = mob.getLocation();
        double distance = Math.sqrt(Creatures.flatDistanceSquared(at, target.getLocation()));
        if (ready(state, JungleSettings.BLINK)
                && mob.getHealth() <= Creatures.maxHealth(mob) * blink.strength() && distance < kind.range()) {
            spend(state, JungleSettings.BLINK, blink.cooldownTicks());
            begin(state, site, mob, JungleSettings.BLINK, Attack.WIND, blink.windupTicks(), at.clone(), new Vector(),
                    target, Shape.NONE, blink.radius(), 0);
            return;
        }
        if (distance < toss.radius()) {
            flee(site, mob, target, kind.speed() * 1.2);
            return;
        }
        if (distance > kind.range()) {
            mover.moveTo(mob, target.getLocation(), kind.speed());
            return;
        }
        mover.stop(mob);
        Creatures.face(mob, target.getLocation());
        if (host.now() >= state.nextBasic) {
            state.nextBasic = host.now() + kind.attackTicks();
            double damage = kind.damage();
            int slow = toss.effectTicks();
            look.attack(mob);
            CreatureSounds.attack(arena, CreatureKind.RABBIT, at);
            Location from = at.clone().add(0, Math.max(0.5, mob.getHeight() * 0.8), 0);
            shots.homing(mob, ToyShots.Look.CLOCK_HAND, from, target, Math.max(0.1, toss.strength()), hit -> {
                host.deal(mob, hit, damage);
                if (slow > 0) {
                    hit.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, TIME_SLOW_LEVEL, false,
                            true, true));
                }
                CreatureSounds.slowed(arena, hit.getLocation());
                hit.getWorld().spawnParticle(Particle.END_ROD, hit.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0.01);
            });
        }
    }

    /** The clock rabbit backs away from its attacker, staying inside its leash. */
    private void flee(JungleObjectives.Site site, Mob mob, LivingEntity from, double speed) {
        Location home = site.at();
        Vector away = mob.getLocation().toVector().subtract(from.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1.0E-4) {
            away = new Vector(1, 0, 0);
        }
        Location goal = mob.getLocation().add(away.normalize().multiply(4));
        double room = Math.max(1, site.kindSettings().leash() - 3);
        Vector fromHome = goal.toVector().subtract(home.toVector()).setY(0);
        if (fromHome.lengthSquared() > room * room) {
            // cornered at the edge: across home to the other side instead
            goal = home.clone().subtract(fromHome.normalize().multiply(room * 0.6));
        }
        goal.setY(home.getY());
        mover.moveTo(mob, goal, speed);
    }

    /** 태엽 감기: the rabbit pops up far from its attacker (inside its leash). */
    private void blink(Cast cast) {
        Mob mob = cast.mob;
        Location home = cast.site.at();
        Location at = mob.getLocation();
        double room = Math.max(2, cast.site.kindSettings().leash() - 3);
        Vector away = at.toVector().subtract(cast.target.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1.0E-4) {
            away = new Vector(1, 0, 0);
        }
        Location to = at.clone().add(away.normalize().multiply(cast.radius));
        Vector fromHome = to.toVector().subtract(home.toVector()).setY(0);
        if (fromHome.lengthSquared() > room * room) {
            to = home.clone().add(fromHome.normalize().multiply(room));
        }
        if (Creatures.flatDistanceSquared(to, cast.target.getLocation()) < 9) {
            // still on top of its attacker: the far side of home
            Vector across = home.toVector().subtract(cast.target.getLocation().toVector()).setY(0);
            to = home.clone().add(across.lengthSquared() < 1.0E-4 ? new Vector(room * 0.7, 0, 0)
                    : across.normalize().multiply(room * 0.7));
        }
        to.setY(at.getY());
        to = ground(to);
        to.setYaw(at.getYaw());
        at.getWorld().spawnParticle(Particle.END_ROD, at.clone().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0.04);
        mover.stop(mob);
        mob.teleport(to);
        Creatures.face(mob, cast.target.getLocation());
        to.getWorld().spawnParticle(Particle.END_ROD, to.clone().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0.04);
    }

    // ------------------------------------------------------------------ dragon

    /** The dragon's rage: a cast with no area. */
    static final String RAGE = "rage";

    private void dragon(JungleObjectives.Site site, Mob mob, LivingEntity target, State state) {
        JungleSettings.Kind kind = site.kindSettings();
        Location at = mob.getLocation();
        if (!state.enraged && mob.getHealth() <= Creatures.maxHealth(mob) * settings.dragonRageHealth()) {
            begin(state, site, mob, RAGE, Attack.ROAR, Attack.ROAR.impact(), at.clone(), new Vector(), target,
                    Shape.NONE, 0, 0);
            return;
        }
        JungleSettings.Skill breath = settings.skill(ObjectiveKind.DRAGON, JungleSettings.BREATH);
        JungleSettings.Skill tail = settings.skill(ObjectiveKind.DRAGON, JungleSettings.TAIL);
        JungleSettings.Skill gust = settings.skill(ObjectiveKind.DRAGON, JungleSettings.GUST);
        double distance = Math.sqrt(Creatures.flatDistanceSquared(at, target.getLocation()));
        int close = 0;
        for (LivingEntity fighter : host.fighters(site)) {
            if (Creatures.flatDistanceSquared(at, fighter.getLocation()) <= Math.pow(gust.radius() * 0.6, 2)) {
                close++;
            }
        }
        if (ready(state, JungleSettings.GUST) && (close >= 2 || distance <= 3)) {
            spend(state, JungleSettings.GUST, gust.cooldownTicks());
            begin(state, site, mob, JungleSettings.GUST, Attack.GUST, gust.windupTicks(), at.clone(), new Vector(),
                    target, Shape.CIRCLE, gust.radius(), 0);
            return;
        }
        if (ready(state, JungleSettings.TAIL) && distance <= tail.radius() - 0.5) {
            spend(state, JungleSettings.TAIL, tail.cooldownTicks());
            begin(state, site, mob, JungleSettings.TAIL, Attack.TAIL, tail.windupTicks(), at.clone(), new Vector(),
                    target, Shape.CIRCLE, tail.radius(), 0);
            return;
        }
        if (ready(state, JungleSettings.BREATH) && distance <= breath.radius() - 1) {
            spend(state, JungleSettings.BREATH, breath.cooldownTicks());
            Vector ahead = flat(target.getLocation().toVector().subtract(at.toVector()), at);
            begin(state, site, mob, JungleSettings.BREATH, Attack.BREATH, breath.windupTicks(), at.clone(), ahead,
                    target, Shape.CONE, breath.radius(), breath.angle());
            return;
        }
        if (distance <= kind.range()) {
            mover.stop(mob);
            Creatures.face(mob, target.getLocation());
            if (host.now() >= state.nextBasic) {
                double haste = state.enraged ? settings.dragonRageHaste() : 1;
                state.nextBasic = host.now() + Math.round(kind.attackTicks() * haste);
                host.basic(site, mob, target, damage(state, kind.damage()));
            }
            return;
        }
        mover.moveTo(mob, target.getLocation(), kind.speed());
    }

    /** The breath rolling out along its cone (decoration). */
    private void breathPuffs(Cast cast) {
        Vector ahead = cast.direction.clone().setY(0);
        if (ahead.lengthSquared() < 1.0E-6) {
            return;
        }
        double heading = Math.atan2(ahead.getZ(), ahead.getX());
        double half = Math.toRadians(cast.angle / 2);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (double d = 1.5; d <= cast.radius; d += 0.8) {
            for (int i = 0; i < 3; i++) {
                double a = heading + random.nextDouble(-half, half);
                Location at = cast.centre.clone().add(Math.cos(a) * d, 1 + random.nextDouble(0.6), Math.sin(a) * d);
                at.getWorld().spawnParticle(Particle.CLOUD, at, 0, Math.cos(a), 0.02, Math.sin(a), 0.08);
                at.getWorld().spawnParticle(Particle.DUST, at, 1, 0.1, 0.1, 0.1, 0,
                        new Particle.DustOptions(Color.fromRGB(0xFF, 0xB3, 0xD1), 1.5f));
            }
        }
    }

    // ------------------------------------------------------------------ shared

    /**
     * Walks up to its target (or to {@code spot}, a mouse's place round it) and makes the basic attack whenever the
     * target is in reach.
     */
    private void melee(JungleObjectives.Site site, Mob mob, LivingEntity target, State state, double speed,
            @Nullable Location spot) {
        JungleSettings.Kind kind = site.kindSettings();
        Location at = mob.getLocation();
        double reach = kind.range();
        boolean inReach = Creatures.flatDistanceSquared(at, target.getLocation()) <= reach * reach;
        if (inReach && host.now() >= state.nextBasic) {
            state.nextBasic = host.now() + kind.attackTicks();
            Creatures.face(mob, target.getLocation());
            host.basic(site, mob, target, kind.damage());
        }
        boolean settled = inReach
                && (spot == null || Creatures.flatDistanceSquared(at, spot) <= SPOT_SLACK * SPOT_SLACK);
        if (settled) {
            mover.stop(mob);
            Creatures.face(mob, target.getLocation());
        } else {
            mover.moveTo(mob, spot == null ? target.getLocation() : spot, speed);
        }
    }

    /** {@code direction} flattened and normalised, or where {@code at} looks. */
    private static Vector flat(Vector direction, Location at) {
        Vector out = direction.clone().setY(0);
        if (out.lengthSquared() < 1.0E-6) {
            out = at.getDirection().setY(0);
        }
        return out.lengthSquared() < 1.0E-6 ? new Vector(0, 0, 1) : out.normalize();
    }

    /** The ground under (or just above) {@code at}: a solid block with two free blocks over it. */
    static Location ground(Location at) {
        Location out = at.clone();
        int x = at.getBlockX();
        int z = at.getBlockZ();
        int y = at.getBlockY();
        for (int dy = 2; dy >= -4; dy--) {
            Block floor = at.getWorld().getBlockAt(x, y + dy - 1, z);
            Block feet = floor.getRelative(0, 1, 0);
            Block head = floor.getRelative(0, 2, 0);
            if (floor.getType().isSolid() && feet.isPassable() && head.isPassable()) {
                out.setY(y + dy);
                return out;
            }
        }
        return out;
    }
}
