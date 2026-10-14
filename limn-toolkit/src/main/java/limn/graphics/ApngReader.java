package limn.graphics;

import limn.io.MalformedContentException;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.CRC32;

/**
 * Reads an animated PNG a frame at a time, each frame handed out whole: laid on what the frames
 * before left, as the file says each is to be disposed of and blended.
 *
 * <p>Not API. The pixels are not decoded here. A frame of an animated PNG is a PNG's picture
 * data under other chunk names, so each is made into a PNG of its own, the file's header and the
 * chunks before its first picture copied in front of it, and handed to the decoder a backend
 * installs, which reads PNG already; what is done here is the walk, the composition and the
 * times.
 *
 * <p>The whole file is walked once when it is opened, without decoding a pixel: that is where
 * the frames, their times and the turns come from, and where a file that is not what it says is
 * refused before anything is allocated for it.
 */
final class ApngReader {

    private static final String FORMAT = "APNG";
    /** The most pixels a canvas may have: sixteen million, four thousand on a side. */
    private static final long MAX_PIXELS = 16L << 20;
    /** The most frames one file may have. */
    private static final int MAX_FRAMES = 1 << 16;
    /** A frame time at or under this was never meant: shown as a tenth of a second, as GIF's are. */
    private static final int SHORTEST_MEANT_MILLIS = 10;
    private static final int TIME_SHOWN_INSTEAD_MILLIS = 100;

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private static final int DISPOSE_NONE = 0;
    private static final int DISPOSE_BACKGROUND = 1;
    private static final int DISPOSE_PREVIOUS = 2;
    private static final int BLEND_SOURCE = 0;

    /** One frame as its control chunk says, and where its picture data is in the file. */
    private record Frame(int x, int y, int width, int height, int millis, int dispose, int blend,
                         List<int[]> data) {
    }

    private final byte[] file;
    private final Function<byte[], Image> decoder;
    private final int width;
    private final int height;
    /** The header's last five bytes, the same for every frame: depth, colour type, methods. */
    private final byte[] layout = new byte[5];
    /** The chunks between the header and the first picture, which every frame's PNG carries. */
    private final byte[] before;
    private final List<Frame> frames = new ArrayList<>();
    private final int loopCount;

    // ---- the place in the file, and what the frames so far left
    private int next;
    private final byte[] canvas;
    private byte[] saved;
    private Frame last;

    /**
     * @param data a whole file
     * @return whether it is a PNG with an animation control chunk before its picture, the mark
     *         of an animated one; a PNG without one is a still picture for the installed decoder
     */
    static boolean isApng(byte[] data) {
        if (data.length < 8 + 25 || !java.util.Arrays.equals(data, 0, 8, SIGNATURE, 0, 8)) {
            return false;
        }
        for (int at = 8; at + 8 <= data.length; ) {
            long length = u32(data, at);
            String type = type(data, at + 4);
            if (type.equals("acTL")) {
                return true;
            }
            if (type.equals("IDAT") || length > data.length) {
                return false;
            }
            at += 12 + (int) length;
        }
        return false;
    }

    /**
     * Opens a file: its size, its frames' times and its turns are known when this returns.
     *
     * @param data    the whole file
     * @param decoder what reads a PNG of one picture into an image of its size
     * @throws MalformedContentException if the file is cut short, is no animated PNG, or is
     *                                   larger than this reads
     */
    ApngReader(byte[] data, Function<byte[], Image> decoder) {
        this.file = data;
        this.decoder = decoder;
        if (!isApng(data) || !type(data, 12).equals("IHDR") || u32(data, 8) != 13) {
            throw new MalformedContentException(FORMAT, "not an animated PNG, or cut short in its header");
        }
        width = (int) Math.min(Integer.MAX_VALUE, u32(data, 16));
        height = (int) Math.min(Integer.MAX_VALUE, u32(data, 20));
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
            throw new MalformedContentException(FORMAT, "a canvas of " + width + " by " + height);
        }
        System.arraycopy(data, 24, layout, 0, 5);

