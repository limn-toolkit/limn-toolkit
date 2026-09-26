package limn.backend.lwjgl;

import limn.backend.Platform;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_API_UNAVAILABLE;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_ERROR;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * The parts of the Windows driver dialog that do not need Windows: the packed layout it hands to
 * {@code TaskDialogIndirect}, the text it resolves, and the catalog that text comes from. Whether
 * Windows accepts the layout and draws the text is settled on a Windows desktop, not here.
 */
class WindowsDriverDialogTest {

    /** Every locale the backend ships a translation for, the same 21 as the toolkit. */
    private static final List<String> LOCALES = List.of(
            "pt-BR", "pt", "es", "fr", "de", "it", "nl", "pl", "cs", "tr",
            "ru", "uk", "id", "vi", "ja", "ko", "zh-Hans", "zh-Hant", "hi", "ar", "he");

    private static final List<I18nString> STRINGS = List.of(
            DriverHelpStrings.HEADING, DriverHelpStrings.BODY, DriverHelpStrings.STORE,
            DriverHelpStrings.STORE_NOTE, DriverHelpStrings.WINGET, DriverHelpStrings.WINGET_NOTE,
            DriverHelpStrings.ASK_STORE);

    @AfterEach
    void restoreLanguage() {
        I18n.setLocale(Locale.ENGLISH);
    }

    /**
     * The offsets are commctrl.h's under pshpack1, and a natural-alignment reading of the same
     * struct would put the window handle at 8 and make the whole 176 bytes. Both numbers are
     * asserted from the field list itself, so an offset typed wrong fails here and not as a
     * dialog Windows refuses with E_INVALIDARG.
     */
    @Test
    void theConfigIsPackedToOneByteAsCommctrlDeclaresIt() {
        int pointer = 8;
        // UINT, HWND, HINSTANCE, flags, common buttons, title, icon, instruction, content,
        // cButtons, pButtons, nDefaultButton, cRadio, pRadio, nDefaultRadio, verification,
        // expanded, expandedControl, collapsedControl, footerIcon, footer, callback, data, cxWidth.
        int[] sizes = {4, pointer, pointer, 4, 4, pointer, pointer, pointer, pointer, 4, pointer, 4,
                4, pointer, 4, pointer, pointer, pointer, pointer, pointer, pointer, pointer, pointer,
                4};
        int[] offsets = new int[sizes.length];
        int at = 0;
        for (int i = 0; i < sizes.length; i++) {
            offsets[i] = at;
            at += sizes[i];
        }
        assertEquals(at, WindowsDriverDialog.Layout.SIZE);
        assertEquals(160, WindowsDriverDialog.Layout.SIZE);
        assertEquals(offsets[1], WindowsDriverDialog.Layout.HWND_PARENT);
        assertEquals(offsets[3], WindowsDriverDialog.Layout.FLAGS);
        assertEquals(offsets[4], WindowsDriverDialog.Layout.COMMON_BUTTONS);
        assertEquals(offsets[5], WindowsDriverDialog.Layout.WINDOW_TITLE);
        assertEquals(offsets[6], WindowsDriverDialog.Layout.MAIN_ICON);
        assertEquals(offsets[7], WindowsDriverDialog.Layout.MAIN_INSTRUCTION);
        assertEquals(offsets[8], WindowsDriverDialog.Layout.CONTENT);
        assertEquals(offsets[9], WindowsDriverDialog.Layout.BUTTON_COUNT);
        assertEquals(offsets[10], WindowsDriverDialog.Layout.BUTTONS);
        assertEquals(offsets[11], WindowsDriverDialog.Layout.DEFAULT_BUTTON);
        assertEquals(offsets[16], WindowsDriverDialog.Layout.EXPANDED_INFORMATION);
        assertEquals(offsets[23], WindowsDriverDialog.Layout.CX_WIDTH);
        assertEquals(4 + pointer, WindowsDriverDialog.Layout.BUTTON_SIZE);
    }

