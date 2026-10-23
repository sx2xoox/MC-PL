package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;

import io.github.sx2xoox.mcpl.core.config.BlockDamageScope;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Abilities that break terrain (explosions and heavy impacts): they scar the battlefield where the server allows it
 * ({@code protection.ability-block-damage}), and everything comes back when the match ends. The test world is not a
 * battle map world, so these scenarios switch the setting to {@code ALL_WORLDS}; with the default nothing breaks.
 */
class TerrainAbilitiesTest {

    private static final int TOP = FlatWorld.FLOOR_Y;
    private static final int Y = (int) FlatWorld.SURFACE;

    private static Scenario.Builder breaking(String id) {
        return Scenario.builder(id).configure(h -> {
            h.hub.config().setAbilityBlockDamage(BlockDamageScope.ALL_WORLDS);
            h.settings().set("ultimate.per-damage-dealt", 0);
            h.settings().set("ultimate.per-damage-taken", 0);
            h.settings().set("ultimate.per-second", 0);
        });
    }

    private static int removed(Scenario s) {
        return s.h.hub.tempBlocks().removedCount();
    }

    private static void face(Player player, float yaw) {
        Location at = player.getLocation();
        at.setYaw(yaw);
        at.setPitch(0);
        player.teleport(at);
    }

    /** No item lies anywhere (nothing broken ever drops) and the flying chunks are gone after a moment. */
    private static void assertNoDropsAndChunksSettle(Scenario s) {
        for (Entity entity : s.world.getEntities()) {
            assertFalse(entity instanceof Item, "an item dropped: " + entity.getLocation());
        }
        s.ticks(60);
        for (Entity entity : s.world.getEntities()) {
            assertFalse(entity instanceof BlockDisplay display && display.getBlock().getMaterial() == Material.STONE,
                    "a chunk of broken stone is still flying");
        }
    }

    /** Ends the match: the craters fill in, and the world is exactly as before (the clean-world check). */
    private static void finishRestored(Scenario s, Consumer<Scenario> clearScenery) {
        s.finishMatch();
        assertEquals(0, removed(s), "every broken block came back at the end of the match");
        clearScenery.accept(s);
        s.assertNoProblems();
        s.assertCleanWorld();
    }

    @Test
    void nothingBreaksOutsideBattleMapsByDefault() {
        try (Scenario s = Scenario.ffa("creeper")) {
            s.assertSucceeded(s.active(), "자폭");
            s.ticks(s.paramTicks("fuse") + 5);
            assertEquals(0, removed(s), "an ordinary world keeps its blocks");
            assertEquals(0, s.session.terrain().brokenCount());
            s.finishClean();
        }
    }

    @Test
    void nothingBreaksWhenSwitchedOff() {
        try (Scenario s = Scenario.builder("creeper")
                .configure(h -> h.hub.config().setAbilityBlockDamage(BlockDamageScope.OFF)).start()) {
            s.assertSucceeded(s.active(), "자폭");
            s.ticks(s.paramTicks("fuse") + 5);
            assertEquals(0, removed(s));
            s.finishClean();
        }
    }

