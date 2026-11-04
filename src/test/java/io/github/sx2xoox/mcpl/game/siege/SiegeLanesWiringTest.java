package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.map.ArenaMap;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKingdomMap;
import io.github.sx2xoox.mcpl.game.siege.arena.SiegeSystems;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionWaves;
import io.github.sx2xoox.mcpl.game.siege.tower.Footprint;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchContext;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The lane systems (minions, jungle, gold, shop) run inside a 공성전 match, and the 장난감 왕국 is its map. */
class SiegeLanesWiringTest {

    private MatchHarness h;
    private SiegeGame siege;
    private ConfigurationSection config;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
        h.registerDummy();
        h.registerTestAbilities();
        h.settings().set("ultimate.per-second", 0);
        siege = (SiegeGame) h.hub.games().game(SiegeGame.ID);
        siege.sitesOverride(new FallbackLaneSites((int) FlatWorld.SURFACE));
        config = siege.config();
        config.set(SiegeSettings.ABILITY_POOL, List.of("dummy"));
        config.set(SiegeSettings.ROUND_PREP_SECONDS, 1);
        config.set(SiegeSettings.SPAWN_ZONE_DAMAGE, 0);
        config.set(SiegeSettings.ATTACK_DAMAGE, 0);
        config.set("minions.first-wave-seconds", 1);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private SiegeMatch start(int players) {
        h.join(players);
        try {
            h.hub.games().start(siege, null);
        } catch (GameStartException e) {
            throw new AssertionError(e);
        }
        h.tickUntil(() -> siege.currentMatch() != null && siege.currentMatch().phase() == SiegeMatch.Phase.FIGHT,
                2000, "the fight");
        return siege.currentMatch();
    }

    private static LaneSystemsModule lanes(SiegeMatch match) {
        for (SiegeModule module : match.modules()) {
            if (module instanceof LaneSystemsModule lanes) {
                return lanes;
            }
        }
        throw new AssertionError("no lane systems");
    }

    @Test
    void theToyKingdomIsTheRegisteredMapAndMustBeBuiltFirst() {
        siege.sitesOverride(null);
        h.join(2);
        assertThrows(GameStartException.class, () -> h.hub.games().start(siege, null),
                "the registered 장난감 왕국 is not built yet");
        assertNull(h.hub.games().session());
        ArenaMap map = h.hub.maps().map(SiegeGame.MAP_ID);
        LaneSites sites = assertInstanceOf(LaneSites.class, map);
        LaneLayout layout = new LaneLayout(sites, h.flatWorld(), 0, 0);
        for (LaneSites.Side side : LaneSites.Side.values()) {
            assertTrue(layout.spawns(side).size() >= 8);
            for (LaneSites.Lane lane : LaneSites.Lane.values()) {
                assertEquals(2, sites.towers(side, lane).size());
            }
        }
    }

    @Test
    void aMatchRunsOnTheToyKingdomSites() {
        siege.sitesOverride(new ToyKingdomMap());
        SiegeMatch match = start(2);
        assertInstanceOf(ToyKingdomMap.class, match.sites());
        assertEquals(10, match.towers().size());
        assertEquals(10, h.flatWorld().getEntitiesByClass(Mannequin.class).size());
        for (Tower tower : match.towers()) {
            assertNotNull(tower.bar(), tower.id());
        }
        Player a = match.session().hub().plugin().getServer().getPlayer(
                match.teams().get(0).members().iterator().next());
        LaneSites.Side side = match.side(a.getUniqueId());
        assertEquals(0, a.getLocation().distance(match.layout().spawns(side).getFirst()), 1.0);
    }

    /** The tower of {@code side} and {@code kind} on the top lane (or the core). */
    private static Tower structure(SiegeMatch match, LaneSites.Side side, Tower.Kind kind) {
        for (Tower tower : match.towers(side)) {
            if (tower.kind() == kind && (kind == Tower.Kind.CORE || tower.lane() == LaneSites.Lane.TOP)) {
                return tower;
            }
        }
        throw new AssertionError("no " + kind);
    }

