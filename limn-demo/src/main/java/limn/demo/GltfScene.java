package limn.demo;

import limn.components.Label;
import limn.components.ScrollView;
import limn.components.Theme;
import limn.components.Viewport3D;
import limn.math.Vec3;
import limn.math.Vec4;
import limn.render3d.Light;
import limn.render3d.OrbitController;
import limn.render3d.gltf.GltfLoader;
import limn.render3d.gltf.GltfModel;
import limn.render3d.scene.LightNode;
import limn.render3d.scene.Scene3D;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;

/**
 * Loads a glTF 2.0 asset (embedded/base64, no AWT) and renders it as a retained
 * {@link Scene3D}: {@link GltfLoader} parses the node hierarchy + PBR materials +
 * textures on the CPU, {@link GltfModel#toScene3D()} uploads them, and the same
 * Phase 3 traversal/culling draws it. The bundled {@code toy.gltf} has three boxes
 * (gold metal, red dielectric, a base-color-textured one parented under the red
 * box so it inherits its rotation), plus a directional/point/spot rig added here.
 *
 * <p>It also shows the shape an application should copy for a model of real size: the read, the
 * parse and the texture decode all run on the worker pool, so the window lays out, paints and
 * responds to input the whole time, and only the GPU upload waits for a frame.
 */
final class GltfScene {

    private GltfScene() {
    }

    static Scene create(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    static Widget content() {
        Theme theme = Theme.current();
        Column col = new Column();
        col.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(new Label("3D viewport: glTF model").setFont(theme.title).setStrong(true));
        col.add(new Label("Loaded without AWT and off the UI thread: node hierarchy, PBR "
                + "metallic-roughness materials and a texture (PNG decoded via stb). The textured box "
                + "is a child of the red one, inheriting its rotation. Drag to orbit · scroll to "
                + "zoom.").setMuted(true).setWrap(true));
        col.add(viewport(640, 460));
        return new ScrollView(col);
    }

    /** What the background load hands to the renderer, one stage at a time. UI thread only. */
    private static final class Loading {
        GltfModel model;
        GltfModel.DecodedTextures textures;
        Scene3D scene;
    }

    /** A viewport hosting the bundled glTF model, reused by the kitchen "3D" showcase. */
    static Viewport3D viewport(float width, float height) {
        Loading loading = new Loading();

        Viewport3D viewport = new Viewport3D().setPreferredSize(width, height);
        viewport.setAnimated(false); // static model: repaint only on orbit/zoom
        viewport.camera().eye(new Vec3(0.5f, 2.2f, 9f)).target(new Vec3(0, 0, 0));
        viewport.setController(new OrbitController(viewport.camera()));
        viewport.setRenderer((pass, t) -> {
            // The uploads are the only part that needs the frame, so they happen here, once the
            // decode has landed. Before that the viewport simply clears. The window is live and
            // orbiting already works; there is just nothing to draw yet.
            if (loading.scene == null && loading.textures != null) {
                loading.scene = build(loading.model, loading.textures);
            }
            if (loading.scene == null) {
                return;
            }
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            loading.scene.render(pass, viewport.camera(), aspect);
        });

        // No deliverIf on either job: this runs while the viewport is still being built, so it has
        // no scene yet and a liveness predicate over its attachment would refuse the only delivery
        // there is. Both handlers write plain fields and ask for a repaint, which is safe detached.
        GltfLoader.fromResourceAsync("/limn/demo/models/toy.gltf")
                .onSuccess(model -> {
                    loading.model = model;
                    model.decodeTexturesAsync()
                            .onSuccess(textures -> {
                                loading.textures = textures;
                                viewport.invalidate();
                            })
                            .start();
                })
                .start();

        viewport.onDispose(() -> {
            if (loading.scene != null) {
                loading.scene.dispose();
                loading.scene = null; // the renderer above re-uploads lazily on re-attach
            }
        });
        return viewport;
    }

    private static Scene3D build(GltfModel model, GltfModel.DecodedTextures textures) {
        Scene3D built = model.toScene3D(textures)
                .background(new Vec4(0.05f, 0.055f, 0.07f, 1f))
                .ambient(new Vec3(0.05f, 0.055f, 0.07f))
                .exposure(1.1f);
        built.root().add(new LightNode(new Light.Directional(
                new Vec3(0.4f, 0.9f, 0.5f), new Vec3(1f, 0.96f, 0.9f), 2.6f)));
        built.root().add(new LightNode(new Light.Point(
                new Vec3(2.6f, 2.4f, 2.6f), new Vec3(0.5f, 0.7f, 1f), 16f, 16f)));
        built.root().add(new LightNode(new Light.Spot(
                new Vec3(-2.6f, 3.4f, 1.4f), new Vec3(0.1f, -1f, -0.25f),
                new Vec3(1f, 0.85f, 0.6f), 26f, 16f,
                (float) Math.toRadians(14), (float) Math.toRadians(28))));
        return built;
    }
}
