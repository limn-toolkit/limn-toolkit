package limn.graphics.internal;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import limn.graphics.Image;
import limn.graphics.ImageFormat;
import limn.graphics.Images;
import limn.io.MalformedContentException;
import org.jspecify.annotations.Nullable;

/**
 * An SVG as the toolkit hands it to a renderer: read in Java ({@link SvgXml}) within ceilings, and
 * made to refer to nothing outside itself.
 *
 * <ul>
 *   <li><b>Taken out:</b> scripts, {@code foreignObject}, media elements, every event attribute,
 *       and every reference that is not to an element of the same file. A renderer reads a path
 *       it is given; it is given none.</li>
 *   <li><b>Pictures</b> are read by the toolkit: one embedded as {@code data:}, and, for a file
 *       read from disk, one beside it, its address relative to the file's own folder and refused
 *       if it leads out. Each is decoded as any picture the toolkit reads, within the same
 *       ceilings, and handed to the renderer as a PNG the toolkit wrote, so the renderer's own
 *       image decoder reads nothing but that. A network address is never read.</li>
 *   <li><b>CSS custom properties</b> are resolved here, as a browser resolves them: a
 *       {@code var(--name, fallback)} in an attribute, a {@code style} or a style sheet becomes
 *       the value declared for that name, by an ancestor's {@code style} or by a sheet's rule for
 *       {@code :root}, {@code svg} or {@code *}, or else its fallback. Illustrations exported from
 *       design systems paint this way, and a renderer that does not read {@code var()} drew them
 *       black.</li>
 *   <li><b>{@code use}</b> is followed to count what drawing the file costs: the elements it
 *       draws, through every reference, are held under {@link #MAX_DRAWN_ELEMENTS}, and a
 *       reference that leads back to itself is refused.</li>
 * </ul>
 *
 * <p>Pure CPU, on any thread; the pictures are read on the calling thread.
 */
public final class SvgSource {

    /** The most elements a file may draw, every {@code use} followed: a million. */
    public static final int MAX_DRAWN_ELEMENTS = 1_000_000;
    /** The largest picture file read beside an SVG: 64 MiB. */
    public static final int MAX_PICTURE_BYTES = 64 << 20;

    private static final Set<String> REMOVED = Set.of("script", "foreignObject", "audio", "video",
            "iframe", "canvas", "handler", "listener");

    private final SvgXml.Element root;
    private final byte[] text;
    private final @Nullable SvgAnimation animation;

    private SvgSource(SvgXml.Element root, @Nullable SvgAnimation animation) {
        this.root = root;
        this.animation = animation;
        this.text = SvgXml.write(root);
    }

    /** @return the animation the toolkit plays, taken out of the text; null when nothing moves */
    public @Nullable SvgAnimation animation() {
        return animation;
    }

    /**
     * Reads an SVG.
     *
     * @param bytes  the file
     * @param folder the folder of the file it was read from, whose pictures it may show, or null
     *               for one from a resource or from bytes, which shows only its embedded pictures
     * @return the source, ready for a renderer
     * @throws MalformedContentException when the file is damaged or passes a ceiling
     */
    public static SvgSource read(byte[] bytes, @Nullable Path folder) {
        SvgXml.Element root = SvgXml.parse(bytes);
        if (!root.localName().equals("svg")) {
            throw SvgXml.refused("the root element is <" + root.name() + ">, not <svg>");
        }
        new Sanitizer(folder).clean(root);
        CustomProperties.resolve(root);
        drawnElements(root);
        SvgAnimation animation = SvgAnimation.extract(root);
        return new SvgSource(root, animation);
    }

    /**
     * Whether a file is an SVG: text whose first element, after the declaration, comments and a
     * DOCTYPE, is {@code svg}. Read from its first four kilobytes, without parsing.
     *
     * @param bytes the file
     * @return whether to read it as an SVG
     */
    public static boolean looksLikeSvg(byte[] bytes) {
        int start = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        String head = new String(bytes, start, Math.min(bytes.length - start, 4096), StandardCharsets.ISO_8859_1);
        int at = 0;
        while (true) {
            while (at < head.length() && Character.isWhitespace(head.charAt(at))) {
                at++;
            }
            if (head.startsWith("<?", at) || head.startsWith("<!--", at)) {
                String end = head.startsWith("<?", at) ? "?>" : "-->";
                int close = head.indexOf(end, at);
                if (close < 0) {
                    return false;
                }
                at = close + end.length();
            } else if (head.startsWith("<!DOCTYPE", at)) {
                return head.startsWith("svg", at + 10) || head.indexOf("<svg", at) >= 0;
            } else {
                return head.startsWith("<svg", at) && at + 4 < head.length()
                        && (Character.isWhitespace(head.charAt(at + 4)) || head.charAt(at + 4) == '>'
                        || head.charAt(at + 4) == '/');
            }
        }
    }

