package limn.components;

import limn.concurrent.Ui;
import limn.graphics.SvgIcon;
import limn.input.Keys;
import limn.scene.event.KeyEvent;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A {@link TextField} preset for search: a leading magnifier icon inside the
 * field and a trailing coupled clear button (the ComboBox-caret idiom). Enter
 * fires {@link #onSubmit}; the clear button empties the field.
 */
public class SearchField extends TextField {

    private Consumer<String> onSubmit = query -> {
    };

    /** A field with a search icon, a clear button and a localized placeholder. */
    public SearchField() {
        setPlaceholder(ComponentStrings.SEARCH_PLACEHOLDER);
        setLeadingIcon(SvgIcon.fromResource("/limn/components/icons/search.svg"));
        setTrailingButton(SvgIcon.fromResource("/limn/components/icons/close.svg"), this::clear);
    }

    /** Fires with the current query when Enter is pressed. */
    public SearchField onSubmit(Consumer<String> listener) {
        Ui.checkUiThread();
        this.onSubmit = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** Empties the field (and notifies onChange), as the trailing button does. */
    public void clear() {
        if (!text().isEmpty()) {
            setText("");
            fireChange();
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (event.isPressed() && !event.isRepeat() && event.key() == Keys.ENTER) {
            onSubmit.accept(text());
            event.consume();
            return;
        }
        super.onKeyEvent(event);
    }
}
