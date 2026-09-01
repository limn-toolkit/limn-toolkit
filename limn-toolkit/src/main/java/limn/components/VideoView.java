package limn.components;

import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.video.MediaPlayer;
import limn.video.VideoClock;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import limn.video.VideoSurface;
import limn.video.VideoSurfaces;

import java.util.Objects;

/**
 * Shows the pictures of a {@link VideoStreamSource}: each one handed to a device-side picture and
 * composited as one quad in the 2D paint order, so clipping, opacity, overlays and dialogs apply to
 * a video exactly as they apply to a rectangle.
 *
 * <p>How a picture reaches the device is the surface's business and not this view's: samples are
 * uploaded and a hardware decoder's handle is bound without a copy, and either way the frame is read
 * and released inside the paint that showed it. This view therefore has no branch for it.
 *
 * <p><b>A stream, or a player, and nothing this view opens or closes.</b> {@link #setSource} shows a
 * stream directly and {@link #setPlayer} shows what a {@link MediaPlayer} has already decoded and
 * timed; setting one clears the other, because two things pacing one picture is two clocks
 * disagreeing. Either way the view never calls {@link VideoStreamSource#close()} and never calls
 * {@link MediaPlayer#close()}: not on replacement, not on detach, not ever. A view that closed
 * what it was given would make {@code setSource(a); setSource(b); setSource(a)} impossible and would
 * close a stream a second view is still showing; and a widget that could open one would have to know
 * about decoders, which is the dependency this widget set exists without. What the view does own is
 * its device-side picture and any decoded pictures it is holding, and all of them are released when it is
 * detached.
 *
 * <pre>{@code
 * VideoStreamSource stream = Videos.open(file);   // the application's decoder, the application's stream
 * VideoView view = new VideoView(stream).setLooping(true);
 * // ...or, with a decode thread and a soundtrack behind it:
 * MediaPlayer player = new MediaPlayer(stream).setAudio(track, PlayOptions.DEFAULTS);
 * VideoView view = new VideoView().setPlayer(player);
 * player.start();                                 // the view does not start or stop a player
 * // ... and when the application is finished with it:
 * player.close();
 * stream.close();
 * }</pre>
 *
 * <p><b>Pacing, with a stream.</b> Pictures are shown on a {@link VideoClock} driven by the wall
 * clock, so a 30-per-second stream is shown thirty times a second on a display refreshing at any
 * rate, and a held tick costs no repaint at all. The view holds two decoded pictures at most (the
 * one waiting for its moment and the one after it), and that successor is what lets the clock drop:
 * a view that has fallen behind (a slow decode, or a stream whose rate is above the display's)
 * catches up by leaving late pictures unshown, which is what keeps the film at its own speed rather
 * than at whatever the machine could manage. Without the second picture, which is what a source with
 * a single pooled slot leaves it with, nothing is ever dropped and a view that falls behind stays
 * behind.
 *
 * <p><b>Pacing, with a player.</b> The player has already decided: it hands over a picture only when
 * that picture's moment has come, having dropped any whose moment passed, and it is what the audio
 * position masters. The view then does nothing but upload and draw. Everything that was the view's
 * ({@link #restart()}, {@link #seek(long, VideoStreamSource.SeekMode)}, {@link #isEnded()},
 * {@link #failure()}, {@link #setLooping} and {@link #clock()}) reads or drives the player
 * instead, so there is one answer to each rather than two that can disagree.
 *
 * <p><b>A recording made sideways is shown upright.</b> A stream reporting a quarter turn is drawn
 * under that rotation and <em>measures</em> to its displayed size, so a portrait recording asks for
 * a portrait box and letterboxes inside it as a portrait picture. The samples are never turned;
 * the angle is a transform on the quad, which the picture was already going through.
 *
 * <p><b>Decoding happens on the UI thread with a stream</b>, at most two pictures per repaint (the
 * one being shown and the one read ahead of it), in the periodic callback that paces the view; a
 * source whose {@code readFrame} is slow therefore costs frame time. With a player it happens on that player's decode thread, and the UI thread only takes
 * what is ready.
 *
 * <p><b>Points and device pixels.</b> Everything this widget measures, lays out and draws is in
 * logical points. The picture is in device pixels and is the size the stream says it is;
 * {@link VideoSurface#resize} is a no-op and is never called here. A change of content scale, a
 * window resize and a change of resolution mid-stream are three different events and none of them
 * costs a reallocation on this side: the first two only change the rectangle the composite filters
 * the picture into, and the third is absorbed by the upload, after which the aspect ratio of the
 * letterbox follows the new picture while the measured box keeps following the stream's declared
 * size, so a resolution change re-letterboxes without relaying out the window around it.
 *
 * <p><b>What is on screen, in every state a stream can be in.</b> With no stream: nothing at all,
 * and the view asks the layout for no room either. With a stream and no picture yet (including a
 * decoder that has not produced one), the {@linkplain #setLetterboxColor bar colour} over the whole
 * box, so the space is visibly reserved rather than showing a hole. Playing: the picture, centred and
 * fitted, over those bars. Ended, whether because looping is off or because the stream cannot be
 * rewound: the last picture, unchanged and costing nothing, until something else is asked of the
 * view. A decode that threw: a short message, and the stream is not read again; {@link #failure()}
 * has the exception and {@link #restart()} clears it. No GPU backend at all: a placeholder frame and
 * a message, and the stream is never opened for reading, so a machine with no device decodes nothing.
 *
 * <p><b>Reading right to left changes the notice and nothing else.</b> A picture is content: a
 * frame drawn mirrored would reverse the sign on a shop front and turn every hand in it into the
 * other one, so the letterbox, the fit and the rotation are the same arithmetic in both
 * directions. The one thing here that is language and not picture is the message a stalled or
 * unsupported view draws, and it is shaped for this view's direction. Its pill stays where it is:
 * it is centred, and a centre has no side to move to.
 *
 * <p><b>Cost while hidden.</b> A view that is not {@linkplain #isShowing() showing} (an unselected
 * tab, a scrolled-away row, {@code setVisible(false)}) arms no periodic callback, decodes nothing
 * and uploads nothing. The repaint that reveals it starts it again. A <em>player</em> is not stopped
 * by any of that: it keeps decoding until its ring is full and its soundtrack keeps playing, because
 * the player is the application's and a track that stopped when a tab was clicked is not what a
 * player is for. Stop it, or pause it, if that is what the application wants.
 *
 * <p><b>Size axis:</b> this widget does not participate. Its content is a decoded picture whose
 * size is the stream's, and the box is the layout's, so no {@link limn.scene.ControlSize} step
 * changes what a video looks like. The one thing that <em>is</em> chrome (the "no GPU backend" and
 * "cannot be played" notices) reads the row resolved on this widget like every other component, so
 * a view dropped into an XSMALL panel does not report failure in MEDIUM body type.
 *
 * <p>UI thread throughout, like every widget.
 */
