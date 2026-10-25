package limn.graphics.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import limn.graphics.SvgRenderer;
import org.jspecify.annotations.Nullable;

/**
 * The animation of an SVG, read by the toolkit and played by it, a moment at a time: SMIL's
 * {@code animate}, {@code animateTransform} and {@code set}, and CSS's {@code @keyframes} with the
 * {@code animation} properties. The renderer animates nothing; at each moment drawn, every value
 * that moves is computed here and set on the document as an attribute, and the document is drawn.
 *
 * <p>What is read: SMIL's {@code from}, {@code to}, {@code by} and {@code values}; {@code dur},
 * {@code begin} as a time (negative too), {@code repeatCount}, {@code repeatDur}, {@code fill};
 * {@code keyTimes}, {@code keySplines}, {@code calcMode} linear, discrete and spline (paced as
 * linear); {@code additive} for transforms. CSS's name, duration, timing function
 * ({@code cubic-bezier} and {@code steps} among them, per keyframe too), delay, iteration count,
 * direction and fill mode, over {@code transform} with {@code transform-origin} and
 * {@code transform-box}, and over every other property, as an attribute. What is not:
 * {@code animateMotion}, a beginning on an event, {@code accumulate}, CSS transitions; a
 * transform CSS gives an element without animating it is drawn too, which the renderer cannot.
 *
 * <p>A SMIL animation may begin at another's beginning or end, offset or not
 * ({@code begin="0;spin.end-0.25s"}), which is how indicators chain their parts into a loop: every
 * instant each begins at is worked out to thirty seconds, a beginning while it plays restarting
 * it, and a chain that comes round again is a loop of its own period.
 *
 * <p><b>Frames are moments at a fixed rate</b>, so an animated SVG is an animated image like any
 * other. One whose animations repeat without end plays its period without end: the least common
 * multiple of their cycles, from the moment the last of them repeats steadily, held under
 * {@link #MAX_LENGTH_MILLIS}. One whose animations all end plays its length once and rests on its
 * last moment.
 */
public final class SvgAnimation {

    /** The rate an SVG plays at unless its icon or {@code limn.svg.frameRate} says otherwise: that of film. */
    public static final int DEFAULT_FRAME_RATE = 24;
    /** The highest rate. */
    public static final int MAX_FRAME_RATE = 120;
    /** The longest an SVG's frames cover: thirty seconds. */
    public static final int MAX_LENGTH_MILLIS = 30_000;
    /** The most animations one file may have. */
    public static final int MAX_ANIMATIONS = 10_000;
    /**
     * The most beginnings and ends read, over every round, to work out when a file's chained SMIL
     * animations begin: a chain that restarts its links every millisecond took 36 seconds before
     * this was counted. An animation that begins at its own times only is worked out once and
     * not counted.
     */
    public static final long MAX_TIMING_STEPS = 4_000_000L;

    private static final Set<String> SMIL = Set.of("animate", "animateTransform", "set", "animateColor", "animateMotion");

    /** Properties that inherit, which an animation that is not in effect gives back to the parent. */
    private static final Set<String> INHERITED = Set.of("fill", "stroke", "fill-opacity", "stroke-opacity", "stroke-width",
            "stroke-dasharray", "stroke-dashoffset", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit",
            "fill-rule", "clip-rule", "visibility", "color", "font-size", "font-family", "font-weight", "font-style",
            "text-anchor", "letter-spacing", "word-spacing");

    /** Properties a stylesheet may give, as an attribute may. */
    private static final Set<String> PRESENTATION = Set.of("fill", "stroke", "opacity", "fill-opacity", "stroke-opacity",
            "stroke-width", "stroke-dasharray", "stroke-dashoffset", "stroke-linecap", "stroke-linejoin",
            "stroke-miterlimit", "fill-rule", "clip-rule", "visibility", "display", "color", "stop-color",
            "stop-opacity", "font-size", "font-family", "font-weight", "font-style", "text-anchor", "letter-spacing",
            "word-spacing", "clip-path", "mask", "filter", "marker-start", "marker-mid", "marker-end",
            "flood-color", "flood-opacity", "lighting-color", "mix-blend-mode", "transform");

    /** What one moving value is at a moment: null when it is not in effect then. */
    private interface Track {
        @Nullable String at(double millis);

        /** The length of its cycle once it repeats as it will for ever, else 0. */
        double period();

        /** From when it repeats with {@link #period()}. */
        double steadyFrom();

        /** When it ends, for one that does not repeat for ever. */
        double end();

        /**
         * When the interval in effect at {@code millis} began: of several animations of one
         * attribute in effect, held or playing, the one that began last wins, as SMIL ranks them.
         */
        double since(double millis);
    }

    /** The moving values of one attribute of one element, the last in effect winning. */
    private static final class Slot {
        final String id;
        final String attribute;
        final String base;
        final List<Track> tracks = new ArrayList<>();

        Slot(String id, String attribute, String base) {
            this.id = id;
            this.attribute = attribute;
            this.base = base;
        }

        String at(double millis) {
            String value = null;
            double rank = Double.NEGATIVE_INFINITY;
            for (Track track : tracks) {
                String v = track.at(millis);
                if (v != null) {
                    double since = track.since(millis);
                    if (since >= rank) { // later in the document wins a tie
                        value = v;
                        rank = since;
                    }
                }
            }
            return value == null ? base : value;
        }
    }

    /** A transform that moves: the element's own, its SMIL transforms, and CSS's, which replaces both. */
    private static final class TransformSlot {
        final String id;
        final String attribute;
        final List<Track> smil = new ArrayList<>();
        final List<Boolean> additive = new ArrayList<>();
        @Nullable String cssStatic;
        final List<Track> css = new ArrayList<>();
        double[] origin = {0, 0};
        SvgValues.Box box = new SvgValues.Box(0, 0, 0, 0);

        TransformSlot(String id, String attribute) {
            this.id = id;
            this.attribute = attribute;
        }

