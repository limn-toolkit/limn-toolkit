import Cocoa

// Stacks a run's caption captures into one tall image, dropping consecutive duplicates.
//
// A screen-reader run is a sequence of phrases, and reading it as a sequence is the point: what
// matters is that "Save, botão" follows the focus move and "marcado" follows the toggle. Fifteen
// separate PNGs, most of them identical because the panel had not changed between captures, are a
// worse record of that than one strip.
//
// usage: vomontage <out.png> <in1.png> <in2.png> ...
let args = CommandLine.arguments
guard args.count >= 3 else { print("usage: vomontage <out.png> <in...>"); exit(2) }

var frames: [NSBitmapImageRep] = []
var lastDigest: Data?
for path in args.dropFirst(2) {
    guard let data = FileManager.default.contents(atPath: path),
          let rep = NSBitmapImageRep(data: data) else { continue }
    // Consecutive identical panels are one phrase held on screen, not two.
    if let previous = lastDigest, previous == data { continue }
    lastDigest = data
    frames.append(rep)
}
guard !frames.isEmpty else { print("FAIL: nothing to stack"); exit(1) }

let width = frames.map(\.pixelsWide).max()!
let height = frames.map(\.pixelsHigh).reduce(0, +)
guard let canvas = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height,
                                    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
                                    isPlanar: false, colorSpaceName: .deviceRGB,
                                    bytesPerRow: 0, bitsPerPixel: 0) else { exit(1) }
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: canvas)
var y = height
for frame in frames {
    y -= frame.pixelsHigh
    frame.draw(in: NSRect(x: 0, y: y, width: frame.pixelsWide, height: frame.pixelsHigh))
}
NSGraphicsContext.restoreGraphicsState()

guard let png = canvas.representation(using: .png, properties: [:]) else { exit(1) }
try! png.write(to: URL(fileURLWithPath: args[1]))
print("stacked \(frames.count) distinct panels of \(args.count - 2) captures -> \(args[1])")
