/**
 * Reading bytes into the toolkit: {@link limn.io.Resources} is the one way a loader takes a
 * classpath resource or a file whole, so that "the jar was built without it" and "the disk
 * refused" are reported the same way by every loader that meets them. Nothing here decodes
 * anything; the image, audio, font and model loaders that call it live with their formats.
 */
package limn.io;
