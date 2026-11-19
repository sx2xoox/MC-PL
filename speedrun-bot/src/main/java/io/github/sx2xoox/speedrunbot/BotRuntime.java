package io.github.sx2xoox.speedrunbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.sx2xoox.speedrunbot.brain.RunBrain;
import io.github.sx2xoox.speedrunbot.core.Igt;
import io.github.sx2xoox.speedrunbot.core.Params;
import io.github.sx2xoox.speedrunbot.core.PerceptionRules;
import io.github.sx2xoox.speedrunbot.server.FakePlayer;
import io.github.sx2xoox.speedrunbot.server.ServerBot;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs one bot run per server start, configured by system properties (set by tools/run_batch.py):
 * <ul>
 *   <li>{@code srb.enabled} (true) - spawn the bot on the first tick</li>
 *   <li>{@code srb.params} - params.json path</li>
 *   <li>{@code srb.out} - result file (one JSON object appended per run)</li>
 *   <li>{@code srb.trace} - optional trace log path</li>
 *   <li>{@code srb.timeoutTicks} (12000 = 10 IGT minutes)</li>
 *   <li>{@code srb.fair} (true) - fair perception; false = relaxed comparison mode</li>
 *   <li>{@code srb.warp} (true) - no sleeping between ticks; {@code srb.warpBudgetMs} (0) - extra wall time per tick for
 *   queued tasks (queued chunk work still runs at the start of the next tick, so 0 changes no tick)</li>
 *   <li>{@code srb.renderChunks} (10), {@code srb.rays} (220 rays per tick)</li>
 *   <li>{@code srb.exit} (true) - halt the JVM after writing the result (the world is thrown away anyway)</li>
 * </ul>
 */
public final class BotRuntime {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("srb.enabled", "true"));
    private static final boolean WARP = Boolean.parseBoolean(System.getProperty("srb.warp", "true"));
    private static final long WARP_BUDGET_MS = Long.getLong("srb.warpBudgetMs", 0L);

    private static ServerBot bot;
    private static RunBrain brain;
    private static long wallStart;
    private static long startTick;
    private static long tickNanosSum;
    private static long tickNanosMax;
    private static long ticksMeasured;
    private static long lastTickStart;
    private static boolean done;

    private BotRuntime() {
    }

    public static boolean warp() {
        return WARP && brain != null && !done;
    }

    public static long warpTaskBudgetMs() {
        return WARP_BUDGET_MS;
    }

    public static void onServerTick(MinecraftServer server) {
        if (!ENABLED || done) {
            return;
        }
        long now = System.nanoTime();
        if (brain == null) {
            start(server);
            lastTickStart = now;
            return;
        }
        // MSPT: the vanilla per-tick processing time of the last tick
        long last = server.lastTickLengths[(server.getTicks() + 99) % 100];
        tickNanosSum += last;
        tickNanosMax = Math.max(tickNanosMax, last);
        ticksMeasured++;
        try {
            bot.player().networkTick();
        } catch (RuntimeException e) {
            e.printStackTrace();
            finish(server, "exception:" + e.getClass().getSimpleName());
            return;
        }
        bot.newTick();
        try {
            brain.tick();
        } catch (RuntimeException e) {
            e.printStackTrace();
            finish(server, "exception:" + e.getClass().getSimpleName());
            return;
        }
        long timeout = Long.getLong("srb.timeoutTicks", 12000L);
        if (brain.finished()) {
            String f = brain.failure();
            if ("death".equals(f) && bot.player().deathSource() != null) {
                f = "death_" + bot.player().deathSource().getName();
            }
            finish(server, f);
        } else if (brain.igt().elapsedTicks(server.getTicks()) >= timeout) {
            finish(server, "timeout_" + brain.goal());
        }
    }

    private static void start(MinecraftServer server) {
        server.getCommandManager().execute(server.getCommandSource().withSilent(), "gamerule spawnRadius 0");
        FakePlayer p = FakePlayer.join(server, "SpeedrunBot");
        bot = new ServerBot(server, p);
        if (Boolean.getBoolean("srb.probe")) {
            String probe = DevSetup.probe(server, p.getBlockPos());
            System.out.println("[speedrunbot] probe " + probe);
            try {
                String out = System.getProperty("srb.out");
                if (out != null) {
                    Files.write(Paths.get(out), ("{\"seed\":\"" + server.getOverworld().getSeed() + "\",\"probe\":" + probe + "}\n").getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.flush();
            Runtime.getRuntime().halt(0);
        }
        DevSetup.apply(server, p);
        Params params;
        try {
            String path = System.getProperty("srb.params");
            params = path == null ? Params.defaults() : Params.load(Paths.get(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        boolean fair = Boolean.parseBoolean(System.getProperty("srb.fair", "true"));
        PerceptionRules rules = new PerceptionRules(fair, Integer.getInteger("srb.renderChunks", 10), 160);
        brain = new RunBrain(bot, bot, params, rules, Integer.getInteger("srb.rays", 220));
        wallStart = System.nanoTime();
        startTick = server.getTicks();
        System.out.println("[speedrunbot] run started at tick " + startTick + " seed " + server.getOverworld().getSeed());
    }

    private static void finish(MinecraftServer server, String failure) {
        done = true;
        Igt igt = brain.igt();
        long ticks = igt.elapsedTicks(server.getTicks());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("seed", Long.toString(server.getOverworld().getSeed()));
        r.put("label", System.getProperty("srb.label", ""));
        r.put("success", failure == null);
        r.put("reason", failure == null ? "nether" : failure);
        r.put("route", brain.route().name());
        r.put("route_ranking", brain.routeNote());
        r.put("igt_ticks", failure == null ? igt.splitTicks("nether") : ticks);
        r.put("igt", Igt.format(failure == null ? igt.splitTicks("nether") : ticks));
        Map<String, Long> splits = new LinkedHashMap<>(igt.splits());
        r.put("splits", splits);
        r.put("last_goal", brain.goal().name());
        r.put("mspt_avg", ticksMeasured == 0 ? 0 : tickNanosSum / 1e6 / ticksMeasured);
        r.put("mspt_max", tickNanosMax / 1e6);
        r.put("wall_s", (System.nanoTime() - wallStart) / 1e9);
        r.put("fair", Boolean.parseBoolean(System.getProperty("srb.fair", "true")));
        r.put("memory_sections", brain.memory().sectionCount());
        if (DevSetup.active()) {
            r.put("dev", DevSetup.describe());
        }
        Gson g = new GsonBuilder().create();
        String json = g.toJson(r);
        System.out.println("[speedrunbot] result " + json);
        try {
            String out = System.getProperty("srb.out");
            if (out != null) {
                Path p = Paths.get(out);
                try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(json);
                    w.write('\n');
                }
            }
            String trace = System.getProperty("srb.trace");
            if (trace != null) {
                Files.write(Paths.get(trace), brain.traceLines(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (Boolean.parseBoolean(System.getProperty("srb.exit", "true"))) {
            System.out.flush();
            Runtime.getRuntime().halt(0);
        } else {
            server.stop(false);
        }
    }
}
