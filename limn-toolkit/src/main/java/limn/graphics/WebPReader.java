package limn.graphics;

import limn.io.MalformedContentException;
import org.glavo.webp.WebPFrame;
import org.glavo.webp.WebPImageReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads a WebP, still or animated, through the decoder the toolkit depends on, a frame at a time.
 *
 * <p>Not API. Two things are done here and not left to that decoder. The container is walked
 * first, every chunk held inside the file and the canvas and the frames under a ceiling, because
 * the decoder allocates what a file's sizes say and a damaged file's say anything: measured, one
 * damaged file in twenty-five ended in an out-of-memory error without this walk and one in ten
 * thousand with it. And whatever the decoder throws leaves as the one exception a file is
 * refused with.
 *
 * <p>No class of the decoder is loaded until a WebP is read: nothing but this class names one.
 */
final class WebPReader implements AutoCloseable {

    private static final String FORMAT = "WebP";
    /** The most pixels a canvas may have: sixteen million, as for the other pictures read here. */
    private static final long MAX_PIXELS = 16L << 20;
    /** The most frames one file may have. */
    private static final int MAX_FRAMES = 1 << 16;
    /** A frame time at or under this was never meant; see the GIF reader. */
    private static final int SHORTEST_MEANT_MILLIS = 10;
    private static final int TIME_SHOWN_INSTEAD_MILLIS = 100;

    private final byte[] data;
    private WebPImageReader reader;
    /** How long each frame is shown, read off the container; one of 0 for a still picture. */
    private final int[] millis;
    /** The index of the frame {@link #nextFrame} hands out next. */
    private int position;
    private final int width;
    private final int height;
    private final int loopCount;

