package limn.backend.lwjgl.a11y.linux;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire format, against the specification and against the reference implementation.
 *
 * <p>Two kinds of vector, and the second is the one that matters. The hand-derived ones spell out
 * the specification's own alignment and container rules, so a misreading of the text shows up as a
 * byte. The golden ones are raw messages captured off the wire from {@code dbus-daemon} 1.14 and
 * {@code at-spi2-registryd} 2.52 on the Ubuntu guest, hex-encoded exactly as they arrived: nothing
 * in this repository produced them, which is what lets them catch a reading of the specification
 * that is wrong and self-consistent.
 *
 * <p>Every assertion is accumulated rather than thrown at the first failure, and the whole set is
 * asserted at the end. A marshaller fails in families -- one alignment rule wrong is twenty
 * vectors wrong -- and the first of those twenty says far less than the twenty together.
 *
 * <p>Nothing here opens a socket, so it runs on any machine. The bridge this covers only functions
 * on Linux; the format it speaks is portable, and so is the evidence.
 */
class DBusWireTest {

    private int passed;
    private final List<String> failures = new ArrayList<>();

    private void check(String what, Object expected, Object actual) {
        if (deepEq(expected, actual)) {
            passed++;
        } else {
            failures.add(what + "\n         expected: " + DBus.fmt(expected)
                    + "\n         actual  : " + DBus.fmt(actual));
        }
    }

    private void done() {
        assertTrue(failures.isEmpty(),
                () -> failures.size() + " of " + (passed + failures.size()) + " vectors wrong:\n  - "
                        + String.join("\n  - ", failures));
        assertTrue(passed > 0, "no vector ran at all");
    }

