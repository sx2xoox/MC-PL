package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The shared minds of the bot teams of a 봇전 팀전 (Bukkit side of {@link TeamTactics}): what each team has seen
 * (every teammate's sightings and fights, never more), the hits that make its focus and its peels, its leader (a human
 * teammate, else the strongest bot) and who builds its shared base. Also counts what the teamplay measurements need
 * (focus-fire share, distance to teammates).
 */
final class BotTeams {

    /** One sighting of an enemy by a teammate. */
    record Intel(LivingEntity entity, Location at, long tick, UUID by) {
    }

    private final BotManager manager;
    private final GameSession session;
    private final BotSettings.Team settings;
    private final Map<MatchTeam, Map<UUID, Intel>> intel = new HashMap<>();
    private final Deque<TeamTactics.Hit> hits = new ArrayDeque<>();
    private final Map<MatchTeam, UUID> leaders = new HashMap<>();
    private final Map<MatchTeam, Long> leaderAt = new HashMap<>();
    private final Map<MatchTeam, UUID> architects = new HashMap<>();
    private final Map<MatchTeam, TeamTactics.SharedBase> bases = new HashMap<>();
    private final Map<MatchTeam, Long> baseAt = new HashMap<>();
    /** Bot hits on enemies, and those on an enemy a teammate hit in the {@link TeamTactics#FOCUS_TICKS} before. */
    private long botHits;
    private long focusHits;
    /** Distance of each living bot to its nearest teammate, sampled every few seconds in the fight. */
    private double mateDistance;
    private long mateSamples;
    /** Behaviours of the living bots at each sample (how much they regroup, defend the base ...). */
    private final Map<BotBrain.Behaviour, Integer> behaviours = new java.util.EnumMap<>(BotBrain.Behaviour.class);

    BotTeams(BotManager manager, GameSession session, BotSettings.Team settings) {
        this.manager = manager;
        this.session = session;
        this.settings = settings;
    }

    BotSettings.Team settings() {
        return settings;
    }

    boolean active() {
        return session.isTeamMode();
    }

    private @Nullable MatchTeam team(UUID id) {
        return session.teamOf(id);
    }

    /** The living body of a participant in play: a bot's body or a player in survival. */
    @Nullable LivingEntity entity(UUID id) {
        if (!session.isAlive(id)) {
            return null;
        }
        LivingEntity body = manager.body(id);
        if (body != null) {
            return body;
        }
        Player player = Bukkit.getPlayer(id);
        return player != null && player.isOnline() && !player.isDead() ? player : null;
    }

    // =================================================================== what the team knows

    /** {@code by} perceives {@code enemy} at {@code at}: the whole team knows. */
    void report(UUID by, UUID enemy, LivingEntity entity, Location at, long now) {
        MatchTeam team = team(by);
        if (team == null) {
            return;
        }
        intel.computeIfAbsent(team, any -> new HashMap<>()).put(enemy, new Intel(entity, at.clone(), now, by));
    }

