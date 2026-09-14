import Cocoa
import ApplicationServices

// Does AppKit ask -isAccessibilitySelectorAllowed: before answering an attribute from a getter the
// element INHERITS from NSAccessibilityElement rather than implements? And when the gate says no, is
// the attribute gone? The audit's M1 correction 5 asks it because the bridge's gate answers yes for
// every attribute selector today, and refusing one -- a disclosure level on a row that is not an
// outline row -- is only a design if AppKit honours a refusal for a getter nobody overrode. That is a
// fact about AppKit's dispatch, which no test off a Mac exercises, so it is read.
//
// One binary, two halves, so the provider and the client are always the same revision:
//
//   serve   A window whose content view carries three NSAccessibilityElement subclasses with row
//           role, each with a disclosure level set through the inherited setter (so an inherited
//           getter has a real value to answer), each logging every selector its gate is asked:
//             inherited-refused   gate refuses the named selectors; getter inherited
//             overridden-refused  gate refuses the named selectors; getter overridden (answers 3)
//             inherited-allowed   gate refuses nothing; getter inherited -- the control
//           The window is ordered front without activating the application, so a screen reader
//           that is running is not led to it and its asks do not muddy the log. It exits by itself.
//   read    An unprivileged AX client: finds the three by their descriptions under the provider's
//           window, prints each one's AXAttributeNames, and for each asked attribute prints the
//           read's AXError and value, whether it is in AXAttributeNames, and whether it is settable.
//           It reads twice, a second apart, so an answer AppKit caches after the first ask shows up
//           as a difference.
//
// Settable is on the list because the gate is asked for the SETTER selector when a client asks
// whether an attribute is settable, which is what M2's correction (3) is about.
//
// Build with `swiftc -O -o selector-allowed-probe selector-allowed-probe.swift`. Start the provider
// in the GUI session (`sudo launchctl asuser <uid> sudo -u <user> ./selector-allowed-probe serve ...`),
// take its pid from its own first line, run the client over plain ssh, then kill the provider by pid.
//
// usage: selector-allowed-probe serve [seconds] [refused-selector ...]
//        (default: 60 accessibilityDisclosureLevel)
//        selector-allowed-probe read <pid> [attribute ...]
//        (default: AXDisclosureLevel)

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
    print("usage: selector-allowed-probe serve [seconds] [refused-selector ...]\n"
        + "       selector-allowed-probe read <pid> [attribute ...]")
    exit(2)
}

// ---- serve ----------------------------------------------------------------------------------------

class GatedElement: NSAccessibilityElement {
    var tag = ""
    var refused: Set<String> = []
    override func isAccessibilitySelectorAllowed(_ selector: Selector) -> Bool {
        let name = NSStringFromSelector(selector)
        let allowed = !refused.contains(name)
        log("gate \(tag) -\(name) -> \(allowed ? "YES" : "NO")")
        return allowed
    }
}

final class OverridingElement: GatedElement {
    override func accessibilityDisclosureLevel() -> Int {
        log("getter \(tag) -accessibilityDisclosureLevel -> 3")
        return 3
    }
}

func serve() {
    let seconds = args.count >= 2 ? Double(args[1]) ?? 60 : 60
    let refused = Set(args.count >= 3 ? Array(args.dropFirst(2)) : ["accessibilityDisclosureLevel"])

    let app = NSApplication.shared
    app.setActivationPolicy(.accessory)
    let window = NSWindow(contentRect: NSRect(x: 200, y: 200, width: 360, height: 200),
                          styleMask: [.titled], backing: .buffered, defer: false)
    window.title = "selector-allowed-probe"
    let content = NSView(frame: NSRect(x: 0, y: 0, width: 360, height: 200))
    window.contentView = content

    let specs: [(String, GatedElement, Set<String>)] = [
        ("inherited-refused", GatedElement(), refused),
        ("overridden-refused", OverridingElement(), refused),
        ("inherited-allowed", GatedElement(), []),
    ]
    var elements: [GatedElement] = []
    for (index, spec) in specs.enumerated() {
        let (tag, element, refusedHere) = spec
        element.tag = tag
        element.refused = refusedHere
        element.setAccessibilityRole(.row)
        element.setAccessibilitySubrole(.outlineRow)
        element.setAccessibilityLabel(tag)
        element.setAccessibilityDisclosureLevel(2)
        element.setAccessibilityParent(content)
        element.setAccessibilityFrameInParentSpace(
            NSRect(x: 20, y: 140 - CGFloat(index) * 50, width: 320, height: 40))
        elements.append(element)
    }
    content.setAccessibilityChildren(elements)

    window.orderFrontRegardless()
    log("serving pid=\(getpid()) refused=\(refused.sorted()) for \(Int(seconds))s")
    DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
        log("time is up; exiting")
        exit(0)
    }
    app.run()
}

