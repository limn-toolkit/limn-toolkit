import Cocoa
import ApplicationServices
import ObjectiveC

// How an AX client's AXScrollToVisible reaches an NSAccessibilityElement, if it reaches one at all.
// macOS 26 exports the action name NSAccessibilityScrollToVisibleAction ("AXScrollToVisible") and
// declares no selector for it: none in NSAccessibilityElement's, NSObject's, NSView's, NSResponder's,
// NSWindow's or NSApplication's own method lists, and none in any NSAccessibility protocol (read on
// the 26.6.2 guest, 2026-09-13). The legacy action entry points (accessibilityActionNames,
// accessibilityPerformAction:) are NSView's category and not in an NSAccessibilityElement's chain.
// So the route is a behaviour of AppKit's dispatch, and it is read here rather than guessed.
//
// Every method below is added at run time with class_addMethod on a subclass of
// NSAccessibilityElement, which is exactly how the Limn bridge installs its own; each logs when it
// is entered, and every element's isAccessibilitySelectorAllowed: logs what it is asked and allows
// everything. Encodings are read from the running AppKit (method_getTypeEncoding) where a class
// declares the selector, and written only for the one selector no class declares, which is the
// guess this probe exists to test.
//
//   serve   A window whose content view carries five elements, each a button with a label:
//             plain             nothing added: the control
//             press-only        -accessibilityPerformPress
//             legacy-actions    -accessibilityActionNames answering [AXPress, AXScrollToVisible],
//                               -accessibilityPerformAction:, -accessibilityActionDescription:
//             custom-action     an NSAccessibilityCustomAction named "AXScrollToVisible"
//             guessed-selector  -accessibilityPerformScrollToVisible (encoding B16@0:8, written)
//   read    An unprivileged AX client: for each element, its attribute names, its action names,
//           and the AXError of AXUIElementPerformAction for AXScrollToVisible and then AXPress.
//           Whether a perform was delivered is read off the provider's log, not the error.
//
// Build with `swiftc -O -o scroll-to-visible-probe scroll-to-visible-probe.swift`. Start the
// provider in the GUI session as the logged-in user, take its pid from its first line, run the
// client over plain ssh, then kill the provider by that pid.
//
// usage: scroll-to-visible-probe serve [seconds]    (default 60)
//        scroll-to-visible-probe read <pid>

setvbuf(stdout, nil, _IOLBF, 0)

let clock: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss.SSS"
    f.timeZone = TimeZone(identifier: "UTC")
    return f
}()
func log(_ text: String) { print("\(clock.string(from: Date()))Z \(text)") }

let args = Array(CommandLine.arguments.dropFirst())
guard let mode = args.first, mode == "serve" || mode == "read" else {
    print("usage: scroll-to-visible-probe serve [seconds]\n       scroll-to-visible-probe read <pid>")
    exit(2)
}
let tags = ["plain", "press-only", "legacy-actions", "custom-action", "guessed-selector"]

// ---- serve ----------------------------------------------------------------------------------------

func encoding(_ cls: AnyClass, _ selector: String) -> String? {
    guard let method = class_getInstanceMethod(cls, NSSelectorFromString(selector)),
          let types = method_getTypeEncoding(method) else { return nil }
    return String(cString: types)
}

func tagOf(_ me: AnyObject) -> String {
    (me as? NSAccessibilityElement)?.accessibilityLabel() ?? "?"
}

/// A fresh subclass of NSAccessibilityElement with a logging, allow-everything gate.
func subclass(_ name: String) -> AnyClass {
    let cls: AnyClass = objc_allocateClassPair(NSAccessibilityElement.self, name, 0)!
    let gate: @convention(block) (AnyObject, Selector) -> Bool = { me, selector in
        log("gate \(tagOf(me)) -\(NSStringFromSelector(selector)) -> YES")
        return true
    }
    let types = encoding(NSAccessibilityElement.self, "isAccessibilitySelectorAllowed:")!
    class_addMethod(cls, NSSelectorFromString("isAccessibilitySelectorAllowed:"),
                    imp_implementationWithBlock(gate), types)
    return cls
}

func add(_ cls: AnyClass, _ selector: String, _ types: String, _ block: Any) {
    let added = class_addMethod(cls, NSSelectorFromString(selector),
                                imp_implementationWithBlock(block), types)
    log("added -\(selector) \(types) to \(NSStringFromClass(cls)): \(added)")
}

