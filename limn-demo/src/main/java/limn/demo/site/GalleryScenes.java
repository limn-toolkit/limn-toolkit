package limn.demo.site;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.PasswordField;
import limn.components.ProgressBar;
import limn.components.RadioButton;
import limn.components.SearchField;
import limn.components.SegmentedControl;
import limn.components.Slider;
import limn.components.Spinner;
import limn.components.TabbedPane;
import limn.components.Theme;
import limn.components.TextArea;
import limn.components.TextField;
import limn.components.BackdropPanel;
import limn.components.ColorPicker;
import limn.components.Dialog;
import limn.components.DisplayMode;
import limn.components.ImageView;
import limn.components.ListView;
import limn.components.Menu;
import limn.components.MenuBar;
import limn.components.MenuItem;
import limn.components.ScrollBar;
import limn.components.ScrollView;
import limn.components.Separator;
import limn.components.SplitPane;
import limn.components.ToolBar;
import limn.components.VideoView;
import limn.components.Viewport3D;
import limn.components.chart.BarChart;
import limn.components.chart.ChartSeries;
import limn.components.chart.DonutChart;
import limn.components.chart.LineChart;
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
import limn.video.decode.SyntheticPattern;
import limn.video.decode.SyntheticSpec;
import limn.video.decode.SyntheticVideoDecoder;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;

import java.util.List;

/**
 * One scene per documented component, each containing <em>only</em> that component.
 *
 * <p>These are not the kitchen sink and they are not crops of it. A reference screen shows
 * a component surrounded by twenty others and at whatever size the surrounding layout left
 * it; a documentation page has to show the component itself, at its own size, in the states
 * worth naming. So the gallery captures these, and the kitchen sink stays what it is: a
 * place to try the toolkit, not the source of its documentation.
 *
 * <p><b>Every scene is inside a {@code #region} marker, and the site extracts the marked
 * text as that entry's code sample.</b> The picture and the snippet therefore come from the
 * same lines: a sample on the site is a sample {@code ./gradlew check} compiles, and there
 * is no third place where either could be edited on its own. The build fails if a region a
 * manifest entry names is missing, so deleting one is caught rather than shipped as an
 * empty code block.
 *
 * <p>Scenes must be <b>deterministic</b>: no clock in frame, no animation left mid-flight,
 * no value that depends on the machine. Two runs of the same entry produce the same pixels.
 */
final class GalleryScenes {

    private GalleryScenes() {
    }

    /**
     * A built scene. It carries no size: every entry is captured on ONE fixed canvas.
     *
     * <p>Per-entry sizes were tried first and are the reason this note exists. Resizing the
     * window between shots races the framebuffer; the resize lands asynchronously, so a
     * scene built too early lays out against the previous entry's size and the capture
     * returns the leftover pixels of the shot before it, flipped, down one edge. A fixed
     * canvas removes the race rather than timing it, and it also gives the gallery grid
     * uniform cards, which is what keeps that page free of layout shift.
     */
    /**
     * @param pointer the arrow layer sitting over the content. Every scene carries one and
     *                it draws nothing until the gallery's film loop moves it, so a still
     *                capture is the same picture it was before animation existed.
     * @param content the widget this entry is about. The capture reports its box so the site
     *                can crop a film to it: trimming the pixels instead works for a picture
     *                and fails for a film, because a component that is mostly background
     *                (a split pane is two labels and a hairline) trims to its ink, and
     *                anything that then MOVES moves outside the crop.
     */
    record Built(Scene scene, PointerLayer pointer, Widget content, FrameClock clock) {
    }

