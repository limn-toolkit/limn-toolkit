package limn.desktop;

import limn.backend.spi.TrayHost;
import limn.backend.spi.TrayPeer;
import limn.backend.spi.TrayPicture;
import limn.backend.spi.TrayRow;
import limn.backend.spi.TrayState;
import limn.components.Menu;
import limn.components.MenuItem;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.scene.LayoutDirection;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@link Tray} hands a backend and does with what a backend tells it. */
class TrayTest {

    /** A peer that remembers each state and is told when it is available. */
    private static final class Recording implements TrayPeer {
        final List<TrayState> states = new ArrayList<>();
        TrayHost host;
        boolean available = true;
        int disposed;

        @Override
        public void update(TrayState state) {
            states.add(state);
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public void dispose() {
            disposed++;
        }

        TrayState last() {
            return states.get(states.size() - 1);
        }
    }

    /** A solid square in one colour, tintable or not. */
    private static Icon square(int rgb, boolean tintable) {
        return new Icon() {
            @Override
            public Image image(int pixelSize, boolean dark) {
                byte[] px = new byte[pixelSize * pixelSize * 4];
                for (int i = 0; i < px.length; i += 4) {
                    px[i] = (byte) (rgb >> 16);
                    px[i + 1] = (byte) (rgb >> 8);
                    px[i + 2] = (byte) rgb;
                    px[i + 3] = (byte) 0xFF;
                }
                return new Image(pixelSize, pixelSize, px);
            }

            @Override
            public boolean tintable() {
                return tintable;
            }
        };
    }

    private HeadlessUi ui;
    private Recording peer;
    private Tray tray;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
        peer = new Recording();
        tray = Tray.over("orders", host -> {
            peer.host = host;
            return peer;
        });
    }

    @AfterEach
    void closeRuntime() {
        ui.close();
    }

    private static int rgbAt(Image image, int x, int y) {
        int at = (y * image.width() + x) * 4;
        byte[] p = image.pixels();
        return (p[at] & 0xFF) << 16 | (p[at + 1] & 0xFF) << 8 | p[at + 2] & 0xFF;
    }

    // ---- what is shown -----------------------------------------------------------------------

    @Test
    void nothingIsShownUntilThereIsAnIcon() {
        tray.setTitle("Orders").setTooltip("3 waiting").setAttention(true);
        assertTrue(peer.states.isEmpty());
        assertFalse(tray.isAvailable(), "no icon was handed over, so none was taken");

        tray.setIcon(square(0x2E7DFF, false));
        assertEquals(1, peer.states.size());
        TrayState state = peer.last();
        assertEquals("Orders", state.title());
        assertEquals("3 waiting", state.tooltip());
        assertTrue(state.attention());
        assertFalse(state.activates());
        assertTrue(tray.isAvailable());
    }

    @Test
    void theTitleIsTheIdentifierUntilSet() {
        tray.setIcon(square(0, false));
        assertEquals("orders", peer.last().title());
    }

    @Test
    void aSetterThatChangesNothingSendsNothing() {
        tray.setIcon(square(0, false)).setTooltip("a");
        int sent = peer.states.size();
        tray.setTooltip("a").setAttention(false).onActivate(null);
        assertEquals(sent, peer.states.size());
    }

    @Test
    void aMonochromeIconIsATintableOneAndGoesBesideTheOther() {
        assertThrows(IllegalArgumentException.class, () -> tray.setMonochromeIcon(square(0, false)));
        tray.setMonochromeIcon(square(0xFFFFFF, true));
        assertNull(peer.last().picture());
        assertNotNull(peer.last().monochrome());
        tray.setIcon(square(0x2E7DFF, false));
        assertNotNull(peer.last().picture());
        assertNotNull(peer.last().monochrome());
    }

    @Test
    void activationIsSaidInTheStateAndRunsTheListener() {
        int[] runs = {0};
        tray.setIcon(square(0, false)).onActivate(() -> runs[0]++);
        assertTrue(peer.last().activates());
        peer.host.activate();
        assertEquals(1, runs[0]);
        tray.onActivate(null);
        assertFalse(peer.last().activates());
        peer.host.activate();
        assertEquals(1, runs[0]);
    }

    // ---- attention ---------------------------------------------------------------------------

