package limn.backend;

import java.util.List;

/**
 * Which graphics context a backend actually obtained, and which of the optional
 * capabilities the toolkit uses are present on it. Read it with
 * {@link Backend#graphicsInfo()}.
 *
 * <p>This exists because "it will not start on that machine" and "it is slow on
 * that machine" cannot be answered from a stack trace. An OpenGL above 1.1 is
 * not part of Windows (it arrives with the GPU vendor's driver), so the same
 * application meets vendor drivers, translation layers over Direct3D, and pure
 * software rasterizers, and nothing but the strings below tells them apart.
 *
 * <p>When {@link #failure()} is non-null no context was obtained: only
 * {@link #windowPlatform()} and {@link #windowLibrary()} carry an answer, and
 * every other field is empty, zero or false.
 *
 * @param windowPlatform  the windowing system in use: {@code Cocoa},
 *                        {@code Win32}, {@code X11}, {@code Wayland} or
 *                        {@code Null}. Chosen when the process starts and not
 *                        when it was built, so one binary reports different
 *                        values on one machine depending on the session it was
 *                        launched from
 * @param windowLibrary   version string of the native windowing library
 * @param api             client API and version the context reports, such as
 *                        {@code OpenGL 3.3 core}; read back from the context,
 *                        not from what was requested
 * @param contextApi      how the context was created: {@code native},
 *                        {@code EGL} or {@code OSMesa}. EGL on a desktop
 *                        platform means the context came from a translation
 *                        layer rather than the platform's own driver
 * @param vendor          driver's vendor string, verbatim
 * @param renderer        driver's device string, verbatim. It names the
 *                        emulation rather than a GPU when there is one:
 *                        {@code llvmpipe} for software, a Direct3D or Vulkan
 *                        device name for a translation layer
 * @param version         driver's version string, verbatim
 * @param shadingLanguage highest shading-language version the context accepts
 * @param maxSamples      largest multisample count the context accepts;
 *                        multisample requests are clamped to it
 * @param timerQueries    whether GPU-side durations can be measured. Without
 *                        it a performance monitor has no GPU figure to show;
 *                        nothing else changes
 * @param floatColorBuffer whether a 16-bit float colour buffer can be rendered
 *                        to. The 3D subsystem renders to one and has no
 *                        8-bit fallback, so it does not run where this is false
 * @param norm16Textures  whether 16-bit normalized single-channel textures
 *                        exist. A 10-bit video plane is uploaded as one
 * @param extensions      every extension the context advertises, sorted; empty
 *                        when there is no context
 * @param failure         why no context exists, or {@code null} when one does
 */
public record GraphicsInfo(String windowPlatform, String windowLibrary, String api,
                           String contextApi, String vendor, String renderer, String version,
                           String shadingLanguage, int maxSamples, boolean timerQueries,
                           boolean floatColorBuffer, boolean norm16Textures,
                           List<String> extensions, String failure) {

    /** What a backend that cannot describe its graphics stack reports. */
    public static final GraphicsInfo NONE =
            unavailable("unknown", "unknown", "this backend reports no graphics context");

    public GraphicsInfo {
        windowPlatform = blankIfNull(windowPlatform);
        windowLibrary = blankIfNull(windowLibrary);
        api = blankIfNull(api);
        contextApi = blankIfNull(contextApi);
        vendor = blankIfNull(vendor);
        renderer = blankIfNull(renderer);
        version = blankIfNull(version);
        shadingLanguage = blankIfNull(shadingLanguage);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /** A report for a machine that gave no context, carrying what is knowable without one. */
    public static GraphicsInfo unavailable(String windowPlatform, String windowLibrary,
                                           String failure) {
        return new GraphicsInfo(windowPlatform, windowLibrary, "", "", "", "", "", "", 0,
                false, false, false, List.of(), failure);
    }

    /** @return whether a graphics context was obtained at all */
    public boolean available() {
        return failure == null;
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