    @Test
    void buildingsStandInBarrierWallsThatStillLetThemBeSeenAndHit() {
        SiegeMatch match = start(2);
        for (Tower tower : match.towers()) {
            assertTrue(tower.wallCount() > 0, tower.id() + " has walls");
            Location base = tower.base();
            assertEquals(Material.BARRIER, base.getBlock().getType(), tower.id() + ": no body walks into it");
            assertTrue(match.isStructureWall(base.getBlock()));
            Footprint.Box walls = tower.keepOut(0);
            assertNotNull(walls);
            // the model's size, cut to the room the map keeps clear (the fallback's core has 5)
            double reach = Math.min(tower.isCore() ? 7.5 : 2.5, tower.site().radius() - 0.5);
            assertEquals(reach, walls.maxX() - base.getX(), 1.0E-9, tower.id());
            // the hitbox of a tower reaches past its ground walls: a click at the building lands on the hitbox
            Mannequin hitbox = tower.hitbox();
            assertNotNull(hitbox);
            if (!tower.isCore()) {
                assertTrue(hitbox.getBoundingBox().getMaxX() > walls.maxX(), tower.id());
            }
            assertTrue(tower.bounds().getMaxX() >= walls.maxX(), "reach and aim measure the whole building");
        }
        assertFalse(match.isStructureWall(h.flatWorld().getBlockAt(0, (int) FlatWorld.SURFACE, 0)));

        // a player at the wall of an enemy tower sees and reaches it, through the barriers
        Player a = match.session().hub().plugin().getServer().getPlayer(
                match.teams().get(0).members().iterator().next());
        LaneSites.Side enemy = match.side(a.getUniqueId()) == LaneSites.Side.BLUE ? LaneSites.Side.RED
                : LaneSites.Side.BLUE;
        Tower outer = structure(match, enemy, Tower.Kind.OUTER);
        Location base = outer.base();
        Footprint.Box walls = outer.keepOut(0);
        Location front = new Location(base.getWorld(), walls.minX() - 0.4, base.getY(), base.getZ(), -90, 0);
        a.teleport(front);
        MatchContext ctx = match.abilities().context();
        assertFalse(a.hasLineOfSight(outer.hitbox()), "the barrier hides the hitbox from vanilla sight");
        assertTrue(ctx.canSee(a, outer.hitbox()), "but the game sees the building");
        assertTrue(ctx.enemiesNear(a, a.getEyeLocation(), 1.0).contains(outer.hitbox()), "in arm's reach");
        assertEquals(outer.hitbox(), ctx.findTarget(a, 4), "aimed at");

        // a fallen tower leaves the walls of its ruin, a round's end takes every wall down
        int standing = outer.wallCount();
        outer.damage(outer.maxHealth(), null, match.session().tick());
        assertTrue(outer.isDestroyed());
        assertTrue(outer.wallCount() > 0 && outer.wallCount() < standing, "the ruin's walls");
        assertEquals(Material.BARRIER, base.getBlock().getType());
        assertEquals(Material.AIR, base.clone().add(0, 4, 0).getBlock().getType(), "nothing over the ruin");
        Tower core = structure(match, enemy, Tower.Kind.CORE);
        Location coreBase = core.base();
        outer.dispose();
        assertEquals(Material.AIR, base.getBlock().getType(), "restored");
        assertEquals(0, outer.wallCount());
        assertTrue(h.server.dispatchCommand(h.server.getConsoleSender(), "게임 중지"));
        h.tickUntil(() -> h.hub.games().session() == null, 2000, "the session to close");
        assertEquals(Material.AIR, coreBase.getBlock().getType(), "every wall goes with the match");
    }

    @Test
    void minionsWalkRoundTheTowersOnTheirLanesNeverThrough() {
        SiegeMatch match = start(2);
        MinionWaves minions = match.systems().minions();
        h.tickUntil(() -> !minions.minions().isEmpty(), 400, "the first wave");
        double furthestBlue = Double.NEGATIVE_INFINITY;
        int checked = 0;
        for (int tick = 0; tick < 700; tick++) {
            h.ticks(1);
            for (MinionWaves.Minion minion : minions.minions()) {
                Location at = minion.body().getLocation();
                for (Tower tower : match.towers()) {
                    Footprint.Box walls = tower.keepOut(0);
                    assertFalse(walls != null && walls.contains(at.getX(), at.getZ()),
                            minion.side() + " " + minion.kind() + " at " + at.toVector() + " inside " + tower.id()
                                    + " (tick " + tick + ")");
                }
                if (minion.side() == LaneSites.Side.BLUE && minion.lane() == LaneSites.Lane.TOP) {
                    furthestBlue = Math.max(furthestBlue, at.getX());
                }
                checked++;
            }
        }
        assertTrue(checked > 1000, "minion ticks checked: " + checked);
        // the fallback's BLUE towers stand on the lane at x -24 and -12: the minions got past both
        assertTrue(furthestBlue > -8, "BLUE's top wave got past its towers: " + furthestBlue);
    }

