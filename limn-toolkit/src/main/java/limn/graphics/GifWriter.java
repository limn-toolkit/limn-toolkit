package limn.graphics;

import java.io.ByteArrayOutputStream;

/**
 * Writes an image as a GIF: every frame of an animated one with its time and the turns it asks
 * for, or the one picture of a still one.
 *
 * <p>Not API; {@link Images#encode} reaches it. A GIF holds 256 colours, one of which here
 * stands for a pixel that is not there, so a picture of more is written in the 255 that stand
 * for it best, chosen once for the whole animation so that a colour does not shift from frame to
 * frame, and with no dithering: a pattern that changes between frames flickers. Transparency is
 * all or nothing, as the format has it: a pixel less than half there is written as not there.
 */
final class GifWriter {

    private GifWriter() {
    }

    static byte[] encode(Image image) {
        int w = image.width();
        int h = image.height();
        if (w > 0xFFFF || h > 0xFFFF) {
            throw new IllegalArgumentException("a GIF is at most 65,535 on a side, and this is " + w + " by " + h);
        }
        int frames = image.frameCount();
        Palette palette = Palette.of(image);
        int bits = 1;
        while ((1 << bits) < palette.size()) {
            bits++;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024 + w * h * frames / 4);
        ascii(out, "GIF89a");
        u16(out, w);
        u16(out, h);
        out.write(0x80 | (bits - 1) << 4 | (bits - 1)); // a palette of the file's, of this many colours
        out.write(0);                                   // the background's index: nothing draws it
        out.write(0);                                   // square pixels
        for (int i = 0; i < (1 << bits); i++) {
            int colour = i < palette.size() ? palette.colour(i) : 0;
            out.write(colour >> 16);
            out.write(colour >> 8);
            out.write(colour);
        }
        if (image.isAnimated() && image.loopCount() != 1) {
            // The extension that makes an animation turn more than once: how many times again.
            out.write(0x21);
            out.write(0xFF);
            out.write(11);
            ascii(out, "NETSCAPE2.0");
            out.write(3);
            out.write(1);
            u16(out, image.loopCount() == 0 ? 0 : Math.min(0xFFFF, image.loopCount() - 1));
            out.write(0);
        }
        byte[] indices = new byte[w * h];
        for (int f = 0; f < frames; f++) {
            Image frame = image.frame(f);
            boolean holes = palette.index(frame.pixels(), indices);
            out.write(0x21);
            out.write(0xF9);
            out.write(4);
            // A frame is the whole picture, so the one before is cleared from under it wherever
            // either has a hole; with none, leaving it is the same and smaller to say.
            out.write((palette.hasTransparent() ? 2 : 1) << 2 | (holes ? 1 : 0));
            u16(out, Math.min(0xFFFF, Math.round(image.frameMillis(f) / 10f)));
            out.write(holes ? palette.transparentIndex() : 0);
            out.write(0);
            out.write(0x2C);
            u16(out, 0);
            u16(out, 0);
            u16(out, w);
            u16(out, h);
            out.write(0); // the file's palette, not interlaced
            pack(out, indices, Math.max(2, bits));
        }
        out.write(0x3B);
        return out.toByteArray();
    }

    /** The dictionary coder: strings of pixels already seen become one code each, of a width that grows to twelve bits. */
    private static void pack(ByteArrayOutputStream out, byte[] indices, int minimum) {
        out.write(minimum);
        int clear = 1 << minimum;
        int end = clear + 1;
        int[] keys = new int[8192];
        int[] codes = new int[8192];
        Bits bits = new Bits(out);
        int size = minimum + 1;
        int available = clear + 2;
        java.util.Arrays.fill(keys, -1);
        bits.put(clear, size);
        int string = indices[0] & 0xFF;
        for (int i = 1; i < indices.length; i++) {
            int pixel = indices[i] & 0xFF;
            int key = string << 8 | pixel;
            int slot = (key * 0x9E3779B1) >>> 19;
            while (keys[slot] != -1 && keys[slot] != key) {
                slot = (slot + 1) & 8191;
            }
            if (keys[slot] == key) {
                string = codes[slot];
                continue;
            }
            bits.put(string, size);
            if (available < 4096) {
                keys[slot] = key;
                codes[slot] = available;
                if (available == (1 << size)) {
                    size++;
                }
                available++;
            } else {
                // The dictionary is full: begin again, as every reader expects.
                bits.put(clear, size);
                java.util.Arrays.fill(keys, -1);
                size = minimum + 1;
                available = clear + 2;
            }
            string = pixel;
        }
        bits.put(string, size);
        bits.put(end, size);
        bits.finish();
        out.write(0);
    }

