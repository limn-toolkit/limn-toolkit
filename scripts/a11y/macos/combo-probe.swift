import Cocoa

// What VoiceOver says, and what the accessibility tree is, for a NATIVE NSComboBox driven through
// the gallery's `--reader combo` steps: Down opens the list on the selected item, Down, Down, Up walk
// it, Return picks, Down opens it again, Escape closes it. The keys are posted into this process's
// own event queue, as the demo's reader driver sends Limn its keys: a reader hears the result and
// never the key. After each step the probe prints the field's value, the selected index and whether
// the list is showing. The AX tree an outside client reads is walked separately (axtree), so this
// process never asks the platform what it was told.
//
// The same six ranges as the gallery's "Combo box, driven", Andes selected, under the label
// "Cordilheiras" (the pt-BR caption). Two native controls, because a Limn combo only chooses and
// AppKit has one control for each half of what "combo box" means:
//   combo   NSComboBox, the editable one (AXComboBox, its open list an AXList)
//   popup   NSPopUpButton, AppKit's choose-one control (AXPopUpButton, its open list an AXMenu)
//
// Build on the guest: swiftc -O -o combo-probe combo-probe.swift
// usage: combo-probe [first-step-seconds] [combo|popup]   (defaults 5, combo; steps 3 s apart)

setvbuf(stdout, nil, _IOLBF, 0)
let clock: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss.SSS"
    f.timeZone = TimeZone(identifier: "UTC")
    return f
}()
func say(_ s: String) { print("\(clock.string(from: Date()))Z \(s)") }

let ranges = ["Alps", "Andes", "Atlas", "Carpathians", "Caucasus", "Himalayas"]

let variant = CommandLine.arguments.count > 2 ? CommandLine.arguments[2] : "combo"

final class D: NSObject, NSApplicationDelegate {
    var window: NSWindow!
    var combo: NSComboBox!
    var popup: NSPopUpButton!
    let first: Double
    init(first: Double) { self.first = first }

    func key(_ code: UInt16, _ chars: String) {
        for down in [true, false] {
            if let e = NSEvent.keyEvent(with: down ? .keyDown : .keyUp, location: .zero, modifierFlags: [],
                                        timestamp: ProcessInfo.processInfo.systemUptime,
                                        windowNumber: window.windowNumber, context: nil,
                                        characters: chars, charactersIgnoringModifiers: chars,
                                        isARepeat: false, keyCode: code) {
                NSApp.postEvent(e, atStart: false)
            }
        }
    }

    func state() -> String {
        let listShowing = NSApp.windows.contains { $0 !== window && $0.isVisible }
        if let p = popup {
            return "value='\(p.titleOfSelectedItem ?? "")' index=\(p.indexOfSelectedItem) highlighted='\(p.menu?.highlightedItem?.title ?? "-")'"
        }
        return "value='\(combo.stringValue)' index=\(combo.indexOfSelectedItem) list=\(listShowing ? "shown" : "hidden")"
    }

    func applicationDidFinishLaunching(_ n: Notification) {
        window = NSWindow(contentRect: NSRect(x: 200, y: 300, width: 360, height: 200),
                          styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "Native combo"
        let label = NSTextField(labelWithString: "Cordilheiras")
        label.frame = NSRect(x: 20, y: 130, width: 200, height: 20)
        window.contentView!.addSubview(label)
        let control: NSControl
        if variant == "popup" {
            popup = NSPopUpButton(frame: NSRect(x: 20, y: 96, width: 220, height: 26), pullsDown: false)
            popup.addItems(withTitles: ranges)
            popup.selectItem(at: 1)
            popup.setAccessibilityTitleUIElement(label)
            control = popup
        } else {
            combo = NSComboBox(frame: NSRect(x: 20, y: 96, width: 220, height: 26))
            combo.addItems(withObjectValues: ranges)
            combo.selectItem(at: 1)
            combo.setAccessibilityTitleUIElement(label)
            control = combo
        }
        window.contentView!.addSubview(control)
        NSApp.setActivationPolicy(.regular)
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(control)
        NSApp.activate(ignoringOtherApps: true)
        say("variant=\(variant) pid=\(getpid()) up; \(state())")
        let down: UInt16 = 125, up: UInt16 = 126, ret: UInt16 = 36, esc: UInt16 = 53
        let downC = String(Character(UnicodeScalar(NSDownArrowFunctionKey)!))
        let upC = String(Character(UnicodeScalar(NSUpArrowFunctionKey)!))
        let steps: [(String, UInt16, String)] = [
            ("1 DOWN opens the list", down, downC), ("2 DOWN", down, downC), ("3 DOWN", down, downC),
            ("4 UP", up, upC), ("5 RETURN picks", ret, "\r"), ("6 DOWN opens again", down, downC),
            ("7 ESCAPE closes", esc, "\u{1b}"),
        ]
        for (i, step) in steps.enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + first + Double(i) * 3) {
                NSApp.activate(ignoringOtherApps: true)
                self.window.makeKeyAndOrderFront(nil)
                self.key(step.1, step.2)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    say("--- step \(step.0): \(self.state())")
                }
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + first + Double(steps.count) * 3 + 3) {
            say("--- exit")
            NSApp.terminate(nil)
        }
    }
}

let first = CommandLine.arguments.count > 1 ? Double(CommandLine.arguments[1]) ?? 5 : 5
let d = D(first: first)
NSApplication.shared.delegate = d
NSApplication.shared.run()