public class VideoView extends Widget {

    /** How a picture whose aspect ratio differs from the box's is mapped into it. */
    public enum Fit {

        /** Scale to fit inside the box, keeping the aspect ratio; the remainder is letterboxed. */
        CONTAIN,

        /** Scale to cover the box, keeping the aspect ratio; the overflow is clipped away. */
        COVER,

        /** Stretch to the box, ignoring the aspect ratio. Nothing is cropped and nothing is barred. */
        FILL
    }

    private VideoStreamSource source;
    private MediaPlayer player;
    private VideoSurface surface;
    private VideoClock clock = new VideoClock();
    private Fit fit = Fit.CONTAIN;
    private Color letterbox = Color.BLACK;
    private float preferredWidth = -1;
    private float preferredHeight = -1;
    private boolean looping;
    /**
     * This view's own pause, used only when no player drives it. Kept beside the clock's rather
     * than read back out of it: the clock cannot be asked whether it is paused, and a stream with
     * no timing never reaches the clock at all.
     */
    private boolean paused;

    /** The picture being judged: waiting for its moment, then for its upload. */
    private VideoFrame picture;
    /**
     * The one after it, decoded early. It is what lets the clock be told what follows, and a clock
     * with no successor never drops; see {@link #advance()}.
     */
    private VideoFrame ahead;
    /** The source has reported its end; {@link #picture} and {@link #ahead} are what is left. */
    private boolean drained;
    /** The clock has said to show {@link #picture}; the next paint uploads it. */
    private boolean due;
    /** A picture of the <em>current</em> source has been uploaded, so the surface is worth drawing. */
    private boolean uploaded;
    /** The first decode has been attempted in a paint, which is what makes a single frame show one. */
    private boolean primed;
    private boolean ended;
    private RuntimeException failure;

    private boolean ticking;
    private boolean polling;    // a paused-poll callback is in flight
    private int tickGeneration; // kills a ticker left behind by a detach

    /** How often a paused view asks whether something outside it has resumed the player. */
    private static final long PAUSED_POLL_MILLIS = 150;

    /**
     * Whether installing a source starts it playing. False by default: a video that begins the
     * moment it is built is a decision about someone else's screen, and the application is what
     * knows whether this is a film somebody asked for or a panel that happens to contain one.
     */
    private boolean autoplay;

    /** A view with no stream: it asks for no space and draws nothing until {@link #setSource}. */
    public VideoView() {
    }

    /**
     * A view showing {@code source}, which stays the caller's to close. Null is allowed.
     *
     * <p>Paused, like {@link #setSource}: see {@link #setAutoplay}. It shows the first picture and
     * holds it.
     */
    public VideoView(VideoStreamSource source) {
        this.source = source;
        this.paused = true;
        clock.setPaused(true);
    }

    /**
     * Shows {@code source} from wherever it currently is, or nothing when null. The previous source
     * is <b>not</b> closed; it is the caller's, and closing it here would break handing the same
     * stream to another view. The previous stream's last picture is not shown again: the box stays
     * empty until the new stream produces one, so two streams never share a frame on screen.
     *
     * <p>Re-measures, because the natural size is the stream's.
     */
    public VideoView setSource(VideoStreamSource newSource) {
        Ui.checkUiThread();
        releasePictures();
        source = newSource;
        player = null; // one thing paces the picture, or two clocks disagree about it
        reset();
        return this;
    }

    /**
     * Whether a source installed <em>after</em> this call starts playing by itself. <b>False by
     * default: a video starts paused</b>, showing its first picture and holding it, and something
     * has to ask for it to run: {@link #setPaused setPaused(false)}, a transport's play button,
     * an application that knows the view is the reason the window was opened.
     *
     * <p>Applied when a source or player is installed, so set it before {@link #setSource} rather
     * than after; on a view that already holds one, {@code setPaused(false)} is what starts it.
     *
     * <p>A stream shows its first picture either way: a paused video is a still frame, not an
     * empty box. A {@link MediaPlayer} that has not been {@linkplain MediaPlayer#start() started}
     * has decoded nothing and therefore shows nothing, which is that class's contract and not this
     * one's.
     */
    public VideoView setAutoplay(boolean newAutoplay) {
        Ui.checkUiThread();
        this.autoplay = newAutoplay;
        if (newAutoplay) {
            // Also starts what is already installed, so the fluent form reads as it looks:
            // new VideoView(stream).setAutoplay(true) plays, rather than setting a flag for a
            // source nobody is going to install.
            setPaused(false);
        }
        return this;
    }

