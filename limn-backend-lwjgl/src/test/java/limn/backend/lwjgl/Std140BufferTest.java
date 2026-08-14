package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The std140 packer's offsets must match what a GLSL {@code layout(std140)} block
 * expects; this pins the alignment rules and the exact Frame/Lights/Material
 * layouts the PBR shader declares. Pure {@link ByteBuffer} math; no GL needed.
 */
class Std140BufferTest {

    @Test
    void scalarThenVec3ThenScalarPacks() {
        Std140Buffer b = new Std140Buffer(64);
        assertEquals(0, b.position());
        b.putFloat(1f);              // [0, 4)
        assertEquals(4, b.position());
        b.putVec3(1f, 2f, 3f);       // vec3 aligns to 16 → [16, 28)
        assertEquals(28, b.position());
        b.putFloat(9f);              // packs into the vec3's 4th slot → [28, 32)
        assertEquals(32, b.position());
    }

    @Test
    void vec2AlignsToEight() {
        Std140Buffer b = new Std140Buffer(64);
        b.putFloat(1f);              // [0, 4)
        b.putVec2(1f, 2f);           // aligns to 8 → [8, 16)
        assertEquals(16, b.position());
    }

    @Test
    void mat4ThenVec4MatchesFrameBlock() {
        Std140Buffer b = new Std140Buffer(80);
        b.putMat4(new float[16]);    // [0, 64)
        assertEquals(64, b.position());
        b.putVec4(0f, 0f, 0f, 0f);   // [64, 80)
        assertEquals(80, b.position());
    }

    @Test
    void lightsArrayElementsStaySixteenAligned() {
        Std140Buffer b = new Std140Buffer(32 + 3 * 64);
        b.putVec4(0f, 0f, 0f, 0f);   // ambient [0, 16)
        b.putIVec4(1, 0, 0, 0);      // count   [16, 32)
        assertEquals(32, b.position());
        for (int e = 0; e < 3; e++) {
            int start = 32 + e * 64;
            b.alignElement();
            assertEquals(start, b.position(), "light " + e + " start");
            b.putVec4(0f, 0f, 0f, 0f).putVec4(0f, 0f, 0f, 0f)
                    .putVec4(0f, 0f, 0f, 0f).putVec4(0f, 0f, 0f, 0f);
            assertEquals(start + 64, b.position(), "light " + e + " end");
        }
    }

    @Test
    void writesValuesAtTheComputedOffsets() {
        Std140Buffer b = new Std140Buffer(32);
        b.putFloat(1.5f);            // offset 0
        b.putVec3(2f, 3f, 4f);       // offsets 16, 20, 24
        ByteBuffer buf = b.buffer();
        assertEquals(1.5f, buf.getFloat(0), 0f);
        assertEquals(2f, buf.getFloat(16), 0f);
        assertEquals(3f, buf.getFloat(20), 0f);
        assertEquals(4f, buf.getFloat(24), 0f);
    }

    @Test
    void bufferIsRewoundAndSizedForUpload() {
        Std140Buffer b = new Std140Buffer(48);
        b.putVec4(1f, 2f, 3f, 4f);
        ByteBuffer buf = b.buffer();
        assertEquals(0, buf.position());
        assertEquals(48, buf.limit());
        assertEquals(48, buf.remaining());
    }

    @Test
    void resetRewindsTheCursor() {
        Std140Buffer b = new Std140Buffer(32);
        b.putVec4(1f, 2f, 3f, 4f);
        assertEquals(16, b.position());
        b.reset();
        assertEquals(0, b.position());
    }
}
