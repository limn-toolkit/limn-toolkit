package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.ToggleFacet;
import limn.graphics.ShapedText;
import limn.i18n.I18nString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Field;
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
        COVERED.put("IGridProvider", List.of("GetItem", "get_RowCount", "get_ColumnCount"));
        COVERED.put("ITableProvider", List.of("GetRowHeaders", "GetColumnHeaders",
                "get_RowOrColumnMajor"));
        COVERED.put("IGridItemProvider", List.of("get_Row", "get_Column", "get_RowSpan",
                "get_ColumnSpan", "get_ContainingGrid"));
        COVERED.put("ITableItemProvider", List.of("GetRowHeaderItems", "GetColumnHeaderItems"));
    }

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
     *   1001 BUTTON [press]            1002 CHECK_BOX mixed
     *   1003 TEXT_FIELD "draft"        1004 TEXT_FIELD "fixed" read-only
     *   1005 SPIN_BUTTON 7 "07"        1006 PROGRESS_BAR 40 read-only
     *   1007 COMBO_BOX expanded
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
     *     1021 GROUP (the header)  1022 COLUMN_HEADER (-1,0)  1023 COLUMN_HEADER (-1,1)
     *     1024 ROW 1/2             1025 CELL (0,0)            1026 CELL (0,1)
     *     1027 ROW 2/2             1028 CELL (1,0)            1029 CELL (1,1)
     *   1030 TABLE 1x1, calendar-shaped: its ROW carries no position
     *     1031 ROW                 1032 CELL (0,0)
     *   1040 SCROLL_PANE
     *     1041 BUTTON
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
        int header = node(a, 1021, table, Accessible.Role.GROUP);
        cell(a, 1022, header, Accessible.Role.COLUMN_HEADER, -1, 0);
        cell(a, 1023, header, Accessible.Role.COLUMN_HEADER, -1, 1);
        a.end();
        int row0 = node(a, 1024, table, Accessible.Role.ROW);
        a.selectionItem(false, 1, 2);
        cell(a, 1025, row0, Accessible.Role.CELL, 0, 0);
        cell(a, 1026, row0, Accessible.Role.CELL, 0, 1);
        a.end();
        int row1 = node(a, 1027, table, Accessible.Role.ROW);
        a.selectionItem(false, 2, 2);
        cell(a, 1028, row1, Accessible.Role.CELL, 1, 0);
        cell(a, 1029, row1, Accessible.Role.CELL, 1, 1);
        a.end();
        a.end();
        int calendar = node(a, 1030, window, Accessible.Role.TABLE);
        a.table(1, 1);
        int week = node(a, 1031, calendar, Accessible.Role.ROW);
        cell(a, 1032, week, Accessible.Role.CELL, 0, 0);
        a.end();
        a.end();
        int pane = node(a, 1040, window, Accessible.Role.SCROLL_PANE);
        a.scroll(0, 0.25, 1, 0.5, false, true);
        node(a, 1041, pane, Accessible.Role.BUTTON);
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

    @Test
    void invokePostsAPressAndAnswersNotAvailableWhenTheSceneRefuses() {
        assertEquals(UiaIds.S_OK, verb(UiaIds.INVOKE_PATTERN, 1001, "Invoke"));
        assertEquals(List.of("1001 PRESS None[]"), posted);

        accepting = false;
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE, verb(UiaIds.INVOKE_PATTERN, 1001, "Invoke"),
                "a refusal from the scene reads as a node that has gone");
    }

    // ---- Toggle

    @Test
    void toggleIsPostedAndTheStateIsTheThreePlatformNumbersInFourBytes() {
        assertEquals(UiaIds.S_OK, verb(UiaIds.TOGGLE_PATTERN, 1002, "Toggle"));
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

    @Test
    void valueSetValuePostsTheWholeTextOnATextFieldAndOnAValueNode() {
        assertEquals(UiaIds.S_OK,
                ((UiaCom.PP) slot(UiaIds.VALUE_PATTERN, 1003, "SetValue")).invoke(0, bstr("final")));
        // Pinned as of 048f7d0: a value node's SetValue posts SET_TEXT too, which its widget refuses
        // (CRIT-7; item 6 of the Windows brief posts SET_VALUE by text there).
        assertEquals(UiaIds.S_OK,
                ((UiaCom.PP) slot(UiaIds.VALUE_PATTERN, 1005, "SetValue")).invoke(0, bstr("08")));
        assertEquals(List.of("1003 SET_TEXT OfText[text=final]", "1005 SET_TEXT OfText[text=08]"),
                posted);

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

        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.VALUE_PATTERN, 1003, "get_IsReadOnly", buffer()));
    }

    // ---- RangeValue

    @Test
    void rangeValueSetValuePostsTheNumber() {
        assertEquals(UiaIds.S_OK,
                ((UiaCom.PD) slot(UiaIds.RANGE_VALUE_PATTERN, 1005, "SetValue")).invoke(0, 9));
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

    @Test
    void expandAndCollapseArePostedAndTheStateIsZeroOrOne() {
        // Pinned as of 048f7d0: posted whether or not the node publishes the verb (WINDOWS-NEW-10;
        // item 6 of the Windows brief refuses them there).
        assertEquals(UiaIds.S_OK, verb(UiaIds.EXPAND_COLLAPSE_PATTERN, 1007, "Expand"));
        assertEquals(UiaIds.S_OK, verb(UiaIds.EXPAND_COLLAPSE_PATTERN, 1007, "Collapse"));
        assertEquals(List.of("1007 EXPAND None[]", "1007 COLLAPSE None[]"), posted);

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

    @Test
    void scrollIntoViewIsPosted() {
        assertEquals(UiaIds.S_OK, verb(UiaIds.SCROLL_ITEM_PATTERN, 1041, "ScrollIntoView"));
        assertEquals(List.of("1041 SCROLL_INTO_VIEW None[]"), posted);
    }

    // ---- Grid and Table

    @Test
    void getItemFindsACellOfARowByTheRowsPositionAndNothingInACalendarShapedGrid() {
        long out = buffer();
        assertEquals(UiaIds.S_OK,
                ((UiaCom.PIIP) slot(UiaIds.GRID_PATTERN, 1020, "GetItem")).invoke(0, 1, 0, out));
        assertEquals(SIMPLE + 1028, MemoryUtil.memGetAddress(out));
        assertEquals(UiaIds.S_OK,
                ((UiaCom.PIIP) slot(UiaIds.GRID_PATTERN, 1020, "GetItem")).invoke(0, 5, 0, out));
        assertEquals(0L, MemoryUtil.memGetAddress(out), "an unrealized row answers null");
        // Pinned as of 048f7d0: a row with no position is never matched, so a calendar's day is
        // not found (WINDOWS-NEW-8; semantics 2 finds it by CellFacet).
        assertEquals(UiaIds.S_OK,
                ((UiaCom.PIIP) slot(UiaIds.GRID_PATTERN, 1030, "GetItem")).invoke(0, 0, 0, out));
        assertEquals(0L, MemoryUtil.memGetAddress(out));

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

    @Test
    void theTableAnswersNoRowHeadersTheHeaderGroupsChildrenAndRowMajor() {
        long out = buffer();
        assertEquals(UiaIds.S_OK, get(UiaIds.TABLE_PATTERN, 1020, "GetRowHeaders", out));
        assertEquals(ARRAY, MemoryUtil.memGetAddress(out));
        assertEquals(UiaIds.S_OK, get(UiaIds.TABLE_PATTERN, 1020, "GetColumnHeaders", out));
        assertEquals(ARRAY + 1, MemoryUtil.memGetAddress(out));
        assertArrayEquals(new long[0], arrays.get(0));
        assertArrayEquals(new long[] {SIMPLE + 1022, SIMPLE + 1023}, arrays.get(1));
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

    @Test
    void aCellsHeaderItemsAreNoneForTheRowAndTheHeaderAtItsColumn() {
        long out = buffer();
        assertEquals(UiaIds.S_OK, get(UiaIds.TABLE_ITEM_PATTERN, 1029, "GetRowHeaderItems", out));
        assertEquals(UiaIds.S_OK, get(UiaIds.TABLE_ITEM_PATTERN, 1029, "GetColumnHeaderItems", out));
        assertEquals(UiaIds.S_OK, get(UiaIds.TABLE_ITEM_PATTERN, 1032, "GetColumnHeaderItems", out));
        assertArrayEquals(new long[0], arrays.get(0));
        assertArrayEquals(new long[] {SIMPLE + 1023}, arrays.get(1));
        assertArrayEquals(new long[0], arrays.get(2), "a grid with no header group has none");

        goneFromTheTree();
        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                get(UiaIds.TABLE_ITEM_PATTERN, 1029, "GetColumnHeaderItems", out));
    }
}
