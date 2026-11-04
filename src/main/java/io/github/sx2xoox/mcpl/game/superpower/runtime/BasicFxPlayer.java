package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.pack.ItemDisplayModels;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttackSettings.Feel;
import io.github.sx2xoox.mcpl.game.superpower.basic.FxSprite;
import io.github.sx2xoox.mcpl.game.superpower.basic.FxSprite.Kind;
import io.github.sx2xoox.mcpl.game.superpower.basic.FxSprite.Pose;
import io.github.sx2xoox.mcpl.util.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Plays the display entities of basic attacks ({@link FxSprite}s from {@code BasicFx}): spawns each when its time
 * comes, steps it through its poses with one-tick client interpolation and removes it after the last one.
 *
 * <p>Limits ({@link Feel}): an attack spends a {@link Budget} of at most {@code maxPerAttack} displays, and never more
 * than its attacker's {@code maxPerPlayer} minus what is still showing; sprites over the budget are skipped (the
 * lists put the important ones first). Every display is tagged {@value #TAG}, tracked for its owner (revoke and match
 * end remove it), seen within {@code viewRange}, and removed as soon as its owner is out of play (death, elimination,
 * leaving) or the match ends ({@link #dispose()}).
 */
final class BasicFxPlayer {

    /** Scoreboard tag of every basic attack display. */
    static final String TAG = "mcpl_basic_fx";

    private final MatchContext ctx;
    private final GameSession session;
    private final Feel feel;
    private final Map<UUID, Integer> live = new HashMap<>();
    private final Set<Run> runs = new LinkedHashSet<>();
    private final Set<Display> loose = new LinkedHashSet<>();
    private boolean disposed;

    BasicFxPlayer(MatchContext ctx, GameSession session, Feel feel) {
        this.ctx = ctx;
        this.session = session;
        this.feel = feel;
    }

    /** How many displays one attack may still spawn. */
    final class Budget {
        private int left;

        Budget(int left) {
            this.left = Math.max(0, left);
        }

        boolean take() {
            if (left <= 0) {
                return false;
            }
            left--;
            return true;
        }

        int left() {
            return left;
        }
    }

    /** The budget of a new attack of {@code owner}. */
    Budget budget(Player owner) {
        if (!feel.displays() || disposed) {
            return new Budget(0);
        }
        return new Budget(Math.min(feel.maxPerAttack(), feel.maxPerPlayer() - live(owner.getUniqueId())));
    }

    /** Displays of {@code owner}'s attacks showing now. */
    int live(UUID owner) {
        return live.getOrDefault(owner, 0);
    }

    /** Displays of every attack showing now (tests). */
    int liveTotal() {
        int total = 0;
        for (int count : live.values()) {
            total += count;
        }
        return total;
    }

    /** Plays {@code sprites} at {@code anchor}, turned by {@code frame}, now and on its own timer. */
    void play(Player owner, Location anchor, Quaternionfc frame, List<FxSprite> sprites, Budget budget) {
        if (sprites.isEmpty() || budget.left() <= 0) {
            return;
        }
        Run run = new Run(owner);
        run.add(sprites, anchor, frame, budget);
        run.drain();
    }

    /** A new run that its creator steps (e.g. a projectile's trail), {@link Run#drain draining} at the end. */
    Run run(Player owner) {
        return new Run(owner);
    }

    /** One sprite of a run: where and when. */
    private static final class Entry {
        final FxSprite sprite;
        final Location anchor;
        final Quaternionf frame;
        final int startTick;
        @Nullable Display display;

        Entry(FxSprite sprite, Location anchor, Quaternionf frame, int startTick) {
            this.sprite = sprite;
            this.anchor = anchor;
            this.frame = frame;
            this.startTick = startTick;
        }
    }

    /** Sprites playing together on one clock. */
    final class Run {
        private final Player owner;
        private final List<Entry> entries = new ArrayList<>();
        private int tick;
        private @Nullable BukkitTask task;

        private Run(Player owner) {
            this.owner = owner;
        }

        /** Adds sprites (their {@code start} counts from the run's current tick), within the budget. */
        void add(List<FxSprite> sprites, Location anchor, Quaternionfc frame, Budget budget) {
            if (disposed) {
                return;
            }
            for (FxSprite sprite : sprites) {
                if (!budget.take()) {
                    break;
                }
                entries.add(new Entry(sprite, anchor.clone(), new Quaternionf(frame), tick + sprite.start()));
            }
        }

        /** Shows the current tick of every sprite and advances; false once nothing is left. */
        boolean step() {
            if (disposed || !session.isInPlay(owner)) {
                clear();
                return false;
            }
            boolean more = false;
            for (Entry entry : entries) {
                int frame = tick - entry.startTick;
                int frames = entry.sprite.poses().size();
                if (frame < 0) {
                    more = true;
                } else if (frame == 0) {
                    entry.display = spawn(owner, entry.sprite.kind(), entry.sprite.material(), entry.sprite.text(),
                            entry.anchor, transformation(entry.sprite.kind(), entry.sprite.poses().getFirst(),
                                    entry.frame));
                    more |= frames > 1 || entry.display != null;
                } else if (frame < frames) {
                    animate(entry.display, transformation(entry.sprite.kind(), entry.sprite.poses().get(frame),
                            entry.frame));
                    more = true;
                } else if (entry.display != null) {
                    remove(owner.getUniqueId(), entry.display);
                    entry.display = null;
                }
            }
            tick++;
            if (!more) {
                entries.clear();
            }
            return more;
        }

        /** Steps now and then every tick on its own until it is over. */
        void drain() {
            if (task != null) {
                return;
            }
            if (!step()) {
                return;
            }
            runs.add(this);
            BukkitTask[] holder = new BukkitTask[1];
            holder[0] = ctx.runTimer(owner, 1, 1, () -> {
                if (!step()) {
                    holder[0].cancel();
                    runs.remove(this);
                }
            });
            task = holder[0];
        }

        /** Removes every display of the run now. */
        void clear() {
            for (Entry entry : entries) {
                if (entry.display != null) {
                    remove(owner.getUniqueId(), entry.display);
                    entry.display = null;
                }
            }
            entries.clear();
            if (task != null) {
                task.cancel();
                task = null;
            }
            runs.remove(this);
        }
    }

    /**
     * Spawns one display for {@code owner} (counted, tagged, tracked) or returns null when displays are off or the
     * match is over. Blocks that are not placeable blocks are shown as items.
     */
    @Nullable Display spawn(Player owner, Kind kind, @Nullable Material material, @Nullable String text,
                            Location anchor, Transformation transformation) {
        if (disposed || !feel.displays() || anchor.getWorld() == null) {
            return null;
        }
        Kind shown = kind == Kind.BLOCK && (material == null || !material.isBlock()) ? Kind.ITEM : kind;
        if (shown != Kind.TEXT && (material == null || material.isAir())) {
            return null;
        }
        Location at = anchor.clone();
        at.setYaw(0);
        at.setPitch(0);
        try {
            Display display = switch (shown) {
                case BLOCK -> at.getWorld().spawn(at, BlockDisplay.class, d -> {
                    setup(d, transformation);
                    d.setBlock(material.createBlockData());
                });
                case ITEM -> at.getWorld().spawn(at, ItemDisplay.class, d -> {
                    setup(d, transformation);
                    d.setItemStack(new ItemStack(material));
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                });
                case TEXT -> at.getWorld().spawn(at, TextDisplay.class, d -> {
                    setup(d, transformation);
                    d.text(Text.mm(text == null ? "" : text));
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    d.setShadowed(true);
                });
            };
            ctx.track(owner, display);
            live.merge(owner.getUniqueId(), 1, Integer::sum);
            loose.add(display);
            return display;
        } catch (RuntimeException e) {
            session.plugin().getLogger().log(Level.FINE, "Could not spawn a basic attack display", e);
            return null;
        }
    }

    private void setup(Display display, Transformation transformation) {
        display.setPersistent(false);
        display.addScoreboardTag(TAG);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setShadowRadius(0);
        display.setShadowStrength(0);
        display.setViewRange((float) feel.viewRange());
        display.setTeleportDuration(1);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(0);
        display.setTransformation(transformation);
    }

    /** Moves {@code display} to {@code transformation} over the next tick. */
    static void animate(@Nullable Display display, Transformation transformation) {
        if (display == null || !display.isValid()) {
            return;
        }
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTransformation(transformation);
    }

    /** Removes a display this player spawned. */
    void remove(UUID owner, Display display) {
        if (loose.remove(display)) {
            live.computeIfPresent(owner, (id, count) -> count <= 1 ? null : count - 1);
        }
        if (display.isValid()) {
            display.remove();
        }
        session.untrack(display);
    }

    /**
     * The display transformation of {@code pose} turned by {@code frame}. Item displays get the client's half turn
     * of the item undone ({@link ItemDisplayModels#asAuthored}), so a pose points the item's sprite where it says (a
     * blade's tip along the pose's +x+y diagonal), like the preview draws it.
     */
    static Transformation transformation(Kind kind, Pose pose, Quaternionfc frame) {
        Transformation t = new Transformation(FxSprite.translation(kind, pose, frame),
                FxSprite.leftRotation(kind, pose, frame), new Vector3f(pose.size()), new Quaternionf());
        return kind == Kind.ITEM ? ItemDisplayModels.asAuthored(t) : t;
    }

    /** The match ended: every display goes now and nothing new spawns. */
    void dispose() {
        disposed = true;
        for (Run run : List.copyOf(runs)) {
            run.clear();
        }
        for (Display display : List.copyOf(loose)) {
            if (display.isValid()) {
                display.remove();
            }
            session.untrack(display);
        }
        loose.clear();
        live.clear();
    }
}