    /**
     * @param data a whole file
     * @return whether it starts as a WebP does
     */
    static boolean isWebP(byte[] data) {
        return data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    /**
     * Opens a file: its size, its frame count and its turns are known when this returns.
     *
     * @throws MalformedContentException if the file is no WebP, is cut short in its container, or
     *                                   is larger than this reads
     */
    WebPReader(byte[] data) {
        this.data = data;
        this.millis = walk(data);
        int counted = millis.length;
        int frames;
        try {
            this.reader = WebPImageReader.open(new ByteArrayInputStream(data));
            this.width = reader.getWidth();
            this.height = reader.getHeight();
            frames = Math.max(1, reader.isAnimated() ? reader.getFrameCount() : 1);
            this.loopCount = reader.isAnimated() ? Math.max(0, reader.getLoopCount()) : 0;
        } catch (IOException | RuntimeException refused) {
            throw new MalformedContentException(FORMAT, String.valueOf(refused.getMessage()), refused);
        }
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS || frames > Math.max(1, counted)) {
            close();
            throw new MalformedContentException(FORMAT, "a canvas of " + width + " by " + height + " and "
                    + frames + " frames is not what the container holds, or more than is read");
        }
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** @return how many turns the file asks for; 0 for no end */
    int loopCount() {
        return loopCount;
    }

    /**
     * Decodes the next frame.
     *
     * @return the frame, whole and of the canvas's size, or null after the last
     * @throws MalformedContentException if the frame's data is not what the format says
     */
    Image nextFrame() {
        WebPFrame frame;
        try {
            frame = reader.readNextFrame();
        } catch (IOException | RuntimeException refused) {
            throw new MalformedContentException(FORMAT, String.valueOf(refused.getMessage()), refused);
        }
        if (frame == null) {
            return null;
        }
        if (frame.getWidth() != width || frame.getHeight() != height) {
            throw new MalformedContentException(FORMAT, "a frame of " + frame.getWidth() + " by "
                    + frame.getHeight() + " on a canvas of " + width + " by " + height);
        }
        position++;
        int[] argb = frame.getArgbArray();
        int stride = frame.getScanlineStride();
        byte[] rgba = new byte[width * height * 4];
        for (int y = 0, o = 0; y < height; y++) {
            for (int x = 0, i = y * stride; x < width; x++, i++, o += 4) {
                int pixel = argb[i];
                rgba[o] = (byte) (pixel >> 16);
                rgba[o + 1] = (byte) (pixel >> 8);
                rgba[o + 2] = (byte) pixel;
                rgba[o + 3] = (byte) (pixel >>> 24);
            }
        }
        return new Image(width, height, rgba);
    }

    /** @return how long each frame is shown, a time too short to be meant already lengthened */
    int[] millis() {
        return millis;
    }

    /** @return the index of the frame {@link #nextFrame} hands out next */
    int position() {
        return position;
    }

    /** Goes back to before the first frame: the decoder reads forward only, so it is opened again. */
    void rewind() {
        close();
        try {
            reader = WebPImageReader.open(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException refused) {
            throw new MalformedContentException(FORMAT, String.valueOf(refused.getMessage()), refused);
        }
        position = 0;
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (IOException | RuntimeException ignored) {
            // Read from memory: nothing is held that closing has to give back.
        }
    }

    /**
     * Walks the container before the decoder sees it.
     *
     * @return how long each frame the container holds is shown, one time of 0 for a still picture
     * @throws MalformedContentException if a chunk runs past what holds it, there is no picture,
     *                                   or a size is over the ceiling
     */
    private static int[] walk(byte[] b) {
        if (!isWebP(b) || b.length < 20) {
            throw new MalformedContentException(FORMAT, "not a WebP, or cut short in its header");
        }
        long canvas = -1;
        int frames = 0;
        int[] times = new int[8];
        int at = 12;
        while (at + 8 <= b.length) {
            String id = new String(b, at, 4, StandardCharsets.ISO_8859_1);
            long size = le32(b, at + 4);
            int body = at + 8;
            if (size > b.length - body) {
                throw new MalformedContentException(FORMAT, "the chunk " + printable(id) + " at byte " + at
                        + " runs past the end of the file");
            }
            switch (id) {
                case "VP8X" -> {
                    if (size >= 10) {
                        canvas = (long) (le24(b, body + 4) + 1) * (le24(b, body + 7) + 1);
                    }
                }
                case "ANMF" -> {
                    if (size < 16) {
                        throw new MalformedContentException(FORMAT, "a frame with no header, at byte " + at);
                    }
                    if (++frames > MAX_FRAMES) {
                        throw new MalformedContentException(FORMAT, "more than " + MAX_FRAMES + " frames");
                    }
                    if (frames > times.length) {
                        times = java.util.Arrays.copyOf(times, times.length * 2);
                    }
                    int shownFor = le24(b, body + 12);
                    times[frames - 1] = shownFor <= SHORTEST_MEANT_MILLIS ? TIME_SHOWN_INSTEAD_MILLIS : shownFor;
                    long frame = (long) (le24(b, body + 6) + 1) * (le24(b, body + 9) + 1);
                    if (frame > MAX_PIXELS) {
                        throw new MalformedContentException(FORMAT, "a frame larger than is read, at byte " + at);
                    }
                    // What the frame holds is chunks too, each inside the frame.
                    int inner = body + 16;
                    long end = body + size;
                    while (inner + 8 <= end) {
                        long innerSize = le32(b, inner + 4);
                        if (innerSize > end - inner - 8) {
                            throw new MalformedContentException(FORMAT, "a chunk at byte " + inner
                                    + " runs past the frame that holds it");
                        }
                        inner += 8 + (int) innerSize + (int) (innerSize & 1);
                    }
                }
                case "VP8 " -> {
                    if (canvas < 0 && size >= 10) {
                        canvas = (long) ((b[body + 6] & 0xFF) | (b[body + 7] & 0x3F) << 8)
                                * ((b[body + 8] & 0xFF) | (b[body + 9] & 0x3F) << 8);
                    }
                }
                case "VP8L" -> {
                    if (canvas < 0 && size >= 5) {
                        long bits = le32(b, body + 1);
                        canvas = ((bits & 0x3FFF) + 1) * (((bits >> 14) & 0x3FFF) + 1);
                    }
                }
                default -> {
                    // Colour profiles, metadata, and whatever a later version of the format adds.
                }
            }
            at = body + (int) size + (int) (size & 1);
        }
        if (canvas <= 0) {
            throw new MalformedContentException(FORMAT, "no picture in the file");
        }
        if (canvas > MAX_PIXELS) {
            throw new MalformedContentException(FORMAT, "a canvas larger than is read");
        }
        return java.util.Arrays.copyOf(times, Math.max(1, frames));
    }

    private static String printable(String id) {
        StringBuilder out = new StringBuilder(4);
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            out.append(c >= ' ' && c < 127 ? c : '?');
        }
        return out.toString();
    }

    private static long le32(byte[] b, int at) {
        return (b[at] & 0xFFL) | (b[at + 1] & 0xFFL) << 8 | (b[at + 2] & 0xFFL) << 16 | (b[at + 3] & 0xFFL) << 24;
    }

    private static int le24(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16;
    }
}
