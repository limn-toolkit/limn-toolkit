package limn.backend.lwjgl.a11y.linux;

import limn.backend.spi.TrayHost;
import limn.backend.spi.TrayPeer;
import limn.backend.spi.TrayPicture;
import limn.backend.spi.TrayRow;
import limn.backend.spi.TrayState;
import limn.graphics.Icon;
import limn.graphics.Image;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tray's status item and menu: what they say as values, and what they do on a connection to
 * a bus this test plays over a real unix socket, with a watcher that takes the item or is not
 * there.
 */
class StatusNotifierTrayTest {

    private static final Duration SOON = Duration.ofSeconds(10);

    private static Icon square(int rgb, boolean tintable) {
        return new Icon() {
            @Override
            public Image image(int pixelSize, boolean dark) {
                byte[] px = new byte[pixelSize * pixelSize * 4];
                for (int i = 0; i < px.length; i += 4) {
                    px[i] = (byte) (rgb >> 16);
                    px[i + 1] = (byte) (rgb >> 8);
                    px[i + 2] = (byte) rgb;
                    px[i + 3] = (byte) 0x80;
                }
                return new Image(pixelSize, pixelSize, px);
            }

            @Override
            public boolean tintable() {
                return tintable;
            }
        };
    }

    private static TrayState state(boolean activates, boolean attention) {
        return new TrayState("Orders", "3 waiting", new TrayPicture(square(0x2E7DFF, false), null, false), null,
                attention, activates, false);
    }

    private static TrayRow row(int id, TrayRow.Kind kind, String label, TrayRow... children) {
        return new TrayRow(id, kind, label, true, false, (char) 0, List.of(children));
    }

    // ---- values -------------------------------------------------------------------------------

    @Test
    void theItemsPropertiesAreTheStates() {
        Map<String, DBus.Variant> p = TrayWire.itemProperties(state(false, false), "orders", TrayWire.PANEL_GREY);
        assertEquals("orders", p.get("Id").value);
        assertEquals("Orders", p.get("Title").value);
        assertEquals("Active", p.get("Status").value);
        assertEquals(TrayWire.MENU_PATH, p.get("Menu").value);
        assertEquals(true, p.get("ItemIsMenu").value, "with no activation every click opens the menu");
        assertEquals(false, TrayWire.itemProperties(state(true, false), "orders", TrayWire.PANEL_GREY).get("ItemIsMenu").value);
        assertEquals("3 waiting", ((Object[]) p.get("ToolTip").value)[2]);
        List<?> pixmaps = (List<?>) p.get("IconPixmap").value;
        assertEquals(TrayWire.SIZES.length, pixmaps.size());
        Object[] first = (Object[]) pixmaps.get(0);
        assertEquals(16, first[0]);
        byte[] argb = (byte[]) first[2];
        assertEquals(16 * 16 * 4, argb.length);
        assertEquals((byte) 0x80, argb[0], "alpha first");
        assertEquals((byte) 0x2E, argb[1]);
        assertEquals((byte) 0xFF, argb[3]);
        assertTrue(((List<?>) p.get("AttentionIconPixmap").value).isEmpty());
    }

    @Test
    void attentionIsAStatusWithItsPicture() {
        Map<String, DBus.Variant> p = TrayWire.itemProperties(state(false, true), "orders", TrayWire.PANEL_GREY);
        assertEquals("NeedsAttention", p.get("Status").value);
        assertEquals(TrayWire.SIZES.length, ((List<?>) p.get("AttentionIconPixmap").value).size());
    }

    @Test
    void aMonochromeIconAloneIsDrawnInTheGrey() {
        TrayState only = new TrayState("Orders", "", null, new TrayPicture(square(0, true), null, false), false,
                false, false);
        byte[] argb = (byte[]) ((Object[]) ((List<?>) TrayWire.itemProperties(only, "orders", TrayWire.panelTint("KDE")).get("IconPixmap").value)
                .get(0))[2];
        assertEquals((byte) 0x8C, argb[1]);
        assertEquals("Orders", ((Object[]) TrayWire.itemProperties(only, "orders", TrayWire.panelTint("KDE")).get("ToolTip").value)[2],
                "with no tooltip the name is what there is to say");
    }