    /** What {@code bot}'s teammates have seen lately (enemies still in play only). */
    List<Intel> shared(UUID bot, long now) {
        MatchTeam team = team(bot);
        Map<UUID, Intel> known = team == null ? null : intel.get(team);
        if (known == null) {
            return List.of();
        }
        known.values().removeIf(i -> now - i.tick() > BotController.MEMORY_TICKS || i.entity().isDead());
        List<Intel> out = new ArrayList<>();
        for (Map.Entry<UUID, Intel> e : known.entrySet()) {
            if (!e.getValue().by().equals(bot) && session.isAlive(e.getKey()) && !session.sameTeam(bot, e.getKey())) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /**
     * A hit between participants: feeds the focus and peel choices, tells both teams where the other side is (a
     * teammate in a fight sees whom it fights) and counts focus fire.
     */
    void hit(UUID attacker, UUID victim, @Nullable LivingEntity attackerBody, LivingEntity victimBody, long now) {
        if (!active() || session.sameTeam(attacker, victim)) {
            return;
        }
        while (!hits.isEmpty() && now - hits.peekFirst().tick() > TeamTactics.FOCUS_TICKS) {
            hits.removeFirst();
        }
        if (manager.isBot(attacker)) {
            botHits++;
            for (TeamTactics.Hit h : hits) {
                if (h.victim().equals(victim) && !h.attacker().equals(attacker) && session.sameTeam(h.attacker(), attacker)) {
                    focusHits++;
                    break;
                }
            }
        }
        hits.addLast(new TeamTactics.Hit(attacker, victim, now));
        if (hits.size() > 256) {
            hits.removeFirst();
        }
        report(attacker, victim, victimBody, victimBody.getLocation(), now);
        if (attackerBody != null) {
            report(victim, attacker, attackerBody, attackerBody.getLocation(), now);
        }
    }

    // =================================================================== leader, focus, peel

    private List<TeamTactics.Mate> mates(MatchTeam team) {
        List<TeamTactics.Mate> out = new ArrayList<>();
        for (UUID member : team.members()) {
            LivingEntity body = entity(member);
            if (body == null) {
                out.add(new TeamTactics.Mate(member, !manager.isBot(member), false, 0, 0));
                continue;
            }
            double max = BotController.maxHealth(body);
            double hp = body.getHealth() + body.getAbsorptionAmount();
            out.add(new TeamTactics.Mate(member, !manager.isBot(member), true,
                    BotBrain.power(hp, manager.gearOf(body)), Math.min(1, body.getHealth() / Math.max(1, max))));
        }
        return out;
    }

    /** The team's leader (re-chosen at most once a second). */
    @Nullable UUID leader(UUID member, long now) {
        MatchTeam team = team(member);
        if (team == null) {
            return null;
        }
        UUID current = leaders.get(team);
        Long at = leaderAt.get(team);
        if (current != null && at != null && now - at < 20 && session.isAlive(current)) {
            return current;
        }
        UUID chosen = TeamTactics.leader(mates(team), current);
        leaderAt.put(team, now);
        if (chosen == null) {
            leaders.remove(team);
        } else {
            leaders.put(team, chosen);
        }
        return chosen;
    }

    /**
     * Whom {@code member} keeps close to: its leader; while farming a bot-led team gathers around its base builder
     * instead (the base is where the team will be).
     */
    @Nullable UUID anchor(UUID member, boolean fight, long now) {
        UUID leader = leader(member, now);
        if (!fight && leader != null && manager.isBot(leader)) {
            TeamTactics.SharedBase shared = sharedBase(member, now);
            if (shared != null) {
                return shared.architect();
            }
        }
        return leader;
    }

    private Set<UUID> enemiesInPlay(UUID of) {
        Set<UUID> out = new HashSet<>();
        for (UUID id : session.aliveIds()) {
            if (!session.sameTeam(of, id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** The enemy {@code bot}'s team focuses, or null. */
    @Nullable UUID focus(UUID bot, long now) {
        MatchTeam team = team(bot);
        if (team == null) {
            return null;
        }
        Map<UUID, Double> health = new HashMap<>();
        Set<UUID> enemies = enemiesInPlay(bot);
        for (TeamTactics.Hit h : hits) {
            if (enemies.contains(h.victim()) && !health.containsKey(h.victim())) {
                LivingEntity body = entity(h.victim());
                health.put(h.victim(), body == null ? 1 : body.getHealth() / Math.max(1, BotController.maxHealth(body)));
            }
        }
        return TeamTactics.focus(List.copyOf(hits), team.members(), enemies, health, now);
    }

    TeamTactics.@Nullable Peel peel(UUID bot, long now) {
        MatchTeam team = team(bot);
        return team == null ? null : TeamTactics.peel(mates(team), List.copyOf(hits), enemiesInPlay(bot), bot, now);
    }

    /** What the brain of {@code bot} (standing at {@code at}) knows about its team; null in free-for-all. */
    BotBrain.@Nullable TeamView view(UUID bot, Location at, boolean fight, long now) {
        MatchTeam team = team(bot);
        if (team == null) {
            return null;
        }
        int near = 0;
        for (UUID member : team.members()) {
            LivingEntity body = member.equals(bot) ? null : entity(member);
            if (body != null && body.getWorld().equals(at.getWorld())
                    && body.getLocation().distanceSquared(at) <= BotBrain.NEAR * BotBrain.NEAR) {
                near++;
            }
        }
        UUID leader = anchor(bot, fight, now);
        double leaderDistance = 0;
        if (leader != null && !leader.equals(bot)) {
            LivingEntity body = entity(leader);
            leaderDistance = body == null || !body.getWorld().equals(at.getWorld()) ? 0
                    : body.getLocation().distance(at);
        }
        TeamTactics.Peel peel = fight ? peel(bot, now) : null;
        return new BotBrain.TeamView(near, leaderDistance, settings.cohesionRadius(), leader == null
                || leader.equals(bot), fight ? focus(bot, now) : null, peel == null ? null : peel.enemy(),
                peel != null && peel.human());
    }

    /** Where a straying bot heads: the teammate it peels for, else its leader. */
    @Nullable Location rally(UUID bot, long now) {
        TeamTactics.Peel peel = manager.fightOn() ? peel(bot, now) : null;
        LivingEntity body = peel == null ? null : entity(peel.mate());
        if (body == null) {
            UUID leader = anchor(bot, manager.fightOn(), now);
            body = leader == null || leader.equals(bot) ? null : entity(leader);
        }
        return body == null ? null : body.getLocation();
    }

    /** The nearest teammate in good health (to fall back to), or null. */
    @Nullable Location healthyMate(UUID bot, Location at) {
        MatchTeam team = team(bot);
        if (team == null) {
            return null;
        }
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        for (UUID member : team.members()) {
            LivingEntity body = member.equals(bot) ? null : entity(member);
            if (body == null || !body.getWorld().equals(at.getWorld())
                    || body.getHealth() < BotController.maxHealth(body) * 0.5) {
                continue;
            }
            double d = body.getLocation().distanceSquared(at);
            if (d < bestDistance) {
                bestDistance = d;
                best = body.getLocation();
            }
        }
        return best;
    }

    // =================================================================== the shared base

    /** The team base of {@code bot}'s team (refreshed once a second), or null (no team, no builder, turned off). */
    TeamTactics.@Nullable SharedBase sharedBase(UUID bot, long now) {
        MatchTeam team = team(bot);
        if (team == null || !settings.sharedBase()) {
            return null;
        }
        Long at = baseAt.get(team);
        TeamTactics.SharedBase known = bases.get(team);
        if (at != null && now - at < 20 && (known == null || session.isAlive(known.architect()))) {
            return known;
        }
        List<TeamTactics.Builder> builders = new ArrayList<>();
        for (UUID member : team.members()) {
            BotController mate = manager.bot(member);
            if (mate != null && session.isAlive(member) && mate.body() != null && mate.traits().builds()) {
                builders.add(new TeamTactics.Builder(member, mate.ownBuildRate(), mate.ownStock(),
                        mate.traits().traps()));
            }
        }
        TeamTactics.SharedBase chosen = TeamTactics.sharedBase(builders, architects.get(team));
        baseAt.put(team, now);
        if (chosen == null) {
            bases.remove(team);
            architects.remove(team);
        } else {
            bases.put(team, chosen);
            architects.put(team, chosen.architect());
        }
        return chosen;
    }

    // =================================================================== measurements

    /** Samples the distance of every living bot to its nearest living teammate. */
    void sample() {
        if (!active()) {
            return;
        }
        for (BotController bot : manager.all()) {
            LivingEntity body = manager.body(bot.id());
            MatchTeam team = team(bot.id());
            if (body == null || team == null) {
                continue;
            }
            if (bot.decision() != null) {
                behaviours.merge(bot.decision().behaviour(), 1, Integer::sum);
            }
            double best = Double.MAX_VALUE;
            for (UUID member : team.members()) {
                LivingEntity mate = member.equals(bot.id()) ? null : entity(member);
                if (mate != null && mate.getWorld().equals(body.getWorld())) {
                    best = Math.min(best, mate.getLocation().distance(body.getLocation()));
                }
            }
            if (best < Double.MAX_VALUE) {
                mateDistance += best;
                mateSamples++;
            }
        }
    }

    /** Share of bot hits on an enemy a teammate hit just before (focus fire), 0 without hits. */
    double focusShare() {
        return botHits == 0 ? 0 : (double) focusHits / botHits;
    }

    /**
     * One line for the server log: focus-fire share, distance to teammates, sampled behaviours and each team's
     * shared base (its architect's progress: placed / closing / all).
     */
    String report() {
        StringBuilder out = new StringBuilder();
        out.append(String.format(java.util.Locale.ROOT, "focus=%.2f (%d/%d hits) mateDist=%.1f samples=%d",
                focusShare(), focusHits, botHits, averageMateDistance(), mateSamples));
        out.append(" behaviours=").append(behaviours);
        for (Map.Entry<MatchTeam, UUID> e : architects.entrySet()) {
            BotController arch = manager.bot(e.getValue());
            BasePlanner.Plan plan = arch == null ? null : arch.base();
            out.append(" base[").append(e.getKey().id()).append("]=");
            out.append(plan == null ? "none" : plan.design() + " " + arch.built() + "/" + plan.closure() + "/"
                    + plan.placements().size() + " at " + plan.x() + "," + plan.floorY() + "," + plan.z());
        }
        return out.toString();
    }

    long botHits() {
        return botHits;
    }

    /** Average distance of a living bot to its nearest teammate (blocks), NaN without samples. */
    double averageMateDistance() {
        return mateSamples == 0 ? Double.NaN : mateDistance / mateSamples;
    }
}
