package limn.demo;

import limn.io.Resources;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The ten-second Big Buck Bunny excerpt this jar carries, as a file a decoder can open.
 *
 * <p>The build copies it out of the repository's {@code media/} into {@code limn/demo/media/}, with
 * its licence beside it, because the shipping FFmpeg payload has no H.264 <em>encoder</em> (every
 * GPL component is kept out), so a program that wants to show real H.264 cannot make a clip and has
 * to carry one.
 *
 * <p>&copy; 2008 Blender Foundation, <a href="https://peach.blender.org/">peach.blender.org</a>,
 * under the Creative Commons Attribution 3.0 Unported licence, whose text ships beside it. The
 * licence asks for the credit wherever the work goes: the demo's video screen names it on screen,
 * and the website's component page carries it under the pictures it publishes.
 *
 * <p>It lives in a class of its own because it has two consumers in two packages -- the demo's
 * video screen and the site gallery's transport entry -- and a resource path written down twice
 * is a resource path that stops matching the build file once.
 */
public final class BundledClip {

    /** The resource the build writes; see {@code limn-demo/build.gradle.kts}. */
    public static final String RESOURCE = "/limn/demo/media/Big_Buck_Bunny_360_10s_1MB.mp4";

    private static Path unpacked;

    private BundledClip() {
    }

    /**
     * The clip as a file: the decoder opens paths, not streams, so the resource is copied out
     * once per run into a directory that is this process's alone.
     *
     * @return the unpacked file, the same one on every call
     * @throws UncheckedIOException if it cannot be unpacked, which is a broken jar rather than
     *                              an ordinary state of the world
     */
    public static synchronized Path path() {
        if (unpacked == null) {
            byte[] clip = Resources.bytes(BundledClip.class, RESOURCE, "bundled clip");
            try {
                Path folder = Files.createTempDirectory("limn-demo-");
                folder.toFile().deleteOnExit();
                Path file = folder.resolve("big-buck-bunny-360.mp4");
                file.toFile().deleteOnExit();
                Files.write(file, clip);
                unpacked = file;
            } catch (IOException error) {
                throw new UncheckedIOException("cannot unpack the demo's bundled clip", error);
            }
        }
        return unpacked;
    }
}