    @Test
    void onGnomeAMonochromeIconIsTheWhiteOfTheBarsOwn() {
        assertEquals(limn.graphics.Color.rgb(0xFFFFFF), TrayWire.panelTint("ubuntu:GNOME"));
        assertEquals(limn.graphics.Color.rgb(0xFFFFFF), TrayWire.panelTint("GNOME"));
        assertEquals(TrayWire.PANEL_GREY, TrayWire.panelTint("KDE"));
        assertEquals(TrayWire.PANEL_GREY, TrayWire.panelTint(null));
    }

    @Test
    void aPictureIsSentAtEverySizeDrawnForIt() {
        List<?> pixmaps = TrayWire.pixmaps(state(false, false), TrayWire.PANEL_GREY);
        for (int i = 0; i < TrayWire.SIZES.length; i++) {
            Object[] one = (Object[]) pixmaps.get(i);
            assertEquals(TrayWire.SIZES[i], one[0]);
            assertEquals(TrayWire.SIZES[i] * TrayWire.SIZES[i] * 4, ((byte[]) one[2]).length);
        }
        assertEquals(96, TrayWire.SIZES[TrayWire.SIZES.length - 1], "three times a 32-pixel slot");
    }

    @Test
    void everyPropertyAndTheLayoutCanBeWritten() {
        Map<String, DBus.Variant> p = TrayWire.itemProperties(state(true, true), "orders", TrayWire.PANEL_GREY);
        byte[] wire = DBus.writeArgs("a{sv}", new Object[] {p});
        Object[] back = DBus.readArgs(wire, ByteOrder.LITTLE_ENDIAN, "a{sv}");
        assertEquals(p.size(), ((Map<?, ?>) back[0]).size());

        List<TrayRow> rows = List.of(row(1, TrayRow.Kind.COMMAND, "Open"),
                row(2, TrayRow.Kind.SUBMENU, "Export", row(3, TrayRow.Kind.COMMAND, "PDF")));
        Object[] layout = TrayWire.layout(0, -1, rows);
        byte[] bytes = DBus.writeArgs("u(ia{sv}av)", new Object[] {7, layout});
        assertEquals(7, DBus.readArgs(bytes, ByteOrder.LITTLE_ENDIAN, "u(ia{sv}av)")[0]);
    }

    @Test
    void introspectionListsActivationOnlyForAnItemThatActivates() {
        assertTrue(TrayWire.itemIntrospection(true).contains("\"Activate\""));
        assertFalse(TrayWire.itemIntrospection(false).contains("\"Activate\""));
    }

    @Test
    void theLayoutFollowsTheRowsToTheDepthAsked() {
        List<TrayRow> rows = List.of(row(1, TrayRow.Kind.COMMAND, "Open"),
                row(2, TrayRow.Kind.SUBMENU, "Export", row(3, TrayRow.Kind.COMMAND, "PDF")));
        Object[] all = TrayWire.layout(0, -1, rows);
        assertEquals(0, all[0]);
        List<?> top = (List<?>) all[2];
        assertEquals(2, top.size());
        Object[] export = (Object[]) ((DBus.Variant) top.get(1)).value;
        assertEquals(2, export[0]);
        assertEquals(1, ((List<?>) export[2]).size());

        Object[] oneLevel = TrayWire.layout(0, 1, rows);
        Object[] shallow = (Object[]) ((DBus.Variant) ((List<?>) oneLevel[2]).get(1)).value;
        assertTrue(((List<?>) shallow[2]).isEmpty());

        assertEquals(3, ((List<?>) ((Object[]) TrayWire.layout(2, -1, rows))[2]).size() + 2);
        assertNull(TrayWire.layout(99, -1, rows));
    }

    @Test
    void aRowsPropertiesLeaveOutWhatIsAtItsDefault() {
        assertEquals(Map.of("label", "Open").keySet(), TrayWire.rowProperties(row(1, TrayRow.Kind.COMMAND, "Open")).keySet());
        Map<String, DBus.Variant> check = TrayWire.rowProperties(
                new TrayRow(2, TrayRow.Kind.CHECK, "Bold", false, true, (char) 0, List.of()));
        assertEquals(false, check.get("enabled").value);
        assertEquals("checkmark", check.get("toggle-type").value);
        assertEquals(1, check.get("toggle-state").value);
        assertEquals("separator", TrayWire.rowProperties(
                new TrayRow(3, TrayRow.Kind.SEPARATOR, "", false, false, (char) 0, List.of())).get("type").value);
    }

