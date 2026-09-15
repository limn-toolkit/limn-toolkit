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
// After the encodings, four sections that answer questions the encodings alone cannot:
//
//   * which class in NSAccessibilityElement's own superclass chain OWNS each selector -- the first
//     whose own method list (class_copyMethodList, no inheritance) has it, which is the
//     implementation a forward from a subclass of ours actually reaches, and the image it lives in;
//   * which NSAccessibility* protocol declares it, required or optional, with the protocol's types
//     (protocol_copyMethodDescriptionList, which does not recurse into adopted protocols);
//   * every selector name containing a few fragments, so a name like the scroll-to-visible action's
//     is read off the runtime rather than spelled from memory;
//   * every selector the bridge installs today, looked up exactly the way AxObjC.encodingOf looks it
//     up, so a selector that would make that method throw -- and Bridges.openFor answer NONE -- is
//     visible here first (MACOS-NEW-6).
//
// And the one kind of number this script cannot dlsym: C enum values, printed from the SDK the
// script was compiled against and labelled as such, without quotes, so the constants test's parser
// never mistakes one for an exported string.
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
    "NSAccessibilityCellRole", "NSAccessibilityColumnRole", "NSAccessibilityOutlineRole",
    // The triangle NSOutlineView vends inside a row, if an outline row ever vends one (M1).
    "NSAccessibilityDisclosureTriangleRole",
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
    "NSAccessibilityTableRowSubrole", "NSAccessibilityOutlineRowSubrole",
    "NSAccessibilitySortButtonSubrole",
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
    "NSAccessibilitySelectedRowsChangedNotification",
    "NSAccessibilitySelectedColumnsChangedNotification",
    "NSAccessibilitySortDirectionAscending", "NSAccessibilitySortDirectionDescending",
    "NSAccessibilitySortDirectionUnknown",
    // An outline row opening and closing (M1), a grid's chosen cells moving (M3's route C), and the
    // key that names the elements a layout change is about (MACOS-NEW-3).
    "NSAccessibilityRowExpandedNotification", "NSAccessibilityRowCollapsedNotification",
    "NSAccessibilitySelectedCellsChangedNotification", "NSAccessibilityUIElementsKey",
]

// Attribute names: what the legacy entry points are asked by, and what isAccessibilitySelectorAllowed:
// would have to be told apart by once the gate stops answering yes to every attribute (M1, M2).
let attributes = [
    "NSAccessibilityRoleAttribute", "NSAccessibilityValueAttribute", "NSAccessibilityChildrenAttribute",
    "NSAccessibilityFocusedAttribute", "NSAccessibilitySelectedAttribute",
    "NSAccessibilityFocusedUIElementAttribute",
    "NSAccessibilityDisclosingAttribute", "NSAccessibilityDisclosedRowsAttribute",
    "NSAccessibilityDisclosedByRowAttribute", "NSAccessibilityDisclosureLevelAttribute",
    "NSAccessibilityExpandedAttribute", "NSAccessibilitySelectedChildrenAttribute",
    "NSAccessibilityRowsAttribute", "NSAccessibilityVisibleRowsAttribute",
    "NSAccessibilitySelectedRowsAttribute", "NSAccessibilitySelectedCellsAttribute",
    "NSAccessibilityColumnsAttribute", "NSAccessibilityIndexAttribute",
    "NSAccessibilityRowCountAttribute", "NSAccessibilityColumnCountAttribute",
]

