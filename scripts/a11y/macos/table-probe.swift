import Cocoa
import ApplicationServices
import ObjectiveC

// What a NATIVE NSTableView vends for its columns, its header and its sort direction, read through
// the AX API the way a reader reads it, and whether the same shapes reach a client when they are
// built the way the Limn bridge builds its elements (NSAccessibilityElement subclasses, methods added
// with class_addMethod, the legacy attribute pair overridden). Decision 34 of 2026-09-13: "a native
// NSTableView decides" what a Limn table answers for AXColumns; decision 36: a header cell carries
// its sort direction, read off the platform before code.
//
// One binary, two halves, so the provider and the client are always the same revision:
//
//   serve   Three windows, ordered front without activating the application:
//             "native-table"     a view-based NSTableView, columns Name, Qty (sortable) and Note
//                                (not sortable), five rows, sorted by Qty ascending, row 1 selected,
//                                multiple selection allowed, header shown
//             "native-headless"  the same shape with the header view removed, two columns, two rows
//             "built-table"      NSAccessibilityElement subclasses, as the bridge builds them: a table
//                                (AXTable) whose -accessibilityRows, -accessibilityColumns,
//                                -accessibilityCellForColumn:row:, -accessibilityHeader,
//                                -accessibilityAttributeValue: and -accessibilityAttributeNames
//                                (both forwarding to NSAccessibilityElement's own, as the bridge's
//                                busy override does) and -isAccessibilitySelectorAllowed: are added
//                                with class_addMethod; two rows of two cells; a header group of two
//                                header buttons whose -accessibilitySortDirection is added with
//                                class_addMethod answering 1 and 2; and two column elements that are
//                                plain NSAccessibilityElement instances carrying only stored setters
//                                (role, index, children, header, parent, frame)
//           Every sort change the native tables' data sources hear is logged, and every added method
//           logs when it is entered.
//   read    An unprivileged AX client. For each table: its attribute and parameterized attribute
//           names, AXRowCount, AXColumnCount, AXColumns, AXVisibleColumns, AXSelectedColumns,
//           AXColumnTitles, AXHeader, AXColumnHeaderUIElements, AXSortDirection, AXSelectedRows,
//           AXSelectedCells and its children's roles; every column's role, subrole, role
//           description, attribute names, actions, AXIndex, AXHeader, AXChildren, AXRows,
//           AXVisibleRows, AXVisibleCells, AXSelected, AXSortDirection, AXTitle, AXFrame and parent;
//           the header group's attribute names and each header child's role, subrole, title,
//           actions, attribute names, AXSortDirection and AXIndex; every row's role, subrole, AXIndex
//           and children; each cell of the second row's role, attribute names, index ranges, column
//           headers and parent; AXCellForColumnAndRow at a few points in and out of range. Then an
//           AXObserver on the application, the native table and its header records what AXPress on
//           the Name header, twice, delivers, and the sort directions are read again after each.
//
// Build with `swiftc -O -o table-probe table-probe.swift`. Start the provider in the GUI session as
// the logged-in user, take its pid from its own first line, run the client over plain ssh, then kill
// the provider by that pid.
//
// usage: table-probe serve [seconds]      (default 60)
//        table-probe read <pid>

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
    print("usage: table-probe serve [seconds]\n       table-probe read <pid>")
    exit(2)
}

// ---- serve ----------------------------------------------------------------------------------------

final class Rows: NSObject, NSTableViewDataSource, NSTableViewDelegate {
    var rows: [[String]]
    let tag: String
    init(_ tag: String, _ rows: [[String]]) { self.tag = tag; self.rows = rows }
    func numberOfRows(in tableView: NSTableView) -> Int { rows.count }
    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        guard let tableColumn, let column = tableView.tableColumns.firstIndex(of: tableColumn) else { return nil }
        let cell = NSTableCellView(frame: NSRect(x: 0, y: 0, width: tableColumn.width, height: 20))
        let text = NSTextField(labelWithString: rows[row][column])
        text.frame = cell.bounds
        cell.addSubview(text)
        cell.textField = text
        return cell
    }
    func tableView(_ tableView: NSTableView, sortDescriptorsDidChange oldDescriptors: [NSSortDescriptor]) {
        let now = tableView.sortDescriptors.map { "\($0.key ?? "?") \($0.ascending ? "ascending" : "descending")" }
        log("data source \(tag): sort descriptors now \(now)")
        tableView.reloadData()
    }
}

