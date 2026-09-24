import Cocoa

// What VoiceOver does to a NATIVE multi-select list while its selection is moved and extended the
// way the gallery's `--reader list-multi` script moves ours: whether it writes AXSelected on the
// row the keyboard lands on (and so collapses a Shift range, since a native row's AXSelected = YES
// replaces the selection: selection-writes-probe, 2026-09-15), what the application answers as its
// focused element, and what the selection is after each step once VoiceOver has had its say.
//
// A view-based, one-column NSTableView over the gallery's ten ranges, allowsMultipleSelection,
// first responder of a key window, Atlas selected. Every AXSelected write a client makes on a row,
// and every AXSelectedRows write on the table, is logged with the row and whether it was already
// selected, then passed to AppKit unchanged. The steps are the script's, made by the program as
// the demo's reader driver makes them (a reader never sees the key), three seconds apart:
//   1 Carpathians alone   2 extend to Caucasus   3 extend to Himalayas   4 Himalayas out
//   5 Himalayas back      6 Zagros alone          7 all                   8 Alps alone
// After each step, and again 1 s later, the selection and NSApp's focused element are printed.
//
// A variant makes the native table look like Limn's list in one respect at a time, to learn which
// difference changes what VoiceOver says and writes:
//   native    as AppKit builds it: an AXTable whose focused element is the table
//   list      the table answers the role AXList, and its rows no subrole (Limn's LIST and LIST_ITEM)
//   focusrow  the table answers the cursor's row view as the focused element and posts
//             AXFocusedUIElementChanged on it at each step (what Limn answers for a list)
//   both      the two together
//
// Run it in the console session with VoiceOver on (sudo launchctl asuser 501 sudo -u <user> ...).
// Build: swiftc -O -o multi-list-probe multi-list-probe.swift
// usage: multi-list-probe [first-step-seconds] [variant]   (defaults 5, native)

setvbuf(stdout, nil, _IOLBF, 0)

let clock: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss.SSS"
    f.timeZone = TimeZone(identifier: "UTC")
    return f
}()
func say(_ s: String) { print("\(clock.string(from: Date()))Z \(s)") }

let ranges = ["Alps", "Andes", "Atlas", "Carpathians", "Caucasus", "Himalayas", "Pyrenees",
              "Rockies", "Urals", "Zagros"]

let variant = CommandLine.arguments.count > 2 ? CommandLine.arguments[2] : "native"
let asList = variant == "list" || variant == "both"
let focusRow = variant == "focusrow" || variant == "both"
var cursorRow = 2

final class LoggingTable: NSTableView {
    var ours = false
    override func accessibilityRole() -> NSAccessibility.Role? {
        return asList ? .list : super.accessibilityRole()
    }
    override var accessibilityFocusedUIElement: Any? {
        if focusRow, let row = rowView(atRow: cursorRow, makeIfNecessary: false) { return row }
        return super.accessibilityFocusedUIElement
    }
    override func setAccessibilitySelectedRows(_ rows: [any NSAccessibilityRow]) {
        say("CLIENT WROTE AXSelectedRows on the table: \(rows.count) row(s); selection before: \(selectionText())")
        super.setAccessibilitySelectedRows(rows)
    }
}

final class LoggingRow: NSTableRowView {
    var index = -1
    weak var table: LoggingTable?
    override func setAccessibilitySelected(_ selected: Bool) {
        let name = index >= 0 && index < ranges.count ? ranges[index] : "?"
        let was = table?.selectedRowIndexes.contains(index) ?? false
        say("CLIENT WROTE AXSelected=\(selected ? "YES" : "NO") on row \(index) '\(name)', "
            + "which was \(was ? "selected" : "unselected"); selection before: \(selectionText())")
        super.setAccessibilitySelected(selected)
    }
    override func accessibilitySubrole() -> NSAccessibility.Subrole? {
        return asList ? nil : super.accessibilitySubrole()
    }
    override func setAccessibilityFocused(_ focused: Bool) {
        say("CLIENT WROTE AXFocused=\(focused ? "YES" : "NO") on row \(index)")
        super.setAccessibilityFocused(focused)
    }
}

var tableRef: LoggingTable!
func selectionText() -> String {
    guard let t = tableRef else { return "?" }
    return "[" + t.selectedRowIndexes.map { ranges[$0] }.joined(separator: ", ") + "]"
}
final class Delegate: NSObject, NSApplicationDelegate, NSTableViewDataSource, NSTableViewDelegate {
    var window: NSWindow!
    var table: LoggingTable!
    let first: Double

