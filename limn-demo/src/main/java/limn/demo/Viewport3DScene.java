package limn.demo;

import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.ScrollView;
import limn.components.SegmentedControl;
import limn.components.Slider;
import limn.components.Theme;
import limn.components.Viewport3D;
import limn.graphics.BlendMode;
import limn.math.Mat4;
import limn.math.Quat;
import limn.math.Transform3D;
import limn.math.Vec3;
import limn.math.Vec4;
import limn.render3d.ColorSpace;
import limn.render3d.DebugDraw;
import limn.render3d.Environment;
import limn.render3d.Graphics3D;
import limn.render3d.GpuMesh;
import limn.render3d.GpuTexture;
import limn.render3d.Light;
import limn.render3d.Material;
import limn.render3d.MeshData;
import limn.render3d.MeshUsage;
import limn.render3d.OrbitController;
import limn.render3d.Pickable;
import limn.render3d.PickResult;
import limn.render3d.Picker;
import limn.render3d.Primitives;
import limn.render3d.Sampler;
import limn.render3d.TextureData;
import limn.render3d.VertexAttribute;
import limn.render3d.shader.Expr;
import limn.render3d.shader.ShaderType;
import limn.render3d.shader.SurfaceOutputs;
import limn.render3d.scene.LightNode;
import limn.render3d.scene.MeshNode;
import limn.render3d.scene.Scene3D;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;

import java.util.ArrayList;
import java.util.List;

/**
 * Exercises the Phase 3 retained scene graph: the Phase 2a PBR row (glossy
 * dielectric, gold and silver metal, a base-color-textured sphere over a checker
 * ground, under a directional + point + spot light) is now built as a
 * {@link Scene3D} of {@link MeshNode}s and {@link LightNode}s and drawn with
 * {@code scene.render(pass, camera, aspect)}: traversal + frustum culling emit
 * the same draws the imperative version did (declarative/imperative parity). Orbit
 * + CPU raycast picking (Phase 1) still apply, and it composites as one 2D layer,
 * so a 2D button sits on top and a {@link ScrollView} clips it.
 */
final class Viewport3DScene {

    private static final String HINT = "Drag to orbit · scroll to zoom · click to select";
    private static final Vec3 HIGHLIGHT = new Vec3(0.42f, 0.30f, 0.06f);

    // The row of spheres: x position, base material (texture applied on upload), tag.
    private static final float[] BALL_X = {-3f, -1f, 1f, 3f};
    private static final String[] BALL_TAG = {"red", "gold", "texture", "silver"};
    private static final int TEXTURED_BALL = 2;

    private Viewport3DScene() {
    }

    static Scene create(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Scene scene = new Scene(new Padding(Insets.all(20), content()));
        scene.setBackground(Theme.current().background);
        return scene;
    }

    /** Standalone scene: the retained PBR row plus the layering proof (button + ScrollView clip). */
    static Widget content() {
        Theme theme = Theme.current();
        Label status = new Label(HINT).setMuted(true);
        Viewport3D viewport = buildViewport(600, 460, status);
        viewport.setTooltip("3D viewport: retained Scene3D (PBR) in an FBO, composited as a layer");

        Column col = new Column();
        col.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(new Label("3D viewport: retained Scene3D (PBR)").setFont(theme.title).setStrong(true));
        col.add(new Label("Declarative scene graph: mesh + light nodes, traversal and frustum culling "
                + "emitting the same draws as the imperative version. Composited as a 2D layer; the "
                + "ScrollView clips it at the edge like any other widget.").setMuted(true).setWrap(true));
        col.add(viewport);
        col.add(status);
        for (int i = 1; i <= 5; i++) {
            col.add(new Label("Content below the viewport (" + i + ").").setMuted(true));
        }
        return new ScrollView(col);
    }

    /**
     * The two the kitchen sink's tab shows, in its picker's order.
     *
     * <p>Two, out of the ten viewports this class builds. The other eight each have a
     * {@code --scene} of their own ({@code gltf}, {@code ibl}, {@code debugdraw},
     * {@code blend3d}, {@code normalmap}, {@code bloom}, {@code surface}, and the cube
     * field via {@code --gadget}), where they get a whole window instead of the sliver a
     * tab body leaves them, which is the size at which a normal map or a bloom threshold
     * can actually be judged. A ten-segment picker inside a tab was ten ways to look at a
     * 200-point-tall render badly.
     *
     * <p>These two earn the place because between them they run the whole pipeline: PBR is
     * the lit forward pass with three light types, and shadows add the depth pass from the
     * light. Everything the others add is a variation on top of what these two already do.
     */
    private static final String[] SHOWCASE = {
            "PBR metallic-roughness under 3 lights (directional + point + spot); click to select.",
            "Shadow mapping: depth pass from the light + PCF; the objects cast shadows on the ground.",
    };

    /**
     * Kitchen-sink "3D" tab: the lit pass and the shadow pass, a
     * {@link SegmentedControl} between them; only the visible viewport renders (the
     * other pauses). See {@link #SHOWCASE} for why it is these two.
     */
    static Widget tabContent() {
        Theme theme = Theme.current();
        Label caption = new Label(SHOWCASE[0]).setMuted(true).setWrap(true);
        Viewport3D pbr = buildViewport(560, 320, caption);
        Widget shadows = shadowViewport(560, 320);
        Widget[] demos = {pbr, shadows};
        for (int i = 1; i < demos.length; i++) {
            demos[i].setVisible(false);
        }

        Stack viewers = new Stack();
        for (Widget demo : demos) {
            viewers.add(demo);
        }

        SegmentedControl picker = new SegmentedControl(List.of("PBR", "Shadows"));
        picker.onSelect(index -> {
            for (int i = 0; i < demos.length; i++) {
                demos[i].setVisible(i == index);
            }
            caption.setText(SHOWCASE[index]);
        });

        // Render scale on the PBR demo, beside the picker: the target is allocated at this
        // fraction of the viewport's device resolution and upsampled into the same box, so
        // the Textures figure in the perf footer falls with the square of the setting while
        // the widget's geometry does not move at all.
        float[] scales = {1f, 0.75f, 0.5f, 0.25f};
        ComboBox renderScale = new ComboBox(List.of("Render 100%", "Render 75%", "Render 50%",
                "Render 25%"));
        renderScale.onSelect(index -> pbr.setRenderScale(scales[index]));
        renderScale.setTooltip("Renders the 3D target below device resolution and upsamples it; "
                + "memory and fill cost fall with the square of the setting");

        // Beside the caption rather than the picker, which now has room to spare: the
        // caption is the wrapping widget on this panel, so the fixed-width control beside
        // it is the one that keeps its size when the window narrows.
        Row captionRow = new Row();
        captionRow.gap(10).crossAlignment(Flex.CrossAlignment.CENTER);
        captionRow.add(Expanded.of(caption, 1));
        captionRow.add(renderScale);

        Column col = new Column();
        col.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(new Label("3D showcase: subsystem capabilities").setFont(theme.title).setStrong(true));
        col.add(picker);
        col.add(captionRow);
        // Flexed, not fixed: the demos declare 560×320 but the kitchen tab body
        // is much shorter: measured unbounded they would keep their full height
        // and the tab clip would leave only the top sliver of the 3D render
        // visible (the scene's empty sky, which reads as a solid dark rectangle).
        // The leftover tab height bounds the Stack instead; Viewport3D.onMeasure
        // clamps to it and the whole (letterboxed) scene stays visible.
        col.add(Expanded.of(viewers));
        return col;
    }

