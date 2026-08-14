package limn.demo;

import limn.animation.ColorTransition;
import limn.animation.Easing;
import limn.animation.Transition;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.WindowStyle;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.ScrollView;
import limn.components.SegmentedControl;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
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

import java.util.List;

/**
 * Animation showcase: an easing playground (pick the curve, the timing and
 * whether it loops), two bouncing balls (bounce vs. rubber easing), a DVD-logo
 * that changes color when it hits a wall (and flashes on a corner) and a
 * {@link ColorTransition} swatch. Everything is driven by the toolkit's
 * {@code limn.animation} classes; nothing hand-rolls interpolation.
 */
final class AnimationsScene {

    private AnimationsScene() {
    }

    /** Standalone {@code --scene animations}. */
    static Scene create() {
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    /** The subtree, reusable as a kitchen-sink tab. */
    static Widget content() {
        Column column = new Column();
        column.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);

        // --- Easing playground (options + timing) ------------------------
        column.add(heading("Easing playground"));
        column.add(new Label("Pick the curve and the timing; “Animate” fires it. "
                + "Bounce and Rubber overshoot and come back.").setMuted(true).setWrap(true));
        EasingTrack track = new EasingTrack();
        column.add(track);

        Easing[] curves = {Easing.LINEAR, Easing.EASE_OUT, Easing.EASE_IN_OUT, Easing.BOUNCE, Easing.RUBBER};
        ComboBox easing = new ComboBox(List.of("Linear", "Ease out", "Ease in-out", "Bounce", "Rubber"));
        easing.setSelectedIndex(1);
        track.setEasing(curves[1]);
        easing.onSelect(i -> track.setEasing(curves[i]));

        double[] times = {0.4, 0.8, 1.5};
        ComboBox timing = new ComboBox(List.of("Fast", "Normal", "Slow"));
        timing.setSelectedIndex(1);
        timing.onSelect(i -> track.setDuration(times[i]));

        Button animate = new Button("Animate").onAction(track::play);
        Checkbox repeat = new Checkbox(Checkbox.Variant.SWITCH, "Repeat");
        repeat.onChange(track::setRepeat);

        Row controls = new Row();
        controls.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
        controls.add(new SizedBox(150, SizedBox.UNSET, easing));
        controls.add(new SizedBox(130, SizedBox.UNSET, timing));
        controls.add(animate);
        controls.add(Expanded.spacer(1));
        controls.add(repeat);
        column.add(controls);

        // --- Scene time (clamp / scale / pause) ---------------------------
        column.add(heading("Scene time"));
        column.add(new Label("Pause and slow down what the app animates. Everything below this row "
                + "runs on scene time and freezes; the toolkit's own chrome (hover and focus "
                + "fades, scrollbars, popups, the window fade above) runs on wall time and keeps "
                + "going, because a fade that never ends is what leaves a dialog half-closed.")
                .setMuted(true).setWrap(true));
        SegmentedControl speed = new SegmentedControl(List.of("0.25×", "0.5×", "1×", "2×"));
        double[] scales = {0.25, 0.5, 1, 2};
        speed.setSelectedIndex(2);
        speed.onSelect(i -> {
            Scene s = speed.scene();
            if (s != null) {
                s.setTimeScale(scales[i]);
            }
        });
        Checkbox pause = new Checkbox(Checkbox.Variant.SWITCH, "Paused");
        pause.onChange(on -> {
            Scene s = pause.scene();
            if (s != null) {
                s.setPaused(on);
            }
        });
        Row timeRow = new Row();
        timeRow.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
        timeRow.add(speed);
        timeRow.add(Expanded.spacer(1));
        timeRow.add(pause);
        column.add(timeRow);

        // --- Bouncing balls ----------------------------------------------
        column.add(heading("Bouncing balls"));
        Row balls = new Row();
        balls.gap(16).crossAlignment(Flex.CrossAlignment.STRETCH);
        balls.add(Expanded.of(labelled("Bounce", new BallDrop(Easing.BOUNCE, Color.rgb(0x4C8DFF))), 1));
        balls.add(Expanded.of(labelled("Rubber", new BallDrop(Easing.RUBBER, Color.rgb(0xF0997B))), 1));
        column.add(balls);

        // --- DVD logo + color transition ---------------------------------
        column.add(heading("DVD hitting the corners"));
        column.add(new DvdLogo());

        column.add(heading("ColorTransition (a → b)"));
        column.add(new ColorSwatch());

        // --- Whole-window fade (native window appears/disappears) --------
        column.add(heading("Window (fade-in/out)"));
        column.add(new Label("Opens a separate native window that appears and disappears "
                + "with a fade: the same effect as dialogs and popups, applied to the whole window.")
                .setMuted(true).setWrap(true));
        Button openWindow = new Button("Open floating window");
        openWindow.onAction(() -> openFloatingWindow(openWindow));
        Row windowRow = new Row();
        windowRow.add(openWindow);
        column.add(windowRow);

        return new ScrollView(column);
    }

