package limn.backend.lwjgl;

import limn.backend.lwjgl.internal.ObjC;
import limn.i18n.I18n;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCLocale;

import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * What the process is to macOS before and after the windowing library starts: the name the
 * system's menu bar writes in bold, and whether the application's package makes it an agent,
 * with no icon in the Dock and no menu bar of its own.
 */
final class MacApplication {

    private static final System.Logger LOG = System.getLogger(MacApplication.class.getName());

    private MacApplication() {
    }

    /** {@code -Dlimn.macos.agent=true}: this run is an agent's, whatever its package says. */
    static final String AGENT_PROPERTY = "limn.macos.agent";
    /** The same, for a process that cannot be handed a property: the processes a build starts start others. */
    static final String AGENT_VARIABLE = "LIMN_MACOS_AGENT";

    /**
     * Whether this run was asked to be an agent's: no icon in the Dock, no bar of menus, and
     * never brought in front of what the user is doing by a window of its own being shown. It is
     * how the processes of a build run, whose windows nobody is meant to look at: measured, one
     * that showed a window as an ordinary application took the keyboard from whatever the user
     * was typing in, each time a test opened one.
     */
    static boolean askedToBeAnAgent() {
        return Boolean.getBoolean(AGENT_PROPERTY) || "1".equals(System.getenv(AGENT_VARIABLE))
                || "true".equalsIgnoreCase(System.getenv(AGENT_VARIABLE));
    }

    /**
     * Whether the application's package says it is an agent ({@code LSUIElement}). Such an
     * application is left one: the windowing library is told to build no menu bar, which is also
     * what stops it making the application an ordinary one.
     */
    static boolean isAgent() {
        if (askedToBeAnAgent()) {
            return true;
        }
        try {
            long info = infoDictionary();
            long value = info == NULL ? NULL : ObjC.msg(info, "objectForKey:", MacTray.nsString("LSUIElement"));
            return value != NULL && ObjC.msg(value, "respondsToSelector:", ObjC.sel("boolValue")) != 0
                    && (ObjC.msg(value, "boolValue") & 0xFF) != 0;
        } catch (Throwable unreadable) {
            LOG.log(Level.DEBUG, "the application's package could not be read", unreadable);
            return false;
        }
    }

    /**
     * Gives the process its name for the system's menu bar. It reaches the bar only when given
     * before the windowing library starts: the system reads it once, as the application
     * finishes launching.
     */
    static void name(String name) {
        try {
            long info = infoDictionary();
            if (info != NULL && ObjC.msg(info, "respondsToSelector:", ObjC.sel("setObject:forKey:")) != 0) {
                ObjC.msgVoid(info, "setObject:forKey:", MacTray.nsString(name), MacTray.nsString("CFBundleName"));
            } else {
                LOG.log(Level.INFO, "the application's name could not be given to the system's menu bar");
            }
        } catch (Throwable refused) {
            LOG.log(Level.INFO, "the application's name could not be given to the system's menu bar", refused);
        }
    }

    /**
     * Puts the C library's numbers back in the {@code "C"} locale, which is where a macOS
     * application started by the system has them. The Java launcher takes every category from the
     * environment, and a terminal hands it one: started from a terminal with
     * {@code LANG=pt_BR.UTF-8}, the system's menus drew no check mark beside a checked item and no
     * arrow beside a submenu, measured on macOS 27 with a plain native program that did nothing
     * else; numbers written with a comma are the category that does it. Java formats its own
     * numbers and reads nothing of this one, so the text a reader sees is unchanged.
     *
     * @return the category's locale after the call, or {@code null} when it could not be read
     */
    static @Nullable String numbersInTheCLocale() {
        try {
            String now = cLocale(LibCLocale.LC_NUMERIC);
            if (!"C".equals(now)) {
                now = LibCLocale.setlocale(LibCLocale.LC_NUMERIC, "C");
            }
            return now;
        } catch (Throwable refused) {
            LOG.log(Level.DEBUG, "the C library's number locale could not be set", refused);
            return null;
        }
    }

    /** The environment variables that name a language, in the order the C library consults them. */
    private static final List<String> LANGUAGE_VARIABLES = List.of("LC_ALL", "LC_MESSAGES", "LANG");

