/**
 * Testing a Limn UI with no display: a driver for a scene's input, a headless runtime and backend,
 * the doubles a scene is bound to, and the accessibility contracts the toolkit's widgets are held
 * to. No test framework is required.
 */
module limn.test {
    requires transitive limn.toolkit;
    // AllocationProbe reads the allocated bytes of the current thread.
    requires jdk.management;

    exports limn.testing;
    exports limn.testing.a11y;
}
