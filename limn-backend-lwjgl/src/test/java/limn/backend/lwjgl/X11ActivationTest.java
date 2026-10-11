package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the X11 activation reads off a panel's token before it asks the server anything. */
class X11ActivationTest {

    @Test
    void aStartupIdentifierEndsWithTheTimeOfTheUsersAction() {
        assertEquals(132999883L, X11Activation.timeOf(
                "gnome-shell/limn-showcase/2812-3-ubuntu-gnu-linux-24-04-3_TIME132999883"));
        assertEquals(0L, X11Activation.timeOf("kwin-23"), "a token with no time names none");
        assertEquals(0L, X11Activation.timeOf("x_TIME12_more"));
        assertEquals(0L, X11Activation.timeOf(null));
        assertEquals(0L, X11Activation.timeOf("x_TIME99999999999999999999999"));
        assertEquals(4294967295L, X11Activation.timeOf("x_TIME4294967295"));
    }
}
