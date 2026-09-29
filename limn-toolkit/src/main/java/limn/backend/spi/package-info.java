/**
 * What a backend implements and the toolkit calls, below the application's view of
 * {@link limn.backend}: the input a window delivers to its scene ({@link limn.backend.spi.WindowInput}),
 * the frame it asks the scene to draw ({@link limn.backend.spi.FrameCallback},
 * {@link limn.backend.spi.FrameInfo}, {@link limn.backend.spi.GpuRenderer}), the services it installs
 * ({@link limn.backend.spi.BackendServices}), and the modality rules every backend shares
 * ({@link limn.backend.spi.ModalStack}).
 *
 * <p><b>Incubating.</b> The 1.0 contract freezes the application's layer, {@link limn.backend}; this
 * layer may change in a minor release while a second backend is still possible, and every change is
 * listed in the CHANGELOG with its migration. An application does not need it.
 */
@NullMarked
package limn.backend.spi;

import org.jspecify.annotations.NullMarked;
