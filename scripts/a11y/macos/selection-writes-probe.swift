import Cocoa
import ApplicationServices

// What a NATIVE NSOutlineView does with a reader's selection writes, read through the AX API the way
// a reader writes them, for the two selection modes: whether AXSelected = true on a second row of a
// multi-select outline extends the selection or replaces it, what AXSelected = false does on the only
// selected row, what AXSelectedRows = [rows] and = [] do, and what a single-select outline does with
// the same writes. The macOS bridge maps each write onto the toolkit's SELECT (a click: replaces),
// ADD_TO_SELECTION and DESELECT, and which one a write means is not in a header.
//
// One binary, two halves, so the provider and the client are always the same revision:
//
//   serve   A window holding two view-based, one-column NSOutlineViews over four flat rows
//           (Alpha, Bravo, Charlie, Delta), each with Bravo selected: the left one with
//           allowsMultipleSelection = true (identifier "multi"), the right one with the default
//           single selection (identifier "single"). Both keep AppKit's default
//           allowsEmptySelection = true. Every selection change a delegate hears is logged.
//   read    An unprivileged AX client. It finds both AXOutlines under the provider's pid by
//           AXIdentifier, prints AXSelectedRows and every row's AXSelected with their settability,
//           registers an AXObserver for the selection, value and focus notifications on the
//           application element and on each outline, and makes these writes, printing every
//           notification delivered after each and the selection read back:
//             multi   1 AXSelected = true on Delta        2 AXSelected = false on Delta
//                     3 AXSelected = false on Bravo       4 AXSelectedRows = [Alpha, Charlie]
//                     5 AXSelectedRows = [Charlie]        6 AXSelectedRows = []
//                     7 AXSelected = true on Bravo
//             single  8 AXSelected = true on Delta        9 AXSelected = false on Delta
//                    10 AXSelectedRows = [Alpha]         11 AXSelectedRows = [Alpha, Charlie]
//                    12 AXSelectedRows = []
//
// Build with `swiftc -O -o selection-writes-probe selection-writes-probe.swift`. Start the provider in
// the GUI session as the logged-in user, take its pid from its own first line, run the client over
// plain ssh, then kill the provider by that pid.
//
// usage: selection-writes-probe serve [seconds]      (default 60)
//        selection-writes-probe read <pid>

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
    print("usage: selection-writes-probe serve [seconds]\n       selection-writes-probe read <pid>")
    exit(2)
}

let rowNames = ["Alpha", "Bravo", "Charlie", "Delta"]

// ---- serve ----------------------------------------------------------------------------------------

final class Source: NSObject, NSOutlineViewDataSource, NSOutlineViewDelegate {
    let label: String
    let items: [NSString] = rowNames.map { $0 as NSString }
    init(_ label: String) { self.label = label }

    func outlineView(_ outlineView: NSOutlineView, numberOfChildrenOfItem item: Any?) -> Int {
        item == nil ? items.count : 0
    }
    func outlineView(_ outlineView: NSOutlineView, child index: Int, ofItem item: Any?) -> Any {
        items[index]
    }
    func outlineView(_ outlineView: NSOutlineView, isItemExpandable item: Any) -> Bool { false }
    func outlineView(_ outlineView: NSOutlineView, viewFor tableColumn: NSTableColumn?, item: Any) -> NSView? {
        let cell = NSTableCellView(frame: NSRect(x: 0, y: 0, width: 140, height: 20))
        let text = NSTextField(labelWithString: (item as? String) ?? "?")
        text.frame = cell.bounds
        cell.addSubview(text)
        cell.textField = text
        return cell
    }
    func outlineViewSelectionDidChange(_ notification: Notification) {
        guard let outline = notification.object as? NSOutlineView else { return }
        let names = outline.selectedRowIndexes.compactMap { outline.item(atRow: $0) as? String }
        log("delegate \(label): selection now \(names)")
    }
}