    /**
     * Every entry the gallery renders. Adding a component here adds it to the site: the
     * page is built from the manifest this produces, so no list is maintained twice.
     *
     * <p><b>This order is the order of the component page</b>, which reads it as written and
     * does not sort. It runs from what nearly every window needs down to what one window in
     * a hundred does, so that a reader meeting the toolkit sees the vocabulary they will
     * actually type first, and the specialised surfaces (charts, the colour picker, glass,
     * video, 3D) after it. Alphabetical would open the page on BackdropPanel, which is the
     * component least likely to be anyone's first question.
     *
     * <p>So adding an entry means deciding where it belongs, and appending is a decision
     * too. The only pair deliberately kept together rather than ranked is ProgressBar and
     * its indeterminate variant: one component, two pictures.
     */
    static List<GalleryEntry> entries() {
        return List.of(
                // The vocabulary of an ordinary window.
                new GalleryEntry("button", "Button", "gallery:button", GalleryScenes::button),
                new GalleryEntry("label", "Label", "gallery:label", GalleryScenes::label),
                new GalleryEntry("text-field", "TextField", "gallery:text-field",
                        GalleryScenes::textField),
                new GalleryEntry("checkbox", "Checkbox and switch", "gallery:checkbox",
                        GalleryScenes::checkbox),
                new GalleryEntry("combo-box", "ComboBox", "gallery:combo-box",
                        GalleryScenes::comboBox),
                new GalleryEntry("list-view", "ListView", "gallery:list-view",
                        GalleryScenes::listView),
                new GalleryEntry("scroll-view", "ScrollView and ScrollBar",
                        "gallery:scroll-view", GalleryScenes::scrollView),
                new GalleryEntry("dialog", "Dialog", "gallery:dialog", GalleryScenes::dialog),
                new GalleryEntry("menu-bar", "MenuBar", "gallery:menu-bar",
                        GalleryScenes::menuBar),
                new GalleryEntry("tool-bar", "ToolBar", "gallery:tool-bar",
                        GalleryScenes::toolBar),

                // Reached for on most screens, but not on every one.
                new GalleryEntry("tabbed-pane", "TabbedPane", "gallery:tabbed-pane",
                        GalleryScenes::tabbedPane),
                new GalleryEntry("text-area", "TextArea", "gallery:text-area",
                        GalleryScenes::textArea),
                new GalleryEntry("radio-button", "RadioButton", "gallery:radio-button",
                        GalleryScenes::radioButton),
                new GalleryEntry("separator", "Separator", "gallery:separator",
                        GalleryScenes::separator),
                new GalleryEntry("progress-bar", "ProgressBar", "gallery:progress-bar",
                        GalleryScenes::progressBar),
                new GalleryEntry("progress-indeterminate", "ProgressBar (indeterminate)",
                        "gallery:progress-indeterminate", GalleryScenes::progressIndeterminate),
                new GalleryEntry("image-view", "ImageView", "gallery:image-view",
                        GalleryScenes::imageView),
                new GalleryEntry("search-field", "SearchField", "gallery:search-field",
                        GalleryScenes::searchField),
                new GalleryEntry("password-field", "PasswordField", "gallery:password-field",
                        GalleryScenes::passwordField),

                // A particular job, on a particular screen.
                new GalleryEntry("slider", "Slider", "gallery:slider", GalleryScenes::slider),
                new GalleryEntry("spinner", "Spinner", "gallery:spinner", GalleryScenes::spinner),
                new GalleryEntry("split-pane", "SplitPane", "gallery:split-pane",
                        GalleryScenes::splitPane),
                new GalleryEntry("segmented-control", "SegmentedControl",
                        "gallery:segmented-control", GalleryScenes::segmentedControl),
                new GalleryEntry("bar-chart", "BarChart", "gallery:bar-chart",
                        GalleryScenes::barChart),
                new GalleryEntry("line-chart", "LineChart", "gallery:line-chart",
                        GalleryScenes::lineChart),
                new GalleryEntry("donut-chart", "DonutChart", "gallery:donut-chart",
                        GalleryScenes::donutChart),

                // The specialised surfaces: an application asks for these by name.
                new GalleryEntry("color-picker", "ColorPicker", "gallery:color-picker",
                        GalleryScenes::colorPicker),
                new GalleryEntry("backdrop-panel", "BackdropPanel", "gallery:backdrop-panel",
                        GalleryScenes::backdropPanel),
                new GalleryEntry("video-view", "VideoView", "gallery:video-view",
                        GalleryScenes::videoView),
                new GalleryEntry("viewport-3d", "Viewport3D", "gallery:viewport-3d",
                        GalleryScenes::viewport3d));
    }

