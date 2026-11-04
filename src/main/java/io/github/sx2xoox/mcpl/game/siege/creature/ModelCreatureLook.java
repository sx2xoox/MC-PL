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
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureModels.Gait;
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
import org.bukkit.SoundCategory;
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
import org.bukkit.event.player.PlayerInteractEntityEvent;
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
 *   <li>players with the pack ({@link PackAudience#hasPack}) see item displays of the creature's models following its
 *       body ({@link CreatureBodies}: one invisible neutral mob for every kind; teleported every tick with a short
 *       teleport interpolation, turned with it) and animated as the toy it is: its {@link Gait} (the soldier's march,
 *       the jelly's bounce, the rabbit's hops, the dragon's stride, with soft step sounds), its attack and skill
 *       motions ({@link Visual#attack(Attack)}), the shiver of a skill being charged, a flinch on
 *       {@link Visual#hit()}, a stagger when pushed, the dragon's rage glow. They hit it through an invisible
 *       {@link Interaction} the size of the model, whose melee attacks are passed on to the body as the player's own
 *       attack (so damage, kill credit and the game's damage rules work as for the body);</li>
 *   <li>everyone else never receives the model displays or the hitbox (the server expects every player to have the
 *       pack).</li>
 * </ul>
 * A player's view swaps when their pack status changes. The look never moves, damages or removes the body, and players'
 * clicks on the body itself do nothing. Landmarks ({@link #landmark}) are models on a base with a block-display
 * fallback, like the towers.
 *
 * <p>Note for the game: the body is hidden from pack players, so anything that must stay visible to them (name plate,
 * health bar) must be its own entity, not a passenger of the body.
 */
public final class ModelCreatureLook implements CreatureSkin, Listener {

    private static final int TELEPORT_TICKS = 2;
    private static final int FLASH_TICKS = 3;
    private static final float VIEW_RANGE = 2f;
    /** The glow of an enraged creature. */
    static final Color RAGE = Color.fromRGB(0xFF, 0x45, 0x45);

    private final Plugin plugin;
    private final PackAudience audience;
    private final BiConsumer<Player, LivingEntity> melee;
    private final List<ModelCreature> creatures = new ArrayList<>();
    private final List<Landmark> landmarks = new ArrayList<>();
    private final Map<UUID, ModelCreature> byHitbox = new HashMap<>();
    private final Map<UUID, ModelCreature> byBody = new HashMap<>();
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
        byBody.put(body.getUniqueId(), creature);
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

    /**
     * The top of the model dressing {@code body} above its feet, blocks ({@link CreatureModels#TOPS}), or 0 when the
     * body wears none.
     */
    public double modelTop(LivingEntity body) {
        ModelCreature creature = byBody.get(body.getUniqueId());
        return creature == null ? 0 : creature.top();
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

    /**
     * Nothing to do with a creature's body by hand: it is only the toy's hitbox and legs (a player's click on it never
     * shears, dyes, feeds or leashes it). The shop terminal answers its own clicks.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (byBody.containsKey(event.getRightClicked().getUniqueId())) {
            event.setCancelled(true);
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
    static void puff(Location feet, Puff puff, float size) {
        double yaw = Math.toRadians(feet.getYaw());
        Vector ahead = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        Location at = feet.clone().add(ahead.clone().multiply(puff.forward() * size)).add(0, puff.up() * size, 0);
        Vector drift = ahead.clone().add(new Vector(0, 0.15, 0));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < puff.count(); i++) {
            double spread = puff.spread() * size;
            Location p = at.clone().add(random.nextGaussian() * spread, random.nextGaussian() * spread,
                    random.nextGaussian() * spread);
            feet.getWorld().spawnParticle(puff.particle(), p, 0, drift.getX(), drift.getY(), drift.getZ(),
                    puff.speed());
        }
    }

    /** {@link #pose(Part, int, boolean, Offset, float)} of a toy at its modelled size. */
    static Transformation pose(Part part, int age, boolean moving, Offset offset) {
        return pose(part, age, moving, offset, 1);
    }

    /**
     * A part's pose {@code age} ticks into its life, moved by the whole model's {@code offset} (gait, attack, flinch,
     * stagger: about the feet, in the model's frame, its front -z) on a toy {@code size} times as big as modelled. Every
     * part of a creature gets the same offset, so the model moves as one; wings spread too.
     */
    static Transformation pose(Part part, int age, boolean moving, Offset offset, float size) {
        Transformation base = pose(part, age, moving, size);
        if (offset.equals(Offset.REST)) {
            return base;
        }
        Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(offset.yaw()))
                .rotateX((float) Math.toRadians(-offset.pitch())).rotateZ((float) Math.toRadians(-offset.roll()));
        Vector3f squash = new Vector3f(offset.width(), offset.height(), offset.width());
        Vector3f at = new Vector3f(base.getTranslation()).mul(squash).rotate(turn)
                .add(0, offset.up() * size, -offset.forward() * size);
        Vector3f scale = new Vector3f(base.getScale()).mul(squash);
        if (part.motion() == Motion.FLAP) {
            scale.x *= Math.max(0.2f, 1 + offset.wings());
        }
        return new Transformation(at, turn.mul(base.getLeftRotation()), scale, base.getRightRotation());
    }

    /** {@link #pose(Part, int, boolean, float)} at the modelled size. */
    static Transformation pose(Part part, int age, boolean moving) {
        return pose(part, age, moving, 1);
    }

    /**
     * A part's own pose {@code age} ticks into its life (shared by creatures and landmarks): its height and its own
     * motion, continuous in time (a wind-up key turns a quarter every {@code period} ticks, wings beat once in two).
     */
    static Transformation pose(Part part, int age, boolean moving, float size) {
        float y = part.height() * size;
        float s = part.scale() * size;
        Vector3f scale = new Vector3f(s, s, s);
        Quaternionf rotation = new Quaternionf();
        Motion motion = part.motion();
        int period = Math.max(1, moving ? motion.moving : motion.idle);
        float quarter = (float) (Math.PI / 2);
        switch (motion) {
            case FLAP -> {
                float beat = (float) (1 - Math.cos(Math.PI * age / period)) / 2;
                scale.x *= 1 - 0.3f * beat;
                y += 0.2f * size * beat;
            }
            case KEY_Z -> rotation.rotateZ(quarter * age / period);
            case KEY_Y, SPIN -> rotation.rotateY(quarter * age / period);
            default -> {
                // a body: its gait moves the whole model
            }
        }
        return new Transformation(new Vector3f(0, y, 0), rotation, scale, new Quaternionf());
    }

    // ------------------------------------------------------------------------------------------ creatures

    /**
     * One dressed creature: its model displays following the body, its hitbox for pack players, the per-viewer swap,
     * and the toy's own animation: its {@link Gait} (walking or standing), the attack and skill motions, the warning
     * shiver of a skill being charged, the flinch, the stagger of a push, the rage glow.
     */
    public final class ModelCreature implements Visual {

        private final LivingEntity body;
        private final String kind;
        private final List<Part> parts;
        private final @Nullable Color team;
        private final List<ItemDisplay> displays = new ArrayList<>();
        private final Interaction hitbox;
        private final float size;
        private final Gait gait;
        private final Attack basic;
        private Location last;
        private int age;
        /** Ticks walked since it last stood (the gait's clock). */
        private int walked;
        private Attack attack;
        private int flash;
        /** The tick of the attack / flinch / stagger playing (1 = its first), 0 = none. */
        private int attackTick;
        private int hitTick;
        private int staggerTick;
        private float staggerAhead;
        private float staggerRight;
        private float staggerStrength;
        /** Ticks left of a skill being charged. */
        private int charging;
        private boolean enraged;
        private boolean wasMoving;
        private boolean wasPlaying;
        private boolean removed;

        ModelCreature(LivingEntity body, String kind, List<Part> parts, @Nullable Color team) {
            this.body = body;
            this.kind = kind;
            this.parts = parts;
            this.basic = CreatureModels.ATTACKS.getOrDefault(kind, Attack.THRUST);
            this.attack = basic;
            this.gait = CreatureModels.GAITS.getOrDefault(kind, Gait.MARCH);
            this.team = team;
            this.size = (float) CreatureBodies.size(body, kind);
            this.last = body.getLocation();
            World world = body.getWorld();
            Location at = facing(last);
            for (Part part : parts) {
                displays.add(world.spawn(at, ItemDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setVisibleByDefault(false); // only players with the pack are shown it
                    d.setItemStack(part.ref().item(team));
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    d.setTransformation(ItemDisplayModels.asAuthored(pose(part, 0, false, Offset.REST, size)));
                    d.setTeleportDuration(TELEPORT_TICKS);
                    d.setViewRange(VIEW_RANGE);
                    d.setShadowRadius(0);
                }));
            }
            float width = (float) Math.max(0.4, body.getBoundingBox().getWidthX());
            // as tall as the model where it is taller than the body: a pack player swinging at the rabbit's ears or
            // the dragon's head hits it
            float height = (float) Math.max(0.4, Math.max(body.getBoundingBox().getHeight(), top()));
            hitbox = world.spawn(last.clone(), Interaction.class, i -> {
                i.setPersistent(false);
                i.setVisibleByDefault(false); // only pack players need it: everyone else hits the body
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

        /** The top of the model above the feet, blocks (its {@link CreatureModels#TOPS} at its size). */
        double top() {
            return CreatureModels.TOPS.getOrDefault(kind, 0.0) * size;
        }

        @Override
        public void hit() {
            if (removed) {
                return;
            }
            flash = FLASH_TICKS;
            glow(Color.WHITE);
            hitTick = 1;
            show(1);
        }

        /** Plays the kind's basic {@link CreatureModels.Attack} from the start (again, if it was still playing). */
        @Override
        public void attack() {
            attack(basic);
        }

        @Override
        public void attack(Attack motion) {
            if (removed) {
                return;
            }
            attack = motion;
            attackTick = 1;
            charging = 0;
            puffs();
            show(1);
        }

        @Override
        public void charge(int ticks) {
            if (!removed) {
                charging = Math.max(charging, ticks);
            }
        }

        @Override
        public void stagger(double fromX, double fromZ, double strength) {
            if (removed || strength <= 0) {
                return;
            }
            Location at = body.getLocation();
            double dx = fromX - at.getX();
            double dz = fromZ - at.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0E-6) {
                return;
            }
            double yaw = Math.toRadians(at.getYaw());
            // a yaw turns +z towards where it looks: forward (-sin, cos), its right hand (-cos, -sin)
            staggerAhead = (float) ((-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / length);
            staggerRight = (float) ((-Math.cos(yaw) * dx - Math.sin(yaw) * dz) / length);
            staggerStrength = (float) Math.min(1, strength);
            staggerTick = 1;
            show(1);
        }

        @Override
        public void enraged(boolean enraged) {
            if (removed || this.enraged == enraged) {
                return;
            }
            this.enraged = enraged;
            if (enraged) {
                glow(RAGE);
            } else if (flash == 0) {
                unglow();
            }
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
            byBody.remove(body.getUniqueId(), this);
            if (body.isValid() && !body.isDead()) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.showEntity(plugin, body); // the body back for everyone
                }
            } else if (body.isDead()) {
                // Died: pack players never see the body (its death, its name) appear in the model's place; a little
                // poof instead. The server forgets the hidden body when it is removed.
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

        /** The kind's basic attack motion. */
        Attack attackMotion() {
            return basic;
        }

        /** The motion playing or last played. */
        Attack playingMotion() {
            return attack;
        }

        /** The attack's tick playing (1 = its first), 0 = none. */
        int attackTick() {
            return attackTick;
        }

        int hitTick() {
            return hitTick;
        }

        int staggerTick() {
            return staggerTick;
        }

        int charging() {
            return charging;
        }

        boolean isEnraged() {
            return enraged;
        }

        /** How many times its modelled size the toy is. */
        float size() {
            return size;
        }

        Gait gait() {
            return gait;
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
                if (enraged) {
                    glow(RAGE);
                } else {
                    unglow();
                }
            }
            boolean restart = moving != wasMoving;
            wasMoving = moving;
            if (moving) {
                walked++;
                if (gait.stepsAt(walked) && attackTick == 0) {
                    body.getWorld().playSound(now, gait.stepSound(), SoundCategory.NEUTRAL, gait.stepVolume(),
                            gait.stepPitch());
                }
            } else {
                walked = 0;
            }
            if (attackTick > 0 && ++attackTick > attack.ticks()) {
                attackTick = 0;
            }
            if (hitTick > 0 && ++hitTick > CreatureModels.HIT_TICKS) {
                hitTick = 0;
            }
            if (staggerTick > 0 && ++staggerTick > CreatureModels.STAGGER_TICKS) {
                staggerTick = 0;
            }
            if (charging > 0) {
                charging--;
            }
            boolean playing = playing();
            if (playing) {
                puffs();
                show(1);
            } else if (wasPlaying || restart) {
                show(2); // back into the gait's pose
            } else {
                int every = moving ? 2 : 4;
                if (age % every == 0) {
                    show(every);
                }
            }
            wasPlaying = playing;
        }

        private boolean playing() {
            return attackTick > 0 || hitTick > 0 || staggerTick > 0 || charging > 0;
        }

        /** Where the gait, the attack, the charge, the flinch and the stagger put the model now. */
        Offset offset() {
            Offset out;
            if (attackTick > 0) {
                out = attack.at(attackTick);
            } else if (charging > 0) {
                out = CreatureModels.chargeAt(age);
            } else {
                out = wasMoving ? gait.walkAt(walked) : gait.idleAt(age);
            }
            if (hitTick > 0) {
                out = out.plus(CreatureModels.hitAt(hitTick));
            }
            if (staggerTick > 0) {
                out = out.plus(CreatureModels.staggerAt(staggerTick, staggerAhead, staggerRight, staggerStrength));
            }
            return out;
        }

        /** Every part to its pose moved by {@link #offset()}, reached in {@code ticks}. */
        private void show(int ticks) {
            Offset offset = offset();
            for (int i = 0; i < parts.size(); i++) {
                animate(displays.get(i), pose(parts.get(i), age, wasMoving, offset, size), ticks);
            }
        }

        private void glow(Color colour) {
            for (ItemDisplay display : displays) {
                display.setGlowColorOverride(colour);
                display.setGlowing(true);
            }
        }

        private void unglow() {
            for (ItemDisplay display : displays) {
                display.setGlowing(false);
                display.setGlowColorOverride(null);
            }
        }

        /** The attack's particle puffs of this tick, for everyone. */
        private void puffs() {
            if (attackTick <= 0) {
                return;
            }
            for (Puff puff : attack.puffs()) {
                if (puff.tick() == attackTick) {
                    puff(body.getLocation(), puff, size);
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
                    animate(displays.get(i), turned(pose(part, age, false)), period);
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