func nativeTable(_ title: String, _ origin: NSPoint, _ columns: [(String, Bool)], _ source: Rows,
                 header: Bool) -> NSTableView {
    let window = NSWindow(contentRect: NSRect(origin: origin, size: NSSize(width: 360, height: 200)),
                          styleMask: [.titled], backing: .buffered, defer: false)
    window.title = title
    let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 360, height: 200))
    let table = NSTableView(frame: scroll.bounds)
    for (name, sortable) in columns {
        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier(name))
        column.title = name
        column.width = 110
        if sortable { column.sortDescriptorPrototype = NSSortDescriptor(key: name, ascending: true) }
        table.addTableColumn(column)
    }
    if !header { table.headerView = nil }
    table.allowsMultipleSelection = true
    table.dataSource = source
    table.delegate = source
    scroll.documentView = table
    scroll.hasVerticalScroller = true
    window.contentView = scroll
    table.reloadData()
    window.orderFrontRegardless()
    return table
}

func encoding(_ cls: AnyClass, _ selector: String) -> String {
    let method = class_getInstanceMethod(cls, NSSelectorFromString(selector))!
    return String(cString: method_getTypeEncoding(method)!)
}

func add(_ cls: AnyClass, _ selector: String, _ block: Any) {
    let types = encoding(NSAccessibilityElement.self, selector)
    let added = class_addMethod(cls, NSSelectorFromString(selector), imp_implementationWithBlock(block), types)
    log("added -\(selector) \(types) to \(NSStringFromClass(cls)): \(added)")
}

func labelOf(_ me: AnyObject) -> String {
    (me as? NSAccessibilityElement)?.accessibilityLabel() ?? "?"
}

var keep: [AnyObject] = []

