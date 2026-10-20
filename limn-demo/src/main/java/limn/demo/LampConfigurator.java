package limn.demo;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.animation.Easing;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.components.Checkbox;
import limn.components.DisplayMode;
import limn.components.Label;
import limn.components.Popover;
import limn.components.SegmentedControl;
import limn.components.Separator;
import limn.components.Theme;
import limn.components.Viewport3D;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.i18n.I18nString;
import limn.i18n.NumberFormats;
import limn.input.Keys;
import limn.math.Mat4;
import limn.math.Quat;
import limn.math.Transform3D;
import limn.math.Vec3;
import limn.math.Vec4;
import limn.render3d.Environment;
import limn.render3d.Graphics3D;
import limn.render3d.GpuMesh;
import limn.render3d.Light;
import limn.render3d.Material;
import limn.render3d.MeshData;
import limn.render3d.OrbitController;
import limn.render3d.PickResult;
import limn.render3d.Pickable;
import limn.render3d.Picker;
import limn.render3d.Primitives;
import limn.render3d.VertexAttribute;
import limn.render3d.scene.LightNode;
import limn.render3d.scene.MeshNode;
import limn.render3d.scene.Scene3D;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.IntConsumer;

/**
 * A product page of the kind a business application has: a desk lamp built in code from its
 * parts, in a 3D view that orbits, with the choices that price it beside it.
 *
 * <p>The colour and the finish change the lamp's body, and the change is a short blend from the
 * old material to the new rather than a cut, which the system's wish for less motion turns back
 * into a cut. A click on a part picks it on the CPU, against the same geometry the GPU draws, and
 * a popover at the click names the part and its price; the price list under the choices says the
 * same for the reader who does not point, and the total follows every choice.
 *
 * <p>Everything here is public API: the lamp is a {@link Scene3D} of {@link MeshNode}s whose
 * meshes are turned from profiles ({@link #lathe}), lit by a directional light that casts the
 * shadows on the floor, the environment's sky, and, when it is on, a spot light in the shade.
 */
final class LampConfigurator {

    /** What the configurator says, ready for translation. */
    static final class Text {
        static final I18nString CONFIGURATOR = new I18nString("showcase.lamp.configurator", "Configurator");
        static final I18nString HINT = new I18nString("showcase.lamp.hint", "Drag to orbit · click a part");
        static final I18nString PRODUCT = new I18nString("showcase.lamp.product", "Desk lamp");
        static final I18nString COLOUR = new I18nString("showcase.lamp.colour", "Colour");
        static final I18nString GRAPHITE = new I18nString("showcase.lamp.graphite", "Graphite");
        static final I18nString IVORY = new I18nString("showcase.lamp.ivory", "Ivory");
        static final I18nString SAGE = new I18nString("showcase.lamp.sage", "Sage");
        static final I18nString TERRACOTTA = new I18nString("showcase.lamp.terracotta", "Terracotta");
        static final I18nString FINISH = new I18nString("showcase.lamp.finish", "Finish");
        static final I18nString MATTE = new I18nString("showcase.lamp.matte", "Matte");
        static final I18nString GLOSSY = new I18nString("showcase.lamp.glossy", "Glossy");
        static final I18nString METAL = new I18nString("showcase.lamp.metal", "Metal");
        static final I18nString LIGHT = new I18nString("showcase.lamp.light", "Light on");
        static final I18nString BASE = new I18nString("showcase.lamp.base", "Base");
        static final I18nString ARM = new I18nString("showcase.lamp.arm", "Arm");
        static final I18nString JOINTS = new I18nString("showcase.lamp.joints", "Joints");
        static final I18nString SHADE = new I18nString("showcase.lamp.shade", "Shade");
        static final I18nString BULB = new I18nString("showcase.lamp.bulb", "Bulb");
        static final I18nString TOTAL = new I18nString("showcase.lamp.total", "Total");

        private Text() {
        }
    }

    /** The lamp's parts, what each is called, and its price in the matte finish. */
    enum Part {
        BASE(Text.BASE, 64, true),
        ARM(Text.ARM, 52, true),
        JOINTS(Text.JOINTS, 18, false),
        SHADE(Text.SHADE, 46, true),
        BULB(Text.BULB, 12, false);

