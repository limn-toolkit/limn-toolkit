package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.components.table.Column;
import limn.components.table.Table;
import limn.components.tree.Tree;
import limn.input.Keys;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a list, a table and a tree show while they hold no rows, and while they load: shown by
 * itself at zero rows and gone at one, in the rows' viewport with a table's header and footer kept,
 * after the widget in the Tab order, describing the widget to a reader and belonging to no
 * selection, busy and announced while loading, and repainting the widget and not the window.
 */
class EmptyContentTest extends AccessibleComponentTestBase {

    private final List<String> names = new ArrayList<>();

    @org.junit.jupiter.api.BeforeEach
    void inEnglish() {
        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
    }

    private ListView<String> list() {
        ListView<String> list = new ListView<>(Label::new);
        list.setItems(names);
        list.setAccessibleName("Contacts");
        return list;
    }

    private Table<String> table() {
        Column<String> name = Column.text("Customer", s -> s);
        name.footer("Total");
        Table<String> table = new Table<>(List.of(name));
        table.setRows(names);
        table.setAccessibleName("Orders");
        return table;
    }

    private Tree<String> folders() {
        Tree<String> tree = new Tree<>(new Tree.Model<>() {
            @Override
            public List<String> roots() {
                return names;
            }

            @Override
            public List<String> children(String node) {
                return List.of();
            }

            @Override
            public Widget<?> cellFor(String node) {
                return new Label(node);
            }
        });
        tree.setAccessibleName("Folders");
        return tree;
    }

    /** The empty content: a message and a button that clears the filter, as the Showcase has. */
    private record Empty(TokenColumn content, Label message, Button clear) {
        static Empty make() {
            Label message = new Label("No orders match");
            Button clear = new Button("Clear filter").setStyle(Button.Style.SECONDARY);
            TokenColumn content = new TokenColumn(Tokens.Role.SMALL);
            content.add(message);
            content.add(clear);
            return new Empty(content, message, clear);
        }
    }

    private void mount(Widget<?> rows) {
        bind(new Padding(Insets.all(20), new SizedBox(300, 200, rows)));
    }

    @Test
    void eachShowsItsContentAtZeroRowsAndLetsItGoAtOne() {
        for (int kind = 0; kind < 3; kind++) {
            names.clear();
            Widget<?> rows = kind == 0 ? list() : kind == 1 ? table() : folders();
            Empty empty = Empty.make();
            setEmpty(rows, empty);
            mount(rows);
            assertSame(rows, empty.content().parent(), kind + ": the content is the widget's child");
            assertSame(empty.content(), rows.children().get(rows.children().size() - 1),
                    kind + ": and its last");
            assertTrue(showingEmpty(rows), kind + ": showing at zero rows");
            assertTrue(empty.content().width() > 0, kind + ": laid out");

            names.add("Ana Lima");
            refresh(rows);
            frame();
            assertNull(empty.content().parent(), kind + ": gone at one row");
            assertFalse(showingEmpty(rows));

            names.clear();
            refresh(rows);
            frame();
            assertSame(rows, empty.content().parent(), kind + ": back when the rows go");
        }
    }

    @Test
    void eachPaintsItsContent() {
        // The three paint their children themselves, the rows they mounted: the content is painted
        // by them too, or it is laid out and published and never seen (the first prints).
        for (int kind = 0; kind < 3; kind++) {
            names.clear();
            Widget<?> rows = kind == 0 ? list() : kind == 1 ? table() : folders();
            Empty empty = Empty.make();
            setEmpty(rows, empty);
            Scene s = new Scene(new limn.scene.layout.SizedBox(300, 200, rows));
            s.setTextRuler(RULER);
            s.layoutPass(300, 200);
            List<String> drawn = new ArrayList<>();
            s.renderFrame(new FakeCanvas(300, 200) {
                @Override
                public void drawText(String text, float x, float y, limn.graphics.Font font,
                        limn.graphics.Paint paint) {
                    drawn.add(text);
                }
            });
            assertTrue(drawn.contains("No orders match"), kind + " painted its message: " + drawn);
            assertTrue(drawn.contains("Clear filter"), kind + " and its button: " + drawn);
        }
    }

    private static void setEmpty(Widget<?> rows, Empty empty) {
        if (rows instanceof ListView<?> l) {
            l.setEmptyContent(empty.content(), empty.message());
        } else if (rows instanceof Table<?> t) {
            t.setEmptyContent(empty.content(), empty.message());
        } else {
            ((Tree<?>) rows).setEmptyContent(empty.content(), empty.message());
        }
    }

