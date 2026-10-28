package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Contact;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Decision;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Carries out one bot's decisions: it perceives (line of sight, footsteps nearby, memory of the last known position:
 * never through walls), asks its {@link BotBrain} every {@code reaction} ticks and acts every tick: walking with the
 * pathfinder, strafing, swinging (with jump crits), drawing and leading the bow, eating golden apples, building its
 * base during the farming and walling itself in when hunted. The {@link BotProfile} sets how well it does all that.
 */
public final class BotController {

    /** How long a bot remembers where it last saw or heard someone. */
    static final int MEMORY_TICKS = 200;
    /** Footsteps are heard this close (sneaking: {@link #SNEAK_HEARING}). */
    static final double HEARING = 10;
    static final double SNEAK_HEARING = 3;
    static final double SIGHT = 64;
    static final double REACH = 3.0;
    static final int DRAW_TICKS = 20;
    static final int APPLE_TICKS = 32;
    /** After walling itself in once, how long before it may do it again. */
    static final int FORTIFY_COOLDOWN = 30 * 20;
    /** A player with food heals about 1 health every 4 s out of combat; so does a bot (it does not eat). */
    static final int REGEN_TICKS = 80;
    static final int REGEN_AFTER_HURT = 100;

    private record Seen(LivingEntity entity, Location at, long tick, double distance, double previous) {
    }

    private final UUID id;
    private final String name;
    private final BotDifficulty difficulty;
    private final BotManager manager;
    private final BotHost host;
    private final BotProfile profile;
    private final BotBrain brain;
    private final Random random;
    private final int offset;
    private @Nullable BotBody body;
    private Gear.Loadout loadout = Gear.at(0);
    private int apples;
    private boolean applesGiven;
    private int arrows;
    private int blocksUsed;
    private int arrowsUsed;
    private @Nullable Decision decision;
    private long nextThink;
    private long nextPath;
    private @Nullable Location goal;
    private final Map<UUID, Seen> memory = new HashMap<>();
    private BasePlanner.@Nullable Plan base;
    private BasePlanner.@Nullable Palette palette;
    private boolean baseFailed;
    private int built;
    private double buildCredit;
    private final List<Block> fortress = new ArrayList<>();
    private long fortifyUntil = -1;
    private long fortifyAgainAt;
    private @Nullable Location shore;
    private long shoreUntil;
    private double lastHealth = -1;
    private long lastHurtAt;
    private long nextSwing;
    private long drawStart = -1;
    private long nextShot;
    private long nextAppleAt;
    private long nextShieldAt;
    private int strafe = 1;
    private long strafeAt;
    /** The head (camera) as the client sees it. */
    private float yaw;
    private float pitch;
    private float bodyYaw;
    /** Where the bot means to look this tick (NaN: nothing in particular), set by {@link #face}. */
    private float wantYaw = Float.NaN;
    private float wantPitch = Float.NaN;
    /** A sound it turned towards (someone heard but not seen). */
    private @Nullable Location glance;
    private long glanceUntil;
    private long eatLookUntil;
    private long idleLookAt;
    private float idleYaw;
    private float idlePitch;
    private @Nullable Location wander;
    private long wanderUntil;
    private @Nullable Location holdSpot;
    private @Nullable Location lastPos;
    private long lastMoveCheck;
    private float lastFall;

    BotController(UUID id, String name, BotDifficulty difficulty, BotManager manager, BotHost host, BotProfile profile,
                  Random random, int offset) {
        this.id = id;
        this.name = name;
        this.difficulty = difficulty;
        this.manager = manager;
        this.host = host;
        this.profile = profile;
        this.brain = new BotBrain(profile);
        this.random = random;
        this.offset = offset;
        this.nextThink = offset % 20; // decisions of different bots fall on different ticks
    }

    public UUID id() {
        return id;
    }

    /** The name on its body ("철수 봇"); the match shows {@code session.name(id)} (with the difficulty when mixed). */
    public String name() {
        return name;
    }

    public BotDifficulty difficulty() {
        return difficulty;
    }

    public @Nullable BotBody body() {
        return body;
    }

    void attach(BotBody spawned) {
        this.body = spawned;
        Location at = spawned.legs().getLocation();
        this.yaw = BotAim.wrap(at.getYaw());
        this.bodyYaw = yaw;
        this.idleYaw = yaw;
        spawned.dress(loadout);
    }

    public Gear.Loadout loadout() {
        return loadout;
    }

    public int apples() {
        return apples;
    }

    public int blocks() {
        return Math.max(0, loadout.blocks() - blocksUsed);
    }

    public int arrows() {
        return Math.max(0, loadout.arrows() - arrowsUsed);
    }

    public @Nullable Decision decision() {
        return decision;
    }

    public BasePlanner.@Nullable Plan base() {
        return base;
    }

    /** Blocks of the base placed so far. */
    public int built() {
        return built;
    }

    BotProfile profile() {
        return profile;
    }

    /** The gear score others read on this bot. */
    double gearScore() {
        return Gear.score(loadout.armorPoints() + bonus(Attribute.ARMOR), loadout.toughness(),
                loadout.sword().damage() + bonus(Attribute.ATTACK_DAMAGE));
    }

    // =================================================================== tick

