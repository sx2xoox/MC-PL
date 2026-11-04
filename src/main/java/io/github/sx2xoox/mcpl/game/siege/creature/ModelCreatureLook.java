package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

import io.github.sx2xoox.mcpl.core.pack.ItemDisplayModels;
import io.github.sx2xoox.mcpl.core.pack.PackAudience;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Attack;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Motion;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Offset;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Part;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Puff;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The 3D toy look of 공성전 creatures and capture landmarks ({@link CreatureModels}), per viewer:
 *
 * <ul>
 *   <li>players with the pack ({@link PackAudience#hasPack}) see item displays of the creature's models following the
 *       mob (teleported every tick with a short teleport interpolation, turned with the mob, animated: waddle, wobble,
 *       hop, flapping wings, turning wind-up keys; each kind's {@link Attack} on {@link Visual#attack()} and a flinch
 *       on {@link Visual#hit()}) and do not see the vanilla mob; they hit it through an invisible
 *       {@link Interaction} the size of the mob, whose melee attacks are passed on to the mob as the player's own
 *       attack (so damage, kill credit and the game's damage rules work as for the mob);</li>
 *   <li>everyone else sees the vanilla mob and never receives the model displays or the hitbox.</li>
 * </ul>
 * A player's view swaps when their pack status changes. The look never moves, damages or removes the mob. Landmarks
 * ({@link #landmark}) are models on a base with a block-display fallback, like the towers.
 *
 * <p>Note for the game: the mob is hidden from pack players, so anything that must stay visible to them (name plate,
 * health bar) must be its own entity, not a passenger of the mob.
 */
public final class ModelCreatureLook implements CreatureSkin, Listener {

    private static final int TELEPORT_TICKS = 2;
    private static final int FLASH_TICKS = 3;
    private static final float VIEW_RANGE = 2f;

    private final Plugin plugin;
    private final PackAudience audience;
    private final BiConsumer<Player, LivingEntity> melee;
    private final List<ModelCreature> creatures = new ArrayList<>();
    private final List<Landmark> landmarks = new ArrayList<>();
    private final Map<UUID, ModelCreature> byHitbox = new HashMap<>();
    private @Nullable BukkitTask ticker;
    private PackAudience.@Nullable Subscription subscription;

    public ModelCreatureLook(Plugin plugin, PackAudience audience) {
        this(plugin, audience, (player, body) -> player.attack(body));
    }

    /** @param melee how a pack player's hit on a creature's hitbox reaches the mob (tests replace it) */
    ModelCreatureLook(Plugin plugin, PackAudience audience, BiConsumer<Player, LivingEntity> melee) {
        this.plugin = plugin;
        this.audience = audience;
        this.melee = melee;
    }

    @Override
    public ModelCreature attach(LivingEntity body, String kind, @Nullable Color team) {
        String id = CreatureModels.kindOf(kind);
        List<Part> parts = id == null ? null : CreatureModels.CREATURES.get(id);
        if (parts == null) {
            throw new IllegalArgumentException("unknown creature kind: " + kind);
        }
        start();
        ModelCreature creature = new ModelCreature(body, id, parts, team);
        creatures.add(creature);
        byHitbox.put(creature.hitbox.getUniqueId(), creature);
        return creature;
    }

    /**
     * A capture landmark ({@code fountain} or {@code telescope}) standing on {@code base}, its front towards the base's
     * yaw, its pennants in {@code owner}'s colour (null = neutral white).
     */
    public Landmark landmark(Location base, String id, @Nullable Color owner) {
        String kind = CreatureModels.kindOf(id);
        if (kind == null || !CreatureModels.LANDMARKS.containsKey(kind)) {
            throw new IllegalArgumentException("unknown landmark: " + id);
        }
        start();
        Landmark landmark = new Landmark(base.clone(), kind, owner);
        landmarks.add(landmark);
        return landmark;
    }

    /** Live creatures (for tests). */
    int creatureCount() {
        return creatures.size();
    }

    boolean running() {
        return ticker != null;
    }

    private void start() {
        if (ticker != null) {
            return;
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        subscription = audience.subscribe(this::refresh);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void stopWhenIdle() {
        if (!creatures.isEmpty() || !landmarks.isEmpty() || ticker == null) {
            return;
        }
        ticker.cancel();
        ticker = null;
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        HandlerList.unregisterAll(this);
    }

    private void tick() {
        for (ModelCreature creature : List.copyOf(creatures)) {
            creature.tick();
        }
        for (Landmark landmark : List.copyOf(landmarks)) {
            landmark.tick();
        }
    }

    private void refresh(Player player) {
        boolean pack = audience.hasPack(player);
        for (ModelCreature creature : creatures) {
            creature.refresh(player, pack);
        }
        for (Landmark landmark : landmarks) {
            landmark.refresh(player, pack);
        }
    }

    /** A pack player's melee hit on a creature's hitbox becomes their attack on the mob. */
    @EventHandler(priority = EventPriority.LOW)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        ModelCreature creature = byHitbox.get(event.getAttacked().getUniqueId());
        if (creature == null) {
            return;
        }
        event.setCancelled(true);
        if (creature.body.isValid() && !creature.body.isDead()) {
            melee.accept(event.getPlayer(), creature.body);
        }
    }

    /** A model display to the pose {@code to} (in the model's authored frame, front -z) in {@code ticks}. */
    private static void animate(ItemDisplay display, Transformation to, int ticks) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(ticks);
        display.setTransformation(ItemDisplayModels.asAuthored(to));
    }

    /**
     * An attack's puff of particles ahead of a creature standing at {@code feet} (its yaw is where it looks), drifting
     * forward; seen by everyone.
     */
    static void puff(Location feet, Puff puff) {
        double yaw = Math.toRadians(feet.getYaw());
        Vector ahead = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        Location at = feet.clone().add(ahead.clone().multiply(puff.forward())).add(0, puff.up(), 0);
        Vector drift = ahead.clone().add(new Vector(0, 0.15, 0));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < puff.count(); i++) {
            Location p = at.clone().add(random.nextGaussian() * puff.spread(), random.nextGaussian() * puff.spread(),
                    random.nextGaussian() * puff.spread());
            feet.getWorld().spawnParticle(puff.particle(), p, 0, drift.getX(), drift.getY(), drift.getZ(),
                    puff.speed());
        }
    }

    /**
     * A step's pose of a part moved by an attack / flinch {@code offset} (about the feet, in the model's frame: its
     * front is -z). Every part of a creature gets the same offset, so the model moves as one; wings spread too.
     */
    static Transformation pose(Part part, int step, boolean moving, Offset offset) {
        Transformation base = pose(part, step, moving);
        if (offset.equals(Offset.REST)) {
            return base;
        }
        Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(offset.yaw()))
                .rotateX((float) Math.toRadians(-offset.pitch())).rotateZ((float) Math.toRadians(-offset.roll()));
        Vector3f squash = new Vector3f(offset.width(), offset.height(), offset.width());
        Vector3f at = new Vector3f(base.getTranslation()).mul(squash).rotate(turn)
                .add(0, offset.up(), -offset.forward());
        Vector3f scale = new Vector3f(base.getScale()).mul(squash);
        if (part.motion() == Motion.FLAP) {
            scale.x *= Math.max(0.2f, 1 + offset.wings());
        }
        return new Transformation(at, turn.mul(base.getLeftRotation()), scale, base.getRightRotation());
    }

    /** A step's pose of a part (shared by creatures and landmarks): its height and motion. */
    static Transformation pose(Part part, int step, boolean moving) {
        float y = part.height();
        float s = part.scale();
        Vector3f scale = new Vector3f(s, s, s);
        Quaternionf rotation = new Quaternionf();
        boolean even = step % 2 == 0;
        float quarter = (float) (Math.PI / 2);
        switch (part.motion()) {
            case WADDLE, STOMP -> {
                if (moving) {
                    float tilt = part.motion() == Motion.WADDLE ? 7 : 4;
                    rotation.rotateZ((float) Math.toRadians(even ? tilt : -tilt));
                    y += even ? 0.05f * s : 0;
                } else {
                    scale.y *= even ? 1f : 1.03f;
                }
            }
            case ROLL -> y += moving && even ? 0.03f : 0;
            case WOBBLE -> scale.mul(even ? 1.08f : 0.95f, even ? 0.9f : 1.08f, even ? 1.08f : 0.95f);
            case SCURRY -> y += moving && even ? 0.04f : 0;
            case HOP -> y += moving && even ? 0.3f : 0;
            case BOB -> {
                y += even ? 0 : 0.05f;
                rotation.rotateY((float) Math.toRadians(even ? 6 : -6));
            }
            case FLAP -> {
                scale.x *= even ? 1f : 0.7f;
                y += even ? 0 : 0.2f;
            }
            case KEY_Z -> rotation.rotateZ(step * quarter);
            case KEY_Y, SPIN -> rotation.rotateY(step * quarter);
            case NONE -> {
            }
        }
        return new Transformation(new Vector3f(0, y, 0), rotation, scale, new Quaternionf());
    }

    // ------------------------------------------------------------------------------------------ creatures

    /** One dressed creature: its model displays, its hitbox for pack players, and the per-viewer swap. */
    public final class ModelCreature implements Visual {

        private final LivingEntity body;
        private final String kind;
        private final List<Part> parts;
        private final @Nullable Color team;
        private final List<ItemDisplay> displays = new ArrayList<>();
        private final Interaction hitbox;
        private final int[] steps;
        private Location last;
        private int age;
        private final Attack attack;
        private int flash;
        /** The tick of the attack / flinch playing (1 = its first), 0 = none. */
        private int attackTick;
        private int hitTick;
        private boolean wasMoving;
        private boolean removed;

        ModelCreature(LivingEntity body, String kind, List<Part> parts, @Nullable Color team) {
            this.body = body;
            this.kind = kind;
            this.parts = parts;
            this.attack = CreatureModels.ATTACKS.getOrDefault(kind, Attack.THRUST);
            this.team = team;
            this.steps = new int[parts.size()];
            this.last = body.getLocation();
            World world = body.getWorld();
            Location at = facing(last);
            for (Part part : parts) {
                displays.add(world.spawn(at, ItemDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setVisibleByDefault(false); // only players with the pack are shown it
                    d.setItemStack(part.ref().item(team));
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    d.setTransformation(ItemDisplayModels.asAuthored(pose(part, 0, false)));
                    d.setTeleportDuration(TELEPORT_TICKS);
                    d.setViewRange(VIEW_RANGE);
                    d.setShadowRadius(0);
                }));
            }
            float width = (float) Math.max(0.4, body.getBoundingBox().getWidthX());
            float height = (float) Math.max(0.4, body.getBoundingBox().getHeight());
            hitbox = world.spawn(last.clone(), Interaction.class, i -> {
                i.setPersistent(false);
                i.setVisibleByDefault(false); // only pack players need it: everyone else hits the vanilla mob
                i.setInteractionWidth(width);
                i.setInteractionHeight(height);
                i.setResponsive(true);
            });
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (audience.hasPack(player)) {
                    refresh(player, true);
                }
            }
        }

        @Override
        public void hit() {
            if (removed) {
                return;
            }
            flash = FLASH_TICKS;
            for (ItemDisplay display : displays) {
                display.setGlowColorOverride(Color.WHITE);
                display.setGlowing(true);
            }
            hitTick = 1;
            show(1);
        }

        /** Plays the kind's {@link CreatureModels.Attack} from the start (again, if it was still playing). */
        @Override
        public void attack() {
            if (removed) {
                return;
            }
            attackTick = 1;
            puffs();
            show(1);
        }

        @Override
        public void remove() {
            if (removed) {
                return;
            }
            removed = true;
            for (ItemDisplay display : displays) {
                display.remove();
            }
            displays.clear();
            hitbox.remove();
            byHitbox.remove(hitbox.getUniqueId());
            if (body.isValid() && !body.isDead()) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.showEntity(plugin, body); // the vanilla mob back for everyone
                }
            } else if (body.isDead()) {
                // Died: pack players never see the vanilla body (its death animation, its vanilla name) appear in
                // the model's place; a little poof instead. The server forgets the hidden body when it is removed.
                body.getWorld().spawnParticle(Particle.POOF, last.clone().add(0, 0.5, 0), 8, 0.3, 0.3, 0.3, 0.02);
            }
            creatures.remove(this);
            stopWhenIdle();
        }

        // ------------------------------------------------------------------ for tests

        String kind() {
            return kind;
        }

        /** The model displays, one per part (tests, checks). */
        public List<ItemDisplay> displays() {
            return List.copyOf(displays);
        }

        Interaction hitbox() {
            return hitbox;
        }

        boolean isRemoved() {
            return removed;
        }

        Attack attackMotion() {
            return attack;
        }

        /** The attack's tick playing (1 = its first), 0 = none. */
        int attackTick() {
            return attackTick;
        }

        int hitTick() {
            return hitTick;
        }

        // ------------------------------------------------------------------ per viewer, ticking

        void refresh(Player player, boolean pack) {
            if (removed) {
                return;
            }
            for (ItemDisplay display : displays) {
                if (pack) {
                    player.showEntity(plugin, display);
                } else {
                    player.hideEntity(plugin, display);
                }
            }
            if (pack) {
                player.showEntity(plugin, hitbox);
                player.hideEntity(plugin, body);
            } else {
                player.hideEntity(plugin, hitbox);
                player.showEntity(plugin, body);
            }
        }

        void tick() {
            if (removed) {
                return;
            }
            if (!body.isValid() || body.isDead()) {
                // gone without a remove(): a little poof where it stood (remove() makes it for a death), then clean up
                if (!body.isDead()) {
                    body.getWorld().spawnParticle(Particle.POOF, last.clone().add(0, 0.5, 0), 8, 0.3, 0.3, 0.3, 0.02);
                }
                remove();
                return;
            }
            age++;
            Location now = body.getLocation();
            double dx = now.getX() - last.getX();
            double dz = now.getZ() - last.getZ();
            boolean moving = dx * dx + dz * dz > 0.0004;
            boolean turned = Math.abs(now.getYaw() - last.getYaw()) > 0.5f;
            if (moving || turned || Math.abs(now.getY() - last.getY()) > 0.001) {
                Location at = facing(now);
                for (ItemDisplay display : displays) {
                    display.teleport(at);
                }
                hitbox.teleport(now);
            }
            last = now;
            if (flash > 0 && --flash == 0) {
                for (ItemDisplay display : displays) {
                    display.setGlowing(false);
                    display.setGlowColorOverride(null);
                }
            }
            boolean restart = moving != wasMoving;
            wasMoving = moving;
            boolean playing = playing();
            if (attackTick > 0 && ++attackTick > attack.ticks()) {
                attackTick = 0;
            }
            if (hitTick > 0 && ++hitTick > CreatureModels.HIT_TICKS) {
                hitTick = 0;
            }
            if (playing()) {
                puffs();
                show(1);
                return;
            }
            if (playing) {
                show(2); // back into the motion's pose
            }
            for (int i = 0; i < parts.size(); i++) {
                Motion motion = parts.get(i).motion();
                int period = moving ? motion.moving : motion.idle;
                if (period > 0 && (age % period == 0 || restart)) {
                    steps[i]++;
                    animate(displays.get(i), pose(parts.get(i), steps[i], moving), period);
                } else if (period == 0 && restart) {
                    animate(displays.get(i), pose(parts.get(i), steps[i], false), 4);
                }
            }
        }

        private boolean playing() {
            return attackTick > 0 || hitTick > 0;
        }

        /** Where the attack and the flinch put the model now (rest when neither plays). */
        Offset offset() {
            Offset out = attackTick > 0 ? attack.at(attackTick) : Offset.REST;
            return hitTick > 0 ? out.plus(CreatureModels.hitAt(hitTick)) : out;
        }

        /** Every part to the motion's pose moved by {@link #offset()}, reached in {@code ticks}. */
        private void show(int ticks) {
            Offset offset = offset();
            for (int i = 0; i < parts.size(); i++) {
                animate(displays.get(i), pose(parts.get(i), steps[i], wasMoving, offset), ticks);
            }
        }

        /** The attack's particle puffs of this tick, for everyone (they are where the vanilla mob is too). */
        private void puffs() {
            if (attackTick <= 0) {
                return;
            }
            for (Puff puff : attack.puffs()) {
                if (puff.tick() == attackTick) {
                    puff(body.getLocation(), puff);
                }
            }
        }

        /**
         * The display location for the mob at {@code at}. A yaw turns an entity's +z towards where it looks; the models
         * face -z as authored ({@link ItemDisplayModels#asAuthored} keeps them so), so the display is turned half round
         * from the mob and the face looks where the mob looks (and walks).
         */
        private Location facing(Location at) {
            Location out = at.clone();
            out.setYaw(at.getYaw() + 180);
            out.setPitch(0);
            return out;
        }
    }

    // ------------------------------------------------------------------------------------------ landmarks

    /** A capture landmark: model displays for pack players, a block-display sketch for everyone else. */
    public final class Landmark {

        private final Location base;
        private final String kind;
        private final List<ItemDisplay> displays = new ArrayList<>();
        private final List<BlockDisplay> fallback = new ArrayList<>();
        private final List<Part> parts;
        private final Quaternionf facing;
        private @Nullable Color owner;
        private int age;
        private int spin;
        private boolean removed;

        Landmark(Location base, String kind, @Nullable Color owner) {
            this.base = base;
            this.kind = kind;
            this.owner = owner;
            this.parts = CreatureModels.LANDMARKS.get(kind);
            this.facing = new Quaternionf().rotationY((float) Math.PI - (float) Math.toRadians(base.getYaw()));
            World world = base.getWorld();
            Location at = base.clone();
            at.setYaw(0);
            at.setPitch(0);
            for (Part part : parts) {
                displays.add(world.spawn(at, ItemDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setVisibleByDefault(false);
                    d.setItemStack(part.ref().item(owner));
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    d.setTransformation(ItemDisplayModels.asAuthored(turned(pose(part, 0, false))));
                    d.setViewRange(VIEW_RANGE * 2);
                    d.setShadowRadius(0);
                }));
            }
            for (Sketch box : sketch(kind, owner)) {
                fallback.add(world.spawn(at, BlockDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setBlock(box.material().createBlockData());
                    d.setTransformation(boxTransform(box));
                    d.setViewRange(VIEW_RANGE * 2);
                    d.setShadowRadius(0);
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (audience.hasPack(player)) {
                            player.hideEntity(plugin, d); // hidden inside the spawn: pack players never see it
                        }
                    }
                }));
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (audience.hasPack(player)) {
                    displays.forEach(d -> player.showEntity(plugin, d));
                }
            }
        }

        /** The owner changed (captured, or neutral again with null): pennants and ribbons take its colour. */
        public void owner(@Nullable Color owner) {
            if (removed) {
                return;
            }
            this.owner = owner;
            for (int i = 0; i < parts.size(); i++) {
                displays.get(i).setItemStack(parts.get(i).ref().item(owner));
            }
            List<Sketch> boxes = sketch(kind, owner);
            for (int i = 0; i < fallback.size(); i++) {
                fallback.get(i).setBlock(boxes.get(i).material().createBlockData());
            }
            base.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, base.clone().add(0, 2, 0), 20, 1.5, 1, 1.5, 0);
        }

        public void remove() {
            if (removed) {
                return;
            }
            removed = true;
            displays.forEach(Entity::remove);
            fallback.forEach(Entity::remove);
            displays.clear();
            fallback.clear();
            landmarks.remove(this);
            stopWhenIdle();
        }

        List<ItemDisplay> displays() {
            return List.copyOf(displays);
        }

        List<BlockDisplay> fallback() {
            return List.copyOf(fallback);
        }

        @Nullable Color owner() {
            return owner;
        }

        void refresh(Player player, boolean pack) {
            if (removed) {
                return;
            }
            for (ItemDisplay display : displays) {
                if (pack) {
                    player.showEntity(plugin, display);
                } else {
                    player.hideEntity(plugin, display);
                }
            }
            for (BlockDisplay block : fallback) {
                if (pack) {
                    player.hideEntity(plugin, block);
                } else {
                    player.showEntity(plugin, block);
                }
            }
        }

        void tick() {
            age++;
            for (int i = 0; i < parts.size(); i++) {
                Part part = parts.get(i);
                int period = part.motion().idle;
                if (period > 0 && age % period == 0) {
                    spin++;
                    animate(displays.get(i), turned(pose(part, spin, false)), period);
                }
            }
        }

        private Transformation turned(Transformation t) {
            return new Transformation(t.getTranslation(), new Quaternionf(facing).mul(t.getLeftRotation()),
                    t.getScale(), t.getRightRotation());
        }

        private Transformation boxTransform(Sketch box) {
            Vector3f min = new Vector3f(box.x0(), box.y0(), box.z0()).rotate(facing);
            Vector3f size = new Vector3f(box.x1() - box.x0(), box.y1() - box.y0(), box.z1() - box.z0());
            return new Transformation(min, new Quaternionf(facing), size, new Quaternionf());
        }
    }

    /** One box of a landmark's vanilla sketch, blocks relative to the base, front towards -z. */
    record Sketch(float x0, float y0, float z0, float x1, float y1, float z1, Material material) {

        static Sketch of(double x0, double y0, double z0, double x1, double y1, double z1, Material material) {
            return new Sketch((float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1, material);
        }
    }

    /** The vanilla sketch of a landmark; the owner's wool marks who holds it (white = nobody). */
    static List<Sketch> sketch(String kind, @Nullable Color owner) {
        Material flag = owner == null ? Material.WHITE_WOOL : wool(owner);
        List<Sketch> out = new ArrayList<>();
        if (CreatureModels.FOUNTAIN.equals(kind)) {
            out.add(Sketch.of(-2.5, 0, -2.5, 2.5, 0.9, 2.5, Material.PINK_CONCRETE));
            out.add(Sketch.of(-2.25, 0.9, -2.25, 2.25, 1.0, 2.25, Material.LIGHT_BLUE_CONCRETE));
            out.add(Sketch.of(-0.35, 1.0, -0.35, 0.35, 2.6, 0.35, Material.WHITE_CONCRETE));
            out.add(Sketch.of(-1.15, 2.6, -1.15, 1.15, 3.0, 1.15, Material.LIME_CONCRETE));
            out.add(Sketch.of(-0.06, 3.0, -0.06, 0.06, 3.9, 0.06, Material.WHITE_CONCRETE));
            out.add(Sketch.of(-0.5, 3.9, -0.12, 0.5, 4.9, 0.12, Material.RED_CONCRETE));
            for (int[] c : new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                out.add(Sketch.of(c[0] * 2.4 + 0.08, 1.85, c[1] * 2.4 - 0.03, c[0] * 2.4 + 0.5, 2.35, c[1] * 2.4 + 0.03,
                        flag));
            }
        } else {
            out.add(Sketch.of(-1.6, 0, -1.6, 1.6, 1.6, 1.6, Material.LIGHT_BLUE_CONCRETE));
            out.add(Sketch.of(-1.3, 1.6, -1.3, 1.3, 3.0, 1.3, Material.YELLOW_CONCRETE));
            out.add(Sketch.of(-1.0, 3.0, -1.0, 1.0, 4.4, 1.0, Material.PINK_CONCRETE));
            out.add(Sketch.of(-1.2, 4.4, -1.2, 1.2, 5.6, 1.2, Material.LIME_CONCRETE));
            out.add(Sketch.of(-1.7, 5.6, -1.7, 1.7, 5.9, 1.7, Material.OAK_PLANKS));
            out.add(Sketch.of(-0.3, 6.5, -1.6, 0.3, 7.1, 0.6, Material.GOLD_BLOCK));
            out.add(Sketch.of(1.45, 5.9, 1.45, 1.55, 7.8, 1.55, Material.WHITE_CONCRETE));
            out.add(Sketch.of(1.55, 7.3, 1.47, 2.2, 7.75, 1.53, flag));
        }
        return out;
    }

    /** The wool closest to a colour. */
    static Material wool(Color color) {
        DyeColor best = DyeColor.WHITE;
        double bestDistance = Double.MAX_VALUE;
        for (DyeColor dye : DyeColor.values()) {
            Color c = dye.getColor();
            double d = Math.pow(c.getRed() - color.getRed(), 2) + Math.pow(c.getGreen() - color.getGreen(), 2)
                    + Math.pow(c.getBlue() - color.getBlue(), 2);
            if (d < bestDistance) {
                bestDistance = d;
                best = dye;
            }
        }
        Material block = Material.matchMaterial(best.name() + "_WOOL");
        return block == null ? Material.WHITE_WOOL : block;
    }
}