    private static boolean showingEmpty(Widget<?> rows) {
        if (rows instanceof ListView<?> l) {
            return l.isShowingEmptyContent();
        } else if (rows instanceof Table<?> t) {
            return t.isShowingEmptyContent();
        }
        return ((Tree<?>) rows).isShowingEmptyContent();
    }

    private static void refresh(Widget<?> rows) {
        if (rows instanceof ListView<?> l) {
            l.refresh();
        } else if (rows instanceof Table<?> t) {
            t.refresh();
        } else {
            ((Tree<?>) rows).refresh();
        }
    }

    @Test
    void aTableKeepsItsHeaderAndFooterAndCentresTheContentInTheRowsBetween() {
        Table<String> table = table();
        Empty empty = Empty.make();
        table.setEmptyContent(empty.content(), empty.message());
        mount(table);
        Widget<?> content = empty.content();
        float header = Theme.current().tokensFor(table).controlHeight() / 2;
        assertTrue(content.y() > header, "below the header: " + content.y());
        assertTrue(content.y() + content.height() < table.height() - header, "above the footer");
        float viewportLeft = 0;
        assertEquals(table.width() / 2, content.x() + content.width() / 2, 1f, "centred across");
        assertTrue(content.x() >= viewportLeft + Theme.current().tokensFor(table).spacingLarge() - 0.01f,
                "spacingLarge kept from the edge");
    }

    @Test
    void theContentsControlsFollowTheWidgetInTheTabOrder() {
        ListView<String> list = list();
        Empty empty = Empty.make();
        list.setEmptyContent(empty.content(), empty.message());
        mount(list);
        scene.requestFocus(list);
        drive(scene).keyEvent(Keys.TAB, true, false, 0);
        drive(scene).keyEvent(Keys.TAB, false, false, 0);
        drive(scene).inputBatchEnded();
        assertSame(empty.clear(), scene.focusedWidget(), "Tab from the empty list reaches Clear filter");
    }

    @Test
    void theRowsComingBackUnderAFocusedButtonGiveTheKeyboardToTheWidget() {
        ListView<String> list = list();
        Empty empty = Empty.make();
        empty.clear().onAction(() -> {
            names.add("Ana Lima");
            list.refresh();
        });
        list.setEmptyContent(empty.content(), empty.message());
        mount(list);
        scene.requestFocus(empty.clear());
        drive(scene).keyEvent(Keys.SPACE, true, false, 0);
        drive(scene).keyEvent(Keys.SPACE, false, false, 0);
        drive(scene).inputBatchEnded();
        frame();
        assertNull(empty.content().parent(), "the content left with the empty state");
        assertSame(list, scene.focusedWidget(), "the keyboard is where the rows came back");
    }

    @Test
    void aReaderFindsTheWidgetDescribedByTheMessageAndTheContentAfterItsRowsInNoSelection() {
        Table<String> table = table();
        Empty empty = Empty.make();
        table.setEmptyContent(empty.content(), empty.message());
        mount(table);
        AccessibleNode node = node("Orders");
        assertEquals("No orders match", node.description(), "table, 0 rows, no orders match");
        AccessibleNode message = node("No orders match");
        boolean related = false;
        for (AccessibleRelation r : node.relations()) {
            related |= r.kind() == Accessible.Relation.DESCRIBED_BY && r.target() == message.id();
        }
        assertTrue(related, "DESCRIBED_BY the message: " + node.relations());
        AccessibleNode clear = node("Clear filter");
        assertEquals(Accessible.Role.BUTTON, clear.role());
        assertTrue(isUnder(clear, node), "published under the table");
        List<AccessibleNode> kids = childrenOf(node);
        assertTrue(isUnder(clear, kids.get(kids.size() - 1)) || kids.get(kids.size() - 1).id() == clear.id(),
                "after the rows, last: " + kids);
        assertFalse(clear.states().contains(Accessible.State.SELECTABLE), "no member of the selection");

        names.add("Ana Lima");
        table.refresh();
        frame();
        assertEquals("", node("Orders").description(), "a table with rows is described by nothing");
    }

    @Test
    void aContentWithNoMessageDescribesNothingAndAMessageAloneIsItsOwnContent() {
        ListView<String> list = list();
        list.setEmptyContent(new Button("Add a contact"));
        mount(list);
        assertEquals("", node("Contacts").description(), "no message given: no description");
        list.setEmptyMessage("No contacts yet");
        frame();
        assertTrue(list.isShowingEmptyContent());
        assertEquals("No contacts yet", node("Contacts").description());
        assertEquals(Accessible.Role.LABEL, node("No contacts yet").role());
        list.setEmptyMessage((String) null);
        frame();
        assertNull(list.emptyContent());
        assertFalse(list.isShowingEmptyContent());
    }

