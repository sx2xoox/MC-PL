package io.github.sx2xoox.mcpl.game.siege.tower;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.core.pack.ModelRef;
import io.github.sx2xoox.mcpl.core.pack.PackAudience;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The 3D model look of the 공성전 towers (cyberpunk megacity): 방화벽 게이트 ({@code front}), 데이터 허브
 * ({@code middle}) and 메인프레임 ({@code core}), drawn with models of the MCPL resource pack made in
 * {@code tools/assetgen/models/towers.py}. A tower is several item displays with an {@code item_model} (a model only
 * spans 3 blocks at scale 1): body slabs per damage stage ({@code tower_<id>_<part>_<stage>}: intact, cracked, heavily
 * damaged, ruin), moving parts (the gate's energy barrier, the hub's data rings and core, the mainframe's heart and
 * halos) and a hex energy shield per tower size ({@code tower_<id>_shield}). Neon, racks, holograms, the barrier and
 * the crystals are tinted with the team colour ({@code dyed_color}).
 *
 * <p>Players who have not loaded the pack ({@link PackAudience#hasPack}) would see missing-model cubes, so the model
 * entities are hidden by default and shown only to players with the pack, while a coarse vanilla look of scaled block
 * displays (black concrete, glass, iron, bright team-coloured concrete neon) is shown to everyone else (and hidden
 * from players with the pack). A player's view swaps whenever their pack status changes. Every entity is tracked and
 * removed by {@link Visual#remove()}.
 *
 * <p>The tower's front (the gate side) faces the yaw of the {@code base} location (yaw 90 = towards -x).
 */
public final class ModelTowerLook implements TowerLook {

    /** Damage stage of the ruin (after {@link Visual#destroyed()}). */
    public static final int RUIN = 3;

    /** How a part moves. */
    enum Motion {
        /** Still (body slabs). */
        NONE(0),
        /** The gate's energy barrier: shimmers (animated texture) and ripples when hit. */
        BARRIER(0),
        /** A data ring: turns slowly about the vertical axis. */
        RING(40),
        /** The smaller data ring: turns the other way. */
        RING_REVERSE(30),
        /** The hub's data core: turns and bobs. */
        CORE(20),
        /** The mainframe's heart: turns and pulses. */
        HEART(20),
        /** A halo round the heart: spins tilted. */
        HALO(24),
        /** The second halo: spins the other way, tilted the other way. */
        HALO_REVERSE(32),
        /** The shield: grows out of the ground when raised. */
        SHIELD(0);

        final int period;

        Motion(int period) {
            this.period = period;
        }
    }

    /**
     * One display of a tower (kept in sync with {@code PARTS} in tools/assetgen/models/towers.py).
     *
     * @param model  part name: {@code tower_<id>_<model>_<stage>} when staged, else {@code tower_<id>_<model>}
     * @param stages number of stage models (0 = one model); the part collapses when the tower falls past them
     * @param until  the part shows while the stage is below this (hidden at higher stages, gone for the ruin)
     * @param height model centre above the ground, blocks
     * @param scale  display scale
     * @param motion how it moves
     */
    record Part(String model, int stages, int until, float height, float scale, Motion motion) {

        ModelRef ref(String towerId, int stage) {
            return stages > 0
                    ? new ModelRef("tower_" + towerId + "_" + model + "_" + Math.min(stage, stages - 1))
                    : new ModelRef("tower_" + towerId + "_" + model);
        }
    }

    /**
     * A tower: its displays, the fallback's size, and the hitbox it needs.
     *
     * @param parts        the model displays
     * @param shield       half width (x), half depth (z) and height of the shield dome, blocks
     * @param spinnerAt    height of the fallback's spinning team-glass crystal (heart or core), blocks
     * @param spinnerSize  its size, blocks (0 = none)
     * @param hitbox       recommended Mannequin scale at the default {@code hitbox-scale} 2.5
     */
    record Spec(List<Part> parts, Vector3f shield, float spinnerAt, float spinnerSize, double hitbox) {
    }

    private static final float BODY = 4f;
    private static final float SHIELD_SCALE = 8f;
    private static final float SHIELD_CENTRE = 11f;
    private static final double DEFAULT_HITBOX = 2.5;

    static final Map<String, Spec> SPECS = Map.of(
            "front", new Spec(List.of(
                    new Part("body", 4, 4, 6, BODY, Motion.NONE),
                    new Part("barrier", 3, 3, 6, BODY, Motion.BARRIER),
                    new Part("shield", 0, 3, SHIELD_CENTRE, SHIELD_SCALE, Motion.SHIELD)),
                    new Vector3f(5.75f, 13.5f, 4f), 0, 0, 6),
            "middle", new Spec(List.of(
                    new Part("base", 4, 4, 6, BODY, Motion.NONE),
                    new Part("top", 3, 3, 18, BODY, Motion.NONE),
                    new Part("ring", 0, 3, 6.5f, BODY, Motion.RING),
                    new Part("ring", 0, 3, 12.2f, BODY * 0.8f, Motion.RING_REVERSE),
                    new Part("core", 0, 2, 15.3f, BODY, Motion.CORE),
                    new Part("shield", 0, 3, SHIELD_CENTRE, SHIELD_SCALE, Motion.SHIELD)),
                    new Vector3f(5.25f, 19.75f, 5.25f), 15.3f, 1f, 7),
            "core", new Spec(List.of(
                    new Part("base", 4, 4, 6, BODY, Motion.NONE),
                    new Part("top", 3, 3, 18, BODY, Motion.NONE),
                    new Part("heart", 0, 3, 11, BODY * 1.25f, Motion.HEART),
                    new Part("halo", 0, 3, 11, BODY, Motion.HALO),
                    new Part("halo", 0, 3, 11, BODY * 1.15f, Motion.HALO_REVERSE),
                    new Part("shield", 0, 3, SHIELD_CENTRE, SHIELD_SCALE, Motion.SHIELD)),
                    new Vector3f(6.75f, 20.5f, 6.75f), 11f, 2.2f, 8));

    private static final int FLASH_TICKS = 4;
    private static final int SHAKE_TICKS = 3;
    private static final int SHIELD_POP_TICKS = 8;
    private static final int COLLAPSE_TICKS = 16;
    private static final float VIEW_RANGE = 4f;
    private static final Display.Brightness FULL_BRIGHT = new Display.Brightness(15, 15);

    private final Plugin plugin;
    private final PackAudience audience;

    public ModelTowerLook(Plugin plugin, PackAudience audience) {
        this.plugin = plugin;
        this.audience = audience;
    }

    /** Every model this look uses (all parts of all towers, every stage). */
    public static Set<ModelRef> models() {
        Set<ModelRef> out = new LinkedHashSet<>();
        for (Map.Entry<String, Spec> tower : SPECS.entrySet()) {
            for (Part part : tower.getValue().parts()) {
                for (int stage = 0; stage < Math.max(1, part.stages()); stage++) {
                    out.add(part.ref(tower.getKey(), stage));
                }
            }
        }
        return out;
    }

    /** Damage stage for the health left: 0 intact (over 2/3), 1 cracked, 2 heavily damaged (1/3 or less). */
    public static int stage(double health) {
        if (health > 2 / 3.0) {
            return 0;
        }
        return health > 1 / 3.0 ? 1 : 2;
    }

    @Override
    public double hitboxScale(String towerId, double configured) {
        Spec spec = SPECS.get(towerId);
        return spec == null ? configured : configured * spec.hitbox() / DEFAULT_HITBOX;
    }

    @Override
    public ModelVisual spawn(Location base, String towerId, TextColor teamColor) {
        Spec spec = SPECS.get(towerId);
        if (spec == null) {
            throw new IllegalArgumentException("unknown tower: " + towerId);
        }
        return new ModelVisual(base.clone(), towerId, spec, Color.fromRGB(teamColor.value()));
    }

    /** One tower: its model entities, its vanilla fallback, and the per-player choice between them. */
    public final class ModelVisual implements Visual {

        /** A live model display and its part. */
        private final class Shown {
            final Part part;
            final ItemDisplay display;
            int step;
            int collapseAt = -1;
            boolean hidden;

            Shown(Part part, ItemDisplay display) {
                this.part = part;
                this.display = display;
            }
        }

        private final Location base;
        private final String towerId;
        private final Spec spec;
        private final Color tint;
        private final Quaternionf facing;
        private final Material neon;
        private final Material energy;
        private final List<Shown> parts = new ArrayList<>();
        private final List<Display> models = new ArrayList<>();
        private final List<Display> fallback = new ArrayList<>();
        private final List<BlockDisplay> fallbackBoxes = new ArrayList<>();
        private @Nullable BlockDisplay fallbackSpinner;
        private final BlockDisplay fallbackShield;
        private final PackAudience.Subscription subscription;
        private final BukkitTask ticker;
        private int stage;
        private boolean shielded;
        private boolean removed;
        private long tick;
        private int flash;
        private int shake;
        private int spinStep;

        ModelVisual(Location base, String towerId, Spec spec, Color tint) {
            this.base = base;
            this.towerId = towerId;
            this.spec = spec;
            this.tint = tint;
            // models face north (-Z); turn the front towards the base's yaw (yaw 0 = south)
            this.facing = new Quaternionf().rotationY((float) Math.PI - (float) Math.toRadians(base.getYaw()));
            this.neon = coloured(tint, "_CONCRETE", Material.WHITE_CONCRETE);
            this.energy = coloured(tint, "_STAINED_GLASS", Material.WHITE_STAINED_GLASS);
            World world = base.getWorld();
            Location at = base.clone();
            at.setYaw(0);
            at.setPitch(0);

            for (Part part : spec.parts()) {
                Shown shown = new Shown(part, spawnModel(world, at, part));
                parts.add(shown);
                animate(shown.display, transform(shown), 1);
            }

            for (Box box : FallbackShape.of(towerId, 0, neon, energy)) {
                BlockDisplay display = spawnBlock(world, at, box.material().createBlockData(), boxTransform(box));
                display.setBrightness(box.bright() ? FULL_BRIGHT : null);
                fallbackBoxes.add(display);
            }
            if (spec.spinnerSize() > 0) {
                fallbackSpinner = spawnBlock(world, at, energy.createBlockData(), spinnerTransform(0));
                fallbackSpinner.setBrightness(FULL_BRIGHT);
            }
            fallbackShield = spawnBlock(world, at, Material.LIGHT_BLUE_STAINED_GLASS.createBlockData(),
                    shieldBlockTransform(false));

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (audience.hasPack(player)) {
                    for (Display model : models) {
                        player.showEntity(plugin, model);
                    }
                }
            }
            subscription = audience.subscribe(this::refresh);
            ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        }

        // ------------------------------------------------------------------ TowerLook.Visual

        @Override
        public void health(double fraction) {
            if (removed || stage == RUIN) {
                return;
            }
            setStage(ModelTowerLook.stage(Math.max(0, Math.min(1, fraction))));
        }

        @Override
        public void shielded(boolean shielded) {
            if (removed || this.shielded == shielded || stage == RUIN) {
                return;
            }
            this.shielded = shielded;
            for (Shown shown : parts) {
                if (shown.part.motion() == Motion.SHIELD) {
                    animate(shown.display, transform(shown), SHIELD_POP_TICKS);
                }
            }
            animate(fallbackShield, shieldBlockTransform(shielded), SHIELD_POP_TICKS);
        }

        @Override
        public void hit() {
            if (removed || stage == RUIN) {
                return;
            }
            flash = FLASH_TICKS;
            shake = SHAKE_TICKS;
            for (Display display : glowParts()) {
                display.setGlowColorOverride(Color.WHITE);
                display.setGlowing(true);
            }
            for (Shown shown : parts) {
                if (shown.part.motion() == Motion.BARRIER && !shown.hidden) {
                    // a ripple through the firewall
                    animate(shown.display, transform(shown, 0, 0, 1.06f), 2);
                }
            }
        }

        @Override
        public void destroyed() {
            if (removed || stage == RUIN) {
                return;
            }
            shielded(false);
            setStage(RUIN);
            World world = base.getWorld();
            Location middle = base.clone().add(0, 4, 0);
            world.spawnParticle(Particle.BLOCK, middle, 160, 2.2, 3, 2.2, 0.2,
                    Material.BLACK_CONCRETE.createBlockData());
            world.spawnParticle(Particle.EXPLOSION, middle, 5, 1.6, 2.5, 1.6, 0);
            world.spawnParticle(Particle.ELECTRIC_SPARK, middle, 80, 2, 3, 2, 0.4);
            world.playSound(middle, Sound.ENTITY_GENERIC_EXPLODE, 2.5f, 0.6f);
            world.playSound(middle, Sound.BLOCK_BEACON_DEACTIVATE, 2f, 0.7f);
            if (spec.spinnerSize() > 0) {
                Location spot = base.clone().add(0, spec.spinnerAt(), 0);
                world.spawnParticle(Particle.DUST, spot, 80, 1, 1.2, 1, 0, new Particle.DustOptions(tint, 1.8f));
                world.playSound(spot, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 2f, 0.6f);
            }
            if (fallbackSpinner != null) {
                fallback.remove(fallbackSpinner);
                fallbackSpinner.remove();
                fallbackSpinner = null;
            }
        }

        @Override
        public void remove() {
            if (removed) {
                return;
            }
            removed = true;
            ticker.cancel();
            subscription.close();
            for (Display display : models) {
                display.remove();
            }
            for (Display display : fallback) {
                display.remove();
            }
            models.clear();
            fallback.clear();
            fallbackBoxes.clear();
            parts.clear();
            fallbackSpinner = null;
        }

        // ------------------------------------------------------------------ state, for tests

        int stage() {
            return stage;
        }

        boolean isShielded() {
            return shielded;
        }

        List<Display> models() {
            return List.copyOf(models);
        }

        List<Display> fallback() {
            return List.copyOf(fallback);
        }

        /** The model each live part shows now ({@code null} for a hidden part). */
        List<@Nullable ModelRef> partModels() {
            List<@Nullable ModelRef> out = new ArrayList<>();
            for (Shown shown : parts) {
                out.add(shown.hidden ? null : shown.part.ref(towerId, stage));
            }
            return out;
        }

        Color tint() {
            return tint;
        }

        // ------------------------------------------------------------------ per-viewer

        /** Shows {@code player} the models or the fallback, depending on whether they have the pack now. */
        private void refresh(Player player) {
            if (removed) {
                return;
            }
            boolean pack = audience.hasPack(player);
            for (Display model : models) {
                if (pack) {
                    player.showEntity(plugin, model);
                } else {
                    player.hideEntity(plugin, model);
                }
            }
            for (Display block : fallback) {
                if (pack) {
                    player.hideEntity(plugin, block);
                } else {
                    player.showEntity(plugin, block);
                }
            }
        }

        // ------------------------------------------------------------------ animation

        private void tick() {
            tick++;
            for (Shown shown : List.copyOf(parts)) {
                if (shown.collapseAt >= 0) {
                    if (tick >= shown.collapseAt) {
                        parts.remove(shown);
                        models.remove(shown.display);
                        shown.display.remove();
                    }
                    continue;
                }
                int period = shown.part.motion().period;
                if (period > 0 && !shown.hidden && tick % period == 1) {
                    shown.step++;
                    animate(shown.display, transform(shown), period);
                }
            }
            if (fallbackSpinner != null && tick % 40 == 1) {
                spinStep++;
                animate(fallbackSpinner, spinnerTransform(spinStep), 40);
            }
            if (shake > 0) {
                shake--;
                float amount = shake == 0 ? 0 : 0.07f;
                float sign = shake % 2 == 0 ? 1 : -1;
                for (Shown shown : parts) {
                    if (shown.part.stages() > 0 && shown.collapseAt < 0) {
                        animate(shown.display, transform(shown, sign * amount, -sign * amount, 1), 1);
                    }
                }
            }
            if (flash > 0 && --flash == 0) {
                for (Display display : glowParts()) {
                    display.setGlowing(false);
                    display.setGlowColorOverride(null);
                }
            }
            ambience();
        }

        /** Sparks fly from a heavily damaged tower, a ruin smokes. */
        private void ambience() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            World world = base.getWorld();
            if (stage == 2 && tick % 12 == 0) {
                Location spot = base.clone().add(random.nextDouble(-2.5, 2.5), random.nextDouble(2, 9),
                        random.nextDouble(-2.5, 2.5));
                world.spawnParticle(Particle.ELECTRIC_SPARK, spot, 8, 0.3, 0.3, 0.3, 0.2);
            } else if (stage == RUIN && tick % 6 == 0) {
                Location spot = base.clone().add(random.nextDouble(-2, 2), 1.5, random.nextDouble(-2, 2));
                world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, spot, 1, 0.2, 0.1, 0.2, 0.01);
            }
        }

        private List<Display> glowParts() {
            List<Display> out = new ArrayList<>(models);
            out.addAll(fallback);
            return out;
        }

        private void setStage(int next) {
            if (next == stage) {
                return;
            }
            stage = next;
            for (Shown shown : parts) {
                Part part = shown.part;
                if (shown.collapseAt >= 0) {
                    continue;
                }
                if (next < part.until()) {
                    shown.display.setItemStack(part.ref(towerId, next).item(tint));
                    if (shown.hidden) {
                        shown.hidden = false;
                        animate(shown.display, transform(shown), 6);
                    }
                } else if (next == RUIN && part.stages() > 0) {
                    // the upper slabs and the barrier come down with the tower
                    shown.collapseAt = (int) tick + COLLAPSE_TICKS;
                    animate(shown.display, collapseTransform(shown), COLLAPSE_TICKS);
                } else if (next == RUIN) {
                    // the shield fades, crystals and rings shatter
                    if (part.motion() != Motion.SHIELD && !shown.hidden) {
                        Location spot = base.clone().add(0, part.height(), 0);
                        base.getWorld().spawnParticle(Particle.DUST, spot, 40, 0.8, 0.8, 0.8, 0,
                                new Particle.DustOptions(tint, 1.6f));
                    }
                    shown.hidden = true;
                    shown.collapseAt = (int) tick + 5;
                    animate(shown.display, transform(shown), 4);
                } else if (!shown.hidden) {
                    shown.hidden = true;
                    Location spot = base.clone().add(0, part.height(), 0);
                    base.getWorld().spawnParticle(Particle.DUST, spot, 30, 0.5, 0.5, 0.5, 0,
                            new Particle.DustOptions(tint, 1.4f));
                    animate(shown.display, transform(shown), 6);
                }
            }
            List<Box> shape = FallbackShape.of(towerId, next, neon, energy);
            for (int i = 0; i < fallbackBoxes.size(); i++) {
                Box box = shape.get(i);
                BlockDisplay display = fallbackBoxes.get(i);
                BlockData data = box.material().createBlockData();
                if (!display.getBlock().equals(data)) {
                    display.setBlock(data);
                }
                display.setBrightness(box.bright() ? FULL_BRIGHT : null);
                display.setTransformation(boxTransform(box));
            }
            if (fallbackSpinner != null) {
                boolean show = next < 2 || spec.spinnerAt() < 14;
                animate(fallbackSpinner, show ? spinnerTransform(spinStep) : hiddenTransform(), 6);
            }
        }

        // ------------------------------------------------------------------ transformations

        private Transformation transform(Shown shown) {
            return transform(shown, 0, 0, 1);
        }

        /** A part's pose now: its motion step, shake offset and an extra scale factor. */
        private Transformation transform(Shown shown, float dx, float dz, float grow) {
            Part part = shown.part;
            if (shown.hidden) {
                return hiddenAt(part.height());
            }
            Quaternionf rotation = new Quaternionf(facing);
            float scale = part.scale() * grow;
            float height = part.height();
            float quarter = (float) (Math.PI / 2);
            switch (part.motion()) {
                case RING -> rotation.rotateY(shown.step * quarter);
                case RING_REVERSE -> rotation.rotateY((float) (Math.PI / 8) - shown.step * quarter);
                case CORE -> {
                    rotation.rotateY(shown.step * quarter);
                    height += shown.step % 2 == 0 ? 0 : 0.15f;
                }
                case HEART -> {
                    rotation.rotateY(shown.step * quarter);
                    scale *= shown.step % 2 == 0 ? 1f : 1.08f;
                }
                case HALO -> rotation.rotateX((float) Math.toRadians(22)).rotateY(shown.step * quarter);
                case HALO_REVERSE -> rotation.rotateZ((float) Math.toRadians(64)).rotateY(-shown.step * quarter);
                case SHIELD -> {
                    float k = shielded ? 1f : 0f;
                    scale *= k;
                    height *= k;
                }
                case NONE, BARRIER -> {
                }
            }
            return new Transformation(new Vector3f(dx, height, dz), rotation, new Vector3f(scale, scale, scale),
                    new Quaternionf());
        }

        /** Falling: sinks, tips over towards the front and shrinks into the rubble. */
        private Transformation collapseTransform(Shown shown) {
            Part part = shown.part;
            Quaternionf rotation = new Quaternionf(facing).rotateX((float) Math.toRadians(-28))
                    .rotateZ((float) Math.toRadians(12));
            float scale = part.scale() * 0.55f;
            float height = Math.max(1.5f, part.height() * 0.35f);
            return new Transformation(new Vector3f(0, height, 0).add(new Vector3f(0, 0, -1.5f).rotate(facing)),
                    rotation, new Vector3f(scale, scale, scale), new Quaternionf());
        }

        private Transformation hiddenAt(float height) {
            return new Transformation(new Vector3f(0, height, 0), new Quaternionf(facing), new Vector3f(),
                    new Quaternionf());
        }

        private Transformation hiddenTransform() {
            return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(), new Quaternionf());
        }

        /** A fallback box: the unit block scaled to the box and turned with the tower about the base. */
        private Transformation boxTransform(Box box) {
            Vector3f min = new Vector3f(box.x0(), box.y0(), box.z0()).rotate(facing);
            Vector3f size = new Vector3f(box.x1() - box.x0(), box.y1() - box.y0(), box.z1() - box.z0());
            return new Transformation(min, new Quaternionf(facing), size, new Quaternionf());
        }

        /** The fallback crystal: a team glass block standing on a corner, turning. */
        private Transformation spinnerTransform(int step) {
            float s = spec.spinnerSize();
            Quaternionf rotation = new Quaternionf().rotateY((float) (step * Math.PI / 2 + Math.PI / 4))
                    .rotateX((float) Math.toRadians(35.26)).rotateZ((float) (Math.PI / 4));
            Vector3f half = new Vector3f(-s / 2, -s / 2, -s / 2).rotate(rotation).add(0, spec.spinnerAt(), 0);
            return new Transformation(half, rotation, new Vector3f(s, s, s), new Quaternionf());
        }

        private Transformation shieldBlockTransform(boolean on) {
            float k = on ? 1f : 0f;
            Vector3f dome = spec.shield();
            Vector3f min = new Vector3f(-dome.x * k, 0, -dome.z * k).rotate(facing);
            return new Transformation(min, new Quaternionf(facing),
                    new Vector3f(2 * dome.x * k, dome.y * k, 2 * dome.z * k), new Quaternionf());
        }

        // ------------------------------------------------------------------ spawning

        private ItemDisplay spawnModel(World world, Location at, Part part) {
            ItemDisplay display = world.spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setVisibleByDefault(false); // only players with the pack are shown it (no missing-model cubes)
                d.setItemStack(part.ref(towerId, 0).item(tint));
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setViewRange(VIEW_RANGE);
                d.setShadowRadius(0);
            });
            models.add(display);
            return display;
        }

        private BlockDisplay spawnBlock(World world, Location at, BlockData data, Transformation transformation) {
            BlockDisplay display = world.spawn(at, BlockDisplay.class, d -> {
                d.setPersistent(false);
                d.setBlock(data);
                d.setTransformation(transformation);
                d.setViewRange(VIEW_RANGE);
                d.setShadowRadius(0);
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (audience.hasPack(player)) {
                        player.hideEntity(plugin, d); // hidden inside the spawn: pack players never see it
                    }
                }
            });
            fallback.add(display);
            return display;
        }

        private static void animate(Display display, Transformation to, int ticks) {
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(ticks);
            display.setTransformation(to);
        }
    }

    /** The stained glass closest to a team colour. */
    static Material glassOf(Color color) {
        return coloured(color, "_STAINED_GLASS", Material.WHITE_STAINED_GLASS);
    }

    /** The dyed block ({@code <DYE><suffix>}) closest to a team colour. */
    static Material coloured(Color color, String suffix, Material fallback) {
        DyeColor best = DyeColor.WHITE;
        double bestDistance = Double.MAX_VALUE;
        for (DyeColor dye : DyeColor.values()) {
            Color c = dye.getColor();
            double d = Math.pow(c.getRed() - color.getRed(), 2) + Math.pow(c.getGreen() - color.getGreen(), 2)
                    + Math.pow(c.getBlue() - color.getBlue(), 2);
            if (d < bestDistance) {
                bestDistance = d;
                best = dye;
            }
        }
        Material block = Material.matchMaterial(best.name() + suffix);
        return block == null ? fallback : block;
    }

    /**
     * One box of the vanilla look, in blocks relative to the base with the front towards -z (AIR = not shown).
     *
     * @param bright full-bright (neon, energy)
     */
    record Box(float x0, float y0, float z0, float x1, float y1, float z1, Material material, boolean bright) {

        static Box of(double x0, double y0, double z0, double x1, double y1, double z1, Material material) {
            return new Box((float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1, material, false);
        }
    }

    /**
     * The vanilla look: a few dozen scaled block displays tracing each tower's silhouette (black concrete hulls, grey
     * plinths, iron masts, smart glass, bright team-coloured concrete neon and stained-glass energy). Every stage has
     * the same number of boxes (one display each); damage swaps materials, shortens and drops boxes.
     */
    static final class FallbackShape {

        private static final Material HULL = Material.BLACK_CONCRETE;
        private static final Material CRACKED = Material.CRACKED_POLISHED_BLACKSTONE_BRICKS;
        private static final Material PLINTH = Material.GRAY_CONCRETE;
        private static final Material METAL = Material.IRON_BLOCK;
        private static final Material HAZARD = Material.YELLOW_CONCRETE;
        private static final Material WINDOW = Material.GRAY_STAINED_GLASS;
        private static final Material DEAD = Material.GRAY_CONCRETE;
        private static final Material SCRAP = Material.BLACKSTONE;
        private static final Material AMBER = Material.ORANGE_CONCRETE;
        private static final Material NONE = Material.AIR;

        private FallbackShape() {
        }

        static List<Box> of(String towerId, int stage, Material neon, Material energy) {
            List<Box> out = new ArrayList<>();
            Material hull = stage >= 2 ? CRACKED : HULL;
            Material lit = stage >= 2 ? DEAD : neon;
            switch (towerId) {
                case "front" -> front(out, stage, hull, neon, lit, energy);
                case "middle" -> middle(out, stage, hull, neon, energy);
                default -> core(out, stage, hull, neon, lit);
            }
            // scrap piles once heavily damaged
            out.add(Box.of(-5.8, 0, -4.4, -4.4, 0.6, -3.2, stage >= 2 ? SCRAP : NONE));
            out.add(Box.of(4.2, 0, 2.8, 5.6, 0.5, 4.0, stage >= 2 ? SCRAP : NONE));
            out.add(Box.of(-1.0, 0, -5.6, 0.6, 0.45, -4.6, stage >= RUIN ? SCRAP : NONE));
            out.replaceAll(b -> b.material() == neon || b.material() == energy || b.material() == AMBER
                    ? new Box(b.x0(), b.y0(), b.z0(), b.x1(), b.y1(), b.z1(), b.material(), true) : b);
            return out;
        }

        private static Material keep(boolean keep, Material material) {
            return keep ? material : NONE;
        }

        private static void front(List<Box> out, int stage, Material hull, Material neon, Material lit,
                                  Material energy) {
            boolean ruin = stage >= RUIN;
            double topL = stage < 2 ? 10 : ruin ? 3 : 7.25;
            double topR = ruin ? 5 : 10;
            out.add(Box.of(-5, 0, -2.75, 5, 0.5, 2.75, PLINTH));
            out.add(Box.of(-4.25, 0.5, -1.75, -2.5, topL, 1.75, hull));
            out.add(Box.of(2.5, 0.5, -1.75, 4.25, topR, 1.75, hull));
            out.add(Box.of(-4.75, 0.5, -2.25, -2.375, ruin ? 2.25 : 3.5, 2.25, hull));
            out.add(Box.of(2.375, 0.5, -2.25, 4.75, ruin ? 3 : 3.5, 2.25, hull));
            out.add(Box.of(-4.75, 0.5, -2.3125, -2.375, 3.5, -2.25, keep(!ruin, HAZARD)));
            out.add(Box.of(2.375, 0.5, -2.3125, 4.75, 3.5, -2.25, keep(!ruin, HAZARD)));
            if (stage < 2) {
                out.add(Box.of(-2.5, 8, -1.5, 2.5, 10, 1.5, hull));
            } else if (!ruin) {
                out.add(Box.of(-0.75, 8, -1.5, 2.5, 10, 1.5, hull));
            } else {
                out.add(Box.of(-2.5, 0.5, -3, 2.5, 1.75, -1.25, hull));
            }
            out.add(Box.of(-3.875, 4.25, -1.875, -2.875, topL - 0.5, -1.75, keep(!ruin, stage >= 1 ? lit : neon)));
            out.add(Box.of(2.875, 4.25, -1.875, 3.875, 9.5, -1.75, keep(!ruin, lit)));
            out.add(Box.of(-2.25, 0.875, -0.0625, 2.25, stage >= 2 ? 3.25 : 7.625, 0.0625, keep(!ruin, energy)));
            out.add(Box.of(-2.25, 8.25, -1.8125, 2.25, 9.75, -1.6875, keep(stage < 2, energy)));
            out.add(Box.of(-4.5, 10, -2, -2.25, 10.5, 2, keep(stage < 2, PLINTH)));
            out.add(Box.of(2.25, 10, -2, 4.5, 10.5, 2, keep(!ruin, PLINTH)));
            out.add(Box.of(-3.5, 10.5, 0.75, -3.25, 11.75, 1, keep(stage < 2, METAL)));
            out.add(Box.of(3.25, 10.5, 0.75, 3.5, 11.75, 1, keep(!ruin, METAL)));
            out.add(Box.of(-3.5625, 11.75, 0.6875, -3.1875, 12, 1.0625, keep(stage < 2, AMBER)));
            out.add(Box.of(3.1875, 11.75, 0.6875, 3.5625, 12, 1.0625, keep(!ruin, AMBER)));
            out.add(Box.of(-2.25, 0.5, -0.125, 2.25, 0.875, 0.125, keep(!ruin, lit)));
        }

        private static void middle(List<Box> out, int stage, Material hull, Material neon, Material energy) {
            boolean ruin = stage >= RUIN;
            double[] stubs = {2.0, 4.0, 1.25, 3.0};
            int[][] corners = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
            out.add(Box.of(-3.75, 0, -3.75, 3.75, 0.5, 3.75, PLINTH));
            for (int n = 0; n < 4; n++) {
                int sx = corners[n][0];
                int sz = corners[n][1];
                double x0 = Math.min(sx * 1.25, sx * 3.25);
                double x1 = Math.max(sx * 1.25, sx * 3.25);
                double z0 = Math.min(sz * 1.25, sz * 3.25);
                double z1 = Math.max(sz * 1.25, sz * 3.25);
                double top = ruin ? 0.5 + stubs[n] : stage == 2 && n == 1 ? 7 : 10;
                out.add(Box.of(x0, 0.5, z0, x1, top, z1, hull));
                // the rack front on the outer z face (team-coloured LEDs)
                double fz = sz < 0 ? z0 - 0.125 : z1;
                boolean alive = stage < 2 || n == 0 || n == 3;
                out.add(Box.of(x0 + 0.25, 1, fz, x1 - 0.25, top - 0.5, fz + 0.125,
                        keep(!ruin, alive ? energy : SCRAP)));
            }
            out.add(Box.of(-1, 0.5, -1, 1, ruin ? 2.5 : 10, 1, ruin ? SCRAP : energy));
            out.add(Box.of(-3.5, 10, stage < 2 ? -3.5 : -0.5, 3.5, 11, 3.5, keep(!ruin, hull)));
            out.add(Box.of(-3.625, 10.375, -3.625, 3.625, 10.625, 3.625, keep(stage < 2, neon)));
            out.add(Box.of(-2.25, 11, stage < 2 ? -2.25 : -0.5, 2.25, stage < 2 ? 13.5 : 12.75, 2.25,
                    keep(!ruin, METAL)));
            out.add(Box.of(-3, 13.5, -3, 3, 14, 3, keep(stage < 2, PLINTH)));
            out.add(Box.of(-3.125, 13.625, -3.125, 3.125, 13.875, 3.125, keep(stage < 2, neon)));
            for (int[] c : corners) {
                double x0 = Math.min(c[0] * 1.75, c[0] * 2.0);
                double z0 = Math.min(c[1] * 1.75, c[1] * 2.0);
                out.add(Box.of(x0, 14, z0, x0 + 0.25, 16.5, z0 + 0.25, keep(stage < 2, METAL)));
            }
            out.add(Box.of(-2.25, 16.5, -2.25, 2.25, 16.875, 2.25, keep(stage < 2, hull)));
            out.add(Box.of(-0.1875, 16.875, -0.1875, 0.1875, 18.25, 0.1875, keep(stage < 2, METAL)));
            out.add(Box.of(-0.25, 18.25, -0.25, 0.25, 18.625, 0.25, keep(stage < 2, AMBER)));
            // the holographic data ring (four plates)
            for (int k = 0; k < 4; k++) {
                Vector3f a = new Vector3f(-3.5f, 6.0f, -4.5625f);
                Vector3f b = new Vector3f(3.5f, 6.25f, -4.4375f);
                for (int t = 0; t < k; t++) {
                    a.set(-a.z, a.y, a.x);
                    b.set(-b.z, b.y, b.x);
                }
                out.add(Box.of(Math.min(a.x, b.x), 6.0, Math.min(a.z, b.z), Math.max(a.x, b.x), 7.0,
                        Math.max(a.z, b.z), keep(!ruin, stage >= 1 && k == 1 ? NONE : energy)));
            }
        }

        private static void core(List<Box> out, int stage, Material hull, Material neon, Material lit) {
            boolean ruin = stage >= RUIN;
            out.add(Box.of(-4.5, 0, -4.5, 4.5, 1, 4.5, PLINTH));
            out.add(Box.of(-4.5625, 0.125, -4.5625, 4.5625, 0.375, 4.5625, keep(!ruin, HAZARD)));
            out.add(Box.of(-3.75, 1, -3.75, 3.75, ruin ? 3.5 : 4.5, 3.75, hull));
            out.add(Box.of(-3.8125, 2, -3.8125, 3.8125, 3.75, 3.8125, keep(!ruin, WINDOW)));
            out.add(Box.of(-3.875, 4.5, -3.875, 3.875, 4.875, 3.875, keep(!ruin, PLINTH)));
            out.add(Box.of(-3, 4.875, -3, 3, 8, 3, keep(!ruin, hull)));
            // light conduits: boxes through each tier, showing as strips on two opposite faces
            for (double[] tier : new double[][] {{1, 4.5, 3.875}, {4.875, 8, 3.125}}) {
                for (double x : new double[] {-1.625, 1.375}) {
                    Material conduit = keep(!ruin, tier[0] > 2 ? (stage >= 1 ? lit : neon) : lit);
                    out.add(Box.of(x, tier[0], -tier[2], x + 0.25, tier[1], tier[2], conduit));
                    out.add(Box.of(-tier[2], tier[0], x, tier[2], tier[1], x + 0.25, conduit));
                }
            }
            out.add(Box.of(-3.25, 8, -3.25, 3.25, 8.5, 3.25, keep(!ruin, PLINTH)));
            out.add(Box.of(-1.75, 8.5, -1.75, 1.75, 8.75, 1.75, keep(!ruin, neon)));
            int[][] corners = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
            for (int n = 0; n < 4; n++) {
                double x0 = Math.min(corners[n][0] * 2.0, corners[n][0] * 3.0);
                double z0 = Math.min(corners[n][1] * 2.0, corners[n][1] * 3.0);
                double top = stage == 2 && n == 1 ? 10 : 13.5;
                out.add(Box.of(x0, 8.5, z0, x0 + 1, top, z0 + 1, keep(!ruin, hull)));
            }
            out.add(Box.of(-3.25, 13.5, stage < 2 ? -3.25 : -0.5, 3.25, 14.5, 3.25, keep(!ruin, hull)));
            out.add(Box.of(-3.375, 13.875, -3.375, 3.375, 14.125, 3.375, keep(stage < 2, neon)));
            out.add(Box.of(-2, 14.5, stage < 2 ? -2 : -0.25, 2, stage < 2 ? 15.75 : 15.5, 2, keep(!ruin, hull)));
            out.add(Box.of(-1.25, 15.75, -1.25, 1.25, 17.25, 1.25, keep(stage < 2, hull)));
            out.add(Box.of(-0.125, 15.75, -1.375, 0.125, 17.25, 1.375, keep(stage < 2, neon)));
            out.add(Box.of(-0.25, 17.25, -0.25, 0.25, 18.5, 0.25, keep(stage < 2, METAL)));
            out.add(Box.of(-0.375, 18.5, -0.375, 0.375, 19, 0.375, keep(stage < 2, neon)));
            out.add(Box.of(-3.375, 14.5, -3.375, -1.875, 14.625, -1.875, keep(stage < 2, METAL)));
            out.add(Box.of(1.875, 14.5, 1.875, 3.375, 14.625, 3.375, keep(!ruin, METAL)));
        }
    }
}
