package limn.components;

import limn.backend.NativeWindow;
import limn.backend.spi.MenuBarHost;
import limn.backend.spi.MenuBarReading;
import limn.backend.spi.MenuRow;
import limn.backend.spi.SystemMenuBar;
import limn.input.Keys;
import limn.scene.EditCommand;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A menu bar where the system keeps the bar outside the window: what it hands over and what is left of it in the scene. */
class SystemMenuBarTest extends ComponentTestBase {

    /** A system's bar that takes the first bar of a window and can be told to let go. */
    private static final class FakeSystemBar implements SystemMenuBar {
        final List<MenuBarHost> hosts = new ArrayList<>();
        NativeWindow window;
        boolean holding = true;
        int changes;

        @Override
        public boolean attach(NativeWindow from, MenuBarHost host) {
            if (!hosts.isEmpty() && window == from) {
                return false;
            }
            window = from;
            hosts.add(host);
            return true;
        }

        @Override
        public void detach(NativeWindow from, MenuBarHost host) {
            hosts.remove(host);
        }

        @Override
        public boolean holds(NativeWindow from, MenuBarHost host) {
            return holding && hosts.contains(host);
        }

        @Override
        public void changed(MenuBarHost host) {
            changes++;
        }

        @Override
        public boolean runs(String key, int modifiers) {
            return (modifiers & (Keys.MOD_SUPER | Keys.MOD_CONTROL)) != 0;
        }
    }

    private final List<String> ran = new ArrayList<>();

    private MenuItem item(String label) {
        return MenuItem.of(label, () -> ran.add(label));
    }

    private static List<String> labels(List<MenuRow> rows) {
        return rows.stream().map(row -> row.kind() == MenuRow.Kind.SEPARATOR ? "-" : row.label()).toList();
    }

    private static SystemBarReader read(Menu file, Menu edit, Menu help) {
        return SystemBarReader.read(List.of(new SystemBarReader.Source("File", file),
                new SystemBarReader.Source("Edit", edit), new SystemBarReader.Source("Help", help)), null, help, false);
    }

    @Test
    void theThreeItemsTheSystemHasAPlaceForLeaveTheirMenus() {
        Menu file = new Menu().add(item("New")).addSeparator()
                .add(item("Exit").setRole(MenuItem.Role.QUIT));
        Menu edit = new Menu().add(MenuItem.of(MenuItem.Role.COPY)).addSeparator()
                .add(item("Preferences").setRole(MenuItem.Role.SETTINGS));
        Menu help = new Menu().add(item("About").setRole(MenuItem.Role.ABOUT));
        SystemBarReader reader = read(file, edit, help);
        MenuBarReading reading = reader.reading();

        assertNotNull(reading.about());
        assertNotNull(reading.settings());
        assertEquals(3, reading.menus().size(), "the Help menu stays, emptied, for the system's search");
        assertEquals(List.of("New"), labels(reading.menus().get(0).rows()), "no separator left at the end");
        assertEquals(List.of(ComponentStrings.TEXT_MENU_COPY.get()), labels(reading.menus().get(1).rows()));
        assertEquals(MenuBarReading.Kind.HELP, reading.menus().get(2).kind());
        assertTrue(reading.menus().get(2).rows().isEmpty());
        assertEquals(EditCommand.COPY, reading.menus().get(1).rows().get(0).edit());

        assertEquals(3, file.items().size(), "the menus are read, never changed");
        assertEquals(3, edit.items().size());

        reader.choose(reading.about().id());
        reader.choose(reading.settings().id());
        assertEquals(List.of("About", "Preferences"), ran);
        assertFalse(reader.roleGivenTwice());
    }

    @Test
    void aMenuEmptiedByTheSystemIsNotShownUnlessItIsTheHelpMenu() {
        Menu app = new Menu().add(item("About").setRole(MenuItem.Role.ABOUT));
        SystemBarReader reader = SystemBarReader.read(
                List.of(new SystemBarReader.Source("App", app)), null, null, false);
        assertTrue(reader.reading().menus().isEmpty());
    }

    @Test
    void aRoleGivenTwiceIsTheFirstItems() {
        Menu help = new Menu().add(item("About").setRole(MenuItem.Role.ABOUT))
                .add(item("About again").setRole(MenuItem.Role.ABOUT));
        SystemBarReader reader = SystemBarReader.read(
                List.of(new SystemBarReader.Source("Help", help)), null, null, false);
        assertTrue(reader.roleGivenTwice());
        assertEquals(List.of("About again"), labels(reader.reading().menus().get(0).rows()));
    }

    @Test
    void aSubmenuKeepsItsPlaceAndLosesWhatTheSystemTook() {
        Menu more = new Menu().add(item("Preferences").setRole(MenuItem.Role.SETTINGS));
        Menu other = new Menu().add(item("Inner"));
        Menu edit = new Menu().add(item("Find")).addSubmenu("More", more).addSubmenu("Other", other);
        SystemBarReader reader = SystemBarReader.read(
                List.of(new SystemBarReader.Source("Edit", edit)), null, null, false);
        assertEquals(List.of("Find", "Other"), labels(reader.reading().menus().get(0).rows()));
        assertNotNull(reader.reading().settings());
    }