    // ---------------------------------------------------------------- scenes

    // #region gallery:button
    static Built button() {
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        Button disabled = new Button("Disabled");
        disabled.setEnabled(false);
        row.add(new Button("Primary"));
        row.add(new Button("Secondary").setSecondary(true));
        row.add(disabled);
        return scene(row);
    }
    // #endregion

    // #region gallery:checkbox
    static Built checkbox() {
        Column column = new Column();
        column.gap(12);
        column.add(new Checkbox(Checkbox.Variant.BOX, "Box, checked").setChecked(true));
        column.add(new Checkbox(Checkbox.Variant.BOX, "Box, unchecked"));
        column.add(new Checkbox(Checkbox.Variant.SWITCH, "Switch, on").setChecked(true));
        column.add(new Checkbox(Checkbox.Variant.SWITCH, "Switch, off"));
        return scene(column);
    }
    // #endregion

    // #region gallery:radio-button
    static Built radioButton() {
        RadioButton one = new RadioButton("Selected");
        RadioButton two = new RadioButton("Not selected");
        new limn.components.ButtonGroup().add(one).add(two);
        one.select();
        Column column = new Column();
        column.gap(12);
        column.add(one);
        column.add(two);
        return scene(column);
    }
    // #endregion

    // #region gallery:text-field
    static Built textField() {
        Column column = new Column();
        column.gap(12);
        column.add(new TextField().setText("Typed text"));
        column.add(new TextField().setPlaceholder("Placeholder"));
        return scene(column);
    }
    // #endregion

    // #region gallery:password-field
    static Built passwordField() {
        // The dot is DRAWN rather than typeset, so it needs no glyph coverage at any
        // control size, which is why this reads the same in every locale on the site.
        PasswordField masked = new PasswordField();
        masked.setText("correct horse");
        return scene(masked);
    }
    // #endregion

    // #region gallery:text-area
    static Built textArea() {
        TextArea area = new TextArea();
        area.setText("""
                A multiline editor with draggable scrollbars.
                Arrow keys move across lines; the wheel scrolls.
                Selection is by grapheme cluster, so combining
                marks and ZWJ emoji are never split.""");
        return scene(new SizedBox(360, 120, area));
    }
    // #endregion

    // #region gallery:search-field
    static Built searchField() {
        return scene(new SearchField());
    }
    // #endregion

    // #region gallery:combo-box
    static Built comboBox() {
        ComboBox combo = new ComboBox(List.of("Limn", "Limn Light", "Nord", "Dracula"));
        combo.setSelectedIndex(0);
        return scene(new SizedBox(260, SizedBox.UNSET, combo));
    }
    // #endregion

    // #region gallery:slider
    static Built slider() {
        Slider slider = new Slider(0, 100);
        slider.setValue(65);
        return scene(new SizedBox(280, SizedBox.UNSET, slider));
    }
    // #endregion

    // #region gallery:spinner
    static Built spinner() {
        Spinner spinner = new Spinner(0, 100, 1);
        spinner.setValue(42);
        return scene(spinner);
    }
    // #endregion

    // #region gallery:progress-bar
    static Built progressBar() {
        Column column = new Column();
        column.gap(16);
        // Determinate only: an indeterminate bar animates, and an animated widget captured
        // mid-sweep gives a different picture every run.
        column.add(new Label("Determinate").setMuted(true));
        column.add(new ProgressBar().setProgress(0.62f).setPreferredWidth(280));
        return scene(column);
    }
    // #endregion

    // #region gallery:segmented-control
    static Built segmentedControl() {
        SegmentedControl control = new SegmentedControl(List.of("Day", "Week", "Month"));
        control.setSelectedIndex(1);
        return scene(control);
    }
    // #endregion

    // #region gallery:tabbed-pane
    static Built tabbedPane() {
        TabbedPane tabs = new TabbedPane();
        tabs.addTab("Overview", new Label("Content of the selected tab."));
        tabs.addTab("Details", new Label("Second tab."));
        tabs.addTab("History", new Label("Third tab."));
        return scene(new SizedBox(360, 120, tabs));
    }
    // #endregion

