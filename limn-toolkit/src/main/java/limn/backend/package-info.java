/**
 * The service-provider interface a native platform implements: {@link limn.backend.Backend}
 * runs the event loop and creates {@link limn.backend.NativeWindow}s. Beside it sit the
 * contracts a platform must supply: the {@link limn.backend.GpuRenderer} widgets draw
 * through, {@link limn.backend.WindowInput}, {@link limn.backend.Clipboard},
 * {@link limn.backend.Cursor}s, {@link limn.backend.FileDialogs} and
 * {@link limn.backend.Crashes} reporting. Come here to write a backend, or to see exactly
 * what one owes the toolkit; an application constructs one at startup and otherwise
 * programs against the modules above, which never name a windowing library.
 */
package limn.backend;
