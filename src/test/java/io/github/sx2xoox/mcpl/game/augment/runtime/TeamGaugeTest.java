package io.github.sx2xoox.mcpl.game.augment.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 팀전 team power gauge: total health over total max health, the dead count as 0 of their max. */
class TeamGaugeTest {

    @Test
    void fullTeamIsFull() {
        double share = TeamGauge.share(List.of(new TeamGauge.Member(20, 20, true), new TeamGauge.Member(24, 24, true)));
        assertEquals(1.0, share, 1e-9);
        assertEquals(100, TeamGauge.percent(share));
        assertEquals("▰▰▰▰▰▰▰▰", TeamGauge.bar(share, 8));
    }

    @Test
    void deadMembersCountAsZeroOfTheirMax() {
        // 20/20 + 10/20 + dead (0/20) = 30/60
        double share = TeamGauge.share(List.of(new TeamGauge.Member(20, 20, true),
                new TeamGauge.Member(10, 20, true), new TeamGauge.Member(15, 20, false)));
        assertEquals(0.5, share, 1e-9);
        assertEquals(50, TeamGauge.percent(share));
        assertEquals("▰▰▰▰▱▱▱▱", TeamGauge.bar(share, 8));
    }

    @Test
    void healthIsCappedAtItsMax() {
        assertEquals(1.0, TeamGauge.share(List.of(new TeamGauge.Member(30, 20, true))), 1e-9);
        assertEquals(0.0, TeamGauge.share(List.of(new TeamGauge.Member(-3, 20, true))), 1e-9);
    }

    @Test
    void wipedOrEmptyTeamIsEmpty() {
        double share = TeamGauge.share(List.of(new TeamGauge.Member(0, 20, false), new TeamGauge.Member(0, 20, false)));
        assertEquals(0.0, share, 1e-9);
        assertEquals(0, TeamGauge.percent(share));
        assertEquals("▱▱▱▱▱▱▱▱", TeamGauge.bar(share, 8));
        assertEquals(0.0, TeamGauge.share(List.of()), 1e-9);
    }

    @Test
    void anyHealthLeftShowsAtLeastOneSegmentAndOnePercent() {
        double share = TeamGauge.share(List.of(new TeamGauge.Member(0.5, 20, true), new TeamGauge.Member(0, 20, false),
                new TeamGauge.Member(0, 20, false)));
        assertEquals(1, TeamGauge.percent(share));
        assertEquals("▰▱▱▱▱▱▱▱", TeamGauge.bar(share, 8));
    }

    @Test
    void roundsToTheNearestSegment() {
        // 62% of 8 = 4.96 -> 5 segments
        double share = TeamGauge.share(List.of(new TeamGauge.Member(37.2, 60, true)));
        assertEquals(62, TeamGauge.percent(share));
        assertEquals("▰▰▰▰▰▱▱▱", TeamGauge.bar(share, 8));
    }
}