        final I18nString name;
        final double price;
        /** Whether the part is in the body's colour and finish, and priced by the finish. */
        final boolean body;

        Part(I18nString name, double price, boolean body) {
            this.name = name;
            this.price = price;
            this.body = body;
        }
    }

    /** The body's finishes: how rough, how metallic, and what each adds to a body part's price. */
    enum Finish {
        MATTE(Text.MATTE, 0.72f, 0f, 1.00),
        GLOSSY(Text.GLOSSY, 0.16f, 0f, 1.10),
        METAL(Text.METAL, 0.3f, 1f, 1.30);

        final I18nString name;
        final float roughness;
        final float metallic;
        final double factor;

        Finish(I18nString name, float roughness, float metallic, double factor) {
            this.name = name;
            this.roughness = roughness;
            this.metallic = metallic;
            this.factor = factor;
        }
    }

    /** The body's colours, by name and sRGB value, in the swatches' order. */
    static final I18nString[] COLOUR_NAMES = {Text.GRAPHITE, Text.IVORY, Text.SAGE, Text.TERRACOTTA};
    private static final int[] COLOURS = {0x34363B, 0xE6DECB, 0x8EA584, 0xC0663E};

    private static final DoubleFunction<String> DOLLARS = NumberFormats.currency(Currency.getInstance("USD"));

    /** A studio's light: a pale sky over a warm floor, which the metal finish mirrors. */
    private static final Environment STUDIO = Environment.gradient(
            new Vec3(0.62f, 0.66f, 0.72f),
            new Vec3(0.86f, 0.84f, 0.80f),
            new Vec3(0.30f, 0.28f, 0.26f)).intensity(0.55f);

    private static final Vec3 PICKED_GLOW = new Vec3(0.10f, 0.08f, 0.03f);
    private static final double BLEND_SECONDS = 0.35;

    private LampConfigurator() {
    }

    // ------------------------------------------------------------------ the choice

    /** The lamp as configured. Holds no GPU state, so a test reads it as the page does. */
    static final class Choice {
        int colour = 2;
        Finish finish = Finish.MATTE;
        boolean lightOn = true;

        /** @return what {@code part} costs in the chosen finish, to the cent */
        double price(Part part) {
            double price = part.body ? part.price * finish.factor : part.price;
            return Math.round(price * 100) / 100.0;
        }

        /** @return the lamp's price: every part, in the chosen finish */
        double total() {
            double total = 0;
            for (Part part : Part.values()) {
                total += price(part);
            }
            return Math.round(total * 100) / 100.0;
        }

        /** @return the body's material for the chosen colour and finish */
        Material.Pbr body() {
            int rgb = COLOURS[colour];
            return Material.Pbr.of(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f)
                    .metallic(finish.metallic).roughness(finish.roughness);
        }
    }

    /** {@code a} to {@code b} by {@code t}: colour, metal and roughness, each in a straight line. */
    static Material.Pbr blend(Material.Pbr a, Material.Pbr b, float t) {
        if (t <= 0) {
            return a;
        }
        if (t >= 1) {
            return b;
        }
        Vec4 ca = a.baseColor();
        Vec4 cb = b.baseColor();
        Vec4 colour = new Vec4(ca.x() + (cb.x() - ca.x()) * t, ca.y() + (cb.y() - ca.y()) * t,
                ca.z() + (cb.z() - ca.z()) * t, 1f);
        return b.baseColor(colour)
                .metallic(a.metallic() + (b.metallic() - a.metallic()) * t)
                .roughness(a.roughness() + (b.roughness() - a.roughness()) * t);
    }

    // ------------------------------------------------------------------- the page

    /** What the page is made of, and what a test reads of it. */
    static final class Built {
        final Widget<?> widget;
        final Viewport3D view;
        final Choice choice;
        final Popover popover;
        private final Label popoverName;
        private final Label popoverPrice;
        private final Transition change;
        private Material.Pbr from;
        private Material.Pbr to;
        @Nullable Part picked;
        /** A point on the front of each part, in world space, by the parts' order. */
        Vec3[] fronts = new Vec3[0];