// Action names: what a client's AXPerformAction carries, and what accessibilityActionNames lists.
let actions = [
    "NSAccessibilityPressAction", "NSAccessibilityIncrementAction", "NSAccessibilityDecrementAction",
    "NSAccessibilityShowMenuAction", "NSAccessibilityScrollToVisibleAction",
    "NSAccessibilityConfirmAction", "NSAccessibilityCancelAction", "NSAccessibilityPickAction",
    "NSAccessibilityRaiseAction",
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
    // The gate that decides which of the perform selectors an element actually OFFERS. Without it,
    // installing the seven on one class makes every element advertise all seven.
    "isAccessibilitySelectorAllowed:", "accessibilityActionNames", "accessibilityPerformRaise",
    // The table family: what NSAccessibilityTable, NSAccessibilityRow and NSAccessibilityCell ask.
    "accessibilityRows", "accessibilityColumns", "accessibilityVisibleRows",
    "accessibilityVisibleColumns", "accessibilitySelectedRows", "setAccessibilitySelectedRows:",
    "accessibilitySelectedColumns", "accessibilityHeader", "accessibilityRowCount",
    "accessibilityColumnCount", "accessibilityColumnHeaderUIElements",
    "accessibilityRowHeaderUIElements", "accessibilityVisibleCells", "accessibilitySelectedCells",
    "accessibilityCellForColumn:row:", "accessibilityRowIndexRange",
    "accessibilityColumnIndexRange", "accessibilityIndex", "isAccessibilitySelected",
    "setAccessibilitySelected:", "accessibilitySortDirection", "accessibilityColumnTitles",
    "accessibilityDisclosureLevel", "accessibilityOrientation",
    // An outline row's disclosure, as NSAccessibilityRow and NSOutlineView answer it (M1).
    "isAccessibilityDisclosed", "setAccessibilityDisclosed:", "accessibilityDisclosedRows",
    "setAccessibilityDisclosedRows:", "accessibilityDisclosedByRow", "setAccessibilityDisclosedByRow:",
    "setAccessibilityDisclosureLevel:",
    // Open or closed, for a combo box or anything else that expands (MACOS-NEW-5).
    "isAccessibilityExpanded", "setAccessibilityExpanded:",
    // The setter half a reader writes through (MACOS-NEW-11), selected children (MACOS-NEW-2) and
    // columns (M4's vended-column option).
    "setAccessibilityValue:", "setAccessibilitySelectedChildren:", "setAccessibilityColumns:",
    // The legacy entry points. f4bc544 overrides the first two class-wide for AXElementBusy, and
    // every other one is what a forward would have to cover if an override ever broke it
    // (MACOS-NEW-6, MACOS-NEW-12). The section after the encodings says which class owns each.
    "accessibilityAttributeNames", "accessibilityAttributeValue:", "accessibilityIsAttributeSettable:",
    "accessibilitySetValue:forAttribute:", "accessibilityParameterizedAttributeNames",
    "accessibilityAttributeValue:forParameter:", "accessibilityActionDescription:",
    "accessibilityPerformAction:", "accessibilityIsIgnored", "accessibilityArrayAttributeCount:",
    "accessibilityArrayAttributeValues:index:maxCount:", "accessibilityIndexOfChild:",
]

// Every selector limn-backend-lwjgl's macOS bridge hands class_addMethod, with where, read out of
// AxElementClass.java (install, installBusy, installTable, installActions, installFocusedElement,
// installFocusedElementOnView, installHitTest) and AxActions.java's selector table at f4bc544.
// This is a copy, and a copy is what goes stale: its use is to show, on the guest, what
// AxObjC.encodingOf would answer for each one. AxSelectorsTest fails when it and AxSelectors differ.
let installedByTheBridge: [(String, String)] = [
    ("accessibilityRole", "element"), ("accessibilitySubrole", "element"),
    ("accessibilityTitle", "element"), ("accessibilityLabel", "element"),
    ("accessibilityHelp", "element"), ("accessibilityRoleDescription", "element"),
    ("accessibilityValue", "element"), ("accessibilityIdentifier", "element"),
    ("accessibilityChildren", "element"), ("accessibilityParent", "element"),
    ("accessibilityLinkedUIElements", "element"), ("isAccessibilityElement", "element"),
    ("isAccessibilityEnabled", "element"), ("isAccessibilityFocused", "element"),
    ("accessibilityHitTest:", "element"), ("accessibilityFocusedUIElement", "element"),
    ("accessibilityFocusedUIElement", "content view subclass"),
    ("accessibilityPerformPress", "element"), ("accessibilityPerformConfirm", "element"),
    ("accessibilityPerformIncrement", "element"), ("accessibilityPerformDecrement", "element"),
    ("accessibilityPerformShowMenu", "element"), ("accessibilityPerformCancel", "element"),
    ("isAccessibilitySelectorAllowed:", "element"),
    ("accessibilityRows", "element"), ("accessibilityVisibleRows", "element"),
    ("accessibilitySelectedRows", "element"), ("accessibilityColumns", "element"),
    ("accessibilityHeader", "element"), ("accessibilityColumnHeaderUIElements", "element"),
    ("accessibilityRowCount", "element"), ("accessibilityColumnCount", "element"),
    ("accessibilityIndex", "element"), ("accessibilityRowIndexRange", "element"),
    ("accessibilityColumnIndexRange", "element"), ("accessibilityCellForColumn:row:", "element"),
    ("isAccessibilitySelected", "element"),
    ("accessibilityAttributeValue:", "element"), ("accessibilityAttributeNames", "element"),
    // Added by the phase-3 macOS lane (2026-09-15): selection (MACOS-NEW-2), disclosure (M1),
    // expanded (MACOS-NEW-5). AxSelectorsTest holds this list to AxSelectors'.
    ("accessibilitySelectedChildren", "element"), ("accessibilitySelectedCells", "element"),
    ("isAccessibilityDisclosed", "element"), ("accessibilityDisclosureLevel", "element"),
    ("accessibilityDisclosedByRow", "element"), ("accessibilityDisclosedRows", "element"),
    ("isAccessibilityExpanded", "element"),
    // The setter half (MACOS-NEW-11).
    ("setAccessibilityFocused:", "element"), ("setAccessibilitySelected:", "element"),
    ("setAccessibilityDisclosed:", "element"), ("setAccessibilityExpanded:", "element"),
    ("setAccessibilityValue:", "element"), ("setAccessibilitySelectedRows:", "element"),
    // The legacy action pair, for AXScrollToVisible (MACOS-NEW-11).
    ("accessibilityActionNames", "element"), ("accessibilityPerformAction:", "element"),
    // A table's columns (M4): the two column lists on the table, and what a column element answers on
    // the class it is vended as.
    ("accessibilityVisibleColumns", "element"), ("accessibilitySelectedColumns", "element"),
    ("accessibilityRole", "column class"), ("accessibilityIndex", "column class"),
    ("accessibilityRows", "column class"), ("accessibilityVisibleRows", "column class"),
    ("accessibilityHeader", "column class"), ("accessibilityParent", "column class"),
    ("isAccessibilitySelectorAllowed:", "column class"), ("accessibilityActionNames", "column class"),
]

