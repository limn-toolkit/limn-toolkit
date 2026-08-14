/**
 * Backend-neutral video: a pooled {@link limn.video.VideoFrame} of planar YCbCr samples described
 * by a {@link limn.video.PixelFormat} layout and a {@link limn.video.VideoColor} interpretation,
 * produced by a {@link limn.video.VideoStreamSource} that a {@link limn.video.VideoDecoder} opened
 * through the {@link limn.video.Videos} facade, timed by {@link limn.video.VideoClock} and shown by
 * uploading it to a {@link limn.video.VideoSurface} that the {@link limn.video.VideoSurfaces}
 * facade created, or, where no device conversion exists, turned into RGBA by
 * {@link limn.video.YuvConverter}.
 *
 * <p>Mirrors the audio package: the toolkit stays free of any media library and holds only the
 * layout, the colour arithmetic and the timing policy; a backend supplies the decoders. Frames are
 * borrowed and returned rather than allocated, because a picture is millions of bytes arriving tens
 * of times a second.
 */
package limn.video;
