package limn.demo;

import limn.components.BackdropPanel;
import limn.components.Button;
import limn.components.Label;
import limn.components.ScrollView;
import limn.components.SegmentedControl;
import limn.components.Theme;
import limn.components.SizeTokens;
import limn.components.VideoView;
import limn.icons.tabler.TablerMedia;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.concurrent.Job;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import limn.video.Videos;
import limn.video.decode.SyntheticPattern;
import limn.video.decode.SyntheticSpec;
import limn.video.decode.SyntheticVideoDecoder;
import limn.video.decode.Y4mWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The video tab: pictures that come out of a decoder, cross the SPI as planar samples and are
 * composited as an ordinary 2D quad by {@link VideoView}. Everything here is picked to make a
 * regression <em>visible</em>: the bars carry the studio code table, so a wrong matrix or range
 * reads as the wrong bars rather than as nothing; the odd-size entries are where a chroma plane
 * could be rounded one way by the producer and another by the shader, which shows as a coloured
 * stripe down one edge and nowhere else.
 *
 * <p>The widget takes a stream and draws it; opening and closing one is the application's, so that
 * is what this scene does: {@link Videos#openAsync} on a pick, {@code close} on the next. A pick
 * costs the window one frame and not the half second the container takes, and the picture's own
 * box says what is happening for as long as it takes.
 */
final class VideoScene {

    /**
     * The picture every view shows when the demo was told to stand still, or -1 to play. Process-wide
     * and set before any view is built, because it decides which stream a view is handed.
     */
    private static int staticFrame = -1;

    /**
     * Whether this run may start an audio device. A screenshot must not: the picture a player is
     * showing depends on where the soundtrack has reached, and two runs of the same command would
     * capture two different pictures. Process-wide and set before any view is built, for the same
     * reason {@link #staticFrame} is.
     */
    private static boolean soundAllowed = true;

    /**
     * Which entry of the source picker the tab opens on. Process-wide and set before any view is
     * built, for the same reason {@link #staticFrame} is: it decides which stream a view is handed.
     */
    private static int initialSource;

    private VideoScene() {
    }

    /**
     * Starts the tab on source {@code index} rather than the first, so that a capture can be taken
     * of a decoder other than the one that happens to be listed first. Out-of-range values are
     * clamped rather than refused: the list is a demo's and its length is not a contract.
     */
    static void setInitialSource(int index) {
        initialSource = Math.max(0, Math.min(index, SOURCES.size() - 1));
    }

    /**
     * Freezes every view on picture {@code index} instead of playing, which is what makes a screenshot of a
     * moving image reproducible, since the stream a view is handed then contains exactly that one
     * picture and nothing else, with no clock and no frame timing anywhere in the path.
     *
     * @param index the picture to show, or -1 to play
     */
    static void setStaticFrame(int index) {
        staticFrame = index;
    }

    /**
     * Forbids the soundtrack, and with it the player that carries it; a screenshot run then takes
     * exactly the path phase 4 shipped, so a capture depends on nothing but the pictures.
     */
    static void setSoundAllowed(boolean allowed) {
        soundAllowed = allowed;
    }

    /**
     * Installs the decoders an application ships. The backend installs none: this is the app's call.
     *
     * <p>The order is the probe order, and the two pure-Java decoders go first, not because they
     * would otherwise be shadowed, since no two of these claim the same extension, but because
     * asking a decoder with a native payload last means a machine without that payload does the
     * cheapest possible thing on every open.
     *
     * <p>The FFmpeg decoder is installed whether or not its library exists. That is the whole
     * point of {@code supports} being allowed to answer false: with no library it claims nothing,
     * and the decoders in front of it are reached exactly as if it had never been installed.
     */
    static void installDecoders() {
        Videos.installDecoder(new limn.video.decode.Y4mDecoder());
        Videos.installDecoder(new SyntheticVideoDecoder());
        Videos.installDecoder(new limn.video.ffmpeg.FfmpegVideoDecoder());
        // And their one-off preparation is paid here, on a worker, rather than by whoever asks
        // first. For the decoder with a native behind it that is tens of megabytes extracted and
        // linked under a lock, and the call that triggers it is as likely to be a file chooser
        // asking whether a clip is playable, which is synchronous by contract and cannot wait.
        // Started and dropped on purpose: there is nothing to deliver and nothing to withdraw.
        Videos.warmUpAsync().start();
    }

    static Scene create(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(20),
                tabContent(SCENE_PICTURE_WIDTH, SCENE_PICTURE_HEIGHT, SOURCES)));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    /**
     * What a source entry yields: the pictures, the soundtrack if it brought one of its own, and,
     * for a real container, the container itself, because that is what can be asked for another
     * of its audio tracks later. Everything synthetic brings none of that and is paired with the
     * generated click track.
     */
    private record Opened(VideoStreamSource video, limn.sound.AudioStreamSource audio,
                          limn.video.ffmpeg.FfmpegMedia media) {

        List<limn.video.ffmpeg.FfmpegMedia.AudioTrack> audioTracks() {
            return media == null ? List.of() : media.audioTracks();
        }

        List<limn.video.ffmpeg.FfmpegMedia.SubtitleTrack> subtitleTracks() {
            return media == null ? List.of() : media.subtitleTracks();
        }
    }

    /** One entry of the source picker: a label, a caption and how to open it. */
    private record Source(String label, String caption, Opener opener) {
    }

    /**
     * How a picker entry opens, in the two shapes this tab needs: on the calling thread while the
     * tab is being built, and as a job once there is a window that must not freeze.
     */
    private interface Opener {

        /**
         * Opens on the calling thread and takes as long as the decoder takes: the form for the
         * build, where the window has not appeared yet and there is no frame to lose.
         *
         * @return what was opened, or null when this machine cannot play this entry at all
         */
        Opened openNow();

        /**
         * Starts the same open on a worker, reporting back to {@code pick} on the UI thread.
         *
         * @return the handle, so that the next pick can withdraw this one, and a withdrawn open
         *         closes whatever it had already produced instead of leaking it
         */
        Job start(Pick pick);
    }

    /**
     * Where an open in flight reports back. Every one of these is called on the UI thread and may
     * touch widgets directly.
     *
     * @param opened   what was opened, or null when this machine cannot play the entry
     * @param failed   what the decoder threw, unwrapped
     * @param progress the newest fraction the open published, 0 to 1; most opens publish none
     * @param wanted   whether the answer is still wanted; a false closes what was opened
     */
    private record Pick(java.util.function.Consumer<Opened> opened,
                        java.util.function.Consumer<Throwable> failed,
                        java.util.function.DoubleConsumer progress,
                        java.util.function.BooleanSupplier wanted) {
    }

    /**
     * The ordinary case: a path that costs nothing to name, opened through the facade in install
     * order, and through the facade's own job, which brings the probe order, the message naming
     * every decoder it asked, and the guard that closes a stream nobody received.
     *
     * <p>{@code path} is evaluated on the calling thread, so it must be arithmetic rather than
     * I/O. An entry that has to <em>produce</em> its input first cannot come through here at all,
     * because the facade's asynchronous form takes a path and the path would then be made on the
     * UI thread; {@link #writingFirst} is that case.
     */
    private static Opener viaFacade(java.util.function.Supplier<Path> path) {
        return new Opener() {
            @Override
            public Opened openNow() {
                return new Opened(Videos.open(path.get()), null, null);
            }

            @Override
            public Job start(Pick pick) {
                return Videos.openAsync(path.get())
                        .onSuccess(source -> pick.opened().accept(new Opened(source, null, null)))
                        .onFailure(pick.failed())
                        .onProgress(pick.progress())
                        .deliverIf(pick.wanted())
                        .start();
            }
        };
    }

    /**
     * An entry that has to write its input before there is anything to open. The fixture is most
     * of what such a pick costs, so it goes on the same worker as the open rather than in front
     * of it, on the UI thread.
     */
    private static Opener writingFirst(java.util.function.Supplier<Path> file) {
        return onAWorker(() -> new Opened(Videos.open(file.get()), null, null));
    }

    /**
     * An entry whose open is this scene's own work rather than the facade's, because it produces
     * its input, or because it wants the container's other tracks, which the facade's shape has
     * nowhere to return.
     *
     * @param open runs on a worker; returns null for a source this machine cannot play at all
     */
    private static Opener onAWorker(java.util.function.Supplier<Opened> open) {
        return new Opener() {
            @Override
            public Opened openNow() {
                return open.get();
            }

            @Override
            public Job start(Pick pick) {
                return limn.concurrent.Ui.<Opened>work(progress -> open.get())
                        .onSuccess(pick.opened())
                        .onFailure(pick.failed())
                        .onProgress(pick.progress())
                        .deliverIf(pick.wanted())
                        // The leak guard, and it is not theoretical: clicking through the picker
                        // withdraws opens that have already produced a container, and this is
                        // what closes them. The facade's own form carries its own.
                        .onDiscarded(VideoScene::closeOpened)
                        .start();
            }
        };
    }

    /**
     * Closes what an open produced that nobody received. Called on a worker, where a close is
     * allowed to block.
     *
     * <p>The audio track first and the video track second, and the {@code finally} keeps that true
     * when the first close throws: closing the video track closes the container, so the other
     * order would leave the container open for as long as the process lives.
     */
    private static void closeOpened(Opened opened) {
        if (opened == null) {
            return; // an entry this machine cannot play produces nothing to close
        }
        try {
            if (opened.audio() != null) {
                opened.audio().close();
            }
        } finally {
            if (opened.video() != null) {
                opened.video().close();
            }
        }
    }

    /**
     * What the file chooser offers: the containers an installed decoder can claim. Y4M is in the
     * list because a machine that has not built the native still has the two pure-Java decoders,
     * and a chooser that offered only what the native handles would be wrong there.
     */
    private static final limn.backend.FileDialogs.Filter PLAYABLE =
            limn.backend.FileDialogs.Filter.of("Video", "*.mp4", "*.m4v", "*.mov", "*.3gp",
                    "*.mkv", "*.webm", "*.y4m");

    /**
     * The platform's chooser, or a sentence in {@code caption} when there is no native window to
     * put one over, which is every headless and screenshot run, where a dialog that blocked the UI
     * thread waiting for a click nobody can make would hang the capture.
     */
    private static java.util.Optional<Path> pick(Widget host, Label caption) {
        Scene scene = host.scene();
        if (scene == null || scene.window() == null) {
            caption.setText("No native window: the platform's file chooser is unavailable here.");
            return java.util.Optional.empty();
        }
        return scene.window().backend().fileDialogs().openFile("Open a video", null, PLAYABLE);
    }

    /**
     * Opens {@code file} in a window of its own, with the player filling the whole content area and
     * the window resizable: the shape an actual video player is, rather than a panel in a tab.
     *
     * <p>The view is the scene's <b>root</b>, which is what "fills the content" means here: a scene
     * lays its root out under tight constraints, so the view is exactly the window's content box at
     * every size and the letterbox puts the aspect ratio back inside it. Resizing the window is
     * therefore a re-letterbox and nothing else: no reallocation, no re-upload, because the picture
     * is in device pixels and only the rectangle it is filtered into changes.
     *
     * <p><b>This window owns what it opened.</b> The stream and the player are closed when it
     * closes, in that order and not the other one: the player's close returns only once its decode
     * thread has let go of the stream. It is registered against the window that opened it, so it can
     * never outlive the application; on that path the process is ending anyway and the daemon decode
     * thread goes with it.
     *
     * <p>The open is a job like the tab's, so a file chosen from a network volume does not freeze
     * the window that asked for it. Both answers land in {@code caption}: the sentence for a file
     * that will not open, and the one saying where it went when it does.
     */
    private static void openInOwnWindow(NativeWindow parent, Path file, boolean withSound,
                                        Label caption) {
        caption.setText("Opening " + file.getFileName() + "…");
        ownFile(file).opener().start(new Pick(
                opened -> {
                    if (opened == null) {
                        caption.setText("Cannot play " + file.getFileName() + ": "
                                + encodedUnavailableNotice());
                        return;
                    }
                    openWindowFor(parent, file, opened, withSound);
                    caption.setText("Playing " + file.getFileName() + " in a window of its own. "
                            + "The tab keeps what it was showing: the two are separate players "
                            + "over separate streams.");
                },
                failure -> caption.setText("Cannot play " + file.getFileName() + ": "
                        + describeFailure(failure)),
                fraction -> {
                    // Nothing to show it on: this window does not exist yet, and the caption is
                    // the tab's rather than this open's.
                },
                () -> caption.scene() != null));
    }

    /** Builds and shows the window for what {@link #openInOwnWindow} opened. UI thread. */
    private static void openWindowFor(NativeWindow parent, Path file, Opened opened,
                                      boolean withSound) {
        Playing open = new Playing();
        open.stream = opened.video();

        VideoView view = new VideoView().setLooping(true);
        if (withSound) {
            limn.sound.AudioStreamSource track = opened.audio() != null
                    ? opened.audio()
                    : new ClickTrack(TRACK_SECONDS);
            open.player = new limn.video.MediaPlayer(open.stream)
                    .setAudio(track, TRACK_OPTIONS);
            open.player.setLooping(true);
            view.setPlayer(open.player);
        } else {
            if (opened.audio() != null) {
                opened.audio().close(); // taken from the container and not handed to an engine
            }
            view.setSource(open.stream);
        }
        // The same pane the tab uses: the transport is drawn OVER the picture, so the video still
        // fills the window, and the player is started by the first paint rather than here: a
        // window that is never shown must not start a soundtrack.
        VideoPane pane = new VideoPane(view, open, new BackdropPanel(GLASS,
                Insets.symmetric(6, 10), new Transport(open, view, null)));

        Scene scene = new Scene(pane);
        scene.setBackground(limn.graphics.Color.BLACK);
        int[] box = windowBox(view, parent);
        NativeWindow window = parent.backend().createWindow(new WindowConfig(
                file.getFileName().toString(), box[0], box[1],
                false /* shown below, after binding */, true /* resizable, the point of this */));
        // A picture has an aspect ratio but no minimum; this one is the transport's, below which
        // the bar and the button overlap each other rather than the window refusing to shrink.
        window.setSizeLimits(240, 135, -1, -1);
        parent.registerChildPopup(window); // never outlives the application window
        scene.bind(window);
        window.setCloseRequestHandler(() -> {
            view.setPlayer(null);
            view.setSource(null);
            open.closeAll(); // the player first, then the stream it was reading
            parent.unregisterChildPopup(window);
            return true;
        });
        window.show();
        window.requestFrame();
    }

    /** What a failed open says to a viewer: its own sentence, or the type when it brought none. */
    private static String describeFailure(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.toString();
    }

    /**
     * The window box for {@code view}'s stream: the size the picture is <em>seen</em> at, scaled
     * down as a whole to fit the display it will open on.
     *
     * <p><b>The size is the view's own measure, not arithmetic over the stream.</b> A recording made
     * sideways is seen with its axes swapped, and restating that rule here is how the two come to
     * disagree, which is not a subtle failure: the window opens in the wrong orientation and the
     * picture letterboxes to a sliver inside it.
     *
     * <p><b>Both axes are scaled by one factor.</b> Capping them independently is the defect this
     * replaced: a portrait recording had its width capped and its height computed from the ratio
     * with no cap at all, so it asked for a window taller than the screen, and what the platform
     * does with that is squash it into a landscape frame, which looks exactly like rotation not
     * working.
     */
    private static int[] windowBox(VideoView view, NativeWindow parent) {
        limn.scene.Size shown = view.measure(limn.scene.Constraints.loose(100_000, 100_000));
        float width = Math.max(1, shown.width());
        float height = Math.max(1, shown.height());
        // Comfortably inside the usable area rather than filling it: a window opened at the size of
        // the screen has its title bar under the menu bar and cannot be moved.
        float maxWidth = 1280;
        float maxHeight = 720;
        limn.backend.Display display = parent.display();
        if (display != null) {
            float factor = Math.max(0.01f, parent.logicalToScreenFactor());
            maxWidth = Math.min(maxWidth, display.workArea().width() / factor * 0.85f);
            maxHeight = Math.min(maxHeight, display.workArea().height() / factor * 0.85f);
        }
        float scale = Math.min(1f, Math.min(maxWidth / width, maxHeight / height));
        return new int[] {
                Math.max(240, Math.round(width * scale)),
                Math.max(135, Math.round(height * scale))
        };
    }

    /**
     * A picker entry for a file the viewer chose. Nothing downstream knows the difference between
     * this and an entry that was compiled in, which is the whole point: Restart, the fits and the
     * soundtrack switch work on it with no special case.
     *
     * <p><b>The container is tried before the facade, and that ordering is the soundtrack.</b>
     * {@code Videos.open} returns video and only video (the decoder facade has no place to put a
     * second track), so a file opened through it plays silently however much audio is inside it.
     * The container type is where both tracks come from, so it is asked first and the facade is the
     * fallback for everything it cannot demultiplex.
     *
     * <p>An unreadable or undecodable file is not a crash: the opener answers null, which is the
     * same answer the MP4 entry gives on a machine with no native, and the tab already says so in
     * the picture's own box rather than in a stack trace.
     */
    private static Source ownFile(Path file) {
        return new Source(file.getFileName().toString(),
                "Playing " + file + ", opened through the decoders this application installed, "
                        + "in the order it installed them.",
                onAWorker(() -> {
                    // Both catches are on the worker, which is the point: the fallback is a
                    // second open of the same file, so the file a container declines costs two
                    // opens, and neither of them may be on the UI thread.
                    try {
                        return openContainer(file);
                    } catch (RuntimeException notAContainer) {
                        try {
                            return new Opened(Videos.open(file), null, null);
                        } catch (RuntimeException unplayable) {
                            return null;
                        }
                    }
                }));
    }

    private static final List<Source> SOURCES = List.of(
            new Source("Bars", "Colour bars in the studio BT.709 code table, 4:2:0. A wrong matrix "
                    + "or range does not read as \"slightly off\"; it reads as the wrong bars.",
                    viaFacade(() -> bars(SyntheticPattern.BARS).path())),
            new Source("Counter", "The same bars with the picture index over them, so a stream that "
                    + "is not stepping is visible without a stopwatch.",
                    viaFacade(() -> bars(SyntheticPattern.COUNTER).path())),
            new Source("Gradient", "Every channel moving one code per picture, one code per column, "
                    + "wrapping at the end of the code space, so it sawtooths back to black twice "
                    + "and a half across these 640 columns.",
                    viaFacade(() -> bars(SyntheticPattern.GRADIENT).path())),
            new Source("4:4:4", "Full-resolution chroma: three planes, no subsampling, so the bar "
                    + "edges carry no colour fringe at all.",
                    viaFacade(() -> bars(SyntheticPattern.BARS).withFormat(PixelFormat.I444).path())),
            new Source("NV12", "Two planes, with Cb and Cr interleaved a byte apart: what a "
                    + "hardware decoder hands back, and a different upload path.",
                    viaFacade(() -> bars(SyntheticPattern.COUNTER)
                            .withFormat(PixelFormat.NV12).path())),
            new Source("BT.601", "The same samples read through the standard-definition matrix. It "
                    + "should look wrong here: these are BT.709 codes.",
                    viaFacade(() -> bars(SyntheticPattern.BARS)
                            .withColor(VideoColor.BT601_LIMITED).path())),
            new Source("Full range", "The same samples read as full range. Studio black lifts and "
                    + "studio white loses its headroom.",
                    viaFacade(() -> bars(SyntheticPattern.BARS)
                            .withColor(VideoColor.BT709_FULL).path())),
            new Source("Odd size", "641 by 361: every chroma plane rounds up, so the last column "
                    + "and row are where a producer and a shader can disagree.",
                    viaFacade(() -> bars(SyntheticPattern.COUNTER).withSize(641, 361).path())),
            new Source("Y4M file", "The same pictures written to a real YUV4MPEG2 file in the "
                    + "temporary directory and read back, header, FRAME lines and all.",
                    writingFirst(VideoScene::y4mFile)),
            new Source("MP4", "A real encoded container, demultiplexed by a trimmed FFmpeg behind "
                    + "a JNI shim, with the soundtrack that is inside the file rather than the "
                    + "generated one. The clip is encoded into the temporary directory first, "
                    + "because nothing this demo can generate is shipped with it. Or set "
                    + "-Dlimn.demo.video to play a file of your own.",
                    onAWorker(VideoScene::openEncoded)),
            new Source("10-bit", "The Gradient entry's pattern in 10-bit 4:2:0, and the difference "
                    + "is countable: the same one-code-per-column ramp has four times as many "
                    + "codes to climb, so it crosses the whole picture ONCE instead of sawtoothing "
                    + "back to black twice and a half. That is what the extra bits are, made "
                    + "visible on a screen that has only eight of its own.",
                    viaFacade(() -> bars(SyntheticPattern.GRADIENT)
                            .withFormat(PixelFormat.I420_10LE).path())),
            new Source("P010", "The same 10-bit ramp in the layout a HARDWARE decoder produces: "
                    + "NV12's two planes at ten bits, with the code in the TOP ten bits of its "
                    + "sixteen-bit word rather than the bottom ten. Nothing about its geometry says "
                    + "so, which is the point: read as an ordinary 10-bit layout it would be 64 "
                    + "times too bright, and it must be indistinguishable from the 10-bit entry "
                    + "beside it instead.",
                    viaFacade(() -> bars(SyntheticPattern.GRADIENT)
                            .withFormat(PixelFormat.P010).path())));

    private static SyntheticSpec bars(SyntheticPattern pattern) {
        return SyntheticSpec.of(640, 360).withPattern(pattern).withRate(30, 1)
                .withFrameCount(GENERATED_SECONDS * RATE);
    }

    /**
     * How long a generated source runs before it loops. Finite, where the generator's own default is
     * endless, because a length is what a transport puts its thumb over: a stream that cannot say
     * how long it is cannot be scrubbed to a fraction of itself, and the tab would then demonstrate
     * a disabled control. Longer than the click track on purpose, so that the handover to the wall
     * clock at the track's end still happens before the pictures wrap.
     */
    private static final int GENERATED_SECONDS = 60;

    /**
     * Writes a short stream to a temporary YUV4MPEG2 file the first time it is asked for. Nothing
     * this demo can generate is committed, so the only honest way to exercise the file reader is
     * to make a file first.
     */
    private static Path y4mFile() {
        if (y4mCache != null) {
            return y4mCache;
        }
        try {
            Path file = Files.createTempFile("limn-demo-", ".y4m");
            file.toFile().deleteOnExit();
            try (VideoStreamSource source = SyntheticVideoDecoder.open(
                    bars(SyntheticPattern.COUNTER).withFrameCount(90))) {
                Y4mWriter.write(file, source, 90);
            }
            y4mCache = file;
            return file;
        } catch (IOException error) {
            throw new UncheckedIOException("cannot write the demo's Y4M file", error);
        }
    }

    private static Path y4mCache;

    // ------------------------------------------------------------------ the encoded clip

    /** Where a real encoded file comes from, so that none has to be committed. */
    private static final String OWN_FILE_PROPERTY = "limn.demo.video";

    private static Path mp4Cache;

    /**
     * Opens a real MP4.
     *
     * <p>Three outcomes, and the awkward one is the common one. If the application was pointed at
     * a file with {@code -Dlimn.demo.video}, that file is played, which is the use case this
     * decoder exists for, somebody's own media. Otherwise, if this build of FFmpeg can encode, a
     * clip is written to the temporary directory and read back, the same way the Y4M entry writes
     * a Y4M: what this build can generate, it generates rather than carrying. The repository does
     * carry a small licensed corpus for the codecs nothing here can encode, but it is test
     * evidence and the demo does not reach for it; point {@code -Dlimn.demo.video} at one of
     * those files to watch H.264 here.
     *
     * <p>And if neither holds (which is every machine running the payload that ships, because
     * the shipped build has no encoder — only a {@code full} build from a sibling clone of
     * limn-ffmpeg-natives has one), this returns null and the tab says so. It does not throw:
     * a decoder that is not installed is an ordinary state of the world, not a failure, and the
     * other entries are unaffected by it.
     */
    private static Opened openEncoded() {
        String own = System.getProperty(OWN_FILE_PROPERTY);
        if (own != null && !own.isBlank()) {
            Path file = Path.of(own);
            if (!Files.isReadable(file)) {
                return null;
            }
            return openContainer(file);
        }
        if (!limn.video.ffmpeg.FfmpegMedia.canWriteClip()) {
            return null;
        }
        if (mp4Cache == null) {
            try {
                Path file = Files.createTempFile("limn-demo-", ".mp4");
                file.toFile().deleteOnExit();
                Files.deleteIfExists(file);
                // Small and short on purpose. The encode is on a worker and no longer freezes the
                // window, but it is still what the viewer waits for before there is a picture, and
                // the view scales whatever it gets to its box, so a smaller clip looks the same
                // and arrives in a fraction of the time.
                // TWO soundtracks, a fifth apart in pitch and tagged as two languages, because a
                // track picker with one entry demonstrates nothing. A file somebody points
                // -Dlimn.demo.video at will have its own; this is what there is without one.
                limn.video.ffmpeg.FfmpegMedia.writeClip(file,
                        limn.video.ffmpeg.FfmpegMedia.ClipCodec.MPEG4,
                        320, 180, RATE * 5, RATE, 1,
                        List.of(new limn.video.ffmpeg.FfmpegMedia.ClipAudioTrack(2, "eng"),
                                new limn.video.ffmpeg.FfmpegMedia.ClipAudioTrack(2, "fra")),
                        44_100,
                        // And two subtitle tracks, for the same reason as the two soundtracks: a
                        // picker with one entry demonstrates nothing. The cues are the writer's
                        // own (each names its track and its index), which is what makes a wrong
                        // track or a stale cue visible in the capture rather than plausible.
                        List.of("eng", "fra"));
                mp4Cache = file;
            } catch (IOException error) {
                throw new UncheckedIOException("cannot write the demo's MP4", error);
            }
        }
        return openContainer(mp4Cache);
    }

    /**
     * Opens a container for both of its tracks, which the facade's entry point cannot express:
     * {@code Videos.open} returns video and only video, so an application that wants the
     * soundtrack asks the module that demultiplexed it.
     */
    private static Opened openContainer(Path file) {
        limn.video.ffmpeg.FfmpegMedia media = limn.video.ffmpeg.FfmpegMedia.open(file);
        // Closing the video track closes the container, which is what Playing.closeAll does, so
        // the container is carried here to be ASKED things (which of its audio tracks there are,
        // and for another one) and never to be closed.
        return new Opened(media.video(), media.audio(), media);
    }

    /**
     * What goes ON the picture: a few words, no paths, no flags, no library names.
     *
     * <p>Whoever is looking at the panel wants to know that this one will not play and that the
     * others will. What to type to fix it is a different question asked by a different person, and
     * it goes in the caption below (see {@link #encodedUnavailableDetail()}).
     */
    private static String encodedUnavailableNotice() {
        String own = System.getProperty(OWN_FILE_PROPERTY);
        if (own != null && !own.isBlank()) {
            return "This file cannot be opened";
        }
        return "MP4 playback is not available";
    }

    /** And what goes below it: the sentence for whoever is building this, with the fix in it. */
    private static String encodedUnavailableDetail() {
        String own = System.getProperty(OWN_FILE_PROPERTY);
        if (own != null && !own.isBlank()) {
            return "Cannot read " + own + " (-D" + OWN_FILE_PROPERTY + ").";
        }
        String reason = limn.video.ffmpeg.FfmpegVideoDecoder.unavailableReason();
        if (reason != null) {
            return "The FFmpeg decoder is not installed on this machine: " + reason
                    + "  Add the limn-ffmpeg-natives classifier for this platform, or set -D"
                    + OWN_FILE_PROPERTY + " to a file you already have.";
        }
        return "The FFmpeg decoder is installed but this build has no encoder, so it cannot make "
                + "a clip to play; the payload that ships is decode-only. A 'full' build from a "
                + "sibling clone of limn-ffmpeg-natives (scripts/build-ffmpeg.sh --profile full) "
                + "is picked up automatically, or set -D" + OWN_FILE_PROPERTY
                + " to a file you already have.";
    }

    // ------------------------------------------------------------------ the soundtrack

    /** Pictures a second every source here runs at, and therefore what one click apart means. */
    private static final int RATE = 30;

    /**
     * The soundtrack: a click on every second, a higher one every fifth, over a quiet tone,
     * computed from the frame index and nothing else, so it needs no file and no decoder.
     *
     * <p>It is deliberately not pleasant. A soundtrack is only useful here if a viewer can tell
     * whether it is in time with the picture, and the counter pattern draws the picture index: at
     * thirty pictures a second a click lands exactly as the counter reaches a multiple of thirty,
     * so being a fifth of a second out is something you can hear rather than something you have to
     * measure. Music would be worth less.
     *
     * <p>It also exists to be handed over already open: a video's audio track has no file of its
     * own, which is the shape {@link limn.sound.Sounds#stream(limn.sound.AudioStreamSource,
     * limn.sound.PlayOptions)} was added for.
     */
    private static final class ClickTrack implements limn.sound.AudioStreamSource {

        private static final int SAMPLE_RATE = 44_100;
        private static final int CLICK_FRAMES = SAMPLE_RATE / 50; // 20 ms

        private final long totalFrames;
        private long position;

        ClickTrack(int seconds) {
            this.totalFrames = (long) seconds * SAMPLE_RATE;
        }

        @Override
        public int channels() {
            return 2;
        }

        @Override
        public int sampleRate() {
            return SAMPLE_RATE;
        }

        @Override
        public int readFrames(short[] out, int maxFrames) {
            int frames = (int) Math.min(maxFrames, totalFrames - position);
            for (int i = 0; i < frames; i++) {
                long frame = position + i;
                long intoSecond = frame % SAMPLE_RATE;
                long second = frame / SAMPLE_RATE;
                double value = 0.06 * Math.sin(2 * Math.PI * 110 * frame / SAMPLE_RATE);
                if (intoSecond < CLICK_FRAMES) {
                    // Decaying burst: a fifth of a second of drift is audible against the counter.
                    double decay = 1 - intoSecond / (double) CLICK_FRAMES;
                    double tone = second % 5 == 0 ? 1760 : 880;
                    value += 0.55 * decay * decay
                            * Math.sin(2 * Math.PI * tone * intoSecond / SAMPLE_RATE);
                }
                short sample = (short) (Math.max(-1, Math.min(1, value)) * Short.MAX_VALUE);
                out[i * 2] = sample;
                out[i * 2 + 1] = sample;
            }
            position += frames;
            return frames;
        }

        @Override
        public void reset() {
            position = 0;
        }

        @Override
        public void close() {
        }
    }

    /** Everything the tab keeps between source switches, so a switch closes exactly what it opened. */
    private static final class Playing {
        VideoStreamSource stream;
        limn.video.MediaPlayer player;
        /**
         * The container the stream came out of, when it came out of one. Held to be asked for
         * another of its audio tracks and never to be closed: closing the video track closes the
         * container, which is what {@link #closeAll} already does.
         */
        limn.video.ffmpeg.FfmpegMedia media;

        /**
         * The open in flight, or null. Held so that the next pick can withdraw it: two opens race
         * by construction here (a container takes half a second and a viewer clicks through the
         * picker faster than that), and what has to be shown is the entry last <em>asked</em> for
         * rather than the open that happened to finish last. Cancelling stops the delivery and not
         * the decoder, which is parked in a read nothing interrupts; what it produces after that
         * is closed on a worker by the job's own discard guard.
         */
        Job opening;

        void closeAll() {
            if (opening != null) {
                opening.cancel(); // before the closes below: this one is not ours to close yet
                opening = null;
            }
            if (player != null) {
                player.close(); // returns only once the decode thread has let go of the stream
                player = null;
            }
            if (stream != null) {
                stream.close(); // opened here, so closed here, never by the widget or the player
                stream = null;
            }
            media = null; // closed by the line above, because closing the video track closes it
        }
    }

    /**
     * The picture area: the view, or, when there is nothing to show it, the reason, in the same
     * box and in the same place.
     *
     * <p>It exists because of two separate mistakes, and it is worth saying which, because both are
     * easy to make again.
     *
     * <p><b>An explanation nobody can see is not an explanation.</b> The reason a source could not be
     * opened used to go into the caption below the picture. In this tab, inside a Kitchen Sink whose
     * chrome leaves the panel a couple of hundred points tall, that line is below the fold: the
     * viewer sees an empty rectangle, no message, and no way to find out that the FFmpeg native
     * simply is not built on their machine. The message belongs where the eye already is.
     *
     * <p><b>A player is not stopped by a tab being switched away, and should not be.</b> That is the
     * widget's documented contract and it is the right one (a soundtrack that stopped because
     * somebody clicked another tab is not what a player is for), but it makes starting one the
     * application's decision, and this application was making it at construction time, so the
     * Kitchen Sink began playing a video the moment it opened, on a tab nobody had looked at. So
     * this is where the decision is made: nothing starts until the picture is actually on screen,
     * and it pauses again when it leaves. The view's own decoding already stops on its own; only the
     * player needs telling.
     */
    private static final class VideoPane extends Widget {

        private final VideoView view;
        private final Playing open;
        /** The transport, over the bottom of the picture, or null in a frozen capture. */
        private final Widget transport;
        private String notice;
        /** What is being opened right now, or null when nothing is. */
        private String opening;
        private long openingSinceNanos;
        private boolean openingPolling;
        /** The newest fraction the open published, or NaN while it has published none. */
        private double openingFraction = Double.NaN;
        private boolean ticking;
        private boolean polling; // an idle-poll callback is in flight
        private boolean counting; // an idle-countdown deadline is in flight
        /** 0 = the bar has slid out through the bottom edge and is invisible, 1 = resting. */
        private final limn.animation.Transition reveal;
        private long quietSinceNanos = System.nanoTime();
        private boolean revealed = true;
        /**
         * Whether the pause now in force is <em>this widget's</em>, put there because the picture
         * left the screen. Without it the resume below undoes every other pause there is: the
         * transport's Pause button lasts less than one frame, because the next tick finds a paused
         * player and starts it again.
         */
        private boolean pausedByHiding;

        /**
         * Where the cues come from and what time to ask them about, or null when the source is not
         * a container. Both are the application's: the toolkit hands over text and timing and has
         * no opinion about when a cue is current — the argument that keeps subtitles and volume
         * out of the toolkit's {@code MediaControls} too, injected through its slots instead.
         */
        private limn.video.ffmpeg.FfmpegMedia cueSource;
        private java.util.function.LongSupplier cueTime;

        VideoPane(VideoView view, Playing open, Widget transport) {
            this.view = view;
            this.open = open;
            this.transport = transport;
            this.reveal = new limn.animation.Transition(this, 1f)
                    .duration(0.22)
                    .easing(limn.animation.Easing.EASE_OUT);
            add(view);
            if (transport != null) {
                // Over the picture, which is where every player puts it and (in a Kitchen Sink
                // tab a couple of hundred points tall) the only place it is visible at all. Below
                // the picture it lands past the fold, which is the same trap the ordering comment
                // in tabContent() records for the prose.
                add(transport);
            }
        }

        /** Shows the view alone. */
        void showPicture() {
            opening = null;
            notice = null;
            invalidate();
        }

        /**
         * Shows {@code why} on the picture, which stays where it is: the view paints its own black
         * background whether or not it has a stream, so what a viewer sees is a player with a label
         * on it rather than a gap where a player was supposed to be.
         */
        void showNotice(String why) {
            opening = null;
            notice = why;
            invalidate();
        }

        /**
         * Says that {@code what} is being opened, in the same box as every other message here.
         *
         * <p><b>The line carries the time the open has been running, and that is not decoration.</b>
         * These opens used to freeze the window for the whole half second, and a still "Opening…"
         * is what a frozen window would show too; a number that keeps moving is the one thing a
         * viewer can see that says the frame loop is still turning while a decoder blocks a worker.
         */
        void showOpening(String what) {
            opening = what;
            openingSinceNanos = System.nanoTime();
            openingFraction = Double.NaN;
            refreshOpening();
            pollOpening();
        }

        /**
         * The newest fraction the open published, 0 to 1, which takes the clock's place. Most opens
         * publish nothing at all, which is why the clock is what this falls back to rather than the
         * other way round.
         */
        void showOpeningProgress(double fraction) {
            openingFraction = fraction;
            refreshOpening();
        }

        private void refreshOpening() {
            if (opening == null) {
                return;
            }
            String text = Double.isNaN(openingFraction)
                    ? String.format(java.util.Locale.US, "Opening %s… %.1f s", opening,
                            (System.nanoTime() - openingSinceNanos) / 1e9)
                    : String.format(java.util.Locale.US, "Opening %s… %d%%", opening,
                            Math.round(openingFraction * 100));
            if (!text.equals(notice)) {
                notice = text;
                invalidate();
            }
        }

        /**
         * Re-draws that line a few times a second for as long as the open runs, and stops with it.
         * On a timer rather than on a ticker for the reason the readouts below the picture give: a
         * registered ticker asks the scene for a frame every frame the display can give, and this
         * has one number to move.
         */
        private void pollOpening() {
            if (openingPolling || opening == null) {
                return;
            }
            openingPolling = true;
            limn.concurrent.Ui.postDelayed(() -> {
                openingPolling = false;
                if (opening == null || scene() == null) {
                    return;
                }
                refreshOpening();
                pollOpening();
            }, POLL_MILLIS);
        }

        @Override
        protected limn.scene.Size onMeasure(limn.scene.Constraints constraints) {
            // Always the view's box, message or no message: a panel that collapsed when a source
            // could not be opened would move everything below it and make the failure look like a
            // layout bug.
            limn.scene.Size box = view.measure(constraints);
            return constraints.constrain(box.width(), box.height());
        }

        @Override
        protected void onLayout() {
            view.measure(limn.scene.Constraints.tight(width(), height()));
            view.layoutBox(0, 0, width(), height());
            if (transport != null) {
                // Inset on three sides rather than edge to edge: a bar welded to the corners reads
                // as part of the window's chrome, and the rounded glass has nothing to be rounded
                // against. The margin is also the distance it slides out through.
                float box = Math.max(0, width() - 2 * CHROME_MARGIN);
                float wanted = transport.measure(
                        limn.scene.Constraints.loose(box, height())).height();
                float bar = Math.min(wanted, Math.max(0, height() - 2 * CHROME_MARGIN));
                transport.measure(limn.scene.Constraints.tight(box, bar));
                transport.layoutBox(CHROME_MARGIN, height() - bar - CHROME_MARGIN, box, bar);
            }
        }

        /**
         * After the children, so it lands on the picture rather than under it, and in the same
         * fill, border, radius and padding the hover tooltips use: a fixed tooltip, centred on
         * both axes, which is where every player puts this.
         */
        void showCuesOf(limn.video.ffmpeg.FfmpegMedia media,
                        java.util.function.LongSupplier at) {
            this.cueSource = media;
            this.cueTime = at;
        }

        /**
         * The subtitle, drawn by the APPLICATION over the picture with the toolkit's own text
         * stack, which is the whole demonstration. Nothing in {@code limn-toolkit} or
         * {@code limn.components} knows a subtitle exists: what the SPI hands over is text and an
         * interval, and where it goes, how big it is, what colour it is and whether it is shown at
         * all are decided here, in forty lines, by the code that already knows what the rest of
         * this window looks like.
         *
         * <p>Lower third, centred, one line per break, with a plate behind it so the text survives
         * a light picture, which is roughly what every player does and is exactly the sort of
         * choice that would have had to be invented, and then argued about, if the toolkit had
         * tried to make it.
         */
        private void paintCues(limn.graphics.Canvas canvas) {
            if (cueSource == null || cueTime == null) {
                return;
            }
            List<limn.video.ffmpeg.SubtitleCues.Cue> cues =
                    cueSource.subtitles().activeAt(cueTime.getAsLong());
            if (cues.isEmpty()) {
                return;
            }
            Theme theme = Theme.current();
            limn.components.SizeTokens tokens = theme.tokensFor(this);
            limn.graphics.Font font = tokens.label();
            List<String> lines = new java.util.ArrayList<>();
            for (limn.video.ffmpeg.SubtitleCues.Cue cue : cues) {
                // The SPI promises plain text and says it may carry breaks; splitting on them is
                // the application's, because how many lines fit is a question about this box.
                lines.addAll(List.of(cue.text().split("\n", -1)));
            }
            float lineHeight = textRuler().measure("Ag", font).height();
            float pad = tokens.tooltipPadV();
            float blockHeight = lines.size() * lineHeight + 2 * pad;
            float bottom = height() - Math.max(pad, height() * 0.08f);
            if (transport != null) {
                bottom -= transport.height();
            }
            float top = Math.max(0, bottom - blockHeight);
            float widest = 0;
            for (String line : lines) {
                widest = Math.max(widest, textRuler().measure(line, font).width());
            }
            float plateWidth = Math.min(width(), widest + 2 * tokens.tooltipPadH());
            canvas.fillRoundRect((width() - plateWidth) / 2, top, plateWidth, blockHeight,
                    tokens.radiusSmall(), theme.surfaceRaised);
            float y = top + pad;
            for (String line : lines) {
                limn.graphics.TextMetrics metrics = textRuler().measure(line, font);
                canvas.drawText(line, (width() - metrics.width()) / 2, y + metrics.ascent(), font,
                        theme.text);
                y += lineHeight;
            }
        }

        @Override
        protected void onPaintOverlay(limn.graphics.Canvas canvas) {
            paintCues(canvas);
            if (notice == null) {
                return;
            }
            Theme theme = Theme.current();
            limn.components.SizeTokens tokens = theme.tokensFor(this);
            limn.graphics.Font font = tokens.label();
            limn.graphics.TextMetrics metrics = textRuler().measure(notice, font);
            float padX = tokens.tooltipPadH();
            float padY = tokens.tooltipPadV();
            float pillWidth = Math.min(width(), metrics.width() + 2 * padX);
            float pillHeight = Math.min(height(), metrics.height() + 2 * padY);
            float left = (width() - pillWidth) / 2;
            float top = (height() - pillHeight) / 2;
            canvas.fillRoundRect(left, top, pillWidth, pillHeight, tokens.radiusSmall(),
                    theme.surfaceRaised);
            canvas.drawRoundRect(left + 0.5f, top + 0.5f, pillWidth - 1, pillHeight - 1,
                    tokens.radiusSmall(), limn.components.Strokes.BORDER, theme.outline);
            canvas.drawText(notice, left + (pillWidth - metrics.width()) / 2,
                    top + (pillHeight - metrics.height()) / 2 + metrics.ascent(), font, theme.text);
        }

        @Override
        protected void onPaint(limn.graphics.Canvas canvas) {
            // Painting is the proof that this is on screen, and it is what arms everything below.
            // A tab that is never opened never paints, so a player behind it is never started at
            // all.
            //
            // The resume is here and not only on the ticker: a pane whose picture is frozen and
            // whose bar is at rest carries no ticker, and that is exactly the state a pane comes
            // back in when it left the screen playing: this widget paused it on the way out, and
            // nothing else is going to undo that.
            resumePlayer();
            startTicking();
            countDownToHide();
        }

        private void startTicking() {
            if (ticking || scene() == null || !isShowing()) {
                return;
            }
            if (settled()) {
                // Registering a ticker asks for a frame by itself, so re-arming one from a paint
                // here would keep the loop at the display's rate over a frozen picture.
                pollWhileIdle();
                return;
            }
            ticking = true;
            scene().addTicker(dt -> {
                if (!isShowing()) {
                    ticking = false; // re-armed by the next paint, when it is showing again
                    pausePlayer();
                    return false;
                }
                resumePlayer();
                hideWhenFaded();
                if (settled()) {
                    // A registered ticker asks the scene for a frame every frame whether or not
                    // anything it drives has moved. With the bar at rest and the picture frozen
                    // there is nothing here that moves, so the ticker stops and the poll below
                    // takes over the one thing it was still watching for.
                    ticking = false;
                    pollWhileIdle();
                    return false;
                }
                return true;
            });
        }

        /**
         * Nothing on this pane can change by itself: the bar is where it is going to stay and the
         * picture is frozen. Anything else is a ticker asking for every frame the display can give
         * to redraw pixels that cannot differ.
         */
        private boolean settled() {
            return chromeAtRest() && pictureStill();
        }

        /**
         * Whether the transport has arrived wherever it was sent. The fade is real animation and
         * this pane's ticker is what carries it; dropping the faded bar's visibility is the last
         * thing that ticker is registered for, so a bar that has finished leaving but is still
         * visible has one tick left to go rather than being at rest.
         *
         * <p>A pane with no transport has no chrome at all (the frozen-capture path), so it is at
         * rest from the first frame. Requiring a transport here instead pins the ticker for the
         * whole run of a pane that has nothing to animate.
         */
        private boolean chromeAtRest() {
            if (transport == null) {
                return true;
            }
            return !reveal.isAnimating() && (revealed || !transport.isVisible());
        }

        /**
         * Whether the picture can still change on its own. <b>Wider than the pause</b>: a player
         * that has ended or failed answers false to {@link VideoView#isPaused()} and can produce
         * nothing further, so a test built on the pause alone never lets this pane retire: the
         * display's whole refresh rate, forever, over a picture nothing can move again.
         */
        private boolean pictureStill() {
            return view.isPaused() || view.isEnded() || view.failure() != null;
        }

        /**
         * Watches, a few times a second, for the pane leaving the screen, the one thing the
         * stopped ticker was still there for, because a player is not stopped by its picture being
         * hidden and has to be told. Costs no frames, where the ticker it replaces asked for every
         * frame the display could give.
         */
        private void pollWhileIdle() {
            if (polling || open.player == null) {
                // The poll exists to pause a player when the picture leaves the screen. Without a
                // player there is nothing to pause, so an idle pane with no soundtrack schedules
                // nothing at all rather than waking the loop ten times a second to read nothing.
                return;
            }
            polling = true;
            limn.concurrent.Ui.postDelayed(() -> {
                polling = false;
                if (ticking || scene() == null) {
                    return;
                }
                if (!isShowing()) {
                    pausePlayer();
                    return; // the next paint arms the ticker again
                }
                if (settled()) {
                    pollWhileIdle();
                    return;
                }
                startTicking();
            }, 150);
        }

        /**
         * Starts or resumes what this widget stopped, and <b>only</b> what this widget stopped.
         * This runs every frame the picture is on screen, so a version that resumed any paused
         * player would undo the viewer's own pause on the very next tick.
         */
        private void resumePlayer() {
            limn.video.MediaPlayer player = open.player;
            if (player == null) {
                return;
            }
            switch (player.state()) {
                case IDLE -> {
                    // NOT started here. A video starts paused (VideoView.setAutoplay), and a panel
                    // that began playing the moment it was painted would be this widget overruling
                    // that for every application that ever embedded one. The transport's play
                    // button is what starts it.
                    pausedByHiding = false;
                }
                case PAUSED -> {
                    if (pausedByHiding) {
                        player.resume();
                        pausedByHiding = false;
                    }
                    // Otherwise somebody else paused it (the transport, or the application), and a
                    // pause this widget did not cause is not one it may undo.
                }
                default -> {
                    // Playing, ended, failed or closed: none of them is this widget's to change.
                }
            }
        }

        /**
         * Any pointer activity over the picture brings the transport back and restarts the
         * countdown. Buttons and sliders do not consume {@code MOVE}, so this still runs while the
         * pointer is over the bar itself, which is what keeps it from sliding out from under a
         * hand that is reaching for it.
         */
        @Override
        protected void onMouseEvent(limn.scene.event.MouseEvent event) {
            switch (event.type()) {
                case ENTER, MOVE, DRAG, PRESS, WHEEL -> wake();
                default -> { }
            }
        }

        private void wake() {
            quietSinceNanos = System.nanoTime();
            if (!revealed && transport != null) {
                revealed = true;
                transport.setVisible(true);
                reveal.to(1);
                startTicking(); // the fade is animation, and the ticker retires with it
            }
            countDownToHide();
        }

        /**
         * Takes the transport away once the pointer has been still for {@link #IDLE_SECONDS}.
         *
         * <p><b>On one timer, and not on a ticker.</b> A registered ticker asks the scene for a
         * frame every frame it stays registered, so watching a wall-clock deadline on one buys the
         * display's whole refresh rate for the length of the countdown over a picture that is not
         * moving; since {@link #wake} restarts the countdown, every further nudge of the
         * pointer buys another countdown of it.
         *
         * <p><b>One deadline is in flight at a time.</b> {@code wake} runs on every {@code MOVE},
         * and a version that posted a task per event would have traded the frames for a queue of
         * them; this one re-reads {@link #quietSinceNanos} when it fires and posts again for
         * whatever is left of the quiet period. It is dropped when the pane stops showing, and the
         * paint that proves the pane is back is what arms it again.
         */
        private void countDownToHide() {
            if (counting || transport == null || !revealed || scene() == null) {
                return;
            }
            awaitQuiet();
        }

        private void awaitQuiet() {
            counting = true;
            long left = (long) (IDLE_SECONDS * 1000)
                    - (System.nanoTime() - quietSinceNanos) / 1_000_000L;
            limn.concurrent.Ui.postDelayed(() -> {
                counting = false;
                if (transport == null || !revealed || scene() == null || !isShowing()) {
                    return;
                }
                if (System.nanoTime() - quietSinceNanos < IDLE_SECONDS * 1e9) {
                    awaitQuiet(); // the pointer moved while this was pending
                    return;
                }
                revealed = false;
                reveal.to(0);
                // The transition fades the bar off a ticker of its own; this one is registered to
                // drop the bar's visibility at the end of that, and retires as soon as it has.
                startTicking();
            }, Math.max(1, left));
        }

        /**
         * Drops the visibility of a transport that has finished leaving. Only then: an invisible
         * widget is not hit-tested, and taking the pointer away from a bar that is still on screen
         * is worse than either state.
         */
        private void hideWhenFaded() {
            if (transport != null && !revealed && !reveal.isAnimating() && transport.isVisible()) {
                transport.setVisible(false);
            }
        }

        /**
         * The picture, then the transport with the reveal applied to it: opacity and a vertical
         * offset off the same number, clipped to the picture so the bar leaves through the bottom
         * edge instead of drawing over the prose below the pane.
         */
        @Override
        protected void paintChildren(limn.graphics.Canvas canvas) {
            paintChild(canvas, view, 0);
            if (transport != null && transport.isVisible()) {
                canvas.save();
                try {
                    canvas.clipRect(0, 0, width(), height());
                    canvas.setOpacity(canvas.opacity() * reveal.value());
                    paintChild(canvas, transport,
                            (1 - reveal.value()) * (transport.height() + CHROME_MARGIN));
                } finally {
                    canvas.restore();
                }
            }
        }

        private static void paintChild(limn.graphics.Canvas canvas, limn.scene.Widget child,
                                       float offsetY) {
            canvas.save();
            try {
                canvas.translate(child.x(), child.y() + offsetY);
                child.paintWidget(canvas);
            } finally {
                canvas.restore();
            }
        }

        /** The picture has left the screen. Only a player that was actually playing is stopped. */
        private void pausePlayer() {
            limn.video.MediaPlayer player = open.player;
            if (player != null && player.state() == limn.video.MediaPlayer.State.PLAYING) {
                player.pause();
                pausedByHiding = true;
            }
        }
    }

    /**
     * The transport bar's material. Clear rather than a wash: a refracting pane keeps the picture
     * readable through it, which a frosted one does not, and it costs one sample per pixel; the
     * transport sits over video and repaints every frame it is shown.
     *
     * <p>The tint is what makes the controls legible, and it is set against the worst case rather
     * than the average one: white-on-glass over a bright picture is the frame that decides it.
     */
    private static final limn.graphics.BackdropEffect GLASS =
            new limn.graphics.BackdropEffect.Clear(limn.graphics.Color.BLACK.withAlpha(0.60f), 14f, 0.35f);

    /**
     * The ink the transport writes in, and it is deliberately not the theme's.
     *
     * <p>Everything in the bar sits on {@link #GLASS}, which is black at 60% over whatever the
     * decoder just produced, a surface the palette knows nothing about. A control that took
     * {@code Theme.text} would be near-black on it in every light palette, which is the defect
     * these three constants exist to remove, and merely dim in the dark ones.
     *
     * <p>The bar to hold them to is the worst frame rather than the average one: over a white
     * picture the glass transmits to about {@code #666666}, and these clear 5.2:1 and 4.6:1 on
     * that. Dimming either one to taste is the wrong edit: it is legible over the black frame
     * that a still capture usually shows and unreadable over the bright one that follows.
     * {@link #GLASS_INK_DISABLED} is the exception, at 3.1:1: a control that cannot be used is
     * not text a viewer has to read.
     */
    private static final limn.graphics.Color GLASS_INK = limn.graphics.Color.rgb(0xF6F5F9);

    /** The clock's ink (see {@link #GLASS_INK}); dimmer, and still over the body-text bar. */
    private static final limn.graphics.Color GLASS_INK_MUTED = limn.graphics.Color.rgb(0xE6E4EE);

    /** Unusable rather than unreadable; see {@link #GLASS_INK}. */
    private static final limn.graphics.Color GLASS_INK_DISABLED = limn.graphics.Color.rgb(0x9E9BAA);

    /** Gap between the transport and the picture's edges, and the distance it slides out through. */
    private static final float CHROME_MARGIN = 10;

    /** How long the pointer must be still before the transport leaves. */
    private static final double IDLE_SECONDS = 3;

    /**
     * The transport: the toolkit's {@code MediaControls} plus what only this application can add.
     *
     * <p>The bar itself — play/pause, the scrub policy (keyframe seeks while dragging, one exact
     * seek on release, rate-limited), and the clock — <b>moved into the toolkit</b> and lives in
     * {@link limn.components.MediaControls}, which also owns the decision that the bar reads left
     * to right in either direction. What stays here is exactly the part no component could choose
     * for every application: a volume that lazily re-opens the soundtrack ({@link SoundLevel}) and
     * a subtitle button that knows the container, both injected through the bar's slots.
     */
    /**
     * The transport's volume, the way a browser's is: one level behind a slider and a mute button.
     *
     * <p>The awkward half is that a soundtrack is not a gain. It is handed to the audio engine when
     * the player is built, so there is nothing to turn up on a picture opened without one: the
     * first time this is asked to be audible it <em>re-opens</em> the source with its audio track,
     * and everything after that is a gain on the handle that player is holding. Muting is therefore
     * gain 0 and never a close: a browser's mute button does not tear the stream down, and one that
     * did would cost a decoder flush every time somebody silenced a video for a moment.
     *
     * <p>Null where nothing can re-open: a frozen capture, and the standalone window, which owns
     * its stream from the file it was given.
     */
    private static final class SoundLevel {

        /** Where the slider starts, and what unmuting returns to. */
        private static final float DEFAULT_VOLUME = 0.7f;

        private final Playing open;
        private final Runnable openSoundtrack;
        private float volume = DEFAULT_VOLUME;
        private boolean muted; // a soundtrack that exists is audible; isMuted() covers the rest
        private limn.sound.Playback applied;
        private float appliedGain = Float.NaN;

        SoundLevel(Playing open, Runnable openSoundtrack) {
            this.open = open;
            this.openSoundtrack = openSoundtrack;
        }

        float volume() {
            return volume;
        }

        /** Muted, or simply silent because nothing has opened a soundtrack yet. */
        boolean isMuted() {
            return muted || open.player == null;
        }

        void setVolume(float newVolume) {
            volume = Math.max(0f, Math.min(1f, newVolume));
            muted = volume <= 0;
            if (!muted) {
                ensureSoundtrack();
            }
        }

        void toggleMuted() {
            muted = !isMuted();
            if (!muted) {
                if (volume <= 0) {
                    volume = DEFAULT_VOLUME; // unmuting a slider dragged to zero has to go somewhere
                }
                ensureSoundtrack();
            }
        }

        /**
         * Writes the level onto whatever handle is sounding now. Called every frame because the
         * handle is not stable: every re-open builds a player, and a gain set on the previous one
         * is a gain set on nothing. Guarded on both the handle and the value, so a steady state
         * costs a comparison rather than a call into the audio engine.
         */
        void sync() {
            limn.video.MediaPlayer player = open.player;
            if (player == null) {
                applied = null;
                appliedGain = Float.NaN;
                return;
            }
            limn.sound.Playback playback = player.audio();
            float wanted = muted ? 0f : volume;
            if (playback != applied || wanted != appliedGain) {
                playback.setGain(wanted);
                applied = playback;
                appliedGain = wanted;
            }
        }

        private void ensureSoundtrack() {
            if (open.player == null) {
                openSoundtrack.run();
            }
        }
    }

    /**
     * A transport control: the shape of {@link Button}, painted for glass instead of for the
     * palette.
     *
     * <p><b>Why this is not a {@code Button}.</b> A button takes its ink and its fill from the
     * theme, which is what makes a form look like one screen, and the transport is not on that
     * screen. It floats on {@link #GLASS} over someone else's picture, so the palette's answer is
     * wrong for it in every light theme and thin in the dark ones. There is no per-subtree theme
     * to switch to and no ink override on {@code Button}, so the bar paints its own: one light
     * ink from {@link #GLASS_INK}, and a fill that is white at a few percent rather than a
     * surface colour, so it reads as the picture lightening under the pointer.
     *
     * <p>Everything else is the button's behaviour, kept deliberately: focusable, Enter and
     * Space, a ring outside the box, a hover that fades over {@code Theme.animHover}, and the
     * sizes from the {@link SizeTokens} row the widget resolves. A control that is legible but
     * unreachable from the keyboard would be a worse bar than the one this replaces.
     */
    private static final class GlassButton extends Widget {

        /** How far the glass lightens under the pointer, and under a press. */
        private static final float HOVER_ALPHA = 0.14f;
        private static final float PRESSED_ALPHA = 0.26f;

        private final limn.animation.Transition hover =
                new limn.animation.Transition(this).duration(Theme.current().animHover)
                        .easing(Theme.current().animEasing);
        private final limn.animation.Transition focusFade =
                new limn.animation.Transition(this).duration(Theme.current().animFocus)
                        .easing(Theme.current().animEasing);

        private limn.graphics.Icon icon;
        private String text = "";
        private Runnable action = () -> {
        };
        private boolean armed;    // mouse press in progress
        private boolean keyArmed; // Space/Enter held, tracked apart from the mouse

        GlassButton() {
            setFocusable(true);
            setCursor(limn.backend.Cursor.POINTER);
        }

        GlassButton setIcon(limn.graphics.Icon newIcon) {
            if (icon == newIcon) {
                return this;
            }
            boolean boxChanges = (icon == null) != (newIcon == null);
            icon = newIcon;
            // A swap between two icons is a repaint; gaining or losing one is a new width. Both
            // run on the poll below, so the cheap case must not ask for a layout pass.
            if (boxChanges) {
                markNeedsLayout();
            } else {
                invalidate();
            }
            return this;
        }

        GlassButton setText(String newText) {
            if (text.equals(newText)) {
                return this;
            }
            text = newText;
            markNeedsLayout();
            return this;
        }

        GlassButton onAction(Runnable newAction) {
            action = java.util.Objects.requireNonNull(newAction, "newAction");
            return this;
        }

        /** Icon-only controls are square, so a row of them reads as one set rather than as pills. */
        @Override
        protected limn.scene.Size onMeasure(limn.scene.Constraints constraints) {
            SizeTokens tokens = Theme.current().tokensFor(this);
            limn.graphics.TextMetrics metrics = textRuler().measure(text, tokens.body());
            float height = tokens.resolvedHeight(metrics.lineHeight());
            if (text.isEmpty()) {
                return constraints.constrain(height, height);
            }
            return constraints.constrain(
                    metrics.width() + iconAdvance(tokens) + 2 * tokens.padH(), height);
        }

        private float iconAdvance(SizeTokens tokens) {
            if (icon == null) {
                return 0;
            }
            return tokens.iconBox() + (text.isEmpty() ? 0 : tokens.gapIcon());
        }

        /** The ring is drawn outside the box, so damage has to be told, as {@code Button} is. */
        @Override
        protected float paintOutset() {
            return limn.components.Strokes.FOCUS_RING_OUTSET;
        }

        @Override
        protected void onPaint(limn.graphics.Canvas canvas) {
            SizeTokens tokens = Theme.current().tokensFor(this);
            float radius = tokens.radiusMedium();
            float lift = !isEnabled() ? 0
                    : (armed || keyArmed) ? PRESSED_ALPHA
                    : HOVER_ALPHA * hover.value();
            if (lift > 0.001f) {
                canvas.fillRoundRect(0, 0, width(), height(), radius,
                        limn.graphics.Color.WHITE.withAlpha(lift));
            }
            float focus = focusFade.value();
            if (focus > 0.001f) {
                float gap = limn.components.Strokes.FOCUS_GAP_BUTTON;
                canvas.drawRoundRect(-gap, -gap, width() + 2 * gap, height() + 2 * gap,
                        radius + gap, limn.components.Strokes.FOCUS_RING,
                        GLASS_INK.withAlpha(focus));
            }
            limn.graphics.Color ink = isEnabled() ? GLASS_INK : GLASS_INK_DISABLED;
            limn.graphics.Font font = tokens.body();
            limn.graphics.TextMetrics metrics = textRuler().measure(text, font);
            float advance = iconAdvance(tokens);
            float cursorX = (width() - (advance + metrics.width())) / 2;
            if (icon != null) {
                float box = tokens.iconBox();
                // dark = true whatever the palette is doing: the surface under this icon is the
                // glass, and an icon that has a light-scheme drawing would pick the wrong one.
                icon.paint(canvas, cursorX, (height() - box) / 2, box, ink, true);
                cursorX += advance;
            }
            if (!text.isEmpty()) {
                canvas.drawText(text, cursorX,
                        (height() - metrics.height()) / 2 + metrics.ascent(), font, ink);
            }
        }

        @Override
        protected void onMouseEvent(limn.scene.event.MouseEvent event) {
            switch (event.type()) {
                case ENTER -> hover.to(1);
                case EXIT -> {
                    hover.to(0);
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
                        action.run();
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
                    action.run();
                }
            }
        }

        @Override
        protected void onFocusGained() {
            focusFade.to(1);
        }

        @Override
        protected void onFocusLost() {
            focusFade.to(0);
            armed = false;
            keyArmed = false;
            invalidate();
        }
    }

    private static final class Transport extends Widget {

        private final Playing open;
        private final SoundLevel sound;
        private final limn.components.MediaControls controls;
        private final GlassButton mute = new GlassButton();
        private final limn.components.Slider volume = new limn.components.Slider(0, 100);
        private final GlassButton subtitles = new GlassButton();
        private String subtitleLabel = "";
        /** What the two icon buttons are currently drawing; null until the first refresh drew one. */
        private Boolean soundMuted;

        Transport(Playing open, VideoView view, SoundLevel sound) {
            this.open = open;
            this.sound = sound;
            // Two steps down for everything inside the bar. A transport is chrome over someone
            // else's content, not the content: at the default step it is a third of the picture's
            // height in a Kitchen Sink tab, and even one step down it competes with the picture
            // for the eye. XSMALL is a compact step and not a miniature one: the hit targets are
            // still clamped to Strokes.MIN_HIT_TARGET.
            setControlSize(limn.scene.ControlSize.XSMALL);
            // The toolkit's bar brings play/pause, the scrub policy and the clock, and already
            // reads left to right on its own, so this class declares neither. What it adds is
            // exactly the part no component could choose for the application: a volume that
            // lazily re-opens the soundtrack, and a subtitle button that knows the container.
            // The pane behind paints the GLASS, so the bar's own panel is off, and the built-ins
            // write in the glass inks for the reason those constants document.
            controls = new limn.components.MediaControls(view)
                    .setBackdrop(false)
                    .setInk(GLASS_INK, GLASS_INK_MUTED)
                    .setOnRefresh(() -> {
                        refreshSubtitles();
                        refreshSound();
                    });
            if (sound != null) {
                // The built-in pair can only turn a gain the player already holds, and this
                // tab's soundtrack is lazily re-opened (SoundLevel) — a policy the toolkit
                // cannot know. So the built-ins are OFF here and the demo's own pair rides the
                // slots; the standalone window keeps the default AUTO, where its player owns
                // the track from the file and the built-in volume simply works.
                controls.setSound(limn.components.MediaControls.Sound.OFF);
                mute.setTooltip("Silence the soundtrack, keeping it where it is");
                mute.onAction(() -> {
                    sound.toggleMuted();
                    refreshSound();
                });
                volume.setTooltip("How loud, from silent to full");
                volume.setValue(0); // muted until somebody asks: the level and the label agree
                volume.onChange(value -> {
                    sound.setVolume(value / 100f);
                    refreshSound();
                });
                controls.addLeading(mute);
                // Fixed and small, before the scrub bar: the timeline is the control that should
                // take whatever width is left, and a volume bar as long as a film reads as a
                // second timeline. This is also the order a browser puts them in.
                controls.addLeading(
                        new limn.scene.layout.SizedBox(72, limn.scene.layout.SizedBox.UNSET, volume));
            }
            // ON THE TRANSPORT and not in the rows below the picture, and the reason is the same
            // arithmetic that put the transport here: this tab's body is a couple of hundred points
            // tall, the two rows under the picture are already at this window's width, and a
            // control that lands past the fold is a control no capture can show. Over the picture
            // costs no vertical space at all.
            subtitles.setText("Subtitles");
            subtitles.setTooltip("Show the next subtitle track of this container, or none");
            subtitles.onAction(this::takeTheNextSubtitleTrack);
            controls.addTrailing(subtitles);
            add(controls);
        }

        /**
         * The next text track, then none, then round again.
         *
         * <p>Cheaper than the audio switch beside it by a whole player: a soundtrack is transferred
         * to the audio engine at construction, so changing one means a new player, but nothing owns
         * a cue. The container drops the window and the next paint asks the new track.
         *
         * <p>A bitmap track and one this build cannot decode are both skipped rather than offered
         * and then refused; the listing says which those are, so a picker has no excuse for
         * walking into either.
         */
        private void takeTheNextSubtitleTrack() {
            limn.video.ffmpeg.FfmpegMedia media = open.media;
            if (media == null || media.subtitleTracks().isEmpty()) {
                return;
            }
            int count = media.subtitleTracks().size();
            int selected = media.selectedSubtitleTrack();
            // count + 1 candidates, because "off" is one of the choices a viewer needs.
            for (int step = 1; step <= count + 1; step++) {
                int candidate = selected + step;
                if (candidate >= count) {
                    media.selectSubtitles(limn.video.ffmpeg.FfmpegMedia.NO_SUBTITLES);
                    return;
                }
                limn.video.ffmpeg.FfmpegMedia.SubtitleTrack track =
                        media.subtitleTracks().get(candidate);
                if (track.text() && track.decodable()) {
                    media.selectSubtitles(candidate);
                    return;
                }
            }
        }

        private void refreshSubtitles() {
            limn.video.ffmpeg.FfmpegMedia media = open.media;
            String text;
            if (media == null || media.subtitleTracks().isEmpty()) {
                text = "No subtitles";
                subtitles.setEnabled(false);
                // Out of the row entirely, not merely greyed: this bar is the width of the picture,
                // and a dead control in it is width taken from the scrub bar, which is the one
                // control nobody can do without.
                subtitles.setVisible(false);
            } else {
                subtitles.setEnabled(true);
                subtitles.setVisible(true);
                int selected = media.selectedSubtitleTrack();
                if (selected < 0) {
                    text = "Subtitles off";
                } else {
                    String language = media.subtitleTracks().get(selected).language();
                    text = "Subtitles: " + (language != null ? language : "on");
                }
            }
            if (!text.equals(subtitleLabel)) {
                subtitleLabel = text;
                subtitles.setText(text);
            }
        }

        /**
         * Writes the level onto the sounding handle and keeps the two controls saying the same
         * thing. Runs every frame, so both the label and the thumb are guarded: {@code setText}
         * and {@code setValue} invalidate, and a transport that repainted the window every frame
         * over a paused picture would be the most expensive thing on screen.
         */
        private void refreshSound() {
            if (sound == null) {
                return;
            }
            sound.sync();
            boolean silent = sound.isMuted();
            if (soundMuted == null || soundMuted != silent) {
                soundMuted = silent;
                mute.setIcon((silent ? TablerMedia.VOLUME_OFF : TablerMedia.VOLUME).icon());
                mute.setTooltip(silent
                        ? "Let the soundtrack be heard again, where it is"
                        : "Silence the soundtrack, keeping it where it is");
            }
            float wanted = sound.isMuted() ? 0 : Math.round(sound.volume() * 100);
            if (wanted != volume.value()) {
                volume.setValue(wanted);
            }
        }

        @Override
        protected limn.scene.Size onMeasure(limn.scene.Constraints constraints) {
            limn.scene.Size inner = controls.measure(constraints);
            return constraints.constrain(inner.width(), inner.height());
        }

        @Override
        protected void onLayout() {
            controls.measure(limn.scene.Constraints.tight(width(), height()));
            controls.layoutBox(0, 0, width(), height());
        }
    }

    /**
     * The language picker: which of a container's audio tracks is playing, and a click to take the
     * next one, the whole point of a container being able to say what is inside it.
     *
     * <p><b>Switching one is a new player over the same stream</b>, and that is not a workaround: a
     * player takes its audio track at construction because handing a source to the audio engine
     * transfers it, so there is no such thing as swapping the track under a running one. What that
     * costs here is a close (which joins the decode thread and ends the old track with the engine),
     * a selection, and a seek back to where the picture had reached; the container stays open
     * throughout, the pictures are borrowed by both players in turn, and the viewer sees the
     * language change without the film restarting.
     *
     * <p>It is present only when this run may have sound at all, for the same reason the soundtrack
     * switch is: a screenshot starts no audio device, so there is no player to change the track of.
     */
    private static final class AudioTracks extends Widget {

        private final Playing open;
        private final VideoView view;
        private final Button button = new Button("Audio").setSecondary(true);
        private final Row row = new Row();
        private boolean ticking;
        private String shown = "";

        AudioTracks(Playing open, VideoView view) {
            this.open = open;
            this.view = view;
            button.setTooltip("Play the next audio track of this container, keeping the position");
            button.onAction(this::takeTheNextTrack);
            row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
            row.add(button);
            add(row);
        }

        private void takeTheNextTrack() {
            limn.video.ffmpeg.FfmpegMedia media = open.media;
            limn.video.MediaPlayer player = open.player;
            if (media == null || player == null || media.audioTracks().size() < 2) {
                return;
            }
            int count = media.audioTracks().size();
            int selected = media.selectedAudioTrack();
            int wanted = selected;
            for (int step = 1; step <= count; step++) {
                int candidate = (selected + step) % count;
                // A track this build has no decoder for is skipped rather than offered and then
                // refused: the list says which those are, so a picker has no excuse.
                if (media.audioTracks().get(candidate).decodable()) {
                    wanted = candidate;
                    break;
                }
            }
            if (wanted == selected) {
                return;
            }
            long position = player.positionMicros();
            boolean paused = view.isPaused();

            // The new track is taken FIRST, while the old one is still with the engine, which is
            // the case the container is built for: the old track reports its end, the engine lets
            // go of it, and a failure here leaves the player that is running untouched.
            limn.sound.AudioStreamSource track = media.audio(wanted);
            view.setPlayer(null);
            player.close(); // joins the decode thread, so the stream is free to be lent again
            limn.video.MediaPlayer next = new limn.video.MediaPlayer(open.stream)
                    .setAudio(track, TRACK_OPTIONS);
            next.setLooping(true);
            open.player = next;
            view.setPlayer(next);
            next.start();
            next.seek(position, VideoStreamSource.SeekMode.EXACT);
            if (paused) {
                view.setPaused(true); // a viewer who was paused stays paused
            }
            refresh();
        }

        private void refresh() {
            limn.video.ffmpeg.FfmpegMedia media = open.media;
            List<limn.video.ffmpeg.FfmpegMedia.AudioTrack> tracks =
                    media == null ? List.of() : media.audioTracks();
            button.setEnabled(tracks.size() > 1 && open.player != null);
            String text;
            if (media == null) {
                // A synthetic source has no container and therefore no track list; what it is
                // playing is the generated click track, which is not one of anything.
                text = "Generated soundtrack";
            } else if (tracks.isEmpty()) {
                text = "No soundtrack in this file";
            } else {
                int selected = Math.max(0, media.selectedAudioTrack());
                String language = tracks.get(selected).language();
                text = "Audio " + (selected + 1) + "/" + tracks.size() + ": "
                        + (language != null ? language : "no language");
            }
            if (!text.equals(shown)) {
                shown = text;
                button.setText(text);
            }
        }

        @Override
        protected limn.scene.Size onMeasure(limn.scene.Constraints constraints) {
            limn.scene.Size inner = row.measure(constraints);
            return constraints.constrain(inner.width(), inner.height());
        }

        @Override
        protected void onLayout() {
            row.measure(limn.scene.Constraints.tight(width(), height()));
            row.layoutBox(0, 0, width(), height());
        }

        /**
         * Polled on a timer rather than on a ticker, and the difference is the whole frame rate: a
         * registered ticker asks the scene for a frame every frame, so a widget that only wants to
         * re-read something a few times a second keeps the window at its refresh rate for as long
         * as it is on screen. Nothing here moves by itself (it changes when a track is picked),
         * and {@code setText} asks for the frame that shows it.
         */
        @Override
        protected void onPaint(limn.graphics.Canvas canvas) {
            refresh();
            if (ticking || scene() == null || !isShowing() || open.player == null) {
                // The player gate is at the ARMING site and not only inside the callback: what
                // this line describes cannot change without a player, and a timer that re-reads
                // nothing is a wake-up of the UI thread ten times a second for no answer.
                return;
            }
            ticking = true;
            poll();
        }

        private void poll() {
            limn.concurrent.Ui.postDelayed(() -> {
                // Only while a player exists: what this describes cannot change without one, so a
                // poll with nothing to read stops rather than waking the loop forever. The next
                // paint arms it again.
                if (!isShowing() || scene() == null || open.player == null) {
                    ticking = false;
                    return;
                }
                refresh();
                poll();
            }, POLL_MILLIS);
        }
    }

    /** How often the demo's own readouts re-read what they are describing. */
    private static final long POLL_MILLIS = 100;

    /**
     * The line that makes the pairing checkable rather than merely pleasant: where the soundtrack
     * has reached, which picture that is, and whether the audio is still what the pictures are timed
     * by. A click is heard as the counter reaches a multiple of thirty; this says the same thing in
     * numbers, and says which clock produced them when they stop agreeing.
     */
    private static final class PlayerStatus extends Widget {

        private final Label label = new Label("").setMuted(true).setWrap(true);
        private final Playing open;
        private boolean ticking;
        private double since;

        PlayerStatus(Playing open) {
            this.open = open;
            label.setText(describePlayer(open.player)); // a blank first frame reads as a broken line
            add(label);
        }

        @Override
        protected limn.scene.Size onMeasure(limn.scene.Constraints constraints) {
            limn.scene.Size inner = label.measure(constraints);
            return constraints.constrain(inner.width(), inner.height());
        }

        @Override
        protected void onLayout() {
            label.measure(limn.scene.Constraints.loose(width(), height()));
            label.layoutBox(0, 0, width(), height());
        }

        /** On a timer, for the reason {@code AudioTracks} gives: a ticker would cost every frame. */
        @Override
        protected void onPaint(limn.graphics.Canvas canvas) {
            label.setText(describePlayer(open.player));
            if (ticking || scene() == null || !isShowing() || open.player == null) {
                return; // nothing to describe: a poll here would wake the loop to read a null
            }
            ticking = true;
            poll();
        }

        private void poll() {
            limn.concurrent.Ui.postDelayed(() -> {
                if (!isShowing() || scene() == null || open.player == null) {
                    ticking = false; // nothing to describe; the next paint arms it again
                    return;
                }
                label.setText(describePlayer(open.player));
                poll();
            }, POLL_MILLIS);
        }
    }

    private static String describePlayer(limn.video.MediaPlayer player) {
        if (player == null) {
            return "No soundtrack: the view decodes on the UI thread and paces on the wall clock.";
        }
        double seconds = player.positionMicros() / 1_000_000.0;
        return String.format(java.util.Locale.US,
                "%.2f s · picture %d · timed by %s · %d buffered · %d dropped · %d underruns",
                seconds, (long) (seconds * RATE),
                player.isFollowingAudio() ? "the soundtrack" : "the wall clock",
                player.bufferedPictures(), player.clock().droppedFrames(), player.underruns());
    }

    /**
     * How big the picture box is inside the Kitchen Sink's Video tab.
     *
     * <p><b>The height is the bounded axis; the width is free.</b> The transport bar is drawn
     * over the picture's lower edge, so a taller box carries the bar further down the tab body
     * until the bar is cut in half by it, while the same box made wider costs nothing. That is
     * why this one grows sideways and the standalone scene, which has a window to itself, grows
     * both ways. A capture that cannot show a control is not a verification of it, so a change
     * to the height is checked against the {@code kitchen-video} capture rather than guessed:
     * the bar disappearing from that picture is the symptom, and it is silent.
     *
     * <p>Deliberately not 16:9, in both boxes: a picture whose ratio matches its box shows
     * nothing about letterboxing, and the three fits would all look the same.
     */
    private static final float TAB_PICTURE_WIDTH = 620;
    private static final float TAB_PICTURE_HEIGHT = 128;

    /** How big it is in the standalone scene, which has a window to itself. */
    private static final float SCENE_PICTURE_WIDTH = 640;
    private static final float SCENE_PICTURE_HEIGHT = 320;

    /**
     * The two the Kitchen Sink's Video tab offers, out of the twelve
     * {@code --scene video} does.
     *
     * <p>Bars first because it is the one a capture can rely on: it is generated in
     * memory and is on screen in the frame the tab is built, where MP4 encodes a clip
     * into the temporary directory first and would leave "Opening MP4…" in every
     * screenshot of this tab. MP4 second because it is the whole of the rest of the
     * list at once: a real container, demultiplexed and decoded by FFmpeg through
     * the JNI shim, with the soundtrack that is inside the file.
     *
     * <p>The ten left out are one property each (a chroma layout, a matrix, an odd
     * size, a bit depth), and each is a comparison against the entry beside it, which
     * a tab that shows one picture at a time cannot make. {@code --scene video} shows
     * them all, at twice the picture size, which is where that comparison belongs.
     */
    private static final List<String> TAB_SOURCES = List.of("Bars", "MP4");

    /** The Kitchen Sink's Video tab. */
    static Widget tabContent() {
        return tabContent(TAB_PICTURE_WIDTH, TAB_PICTURE_HEIGHT, sourcesNamed(TAB_SOURCES));
    }

    /**
     * Picks entries out of {@link #SOURCES} by label.
     *
     * <p>By label rather than by index, and it throws on a name that is not there: a
     * written-down index is a fact this file can invalidate silently; insert one
     * entry and the tab quietly shows a different decoder than the one it documents.
     */
    private static List<Source> sourcesNamed(List<String> labels) {
        return labels.stream()
                .map(label -> SOURCES.stream()
                        .filter(s -> s.label().equals(label))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("no video source '" + label + "'")))
                .toList();
    }

    /** The tab's content: a source picker, the picture, and what the stream says about itself. */
    private static Widget tabContent(float pictureWidth, float pictureHeight, List<Source> sources) {
        Theme theme = Theme.current();
        int initial = Math.max(0, Math.min(initialSource, sources.size() - 1));
        Label caption = new Label(sources.get(initial).caption()).setMuted(true).setWrap(true);
        Label facts = new Label("").setMuted(true).setWrap(true);

        VideoView view = new VideoView();
        // The stream's own 640×360 would set the column width; the demo wants a stable box, and a
        // deliberately non-16:9 one, because a picture whose ratio matches its box shows nothing
        // about letterboxing and the three fits would all look the same.
        // 2:1 on purpose: a picture whose ratio matches its box shows nothing about letterboxing
        // and the three fits would all look the same. Kept small enough that the whole of it is on
        // screen inside a tab body, because a picture you have to scroll to is a picture nobody
        // checks.
        view.setPreferredSize(pictureWidth, pictureHeight).setLooping(true);
        view.setTooltip("Decoded planes uploaded to a video surface and composited as one quad");

        // What this scene has open. Neither the widget nor the player closes a stream (neither
        // opened it), so switching sources is where the previous one is released.
        Playing open = new Playing();
        // No transport in a frozen capture: --video-frame hands the view a stream holding exactly
        // one picture, and a control that could move it is a control that could make the capture
        // depend on when it ran.
        boolean[] sound = {soundAllowed && staticFrame < 0};
        // Set once the pane exists, because re-opening needs the pane it plays into and the pane
        // needs the transport that asks for the re-open.
        java.util.function.LongConsumer[] reopen = {null};
        limn.components.Checkbox[] soundBox = {null};
        SoundLevel level = !soundAllowed || staticFrame >= 0 ? null : new SoundLevel(open, () -> {
            // Where the picture had reached, because opening the soundtrack builds a new player
            // over a freshly opened source: without this, unmuting starts the film again, which is
            // not what unmuting means anywhere else. It is carried INTO the re-open rather than
            // applied after it: the re-open is a job, and the stream to seek does not exist on
            // the line below.
            long at = view.positionMicros();
            sound[0] = true;
            if (soundBox[0] != null) {
                soundBox[0].setChecked(true); // the switch below the picture says the same thing
            }
            if (reopen[0] != null) {
                reopen[0].accept(at);
            }
        });
        VideoPane pane = new VideoPane(view, open, staticFrame >= 0 ? null
                : new BackdropPanel(GLASS, Insets.symmetric(6, 10),
                        new Transport(open, view, level)));
        // What is playing, as a value rather than as an index into the picker: a file the viewer
        // chose is not in the picker at all, and everything that re-opens (Restart, the soundtrack
        // switch) has to re-open THAT rather than whichever entry was last highlighted.
        Source[] current = {sources.get(initial)};
        Label status = new Label("").setMuted(true);
        reopen[0] = at -> play(pane, view, facts, open, current[0], sound[0], at);
        play(pane, view, facts, open, current[0], sound[0]);

        SegmentedControl picker = new SegmentedControl(sources.stream().map(Source::label).toList());
        picker.setSelectedIndex(initial);
        picker.onSelect(index -> {
            current[0] = sources.get(index);
            caption.setText(current[0].caption());
            play(pane, view, facts, open, current[0], sound[0]);
        });

        Button restart = new Button("Restart").setSecondary(true);
        restart.setTooltip("Play this source again from its first picture");
        restart.onAction(() -> {
            if (open.player != null) {
                // A soundtrack cannot be rewound by something that did not open it: handing it to
                // the engine transferred it. So the application re-opens what the application owns,
                // which is what an application would do and is why the player does not pretend to.
                play(pane, view, facts, open, current[0], sound[0]);
            } else if (view.source() != null && view.source().canReset()) {
                view.restart();
            }
        });

        // The point of the FFmpeg decoder, reachable without a system property: the viewer's own
        // file. The dialog is the platform's, the open is the same worker every entry above uses,
        // and what comes back is a Source like any other, so Restart, the fits and the soundtrack
        // switch work on it with no special case anywhere.
        Button openFile = new Button("Open…").setSecondary(true);
        openFile.setTooltip("Play a file from this machine through the installed decoders");
        openFile.onAction(() -> pick(openFile, caption).ifPresent(file -> {
            current[0] = ownFile(file);
            caption.setText(current[0].caption());
            play(pane, view, facts, open, current[0], sound[0]);
        }));

        // The same file, in a window of its own with nothing else in it, which is the shape a
        // player actually is, and the one thing a tab cannot show: a resizable window whose whole
        // content is the picture.
        Button openWindow = new Button("Open in window…").setSecondary(true);
        openWindow.setTooltip("Play a file in a resizable window of its own");
        openWindow.onAction(() -> pick(openWindow, caption).ifPresent(file ->
                openInOwnWindow(openWindow.scene().window(), file, sound[0], caption)));

        // In a row rather than in the stretched column, which would blow the picture up to the
        // panel's width and letterbox it against its own aspect ratio.
        Row picture = new Row();
        picture.add(pane);

        // The box is 2:1 and the pictures are 16:9, so the three fits are three different pictures.
        List<VideoView.Fit> fits = List.of(VideoView.Fit.CONTAIN, VideoView.Fit.COVER,
                VideoView.Fit.FILL);
        SegmentedControl fitPicker = new SegmentedControl(List.of("Contain", "Cover", "Fill"));
        fitPicker.setTooltip("Letterbox inside the box, crop to cover it, or stretch to it");
        fitPicker.onSelect(index -> view.setFit(fits.get(index)));

        limn.components.Checkbox soundToggle =
                new limn.components.Checkbox(limn.components.Checkbox.Variant.SWITCH, "Soundtrack");
        soundBox[0] = soundToggle;
        soundToggle.setChecked(sound[0]);
        soundToggle.setTooltip("Decode on a thread of its own and time the pictures by the audio");
        soundToggle.onChange(on -> {
            sound[0] = on;
            play(pane, view, facts, open, current[0], on);
        });

        // The picker gets a row to itself: at the standalone scene's source count it fills the
        // width on its own, and a button sharing that row is a button off the edge.
        Row controls = new Row();
        controls.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        controls.add(restart);
        if (staticFrame < 0) {
            // Not while frozen: --video-frame hands the view a stream holding exactly one picture,
            // and a control that could replace it is a control that could make the capture depend
            // on when it ran.
            controls.add(openFile);
            controls.add(openWindow);
        }
        controls.add(fitPicker);

        // The sound controls get a row of their own, and the reason is arithmetic rather than
        // taste: the row above is already about eight hundred points wide at its widest, which is
        // this scene's whole window, and a Row does not wrap: a sixth control on it is a control
        // pushed off the edge. They belong together anyway, because the second answers the
        // question the first raises: whether there is sound, and then WHICH sound.
        //
        // Both are absent from every capture, for the reason the soundtrack switch always was: a
        // screenshot run starts no audio device and therefore no player to change the track of.
        Row soundControls = new Row();
        soundControls.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        if (soundAllowed && staticFrame < 0) {
            soundControls.add(soundToggle);
            soundControls.add(new AudioTracks(open, view));
        }

        // ORDER MATTERS, and it is the picture that decides it. This tab lives in a pane whose body
        // is a couple of hundred points tall at the window this repository captures at, and the
        // prose that used to sit above the picture pushed it below the fold: what a reader saw on
        // opening the tab was a twenty-point sliver of video, which reads as a decoder that does
        // not work rather than as a panel that does not fit. So the picker and the picture come
        // first, and everything that describes them comes after: a caption below a picture is
        // still a caption, and a picture below three paragraphs is not a picture.
        Column column = new Column();
        column.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(new Label("Video: decoded planes, converted on the device")
                .setFont(theme.title).setStrong(true));
        column.add(picker);
        column.add(picture);
        column.add(controls);
        if (soundAllowed && staticFrame < 0) {
            column.add(soundControls);
        }
        column.add(caption);
        column.add(facts);
        column.add(new Label("Two pure-Java decoders (a YUV4MPEG2 reader and a generator whose "
                + "every sample is arithmetic) and one with a trimmed FFmpeg behind a JNI shim, "
                + "which is absent unless it has been built and says so rather than failing. No "
                + "media file is committed here: every one of these is produced on the way in.")
                .setMuted(true).setWrap(true));
        if (soundAllowed && staticFrame < 0) {
            column.add(new PlayerStatus(open));
            column.add(new Label("The soundtrack clicks once a second and higher every fifth, and "
                    + "the counter pattern draws the picture index, so a click landing anywhere "
                    + "but on a multiple of thirty is audible drift, not a matter of taste. It is "
                    + "generated sample by sample and handed over already open, the way a "
                    + "container's audio track is; no file is written and none is committed.")
                    .setMuted(true).setWrap(true));
        }
        if (staticFrame >= 0) {
            column.add(new Label("Frozen on picture " + staticFrame + " (--video-frame)")
                    .setMuted(true));
        }
        return column;
    }

    /** The standalone scene, for {@code --scene video}: every source, at twice the picture. */
    static Widget content() {
        return new ScrollView(tabContent(SCENE_PICTURE_WIDTH, SCENE_PICTURE_HEIGHT, SOURCES));
    }

    /** Opens {@code entry} and plays it from its first picture. */
    private static void play(VideoPane pane, VideoView view, Label facts, Playing open, Source entry,
                             boolean withSound) {
        play(pane, view, facts, open, entry, withSound, 0);
    }

    /**
     * Withdraws what was opening, closes what was playing, and starts {@code entry} opening on a
     * worker, so a pick costs the window the frame it takes to say so and not the half second the
     * container takes.
     *
     * <p>The closing order matters and is the ownership rule in three lines: the view is detached
     * from what it was showing first, then the player is closed (which waits for its decode thread
     * to let go), and only then is the stream closed. Closing the stream first would be a decode
     * against a torn down decoder, and nothing below would report it as one.
     *
     * <p><b>The open in flight is cancelled before this one starts</b>, which is the whole reason a
     * job is held between picks: two opens racing land in the order they finished, and what has to
     * be shown is the entry last asked for. What the abandoned one had already opened is closed on
     * a worker rather than leaked, by the discard guard the job carries.
     *
     * <p><b>An open with nowhere to deliver runs here instead</b>, and the condition is the pane
     * having no scene yet: that is the tab being built, where there is no window to freeze, no
     * frame loop to keep alive, and a delivery would land on a frame a {@code --screenshot} run
     * has already captured past.
     *
     * @param resumeMicros where to seek to once the stream lands, or 0 to start from the beginning
     */
    private static void play(VideoPane pane, VideoView view, Label facts, Playing open, Source entry,
                             boolean withSound, long resumeMicros) {
        view.setSource(null);
        view.setPlayer(null);
        open.closeAll();
        if (pane.scene() == null) {
            Opened opened;
            try {
                opened = entry.opener().openNow();
            } catch (RuntimeException failure) {
                // Caught here so that the two paths say the same thing: a file the decoder
                // refuses is a sentence either way, and not a crash on the way to the first
                // frame because it happened to be the entry the tab was built on.
                showFailure(pane, facts, failure);
                return;
            }
            show(pane, view, facts, open, opened, withSound, resumeMicros);
            return;
        }
        pane.showOpening(entry.label());
        facts.setText("Opening " + entry.label() + "…");
        long startedNanos = System.nanoTime();
        open.opening = entry.opener().start(new Pick(
                opened -> {
                    open.opening = null;
                    if (opened != null) {
                        // The evidence that the cost moved rather than went away: this is how long
                        // the pick took, and the window drew every frame of it.
                        System.out.printf("Video: '%s' opened in %d ms, off the UI thread%n",
                                entry.label(), (System.nanoTime() - startedNanos) / 1_000_000L);
                    }
                    show(pane, view, facts, open, opened, withSound, resumeMicros);
                },
                failure -> {
                    open.opening = null;
                    showFailure(pane, facts, failure);
                },
                pane::showOpeningProgress,
                () -> pane.scene() != null));
    }

    /**
     * What a failed open leaves on screen: a sentence in the picture's own box (the same place an
     * unplayable source puts one), and never a stack trace out of a worker.
     */
    private static void showFailure(VideoPane pane, Label facts, Throwable failure) {
        pane.showNotice("This source did not open");
        facts.setText(describeFailure(failure));
    }

    /**
     * Hands over what an open produced: the pictures to the view, through a player when there is to
     * be sound and directly when there is not.
     *
     * <p>On the UI thread, after {@link #play} has already detached and closed whatever was here
     * before, so this both starts nothing and closes nothing.
     */
    private static void show(VideoPane pane, VideoView view, Label facts, Playing open,
                             Opened opened, boolean withSound, long resumeMicros) {
        if (opened == null) {
            // A decoder that is not installed on this machine is an ordinary state of the world,
            // and it reaches the viewer as a sentence rather than as a stack trace. Every other
            // entry keeps working: nothing about this one has touched the facade or the view.
            //
            // The sentence goes in the PICTURE's box, not only in the caption below it. In a panel
            // short enough that the caption is below the fold (which the Kitchen Sink's is), a
            // message down there is a message nobody reads, and an empty rectangle is all that is
            // left of it.
            pane.showNotice(encodedUnavailableNotice());
            facts.setText(encodedUnavailableDetail());
            return;
        }
        pane.showPicture();
        VideoStreamSource source = opened.video();
        facts.setText(describe(source) + describeAudioTracks(opened)
                + describeSubtitleTracks(opened));
        // SUBTITLES ARE THE APPLICATION'S DECISION and the toolkit makes none: a container opens
        // with none selected whatever the file marks as default, so this is where a viewer's
        // preference would be applied. This demo turns the first text track on, because a Video tab
        // that never shows a cue demonstrates nothing about the SPI that produces them.
        //
        // BEFORE ANYTHING READS A PICTURE, which is not a tidiness preference. Cues ride the
        // pictures' demultiplexing and an unselected track's packets are freed as they are met, so
        // a selection made after something has already read forward has missed everything it read
        // past, and the demuxer will not go back for them. The frozen source below decodes to its
        // picture in its own constructor, which made this the difference between a capture with a
        // subtitle in it and one without.
        if (opened.media() != null) {
            for (limn.video.ffmpeg.FfmpegMedia.SubtitleTrack track : opened.subtitleTracks()) {
                if (track.text() && track.decodable()) {
                    opened.media().selectSubtitles(track.index());
                    break;
                }
            }
        }
        // Frozen: hand the view a stream holding exactly one picture, so the capture does not depend
        // on how many frames were rendered before it, on a clock, or on the speed of the machine.
        FrozenSource frozen = staticFrame >= 0 ? new FrozenSource(source, staticFrame) : null;
        VideoStreamSource shown = frozen != null ? frozen : source;
        open.stream = shown;
        open.media = opened.media();
        // Where to ask for the cue. Live, it is where the pictures have reached. Frozen, it is the
        // held picture's OWN presentation time and never the clock's: a --video-frame capture must
        // hash the same twice, and a clock free-running on the wall would make the cue depend on
        // how long the process took to reach the paint.
        pane.showCuesOf(opened.media(),
                frozen != null ? frozen::ptsMicros : view::positionMicros);

        if (!withSound) {
            view.setSource(shown);
            resume(view, resumeMicros);
            return;
        }
        // A container's own track where there is one, and the generated click track where there is
        // not. The two are the same shape by the time they get here (an AudioStreamSource the
        // player takes ownership of), which is the point of the audio track having been given
        // nowhere special to arrive.
        limn.sound.AudioStreamSource track = opened.audio() != null
                ? opened.audio()
                : new ClickTrack(TRACK_SECONDS);
        limn.video.MediaPlayer player = new limn.video.MediaPlayer(shown)
                .setAudio(track, TRACK_OPTIONS);
        player.setLooping(true);
        open.player = player;
        view.setPlayer(player);
        resume(view, resumeMicros);
        // Deliberately NOT started here. A player started at construction plays on a tab nobody has
        // opened (sound and all) because a player is not stopped by a view being hidden, and
        // should not be: that is the widget's contract and the right one. So starting it is the
        // application's call, and this application makes it in VideoPane, the first time the
        // picture is actually painted.
    }

    /** Puts a re-opened source back where the one it replaced had reached. Nothing at 0. */
    private static void resume(VideoView view, long resumeMicros) {
        if (resumeMicros > 0 && view.canSeek()) {
            view.seek(resumeMicros);
        }
    }

    /** Long enough to watch, and finite so the handover to the wall clock at its end is visible. */
    private static final int TRACK_SECONDS = 40;

    /**
     * How this demo asks for a soundtrack. One constant rather than three copies, because a player
     * built to replace another has to ask for the same thing the first one did or the language
     * change is also a change of bus, priority and gain.
     */
    private static final limn.sound.PlayOptions TRACK_OPTIONS = limn.sound.PlayOptions.DEFAULTS
            .withBus(limn.sound.AudioBus.MUSIC)
            .withPriority(limn.sound.PlayOptions.Priority.HIGH)
            .withGain(0.7f);

    /**
     * What the container says about its soundtracks, or nothing at all when there is one or none:
     * an entry that reads "1 audio track" on every synthetic source is noise, and the line below the
     * picture is already long.
     */
    private static String describeAudioTracks(Opened opened) {
        List<limn.video.ffmpeg.FfmpegMedia.AudioTrack> tracks = opened.audioTracks();
        if (tracks.size() < 2) {
            return "";
        }
        StringBuilder text = new StringBuilder(" · " + tracks.size() + " audio tracks: ");
        for (int index = 0; index < tracks.size(); index++) {
            text.append(index == 0 ? "" : ", ").append(trackLabel(tracks.get(index)));
        }
        return text.toString();
    }

    /**
     * What the container says about its subtitle tracks. Unlike the soundtracks this is written
     * even for a single track, because a viewer looking at a cue wants to know what else there is,
     * and because a listing is the only place a track this build refuses shows up at all.
     */
    private static String describeSubtitleTracks(Opened opened) {
        List<limn.video.ffmpeg.FfmpegMedia.SubtitleTrack> tracks = opened.subtitleTracks();
        if (tracks.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder(" · " + tracks.size() + " subtitle track"
                + (tracks.size() == 1 ? ": " : "s: "));
        for (int index = 0; index < tracks.size(); index++) {
            limn.video.ffmpeg.FfmpegMedia.SubtitleTrack track = tracks.get(index);
            text.append(index == 0 ? "" : ", ")
                    .append(track.language() != null ? track.language() : "no language")
                    .append(" (").append(track.codec());
            if (!track.text()) {
                text.append(", bitmap: not shown");
            } else if (!track.decodable()) {
                text.append(", no decoder");
            }
            text.append(')');
        }
        return text.toString();
    }

    /** A track as a viewer reads it: its language where it states one, and its shape either way. */
    private static String trackLabel(limn.video.ffmpeg.FfmpegMedia.AudioTrack track) {
        String language = track.language() != null ? track.language() : "no language";
        return language + " (" + track.codec() + ", "
                + (track.sourceChannels() == 1 ? "mono" : track.sourceChannels() + "ch") + ")";
    }

    private static String describe(VideoStreamSource source) {
        String rate = source.frameRateNum() == 0
                ? "rate unknown"
                : String.format("%.3f per second", source.frameRateNum()
                / (double) source.frameRateDen());
        VideoColor color = source.color();
        return source.width() + "×" + source.height() + " · " + source.pixelFormat() + " · "
                + (color.isSpecified()
                ? color.matrix() + " " + color.range()
                : "colour unsignalled (decoded as BT709 LIMITED)")
                + " · " + rate
                + (source.canReset() ? " · rewindable" : " · not rewindable");
    }

    /**
     * A stream holding exactly one picture of another stream: the demo's determinism instrument,
     * demo-local because a widget has no business knowing what "picture 42" means and a real player
     * reaches one by seeking.
     *
     * <p>It decodes to that picture when it is built, hands it over once and then reports the end,
     * and says it cannot be rewound, so a view showing it presents that picture and never asks for
     * anything again: two runs of the same command produce the same pixels.
     */
    private static final class FrozenSource implements VideoStreamSource {

        /** Reads to attempt beyond the wanted index before giving up, in case of PENDING. */
        private static final int SLACK = 16;

        private final VideoStreamSource delegate;
        private boolean ready;
        private boolean delivered;
        private long ptsMicros;

        /**
         * @return the held picture's own presentation time, which is what anything timed against
         *         this stream must use. The view's clock is not that: with one picture and no
         *         player it free-runs on the wall, so a capture asking it a question would get a
         *         different answer on a slower machine
         */
        long ptsMicros() {
            return ptsMicros;
        }

        FrozenSource(VideoStreamSource delegate, int index) {
            this.delegate = delegate;
            int decoded = 0;
            // Bounded: a source that only ever answered PENDING would otherwise never return.
            for (int attempt = 0; decoded <= index && attempt <= index + SLACK; attempt++) {
                switch (delegate.readFrame()) {
                    case PENDING -> {
                    }
                    case END -> {
                        return; // the stream is shorter than the picture asked for
                    }
                    case FRAME -> {
                        if (decoded == index) {
                            ready = true;
                            ptsMicros = Math.max(0, delegate.frame().ptsMicros());
                            return;
                        }
                        delegate.frame().release();
                        decoded++;
                    }
                    default -> throw new IllegalStateException("unreachable");
                }
            }
        }

        @Override
        public int width() {
            return delegate.width();
        }

        @Override
        public int height() {
            return delegate.height();
        }

        @Override
        public PixelFormat pixelFormat() {
            return delegate.pixelFormat();
        }

        @Override
        public VideoColor color() {
            return delegate.color();
        }

        @Override
        public int frameRateNum() {
            return delegate.frameRateNum();
        }

        @Override
        public int frameRateDen() {
            return delegate.frameRateDen();
        }

        @Override
        public Read readFrame() {
            if (ready && !delivered) {
                delivered = true;
                return Read.FRAME;
            }
            return Read.END;
        }

        @Override
        public VideoFrame frame() {
            return delivered ? delegate.frame() : null;
        }

        @Override
        public void reset() {
            throw new UnsupportedOperationException("a frozen stream holds one picture");
        }

        @Override
        public boolean canReset() {
            return false;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
