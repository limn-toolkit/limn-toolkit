package limn.render3d.gltf;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a malformed model is refused with, site by site: an {@link IllegalArgumentException} of
 * exactly that class, saying which member it was reading and what it had to be, or which list an
 * index missed, or where in the text the JSON broke. {@link GltfLoaderFuzzTest} holds the class
 * over thousands of inputs; this holds the words for one input per kind of refusal, because a
 * refusal an application shows its user is only as useful as what it says.
 *
 * <p>Each of these escaped the loader as something else before: a {@code ClassCastException}, a
 * {@code NullPointerException}, an {@code IndexOutOfBoundsException}, a
 * {@code StringIndexOutOfBoundsException}, a {@code BufferUnderflowException}, or a
 * {@code NumberFormatException} saying only "For input string".
 */
class GltfLoaderRefusalTest {

    private static final String ASSET = "\"asset\": {\"version\": \"2.0\"}";

    private static IllegalArgumentException refusal(String document) {
        return refusal(document.getBytes(StandardCharsets.UTF_8));
    }

    private static IllegalArgumentException refusal(byte[] data) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> GltfLoader.load(data));
        assertSame(IllegalArgumentException.class, refused.getClass(), refused.toString());
        return refused;
    }

    private static void assertSays(String expected, IllegalArgumentException refused) {
        assertTrue(refused.getMessage().contains(expected),
                "expected the refusal to say \"" + expected + "\", but it said \""
                        + refused.getMessage() + "\"");
    }

    @Test
    void aValueOfTheWrongTypeNamesTheMemberAndTheTypeItHadToBe() {
        assertSays("\"mesh\" must be a number, not a string",
                refusal("{" + ASSET + ", \"nodes\": [{\"mesh\": \"x\"}]}"));
        assertSays("\"name\" must be a string, not the number 1.0",
                refusal("{" + ASSET + ", \"nodes\": [{\"name\": 1}]}"));
        assertSays("\"children\" must be an array, not an object",
                refusal("{" + ASSET + ", \"nodes\": [{\"children\": {}}]}"));
        assertSays("glTF node 0 must be an object, not an array",
                refusal("{" + ASSET + ", \"nodes\": [[]]}"));
        assertSays("glTF document must be an object, not an array", refusal("[1, 2]"));
    }

    @Test
    void aRequiredMemberThatIsMissingIsNamed() {
        assertSays("\"attributes\" must be an object, not null",
                refusal("{" + ASSET + ", \"meshes\": [{\"primitives\": [{\"indices\": 0}]}]}"));
    }

    @Test
    void aVectorWithTooFewNumbersSaysHowManyItNeeds() {
        assertSays("\"translation\" must hold 3 numbers, not 2",
                refusal("{" + ASSET + ", \"nodes\": [{\"translation\": [1, 2]}]}"));
        assertSays("\"matrix\" must hold 16 numbers, not 4",
                refusal("{" + ASSET + ", \"nodes\": [{\"matrix\": [1, 0, 0, 1]}]}"));
    }

    @Test
    void anIndexPastTheEndOfAListNamesTheListAndItsLength() {
        assertSays("glTF names accessor 7, but the file has 0", refusal("{" + ASSET
                + ", \"meshes\": [{\"primitives\": [{\"attributes\": {\"POSITION\": 7}}]}]}"));
        assertSays("glTF names scene 2, but the file has 1",
                refusal("{" + ASSET + ", \"scene\": 2, \"scenes\": [{\"nodes\": []}]}"));
    }

    /**
     * A node's mesh used to be looked up only when the scene was built, so a node naming one the
     * file does not have loaded and threw IndexOutOfBoundsException later, from a model the
     * application had been told was good.
     */
    @Test
    void aNodeNamingAMeshTheFileDoesNotHaveIsRefusedWhenTheFileIsRead() {
        assertSays("glTF node 0 names mesh 3, but the file has 0",
                refusal("{" + ASSET + ", \"nodes\": [{\"mesh\": 3}]}"));
    }

    @Test
    void aBufferStandingForAMissingBinaryChunkIsNamed() {
        assertSays("glTF buffer 0 has no uri", refusal("{" + ASSET
                + ", \"buffers\": [{\"byteLength\": 4}],"
                + " \"bufferViews\": [{\"buffer\": 0, \"byteLength\": 4}],"
                + " \"images\": [{\"bufferView\": 0, \"mimeType\": \"image/png\"}]}"));
    }

    @Test
    void textThatIsNotJsonSaysWhereItBroke() {
        assertSays("JSON: the text ends at index 9 in the middle of a value",
                refusal("{\"asset\":"));
        assertSays("JSON: the text ends at index 12 in the middle of a value",
                refusal("{\"asset\": \"2"));
        assertSays("JSON: malformed number \"1e\" at index 10", refusal("{\"scene\": 1e}"));
        assertSays("JSON: expected a value at index 10", refusal("{\"scene\": ]}"));
        assertSays("JSON: expected four hex digits at index 12", refusal("{\"name\": \"\\u12\"}"));
    }

    @Test
    void aGlbShorterThanItsHeaderIsRefusedAsOne() {
        byte[] glb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546C67).putInt(2).array();
        assertSays("malformed GLB: 8 bytes, shorter than the 12-byte header", refusal(glb));
    }

    @Test
    void anHonestDocumentStillLoads() {
        GltfModel model = GltfLoader.load(("{" + ASSET + ", \"scene\": 0, \"scenes\": [{\"nodes\": [0]}],"
                + " \"nodes\": [{\"name\": \"n\", \"translation\": [1, 2, 3], \"children\": []}]}")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(1, model.nodes().size());
    }
}