    // #region gallery:video-view
    static Built videoView() {
        // The PURE-JAVA source, deliberately: the toolkit's synthetic generator needs no
        // native, no third-party codec and no media file, so this renders identically on
        // any machine and on a CI runner that has never built FFmpeg. The still therefore
        // shows the WIDGET working, not a codec; the caption on the site says so, because
        // a frame of H.264 is not what this picture is evidence of.
        SyntheticSpec spec = SyntheticSpec.of(400, 226)
                .withPattern(SyntheticPattern.BARS)
                .withFrameCount(1);
        // FILL, and a box the size of the frame: the default CONTAIN letterboxes inside its
        // box whenever the two aspects differ at all, and a transport aligned to the box then
        // hangs over the edge of the picture it is supposed to be sitting on.
        VideoView view = new VideoView(SyntheticVideoDecoder.open(spec));
        view.setFit(VideoView.Fit.FILL).setPreferredSize(400, 226);

        // The transport is NOT part of the widget: it is ordinary controls under the picture.
        // CENTER rather than STRETCH, and a transport narrower than the frame: a video view
        // sizes itself from the stream it is showing, so the two widths are never a number
        // this file gets to pick, and centring is what stays right when they differ.
        Column player = new Column();
        player.gap(4).crossAlignment(Flex.CrossAlignment.CENTER);
        player.add(view);
        player.add(new SizedBox(320, SizedBox.UNSET, transport()));
        return scene(player);
    }

    /**
     * A play/scrub/volume bar for the picture above it: a Button, two Sliders and a Label,
     * and nothing the video widget itself provides. One size step down is what keeps a
     * transport reading as chrome rather than as content.
     */
    private static Widget transport() {
        Slider scrub = new Slider(0, 100);
        scrub.setValue(38);
        Slider volume = new Slider(0, 100);
        volume.setValue(70);

        Row row = new Row();
        row.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(new Button("Pause").setSecondary(true));
        row.add(limn.scene.layout.Expanded.of(scrub));
        row.add(new Label("0:24 / 1:03").setMuted(true));
        row.add(new SizedBox(52, SizedBox.UNSET, volume));

        Padding bar = new Padding(limn.scene.Insets.symmetric(10, 2), row);
        bar.setControlSize(limn.scene.ControlSize.SMALL);
        return bar;
    }
    // #endregion

    // #region gallery:label
    static Built label() {
        Column column = new Column();
        column.gap(10);
        column.add(new Label("Body text"));
        column.add(new Label("Muted text").setMuted(true));
        Label clipped = new Label("A long line that does not fit, measured and ellipsised");
        clipped.setOverflow(Label.Overflow.ELLIPSIS);
        column.add(new SizedBox(260, SizedBox.UNSET, clipped));
        return scene(column);
    }
    // #endregion

    // #region gallery:separator
    static Built separator() {
        Column column = new Column();
        column.gap(14);
        column.add(new Label("Above"));
        column.add(new SizedBox(260, SizedBox.UNSET, Separator.horizontal()));
        column.add(new Label("Below").setMuted(true));
        return scene(column);
    }
    // #endregion

    // #region gallery:tool-bar
    static Built toolBar() {
        ToolBar bar = new ToolBar();
        bar.addItem(new Button("New"));
        bar.addItem(new Button("Open").setSecondary(true));
        bar.addSeparator();
        bar.addItem(new Button("Save").setSecondary(true));
        return scene(bar);
    }
    // #endregion

    // #region gallery:menu-bar
    static Built menuBar() {
        // The bar itself is drawn IN the scene, so it captures with the window. Its
        // dropdowns are native windows and are a separate entry.
        Menu file = new Menu();
        file.add(MenuItem.of("New", () -> { }));
        file.add(MenuItem.of("Open…", () -> { }));
        file.add(MenuItem.separator());
        file.add(MenuItem.check("Word wrap", true, on -> { }));
        MenuBar bar = new MenuBar();
        bar.addMenu("File", file);
        bar.addMenu("Edit", new Menu());
        bar.addMenu("View", new Menu());
        return scene(new SizedBox(300, SizedBox.UNSET, bar));
    }
    // #endregion

