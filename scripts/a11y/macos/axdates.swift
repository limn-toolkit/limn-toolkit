import Cocoa
import ApplicationServices

// Asks a running Limn application what the AX API asks of its date widgets — a calendar grid, a
// segmented date field and a date picker — and prints one snapshot. The macOS client H3 owes, beside
// axtable.swift and axoutline.swift: a reader run is only as good as the facts it can be checked
// against, and until this nothing read CalendarView, DateField or DatePicker from outside on macOS.
//
// It finds the widgets by role, not by name, so it reads whichever of them the application shows:
// written against the accessibility gallery's entries "Calendar grid", "Date field, segmented",
// "Date picker, closed" (whose calendar opens natively, in a window of its own) and "Date picker, open",
// and the demo's `--scene dates`. Names are localized and a day's name carries ", today" from the
// application's clock, so a runner pins the locale and the day (decision 65; ADR 042 §1) and snapshots
// at the driver's step lines; nothing here waits or presses anything.
//
// Printed, per window of the application:
//   grid    every AXTable: role, subrole, role description; AXRowCount, AXColumnCount, AXColumns;
//           AXHeader's children and AXColumnHeaderUIElements by title; each AXRow's AXIndex; the cell
//           AXCellForColumnAndRow answers at every row and column (title or description, AXRowIndexRange,
//           AXColumnIndexRange, AXSelected, AXEnabled, AXHelp, AXFocused, its column header); and
//           AXSelectedCells / AXSelectedRows
//   title   every element answering AXExpanded: role, name, AXExpanded, actions — the calendar's title
//           button, the date field and the picker
//   field   every AXIncrementor: its parent group's name, its name, AXValue, AXMinValue, AXMaxValue,
//           AXFocused and actions (AXIncrement/AXDecrement expected)
// and the application's AXFocusedUIElement (a day cell, a segment or the table).
//
// Build with `swiftc -O -o axdates axdates.swift` and run it unprivileged against the application's
// pid. Exits 1 when the application shows neither an AXTable nor an AXIncrementor.
//
// usage: axdates <pid> [label]

setvbuf(stdout, nil, _IOLBF, 0)

let args = CommandLine.arguments
guard args.count >= 2, let pid = Int32(args[1]) else { print("usage: axdates <pid> [label]"); exit(2) }
let label = args.count >= 3 ? args[2] : ""

func copy(_ e: AXUIElement, _ name: String) -> (AXError, AnyObject?) {
    var value: CFTypeRef?
    let error = AXUIElementCopyAttributeValue(e, name as CFString, &value)
    return (error, value)
}
func attr(_ e: AXUIElement, _ name: String) -> AnyObject? { copy(e, name).1 }
func children(_ e: AXUIElement) -> [AXUIElement] { (attr(e, kAXChildrenAttribute) as? [AXUIElement]) ?? [] }
func role(_ e: AXUIElement) -> String { (attr(e, kAXRoleAttribute) as? String) ?? "nil" }
func name(_ e: AXUIElement) -> String {
    for key in [kAXTitleAttribute, kAXDescriptionAttribute] {
        if let text = attr(e, key) as? String, !text.isEmpty { return text }
    }
    return ""
}
func range(_ value: AnyObject?) -> String {
    guard let value, CFGetTypeID(value) == AXValueGetTypeID() else { return "nil" }
    var r = CFRange()
    return AXValueGetValue(value as! AXValue, .cfRange, &r) ? "\(r.location),\(r.length)" : "?"
}
func text(_ value: AnyObject?) -> String {
    guard let value else { return "nil" }
    if CFGetTypeID(value) == AXUIElementGetTypeID() {
        let element = value as! AXUIElement
        return "\(role(element)) '\(name(element))'"
    }
    if let s = value as? String { return "'\(s)'" }
    if let n = value as? NSNumber { return n.stringValue }
    if let a = value as? [AnyObject] { return "[" + a.map { text($0) }.joined(separator: ", ") + "]" }
    return "\(value)"
}
func show(_ e: AXUIElement, _ name: String) -> String {
    let (error, value) = copy(e, name)
    return "\(name)=" + (error == .success ? text(value) : "AXError(\(error.rawValue))")
}
func actions(_ e: AXUIElement) -> [String] {
    var list: CFArray?
    let error = AXUIElementCopyActionNames(e, &list)
    return error == .success ? ((list as? [String]) ?? []) : ["AXError(\(error.rawValue))"]
}
func collect(_ e: AXUIElement, _ depth: Int, _ into: inout [AXUIElement]) {
    into.append(e)
    if depth == 0 { return }
    for child in children(e) { collect(child, depth - 1, &into) }
}
func cellAt(_ table: AXUIElement, column: Int, row: Int) -> AXUIElement? {
    var value: CFTypeRef?
    let error = AXUIElementCopyParameterizedAttributeValue(table, "AXCellForColumnAndRow" as CFString,
                                                           [column, row] as CFArray, &value)
    guard error == .success, let value, CFGetTypeID(value) == AXUIElementGetTypeID() else { return nil }
    return (value as! AXUIElement)
}

