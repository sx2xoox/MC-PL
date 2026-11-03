package io.github.sx2xoox.mcpl.core.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

/**
 * Converts a Blockbench project ({@code .bbmodel}, "Java Block/Item" format) into a vanilla model JSON plus its PNG
 * textures (taken from STSV's converter).
 *
 * <p>Supported: cubes, per-face UV / rotation / tint index / cullface, single-axis element rotation (with rescale),
 * inflate, shade, light emission, embedded (base64) or relative-path textures, animated texture strips
 * ({@code .png.mcmeta}), display transforms and front GUI light. Groups are flattened (vanilla models have no bones);
 * meshes and other non-cube elements are skipped with a warning.
 */
public final class BBModelConverter {

    /**
     * @param model    the vanilla model JSON
     * @param textures texture file name ({@code <name>.png}, {@code <name>.png.mcmeta}) → bytes
     * @param warnings what could not be converted exactly
     */
    public record Result(JsonObject model, Map<String, byte[]> textures, List<String> warnings) {
    }

    private static final List<String> FACES = List.of("north", "east", "south", "west", "up", "down");

    private BBModelConverter() {
    }

    /**
     * @param file          the .bbmodel file
     * @param textureFolder resource location prefix of the textures, e.g. {@code mcpl:item/tower_front}
     */
    public static Result convert(Path file, String textureFolder) throws IOException {
        JsonObject bb = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        return convert(bb, file.getParent(), textureFolder);
    }

    /** Converts an already parsed project; relative texture paths resolve against {@code folder}. */
    public static Result convert(JsonObject bb, Path folder, String textureFolder) throws IOException {
        List<String> warnings = new ArrayList<>();

        float resW = 16;
        float resH = 16;
        if (bb.has("resolution")) {
            JsonObject res = bb.getAsJsonObject("resolution");
            resW = res.get("width").getAsFloat();
            resH = res.get("height").getAsFloat();
        }

        // --- textures --------------------------------------------------------------------------------------------
        Map<String, byte[]> textures = new LinkedHashMap<>();
        JsonObject texRefs = new JsonObject();
        List<float[]> uvSize = new ArrayList<>();
        JsonArray texArr = bb.has("textures") ? bb.getAsJsonArray("textures") : new JsonArray();
        for (int i = 0; i < texArr.size(); i++) {
            JsonObject t = texArr.get(i).getAsJsonObject();
            String name = sanitize(str(t, "name", "texture_" + i).replaceFirst("\\.png$", ""));
            byte[] png = readTexture(folder, t);
            if (png == null) {
                warnings.add("texture '" + name + "' could not be read");
            } else {
                textures.put(name + ".png", png);
                // animated texture: frames stacked vertically (height = n * width) -> .mcmeta
                int w = t.has("width") ? t.get("width").getAsInt() : 0;
                int h = t.has("height") ? t.get("height").getAsInt() : 0;
                if (w > 0 && h > w && h % w == 0) {
                    int frameTime = t.has("frame_time") ? Math.max(1, t.get("frame_time").getAsInt()) : 1;
                    boolean interpolate = t.has("frame_interpolate") && t.get("frame_interpolate").getAsBoolean();
                    String mcmeta = "{\"animation\":{\"frametime\":" + frameTime
                            + (interpolate ? ",\"interpolate\":true" : "") + "}}";
                    textures.put(name + ".png.mcmeta", mcmeta.getBytes(StandardCharsets.UTF_8));
                }
            }
            texRefs.addProperty(String.valueOf(i), textureFolder + "/" + name);
            if (t.has("particle") && t.get("particle").getAsBoolean()) {
                texRefs.addProperty("particle", textureFolder + "/" + name);
            }
            float uw = t.has("uv_width") ? t.get("uv_width").getAsFloat() : resW;
            float uh = t.has("uv_height") ? t.get("uv_height").getAsFloat() : resH;
            uvSize.add(new float[] {uw, uh});
        }
        if (!texRefs.has("particle") && texRefs.has("0")) {
            texRefs.add("particle", texRefs.get("0"));
        }

        // --- elements --------------------------------------------------------------------------------------------
        JsonArray elements = new JsonArray();
        JsonArray bbElements = bb.has("elements") ? bb.getAsJsonArray("elements") : new JsonArray();
        for (JsonElement je : bbElements) {
            JsonObject el = je.getAsJsonObject();
            String elName = str(el, "name", "?");
            String type = str(el, "type", "cube");
            if (!type.equals("cube")) {
                warnings.add("'" + elName + "' is a " + type + ", skipped (only cubes are supported)");
                continue;
            }
            if (el.has("export") && !el.get("export").getAsBoolean()) {
                continue;
            }

            float inflate = el.has("inflate") ? el.get("inflate").getAsFloat() : 0;
            float[] from = vec(el.getAsJsonArray("from"));
            float[] to = vec(el.getAsJsonArray("to"));
            for (int a = 0; a < 3; a++) {
                from[a] -= inflate;
                to[a] += inflate;
            }
            for (int a = 0; a < 3; a++) {
                if (from[a] < -16 || to[a] > 32) {
                    warnings.add("'" + elName + "' is outside the vanilla range -16..32");
                    break;
                }
            }

            JsonObject out = new JsonObject();
            out.add("from", arr(from));
            out.add("to", arr(to));

            if (el.has("rotation")) {
                float[] rot = vec(el.getAsJsonArray("rotation"));
                int axis = -1;
                for (int a = 0; a < 3; a++) {
                    if (rot[a] != 0) {
                        if (axis != -1) {
                            warnings.add("'" + elName + "' rotates on several axes, only the first is kept");
                        } else {
                            axis = a;
                        }
                    }
                }
                if (axis != -1) {
                    JsonObject r = new JsonObject();
                    r.addProperty("angle", rot[axis]);
                    r.addProperty("axis", "xyz".substring(axis, axis + 1));
                    r.add("origin", el.has("origin") ? arr(vec(el.getAsJsonArray("origin"))) : arr(new float[] {8, 8, 8}));
                    if (el.has("rescale") && el.get("rescale").getAsBoolean()) {
                        r.addProperty("rescale", true);
                    }
                    out.add("rotation", r);
                }
            }
            if (el.has("shade") && !el.get("shade").getAsBoolean()) {
                out.addProperty("shade", false);
            }
            if (el.has("light_emission") && el.get("light_emission").getAsInt() > 0) {
                out.addProperty("light_emission", Math.min(15, el.get("light_emission").getAsInt()));
            }

            JsonObject faces = new JsonObject();
            JsonObject bbFaces = el.getAsJsonObject("faces");
            for (String f : FACES) {
                if (bbFaces == null || !bbFaces.has(f)) {
                    continue;
                }
                JsonObject face = bbFaces.getAsJsonObject(f);
                if (!face.has("texture") || face.get("texture").isJsonNull()) {
                    continue;
                }
                int tex = face.get("texture").getAsInt();
                float[] size = tex < uvSize.size() ? uvSize.get(tex) : new float[] {resW, resH};
                float[] uv = vec(face.getAsJsonArray("uv"));
                JsonObject of = new JsonObject();
                of.add("uv", arr(new float[] {
                        uv[0] * 16 / size[0], uv[1] * 16 / size[1], uv[2] * 16 / size[0], uv[3] * 16 / size[1]}));
                of.addProperty("texture", "#" + tex);
                if (face.has("rotation") && face.get("rotation").getAsInt() != 0) {
                    of.addProperty("rotation", face.get("rotation").getAsInt());
                }
                if (face.has("tint") && face.get("tint").getAsInt() >= 0) {
                    of.addProperty("tintindex", face.get("tint").getAsInt());
                }
                if (face.has("cullface") && !face.get("cullface").getAsString().isEmpty()) {
                    of.add("cullface", face.get("cullface"));
                }
                faces.add(f, of);
            }
            out.add("faces", faces);
            elements.add(out);
        }

        JsonObject model = new JsonObject();
        if (bb.has("ambientocclusion") && !bb.get("ambientocclusion").getAsBoolean()) {
            model.addProperty("ambientocclusion", false);
        }
        if (bb.has("front_gui_light") && bb.get("front_gui_light").getAsBoolean()) {
            model.addProperty("gui_light", "front");
        }
        model.add("textures", texRefs);
        model.add("elements", elements);
        if (bb.has("display") && bb.get("display").isJsonObject() && !bb.getAsJsonObject("display").isEmpty()) {
            model.add("display", bb.get("display"));
        }
        return new Result(model, textures, warnings);
    }

