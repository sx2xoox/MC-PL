package io.github.sx2xoox.mcpl.core.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sx2xoox.mcpl.game.siege.SiegeSettings;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels;
import io.github.sx2xoox.mcpl.game.siege.tower.ModelTowerLook;
import io.github.sx2xoox.mcpl.game.superpower.abilities.BuiltinAbilities;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityWeaponModels;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * The MCPL resource pack built from {@code src/main/resourcepack-src}: valid JSON everywhere, every item model and
 * texture it references exists, it is reproducible, and the copy bundled into the jar is up to date.
 */
class ResourcePackContentsTest {

    private static final Path SOURCE = Path.of("src/main/resourcepack-src");
    private static PackBuilder.Built built;

    @BeforeAll
    static void build() throws IOException {
        built = PackBuilder.build(SOURCE, "test");
    }

    @Test
    void buildsWithoutWarningsAndReproducibly() throws IOException {
        assertTrue(built.warnings().isEmpty(), () -> String.join("\n", built.warnings()));
        assertEquals(built.sha1(), PackBuilder.build(SOURCE, "test").sha1(), "same sources, same hash");
    }

    @Test
    void theMetadataUsesThe26Point3Format() {
        JsonObject pack = json("pack.mcmeta").getAsJsonObject("pack");
        assertEquals(PackBuilder.PACK_FORMAT, pack.get("min_format").getAsInt());
        assertEquals(PackBuilder.PACK_FORMAT, pack.get("max_format").getAsInt());
        assertFalse(pack.has("pack_format"), "26.x packs use min_format / max_format");
    }

