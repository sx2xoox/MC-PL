package io.github.sx2xoox.mcpl.core.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sx2xoox.mcpl.game.siege.tower.ModelTowerLook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
            if (path.endsWith(".png")) {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(file.getValue()));
                if (image == null || image.getWidth() != image.getHeight() && image.getHeight() % image.getWidth() != 0) {
                    problems.add(path + " is not a square (or frame strip) PNG");
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
        assertTrue(items >= ModelTowerLook.models().size(), "every tower part");
    }

    @Test
    void everyModelTheTowerLookUsesIsInThePack() {
        List<ModelRef> used = new ArrayList<>(ModelTowerLook.models());
        used.add(PackCheck.MODEL);
        for (ModelRef ref : used) {
            assertTrue(built.files().containsKey("assets/mcpl/items/" + ref.id() + ".json"), ref.id());
        }
        JsonObject heart = json("assets/mcpl/items/tower_core_heart.json").getAsJsonObject("model");
        assertEquals("minecraft:dye", heart.getAsJsonArray("tints").get(0).getAsJsonObject().get("type")
                .getAsString(), "the mainframe's heart takes the team colour");
        assertFalse(json("assets/mcpl/items/tower_core_shield.json").getAsJsonObject("model").has("tints"));
        // animated textures (the firewall's shimmer, blinking racks) ship their .mcmeta
        assertTrue(built.files().keySet().stream().anyMatch(p -> p.endsWith("/energy.png.mcmeta")));
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