    void tick(long now) {
        BotBody b = body;
        if (b == null || !b.valid()) {
            return;
        }
        if ((now + offset) % 20 == 0) {
            updateGear();
        }
        regenerate(now, b.body());
        if (host.fight() && !applesGiven) {
            applesGiven = true;
            apples = profile.goldenApples();
        }
        if (now >= nextThink) {
            think(now);
            nextThink = now + profile.reactionTicks() + (random.nextInt(3) == 0 ? 1 : 0);
        }
        wantYaw = Float.NaN;
        wantPitch = Float.NaN;
        try {
            if (drowning(b.body())) {
                surface(now, b); // nothing else matters while out of air
            } else {
                act(now, b);
            }
        } catch (RuntimeException e) {
            manager.log("bot " + name, e);
        }
        fall(b);
        look(now, b);
        b.sync(yaw, pitch, bodyYaw);
        breathe(b);
    }

    /** Under water and running out of air. */
    private static boolean drowning(Mannequin self) {
        return self.getRemainingAir() < self.getMaximumAir() * 0.6 && self.getEyeLocation().getBlock().isLiquid();
    }

    /** Swims up and heads for the nearest dry ground. */
    private void surface(long now, BotBody b) {
        WanderingTrader legs = b.legs();
        Vector velocity = legs.getVelocity();
        legs.setVelocity(velocity.setY(Math.max(velocity.getY(), 0.3)));
        Location at = legs.getLocation();
        if (shore == null || now >= shoreUntil) {
            shore = nearestDry(at, 24);
            shoreUntil = now + 60;
        }
        if (shore != null) {
            goTo(now, shore, 1.3);
        }
    }

    /** The body breathes while the legs' head is above the water (it is put where the legs are, not swimming). */
    private static void breathe(BotBody b) {
        Mannequin self = b.body();
        if (self.getRemainingAir() < self.getMaximumAir() && !self.getEyeLocation().getBlock().isLiquid()
                && !b.legs().getEyeLocation().getBlock().isLiquid()) {
            self.setRemainingAir(self.getMaximumAir());
        }
    }

    /** The nearest standing spot on dry ground within {@code range} blocks (rings of 4 blocks), or null. */
    public static @Nullable Location nearestDry(Location from, int range) {
        World world = from.getWorld();
        for (int r = 4; r <= range; r += 4) {
            for (int i = 0; i < 16; i++) {
                double angle = i * Math.PI / 8;
                int x = from.getBlockX() + (int) Math.round(Math.cos(angle) * r);
                int z = from.getBlockZ() + (int) Math.round(Math.sin(angle) * r);
                Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (top.getType().isSolid() && !top.isLiquid()) {
                    return new Location(world, x + 0.5, top.getY() + 1, z + 0.5);
                }
            }
        }
        return null;
    }

    /**
     * Turns the camera like a player moving the mouse: towards what it deals with ({@link #face}: a target, the bow's
     * aim, a block being placed), else a sound it heard, else where it walks, else an occasional look around; at the
     * difficulty's turn speed, the short way round. The body follows the walking direction (or the head when it
     * fights or stands), never more than {@link BotAim#BODY_LAG} from the head.
     */
    private void look(long now, BotBody b) {
        float speed = difficulty.turnSpeed();
        boolean explicit = !Float.isNaN(wantYaw);
        Location at = b.legs().getLocation();
        float moveYaw = movementYaw(b);
        boolean moving = !Float.isNaN(moveYaw);
        float targetYaw;
        float targetPitch;
        if (explicit) {
            targetYaw = wantYaw;
            targetPitch = wantPitch;
        } else if (glance != null && now < glanceUntil && glance.getWorld() == at.getWorld()) {
            Location eye = at.clone().add(0, 1.62, 0);
            targetYaw = BotAim.yaw(glance.getX() - eye.getX(), glance.getZ() - eye.getZ(), yaw);
            targetPitch = BotAim.pitch(glance.getX() - eye.getX(), glance.getY() + 1.5 - eye.getY(),
                    glance.getZ() - eye.getZ());
        } else if (moving) {
            targetYaw = moveYaw;
            targetPitch = 5f;
        } else {
            if (now >= idleLookAt) {
                idleLookAt = now + 40 + random.nextInt(60);
                idleYaw = BotAim.wrap(bodyYaw + (random.nextFloat() * 2 - 1) * 70f);
                idlePitch = -10f + random.nextFloat() * 25f;
            }
            targetYaw = idleYaw;
            targetPitch = idlePitch;
        }
        if (now < eatLookUntil) {
            targetPitch = Math.max(targetPitch, 30f); // eating: a glance down at the apple
        }
        yaw = BotAim.turn(yaw, targetYaw, speed, explicit && difficulty.flicks());
        pitch = BotAim.turnPitch(pitch, targetPitch, speed * 0.7f);
        float bodyWanted = !explicit && moving ? moveYaw : yaw;
        bodyYaw = BotAim.body(bodyYaw, yaw, bodyWanted, speed * 0.6f);
    }

    /** The walking direction (from the legs' velocity or the next path point), NaN when standing. */
    private float movementYaw(BotBody b) {
        Vector velocity = b.legs().getVelocity();
        if (velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ() > 4.0E-4) {
            return BotAim.yaw(velocity.getX(), velocity.getZ(), yaw);
        }
        if (goal == null) {
            return Float.NaN;
        }
        Location at = b.legs().getLocation();
        Location next = null;
        try {
            var path = b.legs().getPathfinder().getCurrentPath();
            next = path == null ? null : path.getNextPoint();
        } catch (RuntimeException ignored) {
            // no path information: head for the goal
        }
        Location towards = next != null ? next : goal;
        if (towards.getWorld() != at.getWorld() || towards.distanceSquared(at) < 0.25) {
            return Float.NaN;
        }
        return BotAim.yaw(towards.getX() - at.getX(), towards.getZ() - at.getZ(), yaw);
    }

    /** The head's yaw now (for tests). */
    public float headYaw() {
        return yaw;
    }

