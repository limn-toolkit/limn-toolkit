/**
 * The system clipboard beyond GLFW's text: one {@link limn.backend.lwjgl.transfer.NativeClipboard}
 * per windowing system, which speaks in native types and bytes, under one
 * {@link limn.backend.lwjgl.transfer.PlatformClipboard}, which turns a
 * {@code limn.transfer.Transfer} into those and back. Internal to the backend.
 */
@NullMarked
package limn.backend.lwjgl.transfer;

import org.jspecify.annotations.NullMarked;
