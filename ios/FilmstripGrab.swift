import AVFoundation
import ImageIO
import UniformTypeIdentifiers

/// A clip's frames across its whole source, one per `intervalMs`, drawn through merge's geometry
/// (as `FrameGrab` draws one), each covering a `frameWidth` × `frameHeight` cell (cropped, centred),
/// side by side in JPEG rows. A frame may come from up to half an interval either side of its time,
/// so most land on keyframes and decode cheaply.
enum FilmstripGrab {
  static func run(_ uri: String, _ options: FilmstripOptions) async throws -> Filmstrip {
    let media = try await Probe.load(fileURL(uri))
    guard let track = media.videoTrack else { throw MergeError.invalid("The file has no video.") }
    let (size, transform, range, frame) = try await track.load(
      .naturalSize, .preferredTransform, .timeRange, .minFrameDuration)

    let fw = max(2, Int(options.frameWidth.rounded()))
    let fh = max(2, Int(options.frameHeight.rounded()))
    let clip = MergeClip(
      uri: uri, startMs: 0, endMs: 0, speed: 1, muted: false,
      rotation: options.rotation, flipped: options.flipped, crop: options.crop)
    // The edited picture, scaled to cover a cell.
    let content = MergeGeometry(
      clip: clip, sourceSize: size, sourceTransform: transform,
      targetSize: size, targetTransform: .identity, canvas: size).contentSize
    let cover = max(Double(fw) / content.width, Double(fh) / content.height)
    let output = CGSize(
      width: max(2, (content.width * cover).rounded()), height: max(2, (content.height * cover).rounded()))
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

    let interval = max(50, options.intervalMs)
    let half = CMTime(value: CMTimeValue(interval / 2), timescale: 1000)
    let generator = AVAssetImageGenerator(asset: media.asset)
    generator.videoComposition = composition
    generator.requestedTimeToleranceBefore = half
    generator.requestedTimeToleranceAfter = half

    let count = max(1, Int((Probe.ms(range.duration) / interval).rounded(.up)))
    let lastFrame = range.end - frameDuration
    let times = (0..<count).map { i in
      CMTimeMinimum(range.start + CMTime(value: CMTimeValue(Double(i) * interval), timescale: 1000), lastFrame)
    }

    let perImage = max(1, Int(options.maxImageWidth) / fw)
    let images = (count + perImage - 1) / perImage
    var contexts: [CGContext] = []
    for k in 0..<images {
      let frames = min(perImage, count - k * perImage)
      guard let ctx = CGContext(
        data: nil, width: fw * frames, height: fh, bitsPerComponent: 8, bytesPerRow: 0,
        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
      else { throw MergeError.failed("Couldn't create the filmstrip image.") }
      contexts.append(ctx)
    }
    // Frame i fills its cell (centred, the overflow cropped).
    func place(_ image: CGImage, _ i: Int) {
      let ctx = contexts[i / perImage]
      let cell = CGRect(x: (i % perImage) * fw, y: 0, width: fw, height: fh)
      ctx.saveGState()
      ctx.clip(to: cell)
      let w = CGFloat(image.width), h = CGFloat(image.height)
      ctx.draw(image, in: CGRect(x: cell.midX - w / 2, y: cell.midY - h / 2, width: w, height: h))
      ctx.restoreGState()
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

    let quality = min(max(options.quality, 0), 1)
    var uris: [String] = []
    for ctx in contexts {
      guard let row = ctx.makeImage() else { throw MergeError.failed("Couldn't draw the filmstrip.") }
      let url = try Merge.outputURL("strip", extension: "jpg")
      guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
        throw MergeError.failed("Couldn't create the image file.")
      }
      CGImageDestinationAddImage(destination, row, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
      guard CGImageDestinationFinalize(destination) else {
        try? FileManager.default.removeItem(at: url)
        throw MergeError.failed("Couldn't write the image file.")
      }
      uris.append(url.absoluteString)
    }
    return Filmstrip(
      uris: uris, framesPerImage: Double(perImage), count: Double(count),
      frameWidth: Double(fw), frameHeight: Double(fh), intervalMs: interval)
  }
}
