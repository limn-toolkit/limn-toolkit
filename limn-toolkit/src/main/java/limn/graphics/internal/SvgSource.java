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

    private SvgSource(SvgXml.Element root) {
        this.root = root;
        this.text = SvgXml.write(root);
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
        drawnElements(root);
        return new SvgSource(root);
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
}