    @Test
    void minionsMarchWithSeparateOverheadBarsAndGoldIsTheMatchGold() {
        SiegeMatch match = start(2);
        SiegeSystems systems = match.systems();
        assertNotNull(systems, "the lane systems run in the match");
        assertTrue(match.economy().enabled(), "gold comes from the lane systems");
        Player a = match.session().hub().plugin().getServer().getPlayer(
                match.teams().get(0).members().iterator().next());
        assertEquals(systems.gold().balance(a.getUniqueId()), match.gold(a.getUniqueId()));
        match.addGold(a.getUniqueId(), 77, "test");
        assertEquals(systems.gold().balance(a.getUniqueId()), match.gold(a.getUniqueId()));

        MinionWaves minions = systems.minions();
        h.tickUntil(() -> !minions.minions().isEmpty(), 400, "the first wave");
        h.ticks(5);
        LaneSystemsModule lanes = lanes(match);
        Mob body = minions.minions().getFirst().body();
        assertTrue(match.abilities().context().isEnemy(a, body)
                == (minions.minions().getFirst().side() != match.side(a.getUniqueId())),
                "minions are enemies of the other side only");
        OverheadBar bar = lanes.bar(body);
        assertNotNull(bar, "a minion has an overhead bar");
        TextDisplay display = bar.display();
        assertNotNull(display);
        assertNull(display.getVehicle(), "a separate display, not a passenger of the mob");
        assertFalse(body.getPassengers().contains(display));
        Location over = body.getLocation();
        h.ticks(2);
        assertTrue(display.getLocation().getY() > over.getY() + body.getHeight(), "above the head");
        assertTrue(Math.abs(display.getLocation().getX() - body.getLocation().getX()) < 0.01, "it follows the mob");

        body.remove();
        h.ticks(2);
        assertNull(lanes.bar(body), "a gone minion's bar goes too");
    }

    @Test
    void aCreatureBarSitsOverItsModelForPackPlayersAndOverItsBodyForTheRest() {
        SiegeMatch match = start(2);
        SiegeSystems systems = match.systems();
        assertNotNull(systems);
        h.tickUntil(() -> systems.minions().minions().stream()
                .anyMatch(m -> m.kind() == io.github.sx2xoox.mcpl.game.siege.minion.MinionKind.MELEE), 400,
                "a melee minion");
        h.ticks(3);
        Mob soldier = systems.minions().minions().stream()
                .filter(m -> m.kind() == io.github.sx2xoox.mcpl.game.siege.minion.MinionKind.MELEE).findFirst()
                .orElseThrow().body();
        OverheadBar bar = lanes(match).bar(soldier);
        assertNotNull(bar);
        assertFalse(soldier.isCustomNameVisible(), "the bar carries the name: no vanilla name tag on top of it");
        TextDisplay body = bar.display();
        TextDisplay model = bar.packDisplay();
        assertNotNull(body);
        assertNotNull(model, "the toy soldier model is twice as tall as the baby zombie under it");
        h.ticks(2);
        double feet = soldier.getLocation().getY();
        assertEquals(soldier.getHeight() + LaneSystemsModule.BAR_GAP, body.getLocation().getY() - feet, 1.0E-6);
        assertEquals(io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.TOPS.get("soldier")
                + LaneSystemsModule.BAR_GAP, model.getLocation().getY() - feet, 1.0E-6);
        assertFalse(model.isVisibleByDefault(), "only players with the pack see the model's bar");
        Player a = match.session().hub().plugin().getServer().getPlayer(
                match.teams().get(0).members().iterator().next());
        assertTrue(a.canSee(body) && !a.canSee(model), "no pack: the bar over the vanilla body");
        assertEquals(bar.text(), model.text());

        soldier.remove();
        h.ticks(2);
        assertFalse(body.isValid() || model.isValid(), "both go with the minion");
    }

