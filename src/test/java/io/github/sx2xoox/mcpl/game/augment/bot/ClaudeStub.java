package io.github.sx2xoox.mcpl.game.augment.bot;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

/**
 * A local stand-in for the Messages API (no real API is ever called in tests): records every request (body and
 * headers) and answers what the test's responder says, after an optional delay.
 */
final class ClaudeStub implements AutoCloseable {

    /** One recorded request. */
    record Seen(JsonObject body, Map<String, List<String>> headers) {

        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().getFirst();
                }
            }
            return null;
        }

        /** The text of the (only) user message. */
        String user() {
            var messages = body.getAsJsonArray("messages");
            var last = messages.get(messages.size() - 1).getAsJsonObject();
            var content = last.get("content");
            if (content.isJsonPrimitive()) {
                return content.getAsString();
            }
            StringBuilder b = new StringBuilder();
            content.getAsJsonArray().forEach(part -> b.append(part.getAsJsonObject().get("text").getAsString()));
            return b.toString();
        }
    }

    /** An answer: HTTP status, body, and how long to wait before sending it. */
    record Answer(int status, String body, long delayMillis) {
    }

    private final HttpServer server;
    final List<Seen> requests = new CopyOnWriteArrayList<>();
    volatile Function<Seen, Answer> responder = seen -> ok(decision("AUTO", "", "AUTO", "", "", ""));

    ClaudeStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "claude-stub");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", exchange -> {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Seen seen = new Seen(JsonParser.parseString(raw).getAsJsonObject(),
                    Map.copyOf(exchange.getRequestHeaders()));
            requests.add(seen);
            Answer answer = responder.apply(seen);
            if (answer.delayMillis() > 0) {
                try {
                    Thread.sleep(answer.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            try {
                exchange.sendResponseHeaders(answer.status(), bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (IOException e) {
                // the client gave up (timeout, cancelled)
            }
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The JSON text of an answer the schema allows. */
    static String decision(String goal, String target, String range, String augment, String notes, String chat) {
        JsonObject o = new JsonObject();
        o.addProperty("goal", goal);
        o.addProperty("target", target);
        o.addProperty("range", range);
        o.addProperty("augment", augment);
        o.addProperty("notes", notes);
        o.addProperty("chat", chat);
        o.addProperty("reason", "stub");
        return o.toString();
    }

    static Answer ok(String text) {
        return message("end_turn", text, 0);
    }

    /** A Messages API response with {@code text} as its only text block. */
    static Answer message(String stopReason, String text, long delayMillis) {
        JsonObject usage = new JsonObject();
        usage.addProperty("input_tokens", 300);
        usage.addProperty("output_tokens", 120);
        usage.addProperty("cache_read_input_tokens", 2000);
        usage.addProperty("cache_creation_input_tokens", 0);
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        var content = new com.google.gson.JsonArray();
        content.add(block);
        JsonObject m = new JsonObject();
        m.addProperty("id", "msg_stub");
        m.addProperty("type", "message");
        m.addProperty("role", "assistant");
        m.addProperty("model", "claude-opus-5-5");
        m.add("content", content);
        m.addProperty("stop_reason", stopReason);
        m.add("stop_sequence", null);
        m.add("usage", usage);
        return new Answer(200, new Gson().toJson(m), delayMillis);
    }

    static Answer error(int status, String type) {
        return new Answer(status, "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"stub\"}}", 0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
