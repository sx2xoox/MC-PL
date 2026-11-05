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
        } else if (fx.signature() == Signature.STAFF) {
            staff(out, fx, radius, width, side, roll, sweep, size, finisher);
        } else if (fx.weapon() != null) {
            weaponSweep(out, fx.weapon(), radius * 0.62f, width, side, roll, sweep, 0.95f * size, 0);
            if (fx.signature() == Signature.CLEAVER) {
                weaponSweep(out, fx.weapon(), radius * 0.62f, width, side, roll, sweep, 0.72f * size, 1);
                weaponSweep(out, fx.weapon(), radius * 0.62f, width, side, roll, sweep, 0.5f * size, 2);
            }
        }
        if (fx.signature() == Signature.PAW) {
            paw(out, fx, clamp(range * 0.75f, 1.2f, 2.6f), side, size, sweep, finisher);
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
        if (fx.signature() == Signature.SONAR) {
            sonar(out, fx, end, size, moment.finisher());
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
        if (fx.signature() == Signature.HORNS) {
            horns(out, fx, end, size);
        }
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
        } else if (fx.signature() == Signature.STONE) {
            // two snake-eye glares side by side, a gold slit in each
            for (float eye : new float[] {0.09f, -0.09f}) {
                out.add(block(fx.glow(), 0, beam(x + eye, end, width * 1.8f, fade)));
                out.add(block(fx.core(), 0, beam(x + eye, end, width * 0.55f, fade)));
            }
            List<Pose> slit = new ArrayList<>();
            for (float s : new float[] {0.3f, 1, 1, 0.7f, 0}) {
                slit.add(new Pose(new Vector3f(x, 0.02f, 0.35f), NONE, new Vector3f(0.05f * s, 0.32f * s, 0.05f)
                        .mul(size)));
            }
            out.add(block(Material.GOLD_BLOCK, 0, slit));
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
        if (fx.signature() == Signature.FEATHERS || fx.signature() == Signature.INK) {
            boolean feathers = fx.signature() == Signature.FEATHERS;
            int count = feathers ? 4 : 3;
            for (int i = 0; i < count; i++) {
                float yaw = -angle / 2 + angle * (i + 0.5f) / count + (random.nextFloat() - 0.5f) * 10;
                float pitch = (random.nextFloat() - 0.3f) * 14;
                Vector3f step = new Quaternionf().rotateY(rad(yaw)).rotateX(rad(-pitch))
                        .transform(new Vector3f(0, 0, range / 6f));
                List<Pose> poses = new ArrayList<>();
                for (int f = 0; f < 7; f++) {
                    Vector3f at = new Vector3f(step).mul(f + 0.8f).add(0, feathers ? 0.04f * f : -0.03f * f * f, 0);
                    Quaternionf turn = feathers ? new Quaternionf().rotateZ(rad(70 * f + 40 * i)).rotateX(rad(30 * f))
                            : new Quaternionf().rotateXYZ(0.5f * f, 0.7f * f, 0);
                    float s = (feathers ? 0.55f : 0.22f) * size * (f < 5 ? 1 : 0.6f);
                    poses.add(new Pose(at, turn, uniform(s)));
                }
                poses.add(poses.getLast().gone());
                out.add(feathers ? item(Material.FEATHER, 1, poses) : block(fx.core(), 1, poses));
            }
        }
        return out;
    }

    /** A NOVA (yaw frame at the chest): a ring rushing out with a bright inner ring; music notes for the bard. */
    public static List<FxSprite> nova(BasicAttack attack, Moment moment) {
        Fx fx = attack.fx();
        List<FxSprite> out = new ArrayList<>();
        float size = fx.size() * moment.boost();
        float range = (float) attack.range() * (moment.finisher() ? 1.1f : 1f);
        if (fx.signature() == Signature.QUAKE) {
            quake(out, fx, range, size, new Random(moment.seed() * 13 + 5));
            return out;
        }
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
        if (fx.signature() == Signature.SUNBEAM) {
            sunbeam(out, fx, radius, size);
            return out;
        }
        if (fx.signature() == Signature.VINES) {
            vines(out, fx, radius, size, new Random(moment.seed() * 7 + 11));
            return out;
        }
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
        if (fx.signature() == Signature.SHARDS) {
            Random random = new Random(moment.seed() * 19 + 7);
            for (int i = 0; i < 6; i++) {
                double a = Math.toRadians(i * 60 + random.nextInt(40));
                Vector3f velocity = new Vector3f((float) Math.sin(a) * 0.2f, 0.28f + random.nextFloat() * 0.1f,
                        (float) Math.cos(a) * 0.2f);
                out.add(block(i % 2 == 0 ? fx.core() : fx.glow(), 1,
                        toss(new Vector3f(0, 0.9f * size, 0), velocity, 0.05f, 7, 0.16f * size)));
            }
        }
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
        if (fx.signature() == Signature.FANGS) {
            serpent(out, fx, to, side, up, pieces, size, random.nextBoolean() ? 1 : -1);
            return out;
        }
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
            case TENNIS -> {
                boolean finisher = moment.finisher();
                word(out, finisher ? "<bold><#FF8A3D>에이스!" : "<bold><#D9F99D>퐁!", finisher ? 0xFF8A3D : 0xD9F99D,
                        finisher ? 1.5f : 0.95f);
            }
            case RUNE -> {
                String[] runes = {"ᚠ", "ᚢ", "ᚦ", "ᚱ", "ᚷ", "ᛟ"};
                String rune = runes[random.nextInt(runes.length)];
                List<Pose> poses = new ArrayList<>();
                float[] grow = {0.6f, 1.6f, 1.4f, 1.3f, 1.3f, 1.25f, 1.2f, 1.1f, 0.8f};
                for (int f = 0; f < grow.length; f++) {
                    poses.add(new Pose(new Vector3f(0, 0.75f + 0.04f * f, -0.3f), NONE, uniform(grow[f] * size)));
                }
                poses.add(poses.getLast().gone());
                out.add(text("<bold><#FDE68A>" + rune, 0xFDE68A, 0, poses));
                ring(out, Material.YELLOW_STAINED_GLASS, 8, 1, new Vector3f(0, 0, -0.25f), facing(),
                        new float[] {0.3f, 0.55f, 0.7f, 0.75f}, 0.04f * size, 0.07f * size);
            }
            case STONE, SHARDS, HORNS, SHELL -> {
                Material chip = switch (fx.signature()) {
                    case STONE -> Material.STONE;
                    case SHARDS -> Material.AMETHYST_BLOCK;
                    case HORNS -> Material.COARSE_DIRT;
                    default -> Material.GREEN_CONCRETE;
                };
                int count = fx.signature() == Signature.STONE ? 5 : 4;
                for (int i = 0; i < count; i++) {
                    double a = Math.toRadians(360.0 * i / count + random.nextInt(40));
                    Vector3f velocity = new Vector3f((float) Math.cos(a) * 0.14f, 0.12f + 0.12f * (float) Math.max(0,
                            Math.sin(a)) + random.nextFloat() * 0.06f, -0.08f - random.nextFloat() * 0.05f);
                    out.add(block(chip, 0, toss(new Vector3f(0, 0, -0.3f), velocity, 0.045f, 8, 0.13f * size)));
                }
                if (fx.signature() == Signature.STONE) {
                    // a grey crust closing over the front for a moment, then cracking off
                    List<Pose> crust = new ArrayList<>();
                    for (float s : new float[] {0.3f, 0.75f, 0.8f, 0.8f, 0.78f, 0.5f, 0}) {
                        crust.add(new Pose(new Vector3f(0, 0, -0.38f), new Quaternionf().rotateZ(rad(45)),
                                new Vector3f(s * size, s * size, 0.04f)));
                    }
                    out.add(block(Material.LIGHT_GRAY_STAINED_GLASS, 0, crust));
                }
            }
            case INK -> {
                List<Pose> splat = new ArrayList<>();
                for (float s : new float[] {0.4f, 0.9f, 0.85f, 0.85f, 0.8f, 0.75f, 0.65f, 0.45f, 0}) {
                    splat.add(new Pose(new Vector3f(0, -0.05f, -0.37f), new Quaternionf().rotateZ(rad(20)),
                            new Vector3f(s * size, s * 0.8f * size, 0.03f)));
                }
                out.add(block(Material.BLACK_CONCRETE, 0, splat));
                for (int i = 0; i < 3; i++) {
                    out.add(block(Material.BLACK_CONCRETE, 1, toss(new Vector3f((i - 1) * 0.2f, -0.2f, -0.38f),
                            new Vector3f(0, 0.02f, -0.02f), 0.035f, 8, 0.08f * size)));
                }
            }
            case FANGS -> {
                for (int i = 0; i < 2; i++) {
                    out.add(block(Material.LIME_CONCRETE, 0, toss(new Vector3f((i == 0 ? 0.12f : -0.12f), 0.1f, -0.35f),
                            new Vector3f(0, 0.03f, -0.03f), 0.04f, 8, 0.08f * size)));
                }
            }
            case HEARTS -> {
                for (int i = 0; i < 3; i++) {
                    List<Pose> heart = new ArrayList<>();
                    float x = (i - 1) * 0.35f;
                    for (int f = 0; f < 9; f++) {
                        heart.add(new Pose(new Vector3f(x * (1 + 0.15f * f), 0.3f + 0.12f * f, -0.3f), NONE,
                                uniform((f == 0 ? 0.6f : 1.1f - 0.04f * f) * size)));
                    }
                    heart.add(heart.getLast().gone());
                    out.add(text("<bold>❤", i == 1 ? 0xFF4F9A : 0xFF9AC8, i == 1 ? 0 : 1, heart));
                }
            }
            case TALISMAN -> {
                List<Pose> paper = new ArrayList<>();
                List<Pose> seal = new ArrayList<>();
                float[] grow = {0.5f, 1.0f, 0.95f, 0.95f, 0.95f, 0.95f, 0.9f, 0.9f, 0.85f, 0.6f};
                for (int f = 0; f < grow.length; f++) {
                    paper.add(new Pose(new Vector3f(0, 0.15f, -0.4f), new Quaternionf().rotateZ(rad(-8)),
                            uniform(0.6f * grow[f] * size)));
                    seal.add(new Pose(new Vector3f(0, 0.55f + 0.02f * f, -0.45f), NONE, uniform(1.1f * grow[f] * size)));
                }
                paper.add(paper.getLast().gone());
                seal.add(seal.getLast().gone());
                out.add(item(Material.PAPER, 0, paper));
                out.add(text("<bold><#DC2626>勅", 0xDC2626, 0, seal));
            }
            case FOXFIRE -> {
                for (int i = 0; i < 2; i++) {
                    List<Pose> wisp = new ArrayList<>();
                    for (int f = 0; f < 8; f++) {
                        float sway = (float) Math.sin(f * 0.9f + i * 2) * 0.12f;
                        wisp.add(new Pose(new Vector3f((i == 0 ? 0.2f : -0.2f) + sway, 0.1f + 0.14f * f, -0.25f),
                                new Quaternionf().rotateY(rad(30 * f)), uniform((0.2f - 0.015f * f) * size)));
                    }
                    wisp.add(wisp.getLast().gone());
                    out.add(block(fx.glow(), i, wisp));
                }
            }
            default -> {
            }
        }
    }

    /** A camera-facing word popping over the hit and drifting up. */
    private static void word(List<FxSprite> out, String word, int color, float base) {
        List<Pose> poses = new ArrayList<>();
        float[] grow = {0.7f, 1.3f, 1.2f, 1.2f, 1.2f, 1.2f, 1.15f, 1.1f, 1.0f};
        for (int f = 0; f < grow.length; f++) {
            poses.add(new Pose(new Vector3f(0, 1.0f + 0.05f * f, -0.2f), NONE, uniform(grow[f] * base)));
        }
        poses.add(poses.getLast().gone());
        out.add(text(word, color, 0, poses));
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
        } else if (fx.signature() == Signature.PALM) {
            // a palm print of wind facing the flight: the palm, four fingers, a thumb, a bright heart
            parts.add(new Part(Kind.BLOCK, fx.glow(), new Pose(new Vector3f(0, -0.05f, 0), NONE,
                    new Vector3f(0.5f, 0.5f, 0.08f).mul(size)), false));
            for (int i = 0; i < 4; i++) {
                float x = (-0.18f + 0.12f * i) * size;
                float tall = (i == 1 || i == 2 ? 0.34f : 0.27f) * size;
                parts.add(new Part(Kind.BLOCK, fx.glow(), new Pose(new Vector3f(x, 0.2f * size + tall / 2, 0), NONE,
                        new Vector3f(0.1f * size, tall, 0.08f * size)), false));
            }
            parts.add(new Part(Kind.BLOCK, fx.glow(), new Pose(new Vector3f(0.31f * size, -0.02f * size, 0),
                    new Quaternionf().rotateZ(rad(-40)), new Vector3f(0.1f, 0.26f, 0.08f).mul(size)), false));
            parts.add(new Part(Kind.BLOCK, fx.core(), new Pose(new Vector3f(0, -0.05f, -0.02f), NONE,
                    new Vector3f(0.22f, 0.22f, 0.1f).mul(size)), false));
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
            case HEARTS -> {
                streak(out, fx, back, size);
                if (tick % 3 == 1) {
                    List<Pose> heart = new ArrayList<>();
                    for (int f = 0; f < 7; f++) {
                        heart.add(new Pose(new Vector3f(back).mul(0.5f).add(0, 0.07f * f, 0), NONE,
                                uniform((0.75f - 0.05f * f) * size)));
                    }
                    heart.add(heart.getLast().gone());
                    out.add(text("<bold>❤", (tick / 3) % 2 == 0 ? 0xFF4F9A : 0xFFB3D1, 0, heart));
                }
            }
            case FOXFIRE -> {
                streak(out, fx, back, size);
                Random random = new Random(moment.seed() * 3 + tick);
                List<Pose> wisp = new ArrayList<>();
                Vector3f at = new Vector3f(back).mul(0.4f).add((random.nextFloat() - 0.5f) * 0.2f, 0,
                        (random.nextFloat() - 0.5f) * 0.2f);
                for (int f = 0; f < 6; f++) {
                    wisp.add(new Pose(new Vector3f(at).add(0, 0.09f * f, 0), new Quaternionf().rotateY(rad(35 * f)),
                            uniform((0.15f - 0.02f * f) * size)));
                }
                wisp.add(wisp.getLast().gone());
                out.add(block(tick % 2 == 0 ? fx.core() : fx.glow(), 0, wisp));
            }
            case KI -> {
                streak(out, fx, back, size);
                if (tick % 3 == 0 && back.lengthSquared() > 1.0E-4f) {
                    Quaternionf plane = aimFrame(back.x(), back.y(), back.z()).mul(facing());
                    ring(out, fx.glow(), 6, 0, new Vector3f(back).mul(0.5f), plane,
                            new float[] {0.2f * size, 0.4f * size, 0.55f * size}, 0.04f * size, 0.08f * size);
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

    /**
     * A long staff sweeping the arc instead of a blade (제천대성): a gold rod from behind the shoulder out past the
     * slash with red bands at both ends, a bit longer on the finisher.
     */
    private static void staff(List<FxSprite> out, Fx fx, float radius, float width, int side, Quaternionfc tilt,
                              int sweep, float size, boolean finisher) {
        int steps = Math.max(3, sweep + 2);
        float reach = radius * (finisher ? 1.5f : 1.3f);
        List<Pose> rod = new ArrayList<>();
        List<Pose> tip = new ArrayList<>();
        List<Pose> butt = new ArrayList<>();
        for (int f = 0; f < steps; f++) {
            float t = Math.min(1f, f / (float) (steps - 2));
            float theta = side * (-width / 2 + width * t);
            Vector3f direction = tilt.transform(new Vector3f((float) Math.sin(rad(theta)), 0,
                    (float) Math.cos(rad(theta))));
            Vector3f from = new Vector3f(direction).mul(-0.5f * size).add(0, -0.05f, 0);
            Vector3f to = new Vector3f(direction).mul(reach).add(0, -0.05f, 0);
            rod.add(along(from, to, 0.1f * size));
            tip.add(along(new Vector3f(direction).mul(reach - 0.32f * size).add(0, -0.05f, 0), to, 0.15f * size));
            butt.add(along(from, new Vector3f(direction).mul(-0.2f * size).add(0, -0.05f, 0), 0.15f * size));
        }
        rod.add(rod.getLast().gone());
        tip.add(tip.getLast().gone());
        butt.add(butt.getLast().gone());
        out.add(block(fx.core(), 0, rod));
        out.add(block(Material.RED_CONCRETE, 0, tip));
        out.add(block(Material.RED_CONCRETE, 0, butt));
    }

    /** A huge paw print slamming flat at the end of the swing, four toes over the pad; claws on the finisher. */
    private static void paw(List<FxSprite> out, Fx fx, float distance, int side, float size, int sweep,
                            boolean finisher) {
        float[] pop = {0.4f, 1.2f, 1.0f, 1.0f, 0.95f, 0.8f, 0.5f};
        float[] slam = {-0.6f, 0, 0, 0, 0, 0, 0};
        float big = (finisher ? 1.3f : 1f) * size;
        Vector3f pad = new Vector3f(0.15f * side, -0.1f, distance);
        List<Pose> body = new ArrayList<>();
        for (int f = 0; f < pop.length; f++) {
            body.add(new Pose(new Vector3f(pad).add(0, 0, slam[f]), NONE,
                    new Vector3f(0.62f * pop[f], 0.5f * pop[f], 0.08f).mul(big)));
        }
        body.add(body.getLast().gone());
        out.add(block(fx.core(), sweep, body));
        float[][] toes = {{-0.3f, 0.3f}, {-0.1f, 0.42f}, {0.1f, 0.42f}, {0.3f, 0.3f}};
        for (float[] toe : toes) {
            List<Pose> poses = new ArrayList<>();
            for (int f = 0; f < pop.length; f++) {
                poses.add(new Pose(new Vector3f(pad).add(toe[0] * big, toe[1] * big, slam[f]), NONE,
                        new Vector3f(0.17f * pop[f], 0.17f * pop[f], 0.08f).mul(big)));
            }
            poses.add(poses.getLast().gone());
            out.add(block(fx.core(), sweep, poses));
        }
        if (finisher) {
            claw(out, fx, distance + 0.15f, side, size * 1.1f, sweep + 1);
        }
    }

    /** Two bull horns goring forward with a thrust, dust kicked up behind the feet. */
    private static void horns(List<FxSprite> out, Fx fx, float end, float size) {
        float[] reach = {0.35f, 1, 1, 0.9f, 0.7f};
        float[] thin = {0.8f, 1, 1, 0.7f, 0.4f};
        for (int s = -1; s <= 1; s += 2) {
            List<Pose> root = new ArrayList<>();
            List<Pose> point = new ArrayList<>();
            for (int f = 0; f < reach.length; f++) {
                float z = 0.6f + (end - 0.6f) * reach[f] * 0.85f;
                Vector3f base = new Vector3f(0.3f * s, 0.25f, 0.45f + (z - 0.6f) * 0.25f);
                Vector3f mid = new Vector3f(0.5f * s, 0.42f, 0.5f + (z - 0.5f) * 0.6f);
                Vector3f tip = new Vector3f(0.2f * s, 0.6f, z);
                root.add(along(base, mid, 0.17f * size * thin[f]));
                point.add(along(mid, tip, 0.1f * size * thin[f]));
            }
            root.add(root.getLast().gone());
            point.add(point.getLast().gone());
            out.add(block(Material.BONE_BLOCK, 0, root));
            out.add(block(fx.core(), 0, point));
        }
        for (int i = 0; i < 3; i++) {
            out.add(block(fx.glow(), 0, toss(new Vector3f((i - 1) * 0.3f, -1.2f, 0.1f),
                    new Vector3f((i - 1) * 0.05f, 0.07f, -0.12f), 0.01f, 6, 0.3f * size)));
        }
    }

    /** Warden-like sonic rings racing down the line, growing as they go, over a thin core. */
    private static void sonar(List<FxSprite> out, Fx fx, float end, float size, boolean finisher) {
        out.add(block(fx.core(), 0, beam(0, end, 0.05f * size, new float[] {1, 0.8f, 0.5f, 0.2f, 0})));
        int rings = finisher ? 4 : 3;
        for (int k = 0; k < rings; k++) {
            float z = 0.9f + (end - 0.9f) * k / (rings - 1);
            float grow = (1 + 0.3f * k) * size;
            ring(out, fx.glow(), 8, k, new Vector3f(0, 0, z), facing(),
                    new float[] {0.2f * grow, 0.42f * grow, 0.58f * grow, 0.66f * grow}, 0.05f * size, 0.09f * size);
        }
    }

    /** A ground slam around the attacker (chest frame): cracks, a rushing ring of dust and rubble jumping up. */
    private static void quake(List<FxSprite> out, Fx fx, float range, float size, Random random) {
        float ground = -1.06f;
        groundRing(out, fx.glow(), 0, ground, 0.4f, range, 0.5f * size);
        groundRing(out, fx.core(), 1, ground + 0.02f, 0.3f, range * 0.65f, 0.2f * size);
        for (int i = 0; i < 5; i++) {
            double a = Math.toRadians(i * 72 + random.nextInt(30));
            Vector3f direction = new Vector3f((float) Math.sin(a), 0, (float) Math.cos(a));
            Vector3f to = new Vector3f(direction).mul(range * (0.7f + 0.3f * random.nextFloat())).add(0, ground, 0);
            out.add(block(Material.BLACK_STAINED_GLASS, 0, cut(new Vector3f(0, ground, 0), to, 0.1f * size)));
        }
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(i * 60 + random.nextInt(40));
            Vector3f direction = new Vector3f((float) Math.sin(a), 0, (float) Math.cos(a));
            Vector3f from = new Vector3f(direction).mul(range * 0.55f).add(0, ground + 0.1f, 0);
            Vector3f velocity = new Vector3f(direction).mul(0.06f).add(0, 0.28f + random.nextFloat() * 0.08f, 0);
            out.add(block(i % 2 == 0 ? Material.COBBLESTONE : fx.core(), 1,
                    toss(from, velocity, 0.06f, 7, 0.26f * size)));
        }
    }

    /** A pillar of sunlight crashing down on the spot (ground frame): a gold column, a bright core and a disc. */
    private static void sunbeam(List<FxSprite> out, Fx fx, float radius, float size) {
        float tall = 7f;
        float[] wide = {0.35f, 1.0f, 0.95f, 0.8f, 0.55f, 0.3f};
        float[] drop = {0.3f, 1, 1, 1, 1, 1};
        List<Pose> column = new ArrayList<>();
        List<Pose> core = new ArrayList<>();
        List<Pose> disc = new ArrayList<>();
        for (int f = 0; f < wide.length; f++) {
            float h = tall * drop[f];
            float w = radius * 0.9f * size * wide[f];
            column.add(new Pose(new Vector3f(0, tall - h / 2, 0), NONE, new Vector3f(w, h, w)));
            core.add(new Pose(new Vector3f(0, tall - h / 2, 0), new Quaternionf().rotateY(rad(45)),
                    new Vector3f(w * 0.35f, h, w * 0.35f)));
            disc.add(new Pose(new Vector3f(0, 0.04f, 0), new Quaternionf().rotateY(rad(15 * f)),
                    new Vector3f(2 * radius * wide[f], 0.05f, 2 * radius * wide[f])));
        }
        column.add(column.getLast().gone());
        core.add(core.getLast().gone());
        disc.add(disc.getLast().gone());
        out.add(block(fx.core(), 0, core));
        out.add(block(fx.glow(), 0, column));
        out.add(block(fx.glow(), 1, disc));
        groundRing(out, fx.glow(), 1, 0.06f, 0.3f, radius * 1.3f, 0.3f * size);
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(i * 90 + 45);
            out.add(block(fx.core(), 1, toss(new Vector3f(0, 0.2f, 0), new Vector3f((float) Math.sin(a) * 0.12f,
                    0.3f, (float) Math.cos(a) * 0.12f), 0.04f, 7, 0.12f * size)));
        }
    }

    /** Thorny vines coiling up out of the ground (ground frame) and bursting into leaves. */
    private static void vines(List<FxSprite> out, Fx fx, float radius, float size, Random random) {
        float[] rise = {0.2f, 0.7f, 1.2f, 1.5f, 1.5f, 1.4f, 1.0f, 0.5f};
        for (int i = 0; i < 5; i++) {
            boolean center = i == 0;
            double a = Math.toRadians(i * 90 + random.nextInt(30));
            float r = center ? 0 : radius * 0.6f;
            float x = (float) Math.sin(a) * r;
            float z = (float) Math.cos(a) * r;
            float height = (center ? 1.4f : 1.0f) * size;
            List<Pose> stalk = new ArrayList<>();
            for (int f = 0; f < rise.length; f++) {
                float h = rise[f] * height;
                Quaternionf lean = new Quaternionf().rotateY(rad(40 * f + 70 * i)).rotateX(rad(center ? 0 : 12));
                stalk.add(new Pose(new Vector3f(x, h / 2, z), lean, new Vector3f(0.16f * size, h, 0.16f * size)));
            }
            stalk.add(stalk.getLast().gone());
            out.add(block(fx.core(), center ? 0 : 1, stalk));
        }
        groundRing(out, fx.glow(), 0, 0.05f, 0.3f, radius * 1.1f, 0.3f * size);
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(i * 90 + random.nextInt(60));
            out.add(block(Material.AZALEA_LEAVES, 2, toss(new Vector3f(0, 1.3f * size, 0),
                    new Vector3f((float) Math.sin(a) * 0.14f, 0.18f, (float) Math.cos(a) * 0.14f), 0.04f, 7,
                    0.24f * size)));
        }
    }

    /** A green serpent writhing from the anchor to {@code to}, its white fangs bared at the end. */
    private static void serpent(List<FxSprite> out, Fx fx, Vector3fc to, Vector3fc side, Vector3fc up, int pieces,
                                float size, int flip) {
        out.add(block(fx.glow(), 0, segmentPoses(new Vector3f(), new Vector3f(to), 0.22f * size,
                new float[] {1, 0.6f, 0})));
        Vector3f last = new Vector3f();
        float[] thick = {0.6f, 1, 1, 0.6f, 0};
        for (int i = 1; i <= pieces; i++) {
            float t = (float) i / pieces;
            float wave = (float) Math.sin(t * Math.PI * 2) * 0.32f * flip * (1 - t * 0.5f);
            Vector3f point = new Vector3f(to).mul(t).add(new Vector3f(side).mul(wave))
                    .add(new Vector3f(up).mul((float) Math.sin(t * Math.PI) * 0.15f));
            out.add(block(fx.core(), 0, segmentPoses(last, point, 0.11f * size * (1.2f - 0.4f * t), thick)));
            last = point;
        }
        Vector3f direction = new Vector3f(to).normalize();
        Quaternionf point = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), direction);
        for (int s = -1; s <= 1; s += 2) {
            List<Pose> fang = new ArrayList<>();
            for (float g : new float[] {0.5f, 1, 1, 0.7f, 0}) {
                fang.add(new Pose(new Vector3f(to).add(new Vector3f(up).mul(0.12f * s)).sub(new Vector3f(direction)
                        .mul(0.15f)), point, new Vector3f(0.07f, 0.07f, 0.24f).mul(g * size)));
            }
            out.add(block(Material.WHITE_CONCRETE, 1, fang));
        }
    }

    /** A fading streak from where a projectile was a tick ago (the usual trail). */
    private static void streak(List<FxSprite> out, Fx fx, Vector3fc back, float size) {
        if (back.lengthSquared() > 1.0E-4f) {
            out.add(block(fx.glow(), 0, segmentPoses(new Vector3f(back), new Vector3f(), 0.14f * size,
                    new float[] {1, 0.6f, 0.25f, 0})));
        }
    }

    /** A small cube thrown from {@code from} at {@code velocity} (blocks per tick) under gravity, shrinking. */
    private static List<Pose> toss(Vector3fc from, Vector3fc velocity, float gravity, int frames, float size) {
        List<Pose> poses = new ArrayList<>();
        for (Pose pose : flight(new Vector3f(velocity), gravity, frames, size)) {
            poses.add(new Pose(new Vector3f(pose.center()).add(from), pose.rotation(), pose.size()));
        }
        return poses;
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
