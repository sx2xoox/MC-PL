package io.github.sx2xoox.mcpl.core.pack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.command.Permissions;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * {@code /게임 리소스팩}: lets players check before a match that the MCPL resource pack is applied. Admins get every
 * online player's {@link ResourcePackService.Status status}; everyone gets their own plus a visual check: for
 * {@link #PREVIEW_TICKS} a {@link #MODEL pack-only model} (a green "OK" cube) floats in front of them next to a label
 * that explains it, both shown to that player only. Without the pack the model is the purple-black missing-model cube
 * (or nothing), which is exactly what tells them it is not applied. Also resends the pack and tells admins at a
 * match start who is missing it ({@link #noticeMissing}).
 */
public final class PackCheck {

    /** The pack-only check model ({@code src/main/resourcepack-src/assets/mcpl/items/pack_check.json}). */
    public static final ModelRef MODEL = new ModelRef("pack_check");
    /** How long the preview stays (ticks). */
    public static final long PREVIEW_TICKS = 200;

    private final JavaPlugin plugin;
    private final ResourcePackService packs;
    private final Messages messages;
    private final Map<UUID, Preview> previews = new HashMap<>();

    private record Preview(List<Entity> entities, @Nullable BukkitTask removal) {
    }

    public PackCheck(JavaPlugin plugin, ResourcePackService packs, Messages messages) {
        this.plugin = plugin;
        this.packs = packs;
        this.messages = messages;
    }

    /** {@code /게임 리소스팩}: the status list for admins, their own status and the preview for players. */
    public void check(CommandSender sender) {
        if (sender.hasPermission(Permissions.ADMIN) || !(sender instanceof Player)) {
            list(sender);
        }
        if (sender instanceof Player player) {
            if (!packs.offered()) {
                messages.send(player, "resource-pack.self-off");
            } else {
                messages.send(player, "resource-pack.self", Messages.tag("status", status(player)),
                        Messages.tag("url", String.valueOf(packs.urlFor(player))));
            }
            preview(player);
            messages.send(player, "resource-pack.check-hint");
        }
    }

    /** Every online player's pack status (and the URL each is sent the pack from). */
    public void list(CommandSender sender) {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        int loaded = 0;
        for (Player player : online) {
            if (packs.hasPack(player)) {
                loaded++;
            }
        }
        messages.send(sender, "resource-pack.list-header", Messages.tag("mode", mode()),
                Messages.tag("loaded", loaded), Messages.tag("total", online.size()));
        if (online.isEmpty()) {
            messages.sendPlain(sender, "resource-pack.list-empty");
        }
        for (Player player : online) {
            String url = packs.urlFor(player);
            messages.sendPlain(sender, "resource-pack.list-entry", Messages.tag("player", player.getName()),
                    Messages.tag("status", status(player)), Messages.tag("url", url == null ? "" : url));
        }
    }

    /** How the pack is served, for the list header. */
    private Component mode() {
        if (!packs.offered()) {
            return messages.get("resource-pack.mode.off");
        }
        ResourcePackSettings.Mode served = packs.servedMode();
        if (served == null) {
            return messages.get("resource-pack.mode.url");
        }
        return served == ResourcePackSettings.Mode.SAME_PORT ? messages.get("resource-pack.mode.same-port")
                : messages.get("resource-pack.mode.separate-port",
                        Messages.tag("port", packs.settings().hostPort()));
    }

    /** The Korean label of {@code player}'s pack status. */
    public Component status(Player player) {
        return messages.get("resource-pack.status." + switch (packs.status(player.getUniqueId())) {
            case LOADED -> "loaded";
            case PENDING -> "pending";
            case DECLINED -> "declined";
            case FAILED -> "failed";
            case NONE -> "none";
        });
    }

    /**
     * Shows {@code player} alone, for {@link #PREVIEW_TICKS}, the check model 2.5 blocks in front of their eyes and a
     * label above it. A new preview replaces the player's previous one.
     *
     * @return the spawned entities (model first, then label)
     */
    public List<Entity> preview(Player player) {
        removePreview(player.getUniqueId());
        Location eye = player.getEyeLocation();
        Vector facing = eye.getDirection().setY(0);
        if (facing.lengthSquared() < 1.0E-4) {
            facing = new Vector(0, 0, 1);
        }
        Location at = eye.clone().add(facing.normalize().multiply(2.5)).add(0, -0.3, 0);
        at.setYaw(eye.getYaw() + 180f);
        at.setPitch(0f);
        World world = at.getWorld();
        List<Entity> spawned = new ArrayList<>(2);
        if (world == null) {
            return spawned;
        }
        spawned.add(world.spawn(at, ItemDisplay.class, display -> {
            prepare(display, player);
            display.setItemStack(MODEL.item());
        }));
        spawned.add(world.spawn(at.clone().add(0, 0.85, 0), TextDisplay.class, label -> {
            prepare(label, player);
            label.text(messages.get("resource-pack.check-label"));
            label.setBillboard(Display.Billboard.CENTER);
        }));
        UUID id = player.getUniqueId();
        BukkitTask removal = Bukkit.getScheduler().runTaskLater(plugin, () -> removePreview(id), PREVIEW_TICKS);
        previews.put(id, new Preview(spawned, removal));
        return spawned;
    }

    /** Only {@code viewer} sees it: hidden by default and shown to them inside the spawn (no flash for others). */
    private void prepare(Display display, Player viewer) {
        display.setPersistent(false);
        display.setVisibleByDefault(false);
        viewer.showEntity(plugin, display);
    }

    private void removePreview(UUID player) {
        Preview preview = previews.remove(player);
        if (preview == null) {
            return;
        }
        if (preview.removal() != null) {
            preview.removal().cancel();
        }
        for (Entity entity : preview.entities()) {
            entity.remove();
        }
    }

    /** Removes every preview (plugin disable). */
    public void removeAll() {
        for (UUID id : List.copyOf(previews.keySet())) {
            removePreview(id);
        }
    }

    /**
     * {@code /게임 리소스팩 다시받기 [플레이어|모두]}: sends the pack again to {@code targets}.
     *
     * @return how many were sent it (0 when the pack is not offered)
     */
    public int resend(Collection<? extends Player> targets) {
        int sent = 0;
        for (Player target : targets) {
            if (packs.resend(target)) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * Tells admins (online with {@link Permissions#ADMIN}, and the console) which of {@code participants} have not
     * loaded the pack, in one line with a clickable {@code /게임 리소스팩}. Nothing when everyone has it or the pack is
     * not offered. Never blocks anything.
     */
    public void noticeMissing(Collection<? extends Player> participants) {
        if (!packs.offered()) {
            return;
        }
        List<Component> missing = new ArrayList<>();
        for (Player player : participants) {
            if (!packs.hasPack(player)) {
                missing.add(Component.text(player.getName()));
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        Component line = messages.prefixed("resource-pack.missing-before-start",
                Messages.tag("count", missing.size()),
                Messages.tag("players", Component.join(JoinConfiguration.commas(true), missing)));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(Permissions.ADMIN)) {
                online.sendMessage(line);
            }
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }
}
