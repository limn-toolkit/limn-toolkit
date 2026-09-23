/**
 * The headless editing engine behind the text components:
 * {@link limn.components.internal.text.TextEditModel} owns the buffer, the caret, the anchor-based
 * selection, line movement with a sticky goal column and bounded undo/redo, stepping by
 * grapheme cluster so combining marks and ZWJ emoji are never split. It draws nothing and
 * knows no widget, which is what lets the editing rules the single-line and multiline
 * fields share be exercised in tests without a scene.
 *
 * <p><b>Not API.</b> The module exports this package only to the Limn modules that share
 * it, and an application should not name it; it changes without notice.
 */
package limn.components.internal.text;
