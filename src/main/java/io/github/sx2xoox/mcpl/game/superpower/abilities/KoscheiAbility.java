package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.ArrayList;
import java.util.List;
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
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 코셰이 — Koschei the Deathless of the Slavic tale, whose death is in a needle, in an egg, in a duck, in a hare, in an
 * iron chest buried under an oak. While the egg with its death lies on the battlefield near it, Koschei cannot fall
 * below one heart (the egg takes whatever would have killed it), but anyone can find the glowing egg and smash it — so
 * every fight becomes "catch Koschei, or find the egg". It buries the egg wherever it aims in an iron chest that bursts
 * out of the ground, and as its ultimate splits its death into all four shells of the tale, scattered and running away.
 *
 * <p>Look (bone white, faded gold, dark green): the egg is a big cream-and-green speckled egg glowing dark green
 * through walls, a gold ring turning around it and its health in white pips over it; a dark green thread runs from
 * Koschei to it now and then. A blow too heavy for Koschei flows to the egg as a green-gold trail and cracks it; a broken
 * egg bursts into shell and smoke. The chest is an iron casket with a gold lock that bursts out of the ground with a
 * clang and a shock ring. The ultimate throws the iron chest, a brown hare, a green-headed duck and the egg out to the
 * sides on gold trails; the hare hops and the duck waddles away from enemies, and at the end the survivors fly home.
 */
