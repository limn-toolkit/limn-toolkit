import Cocoa
import ApplicationServices

// Asks a running Limn form (`--scene form`) what the Accessibility API answers about a field's
// relations: AXLinkedUIElements, the one relation attribute this platform has for an element that
// is neither parent nor child, with every target resolved and read back; and beside it AXHelp,
// which is where the description's text travels, AXTitleUIElement, and the name attributes.
// The macOS sibling of scripts/a11y/linux/relations-check.py and
// scripts/a11y/windows/walk-the-relations.ps1.
//
// usage: axrelations <pid> [field-title]

let args = CommandLine.arguments
guard args.count >= 2, let pid = Int32(args[1]) else {
    print("usage: axrelations <pid> [field-title]"); exit(2)
}
let wanted = args.count >= 3 ? args[2] : "Email"

func attr(_ el: AXUIElement, _ name: String) -> AnyObject? {
    var v: CFTypeRef?
    return AXUIElementCopyAttributeValue(el, name as CFString, &v) == .success ? v : nil
}

func str(_ v: AnyObject?) -> String {
    guard let v = v else { return "" }
    if let s = v as? String { return s }
    return "\(v)"
}

func describe(_ el: AXUIElement) -> String {
    let role = str(attr(el, kAXRoleAttribute))
    let title = str(attr(el, kAXTitleAttribute))
    let desc = str(attr(el, kAXDescriptionAttribute))
    return role.isEmpty ? "<unresolvable>" : "\(role) title='\(title)' description='\(desc)'"
}

func find(_ el: AXUIElement, _ depth: Int) -> AXUIElement? {
    let role = str(attr(el, kAXRoleAttribute))
    if role == "AXMenuBar" { return nil }
    if role == "AXTextField" {
        let title = str(attr(el, kAXTitleAttribute))
        let desc = str(attr(el, kAXDescriptionAttribute))
        if title == wanted || desc == wanted { return el }
    }
    if depth > 12 { return nil }
    if let kids = attr(el, kAXChildrenAttribute) as? [AXUIElement] {
        for k in kids { if let hit = find(k, depth + 1) { return hit } }
    }
    return nil
}

print("pid=\(pid) trusted=\(AXIsProcessTrusted()) euid=\(geteuid())")
let app = AXUIElementCreateApplication(pid)
guard let field = find(app, 0) else { print("NO AXTextField titled '\(wanted)'"); exit(3) }
print("field: \(describe(field))")
print("  AXHelp='\(str(attr(field, kAXHelpAttribute)))'")
print("  AXValue='\(str(attr(field, kAXValueAttribute)))'")
if let titleElement = attr(field, "AXTitleUIElement") {
    print("  AXTitleUIElement -> \(describe(titleElement as! AXUIElement))")
} else {
    print("  AXTitleUIElement -> none")
}
// A relation is only worth publishing if a client can FOLLOW it: each target is resolved and read
// back rather than counted.
if let linked = attr(field, "AXLinkedUIElements") as? [AXUIElement], !linked.isEmpty {
    print("  AXLinkedUIElements (\(linked.count)):")
    for target in linked {
        print("    -> \(describe(target))")
        if let back = attr(target, "AXLinkedUIElements") as? [AXUIElement] {
            print("       which links back to \(back.count) element(s): \(back.map(describe))")
        } else {
            print("       which links to nothing")
        }
    }
} else {
    print("  AXLinkedUIElements -> none")
}
