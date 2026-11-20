package io.github.sx2xoox.mcpl.core.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Blockbench → vanilla model conversion. */
class BBModelConverterTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    private static JsonObject project() {
        String png = Base64.getEncoder().encodeToString(PNG);
        return JsonParser.parseString("""
                {
                  "meta": {"model_format": "java_block"},
                  "resolution": {"width": 32, "height": 32},
                  "ambientocclusion": false,
                  "front_gui_light": true,
                  "textures": [
                    {"name": "Stone Wall.png", "width": 32, "height": 32, "source": "data:image/png;base64,%s"},
                    {"name": "flow.png", "width": 16, "height": 64, "uv_width": 16, "uv_height": 16,
                     "frame_time": 3, "frame_interpolate": true, "particle": true, "relative_path": "flow.png"}
                  ],
                  "elements": [
                    {"name": "wall", "type": "cube", "from": [0, 0, 0], "to": [16, 8, 4], "inflate": 0.5,
                     "rotation": [0, 22.5, 0], "origin": [8, 4, 2], "rescale": true,
                     "faces": {
                       "north": {"uv": [0, 0, 32, 16], "texture": 0, "rotation": 90, "cullface": "north"},
                       "south": {"uv": [0, 0, 16, 16], "texture": 1, "tint": 0},
                       "up": {"uv": [0, 0, 0, 0], "texture": null}
                     }},
                    {"name": "lamp", "type": "cube", "from": [4, 8, 4], "to": [12, 12, 12], "shade": false,
                     "light_emission": 20, "rotation": [45, 0, 22.5],
                     "faces": {"up": {"uv": [0, 0, 8, 8], "texture": 0}}},
                    {"name": "hidden", "type": "cube", "export": false, "from": [0, 0, 0], "to": [1, 1, 1], "faces": {}},
                    {"name": "blob", "type": "mesh", "vertices": {}},
                    {"name": "huge", "type": "cube", "from": [-20, 0, 0], "to": [1, 1, 1], "faces": {}}
                  ],
                  "display": {"fixed": {"scale": [2, 2, 2]}}
                }
                """.formatted(png)).getAsJsonObject();
    }

    @Test
    void convertsCubesFacesAndTextures(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("flow.png"), new byte[] {9, 9});
        BBModelConverter.Result r = BBModelConverter.convert(project(), dir, "mcpl:item/demo");
        JsonObject model = r.model();

        assertEquals(false, model.get("ambientocclusion").getAsBoolean());
        assertEquals("front", model.get("gui_light").getAsString());
        JsonObject textures = model.getAsJsonObject("textures");
        assertEquals("mcpl:item/demo/stone_wall", textures.get("0").getAsString(), "names are sanitized");
        assertEquals("mcpl:item/demo/flow", textures.get("1").getAsString());
        assertEquals("mcpl:item/demo/flow", textures.get("particle").getAsString(), "the particle texture is kept");
        assertArrayEquals(PNG, r.textures().get("stone_wall.png"), "embedded base64 texture");
        assertArrayEquals(new byte[] {9, 9}, r.textures().get("flow.png"), "relative path texture");
        assertEquals("{\"animation\":{\"frametime\":3,\"interpolate\":true}}",
                new String(r.textures().get("flow.png.mcmeta"), StandardCharsets.UTF_8), "frame strip → mcmeta");

        JsonArray elements = model.getAsJsonArray("elements");
        assertEquals(3, elements.size(), "export=false and the mesh are dropped; the oversized cube is kept");
        JsonObject wall = elements.get(0).getAsJsonObject();
        assertEquals("[-0.5,-0.5,-0.5]", wall.get("from").toString(), "inflate grows the box");
        assertEquals("[16.5,8.5,4.5]", wall.get("to").toString());
        JsonObject rotation = wall.getAsJsonObject("rotation");
        assertEquals(22.5, rotation.get("angle").getAsDouble());
        assertEquals("y", rotation.get("axis").getAsString());
        assertEquals("[8,4,2]", rotation.get("origin").toString());
        assertTrue(rotation.get("rescale").getAsBoolean());

        JsonObject faces = wall.getAsJsonObject("faces");
        assertFalse(faces.has("up"), "a face without texture is removed");
        JsonObject north = faces.getAsJsonObject("north");
        assertEquals("[0,0,16,8]", north.get("uv").toString(), "UV scaled from the 32px project resolution");
        assertEquals("#0", north.get("texture").getAsString());
        assertEquals(90, north.get("rotation").getAsInt());
        assertEquals("north", north.get("cullface").getAsString());
        JsonObject south = faces.getAsJsonObject("south");
        assertEquals("[0,0,16,16]", south.get("uv").toString(), "per-texture uv size wins");
        assertEquals(0, south.get("tintindex").getAsInt());

        JsonObject lamp = elements.get(1).getAsJsonObject();
        assertFalse(lamp.get("shade").getAsBoolean());
        assertEquals(15, lamp.get("light_emission").getAsInt(), "clamped to 15");
        assertEquals("x", lamp.getAsJsonObject("rotation").get("axis").getAsString(), "only the first axis");
        assertEquals("[8,8,8]", lamp.getAsJsonObject("rotation").get("origin").toString(), "default origin");

        assertEquals(2, model.getAsJsonObject("display").getAsJsonObject("fixed").getAsJsonArray("scale").get(0)
                .getAsInt());
        assertEquals(1, BBModelConverter.tintCount(model));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("blob")), "mesh warned");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("lamp")), "multi-axis rotation warned");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("huge")), "out of range warned");
    }

    @Test
    void aMissingTextureIsAWarning(@TempDir Path dir) throws IOException {
        JsonObject bb = project();
        bb.getAsJsonArray("textures").get(1).getAsJsonObject().addProperty("relative_path", "nowhere.png");
        BBModelConverter.Result r = BBModelConverter.convert(bb, dir, "mcpl:item/demo");
        assertFalse(r.textures().containsKey("flow.png"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("flow")));
    }

    @Test
    void readsAFileAndSanitizes(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Model.bbmodel");
        Files.writeString(file, project().toString());
        Files.write(dir.resolve("flow.png"), new byte[] {1});
        assertEquals(3, BBModelConverter.convert(file, "x:y").model().getAsJsonArray("elements").size());
        assertEquals("tower_front_0", BBModelConverter.sanitize("Tower Front_0"));
        assertEquals("texture", BBModelConverter.sanitize(""));
    }

    @Test
    void itemDefinitionsGetOneDyeTintPerIndex() {
        JsonObject plain = PackBuilder.itemDefinition("tower_shield", 0).getAsJsonObject("model");
        assertEquals("minecraft:model", plain.get("type").getAsString());
        assertEquals("mcpl:item/tower_shield", plain.get("model").getAsString());
        assertFalse(plain.has("tints"));
        JsonArray tints = PackBuilder.itemDefinition("tower_crystal", 1).getAsJsonObject("model")
                .getAsJsonArray("tints");
        assertEquals(1, tints.size());
        assertEquals("minecraft:dye", tints.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(PackBuilder.DEFAULT_TINT, tints.get(0).getAsJsonObject().get("default").getAsInt());
    }
}