    /** Bits packed low first into blocks of at most 255 bytes, each with its length before it. */
    private static final class Bits {
        private final ByteArrayOutputStream out;
        private final byte[] block = new byte[255];
        private int filled;
        private int held;
        private int count;

        Bits(ByteArrayOutputStream out) {
            this.out = out;
        }

        void put(int code, int size) {
            held |= code << count;
            count += size;
            while (count >= 8) {
                add(held & 0xFF);
                held >>>= 8;
                count -= 8;
            }
        }

        void finish() {
            if (count > 0) {
                add(held & 0xFF);
            }
            if (filled > 0) {
                out.write(filled);
                out.write(block, 0, filled);
                filled = 0;
            }
        }

        private void add(int value) {
            block[filled++] = (byte) value;
            if (filled == 255) {
                out.write(255);
                out.write(block, 0, 255);
                filled = 0;
            }
        }
    }

    /**
     * The colours an animation is written in. Exact when every frame together has no more than
     * the format holds; otherwise the box of colours is cut, at the middle of its longest side,
     * until there are as many boxes as places, and each box is written as its mean.
     */
    private static final class Palette {
        private final int[] colours;
        private final int count;
        private final boolean transparent;
        /** The nearest place for each colour cut to five bits a channel; -1 until asked. */
        private final short[] nearest = new short[1 << 15];
        private final java.util.HashMap<Integer, Integer> exact;

        private Palette(int[] colours, int count, boolean transparent, java.util.HashMap<Integer, Integer> exact) {
            this.colours = colours;
            this.count = count;
            this.transparent = transparent;
            this.exact = exact;
            java.util.Arrays.fill(nearest, (short) -1);
        }

        static Palette of(Image image) {
            java.util.HashMap<Integer, Integer> seen = new java.util.HashMap<>();
            boolean holes = false;
            boolean many = false;
            int[] histogram = null;
            for (int f = 0; f < image.frameCount(); f++) {
                byte[] rgba = image.frame(f).pixels();
                for (int o = 0; o < rgba.length; o += 4) {
                    if ((rgba[o + 3] & 0xFF) < 128) {
                        holes = true;
                        continue;
                    }
                    int rgb = (rgba[o] & 0xFF) << 16 | (rgba[o + 1] & 0xFF) << 8 | (rgba[o + 2] & 0xFF);
                    if (!many) {
                        seen.putIfAbsent(rgb, seen.size());
                        if (seen.size() > 256) {
                            many = true;
                            histogram = new int[1 << 15];
                            for (int colour : seen.keySet()) {
                                histogram[five(colour)]++;
                            }
                        }
                    } else {
                        histogram[five(rgb)]++;
                    }
                }
            }
            int places = holes ? 255 : 256;
            if (!many && seen.size() <= places) {
                int[] colours = new int[256];
                seen.forEach((rgb, index) -> colours[index] = rgb);
                return new Palette(colours, seen.size() + (holes ? 1 : 0), holes, seen);
            }
            if (histogram == null) {
                // 256 colours and a hole besides: one more than fits, so they are cut like any other.
                histogram = new int[1 << 15];
                for (int colour : seen.keySet()) {
                    histogram[five(colour)]++;
                }
            }
            int[] colours = new int[256];
            int made = cut(histogram, places, colours);
            return new Palette(colours, made + (holes ? 1 : 0), holes, null);
        }

        private static int five(int rgb) {
            return (rgb >> 19 & 31) << 10 | (rgb >> 11 & 31) << 5 | (rgb >> 3 & 31);
        }