    /** @return whether an installed source starts playing by itself; false by default */
    public boolean isAutoplay() {
        return autoplay;
    }

    /** @return the stream being shown directly, or null when there is none or a player holds it */
    public VideoStreamSource source() {
        return source;
    }

    /**
     * Shows what {@code newPlayer} decodes and times, or nothing when null. The previous player is
     * <b>not</b> stopped and <b>not</b> closed, and neither is the stream behind it: both are the
     * caller's. Clears any {@linkplain #setSource stream} set directly.
     *
     * <p>The view does not {@linkplain MediaPlayer#start() start} the player either; a view that
     * started one would decide when a soundtrack begins, which belongs to whatever opened it. A
     * player that has not been started shows the bar colour and nothing else.
     *
     * <p>Re-measures, because the natural size is the player's stream's.
     */
    public VideoView setPlayer(MediaPlayer newPlayer) {
        Ui.checkUiThread();
        releasePictures();
        player = newPlayer;
        source = null;
        reset();
        return this;
    }

    /** @return the player driving this view, or null when it is showing a stream directly */
    public MediaPlayer player() {
        return player;
    }

    /** Forgets everything about what was on screen, without touching what produced it. */
    private void reset() {
        uploaded = false;
        ended = false;
        failure = null;
        primed = false;
        // A new stream starts paused unless the application asked otherwise, and a view left paused
        // by the PREVIOUS source must not inherit that pause; the two are different questions and
        // this is the one place both are answered.
        paused = !autoplay;
        clock.setPaused(paused);
        clock.reset();
        markNeedsLayout();
    }

    /** @return the stream whose declared size this view measures to, or null when there is none */
    private VideoStreamSource stream() {
        return player != null ? player.video() : source;
    }

    /** How the picture maps into the box when the two aspect ratios differ; {@link Fit#CONTAIN} by default. */
    public VideoView setFit(Fit newFit) {
        Ui.checkUiThread();
        fit = Objects.requireNonNull(newFit, "newFit");
        invalidate();
        return this;
    }

    /** @return how the picture maps into the box */
    public Fit fit() {
        return fit;
    }

    /**
     * The colour behind the picture: the bars beside or above a {@link Fit#CONTAIN} one, and the
     * whole box whenever there is no picture: before the first arrives, while a stream is being
     * chosen, and when there is no stream at all.
     *
     * <p>Opaque black by default, which is what every player uses and is not an arbitrary choice: a
     * bar in any other colour sits directly against the picture's edge and shifts how the tones next
     * to it are read, and black is the one colour that adds no such cast.
     *
     * <p><b>There is no way to turn it off.</b> A view with no background is a hole: the box is
     * reserved and laid out, and whatever happens to be behind it shows through, so an application
     * that has not yet opened a stream displays not "a player waiting" but a gap in its own window
     * that reads as a bug. Being able to paint nothing was worth less than never being able to
     * produce that, so a null or fully transparent colour is refused here rather than becoming a
     * blank rectangle somebody has to explain later. A partly transparent one is allowed: it tints
     * what is behind rather than deleting it.
     *
     * @param color the background colour; never null and never fully transparent
     * @throws IllegalArgumentException if {@code color} is fully transparent
     * @throws NullPointerException     if {@code color} is null
     */
    public VideoView setLetterboxColor(Color color) {
        Ui.checkUiThread();
        Objects.requireNonNull(color, "color");
        if (color.a() <= 0f) {
            throw new IllegalArgumentException(
                    "a video view's background may not be fully transparent: the box is reserved "
                            + "either way, and an empty one reads as a hole in the window");
        }
        letterbox = color;
        invalidate();
        return this;
    }

    /** @return the background colour; never null */
    public Color letterboxColor() {
        return letterbox;
    }

    /**
     * The size to ask the layout for, in logical points, or {@code -1, -1} (the default) to ask for
     * the stream's own size at one point per pixel. Set it whenever the stream's size is not the size
     * wanted on screen (a 1920×1080 stream asks for 1920×1080 points otherwise) or to reserve the
     * box before a stream has been opened at all.
     */
    public VideoView setPreferredSize(float width, float height) {
        Ui.checkUiThread();
        preferredWidth = width;
        preferredHeight = height;
        markNeedsLayout();
        return this;
    }

    /**
     * Whether reaching the end rewinds and plays again (default false). Ignored by a stream that
     * {@linkplain VideoStreamSource#canReset() cannot be rewound}, which ends once whatever this says.
     */
    public VideoView setLooping(boolean newLooping) {
        Ui.checkUiThread();
        looping = newLooping;
        if (player != null) {
            player.setLooping(newLooping); // the player owns the decode, so it owns the rewind
        }
        invalidate(); // an ended view re-arms its callback on the next paint
        return this;
    }

    /** @return whether the end rewinds rather than stops; the player's answer when one drives this view */
    public boolean isLooping() {
        return player != null ? player.isLooping() : looping;
    }