    /** @return the document the renderer is given, as text */
    public byte[] text() {
        return text;
    }

    /** @return the document, as read and cleaned */
    public SvgXml.Element root() {
        return root;
    }

    // ----------------------------------------------------------------------------- use

    /** Counts what the document draws, every {@code use} followed, and refuses a cycle or a count past the ceiling. */
    private static void drawnElements(SvgXml.Element root) {
        Map<String, SvgXml.Element> byId = new HashMap<>();
        index(root, byId);
        Map<SvgXml.Element, Long> cost = new IdentityHashMap<>();
        long drawn = cost(root, byId, cost, new IdentityHashMap<>());
        if (drawn > MAX_DRAWN_ELEMENTS) {
            throw SvgXml.refused("the SVG draws more than " + MAX_DRAWN_ELEMENTS + " elements, its uses followed");
        }
    }

    private static void index(SvgXml.Element element, Map<String, SvgXml.Element> byId) {
        String id = element.attribute("id");
        if (id != null) {
            byId.putIfAbsent(id, element);
        }
        for (SvgXml.Element child : element.elements()) {
            index(child, byId);
        }
    }

    private static long cost(SvgXml.Element element, Map<String, SvgXml.Element> byId,
                             Map<SvgXml.Element, Long> known, Map<SvgXml.Element, Boolean> visiting) {
        Long done = known.get(element);
        if (done != null) {
            return done;
        }
        if (visiting.put(element, Boolean.TRUE) != null) {
            throw SvgXml.refused("a reference leads back to the element it is in");
        }
        long total = 1;
        for (SvgXml.Element child : element.elements()) {
            total = Math.min(Long.MAX_VALUE / 2, total + cost(child, byId, known, visiting));
        }
        if (element.localName().equals("use")) {
            String href = href(element);
            if (href != null && href.startsWith("#")) {
                SvgXml.Element target = byId.get(href.substring(1));
                if (target != null) {
                    total = Math.min(Long.MAX_VALUE / 2, total + cost(target, byId, known, visiting));
                }
            }
        }
        visiting.remove(element);
        known.put(element, total);
        if (total > MAX_DRAWN_ELEMENTS) {
            throw SvgXml.refused("the SVG draws more than " + MAX_DRAWN_ELEMENTS + " elements, its uses followed");
        }
        return total;
    }

    static @Nullable String href(SvgXml.Element element) {
        String href = element.attribute("href");
        return href != null ? href.trim() : element.attribute("xlink:href") == null ? null
                : element.attribute("xlink:href").trim();
    }

    // ----------------------------------------------------------------------------- cleaning

    private static final class Sanitizer {

        private final @Nullable Path folder;

        Sanitizer(@Nullable Path folder) {
            this.folder = folder;
        }

        void clean(SvgXml.Element element) {
            for (int i = element.attributeCount() - 1; i >= 0; i--) {
                String name = element.attributeName(i);
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("on")) {
                    element.removeAttribute(i);
                } else if (lower.equals("href") || lower.equals("xlink:href")) {
                    String value = element.attributeValue(i).trim();
                    String kept = keep(element, value);
                    if (kept == null) {
                        element.removeAttribute(i);
                    } else if (!kept.equals(value)) {
                        element.removeAttribute(i);
                        element.setAttribute(name, kept);
                    }
                }
            }
            List<Object> children = element.children();
            for (int i = children.size() - 1; i >= 0; i--) {
                if (children.get(i) instanceof SvgXml.Element child) {
                    if (REMOVED.contains(child.localName())) {
                        children.remove(i);
                    } else {
                        clean(child);
                    }
                }
            }
        }

        /** @return what an address becomes: itself for a reference inside the file, a PNG for a picture, or null to drop it */
        private @Nullable String keep(SvgXml.Element element, String href) {
            if (href.startsWith("#")) {
                return href;
            }
            String local = element.localName();
            if (!local.equals("image") && !local.equals("feImage")) {
                return null; // another file's element: never read
            }
            byte[] picture = href.regionMatches(true, 0, "data:", 0, 5) ? dataUri(href) : beside(href);
            if (picture == null) {
                return null;
            }
            Image image;
            try {
                image = Images.decode(picture);
            } catch (RuntimeException unreadable) {
                return null; // a picture the toolkit does not read is not drawn, as a browser would not
            }
            byte[] png = Images.encode(image, ImageFormat.PNG);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        }

