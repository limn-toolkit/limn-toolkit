package limn.print;

import limn.backend.NativeWindow;
import limn.backend.Printer;
import limn.backend.spi.WindowPeer;

import java.lang.reflect.Proxy;

/** A window that has nothing but its printer, for a document's print to be asked through. */
final class PrintingWindow {

    private PrintingWindow() {
    }

    /**
     * @param printer what the window's {@code printer()} answers
     * @return a window whose every other call answers nothing
     */
    static NativeWindow of(Printer printer) {
        return (NativeWindow) Proxy.newProxyInstance(WindowPeer.class.getClassLoader(),
                new Class<?>[] {WindowPeer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("printer")) {
                        return printer;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type.isPrimitive() && type != void.class) {
                        return type == float.class ? 0f : type == double.class ? 0d : type == long.class ? 0L : 0;
                    }
                    return null;
                });
    }
}
