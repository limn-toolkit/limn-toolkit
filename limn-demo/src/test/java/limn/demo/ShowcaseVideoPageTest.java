package limn.demo;

import limn.components.Label;
import limn.components.SegmentedControl;
import limn.components.VideoView;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.HeadlessUi;
import limn.video.VideoStreamSource;
import limn.video.Videos;
import limn.video.decode.SyntheticVideoDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Showcase's Video page opens on the sample clip, Big Buck Bunny, with its credit under it, as
 * its licence asks, and keeps the colour bars in its picker.
 *
 * <p>The clip is the bundled excerpt and nothing else, whatever FFmpeg payload is on the class
 * path: a developer's {@code full} payload can encode, and the page's clip entry then played a
 * clip written on the spot, eight colour bars under the bunny's credit, so the page showed colour
 * bars whichever entry was picked. Where the decoder cannot load at all the page opens on the bars
 * instead of on a picture that says it cannot play.
 *
 * <p>The clip is opened on a worker, as every pick of the picker is: a container is a demultiplexer
 * and a decoder set up, and on a machine's first run the native extracted behind a lock, so opening
 * it while the page was built froze the window for a tenth of a second to more than one on a click
 * in the navigation. A run nobody watches (a screenshot, a film) still opens it while the page is
 * built, so its first picture is the clip and never "Opening…".
 */
class ShowcaseVideoPageTest {

    /**
     * The transport's icons are SVG. With no renderer every frame threw as it painted them, before
     * the picture was asked for, and logged the throw: on GitHub's Linux runner and the Ubuntu
     * guest a played film stayed at 0 µs for 30 s under 220,000 such logs, where the development
     * Mac reached 200 ms at once.
     */
    private static final limn.graphics.SvgRenderer RENDERER =
            limn.testfixtures.FakeSvg.renderer((svg, px) -> new limn.graphics.Image(1, 1, new byte[4]));

    @TempDir
    Path directory;

    @BeforeEach
    void installSvg() {
        limn.graphics.SvgIcon.installRenderer(RENDERER);
    }

    @AfterEach
    void uninstallSvg() {
        limn.graphics.SvgIcon.uninstallRenderer(RENDERER);
    }

    @Test
    void thePageOpensOnTheBundledClipWithItsCreditEvenWhereThePayloadCanEncode() {
        assumeTrue(limn.video.ffmpeg.FfmpegLibrary.isAvailable(), "needs the FFmpeg native");
        SyntheticVideoDecoder bars = new SyntheticVideoDecoder();
        Videos.installDecoder(bars);
        VideoScene.setSoundAllowed(false); // no audio device: the view is handed the stream itself
        try (HeadlessUi ui = new HeadlessUi()) {
            Widget<?> page = VideoScene.pageContent();
            SegmentedControl picker = sourcePicker(page);
            assertEquals(2, picker.segmentCount(), "the clip and the bars");
            assertEquals(VideoScene.Text.CLIP, picker.segmentSource(picker.selectedIndex()),
                    "the page opens on the sample clip");
            assertTrue(creditShown(page), "the clip's credit is shown under it");

            VideoView view = find(page, VideoView.class).get(0);
            ui.pumpUntil(() -> view.source() != null, 30_000_000_000L);
            assertClip(view);
        } finally {
            VideoScene.setSoundAllowed(true);
            Videos.uninstallDecoder(bars);
        }
    }

