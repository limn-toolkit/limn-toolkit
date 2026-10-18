package limn.demo;

import limn.components.BackdropPanel;
import limn.components.Button;
import limn.components.ImageView;
import limn.components.Label;
import limn.components.ScrollView;
import limn.components.SegmentedControl;
import limn.components.Slider;
import limn.components.Theme;
import limn.graphics.BackdropEffect;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.graphics.RoundRect;
import limn.i18n.I18nString;
import limn.i18n.NumberFormats;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;

/**
 * The Backdrop panel page: a toolbar of glass over a library that scrolls under it, a tinted panel
 * floating over a picture, and the effects one beside the other over the same busy plate, so the
 * difference between them is the only thing that varies. Each is a {@link BackdropPanel}, an
 * ordinary container whose background is a re-sampled copy of what the frame already drew
 * underneath it.
 *
 * <p>The pictures are generated in code rather than loaded, so the page renders identically on a
 * machine with no assets and a screenshot of it is worth comparing against another.
 */
final class GlassScene {

    /** What the page says, ready for translation. */
    static final class Text {
        static final I18nString TOOLBAR =
                new I18nString("showcase.glass.toolbar", "Toolbar over scrolling content");
        static final I18nString LIBRARY = new I18nString("showcase.glass.library", "Library");
        static final I18nString IMPORT = new I18nString("showcase.glass.import", "Import");
        static final I18nString NEW_ALBUM = new I18nString("showcase.glass.newAlbum", "New album");
        static final I18nString FLOATING = new I18nString("showcase.glass.floating", "Floating panel");
        static final I18nString TINT = new I18nString("showcase.glass.tint", "Tint");
        static final I18nString TINT_AMOUNT = new I18nString("showcase.glass.tintAmount", "Strength");
        static final I18nString COVER = new I18nString("showcase.glass.cover", "Coverage");
        static final I18nString OPACITY = new I18nString("showcase.glass.opacity", "Opacity");
        static final I18nString FADE = new I18nString("showcase.glass.fade", "Fade out and in");
        static final I18nString NONE = new I18nString("showcase.glass.none", "None");
        static final I18nString TEAL = new I18nString("showcase.glass.teal", "Teal");
        static final I18nString AMBER = new I18nString("showcase.glass.amber", "Amber");
        static final I18nString ROSE = new I18nString("showcase.glass.rose", "Rose");
        static final I18nString ALBUM_OF = new I18nString("showcase.glass.albumOf", "Album {0} of {1}");
        static final I18nString POSITION = new I18nString("showcase.glass.position", "Position");
        static final I18nString OPEN = new I18nString("showcase.glass.open", "Open");
        static final I18nString EFFECTS = new I18nString("showcase.glass.effects", "Effects");
        static final I18nString CLEAR = new I18nString("showcase.glass.clear", "Clear");
        static final I18nString WASH = new I18nString("showcase.glass.wash", "Wash");
        static final I18nString PIXELATE = new I18nString("showcase.glass.pixelate", "Pixelate");
        static final I18nString CRT = new I18nString("showcase.glass.crt", "CRT");
        static final I18nString STACKED = new I18nString("showcase.glass.stacked", "Stacked effects");
        static final I18nString GLASS = new I18nString("showcase.glass.glass", "Glass");
        static final I18nString GLASS_BLUR = new I18nString("showcase.glass.glassBlur", "Glass and blur");
        static final I18nString GLASS_BLUR_WASH =
                new I18nString("showcase.glass.glassBlurWash", "Glass, blur and wash");

        private Text() {
        }
    }

    /** The tints the floating panel offers, a fifth of the way toward each. */
    private static final Color[] TINTS = {null, new Color(0.10f, 0.55f, 0.60f, 1f),
            new Color(0.85f, 0.55f, 0.12f, 1f), new Color(0.80f, 0.25f, 0.45f, 1f)};

    private GlassScene() {
    }

