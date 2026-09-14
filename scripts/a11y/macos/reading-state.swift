import Cocoa
import ApplicationServices

// The two facts every live reading on the macOS guest depends on, printed from inside the process
// whose launch path is being asked about, before anything is read and without changing either.
//
//   * Accessibility trust. A client that is not trusted gets kAXErrorAPIDisabled (-25211) from every
//     read, which looks exactly like a provider that answers nothing. AXIsProcessTrusted() is what
//     TCC says; the system-wide focused-application read beside it is what an actual read gets. It
//     never prompts: AXIsProcessTrustedWithOptions with the prompt key is the call that would, and it
//     is deliberately not made. Root is trusted whatever TCC says, so run it once per launch path --
//     plain ssh, `sudo launchctl asuser <uid>`, and `sudo launchctl asuser <uid> sudo -u <user>` -- and
//     read the uid beside the answer.
//   * Whether the console session is locked. A locked guest runs everything and reads nothing a
//     reader would: VoiceOver reads loginwindow, ScreenCaptureKit fails. From a process with no GUI
//     session the dictionary is absent, and that is printed rather than read as "unlocked".
//
// Build with `swiftc -O -o reading-state reading-state.swift` on the guest.
//
// usage: reading-state [label]

let label = CommandLine.arguments.dropFirst().first ?? "state"
let session = CGSessionCopyCurrentDictionary() as? [String: Any]
func sessionValue(_ key: String) -> String {
    guard let session else { return "no-session-dictionary" }
    return session[key].map { "\($0)" } ?? "absent"
}

var focused: CFTypeRef?
let read = AXUIElementCopyAttributeValue(AXUIElementCreateSystemWide(),
                                         kAXFocusedApplicationAttribute as CFString, &focused)
var focusedPid: pid_t = -1
if read == .success, let element = focused, CFGetTypeID(element) == AXUIElementGetTypeID() {
    AXUIElementGetPid(element as! AXUIElement, &focusedPid)
}

// The system-wide focused application can fail for reasons that are not trust (nothing frontmost,
// the VM's foreground proxy), so one read against a process that is always there as well: the Dock.
var dockRole: CFTypeRef?
var dockRead = "no-dock-process"
if let dock = NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.dock").first {
    let error = AXUIElementCopyAttributeValue(AXUIElementCreateApplication(dock.processIdentifier),
                                              kAXRoleAttribute as CFString, &dockRole)
    dockRead = "AXError(\(error.rawValue)) role=\(dockRole.map { "\($0)" } ?? "nil")"
}

print("\(label): uid=\(getuid()) euid=\(geteuid()) pid=\(getpid()) ppid=\(getppid())"
    + " AXIsProcessTrusted=\(AXIsProcessTrusted())"
    + " systemWideFocusedApplication=AXError(\(read.rawValue)) focusedPid=\(focusedPid)"
    + " dockRole=\(dockRead)"
    + " screenIsLocked=\(sessionValue("CGSSessionScreenIsLocked"))"
    + " onConsole=\(sessionValue("kCGSSessionOnConsoleKey"))")
