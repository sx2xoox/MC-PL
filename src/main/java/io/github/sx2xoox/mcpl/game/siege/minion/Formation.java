package io.github.sx2xoox.mcpl.game.siege.minion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The formation of a minion wave (docs/siege-v2.md §2, 대열), pure math without Bukkit. Places are read in a lane's
 * frame: <em>progress</em> = blocks walked along the lane from the own base, <em>lateral</em> = blocks to the right of
 * the walking direction (negative = left).
 *
 * <ul>
 *     <li>{@link #assign}: each kind starts a new row (melee in front, ranged behind, the cannon last), at most
 *     {@link Settings#perRow()} to a row; the rows that are left close up (the front dies, the next row is the front),
 *     and the members of a row get files spread evenly around the lane line in the order they stand (nobody crosses).
 *     </li>
 *     <li>{@link #front} and {@link #pace}: on the march every row keeps its distance behind the wave's front line: a
 *     member ahead of its place slows down, one behind it hurries.</li>
 *     <li>{@link #pick}: an enemy facing the member's file, not one many allies already fight.</li>
 *     <li>{@link #standPoint}: where to fight from: melee just in front of the target, ranged near the end of their
 *     range, both on their own file as far as their reach allows (no crowding on one point, no spreading out).</li>
 * </ul>
 */
public final class Formation {

    private Formation() {
    }

    /**
     * The formation numbers ({@code games.siege.minions.formation.*}), blocks unless noted.
     *
     * @param rowSpacing    between rows on the march
     * @param fileSpacing   between neighbours of a row
     * @param perRow        most members of a row
     * @param maxLateral    no file farther from the lane line than this
     * @param lookahead     a marching minion walks to the lane point this far ahead of it (on its file)
     * @param paceSlack     how far from its place in the wave a marching minion may be before it changes pace
     * @param slowFactor    speed factor of a minion ahead of its place
     * @param catchUpFactor speed factor of a minion behind its place
     * @param meleeStand    a melee minion fights from this far in front of its target (plus half the target's width)
     * @param rangedMargin  a ranged minion stops this far inside its range
     * @param lateralWeight target choice: blocks of distance one block of file mismatch counts as
     * @param crowdPenalty  target choice: blocks of distance each ally already fighting a target counts as
     * @param knockback     minions knock each other back with their hits (vanilla); off keeps the lines
     */
    public record Settings(double rowSpacing, double fileSpacing, int perRow, double maxLateral, double lookahead,
            double paceSlack, double slowFactor, double catchUpFactor, double meleeStand, double rangedMargin,
            double lateralWeight, double crowdPenalty, boolean knockback) {

        public static final Settings DEFAULTS = new Settings(1.6, 2.0, 3, 2.5, 3.0, 1.0, 0.6, 1.25, 1.4, 1.0, 0.6,
                1.5, false);
    }

    /** A point on the ground plane. */
    public record Vec(double x, double z) {
        public double distance(Vec other) {
            return Math.hypot(x - other.x, z - other.z);
        }
    }

    /** A place in a lane's frame. */
    public record Frame(double progress, double lateral) {
    }

    /** A lane in one side's walking order. */
    public static final class Lane {
        private final List<Vec> points;
        private final double[] walked;

        public Lane(List<Vec> points) {
            if (points.size() < 2) {
                throw new IllegalArgumentException("A lane needs two waypoints");
            }
            this.points = List.copyOf(points);
            this.walked = new double[points.size()];
            for (int i = 1; i < points.size(); i++) {
                walked[i] = walked[i - 1] + points.get(i - 1).distance(points.get(i));
            }
        }

        public double length() {
            return walked[walked.length - 1];
        }

        /** Progress at waypoint {@code index}. */
        public double walked(int index) {
            return walked[index];
        }

        public int size() {
            return points.size();
        }

        /**
         * {@code at} in the lane's frame: the nearest lane point, looked for within {@code window} blocks of progress
         * around {@code hint} (a lane that bends back near itself does not make a minion jump; NaN = everywhere).
         * Before the start and past the end the progress goes on along the end segments (negative, or over the
         * length).
         */
        public Frame frame(Vec at, double hint, double window) {
            Frame best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int pass = 0; pass < 2 && best == null; pass++) {
                for (int i = 1; i < points.size(); i++) {
                    if (pass == 0 && !Double.isNaN(hint)
                            && (walked[i] < hint - window || walked[i - 1] > hint + window)) {
                        continue;
                    }
                    Vec a = points.get(i - 1);
                    Vec b = points.get(i);
                    double dx = b.x() - a.x();
                    double dz = b.z() - a.z();
                    double length = Math.hypot(dx, dz);
                    if (length < 1.0E-9) {
                        continue;
                    }
                    double ux = dx / length;
                    double uz = dz / length;
                    double along = (at.x() - a.x()) * ux + (at.z() - a.z()) * uz;
                    double t = along;
                    if (i > 1 || along > 0) {
                        t = Math.max(0, t);
                    }
                    if (i < points.size() - 1 || along < length) {
                        t = Math.min(length, t);
                    }
                    double px = a.x() + ux * t;
                    double pz = a.z() + uz * t;
                    double lateral = (at.x() - px) * -uz + (at.z() - pz) * ux;
                    double distance = Math.hypot(at.x() - px, at.z() - pz);
                    if (distance < bestDistance - 1.0E-9) {
                        bestDistance = distance;
                        best = new Frame(walked[i - 1] + t, lateral);
                    }
                }
            }
            return best;
        }

        /** The place {@code progress} blocks along the lane, {@code lateral} to its right. */
        public Vec at(double progress, double lateral) {
            int i = 1;
            while (i < points.size() - 1 && progress > walked[i]) {
                i++;
            }
            Vec a = points.get(i - 1);
            Vec b = points.get(i);
            double length = walked[i] - walked[i - 1];
            double ux = length < 1.0E-9 ? 1 : (b.x() - a.x()) / length;
            double uz = length < 1.0E-9 ? 0 : (b.z() - a.z()) / length;
            double t = progress - walked[i - 1];
            return new Vec(a.x() + ux * t - uz * lateral, a.z() + uz * t + ux * lateral);
        }

        /** The walking direction at {@code progress} (unit). */
        public Vec direction(double progress) {
            int i = 1;
            while (i < points.size() - 1 && progress > walked[i]) {
                i++;
            }
            Vec a = points.get(i - 1);
            Vec b = points.get(i);
            double length = Math.max(1.0E-9, a.distance(b));
            return new Vec((b.x() - a.x()) / length, (b.z() - a.z()) / length);
        }

        /** The first waypoint more than {@code reach} ahead of {@code progress} ({@link #size()} past the end). */
        public int nextWaypoint(double progress, double reach) {
            for (int i = 1; i < points.size(); i++) {
                if (walked[i] > progress + reach) {
                    return i;
                }
            }
            return points.size();
        }
    }

    /** A member of a wave, as the formation sees it. */
    public record Member(int id, MinionKind kind, Frame at) {
    }

    /** A member's place: its row (0 = the front) and its file (lateral). */
    public record Slot(int row, double lateral) {
    }

    /** Rows and files for the live members of a wave. */
    public static Map<Integer, Slot> assign(List<Member> members, Settings settings) {
        Map<Integer, Slot> out = new HashMap<>();
        int perRow = Math.max(1, settings.perRow());
        int row = 0;
        for (MinionKind kind : MinionKind.values()) {
            List<Member> ofKind = new ArrayList<>();
            for (Member member : members) {
                if (member.kind() == kind) {
                    ofKind.add(member);
                }
            }
            // The ones in front lead the kind's rows; inside a row, files by where they stand.
            ofKind.sort(Comparator.comparingDouble((Member m) -> -m.at().progress()).thenComparingInt(Member::id));
            for (int start = 0; start < ofKind.size(); start += perRow) {
                List<Member> rowMembers = new ArrayList<>(ofKind.subList(start, Math.min(ofKind.size(),
                        start + perRow)));
                rowMembers.sort(Comparator.comparingDouble((Member m) -> m.at().lateral()).thenComparingInt(
                        Member::id));
                int n = rowMembers.size();
                double spacing = n > 1 ? Math.min(settings.fileSpacing(), 2 * settings.maxLateral() / (n - 1))
                        : 0;
                for (int i = 0; i < n; i++) {
                    out.put(rowMembers.get(i).id(), new Slot(row, (i - (n - 1) / 2.0) * spacing));
                }
                row++;
            }
        }
        return out;
    }

    /**
     * The wave's front line on the march: the mean of where each member's row puts the front (a member of row r
     * at progress p says {@code p + r × rowSpacing}).
     */
    public static double front(List<Member> marching, Map<Integer, Slot> slots, Settings settings) {
        double sum = 0;
        int n = 0;
        for (Member member : marching) {
            Slot slot = slots.get(member.id());
            if (slot != null) {
                sum += member.at().progress() + slot.row() * settings.rowSpacing();
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** The speed factor of a marching member at {@code progress} whose place is {@code front − row × rowSpacing}. */
    public static double pace(double progress, int row, double front, Settings settings) {
        if (Double.isNaN(front)) {
            return 1;
        }
        double off = progress - (front - row * settings.rowSpacing());
        if (off > settings.paceSlack()) {
            return settings.slowFactor();
        }
        if (off < -settings.paceSlack()) {
            return settings.catchUpFactor();
        }
        return 1;
    }

    /** A target a member may choose. */
    public record Candidate(int id, Frame at, double distance, int fightersOn) {
    }

    /**
     * The candidate (index) to fight for a member on file {@code lateral}: the nearest, a file mismatch and every ally
     * already on it counting as extra distance; -1 for none.
     */
    public static int pick(double lateral, List<Candidate> candidates, Settings settings) {
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            double score = candidate.distance() + settings.lateralWeight() * Math.abs(candidate.at().lateral()
                    - lateral) + settings.crowdPenalty() * candidate.fightersOn();
            if (score < bestScore - 1.0E-9) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /**
     * Where a member on file {@code lateral} with {@code range} fights a target at {@code target} (a body
     * {@code targetHalfWidth} wide) from: in front of it (towards the own base) by the melee stand or the range less
     * the margin, on its own file as far as that keeps the target in reach.
     */
    public static Frame standPoint(Frame target, double targetHalfWidth, double lateral, double range, boolean ranged,
            Settings settings) {
        double reach = range + targetHalfWidth;
        double along = ranged ? Math.max(1, reach - settings.rangedMargin())
                : Math.min(settings.meleeStand() + targetHalfWidth, reach * 0.8);
        double side = Math.sqrt(Math.max(0, (reach * 0.9) * (reach * 0.9) - along * along));
        double offset = Math.clamp(lateral - target.lateral(), -side, side);
        return new Frame(target.progress() - along, target.lateral() + offset);
    }
}