// Fragments searched for across the class chains and the protocols below, so a name is read rather
// than spelled: the scroll-to-visible action (MACOS-NEW-11), disclosure (M1), expanded (MACOS-NEW-5).
let fragments = ["ScrollToVisible", "Disclos", "Expanded"]

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

/// The encoding line the way AxObjC.encodingOf finds it: class_getInstanceMethod, which DOES search
/// superclasses, on each of `classes` in order. nil when none of them responds.
func encodingLookup(_ selector: String) -> (encoding: String, className: String)? {
    let sel = NSSelectorFromString(selector)
    for className in classes {
        guard let cls = NSClassFromString(className) else { continue }
        guard let method = class_getInstanceMethod(cls, sel) else { continue }
        return (method_getTypeEncoding(method).map { String(cString: $0) } ?? "<none>", className)
    }
    return nil
}

/// A class and every superclass above it, nearest first.
func chain(_ className: String) -> [AnyClass] {
    var out: [AnyClass] = []
    var next: AnyClass? = NSClassFromString(className)
    while let cls = next { out.append(cls); next = class_getSuperclass(cls) }
    return out
}

/// The method in `cls`'s OWN list -- categories included, superclasses not.
func ownMethod(_ cls: AnyClass, _ sel: Selector) -> Method? {
    var count: UInt32 = 0
    guard let list = class_copyMethodList(cls, &count) else { return nil }
    defer { free(list) }
    for i in 0..<Int(count) where method_getName(list[i]) == sel { return list[i] }
    return nil
}

/// Where an implementation lives. The symbol name is printed only when it names the selector: in
/// the shared cache dladdr answers the nearest EXPORTED symbol, which for a method is usually some
/// unrelated function, and printing that would be a wrong answer dressed as a reading.
func imageOf(_ method: Method) -> String {
    var info = Dl_info()
    let imp = method_getImplementation(method)
    guard dladdr(UnsafeRawPointer(imp), &info) != 0, let file = info.dli_fname else { return "image ?" }
    var text = "image \(String(cString: file))"
    if let sname = info.dli_sname {
        let symbol = String(cString: sname)
        if symbol.contains(NSStringFromSelector(method_getName(method))) { text += " symbol \(symbol)" }
    }
    return text
}

func encodingOf(_ method: Method) -> String {
    method_getTypeEncoding(method).map { String(cString: $0) } ?? "<none>"
}

