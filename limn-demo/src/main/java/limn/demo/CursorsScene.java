package limn.demo;

import limn.animation.Transition;
import limn.backend.Cursor;
import limn.backend.ImageCursor;
import limn.backend.PointerMode;
import limn.graphics.Image;
import limn.input.Keys;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.ScrollView;
import limn.components.TextField;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.TextMetrics;
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

import java.util.List;

/**
 * Cursor showcase: a grid of tiles, each requesting a different mouse cursor on
 * hover (arrow, hand, text, crosshair, the four resize shapes, move, not-allowed),
 * plus the real controls whose default cursor is baked in (buttons/checkbox/combo
 * → hand, text field → I-beam; a disabled button falls back to the arrow). Every
 * tile is just a plain {@link Widget} calling {@link Widget#setCursor}, the same
 * one line any widget can use to customize its cursor.
 */
final class CursorsScene {

    private CursorsScene() {
    }

    /** Standalone {@code --scene cursors}. */
    static Scene create() {
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    /** The subtree, reusable as a kitchen-sink tab. */
    static Widget content() {
        Column column = new Column();
        column.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);

        column.add(heading("Cursors on hover"));
        column.add(new Label("Hover each tile: any Widget can request a cursor "
                + "with widget.setCursor(Cursor.X). Buttons/checkbox/combo come with the hand; text "
                + "fields with the I-beam.").setMuted(true).setWrap(true));

        column.add(cursorRow(
                new CursorTile(Cursor.DEFAULT, "Default (arrow)"),
                new CursorTile(Cursor.POINTER, "Pointer (hand)"),
                new CursorTile(Cursor.TEXT, "Text (I-beam)"),
                new CursorTile(Cursor.CROSSHAIR, "Crosshair"),
                new CursorTile(Cursor.NOT_ALLOWED, "Not allowed")));
        column.add(cursorRow(
                new CursorTile(Cursor.RESIZE_EW, "Resize horiz."),
                new CursorTile(Cursor.RESIZE_NS, "Resize vert."),
                new CursorTile(Cursor.RESIZE_NESW, "Resize diag. /"),
                new CursorTile(Cursor.RESIZE_NWSE, "Resize diag. \\"),
                new CursorTile(Cursor.MOVE, "Move")));

        column.add(heading("Custom image cursors"));
        column.add(new Label("A widget can also request a cursor built from any Image "
                + "with widget.setImageCursor(new ImageCursor(image, hotspotX, hotspotY)); "
                + "these two are rasterized procedurally at startup.")
                .setMuted(true).setWrap(true));
        column.add(cursorRow(
                new ImageCursorTile(crosshairCursor(), "Crosshair (image)"),
                new ImageCursorTile(ringCursor(), "Brush ring (image)")));

        column.add(heading("Pointer modes"));
        column.add(new Label("Window-level pointer states via window.setPointerMode(...): "
                + "HIDDEN hides the cursor while it is over the tile. RELATIVE captures it: "
                + "the cursor disappears, motion arrives as unbounded deltas (MOTION events), "
                + "and no screen edge ever stops it.").setMuted(true).setWrap(true));
        Row modes = new Row();
        modes.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
        modes.add(Expanded.of(new HiddenTile(), 1));
        modes.add(Expanded.of(new CaptureTile(), 2));
        column.add(modes);

        column.add(heading("Default component cursors"));
        column.add(new Label("Components set their own cursor; a disabled control "
                + "is not reached by hover, so it falls back to the arrow automatically.")
                .setMuted(true).setWrap(true));

        Button disabled = new Button("Disabled");
        disabled.setEnabled(false);
        Row controls = new Row();
        controls.gap(16).crossAlignment(Flex.CrossAlignment.START);
        controls.add(labelled("Button", new Button("Save")));
        controls.add(labelled("Checkbox", new Checkbox(Checkbox.Variant.BOX, "I agree")));
        controls.add(labelled("ComboBox", new ComboBox(List.of("One", "Two", "Three"))));
        controls.add(labelled("Field (I-beam)", new TextField().setPreferredWidth(150)
                .setPlaceholder("Type…")));
        controls.add(labelled("Disabled (arrow)", disabled));
        column.add(controls);

        return new ScrollView(column);
    }

    private static Label heading(String text) {
        return new Label(text).setFont(Theme.current().title);
    }

    private static Widget labelled(String caption, Widget content) {
        Column column = new Column();
        column.gap(6).crossAlignment(Flex.CrossAlignment.START);
        column.add(content);
        column.add(new Label(caption).setMuted(true));
        return column;
    }

    /** A row of equal-width cursor tiles. */
    private static Row cursorRow(Widget... tiles) {
        Row row = new Row();
        row.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
        for (Widget tile : tiles) {
            row.add(Expanded.of(tile, 1));
        }
        return row;
    }

