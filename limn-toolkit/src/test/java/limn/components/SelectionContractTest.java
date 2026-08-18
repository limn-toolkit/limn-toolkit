package limn.components;

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
 * is not a choice throws, a programmatic set fires the listener, and setting the index already
 * held does neither.
 *
 * <p>That last one is the load-bearing clause and the reason the second is safe. A single UI
 * thread rules out two <em>concurrent</em> entries into a widget and says nothing at all about
 * two <em>nested</em> ones: bind two of these controls to each other and A's listener writes B,
 * whose listener writes A, on one stack. What stops it is that the second write finds the value
 * already there and returns before firing. Remove the guard as a redundant optimization and a
 * two-way binding becomes a {@code StackOverflowError}, which is what
 * {@link #aTwoWayBindingSettlesInsteadOfRecursing} would then throw.
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
    }

    /** A row/panel with a size and nothing else: these tests never lay anything out. */
    private static final class Plain extends Widget {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(20, 20);
        }
    }

    private static Choice listView() {
        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return CHOICES;
            }

            @Override
            public Widget rowAt(int index) {
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
                list.onSelect(listener);
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
                tabs.onSelect(listener::accept);
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
                combo.onSelect(listener::accept);
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
                seg.onSelect(listener::accept);
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
                group.onSelect(listener::accept);
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
    void aRefusedIndexChangesNothingAndFiresNothing() {
        for (Choice choice : family()) {
            choice.setSelectedIndex(1);
            AtomicInteger fires = new AtomicInteger();
            choice.onSelect(index -> fires.incrementAndGet());

            assertThrows(IndexOutOfBoundsException.class, () -> choice.setSelectedIndex(CHOICES));

            assertEquals(1, choice.selectedIndex(),
                    choice.name() + ": a throw is not a half-applied selection");
            assertEquals(0, fires.get(), choice.name() + ": nor a listener call");
        }
    }

    @Test
    void aProgrammaticSetFiresTheListener() {
        for (Choice choice : family()) {
            choice.setSelectedIndex(0);
            AtomicInteger heard = new AtomicInteger(-2);
            choice.onSelect(heard::set);

            choice.setSelectedIndex(2);

            assertEquals(2, choice.selectedIndex(), choice.name());
            assertEquals(2, heard.get(), choice.name()
                    + ": a listener describes the selection, not the mouse; code and a click"
                    + " reach it by the same path");
        }
    }

    /**
     * The guard the always-echo rule rests on. It is not an optimization: it is the only thing
     * between a two-way binding and a stack overflow, so a "simplification" that drops it must
     * fail here rather than in an application.
     */
    @Test
    void settingTheIndexAlreadyHeldFiresNothing() {
        for (Choice choice : family()) {
            choice.setSelectedIndex(2);
            AtomicInteger fires = new AtomicInteger();
            choice.onSelect(index -> fires.incrementAndGet());

            choice.setSelectedIndex(2);
            choice.setSelectedIndex(2);

            assertEquals(0, fires.get(), choice.name()
                    + ": re-setting the value already held must return before it notifies");
            assertEquals(2, choice.selectedIndex(), choice.name());
        }
    }

    /**
     * Two controls wired to follow each other: the shape a settings screen with a strip and a
     * list has. Without the unchanged-value early return this recurses on one stack; the UI-thread
     * rule does not help, because both entries are on that thread and nested inside one call.
     */
    @Test
    void aTwoWayBindingSettlesInsteadOfRecursing() {
        for (Supplier<Choice> factory : FAMILY) {
            Choice a = factory.get();
            Choice b = factory.get();
            AtomicInteger fires = bind(a, b);

            a.setSelectedIndex(3);
            assertEquals(3, a.selectedIndex(), a.name() + " A");
            assertEquals(3, b.selectedIndex(), a.name() + " B followed");

            b.setSelectedIndex(1);
            assertEquals(1, a.selectedIndex(), a.name() + " A followed back");
            assertEquals(1, b.selectedIndex(), a.name() + " B");

            assertEquals(4, fires.get(), a.name()
                    + ": each write should cost one notification per control and stop; more means"
                    + " the echo is bouncing rather than dying on the first unchanged set");
        }
    }

    /** The same binding across two different widgets, which is the one an application writes. */
    @Test
    void aBindingBetweenTwoDifferentWidgetsSettlesToo() {
        Choice combo = comboBox();
        Choice strip = segmentedControl();
        AtomicInteger fires = bind(combo, strip);

        strip.setSelectedIndex(3);

        assertEquals(3, combo.selectedIndex(), "the combo followed the strip");
        assertEquals(3, strip.selectedIndex());
        assertEquals(2, fires.get(), "one notification each, then the echo found nothing to change");
    }

    /** Wires each control to write the other, counting every notification the pair produces. */
    private static AtomicInteger bind(Choice a, Choice b) {
        AtomicInteger fires = new AtomicInteger();
        a.onSelect(index -> {
            fires.incrementAndGet();
            b.setSelectedIndex(index);
        });
        b.onSelect(index -> {
            fires.incrementAndGet();
            a.setSelectedIndex(index);
        });
        return fires;
    }

    /**
     * The two widgets that can be empty refuse every index while they are, rather than inventing
     * a selection. The other three cannot reach the state: their constructors say so.
     */
    @Test
    void anEmptyWidgetHasNoIndexToSelect() {
        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return 0;
            }

            @Override
            public Widget rowAt(int index) {
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
