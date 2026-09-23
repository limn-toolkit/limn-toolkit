package limn.components;

import limn.math.Mat4;
import limn.math.Quat;
import limn.math.Vec3;
import limn.render3d.Camera;
import limn.render3d.GpuMesh;
import limn.render3d.Graphics3D;
import limn.render3d.Material;
import limn.render3d.MeshData;
import limn.render3d.RenderPass;
import limn.render3d.VertexAttribute;

/**
 * What a {@link Viewport3D} shows until it is given a renderer: a slowly turning cube with six
 * coloured faces, lit by one light over an ambient floor. Drawn through the public renderer like
 * any application's scene, so a backend owes nothing for it beyond drawing a mesh.
 *
 * <p>One mesh per face, because a face's colour is its material's. The meshes are uploaded on the
 * first render, inside the paint that has the graphics context, and released by
 * {@link #dispose()}.
 */
final class DemoCube {

    /** Half the edge. */
    private static final float H = 0.8f;

    /** Normal, colour (authored sRGB) and four corners per face: +X, -X, +Y, -Y, +Z, -Z. */
    private static final float[][] FACES = {
            {1, 0, 0, 0.90f, 0.32f, 0.38f, H, -H, H, H, -H, -H, H, H, -H, H, H, H},
            {-1, 0, 0, 0.36f, 0.80f, 0.48f, -H, -H, -H, -H, -H, H, -H, H, H, -H, H, -H},
            {0, 1, 0, 0.32f, 0.58f, 0.96f, -H, H, H, H, H, H, H, H, -H, -H, H, -H},
            {0, -1, 0, 0.96f, 0.78f, 0.32f, -H, -H, -H, H, -H, -H, H, -H, H, -H, -H, H},
            {0, 0, 1, 0.66f, 0.46f, 0.96f, -H, -H, H, H, -H, H, H, H, H, -H, H, H},
            {0, 0, -1, 0.30f, 0.82f, 0.86f, H, -H, -H, -H, -H, -H, -H, H, -H, H, H, -H},
    };

    private static final Vec3 LIGHT = new Vec3(0.4f, 0.85f, 0.55f).normalize();
    private static final Vec3 LIGHT_COLOR = new Vec3(0.65f, 0.65f, 0.65f);
    private static final float AMBIENT = 0.35f;

    private final Camera camera = new Camera()
            .eye(new Vec3(0, 0, 3.6f))
            .fovy((float) Math.toRadians(42))
            .clip(0.1f, 20f);
    private final Material[] materials = new Material[FACES.length];
    private GpuMesh[] faces;

    DemoCube() {
        for (int f = 0; f < FACES.length; f++) {
            materials[f] = Material.SimpleLit.of(FACES[f][3], FACES[f][4], FACES[f][5]);
        }
    }

    /** @return the camera the cube is framed by, which is not the viewport's own */
    Camera camera() {
        return camera;
    }

    /** Draws the cube as it stands {@code timeSeconds} into its turn. */
    void render(RenderPass pass, double timeSeconds) {
        if (faces == null) {
            faces = new GpuMesh[FACES.length];
            for (int f = 0; f < FACES.length; f++) {
                faces[f] = Graphics3D.upload(face(FACES[f]));
            }
        }
        float t = (float) timeSeconds;
        Mat4 model = Mat4.rotation(Quat.fromAxisAngle(Vec3.UNIT_Y, t * 0.9f))
                .multiply(Mat4.rotation(Quat.fromAxisAngle(Vec3.UNIT_X, t * 0.55f)));
        pass.clear(0.13f, 0.15f, 0.20f, 1f).light(LIGHT, LIGHT_COLOR, AMBIENT);
        for (int f = 0; f < faces.length; f++) {
            pass.draw(faces[f], materials[f], model);
        }
    }

    /** Releases the meshes; the next render uploads them again. */
    void dispose() {
        if (faces != null) {
            for (GpuMesh face : faces) {
                face.dispose();
            }
            faces = null;
        }
    }

    private static MeshData face(float[] face) {
        float[] position = new float[12];
        float[] normal = new float[12];
        for (int c = 0; c < 4; c++) {
            System.arraycopy(face, 6 + c * 3, position, c * 3, 3);
            System.arraycopy(face, 0, normal, c * 3, 3);
        }
        return new MeshData()
                .put(VertexAttribute.POSITION, position)
                .put(VertexAttribute.NORMAL, normal)
                .put(VertexAttribute.UV0, new float[] {0, 0, 1, 0, 1, 1, 0, 1})
                .indices(new int[] {0, 1, 2, 0, 2, 3});
    }
}