        private Built(Widget<?> widget, Viewport3D view, Choice choice, Popover popover, Label name,
                      Label price, Transition change) {
            this.widget = widget;
            this.view = view;
            this.choice = choice;
            this.popover = popover;
            this.popoverName = name;
            this.popoverPrice = price;
            this.change = change;
            this.from = choice.body();
            this.to = from;
        }

        /** @return the body's material on the frame being drawn: on its way to the choice */
        Material.Pbr bodyNow() {
            return blend(from, to, change.value());
        }

        /** Starts the body on its way from what it shows now to the choice. */
        void restyle() {
            Material.Pbr shown = bodyNow();
            from = shown;
            to = choice.body();
            change.snap(0f);
            change.to(1f);
        }

        /** @return where the front of {@code part} is on the view, in the view's own points */
        float[] onView(Part part) {
            float aspect = view.height() > 0 ? view.width() / view.height() : 1f;
            Vec3 ndc = view.camera().viewProjection(aspect).transformPoint(fronts[part.ordinal()]);
            return new float[]{(ndc.x() * 0.5f + 0.5f) * view.width(), (0.5f - ndc.y() * 0.5f) * view.height()};
        }

        /** @return what the popover says: the part's name, then its price */
        List<String> popoverText() {
            return List.of(popoverName.text(), popoverPrice.text());
        }
    }

