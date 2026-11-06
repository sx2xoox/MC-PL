package io.github.sx2xoox.mcpl.game.augment.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The recent hits a bot match keeps for the bots' chat. */
class FightLogTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();
    private final FightLog log = new FightLog();

    @Test
    void aThirdPartyIsSomeoneNewHittingABotThatTradesHitsWithAnother() {
        log.record(a, b, 100, 3);
        log.record(b, a, 105, 3);
        log.record(c, a, 110, 3);
        assertEquals(c, log.thirdParty(a, 110, 160));
        log.record(b, a, 120, 3);
        assertNull(log.thirdParty(a, 120, 160), "b was already in the fight");
        log.record(d, c, 130, 3);
        assertNull(log.thirdParty(c, 130, 160), "c was not trading hits with anyone else");
    }

    @Test
    void sumsAndOpponentsCoverTheWindowOnly() {
        log.record(a, b, 0, 5);
        log.record(a, b, 100, 2);
        log.record(c, a, 110, 4);
        assertEquals(2, log.dealt(a, 110, 50), 1e-9);
        assertEquals(4, log.taken(a, 110, 50), 1e-9);
        assertEquals(Set.of(b, c), log.opponents(a, 110, 50));
        assertEquals(c, log.lastAttacker(a, 110, 50));
        assertNull(log.lastAttacker(b, 400, 50));
        log.record(a, a, 120, 1);
        assertTrue(log.since(120, 5).isEmpty(), "self-hits are not kept");
    }
}