    /** The head's pitch now (for tests). */
    public float headPitch() {
        return pitch;
    }

    /** Natural regeneration like a fed player's: 1 health every 4 s once not hurt for 5 s. */
    private void regenerate(long now, Mannequin self) {
        double health = self.getHealth();
        if (lastHealth >= 0 && health < lastHealth) {
            lastHurtAt = now;
        }
        if (now - lastHurtAt >= REGEN_AFTER_HURT && (now + offset) % REGEN_TICKS == 0 && !self.isDead()) {
            health = Math.min(maxHealth(self), health + 1);
            self.setHealth(health);
        }
        lastHealth = health;
    }

    /** The gear grows on the schedule (difficulty scales the pace). */
    void updateGear() {
        Gear.Loadout next = Gear.at(host.farmingProgress() * profile.gearPace());
        if (next.score() > loadout.score() || next.blocks() > loadout.blocks()) {
            boolean dress = next.score() != loadout.score() || next.shield() != loadout.shield();
            loadout = next;
            if (dress && body != null) {
                body.dress(loadout);
            }
        }
    }

    // =================================================================== perception and decision

    void think(long now) {
        BotBody b = body;
        if (b == null) {
            return;
        }
        perceive(now, b);
        decision = brain.decide(situation(now, b));
    }

    private void perceive(long now, BotBody b) {
        Mannequin self = b.body();
        Location at = self.getLocation();
        for (LivingEntity rival : host.rivals(id, self.getWorld())) {
            UUID who = host.participantOf(rival);
            if (who == null) {
                continue;
            }
            double distance = rival.getLocation().distance(at);
            boolean heard = distance <= (rival instanceof Player p && p.isSneaking() ? SNEAK_HEARING : HEARING);
            boolean seen = distance <= SIGHT && self.hasLineOfSight(rival);
            if (heard && !seen) {
                Seen known = memory.get(who);
                if (known == null || now - known.tick() > 40) {
                    glance = rival.getLocation(); // a step behind it: turn towards the sound
                    glanceUntil = now + 25;
                }
            }
            if (seen || heard) {
                Seen old = memory.get(who);
                memory.put(who, new Seen(rival, rival.getLocation(), now, distance,
                        old == null ? distance : old.distance()));
            }
        }
        memory.values().removeIf(seen -> now - seen.tick() > MEMORY_TICKS || seen.entity().isDead()
                || !host.isEnemy(id, seen.entity()));
    }

    BotBrain.Situation situation(long now, BotBody b) {
        Mannequin self = b.body();
        Location at = self.getLocation();
        List<Contact> contacts = new ArrayList<>();
        for (Map.Entry<UUID, Seen> entry : memory.entrySet()) {
            Seen seen = entry.getValue();
            LivingEntity rival = seen.entity();
            boolean visible = seen.tick() == now;
            double distance = visible ? seen.distance() : seen.at().distance(at);
            contacts.add(new Contact(entry.getKey(), distance, rival.getHealth() + rival.getAbsorptionAmount(),
                    maxHealth(rival), manager.gearOf(rival), visible, manager.fightingOther(entry.getKey(), id, now),
                    seen.distance() < seen.previous() - 0.5, holdsBow(rival)));
        }
        // the nearer of where the border is now and where it will be in 20 s (it keeps closing in)
        double off = Math.max(Math.abs(at.getX() - host.borderCenterX()), Math.abs(at.getZ() - host.borderCenterZ()));
        double margin = Math.min(host.borderHalfIn(0), host.borderHalfIn(host.borderShrinking() ? 400 : 0)) - off;
        double zone = Math.hypot(at.getX() - host.borderCenterX(), at.getZ() - host.borderCenterZ());
        BasePlanner.Plan plan = base;
        boolean baseBuilt = plan != null && built >= plan.placements().size() / 2;
        boolean baseDone = baseFailed || plan != null && built >= plan.placements().size();
        double baseDistance = plan == null ? Double.POSITIVE_INFINITY
                : Math.hypot(at.getX() - (plan.x() + 0.5), at.getZ() - (plan.z() + 0.5));
        boolean baseSafe = plan != null && host.borderHalfIn(1200) - Math.max(Math.abs(plan.x() - host.borderCenterX()),
                Math.abs(plan.z() - host.borderCenterZ())) > plan.half() + 2;
        Decision current = decision;
        return new BotBrain.Situation(host.fight(), Math.max(0, 1 - host.farmingProgress()), self.getHealth(),
                self.getAbsorptionAmount(), maxHealth(self), apples, now >= nextAppleAt, gearScore(),
                loadout.bow() && arrows() > 0, now < fortifyAgainAt ? 0 : blocks(), margin, host.borderShrinking(),
                zone, host.finalHalf(),
                baseBuilt, baseDone, baseDistance, baseSafe, highGround(at),
                current == null ? null : current.behaviour(), current == null ? null : current.target(), contacts,
                host.pressure());
    }

