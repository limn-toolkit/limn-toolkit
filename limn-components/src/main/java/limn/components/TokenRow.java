package limn.components;

import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.layout.Row;

import java.util.Objects;

/**
 * A {@link Row} whose gap follows the resolved {@link limn.scene.ControlSize} step.
 *
 * <p>The gap is pushed from {@code onMeasure}, so it tracks a step change with no app code and
 * with no extra layout pass: the push uses {@code Flex}'s silent form, which assigns without
 * requesting a pass, because the caller is already inside the pass that consumes it.
 */
public class TokenRow extends Row {

    private final Tokens.Role role;

    /** A row gutter at {@code spacingMedium}, the default between controls. */
    public TokenRow() {
        this(Tokens.Role.MEDIUM);
    }

    /** A row whose gap follows {@code role} at the resolved size step. */
    public TokenRow(Tokens.Role role) {
        this.role = Objects.requireNonNull(role, "role");
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        gapSilently(Tokens.spacingFor(this, role));
        return super.onMeasure(constraints);
    }
}
