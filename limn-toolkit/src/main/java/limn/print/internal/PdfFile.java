package limn.print.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * The bytes of a PDF file, written as they come: objects one after the other, each at an offset
 * the cross-reference table at the end records (ISO 32000-1, §7.5). An object may be numbered
 * before it is written, which is how a page names a font whose glyphs are only all known at the
 * end of the document.
 *
 * <p>Deterministic: the same objects in the same order give the same bytes, since nothing written
 * depends on the clock or on a hash order. Streams are compressed with {@code Deflater}'s default
 * level, as the PNG encoder compresses.
 */
final class PdfFile {

    private final CountingStream out;
    private final List<Long> offsets = new ArrayList<>(); // by object number - 1; -1 until written
    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);

    PdfFile(OutputStream target) throws IOException {
        this.out = new CountingStream(target);
        // The header, and a comment of four bytes over 127 that tells a transport the file is binary.
        write("%PDF-1.7\n");
        out.write(new byte[] {'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});
    }

    /** @return a number for an object written later with {@link #begin} */
    int reserve() {
        offsets.add(-1L);
        return offsets.size();
    }

    /** Starts object {@code number}, which {@link #reserve} gave; the caller writes it and {@link #end}s it. */
    void begin(int number) throws IOException {
        if (offsets.get(number - 1) != -1L) {
            throw new IllegalStateException("object " + number + " written twice");
        }
        offsets.set(number - 1, out.count());
        write(number + " 0 obj\n");
    }

    void end() throws IOException {
        write("\nendobj\n");
    }

    /** Writes a whole object whose body is {@code body}. */
    void object(int number, String body) throws IOException {
        begin(number);
        write(body);
        end();
    }

    /** @return a new object holding {@code body} */
    int object(String body) throws IOException {
        int number = reserve();
        object(number, body);
        return number;
    }

    /**
     * Writes a stream object: {@code dictionary} (without its brackets) and the bytes, compressed
     * when {@code compress} says.
     */
    void stream(int number, String dictionary, byte[] data, boolean compress) throws IOException {
        byte[] body = compress ? deflate(data) : data;
        begin(number);
        write("<<" + dictionary + (compress ? "/Filter/FlateDecode" : "") + "/Length " + body.length
                + ">>\nstream\n");
        out.write(body);
        write("\nendstream");
        end();
    }

    int stream(String dictionary, byte[] data, boolean compress) throws IOException {
        int number = reserve();
        stream(number, dictionary, data, compress);
        return number;
    }

    private byte[] deflate(byte[] data) {
        deflater.reset();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream packed = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        byte[] chunk = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(chunk);
            packed.write(chunk, 0, n);
        }
        return packed.toByteArray();
    }

    /**
     * Ends the file: the cross-reference table and the trailer naming the catalog and the
     * information dictionary.
     */
    void finish(int catalog, int info) throws IOException {
        for (int i = 0; i < offsets.size(); i++) {
            if (offsets.get(i) == -1L) {
                throw new IllegalStateException("object " + (i + 1) + " was numbered and never written");
            }
        }
        long xref = out.count();
        StringBuilder table = new StringBuilder(32 + 20 * offsets.size());
        table.append("xref\n0 ").append(offsets.size() + 1).append('\n');
        table.append("0000000000 65535 f\r\n");
        for (long offset : offsets) {
            String digits = Long.toString(offset);
            table.append("0".repeat(10 - digits.length())).append(digits).append(" 00000 n\r\n");
        }
        table.append("trailer\n<</Size ").append(offsets.size() + 1).append("/Root ").append(catalog)
                .append(" 0 R/Info ").append(info).append(" 0 R>>\nstartxref\n").append(xref).append("\n%%EOF\n");
        write(table.toString());
        out.flush();
        deflater.end();
    }

    private void write(String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** An output stream that knows how many bytes it has passed on: the offsets. */
    private static final class CountingStream extends OutputStream {
        private final OutputStream target;
        private long count;

        CountingStream(OutputStream target) {
            this.target = target;
        }

        long count() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            target.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            target.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }
    }
}
