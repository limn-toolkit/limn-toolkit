// Asks a running Limn tree what the AX API asks an outline: its role and subrole, the rows it
// answers, and on each realized row the subrole and the disclosure attributes VoiceOver reads an
// outline row by. The macOS sibling of scripts/a11y/linux/tree-check.py and
// scripts/a11y/windows/walk-the-tree.ps1, and the check ADR 044 §4 owes this bridge.
//
// axtree does not print a subrole or AXDisclosing, and those are precisely the two things that
// decide whether VoiceOver treats a row as an outline row that opens or as a row in a list.
//
// Build with `swiftc -O -o axoutline axoutline.swift` on the guest; run it unprivileged against the
// demo's pid (`--scene tree-reader` or `--scene tree`).
//
// usage: axoutline <pid> [label]
import Cocoa
import ApplicationServices

let args = CommandLine.arguments
guard args.count >= 2, let pid = Int32(args[1]) else { print("usage: axoutline <pid> [label]"); exit(2) }
let label = args.count >= 3 ? args[2] : "snapshot"

func attr(_ e: AXUIElement, _ name: String) -> AnyObject? {
    var v: AnyObject?
    return AXUIElementCopyAttributeValue(e, name as CFString, &v) == .success ? v : nil
}
/// An attribute as text, with "nil" for one the element does not answer: that distinction is the
/// whole of what a missing disclosure attribute looks like from outside.
func show(_ e: AXUIElement, _ name: String) -> String {
    guard let v = attr(e, name) else { return "nil" }
    if let s = v as? String { return s }
    if let n = v as? NSNumber { return n.stringValue }
    if let a = v as? [AnyObject] { return "[\(a.count)]" }
    return "\(v)"
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
/// The first non-empty title, description or value under a row: its cell's label.
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
    print("NO AXOutline: the role is not reaching AppKit")
    exit(1)
}
print("AXOutline subrole=\(show(outline, kAXSubroleAttribute)) says='\(show(outline, "AXRoleDescription"))'"
    + " AXRows=\(show(outline, "AXRows")) AXSelectedRows=\(show(outline, "AXSelectedRows"))"
    + " children=\(children(outline).count)")
for (i, row) in children(outline).prefix(12).enumerated() {
    print("  [\(i)] \(show(row, kAXRoleAttribute))/\(show(row, kAXSubroleAttribute))"
        + " says='\(show(row, "AXRoleDescription"))' '\(text(row))'"
        + " disclosing=\(show(row, "AXDisclosing")) level=\(show(row, "AXDisclosureLevel"))"
        + " expanded=\(show(row, "AXExpanded")) selected=\(show(row, "AXSelected"))"
        + " focused=\(show(row, "AXFocused"))")
}
if let f = attr(app, kAXFocusedUIElementAttribute) {
    let focused = f as! AXUIElement
    print("focused: \(show(focused, kAXRoleAttribute))/\(show(focused, kAXSubroleAttribute)) '\(text(focused))'")
}