        ByteArrayOutputStream ancillary = new ByteArrayOutputStream();
        int plays = 0;
        boolean seenPicture = false;
        Frame open = null;
        Frame pictureFrame = null;
        int at = 8 + 25;
        while (at + 12 <= data.length) {
            long length = u32(data, at);
            if (length > data.length - at - 12) {
                break; // cut short here: the frames before it are what there is
            }
            int body = at + 8;
            int size = (int) length;
            String type = type(data, at + 4);
            switch (type) {
                case "acTL" -> {
                    if (size != 8) {
                        throw new MalformedContentException(FORMAT, "an animation control of " + size + " bytes");
                    }
                    plays = (int) Math.min(Integer.MAX_VALUE, u32(data, body + 4));
                }
                case "fcTL" -> {
                    if (size != 26) {
                        throw new MalformedContentException(FORMAT, "a frame control of " + size + " bytes");
                    }
                    if (frames.size() >= MAX_FRAMES) {
                        throw new MalformedContentException(FORMAT, "more than " + MAX_FRAMES + " frames");
                    }
                    open = frame(data, body);
                    frames.add(open);
                }
                case "IDAT" -> {
                    // The picture a reader that does not animate shows: the first frame if a frame
                    // control came before it, and otherwise no frame at all.
                    if (!seenPicture && open != null) {
                        pictureFrame = open;
                    }
                    seenPicture = true;
                    if (open != null && open == pictureFrame) {
                        open.data().add(new int[] {body, size});
                    }
                }
                case "fdAT" -> {
                    if (size < 4) {
                        throw new MalformedContentException(FORMAT, "frame data of " + size + " bytes");
                    }
                    if (seenPicture && open != null && open != pictureFrame) {
                        open.data().add(new int[] {body + 4, size - 4}); // after its sequence number
                    }
                }
                case "IEND" -> at = data.length;
                default -> {
                    if (!seenPicture && !type.equals("IHDR")) {
                        ancillary.write(data, at, size + 12); // a palette, transparency, a colour space
                    }
                }
            }
            if (at < data.length) {
                at += 12 + size;
            }
        }
        frames.removeIf(frame -> frame.data().isEmpty());
        if (frames.isEmpty() || !seenPicture) {
            throw new MalformedContentException(FORMAT, "no frame has a picture");
        }
        before = ancillary.toByteArray();
        loopCount = plays;
        canvas = new byte[width * height * 4];
    }

    private Frame frame(byte[] data, int body) {
        long w = u32(data, body + 4);
        long h = u32(data, body + 8);
        long x = u32(data, body + 12);
        long y = u32(data, body + 16);
        if (w == 0 || h == 0 || x + w > width || y + h > height) {
            throw new MalformedContentException(FORMAT, "a frame of " + w + " by " + h + " at " + x + ", " + y
                    + " outside a canvas of " + width + " by " + height);
        }
        int numerator = u16(data, body + 20);
        int denominator = u16(data, body + 22);
        int millis = (int) Math.round(numerator * 1000.0 / (denominator == 0 ? 100 : denominator));
        if (millis <= SHORTEST_MEANT_MILLIS) {
            millis = TIME_SHOWN_INSTEAD_MILLIS;
        }
        int dispose = data[body + 24];
        int blend = data[body + 25];
        if (dispose < 0 || dispose > DISPOSE_PREVIOUS || blend < 0 || blend > 1) {
            throw new MalformedContentException(FORMAT, "a frame disposed " + dispose + " and blended " + blend);
        }
        return new Frame((int) x, (int) y, (int) w, (int) h, millis, dispose, blend, new ArrayList<>());
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** @return each frame's time, in milliseconds, its length the number of frames */
    int[] millis() {
        int[] out = new int[frames.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = frames.get(i).millis();
        }
        return out;
    }

    /** @return how many turns the file asks for, 0 for no end */
    int loopCount() {
        return loopCount;
    }

    /** @return the index of the frame {@link #nextFrame} hands out next */
    int position() {
        return next;
    }

    /** Goes back to before the first frame. */
    void rewind() {
        next = 0;
        last = null;
        saved = null;
        java.util.Arrays.fill(canvas, (byte) 0);
    }

    /**
     * @return the next frame, whole, or null if the file has no more
     * @throws MalformedContentException if the frame's picture cannot be read
     */
    Image nextFrame() {
        if (next >= frames.size()) {
            return null;
        }
        Frame frame = frames.get(next);
        if (last != null) {
            dispose(last);
        }
        byte[] picture = picture(frame);
        if (frame.dispose() == DISPOSE_PREVIOUS && next > 0) {
            saved = region(frame);
        }
        // The first frame's previous is nothing: disposed of, it leaves the canvas clear.
        last = frame.dispose() == DISPOSE_PREVIOUS && next == 0
                ? new Frame(frame.x(), frame.y(), frame.width(), frame.height(), frame.millis(), DISPOSE_BACKGROUND,
                        frame.blend(), frame.data())
                : frame;
        draw(frame, picture);
        next++;
        return new Image(width, height, canvas.clone());
    }

    /** The frame's picture, decoded from a PNG made of it. */
    private byte[] picture(Frame frame) {
        Image decoded;
        try {
            decoded = decoder.apply(png(frame));
        } catch (MalformedContentException damaged) {
            throw damaged;
        } catch (IllegalStateException | UnsupportedOperationException notThere) {
            throw notThere; // no decoder installed: no fault of the file's
        } catch (RuntimeException damaged) {
            throw new MalformedContentException(FORMAT, "frame " + next + " could not be read", damaged);
        }
        if (decoded == null || decoded.width() != frame.width() || decoded.height() != frame.height()) {
            throw new MalformedContentException(FORMAT, "frame " + next + " is not the size its control says");
        }
        return decoded.pixels();
    }

    /** A PNG of one frame: the file's header at the frame's size, the chunks before, the picture. */
    private byte[] png(Frame frame) {
        int pictureBytes = 0;
        for (int[] part : frame.data()) {
            pictureBytes += part[1];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + 25 + before.length + pictureBytes + 12 * 2);
        out.writeBytes(SIGNATURE);
        byte[] header = new byte[13];
        put32(header, 0, frame.width());
        put32(header, 4, frame.height());
        System.arraycopy(layout, 0, header, 8, 5);
        chunk(out, "IHDR", header, 0, header.length);
        out.writeBytes(before);
        byte[] picture = new byte[pictureBytes];
        int at = 0;
        for (int[] part : frame.data()) {
            System.arraycopy(file, part[0], picture, at, part[1]);
            at += part[1];
        }
        chunk(out, "IDAT", picture, 0, picture.length);
        chunk(out, "IEND", new byte[0], 0, 0);
        return out.toByteArray();
    }

    private void dispose(Frame frame) {
        if (frame.dispose() == DISPOSE_BACKGROUND) {
            for (int row = 0; row < frame.height(); row++) {
                int from = ((frame.y() + row) * width + frame.x()) * 4;
                java.util.Arrays.fill(canvas, from, from + frame.width() * 4, (byte) 0);
            }
        } else if (frame.dispose() == DISPOSE_PREVIOUS && saved != null) {
            int stride = frame.width() * 4;
            for (int row = 0; row < frame.height(); row++) {
                System.arraycopy(saved, row * stride, canvas, ((frame.y() + row) * width + frame.x()) * 4, stride);
            }
            saved = null;
        }
    }

    private byte[] region(Frame frame) {
        int stride = frame.width() * 4;
        byte[] out = new byte[stride * frame.height()];
        for (int row = 0; row < frame.height(); row++) {
            System.arraycopy(canvas, ((frame.y() + row) * width + frame.x()) * 4, out, row * stride, stride);
        }
        return out;
    }

    /** Lays a frame's picture on the canvas: in place of what is there, or over it. */
    private void draw(Frame frame, byte[] picture) {
        for (int row = 0; row < frame.height(); row++) {
            int to = ((frame.y() + row) * width + frame.x()) * 4;
            int from = row * frame.width() * 4;
            if (frame.blend() == BLEND_SOURCE) {
                System.arraycopy(picture, from, canvas, to, frame.width() * 4);
                continue;
            }
            for (int x = 0; x < frame.width(); x++, to += 4, from += 4) {
                over(picture, from, to);
            }
        }
    }

    /** Straight alpha, one picture's pixel over the canvas's, as the format defines it. */
    private void over(byte[] picture, int from, int to) {
        int sa = picture[from + 3] & 0xFF;
        if (sa == 255) {
            System.arraycopy(picture, from, canvas, to, 4);
            return;
        }
        if (sa == 0) {
            return;
        }
        int da = canvas[to + 3] & 0xFF;
        if (da == 0) {
            System.arraycopy(picture, from, canvas, to, 4);
            return;
        }
        // Both in 0..255: out alpha is sa + da (1 - sa), and each colour is weighted by its alpha.
        // Truncated, not rounded, as the format's own sample decoder does and as the decoder the
        // fixtures were checked against does too: rounding is a step of one away from both.
        int over = sa * 255;
        int under = da * (255 - sa);
        int alpha255 = over + under; // out alpha times 255
        for (int c = 0; c < 3; c++) {
            canvas[to + c] = (byte) (((picture[from + c] & 0xFF) * over + (canvas[to + c] & 0xFF) * under) / alpha255);
        }
        canvas[to + 3] = (byte) (alpha255 / 255);
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data, int offset, int length) {
        byte[] head = new byte[8];
        put32(head, 0, length);
        for (int i = 0; i < 4; i++) {
            head[4 + i] = (byte) type.charAt(i);
        }
        out.writeBytes(head);
        out.write(data, offset, length);
        CRC32 crc = new CRC32();
        crc.update(head, 4, 4);
        crc.update(data, offset, length);
        byte[] tail = new byte[4];
        put32(tail, 0, (int) crc.getValue());
        out.writeBytes(tail);
    }

    private static String type(byte[] data, int at) {
        if (at + 4 > data.length) {
            return "";
        }
        return new String(data, at, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static long u32(byte[] data, int at) {
        if (at + 4 > data.length) {
            return Long.MAX_VALUE;
        }
        return ((data[at] & 0xFFL) << 24) | ((data[at + 1] & 0xFFL) << 16) | ((data[at + 2] & 0xFFL) << 8)
                | (data[at + 3] & 0xFFL);
    }

    private static int u16(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private static void put32(byte[] target, int at, int value) {
        target[at] = (byte) (value >>> 24);
        target[at + 1] = (byte) (value >>> 16);
        target[at + 2] = (byte) (value >>> 8);
        target[at + 3] = (byte) value;
    }
}
