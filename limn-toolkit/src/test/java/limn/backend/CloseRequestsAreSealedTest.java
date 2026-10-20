package limn.backend;

import limn.components.Dialog;
import limn.components.TabbedPane;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three requests a user's close puts to an application, a window's, a tab's and a dialog's,
 * and the fourth an application ending puts to its quit handler, are implemented by Limn alone, so
 * that a 1.x release can give each a method without breaking an application's class: open, the one
 * an application had implemented would stop compiling. Each is sealed and permits only the class
 * Limn answers with; a mock made the way mocking libraries make one, a proxy, is refused. A test
 * asks a handler the way the user and the system do, through the headless backend.
 */
class CloseRequestsAreSealedTest {

    private static final List<Class<?>> REQUESTS =
            List.of(CloseRequest.class, TabbedPane.TabCloseRequest.class, Dialog.CancelRequest.class,
                    QuitRequest.class);

    @Test
    void eachRequestIsSealedAndPermitsOnlyLimnsOwnClass() {
        for (Class<?> request : REQUESTS) {
            assertTrue(request.isSealed(), request.getName() + " is sealed");
            Class<?>[] permitted = request.getPermittedSubclasses();
            assertEquals(1, permitted.length, request.getName() + " permits one class: "
                    + Arrays.toString(permitted));
            Class<?> only = permitted[0];
            assertTrue(only.getName().startsWith("limn."), only.getName());
            assertTrue(java.lang.reflect.Modifier.isFinal(only.getModifiers()), only.getName() + " is final");
            for (var constructor : only.getDeclaredConstructors()) {
                assertTrue(!java.lang.reflect.Modifier.isPublic(constructor.getModifiers()),
                        only.getName() + " cannot be made outside Limn");
            }
        }
    }

    @Test
    void anApplicationsProxyOfARequestIsRefused() {
        for (Class<?> request : REQUESTS) {
            assertThrows(IllegalArgumentException.class, () -> Proxy.newProxyInstance(
                    request.getClassLoader(), new Class<?>[] {request}, (proxy, method, args) -> null),
                    request.getName());
        }
    }
}
