/**
 * The widget set, layout, the scene graph, the backend SPIs and the pure-Java video decoders.
 * It depends on nothing outside the JDK's base module.
 *
 * <p>Every package is exported except those named {@code internal}, which are shared with the
 * other Limn modules and exported to them alone. On the class path the boundary is a naming
 * convention; on the module path the compiler and the runtime enforce it.
 */
// The qualified exports name the modules that share the internal packages, which this module
// does not depend on; "module" is the warning that says a named module is not on this build's path.
@SuppressWarnings("module")
module limn.toolkit {
    exports limn.accessibility;
    exports limn.animation;
    exports limn.backend;
    exports limn.components;
    exports limn.components.chart;
    exports limn.components.date;
    exports limn.components.table;
    exports limn.components.tree;
    exports limn.concurrent;
    exports limn.graphics;
    exports limn.i18n;
    exports limn.input;
    exports limn.io;
    exports limn.math;
    exports limn.render3d;
    exports limn.render3d.gltf;
    exports limn.render3d.scene;
    exports limn.render3d.shader;
    exports limn.scene;
    exports limn.scene.event;
    exports limn.scene.layout;
    exports limn.sound;
    exports limn.video;
    exports limn.video.decode;

    exports limn.internal.lang to limn.backend.lwjgl, limn.video.ffmpeg, limn.themeeditor;
    exports limn.concurrent.internal to limn.backend.lwjgl;
    exports limn.io.internal to limn.backend.lwjgl;
    exports limn.scene.internal to limn.test;
}
