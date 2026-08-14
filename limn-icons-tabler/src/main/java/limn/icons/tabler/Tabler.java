package limn.icons.tabler;

import limn.graphics.Icon;
import limn.graphics.SvgIcon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Tabler icon pack: name in, {@link Icon} out, shared and lazily rasterized.
 *
 * <pre>{@code
 * button.setIcon(TablerSystem.TRASH.icon());          // checked by the compiler
 * button.setIcon(Tabler.outline("trash"));            // when the name is data
 * }</pre>
 *
 * <p>The drawings live in one resource rather than six thousand: as separate classpath
 * entries the set costs about 2.9MB of jar, because a few hundred bytes of XML compresses
 * badly on its own and every entry carries a header; concatenated, the same drawings
 * compress to about a tenth of that. The price is that reaching any icon reads the whole
 * blob, so it is held through a {@link SoftReference}: a run that draws six icons keeps it
 * only until memory is wanted elsewhere, and a run that draws six thousand keeps it while
 * it is being used.
 *
 * <p><b>The icons are cached softly for the same reason, and the reason is the bitmaps.</b>
 * An {@link SvgIcon} is a few hundred bytes of markup and up to sixteen rasterized pictures
 * (each an on-heap RGBA array), so the pixels, not the text, are what a catalogue-sized run
 * accumulates. An icon still on screen is held strongly by the widget drawing it and cannot be
 * collected; one that has scrolled away is reachable only from here, and lets go of its bitmaps
 * with it. Caching them strongly instead would keep every icon ever drawn, and every size it was
 * ever drawn at, for the life of the process: the case a scrolling picker reaches in a few
 * seconds.
 *
 * <p>Sharing survives that: while anyone holds an icon, everyone asking for the same name gets
 * the same instance, which is what makes a hundred buttons share one bitmap and one texture. An
 * icon rebuilt after its entry was cleared is a new instance and re-rasterizes, which is the cost
 * of the memory being reclaimable at all.
 *
 * <p>Every method here is safe to call from any thread, and the first one that reaches a
 * drawing pays for the pack: the index and that blob are read off the classpath and inflated
 * on the calling thread, which on the UI thread is a frame. So does the first call after the
 * soft reference has been cleared, which can happen at any point in a long run.
 * {@link #warmUpAsync()} does both reads on the worker pool instead. Nothing rasterizes until
 * an icon is asked for a bitmap, which is the UI thread's business and the toolkit's rule, not
 * this pack's.
 */
public final class Tabler {

    private static final String BASE = "/limn/icons/tabler/";

    private record Slice(int offset, int length) {
    }

    /** name -> where it is in the blob, in upstream order. Read once, kept: it is the catalogue. */
    private static volatile Map<String, Slice> index;
    private static volatile SoftReference<byte[]> blob = new SoftReference<>(null);

    /**
     * key -> the shared icon, softly. Cleared entries stay in the map as empty references; there
     * are at most as many as the pack has drawings, so the residue is bounded and tiny next to
     * the pixels it let go of.
     */
    private static final Map<String, SoftReference<SvgIcon>> ICONS = new ConcurrentHashMap<>();

    /** Built once from the immutable index, so {@link #names()} is the field read it promises. */
    private static volatile List<String> outlineNames;

    private static final Object LOCK = new Object();

    private Tabler() {
    }

    /**
     * The outline drawing for {@code name}, shared between callers.
     *
     * @throws NoSuchElementException if no icon has that name; the message names the
     *                                closest thing to a suggestion this pack can offer,
     *                                which is how many names it does have
     */
    public static Icon outline(String name) {
        return icon("outline/" + Objects.requireNonNull(name, "name"), name);
    }

    /**
     * The filled drawing for {@code name}, shared between callers.
     *
     * @throws NoSuchElementException if that name has no filled variant; ask
     *                                {@link #hasFilled} first
     */
    public static Icon filled(String name) {
        return icon("filled/" + Objects.requireNonNull(name, "name"), name);
    }

    /** @return whether {@code name} has a filled variant as well as an outline one */
    public static boolean hasFilled(String name) {
        return index().containsKey("filled/" + Objects.requireNonNull(name, "name"));
    }

    /**
     * @return whether the pack has an outline drawing under this name: the non-throwing
     *         form of {@link #outline}, for a name that came from a file or a user
     */
    public static boolean has(String name) {
        return index().containsKey("outline/" + Objects.requireNonNull(name, "name"));
    }

    /**
     * Every outline name, in upstream order. The list is immutable and computed once: the same
     * instance every call, cheap enough to ask for inside a filter's change handler. It is what a
     * picker enumerates, and it is deliberately names rather than icons, so listing the catalogue
     * does not rasterize it.
     */
    public static List<String> names() {
        List<String> current = outlineNames;
        if (current != null) {
            return current;
        }
        // Raced deliberately, like icon() below: two threads may both build the list, and both
        // build the same thing from an index that cannot change. A lock here would serialize a
        // walk of several thousand keys to avoid an allocation nobody notices.
        List<String> all = new ArrayList<>();
        for (String key : index().keySet()) {
            if (key.startsWith("outline/")) {
                all.add(key.substring("outline/".length()));
            }
        }
        List<String> built = Collections.unmodifiableList(all);
        outlineNames = built;
        return built;
    }

    /**
     * Reads the index and the drawing blob on the {@code Ui} worker pool, so the first
     * {@link #outline} on the UI thread finds them already in memory. Started once during
     * startup, before the first screen that draws an icon:
     *
     * <pre>{@code
     * Tabler.warmUpAsync().start();
     * }</pre>
     *
     * <p>Returned <b>unstarted</b>, like every {@code Async} form in this toolkit: calling this
     * and dropping the result warms nothing at all.
     *
     * <p>It buys nothing but the thread the reads happen on, and skipping it changes no answer
     * this class gives. Progress runs 0 to 1 across the two reads, and cancelling between them
     * leaves whatever was read already read.
     *
     * <p><b>It is not a promise that the blob stays resident.</b> The drawings are held through a
     * {@link SoftReference} and a memory-hungry run may reclaim them, after which the next icon
     * reads them again on whatever thread asked. Start this again to move that read off the UI
     * thread too. The index is read once and kept, so it is warmed for good.
     *
     * <p>It delivers nothing, where {@code Sounds.warmUpAsync} and {@code Videos.warmUpAsync}
     * deliver whether the thing will work: those two front an SPI that may have nothing installed
     * behind it, and this pack ships its own resources; there is no question to answer. A pack
     * whose resources are missing is a broken build and fails to {@code onFailure} rather than
     * reporting false.
     *
     * @return the unstarted work; nothing is read until {@code start()}
     * @throws IllegalStateException if no backend is running (there is no worker pool to use)
     */
    public static limn.concurrent.Work<Void> warmUpAsync() {
        return limn.concurrent.Ui.work(progress -> {
            index();
            progress.report(0.5);
            if (progress.isCancelled()) {
                return null;
            }
            payload();
            progress.report(1);
            return null;
        });
    }

    /**
     * The raw SVG bytes for an entry, freshly copied so a caller cannot scribble on the
     * pack's own blob; NanoSVG parses destructively, and this array is handed to it.
     */
    private static byte[] svg(String key, String name) {
        Slice slice = index().get(key);
        if (slice == null) {
            throw new NoSuchElementException("no Tabler icon '" + name + "' (" + key
                    + "); the pack has " + index().size() + " entries");
        }
        byte[] all = payload();
        byte[] out = new byte[slice.length()];
        System.arraycopy(all, slice.offset(), out, 0, slice.length());
        return out;
    }

    private static Icon icon(String key, String name) {
        SoftReference<SvgIcon> cached = ICONS.get(key);
        if (cached != null) {
            SvgIcon hit = cached.get();
            if (hit != null) {
                return hit;
            }
        }
        // Built outside the map's lock and raced deliberately: two threads asking at once
        // may both parse, and computeIfAbsent would instead hold the bin while reading three
        // megabytes of blob.
        SvgIcon built = SvgIcon.of(new String(svg(key, name), StandardCharsets.UTF_8));
        // merge settles which instance everyone gets, and sharing ONE instance is what makes a
        // hundred buttons hold one bitmap. It has to look THROUGH the existing reference rather
        // than merely find one: an entry whose icon has been collected is a hit that hands back
        // null, which is the bug putIfAbsent would have had here.
        SoftReference<SvgIcon> winner = ICONS.merge(key, new SoftReference<>(built),
                (existing, fresh) -> existing.get() != null ? existing : fresh);
        SvgIcon settled = winner.get();
        // Collected between the merge and this read: the caller still gets a usable icon, just
        // not necessarily the shared one. Rarer than rare, and a wrong answer here would be null.
        return settled != null ? settled : built;
    }

    private static Map<String, Slice> index() {
        Map<String, Slice> current = index;
        if (current != null) {
            return current;
        }
        synchronized (LOCK) {
            if (index == null) {
                index = readIndex();
            }
            return index;
        }
    }

    private static Map<String, Slice> readIndex() {
        Map<String, Slice> parsed = new LinkedHashMap<>();
        String text = new String(read("icons.index"), StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int firstTab = line.indexOf('\t');
            int secondTab = line.indexOf('\t', firstTab + 1);
            parsed.put(line.substring(0, firstTab),
                    new Slice(Integer.parseInt(line.substring(firstTab + 1, secondTab)),
                            Integer.parseInt(line.substring(secondTab + 1))));
        }
        return Collections.unmodifiableMap(parsed);
    }

    private static byte[] payload() {
        byte[] current = blob.get();
        if (current != null) {
            return current;
        }
        synchronized (LOCK) {
            byte[] again = blob.get();
            if (again != null) {
                return again;
            }
            byte[] loaded = read("icons.blob");
            blob = new SoftReference<>(loaded);
            return loaded;
        }
    }

    private static byte[] read(String resource) {
        try (InputStream in = Tabler.class.getResourceAsStream(BASE + resource)) {
            if (in == null) {
                throw new IllegalStateException("limn-icons-tabler is on the classpath without "
                        + "its resources: " + BASE + resource + " is missing. It is generated;"
                        + " see scripts/generate-tabler-icons.py.");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("could not read " + BASE + resource, failure);
        }
    }
}
