package io.github.sx2xoox.mcpl.game.siege.props;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.ModelProp;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.PropMotion;
import io.github.sx2xoox.mcpl.core.map.toykingdom.ToyKingdomMap;
import io.github.sx2xoox.mcpl.core.pack.ItemDisplayModels;
import io.github.sx2xoox.mcpl.core.pack.ModelRef;
import io.github.sx2xoox.mcpl.game.siege.LaneLayout;
import io.github.sx2xoox.mcpl.game.superpower.support.TestServer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * The map's model props in a 공성전 match: one item display per prop of the map, turned to its yaw, the moving ones
 * animated by interpolated steps, and nothing left after the match; the display table agrees with props.py.
 */
class MapPropsTest {

    private static final ToyKingdomMap MAP = new ToyKingdomMap();

    private TestServer server;
    private PluginMock plugin;
    private FlatWorld world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new TestServer());
        plugin = MockBukkit.createMockPlugin();
        world = new FlatWorld("siege");
        server.addWorld(world);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private MapProps show() {
        return new MapProps(plugin, new LaneLayout(MAP, world, 1000, -500));
    }

    private List<ItemDisplay> displays() {
        return List.copyOf(world.getEntitiesByClass(ItemDisplay.class));
    }

    @Test
    void everyPropOfTheMapIsShownAtItsBaseWithItsModel() {
        MapProps props = show();
        List<ModelProp> declared = MAP.modelProps();
        assertEquals(declared.size(), props.shown().size());
        assertEquals(declared.size(), displays().size());
        for (MapProps.Shown s : props.shown()) {
            ItemDisplay d = s.display();
            assertEquals(1000 + s.prop().x(), d.getLocation().getX(), 1e-6);
            assertEquals(s.prop().y(), d.getLocation().getY(), 1e-6);
            assertEquals(-500 + s.prop().z(), d.getLocation().getZ(), 1e-6);
            assertFalse(d.isPersistent(), "a prop never outlives the server");
            // (the mock keeps the item's type only; ModelRef puts the model on a paper item)
            assertEquals(new ModelRef(s.prop().model()).item().getType(), d.getItemStack().getType());
            Transformation t = d.getTransformation();
            assertEquals(s.look().scale(), t.getScale().x(), 1e-5);
            assertEquals(s.look().centre(), t.getTranslation().y(), 1e-5);
            // the authored front (-z) points where the map says (Minecraft yaw: x = -sin, z = cos)
            Vector3f front = ItemDisplayModels.front(d.getYaw(), t);
            double yaw = Math.toRadians(s.prop().yaw());
            assertEquals(-Math.sin(yaw), front.x(), 1e-4, s.prop().model() + " front x");
            assertEquals(Math.cos(yaw), front.z(), 1e-4, s.prop().model() + " front z");
        }
    }

    @Test
    void movingPropsStepWithInterpolationAndStillOnesStayPut() {
        MapProps props = show();
        Map<ItemDisplay, Transformation> before = new HashMap<>();
        for (MapProps.Shown s : props.shown()) {
            before.put(s.display(), s.display().getTransformation());
        }
        server.getScheduler().performTicks(MapProps.STEP_TICKS);
        int moved = 0;
        for (MapProps.Shown s : props.shown()) {
            Transformation now = s.display().getTransformation();
            if (s.prop().motion() == PropMotion.STILL) {
                assertEquals(before.get(s.display()), now, s.prop().model() + " stays put");
            } else {
                assertNotEquals(before.get(s.display()), now, s.prop().model() + " moves");
                assertEquals(MapProps.STEP_TICKS, s.display().getInterpolationDuration());
                moved++;
            }
        }
        assertTrue(moved >= 8, "the tops, keys, boats and ducks move: " + moved);
    }

    @Test
    void aSpinStaysUprightAndAKeyTurnsAboutItsAxle() {
        ModelProp top = MAP.modelProps().stream().filter(p -> p.motion() == PropMotion.SPIN).findFirst().orElseThrow();
        PropModels.Display look = PropModels.display(top.model());
        assertNotNull(look);
        for (long step = 0; step < 6; step++) {
            Transformation t = ItemDisplayModels.asAuthored(MapProps.pose(top, look, step));
            Vector3f up = ItemDisplayModels.drawn(0, t, new Vector3f(0, 1, 0))
                    .sub(ItemDisplayModels.drawn(0, t, new Vector3f()));
            assertEquals(0, up.x(), 1e-4);
            assertEquals(0, up.z(), 1e-4);
        }
        ModelProp key = MAP.modelProps().stream().filter(p -> p.motion() == PropMotion.KEY).findFirst().orElseThrow();
        PropModels.Display keyLook = PropModels.display(key.model());
        assertNotNull(keyLook);
        for (long step = 0; step < 4; step++) {
            // the authored axle (z) keeps pointing back along the car while the bow turns
            Transformation t = ItemDisplayModels.asAuthored(MapProps.pose(key, keyLook, step));
            Vector3f axle = ItemDisplayModels.drawn(0, t, new Vector3f(0, 0, -1))
                    .sub(ItemDisplayModels.drawn(0, t, new Vector3f())).normalize();
            double yaw = Math.toRadians(key.yaw());
            assertEquals(-Math.sin(yaw), axle.x(), 1e-4);
            assertEquals(Math.cos(yaw), axle.z(), 1e-4);
        }
    }

    @Test
    void theMatchEndingRemovesEveryProp() {
        MapProps props = show();
        assertFalse(displays().isEmpty());
        props.stop();
        assertTrue(displays().isEmpty(), "no prop left: " + displays().size());
        server.getScheduler().performTicks(MapProps.STEP_TICKS * 2L);
        assertTrue(displays().isEmpty());
        props.stop(); // twice is harmless
    }

    @Test
    void theDisplayTableMatchesPropsPy() throws Exception {
        String py = Files.readString(Path.of("tools/assetgen/models/props.py"));
        Map<String, Double> constants = new HashMap<>();
        Matcher c = Pattern.compile("(?m)^([A-Z_]+) = ([0-9.]+)$").matcher(py);
        while (c.find()) {
            constants.put(c.group(1), Double.parseDouble(c.group(2)));
        }
        String table = py.substring(py.indexOf("PROPS = {"));
        table = table.substring(0, table.indexOf('}'));
        Matcher m = Pattern.compile("\"([a-z_]+)\": \\(\\w+, ([0-9.]+), ([A-Z_0-9.]+)\\)").matcher(table);
        Map<String, PropModels.Display> fromPy = new HashMap<>();
        while (m.find()) {
            String centre = m.group(3);
            double value = constants.containsKey(centre) ? constants.get(centre) : Double.parseDouble(centre);
            fromPy.put("prop/" + m.group(1), new PropModels.Display(Float.parseFloat(m.group(2)), value));
        }
        assertEquals(fromPy, PropModels.DISPLAYS);
        for (String id : PropModels.ids()) {
            assertTrue(Files.exists(Path.of("src/main/resourcepack-src/models/" + id + ".bbmodel")), id);
        }
    }

    @Test
    void theToyKingdomDeclaresMirroredPropsWithKnownModels() {
        List<ModelProp> props = MAP.modelProps();
        assertTrue(props.size() >= 40, "lamps, canes, toys, boats, ducks, rainbows: " + props.size());
        for (ModelProp p : props) {
            assertNotNull(PropModels.display(p.model()), p.model());
            if (Math.abs(p.x() - 0.5) < 1e-9) {
                continue; // on the middle line (river)
            }
            // the other half has the same prop at the mirror image (x -> 1 - x); a handed model has a mirrored twin
            String twin = p.model().endsWith("_mirror") ? p.model().substring(0, p.model().length() - 7)
                    : p.model() + "_mirror";
            boolean mirrored = props.stream().anyMatch(o -> (o.model().equals(p.model())
                    || o.model().equals(twin) && PropModels.display(twin) != null)
                    && Math.abs(o.x() - (1 - p.x())) < 1e-9 && Math.abs(o.z() - p.z()) < 1e-9);
            assertTrue(mirrored, "no mirror image of " + p);
        }
    }
}
