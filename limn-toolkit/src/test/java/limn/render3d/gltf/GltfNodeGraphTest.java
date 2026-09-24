package limn.render3d.gltf;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a model's structure may ask of the loader, beyond one accessor's bytes: its nodes form trees
 * no deeper than {@link GltfLoader}'s limit, and all its arrays together stay within one budget. A
 * 609-byte file of nodes each naming the next one twice asked for 2<sup>28</sup> nodes, a node naming
 * itself recursed until the stack ran out, and a 168 KB file whose strips all reused one buffer asked
 * for more than two gigabytes. Each is refused as {@link IllegalArgumentException}, before the arrays
 * exist.
 */
class GltfNodeGraphTest {

    private static final limn.concurrent.Progress NEVER_CANCELLED = new limn.concurrent.Progress() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void report(double fraction) {
        }
    };

    /** A document with one triangle mesh and the given nodes and scene roots. */
    private static byte[] withNodes(String nodes, String roots) {
        byte[] buffer = new byte[48];
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":" + roots + "}],"
                + "\"nodes\":" + nodes + ","
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":48}],"
                + "\"buffers\":[{\"uri\":\"data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(buffer) + "\",\"byteLength\":48}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String chain(int length) {
        StringBuilder nodes = new StringBuilder("[");
        for (int i = 0; i < length; i++) {
            nodes.append(i > 0 ? "," : "").append("{\"mesh\":0");
            if (i + 1 < length) {
                nodes.append(",\"children\":[").append(i + 1).append(']');
            }
            nodes.append('}');
        }
        return nodes.append(']').toString();
    }

    @Test
    void aNodeNamingItselfIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> GltfLoader.load(withNodes("[{\"children\":[0]}]", "[0]")));
    }

    @Test
    void aNodeNamedTwiceIsRefusedBeforeItsSubtreeIsBuiltTwice() {
        StringBuilder nodes = new StringBuilder("[");
        for (int i = 0; i < 28; i++) {
            nodes.append(i > 0 ? "," : "").append("{\"children\":[").append(i + 1).append(',')
                    .append(i + 1).append("]}");
        }
        nodes.append(",{\"mesh\":0}]");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> GltfLoader.load(withNodes(nodes.toString(), "[0]")));
        assertTrue(refused.getMessage().contains("more than one parent"), refused.getMessage());
    }

    @Test
    void aChildThatIsNoNodeAndARootThatIsAChildAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> GltfLoader.load(withNodes("[{\"children\":[7]}]", "[0]")));
        assertThrows(IllegalArgumentException.class,
                () -> GltfLoader.load(withNodes("[{\"children\":[1]},{\"mesh\":0}]", "[0,1]")));
    }

    @Test
    void aChainDeeperThanTheLimitIsRefusedAndAnOrdinaryOneLoads() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> GltfLoader.load(withNodes(chain(20_000), "[0]")));
        assertTrue(refused.getMessage().contains("levels deep"), refused.getMessage());
        GltfModel model = GltfLoader.load(withNodes(chain(200), "[0]"));
        assertEquals(200, model.nodes().size());
    }

    @Test
    void stripsThatReuseOneBufferPastTheBudgetAreRefused() {
        // A 64 KB buffer of zeros: 5461 positions and 32768 indices, all of them vertex 0. Each
        // strip below reads both, becomes a list of 98,298 indices and is given flat normals:
        // about 835,000 elements a strip, 334 million for the file's 400.
        byte[] buffer = new byte[65536];
        StringBuilder primitives = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            primitives.append(i > 0 ? "," : "")
                    .append("{\"attributes\":{\"POSITION\":0},\"indices\":1,\"mode\":5}");
        }
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[{\"mesh\":0}],\"meshes\":[{\"primitives\":[" + primitives + "]}],"
                + "\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":5461,\"type\":\"VEC3\"},"
                + "{\"bufferView\":0,\"componentType\":5123,\"count\":32768,\"type\":\"SCALAR\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":65536}],"
                + "\"buffers\":[{\"uri\":\"data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(buffer) + "\",\"byteLength\":65536}]}";
        // The same file under a budget a test can afford: forty times less than the real one.
        long budget = GltfLoader.MAX_ELEMENTS / 40;
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> GltfLoader.load(json.getBytes(StandardCharsets.UTF_8), NEVER_CANCELLED, budget));
        assertTrue(refused.getMessage().contains(budget + " elements"), refused.getMessage());
        assertEquals(1L << 26, GltfLoader.MAX_ELEMENTS,
                "400 of these strips, 2.4 bytes of file per element, pass the real budget too");
    }

    /**
     * A square of two triangles with no normals, with and without texture coordinates: four
     * positions, four UVs when asked, six indices. The loader gives it flat normals.
     */
    private static byte[] squareWithoutNormals(boolean uv) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(48 + 32 + 12)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float f : new float[] {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0}) {
            buffer.putFloat(f);
        }
        for (float f : new float[] {0, 0, 1, 0, 1, 1, 0, 1}) {
            buffer.putFloat(f);
        }
        for (short i : new short[] {0, 1, 2, 0, 2, 3}) {
            buffer.putShort(i);
        }
        String attributes = uv ? "{\"POSITION\":0,\"TEXCOORD_0\":1}" : "{\"POSITION\":0}";
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[{\"mesh\":0}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":" + attributes + ",\"indices\":2}]}],"
                + "\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":4,\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":4,\"type\":\"VEC2\"},"
                + "{\"bufferView\":2,\"componentType\":5123,\"count\":6,\"type\":\"SCALAR\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":48},"
                + "{\"buffer\":0,\"byteOffset\":48,\"byteLength\":32},"
                + "{\"buffer\":0,\"byteOffset\":80,\"byteLength\":12}],"
                + "\"buffers\":[{\"uri\":\"data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(buffer.array()) + "\",\"byteLength\":92}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The budget is charged before the arrays exist, so the charge for flat normals has to be what
     * the expansion then allocates. It left out the new index list: one element a corner that no
     * budget saw. Counted here from the arrays themselves, those read from the file and those the
     * model ends up holding, the load fits a budget of exactly that many and not one fewer.
     */
    @Test
    void flatNormalsAreChargedExactlyTheArraysTheyAllocate() {
        for (boolean uv : new boolean[] {false, true}) {
            byte[] file = squareWithoutNormals(uv);
            GltfModel model = GltfLoader.load(file);
            limn.render3d.MeshData flat = model.meshes().get(0).primitives().get(0).mesh();
            long read = 4 * 3 + (uv ? 4 * 2 : 0) + 6;
            long made = flat.get(limn.render3d.VertexAttribute.POSITION).length
                    + flat.get(limn.render3d.VertexAttribute.NORMAL).length
                    + (uv ? flat.get(limn.render3d.VertexAttribute.UV0).length : 0)
                    + flat.indices().length;
            assertEquals(6 * (uv ? 9 : 7), made, "a vertex of its own for every corner");

            long exact = read + made;
            GltfLoader.load(file, NEVER_CANCELLED, exact);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> GltfLoader.load(file, NEVER_CANCELLED, exact - 1),
                    "a budget one element short of what the load allocates must refuse it, uv=" + uv);
            assertTrue(refused.getMessage().contains("flat normals"), refused.getMessage());
        }
    }

    @Test
    void aByteStrideShorterThanItsElementIsRefused() {
        byte[] buffer = new byte[48];
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[{\"mesh\":0}],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":30,\"type\":\"VEC3\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":48,\"byteStride\":1}],"
                + "\"buffers\":[{\"uri\":\"data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(buffer) + "\",\"byteLength\":48}]}";
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> GltfLoader.load(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(refused.getMessage().contains("byteStride"), refused.getMessage());
    }
}
