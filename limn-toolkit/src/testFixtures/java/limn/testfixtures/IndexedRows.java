package limn.testfixtures;

import limn.components.ListView;
import limn.i18n.I18nString;
import limn.scene.Widget;

import java.util.AbstractList;
import java.util.List;

/**
 * Rows a test describes by index, as the old untyped adapter did, handed to the typed
 * {@link ListView} as a {@code ListView<Integer>} over a live list of indices: the count is read
 * each time the list asks, so a test that changes it and calls {@code refresh()} tests what an
 * application changing its list in place does. Not API: the repository's tests, and nothing else.
 */
public interface IndexedRows {

    /** @return how many rows there are now */
    int rowCount();

    /**
     * @param index a row
     * @return its widget, populated
     */
    Widget<?> rowAt(int index);

    /**
     * A row widget that scrolled out.
     *
     * @param widget the widget
     */
    default void recycle(Widget<?> widget) {
    }

    /**
     * @param index a row
     * @return its name for an assistive technology, or {@code null}
     */
    default I18nString rowName(int index) {
        return null;
    }

    /**
     * @param rows the rows
     * @return a list over them, with its items set
     */
    static ListView<Integer> list(IndexedRows rows) {
        ListView<Integer> list = new ListView<>(rows::rowAt, rows::recycle);
        list.setItems(indices(rows));
        list.setItemName(rows::rowName);
        return list;
    }

    /**
     * @param rows the rows
     * @return the indices {@code 0 .. rowCount()-1}, re-read on every call
     */
    static List<Integer> indices(IndexedRows rows) {
        return new AbstractList<>() {
            @Override
            public Integer get(int index) {
                return index;
            }

            @Override
            public int size() {
                return rows.rowCount();
            }
        };
    }
}
