package io.github.sx2xoox.mcpl.game.superpower.basic;

import java.util.List;

import org.bukkit.Material;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * One display entity of a basic attack effect over its whole short life, frame by frame (pure data): the runtime
 * spawns it {@code start} ticks after the effect begins, moves it to the next {@link Pose} every tick (client
 * interpolation smooths each step) and removes it after the last one; the preview exporter draws the very same poses.
 *
 * <p>Poses are in the effect's local frame (x left, y up, z forward, blocks), which the runtime turns by the
 * attacker's facing (or a target's direction) and places at the effect's anchor.
 *
 * @param kind     a block, item or text display
 * @param material the block or item (null for text)
 * @param text     MiniMessage text of a text display (null otherwise)
 * @param color    the text's color, also the preview color of a text display (0xRRGGBB)
 * @param start    ticks after the effect begins when it appears
 * @param poses    one pose per tick of its life (the first is where it appears)
 */
public record FxSprite(Kind kind, @Nullable Material material, @Nullable String text, int color, int start,
                       List<Pose> poses) {

    /** Which display entity. */
    public enum Kind {
        /** A block display: the pose's box is the block, centered. */
        BLOCK,
        /** An item display (fixed transform): the item's model centered on the pose, one block wide at size 1. */
        ITEM,
        /** A camera-facing text display; only the center and the size's x (scale) count. */
        TEXT
    }

    /**
     * Where a sprite is in one tick: a box (block displays) or a model (items, text) of {@code size} turned by
     * {@code rotation} around {@code center}.
     */
    public record Pose(Vector3fc center, Quaternionfc rotation, Vector3fc size) {

        /** The size of a vanishing sprite (a display at scale 0 renders nothing). */
        public static final float GONE = 0.001f;

        public Pose {
            center = new Vector3f(center);
            rotation = new Quaternionf(rotation);
            size = new Vector3f(Math.max(GONE, size.x()), Math.max(GONE, size.y()), Math.max(GONE, size.z()));
        }

        /** This pose shrunk to nothing in place (the usual last frame). */
        public Pose gone() {
            return new Pose(center, rotation, new Vector3f(GONE));
        }
    }

    public FxSprite {
        poses = List.copyOf(poses);
        if (poses.isEmpty()) {
            throw new IllegalArgumentException("A sprite needs at least one pose");
        }
        start = Math.max(0, start);
    }

    /** The tick after its last pose (when it is removed). */
    public int end() {
        return start + poses.size();
    }

    /** The same sprite {@code ticks} later. */
    public FxSprite delayed(int ticks) {
        return new FxSprite(kind, material, text, color, start + ticks, poses);
    }

    /**
     * Translation, left rotation and scale of the display's transformation for {@code pose} in a frame turned by
     * {@code frame} (block displays are moved by half their size so the box is centered; item and text models are
     * centered already; text displays face the camera, so they keep no rotation).
     */
    public static Vector3f translation(Kind kind, Pose pose, Quaternionfc frame) {
        Vector3f center = frame.transform(new Vector3f(pose.center()));
        if (kind != Kind.BLOCK) {
            return center;
        }
        Quaternionf left = leftRotation(kind, pose, frame);
        Vector3f half = new Vector3f(pose.size()).mul(0.5f);
        return center.sub(left.transform(half));
    }

    /** The left rotation of the display's transformation (none for text). */
    public static Quaternionf leftRotation(Kind kind, Pose pose, Quaternionfc frame) {
        return kind == Kind.TEXT ? new Quaternionf() : new Quaternionf(frame).mul(pose.rotation());
    }

    /**
     * The model matrix of {@code pose} relative to the anchor (preview): maps a block's unit cube {@code [0,1]³}, or an
     * item's / text's centered unit square {@code [-½,½]²}, to where the display shows it.
     */
    public static Matrix4f matrix(Kind kind, Pose pose, Quaternionfc frame) {
        Matrix4f m = new Matrix4f().translate(translation(kind, pose, frame))
                .rotate(leftRotation(kind, pose, frame))
                .scale(new Vector3f(pose.size()));
        return m;
    }
}
