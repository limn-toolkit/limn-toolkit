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
                + "one effect. Clear refracts at the rim (the shape's own distance field is the "
                + "surface, so the bend follows every corner) and splits the channels slightly, "
                + "which is what glass does to white light. Wash only moves saturation. Pixelate "
                + "is redaction: one sample per cell, nothing recoverable.")
                .setMuted(true).setWrap(true));

        Image picture = plate();

        Row panels = new Row();
        panels.gap(12).crossAlignment(Flex.CrossAlignment.START);
        panels.add(over(picture, new BackdropEffect.Clear(Color.WHITE.withAlpha(0.10f), 16f, 0.45f),
                "Clear", "refraction + dispersion"));
        panels.add(over(picture, new BackdropEffect.Wash(Color.BLACK.withAlpha(0.25f), 0f),
                "Wash", "grey, tinted dark"));
        panels.add(over(picture, new BackdropEffect.Pixelate(Color.TRANSPARENT, 9f),
                "Pixelate", "redaction, 9pt cells"));
        column.add(panels);

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
