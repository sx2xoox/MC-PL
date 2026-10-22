package io.github.sx2xoox.mcpl.game.superpower.abilities;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Combat;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.LandingGuard;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Loop;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Stealth;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Vfx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.util.Text;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * 도둑 — steals ultimates. Every melee hit lifts a little of the victim's ultimate gauge, the active slips behind an
 * aimed enemy to pick its pocket (damage, a chunk of its gauge and a moment without its abilities) and the ultimate
 * robs every enemy around of half their gauge before the thief vanishes from their screens.
 *
 * <p>The heist is staged: the gauge is taken at once, the loot flies over while a ring of gold ingots spirals into the
 * thief, and when it lands the thief's share is paid (the framework empties the gauge right after the ultimate, so it
 * must come later) and the thief vanishes. The stealth hides the owner with {@code hidePlayer}, so the ultimate is not
 * standalone: it needs {@link #onRevoke()} to show the owner again. Being hit does not break it; attacking does.
 *
 * <p>Look (dark green and gold): stolen gauge flies over as spinning gold-nugget displays in arcs with a glittering
 * trail, a floating "-N%" over each victim, a coin pouch snatched from the pickpocketed enemy's back, a lingering
 * afterimage where the thief slipped away, a gold padlock ring over a silenced victim, the vault ring and a gold sweep
 * marking the heist radius, green-black smoke where the thief vanishes and a stolen coin glinting at its feet.
 */
public final class ThiefAbility extends Ability {

    public static final AbilityInfo INFO = AbilityInfo.builder("thief", "도둑", Material.BUNDLE)
            .color(TextColor.color(0x6CC17A))
            .difficulty(2)
            .passive("손버릇",
                    "근접 공격으로 적을 때리면 그 적의 궁극기 게이지를 {pick_steal}% 훔쳐 내 것으로 만듭니다"
                            + "(같은 적에게서는 {pick_interval}초마다).")
            .active("소매치기",
                    "조준한 적({dash_range}블록 이내)의 등 뒤로 순식간에 파고들어 {dash_damage} 피해를 주고, 궁극기 "
                            + "게이지를 {dash_steal}% 빼앗으며 {dash_silence}초간 능력을 쓰지 못하게 합니다.",
                    10)
            .ultimate("대도",
                    "반경 {heist_radius}블록 모든 적의 궁극기 게이지를 {heist_steal}%씩 털어 그중 {heist_share}%를 "
                            + "챙긴 뒤, {heist_invis}초간 적의 눈에서 사라지고 신속 I을 얻습니다. 공격하면 은신이 풀리지만 "
                            + "그 첫 근접 공격은 게이지를 {ambush_mult}배로 훔칩니다.")
            // The stealth needs onRevoke to show the owner again, and the ambush needs the damage hooks.
            .ultimateNotStandalone()
            .param("pick_steal", 4)
            .param("pick_interval", 1)
            .param("dash_range", 10)
            .param("dash_damage", 4)
            .param("dash_steal", 15)
            .param("dash_silence", 1.5)
            .param("heist_radius", 12)
            .param("heist_steal", 50)
            .param("heist_share", 30)
            .param("heist_invis", 5)
            .param("ambush_mult", 3)
            .build();

    private static final String COLOR_TAG = "#6CC17A";
    private static final String GOLD_TAG = "#FFD54F";
    /** Coin gold core, thieves' green body, a near-black green edge. */
    private static final Vfx.Palette PALETTE = Vfx.Palette.of(0xFFD54F, 0x2E7D4F, 0x0F2A1A);
    private static final Color GOLD = Color.fromRGB(0xFFD54F);
    private static final Color DEEP_GOLD = Color.fromRGB(0xC99A2E);
    private static final Particle.DustOptions GLINT = new Particle.DustOptions(GOLD, 0.7f);
    private static final Particle.DustTransition SMOKE = PALETTE.fade(1.4f);
    private static final double TAU = Math.PI * 2;

    /** Enemy mobs this close that are chasing the owner lose track of them when the thief vanishes. */
    private static final double AGGRO_DROP_RANGE = 24;
    /** The drop after slipping behind an airborne enemy is harmless if it lands within this long. */
    private static final long LANDING_GRACE_TICKS = 60;
    /** Gold coins flying from one victim, and in total for one skill. */
    private static final int COINS_PER_VICTIM = 3;
    private static final int MAX_COINS = 10;
    private static final int COIN_FLIGHT_TICKS = 12;
    /** "-N%" labels over robbed enemies in one heist; more victims only get particles. */
    private static final int MAX_LABELS = 6;
    private static final int LABEL_TICKS = 22;
    private static final int POUCH_TICKS = 9;
    private static final int AFTERIMAGE_TICKS = 10;
    private static final int HEIST_SWEEP_TICKS = 8;
    /** Gold ingots spiralling into the thief while the loot flies in; it vanishes when they arrive. */
    private static final int VAULT_INGOTS = 6;
    private static final double VAULT_RADIUS = 1.7;
    private static final long GLINT_PERIOD = 8;

    private final Stealth stealth = new Stealth();
    /** When each victim was last robbed by the passive. */
    private final Map<UUID, Long> lastPick = new HashMap<>();
    /** The tick an attack from stealth revealed the thief; that tick's melee hit is an ambush. */
    private long ambushTick = -1;
    /** The target of the ability hit being dealt right now, and whether it landed (see {@link #onDamageDealt}). */
    private @Nullable LivingEntity pendingTarget;
    private boolean landed;
    /** Protects the landing after slipping behind an airborne enemy. */
    private final LandingGuard landing = new LandingGuard();

    private enum Reveal { ATTACK, EXPIRED, REVOKE }

    @Override
    public AbilityInfo info() {
        return INFO;
    }

    @Override
    public void onRevoke() {
        reveal(Reveal.REVOKE);
        lastPick.clear();
        landing.disarm();
    }

    @Override
    public void onTakeDamage(EntityDamageEvent event) {
        landing.absorb(event, ctx().currentTick());
    }

    // ------------------------------------------------------------------ passive: sticky fingers

    /** Attacking out of stealth reveals the thief; the hit itself becomes an ambush. */
    @Override
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (event.isCancelled() || !stealth.isActive() || !isOwnAttack(event)) {
            return;
        }
        ambushTick = ctx().currentTick();
        reveal(Reveal.ATTACK);
    }

    @Override
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
        if (ctx().isAbilityDamage()) {
            if (victim.equals(pendingTarget)) {
                landed = true;
            }
            ambushTick = -1; // a skill that broke the stealth spent the ambush
            return;
        }
        long now = ctx().currentTick();
        boolean ambush = ambushTick == now;
        ambushTick = -1;
        if (!player().equals(event.getDamager()) || event.getCause() != DamageCause.ENTITY_ATTACK
                || !(victim instanceof Player robbed)) {
            return;
        }
        Long last = lastPick.get(robbed.getUniqueId());
        if (!ambush && last != null && now - last < params().ticks("pick_interval")) {
            return;
        }
        lastPick.put(robbed.getUniqueId(), now);
        double amount = Math.max(0, params().num("pick_steal")) * (ambush ? Math.max(1, params().num("ambush_mult"))
                : 1);
        double taken = steal(robbed, amount);
        if (taken <= 0) {
            return;
        }
        ctx().addGauge(player(), taken);
        Location body = Fx.body(robbed);
        coins(body, ambush ? COINS_PER_VICTIM : 1, ambush ? 1.0f : 0.8f);
        stolenLabel(robbed, taken, ambush ? 1.3f : 0.8f);
        Vfx.cloud(ctx(), body, Particle.WAX_ON, ambush ? 8 : 3, 0.25, 0.1, null, false);
        Fx.sound(body, Sound.ITEM_BUNDLE_REMOVE_ONE, 0.8f, 1.3f + Fx.jitter(0.1f));
        if (ambush) {
            // The ambush: a gold X slashed across the victim and a sharp crit.
            Vfx.arc(ctx(), body, 0.7, player().getLocation().getYaw() + 45, 120, 10, Particle.DUST_COLOR_TRANSITION,
                    PALETTE.glow(1.1f), false);
            Vfx.arc(ctx(), body.clone().add(0, 0.25, 0), 0.7, player().getLocation().getYaw() - 45, 120, 10,
                    Particle.DUST_COLOR_TRANSITION, PALETTE.glow(1.1f), false);
            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.8f + Fx.jitter(0.08f));
        }
        player().sendActionBar(Text.mm("<" + GOLD_TAG + ">" + (ambush ? "✦ 기습 강탈" : "손버릇") + " +"
                + AbilityInfo.formatNumber(taken) + "%</" + GOLD_TAG + ">"));
    }

    /** The owner's own attack: a melee swing, a projectile it shot, or one of its skills. */
    private boolean isOwnAttack(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        return damager.equals(player())
                || damager instanceof Projectile projectile && player().equals(projectile.getShooter());
    }

    /** Takes up to {@code amount} gauge from {@code victim}; returns how much it actually had to give. */
    private double steal(Player victim, double amount) {
        double taken = Math.min(ctx().gauge(victim), Math.max(0, amount));
        if (!(taken > 1.0E-9)) {
            return 0;
        }
        ctx().addGauge(victim, -taken);
        return taken;
    }

    // ------------------------------------------------------------------ active: pickpocket

    @Override
    public ActivationResult onActive() {
        Player owner = player();
        LivingEntity target = ctx().findTarget(owner, Math.max(1, params().num("dash_range")));
        if (target == null) {
            return ActivationResult.fail("조준한 곳에 적이 없습니다");
        }
        Location spot = Spots.around(ctx(), owner, target, 0);
        if (spot == null) {
            return ActivationResult.fail("적의 등 뒤에 설 자리가 없습니다");
        }
        Location from = owner.getLocation();
        if (!owner.teleport(spot, TeleportCause.PLUGIN)) {
            return ActivationResult.fail("이동할 수 없습니다");
        }
        owner.setFallDistance(0);
        // Slipping behind an enemy in mid-air leaves the thief falling: that landing must not hurt.
        landing.armIfAirborne(owner, ctx().currentTick(), LANDING_GRACE_TICKS);
        slipFx(owner, from, spot);

        if (!strike(target, params().num("dash_damage")) || !Combat.isTargetable(ctx(), owner, target)) {
            return ActivationResult.success();
        }
        snatchFx(owner, target);
        if (target instanceof Player victim) {
            double taken = steal(victim, params().num("dash_steal"));
            if (taken > 0) {
                ctx().addGauge(owner, taken);
                stolenLabel(victim, taken, 1.2f);
                coins(Fx.body(victim), COINS_PER_VICTIM, 1.0f);
            }
            long silence = params().ticks("dash_silence");
            if (silence > 0) {
                ctx().silence(victim, silence);
                emptyPockets(victim, silence);
            }
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">소매치기</" + COLOR_TAG + "> <" + GOLD_TAG + ">+"
                    + AbilityInfo.formatNumber(taken) + "%</" + GOLD_TAG + "> <gray>· <victim> "
                    + AbilityInfo.formatNumber(params().num("dash_silence")) + "초 봉인</gray>",
                    Placeholder.component("victim", victim.name())));
        }
        return ActivationResult.success();
    }

    /**
     * Deals ability damage and tells whether it landed: on-hit effects only follow a hit nothing cancelled. Without
     * damage there is nothing to dodge, so the effects follow whenever the target is still a valid enemy.
     */
    private boolean strike(LivingEntity target, double damage) {
        if (!(damage > 0)) {
            return Combat.isTargetable(ctx(), player(), target);
        }
        pendingTarget = target;
        landed = false;
        try {
            Combat.hit(ctx(), player(), target, damage);
        } finally {
            pendingTarget = null;
        }
        return landed;
    }

    /** The slip: a green-black puff where the thief was, streaks of green and gold to where it reappears. */
    private void slipFx(Player owner, Location from, Location to) {
        World world = from.getWorld();
        double height = owner.getHeight();
        Location fromBody = from.clone().add(0, height * 0.5, 0);
        Location toBody = to.clone().add(0, height * 0.5, 0);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, fromBody, Vfx.n(ctx(), 14), 0.25, 0.55, 0.25, 0, SMOKE);
        Vfx.cloud(ctx(), fromBody, Particle.SMOKE, 6, 0.2, 0.02, null, false);
        Vfx.cloud(ctx(), fromBody, Particle.WAX_ON, 4, 0.3, 0.05, null, false);
        if (world.equals(to.getWorld())) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int streaks = Vfx.n(ctx(), 9);
            int duration = Math.clamp(Math.round(from.distance(to) * 0.6), 3, 8);
            for (int i = 0; i < streaks; i++) {
                double ox = (random.nextDouble() - 0.5) * 0.5;
                double oy = (random.nextDouble() - 0.5) * height * 0.9;
                double oz = (random.nextDouble() - 0.5) * 0.5;
                Vfx.trail(fromBody.clone().add(ox, oy, oz), toBody.clone().add(ox, oy, oz),
                        i % 3 == 0 ? GOLD : PALETTE.body(), duration, false);
            }
            Vfx.line(ctx(), fromBody, toBody, 1.5, Particle.DUST_COLOR_TRANSITION, PALETTE.fade(0.7f), false);
        }
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, toBody, Vfx.n(ctx(), 8), 0.2, 0.45, 0.2, 0, SMOKE);
        // Afterimage: the thief's outline lingers where it stood, crumbling into falling smoke and a coin or two.
        Location ghost = from.clone();
        Loop.repeat(ctx(), owner, 1, 1, AFTERIMAGE_TICKS, run -> {
            double fade = 1 - (double) run / AFTERIMAGE_TICKS;
            int motes = (int) Math.max(1, Math.round(3 * fade));
            for (int i = 0; i < motes; i++) {
                Location at = ghost.clone().add(Fx.jitter(0.25f),
                        height * (0.15 + ThreadLocalRandom.current().nextDouble() * 0.8 * fade), Fx.jitter(0.25f));
                Vfx.dot(at, Particle.DUST_COLOR_TRANSITION, PALETTE.fade((float) (0.6 + 0.6 * fade)), false);
            }
            if (run % 4 == 1) {
                Vfx.dot(ghost.clone().add(Fx.jitter(0.2f), height * 0.5 * fade, Fx.jitter(0.2f)), Particle.DUST, GLINT,
                        false);
            }
            return true;
        });
        Fx.sound(from, Sound.ENTITY_PHANTOM_SWOOP, 0.8f, 1.7f + Fx.jitter(0.1f));
        Fx.sound(to, Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 1.4f + Fx.jitter(0.1f));
    }

    /**
     * The snatch: a gold swipe across the victim's back, a sweep, and the coin pouch popping out of its back and
     * flying into the thief's hand.
     */
    private void snatchFx(Player owner, LivingEntity victim) {
        Location chest = victim.getLocation().add(0, victim.getHeight() * 0.6, 0);
        World world = chest.getWorld();
        Vfx.arc(ctx(), chest, 0.75, owner.getLocation().getYaw(), 150, 12, Particle.DUST_COLOR_TRANSITION,
                PALETTE.glow(1.0f), false);
        world.spawnParticle(Particle.SWEEP_ATTACK, chest, 1, 0.1, 0.1, 0.1, 0);
        Vfx.cloud(ctx(), chest, Particle.CRIT, 8, 0.25, 0.2, null, false);
        Fx.sound(chest, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 1.4f + Fx.jitter(0.1f));
        Fx.sound(chest, Sound.ITEM_BUNDLE_REMOVE_ONE, 1.2f, 0.9f + Fx.jitter(0.1f));

        ItemDisplay pouch = Displays.item(ctx(), owner, chest, ItemStack.of(Material.BUNDLE),
                Displays.centered(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), POUCH_TICKS + 4L);
        pouch.setBillboard(Display.Billboard.CENTER);
        pouch.setTeleportDuration(1);
        Displays.animateNextTick(ctx(), owner, pouch, Displays.centered(0, 0, 0, 0.5f, 0.5f, 0.5f, 0, 0, 0), 3);
        Location start = chest.clone();
        Loop.repeat(ctx(), owner, 2, 1, POUCH_TICKS, run -> {
            if (!pouch.isValid()) {
                return false;
            }
            double t = (double) (run + 1) / POUCH_TICKS;
            Location hand = hand(owner);
            Location control = start.clone().add(hand).multiply(0.5).add(0, 1.0, 0);
            Location at = bezier(start, control, hand, t * t);
            Displays.moveTo(pouch, at);
            Vfx.dot(at, Particle.DUST, GLINT, false);
            return true;
        }, () -> {
            if (pouch.isValid()) {
                pouch.remove();
            }
            Location hand = hand(owner);
            Vfx.cloud(ctx(), hand, Particle.WAX_ON, 5, 0.15, 0.08, null, false);
            Fx.sound(hand, Sound.ITEM_BUNDLE_INSERT, 1.0f, 1.1f + Fx.jitter(0.1f));
        });
    }

    /**
     * While the robbed enemy cannot use its abilities: two crossed rings of gold, like a padlock's shackle, turn over
     * its head, and an empty-pocket rattle plays when the seal breaks.
     */
    private void emptyPockets(Player victim, long ticks) {
        Player owner = player();
        long runs = Math.max(1, ticks / 3);
        Loop.repeat(ctx(), owner, 0, 3, runs, run -> {
            if (!Combat.isTargetable(ctx(), owner, victim)) {
                return false;
            }
            Location head = victim.getLocation().add(0, victim.getHeight() + 0.3, 0);
            double spin = run * 0.6;
            int points = Vfx.n(ctx(), 8);
            for (int i = 0; i < points; i++) {
                double angle = spin + i * TAU / points;
                double c = Math.cos(angle) * 0.35;
                double s = Math.sin(angle) * 0.35;
                // One ring stands across the view, the other along it.
                Vfx.dot(head.clone().add(c, s, 0), Particle.DUST, GLINT, false);
                Vfx.dot(head.clone().add(0, c, s), Particle.DUST_COLOR_TRANSITION, PALETTE.glow(0.6f), false);
            }
            return true;
        }, () -> {
            if (Combat.isTargetable(ctx(), owner, victim)) {
                Location head = victim.getLocation().add(0, victim.getHeight() + 0.3, 0);
                Vfx.cloud(ctx(), head, Particle.WAX_ON, 4, 0.2, 0.05, null, false);
                Fx.sound(head, Sound.BLOCK_CHAIN_BREAK, 0.6f, 1.4f + Fx.jitter(0.1f));
            }
        });
    }

    // ------------------------------------------------------------------ ultimate: grand heist

    @Override
    public ActivationResult onUltimate() {
        Player owner = player();
        double radius = Math.max(1, params().num("heist_radius"));
        Location center = owner.getLocation();
        List<LivingEntity> enemies = ctx().enemiesNear(owner, center, radius);
        if (enemies.isEmpty()) {
            return ActivationResult.fail("반경 " + AbilityInfo.formatNumber(radius) + "블록 안에 적이 없습니다");
        }
        // Telegraph and action at once: the sweep marks the reach while every enemy in it is robbed.
        heistSweep(owner, radius);
        double amount = Math.max(0, params().num("heist_steal"));
        double total = 0;
        int robbed = 0;
        int coinBudget = MAX_COINS;
        for (LivingEntity enemy : enemies) {
            if (!(enemy instanceof Player victim)) {
                continue;
            }
            double taken = steal(victim, amount);
            Location body = Fx.body(victim);
            Vfx.trail(body, Fx.body(owner), GOLD, 10, true);
            Vfx.cloud(ctx(), body, Particle.WAX_ON, 6, 0.3, 0.1, null, true);
            if (taken <= 0) {
                continue;
            }
            robbed++;
            total += taken;
            if (robbed <= MAX_LABELS) {
                stolenLabel(victim, taken, 1.4f);
            }
            int coins = Math.min(COINS_PER_VICTIM, coinBudget);
            coinBudget -= coins;
            coins(body, coins, 1.1f);
            Fx.sound(body, Sound.ITEM_BUNDLE_REMOVE_ONE, 1.0f, 0.8f + Fx.jitter(0.1f));
        }
        vaultRing(owner);

        // The loot lands and the thief is gone. (The framework empties the gauge right after this method, so the
        // thief's share is paid with the landing loot.)
        double share = total * Math.max(0, params().num("heist_share")) / 100.0;
        int count = robbed;
        double stolen = total;
        long invisible = Math.max(1, params().ticks("heist_invis"));
        ctx().runLater(owner, COIN_FLIGHT_TICKS, () -> {
            if (share > 0) {
                ctx().addGauge(owner, share);
            }
            owner.sendActionBar(Text.mm("<" + GOLD_TAG + ">♛ 대도</" + GOLD_TAG + "> <gray>" + count + "명에게서 "
                    + AbilityInfo.formatNumber(stolen) + "% 강탈 · 내 게이지 <white>+"
                    + AbilityInfo.formatNumber(share) + "%</white></gray>"));
            Location chest = Fx.body(owner);
            Vfx.helix(ctx(), owner.getLocation(), 0.6, owner.getHeight() * 1.1, 1.5, 2, 10, 0,
                    Particle.DUST_COLOR_TRANSITION, new Particle.DustTransition(GOLD, DEEP_GOLD, 0.9f), false);
            Fx.sound(chest, Sound.ITEM_BUNDLE_INSERT, 1.2f, 0.8f);
            Fx.sound(chest, Sound.BLOCK_VAULT_EJECT_ITEM, 1.0f, 1.2f + Fx.jitter(0.05f));
            vanish(owner, invisible);
        });
        return ActivationResult.success();
    }

    /**
     * The vault: gold ingots appear in a wide ring around the thief and spiral in, tumbling ever faster, while the loot
     * flies over; they are swallowed in a burst of glitter the moment the thief vanishes.
     */
    private void vaultRing(Player owner) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double phase = random.nextDouble(TAU);
        ItemDisplay[] ingots = new ItemDisplay[VAULT_INGOTS];
        for (int i = 0; i < ingots.length; i++) {
            Location at = vaultPoint(owner.getLocation(), phase, i, 0);
            ingots[i] = Displays.item(ctx(), owner, at, ItemStack.of(Material.GOLD_INGOT),
                    Displays.centered(0, 0, 0, 0.05f, 0.05f, 0.05f, 0, 0, 0), COIN_FLIGHT_TICKS + 6L);
            ingots[i].setTeleportDuration(1);
            Displays.animateNextTick(ctx(), owner, ingots[i], Displays.centered(0, 0, 0, 0.42f, 0.42f, 0.42f,
                    i * 60f, 30f, 0), 4);
        }
        Loop.repeat(ctx(), owner, 1, 1, COIN_FLIGHT_TICKS, run -> {
            double t = (double) (run + 1) / COIN_FLIGHT_TICKS;
            Location base = owner.getLocation();
            for (int i = 0; i < ingots.length; i++) {
                ItemDisplay ingot = ingots[i];
                if (ingot == null || !ingot.isValid()) {
                    continue;
                }
                Location at = vaultPoint(base, phase, i, t);
                // The point carries the thief's yaw and pitch: teleporting there would tilt every ingot with the
                // thief's view on top of its own spin and tilt.
                Displays.moveTo(ingot, at);
                if (run >= 4) {
                    float size = (float) (0.42 * (1 - 0.5 * t));
                    Displays.animate(ingot, Displays.centered(0, 0, 0, size, size, size, i * 60f + run * 35f * (1 + (float) t),
                            30f, 0), 1);
                }
                if (run % 2 == 0) {
                    Vfx.dot(at, Particle.DUST, GLINT, true);
                }
            }
            return true;
        }, () -> {
            for (ItemDisplay ingot : ingots) {
                if (ingot != null && ingot.isValid()) {
                    ingot.remove();
                }
            }
            Location chest = Fx.body(owner);
            Vfx.dot(chest, Particle.FLASH, GOLD, true);
            Vfx.sphere(ctx(), chest, 0.6, 16, Particle.DUST_COLOR_TRANSITION,
                    new Particle.DustTransition(GOLD, DEEP_GOLD, 1.0f), true);
            Fx.sound(chest, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 0.7f);
        });
    }

    /** Ingot {@code index} of the vault ring at progress {@code t} (0..1): spiralling in and up to the chest. */
    private static Location vaultPoint(Location base, double phase, int index, double t) {
        double angle = phase + index * TAU / VAULT_INGOTS + t * TAU * 1.25;
        double radius = VAULT_RADIUS * (1 - t) + 0.15;
        return base.clone().add(Math.cos(angle) * radius, 0.6 + 0.6 * t, Math.sin(angle) * radius);
    }

    /** The heist: a gold ring sweeps out to the radius over a dark green one, the vault-lock clack and a chime. */
    private void heistSweep(Player owner, double radius) {
        Location feet = owner.getLocation().add(0, 0.12, 0);
        Vfx.shockwave(ctx(), owner, feet, 0.5, radius, HEIST_SWEEP_TICKS, Particle.DUST_COLOR_TRANSITION,
                new Particle.DustTransition(GOLD, DEEP_GOLD, 1.4f), true);
        Vfx.shockwave(ctx(), owner, feet.clone().add(0, 0.08, 0), 0.3, radius * 0.92, HEIST_SWEEP_TICKS + 2,
                Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.2f), true);
        Vfx.dot(feet.clone().add(0, 1.0, 0), Particle.FLASH, GOLD, true);
        Fx.sound(feet, Sound.BLOCK_VAULT_OPEN_SHUTTER, 1.4f, 0.9f);
        Fx.sound(feet, Sound.BLOCK_CHEST_LOCKED, 1.0f, 0.7f);
        Fx.sound(feet, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 0.8f, 1.4f);
    }

    // ------------------------------------------------------------------ stealth

    /** The thief melts into green-black smoke and stays hidden from enemies (allies see it translucent). */
    private void vanish(Player owner, long ticks) {
        int effectTicks = (int) Math.min(Integer.MAX_VALUE, ticks);
        owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, effectTicks + 5, 0, false, false, true));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, effectTicks, 0, false, false, true));
        stealth.begin(ctx(), owner, ticks, () -> reveal(Reveal.EXPIRED));
        Stealth.dropAggro(ctx(), owner, AGGRO_DROP_RANGE);

        Location feet = owner.getLocation().add(0, 0.05, 0);
        Location body = feet.clone().add(0, owner.getHeight() * 0.5, 0);
        World world = body.getWorld();
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 24), 0.4, 0.7, 0.4, 0, SMOKE, true);
        Vfx.cloud(ctx(), body, Particle.LARGE_SMOKE, 10, 0.3, 0.02, null, true);
        Vfx.cloud(ctx(), body, Particle.WAX_ON, 8, 0.4, 0.06, null, true);
        Vfx.helix(ctx(), feet, 0.55, owner.getHeight() * 1.05, 1.25, 2, 10, ThreadLocalRandom.current().nextDouble(TAU),
                Particle.DUST_COLOR_TRANSITION, PALETTE.fade(1.0f), true);
        Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 1.3f + Fx.jitter(0.05f));
        Fx.sound(body, Sound.ENTITY_PHANTOM_FLAP, 0.8f, 1.4f + Fx.jitter(0.1f));
    }

    @Override
    public void onTick(long tick) {
        Player owner = player();
        landing.update(owner, tick);
        if (!stealth.isActive()) {
            return;
        }
        if (tick % 5 == 0) {
            owner.sendActionBar(Text.mm("<" + COLOR_TAG + ">♛ 대도 은신</" + COLOR_TAG + "> <gray>"
                    + Fx.seconds(stealth.ticksLeft(ctx().currentTick())) + "초 · 첫 근접 공격은 기습 강탈</gray>"));
        }
        if (tick % 20 == 0) {
            stealth.hideFromEnemies(ctx(), owner);
            Stealth.dropAggro(ctx(), owner, AGGRO_DROP_RANGE);
        }
        if (tick % GLINT_PERIOD == 0) {
            // The only tell: now and then a stolen coin glints at the thief's feet.
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Vfx.dot(owner.getLocation().add(random.nextGaussian() * 0.2, 0.1, random.nextGaussian() * 0.2),
                    Particle.DUST, GLINT, false);
        }
    }

    /** Ends the stealth (if any), showing the owner to everyone it was hidden from. */
    private void reveal(Reveal reason) {
        Player owner = player();
        if (!stealth.end(ctx(), owner) || !owner.isOnline()) {
            return;
        }
        removeOwnEffect(owner, PotionEffectType.INVISIBILITY);
        removeOwnEffect(owner, PotionEffectType.SPEED);
        if (reason == Reveal.REVOKE) {
            return;
        }
        Location body = Fx.body(owner);
        body.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, body, Vfx.n(ctx(), 12), 0.3, 0.6, 0.3, 0, SMOKE);
        Vfx.cloud(ctx(), body, Particle.SMOKE, 6, 0.25, 0.02, null, false);
        Fx.sound(body, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.7f, 1.6f + Fx.jitter(0.1f));
        if (reason == Reveal.EXPIRED) {
            owner.sendActionBar(Text.mm("<gray>은신이 끝났습니다</gray>"));
        }
    }

    /** Removes our level-I effect; a stronger effect from another source is left alone. */
    private static void removeOwnEffect(Player owner, PotionEffectType type) {
        PotionEffect effect = owner.getPotionEffect(type);
        if (effect != null && effect.getAmplifier() == 0) {
            owner.removePotionEffect(type);
        }
    }

    // ------------------------------------------------------------------ loot visuals

    /**
     * {@code count} spinning gold nuggets leaping from {@code from} in arcs into the thief's hand, glittering on the
     * way; each one chimes higher as it lands.
     */
    private void coins(Location from, int count, float size) {
        Player owner = player();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            float scale = 0.32f * size;
            Location start = from.clone().add(random.nextGaussian() * 0.15, random.nextDouble() * 0.3,
                    random.nextGaussian() * 0.15);
            ItemDisplay coin = Displays.item(ctx(), owner, start, ItemStack.of(Material.GOLD_NUGGET),
                    Displays.centered(0, 0, 0, scale, scale, scale, 0, 0, 0), COIN_FLIGHT_TICKS + 6L + i * 2L);
            coin.setTeleportDuration(1);
            double lift = 1.2 + random.nextDouble() * 0.9;
            Vector sway = new Vector(random.nextGaussian(), 0, random.nextGaussian()).multiply(0.6);
            int index = i;
            Loop.repeat(ctx(), owner, 1 + i * 2L, 1, COIN_FLIGHT_TICKS, run -> {
                if (!coin.isValid() || !owner.getWorld().equals(start.getWorld())) {
                    return false;
                }
                double t = (double) (run + 1) / COIN_FLIGHT_TICKS;
                Location hand = hand(owner);
                Location control = start.clone().add(hand).multiply(0.5).add(sway).add(0, lift, 0);
                Location at = bezier(start, control, hand, t);
                Displays.moveTo(coin, at);
                Displays.animate(coin, Displays.centered(0, 0, 0, scale, scale, scale, run * 40f, 0, 0), 1);
                Vfx.dot(at, Particle.DUST, GLINT, false);
                if (run % 3 == 0) {
                    Vfx.dot(at, Particle.WAX_ON, null, false);
                }
                return true;
            }, () -> {
                if (coin.isValid()) {
                    coin.remove();
                }
                Location hand = hand(owner);
                Vfx.dot(hand, Particle.WAX_ON, null, false);
                Fx.sound(hand, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f + index * 0.15f + Fx.jitter(0.05f));
            });
        }
    }

    /** A gold "-N%" popping up over a robbed enemy, floating up and fading. */
    private void stolenLabel(LivingEntity victim, double taken, float scale) {
        Player owner = player();
        Location above = victim.getLocation().add(0, victim.getHeight() + 0.55, 0);
        TextDisplay label = Displays.text(ctx(), owner, above,
                Text.mm("<b><gradient:#FFE082:#FFB300>-" + AbilityInfo.formatNumber(Math.round(taken))
                        + "%</gradient></b>"), 0.2f * scale, LABEL_TICKS);
        float pop = 1.2f * scale;
        float settle = 0.95f * scale;
        float sway = Fx.jitter(0.2f);
        Displays.animateNextTick(ctx(), owner, label, Displays.centered(0, 0.25f, 0, pop, pop, pop, 0, 0, 0), 4);
        ctx().runLater(owner, 5, () -> {
            if (label.isValid()) {
                Displays.animate(label, Displays.centered(sway, 0.85f, 0, settle, settle, settle, 0, 0, sway * 20),
                        LABEL_TICKS - 11);
            }
        });
        ctx().runLater(owner, LABEL_TICKS - 5L, () -> {
            if (label.isValid()) {
                Displays.shrinkAway(ctx(), owner, label, 4);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Just in front of the thief's hand, where the loot lands. */
    private static Location hand(Player owner) {
        Location eye = owner.getEyeLocation();
        Vector side = new Vector(-eye.getDirection().getZ(), 0, eye.getDirection().getX());
        if (side.lengthSquared() > 1.0E-6) {
            side.normalize().multiply(-0.3);
        }
        return eye.add(Motion.horizontal(eye.getDirection()).multiply(0.4)).add(side).subtract(0, 0.55, 0);
    }

    /** A point on the quadratic Bézier curve from {@code a} over {@code control} to {@code b}. */
    private static Location bezier(Location a, Location control, Location b, double t) {
        double u = 1 - t;
        return new Location(a.getWorld(),
                u * u * a.getX() + 2 * u * t * control.getX() + t * t * b.getX(),
                u * u * a.getY() + 2 * u * t * control.getY() + t * t * b.getY(),
                u * u * a.getZ() + 2 * u * t * control.getZ() + t * t * b.getZ());
    }
}
