package limn.demo;

import limn.components.Theme;
import limn.graphics.Color;
import limn.graphics.Fonts;
import limn.scene.ControlSize;
import limn.scene.Scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Whole screens, as opposed to the component gallery's single widgets: the kitchen sink and
 * the 3D viewport, captured on a canvas the size of a real window.
 *
 * <p>Lives in {@code limn.demo} rather than beside the rest of the site tooling because the
 * scenes it captures ({@code KitchenSinkScene}, {@code Viewport3DScene}) are
 * package-private, and opening them up so a sibling package could reach them would widen
 * the demo's API for the convenience of one caller.
 *
 * <p>The kitchen sink is captured in several languages on purpose. The toolkit already ships
 * bundles for nineteen tags, so showing a reader the toolkit rendering in <em>their</em>
 * language costs one more capture and is the most persuasive thing the site can do, and it
 * is the honest way to show the text pipeline handling CJK rather than asserting it in prose.
 */
public final class SiteShowcase {

    private SiteShowcase() {
    }

    /**
     * @param id     stable slug and file name; treat as published
     * @param locale UI language for this capture. Applied BEFORE the scene is constructed: a
     *               scene measures the strings it is built with, so a language set afterwards
     *               is a relayout the capture has no reason to wait for
     * @param filmed whether the capture drives a pointer over this screen and writes a frame per
     *               step. Off for every screen but one: what a whole-window capture is evidence of
     *               is the screen, and a film costs well over a hundred files and a share of the
     *               capture's wall clock. Turn it on only when the screen's claim is a change a
     *               still cannot show
     * @param settles whether this screen needs the shutter to wait on the wall clock before it
     *               is worth photographing. Almost none do, and it is the most expensive thing
     *               an entry can ask for: see {@link #settling()} for what buys it
     */
    public record Entry(String id, String title, Locale locale, boolean paletteInvariant,
                        boolean warmUpPass, boolean filmed, boolean settles,
                        Function<Theme, Scene> builder) {

        /** The ordinary case: the scene is built once per palette and looks different in each. */
        public Entry(String id, String title, Locale locale, Function<Theme, Scene> builder) {
            this(id, title, locale, false, false, false, false, builder);
        }

        /** Palette-invariant, captured once: the common case for a scene that pins its own theme. */
        public Entry(String id, String title, Locale locale, boolean paletteInvariant,
                Function<Theme, Scene> builder) {
            this(id, title, locale, paletteInvariant, false, false, false, builder);
        }

        /**
         * The same entry, marked as one the shutter must wait on.
         *
         * <p><b>Only a screen with something on a real clock in it.</b> The settle is wall-clock,
         * it is measured in seconds, and it is paid once per palette, so marking a screen that
         * does not need it is the most effective way to make this capture slow: it was blanket
         * before, and the screens that did not need it were most of the run.
         *
         * <p>Two things buy it today, both named where they are asked for: a live performance
         * footer, whose gauges latch on a once-per-second heartbeat that frames cannot hurry,
         * and the 3D window, whose first frame can present before its geometry does.
         */
        public Entry settling() {
            return new Entry(id, title, locale, paletteInvariant, warmUpPass, filmed, true,
                    builder);
        }
    }

