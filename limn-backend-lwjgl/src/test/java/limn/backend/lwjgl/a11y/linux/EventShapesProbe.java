package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.graphics.ShapedText;
import limn.i18n.I18nString;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Every event shape the Linux bridge sends, sent for real with no window, for a libatspi client on
 * the same machine to read ({@code scripts/a11y/linux/events-check.py}).
 *
 * <p>Not a test: it does not assert, it prints. It opens a bridge the way a window does
 * ({@link AtspiBridge#open}) and drives a real {@link Accessibility} difference through a scripted
 * sequence, handing the bridge every event each publish found exactly as a scene does: the window
 * activating with a focused list, the list's cursor moving, a text replacement and a caret move, an
 * announcement, a row arriving and a row leaving, a branch gaining its triangle and opening, a
 * publish wide enough to collapse to {@code INVALIDATED} while the focus moves, and the window
 * deactivating. Each step prints its label with a timestamp first, so the client's lines can be read
 * against it. The steps start once the application has joined, and are 1.5 s apart.
 *
 * <pre>
 * java -cp &lt;limn-toolkit main classes&gt;:&lt;backend main classes&gt;:&lt;backend test classes&gt; \
 *     limn.backend.lwjgl.a11y.linux.EventShapesProbe
 * </pre>
 */
public final class EventShapesProbe {

    private EventShapesProbe() {
    }

    private static final long WINDOW = 1_000;
    private static final long LIST = 1_001;
    private static final long FIELD = 1_002;
    private static final long BRANCH = 1_003;
    private static final long BUTTON = 1_004;
    private static final long FIRST_ROW = 1_100;
    private static final long FIRST_LABEL = 2_000;

    /** What one publish of the probe's window holds. */
    private static final class State {
        boolean active;
        long focused = LIST;
        List<Long> rows = List.of(FIRST_ROW, FIRST_ROW + 1, FIRST_ROW + 2);
        long cursor = FIRST_ROW;
        String text = "abc";
        int caret = 3;
        Boolean expanded;
        boolean labelsShowing = true;
    }

    private static AccessibleTree build(Accessibility a, State s) {
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(WINDOW, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("Limn event probe"), Accessible.NameFrom.EXPLICIT);
        if (s.active) {
            a.state(Accessible.State.ACTIVE);
        }
        a.inherited(true, true, true, false, false);

        a.begin(LIST, 0, Locale.ENGLISH, 0, 0, 200, 100);
        a.role(Accessible.Role.LIST);
        a.name(I18nString.literal("Rows"), Accessible.NameFrom.EXPLICIT);
        a.selection(false, false);
        a.inherited(true, true, true, true, s.focused == LIST);
        for (int i = 0; i < s.rows.size(); i++) {
            long row = s.rows.get(i);
            a.begin(row, 1, Locale.ENGLISH, 0, 20 * i, 200, 20);
            a.role(Accessible.Role.LIST_ITEM);
            a.name(I18nString.literal("Row " + (row - FIRST_ROW + 1)), Accessible.NameFrom.CONTENT);
            a.selectionItem(row == s.cursor, i + 1, s.rows.size());
            if (row == s.cursor && s.focused == LIST) {
                a.state(Accessible.State.ACTIVE);
            }
            a.inherited(true, true, true, false, false);
            a.end();
        }
        a.end();

        a.begin(FIELD, 0, Locale.ENGLISH, 0, 110, 200, 30);
        a.role(Accessible.Role.TEXT_FIELD);
        a.name(I18nString.literal("Note"), Accessible.NameFrom.EXPLICIT);
        a.text(s.text, s.text.hashCode(), s.caret, ShapedText.Affinity.DOWNSTREAM, s.caret, s.caret,
                1, null, false);
        a.inherited(true, true, true, true, s.focused == FIELD);
        a.end();

        a.begin(BRANCH, 0, Locale.ENGLISH, 0, 150, 200, 20);
        a.role(Accessible.Role.TREE_ITEM);
        a.name(I18nString.literal("Media"), Accessible.NameFrom.CONTENT);
        if (s.expanded != null) {
            a.expand(s.expanded);
        }
        a.inherited(true, true, true, true, false);
        a.end();

        a.begin(BUTTON, 0, Locale.ENGLISH, 0, 180, 100, 30);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, true, s.focused == BUTTON);
        a.end();

        for (int i = 0; i < 300; i++) {
            a.begin(FIRST_LABEL + i, 0, Locale.ENGLISH, 220, i, 100, 1);
            a.role(Accessible.Role.LABEL);
            a.name(I18nString.literal("Label " + i), Accessible.NameFrom.CONTENT);
            a.inherited(true, true, s.labelsShowing, false, false);
            a.end();
        }
        a.end();
        return a.publish(s.focused, 0, 0, 1f, true);
    }

    private static void step(AtspiBridge bridge, Accessibility a, State s, String label) {
        System.out.println(Instant.now() + " STEP " + label);
        AccessibleTree tree = build(a, s);
        bridge.publish(tree, false);
        List<AccessibleEvent> events = List.copyOf(a.events());
        for (AccessibleEvent event : events) {
            bridge.emit(event);
        }
        System.out.println(Instant.now() + "   events " + events.size() + ": "
                + (events.size() > 12 ? events.subList(0, 12) + " …" : events));
    }

    private static final class QuietHost implements AccessibilityBridge.Host {
        @Override public void requestRepublish() { }
        @Override public void requestRestamp() { }
        @Override public AccessibleTree republishNow() { return AccessibleTree.EMPTY; }
        @Override public boolean perform(long nodeId, Accessible.Action action,
                                         Accessible.Argument arg) {
            return false;
        }
    }

    /**
     * @param args ignored
     */
    public static void main(String[] args) throws InterruptedException {
        AccessibilityBridge opened = AtspiBridge.open("Limn event probe");
        System.out.println(Instant.now() + " bridge: " + opened.getClass().getSimpleName());
        if (!(opened instanceof AtspiBridge bridge)) {
            System.out.println("no session bus this client can open; nothing to send");
            return;
        }
        bridge.attach(new QuietHost());
        Accessibility a = new Accessibility();
        State s = new State();
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!bridge.isListening() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        step(bridge, a, s, "first publish: inactive window, list focused, cursor on row 1");
        while (!bridge.isOnTheBus() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        System.out.println(Instant.now() + " onTheBus=" + bridge.isOnTheBus());
        Thread.sleep(4_000);  // the listener attaches meanwhile

        s.active = true;
        step(bridge, a, s, "window activates (Activate from the frame, focus and cursor again)");
        Thread.sleep(1_500);
        s.cursor = FIRST_ROW + 1;
        step(bridge, a, s, "cursor to row 2 (ActiveDescendantChanged with the row's reference)");
        Thread.sleep(1_500);
        s.focused = FIELD;
        step(bridge, a, s, "focus to the field");
        Thread.sleep(1_500);
        s.text = "a😀c";
        s.caret = 3;
        step(bridge, a, s, "replace b with an emoji (delete b, insert the emoji; caret 2)");
        Thread.sleep(1_500);
        System.out.println(Instant.now() + " STEP announcement Saved (polite)");
        bridge.emit(AccessibleEvent.announcement("Saved", Accessible.Politeness.POLITE));
        Thread.sleep(1_500);
        s.focused = LIST;
        s.rows = List.of(FIRST_ROW, FIRST_ROW + 1, FIRST_ROW + 2, FIRST_ROW + 3);
        step(bridge, a, s, "focus back to the list, row 4 arrives at index 3");
        Thread.sleep(1_500);
        s.rows = List.of(FIRST_ROW, FIRST_ROW + 2, FIRST_ROW + 3);
        s.cursor = FIRST_ROW + 2;
        step(bridge, a, s, "row 2 leaves from index 1, the cursor moves to row 3");
        Thread.sleep(1_500);
        s.expanded = false;
        step(bridge, a, s, "the branch gains its triangle, closed (expandable 1, collapsed 1)");
        Thread.sleep(1_500);
        s.expanded = true;
        step(bridge, a, s, "the branch opens (expanded 1, collapsed 0)");
        Thread.sleep(1_500);
        s.labelsShowing = false;
        s.focused = BUTTON;
        step(bridge, a, s, "300 labels stop showing and the focus moves to Save (INVALIDATED)");
        Thread.sleep(1_500);
        s.active = false;
        step(bridge, a, s, "window deactivates");
        Thread.sleep(2_000);
        System.out.println(Instant.now() + " DONE");
    }
}
