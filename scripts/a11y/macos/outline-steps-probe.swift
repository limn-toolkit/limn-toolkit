import Cocoa

// What VoiceOver says, and writes, for a NATIVE NSOutlineView driven through the first steps of the
// gallery's `--reader tree-loading` script: Documents and Reports open, the cursor walked onto
// Documents and Reports, Reports closed, Reports opened again, then down onto its first report. On
// Limn's tree VoiceOver answered step 4 (Reports opened again) by scrolling to Trash and saying
// "Trash, reduzido" without asking for the focused element (readings/list-multi-macos, final-tree-1);
// this is the control. The steps are made by the program, as the demo's driver makes them, three
// seconds apart; every AXSelected / AXSelectedRows / AXDisclosing write a client makes is logged.
//
// Build on the guest: swiftc -O -o outline-steps-probe outline-steps-probe.swift
// usage: outline-steps-probe [first-step-seconds]   (default 6)

setvbuf(stdout, nil, _IOLBF, 0)
let clock: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss.SSS"
    f.timeZone = TimeZone(identifier: "UTC")
    return f
}()
func say(_ s: String) { print("\(clock.string(from: Date()))Z \(s)") }

final class Node: NSObject {
    let name: String
    let kids: [Node]
    init(_ name: String, _ kids: [Node] = []) { self.name = name; self.kids = kids }
}

let reports = Node("Reports", [Node("Q3 regional revenue.pdf"), Node("2026.pdf")])
let documents = Node("Documents", [reports, Node("meeting notes.md")])
let media = Node("Media", [Node("clip.mp4"), Node("cover.png")])
let remote = Node("Remote", [Node("index.json"), Node("a.json"), Node("b.json")])
let roots = [documents, media, remote, Node("Trash", [Node("old.txt")]), Node("Empty folder")]

final class LoggingOutline: NSOutlineView {
    override func setAccessibilitySelectedRows(_ rows: [any NSAccessibilityRow]) {
        say("CLIENT WROTE AXSelectedRows on the outline: \(rows.count) row(s)")
        super.setAccessibilitySelectedRows(rows)
    }
}

final class LoggingRow: NSTableRowView {
    var label = "?"
    override func setAccessibilitySelected(_ selected: Bool) {
        say("CLIENT WROTE AXSelected=\(selected) on '\(label)'")
        super.setAccessibilitySelected(selected)
    }
    override func setAccessibilityDisclosed(_ disclosed: Bool) {
        say("CLIENT WROTE AXDisclosing=\(disclosed) on '\(label)'")
        super.setAccessibilityDisclosed(disclosed)
    }
}

final class D: NSObject, NSApplicationDelegate, NSOutlineViewDataSource, NSOutlineViewDelegate {
    var window: NSWindow!
    var outline: LoggingOutline!
    let first: Double
    init(first: Double) { self.first = first }

    func outlineView(_ o: NSOutlineView, numberOfChildrenOfItem item: Any?) -> Int {
        (item as? Node)?.kids.count ?? roots.count
    }
    func outlineView(_ o: NSOutlineView, child index: Int, ofItem item: Any?) -> Any {
        (item as? Node)?.kids[index] ?? roots[index]
    }
    func outlineView(_ o: NSOutlineView, isItemExpandable item: Any) -> Bool {
        !((item as? Node)?.kids.isEmpty ?? true)
    }
    func outlineView(_ o: NSOutlineView, viewFor tableColumn: NSTableColumn?, item: Any) -> NSView? {
        let cell = NSTableCellView()
        let text = NSTextField(labelWithString: (item as? Node)?.name ?? "?")
        text.translatesAutoresizingMaskIntoConstraints = false
        cell.addSubview(text)
        cell.textField = text
        text.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 4).isActive = true
        text.centerYAnchor.constraint(equalTo: cell.centerYAnchor).isActive = true
        return cell
    }
    func outlineView(_ o: NSOutlineView, rowViewForItem item: Any) -> NSTableRowView? {
        let row = LoggingRow()
        row.label = (item as? Node)?.name ?? "?"
        return row
    }
    func outlineViewSelectionDidChange(_ n: Notification) {
        let r = outline.selectedRow
        say("selection -> row \(r) '\((outline.item(atRow: r) as? Node)?.name ?? "-")'")
    }

    func applicationDidFinishLaunching(_ n: Notification) {
        window = NSWindow(contentRect: NSRect(x: 200, y: 200, width: 380, height: 380),
                          styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "Native outline"
        let scroll = NSScrollView(frame: NSRect(x: 16, y: 16, width: 348, height: 348))
        outline = LoggingOutline(frame: scroll.bounds)
        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("name"))
        column.width = 320
        outline.addTableColumn(column)
        outline.outlineTableColumn = column
        outline.headerView = nil
        outline.setAccessibilityLabel("Arquivos")
        outline.dataSource = self
        outline.delegate = self
        scroll.documentView = outline
        window.contentView!.addSubview(scroll)
        outline.expandItem(documents)
        outline.expandItem(reports)
        NSApp.setActivationPolicy(.regular)
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(outline)
        NSApp.activate(ignoringOtherApps: true)
        say("pid=\(getpid()) up; rows=\(outline.numberOfRows)")
        let steps: [(String, () -> Void)] = [
            ("1 DOWN lands on Documents", { self.select(documents) }),
            ("2 DOWN moves to Reports", { self.select(reports) }),
            ("3 LEFT closes Reports", { self.outline.collapseItem(reports) }),
            ("4 RIGHT opens Reports again", { self.outline.expandItem(reports) }),
            ("5 DOWN moves to the first report", { self.select(reports.kids[0]) }),
            ("6 DOWN moves to 2026.pdf", { self.select(reports.kids[1]) }),
        ]
        for (i, step) in steps.enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + first + Double(i) * 3) {
                NSApp.activate(ignoringOtherApps: true)
                step.1()
                say("--- step \(step.0): rows=\(self.outline.numberOfRows) selected='\((self.outline.item(atRow: self.outline.selectedRow) as? Node)?.name ?? "-")'")
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + first + Double(steps.count) * 3 + 3) {
            say("--- exit")
            NSApp.terminate(nil)
        }
    }

    func select(_ node: Node) {
        let row = outline.row(forItem: node)
        outline.selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false)
        outline.scrollRowToVisible(row)
    }
}

let first = CommandLine.arguments.count > 1 ? Double(CommandLine.arguments[1]) ?? 6 : 6
let d = D(first: first)
NSApplication.shared.delegate = d
NSApplication.shared.run()
