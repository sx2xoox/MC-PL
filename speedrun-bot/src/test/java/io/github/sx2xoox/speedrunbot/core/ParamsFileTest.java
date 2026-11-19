package io.github.sx2xoox.speedrunbot.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** tools/defaults.json (the tuner's baseline) and tools/param_space.json must match the code defaults. */
class ParamsFileTest {
    private static JsonObject read(String p) throws IOException {
        Path path = Paths.get(p);
        return new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void defaultsJsonMatchesCode() throws IOException {
        JsonObject o = read("tools/defaults.json");
        assertEquals(Params.DEFAULTS.size(), o.size(), "tools/defaults.json is stale");
        for (Map.Entry<String, Double> e : Params.DEFAULTS.entrySet()) {
            assertTrue(o.has(e.getKey()), "missing " + e.getKey());
            assertEquals(e.getValue(), o.get(e.getKey()).getAsDouble(), 1e-9, e.getKey());
        }
    }

    @Test
    void searchSpaceCoversKnownKeysAndDefaults() throws IOException {
        JsonObject space = read("tools/param_space.json");
        for (Map.Entry<String, JsonElement> e : space.entrySet()) {
            assertTrue(Params.DEFAULTS.containsKey(e.getKey()), "unknown key " + e.getKey());
            JsonObject b = e.getValue().getAsJsonObject();
            double v = Params.DEFAULTS.get(e.getKey());
            assertTrue(v >= b.get("min").getAsDouble() && v <= b.get("max").getAsDouble(), e.getKey() + " default outside its range");
        }
    }
}
