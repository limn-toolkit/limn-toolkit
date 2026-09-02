rootProject.name = "limn-toolkit"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// The module graph is the guard, and checkArchitecture cannot catch a mistake in it: that task is
// a grep over import lines, so it sees a forbidden PACKAGE and never a forbidden DEPENDENCY.
// These edges therefore have to stay absent by hand, and they are here rather than anywhere else:
//
//   limn-toolkit MUST NOT depend on limn-video-ffmpeg. This is what is left of ADR 008 after the
//   widget set and the pure-Java decoders moved into the toolkit, and it is the half that was
//   load-bearing: the decoder with a native payload, a licence and a platform matrix stays in a
//   module of its own, so an application that plays no MP4 carries no FFmpeg. The two decoders
//   that came in are pure Java with no native and no third-party dependency, which is why they
//   could come in at all.
//
//   limn-icons-tabler is no longer a module here at all (ADR 038): it is an artifact of its own,
//   versioned with Tabler, compiled against the published toolkit as compileOnly. The rule it
//   used to state still holds and is now structural: an icon pack is an application's choice of
//   vocabulary, not the toolkit's, so nothing in this build may depend on it except limn-demo —
//   which is an application. The widget set ships the handful of glyphs its own controls draw.
//
//   limn-theme-editor MUST NOT be depended on by limn-toolkit or the backend. It is a tool for
//   authoring a palette, not a part of wearing one: an application ships a Theme, and the screen
//   that built that Theme has no business on the classpath of every application that draws a
//   button. The dependency points the only way it can: the editor reads limn-toolkit's Theme, and
//   nothing reads the editor.
include(
    "limn-toolkit",
    "limn-theme-editor",
    "limn-video-ffmpeg",
    "limn-video-ffmpeg-natives-all",
    "limn-fonts-all",
    "limn-backend-lwjgl",
    "limn-demo",
)
