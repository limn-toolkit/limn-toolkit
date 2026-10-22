package limn.backend.lwjgl.a11y.linux;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the print portal is sent, built without a bus: the page setup the dialog starts from, the
 * options of both calls, the request paths, the token read back, and the {@code Print} message with
 * its descriptor. The exchange itself was run against the GNOME and KDE portals on the guests.
 */
class PortalPrintTest {

    @Test
    void anA4PageIsNamedAndMeasuredInMillimetresPortrait() {
        Map<String, DBus.Variant> setup = PortalPrint.pageSetup(595.28f, 841.89f);
        assertEquals("iso_a4", setup.get("Name").value);
        assertEquals("A4", setup.get("DisplayName").value);
        assertEquals(210.0, setup.get("Width").value);
        assertEquals(297.0, setup.get("Height").value);
        assertEquals("portrait", setup.get("Orientation").value);
        for (String margin : List.of("MarginTop", "MarginBottom", "MarginLeft", "MarginRight")) {
            assertEquals(0.0, setup.get(margin).value, margin + ": the document's margins are on its pages");
        }
        assertEquals("d", setup.get("Width").sig);
    }

    @Test
    void aLetterPageWiderThanTallIsLetterInLandscape() {
        Map<String, DBus.Variant> setup = PortalPrint.pageSetup(792, 612);
        assertEquals("na_letter", setup.get("Name").value);
        assertEquals(215.9, setup.get("Width").value, "the paper's own width: the short side");
        assertEquals(279.4, setup.get("Height").value);
        assertEquals("landscape", setup.get("Orientation").value);
    }

    @Test
    void aPaperOfItsOwnHasASizeAndNoName() {
        Map<String, DBus.Variant> setup = PortalPrint.pageSetup(500, 500);
        assertFalse(setup.containsKey("Name"));
        assertEquals(176.39, setup.get("Width").value);
        assertEquals(176.39, setup.get("Height").value);
    }

    @Test
    void anUnknownPaperLeavesThePageSetupEmpty() {
        assertTrue(PortalPrint.pageSetup(0, 0).isEmpty(), "the dialog starts from the printer's own");
    }

    @Test
    void printCarriesTheTokenPreparePrintGaveAsAnUnsignedInteger() {
        Map<String, DBus.Variant> options = PortalPrint.printOptions("limn1_p2", 0xFFFF_FFFEL);
        assertEquals("limn1_p2", options.get("handle_token").value);
        assertEquals(Boolean.TRUE, options.get("modal").value);
        assertEquals("u", options.get("token").sig);
        assertEquals(0xFFFF_FFFE, options.get("token").value);
        assertFalse(PortalPrint.prepareOptions("x").containsKey("token"));
    }

    @Test
    void theTokenIsReadFromPreparePrintsResults() {
        assertEquals(7L, PortalPrint.tokenIn(Map.of("token", DBus.v("u", 7))));
        assertEquals(0xFFFF_FFFFL, PortalPrint.tokenIn(Map.of("token", DBus.v("u", -1))));
        assertNull(PortalPrint.tokenIn(Map.of("settings", DBus.v("a{sv}", Map.of()))));
        assertNull(PortalPrint.tokenIn("not a dictionary"));
    }

    @Test
    void theRequestPathIsTheSendersNameWithItsDotsAsUnderscores() {
        assertEquals("/org/freedesktop/portal/desktop/request/1_42/limn9_p1",
                PortalPrint.handle(":1.42", "limn9_p1"));
    }

    @Test
    void onlyThatRequestsResponseAnswers() {
        String handle = PortalPrint.handle(":1.42", "t");
        DBus.Msg response = DBus.Msg.signal(handle, PortalFileChooser.REQUEST, "Response", "ua{sv}", 0, Map.of());
        assertTrue(PortalPrint.isResponse(response, handle));
        assertFalse(PortalPrint.isResponse(response, PortalPrint.handle(":1.42", "other")));
        DBus.Msg other = DBus.Msg.signal(handle, PortalFileChooser.REQUEST, "Closed", "", new Object[0]);
        assertFalse(PortalPrint.isResponse(other, handle));
    }

    /**
     * The {@code Print} call: the descriptor's place in the body is its index, 0, written as a
     * uint32 exactly where a {@code u} would be, and the header says one descriptor travels with it.
     */
    @Test
    void thePrintCallCarriesOneDescriptorAtIndexZero() {
        Map<String, DBus.Variant> options = PortalPrint.printOptions("limn1_p2", 41);
        Object[] args = {"x11:3600007", "Invoice", 0, options};
        DBus.Msg call = DBus.Msg.call("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop",
                PortalPrint.PRINT, "Print", "ssha{sv}", args);
        call.unixFds = 1;
        byte[] raw = call.marshal(3);

        DBus.Msg header = DBus.Msg.parseHeader(raw);
        assertEquals(1, header.unixFds, "the UNIX_FDS field");
        assertEquals("ssha{sv}", header.signature);
        assertEquals("Print", header.member);

        byte[] withH = DBus.writeArgs("ssha{sv}", args);
        assertArrayEquals(DBus.writeArgs("ssua{sv}", args), withH, "an 'h' is a uint32 index on the wire");
        // and the index is 0, right after the two strings, 4-aligned
        DBus.Writer strings = new DBus.Writer();
        DBus.write(strings, "s", args[0]);
        DBus.write(strings, "s", args[1]);
        strings.align(4);
        assertEquals(0, ByteBuffer.wrap(withH).order(ByteOrder.LITTLE_ENDIAN).getInt(strings.pos));
    }

    @Test
    void aMessageWithNoDescriptorWritesNoUnixFdsField() {
        DBus.Msg call = DBus.Msg.call("a.b", "/", "a.b", "M", "s", "x");
        assertEquals(0, DBus.Msg.parseHeader(call.marshal(1)).unixFds);
    }

    @Test
    void onlyThePrintConnectionNegotiatesDescriptors() {
        assertEquals(List.of("AUTH EXTERNAL 31303030", "NEGOTIATE_UNIX_FD", "BEGIN"),
                DBus.Conn.saslCommands("1000", true));
        assertEquals(List.of("AUTH EXTERNAL 31303030", "BEGIN"), DBus.Conn.saslCommands("1000"));
    }
}
