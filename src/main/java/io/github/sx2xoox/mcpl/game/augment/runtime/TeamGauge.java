package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.List;

/**
 * 팀전 team power for the sidebar (pure): the team's total current health over its total max health, a member out of
 * play counting as 0 of its max, drawn like a health bar: {@link #SEGMENTS} bars, the filled ones in the team colour.
 */
public final class TeamGauge {

    /** Segments of the gauge. */
    public static final int SEGMENTS = 20;
    /** One segment (thin, so many fit; the font draws it everywhere). */
    public static final String SEGMENT = "|";

    private TeamGauge() {
    }

    /**
     * A team member as the gauge sees it.
     *
     * @param health current health (ignored when out of play; capped at {@code max})
     * @param max    max health (its last known one when out of play)
     * @param alive  in play
     */
    public record Member(double health, double max, boolean alive) {
    }

    /** The team's power, 0..1: the alive members' health over every member's max health; 0 without members. */
    public static double share(List<Member> members) {
        double health = 0;
        double max = 0;
        for (Member m : members) {
            double cap = Math.max(0, m.max());
            max += cap;
            if (m.alive()) {
                health += Math.clamp(m.health(), 0, cap);
            }
        }
        return max <= 0 ? 0 : Math.clamp(health / max, 0, 1);
    }

    /** {@code share} as a whole percentage; a team with anyone left shows at least 1%. */
    public static int percent(double share) {
        if (share <= 0) {
            return 0;
        }
        return Math.clamp(Math.round(share * 100), 1, 100);
    }

    /** Filled segments of {@code segments} for {@code share} (at least one while the team has any health). */
    public static int filled(double share, int segments) {
        return share <= 0 ? 0 : Math.clamp(Math.round(share * segments), 1, segments);
    }
}
