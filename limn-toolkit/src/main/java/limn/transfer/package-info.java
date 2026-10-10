/**
 * What a copy or a drag carries. A {@link limn.transfer.Transfer} offers one item in several
 * {@linkplain limn.transfer.DataFormat formats} at once (a table row as text, as HTML and as the
 * application's own record), and whoever takes it reads the best one it understands from an
 * {@link limn.transfer.Offer}. The clipboard ({@code limn.backend.Clipboard}) is the first thing
 * that carries one.
 */
@NullMarked
package limn.transfer;

import org.jspecify.annotations.NullMarked;
