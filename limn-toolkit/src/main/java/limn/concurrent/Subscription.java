package limn.concurrent;

/**
 * What a registration hands back: the one way to stop listening.
 *
 * <p>A distinct type and not a {@link Runnable}, because a {@code Runnable} handle returned from
 * a {@code Runnable}-taking registrar can be handed straight back to it:
 *
 * <pre>{@code
 * Runnable handle = viewport.observeDispose(scene3d::dispose);
 * viewport.observeDispose(handle);   // compiles; registers the unsubscriber as a listener
 * }</pre>
 *
 * <p>That program compiles, runs, and then unsubscribes something at a moment nobody chose, with
 * no diagnostic anywhere. One method with a name of its own closes it for every registrar at
 * once: the argument position takes a {@code Runnable}, a {@code ChangeObserver} or a
 * {@code Predicate}, the return position takes a {@code Subscription}, and the two can never be
 * confused.
 *
 * <p>It lives here rather than beside {@code Widget} because five registrars in four packages
 * return it and two of those packages ({@code limn.graphics}, {@code limn.i18n}) do not depend on
 * the widget layer at all. {@code limn.concurrent} is where the dependencies already point.
 *
 * <p><b>Cancelling twice is a no-op</b>, which is a promise about the handle rather than about
 * the removal: a handle drops the registration it captured on its first {@link #cancel()} and
 * does nothing thereafter, so it can never take a second listener off a list.
 */
@FunctionalInterface
public interface Subscription {

    /** Stops the listening this handle was returned for. Idempotent. UI thread. */
    void cancel();
}
