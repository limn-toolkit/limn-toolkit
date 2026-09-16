import Cocoa
import ApplicationServices

// What a NATIVE NSTableView used as a LIST posts, and what a client reads back from an
// NSAccessibilityElement that answers NSNotFound for its index — the two macOS facts the phase-3
// critic listed as used without a reading.
//
//   1 The list's selection.  AxNotifications maps a SELECTION_CHANGED on a container whose members
//     are rows to AXSelectedRowsChanged, for an outline AND for a list. The outline half was read
//     (outline-probe.swift, 2026-09-15: a selection write delivered AXSelectedRowsChanged only);
//     the list half was inferred from it, and a list is a different native view. This reads it.
//   2 AXRowCountChanged on a trigger that is not a disclosure.  The bridge queues a row-count
//     change whenever a held container's row count differs between two snapshots — a lazy load, a
//     refresh, a model adding roots — and only the disclosure trigger was read. Here the provider
//     changes its row count with reloadData(), no row expanding or collapsing, and the reader
//     prints what is delivered.
//   3 NSNotFound as AXIndex.  AxGrid.NOT_FOUND[0] is Long.MAX_VALUE, answered for a row whose
//     number is unknown, and no reading named NSNotFound. The provider prints it from the running
//     Foundation, and hangs an NSAccessibilityElement of role AXRow that answers NSNotFound for
//     accessibilityIndex off a plain NSView — which is exactly how the bridge vends an element —
//     so the reader can print what an out-of-process client actually receives for it.
//
// One binary, two halves, so the provider and the client are always the same revision:
//
//   serve   A window holding an NSTableView (view-based, one column, no header) over four rows,
//           with row 1 selected, and beside it a view whose one accessibility child is the
//           NSNotFound row. The window is ordered front WITHOUT activating the application. Every
//           selection change the delegate hears is logged. The row count then changes twice on a
//           timer and with no disclosure at all: a fifth row appears at +14s and two rows go at
//           +18s, each followed by reloadData().
//   read    An unprivileged AX client. It finds the AXTable and the AXRow with no index under the
//           provider's pid, prints their attributes, registers an AXObserver for the selection,
//           row-count, value and focus notifications on the application element and, separately, on
//           the table, and then: writes AXSelected = true on row 2, writes AXSelectedRows = [row 0,
//           row 3] on the table, and spins past both of the provider's row-count changes, printing
//           every notification delivered with the registration it arrived on.
//
// Build with `swiftc -O -o list-probe list-probe.swift`. Start the provider in the GUI session as
// the logged-in user, take its pid from its own first line, run the client over plain ssh, then kill
// the provider by that pid.
//
// usage: list-probe serve [seconds]      (default 40)
//        list-probe read <pid>

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
    print("usage: list-probe serve [seconds]\n       list-probe read <pid>")
    exit(2)
}

// ---- serve ----------------------------------------------------------------------------------------

/// An accessibility element that answers NSNotFound for its index, vended the way the Limn bridge
/// vends one: an NSAccessibilityElement hung off a plain view's accessibilityChildren.
final class RowWithNoIndex: NSAccessibilityElement {
    override func accessibilityRole() -> NSAccessibility.Role? { .row }
    override func accessibilityLabel() -> String? { "the row with no index" }
    override func accessibilityIndex() -> Int { NSNotFound }
}

final class Rows: NSObject, NSTableViewDataSource, NSTableViewDelegate {
    var names: [String]
    init(_ names: [String]) { self.names = names }

    func numberOfRows(in tableView: NSTableView) -> Int { names.count }
    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        let cell = NSTableCellView(frame: NSRect(x: 0, y: 0, width: 200, height: 20))
        let text = NSTextField(labelWithString: names[row])
        text.frame = cell.bounds
        cell.addSubview(text)
        cell.textField = text
        return cell
    }
    func tableViewSelectionDidChange(_ notification: Notification) {
        guard let table = notification.object as? NSTableView else { return }
        log("delegate: selection now \(table.selectedRowIndexes.map { $0 })")
    }
}

