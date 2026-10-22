package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.abilities.BuiltinAbilities;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.util.Vector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every built-in ability, used in the practice mode against its training dummies, must run without a single error.
 * The dummies are custom-named mannequins, and the test doubles name them like Paper does ({@code getName()} is the
 * legacy "§e..." string), so code that pastes an entity name into MiniMessage fails here as it does on a server.
 */
class PracticeDummySweepTest {

    static Stream<String> abilities() {
        // Ability classes build block data while they initialize, which needs a (mock) server.
        MatchHarness h = MatchHarness.flat();
        try {
            AbilityRegistry registry = new AbilityRegistry();
            BuiltinAbilities.registerAll(registry);
            return registry.all().stream().map(entry -> entry.info().id()).sorted().toList().stream();
        } finally {
            h.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void everyAbilityWorksOnTheTrainingDummies(String id) {
        MatchHarness h = MatchHarness.flat();
        Logger logger = h.hub.plugin().getLogger();
        List<LogRecord> problems = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    problems.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            FlatWorld world = h.flatWorld();
            assertNotNull(world);
            TestPlayer admin = h.join("Admin");
            admin.setOp(true);
            admin.teleport(new Location(world, 0.5, FlatWorld.SURFACE, 0.5, 0f, 0f));
            assertTrue(h.server.dispatchCommand(admin, "게임 능력 연습 " + id));
            h.tickUntil(() -> h.match() != null && h.match().hasStarted() && h.match().phase() == MatchPhase.FIGHT,
                    400, "the practice to start");
            SuperpowerMatch match = h.match();
            assertNotNull(match);
            List<Mannequin> dummies = match.dummiesOf(admin);
            assertFalse(dummies.isEmpty(), "the practice has training dummies");
            Mannequin dummy = dummies.getFirst();
            assertTrue(dummy.getName().startsWith("§"), "the dummy is named like on a server: " + dummy.getName());

            aimAt(admin, dummy);
            dummy.damage(2, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(admin)
                    .withDirectEntity(admin).build());
            h.ticks(5);
            aimAt(admin, dummy);
            match.useActive(admin);
            h.ticks(80);
            aimAt(admin, dummy);
            match.setGauge(admin, 100);
            match.useUltimate(admin);
            h.ticks(240);

            StringBuilder report = new StringBuilder();
            for (LogRecord record : problems) {
                report.append("\n  ").append(record.getMessage());
                Throwable thrown = record.getThrown();
                for (int depth = 0; thrown != null && depth < 3; depth++, thrown = thrown.getCause()) {
                    report.append("\n    ").append(depth == 0 ? "<- " : "caused by ").append(thrown);
                    StackTraceElement[] trace = thrown.getStackTrace();
                    for (int i = 0; i < Math.min(8, trace.length); i++) {
                        report.append("\n      at ").append(trace[i]);
                    }
                }
            }
            assertEquals(0, match.errors().totalFailures(), id + " failed against a training dummy:" + report);
            assertTrue(problems.isEmpty(), id + " logged problems against a training dummy:" + report);
        } finally {
            logger.removeHandler(handler);
            h.close();
        }
    }

    private static void aimAt(TestPlayer player, Entity target) {
        Location point = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        Location look = player.getLocation();
        look.setDirection(point.toVector().subtract(player.getEyeLocation().toVector()));
        player.teleport(look);
        player.setVelocity(new Vector());
    }
}