    /**
     * Rewinds the stream to its first picture and re-anchors the pacing, the only repositioning
     * {@link VideoStreamSource} defines, and not a seek. Clears an ended or failed state, so it is
     * also how a view recovers from a decode that threw. The picture already on screen stays there
     * until the first one of the new pass is uploaded, so restarting does not flash.
     *
     * <p>Does nothing when there is no source.
     *
     * @throws UnsupportedOperationException if the stream cannot be rewound, which
     *                                       {@link VideoStreamSource#canReset()} answers in advance
     */
    public void restart() {
        Ui.checkUiThread();
        releasePictures();
        if (player != null) {
            player.restart(); // rewinds the stream, resets the pacing, and clears the player's failure
        } else if (source != null) {
            source.reset();
        } else {
            return;
        }
        ended = false;
        failure = null;
        primed = false;
        clock.reset();
        invalidate();
    }

    /**
     * Moves to {@code micros} and carries on from there: the player's seek when one drives this
     * view, and the stream's plus this view's own pacing otherwise. Like {@link #restart()} it
     * exists because the view holds state the caller cannot reach: the pacing anchor, the picture
     * being held, and the ended and failed flags, none of which a bare
     * {@link VideoStreamSource#seek} would put right.
     *
     * <p>The picture on screen stays there until the first one from the new position is uploaded,
     * so a scrub does not flash. Clears an ended or failed state.
     *
     * <p>Cheap enough to call from a control being dragged in {@link VideoStreamSource.SeekMode#KEYFRAME},
     * and {@link VideoStreamSource.SeekMode#EXACT} is what lands where the viewer let go. Does
     * nothing when there is no stream.
     *
     * @throws UnsupportedOperationException if the stream cannot be seeked, which {@link #canSeek()}
     *                                       answers in advance
     * @throws IllegalArgumentException      if {@code micros} is negative
     */
    public void seek(long micros, VideoStreamSource.SeekMode mode) {
        Ui.checkUiThread();
        if (player == null && source == null) {
            return;
        }
        releasePictures();
        if (player != null) {
            player.seek(micros, mode);
        } else {
            source.seek(micros, mode);
            clock.seekTo(micros);
        }
        ended = false;
        failure = null;
        primed = false;
        invalidate();
    }

    /** As {@link #seek(long, VideoStreamSource.SeekMode)} landing exactly. */
    public void seek(long micros) {
        seek(micros, VideoStreamSource.SeekMode.EXACT);
    }

    /**
     * @return whether {@link #seek(long, VideoStreamSource.SeekMode)} works, which is the stream's
     *         answer and false with no stream. A transport control asks this rather than catching
     *         an exception under the viewer's finger.
     */
    public boolean canSeek() {
        VideoStreamSource seekable = stream();
        return seekable != null && seekable.canSeek();
    }

    /**
     * @return where the pictures have reached, in microseconds on the stream's own timeline: the
     *         player's position when one drives this view and this view's clock otherwise. What a
     *         transport control puts its thumb at.
     */
    public long positionMicros() {
        return clock().positionMicros();
    }

    /**
     * Freezes the picture where it is, or lets it run again: the player's pause when one drives
     * this view, so the sound freezes with the picture, and this view's own pacing otherwise.
     * Position is kept either way: resuming continues rather than restarting.
     *
     * <p>A paused view still decodes one picture and holds it, so resuming shows the next picture
     * immediately rather than after a decode. It reads no further than that: the read-ahead exists
     * to let the clock drop, and a paused clock drops nothing. It does nothing with no
     * stream, and it is not what a stream reaching its end does; that is {@link #isEnded()}.
     *
     * <p><b>A stream carrying no timing at all pauses too.</b> Such a stream is otherwise shown one
     * picture per repaint with no clock consulted, so pausing it has to be answered here rather
     * than by the pacing.
     */
    public VideoView setPaused(boolean newPaused) {
        Ui.checkUiThread();
        paused = newPaused;
        if (player != null) {
            if (newPaused) {
                player.pause();
            } else {
                // start(), not resume(): a player that has never run is IDLE rather than PAUSED,
                // and resume() does nothing to one. Installing a player still does not start it
                // (that promise is on setPlayer), but being told to play is not installing.
                player.start();
            }
        } else {
            clock.setPaused(newPaused);
        }
        if (!newPaused) {
            startTicking(); // a paused view stops ticking; resuming through this view re-arms it now
        }
        invalidate();
        return this;
    }

    /**
     * @return whether the picture is frozen: the player's answer when one drives this view, so a
     *         player paused directly reads as paused here too, and this view's own otherwise
     */
    public boolean isPaused() {
        if (player == null) {
            return paused;
        }
        MediaPlayer.State state = player.state();
        // IDLE counts: a player nobody has started is showing nothing and moving nothing, which is
        // what a transport's play button and this view's own idling both need to know.
        return state == MediaPlayer.State.PAUSED || state == MediaPlayer.State.IDLE;
    }

    /**
     * @return whether the stream has reported its end and will not be rewound: either because
     *         looping is off or because the stream cannot be rewound. The last picture stays on
     *         screen; nothing is decoded, uploaded or repainted afterwards.
     */
    public boolean isEnded() {
        return player != null ? player.isEnded() : ended;
    }

    /**
     * @return the exception a decode threw, or null. The view then shows a message instead of the
     *         stream and stops asking the source for anything; {@link #restart()} or a new source is
     *         what clears it.
     */
    public RuntimeException failure() {
        if (failure != null) {
            return failure; // an upload that threw is this view's own, player or no player
        }
        return player != null ? player.failure() : null;
    }

