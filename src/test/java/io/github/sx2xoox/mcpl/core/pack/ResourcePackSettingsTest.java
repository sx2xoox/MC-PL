package io.github.sx2xoox.mcpl.core.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** {@code resource-pack.*} parsing, the per-player download URL and hash, and client statuses. */
class ResourcePackSettingsTest {

    @Test
    void theShippedConfigSendsThePackFromTheGamePort() throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            config.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        ResourcePackSettings settings = ResourcePackSettings.read(config.getConfigurationSection("resource-pack"));
        assertTrue(settings.enabled(), "on by default: no setup needed");
        assertEquals(ResourcePackSettings.Mode.SAME_PORT, settings.mode());
        assertFalse(settings.required());
        assertTrue(settings.selfHosted());
        assertEquals("", settings.publicAddress());
        assertEquals(ResourcePackSettings.DEFAULT_PORT, settings.hostPort());
        assertEquals(ResourcePackSettings.AUTO, settings.sha1());
        assertEquals(ShaderPreset.TOY_KINGDOM, settings.shaders(), "the 장난감 왕국 shaders, always on");
        assertEquals(ResourcePackSettings.DISABLED, ResourcePackSettings.read(null));
    }

    @Test
    void readsAndValidatesTheSection() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("resource-pack.url", "  https://example.com/MCPL-pack.zip ");
        config.set("resource-pack.mode", "separate-port");
        config.set("resource-pack.host-port", 99999);
        config.set("resource-pack.sha1", " ABCDEF ");
        config.set("resource-pack.required", true);
        ResourcePackSettings settings = ResourcePackSettings.read(config.getConfigurationSection("resource-pack"));
        assertTrue(settings.enabled(), "a missing enabled key means on");
        assertTrue(settings.required());
        assertFalse(settings.selfHosted());
        assertEquals(ResourcePackSettings.Mode.SEPARATE_PORT, settings.mode());
        assertEquals(ResourcePackSettings.DEFAULT_PORT, settings.hostPort(), "an invalid port falls back");
        assertEquals("https://example.com/MCPL-pack.zip",
                settings.downloadUrl("ignored.zip", true, "mc.example.com", 25565, "", 25565));
        assertNull(settings.hashToSend("0123"), "not a 40-digit SHA-1: no hash check");
        config.set("resource-pack.mode", "nonsense");
        assertEquals(ResourcePackSettings.Mode.SAME_PORT,
                ResourcePackSettings.read(config.getConfigurationSection("resource-pack")).mode());
    }

    /** {@code resource-pack.shaders}: a preset id or Korean name, off, or the old boolean; anything else = default. */
    @Test
    void readsTheShaderPresetAndTheOldBoolean() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("resource-pack.enabled", true);
        assertEquals(ShaderPreset.DEFAULT, shaders(config), "missing = the default preset");
        config.set("resource-pack.shaders", true);
        assertEquals(ShaderPreset.TOY_KINGDOM, shaders(config), "the old true");
        config.set("resource-pack.shaders", false);
        assertNull(shaders(config), "the old false");
        config.set("resource-pack.shaders", "off");
        assertNull(shaders(config));
        config.set("resource-pack.shaders", "끄기");
        assertNull(shaders(config));
        config.set("resource-pack.shaders", "bubble");
        assertEquals(ShaderPreset.BUBBLE_ONLY, shaders(config));
        config.set("resource-pack.shaders", " Toy_Kingdom ");
        assertEquals(ShaderPreset.TOY_KINGDOM, shaders(config));
        config.set("resource-pack.shaders", "장난감 왕국");
        assertEquals(ShaderPreset.TOY_KINGDOM, shaders(config));
        config.set("resource-pack.shaders", "nonsense");
        assertEquals(ShaderPreset.DEFAULT, shaders(config));
        assertTrue(ShaderPreset.parse("nonsense") instanceof ShaderPreset.Choice.Unknown);
        for (ShaderPreset preset : ShaderPreset.values()) {
            assertEquals(new ShaderPreset.Choice.On(preset), ShaderPreset.parse(preset.id()));
            assertEquals(new ShaderPreset.Choice.On(preset), ShaderPreset.parse(preset.koreanName()));
            assertEquals(preset, selfHosted("").withShaders(preset).shaders());
        }
    }

    private static @Nullable ShaderPreset shaders(YamlConfiguration config) {
        return ResourcePackSettings.read(config.getConfigurationSection("resource-pack")).shaders();
    }

    private static ResourcePackSettings selfHosted(String publicAddress) {
        return new ResourcePackSettings(true, ResourcePackSettings.Mode.SAME_PORT, "", 8164, publicAddress, "auto",
                false);
    }

    @Test
    void theGamePortUrlFollowsTheAddressThePlayerConnectedWith() {
        ResourcePackSettings settings = selfHosted("");
        assertEquals("http://127.0.0.1:25599/p.zip", settings.downloadUrl("p.zip", true, "127.0.0.1", 25599, "", 25599));
        assertEquals("http://mc.example.com:25570/p.zip",
                settings.downloadUrl("p.zip", true, "mc.example.com.", 25570, "", 25599),
                "the port they typed (port forwarding), trailing DNS dot dropped");
        assertEquals("http://mc.example.com:25599/p.zip",
                settings.downloadUrl("p.zip", true, "mc.example.com\0FML3\0", 25565, "", 25599),
                "25565 = typed without a port (SRV): the server's port; mod loader markers dropped");
        assertEquals("http://10.0.0.5:25599/p.zip", settings.downloadUrl("p.zip", true, null, -1, "10.0.0.5", 25599),
                "unknown connect address: server-ip");
        assertNull(settings.downloadUrl("p.zip", true, null, -1, "", 25599), "no address at all");
        assertNull(settings.downloadUrl("p.zip", true, "", 25599, "0.0.0.0", 25599));
        assertEquals("http://[2001:db8::1]:25599/p.zip",
                settings.downloadUrl("p.zip", true, "2001:db8::1", 25599, "", 25599));
    }

    @Test
    void theSeparatePortAndAPublicAddressOverride() {
        ResourcePackSettings settings = selfHosted("");
        assertEquals("http://127.0.0.1:8164/p.zip", settings.downloadUrl("p.zip", false, "127.0.0.1", 25599, "", 25599),
                "separate port: the connect host with host-port");
        assertEquals("http://mc.example.com:25599/p.zip",
                selfHosted("mc.example.com").downloadUrl("p.zip", true, "127.0.0.1", 25570, "", 25599));
        assertEquals("http://mc.example.com:8164/p.zip",
                selfHosted("mc.example.com").downloadUrl("p.zip", false, "127.0.0.1", 25570, "", 25599));
        assertEquals("http://mc.example.com:30000/p.zip",
                selfHosted("mc.example.com:30000").downloadUrl("p.zip", true, "127.0.0.1", 25570, "", 25599));
        assertEquals("http://[2001:db8::1]:8164/p.zip",
                selfHosted("2001:db8::1").downloadUrl("p.zip", false, null, -1, "", 25599));
        String sha1 = "a".repeat(40);
        assertEquals(sha1, settings.hashToSend(sha1), "auto = the bundled pack's hash");
        ResourcePackSettings noCheck = new ResourcePackSettings(true, ResourcePackSettings.Mode.SAME_PORT, "", 8164,
                "", "", false);
        assertNull(noCheck.hashToSend(sha1));
        ResourcePackSettings fixed = new ResourcePackSettings(true, ResourcePackSettings.Mode.SAME_PORT, "", 8164, "",
                "B".repeat(40), false);
        assertEquals("b".repeat(40), fixed.hashToSend(sha1));
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
        assertEquals(40, ResourcePackService.sha1(new byte[] {1, 2, 3}).length());
    }
}