        String at(double millis) {
            String cssValue = cssStatic;
            for (Track track : css) {
                String v = track.at(millis);
                if (v != null) {
                    cssValue = v;
                }
            }
            if (cssValue != null) {
                return cssValue; // a matrix already, its origin applied
            }
            // In the order SMIL ranks them: the one that began last on top, a replace starting over.
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < smil.size(); i++) {
                order.add(i);
            }
            order.sort((x, y) -> Double.compare(smil.get(x).since(millis), smil.get(y).since(millis)));
            String value = attribute;
            for (int i : order) {
                String v = smil.get(i).at(millis);
                if (v != null) {
                    value = additive.get(i) ? (value + " " + v).trim() : v;
                }
            }
            return value.isEmpty() ? "matrix(1 0 0 1 0 0)" : value;
        }
    }

    private final List<Slot> slots;
    private final List<TransformSlot> transforms;
    private final boolean loops;
    private final double start;
    private final double length;

    private SvgAnimation(List<Slot> slots, List<TransformSlot> transforms) {
        this.slots = slots;
        this.transforms = transforms;
        List<Track> all = new ArrayList<>();
        for (Slot slot : slots) {
            all.addAll(slot.tracks);
        }
        for (TransformSlot t : transforms) {
            all.addAll(t.smil);
            all.addAll(t.css);
        }
        double period = 0;
        double begun = 0;
        double end = 0;
        boolean endless = false;
        for (Track track : all) {
            if (track.period() > 0) {
                endless = true;
                long cycle = Math.max(1, Math.round(track.period()));
                period = period == 0 ? cycle : lcm((long) period, cycle);
                begun = Math.max(begun, track.steadyFrom());
            } else if (!Double.isInfinite(track.end())) {
                end = Math.max(end, track.end());
            }
        }
        this.loops = endless;
        this.start = endless ? Math.min(begun, MAX_LENGTH_MILLIS) : 0;
        this.length = Math.min(MAX_LENGTH_MILLIS, Math.max(1, endless ? period : end));
    }

    private static long lcm(long a, long b) {
        long g = a;
        long h = b;
        while (h != 0) {
            long r = g % h;
            g = h;
            h = r;
        }
        long l = a / g * b;
        return l > MAX_LENGTH_MILLIS || l <= 0 ? MAX_LENGTH_MILLIS : l;
    }

    // ----------------------------------------------------------------------------- the timeline

    /** @return whether it plays without end */
    public boolean loops() {
        return loops;
    }

    /** @return how many frames at {@code rate} a second: the period's, or the length's and the last moment's */
    public int frameCount(int rate) {
        int n = Math.max(1, (int) Math.round(length * rate / 1000.0));
        return loops ? n : n + 1;
    }

    /** @return how long frame {@code frame} is shown, in milliseconds, at least 1 */
    public int frameMillis(int rate, int frame) {
        int n = Math.max(1, (int) Math.round(length * rate / 1000.0));
        if (frame >= n) {
            return Math.max(1, (int) Math.round(1000.0 / rate)); // the last moment, which it rests on
        }
        return (int) Math.max(1, Math.round((frame + 1) * length / n) - Math.round(frame * length / n));
    }

    /** @return the moment frame {@code frame} draws, in milliseconds of the document's time */
    public double timeOf(int rate, int frame) {
        int n = Math.max(1, (int) Math.round(length * rate / 1000.0));
        return start + Math.min(frame, n) * length / n;
    }

    /** What has been set on one document, so a frame sets only what changed since the one before. */
    public static final class Applied {
        private final Map<String, String> written = new HashMap<>();
    }

    /** Sets every moving value at {@code millis} on {@code document}. */
    public void apply(SvgRenderer.Document document, Applied applied, double millis) {
        for (Slot slot : slots) {
            set(document, applied, slot.id, slot.attribute, slot.at(millis));
        }
        for (TransformSlot slot : transforms) {
            set(document, applied, slot.id, "transform", slot.at(millis));
        }
    }

    private static void set(SvgRenderer.Document document, Applied applied, String id, String attribute, String value) {
        if (SvgSource.isReference(attribute)) {
            return; // never set, whoever asks: what the renderer opens as a path is the sanitizer's alone
        }
        String key = id + '\u0000' + attribute;
        if (!value.equals(applied.written.get(key))) {
            document.setAttribute(id, attribute, value);
            applied.written.put(key, value);
        }
    }

    // ----------------------------------------------------------------------------- reading

    /**
     * Takes the animations out of a document: SMIL elements are removed, every animated element is
     * given an id if it has none, and a transform CSS gives an element is written as its attribute.
     * The document was read from a file of {@code fileBytes}, which the work its style sheets may
     * cost is held to.
     *
     * @return the animation, or null when nothing in the document moves
     */
    public static @Nullable SvgAnimation extract(SvgXml.Element root, int fileBytes) {
        Reader reader = new Reader(root, fileBytes);
        reader.read();
        if (reader.slots.isEmpty() && reader.transforms.values().stream().noneMatch(t -> !t.smil.isEmpty() || !t.css.isEmpty())) {
            return null;
        }
        List<TransformSlot> moving = new ArrayList<>();
        for (TransformSlot t : reader.transforms.values()) {
            if (!t.smil.isEmpty() || !t.css.isEmpty()) {
                moving.add(t);
            }
        }
        return new SvgAnimation(new ArrayList<>(reader.slots.values()), moving);
    }

    private static final class Reader {

        private final SvgXml.Element root;
        private final SvgCss css;
        private final Map<String, SvgXml.Element> byId = new HashMap<>();
        private final Map<String, Slot> slots = new LinkedHashMap<>();
        private final Map<String, TransformSlot> transforms = new LinkedHashMap<>();
        private int animations;
        private int nextId;

        Reader(SvgXml.Element root, int fileBytes) {
            this.root = root;
            this.css = SvgCss.of(root, fileBytes);
        }

        void read() {
            index(root);
            smil(root);
            resolve();
            cssWalk(root);
            // The SMIL elements leave the document only now, so that every selector was matched
            // against the document as written, where an animation is a child like any other and
            // counts in a :last-child or an :nth-child, as it does in a browser.
            for (SvgXml.Element[] read : taken) {
                read[0].children().remove(read[1]);
            }
        }

        /** The SMIL elements read, each with its parent, to be taken out once the reading is done. */
        private final List<SvgXml.Element[]> taken = new ArrayList<>();

        private void index(SvgXml.Element element) {
            String id = element.attribute("id");
            if (id != null) {
                byId.putIfAbsent(id, element);
            }
            for (SvgXml.Element child : element.elements()) {
                index(child);
            }
        }

        private String idOf(SvgXml.Element element) {
            String id = element.attribute("id");
            if (id == null || id.isEmpty()) {
                do {
                    id = "limn-anim-" + nextId++;
                } while (byId.containsKey(id));
                element.setAttribute("id", id);
                byId.put(id, element);
            }
            return id;
        }

        private void count() {
            if (++animations > MAX_ANIMATIONS) {
                throw SvgXml.refused("more than " + MAX_ANIMATIONS + " animations");
            }
        }

        // ------------------------------------------------------------------------- SMIL

        private void smil(SvgXml.Element element) {
            List<Object> children = element.children();
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i) instanceof SvgXml.Element child) {
                    if (SMIL.contains(child.localName())) {
                        count();
                        smilElement(child, element);
                        taken.add(new SvgXml.Element[] {element, child});
                    } else {
                        smil(child);
                    }
                }
            }
        }

        private void smilElement(SvgXml.Element animation, SvgXml.Element parent) {
            String kind = animation.localName();
            if (kind.equals("animateMotion")) {
                return; // not played
            }
            SvgXml.Element target = parent;
            String href = SvgSource.href(animation);
            if (href != null && href.startsWith("#")) {
                SvgXml.Element referenced = byId.get(href.substring(1));
                if (referenced == null) {
                    return;
                }
                target = referenced;
            }
            String name = animation.attribute("attributeName");
            if (name == null || name.isBlank()) {
                return;
            }
            name = name.trim();
            if (name.startsWith("xlink:") || SvgSource.isReference(name)) {
                return; // a reference does not move: the renderer would open what it moved to as a path
            }
            Timing timing = Timing.smil(animation);
            if (timing == null) {
                return;
            }
            String id = idOf(target);
            if (kind.equals("animateTransform") && (name.equals("transform") || name.equals("gradientTransform")
                    || name.equals("patternTransform"))) {
                String type = animation.attribute("type");
                type = type == null ? "translate" : type.trim();
                List<String> values = smilValues(animation, null);
                if (values == null) {
                    return;
                }
                List<String> functions = new ArrayList<>();
                for (String v : values) {
                    functions.add(type + "(" + v.trim().replace(',', ' ') + ")");
                }
                SmilTrack track = register(new SmilTrack(timing, functions, animation, true), animation);
                if (name.equals("transform")) {
                    TransformSlot slot = transformSlot(target, id);
                    slot.smil.add(track);
                    slot.additive.add("sum".equals(animation.attribute("additive")));
                } else {
                    slot(target, id, name, false).tracks.add(track);
                }
                return;
            }
            Slot slot = slot(target, id, name, PRESENTATION.contains(name));
            if (kind.equals("set")) {
                String to = animation.attribute("to");
                if (to != null) {
                    slot.tracks.add(register(new SmilTrack(timing, List.of(to.trim()), animation, false), animation));
                }
                return;
            }
            List<String> values = smilValues(animation, slot.base);
            if (values != null) {
                slot.tracks.add(register(new SmilTrack(timing, values, animation, false), animation));
            }
        }

        private final List<SmilTrack> smilTracks = new ArrayList<>();
        private final Map<String, SmilTrack> smilById = new HashMap<>();

        private SmilTrack register(SmilTrack track, SvgXml.Element animation) {
            smilTracks.add(track);
            String id = animation.attribute("id");
            if (id != null && !id.isBlank()) {
                smilById.putIfAbsent(id.trim(), track);
            }
            return track;
        }

        /**
         * Works out when every SMIL animation begins and ends, to {@link #WINDOW} milliseconds:
         * its own times, and the beginnings and ends of the animations it refers to, offset, until
         * nothing changes. A beginning while one plays restarts it.
         *
         * <p>Each round moves a chain on by one link, so a chain of short links takes as many
         * rounds as it has beginnings in the window, each round over every animation of a chain:
         * what the rounds together take is held under {@link #MAX_TIMING_STEPS}, past which the
         * file is refused. An indicator's chain takes some thousands.
         */
        private void resolve() {
            List<SmilTrack> chained = new ArrayList<>();
            for (SmilTrack track : smilTracks) {
                track.instances(track.timing.offsets());
                if (!track.timing.syncs().isEmpty()) {
                    chained.add(track);
                }
            }
            // Only an animation that begins at another's beginning or end changes from round to
            // round: one that begins at its own times has them from the start, and is neither
            // worked out again nor counted, however many of them a file has.
            List<SmilTrack> moved = new ArrayList<>(smilTracks);
            long steps = 0;
            for (int round = 0; round < 4096; round++) {
                for (SmilTrack track : moved) {
                    track.intervals();
                }
                moved.clear();
                for (SmilTrack track : chained) {
                    List<Double> instances = new ArrayList<>(track.timing.offsets());
                    for (Sync sync : track.timing.syncs()) {
                        SmilTrack base = smilById.get(sync.id());
                        if (base == null) {
                            continue;
                        }
                        double[] times = sync.end() ? base.ends : base.begins;
                        timingSteps(steps + instances.size() + times.length);
                        for (double t : times) {
                            if (!Double.isInfinite(t)) {
                                instances.add(t + sync.offset());
                            }
                        }
                    }
                    steps += 1 + instances.size();
                    timingSteps(steps);
                    if (track.instances(instances)) {
                        moved.add(track);
                    }
                }
                if (moved.isEmpty()) {
                    return;
                }
            }
        }

        private static void timingSteps(long steps) {
            if (steps > MAX_TIMING_STEPS) {
                throw SvgXml.refused("working out when the animations begin takes more than "
                        + MAX_TIMING_STEPS + " steps");
            }
        }

        /** {@code values}, or {@code from}, {@code to} and {@code by} made into values; null for none. */
        private static @Nullable List<String> smilValues(SvgXml.Element animation, @Nullable String base) {
            String values = animation.attribute("values");
            if (values != null) {
                List<String> out = new ArrayList<>();
                for (String v : values.split(";")) {
                    if (!v.isBlank()) {
                        out.add(v.trim());
                    }
                }
                return out.isEmpty() ? null : out;
            }
            String from = animation.attribute("from");
            String to = animation.attribute("to");
            String by = animation.attribute("by");
            if (from == null) {
                from = base;
            }
            if (to != null) {
                return from == null ? List.of(to.trim()) : List.of(from.trim(), to.trim());
            }
            if (by != null && from != null) {
                return List.of(from.trim(), SvgValues.add(from.trim(), by.trim()));
            }
            return null;
        }

        private Slot slot(SvgXml.Element element, String id, String attribute, boolean presentation) {
            String key = id + '\u0000' + attribute;
            Slot slot = slots.get(key);
            if (slot == null) {
                String base = presentation ? css.cascaded(element, attribute, true) : element.attribute(attribute);
                if (base == null) {
                    base = initial(attribute);
                }
                slot = new Slot(id, attribute, base);
                slots.put(key, slot);
            }
            return slot;
        }

        private TransformSlot transformSlot(SvgXml.Element element, String id) {
            TransformSlot slot = transforms.get(id);
            if (slot == null) {
                String own = element.attribute("transform");
                slot = new TransformSlot(id, own == null ? "" : own);
                transforms.put(id, slot);
            }
            return slot;
        }

        // ------------------------------------------------------------------------- CSS

        private void cssWalk(SvgXml.Element element) {
            if (SMIL.contains(element.localName())) {
                return; // read already, and leaving the document
            }
            if (!element.localName().equals("style")) {
                cssElement(element);
            }
            for (SvgXml.Element child : element.elements()) {
                cssWalk(child);
            }
        }

        private void cssElement(SvgXml.Element element) {
            String transform = css.cascaded(element, "transform", false);
            List<CssAnimation> animations = cssAnimations(element);
            if (transform == null && animations.isEmpty()) {
                return;
            }
            SvgValues.Box box = referenceBox(element);
            double[] origin = origin(element, box);
            if (animations.isEmpty()) {
                // A transform CSS gives and nothing moves: written as the attribute the renderer reads.
                element.setAttribute("transform", SvgValues.attribute(around(SvgValues.matrix(transform, true, box), origin)));
                return;
            }
            String id = idOf(element);
            for (CssAnimation animation : animations) {
                List<SvgCss.Keyframe> frames = css.keyframes.get(animation.name);
                if (frames == null || frames.isEmpty() || !(animation.duration > 0)) {
                    continue;
                }
                count();
                for (String property : propertiesOf(frames)) {
                    if (property.equals("transform")) {
                        TransformSlot slot = transformSlot(element, id);
                        slot.box = box;
                        slot.origin = origin;
                        if (transform != null && slot.cssStatic == null) {
                            slot.cssStatic = SvgValues.attribute(around(SvgValues.matrix(transform, true, box), origin));
                        }
                        String base = transform == null ? "none" : transform;
                        slot.css.add(new CssTrack(animation, frames, property, base, box, origin));
                    } else if (property.equals("offset-distance") || property.startsWith("animation")) {
                        continue; // motion along a path, and the animation's own properties: not played
                    } else if (SvgSource.isReference(property)) {
                        continue; // a reference does not move, from a keyframe either
                    } else {
                        Slot slot = slot(element, id, property, true);
                        slot.tracks.add(new CssTrack(animation, frames, property, slot.base, box, origin));
                    }
                }
            }
            if (transform != null) {
                TransformSlot slot = transforms.get(id);
                if (slot == null) {
                    element.setAttribute("transform", SvgValues.attribute(around(SvgValues.matrix(transform, true, box), origin)));
                } else if (slot.cssStatic == null) {
                    slot.cssStatic = SvgValues.attribute(around(SvgValues.matrix(transform, true, box), origin));
                }
            }
        }

        private static List<String> propertiesOf(List<SvgCss.Keyframe> frames) {
            List<String> out = new ArrayList<>();
            for (SvgCss.Keyframe frame : frames) {
                for (String property : frame.declarations().keySet()) {
                    if (!property.equals("animation-timing-function") && !out.contains(property)) {
                        out.add(property);
                    }
                }
            }
            return out;
        }

        /** The animations CSS gives an element: the shorthand's, each longhand given overriding it. */
        private List<CssAnimation> cssAnimations(SvgXml.Element element) {
            String shorthand = css.cascaded(element, "animation", false);
            String names = css.cascaded(element, "animation-name", false);
            if (shorthand == null && names == null) {
                return List.of();
            }
            List<CssAnimation> out = new ArrayList<>();
            if (shorthand != null) {
                for (String one : SvgCss.splitTopLevel(shorthand, ',')) {
                    CssAnimation a = CssAnimation.shorthand(one.trim(), css.keyframes.keySet());
                    if (a != null) {
                        out.add(a);
                    }
                }
            }
            if (names != null) {
                List<String> list = SvgCss.splitTopLevel(names, ',');
                for (int i = 0; i < list.size(); i++) {
                    String name = SvgCss.unquote(list.get(i).trim());
                    if (i < out.size()) {
                        out.get(i).name = name;
                    } else {
                        CssAnimation a = new CssAnimation();
                        a.name = name;
                        out.add(a);
                    }
                }
            }
            longhand(element, "animation-duration", out, (a, v) -> a.duration = Timing.clock(v));
            longhand(element, "animation-delay", out, (a, v) -> a.delay = Timing.clock(v));
            longhand(element, "animation-timing-function", out, (a, v) -> a.easing = v);
            longhand(element, "animation-iteration-count", out, (a, v) ->
                    a.count = v.trim().equals("infinite") ? Double.POSITIVE_INFINITY : SvgValues.number(v, 1, false));
            longhand(element, "animation-direction", out, (a, v) -> a.direction = v.trim());
            longhand(element, "animation-fill-mode", out, (a, v) -> a.fill = v.trim());
            out.removeIf(a -> a.name == null || a.name.equals("none"));
            return out;
        }

        private void longhand(SvgXml.Element element, String property, List<CssAnimation> animations,
                              java.util.function.BiConsumer<CssAnimation, String> set) {
            String value = css.cascaded(element, property, false);
            if (value == null || animations.isEmpty()) {
                return;
            }
            List<String> list = SvgCss.splitTopLevel(value, ',');
            for (int i = 0; i < animations.size(); i++) {
                set.accept(animations.get(i), list.get(i % list.size()).trim());
            }
        }

        /** The box a CSS transform's percentages and origin refer to: the view box unless {@code transform-box} says the element's own. */
        private SvgValues.Box referenceBox(SvgXml.Element element) {
            String box = css.cascaded(element, "transform-box", false);
            if (box != null && (box.trim().equals("fill-box") || box.trim().equals("border-box")
                    || box.trim().equals("content-box") || box.trim().equals("stroke-box"))) {
                double[] b = Bounds.of(element);
                if (b != null) {
                    return new SvgValues.Box(b[0], b[1], b[2] - b[0], b[3] - b[1]);
                }
            }
            SvgXml.Element viewport = element.parent();
            while (viewport != null && !viewport.localName().equals("svg")) {
                viewport = viewport.parent();
            }
            if (viewport == null) {
                viewport = root;
            }
            String viewBox = viewport.attribute("viewBox");
            if (viewBox != null) {
                String[] p = viewBox.trim().split("[\\s,]+");
                if (p.length == 4) {
                    return new SvgValues.Box(SvgValues.number(p[0], 0, false), SvgValues.number(p[1], 0, false),
                            SvgValues.number(p[2], 0, false), SvgValues.number(p[3], 0, false));
                }
            }
            return new SvgValues.Box(0, 0, SvgValues.number(viewport.attribute("width"), 0, false),
                    SvgValues.number(viewport.attribute("height"), 0, false));
        }

        /** The point a CSS transform turns and scales about, in user units. */
        private double[] origin(SvgXml.Element element, SvgValues.Box box) {
            String origin = css.cascaded(element, "transform-origin", false);
            if (origin == null) {
                return new double[] {box.x(), box.y()};
            }
            String[] parts = origin.trim().toLowerCase(Locale.ROOT).split("\\s+");
            double x = box.width() / 2;
            double y = box.height() / 2;
            String first = parts[0];
            String second = parts.length > 1 ? parts[1] : "center";
            if (first.equals("top") || first.equals("bottom")) {
                String swap = first;
                first = second;
                second = swap;
            }
            x = originPart(first, box.width(), x);
            y = originPart(second, box.height(), y);
            return new double[] {box.x() + x, box.y() + y};
        }

        private static double originPart(String part, double of, double center) {
            return switch (part) {
                case "left", "top" -> 0;
                case "right", "bottom" -> of;
                case "center" -> center;
                default -> SvgValues.length(part, of);
            };
        }
    }

    /** {@code m} applied about {@code origin}: translate there, apply, translate back. */
    static double[] around(double[] m, double[] origin) {
        if (origin[0] == 0 && origin[1] == 0) {
            return m;
        }
        return SvgValues.multiply(SvgValues.multiply(new double[] {1, 0, 0, 1, origin[0], origin[1]}, m),
                new double[] {1, 0, 0, 1, -origin[0], -origin[1]});
    }

    /** The value an attribute has when nothing gives it one. */
    static String initial(String attribute) {
        if (INHERITED.contains(attribute)) {
            return "inherit";
        }
        return switch (attribute) {
            case "opacity", "stop-opacity", "flood-opacity" -> "1";
            case "display" -> "inline";
            case "transform" -> "matrix(1 0 0 1 0 0)";
            default -> "0";
        };
    }

    // ----------------------------------------------------------------------------- SMIL timing

    /** How far SMIL timing is worked out: thirty seconds, and a little more for a loop to show itself. */
    static final double WINDOW = MAX_LENGTH_MILLIS + 5_000;

    /** A beginning at another animation's beginning or end, offset. */
    private record Sync(String id, boolean end, double offset) {
    }

    /** When an animation may begin, how long it plays, and whether it holds its last value. */
    private record Timing(List<Double> offsets, List<Sync> syncs, double dur, double active, boolean freeze) {

        static @Nullable Timing smil(SvgXml.Element animation) {
            String begin = animation.attribute("begin");
            List<Double> offsets = new ArrayList<>();
            List<Sync> syncs = new ArrayList<>();
            if (begin == null || begin.isBlank()) {
                offsets.add(0.0);
            } else {
                for (String part : begin.split(";")) {
                    String token = part.trim().replace(" ", "");
                    if (token.isEmpty() || token.equals("indefinite")) {
                        continue;
                    }
                    if (token.matches("[+-]?[0-9.:]+[a-z]*")) {
                        double t = clock(token);
                        if (!Double.isNaN(t)) {
                            offsets.add(t);
                        }
                        continue;
                    }
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("([A-Za-z_][\\w.:-]*?)\\.(begin|end)([+-][0-9.:]+[a-z]*)?").matcher(token);
                    if (m.matches()) {
                        double offset = m.group(3) == null ? 0 : clock(m.group(3));
                        if (!Double.isNaN(offset)) {
                            syncs.add(new Sync(m.group(1), m.group(2).equals("end"), offset));
                        }
                    }
                    // An event, a repeat, an access key, a wall-clock time: not played.
                }
                if (offsets.isEmpty() && syncs.isEmpty()) {
                    return null;
                }
            }
            String durText = animation.attribute("dur");
            double dur = durText == null || durText.trim().equals("indefinite") || durText.trim().equals("media")
                    ? Double.POSITIVE_INFINITY : clock(durText);
            if (Double.isNaN(dur) || dur <= 0) {
                return null;
            }
            boolean set = animation.localName().equals("set");
            if (Double.isInfinite(dur) && !set) {
                return null; // no simple duration, nothing to interpolate over
            }
            String repeatCount = animation.attribute("repeatCount");
            String repeatDur = animation.attribute("repeatDur");
            double count = repeatCount == null ? (repeatDur == null ? 1 : Double.POSITIVE_INFINITY)
                    : repeatCount.trim().equals("indefinite") ? Double.POSITIVE_INFINITY : SvgValues.number(repeatCount, 1, false);
            if (!(count > 0)) {
                count = 1;
            }
            double active = count * dur;
            if (repeatDur != null && !repeatDur.trim().equals("indefinite")) {
                double rd = clock(repeatDur);
                if (!Double.isNaN(rd) && rd > 0) {
                    active = Math.min(active, rd);
                }
            }
            return new Timing(offsets, syncs, dur, active, "freeze".equals(animation.attribute("fill")));
        }

        /** A clock value, in milliseconds: {@code 2s}, {@code 300ms}, {@code 1.5min}, {@code 00:01.5}, or seconds. NaN when none. */
        static double clock(String text) {
            String t = text.trim().toLowerCase(Locale.ROOT);
            try {
                if (t.contains(":")) {
                    String[] parts = t.split(":");
                    double seconds = 0;
                    for (String p : parts) {
                        seconds = seconds * 60 + Double.parseDouble(p);
                    }
                    return seconds * 1000;
                }
                if (t.endsWith("ms")) {
                    return Double.parseDouble(t.substring(0, t.length() - 2));
                }
                if (t.endsWith("min")) {
                    return Double.parseDouble(t.substring(0, t.length() - 3)) * 60_000;
                }
                if (t.endsWith("h")) {
                    return Double.parseDouble(t.substring(0, t.length() - 1)) * 3_600_000;
                }
                if (t.endsWith("s")) {
                    return Double.parseDouble(t.substring(0, t.length() - 1)) * 1000;
                }
                return Double.parseDouble(t) * 1000;
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        /** Where a moment {@code into} an interval of {@code length} is in the simple duration, 0 to 1, or NaN when it is past and not held. */
        double progress(double into, double length) {
            if (into >= length) {
                if (!freeze) {
                    return Double.NaN;
                }
                if (Double.isInfinite(dur)) {
                    return 1;
                }
                double turns = length / dur;
                double part = turns - Math.floor(turns);
                return part < 1e-9 ? 1 : part;
            }
            if (Double.isInfinite(dur)) {
                return 0;
            }
            return (into % dur) / dur;
        }
    }

    private static final class SmilTrack implements Track {

        /** The most beginnings worked out for one animation. */
        private static final int MAX_INSTANCES = 4096;

        final Timing timing;
        /** When each of its intervals begins and ends, in order. */
        double[] begins = new double[0];
        double[] ends = new double[0];
        private double[] instances = new double[0];
        private final List<String> values;
        private final double[] keyTimes;
        private final SvgValues.Easing[] splines;
        private final boolean discrete;
        private final boolean transform;

        SmilTrack(Timing timing, List<String> values, SvgXml.Element animation, boolean transform) {
            this.timing = timing;
            this.values = values;
            this.transform = transform;
            String mode = animation.attribute("calcMode");
            this.discrete = animation.localName().equals("set") || "discrete".equals(mode);
            int n = values.size();
            double[] times = null;
            String kt = animation.attribute("keyTimes");
            if (kt != null) {
                String[] parts = kt.split(";");
                List<Double> parsed = new ArrayList<>();
                for (String p : parts) {
                    if (!p.isBlank()) {
                        parsed.add(SvgValues.number(p, 0, false));
                    }
                }
                if (parsed.size() == n) {
                    times = new double[n];
                    for (int i = 0; i < n; i++) {
                        times[i] = parsed.get(i);
                    }
                }
            }
            if (times == null) {
                times = new double[n];
                for (int i = 0; i < n; i++) {
                    times[i] = discrete ? (double) i / n : n == 1 ? 0 : (double) i / (n - 1);
                }
            }
            this.keyTimes = times;
            this.splines = new SvgValues.Easing[Math.max(0, n - 1)];
            String ks = "spline".equals(mode) ? animation.attribute("keySplines") : null;
            String[] segments = ks == null ? new String[0] : ks.split(";");
            for (int i = 0; i < splines.length; i++) {
                SvgValues.Easing easing = SvgValues.Easing.LINEAR;
                if (i < segments.length) {
                    String[] c = segments[i].trim().split("[\\s,]+");
                    if (c.length == 4) {
                        easing = SvgValues.bezier(SvgValues.number(c[0], 0, false), SvgValues.number(c[1], 0, false),
                                SvgValues.number(c[2], 1, false), SvgValues.number(c[3], 1, false));
                    }
                }
                splines[i] = easing;
            }
        }

        /** Sets the times it may begin at; whether they changed. */
        boolean instances(List<Double> times) {
            double[] sorted = times.stream().mapToDouble(Double::doubleValue)
                    .filter(t -> t < WINDOW).sorted().distinct().limit(MAX_INSTANCES).toArray();
            if (java.util.Arrays.equals(sorted, instances)) {
                return false;
            }
            instances = sorted;
            return true;
        }

        /** Its intervals: one from each beginning, cut short by the next, since a beginning while it plays restarts it. */
        void intervals() {
            int n = instances.length;
            begins = instances;
            ends = new double[n];
            for (int i = 0; i < n; i++) {
                double natural = instances[i] + timing.active;
                ends[i] = i + 1 < n ? Math.min(natural, instances[i + 1]) : natural;
            }
        }

        @Override
        public @Nullable String at(double millis) {
            int k = -1;
            for (int i = begins.length - 1; i >= 0; i--) {
                if (begins[i] <= millis) {
                    k = i;
                    break;
                }
            }
            if (k < 0) {
                return null;
            }
            double p = timing.progress(millis - begins[k], ends[k] - begins[k]);
            if (Double.isNaN(p)) {
                return null;
            }
            int n = values.size();
            if (n == 1) {
                return values.get(0);
            }
            if (discrete) {
                int index = 0;
                for (int i = 0; i < n; i++) {
                    if (keyTimes[i] <= p + 1e-12) {
                        index = i;
                    }
                }
                return values.get(index);
            }
            if (p >= keyTimes[n - 1]) {
                return values.get(n - 1);
            }
            int segment = 0;
            while (segment < n - 2 && p >= keyTimes[segment + 1]) {
                segment++;
            }
            double span = keyTimes[segment + 1] - keyTimes[segment];
            double local = span <= 0 ? 1 : (p - keyTimes[segment]) / span;
            local = splines[segment].at(Math.max(0, Math.min(1, local)));
            String a = values.get(segment);
            String b = values.get(segment + 1);
            return SvgValues.interpolate(a, b, local, transform ? "transform-attribute" : "");
        }

        @Override
        public double period() {
            if (begins.length == 0) {
                return 0;
            }
            if (Double.isInfinite(ends[ends.length - 1])) {
                return Double.isInfinite(timing.dur) ? 0 : timing.dur; // repeats for ever in its last interval
            }
            // Beginnings that come round at one distance, to the end of the window: a chain's loop.
            int n = begins.length;
            if (n < 3 || begins[n - 1] < MAX_LENGTH_MILLIS) {
                return 0;
            }
            double step = begins[n - 1] - begins[n - 2];
            return step > 0 && Math.abs(begins[n - 2] - begins[n - 3] - step) < 0.5 ? step : 0;
        }

        @Override
        public double steadyFrom() {
            if (begins.length == 0) {
                return 0;
            }
            if (Double.isInfinite(ends[ends.length - 1])) {
                return Math.max(0, begins[begins.length - 1]);
            }
            double step = period();
            int i = begins.length - 2;
            while (i > 0 && Math.abs(begins[i] - begins[i - 1] - step) < 0.5) {
                i--;
            }
            return Math.max(0, begins[i]);
        }

        @Override
        public double end() {
            return ends.length == 0 ? 0 : ends[ends.length - 1];
        }

        @Override
        public double since(double millis) {
            for (int i = begins.length - 1; i >= 0; i--) {
                if (begins[i] <= millis) {
                    return begins[i];
                }
            }
            return Double.NEGATIVE_INFINITY;
        }
    }

    // ----------------------------------------------------------------------------- CSS timing

    /** One CSS animation as the cascade gave it to an element. */
    private static final class CssAnimation {
        @Nullable String name;
        double duration;
        double delay;
        @Nullable String easing;
        double count = 1;
        String direction = "normal";
        String fill = "none";

        static @Nullable CssAnimation shorthand(String text, Set<String> known) {
            CssAnimation a = new CssAnimation();
            int times = 0;
            for (String token : SvgCss.splitTopLevel(text, ' ')) {
                String t = token.trim();
                if (t.isEmpty()) {
                    continue;
                }
                String lower = t.toLowerCase(Locale.ROOT);
                double clock = lower.matches("[+-]?[0-9.]+m?s") ? Timing.clock(lower) : Double.NaN;
                if (!Double.isNaN(clock)) {
                    if (times++ == 0) {
                        a.duration = clock;
                    } else {
                        a.delay = clock;
                    }
                } else if (lower.equals("infinite")) {
                    a.count = Double.POSITIVE_INFINITY;
                } else if (lower.matches("[0-9.]+")) {
                    try {
                        a.count = Double.parseDouble(lower);
                    } catch (NumberFormatException damaged) {
                        return null; // "." or "1.2.3": a declaration a browser cannot read, and drops
                    }
                } else if (Set.of("linear", "ease", "ease-in", "ease-out", "ease-in-out", "step-start", "step-end")
                        .contains(lower) || lower.startsWith("cubic-bezier(") || lower.startsWith("steps(")) {
                    a.easing = t;
                } else if (Set.of("normal", "reverse", "alternate", "alternate-reverse").contains(lower)
                        && !known.contains(t)) {
                    a.direction = lower;
                } else if (Set.of("none", "forwards", "backwards", "both").contains(lower) && !known.contains(t)) {
                    a.fill = lower;
                } else if (Set.of("running", "paused").contains(lower)) {
                    continue;
                } else {
                    a.name = SvgCss.unquote(t);
                }
            }
            return a.name == null ? null : a;
        }
    }

    private static final class CssTrack implements Track {

        private final CssAnimation animation;
        private final String property;
        private final double[] offsets;
        private final String[] values;
        private final SvgValues.Easing[] easings;
        private final SvgValues.Box box;
        private final double[] origin;

        CssTrack(CssAnimation animation, List<SvgCss.Keyframe> frames, String property, String base,
                 SvgValues.Box box, double[] origin) {
            this.animation = animation;
            this.property = property;
            this.box = box;
            this.origin = origin;
            List<Double> o = new ArrayList<>();
            List<String> v = new ArrayList<>();
            List<SvgValues.Easing> e = new ArrayList<>();
            SvgValues.Easing standard = SvgValues.cssEasing(animation.easing);
            for (SvgCss.Keyframe frame : frames) {
                String value = frame.declarations().get(property);
                if (value == null) {
                    continue;
                }
                String timing = frame.declarations().get("animation-timing-function");
                o.add(frame.offset());
                v.add(value);
                e.add(timing == null ? standard : SvgValues.cssEasing(timing));
            }
            if (o.isEmpty() || o.get(0) > 0) {
                o.add(0, 0.0);
                v.add(0, base);
                e.add(0, standard);
            }
            if (o.get(o.size() - 1) < 1) {
                o.add(1.0);
                v.add(base);
                e.add(standard);
            }
            this.offsets = new double[o.size()];
            for (int i = 0; i < offsets.length; i++) {
                offsets[i] = o.get(i);
            }
            this.values = v.toArray(new String[0]);
            this.easings = e.toArray(new SvgValues.Easing[0]);
        }

        @Override
        public @Nullable String at(double millis) {
            double duration = animation.duration;
            double elapsed = millis - animation.delay;
            double progress;
            int iteration;
            boolean backwards = animation.fill.equals("backwards") || animation.fill.equals("both");
            boolean forwards = animation.fill.equals("forwards") || animation.fill.equals("both");
            if (elapsed < 0) {
                if (!backwards) {
                    return null;
                }
                progress = 0;
                iteration = 0;
            } else if (elapsed >= animation.count * duration) {
                if (!forwards) {
                    return null;
                }
                double turns = animation.count;
                double part = turns - Math.floor(turns);
                iteration = (int) Math.max(0, part < 1e-9 ? turns - 1 : Math.floor(turns));
                progress = part < 1e-9 ? 1 : part;
            } else {
                iteration = (int) Math.floor(elapsed / duration);
                progress = (elapsed - iteration * duration) / duration;
            }
            boolean reversed = switch (animation.direction) {
                case "reverse" -> true;
                case "alternate" -> iteration % 2 == 1;
                case "alternate-reverse" -> iteration % 2 == 0;
                default -> false;
            };
            if (reversed) {
                progress = 1 - progress;
            }
            int segment = 0;
            while (segment < offsets.length - 2 && progress >= offsets[segment + 1]) {
                segment++;
            }
            double span = offsets[segment + 1] - offsets[segment];
            double local = span <= 0 ? 1 : (progress - offsets[segment]) / span;
            local = easings[segment].at(Math.max(0, Math.min(1, local)));
            if (property.equals("transform")) {
                return SvgValues.attribute(around(
                        SvgValues.interpolateTransform(values[segment], values[segment + 1], local, box), origin));
            }
            String value = SvgValues.interpolate(values[segment], values[segment + 1], local, property);
            if (property.endsWith("opacity") && value.trim().endsWith("%")) {
                return SvgValues.format(SvgValues.number(value, 1, true));
            }
            return value;
        }

        @Override
        public double period() {
            if (!Double.isInfinite(animation.count)) {
                return 0;
            }
            return animation.direction.startsWith("alternate") ? 2 * animation.duration : animation.duration;
        }

        @Override
        public double steadyFrom() {
            return Math.max(0, animation.delay);
        }

        @Override
        public double end() {
            return animation.delay + animation.count * animation.duration;
        }

        @Override
        public double since(double millis) {
            return animation.delay;
        }
    }

    // ----------------------------------------------------------------------------- bounds

    /** The box an element's own geometry covers, from its attributes: min x, min y, max x, max y; null when not known. */
    static final class Bounds {

        private Bounds() {
        }

        static double @Nullable [] of(SvgXml.Element element) {
            double[] b = switch (element.localName()) {
                case "rect", "image", "use", "foreignObject" -> {
                    double x = SvgValues.number(element.attribute("x"), 0, false);
                    double y = SvgValues.number(element.attribute("y"), 0, false);
                    yield new double[] {x, y, x + SvgValues.number(element.attribute("width"), 0, false),
                            y + SvgValues.number(element.attribute("height"), 0, false)};
                }
                case "circle" -> {
                    double cx = SvgValues.number(element.attribute("cx"), 0, false);
                    double cy = SvgValues.number(element.attribute("cy"), 0, false);
                    double r = SvgValues.number(element.attribute("r"), 0, false);
                    yield new double[] {cx - r, cy - r, cx + r, cy + r};
                }
                case "ellipse" -> {
                    double cx = SvgValues.number(element.attribute("cx"), 0, false);
                    double cy = SvgValues.number(element.attribute("cy"), 0, false);
                    double rx = SvgValues.number(element.attribute("rx"), 0, false);
                    double ry = SvgValues.number(element.attribute("ry"), 0, false);
                    yield new double[] {cx - rx, cy - ry, cx + rx, cy + ry};
                }
                case "line" -> {
                    double x1 = SvgValues.number(element.attribute("x1"), 0, false);
                    double y1 = SvgValues.number(element.attribute("y1"), 0, false);
                    double x2 = SvgValues.number(element.attribute("x2"), 0, false);
                    double y2 = SvgValues.number(element.attribute("y2"), 0, false);
                    yield new double[] {Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)};
                }
                case "polyline", "polygon" -> points(element.attribute("points"));
                case "path" -> path(element.attribute("d"));
                case "g", "svg", "a", "switch" -> {
                    double[] union = null;
                    for (SvgXml.Element child : element.elements()) {
                        double[] c = of(child);
                        if (c != null) {
                            union = union == null ? c : new double[] {Math.min(union[0], c[0]), Math.min(union[1], c[1]),
                                    Math.max(union[2], c[2]), Math.max(union[3], c[3])};
                        }
                    }
                    yield union;
                }
                default -> null;
            };
            return b;
        }

        private static double @Nullable [] points(@Nullable String text) {
            if (text == null) {
                return null;
            }
            SvgValues.Shape shape = SvgValues.shape(text);
            double[] n = shape.numbers();
            if (n.length < 2) {
                return null;
            }
            double[] b = {n[0], n[1], n[0], n[1]};
            for (int i = 0; i + 1 < n.length; i += 2) {
                b[0] = Math.min(b[0], n[i]);
                b[1] = Math.min(b[1], n[i + 1]);
                b[2] = Math.max(b[2], n[i]);
                b[3] = Math.max(b[3], n[i + 1]);
            }
            return b;
        }

        /** A path's box from its points, every command made absolute; control points included, which is near enough. */
        private static double @Nullable [] path(@Nullable String d) {
            if (d == null) {
                return null;
            }
            double[] b = null;
            double x = 0;
            double y = 0;
            double sx = 0;
            double sy = 0;
            char command = 'M';
            List<Double> args = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("[MmLlHhVvCcSsQqTtAaZz]|[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?").matcher(d);
            List<Object> tokens = new ArrayList<>();
            while (m.find()) {
                String t = m.group();
                tokens.add(Character.isLetter(t.charAt(0)) && "eE".indexOf(t.charAt(0)) < 0 ? (Object) t.charAt(0)
                        : (Object) Double.parseDouble(t));
            }
            int i = 0;
            while (i < tokens.size()) {
                if (tokens.get(i) instanceof Character c) {
                    command = c;
                    i++;
                    if (c == 'Z' || c == 'z') {
                        x = sx;
                        y = sy;
                        continue;
                    }
                }
                int need = switch (Character.toUpperCase(command)) {
                    case 'H', 'V' -> 1;
                    case 'M', 'L', 'T' -> 2;
                    case 'S', 'Q' -> 4;
                    case 'C' -> 6;
                    case 'A' -> 7;
                    default -> 0;
                };
                if (need == 0 || i + need > tokens.size()) {
                    break;
                }
                args.clear();
                for (int k = 0; k < need; k++) {
                    if (!(tokens.get(i + k) instanceof Double v)) {
                        return b;
                    }
                    args.add(v);
                }
                i += need;
                boolean relative = Character.isLowerCase(command);
                double ox = relative ? x : 0;
                double oy = relative ? y : 0;
                switch (Character.toUpperCase(command)) {
                    case 'H' -> x = ox + args.get(0);
                    case 'V' -> y = oy + args.get(0);
                    case 'A' -> {
                        x = ox + args.get(5);
                        y = oy + args.get(6);
                    }
                    default -> {
                        for (int k = 0; k + 1 < need; k += 2) {
                            double px = ox + args.get(k);
                            double py = oy + args.get(k + 1);
                            b = include(b, px, py);
                        }
                        x = ox + args.get(need - 2);
                        y = oy + args.get(need - 1);
                    }
                }
                b = include(b, x, y);
                if (Character.toUpperCase(command) == 'M') {
                    sx = x;
                    sy = y;
                    command = relative ? 'l' : 'L';
                }
            }
            return b;
        }

        private static double[] include(double @Nullable [] b, double x, double y) {
            if (b == null) {
                return new double[] {x, y, x, y};
            }
            return new double[] {Math.min(b[0], x), Math.min(b[1], y), Math.max(b[2], x), Math.max(b[3], y)};
        }
    }
}
