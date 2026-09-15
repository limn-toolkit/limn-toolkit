import Cocoa
import ApplicationServices

// What a NATIVE NSOutlineView answers and posts, read through the AX API the way a reader reads it,
// so that the bridge vends an outline the platform's own shape instead of a shape reasoned out at a
// desk. The macOS bridge's outline rows owe AXDisclosureLevel, AXIndex, AXDisclosing,
// AXDisclosedRows and AXDisclosedByRow, and two facts decide how they are written: the base of the
// level and of the index (the model counts both from one), and which element a row's expansion
// and a selection move are posted on. Neither is in a header.
//
// One binary, two halves, so the provider and the client are always the same revision:
//
//   serve   A window holding an NSOutlineView (view-based, one column) over this data, with
//           Documents and Reports open, Pictures closed, and Notes selected:
//             Documents
//               Reports
//                 Q1
//               Notes
//             Pictures        (closed; holds Trip)
//             Readme
//           The window is ordered front without activating the application. Every expansion,
//           collapse and selection change the outline's delegate hears is logged, so a write a
//           client makes shows up as having reached the view.
//   read    An unprivileged AX client. It finds the AXOutline under the provider's pid and prints:
//           the outline's attribute names and table attributes; for every row its attribute
//           names (once), role, subrole, AXIndex, AXDisclosureLevel, AXDisclosing, AXDisclosedByRow
//           (as the row index it names), AXDisclosedRows (as indices), AXExpanded, AXSelected, the
//           settability of each of those, its action names and its children's roles. Then it
//           registers an AXObserver for the row, selection and focus notifications on the
//           application element and, separately, on the outline, and makes five writes through
//           the AX API, printing every notification delivered after each with the registration it
//           arrived on and the element it names:
//             1 AXDisclosing = true on Pictures      2 AXDisclosing = false on Pictures
//             3 AXSelected = true on Readme          4 AXSelectedRows = [Documents] on the outline
//             5 AXScrollToVisible performed on Readme
//           and re-reads the rows after each.
//
// Build with `swiftc -O -o outline-probe outline-probe.swift`. Start the provider in the GUI
// session as the logged-in user, take its pid from its own first line, run the client over plain
// ssh, then kill the provider by that pid.
//
// usage: outline-probe serve [seconds]      (default 60)
//        outline-probe read <pid>

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
    print("usage: outline-probe serve [seconds]\n       outline-probe read <pid>")
    exit(2)
}

// ---- serve ----------------------------------------------------------------------------------------

final class Item: NSObject {
    let name: String
    let children: [Item]
    init(_ name: String, _ children: [Item] = []) {
        self.name = name
        self.children = children
    }
}

final class Source: NSObject, NSOutlineViewDataSource, NSOutlineViewDelegate {
    let roots: [Item]
    init(_ roots: [Item]) { self.roots = roots }

    func outlineView(_ outlineView: NSOutlineView, numberOfChildrenOfItem item: Any?) -> Int {
        (item as? Item)?.children.count ?? roots.count
    }
    func outlineView(_ outlineView: NSOutlineView, child index: Int, ofItem item: Any?) -> Any {
        (item as? Item)?.children[index] ?? roots[index]
    }
    func outlineView(_ outlineView: NSOutlineView, isItemExpandable item: Any) -> Bool {
        !((item as? Item)?.children.isEmpty ?? true)
    }
    func outlineView(_ outlineView: NSOutlineView, viewFor tableColumn: NSTableColumn?, item: Any) -> NSView? {
        let cell = NSTableCellView(frame: NSRect(x: 0, y: 0, width: 200, height: 20))
        let text = NSTextField(labelWithString: (item as? Item)?.name ?? "?")
        text.frame = cell.bounds
        cell.addSubview(text)
        cell.textField = text
        return cell
    }
    func outlineViewItemDidExpand(_ notification: Notification) {
        log("delegate: expanded \(name(notification))")
    }
    func outlineViewItemDidCollapse(_ notification: Notification) {
        log("delegate: collapsed \(name(notification))")
    }
    func outlineViewSelectionDidChange(_ notification: Notification) {
        guard let outline = notification.object as? NSOutlineView else { return }
        let names = outline.selectedRowIndexes.compactMap { (outline.item(atRow: $0) as? Item)?.name }
        log("delegate: selection now \(names)")
    }
    private func name(_ notification: Notification) -> String {
        (notification.userInfo?["NSObject"] as? Item)?.name ?? "?"
    }
}