    /** Standalone {@code --scene glass}. */
    static Scene create(boolean lightTheme) {
        Theme.setCurrent(lightTheme ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(Theme.current().background());
        return scene;
    }

    /** The subtree, reusable as a page of the showcase. */
    static Widget<?> content() {
        Column column = Page.column();

        // ---- how an application uses it: a bar over what scrolls, a panel over a picture
        column.add(Page.firstSection(Text.TOOLBAR));
        Row tools = Page.row(8, new Label(Text.LIBRARY).setRole(Label.Role.TITLE));
        tools.add(Expanded.spacer(1));
        tools.add(new Button(Text.IMPORT).setSecondary(true));
        tools.add(new Button(Text.NEW_ALBUM));
        BackdropPanel bar = BackdropPanel.frosted(Insets.symmetric(8, 12), tools);
        Widget<?> library = limn.demo.site.GlassExample.barOver(shelf(), bar);
        column.add(new SizedBox(SizedBox.UNSET, 280, library));
        // Opened a little way down, so that the first thing seen is content under the glass: set
        // before the first layout, it is where the first frame shows it.
        if (library.children().get(0) instanceof ScrollView shelfScroll) {
            shelfScroll.scrollTo(0, 64);
        }

        column.add(Page.section(Text.FLOATING));
        Column titles = new Column();
        titles.gap(2);
        titles.add(new Label("Coastline at dusk").setStrong(true));
        titles.add(Page.label(() -> Text.ALBUM_OF.format(4, 12)).setMuted(true));
        Slider position = new Slider(0, 100).setValue(35);
        position.setAccessibleName(Text.POSITION);
        Row playing = Page.row(12, titles, new SizedBox(180, SizedBox.UNSET, position),
                new Button(Text.OPEN).setSecondary(true));
        // Leaning toward a colour of the application's by a fifth, and covering less than glass
        // does by itself, since it is over a picture chosen for it; the bar above is the theme's
        // alone. The bottom inset evens out the room a line's descent already leaves under the
        // second title, so the text sits as far from the glass's lower edge as from its upper.
        BackdropPanel floating = limn.demo.site.GlassExample.tinted(
                new Padding(new Insets(0, 0, 3, 0), playing), TINTS[1]);
        SegmentedControl tint = new SegmentedControl(Text.NONE, Text.TEAL, Text.AMBER, Text.ROSE);
        tint.setAccessibleName(Text.TINT);
        tint.setSelectedIndex(1);
        // How far the glass leans toward the colour, shown and changeable: at a fifth a tint is a
        // hint that is easy to miss, so the page starts at two fifths and lets the viewer compare.
        Slider strength = new Slider(0, 100).setStep(5).setValue(40);
        strength.setAccessibleName(Text.TINT_AMOUNT);
        Label percent = Page.label(() -> NumberFormats.percent(0).apply(strength.value() / 100.0))
                .setMuted(true);
        Runnable applyTint = () -> floating.setTint(TINTS[tint.selectedIndex()], strength.value() / 100f);
        applyTint.run();
        tint.onSelect(index -> applyTint.run());
        strength.onChange(value -> {
            percent.setText(NumberFormats.percent(0).apply(value / 100.0));
            applyTint.run();
        });
        Row tintRow = Page.row(12, new Label(Text.TINT).setMuted(true).setLabelFor(tint), tint,
                new Label(Text.TINT_AMOUNT).setMuted(true).setLabelFor(strength),
                new SizedBox(160, SizedBox.UNSET, strength), percent);
        tintRow.add(Expanded.spacer(1));
        column.add(tintRow);
        // Coverage, not opacity: how much of its tone the glass lays over the picture; at none it only blurs, at all it
        // hides what is behind. The example starts at the 0.3 it was built with.
        Slider cover = new Slider(0, 100).setStep(5).setValue(30);
        cover.setAccessibleName(Text.COVER);
        Label coverPercent = Page.label(() -> NumberFormats.percent(0).apply(cover.value() / 100.0))
                .setMuted(true);
        floating.setCover(cover.value() / 100f);
        cover.onChange(value -> {
            coverPercent.setText(NumberFormats.percent(0).apply(value / 100.0));
            floating.setCover(value / 100f);
        });
        Row coverRow = Page.row(12, new Label(Text.COVER).setMuted(true).setLabelFor(cover),
                new SizedBox(160, SizedBox.UNSET, cover), coverPercent);
        coverRow.add(Expanded.spacer(1));
        column.add(coverRow);
        // Opacity is the whole panel's, the widget's own: the glass, its tone and the controls on it
        // fade as one picture over the picture behind, and the glass still reads that picture as if
        // the panel were opaque. The button fades it out and back to where the slider stands.
        Slider opacity = new Slider(0, 100).setStep(5).setValue(100);
        opacity.setAccessibleName(Text.OPACITY);
        Label opacityPercent = Page.label(() -> NumberFormats.percent(0).apply(opacity.value() / 100.0))
                .setMuted(true);
        opacity.onChange(value -> {
            opacityPercent.setText(NumberFormats.percent(0).apply(value / 100.0));
            floating.setOpacity(value / 100f);
        });
        Button fade = new Button(Text.FADE).setSecondary(true).onAction(() -> floating.fadeTo(0f, 0.6,
                () -> floating.fadeTo(opacity.value() / 100f, 0.6, null)));
        Row opacityRow = Page.row(12, new Label(Text.OPACITY).setMuted(true).setLabelFor(opacity),
                new SizedBox(160, SizedBox.UNSET, opacity), opacityPercent, fade);
        opacityRow.add(Expanded.spacer(1));
        column.add(opacityRow);
        column.add(new SizedBox(SizedBox.UNSET, 240, limn.demo.site.GlassExample.floatingOver(
                new ImageView(dusk()).setFit(ImageView.Fit.COVER), floating)));

        // ---- the effects themselves, one beside the other
        column.add(Page.section(Text.EFFECTS));
        Image picture = plate();

        // Two rows of two rather than one row of four: four 240pt plates and their gaps are
        // wider than this window, and a Row does not wrap. The alternative is a horizontal
        // scrollbar under a set whose whole point is comparing them side by side.
        Row top = new Row();
        top.gap(12).crossAlignment(Flex.CrossAlignment.START);
        top.add(over(picture, new BackdropEffect.Clear(Color.WHITE.withAlpha(0.08f), 22f, 0.4f), Text.CLEAR));
        top.add(over(picture, new BackdropEffect.Wash(Color.BLACK.withAlpha(0.25f), 0f), Text.WASH));
        column.add(top);

        Row bottom = new Row();
        bottom.gap(12).crossAlignment(Flex.CrossAlignment.START);
        bottom.add(over(picture, new BackdropEffect.Pixelate(Color.TRANSPARENT, 9f), Text.PIXELATE));
        // Full-bleed, unlike the three above, and that is not decoration: the tube's curvature and
        // vignette are fractions of the distance from the shape's OWN centre, so in a panel the
        // size of a caption they move a point or two and the effect reads as a stripe pattern
        // laid on the picture. A tube needs to be the screen to look like one.
        bottom.add(screen(picture, new BackdropEffect.Crt(Color.TRANSPARENT, 0.35f, 0.12f), Text.CRT));
        column.add(bottom);

        column.add(Page.section(Text.STACKED));
        Row stacked = new Row();
        stacked.gap(12).crossAlignment(Flex.CrossAlignment.START);
        BackdropEffect glass = new BackdropEffect.Clear(Color.WHITE.withAlpha(0.06f), 20f, 0.4f);
        stacked.add(stack(picture, Text.GLASS, glass));
        stacked.add(stack(picture, Text.GLASS_BLUR,
                glass,
                new BackdropEffect.Blur(Color.TRANSPARENT, 7f, BackdropEffect.Blur.Axis.X),
                new BackdropEffect.Blur(Color.TRANSPARENT, 7f, BackdropEffect.Blur.Axis.Y)));
        stacked.add(stack(picture, Text.GLASS_BLUR_WASH,
                glass,
                new BackdropEffect.Blur(Color.TRANSPARENT, 7f, BackdropEffect.Blur.Axis.X),
                new BackdropEffect.Blur(Color.TRANSPARENT, 7f, BackdropEffect.Blur.Axis.Y),
                new BackdropEffect.Wash(Color.TRANSPARENT, 0.35f, 0.18f)));
        column.add(stacked);

        return column; // the page scrolls it
    }

    /** The name of an effect, on the glass that shows it. */
    private static Label title(I18nString name) {
        return new Label(name).setRole(Label.Role.TITLE);
    }

    /**
     * The name of an effect on a panel larger than the name: in the panel's middle, as a panel
     * that hugs its name has it, and on two lines when one is not enough. At the start of the
     * panel, the CRT's name stood 88 pt left of the middle the Pixelate name beside it is in,
     * and pt-BR's "Vidro, desfoque e véu" was cut to "Vidro, desfoque e v…".
     */
    private static Label centredTitle(I18nString name) {
        return title(name).setAlign(Label.HAlign.CENTER, Label.VAlign.CENTER).setWrap(true);
    }

    /** One sample: the plate, with a panel of {@code effect} floating over its middle. */
    private static Widget<?> over(Image picture, BackdropEffect effect, I18nString name) {
        Stack stack = new Stack().alignment(Stack.Alignment.CENTER);
        stack.add(new ImageView(picture).setFit(ImageView.Fit.COVER));
        // The panel is added AFTER the picture: the effect samples what the frame has already
        // drawn, so a panel painted first would show the window background and nothing else.
        stack.add(new BackdropPanel(effect, Insets.all(14), title(name)));
        return new SizedBox(240, 180, stack);
    }

    /**
     * The same sample, with the panel filling the plate instead of hugging its title. Full bleed
     * and square-cornered: a tube is the screen, so an inset panel with round corners would be
     * showing the effect on a widget floating over the picture rather than on the picture. It is
     * also the case that exercises the shape clamp, since every displaced sample is then at the
     * shape's own edge.
     */
    private static Widget<?> screen(Image picture, BackdropEffect effect, I18nString name) {
        Stack stack = new Stack().alignment(Stack.Alignment.CENTER);
        stack.add(new ImageView(picture).setFit(ImageView.Fit.COVER));
        BackdropPanel panel = new BackdropPanel(effect, Insets.all(14), centredTitle(name));
        panel.setCornerRadius(0);
        stack.add(new SizedBox(240, 180, panel));
        return new SizedBox(240, 180, stack);
    }

    /**
     * A panel wearing a whole stack of effects, drawn in order. A margin of untouched picture is
     * left around it on purpose: it is what shows that the difference is the effect and not the
     * plate.
     */
    private static Widget<?> stack(Image picture, I18nString name, BackdropEffect... effects) {
        Stack layers = new Stack().alignment(Stack.Alignment.CENTER);
        layers.add(new ImageView(picture).setFit(ImageView.Fit.COVER));
        BackdropPanel panel = new BackdropPanel(effects[0], Insets.all(14), centredTitle(name));
        panel.setEffects(effects);
        layers.add(new SizedBox(216, 156, panel));
        return new SizedBox(240, 180, layers);
    }

    /** A picture cropped to fill its box, with the box's corners rounded off it. */
    private static final class Thumbnail extends Widget<Thumbnail> {
        private final Image image;

        Thumbnail(Image image, String name) {
            this.image = image;
            setAccessibleName(name);
            setAccessibleRole(limn.accessibility.Accessible.Role.IMAGE);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(132, 88);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            float scale = Math.max(width() / image.width(), height() / image.height());
            float w = image.width() * scale;
            float h = image.height() * scale;
            canvas.save();
            try {
                canvas.clipRoundRect(RoundRect.of(0, 0, width(), height(),
                        Theme.of(this).tokensFor(this).radiusMedium()));
                canvas.drawImage(image, (width() - w) / 2, (height() - h) / 2, w, h);
            } finally {
                canvas.restore();
            }
        }
    }

    // ------------------------------------------------------------ what the glass is over

    /** Rows of coloured tiles with a name under each: something busy enough to see through glass. */
    private static Widget<?> shelf() {
        String[] names = {"Harbour", "Orchard", "Foundry", "Salt flats", "Night market", "Ridge line",
                "Greenhouse", "Ferry", "Quarry", "Lantern street", "Tide pools", "Rail yard"};
        limn.scene.layout.Wrap tiles = new limn.scene.layout.Wrap();
        // Centred: whatever width is left over after the last whole tile goes to both sides.
        tiles.gap(12).runGap(14).mainAlignment(Flex.MainAlignment.CENTER);
        for (int i = 0; i < 24; i++) {
            Column tile = new Column();
            tile.gap(4);
            String name = names[i % names.length];
            tile.add(new Thumbnail(tile(i), name));
            tile.add(new Label(name));
            tiles.add(tile);
        }
        return new Padding(Insets.all(12), tiles);
    }

    /** One tile: two tones of a hue across a diagonal, a different hue for each index. */
    private static Image tile(int index) {
        int size = 48;
        byte[] pixels = new byte[size * size * 4];
        float hue = (index * 47 % 360) / 360f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float t = (x + y) / (float) (2 * size - 2);
                float turn = (hue + 0.08f * t) * 6;
                float saturation = 0.55f + 0.3f * t;
                float value = 0.95f - 0.45f * t;
                int at = (y * size + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    // Hue to one channel: a triangle wave a third of a turn apart for each.
                    float k = (5 - 2 * channel + turn) % 6;
                    float amount = value - value * saturation * Math.max(0, Math.min(Math.min(k, 4 - k), 1));
                    pixels[at + channel] = (byte) Color.toByte(amount);
                }
                pixels[at + 3] = (byte) 255;
            }
        }
        return new Image(size, size, pixels);
    }

    /** A sky going down to a dark sea, with a low sun: a picture for a panel to float over. */
    static Image dusk() {
        int w = 320;
        int h = 160;
        byte[] pixels = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = y / (float) (h - 1);
                float dx = (x - w * 0.5f) / w;
                float dy = (y - h * 0.52f) / h;
                float sun = (float) Math.exp(-(dx * dx * 60 + dy * dy * 220));
                float r;
                float g;
                float b;
                if (v < 0.55f) {            // the sky: violet above, orange at the horizon
                    float t = v / 0.55f;
                    r = 0.22f + 0.72f * t;
                    g = 0.16f + 0.38f * t;
                    b = 0.42f - 0.12f * t;
                } else {                    // the sea: dark, with bands the sun lights
                    float t = (v - 0.55f) / 0.45f;
                    float band = 0.5f + 0.5f * (float) Math.sin(y * 1.7f + x * 0.05f);
                    // The sun's path on the water, under it and widening toward the shore: what
                    // the panel floats over, so that the glass has light and colour to show.
                    float path = (float) Math.exp(-dx * dx * (70 - 40 * t)) * (0.35f + 0.65f * band);
                    r = 0.10f + 0.25f * (1 - t) * band + 0.80f * path;
                    g = 0.14f + 0.14f * (1 - t) * band + 0.45f * path;
                    b = 0.26f + 0.10f * band + 0.10f * path;
                }
                int at = (y * w + x) * 4;
                pixels[at] = (byte) Color.toByte(Math.min(1, r + sun));
                pixels[at + 1] = (byte) Color.toByte(Math.min(1, g + sun * 0.8f));
                pixels[at + 2] = (byte) Color.toByte(Math.min(1, b + sun * 0.5f));
                pixels[at + 3] = (byte) 255;
            }
        }
        return new Image(w, h, pixels);
    }

    // ------------------------------------------------------------ the backdrop

    private static final int PLATE = 256;

    /**
     * A deliberately busy plate: broad colour bands crossed by thin high-contrast stripes. The
     * bands show refraction (they bend at the rim), the stripes show pixelation (they vanish into
     * the cells) and the saturation ramp shows the wash.
     */
    private static Image plate() {
        byte[] pixels = new byte[PLATE * PLATE * 4];
        for (int y = 0; y < PLATE; y++) {
            for (int x = 0; x < PLATE; x++) {
                int at = (y * PLATE + x) * 4;
                float u = x / (float) (PLATE - 1);
                float v = y / (float) (PLATE - 1);
                float r = 0.15f + 0.85f * u;
                float g = 0.30f + 0.55f * (1 - v);
                float b = 0.45f + 0.50f * (float) Math.abs(Math.sin((u + v) * 6));
                if (((x / 7) + (y / 23)) % 5 == 0) {
                    r = 1 - r;   // thin stripes: the detail a redaction has to destroy
                    g = 1 - g;
                    b = 1 - b;
                }
                pixels[at] = (byte) Color.toByte(r);
                pixels[at + 1] = (byte) Color.toByte(g);
                pixels[at + 2] = (byte) Color.toByte(b);
                pixels[at + 3] = (byte) 255;
            }
        }
        return new Image(PLATE, PLATE, pixels);
    }
}