    /** The configurator for the showcase's 3D page. */
    static Built build() {
        Choice choice = new Choice();

        // ------------------------------------------------------------ the lamp's geometry
        MeshData baseData = lathe(new float[]{0, 0, 1.0f, 0, 1.0f, 0, 1.0f, 0.12f, 0.97f, 0.19f,
                0.90f, 0.22f, 0.90f, 0.22f, 0, 0.22f}, 64);
        MeshData armData = lathe(new float[]{0, 0, 0.065f, 0, 0.065f, 0, 0.065f, 1f, 0.065f, 1f, 0, 1f}, 24);
        MeshData shadeData = lathe(new float[]{0, -0.05f, 0.68f, -0.92f, 0.68f, -0.92f, 0.73f, -0.92f,
                0.73f, -0.92f, 0.18f, -0.04f, 0.10f, 0.05f, 0, 0.07f}, 64);
        MeshData jointData = Primitives.sphere(0.13f, 20, 28);
        MeshData bulbData = Primitives.sphere(0.17f, 24, 32);
        // A round plinth rather than a floor to the horizon: the edge of a product shot's stage is
        // meant to be seen, and a plane wide enough to hide its own would spread the shadow map,
        // which is fitted to the whole scene, over metres nobody looks at.
        MeshData floorData = lathe(new float[]{0, -0.1f, 3.8f, -0.1f, 3.8f, -0.1f, 3.8f, -0.02f,
                3.76f, 0f, 3.76f, 0f, 0, 0f}, 96);

        // Where the parts stand: a base on the floor, a lower arm leaning back from a hinge on
        // it, an upper arm reaching forward from the elbow, and the shade hanging from its end,
        // its opening turned down toward the floor in front of the base.
        Vec3 hinge = new Vec3(0, 0.27f, 0);
        Quat lowerTilt = Quat.fromAxisAngle(Vec3.UNIT_Z, 0.32f);
        float lowerLength = 2.2f;
        Vec3 elbow = hinge.add(Mat4.rotation(lowerTilt).transformDirection(new Vec3(0, lowerLength, 0)));
        Quat upperTilt = Quat.fromAxisAngle(Vec3.UNIT_Z, -1.12f);
        float upperLength = 1.9f;
        Vec3 wrist = elbow.add(Mat4.rotation(upperTilt).transformDirection(new Vec3(0, upperLength, 0)));
        Quat shadeTilt = Quat.fromAxisAngle(Vec3.UNIT_Z, 0.3f);
        Vec3 shadeAxis = Mat4.rotation(shadeTilt).transformDirection(new Vec3(0, -1, 0));
        Vec3 bulbAt = wrist.add(shadeAxis.mul(0.4f));

        Mat4 baseAt = Mat4.identity();
        Mat4 lowerAt = Mat4.trs(hinge, lowerTilt, new Vec3(1, lowerLength, 1));
        Mat4 upperAt = Mat4.trs(elbow, upperTilt, new Vec3(1, upperLength, 1));
        Mat4 shadeAt = Mat4.trs(wrist, shadeTilt, Vec3.ONE);
        Mat4 bulbMatrix = Mat4.translation(bulbAt);
        Vec3[] joints = {hinge, elbow, wrist};

        // What a click can pick, in the same places the GPU draws them.
        List<Pickable> pickables = new ArrayList<>();
        pickables.add(new Pickable(baseData, baseAt, Part.BASE));
        pickables.add(new Pickable(armData, lowerAt, Part.ARM));
        pickables.add(new Pickable(armData, upperAt, Part.ARM));
        for (Vec3 joint : joints) {
            pickables.add(new Pickable(jointData, Mat4.translation(joint), Part.JOINTS));
        }
        pickables.add(new Pickable(shadeData, shadeAt, Part.SHADE));
        pickables.add(new Pickable(bulbData, bulbMatrix, Part.BULB));

        // -------------------------------------------------------------------- the view
        Viewport3D viewport = new Viewport3D().setPreferredSize(560, 420);
        viewport.camera().eye(new Vec3(3.7f, 3.3f, 7.7f)).target(new Vec3(0.6f, 1.5f, 0));
        viewport.setController(new OrbitController(viewport.camera()).radiusLimits(4f, 16f));
        viewport.setAnimated(false); // still: it paints when orbited, picked or restyled
        viewport.setAccessibleName(Text.PRODUCT);

        Transition change = new Transition(viewport, 1f).duration(BLEND_SECONDS).easing(Easing.EASE_OUT);
        Transition light = new Transition(viewport, 1f).duration(BLEND_SECONDS).easing(Easing.EASE_OUT);

        Label popoverName = new Label("").setStrong(true);
        Label popoverPrice = new Label("");
        Column popoverBody = new Column();
        popoverBody.gap(4).crossAlignment(Flex.CrossAlignment.START);
        popoverBody.add(popoverName);
        popoverBody.add(popoverPrice);
        // In the window's own scene: the panel belongs to the view it points into, and is placed
        // in the frame's layout, where the point it hangs from has already moved to the click.
        Popover popover = new Popover(popoverBody).setDisplayMode(DisplayMode.IN_SCENE);

        Pinned pinned = new Pinned(viewport);
        Controls controls = new Controls(choice);
        Row body = new Row();
        body.gap(24).crossAlignment(Flex.CrossAlignment.START);
        body.add(Expanded.of(Page.aspect(4f / 3f, pinned), 1));
        body.add(new SizedBox(260, SizedBox.UNSET, controls.panel));
        Built built = new Built(body, viewport, choice, popover, popoverName, popoverPrice, change);
        popover.onClose(origin -> {
            built.picked = null;
            viewport.invalidate();
        });

        Scene3D[] scene = {null};
        MeshNode[] bodyNodes = new MeshNode[4];
        MeshNode[] jointNodes = new MeshNode[joints.length];
        MeshNode[] bulb = {null};
        Material.Pbr joint = Material.Pbr.of(0.78f, 0.78f, 0.80f).metallic(1f).roughness(0.32f);
        Material.Pbr bulbOff = Material.Pbr.of(0.94f, 0.93f, 0.90f).roughness(0.12f);
        Vec3 bulbGlow = new Vec3(7f, 5.4f, 3.4f);
        viewport.setRenderer((pass, t) -> {
            if (scene[0] == null) {
                GpuMesh armMesh = Graphics3D.upload(armData);
                GpuMesh jointMesh = Graphics3D.upload(jointData);
                Scene3D lamp = new Scene3D()
                        .background(new Vec4(0f, 0f, 0f, 1f)) // clears depth; the sky paints the colour
                        .ambient(new Vec3(0.05f, 0.05f, 0.055f))
                        .exposure(1.05f)
                        .environment(STUDIO)
                        .castShadows(true);
                lamp.root().add(new MeshNode(Graphics3D.upload(floorData),
                        Material.Pbr.of(0.50f, 0.49f, 0.47f).roughness(0.78f)));
                bodyNodes[0] = lamp.root().add(new MeshNode(Graphics3D.upload(baseData), built.bodyNow()));
                bodyNodes[1] = lamp.root().add(new MeshNode(armMesh, built.bodyNow())
                        .transform(new Transform3D(hinge, lowerTilt, new Vec3(1, lowerLength, 1))));
                bodyNodes[2] = lamp.root().add(new MeshNode(armMesh, built.bodyNow())
                        .transform(new Transform3D(elbow, upperTilt, new Vec3(1, upperLength, 1))));
                bodyNodes[3] = lamp.root().add(new MeshNode(Graphics3D.upload(shadeData), built.bodyNow())
                        .transform(new Transform3D(wrist, shadeTilt, Vec3.ONE)));
                for (int i = 0; i < joints.length; i++) {
                    jointNodes[i] = lamp.root().add(new MeshNode(jointMesh, joint).transform(Transform3D.at(joints[i])));
                }
                bulb[0] = lamp.root().add(new MeshNode(Graphics3D.upload(bulbData), bulbOff)
                        .transform(Transform3D.at(bulbAt)));
                lamp.root().add(new LightNode(new Light.Directional(
                        new Vec3(-0.45f, 1.15f, 0.65f), new Vec3(1f, 0.97f, 0.92f), 2.4f)));
                scene[0] = lamp;
            }
            Material.Pbr now = built.bodyNow();
            Part picked = built.picked;
            bodyNodes[0].material(picked == Part.BASE ? now.emissive(PICKED_GLOW) : now);
            bodyNodes[1].material(picked == Part.ARM ? now.emissive(PICKED_GLOW) : now);
            bodyNodes[2].material(picked == Part.ARM ? now.emissive(PICKED_GLOW) : now);
            bodyNodes[3].material(picked == Part.SHADE ? now.emissive(PICKED_GLOW) : now);
            for (MeshNode each : jointNodes) {
                each.material(picked == Part.JOINTS ? joint.emissive(PICKED_GLOW) : joint);
            }
            float on = light.value();
            Vec3 glow = bulbGlow.mul(on);
            bulb[0].material(picked == Part.BULB ? bulbOff.emissive(glow.add(PICKED_GLOW)) : bulbOff.emissive(glow));
            if (on > 0) {
                // The lamp's own light, before the scene's: a warm cone out of the shade.
                pass.addLight(new Light.Spot(bulbAt, shadeAxis, new Vec3(1f, 0.84f, 0.62f), 34f * on, 9f,
                        (float) Math.toRadians(28), (float) Math.toRadians(44)));
                pass.bloom(1.2f, 0.5f * on, 6f);
            }
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            scene[0].render(pass, viewport.camera(), aspect);
        });
        viewport.observeDispose(() -> {
            if (scene[0] != null) {
                scene[0].dispose();
                scene[0] = null; // the renderer above rebuilds lazily on re-attach
            }
        });

        java.util.function.Consumer<limn.math.Ray> pick = ray -> {
            PickResult hit = Picker.pick(ray, pickables);
            popover.close();
            if (hit == null) {
                return;
            }
            Part part = (Part) hit.tag();
            built.picked = part;
            // Where the part was hit, on the view: the point projected back through the camera.
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            Vec3 ndc = viewport.camera().viewProjection(aspect).transformPoint(hit.point());
            pinned.pin((ndc.x() * 0.5f + 0.5f) * viewport.width(), (0.5f - ndc.y() * 0.5f) * viewport.height());
            popoverName.setText(part.name);
            popoverPrice.setText(DOLLARS.apply(choice.price(part)));
            popover.setAccessibleName(part.name);
            popover.showFor(pinned.mark);
            viewport.invalidate();
        };
        viewport.onClick(pick);
        // A point on the front of each part, in the parts' order, for whoever picks without a
        // pointer: the lab switch below, and the tests.
        built.fronts = new Vec3[]{new Vec3(0.6f, 0.15f, 0.5f), hinge.add(elbow).mul(0.5f), elbow,
                wrist.add(shadeAxis.mul(0.55f)).add(new Vec3(0, 0, 0.5f)), bulbAt};
        // A lab switch for a film of the page: -Dlimn.demo.lamp.pick=Shade picks that part, by its
        // English name, as a click on the front of it would, once the page has been on screen.
        String lab = System.getProperty("limn.demo.lamp.pick");
        for (Part part : Part.values()) {
            if (part.name.english().equalsIgnoreCase(String.valueOf(lab))) {
                limn.concurrent.Ui.postDelayed(() -> {
                    float[] at = built.onView(part);
                    pick.accept(viewport.rayAt(at[0], at[1]));
                }, 2500);
            }
        }

        // ------------------------------------------------------------------ the choices
        controls.swatches.onChoose(index -> {
            choice.colour = index;
            controls.colourName.setText(COLOUR_NAMES[index]);
            built.restyle();
        });
        controls.finish.onSelect(index -> {
            choice.finish = Finish.values()[index];
            built.restyle();
            controls.prices.run();
        });
        controls.lightOn.onChange(on -> {
            choice.lightOn = on;
            light.to(on ? 1f : 0f);
        });
        return built;
    }

