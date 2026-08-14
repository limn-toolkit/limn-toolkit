/**
 * The threading model and the async API on top of it: a single UI thread that every widget
 * mutation is confined to, reached through {@link limn.concurrent.Ui}, with
 * {@link limn.concurrent.Ui#post} to cross onto it, {@link limn.concurrent.Ui#async} for
 * future-shaped work, and {@link limn.concurrent.Ui#work} for a cancellable
 * {@link limn.concurrent.Work} that reports {@link limn.concurrent.Progress} and is held
 * as a {@link limn.concurrent.Job}. The backend installs the
 * {@link limn.concurrent.UiRuntime} at startup and drains its queue once per frame.
 *
 * <p>Confinement is checked, not advisory: a widget mutated off the UI thread throws, and
 * every success, failure and progress callback this package delivers runs where touching
 * widgets is legal.
 */
package limn.concurrent;
