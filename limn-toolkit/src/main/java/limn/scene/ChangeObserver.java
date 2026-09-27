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
 * <p><b>What this channel does not say</b>, by design and as part of the 1.0 contract:
 * <ul>
 *   <li>A menu's highlighted item, a check item's state and whether a popup menu or dialog is open
 *       reach no watcher. What does arrive is coarse: opening or closing a popup is a
 *       {@code CHILDREN} change on the overlay root of the scene that hosts it, and a
 *       {@code MenuBar} announces its own {@code EXPANDED}. The accessibility tree carries all of
 *       it, through synthetic children the owning widget declares.</li>
 *   <li>A subtree's {@code setLocale} or {@code setInheritanceHost} changes every name, description
 *       and formatted value under it and announces only {@code LAYOUT}. A watcher that follows text
 *       re-reads it after either, and after {@code I18n.observeChanges} fires for the process's
 *       language.</li>
 * </ul>
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