    init(first: Double) { self.first = first }

    func numberOfRows(in tableView: NSTableView) -> Int { ranges.count }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        let cell = NSTableCellView()
        let text = NSTextField(labelWithString: ranges[row])
        text.translatesAutoresizingMaskIntoConstraints = false
        cell.addSubview(text)
        cell.textField = text
        text.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 8).isActive = true
        text.centerYAnchor.constraint(equalTo: cell.centerYAnchor).isActive = true
        return cell
    }

    func tableView(_ tableView: NSTableView, rowViewForRow row: Int) -> NSTableRowView? {
        let view = LoggingRow()
        view.index = row
        view.table = table
        return view
    }

    func tableViewSelectionDidChange(_ notification: Notification) {
        say("selection changed \(table.ours ? "(ours)" : "(NOT OURS)") -> \(selectionText())")
    }

    func applicationDidFinishLaunching(_ n: Notification) {
        window = NSWindow(contentRect: NSRect(x: 200, y: 200, width: 360, height: 360),
                          styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "Native multi list"
        let scroll = NSScrollView(frame: NSRect(x: 16, y: 16, width: 328, height: 328))
        table = LoggingTable(frame: scroll.bounds)
        tableRef = table
        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("range"))
        column.width = 300
        table.addTableColumn(column)
        table.headerView = nil
        table.allowsMultipleSelection = true
        table.setAccessibilityLabel("Cordilheiras")
        table.dataSource = self
        table.delegate = self
        scroll.documentView = table
        window.contentView!.addSubview(scroll)
        table.ours = true
        table.selectRowIndexes(IndexSet(integer: 2), byExtendingSelection: false)
        table.ours = false
        NSApp.setActivationPolicy(.regular)
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(table)
        NSApp.activate(ignoringOtherApps: true)
        say("variant=\(variant)")
        say("pid=\(getpid()) up; selection \(selectionText()); focused \(self.focusedText())")

        let steps: [(String, () -> Void)] = [
            ("1 Carpathians alone", { self.select([3], extend: false); self.moveCursor(3) }),
            ("2 extend to Caucasus", { self.select([4], extend: true); self.moveCursor(4) }),
            ("3 extend to Himalayas", { self.select([5], extend: true); self.moveCursor(5) }),
            ("4 Himalayas out", { self.deselect(5) }),
            ("5 Himalayas back", { self.select([5], extend: true) }),
            ("6 Zagros alone", { self.select([9], extend: false); self.moveCursor(9) }),
            ("7 all", { self.table.ours = true; self.table.selectAll(nil); self.table.ours = false }),
            ("8 Alps alone", { self.select([0], extend: false); self.moveCursor(0) }),
        ]
        for (i, step) in steps.enumerated() {
            let at = first + Double(i) * 3
            DispatchQueue.main.asyncAfter(deadline: .now() + at) {
                NSApp.activate(ignoringOtherApps: true)
                self.window.makeKeyAndOrderFront(nil)
                step.1()
                say("--- step \(step.0): selection \(selectionText()); focused \(self.focusedText())")
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + at + 1) {
                say("    1 s later: selection \(selectionText())")
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + first + Double(steps.count) * 3 + 2) {
            say("--- exit")
            NSApp.terminate(nil)
        }
    }

    func focusedText() -> String { return (NSApp.accessibilityFocusedUIElement as? NSObject).map {
        "\(type(of: $0)) role=\(($0 as? NSAccessibilityProtocol)?.accessibilityRole()?.rawValue ?? "?")" } ?? "nil" }

    func select(_ rows: [Int], extend: Bool) {
        table.ours = true
        table.selectRowIndexes(IndexSet(rows), byExtendingSelection: extend)
        if let last = rows.last { table.scrollRowToVisible(last) }
        table.ours = false
    }

    /** Where Limn's cursor would be; in the focusrow variants, told to AppKit as a focus change. */
    func moveCursor(_ row: Int) {
        cursorRow = row
        if focusRow, let view = table.rowView(atRow: row, makeIfNecessary: false) {
            NSAccessibility.post(element: view, notification: .focusedUIElementChanged)
        }
    }

    func deselect(_ row: Int) {
        table.ours = true
        table.deselectRow(row)
        table.ours = false
    }
}

let first = CommandLine.arguments.count > 1 ? Double(CommandLine.arguments[1]) ?? 5 : 5
let app = NSApplication.shared
let delegate = Delegate(first: first)
app.delegate = delegate
app.run()
