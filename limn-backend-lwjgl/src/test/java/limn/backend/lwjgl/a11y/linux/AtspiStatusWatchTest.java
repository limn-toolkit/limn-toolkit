package limn.backend.lwjgl.a11y.linux;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The watch on the desktop's accessibility switch (LINUX-NEW-7, decision 29), against the signal
 * at-spi-bus-launcher was read sending on Fedora KDE 44 (2.60.6) and Ubuntu 24.04 (2.52.0) on
 * 2026-09-15 (readings/fedora-a11y-status-signal.txt, readings/ubuntu-a11y-status-signal.txt): a
 * broadcast {@code PropertiesChanged} on {@code /org/a11y/bus} carrying
 * {@code ("org.a11y.Status", {"IsEnabled": <b>}, [])}.
 */
class AtspiStatusWatchTest {

    /** The announcement as the readings show it, from the launcher's unique name. */
    private static DBus.Msg theSwitchSays(String iface, boolean on) {
        DBus.Msg signal = DBus.Msg.signal("/org/a11y/bus", "org.freedesktop.DBus.Properties",
                "PropertiesChanged", "sa{sv}as", iface,
                Map.of("IsEnabled", new DBus.Variant("b", on)), List.of());
        signal.sender = ":1.33";
        return signal;
    }

    /** What the watch reads off the wire, as a message it parsed. */
    private static DBus.Msg asReceived(DBus.Msg m) {
        return DBus.Msg.parse(m.marshal(351));
    }

    @Test
    void theSwitchsOwnAnnouncementIsReadAndNothingElseIs() {
        assertEquals(Boolean.FALSE, AtspiStatusWatch.enabledIn(asReceived(
                theSwitchSays("org.a11y.Status", false))));
        assertEquals(Boolean.TRUE, AtspiStatusWatch.enabledIn(asReceived(
                theSwitchSays("org.a11y.Status", true))));
        assertNull(AtspiStatusWatch.enabledIn(asReceived(theSwitchSays("org.a11y.Other", true))),
                "another interface's IsEnabled is not the switch");
        DBus.Msg elsewhere = theSwitchSays("org.a11y.Status", true);
        elsewhere.path = "/org/a11y/elsewhere";
        assertNull(AtspiStatusWatch.enabledIn(asReceived(elsewhere)));
        DBus.Msg screenReader = DBus.Msg.signal("/org/a11y/bus", "org.freedesktop.DBus.Properties",
                "PropertiesChanged", "sa{sv}as", "org.a11y.Status",
                Map.of("ScreenReaderEnabled", new DBus.Variant("b", true)), List.of());
        assertNull(AtspiStatusWatch.enabledIn(asReceived(screenReader)),
                "ScreenReaderEnabled is a different property: turning it off leaves IsEnabled on");
    }

    @Test
    void theBusIsAskedForTheSwitchsAnnouncementByItsWellKnownSenderAndNothingMore() {
        assertEquals("type='signal',sender='org.a11y.Bus',path='/org/a11y/bus',"
                        + "interface='org.freedesktop.DBus.Properties',member='PropertiesChanged',"
                        + "arg0='org.a11y.Status'",
                AtspiStatusWatch.MATCH_RULE);
    }

    @Test
    void aProcessWithNoSessionBusItCanOpenWatchesNothing() {
        assertFalse(AtspiStatusWatch.canWatch(null), "headless: no bridge, no thread");
        assertFalse(AtspiStatusWatch.canWatch("unix:abstract=/tmp/dbus-XYZ"),
                "an abstract socket this client cannot open");
        assertTrue(AtspiStatusWatch.canWatch("unix:path=/run/user/1000/bus"));
    }

    private Path directory;
    private ServerSocketChannel server;
    private AtspiStatusWatch watch;

