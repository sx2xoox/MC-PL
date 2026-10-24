package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.PotionContents;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import net.kyori.adventure.text.format.TextColor;
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
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 키르케 — the witch of Aeaea who turned Odysseus' men into swine with one cup of wine. Beasts and summons will not go
 * for their mistress (and her blows hit them twice as hard); a thrown cup of enchanted wine turns the first enemy it
 * splashes into a pig for a moment — it can still run, but neither strike nor cast — and her banquet turns everyone
 * who did not leave the table in time into a random farm animal.
 *
 * <p>Look (witch purple, herb green, wine red): a faint ring of herbs and violet motes at her feet; mobs that give up on
 * her bow with a heart. The cup is a spinning wine-red flask leaving drops of wine and purple sparks, bursting in a
 * splash of wine and purple-green smoke; the victim vanishes into a real pig that follows it, squealing when hit. A
 * missed cup leaves a round wine puddle. The banquet sets a long table with a white cloth, goblets, a cake and fruit,
 * while a violet-and-green ring of light closes around it; at the toast the ring flashes and the guests turn into pigs,
 * chickens, rabbits and sheep.
 */
public final class CirceAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("circe", "키르케", Material.CARROT_ON_A_STICK)
            .color(TextColor.color(0x6C3FA0))
            .difficulty(1)
            .passive("짐승의 여주인",
                    "{tame_radius}블록 안에 있는 적의 소환수와 짐승(늑대·골렘·언데드·좀비 같은 몹)은 스스로 나를 "
                            + "노리지 않고, 나를 쫓던 몹도 {tame_interval}초마다 나를 놓쳐 머뭇거립니다(주인이 다시 시키면 "
                            + "다시 덤빕니다). 몹에게 주는 내 근접 피해는 {beast_multiplier}배가 됩니다.")
            .active("돼지가 되어라",
                    "마법 포도주 잔을 포물선으로 던집니다(최대 {cup_range}블록). 처음 맞은 적은 {pig_duration}초간 "
                            + "돼지가 됩니다: 몸이 숨겨지고 돼지 모습으로 걷거나(이동 속도 -{pig_slow}%) 뛸 수는 있지만 "
                            + "공격하거나 능력을 쓸 수 없고, 피해를 받아도 풀리지 않습니다. 끝나면 연기와 함께 돌아오며 "
                            + "{unpig_damage} 피해를 입습니다. 빗나가면 떨어진 자리에 {puddle_duration}초간 반경 "
                            + "{puddle_radius}블록 포도주 웅덩이가 생겨 밟은 적의 이동 속도가 {puddle_slow}% 느려집니다.",
                    11)
            .ultimate("키르케의 연회",
                    "내 자리에 잔이 놓인 연회 식탁을 차립니다. {feast_warning}초 동안 반경 {feast_radius}블록의 빛의 "
                            + "고리가 경고하고(그사이 고리 밖으로 달아날 수 있음), 그때 고리 안에 있는 적은 모두 "
                            + "{feast_duration}초간 "
                            + "무작위 동물이 됩니다 — 돼지(이동 속도 -{feast_pig_slow}%), 닭(점프력 "
                            + "-{feast_chicken_jump}%, 천천히 떨어짐), 토끼(계속 깡충거리며 이동 속도 "
                            + "+{feast_rabbit_speed}%), 양(넉백 {feast_sheep_knockback}배). 동물은 공격하거나 능력을 쓸 "
                            + "수 없고, 그동안 나와 아군이 동물에게 주는 피해가 {feast_bonus}% 늘어납니다. 풀릴 때 "
                            + "{feast_release_damage} 피해를 입습니다.")
            .param("tame_radius", 8)
            .param("tame_interval", 1)
            .param("beast_multiplier", 2)
            .param("cup_range", 20)
            .param("pig_duration", 2)
            .param("pig_slow", 25)
            .param("unpig_damage", 2)
            .param("puddle_duration", 3)
            .param("puddle_radius", 2)
            .param("puddle_slow", 30)
            .param("feast_warning", 1.5)
            .param("feast_radius", 10)
            .param("feast_duration", 2.5)
            .param("feast_pig_slow", 30)
            .param("feast_chicken_jump", 40)
            .param("feast_rabbit_speed", 20)
            .param("feast_sheep_knockback", 2)
            .param("feast_bonus", 20)
            .param("feast_release_damage", 2)
            .build();

    private static final String PURPLE_TAG = "#9B6BD6";
    private static final String WINE_TAG = "#C2335A";

    // ------------------------------------------------------------------ look
    /** Pale violet, witch purple, wine red. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xE6D4FF, 0x6C3FA0, 0x8E1F3C);
    private static final Color PURPLE = Color.fromRGB(0x6C3FA0);
    private static final Color HERB = Color.fromRGB(0x5E9E4A);
    private static final Color WINE = Color.fromRGB(0x8E1F3C);
    private static final Color GOLD = Color.fromRGB(0xE8C66A);
    private static final Particle.DustOptions PURPLE_DUST = new Particle.DustOptions(PURPLE, 1.0f);
    private static final Particle.DustOptions HERB_DUST = new Particle.DustOptions(HERB, 0.8f);
    private static final Particle.DustOptions WINE_DUST = new Particle.DustOptions(WINE, 0.9f);
    private static final Particle.DustOptions SMALL_WINE = new Particle.DustOptions(WINE, 0.6f);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 0.7f);
    private static final Particle.DustTransition WITCH_GLOW = new Particle.DustTransition(PURPLE, HERB, 1.1f);

    /** Block types; block data is created on use (it needs a running server). */
    private static final Material TABLE_TOP = Material.DARK_OAK_PLANKS;
    private static final Material TABLE_LEG = Material.DARK_OAK_LOG;
    private static final Material CLOTH = Material.WHITE_WOOL;
    private static final Material RUNNER = Material.PURPLE_WOOL;
    private static final Material CAKE = Material.CAKE;
    private static final Material PUDDLE = Material.RED_STAINED_GLASS;

    private static final int MAX_DISPLAYS = 24;
    private static final double MAX_SPEED = 2.0;

    // passive
    private static final long AURA_PERIOD = 20;
    private static final long TAME_NOTICE = 60;

    // active: the cup
    private static final double CUP_SPEED = 1.15;
    private static final double CUP_LIFT = 0.18;
    private static final double CUP_GRAVITY = 0.05;
    private static final double CUP_DRAG = 0.99;
    private static final long CUP_MAX_TICKS = 50;
    private static final double CUP_HITBOX = 0.3;
    private static final float CUP_SIZE = 0.55f;
    private static final long PUDDLE_SLOW_TICKS = 10;
    private static final long PUDDLE_PERIOD = 4;
    private static final int PUDDLE_LAYERS = 3;
    private static final double PUDDLE_HEIGHT = 1.0;

    // morphs
    private static final EquipmentSlot[] GEAR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND};
    private static final long BUBBLE_GAP = 10;
    private static final double RABBIT_HOP = 0.8;
    private static final long RABBIT_PERIOD = 10;
    private static final double RABBIT_PUSH = 0.18;

    // ultimate: the banquet
    private static final long FEAST_LINGER = 30;
    private static final double FEAST_HEIGHT = 4;

    /** The animals of the spell, with what each body is like. */
    private enum Kind {
        PIG(Pig.class, "돼지", "꿀꿀!", Sound.ENTITY_PIG_AMBIENT, Sound.ENTITY_PIG_HURT),
        CHICKEN(Chicken.class, "닭", "꼬꼬댁!", Sound.ENTITY_CHICKEN_AMBIENT, Sound.ENTITY_CHICKEN_HURT),
        RABBIT(Rabbit.class, "토끼", "깡충!", Sound.ENTITY_RABBIT_AMBIENT, Sound.ENTITY_RABBIT_HURT),
        SHEEP(Sheep.class, "양", "매애~", Sound.ENTITY_SHEEP_AMBIENT, Sound.ENTITY_SHEEP_HURT);

        final Class<? extends Mob> type;
        final String label;
        final String cry;
        final Sound ambient;
        final Sound hurt;

        Kind(Class<? extends Mob> type, String label, String cry, Sound ambient, Sound hurt) {
            this.type = type;
            this.label = label;
            this.cry = cry;
            this.ambient = ambient;
            this.hurt = hurt;
        }
    }

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final Map<UUID, Morph> morphs = new HashMap<>();
    private final Map<UUID, Morph> byModel = new HashMap<>();
    private @Nullable MistressListener mistress;
    private @Nullable MorphListener morphListener;
    private long lastTameNotice = Long.MIN_VALUE / 2;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        MistressListener created = new MistressListener();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        mistress = created;
    }

    @Override
    public void onRevoke() {
        if (mistress != null) {
            HandlerList.unregisterAll(mistress);
            mistress = null;
        }
        for (Morph morph : new ArrayList<>(morphs.values())) {
            morph.end(false);
        }
        morphs.clear();
        byModel.clear();
        stopMorphListener();
        live.clear();
    }

    // ================================================================== passive: mistress of beasts

    /** Enemy beasts and summons near the witch will not take her as their target. */
    private final class MistressListener implements Listener {

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onTarget(EntityTargetEvent event) {
            Player owner = player();
            if (!owner.equals(event.getTarget()) || !(event.getEntity() instanceof Mob mob)
                    || !ctx().isAlive(owner) || !tamed(owner, mob)) {
                return;
            }
            event.setCancelled(true);
        }
    }

    /** An enemy mob within the radius: it will not go for its mistress. */
    private boolean tamed(Player owner, Mob mob) {
        double radius = Math.max(0, params().num("tame_radius"));
        return ctx().isEnemy(owner, mob) && mob.getWorld().equals(owner.getWorld())
                && mob.getLocation().distanceSquared(owner.getLocation()) <= radius * radius;
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        if (tick % Math.max(1, params().ticks("tame_interval")) == 0) {
            calmBeasts(owner);
        }
        if (tick % AURA_PERIOD == 0 && !owner.isInvisible() && !owner.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            Location feet = owner.getLocation().add(0, 0.08, 0);
            Vfx.ring(ctx(), feet, 0.55, 8, tick * 0.05, Particle.DUST, tick % 40 == 0 ? HERB_DUST : PURPLE_DUST,
                    false);
        }
    }

    /**
     * Mobs chasing the witch lose interest for a moment and bow to her with a heart. Only now and then: a summon its
     * master keeps sending at her hesitates, but still comes (the passive is a weak counter, not an immunity).
     */
    private void calmBeasts(Player owner) {
        double radius = Math.max(0, params().num("tame_radius"));
        if (radius <= 0) {
            return;
        }
        int calmed = 0;
        for (LivingEntity entity : ctx().enemiesNear(owner, owner.getLocation(), radius)) {
            if (entity instanceof Mob mob && owner.equals(mob.getTarget()) && tamed(owner, mob)) {
                mob.setTarget(null);
                calmed++;
                bow(mob);
            }
        }
        long now = ctx().currentTick();
        if (calmed > 0 && now - lastTameNotice >= TAME_NOTICE) {
            lastTameNotice = now;
            owner.sendActionBar(Text.mm("<" + PURPLE_TAG + "><b>짐승의 여주인</b></" + PURPLE_TAG + "> <gray>짐승들이 "
                    + "고개를 숙입니다</gray>"));
        }
    }

    /** A beast bows to its mistress: a heart over its head and a purr. */
    private void bow(Mob mob) {
        Location head = mob.getLocation().add(0, mob.getHeight() + 0.3, 0);
        Vfx.dot(head, Particle.HEART, null, false);
        Vfx.cloud(ctx(), head, Particle.DUST, 3, 0.2, 0, PURPLE_DUST, false);
        Fx.sound(head, Sound.ENTITY_CAT_PURR, 0.6f, 1.2f + Fx.jitter(0.1f));
    }

    /** Her own blows (not ability damage) hit mobs harder: only changes the damage. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || ctx().isAbilityDamage() || !player().equals(event.getDamager())
                || !(victim instanceof Mob)
                || event.getCause() != DamageCause.ENTITY_ATTACK && event.getCause() != DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        event.setDamage(event.getDamage() * Math.max(1, params().num("beast_multiplier")));
        Location body = Fx.body(victim);
        Vfx.cloud(ctx(), body, Particle.DUST, 4, 0.25, 0, PURPLE_DUST, false);
        Fx.sound(body, Sound.ENTITY_EVOKER_CAST_SPELL, 0.4f, 1.8f + Fx.jitter(0.1f));
    }

    // ================================================================== active: the cup of wine

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        new Cup(owner).launch();
        return ActivationResult.success();
    }

    /** A cup of enchanted wine for an item display: a potion bottle coloured like dark wine. */
    private static ItemStack wineCup() {
        ItemStack cup = ItemStack.of(Material.POTION);
        cup.setData(DataComponentTypes.POTION_CONTENTS, PotionContents.potionContents().customColor(WINE).build());
        return cup;
    }

    /**
     * The thrown cup: flies in an arc (simulated, no entity), splashes on the first visible enemy in its way, or
     * breaks on the ground (or a wall) into a puddle of wine.
     */
    private final class Cup {
        private final Player owner;
        private final World world;
        private Location at;
        private Vector velocity;
        private double travelled;
        private @Nullable ItemDisplay flask;

        Cup(Player owner) {
            this.owner = owner;
            Location eye = owner.getEyeLocation();
            this.world = eye.getWorld();
            Vector direction = eye.getDirection();
            this.at = eye.clone().add(direction.clone().multiply(0.5)).subtract(0, 0.15, 0);
            this.velocity = direction.multiply(CUP_SPEED).add(new Vector(0, CUP_LIFT, 0));
        }

        void launch() {
            Fx.sound(at, Sound.ENTITY_WITCH_THROW, 1.0f, 1.0f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.ENTITY_SPLASH_POTION_THROW, 0.8f, 0.9f + Fx.jitter(0.1f));
            Fx.sound(at, Sound.ENTITY_WITCH_AMBIENT, 0.4f, 1.4f + Fx.jitter(0.1f));
            Vfx.cloud(ctx(), at, Particle.WITCH, 6, 0.2, 0.02, null, false);
            if (reserve(1)) {
                flask = keep(Displays.item(ctx(), owner, Displays.unrotated(at), wineCup(),
                        Displays.centered(0, 0, 0, CUP_SIZE, CUP_SIZE, CUP_SIZE, 0, 0, 0), CUP_MAX_TICKS + 2));
                flask.setTeleportDuration(1);
            }
            Loop.repeat(ctx(), owner, 1, 1, CUP_MAX_TICKS, this::fly, this::fizzle);
        }

        private boolean fly(long run) {
            double range = Math.max(1, params().num("cup_range"));
            double speed = velocity.length();
            if (!(speed > 1.0E-6)) {
                return false;
            }
            Vector direction = velocity.clone().multiply(1 / speed);
            LivingEntity hit = firstEnemy(direction, speed);
            RayTraceResult wall = world.rayTraceBlocks(at, direction, speed, FluidCollisionMode.NEVER, true);
            double wallDistance = wall == null ? Double.MAX_VALUE : wall.getHitPosition().distance(at.toVector());
            if (hit != null && hitDistance(hit, direction, speed) <= wallDistance) {
                splash(hit);
                return false;
            }
            if (wall != null) {
                Location spot = wall.getHitPosition().toLocation(world);
                BlockFace face = wall.getHitBlockFace();
                if (face != null && face != BlockFace.UP) {
                    spot.add(face.getDirection().multiply(0.3));
                }
                shatter(spot);
                return false;
            }
            Location next = at.clone().add(velocity);
            travelled += Math.hypot(velocity.getX(), velocity.getZ());
            at = next;
            velocity.multiply(CUP_DRAG).subtract(new Vector(0, CUP_GRAVITY, 0));
            if (flask != null) {
                Displays.moveTo(flask, at);
                Displays.animate(flask, Displays.centered(0, 0, 0, CUP_SIZE, CUP_SIZE, CUP_SIZE, run * 40f, 0,
                        run * 25f), 1);
            }
            Vfx.dot(at, Particle.DUST, run % 2 == 0 ? WINE_DUST : PURPLE_DUST, false);
            if (run % 3 == 0) {
                Vfx.dot(at, Particle.FALLING_DRIPSTONE_LAVA, null, false);
                Vfx.dot(at, Particle.WITCH, null, false);
            }
            if (travelled >= range || !ctx().isInsideArena(at)) {
                // Out of reach: it drops where it is.
                Location ground = at.clone();
                Spots.snapToGround(world, ground, 6);
                shatter(ground);
                return false;
            }
            return true;
        }

        /** The nearest enemy the owner can see whose (slightly enlarged) box this step passes through. */
        private @Nullable LivingEntity firstEnemy(Vector direction, double speed) {
            LivingEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            Location middle = at.clone().add(direction.clone().multiply(speed / 2));
            for (LivingEntity enemy : ctx().enemiesNear(owner, middle, speed / 2 + 1.2)) {
                if (!seen(owner, enemy)) {
                    continue;
                }
                double distance = hitDistance(enemy, direction, speed);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = enemy;
                }
            }
            return best;
        }

        private double hitDistance(LivingEntity enemy, Vector direction, double speed) {
            BoundingBox box = enemy.getBoundingBox().expand(CUP_HITBOX);
            if (box.contains(at.toVector())) {
                return 0;
            }
            RayTraceResult hit = box.rayTrace(at.toVector(), direction, speed);
            return hit == null ? Double.MAX_VALUE : hit.getHitPosition().distance(at.toVector());
        }

        /** The wine splashes over the enemy: it turns into a pig. */
        private void splash(LivingEntity enemy) {
            removeFlask();
            Location body = Fx.body(enemy);
            Fx.sound(body, Sound.ENTITY_SPLASH_POTION_BREAK, 1.0f, 0.9f + Fx.jitter(0.1f));
            Fx.sound(body, Sound.BLOCK_GLASS_BREAK, 0.6f, 1.4f + Fx.jitter(0.1f));
            Vfx.burst(ctx(), body, PALETTE, 0.7, Particle.WITCH, false);
            Vfx.cloud(ctx(), body, Particle.DUST, 10, 0.4, 0, WINE_DUST, false);
            morph(owner, enemy, Kind.PIG, params().ticks("pig_duration"), params().num("unpig_damage"), false);
        }

        /** No one was hit: the cup breaks and the wine spreads into a puddle on the ground below. */
        private void shatter(Location spot) {
            removeFlask();
            Location ground = spot.clone().add(0, 0.6, 0);
            Spots.snapToGround(world, ground, 8);
            Fx.sound(ground, Sound.ENTITY_SPLASH_POTION_BREAK, 1.0f, 1.0f + Fx.jitter(0.1f));
            Fx.sound(ground, Sound.ENTITY_GENERIC_SPLASH, 0.6f, 1.3f + Fx.jitter(0.1f));
            Vfx.cloud(ctx(), ground.clone().add(0, 0.3, 0), Particle.DUST, 12, 0.5, 0, WINE_DUST, false);
            Vfx.cloud(ctx(), ground.clone().add(0, 0.3, 0), Particle.WITCH, 6, 0.4, 0.02, null, false);
            new Puddle(owner, Displays.unrotated(ground)).spill();
        }

        /** The loop ran out (should not happen before the range check): the cup drops. */
        private void fizzle() {
            if (flask != null && flask.isValid()) {
                Location ground = at.clone();
                Spots.snapToGround(world, ground, 6);
                shatter(ground);
            }
        }

        private void removeFlask() {
            ItemDisplay shown = flask;
            flask = null;
            if (shown != null && shown.isValid()) {
                shown.remove();
            }
        }
    }

    /** A round puddle of wine: enemies wading through it are slowed. */
    private final class Puddle {
        private final Player owner;
        private final Location center;
        private final double radius = Math.max(0.5, params().num("puddle_radius"));
        private final long ticks = Math.max(1, params().ticks("puddle_duration"));
        private final Map<UUID, Long> slowedUntil = new HashMap<>();
        private final List<BlockDisplay> layers = new ArrayList<>();

        Puddle(Player owner, Location center) {
            this.owner = owner;
            this.center = center;
        }

        void spill() {
            if (reserve(PUDDLE_LAYERS)) {
                // Three thin squares turned against each other make a roundish puddle.
                float size = (float) (radius * 2 * 0.86);
                for (int i = 0; i < PUDDLE_LAYERS; i++) {
                    BlockDisplay layer = keep(Displays.block(ctx(), owner, center, PUDDLE.createBlockData(),
                            Displays.transform(0, 0.02f + i * 0.002f, 0, 0.1f, 0.01f, 0.1f, i * 30f, 0, 0), ticks + 10));
                    Displays.animateNextTick(ctx(), owner, layer, Displays.transform(0, 0.02f + i * 0.002f, 0, size,
                            0.01f, size, i * 30f, 0, 0), 5);
                    layers.add(layer);
                }
            }
            Loop.repeat(ctx(), owner, 0, PUDDLE_PERIOD, ticks / PUDDLE_PERIOD + 1, this::soak, this::dry);
        }

        private boolean soak(long run) {
            long now = ctx().currentTick();
            double slow = Math.clamp(params().num("puddle_slow") / 100.0, 0.0, 1.0);
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius + 1)) {
                Location feet = enemy.getLocation();
                double dx = feet.getX() - center.getX();
                double dz = feet.getZ() - center.getZ();
                if (dx * dx + dz * dz > radius * radius || Math.abs(feet.getY() - center.getY()) > PUDDLE_HEIGHT) {
                    continue;
                }
                Long until = slowedUntil.get(enemy.getUniqueId());
                if (until == null || until <= now + 1) {
                    ctx().addModifier(owner, enemy, Attribute.MOVEMENT_SPEED, -slow, Operation.MULTIPLY_SCALAR_1,
                            PUDDLE_SLOW_TICKS);
                    slowedUntil.put(enemy.getUniqueId(), now + PUDDLE_SLOW_TICKS);
                    if (seen(owner, enemy)) {
                        Vfx.cloud(ctx(), feet.clone().add(0, 0.1, 0), Particle.DUST, 3, 0.2, 0, SMALL_WINE, false);
                        Fx.sound(feet, Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.4f, 1.3f + Fx.jitter(0.1f));
                    }
                }
            }
            // The wine glints and bubbles.
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < Vfx.n(ctx(), 2); i++) {
                Location spot = Vfx.scatter(center, radius * 0.9).add(0, 0.05, 0);
                Vfx.dot(spot, Particle.DUST, random.nextBoolean() ? WINE_DUST : PURPLE_DUST, false);
            }
            if (run % 3 == 0) {
                Vfx.dot(Vfx.scatter(center, radius * 0.7).add(0, 0.1, 0), Particle.WITCH, null, false);
            }
            return true;
        }

        private void dry() {
            for (BlockDisplay layer : layers) {
                if (layer.isValid()) {
                    Displays.shrinkAway(ctx(), owner, layer, 8);
                }
            }
        }
    }

    // ================================================================== morphs

    /**
     * One enemy turned into an animal: its body turns invisible (its armour and held items too, with fake empty
     * equipment for every viewer), a real (but motionless and silent) animal follows it and takes the blows meant for
     * it, it cannot strike or shoot (and is silenced, so it cannot cast), and its body gets the animal's quirks.
     *
     * <p>The body is not hidden with {@code hidePlayer}: everyone can still see (so aim at and target) the victim, as
     * stealth checks treat a hidden player as out of sight, and the witch's side must be able to hit the animals.
     */
    private final class Morph {
        final Player owner;
        final LivingEntity victim;
        final Kind kind;
        final boolean feast;
        final double releaseDamage;
        final long until;
        final List<NamespacedKey> keys = new ArrayList<>();
        /** Whether the spell made the body invisible (it was not already). */
        boolean madeInvisible;
        @Nullable Mob model;
        /** Stands in for a missing animal: its removal with the witch's ability ends the spell. */
        @Nullable TextDisplay anchor;
        long lastBubble = Long.MIN_VALUE / 2;
        boolean ended;

        Morph(Player owner, LivingEntity victim, Kind kind, boolean feast, double releaseDamage, long until) {
            this.owner = owner;
            this.victim = victim;
            this.kind = kind;
            this.feast = feast;
            this.releaseDamage = releaseDamage;
            this.until = until;
        }

        /** The body vanishes into its animal: invisible, with its gear hidden from everyone. */
        void conceal(long life) {
            if (!victim.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                victim.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) life + 5, 0, false,
                        false, true));
                madeInvisible = true;
            }
            hideGear();
        }

        /** Every viewer sees the body's armour and held items as empty (resent every tick: the server resends gear). */
        void hideGear() {
            if (!victim.isValid()) {
                return;
            }
            Map<EquipmentSlot, ItemStack> empty = new EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot slot : GEAR) {
                empty.put(slot, ItemStack.empty());
            }
            for (Player viewer : victim.getWorld().getPlayers()) {
                if (!viewer.equals(victim)) {
                    viewer.sendEquipmentChange(victim, empty);
                }
            }
        }

        void showAgain() {
            if (madeInvisible) {
                madeInvisible = false;
                PotionEffect invisible = victim.getPotionEffect(PotionEffectType.INVISIBILITY);
                if (invisible != null && !invisible.hasParticles()) {
                    victim.removePotionEffect(PotionEffectType.INVISIBILITY);
                }
            }
            EntityEquipment worn = victim.getEquipment();
            if (worn == null || !victim.isValid()) {
                return;
            }
            Map<EquipmentSlot, ItemStack> real = new EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot slot : GEAR) {
                real.put(slot, worn.getItem(slot));
            }
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(victim)) {
                    viewer.sendEquipmentChange(victim, real);
                }
            }
        }

        /** The animal follows the victim's body, turned the way it looks. */
        void follow() {
            Mob shown = model;
            if (shown == null || !shown.isValid()) {
                return;
            }
            Location at = victim.getLocation();
            if (!shown.getWorld().equals(at.getWorld())) {
                return;
            }
            shown.teleport(at);
            shown.setRotation(at.getYaw(), Math.clamp(at.getPitch(), -40f, 40f));
        }

        /** A little speech bubble over the animal ("꿀꿀!"), at most every half second. */
        void cry(boolean hurt) {
            long now = ctx().currentTick();
            if (now - lastBubble < BUBBLE_GAP) {
                return;
            }
            lastBubble = now;
            Location head = victim.getLocation().add(0, 1.3, 0);
            Fx.sound(head, hurt ? kind.hurt : kind.ambient, 0.9f, 1.0f + Fx.jitter(0.15f));
            if (ctx().isAlive(owner) && reserve(1)) {
                TextDisplay bubble = keep(Displays.text(ctx(), owner, Displays.unrotated(head), Text.mm("<white>"
                        + kind.cry + "</white>"), 0.9f, 16));
                bubble.setTeleportDuration(10);
                ctx().runLater(owner, 1, () -> Displays.moveTo(bubble, bubble.getLocation().add(0, 0.5, 0)));
            }
        }

        /**
         * The spell wears off (or is broken off): the victim shows again in a puff of smoke, the animal goes, the quirks
         * end, and if the spell ran its course the victim takes the release damage.
         */
        void end(boolean ranOut) {
            if (ended) {
                return;
            }
            ended = true;
            morphs.remove(victim.getUniqueId(), this);
            Mob shown = model;
            model = null;
            if (shown != null) {
                byModel.remove(shown.getUniqueId());
                if (shown.isValid()) {
                    shown.remove();
                }
            }
            TextDisplay standIn = anchor;
            anchor = null;
            if (standIn != null) {
                byModel.remove(standIn.getUniqueId());
                if (standIn.isValid()) {
                    standIn.remove();
                }
            }
            showAgain();
            for (NamespacedKey key : keys) {
                ctx().removeModifier(victim, key);
            }
            keys.clear();
            if (kind == Kind.CHICKEN) {
                PotionEffect fall = victim.getPotionEffect(PotionEffectType.SLOW_FALLING);
                if (fall != null && fall.getDuration() <= until - ctx().currentTick() + 5) {
                    victim.removePotionEffect(PotionEffectType.SLOW_FALLING);
                }
            }
            if (victim.isValid() && !victim.isDead()) {
                Location body = Fx.body(victim);
                Vfx.cloud(ctx(), body, Particle.POOF, 8, 0.3, 0.02, null, false);
                Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 8, 0.4, 0, WITCH_GLOW, false);
                Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8f, 1.3f + Fx.jitter(0.1f));
                if (ranOut && ctx().isAlive(owner)) {
                    Combat.hit(ctx(), owner, victim, releaseDamage);
                }
            }
            if (morphs.isEmpty()) {
                stopMorphListener();
            }
        }
    }

    /**
     * Turns {@code victim} into a {@code kind} for {@code ticks}. A victim already under the spell is turned into the
     * new animal (the newest spell decides how long it lasts).
     */
    private void morph(Player owner, LivingEntity victim, Kind kind, long ticks, double releaseDamage,
                       boolean feast) {
        if (!Combat.isTargetable(ctx(), owner, victim)) {
            return;
        }
        Morph old = morphs.get(victim.getUniqueId());
        if (old != null) {
            old.end(false);
        }
        long life = Math.max(1, ticks);
        startMorphListener();
        Morph morph = new Morph(owner, victim, kind, feast, releaseDamage, ctx().currentTick() + life);
        morphs.put(victim.getUniqueId(), morph);
        Location at = victim.getLocation();
        try {
            Mob model = at.getWorld().spawn(at, kind.type, spawned -> {
                spawned.setPersistent(false);
                spawned.setAI(false);
                spawned.setSilent(true);
                spawned.setCollidable(false);
                if (spawned instanceof Rabbit rabbit) {
                    rabbit.setRabbitType(Rabbit.Type.BROWN);
                }
            });
            if (model.isValid()) {
                ctx().track(owner, model);
                morph.model = model;
                byModel.put(model.getUniqueId(), morph);
            }
        } catch (RuntimeException e) {
            ctx().plugin().getLogger().warning("Could not spawn the " + kind.label + " of the circe: " + e);
        }
        if (morph.model == null) {
            TextDisplay standIn = Displays.text(ctx(), owner, Displays.unrotated(at), Text.mm(""), 0.01f, life + 5);
            standIn.setViewRange(0f);
            morph.anchor = standIn;
            byModel.put(standIn.getUniqueId(), morph);
        }
        morph.conceal(life);
        applyQuirks(owner, morph, life);
        if (victim instanceof Player target) {
            ctx().silence(target, life);
            target.sendActionBar(Text.mm("<" + WINE_TAG + "><b>" + kind.cry + "</b></" + WINE_TAG + "> <gray>"
                    + kind.label + "(이)가 되었습니다 · 공격도 능력도 쓸 수 없습니다 (" + Fx.seconds(life)
                    + "초)</gray>"));
        }
        Location body = Fx.body(victim);
        Vfx.cloud(ctx(), body, Particle.POOF, 10, 0.35, 0.03, null, false);
        Vfx.cloud(ctx(), body, Particle.DUST_COLOR_TRANSITION, 10, 0.4, 0, WITCH_GLOW, false);
        Vfx.cloud(ctx(), body, Particle.ENTITY_EFFECT, 6, 0.3, 0, PURPLE, false);
        Fx.sound(body, Sound.ENTITY_EVOKER_PREPARE_WOLOLO, 0.5f, 1.6f + Fx.jitter(0.1f));
        Fx.sound(body, kind.ambient, 1.0f, 1.0f + Fx.jitter(0.1f));
        Loop.repeat(ctx(), owner, 1, 1, life, run -> tickMorph(morph, run), () -> morph.end(true));
    }

    /** The animal's quirks: slower pig, short-jumping slow-falling chicken, fast rabbit, sheep (knockback). */
    private void applyQuirks(Player owner, Morph morph, long life) {
        LivingEntity victim = morph.victim;
        switch (morph.kind) {
            case PIG -> {
                double slow = Math.clamp((morph.feast ? params().num("feast_pig_slow") : params().num("pig_slow"))
                        / 100.0, 0.0, 1.0);
                morph.keys.add(ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, -slow,
                        Operation.MULTIPLY_SCALAR_1, life));
            }
            case CHICKEN -> {
                double jump = Math.clamp(params().num("feast_chicken_jump") / 100.0, 0.0, 1.0);
                morph.keys.add(ctx().addModifier(owner, victim, Attribute.JUMP_STRENGTH, -jump,
                        Operation.MULTIPLY_SCALAR_1, life));
                victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, (int) life, 0, false, false,
                        true));
            }
            case RABBIT -> {
                double fast = Math.max(0, params().num("feast_rabbit_speed")) / 100.0;
                morph.keys.add(ctx().addModifier(owner, victim, Attribute.MOVEMENT_SPEED, fast,
                        Operation.MULTIPLY_SCALAR_1, life));
            }
            case SHEEP -> {
                // Its knockback is doubled by the morph listener.
            }
        }
    }

    private boolean tickMorph(Morph morph, long run) {
        if (morph.ended) {
            return false;
        }
        LivingEntity victim = morph.victim;
        if (!victim.isValid() || victim.isDead() || victim instanceof Player target && !ctx().isAlive(target)) {
            morph.end(false);
            return false;
        }
        morph.follow();
        morph.hideGear();
        if (morph.kind == Kind.RABBIT && run % RABBIT_PERIOD == 0 && Motion.isGrounded(victim)) {
            // It cannot help hopping.
            Vector hop = Motion.horizontalFacing(victim.getLocation()).multiply(RABBIT_PUSH);
            hop.setY(Motion.launchSpeed(RABBIT_HOP));
            victim.setVelocity(Motion.clamp(hop, MAX_SPEED));
        }
        if (run % 6 == 0) {
            Location head = victim.getLocation().add(0, 1.0, 0);
            Vfx.dot(head.add(Fx.jitter(0.3f), Fx.jitter(0.2f), Fx.jitter(0.3f)), Particle.DUST,
                    run % 12 == 0 ? PURPLE_DUST : HERB_DUST, false);
        }
        if (run == 8) {
            morph.cry(false);
        }
        return true;
    }

    private void startMorphListener() {
        if (morphListener == null) {
            MorphListener created = new MorphListener();
            Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
            morphListener = created;
        }
    }

    private void stopMorphListener() {
        if (morphListener != null) {
            HandlerList.unregisterAll(morphListener);
            morphListener = null;
        }
    }

    private @Nullable Morph morphOfAttacker(Entity damager) {
        Morph morph = morphs.get(damager.getUniqueId());
        if (morph != null) {
            return morph;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return morphs.get(shooter.getUniqueId());
        }
        return null;
    }

    /**
     * The spell's rules while anyone is under it: blows on the animal land on the victim, the victim's own blows and
     * shots do nothing, a sheep flies further, the feast's guests take more from the witch's side, and the spell ends
     * cleanly when its animal is removed with the witch (death, quitting, the match ending).
     */
    private final class MorphListener implements Listener {

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onHit(EntityDamageEvent event) {
            Morph onModel = byModel.get(event.getEntity().getUniqueId());
            if (onModel != null) {
                // The animal is only a shape: what hits it hits the victim (with the same weapon and attacker).
                event.setCancelled(true);
                if (event instanceof EntityDamageByEntityEvent byEntity && !onModel.ended
                        && !onModel.victim.equals(byEntity.getDamager()) && onModel.victim.isValid()
                        && !onModel.victim.isDead()) {
                    onModel.victim.damage(event.getDamage(), event.getDamageSource());
                }
                return;
            }
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                Morph attacker = morphOfAttacker(byEntity.getDamager());
                if (attacker != null && !attacker.ended && byEntity.getCause() != DamageCause.MAGIC
                        && !attacker.victim.equals(event.getEntity())) {
                    // An animal cannot fight.
                    event.setCancelled(true);
                    attacker.cry(false);
                }
            }
        }

        /** The feast's guests take more from the witch and her allies. */
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onFeastHit(EntityDamageByEntityEvent event) {
            Morph morph = morphs.get(event.getEntity().getUniqueId());
            if (morph == null || morph.ended || !morph.feast) {
                return;
            }
            Player attacker = ctx().resolveAttacker(event.getDamager());
            if (attacker == null || !attacker.equals(morph.owner) && !ctx().isAlly(morph.owner, attacker)) {
                return;
            }
            double bonus = Math.max(0, params().num("feast_bonus")) / 100.0;
            event.setDamage(event.getDamage() * (1 + bonus));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onHurt(EntityDamageEvent event) {
            Morph morph = morphs.get(event.getEntity().getUniqueId());
            if (morph != null && !morph.ended && event.getFinalDamage() > 0) {
                morph.cry(true);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onKnockback(EntityKnockbackEvent event) {
            Morph morph = morphs.get(event.getEntity().getUniqueId());
            if (morph == null || morph.ended || morph.kind != Kind.SHEEP) {
                return;
            }
            double extra = Math.max(1, params().num("feast_sheep_knockback"));
            Vector knock = event.getKnockback();
            event.setKnockback(new Vector(knock.getX() * extra, knock.getY(), knock.getZ() * extra));
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onShoot(ProjectileLaunchEvent event) {
            if (event.getEntity().getShooter() instanceof Entity shooter) {
                Morph morph = morphs.get(shooter.getUniqueId());
                if (morph != null && !morph.ended) {
                    event.setCancelled(true);
                }
            }
        }

        /** The animal went with the witch's ability: the spell is broken at once (no release damage). */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onRemove(EntityRemoveEvent event) {
            Morph morph = byModel.get(event.getEntity().getUniqueId());
            if (morph != null && !morph.ended) {
                morph.end(false);
            }
        }
    }

    // ================================================================== ultimate: the banquet

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        Location ground = owner.getLocation();
        Spots.snapToGround(ground.getWorld(), ground, 3);
        new Feast(owner, Displays.unrotated(ground)).set();
        return ActivationResult.success();
    }

    /**
     * The banquet: a table pops up at the witch's feet while a ring of light closes around it for the warning time; at
     * the toast every enemy still inside (and seen by the witch) turns into a random animal.
     */
    private final class Feast {
        private final Player owner;
        private final Location center;
        private final float yaw;
        private final double radius = Math.max(1, params().num("feast_radius"));
        private final long warning = Math.max(1, params().ticks("feast_warning"));
        private final long duration = Math.max(1, params().ticks("feast_duration"));
        private final List<Display> table = new ArrayList<>();

        Feast(Player owner, Location center) {
            this.owner = owner;
            this.center = center;
            this.yaw = owner.getLocation().getYaw();
        }

        void set() {
            Fx.sound(center, Sound.BLOCK_BELL_USE, 1.2f, 1.3f);
            Fx.sound(center, Sound.ENTITY_WITCH_CELEBRATE, 1.0f, 1.0f);
            Fx.sound(center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 0.8f);
            owner.sendActionBar(Text.mm("<" + PURPLE_TAG + "><b>키르케의 연회</b></" + PURPLE_TAG + "> <gray>반경 "
                    + AbilityInfo.formatNumber(radius) + "블록 — 건배하면 모두 짐승이 됩니다</gray>"));
            buildTable();
            Loop.repeat(ctx(), owner, 0, 1, warning, this::warn, this::toast);
        }

        /** The table: a dark wooden top on two legs under a white cloth and a purple runner, with the feast on it. */
        private void buildTable() {
            if (!reserve(10)) {
                return;
            }
            long life = warning + duration + FEAST_LINGER;
            Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-yaw + 90));
            addPart(TABLE_LEG, new Vector3f(-0.85f, 0.38f, 0), new Vector3f(0.18f, 0.76f, 0.18f), turn, life);
            addPart(TABLE_LEG, new Vector3f(0.85f, 0.38f, 0), new Vector3f(0.18f, 0.76f, 0.18f), turn, life);
            addPart(TABLE_TOP, new Vector3f(0, 0.8f, 0), new Vector3f(2.3f, 0.1f, 1.0f), turn, life);
            addPart(CLOTH, new Vector3f(0, 0.86f, 0), new Vector3f(2.45f, 0.03f, 1.15f), turn, life);
            addPart(RUNNER, new Vector3f(0, 0.875f, 0), new Vector3f(2.46f, 0.03f, 0.35f), turn, life);
            addPart(CAKE, new Vector3f(0, 1.05f, 0), new Vector3f(0.42f, 0.3f, 0.42f), turn, life);
            float[][] goblets = {{-0.7f, 0.2f}, {0.65f, -0.22f}, {-0.3f, -0.3f}};
            for (float[] goblet : goblets) {
                Vector3f spot = turn.transform(new Vector3f(goblet[0], 1.08f, goblet[1]));
                ItemDisplay cup = keep(Displays.item(ctx(), owner, center, wineCup(), Displays.centered(spot.x,
                        spot.y, spot.z, 0.01f, 0.01f, 0.01f, 0, 0, 0), life));
                Displays.animateNextTick(ctx(), owner, cup, Displays.centered(spot.x, spot.y, spot.z, 0.42f, 0.42f,
                        0.42f, yaw, 0, 0), 6);
                table.add(cup);
            }
            Vector3f fruit = turn.transform(new Vector3f(0.4f, 1.0f, 0.25f));
            ItemDisplay apple = keep(Displays.item(ctx(), owner, center, ItemStack.of(Material.GOLDEN_APPLE),
                    Displays.centered(fruit.x, fruit.y, fruit.z, 0.01f, 0.01f, 0.01f, 0, 0, 0), life));
            Displays.animateNextTick(ctx(), owner, apple, Displays.centered(fruit.x, fruit.y, fruit.z, 0.35f, 0.35f,
                    0.35f, yaw, -90, 0), 6);
            table.add(apple);
            for (Display part : table) {
                Displays.outline(part, PURPLE);
            }
        }

        private void addPart(Material type, Vector3f center3, Vector3f size, Quaternionf turn, long life) {
            Vector3f middle = turn.transform(new Vector3f(center3));
            BlockDisplay part = keep(Displays.block(ctx(), owner, center, type.createBlockData(),
                    box(new Vector3f(middle.x, 0.01f, middle.z), new Vector3f(size).mul(0.05f), turn), life));
            Displays.animateNextTick(ctx(), owner, part, box(middle, size, turn), 6);
            table.add(part);
        }

        /** The warning ring of light closes in on the table, a bell counting down the toast. */
        private boolean warn(long run) {
            double progress = (double) run / Math.max(1, warning);
            Location ring = center.clone().add(0, 0.15, 0);
            if (run % 2 == 0) {
                Vfx.ring(ctx(), ring, radius, (int) Math.round(radius * 5), run * 0.04, Particle.DUST,
                        run % 4 == 0 ? PURPLE_DUST : HERB_DUST, true);
                Vfx.ring(ctx(), ring.clone().add(0, 0.6, 0), radius * (1 - 0.25 * progress), (int) Math.round(radius
                        * 2), -run * 0.06, Particle.END_ROD, null, true);
            }
            if (run % 5 == 0) {
                Fx.sound(center, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 0.9f + (float) progress * 0.8f);
                Vfx.cloud(ctx(), center.clone().add(0, 1.3, 0), Particle.WITCH, 6, 0.6, 0.02, null, true);
                Vfx.cloud(ctx(), center.clone().add(0, 1.2, 0), Particle.DUST, 4, 0.5, 0, GOLD_DUST, true);
            }
            return true;
        }

        /** The toast: the ring flashes, and the guests left inside turn into animals. */
        private void toast() {
            Location ring = center.clone().add(0, 0.2, 0);
            Fx.sound(center, Sound.ENTITY_EVOKER_PREPARE_WOLOLO, 1.2f, 1.2f);
            Fx.sound(center, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.6f);
            Fx.sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.4f);
            Vfx.dot(center.clone().add(0, 1.4, 0), Particle.FLASH, PURPLE, true);
            Vfx.shockwave(ctx(), owner, ring, radius, 0.5, 8, Particle.DUST_COLOR_TRANSITION, WITCH_GLOW, true);
            Kind[] kinds = Kind.values();
            int turned = 0;
            for (LivingEntity enemy : ctx().enemiesNear(owner, center, radius)) {
                Location feet = enemy.getLocation();
                double dx = feet.getX() - center.getX();
                double dz = feet.getZ() - center.getZ();
                if (dx * dx + dz * dz > radius * radius || Math.abs(feet.getY() - center.getY()) > FEAST_HEIGHT
                        || !seen(owner, enemy)) {
                    continue;
                }
                Kind kind = kinds[ThreadLocalRandom.current().nextInt(kinds.length)];
                morph(owner, enemy, kind, duration, params().num("feast_release_damage"), true);
                turned++;
                Vfx.trail(center.clone().add(0, 1.1, 0), Fx.body(enemy), PURPLE, 8, true);
            }
            owner.sendActionBar(Text.mm("<" + PURPLE_TAG + "><b>건배!</b></" + PURPLE_TAG + "> <white>" + turned
                    + "</white><gray>명이 짐승이 되었습니다</gray>"));
            ctx().runLater(owner, duration + FEAST_LINGER / 2, this::clear);
        }

        private void clear() {
            for (Display part : table) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 8);
                }
            }
            Vfx.cloud(ctx(), center.clone().add(0, 1, 0), Particle.POOF, 8, 0.8, 0.02, null, false);
        }
    }

    // ================================================================== helpers

    /** True unless {@code entity} is a player hidden from the owner (aiming and markers must not give it away). */
    private static boolean seen(Player owner, Entity entity) {
        return !(entity instanceof Player player) || owner.canSee(player);
    }

    /** A box of {@code size} centered on {@code center} (relative to the display), turned by {@code rotation}. */
    private static Transformation box(Vector3f center, Vector3f size, Quaternionf rotation) {
        Vector3f translation = new Vector3f(center).sub(new Quaternionf(rotation).transform(new Vector3f(size)
                .mul(0.5f)));
        return new Transformation(translation, new Quaternionf(rotation), new Vector3f(size), new Quaternionf());
    }

    private boolean reserve(int count) {
        live.removeIf(display -> !display.isValid());
        return live.size() + count <= MAX_DISPLAYS;
    }

    private <T extends Display> T keep(T display) {
        live.add(display);
        return display;
    }

    /** True while {@code entity} is under this witch's spell (for tests). */
    public boolean isMorphed(Entity entity) {
        Morph morph = morphs.get(entity.getUniqueId());
        return morph != null && !morph.ended;
    }

    /** The animal {@code entity} was turned into, null if none (for tests). */
    public @Nullable Entity modelOf(Entity entity) {
        Morph morph = morphs.get(entity.getUniqueId());
        return morph == null ? null : morph.model;
    }
}