    /**
     * The clock deciding when each picture is shown: <b>the player's when one drives this view</b>,
     * and this view's own otherwise, so there is one answer rather than two that can disagree.
     * Mutable: install a {@link VideoClock.MasterClock} on it to slave the video to an audio
     * position, or pause it to freeze the picture. One clock per view: two views deciding on the
     * same instance would each move the other's timeline.
     */
    public VideoClock clock() {
        return player != null ? player.clock() : clock;
    }

    /**
     * Replaces the pacing clock, e.g. with one a test drives.
     *
     * @throws IllegalStateException if a {@linkplain #setPlayer player} drives this view, whose
     *                               clock is the one that decides; silently ignoring this would
     *                               leave a caller holding a clock that paces nothing
     */
    public VideoView setClock(VideoClock newClock) {
        Ui.checkUiThread();
        if (player != null) {
            throw new IllegalStateException(
                    "this VideoView is driven by a MediaPlayer; set the clock on the player");
        }
        clock = Objects.requireNonNull(newClock, "newClock");
        return this;
    }

    /**
     * The stream's own size at one point per pixel, or the {@linkplain #setPreferredSize preferred
     * size} when one is set, clamped by the constraints, and {@code 0 × 0} with no stream at all,
     * because a view with nothing to show asks for no room. The stream answers its size at open, so
     * this works before a single picture has been decoded; it keeps answering the stream's declared
     * size afterwards, so a resolution change mid-stream re-letterboxes rather than relaying out.
     *
     * <p>The constraints win: a box too small, too large or too narrow for what was asked simply
     * clamps, and it is the letterbox that puts the aspect ratio back; measuring does not preserve
     * it, because a parent that stretches one axis has already decided that axis.
     */
    @Override
    protected Size onMeasure(Constraints constraints) {
        VideoStreamSource measured = stream();
        // Displayed rather than stored: a recording made sideways asks for the box it will be seen
        // in, and one that asked for its stored size would letterbox a portrait picture inside a
        // landscape box, exactly as wrong as not rotating it at all.
        boolean quarter = measured != null && isQuarterTurn(measured.rotationDegrees());
        float natural = measured == null ? 0 : (quarter ? measured.height() : measured.width());
        float across = measured == null ? 0 : (quarter ? measured.width() : measured.height());
        float w = preferredWidth >= 0 ? preferredWidth : natural;
        float h = preferredHeight >= 0 ? preferredHeight : across;
        return constraints.constrain(w, h);
    }

    /** @return whether {@code degrees} swaps the picture's two axes */
    private static boolean isQuarterTurn(int degrees) {
        return degrees == 90 || degrees == 270;
    }

    @Override
    protected void onPaint(Canvas canvas) {
        // FIRST, and before every early return below. The box is reserved and laid out on all of
        // them (no backend, no stream, a decode that threw), and a view that painted nothing on
        // any of those leaves whatever is behind it showing through a rectangle it has already
        // taken. That reads as a hole in the window rather than as a player with nothing in it,
        // and it is what the notices are drawn ON.
        canvas.fillRect(0, 0, width(), height(), letterbox);
        if (!VideoSurfaces.isAvailable()) {
            paintNotice(canvas, ComponentStrings.VIDEO_NO_BACKEND.get());
            return;
        }
        boolean hasContent = source != null || player != null;
        if (hasContent && failure() == null) {
            if (surface == null) {
                surface = VideoSurfaces.create(); // a surface belongs to the window rendering now
            }
            if (!primed) {
                // The first picture is taken here rather than waiting for the first periodic
                // callback, so a single rendered frame (a screenshot) already shows one.
                primed = true;
                advance();
            }
            uploadIfDue();
        }
        if (failure() != null) {
            paintNotice(canvas, ComponentStrings.VIDEO_DECODE_FAILED.get());
            return;
        }
        if (!hasContent) {
            return; // an empty player, which is what it is
        }
        if (uploaded && surface != null && surface.hasPicture()) {
            drawPicture(canvas);
        }
        startTicking();
    }

    /**
     * Uploads the picture the clock released, and only here. A partial frame paints one pass per
     * damage rectangle, so this runs more than once in a frame; dropping the reference is what keeps
     * the second pass from uploading again over a quad the first pass has already queued, which the
     * composite would resolve by showing the newer picture in the older pass.
     */
    private void uploadIfDue() {
        if (!due || picture == null) {
            return;
        }
        try {
            surface.upload(picture);
            uploaded = true;
        } catch (RuntimeException error) {
            failure = error;
        } finally {
            // Exactly once, immediately: the upload reads the samples and retains nothing, and a
            // picture never handed back costs the producer a pooled slot for good.
            releasePicture();
        }
    }

