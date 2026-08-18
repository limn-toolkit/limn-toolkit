/**
 * The headless editing engine behind the text components:
 * {@link limn.components.text.TextEditModel} owns the buffer, the caret, the anchor-based
 * selection, line movement with a sticky goal column and bounded undo/redo, stepping by
 * grapheme cluster so combining marks and ZWJ emoji are never split. It draws nothing and
 * knows no widget, which is what lets the editing rules the single-line and multiline
 * fields share be exercised in tests without a scene.
 */
package limn.components.text;
