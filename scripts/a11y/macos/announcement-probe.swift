import Cocoa
import ApplicationServices

// Which registrations an announcement and a layout change reach, by the object they are posted on.
// The Limn bridge has three events with no element of their own to post on — an announcement, the
// model's INVALIDATED, and a structure change of the window root it elides — and posting on a node's
// element is not possible for any of them. The subjects it could use instead are the application,
// the window, the content view, or an element of ours; which of those an AX client registered on the
// application, on the window or on an element receives is a behaviour of AppKit's delivery, and is
// read here rather than assumed. (Whether VoiceOver SPEAKS an announcement from each subject is a
// reader run with the window in front, and is not what this reads.)
//
// One binary, two halves, so the provider and the client are always the same revision:
//
//   serve   A window whose content view carries a static text "target" and eight buttons, each an
//           NSAccessibilityElement subclass. Pressing a button posts, a tenth of a second later on
//           the main queue (never from inside the press), one notification on one subject:
//             announce-app      AXAnnouncementRequested on NSApp
//             announce-window   AXAnnouncementRequested on the window
//             announce-view     AXAnnouncementRequested on the content view
//             announce-element  AXAnnouncementRequested on the target element
//             layout-app        AXLayoutChanged on NSApp, AXUIElements = [target]
//             layout-window     AXLayoutChanged on the window, AXUIElements = [target]
//             layout-view       AXLayoutChanged on the content view, AXUIElements = [target]
//             layout-bare       AXLayoutChanged on the window with no user info
//           Every announcement carries AXAnnouncementKey = its button's name and AXPriorityKey = 90.
//   read    An unprivileged AX client. It registers AXAnnouncementRequested and AXLayoutChanged on
//           the application element, on the window element and on the target element, presses each
//           button in turn, and prints every notification delivered after each press with the
//           registration it arrived on, the element it names and its user info.
//
// Build with `swiftc -O -o announcement-probe announcement-probe.swift`. Start the provider in the
// GUI session as the logged-in user, take its pid from its own first line, run the client over plain
// ssh, then kill the provider by that pid.
//
// usage: announcement-probe serve [seconds]      (default 60)
//        announcement-probe read <pid>

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
    print("usage: announcement-probe serve [seconds]\n       announcement-probe read <pid>")
    exit(2)
}
let triggers = ["announce-app", "announce-window", "announce-view", "announce-element",
                "layout-app", "layout-window", "layout-view", "layout-bare"]

// ---- serve ----------------------------------------------------------------------------------------

final class Trigger: NSAccessibilityElement {
    var fire: () -> Void = {}
    override func accessibilityPerformPress() -> Bool {
        log("pressed \(accessibilityLabel() ?? "?")")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { self.fire() }
        return true
    }
}

var keep: [AnyObject] = []

func serve() {
    let seconds = args.count >= 2 ? Double(args[1]) ?? 60 : 60
    let app = NSApplication.shared
    app.setActivationPolicy(.accessory)
    let window = NSWindow(contentRect: NSRect(x: 280, y: 280, width: 360, height: 520),
                          styleMask: [.titled], backing: .buffered, defer: false)
    window.title = "announcement-probe"
    let content = NSView(frame: NSRect(x: 0, y: 0, width: 360, height: 520))
    window.contentView = content

    let target = NSAccessibilityElement()
    target.setAccessibilityRole(.staticText)
    target.setAccessibilityLabel("target")
    target.setAccessibilityParent(content)
    target.setAccessibilityFrameInParentSpace(NSRect(x: 20, y: 470, width: 320, height: 30))

    func announce(_ subject: Any, _ name: String) {
        let info: [NSAccessibility.NotificationUserInfoKey: Any] = [
            .announcement: name, .priority: NSAccessibilityPriorityLevel.high.rawValue]
        NSAccessibility.post(element: subject, notification: .announcementRequested, userInfo: info)
        log("posted AXAnnouncementRequested '\(name)' on \(type(of: subject))")
    }
    func layout(_ subject: Any, _ name: String, withElements: Bool) {
        if withElements {
            NSAccessibility.post(element: subject, notification: .layoutChanged,
                                 userInfo: [.uiElements: [target]])
        } else {
            NSAccessibility.post(element: subject, notification: .layoutChanged)
        }
        log("posted AXLayoutChanged for \(name) on \(type(of: subject)) uiElements=\(withElements)")
    }

    var children: [NSAccessibilityElement] = [target]
    for (index, name) in triggers.enumerated() {
        let trigger = Trigger()
        trigger.setAccessibilityRole(.button)
        trigger.setAccessibilityLabel(name)
        trigger.setAccessibilityParent(content)
        trigger.setAccessibilityFrameInParentSpace(
            NSRect(x: 20, y: 420 - CGFloat(index) * 50, width: 320, height: 40))
        switch name {
        case "announce-app": trigger.fire = { announce(NSApp as Any, name) }
        case "announce-window": trigger.fire = { announce(window, name) }
        case "announce-view": trigger.fire = { announce(content, name) }
        case "announce-element": trigger.fire = { announce(target, name) }
        case "layout-app": trigger.fire = { layout(NSApp as Any, name, withElements: true) }
        case "layout-window": trigger.fire = { layout(window, name, withElements: true) }
        case "layout-view": trigger.fire = { layout(content, name, withElements: true) }
        default: trigger.fire = { layout(window, name, withElements: false) }
        }
        children.append(trigger)
    }
    content.setAccessibilityChildren(children)
    keep.append(contentsOf: children as [AnyObject])
    keep.append(window)

    window.orderFrontRegardless()
    log("serving pid=\(getpid()) for \(Int(seconds))s")
    DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
        log("time is up; exiting")
        exit(0)
    }
    app.run()
}

