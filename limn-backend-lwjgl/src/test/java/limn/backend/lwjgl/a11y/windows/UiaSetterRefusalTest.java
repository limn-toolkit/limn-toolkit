package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.graphics.ShapedText;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The minimal setter refusal fix round 2e put in front of phase 3 (semantics 5, amended
 * 2026-09-15): {@code Value.SetValue} and {@code RangeValue.SetValue} post nothing to a node that
 * is not {@code ENABLED} and answer {@code UIA_E_INVALIDOPERATION}, while {@code IsReadOnly} stays
 * the facet's truth, so a disabled field is never reported read-only. Driven through the slots
 * themselves, which are plain Java callbacks until a client calls them through a vtable.
 */
class UiaSetterRefusalTest {

    private AccessibleTree tree = AccessibleTree.EMPTY;
    private final List<String> posted = new ArrayList<>();

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
            return text -> 0;
        }

        @Override
        public long int32Array(int[] values) {
            return 0;
        }

        @Override
        public long unknownArray(long[] pointers) {
            return 0;
        }

        @Override
        public long elementFor(long nodeId) {
            return 0;
        }

        @Override
        public long simpleElementFor(long nodeId) {
            return 0;
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
            posted.add(action.name());
            return true;
        }
    };

    /** A window holding a slider (1001) and a text field (1002), enabled or not. */
    private void publish(boolean enabled) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 20, 160, 40);
        a.role(Accessible.Role.SLIDER);
        a.name(I18nString.literal("Level"), Accessible.NameFrom.EXPLICIT);
        a.value(40, 0, 100, 1);
        a.inherited(enabled, true, true, enabled, false);
        a.end();
        a.begin(1002, 0, Locale.ENGLISH, 10, 80, 160, 40);
        a.role(Accessible.Role.TEXT_FIELD);
        a.name(I18nString.literal("Notes"), Accessible.NameFrom.EXPLICIT);
        a.state(Accessible.State.EDITABLE);
        a.text("draft", 1, 0, ShapedText.Affinity.DOWNSTREAM, 0, 0, 1, null, false);
        a.inherited(enabled, true, true, enabled, false);
        a.end();
        a.end();
        tree = a.publish(0, 0, 0, 1f, true);
    }

    private int rangeSetValue() {
        return ((UiaCom.PD) UiaPatternProviders.slotsFor(UiaIds.RANGE_VALUE_PATTERN, 1001, context)
                .get("SetValue")).invoke(0, 80);
    }

    private int valueSetValue() {
        // A null BSTR is the empty string, which is all the slot reads of it.
        return ((UiaCom.PP) UiaPatternProviders.slotsFor(UiaIds.VALUE_PATTERN, 1002, context)
                .get("SetValue")).invoke(0, 0);
    }

    private int isReadOnly(int patternId, long nodeId) {
        long out = MemoryUtil.nmemAllocChecked(8);
        try {
            int hr = ((UiaCom.PP) UiaPatternProviders.slotsFor(patternId, nodeId, context)
                    .get("get_IsReadOnly")).invoke(0, out);
            assertEquals(UiaIds.S_OK, hr);
            return MemoryUtil.memGetInt(out);
        } finally {
            MemoryUtil.nmemFree(out);
        }
    }

    @Test
    void aNodeThatIsNotEnabledIsRefusedEverySetterAndIsStillNotReadOnly() {
        publish(false);

        assertEquals(UiaIds.E_INVALID_OPERATION, rangeSetValue(),
                "a disabled slider's RangeValue.SetValue is refused synchronously");
        assertEquals(UiaIds.E_INVALID_OPERATION, valueSetValue(),
                "and a disabled field's Value.SetValue");
        assertEquals(List.of(), posted, "and nothing reaches the toolkit");
        assertEquals(UiaIds.BOOL_FALSE, isReadOnly(UiaIds.RANGE_VALUE_PATTERN, 1001),
                "while the value is still not read-only: disabled is not read-only (§1.2)");
        assertEquals(UiaIds.BOOL_FALSE, isReadOnly(UiaIds.VALUE_PATTERN, 1002));
    }

    @Test
    void anEnabledNodeHasItsSettersPosted() {
        publish(true);

        assertEquals(UiaIds.S_OK, rangeSetValue());
        assertEquals(UiaIds.S_OK, valueSetValue());
        assertEquals(List.of("SET_VALUE", "SET_TEXT"), posted);
    }

    @Test
    void aNodeGoneFromTheSnapshotIsNotAvailable() {
        publish(true);
        tree = AccessibleTree.EMPTY;

        assertEquals(UiaIds.E_ELEMENT_NOT_AVAILABLE, rangeSetValue());
        assertEquals(List.of(), posted);
    }
}
