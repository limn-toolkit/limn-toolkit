package limn.demo.site;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the testing guide says its two driven tests observe. */
class TestingExampleTest {

    @Test
    void clickingSaveAfterTypingSaysWhatWasSaved() {
        assertEquals("Saved Ada", TestingExample.typeANameAndSave());
    }

    @Test
    void enterInTheFieldSavesToo() {
        assertEquals("Saved Grace", TestingExample.typeANameAndPressEnter());
    }
}
