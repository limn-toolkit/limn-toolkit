package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.internal.FloatingSurfaces;
import limn.components.internal.a11y.PopupOwnerAccessibility;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.components.internal.a11y.RowsAccessibility;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.concurrent.Ui;
import limn.concurrent.Subscription;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.graphics.Path2D;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.internal.lang.Checks;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Scrollable;
import limn.scene.Size;
import limn.scene.Change;
import limn.scene.Widget;
import limn.graphics.SvgIcon;
import limn.scene.internal.SceneAccess;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;
import limn.backend.spi.WindowPeer;

/**
 * Dropdown selector. The popup is a real native window: undecorated,
 * floating, non-focus-stealing and with a <em>transparent framebuffer</em>,
 * so its rounded corners (and the translucent panel behind them) composite
 * over whatever is on screen, exactly like the rest of the theme. It can
 * overflow the parent window like a native menu.
 *
 * <p>Keyboard: Space/Enter/Down opens; Up/Down move the highlight; Enter
 * commits; Esc closes. The popup takes no focus when it opens; the combo keeps
 * it, so clicking anywhere else in the parent window closes the popup via
 * focus loss. A click in the list that chooses nothing, on its scroll bar or
 * its padding, makes the list's window the desktop's key window; the keys it
 * then receives are handed to the combo, and closing the list gives the
 * parent window the keyboard back. Headless scenes (tests) keep the same
 * open/highlight/commit state machine without a native window.
 *
 * <p><b>In-scene fallback.</b> Where the window reports no
 * {@link limn.backend.spi.WindowPeer#supportsAbsolutePositioning()} (Wayland), a list in a window of
 * its own would open somewhere other than under the field, and over a
 * {@linkplain limn.backend.NativeWindow#isFullscreen() fullscreen} window it would minimize an
 * owner fullscreen at a named mode at the first click, while one at the mode the display is in
 * already covers the screen and leaves a list window no edge to reach past, so in both the list is
 * drawn as an overlay inside the owner window instead (see {@link DisplayMode}). Same rows, same
 * keys, same type-ahead; the difference is that it is clamped to that window rather than to the
 * display, so a combo near the bottom edge shows fewer rows and scrolls sooner. Chosen by the
 * platform, not by the application, and {@link #popupWindow()} is {@code null} throughout. Chosen
 * when the list opens; a list already open in a window of its own when the owner enters or leaves
 * fullscreen closes instead, as if dismissed, with the selection as it was: see
 * {@link DisplayMode}.
 *
 * <p>Field and dropdown share one {@link limn.scene.ControlSize} step: the panel is the root
 * of its own window-bound {@link Scene}, so it inherits through
 * {@link Widget#setInheritanceHost} rather than through the tree. A dropdown at a different
 * density from the field that opened it is simply a bug, so there is no setter on the panel.
 * The in-scene presentation carries the same link on the overlay layer, which is the parentless
 * widget there — the panel's own would be ignored, because a host link on a widget that has a
 * parent loses to the tree.
 *
 * <p>The {@link LayoutDirection} arrives by that same link and for that same reason: a list
 * whose rows read the other way from the field that opened them is a bug rather than a
 * configuration. Reading right to left the label sits against the right edge, the chevron takes
 * the gutter on the left, the list hangs from the field's right edge and the marker column, the
 * row labels and the scrollbar all move to the other side. The chevron itself does not turn
 * over: it points up and down.
 */
public final class ComboBox<T> extends Widget<ComboBox<T>> {

    /** Breathing room kept from the work-area edge when clamping the popup. */
    private static final float EDGE_MARGIN = 8;

    /** The items themselves, what {@link #selectedItem()} and {@link #onSelect} hand back. */
    private List<T> values;
    /** Each item's text, in the order of {@link #values}: what the field and the list show. */
    private List<I18nString> items;
    /** How an item reads, kept for the items a search answers with. */
    private final Function<? super T, ?> textOf;
    /** An item's icon, or {@code null} for none at all; see {@link #setItemIcon}. */
    private @Nullable Function<? super T, ? extends @Nullable Icon> iconOf;
    /** Each item's icon, in the order of {@link #values}, while {@link #iconOf} is set; else empty. */
    private List<@Nullable Icon> icons = List.of();
    /** Where the items come from while the user types, or {@code null} for the list in memory. */
    private @Nullable Function<String, ? extends java.util.concurrent.CompletionStage<? extends List<? extends T>>> search;
    /** Numbers the searches asked for: an answer for any but the newest is dropped. */
    private int searchSerial;
    /**
     * The one line the list shows instead of items: loading, nothing found, or a failed search;
     * {@code null} while it shows items.
     */
    private @Nullable I18nString statusLine;
    /** How long a search waits after the last keystroke, so a word typed quickly is one request. */
    static final long SEARCH_DELAY_MILLIS = 200;
    private static final System.Logger LOG = System.getLogger(ComboBox.class.getName());
    /**
     * The selected item in {@link SelectionMode#SINGLE}; in {@link SelectionMode#MULTI} the first of
     * {@link #chosen}, kept so, which is what {@link #selectedIndex()} and {@link #selectedItem()}
     * answer there. -1 for none.
     */
    private int selectedIndex;
    /**
     * The row standing for "nothing selected" in {@link #rows}, while {@link #emptyAllowed}: not an
     * item, and never an index into {@link #items}.
     */
    private static final int EMPTY_ROW = -1;
    /** The accessible key of that row, apart from every item's index and from the status line's. */
    private static final int EMPTY_KEY = Integer.MAX_VALUE - 1;
    /** Whether a combo holding one item may hold none; see {@link #setEmptySelectionAllowed}. */
    private boolean emptyAllowed;
    /** What the empty option reads as: the text function's answer for {@code null}. */
    private I18nString emptyLabel = I18nString.literal("");
    /** The empty option's icon: the icon function's answer for {@code null}. */
    private @Nullable Icon emptyIcon;
    /** How a chip is drawn, or {@code null} for the default pill; see {@link #setChipRenderer}. */
    private @Nullable Function<? super T, ? extends Widget<?>> chipRenderer;
    /** The chips standing for a selection of many in the field, while holding many; else null. */
    private @Nullable ChipStrip chipStrip;
    /** The field's hint while it reads nothing; see {@link #setPlaceholder(I18nString)}. */
    private I18nString placeholder = I18nString.EMPTY;
    /** Moves with every change of a selection of many: the summary's witness. */
    private long selectionSerial;
    /** "3 selecionados: Aberto, Faturado, Enviado", built once per selection and language. */
    private @Nullable String summary;
    private long summaryBuiltAt = Long.MIN_VALUE;
    /** How many items the combo holds; see {@link #setSelectionMode}. */
    private SelectionMode selectionMode = SelectionMode.SINGLE;
    /** The items selected in {@code MULTI}, by index; empty in {@code SINGLE}. */
    private final java.util.BitSet chosen = new java.util.BitSet();
    /** The highlighted row of the list, an index into {@link #rows}; -1 when the list shows none. */
    private int highlightedRow;
    /**
     * The rows the list shows, as indices into {@link #items}: every item in order, unless an
     * editable combo's text filters them. A row is where an item is drawn; an item is
     * what is picked, and the accessible option keeps its item's index as its key, so an option
     * keeps its identity when a filter moves it.
     */
    private int[] rows;

    /** How long a pause splits one type-ahead word from the next. */
    private static final long TYPE_AHEAD_RESET_NANOS = 1_000_000_000L;

    private final StringBuilder typeAhead = new StringBuilder();
    private long lastTypedNanos;
    private boolean open;
    private final Transition hover =
            new Transition(this).duration(Theme.of(this).animHover).easing(Theme.of(this).animEasing);
    private final Transition focusFade =
            new Transition(this).duration(Theme.of(this).animFocus).easing(Theme.of(this).animEasing);
    private @Nullable Runnable onSelect;
    /** Whether the field is text the user types into; see {@link #setEditable}. */
    private boolean editable;
    /** The text the user types into, while the combo is editable. */
    private @Nullable Editor editor;
    /** How a typed text that names no item becomes one, or {@code null} to accept items only. */
    private @Nullable Function<String, ? extends T> freeText;
    /** The value a free text became, while {@link #selectedIndex} is -1. */
    private @Nullable T freeValue;
    /** That text, as the field shows it and the tree publishes it; empty for no selection. */
    private I18nString freeLabel = I18nString.literal("");
    /** How much of a refused text is read back: a paste is typed too. */
    private static final int REFUSAL_QUOTE_LIMIT = 32;
    /** Whether an item matches the typed text, or {@code null} for the default rule. */
    private @Nullable BiPredicate<? super T, String> filter;
    /** Makes the grafted list's highlight the tree's cursor while the editor keeps the keyboard. */
    private @Nullable Subscription cursorHandle;

    /** Reused each paint; the caret's 3 points are recomputed from the size. */
    private final Path2D caret = new Path2D();

    private NativeWindow popupWindow;
    private Scene popupScene;
    private PopupPanel popupPanel;
    /**
     * The in-scene presentation, non-null instead of {@link #popupWindow} on a platform that
     * cannot place a window. Exactly one of the two is ever set while the popup is open.
     */
    private ScenePopup scenePopup;
    /** What the application asked for. The platform may override it towards IN_SCENE, never away. */
    private DisplayMode requested = DisplayMode.NATIVE_WINDOW;
    /** Overlay fade, 0..1: the in-scene twin of the popup window's compositor fade. */
    private float sceneFade;
    /**
     * The step the open popup's window was <em>sized</em> at. The window is sized once, at
     * creation, from {@code popupItemHeight}/{@code popupPadV}; a step change while it is open
     * would re-measure the rows inside a stale window and clip the last one, so the popup
     * closes instead and reopens at the new step.
     *
     * <p>The in-scene presentation is laid out on every pass and so re-fits by itself; the
     * close-and-reopen is guarded on there being a window, not on this field.
     */
    private ControlSize popupStep;
    /**
     * The direction the open popup <em>window</em> was last laid out for, resolved when the
     * window was created and again whenever this field re-measures.
     *
     * <p>Not a size key and not a reason to close: a flip changes neither the window's width
     * nor its height (the pad is on both sides and the caret gutter is a magnitude), so the
     * panel re-lays out and repaints in place. It is held at all because the panel is the root
     * of <em>another</em> scene, reachable only through the host link: bumping the direction
     * epoch in this tree damages nothing over there, and the list would keep painting
     * yesterday's side until something else touched that window.
     *
     * <p>Unused by the in-scene presentation, which is an overlay in the owner's own scene and
     * so is laid out and repainted by the pass that already noticed the change.
     */
    private boolean popupRtl;
    /** Unregisters the outside-press dismiss observer while the popup is open. */
    private Subscription dismissHandle;
    private Subscription blurHandle;
    private Subscription popupBlurHandle;
    /** Closes a list that opened towards a window of its own when the owner changes fullscreen. */
    private Subscription fullscreenHandle;

    /**
     * A combo over items that read as themselves: a {@link String} as it is, an
     * {@link I18nString} in the UI language (a theme or unit picker, whose words follow a language
     * change), anything else by its {@code toString()}. For items that read some other way, give
     * the text: {@link #ComboBox(List, Function)}.
     *
     * @param items the items, in the order the list shows them; at least one
     * @throws IllegalArgumentException for an empty list
     */
    public ComboBox(List<? extends T> items) {
        this(items, ComboBox::textOfItself);
    }