    /**
     * The panel beside the view: the colour, the finish and the light, then the price of every
     * part and the total, which a click on the lamp says of one part and this says of all of them,
     * for the reader who does not point.
     */
    private static final class Controls {
        final Column panel = Page.column();
        final Swatches swatches;
        final Label colourName;
        final SegmentedControl finish = new SegmentedControl(Text.MATTE, Text.GLOSSY, Text.METAL);
        final Checkbox lightOn = new Checkbox(Checkbox.Variant.SWITCH, Text.LIGHT);
        final Runnable prices;

        Controls(Choice choice) {
            panel.add(new Label(Text.PRODUCT).setStrong(true));

            swatches = new Swatches(COLOUR_NAMES, COLOURS, choice.colour);
            swatches.describedAs(Text.COLOUR);
            colourName = new Label(COLOUR_NAMES[choice.colour]);
            panel.add(Page.row(8, Expanded.of(new Label(Text.COLOUR).setMuted(true), 1), colourName));
            panel.add(swatches.row());

            finish.setSelectedIndex(choice.finish.ordinal());
            finish.setAccessibleName(Text.FINISH);
            panel.add(Labelled.caption(Text.FINISH, finish).setMuted(true));
            Row finishRow = Page.row(0, finish);
            finishRow.add(Expanded.spacer(1)); // at the start of the line, under its caption
            panel.add(finishRow);

            lightOn.setChecked(choice.lightOn);
            panel.add(lightOn);
            panel.add(Separator.horizontal());

            List<Runnable> writes = new ArrayList<>();
            for (Part part : Part.values()) {
                Label amount = new Label("");
                writes.add(() -> amount.setText(DOLLARS.apply(choice.price(part))));
                panel.add(Page.row(8, Expanded.of(new Label(part.name).setMuted(true), 1), amount));
            }
            panel.add(Separator.horizontal());
            Label total = new Label("").setStrong(true);
            writes.add(() -> total.setText(DOLLARS.apply(choice.total())));
            panel.add(Page.row(8, Expanded.of(new Label(Text.TOTAL).setStrong(true), 1), total));
            prices = () -> writes.forEach(Runnable::run);
            Page.followLanguage(prices); // amounts are written as the language in effect writes them
        }
    }

