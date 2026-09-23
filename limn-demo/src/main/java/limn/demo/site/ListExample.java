package limn.demo.site;

import limn.components.Label;
import limn.components.ListView;
import limn.components.SelectionMode;
import limn.scene.Change;
import limn.scene.Insets;
import limn.scene.layout.Padding;

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
}