// ---- read -----------------------------------------------------------------------------------------

func read() {
    guard args.count >= 2, let pid = Int32(args[1]) else {
        print("usage: selector-allowed-probe read <pid> [attribute ...]"); exit(2)
    }
    let asked = args.count >= 3 ? Array(args.dropFirst(2)) : ["AXDisclosureLevel"]
    let tags = ["inherited-refused", "overridden-refused", "inherited-allowed"]

    func copy(_ e: AXUIElement, _ name: String) -> (AXError, AnyObject?) {
        var value: CFTypeRef?
        let error = AXUIElementCopyAttributeValue(e, name as CFString, &value)
        return (error, value)
    }
    func text(_ value: AnyObject?) -> String {
        guard let value else { return "nil" }
        if let s = value as? String { return "'\(s)'" }
        if let n = value as? NSNumber { return n.stringValue }
        if let a = value as? [AnyObject] { return "[\(a.count)]" }
        return "\(value)"
    }
    func children(_ e: AXUIElement) -> [AXUIElement] {
        (copy(e, kAXChildrenAttribute).1 as? [AXUIElement]) ?? []
    }
    func find(_ e: AXUIElement, _ depth: Int, into found: inout [String: AXUIElement]) {
        if let description = copy(e, kAXDescriptionAttribute).1 as? String, tags.contains(description) {
            found[description] = e
        }
        if depth == 0 { return }
        for child in children(e) { find(child, depth - 1, into: &found) }
    }

    log("reader pid=\(getpid()) trusted=\(AXIsProcessTrusted()) provider=\(pid) asking \(asked)")
    let application = AXUIElementCreateApplication(pid)
    var found: [String: AXUIElement] = [:]
    find(application, 6, into: &found)
    if found.isEmpty {
        let (error, _) = copy(application, kAXRoleAttribute)
        log("NONE of \(tags) found; application AXRole read = AXError(\(error.rawValue))")
        exit(1)
    }
    for pass in 1...2 {
        log("--- pass \(pass)")
        for tag in tags {
            guard let element = found[tag] else { log("\(tag): not found"); continue }
            var names: CFArray?
            let namesError = AXUIElementCopyAttributeNames(element, &names)
            let nameList = (names as? [String]) ?? []
            log("\(tag): role=\(text(copy(element, kAXRoleAttribute).1))"
                + " subrole=\(text(copy(element, kAXSubroleAttribute).1))"
                + " AXAttributeNames=AXError(\(namesError.rawValue)) count=\(nameList.count)"
                + (pass == 1 ? " \(nameList)" : ""))
            for attribute in asked {
                let (error, value) = copy(element, attribute)
                var settable = DarwinBoolean(false)
                let settableError = AXUIElementIsAttributeSettable(element, attribute as CFString, &settable)
                log("\(tag): \(attribute) read=AXError(\(error.rawValue)) value=\(text(value))"
                    + " inAttributeNames=\(nameList.contains(attribute))"
                    + " settable=\(settable.boolValue) settableRead=AXError(\(settableError.rawValue))")
            }
        }
        if pass == 1 { Thread.sleep(forTimeInterval: 1) }
    }
}

if mode == "serve" { serve() } else { read() }
