package limn.components;

import limn.scene.Insets;
import limn.scene.Widget;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;

import java.util.Objects;

/**
 * Pushes resolved {@link SizeTokens} spacing into the geometry-only containers, which hold
 * plain floats and therefore cannot follow a size step on their own.
 *
 * <p>{@code Flex.gap} and {@code Padding.insets} are literals: a bare
 * {@code row.setControlSize(SMALL)} compacts every child and leaves the gutters <em>between</em>
 * them at whatever number the app typed, and an app that writes
 * {@code col.gap(theme.spacingMedium)} bakes MEDIUM in permanently. The spacing ramp is the
 * widest of the three (3.33x), so leaving it unreachable would discard most of the visible
 * effect of changing step.
 *
 * <p>Two ways to use this. Prefer {@link TokenRow} / {@link TokenColumn} / {@link TokenPadding},
 * which push on every measure pass and therefore track a step change with no app code. Call the
 * static forms directly only where a container is already owned by something else.
 *
 * @see TokenRow
 * @see TokenColumn
 * @see TokenPadding
 */
public final class Tokens {

    private Tokens() {
    }

    /** Which of the three spacing tokens a container follows. */
    public enum Role {
        /** {@code spacingSmall}: 3 / 4 / 6 / 8 / 10. Tight groups, icon-to-label gaps. */
        SMALL,
        /** {@code spacingMedium}: 6 / 9 / 12 / 16 / 20. The default gutter between controls. */
        MEDIUM,
        /** {@code spacingLarge}: 12 / 16 / 20 / 26 / 32. Section breaks and page padding. */
        LARGE
    }

    /** @return the spacing value for {@code role} out of {@code tokens} */
    public static float spacing(SizeTokens tokens, Role role) {
        Objects.requireNonNull(tokens, "tokens");
        return switch (Objects.requireNonNull(role, "role")) {
            case SMALL -> tokens.spacingSmall();
            case MEDIUM -> tokens.spacingMedium();
            case LARGE -> tokens.spacingLarge();
        };
    }

    /** @return the spacing for {@code role} at the step resolved on {@code widget} */
    public static float spacingFor(Widget widget, Role role) {
        return spacing(Theme.current().tokensFor(widget), role);
    }

    /**
     * Sets {@code flex}'s gap from the step resolved on it. Idempotent ({@code Flex.gap}
     * carries an equality guard), so calling this from a measure path does not loop.
     */
    public static void applyGap(Flex flex, Role role) {
        flex.gap(spacingFor(flex, role));
    }

    /** Sets {@code padding}'s insets, uniformly, from the step resolved on it. */
    public static void applyInsets(Padding padding, Role role) {
        padding.setInsets(Insets.all(spacingFor(padding, role)));
    }

    /**
     * The gap a column of {@link Checkbox} or {@link RadioButton} rows needs so that stacked
     * toggles clear WCAG 2.2 SC 2.5.8's <em>Spacing</em> exception: a toggle row is 18pt at
     * three steps, so the pitch {@code row + gap} must reach 24.
     *
     * <p>Exposed as a call rather than pushed by a container, because a {@link TokenColumn}
     * cannot know its children are toggles. An app stacking toggles applies it explicitly:
     * {@code col.gap(Tokens.toggleColumnGap(col))}. Without it, stacked toggles keep a
     * 21/22pt pitch at the dense steps, the same sub-24 admission the toggles themselves
     * carry at MEDIUM today.
     */
    public static float toggleColumnGap(Widget widget) {
        return Theme.current().tokensFor(widget).toggleColumnGap();
    }
}