    // ------------------------------------------------------------------ the geometry

    /**
     * A surface of revolution about +Y, turned from a profile of {@code (radius, height)} pairs.
     *
     * <p>The profile runs with the solid on its left, upward on an outer wall, so each segment's
     * normal, (dy, -dr), points out of the solid. A point written twice is a crease: the faces on
     * either side of it keep their own normals there instead of sharing a rounded one.
     *
     * @param profile  radius and height, pairs in order; radius 0 closes a cap on the axis
     * @param segments how many slices around the axis
     */
    static MeshData lathe(float[] profile, int segments) {
        int n = profile.length / 2;
        float[] nr = new float[n];
        float[] ny = new float[n];
        for (int i = 0; i < n; i++) {
            float sumR = 0;
            float sumY = 0;
            if (i > 0 && !same(profile, i - 1, i)) {
                float dr = profile[2 * i] - profile[2 * i - 2];
                float dy = profile[2 * i + 1] - profile[2 * i - 1];
                float len = (float) Math.hypot(dr, dy);
                sumR += dy / len;
                sumY += -dr / len;
            }
            if (i < n - 1 && !same(profile, i, i + 1)) {
                float dr = profile[2 * i + 2] - profile[2 * i];
                float dy = profile[2 * i + 3] - profile[2 * i + 1];
                float len = (float) Math.hypot(dr, dy);
                sumR += dy / len;
                sumY += -dr / len;
            }
            float len = (float) Math.hypot(sumR, sumY);
            nr[i] = len > 0 ? sumR / len : 0;
            ny[i] = len > 0 ? sumY / len : 1;
        }
        int ring = segments + 1; // the seam is written twice, so u runs 0 to 1
        float[] positions = new float[n * ring * 3];
        float[] normals = new float[n * ring * 3];
        float[] uvs = new float[n * ring * 2];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < ring; j++) {
                double angle = 2 * Math.PI * j / segments;
                float c = (float) Math.cos(angle);
                float s = (float) Math.sin(angle);
                int v = i * ring + j;
                positions[v * 3] = profile[2 * i] * c;
                positions[v * 3 + 1] = profile[2 * i + 1];
                positions[v * 3 + 2] = profile[2 * i] * s;
                normals[v * 3] = nr[i] * c;
                normals[v * 3 + 1] = ny[i];
                normals[v * 3 + 2] = nr[i] * s;
                uvs[v * 2] = (float) j / segments;
                uvs[v * 2 + 1] = (float) i / (n - 1);
            }
        }
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            if (same(profile, i, i + 1)) {
                continue; // a crease: no faces between a point and its double
            }
            for (int j = 0; j < segments; j++) {
                int a = i * ring + j;
                int b = (i + 1) * ring + j;
                indices.add(a);
                indices.add(b);
                indices.add(b + 1);
                indices.add(a);
                indices.add(b + 1);
                indices.add(a + 1);
            }
        }
        return new MeshData()
                .put(VertexAttribute.POSITION, positions)
                .put(VertexAttribute.NORMAL, normals)
                .put(VertexAttribute.UV0, uvs)
                .indices(indices.stream().mapToInt(Integer::intValue).toArray());
    }

    private static boolean same(float[] profile, int i, int j) {
        return profile[2 * i] == profile[2 * j] && profile[2 * i + 1] == profile[2 * j + 1];
    }

    // ------------------------------------------------------------- the popover's point

    /**
     * The view with a point on it the popover hangs from: an empty mark, a point wide, moved to
     * where a part was picked. Clicks go to the view, never to the mark.
     */
    private static final class Pinned extends Widget<Pinned> {
        final Viewport3D view;
        final Mark mark = new Mark();
        private float x;
        private float y;

        Pinned(Viewport3D view) {
            this.view = view;
            add(view);
            add(mark);
        }

        void pin(float atX, float atY) {
            x = atX;
            y = atY;
            markNeedsLayout();
        }

        @Override
        protected Size onMeasure(Constraints c) {
            Size size = view.measure(c);
            mark.measure(Constraints.tight(1, 1));
            return size;
        }

        @Override
        protected void onLayout() {
            view.measure(Constraints.tight(width(), height()));
            view.layoutBox(0, 0, width(), height());
            mark.measure(Constraints.tight(1, 1));
            mark.layoutBox(Math.max(0, Math.min(x, width() - 1)), Math.max(0, Math.min(y, height() - 1)), 1, 1);
        }

        @Override
        protected @Nullable Widget<?> onHitTest(float localX, float localY) {
            return hitTestChild(view, localX, localY);
        }
    }

    /** A point that draws nothing and is nothing to a reader: only somewhere to hang a popover. */
    private static final class Mark extends Widget<Mark> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(1, 1);
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            a.ignore();
        }
    }

    // --------------------------------------------------------------------- swatches

    /**
     * A row of colour swatches that choose one: a radio group, and spoken as one. One tab stop,
     * the chosen swatch; the arrow keys move the choice along the row, and Space, Enter or a click
     * choose the swatch they are on.
     */
    static final class Swatches {
        private final List<Swatch> members = new ArrayList<>();
        private int chosen;
        private @Nullable IntConsumer onChoose;

        Swatches(I18nString[] names, int[] colours, int initial) {
            for (int i = 0; i < names.length; i++) {
                members.add(new Swatch(this, i, names[i], Color.rgb(colours[i])));
            }
            chosen = initial;
            members.get(chosen).setFocusable(true);
        }

        Row row() {
            Row row = new Row();
            row.gap(6).crossAlignment(Flex.CrossAlignment.CENTER);
            members.forEach(row::add);
            return row;
        }

        List<Swatch> members() {
            return members;
        }

        int chosen() {
            return chosen;
        }

        void onChoose(IntConsumer handler) {
            onChoose = handler;
        }

        /** Gives every swatch the row's caption as its description: what a swatch is the colour of. */
        void describedAs(I18nString caption) {
            for (Swatch swatch : members) {
                swatch.setAccessibleDescription(caption);
            }
        }

        void choose(int index, Change.Origin origin) {
            index = Math.max(0, Math.min(index, members.size() - 1));
            if (index == chosen) {
                return;
            }
            Swatch left = members.get(chosen);
            Swatch entered = members.get(index);
            chosen = index;
            for (Swatch swatch : members) {
                swatch.setFocusable(swatch == entered);
                swatch.invalidate();
                swatch.invalidateAccessible();
            }
            if (left.isFocused()) {
                entered.requestFocus();
            }
            entered.chosen(origin);
            if (origin == Change.Origin.USER && onChoose != null) {
                onChoose.accept(index);
            }
        }
    }

    /** One swatch: a disc of its colour, ringed in the accent when it is the one chosen. */
    static final class Swatch extends Widget<Swatch> {
        private static final float BOX = 36;
        private final Swatches group;
        private final int index;
        private final I18nString name;
        private final Color colour;

        Swatch(Swatches group, int index, I18nString name, Color colour) {
            this.group = group;
            this.index = index;
            this.name = name;
            this.colour = colour;
            setFocusable(false);
            setCursor(Cursor.POINTER);
            setTooltip(name);
        }

        boolean isChosen() {
            return group.chosen() == index;
        }

        void chosen(Change.Origin origin) {
            notifyChange(Change.of(Change.Aspect.SELECTION, origin));
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(BOX, BOX);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            float cx = width() / 2;
            float cy = height() / 2;
            if (isChosen()) {
                canvas.drawCircle(cx, cy, 14.5f, 2f, theme.primary());
            }
            canvas.fillCircle(cx, cy, 11f, colour);
            canvas.drawCircle(cx, cy, 11f, 1f, theme.outline());
            if (isFocused()) {
                canvas.drawCircle(cx, cy, 17f, 2f, theme.focusRing());
            }
        }

        @Override
        protected void onFocusGained() {
            invalidate();
        }

        @Override
        protected void onFocusLost() {
            invalidate();
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            switch (event.type()) {
                case PRESS -> event.consume();
                case CLICK -> {
                    if (event.button() == Keys.MOUSE_LEFT) {
                        event.consume();
                        group.choose(index, Change.Origin.USER);
                        requestFocus();
                    }
                }
                default -> {
                }
            }
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (!event.isPressed()) {
                return;
            }
            // LEFT and RIGHT name sides of the screen, so reading right to left LEFT is the later
            // swatch; UP and DOWN name the order, which no direction moves.
            boolean rtl = isRightToLeft();
            int step = switch (event.key()) {
                case Keys.LEFT -> rtl ? 1 : -1;
                case Keys.RIGHT -> rtl ? -1 : 1;
                case Keys.UP -> -1;
                case Keys.DOWN -> 1;
                default -> 0;
            };
            if (step != 0) {
                event.consume();
                group.choose(index + step, Change.Origin.USER);
            } else if ((event.key() == Keys.SPACE || event.key() == Keys.ENTER) && !event.isRepeat()) {
                event.consume();
                group.choose(index, Change.Origin.USER);
            }
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.RADIO_BUTTON);
            a.name(name);
            a.containerlessSelectionItem(isChosen(), index + 1, group.members().size());
            a.action(Accessible.Action.SELECT);
        }

        @Override
        protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
            if (action == Accessible.Action.SELECT) {
                group.choose(index, Change.Origin.USER);
                return true;
            }
            return false;
        }
    }
}