func serve() {
    let seconds = args.count >= 2 ? Double(args[1]) ?? 60 : 60
    let app = NSApplication.shared
    app.setActivationPolicy(.accessory)

    let q1 = Item("Q1")
    let reports = Item("Reports", [q1])
    let notes = Item("Notes")
    let documents = Item("Documents", [reports, notes])
    let pictures = Item("Pictures", [Item("Trip")])
    let readme = Item("Readme")
    let source = Source([documents, pictures, readme])

    let window = NSWindow(contentRect: NSRect(x: 240, y: 240, width: 320, height: 260),
                          styleMask: [.titled], backing: .buffered, defer: false)
    window.title = "outline-probe"
    let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 320, height: 260))
    let outline = NSOutlineView(frame: scroll.bounds)
    let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("name"))
    column.width = 280
    outline.addTableColumn(column)
    outline.outlineTableColumn = column
    outline.headerView = nil
    outline.dataSource = source
    outline.delegate = source
    scroll.documentView = outline
    window.contentView = scroll
    outline.reloadData()
    outline.expandItem(documents)
    outline.expandItem(reports)
    outline.selectRowIndexes(IndexSet(integer: outline.row(forItem: notes)), byExtendingSelection: false)

    window.orderFrontRegardless()
    log("serving pid=\(getpid()) rows=\(outline.numberOfRows) for \(Int(seconds))s")
    DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
        log("time is up; exiting")
        exit(0)
    }
    withExtendedLifetime(source) { app.run() }
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
func find(_ e: AXUIElement, _ wanted: String, _ depth: Int) -> AXUIElement? {
    if role(e) == wanted { return e }
    if depth == 0 { return nil }
    for child in children(e) { if let found = find(child, wanted, depth - 1) { return found } }
    return nil
}
/// The first non-empty title, description or value under an element: a row's cell text.
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

var rowsNow: [AXUIElement] = []

