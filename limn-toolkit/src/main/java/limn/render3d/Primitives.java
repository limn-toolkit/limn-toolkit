package limn.render3d;

/**
 * Procedural {@link MeshData} for common shapes, each with POSITION, NORMAL and
 * UV0. Upload with {@code Graphics3D.upload(...)}.
 */
public final class Primitives {

    private Primitives() {
    }

    /** An axis-aligned cube of the given edge length, centered at the origin. */
    public static MeshData cube(float size) {
        float h = size / 2;
        float[][] faces = {
                {1, 0, 0, h, -h, h, h, -h, -h, h, h, -h, h, h, h},
                {-1, 0, 0, -h, -h, -h, -h, -h, h, -h, h, h, -h, h, -h},
                {0, 1, 0, -h, h, h, h, h, h, h, h, -h, -h, h, -h},
                {0, -1, 0, -h, -h, -h, h, -h, -h, h, -h, h, -h, -h, h},
                {0, 0, 1, -h, -h, h, h, -h, h, h, h, h, -h, h, h},
                {0, 0, -1, h, -h, -h, -h, -h, -h, -h, h, -h, h, h, -h},
        };
        float[] pos = new float[24 * 3];
        float[] nrm = new float[24 * 3];
        float[] uv = new float[24 * 2];
        int[] idx = new int[36];
        float[][] cornerUv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int f = 0; f < 6; f++) {
            float[] face = faces[f];
            for (int c = 0; c < 4; c++) {
                int v = f * 4 + c;
                pos[v * 3] = face[3 + c * 3];
                pos[v * 3 + 1] = face[3 + c * 3 + 1];
                pos[v * 3 + 2] = face[3 + c * 3 + 2];
                nrm[v * 3] = face[0];
                nrm[v * 3 + 1] = face[1];
                nrm[v * 3 + 2] = face[2];
                uv[v * 2] = cornerUv[c][0];
                uv[v * 2 + 1] = cornerUv[c][1];
            }
            int base = f * 4;
            int i = f * 6;
            idx[i] = base;
            idx[i + 1] = base + 1;
            idx[i + 2] = base + 2;
            idx[i + 3] = base;
            idx[i + 4] = base + 2;
            idx[i + 5] = base + 3;
        }
        return new MeshData()
                .put(VertexAttribute.POSITION, pos)
                .put(VertexAttribute.NORMAL, nrm)
                .put(VertexAttribute.UV0, uv)
                .indices(idx);
    }

    /** A UV sphere of the given radius, centered at the origin. */
    public static MeshData sphere(float radius, int rings, int sectors) {
        int cols = sectors + 1;
        int vertexCount = (rings + 1) * cols;
        float[] pos = new float[vertexCount * 3];
        float[] nrm = new float[vertexCount * 3];
        float[] uv = new float[vertexCount * 2];
        int v = 0;
        for (int r = 0; r <= rings; r++) {
            float phi = (float) (Math.PI * r / rings);
            float sinPhi = (float) Math.sin(phi);
            float cosPhi = (float) Math.cos(phi);
            for (int s = 0; s <= sectors; s++) {
                float theta = (float) (2 * Math.PI * s / sectors);
                float nx = sinPhi * (float) Math.cos(theta);
                float ny = cosPhi;
                float nz = sinPhi * (float) Math.sin(theta);
                pos[v * 3] = nx * radius;
                pos[v * 3 + 1] = ny * radius;
                pos[v * 3 + 2] = nz * radius;
                nrm[v * 3] = nx;
                nrm[v * 3 + 1] = ny;
                nrm[v * 3 + 2] = nz;
                uv[v * 2] = (float) s / sectors;
                uv[v * 2 + 1] = (float) r / rings;
                v++;
            }
        }
        int[] idx = new int[rings * sectors * 6];
        int i = 0;
        for (int r = 0; r < rings; r++) {
            for (int s = 0; s < sectors; s++) {
                int a = r * cols + s;
                int b = a + 1;
                int c = a + cols;
                int d = c + 1;
                idx[i++] = a;
                idx[i++] = c;
                idx[i++] = b;
                idx[i++] = b;
                idx[i++] = c;
                idx[i++] = d;
            }
        }
        return new MeshData()
                .put(VertexAttribute.POSITION, pos)
                .put(VertexAttribute.NORMAL, nrm)
                .put(VertexAttribute.UV0, uv)
                .indices(idx);
    }

    /** A flat plane in the XZ axis (normal +Y), centered at the origin. */
    public static MeshData plane(float width, float depth) {
        float w = width / 2;
        float d = depth / 2;
        return new MeshData()
                .put(VertexAttribute.POSITION, new float[]{
                        -w, 0, -d, w, 0, -d, w, 0, d, -w, 0, d})
                .put(VertexAttribute.NORMAL, new float[]{
                        0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0})
                .put(VertexAttribute.UV0, new float[]{0, 0, 1, 0, 1, 1, 0, 1})
                .indices(new int[]{0, 1, 2, 0, 2, 3});
    }
}
