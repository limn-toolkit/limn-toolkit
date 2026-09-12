/**
 * The tree: an outline over children the application provides.
 *
 * <p>{@link limn.components.tree.Tree} is one column of rows at a depth — an indent, a disclosure
 * triangle where a row can open, and the application's own cell widget. Rows are realized only
 * where the viewport reaches, as {@code ListView}'s and {@code Table}'s are, and the order they
 * are walked in is a traversal of what is expanded rather than a list.
 *
 * <p>The application keeps its nodes. A tree is given roots and a model that answers what a
 * node's children are, whether it can have any at all, and — when they are not known yet — a
 * {@link limn.concurrent.Work} that fetches them, so a directory or a remote catalogue can open a
 * row before it can name what is inside it.
 *
 * <p>Columns are not here: a {@code TreeTable} is ADR 044 §9, and the day it arrives is the day
 * the anchor walk this package copies from {@code ListView} and {@code Table} collapses into one
 * engine the three of them share (§3).
 */
package limn.components.tree;
