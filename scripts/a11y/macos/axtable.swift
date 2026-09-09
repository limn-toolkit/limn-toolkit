// Asks a running Limn table what the AX API asks a table: rows, header, selected rows, the cell
// at a column and row, a cell's index ranges and its column header. The macOS sibling of
// scripts/a11y/linux/table-check.py and scripts/a11y/windows/walk-the-table.ps1, and the check
// ADR 041 §7 owes this bridge. Build with `swiftc -O -o axtable axtable.swift` on the guest and
// run it unprivileged against the demo's pid (`--scene table`).
//
// usage: axtable <pid>
import Cocoa
import ApplicationServices
let args = CommandLine.arguments
guard args.count >= 2, let pid = Int32(args[1]) else { print("usage: axtable <pid>"); exit(2) }
func attr(_ e: AXUIElement, _ name: String) -> AnyObject? {
    var v: AnyObject?; return AXUIElementCopyAttributeValue(e, name as CFString, &v) == .success ? v : nil
}
func role(_ e: AXUIElement) -> String { (attr(e, kAXRoleAttribute) as? String) ?? "?" }
func title(_ e: AXUIElement) -> String { (attr(e, kAXTitleAttribute) as? String) ?? "" }
func children(_ e: AXUIElement) -> [AXUIElement] { (attr(e, kAXChildrenAttribute) as? [AXUIElement]) ?? [] }
func find(_ e: AXUIElement, _ r: String, _ depth: Int) -> AXUIElement? {
    if role(e) == r { return e }
    if depth == 0 { return nil }
    for c in children(e) { if let f = find(c, r, depth - 1) { return f } }
    return nil
}
let app = AXUIElementCreateApplication(pid)
guard let table = find(app, "AXTable", 6) else { print("no AXTable"); exit(1) }
func count(_ name: String) -> String { let v = attr(table, name); return v == nil ? "nil" : "\(v!)" }
print("AXRowCount=\(count("AXRowCount")) AXColumnCount=\(count("AXColumnCount"))")
let rows = (attr(table, "AXRows") as? [AXUIElement]) ?? []
print("AXRows count=\(rows.count) roles=\(Set(rows.map(role)))")
let visible = (attr(table, "AXVisibleRows") as? [AXUIElement]) ?? []
print("AXVisibleRows count=\(visible.count)")
let selected = (attr(table, "AXSelectedRows") as? [AXUIElement]) ?? []
print("AXSelectedRows count=\(selected.count) indices=\(selected.map { (attr($0, "AXIndex") as? Int) ?? -9 })")
if let header = attr(table, "AXHeader") { let h = header as! AXUIElement; print("AXHeader role=\(role(h)) children=\(children(h).map(title))") } else { print("AXHeader=nil") }
let headers = (attr(table, "AXColumnHeaderUIElements") as? [AXUIElement]) ?? []
print("AXColumnHeaderUIElements=\(headers.map(title))")
var names: CFArray?
AXUIElementCopyParameterizedAttributeNames(table, &names)
print("parameterized: \((names as? [String]) ?? [])")
var cellRef: AnyObject?
let params: [Int] = [3, 1]   // column 3, row 1
let err = AXUIElementCopyParameterizedAttributeValue(table, "AXCellForColumnAndRow" as CFString, params as CFArray, &cellRef)
if err == .success, let cell = cellRef { let c = cell as! AXUIElement; print("AXCellForColumnAndRow(col 3,row 1): \(role(c)) \u{27}\(title(c))\u{27}") } else { print("AXCellForColumnAndRow err=\(err.rawValue)") }
if let r1 = rows.dropFirst().first { let cells = children(r1); print("row1 AXIndex=\((attr(r1, "AXIndex") as? Int) ?? -9) selected=\(attr(r1, "AXSelected") ?? "nil" as AnyObject) cells=\(cells.map(title))")
  if let c = cells.dropFirst(3).first {
    if let rr = attr(c, "AXRowIndexRange") { var range = CFRange(); AXValueGetValue(rr as! AXValue, .cfRange, &range); print("cell(1,3) AXRowIndexRange=\(range.location),\(range.length)") }
    if let cr = attr(c, "AXColumnIndexRange") { var range = CFRange(); AXValueGetValue(cr as! AXValue, .cfRange, &range); print("cell(1,3) AXColumnIndexRange=\(range.location),\(range.length)") }
    print("cell(1,3) AXColumnHeaderUIElements=\(((attr(c, "AXColumnHeaderUIElements") as? [AXUIElement]) ?? []).map(title))")
  }
}
