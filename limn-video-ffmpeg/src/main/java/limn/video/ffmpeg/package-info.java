/**
 * H.264 and AAC out of MP4, through a trimmed FFmpeg behind a hand-written JNI shim.
 *
 * <p>This is the only package in Limn with a native payload, and it is a module of its own so that
 * it stays the only one. {@code limn-toolkit} depends on nothing, {@code limn-video} carries no
 * native and no third-party dependency, and an application that never plays an MP4 never puts a
 * codec on its classpath.
 *
 * <p><b>The library is not committed and Gradle does not build it.</b> It is produced by
 * {@code scripts/build-ffmpeg.sh} and is absent on every machine that has not run that script,
 * which is the normal case rather than a broken one. Everything here is written so that absence is
 * ordinary: {@link limn.video.ffmpeg.FfmpegVideoDecoder#supports} answers {@code false},
 * {@link limn.video.ffmpeg.FfmpegLibrary#isAvailable()} answers {@code false} and never throws,
 * and any decoder installed behind this one is reached exactly as if this one were not there.
 *
 * <p>Two entry points, for the two shapes a caller wants:
 *
 * <ul>
 *   <li>{@link limn.video.ffmpeg.FfmpegVideoDecoder} is the SPI implementation: install it and
 *       {@code Videos.open} returns pictures. Video only, because that is what the facade's
 *       signature carries.</li>
 *   <li>{@link limn.video.ffmpeg.FfmpegMedia} is the container; it opens a file once and hands
 *       out both tracks, as the two types the toolkit already publishes, for an application that
 *       wants the soundtrack as well.</li>
 * </ul>
 *
 * <p>The licence is LGPL-2.1-or-later and the FFmpeg libraries are linked dynamically, which is
 * what keeps it so. Nothing here may be built with {@code --enable-gpl}, and a test reads the
 * linked library's own configure line to make sure nothing has been.
 */
package limn.video.ffmpeg;