        private static byte @Nullable [] dataUri(String href) {
            int comma = href.indexOf(',');
            if (comma < 0) {
                return null;
            }
            String header = href.substring(5, comma).toLowerCase(Locale.ROOT);
            String payload = href.substring(comma + 1);
            if (payload.length() > MAX_PICTURE_BYTES / 3 * 4 + 4) {
                throw SvgXml.refused("an embedded picture is above the " + MAX_PICTURE_BYTES + " bytes read");
            }
            try {
                if (header.endsWith(";base64")) {
                    return Base64.getMimeDecoder().decode(payload);
                }
                return URLDecoder.decode(payload.replace("+", "%2B"), StandardCharsets.ISO_8859_1)
                        .getBytes(StandardCharsets.ISO_8859_1);
            } catch (IllegalArgumentException damaged) {
                return null;
            }
        }

        /** A picture beside the file, inside its folder; null when there is no folder or it is not there. */
        private byte @Nullable [] beside(String href) {
            if (folder == null || href.isEmpty() || href.indexOf(':') >= 0 || href.startsWith("/")
                    || href.startsWith("\\")) {
                return null; // no folder, a scheme or a drive, or an absolute path: nothing beside
            }
            String relative;
            try {
                relative = URLDecoder.decode(href.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException damaged) {
                return null;
            }
            try {
                Path base = folder.toRealPath();
                Path wanted = base.resolve(relative).normalize();
                if (!wanted.startsWith(base) || !Files.isRegularFile(wanted, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(wanted)) {
                    return null;
                }
                Path real = wanted.toRealPath();
                if (!real.startsWith(base)) {
                    return null; // a link that leads out of the folder
                }
                if (Files.size(real) > MAX_PICTURE_BYTES) {
                    throw SvgXml.refused("the picture " + relative + " is above the " + MAX_PICTURE_BYTES + " bytes read");
                }
                return Files.readAllBytes(real);
            } catch (IOException | SecurityException | java.nio.file.InvalidPathException unreadable) {
                return null;
            }
        }
    }

    // ----------------------------------------------------------------------------- var()

    /** Resolves CSS custom properties, which the renderer does not read. */
    static final class CustomProperties {

        /** The deepest a property may refer to another before it reads as unset. */
        private static final int MAX_DEPTH = 16;

        private CustomProperties() {
        }

        static void resolve(SvgXml.Element root) {
            Map<String, String> global = new HashMap<>();
            collectGlobal(root, global);
            String rootStyle = root.attribute("style");
            if (rootStyle != null) {
                declarations(rootStyle, global); // the root's own, which every element inherits
            }
            walk(root, global, global);
        }

        /** The declarations of a sheet's rules for the whole document: {@code :root}, {@code svg}, {@code *}, {@code html}. */
        private static void collectGlobal(SvgXml.Element element, Map<String, String> global) {
            if (element.localName().equals("style")) {
                String sheet = element.text();
                int at = 0;
                while (true) {
                    int open = sheet.indexOf('{', at);
                    int close = open < 0 ? -1 : sheet.indexOf('}', open);
                    if (close < 0) {
                        break;
                    }
                    String selectors = sheet.substring(at, open);
                    for (String selector : selectors.split(",")) {
                        String one = selector.replaceAll("/\\*.*?\\*/", "").trim();
                        if (one.equals(":root") || one.equals("svg") || one.equals("*") || one.equals("html")) {
                            declarations(sheet.substring(open + 1, close), global);
                            break;
                        }
                    }
                    at = close + 1;
                }
            }
            for (SvgXml.Element child : element.elements()) {
                collectGlobal(child, global);
            }
        }

        /** Adds the {@code --name: value} declarations of a declaration block to {@code into}. */
        private static void declarations(String block, Map<String, String> into) {
            for (String declaration : splitDeclarations(block)) {
                int colon = declaration.indexOf(':');
                if (colon > 0) {
                    String name = declaration.substring(0, colon).trim();
                    if (name.startsWith("--")) {
                        into.put(name, declaration.substring(colon + 1).trim());
                    }
                }
            }
        }

        private static List<String> splitDeclarations(String block) {
            List<String> out = new java.util.ArrayList<>();
            int depth = 0;
            int start = 0;
            for (int i = 0; i < block.length(); i++) {
                char c = block.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth = Math.max(0, depth - 1);
                } else if (c == ';' && depth == 0) {
                    out.add(block.substring(start, i));
                    start = i + 1;
                }
            }
            out.add(block.substring(start));
            return out;
        }

        private static void walk(SvgXml.Element element, Map<String, String> global, Map<String, String> inherited) {
            Map<String, String> own = inherited;
            String style = element.attribute("style");
            if (style != null && style.contains("--")) {
                own = new HashMap<>(inherited);
                declarations(style, own);
            }
            for (int i = element.attributeCount() - 1; i >= 0; i--) {
                String value = element.attributeValue(i);
                if (!value.contains("var(")) {
                    continue;
                }
                String name = element.attributeName(i);
                String resolved = name.equals("style") ? resolveDeclarations(value, own) : substitute(value, own, 0);
                element.removeAttribute(i);
                if (resolved != null && !resolved.isBlank()) {
                    element.setAttribute(name, resolved.trim());
                }
                // Unresolved, the attribute goes: the property is unset, and inherits or takes its default.
            }
            if (element.localName().equals("style")) {
                String sheet = element.text();
                if (sheet.contains("var(")) {
                    element.children().clear();
                    element.children().add(resolveSheet(sheet, global));
                }
            }
            for (SvgXml.Element child : element.elements()) {
                walk(child, global, own);
            }
        }

        /** A sheet with every declaration's {@code var()} resolved against the document's properties. */
        private static String resolveSheet(String sheet, Map<String, String> global) {
            StringBuilder out = new StringBuilder(sheet.length());
            int at = 0;
            while (true) {
                int open = sheet.indexOf('{', at);
                int close = open < 0 ? -1 : sheet.indexOf('}', open);
                if (close < 0) {
                    out.append(sheet, at, sheet.length());
                    return out.toString();
                }
                out.append(sheet, at, open + 1);
                String block = resolveDeclarations(sheet.substring(open + 1, close), global);
                out.append(block == null ? "" : block).append('}');
                at = close + 1;
            }
        }

        /** A declaration block with each {@code var()} resolved, and a declaration that resolves to nothing dropped. */
        private static @Nullable String resolveDeclarations(String block, Map<String, String> properties) {
            StringBuilder out = new StringBuilder();
            for (String declaration : splitDeclarations(block)) {
                String trimmed = declaration.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue; // a property's own declaration means nothing to the renderer
                }
                int colon = trimmed.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String value = trimmed.substring(colon + 1);
                if (value.contains("var(")) {
                    value = substitute(value, properties, 0);
                    if (value == null || value.isBlank()) {
                        continue;
                    }
                }
                if (out.length() > 0) {
                    out.append(';');
                }
                out.append(trimmed, 0, colon).append(':').append(value.trim());
            }
            return out.toString();
        }

