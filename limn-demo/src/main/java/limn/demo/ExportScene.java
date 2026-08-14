package limn.demo;

import limn.components.Button;
import limn.components.ImageView;
import limn.components.Label;
import limn.components.ScrollView;
import limn.components.Theme;
import limn.components.Viewport3D;
import limn.graphics.Image;
import limn.graphics.ImageFormat;
import limn.graphics.Images;
import limn.render3d.Graphics3D;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

/**
 * Image export: an {@link Image} encoded to PNG bytes and decoded straight back, side by side with
 * the original, plus the two ways of getting pixels off the GPU to feed it: a window capture and a
 * 3D viewport capture.
 *
 * <p>The round trip is the demo on purpose. It is visible on screen, it writes to nobody's disk,
 * and the pair of pictures is the assertion: an encoder that dropped alpha, flipped the rows or
 * premultiplied on the way out would be obvious here and nowhere else.
 */
final class ExportScene {

    private ExportScene() {
    }

    /** Standalone {@code --scene export}. */
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

        column.add(new Label("PNG round trip").setFont(Theme.current().title));
        column.add(new Label("The left picture is drawn in Java, pixel by pixel. It is encoded to "
                + "PNG by the toolkit's own encoder (no backend, no native library) and decoded "
                + "back by the backend's stb_image. The two must be indistinguishable: the checker "
                + "shows through the transparent half, and the top row is the top row.")
                .setMuted(true).setWrap(true));

        Image chart = chart();
        ImageView source = new ImageView(chart).setFit(ImageView.Fit.CONTAIN);
        ImageView decoded = new ImageView(chart).setFit(ImageView.Fit.CONTAIN);
        Label status = new Label("").setMuted(true).setWrap(true);

        Row pair = new Row();
        pair.gap(16).crossAlignment(Flex.CrossAlignment.START);
        pair.add(labelled("Source Image", source));
        pair.add(labelled("Encoded → decoded", decoded));
        column.add(pair);
        column.add(status);

        roundTrip(chart, source, decoded, status, "the generated chart");

        Row buttons = new Row();
        buttons.gap(8);
        Button again = new Button("Round-trip the chart");
        again.onAction(() -> roundTrip(chart, source, decoded, status, "the generated chart"));
        buttons.add(again);

        Button captureWindow = new Button("Capture this window").setSecondary(true);
        captureWindow.onAction(() -> {
            Scene scene = captureWindow.scene();
            if (scene == null || scene.window() == null) {
                status.setText("No window to capture (in-scene demo).");
                return;
            }
            // Deferred to the next frame, post-flush and pre-swap: what the window is showing,
            // including this button's own pressed state.
            scene.window().captureNextFrame(
                    captured -> roundTrip(captured, source, decoded, status, "the window"));
        });
        buttons.add(captureWindow);
        column.add(buttons);

        if (Graphics3D.isAvailable()) {
            column.add(new Label("From the GPU").setFont(Theme.current().title));
            column.add(new Label("A 3D viewport renders into an offscreen target whose contents are "
                    + "scene-referred linear light. Capturing it reads back the display-referred "
                    + "picture (exposure, tonemap and sRGB encode applied once, exactly as the "
                    + "composite applies them), which is why the capture matches what you see "
                    + "rather than a washed-out version of it.")
                    .setMuted(true).setWrap(true));
            Viewport3D viewport = new Viewport3D();
            viewport.setPreferredSize(260, 170);
            // In a Row: the column stretches its children, and a stretched viewport would render
            // (and capture) at the whole panel's width.
            Row viewportRow = new Row();
            viewportRow.crossAlignment(Flex.CrossAlignment.START);
            viewportRow.add(new SizedBox(260, 170, viewport));
            column.add(viewportRow);
            Button capture = new Button("Capture the viewport").setSecondary(true);
            capture.onAction(() -> viewport.captureNext(
                    captured -> roundTrip(captured, source, decoded, status, "the 3D viewport")));
            column.add(capture);
        }

