package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import io.github.sx2xoox.mcpl.game.augment.api.Augment;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentContext;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * One augment owned by one player: the {@link AugmentContext} handed to it, and everything it created (attribute
 * modifiers, tracked entities), removed together in {@link #remove()}.
 */
final class AugmentSlot implements AugmentContext {

    private final AugmentMatch match;
    private final UUID owner;
    private final AugmentInfo info;
    private final Params params;
    private final Augment augment;
    private final Map<Attribute, NamespacedKey> modifiers = new LinkedHashMap<>();
    private final List<Entity> entities = new ArrayList<>();
    private boolean removed;

    AugmentSlot(AugmentMatch match, UUID owner, AugmentInfo info, Params params,
                Function<AugmentContext, Augment> factory) {
        this.match = match;
        this.owner = owner;
        this.info = info;
        this.params = params;
        this.augment = factory.apply(this);
    }

    Augment augment() {
        return augment;
    }

    boolean isRemoved() {
        return removed;
    }

    /** Calls {@code onRemove} (owner online), then removes its modifiers and entities. Idempotent. */
    void remove() {
        if (removed) {
            return;
        }
        removed = true;
        Player player = Bukkit.getPlayer(owner);
        if (player != null) {
            // an offline owner is restored from their snapshot when they come back (plugin modifiers stripped)
            try {
                augment.onRemove();
            } catch (RuntimeException e) {
                match.log("onRemove of " + info.id(), e);
            }
            for (Map.Entry<Attribute, NamespacedKey> entry : modifiers.entrySet()) {
                AttributeInstance instance = player.getAttribute(entry.getKey());
                if (instance != null && instance.getModifier(entry.getValue()) != null) {
                    instance.removeModifier(entry.getValue());
                }
            }
            clampHealth(player);
        }
        modifiers.clear();
        for (Entity entity : entities) {
            if (entity.isValid()) {
                entity.remove();
            }
        }
        entities.clear();
    }

    // =================================================================== AugmentContext

    @Override
    public Player player() {
        Player player = Bukkit.getPlayer(owner);
        if (player == null) {
            throw new IllegalStateException("the owner of " + info.id() + " is offline");
        }
        return player;
    }

    @Override
    public AugmentInfo info() {
        return info;
    }

    @Override
    public Params params() {
        return params;
    }

    @Override
    public long tick() {
        return match.clock();
    }

    @Override
    public boolean isEnemy(@Nullable Entity entity) {
        return match.isEnemy(owner, entity);
    }

    @Override
    public boolean pvp() {
        return match.phase() == MatchPhase.FIGHT;
    }

    @Override
    public void addModifier(Attribute attribute, double amount, AttributeModifier.Operation operation) {
        if (removed || !Double.isFinite(amount)) {
            return;
        }
        Player player = player();
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        NamespacedKey key = match.game().hub().key("augment_" + info.id() + "_"
                + attribute.getKey().getKey().replace('.', '_'));
        if (instance.getModifier(key) != null) {
            instance.removeModifier(key);
        }
        instance.addModifier(new AttributeModifier(key, amount, operation, EquipmentSlotGroup.ANY));
        modifiers.put(attribute, key);
        if (attribute == Attribute.MAX_HEALTH) {
            clampHealth(player);
        }
    }

    private static void clampHealth(Player player) {
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        if (max != null && !player.isDead() && player.getHealth() > max.getValue()) {
            player.setHealth(max.getValue());
        }
    }

    @Override
    public void give(ItemStack... items) {
        if (removed || items.length == 0) {
            return;
        }
        Player player = player();
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(items);
        for (ItemStack rest : leftover.values()) {
            Item dropped = player.getWorld().dropItem(player.getLocation(), rest);
            dropped.setPickupDelay(0);
        }
    }

    @Override
    public <T extends Entity> T track(T entity) {
        if (removed) {
            entity.remove();
            return entity;
        }
        entities.removeIf(e -> !e.isValid());
        entities.add(entity);
        return match.session().track(entity);
    }

    @Override
    public void actionBar(String key, TagResolver... resolvers) {
        Player player = Bukkit.getPlayer(owner);
        if (player != null) {
            match.messages().actionBar(player, key, resolvers);
        }
    }

    @Override
    public void message(String key, TagResolver... resolvers) {
        Player player = Bukkit.getPlayer(owner);
        if (player != null) {
            match.messages().send(player, key, resolvers);
        }
    }
}
