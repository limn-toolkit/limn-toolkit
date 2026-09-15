package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@code IRawElementProviderSimple} answered through its own vtable, from a real published tree.
 *
 * <p>The strings are the only part a machine without Windows cannot do, and they are a seam here
 * rather than a special case: the fake allocator below hands out ordinary memory and the cases read
 * it back, which is exactly the shape {@code oleaut32} has and none of its ownership.
 */
class UiaProviderTest {

    /** Every address the fake allocator handed out, so the fixture can free what it made. */
    private final List<Long> allocated = new ArrayList<>();

    /** Stands in for SysAllocStringLen: length-prefixed UTF-16, and ours to free. */
    private final UiaStrings.Allocator strings = text -> {
        long bytes = MemoryUtil.nmemAllocChecked(4L + (long) text.length() * 2 + 2);
        MemoryUtil.memPutInt(bytes, text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            MemoryUtil.memPutShort(bytes + 4 + (long) i * 2, (short) text.charAt(i));
        }
        MemoryUtil.memPutShort(bytes + 4 + (long) text.length() * 2, (short) 0);
        long bstr = bytes + 4;
        allocated.add(bytes);
        return bstr;
    };

    private static String readBstr(long bstr) {
        int bytes = MemoryUtil.memGetInt(bstr - 4);
        StringBuilder text = new StringBuilder(bytes / 2);
        for (int i = 0; i < bytes / 2; i++) {
            text.append((char) MemoryUtil.memGetShort(bstr + (long) i * 2));
        }
        return text.toString();
    }

    private final AtomicReference<AccessibleTree> published =
            new AtomicReference<>(AccessibleTree.EMPTY);
    private long host;
    private final List<int[]> patternsAsked = new ArrayList<>();
    private final List<long[]> arraysMade = new ArrayList<>();

    /** The nodes another open window of this process holds an element for (CRIT-2). */
    private final java.util.Set<Long> otherWindowHolds = new java.util.HashSet<>();

    private final UiaProvider.Context context = new UiaProvider.Context() {
        @Override
        public AccessibleTree tree() {
            return published.get();
        }

        @Override
        public long patternProviderFor(long nodeId, int patternId) {
            patternsAsked.add(new int[] {(int) nodeId, patternId});
            return 0xBEEF0000L + patternId;
        }

        @Override
        public long hostProvider() {
            return host;
        }

        @Override
        public UiaStrings.Allocator strings() {
            return strings;
        }

        @Override
        public long int32Array(int[] values) {
            return 0;
        }

        @Override
        public long unknownArray(long[] pointers) {
            arraysMade.add(pointers);
            return 0xA77A0000L + pointers.length;
        }

        @Override
        public long elementFor(long nodeId) {
            return 0;
        }

        @Override
        public long simpleElementFor(long nodeId) {
            // A stand-in pointer that names the node, so a test can see which node an
            // element-valued property was answered with and through which interface; and 0 for
            // a node this tree does not hold, which is the bridge's own answer (elementOf).
            return published.get().indexOf(nodeId) < 0 ? 0 : 0xE1E00000L + nodeId;
        }

        /**
         * Stands for the bridge's {@code simpleElementInAnotherWindowFor} (CRIT-2): the pointer
         * ANOTHER open window's provider hands back for a node of its own tree, named so that a
         * case can tell it apart from one of this window's, and {@code 0} for a node no open
         * window holds at all.
         */
        @Override
        public long simpleElementInAnotherWindowFor(long nodeId) {
            return otherWindowHolds.contains(nodeId) ? 0xF0E10000L + (nodeId & 0xFF) : 0;
        }

        @Override
        public long rootElement() {
            return 0;
        }

        @Override
        public boolean perform(long nodeId, Accessible.Action action, Accessible.Argument arg) {
            return published.get().indexOf(nodeId) >= 0;
        }

        @Override
        public boolean requestFocus(long nodeId) {
            return false;
        }
    };

    private final List<UiaObject> made = new ArrayList<>();

    @AfterEach
    void freeWhatWasMade() {
        made.forEach(UiaObject::free);
        allocated.forEach(MemoryUtil::nmemFree);
    }