    /** Opens a plain native window centered over the host that fades in, and fades out on close. */
    private static void openFloatingWindow(Widget anchor) {
        Scene host = anchor.scene();
        if (host == null || host.window() == null) {
            return; // headless / not shown
        }
        NativeWindow parent = host.window();
        int w = 320;
        int h = 180;

        Column body = new Column();
        body.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        body.add(new Label("Floating window").setRole(Label.Role.TITLE));
        body.add(new Label("I appeared with a fade-in. “Close” fades me out.")
                .setMuted(true).setWrap(true));
        Button close = new Button("Close");
        Row footer = new Row();
        footer.mainAlignment(Flex.MainAlignment.END);
        footer.add(close);
        body.add(footer);

        Scene scene = new Scene(new FloatingPanel(new Padding(Insets.all(18), body)));
        scene.setBackground(Color.TRANSPARENT);
        NativeWindow win = parent.backend().createWindow(WindowConfig.styled(
                "Window", w, h, WindowStyle.UNDECORATED_TRANSLUCENT, true, true));
        parent.registerChildPopup(win); // closes if the host closes
        scene.bind(win);

        float factor = parent.logicalToScreenFactor();
        int sx = parent.screenX() + Math.round((parent.logicalWidth() - w) / 2f * factor);
        int sy = parent.screenY() + Math.round((parent.logicalHeight() - h) / 2f * factor);
        win.setScreenPosition(sx, sy);

        close.onAction(() -> scene.fadeWindowOut(Theme.current().animWindow, () -> {
            if (!parent.isClosed()) {
                parent.unregisterChildPopup(win);
            }
            win.requestClose();
        }));

        scene.fadeWindowIn(Theme.current().animWindow); // transparent before it maps
        win.show();
        win.requestFrame();
    }

    // Role, not setFont(theme.title): setFont pins MEDIUM's 20 pt whatever step the
    // subtree resolves to, so the headings would stay put while the controls under
    // them ramp. The role picks the title token OF the resolved step instead.
    private static Label heading(String text) {
        return new Label(text).setRole(Label.Role.TITLE);
    }

    private static Widget labelled(String label, Widget content) {
        Column column = new Column();
        column.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(content);
        column.add(new Label(label).setMuted(true));
        return column;
    }

    // ------------------------------------------------------------- widgets

    /** A dot the user animates across a track with a chosen easing/timing. */
    private static final class EasingTrack extends Widget {
        private final Transition pos =
                new Transition(this).duration(0.8).easing(Easing.EASE_OUT).sceneTime(true);

        void setEasing(Easing easing) {
            pos.easing(easing);
            play();
        }

        void setDuration(double seconds) {
            pos.duration(seconds);
        }

        void setRepeat(boolean value) {
            pos.repeat(value);
            play();
        }

