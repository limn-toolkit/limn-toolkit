// limn-components is the widget set built on the toolkit's tree: Label, Button,
// TextField, TextArea, Checkbox, RadioButton, ComboBox, Slider, Spinner, ListView,
// ScrollView, TabbedPane, SegmentedControl, Dialog, MenuBar, PopupMenu, ColorPicker,
// VideoView and charts, plus the Theme and size tokens every one of them reads from.
//
// It sees the toolkit and nothing below it: a widget never reaches for a backend.

plugins {
    `java-library`
}

dependencies {
    api(project(":limn-toolkit"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
