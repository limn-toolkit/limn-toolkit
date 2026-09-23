/**
 * H.264/HEVC/VP9/VP8 and AAC/Opus/Vorbis out of MP4 and Matroska, through FFmpeg. The native
 * payload is read as resources from the limn-ffmpeg-natives jars.
 */
module limn.video.ffmpeg {
    requires transitive limn.toolkit;
    // The JNI shim's jar. Required so that on the module path it is loaded at all, and loading it
    // loads the platform jars (limn.ffmpeg.natives.<os>.<arch>) on the path with it.
    requires limn.ffmpeg.natives;

    exports limn.video.ffmpeg;
}
