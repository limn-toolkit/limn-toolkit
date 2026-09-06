import Cocoa
import ScreenCaptureKit

// Captures one on-screen window by owner and size, through ScreenCaptureKit.
//
// It exists because reading what VoiceOver SAYS needs a picture of its caption panel -- the reader's
// own process is not accessibility-inspectable, its speech is not in the unified log, and
// CGWindowListCreateImage was removed in macOS 26 -- and because /usr/sbin/screencapture raises a
// consent dialog on EVERY invocation. That dialog takes the foreground, and a screen reader
// announces the window in front, so the measurement destroys what it is measuring: VoiceOver spends
// the run describing the dialog instead of the application.
//
// A stable binary at a stable path is granted once and stays granted, which is the whole difference.
//
// The height range matters and is not fussiness: VoiceOver puts its caption panel and its
// full-screen cursor overlay on screen at once, and "the tallest window it owns" is the overlay.
//
// usage: vocap <owner-name-substring> <min-height> <max-height> <out.png>

// A plain command-line tool has no connection to the window server until something initialises
// CoreGraphics, and ScreenCaptureKit asserts rather than failing when it finds none
// (CGS_REQUIRE_INIT). Touching NSApplication is what opens it.
_ = NSApplication.shared

let args = CommandLine.arguments
guard args.count >= 5, let minHeight = Int(args[2]), let maxHeight = Int(args[3]) else {
    print("usage: vocap <owner> <minHeight> <maxHeight> <out.png>"); exit(2)
}
let wantedOwner = args[1]
let outPath = args[4]

let done = DispatchSemaphore(value: 0)
var status: Int32 = 1

Task {
    do {
        let content = try await SCShareableContent.excludingDesktopWindows(false,
                                                                          onScreenWindowsOnly: true)
        let match = content.windows.first { window in
            guard let owner = window.owningApplication?.applicationName else { return false }
            let height = Int(window.frame.height)
            return owner.localizedCaseInsensitiveContains(wantedOwner)
                && height >= minHeight && height <= maxHeight
        }
        guard let window = match else {
            print("FAIL: no window of '\(wantedOwner)' between \(minHeight) and \(maxHeight) tall")
            done.signal(); return
        }
        let config = SCStreamConfiguration()
        config.width = Int(window.frame.width * 2)     // the guest is a 2x display
        config.height = Int(window.frame.height * 2)
        config.showsCursor = false
        let filter = SCContentFilter(desktopIndependentWindow: window)
        let image = try await SCScreenshotManager.captureImage(contentFilter: filter,
                                                              configuration: config)
        let rep = NSBitmapImageRep(cgImage: image)
        guard let png = rep.representation(using: .png, properties: [:]) else {
            print("FAIL: no PNG"); done.signal(); return
        }
        try png.write(to: URL(fileURLWithPath: outPath))
        print("captured '\(window.owningApplication?.applicationName ?? "?")'"
            + " \(Int(window.frame.width))x\(Int(window.frame.height)) -> \(outPath)")
        status = 0
    } catch {
        print("FAIL: \(error)")
    }
    done.signal()
}

done.wait()
exit(status)