    /**
     * Makes a native image start in the language the user chose in the system's settings, as the
     * JVM does. The JVM on macOS reads that choice from the system; a native image takes its
     * default locale from {@code LANG} alone, and a {@code .app} started by the system has no
     * {@code LANG}, so it came up in English on a Portuguese system, measured with a native image
     * of the Showcase started with an empty environment against the JVM reading {@code pt-BR}.
     * A terminal sets {@code LANG}, and an image started from one already follows it.
     *
     * <p>Only when nothing has chosen a language: a {@code -Duser.language} on the command line
     * stands, and the toolkit's language follows only if no {@link I18n#setLocale} before the
     * backend started chose another.
     */
    static void followSystemLanguage() {
        try {
            Locale preferred = languageToFollow(System.getProperty("org.graalvm.nativeimage.imagecode"),
                    System::getenv, System.getProperty("user.language", ""),
                    ProcessHandle.current().info().arguments().map(List::of).orElse(List.of()),
                    MacApplication::preferredLocale);
            if (preferred == null) {
                return;
            }
            Locale before = Locale.getDefault();
            Locale.setDefault(preferred);
            if (I18n.processLocale().equals(before)) {
                I18n.setLocale(preferred);
            }
        } catch (Throwable refused) {
            LOG.log(Level.DEBUG, "the system's language could not be followed", refused);
        }
    }

    /**
     * The decision behind {@link #followSystemLanguage}, apart from the system it reads.
     *
     * @param imageCode   the {@code org.graalvm.nativeimage.imagecode} property: set only inside a
     *                    native image, where the JVM's own reading is absent
     * @param environment the process environment
     * @param language    the {@code user.language} property as the process has it
     * @param arguments   the process's command line, or nothing where it cannot be read
     * @param preference  the system's preferred language, read only when it is needed
     * @return the locale to adopt, or {@code null} when the process already has the right one
     */
    static @Nullable Locale languageToFollow(@Nullable String imageCode,
                                             Function<String, @Nullable String> environment,
                                             String language, List<String> arguments,
                                             Supplier<@Nullable Locale> preference) {
        if (imageCode == null) {
            return null; // the JVM, which read the system's choice itself
        }
        for (String variable : LANGUAGE_VARIABLES) {
            String value = environment.apply(variable);
            if (value != null && !value.isEmpty()) {
                return null; // a terminal's choice, which the image has already taken
            }
        }
        for (String argument : arguments) {
            if (argument.startsWith("-Duser.language=") || argument.startsWith("-Duser.country=")
                    || argument.startsWith("-Duser.region=")) {
                return null; // the command line's choice
            }
        }
        // With no language in its environment an image says English; anything else was chosen,
        // by a -D the command line above could not show or by the application before this ran.
        if (!language.equals("en")) {
            return null;
        }
        return preference.get();
    }

    /**
     * The first language in the system's list of preferred languages, with the region the system's
     * formats use when the language names none: {@code pt-BR} as it stands, {@code en} with the
     * region of the user's formats. {@code null} when it cannot be read.
     */
    static @Nullable Locale preferredLocale() {
        try {
            long locale = ObjC.cls("NSLocale");
            long languages = ObjC.msg(locale, "preferredLanguages");
            long first = languages == NULL ? NULL : ObjC.msg(languages, "firstObject");
            String tag = utf8(first);
            if (tag == null) {
                return null;
            }
            Locale language = Locale.forLanguageTag(tag);
            if (language.getLanguage().isEmpty()) {
                return null;
            }
            if (!language.getCountry().isEmpty()) {
                return language;
            }
            long current = ObjC.msg(locale, "currentLocale");
            String region = utf8(current == NULL ? NULL : ObjC.msg(current, "countryCode"));
            return region == null ? language : new Locale.Builder().setLocale(language).setRegion(region).build();
        } catch (Throwable unreadable) {
            LOG.log(Level.DEBUG, "the system's preferred language could not be read", unreadable);
            return null;
        }
    }

    private static @Nullable String utf8(long string) {
        long bytes = string == NULL ? NULL : ObjC.msg(string, "UTF8String");
        return bytes == NULL ? null : MemoryUtil.memUTF8(bytes);
    }

    /** @return the C library's locale for a category, read without changing it, or {@code null} */
    static @Nullable String cLocale(int category) {
        return MemoryUtil.memASCIISafe(LibCLocale.nsetlocale(category, NULL));
    }

    /** @return the name the system shows for this application, or {@code null} when it cannot be read */
    static @Nullable String shownName() {
        try {
            long running = ObjC.msg(ObjC.cls("NSRunningApplication"), "currentApplication");
            long name = running == NULL ? NULL : ObjC.msg(running, "localizedName");
            long utf8 = name == NULL ? NULL : ObjC.msg(name, "UTF8String");
            return utf8 == NULL ? null : MemoryUtil.memUTF8(utf8);
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private static long infoDictionary() {
        long bundle = ObjC.msg(ObjC.cls("NSBundle"), "mainBundle");
        return bundle == NULL ? NULL : ObjC.msg(bundle, "infoDictionary");
    }
}
