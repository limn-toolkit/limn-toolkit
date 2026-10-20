package limn.demo;

import limn.components.Label;
import limn.components.SegmentedControl;
import limn.components.VideoView;
import limn.scene.Widget;
import limn.testing.HeadlessUi;
import limn.video.VideoStreamSource;
import limn.video.Videos;
import limn.video.decode.SyntheticVideoDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 */
class ShowcaseVideoPageTest {

    @TempDir
    Path directory;

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
        } finally {
            VideoScene.setSoundAllowed(true);
            Videos.uninstallDecoder(bars);
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
        assertTrue(output.contains("segments: showcase.video.clip, showcase.video.bars"), output);
        assertTrue(output.contains("selected: showcase.video.bars"), output);
        assertTrue(output.contains("picture: 640x360"), output);
        assertTrue(output.contains("credit shown: false"), output);
    }

    /** Builds the page with the decoder unable to load, and says what it opened on. */
    static final class NoDecoder {

        public static void main(String[] args) {
            Videos.installDecoder(new SyntheticVideoDecoder());
            VideoScene.setSoundAllowed(false);
            try (HeadlessUi ui = new HeadlessUi()) {
                System.out.println("decoder available: " + limn.video.ffmpeg.FfmpegLibrary.isAvailable());
                Widget<?> page = VideoScene.pageContent();
                SegmentedControl picker = sourcePicker(page);
                List<String> keys = new ArrayList<>();
                for (int i = 0; i < picker.segmentCount(); i++) {
                    keys.add(picker.segmentSource(i).key());
                }
                System.out.println("segments: " + String.join(", ", keys));
                System.out.println("selected: " + picker.segmentSource(picker.selectedIndex()).key());
                VideoStreamSource shown = find(page, VideoView.class).get(0).source();
                System.out.println("picture: " + (shown == null ? "none"
                        : shown.width() + "x" + shown.height()));
                System.out.println("credit shown: " + creditShown(page));
            }
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