func serve() {
    let seconds = args.count >= 2 ? Double(args[1]) ?? 60 : 60
    let app = NSApplication.shared
    app.setActivationPolicy(.accessory)

    let window = NSWindow(contentRect: NSRect(x: 240, y: 240, width: 340, height: 160),
                          styleMask: [.titled], backing: .buffered, defer: false)
    window.title = "selection-writes-probe"
    let content = NSView(frame: NSRect(x: 0, y: 0, width: 340, height: 160))
    var sources: [Source] = []
    for (index, label) in ["multi", "single"].enumerated() {
        let scroll = NSScrollView(frame: NSRect(x: 10 + 165 * index, y: 10, width: 155, height: 140))
        let outline = NSOutlineView(frame: scroll.bounds)
        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("name"))
        column.width = 140
        outline.addTableColumn(column)
        outline.outlineTableColumn = column
        outline.headerView = nil
        outline.identifier = NSUserInterfaceItemIdentifier(label)
        outline.allowsMultipleSelection = label == "multi"
        let source = Source(label)
        sources.append(source)
        outline.dataSource = source
        outline.delegate = source
        scroll.documentView = outline
        content.addSubview(scroll)
        outline.reloadData()
        outline.selectRowIndexes(IndexSet(integer: 1), byExtendingSelection: false)
        log("\(label): allowsMultipleSelection=\(outline.allowsMultipleSelection) allowsEmptySelection=\(outline.allowsEmptySelection)")
    }
    window.contentView = content

    window.orderFrontRegardless()
    log("serving pid=\(getpid()) for \(Int(seconds))s")
    DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
        log("time is up; exiting")
        exit(0)
    }
    withExtendedLifetime(sources) { app.run() }
}

// ---- read -----------------------------------------------------------------------------------------

func copy(_ e: AXUIElement, _ name: String) -> (AXError, AnyObject?) {
    var value: CFTypeRef?
    let error = AXUIElementCopyAttributeValue(e, name as CFString, &value)
    return (error, value)
}
func children(_ e: AXUIElement) -> [AXUIElement] {
    (copy(e, kAXChildrenAttribute).1 as? [AXUIElement]) ?? []
}
func role(_ e: AXUIElement) -> String { (copy(e, kAXRoleAttribute).1 as? String) ?? "nil" }
func identifier(_ e: AXUIElement) -> String { (copy(e, kAXIdentifierAttribute).1 as? String) ?? "nil" }
func findAll(_ e: AXUIElement, _ wanted: String, _ depth: Int, _ into: inout [AXUIElement]) {
    if role(e) == wanted { into.append(e); return }
    if depth == 0 { return }
    for child in children(e) { findAll(child, wanted, depth - 1, &into) }
}
func label(_ e: AXUIElement, _ depth: Int = 0) -> String {
    for name in [kAXTitleAttribute, kAXDescriptionAttribute, kAXValueAttribute] {
        if let v = copy(e, name).1 as? String, !v.isEmpty { return v }
    }
    if depth > 4 { return "" }
    for child in children(e) {
        let t = label(child, depth + 1)
        if !t.isEmpty { return t }
    }
    return ""
}
func text(_ value: AnyObject?) -> String {
    guard let value else { return "nil" }
    if CFGetTypeID(value) == AXUIElementGetTypeID() {
        let element = value as! AXUIElement
        return "\(role(element)) '\(label(element))'"
    }
    if let s = value as? String { return "'\(s)'" }
    if let n = value as? NSNumber { return n.stringValue }
    if let a = value as? [AnyObject] { return "[" + a.map { text($0) }.joined(separator: ", ") + "]" }
    return "\(value)"
}
func show(_ e: AXUIElement, _ name: String) -> String {
    let (error, value) = copy(e, name)
    var settable = DarwinBoolean(false)
    let settableError = AXUIElementIsAttributeSettable(e, name as CFString, &settable)
    return "\(name)=" + (error == .success ? text(value) : "AXError(\(error.rawValue))")
        + " settable=\(settableError == .success ? String(settable.boolValue) : "AXError(\(settableError.rawValue))")"
}

