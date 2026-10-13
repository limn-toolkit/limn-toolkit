package limn.backend.lwjgl;

import limn.backend.lwjgl.internal.ObjC;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.System.Logger.Level;

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
