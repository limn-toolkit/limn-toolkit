rootProject.name = "limn-ui"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// The module graph is the guard, and checkArchitecture cannot catch a mistake in it: that task is
// a grep over import lines, so it sees a forbidden PACKAGE and never a forbidden DEPENDENCY.
// Two edges therefore have to stay absent by hand, and both are here rather than anywhere else:
//
//   limn-components MUST NOT depend on limn-video or limn-video-ffmpeg. A widget set that pulled
//   in a decoder would put a codec (and, now, a native payload) on the classpath of every
//   application that draws a button.
//
//   limn-video MUST NOT depend on limn-video-ffmpeg. ADR 008 put the module boundary exactly here
//   so that a codec dependency has somewhere to land that is not a base module; limn-video is
//   pure Java with no native and no third-party dependency, and that sentence stays true because
//   the FFmpeg decoder went into a module of its own rather than into it.
//   limn-icons-tabler MUST NOT be depended on by limn-toolkit, limn-components or the
//   backend. An icon pack is an application's choice of vocabulary, not the toolkit's: the
//   widget set ships the handful of glyphs its own controls draw and nothing more, so an
//   application that wants none of a pack pays for none of it. The same rule is what leaves
//   room for a second pack beside this one.
//
//   limn-theme-editor MUST NOT be depended on by limn-toolkit, limn-components or the
//   backend. It is a tool for authoring a palette, not a part of wearing one: an
//   application ships a Theme, and the screen that built that Theme has no business on the
//   classpath of every application that draws a button. The dependency points the only way
//   it can: the editor reads limn-components' Theme, and nothing reads the editor.
include(
    "limn-toolkit",
    "limn-components",
    "limn-icons-tabler",
    "limn-theme-editor",
    "limn-video",
    "limn-video-ffmpeg",
    "limn-backend-lwjgl",
    "limn-demo",
)
