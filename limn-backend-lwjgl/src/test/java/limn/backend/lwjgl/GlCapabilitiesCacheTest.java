package limn.backend.lwjgl;

import limn.backend.Platform;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What decides whether two windows' contexts share their GL capabilities, with no GL: the key,
 * the holds, and the platform default. That real windows do share them, and draw with them, is
 * {@link GlCapabilitiesReuseTest}'s.
 */
class GlCapabilitiesCacheTest {

    private static final String EXTENSIONS = "GL_ARB_a GL_EXT_b ";
    private static final GlCapabilitiesCache.Key POPUP = key(false, "Vendor", "Renderer A", "3.3 core", 0, 1,
            EXTENSIONS);

    private static GlCapabilitiesCache.Key key(boolean softwareGl, String vendor, String renderer, String version,
            int flags, int profile, String extensions) {
        return new GlCapabilitiesCache.Key(softwareGl, vendor, renderer, version, flags, profile, extensions);
    }

    @Test
    void contextsOfOneKindShareWhatTheFirstCreated() {
        GlCapabilitiesCache<Object> cache = new GlCapabilitiesCache<>(true);
        AtomicInteger created = new AtomicInteger();
        Object first = cache.acquire(POPUP, () -> "caps " + created.incrementAndGet());
        Object second = cache.acquire(key(false, "Vendor", "Renderer A", "3.3 core", 0, 1, EXTENSIONS),
                () -> "caps " + created.incrementAndGet());
        assertSame(first, second);
        assertEquals(1, created.get(), "the second context resolved nothing");
    }

    @Test
    void anyDifferenceInWhatTheContextSaysOrInItsKindCreatesAgain() {
        GlCapabilitiesCache<Object> cache = new GlCapabilitiesCache<>(true);
        AtomicInteger created = new AtomicInteger();
        Object popup = cache.acquire(POPUP, () -> new Object());
        GlCapabilitiesCache.Key[] others = {
                key(true, "Vendor", "Renderer A", "3.3 core", 0, 1, EXTENSIONS),
                key(false, "Other vendor", "Renderer A", "3.3 core", 0, 1, EXTENSIONS),
                key(false, "Vendor", "Renderer B", "3.3 core", 0, 1, EXTENSIONS),
                key(false, "Vendor", "Renderer A", "4.1 core", 0, 1, EXTENSIONS),
                key(false, "Vendor", "Renderer A", "3.3 core", 1, 1, EXTENSIONS),
                key(false, "Vendor", "Renderer A", "3.3 core", 0, 2, EXTENSIONS),
                key(false, "Vendor", "Renderer A", "3.3 core", 0, 1, "GL_ARB_a "),
                new GlCapabilitiesCache.Key(false, null, null, null, 0, 0, ""),
        };
        for (GlCapabilitiesCache.Key other : others) {
            Object caps = cache.acquire(other, () -> {
                created.incrementAndGet();
                return new Object();
            });
            assertNotSame(popup, caps, other::toString);
        }
        assertEquals(others.length, created.get());
        assertEquals(others.length + 1, cache.size());
    }

    @Test
    void theEntryGoesWithItsLastHolder() {
        GlCapabilitiesCache<Object> cache = new GlCapabilitiesCache<>(true);
        Object first = cache.acquire(POPUP, Object::new);
        cache.acquire(POPUP, Object::new);
        cache.release(POPUP);
        assertSame(first, cache.acquire(POPUP, Object::new), "one window still holds it");
        cache.release(POPUP);
        cache.release(POPUP);
        assertEquals(0, cache.size());
        assertNotSame(first, cache.acquire(POPUP, Object::new),
                "a driver unloaded with its last context must not leave its addresses behind");
    }

    @Test
    void aCacheThatDoesNotReuseCreatesEveryTimeAndKeepsNothing() {
        GlCapabilitiesCache<Object> cache = new GlCapabilitiesCache<>(false);
        assertNotSame(cache.acquire(POPUP, Object::new), cache.acquire(POPUP, Object::new));
        cache.release(POPUP);
        assertEquals(0, cache.size());
    }

    @Test
    void theThreeDesktopsReuseByDefaultAndThePropertyWins() {
        assertTrue(GlCapabilitiesCache.reusesByDefault(Platform.Os.MACOS, null));
        assertTrue(GlCapabilitiesCache.reusesByDefault(Platform.Os.LINUX, null));
        assertTrue(GlCapabilitiesCache.reusesByDefault(Platform.Os.WINDOWS, null));
        assertFalse(GlCapabilitiesCache.reusesByDefault(Platform.Os.OTHER, null));
        assertFalse(GlCapabilitiesCache.reusesByDefault(Platform.Os.MACOS, "FALSE"));
        assertFalse(GlCapabilitiesCache.reusesByDefault(Platform.Os.WINDOWS, "false"));
        assertTrue(GlCapabilitiesCache.reusesByDefault(Platform.Os.OTHER, "true"));
        assertTrue(GlCapabilitiesCache.reusesByDefault(Platform.Os.LINUX, "maybe"));
    }
}