    @Test
    void aSplitBarShowsEachViewerTheirOwnDisplay() {
        SiegeMatch match = start(2);
        Player a = match.session().hub().plugin().getServer().getPlayer(
                match.teams().get(0).members().iterator().next());
        Player b = match.session().hub().plugin().getServer().getPlayer(
                match.teams().get(1).members().iterator().next());
        io.github.sx2xoox.mcpl.core.pack.PackAudience onlyA = new io.github.sx2xoox.mcpl.core.pack.PackAudience() {
            @Override
            public boolean hasPack(Player player) {
                return player.equals(a);
            }

            @Override
            public Subscription subscribe(java.util.function.Consumer<Player> listener) {
                return () -> { };
            }
        };
        Location at = a.getLocation().add(3, 1, 0);
        OverheadBar bar = OverheadBar.at(match.session(), at, net.kyori.adventure.text.Component.text("곰"),
                net.kyori.adventure.text.format.NamedTextColor.GOLD, 0.75f);
        bar.splitForPack(onlyA, at.clone().add(0, 1.5, 0));
        TextDisplay body = bar.display();
        TextDisplay model = bar.packDisplay();
        assertNotNull(body);
        assertNotNull(model);
        assertTrue(a.canSee(model) && !a.canSee(body), "a has the pack");
        assertTrue(b.canSee(body) && !b.canSee(model), "b does not");
        bar.health(5, 10);
        assertEquals(body.text(), model.text(), "both redrawn");
        bar.moveTo(at.clone().add(1, 0, 0), at.clone().add(1, 1.5, 0));
        assertEquals(at.getX() + 1, model.getLocation().getX(), 1.0E-6);
        bar.moveTo(at.clone().add(4, 0, 0), null);
        assertEquals(at.getX() + 4, model.getLocation().getX(), 1.0E-6, "no model spot: the twin goes with the bar");
        bar.remove();
        assertFalse(body.isValid() || model.isValid());
    }

    @Test
    void everyCreatureBarFollowsItsCreatureForPlayersWithAndWithoutThePack() {
        config.set("minions.cannon-every", 1);
        for (String kind : List.of("teddy", "rabbit", "dragon", "jelly", "mice")) {
            config.set("jungle." + kind + ".first-seconds", 1);
        }
        java.util.List<java.util.function.Consumer<Player>> listeners = new java.util.ArrayList<>();
        java.util.Set<Player> withPack = new java.util.HashSet<>();
        siege.packsOverride(new io.github.sx2xoox.mcpl.core.pack.PackAudience() {
            @Override
            public boolean hasPack(Player player) {
                return withPack.contains(player);
            }

            @Override
            public Subscription subscribe(java.util.function.Consumer<Player> listener) {
                listeners.add(listener);
                return () -> listeners.remove(listener);
            }
        });
        SiegeMatch match = start(2);
        SiegeSystems systems = match.systems();
        assertNotNull(systems);
        Player pack = match.session().hub().plugin().getServer().getPlayer(
                match.teams().get(0).members().iterator().next());
        Player vanilla = match.session().hub().plugin().getServer().getPlayer(
                match.teams().get(1).members().iterator().next());
        withPack.add(pack);
        listeners.forEach(l -> l.accept(pack));
        h.tickUntil(() -> systems.minions().minions().stream()
                .anyMatch(m -> m.kind() == io.github.sx2xoox.mcpl.game.siege.minion.MinionKind.CANNON), 2000,
                "a cannon");
        h.tickUntil(() -> systems.jungle().sites().stream().filter(s -> !s.isCapturePoint()).allMatch(
                io.github.sx2xoox.mcpl.game.siege.jungle.JungleObjectives.Site::isUp), 2000, "the jungle");
        h.ticks(3);
        LaneSystemsModule lanes = lanes(match);
        java.util.Map<String, Mob> creatures = new java.util.LinkedHashMap<>();
        for (MinionWaves.Minion minion : systems.minions().minions()) {
            creatures.putIfAbsent(minion.kind().name(), minion.body());
        }
        for (var site : systems.jungle().sites()) {
            if (!site.mobs().isEmpty()) {
                creatures.putIfAbsent(site.kind().name(), site.mobs().getFirst());
            }
        }
        for (String kind : List.of("CANNON", "MELEE", "RANGED", "TEDDY", "RABBIT", "DRAGON", "JELLY", "MICE")) {
            assertTrue(creatures.containsKey(kind), kind);
        }
        assertNotNull(lanes.bar(creatures.get("CANNON")).packDisplay(),
                "made in the spawn tick, the cannon's bar judged the golem at its unscaled height");
        // A scaled mob's height settles once it ticks (MockBukkit never applies the scale): the cannon's golem
        // (scale 0.6) then stands about as tall as its model, which once left its pack bar behind at the spawn.
        java.util.function.ToDoubleFunction<org.bukkit.entity.LivingEntity> height = c -> c.getHeight()
                * c.getAttribute(org.bukkit.attribute.Attribute.SCALE).getValue();
        lanes.bodyHeight(height);
        for (int step = 1; step <= 4; step++) {
            for (Mob creature : creatures.values()) {
                creature.setAI(false);
                creature.teleport(creature.getLocation().add(3, 0, 2));
            }
            h.ticks(2);
            for (var e : creatures.entrySet()) {
                Mob creature = e.getValue();
                OverheadBar bar = lanes.bar(creature);
                assertNotNull(bar, e.getKey());
                String kind = io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.kindOf(
                        e.getKey().equals("MELEE") ? "soldier" : e.getKey().equals("RANGED") ? "archer"
                                : e.getKey());
                double model = io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.TOPS.get(kind);
                Location feet = creature.getLocation();
                TextDisplay seenByVanilla = seen(bar, vanilla);
                TextDisplay seenByPack = seen(bar, pack);
                assertEquals(feet.getX(), seenByVanilla.getLocation().getX(), 1.0E-6, e.getKey() + " no pack x");
                assertEquals(feet.getZ(), seenByVanilla.getLocation().getZ(), 1.0E-6, e.getKey() + " no pack z");
                double body = height.applyAsDouble(creature);
                assertEquals(body + LaneSystemsModule.BAR_GAP,
                        seenByVanilla.getLocation().getY() - feet.getY(), 1.0E-6, e.getKey() + " over the body");
                assertEquals(feet.getX(), seenByPack.getLocation().getX(), 1.0E-6, e.getKey() + " pack x");
                assertEquals(feet.getZ(), seenByPack.getLocation().getZ(), 1.0E-6, e.getKey() + " pack z");
                double over = seenByPack.getLocation().getY() - feet.getY();
                assertTrue(over >= Math.min(model, body) + LaneSystemsModule.BAR_GAP - 1.0E-6
                        && over <= Math.max(model, body) + LaneSystemsModule.BAR_GAP + 1.0E-6,
                        e.getKey() + " over the model: " + over);
                if (bar.packDisplay() != null) {
                    assertEquals(model + LaneSystemsModule.BAR_GAP, over, 1.0E-6, e.getKey() + " the model's bar");
                }
            }
        }
    }

