package io.github.sx2xoox.speedrunbot.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A bucket-cast nether portal carved into solid ground ("pocket casting").
 *
 * <p>The 4x5 frame (corners left out: 10 obsidian) lies in a vertical plane of solid rock. The bot carves a room in
 * front of the plane; for every frame cell it mines a one-block pocket, pours lava into it (rock on five sides keeps
 * the lava in place) and then pours water into the cell in front of the pocket, which turns the lava source into
 * obsidian; the water is picked up again. With one bucket the water waits in a floor hole (the water station) while
 * the bucket fetches lava. Finally the six interior cells are mined and the portal is lit on the bottom obsidian.
 *
 * <p>Plan coordinates: {@code origin} is the bottom-left corner of the frame (its own cell is not obsidian), {@code u}
 * is the unit step along the frame width, {@code n} the unit step from the plane toward the room (the "front").
 * Frame cell (a, b) = origin + a*u + b*up, a in 0..3, b in 0..4. The room floor is at origin.y (the bottom row is
 * flush with the floor), so the bot's feet stand at origin.y + 1.
 */
public final class PortalPlan {
    public static final Cell UP = new Cell(0, 1, 0);

    private final Cell origin;
    private final Cell u;
    private final Cell n;

    public PortalPlan(Cell origin, Cell u, Cell n) {
        if (Math.abs(u.x) + Math.abs(u.y) + Math.abs(u.z) != 1 || u.y != 0) {
            throw new IllegalArgumentException("u must be a horizontal unit step");
        }
        if (Math.abs(n.x) + Math.abs(n.y) + Math.abs(n.z) != 1 || n.y != 0 || (u.x * n.x + u.z * n.z) != 0) {
            throw new IllegalArgumentException("n must be a horizontal unit step perpendicular to u");
        }
        this.origin = origin;
        this.u = u;
        this.n = n;
    }

    public Cell origin() {
        return origin;
    }

    public Cell u() {
        return u;
    }

    public Cell n() {
        return n;
    }

    public Cell at(int a, int b) {
        return origin.add(u.scale(a)).add(UP.scale(b));
    }

    /** Cell (a, b) moved k steps toward the room. */
    public Cell at(int a, int b, int k) {
        return at(a, b).add(n.scale(k));
    }

    /** The ten frame cells in cast order: bottom row, the two columns bottom-up, top row. */
    public List<Cell> frame() {
        List<Cell> out = new ArrayList<>();
        out.add(at(1, 0));
        out.add(at(2, 0));
        for (int b = 1; b <= 3; b++) {
            out.add(at(0, b));
            out.add(at(3, b));
        }
        out.add(at(1, 4));
        out.add(at(2, 4));
        return out;
    }

    public List<Cell> interior() {
        List<Cell> out = new ArrayList<>();
        for (int b = 1; b <= 3; b++) {
            for (int a = 1; a <= 2; a++) {
                out.add(at(a, b));
            }
        }
        return out;
    }

    public List<Cell> corners() {
        List<Cell> out = new ArrayList<>();
        out.add(at(0, 0));
        out.add(at(3, 0));
        out.add(at(0, 4));
        out.add(at(3, 4));
        return out;
    }

    /** The cell the water goes to for a frame cell (and through which the pocket is reached). */
    public Cell front(Cell frameCell) {
        return frameCell.add(n);
    }

    public boolean isBottom(Cell c) {
        return c.equals(at(1, 0)) || c.equals(at(2, 0));
    }

    /**
     * Cells that must be air before casting starts: the space in front of the frame (one deep, as high as the frame
     * cells it serves) and a two-high standing row behind it. Floor holes for the bottom row are separate.
     */
    public List<Cell> room() {
        Set<Cell> out = new LinkedHashSet<>();
        for (int a = 0; a <= 3; a++) {
            int top = (a == 0 || a == 3) ? 3 : 4;
            for (int b = 1; b <= top; b++) {
                out.add(at(a, b, 1));
            }
            out.add(at(a, 1, 2));
            out.add(at(a, 2, 2));
            out.add(at(a, 3, 2)); // head room: the third-row fronts are seen from the standing row
        }
        return new ArrayList<>(out);
    }

    /** Floor holes in front of the bottom row (dug just before those two casts, filled again after). */
    public List<Cell> floorHoles() {
        List<Cell> out = new ArrayList<>();
        out.add(front(at(1, 0)));
        out.add(front(at(2, 0)));
        return out;
    }

    /** Where the water waits while the bucket carries lava: a one-block hole in the standing row's floor. */
    public Cell waterStation() {
        return at(0, 0, 2);
    }

    /** Cells where the bot may stand (feet) while casting; the station column is left out. */
    public List<Cell> standCells() {
        List<Cell> out = new ArrayList<>();
        for (int a = 1; a <= 3; a++) {
            out.add(at(a, 1, 2));
        }
        return out;
    }

    /**
     * Neighbours of a frame cell that must be solid (rock, placed block or obsidian) when its lava is poured, so the
     * lava cannot run anywhere but out of the front, which the water closes at once.
     */
    public List<Cell> mustBeSolid(Cell frameCell) {
        List<Cell> out = new ArrayList<>();
        Cell[] dirs = {u, u.scale(-1), UP, UP.scale(-1), n.scale(-1)};
        for (Cell d : dirs) {
            out.add(frameCell.add(d));
        }
        return out;
    }

    /**
     * Neighbours of a frame cell's front cell that are not part of the room, the frame or the floor holes: the faces
     * the water bucket can be clicked on to put water into the front cell. At least one must be solid when casting.
     */
    public List<Cell> frontSupports(Cell frameCell) {
        Cell f = front(frameCell);
        List<Cell> room = room();
        List<Cell> frame = frame();
        List<Cell> holes = floorHoles();
        List<Cell> out = new ArrayList<>();
        Cell[] dirs = {u, u.scale(-1), UP, UP.scale(-1), n};
        for (Cell d : dirs) {
            Cell q = f.add(d);
            if (!room.contains(q) && !frame.contains(q) && !holes.contains(q) && !q.equals(waterStation())) {
                out.add(q);
            }
        }
        return out;
    }

    /** All cells that the finished portal needs to be rock before carving: frame, interior, corners and backing. */
    public List<Cell> solidBefore() {
        Set<Cell> out = new LinkedHashSet<>();
        out.addAll(frame());
        out.addAll(interior());
        for (Cell c : frame()) {
            out.addAll(mustBeSolid(c));
        }
        for (Cell c : frame()) {
            out.addAll(frontSupports(c));
        }
        out.removeAll(room());
        out.removeAll(floorHoles());
        return Collections.unmodifiableList(new ArrayList<>(out));
    }

    /** The cell the flint and steel lights (the air just above the left bottom obsidian). */
    public Cell fireCell() {
        return at(1, 1);
    }

    /** Frame cell the lighter clicks (its top face). */
    public Cell lightTarget() {
        return at(1, 0);
    }

    /** A cell inside the portal to walk into. */
    public Cell entryCell() {
        return at(1, 1);
    }
}
