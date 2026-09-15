package limn.backend.lwjgl.a11y.linux;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connection's own reader and writer, against a bus played by this test over a real unix socket.
 *
 * <p>No D-Bus daemon is involved: the test accepts the connection, plays the SASL server, and then
 * writes and reads whole messages the way a bus relays them. That is enough to pin what only a
 * running connection shows — what the handshake actually sends, that a message the reader cannot
 * parse does not stop it, and that a connection that ends on its own says so — and it runs on any
 * machine with unix-domain sockets, the build host included.
 */
class DBusConnectionTest {

    private Path directory;
    private ServerSocketChannel server;
    private DBus.Conn connection;

    @BeforeEach
    void listen() throws IOException {
        directory = Files.createTempDirectory("dbus");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(directory.resolve("bus")));
    }

    @AfterEach
    void close() throws IOException {
        if (connection != null) {
            connection.close();
        }
        server.close();
        Files.deleteIfExists(directory.resolve("bus"));
        Files.deleteIfExists(directory);
    }

    /** The bus's end of one client connection. */
    private static final class Peer {
        final SocketChannel channel;
        final List<String> handshake = new ArrayList<>();

        Peer(SocketChannel channel) {
            this.channel = channel;
        }

        /** Plays the SASL server: the NUL, then every line up to BEGIN, answering AUTH with OK. */
        void authenticate() throws IOException {
            ByteBuffer nul = ByteBuffer.allocate(1);
            readFully(nul);
            while (true) {
                String line = readLine();
                handshake.add(line);
                if (line.startsWith("AUTH ")) {
                    write(("OK 0123456789abcdef0123456789abcdef\r\n").getBytes(StandardCharsets.US_ASCII));
                } else if (line.startsWith("NEGOTIATE_UNIX_FD")) {
                    write("AGREE_UNIX_FD\r\n".getBytes(StandardCharsets.US_ASCII));
                } else if (line.equals("BEGIN")) {
                    return;
                }
            }
        }

        String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            ByteBuffer one = ByteBuffer.allocate(1);
            while (true) {
                one.clear();
                readFully(one);
                char c = (char) (one.get(0) & 0xff);
                if (c == '\n') {
                    return sb.toString().replace("\r", "");
                }
                sb.append(c);
            }
        }

        void write(byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }

        void readFully(ByteBuffer buffer) throws IOException {
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException("the client hung up");
                }
            }
        }

        DBus.Msg readMessage() throws IOException {
            ByteBuffer head = ByteBuffer.allocate(16);
            readFully(head);
            head.flip().order(ByteOrder.LITTLE_ENDIAN);
            int body = head.getInt(4);
            int fields = (head.getInt(12) + 7) & ~7;
            byte[] full = new byte[16 + fields + body];
            System.arraycopy(head.array(), 0, full, 0, 16);
            readFully(ByteBuffer.wrap(full, 16, full.length - 16));
            return DBus.Msg.parse(full);
        }
    }

    /** Opens the client on another thread and accepts it here, handshake included. */
    private Peer connect() throws Exception {
        DBus.Conn[] opened = new DBus.Conn[1];
        Exception[] failed = new Exception[1];
        Thread client = new Thread(() -> {
            try {
                opened[0] = DBus.Conn.open("unix:path=" + directory.resolve("bus"));
            } catch (Exception e) {
                failed[0] = e;
            }
        });
        client.start();
        Peer peer = new Peer(server.accept());
        peer.authenticate();
        client.join(10_000);
        if (failed[0] != null) {
            throw failed[0];
        }
        connection = opened[0];
        return peer;
    }

    private static DBus.Msg aPing(int serial) {
        DBus.Msg ping = DBus.Msg.call(":1.7", "/", Atspi.I_PEER, "Ping", null);
        ping.sender = ":1.99";
        ping.marshal(serial);
        return ping;
    }

    @Test
    void theHandshakeAuthenticatesAndBeginsAndNegotiatesNothingElse() throws Exception {
        Peer peer = connect();
        assertEquals(2, peer.handshake.size(), "what the client said: " + peer.handshake);
        assertTrue(peer.handshake.get(0).startsWith("AUTH EXTERNAL "), peer.handshake.toString());
        assertEquals("BEGIN", peer.handshake.get(1),
                "no NEGOTIATE_UNIX_FD between them: an agreed connection may be sent a descriptor");
    }

    @Test
    void aMessageTheReaderCannotParseIsRefusedAndTheReaderGoesOn() throws Exception {
        Peer peer = connect();
        connection.exportFallback((conn, call) ->
                "Ping".equals(call.member) ? DBus.Msg.ret(call, null) : null);

        peer.write(DBusWireTest.aCallWhoseBodyIsADescriptor((byte) 0));
        peer.write(aPing(6).raw);

        // Bounded, because the failure this pins is a reader that died: nobody would ever answer.
        DBus.Msg[] replies = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> new DBus.Msg[] {peer.readMessage(), peer.readMessage()});
        assertEquals(DBus.ERROR, replies[0].type, "the unreadable call is answered: " + replies[0]);
        assertEquals(5, replies[0].replySerial);
        assertEquals(DBus.METHOD_RETURN, replies[1].type,
                "and the reader is still there to answer the next one: " + replies[1]);
        assertEquals(6, replies[1].replySerial);
    }

    @Test
    void aConnectionThatEndsOnItsOwnSaysSoOnceAndOneClosedByItsOwnerDoesNot() throws Exception {
        Peer peer = connect();
        CountDownLatch lost = new CountDownLatch(1);
        int[] times = {0};
        connection.onLost(() -> {
            times[0]++;
            lost.countDown();
        });
        peer.channel.close();
        assertTrue(lost.await(5, TimeUnit.SECONDS), "the end of the stream is a lost connection");
        Thread.sleep(100);
        assertEquals(1, times[0], "told once");

        Peer second = connect();
        CountDownLatch notLost = new CountDownLatch(1);
        connection.onLost(notLost::countDown);
        connection.close();
        assertFalse(notLost.await(300, TimeUnit.MILLISECONDS),
                "closing it on purpose is not losing it");
        second.channel.close();
    }
}
