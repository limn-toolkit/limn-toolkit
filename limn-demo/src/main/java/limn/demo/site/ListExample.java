package limn.demo.site;

import limn.components.Button;
import limn.components.Label;
import limn.components.ListView;
import limn.components.SelectionMode;
import limn.scene.Change;
import limn.scene.Insets;
import limn.scene.layout.Expanded;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;

import java.util.List;
import java.util.function.Consumer;

/**
 * The worked example the lists guide shows for a {@link ListView}: the items are the
 * application's list, a row's widget comes from a function of its item, and the detail watches
 * the selection. Compiled by {@code ./gradlew check}, so the sample a reader copies builds.
 */
public final class ListExample {

    private ListExample() {
    }

    /** A record of the application's own. */
    record Person(String name, String email) {
    }

    // #region guide:list
    static ListView<Person> people(List<Person> people, Label detail, Consumer<Person> open) {
        ListView<Person> list = new ListView<>(
                person -> new Padding(Insets.symmetric(9, 14), new Label(person.name())));
        list.setItems(people);
        list.observeChanges((widget, change) -> {
            if (change.aspect() == Change.Aspect.SELECTION) {
                Person chosen = list.selectedItem();
                detail.setText(chosen == null ? "" : chosen.email());
            }
        });
        list.onActivate(index -> open.accept(people.get(index)));
        return list;
    }
    // #endregion

    // #region guide:list-pooled
    /** A row widget made once and filled with an item each time it comes into view. */
    static final class PersonRow extends Padding {
        private final Label name;

        PersonRow() {
            this(new Label(""));
        }

        private PersonRow(Label name) {
            super(Insets.symmetric(9, 14), name);
            this.name = name;
        }

        void show(Person person) {
            name.setText(person.name());
        }
    }

    static ListView<Person> manyPeople(List<Person> people) {
        ListView<Person> list = ListView.pooled(PersonRow::new, PersonRow::show);
        list.setItems(people);
        list.setSelectionMode(SelectionMode.MULTI);
        return list;
    }
    // #endregion

    // #region guide:list-row-button
    /**
     * A pooled row with a button in it. The row keeps the item it is showing, and the button,
     * given its action once when the row is made, reads it when pressed: a widget shows another
     * item after it is recycled, and nothing has to be registered again.
     */
    static final class RemovableRow extends Padding {
        private final Label name;
        private Person person;

        RemovableRow(Consumer<Person> remove) {
            this(new Label(""), new Button("Remove"), remove);
        }

        private RemovableRow(Label name, Button button, Consumer<Person> remove) {
            super(Insets.symmetric(6, 14), row(name, button));
            this.name = name;
            button.onAction(() -> remove.accept(person));
        }

        private static Row row(Label name, Button button) {
            Row row = new Row().gap(8);
            row.add(Expanded.of(name));
            row.add(button);
            return row;
        }

        void show(Person person) {
            this.person = person;
            name.setText(person.name());
        }
    }

    /** The screen the list lives on: it owns the items, so it removes one and refreshes. */
    static final class People {
        private final List<Person> people;
        private final ListView<Person> list;

        People(List<Person> people) {
            this.people = people;
            this.list = ListView.pooled(() -> new RemovableRow(this::remove), RemovableRow::show);
            list.setItems(people);
        }

        private void remove(Person person) {
            people.remove(person);
            list.refresh();
        }
    }
    // #endregion

    /** @return the example above, for a check that it builds and runs */
    static ListView<Person> removable(List<Person> people) {
        return new People(people).list;
    }
}