    @Test
    void anUnderscoreInALabelIsTheTextsAndTheAccessLetterIsMarked() {
        assertEquals("report__final.pdf", TrayWire.label(row(1, TrayRow.Kind.COMMAND, "report_final.pdf")));
        assertEquals("_Open", TrayWire.label(new TrayRow(1, TrayRow.Kind.COMMAND, "Open", true, false, 'O', List.of())));
        assertEquals("E_xit", TrayWire.label(new TrayRow(1, TrayRow.Kind.COMMAND, "Exit", true, false, 'X', List.of())));
        assertEquals("Open", TrayWire.label(new TrayRow(1, TrayRow.Kind.COMMAND, "Open", true, false, 'Z', List.of())));
    }

    @Test
    void noMenuIsOneDisabledRowOfTheTitle() {
        List<TrayRow> shown = TrayWire.shown(List.of(), "Orders");
        assertEquals(1, shown.size());
        assertEquals("Orders", shown.get(0).label());
        assertFalse(shown.get(0).enabled());
    }

    @Test
    void noBusIsNoTray() {
        TrayHost host = new Host();
        assertEquals(TrayPeer.NONE, StatusNotifierTray.create("orders", host, Runnable::run, null));
        assertEquals(TrayPeer.NONE, StatusNotifierTray.create("orders", host, Runnable::run, "unix:abstract=/tmp/x"));
    }

    // ---- on a connection ----------------------------------------------------------------------

    /** The toolkit's side: a menu that can be changed, and what was chosen and activated. */
    private static final class Host implements TrayHost {
        volatile List<TrayRow> menu = List.of();
        final List<String> heard = new CopyOnWriteArrayList<>();

        @Override
        public List<TrayRow> menu() {
            return menu;
        }

        @Override
        public void choose(int id) {
            heard.add("choose " + id);
        }

        @Override
        public void activate() {
            heard.add("activate");
        }

        @Override
        public void availabilityChanged() {
            heard.add("availability");
        }
    }

    private Path directory;
    private ServerSocketChannel server;
    private ExecutorService ui;
    private Host host;
    private TrayPeer tray;
    private PlayedBus.Peer bus;
    private int serial = 100;

