package limn.backend;

import java.util.List;

/**
 * A monitor/screen, normalized so every part of the toolkit reads display
 * information the same way instead of each class poking at the platform: its
 * identity ({@link #id()}/{@link #name()}), the mode currently set
 * ({@link #currentResolution()}), all supported modes
 * ({@link #availableResolutions()}), the physical {@link #bounds()}, the usable
 * {@link #workArea()} (minus taskbar/dock), and the DPI {@link #contentScale()}.
 *
 * <p>Enumerate displays through {@link Backend#displays()} /
 * {@link Backend#primaryDisplay()}; a window reports the display it currently
 * sits on via {@link NativeWindow#display()}. Values are read live from the
 * platform, so they reflect resolution/monitor changes. UI-thread only.
 */
public interface Display {

    /** @return a stable-within-session identifier (e.g. {@code "display-0"}). */
    String id();

    /** @return a human-readable name (e.g. {@code "Built-in Retina Display"}). */
    String name();

    /** @return whether this is the primary display. */
    boolean isPrimary();

    /** @return the mode currently set on this display. */
    Resolution currentResolution();

    /** @return every distinct mode this display supports. */
    List<Resolution> availableResolutions();

    /** @return the full monitor rectangle in screen coordinates. */
    ScreenRect bounds();

    /** @return the usable area (monitor minus OS chrome) in screen coordinates. */
    ScreenRect workArea();

    /** @return the monitor's content (DPI) scale: 1.0, 1.25, 1.5, 2.0… */
    float contentScale();
}
