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

    /** Says, in a shared list, each hook it is told and the scene it answers then. */
    private static final class Told extends FixedBox {
        final String name;
        final java.util.List<String> log;
        final java.util.Map<Scene, String> names;

        Told(String name, java.util.List<String> log, java.util.Map<Scene, String> names) {
            super(10, 10);
            this.name = name;
            this.log = log;
            this.names = names;
        }

        @Override protected void onAttached() {
            log.add("attach " + name + "@" + names.get(scene()));
        }

        @Override protected void onDetached() {
            log.add("detach " + name + "@" + names.get(scene()));
        }
    }

    @Test
    void aSubtreeMovedStraightIntoAnotherSceneLeavesTheFirstAndJoinsTheSecond() {
        // Another window's root adopted by this window's column, and another window's overlay pushed
        // as this one's: neither went through a removal, and the walk moved the subtree from one
        // scene into the other telling no widget anything. A widget that registered something in the
        // first scene as it joined, and gives it back through scene() as it leaves, never gave it
        // back there; one holding a GPU resource of the first window's context kept drawing it in
        // the second. Each is now told it left the first, children first, while scene() still
        // answers it, and then that it joined the second, as a removal and an add would tell it.
        for (boolean overlay : new boolean[] {false, true}) {
            String how = overlay ? "another window's overlay pushed here" : "another window's root adopted here";
            java.util.List<String> log = new java.util.ArrayList<>();
            java.util.Map<Scene, String> names = new java.util.HashMap<>();
            Told card = new Told("card", log, names);
            Told inner = new Told("inner", log, names);
            card.add(inner);
            ResourceWidget resource = new ResourceWidget();
            inner.add(resource);
            Scene first;
            if (overlay) {
                first = new Scene(new FixedBox(100, 100));
                first.pushOverlay(card);
            } else {
                first = new Scene(card);
            }
            Scene second = new Scene(new FixedBox(100, 100));
            names.put(first, "first");
            names.put(second, "second");
            names.put(null, "none");
            log.clear();

            if (overlay) {
                second.pushOverlay(card);
            } else {
                second.root().add(card);
            }

            assertEquals(java.util.List.of("detach inner@first", "detach card@first",
                    "attach card@second", "attach inner@second"), log, how);
            assertSame(first, resource.sceneWhileDetaching, how + ": handed its resource to the window it left");
            assertSame(second, resource.sceneWhileAttaching, how);
            assertEquals(2, resource.attached, how);
            assertEquals(1, resource.detached, how);
            assertSame(second, card.scene(), how);
            assertSame(second, resource.scene(), how);
            first.drainPendingDisposals();
            assertTrue(resource.surface.disposed, how + ": freed by the first window's next frame");
        }
    }
}