    private boolean highGround(Location at) {
        World world = at.getWorld();
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            double angle = i * Math.PI / 2;
            sum += world.getHighestBlockYAt(at.getBlockX() + (int) Math.round(Math.cos(angle) * 8),
                    at.getBlockZ() + (int) Math.round(Math.sin(angle) * 8), HeightMap.MOTION_BLOCKING_NO_LEAVES);
        }
        return at.getY() - 1 - sum / 4 >= 3;
    }

    static boolean holdsBow(LivingEntity entity) {
        if (entity.getEquipment() == null) {
            return false;
        }
        Material held = entity.getEquipment().getItemInMainHand().getType();
        return held == Material.BOW || held == Material.CROSSBOW;
    }

    static double maxHealth(LivingEntity entity) {
        AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? 20.0 : max.getValue();
    }

    // =================================================================== acting

    private void act(long now, BotBody b) {
        Decision d = decision;
        if (d == null) {
            return;
        }
        Mannequin self = b.body();
        LivingEntity target = d.target() == null ? null : known(d.target());
        if (d.behaviour() != Behaviour.FORTIFY && !fortress.isEmpty()) {
            breakOut();
        }
        if (d.behaviour() != Behaviour.KITE && d.behaviour() != Behaviour.ENGAGE && d.behaviour() != Behaviour.AVOID
                && drawStart >= 0) {
            drawStart = -1;
            b.hold(false, loadout.sword());
        }
        switch (d.behaviour()) {
            case FARM -> {
                roam(now, self, 40);
                defendAgainstMobs(now, self);
            }
            case BUILD_BASE -> buildBase(now, self);
            case SCOUT -> {
                Location lead = lastKnown();
                if (lead != null) {
                    goTo(now, lead, 1.1);
                } else {
                    scout(now, self);
                }
                swingAtAnyone(now, self);
            }
            case ENGAGE -> {
                if (target == null) {
                    think(now);
                    return;
                }
                engage(now, b, self, target, where(d.target(), target, now));
            }
            case KITE -> {
                if (target == null) {
                    think(now);
                    return;
                }
                Location at = where(d.target(), target, now);
                double distance = self.getLocation().distance(at);
                if (distance < 9) {
                    goTo(now, away(self.getLocation(), at, 12), 1.3);
                } else if (distance > profile.bowRange() * 0.85) {
                    goTo(now, at, 1.1);
                } else {
                    stop();
                }
                face(self, target.getEyeLocation());
                if (!shoot(now, b, self, target)) {
                    tryMelee(now, self, target);
                }
                eatIfLow(now, self);
            }
            case RETREAT_HEAL -> {
                Location threat = nearestThreat();
                if (threat != null) {
                    goTo(now, away(self.getLocation(), threat, 14), 1.3);
                } else {
                    stop();
                }
                eatIfLow(now, self);
                swingAtAnyone(now, self);
            }
            case FORTIFY -> {
                stop();
                fortify(now, self);
                eatIfLow(now, self);
                swingAtAnyone(now, self);
            }
            case DEFEND_BASE -> {
                BasePlanner.Plan plan = base;
                if (plan == null) {
                    roam(now, self, 20);
                } else if (!plan.inside(self.getLocation().getX(), self.getLocation().getZ())) {
                    goTo(now, new Location(self.getWorld(), plan.x() + 0.5, plan.floorY() + 1, plan.z() + 0.5), 1.3);
                } else {
                    stop();
                    face(self, new Location(self.getWorld(), plan.doorX() + 0.5, plan.floorY() + 1.6,
                            plan.doorZ() + 0.5));
                }
                eatIfLow(now, self);
                swingAtAnyone(now, self);
            }
            case HOLD_ZONE -> {
                if (holdSpot == null) {
                    double r = host.finalHalf() * 0.5;
                    holdSpot = ground(self.getWorld(), host.borderCenterX() + (random.nextDouble() * 2 - 1) * r,
                            host.borderCenterZ() + (random.nextDouble() * 2 - 1) * r);
                }
                if (holdSpot.distance(self.getLocation()) > 2) {
                    goTo(now, holdSpot, 1.1);
                } else {
                    stop();
                }
                eatIfLow(now, self);
                swingAtAnyone(now, self);
            }
            case AMBUSH -> {
                stop();
                if (target != null) {
                    face(self, target.getEyeLocation());
                    if (self.getLocation().distance(target.getLocation()) < 5) {
                        decision = new Decision(Behaviour.ENGAGE, d.target(), d.score());
                    }
                }
            }
            case THIRD_PARTY -> {
                if (target == null) {
                    think(now);
                    return;
                }
                Location at = where(d.target(), target, now);
                double distance = self.getLocation().distance(at);
                if (distance < 14) {
                    goTo(now, away(self.getLocation(), at, 8), 1.1);
                } else if (distance > 24) {
                    goTo(now, at, 1.1);
                } else {
                    stop();
                    face(self, target.getEyeLocation());
                }
                swingAtAnyone(now, self);
            }
            case AVOID -> {
                if (target != null) {
                    goTo(now, away(self.getLocation(), where(d.target(), target, now), 16), 1.3);
                    if (profile.bowSkill() >= 0.9) {
                        shoot(now, b, self, target); // a strong bot shoots while backing off
                    }
                }
                eatIfLow(now, self);
                swingAtAnyone(now, self);
            }
            case FLEE_BORDER -> {
                goTo(now, ground(self.getWorld(), host.borderCenterX(), host.borderCenterZ()), 1.35);
                eatIfLow(now, self);
            }
        }
        unstick(now, b);
    }

    private void engage(long now, BotBody b, Mannequin self, LivingEntity target, Location at) {
        double distance = self.getLocation().distance(at);
        face(self, at.clone().add(0, target.getEyeHeight(), 0));
        if (distance > REACH + 0.6) {
            goTo(now, at, 1.3);
            if (distance > 10 && distance <= profile.bowRange() && shoot(now, b, self, target)) {
                return;
            }
        } else {
            if (drawStart >= 0) {
                drawStart = -1;
                b.hold(false, loadout.sword());
            }
            if (now >= strafeAt) {
                strafeAt = now + 15 + random.nextInt(20);
                if (random.nextDouble() < profile.strafeChance()) {
                    strafe = -strafe;
                }
            }
            Vector toTarget = target.getLocation().toVector().subtract(self.getLocation().toVector()).setY(0);
            if (toTarget.lengthSquared() > 1.0E-6 && random.nextDouble() < profile.strafeChance()) {
                Vector side = new Vector(-toTarget.getZ(), 0, toTarget.getX()).normalize().multiply(1.5 * strafe);
                goTo(now, target.getLocation().add(side), 1.2);
            } else {
                goTo(now, target.getLocation(), 1.2);
            }
            tryMelee(now, self, target);
        }
        eatIfLow(now, self);
    }

    // =================================================================== fighting

    /** The damage of a swing: the sword plus the plugin's attribute bonuses (augments), times the scale. */
    double swingDamage() {
        BotBody b = body;
        double damage = loadout.sword().damage();
        if (b != null) {
            AttributeInstance attack = b.body().getAttribute(Attribute.ATTACK_DAMAGE);
            if (attack != null) {
                double scalar = 1;
                for (AttributeModifier modifier : attack.getModifiers()) {
                    if (!manager.ours(modifier)) {
                        continue;
                    }
                    switch (modifier.getOperation()) {
                        case ADD_NUMBER -> damage += modifier.getAmount();
                        case ADD_SCALAR, MULTIPLY_SCALAR_1 -> scalar *= 1 + modifier.getAmount();
                    }
                }
                damage *= scalar;
            }
        }
        return Math.max(0.5, damage) * profile.damageScale();
    }

    private double bonus(Attribute attribute) {
        BotBody b = body;
        if (b == null) {
            return 0;
        }
        AttributeInstance instance = b.body().getAttribute(attribute);
        if (instance == null) {
            return 0;
        }
        double sum = 0;
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (manager.ours(modifier) && modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                sum += modifier.getAmount();
            }
        }
        return sum;
    }

    /** Swings at {@code target} when in reach and the sword is charged; true if it swung. */
    boolean tryMelee(long now, Mannequin self, LivingEntity target) {
        double reach = REACH + target.getWidth() / 2;
        if (now < nextSwing || target.isDead() || self.getLocation().distance(target.getLocation()) > reach + 0.3
                || !self.hasLineOfSight(target)) {
            return false;
        }
        face(self, target.getEyeLocation());
        if (!aimedAt(self, target)) {
            return false; // still turning towards them
        }
        nextSwing = now + profile.attackTicks();
        self.swingMainHand();
        double hitChance = Math.max(0.5, Math.min(1, 1 - profile.aimError() / 25.0));
        if (random.nextDouble() >= hitChance) {
            self.getWorld().playSound(self.getLocation(), Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.6f, 1f);
            return true;
        }
        boolean crit = random.nextDouble() < profile.critChance();
        BotBody b = body;
        if (crit && b != null) {
            b.legs().setVelocity(b.legs().getVelocity().setY(0.36));
        }
        double damage = swingDamage() * (crit ? 1.5 : 1.0);
        target.damage(damage, DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(self)
                .withDirectEntity(self).build());
        if (crit) {
            self.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, target.getHeight() * 0.6, 0),
                    12, 0.3, 0.3, 0.3, 0.1);
            self.getWorld().playSound(self.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1f);
        }
        if (profile.strafeChance() >= 0.7 && !target.isDead()) {
            // a sprint (w-tap) hit knocks back further
            Vector push = target.getLocation().toVector().subtract(self.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 1.0E-6) {
                target.setVelocity(target.getVelocity().add(push.normalize().multiply(0.3).setY(0.05)));
            }
        }
        return true;
    }

    /** Draws and looses an arrow at {@code target}; true while busy with the bow. */
    boolean shoot(long now, BotBody b, Mannequin self, LivingEntity target) {
        if (!loadout.bow() || arrows() <= 0 || profile.bowRange() <= 0 || now < nextShot) {
            return false;
        }
        double distance = self.getLocation().distance(target.getLocation());
        if (distance < 6 || distance > profile.bowRange() || !self.hasLineOfSight(target)) {
            if (drawStart >= 0) {
                drawStart = -1;
                b.hold(false, loadout.sword());
            }
            return false;
        }
        if (drawStart < 0) {
            drawStart = now;
            b.hold(true, loadout.sword());
        }
        Location eye = self.getEyeLocation();
        Location aim = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        Vector velocity = target.getVelocity();
        double[] launch = Ballistics.launch(new double[] {eye.getX(), eye.getY(), eye.getZ()},
                new double[] {aim.getX(), aim.getY(), aim.getZ()},
                new double[] {velocity.getX(), velocity.getY(), velocity.getZ()}, profile.bowSkill());
        faceAlong(launch); // the camera follows the lead and the arc while drawing
        if (now - drawStart < DRAW_TICKS) {
            return true;
        }
        launch = Ballistics.scatter(launch, profile.aimError() * 0.6, random);
        Arrow arrow = self.launchProjectile(Arrow.class, new Vector(launch[0], launch[1], launch[2]));
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setShooter(self);
        manager.session().track(arrow);
        self.getWorld().playSound(self.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1f);
        arrowsUsed++;
        drawStart = -1;
        nextShot = now + 4;
        b.hold(false, loadout.sword());
        return true;
    }

    private void eatIfLow(long now, Mannequin self) {
        if (apples <= 0 || now < nextAppleAt || self.getHealth() > profile.healAt()) {
            return;
        }
        apples--;
        nextAppleAt = now + APPLE_TICKS + 40;
        eatLookUntil = now + APPLE_TICKS / 2;
        self.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1, false, true, true));
        self.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0, false, true, true));
        self.getWorld().playSound(self.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.8f, 1f);
        self.getWorld().playSound(self.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.5f, 1f);
    }

    private void swingAtAnyone(long now, Mannequin self) {
        for (Seen seen : memory.values()) {
            if (seen.tick() == now || seen.entity().getLocation().distance(self.getLocation()) <= REACH + 0.5) {
                if (tryMelee(now, self, seen.entity())) {
                    return;
                }
            }
        }
    }

    private void defendAgainstMobs(long now, Mannequin self) {
        for (Entity nearby : self.getNearbyEntities(REACH, 2, REACH)) {
            if (nearby instanceof Enemy && nearby instanceof LivingEntity mob && !(nearby instanceof WanderingTrader)
                    && !manager.isLegs(nearby)) {
                face(self, mob.getEyeLocation());
                tryMelee(now, self, mob);
                return;
            }
        }
    }

    /** Called by the manager before a frontal melee hit lands: true if the shield took it. */
    boolean block(long now, @Nullable Entity attacker) {
        BotBody b = body;
        if (b == null || !loadout.shield() || attacker == null || now < nextShieldAt
                || random.nextDouble() >= profile.shieldChance()) {
            return false;
        }
        Vector facing = b.body().getLocation().getDirection().setY(0);
        Vector toAttacker = attacker.getLocation().toVector().subtract(b.body().getLocation().toVector()).setY(0);
        if (facing.lengthSquared() < 1.0E-6 || toAttacker.lengthSquared() < 1.0E-6
                || facing.normalize().dot(toAttacker.normalize()) < 0.3) {
            return false;
        }
        nextShieldAt = now + 30;
        b.body().getWorld().playSound(b.body().getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
        return true;
    }

    // =================================================================== building

    private void buildBase(long now, Mannequin self) {
        BasePlanner.Plan plan = base;
        if (plan == null) {
            plan = planBase(self);
            if (plan == null) {
                baseFailed = true;
                think(now);
                return;
            }
        }
        Location center = new Location(self.getWorld(), plan.x() + 0.5, plan.floorY() + 1, plan.z() + 0.5);
        if (center.distance(self.getLocation()) > 3) {
            goTo(now, center, 1.1);
            return;
        }
        stop();
        buildCredit += profile.buildPerSecond() * Math.max(1, host.timeScale()) / 20.0;
        while (buildCredit >= 1 && built < plan.placements().size()) {
            buildCredit--;
            BasePlanner.Placement next = plan.placements().get(built++);
            if (place(self, next.x(), next.y(), next.z(), material(next.part()))) {
                face(self, new Location(self.getWorld(), next.x() + 0.5, next.y() + 0.5, next.z() + 0.5));
            }
        }
        if (built >= plan.placements().size()) {
            buildCredit = 0;
        } else {
            BasePlanner.Placement next = plan.placements().get(built); // eyes on where the next block goes
            face(self, new Location(self.getWorld(), next.x() + 0.5, next.y() + 0.5, next.z() + 0.5));
        }
    }

    private Material material(BasePlanner.Part part) {
        BasePlanner.Palette p = palette != null ? palette : BasePlanner.Palette.forBiome(null);
        Material material = switch (part) {
            case WALL -> Material.matchMaterial(p.wall());
            case PILLAR -> Material.matchMaterial(p.pillar());
            case ROOF -> Material.matchMaterial(p.roof());
            case CHEST -> Material.CHEST;
            case CRAFTING_TABLE -> Material.CRAFTING_TABLE;
            case COBWEB -> Material.COBWEB;
        };
        return material != null && material.isBlock() ? material : Material.COBBLESTONE;
    }

    private BasePlanner.@Nullable Plan planBase(Mannequin self) {
        World world = self.getWorld();
        Location at = self.getLocation();
        double cx = host.borderCenterX();
        double cz = host.borderCenterZ();
        List<int[]> candidates = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            // around itself, drawn towards where the border will end
            double towards = 0.15 + random.nextDouble() * 0.35;
            double x = at.getX() + (cx - at.getX()) * towards + (random.nextDouble() * 2 - 1) * 24;
            double z = at.getZ() + (cz - at.getZ()) * towards + (random.nextDouble() * 2 - 1) * 24;
            Location spot = new Location(world, x, at.getY(), z);
            if (host.arena().contains(spot)) {
                candidates.add(new int[] {(int) Math.floor(x), (int) Math.floor(z)});
            }
        }
        candidates.add(new int[] {at.getBlockX(), at.getBlockZ()});
        WorldTerrain terrain = new WorldTerrain(world);
        int[] spot = BasePlanner.choose(terrain, candidates, profile.baseSize(), cx, cz, manager.baseCenters(id));
        if (spot == null) {
            return null;
        }
        BasePlanner.Plan plan = BasePlanner.plan(terrain, spot[0], spot[1], profile.baseSize(), cx, cz);
        base = plan;
        int y = terrain.height(spot[0], spot[1]);
        palette = BasePlanner.Palette.forBiome(world.getBiome(spot[0], y, spot[1]).getKey().toString());
        return plan;
    }

    /**
     * Places a block of the bot's stock: only in the match world, only into air or replaceable plants (never water,
     * lava or the void), never where a living entity stands.
     */
    boolean place(Mannequin self, int x, int y, int z, Material material) {
        World world = self.getWorld();
        if (!world.equals(host.world()) || blocks() <= 0 || y <= world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }
        Block block = world.getBlockAt(x, y, z);
        Material type = block.getType();
        if (!(type.isAir() || block.isReplaceable() && !block.isLiquid())) {
            return false;
        }
        if (!host.arena().contains(block.getLocation().add(0.5, 0, 0.5))) {
            return false;
        }
        for (Entity entity : world.getNearbyEntities(block.getBoundingBox().expand(0.05))) {
            if (entity instanceof LivingEntity && !manager.isLegs(entity) || entity == self) {
                return false; // never trap anyone (or itself) inside a block
            }
        }
        block.setType(material);
        blocksUsed++;
        self.swingMainHand();
        world.playSound(block.getLocation(), Sound.BLOCK_STONE_PLACE, 0.8f, 1f);
        return true;
    }

    /** Walls itself in where it stands: four sides two high and a roof, a few blocks per second. */
    private void fortify(long now, Mannequin self) {
        if (fortifyUntil < 0) {
            fortifyUntil = now + 20 * 15;
        }
        Location feet = self.getLocation();
        int x = feet.getBlockX();
        int y = feet.getBlockY();
        int z = feet.getBlockZ();
        int[][] spots = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
                {0, 2, 0}};
        buildCredit += profile.buildPerSecond() * 2 / 20.0;
        for (int[] spot : spots) {
            if (buildCredit < 1) {
                break;
            }
            Block block = self.getWorld().getBlockAt(x + spot[0], y + spot[1], z + spot[2]);
            if (fortress.contains(block) || !block.getType().isAir()) {
                continue;
            }
            buildCredit--;
            if (place(self, block.getX(), block.getY(), block.getZ(), material(BasePlanner.Part.WALL))) {
                fortress.add(block);
                face(self, block.getLocation().add(0.5, 0.5, 0.5));
            }
        }
        if (self.getHealth() >= Math.min(maxHealth(self), profile.healAt() * 1.7) || now >= fortifyUntil) {
            breakOut();
            decision = null;
            nextThink = now;
        }
    }

    /**
     * Takes the shelter down again (a player mines their way out): every block of it that is still its own goes, so
     * no box is left around it that would hide it from everyone for the rest of the match.
     */
    private void breakOut() {
        Iterator<Block> it = fortress.iterator();
        Material wall = material(BasePlanner.Part.WALL);
        while (it.hasNext()) {
            Block block = it.next();
            if (block.getType() == wall) {
                block.setType(Material.AIR);
            }
            it.remove();
        }
        if (fortifyUntil >= 0) {
            fortifyAgainAt = host.clock() + FORTIFY_COOLDOWN; // no hiding in a box over and over
        }
        fortifyUntil = -1;
    }

    // =================================================================== moving

    private void goTo(long now, Location where, double speed) {
        BotBody b = body;
        if (b == null) {
            return;
        }
        Location dest = insideBorder(where);
        Location old = goal;
        if (now < nextPath && old != null && old.getWorld() == dest.getWorld() && old.distanceSquared(dest) < 2.25) {
            return;
        }
        goal = dest;
        nextPath = now + 8;
        b.legs().getPathfinder().moveTo(dest, speed * speedFactor());
    }

    private void stop() {
        BotBody b = body;
        goal = null;
        if (b != null && b.legs().getPathfinder().hasPath()) {
            b.legs().getPathfinder().stopPathfinding();
        }
    }

    private double speedFactor() {
        BotBody b = body;
        AttributeInstance speed = b == null ? null : b.body().getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null || speed.getBaseValue() <= 0) {
            return 1.0;
        }
        return Math.max(0.3, Math.min(3.0, speed.getValue() / speed.getBaseValue()));
    }

    private Location insideBorder(Location where) {
        double half = Math.max(1.5, host.borderHalfIn(host.borderShrinking() ? 200 : 0) - 3);
        double x = clamp(where.getX(), host.borderCenterX() - half, host.borderCenterX() + half);
        double z = clamp(where.getZ(), host.borderCenterZ() - half, host.borderCenterZ() + half);
        Location out = where.clone();
        out.setX(x);
        out.setZ(z);
        if (!host.arena().contains(out)) {
            out = host.arena().clampInside(out);
        }
        return out;
    }

    private void roam(long now, Mannequin self, double range) {
        if (wander == null || now >= wanderUntil || wander.distance(self.getLocation()) < 2) {
            Location at = self.getLocation();
            wander = ground(self.getWorld(), at.getX() + (random.nextDouble() * 2 - 1) * range,
                    at.getZ() + (random.nextDouble() * 2 - 1) * range);
            wanderUntil = now + 300;
        }
        goTo(now, wander, 1.0);
    }

    /**
     * Looks for someone: roams, but each leg of the walk is pulled towards the final zone (more as the fight goes
     * on), where everyone ends up; so bots spread over a big world still meet.
     */
    private void scout(long now, Mannequin self) {
        if (wander == null || now >= wanderUntil || wander.distance(self.getLocation()) < 2) {
            Location at = self.getLocation();
            double pull = scoutPull(host.pressure());
            double x = at.getX() + (host.borderCenterX() - at.getX()) * pull + (random.nextDouble() * 2 - 1) * 24;
            double z = at.getZ() + (host.borderCenterZ() - at.getZ()) * pull + (random.nextDouble() * 2 - 1) * 24;
            wander = ground(self.getWorld(), x, z);
            wanderUntil = now + 300;
        }
        goTo(now, wander, 1.1);
    }

    /** The share of the way to the zone center a scouting leg covers (0.3 at the fight start, 0.8 at the end). */
    static double scoutPull(double pressure) {
        return 0.3 + 0.5 * Math.max(0, Math.min(1, pressure));
    }

    /** The standing spot of column {@code x, z}; over water or lava the nearest dry ground near it if any. */
    private static Location ground(World world, double x, double z) {
        Block top = world.getHighestBlockAt((int) Math.floor(x), (int) Math.floor(z),
                HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Location spot = new Location(world, x, top.getY() + 1, z);
        if (top.isLiquid()) {
            Location dry = nearestDry(spot, 12); // walk around lakes, not through them
            if (dry != null) {
                return dry;
            }
        }
        return spot;
    }

    private static Location away(Location from, Location threat, double distance) {
        Vector out = from.toVector().subtract(threat.toVector()).setY(0);
        if (out.lengthSquared() < 1.0E-6) {
            out = new Vector(1, 0, 0);
        }
        return from.clone().add(out.normalize().multiply(distance));
    }

    /** Means to look at {@code at} this tick (the head turns there at its turn speed, see {@link #look}). */
    private void face(Mannequin self, Location at) {
        Location eye = self.getEyeLocation();
        double dx = at.getX() - eye.getX();
        double dy = at.getY() - eye.getY();
        double dz = at.getZ() - eye.getZ();
        if (dx * dx + dy * dy + dz * dz < 1.0E-6) {
            return;
        }
        wantYaw = BotAim.yaw(dx, dz, yaw);
        wantPitch = BotAim.pitch(dx, dy, dz);
    }

    /** Means to look along {@code direction} this tick (the bow's launch, arc included). */
    private void faceAlong(double[] direction) {
        wantYaw = BotAim.yaw(direction[0], direction[2], yaw);
        wantPitch = BotAim.pitch(direction[0], direction[1], direction[2]);
    }

    /** True when the head points at {@code target} closely enough to land a swing (within 60°). */
    private boolean aimedAt(Mannequin self, LivingEntity target) {
        Location eye = self.getEyeLocation();
        Location to = target.getLocation();
        float wanted = BotAim.yaw(to.getX() - eye.getX(), to.getZ() - eye.getZ(), yaw);
        return Math.abs(BotAim.delta(yaw, wanted)) <= 60f;
    }

    /** Stuck in place while it should walk: hop, then pick another way. */
    private void unstick(long now, BotBody b) {
        if (now - lastMoveCheck < 40) {
            return;
        }
        Location at = b.legs().getLocation();
        if (goal != null && lastPos != null && lastPos.getWorld() == at.getWorld()
                && lastPos.distanceSquared(at) < 0.04 && b.legs().isOnGround()) {
            b.legs().setVelocity(b.legs().getVelocity().setY(0.42));
            nextPath = now;
            wander = null;
        }
        lastPos = at;
        lastMoveCheck = now;
    }

    /** The body has no gravity of its own: falls of the legs hurt the body like a player's fall. */
    private void fall(BotBody b) {
        WanderingTrader legs = b.legs();
        float now = legs.getFallDistance();
        if (legs.isOnGround() && lastFall > 3.0f && !legs.isInWater()) {
            b.body().damage(lastFall - 3.0, DamageSource.builder(DamageType.FALL).build());
        }
        lastFall = legs.isOnGround() ? 0f : now;
    }

    /** Where {@code who} is as far as the bot knows: live while perceived, else where it was last seen or heard. */
    private Location where(UUID who, LivingEntity entity, long now) {
        Seen seen = memory.get(who);
        return seen == null || now - seen.tick() <= profile.reactionTicks() ? entity.getLocation() : seen.at().clone();
    }

    private @Nullable LivingEntity known(UUID who) {
        Seen seen = memory.get(who);
        return seen == null || seen.entity().isDead() ? null : seen.entity();
    }

    private @Nullable Location lastKnown() {
        Seen best = null;
        for (Seen seen : memory.values()) {
            if (best == null || seen.distance() < best.distance()) {
                best = seen;
            }
        }
        return best == null ? null : best.at();
    }

    private @Nullable Location nearestThreat() {
        return lastKnown();
    }

    /** What it drops when it dies: its gear, apples and arrows left. */
    List<ItemStack> loot() {
        List<ItemStack> out = new ArrayList<>();
        String[] pieces = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};
        for (int slot = 0; slot < 4; slot++) {
            ItemStack item = BotBody.item(loadout.armor(slot), pieces[slot]);
            if (item != null) {
                out.add(item);
            }
        }
        ItemStack sword = BotBody.sword(loadout.sword());
        if (sword != null) {
            out.add(sword);
        }
        if (apples > 0) {
            out.add(ItemStack.of(Material.GOLDEN_APPLE, apples));
        }
        if (loadout.bow()) {
            out.add(ItemStack.of(Material.BOW));
            if (arrows() > 0) {
                out.add(ItemStack.of(Material.ARROW, Math.min(64, arrows())));
            }
        }
        return out;
    }

    /** Forgets a combatant (eliminated). */
    void forget(UUID who) {
        memory.remove(who);
    }

    /** For tests: forces the next decision now. */
    void decideNow(long now) {
        think(now);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** {@link BasePlanner.Terrain} over a Bukkit world. */
    record WorldTerrain(World world) implements BasePlanner.Terrain {
        @Override
        public int height(int x, int z) {
            return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        }

        @Override
        public BasePlanner.Surface surface(int x, int z) {
            int y = height(x, z);
            if (y <= world.getMinHeight()) {
                return BasePlanner.Surface.NONE;
            }
            Material type = world.getBlockAt(x, y, z).getType();
            if (type == Material.LAVA || type == Material.MAGMA_BLOCK) {
                return BasePlanner.Surface.LAVA;
            }
            if (type == Material.WATER || type == Material.BUBBLE_COLUMN || type == Material.ICE
                    || type == Material.KELP || type == Material.SEAGRASS) {
                return BasePlanner.Surface.WATER;
            }
            return BasePlanner.Surface.SOLID;
        }
    }
}
