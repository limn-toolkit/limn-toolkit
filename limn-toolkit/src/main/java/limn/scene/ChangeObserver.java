package limn.scene;

/**
 * A watcher: told about every change to a widget, whatever moved it.
 *
 * <p>Called on the UI thread, synchronously, from inside the call that made the change and after
 * that widget's own state has settled — never from inside a paint. Any number of watchers may
 * watch one widget, with no coordination between them, no ability to disturb each other or the
 * application's handler, and no ability to be disturbed by them: each is invoked inside its own
 * {@code try}, and a throw is contained and reported.
 *
 * <p>The source is a {@link Widget} because a watcher-channel source is a node in a widget tree.
 * The five public types that are not widgets — {@code ButtonGroup}, {@code Menu},
 * {@code MenuItem}, {@code PopupMenu} and {@code Dialog} — are in no tree, so they announce
 * nothing here and keep their handler slots; a radio group's change is fully recoverable from the
 * two radio buttons, which are widgets.
 *
 * <p>A watcher may read the widget, and may mutate anything: a nested change is announced
 * synchronously, in full, before the outer dispatch continues. What it must not assume is a
 * frame — a notification is delivered before any layout or paint the mutation asked for, so a
 * watcher that reads geometry reads the geometry of the last pass.
 *
 * @see Change
 */
@FunctionalInterface
public interface ChangeObserver {

    /**
     * Tells this watcher that {@code source} changed.
     *
     * @param source the widget that changed
     * @param change what changed on it, and what moved it
     */
    void changed(Widget<?> source, Change change);
}
