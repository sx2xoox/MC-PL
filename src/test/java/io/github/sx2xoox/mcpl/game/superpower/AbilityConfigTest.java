package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.game.superpower.abilities.BuiltinAbilities;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.config.AbilityConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

class AbilityConfigTest {

    @TempDir
    File folder;

    private AbilityRegistry registry;
    private File file;
    private AbilityConfig config;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        registry = new AbilityRegistry();
        BuiltinAbilities.registerAll(registry);
        file = new File(folder, AbilityConfig.FILE_NAME);
        config = new AbilityConfig(file, Logger.getLogger("AbilityConfigTest"));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void generatedFileListsEveryAbilityAndParamWithComments() throws IOException {
        config.load(registry);
        assertTrue(file.exists());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        for (AbilityRegistry.Entry entry : registry.all()) {
            AbilityInfo info = entry.info();
            String base = "abilities." + info.id();
            assertTrue(yaml.getBoolean(base + ".enabled"), info.id() + " enabled");
            for (Map.Entry<String, Double> param : info.params().entrySet()) {
                String path = base + ".params." + param.getKey();
                assertTrue(yaml.isSet(path), "missing " + path);
                assertEquals(param.getValue(), yaml.getDouble(path), 1.0E-9, path);
            }
            assertTrue(text.contains(info.name() + " (" + info.id() + ")"), "comment for " + info.id());
            assertTrue(text.contains("액티브 " + info.active().name()), "skill comment for " + info.id());
            assertTrue(config.snapshot().isEnabled(info.id()));
        }
        assertEquals(registry.all().size(), config.snapshot().enabledIds().size());
    }

    @Test
    void editsRoundTripAndUnknownEntriesAreKept() throws IOException {
        config.load(registry);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("abilities.pyro.params.cooldown", 3);
        yaml.set("abilities.pyro.params.shots", "5");
        yaml.set("abilities.pyro.params.bogus", 42.0);
        yaml.set("abilities.frost.enabled", false);
        yaml.set("abilities.thunder.params.cooldown", "not a number");
        yaml.set("abilities.aero.params.cooldown", null);
        yaml.set("abilities.unknown_power.enabled", true);
        yaml.set("abilities.unknown_power.params.cooldown", 1.0);
        yaml.save(file);

        config.load(registry);
        AbilityConfig.Snapshot snapshot = config.snapshot();
        AbilityInfo pyro = registry.get("pyro").info();
        AbilityInfo thunder = registry.get("thunder").info();
        AbilityInfo aero = registry.get("aero").info();
        assertEquals(3.0, snapshot.params(pyro).cooldownSeconds(), 1.0E-9);
        assertEquals(5, snapshot.params(pyro).integer("shots"), "numbers written as text are accepted");
        assertFalse(snapshot.params(pyro).has("bogus"), "unknown params are ignored");
        assertFalse(snapshot.isEnabled("frost"));
        assertFalse(snapshot.enabledIds().contains("frost"));
        assertFalse(snapshot.isEnabled("unknown_power"), "unknown abilities are ignored");
        assertEquals(thunder.params().get(AbilityInfo.COOLDOWN), snapshot.params(thunder).cooldownSeconds(), 1.0E-9,
                "invalid numbers fall back to the default");
        assertEquals(aero.params().get(AbilityInfo.COOLDOWN), snapshot.params(aero).cooldownSeconds(), 1.0E-9);

        YamlConfiguration reread = YamlConfiguration.loadConfiguration(file);
        assertEquals(42.0, reread.getDouble("abilities.pyro.params.bogus"), 1.0E-9, "unknown params are kept");
        assertTrue(reread.isSet("abilities.unknown_power.enabled"), "unknown abilities are kept");
        assertEquals(aero.params().get(AbilityInfo.COOLDOWN), reread.getDouble("abilities.aero.params.cooldown"),
                1.0E-9, "missing params are added back with their default");
        assertEquals(3.0, reread.getDouble("abilities.pyro.params.cooldown"), 1.0E-9, "edits survive a reload");
        assertFalse(reread.getBoolean("abilities.frost.enabled"));
    }

    @Test
    void aBrokenFileKeepsThePreviousSettingsAndIsLeftUntouched() throws IOException {
        assertTrue(config.load(registry));
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("abilities.frost.enabled", false);
        yaml.set("abilities.pyro.params.cooldown", 3);
        yaml.save(file);
        assertTrue(config.load(registry));

        String broken = Files.readString(file.toPath(), StandardCharsets.UTF_8) + "\n  bad:\n\t- [unclosed\n";
        Files.writeString(file.toPath(), broken, StandardCharsets.UTF_8);
        assertFalse(config.load(registry), "a YAML error is reported");
        assertFalse(config.snapshot().isEnabled("frost"), "a disabled ability stays disabled");
        assertEquals(3.0, config.snapshot().params(registry.get("pyro").info()).cooldownSeconds(), 1.0E-9,
                "tuned numbers are kept");
        assertEquals(broken, Files.readString(file.toPath(), StandardCharsets.UTF_8), "the file is not touched");

        AbilityConfig fresh = new AbilityConfig(file, Logger.getLogger("AbilityConfigTest"));
        assertFalse(fresh.load(registry));
        assertEquals(registry.all().size(), fresh.snapshot().enabledIds().size(),
                "without earlier settings the defaults are used");
    }

    @Test
    void enabledAcceptsQuotedBooleansAndKeepsInvalidValues() throws IOException {
        config.load(registry);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("abilities.frost.enabled", "false");
        yaml.set("abilities.pyro.enabled", 0);
        yaml.save(file);
        assertTrue(config.load(registry));
        assertFalse(config.snapshot().isEnabled("frost"), "\"false\" turns the ability off");
        assertTrue(config.snapshot().isEnabled("pyro"), "an invalid value keeps the previous state");
        YamlConfiguration reread = YamlConfiguration.loadConfiguration(file);
        assertEquals("false", reread.getString("abilities.frost.enabled"), "the owner's value is not rewritten");
        assertEquals(0, reread.getInt("abilities.pyro.enabled"), "the owner's value is not rewritten");
    }

    @Test
    void unchangedFileIsNotRewritten() throws IOException {
        config.load(registry);
        long modified = file.lastModified();
        String before = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertTrue(file.setLastModified(modified - 10_000));
        config.load(registry);
        assertEquals(modified - 10_000, file.lastModified(), "loading an up-to-date file does not save it");
        assertEquals(before, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }
}
