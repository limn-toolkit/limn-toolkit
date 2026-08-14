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
        scene.setBackground(Theme.current().background);
        return scene;
    }

    /** The subtree, reusable as a kitchen-sink tab. */
    static Widget content() {
        Column column = new Column();
        column.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);

        column.add(new Label("Backdrop effects").setFont(Theme.current().title));
        column.add(new Label("Each panel below samples the picture behind it and puts it through "
                + "one effect. Clear gives the shape a body: a rounded rim whose width is also its "
                + "optical depth, refracting by Snell's law, so the flat middle bends nothing and "
                + "the last fraction of the bevel bends hard, the way real glass does, and the "
                + "channels split by a fringe. Wash moves saturation and lifts. Pixelate "
                + "is redaction: one sample per cell, nothing recoverable. Crt is a tube: an "
                + "aperture grille of RGB triads, a scan whose bright lines swell the way a "
                + "phosphor does, a bulged face and a falloff at the corners. Unlike Pixelate it "
                + "anchors all of that to the SHAPE rather than to the framebuffer, because a "
                + "tube carries its own curve and its own lines wherever it is put.")
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

        column.add(new Label("Stacked, which is how these compose: each pass reads the "
                + "framebuffer the one before it wrote, so a frosted pane is built out of the "
                + "pieces rather than out of one variant with four parameters. It is also what "
                + "makes the blur affordable, since a separable blur IS two passes: across, then "
                + "down, at 2r samples instead of r squared.")
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

        column.add(new Label("A panel costs one batch break and one copy of its own bounds. Over "
                + "content that repaints anyway (video, a viewport), that is the whole cost. With "
                + "partial rendering on, a panel over content that changes without invalidating it "
                + "keeps showing the older backdrop; that is the known limit of this first step.")
                .setMuted(true).setWrap(true));

        return new ScrollView(column);
    }

    /** One labelled sample: the plate, with a panel of {@code effect} floating over its middle. */
    private static Widget over(Image picture, BackdropEffect effect, String title, String caption) {
        Stack stack = new Stack().alignment(Stack.Alignment.CENTER);
        stack.add(new ImageView(picture).setFit(ImageView.Fit.COVER));
        // The panel is added AFTER the picture: the effect samples what the frame has already
        // drawn, so a panel painted first would show the window background and nothing else.
        stack.add(new BackdropPanel(effect, Insets.all(14),
                new Label(title).setFont(Theme.current().title)));

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
    private static Widget screen(Image picture, BackdropEffect effect, String title,
            String caption) {
        Stack stack = new Stack().alignment(Stack.Alignment.CENTER);
        stack.add(new ImageView(picture).setFit(ImageView.Fit.COVER));
        // Full bleed and square-cornered: a tube is the screen, so an inset panel with round
        // corners would be showing the effect on a widget floating over the picture rather
        // than on the picture. It is also the case that exercises the shape clamp, since
        // every displaced sample is then at the shape's own edge.
        BackdropPanel panel = new BackdropPanel(effect, Insets.all(14),
                new Label(title).setFont(Theme.current().title));
        panel.setCornerRadius(0);
        stack.add(new SizedBox(240, 180, panel));

        Column column = new Column();
        column.gap(6);
        column.add(new SizedBox(240, 180, stack));
        column.add(new Label(caption).setMuted(true));
        return column;
    }

    /** A full-bleed panel wearing a whole stack of effects, drawn in order. */
    private static Widget stack(Image picture, String title, String caption,
            BackdropEffect... effects) {
        Stack layers = new Stack().alignment(Stack.Alignment.CENTER);
        layers.add(new ImageView(picture).setFit(ImageView.Fit.COVER));
        BackdropPanel panel = new BackdropPanel(effects[0], Insets.all(14),
                new Label(title).setFont(Theme.current().title));
        panel.setEffects(effects);
        layers.add(new SizedBox(216, 156, panel));

        Column column = new Column();
        column.gap(6);
        column.add(new SizedBox(240, 180, layers));
        column.add(new Label(caption).setMuted(true));
        return column;
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
                pixels[at] = (byte) Math.round(r * 255);
                pixels[at + 1] = (byte) Math.round(g * 255);
                pixels[at + 2] = (byte) Math.round(b * 255);
                pixels[at + 3] = (byte) 255;
            }
        }
        return new Image(PLATE, PLATE, pixels);
    }
}
