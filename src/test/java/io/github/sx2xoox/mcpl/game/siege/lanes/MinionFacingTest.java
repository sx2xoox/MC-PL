package io.github.sx2xoox.mcpl.game.siege.lanes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.pack.ItemDisplayModels;
import io.github.sx2xoox.mcpl.core.pack.PackAudience;
import io.github.sx2xoox.mcpl.game.siege.creature.CreatureMover;
import io.github.sx2xoox.mcpl.game.siege.creature.ModelCreatureLook;
import io.github.sx2xoox.mcpl.game.siege.minion.MinionWaves;
import org.bukkit.Location;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Minions look where they walk: the vanilla body's yaw and, as the client draws it, the front of every part of the
 * pack's toy model point along each tick's movement, for both sides on both lanes, walked by the stepping mover (a
 * server without a pathfinder) and by a mover that moves like the server's pathfinder (the mob's move control turns
 * it towards the next node, at most 90° a tick, and it walks forward along its yaw).
 */
class MinionFacingTest extends LaneTestBase {

    /** Moves per tick worth checking (blocks); a standing or shoved minion is not checked. */
    private static final double MOVING = 0.05;

    private final Map<UUID, ModelCreatureLook.ModelCreature> models = new HashMap<>();

    @Override
    void configure(MemoryConfiguration config) {
        config.set("jungle.enabled", false);
        config.set("minions.first-wave-seconds", 0.05);
        config.set("gold.per-second", 0);
    }

    @Test
    void steppedMinionsFaceWhereTheyWalk() {
        walkAndCheck();
    }

    @Test
    void pathfindingMinionsFaceWhereTheyWalk() {
        mover = new MoveControlMover();
        walkAndCheck();
    }

    private void walkAndCheck() {
        PackAudience everyone = new PackAudience() {
            @Override
            public boolean hasPack(Player player) {
                return true;
            }

            @Override
            public Subscription subscribe(Consumer<Player> listener) {
                return () -> { };
            }
        };
        ModelCreatureLook look = new ModelCreatureLook(h.hub.plugin(), everyone);
        skin = (body, kind, team) -> {
            ModelCreatureLook.ModelCreature model = look.attach(body, kind, team);
            models.put(body.getUniqueId(), model);
            return model;
        };
        start(1, 1);
        Map<LaneSites.Side, Map<LaneSites.Lane, Integer>> checked = new EnumMap<>(LaneSites.Side.class);
        Map<UUID, Location> before = new HashMap<>();
        for (int tick = 0; tick < 160; tick++) {
            before.clear();
            for (MinionWaves.Minion minion : systems.minions().minions()) {
                before.put(minion.body().getUniqueId(), minion.body().getLocation());
            }
            run(1);
            for (MinionWaves.Minion minion : systems.minions().minions()) {
                Location was = before.get(minion.body().getUniqueId());
                if (was == null) {
                    continue;
                }
                Location now = minion.body().getLocation();
                Vector moved = now.toVector().subtract(was.toVector()).setY(0);
                if (moved.length() < MOVING) {
                    continue;
                }
                Vector heading = moved.clone().normalize();
                String who = minion.side() + " " + minion.lane() + " " + minion.kind() + " tick " + tick;
                Vector looks = now.getDirection().setY(0).normalize();
                assertTrue(looks.dot(heading) > 0.9, who + ": the body looks " + looks + " walking " + heading);
                ModelCreatureLook.ModelCreature model = models.get(minion.body().getUniqueId());
                for (ItemDisplay display : model.displays()) {
                    Vector3f front = ItemDisplayModels.front(display.getLocation().getYaw(),
                            display.getTransformation());
                    double dot = front.x * heading.getX() + front.z * heading.getZ();
                    assertTrue(dot > 0.9, who + ": the model looks " + front + " walking " + heading);
                }
                checked.computeIfAbsent(minion.side(), s -> new EnumMap<>(LaneSites.Lane.class))
                        .merge(minion.lane(), 1, Integer::sum);
            }
        }
        for (LaneSites.Side side : LaneSites.Side.values()) {
            for (LaneSites.Lane lane : LaneSites.Lane.values()) {
                int count = checked.getOrDefault(side, Map.of()).getOrDefault(lane, 0);
                assertTrue(count > 50, side + " " + lane + ": only " + count + " moving minion ticks checked");
            }
        }
    }

    /**
     * Walks like Paper's pathfinder on a real server: the move control turns the mob towards the goal (at most 90° a
     * tick) and the mob walks forward along its yaw.
     */
    private static final class MoveControlMover implements CreatureMover {
        private final Map<Mob, Object[]> goals = new WeakHashMap<>();

        @Override
        public void moveTo(Mob mob, Location to, double speed) {
            goals.put(mob, new Object[] {to.clone(), speed});
        }

        @Override
        public void stop(Mob mob) {
            goals.remove(mob);
        }

        @Override
        public void tick(Mob mob) {
            Object[] goal = goals.get(mob);
            if (goal == null || !mob.isValid()) {
                return;
            }
            Location from = mob.getLocation();
            Location to = (Location) goal[0];
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance < 0.1) {
                goals.remove(mob);
                return;
            }
            float wanted = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
            float turn = Math.clamp(wrap(wanted - from.getYaw()), -90f, 90f);
            float yaw = from.getYaw() + turn;
            double step = Math.min(distance, STEP_PER_TICK * (double) goal[1]);
            double rad = Math.toRadians(yaw);
            Location next = from.clone().add(-Math.sin(rad) * step, 0, Math.cos(rad) * step);
            next.setYaw(yaw);
            next.setPitch(0);
            mob.teleport(next);
        }

        private static float wrap(float degrees) {
            float out = degrees % 360;
            if (out >= 180) {
                out -= 360;
            }
            if (out < -180) {
                out += 360;
            }
            return out;
        }
    }
}
