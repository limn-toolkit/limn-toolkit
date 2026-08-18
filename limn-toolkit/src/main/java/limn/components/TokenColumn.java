package limn.components;

import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.layout.Column;

import java.util.Objects;

/**
 * A {@link Column} whose gap follows the resolved {@link limn.scene.ControlSize} step.
 * The column form of {@link TokenRow}; see there for why the push happens in {@code onMeasure}.
 */
public class TokenColumn extends Column {

    private final Tokens.Role role;

    /** A column gutter at {@code spacingMedium}, the default between controls. */
    public TokenColumn() {
        this(Tokens.Role.MEDIUM);
    }

    /** A column whose gap follows {@code role} at the resolved size step. */
    public TokenColumn(Tokens.Role role) {
        this.role = Objects.requireNonNull(role, "role");
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        gapSilently(Tokens.spacingFor(this, role));
        return super.onMeasure(constraints);
    }
}
