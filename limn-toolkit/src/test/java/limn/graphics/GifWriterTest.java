package limn.graphics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is written as a GIF is read back as what it was: every pixel where the picture has no more
 * colours than the format holds, and near it where it has more; every frame's time, and the turns.
 * The reader is held to files it did not write by its own test, so a round trip here says the
 * writer wrote what a reader of the format reads.
 */
class GifWriterTest {

    private static Image picture(int w, int h, int colours, boolean holes, long seed) {
        Random random = new Random(seed);
        int[] palette = new int[colours];
        for (int i = 0; i < colours; i++) {
            palette[i] = random.nextInt(0x1000000);
        }
        byte[] rgba = new byte[w * h * 4];
        for (int o = 0; o < rgba.length; o += 4) {
            if (holes && random.nextInt(5) == 0) {
                continue; // not there: all four bytes 0
            }
            int colour = palette[random.nextInt(colours)];
            rgba[o] = (byte) (colour >> 16);
            rgba[o + 1] = (byte) (colour >> 8);
            rgba[o + 2] = (byte) colour;
            rgba[o + 3] = (byte) 0xFF;
        }
        return new Image(w, h, rgba);
    }

    private static Image animated(int frames, int colours, boolean holes, int turns) {
        List<Image> list = new ArrayList<>();
        int[] millis = new int[frames];
        for (int f = 0; f < frames; f++) {
            list.add(picture(19, 13, colours, holes, 100 + f));
            millis[f] = 30 + 20 * f;
        }
        return Images.animation(list, millis, turns);
    }

    @Test
    void anAnimationOfFewColoursComesBackPixelForPixelWithItsTimesAndTurns() {
        for (int turns : new int[] {0, 1, 2, 7}) {
            for (boolean holes : new boolean[] {false, true}) {
                Image written = animated(5, 40, holes, turns);
                Image read = Images.decode(Images.encode(written, ImageFormat.GIF));
                assertEquals(5, read.frameCount());
                assertEquals(turns, read.loopCount(), "turns " + turns);
                for (int f = 0; f < 5; f++) {
                    assertEquals(written.frameMillis(f), read.frameMillis(f));
                    assertArrayEquals(written.frame(f).pixels(), read.frame(f).pixels(),
                            "frame " + f + ", holes " + holes + ": a hole in one frame shows nothing of the frame before");
                }
            }
        }
    }

    @Test
    void aStillPictureIsWrittenAsOneAndEveryPlaceOfThePaletteIsUsable() {
        // 256 colours and no hole fills the palette; 255 and a hole fills it too; 256 and a hole is one too many.
        Image full = picture(64, 64, 256, false, 1);
        assertArrayEquals(full.pixels(), Images.decode(Images.encode(full, ImageFormat.GIF)).pixels());
        Image fullWithHoles = picture(64, 64, 255, true, 2);
        Image back = Images.decode(Images.encode(fullWithHoles, ImageFormat.GIF));
        assertFalse(back.isAnimated());
        assertArrayEquals(fullWithHoles.pixels(), back.pixels());
        Image oneColour = picture(3, 3, 1, false, 3);
        assertArrayEquals(oneColour.pixels(), Images.decode(Images.encode(oneColour, ImageFormat.GIF)).pixels());
    }

    @Test
    void aPictureOfMoreColoursThanTheFormatHoldsIsWrittenInTheOnesThatStandForItBest() {
        // A smooth ramp of some 4,000 colours: each pixel comes back within a few levels a channel.
        int w = 64;
        int h = 64;
        byte[] rgba = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int o = (y * w + x) * 4;
                rgba[o] = (byte) (x * 4);
                rgba[o + 1] = (byte) (y * 4);
                rgba[o + 2] = (byte) ((x + y) * 2);
                rgba[o + 3] = (byte) 0xFF;
            }
        }
        Image ramp = new Image(w, h, rgba);
        byte[] back = Images.decode(Images.encode(ramp, ImageFormat.GIF)).pixels();
        int worst = 0;
        long sum = 0;
        for (int o = 0; o < rgba.length; o++) {
            int difference = Math.abs((rgba[o] & 0xFF) - (back[o] & 0xFF));
            worst = Math.max(worst, difference);
            sum += difference;
        }
        assertTrue(worst <= 40, "the worst channel is off by " + worst);
        assertTrue(sum / (double) rgba.length < 6, "a channel is off by " + sum / (double) rgba.length + " on average");
    }

    @Test
    void theSameImageIsTheSameBytesAndALongRunOfOneColourFillsTheDictionaryAndGoesOn() {
        Image big = picture(300, 300, 200, false, 9);
        byte[] once = Images.encode(big, ImageFormat.GIF);
        assertArrayEquals(once, Images.encode(big, ImageFormat.GIF));
        assertArrayEquals(big.pixels(), Images.decode(once).pixels(), "ninety thousand pixels cross a full dictionary many times");
        assertTrue(Images.canEncode(ImageFormat.GIF));
    }

    @Test
    void anAnimationIsMadeOfFramesOfOneSizeEachShownForSomeTime() {
        Image a = picture(4, 4, 3, false, 1);
        Image b = picture(4, 5, 3, false, 2);
        assertThrows(IllegalArgumentException.class, () -> Images.animation(List.of(a, b), new int[] {10, 10}, 0));
        assertThrows(IllegalArgumentException.class, () -> Images.animation(List.of(a, a), new int[] {10}, 0));
        assertThrows(IllegalArgumentException.class, () -> Images.animation(List.of(a, a), new int[] {10, 0}, 0));
        assertThrows(IllegalArgumentException.class, () -> Images.animation(List.of(), new int[0], 0));
        assertFalse(Images.animation(List.of(a), new int[] {10}, 0).isAnimated(), "one frame is a still image");
    }

    @Test
    void aSheetIsCutIntoItsFramesAlongEachRowFromTheTop() {
        // Two rows of three frames of 2 by 2, each frame one colour that says which it is.
        byte[] rgba = new byte[6 * 4 * 4];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 6; x++) {
                int frame = (y / 2) * 3 + x / 2;
                int o = (y * 6 + x) * 4;
                rgba[o] = (byte) (frame * 40);
                rgba[o + 3] = (byte) 0xFF;
            }
        }
        Image sheet = Images.fromSheet(new Image(6, 4, rgba), 3, 2, 5, 90);
        assertTrue(sheet.isAnimated());
        assertEquals(5, sheet.frameCount(), "the last row is not full");
        assertEquals(0, sheet.loopCount());
        assertEquals(450, sheet.durationMillis());
        for (int f = 0; f < 5; f++) {
            Image frame = sheet.frame(f);
            assertEquals(2, frame.width());
            assertEquals(2, frame.height());
            for (int o = 0; o < 16; o += 4) {
                assertEquals(f * 40, frame.pixels()[o] & 0xFF, "frame " + f);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Images.fromSheet(new Image(6, 4, rgba), 4, 2, 90));
    }
}
