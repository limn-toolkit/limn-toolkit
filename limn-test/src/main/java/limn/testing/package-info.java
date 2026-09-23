/**
 * Testing a Limn UI with no display.
 *
 * <p>{@link limn.testing.HeadlessUi} installs the UI runtime on the test's own thread, and
 * {@link limn.testing.SceneDriver} drives a scene the way a window would: a click, typed text, a key,
 * a scroll, or any raw window input, dispatched when its batch ends. {@link limn.testing.TestRulers}
 * measures text without fonts, so a layout comes out the same on every machine, and
 * {@link limn.testing.HeadlessBackend} gives a dialog, a menu or a popup a window to open in.
 * {@link limn.testing.AccessibleHarness} binds a widget to a scene whose accessibility bridge
 * records every tree it is handed, and {@link limn.testing.AccessibleInvariants} holds a tree to
 * the rules every published tree keeps.
 *
 * <p>Nothing here depends on a test framework: a contract case throws {@link AssertionError} and
 * {@code AccessibleInvariants} returns its violations as text, and any framework can report
 * either.
 */
package limn.testing;