// ---- read -----------------------------------------------------------------------------------------

func copy(_ e: AXUIElement, _ name: String) -> AnyObject? {
    var value: CFTypeRef?
    return AXUIElementCopyAttributeValue(e, name as CFString, &value) == .success ? value : nil
}
func children(_ e: AXUIElement) -> [AXUIElement] { (copy(e, kAXChildrenAttribute) as? [AXUIElement]) ?? [] }
func role(_ e: AXUIElement) -> String { (copy(e, kAXRoleAttribute) as? String) ?? "nil" }
func name(_ e: AXUIElement) -> String {
    (copy(e, kAXDescriptionAttribute) as? String) ?? (copy(e, kAXTitleAttribute) as? String) ?? ""
}
func find(_ e: AXUIElement, named wanted: String, _ depth: Int) -> AXUIElement? {
    if name(e) == wanted { return e }
    if depth == 0 { return nil }
    for child in children(e) { if let found = find(child, named: wanted, depth - 1) { return found } }
    return nil
}
func describe(_ info: CFDictionary?) -> String {
    guard let dictionary = info as NSDictionary?, dictionary.count > 0 else { return "{}" }
    var parts: [String] = []
    for (key, value) in dictionary {
        if let array = value as? [AnyObject] {
            let items = array.map { item -> String in
                CFGetTypeID(item) == AXUIElementGetTypeID()
                    ? "\(role(item as! AXUIElement)) '\(name(item as! AXUIElement))'" : "\(item)"
            }
            parts.append("\(key)=[\(items.joined(separator: ", "))]")
        } else {
            parts.append("\(key)=\(value)")
        }
    }
    return "{" + parts.sorted().joined(separator: "; ") + "}"
}

func read() {
    guard args.count >= 2, let pid = Int32(args[1]) else {
        print("usage: announcement-probe read <pid>"); exit(2)
    }
    log("reader pid=\(getpid()) trusted=\(AXIsProcessTrusted()) provider=\(pid)")
    let application = AXUIElementCreateApplication(pid)
    let windows = (copy(application, kAXWindowsAttribute) as? [AXUIElement]) ?? []
    guard let window = windows.first(where: { (copy($0, kAXTitleAttribute) as? String) == "announcement-probe" }),
          let target = find(window, named: "target", 4) else {
        log("NO window or target under pid \(pid)"); exit(1)
    }
    log("window children=\(children(window).map { "\(role($0)) '\(name($0))'" })")

    var observer: AXObserver?
    let created = AXObserverCreateWithInfoCallback(pid, { _, element, notification, info, refcon in
        let registration = refcon.map { String(cString: $0.assumingMemoryBound(to: CChar.self)) } ?? "?"
        log("NOTIFIED \(notification as String) on=\(registration) element=\(role(element)) '\(name(element))' userInfo=\(describe(info))")
    }, &observer)
    guard created == .success, let observer else { log("AXObserverCreate = AXError(\(created.rawValue))"); exit(1) }
    var tags: [UnsafeMutablePointer<CChar>] = []
    for (registration, element) in [("application", application), ("window", window), ("target", target)] {
        let tag = strdup(registration)!
        tags.append(tag)
        for notification in ["AXAnnouncementRequested", "AXLayoutChanged"] {
            let error = AXObserverAddNotification(observer, element, notification as CFString, tag)
            log("observe \(notification) on \(registration): AXError(\(error.rawValue))")
        }
    }
    CFRunLoopAddSource(CFRunLoopGetCurrent(), AXObserverGetRunLoopSource(observer), .defaultMode)
    func spin(_ seconds: Double) { CFRunLoopRunInMode(.defaultMode, seconds, false) }
    spin(1.0)
    log("--- quiet spell over (notifications above this line were not ours)")
    for trigger in triggers {
        guard let button = find(window, named: trigger, 4) else { log("NO button \(trigger)"); continue }
        let error = AXUIElementPerformAction(button, kAXPressAction as CFString)
        log("=== AXPress \(trigger) -> AXError(\(error.rawValue))")
        spin(1.2)
    }
    for tag in tags { free(tag) }
}

if mode == "serve" { serve() } else { read() }
