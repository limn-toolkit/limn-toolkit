/**
 * H.264/HEVC/VP9/VP8 and AAC/Opus/Vorbis out of MP4 and Matroska, through FFmpeg. The native
 * payload is found as class-path resources, so the limn-ffmpeg-natives jars belong on the class
 * path.
 */
module limn.video.ffmpeg {
    requires transitive limn.toolkit;

    exports limn.video.ffmpeg;
}
