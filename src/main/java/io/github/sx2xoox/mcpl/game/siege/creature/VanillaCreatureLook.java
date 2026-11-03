package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.function.Consumer;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Endermite;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Panda;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The look of 공성전 v2 creatures without the resource pack: cute, clearly different vanilla mobs.
 *
 * <ul>
 *     <li>minions: baby zombie (태엽 병정) with a wooden sword, small skeleton (장난감 궁수) with a bow, both in leather
 *     dyed in the team colour; a small iron golem (장난감 대포);</li>
 *     <li>젤리 = small slimes, 태엽 쥐 = silverfish and endermites, 곰인형 대장 = a brown panda, 시계 토끼 = a big gold
 *     rabbit, 장난감 드래곤 = a ravager; 태엽 상인 = a villager.</li>
 * </ul>
 *
 * Sizes come from the settings ({@code Attribute.SCALE}, set by the systems' configure step).
 */
public final class VanillaCreatureLook implements CreatureLook {

    public static final Color BLUE = Color.fromRGB(0x4F, 0x8D, 0xF5);
    public static final Color RED = Color.fromRGB(0xEF, 0x5A, 0x6F);

    /** Counts mice so the swarm alternates silverfish and endermites. */
    private int mice;

    @Override
    public Mob spawn(CreatureKind kind, LaneSites.@Nullable Side side, Location at, Consumer<Mob> configure) {
        Color colour = side == LaneSites.Side.RED ? RED : BLUE;
        return switch (kind) {
            case MINION_MELEE -> at.getWorld().spawn(at, Zombie.class, zombie -> {
                zombie.setBaby();
                optional(() -> zombie.setShouldBurnInDay(false));
                dress(zombie, colour, Material.WOODEN_SWORD);
                configure.accept(zombie);
            });
            case MINION_RANGED -> at.getWorld().spawn(at, Skeleton.class, skeleton -> {
                optional(() -> skeleton.setShouldBurnInDay(false));
                dress(skeleton, colour, Material.BOW);
                configure.accept(skeleton);
            });
            case MINION_CANNON -> at.getWorld().spawn(at, IronGolem.class, golem -> {
                optional(() -> golem.setPlayerCreated(true));
                configure.accept(golem);
            });
            case JELLY -> at.getWorld().spawn(at, Slime.class, slime -> {
                slime.setSize(1);
                configure.accept(slime);
            });
            case MICE -> (mice++ % 2 == 0)
                    ? at.getWorld().spawn(at, Silverfish.class, configure::accept)
                    : at.getWorld().spawn(at, Endermite.class, configure::accept);
            case TEDDY -> at.getWorld().spawn(at, Panda.class, panda -> {
                optional(() -> {
                    panda.setMainGene(Panda.Gene.BROWN);
                    panda.setHiddenGene(Panda.Gene.BROWN);
                });
                configure.accept(panda);
            });
            case RABBIT -> at.getWorld().spawn(at, Rabbit.class, rabbit -> {
                optional(() -> rabbit.setRabbitType(Rabbit.Type.GOLD));
                configure.accept(rabbit);
            });
            case DRAGON -> at.getWorld().spawn(at, Ravager.class, configure::accept);
            case SHOPKEEPER -> at.getWorld().spawn(at, Villager.class, villager -> {
                optional(() -> villager.setVillagerLevel(5));
                configure.accept(villager);
            });
        };
    }

    /** The vanilla entity type of a kind (tests, tools); mice alternate, the first is a silverfish. */
    public static EntityType typeOf(CreatureKind kind) {
        return switch (kind) {
            case MINION_MELEE -> EntityType.ZOMBIE;
            case MINION_RANGED -> EntityType.SKELETON;
            case MINION_CANNON -> EntityType.IRON_GOLEM;
            case JELLY -> EntityType.SLIME;
            case MICE -> EntityType.SILVERFISH;
            case TEDDY -> EntityType.PANDA;
            case RABBIT -> EntityType.RABBIT;
            case DRAGON -> EntityType.RAVAGER;
            case SHOPKEEPER -> EntityType.VILLAGER;
        };
    }

    private static void dress(Mob mob, Color colour, Material hand) {
        EntityEquipment equipment = mob.getEquipment();
        equipment.setHelmet(dyed(Material.LEATHER_HELMET, colour), true);
        equipment.setChestplate(dyed(Material.LEATHER_CHESTPLATE, colour), true);
        equipment.setItemInMainHand(ItemStack.of(hand), true);
        equipment.setHelmetDropChance(0);
        equipment.setChestplateDropChance(0);
        equipment.setItemInMainHandDropChance(0);
    }

    private static ItemStack dyed(Material material, Color colour) {
        ItemStack item = ItemStack.of(material);
        item.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(colour));
        return item;
    }

    /** Vanilla behaviour a test server may not implement (the creature works without it). */
    static void optional(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException testServer) {
            // MockBukkit's UnimplementedOperationException: cosmetic only.
        }
    }
}
