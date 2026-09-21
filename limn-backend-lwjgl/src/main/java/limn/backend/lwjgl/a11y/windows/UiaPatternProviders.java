package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pattern interfaces themselves: what a client calls when it operates a control.
 *
 * <p>Everything a pattern <em>reads</em> comes from the facet the walk published, and everything it
 * <em>does</em> goes back through the scene's own dispatcher to the widget's own private path — the
 * one a click takes, with the widget's own enabled guard and the widget's own notification. No
 * component gains a public {@code click()}, which is §1.5's rule and the reason an assistive
 * technology's toggle tells an application exactly what a user's does.
 *
 * <p><b>A verb answers as soon as it is accepted.</b> The call cannot wait for the user-interface
 * thread: a client calls on an RPC thread and blocking it is how a screen reader stops responding
 * to the person using it. So {@code S_OK} here means the request was understood and posted, and
 * what the widget did about it arrives as an event.
 */
final class UiaPatternProviders {

    private UiaPatternProviders() {
    }

    /**
     * @param patternId one of {@link UiaIds}' pattern ids
     * @return the interface that pattern is served through, or {@code null} for one this bridge
     *         does not serve
     */
    static UiaInterfaces.Vtable interfaceFor(int patternId) {
        return switch (patternId) {
            case UiaIds.INVOKE_PATTERN -> UiaInterfaces.INVOKE_PROVIDER;
            case UiaIds.TOGGLE_PATTERN -> UiaInterfaces.TOGGLE_PROVIDER;
            case UiaIds.VALUE_PATTERN -> UiaInterfaces.VALUE_PROVIDER;
            case UiaIds.RANGE_VALUE_PATTERN -> UiaInterfaces.RANGE_VALUE_PROVIDER;
            case UiaIds.SELECTION_PATTERN -> UiaInterfaces.SELECTION_PROVIDER;
            case UiaIds.EXPAND_COLLAPSE_PATTERN -> UiaInterfaces.EXPAND_COLLAPSE_PROVIDER;
            case UiaIds.SCROLL_PATTERN -> UiaInterfaces.SCROLL_PROVIDER;
            case UiaIds.SELECTION_ITEM_PATTERN -> UiaInterfaces.SELECTION_ITEM_PROVIDER;
            case UiaIds.SCROLL_ITEM_PATTERN -> UiaInterfaces.SCROLL_ITEM_PROVIDER;
            case UiaIds.GRID_PATTERN -> UiaInterfaces.GRID_PROVIDER;
            case UiaIds.GRID_ITEM_PATTERN -> UiaInterfaces.GRID_ITEM_PROVIDER;
            case UiaIds.TABLE_PATTERN -> UiaInterfaces.TABLE_PROVIDER;
            case UiaIds.TABLE_ITEM_PATTERN -> UiaInterfaces.TABLE_ITEM_PROVIDER;
            default -> null;
        };
    }

    /**
     * @param patternId which pattern
     * @param nodeId    which node
     * @param context   what the slots read and act through
     * @return that pattern's slots by name, or {@code null} for a pattern this bridge does not
     *         serve
     */
    static Map<String, CallbackI> slotsFor(int patternId, long nodeId,
                                           UiaProvider.Context context) {
        Map<String, CallbackI> slots = new LinkedHashMap<>();
        switch (patternId) {
            // Semantics 5: every verb below is posted only when the node publishes it now, read
            // through AccessibleNode#accepts on the snapshot of the call, because the pointer a
            // client holds outlives the snapshot that vended the pattern (a button disabled since,
            // a row under an overlay). Until 2026-09-15 each was posted whatever the node published
            // and the client was told S_OK for a verb the widget then refused (W6, WINDOWS-NEW-10).
            // One dispatch by the pattern's shape (ADR 045 §5): the code that serves each pattern
            // lives under the shape that owns it, moved there verbatim on 2026-09-21, and the
            // patterns themselves are vended by facet in UiaPatterns.
            case UiaIds.INVOKE_PATTERN -> UiaLeafShape.slots(patternId, slots, nodeId, context);
            case UiaIds.TOGGLE_PATTERN -> UiaToggleShape.slots(patternId, slots, nodeId, context);
            case UiaIds.VALUE_PATTERN -> UiaTextShape.slots(patternId, slots, nodeId, context);
            case UiaIds.RANGE_VALUE_PATTERN ->
                    UiaValueShape.slots(patternId, slots, nodeId, context);
            case UiaIds.EXPAND_COLLAPSE_PATTERN ->
                    UiaPopupOwnerShape.slots(patternId, slots, nodeId, context);
            case UiaIds.SELECTION_PATTERN, UiaIds.SELECTION_ITEM_PATTERN,
                 UiaIds.SCROLL_ITEM_PATTERN ->
                    UiaRowsShape.slots(patternId, slots, nodeId, context);
            case UiaIds.SCROLL_PATTERN -> UiaSurfaceShape.slots(patternId, slots, nodeId, context);
            case UiaIds.GRID_PATTERN, UiaIds.TABLE_PATTERN, UiaIds.GRID_ITEM_PATTERN,
                 UiaIds.TABLE_ITEM_PATTERN ->
                    UiaGridShape.slots(patternId, slots, nodeId, context);
            default -> {
                return null;
            }
        }
        return slots;
    }

