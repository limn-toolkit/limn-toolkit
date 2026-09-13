// Asks a running Limn tree whether each row it answers is busy: AXElementBusy, the one attribute the
// macOS bridge serves through AppKit's legacy entry points rather than the NSAccessibility protocol
// (ADR 044 §2). It also reads each row's role, to show that everything else still reaches the
// protocol getters through the forward.
//
// Run it against `--scene tree-loading` with a load long enough to walk into, once while Remote is
// loading and once after it lands:
//
//   JAVA_TOOL_OPTIONS=-Dlimn.demo.treeLoadMillis=15000 ./gradlew :limn-demo:run \
//       --args="--scene tree-loading --exit-after 30000"
//   swiftc -O -o axbusy axbusy.swift && ./axbusy <pid> loading   # within the fifteen seconds
//   ./axbusy <pid> landed                                          # after them
//
// usage: axbusy <pid> [label]
import Cocoa
import ApplicationServices

let args = CommandLine.arguments
guard args.count >= 2, let pid = Int32(args[1]) else { print("usage: axbusy <pid> [label]"); exit(2) }
let label = args.count >= 3 ? args[2] : "snapshot"

func attr(_ e: AXUIElement, _ name: String) -> AnyObject? {
    var v: AnyObject?
    return AXUIElementCopyAttributeValue(e, name as CFString, &v) == .success ? v : nil
}
func show(_ e: AXUIElement, _ name: String) -> String {
    guard let v = attr(e, name) else { return "nil" }
    if let s = v as? String { return s }
    if let n = v as? NSNumber { return n.stringValue }
    if let a = v as? [AnyObject] { return "[\(a.count)]" }
    return "\(v)"
}
func names(_ e: AXUIElement) -> [String] {
    var v: CFArray?
    return AXUIElementCopyAttributeNames(e, &v) == .success ? (v as? [String]) ?? [] : []
}
func children(_ e: AXUIElement) -> [AXUIElement] {
    (attr(e, kAXChildrenAttribute) as? [AXUIElement]) ?? []
}
func find(_ e: AXUIElement, _ role: String, _ depth: Int) -> AXUIElement? {
    if show(e, kAXRoleAttribute) == role { return e }
    if depth == 0 { return nil }
    for c in children(e) { if let f = find(c, role, depth - 1) { return f } }
    return nil
}
func text(_ e: AXUIElement, _ depth: Int = 0) -> String {
    for name in [kAXTitleAttribute, kAXDescriptionAttribute, kAXValueAttribute] {
        if let v = attr(e, name) as? String, !v.isEmpty { return v }
    }
    if depth > 4 { return "" }
    for c in children(e) {
        let t = text(c, depth + 1)
        if !t.isEmpty { return t }
    }
    return ""
}

print("=== \(label) === pid=\(pid) trusted=\(AXIsProcessTrusted())")
let app = AXUIElementCreateApplication(pid)
guard let outline = find(app, "AXOutline", 8) else {
    print("NO AXOutline: the tree is not reaching AppKit")
    exit(1)
}
print("AXOutline role=\(show(outline, kAXRoleAttribute)) busy=\(show(outline, "AXElementBusy"))")
for (i, row) in children(outline).prefix(14).enumerated() {
    print("  [\(i)] \(show(row, kAXRoleAttribute))/\(show(row, kAXSubroleAttribute))"
        + " '\(text(row))' busy=\(show(row, "AXElementBusy"))"
        + " listed=\(names(row).contains("AXElementBusy"))")
}
