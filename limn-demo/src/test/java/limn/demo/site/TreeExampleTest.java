package limn.demo.site;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The guide's entries have path identity: a tree identifies a node by {@code equals} and refuses
 * the same node in two places (ADR 044 §2, amended 2026-09-14), and every folder read off a disk
 * has a {@code classes} in it. Two files of one name under two folders are two nodes here, and
 * a row still shows the name alone.
 */
class TreeExampleTest {

    @Test
    void twoFilesOfOneNameUnderTwoFoldersAreTwoEntries() {
        TreeExample.Entry a = TreeExample.Entry.file("classes").under("build");
        TreeExample.Entry b = TreeExample.Entry.file("classes").under("out");

        assertNotEquals(a, b, "the same name under two folders is two nodes");
        assertEquals("classes", a.name(), "and a row shows the name, not the path");
        assertEquals("build/classes", a.path());
    }

    @Test
    void aFolderRerootsWhatItHoldsUnderItself() {
        TreeExample.Entry src = TreeExample.Entry.folder("src",
                TreeExample.Entry.folder("main", TreeExample.Entry.file("App.java")),
                TreeExample.Entry.file("README.md"));

        assertEquals(List.of("src/main", "src/README.md"),
                src.children().stream().map(TreeExample.Entry::path).toList());
        assertEquals("src/main/App.java", src.children().get(0).children().get(0).path(),
                "re-rooted all the way down, so a nested name is unique too");
        assertEquals("App.java", src.children().get(0).children().get(0).name());
    }
}
