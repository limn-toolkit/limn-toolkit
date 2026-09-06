import Cocoa

// ADR 039 §12.3, this platform's version of the other two dump scripts: the role and subrole and
// notification constants are NSString globals in AppKit, and their values are read out of the
// running framework with dlsym rather than copied from a header into a table.
//
// It also prints the ObjC type encoding AppKit declares for every selector the bridge implements,
// with the class that declares it -- which is the check the Windows run proved is worth having and
// the one Windows cannot have at all (§13.4).
//
// Two kinds of answer matter here and they are different. A symbol that does not resolve is a name
// this repository invented; a symbol that resolves to a string other than the one expected is a
// name AppKit has quietly changed. Both are printed, neither is guessed at.
//
// usage: dump-appkit-constants [symbol ...]   (no arguments: the whole table below)

let appKitPath = "/System/Library/Frameworks/AppKit.framework/AppKit"
guard let appKit = dlopen(appKitPath, RTLD_LAZY) else {
    print("FAIL: cannot dlopen \(appKitPath)"); exit(1)
}

func globalString(_ symbol: String) -> String? {
    guard let address = dlsym(appKit, symbol) else { return nil }
    let pointer = address.assumingMemoryBound(to: Optional<NSString>.self)
    return pointer.pointee as String?
}

let roles = [
    "NSAccessibilityUnknownRole", "NSAccessibilityWindowRole", "NSAccessibilitySheetRole",
    "NSAccessibilityGroupRole", "NSAccessibilityScrollAreaRole", "NSAccessibilityScrollBarRole",
    "NSAccessibilitySplitGroupRole", "NSAccessibilitySplitterRole", "NSAccessibilityToolbarRole",
    "NSAccessibilityMenuBarRole", "NSAccessibilityMenuRole", "NSAccessibilityMenuItemRole",
    "NSAccessibilityMenuBarItemRole", "NSAccessibilityButtonRole", "NSAccessibilityCheckBoxRole",
    "NSAccessibilityRadioButtonRole", "NSAccessibilityRadioGroupRole",
    "NSAccessibilityStaticTextRole", "NSAccessibilityImageRole",
    "NSAccessibilityProgressIndicatorRole", "NSAccessibilityLevelIndicatorRole",
    "NSAccessibilitySliderRole", "NSAccessibilityIncrementorRole", "NSAccessibilityTextFieldRole",
    "NSAccessibilityTextAreaRole", "NSAccessibilityComboBoxRole", "NSAccessibilityPopUpButtonRole",
    "NSAccessibilityListRole", "NSAccessibilityRowRole", "NSAccessibilityTableRole",
    "NSAccessibilityTabGroupRole", "NSAccessibilityColorWellRole", "NSAccessibilityValueIndicatorRole",
    "NSAccessibilitySplitterRole", "NSAccessibilityToolbarRole", "NSAccessibilityDrawerRole",
    "NSAccessibilityLayoutAreaRole", "NSAccessibilityLayoutItemRole",
]

let subroles = [
    "NSAccessibilityStandardWindowSubrole", "NSAccessibilityDialogSubrole",
    "NSAccessibilitySystemDialogSubrole", "NSAccessibilitySystemFloatingWindowSubrole",
    "NSAccessibilitySecureTextFieldSubrole", "NSAccessibilitySearchFieldSubrole",
    "NSAccessibilityToggleSubrole", "NSAccessibilitySwitchSubrole",
    "NSAccessibilityContentListSubrole", "NSAccessibilityDefinitionListSubrole",
    "NSAccessibilityTimelineSubrole", "NSAccessibilitySeparatorSubrole",
    "NSAccessibilityCloseButtonSubrole", "NSAccessibilityIncrementArrowSubrole",
    "NSAccessibilityTabButtonSubrole", "NSAccessibilityCollectionListSubrole",
    "NSAccessibilitySectionListSubrole", "NSAccessibilityDescriptionListSubrole",
]

