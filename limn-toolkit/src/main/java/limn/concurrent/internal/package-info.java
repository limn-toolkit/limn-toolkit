/**
 * The listener lists, shared loads and thread factories the toolkit's own packages share behind
 * {@link limn.concurrent.Ui}: what makes a watcher list safe to mutate while it notifies, and a load
 * of one resource into one future however many callers ask.
 *
 * <p><b>Not API.</b> The module exports this package only to the Limn modules that share
 * it, and an application should not name it; it changes without notice.
 */
package limn.concurrent.internal;
