package io.github.sx2xoox.speedrunbot.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tunable decision numbers (weights, thresholds, budgets). Every value has a code default; {@code params.json} only
 * overrides. The tuner (tools/tune.py) searches over the keys listed in {@link #DEFAULTS} with the bounds in
 * {@code tools/param_space.json}.
 */
public final class Params {
    public static final Map<String, Double> DEFAULTS;

    static {
        Map<String, Double> d = new LinkedHashMap<>();
        // --- wood ---
        d.put("wood.logs", 7.0);                 // logs before crafting (table + pick + sticks + spare planks)
        d.put("wood.maxSearchTicks", 1200.0);    // give up looking for trees after this
        d.put("craft.tableReuseDist", 6.0);     // walk back to a known table only within this distance
        // --- stone ---
        d.put("stone.cobble", 12.0);             // cobble to collect (pick 3 + furnace 8 + spare)
        d.put("stone.picks", 2.0);              // stone pickaxes crafted at once (one wears out after 131 blocks)
        // --- descent / underground search ---
        d.put("dig.targetY", 12.0);              // feet Y of the mining tunnel (cave lava is at y <= 10)
        d.put("dig.oreDetour", 7.0);             // max detour (blocks) to mine a seen ore while digging
        d.put("dig.tunnelMax", 220.0);           // give up the tunnel after this many blocks
        d.put("dig.gravelDetour", 10.0);         // max detour for gravel (flint) while digging
        d.put("iron.needed", 4.0);               // bucket (3) + flint and steel (1)
        d.put("gravel.maxBreaks", 14.0);         // gravel to break for flint before giving up
        // --- surface route ---
        d.put("surface.lavaMaxDist", 72.0);      // commit to a surface lava pool seen within this distance
        d.put("surface.scanTicks", 60.0);        // look-around ticks at spawn before choosing a route
        // --- route selector (expected seconds model) ---
        d.put("route.digBaseSec", 150.0);
        d.put("route.surfaceBaseSec", 120.0);
        d.put("route.secPerBlock", 0.22);        // walking cost (sprint ~5.6 b/s plus terrain)
        d.put("route.ironMissingSec", 25.0);     // cost of each missing iron on the surface route
        d.put("route.noWaterSec", 30.0);         // penalty when no water is known
        // --- portal site ---
        d.put("portal.maxLavaDist", 9.0);        // site must be within this distance of a lava source
        d.put("portal.waterSearchDist", 64.0);   // max distance to walk back for water
        // --- safety ---
        d.put("safety.lavaMargin", 1.0);         // blocks kept between feet and lava when pathing
        d.put("safety.fleeHealth", 8.0);         // retreat from mobs below this health
        d.put("food.eatBelow", 12.0);            // eat when food level below this (if food carried)
        DEFAULTS = Collections.unmodifiableMap(d);
    }

    private final Map<String, Double> values = new LinkedHashMap<>(DEFAULTS);

    public static Params defaults() {
        return new Params();
    }

    public static Params load(Path file) throws IOException {
        Params p = new Params();
        if (file != null && Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.merge(new JsonParser().parse(r).getAsJsonObject());
            }
        }
        return p;
    }

    public static Params fromJson(String json) {
        Params p = new Params();
        p.merge(new JsonParser().parse(json).getAsJsonObject());
        return p;
    }

    private void merge(JsonObject o) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
                values.put(e.getKey(), e.getValue().getAsDouble());
            }
        }
    }

    public double get(String key) {
        Double v = values.get(key);
        if (v == null) {
            throw new IllegalArgumentException("unknown param " + key);
        }
        return v;
    }

    public int getInt(String key) {
        return (int) Math.round(get(key));
    }

    public Params with(String key, double value) {
        values.put(key, value);
        return this;
    }

    public Map<String, Double> asMap() {
        return Collections.unmodifiableMap(values);
    }

    public String toJson() {
        Gson g = new GsonBuilder().setPrettyPrinting().create();
        return g.toJson(values);
    }
}