    /**
     * The picture's rectangle inside the box, in logical points. The binding axis is chosen by
     * comparing the two aspect ratios as a cross product rather than as a pair of divisions, and the
     * bound axis is then taken from the box <em>exactly</em>: computing both extents from a scale
     * factor leaves a 16:9 picture in a 16:9 box a rounding error short of one edge, which shows as a
     * one-sided hairline bar. What is left of the other axis is snapped away below half a device
     * pixel, where it cannot be drawn but can still be seen as an uneven pair of bars.
     */
    private void drawPicture(Canvas canvas) {
        float boxW = width();
        float boxH = height();
        float storedW = surface.widthPx();
        float storedH = surface.heightPx();
        if (boxW <= 0 || boxH <= 0 || storedW <= 0 || storedH <= 0) {
            return;
        }
        VideoStreamSource shown = stream();
        int rotation = shown == null ? 0 : shown.rotationDegrees();
        // The letterbox is solved in DISPLAYED dimensions and the quad is then drawn in stored
        // ones under a rotation, so the fit compares the ratio a viewer will see. Solving it in
        // stored dimensions and rotating afterwards puts the picture outside the box on the axis
        // the rotation swapped.
        boolean quarter = isQuarterTurn(rotation);
        float pictureW = quarter ? storedH : storedW;
        float pictureH = quarter ? storedW : storedH;
        float drawW;
        float drawH;
        if (fit == Fit.FILL) {
            drawW = boxW;
            drawH = boxH;
        } else {
            boolean widthBinds = fit == Fit.COVER
                    ? boxW * pictureH >= boxH * pictureW
                    : boxW * pictureH <= boxH * pictureW;
            if (widthBinds) {
                drawW = boxW;
                drawH = boxW * pictureH / pictureW;
            } else {
                drawH = boxH;
                drawW = boxH * pictureW / pictureH;
            }
        }
        float epsilon = 0.5f / Math.max(1f, canvas.contentScale());
        if (Math.abs(drawW - boxW) < epsilon) {
            drawW = boxW;
        }
        if (Math.abs(drawH - boxH) < epsilon) {
            drawH = boxH;
        }
        float x = (boxW - drawW) / 2;
        float y = (boxH - drawH) / 2;
        boolean clipped = fit == Fit.COVER;
        if (clipped) {
            canvas.save();
            // Before the rotation on purpose: the clip is the widget's own box, which is
            // axis-aligned in this space and would be an approximation in the rotated one.
            canvas.clipRect(0, 0, boxW, boxH);
        }
        try {
            if (rotation == 0) {
                canvas.drawSurface(surface, x, y, drawW, drawH);
                return;
            }
            canvas.save();
            try {
                // About the centre of the displayed rectangle, then draw the stored picture into
                // the rectangle the rotation maps onto it: at a quarter turn the extents swap, so
                // what is drawn is drawH × drawW centred on the same point.
                canvas.translate(x + drawW / 2, y + drawH / 2);
                canvas.rotate((float) Math.toRadians(rotation));
                float quadW = quarter ? drawH : drawW;
                float quadH = quarter ? drawW : drawH;
                canvas.drawSurface(surface, -quadW / 2, -quadH / 2, quadW, quadH);
            } finally {
                canvas.restore();
            }
        } finally {
            if (clipped) {
                canvas.restore();
            }
        }
    }

    /** The "no GPU backend" and "cannot be played" chrome, the only part of this widget with a size step. */
    /**
     * A notice over the picture area, drawn as a fixed tooltip: a pill the size of its text,
     * centred on both axes, in the same fill, border, radius and padding the hover tooltips use.
     *
     * <p>It used to fill the whole box with a raised panel, which hid the background and made an
     * eight-word message look like a broken player rather than like a player with something to
     * say. A pill leaves the black around it, so what a viewer sees is a video panel with a label
     * on it, the shape every player uses for exactly this.
     *
     * <p><b>The pill does not mirror, and that is a decision rather than an omission.</b> It is
     * centred on both axes, so every x here is derived from a centre and there is no leading edge
     * in the arithmetic for a direction to reflect: reflecting a centred box about the middle of
     * the same box returns the box. What the notice does owe the direction axis is its
     * <em>shaping</em>, below, because the message is a translated string and the picture behind
     * it is not.
     */
    private void paintNotice(Canvas canvas, String message) {
        Theme theme = Theme.current();
        // Resolved on the branch that draws it, and this is where the direction is resolved too:
        // the video path sizes nothing from the step, reads no direction, and would pay a lookup
        // per picture for values it never uses.
        SizeTokens tokens = theme.tokensFor(this);
        Font font = tokens.label();
        // Shaped rather than measured, so the notice is typeset for the direction this view reads
        // in: an Arabic message measured against a left-to-right base is a pill sized for a line
        // nobody drew. The width used for the pill and the width used to centre the text are then
        // one value from one shaping call, which is what keeps the two from disagreeing by the
        // fraction of a point a re-measure can cost.
        ShapedText line = textRuler().shape(message, font,
                ShapedText.Direction.of(message, neutralBase()));
        TextMetrics metrics = line.metrics();
        float padX = tokens.tooltipPadH();
        float padY = tokens.tooltipPadV();
        // Never wider than the box: a long message in a narrow view would otherwise hang out of
        // both sides, which is the one place a notice must not add to the confusion.
        float pillWidth = Math.min(width(), metrics.width() + 2 * padX);
        float pillHeight = Math.min(height(), metrics.height() + 2 * padY);
        float left = (width() - pillWidth) / 2;
        float top = (height() - pillHeight) / 2;
        canvas.fillRoundRect(left, top, pillWidth, pillHeight, tokens.radiusSmall(),
                theme.surfaceRaised);
        canvas.drawRoundRect(left + 0.5f, top + 0.5f, pillWidth - 1, pillHeight - 1,
                tokens.radiusSmall(), Strokes.BORDER, theme.outline);
        // Centred inside an already-centred pill: unchanged in either direction.
        canvas.drawText(line, left + (pillWidth - metrics.width()) / 2,
                top + (pillHeight - metrics.height()) / 2 + metrics.ascent(), theme.text);
    }

