package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.i18n.I18nString;

import java.time.Instant;
import java.util.Locale;

/**
 * The process's AT-SPI application and its switch watch, run for real with no window: what a
 * machine's own bus and registry do with the join, the watch and the teardown.
 *
 * <p>Not a test: it does not assert, it prints. It opens a bridge the way a window does
 * ({@link AtspiBridge#open}), attaches a host that publishes a one-button tree whenever it is asked
 * for a publish — which is what a scene does on the frame the host buys — and prints every change of
 * {@code isListening()} and {@code isOnTheBus()} with a timestamp for as long as it is told to run.
 * Flip {@code org.a11y.Status.IsEnabled} while it runs ({@code read-a11y-status-signal.sh --flip})
 * and the lines say whether the watch followed and the application left and rejoined; walk the
 * desktop with {@code walk-the-probe.py} to see it listed. With {@code popup} it opens a second window
 * of the same process part-way through and closes it again, for {@code frames-check.py} to read one
 * application with two frames, the popup's relation into the other frame and the two
 * {@code children-changed} events. Run with {@code -Dprobe.trace=true} to see the SASL exchange.
 *
 * <p>The host here publishes on whichever thread asks, the status thread included, which a scene
 * never does; it is a probe of the bus's side, not of the threading.
 *
 * <pre>
 * java -cp &lt;limn-toolkit main classes&gt;:&lt;backend main classes&gt;:&lt;backend test classes&gt; \
 *     limn.backend.lwjgl.a11y.linux.StatusWatchProbe [seconds] [popup]
 * </pre>
 */
public final class StatusWatchProbe {

    private StatusWatchProbe() {
    }

    /**
     * A window holding one button, published once and handed out again on every ask, so its ids
     * stay the same across the publishes a switch flip buys.
     *
     * @param title    the window's name
     * @param popupFor the node this window is the popup of, or 0
     */
    private static AccessibleTree aWindow(String title, long popupFor) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        long window = a.mint();
        long button = a.mint();
        a.begin(window, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal(title), Accessible.NameFrom.EXPLICIT);
        if (popupFor != 0) {
            a.relation(Accessible.Relation.POPUP_FOR, popupFor);
        }
        a.inherited(true, true, true, false, false);
        a.begin(button, 0, Locale.ENGLISH, 10, 20, 160, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        a.resolveRelations((kind, target) -> (Long) target);
        return a.publish(0, 0, 0, 1f, true);
    }

    /** A host that publishes {@code tree} whenever it is asked, as a scene does on the bought frame. */
    private static AccessibilityBridge.Host publishing(AtspiBridge bridge, AccessibleTree tree) {
        return new AccessibilityBridge.Host() {
            @Override public void requestRepublish() {
                System.out.println(Instant.now() + " host asked for a publish");
                bridge.publish(tree, false);
            }
            @Override public void requestRestamp() { }
            @Override public AccessibleTree republishNow() { return bridge.tree(); }
            @Override public boolean perform(long nodeId, Accessible.Action action,
                                             Accessible.Argument arg) {
                System.out.println(Instant.now() + " perform " + action + " on " + nodeId);
                return true;
            }
        };
    }

    /**
     * @param args the number of seconds to run, 20 by default; then {@code popup} to open a second
     *             window six seconds in — a popup whose root is POPUP_FOR the first window's button —
     *             and close it six seconds later
     */
    public static void main(String[] args) throws InterruptedException {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        boolean popupMode = args.length > 1 && "popup".equals(args[1]);
        AccessibilityBridge opened = AtspiBridge.open("Limn status probe");
        System.out.println(Instant.now() + " bridge: " + opened.getClass().getSimpleName());
        if (!(opened instanceof AtspiBridge bridge)) {
            System.out.println("no session bus this client can open; nothing to watch");
            return;
        }
        AccessibleTree main = aWindow("Limn status probe", 0);
        bridge.attach(publishing(bridge, main));
        long start = System.nanoTime();
        AtspiBridge popup = null;
        boolean popupClosed = false;
        boolean listening = false;
        boolean onTheBus = false;
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        System.out.println(Instant.now() + " listening=false onTheBus=false");
        while (System.nanoTime() < end) {
            boolean l = bridge.isListening();
            boolean b = bridge.isOnTheBus();
            if (l != listening || b != onTheBus) {
                listening = l;
                onTheBus = b;
                System.out.println(Instant.now() + " listening=" + l + " onTheBus=" + b
                        + " joinAttempts=" + bridge.joinAttempts());
            }
            if (l && bridge.tree().nodeCount() == 0) {
                // A scene's first frame after its bind: it publishes when the gate is open, whether
                // or not the switch arrived before the host was attached to ask for one.
                bridge.publish(main, false);
                System.out.println(Instant.now() + " first frame published");
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            if (popupMode && popup == null && elapsed > 6_000) {
                popup = (AtspiBridge) AtspiBridge.open("Limn status probe");
                AccessibleTree calendar = aWindow("Calendar popup", main.node(1).id());
                popup.attach(publishing(popup, calendar));
                popup.publish(calendar, false);
                System.out.println(Instant.now() + " popup opened: POPUP_FOR " + main.node(1).id());
            }
            if (popupMode && popup != null && !popupClosed && elapsed > 12_000) {
                popup.detach();
                popupClosed = true;
                System.out.println(Instant.now() + " popup closed");
            }
            Thread.sleep(20);
        }
        System.out.println(Instant.now() + " threads: " + Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName).filter(n -> n.startsWith("limn-")).sorted().toList());
        System.out.println(Instant.now() + " DONE");
    }
}