    // #region gallery:list-view
    static Built listView() {
        // Rows are materialised on demand: the list only ever builds what the viewport
        // shows, so this adapter would cost the same with a million rows.
        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return 1000;
            }

            @Override
            public Widget rowAt(int index) {
                Label row = new Label("Row " + (index + 1));
                return new Padding(limn.scene.Insets.symmetric(8, 12), row);
            }
        });
        return scene(new SizedBox(320, 130, list));
    }
    // #endregion

    // #region gallery:scroll-view
    static Built scrollView() {
        Column tall = new Column();
        tall.gap(8);
        for (int i = 1; i <= 12; i++) {
            tall.add(new Label("Scrollable line " + i));
        }
        ScrollView scroller = new ScrollView(new Padding(limn.scene.Insets.all(12), tall));
        // ALWAYS, not the default: a bar that fades on idle is invisible in a still.
        scroller.setScrollbarPolicy(ScrollBar.Policy.ALWAYS);
        return scene(new SizedBox(320, 130, scroller));
    }
    // #endregion

    // #region gallery:split-pane
    static Built splitPane() {
        SplitPane split = SplitPane.horizontal(
                new Padding(limn.scene.Insets.all(12), new Label("Left")),
                new Padding(limn.scene.Insets.all(12), new Label("Right").setMuted(true)));
        split.setRatio(0.4f);
        return scene(new SizedBox(340, 130, split));
    }
    // #endregion

    // #region gallery:dialog
    static Built dialog() {
        // IN_SCENE, not the default native window: an overlay drawn inside the owner is
        // what one framebuffer can capture. The native-window mode is the same widget in
        // a window of its own, which a screenshot of this one would not contain.
        Column owner = new Column();
        owner.gap(8);
        owner.add(new Label("Owner content, dimmed by the scrim").setMuted(true));
        Built built = scene(new SizedBox(340, 140, owner));
        new Dialog("Discard changes?", "Your edits will be lost.")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .addButton("Cancel", "cancel")
                .addPrimaryButton("Discard", "discard")
                .show(built.scene());
        return built;
    }
    // #endregion

    // #region gallery:color-picker
    static Built colorPicker() {
        ColorPicker picker = new ColorPicker();
        picker.setColor(limn.graphics.Color.rgb(0xAF7AFF));
        // Given no width to work with, each channel rail measures to an icon's width and
        // the sliders read as ticks. The box is what bounds it, and every rail then gets
        // the room left over on its line.
        return scene(new SizedBox(380, SizedBox.UNSET, picker));
    }
    // #endregion

    // #region gallery:image-view
    static Built imageView() {
        // A generated image rather than a file: the capture must not depend on an asset
        // that a checkout might not have.
        int size = 96;
        byte[] pixels = new byte[size * size * 4];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int i = (y * size + x) * 4;
                pixels[i] = (byte) (x * 255 / size);
                pixels[i + 1] = (byte) (y * 255 / size);
                pixels[i + 2] = (byte) 0xC0;
                pixels[i + 3] = (byte) 0xFF;
            }
        }
        ImageView view = new ImageView(new limn.graphics.Image(size, size, pixels));
        view.setPreferredSize(96, 96);
        return scene(view);
    }
    // #endregion

    // #region gallery:backdrop-panel
    static Built backdropPanel() {
        // A checkerboard, because the effect this panel exists for is refraction at the
        // rim, and a straight line bending as it passes under the edge is the only backdrop
        // that shows it. It scrolls diagonally, because a rim that bends a STILL grid is a
        // picture of distortion and a rim that bends a moving one is the effect happening.
        Theme theme = Theme.current();
        Widget board = new Widget() {
            private float offset;

            @Override
            protected limn.scene.Size onMeasure(limn.scene.Constraints constraints) {
                return new limn.scene.Size(constraints.maxWidth(), constraints.maxHeight());
            }

            @Override
            protected void onPaint(limn.graphics.Canvas canvas) {
                // Clipped to its own box: the squares are drawn from a cell outside each
                // edge so one sliding in is whole when it crosses, and without a clip that
                // overspill is what the picture shows: a board whose own edge walks
                // diagonally out of the frame.
                canvas.save();
                canvas.clipRect(0, 0, width(), height());
                float cell = 10;
                // Drawn from one cell outside the box on each side, so a square sliding in
                // is already whole when it crosses the edge.
                float shift = offset % (cell * 2);
                // From TWO cells outside, not one: the shift runs up to two cells, so a
                // single spare row leaves the top of the board unpainted once per cycle,
                // a seam that slides down the picture every time the pattern wraps.
                for (int row = -2; row * cell < height() + cell * 2; row++) {
                    for (int col = -2; col * cell < width() + cell * 2; col++) {
                        if (((row + col) & 1) != 0) {
                            continue;
                        }
                        canvas.fillRect(col * cell + shift, row * cell + shift,
                                cell, cell, theme.primary);
                    }
                }
                canvas.restore();
            }

            @Override
            protected void onAttached() {
                // 45°: x and y advance together, one point per frame at the capture's step.
                scene().addTicker(dt -> {
                    offset += (float) (dt * 12.5);
                    invalidate();
                    return true;
                });
            }
        };

        // Clear, not Wash: this is the variant that displaces the backdrop at the rim
        // instead of recolouring it flat. The tint is the page's own canvas at a little
        // under half: enough that the label keeps its contrast over either square, little
        // enough that the grid still visibly bends through it.
        BackdropPanel panel = new BackdropPanel(
                new limn.graphics.BackdropEffect.Clear(theme.background.withAlpha(0.45f), 12f, 0.45f),
                limn.scene.Insets.symmetric(20, 44),
                new Label("Clear glass"));
        panel.setCornerRadius(18);

        Stack stack = new Stack().alignment(Stack.Alignment.CENTER);
        stack.add(board);
        stack.add(panel);
        return scene(new SizedBox(340, 120, stack));
    }
    // #endregion

    // #region gallery:viewport-3d
    static Built viewport3d() {
        // The floor runs well past the frame: a plane that ends inside it puts a hard edge
        // across the picture and the scene reads as a tabletop rather than a ground.
        MeshData ground = Primitives.plane(30, 30);
        MeshData cube = Primitives.cube(1.6f);
        MeshData ball = Primitives.sphere(0.95f, 40, 60);
        Scene3D[] built = {null};

        Viewport3D viewport = new Viewport3D().setPreferredSize(320, 200);
        viewport.camera().eye(new Vec3(3.1f, 2.5f, 5.0f)).target(new Vec3(0.1f, 0.1f, 0));
        viewport.setController(new OrbitController(viewport.camera()));
        viewport.setRenderer((pass, seconds) -> {
            if (built[0] == null) {
                built[0] = pbrScene(ground, cube, ball);
            }
            float aspect = viewport.height() > 0 ? viewport.width() / viewport.height() : 1f;
            built[0].render(pass, viewport.camera(), aspect);
        });
        // Nothing in the render depends on the clock, so asking for a frame per tick still
        // produces identical pixels. It is also what stops the FIRST 3D frame in a window,
        // which comes out empty, from being the one the capture keeps.
        viewport.setRenderScale(0.5f);
        viewport.setAnimated(true);
        viewport.onDispose(() -> {
            if (built[0] != null) {
                built[0].dispose();
            }
        });
        return scene(viewport);
    }

    /** A checkered floor, a metal cube and a glossy sphere, under two lights. */
    private static Scene3D pbrScene(MeshData ground, MeshData cube, MeshData ball) {
        Scene3D scene = new Scene3D()
                .background(new Vec4(0.06f, 0.05f, 0.09f, 1f))
                .ambient(new Vec3(0.05f, 0.05f, 0.07f))
                .exposure(1.15f)
                .castShadows(true);

        GpuTexture floor = Graphics3D.uploadTexture(
                checker(256, 8, 46, 44, 58, 188, 186, 200), Sampler.smooth());
        scene.root().add(new MeshNode(Graphics3D.upload(ground),
                Material.Pbr.of(1f, 1f, 1f).roughness(0.8f).textured(floor))
                .transform(Transform3D.at(new Vec3(0, -1f, 0))));
        // A dielectric, not a metal: there is no environment map in this scene, so a metal
        // has nothing to reflect and renders as a flat dark patch.
        scene.root().add(new MeshNode(Graphics3D.upload(cube),
                Material.Pbr.of(0.45f, 0.16f, 0.92f).metallic(0.1f).roughness(0.35f))
                .transform(new Transform3D(new Vec3(-1.35f, -0.2f, -0.1f),
                        Quat.fromAxisAngle(Vec3.UNIT_Y, 0.55f), Vec3.ONE)));
        scene.root().add(new MeshNode(Graphics3D.upload(ball),
                Material.Pbr.of(0.93f, 0.94f, 0.99f).metallic(0.2f).roughness(0.16f))
                .transform(Transform3D.at(new Vec3(1.3f, -0.05f, 0.45f))));

        scene.root().add(new LightNode(new Light.Directional(
                new Vec3(0.5f, 1.2f, 0.55f), new Vec3(1f, 0.97f, 0.92f), 3.4f)));
        scene.root().add(new LightNode(new Light.Point(
                new Vec3(-3.2f, 1.8f, 2.8f), new Vec3(0.6f, 0.45f, 1f), 14f, 14f)));
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
    // #endregion

    // #region gallery:progress-indeterminate
    static Built progressIndeterminate() {
        // The sweep animates, so this entry is captured at a pinned scene time rather than
        // whenever the frame happened to land (see Gallery's fixed warmup).
        ProgressBar bar = new ProgressBar();
        bar.setIndeterminate(true).setPreferredWidth(280);
        return scene(bar);
    }
    // #endregion

    // #region gallery:bar-chart
    static Built barChart() {
        BarChart chart = BarChart.of(List.of("Q1", "Q2", "Q3", "Q4"),
                ChartSeries.of("Direct", 120, 145, 132, 168),
                ChartSeries.of("Partner", 80, 92, 105, 99));
        chart.setPreferredSize(380, 230);
        return scene(chart);
    }
    // #endregion

    // #region gallery:line-chart
    static Built lineChart() {
        LineChart chart = LineChart.of(List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
                ChartSeries.of("p50", 24, 21, 26, 30, 28, 25, 27),
                ChartSeries.of("p99", 62, 58, 71, 88, 74, 66, 70));
        chart.setSmooth(true).setArea(true).setPreferredSize(380, 230);
        return scene(chart);
    }
    // #endregion

    // #region gallery:donut-chart
    static Built donutChart() {
        DonutChart chart = DonutChart.of(List.of("Direct", "Search", "Social", "Mail"),
                42, 31, 18, 9);
        // The hole is a slot, not a hole: whatever widget goes in it is laid out and drawn
        // like any other child, so a total, a label or a whole column can live there.
        Column centre = new Column();
        centre.gap(2).crossAlignment(Flex.CrossAlignment.CENTER);
        centre.add(new Label("100").setRole(Label.Role.TITLE));
        centre.add(new Label("sessions").setMuted(true));
        chart.setCenter(centre);
        chart.setPreferredSize(300, 230);
        return scene(chart);
    }
    // #endregion

    // ---------------------------------------------------------------- helper

    /**
     * Centres the widget on the fixed canvas, so an entry's PNG is the component and not
     * a component wedged into a corner. Nothing here measures anything: a measured size
     * would make the capture depend on which font happened to load first.
     */
    /**
     * A clock that advances a fixed step per read instead of following the wall.
     *
     * <p>The capture loop renders frames as fast as it can, so real elapsed time between
     * them is a fraction of a millisecond; a fade of 160 ms never finishes however many
     * frames are drawn, and the Dialog entry came out washed out mid-transition. Stepping
     * the clock in code makes the settled state reachable AND identical on every machine,
     * which wall-clock warmup could never be. {@code Scene}'s injectable clock exists for
     * exactly this.
     *
     * <p>The step stays under {@code Scene.MAX_TICK_SECONDS} so no tick is clamped.
     */
    /**
     * Milliseconds of scene time per captured frame: the step of {@link FrameClock}, and the
     * frame delay the site writes into the animations. One number, so a film cannot play at
     * a speed the toolkit never rendered it at.
     */
    static final long STEP_MS = 20;

    /**
     * The capture's clock: it advances once per FRAME, when the driver says so, and never
     * because somebody read it.
     *
     * <p>The first version advanced on every read ({@code () -> now += 20ms}), which is a
     * clock that runs faster the more of it you use. A scene reads its clock several times a
     * frame, so scene time was running about nine times real time: the indeterminate progress
     * bar crossed a sixth of its track between two captured frames, which is what "too fast
     * and not smooth" looks like, and every transition in every other film settled long
     * before the frame that was supposed to show it happening.
     */
    static final class FrameClock implements java.util.function.LongSupplier {

        private long nanos;

        @Override
        public long getAsLong() {
            return nanos;
        }

        /** One captured frame's worth of scene time. Called by the gallery driver. */
        void advance() {
            nanos += java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(STEP_MS);
        }
    }

    /**
     * Makes a whole-window scene filmable: the same pointer layer and stepped clock the component
     * scenes get, wrapped around a scene somebody else built.
     *
     * <p>It re-parents the scene's root instead of asking the caller to build differently, so that
     * a showcase entry stays one method returning one {@code Scene} whether it is filmed or not.
     * The scene handed in is discarded: it was never bound to a window, and the root it holds is
     * the only part that matters.
     *
     * <p>The clock is the reason this exists rather than a pointer layer added at the call site.
     * A film is a sequence of rendered frames, not a recording of wall-clock time: with the real
     * clock, every hover fade in the film lands wherever the render rate happened to put it, so
     * two runs of the same script produce different frames and nothing downstream can tell that
     * from a change somebody made.
     */
    static Built filmable(Scene source, Widget content) {
        PointerLayer pointer = new PointerLayer();
        FrameClock clock = new FrameClock();
        Stack root = new Stack().alignment(Stack.Alignment.CENTER);
        root.add(source.root());
        Scene scene = new Scene(root, clock);
        scene.setBackground(Theme.current().background);
        // The arrow is the scene's front painter, not the last child of this stack. A child
        // paints under every overlay, so an in-scene dialog hides it completely; see
        // PointerLayer for the film that caught it.
        pointer.attachTo(scene);
        return new Built(scene, pointer, content, clock);
    }

    private static Built scene(Widget widget) {
        Row centred = new Row();
        centred.mainAlignment(Flex.MainAlignment.CENTER)
                .crossAlignment(Flex.CrossAlignment.CENTER);
        centred.add(widget);
        // The pointer draws over the whole window in scene coordinates. It is on every scene,
        // animated or not: it paints nothing until it is moved, and a second scene shape for
        // the filmed entries is a second thing to keep true.
        PointerLayer pointer = new PointerLayer();
        FrameClock clock = new FrameClock();
        // CENTER, not the stack's default. The pointer used to be a second child filling the
        // window, which made it the bigger one and pinned the content to its top-left corner;
        // that is what the first filmed capture came out as. It is a front painter now and no
        // longer sizes anything, but the alignment stays: the padding below is what the content
        // is meant to sit inside.
        Stack root = new Stack().alignment(Stack.Alignment.CENTER);
        root.add(new Padding(limn.scene.Insets.all(20), centred));
        Scene scene = new Scene(root, clock);
        // Scene's default background is a hard-coded tone of the generic Dark palette, not
        // the current theme's. Leaving it gives every light capture a dark canvas with
        // light-palette ink on it. That is what the first run of this gallery produced.
        scene.setBackground(Theme.current().background);
        pointer.attachTo(scene);
        return new Built(scene, pointer, widget, clock);
    }
}
