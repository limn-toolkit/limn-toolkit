package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.libc.LibCLocale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The C library's locale the macOS backend leaves the process in. */
class MacApplicationTest {

    /**
     * Numbers written the way a terminal's {@code LANG=pt_BR.UTF-8} writes them hid the system's
     * check marks and submenu arrows; the backend puts them back in {@code "C"}, and the other
     * categories stay the environment's.
     */
    @Test
    void numbersGoBackToTheCLocaleAndTheRestStays() {
        assumeTrue(limn.backend.Platform.current().isMacOs(), "the category is set on macOS only");
        String numbers = MacApplication.cLocale(LibCLocale.LC_NUMERIC);
        String characters = MacApplication.cLocale(LibCLocale.LC_CTYPE);
        try {
            String comma = LibCLocale.setlocale(LibCLocale.LC_NUMERIC, "pt_BR.UTF-8");
            assumeTrue(comma != null, "this machine has no pt_BR locale to start from");
            LibCLocale.setlocale(LibCLocale.LC_CTYPE, "pt_BR.UTF-8");

            assertEquals("C", MacApplication.numbersInTheCLocale());
            assertEquals("C", MacApplication.cLocale(LibCLocale.LC_NUMERIC));
            assertEquals("pt_BR.UTF-8", MacApplication.cLocale(LibCLocale.LC_CTYPE));
        } finally {
            LibCLocale.setlocale(LibCLocale.LC_NUMERIC, numbers == null ? "C" : numbers);
            LibCLocale.setlocale(LibCLocale.LC_CTYPE, characters == null ? "C" : characters);
        }
    }
}
