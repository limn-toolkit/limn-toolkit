package limn.testing.a11y;

import java.util.Objects;

/**
 * One named check of a per-shape contract (ADR 045 §4): a rule the shape holds every widget of
 * that shape to, with the decision it comes from in its name, run over a fresh widget each time.
 * The fixtures know nothing of a test framework, so a case is a name and a body that throws
 * {@link AssertionError} with the tree in the message; a test turns a list of cases into its
 * framework's dynamic tests in one line.
 *
 * @param name what the case proves, worded so that a failure reads as the rule broken
 * @param body the check
 */
public record ContractCase(String name, Body body) {

    /** The check; it throws {@link AssertionError} when the rule does not hold. */
    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    public ContractCase {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(body, "body");
    }
}
