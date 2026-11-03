package io.github.sx2xoox.mcpl.core.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Builds the MCPL resource pack from its source folder ({@code src/main/resourcepack-src}); run at build time by the
 * Gradle {@code resourcePack} task through {@link #main} (it only needs the JDK and Gson). Taken from STSV's builder.
 *
 * <p>Source layout:
 * <pre>
 *   models/&lt;id&gt;.bbmodel   Blockbench models (made by tools/assetgen or by hand), converted by {@link BBModelConverter}
 *   assets/...             copied as-is
 * </pre>
 * Output, per model: {@code assets/mcpl/models/item/<id>.json}, its textures under
 * {@code assets/mcpl/textures/item/<id>/}, and the item model definition {@code assets/mcpl/items/<id>.json} that an
 * {@code item_model} data component ({@link ModelRef}) points at; a model with tinted faces gets one
 * {@code minecraft:dye} tint source (the item's {@code dyed_color}, e.g. a team colour). Plus {@code pack.mcmeta}.
 *
 * <p>The zip is reproducible (sorted entries, fixed timestamps), so the same sources always give the same SHA-1 and
 * clients only download it again when something changed.
 */
public final class PackBuilder {

    /** The 26.3 resource pack format (pack.mcmeta {@code min_format} / {@code max_format}; no {@code pack_format}). */
    public static final int PACK_FORMAT = 97;
    public static final String NAMESPACE = ModelRef.NAMESPACE;
    /** Untinted look of a tintable part when the item has no {@code dyed_color} (white = the texture as drawn). */
    public static final int DEFAULT_TINT = 0xFFFFFF;

    /**
     * @param files    every file of the pack, sorted by path
     * @param warnings conversion problems (the Gradle task fails on any)
     */
    public record Built(byte[] zip, String sha1, SortedMap<String, byte[]> files, List<String> warnings) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

    private final SortedMap<String, byte[]> files = new TreeMap<>();
    private final List<String> warnings = new ArrayList<>();

    private PackBuilder() {
    }

    /** Builds the pack from {@code source} (see the class comment). */
    public static Built build(Path source, String description) throws IOException {
        PackBuilder builder = new PackBuilder();
        builder.run(source, description);
        byte[] zip = builder.zip();
        return new Built(zip, ResourcePackService.sha1(zip), Collections.unmodifiableSortedMap(builder.files),
                List.copyOf(builder.warnings));
    }

    /** {@code PackBuilder <source folder> <output zip>}: fails (exit 1) on any warning, so broken models never ship. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: PackBuilder <source folder> <output zip>");
        }
        Built built = build(Path.of(args[0]), "MCPL: 3D models (공성전 타워 등)");
        if (!built.warnings().isEmpty()) {
            built.warnings().forEach(w -> System.err.println("resource pack: " + w));
            System.exit(1);
        }
        Path out = Path.of(args[1]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, built.zip());
        System.out.println("resource pack: " + built.files().size() + " files, " + built.zip().length / 1024
                + " KB, sha1 " + built.sha1());
    }

    private void run(Path source, String description) throws IOException {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", description);
        pack.addProperty("min_format", PACK_FORMAT);
        pack.addProperty("max_format", PACK_FORMAT);
        JsonObject mcmeta = new JsonObject();
        mcmeta.add("pack", pack);
        json("pack.mcmeta", mcmeta);

        Path assets = source.resolve("assets");
        if (Files.isDirectory(assets)) {
            try (Stream<Path> s = Files.walk(assets)) {
                for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                    put("assets/" + slash(assets.relativize(p)), Files.readAllBytes(p));
                }
            }
        }
        Path models = source.resolve("models");
        if (Files.isDirectory(models)) {
            try (Stream<Path> s = Files.list(models)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".bbmodel")).sorted().toList()) {
                    convert(p);
                }
            }
        }
    }

    private void convert(Path file) throws IOException {
        String id = BBModelConverter.sanitize(file.getFileName().toString().replaceFirst("\\.bbmodel$", ""));
        BBModelConverter.Result r = BBModelConverter.convert(file, NAMESPACE + ":item/" + id);
        r.warnings().forEach(w -> warnings.add(file.getFileName() + ": " + w));
        json("assets/" + NAMESPACE + "/models/item/" + id + ".json", r.model());
        for (Map.Entry<String, byte[]> t : r.textures().entrySet()) {
            put("assets/" + NAMESPACE + "/textures/item/" + id + "/" + t.getKey(), t.getValue());
        }
        json("assets/" + NAMESPACE + "/items/" + id + ".json", itemDefinition(id, BBModelConverter.tintCount(r.model())));
    }

    /** {@code {"model": {"type": "minecraft:model", "model": "mcpl:item/<id>", "tints": [dye...]}}}. */
    static JsonObject itemDefinition(String id, int tints) {
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", NAMESPACE + ":item/" + id);
        if (tints > 0) {
            JsonArray sources = new JsonArray();
            for (int i = 0; i < tints; i++) {
                JsonObject dye = new JsonObject();
                dye.addProperty("type", "minecraft:dye");
                dye.addProperty("default", DEFAULT_TINT);
                sources.add(dye);
            }
            model.add("tints", sources);
        }
        JsonObject def = new JsonObject();
        def.add("model", model);
        return def;
    }

    private void json(String path, JsonObject obj) {
        put(path, (GSON.toJson(obj) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private void put(String path, byte[] data) {
        if (files.containsKey(path)) {
            warnings.add("two sources write " + path);
        }
        files.put(path, data);
    }

    private byte[] zip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setLevel(9);
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                ZipEntry e = new ZipEntry(f.getKey());
                e.setTimeLocal(FIXED_TIME); // no time zone in play: identical content -> identical hash anywhere
                zip.putNextEntry(e);
                zip.write(f.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String slash(Path relative) {
        return relative.toString().replace('\\', '/');
    }
}
