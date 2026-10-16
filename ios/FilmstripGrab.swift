import AVFoundation
import ImageIO
import UniformTypeIdentifiers

/// A clip's frames across its whole source, one per `intervalMs`, drawn through merge's
/// geometry (as `FrameGrab` draws one), written as one grid JPEG. Each frame may come from up to
/// half an interval either side of its time, so most land on keyframes and decode cheaply.
enum FilmstripGrab {
  static func run(_ uri: String, _ options: FilmstripOptions) async throws -> Filmstrip {
    let media = try await Probe.load(fileURL(uri))
    guard let track = media.videoTrack else { throw MergeError.invalid("The file has no video.") }
    let (size, transform, range, frame) = try await track.load(
      .naturalSize, .preferredTransform, .timeRange, .minFrameDuration)

    let clip = MergeClip(
      uri: uri, startMs: 0, endMs: 0, speed: 1, muted: false,
      rotation: options.rotation, flipped: options.flipped, crop: options.crop)
    let content = MergeGeometry(
      clip: clip, sourceSize: size, sourceTransform: transform,
      targetSize: size, targetTransform: .identity, canvas: size).contentSize
    let output = FrameGrab.fitted(content, maxWidth: options.maxWidth, maxHeight: options.maxHeight)
    let geometry = MergeGeometry(
      clip: clip, sourceSize: size, sourceTransform: transform,
      targetSize: output, targetTransform: .identity, canvas: output)

    let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: track)
    layer.setTransform(geometry.transform, at: .zero)
    if let crop = geometry.sourceCrop { layer.setCropRectangle(crop, at: .zero) }
    let instruction = AVMutableVideoCompositionInstruction()
    instruction.timeRange = CMTimeRange(start: .zero, end: range.end)
    instruction.layerInstructions = [layer]
    let composition = AVMutableVideoComposition()
    composition.instructions = [instruction]
    composition.renderSize = output
    let frameDuration = frame.isNumeric && frame > .zero ? frame : CMTime(value: 1, timescale: 30)
    composition.frameDuration = frameDuration
    composition.colorPrimaries = AVVideoColorPrimaries_ITU_R_709_2
    composition.colorTransferFunction = AVVideoTransferFunction_ITU_R_709_2
    composition.colorYCbCrMatrix = AVVideoYCbCrMatrix_ITU_R_709_2

    let interval = max(100, options.intervalMs)
    let half = CMTime(value: CMTimeValue(interval / 2), timescale: 1000)
    let generator = AVAssetImageGenerator(asset: media.asset)
    generator.videoComposition = composition
    generator.requestedTimeToleranceBefore = half
    generator.requestedTimeToleranceAfter = half

    let durationMs = Probe.ms(range.duration)
    let count = max(1, Int((durationMs / interval).rounded(.up)))
    let lastFrame = range.end - frameDuration
    let times = (0..<count).map { i in
      CMTimeMinimum(range.start + CMTime(value: CMTimeValue(Double(i) * interval), timescale: 1000), lastFrame)
    }

    let columns = min(count, 16)
    let rows = (count + columns - 1) / columns
    let fw = Int(output.width), fh = Int(output.height)
    guard let context = CGContext(
      data: nil, width: fw * columns, height: fh * rows, bitsPerComponent: 8, bytesPerRow: 0,
      space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
    else { throw MergeError.failed("Couldn't create the filmstrip image.") }

    // CGContext's origin is bottom-left: row 0 is the top row.
    func place(_ image: CGImage, _ i: Int) {
      let col = i % columns, row = i / columns
      context.draw(image, in: CGRect(x: col * fw, y: (rows - 1 - row) * fh, width: fw, height: fh))
    }
    if #available(iOS 16, *) {
      var i = 0
      for await result in generator.images(for: times) {
        if let image = try? result.image { place(image, i) }
        i += 1
      }
    } else {
      for (i, time) in times.enumerated() {
        if let image = try? generator.copyCGImage(at: time, actualTime: nil) { place(image, i) }
      }
    }
    guard let sheet = context.makeImage() else { throw MergeError.failed("Couldn't draw the filmstrip.") }

    let url = try Merge.outputURL("strip", extension: "jpg")
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
      throw MergeError.failed("Couldn't create the image file.")
    }
    CGImageDestinationAddImage(destination, sheet, [kCGImageDestinationLossyCompressionQuality: min(max(options.quality, 0), 1)] as CFDictionary)
    guard CGImageDestinationFinalize(destination) else {
      try? FileManager.default.removeItem(at: url)
      throw MergeError.failed("Couldn't write the image file.")
    }
    return Filmstrip(
      uri: url.absoluteString, frameWidth: Double(fw), frameHeight: Double(fh),
      columns: Double(columns), count: Double(count), intervalMs: interval)
  }
}