        void play() {
            pos.to(pos.target() >= 0.5f ? 0 : 1);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 64);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            float pad = 26;
            float cy = height() / 2;
            canvas.fillRoundRect(pad, cy - 2, width() - 2 * pad, 4, 2, theme.surfaceRaised);
            float cx = pad + pos.value() * (width() - 2 * pad);
            canvas.fillCircle(cx, cy, 12, theme.primary);
        }
    }

    /** A ball dropping and returning forever, shaped by one easing curve. */
    private static final class BallDrop extends Widget {
        private final Transition y;
        private final Color color;
        private boolean started;

        BallDrop(Easing easing, Color color) {
            this.color = color;
            this.y = new Transition(this).duration(1.3).easing(easing).repeat(true).sceneTime(true);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 150);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            if (!started && scene() != null) {
                started = true;
                y.to(1); // repeat(true) → yoyos forever
            }
            Theme theme = Theme.current();
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(), theme.surface);
            float r = 16;
            float top = r + 18;               // headroom for rubber overshoot
            float bottom = height() - r - 18;
            float cy = top + y.value() * (bottom - top);
            canvas.fillCircle(width() / 2, cy, r, color);
        }
    }

    /** The classic bouncing logo: recolors on every wall hit, flashes on a corner. */
    private static final class DvdLogo extends Widget {
        private static final float LOGO_W = 66;
        private static final float LOGO_H = 34;
        private static final Color[] PALETTE = {
                Color.rgb(0x4C8DFF), Color.rgb(0x1D9E75), Color.rgb(0xF0997B),
                Color.rgb(0xD4537E), Color.rgb(0xEF9F27), Color.rgb(0x7F77DD),
        };

        private float x = 24;
        private float y = 18;
        private float vx = 96;
        private float vy = 74;
        private int colorIndex;
        private double cornerFlash;
        private boolean started;

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 200);
        }

        private void advance(double dt) {
            float maxX = Math.max(0, width() - LOGO_W);
            float maxY = Math.max(0, height() - LOGO_H);
            x += (float) (vx * dt);
            y += (float) (vy * dt);
            boolean hitX = false;
            boolean hitY = false;
            if (x <= 0) {
                x = 0;
                vx = Math.abs(vx);
                hitX = true;
            } else if (x >= maxX) {
                x = maxX;
                vx = -Math.abs(vx);
                hitX = true;
            }
            if (y <= 0) {
                y = 0;
                vy = Math.abs(vy);
                hitY = true;
            } else if (y >= maxY) {
                y = maxY;
                vy = -Math.abs(vy);
                hitY = true;
            }
            if (hitX || hitY) {
                colorIndex = (colorIndex + 1) % PALETTE.length;
            }
            if (hitX && hitY) {
                cornerFlash = 0.6; // corner! celebrate for a moment
            }
            if (cornerFlash > 0) {
                cornerFlash = Math.max(0, cornerFlash - dt);
            }
        }

        @Override
        protected void onPaint(Canvas canvas) {
            if (!started && scene() != null) {
                started = true;
                scene().addTicker(dt -> {
                    if (!isShowing()) {
                        started = false; // pause on a hidden tab; onPaint re-arms when shown
                        return false;
                    }
                    advance(dt);
                    return true;
                });
            }
            Theme theme = Theme.current();
            canvas.fillRoundRect(0, 0, width(), height(), theme.tokensFor(this).radiusMedium(), theme.surface);
            Color logo = PALETTE[colorIndex];
            if (cornerFlash > 0) {
                logo = logo.lerp(Color.WHITE, (float) (cornerFlash / 0.6) * 0.7f);
            }
            canvas.fillRoundRect(x, y, LOGO_W, LOGO_H, 8, logo);
            Font font = theme.body;
            var m = textRuler().measure("LIMN", font);
            canvas.drawText("LIMN", x + (LOGO_W - m.width()) / 2,
                    y + (LOGO_H - m.height()) / 2 + m.ascent(), font, theme.onPrimary);
        }
    }

    /** A swatch cycling through colors with {@link ColorTransition}. */
    private static final class ColorSwatch extends Widget {
        private static final Color[] PALETTE = {
                Color.rgb(0x4C8DFF), Color.rgb(0x1D9E75), Color.rgb(0xEF9F27), Color.rgb(0xD4537E),
        };
        private final ColorTransition fill =
                new ColorTransition(this, PALETTE[0]).duration(0.6).easing(Easing.EASE_IN_OUT)
                        .sceneTime(true);
        private int index;
        private double since;
        private boolean started;

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 72);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            if (!started && scene() != null) {
                started = true;
                scene().addTicker(dt -> {
                    if (!isShowing()) {
                        started = false;
                        return false;
                    }
                    since += dt;
                    if (since >= 1.1 && !fill.isAnimating()) {
                        since = 0;
                        index = (index + 1) % PALETTE.length;
                        fill.to(PALETTE[index]);
                    }
                    return true;
                });
            }
            canvas.fillRoundRect(0, 0, width(), height(), Theme.current().tokensFor(this).radiusMedium(), fill.value());
        }
    }

    /** Rounded translucent card that hosts the floating window's content. */
    private static final class FloatingPanel extends Widget {
        private final Widget child;

        FloatingPanel(Widget child) {
            this.child = child;
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), c.maxHeight());
        }

        @Override
        protected void onLayout() {
            child.layoutBox(0, 0, width(), height());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            canvas.fillRoundRect(0.5f, 0.5f, width() - 1, height() - 1, theme.tokensFor(this).radiusLarge(),
                    theme.surface.withAlpha(0.98f));
            canvas.drawRoundRect(0.5f, 0.5f, width() - 1, height() - 1, theme.tokensFor(this).radiusLarge(),
                    1, theme.outline);
        }
    }
}