    @Test
    void buildingThePageLeavesOpeningTheClipToAWorker() throws Exception {
        assumeTrue(limn.video.ffmpeg.FfmpegLibrary.isAvailable(), "needs the FFmpeg native");
        SyntheticVideoDecoder bars = new SyntheticVideoDecoder();
        Videos.installDecoder(bars);
        VideoScene.setSoundAllowed(false);
        CountDownLatch release = new CountDownLatch(1);
        try (HeadlessUi ui = new HeadlessUi()) {
            // The one worker held, so whatever the build hands it cannot have run when the build
            // returns: a picture in the view by then was opened on this thread, the UI thread,
            // which is the frozen window a click in the navigation used to cost.
            CountDownLatch held = new CountDownLatch(1);
            ui.workers().execute(() -> {
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(held.await(10, TimeUnit.SECONDS), "the worker never started");

            Widget<?> page = VideoScene.pageContent();
            VideoView view = find(page, VideoView.class).get(0);
            assertNull(view.source(), "the clip was opened on the UI thread while the page was built");
            SegmentedControl picker = sourcePicker(page);
            assertEquals(VideoScene.Text.CLIP, picker.segmentSource(picker.selectedIndex()),
                    "the picker says what is opening");
            assertTrue(creditShown(page), "the credit is there before the picture is");

            release.countDown();
            ui.pumpUntil(() -> view.source() != null, 30_000_000_000L);
            assertClip(view);
            assertEquals(VideoScene.Text.CLIP, picker.segmentSource(picker.selectedIndex()));
        } finally {
            release.countDown();
            VideoScene.setSoundAllowed(true);
            Videos.uninstallDecoder(bars);
        }
    }

    @Test
    void aPageLeftBeforeTheClipOpenedOpensItAgainWhenItIsBack() throws Exception {
        assumeTrue(limn.video.ffmpeg.FfmpegLibrary.isAvailable(), "needs the FFmpeg native");
        SyntheticVideoDecoder bars = new SyntheticVideoDecoder();
        Videos.installDecoder(bars);
        VideoScene.setSoundAllowed(false);
        CountDownLatch release = new CountDownLatch(1);
        try (HeadlessUi ui = new HeadlessUi()) {
            ui.workers().execute(() -> {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            // A click on the Video page and on another one before the clip has opened: the
            // Showcase takes the page out of its window and keeps it for the next visit.
            Widget<?> page = VideoScene.pageContent();
            Column holder = new Column();
            holder.add(page);
            new Scene(holder, () -> 0L);
            holder.remove(page);

            release.countDown();
            CountDownLatch answered = new CountDownLatch(1);
            ui.workers().execute(answered::countDown); // one worker: behind the clip's open
            assertTrue(answered.await(30, TimeUnit.SECONDS), "the clip's open never finished");
            ui.runtime().drain(); // its answer, refused while the page is away
            VideoView view = find(page, VideoView.class).get(0);
            assertNull(view.source(), "a page away from its window was handed the clip");

            holder.add(page); // the page shown again
            ui.pumpUntil(() -> view.source() != null, 30_000_000_000L);
            assertClip(view);
        } finally {
            release.countDown();
            VideoScene.setSoundAllowed(true);
            Videos.uninstallDecoder(bars);
        }
    }

    /**
     * An open that reports how far it has got, while the page is away, is not taken for one whose
     * answer was refused: its answer may still land after the page is back, and opening it again
     * then opened the same entry twice, the first withdrawn and closed unseen. Whether the answer
     * was still wanted was asked before every delivery, progress included, and the asking recorded
     * a refusal.
     */
    @Test
    void progressReportedWhileThePageIsAwayDoesNotOpenTheEntryAgain() throws Exception {
        assumeTrue(limn.video.ffmpeg.FfmpegLibrary.isAvailable(), "needs the FFmpeg native");
        CountDownLatch reported = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        SyntheticVideoDecoder bars = new SyntheticVideoDecoder();
        limn.video.VideoDecoder reporting = new limn.video.VideoDecoder() {
            @Override
            public String name() {
                return "reporting bars";
            }

            @Override
            public boolean supports(Path file) {
                return bars.supports(file);
            }

            @Override
            public VideoStreamSource openStream(Path file) {
                return bars.openStream(file);
            }

            @Override
            public VideoStreamSource openStream(Path file, limn.concurrent.Progress progress) {
                opens.incrementAndGet();
                progress.report(0.5);
                reported.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return bars.openStream(file);
            }
        };
        Videos.installDecoder(reporting);
        VideoScene.setSoundAllowed(false);
        try (HeadlessUi ui = new HeadlessUi()) {
            Widget<?> page = VideoScene.pageContent();
            Column holder = new Column();
            holder.add(page);
            Scene scene = new Scene(holder, () -> 0L);
            VideoView view = find(page, VideoView.class).get(0);
            ui.pumpUntil(() -> view.source() != null, 30_000_000_000L); // the clip

            SegmentedControl picker = sourcePicker(page);
            scene.requestFocus(picker);
            limn.testing.SceneDriver.drive(scene).press(limn.input.Keys.RIGHT); // the bars, through the facade
            assertEquals(VideoScene.Text.BARS, picker.segmentSource(picker.selectedIndex()), "sanity: the bars picked");
            ui.pumpUntil(() -> reported.getCount() == 0, 30_000_000_000L);
            holder.remove(page); // the Showcase showing another page
            ui.runtime().drain(); // the progress report, asked about while the page is away
            holder.add(page); // and back, before the open has answered

            release.countDown();
            ui.pumpUntil(() -> view.source() != null, 30_000_000_000L);
            assertEquals(1, opens.get(), "the bars were opened again although their answer was still to come");
            VideoStreamSource shown = view.source();
            view.setSource(null);
            shown.close();
        } finally {
            release.countDown();
            VideoScene.setSoundAllowed(true);
            Videos.uninstallDecoder(reporting);
        }
    }

    /**
     * The Showcase keeps its pages for the next visit, and a Video page left with its film open held
     * the player (its decoding thread, its stream and the audio device) until the process ended,
     * where the backend closed it with "A MediaPlayer was left open". The page now closes the player
     * as it leaves the window, a page switch or the window closing alike, and opens the film again
     * where it had reached when it is back, playing if it was.
     */
    @Test
    void aPageThatLeavesClosesItsPlayerAndOpensTheFilmAgainWhereItWasWhenItIsBack() throws Exception {
        assumeTrue(limn.video.ffmpeg.FfmpegLibrary.isAvailable(), "needs the FFmpeg native");
        SyntheticVideoDecoder bars = new SyntheticVideoDecoder();
        Videos.installDecoder(bars);
        VideoScene.setSoundAllowed(true); // a player, with the film's soundtrack (no engine: silent)
        try (HeadlessUi ui = new HeadlessUi()) {
            limn.testing.HeadlessBackend backend = new limn.testing.HeadlessBackend(ui.runtime());
            limn.testing.HeadlessWindow window = backend.open("Limn Showcase", 900, 700);
            Widget<?> page = VideoScene.pageContent();
            Column holder = new Column();
            holder.add(page);
            Scene scene = new Scene(holder);
            scene.setTextRuler(limn.testing.HeadlessWindow.RULER);
            scene.bind(window);
            VideoView view = find(page, VideoView.class).get(0);
            ui.pumpUntil(() -> view.player() != null, 30_000_000_000L);
            limn.video.MediaPlayer first = view.player();
            view.setPaused(false); // the transport's Play
            ui.pumpUntil(() -> {
                window.frame();
                return first.positionMicros() > 200_000;
            }, 30_000_000_000L);

            holder.remove(page); // the Showcase showing another page
            long leftAt = first.positionMicros();

            assertEquals(limn.video.MediaPlayer.State.CLOSED, first.state(), "the player closed with the page");
            assertNull(view.player());

            holder.add(page); // and back
            ui.pumpUntil(() -> {
                window.frame();
                return view.player() != null;
            }, 30_000_000_000L);
            limn.video.MediaPlayer again = view.player();
            assertTrue(again != first, "a player of its own, the film opened again");
            assertTrue(Math.abs(again.positionMicros() - leftAt) < 200_000,
                    "at the picture it had reached: " + again.positionMicros() + " against " + leftAt);
            ui.pumpUntil(() -> {
                window.frame();
                return again.state() == limn.video.MediaPlayer.State.PLAYING;
            }, 30_000_000_000L);

            window.close(); // the window closing takes the page out too
            ui.runtime().drain();
            assertEquals(limn.video.MediaPlayer.State.CLOSED, again.state(), "and the window closing closes it");
        } finally {
            VideoScene.setSoundAllowed(true);
            Videos.uninstallDecoder(bars);
        }
    }

    /**
     * The bars playing as the page leaves, and opening them again failing as it comes back (the
     * file gone, a decoder that refuses it): the page said it could not play them and kept the note
     * that it was playing as it left, so the next entry the viewer picked started by itself,
     * though a video starts paused.
     */
    @Test
    void aFilmThatCannotBeOpenedAgainOnReturnLeavesTheNextEntryPaused() throws Exception {
        assumeTrue(limn.video.ffmpeg.FfmpegLibrary.isAvailable(), "needs the FFmpeg native");
        SyntheticVideoDecoder bars = new SyntheticVideoDecoder();
        java.util.concurrent.atomic.AtomicBoolean gone = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
        limn.video.VideoDecoder vanishing = new limn.video.VideoDecoder() {
            @Override
            public String name() {
                return "bars that can go";
            }

            @Override
            public boolean supports(Path file) {
                return bars.supports(file);
            }

            @Override
            public VideoStreamSource openStream(Path file) {
                if (gone.get()) {
                    throw new IllegalStateException("the file is gone");
                }
                opened.incrementAndGet();
                return bars.openStream(file);
            }
        };
        Videos.installDecoder(vanishing);
        VideoScene.setSoundAllowed(true); // the clip gets a player (no engine: silent)
        try (HeadlessUi ui = new HeadlessUi()) {
            limn.testing.HeadlessBackend backend = new limn.testing.HeadlessBackend(ui.runtime());
            limn.testing.HeadlessWindow window = backend.open("Limn Showcase", 900, 700);
            Widget<?> page = VideoScene.pageContent();
            Column holder = new Column();
            holder.add(page);
            Scene scene = new Scene(holder);
            scene.setTextRuler(limn.testing.HeadlessWindow.RULER);
            scene.bind(window);
            VideoView view = find(page, VideoView.class).get(0);
            ui.pumpUntil(() -> view.player() != null, 30_000_000_000L); // the clip
            limn.video.MediaPlayer clip = view.player();
            SegmentedControl picker = sourcePicker(page);
            scene.requestFocus(picker);
            limn.testing.SceneDriver.drive(scene).press(limn.input.Keys.RIGHT); // the bars
            assertEquals(VideoScene.Text.BARS, picker.segmentSource(picker.selectedIndex()), "sanity: the bars picked");
            ui.pumpUntil(() -> opened.get() == 1 && view.player() != clip
                    && (view.player() != null || view.source() != null), 30_000_000_000L);
            view.setPaused(false); // the transport's Play
            window.frame();
            assertFalse(view.isPaused(), "sanity: the bars play");

            gone.set(true);
            holder.remove(page); // the Showcase showing another page, the bars playing
            holder.add(page); // and back: opening the bars again fails
            CountDownLatch answered = new CountDownLatch(1);
            ui.workers().execute(answered::countDown); // one worker: behind the open
            assertTrue(answered.await(30, TimeUnit.SECONDS), "the open never finished");
            ui.runtime().drain();
            window.frame();
            assertNull(view.source(), "sanity: nothing could be opened");
            assertNull(view.player(), "sanity: nothing could be opened");

            gone.set(false);
            scene.requestFocus(picker);
            limn.testing.SceneDriver.drive(scene).press(limn.input.Keys.LEFT); // the clip, chosen
            assertEquals(VideoScene.Text.CLIP, picker.segmentSource(picker.selectedIndex()), "sanity: the clip picked");
            ui.pumpUntil(() -> view.player() != null, 30_000_000_000L);
            for (int i = 0; i < 5; i++) {
                window.frame(); // the frames the pane resumes on
                ui.runtime().drain();
            }

            assertTrue(view.isPaused(), "the entry chosen next starts paused, as every video does");
            assertEquals(limn.video.MediaPlayer.State.IDLE, view.player().state());
            window.close();
            ui.runtime().drain();
        } finally {
            VideoScene.setSoundAllowed(true);
            Videos.uninstallDecoder(vanishing);
        }
    }

    @Test
    void aRunNobodyWatchesStillOpensTheClipWhileThePageIsBuilt() throws Exception {
        assumeTrue(limn.video.ffmpeg.FfmpegLibrary.isAvailable(), "needs the FFmpeg native");
        SyntheticVideoDecoder bars = new SyntheticVideoDecoder();
        Videos.installDecoder(bars);
        VideoScene.setSoundAllowed(false);
        VideoScene.setOpenWhileBuilding(true);
        CountDownLatch release = new CountDownLatch(1);
        try (HeadlessUi ui = new HeadlessUi()) {
            // The worker held here too: a screenshot's or a film's first picture must not wait
            // for one, or it would be "Opening…".
            ui.workers().execute(() -> {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            Widget<?> page = VideoScene.pageContent();
            assertClip(find(page, VideoView.class).get(0));
        } finally {
            release.countDown();
            VideoScene.setOpenWhileBuilding(false);
            VideoScene.setSoundAllowed(true);
            Videos.uninstallDecoder(bars);
        }
    }

    @Test
    void theLabSceneCallsItsContainerEntryMp4AndNotTheSampleClip() {
        // Its MP4 entry plays the clip it writes (eight colour bars) wherever the payload can
        // encode, so "Sample clip" there would name the bars the bunny's name, which is what the
        // page did before it got a clip entry of its own.
        try (HeadlessUi ui = new HeadlessUi()) {
            List<String> labels = new ArrayList<>();
            for (SegmentedControl each : find(VideoScene.content(), SegmentedControl.class)) {
                for (int i = 0; i < each.segmentCount(); i++) {
                    labels.add(each.segmentSource(i).get());
                }
            }
            assertTrue(labels.contains("MP4"), labels.toString());
            assertFalse(labels.contains(VideoScene.Text.CLIP.get()), labels.toString());
        }
    }

    /** The bundled excerpt is in the view, and is closed again. */
    private static void assertClip(VideoView view) {
        VideoStreamSource shown = view.source();
        assertNotNull(shown, "the clip opened and the view shows it");
        try {
            // The bundled excerpt's own size (a row short of its name, as the original is),
            // and not the 320 by 180 a payload with an encoder writes and plays instead.
            assertEquals(640, shown.width(), "canWriteClip=" + limn.video.ffmpeg.FfmpegMedia.canWriteClip());
            assertEquals(359, shown.height(), "canWriteClip=" + limn.video.ffmpeg.FfmpegMedia.canWriteClip());
        } finally {
            view.setSource(null);
            shown.close();
        }
    }

    @Test
    void withoutTheDecoderThePageOpensOnTheBarsAndKeepsTheClipInItsPicker() throws Exception {
        // In a JVM of its own: whether the native loaded is decided once per process, and every
        // other test of this module may already have loaded it. An empty directory named as the
        // place to load it from is a machine without the payload, by the library's own switch.
        Path empty = Files.createDirectory(directory.resolve("no-payload"));
        Path log = directory.resolve("child.log");
        Process child = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D" + limn.video.ffmpeg.FfmpegLibrary.LIBRARY_PROPERTY + "=" + empty,
                "-cp", System.getProperty("java.class.path"), NoDecoder.class.getName()))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = child.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            child.destroyForcibly().waitFor();
        }
        String output = Files.readString(log);
        assertTrue(finished, "the child never finished:\n" + output);
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains("decoder available: false"), output);
        // Both ways the page opens: on a worker, as a window shows it, and while it is built, as
        // a screenshot or a film takes it.
        for (String run : List.of("worker", "while built")) {
            assertTrue(output.contains(run + " segments: showcase.video.clip, showcase.video.bars"), output);
            assertTrue(output.contains(run + " selected: showcase.video.bars"), output);
            assertTrue(output.contains(run + " picture: 640x360"), output);
            assertTrue(output.contains(run + " credit shown: false"), output);
        }
    }

    /** Builds the page with the decoder unable to load, and says what it opened on. */
    static final class NoDecoder {

        public static void main(String[] args) {
            Videos.installDecoder(new SyntheticVideoDecoder());
            VideoScene.setSoundAllowed(false);
            try (HeadlessUi ui = new HeadlessUi()) {
                System.out.println("decoder available: " + limn.video.ffmpeg.FfmpegLibrary.isAvailable());
                Widget<?> page = VideoScene.pageContent();
                // The clip's open fails on the worker, and the bars are opened after it there.
                VideoView view = find(page, VideoView.class).get(0);
                ui.pumpUntil(() -> view.source() != null, 30_000_000_000L);
                say("worker", page);

                VideoScene.setOpenWhileBuilding(true);
                say("while built", VideoScene.pageContent());
            }
        }

        private static void say(String run, Widget<?> page) {
            SegmentedControl picker = sourcePicker(page);
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < picker.segmentCount(); i++) {
                keys.add(picker.segmentSource(i).key());
            }
            System.out.println(run + " segments: " + String.join(", ", keys));
            System.out.println(run + " selected: " + picker.segmentSource(picker.selectedIndex()).key());
            VideoStreamSource shown = find(page, VideoView.class).get(0).source();
            System.out.println(run + " picture: " + (shown == null ? "none"
                    : shown.width() + "x" + shown.height()));
            System.out.println(run + " credit shown: " + creditShown(page));
        }
    }

    static SegmentedControl sourcePicker(Widget<?> page) {
        for (SegmentedControl each : find(page, SegmentedControl.class)) {
            for (int i = 0; i < each.segmentCount(); i++) {
                if (each.segmentSource(i) == VideoScene.Text.CLIP) {
                    return each;
                }
            }
        }
        throw new AssertionError("no source picker on the Video page");
    }

    static boolean creditShown(Widget<?> page) {
        for (Label each : find(page, Label.class)) {
            if (each.isVisible() && each.text().equals(VideoScene.Text.CREDIT.get())) {
                return true;
            }
        }
        return false;
    }

    static <T> List<T> find(Widget<?> root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T> void collect(Widget<?> widget, Class<T> type, List<T> found) {
        if (type.isInstance(widget)) {
            found.add(type.cast(widget));
        }
        for (Widget<?> child : widget.children()) {
            collect(child, type, found);
        }
    }
}
