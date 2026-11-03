package io.github.sx2xoox.mcpl.game.siege.tower;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The 3D model look of the 공성전 towers, drawn with models of the MCPL resource pack (made in
 * {@code tools/assetgen/models/towers.py}): a body per damage stage ({@code tower_<id>_<stage>}: intact, cracked,
 * heavily damaged, ruin), a floating, slowly turning crystal ({@code tower_crystal}) and a shield bubble
 * ({@code tower_shield}), all item displays with an {@code item_model}; banners, rune slits and the crystal are tinted
 * with the team colour ({@code dyed_color}).
 *
 * <p>Players who have not loaded the pack ({@link PackAudience#hasPack}) would see missing-model cubes, so the model
 * entities are hidden by default and shown only to players with the pack, while a vanilla look of block displays is
 * shown to everyone else (and hidden from players with the pack). A player's view swaps whenever their pack status
 * changes. Every entity is tracked and removed by {@link Visual#remove()}.
 *
 * <p>The tower's gate faces the yaw of the {@code base} location.
 */
public final class ModelTowerLook implements TowerLook {

    /** Damage stage of the ruin (after {@link Visual#destroyed()}). */
    public static final int RUIN = 3;
    /** Body models are made at half size (24 model units = 3 blocks footprint). */
    static final float BODY_SCALE = 2f;

    /**
     * Placement of one tower's parts (kept in sync with {@code PARTS} in tools/assetgen/models/towers.py).
     *
     * @param crystalHeight  crystal centre above the ground, blocks
     * @param crystalScale   crystal model scale
     * @param shieldScale    shield model scale
     * @param fallbackHeight height of the vanilla fallback's walls, blocks
     */
    record Spec(double crystalHeight, float crystalScale, float shieldScale, int fallbackHeight) {
    }

    static final Map<String, Spec> SPECS = Map.of(
            "front", new Spec(46 / 8.0, 1.15f, 2.35f, 4),
            "middle", new Spec(37.5 / 8.0, 0.75f, 2.15f, 4),
            "core", new Spec(27 / 8.0, 1.6f, 2.2f, 1));

    private static final int SPIN_TICKS = 40;
    private static final int FLASH_TICKS = 4;
    private static final int SHAKE_TICKS = 3;
    private static final int SHIELD_POP_TICKS = 8;
    private static final float VIEW_RANGE = 3f;

    private final Plugin plugin;
    private final PackAudience audience;

    public ModelTowerLook(Plugin plugin, PackAudience audience) {
        this.plugin = plugin;
        this.audience = audience;
    }

    /** The body model of a tower at a damage stage. */
    public static ModelRef body(String towerId, int stage) {
        return new ModelRef("tower_" + towerId + "_" + stage);
    }

    public static final ModelRef CRYSTAL = new ModelRef("tower_crystal");
    public static final ModelRef SHIELD = new ModelRef("tower_shield");

    /** Damage stage for the health left: 0 intact (over 2/3), 1 cracked, 2 heavily damaged (1/3 or less). */
    public static int stage(double health) {
        if (health > 2 / 3.0) {
            return 0;
        }
        return health > 1 / 3.0 ? 1 : 2;
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

        private final Location base;
        private final String towerId;
        private final Spec spec;
        private final Color tint;
        private final float yawRadians;
        private final List<Display> models = new ArrayList<>();
        private final List<Display> fallback = new ArrayList<>();
        private final Map<Vector3f, BlockDisplay> fallbackBlocks = new LinkedHashMap<>();
        private final ItemDisplay body;
        private @Nullable ItemDisplay crystal;
        private final ItemDisplay shield;
        private @Nullable BlockDisplay fallbackCrystal;
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
            this.yawRadians = (float) Math.toRadians(base.getYaw());
            World world = base.getWorld();
            Location at = base.clone();
            at.setYaw(0);
            at.setPitch(0);

            body = model(world, at, ModelTowerLook.body(towerId, 0).item(tint), bodyTransform(0, 0));
            crystal = model(world, at, CRYSTAL.item(tint), crystalTransform());
            shield = model(world, at, SHIELD.item(tint), shieldTransform(false));

            for (Map.Entry<Vector3f, Material> block : FallbackShape.of(towerId, spec.fallbackHeight(), 0).entrySet()) {
                Vector3f offset = block.getKey();
                fallbackBlocks.put(offset, block(world, at.clone().add(offset.x - 0.5, offset.y, offset.z - 0.5),
                        block.getValue().createBlockData(), new Transformation(new Vector3f(), new Quaternionf(),
                                new Vector3f(1, 1, 1), new Quaternionf())));
            }
            fallbackCrystal = block(world, at.clone().add(0, spec.crystalHeight(), 0),
                    glassOf(tint).createBlockData(), crystalBlockTransform(0));
            fallbackCrystal.setGlowing(true);
            fallbackCrystal.setGlowColorOverride(tint);
            fallbackShield = block(world, at, Material.LIGHT_BLUE_STAINED_GLASS.createBlockData(),
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
            animate(shield, shieldTransform(shielded), SHIELD_POP_TICKS);
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
        }

        @Override
        public void destroyed() {
            if (removed || stage == RUIN) {
                return;
            }
            shielded(false);
            setStage(RUIN);
            animate(body, bodyTransform(0, 0), 1);
            World world = base.getWorld();
            Location middle = base.clone().add(0, 2, 0);
            world.spawnParticle(Particle.BLOCK, middle, 120, 1.2, 1.6, 1.2, 0.2,
                    Material.STONE_BRICKS.createBlockData());
            world.spawnParticle(Particle.EXPLOSION, middle, 3, 0.8, 1, 0.8, 0);
            world.playSound(middle, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.7f);
            if (crystal != null) {
                Location spot = base.clone().add(0, spec.crystalHeight(), 0);
                world.spawnParticle(Particle.DUST, spot, 60, 0.6, 0.8, 0.6, 0, new Particle.DustOptions(tint, 1.6f));
                world.playSound(spot, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 2f, 0.6f);
                models.remove(crystal);
                crystal.remove();
                crystal = null;
            }
            if (fallbackCrystal != null) {
                fallback.remove(fallbackCrystal);
                fallbackCrystal.remove();
                fallbackCrystal = null;
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
            fallbackBlocks.clear();
            crystal = null;
            fallbackCrystal = null;
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

        ItemDisplay body() {
            return body;
        }

        /** The model the body shows now. */
        ModelRef bodyModel() {
            return ModelTowerLook.body(towerId, stage);
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
            if (crystal != null && tick % SPIN_TICKS == 1) {
                spinStep++;
                animate(crystal, crystalTransform(), SPIN_TICKS);
                if (fallbackCrystal != null) {
                    animate(fallbackCrystal, crystalBlockTransform(spinStep), SPIN_TICKS);
                }
            }
            if (shake > 0) {
                shake--;
                float amount = shake == 0 ? 0 : 0.06f;
                float sign = shake % 2 == 0 ? 1 : -1;
                animate(body, bodyTransform(sign * amount, -sign * amount), 1);
            }
            if (flash > 0 && --flash == 0) {
                for (Display display : glowParts()) {
                    display.setGlowing(false);
                    display.setGlowColorOverride(null);
                }
                if (fallbackCrystal != null) {
                    fallbackCrystal.setGlowing(true);
                    fallbackCrystal.setGlowColorOverride(tint);
                }
            }
        }

        private List<Display> glowParts() {
            List<Display> parts = new ArrayList<>(2);
            parts.add(body);
            if (crystal != null) {
                parts.add(crystal);
            }
            if (fallbackCrystal != null) {
                parts.add(fallbackCrystal);
            }
            return parts;
        }

        private void setStage(int next) {
            if (next == stage) {
                return;
            }
            stage = next;
            body.setItemStack(ModelTowerLook.body(towerId, next).item(tint));
            Map<Vector3f, Material> shape = FallbackShape.of(towerId, spec.fallbackHeight(), next);
            for (Map.Entry<Vector3f, BlockDisplay> block : fallbackBlocks.entrySet()) {
                Material material = shape.getOrDefault(block.getKey(), Material.AIR);
                BlockData data = material.createBlockData();
                if (!block.getValue().getBlock().equals(data)) {
                    block.getValue().setBlock(data);
                }
            }
        }

        // ------------------------------------------------------------------ transformations

        private Quaternionf facing() {
            // models face north (-Z); turn the gate towards the base's yaw (yaw 0 = south)
            return new Quaternionf().rotationY((float) Math.PI - yawRadians);
        }

        /** Body: model ground (y = -16) on the base, footprint centred. */
        private Transformation bodyTransform(float dx, float dz) {
            return new Transformation(new Vector3f(dx, 1.5f * BODY_SCALE, dz), facing(),
                    new Vector3f(BODY_SCALE, BODY_SCALE, BODY_SCALE), new Quaternionf());
        }

        private Transformation crystalTransform() {
            float bob = spinStep % 2 == 0 ? 0f : 0.12f;
            Quaternionf spin = facing().rotateY((float) (spinStep * Math.PI / 2));
            float s = spec.crystalScale();
            return new Transformation(new Vector3f(0, (float) spec.crystalHeight() + bob, 0), spin,
                    new Vector3f(s, s, s), new Quaternionf());
        }

        private Transformation shieldTransform(boolean on) {
            float s = on ? spec.shieldScale() : 0f;
            return new Transformation(new Vector3f(0, 1.5f * s - 0.05f * (on ? 1 : 0), 0), facing(),
                    new Vector3f(s, s, s), new Quaternionf());
        }

        /** A glass block standing on a corner, centred on the crystal spot. */
        private Transformation crystalBlockTransform(int step) {
            Quaternionf rotation = new Quaternionf().rotateY((float) (step * Math.PI / 2 + Math.PI / 4))
                    .rotateX((float) Math.toRadians(35.26)).rotateZ((float) (Math.PI / 4));
            Vector3f half = new Vector3f(-0.5f, -0.5f, -0.5f).rotate(rotation);
            return new Transformation(half, rotation, new Vector3f(1, 1, 1), new Quaternionf());
        }

        private Transformation shieldBlockTransform(boolean on) {
            float width = on ? 4.4f : 0f;
            float height = on ? spec.fallbackHeight() + 2.6f : 0f;
            return new Transformation(new Vector3f(-width / 2, 0, -width / 2), new Quaternionf(),
                    new Vector3f(width, height, width), new Quaternionf());
        }

        // ------------------------------------------------------------------ spawning

        private ItemDisplay model(World world, Location at, ItemStack item,
                                  Transformation transformation) {
            ItemDisplay display = world.spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setVisibleByDefault(false); // only players with the pack are shown it (no missing-model cubes)
                d.setItemStack(item);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setTransformation(transformation);
                d.setViewRange(VIEW_RANGE);
                d.setShadowRadius(0);
            });
            models.add(display);
            return display;
        }

        private BlockDisplay block(World world, Location at, BlockData data, Transformation transformation) {
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

    /** The stained glass closest to a team colour (fallback crystal). */
    static Material glassOf(Color color) {
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
        Material glass = Material.matchMaterial(best.name() + "_STAINED_GLASS");
        return glass == null ? Material.WHITE_STAINED_GLASS : glass;
    }

    /** The vanilla look: 1×1 block displays on a 3×3 footprint, cracking and falling with the damage stage. */
    static final class FallbackShape {

        private FallbackShape() {
        }

        /** Block offset (x and z in -1..1 around the base, y up) → block, for a stage (AIR = gone). */
        static Map<Vector3f, Material> of(String towerId, int height, int stage) {
            Map<Vector3f, Material> blocks = new LinkedHashMap<>();
            Slot put = (x, y, z, intact) -> blocks.put(new Vector3f(x, y, z), damaged(intact, x, y, z, stage, height));
            boolean core = towerId.equals("core");
            for (int y = 0; y < (core ? 1 : height); y++) {
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && z == 0 && y > 0) {
                            continue; // hollow: the crystal of the middle tower shows through its lantern
                        }
                        Material m = core ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS;
                        put.at(x, y, z, m);
                    }
                }
            }
            switch (towerId) {
                case "core" -> {
                    for (int[] c : new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                        for (int y = 1; y <= 4; y++) {
                            put.at(c[0], y, c[1], Material.STONE_BRICKS);
                        }
                        put.at(c[0], 5, c[1], Material.CHISELED_STONE_BRICKS);
                    }
                }
                case "middle" -> {
                    for (int[] c : new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                        put.at(c[0], height, c[1], Material.STONE_BRICKS);
                        put.at(c[0], height + 1, c[1], Material.STONE_BRICKS);
                    }
                    for (int x = -1; x <= 1; x++) {
                        for (int z = -1; z <= 1; z++) {
                            put.at(x, height + 2, z, Material.DEEPSLATE_TILES);
                        }
                    }
                }
                default -> {
                    for (int[] c : new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                        put.at(c[0], height, c[1], Material.STONE_BRICKS);
                    }
                }
            }
            return blocks;
        }

        private interface Slot {
            void at(int x, int y, int z, Material intact);
        }

        /** The block a slot shows at a stage: cracks spread, the top falls, the ruin keeps only rubble. */
        private static Material damaged(Material intact, int x, int y, int z, int stage, int height) {
            int hash = Math.floorMod(x * 73 + y * 31 + z * 17 + x * z * 7, 6);
            if (stage == 0) {
                return intact;
            }
            if (stage >= RUIN) {
                if (y > 0) {
                    return y == 1 && hash < 2 ? Material.COBBLESTONE : Material.AIR;
                }
                return hash % 3 == 0 ? Material.MOSSY_COBBLESTONE : hash == 1 ? Material.AIR : Material.COBBLESTONE;
            }
            boolean bricks = intact == Material.STONE_BRICKS;
            if (stage == 2 && y >= height && hash < 4) {
                return Material.AIR;
            }
            if (bricks && hash < (stage == 1 ? 2 : 4)) {
                return stage == 2 && hash == 0 ? Material.MOSSY_STONE_BRICKS : Material.CRACKED_STONE_BRICKS;
            }
            return intact;
        }
    }
}