func serve() {
    let seconds = args.count >= 2 ? Double(args[1]) ?? 60 : 60
    let app = NSApplication.shared
    app.setActivationPolicy(.accessory)
    let window = NSWindow(contentRect: NSRect(x: 260, y: 260, width: 360, height: 300),
                          styleMask: [.titled], backing: .buffered, defer: false)
    window.title = "scroll-to-visible-probe"
    let content = NSView(frame: NSRect(x: 0, y: 0, width: 360, height: 300))
    window.contentView = content

    let classes: [String: AnyClass] = Dictionary(uniqueKeysWithValues: tags.map {
        ($0, subclass("ScrollProbe_" + $0.replacingOccurrences(of: "-", with: "_")))
    })

    let boolGetter = encoding(NSAccessibilityElement.self, "accessibilityPerformPress")!
    let press: @convention(block) (AnyObject) -> Bool = { me in
        log("ENTERED -accessibilityPerformPress on \(tagOf(me))"); return true
    }
    add(classes["press-only"]!, "accessibilityPerformPress", boolGetter, press)
    add(classes["legacy-actions"]!, "accessibilityPerformPress", boolGetter, press)

    let actionNames: @convention(block) (AnyObject) -> NSArray = { me in
        log("ENTERED -accessibilityActionNames on \(tagOf(me))")
        return ["AXPress", "AXScrollToVisible"] as NSArray
    }
    let performAction: @convention(block) (AnyObject, NSString) -> Void = { me, action in
        log("ENTERED -accessibilityPerformAction:\(action) on \(tagOf(me))")
    }
    let describeAction: @convention(block) (AnyObject, NSString) -> NSString = { me, action in
        log("ENTERED -accessibilityActionDescription:\(action) on \(tagOf(me))")
        return action
    }
    let legacy: AnyClass = classes["legacy-actions"]!
    for (selector, block) in [("accessibilityActionNames", actionNames as Any),
                              ("accessibilityPerformAction:", performAction as Any),
                              ("accessibilityActionDescription:", describeAction as Any)] {
        guard let types = encoding(NSView.self, selector) else {
            log("NSView declares no -\(selector): not added"); continue
        }
        add(legacy, selector, types, block)
    }

    let guessed: @convention(block) (AnyObject) -> Bool = { me in
        log("ENTERED -accessibilityPerformScrollToVisible on \(tagOf(me))"); return true
    }
    log("any class declares -accessibilityPerformScrollToVisible: "
        + "\(encoding(NSAccessibilityElement.self, "accessibilityPerformScrollToVisible") ?? "no")")
    add(classes["guessed-selector"]!, "accessibilityPerformScrollToVisible", "B16@0:8", guessed)

    for cls in classes.values { objc_registerClassPair(cls) }

    var elements: [NSAccessibilityElement] = []
    for (index, tag) in tags.enumerated() {
        let element = (classes[tag]! as! NSAccessibilityElement.Type).init()
        element.setAccessibilityRole(.button)
        element.setAccessibilityLabel(tag)
        element.setAccessibilityParent(content)
        element.setAccessibilityFrameInParentSpace(
            NSRect(x: 20, y: 250 - CGFloat(index) * 55, width: 320, height: 40))
        if tag == "custom-action" {
            element.setAccessibilityCustomActions([
                NSAccessibilityCustomAction(name: "AXScrollToVisible") {
                    log("ENTERED custom action AXScrollToVisible on custom-action"); return true
                }
            ])
        }
        elements.append(element)
    }
    content.setAccessibilityChildren(elements)

    window.orderFrontRegardless()
    log("serving pid=\(getpid()) for \(Int(seconds))s")
    DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
        log("time is up; exiting")
        exit(0)
    }
    withExtendedLifetime(elements) { app.run() }
}

// ---- read -----------------------------------------------------------------------------------------

func read() {
    guard args.count >= 2, let pid = Int32(args[1]) else {
        print("usage: scroll-to-visible-probe read <pid>"); exit(2)
    }
    func copy(_ e: AXUIElement, _ name: String) -> AnyObject? {
        var value: CFTypeRef?
        return AXUIElementCopyAttributeValue(e, name as CFString, &value) == .success ? value : nil
    }
    func find(_ e: AXUIElement, _ depth: Int, into found: inout [String: AXUIElement]) {
        if let description = copy(e, kAXDescriptionAttribute) as? String, tags.contains(description) {
            found[description] = e
        }
        if depth == 0 { return }
        for child in (copy(e, kAXChildrenAttribute) as? [AXUIElement]) ?? [] {
            find(child, depth - 1, into: &found)
        }
    }
    log("reader pid=\(getpid()) trusted=\(AXIsProcessTrusted()) provider=\(pid)")
    var found: [String: AXUIElement] = [:]
    find(AXUIElementCreateApplication(pid), 6, into: &found)
    if found.isEmpty { log("NONE of \(tags) found"); exit(1) }
    for tag in tags {
        guard let element = found[tag] else { log("\(tag): not found"); continue }
        var names: CFArray?
        AXUIElementCopyAttributeNames(element, &names)
        log("\(tag): AXAttributeNames=\((names as? [String]) ?? [])")
        var actions: CFArray?
        let actionsError = AXUIElementCopyActionNames(element, &actions)
        log("\(tag): actions=AXError(\(actionsError.rawValue)) \((actions as? [String]) ?? [])")
        if let custom = copy(element, "AXCustomActions") {
            log("\(tag): AXCustomActions=\(custom)")
        }
        for action in ["AXScrollToVisible", "AXPress"] {
            log("\(tag): perform \(action) begins")
            let error = AXUIElementPerformAction(element, action as CFString)
            log("\(tag): perform \(action) -> AXError(\(error.rawValue))")
            Thread.sleep(forTimeInterval: 0.3)
        }
    }
}

if mode == "serve" { serve() } else { read() }
