package limn.accessibility;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The parameterless verbs a node offers, and the keystroke that performs the first of them.
 *
 * <p>Only {@linkplain Accessible.Action#isParameterless() parameterless} verbs are published here.
 * A parameterised setter is dispatched through the same one call and is <b>not</b> in this list,
 * because one of the three platforms cannot express a parameterised action in its action list at
 * all; what advertises such a setter is the presence of the facet it sets.
 *
 * <p>The key binding is here because one platform's action list has a column for it and nothing
 * else in the model does. It is the accelerator as a user would read it, already localized.
 *
 * @param actions    the verbs, in no particular order; never empty and never modifiable
 * <p>The access key is apart from it: the letter a user presses with Alt to reach the node
 * ("Alt+N"), which a menu, a button or a field's label marks, where the key binding runs a command
 * ("Ctrl+N"). Windows has a property for each; Linux reads the access key where a binding goes.
 *
 * @param keyBinding the accelerator that performs this node's primary action, or {@code null}
 * @param accessKey  the access letter's chord as a user would read it, or {@code null}
 */
public record ActionFacet(Set<Accessible.Action> actions, @Nullable String keyBinding,
        @Nullable String accessKey) {

    /** A facet with no access key. */
    public ActionFacet(Set<Accessible.Action> actions, @Nullable String keyBinding) {
        this(actions, keyBinding, null);
    }

    /**
     * @throws NullPointerException     if {@code actions} is {@code null}
     * @throws IllegalArgumentException if it is empty or holds a verb that takes an argument
     */
    public ActionFacet {
        java.util.Objects.requireNonNull(actions, "actions");
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("an action facet with no verb says nothing");
        }
        EnumSet<Accessible.Action> copy = EnumSet.noneOf(Accessible.Action.class);
        for (Accessible.Action action : actions) {
            if (!action.isParameterless()) {
                throw new IllegalArgumentException(
                        action + " takes an argument and is never published in an action list");
            }
            copy.add(action);
        }
        actions = Collections.unmodifiableSet(copy);
    }

    /**
     * @param action the verb to look for
     * @return whether this node offers it
     */
    public boolean has(Accessible.Action action) {
        return actions.contains(action);
    }
}
