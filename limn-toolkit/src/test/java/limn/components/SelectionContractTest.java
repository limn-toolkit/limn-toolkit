package limn.components;

import limn.testfixtures.IndexedRows;

import limn.scene.Change;
import limn.scene.ChangeObserver;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one contract {@link ListView}, {@link TabbedPane}, {@link ComboBox},
 * {@link SegmentedControl} and {@link ButtonGroup} share, asserted against all five at once so
 * that a sixth widget cannot quietly answer {@code setSelectedIndex} its own way: an index that
 * is not a choice throws, a programmatic set reaches the watchers as {@code SELECTION}/{@code
 * CODE} and reaches no handler, and setting the index already held does neither.
 *
 * <p>The rule is the one on {@link Change.Origin}: the handler answers the user, and a watcher
 * hears everything. A two-way binding is therefore written on the watcher channel, and what
 * ends it is the last clause -- a mutator handed the state it already holds announces nothing --
 * which {@link #aTwoWayBindingSettlesInsteadOfRecursing} would otherwise turn into a
 * {@code StackOverflowError}. A binding through the handlers cannot recurse at all, because a
 * handler's writes are not user input and reach no second handler.
 *
 * <p>{@link ButtonGroup} is not a widget and announces nothing of its own: its change reaches
 * the channel through its members, the leaver's {@code VALUE} and then the enterer's. The
 * adapter below hears the group through the member that became selected, so one set is one
 * notification for every member of the family.
 */
class SelectionContractTest extends ComponentTestBase {

    /** Every widget here offers exactly this many choices, so one index means one thing. */
    private static final int CHOICES = 4;

    /** A member of the family, reached only through the operation all five spell the same way. */
    private interface Choice {
        String name();

        int selectedIndex();

        void setSelectedIndex(int index);

        void onSelect(IntConsumer listener);

        /** Watches the selection: one call per set, carrying the announced origin. */
        void observe(ChangeObserver observer);
    }

    /** A row/panel with a size and nothing else: these tests never lay anything out. */
    private static final class Plain extends Widget<Plain> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(20, 20);
        }
    }

    private static Choice listView() {
        ListView<Integer> list = IndexedRows.list(new IndexedRows() {
            @Override
            public int rowCount() {
                return CHOICES;
            }

            @Override
            public Widget<?> rowAt(int index) {
                return new Plain();
            }
        });
        return new Choice() {
            @Override
            public String name() {
                return "ListView";
            }

            @Override
            public int selectedIndex() {
                return list.selectedIndex();
            }

            @Override
            public void setSelectedIndex(int index) {
                list.setSelectedIndex(index);
            }

            @Override
            public void onSelect(IntConsumer listener) {
                list.onSelect(() -> listener.accept(list.selectedIndex()));
            }

            @Override
            public void observe(ChangeObserver observer) {
                list.observeChanges(selectionOnly(observer));
            }
        };
    }

    private static Choice tabbedPane() {
        TabbedPane tabs = new TabbedPane();
        for (int i = 0; i < CHOICES; i++) {
            tabs.addTab("T" + i, new Plain());
        }
        return new Choice() {
            @Override
            public String name() {
                return "TabbedPane";
            }

            @Override
            public int selectedIndex() {
                return tabs.selectedIndex();
            }

            @Override
            public void setSelectedIndex(int index) {
                tabs.setSelectedIndex(index);
            }

            @Override
            public void onSelect(IntConsumer listener) {
                tabs.onSelect(listener);
            }

            @Override
            public void observe(ChangeObserver observer) {
                tabs.observeChanges(selectionOnly(observer));
            }
        };
    }

    private static Choice comboBox() {
        ComboBox combo = new ComboBox(List.of("one", "two", "three", "four"));
        return new Choice() {
            @Override
            public String name() {
                return "ComboBox";
            }

            @Override
            public int selectedIndex() {
                return combo.selectedIndex();
            }

            @Override
            public void setSelectedIndex(int index) {
                combo.setSelectedIndex(index);
            }

            @Override
            public void onSelect(IntConsumer listener) {
                combo.onSelect(listener);
            }

            @Override
            public void observe(ChangeObserver observer) {
                combo.observeChanges(selectionOnly(observer));
            }
        };
    }

    private static Choice segmentedControl() {
        SegmentedControl seg = new SegmentedControl(List.of("A", "B", "C", "D"));
        return new Choice() {
            @Override
            public String name() {
                return "SegmentedControl";
            }

            @Override
            public int selectedIndex() {
                return seg.selectedIndex();
            }

            @Override
            public void setSelectedIndex(int index) {
                seg.setSelectedIndex(index);
            }

            @Override
            public void onSelect(IntConsumer listener) {
                seg.onSelect(listener);
            }

            @Override
            public void observe(ChangeObserver observer) {
                seg.observeChanges(selectionOnly(observer));
            }
        };
    }

    private static Choice buttonGroup() {
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < CHOICES; i++) {
            group.add(new RadioButton("R" + i));
        }
        return new Choice() {
            @Override
            public String name() {
                return "ButtonGroup";
            }

            @Override
            public int selectedIndex() {
                return group.selectedIndex();
            }

            @Override
            public void setSelectedIndex(int index) {
                group.setSelectedIndex(index);
            }

            @Override
            public void onSelect(IntConsumer listener) {
                group.onSelect(listener);
            }

            @Override
            public void observe(ChangeObserver observer) {
                // The group has no node; the member that became selected is where its change
                // lands, and it is the second of the two VALUEs a swap announces.
                for (RadioButton member : group.members()) {
                    member.observeChanges((source, change) -> {
                        if (change.aspect() == Change.Aspect.VALUE && member.isSelected()) {
                            observer.changed(source, change);
                        }
                    });
                }
            }
        };
    }

    /** Narrows a widget's watcher to its {@code SELECTION} changes. */
    private static ChangeObserver selectionOnly(ChangeObserver observer) {
        return (source, change) -> {
            if (change.aspect() == Change.Aspect.SELECTION) {
                observer.changed(source, change);
            }
        };
    }

    private static final List<Supplier<Choice>> FAMILY = List.of(
            SelectionContractTest::listView,
            SelectionContractTest::tabbedPane,
            SelectionContractTest::comboBox,
            SelectionContractTest::segmentedControl,
            SelectionContractTest::buttonGroup);

    /** A fresh instance of each widget: nothing here may inherit another case's selection. */
    private static List<Choice> family() {
        List<Choice> all = new ArrayList<>();
        for (Supplier<Choice> factory : FAMILY) {
            all.add(factory.get());
        }
        return all;
    }

    @Test
    void anIndexThatIsNotAChoiceThrows() {
        for (Choice choice : family()) {
            assertThrows(IndexOutOfBoundsException.class, () -> choice.setSelectedIndex(-1),
                    choice.name() + ": -1 is what an empty selection reads as, never what it is set"
                            + " with; clearSelection names that, where it exists at all");
            assertThrows(IndexOutOfBoundsException.class, () -> choice.setSelectedIndex(CHOICES),
                    choice.name() + ": one past the end is a caller's bug, not the last choice");
            assertThrows(IndexOutOfBoundsException.class,
                    () -> choice.setSelectedIndex(Integer.MAX_VALUE), choice.name());
        }
    }

    @Test
    void aRefusedIndexChangesNothingAndAnnouncesNothing() {
        for (Choice choice : family()) {
            choice.setSelectedIndex(1);
            AtomicInteger heard = new AtomicInteger();
            AtomicInteger handled = new AtomicInteger();
            choice.observe((source, change) -> heard.incrementAndGet());
            choice.onSelect(index -> handled.incrementAndGet());

            assertThrows(IndexOutOfBoundsException.class, () -> choice.setSelectedIndex(CHOICES));

            assertEquals(1, choice.selectedIndex(),
                    choice.name() + ": a throw is not a half-applied selection");
            assertEquals(0, heard.get(), choice.name() + ": nor an announcement");
            assertEquals(0, handled.get(), choice.name() + ": nor a handler call");
        }
    }

    @Test
    void aProgrammaticSetReachesTheWatchersAsCodeAndNotTheHandler() {
        for (Choice choice : family()) {
            choice.setSelectedIndex(0);
            List<Change.Origin> heard = new ArrayList<>();
            AtomicInteger handled = new AtomicInteger(-2);
            choice.observe((source, change) -> heard.add(change.origin()));
            choice.onSelect(handled::set);

            choice.setSelectedIndex(2);

            assertEquals(2, choice.selectedIndex(), choice.name());
            assertEquals(List.of(Change.Origin.CODE), heard, choice.name()
                    + ": a watcher hears every change, and this one was a caller's write");
            assertEquals(-2, handled.get(), choice.name()
                    + ": the handler is the application's response to the user, and no user"
                    + " operated this widget");
        }
    }

    /**
     * The guard on the announcement. It is not an optimization: it is what ends a two-way
     * binding written on the watcher channel, so a "simplification" that drops it must fail here
     * rather than in an application.
     */
    @Test
    void settingTheIndexAlreadyHeldAnnouncesNothing() {
        for (Choice choice : family()) {
            choice.setSelectedIndex(2);
            AtomicInteger heard = new AtomicInteger();
            choice.observe((source, change) -> heard.incrementAndGet());

            choice.setSelectedIndex(2);
            choice.setSelectedIndex(2);

            assertEquals(0, heard.get(), choice.name()
                    + ": re-setting the value already held must return before it announces");
            assertEquals(2, choice.selectedIndex(), choice.name());
        }
    }

    /**
     * Two controls wired to follow each other on the watcher channel: the shape a settings screen
     * with a strip and a list has. Without the unchanged-value early return this recurses on one
     * stack; the UI-thread rule does not help, because both entries are on that thread and nested
     * inside one call.
     */
    @Test
    void aTwoWayBindingSettlesInsteadOfRecursing() {
        for (Supplier<Choice> factory : FAMILY) {
            Choice a = factory.get();
            Choice b = factory.get();
            AtomicInteger heard = bind(a, b);

            a.setSelectedIndex(3);
            assertEquals(3, a.selectedIndex(), a.name() + " A");
            assertEquals(3, b.selectedIndex(), a.name() + " B followed");

            b.setSelectedIndex(1);
            assertEquals(1, a.selectedIndex(), a.name() + " A followed back");
            assertEquals(1, b.selectedIndex(), a.name() + " B");

            assertEquals(4, heard.get(), a.name()
                    + ": each write should cost one notification per control and stop; more means"
                    + " the echo is bouncing rather than dying on the first unchanged set");
        }
    }

    /** The same binding across two different widgets, which is the one an application writes. */
    @Test
    void aBindingBetweenTwoDifferentWidgetsSettlesToo() {
        Choice combo = comboBox();
        Choice strip = segmentedControl();
        AtomicInteger heard = bind(combo, strip);

        strip.setSelectedIndex(3);

        assertEquals(3, combo.selectedIndex(), "the combo followed the strip");
        assertEquals(3, strip.selectedIndex());
        assertEquals(2, heard.get(), "one notification each, then the echo found nothing to change");
    }

    /**
     * A binding through the handlers cannot bounce at all: a handler's write is not the user,
     * so it reaches the other control's watchers and never its handler.
     */
    @Test
    void aBindingThroughTheHandlersCannotEchoAtAll() {
        Choice combo = comboBox();
        Choice strip = segmentedControl();
        AtomicInteger handled = new AtomicInteger();
        combo.onSelect(index -> {
            handled.incrementAndGet();
            strip.setSelectedIndex(index);
        });
        strip.onSelect(index -> {
            handled.incrementAndGet();
            combo.setSelectedIndex(index);
        });

        strip.setSelectedIndex(3);

        assertEquals(3, strip.selectedIndex());
        assertEquals(0, combo.selectedIndex(), "nothing ran: the strip was written by code");
        assertEquals(0, handled.get());
    }

    /** Wires each control to write the other, counting every notification the pair produces. */
    private static AtomicInteger bind(Choice a, Choice b) {
        AtomicInteger heard = new AtomicInteger();
        a.observe((source, change) -> {
            heard.incrementAndGet();
            b.setSelectedIndex(a.selectedIndex());
        });
        b.observe((source, change) -> {
            heard.incrementAndGet();
            a.setSelectedIndex(b.selectedIndex());
        });
        return heard;
    }

    /**
     * The two widgets that can be empty refuse every index while they are, rather than inventing
     * a selection. The other three cannot reach the state: their constructors say so.
     */
    @Test
    void anEmptyWidgetHasNoIndexToSelect() {
        ListView<Integer> list = IndexedRows.list(new IndexedRows() {
            @Override
            public int rowCount() {
                return 0;
            }

            @Override
            public Widget<?> rowAt(int index) {
                throw new AssertionError("an empty adapter must never be asked for a row");
            }
        });
        assertThrows(IndexOutOfBoundsException.class, () -> list.setSelectedIndex(0));
        assertEquals(-1, list.selectedIndex(), "and an empty list simply has no selection");

        TabbedPane tabs = new TabbedPane();
        assertThrows(IndexOutOfBoundsException.class, () -> tabs.setSelectedIndex(0));
        assertEquals(-1, tabs.selectedIndex());

        assertThrows(IndexOutOfBoundsException.class,
                () -> new ButtonGroup().setSelectedIndex(0));

        assertThrows(IllegalArgumentException.class, () -> new ComboBox(List.of()),
                "a combo refuses to exist without an item, so it always has a selection");
        assertThrows(IllegalArgumentException.class, () -> new SegmentedControl(List.of()),
                "and so does a segmented control");
    }

    /**
     * Which widgets model "nothing is selected" at all. A list of records need not have a current
     * record and a radio group starts with nothing chosen; a tabbed pane holding tabs, a combo and
     * a segmented control always show exactly one thing, so they have no such state and offer no
     * way to ask for one.
     */
    @Test
    void onlyTheWidgetsWithANoSelectionStateCanBeCleared() {
        Choice list = listView();
        assertEquals(-1, list.selectedIndex(), "a fresh list has selected nothing");
        assertEquals(-1, buttonGroup().selectedIndex(), "nor has a fresh group");

        assertEquals(0, tabbedPane().selectedIndex(), "a pane with tabs shows one");
        assertEquals(0, comboBox().selectedIndex());
        assertEquals(0, segmentedControl().selectedIndex());

        assertTrue(hasClearSelection(ListView.class), "ListView names the empty state");
        assertTrue(hasClearSelection(ButtonGroup.class), "ButtonGroup names the empty state");
        assertTrue(!hasClearSelection(TabbedPane.class)
                        && !hasClearSelection(ComboBox.class)
                        && !hasClearSelection(SegmentedControl.class),
                "a widget that always has a selection must not offer to drop it");
    }

    private static boolean hasClearSelection(Class<?> type) {
        for (java.lang.reflect.Method method : type.getMethods()) {
            if (method.getName().equals("clearSelection") && method.getParameterCount() == 0) {
                return true;
            }
        }
        return false;
    }
}
