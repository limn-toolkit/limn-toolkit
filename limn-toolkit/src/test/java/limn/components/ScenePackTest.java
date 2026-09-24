package limn.components;

import limn.backend.Display;
import limn.backend.Resolution;
import limn.backend.ScreenRect;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.StubWindow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Scene#pack()} sizes the bound window to its content's natural size, within the window's
 * size limits and its display's work area, and gives wrapping text the height it needs at the
 * width the window gets.
 */
class ScenePackTest extends ComponentTestBase {

    /** A stub window on a display of the given work area, in native units. */
    private static StubWindow windowOn(int areaWidth, int areaHeight) {
        return new StubWindow() {
            @Override
            public Display display() {
                return new Display() {
                    @Override public String id() { return "d"; }
                    @Override public String name() { return "d"; }
                    @Override public boolean isPrimary() { return true; }
                    @Override public Resolution currentResolution() {
                        return new Resolution(areaWidth, areaHeight, 60);
                    }
                    @Override public List<Resolution> availableResolutions() {
                        return List.of(currentResolution());
                    }
                    @Override public ScreenRect bounds() { return new ScreenRect(0, 0, areaWidth, areaHeight); }
                    @Override public ScreenRect workArea() { return bounds(); }
                    @Override public float contentScale() { return 1; }
                };
            }
        };
    }

    private static int[] pack(Widget<?> content, StubWindow window) {
        Scene scene = new Scene(content);
        scene.setTextRuler(RULER);
        scene.bind(window);
        scene.pack();
        return window.lastSize;
    }

    @Test
    void theWindowTakesItsContentsNaturalSize() {
        Column column = new Column();
        column.add(new SizedBox(120, 40));
        column.add(new SizedBox(80, 30));
        assertArrayEquals(new int[] {120, 70}, pack(column, windowOn(2000, 2000)));
    }

    @Test
    void theSizeLimitsBoundIt() {
        StubWindow small = windowOn(2000, 2000);
        small.setSizeLimits(200, 100, 0, 0);
        assertArrayEquals(new int[] {200, 100}, pack(new SizedBox(120, 40), small), "the minimum");
        StubWindow large = windowOn(2000, 2000);
        large.setSizeLimits(0, 0, 100, 50);
        assertArrayEquals(new int[] {100, 50}, pack(new SizedBox(1000, 900), large), "the maximum");
    }

    @Test
    void theWorkAreaBoundsItInLogicalPoints() {
        assertArrayEquals(new int[] {400, 300}, pack(new SizedBox(1000, 900), windowOn(400, 300)));
        StubWindow doubled = windowOn(400, 300);
        doubled.logicalToScreenFactor = 2; // two native units a point: 200 by 150 points of room
        assertArrayEquals(new int[] {200, 150}, pack(new SizedBox(1000, 900), doubled));
    }

    @Test
    void wrappingTextIsGivenItsHeightAtTheWidthTheWindowGets() {
        String text = "A paragraph long enough to need several lines at any width a window of this "
                + "size can have, which is the case a single measurement with both axes open gets "
                + "wrong: it asks for one line's height and then the window cuts the rest off.";
        Label oneLine = new Label(text);
        Scene alone = new Scene(oneLine);
        alone.setTextRuler(RULER);
        float lineHeight = oneLine.measure(new Constraints(0, Float.POSITIVE_INFINITY, 0,
                Float.POSITIVE_INFINITY)).height();

        Column column = new Column();
        column.add(new Label(text).setWrap(true));
        int[] size = pack(column, windowOn(300, 2000));
        assertTrue(size[0] <= 300, "no wider than the work area: " + size[0]);
        assertTrue(size[1] >= 3 * lineHeight, "the lines it wraps into, not one: " + size[1]
                + " against a line of " + lineHeight);
    }

    @Test
    void boundsOnTheContentAreWhatAPackedWindowFollows() {
        String text = "A paragraph long enough to need several lines once it is held to a column "
                + "narrower than the screen, which is what a maximum on the widget is for.";
        Column column = new Column();
        column.add(new Label(text).setWrap(true).setMaxWidth(200));
        column.add(limn.scene.layout.Expanded.of(new SizedBox(50, 10)).atLeast(160));
        int[] size = pack(column, windowOn(2000, 2000));
        assertTrue(size[0] <= 200, "the paragraph's maximum, not the screen: " + size[0]);
        assertTrue(size[1] >= 160 + 3 * 12, "its lines at 200 and the flexible child's floor: "
                + size[1]);
    }

    @Test
    void anAxisWithNoNaturalSizeKeepsTheWindowsSize() {
        assertArrayEquals(new int[] {400, 30}, pack(new Fills(), windowOn(2000, 2000)),
                "the stub window's own width, 400");
    }

    /** A widget that takes all the width it is given, and so has no natural width. */
    private static final class Fills extends Widget<Fills> {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return new Size(constraints.maxWidth(), 30);
        }
    }

    @Test
    void aSceneBoundToNoWindowIsRefused() {
        Scene headless = new Scene(new SizedBox(10, 10));
        assertThrows(IllegalStateException.class, headless::pack);
    }
}