let application = AXUIElementCreateApplication(pid)
print("axdates pid=\(pid) label='\(label)' trusted=\(AXIsProcessTrusted())")
print("application \(show(application, kAXFocusedUIElementAttribute))")
var tables = 0
var incrementors = 0
for (w, window) in ((attr(application, kAXWindowsAttribute) as? [AXUIElement]) ?? []).enumerated() {
    var all: [AXUIElement] = []
    collect(window, 12, &all)
    print("window#\(w) '\(name(window))' elements=\(all.count)")
    for element in all {
        switch role(element) {
        case "AXTable":
            tables += 1
            let subrole = (attr(element, kAXSubroleAttribute) as? String) ?? "nil"
            print("  grid '\(name(element))' subrole=\(subrole) \(show(element, kAXRoleDescriptionAttribute))")
            print("  grid \(show(element, "AXRowCount")) \(show(element, "AXColumnCount")) AXColumns=\(((attr(element, "AXColumns") as? [AXUIElement]) ?? []).count)")
            if let header = attr(element, "AXHeader"), CFGetTypeID(header) == AXUIElementGetTypeID() {
                print("  grid AXHeader children=\(children(header as! AXUIElement).map { name($0) })")
            } else {
                print("  grid \(show(element, "AXHeader"))")
            }
            print("  grid AXColumnHeaderUIElements=\(((attr(element, "AXColumnHeaderUIElements") as? [AXUIElement]) ?? []).map { name($0) })")
            let rows = (attr(element, kAXRowsAttribute) as? [AXUIElement]) ?? []
            print("  grid AXRows=\(rows.count) indices=\(rows.map { (attr($0, "AXIndex") as? Int).map(String.init) ?? "nil" })")
            let rowCount = (attr(element, "AXRowCount") as? Int) ?? rows.count
            let columnCount = (attr(element, "AXColumnCount") as? Int) ?? 0
            for r in 0..<rowCount {
                var line = "  grid row \(r):"
                for c in 0..<columnCount {
                    guard let cell = cellAt(element, column: c, row: r) else { line += " [\(c) nil]"; continue }
                    let header = ((attr(cell, "AXColumnHeaderUIElements") as? [AXUIElement]) ?? []).map { name($0) }
                    line += " [\(c) '\(name(cell))' rows=\(range(attr(cell, "AXRowIndexRange")))"
                        + " cols=\(range(attr(cell, "AXColumnIndexRange")))"
                        + " sel=\(text(attr(cell, kAXSelectedAttribute))) en=\(text(attr(cell, kAXEnabledAttribute)))"
                        + " foc=\(text(attr(cell, kAXFocusedAttribute))) help=\(text(attr(cell, kAXHelpAttribute)))"
                        + " header=\(header)]"
                }
                print(line)
            }
            print("  grid \(show(element, "AXSelectedCells"))")
            print("  grid \(show(element, "AXSelectedRows"))")
        case "AXIncrementor":
            incrementors += 1
            let parent = attr(element, kAXParentAttribute)
            let group = parent.flatMap { CFGetTypeID($0) == AXUIElementGetTypeID() ? name($0 as! AXUIElement) : nil } ?? "?"
            print("  field '\(group)' segment '\(name(element))' \(show(element, kAXValueAttribute))"
                  + " \(show(element, kAXMinValueAttribute)) \(show(element, kAXMaxValueAttribute))"
                  + " \(show(element, kAXFocusedAttribute)) actions=\(actions(element))")
        default:
            break
        }
        let (expandedError, expanded) = copy(element, "AXExpanded")
        if expandedError == .success {
            print("  expands \(role(element)) '\(name(element))' AXExpanded=\(text(expanded)) actions=\(actions(element))")
        }
    }
}
print("tables=\(tables) incrementors=\(incrementors)")
exit(tables == 0 && incrementors == 0 ? 1 : 0)
