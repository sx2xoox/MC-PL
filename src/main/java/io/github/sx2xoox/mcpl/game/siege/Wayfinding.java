package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.map.siege.SiegeSites;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Where to go (docs/siege.md "화면 표시"): the target tower (the one the attackers can hit now) gets a light beam
 * everyone sees from afar and through buildings, and a glowing frame only the attackers see; {@link #pointer} names it
 * with distance and direction for the timer boss bar; a player who walks into another district of the map sees its
 * name ({@link SiegeSites#districts()}).
 */
final class Wayfinding {

    static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    /** How long another title keeps the district titles back (its fade-in, stay and fade-out). */
    static final long QUIET_TICKS = 70;
    /** Height of the beam above the tower's hitbox. */
    static final float BEAM_HEIGHT = 96f;
    static final float BEAM_WIDTH = 0.5f;
    static final float FRAME_EDGE = 0.12f;
    private static final Display.Brightness FULL_BRIGHT = new Display.Brightness(15, 15);

    private final SiegeMatch match;
    private final GameSession session;
    private final Messages messages;
    private final SiegeHudSettings settings;
    private @Nullable Tower target;
    private @Nullable BlockDisplay beam;
    private final List<BlockDisplay> frame = new ArrayList<>();
    /** Who sees the frame now (the attackers in play). */
    private final Set<UUID> frameViewers = new HashSet<>();
    /** The district each player in play stood in at the last update. */
    private final Map<UUID, SiegeSites.District> districts = new HashMap<>();
    /** Session tick until which no district title shows (another title is on screen), for everyone / per player. */
    private long quietUntil;
    private final Map<UUID, Long> quietFor = new HashMap<>();
    private boolean reported;

    Wayfinding(SiegeMatch match, SiegeHudSettings settings) {
        this.match = match;
        this.session = match.session();
        this.messages = match.messages();
        this.settings = settings;
    }

    /** The tower the markers point at: the attackable tower while a half is prepared or fought. */
    @Nullable Tower target() {
        SiegeMatch.Phase phase = match.phase();
        if (phase != SiegeMatch.Phase.PREP && phase != SiegeMatch.Phase.FIGHT) {
            return null;
        }
        return match.attackableTower();
    }

    void update() {
        try {
            Tower next = target();
            if (next != target || (beam != null && !beam.isValid())) {
                removeMarkers();
                target = next;
                if (next != null) {
                    spawnMarkers(next);
                }
            }
            updateFrameViewers();
        } catch (RuntimeException e) {
            report(e);
        }
        if (settings.districtTitles()) {
            districts();
        }
    }

    // ------------------------------------------------------------------ pointer

    /** {@code ▶ 방화벽 게이트 42m ↗} for the viewer, or null (no target, another world, not a team member). */
    @Nullable Component pointer(Player viewer) {
        Tower tower = target;
        int team = match.teamIndex(viewer.getUniqueId());
        if (!settings.pointer() || tower == null || tower.isDestroyed() || team < 0) {
            return null;
        }
        Location at = viewer.getLocation();
        Location base = tower.base();
        if (base.getWorld() == null || !base.getWorld().equals(at.getWorld())) {
            return null;
        }
        double dx = base.getX() - at.getX();
        double dz = base.getZ() - at.getZ();
        int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        String arrow = arrow(at.getYaw(), dx, dz);
        boolean attacking = team == match.attackingTeam();
        return messages.get(attacking ? "hud.pointer-attack" : "hud.pointer-defend",
                Messages.tag("tower", tower.name()), Messages.tag("distance", distance),
                Messages.tag("arrow", arrow));
    }

    /**
     * The arrow from a player looking along {@code yaw} (Minecraft degrees: 0 = +z, 90 = −x) towards the offset
     * {@code dx, dz}: ↑ ahead, → to the right, ↓ behind.
     */
    static String arrow(float yaw, double dx, double dz) {
        if (dx * dx + dz * dz < 4) {
            return "●";
        }
        double towards = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = towards - yaw;
        relative = ((relative % 360) + 360) % 360;
        int index = (int) Math.round(relative / 45.0) % 8;
        return ARROWS[index];
    }

    // ------------------------------------------------------------------ beam and frame

    private void spawnMarkers(Tower tower) {
        Location base = tower.base();
        World world = base.getWorld();
        if (world == null) {
            return;
        }
        BoundingBox box = bodyBox(tower);
        if (settings.beam()) {
            Location at = new Location(world, box.getCenterX(), box.getMaxY() + 0.5, box.getCenterZ());
            beam = session.track(world.spawn(at, BlockDisplay.class, display -> {
                display.setPersistent(false);
                display.setBlock(Material.WHITE_STAINED_GLASS.createBlockData());
                display.setTransformation(new Transformation(new Vector3f(-BEAM_WIDTH / 2, 0, -BEAM_WIDTH / 2),
                        new AxisAngle4f(), new Vector3f(BEAM_WIDTH, BEAM_HEIGHT, BEAM_WIDTH), new AxisAngle4f()));
                display.setBrightness(FULL_BRIGHT);
                display.setGlowing(true);
                display.setGlowColorOverride(Color.fromRGB(0xFFD23F));
                display.setViewRange(6f);
                display.setDisplayWidth(BEAM_WIDTH + 1);
                display.setDisplayHeight(BEAM_HEIGHT);
            }));
        }
        if (settings.outline()) {
            spawnFrame(world, box.clone().expand(0.3));
        }
    }

    /** The tower's hitbox, or a 3 x 6 box on its base when it has none. */
    private static BoundingBox bodyBox(Tower tower) {
        Mannequin body = tower.hitbox();
        if (body != null && body.isValid()) {
            return body.getBoundingBox();
        }
        Location base = tower.base();
        return new BoundingBox(base.getX() - 1.5, base.getY(), base.getZ() - 1.5, base.getX() + 1.5,
                base.getY() + 6, base.getZ() + 1.5);
    }

    /** Twelve thin glowing edges around {@code box}, hidden from everyone until {@link #updateFrameViewers}. */
    private void spawnFrame(World world, BoundingBox box) {
        double[] xs = {box.getMinX(), box.getMaxX()};
        double[] ys = {box.getMinY(), box.getMaxY()};
        double[] zs = {box.getMinZ(), box.getMaxZ()};
        float w = (float) box.getWidthX();
        float h = (float) box.getHeight();
        float d = (float) box.getWidthZ();
        for (double y : ys) {
            for (double z : zs) {
                edge(world, box.getMinX(), y, z, w, FRAME_EDGE, FRAME_EDGE);
            }
            for (double x : xs) {
                edge(world, x, y, box.getMinZ(), FRAME_EDGE, FRAME_EDGE, d);
            }
        }
        for (double x : xs) {
            for (double z : zs) {
                edge(world, x, box.getMinY(), z, FRAME_EDGE, h, FRAME_EDGE);
            }
        }
    }

    private void edge(World world, double x, double y, double z, float sx, float sy, float sz) {
        Location at = new Location(world, x, y, z);
        BlockDisplay display = session.track(world.spawn(at, BlockDisplay.class, spawned -> {
            spawned.setPersistent(false);
            spawned.setVisibleByDefault(false);
            spawned.setBlock(Material.YELLOW_CONCRETE.createBlockData());
            spawned.setTransformation(new Transformation(
                    new Vector3f(-FRAME_EDGE / 2, -FRAME_EDGE / 2, -FRAME_EDGE / 2), new AxisAngle4f(),
                    new Vector3f(sx, sy, sz), new AxisAngle4f()));
            spawned.setBrightness(FULL_BRIGHT);
            spawned.setGlowing(true);
            spawned.setGlowColorOverride(Color.fromRGB(0xFF5A36));
            spawned.setViewRange(4f);
            spawned.setDisplayWidth(Math.max(sx, sz) + 1);
            spawned.setDisplayHeight(sy + 1);
        }));
        frame.add(display);
    }

    /** The frame shows to the attackers (in play) only. */
    private void updateFrameViewers() {
        if (frame.isEmpty()) {
            frameViewers.clear();
            return;
        }
        int attackers = match.attackingTeam();
        Set<UUID> now = new HashSet<>();
        for (Player viewer : session.viewers()) {
            boolean wants = match.teamIndex(viewer.getUniqueId()) == attackers && session.isInPlay(viewer);
            if (wants) {
                now.add(viewer.getUniqueId());
            }
            if (wants != frameViewers.contains(viewer.getUniqueId()) || wants && !viewer.canSee(frame.get(0))) {
                for (BlockDisplay edge : frame) {
                    if (wants) {
                        viewer.showEntity(session.plugin(), edge);
                    } else {
                        viewer.hideEntity(session.plugin(), edge);
                    }
                }
            }
        }
        frameViewers.clear();
        frameViewers.addAll(now);
    }

    /** Removes the beam and the frame. */
    void removeMarkers() {
        BlockDisplay shown = beam;
        beam = null;
        if (shown != null) {
            shown.remove();
        }
        for (BlockDisplay edge : frame) {
            edge.remove();
        }
        frame.clear();
        frameViewers.clear();
        target = null;
    }

    // ------------------------------------------------------------------ districts

    private void districts() {
        SiegeLayout layout = match.layout();
        Set<UUID> inPlay = new HashSet<>();
        boolean fighting = match.phase() == SiegeMatch.Phase.FIGHT;
        for (Player player : session.alivePlayers()) {
            if (match.teamIndex(player.getUniqueId()) < 0) {
                continue;
            }
            inPlay.add(player.getUniqueId());
            SiegeSites.District now = layout.districtAt(player.getLocation());
            SiegeSites.District before = now == null ? districts.remove(player.getUniqueId())
                    : districts.put(player.getUniqueId(), now);
            if (now != null && before != null && !now.equals(before) && fighting && !isQuiet(player)) {
                Feedback.title(player, messages.get("district.title", Messages.tag("district", now.name())),
                        messages.get("district.subtitle", Messages.tag("district", now.name())), 5, 30, 10);
            }
        }
        // Dead or respawning players start over: the spawn after a respawn is no "entering".
        districts.keySet().retainAll(inPlay);
        quietFor.values().removeIf(until -> until < session.tick());
    }

    /** Another title is on screen for {@code player} (null: everyone): hold the district titles back. */
    void titleShown(@Nullable Player player) {
        long until = session.tick() + QUIET_TICKS;
        if (player == null) {
            quietUntil = until;
        } else {
            quietFor.put(player.getUniqueId(), until);
        }
    }

    /** True while another title is on the player's screen. */
    boolean isQuiet(Player player) {
        long now = session.tick();
        return now < quietUntil || now < quietFor.getOrDefault(player.getUniqueId(), Long.MIN_VALUE);
    }

    /** The district the player was in at the last update (tests). */
    SiegeSites.@Nullable District districtOf(UUID player) {
        return districts.get(player);
    }

    /** The beam over the target tower (tests). */
    @Nullable BlockDisplay beam() {
        return beam;
    }

    List<BlockDisplay> frame() {
        return List.copyOf(frame);
    }

    private void report(RuntimeException error) {
        if (!reported) {
            reported = true;
            session.plugin().getLogger().log(Level.WARNING, "Could not update the siege way-finding", error);
        }
    }
}