    @Test
    void anOrdinaryItemOnAnEditingChordIsNoticed() {
        Menu edit = new Menu().add(item("Select None").setAccelerator(Accelerator.command(Keys.A)));
        assertTrue(SystemBarReader.read(List.of(new SystemBarReader.Source("Edit", edit)), null, null, false)
                .editingChordTaken());
        Menu roles = new Menu().add(MenuItem.of(MenuItem.Role.SELECT_ALL));
        assertFalse(SystemBarReader.read(List.of(new SystemBarReader.Source("Edit", roles)), null, null, false)
                .editingChordTaken());
    }

    @Test
    void aBarTheSystemHoldsIsNotInTheScene() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        FakeSystemBar system = new FakeSystemBar();
        backend.setSystemMenuBar(system);
        HeadlessWindow window = backend.open("Bars", 400, 300);

        MenuBar bar = new MenuBar().addMenu("File", new Menu().add(item("New")));
        Button below = new Button("Below");
        Column page = new Column();
        page.add(bar);
        page.add(below);
        Scene scene = new Scene(page);
        scene.setTextRuler(RULER);
        assertFalse(bar.isInSystemBar(), "a scene with no window has no system to ask");
        scene.bind(window);
        window.frame();

        assertTrue(bar.isInSystemBar());
        assertFalse(bar.isVisible(), "laid out as a widget that is not there");
        assertEquals(0f, below.y(), 1e-3f, "what was under the bar is at the top");
        assertEquals(1, system.hosts.size());

        MenuBarReading reading = system.hosts.get(0).read();
        assertEquals("File", reading.menus().get(0).title());
        system.hosts.get(0).choose(reading.menus().get(0).rows().get(0).id());
        assertEquals(List.of("New"), ran);

        bar.setPlacement(MenuBar.Placement.IN_WINDOW);
        window.frame();
        assertFalse(bar.isInSystemBar());
        assertTrue(bar.isVisible());
        assertTrue(below.y() > 0, "the bar is back above it");
        assertTrue(system.hosts.isEmpty());

        bar.setPlacement(MenuBar.Placement.SYSTEM);
        assertTrue(bar.isInSystemBar());
        MenuBarHost host = system.hosts.get(0);
        system.holding = false; // a fullscreen that covers the system's bar
        host.heldChanged();
        assertFalse(bar.isInSystemBar());
        assertTrue(bar.isVisible());
        assertSame(host, system.hosts.get(0), "still the system's to take back");

        system.holding = true;
        host.heldChanged();
        page.remove(bar);
        assertTrue(system.hosts.isEmpty(), "a bar that left its scene left the system's bar");
        assertFalse(bar.isInSystemBar());
    }

    @Test
    void aSecondBarOfTheWindowStaysInIt() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        FakeSystemBar system = new FakeSystemBar();
        backend.setSystemMenuBar(system);
        HeadlessWindow window = backend.open("Bars", 400, 300);
        MenuBar first = new MenuBar().addMenu("File", new Menu().add(item("New")));
        MenuBar second = new MenuBar().addMenu("Tools", new Menu().add(item("Run")));
        Column page = new Column();
        page.add(first);
        page.add(second);
        Scene scene = new Scene(page);
        scene.setTextRuler(RULER);
        scene.bind(window);
        assertTrue(first.isInSystemBar());
        assertFalse(second.isInSystemBar());
        assertTrue(second.isVisible());
    }

    @Test
    void aHiddenBarIsNotInTheSystemsBarAndWithNoSystemBarNothingChanges() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        HeadlessWindow plain = backend.open("Plain", 400, 300);
        MenuBar bar = new MenuBar().addMenu("File", new Menu().add(item("New")));
        Scene scene = new Scene(bar);
        scene.setTextRuler(RULER);
        scene.bind(plain);
        assertFalse(bar.isInSystemBar(), "no system bar: the bar is in the window");
        assertTrue(bar.isVisible());
        assertNull(backend.systemMenuBar());

        HeadlessBackend other = new HeadlessBackend(runtime);
        FakeSystemBar system = new FakeSystemBar();
        other.setSystemMenuBar(system);
        MenuBar hidden = new MenuBar().addMenu("File", new Menu().add(item("New")));
        hidden.setVisible(false);
        Scene hiddenScene = new Scene(hidden);
        hiddenScene.setTextRuler(RULER);
        hiddenScene.bind(other.open("Hidden", 400, 300));
        assertFalse(hidden.isInSystemBar());
        hidden.setVisible(true);
        assertTrue(hidden.isInSystemBar());
        hidden.setVisible(false);
        assertFalse(hidden.isInSystemBar());
    }

    @Test
    void theSystemIsToldWhenTheTitlesChange() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        FakeSystemBar system = new FakeSystemBar();
        backend.setSystemMenuBar(system);
        MenuBar bar = new MenuBar().addMenu("File", new Menu().add(item("New")));
        Scene scene = new Scene(bar);
        scene.setTextRuler(RULER);
        scene.bind(backend.open("Titles", 400, 300));
        int before = system.changes;
        Menu help = new Menu().add(item("Guide"));
        bar.addMenu("Help", help);
        bar.setHelpMenu(help);
        assertTrue(system.changes >= before + 2);
        assertEquals(MenuBarReading.Kind.HELP, system.hosts.get(0).read().menus().get(1).kind());
    }
}