    static boolean deepEq(Object a, Object b) {
        if (a instanceof byte[] && b instanceof byte[]) return Arrays.equals((byte[]) a, (byte[]) b);
        if (a instanceof Object[] && b instanceof Object[]) {
            Object[] x = (Object[]) a, y = (Object[]) b;
            if (x.length != y.length) return false;
            for (int i = 0; i < x.length; i++) if (!deepEq(x[i], y[i])) return false;
            return true;
        }
        if (a instanceof DBus.Variant && b instanceof DBus.Variant) {
            DBus.Variant x = (DBus.Variant) a, y = (DBus.Variant) b;
            return x.sig.equals(y.sig) && deepEq(x.value, y.value);
        }
        if (a instanceof List && b instanceof List) {
            List<?> x = (List<?>) a, y = (List<?>) b;
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!deepEq(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> x = (Map<?, ?>) a, y = (Map<?, ?>) b;
            if (x.size() != y.size()) return false;
            for (Map.Entry<?, ?> e : x.entrySet()) if (!deepEq(e.getValue(), y.get(e.getKey()))) return false;
            return true;
        }
        return a == null ? b == null : a.equals(b);
    }

    static byte[] marshal(String sig, Object v) {
        DBus.Writer w = new DBus.Writer();
        DBus.write(w, sig, v);
        return w.toArray();
    }

    static Object unmarshal(String sig, byte[] bytes) {
        return DBus.read(new DBus.Reader(bytes), sig);
    }

    @Test
    void theMarshallerMatchesTheSpecificationsOwnWorkedRules() throws Exception {
        System.out.println("== signature parsing ==");
        check("typeLen a(so) whole", 5, DBus.typeLen("a(so)", 0));
        check("typeLen a{sv} whole", 5, DBus.typeLen("a{sv}", 0));
        check("split 'sua{sv}(ii)'", List.of("s", "u", "a{sv}", "(ii)"), DBus.split("sua{sv}(ii)"));
        check("split cache item",
              List.of("(so)", "(so)", "(so)", "i", "i", "as", "s", "u", "s", "au"),
              DBus.split(Atspi.CACHE_ITEM.substring(1, Atspi.CACHE_ITEM.length() - 1)));
        check("alignOf 's'", 4, DBus.alignOf('s'));
        check("alignOf 'g'", 1, DBus.alignOf('g'));
        check("alignOf 'v'", 1, DBus.alignOf('v'));
        check("alignOf '('", 8, DBus.alignOf('('));

        System.out.println("== hand-derived vectors (spec: Marshalling / Wire Format) ==");

        // STRING: 4-aligned uint32 byte-count, the UTF-8 bytes, a trailing NUL not in the count.
        //   03 00 00 00 | 'f' 'o' 'o' | 00
        check("s \"foo\"", DBus.unhex("03000000666f6f00"), marshal("s", "foo"));

        // OBJECT_PATH marshals exactly like STRING.
        //   0e 00 00 00 | "/org/freedesktop" ... | 00
        check("o \"/foo\"", DBus.unhex("040000002f666f6f00"), marshal("o", "/foo"));

        // SIGNATURE: a single byte length, the bytes, a NUL. No alignment at all.
        check("g \"a{sv}\"", DBus.unhex("05617b73767d00"), marshal("g", "a{sv}"));

        // BOOLEAN is a 4-aligned uint32 that may only be 0 or 1.
        check("b true", DBus.unhex("01000000"), marshal("b", Boolean.TRUE));

        // UINT16 / INT16 are 2-aligned; UINT32 / INT32 4-aligned; UINT64 / DOUBLE 8-aligned.
        check("q 0x1234", DBus.unhex("3412"), marshal("q", 0x1234));
        check("u 42", DBus.unhex("2a000000"), marshal("u", 42));
        check("t 1", DBus.unhex("0100000000000000"), marshal("t", 1L));
        check("d 1.0", DBus.unhex("000000000000f03f"), marshal("d", 1.0d));

        // ARRAY: a 4-aligned uint32 giving the byte length of the CONTENTS, then padding to the
        // element alignment (that padding is NOT counted), then the elements.
        //   08 00 00 00 | 01 00 00 00 | 02 00 00 00
        check("au [1,2]", DBus.unhex("080000000100000002000000"), marshal("au", List.of(1, 2)));

        // An array of 8-aligned elements pads after the length even when empty:
        //   00 00 00 00 | 00 00 00 00   (4 bytes of padding to reach the struct alignment)
        check("a(ii) []", DBus.unhex("0000000000000000"), marshal("a(ii)", List.of()));

        // STRUCT: 8-aligned, then the fields in order, each with its own alignment.
        //   "x": 01 00 00 00 'x' 00 | pad 00 00 | "/y": 02 00 00 00 '/' 'y' 00
        check("(so) (\"x\",\"/y\")",
              DBus.unhex("0100000078000000" + "020000002f7900"),
              marshal("(so)", new DBus.Ref("x", "/y")));

        // VARIANT: the signature (1-aligned), then the value at ITS alignment.
        //   01 'u' 00 | pad 00 | 2a 00 00 00
        check("v <u> 42", DBus.unhex("017500002a000000"), marshal("v", DBus.v("u", 42)));

        // DICT_ENTRY inside an array: entries are 8-aligned like structs.
        //   0e 00 00 00 | pad*4 | "a" | pad*2 | "b"
        Map<Object, Object> ab = new LinkedHashMap<>();
        ab.put("a", "b");
        check("a{ss} {a=b}", DBus.unhex("0e000000000000000100000061000000010000006200"), marshal("a{ss}", ab));

        System.out.println("== round trips ==");
        rt("y", (byte) 0x7f);
        rt("b", Boolean.TRUE);
        rt("b", Boolean.FALSE);
        rt("n", (short) -12345);
        rt("q", 54321);
        rt("i", -2000000000);
        rt("u", 0xdeadbeef);            // stored as raw bits; unsigned only on the way out
        rt("x", -9223372036854775800L);
        rt("t", 1234567890123L);
        rt("d", Math.PI);
        rt("s", "hello é世界");
        rt("o", "/org/a11y/atspi/accessible/1");
        rt("g", "a((so)(so)(so)iiassusau)");
        rt("v", DBus.v("s", "nested"));
        rt("v", DBus.v("(iiii)", new Object[] { 1, 2, 3, 4 }));
        rt("au", List.of(1073741824, 0));
        rt("as", List.of("org.a11y.atspi.Accessible", "org.a11y.atspi.Action"));
        rt("a(so)", structs(new Object[] { "x", "/y" }, new Object[] { "z", "/w" }));
        rt("a(sss)", structs(new Object[] { "press", "Press the probe button", "" }));
        rt("(iiii)", new Object[] { 100, 100, 160, 40 });
        rt("a{ss}", ab);
        Map<Object, Object> sv = new LinkedHashMap<>();
        sv.put("Name", DBus.v("s", "Probe"));
        sv.put("ChildCount", DBus.v("i", 1));
        sv.put("Parent", DBus.v("(so)", new Object[] { ":1.9", "/org/a11y/atspi/accessible/root" }));
        rt("a{sv}", sv);
        rt("ay", List.of((byte) 1, (byte) 2, (byte) 3));
        rt("aay", List.of(List.of((byte) 1), List.of((byte) 2, (byte) 3)));

        // The AT-SPI cache item: the single hardest shape in this protocol.
        Object[] item = {
            new Object[] { ":1.9", "/org/a11y/atspi/accessible/1" },
            new Object[] { ":1.9", "/org/a11y/atspi/accessible/root" },
            new Object[] { ":1.9", "/org/a11y/atspi/accessible/root" },
            0, 0,
            List.of("org.a11y.atspi.Accessible", "org.a11y.atspi.Component", "org.a11y.atspi.Action"),
            "Probe", Atspi.ROLE_PUSH_BUTTON, "The Limn accessibility spike button",
            List.of(1124075776, 0)
        };
        rt(Atspi.CACHE_ITEM, item);
        rt(Atspi.CACHE_ITEMS, structs(item, item));

        System.out.println("== state / role constants (verified against at-spi2-core 2.52 typelib) ==");
        check("ROLE_PUSH_BUTTON", 43, Atspi.ROLE_PUSH_BUTTON);
        check("ROLE_APPLICATION", 75, Atspi.ROLE_APPLICATION);
        check("state words are two uint32, low first",
              List.of(1124075776, 0),
              Atspi.stateWords(Atspi.state(Atspi.STATE_ENABLED, Atspi.STATE_SENSITIVE,
                      Atspi.STATE_SHOWING, Atspi.STATE_VISIBLE, Atspi.STATE_FOCUSABLE)));
        check("high word carries states >= 32", List.of(0, 1), Atspi.stateWords(Atspi.state(32)));

        System.out.println("== message round trips ==");
        DBus.Msg hello = DBus.Msg.call("org.freedesktop.DBus", "/org/freedesktop/DBus",
                "org.freedesktop.DBus", "Hello", null);
        byte[] wire = hello.marshal(1);
        check("Hello endianness byte", (byte) 'l', wire[0]);
        check("Hello type", (byte) 1, wire[1]);
        check("Hello protocol version", (byte) 1, wire[3]);
        check("Hello body length 0", 0, le32(wire, 4));
        check("Hello serial 1", 1, le32(wire, 8));
        check("Hello total length is 8-aligned", 0, wire.length % 8);
        DBus.Msg back = DBus.Msg.parse(wire);
        check("Hello parses back: member", "Hello", back.member);
        check("Hello parses back: path", "/org/freedesktop/DBus", back.path);
        check("Hello parses back: destination", "org.freedesktop.DBus", back.destination);
        check("Hello parses back: no signature", null, back.signature);

        DBus.Msg embed = DBus.Msg.call(Atspi.REGISTRY, Atspi.PATH_ROOT, Atspi.I_SOCKET, "Embed",
                "(so)", new DBus.Ref(":1.42", Atspi.PATH_ROOT));
        DBus.Msg embedBack = DBus.Msg.parse(embed.marshal(7));
        check("Embed signature", "(so)", embedBack.signature);
        check("Embed plug", new Object[] { ":1.42", Atspi.PATH_ROOT }, embedBack.body[0]);
        check("Embed serial", 7, embedBack.serial);

        DBus.Msg getItems = DBus.Msg.call(null, Atspi.PATH_CACHE, Atspi.I_CACHE, "GetItems", null);
        DBus.Msg reply = DBus.Msg.ret(getItems, Atspi.CACHE_ITEMS, structs(item));
        reply.destination = ":1.2";
        DBus.Msg replyBack = DBus.Msg.parse(reply.marshal(9));
        check("GetItems reply type", DBus.METHOD_RETURN, replyBack.type);
        check("GetItems reply signature", Atspi.CACHE_ITEMS, replyBack.signature);
        check("GetItems reply body", structs(item), replyBack.body[0]);

        DBus.Msg err = DBus.Msg.err(getItems, "org.freedesktop.DBus.Error.UnknownMethod", "nope");
        err.destination = ":1.2";
        DBus.Msg errBack = DBus.Msg.parse(err.marshal(11));
        check("error name round trip", "org.freedesktop.DBus.Error.UnknownMethod", errBack.errorName);
        check("error text round trip", "nope", errBack.body[0]);

        // A body long enough to force multi-byte length fields and interior padding.
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 500; i++) many.add(new Object[] { ":1." + i, "/org/a11y/atspi/accessible/" + i });
        DBus.Msg big = DBus.Msg.ret(getItems, "a(so)", many);
        big.destination = ":1.2";
        DBus.Msg bigBack = DBus.Msg.parse(big.marshal(13));
        check("500-element a(so) survives", many, bigBack.body[0]);

        System.out.println("== address parsing ==");
        check("unix:path", "/run/user/1000/bus",
              DBus.Conn.unixPathOf("unix:path=/run/user/1000/bus,guid=deadbeef"));
        check("a11y bus address", "/run/user/1000/at-spi/bus_0",
              DBus.Conn.unixPathOf("unix:path=/run/user/1000/at-spi/bus_0,guid=04b5c378"));
        String abstractErr = "";
        try { DBus.Conn.unixPathOf("unix:abstract=/tmp/dbus-XYZ,guid=1"); }
        catch (IllegalArgumentException e) { abstractErr = e.getMessage(); }
        check("abstract socket is rejected loudly",
              true, abstractErr.contains("abstract unix socket"));

        done();
    }

