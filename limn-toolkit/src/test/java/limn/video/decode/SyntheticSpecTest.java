package limn.video.decode;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The text form a synthetic stream is opened under, and everything it refuses to guess at. */
class SyntheticSpecTest {

    @Test
    void theTextFormRoundTripsExactly() {
        SyntheticSpec[] specs = {
                SyntheticSpec.of(320, 180),
                SyntheticSpec.of(1, 1).withPattern(SyntheticPattern.COUNTER)
                        .withFormat(PixelFormat.NV12).withColor(VideoColor.unspecified())
                        .withRate(30000, 1001).withFrameCount(7).withSlots(1),
                SyntheticSpec.of(1920, 1080).withPattern(SyntheticPattern.GRADIENT)
                        .withFormat(PixelFormat.I444).withColor(VideoColor.BT601_FULL),
        };
        for (SyntheticSpec spec : specs) {
            assertEquals(spec, SyntheticSpec.parse(spec.toString()), "through the text form");
            assertEquals(spec, SyntheticSpec.parse(spec.fileName()), "through the file name");
            assertTrue(spec.fileName().endsWith(SyntheticSpec.EXTENSION));
            // The text IS a file name, so nothing in it may be a directory separator. A slash here
            // resolves perfectly well and names somewhere that does not exist, which is the kind of
            // bug that is found by a user rather than by a build.
            assertEquals(1, spec.path().getNameCount(),
                    "the whole spec is one path element: " + spec.fileName());
        }
    }

    @Test
    void anUnsignalledColourSurvivesAsUnsignalled() {
        SyntheticSpec spec = SyntheticSpec.of(8, 8).withColor(VideoColor.unspecified());
        assertTrue(spec.toString().contains("color=unspecified"));
        assertSame(VideoColor.unspecified(), SyntheticSpec.parse(spec.toString()).color(),
                "not folded into the BT.709 limited it decodes as");
    }

    @Test
    void omittedKeysTakeTheDefaultAndAMisspeltOneIsRefused() {
        SyntheticSpec spec = SyntheticSpec.parse("size=64x32,pattern=counter");
        assertEquals(64, spec.width());
        assertEquals(32, spec.height());
        assertEquals(SyntheticPattern.COUNTER, spec.pattern());
        assertEquals(PixelFormat.I420, spec.format());

        // Skipping it silently would leave a stream running at a size nobody asked for.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SyntheticSpec.parse("sise=64x32"));
        assertTrue(error.getMessage().contains("sise"), error.getMessage());
    }

    @Test
    void badValuesSayWhichKeyWasWrong() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SyntheticSpec.parse("size=64")).getMessage().contains("size"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SyntheticSpec.parse("rate=30")).getMessage().contains("rate"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SyntheticSpec.parse("rate=30/1")).getMessage().contains("rate"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SyntheticSpec.parse("format=YV12")).getMessage().contains("YV12"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SyntheticSpec.parse("color=BT709_STUDIO")).getMessage().contains("BT709_STUDIO"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SyntheticSpec.parse("pattern=zebra")).getMessage().contains("zebra"));
        assertThrows(IllegalArgumentException.class, () -> SyntheticSpec.parse("size=0x8"));
        assertThrows(IllegalArgumentException.class, () -> SyntheticSpec.parse("slots=0"));
        assertThrows(IllegalArgumentException.class, () -> SyntheticSpec.parse("frames=-1"));
        assertThrows(IllegalArgumentException.class, () -> SyntheticSpec.parse("rate=0/1"));
        assertThrows(IllegalArgumentException.class, () -> SyntheticSpec.parse("nonsense"));
    }

    @Test
    void presentationTimesComeFromTheRationalRate() {
        SyntheticSpec spec = SyntheticSpec.of(8, 8).withRate(30000, 1001);
        assertEquals(0L, spec.ptsMicrosOf(0));
        // A hundred thousand pictures in, an interval accumulated as 33333 microseconds would be
        // more than a hundred milliseconds adrift; the rational form is not.
        assertEquals(3_336_666_666L, spec.ptsMicrosOf(100_000));
        assertThrows(IllegalArgumentException.class, () -> spec.ptsMicrosOf(-1));
    }
}
