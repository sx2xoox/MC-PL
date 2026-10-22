package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import com.destroystokyo.paper.ClientOption;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.entity.LookAnchor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 환영술사 — a master of decoys. A mirage shield swallows one enemy hit every so often, the active leaves a perfect
 * copy (a mannequin wearing the owner's skin and gear) behind while the owner vanishes, and the ultimate splits the
 * owner into a whole squad of copies and trades places with one of them. Copies imitate a player: they keep running,
 * turn to face and chase enemies and swing at them. Striking a copy shatters it and blinds the striker.
 */
public final class IllusionistAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("illusionist", "환영술사", Material.ENDER_EYE)
            .color(TextColor.color(0xA98CFF))
            .difficulty(3)
            .passive("환영 보호막",
                    "{shield_cooldown}초마다 적의 공격을 한 번 완전히 막아 내는 환영 보호막이 생깁니다.")
            .active("분신",
                    "제자리에 나와 똑같은 분신을 남기고 {invis_duration}초간 적의 눈에서 사라집니다. 분신은 "
                            + "{clone_duration}초간 나를 흉내 내며, 분신을 공격한 적은 {clone_blind}초간 실명합니다.",
                    12)
            .ultimate("환영 군단",
                    "주변에 분신 {legion_clones}개를 불러내 그중 무작위 분신과 자리를 바꾸고 {legion_invis}초간 "
                            + "사라집니다. 분신들은 {legion_duration}초간 흩어져 적을 쫓습니다.")
            // Copies need the damage listener and the stealth needs onRevoke to show the owner again.
            .ultimateNotStandalone()
            .param("shield_cooldown", 20)
            .param("invis_duration", 2)
            .param("clone_duration", 6)
            .param("clone_blind", 1.5)
            .param("legion_clones", 4)
            .param("legion_spread", 3)
            .param("legion_invis", 4)
            .param("legion_duration", 8)
            .build();

    private static final String LILAC = "#A98CFF";
    private static final Color MIRAGE = Color.fromRGB(0xA9, 0x8C, 0xFF);
    private static final Color PALE = Color.fromRGB(0xE8, 0xE0, 0xFF);
    private static final Color DEEP = Color.fromRGB(0x5A, 0x3D, 0xC8);
    private static final Particle.DustTransition MIRAGE_DUST = new Particle.DustTransition(MIRAGE, PALE, 1.0f);
    private static final Particle.DustTransition SHATTER_DUST = new Particle.DustTransition(PALE, DEEP, 1.2f);
    private static final Particle.DustOptions SHIELD_GLINT = new Particle.DustOptions(PALE, 0.6f);

    // Copies
    private static final int MAX_CLONES = 8;
    private static final double RUN_SPEED_MIN = 0.16;
    private static final double RUN_SPEED_MAX = 0.3;
    private static final double LEGION_RUN_SPEED = 0.28;
    private static final double WALK_SPEED = 0.2;
    private static final long DECOY_RUN_TICKS = 30;
    private static final long LEGION_RUN_TICKS = 24;
    private static final double CHASE_RANGE = 20;
    private static final double SWING_RANGE = 2.6;
    private static final long THINK_INTERVAL = 5;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double MOVING_THRESHOLD = 0.08;
    private static final int GROUND_SEARCH_DEPTH = 3;

    // Placement
    private static final double STANDING_WIDTH = 0.6;
    private static final double STANDING_HEIGHT = 1.8;
    private static final double STANDING_EYE = 1.62;
    private static final double[] LIFTS = {0, 0.5, 1.0, -0.5, -1.0, 1.5, -1.5};
    private static final double GROUND_SNAP = 2.1;
    private static final double MIN_SPOT_SPACING = 1.3;
    /** Ring sample order (bit-reversed indices of 16 angles), so the first picks are spread far apart. */
    private static final int[] RING_ORDER = {0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15};

    private static final double AGGRO_RANGE = 32;

    private final List<Copy> copies = new ArrayList<>();
    private @Nullable CopyGuard guard;

    // Mirage shield
    private long shieldReadyTick;
    private boolean shieldAnnounced = true;

    // Stealth
    private boolean stealthed;
    private int stealthToken;
    private long stealthEndTick;
    private final Set<UUID> hiddenFrom = new HashSet<>();

    // Owner motion (sampled every few ticks), so a decoy can keep running the way the owner was going.
    private @Nullable Location motionSample;
    private Vector motion = new Vector();

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        reveal(false);
        for (Copy copy : copies) {
            copy.body.remove();
        }
        copies.clear();
        unregisterGuard();
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        if (tick % 4 == 0) {
            sampleMotion(owner);
        }
        tickShield(owner, tick);
        if (stealthed) {
            tickStealth(owner, tick);
        }
        if (!copies.isEmpty()) {
            tickCopies(tick);
        }
    }

    private void sampleMotion(Player owner) {
        Location now = owner.getLocation();
        Location last = motionSample;
        motionSample = now;
        if (last == null || last.getWorld() != now.getWorld()) {
            motion = new Vector();
            return;
        }
        motion = now.toVector().subtract(last.toVector()).multiply(0.25).setY(0);
    }

    // ------------------------------------------------------------------ passive: mirage shield

    private void tickShield(Player owner, long tick) {
        if (tick < shieldReadyTick) {
            return;
        }
        if (!shieldAnnounced) {
            shieldAnnounced = true;
            owner.playSound(owner, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.8f, 1.5f);
            owner.sendActionBar(Text.mm("<" + LILAC + ">◈ 환영 보호막 준비</" + LILAC + ">"));
        }
        if (!stealthed && tick % 10 == 0) {
            // A faint orbiting glint tells everyone the shield is up.
            double angle = tick * 0.35;
            double radius = 0.55 * scaleOf(owner);
            Location at = owner.getLocation().add(Math.cos(angle) * radius, owner.getHeight() * 0.55,
                    Math.sin(angle) * radius);
            owner.getWorld().spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, SHIELD_GLINT);
        }
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getDamage() <= 0 || ctx().currentTick() < shieldReadyTick) {
            return;
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity) || !isHostile(byEntity.getDamager())) {
            return;
        }
        event.setCancelled(true);
        shieldReadyTick = ctx().currentTick() + params().ticks("shield_cooldown");
        shieldAnnounced = false;

        Player owner = player();
        Location body = owner.getLocation().add(0, owner.getHeight() * 0.55, 0);
        World world = body.getWorld();
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 24, 0.4, 0.55, 0.4, 0, SHATTER_DUST);
        world.spawnParticle(Particle.ENCHANTED_HIT, body, 14, 0.35, 0.5, 0.35, 0.3);
        world.spawnParticle(Particle.REVERSE_PORTAL, body, 12, 0.3, 0.4, 0.3, 0.05);
        sound(body, Sound.BLOCK_GLASS_BREAK, 0.9f, 1.5f + jitter(0.1f));
        sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8f, 1.3f);
        owner.sendActionBar(Text.mm("<" + LILAC + ">◈ 환영 보호막</" + LILAC + "> <white>공격을 흘려보냈습니다</white>"));
    }

    /** True if the damager is (or belongs to) an enemy of the owner. */
    private boolean isHostile(Entity damager) {
        Player owner = player();
        Player attacker = ctx().resolveAttacker(damager);
        if (attacker != null) {
            return !attacker.equals(owner) && ctx().isEnemy(owner, attacker);
        }
        return ctx().isEnemy(owner, damager);
    }

    // ------------------------------------------------------------------ active: decoy

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 분신을 만들 수 없습니다");
        }
        Location at = owner.getLocation();
        Copy copy = spawnCopy(at, params().ticks("clone_duration"));
        if (copy == null) {
            return ActivationResult.fail("분신을 만들 수 없습니다");
        }
        // The decoy carries on the way the owner was moving, while the real one slips away unseen.
        double speed = motion.length();
        if (speed > MOVING_THRESHOLD) {
            copy.run(motion.clone().normalize(), Math.clamp(speed, RUN_SPEED_MIN, RUN_SPEED_MAX),
                    ctx().currentTick() + DECOY_RUN_TICKS);
        }
        Location body = at.clone().add(0, owner.getHeight() * 0.5, 0);
        World world = body.getWorld();
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 18, 0.35, 0.6, 0.35, 0, MIRAGE_DUST);
        world.spawnParticle(Particle.REVERSE_PORTAL, body, 16, 0.3, 0.6, 0.3, 0.04);
        sound(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.1f + jitter(0.1f));
        sound(at, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.6f, 1.4f);

        startStealth(owner, params().ticks("invis_duration"));
        owner.sendActionBar(Text.mm("<" + LILAC + ">◈ 분신</" + LILAC + "> <gray>분신을 남기고 사라졌습니다</gray>"));
        return ActivationResult.success();
    }

    // ------------------------------------------------------------------ ultimate: legion of mirages

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (owner.isInsideVehicle()) {
            return ActivationResult.fail("탈것에 탄 채로는 사용할 수 없습니다");
        }
        int count = Math.clamp(params().integer("legion_clones"), 1, MAX_CLONES);
        long lifetime = params().ticks("legion_duration");
        Location origin = owner.getLocation();
        List<Location> spots = ringSpots(owner, origin, count, Math.max(1.5, params().num("legion_spread")));

        // Trade places with one of the copies: the owner goes to a random spot, a copy takes the old place.
        List<Location> copySpots = new ArrayList<>(spots);
        boolean swapped = false;
        if (!copySpots.isEmpty()) {
            Location target = copySpots.remove(ThreadLocalRandom.current().nextInt(copySpots.size()));
            target.setYaw(origin.getYaw());
            target.setPitch(origin.getPitch());
            if (owner.teleport(target, TeleportCause.PLUGIN)) {
                owner.setFallDistance(0);
                swapped = true;
            } else {
                copySpots.add(target);
            }
        }
        copySpots.addFirst(origin);
        while (copySpots.size() > count) {
            copySpots.removeLast();
        }

        Vector facing = horizontal(origin.getDirection());
        int made = 0;
        for (Location spot : copySpots) {
            Copy copy = spawnCopy(spot, lifetime);
            if (copy == null) {
                continue;
            }
            made++;
            Vector away = horizontal(spot.toVector().subtract(origin.toVector()));
            Vector direction = away.lengthSquared() > 1.0E-4 ? away : facing;
            if (direction.lengthSquared() > 1.0E-4) {
                copy.run(direction, LEGION_RUN_SPEED, ctx().currentTick() + LEGION_RUN_TICKS);
            }
            Location puff = spot.clone().add(0, 1.0, 0);
            spot.getWorld().spawnParticle(Particle.POOF, puff, 6, 0.25, 0.5, 0.25, 0.02);
            spot.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, puff, 10, 0.3, 0.6, 0.3, 0, MIRAGE_DUST);
        }
        if (made == 0) {
            if (swapped) {
                owner.teleport(origin, TeleportCause.PLUGIN);
            }
            return ActivationResult.fail("분신을 만들 수 없습니다");
        }

        World world = origin.getWorld();
        Location center = origin.clone().add(0, 1.0, 0);
        world.spawnParticle(Particle.REVERSE_PORTAL, center, 40, 1.2, 0.6, 1.2, 0.05);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, center, 30, 1.5, 0.6, 1.5, 0, MIRAGE_DUST);
        sound(origin, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.0f, 1.0f);
        sound(origin, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0f, 0.8f);
        sound(origin, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 0.7f);

        startStealth(owner, params().ticks("legion_invis"));
        owner.sendActionBar(Text.mm("<" + LILAC + ">◈ 환영 군단</" + LILAC + "> <white>분신 " + made + "개</white>"
                + (swapped ? " <gray>· 자리를 바꿨습니다</gray>" : "")));
        return ActivationResult.success();
    }

    /** Up to {@code count} safe standing spots around {@code origin}, spread apart and in line of sight. */
    private List<Location> ringSpots(Player owner, Location origin, int count, double distance) {
        List<Location> spots = new ArrayList<>();
        World world = origin.getWorld();
        double width = STANDING_WIDTH * Math.max(1.0, scaleOf(owner));
        double height = STANDING_HEIGHT * Math.max(1.0, scaleOf(owner));
        Location eye = origin.clone().add(0, STANDING_EYE * scaleOf(owner), 0);
        double baseAngle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        for (double d : new double[] {distance, distance * 0.65}) {
            for (int index : RING_ORDER) {
                if (spots.size() >= count) {
                    return spots;
                }
                double angle = baseAngle + Math.PI * 2 * index / RING_ORDER.length;
                Location base = origin.clone().add(Math.cos(angle) * d, 0, Math.sin(angle) * d);
                Location spot = standingSpotNear(world, base, width, height);
                if (spot == null || tooClose(spot, spots) || spot.distanceSquared(origin) < 1.0
                        || !clearPath(world, eye, spot.clone().add(0, STANDING_EYE, 0))) {
                    continue;
                }
                spot.setYaw((float) Math.toDegrees(Math.atan2(-(spot.getX() - origin.getX()),
                        spot.getZ() - origin.getZ())));
                spots.add(spot);
            }
        }
        return spots;
    }

    private static @Nullable Location standingSpotNear(World world, Location base, double width, double height) {
        for (double lift : LIFTS) {
            Location feet = base.clone().add(0, lift, 0);
            snapToGround(world, feet, GROUND_SNAP);
            if (fits(world, feet, width, height) && hasGroundBelow(world, feet)) {
                return feet;
            }
        }
        return null;
    }

    private static boolean tooClose(Location spot, List<Location> others) {
        for (Location other : others) {
            if (other.distanceSquared(spot) < MIN_SPOT_SPACING * MIN_SPOT_SPACING) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ copies

    /** One mannequin copy of the owner and its little imitation of a player. */
    private final class Copy {
        private final Mannequin body;
        private final long expireTick;
        private @Nullable Vector runDirection;
        private double runSpeed;
        private long runUntilTick;
        private @Nullable LivingEntity focus;
        private long nextThinkTick;
        private long nextSwingTick;
        private boolean shattered;

        Copy(Mannequin body, long expireTick) {
            this.body = body;
            this.expireTick = expireTick;
        }

        void run(Vector direction, double speed, long untilTick) {
            this.runDirection = direction.clone().setY(0).normalize();
            this.runSpeed = speed;
            this.runUntilTick = untilTick;
            face(runDirection);
        }

        void step(long tick) {
            if (runDirection != null && tick < runUntilTick) {
                if (!move(runDirection, runSpeed)) {
                    runDirection = null;
                }
                return;
            }
            runDirection = null;
            if (tick >= nextThinkTick) {
                nextThinkTick = tick + THINK_INTERVAL;
                List<LivingEntity> enemies = ctx().enemiesNear(player(), body.getLocation(), CHASE_RANGE);
                focus = enemies.isEmpty() ? null : enemies.getFirst();
            }
            LivingEntity target = focus;
            if (target == null || !target.isValid() || target.getWorld() != body.getWorld()) {
                halt();
                return;
            }
            Location eye = target.getEyeLocation();
            body.lookAt(eye.getX(), eye.getY(), eye.getZ(), LookAnchor.EYES);
            body.setBodyYaw(body.getLocation().getYaw());
            Vector toward = horizontal(target.getLocation().toVector().subtract(body.getLocation().toVector()));
            double distance = target.getLocation().distance(body.getLocation());
            if (distance > SWING_RANGE) {
                if (toward.lengthSquared() > 1.0E-4) {
                    move(toward.normalize(), WALK_SPEED);
                }
                return;
            }
            halt();
            if (tick >= nextSwingTick) {
                body.swingMainHand();
                nextSwingTick = tick + 8 + ThreadLocalRandom.current().nextInt(8);
            }
        }

        /** Walks one tick along {@code direction}; returns false if the way is blocked or unsafe. */
        private boolean move(Vector direction, double speed) {
            Location feet = body.getLocation();
            World world = feet.getWorld();
            Location ahead = feet.clone().add(direction.clone().multiply(0.7));
            if (!hasFooting(world, ahead)) {
                halt();
                return false;
            }
            double vy = body.getVelocity().getY();
            if (!passable(world, ahead, 0.2)) {
                if (!body.isOnGround() || !passable(world, ahead, 1.2) || !passable(world, ahead, 2.2)
                        || !passable(world, feet, 2.2)) {
                    halt();
                    return false;
                }
                vy = JUMP_VELOCITY;
            }
            body.setVelocity(new Vector(direction.getX() * speed, vy, direction.getZ() * speed));
            if (runDirection != null) {
                face(direction);
            }
            return true;
        }

        private void halt() {
            Vector velocity = body.getVelocity();
            if (Math.abs(velocity.getX()) > 1.0E-3 || Math.abs(velocity.getZ()) > 1.0E-3) {
                body.setVelocity(new Vector(0, velocity.getY(), 0));
            }
        }

        private void face(Vector direction) {
            float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
            body.setRotation(yaw, 0);
            body.setBodyYaw(yaw);
        }
    }

    private @Nullable Copy spawnCopy(Location at, long lifetime) {
        Player owner = player();
        while (copies.size() >= MAX_CLONES) {
            Copy oldest = copies.removeFirst();
            shatter(oldest, false);
        }
        Mannequin body;
        try {
            body = at.getWorld().spawn(at, Mannequin.class, mannequin -> dress(mannequin, owner, at));
        } catch (RuntimeException e) {
            ctx().plugin().getLogger().log(Level.FINE, "Could not spawn an illusionist copy", e);
            return null;
        }
        if (!body.isValid()) {
            // The spawn was cancelled (e.g. by a protection plugin).
            return null;
        }
        ctx().summon(owner, body);
        registerGuard();
        Copy copy = new Copy(body, ctx().currentTick() + Math.max(1, lifetime));
        copies.add(copy);
        return copy;
    }

    /** Makes the mannequin look exactly like the owner: skin, name, hand, armor and a real weapon. */
    private static void dress(Mannequin mannequin, Player owner, Location at) {
        mannequin.setPersistent(false);
        mannequin.setRotation(at.getYaw(), at.getPitch());
        mannequin.setBodyYaw(at.getYaw());
        mannequin.setProfile(ResolvableProfile.resolvableProfile(owner.getPlayerProfile()));
        try {
            mannequin.setSkinParts(owner.getClientOption(ClientOption.SKIN_PARTS));
        } catch (RuntimeException ignored) {
            // Skin parts are cosmetic; the default (all parts) is fine.
        }
        mannequin.setMainHand(owner.getMainHand());
        mannequin.setDescription(null);
        mannequin.customName(owner.teamDisplayName());
        mannequin.setCustomNameVisible(nameTagShown(owner));
        // Several copies spawn close together; they should not shove each other (or the owner) around.
        mannequin.setCollidable(false);

        EntityEquipment equipment = mannequin.getEquipment();
        EntityEquipment worn = owner.getEquipment();
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.OFF_HAND}) {
            equipment.setItem(slot, copyOf(worn.getItem(slot)));
            equipment.setDropChance(slot, 0f);
        }
        equipment.setItem(EquipmentSlot.HAND, weaponOf(owner));
        equipment.setDropChance(EquipmentSlot.HAND, 0f);
    }

    /** Copies hold a real weapon rather than the ability item the owner just used. */
    private static @Nullable ItemStack weaponOf(Player owner) {
        PlayerInventory inventory = owner.getInventory();
        ItemStack held = inventory.getItemInMainHand();
        if (isWeapon(held)) {
            return held.clone();
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && isWeapon(item)) {
                return item.clone();
            }
        }
        return held.isEmpty() || held.getType() == Material.IRON_INGOT ? null : held.clone();
    }

    private static boolean isWeapon(ItemStack item) {
        if (item.isEmpty()) {
            return false;
        }
        Material type = item.getType();
        return Tag.ITEMS_SWORDS.isTagged(type) || Tag.ITEMS_AXES.isTagged(type) || type == Material.BOW
                || type == Material.CROSSBOW || type == Material.TRIDENT || type == Material.MACE;
    }

    private static @Nullable ItemStack copyOf(@Nullable ItemStack item) {
        return item == null || item.isEmpty() ? null : item.clone();
    }

    /** Copies only show a name tag if enemies would see the owner's. */
    private static boolean nameTagShown(Player owner) {
        try {
            Team team = owner.getScoreboard().getEntityTeam(owner);
            if (team == null) {
                return true;
            }
            Team.OptionStatus status = team.getOption(Team.Option.NAME_TAG_VISIBILITY);
            return status == Team.OptionStatus.ALWAYS || status == Team.OptionStatus.FOR_OTHER_TEAMS;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void tickCopies(long tick) {
        Iterator<Copy> iterator = copies.iterator();
        while (iterator.hasNext()) {
            Copy copy = iterator.next();
            if (copy.shattered || !copy.body.isValid()) {
                iterator.remove();
                continue;
            }
            if (tick >= copy.expireTick) {
                iterator.remove();
                shatter(copy, false);
                continue;
            }
            copy.step(tick);
        }
        if (copies.isEmpty()) {
            unregisterGuard();
        }
    }

    private @Nullable Copy findCopy(Entity entity) {
        if (!(entity instanceof Mannequin)) {
            return null;
        }
        for (Copy copy : copies) {
            if (copy.body.equals(entity)) {
                return copy;
            }
        }
        return null;
    }

    /** Removes a copy in a burst of mirage dust; {@code struck} adds the glass-shatter flourish. */
    private void shatter(Copy copy, boolean struck) {
        copy.shattered = true;
        Mannequin body = copy.body;
        if (body.isValid()) {
            Location center = body.getLocation().add(0, body.getHeight() * 0.5, 0);
            World world = center.getWorld();
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, center, struck ? 26 : 14, 0.35, 0.6, 0.35, 0,
                    struck ? SHATTER_DUST : MIRAGE_DUST);
            world.spawnParticle(Particle.POOF, center, struck ? 10 : 5, 0.25, 0.5, 0.25, 0.03);
            if (struck) {
                world.spawnParticle(Particle.ENCHANTED_HIT, center, 12, 0.3, 0.5, 0.3, 0.3);
                sound(center, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.2f + jitter(0.1f));
                sound(center, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.9f, 1.6f);
            } else {
                sound(center, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.5f, 1.8f + jitter(0.1f));
            }
            body.remove();
        }
    }

    /** An enemy struck a copy: it shatters and the striker is blinded by the mirage. */
    private void onCopyStruck(Copy copy, Entity damager) {
        if (copy.shattered || !isHostile(damager)) {
            return;
        }
        Player owner = player();
        LivingEntity striker = null;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof LivingEntity living && ctx().isEnemy(owner, living)) {
                striker = living;
            }
        } else if (damager instanceof LivingEntity living && ctx().isEnemy(owner, living)) {
            striker = living;
        }
        if (striker == null) {
            Player attacker = ctx().resolveAttacker(damager);
            if (attacker != null && ctx().isEnemy(owner, attacker)) {
                striker = attacker;
            }
        }
        copies.remove(copy);
        shatter(copy, true);
        if (striker == null) {
            return;
        }
        int blind = (int) params().ticks("clone_blind");
        if (blind > 0) {
            striker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blind, 0, false, true, true));
        }
        if (striker instanceof Mob mob && owner.equals(mob.getTarget())) {
            mob.setTarget(null);
        }
        Location eye = striker.getEyeLocation();
        striker.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, eye, 10, 0.25, 0.2, 0.25, 0, SHATTER_DUST);
        if (striker instanceof Player victim) {
            victim.playSound(victim, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, SoundCategory.PLAYERS, 0.9f, 1.2f);
            victim.sendActionBar(Text.mm("<" + LILAC + ">환영이었습니다!</" + LILAC + ">"));
        }
        if (owner.isOnline()) {
            owner.sendActionBar(Text.mm("<" + LILAC + ">◈ 분신이 깨졌습니다</" + LILAC + "> <gray>→</gray> "
                    + "<white><name></white> <gray>실명</gray>", Placeholder.component("name", striker.name())));
        }
    }

    /**
     * Copies never take real damage: every hit is cancelled first thing (lowest priority, before kill credit,
     * gauges and on-hit effects see it). Only hits by enemies shatter them.
     */
    private final class CopyGuard implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void onDamage(EntityDamageEvent event) {
            Copy copy = findCopy(event.getEntity());
            if (copy == null) {
                return;
            }
            event.setCancelled(true);
            try {
                if (event instanceof EntityDamageByEntityEvent byEntity) {
                    onCopyStruck(copy, byEntity.getDamager());
                } else if (event.getCause() == DamageCause.VOID) {
                    copies.remove(copy);
                    shatter(copy, false);
                }
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Illusionist copy hit handling failed", e);
            }
        }
    }

    private void registerGuard() {
        if (guard == null) {
            guard = new CopyGuard();
            Bukkit.getPluginManager().registerEvents(guard, ctx().plugin());
        }
    }

    private void unregisterGuard() {
        if (guard != null) {
            HandlerList.unregisterAll(guard);
            guard = null;
        }
    }

    // ------------------------------------------------------------------ stealth

    private void startStealth(Player owner, long ticks) {
        if (ticks <= 0) {
            return;
        }
        stealthed = true;
        int token = ++stealthToken;
        stealthEndTick = ctx().currentTick() + ticks;
        // Allies and spectators keep seeing the vanilla translucent body; enemies do not see the owner at all.
        owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) ticks + 5, 0, false, false, true));
        hideFromEnemies(owner);
        redirectAggro(owner);
        ctx().runLater(owner, ticks, () -> {
            if (token == stealthToken) {
                reveal(true);
            }
        });
    }

    private void tickStealth(Player owner, long tick) {
        if (tick % 5 == 0) {
            double left = Math.max(0, stealthEndTick - ctx().currentTick()) / 20.0;
            owner.sendActionBar(Text.mm("<" + LILAC + ">◈ 투명</" + LILAC + "> <gray>"
                    + AbilityInfo.formatNumber(Math.ceil(left * 10) / 10.0) + "초</gray>"));
        }
        if (tick % 20 == 0) {
            hideFromEnemies(owner);
            redirectAggro(owner);
        }
    }

    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (stealthed && !event.isCancelled()) {
            reveal(true);
        }
    }

    private void hideFromEnemies(Player owner) {
        for (Player viewer : ctx().alivePlayers()) {
            if (!viewer.equals(owner) && ctx().isEnemy(owner, viewer) && hiddenFrom.add(viewer.getUniqueId())) {
                viewer.hidePlayer(ctx().plugin(), owner);
            }
        }
    }

    /** Enemy mobs chasing the owner turn on the nearest copy instead (or lose track). */
    private void redirectAggro(Player owner) {
        for (Entity entity : owner.getNearbyEntities(AGGRO_RANGE, AGGRO_RANGE / 2, AGGRO_RANGE)) {
            if (entity instanceof Mob mob && owner.equals(mob.getTarget()) && ctx().isEnemy(owner, mob)) {
                mob.setTarget(nearestCopy(mob.getLocation()));
            }
        }
    }

    private @Nullable LivingEntity nearestCopy(Location from) {
        Mannequin best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Copy copy : copies) {
            if (copy.shattered || !copy.body.isValid() || copy.body.getWorld() != from.getWorld()) {
                continue;
            }
            double distance = copy.body.getLocation().distanceSquared(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = copy.body;
            }
        }
        return best;
    }

    private void reveal(boolean feedback) {
        if (!stealthed) {
            return;
        }
        stealthed = false;
        stealthToken++;
        Player owner = player();
        for (UUID id : hiddenFrom) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) {
                viewer.showPlayer(ctx().plugin(), owner);
            }
        }
        hiddenFrom.clear();
        if (!owner.isOnline()) {
            return;
        }
        PotionEffect invisibility = owner.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (invisibility != null && !invisibility.hasParticles()) {
            owner.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
        if (feedback) {
            Location body = owner.getLocation().add(0, owner.getHeight() * 0.5, 0);
            body.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, body, 12, 0.3, 0.5, 0.3, 0, MIRAGE_DUST);
            sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.5f, 1.5f + jitter(0.1f));
        }
    }

    // ------------------------------------------------------------------ world checks

    /** True if a standing body of the given size fits at {@code feet}: no collisions and no harmful blocks. */
    private static boolean fits(World world, Location feet, double width, double height) {
        if (feet.getY() < world.getMinHeight() + 1 || feet.getY() + height > world.getMaxHeight()) {
            return false;
        }
        double half = width / 2 - 0.01;
        BoundingBox box = new BoundingBox(feet.getX() - half, feet.getY() + 0.01, feet.getZ() - half,
                feet.getX() + half, feet.getY() + height, feet.getZ() + half);
        if (world.hasCollisionsIn(box)) {
            return false;
        }
        int x = feet.getBlockX();
        int z = feet.getBlockZ();
        for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY()); y++) {
            if (isHazard(world.getBlockAt(x, y, z))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHazard(Block block) {
        Material type = block.getType();
        return type == Material.LAVA || type == Material.FIRE || type == Material.SOUL_FIRE
                || type == Material.POWDER_SNOW || type == Material.COBWEB || type == Material.MAGMA_BLOCK;
    }

    /** False above the void: something (block or fluid) must exist somewhere below. */
    private static boolean hasGroundBelow(World world, Location feet) {
        double depth = feet.getY() - world.getMinHeight();
        return depth > 0 && world.rayTraceBlocks(feet, new Vector(0, -1, 0), depth, FluidCollisionMode.ALWAYS,
                true) != null;
    }

    /** Lowers {@code feet} onto the ground if it is at most {@code maxDrop} blocks below. */
    private static void snapToGround(World world, Location feet, double maxDrop) {
        RayTraceResult ground = world.rayTraceBlocks(feet, new Vector(0, -1, 0), maxDrop, FluidCollisionMode.NEVER,
                true);
        if (ground != null) {
            feet.setY(ground.getHitPosition().getY());
        }
    }

    private static boolean clearPath(World world, Location from, Location to) {
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        if (length < 1.0E-3) {
            return true;
        }
        return world.rayTraceBlocks(from, delta.multiply(1 / length), length, FluidCollisionMode.NEVER, true) == null;
    }

    /** A copy only walks where there is solid, safe ground within a short drop (never off a cliff or into lava). */
    private static boolean hasFooting(World world, Location ahead) {
        int x = ahead.getBlockX();
        int z = ahead.getBlockZ();
        int y = (int) Math.floor(ahead.getY() + 0.01);
        if (isHazard(world.getBlockAt(x, y, z))) {
            return false;
        }
        for (int depth = 0; depth <= GROUND_SEARCH_DEPTH; depth++) {
            Block below = world.getBlockAt(x, y - 1 - depth, z);
            if (isHazard(below) || below.isLiquid()) {
                return false;
            }
            if (below.isSolid()) {
                return true;
            }
        }
        return false;
    }

    private static boolean passable(World world, Location feet, double heightAbove) {
        return world.getBlockAt(feet.getBlockX(), (int) Math.floor(feet.getY() + heightAbove), feet.getBlockZ())
                .isPassable();
    }

    // ------------------------------------------------------------------ helpers

    private static Vector horizontal(Vector vector) {
        Vector flat = vector.clone().setY(0);
        return flat.lengthSquared() < 1.0E-8 ? new Vector() : flat.normalize();
    }

    private static double scaleOf(LivingEntity entity) {
        AttributeInstance scale = entity.getAttribute(Attribute.SCALE);
        return scale == null ? 1.0 : Math.max(0.1, scale.getValue());
    }

    private static void sound(Location at, Sound sound, float volume, float pitch) {
        at.getWorld().playSound(at, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private static float jitter(float amount) {
        return (ThreadLocalRandom.current().nextFloat() * 2 - 1) * amount;
    }
}
