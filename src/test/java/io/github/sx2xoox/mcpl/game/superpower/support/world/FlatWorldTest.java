package io.github.sx2xoox.mcpl.game.superpower.support.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The test world doubles behave like the server they stand in for. */
class FlatWorldTest {

    private MatchHarness h;
    private FlatWorld world;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
        world = h.flatWorld();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private Location at(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    @Test
    void layoutIsAFlatStoneFloor() {
        assertEquals(Material.BEDROCK, world.getBlockAt(5, 0, 5).getType());
        assertEquals(Material.STONE, world.getBlockAt(5, FlatWorld.FLOOR_Y, 5).getType());
        assertEquals(Material.AIR, world.getBlockAt(5, FlatWorld.FLOOR_Y + 1, 5).getType());
        assertTrue(world.getBlockAt(5, FlatWorld.FLOOR_Y + 1, 5).isPassable());
        assertFalse(world.getBlockAt(5, FlatWorld.FLOOR_Y, 5).isPassable());
        assertEquals(new BoundingBox(5, 63, 5, 6, 64, 6), world.getBlockAt(5, 63, 5).getBoundingBox());
    }

    @Test
    void rayTraceBlocksFindsTheFirstSolidFace() {
        world.wallAlongX(-2, 2, 5, 3, Material.STONE);
        RayTraceResult wall = world.rayTraceBlocks(at(0.5, 65.6, 0.5), new Vector(0, 0, 1), 20,
                FluidCollisionMode.NEVER, true);
        assertNotNull(wall);
        assertEquals(5.0, wall.getHitPosition().getZ(), 1.0E-9, "the north face of the wall");
        assertEquals(BlockFace.NORTH, wall.getHitBlockFace());
        assertEquals(5, wall.getHitBlock().getZ());
        assertNull(world.rayTraceBlocks(at(0.5, 65.6, 0.5), new Vector(0, 0, 1), 4.0, FluidCollisionMode.NEVER, true),
                "the wall is out of reach");

        RayTraceResult floor = world.rayTraceBlocks(at(3.0, 64.0, 3.0), new Vector(0, -1, 0), 5,
                FluidCollisionMode.NEVER, true);
        assertNotNull(floor, "a ray straight down along block edges still finds the floor");
        assertEquals(64.0, floor.getHitPosition().getY(), 1.0E-9);
        assertEquals(BlockFace.UP, floor.getHitBlockFace());

        RayTraceResult inside = world.rayTraceBlocks(at(3.5, 63.5, 3.5), new Vector(0, 1, 0), 10,
                FluidCollisionMode.NEVER, true);
        assertNotNull(inside, "a ray starting inside a block hits it right away (vanilla clip)");
        assertEquals(63, inside.getHitBlock().getY());

        assertThrows(IllegalArgumentException.class,
                () -> world.rayTraceBlocks(at(0, 70, 0), new Vector(), 5, FluidCollisionMode.NEVER, true));
        assertNull(world.rayTraceBlocks(at(0, 70, 0), new Vector(0, -1, 0), -1, FluidCollisionMode.NEVER, true));
    }

    @Test
    void fluidsAndPlantsFollowTheTraceModes() {
        world.setBlock(0, 64, 3, Material.WATER);
        world.setBlock(0, 64, 6, Material.SHORT_GRASS);
        Location eye = at(0.5, 64.5, 0.5);
        Vector forward = new Vector(0, 0, 1);
        assertEquals(3, world.rayTraceBlocks(eye, forward, 10, FluidCollisionMode.ALWAYS, true).getHitBlock().getZ());
        assertEquals(6, world.rayTraceBlocks(eye, forward, 10, FluidCollisionMode.NEVER, false).getHitBlock().getZ(),
                "outlines (grass) count when passable blocks are not ignored");
        assertNull(world.rayTraceBlocks(eye, forward, 10, FluidCollisionMode.NEVER, true));
    }

    @Test
    void collisionsNeedPositiveOverlap() {
        assertFalse(world.hasCollisionsIn(new BoundingBox(0, 64.0, 0, 0.6, 65.8, 0.6)), "standing on the floor");
        assertTrue(world.hasCollisionsIn(new BoundingBox(0, 63.9, 0, 0.6, 65.8, 0.6)), "sunk into the floor");
        world.setBlock(2, 65, 2, Material.GLASS);
        assertTrue(world.hasCollisionsIn(new BoundingBox(1.5, 64.5, 1.5, 2.5, 65.5, 2.5)));
        assertFalse(world.hasCollisionsIn(new BoundingBox(1.0, 64.5, 1.5, 2.0, 65.5, 2.5)), "touching is not colliding");
    }

    @Test
    void rayTraceEntitiesAndCombinedTrace() {
        TestPlayer a = h.join("A");
        TestPlayer b = h.join("B");
        a.teleport(at(0.5, 64, 0.5));
        b.teleport(at(0.5, 64, 6.5));
        Location eye = a.getEyeLocation();
        RayTraceResult hit = world.rayTrace(eye, new Vector(0, 0, 1), 20, FluidCollisionMode.NEVER, true, 0.0,
                entity -> !entity.equals(a));
        assertNotNull(hit);
        assertEquals(b, hit.getHitEntity());
        world.wallAlongX(-1, 1, 4, 3, Material.STONE);
        RayTraceResult blocked = world.rayTrace(eye, new Vector(0, 0, 1), 20, FluidCollisionMode.NEVER, true, 0.0,
                entity -> !entity.equals(a));
        assertNotNull(blocked);
        assertNull(blocked.getHitEntity(), "the wall is closer than the player");
        assertFalse(a.hasLineOfSight(b), "no line of sight through the wall");
        world.wallAlongX(-1, 1, 4, 3, Material.AIR);
        assertTrue(a.hasLineOfSight(b));
    }

    @Test
    void playerBodyFollowsTheScaleAttribute() {
        TestPlayer p = h.join("P");
        p.teleport(at(0.5, 64, 0.5));
        assertEquals(1.62, p.getEyeLocation().getY() - 64, 1.0E-9);
        p.getAttribute(Attribute.SCALE).addModifier(new AttributeModifier(new org.bukkit.NamespacedKey("test", "s"),
                1.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        assertEquals(3.6, p.getHeight(), 1.0E-9);
        assertEquals(1.2, p.getBoundingBox().getWidthX(), 1.0E-9);
    }

    @Test
    void damageUsesServerCausesFramesResistanceAndAbsorption() {
        TestPlayer victim = h.join("V");
        TestPlayer attacker = h.join("A");
        List<EntityDamageEvent.DamageCause> causes = new ArrayList<>();
        h.server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onDamage(EntityDamageEvent event) {
                causes.add(event.getCause());
            }
        }, h.hub.plugin());

        victim.damage(2, DamageSource.builder(DamageType.MAGIC).withCausingEntity(attacker)
                .withDirectEntity(attacker).build());
        assertEquals(EntityDamageEvent.DamageCause.MAGIC, causes.getLast(), "ability damage is MAGIC on a server");
        assertEquals(18, victim.getHealth(), 1.0E-9);
        victim.damage(1, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(attacker)
                .withDirectEntity(attacker).build());
        assertEquals(18, victim.getHealth(), 1.0E-9, "a weaker hit during the invulnerability frames is ignored");
        victim.damage(5, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(attacker)
                .withDirectEntity(attacker).build());
        assertEquals(EntityDamageEvent.DamageCause.ENTITY_ATTACK, causes.getLast());
        assertEquals(15, victim.getHealth(), 1.0E-9, "a stronger hit only deals the difference");

        victim.setNoDamageTicks(0);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 1));
        victim.setAbsorptionAmount(2);
        assertEquals(0, victim.getAbsorptionAmount(), 1.0E-9, "absorption is clamped to MAX_ABSORPTION (0)");
        victim.getAttribute(Attribute.MAX_ABSORPTION).addModifier(new AttributeModifier(
                new org.bukkit.NamespacedKey("test", "absorb"), 4, AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.ANY));
        victim.setAbsorptionAmount(2);
        victim.damage(10, DamageSource.builder(DamageType.MAGIC).build());
        assertEquals(0, victim.getAbsorptionAmount(), 1.0E-9, "absorption is used up first");
        assertEquals(11, victim.getHealth(), 1.0E-9, "10 -40% resistance = 6, 2 absorbed");
    }

    @Test
    void mobsTakeDamageSourcesAndDifficultyScalesTheirHits() {
        TestPlayer victim = h.join("V");
        Wolf wolf = world.spawn(at(2, 64, 2), Wolf.class);
        assertInstanceOf(MockEntities.TestWolf.class, wolf);
        wolf.damage(3, DamageSource.builder(DamageType.MAGIC).withCausingEntity(victim).withDirectEntity(victim)
                .build());
        assertEquals(wolf.getAttribute(Attribute.MAX_HEALTH).getValue() - 3, wolf.getHealth(), 1.0E-9);

        victim.damage(7, DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(wolf).withDirectEntity(wolf)
                .build());
        assertEquals(20 - 4.5, victim.getHealth(), 1.0E-9, "easy difficulty: min(7/2+1, 7)");

        TestPlayer other = h.join("O");
        other.damage(4, DamageSource.builder(DamageType.MAGIC).withCausingEntity(victim).withDirectEntity(victim)
                .build());
        assertEquals(20 - 4, other.getHealth(), 1.0E-9, "a player's magic is not scaled");
        other.setNoDamageTicks(0);
        other.damage(10, DamageSource.builder(DamageType.EXPLOSION).build());
        assertEquals(20 - 4 - 6, other.getHealth(), 1.0E-9, "explosions always scale: min(10/2+1, 10)");
    }

    @Test
    void playersReportTheOptionsOfADefaultClient() {
        TestPlayer p = h.join("P");
        assertEquals(TestSkinParts.ALL, p.getClientOption(com.destroystokyo.paper.ClientOption.SKIN_PARTS).getRaw());
        p.setClientSkinParts(0x7E);
        com.destroystokyo.paper.SkinParts parts = p.getClientOption(com.destroystokyo.paper.ClientOption.SKIN_PARTS);
        assertFalse(parts.hasCapeEnabled(), "no cape");
        assertTrue(parts.hasHatsEnabled());
        assertEquals(p.getMainHand(), p.getClientOption(com.destroystokyo.paper.ClientOption.MAIN_HAND));
        Mannequin mannequin = world.spawn(at(0, 64, 0), Mannequin.class);
        mannequin.setSkinParts(parts);
        assertEquals(0x7E, mannequin.getSkinParts().getRaw(), "mannequins keep the skin layers they are given");
    }

    @Test
    void physicsMovesFallsAndHurtsOnLanding() {
        TestPlayer p = h.join("P");
        p.teleport(at(0.5, 74, 0.5));
        h.ticks(60);
        assertEquals(64.0, p.getLocation().getY(), 1.0E-9, "fell onto the floor");
        assertEquals(20 - 7, p.getHealth(), 1.0E-9, "10 blocks of fall: ceil(10 - 3) damage");

        p.setVelocity(new Vector(0.8, 0, 0));
        h.ticks(20);
        assertTrue(p.getLocation().getX() > 1.5, "a horizontal push slides along the ground");
        assertEquals(0.0, p.getVelocity().length(), 1.0E-9, "and stops through friction");

        world.wallAlongX(4, 4, 0, 2, Material.STONE);
        p.teleport(at(0.5, 64, 0.5));
        p.setVelocity(new Vector(2.0, 0, 0));
        h.ticks(5);
        assertEquals(4.0 - 0.3, p.getLocation().getX(), 1.0E-9, "stopped by the wall");
    }

    @Test
    void arrowsFlyHitAndStick() {
        TestPlayer shooter = h.join("S");
        TestPlayer target = h.join("T");
        shooter.teleport(at(0.5, 64, 0.5));
        target.teleport(at(0.5, 64, 8.5));
        List<ProjectileHitEvent> hits = new ArrayList<>();
        h.server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onHit(ProjectileHitEvent event) {
                hits.add(event);
            }
        }, h.hub.plugin());
        Arrow arrow = world.spawnArrow(shooter.getEyeLocation(), new Vector(0, 0, 1), 2.0f, 0f);
        arrow.setShooter(shooter);
        arrow.setDamage(2.0);
        h.ticks(10);
        assertEquals(1, hits.size());
        assertEquals(target, hits.getFirst().getHitEntity(), "the shooter is ignored, the target is hit");
        assertTrue(target.getHealth() < 20, "uncancelled hits deal vanilla arrow damage");
        assertFalse(arrow.isValid());

        Arrow down = world.spawnArrow(at(10.5, 70, 10.5), new Vector(0, -1, 0), 1.0f, 0f);
        h.ticks(20);
        assertTrue(down.isInBlock(), "an arrow hitting the floor sticks");
        assertEquals(64.0, down.getLocation().getY(), 1.0E-9);
    }

    @Test
    void displaysAndMannequinsFillTheMockGaps() {
        ItemDisplay display = world.spawn(at(0, 65, 0), ItemDisplay.class,
                d -> d.setBillboard(Display.Billboard.CENTER));
        assertEquals(Display.Billboard.CENTER, display.getBillboard());
        Mannequin mannequin = world.spawn(at(0, 64, 0), Mannequin.class);
        mannequin.lookAt(0, 64 + 1.62, 5, io.papermc.paper.entity.LookAnchor.EYES);
        assertEquals(0.0, mannequin.getLocation().getYaw(), 1.0E-6, "looking along +z");
        assertThrows(IllegalArgumentException.class,
                () -> mannequin.getEquipment().setDropChance(EquipmentSlot.HEAD, 0f),
                "Paper refuses drop chances for mannequins (they are not mobs)");
        world.strikeLightningEffect(at(3, 64, 3));
        assertEquals(1, world.lightningEffects().size());
    }
}
