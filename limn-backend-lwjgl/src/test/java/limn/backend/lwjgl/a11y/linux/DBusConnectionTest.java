package limn.backend.lwjgl.a11y.linux;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

    /** Opens the client on another thread and accepts it here, handshake included. */
    private PlayedBus.Peer connect() throws Exception {
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
        PlayedBus.Peer peer = new PlayedBus.Peer(server.accept());
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
    void aConnectionWhoseHandshakeIsRefusedClosesItsSocketBeforeTheErrorLeaves() throws Exception {
        // LINUX-NEW-12: a socket that connected and then failed its handshake is still a
        // descriptor, and a join retried on a back-off would leave one behind per attempt. The bus
        // side sees the close as the end of its stream.
        String address = "unix:path=" + directory.resolve("bus");
        for (boolean threadsOfItsOwn : new boolean[] {true, false}) {
            Exception[] failed = new Exception[1];
            Thread client = new Thread(() -> {
                try {
                    if (threadsOfItsOwn) {
                        DBus.Conn.open(address);
                    } else {
                        DBus.Conn.openOnThisThread(address);
                    }
                } catch (Exception e) {
                    failed[0] = e;
                }
            });
            client.start();
            PlayedBus.Peer peer = new PlayedBus.Peer(server.accept());
            peer.refuse();
            client.join(10_000);
            String which = threadsOfItsOwn ? "open" : "openOnThisThread";
            assertTrue(failed[0] instanceof IOException, which + " reports the refusal: " + failed[0]);
            int read = assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> peer.channel.read(ByteBuffer.allocate(1)),
                    which + ": the refused client's socket must be closed, not left for the collector");
            assertEquals(-1, read, which + ": the bus reads the end of the stream");
            peer.channel.close();
        }
    }

    @Test
    void theHandshakeAuthenticatesAndBeginsAndNegotiatesNothingElse() throws Exception {
        PlayedBus.Peer peer = connect();
        assertEquals(2, peer.handshake.size(), "what the client said: " + peer.handshake);
        assertTrue(peer.handshake.get(0).startsWith("AUTH EXTERNAL "), peer.handshake.toString());
        assertEquals("BEGIN", peer.handshake.get(1),
                "no NEGOTIATE_UNIX_FD between them: an agreed connection may be sent a descriptor");
    }

    @Test
    void aMessageTheReaderCannotParseIsRefusedAndTheReaderGoesOn() throws Exception {
        PlayedBus.Peer peer = connect();
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
    void aHandlerThatThrowsOrAnswersWhatCannotBeWrittenStillAnswersTheCaller() throws Exception {
        PlayedBus.Peer peer = connect();
        connection.exportFallback((conn, call) -> switch (call.member) {
            case "GetRole" -> throw new IllegalStateException("no snapshot");
            case "GetName" -> DBus.Msg.ret(call, "u", "not a number");
            default -> DBus.Msg.ret(call, null);
        });
        DBus.Msg role = DBus.Msg.call(":1.7", Atspi.PATH_ROOT, Atspi.I_ACCESSIBLE, "GetRole", null);
        role.sender = ":1.99";
        DBus.Msg name = DBus.Msg.call(":1.7", Atspi.PATH_ROOT, Atspi.I_ACCESSIBLE, "GetName", null);
        name.sender = ":1.99";
        peer.write(role.marshal(21));
        peer.write(name.marshal(22));
        peer.write(aPing(23).raw);

        DBus.Msg[] replies = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> new DBus.Msg[] {peer.readMessage(), peer.readMessage(), peer.readMessage()});
        assertEquals(DBus.ERROR, replies[0].type, "a handler that threw: " + replies[0]);
        assertEquals(21, replies[0].replySerial);
        assertEquals(DBus.ERROR, replies[1].type, "a reply that could not be marshalled: " + replies[1]);
        assertEquals(22, replies[1].replySerial);
        assertEquals(DBus.METHOD_RETURN, replies[2].type, "and the next call is answered as usual");
        assertEquals(23, replies[2].replySerial);
    }

    @Test
    void aHandlerThatOverflowsTheStackIsAnsweredAndNeitherTheReaderNorTheConnectionEnds()
            throws Exception {
        // A StackOverflowError is not an Exception. Caught as one alone, it left replyFor, ended
        // the reader and closed the connection, and the application then joined again: one
        // client call, one lost join.
        PlayedBus.Peer peer = connect();
        CountDownLatch lost = new CountDownLatch(1);
        connection.onLost(lost::countDown);
        connection.exportFallback((conn, call) -> {
            if ("GetRole".equals(call.member)) {
                throw new StackOverflowError("a describe hook recursed");
            }
            return DBus.Msg.ret(call, null);
        });
        DBus.Msg role = DBus.Msg.call(":1.7", Atspi.PATH_ROOT, Atspi.I_ACCESSIBLE, "GetRole", null);
        role.sender = ":1.99";
        peer.write(role.marshal(31));
        peer.write(aPing(32).raw);

        DBus.Msg[] replies = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> new DBus.Msg[] {peer.readMessage(), peer.readMessage()});
        assertEquals(DBus.ERROR, replies[0].type, "the overflowing handler's caller is answered");
        assertEquals("org.freedesktop.DBus.Error.Failed", replies[0].errorName);
        assertEquals(31, replies[0].replySerial);
        assertEquals(32, replies[1].replySerial, "and the reader answers the next call");
        assertFalse(lost.await(200, TimeUnit.MILLISECONDS), "the connection was not lost over it");
    }

    @Test
    void aConnectionThatEndsOnItsOwnSaysSoOnceAndOneClosedByItsOwnerDoesNot() throws Exception {
        PlayedBus.Peer peer = connect();
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

        PlayedBus.Peer second = connect();
        CountDownLatch notLost = new CountDownLatch(1);
        connection.onLost(notLost::countDown);
        connection.close();
        assertFalse(notLost.await(300, TimeUnit.MILLISECONDS),
                "closing it on purpose is not losing it");
        second.channel.close();
    }
}
