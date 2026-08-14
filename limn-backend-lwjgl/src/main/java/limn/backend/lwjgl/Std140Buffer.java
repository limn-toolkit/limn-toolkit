package limn.backend.lwjgl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Packs values into a uniform-buffer following the OpenGL <b>std140</b> layout
 * rules, so the bytes match what a GLSL {@code layout(std140)} block expects. It
 * writes at absolute offsets and advances a cursor with the correct base
 * alignment for each type (scalar 4, vec2 8, vec3/vec4/array-element/mat-column
 * 16); a {@code vec3} occupies 12 bytes so a trailing {@code float} packs into its
 * 4th slot, exactly as std140 specifies.
 *
 * <p>Pure {@link ByteBuffer} arithmetic (no GL), so the layout is unit-testable
 * headless. {@link Gl3DContext} fills one of these per frame and uploads it with
 * {@code glBufferSubData}.
 */
final class Std140Buffer {

    private final ByteBuffer buffer;
    private int cursor;

    Std140Buffer(int capacityBytes) {
        this.buffer = ByteBuffer.allocateDirect(capacityBytes).order(ByteOrder.nativeOrder());
    }

    /** Rewinds the write cursor so the buffer can be refilled next frame. */
    Std140Buffer reset() {
        cursor = 0;
        return this;
    }

    private void align(int alignment) {
        cursor = (cursor + alignment - 1) & ~(alignment - 1);
    }

    Std140Buffer putFloat(float v) {
        align(4);
        buffer.putFloat(cursor, v);
        cursor += 4;
        return this;
    }

    Std140Buffer putInt(int v) {
        align(4);
        buffer.putInt(cursor, v);
        cursor += 4;
        return this;
    }

    Std140Buffer putVec2(float x, float y) {
        align(8);
        buffer.putFloat(cursor, x);
        buffer.putFloat(cursor + 4, y);
        cursor += 8;
        return this;
    }

    Std140Buffer putVec3(float x, float y, float z) {
        align(16);
        buffer.putFloat(cursor, x);
        buffer.putFloat(cursor + 4, y);
        buffer.putFloat(cursor + 8, z);
        cursor += 12; // no trailing pad: the next member aligns itself
        return this;
    }

    Std140Buffer putVec4(float x, float y, float z, float w) {
        align(16);
        buffer.putFloat(cursor, x);
        buffer.putFloat(cursor + 4, y);
        buffer.putFloat(cursor + 8, z);
        buffer.putFloat(cursor + 12, w);
        cursor += 16;
        return this;
    }

    Std140Buffer putIVec4(int x, int y, int z, int w) {
        align(16);
        buffer.putInt(cursor, x);
        buffer.putInt(cursor + 4, y);
        buffer.putInt(cursor + 8, z);
        buffer.putInt(cursor + 12, w);
        cursor += 16;
        return this;
    }

    /** Column-major 4×4 (16 floats): four vec4 columns, 64 bytes. */
    Std140Buffer putMat4(float[] columnMajor) {
        align(16);
        for (int i = 0; i < 16; i++) {
            buffer.putFloat(cursor + i * 4, columnMajor[i]);
        }
        cursor += 64;
        return this;
    }

    /** Advances the cursor to the next 16-byte boundary (start of an array element). */
    Std140Buffer alignElement() {
        align(16);
        return this;
    }

    /** Bytes written so far (also the offset the next aligned write would target). */
    int position() {
        return cursor;
    }

    /** The backing buffer, positioned at 0 with the full capacity as its limit, ready to upload. */
    ByteBuffer buffer() {
        buffer.position(0);
        buffer.limit(buffer.capacity());
        return buffer;
    }

    int capacity() {
        return buffer.capacity();
    }
}
