import Cocoa
import ApplicationServices

// ADR 039 §13.20: an element destroyed while a client holds it. The spike retained every object it
// made and released none, and called this the obvious crash vector; §1.3's answer -- unlink, post
// UIElementDestroyed, then release -- has never been run.
//
// This client is the other half of that experiment, and it has to be a client rather than an
// assertion in the provider, because the whole question is what happens to a reference held ACROSS
// the process boundary. It:
//
//   1. finds the element by AXIdentifier and keeps it,
//   2. registers an AXObserver for AXUIElementDestroyed -- on the ELEMENT and on the application
//      element both, because which registration receives it is itself unproven here,
//   3. tells the provider to destroy it, by appending to the provider's own command file,
//   4. runs a run loop, so the notification has somewhere to arrive,
//   5. and then messages the stale reference, which must return an error and not crash.
//
// The `where` argument selects which registration is live, because the first run of this saw THREE
// notifications from one post and "both registrations receive it" is a different fact from "AppKit
// repeats it".
//
// usage: axlife <pid> <identifier> <commandFile> [seconds] [element|app|both]

let args = CommandLine.arguments
guard args.count >= 4, let pid = Int32(args[1]) else {
    print("usage: axlife <pid> <identifier> <commandFile> [seconds]"); exit(2)
}
let wanted = args[2]
let commandFile = args[3]
let seconds = args.count >= 5 ? Double(args[4]) ?? 8 : 8
let registerOn = args.count >= 6 ? args[5] : "both"

func attr(_ el: AXUIElement, _ name: String) -> AnyObject? {
    var v: CFTypeRef?
    return AXUIElementCopyAttributeValue(el, name as CFString, &v) == .success ? v : nil
}

func find(_ el: AXUIElement, _ depth: Int) -> AXUIElement? {
    if let id = attr(el, "AXIdentifier") as? String, id == wanted { return el }
    if depth > 8 { return nil }
    guard let kids = attr(el, kAXChildrenAttribute) as? [AXUIElement] else { return nil }
    for k in kids { if let hit = find(k, depth + 1) { return hit } }
    return nil
}

let app = AXUIElementCreateApplication(pid)
guard let target = find(app, 0) else {
    print("FAIL: no element with AXIdentifier '\(wanted)'"); exit(1)
}
print("held '\(wanted)': role='\((attr(target, kAXRoleAttribute) as? String) ?? "")'"
    + " title='\((attr(target, kAXTitleAttribute) as? String) ?? "")'")

var destroyedSeen = 0
var otherSeen: [String] = []

let callback: AXObserverCallback = { _, _, notification, _ in
    let name = notification as String
    print("NOTIFICATION: \(name)")
    if name == kAXUIElementDestroyedNotification { destroyedSeen += 1 } else { otherSeen.append(name) }
}

var observer: AXObserver?
guard AXObserverCreate(pid, callback, &observer) == .success, let obs = observer else {
    print("FAIL: AXObserverCreate"); exit(1)
}
var onElement = AXError.cannotComplete, onApp = AXError.cannotComplete
if registerOn == "element" || registerOn == "both" {
    onElement = AXObserverAddNotification(obs, target, kAXUIElementDestroyedNotification as CFString, nil)
}
if registerOn == "app" || registerOn == "both" {
    onApp = AXObserverAddNotification(obs, app, kAXUIElementDestroyedNotification as CFString, nil)
}
print("registered for AXUIElementDestroyed on '\(registerOn)':"
    + " onElement=\(onElement.rawValue) onApp=\(onApp.rawValue)")
CFRunLoopAddSource(CFRunLoopGetCurrent(), AXObserverGetRunLoopSource(obs), .defaultMode)

// Tell the provider to destroy it. The client drives this rather than the shell script, so that the
// observer is demonstrably registered BEFORE the destruction rather than probably.
if let handle = FileHandle(forWritingAtPath: commandFile) {
    handle.seekToEndOfFile()
    handle.write("destroy \(wanted)\n".data(using: .utf8)!)
    try? handle.close()
    print("asked the provider to destroy '\(wanted)'")
} else {
    print("FAIL: cannot write \(commandFile)"); exit(1)
}

CFRunLoopRunInMode(.defaultMode, seconds, false)

print("destroyed notifications seen = \(destroyedSeen)  other = \(otherSeen)")

// The reference is now stale. A message to it must fail cleanly.
var value: CFTypeRef?
let err = AXUIElementCopyAttributeValue(target, kAXRoleAttribute as CFString, &value)
print("stale AXRole read -> error \(err.rawValue)"
    + (err == .success ? " value='\(String(describing: value))'  (STILL ANSWERING)" : "  (refused, as it must)"))

var stillThere = find(app, 0)
print("re-walk finds '\(wanted)': \(stillThere != nil)")
print(destroyedSeen > 0 && err != .success && stillThere == nil
      ? "PASS: destruction observed, stale reference refused, node gone from the tree"
      : "FAIL: destroyedSeen=\(destroyedSeen) staleErr=\(err.rawValue) stillInTree=\(stillThere != nil)")