func rowIndex(_ element: AXUIElement) -> String {
    for (index, row) in rowsNow.enumerated() where CFEqual(row, element) { return "row#\(index)" }
    return "not-a-row"
}
func text(_ value: AnyObject?) -> String {
    guard let value else { return "nil" }
    if CFGetTypeID(value) == AXUIElementGetTypeID() {
        let element = value as! AXUIElement
        return "\(role(element)) \(rowIndex(element)) '\(label(element))'"
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
func names(_ e: AXUIElement) -> [String] {
    var list: CFArray?
    AXUIElementCopyAttributeNames(e, &list)
    return (list as? [String]) ?? []
}
func actions(_ e: AXUIElement) -> [String] {
    var list: CFArray?
    let error = AXUIElementCopyActionNames(e, &list)
    return error == .success ? ((list as? [String]) ?? []) : ["AXError(\(error.rawValue))"]
}

let rowAttributes = ["AXIndex", "AXDisclosureLevel", "AXDisclosing", "AXDisclosedByRow",
                     "AXDisclosedRows", "AXExpanded", "AXSelected", "AXFocused", "AXValue"]

func dumpRows(_ outline: AXUIElement, _ heading: String, withNames: Bool) {
    rowsNow = (copy(outline, kAXRowsAttribute).1 as? [AXUIElement]) ?? []
    log("--- \(heading): \(rowsNow.count) rows")
    for attribute in ["AXRows", "AXVisibleRows", "AXSelectedRows", "AXSelectedChildren",
                      "AXSelectedCells", "AXRowCount", "AXColumns", "AXIndex", "AXDisclosureLevel",
                      "AXExpanded", "AXFocused"] {
        log("outline \(show(outline, attribute))")
    }
    for (index, row) in rowsNow.enumerated() {
        let subrole = (copy(row, kAXSubroleAttribute).1 as? String) ?? "nil"
        log("row#\(index) '\(label(row))' role=\(role(row)) subrole=\(subrole)")
        if withNames { log("row#\(index) AXAttributeNames=\(names(row))") }
        for attribute in rowAttributes { log("row#\(index) \(show(row, attribute))") }
        log("row#\(index) actions=\(actions(row)) children=\(children(row).map { role($0) })")
    }
}

func read() {
    guard args.count >= 2, let pid = Int32(args[1]) else {
        print("usage: outline-probe read <pid>"); exit(2)
    }
    log("reader pid=\(getpid()) trusted=\(AXIsProcessTrusted()) provider=\(pid)")
    let application = AXUIElementCreateApplication(pid)
    guard let outline = find(application, "AXOutline", 8) else {
        log("NO AXOutline under pid \(pid); application AXRole read = AXError(\(copy(application, kAXRoleAttribute).0.rawValue))")
        exit(1)
    }
    log("outline AXAttributeNames=\(names(outline))")
    log("outline actions=\(actions(outline))")
    dumpRows(outline, "initial", withNames: true)

    let notifications = ["AXRowExpanded", "AXRowCollapsed", "AXRowCountChanged",
                         "AXSelectedRowsChanged", "AXSelectedChildrenChanged", "AXSelectedCellsChanged",
                         "AXFocusedUIElementChanged", "AXValueChanged", "AXLayoutChanged",
                         "AXUIElementDestroyed", "AXCreated"]
    var observer: AXObserver?
    let created = AXObserverCreateWithInfoCallback(pid, { _, element, notification, info, refcon in
        let registration = refcon.map { String(cString: $0.assumingMemoryBound(to: CChar.self)) } ?? "?"
        let subrole = (copy(element, kAXSubroleAttribute).1 as? String) ?? "nil"
        let infoText = (info as NSDictionary?).map { "\($0)" } ?? "nil"
        log("NOTIFIED \(notification as String) on=\(registration) element=\(text(element)) subrole=\(subrole) userInfo=\(infoText)")
    }, &observer)
    guard created == .success, let observer else { log("AXObserverCreate = AXError(\(created.rawValue))"); exit(1) }
    let onApplication = strdup("application")!
    let onOutline = strdup("outline")!
    for notification in notifications {
        let a = AXObserverAddNotification(observer, application, notification as CFString, onApplication)
        let o = AXObserverAddNotification(observer, outline, notification as CFString, onOutline)
        log("observe \(notification): application=AXError(\(a.rawValue)) outline=AXError(\(o.rawValue))")
    }
    CFRunLoopAddSource(CFRunLoopGetCurrent(), AXObserverGetRunLoopSource(observer), .defaultMode)
    func spin(_ seconds: Double) { CFRunLoopRunInMode(.defaultMode, seconds, false) }
    spin(1.0)
    log("--- quiet spell over (notifications above this line were not ours)")

    func rowNamed(_ name: String) -> AXUIElement? { rowsNow.first { label($0) == name } }
    func step(_ number: Int, _ what: String, _ act: () -> AXError) {
        let error = act()
        log("=== step \(number): \(what) -> AXError(\(error.rawValue))")
        spin(1.5)
        dumpRows(outline, "after step \(number)", withNames: false)
    }
    step(1, "AXDisclosing = true on Pictures") {
        guard let row = rowNamed("Pictures") else { return .failure }
        return AXUIElementSetAttributeValue(row, "AXDisclosing" as CFString, kCFBooleanTrue)
    }
    step(2, "AXDisclosing = false on Pictures") {
        guard let row = rowNamed("Pictures") else { return .failure }
        return AXUIElementSetAttributeValue(row, "AXDisclosing" as CFString, kCFBooleanFalse)
    }
    step(3, "AXSelected = true on Readme") {
        guard let row = rowNamed("Readme") else { return .failure }
        return AXUIElementSetAttributeValue(row, kAXSelectedAttribute as CFString, kCFBooleanTrue)
    }
    step(4, "AXSelectedRows = [Documents] on the outline") {
        guard let row = rowNamed("Documents") else { return .failure }
        return AXUIElementSetAttributeValue(outline, kAXSelectedRowsAttribute as CFString, [row] as CFArray)
    }
    step(5, "AXScrollToVisible performed on Readme") {
        guard let row = rowNamed("Readme") else { return .failure }
        return AXUIElementPerformAction(row, "AXScrollToVisible" as CFString)
    }
    free(onApplication)
    free(onOutline)
}

if mode == "serve" { serve() } else { read() }