    /**
     * Judges the picture in hand and keeps one decoded behind it. Never blocks and never spins:
     * {@link VideoStreamSource.Read#PENDING} (whether the decoder is still working or every pooled
     * slot is held) simply ends that attempt, and the next callback asks again.
     *
     * <p><b>The successor is what makes this keep time.</b> The clock only drops a picture in favour
     * of one whose own moment has also arrived, so a caller that never names a successor is a caller
     * whose pictures are never dropped, and a view that cannot drop cannot catch up. It falls
     * behind by whatever it could not decode or present in time and stays there, which is not a
     * stutter: it is the whole film playing slower than it should, with the position readout
     * running away from the picture. Reading one ahead turns that into the ordinary answer, the same
     * one a {@link limn.video.MediaPlayer} gives: the late picture goes unshown and the next takes
     * its place.
     *
     * <p>The read-ahead is best-effort by construction. A source with a single pooled slot answers
     * {@code PENDING} to it forever, and this then behaves exactly as it did without one: no
     * successor, therefore no drops.
     */
    private void advance() {
        if (player != null) {
            takeFromPlayer();
            return;
        }
        if (source == null || ended || failure != null || due) {
            return;
        }
        try {
            if (picture == null) {
                if (ahead != null) {
                    picture = ahead; // promoted, not re-read: it was decoded on an earlier tick
                    ahead = null;
                } else if (drained) {
                    finishOrLoop();
                    return;
                } else {
                    switch (source.readFrame()) {
                        case FRAME -> picture = source.frame();
                        case PENDING -> {
                            return;
                        }
                        case END -> {
                            drained = true;
                            finishOrLoop();
                            return;
                        }
                        default -> throw new IllegalStateException("unreachable");
                    }
                    if (picture == null) {
                        return; // a source reporting a frame it does not have; treat it as pending
                    }
                }
            }
            if (paused) {
                if (!uploaded) {
                    // The first picture of a source goes up even when the view is not playing: a
                    // video that starts paused is a still frame, and an empty box reads as a
                    // decoder that produced nothing. The clock is not consulted (it would hold,
                    // being paused), so it anchors on the first picture judged after play starts.
                    due = true;
                    invalidate();
                }
                return; // otherwise: one picture decoded and held, nothing judged
            }
            long pts = picture.ptsMicros();
            if (pts == VideoFrame.PTS_UNKNOWN) {
                // A stream with no timing at all cannot be paced: show one picture per repaint,
                // which is the fastest honest answer and the one the clock would refuse to give (it
                // rejects the unknown-timestamp sentinel rather than doing arithmetic on it). No
                // read-ahead either: there is no moment for a successor to have arrived at.
                due = true;
                invalidate(); // a periodic callback adds no damage of its own
                return;
            }
            for (int skipped = 0; ; skipped++) {
                if (ahead == null && !drained) {
                    switch (source.readFrame()) {
                        case FRAME -> ahead = source.frame();
                        case PENDING -> {
                        }
                        case END -> drained = true;
                        default -> throw new IllegalStateException("unreachable");
                    }
                }
                long nextPts = ahead != null ? ahead.ptsMicros() : VideoClock.NO_PTS;
                VideoClock.Decision decision = clock.decide(pts, nextPts);
                if (decision == VideoClock.Decision.HOLD) {
                    return;
                }
                if (decision == VideoClock.Decision.PRESENT || skipped >= MAX_CATCH_UP) {
                    // Past the budget the picture is shown however late it is. Something on screen
                    // that is behind beats nothing on screen at all, and the next tick carries on
                    // catching up, which is what keeps a stream the machine cannot decode at its
                    // own rate moving instead of freezing on one picture.
                    due = true;
                    invalidate(); // a periodic callback adds no damage of its own
                    return;
                }
                // Released where it is dropped rather than in a paint, because no paint will ever
                // see it.
                picture.release();
                picture = ahead;
                ahead = null;
                if (picture == null) {
                    return; // nothing decoded to judge; the next tick reads again
                }
                pts = picture.ptsMicros();
                if (pts == VideoFrame.PTS_UNKNOWN) {
                    due = true;
                    invalidate();
                    return;
                }
            }
        } catch (RuntimeException error) {
            failure = error;
            releasePictures();
            invalidate();
        }
    }

    /**
     * Pictures a single tick may drop while catching up. Bounded because the catch-up runs on the UI
     * thread inside a frame: an unbounded loop would spend the whole frame in a decoder trying to
     * reach a position it may not be able to reach at all, and would trade a slow picture for a
     * frozen window. Eight is several frames of ordinary jitter and a small fraction of a frame's
     * budget at any rate a decoder here manages.
     */
    private static final int MAX_CATCH_UP = 8;

    /** The source has no more pictures and neither have we: go round again, or stop. */
    private void finishOrLoop() {
        if (looping && source.canReset()) {
            source.reset();
            clock.reset();
            drained = false;
        } else {
            ended = true;
        }
    }

    /**
     * Takes the picture a player says is due, which is all a view driven by one does: the player
     * decoded it on its own thread, timed it against whatever is mastering, and dropped anything
     * whose moment had passed. A picture arrives already due, so it waits only for the upload.
     */
    private void takeFromPlayer() {
        if (due || picture != null || failure != null) {
            return; // still holding one, and a second would have nowhere to go until it is uploaded
        }
        VideoFrame taken = player.takePicture();
        if (taken == null) {
            return; // nothing due, nothing decoded yet, paused, ended or failed: all the same here
        }
        picture = taken;
        due = true;
        invalidate(); // a periodic callback adds no damage of its own
    }

