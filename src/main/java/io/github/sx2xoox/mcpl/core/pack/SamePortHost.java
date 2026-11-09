package io.github.sx2xoox.mcpl.core.pack;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;

/**
 * Serves the resource pack on the Minecraft port itself ({@code resource-pack.mode: same-port}), so no second port has
 * to be opened.
 *
 * <p>Paper calls every channel initialize listener ({@code io.papermc.paper.network.ChannelInitializeListenerHolder},
 * internal, hence reflection) once a new connection's pipeline is set up. Each connection gets a {@link Sniffer} at the
 * very front of its pipeline that looks at the first bytes: an HTTP {@code GET} of a path under {@link #PREFIX} is
 * answered with the pack and the connection closed; anything else (a Minecraft client starts with a packet length and
 * packet id 0, never {@code "GET "}; or another plugin's HTTP request, e.g. another pack server on the same port) is
 * handed on byte for byte and the sniffer removes itself. Being first and answering only its own prefix lets it share
 * the port with other plugins doing the same.
 *
 * <p>Does not work behind something that only lets Minecraft traffic through (Velocity, BungeeCord, TCPShield):
 * {@code separate-port} then.
 */
final class SamePortHost {

    /** URL path prefix of the MCPL pack on the game port. */
    static final String PREFIX = "/mcpl-pack/";

    static final String HANDLER = "mcpl_pack_http";
    private static final Key KEY = Key.key("mcpl", "pack_http");
    private static final byte[] GET = "GET ".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_REQUEST = 8192;
    private static final int DOWNLOAD_SECONDS = 120;

    /** What is served: {@code zip} at {@code path} (starts with {@link #PREFIX}). */
    record Served(String path, byte[] zip) {
    }

    private final Supplier<@Nullable Served> served;
    private final Logger log;
    private final Class<?> holder;
    private final Class<?> listenerType;

    /** @throws ReflectiveOperationException if this server has no channel initialize listeners */
    SamePortHost(Supplier<@Nullable Served> served, Logger log) throws ReflectiveOperationException {
        this.served = served;
        this.log = log;
        ClassLoader loader = Bukkit.getServer().getClass().getClassLoader();
        holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder", true, loader);
        listenerType = Class.forName("io.papermc.paper.network.ChannelInitializeListener", true, loader);
    }

    void install() throws ReflectiveOperationException {
        Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[] {listenerType},
                (proxy, method, args) -> switch (method.getName()) {
                    case "afterInitChannel" -> {
                        attach((Channel) args[0]);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "MCPL resource pack on the game port";
                    default -> null;
                });
        Method remove = holder.getMethod("removeListener", Key.class);
        remove.invoke(null, KEY); // a reload may have left the previous one behind
        holder.getMethod("addListener", Key.class, listenerType).invoke(null, KEY, listener);
    }

    void uninstall() {
        try {
            holder.getMethod("removeListener", Key.class).invoke(null, KEY);
        } catch (ReflectiveOperationException e) {
            log.log(Level.FINE, "Could not remove the pack channel listener", e);
        }
    }

    private void attach(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(HANDLER) == null) {
            pipeline.addFirst(HANDLER, new Sniffer(served)); // before everything, other plugins' sniffers included
        }
    }

    /** Per connection: a GET of our pack gets the pack; any other bytes go on to the next handlers untouched. */
    static final class Sniffer extends ChannelInboundHandlerAdapter {
        private final Supplier<@Nullable Served> served;
        private @Nullable ByteBuf buffered;
        private boolean ours;
        private boolean answered;

        Sniffer(Supplier<@Nullable Served> served) {
            this.served = served;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf in)) {
                ctx.fireChannelRead(msg);
                return;
            }
            if (answered) { // the client keeps talking after the answer: the connection is closing
                in.release();
                return;
            }
            ByteBuf buffer = buffered;
            if (buffer == null) {
                buffer = ctx.alloc().buffer();
                buffered = buffer;
            }
            buffer.writeBytes(in);
            in.release();
            if (!ours) {
                int n = Math.min(buffer.readableBytes(), GET.length);
                for (int i = 0; i < n; i++) {
                    if (buffer.getByte(buffer.readerIndex() + i) != GET[i]) {
                        passOn(ctx);
                        return;
                    }
                }
                String text = buffer.toString(StandardCharsets.ISO_8859_1);
                int lineEnd = text.indexOf("\r\n");
                if (lineEnd < 0) {
                    if (buffer.readableBytes() > MAX_REQUEST) {
                        passOn(ctx); // not a request line we would answer
                    }
                    return; // could still be our GET: wait for the request line
                }
                if (!path(text.substring(0, lineEnd)).startsWith(PREFIX)) {
                    passOn(ctx); // someone else's HTTP request (another plugin's pack server)
                    return;
                }
                ours = true;
                // a download may take longer than the game's read timeout allows; use our own limit
                if (ctx.pipeline().get("timeout") != null) {
                    ctx.pipeline().remove("timeout");
                }
                ctx.executor().schedule(() -> ctx.close(), DOWNLOAD_SECONDS, TimeUnit.SECONDS);
            }
            String request = buffer.toString(StandardCharsets.ISO_8859_1);
            if (!request.contains("\r\n\r\n")) {
                if (buffer.readableBytes() > MAX_REQUEST) {
                    ctx.close();
                }
                return;
            }
            String path = path(request.substring(0, request.indexOf("\r\n")));
            buffered = null;
            buffer.release();
            answered = true;
            respond(ctx, path);
        }

        private static String path(String requestLine) {
            String[] parts = requestLine.split(" ");
            return parts.length >= 2 ? parts[1] : "";
        }

        /** Not ours: give the bytes to the next handlers exactly as they came and step out of the way. */
        private void passOn(ChannelHandlerContext ctx) {
            ByteBuf data = buffered;
            buffered = null;
            if (data != null) {
                ctx.fireChannelRead(data);
            }
            ctx.pipeline().remove(this);
        }

        private void respond(ChannelHandlerContext ctx, String path) {
            Served pack = served.get();
            byte[] body;
            String status;
            String type;
            if (pack != null && path.equals(pack.path())) {
                body = pack.zip();
                status = "200 OK";
                type = "application/zip";
            } else {
                body = "Not Found".getBytes(StandardCharsets.US_ASCII);
                status = "404 Not Found";
                type = "text/plain";
            }
            byte[] head = ("HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            ctx.writeAndFlush(Unpooled.wrappedBuffer(head, body)).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            ByteBuf buffer = buffered;
            if (buffer != null) {
                buffered = null;
                buffer.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (ours) {
                ctx.close();
            } else {
                ctx.fireExceptionCaught(cause);
            }
        }
    }
}
