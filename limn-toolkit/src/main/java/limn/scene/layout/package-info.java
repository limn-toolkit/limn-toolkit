/**
 * Layout containers: {@link limn.scene.layout.Row} and {@link limn.scene.layout.Column}
 * over the shared simplified flexbox {@link limn.scene.layout.Flex}, with
 * {@link limn.scene.layout.Expanded} taking a weighted share of the leftover main-axis
 * space; {@link limn.scene.layout.Wrap} places children along a line and starts a new
 * line below when the next one does not fit; {@link limn.scene.layout.Grid} places them in
 * a fixed number of columns, row by row, so that they line up on both axes;
 * {@link limn.scene.layout.Stack} overlays children, {@link limn.scene.layout.Padding} wraps
 * one child in edge insets, {@link limn.scene.layout.SizedBox} fixes a dimension or
 * spaces rigidly, {@link limn.scene.layout.Opacity} draws one child as one picture at an
 * opacity and fades it, and {@link limn.scene.layout.Transform} draws one child moved, scaled or
 * flipped without moving anything layout placed. All of them are plain widgets: a container an
 * application writes
 * participates in measure and layout on the same terms.
 */
@NullMarked
package limn.scene.layout;

import org.jspecify.annotations.NullMarked;
