package io.github.sx2xoox.mcpl.core.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.game.siege.FallbackLaneSites;
import io.github.sx2xoox.mcpl.game.siege.SiegeGame;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code /게임 리소스팩}: the status list, the per-viewer visual check, resending, the notice at a match start. */
class PackCheckTest {

    private static final String URL = "https://example.com/MCPL-pack.zip";

    private MatchHarness h;
    private TestPlayer admin;
    private TestPlayer player;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
        // An external URL: nothing is hosted in the test, every player is sent this address.
        h.hub.plugin().getConfig().set("resource-pack.enabled", true);
        h.hub.plugin().getConfig().set("resource-pack.url", URL);
        h.hub.plugin().saveConfig();
        h.hub.config().load();
        h.hub.resourcePacks().reload();
        admin = h.join("Admin");
        admin.setOp(true);
        player = h.join("Player");
        drain(admin);
        drain(player);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private void report(TestPlayer who, PlayerResourcePackStatusEvent.Status status) {
        h.server.getPluginManager().callEvent(
                new PlayerResourcePackStatusEvent(who, ResourcePackService.PACK_ID, status));
    }

    private static List<String> drain(TestPlayer who) {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = who.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return lines;
    }

    private static boolean any(List<String> lines, String... parts) {
        return lines.stream().anyMatch(line -> {
            for (String part : parts) {
                if (!line.contains(part)) {
                    return false;
                }
            }
            return true;
        });
    }

    private List<Entity> previewEntities() {
        return h.flatWorld().getEntities().stream()
                .filter(e -> e instanceof ItemDisplay || e instanceof TextDisplay).toList();
    }

    @Test
    void joiningPlayersAreSentThePack() {
        assertEquals(1, player.packRequests().size());
        assertEquals(URL, player.packRequests().getFirst().packs().getFirst().uri().toString());
        assertEquals(ResourcePackService.Status.PENDING, h.hub.resourcePacks().status(player.getUniqueId()));
    }

    @Test
    void adminsSeeEveryonesStatus() {
        report(admin, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        report(player, PlayerResourcePackStatusEvent.Status.DECLINED);
        assertTrue(h.server.dispatchCommand(admin, "게임 리소스팩"));
        List<String> lines = drain(admin);
        assertTrue(any(lines, "직접 올린 주소", "1", "2"), String.join("\n", lines));
        assertTrue(any(lines, "Admin", "적용됨"), String.join("\n", lines));
        assertTrue(any(lines, "Player", "거절", URL), String.join("\n", lines));

        assertTrue(h.server.dispatchCommand(player, "mcpl resourcepack"));
        List<String> own = drain(player);
        assertFalse(any(own, "Admin"), "players only see their own status");
        assertTrue(any(own, "내 리소스팩", "거절"), String.join("\n", own));
    }

    @Test
    void theVisualCheckIsShownToThatPlayerAloneAndGoesAway() {
        assertTrue(h.server.dispatchCommand(player, "게임 리소스팩 확인"));
        List<Entity> shown = previewEntities();
        assertEquals(2, shown.size(), "the model and its label");
        ItemDisplay model = (ItemDisplay) shown.stream().filter(ItemDisplay.class::isInstance).findFirst().orElseThrow();
        assertEquals(PackCheck.MODEL.item(), model.getItemStack(), "the pack-only check model");
        for (Entity entity : shown) {
            assertTrue(player.canSee(entity), "the player sees the check");
            assertFalse(admin.canSee(entity), "nobody else does");
            assertFalse(entity.isPersistent());
        }
        assertTrue(model.getLocation().distance(player.getEyeLocation()) < 3.5, "right in front of them");
        assertTrue(any(drain(player), "초록 OK 상자"));

        assertTrue(h.server.dispatchCommand(player, "게임 리소스팩"));
        assertEquals(2, previewEntities().size(), "a new check replaces the old one");
        h.ticks((int) PackCheck.PREVIEW_TICKS + 1);
        assertTrue(previewEntities().isEmpty(), "removed after 10 seconds");
    }

    @Test
    void resendingThePack() {
        report(player, PlayerResourcePackStatusEvent.Status.DECLINED);
        assertTrue(h.server.dispatchCommand(player, "게임 리소스팩 다시받기"));
        assertEquals(2, player.packRequests().size());
        assertEquals(ResourcePackService.Status.PENDING, h.hub.resourcePacks().status(player.getUniqueId()));
        h.server.dispatchCommand(player, "게임 리소스팩 다시받기 모두");
        assertEquals(1, admin.packRequests().size(), "only admins pick others");
        assertEquals(2, player.packRequests().size());

        assertTrue(h.server.dispatchCommand(admin, "게임 리소스팩 다시받기 모두"));
        assertEquals(2, admin.packRequests().size());
        assertEquals(3, player.packRequests().size());
        assertTrue(h.server.dispatchCommand(admin, "mcpl resourcepack resend Player"));
        assertEquals(4, player.packRequests().size());
        drain(admin);
        h.server.dispatchCommand(admin, "게임 리소스팩 다시받기 Nobody");
        assertTrue(any(drain(admin), "Nobody"));
    }

    @Test
    void adminsHearWhoLacksThePackWhenTheSiegeStarts() {
        report(admin, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        SiegeGame siege = (SiegeGame) h.hub.games().game(SiegeGame.ID);
        assertNotNull(siege);
        assertTrue(siege.usesResourcePack());
        siege.sitesOverride(new FallbackLaneSites((int) FlatWorld.SURFACE));
        assertTrue(h.server.dispatchCommand(admin, "게임 시작 공성전"));
        assertNotNull(h.hub.games().session(), "the start is not blocked");
        List<String> lines = drain(admin);
        assertTrue(any(lines, "적용되지 않은 참가자 1명", "Player", "/게임 리소스팩"), String.join("\n", lines));
        assertFalse(any(lines, "적용되지 않은", "Admin"));
        assertFalse(any(drain(player), "적용되지 않은"), "players are not told");
    }

    @Test
    void nothingIsSentWhenThePackIsOff() {
        h.hub.plugin().getConfig().set("resource-pack.enabled", false);
        h.hub.plugin().saveConfig();
        h.hub.config().load();
        h.hub.resourcePacks().reload();
        drain(player);
        assertTrue(h.server.dispatchCommand(player, "게임 리소스팩"));
        assertTrue(any(drain(player), "보내지 않습니다"));
        h.server.dispatchCommand(player, "게임 리소스팩 다시받기");
        assertEquals(1, player.packRequests().size(), "nothing resent");
    }
}
