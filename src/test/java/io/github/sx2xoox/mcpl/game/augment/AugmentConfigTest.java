package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentRegistry;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.augments.BuiltinAugments;
import io.github.sx2xoox.mcpl.game.augment.augments.IronBodyAugment;
import io.github.sx2xoox.mcpl.game.augment.bonus.BonusCondition;
import io.github.sx2xoox.mcpl.game.augment.config.AugmentConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code augments.yml}: generated entries, overrides, invalid values and a broken file. */
class AugmentConfigTest {

    @TempDir
    Path dir;
    private AugmentRegistry registry;
    private File file;
    private AugmentConfig config;

    @BeforeEach
    void setUp() {
        registry = new AugmentRegistry();
        BuiltinAugments.registerAll(registry);
        file = dir.resolve(AugmentConfig.FILE_NAME).toFile();
        config = new AugmentConfig(file, Logger.getLogger("test"));
    }

    @Test
    void theFileIsGeneratedWithEveryAugmentAndCondition() throws IOException {
        assertTrue(config.load(registry));
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        for (AugmentRegistry.Entry entry : registry.all()) {
            assertTrue(text.contains(entry.info().id() + ":"), entry.info().id());
            assertTrue(text.contains(entry.info().name()), "commented with the name");
        }
        for (BonusCondition condition : BonusCondition.values()) {
            assertTrue(text.contains(condition.id() + ":"), condition.id());
        }
        assertTrue(text.contains("기본값 4"), "inline default comments");
        assertEquals(4.0, config.snapshot().params(IronBodyAugment.INFO).num("health"));
        assertEquals(BonusCondition.FIRST_BLOOD.defaultTier(), config.snapshot().tier(BonusCondition.FIRST_BLOOD));
    }

    @Test
    void overridesAndInvalidValues() throws IOException {
        config.load(registry);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("augments.iron_body.params.health", 8);
        yaml.set("augments.gale.params.speed", "fast");
        yaml.set("augments.gale.enabled", false);
        yaml.set("bonuses.first_blood.tier", "PRISM");
        yaml.set("bonuses.mage_path.tier", "rainbow");
        yaml.set("bonuses.monster_hunter.enabled", false);
        yaml.set("bonuses.monster_hunter.params.kills", 3);
        yaml.save(file);
        assertTrue(config.load(registry));
        AugmentConfig.Snapshot snapshot = config.snapshot();
        assertEquals(8.0, snapshot.params(IronBodyAugment.INFO).num("health"));
        assertEquals(15.0, snapshot.params(registry.get("gale").info()).num("speed"), "invalid → default");
        assertFalse(snapshot.isEnabled("gale"));
        assertEquals(AugmentTier.PRISM, snapshot.tier(BonusCondition.FIRST_BLOOD));
        assertEquals(BonusCondition.MAGE_PATH.defaultTier(), snapshot.tier(BonusCondition.MAGE_PATH));
        assertFalse(snapshot.isEnabled(BonusCondition.MONSTER_HUNTER));
        assertEquals(3, snapshot.params(BonusCondition.MONSTER_HUNTER).integer("kills"));
        assertEquals("fast", YamlConfiguration.loadConfiguration(file).getString("augments.gale.params.speed"),
                "the owner's value stays in the file");
    }

    @Test
    void aBrokenFileKeepsThePreviousSettingsAndIsLeftAlone() throws IOException {
        config.load(registry);
        config.setEnabled(registry, "gale", false);
        Files.writeString(file.toPath(), "augments: [oops\n", StandardCharsets.UTF_8);
        assertFalse(config.load(registry));
        assertFalse(config.snapshot().isEnabled("gale"), "previous settings kept");
        assertEquals("augments: [oops\n", Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }
}
