package limn.demo.site;

import limn.components.Theme;
import limn.components.Viewport3D;
import limn.math.Quat;
import limn.math.Transform3D;
import limn.math.Vec3;
import limn.math.Vec4;
import limn.render3d.ColorSpace;
import limn.render3d.Graphics3D;
import limn.render3d.GpuTexture;
import limn.render3d.Light;
import limn.render3d.Material;
import limn.render3d.MeshData;
import limn.render3d.OrbitController;
import limn.render3d.Primitives;
import limn.render3d.Sampler;
import limn.render3d.TextureData;
import limn.render3d.scene.LightNode;
import limn.render3d.scene.MeshNode;
import limn.render3d.scene.Scene3D;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;

/**
 * The 3D viewport as a whole screen: a lit scene on a checkered ground, filling a window.
 *
 * <p>Deliberately not the demo's own 3D tab. That one is a laboratory: a picker over ten
 * capability probes, most of them on a black sky, which photographs as a dark rectangle with
 * something small in the middle. A site's picture has to read at a glance, so this is one
 * composed shot: the ground runs past the frame, the sky is lifted off black, and the
 * objects fill it.
 *
 * <p>Deterministic: the render ignores the frame clock and the camera is placed rather
 * than orbited, so two runs produce the same pixels.
 */
public final class Viewport3DExample {

    private Viewport3DExample() {
    }

    /** Base colour, metalness and roughness per sphere, laid out along the row. */
    private static final float[][] ROW = {
            //   x      r     g     b     metallic  roughness  radius
            {-1.05f, 0.86f, 0.16f, 0.22f, 0.0f, 0.18f, 0.95f},
            {1.05f, 1.00f, 0.76f, 0.32f, 1.0f, 0.28f, 0.95f},
            {3.15f, 0.45f, 0.16f, 0.92f, 0.1f, 0.30f, 0.95f},
            {5.25f, 0.95f, 0.96f, 1.00f, 0.2f, 0.12f, 0.95f},
    };

    /** The viewport alone, sized by its parent. */
    public static Viewport3D viewport(float width, float height) {
        MeshData ground = Primitives.plane(40, 40);
        MeshData sphere = Primitives.sphere(1f, 40, 60);
        MeshData cube = Primitives.cube(1.7f);
        Scene3D[] built = {null};

        Viewport3D viewport = new Viewport3D().setPreferredSize(width, height);
        // A narrower field of view than the default: at 60° the row sits in the middle
        // third of the frame with empty sky above it and empty floor below. 38° is a
        // portrait lens; it fills the frame without bending the spheres at the edges.
        viewport.camera().eye(new Vec3(2.1f, 1.35f, 11.4f)).target(new Vec3(2.1f, 0.05f, 0))
                .fovy((float) Math.toRadians(38));
        viewport.setController(new OrbitController(viewport.camera()));
        viewport.setRenderer((pass, seconds) -> {
            if (built[0] == null) {
                built[0] = build(ground, sphere, cube);
            }
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            built[0].render(pass, viewport.camera(), aspect);
        });
        // Half the device resolution of the widget's box: the 3D target is then never
        // larger than the picture the site publishes, and a scene this simple loses nothing
        // to it. It is also the one knob that moves 3D memory and fill cost quadratically.
        viewport.setRenderScale(0.5f);
        // Animated even though nothing moves. The FIRST 3D frame in a window comes out
        // empty: the capture of this scene in its dark palette was a flat sky with no
        // geometry in it, because that was the first frame the showcase window ever drew in
        // 3D. The render ignores its time argument, so redrawing every frame costs a capture
        // nothing and still produces identical pixels.
        viewport.setAnimated(true);
        viewport.onDispose(() -> {
            if (built[0] != null) {
                built[0].dispose();
            }
        });
        return viewport;
    }

    private static Scene3D build(MeshData ground, MeshData sphere, MeshData cube) {
        Scene3D scene = new Scene3D()
                // Not black. A sky at zero photographs as a hole in the page, and every
                // silhouette in front of it loses its edge.
                .background(new Vec4(0.16f, 0.15f, 0.24f, 1f))
                .ambient(new Vec3(0.10f, 0.10f, 0.14f))
                .exposure(1.15f)
                .castShadows(true);

        GpuTexture floor = Graphics3D.uploadTexture(
                checker(256, 8, 58, 56, 72, 196, 194, 208), Sampler.smooth());
        scene.root().add(new MeshNode(Graphics3D.upload(ground),
                Material.Pbr.of(1f, 1f, 1f).roughness(0.8f).textured(floor))
                .transform(Transform3D.at(new Vec3(0, -1f, 0))));

        for (float[] item : ROW) {
            scene.root().add(new MeshNode(Graphics3D.upload(sphere),
                    Material.Pbr.of(item[1], item[2], item[3])
                            .metallic(item[4]).roughness(item[5]))
                    .transform(new Transform3D(new Vec3(item[0], 0f, 0),
                            Quat.IDENTITY, new Vec3(item[6], item[6], item[6]))));
        }

        // A cube at the head of the row, not behind it: a row of four spheres alone reads
        // as a swatch chart, and a hard edge beside them is what makes it a scene.
        scene.root().add(new MeshNode(Graphics3D.upload(cube),
                Material.Pbr.of(0.32f, 0.34f, 0.44f).metallic(0.1f).roughness(0.42f))
                .transform(new Transform3D(new Vec3(-3.25f, -0.15f, -0.1f),
                        Quat.fromAxisAngle(Vec3.UNIT_Y, 0.62f), Vec3.ONE)));

        scene.root().add(new LightNode(new Light.Directional(
                new Vec3(0.45f, 1.15f, 0.5f), new Vec3(1f, 0.97f, 0.92f), 3.6f)));
        scene.root().add(new LightNode(new Light.Point(
                new Vec3(-4.5f, 2.2f, 3.5f), new Vec3(0.62f, 0.45f, 1f), 22f, 16f)));
        scene.root().add(new LightNode(new Light.Point(
                new Vec3(4.5f, 1.8f, 3.0f), new Vec3(1f, 0.72f, 0.45f), 16f, 14f)));
        return scene;
    }

    /** A checkerboard as pixels; the capture must not depend on an asset on disk. */
    private static TextureData checker(int size, int cells,
                                       int ar, int ag, int ab, int br, int bg, int bb) {
        byte[] pixels = new byte[size * size * 4];
        int cell = Math.max(1, size / cells);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean first = ((x / cell) + (y / cell)) % 2 == 0;
                int i = (y * size + x) * 4;
                pixels[i] = (byte) (first ? ar : br);
                pixels[i + 1] = (byte) (first ? ag : bg);
                pixels[i + 2] = (byte) (first ? ab : bb);
                pixels[i + 3] = (byte) 255;
            }
        }
        return new TextureData(size, size, pixels, ColorSpace.SRGB);
    }

    /**
     * The viewport alone, edge to edge, for the capture the site shows.
     *
     * <p><b>No title, no caption and no hint.</b> A picture on the site sits inside a page
     * that already carries its heading and its prose; text rendered into the image is that
     * text a second time, at a size the page did not choose, in a language the page cannot
     * translate. The only words a capture may carry are the ones on the controls it is
     * showing, and here there are none.
     */
    public static Scene scene() {
        Column column = new Column();
        column.crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(Expanded.of(viewport(600, 380), 1));

        Scene scene = new Scene(column);
        scene.setBackground(Theme.current().background);
        return scene;
    }
}
