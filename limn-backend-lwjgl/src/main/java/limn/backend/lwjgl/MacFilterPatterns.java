package limn.backend.lwjgl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * macOS translation of {@link limn.backend.FileDialogs.Filter} patterns for
 * tinyfiledialogs. On macOS tinyfd builds an AppleScript {@code choose file}
 * command and turns each pattern into an {@code of type {"…"}} entry by
 * stripping its first two characters (unconditionally: every pattern it
 * receives must start with {@code "*."}). AppleScript matches those entries
 * against each file's uniform type identifier, not its name: an extension no
 * installed application registers has no named type, and the system derives
 * a per-file <em>dynamic</em> UTI ({@code dyn.…}) instead, so a filter
 * naming only the bare extension matches nothing, and the very files it means
 * to admit show up disabled.
 *
 * <p>{@link #expand} therefore emits every simple {@code *.ext} pattern twice:
 * once as-is, for files whose extension <em>is</em> registered and thus carry
 * a declared UTI (e.g. {@code public.png}, or an app bundle's own type), and
 * once naming the dynamic UTI the system derives from the extension, for files
 * nobody registered. The dynamic entry is wrapped as {@code "*." + uti} so
 * tinyfd's two-character strip delivers the bare UTI to AppleScript. Other
 * platforms match by filename and must not see the extra entries.
 *
 * <p>The dynamic UTI is the system's own derivation, stable across machines:
 * {@code "dyn.a" + base32(tagSpec)}, where the tag spec is
 * {@code "?0=6:1=" + extension} (abbreviating {@code UTTypeConformsTo=
 * public.data} and {@code public.filename-extension=ext}), the extension is
 * lowercased first (macOS treats extensions case-insensitively), and the
 * base32 variant uses alphabet {@code abcdefghkmnpqrstuvwxyz0123456789} with
 * bits packed MSB-first and no padding characters. Every value pinned in
 * MacFilterPatternsTest was measured from the system itself (System Events'
 * {@code type identifier} of a probe file, cross-checked against
 * {@code UTType(filenameExtension:)}).
 */
final class MacFilterPatterns {

    /** Base32 alphabet of Apple's {@code dyn.a} encoding (no i, j, l, o). */
    private static final String ALPHABET = "abcdefghkmnpqrstuvwxyz0123456789";

    /**
     * tinyfd assembles the whole osascript command (scaffolding, title,
     * default location and the quoted type list) into one fixed buffer of
     * this many bytes, with unchecked strcat (tinyfiledialogs.c 3.19.3,
     * {@code MAX_PATH_OR_CMD}).
     */
    private static final int TINYFD_BUFFER = 1024;

    /**
     * The fixed AppleScript around the clauses in the worst dialog variant:
     * multi-select open (measured at 356 bytes in the bundled native,
     * including the NUL) plus the 66-byte System Events tell-block wrap
     * tinyfd adds when its macOS version probe fails, rounded up.
     */
    private static final int SCAFFOLDING = 430;

    /** Slack kept unused so a miscounted byte or two can never reach the edge. */
    private static final int MARGIN = 24;

    private MacFilterPatterns() {
    }

    /**
     * The caller's patterns reduced to what tinyfd can deliver safely on
     * macOS (run before {@link #expand}). tinyfd turns every pattern into an
     * AppleScript type entry by skipping its first two bytes unconditionally
     * (trusting a leading {@code "*."}), so a pattern whose C string carries
     * fewer (under two UTF-8 bytes before its first NUL) sends it reading
     * past the terminator, pasting whatever happens to follow on the stack
     * into the osascript command. Such patterns are dropped; tinyfd's macOS
     * scheme cannot express them regardless.
     *
     * <p>A pattern that is exactly {@code "*"} admits every file, and a
     * filter is the union of its patterns, so its presence anywhere makes
     * the whole filter a no-op. The result is then empty, and the caller must
     * send tinyfd no pattern list at all (no {@code of type} clause) rather
     * than restrict the dialog to the remaining patterns. Survivors are
     * forwarded truncated at any embedded NUL, which is all the native side
     * would read of them anyway. Windows and Linux dialogs take the caller's
     * list verbatim and handle these patterns natively; nothing here applies
     * there.
     */
    static List<String> sanitize(List<String> patterns) {
        List<String> out = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            int nul = pattern.indexOf('\0');
            String delivered = nul < 0 ? pattern : pattern.substring(0, nul);
            if (delivered.equals("*")) {
                return List.of();
            }
            if (utf8Length(delivered) >= 2) {
                out.add(delivered);
            }
        }
        return out;
    }

    /**
     * The filter patterns as tinyfd should see them on macOS: each simple
     * {@code *.ext} pattern followed by its {@code "*.dyn.…"} twin; anything
     * else (a bare {@code *}, a path, wildcards or dots inside the extension)
     * passes through untouched; guessing a UTI for something that is not a
     * plain extension would be worse than not filtering.
     *
     * <p>{@code title} and {@code location} are the other caller-controlled
     * strings tinyfd packs into its fixed command buffer: the expansion sizes
     * the complete worst-case command and backs off to the caller's own list
     * when the expanded one might not fit, so the dialog then filters exactly
     * as it did before the expansion existed. (A caller whose un-expanded
     * command already exceeds the buffer was in danger before this class
     * existed; backing off never adds to it.)
     */
    static List<String> expand(List<String> patterns, String title, String location) {
        List<String> out = new ArrayList<>(patterns.size() * 2);
        for (String pattern : patterns) {
            if (utf8Length(pattern) < 2) {
                // tinyfd strips two bytes of every pattern unconditionally, so a
                // shorter one makes it read past the terminator and the emitted
                // entry unbounded (pre-existing tinyfd behavior, not created
                // here). No byte accounting can hold, so never add to it.
                return patterns;
            }
            out.add(pattern);
            String extension = simpleExtension(pattern);
            if (extension != null) {
                out.add("*." + dynamicUti(extension));
            }
        }
        return commandBytes(out, title, location) > TINYFD_BUFFER - MARGIN ? patterns : out;
    }

    /**
     * Conservative size in bytes of the osascript command tinyfd will build:
     * scaffolding + {@code with prompt "…" } (15 + title) +
     * {@code default location "…" } (20 + location) + the type list
     * ({@code of type {"…","…"} } is 13 fixed plus, per entry, the pattern
     * minus its stripped {@code "*."} plus 3 for quotes and comma).
     */
    private static int commandBytes(List<String> patterns, String title, String location) {
        int bytes = SCAFFOLDING + 15 + utf8Length(title) + 20 + utf8Length(location) + 13;
        for (String pattern : patterns) {
            bytes += utf8Length(pattern) - 2 + 3;
        }
        return bytes;
    }

    /**
     * UTF-8 size as LWJGL's marshaller emits it, NOT
     * {@code String.getBytes(UTF_8)}, which shrinks an unpaired surrogate to
     * a one-byte {@code '?'} where the marshaller writes three bytes; counting
     * the smaller number would let a title full of them eat the margin.
     */
    private static int utf8Length(String text) {
        if (text == null) {
            return 0;
        }
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /**
     * The extension of a simple {@code *.ext} glob, or null for any other
     * pattern. The character allowlist is deliberately narrow: letters, digits,
     * {@code -}, {@code _} and {@code +} were each measured to pass through the
     * system's derivation byte-for-byte; non-ASCII is excluded because filename
     * normalization (NFC vs NFD) could derive a different UTI than the encoder.
     */
    private static String simpleExtension(String pattern) {
        if (pattern.length() < 3 || pattern.charAt(0) != '*' || pattern.charAt(1) != '.') {
            return null;
        }
        for (int i = 2; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            boolean allowed = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '+';
            if (!allowed) {
                return null;
            }
        }
        return pattern.substring(2);
    }

    /** The dynamic UTI macOS derives for an unregistered filename extension. */
    static String dynamicUti(String extension) {
        byte[] tagSpec = ("?0=6:1=" + extension.toLowerCase(Locale.ROOT))
                .getBytes(StandardCharsets.UTF_8);
        StringBuilder uti = new StringBuilder(5 + (tagSpec.length * 8 + 4) / 5);
        uti.append("dyn.a");
        int acc = 0;
        int bits = 0;
        for (byte b : tagSpec) {
            acc = acc << 8 | b & 0xFF;
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                uti.append(ALPHABET.charAt(acc >> bits & 31));
            }
        }
        if (bits > 0) {
            uti.append(ALPHABET.charAt(acc << 5 - bits & 31));
        }
        return uti.toString();
    }
}
