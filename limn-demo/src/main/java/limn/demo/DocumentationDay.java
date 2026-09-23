package limn.demo;

import limn.components.date.CalendarView;
import limn.components.date.DateField;
import limn.components.date.DatePicker;
import limn.scene.Widget;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * The day every date widget calls today when a run of the demo must say the same thing twice:
 * 2026-09-09 at noon UTC, the day the dates scene, the site's samples and the accessibility
 * gallery are all built around.
 *
 * <p>One clock for the two runners that need one. The site gallery's capture pins it so a capture
 * taken tomorrow is the capture taken today (a calendar's "today" ring and its reader's "today"
 * moved with the wall clock); the accessibility gallery's date entries pin it so a
 * reader run on any guest, on any day, hears the same today cell and steps an empty segment from
 * the same day (the Fedora guest's clock ran days behind). It is applied by whatever
 * builds a scene and never by the scene functions the site publishes as samples: a reader copying
 * a calendar should not copy a clock pinned to a documentation date.
 */
public final class DocumentationDay {

    /** The fixed clock: 2026-09-09T12:00Z. */
    public static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);

    private DocumentationDay() {
    }

    /**
     * Sets {@link #CLOCK} on every date widget under {@code root}, {@code root} included. A picker
     * hands it to its fields, its time row and its calendar itself, so the walk stops there.
     *
     * @param root the top of the tree to pin
     */
    public static void pin(Widget<?> root) {
        if (root instanceof CalendarView calendar) {
            calendar.setClock(CLOCK);
        } else if (root instanceof DatePicker picker) {
            picker.setClock(CLOCK);
            return;
        } else if (root instanceof DateField field) {
            field.setClock(CLOCK);
        }
        for (Widget<?> child : root.children()) {
            pin(child);
        }
    }
}
