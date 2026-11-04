package io.github.sx2xoox.mcpl.core.pack;

import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * How the client draws a pack model on an {@link org.bukkit.entity.ItemDisplay}, and the one correction MCPL's models
 * need there.
 *
 * <p>The client (26.3 {@code DisplayRenderer}) draws a display as {@code R_y(-yaw) · transformation · model}, and for
 * an <em>item</em> display ({@code ItemDisplayRenderer.submitInner}) it first turns the item half round about y. A block
 * display has no such turn. MCPL's models (tools/assetgen) are authored with their front (face, door) towards -z, so on
 * an item display with no correction the front ends up at +z of the transformation's frame: a building turned with
 * the block-display maths shows its back, and a creature shown at its mob's yaw + 180 walks backwards.
 *
 * <p>{@link #asAuthored} undoes that turn with the transformation's right rotation (applied to the model first), so a
 * transformation written for the model as authored (front -z, like a block display's boxes) shows it that way. Every
 * pack model on an item display goes through it; the poses themselves stay in the authored frame.
 */
public final class ItemDisplayModels {

    /** Half a turn about y: what the client applies to an item display's item. */
    private static final Quaternionf HALF_TURN = new Quaternionf().rotationY((float) Math.PI);

    private ItemDisplayModels() {
    }

    /** {@code t} for an item display: the client's half turn of the item undone, so {@code t} acts on the model. */
    public static Transformation asAuthored(Transformation t) {
        return new Transformation(t.getTranslation(), t.getLeftRotation(), t.getScale(),
                new Quaternionf(t.getRightRotation()).mul(HALF_TURN));
    }

    /**
     * Where the client draws the model point {@code p} (blocks, in the model's frame, relative to the display) of an
     * item display with entity yaw {@code yaw} and transformation {@code t}, relative to the display's position. For
     * tests and checks: the item's half turn included, like the client.
     */
    public static Vector3f drawn(float yaw, Transformation t, Vector3f p) {
        Vector3f out = new Vector3f(p).rotate(HALF_TURN)
                .rotate(t.getRightRotation()).mul(t.getScale()).rotate(t.getLeftRotation()).add(t.getTranslation());
        return out.rotate(new Quaternionf().rotationY((float) Math.toRadians(-yaw)));
    }

    /** The horizontal direction the authored front (-z) of a model points to, as drawn (unit, y = 0). */
    public static Vector3f front(float yaw, Transformation t) {
        Vector3f origin = drawn(yaw, t, new Vector3f());
        Vector3f ahead = drawn(yaw, t, new Vector3f(0, 0, -1)).sub(origin);
        ahead.y = 0;
        return ahead.lengthSquared() < 1.0E-12f ? ahead : ahead.normalize();
    }
}
