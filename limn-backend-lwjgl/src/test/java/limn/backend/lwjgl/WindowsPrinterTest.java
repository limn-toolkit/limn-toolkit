package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the Windows door decides without a printer: the DEVMODE paper a job's page asks the dialog
 * to start from, and which pages the dialog's ranges choose. The dialog and the print path were run
 * on the Windows guest.
 */
class WindowsPrinterTest {

    @Test
    void anA4PageIsTheStandardA4InPortrait() {
        WindowsPrinter.Paper paper = WindowsPrinter.paperFor(595.28f, 841.89f);
        assertEquals(9, paper.size(), "DMPAPER_A4");
        assertEquals(WindowsPrinter.DMORIENT_PORTRAIT, paper.orientation());
    }

    @Test
    void aLetterPageWiderThanTallIsLetterInLandscape() {
        WindowsPrinter.Paper paper = WindowsPrinter.paperFor(792, 612);
        assertEquals(1, paper.size(), "DMPAPER_LETTER");
        assertEquals(WindowsPrinter.DMORIENT_LANDSCAPE, paper.orientation());
    }

    @Test
    void theStandardSizesAreFoundWithinAMillimetre() {
        assertEquals(5, WindowsPrinter.paperFor(612, 1008).size(), "DMPAPER_LEGAL");
        assertEquals(8, WindowsPrinter.paperFor(841.89f, 1190.55f).size(), "DMPAPER_A3");
        assertEquals(11, WindowsPrinter.paperFor(419.53f, 595.28f).size(), "DMPAPER_A5");
        assertEquals(9, WindowsPrinter.paperFor(596, 842).size(), "a quarter of a millimetre off is still A4");
    }

    @Test
    void aPaperOfItsOwnIsAUserSizeInTenthsOfAMillimetre() {
        WindowsPrinter.Paper paper = WindowsPrinter.paperFor(500, 400);
        assertEquals(WindowsPrinter.DMPAPER_USER, paper.size());
        assertEquals(WindowsPrinter.DMORIENT_LANDSCAPE, paper.orientation());
        assertEquals(1764, paper.lengthTenths(), "the long side, as a portrait paper's length");
        assertEquals(1411, paper.widthTenths());
    }

    @Test
    void withoutRangesEveryPageIsPrinted() {
        assertArrayEquals(new int[] {0, 1, 2}, WindowsPrinter.chosenPages(false, new int[] {2, 2}, 3));
    }

    @Test
    void rangesAreHeldToTheDocumentAndNameEachPageOnce() {
        assertArrayEquals(new int[] {2, 3, 0, 4},
                WindowsPrinter.chosenPages(true, new int[] {3, 4, 1, 1, 4, 9}, 5));
        assertArrayEquals(new int[] {1, 2, 3, 4}, WindowsPrinter.chosenPages(true, new int[] {5, 2}, 5),
                "a range written backwards");
        assertArrayEquals(new int[0], WindowsPrinter.chosenPages(true, new int[] {7, 9}, 5));
    }
}