        return new ScrollView(column);
    }

    private static Widget labelled(String caption, ImageView view) {
        Column column = new Column();
        column.gap(6);
        column.add(new Label(caption).setMuted(true));
        column.add(new SizedBox(220, 150, view));
        return column;
    }

    /** Encodes, decodes, shows both, and says what the trip cost and whether anything changed. */
    private static void roundTrip(Image original, ImageView sourceView, ImageView decodedView,
                                  Label status, String what) {
        long startedAt = System.nanoTime();
        byte[] file = Images.encode(original, ImageFormat.PNG);
        float encodeMs = (System.nanoTime() - startedAt) / 1_000_000f;

        sourceView.setImage(original);
        String text = String.format(java.util.Locale.ROOT,
                "%s: %d×%d, %,d bytes of PNG (%.0f%% of the raw RGBA), "
                        + "encoded in %.1f ms. Re-encoding gives %s bytes.",
                what, original.width(), original.height(), file.length,
                100f * file.length / (original.width() * original.height() * 4f), encodeMs,
                java.util.Arrays.equals(file, Images.encode(original, ImageFormat.PNG))
                        ? "identical" : "DIFFERENT");

        if (!Images.isDecoderInstalled()) {
            status.setText(text + " No decoder installed, so there is nothing to decode it back.");
            return;
        }
        Image back = Images.decode(file);
        decodedView.setImage(back);
        status.setText(text + " Decoded back: " + describeDifference(original, back));
    }

    private static String describeDifference(Image original, Image decoded) {
        if (original.width() != decoded.width() || original.height() != decoded.height()) {
            return "the size changed, which it must not have.";
        }
        int worst = 0;
        byte[] a = original.pixels();
        byte[] b = decoded.pixels();
        for (int i = 0; i < a.length; i++) {
            worst = Math.max(worst, Math.abs((a[i] & 0xFF) - (b[i] & 0xFF)));
        }
        return worst == 0
                ? "every channel of every pixel came back unchanged."
                : "worst channel difference " + worst + "; PNG is lossless, so this is a bug.";
    }

    // ------------------------------------------------------- the source picture

    private static final int CHART_WIDTH = 220;
    private static final int CHART_HEIGHT = 150;

    /**
     * A picture drawn in code: opaque bars over a checkerboard on the left, the same bars fading to
     * fully transparent on the right, and a marked top-left corner. Every trap the encoder can fall
     * into changes it visibly: alpha, row order, channel order.
     */
    private static Image chart() {
        byte[] pixels = new byte[CHART_WIDTH * CHART_HEIGHT * 4];
        int[] bars = {96, 132, 60, 148, 110, 34, 126, 88};
        int barWidth = CHART_WIDTH / bars.length;
        for (int y = 0; y < CHART_HEIGHT; y++) {
            for (int x = 0; x < CHART_WIDTH; x++) {
                int at = (y * CHART_WIDTH + x) * 4;
                boolean light = ((x / 10) + (y / 10)) % 2 == 0;
                int red = light ? 0x30 : 0x22;
                int green = light ? 0x36 : 0x28;
                int blue = light ? 0x42 : 0x33;
                int bar = Math.min(bars.length - 1, x / barWidth);
                if (CHART_HEIGHT - y <= bars[bar] && x % barWidth < barWidth - 3) {
                    float t = bar / (float) (bars.length - 1);
                    red = (int) (0x36 + t * 0xC0);
                    green = (int) (0xC8 - t * 0x50);
                    blue = (int) (0xB0 - t * 0x30);
                }
                pixels[at] = (byte) red;
                pixels[at + 1] = (byte) green;
                pixels[at + 2] = (byte) blue;
                // Opaque on the left, fully transparent at the right edge: straight alpha keeps the
                // colour of a transparent pixel, and a premultiplying encoder would grey it out.
                pixels[at + 3] = (byte) (255 - (x * 255 / (CHART_WIDTH - 1)) * 3 / 4);
            }
        }
        // Marked corner: two rows of solid red at the very top, so a vertical flip is unmissable.
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < CHART_WIDTH; x++) {
                int at = (y * CHART_WIDTH + x) * 4;
                pixels[at] = (byte) 0xE0;
                pixels[at + 1] = 0x20;
                pixels[at + 2] = 0x30;
                pixels[at + 3] = (byte) 0xFF;
            }
        }
        return new Image(CHART_WIDTH, CHART_HEIGHT, pixels);
    }
}
