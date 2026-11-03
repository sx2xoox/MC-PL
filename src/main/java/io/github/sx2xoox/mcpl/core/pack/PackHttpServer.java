package io.github.sx2xoox.mcpl.core.pack;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A tiny HTTP server that serves the bundled resource pack at one path ({@code resource-pack.host-port}). It runs on
 * its own daemon threads and only hands out bytes it was given: it never touches Bukkit.
 */
final class PackHttpServer {

    private final HttpServer server;
    private final ExecutorService executor;

    private PackHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /** Starts serving {@code zip} at {@code /<path>} on every interface, port {@code port}. */
    static PackHttpServer start(int port, String path, byte[] zip) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "MCPL-pack-http");
            thread.setDaemon(true);
            return thread;
        });
        String wanted = "/" + path;
        server.createContext("/", exchange -> handle(exchange, wanted, zip));
        server.setExecutor(executor);
        server.start();
        return new PackHttpServer(server, executor);
    }

    private static void handle(HttpExchange exchange, String wanted, byte[] zip) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            boolean head = method.equals("HEAD");
            if (!exchange.getRequestURI().getPath().equals(wanted) || !(head || method.equals("GET"))) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, head ? -1 : zip.length);
            if (!head) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(zip);
                }
            }
        }
    }

    int port() {
        return server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }
}