    @BeforeEach
    void listen() throws IOException {
        directory = Files.createTempDirectory("dbus");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(directory.resolve("bus")));
    }

    @AfterEach
    void close() throws IOException {
        if (watch != null) {
            watch.stop();
        }
        server.close();
        Files.deleteIfExists(directory.resolve("bus"));
        Files.deleteIfExists(directory);
    }

    /** Accepts the watch's connection and answers Hello and AddMatch, returning the Get call. */
    private static DBus.Msg acceptUpToTheRead(PlayedBus.Peer peer, List<String> order)
            throws IOException {
        peer.authenticate();
        DBus.Msg hello = peer.readMessage();
        order.add(hello.member);
        peer.write(DBus.Msg.ret(hello, "s", ":1.5").marshal(1));
        DBus.Msg match = peer.readMessage();
        order.add(match.member);
        assertEquals(AtspiStatusWatch.MATCH_RULE, match.body[0], "the rule the bus is given");
        peer.write(DBus.Msg.ret(match, null).marshal(2));
        DBus.Msg get = peer.readMessage();
        order.add(get.member);
        assertEquals("org.a11y.Bus", get.destination);
        assertEquals("/org/a11y/bus", get.path);
        assertEquals(List.of("org.a11y.Status", "IsEnabled"), List.of(get.body));
        return get;
    }

    @Test
    void theWatchMatchesBeforeItReadsThenFollowsTheSwitchAndReadsItAgainAfterALostConnection() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            BlockingQueue<Boolean> flags = new LinkedBlockingQueue<>();
            List<Long> waits = new CopyOnWriteArrayList<>();
            watch = new AtspiStatusWatch("unix:path=" + directory.resolve("bus"), flags::add,
                    waits::add);
            Thread thread = new Thread(watch, AtspiStatusWatch.THREAD_NAME);
            thread.setDaemon(true);
            thread.start();

            List<String> order = new CopyOnWriteArrayList<>();
            PlayedBus.Peer first = new PlayedBus.Peer(server.accept());
            DBus.Msg get = acceptUpToTheRead(first, order);
            assertEquals(List.of("Hello", "AddMatch", "Get"), order,
                    "the match before the read, so no change can fall between them");
            // A change announced while the read is in flight arrives first, and the reply after it
            // is newer: the flag ends at the reply's value.
            first.write(theSwitchSays("org.a11y.Status", true).marshal(351));
            first.write(DBus.Msg.ret(get, "v", new DBus.Variant("b", false)).marshal(3));
            assertEquals(Boolean.TRUE, flags.poll(5, TimeUnit.SECONDS));
            assertEquals(Boolean.FALSE, flags.poll(5, TimeUnit.SECONDS));

            first.write(theSwitchSays("org.a11y.Other", true).marshal(352));
            DBus.Msg stray = DBus.Msg.call(":1.5", "/", Atspi.I_PEER, "Ping", null);
            stray.sender = ":1.40";
            first.write(stray.marshal(353));
            DBus.Msg refused = first.readMessage();
            assertEquals(DBus.ERROR, refused.type, "a stray call is answered, not ignored");
            assertEquals(353, refused.replySerial);
            first.write(theSwitchSays("org.a11y.Status", true).marshal(354));
            assertEquals(Boolean.TRUE, flags.poll(5, TimeUnit.SECONDS),
                    "Orca starting: the switch turns on and the watch says so");
            assertTrue(flags.isEmpty(), "another interface's news was not taken for the switch");

            first.channel.close();
            PlayedBus.Peer second = new PlayedBus.Peer(server.accept());
            DBus.Msg again = acceptUpToTheRead(second, order);
            second.write(DBus.Msg.ret(again, "v", new DBus.Variant("b", true)).marshal(3));
            assertEquals(Boolean.TRUE, flags.poll(5, TimeUnit.SECONDS),
                    "a lost connection is opened again and the switch read again");
            assertEquals(List.of(AtspiStatusWatch.FIRST_RETRY_NANOS), waits,
                    "after a back-off, not in a spin");
            second.channel.close();
        });
    }
}
