import Cocoa

// What VoiceOver says, and writes, for a NATIVE multi-select NSTableView of four columns driven through
// the row steps of the gallery's `--reader table` script: Caucasus alone, Caucasus taken out, Pyrenees
// alone, the selection extended to Urals, Andes alone at the end, every row. On Limn's table, whose
// focused element is a cell, VoiceOver stayed silent at the deselection, at the jump to the last row and
// (in one run) at select-all, and wrote stale rows back as the selection; a native table's focused
// element is the table itself. The steps are made by the program three seconds apart; every AXSelected
// and AXSelectedRows write a client makes is logged, and NSApp's focused element after each step.
//
// Build on the guest: swiftc -O -o table-steps-probe table-steps-probe.swift
// usage: table-steps-probe [first-step-seconds]   (default 6)

setvbuf(stdout, nil, _IOLBF, 0)
let clock: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss.SSS"
    f.timeZone = TimeZone(identifier: "UTC")
    return f
}()
func say(_ s: String) { print("\(clock.string(from: Date()))Z \(s)") }

struct Range { let name: String; let continent: String; let summit: Int; let visited: Bool }
let ranges = [
    Range(name: "Alps", continent: "Europe", summit: 4808, visited: true),
    Range(name: "Andes", continent: "South America", summit: 6961, visited: false),
    Range(name: "Atlas", continent: "Africa", summit: 4167, visited: false),
    Range(name: "Carpathians", continent: "Europe", summit: 2655, visited: true),
    Range(name: "Caucasus", continent: "Europe", summit: 5642, visited: false),
    Range(name: "Himalayas", continent: "Asia", summit: 8849, visited: false),
    Range(name: "Pyrenees", continent: "Europe", summit: 3404, visited: true),
    Range(name: "Rockies", continent: "North America", summit: 4401, visited: false),
    Range(name: "Urals", continent: "Europe", summit: 1895, visited: false),
    Range(name: "Zagros", continent: "Asia", summit: 4409, visited: false),
]

final class LoggingTable: NSTableView {
    override func setAccessibilitySelectedRows(_ rows: [any NSAccessibilityRow]) {
        say("CLIENT WROTE AXSelectedRows on the table: \(rows.count) row(s)")
        super.setAccessibilitySelectedRows(rows)
    }
}

final class LoggingRow: NSTableRowView {
    var label = "?"
    override func setAccessibilitySelected(_ selected: Bool) {
        say("CLIENT WROTE AXSelected=\(selected) on '\(label)'")
        super.setAccessibilitySelected(selected)
    }
}

final class D: NSObject, NSApplicationDelegate, NSTableViewDataSource, NSTableViewDelegate {
    var window: NSWindow!
    var table: LoggingTable!
    let first: Double
    init(first: Double) { self.first = first }

    func numberOfRows(in t: NSTableView) -> Int { ranges.count }

    func tableView(_ t: NSTableView, viewFor column: NSTableColumn?, row: Int) -> NSView? {
        let r = ranges[row]
        switch column?.identifier.rawValue {
        case "visited":
            let box = NSButton(checkboxWithTitle: "Visitada", target: nil, action: nil)
            box.state = r.visited ? .on : .off
            return box
        default:
            let text: String
            switch column?.identifier.rawValue {
            case "continent": text = r.continent
            case "summit": text = String(r.summit)
            default: text = r.name
            }
            let cell = NSTableCellView()
            let label = NSTextField(labelWithString: text)
            label.translatesAutoresizingMaskIntoConstraints = false
            cell.addSubview(label)
            cell.textField = label
            label.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 4).isActive = true
            label.centerYAnchor.constraint(equalTo: cell.centerYAnchor).isActive = true
            return cell
        }
    }

    func tableView(_ t: NSTableView, rowViewForRow row: Int) -> NSTableRowView? {
        let v = LoggingRow()
        v.label = ranges[row].name
        return v
    }

    func applicationDidFinishLaunching(_ n: Notification) {
        window = NSWindow(contentRect: NSRect(x: 150, y: 200, width: 560, height: 300),
                          styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "Native table"
        let scroll = NSScrollView(frame: NSRect(x: 16, y: 16, width: 528, height: 268))
        table = LoggingTable(frame: scroll.bounds)
        for (id, title, width) in [("range", "Cordilheira", 140.0), ("continent", "Continente", 150.0),
                                   ("summit", "Cume", 90.0), ("visited", "Visitada", 110.0)] {
            let c = NSTableColumn(identifier: NSUserInterfaceItemIdentifier(id))
            c.title = title
            c.width = width
            table.addTableColumn(c)
        }
        table.allowsMultipleSelection = true
        table.setAccessibilityLabel("Cordilheiras")
        table.dataSource = self
        table.delegate = self
        scroll.documentView = table
        scroll.hasVerticalScroller = true
        window.contentView!.addSubview(scroll)
        table.selectRowIndexes(IndexSet([0, 2, 3]), byExtendingSelection: false)
        NSApp.setActivationPolicy(.regular)
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(table)
        NSApp.activate(ignoringOtherApps: true)
        say("pid=\(getpid()) up")
        let steps: [(String, () -> Void)] = [
            ("1 Caucasus alone", { self.only(4) }),
            ("9 Caucasus out", { self.table.deselectRow(4) }),
            ("10 Pyrenees alone", { self.only(6) }),
            ("11 extend to Urals", { self.table.selectRowIndexes(IndexSet([6, 7, 8]), byExtendingSelection: false); self.table.scrollRowToVisible(8) }),
            ("12 Andes alone (last row)", { self.only(9) }),
            ("13 all", { self.table.selectAll(nil) }),
        ]
        for (i, step) in steps.enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + first + Double(i) * 3) {
                NSApp.activate(ignoringOtherApps: true)
                step.1()
                let focused = (NSApp.accessibilityFocusedUIElement as? NSAccessibilityProtocol)?.accessibilityRole()?.rawValue ?? "nil"
                let selected = self.table.selectedRowIndexes.map { ranges[$0].name }.joined(separator: ",")
                say("--- step \(step.0): selection=[\(selected)] focused=\(focused)")
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + first + Double(steps.count) * 3 + 3) {
            say("--- exit")
            NSApp.terminate(nil)
        }
    }

    func only(_ row: Int) {
        table.selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false)
        table.scrollRowToVisible(row)
    }
}

let first = CommandLine.arguments.count > 1 ? Double(CommandLine.arguments[1]) ?? 6 : 6
let d = D(first: first)
NSApplication.shared.delegate = d
NSApplication.shared.run()