    // ------------------------------------------------ procedural cursor images
    // Rasterized into RGBA bytes at startup: no font/AWT machinery, same idea
    // as the cube gadget's 7-segment atlas. 32 px with the hotspot at center.

    private static final int CUR = 32;

    /** White crosshair with a dark outline (visible on any background). */
    private static ImageCursor crosshairCursor() {
        byte[] px = new byte[CUR * CUR * 4];
        int c = CUR / 2;
        for (int i = 0; i < CUR; i++) {
            // Outline first (3px arms), then the white core over it.
            for (int t = -1; t <= 1; t++) {
                set(px, i, c + t, 0x22, 0x22, 0x26, 255);
                set(px, c + t, i, 0x22, 0x22, 0x26, 255);
            }
        }
        for (int i = 2; i < CUR - 2; i++) {
            if (Math.abs(i - c) > 2) { // open center: precision aiming
                set(px, i, c, 0xFF, 0xFF, 0xFF, 255);
                set(px, c, i, 0xFF, 0xFF, 0xFF, 255);
            }
        }
        return new ImageCursor(new Image(CUR, CUR, px), c, c);
    }

    /** Brush-style ring: a circle outline, like a paint tool's size preview. */
    private static ImageCursor ringCursor() {
        byte[] px = new byte[CUR * CUR * 4];
        float c = (CUR - 1) / 2f;
        float radius = 11f;
        for (int y = 0; y < CUR; y++) {
            for (int x = 0; x < CUR; x++) {
                float d = (float) Math.hypot(x - c, y - c) - radius;
                float ring = Math.abs(d);
                if (ring < 2.4f) {
                    // Dark ring with a soft edge; brighter inner rim.
                    int alpha = (int) (255 * Math.max(0, 1 - (ring - 1.4f)));
                    boolean inner = d < 0;
                    int v = inner ? 0xFF : 0x22;
                    set(px, x, y, v, v, v, Math.min(255, alpha));
                }
            }
        }
        int center = CUR / 2;
        set(px, center, center, 0xFF, 0xFF, 0xFF, 255); // center dot
        return new ImageCursor(new Image(CUR, CUR, px), center, center);
    }

    private static void set(byte[] px, int x, int y, int r, int g, int b, int a) {
        if (x < 0 || y < 0 || x >= CUR || y >= CUR) {
            return;
        }
        int i = (y * CUR + x) * 4;
        px[i] = (byte) r;
        px[i + 1] = (byte) g;
        px[i + 2] = (byte) b;
        px[i + 3] = (byte) a;
    }

    /** A tile showing a custom image cursor while hovered. */
    private static final class ImageCursorTile extends Widget {
        private final String label;
        private final Transition hover =
                new Transition(this).duration(Theme.current().animHover).easing(Theme.current().animEasing);