func serve() {
    let seconds = args.count >= 2 ? Double(args[1]) ?? 40 : 40
    let app = NSApplication.shared
    app.setActivationPolicy(.accessory)

    let rows = Rows(["Apples", "Bananas", "Cherries", "Dates"])

    let window = NSWindow(contentRect: NSRect(x: 240, y: 240, width: 380, height: 260),
                          styleMask: [.titled], backing: .buffered, defer: false)
    window.title = "list-probe"
    let content = NSView(frame: NSRect(x: 0, y: 0, width: 380, height: 260))
    let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 300, height: 260))
    let table = NSTableView(frame: scroll.bounds)
    let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("name"))
    column.width = 260
    table.addTableColumn(column)
    table.headerView = nil
    table.dataSource = rows
    table.delegate = rows
    scroll.documentView = table
    content.addSubview(scroll)

    // The NSNotFound row, beside the list and not in it, so that nothing about the native table is
    // disturbed by it.
    let beside = NSView(frame: NSRect(x: 300, y: 0, width: 80, height: 260))
    let noIndex = RowWithNoIndex()
    noIndex.setAccessibilityParent(beside)
    noIndex.setAccessibilityFrameInParentSpace(NSRect(x: 0, y: 0, width: 80, height: 20))
    beside.setAccessibilityElement(true)
    beside.setAccessibilityRole(.group)
    beside.setAccessibilityLabel("beside")
    beside.setAccessibilityChildren([noIndex])
    content.addSubview(beside)
    window.contentView = content

    table.reloadData()
    table.selectRowIndexes(IndexSet(integer: 1), byExtendingSelection: false)

    window.orderFrontRegardless()
    log("serving pid=\(getpid()) rows=\(table.numberOfRows) for \(Int(seconds))s")
    log("NSNotFound=\(NSNotFound) hex=0x\(String(NSNotFound, radix: 16)) "
        + "NSIntegerMax=\(Int.max) equal=\(NSNotFound == Int.max) "
        + "MemoryLayout<Int>.size=\(MemoryLayout<Int>.size)")
    log("RowWithNoIndex accessibilityIndex()=\(noIndex.accessibilityIndex())")

    // Two row-count changes with NO disclosure anywhere: this view has none.
    DispatchQueue.main.asyncAfter(deadline: .now() + 14) {
        rows.names.append("Elderberries")
        table.reloadData()
        log("provider: appended a row, reloadData(); rows=\(table.numberOfRows)")
    }
    DispatchQueue.main.asyncAfter(deadline: .now() + 18) {
        rows.names.removeLast(2)
        table.reloadData()
        log("provider: removed two rows, reloadData(); rows=\(table.numberOfRows)")
    }
    DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
        log("time is up; exiting")
        exit(0)
    }
    withExtendedLifetime(rows) { app.run() }
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
func findAll(_ e: AXUIElement, _ wanted: String, _ depth: Int, _ into: inout [AXUIElement]) {
    if role(e) == wanted { into.append(e) }
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

func dumpTable(_ table: AXUIElement, _ heading: String, withNames: Bool) {
    rowsNow = (copy(table, kAXRowsAttribute).1 as? [AXUIElement]) ?? []
    log("--- \(heading): \(rowsNow.count) rows")
    if withNames { log("table AXAttributeNames=\(names(table))") }
    for attribute in ["AXRole", "AXSubrole", "AXRows", "AXVisibleRows", "AXSelectedRows",
                      "AXSelectedChildren", "AXSelectedCells", "AXRowCount", "AXColumns"] {
        log("table \(show(table, attribute))")
    }
    for (index, row) in rowsNow.enumerated() {
        let subrole = (copy(row, kAXSubroleAttribute).1 as? String) ?? "nil"
        log("row#\(index) '\(label(row))' role=\(role(row)) subrole=\(subrole) "
            + "\(show(row, "AXIndex")) \(show(row, "AXSelected"))")
    }
}

func read() {
    guard args.count >= 2, let pid = Int32(args[1]) else {
        print("usage: list-probe read <pid>"); exit(2)
    }
    log("reader pid=\(getpid()) trusted=\(AXIsProcessTrusted()) provider=\(pid)")
    let application = AXUIElementCreateApplication(pid)
    guard let table = find(application, "AXTable", 10) else {
        log("NO AXTable under pid \(pid); application AXRole read = AXError(\(copy(application, kAXRoleAttribute).0.rawValue))")
        exit(1)
    }
    dumpTable(table, "initial", withNames: true)

    // The NSNotFound row: every AXRow under the application that is not one of the table's.
    var allRows: [AXUIElement] = []
    findAll(application, "AXRow", 10, &allRows)
    for row in allRows where !rowsNow.contains(where: { CFEqual($0, row) }) {
        log("--- an element outside the list that answers AXRow")
        log("outsider AXAttributeNames=\(names(row))")
        for attribute in ["AXRole", "AXLabel", "AXDescription", "AXIndex"] {
            log("outsider \(show(row, attribute))")
        }
        let (error, value) = copy(row, "AXIndex")
        if error == .success, let number = value as? NSNumber {
            log("outsider AXIndex as Int64=\(number.int64Value) hex=0x\(String(number.int64Value, radix: 16)) "
                + "objCType=\(String(cString: number.objCType)) equalsNSNotFound=\(number.intValue == NSNotFound)")
        }
    }

    let notifications = ["AXSelectedRowsChanged", "AXSelectedChildrenChanged", "AXSelectedCellsChanged",
                         "AXRowCountChanged", "AXRowExpanded", "AXRowCollapsed",
                         "AXFocusedUIElementChanged", "AXValueChanged", "AXLayoutChanged",
                         "AXUIElementDestroyed", "AXCreated"]
    var observer: AXObserver?
    let created = AXObserverCreateWithInfoCallback(pid, { _, element, notification, info, refcon in
        let registration = refcon.map { String(cString: $0.assumingMemoryBound(to: CChar.self)) } ?? "?"
        let infoText = (info as NSDictionary?).map { "\($0)" } ?? "nil"
        log("NOTIFIED \(notification as String) on=\(registration) element=\(role(element)) userInfo=\(infoText)")
    }, &observer)
    guard created == .success, let observer else { log("AXObserverCreate = AXError(\(created.rawValue))"); exit(1) }
    let onApplication = strdup("application")!
    let onTable = strdup("table")!
    for notification in notifications {
        let a = AXObserverAddNotification(observer, application, notification as CFString, onApplication)
        let t = AXObserverAddNotification(observer, table, notification as CFString, onTable)
        log("observe \(notification): application=AXError(\(a.rawValue)) table=AXError(\(t.rawValue))")
    }
    CFRunLoopAddSource(CFRunLoopGetCurrent(), AXObserverGetRunLoopSource(observer), .defaultMode)
    func spin(_ seconds: Double) { CFRunLoopRunInMode(.defaultMode, seconds, false) }
    spin(1.0)
    log("--- quiet spell over (notifications above this line were not ours)")

    func step(_ number: Int, _ what: String, _ act: () -> AXError) {
        let error = act()
        log("=== step \(number): \(what) -> AXError(\(error.rawValue))")
        spin(1.5)
        dumpTable(table, "after step \(number)", withNames: false)
    }
    step(1, "AXSelected = true on row#2") {
        guard rowsNow.count > 2 else { return .failure }
        return AXUIElementSetAttributeValue(rowsNow[2], kAXSelectedAttribute as CFString, kCFBooleanTrue)
    }
    step(2, "AXSelectedRows = [row#0, row#3] on the table") {
        guard rowsNow.count > 3 else { return .failure }
        let wanted = [rowsNow[0], rowsNow[3]]
        return AXUIElementSetAttributeValue(table, kAXSelectedRowsAttribute as CFString, wanted as CFArray)
    }
    log("=== waiting for the provider's two row-count changes (no disclosure is involved)")
    spin(8.0)
    dumpTable(table, "after the provider appended a row", withNames: false)
    spin(6.0)
    dumpTable(table, "after the provider removed two rows", withNames: false)
    free(onApplication)
    free(onTable)
}

if mode == "serve" { serve() } else { read() }