    /** Whether any face of the converted model has a tint index (the item definition then needs tint sources). */
    public static int tintCount(JsonObject model) {
        int max = -1;
        for (JsonElement e : model.getAsJsonArray("elements")) {
            for (Map.Entry<String, JsonElement> face : e.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
                JsonObject f = face.getValue().getAsJsonObject();
                if (f.has("tintindex")) {
                    max = Math.max(max, f.get("tintindex").getAsInt());
                }
            }
        }
        return max + 1;
    }

    private static byte @Nullable [] readTexture(Path folder, JsonObject t) throws IOException {
        String source = str(t, "source", "");
        int comma = source.indexOf(',');
        if (source.startsWith("data:image/png;base64,") && comma > 0) {
            return Base64.getDecoder().decode(source.substring(comma + 1));
        }
        for (String key : List.of("relative_path", "path")) {
            String p = str(t, key, "");
            if (p.isEmpty()) {
                continue;
            }
            Path candidate = folder.resolve(p).normalize();
            if (Files.isRegularFile(candidate)) {
                return Files.readAllBytes(candidate);
            }
        }
        // fall back to a png with the same name next to the model
        String name = str(t, "name", "");
        if (name.isEmpty()) {
            return null;
        }
        Path sibling = folder.resolve(name);
        return Files.isRegularFile(sibling) ? Files.readAllBytes(sibling) : null;
    }

    /** A resource-location-safe name: lower case {@code a-z 0-9 _ . / -}. */
    public static String sanitize(String s) {
        String r = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        return r.isEmpty() ? "texture" : r;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    private static float[] vec(JsonArray a) {
        float[] v = new float[a.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = a.get(i).getAsFloat();
        }
        return v;
    }

    private static JsonArray arr(float[] v) {
        JsonArray a = new JsonArray();
        for (float f : v) {
            if (f == Math.rint(f)) {
                a.add((int) f);
            } else {
                a.add(Math.round(f * 10000f) / 10000f);
            }
        }
        return a;
    }
}
