/**
 * What a widget of each accessibility shape owes a screen reader, as named cases a test runs.
 *
 * <p>Each shape has a contract and a subject: {@link limn.testing.a11y.ToggleContract} over a
 * {@link limn.testing.a11y.ToggleSubject}, {@link limn.testing.a11y.ValueContract} over a
 * {@link limn.testing.a11y.ValueSubject}, and the same for text, rows, grids, menus, a leaf action
 * and a popup owner. The subject builds the widget fresh for every case and answers through the
 * widget's API what the published tree cannot say; the contract's {@code cases} returns
 * {@link limn.testing.a11y.ContractCase}s, each a name and a check, which a test turns into its
 * framework's dynamic tests in one line. The toolkit's own components are held to these same
 * contracts.
 */
package limn.testing.a11y;