    @Test
    void creeperBlastLeavesABowlThatComesBackAtTheEnd() {
        try (Scenario s = breaking("creeper").start()) {
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, -9.5, 9.5, 180);
            s.assertSucceeded(s.active(), "자폭");
            s.ticks(s.paramTicks("fuse") + 3);
            assertTrue(removed(s) > 0, "the blast broke the ground");
            assertTrue(s.world.getBlockAt(0, TOP, 0).getType().isAir(), "a crater under the creeper");
            for (int x = -5; x <= 5; x++) {
                for (int z = -5; z <= 5; z++) {
                    assertEquals(Material.STONE, s.world.getBlockAt(x, TOP - 2, z).getType(),
                            "at most two deep at " + x + "," + z);
                }
            }
            assertNoDropsAndChunksSettle(s);
            assertEquals(removed(s), s.session.terrain().brokenCount());
            s.ticks(60);
            assertTrue(removed(s) > 0, "the crater stays for the rest of the match");
            finishRestored(s, x -> { });
        }
    }

    @Test
    void theMatchLimitCapsBrokenBlocks() {
        try (Scenario s = breaking("creeper").configure(h -> h.hub.config().setAbilityBlockDamageLimit(6)).start()) {
            s.place(s.enemy, 0.5, 9.5, 180);
            s.assertSucceeded(s.active(), "자폭");
            s.ticks(s.paramTicks("fuse") + 3);
            assertTrue(removed(s) > 0 && removed(s) <= 6, "at most the limit: " + removed(s));
            finishRestored(s, x -> { });
        }
    }

    @Test
    void bomberCarpetScarsTheBombingRun() {
        try (Scenario s = breaking("bomber").start()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "융단폭격");
            s.ticks(s.paramTicks("carpet_duration") + 25);
            assertTrue(removed(s) > 0, "the bombs blew out craters");
            assertNoDropsAndChunksSettle(s);
            finishRestored(s, x -> { });
        }
    }

    @Test
    void bomberGrenadeBlastsTheGround() {
        try (Scenario s = breaking("bomber").start()) {
            s.place(s.enemy2, 0.5, -10.5, 0);
            s.aimAtFloor(s.owner, 0.5, 3.5);
            s.assertSucceeded(s.active(), "수류탄");
            s.ticks(s.paramTicks("grenade_fuse") + 3);
            assertTrue(removed(s) > 0, "the grenade broke the ground it lay on");
            s.tickUntilGrounded(s.owner, 200);
            finishRestored(s, x -> { });
        }
    }

    @Test
    void minerRockfallDentsTheGround() {
        try (Scenario s = breaking("miner").start()) {
            s.place(s.enemy2, 8.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "낙석");
            s.ticks(s.paramTicks("rock_delay") + 40);
            assertTrue(removed(s) > 0, "the rocks broke the ground");
            s.ticks(s.paramTicks("rubble_duration"));
            assertEquals(0, s.h.hub.tempBlocks().size(), "the rubble cleared");
            finishRestored(s, x -> { });
        }
    }

    @Test
    void earthQuakeCrumblesTheWallItRunsInto() {
        try (Scenario s = breaking("earth").start()) {
            s.world.wallAlongX(-4, 5, 2, 2, Material.STONE_BRICKS);
            s.assertSucceeded(s.active(), "지진");
            s.ticks(30);
            assertTrue(removed(s) > 0, "the wave breached the wall");
            assertTrue(s.world.getBlockAt(0, Y, 2).getType().isAir(), "right in front of the earth bender");
            finishRestored(s, t -> {
                assertEquals(Material.STONE_BRICKS, t.world.getBlockAt(0, Y, 2).getType(), "the wall is whole again");
                t.world.wallAlongX(-4, 5, 2, 2, Material.AIR);
            });
        }
    }

    @Test
    void titanSlamLeavesACraterAroundTheLanding() {
        try (Scenario s = breaking("titan").start()) {
            s.place(s.enemy2, 0.5, -9.5, 0);
            s.assertSucceeded(s.active(), "대지 강타");
            s.ticks(3);
            s.tickUntilGrounded(s.owner, 120);
            s.ticks(2);
            assertTrue(removed(s) > 0, "the slam broke the ground");
            s.tickUntilGrounded(s.owner, 40);
            finishRestored(s, x -> { });
        }
    }

    @Test
    void mechMissilesChipTheWallTheyHit() {
        try (Scenario s = breaking("mech").start()) {
            s.world.wallAlongX(-4, 4, 3, 7, Material.STONE_BRICKS);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "미사일 포드 into a wall");
            s.ticks(80);
            assertTrue(removed(s) > 0, "the warheads broke the wall");
            finishRestored(s, t -> {
                for (int x = -4; x <= 4; x++) {
                    for (int y = Y; y < Y + 7; y++) {
                        assertEquals(Material.STONE_BRICKS, t.world.getBlockAt(x, y, 3).getType(), "wall restored");
                    }
                }
                t.world.wallAlongX(-4, 4, 3, 7, Material.AIR);
            });
        }
    }

    @Test
    void minotaurRushTunnelsThroughRealWalls() {
        try (Scenario s = breaking("minotaur").start()) {
            s.place(s.enemy, 9.5, -9.5, 0);
            s.place(s.enemy2, -9.5, -9.5, 0);
            s.assertSucceeded(s.ultimate(), "미궁");
            s.ticks(25);
            face(s.owner, 270); // east, towards a real wall beyond the labyrinth's first ring
            for (int z = -1; z <= 1; z++) {
                s.world.wallAlongX(5, 5, z, 3, Material.STONE_BRICKS);
            }
            s.assertSucceeded(s.active(), "미궁 돌진 into a real wall");
            s.ticks(20);
            assertTrue(s.world.getBlockAt(5, Y, 0).getType().isAir(), "the bull broke through");
            assertTrue(s.world.getBlockAt(5, Y + 1, 0).getType().isAir(), "head high");
            assertEquals(Material.STONE_BRICKS, s.world.getBlockAt(5, Y + 2, 0).getType(), "a tunnel, not a breach");
            assertEquals(Material.STONE, s.world.getBlockAt(5, TOP, 0).getType(), "the floor stays");
            assertTrue(s.owner.getLocation().getX() > 5, "and charged on");
            s.ticks(s.paramTicks("maze_duration"));
            finishRestored(s, t -> {
                assertEquals(Material.STONE_BRICKS, t.world.getBlockAt(5, Y, 0).getType(), "the wall is back");
                for (int z = -1; z <= 1; z++) {
                    t.world.wallAlongX(5, 5, z, 3, Material.AIR);
                }
            });
        }
    }

    @Test
    void dragonBombsBlowCratersIntoTheGround() {
        try (Scenario s = breaking("dragon").start()) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, -14.5, 0);
            s.assertSucceeded(s.ultimate(), "용의 비상");
            s.ticks(s.paramTicks("bomb_interval") + 15);
            assertTrue(removed(s) > 0, "the breath bomb broke the ground");
            s.ticks(s.paramTicks("flight_duration"));
            s.tickUntilGrounded(s.owner, 600);
            s.ticks(40);
            finishRestored(s, x -> { });
        }
    }
}