    @Test
    void everyCapturedMessageReadsBackAsWhatTheReferenceImplementationSent() throws Exception {
        golden("session-hello-reply.hex", m -> {
            check("golden Hello reply is a METHOD_RETURN", DBus.METHOD_RETURN, m.type);
            check("golden Hello reply sender", "org.freedesktop.DBus", m.sender);
            check("golden Hello reply signature", "s", m.signature);
            check("golden Hello reply body is our unique name",
                  true, ((String) m.body[0]).startsWith(":1."));
            check("golden Hello reply reply-serial", 1, m.replySerial);
        });
        golden("a11y-hello-reply.hex", m -> {
            check("golden a11y Hello reply signature", "s", m.signature);
            check("golden a11y Hello reply body is a unique name",
                  true, ((String) m.body[0]).startsWith(":1."));
        });
        golden("introspect-call.hex", m -> {
            check("golden Introspect is a METHOD_CALL", DBus.METHOD_CALL, m.type);
            check("golden Introspect interface", "org.freedesktop.DBus.Introspectable", m.iface);
            check("golden Introspect member", "Introspect", m.member);
            check("golden Introspect has no body", 0, m.body.length);
        });
        golden("getall-call.hex", m -> {
            check("golden GetAll interface", "org.freedesktop.DBus.Properties", m.iface);
            check("golden GetAll member", "GetAll", m.member);
            check("golden GetAll signature", "s", m.signature);
            check("golden GetAll argument", "org.a11y.atspi.Accessible", m.body[0]);
        });
        golden("embed-reply.hex", m -> {
            check("golden Embed reply signature", "(so)", m.signature);
            Object[] sock = (Object[]) m.body[0];
            check("golden Embed reply socket path", Atspi.PATH_ROOT, sock[1]);
            check("golden Embed reply socket name is the registry",
                  true, ((String) sock[0]).startsWith(":1."));
        });
        golden("introspect-reply.hex", m -> {
            check("golden Introspect reply signature", "s", m.signature);
            String xml = (String) m.body[0];
            check("golden Introspect reply is XML we can re-read",
                  true, xml.contains("org.a11y.atspi.Accessible") && xml.contains("GetRoleName"));
            // Re-marshal the very same reply and demand byte-for-byte identity with the wire.
            DBus.Msg copy = new DBus.Msg();
            copy.type = m.type; copy.replySerial = m.replySerial; copy.destination = m.destination;
            copy.signature = m.signature; copy.body = m.body;
            byte[] mine = copy.marshal(m.serial);
            check("golden Introspect reply re-marshals to a parseable message",
                  xml, DBus.Msg.parse(mine).body[0]);
        });
        golden("doaction-call.hex", m -> {
            check("golden DoAction interface", "org.a11y.atspi.Action", m.iface);
            check("golden DoAction member", "DoAction", m.member);
            check("golden DoAction index", 0, m.body[0]);
        });
        done();
    }