    /** What is written lands where the layout says, and nothing is left from a previous use. */
    @Test
    void writeConfigPutsEachFieldAtItsOffset() {
        long config = MemoryUtil.nmemAlloc(WindowsDriverDialog.Layout.SIZE);
        long buttons = MemoryUtil.nmemAlloc(2L * WindowsDriverDialog.Layout.BUTTON_SIZE);
        try {
            MemoryUtil.memSet(config, 0x5A, WindowsDriverDialog.Layout.SIZE);
            int count = WindowsDriverDialog.writeConfig(config, buttons, 0x1111, 0x2222, 0x3333,
                    0x4444, 0x5555, 0x6666, true);
            assertEquals(2, count);
            assertEquals(160, MemoryUtil.memGetInt(config));
            assertEquals(NULL, address(config + WindowsDriverDialog.Layout.HWND_PARENT));
            int flags = MemoryUtil.memGetInt(config + WindowsDriverDialog.Layout.FLAGS);
            assertEquals(WindowsDriverDialog.TDF_USE_COMMAND_LINKS
                    | WindowsDriverDialog.TDF_ALLOW_DIALOG_CANCELLATION
                    | WindowsDriverDialog.TDF_EXPAND_FOOTER_AREA
                    | WindowsDriverDialog.TDF_RTL_LAYOUT, flags);
            assertEquals(WindowsDriverDialog.TDCBF_CLOSE_BUTTON,
                    MemoryUtil.memGetInt(config + WindowsDriverDialog.Layout.COMMON_BUTTONS));
            assertEquals(0x1111, address(config + WindowsDriverDialog.Layout.WINDOW_TITLE));
            assertEquals(WindowsDriverDialog.TD_ERROR_ICON,
                    address(config + WindowsDriverDialog.Layout.MAIN_ICON));
            assertEquals(0x2222, address(config + WindowsDriverDialog.Layout.MAIN_INSTRUCTION));
            assertEquals(0x3333, address(config + WindowsDriverDialog.Layout.CONTENT));
            assertEquals(2, MemoryUtil.memGetInt(config + WindowsDriverDialog.Layout.BUTTON_COUNT));
            assertEquals(buttons, address(config + WindowsDriverDialog.Layout.BUTTONS));
            assertEquals(WindowsDriverDialog.STORE_BUTTON,
                    MemoryUtil.memGetInt(config + WindowsDriverDialog.Layout.DEFAULT_BUTTON));
            assertEquals(0x6666, address(config + WindowsDriverDialog.Layout.EXPANDED_INFORMATION));
            assertEquals(0, MemoryUtil.memGetInt(config + WindowsDriverDialog.Layout.CX_WIDTH));

            assertEquals(WindowsDriverDialog.STORE_BUTTON, MemoryUtil.memGetInt(buttons));
            assertEquals(0x4444, address(buttons + WindowsDriverDialog.Layout.BUTTON_TEXT));
            long second = buttons + WindowsDriverDialog.Layout.BUTTON_SIZE;
            assertEquals(WindowsDriverDialog.WINGET_BUTTON, MemoryUtil.memGetInt(second));
            assertEquals(0x5555, address(second + WindowsDriverDialog.Layout.BUTTON_TEXT));

            // No winget: one button, and a left-to-right language keeps the layout unmirrored.
            assertEquals(1, WindowsDriverDialog.writeConfig(config, buttons, NULL, 1, 2, 3, NULL,
                    NULL, false));
            assertEquals(1, MemoryUtil.memGetInt(config + WindowsDriverDialog.Layout.BUTTON_COUNT));
            assertEquals(0, MemoryUtil.memGetInt(config + WindowsDriverDialog.Layout.FLAGS)
                    & WindowsDriverDialog.TDF_RTL_LAYOUT);
        } finally {
            MemoryUtil.nmemFree(config);
            MemoryUtil.nmemFree(buttons);
        }
    }

