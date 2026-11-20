package io.github.sx2xoox.mcpl.game.siege.props;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.ModelProp;
import io.github.sx2xoox.mcpl.core.pack.ItemDisplayModels;
import io.github.sx2xoox.mcpl.core.pack.ModelRef;
import io.github.sx2xoox.mcpl.game.siege.LaneLayout;
import io.github.sx2xoox.mcpl.game.siege.SiegeMatch;
import io.github.sx2xoox.mcpl.game.siege.SiegeModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The map's model props ({@link LaneSites#modelProps}: lollipop lamps, candy canes, toys, boats, ...) for the length of
 * a 공성전 match: one item display per prop at its base, the pack model at its designed scale turned to its yaw
 * ({@link ItemDisplayModels#asAuthored}). Everyone sees the model (the map is played with the pack; no vanilla look).
 * Moving props are animated by the client: every {@link #STEP_TICKS} ticks each gets its next pose with an
 * interpolation as long as the step (a spinning top a sixth of a turn, a wind-up key a quarter, a boat or a duck up or
 * down a little), so the server touches a dozen displays every two seconds. The chunks under the props keep a plugin
 * ticket (the displays are not persistent; an unloading chunk would drop them). {@link #stop} removes everything.
 */
public final class MapProps implements SiegeModule {

    /** Ticks between two poses of a moving prop (= the interpolation of each). */
    static final int STEP_TICKS = 40;
    /** A spinning top's turn per step (a full turn in 12 s). */
    static final float SPIN_STEP = 60;
    /** A wind-up key's turn per step (about its axle). */
    static final float KEY_STEP = 90;
    /** How far a floating prop bobs up and down (blocks) and rocks (degrees). */
    static final float BOB_HEIGHT = 0.07f;
    static final float BOB_ROLL = 2.5f;
    /** Item display view range (a factor of the entity distance; props are seen from across the map). */
    static final float VIEW_RANGE = 3f;

    private final Plugin plugin;
    private final World world;
    private final List<Shown> shown = new ArrayList<>();
    private final List<int[]> tickets = new ArrayList<>();
    private @Nullable BukkitTask ticker;
    private long step;

    /** A shown prop: its display and what it was placed as. */
    record Shown(ModelProp prop, ItemDisplay display, PropModels.Display look) {
    }

    public MapProps(SiegeMatch match) {
        this(match.session().plugin(), match.layout());
    }

    MapProps(Plugin plugin, LaneLayout layout) {
        this.plugin = plugin;
        this.world = layout.world();
        for (ModelProp prop : layout.sites().modelProps()) {
            PropModels.Display look = PropModels.display(prop.model());
            if (look == null) {
                plugin.getLogger().log(Level.WARNING, "Unknown map prop model: " + prop.model());
                continue;
            }
            Location at = layout.at(prop);
            hold(at);
            ItemDisplay display = world.spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(new ModelRef(prop.model()).item());
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setTransformation(ItemDisplayModels.asAuthored(pose(prop, look, 0)));
                d.setViewRange(VIEW_RANGE);
            });
            shown.add(new Shown(prop, display, look));
        }
        if (shown.stream().anyMatch(s -> s.prop().motion() != LaneSites.PropMotion.STILL)) {
            ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::animate, STEP_TICKS, STEP_TICKS);
        }
    }

    /** The props on show (for tests). */
    List<Shown> shown() {
        return List.copyOf(shown);
    }

    /**
     * The transformation (as authored: front -z) of {@code prop} at animation step {@code step}: its centre
     * {@code centre · size} above the base, turned from the authored front (yaw 180) to its yaw, plus its motion.
     */
    static Transformation pose(ModelProp prop, PropModels.Display look, long step) {
        float turn = (float) Math.toRadians(180 - prop.yaw());
        float size = (float) prop.size();
        Quaternionf left = new Quaternionf().rotationY(turn);
        float lift = 0;
        switch (prop.motion()) {
            case SPIN -> left.rotateY((float) Math.toRadians(SPIN_STEP * (step % (360 / (long) SPIN_STEP))));
            case KEY -> left.rotateZ((float) Math.toRadians(KEY_STEP * (step % (360 / (long) KEY_STEP))));
            case BOB -> {
                boolean up = step % 2 == 1;
                lift = up ? BOB_HEIGHT : 0;
                left.rotateZ((float) Math.toRadians(up ? BOB_ROLL : -BOB_ROLL));
            }
            case STILL -> {
            }
        }
        return new Transformation(new Vector3f(0, (float) (look.centre() * size) + lift, 0), left,
                new Vector3f(look.scale() * size), new Quaternionf());
    }

    private void animate() {
        step++;
        for (Shown s : shown) {
            if (s.prop().motion() == LaneSites.PropMotion.STILL || !s.display().isValid()) {
                continue;
            }
            s.display().setInterpolationDelay(0);
            s.display().setInterpolationDuration(STEP_TICKS);
            s.display().setTransformation(ItemDisplayModels.asAuthored(pose(s.prop(), s.look(), step)));
        }
    }

    private void hold(Location at) {
        int cx = at.getBlockX() >> 4;
        int cz = at.getBlockZ() >> 4;
        for (int[] held : tickets) {
            if (held[0] == cx && held[1] == cz) {
                return;
            }
        }
        if (world.addPluginChunkTicket(cx, cz, plugin)) {
            tickets.add(new int[] {cx, cz});
        }
    }

    @Override
    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (Shown s : shown) {
            s.display().remove();
        }
        shown.clear();
        for (int[] chunk : tickets) {
            world.removePluginChunkTicket(chunk[0], chunk[1], plugin);
        }
        tickets.clear();
    }
}
