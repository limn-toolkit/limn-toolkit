package limn.scene;

/**
 * The float callback the JDK does not have, for the widgets whose value is a float.
 *
 * <p>{@code Consumer<Float>} boxes on every drag frame, and {@code DoubleConsumer} — the JDK's
 * nearest — widens the value, which would turn {@code Math.round(v)} into a {@code long} and stop
 * {@code other.setValue(v)} compiling at every call site that passes one slider's value to
 * another. One two-line interface keeps both.
 */
@FunctionalInterface
public interface FloatConsumer {

    /**
     * @param value the new value
     */
    void accept(float value);
}
