package limn.render3d.gltf;

import limn.render3d.MeshData;
import limn.render3d.VertexAttribute;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model is input. {@link GltfLoader#load(byte[])} is public and says nothing about where the
 * bytes came from, and every array the parser sizes is sized from a number the document chose, so
 * a two-hundred-byte file can ask for a gigabyte by declaring an accessor with a hundred million
 * elements, or for a negative array by declaring a count below zero.
 *
 * <p>Each case asserts the exception <b>type</b>, which is the whole point: a loader that allocates
 * first and discovers the buffer is too short while reading it throws {@code OutOfMemoryError} or
 * {@code IndexOutOfBoundsException}, and neither is an {@code IllegalArgumentException}. The
 * declaration has to be refused against the bytes behind it before the array exists.
 */
class GltfLoaderBoundsTest {

    /** Four VEC3 positions (48 bytes) then six unsigned-short indices (12): 60 bytes in all. */
    private static byte[] model(long positionCount, long indexCount) {
        return model(positionCount, indexCount, new int[]{0, 1, 2, 0, 2, 3});
    }

    private static byte[] model(long positionCount, long indexCount, int[] indices) {
        ByteBuffer bb = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : new float[]{-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0}) {
            bb.putFloat(v);
        }
        for (int v : indices) {
            bb.putShort((short) v);
        }
        String json = String.format(Locale.ROOT, """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0,
                  "scenes": [{"nodes": [0]}],
                  "nodes": [{"mesh": 0}],
                  "meshes": [{"primitives": [
                    {"attributes": {"POSITION": 0}, "indices": 1}
                  ]}],
                  "accessors": [
                    {"bufferView": 0, "componentType": 5126, "count": %d, "type": "VEC3"},
                    {"bufferView": 1, "componentType": 5123, "count": %d, "type": "SCALAR"}
                  ],
                  "bufferViews": [
                    {"buffer": 0, "byteOffset": 0, "byteLength": 48},
                    {"buffer": 0, "byteOffset": 48, "byteLength": 12}
                  ],
                  "buffers": [{"uri": "data:application/octet-stream;base64,%s", "byteLength": 60}]
                }
                """, positionCount, indexCount,
                Base64.getEncoder().encodeToString(bb.array()));
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theHonestModelStillLoads() {
        // The twin of every hostile file below, differing only in the two counts: the bound has to
        // refuse what does not fit, not everything.
        MeshData mesh = GltfLoader.load(model(4, 6))
                .meshes().get(0).primitives().get(0).mesh();
        assertEquals(6, mesh.vertexCount(), "de-indexed by the flat-normal path");
        assertTrue(mesh.has(VertexAttribute.POSITION));
    }

    /**
     * The ceiling is the application's to move, for the whole process: lowered, the honest model
     * above is refused and the message names the setter; put back, it loads again. A load reads
     * the budget when it starts. The owner chose a setting over a fixed constant on 2026-09-26.
     */
    @Test
    void theElementBudgetIsTheApplicationsToMove() {
        assertEquals(1L << 26, GltfLoader.DEFAULT_ELEMENT_BUDGET);
        assertEquals(GltfLoader.DEFAULT_ELEMENT_BUDGET, GltfLoader.elementBudget());
        try {
            GltfLoader.setElementBudget(10);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> GltfLoader.load(model(4, 6)));
            assertTrue(refused.getMessage().contains("10 elements")
                    && refused.getMessage().contains("setElementBudget"), refused.getMessage());
            assertThrows(IllegalArgumentException.class, () -> GltfLoader.setElementBudget(0));
            assertEquals(10, GltfLoader.elementBudget(), "a refused setting leaves the budget alone");
        } finally {
            GltfLoader.setElementBudget(GltfLoader.DEFAULT_ELEMENT_BUDGET);
        }
        GltfLoader.load(model(4, 6));
    }

    /**
     * An index into another list of the document is checked where it is read: a texture naming an
     * image the file does not have, and a material naming a texture the file does not have, both
     * loaded and threw ArrayIndexOutOfBoundsException later, from the texture decode or upload,
     * instead of the IllegalArgumentException a malformed file gets. A texture with no image at
     * all is legal glTF (an extension may supply one) and loads as no texture: it threw from the
     * decode too.
     */
    @Test
    void anIndexIntoAListTheFileDoesNotFillIsRefusedWhenTheFileIsRead() {
        String image = "\"images\": [{\"uri\": \"data:image/png;base64,AAAA\"}]";
        String[] documents = {
                "{\"asset\": {\"version\": \"2.0\"}, " + image
                        + ", \"textures\": [{\"source\": 3}]}",
                "{\"asset\": {\"version\": \"2.0\"}, " + image
                        + ", \"textures\": [{\"source\": 0}], \"materials\": [{"
                        + "\"pbrMetallicRoughness\": {\"baseColorTexture\": {\"index\": 2}}}]}",
        };
        for (String document : documents) {
            assertThrows(IllegalArgumentException.class,
                    () -> GltfLoader.load(document.getBytes(StandardCharsets.UTF_8)), document);
        }
        String honest = "{\"asset\": {\"version\": \"2.0\"}, " + image
                + ", \"textures\": [{\"source\": 0}], \"materials\": [{"
                + "\"pbrMetallicRoughness\": {\"baseColorTexture\": {\"index\": 0}}}]}";
        GltfLoader.load(honest.getBytes(StandardCharsets.UTF_8));

        String noSource = "{\"asset\": {\"version\": \"2.0\"}, " + image
                + ", \"textures\": [{\"sampler\": 0}], \"materials\": [{"
                + "\"pbrMetallicRoughness\": {\"baseColorTexture\": {\"index\": 0}}}]}";
        GltfLoader.load(noSource.getBytes(StandardCharsets.UTF_8)).decodeTextures();
    }

    @Test
    void aVertexCountBeyondItsBufferIsRefusedBeforeTheArrayIsAllocated() {
        // 100 million VEC3 floats is 1.2 GB asked for by a file of a few hundred bytes.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> GltfLoader.load(model(100_000_000, 6)));
        assertTrue(refused.getMessage().contains("100000000"), refused.getMessage());

        // And the near miss: five VEC3 positions in a bufferView that holds four.
        assertThrows(IllegalArgumentException.class, () -> GltfLoader.load(model(5, 6)));
    }

    @Test
    void anIndexCountBeyondItsBufferIsRefusedBeforeTheArrayIsAllocated() {
        assertThrows(IllegalArgumentException.class, () -> GltfLoader.load(model(4, 500_000_000)));
        assertThrows(IllegalArgumentException.class, () -> GltfLoader.load(model(4, 7)));
    }

    @Test
    void aNegativeCountIsRefusedRatherThanReachingTheAllocator() {
        // new float[-1] is a NegativeArraySizeException, which says nothing about the model.
        assertThrows(IllegalArgumentException.class, () -> GltfLoader.load(model(-1, 6)));
        assertThrows(IllegalArgumentException.class, () -> GltfLoader.load(model(4, -1)));
    }

    @Test
    void aCountThatOverflowsTheByteArithmeticIsRefused() {
        // count * stride wraps int negative at exactly the sizes this exists to refuse, and a
        // wrapped negative compares as comfortably inside the buffer.
        assertThrows(IllegalArgumentException.class,
                () -> GltfLoader.load(model(Integer.MAX_VALUE, 6)));
    }

    @Test
    void anIndexBeyondThePrimitivesVerticesIsRefusedBeforeItReachesTheDevice() {
        // The accessor fits its buffer; the value in it points past the four vertices, and
        // glDrawElements would have read the vertex buffer there.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GltfLoader.load(model(4, 6, new int[]{0, 1, 2, 0, 2, 7})));
        assertTrue(error.getMessage().contains("7"), error.getMessage());
        assertTrue(error.getMessage().contains("4 vertices"), error.getMessage());
    }

    @Test
    void aDocumentOfNothingButBracketsIsRefusedRatherThanOverflowingTheStack() {
        byte[] brackets = "[".repeat(200_000).getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> GltfLoader.load(brackets));
    }

    @Test
    void anImageLongerThanItsBufferIsRefusedBeforeTheArrayIsAllocated() {
        String json = """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0,
                  "scenes": [{"nodes": []}],
                  "images": [{"bufferView": 0, "mimeType": "image/png"}],
                  "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 2000000000}],
                  "buffers": [{"uri": "data:application/octet-stream;base64,AAAA", "byteLength": 3}]
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> GltfLoader.load(json.getBytes(StandardCharsets.UTF_8)));
    }
}