    /**
     * Writes a {@code BOOL*} out-parameter: four bytes, as the guest read them
     * ({@link UiaIds#BOOL_TRUE}). Never {@link UiaVariant#TRUE}, which is a {@code VARIANT}'s.
     */
    static void putBool(long out, boolean value) {
        MemoryUtil.memPutInt(out, value ? UiaIds.BOOL_TRUE : UiaIds.BOOL_FALSE);
    }

    /**
     * Posts a setter the node accepts now, and refuses it synchronously otherwise (semantics 5 as
     * amended 2026-09-15, through {@link AccessibleNode#accepts}): a writable value facet implies
     * {@code SET_VALUE} and a text facet on a node that is not {@code READ_ONLY} implies
     * {@code SET_TEXT}, each only on an {@code ENABLED} node. Replaces fix round 2e's
     * {@code refusedSetter}, which refused on the enabled bit alone and posted a setter to a
     * read-only facet.
     *
     * <p>A node that is not {@code ENABLED} is refused with {@code UIA_E_ELEMENTNOTENABLED}, before
     * anything else, as the platform's own providers do ({@link #refusal}); its
     * {@code IsReadOnly} stays the facet's truth (ADR 039 §1.2: enabled and read-only are never
     * conflated).
     *
     * @param context what to read and post through
     * @param nodeId  the node the pattern was vended for
     * @param setter  {@code SET_VALUE} or {@code SET_TEXT}
     * @param arg     what it carries
     * @return {@code S_OK} when posted and accepted by the scene, otherwise the refusal
     */
    static int postSetter(UiaProvider.Context context, long nodeId, Accessible.Action setter,
                          Accessible.Argument arg) {
        AccessibleNode node = context.tree().find(nodeId);
        if (node == null) {
            return UiaIds.E_ELEMENT_NOT_AVAILABLE;
        }
        if (!node.accepts(setter)) {
            return refusal(node);
        }
        return accepted(context.perform(nodeId, setter, arg));
    }

