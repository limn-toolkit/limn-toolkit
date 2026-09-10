package limn.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The normalized display value types ({@link Resolution} and {@link ScreenRect}),
 * the {@link Backend#primaryDisplay()} default's pick logic, and
 * {@link WindowConfig#on(Display)}, which is how a caller says which of them a window opens on.
 */
class DisplayTypesTest {

    @Test
    void resolutionValidatesAndFormats() {
        Resolution r = new Resolution(1920, 1080, 60);
        assertEquals(1920, r.width());
        assertEquals(60, r.refreshRate());
        assertEquals(16.0 / 9.0, r.aspectRatio(), 1e-9);
        assertEquals("1920×1080 @60Hz", r.toString());
        assertEquals("1280×720", new Resolution(1280, 720).toString(), "unspecified refresh hidden");
        assertThrows(IllegalArgumentException.class, () -> new Resolution(0, 100, 60));
        assertThrows(IllegalArgumentException.class, () -> new Resolution(100, 100, -1));
    }

    @Test
    void resolutionsAreValueEqual() {
        assertEquals(new Resolution(800, 600, 0), new Resolution(800, 600));
    }

    @Test
    void screenRectEdgesAndContainment() {
        ScreenRect rect = new ScreenRect(10, 20, 100, 50);
        assertEquals(110, rect.right());
        assertEquals(70, rect.bottom());
        assertTrue(rect.contains(10, 20));
        assertFalse(rect.contains(110, 20), "right edge is exclusive");
        assertFalse(rect.contains(5, 25));
    }

    @Test
    void primaryDisplayDefaultPrefersTheFlaggedOneThenFallsBackToFirst() {
        Display secondary = display("s", false);
        Display primary = display("p", true);
        // The one flagged isPrimary() wins regardless of order.
        assertEquals("p", backendWith(secondary, primary).primaryDisplay().id());
        // None flagged → the first display.
        assertEquals("a", backendWith(display("a", false), display("b", false)).primaryDisplay().id());
        // Headless → null.
        assertEquals(null, backendWith().primaryDisplay());
    }

    @Test
    void aWindowIsPlacedByNobodyUnlessItAsks() {
        WindowConfig plain = WindowConfig.of("w", 100, 100);
        assertEquals(WindowConfig.ANY_POSITION, plain.screenX(), "the desktop places it");
        assertEquals(WindowConfig.ANY_POSITION, plain.screenY());
        // Every factory, including the nine-part form the popups use.
        assertEquals(WindowConfig.ANY_POSITION, WindowConfig.popup(10, 10).screenX());
        assertEquals(WindowConfig.ANY_POSITION,
                new WindowConfig("w", 1, 1, false, false, false, true, true, false).screenX());
    }

    @Test
    void onADisplayAnchorsToItsWorkAreaAndSurvivesACopy() {
        // The whole point: a window created here is created at THIS monitor's scale, and no
        // arithmetic after the fact can undo a framebuffer that came out the wrong size.
        Display external = display("ext", false, new ScreenRect(-2560, -771, 2560, 1440));
        WindowConfig on = WindowConfig.of("w", 100, 100).on(external);
        assertEquals(-2560, on.screenX());
        assertEquals(-771, on.screenY());
        // A copy keeps it: withVisible is how a popup is built, and losing the display there
        // would put the window back wherever the desktop likes.
        assertEquals(-2560, on.withVisible(false).screenX());
        assertEquals(-771, on.withVisible(false).screenY());
        assertEquals(7, on.at(7, 9).screenX(), "at() overrides");
    }

    @Test
    void onNullIsTheHeadlessAnswerAndNotAFailure() {
        // Backend.primaryDisplay() returns null when headless, and a caller that has to branch
        // on that will forget to; this is why it does not have to.
        WindowConfig plain = WindowConfig.of("w", 100, 100);
        assertEquals(plain, plain.on(null));
        assertEquals(plain, plain.on(backendWith().primaryDisplay()));
    }

    // -- minimal stubs -------------------------------------------------------

    private static Backend backendWith(Display... displays) {
        return new StubBackend(java.util.List.of(displays));
    }

    private static Display display(String id, boolean primary) {
        return display(id, primary, new ScreenRect(0, 0, 1, 1));
    }

    private static Display display(String id, boolean primary, ScreenRect area) {
        return new Display() {
            @Override public String id() {
                return id;
            }

            @Override public String name() {
                return id;
            }

            @Override public boolean isPrimary() {
                return primary;
            }

            @Override public Resolution currentResolution() {
                return new Resolution(1, 1);
            }

            @Override public java.util.List<Resolution> availableResolutions() {
                return java.util.List.of();
            }

            @Override public ScreenRect bounds() {
                return new ScreenRect(0, 0, 1, 1);
            }

            @Override public ScreenRect workArea() {
                return area;
            }

            @Override public float contentScale() {
                return 1;
            }
        };
    }

    /** Only {@link #displays()} matters; the rest throws to prove it's unused here. */
    private record StubBackend(java.util.List<Display> all) implements Backend {
        @Override public java.util.List<Display> displays() {
            return all;
        }

        @Override public limn.concurrent.UiRuntime uiRuntime() {
            throw new UnsupportedOperationException();
        }

        @Override public NativeWindow createWindow(WindowConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override public void runEventLoop() {
            throw new UnsupportedOperationException();
        }

        @Override public void pushModal(NativeWindow modal, NativeWindow parent) {
            throw new UnsupportedOperationException();
        }

        @Override public void popModal(NativeWindow modal) {
            throw new UnsupportedOperationException();
        }

        @Override public SceneModalHandle pushSceneModal(NativeWindow owner, boolean toolkitScope) {
            throw new UnsupportedOperationException();
        }

        @Override public void signalModalBlocked() {
            throw new UnsupportedOperationException();
        }

        @Override public void stop() {
            throw new UnsupportedOperationException();
        }

        @Override public void close() {
            throw new UnsupportedOperationException();
        }
    }
}
