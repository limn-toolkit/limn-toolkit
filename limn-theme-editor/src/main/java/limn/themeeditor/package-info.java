/**
 * A screen for authoring a {@link limn.components.Theme}, so that an application's users
 * can build a palette instead of choosing from the fifteen that ship.
 *
 * <p>{@link limn.themeeditor.ThemeEditor} is the whole of it for most callers: hand it a
 * palette, embed it, and listen for what comes out. Beside it sit the three pieces it is
 * made of, each usable on its own: {@link limn.themeeditor.ThemePreview}, which paints a
 * palette that is not the process-wide one; {@link limn.themeeditor.ThemeAudit}, which
 * measures a palette against WCAG rather than judging it; and
 * {@link limn.themeeditor.ThemeEditorFiles}, the optional half that opens the platform's
 * file chooser.
 *
 * <p><b>Nothing depends on this module, and nothing may.</b> Authoring a palette and
 * wearing one are different jobs: an application ships a {@code Theme}, and the screen that
 * built it has no business on the classpath of every application that draws a button. What
 * crosses the line between them is a value (a {@code Theme}, or the text
 * {@link limn.components.ThemeFormat} writes it as), which is why a palette saved here can
 * be loaded by an application that has never heard of this package.
 */
package limn.themeeditor;