    /**
     * Transparency: an opaque scene, a translucent pane in front of it and an
     * additive glow. Blended surfaces are drawn after every opaque one, test depth
     * but do not write it, and never reach the shadow depth pass: the pane throws
     * no shadow, and the sphere behind it stays visible through it.
     */
    static Viewport3D blendViewport(float width, float height) {
        MeshData sphereData = Primitives.sphere(0.9f, 40, 60);
        MeshData paneData = Primitives.plane(3.2f, 3.2f);
        MeshData glowData = Primitives.sphere(0.75f, 24, 32);
        MeshData groundData = Primitives.plane(9, 9);
        Scene3D[] scene = {null};

        Viewport3D viewport = new Viewport3D().setPreferredSize(width, height);
        viewport.camera().eye(new Vec3(0.6f, 2.4f, 7.4f)).target(new Vec3(0, -0.2f, 0));
        viewport.setController(new OrbitController(viewport.camera()));
        viewport.setRenderer((pass, t) -> {
            if (scene[0] == null) {
                Scene3D built = new Scene3D()
                        .background(new Vec4(0.05f, 0.06f, 0.08f, 1f))
                        .ambient(new Vec3(0.05f, 0.055f, 0.07f))
                        .exposure(1.1f)
                        .castShadows(true);
                built.root().add(new MeshNode(Graphics3D.upload(groundData),
                        Material.Pbr.of(0.58f, 0.60f, 0.64f).roughness(0.85f))
                        .transform(Transform3D.at(new Vec3(0, -1.1f, 0))));
                built.root().add(new MeshNode(Graphics3D.upload(sphereData),
                        Material.Pbr.of(0.85f, 0.32f, 0.24f).roughness(0.35f))
                        .transform(Transform3D.at(new Vec3(-1.3f, -0.2f, -0.4f))));

                // Alpha: a pane of tinted glass. Its alpha lives in baseColor.w.
                built.root().add(new MeshNode(Graphics3D.upload(paneData),
                        new Material.Pbr(new Vec4(0.45f, 0.75f, 0.95f, 0.35f), 0f, 0.1f,
                                Vec3.ZERO, null, null, BlendMode.NORMAL))
                        .transform(new Transform3D(new Vec3(-0.2f, 0.1f, 1.4f),
                                Quat.fromAxisAngle(Vec3.UNIT_X, (float) Math.PI / 2), Vec3.ONE)));

                // Additive: a self-lit glow that brightens whatever it overlaps and
                // occludes nothing. Emissive, so it reads at full strength unlit.
                // Half the intensity you would expect: the pass does not cull faces
                // and an additive surface writes no depth, so the sphere's far side
                // adds through its near side and every pixel is covered twice.
                built.root().add(new MeshNode(Graphics3D.upload(glowData),
                        Material.Pbr.of(0, 0, 0).emissive(new Vec3(0.34f, 0.15f, 0.05f))
                                .blend(BlendMode.ADDITIVE))
                        .transform(Transform3D.at(new Vec3(1.7f, 0.35f, 0.6f))));

                built.root().add(new LightNode(new Light.Directional(
                        new Vec3(0.5f, 1.1f, 0.45f), new Vec3(1f, 0.97f, 0.9f), 3.0f)));
                built.root().add(new LightNode(new Light.Point(
                        new Vec3(-2.5f, 1.8f, 2.5f), new Vec3(0.4f, 0.6f, 1f), 8f, 12f)));
                scene[0] = built;
            }
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            scene[0].render(pass, viewport.camera(), aspect);
        });
        viewport.setAnimated(false); // nothing moves; repaint on orbit/zoom only
        viewport.onDispose(() -> {
            if (scene[0] != null) {
                scene[0].dispose();
                scene[0] = null;
            }
        });
        return viewport;
    }

    /**
     * Normal mapping: the same flat plane twice, once shaded by its geometry and
     * once by a tangent-space map. Nothing about the geometry differs (both are
     * two triangles), so every bump is the shading normal doing the work.
     */
    static Viewport3D normalMapViewport(float width, float height) {
        MeshData panelData = Primitives.plane(3.1f, 3.1f);
        TextureData bumps = bumpNormalMap(256, 4);
        Scene3D[] scene = {null};

        Viewport3D viewport = new Viewport3D().setPreferredSize(width, height);
        viewport.camera().eye(new Vec3(0, 5.2f, 4.6f)).target(new Vec3(0, 0, -0.1f));
        viewport.setController(new OrbitController(viewport.camera()));
        viewport.setRenderer((pass, t) -> {
            if (scene[0] == null) {
                GpuTexture normals = Graphics3D.uploadTexture(bumps, Sampler.smooth());
                Material.Pbr base = Material.Pbr.of(0.72f, 0.70f, 0.66f).roughness(0.42f);
                Scene3D built = new Scene3D()
                        .background(new Vec4(0.05f, 0.06f, 0.08f, 1f))
                        .ambient(new Vec3(0.04f, 0.045f, 0.06f))
                        .exposure(1.1f);
                built.root().add(new MeshNode(Graphics3D.upload(panelData), base)
                        .transform(Transform3D.at(new Vec3(-1.75f, 0, 0))));
                built.root().add(new MeshNode(Graphics3D.upload(panelData),
                        base.normalMapped(Material.NormalMap.of(normals)))
                        .transform(Transform3D.at(new Vec3(1.75f, 0, 0))));
                built.owns(normals);
                built.root().add(new LightNode(new Light.Directional(
                        new Vec3(0.55f, 0.75f, 0.35f), new Vec3(1f, 0.96f, 0.88f), 2.6f)));
                scene[0] = built;
            }
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            scene[0].render(pass, viewport.camera(), aspect);
        });
        viewport.setAnimated(false);
        viewport.onDispose(() -> {
            if (scene[0] != null) {
                scene[0].dispose();
                scene[0] = null;
            }
        });
        return viewport;
    }