func builtTable(_ origin: NSPoint) {
    let window = NSWindow(contentRect: NSRect(origin: origin, size: NSSize(width: 360, height: 200)),
                          styleMask: [.titled], backing: .buffered, defer: false)
    window.title = "built-table"
    let content = NSView(frame: NSRect(x: 0, y: 0, width: 360, height: 200))
    window.contentView = content

    let tableClass: AnyClass = objc_allocateClassPair(NSAccessibilityElement.self, "TableProbe_Table", 0)!
    let headerClass: AnyClass = objc_allocateClassPair(NSAccessibilityElement.self, "TableProbe_Header", 0)!

    let table = NSAccessibilityElement()
    var rows: [NSAccessibilityElement] = []
    var cells: [[NSAccessibilityElement]] = []
    var headers: [NSAccessibilityElement] = []
    var columns: [NSAccessibilityElement] = []
    let group = NSAccessibilityElement()

    let gate: @convention(block) (AnyObject, Selector) -> Bool = { _, _ in true }
    for cls in [tableClass, headerClass] {
        add(cls, "isAccessibilitySelectorAllowed:", gate)
    }
    let rowsGetter: @convention(block) (AnyObject) -> NSArray = { _ in
        log("ENTERED -accessibilityRows on the built table"); return rows as NSArray
    }
    add(tableClass, "accessibilityRows", rowsGetter)
    let columnsGetter: @convention(block) (AnyObject) -> NSArray = { _ in
        log("ENTERED -accessibilityColumns on the built table"); return columns as NSArray
    }
    add(tableClass, "accessibilityColumns", columnsGetter)
    let headerGetter: @convention(block) (AnyObject) -> AnyObject? = { _ in
        log("ENTERED -accessibilityHeader on the built table"); return group
    }
    add(tableClass, "accessibilityHeader", headerGetter)
    let cellAt: @convention(block) (AnyObject, Int, Int) -> AnyObject? = { _, column, row in
        log("ENTERED -accessibilityCellForColumn:\(column) row:\(row) on the built table")
        return row >= 0 && row < cells.count && column >= 0 && column < 2 ? cells[row][column] : nil
    }
    add(tableClass, "accessibilityCellForColumn:row:", cellAt)

    // The legacy pair, forwarded to NSAccessibilityElement's own implementations, as the bridge does.
    let valueSel = NSSelectorFromString("accessibilityAttributeValue:")
    let namesSel = NSSelectorFromString("accessibilityAttributeNames")
    typealias ValueIMP = @convention(c) (AnyObject, Selector, NSString) -> Unmanaged<AnyObject>?
    typealias NamesIMP = @convention(c) (AnyObject, Selector) -> Unmanaged<NSArray>?
    let inheritedValue = unsafeBitCast(class_getMethodImplementation(NSAccessibilityElement.self, valueSel), to: ValueIMP.self)
    let inheritedNames = unsafeBitCast(class_getMethodImplementation(NSAccessibilityElement.self, namesSel), to: NamesIMP.self)
    let value: @convention(block) (AnyObject, NSString) -> AnyObject? = { me, attribute in
        log("ENTERED -accessibilityAttributeValue:\(attribute) on the built table (forwarded)")
        return inheritedValue(me, valueSel, attribute)?.takeUnretainedValue()
    }
    add(tableClass, "accessibilityAttributeValue:", value)
    let namesBlock: @convention(block) (AnyObject) -> NSArray? = { me in
        log("ENTERED -accessibilityAttributeNames on the built table (forwarded)")
        return inheritedNames(me, namesSel)?.takeUnretainedValue()
    }
    add(tableClass, "accessibilityAttributeNames", namesBlock)

    let sortDirection: @convention(block) (AnyObject) -> Int = { me in
        let answer = labelOf(me) == "Name" ? 1 : 2
        log("ENTERED -accessibilitySortDirection on header \(labelOf(me)) -> \(answer)")
        return answer
    }
    add(headerClass, "accessibilitySortDirection", sortDirection)
    objc_registerClassPair(tableClass)
    objc_registerClassPair(headerClass)
    object_setClass(table, tableClass)

    table.setAccessibilityRole(.table)
    table.setAccessibilityLabel("built")
    table.setAccessibilityParent(content)
    table.setAccessibilityFrameInParentSpace(NSRect(x: 10, y: 10, width: 340, height: 180))

    group.setAccessibilityRole(.group)
    group.setAccessibilityParent(table)
    group.setAccessibilityFrameInParentSpace(NSRect(x: 0, y: 150, width: 340, height: 30))
    for (index, name) in ["Name", "Qty"].enumerated() {
        let header = NSAccessibilityElement()
        object_setClass(header, headerClass)
        header.setAccessibilityRole(.button)
        header.setAccessibilitySubrole(.sortButton)
        header.setAccessibilityLabel(name)
        header.setAccessibilityParent(group)
        header.setAccessibilityFrameInParentSpace(NSRect(x: CGFloat(index) * 170, y: 0, width: 170, height: 30))
        headers.append(header)
    }
    group.setAccessibilityChildren(headers)

    for r in 0..<2 {
        let row = NSAccessibilityElement()
        row.setAccessibilityRole(.row)
        row.setAccessibilitySubrole(.tableRow)
        row.setAccessibilityIndex(r)
        row.setAccessibilityParent(table)
        row.setAccessibilityFrameInParentSpace(NSRect(x: 0, y: 110 - CGFloat(r) * 40, width: 340, height: 40))
        var rowCells: [NSAccessibilityElement] = []
        for c in 0..<2 {
            let cell = NSAccessibilityElement()
            cell.setAccessibilityRole(.cell)
            cell.setAccessibilityLabel("r\(r)c\(c)")
            cell.setAccessibilityParent(row)
            cell.setAccessibilityRowIndexRange(NSRange(location: r, length: 1))
            cell.setAccessibilityColumnIndexRange(NSRange(location: c, length: 1))
            cell.setAccessibilityColumnHeaderUIElements([headers[c]])
            cell.setAccessibilityFrameInParentSpace(NSRect(x: CGFloat(c) * 170, y: 0, width: 170, height: 40))
            rowCells.append(cell)
        }
        row.setAccessibilityChildren(rowCells)
        rows.append(row)
        cells.append(rowCells)
    }
    for c in 0..<2 {
        let column = NSAccessibilityElement()
        column.setAccessibilityRole(.column)
        column.setAccessibilityIndex(c)
        column.setAccessibilityChildren([cells[0][c], cells[1][c]])
        column.setAccessibilityHeader(headers[c])
        column.setAccessibilityParent(table)
        column.setAccessibilityFrameInParentSpace(NSRect(x: CGFloat(c) * 170, y: 0, width: 170, height: 180))
        columns.append(column)
    }
    table.setAccessibilityChildren([group] + rows)
    content.setAccessibilityChildren([table])
    keep.append(contentsOf: [window, table, group] as [AnyObject])
    keep.append(contentsOf: rows as [AnyObject])
    keep.append(contentsOf: headers as [AnyObject])
    keep.append(contentsOf: columns as [AnyObject])
    keep.append(contentsOf: cells.flatMap { $0 } as [AnyObject])
    window.orderFrontRegardless()
}

