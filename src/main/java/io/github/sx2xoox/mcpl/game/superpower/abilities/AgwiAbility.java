package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongPredicate;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 아귀 — the hungry ghost: a belly as big as a mountain, a throat as thin as a needle, and everything that reaches its
 * mouth turns to fire. It always carries a chipped begging bowl.
 *
 * <p>Nobody near it gets to heal in peace: a share of every heal of the enemies around (natural regeneration, food,
 * potions, abilities, lifesteal) is snatched and flows into the ghost's mouth. The begging bowl, thrown, sticks over an
 * enemy's head and catches every heal that enemy gets, then brings it home; an empty bowl clonks the enemy instead. The
 * ultimate turns the ground into the realm of hungry ghosts: nobody there can heal, every heal turns to fire in the
 * mouth, and the realm gnaws at everyone in it while the ghost itself is fed.
 *
 * <p>Look: ash grey, sickly yellow and ember red. Snatched heals are streams of yellow-green motes pouring into the
 * ghost's mouth with a slurp. The bowl spins through the air, then hangs over its victim (turned to every viewer),
 * filling up with a growing count and a sickly glow; it flies home with a trail of motes. The realm is an ash-grey
 * circle with ember flames along its rim, dry cracks running out from the middle and a giant bowl turning overhead,
 * raining ash; a heal in the realm bursts as flames from the victim's mouth.
 *
 * <p>Heals are caught two ways: vanilla heals (regeneration, food, potions) through their regain event, before they
 * land, and everything else (abilities heal by setting health) by watching health rise from one tick to the next and
 * taking the share back.
 */
