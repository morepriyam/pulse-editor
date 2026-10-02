import AVFoundation
import ImageIO
import UniformTypeIdentifiers

/// One frame as a JPEG: `AVAssetImageGenerator` reading through a video composition built with
/// merge's own geometry (`MergeGeometry`), so an edited clip's cover is drawn exactly as the
/// export renders it, and HDR is tone-mapped to BT.709 the same way. The frame is the exact one at
/// the requested time (no tolerance), scaled to fit the maximum size, never up.
enum FrameGrab {
  static func run(_ uri: String, _ options: ThumbnailOptions) async throws -> Thumbnail {
    let media = try await Probe.load(fileURL(uri))
    guard let track = media.videoTrack else { throw MergeError.invalid("The file has no video.") }
    let (size, transform, range, frame) = try await track.load(
      .naturalSize, .preferredTransform, .timeRange, .minFrameDuration)

    let clip = MergeClip(
      uri: uri, startMs: 0, endMs: 0, speed: 1, muted: false,
      rotation: options.rotation, flipped: options.flipped, crop: options.crop)
    // The edited picture's size first, then the output: that size fitted into the maximum.
    let content = MergeGeometry(
      clip: clip, sourceSize: size, sourceTransform: transform,
      targetSize: size, targetTransform: .identity, canvas: size).contentSize
    let output = fitted(content, maxWidth: options.maxWidth, maxHeight: options.maxHeight)
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
    composition.frameDuration = frame.isNumeric && frame > .zero ? frame : CMTime(value: 1, timescale: 30)
    composition.colorPrimaries = AVVideoColorPrimaries_ITU_R_709_2
    composition.colorTransferFunction = AVVideoTransferFunction_ITU_R_709_2
    composition.colorYCbCrMatrix = AVVideoYCbCrMatrix_ITU_R_709_2

    let generator = AVAssetImageGenerator(asset: media.asset)
    generator.videoComposition = composition
    generator.requestedTimeToleranceBefore = .zero
    generator.requestedTimeToleranceAfter = .zero

    // Inside the video: a time past its last frame gives the last frame.
    let lastFrame = range.end - (frame.isNumeric && frame > .zero ? frame : CMTime(value: 1, timescale: 30))
    let wanted = CMTimeMaximum(range.start, CMTimeMinimum(
      range.start + CMTime(value: CMTimeValue(max(0, options.timeMs).rounded()), timescale: 1000), lastFrame))
    let image: CGImage
    var actual = CMTime.zero
    if #available(iOS 16, *) {
      (image, actual) = try await generator.image(at: wanted)
    } else {
      image = try generator.copyCGImage(at: wanted, actualTime: &actual)
    }

    let url = try Merge.outputURL("frame", extension: "jpg")
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
      throw MergeError.failed("Couldn't create the image file.")
    }
    let quality = min(max(options.quality, 0), 1)
    CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
    guard CGImageDestinationFinalize(destination) else {
      try? FileManager.default.removeItem(at: url)
      throw MergeError.failed("Couldn't write the image file.")
    }
    return Thumbnail(
      uri: url.absoluteString, width: Double(image.width), height: Double(image.height),
      timeMs: Probe.ms(actual - range.start))
  }

  /// `size` scaled down (never up) to fit inside maxWidth × maxHeight; 0 means no limit.
  static func fitted(_ size: CGSize, maxWidth: Double, maxHeight: Double) -> CGSize {
    var scale = 1.0
    if maxWidth > 0 { scale = min(scale, maxWidth / size.width) }
    if maxHeight > 0 { scale = min(scale, maxHeight / size.height) }
    return CGSize(width: max(1, (size.width * scale).rounded()), height: max(1, (size.height * scale).rounded()))
  }
}
