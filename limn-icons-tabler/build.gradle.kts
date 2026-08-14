// limn-icons-tabler: the Tabler icon set, as an opt-in icon pack.
//
// It sits where limn-video sits: it implements a vocabulary the toolkit publishes
// (limn.graphics.Icon) and NOTHING depends on it, so an application that draws a button
// never pulls six thousand icons onto its classpath. That is also what leaves room for a
// second pack beside this one: the pack is a dependency an application chooses, and the
// widget set keeps shipping only the few glyphs its own controls draw.
//
// The sources under limn/icons/tabler are GENERATED, along with the resource blob, by
// scripts/generate-tabler-icons.py from a pinned upstream release. Edit the generator, not
// its output; the pin and the checksum are in the script and in NOTICE.

plugins {
    `java-library`
}

dependencies {
    api(project(":limn-toolkit"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
