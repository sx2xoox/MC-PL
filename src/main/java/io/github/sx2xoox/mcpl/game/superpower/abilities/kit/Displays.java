package io.github.sx2xoox.mcpl.game.superpower.abilities.kit;

import java.util.function.Consumer;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Display entities (block, item and text) for ability visuals: spawned glowing, shadowless and owner-tracked,
 * animated with client-side interpolation and removed after a hard lifetime.
 *
 * <p>Typical use:
 * <pre>{@code
 * BlockDisplay rock = Displays.block(ctx(), player(), at, Material.TUFF.createBlockData(),
 *         Displays.scaled(0.2f), 30);
 * Displays.animate(rock, Displays.scaled(1.4f), 6);   // grows smoothly over 6 ticks
 * }</pre>
 *
 * <p>The transformation origin of a block display is the block's corner, so {@link #scaled(float)} and
 * {@link #transform} center the shape on the entity location. Item and text displays are already centered.
 * Spawned displays are tracked through {@link AbilityContext#track} (removed when the owner's ability is revoked or
 * the match ends) and additionally removed after {@code lifetimeTicks}.
 */
public final class Displays {

    /** Full brightness, so magic glows at night and in caves. */
    public static final Display.Brightness FULL_BRIGHT = new Display.Brightness(15, 15);

    private Displays() {
    }

    // ------------------------------------------------------------------ spawning

    /** A block display centered on {@code at}. */
    public static BlockDisplay block(AbilityContext ctx, Player owner, Location at, BlockData data,
                                     Transformation start, long lifetimeTicks) {
        return spawn(ctx, owner, at, BlockDisplay.class, lifetimeTicks, display -> {
            display.setBlock(data);
            display.setTransformation(start);
        });
    }

    /** An item display centered on {@code at}. */
    public static ItemDisplay item(AbilityContext ctx, Player owner, Location at, ItemStack item,
                                   Transformation start, long lifetimeTicks) {
        return spawn(ctx, owner, at, ItemDisplay.class, lifetimeTicks, display -> {
            display.setItemStack(item);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setTransformation(start);
        });
    }

    /** A camera-facing text display (no background) centered on {@code at}. */
    public static TextDisplay text(AbilityContext ctx, Player owner, Location at, Component text, float scale,
                                   long lifetimeTicks) {
        return spawn(ctx, owner, at, TextDisplay.class, lifetimeTicks, display -> {
            display.text(text);
            display.setBillboard(Display.Billboard.CENTER);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setShadowed(true);
            display.setTransformation(centered(0, 0, 0, scale, scale, scale, 0, 0, 0));
        });
    }

    /**
     * Spawns any display type with the shared defaults (full bright, no shadow, view range 1.5, smooth teleports),
     * runs {@code setup} before it becomes visible, tracks it for {@code owner} and removes it after
     * {@code lifetimeTicks} (<= 0 means only when the owner's ability is revoked).
     */
    public static <T extends Display> T spawn(AbilityContext ctx, Player owner, Location at, Class<T> type,
                                              long lifetimeTicks, @Nullable Consumer<? super T> setup) {
        T display = at.getWorld().spawn(at, type, d -> {
            d.setPersistent(false);
            d.setBrightness(FULL_BRIGHT);
            d.setShadowRadius(0);
            d.setShadowStrength(0);
            d.setViewRange(1.5f);
            d.setTeleportDuration(2);
            d.setInterpolationDelay(0);
            if (setup != null) {
                setup.accept(d);
            }
        });
        ctx.track(owner, display);
        if (lifetimeTicks > 0) {
            ctx.runLater(owner, lifetimeTicks, () -> {
                if (display.isValid()) {
                    display.remove();
                }
            });
        }
        return display;
    }

    // ------------------------------------------------------------------ animation

    /**
     * Interpolates {@code display} to {@code target} over {@code ticks} ticks, starting next client tick. Call it
     * at least one tick after spawning (or in a later step), otherwise the client snaps to the target.
     */
    public static void animate(Display display, Transformation target, int ticks) {
        if (!display.isValid()) {
            return;
        }
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(Math.max(0, ticks));
        display.setTransformation(target);
    }

    /**
     * Schedules {@link #animate} for the next tick, the usual "spawn small, then grow" pattern.
     */
    public static void animateNextTick(AbilityContext ctx, Player owner, Display display, Transformation target,
                                       int ticks) {
        ctx.runLater(owner, 1, () -> animate(display, target, ticks));
    }

    /** Shrinks {@code display} to nothing over {@code ticks} ticks and removes it afterwards. */
    public static void shrinkAway(AbilityContext ctx, Player owner, Display display, int ticks) {
        Transformation current = display.getTransformation();
        Vector3f center = new Vector3f(current.getTranslation()).add(
                new Quaternionf(current.getLeftRotation()).transform(new Vector3f(current.getScale()).mul(0.5f)));
        boolean block = display instanceof BlockDisplay;
        Transformation gone = new Transformation(block ? center : current.getTranslation(),
                current.getLeftRotation(), new Vector3f(0.001f), current.getRightRotation());
        animate(display, gone, ticks);
        ctx.runLater(owner, Math.max(1, ticks), () -> {
            if (display.isValid()) {
                display.remove();
            }
        });
    }

    /** Gives {@code display} a colored outline visible through walls (use sparingly: telegraphs, key objects). */
    public static void outline(Display display, Color color) {
        display.setGlowColorOverride(color);
        display.setGlowing(true);
    }

    // ------------------------------------------------------------------ transformations

    /** A uniform scale centered on the entity, for block displays (use {@link #centered} for item/text displays). */
    public static Transformation scaled(float scale) {
        return transform(0, 0, 0, scale, scale, scale, 0, 0, 0);
    }

    /**
     * A transformation that scales by {@code (sx, sy, sz)}, rotates by yaw/pitch/roll (degrees) and is centered on
     * the entity location plus the given offset. Use it for <b>block</b> displays: the block's corner is moved so the
     * rotated, scaled block is centered. Item and text displays are already centered; use {@link #centered}.
     */
    public static Transformation transform(float offsetX, float offsetY, float offsetZ, float sx, float sy, float sz,
                                           float yawDeg, float pitchDeg, float rollDeg) {
        Quaternionf rotation = new Quaternionf()
                .rotateY((float) Math.toRadians(-yawDeg))
                .rotateX((float) Math.toRadians(pitchDeg))
                .rotateZ((float) Math.toRadians(rollDeg));
        // Center of a unit block after scaling, rotated, then moved back so the block is centered on the origin.
        Vector3f halfExtent = new Vector3f(sx * 0.5f, sy * 0.5f, sz * 0.5f);
        Vector3f translation = rotation.transform(new Vector3f(halfExtent)).negate()
                .add(offsetX, offsetY, offsetZ);
        return new Transformation(translation, rotation, new Vector3f(sx, sy, sz), new Quaternionf());
    }

    /** Scale + rotation (degrees) + offset for item/text displays, whose models are already centered. */
    public static Transformation centered(float offsetX, float offsetY, float offsetZ, float sx, float sy, float sz,
                                          float yawDeg, float pitchDeg, float rollDeg) {
        Quaternionf rotation = new Quaternionf()
                .rotateY((float) Math.toRadians(-yawDeg))
                .rotateX((float) Math.toRadians(pitchDeg))
                .rotateZ((float) Math.toRadians(rollDeg));
        return new Transformation(new Vector3f(offsetX, offsetY, offsetZ), rotation, new Vector3f(sx, sy, sz),
                new Quaternionf());
    }

    /** A rotation of {@code degrees} around an arbitrary axis, for spinning shapes. */
    public static Quaternionf spin(float degrees, float axisX, float axisY, float axisZ) {
        return new Quaternionf(new AxisAngle4f((float) Math.toRadians(degrees), axisX, axisY, axisZ));
    }
}
