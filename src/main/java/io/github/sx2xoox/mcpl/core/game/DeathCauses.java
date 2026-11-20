package io.github.sx2xoox.mcpl.core.game;

import java.util.Map;

import io.github.sx2xoox.mcpl.core.ui.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jspecify.annotations.Nullable;

/** Short Korean death causes for the kill feed ({@code death-cause.*} in {@code messages.yml}). */
public final class DeathCauses {

    private static final Map<String, String> BY_DAMAGE_TYPE = Map.ofEntries(
            Map.entry("fall", "fall"),
            Map.entry("ender_pearl", "fall"),
            Map.entry("fly_into_wall", "fall"),
            Map.entry("stalagmite", "fall"),
            Map.entry("out_of_world", "void"),
            Map.entry("outside_border", "border"),
            Map.entry("lava", "lava"),
            Map.entry("in_fire", "fire"),
            Map.entry("on_fire", "fire"),
            Map.entry("campfire", "fire"),
            Map.entry("hot_floor", "fire"),
            Map.entry("fireball", "fire"),
            Map.entry("unattributed_fireball", "fire"),
            Map.entry("drown", "drown"),
            Map.entry("explosion", "explosion"),
            Map.entry("player_explosion", "explosion"),
            Map.entry("fireworks", "explosion"),
            Map.entry("bad_respawn_point", "explosion"),
            Map.entry("magic", "magic"),
            Map.entry("indirect_magic", "magic"),
            Map.entry("sonic_boom", "magic"),
            Map.entry("wither", "wither"),
            Map.entry("wither_skull", "wither"),
            Map.entry("starve", "starve"),
            Map.entry("freeze", "freeze"),
            Map.entry("lightning_bolt", "lightning"),
            Map.entry("cactus", "prick"),
            Map.entry("sweet_berry_bush", "prick"),
            Map.entry("in_wall", "suffocation"),
            Map.entry("cramming", "suffocation"),
            Map.entry("falling_block", "crushed"),
            Map.entry("falling_anvil", "crushed"),
            Map.entry("falling_stalactite", "crushed"),
            Map.entry("arrow", "projectile"),
            Map.entry("trident", "projectile"),
            Map.entry("thrown", "projectile"),
            Map.entry("mob_projectile", "projectile"),
            Map.entry("mob_attack", "mob"),
            Map.entry("mob_attack_no_aggro", "mob"),
            Map.entry("sting", "mob"),
            Map.entry("generic_kill", "killed"));

    private DeathCauses() {
    }

    /** The message key suffix for a damage event ({@code "fall"}, {@code "void"}, ... or {@code "generic"}). */
    public static String keyOf(@Nullable EntityDamageEvent lastDamage) {
        if (lastDamage == null) {
            return "generic";
        }
        String type = lastDamage.getDamageSource().getDamageType().getKey().getKey();
        return BY_DAMAGE_TYPE.getOrDefault(type, "generic");
    }

    /** The localized cause, e.g. {@code "추락"}. */
    public static Component describe(Messages messages, @Nullable EntityDamageEvent lastDamage) {
        String key = "death-cause." + keyOf(lastDamage);
        return messages.has(key) ? messages.get(key) : messages.get("death-cause.generic");
    }
}