        /** The value with each {@code var(--name, fallback)} replaced; null when one has neither a value nor a fallback. */
        static @Nullable String substitute(String value, Map<String, String> properties, int depth) {
            if (depth > MAX_DEPTH) {
                return null;
            }
            StringBuilder out = new StringBuilder();
            int at = 0;
            while (true) {
                int start = value.indexOf("var(", at);
                if (start < 0) {
                    out.append(value, at, value.length());
                    return out.toString();
                }
                out.append(value, at, start);
                int level = 0;
                int end = -1;
                int comma = -1;
                for (int i = start + 3; i < value.length(); i++) {
                    char c = value.charAt(i);
                    if (c == '(') {
                        level++;
                    } else if (c == ')') {
                        if (--level == 0) {
                            end = i;
                            break;
                        }
                    } else if (c == ',' && level == 1 && comma < 0) {
                        comma = i;
                    }
                }
                if (end < 0) {
                    return null;
                }
                String name = value.substring(start + 4, comma < 0 ? end : comma).trim();
                String declared = properties.get(name);
                String replacement = declared != null ? substitute(declared, properties, depth + 1) : null;
                if (replacement == null && comma >= 0) {
                    replacement = substitute(value.substring(comma + 1, end).trim(), properties, depth + 1);
                }
                if (replacement == null) {
                    return null;
                }
                out.append(replacement);
                at = end + 1;
            }
        }
    }
}