public final class AgwiAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("agwi", "아귀", Material.BOWL)
            .color(TextColor.color(0xC9B84A))
            .difficulty(1)
            .passive("걸신",
                    "반경 {tax_radius}블록 안의 적이 체력을 회복하면(자연 회복·음식·물약·능력·흡혈 모두) 그 {tax_share}%를 "
                            + "가로챕니다. 적은 나머지만 회복하고, 가로챈 만큼 기운이 내 입으로 빨려 들어와 내가 "
                            + "회복합니다.")
            .active("동냥 그릇",
                    "이 빠진 동냥 그릇을 던집니다({bowl_range}블록). 처음 맞은 적은 {bowl_damage} 피해를 입고, 그릇이 "
                            + "{bowl_duration}초간 그 적의 머리 위에 달라붙어(모두에게 보임) 그 적이 받는 회복을 전부 "
                            + "담습니다(그 적은 회복하지 못함). 끝나면 그릇이 나에게 돌아와 담긴 만큼(최대 {bowl_max})에 "
                            + "덤으로 {bowl_bonus} 더 회복합니다. 아무것도 담지 못했으면 대신 땡그랑 그 적의 머리를 때려 "
                            + "{bowl_bonk} 피해를 줍니다. 빗나가면 쿨타임이 {bowl_refund}% 돌아옵니다.",
                    9)
            .ultimate("아귀도",
                    "{realm_duration}초간 그 자리 반경 {realm_radius}블록을 아귀도로 만듭니다. 안의 적은 회복할 수 없고, "
                            + "회복하려던 만큼 입에서 불꽃이 일어 그만큼 피해를 입습니다(적마다 최대 {realm_burn_max}). "
                            + "또 {realm_interval}초마다 {realm_damage} 피해를 입고 이동 속도가 {realm_slow}% 느려집니다. "
                            + "안에 있는 나는 {realm_interval}초마다 체력을 {realm_heal} 회복합니다. 범위 안에 적이 "
                            + "없으면 쓸 수 없습니다.")
            .param("tax_radius", 8)
            .param("tax_share", 40)
            .param("bowl_range", 16)
            .param("bowl_damage", 3)
            .param("bowl_duration", 5)
            .param("bowl_max", 6)
            .param("bowl_bonus", 1)
            .param("bowl_bonk", 2)
            .param("bowl_refund", 50)
            .param("realm_duration", 6)
            .param("realm_radius", 9)
            .param("realm_burn_max", 6)
            .param("realm_damage", 0.5)
            .param("realm_interval", 1)
            .param("realm_slow", 15)
            .param("realm_heal", 1)
            .build();

    private static final String ASH_TAG = "#A8A59C";
    private static final String HUNGER_TAG = "#C9B84A";
    private static final String EMBER_TAG = "#E0482C";

    // ------------------------------------------------------------------ look
    /** Sickly yellow glow, ash grey body, ember red edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE8DC7A, 0x7C7A74, 0xC2361F);
    private static final Color LIFE = Color.fromRGB(0xB8D45A);
    private static final Color LIFE_PALE = Color.fromRGB(0xE8DC7A);
    private static final Particle.DustOptions LIFE_DUST = new Particle.DustOptions(Color.fromRGB(0xB8D45A), 0.8f);
    private static final Particle.DustOptions SICKLY = new Particle.DustOptions(Color.fromRGB(0xC9B84A), 0.9f);
    private static final Particle.DustOptions ASH = new Particle.DustOptions(Color.fromRGB(0x7C7A74), 1.2f);
    private static final Particle.DustOptions ASH_SMALL = new Particle.DustOptions(Color.fromRGB(0x9A978E), 0.7f);
    private static final Particle.DustTransition EMBER = new Particle.DustTransition(Color.fromRGB(0xFF8A4A),
            Color.fromRGB(0xC2361F), 1.0f);
    private static final Particle.DustTransition HUNGER = PALETTE.glow(1.0f);
    private static final ItemStack BOWL = new ItemStack(Material.BOWL);

    /** Materials of the visuals; block data is created when used (it needs a running server). */
    private static final Material CRACK = Material.BLACKSTONE;
    private static final Material CRACK_GLOW = Material.MAGMA_BLOCK;

    private static final int MAX_DISPLAYS = 24;
    private static final double EPSILON = 1.0E-6;

    // passive
    /** Enemies are watched a little beyond the radius, so the health they bring in is known. */
    private static final double WATCH_MARGIN = 4;
    private static final long STEAL_FX_COOLDOWN = 6;
    private static final long DROOL_PERIOD = 30;

    // active
    private static final double BOWL_SPEED = 1.2;
    private static final double BOWL_HIT_GROW = 0.3;
    private static final float BOWL_FLY_SIZE = 0.6f;
    private static final float BOWL_STUCK_SIZE = 0.7f;
    private static final double BOWL_LIFT = 0.5;
    private static final long BOWL_RETURN_TICKS = 8;
    private static final long BONK_TICKS = 3;

    // ultimate
    private static final int REALM_RISE_TICKS = 8;
    private static final int CRACK_COUNT = 6;
    private static final double CRACK_MIN = 1.2;
    private static final double CRACK_STEP = 0.5;
    private static final double CRACK_PROBE = 1.0;
    private static final double CRACK_STEP_UP = 0.2;
    private static final double REALM_SNAP = 4;
    private static final double BIG_BOWL_HEIGHT = 3.6;
    private static final float BIG_BOWL_SIZE = 2.6f;

    /** What the ghost watches about one enemy's health. */
    private static final class Watch {
        double last;
        /** Health a vanilla heal brought in that the regain event already took its share of. */
        double excused;
        long seen;
        long lastFx = Long.MIN_VALUE / 2;

        Watch(double last, long seen) {
            this.last = last;
            this.seen = seen;
        }
    }

    private final Map<UUID, Watch> watches = new HashMap<>();
    private final List<Display> displays = new ArrayList<>();
    private @Nullable Hunger hunger;
    private @Nullable Bowl bowl;
    private @Nullable Realm realm;
    private long nextDrool;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        Hunger listener = new Hunger();
        hunger = listener;
        Bukkit.getPluginManager().registerEvents(listener, ctx().plugin());
    }

    @Override
    public void onRevoke() {
        Hunger listener = hunger;
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
        hunger = null;
        Realm open = realm;
        if (open != null) {
            open.close(false);
        }
        realm = null;
        bowl = null;
        watches.clear();
        displays.clear();
    }

    /** Package-private for tests: what the bowl holds now (-1 when no bowl hangs over anyone). */
    double bowlHolds() {
        Bowl current = bowl;
        return current == null || current.target == null || current.done ? -1 : current.stored;
    }

    // ------------------------------------------------------------------ passive: the hungry ghost

    /** Who takes an enemy's heal: the realm (all, as fire), the bowl (all, into the bowl), the ghost (a share). */
    private enum Claim { NONE, TAX, BOWL, REALM }

    private Claim claim(LivingEntity enemy) {
        Realm open = realm;
        if (open != null && !open.over && open.contains(enemy)) {
            return Claim.REALM;
        }
        Bowl current = bowl;
        if (current != null && !current.done && enemy.equals(current.target)) {
            return Claim.BOWL;
        }
        if (isHidden(enemy) || !ctx().isAlive(player())) {
            return Claim.NONE;
        }
        double radius = Math.max(0, params().num("tax_radius"));
        Location at = player().getLocation();
        if (!at.getWorld().equals(enemy.getWorld()) || !(radius > 0)) {
            return Claim.NONE;
        }
        return ctx().enemiesNear(player(), at, radius).contains(enemy) ? Claim.TAX : Claim.NONE;
    }

    private double share() {
        return Math.clamp(params().num("tax_share") / 100.0, 0.0, 1.0);
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        double radius = Math.max(0, params().num("tax_radius"));
        Set<UUID> near = new HashSet<>();
        Bowl current = bowl;
        List<LivingEntity> watched = new ArrayList<>(ctx().enemiesNear(owner, owner.getLocation(),
                radius + WATCH_MARGIN));
        if (current != null && current.target != null && !current.done && !watched.contains(current.target)
                && Combat.isTargetable(ctx(), owner, current.target)) {
            // The bowl's victim is watched wherever it runs.
            watched.add(current.target);
        }
        for (LivingEntity enemy : watched) {
            if (!watchesHealth(enemy)) {
                continue;
            }
            UUID id = enemy.getUniqueId();
            near.add(id);
            double health = enemy.getHealth();
            Watch watch = watches.get(id);
            if (watch == null || watch.seen < now - 1) {
                watches.put(id, new Watch(health, now));
                continue;
            }
            double gain = health - watch.last - watch.excused;
            watch.excused = 0;
            watch.seen = now;
            if (gain > EPSILON) {
                Claim claim = claim(enemy);
                switch (claim) {
                    case TAX -> {
                        double stolen = gain * share();
                        enemy.setHealth(Math.max(0, health - stolen));
                        fed(enemy, stolen, watch);
                    }
                    case BOWL -> {
                        enemy.setHealth(Math.max(0, health - gain));
                        if (current != null) {
                            current.store(gain);
                        }
                    }
                    default -> {
                        // The realm watches its own (a borrowed realm has no ghost to do it); nobody else steals.
                    }
                }
            }
            watch.last = enemy.getHealth();
        }
        watches.keySet().retainAll(near);
        if (now >= nextDrool) {
            nextDrool = now + DROOL_PERIOD;
            Location mouth = mouth(owner);
            owner.getWorld().spawnParticle(Particle.WHITE_ASH, mouth, Vfx.n(ctx(), 2), 0.1, 0.05, 0.1, 0);
            owner.getWorld().spawnParticle(Particle.DUST, mouth, 1, 0.05, 0.05, 0.05, 0, SICKLY);
        }
    }

    /**
     * Whose health the watches follow. Not a mannequin's: the training dummies are refilled to full health by the
     * practice mode, which is no heal (taking it back would keep them from ever filling up again).
     */
    private static boolean watchesHealth(LivingEntity enemy) {
        return !(enemy instanceof Mannequin);
    }

    /** What a heal of {@code amount} can really give {@code enemy}: no more than the health it is missing. */
    private static double landing(LivingEntity enemy, double amount) {
        return Math.clamp(Combat.maxHealth(enemy) - enemy.getHealth(), 0.0, Math.max(0, amount));
    }

    /** A snatched heal reaches the ghost: it heals by the same amount, and the stream shows where it came from. */
    private void fed(LivingEntity enemy, double amount, @Nullable Watch watch) {
        if (!(amount > EPSILON)) {
            return;
        }
        Player owner = player();
        ctx().heal(owner, amount);
        long now = ctx().currentTick();
        if (watch != null && now - watch.lastFx < STEAL_FX_COOLDOWN) {
            return;
        }
        if (watch != null) {
            watch.lastFx = now;
        }
        Location chest = enemy.getLocation().add(0, enemy.getHeight() * 0.6, 0);
        Location mouth = mouth(owner);
        World world = chest.getWorld();
        if (world.equals(mouth.getWorld())) {
            int travel = (int) Math.max(5, Math.min(24, chest.distance(mouth) * 2));
            int motes = Vfx.n(ctx(), 6);
            for (int i = 0; i < motes; i++) {
                Vfx.trail(Vfx.scatter(chest, 0.3).add(0, (i % 3 - 1) * 0.15, 0), mouth, i % 2 == 0 ? LIFE : LIFE_PALE,
                        travel + i, false);
            }
        }
        world.spawnParticle(Particle.DUST, chest, Vfx.n(ctx(), 4), 0.2, 0.25, 0.2, 0, SICKLY);
        world.spawnParticle(Particle.DUST, mouth, Vfx.n(ctx(), 3), 0.08, 0.08, 0.08, 0, LIFE_DUST);
        Fx.sound(mouth, Sound.ENTITY_GENERIC_DRINK, 0.7f, 0.55f + Fx.jitter(0.06f));
        Fx.sound(chest, Sound.ENTITY_PHANTOM_BITE, 0.25f, 0.5f + Fx.jitter(0.05f));
        owner.sendActionBar(Text.mm("<" + HUNGER_TAG + ">걸신</" + HUNGER_TAG + "> <white><name></white><gray>의 "
                + "회복을 가로챘습니다</gray> <" + HUNGER_TAG + ">+" + AbilityInfo.formatNumber(round(amount)) + "</"
                + HUNGER_TAG + ">", Placeholder.component("name", enemy.name())));
    }

    /**
     * Vanilla heals of the enemies (regeneration, food, potions) are caught before they land: a share for the ghost,
     * everything for the bowl, everything as fire in the realm. Ability heals set health directly and are caught by the
     * ghost's watch instead.
     */
    private final class Hunger implements Listener {

        private final Map<EntityRegainHealthEvent, Double> pending = new WeakHashMap<>();

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onHeal(EntityRegainHealthEvent event) {
            try {
                intercept(event);
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Agwi heal interception failed", e);
            }
        }

        private void intercept(EntityRegainHealthEvent event) {
            if (!(event.getEntity() instanceof LivingEntity enemy) || !(event.getAmount() > 0)
                    || !ctx().isAlive(player()) || !ctx().isEnemy(player(), enemy)) {
                return;
            }
            // Only what would really land counts: a heal on a nearly full enemy is mostly wasted anyway.
            double amount = landing(enemy, event.getAmount());
            if (!(amount > 0)) {
                return;
            }
            switch (claim(enemy)) {
                case REALM -> {
                    event.setCancelled(true);
                    Realm open = realm;
                    if (open != null) {
                        open.burn(enemy, amount);
                    }
                }
                case BOWL -> {
                    event.setCancelled(true);
                    Bowl current = bowl;
                    if (current != null) {
                        current.store(amount);
                    }
                }
                case TAX -> {
                    double stolen = amount * share();
                    event.setAmount(Math.max(0, amount - stolen));
                    pending.put(event, stolen);
                }
                default -> {
                }
            }
        }

        /** The heal landed: the ghost eats its share, and the watch knows this rise is accounted for. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onHealed(EntityRegainHealthEvent event) {
            Double stolen = pending.remove(event);
            if (stolen == null || !(event.getEntity() instanceof LivingEntity enemy)) {
                return;
            }
            try {
                Watch watch = watches.get(enemy.getUniqueId());
                if (watch != null) {
                    watch.excused += Math.max(0, event.getAmount());
                }
                fed(enemy, stolen, watch);
            } catch (RuntimeException e) {
                ctx().plugin().getLogger().log(Level.WARNING, "Agwi heal interception failed", e);
            }
        }
    }

    // ------------------------------------------------------------------ active: the begging bowl

    @Override
    public ActivationResult onActive() {
        Bowl current = bowl;
        if (current != null && !current.done) {
            return ActivationResult.fail("동냥 그릇이 아직 돌아오지 않았습니다");
        }
        Player owner = player();
        Bowl thrown = new Bowl(owner);
        bowl = thrown;
        thrown.toss();
        return ActivationResult.success();
    }

    /**
     * The begging bowl: thrown, it sticks over the first enemy it hits and catches all of that enemy's healing, then
     * flies home and feeds the ghost (an empty bowl clonks the enemy's head instead).
     */
    private final class Bowl implements LongPredicate {

        private final Player owner;
        private final double range;
        private final long duration;
        private final Location position;
        private final Vector direction;
        private @Nullable ItemDisplay display;
        private @Nullable TextDisplay label;
        private @Nullable LivingEntity target;
        private double travelled;
        private double stored;
        private long stuckAt;
        private boolean done;

        Bowl(Player owner) {
            this.owner = owner;
            this.range = Math.max(1, params().num("bowl_range"));
            this.duration = Math.max(1, params().ticks("bowl_duration"));
            Location eye = owner.getEyeLocation();
            Vector look = eye.getDirection();
            this.direction = look.lengthSquared() < 1.0E-8 ? Motion.horizontalFacing(eye) : look.normalize();
            this.position = hand(owner);
        }

        void toss() {
            long life = (long) Math.ceil(range / BOWL_SPEED) + duration + BOWL_RETURN_TICKS + 20;
            if (reserve(1)) {
                display = keep(Displays.item(ctx(), owner, Displays.unrotated(position), BOWL,
                        flyPose(0), life));
                display.setTeleportDuration(1);
            }
            Location hand = position.clone();
            hand.getWorld().spawnParticle(Particle.DUST, hand, Vfx.n(ctx(), 5), 0.1, 0.1, 0.1, 0, ASH_SMALL);
            Fx.sound(hand, Sound.ENTITY_WITCH_THROW, 0.9f, 0.8f + Fx.jitter(0.08f));
            Fx.sound(hand, Sound.ENTITY_GHAST_AMBIENT, 0.25f, 1.6f + Fx.jitter(0.1f));
            Loop.repeat(ctx(), owner, 1, 1, (long) Math.ceil(range / BOWL_SPEED) + duration + 2, this,
                    this::release);
        }

        @Override
        public boolean test(long tick) {
            if (done) {
                return false;
            }
            if (target == null) {
                return fly(tick);
            }
            return hold(tick);
        }

        // ---------------------------------------------------------------- flight

        private boolean fly(long tick) {
            World world = position.getWorld();
            double length = Math.min(BOWL_SPEED, range - travelled);
            if (length <= 0) {
                miss(position);
                return false;
            }
            LivingEntity hit = null;
            double best = Double.MAX_VALUE;
            for (LivingEntity enemy : ctx().enemiesNear(owner, position, length + 2)) {
                BoundingBox box = enemy.getBoundingBox().expand(BOWL_HIT_GROW);
                RayTraceResult trace = box.rayTrace(position.toVector(), direction, length);
                double distance = box.contains(position.toVector()) ? 0
                        : trace == null ? Double.MAX_VALUE : trace.getHitPosition().distance(position.toVector());
                if (distance < best) {
                    best = distance;
                    hit = enemy;
                }
            }
            RayTraceResult wall = world.rayTraceBlocks(position, direction, length, FluidCollisionMode.NEVER, true);
            double wallDistance = wall == null ? Double.MAX_VALUE : wall.getHitPosition().distance(position.toVector());
            if (hit != null && best <= wallDistance) {
                position.add(direction.clone().multiply(best));
                stick(hit);
                return true;
            }
            if (wall != null) {
                miss(wall.getHitPosition().toLocation(world).subtract(direction.clone().multiply(0.2)));
                return false;
            }
            position.add(direction.clone().multiply(length));
            travelled += length;
            ItemDisplay item = display;
            if (item != null && item.isValid()) {
                Displays.moveTo(item, position);
                Displays.animate(item, flyPose((tick + 1) * 40f), 1);
            }
            if (tick % 2 == 0) {
                Vfx.dot(position, Particle.DUST, ASH_SMALL, false);
            }
            return true;
        }

        private void stick(LivingEntity enemy) {
            target = enemy;
            stuckAt = ctx().currentTick();
            Combat.hit(ctx(), owner, enemy, params().num("bowl_damage"));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                comeHome(position);
                return;
            }
            Location over = over(enemy);
            boolean hidden = isHidden(enemy);
            ItemDisplay item = display;
            if (item != null && item.isValid()) {
                // Hanging over the victim's head, the bowl turns to face every viewer.
                item.setBillboard(Display.Billboard.CENTER);
                Displays.moveTo(item, over);
                Displays.animate(item, hidden ? hiddenPose() : stuckPose(0), 3);
            }
            if (reserve(1)) {
                label = keep(Displays.text(ctx(), owner, labelSpot(enemy), hidden ? Text.mm("") : fillText(), 0.5f,
                        duration + 4));
                label.setTeleportDuration(1);
            }
            World world = over.getWorld();
            if (!hidden) {
                Vfx.dot(Fx.body(enemy), Particle.FLASH, PALETTE.core(), false);
                world.spawnParticle(Particle.DUST, over, Vfx.n(ctx(), 8), 0.25, 0.1, 0.25, 0, SICKLY);
                Vfx.ring(ctx(), over.clone().subtract(0, 0.25, 0), 0.45, 12, 0, Particle.DUST_COLOR_TRANSITION, HUNGER,
                        false);
                Fx.sound(over, Sound.BLOCK_BAMBOO_WOOD_HIT, 1.0f, 0.7f + Fx.jitter(0.08f));
                Fx.sound(over, Sound.ENTITY_ITEM_FRAME_ADD_ITEM, 0.9f, 0.6f + Fx.jitter(0.08f));
            }
            if (!hidden) {
                owner.sendActionBar(Text.mm("<" + HUNGER_TAG + ">동냥 그릇</" + HUNGER_TAG + "> <gray>"
                        + Fx.seconds(duration) + "초간</gray> <white><name></white><gray>의 회복을 담습니다</gray>",
                        Placeholder.component("name", enemy.name())));
            }
            if (enemy instanceof Player victim) {
                victim.sendActionBar(Text.mm("<" + HUNGER_TAG + ">머리 위에 아귀의 동냥 그릇이 붙었습니다!</" + HUNGER_TAG
                        + "> <gray>" + Fx.seconds(duration) + "초간 회복이 모두 그릇으로 갑니다</gray>"));
            }
        }

        private void miss(Location at) {
            done = true;
            World world = at.getWorld();
            world.spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 6), 0.15, 0.15, 0.15, 0, ASH);
            Fx.sound(at, Sound.BLOCK_BAMBOO_WOOD_FALL, 0.9f, 0.7f + Fx.jitter(0.1f));
            double refund = Math.clamp(params().num("bowl_refund") / 100.0, 0.0, 1.0) * params().cooldownSeconds();
            if (refund > 0) {
                ctx().reduceCooldown(owner, refund);
            }
            owner.sendActionBar(Text.mm("<gray>그릇이 빗나갔습니다</gray>" + (refund > 0 ? " <" + HUNGER_TAG + ">쿨타임 -"
                    + AbilityInfo.formatNumber(Math.round(refund * 10) / 10.0) + "초</" + HUNGER_TAG + ">" : "")));
            flyHome(at, 0, false);
        }

        // ---------------------------------------------------------------- hanging on

        private boolean hold(long tick) {
            LivingEntity enemy = target;
            if (enemy == null) {
                return false;
            }
            long held = ctx().currentTick() - stuckAt;
            if (!Combat.isTargetable(ctx(), owner, enemy) || !enemy.getWorld().equals(owner.getWorld())) {
                comeHome(display != null ? display.getLocation() : position);
                return false;
            }
            if (held >= duration) {
                finish(enemy);
                return false;
            }
            boolean hidden = isHidden(enemy);
            Location over = over(enemy);
            ItemDisplay item = display;
            if (item != null && item.isValid()) {
                Displays.moveTo(item, over.clone().add(0, Math.sin(tick * 0.3) * 0.05, 0));
                if (tick % 2 == 0) {
                    // Hidden from the ghost, the bowl hides too: it must not give away where its victim stands.
                    Displays.animate(item, hidden ? hiddenPose() : stuckPose(stored), 2);
                }
            }
            TextDisplay text = label;
            if (text != null && text.isValid()) {
                Displays.moveTo(text, labelSpot(enemy));
                if (tick % 5 == 0) {
                    text.text(hidden ? Text.mm("") : fillText());
                }
            }
            if (!hidden && tick % 6 == 0) {
                Vfx.ring(ctx(), over.clone().subtract(0, 0.22, 0), 0.38, 8, tick * 0.2, Particle.DUST, SICKLY, false);
            }
            return true;
        }

        /** A heal of the victim lands in the bowl. */
        void store(double amount) {
            if (done || target == null || !(amount > 0)) {
                return;
            }
            double before = stored;
            stored += amount;
            LivingEntity enemy = target;
            if (isHidden(enemy)) {
                return;
            }
            Location chest = enemy.getLocation().add(0, enemy.getHeight() * 0.6, 0);
            Location over = over(enemy);
            int motes = Vfx.n(ctx(), 5);
            for (int i = 0; i < motes; i++) {
                Vfx.trail(Vfx.scatter(chest, 0.25), over, i % 2 == 0 ? LIFE : LIFE_PALE, 6 + i, false);
            }
            Fx.sound(over, Sound.ITEM_BOTTLE_FILL, 0.8f, 0.8f + (float) Math.min(0.8, stored * 0.1));
            if (before <= 0 && enemy instanceof Player victim) {
                victim.sendActionBar(Text.mm("<" + HUNGER_TAG + ">회복이 그릇으로 빠져나갑니다!</" + HUNGER_TAG + ">"));
            }
        }

        private Component fillText() {
            double cap = Math.max(0, params().num("bowl_max"));
            long left = Math.max(0, duration - (ctx().currentTick() - stuckAt));
            return Text.mm("<" + HUNGER_TAG + ">" + AbilityInfo.formatNumber(round(Math.min(cap, stored))) + "</"
                    + HUNGER_TAG + "><dark_gray>/" + AbilityInfo.formatNumber(cap) + "</dark_gray> <gray>"
                    + Fx.seconds(left) + "s</gray>");
        }

        /** Time is up: a bowl with something in it flies home; an empty one clonks the victim. */
        private void finish(LivingEntity enemy) {
            if (stored > EPSILON) {
                comeHome(over(enemy));
                return;
            }
            done = true;
            Location head = enemy.getLocation().add(0, enemy.getHeight() + 0.05, 0);
            ItemDisplay item = display;
            if (item != null && item.isValid()) {
                item.setTeleportDuration((int) BONK_TICKS);
                Displays.moveTo(item, head);
            }
            removeLabel();
            ctx().runLater(owner, BONK_TICKS, () -> {
                if (Combat.isTargetable(ctx(), owner, enemy)) {
                    Combat.hit(ctx(), owner, enemy, params().num("bowl_bonk"));
                }
                Location at = enemy.getLocation().add(0, enemy.getHeight() + 0.1, 0);
                boolean hidden = isHidden(enemy);
                if (!hidden) {
                    World world = at.getWorld();
                    world.spawnParticle(Particle.CRIT, at, Vfx.n(ctx(), 10), 0.2, 0.1, 0.2, 0.2);
                    world.spawnParticle(Particle.DUST, at, Vfx.n(ctx(), 6), 0.25, 0.1, 0.25, 0, ASH);
                    Fx.sound(at, Sound.BLOCK_BELL_USE, 0.8f, 1.9f + Fx.jitter(0.05f));
                    Fx.sound(at, Sound.BLOCK_BAMBOO_WOOD_HIT, 1.0f, 0.5f + Fx.jitter(0.05f));
                }
                owner.sendActionBar(Text.mm("<" + ASH_TAG + "><b>땡그랑!</b></" + ASH_TAG + "> <gray>빈 그릇으로 머리를 "
                        + "때렸습니다</gray>"));
                flyHome(at, 0, hidden);
            });
        }

        /** The bowl flies back with what it caught and feeds the ghost on arrival. */
        private void comeHome(Location from) {
            done = true;
            removeLabel();
            double cap = Math.max(0, params().num("bowl_max"));
            double meal = Math.min(cap, stored) + Math.max(0, params().num("bowl_bonus"));
            LivingEntity victim = target;
            flyHome(from, meal, victim != null && isHidden(victim));
        }

        /**
         * The bowl flies back to the ghost's mouth and feeds it on arrival. From a hidden victim it comes back at once,
         * without a flight that would show where the victim stands.
         */
        private void flyHome(Location from, double meal, boolean fromHidden) {
            ItemDisplay item = display;
            display = null;
            if (fromHidden && item != null && item.isValid()) {
                item.remove();
            }
            if (item == null || !item.isValid()) {
                if (meal > 0) {
                    arrive(meal);
                }
                return;
            }
            item.setBillboard(Display.Billboard.FIXED);
            item.setTeleportDuration((int) BOWL_RETURN_TICKS);
            ctx().runLater(owner, 1, () -> {
                if (item.isValid()) {
                    Displays.moveTo(item, mouth(owner));
                    Displays.animate(item, flyPose(360), (int) BOWL_RETURN_TICKS);
                }
            });
            if (meal > 0 && from.getWorld().equals(owner.getWorld())) {
                int motes = Vfx.n(ctx(), 6);
                for (int i = 0; i < motes; i++) {
                    Vfx.trail(Vfx.scatter(from, 0.2), mouth(owner), i % 2 == 0 ? LIFE : LIFE_PALE,
                            (int) BOWL_RETURN_TICKS + i, false);
                }
            }
            ctx().runLater(owner, BOWL_RETURN_TICKS + 1, () -> {
                if (item.isValid()) {
                    Displays.shrinkAway(ctx(), owner, item, 3);
                }
                if (meal > 0) {
                    arrive(meal);
                }
            });
        }

        private void arrive(double meal) {
            ctx().heal(owner, meal);
            Location mouth = mouth(owner);
            World world = mouth.getWorld();
            world.spawnParticle(Particle.HAPPY_VILLAGER, mouth, Vfx.n(ctx(), 5), 0.2, 0.2, 0.2, 0);
            world.spawnParticle(Particle.DUST, mouth, Vfx.n(ctx(), 6), 0.15, 0.15, 0.15, 0, LIFE_DUST);
            Fx.sound(mouth, Sound.ENTITY_GENERIC_EAT, 1.0f, 0.6f + Fx.jitter(0.06f));
            Fx.sound(mouth, Sound.ENTITY_PLAYER_BURP, 0.6f, 0.7f + Fx.jitter(0.06f));
            owner.sendActionBar(Text.mm("<" + HUNGER_TAG + ">동냥 그릇</" + HUNGER_TAG + "> <gray>담아 온 기운으로</gray> "
                    + "<white>+" + AbilityInfo.formatNumber(round(meal)) + "</white> <gray>회복</gray>"));
        }

        private void removeLabel() {
            TextDisplay text = label;
            label = null;
            if (text != null && text.isValid()) {
                Displays.shrinkAway(ctx(), owner, text, 3);
            }
        }

        /** The loop ran out (it only does once the bowl is done, or its owner fell). */
        void release() {
            if (!done && target != null) {
                finish(target);
            }
            done = true;
        }
    }

    private static Location over(LivingEntity enemy) {
        return Displays.unrotated(enemy.getLocation().add(0, enemy.getHeight() + BOWL_LIFT, 0));
    }

    private static Location labelSpot(LivingEntity enemy) {
        return Displays.unrotated(enemy.getLocation().add(0, enemy.getHeight() + BOWL_LIFT + 0.5, 0));
    }

    /** The bowl in flight, tumbling end over end. */
    private static Transformation flyPose(float spinDeg) {
        return Displays.centered(0, 0, 0, BOWL_FLY_SIZE, BOWL_FLY_SIZE, BOWL_FLY_SIZE, spinDeg, spinDeg * 0.5f, 0);
    }

    /** The bowl over a head: it swells a little as it fills. */
    private Transformation stuckPose(double stored) {
        double cap = Math.max(1, params().num("bowl_max"));
        float size = BOWL_STUCK_SIZE * (1f + 0.3f * (float) Math.min(1.0, stored / cap));
        return Displays.centered(0, 0, 0, size, size, size, 0, 0, 0);
    }

    private static Transformation hiddenPose() {
        return Displays.centered(0, 0, 0, 0.001f, 0.001f, 0.001f, 0, 0, 0);
    }

    // ------------------------------------------------------------------ ultimate: the realm of hungry ghosts

    @Override
    public ActivationResult onUltimate() {
        Realm open = realm;
        if (open != null && !open.over) {
            return ActivationResult.fail("아귀도가 이미 열려 있습니다");
        }
        Player owner = player();
        double radius = Math.max(1, params().num("realm_radius"));
        Location center = owner.getLocation();
        Spots.snapToGround(owner.getWorld(), center, REALM_SNAP);
        // An enemy hidden from the ghost does not count: casting must not tell it that one is near.
        if (ctx().enemiesNear(owner, center, radius).stream().allMatch(this::isHidden)) {
            return ActivationResult.fail("아귀도 범위(" + AbilityInfo.formatNumber(radius) + "블록) 안에 적이 없습니다");
        }
        Realm fresh = new Realm(owner, Displays.unrotated(center), radius);
        realm = fresh;
        fresh.open();
        return ActivationResult.success();
    }

    /**
     * The realm: nobody inside heals, heals turn to fire in the mouth, the realm gnaws at its prisoners every second
     * and feeds the ghost. A granted ghost's hunger catches vanilla heals in the realm before they land; a borrowed
     * realm watches health rise like the ghost's watch does.
     */
    private final class Realm implements LongPredicate {

        private final Player owner;
        private final Location center;
        private final double radius;
        private final long duration;
        private final long interval;
        private final Map<UUID, Double> last = new HashMap<>();
        private final Map<UUID, Double> burned = new HashMap<>();
        private final Map<UUID, Double> pendingBurn = new HashMap<>();
        private final Map<UUID, NamespacedKey> slows = new HashMap<>();
        private final List<Display> parts = new ArrayList<>();
        private @Nullable ItemDisplay bigBowl;
        private boolean over;

        Realm(Player owner, Location center, double radius) {
            this.owner = owner;
            this.center = center;
            this.radius = radius;
            this.duration = Math.max(1, params().ticks("realm_duration"));
            this.interval = Math.max(1, params().ticks("realm_interval"));
        }

        /**
         * How far a flat crack can run out from the middle along {@code yawDeg} (at most {@code max}) while the ground
         * stays at the middle's height: a crack lying on the ground, never floating over a dip or sunk into a rise.
         */
        private double flatReach(float yawDeg, double max) {
            Vector out = Motion.horizontalFacing(new Location(center.getWorld(), 0, 0, 0, yawDeg, 0));
            World world = center.getWorld();
            double reach = 0;
            for (double d = CRACK_STEP; d <= max + 1.0E-6; d += CRACK_STEP) {
                Location probe = center.clone().add(out.clone().multiply(d)).add(0, CRACK_PROBE, 0);
                Spots.snapToGround(world, probe, CRACK_PROBE * 2);
                if (Math.abs(probe.getY() - center.getY()) > CRACK_STEP_UP) {
                    break;
                }
                reach = d;
            }
            return reach;
        }

        boolean contains(LivingEntity entity) {
            Location at = entity.getLocation();
            return at.getWorld().equals(center.getWorld())
                    && Math.hypot(at.getX() - center.getX(), at.getZ() - center.getZ()) <= radius
                    && Math.abs(at.getY() - center.getY()) <= radius;
        }

        void open() {
            long life = duration + 16;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double offset = random.nextDouble(360);
            for (int i = 0; i < CRACK_COUNT && reserve(2); i++) {
                float yaw = (float) (offset + i * 360.0 / CRACK_COUNT + random.nextDouble(-12, 12));
                float length = (float) flatReach(yaw, radius * (0.55 + random.nextDouble() * 0.35));
                if (length < CRACK_MIN) {
                    continue;
                }
                BlockDisplay crack = keep(Displays.block(ctx(), owner, center, CRACK.createBlockData(),
                        crackPose(yaw, length, 0.02f, 0.1f), life));
                BlockDisplay glow = keep(Displays.block(ctx(), owner, center, CRACK_GLOW.createBlockData(),
                        crackPose(yaw, length * 0.7f, 0.025f, 0.05f), life));
                parts.add(crack);
                parts.add(glow);
                ctx().runLater(owner, 1 + i, () -> {
                    Displays.animate(crack, crackPose(yaw, length, 1f, 0.1f), REALM_RISE_TICKS);
                    Displays.animate(glow, crackPose(yaw, length * 0.7f, 1f, 0.05f), REALM_RISE_TICKS);
                });
            }
            if (reserve(1)) {
                Location top = center.clone().add(0, BIG_BOWL_HEIGHT, 0);
                bigBowl = keep(Displays.item(ctx(), owner, top, BOWL, bigBowlPose(0, 0.05f), life));
                // Mouth down over the realm, always turned to the viewer.
                bigBowl.setBillboard(Display.Billboard.VERTICAL);
                bigBowl.setTeleportDuration(2);
                parts.add(bigBowl);
                ItemDisplay big = bigBowl;
                ctx().runLater(owner, 1, () -> Displays.animate(big, bigBowlPose(0, 1f), REALM_RISE_TICKS));
            }
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.1, 0), 0.5, radius, REALM_RISE_TICKS,
                    Particle.DUST_COLOR_TRANSITION, EMBER, true);
            World world = center.getWorld();
            world.spawnParticle(Particle.LARGE_SMOKE, center.clone().add(0, 0.5, 0), Vfx.n(ctx(), 20), 1.2, 0.3, 1.2,
                    0.02, null, true);
            world.spawnParticle(Particle.WHITE_ASH, center.clone().add(0, 1.5, 0), Vfx.n(ctx(), 40), radius * 0.5, 1,
                    radius * 0.5, 0, null, true);
            Fx.sound(center, Sound.ENTITY_GHAST_SCREAM, 0.7f, 0.4f);
            Fx.sound(center, Sound.BLOCK_FIRE_AMBIENT, 1.2f, 0.6f);
            Fx.sound(center, Sound.ENTITY_WITHER_AMBIENT, 0.6f, 0.5f);
            owner.sendActionBar(Text.mm("<" + EMBER_TAG + "><b>아귀도</b></" + EMBER_TAG + "> <gray>" + Fx.seconds(duration)
                    + "초간 이곳에서는 아무도 배부르지 못합니다</gray>"));
            Loop.repeat(ctx(), owner, 1, 1, duration, this, () -> close(true));
        }

        @Override
        public boolean test(long tick) {
            if (over) {
                return false;
            }
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius + WATCH_MARGIN)) {
                if (watchesHealth(enemy)) {
                    watch(enemy);
                }
            }
            for (Map.Entry<UUID, Double> burn : List.copyOf(pendingBurn.entrySet())) {
                pendingBurn.remove(burn.getKey());
                if (Bukkit.getEntity(burn.getKey()) instanceof LivingEntity enemy) {
                    flare(enemy, burn.getValue());
                }
            }
            if ((tick + 1) % interval == 0) {
                gnaw();
            }
            draw(tick);
            return true;
        }

        /** Health that rose since the last tick inside the realm is taken back and burns. */
        private void watch(LivingEntity enemy) {
            UUID id = enemy.getUniqueId();
            double health = enemy.getHealth();
            Double before = last.get(id);
            if (before != null && health > before + EPSILON && contains(enemy)) {
                double gain = health - before;
                enemy.setHealth(Math.max(0, health - gain));
                burn(enemy, gain);
            }
            last.put(id, enemy.getHealth());
        }

        /** A heal that turns to fire: it burns next tick (never inside a heal event), up to the cap per enemy. */
        void burn(LivingEntity enemy, double amount) {
            if (over || !(amount > 0)) {
                return;
            }
            pendingBurn.merge(enemy.getUniqueId(), amount, Double::sum);
        }

        private void flare(LivingEntity enemy, double amount) {
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                return;
            }
            double cap = Math.max(0, params().num("realm_burn_max"));
            double done = burned.getOrDefault(enemy.getUniqueId(), 0.0);
            double dealt = Math.min(amount, cap - done);
            if (dealt <= EPSILON) {
                return;
            }
            burned.put(enemy.getUniqueId(), done + dealt);
            Combat.hit(ctx(), owner, enemy, dealt);
            if (isHidden(enemy)) {
                return;
            }
            // Flames burst out of the mouth the heal was meant for.
            Location eye = enemy instanceof Player player ? player.getEyeLocation()
                    : enemy.getLocation().add(0, enemy.getHeight() * 0.8, 0);
            Vector look = eye.getDirection().setY(0);
            if (look.lengthSquared() < 1.0E-6) {
                look = new Vector(0, 0, 1);
            }
            look.normalize();
            World world = eye.getWorld();
            Location mouth = eye.clone().subtract(0, 0.2, 0).add(look.clone().multiply(0.3));
            for (int i = 0; i < Vfx.n(ctx(), 8); i++) {
                Vector spray = look.clone().add(new Vector(Fx.jitter(0.3f), Fx.jitter(0.2f) + 0.15, Fx.jitter(0.3f)));
                world.spawnParticle(Particle.FLAME, mouth.getX(), mouth.getY(), mouth.getZ(), 0, spray.getX(),
                        spray.getY(), spray.getZ(), 0.18);
            }
            Vfx.dot(mouth, Particle.FLASH, Color.fromRGB(0xFF8A4A), false);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, mouth, Vfx.n(ctx(), 5), 0.1, 0.1, 0.1, 0, EMBER);
            Fx.sound(mouth, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.8f + Fx.jitter(0.08f));
            Fx.sound(mouth, Sound.ENTITY_PLAYER_HURT_ON_FIRE, 0.7f, 0.9f + Fx.jitter(0.08f));
            if (enemy instanceof Player victim) {
                victim.sendActionBar(Text.mm("<" + EMBER_TAG + ">아귀도에서는 먹은 것이 불이 됩니다!</" + EMBER_TAG
                        + "> <gray>-" + AbilityInfo.formatNumber(round(dealt)) + "</gray>"));
            }
        }

        /** Every second: the realm gnaws at its prisoners and slows them, and feeds the ghost inside it. */
        private void gnaw() {
            double damage = Math.max(0, params().num("realm_damage"));
            double slow = Math.clamp(params().num("realm_slow") / 100.0, 0.0, 1.0);
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                if (!contains(enemy)) {
                    continue;
                }
                Combat.hit(ctx(), owner, enemy, damage);
                if (!Combat.isTargetable(ctx(), owner, enemy)) {
                    continue;
                }
                if (slow > 0) {
                    NamespacedKey old = slows.remove(enemy.getUniqueId());
                    if (old != null) {
                        ctx().removeModifier(enemy, old);
                    }
                    slows.put(enemy.getUniqueId(), ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow,
                            Operation.MULTIPLY_SCALAR_1, interval + 4));
                }
                if (!isHidden(enemy)) {
                    Location head = enemy.getLocation().add(0, enemy.getHeight() + 0.2, 0);
                    enemy.getWorld().spawnParticle(Particle.WHITE_ASH, head, Vfx.n(ctx(), 6), 0.3, 0.2, 0.3, 0);
                    enemy.getWorld().spawnParticle(Particle.DUST, Fx.body(enemy), Vfx.n(ctx(), 3), 0.25, 0.4, 0.25,
                            0, ASH);
                }
            }
            if (contains(owner)) {
                ctx().heal(owner, Math.max(0, params().num("realm_heal")));
                Location mouth = mouth(owner);
                mouth.getWorld().spawnParticle(Particle.DUST, mouth, Vfx.n(ctx(), 4), 0.15, 0.15, 0.15, 0, LIFE_DUST);
            }
            Vfx.shockwave(ctx(), owner, center.clone().add(0, 0.12, 0), 0.6, radius, 8, Particle.DUST, ASH_SMALL,
                    false);
            Fx.sound(center, Sound.ENTITY_GHAST_AMBIENT, 0.6f, 0.45f + Fx.jitter(0.05f));
        }

        private void draw(long tick) {
            World world = center.getWorld();
            if (tick % 3 == 0) {
                Location rim = center.clone().add(0, 0.12, 0);
                int points = (int) Math.max(18, Math.min(44, radius * 5));
                Vfx.ring(ctx(), rim, radius, points, tick * 0.02, Particle.DUST_COLOR_TRANSITION, EMBER, true);
                // Small flames licking along the rim.
                for (int i = 0; i < Vfx.n(ctx(), 4); i++) {
                    double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                    world.spawnParticle(Particle.SMALL_FLAME, center.getX() + Math.cos(angle) * radius,
                            center.getY() + 0.15, center.getZ() + Math.sin(angle) * radius, 1, 0, 0.02, 0, 0.01);
                }
            }
            if (tick % 2 == 0) {
                // Ash raining from the great bowl overhead.
                Location top = center.clone().add(0, BIG_BOWL_HEIGHT - 0.2, 0);
                world.spawnParticle(Particle.WHITE_ASH, top, Vfx.n(ctx(), 4), 0.8, 0.1, 0.8, 0);
                world.spawnParticle(Particle.ASH, Vfx.scatter(center, radius).add(0, 2.2, 0), Vfx.n(ctx(), 3), 0.6,
                        0.4, 0.6, 0);
            }
            ItemDisplay big = bigBowl;
            if (big != null && big.isValid() && tick % 4 == 0 && tick >= REALM_RISE_TICKS) {
                // The great bowl breathes, slowly rising and sinking.
                Displays.animate(big, bigBowlPose((float) Math.sin((tick + 4) * 0.12) * 0.25f, 1f), 4);
            }
            if (tick % 30 == 15) {
                Fx.sound(center, Sound.BLOCK_FIRE_AMBIENT, 0.8f, 0.5f + Fx.jitter(0.05f));
            }
        }

        void close(boolean naturally) {
            if (over) {
                return;
            }
            over = true;
            if (realm == this) {
                realm = null;
            }
            for (Map.Entry<UUID, NamespacedKey> slow : slows.entrySet()) {
                if (Bukkit.getEntity(slow.getKey()) instanceof LivingEntity enemy) {
                    ctx().removeModifier(enemy, slow.getValue());
                }
            }
            slows.clear();
            for (Display part : parts) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 8);
                }
            }
            if (naturally) {
                World world = center.getWorld();
                world.spawnParticle(Particle.LARGE_SMOKE, center.clone().add(0, 0.6, 0), Vfx.n(ctx(), 16), radius * 0.4,
                        0.3, radius * 0.4, 0.01, null, true);
                Fx.sound(center, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 0.6f);
                Fx.sound(center, Sound.ENTITY_GHAST_DEATH, 0.4f, 0.5f);
            }
        }
    }

    /** A dry crack running out from the middle along {@code yawDeg}, flat on the ground, grown to {@code grow}. */
    private static Transformation crackPose(float yawDeg, float length, float grow, float width) {
        float l = Math.max(0.01f, length * grow);
        return part(yawDeg, 0, 0.012f, l / 2, width, 0.02f, l);
    }

    /** A block display box of size (sx, sy, sz) centered on the local point (lx, ly, lz) of a frame turned by yaw. */
    private static Transformation part(float yaw, float lx, float ly, float lz, float sx, float sy, float sz) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
        Vector3f translation = rotation.transform(new Vector3f(lx - sx / 2, ly - sy / 2, lz - sz / 2));
        return new Transformation(translation, rotation, new Vector3f(sx, sy, sz), new Quaternionf());
    }

    /** The great bowl overhead, mouth down, {@code bob} blocks above its rest. */
    private static Transformation bigBowlPose(float bob, float grow) {
        float s = BIG_BOWL_SIZE * Math.max(0.01f, grow);
        return Displays.centered(0, bob, 0, s, s, s, 0, 0, 180);
    }

    // ------------------------------------------------------------------ helpers

    private static Location mouth(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.add(Motion.horizontalFacing(eye).multiply(0.3)).subtract(0, 0.2, 0);
    }

    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        return eye.add(eye.getDirection().multiply(0.6)).subtract(0, 0.3, 0);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** True for a player the ghost cannot see (stealth): no visual may give it away. */
    private boolean isHidden(LivingEntity entity) {
        return entity instanceof Player hidden && !player().canSee(hidden);
    }

    private <T extends Display> T keep(T display) {
        displays.add(display);
        return display;
    }

    private boolean reserve(int count) {
        displays.removeIf(display -> !display.isValid());
        return displays.size() + count <= MAX_DISPLAYS;
    }
}