/// Every protocol whose name starts NSAccessibility: those the runtime enumerates, plus the ones the
/// SDK this was compiled against names, asked for by name in case the enumeration skips protocols
/// that live preoptimized in the shared cache. A name that resolves to nothing is printed as such.
func accessibilityProtocols() -> (found: [(String, Protocol)], unresolved: [String]) {
    var byName: [String: Protocol] = [:]
    var count: UInt32 = 0
    if let list = objc_copyProtocolList(&count) {
        for i in 0..<Int(count) {
            let name = String(cString: protocol_getName(list[i]))
            if name.hasPrefix("NSAccessibility") { byName[name] = list[i] }
        }
    }
    // Keys to ask by, not answers: whether each resolves is printed.
    let named = [
        "NSAccessibility", "NSAccessibilityElement", "NSAccessibilityGroup", "NSAccessibilityButton",
        "NSAccessibilitySwitch", "NSAccessibilityRadioButton", "NSAccessibilityCheckBox",
        "NSAccessibilityStaticText", "NSAccessibilityNavigableStaticText",
        "NSAccessibilityProgressIndicator", "NSAccessibilityStepper", "NSAccessibilitySlider",
        "NSAccessibilityImage", "NSAccessibilityContainsTransientUI", "NSAccessibilityTable",
        "NSAccessibilityOutline", "NSAccessibilityList", "NSAccessibilityRow",
        "NSAccessibilityLayoutArea", "NSAccessibilityLayoutItem", "NSAccessibilityElementLoading",
    ]
    var unresolved: [String] = []
    for name in named where byName[name] == nil {
        if let p = objc_getProtocol(name) { byName[name] = p } else { unresolved.append(name) }
    }
    return (byName.keys.sorted().map { ($0, byName[$0]!) }, unresolved)
}

/// The protocols that declare `sel` THEMSELVES, required or optional, instance side, with their types.
func declarations(_ sel: Selector, _ protocols: [(String, Protocol)]) -> [String] {
    var out: [String] = []
    for (name, proto) in protocols {
        for required in [true, false] {
            var count: UInt32 = 0
            guard let list = protocol_copyMethodDescriptionList(proto, required, true, &count) else { continue }
            defer { free(list) }
            for i in 0..<Int(count) where list[i].name == sel {
                let types = list[i].types.map { String(cString: $0) } ?? "<none>"
                out.append("@protocol \(name) \(required ? "required" : "optional") \(types)")
            }
        }
    }
    return out
}