    /**
     * What a verb or a setter the node does not accept is refused with: {@code
     * UIA_E_ELEMENTNOTENABLED} (0x80040200) on a node that is not {@code ENABLED} -- disabled, under
     * a disabled ancestor, or outside the layer that owns input -- and {@code 0x80131509} on one
     * that is enabled and simply does not offer it.
     *
     * <p><b>Both numbers were read on the guest, and this is where they are used.</b> 0x80040200 is
     * the managed {@code UIA_E_ELEMENTNOTENABLED} and 0x80131509 the {@code HResult} of
     * {@code System.InvalidOperationException}, each read on the Windows 11 ARM64 guest
     * (10.0.26200, UIAutomationCore.dll 7.2.26100.9278) on 2026-09-13 by
     * {@code scripts/a11y/windows/dump-uia-hresults.ps1}
     * (readings/windows-dump-uia-hresults.txt); {@link UiaIds#E_ELEMENT_NOT_ENABLED} and
     * {@link UiaIds#E_INVALID_OPERATION} carry the full reading, and neither header spelling is
     * read.
     *
     * <p>The order is the platform's own providers', read as IL on the guest 2026-09-15
     * (readings/windows-dump-uia-provider-conventions.txt §1b): {@code ButtonAutomationPeer.Invoke},
     * {@code ToggleButtonAutomationPeer.Toggle}, {@code ExpanderAutomationPeer} and
     * {@code TreeViewItemAutomationPeer}'s {@code Expand}/{@code Collapse},
     * {@code SelectorItemAutomationPeer}'s {@code Select}/{@code AddToSelection}/
     * {@code RemoveFromSelection}, {@code TextBoxAutomationPeer.SetValue} and
     * {@code RangeBaseAutomationPeer.SetValue} all begin {@code call AutomationPeer::IsEnabled();
     * brtrue; newobj ElementNotEnabledException; throw}, and only then throw
     * {@code InvalidOperationException} for what they cannot do.
     *
     * <p><b>A choice and not a reading: {@code SetFocus} and {@code ScrollIntoView}.</b> They were
     * read separately, the same day
     * (readings/windows-dump-uia-focus-and-scroll-item.txt, {@code
     * scripts/a11y/windows/dump-uia-focus-and-scroll-item.ps1}), and the platform's providers do not
     * agree on them, so no reading settles what this bridge answers and what follows is argued.
     * The client-side proxies of the Win32 controls follow the order above:
     * {@code ProxySimple}'s {@code IRawElementProviderFragment.SetFocus} throws
     * {@code ElementNotEnabledException} when the window is not enabled and
     * {@code InvalidOperationException} when the element is not keyboard-focusable, and
     * {@code ListViewItem}'s and {@code WindowsTabItem}'s {@code ScrollIntoView} throw
     * {@code ElementNotEnabledException} before {@code InvalidOperationException} for a container
     * that cannot scroll. WPF checks no enabled bit for either: {@code ElementProxy.SetFocus}
     * reaches {@code UIElementAutomationPeer.SetFocusCore}, which throws
     * {@code InvalidOperationException} when {@code UIElement.Focus()} refuses, and its item peers'
     * {@code ScrollIntoView} scroll whatever the item's state, as do the list-box and tree-view item
     * proxies. <b>The choice: this bridge answers both the way it answers every other verb, which
     * is the Win32 proxies' order.</b> Its reasoning is that one answer for every refusal is the
     * only one a client can rely on — a reader that learns 0x80040200 means "disabled" from
     * {@code Invoke} would have to learn a second rule for {@code SetFocus} alone — and that the
     * Win32 proxies are what a reader meets on the desktop's own controls, while WPF's silence is
     * the absence of a check rather than a decision to answer something else. It was settled again
     * by the orchestrator after phase 3 (2026-09-15: "Windows answering 0x80040200 for a verb on a
     * node that is not ENABLED is kept, the same reading as the setter case"), and it is Windows
     * open question 1, which phase 5 hears NVDA's side of.
     */
    static int refusal(AccessibleNode node) {
        return node.has(Accessible.State.ENABLED) ? UiaIds.E_INVALID_OPERATION
                : UiaIds.E_ELEMENT_NOT_ENABLED;
    }

    /**
     * Posts the first of an ordered candidate list the node accepts now (semantics 5, read through
     * {@link AccessibleNode#accepts}), and refuses synchronously when it accepts none.
     *
     * @param context    what to read and post through
     * @param nodeId     the node the pattern was vended for
     * @param candidates the verbs in the order the platform entry point maps them
     * @return {@code S_OK} when posted and accepted by the scene; {@code E_ELEMENT_NOT_AVAILABLE}
     *         for a node gone from the snapshot (or refused by the scene, which is what a refusal
     *         there almost always is); for a node that publishes none of the candidates,
     *         {@link #refusal}'s answer: {@code E_ELEMENT_NOT_ENABLED} when it is not
     *         {@code ENABLED}, {@code E_INVALID_OPERATION} otherwise
     */
    static int postFirstAccepted(UiaProvider.Context context, long nodeId,
                                 Accessible.Action... candidates) {
        AccessibleNode node = context.tree().find(nodeId);
        if (node == null) {
            return UiaIds.E_ELEMENT_NOT_AVAILABLE;
        }
        for (Accessible.Action candidate : candidates) {
            if (node.accepts(candidate)) {
                return accepted(context.perform(nodeId, candidate, Accessible.Argument.NONE));
            }
        }
        return refusal(node);
    }

    /**
     * @param wasAccepted whether the scene took the request
     * @return {@code S_OK}, or the code §1.3 gives a client holding an element for a node that has
     *         gone — which is what a refusal here almost always is
     */
    static int accepted(boolean wasAccepted) {
        return wasAccepted ? UiaIds.S_OK : UiaIds.E_ELEMENT_NOT_AVAILABLE;
    }

    /** Reads a {@code BSTR} a client passed in, which is length-prefixed UTF-16. */
    static String bstrOf(long bstr) {
        if (bstr == 0) {
            return "";
        }
        int bytes = MemoryUtil.memGetInt(bstr - 4);
        StringBuilder text = new StringBuilder(Math.max(0, bytes / 2));
        for (int i = 0; i < bytes / 2; i++) {
            text.append((char) MemoryUtil.memGetShort(bstr + (long) i * 2));
        }
        return text.toString();
    }
}
