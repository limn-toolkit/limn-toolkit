package limn.components;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

/**
 * The component chrome that is small enough not to deserve a domain of its own:
 * a placeholder and a failure message.
 *
 * <p>Each domain owns its file family and registers it here, in a static block, so
 * touching one of its strings is what makes its translations available. There is no
 * central list of bundles to keep in step with the classes that need them, and a
 * domain nobody uses costs nothing.
 *
 * @see ColorPickerStrings
 * @see ThemeStrings
 */
final class ComponentStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/components"));
    }

    static final I18nString SEARCH_PLACEHOLDER =
            new I18nString("limn.searchField.placeholder", "Search…");

    // What a screen reader calls a search field's trailing button, for the SplitPane divider's
    // reason: it paints a glyph, holds no text and no tooltip, and no application can reach the
    // drawn region to name it, so without this the one field in the toolkit that ships a trailing
    // button out of the box publishes an operable control with an empty name.
    static final I18nString SEARCH_CLEAR =
            new I18nString("limn.searchField.clear", "Clear");

    static final I18nString VIEWPORT3D_NO_BACKEND =
            new I18nString("limn.viewport3d.noBackend", "3D unavailable (no GPU backend)");

    static final I18nString VIDEO_NO_BACKEND =
            new I18nString("limn.videoView.noBackend", "Video unavailable (no GPU backend)");

    static final I18nString VIDEO_DECODE_FAILED =
            new I18nString("limn.videoView.decodeFailed", "This video cannot be played");

    // The text widgets' context menu. Plain platform verbs on purpose: these are the four rows a
    // user has read in every other application, and a cleverer word here is one they have to
    // stop and parse.
    static final I18nString TEXT_MENU_CUT =
            new I18nString("limn.textMenu.cut", "Cut");

    static final I18nString TEXT_MENU_COPY =
            new I18nString("limn.textMenu.copy", "Copy");

    static final I18nString TEXT_MENU_PASTE =
            new I18nString("limn.textMenu.paste", "Paste");

    static final I18nString TEXT_MENU_SELECT_ALL =
            new I18nString("limn.textMenu.selectAll", "Select All");

    // What a screen reader calls the bar between a split's two panes. The toolkit has to supply
    // it: the divider paints a line and no text, it has no tooltip, and an application cannot
    // reach it to name it, so without this a split whose divider was made a tab stop publishes a
    // focusable control with an empty name.
    static final I18nString SPLIT_DIVIDER =
            new I18nString("limn.splitPane.divider", "Divider");

    // What a screen reader calls the two halves of a spinner's stepper column. Same situation as
    // the divider above, and the same answer: the arrows are painted glyphs with no text and no
    // tooltip, and they are SYNTHETIC children, so none of the three routes that name a widget --
    // setAccessibleName, a bound caption, a tooltip -- reaches them at all. Without a word here a
    // reader hears "button, button" beside every number, twenty-two times in a colour picker, and
    // no application can correct it.
    static final I18nString SPINNER_INCREMENT =
            new I18nString("limn.spinner.increment", "Increase");
    static final I18nString SPINNER_DECREMENT =
            new I18nString("limn.spinner.decrement", "Decrease");

    // What a screen reader calls the layer an in-scene dropdown list is drawn on, and the
    // toolkit has to supply it for the divider's reason and one more: that layer paints nothing
    // at all, holds no text, can never acquire a tooltip, and is a private inner class no
    // application can reach to name. It holds the keyboard for the whole life of the list, so
    // without this it is a focusable control with an empty name.
    static final I18nString COMBO_POPUP =
            new I18nString("limn.comboBox.popup", "Options");

    // The tabbed pane's three overflow controls, for the divider's reason: each paints a chevron
    // and no text, none has a tooltip of its own, and all three are instances of a private inner
    // class no application can reach to name. They are the tooltip a sighted user hovers and the
    // name a screen reader reads, from one string.
    //
    // They name a position in the TAB ORDER and never a screen side. The chevrons are turned
    // around right to left and the pane puts the previous-tabs control on the edge reading starts
    // from, so "scroll left" would be true where it was written and false in half the world.
    static final I18nString TAB_PREVIOUS =
            new I18nString("limn.tabbedPane.previousTabs", "Previous tabs");

    static final I18nString TAB_NEXT =
            new I18nString("limn.tabbedPane.nextTabs", "Next tabs");

    static final I18nString TAB_LIST_ALL =
            new I18nString("limn.tabbedPane.allTabs", "All tabs");

    // The segmented control's two overflow chevrons, for the spinner arrows' reason: they are
    // painted glyphs with no text and no tooltip, and they are SYNTHETIC children, so none of the
    // three routes that name a widget -- setAccessibleName, a bound caption, a tooltip -- can
    // reach them at all, and no application can correct a reader that says "button, button".
    //
    // They name a position in the segment ORDER and never a screen side, for the reason the two
    // above carry: the chevrons are turned around right to left and the back arrow sits in the
    // gutter reading starts from, so "scroll left" would be true where it was written and false
    // in half the world.
    static final I18nString SEGMENT_PREVIOUS =
            new I18nString("limn.segmentedControl.previousSegments", "Previous segments");

    static final I18nString SEGMENT_NEXT =
            new I18nString("limn.segmentedControl.nextSegments", "Next segments");

    // What a screen reader calls the strip of top-level menu titles. The bar is focusable from
    // its constructor, so it is a permanent tab stop and the every-focusable-node-is-named rule
    // always covers it, and the class holds no string of its own: its titles name the menus, not
    // the strip. Unlike the four above, MenuBar is a public class an application can reach with
    // setAccessibleName or a tooltip, and the name is redundant with the role -- which is the
    // argument against supplying one, and is why the argument is written here. It is supplied
    // anyway, because the alternative is a tab stop with an empty name in every application that
    // has not thought about it. An application's own name still wins over this one.
    static final I18nString MENU_BAR =
            new I18nString("limn.menuBar.name", "Menu bar");

    // What a screen reader calls the layer a whole open menu cascade is drawn on, for the
    // dropdown layer's reason in the same words: it paints nothing itself, holds no text, can
    // never acquire a tooltip, and is a private inner class no application can reach to name. It
    // is focusable from its constructor and takes the focus in both presentations, so for the
    // whole life of the cascade it is the focused widget and the every-focusable-node-is-named
    // rule always covers it. The columns beneath it are the menus and carry the menus' names.
    static final I18nString MENU_POPUP =
            new I18nString("limn.popupMenu.name", "Menu");

    // A menu column's two scroll-hint bands, for the segmented control's chevrons' reason: they
    // are painted glyphs with no text and no tooltip, and they are SYNTHETIC children, so none of
    // the three routes that name a widget -- setAccessibleName, a bound caption, a tooltip -- can
    // reach them at all.
    //
    // They name a position in the ITEM order and never a screen side. A menu column scrolls
    // vertically and never mirrors, so "up" and "down" would be true here; they are still refused,
    // because a user who cannot see the column is told where the rows are in the list and not
    // where the ink is on the glass, and because these two must read like the four above them.
    static final I18nString MENU_SCROLL_PREVIOUS =
            new I18nString("limn.popupMenu.previousItems", "Previous items");

    static final I18nString MENU_SCROLL_NEXT =
            new I18nString("limn.popupMenu.moreItems", "More items");

    // The dialog a ColorPickerButton raises. Here rather than in ColorPickerStrings
    // because two of the three are the words every dialog in every application uses,
    // and this is the first place the toolkit itself has had to supply them: a Dialog
    // an application builds takes its own button captions.
    static final I18nString COLOR_TITLE = new I18nString("limn.color.title", "Colour");
    static final I18nString OK = new I18nString("limn.ok", "OK");
    static final I18nString CANCEL = new I18nString("limn.cancel", "Cancel");

    private ComponentStrings() {
    }
}
