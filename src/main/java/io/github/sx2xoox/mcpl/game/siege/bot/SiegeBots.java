package io.github.sx2xoox.mcpl.game.siege.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.combat.CombatTracker;
import io.github.sx2xoox.mcpl.core.game.DeathCauses;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.siege.DamageNumbers;
import io.github.sx2xoox.mcpl.game.siege.SiegeMatch;
import io.github.sx2xoox.mcpl.game.siege.SiegeModule;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeSystems;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureMover;
import io.github.sx2xoox.mcpl.game.siege.creature.Creatures;
import io.github.sx2xoox.mcpl.game.siege.jungle.JungleObjectives;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionWaves;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 공성전 봇전 (docs/siege-v2.md §9): bots that fill both sides of a 공성전 match and fight with bot-only
 * {@link BotJob}s. {@link #enlist} adds them to the session as participants (the hub puts each on the smaller team),
 * then this module, plugged into the match after the lane systems, spawns their bodies ({@link SiegeBot}) at their
 * base every round, drives them every tick of the fight with a {@link SiegeBotBrain} per bot and applies the rules:
 *
 * <ul>
 *     <li>they are fighters like the players: ability and basic-attack targets of the other side
 *     ({@link #isTarget}/{@link #isEnemy}), shot by enemy towers when no minion is nearer ({@link #towerTarget}), fought
 *     by minions and monsters ({@link #fighters});</li>
 *     <li>they hurt only through the proper paths: structures through {@link SiegeMatch#damageStructureBy}, everything else
 *     as mob attack damage they cause (so the minions', monsters' and the session's own rules and kill credit
 *     apply);</li>
 *     <li>a death scores like a player's ({@link SiegeMatch#fighterKilled}: kill and assist points, gold,
 *     experience) and they come back at their base after the players' respawn delay, protected for as long;</li>
 *     <li>levels from the match's experience raise their health and damage like the players'; gold buys
 *     upgrades at the base.</li>
 * </ul>
 *
 * Everything runs on the main thread; every entity is tracked by the session and removed when a round or the match
 * ends, and the chunks held for them are let go.
 */
public final class SiegeBots implements SiegeModule, Listener {

    /** What {@code /게임 시작 공성전 봇전} asked for: fill each side up to {@code perSide} with bots of a difficulty. */
    public record Setup(SiegeBotSettings settings, int perSide, SiegeBotDifficulty difficulty) {
    }

    /** One enlisted bot: its participant id, name and difficulty. */
    public record Seat(UUID id, String name, SiegeBotDifficulty difficulty) {
    }

    /** A structure hit within this many ticks is "under attack". */
    static final int UNDER_ATTACK_TICKS = 60;
    /** Bots this close to their base heal there. */
    static final double BASE_RADIUS = 10;
    /** Ticks without moving before a bot asks for a new path, and twice that before it is put back on its lane. */
    static final int STUCK_TICKS = 60;

    private final SiegeMatch match;
    private final GameSession session;
    private final Messages messages;
    private final SiegeBotSettings settings;
    private final CreatureMover mover = CreatureMover.pathfinder();
    private final Random random;
    private final Map<UUID, SiegeBot> bots = new LinkedHashMap<>();
    /** Body entity id → bot. */
    private final Map<UUID, SiegeBot> bodies = new HashMap<>();
    /** Body entity id → bot, for the legs. */
    private final Map<UUID, SiegeBot> legs = new HashMap<>();
    private final Map<String, LanePath> lanes = new HashMap<>();
    private final Map<String, List<Location>> laneLocations = new HashMap<>();
    /** What each bot saw at its last decision, by participant or entity id. */
    private final Map<UUID, Map<UUID, LivingEntity>> seen = new HashMap<>();
    private final Set<Long> heldChunks = new HashSet<>();
    private long emptySince = -1;
    private boolean stopped;
    private int forwarding;

    public SiegeBots(SiegeMatch match, Setup setup, List<Seat> seats) {
        this.match = match;
        this.session = match.session();
        this.messages = match.messages();
        this.settings = setup.settings();
        this.random = new Random(session.random().nextLong());
        Map<LaneSites.Side, Integer> dealt = new EnumMap<>(LaneSites.Side.class);
        Map<LaneSites.Side, Integer> offset = new EnumMap<>(LaneSites.Side.class);
        for (LaneSites.Side side : LaneSites.Side.values()) {
            offset.put(side, random.nextInt(Math.max(1, settings.jobs().size())));
        }
        for (Seat seat : seats) {
            LaneSites.Side side = match.side(seat.id());
            if (side == null) {
                continue;
            }
            int index = dealt.merge(side, 1, Integer::sum) - 1;
            BotJob job = settings.jobs().get((index + offset.get(side)) % settings.jobs().size());
            LaneSites.Lane lane = index % 2 == 0 ? LaneSites.Lane.TOP : LaneSites.Lane.BOTTOM;
            SiegeBotBrain brain = new SiegeBotBrain(settings.profile(seat.difficulty()), new Random(random.nextLong()));
            bots.put(seat.id(), new SiegeBot(seat.id(), seat.name(), job, seat.difficulty(), side, lane, brain));
        }
        session.registerListener(this);
        for (LaneSites.Side side : LaneSites.Side.values()) {
            Component list = Component.empty();
            boolean first = true;
            for (SiegeBot bot : bots.values()) {
                if (bot.side != side) {
                    continue;
                }
                list = list.append(Component.text(first ? "" : ", "))
                        .append(Component.text(bot.name, match.team(side).color()))
                        .append(Component.text("(" + bot.job.koreanName() + "·"
                                + (bot.lane == LaneSites.Lane.TOP ? "탑" : "바텀") + ")", NamedTextColor.GRAY));
                first = false;
            }
            if (!first) {
                session.broadcast(messages.prefixed("bots.joined", Messages.tag("team", match.team(side).displayName()),
                        Messages.tag("bots", list)));
            }
        }
    }

    /**
     * Adds the bots of {@code setup} to a starting session (RUNNING, teams formed): as many as fill both of the first two
     * teams up to {@code perSide} fighters; the hub puts each on the smaller team.
     */
    public static List<Seat> enlist(GameSession session, Setup setup) {
        List<MatchTeam> teams = session.teams();
        if (teams.size() < 2) {
            return List.of();
        }
        int wanted = 0;
        for (MatchTeam team : teams.subList(0, 2)) {
            wanted += Math.max(0, setup.perSide() - team.size());
        }
        List<String> names = new ArrayList<>(setup.settings().names());
        Collections.shuffle(names, session.random());
        List<Seat> out = new ArrayList<>();
        for (int i = 0; i < wanted; i++) {
            String base = names.get(i % names.size());
            String name = base + " 봇" + (i >= names.size() ? " " + (i / names.size() + 1) : "");
            UUID id = UUID.nameUUIDFromBytes(("mcpl-siege-bot:" + session.tick() + ":" + i + ":"
                    + session.random().nextLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (session.addBot(id, name)) {
                out.add(new Seat(id, name, setup.difficulty()));
            }
        }
        return out;
    }

    // =================================================================== flow

    @Override
    public void roundStarted(int round) {
        releaseChunks();
        for (SiegeBot bot : bots.values()) {
            bot.brain.reset();
            bot.upgrades = 0;
            spawnAtBase(bot);
            bot.protectedUntil = 0;
        }
    }

    @Override
    public void tick(long fightTicks) {
        if (stopped) {
            return;
        }
        long now = session.tick();
        if (abandoned(now)) {
            return;
        }
        for (SiegeBot bot : List.copyOf(bots.values())) {
            try {
                tickBot(bot, now);
            } catch (RuntimeException e) {
                session.plugin().getLogger().log(Level.WARNING, "A siege bot failed: " + bot.name, e);
                bot.nextThink = now + 20;
            }
            if (match.phase() != SiegeMatch.Phase.FIGHT) {
                return;
            }
        }
        if (fightTicks % 20 == 0) {
            holdChunks();
        }
    }

    @Override
    public void roundEnded() {
        for (SiegeBot bot : bots.values()) {
            despawn(bot);
            bot.respawnAt = -1;
        }
        releaseChunks();
    }

    @Override
    public void stop() {
        stopped = true;
        for (SiegeBot bot : bots.values()) {
            despawn(bot);
        }
        releaseChunks();
        seen.clear();
    }

    /** Nobody but bots left in the match: it ends instead of playing on for nobody. */
    private boolean abandoned(long now) {
        if (!session.participants().isEmpty()) {
            emptySince = -1;
            return false;
        }
        if (emptySince < 0) {
            emptySince = now;
        }
        if (now - emptySince >= 100) {
            session.abort(messages.get("bots.everyone-left"));
            return true;
        }
        return false;
    }

    // =================================================================== module hooks

    @Override
    public List<LivingEntity> targets() {
        List<LivingEntity> out = new ArrayList<>();
        for (SiegeBot bot : bots.values()) {
            if (bot.alive()) {
                out.add(bot.body);
            }
        }
        return out;
    }

    @Override
    public boolean isTarget(Entity entity) {
        return bodies.containsKey(entity.getUniqueId());
    }

    @Override
    public boolean isEnemy(Player player, Entity entity) {
        SiegeBot bot = bodies.get(entity.getUniqueId());
        LaneSites.Side side = match.side(player.getUniqueId());
        return bot != null && bot.alive() && side != null && side != bot.side
                && match.phase() == SiegeMatch.Phase.FIGHT && !isProtected(bot);
    }

    @Override
    public List<LivingEntity> fighters(LaneSites.Side side) {
        List<LivingEntity> out = new ArrayList<>();
        for (SiegeBot bot : bots.values()) {
            if (bot.side == side && bot.alive() && !isProtected(bot)) {
                out.add(bot.body);
            }
        }
        return out;
    }

    @Override
    public @Nullable UUID fighterOf(@Nullable Entity entity) {
        SiegeBot bot = entity == null ? null : bodies.get(entity.getUniqueId());
        return bot == null ? null : bot.id;
    }

    /** Towers shoot a bot when no minion is in range (the lane systems answer first) and no player is nearer. */
    @Override
    public @Nullable LivingEntity towerTarget(Tower tower, double range) {
        SiegeSystems systems = match.systems();
        if (systems != null && systems.towerTarget(tower.owner(), tower.base(), range) != null) {
            return null;
        }
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (SiegeBot bot : bots.values()) {
            if (bot.side == tower.owner() || !bot.alive() || isProtected(bot)
                    || !tower.isWithin(bot.body.getLocation(), range)) {
                continue;
            }
            double distance = bot.body.getLocation().distanceSquared(tower.base());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = bot.body;
            }
        }
        if (best == null) {
            return null;
        }
        for (Player player : session.alivePlayers()) {
            LaneSites.Side side = match.side(player.getUniqueId());
            if (side != null && side != tower.owner() && session.isInPlay(player) && !match.isProtected(player)
                    && tower.isWithin(player.getLocation(), range)
                    && player.getLocation().distanceSquared(tower.base()) < bestDistance) {
                return null;
            }
        }
        return best;
    }

    @Override
    public void towerShot(Tower tower, LivingEntity target, double damage) {
        target.damage(damage, DamageSource.builder(DamageType.MAGIC).build());
    }

    @Override
    public @Nullable Component fighterTag(UUID id) {
        SiegeBot bot = bots.get(id);
        return bot == null ? null : Component.text(bot.job.koreanName(), NamedTextColor.WHITE);
    }

    // =================================================================== one bot

    private void tickBot(SiegeBot bot, long now) {
        if (bot.respawnAt >= 0) {
            if (now >= bot.respawnAt) {
                bot.respawnAt = -1;
                spawnAtBase(bot);
                bot.protectedUntil = now + match.settings().protectionTicks();
                sound(bot.body.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.4f, 1.8f);
            }
            return;
        }
        if (!bot.alive()) {
            // Its body went missing (not a death the rules saw): it comes back soon, without a kill for anyone.
            despawn(bot);
            bot.respawnAt = now + 40;
            return;
        }
        WanderingTrader walker = bot.legs;
        mover.tick(walker);
        if ((now + bot.id.hashCode()) % 20 == 0) {
            second(bot, now);
        }
        if (bot.recallUntil >= 0) {
            recalling(bot, now);
        } else {
            if (now >= bot.nextThink) {
                think(bot, now);
            }
            act(bot, now);
        }
        if (bot.alive()) {
            bot.sync(facing(bot));
            if (bot.plateDirty) {
                redrawPlate(bot);
            }
        }
    }

    private float facing(SiegeBot bot) {
        LivingEntity target = target(bot);
        if (target != null && target.getWorld().equals(bot.body.getWorld())) {
            Vector delta = target.getLocation().toVector().subtract(bot.body.getLocation().toVector()).setY(0);
            if (delta.lengthSquared() > 1.0E-6) {
                return (float) Math.toDegrees(Math.atan2(-delta.getX(), delta.getZ()));
            }
        }
        return bot.legs.getLocation().getYaw();
    }

    /** Once a second: experience, level health, healing, upgrades. */
    private void second(SiegeBot bot, long now) {
        match.addXp(bot.id, match.passiveXpPerSecond());
        SiegeBotSettings.JobStats stats = settings.stats(bot.job);
        double max = stats.health() + match.levelHealth(bot.id) + bot.upgrades * settings.upgradeHealth();
        Mannequin body = bot.body;
        double before = bot.maxHealth();
        if (Math.abs(max - before) > 1.0E-6) {
            SiegeBot.base(body, org.bukkit.attribute.Attribute.MAX_HEALTH, max);
            double health = Math.min(max, body.getHealth() + Math.max(0, max - before));
            body.setHealth(Math.max(0.5, health));
            bot.plateDirty = true;
        }
        boolean atBase = atBase(bot);
        double heal = atBase ? settings.baseHeal() : now - bot.lastHurt >= settings.regenDelayTicks() ? settings.regen()
                : 0;
        if (heal > 0 && body.getHealth() < max) {
            body.setHealth(Math.min(max, body.getHealth() + heal * max));
            bot.plateDirty = true;
        }
        if (atBase) {
            buyUpgrades(bot);
        }
    }

    private void buyUpgrades(SiegeBot bot) {
        SiegeSystems systems = match.systems();
        if (systems == null || settings.upgradeCost() <= 0) {
            return;
        }
        while (bot.upgrades < settings.maxUpgrades() && systems.gold().spend(bot.id, settings.upgradeCost())) {
            bot.upgrades++;
        }
    }

    // ------------------------------------------------------------------ deciding

    private void think(SiegeBot bot, long now) {
        SiegeBotSettings.Profile profile = settings.profile(bot.difficulty);
        bot.nextThink = now + profile.reactionTicks() + random.nextInt(Math.max(1, profile.reactionTicks() / 2 + 1));
        SiegeBotBrain.Plan plan = bot.brain.decide(view(bot, now));
        if (plan.action() == SiegeBotBrain.Action.JUNGLE && plan.camp() != null
                && !plan.camp().equals(bot.plan.camp()) && !reachable(bot, site(plan.camp()))) {
            // A camp its legs cannot path to: back to the lane, and no jungle for a while.
            bot.brain.giveUpCamp(now);
            plan = bot.brain.decide(view(bot, now));
        }
        if (plan.action() == SiegeBotBrain.Action.RECALL && bot.plan.action() != SiegeBotBrain.Action.RECALL) {
            bot.recallUntil = now + settings.recallTicks();
            mover.stop(bot.legs);
            bot.movingTo = null;
        }
        bot.plan = plan;
    }

    /** Whether the legs find a full path to the camp (true on a server without a pathfinder). */
    private boolean reachable(SiegeBot bot, JungleObjectives.@Nullable Site site) {
        if (site == null) {
            return false;
        }
        try {
            com.destroystokyo.paper.entity.Pathfinder.PathResult path = bot.legs.getPathfinder()
                    .findPath(site.at());
            return path != null && path.canReachFinalPoint();
        } catch (RuntimeException | LinkageError noPathfinder) {
            return true;
        }
    }

    private SiegeBotBrain.View view(SiegeBot bot, long now) {
        Location at = bot.body.getLocation();
        SiegeBotBrain.Vec me = vec(at);
        SiegeBotSettings.JobStats stats = settings.stats(bot.job);
        SiegeBotBrain.Self self = new SiegeBotBrain.Self(bot.id, me, bot.health(), bot.maxHealth(),
                damage(bot, stats.damage()), stats.range(), bot.job.role(), atBase(bot), now - bot.lastHurt <= 60);
        Map<UUID, LivingEntity> sight = new HashMap<>();
        List<SiegeBotBrain.Unit> units = new ArrayList<>();
        double see = SiegeBotBrain.SEE;
        for (Champion champion : champions()) {
            LivingEntity body = champion.body();
            if (!body.getWorld().equals(at.getWorld()) || Creatures.flatDistanceSquared(body.getLocation(), at)
                    > see * see) {
                continue;
            }
            sight.put(champion.id(), body);
            units.add(new SiegeBotBrain.Unit(champion.id(), SiegeBotBrain.Kind.CHAMPION, vec(body.getLocation()),
                    body.getHealth(), Creatures.maxHealth(body), champion.side() != bot.side));
        }
        SiegeSystems systems = match.systems();
        if (systems != null) {
            for (MinionWaves.Minion minion : systems.minions().minions()) {
                Mob body = minion.body();
                if (!body.isValid() || body.isDead() || !body.getWorld().equals(at.getWorld())
                        || Creatures.flatDistanceSquared(body.getLocation(), at) > see * see) {
                    continue;
                }
                sight.put(body.getUniqueId(), body);
                units.add(new SiegeBotBrain.Unit(body.getUniqueId(), SiegeBotBrain.Kind.MINION,
                        vec(body.getLocation()), body.getHealth(), Creatures.maxHealth(body), minion.side() != bot.side));
            }
        }
        seen.put(bot.id, sight);
        List<SiegeBotBrain.Structure> structures = new ArrayList<>();
        for (Tower tower : match.towers()) {
            if (tower.isDestroyed()) {
                continue;
            }
            boolean hit = tower.lastDamageTick() > 0 && now - tower.lastDamageTick() <= UNDER_ATTACK_TICKS;
            structures.add(new SiegeBotBrain.Structure(tower.id(), vec(tower.base()), tower.owner() != bot.side,
                    tower.isShielded(), tower.isCore(), hit));
        }
        return new SiegeBotBrain.View(now, self, lane(bot), vec(base(bot)), units, structures, camps(bot, me),
                match.settings().towerAttackRange());
    }

    private List<SiegeBotBrain.Camp> camps(SiegeBot bot, SiegeBotBrain.Vec me) {
        SiegeSystems systems = match.systems();
        if (systems == null) {
            return List.of();
        }
        List<Champion> champions = champions();
        List<SiegeBotBrain.Camp> out = new ArrayList<>();
        for (JungleObjectives.Site site : systems.jungle().sites()) {
            boolean dragon = site.kind() == LaneSites.ObjectiveKind.DRAGON;
            if (!site.isUp() || site.isCapturePoint() || site.mobs().isEmpty()
                    || site.kind() == LaneSites.ObjectiveKind.RABBIT || !dragon && site.home() != bot.side) {
                continue;
            }
            SiegeBotBrain.Vec camp = vec(site.at());
            int allies = 0;
            int enemies = 0;
            for (Champion champion : champions) {
                if (champion.id().equals(bot.id)) {
                    continue;
                }
                SiegeBotBrain.Vec where = vec(champion.body().getLocation());
                boolean near = where.distance(camp) <= 30;
                if (champion.side() == bot.side && (near || where.distance(me) <= 12)) {
                    allies++;
                } else if (champion.side() != bot.side && near) {
                    enemies++;
                }
            }
            boolean claimed = false;
            for (SiegeBot other : bots.values()) {
                claimed |= other != bot && other.side == bot.side && other.alive()
                        && site.objective().id().equals(other.brain.camp());
            }
            out.add(new SiegeBotBrain.Camp(site.objective().id(), camp, dragon, allies, enemies, claimed));
        }
        return out;
    }

    /** A player or bot in play. */
    private record Champion(UUID id, LivingEntity body, LaneSites.Side side) {
    }

    /** Every player in play (not protected, not hidden) and every live bot. */
    private List<Champion> champions() {
        List<Champion> out = new ArrayList<>();
        for (Player player : session.alivePlayers()) {
            LaneSites.Side side = match.side(player.getUniqueId());
            if (side != null && session.isInPlay(player) && !player.isDead()
                    && player.getGameMode() != GameMode.SPECTATOR && !match.isProtected(player)
                    && !player.isInvisible() && !player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                out.add(new Champion(player.getUniqueId(), player, side));
            }
        }
        for (SiegeBot other : bots.values()) {
            if (other.alive() && !isProtected(other)) {
                out.add(new Champion(other.id, other.body, other.side));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ acting

    private void act(SiegeBot bot, long now) {
        SiegeBotBrain.Plan plan = bot.plan;
        SiegeBotSettings.JobStats stats = settings.stats(bot.job);
        if (bot.job == BotJob.HEALER && now >= bot.nextSkill) {
            healAlly(bot, now, stats);
        }
        switch (plan.action()) {
            case WAIT, RECALL -> stop(bot);
            case PUSH, DEFEND -> walk(bot, now, location(plan.goal(), bot), stats.speed());
            case RETREAT -> walk(bot, now, location(plan.goal(), bot), stats.speed() * 1.1);
            case FARM, FIGHT -> {
                LivingEntity target = target(bot);
                if (target == null) {
                    bot.nextThink = Math.min(bot.nextThink, now);
                    stop(bot);
                } else {
                    engage(bot, now, target, stats, 0.3);
                }
            }
            case SIEGE -> {
                Tower tower = tower(plan.structure());
                LivingEntity hitbox = tower == null || tower.isShielded() ? null : tower.hitbox();
                if (hitbox == null || !hitbox.isValid()) {
                    bot.nextThink = Math.min(bot.nextThink, now);
                    stop(bot);
                } else {
                    engage(bot, now, hitbox, stats, hitbox.getWidth() / 2);
                }
            }
            case JUNGLE -> {
                JungleObjectives.Site site = site(plan.camp());
                if (site == null || !site.isUp()) {
                    bot.nextThink = Math.min(bot.nextThink, now);
                    stop(bot);
                    return;
                }
                Mob mob = nearest(site.mobs(), bot.body.getLocation());
                if (mob == null || mob.getLocation().distanceSquared(bot.body.getLocation()) > 20 * 20) {
                    walk(bot, now, site.at(), stats.speed());
                } else {
                    engage(bot, now, mob, stats, mob.getWidth() / 2);
                }
            }
        }
    }

    /** Fights {@code target}: closes in until it is in reach, then attacks (and uses the skill when it may). */
    private void engage(SiegeBot bot, long now, LivingEntity target, SiegeBotSettings.JobStats stats, double extra) {
        Location at = bot.body.getLocation();
        Location to = target.getLocation();
        double distance = Math.sqrt(Creatures.flatDistanceSquared(at, to));
        double reach = stats.range() + extra;
        trySkill(bot, now, target, distance, stats);
        if (!bot.alive() || !target.isValid() || target.isDead()) {
            return;
        }
        if (distance <= reach && Math.abs(at.getY() - to.getY()) <= Math.max(3, reach)) {
            if (bot.job.ranged() && distance < reach * 0.35 && isChampion(target)) {
                // too close for a ranged job: a step back while it shoots
                Vector away = at.toVector().subtract(to.toVector()).setY(0);
                if (away.lengthSquared() > 1.0E-6) {
                    walk(bot, now, at.clone().add(away.normalize().multiply(3)), stats.speed());
                }
            } else {
                stop(bot);
            }
            if (now >= bot.nextAttack) {
                bot.nextAttack = now + stats.attackTicks();
                basicAttack(bot, target, stats);
            }
            return;
        }
        walk(bot, now, to, stats.speed() * (isChampion(target) ? 1.1 : 1));
    }

    private void basicAttack(SiegeBot bot, LivingEntity target, SiegeBotSettings.JobStats stats) {
        bot.protectedUntil = 0;
        swing(bot);
        double amount = damage(bot, stats.damage());
        Location from = bot.body.getEyeLocation();
        Location to = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        switch (bot.job) {
            case KNIGHT, ASSASSIN -> {
                sound(bot.body.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, bot.job == BotJob.KNIGHT ? 0.9f
                        : 1.4f);
                strike(bot, target, amount, false);
            }
            case ARCHER -> {
                sound(from, Sound.ENTITY_ARROW_SHOOT, 0.7f, 1.2f);
                if (hits(bot)) {
                    trail(from, to, Particle.CRIT);
                    strike(bot, target, amount, false);
                } else {
                    trail(from, miss(to), Particle.CRIT);
                }
            }
            case MAGE -> {
                sound(from, Sound.ENTITY_BLAZE_SHOOT, 0.5f, 1.6f);
                if (hits(bot)) {
                    trail(from, to, Particle.WITCH);
                    strike(bot, target, amount, false);
                } else {
                    trail(from, miss(to), Particle.WITCH);
                }
            }
            case HEALER -> {
                sound(from, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
                if (hits(bot)) {
                    trail(from, to, Particle.END_ROD);
                    strike(bot, target, amount, false);
                } else {
                    trail(from, miss(to), Particle.END_ROD);
                }
            }
        }
    }

    /** Uses the job's skill on {@code target} when it is ready, in reach and the bot decides to. */
    private void trySkill(SiegeBot bot, long now, LivingEntity target, double distance,
                          SiegeBotSettings.JobStats stats) {
        if (now < bot.nextSkill || bot.job == BotJob.HEALER || match.towerOf(target) != null) {
            return;
        }
        boolean champion = isChampion(target);
        boolean monster = !champion && match.systems() != null && match.systems().jungle().siteOf(target) != null;
        boolean eligible = switch (bot.job) {
            case KNIGHT -> distance <= stats.skillRange() && (champion || monster);
            case ARCHER, MAGE -> distance <= stats.skillRange();
            case ASSASSIN -> distance <= stats.skillRange() && (champion || monster);
            case HEALER -> false;
        };
        if (!eligible) {
            return;
        }
        if (random.nextDouble() >= settings.profile(bot.difficulty).skillChance()) {
            bot.nextSkill = now + 20 + random.nextInt(20);
            return;
        }
        bot.nextSkill = now + stats.skillCooldown();
        bot.protectedUntil = 0;
        double amount = damage(bot, stats.skillDamage());
        Location from = bot.body.getEyeLocation();
        switch (bot.job) {
            case KNIGHT -> {
                swing(bot);
                sound(bot.body.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 0.7f);
                world(bot).spawnParticle(Particle.SWEEP_ATTACK, target.getLocation().add(0, 1, 0), 2, 0.3, 0.2, 0.3, 0);
                strike(bot, target, amount, true);
                if (target instanceof Player player && stats.skillEffect() > 0) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, stats.skillEffect(), 1, false,
                            true, true));
                    Vector push = player.getLocation().toVector().subtract(bot.body.getLocation().toVector()).setY(0);
                    if (push.lengthSquared() > 1.0E-6) {
                        player.setVelocity(push.normalize().multiply(0.5).setY(0.25));
                    }
                }
            }
            case ARCHER -> {
                sound(from, Sound.ITEM_CROSSBOW_SHOOT, 0.9f, 1.1f);
                List<LivingEntity> hit = new ArrayList<>();
                hit.add(target);
                for (LivingEntity other : enemiesNear(bot, target.getLocation(), stats.skillRadius(), monster)) {
                    if (hit.size() >= stats.skillCount()) {
                        break;
                    }
                    if (!hit.contains(other)) {
                        hit.add(other);
                    }
                }
                for (LivingEntity victim : hit) {
                    Location to = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
                    if (hits(bot)) {
                        trail(from, to, Particle.CRIT);
                        strike(bot, victim, amount, true);
                    } else {
                        trail(from, miss(to), Particle.CRIT);
                    }
                }
            }
            case MAGE -> {
                Location center = hits(bot) ? target.getLocation() : miss(target.getLocation());
                trail(from, center.clone().add(0, 0.5, 0), Particle.FLAME);
                sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.5f);
                world(bot).spawnParticle(Particle.EXPLOSION, center.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
                world(bot).spawnParticle(Particle.FLAME, center.clone().add(0, 0.5, 0), 30, stats.skillRadius() / 2,
                        0.3, stats.skillRadius() / 2, 0.02);
                for (LivingEntity victim : enemiesNear(bot, center, stats.skillRadius(), monster)) {
                    strike(bot, victim, amount, true);
                }
            }
            case ASSASSIN -> {
                Location to = target.getLocation();
                Vector back = bot.body.getLocation().toVector().subtract(to.toVector()).setY(0);
                if (back.lengthSquared() > 1.0E-6 && distance > settings.stats(bot.job).range()) {
                    Location spot = to.clone().add(back.normalize().multiply(1.2));
                    spot.setY(to.getY());
                    if (standable(spot)) {
                        world(bot).spawnParticle(Particle.LARGE_SMOKE, bot.body.getLocation().add(0, 1, 0), 12, 0.3,
                                0.5, 0.3, 0.02);
                        bot.legs.teleport(spot);
                        bot.sync(facing(bot));
                    }
                }
                swing(bot);
                sound(bot.body.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.8f);
                world(bot).spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.1);
                if (Math.sqrt(Creatures.flatDistanceSquared(bot.body.getLocation(), target.getLocation()))
                        <= settings.stats(bot.job).range() + 1) {
                    strike(bot, target, amount, true);
                }
            }
            case HEALER -> {
            }
        }
    }

    /** 치유의 빛: the most hurt ally (player or bot, itself included) in reach below 75 % health. */
    private void healAlly(SiegeBot bot, long now, SiegeBotSettings.JobStats stats) {
        LivingEntity best = null;
        double lowest = 0.75;
        Location at = bot.body.getLocation();
        for (Champion champion : champions()) {
            LivingEntity body = champion.body();
            if (champion.side() != bot.side || !body.getWorld().equals(at.getWorld())
                    || body.getLocation().distanceSquared(at) > stats.skillRange() * stats.skillRange()) {
                continue;
            }
            double fraction = body.getHealth() / Math.max(1, Creatures.maxHealth(body));
            if (fraction < lowest) {
                lowest = fraction;
                best = body;
            }
        }
        if (best == null) {
            return;
        }
        if (random.nextDouble() >= settings.profile(bot.difficulty).skillChance()) {
            bot.nextSkill = now + 20 + random.nextInt(20);
            return;
        }
        bot.nextSkill = now + stats.skillCooldown();
        double amount = stats.skillDamage() * match.levelFactor(bot.id);
        swing(bot);
        trail(bot.body.getEyeLocation(), best.getLocation().add(0, 1, 0), Particle.HAPPY_VILLAGER);
        world(bot).spawnParticle(Particle.HEART, best.getLocation().add(0, 2, 0), 4, 0.4, 0.3, 0.4, 0);
        sound(best.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.6f);
        double before = best.getHealth();
        best.heal(amount, EntityRegainHealthEvent.RegainReason.MAGIC);
        SiegeBot healed = bodies.get(best.getUniqueId());
        if (healed != null) {
            healed.plateDirty = true;
            if (best.getHealth() > before) {
                match.damageNumbers().show(best, best.getHealth() - before, DamageNumbers.Kind.HEAL);
            }
        }
    }

    /**
     * A hit of {@code bot}: a structure through the rules ({@link SiegeMatch#damageStructureBy}), anything else as mob
     * attack damage the bot's body causes (a player takes exactly {@code amount} whatever the world difficulty).
     */
    void strike(SiegeBot bot, LivingEntity victim, double amount, boolean skill) {
        if (!(amount > 0) || !victim.isValid() || victim.isDead()) {
            return;
        }
        Tower tower = match.towerOf(victim);
        if (tower != null) {
            match.damageStructureBy(tower, bot.side, amount, bot.id);
            return;
        }
        if (skill) {
            victim.setNoDamageTicks(0);
        }
        Mannequin body = bot.body;
        if (victim instanceof Player && victim.getWorld().getDifficulty() == Difficulty.PEACEFUL) {
            victim.damage(amount, DamageSource.builder(DamageType.MOB_ATTACK).withDirectEntity(body).build());
            return;
        }
        double dealt = victim instanceof Player ? Creatures.beforeDifficulty(amount, victim.getWorld().getDifficulty())
                : amount;
        victim.damage(dealt, DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(body)
                .withDirectEntity(body).build());
    }

    /** Enemies (players, bots, minions; monsters only for {@code monsters}) within {@code radius} of {@code at}. */
    private List<LivingEntity> enemiesNear(SiegeBot bot, Location at, double radius, boolean monsters) {
        List<LivingEntity> out = new ArrayList<>();
        if (!(radius > 0)) {
            return out;
        }
        double r2 = radius * radius;
        for (Champion champion : champions()) {
            if (champion.side() != bot.side && near(champion.body(), at, r2)) {
                out.add(champion.body());
            }
        }
        SiegeSystems systems = match.systems();
        if (systems != null) {
            for (MinionWaves.Minion minion : systems.minions().minions()) {
                if (minion.side() != bot.side && near(minion.body(), at, r2)) {
                    out.add(minion.body());
                }
            }
            if (monsters) {
                for (LivingEntity monster : systems.jungle().targets()) {
                    if (near(monster, at, r2)) {
                        out.add(monster);
                    }
                }
            }
        }
        return out;
    }

    private static boolean near(LivingEntity entity, Location at, double r2) {
        return entity.isValid() && !entity.isDead() && entity.getWorld().equals(at.getWorld())
                && entity.getLocation().distanceSquared(at) <= r2;
    }

    /** Damage of {@code base} for the bot now: its level (as players' basic attacks), difficulty and upgrades. */
    private double damage(SiegeBot bot, double base) {
        return base * match.levelFactor(bot.id) * settings.profile(bot.difficulty).damageScale()
                * (1 + bot.upgrades * settings.upgradeDamage());
    }

    private boolean hits(SiegeBot bot) {
        return random.nextDouble() < settings.profile(bot.difficulty).accuracy();
    }

    private Location miss(Location to) {
        double angle = random.nextDouble() * Math.PI * 2;
        return to.clone().add(Math.cos(angle) * 1.6, 0.4, Math.sin(angle) * 1.6);
    }

    // ------------------------------------------------------------------ moving

    /** Sends the legs towards {@code goal} (through the lane when far), renewing the path when stuck. */
    private void walk(SiegeBot bot, long now, Location goal, double speed) {
        WanderingTrader walker = bot.legs;
        Location at = walker.getLocation();
        Location step = route(bot, at, goal);
        if (Creatures.flatDistanceSquared(at, step) < 0.6 * 0.6) {
            stop(bot);
            return;
        }
        if (now - bot.checkedAt >= 20) {
            Location last = bot.lastCheck;
            if (last != null && last.getWorld().equals(at.getWorld()) && at.distanceSquared(last) < 0.25) {
                bot.stuck += (int) (now - bot.checkedAt);
            } else {
                bot.stuck = 0;
            }
            bot.lastCheck = at.clone();
            bot.checkedAt = now;
        }
        if (bot.stuck >= STUCK_TICKS * 2) {
            unstick(bot, at, step);
            return;
        }
        boolean repath = bot.stuck >= STUCK_TICKS && now - bot.movedAt >= STUCK_TICKS;
        Location moving = bot.movingTo;
        if (repath || moving == null || !moving.getWorld().equals(step.getWorld())
                || moving.distanceSquared(step) > 1.5 * 1.5 || now - bot.movedAt >= 40) {
            if (repath) {
                mover.stop(walker);
            }
            bot.movingTo = step.clone();
            bot.movedAt = now;
            mover.moveTo(walker, step, speed);
        }
    }

    /** Far goals are walked to through the lane's waypoints (a path reaches only so far). */
    private Location route(SiegeBot bot, Location at, Location goal) {
        if (Creatures.flatDistanceSquared(at, goal) <= 28 * 28) {
            return goal;
        }
        LanePath lane = lane(bot);
        SiegeBotBrain.Vec me = vec(at);
        SiegeBotBrain.Vec target = vec(goal);
        if (lane.distance(me) > 14 || lane.distance(target) > 14) {
            return goal;
        }
        double from = lane.progress(me);
        double to = lane.progress(target);
        double next = from + Math.signum(to - from) * 20;
        return laneLocation(bot, next);
    }

    /** Put back on its lane a little towards where it wanted to go (the minions do the same). */
    private void unstick(SiegeBot bot, Location at, Location toward) {
        LanePath lane = lane(bot);
        double from = lane.progress(vec(at));
        double to = lane.progress(vec(toward));
        Location spot = laneLocation(bot, from + Math.signum(to - from) * 3);
        bot.legs.teleport(spot);
        bot.stuck = 0;
        bot.movingTo = null;
        bot.lastCheck = spot.clone();
    }

    private void stop(SiegeBot bot) {
        if (bot.movingTo != null) {
            mover.stop(bot.legs);
            bot.movingTo = null;
        }
        bot.stuck = 0;
    }

    // ------------------------------------------------------------------ recall, spawning, death

    private void recalling(SiegeBot bot, long now) {
        Location at = bot.body.getLocation();
        if (now - bot.lastHurt <= 1 || bot.plan.action() != SiegeBotBrain.Action.RECALL) {
            bot.recallUntil = -1;
            bot.nextThink = now;
            return;
        }
        if (now % 5 == 0) {
            world(bot).spawnParticle(Particle.PORTAL, at.clone().add(0, 1, 0), 16, 0.4, 0.8, 0.4, 0.2);
        }
        if (now >= bot.recallUntil) {
            bot.recallUntil = -1;
            Location base = match.spawnFor(bot.id);
            bot.legs.teleport(base);
            bot.sync(base.getYaw());
            sound(base, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.3f);
            bot.plan = SiegeBotBrain.Plan.go(SiegeBotBrain.Action.WAIT, vec(base));
            bot.nextThink = now + 20;
        }
    }

    private void spawnAtBase(SiegeBot bot) {
        despawn(bot);
        Location at = match.spawnFor(bot.id);
        double health = settings.stats(bot.job).health() + match.levelHealth(bot.id)
                + bot.upgrades * settings.upgradeHealth();
        Color colour = match.team(bot.side).rgb();
        if (!bot.spawn(session, at, health, settings.legSpeed(), colour)) {
            bot.respawnAt = session.tick() + 100;
            return;
        }
        bodies.put(bot.body.getUniqueId(), bot);
        legs.put(bot.legs.getUniqueId(), bot);
        bot.plan = SiegeBotBrain.Plan.go(SiegeBotBrain.Action.WAIT, vec(at));
        bot.nextThink = session.tick() + 10 + random.nextInt(20);
        bot.nextAttack = 0;
        bot.nextSkill = session.tick() + 60;
        bot.lastHurt = Long.MIN_VALUE / 2;
        bot.sync(at.getYaw());
        redrawPlate(bot);
    }

    private void despawn(SiegeBot bot) {
        if (bot.body != null) {
            bodies.remove(bot.body.getUniqueId());
        }
        if (bot.legs != null) {
            legs.remove(bot.legs.getUniqueId());
        }
        bot.remove(session);
        seen.remove(bot.id);
    }

    private void redrawPlate(SiegeBot bot) {
        bot.plateDirty = false;
        if (bot.plate == null || bot.body == null) {
            return;
        }
        Component name = Component.text(bot.name, match.team(bot.side).color())
                .append(Component.text(" " + bot.job.koreanName(), NamedTextColor.GRAY));
        Component text = match.plateText(bot.id, name, bot.health(), bot.maxHealth());
        if (!text.equals(bot.plateText)) {
            bot.plateText = text;
            bot.plate.text(text);
        }
    }

    // =================================================================== events

    /** Who may hurt a bot: enemies in the fight (not its team, not while it is protected), no falls or walls. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBodyDamage(EntityDamageEvent event) {
        SiegeBot bot = bodies.get(event.getEntity().getUniqueId());
        if (bot == null) {
            return;
        }
        if (match.phase() != SiegeMatch.Phase.FIGHT || !bot.alive() || isProtected(bot)) {
            event.setCancelled(true);
            return;
        }
        switch (event.getCause()) {
            case FALL, SUFFOCATION, DROWNING, CRAMMING, FLY_INTO_WALL, CONTACT, HOT_FLOOR, FREEZE, STARVATION,
                 LAVA, VOID, DRYOUT, WORLD_BORDER -> {
                event.setCancelled(true);
                return;
            }
            default -> {
            }
        }
        Player player = session.combat().resolveAttacker(event);
        if (player != null) {
            LaneSites.Side side = match.side(player.getUniqueId());
            if (side == null || side == bot.side || !session.isInPlay(player)) {
                event.setCancelled(true);
                return;
            }
        } else {
            UUID attacker = fighterAttacker(event);
            SiegeBot other = attacker == null ? null : bots.get(attacker);
            if (other != null && other.side == bot.side) {
                event.setCancelled(true);
                return;
            }
        }
        double defense = settings.stats(bot.job).defense();
        if (defense > 0) {
            event.setDamage(event.getDamage() * (1 - defense));
        }
    }

    /** The legs never take damage: a hit on them (they are never sent to anyone) is passed on to the body. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegsDamage(EntityDamageEvent event) {
        SiegeBot bot = legs.get(event.getEntity().getUniqueId());
        if (bot == null) {
            return;
        }
        event.setCancelled(true);
        Mannequin body = bot.body;
        if (body != null && body.isValid() && event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() != body && forwarding == 0) {
            forwarding++;
            try {
                body.damage(event.getDamage(), event.getDamageSource());
            } finally {
                forwarding--;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLegsBurn(EntityCombustEvent event) {
        if (legs.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (legs.containsKey(event.getRightClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** No vanilla mob picks a bot's legs as its target. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTargeted(EntityTargetEvent event) {
        Entity target = event.getTarget();
        if (target != null && legs.containsKey(target.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * A landed hit where a bot is the victim or the attacker: kill credit and damage stats (the hub only sees hits
     * between players), assists, the plate, the floating number and the recall it breaks.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRecord(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        SiegeBot victimBot = bodies.get(entity.getUniqueId());
        UUID victim = victimBot != null ? victimBot.id
                : entity instanceof Player player && session.isParticipant(player) ? player.getUniqueId() : null;
        if (victim == null) {
            return;
        }
        long now = session.tick();
        if (victimBot != null) {
            victimBot.lastHurt = now;
            victimBot.plateDirty = true;
            match.damageNumbers().show(entity, event.getFinalDamage(),
                    DamageNumbers.Kind.ofHit(match.abilities().context().isAbilityDamage(),
                            match.abilities().context().basicHitAccent()));
        }
        Player player = session.combat().resolveAttacker(event);
        UUID attacker = player != null ? player.getUniqueId() : fighterAttacker(event);
        if (attacker == null || attacker.equals(victim) || (player != null && victimBot == null)) {
            // between players the hub and the match's listener already count it
            return;
        }
        double dealt = entity instanceof LivingEntity living
                ? Math.min(event.getFinalDamage(), living.getHealth() + living.getAbsorptionAmount()) : 0;
        session.recordDamage(attacker, victim, dealt);
        if (match.phase() == SiegeMatch.Phase.FIGHT) {
            match.score().recordHit(attacker, victim, now);
        }
    }

    /** A bot's body died: the kill is scored like a player's, and it respawns at its base after the delay. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (legs.containsKey(dead.getUniqueId())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }
        SiegeBot bot = bodies.remove(dead.getUniqueId());
        if (bot == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        long now = session.tick();
        UUID killer = session.combat().lastAttackerId(bot.id, CombatTracker.DEFAULT_WINDOW_TICKS);
        if (killer != null && (killer.equals(bot.id) || match.side(killer) == null || match.side(killer) == bot.side)) {
            killer = null;
        }
        Component cause = DeathCauses.describe(session.messages(), dead.getLastDamageCause());
        match.fighterKilled(bot.id, killer);
        session.stats().addDeath(bot.id);
        if (killer != null) {
            session.stats().addKill(killer);
        }
        session.combat().forget(bot.id);
        session.broadcast(killer != null
                ? session.messages().get("kill-feed.kill", Messages.tag("killer", session.displayName(killer)),
                        Messages.tag("victim", session.displayName(bot.id)))
                : session.messages().get("kill-feed.death", Messages.tag("victim", session.displayName(bot.id)),
                        Messages.tag("cause", cause)));
        // The body plays its death; the legs and the plate go now.
        bot.body = null;
        despawn(bot);
        bot.brain.reset();
        bot.respawnAt = match.phase() == SiegeMatch.Phase.FIGHT ? now + match.respawnTicks(bot.id) : now + 20;
        bot.recallUntil = -1;
    }

    // =================================================================== helpers

    private @Nullable UUID fighterAttacker(EntityDamageEvent event) {
        UUID id = fighterOf(event.getDamageSource().getCausingEntity());
        return id != null ? id : fighterOf(event.getDamageSource().getDirectEntity());
    }

    boolean isProtected(SiegeBot bot) {
        return bot.protectedUntil > session.tick();
    }

    private boolean atBase(SiegeBot bot) {
        Location base = match.spawnFor(bot.id);
        Mannequin body = bot.body;
        return body != null && body.getWorld().equals(base.getWorld())
                && Creatures.flatDistanceSquared(body.getLocation(), base) <= BASE_RADIUS * BASE_RADIUS;
    }

    private Location base(SiegeBot bot) {
        return match.layout().spawns(bot.side).getFirst();
    }

    private boolean isChampion(LivingEntity entity) {
        return entity instanceof Player || bodies.containsKey(entity.getUniqueId());
    }

    /** The entity the bot's plan aims at, if it is still there. */
    private @Nullable LivingEntity target(SiegeBot bot) {
        SiegeBotBrain.Plan plan = bot.plan;
        UUID id = plan.target();
        if (id == null) {
            return null;
        }
        SiegeBot other = bots.get(id);
        LivingEntity target;
        if (other != null) {
            target = other.alive() && !isProtected(other) ? other.body : null;
        } else {
            Map<UUID, LivingEntity> sight = seen.get(bot.id);
            target = sight == null ? null : sight.get(id);
        }
        if (target == null || !target.isValid() || target.isDead()
                || target instanceof Player player && (!session.isInPlay(player) || match.isProtected(player))) {
            return null;
        }
        return target;
    }

    private @Nullable Tower tower(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (Tower tower : match.towers()) {
            if (tower.id().equals(id) && !tower.isDestroyed()) {
                return tower;
            }
        }
        return null;
    }

    private JungleObjectives.@Nullable Site site(@Nullable String id) {
        SiegeSystems systems = match.systems();
        if (id == null || systems == null) {
            return null;
        }
        for (JungleObjectives.Site site : systems.jungle().sites()) {
            if (site.objective().id().equals(id)) {
                return site;
            }
        }
        return null;
    }

    private static @Nullable Mob nearest(List<Mob> mobs, Location at) {
        Mob best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Mob mob : mobs) {
            if (!mob.isValid() || mob.isDead() || !mob.getWorld().equals(at.getWorld())) {
                continue;
            }
            double distance = mob.getLocation().distanceSquared(at);
            if (distance < bestDistance) {
                best = mob;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The bot's lane from its base to the enemy base. */
    private LanePath lane(SiegeBot bot) {
        String key = bot.side + ":" + bot.lane;
        LanePath path = lanes.get(key);
        if (path == null) {
            List<Location> points = laneLocations(bot);
            List<SiegeBotBrain.Vec> flat = new ArrayList<>();
            for (Location point : points) {
                flat.add(vec(point));
            }
            path = new LanePath(flat);
            lanes.put(key, path);
        }
        return path;
    }

    private List<Location> laneLocations(SiegeBot bot) {
        String key = bot.side + ":" + bot.lane;
        List<Location> points = laneLocations.get(key);
        if (points == null) {
            points = new ArrayList<>();
            for (LaneSites.Point point : match.sites().lanePath(bot.lane)) {
                points.add(match.layout().at(point));
            }
            if (bot.side == LaneSites.Side.RED) {
                Collections.reverse(points);
            }
            laneLocations.put(key, points);
        }
        return points;
    }

    /** The world location {@code progress} blocks along the bot's lane (its height from the nearest waypoint). */
    private Location laneLocation(SiegeBot bot, double progress) {
        LanePath lane = lane(bot);
        SiegeBotBrain.Vec flat = lane.pointAt(progress);
        Location near = laneLocations(bot).get(lane.segment(Math.max(0, Math.min(lane.length(), progress))));
        return new Location(near.getWorld(), flat.x(), near.getY(), flat.z());
    }

    /** A place of the plan as a world location (on the ground of the bot's height). */
    private Location location(SiegeBotBrain.Vec at, SiegeBot bot) {
        LanePath lane = lane(bot);
        if (lane.distance(at) < 1.0) {
            return laneLocation(bot, lane.progress(at));
        }
        Location base = match.spawnFor(bot.id);
        if (at.distance(vec(base)) < 1.0) {
            return base;
        }
        Location here = bot.body.getLocation();
        return new Location(here.getWorld(), at.x(), here.getY(), at.z());
    }

    private static boolean standable(Location at) {
        Block feet = at.getBlock();
        return feet.isPassable() && feet.getRelative(0, 1, 0).isPassable() && !feet.getRelative(0, -1, 0).isPassable();
    }

    static SiegeBotBrain.Vec vec(Location at) {
        return new SiegeBotBrain.Vec(at.getX(), at.getZ());
    }

    private World world(SiegeBot bot) {
        return bot.body.getWorld();
    }

    private void swing(SiegeBot bot) {
        try {
            bot.body.swingMainHand();
        } catch (RuntimeException testServer) {
            // cosmetic
        }
    }

    private void sound(Location at, Sound sound, float volume, float pitch) {
        match.sounds().at(at, sound, volume, pitch);
    }

    private static void trail(Location from, Location to, Particle particle) {
        World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld())) {
            return;
        }
        Vector step = to.toVector().subtract(from.toVector());
        double length = step.length();
        if (length < 0.5) {
            return;
        }
        int points = (int) Math.min(24, length * 2);
        step.multiply(1.0 / points);
        Location at = from.clone();
        for (int i = 0; i < points; i++) {
            at.add(step);
            world.spawnParticle(particle, at, 1, 0, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------------ chunks

    /**
     * Keeps the chunks around every live bot loaded while the round's fight runs (plugin tickets: a bot's entities are
     * not saved, an unloading chunk would delete them). Only chunks nobody else of the plugin held are taken, and they
     * are let go when the round ends, so the structures' and lanes' own tickets are never touched.
     */
    private void holdChunks() {
        for (SiegeBot bot : bots.values()) {
            if (!bot.alive()) {
                continue;
            }
            Location at = bot.legs.getLocation();
            World world = at.getWorld();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = (at.getBlockX() >> 4) + dx;
                    int cz = (at.getBlockZ() >> 4) + dz;
                    long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
                    if (!heldChunks.contains(key) && world.addPluginChunkTicket(cx, cz, session.plugin())) {
                        heldChunks.add(key);
                    }
                }
            }
        }
    }

    private void releaseChunks() {
        World world = match.layout().world();
        for (long key : heldChunks) {
            world.removePluginChunkTicket((int) (key >> 32), (int) key, session.plugin());
        }
        heldChunks.clear();
    }

    // =================================================================== queries (tests, commands)

    /** The bots, in the order they were enlisted. */
    public List<UUID> ids() {
        return List.copyOf(bots.keySet());
    }

    /** The body of a live bot, or null. */
    public @Nullable Mannequin body(UUID id) {
        SiegeBot bot = bots.get(id);
        return bot == null || !bot.alive() ? null : bot.body;
    }

    /** The invisible walker of a live bot, or null. */
    public @Nullable WanderingTrader legs(UUID id) {
        SiegeBot bot = bots.get(id);
        return bot == null || !bot.alive() ? null : bot.legs;
    }

    public @Nullable BotJob job(UUID id) {
        SiegeBot bot = bots.get(id);
        return bot == null ? null : bot.job;
    }

    public LaneSites.@Nullable Lane lane(UUID id) {
        SiegeBot bot = bots.get(id);
        return bot == null ? null : bot.lane;
    }

    /** What the bot is doing (its last plan). */
    public SiegeBotBrain.@Nullable Plan plan(UUID id) {
        SiegeBot bot = bots.get(id);
        return bot == null ? null : bot.plan;
    }

    /** True while the bot waits to respawn. */
    public boolean isDead(UUID id) {
        SiegeBot bot = bots.get(id);
        return bot != null && bot.respawnAt >= 0;
    }

    /** True while the bot is protected after a respawn. */
    public boolean isProtected(UUID id) {
        SiegeBot bot = bots.get(id);
        return bot != null && isProtected(bot);
    }

    /** The text of a bot's plate (tests). */
    public Component plateText(UUID id) {
        SiegeBot bot = bots.get(id);
        return bot == null ? Component.empty() : bot.plateText;
    }

    /** Makes the bot hit {@code target} with its basic attack now (tests and admins). */
    public void attack(UUID id, LivingEntity target) {
        SiegeBot bot = bots.get(id);
        if (bot != null && bot.alive()) {
            basicAttack(bot, target, settings.stats(bot.job));
        }
    }

    /** One line per bot: name, job, lane, level, health, what it does and where (/게임 공성전 봇). */
    public List<Component> status() {
        List<Component> out = new ArrayList<>();
        long now = session.tick();
        for (SiegeBot bot : bots.values()) {
            Component name = Component.text(bot.name, match.team(bot.side).color());
            String lane = bot.lane == LaneSites.Lane.TOP ? "탑" : "바텀";
            if (!bot.alive()) {
                long left = bot.respawnAt < 0 ? 0 : Math.max(0, (bot.respawnAt - now + 19) / 20);
                out.add(messages.get("bots.status-dead", Messages.tag("name", name),
                        Messages.tag("job", bot.job.koreanName()), Messages.tag("lane", lane),
                        Messages.tag("seconds", left)));
                continue;
            }
            Location at = bot.body.getLocation();
            out.add(messages.get("bots.status", Messages.tag("name", name), Messages.tag("job", bot.job.koreanName()),
                    Messages.tag("lane", lane), Messages.tag("level", match.level(bot.id)),
                    Messages.tag("health", (int) Math.ceil(bot.health())),
                    Messages.tag("max", (int) Math.ceil(bot.maxHealth())),
                    Messages.tag("action", bot.recallUntil >= 0 ? "RECALL" : bot.plan.action().name()),
                    Messages.tag("x", at.getBlockX()), Messages.tag("z", at.getBlockZ()),
                    Messages.tag("upgrades", bot.upgrades)));
        }
        return out;
    }

    /** The chunks this module holds now (tests). */
    public int heldChunks() {
        return heldChunks.size();
    }

    public SiegeBotSettings settings() {
        return settings;
    }
}
