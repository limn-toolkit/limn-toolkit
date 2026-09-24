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
        // about 737,000 elements a strip, 295 million for the file's 400.
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