let notifications = [
    "NSAccessibilityFocusedUIElementChangedNotification", "NSAccessibilityValueChangedNotification",
    "NSAccessibilityTitleChangedNotification", "NSAccessibilityUIElementDestroyedNotification",
    "NSAccessibilityCreatedNotification", "NSAccessibilityLayoutChangedNotification",
    "NSAccessibilitySelectedChildrenChangedNotification",
    "NSAccessibilitySelectedTextChangedNotification", "NSAccessibilityMovedNotification",
    "NSAccessibilityResizedNotification", "NSAccessibilityWindowCreatedNotification",
    "NSAccessibilityMainWindowChangedNotification", "NSAccessibilityFocusedWindowChangedNotification",
    "NSAccessibilityAnnouncementRequestedNotification", "NSAccessibilityRowCountChangedNotification",
    "NSAccessibilityAnnouncementKey", "NSAccessibilityPriorityKey",
    "NSAccessibilityPriorityLow", "NSAccessibilityPriorityMedium", "NSAccessibilityPriorityHigh",
]

let selectors = [
    "accessibilityRole", "accessibilitySubrole", "accessibilityRoleDescription",
    "accessibilityTitle", "accessibilityLabel", "accessibilityHelp", "accessibilityValue",
    "accessibilityMinValue", "accessibilityMaxValue", "accessibilityChildren",
    "accessibilityParent", "accessibilityFocusedUIElement", "isAccessibilityElement",
    "isAccessibilityEnabled", "isAccessibilityFocused", "setAccessibilityFocused:",
    "accessibilitySelectedChildren", "accessibilityHitTest:", "accessibilityIdentifier",
    "accessibilityFrame", "accessibilityFrameInParentSpace", "setAccessibilityFrameInParentSpace:",
    "setAccessibilityChildren:", "accessibilityPerformPress", "accessibilityPerformIncrement",
    "accessibilityPerformDecrement", "accessibilityPerformShowMenu", "accessibilityPerformPick",
    "accessibilityPerformCancel", "accessibilityPerformConfirm",
    "accessibilityNumberOfCharacters", "accessibilitySelectedText",
    "accessibilitySelectedTextRange", "accessibilityStringForRange:",
    "accessibilityRangeForLine:", "accessibilityInsertionPointLineNumber",
    "accessibilityLinkedUIElements", "setAccessibilityLinkedUIElements:",
    "isAccessibilityModal", "setAccessibilityModal:", "accessibilityWindow",
]

// The classes worth asking, in the order a bridge would trust them. A selector's encoding is a fact
// about the ObjC runtime, not about the class it was read from, so the first class that declares it
// gives the answer -- but WHICH class answered is itself worth printing, because a selector that
// only NSView declares is one our elements may never be asked at all (§13.22).
let classes = ["NSAccessibilityElement", "NSView", "NSWindow", "NSResponder", "NSApplication"]

func dumpGlobals(_ title: String, _ symbols: [String]) {
    print("\n==== \(title) ====")
    var missing: [String] = []
    for symbol in symbols.sorted() {
        if let value = globalString(symbol) {
            print("  \(symbol) = \"\(value)\"")
        } else {
            missing.append(symbol)
        }
    }
    if !missing.isEmpty { print("  !! NOT EXPORTED BY THIS APPKIT: \(missing.joined(separator: ", "))") }
}

let wanted = Array(CommandLine.arguments.dropFirst())
if wanted.isEmpty {
    print("AppKit: \(appKitPath)")
    print("macOS: \(ProcessInfo.processInfo.operatingSystemVersionString)")
    dumpGlobals("roles", roles)
    dumpGlobals("subroles", subroles)
    dumpGlobals("notifications and announcement keys", notifications)

    print("\n==== selector type encodings ====")
    for selector in selectors {
        let sel = NSSelectorFromString(selector)
        var line = "  -\(selector)"
        var found = false
        for className in classes {
            guard let cls = NSClassFromString(className) else { continue }
            guard let method = class_getInstanceMethod(cls, sel) else { continue }
            let encoding = method_getTypeEncoding(method).map { String(cString: $0) } ?? "<none>"
            line += "  \(encoding)  (from \(className))"
            found = true
            break
        }
        print(found ? line : line + "  !! DECLARED BY NONE OF \(classes)")
    }
} else {
    dumpGlobals("requested", wanted)
}
