package limn.demo.site;

import limn.components.BackdropPanel;
import limn.components.ScrollView;
import limn.graphics.Color;
import limn.scene.Insets;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Stack;

/**
 * The worked examples the theming guide shows for glass: frosted glass that follows the theme, a
 * bar of it that stays put while the content scrolls under it, and a panel that floats over a
 * picture. The demo's own page is built
 * from these, so what the guide shows is what the page runs. Compiled by {@code ./gradlew check}.
 */
public final class GlassExample {

    private GlassExample() {
    }

    // #region guide:glass-frosted
    /** Frosted glass in the theme's tone: light or dark as the palette is, by itself. */
    static BackdropPanel plain(Widget<?> content) {
        return BackdropPanel.frosted(Insets.symmetric(8, 12), content);
    }

    /**
     * The same glass leaning toward a colour of the application's, here by a fifth, and letting
     * more of what is behind through: over a picture chosen for it, a third of the tone is enough.
     */
    public static BackdropPanel tinted(Widget<?> content, Color brand) {
        return BackdropPanel.frosted(Insets.symmetric(10, 14), content).setTint(brand, 0.2f).setCover(0.3f);
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