    @BeforeEach
    void listen() throws IOException {
        directory = Files.createTempDirectory("tray");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(directory.resolve("bus")));
        ui = Executors.newSingleThreadExecutor();
        host = new Host();
    }

    @AfterEach
    void close() throws IOException {
        if (tray != null) {
            tray.dispose();
        }
        ui.shutdownNow();
        server.close();
        Files.deleteIfExists(directory.resolve("bus"));
        Files.deleteIfExists(directory);
    }

    /** Shows the tray and plays the bus up to the watcher's answer to the registration. */
    private void show(TrayState first, boolean watcher) throws IOException {
        tray = StatusNotifierTray.create("orders", host, ui, "unix:path=" + directory.resolve("bus"));
        tray.update(first);
        accept(watcher);
    }

    private void accept(boolean watcher) throws IOException {
        bus = new PlayedBus.Peer(server.accept());
        bus.authenticate();
        DBus.Msg hello = bus.readMessage();
        assertEquals("Hello", hello.member);
        reply(DBus.Msg.ret(hello, "s", ":1.42"));
        DBus.Msg match = bus.readMessage();
        assertEquals("AddMatch", match.member);
        assertTrue(String.valueOf(match.body[0]).contains(TrayWire.WATCHER));
        reply(DBus.Msg.ret(match, null));
        answerRegistration(watcher);
    }

    private void answerRegistration(boolean watcher) throws IOException {
        DBus.Msg register = bus.readMessage();
        assertEquals("RegisterStatusNotifierItem", register.member);
        assertEquals(TrayWire.WATCHER, register.destination);
        assertEquals(":1.42", register.body[0]);
        reply(watcher ? DBus.Msg.ret(register, null)
                : DBus.Msg.err(register, "org.freedesktop.DBus.Error.ServiceUnknown", "no watcher"));
    }

    private void reply(DBus.Msg message) throws IOException {
        bus.write(message.marshal(serial++));
    }

    /** Calls the tray as a panel does and reads on until the answer, keeping the signals met on the way. */
    private DBus.Msg ask(String path, String iface, String member, String signature, Object... args) throws IOException {
        DBus.Msg call = DBus.Msg.call(":1.42", path, iface, member, signature, args);
        call.sender = ":1.7";
        int mine = serial++;
        bus.write(call.marshal(mine));
        while (true) {
            DBus.Msg got = bus.readMessage();
            if (got.type == DBus.SIGNAL) {
                signals.add(got);
            } else if (got.replySerial == mine) {
                return got;
            }
        }
    }

    private final List<DBus.Msg> signals = new ArrayList<>();

    private void await(Runnable check) {
        assertTimeoutPreemptively(SOON, () -> {
            while (true) {
                try {
                    check.run();
                    return;
                } catch (AssertionError notYet) {
                    Thread.sleep(10);
                }
            }
        });
    }

    @Test
    void aWatcherThatTakesTheItemMakesTheTrayAvailable() throws Exception {
        assertTimeoutPreemptively(SOON, () -> show(state(false, false), true));
        await(() -> assertTrue(tray.isAvailable()));
        await(() -> assertEquals(List.of("availability"), host.heard));
        DBus.Msg all = ask(TrayWire.ITEM_PATH, DBus.I_PROPS_NAME, "GetAll", "s", TrayWire.ITEM);
        assertEquals("Orders", ((DBus.Variant) ((Map<?, ?>) all.body[0]).get("Title")).value);
        DBus.Msg xml = ask(TrayWire.ITEM_PATH, "org.freedesktop.DBus.Introspectable", "Introspect", null);
        assertFalse(String.valueOf(xml.body[0]).contains("\"Activate\""));
    }

    @Test
    void aDesktopWithNoWatcherHasNoTrayUntilOneAppears() throws Exception {
        assertTimeoutPreemptively(SOON, () -> show(state(false, false), false));
        assertFalse(tray.isAvailable());
        assertTrue(host.heard.isEmpty());

        // The watcher's name gets an owner: the item registers with it.
        DBus.Msg appeared = DBus.Msg.signal("/org/freedesktop/DBus", "org.freedesktop.DBus", "NameOwnerChanged",
                "sss", TrayWire.WATCHER, "", ":1.50");
        appeared.sender = "org.freedesktop.DBus";
        reply(appeared);
        assertTimeoutPreemptively(SOON, () -> answerRegistration(true));
        await(() -> assertTrue(tray.isAvailable()));

        DBus.Msg gone = DBus.Msg.signal("/org/freedesktop/DBus", "org.freedesktop.DBus", "NameOwnerChanged",
                "sss", TrayWire.WATCHER, ":1.50", "");
        gone.sender = "org.freedesktop.DBus";
        reply(gone);
        await(() -> assertFalse(tray.isAvailable()));
        await(() -> assertEquals(List.of("availability", "availability"), host.heard));
    }

    @Test
    void theMenuIsReadAgainWhenItIsAboutToShow() throws Exception {
        host.menu = List.of(row(1, TrayRow.Kind.COMMAND, "Open"));
        assertTimeoutPreemptively(SOON, () -> show(state(false, false), true));
        DBus.Msg first = assertTimeoutPreemptively(SOON,
                () -> ask(TrayWire.MENU_PATH, TrayWire.MENU, "GetLayout", "iias", 0, -1, List.of()));
        int revision = (Integer) first.body[0];
        assertEquals(1, ((List<?>) ((Object[]) first.body[1])[2]).size());

        DBus.Msg same = ask(TrayWire.MENU_PATH, TrayWire.MENU, "AboutToShow", "i", 0);
        assertEquals(false, same.body[0], "nothing changed, so the panel keeps what it has");

        host.menu = List.of(row(1, TrayRow.Kind.COMMAND, "Open"), row(2, TrayRow.Kind.CHECK, "Bold"));
        signals.clear();
        DBus.Msg changed = ask(TrayWire.MENU_PATH, TrayWire.MENU, "AboutToShow", "i", 0);
        assertEquals(true, changed.body[0]);
        assertTrue(signals.stream().anyMatch(s -> "LayoutUpdated".equals(s.member)), "and it is told so");
        DBus.Msg second = ask(TrayWire.MENU_PATH, TrayWire.MENU, "GetLayout", "iias", 0, -1, List.of());
        assertTrue((Integer) second.body[0] > revision);
        assertEquals(2, ((List<?>) ((Object[]) second.body[1])[2]).size());
    }

    @Test
    void aClickOnARowAndAnActivationReachTheHost() throws Exception {
        host.menu = List.of(row(1, TrayRow.Kind.COMMAND, "Open"));
        assertTimeoutPreemptively(SOON, () -> show(state(true, false), true));
        assertTimeoutPreemptively(SOON, () -> {
            ask(TrayWire.MENU_PATH, TrayWire.MENU, "GetLayout", "iias", 0, -1, List.of());
            ask(TrayWire.MENU_PATH, TrayWire.MENU, "Event", "isvu", 1, "clicked", DBus.v("s", ""), 0);
            ask(TrayWire.MENU_PATH, TrayWire.MENU, "Event", "isvu", 1, "hovered", DBus.v("s", ""), 0);
            ask(TrayWire.ITEM_PATH, TrayWire.ITEM, "ProvideXdgActivationToken", "s", "token-1");
            ask(TrayWire.ITEM_PATH, TrayWire.ITEM, "Activate", "ii", 10, 20);
            ask(TrayWire.ITEM_PATH, TrayWire.ITEM, "SecondaryActivate", "ii", 10, 20);
        });
        await(() -> assertEquals(List.of("availability", "choose 1", "activate"), host.heard));
        assertEquals("token-1", ((StatusNotifierTray) tray).activationToken());
    }

    @Test
    void withNoMenuTheRowOfTheTitleIsNobodysToChoose() throws Exception {
        assertTimeoutPreemptively(SOON, () -> show(state(false, false), true));
        DBus.Msg layout = assertTimeoutPreemptively(SOON,
                () -> ask(TrayWire.MENU_PATH, TrayWire.MENU, "GetLayout", "iias", 0, -1, List.of()));
        assertEquals(1, ((List<?>) ((Object[]) layout.body[1])[2]).size());
        ask(TrayWire.MENU_PATH, TrayWire.MENU, "Event", "isvu", 1, "clicked", DBus.v("s", ""), 0);
        ask(TrayWire.MENU_PATH, TrayWire.MENU, "AboutToShow", "i", 0);
        assertEquals(List.of("availability"), host.heard);
    }

    @Test
    void aChangeWhileShownIsSignalled() throws Exception {
        assertTimeoutPreemptively(SOON, () -> show(state(false, false), true));
        await(() -> assertTrue(tray.isAvailable()));
        tray.update(new TrayState("Orders", "5 waiting", new TrayPicture(square(0xFF0000, false), null, false), null,
                true, false, false));
        assertTimeoutPreemptively(SOON, () -> {
            ask(TrayWire.ITEM_PATH, DBus.I_PROPS_NAME, "Get", "ss", TrayWire.ITEM, "Status");
        });
        List<String> names = signals.stream().map(s -> s.member).toList();
        assertTrue(names.containsAll(List.of("NewIcon", "NewAttentionIcon", "NewToolTip", "NewStatus")), names.toString());
        assertFalse(names.contains("NewTitle"));
    }

    @Test
    void anItemThatStartsToActivateRegistersAgain() throws Exception {
        assertTimeoutPreemptively(SOON, () -> show(state(false, false), true));
        await(() -> assertTrue(tray.isAvailable()));
        tray.update(state(true, false));
        assertTimeoutPreemptively(SOON, () -> accept(true));   // a new connection, and a new name on a real bus
        await(() -> assertTrue(tray.isAvailable()));
        DBus.Msg xml = assertTimeoutPreemptively(SOON,
                () -> ask(TrayWire.ITEM_PATH, "org.freedesktop.DBus.Introspectable", "Introspect", null));
        assertTrue(String.valueOf(xml.body[0]).contains("\"Activate\""));
    }

    @Test
    void disposingClosesTheConnection() throws Exception {
        assertTimeoutPreemptively(SOON, () -> show(state(false, false), true));
        await(() -> assertTrue(tray.isAvailable()));
        tray.dispose();
        assertFalse(tray.isAvailable());
        assertTimeoutPreemptively(SOON, () -> {
            try {
                while (true) {
                    bus.readMessage();
                }
            } catch (IOException closed) {
                // The name leaves the bus with the connection, which is how a panel drops the item.
            }
        });
        assertNotNull(bus);
    }
}
