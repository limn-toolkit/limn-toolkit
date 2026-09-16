package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.ToggleFacet;
import limn.graphics.ShapedText;
import limn.i18n.I18nString;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every slot of every pattern interface this bridge serves, called the way a client's call arrives:
 * the {@code CallbackI} lambda {@link UiaPatternProviders#slotsFor} hands {@link UiaObject}, invoked
 * directly with a fake context and native out buffers (WINDOWS-NEW-13).
 *
 * <p><b>Why this exists.</b> Until 2026-09-15 no headless test called a single pattern slot: the
 * interface tables were pinned by {@link UiaInterfacesTest} and the pointers by
 * {@link UiaBridgeTest}, and what the slots wrote and posted was seen only by a guest run. The
 * boolean width (WINDOWS-NEW-11), the calendar's GetItem miss (WINDOWS-NEW-8), the collapsed
 * Select/AddToSelection (WINDOWS-NEW-9) and the unserved Selection interface (W1) all passed a green
 * build that way.
 *
 * <p><b>It first pinned what the slots did on 2026-09-15 (main at 048f7d0), wrong answers
 * included</b>, so that every later change to a slot moves a line here and says so in its commit.
 * A case whose pinned answer is a known defect names the item that changes it.
 *
 * <p>The fake context answers a simple element pointer for a node as {@link #SIMPLE} plus the node
 * id, and records every array and string it is asked to allocate, so what a slot handed back is
 * read without a COM object anywhere near it.
 */
class UiaPatternProvidersTest {

    /** What the fake context answers as node {@code n}'s simple pointer: this plus {@code n}. */
    private static final long SIMPLE = 0x5_0000_0000L;

    /** What it answers for the {@code i}th array or string it was asked to allocate. */
    private static final long ARRAY = 0x6_0000_0000L;
    private static final long STRING = 0x7_0000_0000L;

    /**
     * The slots this class has a case for, by interface. {@link #everySlotOfEveryServedInterfaceHasACase}
     * holds this equal to each served interface's own list, and {@link #slot} refuses a name that is
     * not here, so a slot an interface gains cannot go uncovered.
     */
    private static final Map<String, List<String>> COVERED = new LinkedHashMap<>();

    static {
        COVERED.put("IInvokeProvider", List.of("Invoke"));
        COVERED.put("IToggleProvider", List.of("Toggle", "get_ToggleState"));
        COVERED.put("IValueProvider", List.of("SetValue", "get_Value", "get_IsReadOnly"));
        COVERED.put("IRangeValueProvider", List.of("SetValue", "get_Value", "get_IsReadOnly",
                "get_Maximum", "get_Minimum", "get_LargeChange", "get_SmallChange"));
        COVERED.put("ISelectionProvider", List.of("GetSelection", "get_CanSelectMultiple",
                "get_IsSelectionRequired"));
        COVERED.put("IExpandCollapseProvider", List.of("Expand", "Collapse",
                "get_ExpandCollapseState"));
        COVERED.put("ISelectionItemProvider", List.of("Select", "AddToSelection",
                "RemoveFromSelection", "get_IsSelected", "get_SelectionContainer"));
        COVERED.put("IScrollItemProvider", List.of("ScrollIntoView"));
        COVERED.put("IScrollProvider", List.of("Scroll", "SetScrollPercent",
                "get_HorizontalScrollPercent", "get_VerticalScrollPercent", "get_HorizontalViewSize",
                "get_VerticalViewSize", "get_HorizontallyScrollable", "get_VerticallyScrollable"));
        COVERED.put("IGridProvider", List.of("GetItem", "get_RowCount", "get_ColumnCount"));
        COVERED.put("ITableProvider", List.of("GetRowHeaders", "GetColumnHeaders",
                "get_RowOrColumnMajor"));
        COVERED.put("IGridItemProvider", List.of("get_Row", "get_Column", "get_RowSpan",
                "get_ColumnSpan", "get_ContainingGrid"));
        COVERED.put("ITableItemProvider", List.of("GetRowHeaderItems", "GetColumnHeaderItems"));
    }

    /** Every {@code interface.slot} {@link #slot} handed a case during this class's run. */
    private static final java.util.Set<String> CALLED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Every test method of this class that has started during this run. */
    private static final java.util.Set<String> STARTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private AccessibleTree tree = AccessibleTree.EMPTY;
    private boolean accepting = true;
    private final List<String> posted = new ArrayList<>();
    private final List<long[]> arrays = new ArrayList<>();
    private final List<String> strings = new ArrayList<>();
    private final List<Long> buffers = new ArrayList<>();

    private final UiaProvider.Context context = new UiaProvider.Context() {
        @Override
        public AccessibleTree tree() {
            return tree;
        }

        @Override
        public long patternProviderFor(long nodeId, int patternId) {
            return 0;
        }

        @Override
        public long hostProvider() {
            return 0;
        }

        @Override
        public UiaStrings.Allocator strings() {
            return text -> {
                strings.add(text);
                return STRING + strings.size() - 1;
            };
        }

        @Override
        public long int32Array(int[] values) {
            return 0;
        }

        @Override
        public long unknownArray(long[] pointers) {
            arrays.add(pointers.clone());
            return ARRAY + arrays.size() - 1;
        }

        @Override
        public long elementFor(long nodeId) {
            return 0;
        }

        @Override
        public long simpleElementFor(long nodeId) {
            return tree.find(nodeId) == null ? 0 : SIMPLE + nodeId;
        }

        @Override
        public long rootElement() {
            return 0;
        }

        @Override
        public boolean requestFocus(long nodeId) {
            return false;
        }

        @Override
        public boolean perform(long nodeId, Accessible.Action action, Accessible.Argument arg) {
            posted.add(nodeId + " " + action + " " + arg);
            return accepting;
        }
    };

    @BeforeEach
    void publishTheFixture() {
        tree = fixture();
    }

    @AfterEach
    void freeTheBuffers() {
        buffers.forEach(MemoryUtil::nmemFree);
        buffers.clear();
    }

    /**
     * One window holding a node for every slot: the ids are what the cases name.
     *
     * <pre>
     * 1000 WINDOW
     *   1001 BUTTON [press]            1002 CHECK_BOX mixed [toggle]
     *   1003 TEXT_FIELD "draft"        1004 TEXT_FIELD "fixed" read-only
     *   1005 SPIN_BUTTON 7 "07"        1006 PROGRESS_BAR 40 read-only
     *   1007 COMBO_BOX expanded [collapse]
     *   1017 COMBO_BOX collapsed [expand]
     *   1014 BUTTON disabled (no verb)  1015 TEXT_FIELD "off" disabled, still editable
     *   1016 SPIN_BUTTON 9 "09" read-only value, enabled
     *   1018 LIST_ITEM disabled, no verb
     *   1008 LIST multi
     *     1009 LIST_ITEM selected 1/2 [deselect]
     *     1010 GROUP (a widget, not synthetic)
     *       1011 LIST_ITEM 2/2 [select, add to selection]
     *   1012 LIST_ITEM with no container [select]
     *   1013 LIST_ITEM with no verb
     *   1060 TABLE (a calendar grid) single, selection required
     *     synthetic ROW
     *       synthetic CELL "15" selected     synthetic CELL "16"
     *   1020 TABLE 2x2
     *     1033 GROUP (a toolbar)   1034 BUTTON
     *     1021 GROUP (the header)  1023 COLUMN_HEADER (-1,1)  1022 COLUMN_HEADER (-1,0)
     *     1024 ROW 1/2             1025 CELL (0,0)            1026 CELL (0,1)
     *     1027 ROW 7/9 (sorted)    1028 CELL (1,0)            1029 SWITCH (1,1), a widget cell
     *     1035 GROUP (the footer)  1036 CELL (-2,0)
     *   1030 TABLE 1x1, calendar-shaped: its ROW carries no position
     *     1031 ROW                 1032 CELL (0,0)
     *   1050 TABLE 1x1 (the outer)
     *     1051 ROW                 1052 TABLE 1x1 AND CELL (0,0): a table nested in a cell
     *                                1053 ROW     1054 CELL (0,0)
     *   1040 SCROLL_PANE v 25% of 50%, scrolls vertically only
     *     1041 BUTTON [scroll into view]
     *     1042 SCROLL_BAR vertical 75 [0..300] [increment, decrement]
     *     1046 BUTTON (no verb)
     *   1043 SCROLL_PANE h 50% of 40%, v 0% of 20%: both axes scroll
     *     1044 SCROLL_BAR horizontal, read-only, no verb (and no vertical bar at all)
     *   1045 SCROLL_PANE disabled, scrolls vertically
     *     1047 SCROLL_BAR vertical, disabled
     * </pre>
     */
    private static AccessibleTree fixture() {
        Accessibility a = new Accessibility();
        a.beginWalk(800, 600, Locale.ENGLISH);
        int window = node(a, 1000, AccessibleNode.NONE, Accessible.Role.WINDOW);
        node(a, 1001, window, Accessible.Role.BUTTON);
        a.action(Accessible.Action.PRESS);
        a.end();
        node(a, 1002, window, Accessible.Role.CHECK_BOX);
        a.toggle(ToggleFacet.State.MIXED);
        a.action(Accessible.Action.TOGGLE);
        a.end();
        node(a, 1003, window, Accessible.Role.TEXT_FIELD);
        a.state(Accessible.State.EDITABLE);
        a.text("draft", 1, 0, ShapedText.Affinity.DOWNSTREAM, 0, 0, 1, null, false);
        a.end();
        node(a, 1004, window, Accessible.Role.TEXT_FIELD);
        a.text("fixed", 2, 0, ShapedText.Affinity.DOWNSTREAM, 0, 0, 1, null, true);
        a.end();
        node(a, 1005, window, Accessible.Role.SPIN_BUTTON);
        a.value(7, 0, 10, 1);
        a.valueText("07", 3);
        a.end();
        node(a, 1006, window, Accessible.Role.PROGRESS_BAR);
        a.value(40, 0, 100, 5, true);
        a.end();
        node(a, 1007, window, Accessible.Role.COMBO_BOX);
        a.expand(true);
        a.action(Accessible.Action.COLLAPSE);
        a.end();
        node(a, 1017, window, Accessible.Role.COMBO_BOX);
        a.expand(false);
        a.action(Accessible.Action.EXPAND);
        a.end();
        node(a, 1014, window, Accessible.Role.BUTTON);
        a.inherited(false, true, true, false, false);
        a.end();
        node(a, 1015, window, Accessible.Role.TEXT_FIELD);
        a.state(Accessible.State.EDITABLE);
        a.text("off", 4, 0, ShapedText.Affinity.DOWNSTREAM, 0, 0, 1, null, false);
        a.inherited(false, true, true, false, false);
        a.end();
        node(a, 1016, window, Accessible.Role.SPIN_BUTTON);
        a.value(9, 0, 10, 1, true);
        a.valueText("09", 5);
        a.end();
        node(a, 1018, window, Accessible.Role.LIST_ITEM);
        a.selectionItem(false, 1, 1);
        a.inherited(false, true, true, false, false);
        a.end();
        int list = node(a, 1008, window, Accessible.Role.LIST);
        a.selection(true, false);
        node(a, 1009, list, Accessible.Role.LIST_ITEM);
        a.selectionItem(true, 1, 2);
        a.action(Accessible.Action.DESELECT);
        a.end();
        int padding = node(a, 1010, list, Accessible.Role.GROUP);
        node(a, 1011, padding, Accessible.Role.LIST_ITEM);
        a.selectionItem(false, 2, 2);
        a.action(Accessible.Action.SELECT, Accessible.Action.ADD_TO_SELECTION);
        a.end();
        a.end();
        a.end();
        node(a, 1012, window, Accessible.Role.LIST_ITEM);
        a.selectionItem(false, 1, 1);
        a.action(Accessible.Action.SELECT);
        a.end();
        node(a, 1013, window, Accessible.Role.LIST_ITEM);
        a.selectionItem(false, 1, 1);
        a.end();
        node(a, 1060, window, Accessible.Role.TABLE);
        a.selection(false, true);
        a.child(1);
        a.role(Accessible.Role.ROW);
        a.child(15);
        a.role(Accessible.Role.CELL);
        a.name(I18nString.literal("15"), Accessible.NameFrom.CONTENT);
        a.selectionItem(true, 15, 30);
        a.endChild();
        a.child(16);
        a.role(Accessible.Role.CELL);
        a.name(I18nString.literal("16"), Accessible.NameFrom.CONTENT);
        a.selectionItem(false, 16, 30);
        a.endChild();
        a.endChild();
        a.end();
        int table = node(a, 1020, window, Accessible.Role.TABLE);
        a.table(2, 2);
        int toolbar = node(a, 1033, table, Accessible.Role.GROUP);
        node(a, 1034, toolbar, Accessible.Role.BUTTON);
        a.end();
        a.end();
        int header = node(a, 1021, table, Accessible.Role.GROUP);
        cell(a, 1023, header, Accessible.Role.COLUMN_HEADER, -1, 1);
        cell(a, 1022, header, Accessible.Role.COLUMN_HEADER, -1, 0);
        a.end();
        int row0 = node(a, 1024, table, Accessible.Role.ROW);
        a.selectionItem(false, 1, 2);
        cell(a, 1025, row0, Accessible.Role.CELL, 0, 0);
        cell(a, 1026, row0, Accessible.Role.CELL, 0, 1);
        a.end();
        int row1 = node(a, 1027, table, Accessible.Role.ROW);
        a.selectionItem(false, 7, 9);
        cell(a, 1028, row1, Accessible.Role.CELL, 1, 0);
        cell(a, 1029, row1, Accessible.Role.SWITCH, 1, 1);
        a.end();
        int footer = node(a, 1035, table, Accessible.Role.GROUP);
        cell(a, 1036, footer, Accessible.Role.CELL, -2, 0);
        a.end();
        a.end();
        int calendar = node(a, 1030, window, Accessible.Role.TABLE);
        a.table(1, 1);
        int week = node(a, 1031, calendar, Accessible.Role.ROW);
        cell(a, 1032, week, Accessible.Role.CELL, 0, 0);
        a.end();
        a.end();
        // A table nested in a cell of another: the one shape where starting the climb at the cell
        // and starting it at the cell's parent disagree (semantics 2).
        int outer = node(a, 1050, window, Accessible.Role.TABLE);
        a.table(1, 1);
        int outerRow = node(a, 1051, outer, Accessible.Role.ROW);
        int inner = node(a, 1052, outerRow, Accessible.Role.TABLE);
        a.cell(0, 0);
        a.table(1, 1);
        int innerRow = node(a, 1053, inner, Accessible.Role.ROW);
        cell(a, 1054, innerRow, Accessible.Role.CELL, 0, 0);
        a.end();
        a.end();
        a.end();
        a.end();
        int pane = node(a, 1040, window, Accessible.Role.SCROLL_PANE);
        a.scroll(0, 0.25, 1, 0.5, false, true);
        node(a, 1041, pane, Accessible.Role.BUTTON);
        a.action(Accessible.Action.SCROLL_INTO_VIEW);
        a.end();
        node(a, 1042, pane, Accessible.Role.SCROLL_BAR);
        a.state(Accessible.State.VERTICAL);
        a.value(75, 0, 300, 100);
        a.action(Accessible.Action.INCREMENT, Accessible.Action.DECREMENT);
        a.end();
        node(a, 1046, pane, Accessible.Role.BUTTON);
        a.end();
        a.end();
        int wide = node(a, 1043, window, Accessible.Role.SCROLL_PANE);
        a.scroll(0.5, 0, 0.4, 0.2, true, true);
        node(a, 1044, wide, Accessible.Role.SCROLL_BAR);
        a.state(Accessible.State.HORIZONTAL);
        a.value(10, 0, 20, 5, true);
        a.end();
        a.end();
        int disabled = node(a, 1045, window, Accessible.Role.SCROLL_PANE);
        a.scroll(0, 0.5, 1, 0.5, false, true);
        a.inherited(false, true, true, false, false);
        node(a, 1047, disabled, Accessible.Role.SCROLL_BAR);
        a.state(Accessible.State.VERTICAL);
        a.value(0, 0, 100, 10);
        a.inherited(false, true, true, false, false);
        a.end();
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    /** Begins an enabled, showing node; the caller ends it. */
    private static int node(Accessibility a, long id, int parent, Accessible.Role role) {
        int index = a.begin(id, parent, Locale.ENGLISH, 0, 0, 10, 10);
        a.role(role);
        a.name(I18nString.literal("n" + id), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        return index;
    }

    private static void cell(Accessibility a, long id, int parent, Accessible.Role role, int row,
                             int column) {
        node(a, id, parent, role);
        a.cell(row, column);
        a.end();
    }

    // ---- calling a slot

    /** The slot, refused unless this class declares a case for it in {@link #COVERED}. */
    private CallbackI slot(int patternId, long nodeId, String name) {
        UiaInterfaces.Vtable iface = UiaPatternProviders.interfaceFor(patternId);
        assertNotNull(iface, "pattern " + patternId + " is not served");
        assertTrue(COVERED.getOrDefault(iface.name(), List.of()).contains(name),
                name + " of " + iface.name() + " is called here without being declared covered");
        Map<String, CallbackI> slots = UiaPatternProviders.slotsFor(patternId, nodeId, context);
        CallbackI slot = slots.get(name);
        assertNotNull(slot, iface.name() + " has no slot " + name);
        CALLED.add(iface.name() + "." + name);
        return slot;
    }

    private int verb(int patternId, long nodeId, String name) {
        return ((UiaCom.P) slot(patternId, nodeId, name)).invoke(0);
    }

    private int get(int patternId, long nodeId, String name, long out) {
        return ((UiaCom.PP) slot(patternId, nodeId, name)).invoke(0, out);
    }

    /** Sixteen bytes of 0xAA, so a slot that writes fewer than it should shows the rest. */
    private long buffer() {
        long out = MemoryUtil.nmemAllocChecked(16);
        MemoryUtil.memSet(out, 0xAA, 16);
        buffers.add(out);
        return out;
    }

    /** A {@code BSTR} holding {@code text}: the pointer past its four-byte length prefix. */
    private long bstr(String text) {
        long block = MemoryUtil.nmemAllocChecked(4 + 2L * text.length() + 2);
        buffers.add(block);
        MemoryUtil.memPutInt(block, text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            MemoryUtil.memPutShort(block + 4 + 2L * i, (short) text.charAt(i));
        }
        MemoryUtil.memPutShort(block + 4 + 2L * text.length(), (short) 0);
        return block + 4;
    }

    /** A {@code BOOL*} answer: four bytes of 1 or 0, and nothing written past them. */
    private static void assertBool(boolean expected, long out) {
        assertEquals(expected ? 1 : 0, MemoryUtil.memGetInt(out),
                "a four-byte BOOL, as the guest's own provider writes it");
        assertEquals(0xAAAAAAAA, MemoryUtil.memGetInt(out + 4), "and nothing past it");
    }

    /** The id the fixture's synthetic node named {@code name} was minted. */
    private long idNamed(String name) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).name().equals(name)) {
                return tree.node(i).id();
            }
        }
        throw new AssertionError("no node named " + name);
    }

    private void goneFromTheTree() {
        tree = AccessibleTree.EMPTY;
    }

    // ---- the ratchet

    @BeforeEach
    void noteTheCaseStarted(TestInfo info) {
        info.getTestMethod().ifPresent(method -> STARTED.add(method.getName()));
    }

    /**
     * The other half of {@link #everySlotOfEveryServedInterfaceHasACase}: a slot declared in
     * {@link #COVERED} must actually be handed to some case, or the declaration is a promise
     * nothing keeps (review of windows-A). Checked once every test method of this class has run,
     * so a run filtered to one method is not refused for the slots the others call.
     */
    @AfterAll
    static void everyDeclaredSlotWasCalledByACase() {
        List<String> all = java.util.Arrays.stream(UiaPatternProvidersTest.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class))
                .map(Method::getName).toList();
        if (!STARTED.containsAll(all)) {
            return;
        }
        List<String> declared = COVERED.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(slot -> entry.getKey() + "." + slot))
                .sorted().toList();
        assertEquals(declared, CALLED.stream().sorted().toList(),
                "every slot declared covered is called by a case, and no other");
    }

    @Test
    void everySlotOfEveryServedInterfaceHasACase() throws IllegalAccessException {
        List<String> served = new ArrayList<>();
        for (Field field : UiaIds.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !field.getName().endsWith("_PATTERN")) {
                continue;
            }
            int patternId = field.getInt(null);
            UiaInterfaces.Vtable iface = UiaPatternProviders.interfaceFor(patternId);
            if (iface == null) {
                continue;
            }
            served.add(iface.name());
            assertEquals(iface.slots().stream().sorted().toList(),
                    COVERED.getOrDefault(iface.name(), List.of()).stream().sorted().toList(),
                    iface.name() + ": every slot the guest reported has a case here");
            assertEquals(iface.slots().stream().sorted().toList(),
                    UiaPatternProviders.slotsFor(patternId, 1001, context).keySet().stream()
                            .sorted().toList(),
                    iface.name() + ": and slotsFor builds exactly those");
        }
        assertEquals(COVERED.keySet().stream().sorted().toList(), served.stream().sorted().toList(),
                "no case is declared for an interface this bridge does not serve");
    }

    // ---- Invoke

    /**
     * Semantics 5: PRESS is posted where published; a node that does not publish it is refused,
     * with UIA_E_ELEMENTNOTENABLED when it is not ENABLED (the platform's own ButtonAutomationPeer
     * answers that first, read as IL 2026-09-15) and 0x80131509 otherwise. Until 2026-09-15 it was
     * posted to any node a client held the pattern of.
     */
    @Test
    void invokePostsAPressOnlyWherePublishedAndAnswersNotAvailableWhenTheSceneRefuses() {
        assertEquals(UiaIds.S_OK, verb(UiaIds.INVOKE_PATTERN, 1001, "Invoke"));
        assertEquals(UiaIds.E_ELEMENT_NOT_ENABLED, verb(UiaIds.INVOKE_PATTERN, 1014, "Invoke"),
                "a disabled button, whose Invoke pattern a client may still hold");
        assertEquals(UiaIds.E_INVALID_OPERATION, verb(UiaIds.INVOKE_PATTERN, 1002, "Invoke"),
                "an enabled node that publishes no PRESS");
        assertEquals(List.of("1001 PRESS None[]"), posted);

        accepting = false;
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE, verb(UiaIds.INVOKE_PATTERN, 1001, "Invoke"),
                "a refusal from the scene reads as a node that has gone");
    }

    // ---- Toggle

    @Test
    void toggleIsPostedAndTheStateIsTheThreePlatformNumbersInFourBytes() {
        assertEquals(UiaIds.S_OK, verb(UiaIds.TOGGLE_PATTERN, 1002, "Toggle"));
        assertEquals(UiaIds.E_INVALID_OPERATION, verb(UiaIds.TOGGLE_PATTERN, 1001, "Toggle"),
                "semantics 5: no TOGGLE published, nothing posted");
        assertEquals(List.of("1002 TOGGLE None[]"), posted);

        long out = buffer();
        assertEquals(UiaIds.S_OK, get(UiaIds.TOGGLE_PATTERN, 1002, "get_ToggleState", out));
        assertEquals(2, MemoryUtil.memGetInt(out), "MIXED is the platform's Indeterminate, 2");
        assertEquals(0xAAAAAAAA, MemoryUtil.memGetInt(out + 4), "and nothing past the int");

        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.TOGGLE_PATTERN, 1001, "get_ToggleState", buffer()),
                "a node with no toggle facet");
        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.TOGGLE_PATTERN, 1002, "get_ToggleState", buffer()));
    }

    // ---- Value

    private int setValue(long nodeId, String text) {
        return ((UiaCom.PP) slot(UiaIds.VALUE_PATTERN, nodeId, "SetValue")).invoke(0, bstr(text));
    }

    /**
     * Semantics 5 and the addendum's setter bullets (CRIT-7): a text facet's SetValue is SET_TEXT,
     * a value facet's is SET_VALUE carrying the text; each is posted only where the node accepts it
     * now (AccessibleNode#accepts), refused with UIA_E_ELEMENTNOTENABLED on a node that is not
     * ENABLED and 0x80131509 on a read-only one. Until 2026-09-15 a value node's SetValue posted
     * SET_TEXT, which its widget refuses, and a read-only but enabled node's setter was posted.
     */
    @Test
    void valueSetValuePostsTheTextSetterOrTheValueByTextWhereTheNodeAcceptsIt() {
        assertEquals(UiaIds.S_OK, setValue(1003, "final"));
        assertEquals(UiaIds.S_OK, setValue(1005, "08"));
        assertEquals(List.of("1003 SET_TEXT OfText[text=final]", "1005 SET_VALUE OfText[text=08]"),
                posted);

        posted.clear();
        assertEquals(UiaIds.E_INVALID_OPERATION, setValue(1004, "x"), "a read-only text");
        assertEquals(UiaIds.E_INVALID_OPERATION, setValue(1016, "10"), "a read-only value");
        assertEquals(UiaIds.E_ELEMENT_NOT_ENABLED, setValue(1015, "on"),
                "a disabled field, which is not read-only");
        assertEquals(List.of(), posted);

        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                ((UiaCom.PP) slot(UiaIds.VALUE_PATTERN, 1003, "SetValue")).invoke(0, bstr("x")));
    }

    @Test
    void valueGetValueIsTheTextFacetsTextOrTheValueFacetsSpokenForm() {
        long out = buffer();
        assertEquals(UiaIds.S_OK, get(UiaIds.VALUE_PATTERN, 1003, "get_Value", out));
        assertEquals(STRING, MemoryUtil.memGetAddress(out));
        assertEquals(UiaIds.S_OK, get(UiaIds.VALUE_PATTERN, 1005, "get_Value", out));
        assertEquals(STRING + 1, MemoryUtil.memGetAddress(out));
        assertEquals(UiaIds.S_OK, get(UiaIds.VALUE_PATTERN, 1001, "get_Value", out));
        assertEquals(List.of("draft", "07", ""), strings, "a node with neither answers empty");

        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE, get(UiaIds.VALUE_PATTERN, 1003, "get_Value", out));
    }

    @Test
    void valueIsReadOnlyIsTheReadOnlyStateWrittenAsAFourByteBool() {
        long editable = buffer();
        long fixed = buffer();
        assertEquals(UiaIds.S_OK, get(UiaIds.VALUE_PATTERN, 1003, "get_IsReadOnly", editable));
        assertEquals(UiaIds.S_OK, get(UiaIds.VALUE_PATTERN, 1004, "get_IsReadOnly", fixed));
        // Four bytes of 0 or 1 (WINDOWS-NEW-11, read 2026-09-13); until 2026-09-15 the two bytes of
        // a VARIANT_BOOL, which read 0xAAAA0000 and 0xAAAAFFFF here.
        assertBool(false, editable);
        assertBool(true, fixed);
        // A value facet's writability (item 6 of the Windows brief): answered through the READ_ONLY
        // state, which the model derives from the facet's readOnly, so a read-only value reads true
        // and a disabled field, which is not read-only, false (ADR 039 §1.2).
        long writableValue = buffer();
        long fixedValue = buffer();
        long disabled = buffer();
        assertEquals(UiaIds.S_OK, get(UiaIds.VALUE_PATTERN, 1005, "get_IsReadOnly", writableValue));
        assertEquals(UiaIds.S_OK, get(UiaIds.VALUE_PATTERN, 1016, "get_IsReadOnly", fixedValue));
        assertEquals(UiaIds.S_OK, get(UiaIds.VALUE_PATTERN, 1015, "get_IsReadOnly", disabled));
        assertBool(false, writableValue);
        assertBool(true, fixedValue);
        assertBool(false, disabled);

        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.VALUE_PATTERN, 1003, "get_IsReadOnly", buffer()));
    }

    // ---- RangeValue

    /** Semantics 5: SET_VALUE where the value is writable and the node ENABLED, else refused. */
    @Test
    void rangeValueSetValuePostsTheNumberWhereTheNodeAcceptsIt() {
        assertEquals(UiaIds.S_OK,
                ((UiaCom.PD) slot(UiaIds.RANGE_VALUE_PATTERN, 1005, "SetValue")).invoke(0, 9));
        assertEquals(UiaIds.E_INVALID_OPERATION,
                ((UiaCom.PD) slot(UiaIds.RANGE_VALUE_PATTERN, 1006, "SetValue")).invoke(0, 9),
                "a read-only progress bar, enabled; until 2026-09-15 this was posted");
        assertEquals(List.of("1005 SET_VALUE OfValue[value=9.0]"), posted);

        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                ((UiaCom.PD) slot(UiaIds.RANGE_VALUE_PATTERN, 1005, "SetValue")).invoke(0, 9));
    }

    @Test
    void rangeValueGettersAreTheFacetsNumbersAndTheLargeChangeIsTheStep() {
        long out = buffer();
        String[] names = {"get_Value", "get_Minimum", "get_Maximum", "get_SmallChange",
                "get_LargeChange"};
        double[] expected = {40, 0, 100, 5, 5};
        for (int i = 0; i < names.length; i++) {
            assertEquals(UiaIds.S_OK, get(UiaIds.RANGE_VALUE_PATTERN, 1006, names[i], out), names[i]);
            assertEquals(expected[i], MemoryUtil.memGetDouble(out), names[i]);
        }
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.RANGE_VALUE_PATTERN, 1001, "get_Value", out), "no value facet");
        goneFromTheTree();
        for (String name : names) {
            assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                    get(UiaIds.RANGE_VALUE_PATTERN, 1006, name, out), name);
        }
    }

    @Test
    void rangeValueIsReadOnlyIsTheFacetsFlagWrittenAsAFourByteBool() {
        long writable = buffer();
        long fixed = buffer();
        assertEquals(UiaIds.S_OK, get(UiaIds.RANGE_VALUE_PATTERN, 1005, "get_IsReadOnly", writable));
        assertEquals(UiaIds.S_OK, get(UiaIds.RANGE_VALUE_PATTERN, 1006, "get_IsReadOnly", fixed));
        // WINDOWS-NEW-11, as Value's.
        assertBool(false, writable);
        assertBool(true, fixed);

        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.RANGE_VALUE_PATTERN, 1005, "get_IsReadOnly", buffer()));
    }

    // ---- ExpandCollapse

    /**
     * WINDOWS-NEW-10, semantics 5: Expand posts EXPAND and Collapse COLLAPSE only where the node
     * publishes that verb now, which a widget does by state; otherwise refused. Until 2026-09-15
     * both were posted whatever the node published.
     */
    @Test
    void expandAndCollapsePostTheVerbTheNodePublishesAndTheStateIsZeroOrOne() {
        assertEquals(UiaIds.S_OK, verb(UiaIds.EXPAND_COLLAPSE_PATTERN, 1007, "Collapse"));
        assertEquals(UiaIds.S_OK, verb(UiaIds.EXPAND_COLLAPSE_PATTERN, 1017, "Expand"));
        assertEquals(UiaIds.E_INVALID_OPERATION,
                verb(UiaIds.EXPAND_COLLAPSE_PATTERN, 1007, "Expand"), "already open: no EXPAND");
        assertEquals(UiaIds.E_INVALID_OPERATION,
                verb(UiaIds.EXPAND_COLLAPSE_PATTERN, 1017, "Collapse"), "closed: no COLLAPSE");
        assertEquals(UiaIds.E_ELEMENT_NOT_ENABLED,
                verb(UiaIds.EXPAND_COLLAPSE_PATTERN, 1014, "Expand"));
        assertEquals(List.of("1007 COLLAPSE None[]", "1017 EXPAND None[]"), posted);

        long out = buffer();
        assertEquals(UiaIds.S_OK,
                get(UiaIds.EXPAND_COLLAPSE_PATTERN, 1007, "get_ExpandCollapseState", out));
        assertEquals(1, MemoryUtil.memGetInt(out), "Expanded");
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.EXPAND_COLLAPSE_PATTERN, 1001, "get_ExpandCollapseState", out));
        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.EXPAND_COLLAPSE_PATTERN, 1007, "get_ExpandCollapseState", out));
    }

    // ---- SelectionItem

    /**
     * Decision 10 and semantics 5's candidate lists (WINDOWS-NEW-9): Select posts SELECT,
     * AddToSelection the first of [ADD_TO_SELECTION, SELECT] the node publishes, RemoveFromSelection
     * DESELECT; a node publishing none of its candidates is refused synchronously. Until
     * 2026-09-15 AddToSelection posted SELECT, which selects only that item, and every one of the
     * three was posted whatever the node published.
     */
    @Test
    void selectAddAndRemovePostTheFirstVerbOfTheirListTheNodePublishes() {
        assertEquals(UiaIds.S_OK, verb(UiaIds.SELECTION_ITEM_PATTERN, 1011, "Select"));
        assertEquals(UiaIds.S_OK, verb(UiaIds.SELECTION_ITEM_PATTERN, 1011, "AddToSelection"));
        assertEquals(UiaIds.S_OK, verb(UiaIds.SELECTION_ITEM_PATTERN, 1012, "AddToSelection"));
        assertEquals(UiaIds.S_OK, verb(UiaIds.SELECTION_ITEM_PATTERN, 1009, "RemoveFromSelection"));
        assertEquals(List.of("1011 SELECT None[]", "1011 ADD_TO_SELECTION None[]",
                "1012 SELECT None[]", "1009 DESELECT None[]"), posted,
                "add is ADD_TO_SELECTION where offered and a click where the container has only that");

        posted.clear();
        assertEquals(UiaIds.E_ELEMENT_NOT_ENABLED,
                verb(UiaIds.SELECTION_ITEM_PATTERN, 1018, "Select"),
                "a disabled item: not enabled is answered first (read as IL 2026-09-15)");
        assertEquals(UiaIds.E_INVALID_OPERATION,
                verb(UiaIds.SELECTION_ITEM_PATTERN, 1013, "Select"));
        assertEquals(UiaIds.E_INVALID_OPERATION,
                verb(UiaIds.SELECTION_ITEM_PATTERN, 1013, "AddToSelection"));
        assertEquals(UiaIds.E_INVALID_OPERATION,
                verb(UiaIds.SELECTION_ITEM_PATTERN, 1011, "RemoveFromSelection"));
        assertEquals(List.of(), posted, "a verb the node does not publish is never posted");

        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                verb(UiaIds.SELECTION_ITEM_PATTERN, 1011, "Select"));
    }

    @Test
    void isSelectedIsTheFacetsFlagWrittenAsAFourByteBool() {
        long selected = buffer();
        long not = buffer();
        assertEquals(UiaIds.S_OK, get(UiaIds.SELECTION_ITEM_PATTERN, 1009, "get_IsSelected", selected));
        assertEquals(UiaIds.S_OK, get(UiaIds.SELECTION_ITEM_PATTERN, 1011, "get_IsSelected", not));
        // WINDOWS-NEW-11.
        assertBool(true, selected);
        assertBool(false, not);
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.SELECTION_ITEM_PATTERN, 1001, "get_IsSelected", buffer()));
    }

    /**
     * Semantics 1: the container resolved at publish, climbed to through synthetic ancestors only.
     * Until 2026-09-15 this climbed through any ancestor, and answered the list for 1011 under a
     * widget group, which the model's SELECTION_CHANGED and the other bridges do not.
     */
    @Test
    void theSelectionContainerIsTheOneResolvedAtPublishAndNullWhenNone() {
        long out = buffer();
        assertEquals(UiaIds.S_OK,
                get(UiaIds.SELECTION_ITEM_PATTERN, 1009, "get_SelectionContainer", out));
        assertEquals(SIMPLE + 1008, MemoryUtil.memGetAddress(out));
        assertEquals(UiaIds.S_OK,
                get(UiaIds.SELECTION_ITEM_PATTERN, idNamed("15"), "get_SelectionContainer", out));
        assertEquals(SIMPLE + 1060, MemoryUtil.memGetAddress(out),
                "a day's grid, past the synthetic week row");
        assertEquals(UiaIds.S_OK,
                get(UiaIds.SELECTION_ITEM_PATTERN, 1011, "get_SelectionContainer", out));
        assertEquals(0L, MemoryUtil.memGetAddress(out),
                "a climb that meets a widget first finds no container");
        assertEquals(UiaIds.S_OK,
                get(UiaIds.SELECTION_ITEM_PATTERN, 1012, "get_SelectionContainer", out));
        assertEquals(0L, MemoryUtil.memGetAddress(out), "an item under no container names none");

        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.SELECTION_ITEM_PATTERN, 1011, "get_SelectionContainer", out));
    }

    // ---- Selection

    /**
     * W1's Selection half (semantics 1): GetSelection lists the realized selected members whose
     * container is this one, as a SAFEARRAY of simple pointers (GetColumnHeaders' shape); the two
     * flags are four-byte BOOLs. Until 2026-09-15 the interface was claimed and never served.
     */
    @Test
    void getSelectionListsTheContainersOwnSelectedMembersAndTheFlagsAreBools() {
        long out = buffer();
        assertEquals(UiaIds.S_OK, get(UiaIds.SELECTION_PATTERN, 1008, "GetSelection", out));
        assertEquals(ARRAY, MemoryUtil.memGetAddress(out));
        assertArrayEquals(new long[] {SIMPLE + 1009}, arrays.get(0),
                "the list's own selected row; 1011 is not its member");
        assertEquals(UiaIds.S_OK, get(UiaIds.SELECTION_PATTERN, 1060, "GetSelection", out));
        assertArrayEquals(new long[] {SIMPLE + idNamed("15")}, arrays.get(1),
                "the grid's selected day, found past its synthetic row");

        long multi = buffer();
        long required = buffer();
        assertEquals(UiaIds.S_OK, get(UiaIds.SELECTION_PATTERN, 1008, "get_CanSelectMultiple", multi));
        assertEquals(UiaIds.S_OK,
                get(UiaIds.SELECTION_PATTERN, 1008, "get_IsSelectionRequired", required));
        assertBool(true, multi);
        assertBool(false, required);
        assertEquals(UiaIds.S_OK, get(UiaIds.SELECTION_PATTERN, 1060, "get_CanSelectMultiple", multi));
        assertEquals(UiaIds.S_OK,
                get(UiaIds.SELECTION_PATTERN, 1060, "get_IsSelectionRequired", required));
        assertBool(false, multi);
        assertBool(true, required);

        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.SELECTION_PATTERN, 1001, "GetSelection", out), "no selection facet");
        goneFromTheTree();
        for (String name : List.of("GetSelection", "get_CanSelectMultiple",
                "get_IsSelectionRequired")) {
            assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                    get(UiaIds.SELECTION_PATTERN, 1008, name, buffer()), name);
        }
    }

    // ---- ScrollItem

    /**
     * Semantics 5 (WINDOWS-NEW-7): posted where the node publishes the verb, refused where it does
     * not. Until 2026-09-15 it was posted for any node, the one under a scroll pane with no verb
     * included, and the client was told S_OK for a reveal its widget refused.
     */
    @Test
    void scrollIntoViewIsPostedOnlyWhereTheNodePublishesIt() {
        assertEquals(UiaIds.S_OK, verb(UiaIds.SCROLL_ITEM_PATTERN, 1041, "ScrollIntoView"));
        assertEquals(List.of("1041 SCROLL_INTO_VIEW None[]"), posted);

        posted.clear();
        assertEquals(UiaIds.E_INVALID_OPERATION,
                verb(UiaIds.SCROLL_ITEM_PATTERN, 1046, "ScrollIntoView"));
        assertEquals(UiaIds.E_ELEMENT_NOT_ENABLED,
                verb(UiaIds.SCROLL_ITEM_PATTERN, 1018, "ScrollIntoView"),
                "a disabled item: not enabled first, as the platform's client-side ListViewItem and "
                        + "WindowsTabItem ScrollIntoView answer (read as IL 2026-09-15)");
        assertEquals(List.of(), posted);
        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                verb(UiaIds.SCROLL_ITEM_PATTERN, 1041, "ScrollIntoView"));
    }

    // ---- Scroll

    private double number(int patternId, long nodeId, String name) {
        long out = buffer();
        assertEquals(UiaIds.S_OK, get(patternId, nodeId, name, out), name);
        return MemoryUtil.memGetDouble(out);
    }

    private int scroll(long nodeId, int horizontal, int vertical) {
        return ((UiaCom.PII) slot(UiaIds.SCROLL_PATTERN, nodeId, "Scroll"))
                .invoke(0, horizontal, vertical);
    }

    private int scrollPercent(long nodeId, double horizontal, double vertical) {
        return ((UiaCom.PDD) slot(UiaIds.SCROLL_PATTERN, nodeId, "SetScrollPercent"))
                .invoke(0, horizontal, vertical);
    }

    /**
     * W1's Scroll half: the getters are the facet's, as percents, and NoScroll (-1, read
     * 2026-09-13) on an axis that cannot scroll, which is what the platform's own
     * ScrollViewerAutomationPeer answers (read as IL 2026-09-15); a view size is a percent on
     * either axis. Until 2026-09-15 the interface was claimed and never served.
     */
    @Test
    void theScrollGettersArePercentsAndNoScrollWhereAnAxisCannotScroll() {
        assertEquals(-1.0, number(UiaIds.SCROLL_PATTERN, 1040, "get_HorizontalScrollPercent"),
                "NoScroll: the pane does not scroll sideways");
        assertEquals(25.0, number(UiaIds.SCROLL_PATTERN, 1040, "get_VerticalScrollPercent"));
        assertEquals(100.0, number(UiaIds.SCROLL_PATTERN, 1040, "get_HorizontalViewSize"),
                "all of the width is shown");
        assertEquals(50.0, number(UiaIds.SCROLL_PATTERN, 1040, "get_VerticalViewSize"));
        assertEquals(50.0, number(UiaIds.SCROLL_PATTERN, 1043, "get_HorizontalScrollPercent"));
        assertEquals(0.0, number(UiaIds.SCROLL_PATTERN, 1043, "get_VerticalScrollPercent"),
                "an axis that scrolls and sits at its start is 0, not NoScroll");
        assertEquals(40.0, number(UiaIds.SCROLL_PATTERN, 1043, "get_HorizontalViewSize"), 1e-9);

        long horizontally = buffer();
        long vertically = buffer();
        assertEquals(UiaIds.S_OK,
                get(UiaIds.SCROLL_PATTERN, 1040, "get_HorizontallyScrollable", horizontally));
        assertEquals(UiaIds.S_OK,
                get(UiaIds.SCROLL_PATTERN, 1040, "get_VerticallyScrollable", vertically));
        assertBool(false, horizontally);
        assertBool(true, vertically);

        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.SCROLL_PATTERN, 1001, "get_VerticalScrollPercent", buffer()),
                "a node with no scroll facet");
        goneFromTheTree();
        for (String name : List.of("get_HorizontalScrollPercent", "get_VerticalScrollPercent",
                "get_HorizontalViewSize", "get_VerticalViewSize", "get_HorizontallyScrollable",
                "get_VerticallyScrollable")) {
            assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                    get(UiaIds.SCROLL_PATTERN, 1040, name, buffer()), name);
        }
    }

    /**
     * Decision 39: a small step is the axis's scroll bar's INCREMENT or DECREMENT, posted on the
     * bar; a large step has no page verb to go to and is refused; NoAmount leaves the axis; an
     * axis with no bar, or a bar publishing no such verb, is refused with 0x80131509 and nothing
     * is posted for either axis; a pane that is not enabled answers UIA_E_ELEMENTNOTENABLED first.
     */
    @Test
    void scrollPostsTheBarsSteppingVerbAndRefusesWhatNoBarPublishes() {
        assertEquals(UiaIds.S_OK, scroll(1040, UiaIds.SCROLL_AMOUNT_NO_AMOUNT,
                UiaIds.SCROLL_AMOUNT_SMALL_INCREMENT));
        assertEquals(UiaIds.S_OK, scroll(1040, UiaIds.SCROLL_AMOUNT_NO_AMOUNT,
                UiaIds.SCROLL_AMOUNT_SMALL_DECREMENT));
        assertEquals(UiaIds.S_OK, scroll(1040, UiaIds.SCROLL_AMOUNT_NO_AMOUNT,
                UiaIds.SCROLL_AMOUNT_NO_AMOUNT));
        assertEquals(List.of("1042 INCREMENT None[]", "1042 DECREMENT None[]"), posted,
                "on the vertical bar, and nothing at all for NoAmount");

        posted.clear();
        assertEquals(UiaIds.E_INVALID_OPERATION, scroll(1040, UiaIds.SCROLL_AMOUNT_NO_AMOUNT,
                UiaIds.SCROLL_AMOUNT_LARGE_INCREMENT), "no page verb exists to route a large step to");
        assertEquals(UiaIds.E_INVALID_OPERATION, scroll(1040, UiaIds.SCROLL_AMOUNT_SMALL_INCREMENT,
                UiaIds.SCROLL_AMOUNT_SMALL_INCREMENT), "the pane cannot scroll sideways");
        assertEquals(UiaIds.E_INVALID_OPERATION, scroll(1040, UiaIds.SCROLL_AMOUNT_NO_AMOUNT, 9),
                "not an amount");
        assertEquals(UiaIds.E_INVALID_OPERATION, scroll(1043, UiaIds.SCROLL_AMOUNT_SMALL_INCREMENT,
                UiaIds.SCROLL_AMOUNT_NO_AMOUNT), "the horizontal bar publishes no verb");
        assertEquals(UiaIds.E_INVALID_OPERATION, scroll(1043, UiaIds.SCROLL_AMOUNT_NO_AMOUNT,
                UiaIds.SCROLL_AMOUNT_SMALL_INCREMENT), "no vertical bar at all");
        assertEquals(UiaIds.E_ELEMENT_NOT_ENABLED, scroll(1045, UiaIds.SCROLL_AMOUNT_SMALL_INCREMENT,
                UiaIds.SCROLL_AMOUNT_SMALL_INCREMENT), "not enabled comes before every other refusal");
        assertEquals(List.of(), posted, "a refused call posts nothing for either axis");

        accepting = false;
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE, scroll(1040, UiaIds.SCROLL_AMOUNT_NO_AMOUNT,
                UiaIds.SCROLL_AMOUNT_SMALL_INCREMENT), "a refusal from the scene");
        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE, scroll(1040, UiaIds.SCROLL_AMOUNT_NO_AMOUNT,
                UiaIds.SCROLL_AMOUNT_SMALL_INCREMENT));
    }

    /**
     * Decision 39: a percent is a SET_VALUE on the axis's bar, its own range scaled; NoScroll leaves
     * the axis; the refusals come in the platform provider's order (read as IL 2026-09-15): not
     * enabled, an axis that cannot scroll, a percent outside 0..100 (0x80131502), then no bar or a
     * bar that accepts no value.
     */
    @Test
    void setScrollPercentPostsTheBarsValueAndRefusesInThePlatformsOrder() {
        assertEquals(UiaIds.S_OK, scrollPercent(1040, UiaIds.SCROLL_NO_SCROLL, 50));
        assertEquals(UiaIds.S_OK, scrollPercent(1040, UiaIds.SCROLL_NO_SCROLL, 100));
        assertEquals(List.of("1042 SET_VALUE OfValue[value=150.0]",
                "1042 SET_VALUE OfValue[value=300.0]"), posted, "half and all of 0..300");

        posted.clear();
        assertEquals(UiaIds.S_OK, scrollPercent(1040, UiaIds.SCROLL_NO_SCROLL,
                UiaIds.SCROLL_NO_SCROLL), "NoScroll on both is nothing to do");
        assertEquals(UiaIds.E_INVALID_OPERATION, scrollPercent(1040, 10, 10),
                "the pane cannot scroll sideways");
        assertEquals(UiaIds.E_INVALID_OPERATION, scrollPercent(1040, 10, 200),
                "an axis that cannot scroll is refused before a percent out of range");
        assertEquals(UiaIds.E_ARGUMENT_OUT_OF_RANGE,
                scrollPercent(1040, UiaIds.SCROLL_NO_SCROLL, 100.5));
        assertEquals(UiaIds.E_ARGUMENT_OUT_OF_RANGE,
                scrollPercent(1040, UiaIds.SCROLL_NO_SCROLL, -0.5));
        assertEquals(UiaIds.E_ARGUMENT_OUT_OF_RANGE,
                scrollPercent(1040, UiaIds.SCROLL_NO_SCROLL, Double.NaN));
        assertEquals(UiaIds.E_INVALID_OPERATION, scrollPercent(1043, 50, UiaIds.SCROLL_NO_SCROLL),
                "the horizontal bar's value is read-only");
        assertEquals(UiaIds.E_INVALID_OPERATION, scrollPercent(1043, UiaIds.SCROLL_NO_SCROLL, 50),
                "no vertical bar at all");
        assertEquals(UiaIds.E_ELEMENT_NOT_ENABLED, scrollPercent(1045, 500, 50),
                "not enabled comes first");
        assertEquals(List.of(), posted, "a refused call posts nothing for either axis");

        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                scrollPercent(1040, UiaIds.SCROLL_NO_SCROLL, 50));
    }

    // ---- Grid and Table

    private long getItem(long table, int row, int column) {
        long out = buffer();
        assertEquals(UiaIds.S_OK,
                ((UiaCom.PIIP) slot(UiaIds.GRID_PATTERN, table, "GetItem")).invoke(0, row, column, out));
        return MemoryUtil.memGetAddress(out);
    }

    /**
     * Semantics 2 (decision 8; WINDOWS-NEW-8): a cell is the node whose CellFacet is the pair, under
     * the table's rows, whatever the row's position in set says; a widget cell under its row is
     * found like a synthetic one; a calendar's week row, which carries no position, is searched
     * too. Until 2026-09-15 a row was matched by its position (row + 1), so the calendar answered no
     * day and a sorted row's position named the wrong one.
     */
    @Test
    void getItemFindsTheCellByItsCellFacetUnderTheTablesRows() {
        assertEquals(SIMPLE + 1028, getItem(1020, 1, 0), "row 1, whose row publishes position 7");
        assertEquals(SIMPLE + 1029, getItem(1020, 1, 1), "a widget cell under its row");
        assertEquals(SIMPLE + 1025, getItem(1020, 0, 0));
        assertEquals(0L, getItem(1020, 5, 0), "an unrealized row answers null");
        assertEquals(0L, getItem(1020, -1, 0), "a header is not a grid item");
        assertEquals(0L, getItem(1020, -2, 0), "nor a footer");
        assertEquals(SIMPLE + 1032, getItem(1030, 0, 0),
                "a calendar-shaped grid's cell, under a row with no position");
        long out = buffer();

        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                ((UiaCom.PIIP) slot(UiaIds.GRID_PATTERN, 1001, "GetItem")).invoke(0, 0, 0, out),
                "a node with no table facet");
    }

    @Test
    void theGridCountsAreTheTableFacets() {
        long out = buffer();
        assertEquals(UiaIds.S_OK, get(UiaIds.GRID_PATTERN, 1020, "get_RowCount", out));
        assertEquals(2, MemoryUtil.memGetInt(out));
        assertEquals(UiaIds.S_OK, get(UiaIds.GRID_PATTERN, 1030, "get_ColumnCount", out));
        assertEquals(1, MemoryUtil.memGetInt(out));
        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE, get(UiaIds.GRID_PATTERN, 1020, "get_RowCount", out));
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.GRID_PATTERN, 1020, "get_ColumnCount", out));
    }

    /**
     * Semantics 3 (the settled header-group rule; TABLE-NEW-11): the column headers are the
     * CellFacet(-1, c) children of the table's direct groups, in reading order; a group before the
     * header group (a toolbar) and the footer's (-2, c) cells are not headers. Until 2026-09-15 the
     * children of the first group were answered, which here would be the toolbar's button.
     */
    @Test
    void theTableAnswersNoRowHeadersTheHeaderCellsOfItsGroupsAndRowMajor() {
        long out = buffer();
        assertEquals(UiaIds.S_OK, get(UiaIds.TABLE_PATTERN, 1020, "GetRowHeaders", out));
        assertEquals(ARRAY, MemoryUtil.memGetAddress(out));
        assertEquals(UiaIds.S_OK, get(UiaIds.TABLE_PATTERN, 1020, "GetColumnHeaders", out));
        assertEquals(ARRAY + 1, MemoryUtil.memGetAddress(out));
        assertArrayEquals(new long[0], arrays.get(0));
        assertArrayEquals(new long[] {SIMPLE + 1023, SIMPLE + 1022}, arrays.get(1),
                "the header cells in reading order, and nothing of the toolbar or the footer");
        assertEquals(UiaIds.S_OK, get(UiaIds.TABLE_PATTERN, 1020, "get_RowOrColumnMajor", out));
        assertEquals(UiaIds.ROW_OR_COLUMN_MAJOR_ROW_MAJOR, MemoryUtil.memGetInt(out));

        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.TABLE_PATTERN, 1020, "GetColumnHeaders", out));
    }

    @Test
    void aCellAnswersItsRowColumnSpansOfOneAndItsGrid() {
        long out = buffer();
        String[] names = {"get_Row", "get_Column", "get_RowSpan", "get_ColumnSpan"};
        int[] expected = {1, 0, 1, 1};
        for (int i = 0; i < names.length; i++) {
            assertEquals(UiaIds.S_OK, get(UiaIds.GRID_ITEM_PATTERN, 1028, names[i], out), names[i]);
            assertEquals(expected[i], MemoryUtil.memGetInt(out), names[i]);
        }
        assertEquals(UiaIds.S_OK, get(UiaIds.GRID_ITEM_PATTERN, 1032, "get_ContainingGrid", out));
        assertEquals(SIMPLE + 1030, MemoryUtil.memGetAddress(out));

        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.GRID_ITEM_PATTERN, 1001, "get_Row", out), "a node with no cell facet");
        goneFromTheTree();
        for (String name : names) {
            assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                    get(UiaIds.GRID_ITEM_PATTERN, 1028, name, out), name);
        }
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.GRID_ITEM_PATTERN, 1028, "get_ContainingGrid", out));
    }

    /**
     * Semantics 3: a cell's column header is the header whose CellFacet column is the cell's, never
     * the header at the cell's column index among the group's children. Until 2026-09-15 it was the
     * latter, which in this fixture (headers in the order 1, 0) names the other column's header.
     *
     * <p><b>Extended 2026-09-15 (the settled split of semantics 3):</b> it is answered for a data
     * cell and for a footer cell, and never for the header cell itself, which answered itself
     * before — a header is not under its own column's header, and a client walking the array from
     * a header walked back to where it started.
     */
    @Test
    void aCellsHeaderItemsAreNoneForTheRowAndTheHeaderOfItsColumnAndNoneForAHeader() {
        long out = buffer();
        assertEquals(UiaIds.S_OK, get(UiaIds.TABLE_ITEM_PATTERN, 1029, "GetRowHeaderItems", out));
        assertEquals(UiaIds.S_OK, get(UiaIds.TABLE_ITEM_PATTERN, 1029, "GetColumnHeaderItems", out));
        assertEquals(UiaIds.S_OK, get(UiaIds.TABLE_ITEM_PATTERN, 1032, "GetColumnHeaderItems", out));
        assertEquals(UiaIds.S_OK, get(UiaIds.TABLE_ITEM_PATTERN, 1028, "GetColumnHeaderItems", out));
        assertEquals(UiaIds.S_OK, get(UiaIds.TABLE_ITEM_PATTERN, 1036, "GetColumnHeaderItems", out));
        assertEquals(UiaIds.S_OK, get(UiaIds.TABLE_ITEM_PATTERN, 1022, "GetColumnHeaderItems", out));
        assertArrayEquals(new long[0], arrays.get(0));
        assertArrayEquals(new long[] {SIMPLE + 1023}, arrays.get(1), "column 1's header");
        assertArrayEquals(new long[0], arrays.get(2), "a grid with no header group has none");
        assertArrayEquals(new long[] {SIMPLE + 1022}, arrays.get(3), "column 0's header");
        assertArrayEquals(new long[] {SIMPLE + 1022}, arrays.get(4),
                "a footer cell is under its column's header too: it summarises that column");
        assertArrayEquals(new long[0], arrays.get(5),
                "and the header cell itself is under none, rather than under itself");

        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.TABLE_ITEM_PATTERN, 1029, "GetColumnHeaderItems", out));
    }

    /**
     * Semantics 2, the minor split settled 2026-09-15: the climb to a cell's table starts at the
     * cell's <b>parent</b>. It bites on the one shape where the two readings disagree — a table
     * nested inside a cell of another — and it bites twice: the inner table was its own containing
     * grid, and the outer table's GetItem could not find it at all, because the cell's table was
     * not the table asked. Linux and macOS started at the parent already.
     */
    @Test
    void aNestedTablesOwnCellBelongsToTheTableAboveItAndNotToItself() {
        long out = buffer();
        assertEquals(UiaIds.S_OK, get(UiaIds.GRID_ITEM_PATTERN, 1052, "get_ContainingGrid", out));
        assertEquals(SIMPLE + 1050, MemoryUtil.memGetAddress(out),
                "the table above it, never the table it is");
        assertEquals(SIMPLE + 1052, getItem(1050, 0, 0),
                "and the outer table finds it at its own coordinates");

        assertEquals(UiaIds.S_OK, get(UiaIds.GRID_ITEM_PATTERN, 1054, "get_ContainingGrid", out));
        assertEquals(SIMPLE + 1052, MemoryUtil.memGetAddress(out),
                "a cell inside the nested table belongs to the nested table");
        assertEquals(SIMPLE + 1054, getItem(1052, 0, 0));
    }
}
