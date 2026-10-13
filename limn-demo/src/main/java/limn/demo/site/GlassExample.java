package limn.demo.site;

import limn.components.BackdropPanel;
import limn.components.ScrollView;
import limn.components.Theme;
import limn.graphics.BackdropEffect;
import limn.graphics.Color;
import limn.scene.Insets;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Stack;

/**
 * The worked examples the theming guide shows for glass: a frosted bar that stays put while the
 * content scrolls under it, and a panel that floats over a picture. The demo's own page is built
 * from these, so what the guide shows is what the page runs. Compiled by {@code ./gradlew check}.
 */
public final class GlassExample {

    private GlassExample() {
    }

    // #region guide:glass-frosted
    /** A panel of frosted glass in the current palette's tone. */
    public static BackdropPanel frosted(Insets insets, Widget<?> content) {
        BackdropPanel panel = new BackdropPanel(
                new BackdropEffect.Blur(Color.TRANSPARENT, 10, BackdropEffect.Blur.Axis.X), insets, content);
        frost(panel, Theme.current());
        return panel;
    }

    /**
     * Makes {@code panel} frosted glass in a palette's tone: what is behind it is blurred across
     * and then down, and then calmed and tinted with the surface colour so that text laid on it
     * reads. An effect holds the colour it was given, so call this again when the palette
     * changes, from the same place that applies the theme.
     */
    public static void frost(BackdropPanel panel, Theme theme) {
        panel.setEffects(
                new BackdropEffect.Blur(Color.TRANSPARENT, 10, BackdropEffect.Blur.Axis.X),
                new BackdropEffect.Blur(Color.TRANSPARENT, 10, BackdropEffect.Blur.Axis.Y),
                // Less colour than the content has, and the surface over it at a little more
                // than half: the content is still seen moving, and no longer competes.
                new BackdropEffect.Wash(theme.surface().withAlpha(0.6f), 0.7f, 0f));
    }
    // #endregion

    // #region guide:glass-bar
    /** How tall the bar is taken to be, so that the content starts below it. */
    static final float BAR_HEIGHT = 52;

    /** A bar that stays at the top while {@code content} scrolls under it and shows through. */
    public static Widget<?> barOver(Widget<?> content, BackdropPanel bar) {
        // The content begins below the bar and scrolls up under it.
        ScrollView scrolling = new ScrollView(new Padding(new Insets(BAR_HEIGHT, 0, 0, 0), content));
        bar.setCornerRadius(0);                  // a bar goes from edge to edge
        Stack layers = new Stack();
        layers.add(scrolling);
        layers.add(bar);                         // after what it covers: glass samples what is drawn
        return layers;
    }
    // #endregion

    // #region guide:glass-floating
    /** A panel floating over {@code behind}, at its bottom, set in from the edges. */
    public static Widget<?> floatingOver(Widget<?> behind, BackdropPanel panel) {
        // What is behind takes the whole box: a stack gives each layer the size it asks for,
        // and a column that stretches its one child asks for all of it.
        Column filling = new Column();
        filling.crossAlignment(Flex.CrossAlignment.STRETCH);
        filling.add(Expanded.of(behind, 1));
        Stack layers = new Stack().alignment(Stack.Alignment.BOTTOM_CENTER);
        layers.add(filling);
        // The padding around the glass is what sets it in from the picture's edges.
        layers.add(new Padding(Insets.all(16), panel));
        return layers;
    }
    // #endregion
}
