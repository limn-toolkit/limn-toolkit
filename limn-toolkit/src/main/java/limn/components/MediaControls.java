package limn.components;

import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.i18n.I18nString;
import limn.graphics.Path2D;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;
import limn.video.VideoStreamSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Playback controls for a {@link VideoView}: play/pause, a scrub bar and a position clock — the
 * bar every player lays over its picture. Everything here drives the view and only the view
 * ({@link VideoView#setPaused}, {@link VideoView#seek(long, VideoStreamSource.SeekMode)},
 * {@link VideoView#positionMicros()}), so it works with a frozen capture, a source with no
 * soundtrack, and a player the application built itself.
 *
 * <p><b>It reads left to right in either direction, by default.</b> The playback cluster inherits
 * its arrangement from tape decks and the scrub bar advances the way the tape ran; media playback
 * is the standing exception in every platform's bidi guidance, so this widget declares {@code LTR}
 * for itself when it is built. That is a declaration and not a law: an application that wants the
 * controls to follow the tree (some platforms do mirror them) calls
 * {@code setLayoutDirection(null)} to inherit, or passes an explicit direction — the same knob
 * every widget has (see {@code docs/design/direction-axis.md}).
 *
 * <p><b>What deliberately stays out.</b> Volume, audio-track and subtitle choices depend on how an
 * application wired its sound and its container, and the toolkit has no opinion about either. The
 * bar composes instead: {@link #addLeading} places a widget between the play button and the scrub
 * bar (where players put their volume), {@link #addTrailing} between the scrub bar and the clock,
 * and {@link #setOnRefresh} gives injected controls a ride on the same heartbeat the built-in ones
 * update on. {@link #setShowPosition} and {@link #setBackdrop} trim the built-ins.
 *
 * <p>The bar polls on a timer rather than a ticker, so parked over a paused picture it costs
 * nothing: every write below is guarded, and a frame is only asked for when a number actually
 * moved.
 */
public class MediaControls extends Widget {

    /**
     * The least a drag lets pass between two keyframe seeks: a slider fires per pixel, and a drag
     * across the bar must not queue a decode per pixel. The seek on release is exact and is never
     * throttled.
     */
    private static final long SCRUB_INTERVAL_MICROS = 250_000;

    /** How often the clock and the thumb are re-read while the bar is on screen. */
    private static final int POLL_MILLIS = 100;

    private static final I18nString PLAY =
            new I18nString("limn.mediaControls.play", "Play");
    private static final I18nString PAUSE =
            new I18nString("limn.mediaControls.pause", "Pause");
    private static final I18nString SCRUB =
            new I18nString("limn.mediaControls.scrub", "Drag to scrub");

    private final VideoView view;
    private final PlayPause playPause = new PlayPause();
    private final Slider bar = new Slider(0, 1000);
    private final Widget scrub = Expanded.of(bar);
    private final Label position = new Label("").setMuted(true);
    private final Row row = new Row();
    private final List<Widget> leading = new ArrayList<>();
    private final List<Widget> trailing = new ArrayList<>();

    private Runnable onRefresh;
    private boolean showPosition = true;
    private boolean backdrop = true;
    private Color ink;
    private Color mutedInk;
    private boolean polling;
    private boolean dragging;
    private long lastScrubNanos = Long.MIN_VALUE;
    private Boolean showingPaused;

    public MediaControls(VideoView view) {
        this.view = Objects.requireNonNull(view, "view");
        // The media convention, declared rather than resolved: see the class comment. A
        // declaration in a constructor is a stored preference, not a captured resolution,
        // which is why this one line is legal where resolving a direction is not.
        setLayoutDirection(LayoutDirection.LTR);
        row.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
        bar.setTooltip(SCRUB);
        bar.onChange(fraction -> {
            dragging = true;
            scrubTo(fraction, false);
        });
        bar.onCommit(fraction -> {
            dragging = false;
            scrubTo(fraction, true);
        });
        rebuild();
        add(row);
    }

    /** The view these controls drive. */
    public VideoView view() {
        return view;
    }

    /**
     * Adds a widget between the play button and the scrub bar — where players put their volume.
     * Order of calls is the order on screen.
     */
    public MediaControls addLeading(Widget widget) {
        leading.add(Objects.requireNonNull(widget, "widget"));
        rebuild();
        return this;
    }

    /** Adds a widget between the scrub bar and the position clock. */
    public MediaControls addTrailing(Widget widget) {
        trailing.add(Objects.requireNonNull(widget, "widget"));
        rebuild();
        return this;
    }

    /** Whether the position clock is shown (default true). */
    public MediaControls setShowPosition(boolean show) {
        if (showPosition != show) {
            showPosition = show;
            rebuild();
        }
        return this;
    }

    /**
     * Whether the bar paints its own translucent panel (default true). A host that already draws
     * chrome behind the controls — a glass strip over the picture — turns it off.
     */
    public MediaControls setBackdrop(boolean paint) {
        if (backdrop != paint) {
            backdrop = paint;
            markNeedsLayout();
        }
        return this;
    }

    /**
     * Runs on every refresh of the built-in controls, so a widget injected through
     * {@link #addLeading}/{@link #addTrailing} can update on the same heartbeat instead of
     * arming a timer of its own.
     */
    public MediaControls setOnRefresh(Runnable listener) {
        onRefresh = listener;
        return this;
    }

    /**
     * Overrides the ink the built-ins write in — the play glyph and the clock. A bar drawn over
     * someone else's chrome (a glass strip over the picture) sits on a surface the palette knows
     * nothing about, and a theme ink chosen for the theme's own surfaces can land near-black on
     * it. {@code null}s restore the theme's answer.
     */
    public MediaControls setInk(Color newInk, Color newMutedInk) {
        ink = newInk;
        mutedInk = newMutedInk;
        if (newMutedInk != null) {
            position.setColor(newMutedInk);
        } else {
            position.setMuted(true);
        }
        invalidate();
        return this;
    }

    private void rebuild() {
        for (Widget child : List.copyOf(row.children())) {
            row.remove(child);
        }
        row.add(playPause);
        for (Widget widget : leading) {
            row.add(widget);
        }
        row.add(scrub);
        for (Widget widget : trailing) {
            row.add(widget);
        }
        if (showPosition) {
            row.add(position);
        }
        markNeedsLayout();
    }

    private float pad() {
        return backdrop ? Theme.current().tokensFor(this).padV() : 0;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        float pad = pad();
        Size inner = row.measure(constraints.deflate(Insets.all(pad)));
        return constraints.constrain(inner.width() + 2 * pad, inner.height() + 2 * pad);
    }

    @Override
    protected void onLayout() {
        float pad = pad();
        row.measure(Constraints.tight(width() - 2 * pad, height() - 2 * pad));
        row.layoutBox(pad, pad, width() - 2 * pad, height() - 2 * pad);
    }

    @Override
    protected void onPaint(Canvas canvas) {
        refresh();
        if (backdrop) {
            Theme theme = Theme.current();
            float radius = Theme.current().tokensFor(this).radiusMedium();
            canvas.fillRoundRect(0, 0, width(), height(), radius,
                    theme.surface.withAlpha(0.92f));
            canvas.drawRoundRect(0.5f, 0.5f, width() - 1, height() - 1, radius,
                    Strokes.BORDER, theme.outline);
        }
        if (!polling && scene() != null && isShowing()) {
            polling = true;
            poll();
        }
    }

    private void poll() {
        Ui.postDelayed(() -> {
            // Ends with the bar leaving the screen; the paint that brings it back arms it again.
            if (!isShowing() || scene() == null) {
                polling = false;
                return;
            }
            refresh();
            poll();
        }, POLL_MILLIS);
    }

    /**
     * Re-reads the view and writes the controls. Every write is guarded — {@code setValue},
     * {@code setText} and {@code setTooltip} all invalidate, and a bar that repainted the window
     * every poll over a paused picture would be the most expensive thing on screen.
     */
    private void refresh() {
        long length = durationMicros();
        boolean seekable = view.canSeek() && length > 0;
        bar.setEnabled(seekable);
        playPause.setEnabled(view.source() != null || view.player() != null);
        boolean paused = view.isPaused();
        if (showingPaused == null || showingPaused != paused) {
            showingPaused = paused;
            playPause.setTooltip(paused ? PLAY : PAUSE);
            playPause.invalidate();
        }
        long at = view.positionMicros();
        if (!dragging && length > 0) {
            bar.setValue(Math.max(0, Math.min(bar.max(), at / (float) length * bar.max())));
        }
        // A clock, never a sentence: prose in a slot that is a timestamp everywhere else changes
        // width, changes alignment, and reads as an error rather than as a source with no end to
        // report.
        if (showPosition) {
            position.setText(length > 0
                    ? clock(at) + " / " + clock(length)
                    : seekable ? clock(at) : clock(0));
        }
        if (onRefresh != null) {
            onRefresh.run();
        }
    }

    private void scrubTo(float fraction, boolean settled) {
        long length = durationMicros();
        if (length <= 0 || !view.canSeek()) {
            return;
        }
        long target = (long) (fraction / bar.max() * length);
        long now = System.nanoTime();
        if (!settled && lastScrubNanos != Long.MIN_VALUE
                && now - lastScrubNanos < SCRUB_INTERVAL_MICROS * 1_000L) {
            return;
        }
        lastScrubNanos = now;
        view.seek(target, settled
                ? VideoStreamSource.SeekMode.EXACT
                : VideoStreamSource.SeekMode.KEYFRAME);
    }

    private long durationMicros() {
        if (view.player() != null) {
            return view.player().video().durationMicros();
        }
        VideoStreamSource source = view.source();
        return source == null ? VideoStreamSource.DURATION_UNKNOWN : source.durationMicros();
    }

    /** {@code m:ss}, which is what a transport says and a duration in microseconds is not. */
    private static String clock(long micros) {
        long seconds = Math.max(0, micros) / 1_000_000L;
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    /**
     * The one control with no widget to borrow: a square button whose glyph is drawn ink. The
     * triangle points the way the tape runs and <b>never mirrors</b>, for the reason the whole
     * bar declares {@code LTR}; it survives even a caller who re-declares the bar to follow a
     * right-to-left tree, because a play glyph is a universal mark, like a check.
     */
    private final class PlayPause extends Widget {

        private final Path2D glyph = new Path2D();
        private boolean armed;
        private boolean keyArmed;
        private float hover;

        PlayPause() {
            setFocusable(true);
            setCursor(limn.backend.Cursor.POINTER);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            float side = Theme.current().tokensFor(this).controlHeight();
            return constraints.constrain(side, side);
        }

        @Override
        protected float paintOutset() {
            return Strokes.FOCUS_RING_OUTSET;
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            float radius = Theme.current().tokensFor(this).radiusMedium();
            float lift = !isEnabled() ? 0 : (armed || keyArmed) ? 0.26f : 0.14f * hover;
            if (lift > 0.001f) {
                canvas.fillRoundRect(0, 0, width(), height(), radius,
                        theme.text.withAlpha(lift * 0.5f));
            }
            if (isFocused()) {
                float gap = Strokes.FOCUS_GAP_BUTTON;
                canvas.drawRoundRect(-gap, -gap, width() + 2 * gap, height() + 2 * gap,
                        radius + gap, Strokes.FOCUS_RING, theme.focusRing);
            }
            Color glyphInk = isEnabled()
                    ? (ink != null ? ink : theme.text)
                    : (mutedInk != null ? mutedInk : theme.textMuted);
            float box = Theme.current().tokensFor(this).iconBox();
            float x = (width() - box) / 2;
            float y = (height() - box) / 2;
            if (view.isPaused()) {
                // Play: a triangle pointing the way the tape runs, in any language.
                glyph.reset();
                glyph.moveTo(x + box * 0.2f, y)
                        .lineTo(x + box * 0.95f, y + box / 2)
                        .lineTo(x + box * 0.2f, y + box)
                        .close();
                canvas.fillPath(glyph, glyphInk);
            } else {
                float barW = box * 0.28f;
                canvas.fillRect(x + box * 0.15f, y, barW, box, glyphInk);
                canvas.fillRect(x + box * 0.6f, y, barW, box, glyphInk);
            }
        }

        private void toggle() {
            if (!isEnabled()) {
                return;
            }
            view.setPaused(!view.isPaused());
            refresh();
            invalidate();
        }

        @Override
        protected void onMouseEvent(limn.scene.event.MouseEvent event) {
            switch (event.type()) {
                case ENTER -> {
                    hover = 1;
                    invalidate();
                }
                case EXIT -> {
                    hover = 0;
                    armed = false;
                    invalidate();
                }
                case PRESS -> {
                    if (event.button() == limn.input.Keys.MOUSE_LEFT) {
                        armed = true;
                        invalidate();
                        event.consume();
                    }
                }
                case RELEASE -> {
                    armed = false;
                    invalidate();
                    event.consume();
                }
                case CLICK -> {
                    if (event.button() == limn.input.Keys.MOUSE_LEFT) {
                        event.consume();
                        toggle();
                    }
                }
                default -> {
                }
            }
        }

        @Override
        protected void onKeyEvent(limn.scene.event.KeyEvent event) {
            if (event.key() != limn.input.Keys.ENTER && event.key() != limn.input.Keys.SPACE) {
                return;
            }
            if (event.isPressed() && !event.isRepeat()) {
                keyArmed = true;
                invalidate();
                event.consume();
            } else if (!event.isPressed()) {
                boolean fire = keyArmed;
                keyArmed = false;
                invalidate();
                event.consume();
                if (fire) {
                    toggle();
                }
            }
        }
    }
}