    private static long address(long at) {
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (MemoryUtil.memGetByte(at + i) & 0xFF);
        }
        return value;
    }

    /**
     * The winget link carries the command it runs, so the person sees what will happen before
     * choosing it; with no winget there is no link rather than one that fails.
     */
    @Test
    void theContentOffersTheStoreAlwaysAndWingetOnlyWhereItIs() {
        I18n.setLocale(Locale.ENGLISH);
        WindowsDriverDialog.Content with = WindowsDriverDialog.content("My App", "65542 WGL", true);
        assertEquals("My App", with.title());
        assertEquals("65542 WGL", with.details());
        assertTrue(with.store().startsWith("Open the Microsoft Store\n"), with.store());
        assertTrue(with.store().contains("OpenCL, OpenGL, and Vulkan Compatibility Pack"));
        assertTrue(with.winget().endsWith("\n" + WindowsDriverDialog.WINGET_COMMAND), with.winget());
        assertTrue(WindowsDriverDialog.WINGET_COMMAND.contains(
                GraphicsProbe.COMPATIBILITY_PACK_WINGET_ID));
        assertFalse(with.rtl());

        WindowsDriverDialog.Content without = WindowsDriverDialog.content(" ", "x", false);
        assertNull(without.winget());
        assertNull(without.title(), "a blank title leaves Windows to name the executable");
    }

    /** The text follows the process language, and a right-to-left one mirrors the dialog. */
    @Test
    void theContentIsInTheProcessLanguage() {
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        WindowsDriverDialog.Content portuguese = WindowsDriverDialog.content("A", "x", true);
        assertTrue(portuguese.heading().startsWith("Este computador"), portuguese.heading());
        assertTrue(portuguese.body().contains("\n\n"), "the body keeps its paragraph break");
        assertFalse(portuguese.rtl());
        for (String tag : List.of("ar", "he")) {
            I18n.setLocale(Locale.forLanguageTag(tag));
            WindowsDriverDialog.Content rtl = WindowsDriverDialog.content("A", "x", true);
            assertTrue(rtl.rtl(), tag);
            assertNotEquals(DriverHelpStrings.HEADING.english(), rtl.heading(), tag);
        }
    }

    /**
     * Every shipped file translates exactly the keys the dialog declares: none missing (which
     * would show English in the middle of another language) and none left over (a key nobody
     * reads). Every locale's words differ from the English, which catches a file copied and not
     * translated.
     */
    @Test
    void everyLocaleCarriesEveryKeyAndOnlyThose() {
        Set<String> declared = new TreeSet<>();
        STRINGS.forEach(s -> declared.add(s.key()));
        List<String> problems = new ArrayList<>();
        for (String tag : LOCALES) {
            Properties file = read("/limn/backend/lwjgl/i18n/driverhelp_" + tag + ".properties");
            if (!declared.equals(new TreeSet<>(file.stringPropertyNames()))) {
                problems.add(tag + ": " + file.stringPropertyNames());
            }
            if (DriverHelpStrings.HEADING.english().equals(file.getProperty(DriverHelpStrings.HEADING.key()))) {
                problems.add(tag + ": heading is the English");
            }
            for (I18nString s : STRINGS) {
                String text = file.getProperty(s.key());
                if (s == DriverHelpStrings.STORE_NOTE || s == DriverHelpStrings.ASK_STORE) {
                    if (text == null || !text.contains("OpenCL, OpenGL, and Vulkan Compatibility Pack")) {
                        problems.add(tag + ": " + s.key() + " does not name the pack as the Store does");
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    /** The dialog is asked for exactly where the advice is given, and nowhere else. */
    @Test
    void theDialogFollowsTheSameTestAsTheAdvice() {
        assertTrue(GraphicsProbe.lacksDriver(Platform.Os.WINDOWS, GLFW_API_UNAVAILABLE));
        assertFalse(GraphicsProbe.lacksDriver(Platform.Os.WINDOWS, GLFW_PLATFORM_ERROR));
        for (Platform.Os os : Platform.Os.values()) {
            assertEquals(!GraphicsProbe.contextAdvice(os, GLFW_API_UNAVAILABLE).isEmpty(),
                    GraphicsProbe.lacksDriver(os, GLFW_API_UNAVAILABLE), os.name());
        }
    }

    private static Properties read(String resource) {
        try (InputStream in = DriverHelpStrings.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
