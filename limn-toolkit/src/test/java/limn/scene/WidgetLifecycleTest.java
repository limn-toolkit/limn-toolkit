package limn.scene;

import limn.graphics.GpuSurface;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code onAttached}/{@code onDetached} fire on the scene transition, and a GPU
 * resource handed to {@link Scene#disposeLater} is released on the next frame,
 * not inline (disposal needs the owning GL context, current only in a frame).
 * This is the mechanism that stops the 3D viewport leaking its surface.
 */
class WidgetLifecycleTest extends SceneTestBase {

    /** A GpuSurface stub that records whether it was disposed. */
    private static final class StubSurface implements GpuSurface {
        boolean disposed;

        @Override public int widthPx() { return 1; }
        @Override public int heightPx() { return 1; }
        @Override public void resize(int w, int h) { }
        @Override public void dispose() { disposed = true; }
    }

    /**
     * Mimics a 3D viewport: owns a surface, releases it via disposeLater on detach.
     *
     * <p>Written the way the API means it to be: the scene is read from
     * {@link Widget#scene()} inside {@code onDetached}, with no field mirroring it.
     * That only works because the field is cleared after the hook returns; before
     * that, every widget releasing something scene-owned had to keep a copy.
     */
    private static final class ResourceWidget extends FixedBox {
        final StubSurface surface = new StubSurface();
        int attached;
        int detached;
        Scene sceneWhileAttaching;
        Scene sceneWhileDetaching;

        ResourceWidget() {
            super(10, 10);
        }

        @Override protected void onAttached() {
            attached++;
            sceneWhileAttaching = scene();
        }

        @Override protected void onDetached() {
            detached++;
            sceneWhileDetaching = scene();
            if (scene() != null) {
                scene().disposeLater(surface);
            }
        }
    }

    @Test
    void hooksFireOnAttachAndDetach() {
        Scene scene = new Scene(new FixedBox(100, 100));
        ResourceWidget w = new ResourceWidget();

        scene.root().add(w);
        assertEquals(1, w.attached, "onAttached on add to a scene");
        assertEquals(0, w.detached);

        scene.root().remove(w);
        assertEquals(1, w.detached, "onDetached on remove");
    }

    @Test
    void bothHooksSeeTheSceneAndDetachClearsItAfterwards() {
        Scene scene = new Scene(new FixedBox(100, 100));
        ResourceWidget w = new ResourceWidget();

        scene.root().add(w);
        assertSame(scene, w.sceneWhileAttaching, "onAttached must see the scene it joined");

        scene.root().remove(w);
        // The contract: a widget being removed can still answer which scene it is
        // leaving; that is what makes disposeLater reachable without a field.
        assertSame(scene, w.sceneWhileDetaching, "onDetached must see the scene it is leaving");
        assertNull(w.scene(), "and the widget is out of the scene once the hook returns");
    }

    @Test
    void everyWidgetInADetachedSubtreeStillReachesTheScene() {
        // Bottom-up, so a child runs before its parent, and both need the scene.
        // A parent that cleared its own first would strand its children with null.
        Scene scene = new Scene(new FixedBox(100, 100));
        ResourceWidget parent = new ResourceWidget();
        ResourceWidget child = new ResourceWidget();
        parent.add(child);
        scene.root().add(parent);

        scene.root().remove(parent);

        assertSame(scene, child.sceneWhileDetaching, "the child lost the scene early");
        assertSame(scene, parent.sceneWhileDetaching);
        assertNull(child.scene());
        assertNull(parent.scene());
    }

    @Test
    void disposalIsDeferredToTheNextFrame() {
        Scene scene = new Scene(new FixedBox(100, 100));
        ResourceWidget w = new ResourceWidget();
        scene.root().add(w);

        scene.root().remove(w);
        assertFalse(w.surface.disposed, "not disposed inline (no GL context outside a frame)");

        scene.drainPendingDisposals(); // stands in for the next frame's top
        assertTrue(w.surface.disposed, "disposed once a frame runs with the context current");
    }

    /**
     * A memo resolved <em>inside</em> {@code onDetached} dies with the detach. The funnel bumps
     * every axis's epoch before the hook runs, and the hook may legally read any axis — the
     * scene it is leaving is still there to answer. Without a reset once the field clears, that
     * read would be stamped current and survive the detach as the left scene's answer, held by
     * a widget that no longer has a scene at all.
     */
    @Test
    void aMemoResolvedInsideOnDetachedDiesWithTheDetach() {
        Scene scene = new Scene(new FixedBox(100, 100));
        java.util.Locale hebrew = java.util.Locale.forLanguageTag("he");
        scene.setLayoutDirection(LayoutDirection.RTL);
        scene.setControlSize(ControlSize.LARGE);
        scene.setLocale(hebrew);
        try {
            final LayoutDirection[] sawDirection = new LayoutDirection[1];
            final ControlSize[] sawSize = new ControlSize[1];
            final java.util.Locale[] sawLocale = new java.util.Locale[1];
            FixedBox reader = new FixedBox(10, 10) {
                @Override protected void onDetached() {
                    sawDirection[0] = layoutDirection();
                    sawSize[0] = controlSize();
                    sawLocale[0] = locale();
                }
            };
            scene.root().add(reader);
            scene.root().remove(reader);

            assertEquals(LayoutDirection.RTL, sawDirection[0],
                    "the hook reads the scene it is leaving, which is the order the field "
                            + "clears in");
            assertEquals(ControlSize.LARGE, sawSize[0]);
            assertEquals(hebrew, sawLocale[0]);
            assertEquals(LayoutDirection.processDefault(), reader.layoutDirection(),
                    "detached, the widget resolves as a fresh widget would, "
                            + "rather than keeping the left scene's answer");
            assertEquals(ControlSize.processDefault(), reader.controlSize());
            assertEquals(limn.i18n.I18n.processLocale(), reader.locale());
        } finally {
            scene.setLocale(null); // releases the retain; process statics outlive the test
        }
    }
}