    @Test
    void attentionPutsADotAtTheTopTrailingCorner() {
        tray.setIcon(square(0x2E7DFF, false));
        Image plain = peer.last().picture().render(32, null, false);
        assertEquals(0x2E7DFF, rgbAt(plain, 27, 4));

        tray.setAttention(true);
        Image dotted = peer.last().picture().render(32, null, false);
        assertTrue(rgbAt(dotted, 25, 6) != 0x2E7DFF, "the dot covers the top right");
        assertEquals(0x2E7DFF, rgbAt(dotted, 6, 6), "and not the top left");
        assertEquals(0x2E7DFF, rgbAt(dotted, 16, 26));

        tray.setLayoutDirection(LayoutDirection.RTL);
        Image mirrored = peer.last().picture().render(32, null, false);
        assertTrue(rgbAt(mirrored, 6, 6) != 0x2E7DFF, "right to left, the trailing corner is the left one");
        assertEquals(0x2E7DFF, rgbAt(mirrored, 25, 6));
    }

    @Test
    void theDotGoesOverTheTintOfAMonochromeIcon() {
        tray.setMonochromeIcon(square(0x000000, true)).setAttention(true);
        Image image = peer.last().monochrome().render(32, Color.rgb(0xFFFFFF), true);
        assertEquals(0xFFFFFF, rgbAt(image, 16, 26), "the shape takes the tint");
        int dot = rgbAt(image, 25, 6);
        assertTrue(dot != 0xFFFFFF && dot != 0, "the dot keeps its own colour over the tint");
    }

    @Test
    void anAttentionIconReplacesTheIconAndHasNoDot() {
        tray.setIcon(square(0x2E7DFF, false)).setMonochromeIcon(square(0, true))
                .setAttentionIcon(square(0xE53935, false)).setAttention(true);
        TrayState state = peer.last();
        assertNull(state.monochrome(), "a picture of the application's own is shown as it is");
        Image image = state.picture().render(16, null, false);
        assertEquals(0xE53935, rgbAt(image, 13, 2));
        tray.setAttention(false);
        assertEquals(0x2E7DFF, rgbAt(peer.last().picture().render(16, null, false), 13, 2));
    }

    @Test
    void aPictureIsFittedToTheSizeAsked() {
        Icon wide = (px, dark) -> {
            byte[] p = new byte[64 * 32 * 4];
            java.util.Arrays.fill(p, (byte) 0xFF);
            return new Image(64, 32, p);
        };
        Image image = new TrayPicture(wide, null, false).render(16, null, false);
        assertEquals(16, image.width());
        assertEquals(16, image.height());
        assertEquals(0, image.pixels()[3], "above the fitted picture the square is clear");
        assertEquals((byte) 0xFF, image.pixels()[(8 * 16 + 8) * 4 + 3]);
    }

    // ---- the menu ----------------------------------------------------------------------------

