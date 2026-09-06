package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The pushes a client waits on, against the names it subscribes by.
 *
 * <p>Everything else this bridge does answers a question a client thought to ask. These are the
 * ones it is told, and the detail string is not decoration: a client's match rule is literally
 * {@code object:state-changed:focused}, so a detail spelled the toolkit's way rather than the
 * platform's is an event nobody has subscribed to and therefore an event that does not arrive.
 */
class AtspiEventsTest {

    /**
     * Focus travels as a state change, and only once.
     *
     * <p>The dedicated Focus signal is deprecated and Orca subscribes to
     * {@code object:state-changed:focused}; the difference already raises {@code STATE_CHANGED} for
     * that bit on the node gaining it <em>and</em> the node losing it. Mapping {@code
     * FOCUS_CHANGED} as well sent the arrival twice and the departure once, which a listening
     * client showed plainly: a reader announces the newly focused control and then announces it
     * again.
     */
    @Test
    void focusTravelsAsAStateChangeAndIsNotAlsoSentAsItsOwnEvent() {
        assertNull(AtspiEvents.of(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 7)),
                "the state change below is the one that carries it, in both directions");

        AtspiEvents.Signal arriving = AtspiEvents.of(
                AccessibleEvent.state(7, Accessible.State.FOCUSED, true));
        assertEquals("StateChanged", arriving.member());
        assertEquals("focused", arriving.detail());
        assertEquals(1, arriving.detail1());
        assertEquals(0, AtspiEvents.of(
                AccessibleEvent.state(7, Accessible.State.FOCUSED, false)).detail1(),
                "and the departure, which only this one raises");
    }

    @Test
    void aStateCarriesThePlatformsSpellingAndWhetherItWentOnOrOff() {
        AtspiEvents.Signal on = AtspiEvents.of(AccessibleEvent.state(3, Accessible.State.CHECKED, true));
        assertEquals("checked", on.detail());
        assertEquals(1, on.detail1());

        AtspiEvents.Signal off = AtspiEvents.of(AccessibleEvent.state(3, Accessible.State.CHECKED, false));
        assertEquals(0, off.detail1());

        AtspiEvents.Signal hyphened = AtspiEvents.of(AccessibleEvent.state(3, Accessible.State.READ_ONLY, true));
        assertEquals("read-only", hyphened.detail(),
                "the platform hyphenates where the toolkit underscores, and a client's match "
                        + "string is the platform's");

        AtspiEvents.Signal mixed = AtspiEvents.of(AccessibleEvent.state(3, Accessible.State.MIXED, true));
        assertEquals("indeterminate", mixed.detail(),
                "and where it uses another word entirely, the word is the platform's");
    }

    @Test
    void aStateThisPlatformDoesNotCarryRaisesNothingRatherThanSomethingApproximate() {
        assertNull(AtspiEvents.of(AccessibleEvent.state(3, Accessible.State.PASSWORD, true)),
                "being a password is the role here, and the nearest-looking bit means the entry "
                        + "was rejected: an approximate event is worse than none");
        assertNull(AtspiEvents.of(AccessibleEvent.announcement(
                        "saved", Accessible.Politeness.POLITE)),
                "an announcement is a message to the user rather than a fact about a node, and "
                        + "this platform carries it another way");
    }

    @Test
    void aNameChangeCarriesTheNewNameUnderThePropertyAClientAsksFor() {
        AtspiEvents.Signal renamed = AtspiEvents.of(AccessibleEvent.property(
                AccessibleEvent.Type.NAME_CHANGED, 5, "Save", "Save as"));

        assertEquals("PropertyChange", renamed.member());
        assertEquals("accessible-name", renamed.detail());
        assertEquals("Save as", renamed.value().value);
    }

    @Test
    void aWindowEventGoesOutOnTheWindowInterfaceAndNotTheObjectOne() {
        AtspiEvents.Signal opened = AtspiEvents.of(AccessibleEvent.of(
                AccessibleEvent.Type.WINDOW_OPENED, 0));

        assertEquals(AtspiEvents.I_EVENT_WINDOW, opened.iface(),
                "a desktop shell watches these and a screen reader watches the object ones");
        assertEquals("Create", opened.member());
        assertEquals(AtspiEvents.I_EVENT_OBJECT,
                AtspiEvents.of(AccessibleEvent.of(AccessibleEvent.Type.BOUNDS_CHANGED, 1)).iface());
    }

    @Test
    void theBodyCarriesTheApplicationItCameFrom() {
        AtspiEvents.Signal signal = AtspiEvents.of(
                AccessibleEvent.state(7, Accessible.State.FOCUSED, true));
        Object[] body = AtspiEvents.body(signal, new DBus.Ref(":1.9", Atspi.PATH_ROOT));

        assertEquals(5, body.length,
                "detail, two integers, the value and the sender -- and nothing after: at-spi2 "
                        + "rejects the whole signal for a trailing dictionary, and logs it against "
                        + "the interface rather than the sender, so the application looks silent");
        assertEquals(5, AtspiEvents.SIGNATURE.replace("(so)", "x").length());
        assertEquals("focused", body[0]);
        assertEquals(":1.9", DBus.Ref.of(body[4]).name,
                "a client resolves the event against the application that sent it");
    }
}
