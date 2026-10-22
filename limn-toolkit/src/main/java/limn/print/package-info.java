/**
 * Printing and PDF: a {@link limn.print.Document} is widgets the toolkit cuts into pages, between
 * rows and lines, with a header and a footer on every page; it is written as a PDF file by
 * {@link limn.print.Document#writePdf(java.nio.file.Path)}, printed through the system's own dialog
 * by {@link limn.print.Document#print(limn.backend.NativeWindow)}, and shown before either by a
 * {@link limn.print.PrintPreview}.
 *
 * <p>A page is drawn by the same widgets and the same theme a window draws, recorded once: the PDF,
 * the preview and the printer all show that one recording. Text stays text in the PDF (it can be
 * selected, copied and searched); what has no form in PDF (a soft shadow) is a picture at the
 * document's resolution.
 */
@NullMarked
package limn.print;

import org.jspecify.annotations.NullMarked;
