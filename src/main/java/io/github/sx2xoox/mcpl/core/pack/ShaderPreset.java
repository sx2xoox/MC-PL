package io.github.sx2xoox.mcpl.core.pack;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * A named bundle of the pack's core shader effects that the server can serve ({@code resource-pack.shaders},
 * {@code /게임 셰이더 <종류>}). Each preset turns on a set of {@link Feature}s; the pack variant for it carries only the
 * shader files those features need ({@link #files}, plus their includes) and a generated
 * {@code include/mcpl_preset.glsl} that defines the features' macros ({@link #presetInclude}). No preset = the pack
 * without any shader override. A new map's look is a new constant here (and its shader files in the pack sources).
 */
public enum ShaderPreset {

    /** 장난감 왕국 (공성전 v2): the pastel colour grade everywhere in the world + the soap-bubble shield film. */
    TOY_KINGDOM("toy-kingdom", "장난감왕국", EnumSet.of(Feature.COLOR_GRADE, Feature.BUBBLE)),
    /** Only the soap-bubble shield film: the world keeps its vanilla colours. */
    BUBBLE_ONLY("bubble", "보호막만", EnumSet.of(Feature.BUBBLE));

    /** The preset served when the setting is {@code true} or missing. */
    public static final ShaderPreset DEFAULT = TOY_KINGDOM;

    /** Where the pack's shaders are (core shaders and includes below it). */
    public static final String SHADERS = PackBuilder.SHADERS;
    /** The generated include that defines the features' macros. */
    public static final String PRESET_INCLUDE = SHADERS + "include/mcpl_preset.glsl";

    /** One shader effect of the pack: its GLSL macro and the core shaders it overrides. */
    public enum Feature {
        /** The soap-bubble shield film on the marker-alpha texels (include/mcpl_bubble.glsl). */
        BUBBLE("MCPL_BUBBLE", List.of("core/item.fsh")),
        /** The 장난감 왕국 colour grade of the world (include/mcpl_grade.glsl). */
        COLOR_GRADE("MCPL_COLOR_GRADE", List.of("core/terrain.fsh", "core/block.fsh", "core/entity.fsh",
                "core/item.fsh", "core/particle.fsh", "core/sky.fsh", "core/clouds.fsh"));

        private final String macro;
        private final List<String> shaders;

        Feature(String macro, List<String> shaders) {
            this.macro = macro;
            this.shaders = shaders;
        }

        public String macro() {
            return macro;
        }

        /** The core fragment shaders (below {@link #SHADERS}) this feature overrides. */
        public List<String> shaders() {
            return shaders;
        }
    }

    private final String id;
    private final String koreanName;
    private final Set<Feature> features;

    ShaderPreset(String id, String koreanName, Set<Feature> features) {
        this.id = id;
        this.koreanName = koreanName;
        this.features = Set.copyOf(features);
    }

    /** The value in {@code resource-pack.shaders} (e.g. {@code toy-kingdom}). */
    public String id() {
        return id;
    }

    /** The word of {@code /게임 셰이더 <종류>} (no spaces, e.g. {@code 장난감왕국}). */
    public String koreanName() {
        return koreanName;
    }

    /** The {@code messages.yml} key of its line in the {@code /게임 셰이더} list. */
    public String messageKey() {
        return "shader.presets." + id;
    }

    public Set<Feature> features() {
        return features;
    }

    /**
     * The core shaders (paths below {@link #SHADERS}) the features override, sorted; the pack variant adds the vertex
     * shaders paired with them and the includes they need ({@link PackBuilder#withShaders}).
     */
    public SortedSet<String> files() {
        SortedSet<String> files = new TreeSet<>();
        for (Feature feature : Feature.values()) {
            if (features.contains(feature)) {
                files.addAll(feature.shaders());
            }
        }
        return files;
    }

    /** The text of {@code include/mcpl_preset.glsl} for this preset ({@code null} preset: every feature off). */
    public static String presetInclude(@Nullable ShaderPreset preset) {
        StringBuilder out = new StringBuilder();
        out.append("#ifndef MCPL_PRESET_GLSL\n#define MCPL_PRESET_GLSL\n\n");
        out.append("// Written by the MCPL plugin (core/pack/ShaderPreset): the shader preset this pack was served with,\n");
        out.append("// resource-pack.shaders: ").append(preset == null ? "off" : preset.id()).append('\n');
        for (Feature feature : Feature.values()) {
            if (preset != null && preset.features.contains(feature)) {
                out.append("#define ").append(feature.macro()).append('\n');
            }
        }
        out.append("\n#endif\n");
        return out.toString();
    }

    /** What {@code resource-pack.shaders} (or a {@code /게임 셰이더} word) says. */
    public sealed interface Choice {
        /** A preset is served. */
        record On(ShaderPreset preset) implements Choice {
        }

        /** No shader overrides. */
        record Off() implements Choice {
        }

        /** Not a known word. */
        record Unknown(String input) implements Choice {
        }
    }

    /**
     * Reads a setting or a command word: {@code true}/missing = {@link #DEFAULT}, {@code false}/{@code off}/
     * {@code 끄기} = off, a preset's id or Korean name (spaces, case and {@code _}/{@code -} ignored).
     */
    public static Choice parse(@Nullable Object value) {
        if (value == null || Boolean.TRUE.equals(value)) {
            return new Choice.On(DEFAULT);
        }
        if (Boolean.FALSE.equals(value)) {
            return new Choice.Off();
        }
        String word = normalize(value.toString());
        switch (word) {
            case "true", "on", "켜기" -> {
                return new Choice.On(DEFAULT);
            }
            case "false", "off", "none", "끄기", "없음" -> {
                return new Choice.Off();
            }
            default -> {
            }
        }
        for (ShaderPreset preset : values()) {
            if (word.equals(normalize(preset.id)) || word.equals(normalize(preset.koreanName))) {
                return new Choice.On(preset);
            }
        }
        return new Choice.Unknown(value.toString());
    }

    private static String normalize(String word) {
        return word.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
    }
}
