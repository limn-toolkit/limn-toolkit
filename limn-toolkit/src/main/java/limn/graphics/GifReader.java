package limn.graphics;

import limn.io.MalformedContentException;

/**
 * Reads a GIF a frame at a time, each frame handed out whole: laid on what the frames before
 * left, as the file says each is to be disposed of.
 *
 * <p>Not API. Written here, where the picture decoder a backend installs reads GIF too, for two
 * things that one does not do: it reads a file's frames one by one, so that a long animation is
 * decoded as it plays and not all at once, and it reads how many turns the file asks for.
 *
 * <p>The whole file is walked once when it is opened, without decoding a pixel: that is where
 * the number of frames, each one's time and the turns come from, and where a file that is not
 * what it says is refused before anything is allocated for it.
 */
final class GifReader {

    private static final String FORMAT = "GIF";
    /** The most pixels a canvas may have: sixteen million, four thousand on a side. */
    private static final long MAX_PIXELS = 16L << 20;
    /** The most frames one file may have. */
    private static final int MAX_FRAMES = 1 << 16;
    /** A frame time at or under this was never meant: every viewer has shown it as a tenth of a second. */
    private static final int SHORTEST_MEANT_MILLIS = 10;
    private static final int TIME_SHOWN_INSTEAD_MILLIS = 100;

    private final byte[] data;
    private final int width;
    private final int height;
    private final int[] globalPalette;
    /** Where the first block after the header and the global palette starts. */
    private final int firstBlock;
    private final int[] millis;
    private final int loopCount;

    // ---- the place in the file, and what the frames so far left
    private int at;
    private int next;
    private final byte[] canvas;
    private byte[] saved;
    /** What to do with the frame last drawn before the next goes on: its rectangle and its disposal. */
    private int lastX;
    private int lastY;
    private int lastW;
    private int lastH;
    private int lastDisposal;

    // ---- what a graphic control block said of the picture that follows it
    private int delayMillis;
    private int disposal;
    private int transparent = -1;

    // ---- the dictionary coder's tables, kept between frames
    private final short[] prefix = new short[4096];
    private final byte[] suffix = new byte[4096];
    private final byte[] stack = new byte[4097];

    /**
     * @param data a whole file
     * @return whether it starts as a GIF does
     */
    static boolean isGif(byte[] data) {
        return data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8'
                && (data[4] == '7' || data[4] == '9') && data[5] == 'a';
    }

