package limn.accessibility.internal;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The publish step's way into {@link Accessibility}: the methods the scene's accessible walk, the
 * bridges' tests and the model's own lab call to build and read a tree, which a widget describing
 * itself in {@code onAccessibility} never calls. They were public on the class every widget is
 * handed, and so part of the API a 1.0 freezes; they are package-private there now, and reached
 * here. Not API.
 *
 * <p>{@link Accessibility} installs the hook from its own static initializer, and only it can: an
 * install from any other class is refused, so the hook cannot be replaced by whoever loads first.
 */
public final class AccessibilityAccess {

    /** What {@link Accessibility} lends this class. */
    public interface Hook {
        boolean hasCaretAt(Accessibility a, int index);
        void caretAt(Accessibility a, int index, float[] out);
        void caretInScene(Accessibility a, int index, float x, float y, float width, float height);
        int begin(Accessibility a, long id, int parent, Locale locale, float x, float y, float width, float height);
        void beginChildDescription(Accessibility a);
        void beginChildIdentity(Accessibility a);
        void beginWalk(Accessibility a, float sceneWidth, float sceneHeight, Locale sceneLocale);
        long carriedOver(Accessibility a);
        void clipShowingAt(Accessibility a, int index, float x0, float y0, float x1, float y1);
        boolean declaresNothing(Accessibility a);
        int delegatedVerbsAt(Accessibility a, int index);
        void endChildDescription(Accessibility a);
        void endChildIdentity(Accessibility a);
        List<AccessibleEvent> events(Accessibility a);
        void foreignActiveDescendant(Accessibility a, long id);
        long foreignControllerTargetFrom(Accessibility a, long nodeId);
        void forgetPublished(Accessibility a);
        void freeVerbs(Accessibility a);
        boolean hasAccessKey(Accessibility a);
        boolean hasChildren(Accessibility a);
        boolean hasDescription(Accessibility a);
        boolean hasPendingHost(Accessibility a);
        boolean hasPendingKey(Accessibility a);
        long idAt(Accessibility a, int index);
        void identifier(Accessibility a, @Nullable String id);
        long identify(Accessibility a, long owner, long key);
        void inherited(Accessibility a, boolean enabled, boolean visible, boolean showing, boolean focusable, boolean focused);
        void inheritedAt(Accessibility a, int index, boolean enabled, boolean visible, boolean showing);
        void inoperableAt(Accessibility a, int index);
        boolean isEnabledAt(Accessibility a, int index);
        boolean isIgnored(Accessibility a);
        boolean isSyntheticAt(Accessibility a, int index);
        void keyboardPopupAt(Accessibility a, int slot);
        void markHosted(Accessibility a);
        long mint(Accessibility a);
        int pendingHostIndex(Accessibility a, int owner);
        long pendingKey(Accessibility a);
        void placeSyntheticAt(Accessibility a, int index, float x, float y, float width, float height);
        void relationAt(Accessibility a, int index, Accessible.Relation kind, @Nullable Object target);
        void resolveRelations(Accessibility a, Accessibility.RelationResolver resolve);
        long sceneTag(Accessibility a);
        long sceneTagOf(long id);
        void syntheticBoxAt(Accessibility a, int index, float[] out);
        long syntheticKeyAt(Accessibility a, int index);
    }

    private static volatile @Nullable Hook hook;

    private AccessibilityAccess() {
    }

    /**
     * Installs the hook: {@link Accessibility}'s static initializer, once.
     *
     * @param accessibilityHook what the class lends
     * @throws IllegalStateException from any other class, or a second time
     */
    public static void install(Hook accessibilityHook) {
        Class<?> caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass();
        if (caller != Accessibility.class || hook != null) {
            throw new IllegalStateException("only Accessibility installs its access hook, once");
        }
        hook = accessibilityHook;
    }

    private static Hook hook() {
        Hook installed = hook;
        if (installed == null) {
            try {
                Class.forName(Accessibility.class.getName(), true, AccessibilityAccess.class.getClassLoader());
            } catch (ClassNotFoundException impossible) {
                throw new IllegalStateException(impossible);
            }
            installed = hook;
        }
        return installed;
    }