    /** A window holding one button, published the way a scene publishes one. */
    private void publishAWindowWithAButton() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 20, 160, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        published.set(a.publish(0, 0, 0, 1f, true));
    }

    /**
     * A window holding a caption, a field the caption names, and a message beneath the field that
     * describes it, with the relations resolved the way the walk resolves them: to node ids.
     */
    private void publishAWindowWithALabelledAndDescribedField() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(3001, 0, Locale.ENGLISH, 0, 0, 400, 20);
        a.role(Accessible.Role.LABEL);
        a.name(I18nString.literal("Email"), Accessible.NameFrom.CONTENT);
        a.relation(Accessible.Relation.LABEL_FOR, 3002L);
        a.inherited(true, true, true, false, false);
        a.end();
        a.begin(3002, 0, Locale.ENGLISH, 0, 20, 400, 32);
        a.role(Accessible.Role.TEXT_FIELD);
        a.name(I18nString.literal("Email"), Accessible.NameFrom.LABEL);
        a.description(I18nString.literal("Enter an address like ada@example.com"));
        a.relation(Accessible.Relation.LABELLED_BY, 3001L);
        a.relation(Accessible.Relation.DESCRIBED_BY, 3003L);
        a.inherited(true, true, true, true, false);
        a.end();
        a.begin(3003, 0, Locale.ENGLISH, 0, 52, 400, 20);
        a.role(Accessible.Role.LABEL);
        a.name(I18nString.literal("Enter an address like ada@example.com"),
                Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        a.resolveRelations((kind, target) -> (Long) target);
        published.set(a.publish(0, 0, 0, 1f, true));
    }

    private long elementFor(long nodeId) {
        UiaObject object = UiaObject.create(
                List.of(new UiaObject.Served(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE,
                        UiaProvider.simpleSlots(nodeId, context))), () -> { });
        made.add(object);
        return object.pointer();
    }

    /**
     * Where each member sits, taken from the table the guest filled rather than written out: a
     * case that hard-coded 5 for GetPropertyValue would agree with a vtable built the same wrong
     * way.
     */
    private static int slotOf(String member) {
        return 3 + UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE.slots().indexOf(member);
    }

    private static final int GET_PROVIDER_OPTIONS = slotOf("get_ProviderOptions");
    private static final int GET_PATTERN_PROVIDER = slotOf("GetPatternProvider");
    private static final int GET_PROPERTY_VALUE = slotOf("GetPropertyValue");
    private static final int GET_HOST_PROVIDER = slotOf("get_HostRawElementProvider");

    private static int callOut(long element, int slot, long out) {
        return JNI.invokePPI(element, out, UiaCom.slotOf(element, slot));
    }

    private static int callWithId(long element, int slot, int id, long out) {
        return JNI.invokePPI(element, id, out, UiaCom.slotOf(element, slot));
    }

    @Test
    void aProviderSaysItRunsInsideTheProcessItDescribes() {
        publishAWindowWithAButton();
        long element = elementFor(1001);
        long out = MemoryUtil.nmemAllocChecked(8);
        try {
            assertEquals(UiaIds.S_OK, callOut(element, GET_PROVIDER_OPTIONS, out));
            assertEquals(UiaIds.PROVIDER_OPTIONS_SERVER_SIDE_PROVIDER, MemoryUtil.memGetInt(out),
                    "and 1 is ClientSideProvider, which is the reading this constant exists for");
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    @Test
    void aNameArrivesAsAStringTheCallerOwns() {
        publishAWindowWithAButton();
        long element = elementFor(1001);
        long out = MemoryUtil.nmemAllocChecked(UiaVariant.SIZE);
        try {
            assertEquals(UiaIds.S_OK, callWithId(element, GET_PROPERTY_VALUE, UiaIds.NAME, out));

            ByteBuffer variant = MemoryUtil.memByteBuffer(out, UiaVariant.SIZE);
            assertEquals(UiaVariant.VT_BSTR, UiaVariant.tagOf(variant, 0));
            assertEquals("Save", readBstr(variant.getLong(UiaVariant.PAYLOAD)));
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    @Test
    void theControlTypeAndTheBooleansArriveAsThemselves() {
        publishAWindowWithAButton();
        long element = elementFor(1001);
        long out = MemoryUtil.nmemAllocChecked(UiaVariant.SIZE);
        ByteBuffer variant = MemoryUtil.memByteBuffer(out, UiaVariant.SIZE);
        try {
            callWithId(element, GET_PROPERTY_VALUE, UiaIds.CONTROL_TYPE, out);
            assertEquals(UiaVariant.VT_I4, UiaVariant.tagOf(variant, 0));
            assertEquals(UiaIds.CONTROL_BUTTON, variant.getInt(UiaVariant.PAYLOAD));

            callWithId(element, GET_PROPERTY_VALUE, UiaIds.IS_ENABLED, out);
            assertEquals(UiaVariant.VT_BOOL, UiaVariant.tagOf(variant, 0));
            assertEquals((short) -1, variant.getShort(UiaVariant.PAYLOAD),
                    "every bit set, which is the platform's true");

            callWithId(element, GET_PROPERTY_VALUE, UiaIds.IS_OFFSCREEN, out);
            assertEquals((short) 0, variant.getShort(UiaVariant.PAYLOAD),
                    "and this one is showing");
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    /**
     * The three element-valued properties: a caption as one element, a message as an array of one,
     * each through the simple interface the property declares, and nothing for a node that
     * declares no such relation -- the platform's own default for all three.
     */
    @Test
    void aFieldsLabelAndDescriptionArriveAsElementsThroughTheSimpleInterface() {
        publishAWindowWithALabelledAndDescribedField();
        long field = elementFor(3002);
        long out = MemoryUtil.nmemAllocChecked(UiaVariant.SIZE);
        ByteBuffer variant = MemoryUtil.memByteBuffer(out, UiaVariant.SIZE);
        try {
            assertEquals(UiaIds.S_OK, callWithId(field, GET_PROPERTY_VALUE, UiaIds.LABELED_BY, out));
            assertEquals(UiaVariant.VT_UNKNOWN, UiaVariant.tagOf(variant, 0));
            assertEquals(0xE1E00000L + 3001, variant.getLong(UiaVariant.PAYLOAD),
                    "the caption's own element, through the simple interface");

            assertEquals(UiaIds.S_OK, callWithId(field, GET_PROPERTY_VALUE, UiaIds.DESCRIBED_BY, out));
            assertEquals((short) (UiaVariant.VT_ARRAY | UiaVariant.VT_UNKNOWN),
                    UiaVariant.tagOf(variant, 0), "an array, which is what the property declares");
            assertEquals(0xA77A0000L + 1, variant.getLong(UiaVariant.PAYLOAD));
            assertEquals(1, arraysMade.size());
            assertEquals(0xE1E00000L + 3003, arraysMade.get(0)[0],
                    "holding the message's element, referenced for the array");

            assertEquals(UiaIds.S_OK, callWithId(field, GET_PROPERTY_VALUE, UiaIds.CONTROLLER_FOR, out));
            assertEquals(UiaVariant.VT_EMPTY, UiaVariant.tagOf(variant, 0),
                    "a relation the node does not declare is the platform's empty default");

            long message = elementFor(3003);
            assertEquals(UiaIds.S_OK, callWithId(message, GET_PROPERTY_VALUE, UiaIds.LABELED_BY, out));
            assertEquals(UiaVariant.VT_EMPTY, UiaVariant.tagOf(variant, 0));
            assertEquals(UiaIds.S_OK, callWithId(message, GET_PROPERTY_VALUE, UiaIds.DESCRIBED_BY, out));
            assertEquals(UiaVariant.VT_EMPTY, UiaVariant.tagOf(variant, 0));
            assertEquals(1, arraysMade.size(), "and no empty array was minted for it");
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    /**
     * A window holding a combo box that opened a native popup: its {@code CONTROLLER_FOR} names
     * the popup's root, which is a node of the OTHER window's tree (process-wide identifiers,
     * ADR 039 §1.3 amended 2026-09-14); its caption is likewise held elsewhere, and its message
     * is described by one node held here, one the other window holds, and one no open window holds
     * at all. The identifiers are what a scene mints: a tag above a forty-two-bit serial, so a
     * hand-written one below stands for the other window's.
     */
    private void publishAWindowWithAComboWhosePopupIsAnotherWindow() {
        otherWindowHolds.add(FOREIGN_POPUP_ROOT);
        otherWindowHolds.add(FOREIGN_POPUP_ROOT + 1);
        otherWindowHolds.add(FOREIGN_POPUP_ROOT + 2);
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(4001, 0, Locale.ENGLISH, 0, 0, 400, 32);
        a.role(Accessible.Role.COMBO_BOX);
        a.name(I18nString.literal("Size"), Accessible.NameFrom.LABEL);
        a.relation(Accessible.Relation.CONTROLLER_FOR, FOREIGN_POPUP_ROOT);
        a.relation(Accessible.Relation.LABELLED_BY, FOREIGN_POPUP_ROOT + 1);
        a.relation(Accessible.Relation.DESCRIBED_BY, FOREIGN_POPUP_ROOT + 2);
        a.relation(Accessible.Relation.DESCRIBED_BY, 4002L);
        a.relation(Accessible.Relation.DESCRIBED_BY, GONE_FROM_EVERY_WINDOW);
        a.inherited(true, true, true, true, false);
        a.end();
        a.begin(4002, 0, Locale.ENGLISH, 0, 40, 400, 20);
        a.role(Accessible.Role.LABEL);
        a.name(I18nString.literal("Pick a size"), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, false, false);
        a.end();
        a.begin(4003, 0, Locale.ENGLISH, 0, 60, 400, 20);
        a.role(Accessible.Role.TEXT_FIELD);
        a.name(I18nString.literal("Notes"), Accessible.NameFrom.LABEL);
        a.relation(Accessible.Relation.LABELLED_BY, GONE_FROM_EVERY_WINDOW);
        // And a POPUP_FOR naming a node this window DOES hold, so that what the test below reads
        // is the relation being carried by no property rather than a target nobody holds.
        a.relation(Accessible.Relation.POPUP_FOR, 4001L);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        a.resolveRelations((kind, target) -> (Long) target);
        published.set(a.publish(0, 0, 0, 1f, true));
    }

    /** An identifier with another scene's tag in its high bits: nothing this tree holds. */
    private static final long FOREIGN_POPUP_ROOT = (7L << 42) | 5;

    /** The same shape, for a node no open window of this process holds. */
    private static final long GONE_FROM_EVERY_WINDOW = (9L << 42) | 11;

    /**
     * CRIT-2, 2026-09-15: a relation target another window's tree holds is handed back as THAT
     * window's element, from that window's own provider, and not compacted away. This is what a
     * native combo's opener carries while its popup is open — {@code ControllerFor} naming the
     * popup's root, which the platform put in a window of its own — and it is what Linux has named
     * since phase 3. ADR 039 §1.11's 2026-09-14 amendment hands the per-platform half here.
     *
     * <p>A target NO open window holds is still left out rather than handed over as a NULL entry
     * of a {@code SAFEARRAY(VT_UNKNOWN)}, which {@code SafeArrayDestroy} would release; a property
     * whose every target is gone stays the platform's empty default.
     */
    @Test
    void aRelationTargetAnotherWindowHoldsIsHandedBackAsThatWindowsElement() {
        publishAWindowWithAComboWhosePopupIsAnotherWindow();
        long combo = elementFor(4001);
        long field = elementFor(4003);
        long out = MemoryUtil.nmemAllocChecked(UiaVariant.SIZE);
        ByteBuffer variant = MemoryUtil.memByteBuffer(out, UiaVariant.SIZE);
        try {
            assertEquals(UiaIds.S_OK, callWithId(combo, GET_PROPERTY_VALUE, UiaIds.CONTROLLER_FOR, out));
            assertEquals((short) (UiaVariant.VT_ARRAY | UiaVariant.VT_UNKNOWN),
                    UiaVariant.tagOf(variant, 0),
                    "the popup's root is the other window's element, and a client asking the combo "
                            + "what it controls is asking exactly that");
            assertEquals(1, arraysMade.size());
            assertEquals(1, arraysMade.get(0).length);
            assertEquals(0xF0E10000L + (FOREIGN_POPUP_ROOT & 0xFF), arraysMade.get(0)[0],
                    "handed over by the window that holds it, not minted here");

            assertEquals(UiaIds.S_OK, callWithId(combo, GET_PROPERTY_VALUE, UiaIds.LABELED_BY, out));
            assertEquals(UiaVariant.VT_UNKNOWN, UiaVariant.tagOf(variant, 0));
            assertEquals(0xF0E10000L + ((FOREIGN_POPUP_ROOT + 1) & 0xFF),
                    variant.getLong(UiaVariant.PAYLOAD),
                    "a caption another window holds is that window's element too");

            assertEquals(UiaIds.S_OK, callWithId(combo, GET_PROPERTY_VALUE, UiaIds.DESCRIBED_BY, out));
            assertEquals((short) (UiaVariant.VT_ARRAY | UiaVariant.VT_UNKNOWN),
                    UiaVariant.tagOf(variant, 0));
            assertEquals(2, arraysMade.size());
            assertEquals(2, arraysMade.get(1).length,
                    "both windows' targets, and the one no window holds compacted away");
            assertEquals(0xF0E10000L + ((FOREIGN_POPUP_ROOT + 2) & 0xFF), arraysMade.get(1)[0]);
            assertEquals(0xE1E00000L + 4002, arraysMade.get(1)[1]);
            for (long pointer : arraysMade.get(1)) {
                assertNotEquals(0L, pointer, "no NULL entry, which SafeArrayDestroy would release");
            }

            assertEquals(UiaIds.S_OK, callWithId(field, GET_PROPERTY_VALUE, UiaIds.LABELED_BY, out));
            assertEquals(UiaVariant.VT_EMPTY, UiaVariant.tagOf(variant, 0),
                    "a target no open window holds is the platform's empty default, never a NULL "
                            + "element");
            assertEquals(2, arraysMade.size(), "and no array was minted for it");
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    /**
     * The other half of CRIT-2's Windows answer, and a deliberate non-mapping rather than an
     * omission: {@code POPUP_FOR} — the mirror a native popup's own root carries, naming the widget
     * that opened it — is carried by no property of this platform.
     *
     * <p>UI Automation's property table has no "popup for": its element-valued properties are
     * {@code LabeledBy} (30018), {@code ControllerFor} (30104), {@code DescribedBy} (30105),
     * {@code FlowsTo} (30106) and {@code FlowsFrom} (30148), and no member of
     * {@code UIA_PropertyIds} has "popup" in its name at all — read off the guest's own
     * {@code UIAutomationCore.dll} 7.2.26100.9278 on 2026-09-13
     * (readings/windows-dump-uia-typelib-all-members.txt). What a client follows from a popup back
     * to its opener is the opener's {@code ControllerFor}, which the test above pins across the
     * window boundary. The settled list's Windows line ("hands back a ControllerFor/PopupFor
     * element from the other HWND's provider") is amended by ADR 039 §1.11's 2026-09-15 amendment
     * on that point.
     *
     * <p>So this fails if a later lane invents a carrier for it — answering the opener as the
     * popup's own {@code ControllerFor} would say the popup controls the field that opened it,
     * which is the relation backwards.
     */
    @Test
    void aPopupForIsCarriedByNoPropertyBecauseThePlatformHasNone() {
        publishAWindowWithAComboWhosePopupIsAnotherWindow();
        long popupRootStandIn = elementFor(4003);
        long out = MemoryUtil.nmemAllocChecked(UiaVariant.SIZE);
        ByteBuffer variant = MemoryUtil.memByteBuffer(out, UiaVariant.SIZE);
        try {
            int arraysBefore = arraysMade.size();
            for (int property : new int[] {UiaIds.CONTROLLER_FOR, UiaIds.DESCRIBED_BY,
                    UiaIds.LABELED_BY}) {
                assertEquals(UiaIds.S_OK,
                        callWithId(popupRootStandIn, GET_PROPERTY_VALUE, property, out));
                assertEquals(UiaVariant.VT_EMPTY, UiaVariant.tagOf(variant, 0),
                        "the node declares POPUP_FOR naming 4001, which THIS window holds, and no "
                                + "element-valued property of this platform carries that relation: "
                                + "property " + property + " must stay the platform's empty "
                                + "default rather than answer the opener");
            }
            assertEquals(arraysBefore, arraysMade.size(), "and no array was minted for it");
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    @Test
    void aPropertyNobodyAnswersIsEmptyRatherThanAFailure() {
        publishAWindowWithAButton();
        long element = elementFor(1001);
        long out = MemoryUtil.nmemAllocChecked(UiaVariant.SIZE);
        try {
            MemoryUtil.memPutLong(out + UiaVariant.PAYLOAD, 0x0BADF00DL);

            assertEquals(UiaIds.S_OK,
                    callWithId(element, GET_PROPERTY_VALUE, UiaIds.CULTURE, out));

            ByteBuffer variant = MemoryUtil.memByteBuffer(out, UiaVariant.SIZE);
            assertEquals(UiaVariant.VT_EMPTY, UiaVariant.tagOf(variant, 0));
            assertEquals(0L, variant.getLong(UiaVariant.PAYLOAD),
                    "cleared whole, so a caller reading the union finds nothing rather than what "
                            + "its own stack held");
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    /**
     * §1.3's promise to a client holding an element for a node that has left the tree: the object
     * stays alive and answers that it is not available, rather than answering about some other node
     * that happens to have taken its place.
     */
    @Test
    void anElementForANodeThatHasGoneAnswersUnavailable() {
        publishAWindowWithAButton();
        long element = elementFor(1001);
        long out = MemoryUtil.nmemAllocChecked(UiaVariant.SIZE);
        try {
            // Republished without the button, which is what closing a dialog does to its contents.
            Accessibility a = new Accessibility();
            a.beginWalk(400, 300, Locale.ENGLISH);
            a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
            a.role(Accessible.Role.WINDOW);
            a.inherited(true, true, true, false, false);
            a.end();
            published.set(a.publish(0, 0, 0, 1f, true));

            assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE,
                    callWithId(element, GET_PROPERTY_VALUE, UiaIds.NAME, out));

            ByteBuffer variant = MemoryUtil.memByteBuffer(out, UiaVariant.SIZE);
            assertEquals(UiaVariant.VT_EMPTY, UiaVariant.tagOf(variant, 0),
                    "and the variant is cleared on the failing path too, because a caller is "
                            + "entitled to look at it anyway");
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    @Test
    void aPatternTheNodeVendsIsAnsweredWithAProviderAndOneItDoesNotIsANull() {
        publishAWindowWithAButton();
        long element = elementFor(1001);
        long out = MemoryUtil.nmemAllocChecked(8);
        try {
            assertEquals(UiaIds.S_OK,
                    callWithId(element, GET_PATTERN_PROVIDER, UiaIds.INVOKE_PATTERN, out));
            assertNotEquals(0L, MemoryUtil.memGetAddress(out), "a button can be pressed");
            assertEquals(1, patternsAsked.size());
            assertEquals(UiaIds.INVOKE_PATTERN, patternsAsked.get(0)[1]);

            assertEquals(UiaIds.S_OK,
                    callWithId(element, GET_PATTERN_PROVIDER, UiaIds.SCROLL_PATTERN, out));
            assertEquals(0L, MemoryUtil.memGetAddress(out),
                    "a pattern it does not vend is a null and S_OK: 'no' is an ordinary answer to "
                            + "'can you do this', and a failing HRESULT reads as a broken provider");
            assertEquals(1, patternsAsked.size(), "and nothing was minted for it");
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    /**
     * What places this tree inside the desktop's own rather than beside it: a client walking down
     * from the window finds the root through the host provider, and everything below it through
     * this provider's navigation.
     */
    @Test
    void onlyTheRootAnswersTheWindowsOwnProvider() {
        publishAWindowWithAButton();
        host = 0x1234_5678L;
        long root = elementFor(1000);
        long button = elementFor(1001);
        long out = MemoryUtil.nmemAllocChecked(8);
        try {
            assertEquals(UiaIds.S_OK, callOut(root, GET_HOST_PROVIDER, out));
            assertEquals(host, MemoryUtil.memGetAddress(out));

            assertEquals(UiaIds.S_OK, callOut(button, GET_HOST_PROVIDER, out));
            assertEquals(0L, MemoryUtil.memGetAddress(out),
                    "a child answering the host would put a second copy of this window into the "
                            + "desktop's tree");
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    @Test
    void aCallWithNowhereToWriteIsRefusedRatherThanDereferencingZero() {
        publishAWindowWithAButton();
        long element = elementFor(1001);

        assertEquals(UiaIds.E_NO_INTERFACE, callOut(element, GET_PROVIDER_OPTIONS, 0));
        assertEquals(UiaIds.E_NO_INTERFACE, callWithId(element, GET_PROPERTY_VALUE, UiaIds.NAME, 0));
        assertEquals(UiaIds.E_NO_INTERFACE,
                callWithId(element, GET_PATTERN_PROVIDER, UiaIds.INVOKE_PATTERN, 0));
        assertEquals(UiaIds.E_NO_INTERFACE, callOut(element, GET_HOST_PROVIDER, 0));
    }
}