public final class KoscheiAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("koschei", "코셰이", Material.EGG)
            .color(TextColor.color(0xB8963E))
            .difficulty(2)
            .passive("숨겨 둔 죽음",
                    "내 죽음이 든 알(체력 {egg_health})이 전장(경계 안)에 있고 {egg_range}블록 안에 있는 동안 나는 체력 "
                            + "1 아래로 떨어지지 않고, 넘친 피해는 알이 대신 받습니다. 알은 모두에게 보이고(벽 너머로도 "
                            + "희미하게 빛남) 적이 근접 공격·화살·능력으로 부술 수 있으며, {egg_safe_radius}블록 안에 적이 "
                            + "없으면 "
                            + "{egg_regen_interval}초마다 {egg_regen} 회복합니다. 알이 깨지면 나는 {break_damage} 피해를 "
                            + "입고 다시 숨길 때까지 평범한 몸이 됩니다. 경기가 시작되면 알은 내 발밑에 놓입니다.")
            .active("죽음 숨기기",
                    "조준한 바닥이나 적의 발밑({hide_range}블록 이내)에 쇠 상자를 묻어 알을 숨깁니다(알이 있으면 옮기고, 깨졌으면 "
                            + "새로 만듭니다). 상자가 솟아오르는 자리 반경 {box_radius}블록의 적은 {box_damage} 피해를 "
                            + "입고 약 {box_push}블록 밀려납니다.",
                    14)
            .ultimate("겹겹이 숨긴 죽음",
                    "알이 쇠 상자·토끼·오리·알 네 겹으로 갈라져 주위 {layer_spread}블록 곳곳으로 흩어집니다(각 체력 "
                            + "{layer_health}, 토끼와 오리는 적을 피해 도망 다님). {layers_duration}초 동안 겹이 하나라도 "
                            + "남아 있으면 나는 쓰러지지 않고(넘친 피해는 가장 가까운 겹이 받음) 주는 피해가 "
                            + "{layers_bonus}% 늘어납니다. 끝날 때 살아남은 겹 하나마다 체력을 {layer_heal} 회복하고, "
                            + "겹들은 다시 알 하나로 합쳐집니다(하나도 남지 않으면 알도 깨짐).")
            .ultimateNotStandalone()
            .param("egg_health", 12)
            .param("egg_range", 30)
            .param("egg_safe_radius", 4)
            .param("egg_regen_interval", 2)
            .param("egg_regen", 1)
            .param("break_damage", 4)
            .param("hide_range", 8)
            .param("box_radius", 2.5)
            .param("box_damage", 2)
            .param("box_push", 3)
            .param("layer_spread", 10)
            .param("layer_health", 6)
            .param("layers_duration", 8)
            .param("layers_bonus", 20)
            .param("layer_heal", 3)
            .build();

    private static final String GOLD_TAG = "#D9B85C";
    private static final String GREEN_TAG = "#5E8C55";
    private static final String BONE_TAG = "#EDE6D6";

    // ------------------------------------------------------------------ look
    /** Bone white, faded gold, dark green. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xEDE6D6, 0xB8963E, 0x2F4A2C);
    private static final Color BONE = Color.fromRGB(0xEDE6D6);
    private static final Color GOLD = Color.fromRGB(0xB8963E);
    private static final Color GREEN = Color.fromRGB(0x2F4A2C);
    private static final Color GLOW_GREEN = Color.fromRGB(0x5E8C55);
    private static final Particle.DustOptions GOLD_DUST = new Particle.DustOptions(GOLD, 0.8f);
    private static final Particle.DustOptions SMALL_GOLD = new Particle.DustOptions(GOLD, 0.55f);
    private static final Particle.DustOptions GREEN_DUST = new Particle.DustOptions(GREEN, 1.0f);
    private static final Particle.DustOptions BONE_DUST = new Particle.DustOptions(BONE, 0.9f);
    private static final Particle.DustTransition DEATH_FLOW = new Particle.DustTransition(GLOW_GREEN, GOLD, 0.9f);

    /** Block types; block data is created on use (it needs a running server). */
    private static final Material EGG = Material.TURTLE_EGG;
    private static final Material IRON = Material.IRON_BLOCK;
    private static final Material IRON_BAND = Material.POLISHED_BLACKSTONE;
    private static final Material LOCK = Material.GOLD_BLOCK;
    private static final Material HARE_FUR = Material.BROWN_TERRACOTTA;
    private static final Material HARE_TAIL = Material.WHITE_WOOL;
    private static final Material DUCK_BODY = Material.LIGHT_GRAY_WOOL;
    private static final Material DUCK_HEAD = Material.GREEN_CONCRETE;
    private static final Material DUCK_BEAK = Material.ORANGE_CONCRETE;

    private static final int MAX_DISPLAYS = 24;
    private static final double TAU = Math.PI * 2;
    /**
     * Ticks a display following a moving body glides to each new spot: as long as a client lerps a moving entity
     * (3 steps), so a figure moved every tick keeps pace with its hitbox instead of trailing or jittering.
     */
    private static final int FOLLOW_LERP = 3;
    private static final double MAX_SPEED = 1.5;

    // the vessels
    private static final double EGG_HITBOX = 0.55;
    private static final double SMALL_HITBOX = 0.36;
    private static final double BOX_HITBOX = 0.5;
    private static final float EGG_SIZE = 2.0f;
    /** The single turtle egg sits off the middle of its block model by this much (pixels / 16). */
    private static final float EGG_OFF_X = 1f / 16f;
    private static final float EGG_OFF_Z = 2f / 16f;
    private static final long THREAD_PERIOD = 40;
    private static final long RING_PERIOD = 4;
    private static final long BAR_PERIOD = 20;
    private static final long FAR_NOTICE = 100;
    private static final float LABEL_SCALE = 0.7f;
    /** At or under this health the egg beats like a heart. */
    private static final double LOW_HEALTH = 4;

    // active: the iron chest
    private static final int RISE_TICKS = 6;
    private static final int BURST_TICK = 3;
    private static final double PUSH_LIFT = 0.35;
    /** The hiding spot is the ground at most this far below where the aim ends. */
    private static final double MAX_HIDE_DROP = 4;
    /** A pushed body slides about this many blocks per block per tick of horizontal speed. */
    private static final double SLIDE_FACTOR = 6.0;

    // ultimate: the shells
    private static final double MIN_SPREAD = 3.5;
    private static final long FLY_TICKS = 10;
    private static final long HARE_HOP = 10;
    private static final long DUCK_STEP = 4;
    private static final double FLEE_RADIUS = 8;
    private static final double HARE_SPEED = 0.32;
    private static final double DUCK_SPEED = 0.14;
    private static final double SCARED_DUCK = 3;

    /** The four shells of the tale (and the plain egg). */
    private enum Shell {
        EGG("알"),
        CHEST("쇠 상자"),
        HARE("토끼"),
        DUCK("오리");

        final String label;

        Shell(String label) {
            this.label = label;
        }
    }

    // ------------------------------------------------------------------ state

    private final List<Display> live = new ArrayList<>();
    private final List<Vessel> layers = new ArrayList<>();
    private @Nullable Vessel egg;
    private @Nullable KoscheiListener listener;
    private long layersUntil = Long.MIN_VALUE / 2;
    private @Nullable Location layersHome;
    private long lastFarNotice = Long.MIN_VALUE / 2;
    private boolean wasInRange = true;
    private @Nullable Player layerBreaker;

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onGrant() {
        KoscheiListener created = new KoscheiListener();
        Bukkit.getPluginManager().registerEvents(created, ctx().plugin());
        listener = created;
        Player owner = player();
        Location feet = owner.getLocation();
        Spots.snapToGround(feet.getWorld(), feet, 2);
        egg = spawnVessel(owner, Shell.EGG, feet, params().num("egg_health"));
    }

    @Override
    public void onRevoke() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        egg = null;
        layers.clear();
        layersHome = null;
        layerBreaker = null;
        layersUntil = Long.MIN_VALUE / 2;
        live.clear();
    }

    /** The vessels' own damage: only enemies' blows and skills hurt them, and the last one is remembered. */
    private final class KoscheiListener implements Listener {

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onVesselHurt(EntityDamageEvent event) {
            Vessel vessel = vesselOf(event.getEntity());
            if (vessel == null) {
                return;
            }
            Player owner = player();
            Player attacker = event instanceof EntityDamageByEntityEvent byEntity
                    ? ctx().resolveAttacker(byEntity.getDamager()) : null;
            if (attacker == null || !ctx().isEnemy(attacker, vessel.body)) {
                // Falls, fire, suffocation, a stray mob: the death of Koschei is not that easy to break.
                event.setCancelled(true);
                return;
            }
            vessel.lastHitter = attacker;
            if (owner.isOnline()) {
                vessel.crack(false);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onVesselHurtAfter(EntityDamageEvent event) {
            Vessel vessel = vesselOf(event.getEntity());
            if (vessel != null) {
                ctx().runLater(player(), 1, vessel::refreshLabel);
            }
        }
    }

    private @Nullable Vessel vesselOf(Entity entity) {
        Vessel main = egg;
        if (main != null && main.body.equals(entity)) {
            return main;
        }
        for (Vessel layer : layers) {
            if (layer.body.equals(entity)) {
                return layer;
            }
        }
        return null;
    }

    // ================================================================== the vessels

    /**
     * One shell of Koschei's death: an invisible mannequin hitbox (a summon, so enemies can hit and target it) wearing
     * a figure of display entities, with a health label.
     */
    private final class Vessel {
        final Shell shell;
        final Mannequin body;
        final List<BlockDisplay> parts = new ArrayList<>();
        final List<BlockDisplay> casing = new ArrayList<>();
        @Nullable TextDisplay label;
        @Nullable Player lastHitter;
        boolean broken;
        float yaw;
        long nextMove;
        int hop;

        Vessel(Shell shell, Mannequin body) {
            this.shell = shell;
            this.body = body;
            this.yaw = ThreadLocalRandom.current().nextFloat() * 360f;
        }

        double health() {
            return body.isValid() && !body.isDead() ? body.getHealth() : 0;
        }

        double maxHealth() {
            return Combat.maxHealth(body);
        }

        boolean alive() {
            return !broken && body.isValid() && !body.isDead() && health() > 0;
        }

        /** The figure follows its hitbox (turned the way it runs), and the label floats over it. */
        void follow(long tick) {
            Location at = body.getLocation();
            float bob = shell == Shell.HARE && !Motion.isGrounded(body) ? 0.05f : 0f;
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay part = parts.get(i);
                Displays.moveTo(part, at);
                Displays.animate(part, figurePose(shell, i, yaw, 1f, bob, tick), 2);
            }
            TextDisplay shown = label;
            if (shown != null) {
                Displays.moveTo(shown, at.clone().add(0, labelHeight(shell), 0));
            }
        }

        void refreshLabel() {
            TextDisplay shown = label;
            if (shown == null || !shown.isValid() || !alive()) {
                return;
            }
            shown.text(Text.mm(pips(health(), maxHealth())));
        }

        /** A blow lands on the shell: it shakes, cracks and creaks. */
        void crack(boolean heavy) {
            Location at = body.getLocation().add(0, 0.4, 0);
            Vfx.cloud(ctx(), at, Particle.BLOCK, heavy ? 8 : 4, 0.2, 0.05, crumbleOf(shell), false);
            if (shell == Shell.EGG) {
                Vfx.dot(at, Particle.EGG_CRACK, null, false);
            }
            Fx.sound(at, crackSound(shell), 0.9f, 1.0f + Fx.jitter(0.15f));
            long tick = ctx().currentTick();
            for (int i = 0; i < parts.size(); i++) {
                Displays.animate(parts.get(i), figurePose(shell, i, yaw + Fx.jitter(14f), 1.08f, 0f, tick), 1);
            }
        }

        /** Removes the shell (broken or merging): the figure shrinks away or shatters, the hitbox goes. */
        void remove(boolean shatter) {
            broken = true;
            Location at = body.getLocation().add(0, 0.4, 0);
            if (shatter) {
                Vfx.cloud(ctx(), at, Particle.BLOCK, 16, 0.3, 0.1, crumbleOf(shell), false);
                Vfx.cloud(ctx(), at, Particle.DUST, 10, 0.35, 0, GREEN_DUST, false);
                Vfx.cloud(ctx(), at, Particle.SMOKE, 6, 0.25, 0.02, null, false);
                Fx.sound(at, breakSound(shell), 1.0f, 0.9f + Fx.jitter(0.1f));
            }
            for (BlockDisplay part : parts) {
                if (part.isValid()) {
                    if (shatter) {
                        part.remove();
                    } else {
                        Displays.shrinkAway(ctx(), player(), part, 6);
                    }
                }
            }
            parts.clear();
            for (BlockDisplay part : casing) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), player(), part, 8);
                }
            }
            casing.clear();
            if (label != null && label.isValid()) {
                label.remove();
            }
            label = null;
            if (body.isValid()) {
                body.remove();
            }
        }
    }

    /** Spawns a shell at {@code feet} with {@code health}, or null if it cannot be spawned. */
    private @Nullable Vessel spawnVessel(Player owner, Shell shell, Location feet, double health) {
        Location at = Displays.unrotated(feet);
        double maxHealth = Math.max(1, health);
        Mannequin body;
        try {
            body = at.getWorld().spawn(at, Mannequin.class, mannequin -> {
                mannequin.setPersistent(false);
                mannequin.setInvisible(true);
                mannequin.setSilent(true);
                mannequin.setCollidable(false);
                mannequin.setDescription(null);
                mannequin.setImmovable(shell == Shell.EGG || shell == Shell.CHEST);
                setBase(mannequin, Attribute.SCALE, hitboxOf(shell));
                setBase(mannequin, Attribute.MAX_HEALTH, maxHealth);
                mannequin.setHealth(Math.min(maxHealth, Combat.maxHealth(mannequin)));
            });
        } catch (RuntimeException e) {
            ctx().plugin().getLogger().warning("Could not spawn the " + shell.label + " of koschei: " + e);
            return null;
        }
        if (!body.isValid()) {
            return null;
        }
        ctx().summon(owner, body);
        Vessel vessel = new Vessel(shell, body);
        int count = figureSize(shell);
        long tick = ctx().currentTick();
        if (reserve(count + 1)) {
            for (int i = 0; i < count; i++) {
                BlockDisplay part = keep(Displays.block(ctx(), owner, at, figureMaterial(shell, i).createBlockData(),
                        figurePose(shell, i, vessel.yaw, 0.05f, 0f, tick), 0));
                part.setTeleportDuration(FOLLOW_LERP);
                if (shell == Shell.EGG) {
                    Displays.outline(part, GLOW_GREEN);
                } else if (i == 0) {
                    Displays.outline(part, GOLD);
                }
                Displays.animateNextTick(ctx(), owner, part, figurePose(shell, i, vessel.yaw, 1f, 0f, tick), 5);
                vessel.parts.add(part);
            }
            TextDisplay label = keep(Displays.text(ctx(), owner, at.clone().add(0, labelHeight(shell), 0),
                    Text.mm(pips(maxHealth, maxHealth)), LABEL_SCALE, 0));
            label.setTeleportDuration(FOLLOW_LERP);
            label.setSeeThrough(false);
            vessel.label = label;
        }
        return vessel;
    }

    private static void setBase(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private static double hitboxOf(Shell shell) {
        return switch (shell) {
            case EGG -> EGG_HITBOX;
            case CHEST -> BOX_HITBOX;
            case HARE, DUCK -> SMALL_HITBOX;
        };
    }

    private static double labelHeight(Shell shell) {
        return switch (shell) {
            case EGG -> 1.15;
            case CHEST -> 0.9;
            case HARE -> 0.85;
            case DUCK -> 0.8;
        };
    }

    /** White pips for the shell's health, "●●●○". */
    private static String pips(double health, double max) {
        int total = (int) Math.max(1, Math.min(12, Math.ceil(max / 2)));
        int full = (int) Math.ceil(Math.max(0, health) / Math.max(1, max) * total - 1.0E-6);
        StringBuilder text = new StringBuilder("<" + BONE_TAG + ">");
        for (int i = 0; i < total; i++) {
            text.append(i < full ? "●" : "<dark_gray>○</dark_gray>");
        }
        return text.append("</" + BONE_TAG + ">").toString();
    }

    private static int figureSize(Shell shell) {
        return switch (shell) {
            case EGG -> 1;
            case CHEST -> 3;
            case HARE -> 5;
            case DUCK -> 3;
        };
    }

    private static Material figureMaterial(Shell shell, int index) {
        return switch (shell) {
            case EGG -> EGG;
            case CHEST -> index == 0 ? IRON : index == 1 ? IRON_BAND : LOCK;
            case HARE -> index == 4 ? HARE_TAIL : HARE_FUR;
            case DUCK -> index == 0 ? DUCK_BODY : index == 1 ? DUCK_HEAD : DUCK_BEAK;
        };
    }

    private static org.bukkit.block.data.BlockData crumbleOf(Shell shell) {
        return switch (shell) {
            case EGG -> EGG.createBlockData();
            case CHEST -> IRON.createBlockData();
            case HARE -> HARE_FUR.createBlockData();
            case DUCK -> DUCK_BODY.createBlockData();
        };
    }

    private static Sound crackSound(Shell shell) {
        return switch (shell) {
            case EGG -> Sound.ENTITY_TURTLE_EGG_CRACK;
            case CHEST -> Sound.BLOCK_ANVIL_LAND;
            case HARE -> Sound.ENTITY_RABBIT_HURT;
            case DUCK -> Sound.ENTITY_CHICKEN_HURT;
        };
    }

    private static Sound breakSound(Shell shell) {
        return switch (shell) {
            case EGG -> Sound.ENTITY_TURTLE_EGG_BREAK;
            case CHEST -> Sound.BLOCK_IRON_DOOR_CLOSE;
            case HARE -> Sound.ENTITY_RABBIT_DEATH;
            case DUCK -> Sound.ENTITY_CHICKEN_DEATH;
        };
    }

    /**
     * Part {@code index} of a shell's figure, relative to its feet, turned to {@code yaw}, grown by {@code grow}. The
     * egg is a big speckled egg; the chest an iron casket with a dark band and a gold lock; the hare a brown body, head,
     * two long ears and a white tail; the duck a grey body, a green head and an orange beak.
     */
    private static Transformation figurePose(Shell shell, int index, float yaw, float grow, float bob, long tick) {
        Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(-yaw));
        float g = Math.max(0.001f, grow);
        return switch (shell) {
            case EGG -> {
                float s = EGG_SIZE * g;
                // The block model's single egg stands on the block's floor, off its middle.
                Vector3f center = new Vector3f(EGG_OFF_X * s, 0.5f * s, EGG_OFF_Z * s);
                yield box(turn.transform(center), new Vector3f(s), turn);
            }
            case CHEST -> switch (index) {
                case 0 -> part(turn, 0, 0.22f, 0, 0.7f, 0.44f, 0.55f, g);
                case 1 -> part(turn, 0, 0.42f, 0, 0.74f, 0.07f, 0.59f, g);
                default -> part(turn, 0, 0.3f, 0.29f, 0.12f, 0.14f, 0.04f, g);
            };
            case HARE -> switch (index) {
                case 0 -> part(turn, 0, 0.2f + bob, -0.02f, 0.26f, 0.24f, 0.4f, g);
                case 1 -> part(turn, 0, 0.36f + bob, 0.2f, 0.2f, 0.18f, 0.2f, g);
                case 2 -> part(turn, 0.05f, 0.56f + bob, 0.18f, 0.05f, 0.24f, 0.04f, g);
                case 3 -> part(turn, -0.05f, 0.56f + bob, 0.18f, 0.05f, 0.24f, 0.04f, g);
                default -> part(turn, 0, 0.24f + bob, -0.24f, 0.1f, 0.1f, 0.08f, g);
            };
            case DUCK -> {
                // A little waddle.
                float sway = (float) Math.sin(tick * 0.6) * 6f;
                Quaternionf waddle = new Quaternionf(turn).rotateZ((float) Math.toRadians(sway));
                yield switch (index) {
                    case 0 -> part(waddle, 0, 0.17f, 0, 0.26f, 0.22f, 0.42f, g);
                    case 1 -> part(waddle, 0, 0.38f, 0.17f, 0.16f, 0.16f, 0.16f, g);
                    default -> part(waddle, 0, 0.35f, 0.29f, 0.1f, 0.04f, 0.1f, g);
                };
            }
        };
    }

    private static Transformation part(Quaternionf turn, float x, float y, float z, float sx, float sy, float sz,
                                       float grow) {
        Vector3f center = turn.transform(new Vector3f(x, y, z).mul(grow));
        return box(center, new Vector3f(sx, sy, sz).mul(grow), turn);
    }

    // ================================================================== passive: hidden death

    @Override
    public void onTick(long tick) {
        Player owner = player();
        long now = ctx().currentTick();
        Vessel main = egg;
        if (main != null) {
            if (!main.alive()) {
                breakEgg(owner, main);
            } else {
                tickEgg(owner, main, now, tick);
            }
        }
        if (!layers.isEmpty()) {
            tickLayers(owner, now, tick);
        }
    }

    private void tickEgg(Player owner, Vessel main, long now, long tick) {
        Location at = main.body.getLocation();
        if (!ctx().isInsideArena(at) && at.getY() < owner.getWorld().getMinHeight() + 2) {
            main.body.setHealth(0);
            return;
        }
        main.follow(tick);
        boolean inRange = inRange(owner, main);
        if (!inRange && wasInRange && now - lastFarNotice >= FAR_NOTICE) {
            lastFarNotice = now;
            owner.sendActionBar(Text.mm("<gray>알이 너무 멀거나 전장 밖에 있어 </gray><" + GOLD_TAG + ">숨겨 둔 "
                    + "죽음</" + GOLD_TAG + "><gray>이 힘을 잃었습니다</gray>"));
            Fx.sound(owner.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.5f, 1.4f);
        } else if (inRange && !wasInRange) {
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">숨겨 둔 죽음</" + GOLD_TAG + "><gray>이 다시 나를 "
                    + "지킵니다</gray>"));
            Fx.sound(owner.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.6f);
        }
        wasInRange = inRange;
        if (tick % RING_PERIOD == 0) {
            // A faded gold ring turns around the egg (grey and slow when it is too far to protect).
            Location ring = at.clone().add(0, 0.55, 0);
            Vfx.ring(ctx(), ring, 0.5, 6, tick * 0.12, Particle.DUST, inRange ? SMALL_GOLD : BONE_DUST, false);
        }
        if (inRange && tick % THREAD_PERIOD == 0 && !hidden(owner)) {
            Vfx.trail(Fx.body(owner), at.clone().add(0, 0.5, 0), GLOW_GREEN, 20, false);
        }
        long interval = Math.max(1, params().ticks("egg_regen_interval"));
        if (tick % interval == 0 && main.health() < main.maxHealth()
                && ctx().enemiesNear(owner, at, Math.max(0, params().num("egg_safe_radius"))).isEmpty()) {
            main.body.setHealth(Math.min(main.maxHealth(), main.health() + Math.max(0, params().num("egg_regen"))));
            main.refreshLabel();
            Vfx.cloud(ctx(), at.clone().add(0, 0.6, 0), Particle.HAPPY_VILLAGER, 2, 0.2, 0, null, false);
        }
        if (tick % BAR_PERIOD == 0 && inRange && owner.getHealth() <= LOW_HEALTH) {
            // Koschei hangs on by a thread: its death beats inside the egg for everyone to hear.
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>숨겨 둔 죽음</b></" + GOLD_TAG + "> <gray>알 </gray>"
                    + pips(main.health(), main.maxHealth())));
            Fx.sound(at, Sound.ENTITY_WARDEN_HEARTBEAT, 0.8f, 1.2f);
            Vfx.ring(ctx(), at.clone().add(0, 0.1, 0), 0.7, 10, 0, Particle.DUST, GREEN_DUST, false);
            for (int i = 0; i < main.parts.size(); i++) {
                Displays.animate(main.parts.get(i), figurePose(main.shell, i, main.yaw, 1.12f, 0f, tick), 2);
            }
        }
    }

    /**
     * Whether the egg protects Koschei: near enough, and on the battlefield (an egg the shrinking border left outside
     * cannot be reached without taking the border's damage, so it no longer keeps Koschei alive).
     */
    private boolean inRange(Player owner, Vessel vessel) {
        double range = Math.max(0, params().num("egg_range"));
        Location at = vessel.body.getLocation();
        return at.getWorld().equals(owner.getWorld()) && at.distanceSquared(owner.getLocation()) <= range * range
                && ctx().isInsideArena(at);
    }

    /** The vessels that keep Koschei alive right now: every shell during the ultimate, else the egg if near. */
    private List<Vessel> guardians(Player owner) {
        List<Vessel> found = new ArrayList<>();
        if (!layers.isEmpty()) {
            for (Vessel layer : layers) {
                if (layer.alive() && ctx().isInsideArena(layer.body.getLocation())) {
                    found.add(layer);
                }
            }
            return found;
        }
        Vessel main = egg;
        if (main != null && main.alive() && inRange(owner, main)) {
            found.add(main);
        }
        return found;
    }

    /**
     * A blow that would take Koschei below one heart only takes it down to one; what is left over strikes the nearest
     * shell of its death instead (and may break it).
     */
    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        if (event.isCancelled() || isInescapable(event)) {
            return;
        }
        Player owner = player();
        double health = owner.getHealth();
        double lethal = event.getFinalDamage();
        double allowed = Math.max(0, health - 1);
        if (lethal <= allowed + 1.0E-9) {
            return;
        }
        List<Vessel> holders = guardians(owner);
        if (holders.isEmpty()) {
            return;
        }
        double overflow = lethal - allowed;
        if (allowed <= 1.0E-6) {
            event.setDamage(0);
        } else {
            for (int i = 0; i < 4 && event.getFinalDamage() > allowed + 1.0E-9; i++) {
                double finalDamage = event.getFinalDamage();
                event.setDamage(Math.max(0, event.getDamage() * allowed / finalDamage));
            }
        }
        Vessel nearest = holders.getFirst();
        double best = Double.MAX_VALUE;
        Location feet = owner.getLocation();
        for (Vessel holder : holders) {
            Location at = holder.body.getLocation();
            double distance = at.getWorld().equals(feet.getWorld()) ? at.distanceSquared(feet) : Double.MAX_VALUE;
            if (distance < best) {
                best = distance;
                nearest = holder;
            }
        }
        Player attacker = event instanceof EntityDamageByEntityEvent byEntity
                ? ctx().resolveAttacker(byEntity.getDamager()) : null;
        transfer(owner, nearest, overflow, attacker != null && ctx().isEnemy(owner, attacker) ? attacker : null);
    }

    /** The death that did not take Koschei flows into the shell: a green-gold trail, and the shell cracks. */
    private void transfer(Player owner, Vessel vessel, double amount, @Nullable Player attacker) {
        Location from = Fx.body(owner);
        Location to = vessel.body.getLocation().add(0, 0.5, 0);
        if (!hidden(owner)) {
            for (int i = 0; i < Vfx.n(ctx(), 3); i++) {
                Vfx.trail(from.clone().add(Fx.jitter(0.3f), Fx.jitter(0.3f), Fx.jitter(0.3f)), to,
                        i % 2 == 0 ? GLOW_GREEN : GOLD, 10 + i * 2, false);
            }
            Vfx.cloud(ctx(), from, Particle.DUST_COLOR_TRANSITION, 6, 0.3, 0, DEATH_FLOW, false);
        }
        Fx.sound(from, Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 1.4f);
        if (attacker != null) {
            vessel.lastHitter = attacker;
        }
        double left = vessel.health() - Math.max(0, amount);
        if (left <= 1.0E-6) {
            vessel.body.setHealth(0);
        } else {
            vessel.body.setHealth(left);
            vessel.crack(true);
            vessel.refreshLabel();
        }
        owner.sendActionBar(Text.mm("<" + GREEN_TAG + "><b>죽음이 " + vessel.shell.label + "(으)로 넘어갔습니다</b></"
                + GREEN_TAG + "> " + pips(Math.max(0, left), vessel.maxHealth())));
    }

    /** The egg with Koschei's death broke: Koschei is hurt and mortal until it hides a new one. */
    private void breakEgg(Player owner, Vessel main) {
        egg = null;
        Player breaker = main.lastHitter;
        main.remove(true);
        Location at = main.body.getLocation().add(0, 0.4, 0);
        Vfx.burst(ctx(), at, PALETTE, 0.6, Particle.SMOKE, true);
        Vfx.dot(at, Particle.FLASH, GLOW_GREEN, true);
        ctx().announce(Text.mm("<" + GOLD_TAG + "><player>의 죽음이 든 알이 깨졌습니다!</" + GOLD_TAG + ">",
                Placeholder.component("player", owner.name())));
        owner.sendActionBar(Text.mm("<red><b>죽음이 든 알이 깨졌다!</b></red> <gray>다시 숨길 때까지 평범한 "
                + "몸입니다</gray>"));
        hurtByBreak(owner, breaker);
    }

    /** A broken shell hurts Koschei (credited to whoever broke it), a tick later. */
    private void hurtByBreak(Player owner, @Nullable Player breaker) {
        double damage = Math.max(0, params().num("break_damage"));
        if (damage <= 0) {
            return;
        }
        ctx().runLater(owner, 1, () -> {
            if (!ctx().isAlive(owner) || owner.isDead()) {
                return;
            }
            Fx.sound(owner.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0f, 0.6f);
            if (breaker != null && ctx().isAlive(breaker) && ctx().isEnemy(breaker, owner)) {
                ctx().damage(breaker, owner, damage);
            } else {
                owner.setNoDamageTicks(0);
                owner.damage(damage, DamageSource.builder(DamageType.MAGIC).build());
            }
        });
    }

    private static boolean isInescapable(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        if (cause == DamageCause.VOID || cause == DamageCause.KILL || cause == DamageCause.SUICIDE) {
            return true;
        }
        DamageType type = event.getDamageSource().getDamageType();
        return type == DamageType.OUT_OF_WORLD || type == DamageType.GENERIC_KILL;
    }

    // ================================================================== active: hiding the death

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        if (!layers.isEmpty()) {
            return ActivationResult.fail("죽음이 겹겹이 흩어져 있는 동안은 숨길 수 없습니다");
        }
        Location spot = hidingSpot(owner);
        if (spot == null) {
            return ActivationResult.fail("알을 숨길 땅을 조준하세요");
        }
        bury(owner, spot);
        return ActivationResult.success();
    }

    /**
     * The ground aimed at within reach where an egg fits: under the aimed enemy, else where the aim meets a block, else
     * the ground under the end of the reach (not far below it); null if there is none (aiming at the sky).
     */
    private @Nullable Location hidingSpot(Player owner) {
        double range = Math.max(1, params().num("hide_range"));
        Location eye = owner.getEyeLocation();
        Vector direction = eye.getDirection();
        RayTraceResult hit = owner.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        double reach = hit == null ? range : hit.getHitPosition().distance(eye.toVector());
        LivingEntity aimed = ctx().findTarget(owner, reach);
        Location spot;
        if (aimed != null && vesselOf(aimed) == null) {
            spot = aimed.getLocation();
        } else if (hit != null) {
            spot = hit.getHitPosition().toLocation(owner.getWorld());
            if (hit.getHitBlockFace() != BlockFace.UP) {
                spot.subtract(direction.clone().multiply(0.35));
            }
        } else {
            spot = eye.clone().add(direction.clone().multiply(range));
        }
        double top = spot.getY();
        spot.add(0, 0.3, 0);
        Spots.snapToGround(owner.getWorld(), spot, MAX_HIDE_DROP);
        if (spot.getY() > top + 0.25) {
            return null; // nothing below within reach: the sky
        }
        spot = Displays.unrotated(spot);
        double width = 0.6 * EGG_HITBOX;
        if (!Spots.isFree(owner.getWorld(), spot, width, 1.8 * EGG_HITBOX) || !Spots.hasGroundBelow(owner.getWorld(),
                spot) || !ctx().isInsideArena(spot)) {
            return null;
        }
        return spot;
    }

    /** The iron chest bursts out of the ground, scattering the enemies around it, with the egg inside. */
    private void bury(Player owner, Location spot) {
        Fx.sound(spot, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.0f, 0.7f);
        Fx.sound(spot, Sound.BLOCK_CHAIN_PLACE, 0.8f, 0.8f);
        // Telegraph: the ground over the chest cracks in a ring.
        Vfx.ring(ctx(), spot.clone().add(0, 0.08, 0), params().num("box_radius"), 16, 0, Particle.DUST, GOLD_DUST,
                false);
        List<BlockDisplay> chest = new ArrayList<>();
        long tick = ctx().currentTick();
        float yaw = owner.getLocation().getYaw();
        if (reserve(3)) {
            for (int i = 0; i < 3; i++) {
                BlockDisplay part = keep(Displays.block(ctx(), owner, spot, figureMaterial(Shell.CHEST, i)
                        .createBlockData(), sunk(figurePose(Shell.CHEST, i, yaw, 1f, 0f, tick), -0.7f), 0));
                Displays.animateNextTick(ctx(), owner, part, sunk(figurePose(Shell.CHEST, i, yaw, 1f, 0f, tick),
                        -0.12f), RISE_TICKS - 1);
                chest.add(part);
            }
        }
        Loop.repeat(ctx(), owner, 1, 1, RISE_TICKS, run -> {
            Vfx.cloud(ctx(), spot.clone().add(0, 0.2, 0), Particle.BLOCK, 3, 0.35, 0.05, groundOf(spot), false);
            if (run == BURST_TICK) {
                burst(owner, spot);
            }
            return true;
        }, () -> settle(owner, spot, chest));
        Vfx.cloud(ctx(), spot.clone().add(0, 0.3, 0), Particle.DUST_PLUME, 8, 0.4, 0.02, null, false);
    }

    private org.bukkit.block.data.BlockData groundOf(Location spot) {
        org.bukkit.block.Block below = spot.clone().subtract(0, 0.2, 0).getBlock();
        return below.getType().isSolid() ? below.getBlockData() : IRON.createBlockData();
    }

    /** The chest's burst: enemies around take a little damage and are thrown off. */
    private void burst(Player owner, Location spot) {
        double radius = Math.max(0, params().num("box_radius"));
        double push = Math.max(0, params().num("box_push")) / SLIDE_FACTOR;
        for (LivingEntity enemy : ctx().enemiesNear(owner, spot, radius)) {
            if (vesselOf(enemy) != null) {
                continue;
            }
            Combat.hit(ctx(), owner, enemy, params().num("box_damage"));
            if (!Combat.isTargetable(ctx(), owner, enemy)) {
                continue;
            }
            Vector away = enemy.getLocation().toVector().subtract(spot.toVector()).setY(0);
            if (away.lengthSquared() < 1.0E-4) {
                away = Motion.horizontalFacing(owner.getLocation());
            }
            Motion.push(enemy, away.normalize().multiply(push).setY(PUSH_LIFT), MAX_SPEED);
            if (seen(owner, enemy)) {
                Vfx.cloud(ctx(), Fx.body(enemy), Particle.CRIT, 6, 0.3, 0.1, null, false);
            }
        }
        Vfx.shockwave(ctx(), owner, spot.clone().add(0, 0.1, 0), 0.4, radius + 0.3, 5, Particle.DUST, GOLD_DUST, false);
        Vfx.cloud(ctx(), spot.clone().add(0, 0.4, 0), Particle.BLOCK, 14, 0.5, 0.15, groundOf(spot), false);
        Fx.sound(spot, Sound.BLOCK_ANVIL_LAND, 0.9f, 0.6f + Fx.jitter(0.05f));
        Fx.sound(spot, Sound.BLOCK_IRON_DOOR_CLOSE, 1.0f, 0.7f);
        Fx.sound(spot, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 0.8f);
    }

    /** The egg goes into the chest: moved there if it exists, a new one if it broke. */
    private void settle(Player owner, Location spot, List<BlockDisplay> chest) {
        Vessel main = egg;
        if (main != null && main.alive()) {
            Location from = main.body.getLocation().add(0, 0.5, 0);
            Vfx.trail(from, spot.clone().add(0, 0.6, 0), GOLD, 12, false);
            Vfx.cloud(ctx(), from, Particle.DUST, 6, 0.3, 0, GREEN_DUST, false);
            for (BlockDisplay part : main.casing) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 8);
                }
            }
            main.casing.clear();
            main.body.teleport(spot.clone().add(0, 0.25, 0));
            main.body.setVelocity(new Vector());
        } else {
            if (main != null) {
                main.remove(false);
            }
            Vessel fresh = spawnVessel(owner, Shell.EGG, spot.clone().add(0, 0.25, 0), params().num("egg_health"));
            egg = fresh;
            main = fresh;
            if (fresh != null) {
                owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>죽음 숨기기</b></" + GOLD_TAG + "> <gray>새 알에 "
                        + "죽음을 숨겼습니다</gray>"));
            }
        }
        if (main == null) {
            for (BlockDisplay part : chest) {
                if (part.isValid()) {
                    Displays.shrinkAway(ctx(), owner, part, 8);
                }
            }
            return;
        }
        main.casing.addAll(chest);
        wasInRange = true;
        Location at = spot.clone().add(0, 0.6, 0);
        Vfx.cloud(ctx(), at, Particle.DUST_COLOR_TRANSITION, 10, 0.35, 0, DEATH_FLOW, false);
        Fx.sound(at, Sound.BLOCK_CHEST_CLOSE, 0.8f, 0.7f);
    }

    /** The same pose lowered by {@code dy} (the chest rising out of the ground). */
    private static Transformation sunk(Transformation pose, float dy) {
        return new Transformation(new Vector3f(pose.getTranslation()).add(0, dy, 0), pose.getLeftRotation(),
                pose.getScale(), pose.getRightRotation());
    }

    // ================================================================== ultimate: death in layers

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        if (!layers.isEmpty()) {
            return ActivationResult.fail("이미 죽음이 흩어져 있습니다");
        }
        Vessel main = egg;
        Location home = main != null && main.alive() ? main.body.getLocation() : owner.getLocation();
        Spots.snapToGround(home.getWorld(), home, 3);
        home = Displays.unrotated(home);
        if (main != null) {
            main.remove(false);
            egg = null;
        }
        layersHome = home;
        layerBreaker = null;
        long duration = Math.max(1, params().ticks("layers_duration"));
        layersUntil = ctx().currentTick() + duration;
        double health = Math.max(1, params().num("layer_health"));
        Location center = home.clone().add(0, 0.6, 0);
        Vfx.dot(center, Particle.FLASH, GOLD, true);
        Vfx.burst(ctx(), center, PALETTE, 0.8, Particle.WAX_OFF, true);
        Fx.sound(center, Sound.ENTITY_TURTLE_EGG_HATCH, 1.2f, 0.8f);
        Fx.sound(center, Sound.BLOCK_BELL_RESONATE, 0.9f, 0.7f);
        Fx.sound(center, Sound.ENTITY_EVOKER_CAST_SPELL, 0.8f, 0.6f);
        double angle = ThreadLocalRandom.current().nextDouble(TAU);
        for (Shell shell : List.of(Shell.CHEST, Shell.HARE, Shell.DUCK, Shell.EGG)) {
            angle += TAU / 4 + ThreadLocalRandom.current().nextDouble(-0.4, 0.4);
            Location spot = scatterSpot(owner, home, angle);
            Vessel layer = spawnVessel(owner, shell, spot, health);
            if (layer == null) {
                continue;
            }
            layers.add(layer);
            Vfx.trail(center, spot.clone().add(0, 0.5, 0), GOLD, (int) FLY_TICKS, true);
            Vfx.trail(center, spot.clone().add(0, 0.7, 0), GLOW_GREEN, (int) FLY_TICKS + 3, true);
        }
        if (layers.isEmpty()) {
            layersUntil = Long.MIN_VALUE / 2;
            egg = spawnVessel(owner, Shell.EGG, home, params().num("egg_health"));
            return ActivationResult.fail("죽음을 숨길 자리가 없습니다");
        }
        owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>겹겹이 숨긴 죽음</b></" + GOLD_TAG + "> <gray>상자·토끼·"
                + "오리·알 중 하나라도 남으면 쓰러지지 않습니다</gray>"));
        return ActivationResult.success();
    }

    /** A safe spot for a shell around {@code home}, as far out as there is room for (at most the spread). */
    private Location scatterSpot(Player owner, Location home, double angle) {
        double spread = Math.max(MIN_SPREAD, params().num("layer_spread"));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 8; attempt++) {
            double distance = spread * (0.45 + random.nextDouble(0.55)) * (1 - attempt * 0.1);
            double a = angle + (attempt == 0 ? 0 : random.nextDouble(-0.6, 0.6));
            Location spot = home.clone().add(Math.cos(a) * distance, 2.5, Math.sin(a) * distance);
            Spots.snapToGround(home.getWorld(), spot, 6);
            if (Spots.isFree(home.getWorld(), spot, 0.4, 1.0) && Spots.hasGroundBelow(home.getWorld(), spot)
                    && ctx().isInsideArena(spot) && spot.getY() < home.getY() + 2.6 && spot.getY() > home.getY() - 4) {
                return Displays.unrotated(spot);
            }
        }
        return home.clone();
    }

    private void tickLayers(Player owner, long now, long tick) {
        for (Vessel layer : new ArrayList<>(layers)) {
            if (!layer.broken && !layer.alive()) {
                Player breaker = layer.lastHitter;
                layerBreaker = breaker;
                layer.remove(true);
                ctx().announce(Text.mm("<" + GOLD_TAG + "><player>의 죽음을 숨긴 " + layer.shell.label + "이(가) "
                        + "부서졌습니다!</" + GOLD_TAG + ">", Placeholder.component("player", owner.name())));
                if (breaker != null && ctx().isAlive(breaker)) {
                    breaker.sendActionBar(Text.mm("<" + GOLD_TAG + ">" + layer.shell.label + "을(를) 부쉈습니다</"
                            + GOLD_TAG + ">"));
                }
                continue;
            }
            if (layer.broken) {
                continue;
            }
            run(owner, layer, now);
            layer.follow(tick);
        }
        boolean anyAlive = layers.stream().anyMatch(Vessel::alive);
        if (now >= layersUntil || !anyAlive) {
            gather(owner);
        } else if (tick % BAR_PERIOD == 0) {
            long alive = layers.stream().filter(Vessel::alive).count();
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>겹겹이 숨긴 죽음</b></" + GOLD_TAG + "> <white>" + alive
                    + "</white><gray>겹 남음 · " + Fx.seconds(Math.max(0, layersUntil - now)) + "초</gray>"));
        }
    }

    /** The hare hops and the duck waddles away from the nearest enemy; the chest and the egg sit still. */
    private void run(Player owner, Vessel layer, long now) {
        if (layer.shell != Shell.HARE && layer.shell != Shell.DUCK || now < layer.nextMove) {
            return;
        }
        Mannequin body = layer.body;
        if (!Motion.isGrounded(body)) {
            return;
        }
        Location at = body.getLocation();
        LivingEntity threat = null;
        for (LivingEntity enemy : ctx().enemiesNear(owner, at, FLEE_RADIUS)) {
            if (vesselOf(enemy) == null && !(enemy instanceof Mannequin)) {
                threat = enemy;
                break;
            }
        }
        Vector direction;
        if (threat != null) {
            direction = at.toVector().subtract(threat.getLocation().toVector()).setY(0);
        } else {
            direction = new Vector(-Math.sin(Math.toRadians(layer.yaw)), 0, Math.cos(Math.toRadians(layer.yaw)))
                    .rotateAroundY(ThreadLocalRandom.current().nextDouble(-1.0, 1.0));
        }
        Location home = layersHome;
        double spread = Math.max(MIN_SPREAD, params().num("layer_spread"));
        if (home != null && at.distanceSquared(home) > (spread + 2) * (spread + 2)) {
            direction = home.toVector().subtract(at.toVector()).setY(0);
        }
        if (direction.lengthSquared() < 1.0E-6) {
            direction = new Vector(1, 0, 0);
        }
        direction.normalize();
        // Never run off a cliff, into a wall or out of the arena: try turning a little.
        Vector chosen = null;
        for (double turn : new double[] {0, 0.6, -0.6, 1.2, -1.2, 2.0, -2.0}) {
            Vector candidate = direction.clone().rotateAroundY(turn);
            Location ahead = at.clone().add(candidate.clone().multiply(1.0));
            Location ground = ahead.clone().add(0, 0.6, 0);
            Spots.snapToGround(at.getWorld(), ground, 2.5);
            if (ground.getY() <= ahead.getY() + 0.65 && ground.getY() >= at.getY() - 1.5
                    && Spots.isFree(at.getWorld(), ground, 0.3, 0.7) && Spots.hasGroundBelow(at.getWorld(), ground)
                    && ctx().isInsideArena(ground)) {
                chosen = candidate;
                break;
            }
        }
        if (chosen == null) {
            layer.nextMove = now + 10;
            return;
        }
        layer.yaw = (float) Math.toDegrees(Math.atan2(-chosen.getX(), chosen.getZ()));
        if (layer.shell == Shell.HARE) {
            Vector hop = chosen.clone().multiply(HARE_SPEED).setY(Motion.launchSpeed(0.6));
            body.setVelocity(Motion.clamp(hop, MAX_SPEED));
            layer.nextMove = now + HARE_HOP;
            if (++layer.hop % 2 == 0) {
                Fx.sound(at, Sound.ENTITY_RABBIT_JUMP, 0.5f, 1.2f + Fx.jitter(0.1f));
            }
        } else {
            boolean scared = threat != null && threat.getLocation().distanceSquared(at) < SCARED_DUCK * SCARED_DUCK;
            Vector step = chosen.clone().multiply(scared ? DUCK_SPEED * 2.2 : DUCK_SPEED);
            if (scared) {
                step.setY(Motion.launchSpeed(0.7));
                Fx.sound(at, Sound.ENTITY_CHICKEN_HURT, 0.5f, 1.6f + Fx.jitter(0.1f));
                Vfx.cloud(ctx(), at.clone().add(0, 0.4, 0), Particle.WHITE_ASH, 4, 0.2, 0.02, null, false);
            }
            body.setVelocity(Motion.clamp(step, MAX_SPEED));
            layer.nextMove = now + (scared ? 8 : DUCK_STEP);
        }
    }

    /** The layers' time is up: the survivors heal Koschei and fly home into one egg (or none survived). */
    private void gather(Player owner) {
        Location home = layersHome;
        layersHome = null;
        layersUntil = Long.MIN_VALUE / 2;
        int survivors = 0;
        Location center = home == null ? owner.getLocation() : home;
        for (Vessel layer : layers) {
            if (layer.alive()) {
                survivors++;
                Vfx.trail(layer.body.getLocation().add(0, 0.5, 0), center.clone().add(0, 0.6, 0), GOLD, 12, true);
                layer.remove(false);
            } else if (!layer.broken) {
                layer.remove(true);
            }
        }
        layers.clear();
        if (survivors > 0) {
            double heal = survivors * Math.max(0, params().num("layer_heal"));
            ctx().heal(owner, heal);
            Vfx.cloud(ctx(), Fx.body(owner), Particle.DUST_COLOR_TRANSITION, 10, 0.4, 0, DEATH_FLOW, false);
            Fx.sound(owner.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.5f);
            Location eggSpot = center.clone();
            Vessel joined = spawnVessel(owner, Shell.EGG, eggSpot, params().num("egg_health"));
            egg = joined;
            wasInRange = true;
            Fx.sound(eggSpot, Sound.ENTITY_TURTLE_EGG_HATCH, 0.8f, 1.4f);
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + "><b>겹겹이 숨긴 죽음</b></" + GOLD_TAG + "> <white>"
                    + survivors + "</white><gray>겹이 살아남아 체력 " + AbilityInfo.formatNumber(heal) + " 회복, 알로 "
                    + "다시 합쳐졌습니다</gray>"));
        } else {
            egg = null;
            Vfx.burst(ctx(), center.clone().add(0, 0.5, 0), PALETTE, 0.6, Particle.SMOKE, true);
            ctx().announce(Text.mm("<" + GOLD_TAG + "><player>의 숨겨 둔 죽음이 모두 부서졌습니다!</" + GOLD_TAG + ">",
                    Placeholder.component("player", owner.name())));
            owner.sendActionBar(Text.mm("<red><b>죽음을 숨긴 겹이 모두 부서졌다!</b></red>"));
            hurtByBreak(owner, layerBreaker);
        }
    }

    /** While the death is in layers Koschei hits harder: only changes the damage. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || layers.isEmpty() || ctx().currentTick() >= layersUntil) {
            return;
        }
        Entity damager = event.getDamager();
        boolean own = player().equals(damager)
                || damager instanceof Projectile projectile && player().equals(projectile.getShooter());
        if (own) {
            event.setDamage(event.getDamage() * (1 + Math.max(0, params().num("layers_bonus")) / 100.0));
        }
    }

    // ================================================================== helpers

    /** True if Koschei cannot be seen (invisibility): its thread must not give it away. */
    private static boolean hidden(Player owner) {
        return owner.isInvisible() || owner.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
    }

    /** True unless {@code entity} is a player hidden from the owner (its visuals would give it away). */
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

    /** The egg's hitbox, null if the egg is broken (for tests). */
    public @Nullable LivingEntity eggBody() {
        Vessel main = egg;
        return main != null && main.alive() ? main.body : null;
    }

    /** The hitboxes of the ultimate's shells still standing (for tests). */
    public List<LivingEntity> layerBodies() {
        List<LivingEntity> bodies = new ArrayList<>();
        for (Vessel layer : layers) {
            if (layer.alive()) {
                bodies.add(layer.body);
            }
        }
        return bodies;
    }
}
