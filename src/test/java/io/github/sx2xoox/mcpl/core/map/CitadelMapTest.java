package io.github.sx2xoox.mcpl.core.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sx2xoox.mcpl.core.map.citadel.CitadelLayout;
import io.github.sx2xoox.mcpl.core.map.citadel.CitadelMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** The 폐허의 성채 map is playable: you can walk everywhere from the center and back, and no block misbehaves. */
class CitadelMapTest {

    private static CitadelMap map;
    private static MapCanvas canvas;
    private static MapAudit.Report report;

    @BeforeAll
    static void build() {
        MockBukkit.mock();
        map = new CitadelMap();
        canvas = map.render();
        report = new MapAudit(canvas, map.playRadius(), CitadelMapTest::zoneOf).run(0, 0);
        System.out.println("[citadel] " + canvas.blockCount() + " blocks; " + report.summary());
    }

    @AfterAll
    static void tearDown() {
        MockBukkit.unmock();
    }

    /** The zone owning a column, for reports. */
    static String zoneOf(int x, int z) {
        if (CitadelLayout.isRingRoad(x, z) || CitadelLayout.isRadialRoad(x, z)) {
            return "road";
        }
        for (MapZone zone : map.zones()) {
            if (zone.contains(x, z)) {
                return zone.name();
            }
        }
        return "none";
    }

    @Test
    void theMapIsAbout210BlocksAcrossAndFitsItsBudget() {
        assertEquals(105, map.playRadius());
        assertTrue(canvas.blockCount() < 2_500_000, "the map stays under 2.5M blocks: " + canvas.blockCount());
    }

    @Test
    void everyPlaceCanBeReachedFromTheCenterAndLeftAgain() {
        assertTrue(report.unreachableShare() <= 0.03, "at most 3% of the standing places are cut off:\n"
                + report.summary());
        for (MapAudit.Component part : report.unreachable()) {
            assertTrue(part.size() <= 80, "no cut-off area larger than 80 places:\n" + report.summary());
        }
        assertTrue(report.strandedShare() <= 0.01, "at most 1% of the places are pits you cannot leave:\n"
                + report.summary());
        for (MapAudit.Component part : report.stranded()) {
            assertTrue(part.size() <= 40, "no pit larger than 40 places:\n" + report.summary());
        }
    }

    @Test
    void noBlockMisbehavesOnAServer() {
        assertTrue(report.problems().isEmpty(), "no floating sand, spilling water, lava or fire:\n"
                + report.summary());
    }
}
