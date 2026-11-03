package io.github.sx2xoox.mcpl.game.siege.tower;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * The 3D model look of the 공성전 v2 buildings (toy kingdom, docs/siege-v2.md §7): the lane towers are 장난감 성 망루
 * (look id {@code tower}) and the core is 왕관 쓴 오르골 성 (look id {@code core}), drawn with models of the MCPL
 * resource pack made in {@code tools/assetgen/models/toys.py}. Any tower id containing {@code core} gets the core look,
 * every other id (outer / inner lane towers, the v1 {@code front} / {@code middle}) the watchtower ({@link #lookOf}).
 *
 * <p>A building is several item displays with an {@code item_model} (a model only spans 3 blocks at scale 1): body
 * slabs per damage stage ({@code structure/<look>_body|top_<stage>}: intact, cracked with a sticking plaster, heavily
 * damaged with the roofs knocked off, ruin), moving parts (the watchtower's pennant sways, the core's crown turns like
 * a music box dancer and its wind-up key turns) and a soap-bubble shield ({@code structure/<look>_shield}, iridescent
 * through the pack's item shader). Roofs, bands, the lid, the pennant and the crown's gems take the team colour
 * ({@code dyed_color}).
 *
 * <p>Players who have not loaded the pack ({@link PackAudience#hasPack}) would see missing-model cubes, so the model
 * entities are hidden by default and shown only to players with the pack, while a coarse vanilla look of scaled block
 * displays (terracotta walls, purpur plinths, team concrete roofs, a cherry-wood music box with a spinning gold crown) is
 * shown to everyone else (and hidden from players with the pack). A player's view swaps whenever their pack status
 * changes. Every entity is tracked and removed by {@link Visual#remove()}.
 *
 * <p>The building's front (its door) faces the yaw of the {@code base} location (yaw 90 = towards -x).
 */
public final class ModelTowerLook implements TowerLook {

    /** Damage stage of the ruin (after {@link Visual#destroyed()}). */
    public static final int RUIN = 3;

    /** How a part moves. */
    enum Motion {
        /** Still (body slabs). */
        NONE(0),
        /** The watchtower's pennant: sways about its pole. */
        FLAG(16),
        /** The core's crown: turns slowly about the vertical axis, like a music box's dancer. */
        CROWN(40),
        /** The music box's wind-up key: turns about its shaft (the model's x axis). */
        KEY(20),
        /** The soap bubble: rises from the ground when raised and breathes while it stands. */
        SHIELD(30);

        final int period;

        Motion(int period) {
            this.period = period;
        }
    }

    /**
     * One display of a building (kept in sync with {@code PARTS} in tools/assetgen/models/toys.py).
     *
     * @param model  part name: {@code structure/<look>_<model>_<stage>} when staged, else {@code structure/<look>_<model>}
     * @param stages number of stage models (0 = one model); the part collapses when the building falls past them
     * @param until  the part shows while the stage is below this (hidden at higher stages, gone for the ruin)
     * @param height model centre above the ground, blocks
     * @param scale  display scale
     * @param motion how it moves
     */
    record Part(String model, int stages, int until, float height, float scale, Motion motion) {

        ModelRef ref(String look, int stage) {
            return stages > 0
                    ? new ModelRef("structure/" + look + "_" + model + "_" + Math.min(stage, stages - 1))
                    : new ModelRef("structure/" + look + "_" + model);
        }
    }

    /**
     * A building: its displays, the fallback's size, and the hitbox it needs.
     *
     * @param parts        the model displays
     * @param shield       half width (x), height and half depth (z) of the shield dome, blocks
     * @param spinnerAt    height of the fallback's spinning gold crown, blocks
     * @param spinnerSize  its size, blocks (0 = none)
     * @param hitbox       recommended Mannequin scale at the default {@code hitbox-scale} 2.5
     */
    record Spec(List<Part> parts, Vector3f shield, float spinnerAt, float spinnerSize, double hitbox) {
    }

    private static final float BODY = 4f;
    private static final float SHIELD_SCALE = 8f;
    private static final float SHIELD_CENTRE = 11f;
    private static final double DEFAULT_HITBOX = 2.5;
    /** Look ids. */
    public static final String TOWER = "tower";
    public static final String CORE = "core";

    static final Map<String, Spec> SPECS = Map.of(
            TOWER, new Spec(List.of(
                    new Part("body", 4, 4, 6, BODY, Motion.NONE),
                    new Part("top", 3, 3, 18, BODY, Motion.NONE),
                    new Part("flag", 0, 2, 16.35f, 2, Motion.FLAG),
                    new Part("shield", 0, 3, SHIELD_CENTRE, SHIELD_SCALE, Motion.SHIELD)),
                    new Vector3f(4.4f, 18f, 4.4f), 0, 0, 6),
            CORE, new Spec(List.of(
                    new Part("body", 4, 4, 6, BODY, Motion.NONE),
                    new Part("top", 3, 3, 18, BODY, Motion.NONE),
                    new Part("crown", 0, 3, 12f, 2, Motion.CROWN),
                    new Part("key", 0, 3, 2f, BODY, Motion.KEY),
                    new Part("shield", 0, 3, SHIELD_CENTRE, SHIELD_SCALE, Motion.SHIELD)),
                    new Vector3f(5.75f, 14.75f, 5.75f), 11.9f, 1.6f, 7));

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

    /** The look of a tower id: {@link #CORE} for any id containing {@code core}, else {@link #TOWER}. */
    public static String lookOf(String towerId) {
        return towerId.toLowerCase(Locale.ROOT).contains(CORE) ? CORE : TOWER;
    }

    /** Every model this look uses (all parts of both buildings, every stage). */
    public static Set<ModelRef> models() {
        Set<ModelRef> out = new LinkedHashSet<>();
        for (String look : List.of(TOWER, CORE)) {
            for (Part part : SPECS.get(look).parts()) {
                for (int stage = 0; stage < Math.max(1, part.stages()); stage++) {
                    out.add(part.ref(look, stage));
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
        return configured * SPECS.get(lookOf(towerId)).hitbox() / DEFAULT_HITBOX;
    }

    @Override
    public ModelVisual spawn(Location base, String towerId, TextColor teamColor) {
        String look = lookOf(towerId);
        return new ModelVisual(base.clone(), look, SPECS.get(look), Color.fromRGB(teamColor.value()));
    }

    /** One building: its model entities, its vanilla fallback, and the per-player choice between them. */
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
        private final String look;
        private final Spec spec;
        private final Color tint;
        private final Quaternionf facing;
        private final Material roof;
        private final Material cloth;
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

        ModelVisual(Location base, String look, Spec spec, Color tint) {
            this.base = base;
            this.look = look;
            this.spec = spec;
            this.tint = tint;
            // models face north (-Z); turn the front towards the base's yaw (yaw 0 = south)
            this.facing = new Quaternionf().rotationY((float) Math.PI - (float) Math.toRadians(base.getYaw()));
            this.roof = coloured(tint, "_CONCRETE", Material.WHITE_CONCRETE);
            this.cloth = coloured(tint, "_WOOL", Material.WHITE_WOOL);
            World world = base.getWorld();
            Location at = base.clone();
            at.setYaw(0);
            at.setPitch(0);

            for (Part part : spec.parts()) {
                Shown shown = new Shown(part, spawnModel(world, at, part));
                parts.add(shown);
                animate(shown.display, transform(shown), 1);
            }

            for (Box box : FallbackShape.of(look, 0, roof, cloth)) {
                BlockDisplay display = spawnBlock(world, at, box.material().createBlockData(), boxTransform(box));
                display.setBrightness(box.bright() ? FULL_BRIGHT : null);
                fallbackBoxes.add(display);
            }
            if (spec.spinnerSize() > 0) {
                fallbackSpinner = spawnBlock(world, at, Material.GOLD_BLOCK.createBlockData(), spinnerTransform(0));
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
            Location middle = base.clone().add(0, 3, 0);
            if (!shielded) {
                // the bubble pops
                base.getWorld().spawnParticle(Particle.BUBBLE_POP, middle, 60, spec.shield().x * 0.7, 3,
                        spec.shield().z * 0.7, 0.05);
                base.getWorld().playSound(middle, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 2f, 0.8f);
            }
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
                if (shown.part.motion() == Motion.SHIELD && shielded && !shown.hidden) {
                    // the bubble wobbles where it was poked
                    animate(shown.display, transform(shown, 0, 0, 1.05f), 2);
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
            world.spawnParticle(Particle.BLOCK, middle, 120, 2.2, 3, 2.2, 0.2,
                    Material.WHITE_TERRACOTTA.createBlockData());
            world.spawnParticle(Particle.BLOCK, middle, 60, 2.2, 3, 2.2, 0.2, roof.createBlockData());
            world.spawnParticle(Particle.POOF, middle, 40, 2, 2.5, 2, 0.05);
            world.playSound(middle, Sound.ENTITY_GENERIC_EXPLODE, 2f, 1.3f);
            world.playSound(middle, Sound.BLOCK_WOOD_BREAK, 2f, 0.8f);
            if (spec.spinnerSize() > 0) {
                // the music box plays its last notes
                Location spot = base.clone().add(0, spec.spinnerAt(), 0);
                world.spawnParticle(Particle.NOTE, spot, 12, 1.5, 1, 1.5, 1);
                world.playSound(spot, Sound.BLOCK_NOTE_BLOCK_CHIME, 2f, 0.6f);
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

        String look() {
            return look;
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
                out.add(shown.hidden ? null : shown.part.ref(look, stage));
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
                boolean still = shown.part.motion() == Motion.SHIELD && !shielded;
                if (period > 0 && !shown.hidden && !still && tick % period == 1) {
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

        /** A heavily damaged building puffs dust, a ruin smokes softly. */
        private void ambience() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            World world = base.getWorld();
            if (stage == 2 && tick % 12 == 0) {
                Location spot = base.clone().add(random.nextDouble(-2.5, 2.5), random.nextDouble(2, 9),
                        random.nextDouble(-2.5, 2.5));
                world.spawnParticle(Particle.POOF, spot, 3, 0.3, 0.3, 0.3, 0.02);
            } else if (stage == RUIN && tick % 6 == 0) {
                Location spot = base.clone().add(random.nextDouble(-2, 2), 1.5, random.nextDouble(-2, 2));
                world.spawnParticle(Particle.WHITE_SMOKE, spot, 1, 0.2, 0.1, 0.2, 0.01);
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
                    shown.display.setItemStack(part.ref(look, next).item(tint));
                    if (shown.hidden) {
                        shown.hidden = false;
                        animate(shown.display, transform(shown), 6);
                    }
                } else if (next == RUIN && part.stages() > 0) {
                    // the upper slab comes down with the building
                    shown.collapseAt = (int) tick + COLLAPSE_TICKS;
                    animate(shown.display, collapseTransform(shown), COLLAPSE_TICKS);
                } else if (next == RUIN) {
                    // the bubble pops, the crown and the key tumble off
                    if (part.motion() != Motion.SHIELD && !shown.hidden) {
                        Location spot = base.clone().add(0, part.height(), 0);
                        base.getWorld().spawnParticle(Particle.DUST, spot, 30, 0.8, 0.8, 0.8, 0,
                                new Particle.DustOptions(tint, 1.4f));
                    }
                    shown.hidden = true;
                    shown.collapseAt = (int) tick + 5;
                    animate(shown.display, transform(shown), 4);
                } else if (!shown.hidden) {
                    shown.hidden = true;
                    Location spot = base.clone().add(0, part.height(), 0);
                    base.getWorld().spawnParticle(Particle.DUST, spot, 20, 0.5, 0.5, 0.5, 0,
                            new Particle.DustOptions(tint, 1.2f));
                    animate(shown.display, transform(shown), 6);
                }
            }
            List<Box> shape = FallbackShape.of(look, next, roof, cloth);
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
                case FLAG -> rotation.rotateY((float) Math.toRadians(shown.step % 2 == 0 ? 14 : -14));
                case CROWN -> rotation.rotateY(shown.step * quarter);
                case KEY -> rotation.rotateX(shown.step * quarter);
                case SHIELD -> {
                    float k = shielded ? 1f : 0f;
                    scale *= k * (shown.step % 2 == 0 ? 1f : 1.02f);
                    height *= k;
                }
                case NONE -> {
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

        /** A fallback box: the unit block scaled to the box and turned with the building about the base. */
        private Transformation boxTransform(Box box) {
            Vector3f min = new Vector3f(box.x0(), box.y0(), box.z0()).rotate(facing);
            Vector3f size = new Vector3f(box.x1() - box.x0(), box.y1() - box.y0(), box.z1() - box.z0());
            return new Transformation(min, new Quaternionf(facing), size, new Quaternionf());
        }

        /** The fallback crown: a gold block on the keep, turning. */
        private Transformation spinnerTransform(int step) {
            float s = spec.spinnerSize();
            Quaternionf rotation = new Quaternionf().rotateY((float) (step * Math.PI / 2 + Math.PI / 4));
            Vector3f corner = new Vector3f(-s / 2, 0, -s / 2).rotate(rotation).add(0, spec.spinnerAt() - s / 2, 0);
            return new Transformation(corner, rotation, new Vector3f(s, s * 0.6f, s), new Quaternionf());
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
                d.setItemStack(part.ref(look, 0).item(tint));
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
     * @param bright full-bright
     */
    record Box(float x0, float y0, float z0, float x1, float y1, float z1, Material material, boolean bright) {

        static Box of(double x0, double y0, double z0, double x1, double y1, double z1, Material material) {
            return new Box((float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1, material, false);
        }
    }

    /**
     * The vanilla look, in the toy style: a few dozen scaled block displays tracing each building's silhouette (white
     * terracotta walls, purpur plinths and cornices, team-coloured concrete roofs in steps, light-blue windows, an oak
     * door; the core a cherry-wood music box with gold feet and trim under a team lid). Every stage has the same number
     * of boxes (one display each); damage knocks roofs off and drops boxes, the ruin leaves a stub and toy blocks.
     */
    static final class FallbackShape {

        private static final Material WALL = Material.WHITE_TERRACOTTA;
        private static final Material PLINTH = Material.PURPUR_BLOCK;
        private static final Material WINDOW = Material.LIGHT_BLUE_CONCRETE;
        private static final Material DOOR = Material.OAK_PLANKS;
        private static final Material GOLD = Material.GOLD_BLOCK;
        private static final Material BOX = Material.CHERRY_PLANKS;
        private static final Material NONE = Material.AIR;
        private static final Material[] TOY_BLOCKS = {Material.PINK_CONCRETE, Material.LIGHT_BLUE_CONCRETE,
                Material.YELLOW_CONCRETE, Material.LIME_CONCRETE};
        /** Watchtower roof tiers: half width, height (tools/assetgen/models/toys.py TOWER_ROOF). */
        private static final double[][] TOWER_ROOF = {{2.75, 0.95}, {2.3, 0.95}, {1.85, 0.975}, {1.4, 0.85},
                {0.95, 0.8}, {0.55, 0.7}};
        private static final int[][] CORNERS = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};

        private FallbackShape() {
        }

        static List<Box> of(String look, int stage, Material roof, Material cloth) {
            List<Box> out = new ArrayList<>();
            if (CORE.equals(look)) {
                core(out, stage, roof);
            } else {
                tower(out, stage, roof, cloth);
            }
            // building blocks scattered once heavily damaged
            double[][] spots = {{-3.9, -2.6, 0.7}, {3.2, 1.9, 0.6}, {-1.0, -4.0, 0.55}, {2.6, -3.6, 0.8}};
            for (int n = 0; n < spots.length; n++) {
                double[] s = spots[n];
                boolean shown = stage >= RUIN || stage >= 2 && n < 2;
                out.add(Box.of(s[0], 0, s[1], s[0] + s[2], s[2], s[1] + s[2], shown ? TOY_BLOCKS[n] : NONE));
            }
            return out;
        }

        private static Material keep(boolean keep, Material material) {
            return keep ? material : NONE;
        }

        private static void tower(List<Box> out, int stage, Material roof, Material cloth) {
            boolean ruin = stage >= RUIN;
            out.add(Box.of(-2.75, 0, -2.75, 2.75, 0.75, 2.75, PLINTH));
            out.add(Box.of(-2, 0.75, -2, 2, ruin ? 2.5 : 8.5, 2, WALL));
            out.add(Box.of(0.5, 2.5, -0.5, 2, 4.25, 2, keep(ruin, WALL)));
            out.add(Box.of(-2.0625, 3, -2.0625, 2.0625, 3.375, 2.0625, keep(!ruin, roof)));
            out.add(Box.of(-0.6, 0.75, -2.0625, 0.6, ruin ? 1.95 : 2.55, -2, DOOR));
            out.add(Box.of(-1.25, 4.9, -2.0625, -0.45, 5.9, -2, keep(!ruin, WINDOW)));
            out.add(Box.of(0.45, 4.9, -2.0625, 1.25, 5.9, -2, keep(!ruin, WINDOW)));
            out.add(Box.of(-2.5, 8.5, -2.5, 2.5, 9.125, 2.5, keep(!ruin, PLINTH)));
            double y = 9.125;
            for (int n = 0; n < TOWER_ROOF.length; n++) {
                double half = TOWER_ROOF[n][0];
                double h = TOWER_ROOF[n][1];
                out.add(Box.of(-half, y, -half, half, y + h, half, keep(!ruin && (stage < 2 || n < 3), roof)));
                y += h;
            }
            out.add(Box.of(-0.3, y, -0.3, 0.3, y + 0.6, 0.3, keep(stage < 2, GOLD)));
            out.add(Box.of(-0.0625, y + 0.6, -0.0625, 0.0625, 17.2, 0.0625, keep(stage < 2, Material.WHITE_CONCRETE)));
            out.add(Box.of(0.0625, 15.9, -0.03, 1.5, 16.8, 0.03, keep(stage < 2, cloth)));
            // the fallen roof beside the ruin
            out.add(Box.of(2.2, 0, -2.2, 3.8, 0.6, -0.6, keep(ruin, roof)));
        }

        private static void core(List<Box> out, int stage, Material roof) {
            boolean ruin = stage >= RUIN;
            for (int[] c : CORNERS) {
                out.add(Box.of(c[0] * 3 - 0.45, 0, c[1] * 3 - 0.45, c[0] * 3 + 0.45, 0.55, c[1] * 3 + 0.45, GOLD));
            }
            out.add(Box.of(-3.5, 0.5, -3.5, 3.5, 3.25, 3.5, BOX));
            out.add(Box.of(-3.5625, 2.75, -3.5625, 3.5625, 3.0, 3.5625, GOLD));
            out.add(Box.of(-3.75, 3.25, -3.75, 3.75, ruin ? 3.5 : 3.75, ruin ? 2.5 : 3.75, roof));
            out.add(Box.of(-4.4, 1.85, -0.15, -3.5, 2.15, 0.15, GOLD));
            out.add(Box.of(-4.7, 1.55, -0.9, -4.45, 2.45, 0.9, keep(!ruin, GOLD)));
            out.add(Box.of(-1.75, 3.75, -1.75, 1.75, ruin ? 4.8 : 10, 1.75, WALL));
            out.add(Box.of(-0.55, 3.75, -1.8125, 0.55, ruin ? 4.6 : 5.35, -1.75, DOOR));
            out.add(Box.of(-0.55, 6.8, -1.8125, 0.55, 8.0, -1.75, keep(!ruin, WINDOW)));
            out.add(Box.of(-2.05, 10, -2.05, 2.05, 10.5, 2.05, keep(!ruin, PLINTH)));
            for (int n = 0; n < CORNERS.length; n++) {
                double cx = CORNERS[n][0] * 2.75;
                double cz = CORNERS[n][1] * 2.75;
                boolean fallen = stage >= 2 && (n == 1 || n == 2);
                double top = fallen ? 8 : 9.5;
                out.add(Box.of(cx - 0.75, 3.75, cz - 0.75, cx + 0.75, ruin ? (n == 3 ? 4.2 : 3.75) : top, cz + 0.75,
                        keep(!ruin || n == 3, WALL)));
                boolean roofed = !ruin && !fallen;
                out.add(Box.of(cx - 0.9, 9.5, cz - 0.9, cx + 0.9, 9.8, cz + 0.9, keep(roofed, PLINTH)));
                out.add(Box.of(cx - 1.15, 9.8, cz - 1.15, cx + 1.15, 10.55, cz + 1.15, keep(roofed, roof)));
                out.add(Box.of(cx - 0.75, 10.55, cz - 0.75, cx + 0.75, 11.6, cz + 0.75, keep(roofed, roof)));
                out.add(Box.of(cx - 0.3, 11.6, cz - 0.3, cx + 0.3, 12.5, cz + 0.3, keep(roofed, roof)));
            }
        }
    }
}
