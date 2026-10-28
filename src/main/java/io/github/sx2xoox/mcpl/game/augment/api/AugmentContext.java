package io.github.sx2xoox.mcpl.game.augment.api;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** What an {@link Augment} may use: its owner, its numbers and match helpers that clean up after themselves. */
public interface AugmentContext {

    /** The owner. */
    Player player();

    AugmentInfo info();

    /** The configured numbers ({@code augments.yml}). */
    Params params();

    /** Ticks since the farming phase started. */
    long tick();

    /**
     * True for enemies of the owner: participants in play on another team (or anyone else in free-for-all) and
     * hostile mobs. Never the owner, teammates, spectators or peaceful animals.
     */
    boolean isEnemy(@Nullable Entity entity);

    /** True while players may hurt each other (the fight phase). */
    boolean pvp();

    /**
     * Adds an attribute modifier to the owner, keyed by this augment (one per attribute), removed with the augment.
     * Max health changes keep the health within the new maximum.
     */
    void addModifier(Attribute attribute, double amount, AttributeModifier.Operation operation);

    /** Puts items into the owner's inventory; what does not fit drops at their feet. */
    void give(ItemStack... items);

    /** Removes {@code entity} with the augment (and at the end of the match at the latest). */
    <T extends Entity> T track(T entity);

    /** Sends a line of the augment messages file ({@code augment.yml}) to the owner's action bar. */
    void actionBar(String key, TagResolver... resolvers);

    /** Sends a prefixed line of the augment messages file to the owner's chat. */
    void message(String key, TagResolver... resolvers);

    /**
     * Deals augment damage ({@code MAGIC}, ignores invulnerability frames) credited to the owner (kill credit, stats).
     * Does nothing unless {@code target} is an enemy; the PvP gate still applies. Augment damage never runs the damage
     * hooks of any augment (no loops between reflecting augments).
     */
    void damage(LivingEntity target, double amount);
}