func serve() {
    let seconds = args.count >= 2 ? Double(args[1]) ?? 60 : 60
    let app = NSApplication.shared
    app.setActivationPolicy(.accessory)

    let first = Rows("native-table", [["Apple", "3", "red"], ["Pear", "1", "green"], ["Fig", "5", "purple"],
                                      ["Plum", "2", "dark"], ["Kiwi", "4", "brown"]])
    let table = nativeTable("native-table", NSPoint(x: 200, y: 520),
                            [("Name", true), ("Qty", true), ("Note", false)], first, header: true)
    table.sortDescriptors = [NSSortDescriptor(key: "Qty", ascending: true)]
    table.selectRowIndexes(IndexSet(integer: 1), byExtendingSelection: false)

    let second = Rows("native-headless", [["a", "b"], ["c", "d"]])
    _ = nativeTable("native-headless", NSPoint(x: 200, y: 280), [("One", true), ("Two", true)], second, header: false)

    builtTable(NSPoint(x: 600, y: 520))

    log("serving pid=\(getpid()) for \(Int(seconds))s")
    DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
        log("time is up; exiting")
        exit(0)
    }
    withExtendedLifetime([first, second]) { app.run() }
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
func subrole(_ e: AXUIElement) -> String { (copy(e, kAXSubroleAttribute).1 as? String) ?? "nil" }
/// The first non-empty title, description or value under an element: a cell's text.
func label(_ e: AXUIElement, _ depth: Int = 0) -> String {
    for name in [kAXTitleAttribute, kAXDescriptionAttribute, kAXValueAttribute] {
        if let v = copy(e, name).1 as? String, !v.isEmpty { return v }
    }
    if depth > 3 { return "" }
    for child in children(e) {
        let t = label(child, depth + 1)
        if !t.isEmpty { return t }
    }
    return ""
}
func windowNamed(_ application: AXUIElement, _ title: String) -> AXUIElement? {
    let windows = (copy(application, kAXWindowsAttribute).1 as? [AXUIElement]) ?? []
    return windows.first { (copy($0, kAXTitleAttribute).1 as? String) == title }
}
func find(_ e: AXUIElement, _ wanted: String, _ depth: Int) -> AXUIElement? {
    if role(e) == wanted { return e }
    if depth == 0 { return nil }
    for child in children(e) { if let found = find(child, wanted, depth - 1) { return found } }
    return nil
}
func text(_ value: AnyObject?) -> String {
    guard let value else { return "nil" }
    if CFGetTypeID(value) == AXUIElementGetTypeID() {
        let element = value as! AXUIElement
        return "\(role(element))/\(subrole(element)) '\(label(element))'"
    }
    if CFGetTypeID(value) == AXValueGetTypeID() {
        let axValue = value as! AXValue
        var range = CFRange()
        if AXValueGetType(axValue) == .cfRange, AXValueGetValue(axValue, .cfRange, &range) {
            return "range(\(range.location),\(range.length))"
        }
        var rect = CGRect.zero
        if AXValueGetType(axValue) == .cgRect, AXValueGetValue(axValue, .cgRect, &rect) {
            return "rect(\(Int(rect.origin.x)),\(Int(rect.origin.y)),\(Int(rect.width)),\(Int(rect.height)))"
        }
        return "\(axValue)"
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
func parameterizedNames(_ e: AXUIElement) -> [String] {
    var list: CFArray?
    let error = AXUIElementCopyParameterizedAttributeNames(e, &list)
    return error == .success ? ((list as? [String]) ?? []) : ["AXError(\(error.rawValue))"]
}
func actions(_ e: AXUIElement) -> [String] {
    var list: CFArray?
    let error = AXUIElementCopyActionNames(e, &list)
    return error == .success ? ((list as? [String]) ?? []) : ["AXError(\(error.rawValue))"]
}
func cellAt(_ table: AXUIElement, column: Int, row: Int) -> String {
    var value: CFTypeRef?
    let error = AXUIElementCopyParameterizedAttributeValue(table, "AXCellForColumnAndRow" as CFString,
                                                           [column, row] as CFArray, &value)
    return "AXCellForColumnAndRow(column \(column), row \(row))="
        + (error == .success ? text(value) : "AXError(\(error.rawValue))")
}

func dumpSort(_ tag: String, _ table: AXUIElement) {
    log("\(tag) table \(show(table, "AXSortDirection"))")
    for (index, column) in ((copy(table, "AXColumns").1 as? [AXUIElement]) ?? []).enumerated() {
        log("\(tag) column#\(index) \(show(column, "AXSortDirection"))")
    }
    if let header = copy(table, "AXHeader").1, CFGetTypeID(header) == AXUIElementGetTypeID() {
        for child in children(header as! AXUIElement) {
            log("\(tag) header '\(label(child))' \(show(child, "AXSortDirection"))")
        }
    }
}

func dumpTable(_ tag: String, _ table: AXUIElement) {
    log("==== \(tag)")
    log("\(tag) table role=\(role(table)) subrole=\(subrole(table))")
    log("\(tag) table AXAttributeNames=\(names(table))")
    log("\(tag) table parameterized=\(parameterizedNames(table))")
    log("\(tag) table actions=\(actions(table))")
    for attribute in ["AXRowCount", "AXColumnCount", "AXColumns", "AXVisibleColumns", "AXSelectedColumns",
                      "AXColumnTitles", "AXHeader", "AXColumnHeaderUIElements", "AXSortDirection",
                      "AXSelectedRows", "AXSelectedCells", "AXVisibleCells", "AXRowHeaderUIElements"] {
        log("\(tag) table \(show(table, attribute))")
    }
    log("\(tag) table children=\(children(table).map { "\(role($0))/\(subrole($0))" })")

    let columns = (copy(table, "AXColumns").1 as? [AXUIElement]) ?? []
    for (index, column) in columns.enumerated() {
        log("\(tag) column#\(index) role=\(role(column)) subrole=\(subrole(column)) roleDescription=\(text(copy(column, kAXRoleDescriptionAttribute).1))")
        log("\(tag) column#\(index) AXAttributeNames=\(names(column)) actions=\(actions(column))")
        for attribute in ["AXIndex", "AXHeader", "AXChildren", "AXRows", "AXVisibleRows", "AXVisibleCells",
                          "AXSelected", "AXSortDirection", "AXTitle", "AXDescription", "AXFrame", "AXParent",
                          "AXColumnHeaderUIElements"] {
            log("\(tag) column#\(index) \(show(column, attribute))")
        }
    }

    if let header = copy(table, "AXHeader").1, CFGetTypeID(header) == AXUIElementGetTypeID() {
        let group = header as! AXUIElement
        log("\(tag) header role=\(role(group)) subrole=\(subrole(group)) AXAttributeNames=\(names(group))")
        log("\(tag) header \(show(group, "AXParent"))")
        for (index, child) in children(group).enumerated() {
            log("\(tag) header#\(index) '\(label(child))' role=\(role(child)) subrole=\(subrole(child)) actions=\(actions(child))")
            log("\(tag) header#\(index) AXAttributeNames=\(names(child))")
            for attribute in ["AXSortDirection", "AXIndex", "AXColumnIndexRange", "AXParent", "AXValue"] {
                log("\(tag) header#\(index) \(show(child, attribute))")
            }
        }
    }

    let rows = (copy(table, kAXRowsAttribute).1 as? [AXUIElement]) ?? []
    for (index, row) in rows.enumerated() {
        log("\(tag) row#\(index) '\(label(row))' role=\(role(row)) subrole=\(subrole(row)) \(show(row, "AXIndex")) children=\(children(row).map { "\(role($0))/\(subrole($0))" })")
    }
    if rows.count > 1 {
        log("\(tag) row#1 AXAttributeNames=\(names(rows[1]))")
        for (index, cell) in children(rows[1]).enumerated() {
            log("\(tag) row#1 cell#\(index) '\(label(cell))' role=\(role(cell)) subrole=\(subrole(cell)) AXAttributeNames=\(names(cell))")
            for attribute in ["AXRowIndexRange", "AXColumnIndexRange", "AXColumnHeaderUIElements", "AXParent",
                              "AXSelected", "AXIndex"] {
                log("\(tag) row#1 cell#\(index) \(show(cell, attribute))")
            }
        }
    }
    for (column, row) in [(0, 0), (1, 1), (2, 4), (0, 5), (9, 0), (-1, 0)] {
        log("\(tag) \(cellAt(table, column: column, row: row))")
    }
}

func read() {
    guard args.count >= 2, let pid = Int32(args[1]) else {
        print("usage: table-probe read <pid>"); exit(2)
    }
    log("reader pid=\(getpid()) trusted=\(AXIsProcessTrusted()) provider=\(pid)")
    let application = AXUIElementCreateApplication(pid)
    var tables: [String: AXUIElement] = [:]
    for tag in ["native-table", "native-headless", "built-table"] {
        guard let window = windowNamed(application, tag), let table = find(window, "AXTable", 6) else {
            log("NO AXTable in window \(tag)"); continue
        }
        tables[tag] = table
        dumpTable(tag, table)
    }
    guard let native = tables["native-table"] else { exit(1) }

    let notifications = ["AXValueChanged", "AXSelectedColumnsChanged", "AXSelectedRowsChanged",
                         "AXRowCountChanged", "AXLayoutChanged", "AXTitleChanged", "AXUIElementDestroyed",
                         "AXCreated", "AXFocusedUIElementChanged", "AXSelectedChildrenChanged"]
    var observer: AXObserver?
    let created = AXObserverCreateWithInfoCallback(pid, { _, element, notification, info, refcon in
        let registration = refcon.map { String(cString: $0.assumingMemoryBound(to: CChar.self)) } ?? "?"
        let infoText = (info as NSDictionary?).map { "\($0)" } ?? "nil"
        log("NOTIFIED \(notification as String) on=\(registration) element=\(text(element)) userInfo=\(infoText)")
    }, &observer)
    guard created == .success, let observer else { log("AXObserverCreate = AXError(\(created.rawValue))"); exit(1) }
    var registrations: [(String, AXUIElement)] = [("application", application), ("table", native)]
    if let header = copy(native, "AXHeader").1, CFGetTypeID(header) == AXUIElementGetTypeID() {
        registrations.append(("header", header as! AXUIElement))
    }
    var tagsKept: [UnsafeMutablePointer<CChar>] = []
    for (name, element) in registrations {
        let tag = strdup(name)!
        tagsKept.append(tag)
        for notification in notifications {
            let error = AXObserverAddNotification(observer, element, notification as CFString, tag)
            log("observe \(notification) on \(name): AXError(\(error.rawValue))")
        }
    }
    CFRunLoopAddSource(CFRunLoopGetCurrent(), AXObserverGetRunLoopSource(observer), .defaultMode)
    func spin(_ seconds: Double) { CFRunLoopRunInMode(.defaultMode, seconds, false) }
    spin(1.0)
    log("--- quiet spell over (notifications above this line were not ours)")
    dumpSort("before", native)

    func nameHeader() -> AXUIElement? {
        guard let header = copy(native, "AXHeader").1, CFGetTypeID(header) == AXUIElementGetTypeID() else { return nil }
        return children(header as! AXUIElement).first { label($0) == "Name" }
    }
    for step in 1...2 {
        let error = nameHeader().map { AXUIElementPerformAction($0, kAXPressAction as CFString) } ?? .failure
        log("=== step \(step): AXPress on the Name header -> AXError(\(error.rawValue))")
        spin(1.5)
        dumpSort("after step \(step)", native)
    }
    for tag in tagsKept { free(tag) }
}

if mode == "serve" { serve() } else { read() }
