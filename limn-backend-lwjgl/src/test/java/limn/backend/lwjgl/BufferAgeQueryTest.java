package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The part of the buffer-age query that needs no driver: reading an extension string. What the
 * drivers answer was measured on the Linux guests (the popups that came up empty, 2026-09-25) and is
 * checked there, since no test here has a GLX or EGL surface to ask.
 */
class BufferAgeQueryTest {

    @Test
    void anExtensionIsAWholeWordOfTheString() {
        String egl = "EGL_KHR_image_base EGL_EXT_buffer_age EGL_KHR_partial_update";
        assertTrue(BufferAgeQuery.hasExtension(egl, "EGL_EXT_buffer_age"));
        assertTrue(BufferAgeQuery.hasExtension(" GLX_EXT_buffer_age\n", "GLX_EXT_buffer_age"));
        assertFalse(BufferAgeQuery.hasExtension("EGL_EXT_buffer_age_foo EGL_KHR_x", "EGL_EXT_buffer_age"));
        assertFalse(BufferAgeQuery.hasExtension("", "EGL_EXT_buffer_age"));
        assertFalse(BufferAgeQuery.hasExtension(null, "EGL_EXT_buffer_age"));
    }
}
