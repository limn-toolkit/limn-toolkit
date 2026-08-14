/**
 * Layout containers: {@link limn.scene.layout.Row} and {@link limn.scene.layout.Column}
 * over the shared simplified flexbox {@link limn.scene.layout.Flex}, with
 * {@link limn.scene.layout.Expanded} taking a weighted share of the leftover main-axis
 * space; {@link limn.scene.layout.Stack} overlays children,
 * {@link limn.scene.layout.Padding} wraps one child in edge insets, and
 * {@link limn.scene.layout.SizedBox} fixes a dimension or spaces rigidly. All of them are
 * plain widgets: a container an application writes participates in measure and layout on
 * the same terms.
 */
package limn.scene.layout;
