import Cocoa
import ApplicationServices

// ADR 039 phase 7 probe client: walks a provider's AX tree out of process and answers the two
// questions §13.21 and §13.23 ask of a tree the spike never built.
//
//   * Does the walk reach BELOW the array pushed onto the content view? Every level under that
//     array is answered by -accessibilityChildren on our own class, which is a pull no run has
//     ever exercised. Depth in the output is the answer.
//   * Does a name survive the boundary? Names are printed as Unicode scalars, not just as glyphs,
//     because an astral character that arrives as two replacement chars still LOOKS like text in a
//     terminal, and a right-to-left string reversed by a broken layer looks identical either way.
//
// And, when given positions to probe, whether AXUIElementCopyElementAtPosition reaches a NESTED
// element with no accessibilityHitTest: of the provider's -- §2.2 measured that for one element
// directly under the view and §13.21 keeps it open for a deeper one.
//
// usage: axtree <pid> [maxDepth] [hitX,hitY ...]

let args = CommandLine.arguments
guard args.count >= 2, let pid = Int32(args[1]) else {
    print("usage: axtree <pid> [maxDepth] [x,y ...]"); exit(2)
}
let maxDepth = args.count >= 3 ? Int(args[2]) ?? 8 : 8
let hits = args.dropFirst(3).compactMap { spec -> CGPoint? in
    let parts = spec.split(separator: ",")
    guard parts.count == 2, let x = Double(parts[0]), let y = Double(parts[1]) else { return nil }
    return CGPoint(x: x, y: y)
}

func attr(_ el: AXUIElement, _ name: String) -> AnyObject? {
    var v: CFTypeRef?
    return AXUIElementCopyAttributeValue(el, name as CFString, &v) == .success ? v : nil
}

/// A string as its Unicode scalars. The whole point of §13.23 is that the glyphs are not evidence.
func scalars(_ s: String) -> String {
    "[" + s.unicodeScalars.map { String(format: "U+%04X", $0.value) }.joined(separator: " ") + "]"
}

func str(_ v: AnyObject?) -> String {
    guard let v = v else { return "" }
    if let s = v as? String { return s }
    if let n = v as? NSNumber { return n.stringValue }
    if CFGetTypeID(v) == AXValueGetTypeID() {
        let ax = v as! AXValue
        var r = CGRect.zero; var p = CGPoint.zero
        switch AXValueGetType(ax) {
        case .cgRect: AXValueGetValue(ax, .cgRect, &r)
            return "rect(\(Int(r.origin.x)),\(Int(r.origin.y)),\(Int(r.size.width)),\(Int(r.size.height)))"
        case .cgPoint: AXValueGetValue(ax, .cgPoint, &p); return "pt(\(Int(p.x)),\(Int(p.y)))"
        default: return "axvalue"
        }
    }
    if let a = v as? [AnyObject] { return "[\(a.count)]" }
    return "\(v)"
}

var deepest = 0

func walk(_ el: AXUIElement, _ depth: Int) {
    deepest = max(deepest, depth)
    let ind = String(repeating: "  ", count: depth)
    let role = str(attr(el, kAXRoleAttribute))
    let title = str(attr(el, kAXTitleAttribute))
    let ident = str(attr(el, "AXIdentifier"))
    let frame = str(attr(el, "AXFrame"))
    var names: CFArray?
    AXUIElementCopyActionNames(el, &names)
    let actions = (names as? [String])?.joined(separator: ",") ?? ""
    var line = "\(ind)\(role) title='\(title)'"
    if !title.isEmpty { line += " \(scalars(title))" }
    if !ident.isEmpty { line += " id='\(ident)'" }
    line += " \(frame)"
    if !actions.isEmpty { line += " actions=[\(actions)]" }
    // §13.27: a relation is only worth publishing if a client can FOLLOW it, so this does not
    // report that the attribute is present -- it resolves each target and reads its role back.
    if let linked = attr(el, "AXLinkedUIElements") as? [AXUIElement], !linked.isEmpty {
        let targets = linked.map { target -> String in
            let role = str(attr(target, kAXRoleAttribute))
            let title = str(attr(target, kAXTitleAttribute))
            return role.isEmpty ? "<unresolvable>" : "\(role)'\(title)'"
        }
        line += " linkedTo=[\(targets.joined(separator: ", "))]"
    }
    print(line)
    if depth >= maxDepth { return }
    // The menu bar is AppKit's and is hundreds of nodes wide on a localized system. It is not what
    // this probe publishes and printing it buries what is.
    if role == "AXMenuBar" { print("\(ind)  <AppKit's own menu bar, not walked>"); return }
    if let kids = attr(el, kAXChildrenAttribute) as? [AXUIElement] {
        for k in kids { walk(k, depth + 1) }
    }
}

print("pid=\(pid) trusted=\(AXIsProcessTrusted()) euid=\(geteuid())")
let app = AXUIElementCreateApplication(pid)
walk(app, 0)
print("deepest depth reached = \(deepest)")

let systemWide = AXUIElementCreateSystemWide()
for p in hits {
    var found: AXUIElement?
    let err = AXUIElementCopyElementAtPosition(systemWide, Float(p.x), Float(p.y), &found)
    if err == .success, let f = found {
        var owner: pid_t = 0
        AXUIElementGetPid(f, &owner)
        print("hitTest(\(Int(p.x)),\(Int(p.y))) -> role='\(str(attr(f, kAXRoleAttribute)))'"
            + " title='\(str(attr(f, kAXTitleAttribute)))' id='\(str(attr(f, "AXIdentifier")))' pid=\(owner)")
    } else {
        print("hitTest(\(Int(p.x)),\(Int(p.y))) -> error \(err.rawValue)")
    }
}
