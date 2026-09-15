package limn.backend.lwjgl.a11y.linux;

import java.util.function.Consumer;

/**
 * Where the Linux bridge says what it did, so a reader that heard nothing can be asked about rather
 * than guessed at (LINUX-NEW-6).
 *
 * <p>The 2026-09-13 tree-reader run could not tell, from the application's side, whether its events
 * had been sent at all: the inbound calls were traced, the outbound signals were not, a refusal was
 * only counted, and an event with no mapping vanished in silence. Every one of those now says a
 * line here — each signal handed to the connection with its path, member, detail, integers and
 * value, whether the connection accepted it and, when it did not, how many it has refused so far;
 * each event that mapped to no signal, or that the bridge held back as already said; each event that
 * arrived while the application had not joined; and every inbound call.
 *
 * <p><b>Nothing listens by default, and then nothing is built.</b> The consumer is read once per
 * site and every line is formatted only behind a non-null check, as {@code UiaWindow.say} does on
 * Windows. {@code -Dlimn.a11y.linux.trace=true} installs one that prints to standard output with
 * an {@code [atspi]} prefix; a test installs its own. The wire itself — every message's bytes as
 * the connection writes and reads them, and the SASL lines — is the older, separate
 * {@code -Dprobe.trace=true} ({@code DBus.TRACE}, standard error), which this does not replace.
 *
 * <p>A text-changed line carries the text that changed, which a password field publishes masked
 * ({@code TextFacet}); strings are cut to {@value #STRING_LIMIT} characters.
 */
final class AtspiTrace {

    private AtspiTrace() {
    }

    /** The longest string value a line repeats. */
    static final int STRING_LIMIT = 40;

    /** Who hears the trace; {@code null}, the default, is nobody and costs one read per site. */
    static volatile Consumer<String> trace = Boolean.getBoolean("limn.a11y.linux.trace")
            ? line -> {
                System.out.println("[atspi] " + line);
                System.out.flush();
            }
            : null;

    /** One signal as a line: where from, what, and how its value reads. */
    static String describe(DBus.Msg signal) {
        StringBuilder out = new StringBuilder();
        out.append(signal.path).append(' ').append(signal.iface).append('.').append(signal.member);
        Object[] body = signal.body == null ? new Object[0] : signal.body;
        if (body.length == 5 && body[0] instanceof String detail) {
            out.append(" detail=").append(detail).append(" detail1=").append(body[1])
                    .append(" detail2=").append(body[2]).append(" value=").append(valueOf(body[3]));
        } else {
            out.append(" <").append(signal.signature).append('>');
        }
        return out.toString();
    }

    private static String valueOf(Object value) {
        if (!(value instanceof DBus.Variant variant)) {
            return String.valueOf(value);
        }
        Object inner = variant.value;
        String shown;
        if (inner instanceof String s) {
            shown = '"' + (s.length() > STRING_LIMIT ? s.substring(0, STRING_LIMIT) + "…" : s) + '"';
        } else if (inner instanceof Object[] struct) {
            shown = java.util.Arrays.toString(struct);
        } else {
            shown = String.valueOf(inner);
        }
        return variant.sig + " " + shown;
    }
}
