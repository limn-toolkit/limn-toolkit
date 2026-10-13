package limn.demo;

import limn.components.BackdropPanel;
import limn.components.ImageView;
import limn.components.Label;
import limn.components.ScrollView;
import limn.components.Theme;
import limn.graphics.BackdropEffect;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;

/**
 * Backdrop effects: three panels over the same busy picture, so the difference between them is the
 * only thing that varies. Each is a {@link BackdropPanel}, an ordinary container whose background
 * is a re-sampled copy of what the frame already drew underneath it.
 *
 * <p>The picture is generated in code rather than loaded, so the scene renders identically on a
 * machine with no assets and a screenshot of it is worth comparing against another.
 */
final class GlassScene {

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
        Column column = new Column();
        column.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);

        // ---- how an application uses it: a bar over what scrolls, a panel over a picture
        column.add(new Label("A bar that stays while the content scrolls under it").setStrong(true));
        Row tools = new Row();
        tools.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
        tools.add(new Label("Library").setFont(Theme.current().title()));
        tools.add(limn.scene.layout.Expanded.spacer(1));
        tools.add(new limn.components.Button("Import").setSecondary(true));
        tools.add(new limn.components.Button("New album"));
        BackdropPanel bar = limn.demo.site.GlassExample.frosted(Insets.symmetric(8, 12), tools);
        Widget<?> library = limn.demo.site.GlassExample.barOver(shelf(), bar);
        column.add(new SizedBox(SizedBox.UNSET, 280, library));
        // Opened a little way down, so that the first thing seen is content under the glass.
        if (library.children().get(0) instanceof ScrollView shelfScroll) {
            limn.concurrent.Ui.postDelayed(() -> shelfScroll.scrollTo(0, 64), 400);
        }

        column.add(new Label("A panel that floats over a picture").setStrong(true));
        Row playing = new Row();
        playing.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        Column titles = new Column();
        titles.gap(2);
        titles.add(new Label("Coastline at dusk").setStrong(true));
        titles.add(new Label("Album 4 of 12").setMuted(true));
        playing.add(titles);
        playing.add(new SizedBox(180, SizedBox.UNSET, new limn.components.Slider(0, 100).setValue(35)));
        playing.add(new limn.components.Button("Open"));
        BackdropPanel floating = limn.demo.site.GlassExample.frosted(Insets.symmetric(10, 14), playing);
        column.add(new SizedBox(SizedBox.UNSET, 240, limn.demo.site.GlassExample.floatingOver(
                new ImageView(dusk()).setFit(ImageView.Fit.COVER), floating)));
        // An effect holds the colour it was given: when the palette changes, the two panels are
        // frosted again in the new one. The page lives as long as the window does.
        Theme.observeChanges(() -> {
            limn.demo.site.GlassExample.frost(bar, Theme.current());
            limn.demo.site.GlassExample.frost(floating, Theme.current());
        });

        // ---- the effects themselves, one beside the other
        column.add(new Label("The effects, one by one").setStrong(true));
        column.add(new Label("Each panel samples what is behind it and applies one effect.")
                .setMuted(true).setWrap(true));

        Image picture = plate();

        // Two rows of two rather than one row of four: four 240pt plates and their gaps are
        // wider than this window, and a Row does not wrap. The alternative is a horizontal
        // scrollbar under a set whose whole point is comparing them side by side.
        Row top = new Row();
        top.gap(12).crossAlignment(Flex.CrossAlignment.START);
        top.add(over(picture, new BackdropEffect.Clear(Color.WHITE.withAlpha(0.08f), 22f, 0.4f),
                "Clear", "a body, refracting"));
        top.add(over(picture, new BackdropEffect.Wash(Color.BLACK.withAlpha(0.25f), 0f),
                "Wash", "grey, tinted dark"));
        column.add(top);

        Row bottom = new Row();
        bottom.gap(12).crossAlignment(Flex.CrossAlignment.START);
        bottom.add(over(picture, new BackdropEffect.Pixelate(Color.TRANSPARENT, 9f),
                "Pixelate", "redaction, 9pt cells"));
        // Full-bleed, unlike the three above, and that is not decoration: Crt's curvature and
        // vignette are fractions of the distance from the shape's OWN centre, so in a panel the
        // size of a caption they move a point or two and the effect reads as a stripe pattern
        // laid on the picture. A tube needs to be the screen to look like one.
        bottom.add(screen(picture, new BackdropEffect.Crt(Color.TRANSPARENT, 0.35f, 0.12f),
                "Crt", "a tube: grille + scan + bulge"));
        column.add(bottom);

        column.add(new Label("Effects stacked: each reads what the one before it wrote.")
                .setMuted(true).setWrap(true));

        Row stacked = new Row();
        stacked.gap(12).crossAlignment(Flex.CrossAlignment.START);
        BackdropEffect glass = new BackdropEffect.Clear(Color.WHITE.withAlpha(0.06f), 20f, 0.4f);
        stacked.add(stack(picture, "Glass", "refracting only", glass));
        stacked.add(stack(picture, "Glass + blur", "frosted: two crossed passes",
                glass,
                new BackdropEffect.Blur(Color.TRANSPARENT, 7f, BackdropEffect.Blur.Axis.X),
                new BackdropEffect.Blur(Color.TRANSPARENT, 7f, BackdropEffect.Blur.Axis.Y)));
        stacked.add(stack(picture, "Glass + blur + wash", "and drained, and lifted",
                glass,
                new BackdropEffect.Blur(Color.TRANSPARENT, 7f, BackdropEffect.Blur.Axis.X),
                new BackdropEffect.Blur(Color.TRANSPARENT, 7f, BackdropEffect.Blur.Axis.Y),
                new BackdropEffect.Wash(Color.TRANSPARENT, 0.35f, 0.18f)));
        column.add(stacked);

        return new ScrollView(column);
    }

    /** One labelled sample: the plate, with a panel of {@code effect} floating over its middle. */
    private static Widget<?> over(Image picture, BackdropEffect effect, String title, String caption) {
        Stack stack = new Stack().alignment(Stack.Alignment.CENTER);
        stack.add(new ImageView(picture).setFit(ImageView.Fit.COVER));
        // The panel is added AFTER the picture: the effect samples what the frame has already
        // drawn, so a panel painted first would show the window background and nothing else.
        stack.add(new BackdropPanel(effect, Insets.all(14),
                new Label(title).setFont(Theme.current().title())));

        Column column = new Column();
        column.gap(6);
        column.add(new SizedBox(240, 180, stack));
        column.add(new Label(caption).setMuted(true));
        return column;
    }

    /**
     * The same sample, with the panel filling the plate instead of hugging its caption. A margin
     * of untouched picture is left around it on purpose: it is what shows that the difference is
     * the effect and not the plate.
     */
    private static Widget<?> screen(Image picture, BackdropEffect effect, String title,
            String caption) {
        Stack stack = new Stack().alignment(Stack.Alignment.CENTER);
        stack.add(new ImageView(picture).setFit(ImageView.Fit.COVER));
        // Full bleed and square-cornered: a tube is the screen, so an inset panel with round
        // corners would be showing the effect on a widget floating over the picture rather
        // than on the picture. It is also the case that exercises the shape clamp, since
        // every displaced sample is then at the shape's own edge.
        BackdropPanel panel = new BackdropPanel(effect, Insets.all(14),
                new Label(title).setFont(Theme.current().title()));
        panel.setCornerRadius(0);
        stack.add(new SizedBox(240, 180, panel));

        Column column = new Column();
        column.gap(6);
        column.add(new SizedBox(240, 180, stack));
        column.add(new Label(caption).setMuted(true));
        return column;
    }

    /** A full-bleed panel wearing a whole stack of effects, drawn in order. */
    private static Widget<?> stack(Image picture, String title, String caption,
            BackdropEffect... effects) {
        Stack layers = new Stack().alignment(Stack.Alignment.CENTER);
        layers.add(new ImageView(picture).setFit(ImageView.Fit.COVER));
        BackdropPanel panel = new BackdropPanel(effects[0], Insets.all(14),
                new Label(title).setFont(Theme.current().title()));
        panel.setEffects(effects);
        layers.add(new SizedBox(216, 156, panel));

        Column column = new Column();
        column.gap(6);
        column.add(new SizedBox(240, 180, layers));
        column.add(new Label(caption).setMuted(true));
        return column;
    }

    // ------------------------------------------------------------ what the glass is over

    /** Rows of coloured tiles with a name under each: something busy enough to see through glass. */
    private static Widget<?> shelf() {
        String[] names = {"Harbour", "Orchard", "Foundry", "Salt flats", "Night market", "Ridge line",
                "Greenhouse", "Ferry", "Quarry", "Lantern street", "Tide pools", "Rail yard"};
        limn.scene.layout.Wrap tiles = new limn.scene.layout.Wrap();
        tiles.gap(12).runGap(14);
        for (int i = 0; i < 24; i++) {
            Column tile = new Column();
            tile.gap(4);
            tile.add(new SizedBox(132, 88, new ImageView(tile(i)).setFit(ImageView.Fit.COVER)));
            tile.add(new Label(names[i % names.length]));
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
    private static Image dusk() {
        int w = 320;
        int h = 160;
        byte[] pixels = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = y / (float) (h - 1);
                float dx = (x - w * 0.68f) / w;
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
                    r = 0.10f + 0.25f * (1 - t) * band;
                    g = 0.14f + 0.14f * (1 - t) * band;
                    b = 0.26f + 0.10f * band;
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