    /** See {@code Accessibility.begin}. */
    public static int begin(Accessibility a, long id, int parent, Locale locale, float x, float y, float width, float height) {
        return hook().begin(a, id, parent, locale, x, y, width, height);
    }

    /** See {@code Accessibility.beginChildDescription}. */
    public static void beginChildDescription(Accessibility a) {
        hook().beginChildDescription(a);
    }

    /** See {@code Accessibility.beginChildIdentity}. */
    public static void beginChildIdentity(Accessibility a) {
        hook().beginChildIdentity(a);
    }

    /** See {@code Accessibility.beginWalk}. */
    public static void beginWalk(Accessibility a, float sceneWidth, float sceneHeight, Locale sceneLocale) {
        hook().beginWalk(a, sceneWidth, sceneHeight, sceneLocale);
    }

    /** See {@code Accessibility.carriedOver}. */
    public static long carriedOver(Accessibility a) {
        return hook().carriedOver(a);
    }

    /** See {@code Accessibility.clipShowingAt}. */
    public static void clipShowingAt(Accessibility a, int index, float x0, float y0, float x1, float y1) {
        hook().clipShowingAt(a, index, x0, y0, x1, y1);
    }

    /** See {@code Accessibility.declaresNothing}. */
    public static boolean declaresNothing(Accessibility a) {
        return hook().declaresNothing(a);
    }

    /** See {@code Accessibility.delegatedVerbsAt}. */
    public static int delegatedVerbsAt(Accessibility a, int index) {
        return hook().delegatedVerbsAt(a, index);
    }

    /** See {@code Accessibility.endChildDescription}. */
    public static void endChildDescription(Accessibility a) {
        hook().endChildDescription(a);
    }

    /** See {@code Accessibility.endChildIdentity}. */
    public static void endChildIdentity(Accessibility a) {
        hook().endChildIdentity(a);
    }

    /** See {@code Accessibility.events}. */
    public static List<AccessibleEvent> events(Accessibility a) {
        return hook().events(a);
    }

    /** See {@code Accessibility.foreignActiveDescendant}. */
    public static void foreignActiveDescendant(Accessibility a, long id) {
        hook().foreignActiveDescendant(a, id);
    }

    /** See {@code Accessibility.foreignControllerTargetFrom}. */
    public static long foreignControllerTargetFrom(Accessibility a, long nodeId) {
        return hook().foreignControllerTargetFrom(a, nodeId);
    }

    /** See {@code Accessibility.forgetPublished}. */
    public static void forgetPublished(Accessibility a) {
        hook().forgetPublished(a);
    }

    /** See {@code Accessibility.freeVerbs}. */
    public static void freeVerbs(Accessibility a) {
        hook().freeVerbs(a);
    }

    /** See {@code Accessibility.hasAccessKey}. */
    public static boolean hasAccessKey(Accessibility a) {
        return hook().hasAccessKey(a);
    }

    /** See {@code Accessibility.hasChildren}. */
    public static boolean hasChildren(Accessibility a) {
        return hook().hasChildren(a);
    }

    /** See {@code Accessibility.hasDescription}. */
    public static boolean hasDescription(Accessibility a) {
        return hook().hasDescription(a);
    }

    /** See {@code Accessibility.hasPendingHost}. */
    public static boolean hasPendingHost(Accessibility a) {
        return hook().hasPendingHost(a);
    }

    /** See {@code Accessibility.hasPendingKey}. */
    public static boolean hasPendingKey(Accessibility a) {
        return hook().hasPendingKey(a);
    }

    /** See {@code Accessibility.idAt}. */
    public static long idAt(Accessibility a, int index) {
        return hook().idAt(a, index);
    }

    /** See {@code Accessibility.identifier}. */
    public static void identifier(Accessibility a, @Nullable String id) {
        hook().identifier(a, id);
    }

