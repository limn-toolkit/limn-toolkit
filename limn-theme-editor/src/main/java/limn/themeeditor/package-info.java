/**
 * The theme editor, a program for authoring a {@link limn.components.Theme} instead of choosing
 * from the fifteen that ship, and saving it as a {@code .limntheme} file. Not API: no application
 * depends on this package, and the site's API reference leaves it out.
 *
 * <p>{@link limn.themeeditor.ThemeEditorApp} is the program, and
 * {@link limn.themeeditor.ThemeEditor} its screen, made of three pieces:
 * {@link limn.themeeditor.ThemePreview}, which paints a palette that is not the process-wide one;
 * {@link limn.themeeditor.ThemeAudit}, which measures a palette against WCAG rather than judging
 * it; and {@link limn.themeeditor.ThemeEditorFiles}, which opens the platform's file chooser. The
 * classes are public so the demo can show the editor as one of its screens.
 *
 * <p><b>Nothing depends on this module, and nothing may.</b> Authoring a palette and
 * wearing one are different jobs: an application ships a {@code Theme}, and the screen that
 * built it has no business on the classpath of every application that draws a button. What
 * crosses the line between them is a value (a {@code Theme}, or the text
 * {@link limn.components.ThemeFormat} writes it as), which is why a palette saved here can
 * be loaded by an application that has never heard of this package.
 */
package limn.themeeditor;
