import AVFoundation
import Foundation

// cut.swift <in> <out.mp4> <start seconds> <duration seconds>: the stretch asked for, video and
// sound copied as they are, nothing encoded again.
let input = URL(fileURLWithPath: CommandLine.arguments[1])
let output = URL(fileURLWithPath: CommandLine.arguments[2])
let start = Double(CommandLine.arguments[3])!
let length = Double(CommandLine.arguments[4])!
try? FileManager.default.removeItem(at: output)
let asset = AVURLAsset(url: input, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetPassthrough) else {
    print("no export session"); exit(1)
}
export.timeRange = CMTimeRange(start: CMTime(seconds: start, preferredTimescale: 600),
                               duration: CMTime(seconds: length, preferredTimescale: 600))
export.shouldOptimizeForNetworkUse = true
let sem = DispatchSemaphore(value: 0)
Task {
    do {
        try await export.export(to: output, as: .mp4)
        print("exported")
    } catch { print("error", error) }
    sem.signal()
}
sem.wait()