    /**
     * A tiling grid of hemispherical bumps as a tangent-space normal map. Generated
     * rather than shipped, so the demo carries no binary asset.
     *
     * <p>Sampled with mips ({@link Sampler#smooth()}), which is the right call for a
     * tiling pattern at a grazing angle but is not free of sin for a normal map:
     * mips are built in the stored (encoded) space and averaged normals are not
     * renormalized, so the surface flattens with distance rather than staying bumpy.
     */
    private static TextureData bumpNormalMap(int size, int cells) {
        byte[] rgba = new byte[size * size * 4];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float u = (x + 0.5f) / size * cells;
                float v = (y + 0.5f) / size * cells;
                float dx = (u - (float) Math.floor(u)) * 2 - 1;
                // Negated because rows run DOWN an image while green points toward its
                // TOP; see Material.NormalMap. Without it these bumps read as dents,
                // which is a surprisingly easy thing to look at and not notice.
                float dy = -((v - (float) Math.floor(v)) * 2 - 1);
                float r2 = dx * dx + dy * dy;
                boolean insideDome = r2 < 1f;
                int i = (y * size + x) * 4;
                rgba[i] = encodeNormal(insideDome ? dx : 0);
                rgba[i + 1] = encodeNormal(insideDome ? dy : 0);
                rgba[i + 2] = encodeNormal(insideDome ? (float) Math.sqrt(1 - r2) : 1);
                rgba[i + 3] = (byte) 0xFF;
            }
        }
        return TextureData.normalMap(size, size, rgba);
    }

    /** One component of a unit normal, [-1,1] → an unsigned byte. */
    private static byte encodeNormal(float component) {
        return (byte) Math.round((component * 0.5f + 0.5f) * 255f);
    }

    /** Standalone normal-mapping scene (also a section of the showcase). */
    static Scene normalMapScene(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Theme theme = Theme.current();
        Column col = new Column();
        col.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(new Label("3D viewport: Normal mapping").setFont(theme.title).setStrong(true));
        col.add(new Label("Two identical flat planes under one directional light. The right one "
                + "carries a tangent-space normal map; its tangent frame is derived per pixel from "
                + "screen-space derivatives, so no mesh tangents are needed. Drag to orbit.")
                .setMuted(true).setWrap(true));
        col.add(normalMapViewport(700, 500));
        Scene scene = new Scene(new Padding(Insets.all(20), new ScrollView(col)));
        scene.setBackground(theme.background);
        return scene;
    }

    /** Standalone transparency scene (also a section of the showcase). */
    static Scene blendScene(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Theme theme = Theme.current();
        Column col = new Column();
        col.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(new Label("3D viewport: Transparency").setFont(theme.title).setStrong(true));
        col.add(new Label("A translucent pane (BlendMode.NORMAL) and an additive glow "
                + "(BlendMode.ADDITIVE) over an opaque, shadow-casting scene. Blended surfaces "
                + "draw after the opaque ones, test depth without writing it, and cast no "
                + "shadow. Drag to orbit.").setMuted(true).setWrap(true));
        col.add(blendViewport(700, 500));
        Scene scene = new Scene(new Padding(Insets.all(20), new ScrollView(col)));
        scene.setBackground(theme.background);
        return scene;
    }


    // ------------------------------------------- application-supplied surfaces

    /**
     * A surface the application wrote itself, on geometry it rewrites every frame:
     * the two capabilities that only make sense together, so they are demonstrated
     * together.
     *
     * <p>What the material does is not expressible by {@link Material.Pbr}: it samples
     * one sprite sheet at <em>two</em> cells and cross-fades them by a per-vertex
     * weight, then erodes the result through a mask with a glowing edge. What matters
     * for the toolkit is what it still gets for free: it is lit by the pass's lights,
     * it takes the shadow map and the environment, and it compiles to every target
     * profile, because it is the same neutral IR the built-in material is written in.
     * {@link Material.Raw} would have none of that.
     *
     * <p>The plane is a {@link MeshUsage#DYNAMIC} grid whose positions are rewritten
     * into the same buffer every frame, and whose per-vertex {@code PARAMS.x} carries
     * the cross-fade weight, so the wipe travels across the surface rather than
     * happening to all of it at once.
     */
    static Widget customSurfaceViewport(float width, float height) {
        int cells = 24;
        int side = cells + 1;
        float[] positions = new float[side * side * 3];
        float[] normals = new float[side * side * 3];
        float[] params = new float[side * side * 4];
        float[] params1 = new float[side * side * 4];
        MeshData grid = surfaceGrid(cells, positions, normals, params, params1);
        GpuMesh[] mesh = {null};
        GpuTexture[] sheet = {null};
        GpuTexture[] mask = {null};
        Scene3D[] scene = {null};
        MeshNode[] node = {null};
        float[] dissolve = {0f};
        float[] glow = {0f};

        Viewport3D viewport = new Viewport3D().setPreferredSize(width, height);
        viewport.camera().eye(new Vec3(0f, 2.6f, 5.2f)).target(new Vec3(0, 0, 0));
        viewport.setController(new OrbitController(viewport.camera()));
        viewport.setRenderer((pass, t) -> {
            if (scene[0] == null) {
                mesh[0] = Graphics3D.upload(grid, MeshUsage.DYNAMIC);
                // Clamped and unmipped: REPEAT would wrap one cell of the sheet into the
                // edge of its neighbour, and a mip would average whole cells together.
                Sampler sampler = new Sampler(Sampler.Filter.LINEAR, Sampler.Filter.LINEAR,
                        Sampler.Wrap.CLAMP_TO_EDGE, Sampler.Wrap.CLAMP_TO_EDGE, false);
                sheet[0] = Graphics3D.uploadTexture(spriteSheet(256), sampler);
                mask[0] = Graphics3D.uploadTexture(erosionMask(128), sampler);
                Scene3D built = new Scene3D()
                        .background(new Vec4(0.05f, 0.06f, 0.08f, 1f))
                        .ambient(new Vec3(0.04f, 0.045f, 0.06f))
                        .castShadows(false);
                node[0] = new MeshNode(mesh[0], surfaceMaterial(sheet[0], mask[0], 0f, 0f));
                built.root().add(node[0]);
                built.root().add(new LightNode(new Light.Directional(
                        new Vec3(0.4f, 1.0f, 0.6f), new Vec3(1f, 0.96f, 0.9f), 3.2f)));
                built.root().add(new LightNode(new Light.Point(
                        new Vec3(-2.2f, 1.4f, 2.2f), new Vec3(0.35f, 0.6f, 1f), 10f, 10f)));
                scene[0] = built;
            }
            // Rewritten in place: the same buffer, new numbers, no allocation and no
            // new GpuMesh. bounds() measures the live prefix, so counts() must be set
            // by whoever writes fewer vertices than the capacity; this one fills it.
            rippleGrid(positions, normals, params, params1, cells, 3.2f, (float) t);
            mesh[0].update(grid);
            node[0].material(surfaceMaterial(sheet[0], mask[0], dissolve[0], glow[0]));

            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            scene[0].render(pass, viewport.camera(), aspect);
        });
        viewport.setAnimated(true);
        viewport.onDispose(() -> {
            if (scene[0] != null) {
                scene[0].dispose();
                scene[0] = null;
            }
        });

        Slider erosion = new Slider(0f, 1f);
        Label readout = new Label("0.00");
        erosion.onChange(value -> {
            dissolve[0] = value;
            readout.setText(String.format(java.util.Locale.ROOT, "%.2f", value));
        });

        Column col = new Column();
        col.gap(10).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(viewport);
        col.add(bloomSliderRow("Erosion (a vec4 uniform the surface declared)", erosion, readout));

        // The per-light hook, given a control of its own because it is a different kind
        // of thing from every other slider on this screen: the others feed expressions
        // evaluated once a fragment, this one feeds the expression the engine evaluates
        // once per light. Drag it up and the far side of the ripple lights from the lamp
        // behind it, which no combination of the outputs above can produce.
        Slider translucency = new Slider(0f, 1.5f);
        Label glowReadout = new Label("0.00");
        translucency.onChange(value -> {
            glow[0] = value;
            glowReadout.setText(String.format(java.util.Locale.ROOT, "%.2f", value));
        });
        col.add(bloomSliderRow("Translucency (a diffuse response, evaluated per light)",
                translucency, glowReadout));
        return col;
    }

    /**
     * The application's own surface, in the neutral IR.
     *
     * <p>Everything below is ordinary shader arithmetic; the point is where it lives.
     * Two samples of one sheet mixed by a per-vertex weight, an erosion band through
     * alpha, and the band's complement added to emissive so the edge glows, and the
     * engine still supplies the BRDF, the lights and the shadow map underneath it.
     *
     * <p>The key is a constant, not the record's identity: this method runs every
     * frame and the program must be compiled once.
     */
    private static Material.Surface surfaceMaterial(GpuTexture sheet, GpuTexture mask,
                                                    float threshold, float translucency) {
        Expr.Ref sheetTex = new Expr.Ref("u_sheet", ShaderType.SAMPLER2D);
        Expr.Ref maskTex = new Expr.Ref("u_mask", ShaderType.SAMPLER2D);
        Expr.Ref knobs = new Expr.Ref("u_erosion", ShaderType.VEC4);
        Expr.Ref uv = new Expr.Ref("v_uv", ShaderType.VEC2);
        Expr.Ref uv1 = new Expr.Ref("v_uv1", ShaderType.VEC2);
        Expr.Ref params = new Expr.Ref("v_params", ShaderType.VEC4);
        // The second custom stream. A real application fills v_params before reaching
        // for this one (two half-used streams cost bandwidth one full one does not),
        // and the demo deliberately does not, because the thing worth demonstrating is
        // that BOTH reach the surface in one draw. Two attribute buffers bound at once
        // is exactly the GL state that breaks silently and shades plausibly.
        Expr.Ref params1 = new Expr.Ref("v_params1", ShaderType.VEC4);

        // Cell A and cell B of the sheet, cross-faded by the per-vertex weight.
        Expr blended = new Expr.Call("mix", ShaderType.VEC4, List.of(
                new Expr.Sample(sheetTex, uv),
                new Expr.Sample(sheetTex, uv1),
                new Expr.Swizzle(params, "x")));
        Expr albedo = new Expr.Call("srgbToLinear", ShaderType.VEC3,
                List.of(new Expr.Swizzle(blended, "rgb")));

        // The erosion band: 0 where the mask is below the threshold, ramping to 1 over
        // the edge width. smoothstep is inside the portable subset.
        Expr maskValue = new Expr.Swizzle(new Expr.Sample(maskTex, uv), "r");
        Expr band = new Expr.Call("smoothstep", ShaderType.FLOAT, List.of(
                new Expr.Swizzle(knobs, "x"),
                new Expr.Binary(Expr.Op.ADD, new Expr.Swizzle(knobs, "x"),
                        new Expr.Swizzle(knobs, "y")),
                maskValue));
        Expr edge = new Expr.Binary(Expr.Op.SUB, Expr.Lit.of(1f), band);

        // The burning edge's colour, warped per vertex by the second stream. Written as
        // ONE PLUS the channel rather than as the channel itself, so a mesh that does
        // not carry PARAMS1 (reading the context's zeroes) gets the plain edge colour
        // back. That is the "zero is the identity" rule the attribute asks for, spelled
        // out: a surface whose identity is some other value renders wrongly for every
        // mesh that declines the attribute, and nothing says why.
        Expr edgeTint = new Expr.Binary(Expr.Op.ADD, Expr.Lit.of(1f, 1f, 1f),
                new Expr.Swizzle(params1, "xyz"));

        // --- the per-light hook: translucency -----------------------------------
        //
        // The one output evaluated INSIDE the light loop. Everything above runs once a
        // fragment and cannot see a light at all; this runs once per light and is handed
        // L, so it can ask where the light is rather than only how much of it lands.
        //
        // A leaf, a paper lantern or a curtain is lit from behind as well as in front.
        // worldToTangent(L).z is negative exactly when the light is on the far side of
        // the surface, so max(-z, 0) is "how far behind", and adding it to the term the
        // engine would have used lets the sheet glow through without touching the
        // highlight: specular keeps NdotL, because a mirror lobe does not transmit.
        //
        // At u_erosion.z = 0 this is NdotL back again, exactly: x + y * 0.0 is x for any
        // finite y. That matters more than it looks: an application that opts into this
        // hook has left the engine's factored accumulate behind for good, so the way to
        // keep "off" meaning what it always meant is arithmetic that delivers it.
        Expr behind = new Expr.Call("max", ShaderType.FLOAT, List.of(
                new Expr.Binary(Expr.Op.SUB, Expr.Lit.of(0f), new Expr.Swizzle(
                        new Expr.Call("worldToTangent", ShaderType.VEC3,
                                List.of(new Expr.Ref("L", ShaderType.VEC3))), "z")),
                Expr.Lit.of(0f)));
        Expr response = new Expr.Binary(Expr.Op.ADD, new Expr.Ref("NdotL", ShaderType.FLOAT),
                new Expr.Binary(Expr.Op.MUL, behind, new Expr.Swizzle(knobs, "z")));

        return new Material.Surface("demo.dissolve",
                new SurfaceOutputs(
                        new Expr.Construct(ShaderType.VEC4, List.of(albedo, band)),
                        Expr.Lit.of(0f),
                        Expr.Lit.of(0.55f),
                        new Expr.Binary(Expr.Op.MUL,
                                new Expr.Binary(Expr.Op.MUL, Expr.Lit.of(2.4f, 0.8f, 0.2f),
                                        edgeTint),
                                edge),
                        null,
                        response),
                List.of(new Material.Surface.Texture("u_sheet", sheet),
                        new Material.Surface.Texture("u_mask", mask)),
                List.of(new Material.Surface.Value("u_erosion",
                        new Vec4(threshold, 0.12f, translucency, 0))),
                BlendMode.NORMAL);
    }

    /**
     * A grid carrying the two extra per-vertex channels: UV1 points at the next cell of
     * the sheet, PARAMS.x is the cross-fade weight. Positions are filled by
     * {@link #rippleGrid}.
     */
    private static MeshData surfaceGrid(int cells, float[] positions, float[] normals,
                                        float[] params, float[] params1) {
        int side = cells + 1;
        float[] uv0 = new float[side * side * 2];
        float[] uv1 = new float[side * side * 2];
        int[] indices = new int[cells * cells * 6];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int v = y * side + x;
                normals[v * 3 + 1] = 1f;
                // The sheet is 2x2; cell A is the top-left, cell B the top-right, so
                // the demo cross-fades between two halves of one texture.
                float u = (float) x / cells;
                float w = (float) y / cells;
                uv0[v * 2] = u * 0.5f;
                uv0[v * 2 + 1] = w * 0.5f;
                uv1[v * 2] = 0.5f + u * 0.5f;
                uv1[v * 2 + 1] = w * 0.5f;
            }
        }
        int t = 0;
        for (int y = 0; y < cells; y++) {
            for (int x = 0; x < cells; x++) {
                int a = y * side + x;
                indices[t++] = a;
                indices[t++] = a + side;
                indices[t++] = a + side + 1;
                indices[t++] = a;
                indices[t++] = a + side + 1;
                indices[t++] = a + 1;
            }
        }
        return new MeshData()
                .put(VertexAttribute.POSITION, positions)
                .put(VertexAttribute.NORMAL, normals)
                .put(VertexAttribute.UV0, uv0)
                .put(VertexAttribute.UV1, uv1)
                .put(VertexAttribute.PARAMS, params)
                .put(VertexAttribute.PARAMS1, params1)
                .indices(indices);
    }

    /**
     * Rewrites the grid's positions, normals and cross-fade weight for time {@code t}.
     *
     * <p>Into the caller's own arrays, which are the same ones the {@link MeshData}
     * holds; that is what makes this allocation-free, and it is the whole point of a
     * dynamic mesh: same buffer, new numbers, one upload.
     */
    private static void rippleGrid(float[] positions, float[] normals, float[] params,
                                   float[] params1, int cells, float size, float t) {
        int side = cells + 1;
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int v = y * side + x;
                float px = ((float) x / cells - 0.5f) * size;
                float pz = ((float) y / cells - 0.5f) * size;
                float r = (float) Math.sqrt(px * px + pz * pz);
                float height = 0.18f * (float) Math.sin(r * 3.4f - t * 2.2f);
                positions[v * 3] = px;
                positions[v * 3 + 1] = height;
                positions[v * 3 + 2] = pz;
                // The analytic normal of that ripple, so the lighting follows the shape.
                float slope = r > 1e-4f ? 0.18f * 3.4f * (float) Math.cos(r * 3.4f - t * 2.2f) : 0f;
                float nx = r > 1e-4f ? -slope * px / r : 0f;
                float nz = r > 1e-4f ? -slope * pz / r : 0f;
                float length = (float) Math.sqrt(nx * nx + 1f + nz * nz);
                normals[v * 3] = nx / length;
                normals[v * 3 + 1] = 1f / length;
                normals[v * 3 + 2] = nz / length;
                // A wipe travelling across the surface rather than a global fade, which
                // is the whole reason the weight is per-vertex and not a uniform.
                float phase = (float) x / cells;
                params[v * 4] = clamp01(0.5f + 0.5f * (float) Math.sin(t * 0.9f - phase * 3.1f));
                // The second stream: the burning edge runs from red at one corner to
                // white at the other. Purely to have both custom streams live in one
                // draw, which is the state that breaks (see surfaceMaterial).
                float across = (float) y / cells;
                params1[v * 4] = 0f;
                params1[v * 4 + 1] = -0.75f * across;
                params1[v * 4 + 2] = -0.9f * across;
            }
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    /** A 2×2 sheet of four flat colours, enough to see which cell is being sampled. */
    private static TextureData spriteSheet(int size) {
        byte[] rgba = new byte[size * size * 4];
        int[][] colors = {{230, 90, 60}, {70, 150, 235}, {240, 200, 80}, {110, 210, 140}};
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int cell = (y < size / 2 ? 0 : 2) + (x < size / 2 ? 0 : 1);
                // A ring inside each cell, so the cross-fade is visible as shape and not
                // only as hue.
                float cx = (x % (size / 2)) / (float) (size / 2) - 0.5f;
                float cy = (y % (size / 2)) / (float) (size / 2) - 0.5f;
                float r = (float) Math.sqrt(cx * cx + cy * cy);
                float ring = r > 0.18f && r < 0.34f ? 0.35f : 1f;
                int i = (y * size + x) * 4;
                rgba[i] = (byte) Math.round(colors[cell][0] * ring);
                rgba[i + 1] = (byte) Math.round(colors[cell][1] * ring);
                rgba[i + 2] = (byte) Math.round(colors[cell][2] * ring);
                rgba[i + 3] = (byte) 0xFF;
            }
        }
        return TextureData.rgba(size, size, rgba, ColorSpace.SRGB);
    }

    /**
     * The erosion mask: value noise in the red channel. Data, not colour; the surface
     * above samples it without an sRGB decode, and decoding it would shift every
     * threshold and read as the slider being mistuned.
     */
    private static TextureData erosionMask(int size) {
        byte[] rgba = new byte[size * size * 4];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float v = 0f;
                float amplitude = 0.5f;
                for (int octave = 0; octave < 4; octave++) {
                    int step = size >> (2 + octave);
                    v += amplitude * maskNoise(x, y, step, octave);
                    amplitude *= 0.5f;
                }
                byte level = (byte) Math.round(clamp01(v) * 255f);
                int i = (y * size + x) * 4;
                rgba[i] = level;
                rgba[i + 1] = level;
                rgba[i + 2] = level;
                rgba[i + 3] = (byte) 0xFF;
            }
        }
        return new TextureData(size, size, rgba, ColorSpace.LINEAR);
    }

    private static float maskNoise(int x, int y, int step, int salt) {
        if (step < 1) {
            step = 1;
        }
        int gx = x / step;
        int gy = y / step;
        float fx = smoothFraction((x % step) / (float) step);
        float fy = smoothFraction((y % step) / (float) step);
        float a = maskHash(gx, gy, salt);
        float b = maskHash(gx + 1, gy, salt);
        float c = maskHash(gx, gy + 1, salt);
        float d = maskHash(gx + 1, gy + 1, salt);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
    }

    private static float smoothFraction(float t) {
        return t * t * (3f - 2f * t);
    }

    private static float maskHash(int x, int y, int salt) {
        int h = x * 0x27D4EB2D ^ y * 0x165667B1 ^ salt * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 13;
        return (h >>> 8) * 0x1.0p-24f;
    }

    /** Standalone application-surface scene (also a section of the showcase). */
    static Scene customSurfaceScene(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Theme theme = Theme.current();
        Column col = new Column();
        col.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(new Label("3D viewport: Application surface + dynamic mesh")
                .setFont(theme.title).setStrong(true));
        col.add(new Label("Material.Surface: the application supplies the four surface "
                + "expressions in the toolkit's own shader IR, and the engine still lights them. "
                + "This one cross-fades two cells of a sprite sheet by a per-vertex weight (UV1 + "
                + "PARAMS) and erodes the result through a mask whose glowing edge is tinted per "
                + "vertex by the second custom stream (PARAMS1). The plane "
                + "underneath is a MeshUsage.DYNAMIC grid rewritten every frame. Drag to orbit.")
                .setMuted(true).setWrap(true));
        col.add(customSurfaceViewport(700, 460));
        Scene scene = new Scene(new Padding(Insets.all(20), new ScrollView(col)));
        scene.setBackground(theme.background);
        return scene;
    }

    /** Standalone shadow-mapping scene (also a section of the showcase). */
    static Scene shadowScene(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Theme theme = Theme.current();
        Column col = new Column();
        col.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(new Label("3D viewport: Shadow mapping").setFont(theme.title).setStrong(true));
        col.add(new Label("Depth pass from the directional light + 3×3 PCF. The objects spin, casting "
                + "dynamic shadows on the ground. Drag to orbit.").setMuted(true).setWrap(true));
        col.add(shadowViewport(700, 500));
        Scene scene = new Scene(new Padding(Insets.all(20), new ScrollView(col)));
        scene.setBackground(theme.background);
        return scene;
    }

    /** Standalone debug-draw scene (overlay starts on, for headless verification). */
    static Scene debugDrawScene(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Theme theme = Theme.current();
        Column col = new Column();
        col.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(new Label("3D viewport: immediate-mode debug draw").setFont(theme.title).setStrong(true));
        col.add(new Label("Grid and world-space culling AABBs are depth-tested (occluded by geometry); "
                + "the oriented bounds draw as X-ray overlay. Debug lines never cast shadows. "
                + "Drag to orbit.").setMuted(true).setWrap(true));
        col.add(debugViewport(700, 460, true, false));
        Scene scene = new Scene(new Padding(Insets.all(20), new ScrollView(col)));
        scene.setBackground(theme.background);
        return scene;
    }

    private static final Vec4 DEBUG_GRID = new Vec4(0.45f, 0.48f, 0.55f, 1f);
    private static final Vec4 DEBUG_BOUNDS = new Vec4(1f, 0.85f, 0.2f, 1f);
    private static final Vec4 DEBUG_XRAY = new Vec4(1f, 0.3f, 0.85f, 1f);

    /**
     * The shadow demo's spinning scene plus a {@link DebugDraw} overlay: grid
     * and axes at the origin, every mesh's world AABB (all depth-tested), and
     * the cube's oriented bounds as X-ray. The switch toggles emission live.
     *
     * @param fillHeight {@code true} inside the (height-bounded) kitchen tab:
     *                   the viewport flexes to the leftover height. {@code false}
     *                   for the scrollable standalone scene: an {@code Expanded}
     *                   measured with unbounded height collapses to zero there.
     */
    static Widget debugViewport(float width, float height, boolean startOn, boolean fillHeight) {
        MeshData sphereData = Primitives.sphere(0.85f, 40, 60);
        MeshData cubeData = Primitives.cube(1.4f);
        MeshData groundData = Primitives.plane(8, 8);
        Scene3D[] scene = {null};
        GpuMesh[] cubeMesh = {null};
        MeshNode[] spin = new MeshNode[2];
        Vec3 spherePos = new Vec3(-1.6f, 0, 0);
        Vec3 cubePos = new Vec3(1.5f, -0.3f, 0.3f);
        boolean[] debugOn = {startOn};
        DebugDraw debug = new DebugDraw();

        Viewport3D viewport = new Viewport3D().setPreferredSize(width, height);
        viewport.camera().eye(new Vec3(0.2f, 3.0f, 8.2f)).target(new Vec3(0, -0.25f, 0));
        viewport.setController(new OrbitController(viewport.camera()));
        viewport.setRenderer((pass, t) -> {
            if (scene[0] == null) {
                GpuMesh sphere = Graphics3D.upload(sphereData);
                GpuMesh cube = Graphics3D.upload(cubeData);
                GpuMesh ground = Graphics3D.upload(groundData);
                cubeMesh[0] = cube;
                Scene3D built = new Scene3D()
                        .background(new Vec4(0.06f, 0.07f, 0.09f, 1f))
                        .ambient(new Vec3(0.06f, 0.065f, 0.08f))
                        .exposure(1.1f)
                        .castShadows(true);
                built.root().add(new MeshNode(ground, Material.Pbr.of(0.60f, 0.62f, 0.66f).roughness(0.8f))
                        .transform(Transform3D.at(new Vec3(0, -1f, 0))));
                spin[0] = built.root().add(new MeshNode(sphere,
                        Material.Pbr.of(0.85f, 0.32f, 0.24f).roughness(0.35f)));
                spin[1] = built.root().add(new MeshNode(cube,
                        Material.Pbr.of(0.35f, 0.55f, 0.9f).metallic(0.2f).roughness(0.3f)));
                built.root().add(new LightNode(new Light.Directional(
                        new Vec3(0.5f, 1.1f, 0.45f), new Vec3(1f, 0.97f, 0.9f), 3.0f)));
                scene[0] = built;
            }
            Transform3D cubeSpin = new Transform3D(cubePos,
                    Quat.fromAxisAngle(Vec3.UNIT_Y, (float) t * 0.4f + 0.6f), Vec3.ONE);
            spin[0].transform(new Transform3D(spherePos,
                    Quat.fromAxisAngle(Vec3.UNIT_Y, (float) t * 0.5f), Vec3.ONE));
            spin[1].transform(cubeSpin);
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            scene[0].render(pass, viewport.camera(), aspect);
            if (debugOn[0]) {
                debug.grid(4f, 16, DEBUG_GRID);
                debug.axes(Mat4.identity(), 1.6f);
                scene[0].debugBounds(debug, DEBUG_BOUNDS);
                // The cube's local bounds under its world transform: an oriented
                // box, drawn on top of everything (X-ray bucket).
                debug.depthTest(false).obb(cubeMesh[0].bounds(), cubeSpin.toMatrix(), DEBUG_XRAY);
                debug.flush(pass);
            }
        });
        viewport.onDispose(() -> {
            if (scene[0] != null) {
                scene[0].dispose();
                scene[0] = null; // the renderer above rebuilds lazily on re-attach
            }
        });

        Checkbox toggle = new Checkbox(Checkbox.Variant.SWITCH, "Debug overlay").setChecked(startOn);
        toggle.setTooltip("Grid + culling AABBs (depth-tested) and oriented bounds (X-ray)");
        toggle.onChange(on -> debugOn[0] = on);

        Column col = new Column();
        col.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(fillHeight ? Expanded.of(viewport) : viewport);
        col.add(toggle);
        return col;
    }

    /** A viewport with a ground and two spinning objects casting shadows (Phase 5a). */
    static Viewport3D shadowViewport(float width, float height) {
        MeshData sphereData = Primitives.sphere(0.85f, 40, 60);
        MeshData cubeData = Primitives.cube(1.4f);
        MeshData groundData = Primitives.plane(8, 8);
        Scene3D[] scene = {null};
        MeshNode[] spin = new MeshNode[2];
        Vec3 spherePos = new Vec3(-1.6f, 0, 0);
        Vec3 cubePos = new Vec3(1.5f, -0.3f, 0.3f);

        Viewport3D viewport = new Viewport3D().setPreferredSize(width, height);
        viewport.camera().eye(new Vec3(0.2f, 3.0f, 8.2f)).target(new Vec3(0, -0.25f, 0));
        viewport.setController(new OrbitController(viewport.camera()));
        viewport.setRenderer((pass, t) -> {
            if (scene[0] == null) {
                GpuMesh sphere = Graphics3D.upload(sphereData);
                GpuMesh cube = Graphics3D.upload(cubeData);
                GpuMesh ground = Graphics3D.upload(groundData);
                Scene3D built = new Scene3D()
                        .background(new Vec4(0.06f, 0.07f, 0.09f, 1f))
                        .ambient(new Vec3(0.06f, 0.065f, 0.08f))
                        .exposure(1.1f)
                        .castShadows(true);
                built.root().add(new MeshNode(ground, Material.Pbr.of(0.60f, 0.62f, 0.66f).roughness(0.8f))
                        .transform(Transform3D.at(new Vec3(0, -1f, 0))));
                spin[0] = built.root().add(new MeshNode(sphere,
                        Material.Pbr.of(0.85f, 0.32f, 0.24f).roughness(0.35f)));
                spin[1] = built.root().add(new MeshNode(cube,
                        Material.Pbr.of(0.35f, 0.55f, 0.9f).metallic(0.2f).roughness(0.3f)));
                built.root().add(new LightNode(new Light.Directional(
                        new Vec3(0.5f, 1.1f, 0.45f), new Vec3(1f, 0.97f, 0.9f), 3.0f)));
                built.root().add(new LightNode(new Light.Point(
                        new Vec3(-2.5f, 1.8f, 2.5f), new Vec3(0.4f, 0.6f, 1f), 8f, 12f)));
                scene[0] = built;
            }
            spin[0].transform(new Transform3D(spherePos,
                    Quat.fromAxisAngle(Vec3.UNIT_Y, (float) t * 0.5f), Vec3.ONE));
            spin[1].transform(new Transform3D(cubePos,
                    Quat.fromAxisAngle(Vec3.UNIT_Y, (float) t * 0.4f + 0.6f), Vec3.ONE));
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            scene[0].render(pass, viewport.camera(), aspect);
        });
        viewport.onDispose(() -> {
            if (scene[0] != null) {
                scene[0].dispose();
                scene[0] = null; // the renderer above rebuilds lazily on re-attach
            }
        });
        return viewport;
    }

    /** A viewport hosting a retained {@link Scene3D} of the PBR row, orbit controls and click-to-pick. */
    private static Viewport3D buildViewport(float width, float height, Label status) {
        MeshData sphereData = Primitives.sphere(0.9f, 48, 72);
        MeshData groundData = Primitives.plane(10, 10);
        Transform3D groundTransform = Transform3D.at(new Vec3(0, -1f, 0));
        Mat4 groundMatrix = groundTransform.toMatrix();
        TextureData groundChecker = checker(256, 8, 62, 66, 74, 158, 162, 172);
        TextureData ballChecker = checker(256, 6, 244, 246, 250, 46, 132, 222);

        Material.Pbr[] baseMats = {
                Material.Pbr.of(0.85f, 0.13f, 0.16f).roughness(0.18f),               // glossy red dielectric
                Material.Pbr.of(1.0f, 0.78f, 0.34f).metallic(1f).roughness(0.26f),   // gold metal
                Material.Pbr.of(1f, 1f, 1f).metallic(0.1f).roughness(0.45f),         // textured (white × tex)
                Material.Pbr.of(0.95f, 0.96f, 1.0f).metallic(1f).roughness(0.55f),   // brushed silver
        };
        Material.Pbr groundBase = Material.Pbr.of(1f, 1f, 1f).roughness(0.9f);

        String[] selected = {null};
        Scene3D[] scene = {null};
        MeshNode[] balls = new MeshNode[BALL_X.length];
        MeshNode[] ground = {null};
        Material.Pbr[] restingBall = new Material.Pbr[BALL_X.length];
        Material.Pbr[] restingGround = {null};

        Viewport3D viewport = new Viewport3D().setPreferredSize(width, height);
        viewport.camera().eye(new Vec3(0.3f, 2.1f, 10.2f)).target(new Vec3(0, -0.15f, 0));
        viewport.setController(new OrbitController(viewport.camera()));

        Runnable applySelection = () -> {
            if (scene[0] == null) {
                return;
            }
            ground[0].material("ground".equals(selected[0])
                    ? restingGround[0].emissive(HIGHLIGHT) : restingGround[0]);
            for (int i = 0; i < balls.length; i++) {
                balls[i].material(BALL_TAG[i].equals(selected[0])
                        ? restingBall[i].emissive(HIGHLIGHT) : restingBall[i]);
            }
        };

        viewport.setRenderer((pass, t) -> {
            if (scene[0] == null) {
                GpuMesh sphereMesh = Graphics3D.upload(sphereData);
                GpuMesh groundMesh = Graphics3D.upload(groundData);
                GpuTexture groundTex = Graphics3D.uploadTexture(groundChecker, Sampler.smooth());
                GpuTexture ballTex = Graphics3D.uploadTexture(ballChecker, Sampler.smooth());
                restingGround[0] = groundBase.textured(groundTex);
                for (int i = 0; i < balls.length; i++) {
                    restingBall[i] = i == TEXTURED_BALL ? baseMats[i].textured(ballTex) : baseMats[i];
                }

                Scene3D built = new Scene3D()
                        .background(new Vec4(0.05f, 0.055f, 0.07f, 1f))
                        .ambient(new Vec3(0.05f, 0.055f, 0.07f))
                        .exposure(1.1f);
                ground[0] = built.root().add(
                        new MeshNode(groundMesh, restingGround[0]).transform(groundTransform));
                for (int i = 0; i < balls.length; i++) {
                    balls[i] = built.root().add(new MeshNode(sphereMesh, restingBall[i])
                            .transform(Transform3D.at(new Vec3(BALL_X[i], 0, 0))));
                }
                built.root().add(new LightNode(new Light.Directional(
                        new Vec3(0.4f, 0.9f, 0.5f), new Vec3(1f, 0.96f, 0.9f), 2.6f)));
                built.root().add(new LightNode(new Light.Point(
                        new Vec3(2.6f, 2.2f, 2.4f), new Vec3(0.45f, 0.65f, 1f), 14f, 14f)));
                built.root().add(new LightNode(new Light.Spot(
                        new Vec3(-2.6f, 3.2f, 1.2f), new Vec3(0.1f, -1f, -0.25f),
                        new Vec3(1f, 0.85f, 0.6f), 26f, 16f,
                        (float) Math.toRadians(13), (float) Math.toRadians(26))));
                scene[0] = built;
                applySelection.run();
            }

            Quat spin = Quat.fromAxisAngle(Vec3.UNIT_Y, (float) t * 0.3f);
            for (int i = 0; i < balls.length; i++) {
                balls[i].transform(new Transform3D(new Vec3(BALL_X[i], 0, 0), spin, Vec3.ONE));
            }
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            scene[0].render(pass, viewport.camera(), aspect);
        });
        viewport.onClick(ray -> {
            List<Pickable> pickables = new ArrayList<>();
            pickables.add(new Pickable(groundData, groundMatrix, "ground"));
            for (int i = 0; i < BALL_X.length; i++) {
                pickables.add(new Pickable(sphereData,
                        Mat4.translation(new Vec3(BALL_X[i], 0, 0)), BALL_TAG[i]));
            }
            PickResult hit = Picker.pick(ray, pickables);
            selected[0] = hit != null ? (String) hit.tag() : null;
            status.setText(hit != null
                    ? "Selected: " + hit.tag()
                    : "Nothing selected. Click an object.");
            applySelection.run();
            viewport.invalidate();
        });
        viewport.onDispose(() -> {
            if (scene[0] != null) {
                scene[0].dispose();
                scene[0] = null; // the renderer above rebuilds lazily on re-attach
            }
        });
        return viewport;
    }

    /**
     * Bloom's test bench (ADR 005): emissive spheres rising across the
     * threshold (the dimmest must NOT glow) over an opaque ground AND a
     * transparent background, so the glow's alpha handling shows against the
     * UI behind the viewport (finding 3: it must neither vanish at the edge
     * of the drawn content nor darken the interface). {@code params} is
     * {threshold, intensity, radius}, read every frame so controls can move it.
     */
    static Viewport3D bloomViewport(float width, float height, float[] params) {
        MeshData sphereData = Primitives.sphere(0.8f, 40, 60);
        MeshData groundData = Primitives.plane(9, 9);
        Scene3D[] scene = {null};

        Viewport3D viewport = new Viewport3D().setPreferredSize(width, height);
        viewport.camera().eye(new Vec3(0.4f, 2.2f, 8.6f)).target(new Vec3(0, -0.1f, 0));
        viewport.setController(new OrbitController(viewport.camera()));
        viewport.setRenderer((pass, t) -> {
            if (scene[0] == null) {
                GpuMesh sphere = Graphics3D.upload(sphereData);
                Scene3D built = new Scene3D()
                        .background(new Vec4(0, 0, 0, 0)) // the UI shows through, the glow must too
                        .ambient(new Vec3(0.04f, 0.045f, 0.06f))
                        .exposure(1.0f);
                built.root().add(new MeshNode(Graphics3D.upload(groundData),
                        Material.Pbr.of(0.34f, 0.36f, 0.40f).roughness(0.85f))
                        .transform(Transform3D.at(new Vec3(0, -1f, 0))));
                // Emission rising left→right across threshold 1: at the default
                // the first sphere stays quiet and the rest glow increasingly.
                float[] xs = {-2.7f, -0.9f, 0.9f, 2.7f};
                float[] strength = {0.55f, 1.6f, 3.5f, 8f};
                Vec3[] tint = {
                        new Vec3(1f, 0.62f, 0.32f),   // ember orange
                        new Vec3(0.45f, 0.75f, 1f),   // cool blue
                        new Vec3(1f, 0.35f, 0.55f),   // magenta
                        new Vec3(0.55f, 1f, 0.6f),    // green
                };
                for (int i = 0; i < xs.length; i++) {
                    built.root().add(new MeshNode(sphere,
                            Material.Pbr.of(0.07f, 0.07f, 0.09f).roughness(0.5f)
                                    .emissive(tint[i].mul(strength[i])))
                            .transform(Transform3D.at(new Vec3(xs[i], 0, 0))));
                }
                built.root().add(new LightNode(new Light.Directional(
                        new Vec3(0.45f, 1f, 0.5f), new Vec3(1f, 0.97f, 0.9f), 1.6f)));
                scene[0] = built;
            }
            pass.bloom(params[0], params[1], params[2]);
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            scene[0].render(pass, viewport.camera(), aspect);
        });
        viewport.setAnimated(false); // static emitters: repaint on orbit/zoom/control change
        viewport.onDispose(() -> {
            if (scene[0] != null) {
                scene[0].dispose();
                scene[0] = null; // the renderer above rebuilds lazily on re-attach
            }
        });
        return viewport;
    }

    /**
     * The bloom viewport plus its live controls (ADR 005 §2.4): a threshold
     * and an intensity the user can move; a bloom only ever exercised at its
     * default of "off" is not exercised at all. The radius stays at 6 pt (it
     * had its own eyeball pass in step 3).
     *
     * @param fillHeight like {@link #debugViewport}: {@code true} flexes the
     *                   viewport to the height-bounded kitchen tab, {@code false}
     *                   keeps its preferred size in a scrollable scene.
     */
    static Widget bloomDemo(float width, float height, boolean fillHeight) {
        float[] params = {1f, 0.6f, 6f};
        Viewport3D viewport = bloomViewport(width, height, params);

        Label thresholdValue = new Label("1.00").setMuted(true);
        Slider threshold = new Slider(0f, 3f).setStep(0.05f).setValue(params[0]);
        threshold.onChange(v -> {
            params[0] = v;
            thresholdValue.setText(String.format(java.util.Locale.ROOT, "%.2f", v));
            viewport.invalidate(); // static scene: repaint to apply
        });
        Label intensityValue = new Label("0.60").setMuted(true);
        Slider intensity = new Slider(0f, 1.5f).setStep(0.05f).setValue(params[1]);
        intensity.onChange(v -> {
            params[1] = v;
            intensityValue.setText(String.format(java.util.Locale.ROOT, "%.2f", v));
            viewport.invalidate();
        });

        Column col = new Column();
        col.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(fillHeight ? Expanded.of(viewport) : viewport);
        col.add(bloomSliderRow("Threshold (linear light; 1 = fully-lit white)",
                threshold, thresholdValue));
        col.add(bloomSliderRow("Intensity (0 = off; the pass costs nothing)",
                intensity, intensityValue));
        return col;
    }

    private static Widget bloomSliderRow(String caption, Slider slider, Label value) {
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(new SizedBox(280, SizedBox.UNSET, new Label(caption).setMuted(true)));
        row.add(Expanded.of(slider, 1));
        row.add(new SizedBox(44, SizedBox.UNSET, value));
        return row;
    }

    /** Standalone bloom scene (ADR 005): the glow over both opaque and transparent ground truth. */
    static Scene bloomScene(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Theme theme = Theme.current();
        Column col = new Column();
        col.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(new Label("3D viewport: Bloom").setFont(theme.title).setStrong(true));
        col.add(new Label("Emissive spheres brightening left to right across the threshold: the "
                + "dimmest stays quiet, the others glow. The viewport background is transparent, so "
                + "the glow also has to composite over the UI without a halo of darkness or a hard "
                + "cut at the content's edge. Drag to orbit; the sliders are live.")
                .setMuted(true).setWrap(true));
        col.add(bloomDemo(700, 480, false));
        Scene scene = new Scene(new Padding(Insets.all(20), new ScrollView(col)));
        scene.setBackground(theme.background);
        return scene;
    }

    /** Standalone image-based-lighting scene (also a section of the showcase). */
    static Scene iblScene(boolean light) {
        Theme.setCurrent(light ? Theme.light() : Theme.dark());
        Theme theme = Theme.current();
        Column col = new Column();
        col.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        col.add(new Label("3D viewport: Image-based lighting").setFont(theme.title).setStrong(true));
        col.add(new Label("Procedural environment: diffuse irradiance via spherical harmonics + specular "
                + "sky reflection (sharper on smoother surfaces) + skybox. Metallic spheres with "
                + "roughness increasing from left to right. Drag to orbit.")
                .setMuted(true).setWrap(true));
        col.add(iblViewport(700, 500));
        Scene scene = new Scene(new Padding(Insets.all(20), new ScrollView(col)));
        scene.setBackground(theme.background);
        return scene;
    }

    /** A row of metallic spheres (roughness 0→1) lit by an image-based environment (Phase 5b). */
    static Viewport3D iblViewport(float width, float height) {
        MeshData sphereData = Primitives.sphere(0.75f, 48, 72);
        float[] xs = {-3.2f, -1.6f, 0f, 1.6f, 3.2f};
        float[] roughness = {0.05f, 0.22f, 0.42f, 0.65f, 0.9f};
        Scene3D[] scene = {null};

        Viewport3D viewport = new Viewport3D().setPreferredSize(width, height);
        viewport.setAnimated(false); // static spheres: repaint only on orbit/zoom
        viewport.camera().eye(new Vec3(0, 0.4f, 8.5f)).target(new Vec3(0, 0, 0));
        viewport.setController(new OrbitController(viewport.camera()));
        viewport.setRenderer((pass, t) -> {
            if (scene[0] == null) {
                GpuMesh sphere = Graphics3D.upload(sphereData);
                Scene3D built = new Scene3D()
                        .background(new Vec4(0f, 0f, 0f, 1f)) // clears depth; the skybox paints the color
                        .exposure(1.0f)
                        .environment(Environment.gradient(
                                new Vec3(0.22f, 0.36f, 0.68f),  // deep blue zenith
                                new Vec3(0.62f, 0.68f, 0.78f),  // bright horizon
                                new Vec3(0.10f, 0.09f, 0.08f))  // dark ground
                                .intensity(1.0f));
                for (int i = 0; i < xs.length; i++) {
                    built.root().add(new MeshNode(sphere,
                            Material.Pbr.of(1.0f, 0.82f, 0.42f).metallic(1f).roughness(roughness[i]))
                            .transform(Transform3D.at(new Vec3(xs[i], 0, 0))));
                }
                built.root().add(new LightNode(new Light.Directional(
                        new Vec3(0.35f, 0.7f, 0.6f), new Vec3(1f, 0.98f, 0.92f), 2.2f)));
                scene[0] = built;
            }
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            scene[0].render(pass, viewport.camera(), aspect);
        });
        viewport.onDispose(() -> {
            if (scene[0] != null) {
                scene[0].dispose();
                scene[0] = null; // the renderer above rebuilds lazily on re-attach
            }
        });
        return viewport;
    }

    /** A square sRGB checker of two colors, a base-color map for the ground/spheres. */
    private static TextureData checker(int size, int cells,
                                       int ar, int ag, int ab, int br, int bg, int bb) {
        byte[] px = new byte[size * size * 4];
        int cell = Math.max(1, size / cells);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean a = ((x / cell) + (y / cell)) % 2 == 0;
                int i = (y * size + x) * 4;
                px[i] = (byte) (a ? ar : br);
                px[i + 1] = (byte) (a ? ag : bg);
                px[i + 2] = (byte) (a ? ab : bb);
                px[i + 3] = (byte) 255;
            }
        }
        return new TextureData(size, size, px, ColorSpace.SRGB);
    }
}
