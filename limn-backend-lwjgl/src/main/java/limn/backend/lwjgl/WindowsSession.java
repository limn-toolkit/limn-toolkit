package limn.backend.lwjgl;

import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.windows.WinBase;

import java.nio.IntBuffer;

/**
 * Which session of Windows the process runs in. Session 0 is the services': a service's process,
 * and one started over SSH, runs there, with no desktop the user sees, and making a window's OpenGL
 * context in it ends the process with an access violation in {@code ntdll} (measured on Windows 11
 * on 2026-11-03), so a backend that finds itself there starts without a display.
 */
final class WindowsSession {

    private WindowsSession() {
    }

    /** @return whether the process runs in session 0; false where that cannot be read */
    static boolean isServices() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long kernel32 = WinBase.GetModuleHandle(null, "kernel32");
            if (kernel32 == MemoryUtil.NULL) {
                return false;
            }
            long processId = WinBase.GetProcAddress(null, kernel32, "GetCurrentProcessId");
            long sessionOf = WinBase.GetProcAddress(null, kernel32, "ProcessIdToSessionId");
            if (processId == MemoryUtil.NULL || sessionOf == MemoryUtil.NULL) {
                return false;
            }
            IntBuffer session = stack.mallocInt(1);
            int pid = JNI.invokeI(processId);
            return JNI.invokePI(pid, MemoryUtil.memAddress(session), sessionOf) != 0 && session.get(0) == 0;
        } catch (Throwable unreadable) {
            return false;
        }
    }
}
