package limn.demo;

import limn.components.Label;
import limn.components.Theme;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No widget of the Showcase draws something and says nothing about itself: the accessible walk
 * warns once per class about one that does, and five of the Showcase's (an icon's picture, the
 * toast's card, a split pane's surface, the video's pane and its audio-track holder) warned on
 * every run, so a real one elsewhere was easy to miss among them.
 *
 * <p>In a JVM of its own, because the walk warns about a class once per process and another test
 * of this module may already have drawn these pages.
 */
class ShowcaseSaysWhatItPaintsTest {

    @TempDir
    Path directory;

    @Test
    void noShowcasePageWarnsThatAWidgetIsAbsentFromTheAccessibleTree() throws Exception {
        Path log = directory.resolve("child.log");
        Process child = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Child.class.getName()))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = child.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            child.destroyForcibly().waitFor();
        }
        String output = Files.readString(log);
        assertTrue(finished, "the child never finished:\n" + output);
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains("pages drawn: "), output);
        List<String> warned = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.contains("absent from the accessible tree") && line.contains("limn.demo.")) {
                warned.add(line.trim());
            }
        }
        assertEquals(List.of(), warned);
    }

    /** Draws every page of the Showcase in a window with a reader's bridge, and says how many. */
    static final class Child {

        public static void main(String[] args) {
            try (HeadlessUi ui = new HeadlessUi(() -> 0L)) {
                I18n.setLocale(Locale.ENGLISH);
                Theme.setCurrent(Theme.dark());
                ControlSize.setProcessDefault(ControlSize.MEDIUM);
                TextRulers.install(HeadlessWindow.RULER);
                Images.installDecoder(bytes -> {
                    boolean png = bytes.length >= 24 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
                    int width = png ? java.nio.ByteBuffer.wrap(bytes, 16, 4).getInt() : 16;
                    int height = png ? java.nio.ByteBuffer.wrap(bytes, 20, 4).getInt() : 16;
                    return new Image(width, height, new byte[width * height * 4]);
                });
                limn.graphics.SvgIcon.installRenderer(
                        limn.testfixtures.FakeSvg.renderer((svg, px) -> new Image(1, 1, new byte[4])));
                HeadlessBackend backend = new HeadlessBackend(ui.runtime());
                int drawn = 0;
                for (AppScene.Place top : AppScene.places(new Label(""), () -> new Label(""))) {
                    for (AppScene.Place place : top.under) {
                        HeadlessWindow window = backend.open(place.id(), 1100, 760);
                        new Scene(place.page.get(), () -> 0L).bind(window);
                        window.frame();
                        window.bridge().tree(); // the walk, which is what warns
                        drawn++;
                    }
                }
                System.out.println("pages drawn: " + drawn);
            }
        }
    }
}