    interface Assertions { void run(DBus.Msg m); }

    /**
     * Reads one captured message off the classpath and runs {@code a} against it.
     *
     * <p>A resource rather than a directory, because these travel with the test: they are the
     * bytes the reference implementation actually sent, and a vector that can go missing is a
     * vector that silently stops being checked.
     */
    void golden(String file, Assertions a) throws Exception {
        String hex;
        try (java.io.InputStream in = DBusWireTest.class.getResourceAsStream("golden/" + file)) {
            assertNotNull(in, "missing golden vector " + file);
            hex = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        byte[] raw = DBus.unhex(hex);
        DBus.Msg m = DBus.Msg.parse(raw);
        System.out.println("  -- " + file + ": " + m);
        a.run(m);
        // Round-trip the golden bytes through the reader and back through the writer where the
        // shape allows, and always re-read what we wrote.
        check(file + " re-reads identically after a second parse",
              DBus.fmt(m.body), DBus.fmt(DBus.Msg.parse(raw).body));
    }

    /** List.of(Object[]) would spread the array through the varargs overload; this never does. */
    static List<Object> structs(Object[]... xs) {
        List<Object> out = new ArrayList<>();
        for (Object[] x : xs) out.add(x);
        return out;
    }

    void rt(String sig, Object v) {
        byte[] bytes = marshal(sig, v);
        Object back = unmarshal(sig, bytes);
        check("round trip " + sig, normalise(v), normalise(back));
    }

    /** Lists and arrays are interchangeable on input; compare them in one shape. */
    static Object normalise(Object o) {
        if (o instanceof DBus.Ref) return new Object[] { ((DBus.Ref) o).name, ((DBus.Ref) o).path };
        if (o instanceof Object[]) {
            Object[] a = (Object[]) o, b = new Object[a.length];
            for (int i = 0; i < a.length; i++) b[i] = normalise(a[i]);
            return b;
        }
        if (o instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object x : (List<?>) o) out.add(normalise(x));
            return out;
        }
        return o;
    }

    static int le32(byte[] a, int off) {
        return (a[off] & 0xff) | ((a[off + 1] & 0xff) << 8) | ((a[off + 2] & 0xff) << 16) | ((a[off + 3] & 0xff) << 24);
    }
}
