package limn.scene;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

/** What a screen reader hears while the keyboard drags: the scene's own words, in 21 languages. */
final class DragStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/scene"));
    }

    static final I18nString STARTED = new I18nString("limn.drag.started",
            "Dragging {0}. Tab moves to where it can be dropped, the arrow keys choose the place, "
                    + "Enter drops, Escape cancels.");
    static final I18nString STARTED_UNNAMED = new I18nString("limn.drag.startedUnnamed",
            "Dragging. Tab moves to where it can be dropped, the arrow keys choose the place, "
                    + "Enter drops, Escape cancels.");
    static final I18nString TARGET = new I18nString("limn.drag.target", "Place {0} of {1}");
    static final I18nString NO_TARGET = new I18nString("limn.drag.noTarget", "Nothing here takes it.");
    static final I18nString DROPPED = new I18nString("limn.drag.dropped", "Dropped.");
    static final I18nString CANCELLED = new I18nString("limn.drag.cancelled", "Drag cancelled.");

    private DragStrings() {
    }
}