    /**
     * A combo over items that read as {@code text} says: {@code new ComboBox<>(customers,
     * Customer::name)}. The text may be a {@link String} or an {@link I18nString}, which follows
     * the UI language; anything else reads by its {@code toString()}. Asked once per item, here.
     *
     * @param items the items, in the order the list shows them; at least one
     * @param text  how an item reads in the field and the list
     * @throws IllegalArgumentException for an empty list
     */
    public ComboBox(List<? extends T> items, Function<? super T, ?> text) {
        Objects.requireNonNull(text, "text");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("ComboBox needs at least one item");
        }
        this.textOf = text;
        this.values = List.copyOf(items);
        this.items = labelsOf(values, text);
        this.rows = everyRow();
        setFocusable(true);
        setCursor(Cursor.POINTER);
        followOwnEnabled();
    }

    /**
     * A combo whose items follow the UI language: a theme or unit picker, as opposed
     * to a list of file names. The items are held as values, so a language change
     * re-measures the popup instead of leaving yesterday's words in it.
     */
    public static ComboBox<I18nString> localized(List<I18nString> items) {
        return new ComboBox<>(items);
    }

    /**
     * A combo whose items come from a search the application runs as the user types, and start
     * empty: {@code new ComboBox<>(Customer::name).setSearch(text -> Ui.async(() -> find(text)))}.
     * It is editable from the start, since there is nothing to choose before something is typed.
     *
     * @param text how an item reads in the field and the list
     */
    public ComboBox(Function<? super T, ?> text) {
        this.textOf = Objects.requireNonNull(text, "text");
        this.values = List.of();
        this.items = List.of();
        this.rows = new int[0];
        this.selectedIndex = -1;
        setFocusable(true);
        setCursor(Cursor.POINTER);
        followOwnEnabled();
        setEditable(true);
    }

    /**
     * Carries this combo's enabled flag to the text field inside it, which paints and publishes
     * from its own: a disabled editable combo drew its text and its fill as an enabled field's,
     * while the pointer and the keyboard already could not reach it.
     */
    private void followOwnEnabled() {
        observeChanges((source, change) -> {
            if (source == this && change.aspect() == Change.Aspect.ENABLED && editor != null) {
                editor.setEnabled(isEnabled());
            }
        });
    }

    private static <V> List<I18nString> labelsOf(List<V> values, Function<? super V, ?> text) {
        List<I18nString> labels = new java.util.ArrayList<>(values.size());
        for (V value : values) {
            labels.add(asText(text.apply(value)));
        }
        return List.copyOf(labels);
    }

    private static int[] allRows(int count) {
        int[] all = new int[count];
        for (int i = 0; i < count; i++) {
            all[i] = i;
        }
        return all;
    }

    /**
     * Every row the list shows with no text filtering it: the empty option first, while a combo
     * holding one item may hold none, then every item.
     */
    private int[] everyRow() {
        int n = items.size();
        if (!showsEmptyRow()) {
            return allRows(n);
        }
        int[] all = new int[n + 1];
        all[0] = EMPTY_ROW;
        for (int i = 0; i < n; i++) {
            all[i + 1] = i;
        }
        return all;
    }

    /** Whether the list offers the empty option: holding one item, over a list, with it allowed. */
    private boolean showsEmptyRow() {
        return emptyAllowed && selectionMode == SelectionMode.SINGLE && search == null;
    }

    /** Whether the combo holds nothing, and it is the empty option that says so. */
    private boolean emptyChosen() {
        return emptyAllowed && selectionMode == SelectionMode.SINGLE && selectedIndex < 0
                && freeValue == null;
    }

    /** @return what row item {@code item} reads as, the empty option included */
    private I18nString labelOfRow(int item) {
        return item == EMPTY_ROW ? emptyLabel : items.get(item);
    }

    /** @return the icon of row item {@code item}, the empty option included */
    private @Nullable Icon iconOfRow(int item) {
        return item == EMPTY_ROW ? emptyIcon : iconOfItem(item);
    }

    /** @return the icon the field shows: the selected item's, or the empty option's */
    private @Nullable Icon selectedIcon() {
        return selectedIndex >= 0 ? iconOfItem(selectedIndex) : emptyChosen() ? emptyIcon : null;
    }

    /**
     * Asks one of the application's functions about the empty option, which it is handed as
     * {@code null}. A function that cannot take {@code null} (a method reference on the item,
     * {@code Customer::name}) gives the empty option nothing rather than failing the combo.
     */
    private static <R> @Nullable R askForEmpty(@Nullable Function<?, ? extends R> function) {
        if (function == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Function<Object, ? extends R> f = (Function<Object, ? extends R>) function;
            return f.apply(null);
        } catch (NullPointerException refused) {
            return null;
        }
    }

    /** @return the items the list shows, in its order: for tests of the filter */
    int[] rowsForTest() {
        return rows.clone();
    }

    /** @return the row showing item {@code item}, or -1 when the list does not show it */
    private int rowOfItem(int item) {
        for (int row = 0; row < rows.length; row++) {
            if (rows[row] == item) {
                return row;
            }
        }
        return -1;
    }

    private static Object textOfItself(Object item) {
        return item;
    }

    private static I18nString asText(@Nullable Object text) {
        if (text == null) {
            return I18nString.literal("");
        }
        return text instanceof I18nString localized ? localized : I18nString.literal(String.valueOf(text));
    }

    // ------------------------------------------------------------------- API

    /**
     * The application's response to the user changing the selection: a click on a row, Enter on
     * the highlight, an assistive technology's select, a typed text the field accepted. Never for
     * {@link #setSelectedIndex} or the other setters, which are a caller's writes; to hear every
     * change whatever caused it, {@linkplain #observeChanges watch} the combo instead. The handler
     * reads what it needs, {@link #selectedItem()} or, holding many, {@link #selectedItems()}, as
     * a {@link ListView}'s does.
     *
     * @param handler the handler, or {@code null} to clear the slot
     * @return this combo
     * @throws IllegalStateException if a handler is already registered
     */
    public ComboBox<T> onSelect(@Nullable Runnable handler) {
        Ui.checkUiThread();
        this.onSelect = Checks.handlerSlot(onSelect, handler, "ComboBox.onSelect");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.SELECTION) {
            if (onSelect != null) {
                onSelect.run();
            }
            return;
        }
        super.handleUserChange(aspect);
    }

    /**
     * The selected item's index. Never negative for a combo that chooses: it refuses an empty item
     * list and so always has exactly one selection. -1 only for an editable combo with free text
     * ({@link #setFreeText}), while its value is a text the list does not hold, or none.
     */
    public int selectedIndex() {
        return selectedIndex;
    }

    /**
     * The selected item itself: one of the items, or what a free text became, or {@code null} when
     * an editable combo with free text was emptied. Holding many, the first of them in the list's
     * order, or {@code null} for none.
     */
    public @Nullable T selectedItem() {
        return selectedIndex >= 0 ? values.get(selectedIndex) : freeValue;
    }

    /**
     * The selected item as it currently reads, in the UI language: empty when nothing is selected.
     * Holding many, their texts in the list's order, joined as the language joins a list.
     */
    public String selectedText() {
        if (selectionMode == SelectionMode.MULTI) {
            return joinedChosen();
        }
        return selectedLabel().get();
    }

    /** The chosen items' texts, in the list's order, joined by the language's list separator. */
    private String joinedChosen() {
        StringBuilder out = new StringBuilder();
        String separator = ComponentStrings.COMBO_SEPARATOR.get();
        for (int i = chosen.nextSetBit(0); i >= 0 && i < items.size(); i = chosen.nextSetBit(i + 1)) {
            if (!out.isEmpty()) {
                out.append(separator);
            }
            out.append(items.get(i).get());
        }
        return out.toString();
    }

    // ------------------------------------------------------------- many items (ADR 052)

    /**
     * How many items the combo holds: {@link SelectionMode#SINGLE}, the default, or
     * {@link SelectionMode#MULTI}, where the list stays open while items are toggled and the field
     * shows them as chips. {@code NONE} is refused: a combo box that chooses nothing is a label.
     * Switching to {@code MULTI} starts with nothing selected, since the item a combo holding one
     * must hold is not one the user chose, and a filter that opened on it would already be
     * filtering; switching back keeps the first selected, or the first item when none was. Each
     * is announced as {@code SELECTION}/{@code ADJUSTMENT} when it moves anything. Not with {@link #setSearch} or {@link #setFreeText}. UI thread
     * only.
     *
     * @param mode {@code SINGLE} or {@code MULTI}
     * @return this combo
     * @throws IllegalArgumentException for {@code NONE}
     * @throws IllegalStateException for {@code MULTI} on a combo that searches or takes free text
     */
    public ComboBox<T> setSelectionMode(SelectionMode mode) {
        Ui.checkUiThread();
        Objects.requireNonNull(mode, "mode");
        if (mode == SelectionMode.NONE) {
            throw new IllegalArgumentException("a combo box chooses at least one item: SINGLE or MULTI");
        }
        if (mode == selectionMode) {
            return this;
        }
        if (mode == SelectionMode.MULTI && (search != null || freeText != null)) {
            throw new IllegalStateException("a combo that searches or takes free text holds one item"
                    + "; a selection of many is held over a list in memory");
        }
        if (open) {
            close(Change.Origin.CODE);
        }
        selectionMode = mode;
        rows = everyRow(); // the empty option belongs to a combo holding one
        if (mode == SelectionMode.MULTI) {
            chosen.clear();
            boolean moved = selectedIndex >= 0;
            selectedIndex = -1;
            if (moved) {
                notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT));
            }
        } else {
            int keep = chosen.nextSetBit(0);
            if (keep < 0 && emptyAllowed) {
                keep = -1; // nothing held is a state this combo has: the empty option stands
            } else if (keep < 0) {
                keep = 0;
            }
            boolean moved = keep < 0 ? false : chosen.cardinality() != 1;
            chosen.clear();
            selectedIndex = keep;
            if (moved) {
                notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT));
            }
        }
        syncEditor();
        refreshChips();
        markNeedsLayout();
        invalidate();
        invalidateAccessible();
        return this;
    }

    /** @return how many items the combo holds; see {@link #setSelectionMode} */
    public SelectionMode selectionMode() {
        return selectionMode;
    }

    /** @return the selected items, in the list's order; empty when nothing is selected */
    public List<T> selectedItems() {
        if (selectionMode == SelectionMode.SINGLE) {
            T one = selectedItem();
            return one == null ? List.of() : java.util.Collections.singletonList(one);
        }
        List<T> out = new java.util.ArrayList<>(chosen.cardinality());
        for (int i = chosen.nextSetBit(0); i >= 0 && i < values.size(); i = chosen.nextSetBit(i + 1)) {
            out.add(values.get(i));
        }
        return out;
    }

    /** @return every selected item's index, ascending; empty when nothing is selected */
    public int[] selectedIndices() {
        if (selectionMode == SelectionMode.SINGLE) {
            return selectedIndex >= 0 ? new int[] {selectedIndex} : new int[0];
        }
        return chosen.stream().toArray();
    }

    /**
     * @param index an item
     * @return whether it is selected
     */
    public boolean isSelected(int index) {
        return selectionMode == SelectionMode.SINGLE ? index >= 0 && index == selectedIndex
                : index >= 0 && chosen.get(index);
    }

    /**
     * Selects exactly these items: a caller's write, announced as {@code SELECTION}/{@code CODE}
     * when it moved anything, and reaching no handler. Holding one, exactly one index. UI thread
     * only.
     *
     * @param indices items in {@code [0, itemCount)}
     * @return this combo
     * @throws IndexOutOfBoundsException for an index that is not an item
     * @throws IllegalArgumentException for other than one index on a combo holding one item
     */
    public ComboBox<T> setSelectedIndices(int... indices) {
        Ui.checkUiThread();
        for (int index : indices) {
            Objects.checkIndex(index, items.size());
        }
        if (selectionMode == SelectionMode.SINGLE) {
            if (indices.length != 1) {
                throw new IllegalArgumentException("a combo holding one item takes one index, got "
                        + indices.length);
            }
            select(indices[0], Change.Origin.CODE);
            return this;
        }
        java.util.BitSet wanted = new java.util.BitSet();
        for (int index : indices) {
            wanted.set(index);
        }
        choose(wanted, Change.Origin.CODE);
        return this;
    }

    /**
     * Selects exactly these items, compared with {@code equals}: a caller's write, as
     * {@link #setSelectedIndices} is.
     *
     * @param selection some of the items
     * @return this combo
     * @throws IllegalArgumentException for an item that is not one of them, or for other than one
     *         item on a combo holding one
     */
    public ComboBox<T> setSelectedItems(java.util.Collection<? extends T> selection) {
        Ui.checkUiThread();
        int[] indices = new int[selection.size()];
        int n = 0;
        for (T item : selection) {
            int index = values.indexOf(item);
            if (index < 0) {
                throw new IllegalArgumentException("not an item of this combo: " + item);
            }
            indices[n++] = index;
        }
        return setSelectedIndices(indices);
    }

    /**
     * Selects every item, holding many; nothing holding one. A caller's write, announced as
     * {@code SELECTION}/{@code CODE} when it moved anything. UI thread only.
     *
     * @return this combo
     */
    public ComboBox<T> selectAll() {
        Ui.checkUiThread();
        selectAll(Change.Origin.CODE);
        return this;
    }

    private void selectAll(Change.Origin origin) {
        if (selectionMode != SelectionMode.MULTI) {
            return;
        }
        java.util.BitSet every = new java.util.BitSet();
        every.set(0, items.size());
        choose(every, origin);
    }

    /**
     * Drops the selection, holding many, or with free text on; a combo that chooses one item always
     * holds one, and refuses. A caller's write, announced as {@code SELECTION}/{@code CODE} when it
     * moved anything. UI thread only.
     *
     * @return this combo
     * @throws IllegalStateException on a combo holding one item without free text
     */
    public ComboBox<T> clearSelection() {
        Ui.checkUiThread();
        if (selectionMode == SelectionMode.MULTI) {
            choose(new java.util.BitSet(), Change.Origin.CODE);
        } else if (emptyAllowed) {
            selectEmpty(Change.Origin.CODE);
        } else if (freeText != null) {
            selectFree("", null, Change.Origin.CODE);
        } else {
            throw new IllegalStateException("a combo that chooses one item always holds one;"
                    + " setFreeText lets it be emptied");
        }
        return this;
    }

    /**
     * Lets a combo holding one item hold none: the list gains a first option that stands for
     * "nothing", whose value is {@code null}, and choosing it empties the combo. It reads as the
     * item text function answers for {@code null}, and shows the icon the icon function answers
     * for it, so the application words it ("None", "Any status") as it words its items; a
     * function that cannot take {@code null} leaves it blank. Turned off while nothing is held,
     * the combo takes the first item. Holding many, a selection may always be empty and the list
     * shows no such option. UI thread only.
     *
     * @param allowed whether the combo may hold nothing
     * @return this combo
     */
    public ComboBox<T> setEmptySelectionAllowed(boolean allowed) {
        Ui.checkUiThread();
        if (allowed == emptyAllowed) {
            return this;
        }
        if (open) {
            close(Change.Origin.CODE);
        }
        emptyAllowed = allowed;
        emptyLabel = asText(askForEmpty(textOf));
        emptyIcon = askForEmpty(iconOf);
        rows = everyRow();
        if (!allowed && selectionMode == SelectionMode.SINGLE && selectedIndex < 0
                && freeText == null && !items.isEmpty()) {
            select(0, Change.Origin.ADJUSTMENT);
        }
        syncEditor();
        markNeedsLayout();
        invalidate();
        return this;
    }

    /** @return whether a combo holding one item may hold none; see {@link #setEmptySelectionAllowed} */
    public boolean isEmptySelectionAllowed() {
        return emptyAllowed;
    }

    /** Empties a combo holding one item, through the empty option. Announces only when it moved. */
    private void selectEmpty(Change.Origin origin) {
        if (selectedIndex < 0 && freeValue == null && freeLabel.get().isEmpty()) {
            return;
        }
        selectedIndex = -1;
        freeValue = null;
        freeLabel = I18nString.literal("");
        invalidate();
        syncEditor();
        notifyChange(Change.of(Change.Aspect.SELECTION, origin));
    }

    /** The one seam a many-item selection moves through; announces only when it moved. */
    private void choose(java.util.BitSet wanted, Change.Origin origin) {
        if (wanted.equals(chosen)) {
            return;
        }
        chosen.clear();
        chosen.or(wanted);
        chosenMoved(origin);
    }

    /** Flips one item's membership: a click on its row, Space or Enter on it, a reader's toggle. */
    private void toggle(int item, Change.Origin origin) {
        chosen.flip(item);
        chosenMoved(origin);
    }

    private void chosenMoved(Change.Origin origin) {
        selectedIndex = chosen.nextSetBit(0);
        selectionSerial++;
        refreshChips();
        invalidate();
        markNeedsLayout(); // the chips
        if (popupPanel != null) {
            popupPanel.invalidate(); // the check marks
        }
        if (popupWindow != null) {
            popupWindow.requestFrame();
        }
        notifyChange(Change.of(Change.Aspect.SELECTION, origin));
    }

    private I18nString selectedLabel() {
        if (selectedIndex >= 0) {
            return items.get(selectedIndex);
        }
        // The empty option reads as itself, in the field that shows a label and in the text of an
        // editable one alike: an editable field showing only its icon read as a broken field.
        return emptyChosen() ? emptyLabel : freeLabel;
    }

    /**
     * Lets an editable combo take a text that names no item, as a "City" that may be new or a tag
     * does: leaving the field with such a text makes it the value, through {@code fromText}, and
     * leaving it empty clears the value. {@code null}, the default, accepts items only: such a text
     * is refused, put back to the selected item's, and a screen reader is told so. UI thread only.
     *
     * @param fromText how a text becomes an item, or {@code null} to accept items only
     * @return this combo
     */
    public ComboBox<T> setFreeText(@Nullable Function<String, ? extends T> fromText) {
        Ui.checkUiThread();
        if (fromText != null && selectionMode == SelectionMode.MULTI) {
            throw new IllegalStateException("a combo holding many items takes no free text"
                    + "; a selection of many is held over a list in memory");
        }
        this.freeText = fromText;
        return this;
    }

    /**
     * Selects {@code item}: a caller's write, as {@link #setSelectedIndex} is.
     *
     * @param item one of the items, compared with {@code equals}
     * @return this combo
     * @throws IllegalArgumentException if {@code item} is not one of them
     */
    public ComboBox<T> setSelectedItem(T item) {
        Ui.checkUiThread();
        int index = values.indexOf(item);
        if (index < 0) {
            throw new IllegalArgumentException("not an item of this combo: " + item);
        }
        select(index, Change.Origin.CODE);
        return this;
    }

    /**
     * Makes the field text the user types into, or a button again: typing filters the list, the
     * arrows walk it, Enter picks the highlighted item and leaving the field picks the item the
     * text names, or puts the selected item's text back. The field keeps the caret, the
     * selection, the clipboard and the input method while the list is up, as a text field does.
     * UI thread only.
     *
     * @param value whether the field is editable
     * @return this combo
     */
    public ComboBox<T> setEditable(boolean value) {
        Ui.checkUiThread();
        if (value == editable) {
            return this;
        }
        if (open) {
            close(Change.Origin.CODE);
        }
        editable = value;
        if (value) {
            editor = new Editor(this);
            editor.setEnabled(isEnabled());
            editor.setPlaceholder(placeholder);
            add(editor);
            syncEditor(); // the selected item's text, or holding many, none
            refreshChips(); // back above the editor
            setFocusable(false); // the editor takes the keyboard; Tab lands there
        } else {
            Editor gone = editor;
            editor = null;
            remove(gone);
            rows = everyRow();
            if (selectedIndex < 0 && selectionMode == SelectionMode.SINGLE) {
                select(0, Change.Origin.CODE); // a combo that chooses always holds an item
            }
            setFocusable(true);
        }
        markNeedsLayout();
        invalidate();
        return this;
    }

    /** @return whether the field is text the user types into; see {@link #setEditable} */
    public boolean isEditable() {
        return editable;
    }

    /**
     * Replaces the rule an editable combo filters its list by: whether an item matches what is
     * typed. The default finds the text anywhere in an item's text, ignoring case and accents, and
     * lists the items that start with it first; a predicate lists the items it accepts in their
     * order. UI thread only.
     *
     * @param rule the rule, or {@code null} for the default
     * @return this combo
     */
    public ComboBox<T> setFilter(@Nullable BiPredicate<? super T, String> rule) {
        Ui.checkUiThread();
        this.filter = rule;
        if (open && editor != null) {
            showRowsFor(editor.text());
            setHighlight(rows.length == 0 ? -1 : 0, Change.Origin.ADJUSTMENT);
            refitPopup();
        }
        return this;
    }

    /**
     * Makes an editable combo take its items from a search the application runs in the background
     * as the user types, instead of filtering a list: {@code text -> Ui.async(() -> find(text))}.
     * The search is asked {@value #SEARCH_DELAY_MILLIS} ms after the last keystroke, the list says
     * it is loading meanwhile, an answer for a text the user has typed past is dropped, and the
     * number found is told to a screen reader. While a search supplies the items the value is held
     * by itself and {@link #selectedIndex()} answers -1. UI thread only.
     *
     * @param finder the search, or {@code null} to go back to the list in memory
     * @return this combo
     */
    public ComboBox<T> setSearch(
            @Nullable Function<String, ? extends java.util.concurrent.CompletionStage<? extends List<? extends T>>> finder) {
        Ui.checkUiThread();
        if (finder != null && selectionMode == SelectionMode.MULTI) {
            throw new IllegalStateException("a combo holding many items does not search"
                    + "; a selection of many is held over a list in memory");
        }
        this.search = finder;
        searchSerial++;
        statusLine = null;
        return this;
    }

    /**
     * Gives each item an icon, shown before its text in the list, in a chip and in the field of a
     * combo holding one item. Tinted with the text's ink as a {@link Label}'s is, so a
     * picture that must keep its colours is an icon that is not {@linkplain Icon#tintable()
     * tintable}. Asked once per item, here and when a search answers. UI thread only.
     *
     * @param icon an item's icon, answering {@code null} for an item without one; or {@code null}
     *             to show none
     * @return this combo
     */
    public ComboBox<T> setItemIcon(@Nullable Function<? super T, ? extends @Nullable Icon> icon) {
        Ui.checkUiThread();
        this.iconOf = icon;
        icons = iconsOf(values);
        emptyIcon = emptyAllowed ? askForEmpty(icon) : null;
        syncEditor();
        if (chipStrip != null) {
            chipStrip.forget(); // a custom chip may show the icon too
        }
        refreshChips();
        markNeedsLayout();
        invalidate();
        if (popupPanel != null) {
            popupPanel.invalidate();
        }
        return this;
    }

    /**
     * A fixed hint the field shows, muted, while it reads nothing: a combo holding many with none
     * selected, an empty option with no text of its own, an editable combo's emptied text. It is
     * never the value: an empty option that has words shows them, and the hint only fills a field
     * that would otherwise be blank. It names the combo for a screen reader when nothing else
     * does, as a text field's placeholder does.
     *
     * @param hint the hint; empty for none
     * @return this combo
     */
    public ComboBox<T> setPlaceholder(String hint) {
        return setPlaceholder(I18nString.literal(Objects.requireNonNull(hint, "hint")));
    }

    /**
     * {@link #setPlaceholder(String)}, in words that follow the UI language. UI thread only.
     *
     * @param hint the hint; {@link I18nString#EMPTY} for none
     * @return this combo
     */
    public ComboBox<T> setPlaceholder(I18nString hint) {
        Ui.checkUiThread();
        Objects.requireNonNull(hint, "hint");
        if (hint.equals(placeholder)) {
            return this;
        }
        placeholder = hint;
        if (editor != null) {
            editor.setPlaceholder(hint);
        }
        markNeedsLayout();
        invalidate();
        notifyChange(Change.of(Change.Aspect.DESCRIPTION, Change.Origin.CODE));
        return this;
    }

    /** @return the hint as it currently reads; see {@link #setPlaceholder(String)} */
    public String placeholder() {
        return placeholder.get();
    }

    /** Whether the field reads nothing, so that the hint stands in it. */
    private boolean showsPlaceholder() {
        if (placeholder.get().isEmpty()) {
            return false;
        }
        return selectionMode == SelectionMode.MULTI ? chosen.isEmpty() : selectedText().isEmpty();
    }

    /** What a screen reader is told a selection of many holds: the count and the names. */
    String summary() {
        long at = summaryWitness();
        if (summary == null || at != summaryBuiltAt) {
            int count = chosen.cardinality();
            summary = count == 0 ? ComponentStrings.COMBO_NONE_SELECTED.get()
                    : ComponentStrings.COMBO_SELECTED.format(count, joinedChosen());
            summaryBuiltAt = at;
        }
        return summary;
    }

    /** Moves whenever {@link #summary()} would: the selection or the language. */
    long summaryWitness() {
        return selectionSerial * 1_000_003L + I18n.epoch();
    }

    /**
     * Replaces how a chip is drawn in the field of a combo holding many: the widget standing for an
     * item, asked once per item when it is first selected. It is drawn and never operated, taking
     * no pointer and no keyboard, and it is not in the accessible tree, since a reader hears the
     * field's value, which names every item. UI thread only.
     *
     * @param renderer the widget for an item, or {@code null} for the default chip, a pill with
     *                 the item's icon and text
     * @return this combo
     * @throws IllegalArgumentException later, when a chip it answers can take the keyboard
     */
    public ComboBox<T> setChipRenderer(@Nullable Function<? super T, ? extends Widget<?>> renderer) {
        Ui.checkUiThread();
        this.chipRenderer = renderer;
        if (chipStrip != null) {
            chipStrip.forget();
        }
        refreshChips();
        return this;
    }

    /** Whether the chips stand in the field now: holding many, with no text typed over them. */
    boolean chipsShown() {
        return chipStrip != null && chipStrip.isVisible() && !chosen.isEmpty();
    }

    /**
     * Puts the chip strip in step with the mode, the selection and the text: present while holding
     * many, above the editor, and shown while there is no text typed.
     */
    private void refreshChips() {
        if (selectionMode != SelectionMode.MULTI) {
            if (chipStrip != null) {
                remove(chipStrip);
                chipStrip = null;
                markNeedsLayout();
            }
            return;
        }
        if (chipStrip == null) {
            chipStrip = new ChipStrip();
            add(chipStrip);
        } else if (children().indexOf(chipStrip) != children().size() - 1) {
            remove(chipStrip); // after the editor, so it paints over the field the editor draws
            add(chipStrip);
        }
        chipStrip.setVisible(editor == null || editor.text().isEmpty());
        chipStrip.rebuild();
        markNeedsLayout();
        invalidate();
    }

    private List<@Nullable Icon> iconsOf(List<T> of) {
        Function<? super T, ? extends @Nullable Icon> icon = iconOf;
        if (icon == null) {
            return List.of();
        }
        List<@Nullable Icon> out = new java.util.ArrayList<>(of.size());
        for (T value : of) {
            out.add(icon.apply(value));
        }
        return java.util.Collections.unmodifiableList(out);
    }

    /** @return item {@code index}'s icon, or {@code null} when it has none or no item has one */
    private @Nullable Icon iconOfItem(int index) {
        return index >= 0 && index < icons.size() ? icons.get(index) : null;
    }

    /** @return what the field shows: the text typed into an editable combo, else the selected item's */
    public String text() {
        return editor != null ? editor.text() : selectedText();
    }

    /**
     * Selects an item: a caller's write, so it announces {@code SELECTION}/{@code CODE} and
     * reaches no handler. Selecting what is already selected changes nothing and announces
     * nothing. UI thread only.
     *
     * @param index an item in {@code [0, itemCount)}
     * @throws IndexOutOfBoundsException if {@code index} is not an item; an index computed from a
     *         lookup that missed is a caller's bug here, not a request for the nearest item
     */
    public ComboBox<T> setSelectedIndex(int index) {
        Ui.checkUiThread();
        Objects.checkIndex(index, items.size());
        select(index, Change.Origin.CODE);
        return this;
    }

    /**
     * The one seam every selection goes through: the public setter passes {@code CODE}, and a
     * pick from the popup, the Enter/Space path and an assistive technology's select pass
     * {@code USER} through {@link #commit}. Announces only when the index moved.
     */
    private void select(int index, Change.Origin origin) {
        if (selectionMode == SelectionMode.MULTI) {
            java.util.BitSet only = new java.util.BitSet();
            only.set(index);
            choose(only, origin); // the whole selection, as a ListView's setter makes it
            return;
        }
        if (index == selectedIndex) {
            return;
        }
        selectedIndex = index;
        freeValue = null;
        freeLabel = I18nString.literal("");
        invalidate();
        syncEditor();
        notifyChange(Change.of(Change.Aspect.SELECTION, origin));
        if (open) {
            // The open list's selection is its highlight, so a value set while it is down is
            // shown there too, as a native drop-down list's selection follows its value.
            setHighlight(index, origin);
        }
    }

    /** Whether the popup is showing. */
    public boolean isOpen() {
        return open;
    }

    /** @return the highlighted popup row (keyboard navigation state) */
    public int highlightedIndex() {
        return highlightedRow < 0 || highlightedRow >= rows.length ? -1 : rows[highlightedRow];
    }

    /**
     * The window the open list is drawn in, for an application that captures it or a test that
     * drives it: the list is a window of its own, so a capture of the owner window does not
     * contain it. Supported API, not a test hook.
     *
     * @return the popup's native window while open, else {@code null}, which is also the answer
     *         while an open popup is drawn {@linkplain ComboBox in the scene}, where there is no
     *         window to return
     */
    public @Nullable NativeWindow popupWindow() {
        return popupWindow;
    }

    // ------------------------------------------------------------ open/close

    /**
     * Opens the popup (native window when bound; pure state when headless). Announces
     * {@code EXPANDED}/{@code CODE}; the click, the keys and an assistive technology's expand
     * announce it as {@code USER} through the same seam.
     */
    public void open() {
        Ui.checkUiThread();
        open(Change.Origin.CODE);
    }

    private void open(Change.Origin origin) {
        Ui.checkUiThread();
        if (open) {
            return;
        }
        open = true;
        int selectedRow = Math.max(0, rowOfItem(selectedIndex));
        boolean highlightMoved = highlightedRow != selectedRow;
        highlightedRow = rows.length == 0 ? -1 : selectedRow;
        typeAhead.setLength(0); // a new list is a new word, however recently the last one was typed
        invalidate();
        if (highlightMoved) {
            // The highlight snapping back to the selection is a consequence of opening.
            notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.ADJUSTMENT));
        }
        Scene scene = scene();
        if (scene == null) {
            notifyChange(Change.of(Change.Aspect.EXPANDED, origin));
            return;
        }
        // A press in another window/app never reaches observePresses: dismiss on OS focus loss
        // instead, or the always-on-top popup floats over foreign applications indefinitely.
        // In-scene the same rule reads as "the application went away", which closes it too.
        blurHandle = scene.observeWindowBlur(() -> Ui.post(this::closeUnlessRefocused));
        // A fullscreen owner gets no list window (at a named mode it would be minimized at the
        // first click in the list, and at the mode the display is in it already covers the screen,
        // so the list loses nothing inside it), and one that cannot be positioned would put the
        // list away from the field: both draw it in scene, whatever was asked.
        if (requested == DisplayMode.IN_SCENE || (scene.window() != null
                && !FloatingSurfaces.anchoredWindowAvailable(scene.window()))) {
            presentInScene();
            return;
        }
        // Any press elsewhere in the parent window dismisses the popup, even on non-focusable
        // widgets, which never move focus. Native only: an overlay captures every press in the
        // scene, including its own list's, so this observer would close the popup on the press
        // that was choosing an item.
        dismissHandle = scene.observePresses(target -> {
            // A press inside the combo is not "elsewhere": an editable combo's list button is in
            // its text field, and this observer, registered while that very press opened the
            // list, hears the press after it is dispatched and closed the list it had opened.
            if (!isInside(target)) {
                close(Change.Origin.USER);
            }
        });
        // The owner going fullscreen, or leaving it, takes the list down as a platform's own
        // drop-down goes on a change of its window's state: the change resizes and moves the owner
        // under a list placed against the field's old position, and one left open over a
        // fullscreen at a named mode would minimize it at the first click. Dismissed, so the
        // highlight is not chosen. An in-scene list is left alone: it is laid out again with the
        // window, against the field where it now is.
        fullscreenHandle = scene.observeWindowFullscreen(
                fullscreen -> close(Change.Origin.ADJUSTMENT));
        if (scene.window() != null && popupWindow == null) {
            // Defer: creating a window switches the GL context, which must
            // not happen in the middle of another window's frame callback.
            Ui.post(this::createPopupWindow);
        }
    }

    /** @return whether {@code target} is this combo or inside it (its text field, its chips) */
    private boolean isInside(@Nullable Widget<?> target) {
        for (Widget<?> at = target; at != null; at = at.parent()) {
            if (at == this) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether the open popup is drawn in the owner scene; {@link #popupWindow()} is
     *         {@code null} both here and headless, so it cannot tell the two apart
     */
    boolean isInSceneForTest() {
        return scenePopup != null;
    }

    /**
     * Asks for a presentation of the drop-down list; see {@link DisplayMode}. Default
     * {@link DisplayMode#NATIVE_WINDOW}, so a long list can extend past the window's edge the way
     * a platform combo's does.
     *
     * <p><b>A preference, not a guarantee.</b> A list asked for {@code NATIVE_WINDOW} is still
     * drawn in scene on a window that cannot be positioned at an anchor (Wayland), and over a
     * fullscreen window, for the reasons {@link DisplayMode} gives. {@code IN_SCENE}
     * is always honoured, and it is the choice to make when the window has to contain everything
     * it shows: a screenshot, a recording or a screen share of the owner window captures an
     * in-scene list and never a native one.
     *
     * <p>Takes effect on the next open.
     */
    public ComboBox<T> setDisplayMode(DisplayMode mode) {
        Ui.checkUiThread();
        this.requested = Objects.requireNonNull(mode, "mode");
        return this;
    }

    /**
     * @return how the list is presented: while it is open, what it actually chose, including a
     *         fullscreen owner or a platform forcing {@code IN_SCENE} over a request for a
     *         window. While it is closed, what was last asked for, since the next open decides
     *         afresh over the window as it is then.
     */
    public DisplayMode displayMode() {
        return scenePopup != null ? DisplayMode.IN_SCENE : requested;
    }

    /**
     * Dismisses the popup without changing the selection. No-op when closed. Announces
     * {@code EXPANDED}/{@code CODE}; a dismissal by the user is {@code USER}, and one the widget
     * makes for itself -- the window losing focus, the field being detached -- is an adjustment.
     */
    public void close() {
        Ui.checkUiThread();
        close(Change.Origin.CODE);
    }

    private void close(Change.Origin origin) {
        Ui.checkUiThread();
        if (!open) {
            return;
        }
        open = false;
        if (cursorHandle != null) {
            cursorHandle.cancel();
            cursorHandle = null;
        }
        if (selectionMode == SelectionMode.MULTI && editor != null && !editor.text().isEmpty()) {
            // The chips come back when the list goes (ADR 052 §3): the filter goes with it.
            editor.setText("");
            rows = everyRow();
            refreshChips();
        }
        invalidate();
        notifyChange(Change.of(Change.Aspect.EXPANDED, origin));
        if (dismissHandle != null) {
            dismissHandle.cancel();
            dismissHandle = null;
        }
        if (blurHandle != null) {
            blurHandle.cancel();
            blurHandle = null;
        }
        if (popupBlurHandle != null) {
            popupBlurHandle.cancel();
            popupBlurHandle = null;
        }
        if (fullscreenHandle != null) {
            fullscreenHandle.cancel();
            fullscreenHandle = null;
        }
        if (popupWindow != null) {
            // The options stop offering their verbs now (optionsOperable() reads `open`), and a
            // window's fade moves only its opacity and damages nothing in the panel, so the walk
            // that withdraws them is asked for: without it the popup's tree kept publishing
            // SELECT, PRESS and FOCUS through the whole fade (2026-09-15, semantics 5).
            if (popupPanel != null) {
                popupPanel.invalidateAccessible();
            }
            // Relinquish the fields now (a fresh open() can build a new popup
            // while this one fades out), then fade the old window to transparent
            // and destroy it once it has fully vanished.
            NativeWindow closing = popupWindow;
            Scene closingScene = popupScene;
            popupWindow = null;
            popupScene = null;
            popupPanel = null;
            popupStep = null;
            NativeWindow parent = scene() != null ? scene().window() : null;
            if (closingScene != null && closingScene.isWindowFocused()
                    && parent != null && !parent.isClosed()) {
                // A click in the list made its window the key one, and a destroyed key window
                // hands the keyboard to nobody on macOS: the owner's came back only when it was
                // clicked, so a Tab after choosing a row by pointer went nowhere.
                parent.focus();
            }
            Runnable destroy = () -> {
                if (parent != null && !parent.isClosed()) {
                    WindowPeer.of(parent).unregisterChildPopup(closing);
                }
                closing.requestClose();
            };
            if (closingScene != null) {
                closingScene.fadeWindowOut(limn.animation.Motion.duration(Theme.of(this).animWindow), destroy);
            } else {
                destroy.run();
            }
        }
        if (scenePopup != null) {
            ScenePopup closing = scenePopup;
            Scene owner = scene();
            scenePopup = null;
            popupPanel = null;
            popupStep = null;
            if (owner == null) {
                return; // detached mid-open: nothing holds the overlay any more
            }
            if (owner.window() == null) {
                owner.removeOverlay(closing); // headless: no frame pump to advance a fade
                return;
            }
            // Wall time, not frame time: this fade's last frame is what removes the overlay
            // holding input capture and focus. Frozen, the list would stay open over a paused
            // application with the field underneath unreachable.
            owner.addRealTimeTicker(dt -> {
                sceneFade = (float) Math.max(0, sceneFade - limn.animation.Motion.progress(dt, Theme.of(this).animWindow));
                closing.invalidate();
                if (sceneFade > 0) {
                    return true;
                }
                owner.removeOverlay(closing); // restores focus to the field
                return false;
            });
        }
    }

    /**
     * Deferred one event-loop turn so an intra-app focus switch (owner blurs,
     * popup gains focus in the same input batch, or vice versa) settles first:
     * the popup survives while OS focus is on the owner window or on the popup
     * itself, and dismisses when it moved anywhere else.
     */
    // ------------------------------------------------------------- editable field

    /** Asks the search for {@code text} once the typing pauses; an empty text asks nothing. */
    private void searchFor(String text) {
        int serial = ++searchSerial;
        values = List.of();
        items = List.of();
        icons = List.of();
        rows = new int[0];
        if (text.isEmpty()) {
            statusLine = null;
            if (open) {
                close(Change.Origin.USER);
            }
            return;
        }
        statusLine = ComponentStrings.COMBO_LOADING;
        if (!open) {
            open(Change.Origin.USER);
        }
        setHighlight(-1, Change.Origin.USER);
        refitPopup();
        Ui.postDelayed(() -> startSearch(serial, text), SEARCH_DELAY_MILLIS);
    }

    private void startSearch(int serial, String text) {
        Function<String, ? extends java.util.concurrent.CompletionStage<? extends List<? extends T>>> finder = search;
        if (serial != searchSerial || finder == null) {
            return; // typed past, or the search was taken away
        }
        java.util.concurrent.CompletionStage<? extends List<? extends T>> answer;
        try {
            answer = finder.apply(text);
        } catch (RuntimeException failed) {
            searchAnswered(serial, text, null, failed);
            return;
        }
        answer.whenComplete((found, error) -> Ui.post(() -> searchAnswered(serial, text, found, error)));
    }

    private void searchAnswered(int serial, String text, @Nullable List<? extends T> found, @Nullable Throwable error) {
        if (serial != searchSerial || editor == null || !editor.text().equals(text)) {
            return; // an answer for a text the user has typed past
        }
        if (error != null || found == null) {
            LOG.log(System.Logger.Level.WARNING, "the combo box's search for \"" + text + "\" failed", error);
            statusLine = ComponentStrings.COMBO_SEARCH_FAILED;
        } else {
            values = List.copyOf(found);
            items = labelsOf(values, textOf);
            icons = iconsOf(values);
            rows = everyRow();
            statusLine = rows.length == 0 ? ComponentStrings.COMBO_NO_RESULTS : null;
            Scene scene = scene();
            if (scene != null) {
                scene.announce(rows.length == 0 ? ComponentStrings.COMBO_NO_RESULTS.get()
                        : ComponentStrings.COMBO_RESULTS.format(rows.length), Accessible.Politeness.POLITE);
            }
        }
        if (open) {
            setHighlight(rows.length == 0 ? -1 : 0, Change.Origin.USER);
            refitPopup();
        }
        invalidate();
    }

    /** Shows the rows an editable combo's text finds: every item for no text. */
    private void showRowsFor(String text) {
        int n = items.size();
        if (text.isEmpty()) {
            rows = everyRow();
            return;
        }
        int[] found = new int[n];
        int count = 0;
        if (filter != null) {
            for (int i = 0; i < n; i++) {
                if (filter.test(values.get(i), text)) {
                    found[count++] = i;
                }
            }
        } else {
            // Those that start with the text first, then those that hold it anywhere, each in the
            // items' own order: "sao" lists "São Paulo Alimentos" before "Padaria São João".
            String wanted = fold(text);
            boolean[] starts = new boolean[n];
            for (int i = 0; i < n; i++) {
                String label = fold(items.get(i).get());
                if (label.startsWith(wanted)) {
                    found[count++] = i;
                    starts[i] = true;
                }
            }
            for (int i = 0; i < n; i++) {
                if (!starts[i] && fold(items.get(i).get()).contains(wanted)) {
                    found[count++] = i;
                }
            }
        }
        rows = java.util.Arrays.copyOf(found, count);
    }

    /**
     * A text as the default filter compares it: accents taken off (canonical decomposition, the
     * combining marks dropped) and lower-cased in no one language's rules, so "São" and "sao",
     * "É" and "e" meet.
     */
    static String fold(String text) {
        String decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); ) {
            int cp = decomposed.codePointAt(i);
            int type = Character.getType(cp);
            if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK
                    && type != Character.ENCLOSING_MARK) {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /** @return the item whose text is {@code text}, compared as the filter compares, or -1 */
    private int itemNamed(String text) {
        String wanted = fold(text);
        for (int i = 0; i < items.size(); i++) {
            if (fold(items.get(i).get()).equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    /** Makes a free text the value: an editable combo with {@link #setFreeText} only. */
    private void selectFree(String text, @Nullable T value, Change.Origin origin) {
        if (selectedIndex < 0 && text.equals(freeLabel.get()) && java.util.Objects.equals(value, freeValue)) {
            return;
        }
        selectedIndex = -1;
        freeValue = value;
        freeLabel = I18nString.literal(text);
        invalidate();
        syncEditor();
        notifyChange(Change.of(Change.Aspect.SELECTION, origin));
    }

    /**
     * Tells a reader that a typed text was refused and what the field holds again, in the words the
     * spinner uses for a refused number, since the two refusals are the same event.
     */
    private void announceRefused(String typed) {
        Scene scene = scene();
        String quoted = typed.strip();
        if (scene == null || quoted.isEmpty()) {
            return;
        }
        if (quoted.codePointCount(0, quoted.length()) > REFUSAL_QUOTE_LIMIT) {
            quoted = quoted.substring(0, quoted.offsetByCodePoints(0, REFUSAL_QUOTE_LIMIT)) + "…";
        }
        scene.announce(ComponentStrings.SPINNER_REFUSED.format(quoted, selectedText()),
                Accessible.Politeness.POLITE);
    }

    /** Puts the selected item's text in the editor, when it shows anything else, and its icon. */
    private void syncEditor() {
        if (editor == null) {
            return;
        }
        if (selectionMode == SelectionMode.MULTI) {
            // Holding many, the field's text is only ever a filter, and the chips stand for the
            // selection whenever it is empty (ADR 052 §3).
            if (!editor.text().isEmpty()) {
                editor.setText("");
            }
            editor.setLeadingIcon(null);
            refreshChips();
            return;
        }
        if (!editor.text().equals(selectedText())) {
            editor.setText(selectedText());
            editor.setCaretPosition(editor.text().length());
        }
        editor.setLeadingIcon(selectedIcon());
    }

    /** The editor's text changed by the user's hand: filter, and show the list with its first row. */
    private void editorTyped() {
        if (selectionMode == SelectionMode.MULTI) {
            refreshChips(); // text hides the chips, and none shows them again
        }
        if (search != null) {
            searchFor(editor.text());
            return;
        }
        showRowsFor(editor.text());
        statusLine = rows.length == 0 ? ComponentStrings.COMBO_NO_RESULTS : null;
        if (!open) {
            open(Change.Origin.USER);
        }
        setHighlight(rows.length == 0 ? -1 : 0, Change.Origin.USER);
        refitPopup();
    }

    /** The list button: shows every item when the text is the selected one's, else what it finds. */
    private void toggleList() {
        if (open) {
            close(Change.Origin.USER);
            return;
        }
        showRowsFor(filterText());
        open(Change.Origin.USER);
    }

    /**
     * What the list is filtered by as it opens: nothing while the field shows the selected item's
     * own text, else what is typed. Holding many, the text is only ever a filter.
     */
    private String filterText() {
        if (editor == null) {
            return "";
        }
        if (selectionMode == SelectionMode.MULTI) {
            return editor.text();
        }
        return editor.text().equals(selectedText()) ? "" : editor.text();
    }

    /**
     * A key the editor received, before it handles it: the arrows walk the list, Enter picks, Escape
     * closes the list and then puts the selected item's text back. Everything else is the editor's.
     *
     * @return whether the combo took the key
     */
    private boolean editorKey(KeyEvent event) {
        switch (event.key()) {
            case Keys.DOWN -> {
                if (open) {
                    setHighlight(highlightedRow + 1);
                } else {
                    showRowsFor(filterText());
                    open(Change.Origin.USER);
                }
                return true;
            }
            case Keys.UP -> {
                if (open) {
                    setHighlight(highlightedRow - 1);
                }
                return open;
            }
            case Keys.PAGE_DOWN -> {
                if (open) {
                    setHighlight(highlightedRow + visibleRows());
                }
                return open;
            }
            case Keys.PAGE_UP -> {
                if (open) {
                    setHighlight(highlightedRow - visibleRows());
                }
                return open;
            }
            case Keys.ENTER -> {
                if (open && highlightedRow >= 0) {
                    commit(highlightedRow);
                } else if (selectionMode == SelectionMode.MULTI) {
                    // Nothing typed is ever committed while holding many: Enter shows the list.
                    if (!open) {
                        showRowsFor(filterText());
                        open(Change.Origin.USER);
                    }
                } else {
                    commitText();
                }
                return true;
            }
            case Keys.ESCAPE -> {
                if (open) {
                    close(Change.Origin.USER);
                    return true;
                }
                if (selectionMode == SelectionMode.MULTI ? !editor.text().isEmpty()
                        : !editor.text().equals(selectedText())) {
                    syncEditor();
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Commits what the editor holds, as leaving the field does: the item it names is picked, and any
     * other text is put back to the selected item's.
     */
    private void commitText() {
        if (editor == null) {
            return;
        }
        String typed = editor.text();
        if (open) {
            close(Change.Origin.USER);
        }
        if (selectionMode == SelectionMode.MULTI) {
            syncEditor(); // the text was a filter: it goes, and the chips come back
            rows = everyRow();
            return;
        }
        if (!typed.equals(selectedText())) {
            int named = itemNamed(typed);
            if (named >= 0) {
                select(named, Change.Origin.USER);
            } else if (emptyAllowed && (typed.isEmpty() || fold(typed).equals(fold(emptyLabel.get())))) {
                // An emptied field is the empty option, and so is its own text typed back.
                selectEmpty(Change.Origin.USER);
            } else if (freeText != null) {
                selectFree(typed, typed.isEmpty() ? null : freeText.apply(typed), Change.Origin.USER);
            } else {
                syncEditor(); // put back first, so the sentence says what the field holds now
                announceRefused(typed);
            }
        }
        syncEditor();
        rows = everyRow();
    }

    /** The list's rows changed while it is up: its window takes the new height, or the overlay lays out again. */
    private void refitPopup() {
        if (popupPanel != null) {
            popupPanel.markNeedsLayout();
            popupPanel.invalidate();
        }
        if (scenePopup != null) {
            scenePopup.markNeedsLayout();
            scenePopup.invalidate();
        }
        Scene scene = scene();
        if (popupWindow == null || scene == null || scene.window() == null) {
            return;
        }
        NativeWindow parent = scene.window();
        SizeTokens t = Theme.of(this).tokensFor(this);
        float gap = t.popupGap();
        float factor = parent.logicalToScreenFactor();
        int anchorTop = parent.screenY() + Math.round(localToSceneY() * factor);
        int anchorBottom = parent.screenY() + Math.round((localToSceneY() + height() + gap) * factor);
        limn.backend.ScreenRect area = parent.display() != null ? parent.display().workArea() : null;
        float contentH = contentHeight(t);
        float spaceBelow = area != null ? (area.bottom() - anchorBottom) / factor - EDGE_MARGIN : contentH;
        float spaceAbove = area != null ? (anchorTop - area.y()) / factor - gap - EDGE_MARGIN : contentH;
        Drop drop = dropFor(t, spaceAbove, spaceBelow);
        float ownerY = drop.above() ? localToSceneY() - gap - drop.height() : localToSceneY() + height() + gap;
        popupWindow.setSize(Math.max(1, Math.round(width())), Math.max(1, Math.round(drop.height())));
        popupWindow.setScreenPosition(parent.screenX() + Math.round(localToSceneX() * factor),
                parent.screenY() + Math.round(ownerY * factor));
        popupWindow.requestFrame();
    }

    /**
     * The text an editable combo's user types into: a text field in every way but the keys the
     * combo takes first and the button that shows the list. The combo's caption names it, so a
     * reader hears the field's name where the caret is.
     */
    static final class Editor extends TextField {
        private final ComboBox<?> combo;

        Editor(ComboBox<?> combo) {
            this.combo = combo;
            setTrailingButton(SvgIcon.fromResource("/limn/components/icons/chevron-down.svg"),
                    ComponentStrings.COMBO_SHOW_OPTIONS, combo::toggleList);
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (event.isPressed() && combo.editorKey(event)) {
                event.consume();
                return;
            }
            super.onKeyEvent(event);
        }

        @Override
        protected void handleUserChange(Change.Aspect aspect) {
            super.handleUserChange(aspect);
            if (aspect == Change.Aspect.TEXT) {
                combo.editorTyped();
            }
        }

        @Override
        protected void onFocusLost() {
            super.onFocusLost();
            combo.commitText();
        }

        /**
         * A press where the chips stand opens the list, as a press on a combo that is not editable
         * does: the chips are the field's content, and there is no text there to put a caret in.
         * The list button opens it by itself, which the state before and after tells apart.
         */
        @Override
        protected void onMouseEvent(MouseEvent event) {
            boolean wasOpen = combo.isOpen();
            boolean chips = combo.chipsShown();
            super.onMouseEvent(event);
            if (event.type() == MouseEvent.Type.PRESS && event.button() == Keys.MOUSE_LEFT
                    && chips && !wasOpen && !combo.isOpen()) {
                combo.toggleList();
            }
        }

        /** Holding many, the chips stand in the empty field's place: no hint, no caret under them. */
        @Override
        boolean paintsEmptyContent() {
            return !combo.chipsShown();
        }

        /**
         * A text field that filters the list it opens, which is what an editable combo's field is
         * and a plain field is not: {@code AUTOCOMPLETES}, and while the list is shown the link to
         * it, the shape a browser gives an ARIA combo box ({@code aria-autocomplete="list"} and
         * {@code aria-controls}). The list names this field back ({@link PopupPanel}). Only while
         * it is shown: a closed list is in no tree, and a relation to it would resolve up its
         * inheritance host to the combo, this field's own parent.
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            super.onAccessibility(a);
            a.state(Accessible.State.AUTOCOMPLETES);
            if (combo.selectionMode == SelectionMode.MULTI) {
                // Its value is the text typed, which is only a filter: what the combo holds is
                // told here, the count and every name (ADR 052 §4).
                a.description(combo.summary(), combo.summaryWitness());
            }
            if (combo.popupPanel != null) {
                a.relation(Accessible.Relation.CONTROLLER_FOR, combo.popupPanel);
            }
        }
    }

    private void closeUnlessRefocused() {
        if (!open) {
            return;
        }
        boolean ownerFocused = scene() != null && scene().isWindowFocused();
        boolean popupFocused = popupScene != null && popupScene.isWindowFocused();
        if (!ownerFocused && !popupFocused) {
            close(Change.Origin.ADJUSTMENT);
        }
    }

    /**
     * Which way the list drops and how tall it ends up, the one decision both presentations
     * make, from spaces each measures for itself against a different bound (the display's work
     * area for a window, the owner scene for an overlay).
     *
     * @param above  whether the list flips above the field
     * @param height its height in logical points, clamped to the space that direction has
     */
    private record Drop(boolean above, float height) {
    }

    /** The full list height at {@code t}, before any clamping. */
    private float contentHeight(SizeTokens t) {
        int lines = rows.length == 0 && statusLine != null ? 1 : rows.length;
        return lines * t.popupItemHeight() + 2 * t.popupPadV();
    }

    /**
     * Prefers dropping below the field, flipping above only when the list does not fit below
     * <em>and</em> above has more room, so a list that fits nowhere still opens downward, where
     * a dropdown is looked for. Never shorter than two rows: a list clamped to a sliver is
     * unusable, and one clamped to nothing looks like a click that did nothing.
     */
    private Drop dropFor(SizeTokens t, float spaceAbove, float spaceBelow) {
        float contentH = contentHeight(t);
        float minH = Math.min(contentH, 2 * t.popupItemHeight() + 2 * t.popupPadV());
        boolean above = contentH > spaceBelow && spaceAbove > spaceBelow;
        return new Drop(above,
                Math.max(minH, Math.min(contentH, above ? spaceAbove : spaceBelow)));
    }

    /**
     * Draws the list inside the owner scene, for a window that cannot place one of its own.
     *
     * <p>Not deferred, unlike {@link #createPopupWindow()}: there is no window to create and so
     * no GL context to switch, and posting would leave one frame with the field showing itself
     * open and nothing dropped down.
     */
    private void presentInScene() {
        Scene owner = scene();
        popupStep = controlSize();
        popupPanel = new PopupPanel();
        // Before the overlay is pushed, for the reason the native path resolves it before
        // binding: a panel that resolved the process defaults would lay its rows out at one step,
        // or in one direction, inside a box measured at another.
        popupPanel.setInheritanceHost(this);
        scenePopup = new ScenePopup(popupPanel);
        // The link goes on the OVERLAY as well, and it is the overlay's that does the work: the
        // panel's own is ignored here, because a host link on a widget that has a parent loses to
        // the tree, and in this presentation the panel's parent is the overlay. Without this the
        // whole in-scene subtree resolves the owner scene's default and then the process default,
        // so a field at a declared step opens a list whose box is measured at the field's step
        // (this class reads the field's tokens for that) and whose rows are painted and hit at
        // another, and a list opened by a field in its own language resolves its rows in a
        // different one. Before the push, for the reason the panel's own goes on before it:
        // pushOverlay measures, and a link installed afterwards sizes at the wrong step first.
        //
        // It reaches as far as the link is defined to reach and no further: a host is consulted
        // after the widget's own scene default, so an owner scene that declares a step or a
        // language still wins over the field here, where a popup in a window of its own falls
        // through to the field because its scene declares nothing. That is the axis rule rather
        // than this component's, and the case this fixes is the one the class promises.
        scenePopup.setInheritanceHost(this);
        // Assigned before pushOverlay, which moves focus off the field: onFocusLost reads this
        // field to tell "the user clicked elsewhere" from "our own overlay took the keyboard",
        // and the second one must not close the popup it is opening.
        boolean animate = owner.window() != null && !limn.animation.Motion.reduced();
        sceneFade = animate ? 0f : 1f;
        if (editable) {
            // The editor keeps the keyboard, the caret and the input method: the list is drawn
            // over the page and driven from the field, as its window of its own is.
            SceneAccess.pushPopupLeavingKeyboard(owner, scenePopup);
        } else {
            owner.pushPopup(scenePopup);
        }
        if (animate) {
            ScenePopup fading = scenePopup;
            owner.addRealTimeTicker(dt -> {
                if (!open) {
                    return false; // closed mid-fade: the fade-out ticker takes over
                }
                sceneFade = (float) Math.min(1, sceneFade + limn.animation.Motion.progress(dt, Theme.of(this).animWindow));
                fading.invalidate();
                return sceneFade < 1;
            });
        }
    }

    private void createPopupWindow() {
        // Re-check: the combo may have closed (or detached) before this post
        // ran, or the parent window may already be gone.
        Scene scene = scene();
        if (!open || popupWindow != null || scene == null
                || scene.window() == null || scene.window().isClosed()) {
            return;
        }
        NativeWindow parent = scene.window();
        if (!FloatingSurfaces.anchoredWindowAvailable(parent)) {
            // The owner went fullscreen between the open and this turn, and the change has not
            // been dispatched yet: the window it would have closed is never made.
            close(Change.Origin.ADJUSTMENT);
            return;
        }
        SizeTokens t = Theme.of(this).tokensFor(this);
        float gap = t.popupGap();
        float factor = parent.logicalToScreenFactor();
        int anchorTop = parent.screenY() + Math.round(localToSceneY() * factor);
        int anchorBottom = parent.screenY() + Math.round((localToSceneY() + height() + gap) * factor);

        // Clamp the list to the display work area: prefer dropping below the field;
        // flip above when the list doesn't fit below and there is more room above.
        // A clamped list scrolls (wheel + keyboard highlight auto-reveals).
        limn.backend.ScreenRect area = parent.display() != null ? parent.display().workArea() : null;
        float contentH = contentHeight(t);
        float spaceBelow = area != null
                ? (area.bottom() - anchorBottom) / factor - EDGE_MARGIN : contentH;
        float spaceAbove = area != null
                ? (anchorTop - area.y()) / factor - gap - EDGE_MARGIN : contentH;
        Drop drop = dropFor(t, spaceAbove, spaceBelow);

        int popupWidth = Math.max(1, Math.round(width()));
        int popupHeight = Math.max(1, Math.round(drop.height()));
        // Placed against the field in the owner's own points, and owned from creation: the parent
        // closing closes the list, and a platform that places a child against its parent can.
        float ownerY = drop.above()
                ? localToSceneY() - gap - drop.height()
                : localToSceneY() + height() + gap;
        popupWindow = parent.backend().createWindow(WindowConfig.popup(parent, popupWidth, popupHeight)
                .atInOwner(localToSceneX(), ownerY));
        popupStep = controlSize();
        popupRtl = isRightToLeft();

        popupPanel = new PopupPanel();
        // Before the scene binds: binding measures the panel, and a panel that resolved the
        // process defaults there would lay rows out at one step, or in one direction, inside a
        // window sized at another.
        popupPanel.setInheritanceHost(this);
        // On the owner's clock, so the fade-out that destroys this window advances with it
        // (Scene#clock).
        popupScene = new Scene(popupPanel, scene().clock());
        popupScene.inheritRenderingFlags(scene()); // partial/debug follow the owner window
        // Published by this window's tree, under the combo, as a native drop-down list is a child
        // of its field: a reader never leaves this window while the list is open.
        scene().graftPopup(popupScene);
        if (editable) {
            // The editor holds the caret and the keyboard; the list's highlight is still what a
            // reader follows, as ARIA's combobox points at it with aria-activedescendant.
            cursorHandle = SceneAccess.showCursorOf(scene(), popupScene);
        }
        popupScene.bind(popupWindow);
        // Clicking the list may hand OS focus to the popup window itself; when
        // THAT later blurs, the same dismiss rule applies.
        popupBlurHandle = popupScene.observeWindowBlur(() -> Ui.post(this::closeUnlessRefocused));
        popupScene.setBackground(Color.TRANSPARENT); // the translucent-window core

        // Screenshot mode keeps everything hidden; interactive mode shows the
        // popup without stealing focus (WindowConfig.popup → focusOnShow=false).
        if (parent.isVisible()) {
            popupScene.fadeWindowIn(limn.animation.Motion.duration(Theme.of(this).animWindow)); // transparent → visible
            popupWindow.show();
        }
        popupWindow.requestFrame();
    }

    /**
     * Whether an option of the open list can be chosen or walked onto right now: the list is open
     * and not fading out, and the combo is enabled. The one condition the panel's describe hook
     * publishes {@code SELECT}, {@code PRESS} and {@code FOCUS} on and its action hook performs
     * them on.
     */
    private boolean optionsOperable() {
        return open && isEnabled();
    }

    private void commit(int row) {
        if (!open) {
            return; // the popup is already closing (e.g. a click during its fade-out)
        }
        if (!isEnabled()) {
            // The one choke point every commit goes through: the row a click landed in, the
            // keyboard's highlight, and an assistive technology's SELECT. A combo disabled while
            // its list is open kept committing on a click, because the list is a parentless
            // overlay and the scene's ancestor gate never reaches the field -- so the guard on
            // the synthetic hook was refusing the one caller that was doing it politely while
            // the pointer went through. Refusing here is what makes the two agree.
            return;
        }
        // Clamped rather than range-checked: both callers are the popup's own bookkeeping (the
        // row a click landed in, and the keyboard highlight), and neither is an application
        // naming an index, which is the only thing setSelectedIndex refuses.
        if (rows.length == 0) {
            return; // a filter that left nothing: there is no option to pick
        }
        int item = rows[Math.max(0, Math.min(row, rows.length - 1))];
        if (item == EMPTY_ROW) {
            close(Change.Origin.USER);
            selectEmpty(Change.Origin.USER);
            syncEditor();
            return;
        }
        if (selectionMode == SelectionMode.MULTI) {
            // Toggled, and the list stays down (ADR 052 §3). A filter typed to find the item goes,
            // so every item shows again with the highlight still on the one just toggled.
            toggle(item, Change.Origin.USER);
            if (editor != null && !editor.text().isEmpty()) {
                syncEditor();
                showRowsFor("");
                refitPopup();
            }
            int now = rowOfItem(item);
            setHighlight(now < 0 ? 0 : now, Change.Origin.ADJUSTMENT);
            return;
        }
        if (search != null) {
            // The answer the item came in is replaced by the next search: the value is held by
            // itself, as a free text is.
            T picked = values.get(item);
            String label = items.get(item).get();
            close(Change.Origin.USER);
            selectFree(label, picked, Change.Origin.USER);
            syncEditor();
            return;
        }
        // The list closing is the pick's own settling, announced before the pick; re-picking
        // the item already selected closes the popup and announces nothing else, because the
        // selection did not move.
        close(Change.Origin.USER);
        select(item, Change.Origin.USER);
        // Re-picking the item already selected moves nothing, and still puts its text back over
        // whatever was typed to find it.
        syncEditor();
    }

    // ----------------------------------------------------------- field visual


    /**
     * What a label with no strong character of its own falls back to, given the direction the
     * caller already resolved for its pass: an item that is all digits or punctuation
     * ({@code "--"}, {@code "1/2"}, {@code "1.0"}) reads the way the form around it does.
     *
     * <p>The resolved direction is a parameter rather than a second read, so that one pass
     * cannot shape a label against one direction and place it against the other. Every strong
     * character still decides for itself, which is why a Latin item in a right-to-left combo
     * still reads left to right.
     */
    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.of(this).tokensFor(this);
        if (open && popupWindow != null && popupStep != controlSize()) {
            // The window was sized at popupStep and cannot be resized in place without
            // reflowing rows the user is aiming at; deferred so a measure pass never mutates
            // the tree it is running over.
            Ui.post(this::close);
        }
        boolean rtl = isRightToLeft();
        if (open && popupWindow != null && popupRtl != rtl) {
            // Not deferred, and not a close: this touches the popup window's own tree and not
            // the one being measured, and the window keeps its size (the field measures the
            // same in both directions). Without it the list stays as it was drawn, because
            // nothing in this scene damages a panel that lives in another one.
            popupRtl = rtl;
            popupPanel.markNeedsLayout();
            popupWindow.requestFrame();
        }
        TextMetrics metrics = textRuler().measure(widestItem(t.body()), t.body());
        return constraints.constrain(
                metrics.width() + 2 * t.fieldPadH() + t.comboCaretGutter() + iconRoom(t),
                t.resolvedHeight(metrics.lineHeight()));
    }

    private String widestItem(Font font) {
        // By MEASURED width, not character count: proportional fonts make
        // "WWWW" wider than "iiiiii" despite fewer characters.
        if (items.isEmpty()) {
            return selectedText().isEmpty() ? "MMMMMMMMMMMM" : selectedText(); // a search combo's width
        }
        String widest = items.get(0).get();
        float widestWidth = textRuler().measure(widest, font).width();
        float hint = textRuler().measure(placeholder.get(), font).width();
        if (hint > widestWidth) {
            widest = placeholder.get();
            widestWidth = hint;
        }
        if (showsEmptyRow()) {
            float empty = textRuler().measure(emptyLabel.get(), font).width();
            if (empty > widestWidth) {
                widest = emptyLabel.get();
                widestWidth = empty;
            }
        }
        for (I18nString each : items) {
            String item = each.get();
            float itemWidth = textRuler().measure(item, font).width();
            if (itemWidth > widestWidth) {
                widest = item;
                widestWidth = itemWidth;
            }
        }
        return widest;
    }

    /** The room an icon takes before a text, with its gap: none when no item has an icon. */
    private float iconRoom(SizeTokens t) {
        return icons.isEmpty() ? 0 : t.fieldIcon() + t.spacingSmall();
    }

    /** Mixed-step rows align on this, not on the box; see {@code Flex.CrossAlignment.BASELINE}. */
    @Override
    protected float baselineOffset() {
        SizeTokens t = Theme.of(this).tokensFor(this);
        TextMetrics metrics = textRuler().measure(selectedText(), t.body());
        return (height() - metrics.height()) / 2 + metrics.ascent();
    }

    @Override
    protected void onPaint(Canvas canvas) {
        if (editable) {
            return; // the editor draws the field, its frame, its text and the list button
        }
        Theme theme = Theme.of(this);
        SizeTokens t = theme.tokensFor(this);
        Font font = t.body();
        // One resolution for the whole pass: the label, the clip that keeps it off the chevron
        // and the chevron's own gutter have to agree about which side reading starts on.
        boolean rtl = isRightToLeft();
        Color fill = !isEnabled() ? theme.disabledFill()
                : theme.surface().lerp(theme.surfaceRaised(), open ? 1f : hover.value());
        float focus = focusFade.value();
        // One border whose weight animates BORDER -> FOCUS_RING, inward; a ternary here would
        // delete the focus fade outright.
        canvas.fillRoundRectWithBorder(0, 0, width(), height(), t.radiusMedium(), fill,
                Strokes.BORDER + (Strokes.FOCUS_RING - Strokes.BORDER) * focus,
                theme.outline().lerp(theme.focusRing(), focus));

        if (selectionMode == SelectionMode.MULTI) {
            if (showsPlaceholder()) {
                paintHint(canvas, theme, t, rtl, 0);
            }
            paintChevron(canvas, theme, t, rtl);
            return; // the chips stand for the selection, drawn by the strip above this
        }
        String label = selectedText();
        // The vertical band stays the measured one, because that is what baselineOffset()
        // reports and a row aligned on a baseline the paint does not use is a row out of line.
        // Only the horizontal placement needs the shaped run.
        TextMetrics metrics = textRuler().measure(label, font);
        Color ink = isEnabled() ? theme.text() : theme.disabledText();
        canvas.save();
        // The band that keeps the label off the chevron: its variable edge is the one reading
        // ends on, so the reserved strip is on the left of a right-to-left field.
        canvas.clipRect(rtl ? t.comboTextClip() : 0, 0, width() - t.comboTextClip(), height());
        // Shaped here rather than left to the canvas, because the canvas has no widget to ask
        // and falls back to left-to-right for a string with no strong character of its own.
        // Costs nothing extra: the string overload shapes through the same ruler memo.
        ShapedText line = textRuler().shape(label, font,
                ShapedText.Direction.of(label, neutralBase()));
        // drawText places the LEFT edge of the run's box for either base direction — a
        // right-to-left run fills the same box from the other end rather than growing leftwards
        // — so aligning to the edge reading starts from is choosing x, and choosing it takes
        // the run's own width.
        Icon icon = selectedIcon();
        float lead = icon != null ? iconRoom(t) : 0;
        if (showsPlaceholder()) {
            // The hint fills a field that would read nothing, after the empty option's icon when
            // it has one.
            canvas.restore();
            if (icon != null) {
                float ico = t.fieldIcon();
                icon.paint(canvas, rtl ? width() - t.fieldPadH() - ico : t.fieldPadH(),
                        (height() - ico) / 2, ico, ink, theme.isDark(), false);
            }
            paintHint(canvas, theme, t, rtl, lead);
            paintChevron(canvas, theme, t, rtl);
            return;
        }
        if (icon != null) {
            float ico = t.fieldIcon();
            icon.paint(canvas, rtl ? width() - t.fieldPadH() - ico : t.fieldPadH(),
                    (height() - ico) / 2, ico, ink, theme.isDark(), false);
        }
        float textX = rtl ? width() - t.fieldPadH() - lead - line.metrics().width()
                : t.fieldPadH() + lead;
        canvas.drawText(line, textX,
                (height() - metrics.height()) / 2 + metrics.ascent(), ink);
        canvas.restore();
        paintChevron(canvas, theme, t, rtl);
    }

    /** The hint, muted, where the label would start, {@code lead} past the leading edge. */
    private void paintHint(Canvas canvas, Theme theme, SizeTokens t, boolean rtl, float lead) {
        Font font = t.body();
        String hint = placeholder.get();
        TextMetrics metrics = textRuler().measure(hint, font);
        canvas.save();
        canvas.clipRect(rtl ? t.comboTextClip() : 0, 0, width() - t.comboTextClip(), height());
        ShapedText line = textRuler().shape(hint, font, ShapedText.Direction.of(hint, neutralBase()));
        float x = rtl ? width() - t.fieldPadH() - lead - line.metrics().width() : t.fieldPadH() + lead;
        canvas.drawText(line, x, (height() - metrics.height()) / 2 + metrics.ascent(),
                isEnabled() ? theme.textMuted() : theme.disabledText());
        canvas.restore();
    }

    private void paintChevron(Canvas canvas, Theme theme, SizeTokens t, boolean rtl) {
        // Caret triangle (flips while open). Half-height is half the half-width at every
        // step, so the arrow angle is invariant: the same arrow, smaller. Only the gutter it
        // sits in changes sides; the triangle is symmetric about cx and points up and down,
        // so nothing about the path below is directional.
        float cx = rtl ? t.comboCaretCenterX() : width() - t.comboCaretCenterX();
        float cy = height() / 2;
        float halfW = t.chevronHalfW();
        float halfH = halfW / 2;
        caret.reset();
        if (open) {
            caret.moveTo(cx - halfW, cy + halfH).lineTo(cx, cy - halfH).lineTo(cx + halfW, cy + halfH);
        } else {
            caret.moveTo(cx - halfW, cy - halfH).lineTo(cx, cy + halfH).lineTo(cx + halfW, cy - halfH);
        }
        canvas.drawPath(caret, Strokes.ARROW_PEN,
                isEnabled() ? theme.textMuted() : theme.disabledText());
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        if (editable) {
            return; // the editor's, and its list button's
        }
        switch (event.type()) {
            case ENTER -> hover.to(1);
            case EXIT -> hover.to(0);
            case PRESS -> {
                if (event.button() == Keys.MOUSE_LEFT) {
                    if (open) {
                        close(Change.Origin.USER);
                    } else {
                        open(Change.Origin.USER);
                    }
                    event.consume();
                }
            }
            default -> {
            }
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed() || editable) {
            return; // an editable combo's keys reach it through its editor
        }
        boolean handled = true;
        if (!open) {
            switch (event.key()) {
                case Keys.SPACE, Keys.ENTER, Keys.DOWN -> open(Change.Origin.USER);
                default -> handled = false;
            }
        } else {
            switch (event.key()) {
                case Keys.DOWN -> setHighlight(highlightedRow + 1);
                case Keys.UP -> setHighlight(highlightedRow - 1);
                case Keys.HOME -> setHighlight(0);
                case Keys.END -> setHighlight(rows.length - 1);
                case Keys.PAGE_DOWN -> setHighlight(highlightedRow + visibleRows());
                case Keys.PAGE_UP -> setHighlight(highlightedRow - visibleRows());
                case Keys.ENTER, Keys.SPACE -> commit(highlightedRow);
                case Keys.ESCAPE -> close(Change.Origin.USER);
                default -> {
                    if (selectionMode == SelectionMode.MULTI && event.shortcutKey() == Keys.A
                            && (event.modifiers() & Accelerator.commandModifier()) != 0) {
                        selectAll(Change.Origin.USER);
                    } else {
                        handled = false;
                    }
                }
            }
        }
        if (handled) {
            event.consume();
        }
    }

    /**
     * How many rows a Page key steps: what the popup is actually showing, since the list is
     * clamped to the display work area and a long one scrolls. At least one, so a popup clamped to
     * a sliver still moves rather than becoming a dead key.
     */
    private int visibleRows() {
        if (popupPanel == null) {
            return 1;
        }
        SizeTokens t = Theme.of(this).tokensFor(popupPanel);
        float itemHeight = t.popupItemHeight();
        if (itemHeight <= 0) {
            return 1;
        }
        float body = popupPanel.height() - 2 * t.popupPadV();
        return Math.max(1, (int) (body / itemHeight));
    }

    /**
     * Type-ahead: letters typed into an open list jump to what they spell, the way a combo does on
     * Windows and a pop-up button does on GTK and macOS. Without it the only way through a long
     * clamped list is one arrow press per item.
     *
     * <p>Two behaviours a user expects and neither of which falls out of plain prefix matching.
     * The buffer expires after {@link #TYPE_AHEAD_RESET_NANOS}, so "so" typed slowly is two
     * separate jumps rather than a search for "so": a pause means a new word. And repeating one
     * letter cycles through the items starting with it instead of re-finding the first, which is
     * how a list of six things beginning with S is navigated at all.
     *
     * <p>The search wraps and starts <em>after</em> the current highlight, so a match already
     * under the cursor is not what a fresh keystroke finds.
     */
    @Override
    protected void onCharTyped(limn.scene.event.CharEvent event) {
        int codepoint = event.codepoint();
        if (editable || !open || codepoint < 0x20 || codepoint == 0x7F || rows.length == 0) {
            return;
        }
        // The scene's clock rather than the wall's. They are one clock in an application; in a
        // test or a filmed capture the scene's is the one every other interval on screen is
        // measured on, where the wall made the pause however long the machine took between keys.
        long now = sceneNanos();
        if (now - lastTypedNanos > TYPE_AHEAD_RESET_NANOS) {
            typeAhead.setLength(0);
        }
        lastTypedNanos = now;
        typeAhead.appendCodePoint(codepoint);

        String prefix = typeAhead.toString();
        boolean cycling = prefix.length() > 1 && prefix.chars().distinct().count() == 1;
        String wanted = cycling ? prefix.substring(0, 1) : prefix;
        // From the row after the highlight when the buffer is one letter or is cycling; from the
        // highlight itself while a longer prefix is being extended, or typing "ba" after "b"
        // matched would skip the very item "b" had just found.
        int from = (cycling || prefix.length() == 1) ? highlightedRow + 1 : highlightedRow;
        for (int step = 0; step < rows.length; step++) {
            int candidate = Math.floorMod(from + step, rows.length);
            if (rows[candidate] != EMPTY_ROW
                    && startsWithIgnoreCase(items.get(rows[candidate]).get(), wanted)) {
                setHighlight(candidate);
                event.consume();
                return;
            }
        }
        // Nothing spells this: drop the buffer rather than leaving a prefix that can never match
        // poisoning the next keystroke.
        typeAhead.setLength(0);
    }

    /**
     * Case-insensitive in the text locale, not per {@code char}: both sides are upper-cased
     * whole, with a lower-cased retry for the scripts where only one direction folds. A
     * per-char comparison can never match "stras" to "Straße" — one ß upper-cases to two
     * letters — and matches a Turkish dotted İ only by accident of the accentless tables.
     */
    private static boolean startsWithIgnoreCase(String label, String prefix) {
        return I18n.toUpperCase(label).startsWith(I18n.toUpperCase(prefix))
                || I18n.toLowerCase(label).startsWith(I18n.toLowerCase(prefix));
    }

    /** The keyboard highlight, which is the active descendant a reader follows: every caller is a key. */
    private void setHighlight(int row) {
        setHighlight(row, Change.Origin.USER);
    }

    private void setHighlight(int row, Change.Origin origin) {
        int old = highlightedRow;
        highlightedRow = rows.length == 0 ? -1 : Math.max(0, Math.min(row, rows.length - 1));
        if (highlightedRow != old) {
            notifyChange(Change.of(Change.Aspect.ACTIVE, origin));
        }
        if (popupPanel != null) {
            // Resolved once for the whole event, on the panel: damage and reveal must agree
            // with each other and with the paint loop, or the highlight scrolls to one row
            // and repaints another.
            SizeTokens t = Theme.of(this).tokensFor(popupPanel);
            popupPanel.damageRow(old, t); // partial rendering: repaint the two affected rows
            popupPanel.damageRow(highlightedRow, t);
            popupPanel.revealRow(highlightedRow, t); // keyboard highlight stays in view
        }
        if (popupWindow != null) {
            popupWindow.requestFrame();
        }
    }

    @Override
    protected void onFocusGained() {
        focusFade.to(1);
    }

    /** An editable combo's caption names its editor, where the caret and the reader are. */
    @Override
    protected Widget<?> accessibleLabelTarget() {
        return editor != null ? editor : this;
    }

    @Override
    protected void onLayout() {
        if (editor != null) {
            editor.layoutBox(0, 0, width(), height());
        }
        if (chipStrip != null) {
            // The band the text would take: from the field's leading inset to the list button
            // (an editable combo's trailing region, else the chevron's clip), both sides by
            // direction.
            SizeTokens t = Theme.of(this).tokensFor(this);
            float lead = t.segInset() * 2;
            float trail = editor != null ? t.fieldTrailing() : t.comboTextClip();
            float w = Math.max(0, width() - lead - trail);
            chipStrip.measure(Constraints.tight(w, height()));
            chipStrip.layoutBox(isRightToLeft() ? trail : lead, 0, w, height());
        }
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(0);
        if (scenePopup == null) {
            close(Change.Origin.USER); // clicking anywhere else (a popup window never takes focus) dismisses
        }
        // An in-scene popup takes the focus itself (pushOverlay confines it to the overlay), so
        // here focus loss means the popup opening, not the user leaving. Closing on it would
        // dismiss the list in the same pass that opened it.
        invalidate();
    }

    /**
     * A combo removed from the tree must never strand its dropdown on screen:
     * a programmatically-opened popup has no focus to lose, so focus-lost alone
     * does not cover this.
     */
    @Override
    protected void onDetached() {
        close(Change.Origin.ADJUSTMENT);
    }

    // -------------------------------------------------------- accessibility

    /**
     * The field, which is a combo box that is open or shut and is showing one of its items. The
     * options are not here: they are drawn by the panel and described by it, in whichever scene
     * that panel is mounted in, which is what keeps their rectangles right in both presentations.
     *
     * <p><b>The selected item is the value and never the name.</b> A combo has no caption of its
     * own — its items are values and not a label — so the name is left for an application's
     * {@code setAccessibleName} or for the tooltip default to supply, and a combo with neither is
     * published unnamed. Naming it from the item it shows would be worse than that: the name would
     * change on every pick, and on the one platform that maps a name and a value to different
     * attributes a reader would hear the same word twice for every combo in the interface.
     *
     * <p><b>The number is what carries the text.</b> The publish step builds a value facet only
     * for a node that declared a number, so the selected item's text alone would be dropped in
     * silence; and the difference raises its value event off the number and never off the text, so
     * a constant standing in for one would make every pick silent on the channel a reader listens
     * to. The index is the honest number — nothing else about the selection is ordered — and
     * declaring it is also what advertises {@code SET_VALUE}, which is how an assistive technology
     * picks an item without opening the list at all.
     *
     * <p><b>Nothing here is formatted.</b> The item's text comes from the {@link I18nString} the
     * combo already holds, through that string's own per-language memo, so a hover fade or a focus
     * fade — each of which damages this widget on every frame it lasts — walks this hook without
     * allocating anything to conclude that nothing moved.
     *
     * @param a the builder for this node
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.COMBO_BOX);
        // The item it is showing, because a focusable node with no name at all is the one thing
        // §12.1's gallery test refuses, and a combo has no caption of its own to offer instead.
        // The item is the value as well, so a reader does hear the word twice where a platform
        // maps name and value to different attributes -- that is the accepted cost of a default
        // that needs no application to remember anything. An application that wants better says
        // so, and both ways of saying it win over this: setAccessibleName replaces it outright,
        // and a Label bound to this field names it through LABELLED_BY. The source form, never
        // the cached one: the item is an I18nString this widget holds, compared by reference, so
        // a hover or focus fade walks this hook without allocating.
        if (editor == null) {
            if (!placeholder.get().isEmpty()) {
                // The application's hint names the combo, as a text field's placeholder names it:
                // a name that does not change at every pick, and not the value twice.
                a.name(placeholder, Accessible.NameFrom.PLACEHOLDER);
            } else if (selectionMode == SelectionMode.MULTI) {
                a.name(summary(), summaryWitness(), Accessible.NameFrom.CONTENT);
            } else {
                a.name(selectedLabel(), Accessible.NameFrom.CONTENT);
            }
        }
        // An editable combo is a container here and names nothing of its own: its caption names
        // the text field inside it, where the keyboard is, and the item it holds is that field's
        // text. Named by its item as well, it was read as "Andes, combo box, Mountain ranges,
        // edit" before a word of the field.
        // The POPUP_OWNER shape, written once (ADR 045 §3): HAS_POPUP always, and not only
        // while the list is down, because a combo that could not be opened would not be a combo;
        // the open state as the facet, never state(EXPANDED, ...), which the builder ignores by
        // design so the bit and the facet cannot disagree; and one verb and not both, because
        // the other one is what the widget is already doing. PRESS is neither offered nor
        // accepted: what a press on a combo means is exactly the ambiguity these two verbs
        // remove, and a platform whose only activation verb is a press has the expand facet to
        // route it through, which is one decision in one bridge rather than a third meaning
        // here. While the list is down in the scene the walk takes the COLLAPSE off again, with
        // every verb beneath the layer that owns input (ADR 039 §1.13, amended 2026-09-15); in a
        // window of its own the field keeps the input and the verb stands.
        PopupOwnerAccessibility.describe(a, open);
        if (selectionMode == SelectionMode.MULTI) {
            // Holding many, the number is the count, read-only: a selection of many is not one
            // value a client can set (ADR 052 §4). The text is the count and every name, the
            // ones a "+N" hides from sight included.
            a.value(chosen.cardinality(), 0, items.size(), 1, true);
            a.valueText(summary(), summaryWitness());
            return;
        }
        if (selectedIndex >= 0) {
            // A free text has no place among the items to number: its text is the editor's own,
            // which a reader reads there.
            a.value(selectedIndex, 0, items.size() - 1, 1);
        }
        // The witness is the pair the item's text is a function of: which item is selected, and
        // the epoch every translation moves on. There is no cache to guard here -- the string is
        // the model's own, resolved through its memo -- and the witness says so rather than
        // pretending a counter exists.
        a.valueText(selectedText(), I18n.epoch() ^ ((long) selectedIndex << 32));
        // No CONTROLLER_FOR. In the scene presentation the overlay is the popup's parentless root
        // and carries the field as its inheritance host, so the walk publishes POPUP_FOR there and
        // this mirror here; declaring it again would put two of the same relation on this node. In
        // a window of its own the list is grafted into this tree under this node
        // (Scene#graftPopup), and the walk links the two the same way.
    }

    /**
     * Opens the list, closes it, or picks an item, each through the path the user's own gesture
     * takes.
     *
     * <p>No enabled check of its own, unlike the panel's: the node acted on here is this widget, so
     * the scene's own gate has already walked this field and every ancestor for
     * {@code isEnabled()}, checked that it is showing, that the window is not modal-blocked and
     * that it is inside the layer that owns input. That last test is why a
     * {@link Accessible.Action#COLLAPSE} sent to an open in-scene list does nothing, and why the
     * walk does not publish one there: the list's own overlay is that layer, the field is behind
     * it, and the dismissal that lands there is the overlay's {@code CANCEL} — the same asymmetry
     * the keyboard has, where Esc is delivered to the overlay and handed back here.
     *
     * @param action what is being asked
     * @param arg    the item to select for {@code SET_VALUE}, by number or by text
     * @return whether this widget did it
     */
    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        if (action == Accessible.Action.SET_VALUE) {
            return selectFromArgument(arg);
        }
        return PopupOwnerAccessibility.perform(popupHost, action);
    }

    /**
     * The field's mechanisms as the popup-owner shape drives them: EXPAND on a
     * closed list opens it and COLLAPSE on an open one closes it, each through the path the
     * user's own gesture takes; the other state refuses, because nothing was done and saying
     * otherwise is a lie.
     */
    private final class PopupHost implements PopupOwnerAccessibility.Host {
        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void open() {
            ComboBox.this.open(Change.Origin.USER);
        }

        @Override
        public void close() {
            ComboBox.this.close(Change.Origin.USER);
        }
    }

    private final PopupHost popupHost = new PopupHost();

    /**
     * Selects the item an argument names, by index or by its text.
     *
     * <p>Both funnels are the widget's own and both are the user's: an open list commits, which
     * is the path a click on a row takes and which closes the list and reports a change only when
     * there was one; a shut one enters the selection seam at {@code USER} directly, and not
     * through the public setter, which is a caller's write and would reach no handler. So a pick
     * from an assistive technology reaches the application exactly as a pick from the pointer
     * does.
     */
    private boolean selectFromArgument(Accessible.Argument arg) {
        if (selectionMode == SelectionMode.MULTI) {
            return false; // a selection of many is not one value to set (ADR 052 section 4)
        }
        int index;
        if (arg instanceof Accessible.Argument.OfValue value) {
            double raw = value.value();
            if (!Double.isFinite(raw)) {
                return false; // rounding a NaN would land on item zero
            }
            long rounded = Math.round(raw);
            index = rounded < 0 || rounded >= items.size() ? -1 : (int) rounded;
        } else if (arg instanceof Accessible.Argument.OfText text) {
            index = indexOfItem(text.text());
        } else {
            return false;
        }
        if (index < 0 || index >= items.size()) {
            return false; // an index nothing answers to is refused, never clamped to a neighbour
        }
        if (open) {
            commit(index);
        } else {
            select(index, Change.Origin.USER);
        }
        return true;
    }

    /**
     * The item that reads exactly as {@code wanted}, or {@code -1}.
     *
     * <p>Exact equality, and deliberately not the type-ahead's rule: that one matches a prefix
     * case-insensitively, which is what a user typing into an open list wants and is the wrong
     * answer for a client that was handed a value and is asking for it back. "Tw" would set the
     * selection to "Two" there, and a client would have no way to tell that from a combo that
     * genuinely holds "Tw".
     */
    private int indexOfItem(String wanted) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).get().equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------- chips (ADR 052)

    /**
     * The selection of many, standing in the field: a chip per selected item in the list's order,
     * as many as fit whole on the field's one line, then a "+N" badge for the rest. When even the
     * first chip does not fit beside the badge it is given what is left, and the default chip
     * shortens its text with an ellipsis.
     *
     * <p>Drawn and never operated: it answers no hit test, so a press goes to the field beneath it
     * as though the chips were paint, and it is out of the accessible tree with everything in it,
     * since the field's value already names every item.
     */
    private final class ChipStrip extends Widget<ChipStrip> {

        /** The chip made for each item, kept while the renderer stands, so it is asked once. */
        private final java.util.Map<Integer, Widget<?>> made = new java.util.HashMap<>();
        private final Badge badge = new Badge();
        /** The items shown, in order, as the last rebuild found them. */
        private int[] shown = new int[0];

        ChipStrip() {
            setAccessibleIgnored(true);
        }

        /** Drops every chip made, so the next rebuild asks the renderer again. */
        void forget() {
            made.clear();
        }

        /** Puts one chip per selected item under this strip, in the list's order, and the badge. */
        void rebuild() {
            int[] now = chosen.stream().filter(i -> i < values.size()).toArray();
            boolean same = java.util.Arrays.equals(now, shown) && children().size() == now.length + 1;
            if (same) {
                return;
            }
            shown = now;
            while (!children().isEmpty()) {
                remove(children().get(0));
            }
            for (int item : now) {
                add(made.computeIfAbsent(item, this::chipFor));
            }
            add(badge);
            markNeedsLayout();
            invalidate();
        }

        private Widget<?> chipFor(int item) {
            Function<? super T, ? extends Widget<?>> renderer = chipRenderer;
            if (renderer == null) {
                return new Chip(item);
            }
            Widget<?> chip = Objects.requireNonNull(renderer.apply(values.get(item)),
                    "the chip renderer answered null");
            refuseFocusable(chip);
            return chip;
        }

        private void refuseFocusable(Widget<?> widget) {
            if (widget.isFocusable()) {
                throw new IllegalArgumentException("a chip is drawn and never operated, and "
                        + widget.getClass().getSimpleName() + " can take the keyboard");
            }
            for (Widget<?> child : widget.children()) {
                refuseFocusable(child);
            }
        }

        @Override
        public @Nullable Widget<?> hitTest(float localX, float localY) {
            return null; // paint to the pointer: a press is the field's
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            SizeTokens t = Theme.of(this).tokensFor(ComboBox.this);
            boolean rtl = ComboBox.this.isRightToLeft();
            float gap = t.spacingSmall();
            // One point less than a segment's pill above and below (the owner's call, 2026-09-30):
            // a chip is a mark on the field, and at the pill's full height it read as a button.
            float chipH = Math.max(0, height() - 2 * t.segInset() - 2);
            float y = (height() - chipH) / 2;
            int n = shown.length;
            float[] widths = new float[n];
            for (int i = 0; i < n; i++) {
                Widget<?> chip = children().get(i);
                widths[i] = chip.measure(Constraints.loose(Float.POSITIVE_INFINITY, chipH)).width();
            }
            // As many whole chips as fit with the badge after them when any are left over.
            int fit = n;
            for (int k = n; k >= 0; k--) {
                float total = 0;
                for (int i = 0; i < k; i++) {
                    total += widths[i] + (i > 0 ? gap : 0);
                }
                if (k < n) {
                    badge.count = n - k;
                    total += (k > 0 ? gap : 0) + badge.measure(Constraints.loose(width(), chipH)).width();
                }
                if (total <= width() || k == 0) {
                    fit = k;
                    break;
                }
            }
            boolean squeezed = fit == 0 && n > 0;
            if (squeezed) {
                // Not even the first chip fits beside the badge: it takes what the badge leaves.
                fit = 1;
                badge.count = n - 1;
                float badgeW = n > 1 ? badge.measure(Constraints.loose(width(), chipH)).width() + gap : 0;
                widths[0] = Math.max(0, width() - badgeW);
            }
            float x = 0;
            for (int i = 0; i < n; i++) {
                Widget<?> chip = children().get(i);
                if (i >= fit) {
                    chip.measure(Constraints.tight(0, chipH));
                    chip.layoutBox(0, y, 0, chipH);
                    chip.setVisible(false);
                    continue;
                }
                chip.setVisible(true);
                chip.measure(Constraints.tight(widths[i], chipH));
                chip.layoutBox(rtl ? width() - x - widths[i] : x, y, widths[i], chipH);
                x += widths[i] + gap;
            }
            boolean more = fit < n;
            badge.setVisible(more);
            if (more) {
                badge.count = n - fit;
                float bw = badge.measure(Constraints.loose(width(), chipH)).width();
                badge.layoutBox(rtl ? width() - x - bw : x, y, bw, chipH);
            } else {
                badge.layoutBox(0, y, 0, chipH);
            }
        }
    }

    /**
     * The default chip: a pill with the item's icon, when the combo gives items one, and its text,
     * shortened with an ellipsis when the strip gives it less than it asked for.
     */
    private final class Chip extends Widget<Chip> {

        private final int item;

        Chip(int item) {
            this.item = item;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            SizeTokens t = Theme.of(this).tokensFor(ComboBox.this);
            float text = textRuler().measure(items.get(item).get(), t.body()).width();
            Icon icon = iconOfItem(item);
            float w = 2 * chipPadH(t) + text + (icon != null ? t.fieldIcon() + t.spacingSmall() : 0);
            return constraints.constrain(w, constraints.maxHeight());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            SizeTokens t = theme.tokensFor(ComboBox.this);
            boolean rtl = ComboBox.this.isRightToLeft();
            boolean enabled = ComboBox.this.isEnabled();
            canvas.fillRoundRect(0, 0, width(), height(), t.segPillRadius(),
                    enabled ? theme.primary().withAlpha(0.16f) : theme.disabledFill());
            Color ink = enabled ? theme.text() : theme.disabledText();
            float pad = chipPadH(t);
            float x = pad;
            Icon icon = iconOfItem(item);
            if (icon != null) {
                float ico = t.fieldIcon();
                icon.paint(canvas, rtl ? width() - pad - ico : pad, (height() - ico) / 2, ico, ink,
                        theme.isDark(), false);
                x += ico + t.spacingSmall();
            }
            Font font = t.body();
            float room = Math.max(0, width() - x - pad);
            ShapedText line = textRuler().shape(items.get(item).get(), font,
                    ShapedText.Direction.of(items.get(item).get(), neutralBase()));
            if (line.metrics().width() > room) {
                line = textRuler().ellipsize(line, room);
            }
            TextMetrics metrics = textRuler().measure("Hg", font);
            canvas.drawText(line, rtl ? width() - x - line.metrics().width() : x,
                    (height() - metrics.height()) / 2 + metrics.ascent(), ink);
        }
    }

    /** The count of the chips that did not fit: "+2", in the language's digits. */
    private final class Badge extends Widget<Badge> {

        private int count;

        private String text() {
            return ComponentStrings.COMBO_MORE.format(count);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            SizeTokens t = Theme.of(this).tokensFor(ComboBox.this);
            float w = 2 * t.spacingSmall() + textRuler().measure(text(), t.label()).width();
            return constraints.constrain(w, constraints.maxHeight());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            SizeTokens t = theme.tokensFor(ComboBox.this);
            boolean enabled = ComboBox.this.isEnabled();
            // Smaller than a chip, its own box inset within the chip's band: a count is a note
            // beside the chips, not one of them.
            float inset = t.segInset();
            float h = Math.max(0, height() - 2 * inset);
            canvas.fillRoundRectWithBorder(0, inset, width(), h, Math.min(t.segPillRadius(), h / 2),
                    enabled ? theme.surfaceRaised() : theme.disabledFill(), Strokes.BORDER,
                    theme.outline());
            Font font = t.label();
            String text = text();
            ShapedText line = textRuler().shape(text, font, ShapedText.Direction.of(text, neutralBase()));
            TextMetrics metrics = textRuler().measure("Hg", font);
            canvas.drawText(line, (width() - line.metrics().width()) / 2,
                    (height() - metrics.height()) / 2 + metrics.ascent(),
                    enabled ? theme.textMuted() : theme.disabledText());
        }
    }

    /** A chip's inner margin on each side of its content. */
    private static float chipPadH(SizeTokens t) {
        return t.spacingSmall() + t.segInset();
    }

    // ---------------------------------------------------------- scene popup

    /**
     * The dropdown as a scene overlay: a transparent layer the size of the owner scene, holding
     * the same {@link PopupPanel} the native popup puts in a window of its own.
     *
     * <p>Two things it must do that the native popup gets from the OS for free.
     *
     * <p>It <b>holds the keyboard</b>, because {@code pushOverlay} confines focus to the overlay
     * and the field underneath can no longer receive a key. So this layer is focusable and hands
     * what it receives back to the combo, which is where the list's whole key and type-ahead
     * behaviour lives. Nothing is duplicated: both presentations run the same handlers.
     *
     * <p>It <b>dismisses on a press outside the list</b>, which for the native popup is the
     * owner scene's press observer. That observer cannot serve here: an overlay captures every
     * press in the scene, the list's own included, so it would fire on the press that is
     * choosing an item and close the popup before the click landed.
     *
     * <p>The list is clamped to the scene rather than to the display, so a combo near the bottom
     * of a window has less room than the same combo would on a platform that can put its list in
     * a window. That is the visible cost of the fallback, and it is why it is a fallback.
     *
     * <p><b>It is a node in the accessible tree, and holding the keyboard is why.</b> A layer
     * that paints nothing would ordinarily be deleted as scaffolding, but this one is the
     * scene's focused widget and its only tab stop for as long as the list is open, so deleting
     * it would put the tree and the keyboard into disagreement. It says what it is, names itself
     * because nothing else can, and carries the active descendant a reader follows the arrows
     * by; the options themselves are the panel's, which is where they are drawn.
     */
    private final class ScenePopup extends Widget<ScenePopup> {

        private final PopupPanel panel;

        ScenePopup(PopupPanel panel) {
            this.panel = panel;
            setFocusable(true);
            add(panel);
        }

        /** The overlay is laid out tight to the scene; that size is the bound the list clamps to. */
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            SizeTokens t = Theme.of(this).tokensFor(ComboBox.this);
            // The field's direction and not the overlay's, for the reason the tokens are the
            // field's: this list belongs to the combo, and the overlay is only the layer it is
            // drawn on. Resolved once for the pass.
            boolean rtl = ComboBox.this.isRightToLeft();
            float gap = t.popupGap();
            // Read after the root has been laid out: the scene lays overlays out last, which is
            // what makes the field's scene position current here rather than one frame stale.
            float anchorX = ComboBox.this.localToSceneX();
            float anchorTop = ComboBox.this.localToSceneY();
            float anchorBottom = anchorTop + ComboBox.this.height() + gap;
            Drop drop = dropFor(t,
                    anchorTop - gap - EDGE_MARGIN,
                    height() - anchorBottom - EDGE_MARGIN);

            float listWidth = Math.min(ComboBox.this.width(), width());
            // Hung from the field's LEADING edge, which is its right one reading right to left;
            // anchorX stays the field's physical left either way.
            //
            // The two branches compute the same number today, and saying so is the point: the
            // list is the field's own width unless the scene is narrower still, and a box the
            // width of the field aligned to either of the field's edges lands on the field. The
            // alignment is written down anyway because that is the invariant it rests on, and
            // the day a list is narrower than the field that it opened under, this line is
            // already the one that has to be right.
            float wanted = rtl ? anchorX + ComboBox.this.width() - listWidth : anchorX;
            // The clamp keeps its form, and it needs no direction of its own: listWidth is a
            // min with this overlay's width, so the interval is never empty and clamping the
            // leading edge first agrees with clamping the trailing one first.
            float x = Math.max(0, Math.min(wanted, width() - listWidth));
            float y = drop.above() ? anchorTop - gap - drop.height() : anchorBottom;
            panel.measure(Constraints.tight(listWidth, drop.height()));
            panel.layoutBox(x, y, listWidth, drop.height());
        }

        /** Keys reach the list through here; see the class note on why they are not handled here. */
        @Override
        protected void onKeyEvent(KeyEvent event) {
            ComboBox.this.onKeyEvent(event);
        }

        @Override
        protected void onCharTyped(limn.scene.event.CharEvent event) {
            ComboBox.this.onCharTyped(event);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            // Only presses that missed the list arrive here; one inside it is the panel's.
            if (event.type() == MouseEvent.Type.PRESS) {
                close(Change.Origin.USER);
                event.consume();
            }
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            // This layer is not scaffolding, whatever a survey of it from the outside says. The
            // constructor makes it focusable and pushOverlay's focus traverse finds nothing
            // focusable beneath it, so for the whole life of an in-scene list it is the scene's
            // focused widget and its only tab stop. Deleting it would leave the published
            // focusable set empty while the keyboard reaches one widget, which is the equality
            // the modal rule states, and would leave the focused identifier naming a node the
            // tree does not contain. Every parentless top overlay is also declared modal by the
            // walk before the transparency predicate runs, so none of them can be deleted.
            //
            // The role is declared rather than left to the reset default, because a declared
            // role is what stops a focusable node being published as UNKNOWN with a warning
            // logged once and read by nobody. GROUP and not LIST: the panel below is the list,
            // it has the list's own rectangle, and two nested lists is a shape every bridge has
            // to invent its way out of. Not WINDOW, which the tree's own root already is; not
            // DIALOG, which this is not and which a Windows client acts on.
            a.role(Accessible.Role.GROUP);
            // A constant this class holds, never a string built here: an open list runs a fade
            // and damages this layer on every frame of it, and anything allocated in this hook
            // is allocated per frame to conclude that nothing moved. Nothing else can name it --
            // it paints nothing, has no tooltip, and is private to this file -- and the field's
            // own name is not reachable from this package. The relation below is what carries
            // the link to the field instead. The provenance is the control's own name and not a
            // description of it, which is the slot a tooltip would take.
            a.name(ComponentStrings.COMBO_POPUP, Accessible.NameFrom.CONTENT);
            // The whole reason for describing this node well. Focus never moves while the list
            // is open, so the only way a reader can follow Up, Down, Home, End, the page keys or
            // type-ahead is an active descendant on the node that holds the focus -- which is
            // this one, and not the list. The facet declares the container; which of its
            // descendants the cursor is on is the first one published ACTIVE, filled in by the
            // publish step, so this is inert until the panel marks its highlighted row. In this
            // list the cursor and the selection are two things: the highlight moves under the
            // arrows and the selection moves only on commit -- which is about the ACTIVE
            // descendant and not about whether a selection exists. Required is true for the
            // combo's own documented reason, the same one the panel's facet gives over these
            // very members: it refuses an empty item list, so there is always exactly one
            // selection and nothing to clear to.
            boolean many = selectionMode == SelectionMode.MULTI;
            a.selection(many, !many && !emptyAllowed);
            // The single-argument form, never the variable-argument one, which allocates an
            // array per call. CANCEL and nothing else: Esc dismisses the list, a press on this
            // layer is the click-outside dismissal that CANCEL already names, and offering both
            // would be two verbs for one behaviour. FOCUS and SCROLL_INTO_VIEW arrive free
            // because the widget is focusable, and MODAL arrives free from the walk, so neither
            // is written here. Only while the list is open: through the fade-out after close()
            // the layer is still the top overlay and was still published with CANCEL, which
            // close()'s own guard then dropped while the hook answered done (2026-09-15,
            // semantics 5); the hook below reads the same field.
            if (open) {
                a.action(Accessible.Action.CANCEL);
            }
            // No bounds call. The node's box is this overlay's own, which is the whole scene --
            // the layer that genuinely captures every press, the same shape as a dialog's scrim
            // -- so a platform hit test outside the list resolves to this node, and that is
            // true: a press there closes the popup. The list's rectangle is the panel's node.
        }

        @Override
        protected boolean onAccessibilityAction(Accessible.Action action,
                                                Accessible.Argument arg) {
            if (action != Accessible.Action.CANCEL || !open) {
                return false; // not while the list fades out, where nothing publishes CANCEL
            }
            // The path Esc takes, with its own guards: close() checks the thread and returns on
            // a popup that is already closing, and the posted action has already re-checked
            // attachment, the enabled chain, showing and reachability. Nothing here gains an
            // entry point the keyboard does not already use.
            ComboBox.this.close(Change.Origin.USER);
            return true;
        }
    }

    // ---------------------------------------------------------- popup panel

    /**
     * Rounded, translucent item list living in its own transparent window. When
     * the list was clamped to the work area it scrolls: wheel + draggable
     * {@link ScrollBar}, and the keyboard highlight auto-reveals ({@link Scrollable}).
     *
     * <p><b>{@link ScrollBar#thickness()} does not participate in the size axis</b>: it is
     * 15pt at every step. Unlike {@code TextArea} and {@code ListView},
     * which overlay their bar, this panel <em>reserves</em> the column: bar plus
     * {@code popupBarInsetX} takes 17 / 17 / 18 / 19 / 20 pt of the panel's width from XSMALL
     * to XLARGE. Against a field at the step's {@code fieldWidth} that is 9.9% of an XSMALL
     * popup against 7.5% of a MEDIUM one, and the 15pt thumb track covers 62.5% of a 24pt
     * XSMALL row against 50% of a 30pt MEDIUM one. Accepted cost of one scrollbar geometry
     * process-wide; it is stated here rather than absorbed silently.
     */
    private final class PopupPanel extends Widget<PopupPanel> implements Scrollable {

        /** The shadow the list casts while it is drawn in the scene. */
        @Override
        protected float paintOutset() {
            return scenePopup != null ? Theme.of(this).elevation(Elevation.OVERLAY).reach() : 0;
        }

        private final ScrollBar vBar;
        private int hoverIndex = -1;
        private float scroll;
        private boolean initialRevealDone;

        PopupPanel() {
            setCursor(Cursor.POINTER); // the whole list reads as clickable
            vBar = new ScrollBar(ScrollBar.Orientation.VERTICAL, new ScrollBar.Model() {
                @Override
                public float contentLength() {
                    return contentHeight(tokens());
                }

                @Override
                public float viewportLength() {
                    return height();
                }

                @Override
                public float offset() {
                    return scroll;
                }

                @Override
                public void setOffset(float value) {
                    scroll = clampScroll(value, tokens());
                    invalidate();
                }
            });
            add(vBar);
        }

        /**
         * The panel's row, resolved through the host link to the owning combo. Called once at
         * the top of each pass, and from the {@link ScrollBar.Model} callbacks, which are
         * entered from the scroll bar's own pass and so cannot be handed one.
         */
        private SizeTokens tokens() {
            return Theme.of(this).tokensFor(this);
        }

        private float clampScroll(float value, SizeTokens t) {
            return Math.max(0, Math.min(value, Math.max(0, contentHeight(t) - height())));
        }

        private void scrollListBy(float dy, SizeTokens t) {
            float clamped = clampScroll(scroll + dy, t);
            if (clamped != scroll) {
                scroll = clamped;
                vBar.onScrolled();
                invalidate();
            }
        }

        /**
         * Local y of row {@code index}'s top edge, <b>the</b> row formula. Painting, damage,
         * reveal and hit-testing all go through this and {@link #rowAt(float, SizeTokens)};
         * they used to carry four independent copies of the same expression, which is how a
         * hit index and a paint index drift apart silently. The tokens are a parameter for the
         * same reason: two resolutions inside one gesture would put the copies back.
         */
        float rowTop(int index, SizeTokens t) {
            return t.popupPadV() + index * t.popupItemHeight() - scroll;
        }

        /**
         * Inverse of {@link #rowTop(int, SizeTokens)}: the row a local y lands in.
         * {@code rowAt(rowTop(i)) == i} for every row at every scroll offset; that identity is
         * the whole contract, and {@code PopupPanelTest} asserts it exhaustively.
         *
         * <p>Truncating rather than flooring, deliberately, because that is what the four
         * copies this replaces did: a click in the panel's <em>top</em> padding truncates to 0
         * and therefore hits row 0, extending its target upward. Note the asymmetry: the
         * bottom padding yields {@code items.size()}, which is out of range and inert. Whether
         * the top padding should be inert too is a real question, but it is a behaviour change
         * and not this refactor's business.
         */
        int rowAt(float localY, SizeTokens t) {
            return (int) ((localY + scroll - t.popupPadV()) / t.popupItemHeight());
        }

        /**
         * Whether the list reads right to left: <b>the field's</b> resolved direction, taken
         * once per pass into a local exactly as the row tokens are.
         *
         * <p>Asking the field rather than resolving here is not an override, and there is no
         * setter to override: a list whose rows read the other way from the field that opened
         * them is a bug in either presentation. The two answers agree wherever the direction
         * comes from above them both, and they part in the one case the axis exists for. A
         * panel in a window of its own is parentless and reaches the field through its host
         * link, but the in-scene panel is a child of the overlay layer, and the tree wins over
         * a host link: a field declaring a direction its scene does not share would open a list
         * that read the other way.
         *
         * <p>Never in this panel's constructor, which runs before it is linked to the field at
         * all.
         */
        private boolean isRtl() {
            return ComboBox.this.isRightToLeft();
        }

        /**
         * Local x of the selected-row marker's centre: the marker column sits at the start of a
         * row, which is the right side of a row that reads right to left.
         *
         * <p>A method for {@link #rowTop(int, SizeTokens)}'s reason, and taking its resolved
         * inputs for that reason too. The x axis has the same failure the y axis had: three
         * copies of {@code width() - inset - …} across the dot, the label and the label's clip
         * is how a mark and the text it marks drift onto different columns.
         */
        float rowDotX(boolean rtl, SizeTokens t) {
            float inset = t.popupRowInsetX();
            return rtl ? width() - inset - t.popupDotCol() : inset + t.popupDotCol();
        }

        /**
         * Local x where a row's label starts reading: past the marker column, on the side
         * reading starts from. It is the label's left edge reading left to right and its
         * <em>right</em> edge reading right to left, which is why the paint subtracts the run's
         * own width there and the clip band is measured from the other side.
         */
        float rowTextX(boolean rtl, SizeTokens t) {
            float inset = t.popupRowInsetX();
            return rtl ? width() - inset - t.popupMarkerCol() : inset + t.popupMarkerCol();
        }

        void revealRow(int index, SizeTokens t) {
            reveal(rowTop(index, t), t.popupItemHeight(), t);
        }

        /** Repaints a single row; hover/highlight moves must not damage the whole panel. */
        void damageRow(int index, SizeTokens t) {
            if (index >= 0 && index < rows.length) {
                invalidate(0, rowTop(index, t), width(), t.popupItemHeight());
            }
        }

        @Override
        public void revealRect(float x, float y, float rectWidth, float rectHeight) {
            reveal(y, rectHeight, tokens());
        }

        /** Scrolls the minimum so the band is fully visible (viewport coordinates). */
        private void reveal(float y, float bandHeight, SizeTokens t) {
            if (y < 0) {
                scrollListBy(y, t);
            } else if (y + bandHeight > height()) {
                scrollListBy(Math.min(y, y + bandHeight - height()), t);
            }
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            SizeTokens t = tokens();
            boolean rtl = isRtl();
            float barW = ScrollBar.thickness();
            float barH = height() - 2 * t.popupPadV();
            vBar.measure(Constraints.tight(barW, barH));
            // The reserved bar column is on the trailing edge, which is the left one reading
            // right to left. The bar's own geometry is vertical and is untouched by any of this.
            vBar.layoutBox(rtl ? t.popupBarInsetX() : width() - barW - t.popupBarInsetX(),
                    t.popupPadV(), barW, barH);
            scroll = clampScroll(scroll, t);
            if (!initialRevealDone && height() > 0) {
                initialRevealDone = true;
                revealRow(highlightedRow, t); // open with the current selection in view
            }
            vBar.refresh();
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            SizeTokens t = tokens();
            boolean rtl = isRtl();
            float itemH = t.popupItemHeight();
            float inset = t.popupRowInsetX();
            // In-scene the canvas opacity does the fade; a popup window fades through its own
            // compositor opacity, so that presentation paints solid here.
            boolean fading = scenePopup != null && sceneFade < 1f;
            if (fading) {
                canvas.save();
                canvas.setOpacity(sceneFade);
            }
            // Slightly translucent panel: the desktop shows through, and the rounded corners are
            // genuinely transparent (framebuffer alpha 0). In-scene the same 0.94 composites
            // over the owner's own content instead of over the desktop, and a list you can read
            // the page through is not frosted glass: it is unreadable. So that presentation
            // paints the theme's surface as the theme wrote it.
            if (scenePopup != null) {
                // In the scene the list lies over the page, and says so as every surface that
                // covers does; in a window of its own the shadow is the window's.
                theme.drawElevation(canvas, limn.graphics.RoundRect.of(0, 0, width(), height(),
                        t.radiusLarge()), Elevation.OVERLAY);
            }
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), t.radiusLarge(),
                    scenePopup != null ? theme.surfaceRaised()
                            : theme.surfaceRaised().withAlpha(0.94f),
                    Strokes.BORDER, theme.outline());

            Font font = t.body();
            TextMetrics metrics = textRuler().measure("Hg", font);
            // The two row columns and the label's band, composed once: they are the same for
            // every row, and computing them per row is how three copies of one expression get
            // back in.
            float dotX = rowDotX(rtl, t);
            float textStart = rowTextX(rtl, t);
            // The band runs from where the label starts to the panel's far inset, so it is the
            // label's trailing limit that is computed and the sides swap with the direction.
            float bandX = rtl ? inset : textStart;
            float bandW = rtl ? textStart - inset : width() - inset - textStart;
            // The icon column, when any item has an icon: every row keeps it, so labels align.
            float ico = t.fieldIcon();
            float lead = iconRoom(t);
            canvas.save();
            // Keep rows off the border: the clip inset IS the border width.
            canvas.clipRect(Strokes.ROW_CLIP, Strokes.ROW_CLIP,
                    width() - 2 * Strokes.ROW_CLIP, height() - 2 * Strokes.ROW_CLIP);
            for (int i = 0; i < rows.length; i++) {
                float top = rowTop(i, t);
                if (top + itemH < 0 || top > height()) {
                    continue; // scrolled out
                }
                boolean active = i == highlightedRow || i == hoverIndex;
                if (active) {
                    // ROW_GUTTER, not a scaled inset: the 1pt gap exists to stop two
                    // highlighted rows fusing, and 3pt of it reads as a deliberate stripe.
                    canvas.fillRoundRect(inset, top + Strokes.ROW_GUTTER,
                            width() - 2 * inset, itemH - 2 * Strokes.ROW_GUTTER,
                            t.radiusSmall(), theme.primary().withAlpha(0.28f));
                }
                if (selectionMode == SelectionMode.MULTI) {
                    if (chosen.get(rows[i])) {
                        // A check and not the dot: many rows carry it, as a menu's check items
                        // do. Centred on the dot's column, and never mirrored.
                        paintCheck(canvas, dotX - t.checkGlyphW() / 2, top + itemH / 2,
                                theme.primary(), t);
                    }
                } else if (rows[i] == EMPTY_ROW ? emptyChosen() : rows[i] == selectedIndex) {
                    // The one mark that scales: pure area, no pen, and 2.5pt vanishes in a
                    // 42pt row.
                    canvas.fillCircle(dotX, top + itemH / 2, t.popupDotRadius(), theme.primary());
                }
                // Shaped against this list's own direction as the neutral fallback, for the
                // reason the field's label is, and only for the rows that survived the
                // visibility check above. It is also where the run's width comes from: aligning
                // to the edge reading starts from needs it, and the row loop has no other
                // measurement of the label.
                String label = labelOfRow(rows[i]).get();
                ShapedText line = shapeText(label, font);
                // Clip each row so an over-long label can't reach the rounded border.
                canvas.save();
                canvas.clipRect(bandX, top, bandW, itemH);
                Icon icon = iconOfRow(rows[i]);
                if (icon != null) {
                    // The icon leads the text, on the side reading starts from, and the text
                    // moves past it; a row without one keeps the column, so the labels align.
                    icon.paint(canvas, rtl ? textStart - ico : textStart,
                            top + (itemH - ico) / 2, ico, theme.text(), theme.isDark(), false);
                }
                canvas.drawText(line, rtl ? textStart - lead - line.metrics().width()
                                : textStart + lead,
                        top + (itemH - metrics.height()) / 2 + metrics.ascent(),
                        theme.text());
                canvas.restore();
            }
            I18nString status = statusLine;
            if (rows.length == 0 && status != null) {
                // Loading, nothing found, or a failed search: one muted line where the first row
                // would be, not an option, so it is neither highlighted nor chosen.
                float top = rowTop(0, t);
                ShapedText line = shapeText(status.get(), font);
                canvas.save();
                canvas.clipRect(bandX, top, bandW, itemH);
                canvas.drawText(line, rtl ? textStart - line.metrics().width() : textStart,
                        top + (itemH - metrics.height()) / 2 + metrics.ascent(),
                        theme.textMuted());
                canvas.restore();
            }
            canvas.restore();
            if (fading) {
                canvas.restore();
            }
        }

        /**
         * The tick of a selected row in a list holding many, drawn from its left edge at
         * {@code cx}: the menu's own check (PopupMenu), scaled by {@code checkGlyphW} and drawn
         * with its locked pen. A check mark does not mirror.
         */
        private void paintCheck(Canvas canvas, float cx, float cy, Color color, SizeTokens t) {
            float k = t.checkGlyphW() / 9f;
            canvas.drawLine(cx, cy + k, cx + 3.5f * k, cy + 4.5f * k, Strokes.MENU_CHECK_PEN, color);
            canvas.drawLine(cx + 3.5f * k, cy + 4.5f * k, cx + 9 * k, cy - 4 * k,
                    Strokes.MENU_CHECK_PEN, color);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            SizeTokens t = tokens(); // one resolution per event: the hit row is this row formula
            float ly = sceneToLocalY(event.y());
            int index = rowAt(ly, t);
            boolean valid = index >= 0 && index < rows.length;
            switch (event.type()) {
                case MOVE, ENTER -> {
                    vBar.onHostActivity();
                    int newIndex = valid ? index : -1;
                    if (newIndex != hoverIndex) { // partial rendering: two rows, not the panel
                        damageRow(hoverIndex, t);
                        hoverIndex = newIndex;
                        damageRow(hoverIndex, t);
                    }
                }
                case EXIT -> {
                    if (hoverIndex != -1) {
                        damageRow(hoverIndex, t);
                        hoverIndex = -1;
                    }
                }
                case WHEEL -> {
                    if (event.scrollY() != 0 && contentHeight(t) > height()) {
                        scrollListBy(-event.scrollY() * Strokes.WHEEL_STEP, t);
                        event.consume();
                    }
                }
                case CLICK -> {
                    if (valid) {
                        commit(index);
                    }
                    event.consume();
                }
                case PRESS -> event.consume();
                default -> {
                }
            }
        }

        /**
         * The keys that reach the list's own window, which the desktop makes the key window on a
         * click in it that chooses nothing: on the scrollbar, or on the padding round the rows.
         * Nothing in this scene is focusable, so they arrive here and go where they would have
         * gone from the field. Tab, which the field would have passed on, closes the list and
         * gives the field its window's keyboard back. In the scene the overlay above takes them.
         */
        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (scenePopup != null) {
                return;
            }
            ComboBox.this.onKeyEvent(event);
            if (!event.isConsumed() && event.isPressed() && event.key() == Keys.TAB && open) {
                close(Change.Origin.USER);
                event.consume();
            }
        }

        @Override
        protected void onCharTyped(limn.scene.event.CharEvent event) {
            if (scenePopup == null) {
                ComboBox.this.onCharTyped(event);
            }
        }

        /**
         * The list itself, and one node per option.
         *
         * <p><b>Why it is described at all.</b> The panel is not focusable, has no tooltip and
         * declares nothing free, so without this hook the transparency predicate deletes it — and
         * because it paints, the deletion is a warning logged once per class and read by nobody,
         * while the options themselves, which are painted rows and not widgets, would simply not
         * exist. It is the widget that draws the options, so it is the widget that describes them.
         *
         * <p><b>No name.</b> The panel is not focusable, so the every-focusable-node-is-named rule
         * does not reach it, and the layer above already carries the list's name and the link to
         * the field. A second invented string would be one more thing to translate and one more
         * thing a reader has to hear before the options.
         *
         * <p><b>No popup relation either, and that is a correction.</b> (The one relation it
         * does declare is an editable combo's {@code CONTROLLED_BY}, naming the field that filters
         * it, which is another pair.) In the in-scene presentation the
         * overlay is the popup's root and the walk gives it {@code POPUP_FOR} the field and the
         * field the mirror, so a second link declared here would have two nodes in one tree
         * claiming to be the field's popup. In a window of its own the panel is parentless with a
         * host link, which is exactly the shape the walk's own popup gate answers, so the relation
         * is registered there too without a line here. Both mountings are covered by the walk.
         *
         * <p><b>Nothing is formatted here.</b> Every option's name is an {@link I18nString} the
         * combo already holds, handed over as a source to be compared by reference; the rest is
         * primitives. An open list is damaged on every frame of its fade and on every hover move,
         * so a string built in this hook would be built once a frame to conclude that nothing
         * moved.
         *
         * @param a the builder for this node
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            // Once for the pass, as rowTop, rowAt and damageRow already require: two resolutions
            // inside one description could place a row against one step and size it against
            // another.
            SizeTokens t = tokens();
            float itemH = t.popupItemHeight();
            float inset = t.popupRowInsetX();
            float viewport = height();
            float contentH = contentHeight(t);
            float maxScroll = Math.max(0, contentH - viewport);

            a.role(Accessible.Role.LIST);
            // The container half of the ROWS shape (ADR 045 §3): single-selection and
            // always-selected, which are the combo's own documented invariant: it refuses an
            // empty item list, so there is exactly one selection and nothing to clear to. No
            // PRESS of its own. The active descendant is not declared and cannot be: it is
            // resolved in the copy from the first node in this subtree published ACTIVE, which is
            // the highlighted option below.
            boolean many = selectionMode == SelectionMode.MULTI;
            // Holding many, the list's selection is the items checked, and it may be empty; the
            // highlight is only the cursor (ADR 052 §4).
            RowsAccessibility.describeContainer(a, many ? RowsAccessibility.Selection.MULTI
                    : RowsAccessibility.Selection.SINGLE, !many, false);
            if (editor != null) {
                // The mirror of the field's CONTROLLER_FOR, declared rather than left to a bridge,
                // because it is the list that a reader asks: Orca 50.2 reads a selection change
                // in a list controlled by the focused field that autocompletes as the field's
                // own business, and reads it again as a list otherwise.
                a.relation(Accessible.Relation.CONTROLLED_BY, editor);
            }
            // The survey's row omits this and the panel scrolls: the wheel, a real ScrollBar child
            // with a real model, Scrollable#revealRect and the keyboard's auto-reveal all move it,
            // and a list clamped to the work area or to the owner scene is the ordinary case
            // rather than the long one. Computed from the same expressions clampScroll uses, so
            // the facet and the clamp cannot drift apart.
            a.scrollFrom(0, 0, 1, 0, scroll, maxScroll, viewport, contentH);
            I18nString status = statusLine;
            if (status == ComponentStrings.COMBO_LOADING) {
                a.state(Accessible.State.BUSY); // as a tree's branch says while it loads
            }
            if (rows.length == 0 && status != null) {
                // The line is text in the list and not an option: a reader reads it, and nothing
                // can be chosen or walked onto. Keyed apart from every item index.
                a.child(Integer.MAX_VALUE);
                a.bounds(inset, rowTop(0, t), width() - 2 * inset, itemH);
                a.role(Accessible.Role.LABEL);
                a.name(status, Accessible.NameFrom.CONTENT);
                a.endChild();
            }
            // No MODAL. In scene the walk already stamps it on the parentless top overlay, which
            // is the layer this panel is a child of; in a window of its own the modality is the
            // popup window's. Declaring it here would publish it twice in one tree.

            // Read once, and read again by onSyntheticAction: the three verbs are published
            // exactly while they are performed (semantics 5), and not through the fade-out or on
            // a combo disabled under its open list, where the hook refuses all three.
            boolean operable = optionsOperable();
            // A combo disabled under its open list: the options say so themselves. The walk no
            // longer reads a parentless overlay's enabled axis off its inheritance host, because
            // the keyboard and the pointer never did (ADR 039 §1.9, amended 2026-09-15), and a
            // list in a window of its own is walked as the root of a scene of its own, grafted
            // under the field, so this is the one place both mountings learn it. Narrowing takes
            // every verb off the row too.
            boolean inert = !ComboBox.this.isEnabled();
            for (int i = 0; i < rows.length; i++) {
                float top = rowTop(i, t);
                // Keyed by the item and not by the row: an option keeps its identity, and a
                // reader its place, when a filter moves it up or down the list.
                a.child(rows[i] == EMPTY_ROW ? EMPTY_KEY : rows[i]);
                // From the row formula the paint, the damage, the reveal and the hit test all
                // share, and never from an index times a nominal height: rowTop subtracts the
                // scroll, and a clamped list scrolls. The full row height and not the painted
                // highlight's inset band, because the gutter is a cosmetic gap and rowAt hits the
                // whole row: the box published has to be the box a click lands in.
                a.bounds(inset, top, width() - 2 * inset, itemH);
                a.role(Accessible.Role.LIST_ITEM);
                // The string the combo holds, not the string it reads as: reference, locale and
                // translation epoch are what the difference compares, and get() would allocate one
                // per option per frame.
                // The empty option's own words, or when the application gave it none, the
                // toolkit's, since an option a reader lands on must say something.
                I18nString name = labelOfRow(rows[i]);
                a.name(rows[i] == EMPTY_ROW && name.get().isEmpty() ? ComponentStrings.COMBO_NONE
                        : name, Accessible.NameFrom.CONTENT);
                // The ROWS shape, written once (ADR 045 §3), read against this widget. The list's
                // selection is the highlight, and the combo's value is not: the arrows and
                // type-ahead move the selection of the list, and only a commit moves the value,
                // as a native drop-down list does. Until 2026-09-23 the list's selection was the
                // value and the highlight a cursor apart from it, so NVDA and Orca said "not
                // selected" of every option the arrows reached, where Windows' own combo says the
                // option alone (readings/combo-windows, native-combo-winforms). The verbs, only
                // while they are performed (semantics 5): SELECT moves the highlight, as an arrow
                // does, and PRESS chooses; no FOCUS, because the cursor is the selection
                // (decision 11), and no SCROLL_INTO_VIEW: the list reveals its highlight itself.
                // Not the hover either, which is a pointer affordance and would republish the tree
                // on every mouse move. A narrowed row carries no verb, as below.
                boolean verbs = !inert && operable;
                // The cursor only while the list is open, for the verbs' reason: a list closing
                // through its fade is published under the focused combo in a window of its own
                // (Scene#graftPopup), and an option still ACTIVE there kept the reader's cursor on
                // it until the window was gone, and then moved it back to the combo.
                if (many) {
                    // Checked is selected; the cursor is apart from it, so FOCUS moves it.
                    RowsAccessibility.describeRow(a, RowsAccessibility.Offer.OWNED,
                            verbs ? RowsAccessibility.Selection.MULTI : RowsAccessibility.Selection.NONE,
                            chosen.get(rows[i]), i + 1, rows.length, false, false,
                            operable && i == highlightedRow, verbs, verbs, false);
                } else {
                    RowsAccessibility.describeRow(a, RowsAccessibility.Offer.OWNED,
                            verbs ? RowsAccessibility.Selection.SINGLE : RowsAccessibility.Selection.NONE,
                            i == highlightedRow, i + 1, rows.length, false, false,
                            operable && i == highlightedRow, verbs, false, false);
                }
                // The negation of the paint loop's own skip test, so the tree and the pixels agree
                // by construction rather than by two people remembering the same rule. Every
                // option is still published, because the count and each option's position in it
                // are what a reader is told and they do not change with the scroll.
                if (top + itemH < 0 || top > viewport) {
                    a.offScreen();
                }
                if (inert) {
                    a.disabled();
                }
                a.endChild();
            }
        }

        /**
         * Chooses an option, through the same private path a click on it takes, or moves the
         * highlight onto it, through the path the arrow keys take.
         *
         * <p>All three verbs are refused, and none of them published, while
         * {@code optionsOperable()} is false: until 2026-09-15, through the list's fade-out the
         * three were published and {@code FOCUS} was refused here while {@code SELECT} and
         * {@code PRESS} were answered {@code true} for a commit that its own guard dropped.
         * {@code commit} keeps that guard for the pointer, clamps the index, closes the list and
         * notifies the application only when the selection actually moved — so re-picking what is
         * already selected says nothing here either, which is the rule the pointer already obeys.
         * Nothing gains a public entry point for this.
         *
         * <p>The enabled half is load-bearing in a window of its own: the scene's gate walks the
         * owner's ancestors, and there the panel's chain is the panel alone, with the combo in
         * another scene entirely. A list opened through the public {@code open()} on a disabled
         * combo would otherwise accept a commit.
         *
         * @param key    the option's index, which is its key
         * @param action what is being asked
         * @param arg    unused; all three verbs here are parameterless
         * @return whether the option was chosen
         */
        @Override
        protected boolean onSyntheticAction(long key, Accessible.Action action,
                                            Accessible.Argument arg) {
            if (!optionsOperable()) {
                return false;
            }
            int row = key == EMPTY_KEY ? rowOfItem(EMPTY_ROW)
                    : key < 0 || key >= items.size() ? -1 : rowOfItem((int) key);
            if (row < 0) {
                return false; // an option the filter no longer shows
            }
            return RowsAccessibility.performOnRow(rowsHost, row, action);
        }

        /**
         * The list's mechanisms as the rows shape drives them: the list's selection is the
         * highlight, so {@code SELECT} moves it through the arrows' path and {@code PRESS} commits
         * the option; {@code FOCUS} is refused, because the cursor is the selection. Nothing
         * reveals: the verb is not published on an option and the list reveals its highlight
         * itself.
         */
        private final class RowsHost implements RowsAccessibility.Host<Integer> {
            @Override
            public RowsAccessibility.Selection selection() {
                return selectionMode == SelectionMode.MULTI ? RowsAccessibility.Selection.MULTI
                        : RowsAccessibility.Selection.SINGLE;
            }

            @Override
            public boolean cursorIsTheSelection() {
                return selectionMode != SelectionMode.MULTI;
            }

            @Override
            public boolean rowsActivate() {
                return true;
            }

            @Override
            public boolean isSelected(Integer index) {
                if (selectionMode == SelectionMode.MULTI) {
                    return index >= 0 && index < rows.length && rows[index] >= 0
                            && chosen.get(rows[index]);
                }
                return index == highlightedRow;
            }

            @Override
            public boolean select(Integer index, boolean moveCursor) {
                if (selectionMode == SelectionMode.MULTI) {
                    // A client's SELECT makes the option the whole selection, as it does in any
                    // list of many; the list stays down.
                    java.util.BitSet only = new java.util.BitSet();
                    only.set(rows[index]);
                    choose(only, Change.Origin.USER);
                    return true;
                }
                setHighlight(index); // the arrows' path: the list's selection, not the value
                return true;
            }

            @Override
            public void toggleSelection(Integer index, boolean moveCursor) {
                toggle(rows[index], Change.Origin.USER);
                if (moveCursor) {
                    setHighlight(index);
                }
            }

            @Override
            public void moveCursor(Integer index) {
                setHighlight(index); // the arrows' path: announced, damaged, revealed
            }

            @Override
            public void reveal(Integer index) {
                // Not published on an option, so never asked for; the highlight reveals itself.
            }

            @Override
            public void activate(Integer index) {
                commit(index);
            }
        }

        private final RowsHost rowsHost = new RowsHost();
    }
}