    /**
     * Opens a file: its size, its frames' times and its turns are known when this returns.
     *
     * @throws MalformedContentException if the file is cut short, is no GIF, or is larger than
     *                                   this reads
     */
    GifReader(byte[] data) {
        this.data = data;
        if (!isGif(data) || data.length < 13) {
            throw new MalformedContentException(FORMAT, "not a GIF, or cut short in its header");
        }
        this.width = u16(6);
        this.height = u16(8);
        if (width == 0 || height == 0 || (long) width * height > MAX_PIXELS) {
            throw new MalformedContentException(FORMAT, "a canvas of " + width + " by " + height
                    + " is none, or more than is read");
        }
        int flags = data[10] & 0xFF;
        int start = 13;
        if ((flags & 0x80) != 0) {
            int colours = 2 << (flags & 7);
            globalPalette = palette(start, colours);
            start += colours * 3;
        } else {
            globalPalette = null;
        }
        this.firstBlock = start;

        // The walk: every block's length believed only as far as the file goes.
        int[] times = new int[16];
        int frames = 0;
        int turns = 1; // a file that says nothing plays once
        int delay = 0;
        int p = start;
        boolean ended = false;
        while (!ended && p < data.length) {
            int block = data[p++] & 0xFF;
            if (frames > 0 && !wholeBlockAt(p, block)) {
                break; // cut short after a picture: the frames before the cut are the file
            }
            switch (block) {
                case 0x3B -> ended = true;
                case 0x21 -> {
                    need(p, 1);
                    int label = data[p++] & 0xFF;
                    if (label == 0xF9) {
                        need(p, 6);
                        delay = u16(p + 2) * 10;
                    } else if (label == 0xFF && p + 16 < data.length && (data[p] & 0xFF) == 11
                            && isNetscape(p + 1) && (data[p + 12] & 0xFF) == 3 && data[p + 13] == 1) {
                        int repeats = u16(p + 14);
                        turns = repeats == 0 ? 0 : repeats + 1;
                    }
                    p = skipSubBlocks(p);
                }
                case 0x2C -> {
                    need(p, 9);
                    int local = data[p + 8] & 0xFF;
                    p += 9;
                    if ((local & 0x80) != 0) {
                        p += (2 << (local & 7)) * 3;
                    }
                    need(p, 1);
                    p = skipSubBlocks(p + 1);
                    if (frames == MAX_FRAMES) {
                        throw new MalformedContentException(FORMAT, "more than " + MAX_FRAMES + " frames");
                    }
                    if (frames == times.length) {
                        times = java.util.Arrays.copyOf(times, frames * 2);
                    }
                    times[frames++] = delay <= SHORTEST_MEANT_MILLIS ? TIME_SHOWN_INSTEAD_MILLIS : delay;
                    delay = 0;
                }
                default -> throw new MalformedContentException(FORMAT, "a block of an unknown kind, 0x"
                        + Integer.toHexString(block) + ", at byte " + (p - 1));
            }
        }
        if (frames == 0) {
            throw new MalformedContentException(FORMAT, "no picture in the file");
        }
        this.millis = java.util.Arrays.copyOf(times, frames);
        this.loopCount = turns;
        this.canvas = new byte[width * height * 4];
        this.at = firstBlock;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int frameCount() {
        return millis.length;
    }

    /** @return how long each frame is shown, a time too short to be meant already lengthened */
    int[] millis() {
        return millis;
    }

    /** @return how many turns the file asks for; 0 for no end, 1 when it says nothing */
    int loopCount() {
        return loopCount;
    }

    /** @return the index of the frame {@link #nextFrame} hands out next */
    int position() {
        return next;
    }

    /** Goes back to before the first frame. */
    void rewind() {
        at = firstBlock;
        next = 0;
        java.util.Arrays.fill(canvas, (byte) 0);
        saved = null;
        lastDisposal = 0;
        lastW = 0;
        lastH = 0;
        disposal = 0;
        delayMillis = 0;
        transparent = -1;
    }

    /**
     * Decodes the next frame onto what the ones before left.
     *
     * @return the frame, whole and of the canvas's size, or null after the last
     * @throws MalformedContentException if the frame's data is not what the format says
     */
    Image nextFrame() {
        if (next >= millis.length) {
            return null; // the frames the walk found whole, and no more
        }
        while (at < data.length) {
            int block = data[at++] & 0xFF;
            if (block == 0x3B) {
                at = data.length;
                return null;
            }
            if (block == 0x21) {
                need(at, 1);
                int label = data[at++] & 0xFF;
                if (label == 0xF9) {
                    need(at, 6);
                    int packed = data[at + 1] & 0xFF;
                    disposal = (packed >> 2) & 7;
                    delayMillis = u16(at + 2) * 10;
                    transparent = (packed & 1) != 0 ? data[at + 4] & 0xFF : -1;
                }
                at = skipSubBlocks(at);
                continue;
            }
            if (block != 0x2C) {
                throw new MalformedContentException(FORMAT, "a block of an unknown kind at byte " + (at - 1));
            }
            return picture();
        }
        return null;
    }

    private Image picture() {
        need(at, 9);
        int x = u16(at);
        int y = u16(at + 2);
        int w = u16(at + 4);
        int h = u16(at + 6);
        int local = data[at + 8] & 0xFF;
        at += 9;
        int[] colours = globalPalette;
        if ((local & 0x80) != 0) {
            int count = 2 << (local & 7);
            colours = palette(at, count);
            at += count * 3;
        }
        if (colours == null) {
            throw new MalformedContentException(FORMAT, "a picture with no palette, its own or the file's");
        }
        boolean interlaced = (local & 0x40) != 0;

        // What the frame before asked to be done with itself, before this one goes on.
        if (lastDisposal == 2) {
            clear(lastX, lastY, lastW, lastH);
        } else if (lastDisposal == 3 && saved != null) {
            System.arraycopy(saved, 0, canvas, 0, canvas.length);
        }
        if (disposal == 3) {
            if (saved == null) {
                saved = new byte[canvas.length];
            }
            System.arraycopy(canvas, 0, saved, 0, canvas.length);
        }

        need(at, 1);
        int minimum = data[at++] & 0xFF;
        if (minimum < 2 || minimum > 8) {
            throw new MalformedContentException(FORMAT, "a code size of " + minimum + ", where 2 to 8 are");
        }
        unpack(minimum, x, y, w, h, interlaced, colours);
        at = skipSubBlocks(at);

        lastX = x;
        lastY = y;
        lastW = w;
        lastH = h;
        lastDisposal = disposal;
        disposal = 0;
        delayMillis = 0;
        transparent = -1;
        next++;
        return new Image(width, height, canvas.clone());
    }

    private void clear(int x, int y, int w, int h) {
        int right = Math.min(width, x + w);
        int bottom = Math.min(height, y + h);
        for (int row = Math.min(y, height); row < bottom; row++) {
            int from = (row * width + Math.min(x, width)) * 4;
            int to = (row * width + right) * 4;
            if (from < to) {
                java.util.Arrays.fill(canvas, from, to, (byte) 0);
            }
        }
    }

    /**
     * The dictionary coder, read straight onto the canvas: codes of a width that grows from
     * {@code minimum + 1} bits to twelve, each a string of pixels built from the ones before.
     * Pixels past the picture's rectangle are dropped and ones that never come are left as they
     * were, which is what a file cut short gets instead of a refusal of its last frame.
     */
    private void unpack(int minimum, int x, int y, int w, int h, boolean interlaced, int[] colours) {
        int clear = 1 << minimum;
        int end = clear + 1;
        int available = clear + 2;
        int size = minimum + 1;
        int mask = (1 << size) - 1;
        int old = -1;
        int first = 0;
        for (int code = 0; code < clear; code++) {
            prefix[code] = 0;
            suffix[code] = (byte) code;
        }
        long pixels = (long) w * h;
        long done = 0;
        int bits = 0;
        int held = 0;
        int top = 0;
        int p = at;
        int blockLeft = 0;
        int pass = 0;
        int row = 0;
        int column = 0;
        int step = interlaced ? 8 : 1;

        while (done < pixels) {
            if (top == 0) {
                while (bits < size) {
                    if (blockLeft == 0) {
                        if (p >= data.length) {
                            return;
                        }
                        blockLeft = data[p++] & 0xFF;
                        if (blockLeft == 0) {
                            return;
                        }
                    }
                    if (p >= data.length) {
                        return;
                    }
                    held |= (data[p++] & 0xFF) << bits;
                    bits += 8;
                    blockLeft--;
                }
                int code = held & mask;
                held >>= size;
                bits -= size;
                if (code == end) {
                    return;
                }
                if (code == clear) {
                    size = minimum + 1;
                    mask = (1 << size) - 1;
                    available = clear + 2;
                    old = -1;
                    continue;
                }
                if (old == -1) {
                    if (code >= clear) {
                        throw new MalformedContentException(FORMAT, "a first code that names no colour");
                    }
                    stack[top++] = suffix[code];
                    old = code;
                    first = code;
                    continue;
                }
                int in = code;
                if (code > available) {
                    throw new MalformedContentException(FORMAT, "a code past the end of the dictionary");
                }
                if (code == available) {
                    stack[top++] = (byte) first;
                    code = old;
                }
                while (code >= clear) {
                    stack[top++] = suffix[code];
                    code = prefix[code];
                    if (top > 4096) {
                        throw new MalformedContentException(FORMAT, "a dictionary entry that never ends");
                    }
                }
                first = suffix[code] & 0xFF;
                stack[top++] = (byte) first;
                if (available < 4096) {
                    prefix[available] = (short) old;
                    suffix[available] = (byte) first;
                    available++;
                    if ((available & mask) == 0 && available < 4096) {
                        size++;
                        mask = (1 << size) - 1;
                    }
                }
                old = in;
            }
            int index = stack[--top] & 0xFF;
            done++;
            int px = x + column;
            int py = y + row;
            if (index != transparent && px < width && py < height) {
                int colour = index < colours.length ? colours[index] : 0xFF000000;
                int o = (py * width + px) * 4;
                canvas[o] = (byte) (colour >> 16);
                canvas[o + 1] = (byte) (colour >> 8);
                canvas[o + 2] = (byte) colour;
                canvas[o + 3] = (byte) 0xFF;
            }
            if (++column == w) {
                column = 0;
                row += step;
                // The four passes of an interlaced picture: every eighth row, then the ones
                // between, twice over, each pass starting half a step down from the last.
                while (interlaced && row >= h && pass < 3) {
                    pass++;
                    step = pass == 1 ? 8 : pass == 2 ? 4 : 2;
                    row = pass == 1 ? 4 : pass == 2 ? 2 : 1;
                }
            }
        }
    }

    /** Whether the block of this kind whose body starts at {@code p} is all in the file, as far as its lengths say. */
    private boolean wholeBlockAt(int p, int block) {
        if (block == 0x3B) {
            return true;
        }
        if (block == 0x21) {
            return p < data.length && endsInFile(p + 1);
        }
        if (block == 0x2C) {
            if (p + 9 > data.length) {
                return false;
            }
            int local = data[p + 8] & 0xFF;
            int q = p + 9 + ((local & 0x80) != 0 ? (2 << (local & 7)) * 3 : 0);
            return q < data.length && endsInFile(q + 1);
        }
        return true; // an unknown kind: refused where it is met
    }

    /** Whether the run of length-prefixed blocks from {@code from} reaches its block of no length inside the file. */
    private boolean endsInFile(int from) {
        int p = from;
        while (p < data.length) {
            int length = data[p] & 0xFF;
            if (length == 0) {
                return true;
            }
            p += 1 + length;
        }
        return false;
    }

    private int[] palette(int from, int colours) {
        need(from, colours * 3);
        int[] out = new int[colours];
        for (int i = 0; i < colours; i++) {
            int o = from + i * 3;
            out[i] = 0xFF000000 | (data[o] & 0xFF) << 16 | (data[o + 1] & 0xFF) << 8 | (data[o + 2] & 0xFF);
        }
        return out;
    }

    private boolean isNetscape(int from) {
        String name = new String(data, from, 11, java.nio.charset.StandardCharsets.ISO_8859_1);
        return name.equals("NETSCAPE2.0") || name.equals("ANIMEXTS1.0");
    }

    /** @return the byte after a run of length-prefixed blocks, which a block of no length ends */
    private int skipSubBlocks(int from) {
        int p = from;
        while (true) {
            if (p >= data.length) {
                return data.length; // cut short: what was read is what there is
            }
            int length = data[p] & 0xFF;
            p += 1 + length;
            if (length == 0) {
                return Math.min(p, data.length);
            }
        }
    }

    private void need(int from, int length) {
        if (from < 0 || length < 0 || from + (long) length > data.length) {
            throw new MalformedContentException(FORMAT, "cut short at byte " + data.length);
        }
    }

    private int u16(int from) {
        need(from, 2);
        return (data[from] & 0xFF) | (data[from + 1] & 0xFF) << 8;
    }
}