    /** See {@code Accessibility.identify}. */
    public static long identify(Accessibility a, long owner, long key) {
        return hook().identify(a, owner, key);
    }

    /** See {@code Accessibility.inherited}. */
    public static void inherited(Accessibility a, boolean enabled, boolean visible, boolean showing, boolean focusable, boolean focused) {
        hook().inherited(a, enabled, visible, showing, focusable, focused);
    }

    /** See {@code Accessibility.inheritedAt}. */
    public static void inheritedAt(Accessibility a, int index, boolean enabled, boolean visible, boolean showing) {
        hook().inheritedAt(a, index, enabled, visible, showing);
    }

    /** See {@code Accessibility.inoperableAt}. */
    public static void inoperableAt(Accessibility a, int index) {
        hook().inoperableAt(a, index);
    }

    /** See {@code Accessibility.isEnabledAt}. */
    public static boolean isEnabledAt(Accessibility a, int index) {
        return hook().isEnabledAt(a, index);
    }

    /** See {@code Accessibility.isIgnored}. */
    public static boolean isIgnored(Accessibility a) {
        return hook().isIgnored(a);
    }

    /** See {@code Accessibility.isSyntheticAt}. */
    public static boolean isSyntheticAt(Accessibility a, int index) {
        return hook().isSyntheticAt(a, index);
    }

    /** See {@code Accessibility.keyboardPopupAt}. */
    public static void keyboardPopupAt(Accessibility a, int slot) {
        hook().keyboardPopupAt(a, slot);
    }

    /** See {@code Accessibility.markHosted}. */
    public static void markHosted(Accessibility a) {
        hook().markHosted(a);
    }

    /** See {@code Accessibility.mint}. */
    public static long mint(Accessibility a) {
        return hook().mint(a);
    }

    /** See {@code Accessibility.pendingHostIndex}. */
    public static int pendingHostIndex(Accessibility a, int owner) {
        return hook().pendingHostIndex(a, owner);
    }

    /** See {@code Accessibility.pendingKey}. */
    public static long pendingKey(Accessibility a) {
        return hook().pendingKey(a);
    }

    /** See {@code Accessibility.placeSyntheticAt}. */
    public static void placeSyntheticAt(Accessibility a, int index, float x, float y, float width, float height) {
        hook().placeSyntheticAt(a, index, x, y, width, height);
    }

    /** See {@code Accessibility.relationAt}. */
    public static void relationAt(Accessibility a, int index, Accessible.Relation kind, @Nullable Object target) {
        hook().relationAt(a, index, kind, target);
    }

    /** See {@code Accessibility.resolveRelations}. */
    public static void resolveRelations(Accessibility a, Accessibility.RelationResolver resolve) {
        hook().resolveRelations(a, resolve);
    }

    /** See {@code Accessibility.sceneTag}. */
    public static long sceneTag(Accessibility a) {
        return hook().sceneTag(a);
    }

    /** See {@code Accessibility.sceneTagOf}. */
    public static long sceneTagOf(long id) {
        return hook().sceneTagOf(id);
    }

    /** See {@code Accessibility.syntheticBoxAt}. */
    public static void syntheticBoxAt(Accessibility a, int index, float[] out) {
        hook().syntheticBoxAt(a, index, out);
    }

    /** See {@code Accessibility.syntheticKeyAt}. */
    public static long syntheticKeyAt(Accessibility a, int index) {
        return hook().syntheticKeyAt(a, index);
    }

    /** See {@code Accessibility.hasCaretAt}. */
    public static boolean hasCaretAt(Accessibility a, int index) {
        return hook().hasCaretAt(a, index);
    }

    /** See {@code Accessibility.caretAt}. */
    public static void caretAt(Accessibility a, int index, float[] out) {
        hook().caretAt(a, index, out);
    }

    /** See {@code Accessibility.caretInScene}. */
    public static void caretInScene(Accessibility a, int index, float x, float y, float width, float height) {
        hook().caretInScene(a, index, x, y, width, height);
    }
}