    /** The one display of {@code bar} that {@code viewer} sees. */
    private static TextDisplay seen(OverheadBar bar, Player viewer) {
        TextDisplay main = bar.display();
        TextDisplay twin = bar.packDisplay();
        assertNotNull(main);
        if (twin == null) {
            assertTrue(viewer.canSee(main));
            return main;
        }
        assertTrue(viewer.canSee(main) != viewer.canSee(twin), "exactly one of the two");
        return viewer.canSee(twin) ? twin : main;
    }

    @Test
    void towersShootMinionsFirst() {
        config.set(SiegeSettings.ATTACK_DAMAGE, 3);
        SiegeMatch match = start(2);
        SiegeSystems systems = match.systems();
        assertNotNull(systems);
        MinionWaves minions = systems.minions();
        h.tickUntil(() -> !minions.minions().isEmpty(), 400, "the first wave");
        MinionWaves.Minion minion = minions.minions().getFirst();
        Tower enemyOuter = SiegeMatchTest.structure(match, minion.side() == LaneSites.Side.BLUE ? LaneSites.Side.RED
                : LaneSites.Side.BLUE, Tower.Kind.OUTER, LaneSites.Lane.TOP);
        Mob body = minion.body();
        body.teleport(enemyOuter.base().add(3, 0, 0));
        double before = body.getHealth();
        h.ticks(10);
        assertTrue(!body.isValid() || body.getHealth() < before, "the tower shot the minion");
    }

    @Test
    void stoppingRemovesTheLaneCreaturesAndBars() {
        SiegeMatch match = start(2);
        SiegeSystems systems = match.systems();
        assertNotNull(systems);
        h.tickUntil(() -> !systems.minions().minions().isEmpty(), 400, "the first wave");
        assertTrue(h.server.dispatchCommand(h.server.getConsoleSender(), "게임 중지"));
        h.tickUntil(() -> h.hub.games().session() == null, 2000, "the session to close");
        assertEquals(0, h.flatWorld().getEntitiesByClass(Mob.class).size());
        assertEquals(0, h.flatWorld().getEntitiesByClass(TextDisplay.class).size());
    }
}
