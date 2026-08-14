package limn.video.ffmpeg;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoFrame;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two claims about this module that a comment cannot keep true: what it is licensed as, and
 * what it is able to open.
 *
 * <p>Both are read out of the linked library itself rather than out of the build script, so
 * neither can be satisfied by a script that says the right thing while producing something else.
 * If somebody adds {@code --enable-gpl} to make a codec work (which is the single most likely
 * wrong edit in this whole module, because it is what the internet tells you to do), the build
 * goes red here rather than shipping a toolkit whose licence quietly changed.
 */
class LicenceTest {

    @Test
    void theLinkedLibraryIsLgplAndNotGpl() {
        FfmpegTests.requireLibrary();
        String[] identity = FfmpegMedia.identity().split("\n");
        String licence = identity[0];
        String configuration = identity[2].toLowerCase(Locale.ROOT);

        assertTrue(licence.startsWith("LGPL"),
                "FFmpeg reports its own licence, and it must still be LGPL: " + licence);
        assertFalse(configuration.contains("--enable-gpl"),
                "--enable-gpl changes FFmpeg's licence to GPL v2+, and with it this module's and "
                        + "everything that ships it: " + configuration);
        assertFalse(configuration.contains("--enable-nonfree"),
                "--enable-nonfree produces a library that may not be redistributed at all");
        assertFalse(configuration.contains("libx264"), "x264 is GPL");
        assertFalse(configuration.contains("libx265"), "x265 is GPL");
    }

    @Test
    void theLibraryCanOpenFilesAndNothingElse() {
        FfmpegTests.requireLibrary();
        String configuration = FfmpegMedia.identity().split("\n")[2].toLowerCase(Locale.ROOT);

        // VideoDecoder.openStream takes a Path and promises a file. libavformat builds with the
        // network protocols on by default, and a build that would also open http:// behind that
        // signature is an SPI lying about itself, so the protocols are restricted at configure
        // time and the restriction is asserted here rather than remembered.
        assertTrue(configuration.contains("--disable-protocols"), configuration);
        assertTrue(configuration.contains("--enable-protocol=file"), configuration);
        assertTrue(configuration.contains("--disable-network"), configuration);
        for (String protocol : new String[] {"http", "https", "rtmp", "rtsp", "tcp", "udp",
                "concat", "ftp", "sftp"}) {
            assertFalse(configuration.contains("--enable-protocol=" + protocol),
                    "a Path-taking SPI must not be able to open " + protocol + "://");
        }
    }

    /**
     * The shim reports enums as their ordinals, so a reordered enum would silently change what a
     * decoded picture claims to be: I420 read as NV12, or BT.709 as BT.601. Neither shows up as
     * an error; both show up as a picture that looks wrong in a way nobody can place.
     */
    @Test
    void theOrdinalsTheShimReportsAreTheOnesTheToolkitDeclares() {
        assertEquals(PixelFormat.I420.ordinal(), FfmpegNative.FORMAT_I420);
        assertEquals(PixelFormat.NV12.ordinal(), FfmpegNative.FORMAT_NV12);
        assertEquals(PixelFormat.I444.ordinal(), FfmpegNative.FORMAT_I444);
        assertEquals(PixelFormat.I420_10LE.ordinal(), FfmpegNative.FORMAT_I420_10LE);
        assertEquals(PixelFormat.I444_10LE.ordinal(), FfmpegNative.FORMAT_I444_10LE);
        assertEquals(PixelFormat.P010.ordinal(), FfmpegNative.FORMAT_P010);
        assertEquals(6, PixelFormat.values().length,
                "a new PixelFormat needs a mapping in limn_ffmpeg.c's mapPixelFormat, and its "
                        + "component width in componentBytes beside it");

        assertEquals(VideoColor.Matrix.BT601.ordinal(), FfmpegNative.MATRIX_BT601);
        assertEquals(VideoColor.Matrix.BT709.ordinal(), FfmpegNative.MATRIX_BT709);
        assertEquals(VideoColor.Matrix.BT2020.ordinal(), FfmpegNative.MATRIX_BT2020);

        assertEquals(VideoColor.Range.LIMITED.ordinal(), FfmpegNative.RANGE_LIMITED);
        assertEquals(VideoColor.Range.FULL.ordinal(), FfmpegNative.RANGE_FULL);

        assertEquals(VideoColor.Transfer.SDR.ordinal(), FfmpegNative.TRANSFER_SDR);
        assertEquals(VideoColor.Transfer.PQ.ordinal(), FfmpegNative.TRANSFER_PQ);
        assertEquals(VideoColor.Transfer.HLG.ordinal(), FfmpegNative.TRANSFER_HLG);
        assertEquals(3, VideoColor.Transfer.values().length,
                "a new Transfer needs a mapping in limn_ffmpeg.c's describe()");

        // The shim writes INT64_MIN for a picture with no timestamp. AV_NOPTS_VALUE happens to be
        // the same number, which is a coincidence the shim does not rely on (it tests for
        // AV_NOPTS_VALUE and writes this constant), but the constant itself must not move.
        assertEquals(Long.MIN_VALUE, VideoFrame.PTS_UNKNOWN);
    }
}