    @Test
    void theMenuBecomesRows() {
        Menu export = new Menu().addItem("PDF", () -> { }).addItem("PNG", () -> { });
        tray.setMenu(new Menu()
                .addItem("Open", () -> { })
                .add(MenuItem.of("Copy", () -> { }).setEnabled(false))
                .addSeparator()
                .addCheck("Bold", true, on -> { })
                .addSubmenu("Export", export));
        List<TrayRow> rows = peer.host.menu();
        assertEquals(List.of("Open", "Copy", "", "Bold", "Export"), rows.stream().map(TrayRow::label).toList());
        assertEquals(TrayRow.Kind.COMMAND, rows.get(0).kind());
        assertFalse(rows.get(1).enabled());
        assertEquals(TrayRow.Kind.SEPARATOR, rows.get(2).kind());
        assertEquals(TrayRow.Kind.CHECK, rows.get(3).kind());
        assertTrue(rows.get(3).checked());
        assertEquals(TrayRow.Kind.SUBMENU, rows.get(4).kind());
        assertEquals(List.of("PDF", "PNG"), rows.get(4).children().stream().map(TrayRow::label).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), rows.stream().map(TrayRow::id).toList());
        assertEquals(List.of(6, 7), rows.get(4).children().stream().map(TrayRow::id).toList());
    }

    @Test
    void noMenuIsNoRows() {
        assertTrue(peer.host.menu().isEmpty());
    }

    @Test
    void separatorsAreNeverFirstLastOrDoubled() {
        tray.setMenu(new Menu().addSeparator().addItem("A", () -> { }).addSeparator().addSeparator()
                .addItem("B", () -> { }).addSeparator());
        assertEquals(List.of(TrayRow.Kind.COMMAND, TrayRow.Kind.SEPARATOR, TrayRow.Kind.COMMAND),
                peer.host.menu().stream().map(TrayRow::kind).toList());
    }

    @Test
    void theMenuIsReadEachTimeItOpens() {
        Menu menu = new Menu();
        MenuItem bold = MenuItem.check("Bold", false, on -> { });
        menu.add(bold);
        tray.setMenu(menu);
        assertFalse(peer.host.menu().get(0).checked());
        bold.setChecked(true).setEnabled(false);
        menu.addItem("New", () -> { });
        List<TrayRow> rows = peer.host.menu();
        assertTrue(rows.get(0).checked());
        assertFalse(rows.get(0).enabled());
        assertEquals(2, rows.size());
    }

    @Test
    void choosingARowRunsItsItem() {
        List<String> ran = new ArrayList<>();
        Menu menu = new Menu()
                .addItem("Open", () -> ran.add("open"))
                .add(MenuItem.of("Off", () -> ran.add("off")).setEnabled(false))
                .addCheck("Bold", false, on -> ran.add("bold " + on))
                .addSubmenu("More", new Menu().addItem("Deep", () -> ran.add("deep")));
        tray.setMenu(menu);
        List<TrayRow> rows = peer.host.menu();
        peer.host.choose(rows.get(0).id());
        peer.host.choose(rows.get(1).id());
        peer.host.choose(rows.get(2).id());
        peer.host.choose(rows.get(3).id());
        peer.host.choose(rows.get(3).children().get(0).id());
        peer.host.choose(999);
        assertEquals(List.of("open", "bold true", "deep"), ran);
        assertTrue(peer.host.menu().get(2).checked(), "the check the user flipped is read back");
    }

    @Test
    void anItemInTwoPlacesIsTwoRows() {
        int[] runs = {0};
        MenuItem shared = MenuItem.of("Shared", () -> runs[0]++);
        tray.setMenu(new Menu().add(shared).addSubmenu("More", new Menu().add(shared)));
        List<TrayRow> rows = peer.host.menu();
        int first = rows.get(0).id();
        int second = rows.get(1).children().get(0).id();
        assertTrue(first != second);
        peer.host.choose(first);
        peer.host.choose(second);
        assertEquals(2, runs[0]);
    }

    @Test
    void aMenuThatContainsItselfIsRefusedAndOneThatComesToIsCut() {
        Menu outer = new Menu();
        Menu inner = new Menu().addItem("Leaf", () -> { });
        outer.addSubmenu("Inner", inner);
        tray.setMenu(outer);
        inner.addSubmenu("Back", outer);
        assertThrows(IllegalArgumentException.class, () -> tray.setMenu(outer));

        List<TrayRow> rows = peer.host.menu();
        TrayRow back = rows.get(0).children().get(1);
        assertEquals("Back", back.label());
        assertTrue(back.children().isEmpty());
        assertFalse(back.enabled(), "a submenu that opens nothing cannot be chosen");
    }

    @Test
    void anEmptySubmenuIsNotEnabled() {
        tray.setMenu(new Menu().addSubmenu("Empty", new Menu()));
        assertFalse(peer.host.menu().get(0).enabled());
    }

    // ---- availability and the end ------------------------------------------------------------

    @Test
    void availabilityIsThePeersAndItsChangeIsTold() {
        tray.setIcon(square(0, false));
        int[] told = {0};
        var subscription = tray.onAvailabilityChange(() -> told[0]++);
        peer.available = false;
        peer.host.availabilityChanged();
        assertFalse(tray.isAvailable());
        assertEquals(1, told[0]);
        subscription.cancel();
        peer.host.availabilityChanged();
        assertEquals(1, told[0]);
    }

    @Test
    void aDisposedTrayIsGoneAndDeaf() {
        int[] runs = {0};
        tray.setIcon(square(0, false)).onActivate(() -> runs[0]++).setMenu(new Menu().addItem("A", () -> runs[0]++));
        int sent = peer.states.size();
        tray.dispose();
        tray.dispose();
        assertEquals(1, peer.disposed);
        assertFalse(tray.isAvailable());
        tray.setTooltip("late");
        assertEquals(sent, peer.states.size());
        peer.host.activate();
        assertTrue(peer.host.menu().isEmpty());
        peer.host.choose(1);
        assertEquals(0, runs[0]);
    }

    @Test
    void anIdentifierIsShortPlainAscii() {
        for (String bad : new String[] {"", "two words", "ação", "a/b", "x".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> Tray.over(bad, host -> peer), bad);
        }
        Tray.over("Orders-2.main_1", host -> peer).dispose();
    }
}
