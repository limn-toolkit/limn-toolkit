package limn.components;

import limn.graphics.BitmapIcon;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.graphics.Rect;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An animated icon plays by itself in every component that draws icons, on the scene's clock and
 * at the image's times (ADR 065 section 10): it starts at the paint that shows it, repaints its own
 * box and nothing more when a frame is due, waits where nobody sees it and goes on from there,
 * rests on its last frame, and a menu shows its first frame.
 */
class AnimatedIconTest {

    private final AtomicLong nanos = new AtomicLong();
    private HeadlessUi ui;
    private Scene scene;
    /**
     * Every image drawn, in order, since a frame that repaints nothing leaves on screen what the
     * one before drew; and the first clip of the last frame: the region it repainted.
     */
    private final List<Image> drawn = new ArrayList<>();
    private Rect repainted;
    private boolean clipSeen;
    private NoopCanvas canvas;

    /** Four frames of 100 ms, each its own picture; {@code turns} 0 for no end. */
    private static Image spinner(int turns) {
        List<Image> frames = new ArrayList<>();
        for (int f = 0; f < 4; f++) {
            byte[] rgba = new byte[8 * 8 * 4];
            for (int o = 0; o < rgba.length; o += 4) {
                rgba[o] = (byte) (f * 60);
                rgba[o + 3] = (byte) 0xFF;
            }
            frames.add(new Image(8, 8, rgba));
        }
        return Images.animation(frames, new int[] {100, 100, 100, 100}, turns);
    }

    @BeforeEach
    void setUp() {
        ui = new HeadlessUi(nanos::get);
        canvas = new NoopCanvas(400, 300) {
            @Override
            public void drawImage(Image image, float x, float y, float w, float h) {
                drawn.add(image);
            }

            @Override
            public void drawImage(Image image, float x, float y, float w, float h, Color tint) {
                drawn.add(image);
            }

            @Override
            public void drawImageMask(Image image, float x, float y, float w, float h, Color tint) {
                drawn.add(image);
            }

            @Override
            public void clipRect(float x, float y, float w, float h) {
                if (!clipSeen) {
                    clipSeen = true;
                    repainted = new Rect(x, y, w, h);
                }
            }
        };
    }

    @AfterEach
    void tearDown() {
        ui.close();
    }

    private static Column column(Widget<?> child) {
        Column column = new Column();
        column.add(child);
        return column;
    }

    private void show(Widget<?> root) {
        scene = new Scene(root, nanos::get);
        scene.setTextRuler(TestRulers.FIXED);
        scene.layoutPass(400, 300);
        after(0);
        after(0);
    }

    /** Moves the clock on, runs what fell due, and renders a frame, as a backend's loop does. */
    private void after(int millis) {
        nanos.addAndGet(millis * 1_000_000L);
        ui.runtime().drain();
        clipSeen = false;
        repainted = null;
        scene.layoutPass(400, 300);
        scene.renderFrame(canvas);
    }

    /** The frame of {@code image} on screen: the one drawn last, or -1 for none. */
    private int frameDrawn(Image image) {
        for (int at = drawn.size() - 1; at >= 0; at--) {
            for (int f = 0; f < image.frameCount(); f++) {
                if (drawn.get(at) == image.frame(f)) {
                    return f;
                }
            }
        }
        return -1;
    }

    /** What each family that draws icons is shown with, an icon of its own in it. */
    private static final List<Function<Icon, Widget<?>>> FAMILIES = List.of(
            icon -> new Button("Save").setIcon(icon),
            icon -> new Label("Saving").setIcon(icon),
            icon -> new TabbedPane().addTab("Orders", icon, new Label("content")),
            icon -> new TextField().setLeadingIcon(icon),
            icon -> new TextField().setTrailingButton(icon, () -> { }),
            icon -> new ComboBox<>(List.of("One", "Two")).setItemIcon(item -> icon).setSelectedIndex(0),
            icon -> new ComboBox<>(List.of("One", "Two")).setSelectionMode(SelectionMode.MULTI)
                    .setItemIcon(item -> icon).setSelectedIndices(0),
            icon -> new ListView<String>(item -> new Label(item).setIcon(icon)).setItems(List.of("One")));

