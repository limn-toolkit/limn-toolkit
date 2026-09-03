package limn.themeeditor;

import limn.components.Theme;
import limn.components.ThemeFormat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The file name a theme is offered under, from a name nothing bounds. */
class ThemeEditorFilesTest {

    @Test
    void aNameBecomesASlugAFileSystemAccepts() {
        assertEquals("ocean-deep." + ThemeFormat.EXTENSION,
                ThemeEditorFiles.fileNameFor(Theme.dark().toBuilder().name("Ocean Deep").build()));
        assertEquals("palette." + ThemeFormat.EXTENSION,
                ThemeEditorFiles.fileNameFor(Theme.dark().toBuilder().name("···").build()));
    }

    @Test
    void aNameOfHundredsOfLettersDoesNotBecomeAFileNameOfHundredsOfLetters() {
        // A theme's name can come from a file and is not length-limited; the
        // slug goes to the platform's save panel as its preselected name.
        String name = "the ".repeat(200) + "end";
        String file = ThemeEditorFiles.fileNameFor(Theme.dark().toBuilder().name(name).build());
        String slug = file.substring(0, file.length() - ThemeFormat.EXTENSION.length() - 1);
        assertTrue(slug.length() <= ThemeEditorFiles.MAX_SLUG_LENGTH, slug);
        assertTrue(slug.startsWith("the-the-"), slug);
        assertTrue(!slug.endsWith("-"), "cut on a word, not left with a dangling separator");
    }
}
