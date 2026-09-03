import org.gradle.kotlin.dsl.support.serviceOf
import java.util.zip.ZipFile

// limn-demo: the "kitchen sink" application and --screenshot mode for visual verification.
//
// Published, and not as a library: nothing should depend on it, and its POM is shaped for the
// one reader it has — a launcher resolving it to RUN it (see the publishing note at the end).

plugins {
    application
}

dependencies {
    implementation(project(":limn-toolkit"))
    implementation(project(":limn-backend-lwjgl"))
    // The APPLICATION ships the icon pack, not the backend: limn-backend-lwjgl must never see
    // it, or which icons exist would stop being the application's decision. Since ADR 038 the
    // pack is an artifact of its own, versioned with Tabler, and this is the one line in this
    // repository that names it — the shape any application would have.
    implementation(libs.limn.icons.tabler)
    // Same shape as the icon pack: a module nothing in the toolkit depends on, taken here
    // because the demo is an application and this one is a screen an application embeds.
    implementation(project(":limn-theme-editor"))
    // The decoder over a native payload, and the demo is the only thing in this build that takes
    // it. The payload itself is the limn-ffmpeg-natives artifact (ADR 037): the shim arrives
    // with this module, and the libraries are named below.
    implementation(project(":limn-video-ffmpeg"))
    // The opt-in faces, taken here for the same reason the icon pack is: the demo is an
    // APPLICATION, and this is an application's choice (ADR 036). Roboto and the complex-script
    // faces need no line — they come with the backend — but the i18n scenes draw CJK and emoji,
    // and the fatJar is the screenshot rig for every README, so the kitchen sink opts into the
    // whole kitchen.
    runtimeOnly(libs.limn.fonts.noto.cjk)
    runtimeOnly(libs.limn.fonts.noto.emoji)
    // The FFmpeg libraries for every desktop target (ADR 037). limn-video-ffmpeg brings the shim;
    // the libraries are the application's choice, and this application's fatJar is one file that
    // has to play video on whichever desktop `jbang <url>` lands it on — so all six, the same
    // thing limn-video-ffmpeg-natives-all would name, written out because a project cannot depend
    // on a sibling POM's dependency list without publishing it first. The published POM does not
    // say all six: each moves into the <profile> of its platform there, so a launcher resolving
    // the coordinate fetches the one the machine can load.
    val payload = libs.limn.ffmpeg.natives.get()
    listOf("linux-aarch64", "linux-x86_64", "macos-aarch64", "macos-x86_64",
            "windows-aarch64", "windows-x86_64").forEach { platform ->
        runtimeOnly("${payload.module}:${payload.versionConstraint.requiredVersion}:natives-$platform")
    }

    // The gallery capture's writer is the one piece of this module with a contract worth a test
    // that needs no window: it is what stands between a queued capture and a manifest that
    // promises it.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// A developer's `full` payload (encoders, for the writer scenes), from a sibling clone of
// limn-ffmpeg-natives by convention or from -PlimnFfmpegNatives, ahead of the published player
// payload on this module's classpath: main resources come before dependency jars, so the full
// build's manifests and libraries shadow the player's at the same paths. This module is never
// published, and the fatJar the release attaches is built on a runner that has no sibling clone.
//
// "Beside this repository" means beside the MAIN checkout, which is not always rootDir: a linked
// git worktree (the kind an agent or a `git worktree add` under .claude/worktrees/ makes) has a
// rootDir of its own, whose parent holds other worktrees and no sibling clone at all, so from
// there the writer scenes quietly got the player payload and nothing said why. A worktree is
// recognisable without asking git: its .git is a FILE, not a directory, holding
// `gitdir: <main>/.git/worktrees/<name>`, and the main checkout is three levels above that.
val mainRepositoryDir: File = run {
    val dotGit = rootDir.resolve(".git")
    if (!dotGit.isFile) {
        return@run rootDir
    }
    val gitDir = rootDir.resolve(dotGit.readText().trim().removePrefix("gitdir:").trim()).normalize()
    val commonDir = gitDir.parentFile?.parentFile
    if (gitDir.parentFile?.name == "worktrees" && commonDir?.name == ".git") {
        commonDir.parentFile
    } else {
        rootDir
    }
}
val devNatives: File = (findProperty("limnFfmpegNatives") as String?)?.let { file(it) }
    ?: mainRepositoryDir.resolve("../limn-ffmpeg-natives/native/dist/full").normalize()

/**
 * Says, at the start of a run, which payload the writer scenes are about to get. The scenes
 * themselves only know that an encoder is missing; the reason (no sibling clone, or a worktree
 * that could not see it) is only visible here. Takes the directory as a value rather than
 * reading the script-level one inside the action, because the configuration cache serialises
 * the action and refuses a reference to the script.
 */
fun JavaExec.reportFfmpegPayload(payload: File) {
    doFirst {
        if (payload.isDirectory) {
            logger.lifecycle("limn-demo: the 'full' FFmpeg payload at $payload shadows the published one")
        } else {
            logger.lifecycle("limn-demo: the published 'player' FFmpeg payload; the writer scenes " +
                    "have no encoder (a 'full' build from limn-ffmpeg-natives beside the main " +
                    "repository, or -PlimnFfmpegNatives=<dir>, gives them one)")
        }
    }
}
sourceSets {
    named("main") {
        resources.srcDir(devNatives)
    }
}

val isMacOs = System.getProperty("os.name").lowercase().contains("mac")

// Both site tasks are deliberately NOT wired into `check` or into any assemble path: the
// site is a consumer of this repository, not a module of it, and `./gradlew check` has to
// stay exactly what it was.
//
// Renders every gallery entry in both palettes and writes the manifest the site's
// component pages are built from. Needs a GL context: on a headless Linux machine run it
// under `xvfb-run -a` with LIBGL_ALWAYS_SOFTWARE=1, which is what the workflow does.
tasks.register<JavaExec>("captureGallery") {
    group = "documentation"
    description = "Renders the component gallery captures to site/captures/ with a manifest."
    mainClass.set("limn.demo.site.Gallery")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
    // The writer scenes are captured too, so the capture says which payload it filmed them with.
    reportFfmpegPayload(devNatives)
    // The pristine captures, deliberately outside site/public/: `pnpm build:gallery` reads
    // them and writes only derivatives into public/gallery/. Pointing this back at
    // site/public/ would make the derivation's output its own input on the next run, and
    // every crop would run twice.
    args("site/captures")
    // macOS-only, exactly as the `run` task does it: GLFW/Cocoa need the event loop on the
    // process's first thread, and a JVM that does not know the flag refuses to start.
    if (isMacOs) {
        jvmArgs("-XstartOnFirstThread")
    }
    if (JavaVersion.current().majorVersion.toInt() >= 24) {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

// The site's colours ARE this toolkit's palettes, so it reads them from the palettes rather
// than from a transcription. Needs no window and no GL context.
tasks.register<JavaExec>("exportThemeTokens") {
    group = "documentation"
    description = "Writes the Limn/Limn Light palettes to site/src/styles/tokens.generated.css."
    mainClass.set("limn.demo.site.ThemeTokens")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
    args("site/src/styles/tokens.generated.css")
}

application {
    mainClass.set("limn.demo.Main")
    // Do not set -XstartOnFirstThread in applicationDefaultJvmArgs: the value would
    // be baked statically into the dist start scripts of ALL platforms
    // (the flag is macOS-only and aborts the JVM on Windows/Linux).
}

tasks.named<JavaExec>("run") {
    reportFfmpegPayload(devNatives)
    // Relative --screenshot paths resolve from the repository root.
    workingDir = rootDir
    // The video tab's caption tells the reader to set this, and a JavaExec starts a fresh JVM that
    // inherits nothing, so without this line the documented instruction silently does nothing and
    // the generated clip plays instead, which looks like the property being ignored on purpose.
    System.getProperty("limn.demo.video")?.let { systemProperty("limn.demo.video", it) }
    // GLFW/Cocoa require the event loop to run on the first thread of the process.
    if (isMacOs) {
        jvmArgs("-XstartOnFirstThread")
    }
    // JDK 24 restricted System.loadLibrary (JEP 472): today it prints four lines of warning on
    // every run, and a future release will refuse the call outright. Added only where the JVM
    // knows the option: passing it to a JDK that does not is not a warning, it is a JVM that
    // will not start, which is the same trap -XstartOnFirstThread carries on Windows and Linux.
    // Applications that ship this decoder need the same flag; see FfmpegLibrary's documentation.
    if (JavaVersion.current().majorVersion.toInt() >= 24) {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

// ------------------------------------------------------------------ the jar a stranger can run
//
// One file, everything inside it, and a Main-Class: `jbang <url>` and the kitchen sink is on
// screen, with nothing cloned and nothing installed. It exists because this module is
// deliberately NOT published to Maven Central — it is a showcase, not a library, and publishing
// it would invite an application to depend on it — and a showcase nobody can start is not one.
// A jar attached to a GitHub release is a thing somebody runs and not a thing a build file can
// declare, which is the distinction that keeps both properties.
//
// FAT rather than thin, and that is jbang's constraint rather than a preference: a jar it fetches
// by URL arrives alone, with no POM read and no dependency resolved. Whatever is not in here is
// not on the classpath.
//
// The file name carries no version. GitHub serves the newest release's asset by name at
// /releases/latest/download/<name>, so the URL in ten READMEs never has to be bumped; which
// version somebody is holding is in the manifest.
tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Everything the demo needs in one runnable jar, for the GitHub release."
    archiveFileName.set("limn-demo-all.jar")
    manifest {
        attributes(
            "Main-Class" to "limn.demo.Main",
            "Implementation-Title" to "Limn kitchen sink",
            "Implementation-Version" to project.version,
        )
    }
    // Dependency jars collide on their metadata and agree on nothing else. First one wins;
    // nothing here reads any of it at run time.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // A signature does not survive its jar being merged into another one, and a JVM that finds
    // one it cannot verify rejects the whole archive rather than the entry.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    // A dozen module descriptors in one classpath jar describe nothing that exists.
    exclude("module-info.class", "META-INF/versions/*/module-info.class")

    // The licences, FIRST, because first wins above: a dependency that ships a META-INF/LICENSE of
    // its own would otherwise be the one this jar answers with. A jar handed to a stranger by
    // URL is a distribution, and every text a distribution owes travels inside it: this
    // repository's own terms, and the two the merged dependencies do not carry themselves —
    // LWJGL's jars have no licence file, and JLayer's (LGPL) has none either. Both texts are the
    // upstream projects' own, verbatim.
    from(rootProject.files("LICENSE", "NOTICE")) {
        into("META-INF")
    }
    from(layout.projectDirectory.dir("licenses")) {
        into("META-INF/licenses")
    }

    from(sourceSets["main"].output)
    // The closure below unpacks the sibling modules' jars, and a closure hides from Gradle which
    // tasks produce them: with `check` in the same graph it refused to run this task at all
    // ("uses this output of task ':limn-backend-lwjgl:jar' without declaring a dependency"). The
    // configuration itself knows, so depending on it is what makes the order explicit.
    dependsOn(configurations.runtimeClasspath)
    // Everything on the runtime classpath, which since ADR 037 includes the FFmpeg payload: the
    // shim from limn-ffmpeg-natives' main jar and the libraries from the six classifiers this
    // module names above. The release asset therefore plays video on every desktop it runs on
    // with nothing merged in by a workflow.
    // As a Provider mapped over the configuration, and unzipped through the ArchiveOperations
    // service rather than the project's zipTree: a closure that reaches for the project at
    // execution time cannot be stored by the configuration cache, and this task is what the
    // release workflow runs after `check`.
    val runtimeJars = configurations.runtimeClasspath
    val archives = project.serviceOf<org.gradle.api.file.ArchiveOperations>()
    from(runtimeJars.map { files -> files.map { if (it.isDirectory) it else archives.zipTree(it) } })

    // Read back out of the archive rather than trusted from the copy specs above: EXCLUDE keeps
    // whichever entry arrived first, and a reordering of the from() calls would silently ship a
    // dependency's LICENSE under this jar's name. The check costs one zip listing.
    // Resolved now, as plain files: the action below may hold no reference to the project.
    val expected = mapOf(
        "META-INF/LICENSE" to rootProject.file("LICENSE"),
        "META-INF/NOTICE" to rootProject.file("NOTICE"),
        "META-INF/licenses/LWJGL-LICENSE.txt" to file("licenses/LWJGL-LICENSE.txt"),
        "META-INF/licenses/LGPL-2.1.txt" to file("licenses/LGPL-2.1.txt"),
    )
    doLast {
        ZipFile(archiveFile.get().asFile).use { archive ->
            for ((entry, source) in expected) {
                val found = archive.getEntry(entry)
                    ?: throw GradleException("${archiveFileName.get()} carries no $entry")
                if (!archive.getInputStream(found).readAllBytes().contentEquals(source.readBytes())) {
                    throw GradleException("$entry in ${archiveFileName.get()} is not ${source.name}")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ the jar Maven Central holds
//
// The same program, published thin: this jar and a POM, and the launcher's Maven cache does the
// rest. `jbang io.github.limn-toolkit:limn-demo:<version>` fetches the toolkit, the fonts, the
// icon pack and — through the profiles the root build writes into the POM — the LWJGL and FFmpeg
// natives of the machine it runs on, and not the other five platforms'. The fat jar above stays
// for the reader with no network or no cache to fill; this one is for everyone else, and it is
// the coordinate every README leads with.
//
// Publishing it does not make it a library, and ADR 038's rule is not bent by it. That rule says
// an icon pack is an application's choice of vocabulary and nothing in this build may depend on
// it but the demo — because the demo IS an application. It still is: what changes is that a
// stranger can now start it from its coordinate, which is what an application is for.
//
// The manifest is what makes the coordinate enough. jbang runs a jar by its Main-Class, and a
// jar without one needs --main on the command line, which a README then has to carry.
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "limn.demo.Main",
            "Implementation-Title" to "Limn kitchen sink",
            "Implementation-Version" to project.version,
        )
    }
}

// A developer's `full` payload shadows the published one on this module's classpath (see
// devNatives above), and it does so by being a main resource — which the jar and the sources
// jar would carry out of the machine. The release builds on a runner with no sibling clone, but
// publishToMavenLocal and a workstation release do not, so what shadows the payload for `run`
// is kept out of every archive by name, whatever the sibling clone happens to hold.
tasks.withType<Jar>().configureEach {
    // Not the fat jar: a developer's own build of it playing the writer scenes is the point of
    // the sibling clone, and that jar is the release runner's to build clean.
    if (name == "fatJar") return@configureEach
    val shadowing by lazy {
        fileTree(devNatives).files
            .map { devNatives.toPath().relativize(it.toPath()).joinToString("/") }
            .toSet()
    }
    exclude { details -> details.path in shadowing }
}