    private boolean isUnder(AccessibleNode node, AccessibleNode ancestor) {
        for (int at = node.parent(); at != AccessibleNode.NONE; at = tree().node(at).parent()) {
            if (tree().node(at).id() == ancestor.id()) {
                return true;
            }
        }
        return false;
    }

    private List<String> announced() {
        List<String> said = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.ANNOUNCEMENT) {
                said.add(String.valueOf(event.newValue()));
            }
        }
        return said;
    }

    @Test
    void aLoadShowsItsContentMarksTheWidgetBusyAndIsAnnouncedAtBothEnds() {
        Table<String> table = table();
        Empty empty = Empty.make();
        table.setEmptyContent(empty.content(), empty.message());
        mount(table);
        table.setLoading(true);
        frame();
        assertTrue(table.isShowingLoadingContent(), "the default loading content");
        assertFalse(table.isShowingEmptyContent(), "in place of the empty one");
        assertNull(empty.content().parent());
        AccessibleNode node = node("Orders");
        assertTrue(node.states().contains(Accessible.State.BUSY), "busy while it loads");
        assertEquals("Loading…", node.description(), "described by the loading line");
        assertEquals(List.of("Loading Orders"), announced());

        names.add("Ana Lima");
        names.add("Rui Costa");
        table.refresh();
        table.setLoading(false);
        frame();
        assertFalse(table.isShowingLoadingContent());
        assertFalse(node("Orders").states().contains(Accessible.State.BUSY));
        assertEquals(List.of("Loading Orders", "Orders, 2 items"), announced());
    }

    @Test
    void aLoadThatFindsNothingSaysTheEmptyMessage() {
        ListView<String> list = list();
        list.setEmptyMessage("No contacts match");
        mount(list);
        list.setLoading(true);
        frame();
        Label own = new Label("Fetching contacts");
        list.setLoadingContent(own, own);
        frame();
        assertSame(list, own.parent(), "the application's loading content replaces the default");
        assertEquals("Fetching contacts", node("Contacts").description());
        list.setLoading(false);
        frame();
        assertTrue(list.isShowingEmptyContent());
        assertEquals(List.of("Loading Contacts", "No contacts match"), announced());
    }

    @Test
    void aLoadOverRowsKeepsThemAndIsStillBusy() {
        names.add("Ana Lima");
        Tree<String> tree = folders();
        mount(tree);
        tree.setLoading(true);
        frame();
        assertFalse(tree.isShowingLoadingContent(), "the rows stay");
        assertTrue(node("Folders").states().contains(Accessible.State.BUSY));
    }

    @Test
    void emptyingAndRefillingRepaintsTheWidgetAndNotTheWindow() {
        ListView<String> list = list();
        list.setEmptyMessage("No contacts match");
        Label beside = new Label("Beside");
        TokenRow row = new TokenRow();
        row.add(new SizedBox(240, 160, list));
        row.add(beside);
        Scene s = new Scene(new Padding(Insets.all(40), row));
        s.setTextRuler(RULER);
        s.setPartialRendering(true);
        s.layoutPass(640, 480);
        for (int i = 0; i < 10; i++) {
            s.renderFrame(new RecordingTestCanvas(640, 480));
        }
        names.add("Ana Lima");
        list.refresh();
        RecordingTestCanvas filled = new RecordingTestCanvas(640, 480);
        s.renderFrame(filled);
        assertFalse(filled.cleared, "a row arriving is not a full frame");
        names.clear();
        list.refresh();
        RecordingTestCanvas emptied = new RecordingTestCanvas(640, 480);
        s.renderFrame(emptied);
        assertFalse(emptied.cleared, "the message coming back is not a full frame");
        assertTrue(list.isShowingEmptyContent());
        assertNotNull(beside.parent());
    }

    // ------------------------------------------------------------------ one parent, one widget

    private static void setLoadingContent(Widget<?> rows, Widget<?> content) {
        if (rows instanceof ListView<?> l) {
            l.setLoadingContent(content);
        } else if (rows instanceof Table<?> t) {
            t.setLoadingContent(content);
        } else {
            ((Tree<?>) rows).setLoadingContent(content);
        }
    }

    private static void setEmptyContent(Widget<?> rows, Widget<?> content) {
        if (rows instanceof ListView<?> l) {
            l.setEmptyContent(content);
        } else if (rows instanceof Table<?> t) {
            t.setEmptyContent(content);
        } else {
            ((Tree<?>) rows).setEmptyContent(content);
        }
    }

    /** The texts one frame of {@code root} draws. */
    private static List<String> drawnBy(Widget<?> root) {
        Scene s = new Scene(root);
        s.setTextRuler(RULER);
        s.layoutPass(600, 400);
        List<String> drawn = new ArrayList<>();
        s.renderFrame(new FakeCanvas(600, 400) {
            @Override
            public void drawText(String text, float x, float y, limn.graphics.Font font,
                    limn.graphics.Paint paint) {
                drawn.add(text);
            }
        });
        return drawn;
    }

    @Test
    void aContentAlreadyInAnotherContainerIsRefusedAtTheSetterAndDrawnOnce() {
        // The audit's scenario: a status label already on the page passed as the empty content was
        // accepted; the next frame threw inside the list's layout, and then the label was laid out
        // by both parents and drawn twice, every frame.
        for (int kind = 0; kind < 3; kind++) {
            for (boolean loadingContent : new boolean[] {false, true}) {
                names.clear();
                Widget<?> rows = kind == 0 ? list() : kind == 1 ? table() : folders();
                Label status = new Label("No contacts");
                TokenColumn page = new TokenColumn(Tokens.Role.SMALL);
                page.add(status);
                page.add(new SizedBox(300, 200, rows));
                String which = kind + (loadingContent ? " loading" : " empty");
                IllegalArgumentException refused = assertThrows(
                        IllegalArgumentException.class, () -> {
                            if (loadingContent) {
                                setLoadingContent(rows, status);
                            } else {
                                setEmptyContent(rows, status);
                            }
                        }, which);
                assertTrue(refused.getMessage().contains("container"), refused.getMessage());
                if (!loadingContent) {
                    assertNull(emptyContentOf(rows), which + ": nothing was set");
                }
                List<String> drawn = drawnBy(page);
                assertEquals(1, drawn.stream().filter("No contacts"::equals).count(), which + ": " + drawn);
                assertSame(page, status.parent(), which + ": the label stays where it was");
            }
        }
    }

    private static Widget<?> emptyContentOf(Widget<?> rows) {
        if (rows instanceof ListView<?> l) {
            return l.emptyContent();
        } else if (rows instanceof Table<?> t) {
            return t.emptyContent();
        }
        return ((Tree<?>) rows).emptyContent();
    }

    @Test
    void aContentNotShownMayGoToAFreshWidgetWhateverHappenedToTheOld() {
        // The review's case: a page rebuilt with a fresh list reuses the one "No contacts" label
        // the dropped list held while it had rows. A registry of holders refused it until the old
        // list was collected; whether a call throws must not depend on the garbage collector.
        names.add("Ana Lima");
        ListView<String> old = list();
        Label shared = new Label("No contacts");
        old.setEmptyContent(shared, shared);
        mount(old);
        assertNull(shared.parent(), "not shown: the list has a row");
        ListView<String> fresh = new ListView<>(Label::new);
        fresh.setEmptyContent(shared, shared);
        assertSame(shared, fresh.emptyContent());
        Table<String> table = table();
        table.setLoadingContent(shared); // free at this moment, so anyone's
    }

    @Test
    void aContentTwoWidgetsWantAtOnceIsShownByOneAndDrawnOnce() {
        // Given to two lists while neither showed it, then both empty in one window: the first to
        // lay out shows it, the other shows nothing, and no frame is lost.
        ListView<String> first = list();
        ListView<String> second = new ListView<>(Label::new);
        second.setItems(new ArrayList<>());
        Label shared = new Label("No contacts");
        first.setEmptyContent(shared, shared);
        second.setEmptyContent(shared, shared);
        TokenColumn page = new TokenColumn(Tokens.Role.SMALL);
        page.add(new SizedBox(300, 120, first));
        page.add(new SizedBox(300, 120, second));
        List<String> drawn = drawnBy(page);
        assertEquals(1, drawn.stream().filter("No contacts"::equals).count(), "drawn once: " + drawn);
        assertTrue(first.isShowingEmptyContent() != second.isShowingEmptyContent(), "shown by one");
    }

    @Test
    void theSameContentSetAgainOrAsBothEmptyAndLoadingIsTheWidgetsOwn() {
        ListView<String> list = list();
        Empty empty = Empty.make();
        list.setEmptyContent(empty.content(), empty.message());
        mount(list);
        assertSame(list, empty.content().parent(), "shown");
        list.setEmptyContent(empty.content(), empty.message()); // again, while it is the list's child
        list.setLoadingContent(empty.content());
        frame();
        assertSame(list, empty.content().parent());
    }

    @Test
    void aContentThatHoldsTheWidgetIsRefused() {
        ListView<String> list = list();
        TokenColumn around = new TokenColumn(Tokens.Role.SMALL);
        around.add(list);
        assertThrows(IllegalArgumentException.class,
                () -> list.setEmptyContent(around));
    }

    @Test
    void aContentTheApplicationPutElsewhereAfterIsNotShownAndTheFrameGoesOn() {
        // Set while free, then added to the page by the application: the list does not take it
        // from its parent, draws nothing of it, and loses no frame.
        names.add("Ana Lima");
        ListView<String> list = list();
        Label status = new Label("No contacts");
        list.setEmptyContent(status, status);
        TokenColumn page = new TokenColumn(Tokens.Role.SMALL);
        page.add(new SizedBox(300, 200, list));
        page.add(status);
        names.clear();
        list.refresh();
        List<String> drawn = drawnBy(page);
        assertEquals(1, drawn.stream().filter("No contacts"::equals).count(), "drawn once: " + drawn);
        assertSame(page, status.parent());
        assertFalse(list.isShowingEmptyContent(), "the list does not show what it does not hold");
    }

    // ------------------------------------------------------------------ what is said, and to whom

    @Test
    void aListThatIsNotShowingSaysNothingOfItsLoadAndStaysBusy() {
        // The audit's HiddenAnnounce: a list in a hidden container and one on a tab not selected,
        // both in the window, said "Loading Archived", "Loading Customers", "Archived, 0 items" and
        // "Customers, 0 items", loads the user could not see.
        ListView<String> archived = new ListView<>(Label::new);
        archived.setItems(new ArrayList<>());
        archived.setAccessibleName("Archived");
        TokenColumn hiddenBox = new TokenColumn(Tokens.Role.SMALL);
        hiddenBox.add(new SizedBox(200, 100, archived));
        hiddenBox.setVisible(false);
        ListView<String> customers = new ListView<>(Label::new);
        customers.setItems(new ArrayList<>());
        customers.setAccessibleName("Customers");
        TabbedPane tabs = new TabbedPane();
        tabs.addTab("Orders", new Label("orders page"));
        tabs.addTab("People", new SizedBox(200, 100, customers));
        TokenColumn root = new TokenColumn(Tokens.Role.SMALL);
        root.add(hiddenBox);
        root.add(new SizedBox(400, 200, tabs));
        bind(root);
        bridge.events.clear();

        archived.setLoading(true);
        customers.setLoading(true);
        frame();
        archived.setLoading(false);
        customers.setLoading(false);
        frame();
        assertEquals(List.of(), announced(), "neither is on screen");

        customers.setLoading(true);
        tabs.setSelectedIndex(1);
        frame();
        assertTrue(node("Customers").states().contains(Accessible.State.BUSY),
                "still busy when its page is chosen during the load");
        customers.setLoading(false);
        frame();
        assertEquals(List.of("Customers, 0 items"), announced(), "said once it shows");
    }

    @Test
    void aListAddedAndLoadedBeforeItsFirstFrameIsStillSaid() {
        // Not a test of the clip: a list just built inside a scroll view has no box yet when the
        // application starts its load in the same turn, and is on screen at the next frame.
        TokenColumn root = new TokenColumn(Tokens.Role.SMALL);
        bind(root);
        ListView<String> list = list();
        root.add(new SizedBox(300, 200, new ScrollView(list)));
        list.setLoading(true);
        frame();
        assertEquals(List.of("Loading Contacts"), announced());
    }

    @Test
    void aListNamedByABoundCaptionIsSaidByThatName() {
        // AX-4: the caption the accessibility guide keeps as the idiom named the list to a reader
        // and not its load ("Loading…", "2 items").
        ListView<String> list = new ListView<>(Label::new);
        list.setItems(names);
        Label caption = new Label("Contacts").setLabelFor(list);
        TokenColumn root = new TokenColumn(Tokens.Role.SMALL);
        root.add(caption);
        root.add(new SizedBox(300, 200, list));
        bind(root);
        list.setLoading(true);
        frame();
        names.add("Ana Lima");
        names.add("Rui Costa");
        list.refresh();
        list.setLoading(false);
        frame();
        assertEquals(List.of("Loading Contacts", "Contacts, 2 items"), announced());

        list.setAccessibleName("Address book"); // the name the application wrote wins, as in the tree
        list.setLoading(true);
        frame();
        assertEquals("Loading Address book", announced().get(2));
    }
}
