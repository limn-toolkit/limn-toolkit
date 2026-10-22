package limn.backend.spi;

import java.util.Objects;

/**
 * What a {@link PrinterPeer} hands the system: a PDF, the job's name, and the paper the document
 * was laid out on, which the system's dialog starts from.
 *
 * <p>The bytes are the caller's and are not copied: a peer reads them and does not change them.
 *
 * @param title        the job's name, shown in the system's queue and as the dialog's title where
 *                     a dialog has one; never blank
 * @param pdf          the document, a complete PDF file
 * @param paperWidth   the width of the paper the document was laid out on, in points (1/72 inch),
 *                     or 0 when it is not known (a PDF the application received)
 * @param paperHeight  its height in points, or 0 when it is not known
 * @param pageCount    how many pages the document has, or -1 when it is not known
 */
public record PrintJob(String title, byte[] pdf, float paperWidth, float paperHeight, int pageCount) {

    public PrintJob {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(pdf, "pdf");
        if (title.isBlank()) {
            throw new IllegalArgumentException("a print job needs a name");
        }
        if (!(paperWidth >= 0) || !(paperHeight >= 0)) {
            throw new IllegalArgumentException("paper " + paperWidth + " x " + paperHeight);
        }
    }

    /** @return whether the paper is known, as it is for a document Limn laid out */
    public boolean knowsPaper() {
        return paperWidth > 0 && paperHeight > 0;
    }
}
