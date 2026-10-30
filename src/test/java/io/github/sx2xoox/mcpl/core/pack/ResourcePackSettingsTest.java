package io.github.sx2xoox.mcpl.core.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import io.github.sx2xoox.mcpl.game.augment.AugmentSettings.OfferUi;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.junit.jupiter.api.Test;

/** {@code resource-pack.*} parsing, the download URL and hash, client statuses and the PACK → DIALOG fallback. */
class ResourcePackSettingsTest {

    @Test
    void theShippedConfigLeavesThePackOff() throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            config.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        ResourcePackSettings settings = ResourcePackSettings.read(config.getConfigurationSection("resource-pack"));
        assertFalse(settings.enabled());
        assertTrue(settings.selfHosted());
        assertEquals(ResourcePackSettings.DEFAULT_PORT, settings.hostPort());
        assertEquals(ResourcePackSettings.AUTO, settings.sha1());
        assertEquals(ResourcePackSettings.DISABLED, ResourcePackSettings.read(null));
    }

    @Test
    void readsAndValidatesTheSection() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("resource-pack.enabled", true);
        config.set("resource-pack.url", "  https://example.com/MCPL-pack.zip ");
        config.set("resource-pack.host-port", 99999);
        config.set("resource-pack.sha1", " ABCDEF ");
        ResourcePackSettings settings = ResourcePackSettings.read(config.getConfigurationSection("resource-pack"));
        assertTrue(settings.enabled());
        assertFalse(settings.selfHosted());
        assertEquals(ResourcePackSettings.DEFAULT_PORT, settings.hostPort(), "an invalid port falls back");
        assertEquals("https://example.com/MCPL-pack.zip", settings.downloadUrl("ignored.zip", ""));
        assertNull(settings.hashToSend("0123"), "not a 40-digit SHA-1: no hash check");
    }

    @Test
    void theBuiltInServerUrlNeedsAnAddress() {
        ResourcePackSettings settings = new ResourcePackSettings(true, "", 8164, "", "auto");
        assertNull(settings.downloadUrl("p.zip", ""), "no public address and no server-ip");
        assertNull(settings.downloadUrl("p.zip", "0.0.0.0"));
        assertEquals("http://10.0.0.5:8164/p.zip", settings.downloadUrl("p.zip", "10.0.0.5"));
        ResourcePackSettings named = new ResourcePackSettings(true, "", 25599, "mc.example.com", "auto");
        assertEquals("http://mc.example.com:25599/p.zip", named.downloadUrl("p.zip", "10.0.0.5"));
        ResourcePackSettings v6 = new ResourcePackSettings(true, "", 8164, "2001:db8::1", "auto");
        assertEquals("http://[2001:db8::1]:8164/p.zip", v6.downloadUrl("p.zip", ""));
        String sha1 = "a".repeat(40);
        assertEquals(sha1, settings.hashToSend(sha1), "auto = the bundled pack's hash");
        assertNull(new ResourcePackSettings(true, "", 8164, "", "").hashToSend(sha1));
        assertEquals("b".repeat(40), new ResourcePackSettings(true, "", 8164, "", "B".repeat(40)).hashToSend(sha1));
    }

    @Test
    void clientReportsMapToStatuses() {
        assertEquals(ResourcePackService.Status.LOADED,
                ResourcePackService.Status.of(PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
        assertEquals(ResourcePackService.Status.DECLINED,
                ResourcePackService.Status.of(PlayerResourcePackStatusEvent.Status.DECLINED));
        assertEquals(ResourcePackService.Status.PENDING,
                ResourcePackService.Status.of(PlayerResourcePackStatusEvent.Status.ACCEPTED));
        assertEquals(ResourcePackService.Status.PENDING,
                ResourcePackService.Status.of(PlayerResourcePackStatusEvent.Status.DOWNLOADED));
        for (PlayerResourcePackStatusEvent.Status failed : new PlayerResourcePackStatusEvent.Status[] {
                PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD, PlayerResourcePackStatusEvent.Status.INVALID_URL,
                PlayerResourcePackStatusEvent.Status.FAILED_RELOAD, PlayerResourcePackStatusEvent.Status.DISCARDED}) {
            assertEquals(ResourcePackService.Status.FAILED, ResourcePackService.Status.of(failed));
        }
    }

    @Test
    void playersWithoutThePackGetTheDialog() {
        assertEquals(OfferUi.PACK, OfferUi.PACK.resolve(true));
        assertEquals(OfferUi.DIALOG, OfferUi.PACK.resolve(false));
        assertEquals(OfferUi.CHEST, OfferUi.CHEST.resolve(false));
        assertEquals(OfferUi.DIALOG, OfferUi.DIALOG.resolve(true));
        assertEquals(OfferUi.PACK, OfferUi.parse("pack"));
    }

    @Test
    void thePackIsBundledWithItsMetadata() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/" + ResourcePackService.BUNDLED)) {
            assertTrue(in != null, "./gradlew resourcePack is bundled by processResources");
            boolean meta = false;
            boolean font = false;
            try (ZipInputStream zip = new ZipInputStream(in)) {
                for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    meta |= entry.getName().equals("pack.mcmeta");
                    font |= entry.getName().equals("assets/mcpl/font/offer.json");
                }
            }
            assertTrue(meta);
            assertTrue(font);
        }
        assertEquals(40, ResourcePackService.sha1(new byte[] {1, 2, 3}).length());
    }
}
