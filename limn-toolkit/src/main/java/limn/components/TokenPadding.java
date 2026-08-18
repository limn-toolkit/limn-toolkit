package limn.components;

import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Padding;

import java.util.Objects;

/**
 * A {@link Padding} whose insets follow the resolved {@link limn.scene.ControlSize} step,
 * uniform on all four edges. Page and panel padding is the widest thing the spacing ramp moves
 * (12 / 16 / 20 / 26 / 32 at {@link Tokens.Role#LARGE}), and a literal here is what makes a
 * step change read as "the controls shrank" rather than "the density changed".
 */
public class TokenPadding extends Padding {

    private final Tokens.Role role;

    /** Page padding at {@code spacingLarge}. */
    public TokenPadding(Widget child) {
        this(Tokens.Role.LARGE, child);
    }

    /** Padding that follows {@code role} at the resolved size step. */
    public TokenPadding(Tokens.Role role, Widget child) {
        super(Insets.NONE, child);
        this.role = Objects.requireNonNull(role, "role");
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        setInsetsSilently(Insets.all(Tokens.spacingFor(this, role)));
        return super.onMeasure(constraints);
    }
}