let wanted = Array(CommandLine.arguments.dropFirst())
if wanted.isEmpty {
    print("AppKit: \(appKitPath)")
    print("macOS: \(ProcessInfo.processInfo.operatingSystemVersionString)")
    dumpGlobals("roles", roles)
    dumpGlobals("subroles", subroles)
    dumpGlobals("notifications and announcement keys", notifications)
    dumpGlobals("attribute names", attributes)
    dumpGlobals("action names", actions)

    print("\n==== C enum values (compiled in from the SDK headers; no symbol exists to dlsym) ====")
    print("  NSAccessibilityPriorityLow = \(NSAccessibilityPriorityLevel.low.rawValue)  (enum NSAccessibilityPriorityLevel)")
    print("  NSAccessibilityPriorityMedium = \(NSAccessibilityPriorityLevel.medium.rawValue)  (enum NSAccessibilityPriorityLevel)")
    print("  NSAccessibilityPriorityHigh = \(NSAccessibilityPriorityLevel.high.rawValue)  (enum NSAccessibilityPriorityLevel)")

    print("\n==== selector type encodings ====")
    for selector in selectors {
        let line = "  -\(selector)"
        if let found = encodingLookup(selector) {
            print(line + "  \(found.encoding)  (from \(found.className))")
        } else {
            print(line + "  !! DECLARED BY NONE OF \(classes)")
        }
    }

    // Which class a forward from a subclass of NSAccessibilityElement reaches. class_getInstanceMethod
    // above answers "someone up there responds"; this answers who. Every class in the chain that has
    // its own implementation is listed, nearest first, so an override of NSObject's by
    // NSAccessibilityElement is visible as two entries rather than hidden as one.
    let elementChain = chain("NSAccessibilityElement")
    let viewChain = chain("NSView")
    print("\n==== owning class (class_copyMethodList, walked up from NSAccessibilityElement) ====")
    print("  chain: \(elementChain.map { NSStringFromClass($0) }.joined(separator: " < "))")
    print("  fallback chain when that one has none: \(viewChain.map { NSStringFromClass($0) }.joined(separator: " < "))")
    for selector in selectors {
        let sel = NSSelectorFromString(selector)
        var owners = elementChain.compactMap { cls in ownMethod(cls, sel).map { (cls, $0) } }
        var via = "NSAccessibilityElement"
        if owners.isEmpty {
            owners = viewChain.compactMap { cls in ownMethod(cls, sel).map { (cls, $0) } }
            via = "NSView"
        }
        guard let first = owners.first else {
            print("  -\(selector)  !! OWNED BY NO CLASS IN EITHER CHAIN")
            continue
        }
        var line = "  -\(selector)  answered by \(NSStringFromClass(first.0))"
            + (via == "NSView" ? " (NOT in NSAccessibilityElement's chain; found walking NSView's)" : "")
            + "  \(encodingOf(first.1))  \(imageOf(first.1))"
        for owner in owners.dropFirst() {
            line += "  | also \(NSStringFromClass(owner.0)) \(encodingOf(owner.1)) \(imageOf(owner.1))"
        }
        print(line)
    }

    let (protocols, unresolved) = accessibilityProtocols()
    print("\n==== protocol declarations (protocol_copyMethodDescriptionList, instance side, not recursive) ====")
    print("  protocols: \(protocols.map { $0.0 }.joined(separator: ", "))")
    if !unresolved.isEmpty { print("  !! NO SUCH PROTOCOL IN THIS RUNTIME: \(unresolved.joined(separator: ", "))") }
    for selector in selectors {
        let found = declarations(NSSelectorFromString(selector), protocols)
        print("  -\(selector)  " + (found.isEmpty ? "declared by no NSAccessibility protocol" : found.joined(separator: "  | ")))
    }

    print("\n==== selectors containing \(fragments) (own method lists of both chains, NSWindow's and NSApplication's; every protocol above) ====")
    var places: [String: [String]] = [:]
    var seenClasses = Set<String>()
    for root in ["NSAccessibilityElement", "NSView", "NSWindow", "NSApplication"] {
        for cls in chain(root) {
            let className = NSStringFromClass(cls)
            guard seenClasses.insert(className).inserted else { continue }
            var count: UInt32 = 0
            guard let list = class_copyMethodList(cls, &count) else { continue }
            for i in 0..<Int(count) {
                let name = NSStringFromSelector(method_getName(list[i]))
                if fragments.contains(where: { name.contains($0) }) {
                    places[name, default: []].append("\(className) \(encodingOf(list[i]))")
                }
            }
            free(list)
        }
    }
    for (protocolName, proto) in protocols {
        for required in [true, false] {
            var count: UInt32 = 0
            guard let list = protocol_copyMethodDescriptionList(proto, required, true, &count) else { continue }
            for i in 0..<Int(count) {
                guard let sel = list[i].name else { continue }
                let name = NSStringFromSelector(sel)
                if fragments.contains(where: { name.contains($0) }) {
                    let types = list[i].types.map { String(cString: $0) } ?? "<none>"
                    places[name, default: []].append("@protocol \(protocolName) \(required ? "required" : "optional") \(types)")
                }
            }
            free(list)
        }
    }
    for name in places.keys.sorted() {
        print("  -\(name)  \(places[name]!.joined(separator: "  | "))")
    }

    print("\n==== selectors the bridge installs (AxElementClass and AxActions at f4bc544), looked up as AxObjC.encodingOf does ====")
    for (selector, target) in installedByTheBridge {
        var line = "  -\(selector)"
        if let found = encodingLookup(selector) {
            line += "  \(found.encoding)  (from \(found.className))"
        } else {
            line += "  !! DECLARED BY NONE OF \(classes): encodingOf throws and the bridge opens as NONE"
        }
        line += "  on the \(target)"
        line += selectors.contains(selector) ? "  [in the encodings table]" : "  !! NOT IN THE ENCODINGS TABLE"
        let declared = declarations(NSSelectorFromString(selector), protocols)
        line += declared.isEmpty ? "  [no NSAccessibility protocol declares it]" : "  [\(declared.count) protocol declaration(s)]"
        print(line)
    }
} else {
    dumpGlobals("requested", wanted)
}