    @Test
    void everyFamilyThatDrawsIconsPlaysAnAnimatedOneByItself() {
        for (int family = 0; family < FAMILIES.size(); family++) {
            Image image = spinner(0);
            Widget<?> shown = FAMILIES.get(family).apply(BitmapIcon.picture(image));
            show(column(shown));
            assertEquals(0, frameDrawn(image), "family " + family + " starts at the first frame");
            after(60);
            after(60);
            assertEquals(1, frameDrawn(image), "family " + family + " shows the second frame at 100 ms");
            after(100);
            assertEquals(2, frameDrawn(image), "family " + family);
            after(100);
            after(100);
            assertEquals(0, frameDrawn(image), "family " + family + " goes round again");
        }
    }

    @Test
    void aMaskIconPlaysTintedAsAPictureDoes() {
        Image image = spinner(0);
        show(column(new Button("Save").setIcon(BitmapIcon.mask(image))));
        assertEquals(0, frameDrawn(image));
        after(120);
        assertEquals(1, frameDrawn(image));
    }

    @Test
    void aDueFrameRepaintsTheIconsBoxAndNothingMore() {
        Image image = spinner(0);
        Button button = new Button("Save a long name").setIcon(BitmapIcon.picture(image));
        show(column(button));
        after(120);
        assertEquals(1, frameDrawn(image));
        assertNotNull(repainted, "a partial frame clips to what it repaints");
        assertTrue(repainted.width() < button.width() / 2,
                "the icon's box and not the button: " + repainted + " of a button " + button.width() + " wide");
        assertTrue(repainted.height() <= button.height(), repainted.toString());
    }

    @Test
    void anIconRestsOnItsLastFrameWhenItsTurnsAreDone() {
        Image image = spinner(1);
        show(column(new Label("Done").setIcon(BitmapIcon.picture(image))));
        for (int step = 0; step < 4; step++) {
            after(100);
        }
        assertEquals(3, frameDrawn(image));
        after(1000);
        after(1000);
        assertEquals(3, frameDrawn(image), "one turn, ending on the last frame");
    }

    @Test
    void anIconNobodySeesWaitsAndGoesOnFromWhereItWas() {
        Image image = spinner(0);
        Label label = new Label("Saving").setIcon(BitmapIcon.picture(image));
        show(column(label));
        after(120);
        assertEquals(1, frameDrawn(image));
        label.setVisible(false);
        after(0);
        after(1000);
        after(1000);
        label.setVisible(true);
        after(0);
        assertEquals(1, frameDrawn(image), "the time nobody saw did not pass for it");
        after(60);
        after(60);
        assertEquals(2, frameDrawn(image));
    }

    @Test
    void aStillIconAndAnAnimatedOneAtRestAskForNothing() {
        Image still = spinner(0).frame(2);
        show(column(new Button("Still").setIcon(BitmapIcon.picture(still))));
        assertTrue(drawn.contains(still));
        assertEquals(-1, ui.runtime().nanosUntilNextDeadline(), "nothing is scheduled for a still icon");
    }

    @Test
    void aMenuShowsTheFirstFrame() {
        Image image = spinner(0);
        show(column(new Label("Owner")));
        PopupMenu popup = new PopupMenu(new Menu().add(MenuItem.of("Syncing", () -> { })
                .setIcon(BitmapIcon.picture(image))));
        popup.showInSceneForTest(scene, 20, 20, 0, 0);
        after(0);
        after(0);
        assertEquals(0, frameDrawn(image));
        after(120);
        after(120);
        assertEquals(0, frameDrawn(image), "no system's menu moves an icon, and this one keeps to that");
    }

    @Test
    void anIconPaintedFromOutsideAWidgetShowsItsFirstFrame() {
        Image image = spinner(0);
        Icon icon = BitmapIcon.picture(image);
        icon.paint(canvas, 0, 0, 8, null, false);
        assertTrue(drawn.contains(image.frame(0)));
        assertFalse(drawn.contains(image.frame(1)));
    }
}
