package limn.render3d.gltf;

import limn.render3d.MeshData;
import limn.render3d.VertexAttribute;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses a hand-built embedded glTF (a base64 buffer computed here, so it is real
 * glTF the loader has no special knowledge of) and checks the decoded geometry,
 * materials and node hierarchy, all headless (no GPU, no image decode).
 */
class GltfLoaderTest {

    private static final float[] POSITIONS = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0};
    private static final int[] INDICES = {0, 1, 2, 0, 2, 3};

    private static byte[] gltf() {
        // Buffer: positions(48) | normals(48) | uv(32) | indices(12) = 140 bytes.
        ByteBuffer bb = ByteBuffer.allocate(140).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : POSITIONS) {
            bb.putFloat(v);
        }
        for (float v : new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1}) {
            bb.putFloat(v);
        }
        for (float v : new float[]{0, 0, 1, 0, 1, 1, 0, 1}) {
            bb.putFloat(v);
        }
        for (int v : INDICES) {
            bb.putShort((short) v);
        }
        String uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bb.array());

        String json = """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0,
                  "scenes": [{"nodes": [0, 2]}],
                  "nodes": [
                    {"name": "parent", "translation": [1, 0, 0], "mesh": 0, "children": [1]},
                    {"name": "child", "translation": [2, 0, 0], "mesh": 0},
                    {"name": "matrixNode", "matrix": [1,0,0,0, 0,1,0,0, 0,0,1,0, 3,0,0,1]}
                  ],
                  "meshes": [{"name": "quad", "primitives": [
                    {"attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}, "indices": 3, "material": 0}
                  ]}],
                  "materials": [{"name": "red",
                    "pbrMetallicRoughness": {"baseColorFactor": [0.8, 0.1, 0.1, 1.0],
                      "metallicFactor": 0.25, "roughnessFactor": 0.6},
                    "emissiveFactor": [0.0, 0.0, 0.0]}],
                  "accessors": [
                    {"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3",
                     "min": [-1,-1,0], "max": [1,1,0]},
                    {"bufferView": 1, "componentType": 5126, "count": 4, "type": "VEC3"},
                    {"bufferView": 2, "componentType": 5126, "count": 4, "type": "VEC2"},
                    {"bufferView": 3, "componentType": 5123, "count": 6, "type": "SCALAR"}
                  ],
                  "bufferViews": [
                    {"buffer": 0, "byteOffset": 0, "byteLength": 48},
                    {"buffer": 0, "byteOffset": 48, "byteLength": 48},
                    {"buffer": 0, "byteOffset": 96, "byteLength": 32},
                    {"buffer": 0, "byteOffset": 128, "byteLength": 12}
                  ],
                  "buffers": [{"byteLength": 140, "uri": "URI"}]
                }
                """.replace("URI", uri);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void missingNormalsGetFlatShadingPerSpec() {
        // Positions + indices only: per spec the loader must synthesize flat
        // normals (the PBR shader would otherwise normalize zero → black mesh).
        ByteBuffer bb = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : POSITIONS) {
            bb.putFloat(v);
        }
        for (int v : INDICES) {
            bb.putShort((short) v);
        }
        String uri = "data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(bb.array());
        String json = """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0,
                  "scenes": [{"nodes": [0]}],
                  "nodes": [{"mesh": 0}],
                  "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}],
                  "accessors": [
                    {"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3"},
                    {"bufferView": 1, "componentType": 5123, "count": 6, "type": "SCALAR"}
                  ],
                  "bufferViews": [
                    {"buffer": 0, "byteOffset": 0, "byteLength": 48},
                    {"buffer": 0, "byteOffset": 48, "byteLength": 12}
                  ],
                  "buffers": [{"uri": "%s", "byteLength": 60}]
                }
                """.formatted(uri);
        MeshData mesh = GltfLoader.load(json.getBytes(StandardCharsets.UTF_8))
                .meshes().get(0).primitives().get(0).mesh();
        assertTrue(mesh.has(VertexAttribute.NORMAL), "flat normals synthesized");
        assertEquals(6, mesh.vertexCount(), "de-indexed: three vertices per face");
        float[] normals = mesh.get(VertexAttribute.NORMAL);
        for (int i = 0; i < normals.length; i += 3) {
            assertEquals(0, normals[i], 1e-5f);
            assertEquals(0, normals[i + 1], 1e-5f);
            assertEquals(1, normals[i + 2], 1e-5f, "the CCW quad faces +Z");
        }
    }

    @Test
    void decodesMeshGeometry() {
        GltfModel model = GltfLoader.load(gltf());
        assertEquals(1, model.meshes().size());
        assertEquals(1, model.primitiveCount());

        MeshData mesh = model.meshes().get(0).primitives().get(0).mesh();
        assertEquals(4, mesh.vertexCount());
        assertArrayEquals(POSITIONS, mesh.get(VertexAttribute.POSITION), 1e-6f);
        assertArrayEquals(INDICES, mesh.indices());
        assertTrue(mesh.has(VertexAttribute.NORMAL));
        assertTrue(mesh.has(VertexAttribute.UV0));
    }

    @Test
    void decodesTheMaterial() {
        GltfModel.MaterialDef mat = GltfLoader.load(gltf()).materials().get(0);
        assertEquals("red", mat.name());
        assertEquals(0.8f, mat.baseColor().x(), 1e-6f);
        assertEquals(1.0f, mat.baseColor().w(), 1e-6f);
        assertEquals(0.25f, mat.metallic(), 1e-6f);
        assertEquals(0.6f, mat.roughness(), 1e-6f);
        assertEquals(-1, mat.baseColorTexture(), "no texture on this material");
    }

    @Test
    void buildsTheNodeHierarchy() {
        GltfModel model = GltfLoader.load(gltf());
        assertEquals(3, model.nodes().size());
        assertArrayEquals(new int[]{0, 2}, model.rootNodes());

        GltfModel.NodeDef parent = model.nodes().get(0);
        assertEquals("parent", parent.name());
        assertEquals(1f, parent.transform().translation().x(), 1e-6f);
        assertEquals(0, parent.mesh());
        assertArrayEquals(new int[]{1}, parent.children());

        assertEquals(2f, model.nodes().get(1).transform().translation().x(), 1e-6f);
    }

    @Test
    void decomposesAMatrixNode() {
        GltfModel model = GltfLoader.load(gltf());
        GltfModel.NodeDef matrixNode = model.nodes().get(2);
        assertEquals(3f, matrixNode.transform().translation().x(), 1e-6f, "translation from matrix col 3");
        assertEquals(1f, matrixNode.transform().scale().x(), 1e-6f, "unit scale");
    }

    // ------------------------------------------------- robustness (code review)

    /** Three float positions + three normalized-ushort UV pairs in one buffer. */
    private static byte[] triangleWithQuantizedUvs(String primitiveExtras) {
        ByteBuffer bb = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}) {
            bb.putFloat(v);
        }
        bb.putShort((short) 0).putShort((short) 0xFFFF);
        bb.putShort((short) 32767).putShort((short) 0);
        bb.putShort((short) 0xFFFF).putShort((short) 0xFFFF);
        String uri = "data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(bb.array());
        String json = """
                {
                  "asset": {"version": "2.0"},
                  "meshes": [{"primitives": [
                    {"attributes": {"POSITION": 0, "TEXCOORD_0": 1}EXTRAS}
                  ]}],
                  "accessors": [
                    {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 1, "componentType": 5123, "normalized": true, "count": 3, "type": "VEC2"}
                  ],
                  "bufferViews": [
                    {"buffer": 0, "byteOffset": 0, "byteLength": 36},
                    {"buffer": 0, "byteOffset": 36, "byteLength": 12}
                  ],
                  "buffers": [{"byteLength": 48, "uri": "URI"}]
                }
                """.replace("URI", uri).replace("EXTRAS", primitiveExtras);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void decodesNormalizedUnsignedShortUvs() {
        // Regression: componentType was ignored; quantized UVs were read as
        // garbage floats without any error.
        MeshData mesh = GltfLoader.load(triangleWithQuantizedUvs(""))
                .meshes().get(0).primitives().get(0).mesh();
        float[] uv = mesh.get(VertexAttribute.UV0);
        assertEquals(0f, uv[0], 1e-6f);
        assertEquals(1f, uv[1], 1e-6f);
        assertEquals(32767 / 65535f, uv[2], 1e-6f);
        assertEquals(1f, uv[5], 1e-6f);
    }

    @Test
    void convertsTriangleStripsToLists() {
        // Regression: primitive mode was ignored; strips rendered as soup.
        MeshData mesh = GltfLoader.load(triangleWithQuantizedUvs(", \"mode\": 5"))
                .meshes().get(0).primitives().get(0).mesh();
        assertArrayEquals(new int[]{0, 1, 2}, mesh.indices(), "3-vertex strip = 1 triangle");
    }

    @Test
    void rejectsPointAndLineModesDescriptively() {
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> GltfLoader.load(triangleWithQuantizedUvs(", \"mode\": 1")));
        assertTrue(error.getMessage().contains("mode"), error.getMessage());
    }

    @Test
    void rejectsSparseAccessorsDescriptively() {
        // Regression: a spec-valid sparse accessor crashed with a raw NPE.
        String json = """
                {
                  "asset": {"version": "2.0"},
                  "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
                  "accessors": [{"componentType": 5126, "count": 3, "type": "VEC3",
                                 "sparse": {"count": 1}}],
                  "bufferViews": [],
                  "buffers": []
                }
                """;
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> GltfLoader.load(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(error.getMessage().contains("sparse"), error.getMessage());
    }

    @Test
    void rejectsMalformedGlbChunkLengths() {
        ByteBuffer bb = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0x46546C67); // "glTF"
        bb.putInt(2);
        bb.putInt(20);
        bb.putInt(0x7FFFFFFF); // chunk claims 2 GB
        bb.putInt(0x4E4F534A); // "JSON"
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> GltfLoader.load(bb.array()));
        assertTrue(error.getMessage().contains("GLB"), error.getMessage());
    }

    @Test
    void rejectsDataUrisWithoutACommaDescriptively() {
        String json = """
                {
                  "asset": {"version": "2.0"},
                  "images": [{"uri": "data:image/png;base64GARBAGE"}],
                  "buffers": []
                }
                """;
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> GltfLoader.load(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(error.getMessage().contains("data URI"), error.getMessage());
    }

    @Test
    void parsesSamplers() {
        // Regression: sampler settings were parsed and then silently discarded.
        String json = """
                {
                  "asset": {"version": "2.0"},
                  "samplers": [{"magFilter": 9728, "minFilter": 9986,
                                "wrapS": 33071, "wrapT": 10497}],
                  "buffers": []
                }
                """;
        GltfModel model = GltfLoader.load(json.getBytes(StandardCharsets.UTF_8));
        GltfModel.SamplerDef sampler = model.samplers().get(0);
        assertEquals(9728, sampler.magFilter());
        assertEquals(9986, sampler.minFilter());
        assertEquals(33071, sampler.wrapS());
        assertEquals(10497, sampler.wrapT());
    }
}
