package io.github.sx2xoox.mcpl.core.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

/** The pack shares the game port: our GET gets the pack, every other byte reaches the next handlers untouched. */
class SamePortHostTest {

    private static final byte[] ZIP = "PK-fake-zip-content".getBytes(StandardCharsets.US_ASCII);
    private static final String PATH = SamePortHost.PREFIX + "abc.zip";

    /** Stands for whatever comes next (another plugin's sniffer, the Minecraft handlers): collects what reaches it. */
    private static final class Next extends ChannelInboundHandlerAdapter {
        final ByteArrayOutputStream got = new ByteArrayOutputStream();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            buf.release();
            got.writeBytes(bytes);
        }
    }

    private Next next = new Next();

    private EmbeddedChannel channel() {
        next = new Next();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("timeout", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("next", next);
        channel.pipeline().addFirst(SamePortHost.HANDLER,
                new SamePortHost.Sniffer(() -> new SamePortHost.Served(PATH, ZIP)));
        return channel;
    }

    private static ByteBuf bytes(String text) {
        return Unpooled.wrappedBuffer(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String response(EmbeddedChannel channel) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuf buf;
        while ((buf = channel.readOutbound()) != null) {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            buf.release();
            out.writeBytes(bytes);
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void servesOurPathAndCloses() {
        EmbeddedChannel channel = channel();
        channel.writeInbound(bytes("GET " + PATH + " HTTP/1.1\r\nHost: 127.0.0.1:25599\r\nUser-Agent: Minecraft\r\n\r\n"));
        String answer = response(channel);
        assertTrue(answer.startsWith("HTTP/1.1 200 OK\r\n"), answer);
        assertTrue(answer.contains("Content-Type: application/zip\r\n"));
        assertTrue(answer.contains("Content-Length: " + ZIP.length + "\r\n"));
        assertTrue(answer.endsWith("\r\n\r\n" + new String(ZIP, StandardCharsets.ISO_8859_1)));
        assertFalse(channel.isOpen(), "closed after the answer");
        assertEquals(0, next.got.size(), "nobody after us sees our request");
        assertNull(channel.pipeline().get("timeout"), "a download may take longer than the game's read timeout");
    }

    @Test
    void anOldPackUnderOurPrefixIsNotFound() {
        EmbeddedChannel channel = channel();
        channel.writeInbound(bytes("GET " + SamePortHost.PREFIX + "old.zip HTTP/1.1\r\n\r\n"));
        assertTrue(response(channel).startsWith("HTTP/1.1 404 Not Found"));
        assertFalse(channel.isOpen());
    }

    @Test
    void requestSplitAcrossReads() {
        EmbeddedChannel channel = channel();
        channel.writeInbound(bytes("G"));
        channel.writeInbound(bytes("ET " + SamePortHost.PREFIX));
        channel.writeInbound(bytes("abc.zip HT"));
        assertTrue(response(channel).isEmpty());
        assertEquals(0, next.got.size());
        channel.writeInbound(bytes("TP/1.1\r\nHost: x\r\n"));
        assertTrue(response(channel).isEmpty(), "waits for the end of the headers");
        channel.writeInbound(bytes("\r\n"));
        assertTrue(response(channel).startsWith("HTTP/1.1 200 OK"));
    }

    /** Another plugin's pack (e.g. STSV's /pack/...) on the same port: handed on byte for byte, we step out. */
    @Test
    void otherHttpRequestsArePassedOnUntouched() {
        EmbeddedChannel channel = channel();
        String request = "GET /pack/abc.zip HTTP/1.1\r\nHost: x\r\n\r\n";
        channel.writeInbound(bytes("GET /pa"));
        assertEquals(0, next.got.size(), "waits for the request line");
        channel.writeInbound(bytes(request.substring(7)));
        assertEquals(request, next.got.toString(StandardCharsets.ISO_8859_1));
        assertNull(channel.pipeline().get(SamePortHost.HANDLER));
        assertNotNull(channel.pipeline().get("timeout"), "the game's timeout is left alone");
        assertTrue(response(channel).isEmpty());
        assertTrue(channel.isOpen());
    }

    /** Handshake: packet length, packet id 0, protocol, host, port, intent. Must arrive byte for byte. */
    @Test
    void minecraftHandshakePassesThroughUntouched() {
        EmbeddedChannel channel = channel();
        byte[] handshake = {0x10, 0x00, (byte) 0xF3, 0x05, 0x09, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't', 0x63,
                (byte) 0xDD, 0x02};
        byte[] login = {0x05, 0x00, 0x01, 0x02, 0x03, 0x04};
        channel.writeInbound(Unpooled.wrappedBuffer(handshake));
        channel.writeInbound(Unpooled.wrappedBuffer(login));
        byte[] expected = new byte[handshake.length + login.length];
        System.arraycopy(handshake, 0, expected, 0, handshake.length);
        System.arraycopy(login, 0, expected, handshake.length, login.length);
        assertArrayEquals(expected, next.got.toByteArray());
        assertNull(channel.pipeline().get(SamePortHost.HANDLER), "steps out after the first bytes");
        assertTrue(channel.isOpen());
        assertTrue(response(channel).isEmpty());
    }

    /** A packet length of 71 is the byte 'G'; the packet id after it is 0, never 'E'. */
    @Test
    void aHandshakeStartingWithTheLetterG() {
        EmbeddedChannel channel = channel();
        channel.writeInbound(bytes("G"));
        assertEquals(0, next.got.size(), "waits: could still be GET");
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {0x00, 0x01, 0x02}));
        assertArrayEquals(new byte[] {'G', 0x00, 0x01, 0x02}, next.got.toByteArray());
        assertTrue(channel.isOpen());
    }

    @Test
    void oversizedRequestOfOursIsDropped() {
        EmbeddedChannel channel = channel();
        channel.writeInbound(bytes("GET " + PATH + " HTTP/1.1\r\n" + "X: " + "a".repeat(9000)));
        assertFalse(channel.isOpen());
        assertEquals(0, next.got.size());
    }
}