    /**
     * Gives back the picture being shown, and only it. The read-ahead is deliberately kept: it is
     * the next picture of this same stream and the source will not hand it over twice, so releasing
     * it here would skip a picture on every frame shown and, with the stream then a picture ahead
     * of the clock, hold until the timeline caught up with it.
     */
    private void releasePicture() {
        if (picture != null) {
            picture.release();
            picture = null;
        }
        due = false;
    }

    /**
     * Gives back everything held, read-ahead included, for a stream that is being replaced,
     * repositioned or abandoned, where the picture after this one is no longer the picture after
     * this one.
     */
    private void releasePictures() {
        releasePicture();
        if (ahead != null) {
            ahead.release();
            ahead = null;
        }
        drained = false;
    }

    private void startTicking() {
        if (ticking || scene() == null || !isShowing() || !isRunnable()) {
            return;
        }
        if (isFrozen()) {
            // Registering a ticker asks for a frame by itself, so a paint that re-armed one here
            // would keep the loop at the display's rate through a pause: paint, register, ask for
            // a frame, unregister, paint. The poll is what watches instead.
            pollWhilePaused();
            return;
        }
        ticking = true;
        int generation = ++tickGeneration;
        scene().addTicker(dt -> tick(generation));
    }

    private boolean tick(int generation) {
        if (generation != tickGeneration) {
            // Superseded by a detach and a re-attach inside one frame: the old registration is still
            // in the scene's list, and letting both run would decode two pictures per frame forever.
            return false;
        }
        if (!isShowing() || !isRunnable()) {
            // Tested before decoding, not after: a view scrolled away or in an unselected tab must
            // cost nothing at all, and one last picture decoded on the way out is not nothing.
            ticking = false; // re-armed by the next paint, when it is showing again
            return false;
        }
        if (isFrozen()) {
            // A registered ticker asks the scene for a frame every frame, whether or not anything
            // it drives has moved; that is what makes an animation an animation. A frozen picture
            // moves nothing, so a ticker left running here costs the whole display's refresh rate
            // to redraw pixels that cannot change, and the frames it asks for carry no damage at
            // all. Stop, and let the poll below notice a resume.
            ticking = false;
            pollWhilePaused();
            return false;
        }
        advance();
        return true;
    }

    /**
     * Paused <em>and</em> with nothing left to put on screen, the state in which no tick can
     * change anything.
     *
     * <p>The two halves differ by who decodes. A paused <b>stream</b> is paced by this view, so it
     * must keep ticking until it has decoded and shown the first picture: that poster is what makes
     * a paused video a still frame rather than an empty box. A paused or unstarted <b>player</b>
     * hands over nothing at all until something starts it, so there is nothing to wait for.
     */
    private boolean isFrozen() {
        return isPaused() && (player != null || uploaded);
    }

    /**
     * Asks, a few times a second, whether the pause is over: the cost of not ticking while paused.
     *
     * <p>{@link #setPaused} re-arms directly, so this is not what resumes an ordinary pause; it is
     * for the one this view cannot see coming, a {@link MediaPlayer} resumed by the application
     * through the player rather than through the view, which {@link #isPaused()} deliberately
     * reports. Polling on a timer costs a handful of wake-ups a second and asks for no frames,
     * where the ticker it replaces asked for every frame the display could give.
     */
    private void pollWhilePaused() {
        if (polling || player == null) {
            // With no player there is nothing outside this view that can resume it (setPaused is
            // the only way back and it re-arms the ticker itself), so this poll could only ever
            // observe the state it was armed in and re-arm itself, for as long as the view is
            // paused. A timer that can read nothing is a wake-up a second, forever, for nothing.
            return;
        }
        polling = true;
        int generation = tickGeneration;
        Ui.postDelayed(() -> {
            polling = false;
            if (generation != tickGeneration || ticking || scene() == null) {
                return; // superseded, already ticking again, or detached
            }
            if (!isShowing() || !isRunnable()) {
                return; // the next paint re-arms; nothing to poll for meanwhile
            }
            if (isPaused()) {
                pollWhilePaused();
                return;
            }
            startTicking();
        }, PAUSED_POLL_MILLIS);
    }

    /**
     * Whether there is anything left to ask for. A paused player is still runnable (the tick that
     * costs a null answer is what notices the resume), but an ended or failed one is not, and
     * neither is a view with nothing to show.
     */
    private boolean isRunnable() {
        if (failure() != null) {
            return false;
        }
        if (player != null) {
            return !player.isEnded();
        }
        return source != null && !ended;
    }

    @Override
    protected void onDetached() {
        // scene() is still the scene being left, which owns the GL context the surface belongs to:
        // hand it over and it is freed at that scene's next frame, since GPU disposal needs the
        // context and a detach is not inside a frame.
        Scene leaving = scene();
        if (leaving != null && surface != null) {
            leaving.disposeLater(surface);
        }
        surface = null;
        uploaded = false;
        primed = false;
        releasePictures();
        ticking = false;
        tickGeneration++;
        // The view's own timeline is forgotten rather than left running while nothing is on screen:
        // a view re-attached a minute later would otherwise be a minute behind and would race to
        // catch up. A PLAYER's clock is untouched, because a player detached from one view may be
        // showing in another and its soundtrack is still sounding either way.
        clock.reset();
        // The source and the player are the caller's. Neither is closed, rewound or stopped, and a
        // stream shown directly is not read again until this view is attached and painted; a
        // re-attached view therefore resumes from where it stopped and shows nothing until the next
        // picture arrives.
    }
}
