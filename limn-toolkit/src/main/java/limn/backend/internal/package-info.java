/**
 * What every backend shares behind {@link limn.backend.Backend}: how a window's close is decided
 * and how an application ends by asking once.
 *
 * <p><b>Not API.</b> The module exports this package only to the Limn modules that share
 * it, and an application should not name it; it changes without notice.
 */
@NullMarked
package limn.backend.internal;

import org.jspecify.annotations.NullMarked;
