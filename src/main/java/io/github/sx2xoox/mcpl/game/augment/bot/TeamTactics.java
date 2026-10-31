package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * 봇전 팀전 teamplay rules (pure, no Bukkit): who leads a team, which enemy the team focuses, whom a bot peels for,
 * who builds the team base and with what, and which sightings a teammate may share. {@link BotTeams} feeds them
 * from the match; the brain ({@link BotBrain#options(BotBrain.Situation, BotBrain.TeamView)}) acts on the result.
 */
public final class TeamTactics {

    /** A hit by a teammate (or on one) counts towards the team's focus this long (ticks). */
    public static final long FOCUS_TICKS = 60;
    /** A teammate hurt this recently by an enemy is "being hit" (ticks). */
    public static final long PEEL_TICKS = 40;
    /** A bot teammate at or below this share of its health needs help. */
    public static final double PEEL_HEALTH = 0.5;
    /** A human teammate at or below this share of its health needs help (bots protect the humans more). */
    public static final double PEEL_HEALTH_HUMAN = 0.65;
    /** How much of each extra builder's rate and stock goes into the team base (walking, getting in each other's way). */
    public static final double HELPER_SHARE = 0.75;

    private TeamTactics() {
    }

    /**
     * A member of a team as the tactics see it.
     *
     * @param id     participant id
     * @param human  a player (bots follow and protect human teammates)
     * @param alive  in play
     * @param power  fighting strength ({@link BotBrain#power}); for the leader choice
     * @param health health share (0..1)
     */
    public record Mate(UUID id, boolean human, boolean alive, double power, double health) {
    }

    /** A hit: {@code attacker} hit {@code victim} at {@code tick}. */
    public record Hit(UUID attacker, UUID victim, long tick) {
    }

    /**
     * The team's leader: a living human teammate (the one already leading if it still lives), else the strongest
     * living bot (the current leader keeps it unless another is clearly stronger: no flip-flopping); null if nobody
     * is alive.
     */
    public static @Nullable UUID leader(List<Mate> mates, @Nullable UUID current) {
        Mate kept = null;
        for (Mate m : mates) {
            if (m.alive() && m.id().equals(current)) {
                kept = m;
            }
        }
        if (kept != null && kept.human()) {
            return kept.id();
        }
        for (Mate m : mates) {
            if (m.alive() && m.human()) {
                return m.id();
            }
        }
        Mate best = mates.stream().filter(Mate::alive)
                .max(Comparator.comparingDouble(Mate::power).thenComparing(m -> m.id().toString()))
                .orElse(null);
        if (best == null) {
            return null;
        }
        if (kept != null && best.power() < kept.power() * 1.25) {
            return kept.id();
        }
        return best.id();
    }

    /**
     * The enemy the team focuses: the one its members hit most in the last {@link #FOCUS_TICKS} (the latest hits
     * count more), the weakest on a tie; null if the team hit nobody lately.
     *
     * @param team    the team's member ids
     * @param enemies who counts as an enemy (still in play)
     * @param health  an enemy's health share, for ties (missing: 1)
     */
    public static @Nullable UUID focus(List<Hit> hits, java.util.Set<UUID> team, java.util.Set<UUID> enemies,
                                       Map<UUID, Double> health, long now) {
        Map<UUID, Double> weight = new HashMap<>();
        for (Hit hit : hits) {
            long age = now - hit.tick();
            if (age < 0 || age > FOCUS_TICKS || !team.contains(hit.attacker()) || !enemies.contains(hit.victim())) {
                continue;
            }
            weight.merge(hit.victim(), 1 + (FOCUS_TICKS - age) / (double) FOCUS_TICKS, Double::sum);
        }
        UUID best = null;
        double bestWeight = 0;
        for (Map.Entry<UUID, Double> e : weight.entrySet()) {
            double w = e.getValue() + (1 - health.getOrDefault(e.getKey(), 1.0)) * 0.5;
            if (w > bestWeight || w == bestWeight && best != null && e.getKey().compareTo(best) < 0) {
                best = e.getKey();
                bestWeight = w;
            }
        }
        return best;
    }

    /** A teammate in trouble and the enemy hitting it. */
    public record Peel(UUID mate, UUID enemy, boolean human) {
    }

    /**
     * The teammate most in trouble (lowest health share at or under {@link #PEEL_HEALTH}, a human's under
     * {@link #PEEL_HEALTH_HUMAN}) that an enemy hit in the last {@link #PEEL_TICKS}, with that enemy; null if nobody
     * needs help. {@code self} never peels for itself.
     */
    public static @Nullable Peel peel(List<Mate> mates, List<Hit> hits, java.util.Set<UUID> enemies, UUID self,
                                      long now) {
        Peel best = null;
        double bestHealth = 2;
        for (Mate m : mates) {
            if (!m.alive() || m.id().equals(self) || m.health() > (m.human() ? PEEL_HEALTH_HUMAN : PEEL_HEALTH)) {
                continue;
            }
            UUID enemy = null;
            long latest = Long.MIN_VALUE;
            for (Hit hit : hits) {
                if (hit.victim().equals(m.id()) && enemies.contains(hit.attacker()) && now - hit.tick() <= PEEL_TICKS
                        && hit.tick() > latest) {
                    enemy = hit.attacker();
                    latest = hit.tick();
                }
            }
            // a human counts as a bit more hurt than it is: the bots look after their humans first
            double urgency = m.health() - (m.human() ? 0.15 : 0);
            if (enemy != null && urgency < bestHealth) {
                best = new Peel(m.id(), enemy, m.human());
                bestHealth = urgency;
            }
        }
        return best;
    }

    /**
     * A bot that could build: its id, base-building rate (placements a second), block stock and whether it is a
     * trapper (it traps around the team base rather than designing it: a trap field is a death trap for teammates
     * who have to leave it in a hurry when the border comes).
     */
    public record Builder(UUID id, double rate, int stock, boolean trapper) {

        public Builder(UUID id, double rate, int stock) {
            this(id, rate, stock, false);
        }
    }

    /** The team base's builder and what the team puts into it. */
    public record SharedBase(UUID architect, double rate, int stock, int helpers) {
    }

    /**
     * Who builds the team base and with what: the best base builder (highest rate: the base specialist whose design
     * the team gets, a trapper only when nobody else builds; the current one keeps it while alive), with its own rate and stock plus {@link #HELPER_SHARE}
     * of every other builder's. Null without builders.
     */
    public static @Nullable SharedBase sharedBase(List<Builder> builders, @Nullable UUID current) {
        if (builders.isEmpty()) {
            return null;
        }
        Builder architect = null;
        for (Builder b : builders) {
            if (b.id().equals(current)) {
                architect = b;
            }
        }
        if (architect == null) {
            architect = builders.stream().max(Comparator.comparing((Builder b) -> !b.trapper())
                    .thenComparingDouble(Builder::rate).thenComparing(b -> b.id().toString())).orElseThrow();
        }
        double rate = architect.rate();
        int stock = architect.stock();
        int helpers = 0;
        for (Builder b : builders) {
            if (b != architect) {
                rate += b.rate() * HELPER_SHARE;
                stock += (int) Math.round(b.stock() * HELPER_SHARE);
                helpers++;
            }
        }
        return new SharedBase(architect.id(), rate, stock, helpers);
    }

    /** A sighting: an enemy seen by a teammate at a tick. */
    public record Sighting(UUID enemy, UUID by, long tick) {
    }

    /**
     * What a bot learns from its team: the latest sighting of each enemy not older than {@code memory} ticks, from
     * any teammate (itself included). Teammates only share what they saw or fought: no enemy anyone on the team has
     * not perceived.
     */
    public static List<Sighting> shared(List<Sighting> sightings, java.util.Set<UUID> team, long now, long memory) {
        Map<UUID, Sighting> latest = new HashMap<>();
        for (Sighting s : sightings) {
            if (!team.contains(s.by()) || now - s.tick() > memory) {
                continue;
            }
            latest.merge(s.enemy(), s, (a, b) -> a.tick() >= b.tick() ? a : b);
        }
        return new ArrayList<>(latest.values());
    }
}