        ImageCursorTile(ImageCursor cursor, String label) {
            this.label = label;
            setImageCursor(cursor); // one line, same as the standard shapes
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 60);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            Color fill = theme.surface.lerp(theme.surfaceRaised, hover.value());
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(), fill);
            canvas.drawRoundRect(0.5f, 0.5f, width() - 1, height() - 1, theme.tokensFor(this).radiusMedium(),
                    1, theme.outline.lerp(theme.primaryHover, hover.value()));
            Font font = theme.body;
            TextMetrics m = textRuler().measure(label, font);
            canvas.drawText(label, (width() - m.width()) / 2,
                    (height() - m.height()) / 2 + m.ascent(), font, theme.text);
        }

        @Override
        protected void onMouseEvent(limn.scene.event.MouseEvent event) {
            switch (event.type()) {
                case ENTER -> hover.to(1);
                case EXIT -> hover.to(0);
                default -> {
                }
            }
        }
    }

    /** Hides the OS cursor while the pointer is over this tile (HIDDEN mode). */
    private static final class HiddenTile extends Widget {
        private boolean inside;

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 110);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(),
                    inside ? theme.surfaceRaised : theme.surface);
            canvas.drawRoundRect(0.5f, 0.5f, width() - 1, height() - 1, theme.tokensFor(this).radiusMedium(),
                    1, inside ? theme.primaryHover : theme.outline);
            String text = inside ? "Cursor hidden; move out to restore" : "Hover to hide the cursor";
            Font font = theme.body;
            TextMetrics m = textRuler().measure(text, font);
            canvas.drawText(text, (width() - m.width()) / 2,
                    (height() - m.height()) / 2 + m.ascent(), font,
                    inside ? theme.text : theme.textMuted);
        }

        /** Pointer mode is window state, so it is restored through the live scene,
         * which still answers during onDetached, where NORMAL has to be put back. */
        private void setMode(PointerMode mode) {
            if (scene() != null && scene().window() != null) {
                scene().window().setPointerMode(mode);
            }
        }

        @Override
        protected void onMouseEvent(limn.scene.event.MouseEvent event) {
            switch (event.type()) {
                case ENTER -> {
                    inside = true;
                    setMode(PointerMode.HIDDEN);
                    invalidate();
                }
                case EXIT -> {
                    inside = false;
                    setMode(PointerMode.NORMAL);
                    invalidate();
                }
                default -> {
                }
            }
        }

        @Override
        protected void onDetached() {
            // Mode is window state; never leave the cursor hidden behind.
            inside = false;
            setMode(PointerMode.NORMAL);
        }
    }

    /**
     * Relative capture ("trackball"): click to capture the pointer; MOTION
     * deltas spin the dial; no screen edge ever stops the spin. Click again
     * or press ESC to release; losing focus or leaving the tree also releases
     * (the capture must never outlive its owner).
     */
    private static final class CaptureTile extends Widget {
        private boolean captured;
        private float angle;          // radians, driven by deltaX
        private float totalX;
        private float totalY;

        CaptureTile() {
            setFocusable(true); // MOTION is delivered to the focused widget
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 110);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(),
                    captured ? theme.surfaceRaised : theme.surface);
            canvas.drawRoundRect(0.5f, 0.5f, width() - 1, height() - 1, theme.tokensFor(this).radiusMedium(),
                    1, captured ? theme.primary : theme.outline);

            // Dial: a circle with a needle at 'angle'.
            float cx = height() / 2f;
            float cy = height() / 2f;
            float r = height() / 2f - 16;
            canvas.drawCircle(cx, cy, r, 2, captured ? theme.primary : theme.outline);
            float nx = cx + (float) Math.cos(angle) * r;
            float ny = cy + (float) Math.sin(angle) * r;
            canvas.drawLine(cx, cy, nx, ny, 2, theme.text);

            String text = captured
                    ? String.format("Captured: dx %.0f  dy %.0f  (click or ESC releases)",
                            totalX, totalY)
                    : "Click to capture the pointer (RELATIVE)";
            Font font = theme.body;
            TextMetrics m = textRuler().measure(text, font);
            canvas.drawText(text, cx * 2 + 12,
                    (height() - m.height()) / 2 + m.ascent(), font,
                    captured ? theme.text : theme.textMuted);
        }

        private void setCaptured(boolean on) {
            if (captured == on || scene() == null || scene().window() == null) {
                return;
            }
            captured = on;
            scene().window().setPointerMode(on ? PointerMode.RELATIVE : PointerMode.NORMAL);
            invalidate();
        }

        @Override
        protected void onMouseEvent(limn.scene.event.MouseEvent event) {
            switch (event.type()) {
                case PRESS -> {
                    setCaptured(!captured); // toggle: second click releases
                    event.consume();
                }
                case MOTION -> {
                    totalX += event.deltaX();
                    totalY += event.deltaY();
                    angle += event.deltaX() * 0.02f;
                    invalidate();
                    event.consume();
                }
                default -> {
                }
            }
        }

        @Override
        protected void onKeyEvent(limn.scene.event.KeyEvent event) {
            if (event.isPressed() && event.key() == Keys.ESCAPE && captured) {
                setCaptured(false);
                event.consume();
            }
        }

        @Override
        protected void onFocusLost() {
            setCaptured(false); // e.g. Tab away: never strand the capture
        }

        @Override
        protected void onDetached() {
            setCaptured(false);
        }
    }

    /** A hover-highlighted block that requests one specific cursor. */
    private static final class CursorTile extends Widget {
        private final String label;
        private final Transition hover =
                new Transition(this).duration(Theme.current().animHover).easing(Theme.current().animEasing);

        CursorTile(Cursor cursor, String label) {
            this.label = label;
            setCursor(cursor); // the whole point: any widget can pick its cursor
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 60);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            Color fill = theme.surface.lerp(theme.surfaceRaised, hover.value());
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(), fill);
            canvas.drawRoundRect(0.5f, 0.5f, width() - 1, height() - 1, theme.tokensFor(this).radiusMedium(),
                    1, theme.outline.lerp(theme.primaryHover, hover.value()));
            Font font = theme.body;
            TextMetrics m = textRuler().measure(label, font);
            canvas.drawText(label, (width() - m.width()) / 2,
                    (height() - m.height()) / 2 + m.ascent(), font, theme.text);
        }

        @Override
        protected void onMouseEvent(limn.scene.event.MouseEvent event) {
            switch (event.type()) {
                case ENTER -> hover.to(1);
                case EXIT -> hover.to(0);
                default -> {
                }
            }
        }
    }
}