func read() {
    guard args.count >= 2, let pid = Int32(args[1]) else {
        print("usage: selection-writes-probe read <pid>"); exit(2)
    }
    log("reader pid=\(getpid()) trusted=\(AXIsProcessTrusted()) provider=\(pid)")
    let application = AXUIElementCreateApplication(pid)
    var outlines: [AXUIElement] = []
    findAll(application, "AXOutline", 10, &outlines)
    guard let multi = outlines.first(where: { identifier($0) == "multi" }),
          let single = outlines.first(where: { identifier($0) == "single" }) else {
        log("outlines found: \(outlines.map { identifier($0) }); expected multi and single")
        exit(1)
    }
    let outlineNamed = [("multi", multi), ("single", single)]

    func rows(_ outline: AXUIElement) -> [AXUIElement] {
        (copy(outline, kAXRowsAttribute).1 as? [AXUIElement]) ?? []
    }
    func dump(_ heading: String) {
        log("--- \(heading)")
        for (name, outline) in outlineNamed {
            log("\(name) \(show(outline, kAXSelectedRowsAttribute))")
            let selected = rows(outline).map { row -> String in
                let on = (copy(row, kAXSelectedAttribute).1 as? NSNumber)?.boolValue ?? false
                return "\(label(row))=\(on ? 1 : 0)"
            }
            log("\(name) AXSelected per row: \(selected.joined(separator: " "))")
        }
    }
    dump("initial")
    for (name, outline) in outlineNamed {
        if let first = rows(outline).first { log("\(name) row \(show(first, kAXSelectedAttribute))") }
    }

    let notifications = ["AXSelectedRowsChanged", "AXSelectedChildrenChanged", "AXSelectedCellsChanged",
                         "AXFocusedUIElementChanged", "AXValueChanged", "AXRowCountChanged"]
    var observer: AXObserver?
    let created = AXObserverCreateWithInfoCallback(pid, { _, element, notification, _, refcon in
        let registration = refcon.map { String(cString: $0.assumingMemoryBound(to: CChar.self)) } ?? "?"
        log("NOTIFIED \(notification as String) on=\(registration) element=\(text(element)) id=\(identifier(element))")
    }, &observer)
    guard created == .success, let observer else { log("AXObserverCreate = AXError(\(created.rawValue))"); exit(1) }
    let registrations = [("application", application), ("multi", multi), ("single", single)]
    var tags: [UnsafeMutablePointer<CChar>] = []
    for notification in notifications {
        var line = "observe \(notification):"
        for (name, element) in registrations {
            let tag = strdup(name)!
            tags.append(tag)
            line += " \(name)=AXError(\(AXObserverAddNotification(observer, element, notification as CFString, tag).rawValue))"
        }
        log(line)
    }
    CFRunLoopAddSource(CFRunLoopGetCurrent(), AXObserverGetRunLoopSource(observer), .defaultMode)
    func spin(_ seconds: Double) { CFRunLoopRunInMode(.defaultMode, seconds, false) }
    spin(1.0)
    log("--- quiet spell over (notifications above this line were not ours)")

    func row(_ outline: AXUIElement, _ name: String) -> AXUIElement? { rows(outline).first { label($0) == name } }
    func step(_ number: Int, _ what: String, _ act: () -> AXError) {
        let error = act()
        log("=== step \(number): \(what) -> AXError(\(error.rawValue))")
        spin(1.2)
        dump("after step \(number)")
    }
    func selected(_ outline: AXUIElement, _ name: String, _ on: Bool) -> AXError {
        guard let r = row(outline, name) else { return .failure }
        return AXUIElementSetAttributeValue(r, kAXSelectedAttribute as CFString, on ? kCFBooleanTrue : kCFBooleanFalse)
    }
    func selectedRows(_ outline: AXUIElement, _ names: [String]) -> AXError {
        let wanted = names.compactMap { row(outline, $0) }
        guard wanted.count == names.count else { return .failure }
        return AXUIElementSetAttributeValue(outline, kAXSelectedRowsAttribute as CFString, wanted as CFArray)
    }
    step(1, "multi: AXSelected = true on Delta") { selected(multi, "Delta", true) }
    step(2, "multi: AXSelected = false on Delta") { selected(multi, "Delta", false) }
    step(3, "multi: AXSelected = false on Bravo") { selected(multi, "Bravo", false) }
    step(4, "multi: AXSelectedRows = [Alpha, Charlie]") { selectedRows(multi, ["Alpha", "Charlie"]) }
    step(5, "multi: AXSelectedRows = [Charlie]") { selectedRows(multi, ["Charlie"]) }
    step(6, "multi: AXSelectedRows = []") { selectedRows(multi, []) }
    step(7, "multi: AXSelected = true on Bravo") { selected(multi, "Bravo", true) }
    step(8, "single: AXSelected = true on Delta") { selected(single, "Delta", true) }
    step(9, "single: AXSelected = false on Delta") { selected(single, "Delta", false) }
    step(10, "single: AXSelectedRows = [Alpha]") { selectedRows(single, ["Alpha"]) }
    step(11, "single: AXSelectedRows = [Alpha, Charlie]") { selectedRows(single, ["Alpha", "Charlie"]) }
    step(12, "single: AXSelectedRows = []") { selectedRows(single, []) }
    tags.forEach { free($0) }
}

if mode == "serve" { serve() } else { read() }
