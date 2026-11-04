package io.github.sx2xoox.mcpl.game.superpower.basic;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Fx;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Signature;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Spin;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Weight;
import io.github.sx2xoox.mcpl.game.superpower.basic.FxSprite.Kind;
import io.github.sx2xoox.mcpl.game.superpower.basic.FxSprite.Pose;
import org.bukkit.Material;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The look of basic attacks (평타) as display entities, pure: every function returns the {@link FxSprite}s of one
 * part of an attack (a slash, a beam, a hit spark, ...) in that part's local frame (x left, y up, z forward). The
 * runtime ({@code BasicAttackRunner}) places and turns them and plays them tick by tick; the preview exporter
 * (tools/assetgen/basic_attack_preview.py) draws the same sprites. Vanilla blocks and items only, so it looks right
 * without the resource pack.
 *
 * <p>Art direction: big and clear but short (every sprite lives 3–9 ticks), a translucent colored body
 * ({@link Fx#glow()}) with a bright core ({@link Fx#core()}) and one job flourish ({@link Signature}); the third
 * attack of a combo ({@link Moment#finisher()}) is bigger and adds a shock, a critical hit gets gold sparks. Sprite
 * lists are ordered by importance, so a capped budget drops the extras first.
 *
 * <p>Frames and anchors: {@link #CHEST_DROP} below the eye with the attacker's yaw for swings and bursts, the eye
 * minus {@link #AIM_DROP} with yaw and pitch for thrusts and cones, the muzzle for shots, the ground for strikes and
 * splashes, the target's body (z = from the attacker to it) for hits, the projectile itself (world axes) for trails.
 */
public final class BasicFx {

    /** Swings and bursts are anchored this far below the eye (the chest). */
    public static final float CHEST_DROP = 0.5f;
    /** Thrusts and cones are anchored this far below the eye. */
    public static final float AIM_DROP = 0.25f;
    /** Gold of a critical hit. */
    public static final Material CRIT_CORE = Material.GOLD_BLOCK;
    public static final Material CRIT_GLOW = Material.YELLOW_STAINED_GLASS;

    private static final Quaternionfc NONE = new Quaternionf();

    private BasicFx() {
    }

    /**
     * One attack at the moment it fires.
     *
     * @param step     its place in the combo (0 = first)
     * @param finisher the combo's last attack (bigger, a shock)
     * @param crit     a critical hit (gold sparks)
     * @param seed     randomness of jagged and scattered shapes (the preview passes a fixed one)
     */
    public record Moment(int step, boolean finisher, boolean crit, long seed) {

        /** Plain first attack. */
        public static final Moment FIRST = new Moment(0, false, false, 1);

        /** Size factor of the attack's look. */
        public float boost() {
            return finisher ? 1.2f : 1f;
        }

        /** Sweep direction: attacks alternate left and right. */
        public int side() {
            return step % 2 == 0 ? 1 : -1;
        }
    }

    // =================================================================== frames

    /** A frame turned by a yaw (degrees, Minecraft's): local +z is where that yaw faces. */
    public static Quaternionf yawFrame(float yaw) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yaw));
    }

    /** A frame whose local +z points along {@code (x, y, z)} (no roll; identity for a zero vector). */
    public static Quaternionf aimFrame(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length < 1.0E-6) {
            return new Quaternionf();
        }
        float yaw = (float) Math.atan2(x / length, z / length);
        float pitch = (float) -Math.asin(Math.max(-1, Math.min(1, y / length)));
        return new Quaternionf().rotateY(yaw).rotateX(pitch);
    }

    // =================================================================== melee

    /**
     * A SWING, or one hit ({@code sub}) of a COMBO (yaw frame at the chest): a crescent of glowing segments sweeping
     * across, the weapon flashing along it; claws and punches instead for those jobs. Finisher: heavy jobs crack a
     * shock ring on the ground, the others cross a second slash.
     */
    public static List<FxSprite> swing(BasicAttack attack, Moment moment, int sub, boolean last) {
        Fx fx = attack.fx();
        List<FxSprite> out = new ArrayList<>();
        boolean finisher = moment.finisher() && last;
        float boost = finisher ? 1.2f : 1f;
        int side = (moment.side() * (sub % 2 == 0 ? 1 : -1));
        float size = fx.size() * boost;
        float range = (float) attack.range();
        if (fx.signature() == Signature.PUNCH) {
            punch(out, fx, range, side, size, finisher);
            return out;
        }
        if (fx.signature() == Signature.CLAW) {
            claw(out, fx, range * 0.7f, side, size, 0);
            if (finisher) {
                claw(out, fx, range * 0.8f, -side, size * 1.1f, 2);
            }
            return out;
        }
        float radius = clamp(range * 0.62f, 1.3f, 2.5f) * fx.size() * (finisher ? 1.12f : 1f);
        float width = clamp((float) attack.angle(), 50, 200);
        float tilt = finisher ? 0 : (side > 0 ? -14f : 16f) + (sub % 2 == 1 ? 6f * side : 0);
        Quaternionf roll = new Quaternionf().rotateZ(rad(tilt));
        int sweep = fx.weight() == Weight.HEAVY ? 3 : fx.weight() == Weight.LIGHT ? 1 : 2;
        int segments = Math.round(clamp(width / 16f, 5, 11)) + (finisher ? 2 : 0);
        float thick = 0.5f * size;
        float tall = 0.21f * size;
        // the glowing body: thick in the middle, thin at the tips, drifting outwards as it fades
        arc(out, fx.glow(), segments, 0, sweep, side, width, 0, roll, new Vector3f(0, -0.05f, 0), true,
                scale(radius, 1, 1, 1.05f, 1.1f, 1.13f), new float[] {0.55f, 1, 1, 0.9f, 0.5f},
                scale(tall, 0.6f, 1, 0.8f, 0.45f, 0), scale(thick, 0.6f, 1, 0.75f, 0.35f, 0));
        // the bright edge
        arc(out, fx.core(), Math.max(3, segments / 2), 0, sweep, side, width * 0.9f, 0, roll,
                new Vector3f(0, -0.05f, 0), true, scale(radius + thick * 0.42f, 1, 1, 1.05f, 1.1f, 1.13f),
                new float[] {0.5f, 1, 1, 0.8f, 0.4f}, scale(tall * 0.38f, 0.6f, 1, 0.8f, 0.4f, 0),
                scale(0.07f * size, 0.8f, 1, 0.7f, 0.4f, 0));
        if (fx.signature() == Signature.SHIELD) {
            shieldBash(out, fx, size);
        } else if (fx.weapon() != null) {
            weaponSweep(out, fx.weapon(), radius * 0.62f, width, side, roll, sweep, 0.95f * size, 0);
            if (fx.signature() == Signature.CLEAVER) {
                weaponSweep(out, fx.weapon(), radius * 0.62f, width, side, roll, sweep, 0.72f * size, 1);
                weaponSweep(out, fx.weapon(), radius * 0.62f, width, side, roll, sweep, 0.5f * size, 2);
            }
        }
        if (finisher) {
            if (fx.weight() == Weight.HEAVY) {
                groundRing(out, fx.glow(), 1, -1.08f, 0.6f, radius * 1.25f, 0.4f * size);
            } else {
                Quaternionf cross = new Quaternionf().rotateZ(rad(side > 0 ? 24f : -24f));
                arc(out, fx.glow(), segments - 2, 2, sweep, -side, width * 0.85f, 0, cross,
                        new Vector3f(0, 0.05f, 0), true, scale(radius * 0.92f, 1, 1, 1.05f, 1.1f, 1.13f),
                        new float[] {0.55f, 1, 1, 0.9f, 0.5f}, scale(tall, 0.6f, 1, 0.8f, 0.45f, 0),
                        scale(thick * 0.8f, 0.6f, 1, 0.75f, 0.35f, 0));
            }
        }
        return out;
    }

    /** A THRUST (aim frame at the eye − {@link #AIM_DROP}): a beam shooting out to {@code reach}, the weapon with it. */
    public static List<FxSprite> thrust(BasicAttack attack, Moment moment, double reach) {
        Fx fx = attack.fx();
        List<FxSprite> out = new ArrayList<>();
        float size = fx.size() * moment.boost();
        float end = (float) Math.max(1.0, reach);
        if (fx.signature() == Signature.HOOK) {
            float[] tip = {end * 0.5f, end, end, end * 0.6f, end * 0.25f};
            List<Pose> line = new ArrayList<>();
            List<Pose> hook = new ArrayList<>();
            for (float z : tip) {
                line.add(along(new Vector3f(0, 0, 0.5f), new Vector3f(0, 0, Math.max(0.6f, z)), 0.035f));
                hook.add(new Pose(new Vector3f(0, 0, z), new Quaternionf().rotateZ(rad(45)), uniform(0.6f * size)));
            }
            line.add(line.getLast().gone());
            hook.add(hook.getLast().gone());
            out.add(block(fx.core(), 0, line));
            out.add(item(Material.TRIPWIRE_HOOK, 0, hook));
            if (fx.weapon() != null) {
                out.add(item(fx.weapon(), 0, pointing(new float[] {0.6f, 0.7f, 0.7f, 0.65f, 0.6f}, 0.9f * size)));
            }
            return out;
        }
        float width = 0.075f * size;
        float[] reachAt = {0.35f, 1, 1, 1, 1};
        float[] thin = {0.8f, 1, 0.6f, 0.3f, 0};
        float[] glowThin = {0.7f, 1, 0.7f, 0.35f, 0};
        List<Pose> core = new ArrayList<>();
        List<Pose> glow = new ArrayList<>();
        List<Pose> tip = new ArrayList<>();
        float[] tipSize = {0.2f, 0.36f, 0.26f, 0.1f, 0};
        Quaternionf diamond = new Quaternionf().rotateZ(rad(45));
        for (int f = 0; f < reachAt.length; f++) {
            float z = 0.6f + (end - 0.6f) * reachAt[f];
            core.add(along(new Vector3f(0, 0, 0.6f), new Vector3f(0, 0, z), width * thin[f]));
            glow.add(along(new Vector3f(0, 0, 0.6f), new Vector3f(0, 0, z), width * 2.8f * glowThin[f]));
            tip.add(new Pose(new Vector3f(0, 0, z), new Quaternionf(diamond).rotateX(rad(45)),
                    uniform(tipSize[f] * size)));
        }
        out.add(block(fx.core(), 0, core));
        out.add(block(fx.glow(), 0, glow));
        out.add(block(fx.core(), 0, tip));
        if (fx.weapon() != null) {
            float far = 0.5f + end * 0.45f;
            out.add(item(fx.weapon(), 0, pointing(new float[] {0.7f, far, far + 0.1f, far, far * 0.8f},
                    1.0f * fx.size())));
        }
        if (moment.finisher()) {
            ring(out, fx.glow(), 8, 1, new Vector3f(0, 0, end), facing(), new float[] {0.2f, 0.55f, 0.8f, 0.9f},
                    0.09f * size, 0.14f * size);
        }
        return out;
    }

    /**
     * One SHOT (aim frame at the muzzle): a muzzle flash and a tracer {@code length} long that thins away (long
     * lingering for big shots); two colored beams for the magnet.
     */
    public static List<FxSprite> tracer(BasicAttack attack, Moment moment, double length, int sub) {
        Fx fx = attack.fx();
        List<FxSprite> out = new ArrayList<>();
        float size = fx.size() * moment.boost();
        float end = (float) Math.max(0.6, length);
        float x = sub % 2 == 0 ? 0 : 0.06f;
        float[] fade = fx.size() >= 1.25f ? new float[] {1, 0.85f, 0.65f, 0.45f, 0.25f, 0}
                : new float[] {1, 0.6f, 0.3f, 0};
        float width = 0.045f * size;
        if (fx.signature() == Signature.MAGNET) {
            out.add(block(fx.glow(), 0, beam(x + 0.1f, end, width * 2.2f, fade)));
            out.add(block(Material.BLUE_STAINED_GLASS, 0, beam(x - 0.1f, end, width * 2.2f, fade)));
            out.add(block(fx.core(), 0, beam(x, end, width * 0.7f, fade)));
        } else {
            out.add(block(fx.core(), 0, beam(x, end, width, fade)));
            out.add(block(fx.glow(), 0, beam(x, end, width * 2.6f, fade)));
        }
        float flash = 0.5f * size * (fx.weight() == Weight.HEAVY ? 1.6f : 1f);
        for (int i = 0; i < 3; i++) {
            Quaternionf turn = new Quaternionf().rotateZ(rad(i * 60f + sub * 20f));
            List<Pose> star = new ArrayList<>();
            star.add(new Pose(new Vector3f(x, 0, 0.1f), turn, new Vector3f(flash, 0.07f, 0.07f)));
            star.add(new Pose(new Vector3f(x, 0, 0.12f), turn, new Vector3f(flash * 0.5f, 0.05f, 0.05f)));
            star.add(star.getLast().gone());
            out.add(block(fx.core(), 0, star));
        }
        if (fx.weight() == Weight.HEAVY) {
            for (int i = 0; i < 2; i++) {
                List<Pose> smoke = new ArrayList<>();
                for (int f = 0; f < 6; f++) {
                    smoke.add(new Pose(new Vector3f(x + (i == 0 ? 0.12f : -0.1f), 0.1f * f, 0.25f + 0.06f * f),
                            new Quaternionf().rotateY(rad(20 * f + 40 * i)), uniform((0.25f + 0.05f * f) * size)));
                }
                smoke.add(smoke.getLast().gone());
                out.add(block(Material.LIGHT_GRAY_STAINED_GLASS, 1, smoke));
            }
        }
        return out;
    }

    /** One pulse ({@code sub}) of a CONE (aim frame at the eye − {@link #AIM_DROP}): tongues of fire or breath. */
    public static List<FxSprite> cone(BasicAttack attack, Moment moment, int sub) {
        Fx fx = attack.fx();
        List<FxSprite> out = new ArrayList<>();
        Random random = new Random(moment.seed() * 31 + sub);
        float size = fx.size() * moment.boost();
        float range = (float) attack.range() * (moment.finisher() ? 1.1f : 1f);
        float angle = (float) Math.max(20, attack.angle());
        int tongues = Math.round(clamp(angle / 12f, 3, 6));
        float[] length = {0.25f, 0.55f, 0.85f, 1.0f, 1.0f};
        float[] thick = {0.4f, 0.7f, 1.0f, 0.75f, 0.4f, 0};
        for (int i = 0; i < tongues; i++) {
            float yaw = -angle / 2 + angle * (i + 0.5f) / tongues + (random.nextFloat() - 0.5f) * 8;
            float pitch = (random.nextFloat() - 0.5f) * 12;
            Quaternionf direction = new Quaternionf().rotateY(rad(yaw)).rotateX(rad(pitch));
            List<Pose> poses = new ArrayList<>();
            List<Pose> streak = new ArrayList<>();
            for (int f = 0; f < thick.length; f++) {
                float len = range * length[Math.min(f, length.length - 1)];
                Vector3f center = direction.transform(new Vector3f(0, 0, 0.5f + len / 2));
                Quaternionf rotation = new Quaternionf(direction).rotateZ(rad(45 + 15 * f));
                float t = 0.42f * size * thick[f];
                poses.add(new Pose(center, rotation, new Vector3f(t, t, len)));
                streak.add(new Pose(center, rotation, new Vector3f(t * 0.3f, t * 0.3f, len * 0.8f)));
            }
            out.add(block(fx.glow(), i % 2, poses));
            if (i % 2 == 0) {
                out.add(block(fx.core(), i % 2, streak));
            }
        }
        if (fx.signature() == Signature.CLAW && sub == 0) {
            claw(out, fx, range * 0.7f, moment.side(), size, 1);
        }
        return out;
    }

    /** A NOVA (yaw frame at the chest): a ring rushing out with a bright inner ring; music notes for the bard. */
    public static List<FxSprite> nova(BasicAttack attack, Moment moment) {
        Fx fx = attack.fx();
        List<FxSprite> out = new ArrayList<>();
        float size = fx.size() * moment.boost();
        float range = (float) attack.range() * (moment.finisher() ? 1.1f : 1f);
        arc(out, fx.glow(), 12, 0, 0, 1, 360, 0, NONE, new Vector3f(), false,
                scale(range, 0.15f, 0.45f, 0.75f, 1.0f, 1.08f), new float[] {1, 1, 1, 1, 1},
                scale(0.5f * size, 1, 0.8f, 0.6f, 0.4f, 0), scale(0.3f * size, 1, 1.3f, 1.15f, 0.7f, 0));
        arc(out, fx.core(), 8, 1, 0, 1, 360, 22.5f, NONE, new Vector3f(0, -0.1f, 0), false,
                scale(range * 0.9f, 0.15f, 0.5f, 0.8f, 0.95f), new float[] {1, 1, 1, 1},
                scale(0.08f * size, 1, 1, 0.6f, 0), scale(0.1f * size, 1, 1, 0.6f, 0));
        if (fx.signature() == Signature.NOTES) {
            String[] glyphs = {"♪", "♫", "♪", "♬", "♫"};
            for (int i = 0; i < 5; i++) {
                double a = Math.toRadians(i * 72 + 18);
                List<Pose> poses = new ArrayList<>();
                for (int f = 0; f < 8; f++) {
                    float r = 0.6f + (range * 0.9f - 0.6f) * f / 7f;
                    float s = (f == 0 ? 0.8f : 1.3f) * size;
                    poses.add(new Pose(new Vector3f((float) Math.sin(a) * r, 0.1f * f, (float) Math.cos(a) * r), NONE,
                            uniform(s)));
                }
                poses.add(poses.getLast().gone());
                out.add(text("<bold>" + glyphs[i], i % 2 == 0 ? 0xF472B6 : 0xC084FC, i % 2, poses));
            }
        }
        return out;
    }

    // =================================================================== spots

    /**
     * The warning of a STRIKE that lands {@code delay} ticks later (identity frame on the ground at the spot): a ring
     * and a filling disc; nothing for an instant strike.
     */
    public static List<FxSprite> telegraph(BasicAttack attack, Moment moment, int delay) {
        List<FxSprite> out = new ArrayList<>();
        if (delay < 2) {
            return out;
        }
        Fx fx = attack.fx();
        float radius = (float) attack.radius();
        float[] radii = new float[delay + 1];
        float[] ones = new float[delay + 1];
        float[] tall = new float[delay + 1];
        float[] wide = new float[delay + 1];
        for (int f = 0; f <= delay; f++) {
            radii[f] = radius;
            ones[f] = 1;
            tall[f] = f == delay ? 0 : 0.05f;
            wide[f] = f == delay ? 0 : 0.1f;
        }
        arc(out, fx.glow(), 12, 0, 0, 1, 360, 0, NONE, new Vector3f(0, 0.05f, 0), false, radii, ones, tall, wide);
        for (int i = 0; i < 2; i++) {
            List<Pose> fill = new ArrayList<>();
            Quaternionf turn = new Quaternionf().rotateY(rad(45 * i));
            for (int f = 0; f < delay; f++) {
                float side = 2 * radius * 0.72f * (f + 1) / delay;
                fill.add(new Pose(new Vector3f(0, 0.03f + 0.005f * i, 0), turn, new Vector3f(side, 0.02f, side)));
            }
            fill.add(fill.getLast().gone());
            out.add(block(fx.glow(), 0, fill));
        }
        return out;
    }

    /**
     * A STRIKE landing (frame facing from the attacker, on the ground at the spot): stone spikes bursting up from a
     * cracked ring when the attack has an item (the spike block), else an X cut through space at body height.
     */
    public static List<FxSprite> eruption(BasicAttack attack, Moment moment) {
        Fx fx = attack.fx();
        List<FxSprite> out = new ArrayList<>();
        float size = fx.size() * moment.boost();
        float radius = (float) attack.radius() * moment.boost();
        Material spike = attack.palette().item();
        if (spike == null) {
            Vector3f middle = new Vector3f(0, 1.0f, 0);
            float reach = 0.9f * size;
            for (int i = 0; i < 2; i++) {
                float sx = i == 0 ? reach : -reach;
                Vector3f from = new Vector3f(sx, 1.0f + reach, 0);
                Vector3f to = new Vector3f(-sx, 1.0f - reach, 0);
                out.add(block(fx.core(), i, cut(from, to, 0.06f * size)));
                out.add(block(fx.glow(), i, cut(from, to, 0.22f * size)));
            }
            List<Pose> flash = new ArrayList<>();
            Quaternionf turn = new Quaternionf().rotateX(rad(45)).rotateY(rad(45));
            for (float s : new float[] {0.3f, 0.6f, 0.3f, 0}) {
                flash.add(new Pose(middle, turn, uniform(s * size)));
            }
            out.add(block(fx.core(), 1, flash));
            return out;
        }
        float[] rise = {-0.9f, 0.1f, 0.5f, 0.5f, 0.5f, 0.5f, 0.3f, 0.0f};
        for (int i = 0; i < 5; i++) {
            boolean center = i == 0;
            float w = (center ? 0.9f : 0.7f) * size;
            float h = (center ? 1.9f : 1.3f) * size;
            float a = rad(45 + 90 * i);
            float r = center ? 0 : radius * 0.55f;
            List<Pose> poses = new ArrayList<>();
            for (float y : rise) {
                poses.add(new Pose(new Vector3f((float) Math.sin(a) * r, y * h, (float) Math.cos(a) * r), NONE,
                        new Vector3f(w, h, w)));
            }
            poses.add(poses.getLast().gone());
            out.add(block(spike, center ? 0 : 1, poses));
        }
        groundRing(out, fx.glow(), 0, 0.05f, 0.3f, radius * 1.1f, 0.3f * size);
        return out;
    }

    /** The burst of a LOB (identity frame at the burst): a ground ring, a fire ball and chunks flying out. */
    public static List<FxSprite> splash(BasicAttack attack, Moment moment) {
        Fx fx = attack.fx();
        List<FxSprite> out = new ArrayList<>();
        float size = fx.size() * moment.boost();
        float radius = (float) attack.radius() * moment.boost();
        List<Pose> ball = new ArrayList<>();
        float ballSize = size * Math.max(0.6f, radius / 2);
        for (float s : new float[] {0.4f, 1.0f, 1.2f, 0.8f, 0}) {
            ball.add(new Pose(new Vector3f(0, 0.4f, 0), new Quaternionf().rotateY(rad(45 * s)), uniform(s * ballSize)));
        }
        out.add(block(fx.core(), 0, ball));
        arc(out, fx.glow(), 10, 0, 0, 1, 360, 0, NONE, new Vector3f(0, 0.1f, 0), false,
                scale(radius, 0.2f, 0.6f, 0.95f, 1.05f, 1.05f), new float[] {1, 1, 1, 1, 1},
                scale(0.25f * size, 1, 0.75f, 0.5f, 0.25f, 0), scale(0.3f * size, 1, 1.15f, 1, 0.5f, 0));
        Random random = new Random(moment.seed() * 17 + 3);
        for (int i = 0; i < 5; i++) {
            double a = Math.toRadians(i * 72 + random.nextInt(30));
            Vector3f velocity = new Vector3f((float) Math.sin(a) * 0.25f, 0.32f, (float) Math.cos(a) * 0.25f)
                    .mul(Math.max(0.7f, radius / 2));
            List<Pose> chunk = new ArrayList<>();
            for (int f = 0; f < 6; f++) {
                Vector3f at = new Vector3f(velocity).mul(f).add(0, 0.3f - 0.06f * f * f, 0);
                chunk.add(new Pose(at, new Quaternionf().rotateXYZ(f * 0.5f, f * 0.7f, 0),
                        uniform((0.22f - 0.015f * f) * size)));
            }
            chunk.add(chunk.getLast().gone());
            out.add(block(i % 2 == 0 ? fx.glow() : fx.core(), 0, chunk));
        }
        return out;
    }

    /**
     * A lightning bolt from the anchor to {@code to} (world axes, relative to the anchor): a jagged bright line over
     * a straight glow, a side branch for the thunder god.
     */
    public static List<FxSprite> bolt(BasicAttack attack, Moment moment, Vector3fc to, long seed) {
        Fx fx = attack.fx();
        List<FxSprite> out = new ArrayList<>();
        Random random = new Random(seed);
        float size = fx.size() * moment.boost();
        float distance = to.length();
        if (distance < 0.1f) {
            return out;
        }
        int pieces = Math.round(clamp(distance / 0.9f, 3, 7));
        Vector3f direction = new Vector3f(to).normalize();
        Vector3f side = new Vector3f(direction).cross(0, 1, 0);
        if (side.lengthSquared() < 1.0E-4f) {
            side.set(1, 0, 0);
        }
        side.normalize();
        Vector3f up = new Vector3f(side).cross(direction).normalize();
        List<Vector3f> points = new ArrayList<>();
        for (int i = 0; i <= pieces; i++) {
            Vector3f point = new Vector3f(to).mul((float) i / pieces);
            if (i > 0 && i < pieces) {
                point.add(new Vector3f(side).mul((random.nextFloat() - 0.5f) * 0.7f))
                        .add(new Vector3f(up).mul((random.nextFloat() - 0.5f) * 0.7f));
            }
            points.add(point);
        }
        float[] thick = {1, 1.25f, 0.6f, 0};
        out.add(block(fx.glow(), 0, segmentPoses(new Vector3f(), new Vector3f(to), 0.24f * size,
                new float[] {1, 0.6f, 0})));
        for (int i = 0; i < pieces; i++) {
            out.add(block(fx.core(), 0, segmentPoses(points.get(i), points.get(i + 1), 0.07f * size, thick)));
        }
        if (fx.signature() == Signature.BOLT && pieces > 2) {
            Vector3f from = points.get(1 + random.nextInt(pieces - 1));
            Vector3f branch = new Vector3f(from).add(new Vector3f(side).mul(0.6f * (random.nextBoolean() ? 1 : -1)))
                    .add(new Vector3f(up).mul(0.35f));
            out.add(block(fx.core(), 1, segmentPoses(from, branch, 0.05f * size, new float[] {1, 0.5f, 0})));
        }
        return out;
    }

    // =================================================================== hits

    /**
     * A hit on one target (frame from the attacker to it, at its body): a flash, sparks shaped by the hit's
     * {@link Weight}, a shock ring on the finisher, gold on a critical hit, and the job's flourish.
     */
    public static List<FxSprite> impact(BasicAttack attack, Moment moment, long seed) {
        Fx fx = attack.fx();
        List<FxSprite> out = new ArrayList<>();
        Random random = new Random(seed);
        boolean crit = moment.crit();
        float size = Math.max(0.8f, fx.size()) * (moment.finisher() ? 1.2f : 1f);
        Material core = crit ? CRIT_CORE : fx.core();
        Material glow = crit ? CRIT_GLOW : fx.glow();
        List<Pose> flash = new ArrayList<>();
        Quaternionf turn = new Quaternionf().rotateX(rad(45)).rotateY(rad(45));
        float flashSize = 0.22f * size * (crit ? 1.4f : 1f);
        for (float s : new float[] {1, 1.8f, 0.7f, 0}) {
            flash.add(new Pose(new Vector3f(0, 0, -0.2f), turn, uniform(flashSize * s)));
        }
        out.add(block(core, 0, flash));
        Weight weight = fx.weight();
        int rays = crit ? 6 : switch (weight) {
            case LIGHT -> 3;
            case SHARP, HEAVY -> 4;
            case MAGIC -> 5;
        };
        float length = switch (weight) {
            case SHARP -> 0.75f;
            case LIGHT -> 0.45f;
            case HEAVY -> 0.5f;
            case MAGIC -> 0.55f;
        } * size * (crit ? 1.5f : 1f);
        float width = switch (weight) {
            case SHARP -> 0.05f;
            case LIGHT -> 0.06f;
            case HEAVY -> 0.11f;
            case MAGIC -> 0.07f;
        } * size;
        float[] distance = {0.25f, 0.45f, 0.65f, 0.75f};
        float[] long_ = {0.4f, 1, 0.7f, 0};
        float[] thin = {1, 1, 0.6f, 0};
        for (int i = 0; i < rays; i++) {
            double a = Math.toRadians(30 + 360.0 * i / rays + (random.nextFloat() - 0.5f) * 30);
            Vector3f direction = new Vector3f((float) Math.cos(a), (float) Math.sin(a), -0.35f).normalize();
            Quaternionf along = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), direction);
            List<Pose> poses = new ArrayList<>();
            for (int f = 0; f < distance.length; f++) {
                float l = length * long_[f];
                poses.add(new Pose(new Vector3f(direction).mul(distance[f] * size), along,
                        new Vector3f(width * thin[f], width * thin[f], l)));
            }
            out.add(block(i % 2 == 0 ? core : glow, 0, poses));
        }
        if (moment.finisher()) {
            ring(out, glow, 8, 0, new Vector3f(0, 0, -0.1f), facing(), new float[] {0.3f, 0.7f, 1.0f, 1.1f},
                    0.08f * size, 0.16f * size);
        }
        switch (weight) {
            case HEAVY -> {
                for (int i = 0; i < 2; i++) {
                    out.add(block(fx.core(), 0, flight(new Vector3f(i == 0 ? 0.12f : -0.12f, 0.2f, -0.08f),
                            0.06f, 6, 0.12f * size)));
                }
            }
            case MAGIC -> {
                List<Pose> orb = new ArrayList<>();
                for (float s : new float[] {0.4f, 0.7f, 0.5f, 0}) {
                    orb.add(new Pose(new Vector3f(), new Quaternionf().rotateY(rad(30 * s)), uniform(s * size)));
                }
                out.add(block(glow, 0, orb));
            }
            default -> {
            }
        }
        signatureOnHit(out, fx, moment, random, size);
        return out;
    }

    private static void signatureOnHit(List<FxSprite> out, Fx fx, Moment moment, Random random, float size) {
        switch (fx.signature()) {
            case SOUL -> {
                List<Pose> soul = new ArrayList<>();
                for (int f = 0; f < 9; f++) {
                    soul.add(new Pose(new Vector3f(0, 0.18f * f, 0), new Quaternionf().rotateY(rad(25 * f)),
                            uniform((0.3f - 0.02f * f) * size)));
                }
                soul.add(soul.getLast().gone());
                out.add(block(Material.LIGHT_BLUE_STAINED_GLASS, 1, soul));
            }
            case BLOOD -> {
                for (int i = 0; i < 2; i++) {
                    out.add(block(Material.REDSTONE_BLOCK, 0, flight(new Vector3f((random.nextFloat() - 0.5f) * 0.2f,
                            0.05f, -0.05f), 0.05f, 7, 0.09f * size)));
                }
            }
            case EMBER -> {
                for (int i = 0; i < 3; i++) {
                    out.add(block(Material.SHROOMLIGHT, 0, flight(new Vector3f((random.nextFloat() - 0.5f) * 0.3f,
                            0.22f + random.nextFloat() * 0.1f, -0.06f), 0.05f, 7, 0.08f * size)));
                }
            }
            case WEB -> {
                List<Pose> web = new ArrayList<>();
                for (float s : new float[] {0.6f, 1.2f, 1.2f, 1.2f, 1.2f, 1.2f, 1.1f, 0.8f, 0}) {
                    web.add(new Pose(new Vector3f(0, 0, -0.35f), NONE, uniform(s)));
                }
                out.add(item(Material.COBWEB, 0, web));
            }
            case HOMERUN -> {
                boolean finisher = moment.finisher();
                String word = finisher ? "<bold><#FF6A3D>홈런!" : "<bold><#FFD84A>딱!";
                float base = finisher ? 1.6f : 1.0f;
                List<Pose> poses = new ArrayList<>();
                float[] grow = {0.7f, 1.3f, 1.2f, 1.2f, 1.2f, 1.2f, 1.15f, 1.1f, 1.0f};
                for (int f = 0; f < grow.length; f++) {
                    poses.add(new Pose(new Vector3f(0, 1.0f + 0.05f * f, -0.2f), NONE, uniform(grow[f] * base)));
                }
                poses.add(poses.getLast().gone());
                out.add(text(word, finisher ? 0xFF6A3D : 0xFFD84A, 0, poses));
            }
            default -> {
            }
        }
    }

    // =================================================================== projectiles

    /** One piece of a flying projectile's look. */
    public record Part(Kind kind, Material material, Pose base, boolean spins) {
    }

    /**
     * The flying look of a PROJECTILE or LOB: its item (bigger on the finisher), or without one a wind crescent
     * (sharp attacks) or a glowing orb.
     */
    public static List<Part> projectile(BasicAttack attack, Moment moment) {
        Fx fx = attack.fx();
        float size = fx.size() * (moment.finisher() ? 1.4f : 1f);
        List<Part> parts = new ArrayList<>();
        Material item = attack.palette().item();
        if (item != null) {
            parts.add(new Part(Kind.ITEM, item, new Pose(new Vector3f(), NONE, uniform(0.55f * size)), true));
        } else if (fx.weight() == Weight.SHARP) {
            List<FxSprite> crescent = new ArrayList<>();
            float radius = 0.55f * size;
            arc(crescent, fx.glow(), 3, 0, 0, 1, 110, 0, NONE, new Vector3f(0, 0, -radius), true,
                    new float[] {radius}, new float[] {1}, new float[] {0.14f * size}, new float[] {0.3f * size});
            arc(crescent, fx.core(), 2, 0, 0, 1, 90, 0, NONE, new Vector3f(0, 0, -radius), true,
                    new float[] {radius + 0.12f * size}, new float[] {1}, new float[] {0.05f * size},
                    new float[] {0.06f * size});
            for (FxSprite sprite : crescent) {
                parts.add(new Part(Kind.BLOCK, sprite.material(), sprite.poses().getFirst(), false));
            }
        } else {
            Quaternionf turn = new Quaternionf().rotateX(rad(45)).rotateY(rad(45));
            parts.add(new Part(Kind.BLOCK, fx.core(), new Pose(new Vector3f(), turn, uniform(0.2f * size)), true));
            parts.add(new Part(Kind.BLOCK, fx.glow(), new Pose(new Vector3f(), NONE, uniform(0.42f * size)), true));
        }
        return parts;
    }

    /** {@code part} {@code tick}s into the flight (in the flight's aim frame): turned by the attack's {@link Spin}. */
    public static Pose partPose(BasicAttack attack, Part part, int tick) {
        if (!part.spins()) {
            return part.base();
        }
        Quaternionf spin = switch (part.kind() == Kind.ITEM ? attack.fx().spin() : Spin.ROLL) {
            case FLAT -> new Quaternionf().rotateY(rad(55 * tick)).rotateX(rad(90));
            case POINT -> new Quaternionf().rotateZ(rad(25 * tick)).rotateY(rad(-90)).rotateZ(rad(-45));
            case TUMBLE -> new Quaternionf().rotateX(rad(40 * tick));
            case ROLL -> new Quaternionf().rotateY(rad(20 * tick)).rotateX(rad(28 * tick));
        };
        return new Pose(spin.transform(new Vector3f(part.base().center())),
                new Quaternionf(spin).mul(part.base().rotation()), part.base().size());
    }

    /**
     * What a projectile leaves behind in its {@code tick}-th tick (world axes at its position; {@code back} = where
     * it was a tick ago, relative): a fading streak, bubbles for water, ones and zeros for the hacker.
     */
    public static List<FxSprite> trail(BasicAttack attack, Moment moment, Vector3fc back, int tick) {
        Fx fx = attack.fx();
        List<FxSprite> out = new ArrayList<>();
        float size = fx.size() * (moment.finisher() ? 1.3f : 1f);
        switch (fx.signature()) {
            case BUBBLE -> {
                if (tick % 2 == 0) {
                    Random random = new Random(moment.seed() + tick);
                    Vector3f at = new Vector3f(back).mul(0.5f).add((random.nextFloat() - 0.5f) * 0.3f,
                            (random.nextFloat() - 0.5f) * 0.3f, (random.nextFloat() - 0.5f) * 0.3f);
                    List<Pose> bubble = new ArrayList<>();
                    for (int f = 0; f < 5; f++) {
                        bubble.add(new Pose(new Vector3f(at).add(0, 0.05f * f, 0), NONE, uniform(0.16f * size)));
                    }
                    bubble.add(bubble.getLast().gone());
                    out.add(block(fx.glow(), 0, bubble));
                }
            }
            case BINARY -> {
                if (tick % 3 == 0) {
                    List<Pose> bit = new ArrayList<>();
                    for (int f = 0; f < 6; f++) {
                        bit.add(new Pose(new Vector3f(back).mul(0.5f).add(0, 0.06f * f, 0), NONE, uniform(0.7f)));
                    }
                    bit.add(bit.getLast().gone());
                    out.add(text("<bold>" + ((tick / 3) % 2 == 0 ? "1" : "0"), 0x4ADE80, 0, bit));
                }
            }
            default -> {
                if (back.lengthSquared() > 1.0E-4f) {
                    out.add(block(fx.glow(), 0, segmentPoses(new Vector3f(back), new Vector3f(), 0.14f * size,
                            new float[] {1, 0.6f, 0.25f, 0})));
                }
            }
        }
        return out;
    }

    // =================================================================== shared shapes

    /**
     * Tangent segments of a horizontal circle arc around the frame's origin (turned by {@code tilt}, then moved by
     * {@code offset}): {@code width} degrees wide centred on {@code centerYaw} (0 = forward), drawn from one side to
     * the other over {@code sweep} ticks. The arrays give each frame's radius and the segments' tangent stretch,
     * height and radial width (a zero ends it); {@code crescent} thins the tips.
     */
    static void arc(List<FxSprite> out, Material material, int segments, int start, int sweep, int side,
                    float width, float centerYaw, Quaternionfc tilt, Vector3fc offset, boolean crescent,
                    float[] radius, float[] stretch, float[] tall, float[] wide) {
        int frames = radius.length;
        for (int k = 0; k < segments; k++) {
            float theta = centerYaw + side * (-width / 2 + width * (k + 0.5f) / segments);
            int appear = start + (segments > 1 ? Math.round(k * (float) sweep / (segments - 1)) : 0);
            float profile = crescent ? 0.35f + 0.65f * (float) Math.pow(Math.sin(Math.PI * (k + 0.5) / segments), 0.7)
                    : 1;
            Quaternionf rotation = new Quaternionf(tilt).rotateY(rad(theta));
            List<Pose> poses = new ArrayList<>();
            for (int f = 0; f < frames; f++) {
                float r = radius[f];
                float length = r * rad(width / segments) * 1.15f * stretch[Math.min(f, stretch.length - 1)];
                Vector3f center = tilt.transform(new Vector3f((float) Math.sin(rad(theta)) * r, 0,
                        (float) Math.cos(rad(theta)) * r)).add(offset);
                poses.add(new Pose(center, rotation, new Vector3f(length, tall[f] * profile, wide[f] * profile)));
            }
            out.add(block(material, appear, poses));
        }
    }

    /** A flat ring on the ground {@code y} below or above the anchor, rushing from {@code from} to {@code to}. */
    private static void groundRing(List<FxSprite> out, Material material, int start, float y, float from, float to,
                                   float wide) {
        float[] radii = new float[5];
        for (int f = 0; f < 5; f++) {
            radii[f] = from + (to - from) * Math.min(1, f / 3f);
        }
        arc(out, material, 10, start, 0, 1, 360, 0, NONE, new Vector3f(0, y, 0), false, radii,
                new float[] {1, 1, 1, 1, 1}, new float[] {0.06f, 0.06f, 0.05f, 0.03f, 0},
                new float[] {wide, wide, wide * 0.7f, wide * 0.35f, 0});
    }

    /** A ring in the plane {@code plane} turns the ground into, at {@code at}, growing through {@code radii}. */
    private static void ring(List<FxSprite> out, Material material, int segments, int start, Vector3fc at,
                             Quaternionfc plane, float[] radii, float tall, float wide) {
        int frames = radii.length + 1;
        float[] all = new float[frames];
        float[] ones = new float[frames];
        float[] t = new float[frames];
        float[] w = new float[frames];
        for (int f = 0; f < frames; f++) {
            all[f] = radii[Math.min(f, radii.length - 1)];
            ones[f] = 1;
            float fade = f >= radii.length ? 0 : 1 - 0.5f * f / radii.length;
            t[f] = tall * fade;
            w[f] = wide * fade;
        }
        arc(out, material, segments, start, 0, 1, 360, 0, plane, at, false, all, ones, t, w);
    }

    /** Turns the ground plane (x, z) to face forward (x, y). */
    private static Quaternionf facing() {
        return new Quaternionf().rotateX(rad(90));
    }

    /** Three parallel claw marks across the front, {@code distance} ahead. */
    private static void claw(List<FxSprite> out, Fx fx, float distance, int side, float size, int start) {
        for (int i = -1; i <= 1; i++) {
            float x = 0.3f * i * size;
            Vector3f from = new Vector3f(x + 0.45f * side * size, 0.55f * size, distance);
            Vector3f to = new Vector3f(x - 0.45f * side * size, -0.6f * size, distance);
            out.add(block(fx.glow(), start + i + 1, cut(from, to, 0.16f * size)));
            out.add(block(fx.core(), start + i + 1, cut(from, to, 0.06f * size)));
        }
    }

    /** A jab: a streak to {@code range} × 0.6 and a shock ring there. */
    private static void punch(List<FxSprite> out, Fx fx, float range, int side, float size, boolean finisher) {
        float distance = Math.max(1.2f, range * 0.6f);
        Vector3f at = new Vector3f(0.22f * side, 0.05f, distance);
        float big = finisher ? 1.6f : 1f;
        ring(out, fx.glow(), 8, 0, at, facing(), new float[] {0.15f * big, 0.4f * big, 0.6f * big, 0.7f * big},
                0.1f * size, 0.14f * size);
        out.add(block(fx.core(), 0, segmentPoses(new Vector3f(0.22f * side, 0.05f, 0.4f), at, 0.12f * size,
                new float[] {1, 0.5f, 0})));
        if (finisher) {
            ring(out, fx.core(), 8, 1, at, facing(), new float[] {0.1f, 0.3f, 0.45f}, 0.05f * size, 0.06f * size);
        }
    }

    /** The shield slamming forward. */
    private static void shieldBash(List<FxSprite> out, Fx fx, float size) {
        List<Pose> shield = new ArrayList<>();
        for (float z : new float[] {0.5f, 1.3f, 1.5f, 1.45f, 1.4f}) {
            shield.add(new Pose(new Vector3f(0, -0.1f, z), NONE, uniform(1.1f * size)));
        }
        shield.add(shield.getLast().gone());
        if (fx.weapon() != null) {
            out.add(item(fx.weapon(), 0, shield));
        }
        ring(out, fx.glow(), 8, 1, new Vector3f(0, -0.1f, 1.75f), facing(), new float[] {0.3f, 0.7f, 1.0f},
                0.08f * size, 0.16f * size);
    }

    /** The weapon item swinging along the arc, its tip outwards; {@code lag} ticks behind (ghosts). */
    private static void weaponSweep(List<FxSprite> out, Material weapon, float radius, float width, int side,
                                    Quaternionfc tilt, int sweep, float scale, int lag) {
        List<Pose> poses = new ArrayList<>();
        int steps = Math.max(2, sweep + 1);
        for (int f = 0; f < steps; f++) {
            float theta = side * (-width / 2 + width * f / (steps - 1));
            Quaternionf rotation = new Quaternionf(tilt).rotateY(rad(theta - 90)).rotateX(rad(-55)).rotateZ(rad(-45));
            Vector3f center = tilt.transform(new Vector3f((float) Math.sin(rad(theta)) * radius, -0.05f,
                    (float) Math.cos(rad(theta)) * radius));
            poses.add(new Pose(center, rotation, uniform(scale)));
        }
        poses.add(poses.getLast().gone());
        out.add(item(weapon, lag, poses));
    }

    /** An item pointing forward at the given distances (one per frame), then gone. */
    private static List<Pose> pointing(float[] distances, float scale) {
        List<Pose> poses = new ArrayList<>();
        Quaternionf forward = new Quaternionf().rotateY(rad(-90)).rotateZ(rad(-45));
        for (float z : distances) {
            poses.add(new Pose(new Vector3f(0, -0.05f, z), forward, uniform(scale)));
        }
        poses.add(poses.getLast().gone());
        return poses;
    }

    /** A straight beam along z from 0.3 to {@code end}, its width fading by {@code fade}. */
    private static List<Pose> beam(float x, float end, float width, float[] fade) {
        List<Pose> poses = new ArrayList<>();
        for (float f : fade) {
            poses.add(along(new Vector3f(x, 0, 0.3f), new Vector3f(x, 0, end), width * f));
        }
        return poses;
    }

    /** A slash cut from {@code from} to {@code to}: drawn out in two ticks, then thinning away. */
    private static List<Pose> cut(Vector3f from, Vector3f to, float width) {
        List<Pose> poses = new ArrayList<>();
        float[] reach = {0.4f, 1, 1, 1, 1};
        float[] thin = {1, 1, 0.6f, 0.3f, 0};
        for (int f = 0; f < reach.length; f++) {
            Vector3f end = new Vector3f(to).sub(from).mul(reach[f]).add(from);
            poses.add(along(from, end, width * thin[f]));
        }
        return poses;
    }

    /** A segment from {@code a} to {@code b} whose width fades by {@code fade}. */
    private static List<Pose> segmentPoses(Vector3f a, Vector3f b, float width, float[] fade) {
        List<Pose> poses = new ArrayList<>();
        for (float f : fade) {
            poses.add(along(a, b, width * f));
        }
        return poses;
    }

    /** A small cube thrown from the anchor at {@code velocity} (blocks per tick) under gravity, shrinking. */
    private static List<Pose> flight(Vector3f velocity, float gravity, int frames, float size) {
        List<Pose> poses = new ArrayList<>();
        for (int f = 0; f < frames; f++) {
            Vector3f at = new Vector3f(velocity).mul(f).add(0, -gravity * f * f, 0);
            poses.add(new Pose(at, new Quaternionf().rotateXYZ(0.4f * f, 0.6f * f, 0),
                    uniform(size * (1 - 0.6f * f / frames))));
        }
        poses.add(poses.getLast().gone());
        return poses;
    }

    /** A box from {@code a} to {@code b}, {@code width} thick (its z axis along the segment). */
    static Pose along(Vector3fc a, Vector3fc b, float width) {
        Vector3f delta = new Vector3f(b).sub(a);
        float length = delta.length();
        Quaternionf rotation = length < 1.0E-5f ? new Quaternionf()
                : new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(delta).div(length));
        return new Pose(new Vector3f(a).add(b).mul(0.5f), rotation, new Vector3f(width, width, Math.max(length, 0.01f)));
    }

    private static FxSprite block(Material material, int start, List<Pose> poses) {
        return new FxSprite(Kind.BLOCK, material, null, 0, start, poses);
    }

    private static FxSprite item(Material material, int start, List<Pose> poses) {
        return new FxSprite(Kind.ITEM, material, null, 0, start, poses);
    }

    private static FxSprite text(String text, int color, int start, List<Pose> poses) {
        return new FxSprite(Kind.TEXT, null, text, color, start, poses);
    }

    private static float[] scale(float base, float... factors) {
        float[] out = new float[factors.length];
        for (int i = 0; i < factors.length; i++) {
            out[i] = base * factors[i];
        }
        return out;
    }

    private static Vector3f uniform(float size) {
        return new Vector3f(size, size, size);
    }

    private static float rad(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