    @Test
    void everyJsonParsesAndEveryReferenceExists() throws IOException {
        List<String> problems = new ArrayList<>();
        int items = 0;
        int fonts = 0;
        for (Map.Entry<String, byte[]> file : built.files().entrySet()) {
            String path = file.getKey();
            if (path.endsWith(".json") || path.endsWith(".mcmeta")) {
                JsonParser.parseString(new String(file.getValue(), StandardCharsets.UTF_8)); // throws if invalid
            }
            if (path.startsWith("assets/mcpl/items/")) {
                items++;
                String model = json(path).getAsJsonObject("model").get("model").getAsString();
                if (!built.files().containsKey(modelPath(model))) {
                    problems.add(path + " → missing model " + model);
                }
            }
            if (path.startsWith("assets/mcpl/models/")) {
                for (Map.Entry<String, JsonElement> tex : json(path).getAsJsonObject("textures").entrySet()) {
                    String ref = tex.getValue().getAsString();
                    if (!ref.startsWith("#") && !ref.startsWith("minecraft:")
                            && !built.files().containsKey(texturePath(ref))) {
                        problems.add(path + " → missing texture " + ref);
                    }
                }
            }
            if (path.matches("assets/[^/]+/font/[^/]+\\.json")) {
                fonts++;
                for (JsonElement p : json(path).getAsJsonArray("providers")) {
                    JsonObject provider = p.getAsJsonObject();
                    String[] ref = provider.has("file") ? provider.get("file").getAsString().split(":", 2) : null;
                    if (ref != null && !built.files().containsKey("assets/" + ref[0] + "/textures/" + ref[1])) {
                        problems.add(path + " → missing font image " + provider.get("file").getAsString());
                    }
                }
            }
            if (path.endsWith(".png")) {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(file.getValue()));
                boolean equipment = path.contains("/textures/trims/entity/") || path.contains("/textures/entity/equipment/");
                if (image == null) {
                    problems.add(path + " is not a PNG");
                } else if (equipment && image.getWidth() != 2 * image.getHeight()) {
                    problems.add(path + " is not a 2:1 equipment texture (the 64 x 32 humanoid layout)");
                } else if (!equipment && !path.contains("/textures/font/")    // font glyphs may have any size
                        && image.getWidth() != image.getHeight() && image.getHeight() % image.getWidth() != 0) {
                    problems.add(path + " is not a square (or frame strip) PNG");
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
        assertTrue(items >= ModelTowerLook.models().size(), "every tower part");
        assertTrue(fonts >= 1, "the sidebar panel font (tools/assetgen/sidebar_panel.py)");
    }

    @Test
    void everyModelTheTowerLookUsesIsInThePack() {
        List<ModelRef> used = new ArrayList<>(ModelTowerLook.models());
        used.add(PackCheck.MODEL);
        for (ModelRef ref : used) {
            assertTrue(built.files().containsKey("assets/mcpl/items/" + ref.id() + ".json"), ref.id());
        }
        JsonObject crown = json("assets/mcpl/items/structure/core_crown.json").getAsJsonObject("model");
        assertEquals("minecraft:dye", crown.getAsJsonArray("tints").get(0).getAsJsonObject().get("type")
                .getAsString(), "the crown's gems take the team colour");
        assertFalse(json("assets/mcpl/items/structure/core_shield.json").getAsJsonObject("model").has("tints"));
    }

    /** The display contexts every ability weapon sets (the shared preset of tools/assetgen weaponkit.py). */
    private static final List<String> WEAPON_DISPLAYS = List.of("firstperson_righthand", "firstperson_lefthand",
            "thirdperson_righthand", "thirdperson_lefthand", "gui", "ground", "fixed");

    @Test
    void everyAbilityWeaponIsInThePack() {
        List<String> problems = new ArrayList<>();
        for (String id : AbilityWeaponModels.modeled()) {
            NamespacedKey key = AbilityWeaponModels.model(id);
            assertNotNull(key, id);
            String item = "assets/" + key.getNamespace() + "/items/" + key.getKey() + ".json";
            if (!built.files().containsKey(item)) {
                problems.add(id + ": no item definition " + item + " (tools/assetgen/models/weapons.py WEAPONS?)");
                continue;
            }
            String model = modelPath(json(item).getAsJsonObject("model").get("model").getAsString());
            if (!built.files().containsKey(model)) {
                problems.add(id + ": no model " + model);
                continue;
            }
            JsonObject m = json(model);
            JsonObject display = m.has("display") ? m.getAsJsonObject("display") : new JsonObject();
            for (String ctx : WEAPON_DISPLAYS) {
                if (!display.has(ctx)) {
                    problems.add(id + ": no '" + ctx + "' display transform (use weaponkit's Weapon.finish())");
                }
            }
            if (m.getAsJsonObject("textures").isEmpty() || m.getAsJsonArray("elements").isEmpty()) {
                problems.add(id + ": empty model");
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void everyWeaponInThePackIsRegisteredForARealAbility() {
        Set<String> inPack = new TreeSet<>();
        for (String path : built.files().keySet()) {
            if (path.startsWith("assets/mcpl/items/weapon/")) {
                inPack.add(path.substring("assets/mcpl/items/weapon/".length()).replaceFirst("\\.json$", ""));
            }
        }
        assertEquals(new TreeSet<>(AbilityWeaponModels.modeled()), inPack,
                "AbilityWeaponModels.MODELED must list exactly the weapons in the pack");
        MockBukkit.mock(); // some abilities build block data in their static setup
        try {
            AbilityRegistry registry = new AbilityRegistry();
            BuiltinAbilities.registerAll(registry);
            for (String id : AbilityWeaponModels.modeled()) {
                assertNotNull(registry.get(id), "weapon for an unknown ability id: " + id);
            }
            for (String id : SiegeSettings.DEFAULT_POOL) {
                assertNotNull(registry.get(id), "공성전 default pool: unknown ability id " + id);
            }
        } finally {
            MockBukkit.unmock();
        }
        assertNull(AbilityWeaponModels.model("no_such_ability"));
    }

    @Test
    void everySiegePoolAbilityHasAWeapon() {
        assertEquals(SiegeSettings.DEFAULT_POOL.size(), new HashSet<>(SiegeSettings.DEFAULT_POOL).size(),
                "the 공성전 default pool lists an ability twice");
        for (String id : SiegeSettings.DEFAULT_POOL) {
            assertTrue(AbilityWeaponModels.modeled().contains(id), "공성전 pool ability without a weapon: " + id);
        }
    }

    /**
     * The ability sword keeps a vanilla item model for everyone; the pack's override of that sword selects the 3D weapon
     * by {@code custom_model_data} string 0 (the ability id) and falls back to the vanilla sword model otherwise.
     */
    @Test
    void theVanillaSwordsSelectEveryWeaponAndFallBackToVanilla() {
        assertEquals(AbilityWeaponModels.SWORDS, PackBuilder.WEAPON_SWORDS);
        List<String> problems = new ArrayList<>();
        for (String sword : AbilityWeaponModels.SWORDS) {
            JsonObject model = json("assets/minecraft/items/" + sword + ".json").getAsJsonObject("model");
            assertEquals("minecraft:select", model.get("type").getAsString(), sword);
            assertEquals("minecraft:custom_model_data", model.get("property").getAsString(), sword);
            assertEquals(0, model.get("index").getAsInt(), sword);
            JsonObject fallback = model.getAsJsonObject("fallback");
            assertEquals("minecraft:model", fallback.get("type").getAsString(), sword);
            assertEquals("minecraft:item/" + sword, fallback.get("model").getAsString(),
                    sword + ": no custom_model_data string = the vanilla sword (as in the 26.3 client's definition)");
            Set<String> cased = new TreeSet<>();
            for (JsonElement element : model.getAsJsonArray("cases")) {
                JsonObject c = element.getAsJsonObject();
                String id = c.get("when").getAsString();
                cased.add(id);
                JsonObject weapon = json("assets/mcpl/items/weapon/" + id + ".json").getAsJsonObject("model");
                if (!weapon.equals(c.getAsJsonObject("model"))) {
                    problems.add(sword + " / " + id + ": the case is not the weapon's own item model");
                }
                if (!built.files().containsKey(modelPath(c.getAsJsonObject("model").get("model").getAsString()))) {
                    problems.add(sword + " / " + id + ": missing model");
                }
            }
            assertEquals(new TreeSet<>(AbilityWeaponModels.modeled()), cased, sword + " covers every weapon");
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void everyCreatureAndLandmarkModelIsInThePack() {
        List<String> problems = new ArrayList<>();
        for (ModelRef ref : CreatureModels.models()) {
            String item = "assets/mcpl/items/" + ref.id() + ".json";
            if (!built.files().containsKey(item)) {
                problems.add(ref.id() + ": no item definition (tools/assetgen/models/creatures.py / toys.py?)");
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
        // minions take the team colour, jungle monsters keep their own
        assertTrue(json("assets/mcpl/items/creature/soldier.json").getAsJsonObject("model").has("tints"));
        assertFalse(json("assets/mcpl/items/creature/teddy.json").getAsJsonObject("model").has("tints"));
        // every pack model of a creature or a toy building is one the game uses
        Set<String> used = new TreeSet<>();
        CreatureModels.models().forEach(r -> used.add(r.id()));
        ModelTowerLook.models().forEach(r -> used.add(r.id()));
        for (String path : built.files().keySet()) {
            if (path.matches("assets/mcpl/items/(creature|structure)/.*\\.json")) {
                String id = path.substring("assets/mcpl/items/".length()).replaceFirst("\\.json$", "");
                assertTrue(used.contains(id), "a model nothing shows: " + id);
            }
        }
    }

    /**
     * The soap-bubble film: the pack overrides the item core shader (vertex + fragment, including its own include),
     * the bubble texture carries the shader's marker alpha everywhere, and leaving the shaders out
     * ({@code resource-pack.shaders: false}) still gives a reproducible pack without them.
     */
    @Test
    void theBubbleShaderAndItsMarkerShipAndCanBeLeftOut() throws IOException {
        String fsh = text("assets/minecraft/shaders/core/item.fsh");
        String vsh = text("assets/minecraft/shaders/core/item.vsh");
        String include = text("assets/minecraft/shaders/include/mcpl_bubble.glsl");
        assertTrue(fsh.contains("#include <minecraft:mcpl_bubble.glsl>"));
        assertTrue(vsh.contains("out vec3 mcplNormal") && fsh.contains("in vec3 mcplNormal"));
        for (String source : List.of(fsh, vsh)) {
            for (String line : source.split("\n")) {
                if (line.startsWith("#include")) {
                    String name = line.replaceAll(".*<minecraft:([^>]+)>.*", "$1");
                    assertTrue(built.files().containsKey("assets/minecraft/shaders/include/" + name)
                            || VANILLA_INCLUDES.contains(name), "unresolved include " + name);
                }
            }
        }
        int marker = Integer.parseInt(include.replaceAll("(?s).*MCPL_BUBBLE_ALPHA = ([0-9]+)\\.0;.*", "$1"));
        String bubbleTexture = built.files().keySet().stream()
                .filter(p -> p.startsWith("assets/mcpl/textures/item/structure/core_shield/") && p.endsWith(".png"))
                .findFirst().orElseThrow();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(built.files().get(bubbleTexture)));
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                assertEquals(marker, image.getRGB(x, y) >>> 24, "bubble texel " + x + "," + y);
            }
        }

        byte[] stripped = PackBuilder.withShaders(built.zip(), null);
        assertTrue(Arrays.equals(stripped, PackBuilder.withShaders(built.zip(), null)), "reproducible");
        SortedMap<String, byte[]> names = unzip(stripped);
        Set<String> expected = new TreeSet<>(built.files().keySet());
        expected.removeIf(p -> p.startsWith(PackBuilder.SHADERS));
        assertEquals(expected, names.keySet());
        assertTrue(names.size() < built.files().size());
    }

    /**
     * The shader presets ({@code resource-pack.shaders}, {@code /게임 셰이더}): each variant is reproducible, carries
     * only its features' shaders with every include they need resolved, defines exactly its features' macros, and keeps
     * every other file as is; the bundled pack is the default preset's variant; off has no shader at all.
     */
    @Test
    void everyShaderPresetIsAReproducibleVariantWithItsOwnShaders() throws IOException {
        assertTrue(Arrays.equals(built.zip(), PackBuilder.withShaders(built.zip(), ShaderPreset.DEFAULT)),
                "the bundle is the default preset's variant");
        assertEquals(ShaderPreset.presetInclude(ShaderPreset.DEFAULT), text(ShaderPreset.PRESET_INCLUDE));
        Set<String> hashes = new HashSet<>();
        hashes.add(ResourcePackService.sha1(PackBuilder.withShaders(built.zip(), null)));
        SortedMap<String, byte[]> others = new TreeMap<>(built.files());
        others.keySet().removeIf(p -> p.startsWith(PackBuilder.SHADERS));
        for (ShaderPreset preset : ShaderPreset.values()) {
            byte[] variant = PackBuilder.withShaders(built.zip(), preset);
            assertTrue(Arrays.equals(variant, PackBuilder.withShaders(built.zip(), preset)), preset + " reproducible");
            assertTrue(hashes.add(ResourcePackService.sha1(variant)), preset + " has its own hash");
            SortedMap<String, byte[]> files = unzip(variant);
            for (Map.Entry<String, byte[]> other : others.entrySet()) {
                assertTrue(Arrays.equals(other.getValue(), files.get(other.getKey())), preset + " " + other.getKey());
            }
            Set<String> shaders = new TreeSet<>(files.keySet());
            shaders.removeIf(p -> !p.startsWith(PackBuilder.SHADERS));
            for (String core : preset.files()) {
                assertTrue(shaders.contains(PackBuilder.SHADERS + core), preset + " ships " + core);
            }
            assertTrue(shaders.contains(ShaderPreset.PRESET_INCLUDE), preset.toString());
            String presetInclude = new String(files.get(ShaderPreset.PRESET_INCLUDE), StandardCharsets.UTF_8);
            for (ShaderPreset.Feature feature : ShaderPreset.Feature.values()) {
                assertEquals(preset.features().contains(feature), presetInclude.contains("#define " + feature.macro()
                        + "\n"), preset + " " + feature);
            }
            for (String path : shaders) {
                String source = new String(files.get(path), StandardCharsets.UTF_8);
                for (String line : source.split("\n")) {
                    if (line.trim().startsWith("#include")) {
                        String name = line.replaceAll(".*<minecraft:([^>]+)>.*", "$1");
                        assertTrue(files.containsKey(PackBuilder.SHADERS + "include/" + name)
                                || VANILLA_INCLUDES.contains(name), preset + ": " + path + " includes " + name);
                    }
                }
                if (path.endsWith(".fsh") && source.contains("in vec3 mcplNormal")) {
                    String vsh = path.replaceFirst("\\.fsh$", ".vsh");
                    assertTrue(files.containsKey(vsh) && new String(files.get(vsh), StandardCharsets.UTF_8)
                            .contains("out vec3 mcplNormal"), preset + ": " + vsh + " feeds " + path);
                }
            }
            boolean grade = preset.features().contains(ShaderPreset.Feature.COLOR_GRADE);
            assertEquals(grade, shaders.contains(PackBuilder.SHADERS + "core/terrain.fsh"), preset.toString());
        }
    }

    /**
     * The colour grade: every core shader of the feature includes the grade and calls it once on the lit colour, and
     * nothing that draws text or the GUI is overridden (the grade also skips GUI items: the empty fog).
     */
    @Test
    void theColorGradeCoversTheWorldAndLeavesTextAndTheGuiAlone() throws IOException {
        String grade = text("assets/minecraft/shaders/include/mcpl_grade.glsl");
        assertTrue(grade.contains("#ifdef MCPL_COLOR_GRADE") && grade.contains("FogRenderDistanceEnd < 1.0e30"));
        String preview = Files.readString(Path.of("tools/assetgen/grade_preview.py"));
        for (String constant : List.of("SATURATION", "LIFT", "BRIGHTEN", "SHADOW_TINT", "HIGHLIGHT")) {
            String shader = grade.replaceAll("(?s).*const \\w+ MCPL_GRADE_" + constant + " = (?:vec3\\()?([0-9., ]+)\\)?;.*",
                    "$1");
            String python = preview.replaceAll("(?s).*\\n" + constant + " = (?:np\\.array\\(\\[)?([0-9., ]+)\\]?\\)?\\n.*",
                    "$1");
            assertEquals(shader.trim(), python.trim(), "grade_preview.py uses the shader's " + constant);
        }
        for (String core : ShaderPreset.Feature.COLOR_GRADE.shaders()) {
            String source = text(PackBuilder.SHADERS + core);
            assertTrue(source.contains("#include <minecraft:mcpl_grade.glsl>"), core);
            assertEquals(1, source.split("mcpl_world_grade\\(", -1).length - 1, core + " grades once");
            assertTrue(source.indexOf("#include <minecraft:fog.glsl>")
                    < source.indexOf("#include <minecraft:mcpl_grade.glsl>"), core);
            int call = source.indexOf("mcpl_world_grade(");
            int accumulate = source.indexOf("sampleColorForAccumulation(");
            assertTrue(accumulate < 0 || call < accumulate, core + ": graded before OIT premultiplies");
        }
        for (String path : built.files().keySet()) {
            if (path.startsWith(PackBuilder.SHADERS + "core/")) {
                String name = path.substring((PackBuilder.SHADERS + "core/").length());
                assertFalse(name.matches("(text|gui|position_tex_color|position_color|position_tex|panorama)\\..*"),
                        "text and GUI shaders keep their colours: " + name);
            }
        }
    }

    private static SortedMap<String, byte[]> unzip(byte[] zip) throws IOException {
        SortedMap<String, byte[]> files = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    /** The 26.3 client's shader includes a pack shader may use without shipping them. */
    private static final Set<String> VANILLA_INCLUDES = Set.of("light.glsl", "fog.glsl", "dynamictransforms.glsl",
            "projection.glsl", "sample_lightmap.glsl", "globals.glsl", "oit.glsl", "texture_sampling.glsl",
            "terrainglobals.glsl", "chunksection.glsl", "matrix.glsl");

    private static String text(String path) {
        byte[] data = built.files().get(path);
        assertNotNull(data, path);
        return new String(data, StandardCharsets.UTF_8);
    }

    @Test
    void theJarBundlesTheCurrentPack() throws IOException {
        SortedMap<String, byte[]> bundled = new TreeMap<>();
        try (InputStream in = getClass().getResourceAsStream("/" + ResourcePackService.BUNDLED)) {
            assertNotNull(in, "./gradlew resourcePack is bundled by processResources");
            try (ZipInputStream zip = new ZipInputStream(in)) {
                for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    bundled.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        assertEquals(built.files().keySet(), bundled.keySet());
        for (String path : built.files().keySet()) {
            if (!path.equals("pack.mcmeta")) {
                assertTrue(Arrays.equals(built.files().get(path), bundled.get(path)), path);
            }
        }
    }

    private static JsonObject json(String path) {
        byte[] data = built.files().get(path);
        assertNotNull(data, path);
        return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** {@code mcpl:item/x} → {@code assets/mcpl/models/item/x.json}. */
    private static String modelPath(String ref) {
        String[] parts = ref.split(":", 2);
        return "assets/" + parts[0] + "/models/" + parts[1] + ".json";
    }

    private static String texturePath(String ref) {
        String[] parts = ref.split(":", 2);
        return "assets/" + parts[0] + "/textures/" + parts[1] + ".png";
    }
}
