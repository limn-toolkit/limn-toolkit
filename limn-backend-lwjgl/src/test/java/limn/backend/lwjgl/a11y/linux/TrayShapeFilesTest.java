package limn.backend.lwjgl.a11y.linux;

import limn.backend.spi.TrayPicture;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The files KDE's panel is given for an animated monochrome icon: each frame drawn as itself, and
 * written once however many times the animation comes round.
 */
class TrayShapeFilesTest {

    @TempDir
    Path runtime;

    /** Three frames, a bar that moves down the square, so no two frames' masks are alike. */
    private static final Icon MOVING = new Icon() {
        @Override
        public Image image(int pixelSize, boolean dark) {
            return image(pixelSize, dark, 0);
        }

        @Override
        public Image image(int pixelSize, boolean dark, int frame) {
            byte[] rgba = new byte[pixelSize * pixelSize * 4];
            int band = pixelSize / 3;
            for (int y = frame * band; y < (frame + 1) * band; y++) {
                for (int x = 0; x < pixelSize; x++) {
                    int at = (y * pixelSize + x) * 4;
                    rgba[at] = rgba[at + 1] = rgba[at + 2] = rgba[at + 3] = (byte) 0xFF;
                }
            }
            return new Image(pixelSize, pixelSize, rgba);
        }

        @Override
        public boolean isAnimated() {
            return true;
        }

        @Override
        public int frameCount() {
            return 3;
        }

        @Override
        public boolean tintable() {
            return true;
        }
    };

    @Test
    void eachFrameIsDrawnAsItselfAndWrittenOnce() throws Exception {
        TrayShapeFiles files = new TrayShapeFiles("orders", runtime.resolve("limn-tray"));
        Set<String> names = new HashSet<>();
        Set<String> contents = new HashSet<>();
        for (int turn = 0; turn < 3; turn++) {
            for (int frame = 0; frame < 3; frame++) {
                TrayWire.Named named = files.write(new TrayPicture(MOVING, null, false, frame));
                names.add(named.name());
                contents.add(Files.readString(Path.of(named.folder(), named.name() + ".svg")));
            }
        }
        assertEquals(3, names.size(), "a name per frame, the same one each time round: " + names);
        assertEquals(3, contents.size(), "and each frame's file draws that frame");
        assertEquals(3, files.written(), "three turns of the animation wrote three files");
        try (var listed = Files.list(files.folder())) {
            assertEquals(3, listed.count());
        }
    }

    @Test
    void anotherDotStartsNewNamesAndTakesTheOldFilesAway() throws Exception {
        TrayShapeFiles files = new TrayShapeFiles("orders", runtime.resolve("limn-tray"));
        TrayWire.Named plain = files.write(new TrayPicture(MOVING, null, false, 1));
        TrayWire.Named dotted = files.write(new TrayPicture(MOVING, Color.rgb(0xE53935), false, 1));
        assertNotEquals(plain.name(), dotted.name(), "a panel keeps what it loaded under a known name");
        assertFalse(Files.exists(Path.of(plain.folder(), plain.name() + ".svg")));
        assertTrue(Files.exists(Path.of(dotted.folder(), dotted.name() + ".svg")));
        files.delete();
        assertFalse(Files.exists(files.folder()));
    }
}
