package limn.components;

import limn.graphics.Color;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * A palette as text, and back, so an application can ship a theme its designer built as
 * a file beside the jar instead of as a recompile.
 *
 * <pre>{@code
 * # Limn theme
 * name = Ocean
 * dark = true
 * background = #0B1A24
 * surface = #11242F
 * primary = #4FD1C5
 * }</pre>
 *
 * <p>Here rather than in the editor module, so that <b>an application can load a palette
 * its designer built without the screen that built it anywhere in the build</b>. That
 * split is the whole point of the format: what crosses between authoring and wearing a
 * theme is a value, and this is how it is written down.
 *
 * <p>{@link #parse} and {@link #write} are the pure pair: text in, text out, no disk, no
 * thread rules. {@link #load(Path)} and {@link #load(InputStream)} are the two lines above
 * them that an application actually calls, and they are here because the resource case is
 * not one line: a theme shipped inside a jar otherwise costs a try-with-resources, a null
 * check, {@code readAllBytes} and a charset, every time, in every application. There is no
 * {@code save} counterpart on purpose: writing is {@code Files.writeString(path,
 * write(theme))} and nothing is hidden in it, and nobody writes back into a jar.
 *
 * <p><b>No asynchronous form, and none is owed.</b> A palette is a configuration file of
 * about a kilobyte, read once, normally before there is a window to keep responsive. An
 * application loading one while a window is up (from a document, or a file the user just
 * chose) should still go through {@code Ui.work}, which is what {@code ThemeEditorFiles}
 * does; nothing here stops it, since {@link #load} is an ordinary blocking call.
 *
 * <p><b>What may be left out.</b> {@code name} and {@code dark} are required: the mode
 * decides which built-in the missing tones are taken from, so a file without it would
 * mean two different palettes. Every colour is optional and falls back to the built-in
 * {@link Theme#light()} or {@link Theme#dark()}, which is what makes a four-line palette
 * a usable one, and so is {@code cornerScale}, which falls back to the shipped ramp.
 *
 * <p><b>What may not.</b> A key that is not {@code name}, {@code dark}, {@code cornerScale}
 * or a {@link Theme.Token#key()} is an error rather than a shrug, and so is a repeated key: a
 * palette that silently ignored {@code primaryHovor} would be debugged by eye, in a
 * running application, against a tone that never moved.
 */
public final class ThemeFormat {

    /**
     * The conventional file extension, without the dot, for a file dialog's filter and
     * for a resource name. Nothing enforces it; the parser reads whatever it is given.
     */
    public static final String EXTENSION = "limntheme";

    private static final String KEY_NAME = "name";
    private static final String KEY_DARK = "dark";
    private static final String KEY_CORNER_SCALE = "cornerScale";
    private static final String KEY_FONT_FAMILY = "fontFamily";

    /**
     * A byte-order mark, which is not part of any key. Text editors on Windows still add
     * one when they re-save a UTF-8 file, and a palette is a file people hand-edit, so
     * without this the very first line stops looking like a comment and the error names
     * something the reader can see is fine.
     */
    private static final char BOM = '﻿';

    private ThemeFormat() {
    }

    /**
     * Reads a palette from a file.
     *
     * <pre>{@code
     * Theme.setCurrent(ThemeFormat.load(Path.of("themes/ocean." + ThemeFormat.EXTENSION)));
     * }</pre>
     *
     * <p>Blocking, and deliberately so; see the class documentation for when that is fine
     * and when to wrap it in {@code Ui.work}.
     *
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if what it holds is not a palette, naming the line
     */
    public static Theme load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Reads a palette from a stream, the form a theme shipped inside a jar arrives in.
     *
     * <pre>{@code
     * try (InputStream in = App.class.getResourceAsStream("/themes/ocean.limntheme")) {
     *     Theme.setCurrent(ThemeFormat.load(in));
     * }
     * }</pre>
     *
     * <p><b>The stream is read to the end and left open</b>, because it was opened by the
     * caller: a method that closed a stream it did not open would break the
     * try-with-resources above by making the close double.
     *
     * @throws IOException              if the stream cannot be read
     * @throws IllegalArgumentException if what it holds is not a palette, naming the line
     */
    public static Theme load(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    /**
     * The palette as text: a comment header, {@code name}, {@code dark}, then every tone
     * in {@link Theme.Token} order, one per line.
     *
     * <p>Deterministic and newline-terminated with {@code \n} on every platform: two
     * writes of equal palettes produce equal strings, so a saved file can be diffed and a
     * round trip can be asserted.
     */
    public static String write(Theme theme) {
        Objects.requireNonNull(theme, "theme");
        StringBuilder out = new StringBuilder(512);
        out.append("# Limn theme\n");
        out.append(KEY_NAME).append(" = ").append(theme.name).append('\n');
        out.append(KEY_DARK).append(" = ").append(theme.dark).append('\n');
        out.append(KEY_CORNER_SCALE).append(" = ").append(theme.cornerScale).append('\n');
        out.append(KEY_FONT_FAMILY).append(" = ").append(theme.fontFamily).append('\n');
        for (Theme.Token token : Theme.Token.values()) {
            out.append(token.key()).append(" = ").append(token.read(theme).toHex()).append('\n');
        }
        return out.toString();
    }

    /**
     * Reads a palette back. Blank lines are ignored, and so is any line whose first
     * non-blank character is {@code #}; every other line is {@code key = value}.
     *
     * <p>A comment is a whole line and never the tail of one: a colour value begins with
     * {@code #}, and there is no spelling of "comment" that could also let that through.
     *
     * @throws IllegalArgumentException on any malformed, unknown, repeated or missing
     *                                  key, naming the line it was on; the message is
     *                                  meant to be shown to whoever wrote the file
     */
    public static Theme parse(String text) {
        Objects.requireNonNull(text, "text");
        if (!text.isEmpty() && text.charAt(0) == BOM) {
            text = text.substring(1); // only at the head: elsewhere U+FEFF is not a mark
        }
        Map<Theme.Token, Color> colors = new EnumMap<>(Theme.Token.class);
        String name = null;
        Boolean dark = null;
        Float cornerScale = null;
        String fontFamily = null;

        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int line = i + 1;
            String content = lines[i].trim();
            // A comment is a whole line, never a tail: a colour value STARTS with '#', so a
            // parser that stripped from the first one would read "#0B1A24" as an empty value,
            // and the palette would come back as the built-in it seeded from, silently.
            if (content.isEmpty() || content.startsWith("#")) {
                continue;
            }
            int equals = content.indexOf('=');
            if (equals < 0) {
                throw malformed(line, "expected 'key = value'", content);
            }
            String key = content.substring(0, equals).trim();
            String value = content.substring(equals + 1).trim();
            switch (key) {
                case KEY_NAME -> {
                    if (name != null) {
                        throw malformed(line, "'name' was already given", content);
                    }
                    if (value.isEmpty()) {
                        throw malformed(line, "'name' cannot be empty", content);
                    }
                    name = value;
                }
                case KEY_DARK -> {
                    if (dark != null) {
                        throw malformed(line, "'dark' was already given", content);
                    }
                    if (!value.equals("true") && !value.equals("false")) {
                        throw malformed(line, "'dark' must be true or false", content);
                    }
                    dark = value.equals("true");
                }
                case KEY_CORNER_SCALE -> {
                    if (cornerScale != null) {
                        throw malformed(line, "'cornerScale' was already given", content);
                    }
                    try {
                        cornerScale = Float.parseFloat(value);
                    } catch (NumberFormatException notANumber) {
                        throw malformed(line, "'cornerScale' must be a number", content);
                    }
                    if (!Float.isFinite(cornerScale) || cornerScale < 0
                            || cornerScale > Theme.MAX_CORNER_SCALE) {
                        throw malformed(line, "'cornerScale' must be between 0 and "
                                + Theme.MAX_CORNER_SCALE, content);
                    }
                }
                case KEY_FONT_FAMILY -> {
                    if (fontFamily != null) {
                        throw malformed(line, "'fontFamily' was already given", content);
                    }
                    // Not checked against the installed faces, and not allowed to be empty
                    // either: a blank value is a file that meant to say something and did not,
                    // where an absent key is a file with no preference. Theme.Builder maps the
                    // name to the embedded face when nothing can resolve it.
                    if (value.isEmpty()) {
                        throw malformed(line, "'fontFamily' cannot be empty; leave the key out "
                                + "to express no preference", content);
                    }
                    fontFamily = value;
                }
                default -> {
                    Theme.Token token = Theme.Token.byKey(key);
                    if (token == null) {
                        throw malformed(line, "unknown key '" + key + "'", content);
                    }
                    if (colors.containsKey(token)) {
                        throw malformed(line, "'" + key + "' was already given", content);
                    }
                    Color color = Color.fromHex(value);
                    if (color == null) {
                        throw malformed(line, "'" + value + "' is not a #RRGGBB colour", content);
                    }
                    colors.put(token, color);
                }
            }
        }
        if (name == null) {
            throw new IllegalArgumentException("theme: no 'name'; a palette must say what it is called");
        }
        if (dark == null) {
            throw new IllegalArgumentException(
                    "theme '" + name + "': no 'dark'. It decides which palette the tones "
                            + "left out are taken from, so it cannot be guessed");
        }
        Theme.Builder builder = Theme.builder(name, dark);
        if (cornerScale != null) {
            builder.cornerScale(cornerScale);
        }
        if (fontFamily != null) {
            builder.fontFamily(fontFamily);
        }
        for (Map.Entry<Theme.Token, Color> entry : colors.entrySet()) {
            builder.set(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private static IllegalArgumentException malformed(int line, String why, String content) {
        return new IllegalArgumentException(
                "theme, line " + line + ": " + why + " (\"" + content + "\")");
    }
}