        /** Median cut over the colours that occur, weighted by how often. */
        private static int cut(int[] histogram, int places, int[] out) {
            int[] present = new int[1 << 15];
            int n = 0;
            for (int i = 0; i < histogram.length; i++) {
                if (histogram[i] > 0) {
                    present[n++] = i;
                }
            }
            // Boxes as ranges of the list of colours present, each sorted along its longest side.
            int[] from = new int[places];
            int[] to = new int[places];
            from[0] = 0;
            to[0] = n;
            int boxes = 1;
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = present[i];
            }
            while (boxes < places) {
                int widest = -1;
                int widestSpan = 0;
                int widestShift = 0;
                for (int b = 0; b < boxes; b++) {
                    if (to[b] - from[b] < 2) {
                        continue;
                    }
                    for (int shift = 0; shift <= 10; shift += 5) {
                        int low = 31;
                        int high = 0;
                        for (int i = from[b]; i < to[b]; i++) {
                            int v = order[i] >> shift & 31;
                            low = Math.min(low, v);
                            high = Math.max(high, v);
                        }
                        if (high - low > widestSpan) {
                            widestSpan = high - low;
                            widest = b;
                            widestShift = shift;
                        }
                    }
                }
                if (widest < 0) {
                    break; // every box is one colour
                }
                int shift = widestShift;
                java.util.Arrays.sort(order, from[widest], to[widest],
                        java.util.Comparator.comparingInt(c -> c >> shift & 31));
                long weight = 0;
                for (int i = from[widest]; i < to[widest]; i++) {
                    weight += histogram[order[i]];
                }
                long half = 0;
                int middle = from[widest];
                while (middle < to[widest] - 1 && half + histogram[order[middle]] <= weight / 2) {
                    half += histogram[order[middle++]];
                }
                middle = Math.max(middle, from[widest] + 1);
                from[boxes] = middle;
                to[boxes] = to[widest];
                to[widest] = middle;
                boxes++;
            }
            for (int b = 0; b < boxes; b++) {
                long r = 0;
                long g = 0;
                long blue = 0;
                long weight = 0;
                for (int i = from[b]; i < to[b]; i++) {
                    int c = order[i];
                    int times = histogram[c];
                    r += (long) (c >> 10 & 31) * times;
                    g += (long) (c >> 5 & 31) * times;
                    blue += (long) (c & 31) * times;
                    weight += times;
                }
                weight = Math.max(1, weight);
                out[b] = eight((int) (r / weight)) << 16 | eight((int) (g / weight)) << 8 | eight((int) (blue / weight));
            }
            return boxes;
        }

        private static int eight(int five) {
            return five << 3 | five >> 2;
        }

        int size() {
            return Math.max(2, count);
        }

        int colour(int index) {
            return index < colours.length ? colours[index] : 0;
        }

        boolean hasTransparent() {
            return transparent;
        }

        /** The place that stands for a pixel that is not there: the last. */
        int transparentIndex() {
            return count - 1;
        }

        /** @return whether the frame has a pixel that is not there */
        boolean index(byte[] rgba, byte[] out) {
            boolean holes = false;
            int opaque = transparent ? count - 1 : count;
            for (int i = 0, o = 0; o < rgba.length; i++, o += 4) {
                if (transparent && (rgba[o + 3] & 0xFF) < 128) {
                    out[i] = (byte) transparentIndex();
                    holes = true;
                    continue;
                }
                int rgb = (rgba[o] & 0xFF) << 16 | (rgba[o + 1] & 0xFF) << 8 | (rgba[o + 2] & 0xFF);
                if (exact != null) {
                    out[i] = (byte) (int) exact.get(rgb);
                    continue;
                }
                int key = five(rgb);
                int found = nearest[key];
                if (found < 0) {
                    int best = 0;
                    int bestDistance = Integer.MAX_VALUE;
                    for (int c = 0; c < opaque; c++) {
                        int dr = (colours[c] >> 16 & 0xFF) - (rgb >> 16 & 0xFF);
                        int dg = (colours[c] >> 8 & 0xFF) - (rgb >> 8 & 0xFF);
                        int db = (colours[c] & 0xFF) - (rgb & 0xFF);
                        int distance = dr * dr + dg * dg + db * db;
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = c;
                        }
                    }
                    found = best;
                    nearest[key] = (short) best;
                }
                out[i] = (byte) found;
            }
            return holes;
        }
    }

    private static void ascii(ByteArrayOutputStream out, String text) {
        for (int i = 0; i < text.length(); i++) {
            out.write(text.charAt(i));
        }
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >> 8);
    }
}