    public static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        // The kitchen sink mounts a PerfFooter, so it is one of the two screens that has to
        // wait; every other entry below photographs as soon as it has drawn.
        entries.add(new Entry("kitchen", "The kitchen sink", Locale.ENGLISH,
                SiteShowcase::kitchen).settling());
        for (String tag : List.of("ja", "zh-Hans", "ko", "ru")) {
            entries.add(new Entry("kitchen-" + tag, "The kitchen sink in " + tag,
                    Locale.forLanguageTag(tag), SiteShowcase::kitchen).settling());
        }
        // Palette-invariant: the 3D scene picks its own sky, ground and materials, and the
        // window around it carries nothing else, so the two palettes render the same pixels.
        //
        // It keeps the second pass, and that is the ONE entry here that needs it: the first frame
        // a 3D window presents can come back as the cleared sky with no geometry in it, and a
        // graded sky is not one flat colour, so the driver's blank-frame guard cannot see it. The
        // first pass is the warm-up and the second overwrites it. Dropping this flag saves 2.6 s
        // and occasionally publishes an empty sky.
        entries.add(new Entry("viewport-3d", "The 3D viewport", Locale.ENGLISH, true, true, false,
                true, SiteShowcase::viewport));
        entries.add(new Entry("form", "A form", Locale.ENGLISH, SiteShowcase::form));
        entries.add(new Entry("layout", "A window laid out", Locale.ENGLISH, SiteShowcase::layout));
        entries.add(new Entry("control-size", "Every size step", Locale.ENGLISH,
                SiteShowcase::controlSize));
        // The one filmed screen (see Entry.filmed and Films.forShowcase).
        entries.add(new Entry("theme-editor", "The theme editor", Locale.ENGLISH, false, false,
                true, false, theme -> editor(theme)));
        entries.addAll(mosaicTiles());
        return entries;
    }

    /**
     * One tile's dressing: the three axes it varies at once, and the only place they are named.
     *
     * <p>Three axes per tile rather than one, because seven tiles cannot otherwise cover five
     * palettes, all five size steps and four typefaces, and a set of tiles that each move one
     * axis needs so many that two of them end up being the shipped palette at MEDIUM in Roboto,
     * which is one tile printed twice.
     *
     * @param faces file names under {@link #FACE_DIR}; empty means the bundled default family
     */
    private record Dressing(String id, String title, java.util.function.Supplier<Theme> theme,
                            ControlSize size, List<String> faces) {
    }

    /** Where the demo keeps the faces it ships. Relative; see {@link #typeface}. */
    private static final java.nio.file.Path FACE_DIR = java.nio.file.Path.of("limn-demo/fonts");

    /**
     * The seven dressings, in the order the mosaic lays them out.
     *
     * <p>Read down the columns rather than across the rows: every size step appears at least once,
     * every typeface appears at least once, and light and dark alternate. That alternation is
     * load-bearing: seven slats sorted by tone read as one picture darkening, not as seven
     * themes.
     */
    private static final List<Dressing> DRESSINGS = List.of(
            new Dressing("mosaic-vivid", "A saturated theme, roomiest step",
                    SiteShowcase::vivid, ControlSize.XLARGE, List.of()),
            new Dressing("mosaic-paper", "A warm light theme in Inter",
                    SiteShowcase::paper, ControlSize.SMALL, List.of("Inter-Variable.ttf")),
            new Dressing("mosaic-dusk", "A muted dark theme in a pixel face",
                    SiteShowcase::dusk, ControlSize.MEDIUM, List.of("Silkscreen-Regular.ttf")),
            new Dressing("mosaic-mint", "A cool light theme, densest step",
                    SiteShowcase::mint, ControlSize.XSMALL, List.of()),
            new Dressing("mosaic-ember", "A warm dark theme in Comic Neue",
                    SiteShowcase::ember, ControlSize.LARGE,
                    List.of("ComicNeue-Regular.ttf", "ComicNeue-Bold.ttf")),
            new Dressing("mosaic-shipped-light", "The shipped light palette in Archivo Black",
                    Theme::limnLight, ControlSize.MEDIUM, List.of("ArchivoBlack-Regular.ttf")),
            new Dressing("mosaic-shipped-dark", "The shipped dark palette",
                    Theme::limn, ControlSize.MEDIUM, List.of()));

    /**
     * The tiles the home page's mosaic is cut from: one screen each, under a theme, a size step
     * or a typeface that exists nowhere else in the capture set.
     *
     * <p>Every tile is palette-invariant, and that is not an aesthetic choice: a tile pins its
     * own theme and ignores the one the capture hands it, so the light and dark passes would
     * render identical pixels into two files and the mosaic would carry the same tile twice.
     *
     * <p>They are deliberately further apart than anything the toolkit ships. A reader who sees
     * only the built-in palettes concludes that a Limn screen has one look; the whole purpose of
     * this set is to be evidence against that, so a tile that merely nudges a hue earns no place
     * here. Adding one means adding a capture; keep the set small enough that a person can take
     * it in at a glance.
     *
     * <p>Every tile is the SAME screen ({@link limn.demo.site.MosaicExample}, which exists for
     * this and for nothing else), and only the dressing changes. That is deliberate and it is
     * the claim: six crops of one layout, unrecognisable from each other. Using the site's other
     * screens here instead produced a mosaic that read as an album of unrelated applications, and
     * whichever screen appeared twice looked like a duplicated tile.
     */
    private static List<Entry> mosaicTiles() {
        List<Entry> entries = new ArrayList<>();
        for (Dressing dressing : DRESSINGS) {
            entries.add(new Entry(dressing.id(), dressing.title(), Locale.ENGLISH, true,
                    theme -> typeface(dressing.faces(),
                            () -> sized(mosaic(dressing.theme().get()), dressing.size()))));
        }
        return entries;
    }

    /**
     * Applies a size step to a built scene rather than to the process.
     *
     * <p>{@link ControlSize#setProcessDefault} would leak into every capture after this one:
     * the shots share a JVM, and the step a widget resolves is the one in force when it is
     * measured, not when this tile was built.
     */
    private static Scene sized(Scene scene, ControlSize size) {
        scene.setControlSize(size);
        return scene;
    }

    /**
     * Builds a scene in the faces named, and <b>leaves the family pinned</b>.
     *
     * <p>Restoring it here is the obvious mistake and it does not work: a family is resolved when
     * text is measured, which is frames after this returns, so putting the previous family back
     * captures the previous font. The capture driver resets the default family per shot instead,
     * which is where process-wide state already belongs; it does the same for the palette and
     * the locale.
     *
     * <p>The family name comes from {@link Fonts#load}, never from a literal: the name a font file
     * declares and the name of the file need not agree, and a name that resolves to nothing would
     * render the tile in the default face, an identical twin of another tile, published without a
     * word in the log. {@code load} throws when the file is missing, so the capture fails instead
     * of publishing that twin.
     *
     * <p>The paths are relative to the repository root, which is the working directory both
     * {@code :limn-demo:run} and {@code :limn-demo:captureGallery} set. The faces live under
     * {@code limn-demo/} rather than in the backend's resources because nothing published should
     * grow for the sake of a screenshot: {@code limn-demo} is a library nobody depends on.
     *
     * <p>Where a family ships more than one weight, load them all. A family registered with only
     * its regular weight renders a screen's bold labels in the fallback face, which reads as two
     * typefaces in one window.
     */
    private static Scene typeface(List<String> faces, java.util.function.Supplier<Scene> build) {
        String family = null;
        for (String face : faces) {
            family = Fonts.load(FACE_DIR.resolve(face));
        }
        // Null restores the bundled default rather than leaving the previous tile's face in
        // force: the shots share a JVM, and a dressing that names no face means Roboto, not
        // "whatever the tile before this one loaded".
        Fonts.setDefaultFamily(family);
        return build.get();
    }

    /** Magenta on deep violet, corners twice the toolkit's: the loudest tile in the set. */
    private static Theme vivid() {
        return Theme.limn().toBuilder()
                .name("Vivid")
                .background(Color.rgb(0x160B2E))
                .surface(Color.rgb(0x241145))
                .surfaceRaised(Color.rgb(0x33195F))
                .primary(Color.rgb(0xFF2D9B))
                .onPrimary(Color.rgb(0xFFFFFF))
                .text(Color.rgb(0xF4ECFF))
                .textMuted(Color.rgb(0xB49BE0))
                .outline(Color.rgb(0x00E5D0))
                .focusRing(Color.rgb(0xFFE566))
                .cornerScale(2f)
                .deriveAccentStates()
                .deriveDisabled()
                .build();
    }

    /** Ink on warm paper, corners nearly square, the opposite end from {@link #vivid()}. */
    private static Theme paper() {
        return Theme.limnLight().toBuilder()
                .name("Paper")
                .background(Color.rgb(0xF6EFE2))
                .surface(Color.rgb(0xFDF9F1))
                .surfaceRaised(Color.rgb(0xFFFFFF))
                .primary(Color.rgb(0x8A5A2B))
                .onPrimary(Color.rgb(0xFFF8EE))
                .text(Color.rgb(0x2E2418))
                .textMuted(Color.rgb(0x7A6A55))
                .outline(Color.rgb(0xD8C7AC))
                .focusRing(Color.rgb(0xB07C3C))
                .cornerScale(0.25f)
                .deriveAccentStates()
                .deriveDisabled()
                .build();
    }

    /** Teal on cool white, tight corners: the coldest tile, opposite {@link #ember()}. */
    private static Theme mint() {
        return Theme.limnLight().toBuilder()
                .name("Mint")
                .background(Color.rgb(0xEFF6F4))
                .surface(Color.rgb(0xFAFDFC))
                .surfaceRaised(Color.rgb(0xFFFFFF))
                .primary(Color.rgb(0x0E7C6B))
                .onPrimary(Color.rgb(0xF2FFFC))
                .text(Color.rgb(0x112623))
                .textMuted(Color.rgb(0x5D7C77))
                .outline(Color.rgb(0xBBD8D2))
                .focusRing(Color.rgb(0x14A08A))
                .cornerScale(0.5f)
                .deriveAccentStates()
                .deriveDisabled()
                .build();
    }

    /** Ember on near-black brown, generous corners, the warmest dark in the set. */
    private static Theme ember() {
        return Theme.limn().toBuilder()
                .name("Ember")
                .background(Color.rgb(0x1A1210))
                .surface(Color.rgb(0x261A16))
                .surfaceRaised(Color.rgb(0x33231D))
                .primary(Color.rgb(0xE8562F))
                .onPrimary(Color.rgb(0xFFF3EE))
                .text(Color.rgb(0xF2E3DC))
                .textMuted(Color.rgb(0xB29289))
                .outline(Color.rgb(0x4A322A))
                .focusRing(Color.rgb(0xFFB067))
                .cornerScale(1.5f)
                .deriveAccentStates()
                .deriveDisabled()
                .build();
    }

    /** Slate and amber: a dark theme that is not the shipped one, and reads as quiet. */
    private static Theme dusk() {
        return Theme.limn().toBuilder()
                .name("Dusk")
                .background(Color.rgb(0x11181C))
                .surface(Color.rgb(0x1A242A))
                .surfaceRaised(Color.rgb(0x233038))
                .primary(Color.rgb(0xE0A33C))
                .onPrimary(Color.rgb(0x1A1206))
                .text(Color.rgb(0xDCE6EA))
                .textMuted(Color.rgb(0x8DA2AC))
                .outline(Color.rgb(0x35474F))
                .focusRing(Color.rgb(0x6FD3C7))
                .cornerScale(0.75f)
                .deriveAccentStates()
                .deriveDisabled()
                .build();
    }

    /**
     * The palette is applied BEFORE the scene is built, not after: the form pins its error
     * message to {@code Theme.current().danger}, and a colour pinned that way is read once.
     * Every other capture here builds first because nothing in it copies a tone.
     */
    private static Scene form(Theme theme) {
        Theme.setCurrent(theme);
        return retheme(limn.demo.site.FormExample.scene(), theme);
    }

    private static Scene layout(Theme theme) {
        Theme.setCurrent(theme);
        return retheme(limn.demo.site.LayoutExample.scene(), theme);
    }

    /**
     * The theme editor screen. Themed first, like the form: the editor copies the palette it is
     * handed into its own working state as it builds, so a palette set afterwards edits the
     * previous one.
     */
    private static Scene editor(Theme theme) {
        Theme.setCurrent(theme);
        return retheme(limn.demo.site.ThemeEditorExample.scene(), theme);
    }

    /** The mosaic board, themed before it is built; see {@link #form} for why that order. */
    private static Scene mosaic(Theme theme) {
        Theme.setCurrent(theme);
        return retheme(limn.demo.site.MosaicExample.scene(), theme);
    }

    private static Scene controlSize(Theme theme) {
        Theme.setCurrent(theme);
        return retheme(limn.demo.site.ControlSizeExample.scene(), theme);
    }

    private static Scene kitchen(Theme theme) {
        return retheme(KitchenSinkScene.create(!theme.dark).scene(), theme);
    }

    private static Scene viewport(Theme theme) {
        Theme.setCurrent(theme);
        return retheme(limn.demo.site.Viewport3DExample.scene(), theme);
    }

    /**
     * Scenes pick their own light/dark palette while they build, so the palette wanted here is
     * applied afterwards, the same order {@code --theme} uses, and for the same reason. The
     * cleared background is copied out of the palette at construction and is the one thing a
     * relayout does not revisit, so it is set explicitly.
     */
    private static Scene retheme(Scene scene, Theme theme) {
        Theme.setCurrent(theme);
        scene.root().markNeedsLayout();
        scene.setBackground(theme.background);
        return scene;
    }
}
